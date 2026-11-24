"""内存离散事件试验。仅接受线路快照，无业务 HTTP、数据库或 Redis 客户端。"""
import ast
import hashlib
import heapq
import itertools
import random
import re
import secrets
import threading
import time
from collections import Counter, defaultdict, deque
from datetime import datetime, timedelta
from pathlib import Path

from simulation.operation_config import RUN_WALL_LIMIT, save
from simulation.scenarios import SCENARIOS


def catalog(sql):
    """从版本化初始化 SQL 复制基础线路和课表，不执行 SQL。"""
    sql = Path(sql).read_text(encoding="utf-8")
    def rows(table):
        for block in re.findall(r"INSERT INTO `" + table + r"` \([^;]+?\) VALUES\s*(.*?);", sql, re.S):
            yield from ast.literal_eval("[" + block + "]")
    names = {int(number): name for number, name, _ in rows("line")}
    stations = {i: (name, short) for i, name, short, _, _ in rows("station")}
    routes = {}
    for line, station, direction, seq, distance, travel in rows("line_station"):
        key = f"{line}:{direction}"
        routes.setdefault(key, {"lineId": line, "direction": direction, "name": names[line], "stops": []})
        name, short = stations[station]
        routes[key]["stops"].append(dict(stationId=station, stationName=name, shortName=short, seq=seq,
                                          distanceM=distance, travelSec=travel))
    for route in routes.values():
        route["stops"].sort(key=lambda value: value["seq"])
    if len(routes) != 6 or any(len(r["stops"]) < 2 for r in routes.values()):
        raise ValueError("线路快照不完整")
    return routes, list(rows("course_schedule"))


def score(eta, load, capacity, running):
    # TaskService.vehicleCost：状态、负载、ETA 权重与生产相同。
    eta_cost = min(1., max(0, eta) / 1200.)
    return .4 * eta_cost + .4 * (load / capacity) + .2 * (0. if running else .5)


def route_eta(stops, start, target):
    # EtaService.estimateTravelSec：不使用乘客 ETA 的 60 秒展示缓冲。
    if start == target:
        return 0
    if start is None or target is None or start > target:
        return 600
    return sum(s["travelSec"] if s["travelSec"] is not None else 180 for s in stops[start:target])


def cusum_step(previous, rank, count):
    # Java 在线检测固定基线 2.0；FastAPI 演示的自适应基线不是正式派车规则。
    value = max(0., previous + count - 2. - 1.5)
    level = 3 if value >= 20 else 2 if value >= 10 else 1 if value >= 5 else 0
    return (0., 0, 3) if level == 3 and level > rank else (value, max(level, rank) if level else 0, level if level > rank else 0)


def demand(cfg, routes):
    rng = random.Random(cfg["seed"])
    preset = SCENARIOS.get(cfg["scenario"])
    keys = list(routes) if cfg["scenario"] == "all_day" else list(dict.fromkeys(
        (g["lineId"], g["direction"]) for g in cfg["fleet"]))
    if cfg["scenario"] == "all_day":
        keys = [(r["lineId"], r["direction"]) for r in routes.values()]
    window = cfg["minutes"] * 48
    if preset and preset.peak:
        window = min(window, preset.arrival_window_seconds)
    people = []
    for i in range(cfg["passengers"]):
        line, direction = rng.choice(keys)
        route = routes[f"{line}:{direction}"]["stops"]
        choices = range(len(route)-1)
        focus = preset.station_id if preset and (line, direction) == (preset.line_id, preset.direction) else None
        origin = next((j for j in choices if route[j]["stationId"] == focus), None)
        if origin is None:
            origin = rng.choice(choices)
        dest = rng.randint(origin+1, len(route)-1)
        mode = cfg["flow"]
        if mode == "uniform":
            second = i * window // cfg["passengers"]
        elif mode == "random":
            second = rng.randrange(window+1)
        elif mode == "burst":
            second = rng.randint(window//3, window//3 + min(60, window//2))
        else:
            second = max(0, min(window, int(rng.gauss(window*rng.choice((.14, .42, .68)), max(2, window*.035)))))
        people.append(dict(number=i+1, lineId=line, direction=direction, stationId=route[origin]["stationId"],
                           destStationId=route[dest]["stationId"], arrival=second, origin=origin, dest=dest,
                           maxWait=rng.randint(max(1, cfg["wait"]*2//3), cfg["wait"])*60))
    return people


class Experiment:
    def __init__(self, cfg, routes, courses, archive):
        self.config, self.routes, self.courses, self.archive = cfg, routes, courses, archive
        self.id, self.start = secrets.token_hex(6), datetime.fromisoformat(cfg["start"])
        self.end = cfg["minutes"] * 60
        self.now, self.phase, self.error = 0, "preparing", ""
        self.cv, self.commands = threading.Condition(), deque()
        self.heap, self.serial = [], itertools.count()
        self.fleet, self.trips, self.passengers = {}, {}, {}
        self.queue, self.samples = defaultdict(deque), Counter()
        self.cusum, self.levels = {}, {}
        self.course_issued = set()
        self.tasks, self.alerts, self.steps = [], [], []
        self.waits, self.max_queue, self.seat_seconds, self.load_seconds = [], 0, 0, 0
        self.rejected = False
        self.plan = demand(cfg, routes)
        self.snapshot = {"enabled": True, "isolatedExperiment": True, "phase": self.phase}
        for person in self.plan:
            self.put(person["arrival"], "checkin", person)
        for stamp in range(60, self.end+1, 60):
            self.put(stamp, "minute", None)
        for group in cfg["fleet"]:
            route = self.stops(f"{group['lineId']}:{group['direction']}")
            origin = next((i for i, s in enumerate(route[:-1]) if s["stationId"] == group["initialStation"]), None)
            if group["initialStation"] and origin is None:
                raise ValueError("初始站不在所选方向或为终点")
            for i in range(group["count"]):
                n = len(self.fleet)+1
                car = dict(group, id=n, plate=f"实验-{n:02d}", phase="ready", index=origin or 0,
                           people=[len(route)-1]*group["initialLoad"], tripId=None, task=None,
                           route=f"{group['lineId']}:{group['direction']}", version=0,
                           due=i*group["headway"])
                self.fleet[n] = car
                self.vehicle_event(car["due"], "arrive", car)

    def stops(self, key):
        return self.routes[key]["stops"]

    def stamp(self):
        return (self.start+timedelta(seconds=self.now)).isoformat()

    def put(self, second, kind, data):
        if second <= self.end:
            heapq.heappush(self.heap, (second, next(self.serial), kind, data))

    def vehicle_event(self, second, kind, car):
        self.put(second, kind, (car["id"], car["version"]))

    def travel(self, car, index):
        return car["travel"] or self.stops(car["route"])[index]["travelSec"] or 180

    def change(self, task, action, car=None, reason=None):
        task.update(status=action)
        if car:
            task["vehicleId"] = car["id"]
        self.steps.append(dict(taskId=task["id"], action=action, time=self.stamp(),
                               vehicleId=car["id"] if car else None, reason=reason or task["reason"]))

    def trigger(self, key, index, level, reason):
        route = self.routes[key]
        sid = route["stops"][index]["stationId"]
        self.alerts.append(dict(level=level, message=f"{route['name']} / 站点 {sid} / {reason}", createdAt=self.stamp()))
        if level == "yellow":
            return
        # 与 TaskService 一致，同线路/方向/站点/起因仅保留一个未结束任务。
        if any(t["key"] == key and t["index"] == index and t["reason"] == reason and
               t["status"] not in ("completed", "escalated") for t in self.tasks):
            return
        task = dict(id=len(self.tasks)+1, key=key, index=index, reason=reason, created=self.now)
        self.tasks.append(task)
        self.change(task, "created")
        excluded = None
        while True:
            options = []
            for car in self.fleet.values():
                running = car["phase"] in ("dwell", "moving")
                if (car["route"] != key or car["task"] is not None or car["id"] == excluded or
                        car["phase"] not in ("ready", "dwell", "moving") or car["index"] > index or
                        (car["phase"] == "moving" and car["index"] == index)):
                    continue
                eta = route_eta(route["stops"], car["index"], index)
                options.append((score(eta, len(car["people"]), car["capacity"], running), car["id"], car))
            if not options:
                self.change(task, "escalated", reason="没有可达且未占用的同向车辆")
                return
            cost, _, car = min(options)
            self.change(task, "reassigned" if excluded else "assigned", car, f"{reason} · 综合成本 {cost:.3f}")
            if self.config["reject"] and not self.rejected:
                self.rejected = True
                excluded = car["id"]
                self.change(task, "rejected", car)
                continue
            self.change(task, "accepted", car)
            car["task"] = task
            if car["phase"] == "ready" and car["due"] > self.now:
                car["version"] += 1
                car["due"] = self.now
                self.vehicle_event(self.now, "arrive", car)
            self.change(task, "executing", car)
            return

    def surge(self, key, index):
        n = len(self.queue[key, index])
        self.max_queue = max(n, self.max_queue)
        if self.config["policy"] == "fixed":
            return
        level = 3 if n >= 25 and self.config["policy"] == "smart" else 2 if n >= 15 else 1 if n >= 8 else 0
        old = self.levels.get((key, index), 0)
        self.levels[key, index] = level
        if level > old:
            self.trigger(key, index, ("", "yellow", "orange", "red")[level],
                         ("", "YELLOW_SURGE", "ORANGE_SURGE", "RED_SURGE")[level])

    def minute(self):
        if self.config["policy"] != "smart":
            return
        for key, index in sorted(self.cusum.keys() | self.samples.keys()):
            prev, rank = self.cusum.get((key, index), (0., 0))
            value, rank, emit = cusum_step(prev, rank, self.samples[key, index])
            self.cusum[key, index] = value, rank
            if emit:
                level = ("", "yellow", "orange", "red")[emit]
                self.trigger(key, index, level, "CUSUM_"+level.upper())
        self.samples.clear()

    def course(self):
        if self.config["policy"] != "smart":
            return
        clock = (self.start + timedelta(seconds=self.now))
        for _, end, size, station, rate, weekday in self.courses:
            end_at = datetime.combine(clock.date(), datetime.strptime(end, "%H:%M:%S").time())
            delta = int((end_at-clock).total_seconds()/60)
            if weekday not in (0, clock.isoweekday()) or not 0 <= delta <= 10 or int(size*rate) < 8:
                continue
            for key, route in self.routes.items():
                for i, stop in enumerate(route["stops"][:-1]):
                    item = (clock.date(), end, key, i)
                    if stop["stationId"] == station and item not in self.course_issued:
                        self.course_issued.add(item)
                        self.trigger(key, i, "prior", "COURSE_PRE")

    def arrive(self, car):
        key, i = car["route"], car["index"]
        stops = self.stops(key)
        if not car["tripId"]:
            trip = dict(id=f"{self.id}-{len(self.trips)+1}", plate=car["plate"], lineId=car["lineId"],
                        direction=car["direction"], capacity=car["capacity"], status="running", stops=[])
            planned, distance = self.now, 0
            for j, stop in enumerate(stops):
                trip["stops"].append(dict(stationId=stop["stationId"], name=stop["stationName"], distance=distance,
                                          arrived=None, departed=None, planned=planned if j >= i else None,
                                          alighted=0, boarded=0, load=None, waiting=None))
                distance += stop["distanceM"]
                if j >= i:
                    planned += self.travel(car, j)+car["dwell"]
            self.trips[trip["id"]] = trip
            car["tripId"] = trip["id"]
        row = self.trips[car["tripId"]]["stops"][i]
        row["arrived"], car["phase"] = self.now, "dwell"
        retained = []
        for person in car["people"]:
            dest = person if isinstance(person, int) else person["dest"]
            if dest == i:
                row["alighted"] += 1
                if isinstance(person, dict):
                    person.update(status="alighted", alightedAt=self.now)
            else:
                retained.append(person)
        car["people"] = retained
        line = self.queue[key, i]
        while line and len(retained) < car["capacity"]:
            person = line.popleft()
            person.update(status="boarded", boardedAt=self.now, tripId=car["tripId"])
            self.waits.append(self.now-person["arrival"])
            retained.append(person)
            row["boarded"] += 1
        self.surge(key, i)
        if car["task"] and car["task"]["index"] == i:
            self.change(car["task"], "completed", car)
            car["task"] = None
        self.course()
        self.vehicle_event(self.now+car["dwell"], "depart", car)

    def depart(self, car):
        key, i = car["route"], car["index"]
        trip = self.trips[car["tripId"]]
        row = trip["stops"][i]
        row.update(departed=self.now, load=len(car["people"]), waiting=len(self.queue[key, i]))
        if i == len(trip["stops"])-1:
            trip["status"] = "completed"
            car["tripId"] = None
            car["direction"] = "back" if car["direction"] == "go" else "go"
            car.update(index=0, phase="ready", route=f"{car['lineId']}:{car['direction']}",
                       due=self.now+car["headway"])
            self.vehicle_event(car["due"], "arrive", car)
        else:
            length = self.travel(car, i)
            effective = min(length, self.end-self.now)
            self.seat_seconds += effective*car["capacity"]
            self.load_seconds += effective*len(car["people"])
            car.update(phase="moving", moveStart=self.now, moveEnd=self.now+length)
            self.vehicle_event(self.now+length, "advance", car)

    def intervene(self, action, vehicle):
        car = self.fleet[vehicle]
        # 不在站间中断仍载人的区间，避免把虚拟乘客瞬移出车。
        if action == "restore":
            if car["phase"] not in ("fault", "withdrawn"):
                return
            car.update(phase="ready", due=self.now)
            self.vehicle_event(self.now, "arrive", car)
        elif car["phase"] == "ready":
            car["version"] += 1
            car["phase"] = "fault" if action == "fault" else "withdrawn"

    def publish(self, final=False):
        vehicles = []
        for c in self.fleet.values():
            i, route = c["index"], self.stops(c["route"])
            moving = c["phase"] == "moving"
            remaining = c["moveEnd"]-self.now if moving else None
            progress = 1-remaining/(c["moveEnd"]-c["moveStart"]) if moving else 0
            next_eta = remaining if moving else (self.travel(c,i) if i+1<len(route) and c["phase"] == "dwell" else None)
            vehicles.append({**{k:c[k] for k in ("id","plate","lineId","direction","phase","capacity","index","tripId")},
                             "load":len(c["people"]), "progress":progress, "nextEta":next_eta,
                             "remainingDistance":round(route[i]["distanceM"]*(1-progress)) if next_eta is not None else None,
                             "terminalEta": self.now+next_eta+sum(self.travel(c,j)+c["dwell"] for j in range(i+1,len(route)-1)) if next_eta is not None else None,
                             "predictions":[]})
        counts = Counter(p["status"] for p in self.passengers.values())
        waits = sorted(self.waits)
        metrics = dict(checkins=len(self.passengers), boarded=len(waits), waiting=counts["waiting"],
                       cancelled=counts["cancelled"], alighted=counts["alighted"],
                       averageWait=round(sum(waits)/len(waits),1) if waits else None,
                       p95Wait=waits[(95*len(waits)+99)//100-1] if waits else None, peakWaiting=self.max_queue,
                       occupiedSeatSeconds=self.load_seconds, offeredSeatSeconds=self.seat_seconds,
                       seatUtilization=round(self.load_seconds/self.seat_seconds,4) if self.seat_seconds else None,
                       tasksCompleted=sum(t["status"]=="completed" for t in self.tasks))
        queues = {f"{key}:{self.stops(key)[i]['stationId']}":len(q) for (key,i),q in self.queue.items() if q}
        # HTTP 序列化线程只能读取快照，不能读仍在变化的旅程和任务对象。
        import copy
        self.snapshot = copy.deepcopy(dict(enabled=True, isolatedExperiment=True, source="memory_experiment",
            runId=self.id, config=self.config, second=self.now, phase=self.phase, error=self.error,
            metrics=metrics, vehicles=vehicles, queues=queues, routes=self.routes, trips=self.trips,
            alerts=self.alerts, tasks=self.tasks, taskHistory=self.steps))
        if final and self.archive:
            import json
            trace_hash = hashlib.sha256(json.dumps(self.plan, sort_keys=True).encode()).hexdigest()
            save(self.archive/(self.id+".json"), {**self.snapshot, "demandPlan": self.plan, "traceSha256": trace_hash,
                                                 "passengers":list(self.passengers.values())})
            save(self.archive/(self.id+".summary.json"), {k:self.snapshot[k] for k in ("runId","phase","metrics","config")})

    def run(self, realtime=True):
        deadline = time.monotonic()+RUN_WALL_LIMIT
        with self.cv:
            if self.phase == "preparing":
                self.phase = "running"
        try:
            while self.heap:
                with self.cv:
                    if self.phase == "paused":
                        self.cv.wait_for(lambda: self.phase != "paused", timeout=max(0, deadline-time.monotonic()))
                    if self.phase == "stopping" or time.monotonic() >= deadline:
                        self.phase = "stopped"
                        break
                    while self.commands:
                        self.intervene(*self.commands.popleft())
                    next_second = self.heap[0][0]
                    if realtime and self.cv.wait(timeout=(next_second-self.now)/self.config["speed"]):
                        continue
                    self.now, _, kind, data = heapq.heappop(self.heap)
                if kind in ("arrive","advance","depart"):
                    vehicle, version = data
                    car = self.fleet[vehicle]
                    if version == car["version"]:
                        if kind == "advance":
                            car["index"] += 1
                        (self.depart if kind == "depart" else self.arrive)(car)
                elif kind == "minute":
                    self.minute()
                else:
                    key, idx = f"{data['lineId']}:{data['direction']}", data["origin"]
                    if kind == "checkin":
                        person = dict(data, status="waiting")
                        self.passengers[data["number"]] = person
                        self.queue[key, idx].append(person)
                        self.samples[key, idx] += 1
                        self.put(self.now+data["maxWait"], "timeout", data)
                        self.surge(key, idx)
                    else:
                        person = self.passengers[data["number"]]
                        if person["status"] == "waiting":
                            self.queue[key, idx].remove(person)
                            person["status"] = "cancelled"
                            self.surge(key, idx)
                if realtime and self.now != self.snapshot.get("second"):
                    self.publish()
            if self.phase not in ("stopping", "stopped"):
                self.phase = "completed"
                self.now = self.end
            else:
                self.phase = "stopped"
        except Exception:
            self.phase, self.error = "error", "实验执行失败，请检查配置和本机日志"
            raise
        finally:
            self.publish(final=True)
