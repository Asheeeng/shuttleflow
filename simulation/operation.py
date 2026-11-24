"""单线程离散事件调度器。所有载客、队列和任务变化由已认证的业务API执行。"""
from __future__ import annotations

import heapq
import itertools
import json
import os
import random
import re
import secrets
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path

from simulation.api_client import ShuttleApi
from simulation.scenarios import SCENARIOS
from simulation.operation_config import ACTIVE, RUN_WALL_LIMIT, configuration, encode, save

BASE = "http://127.0.0.1:18080"
ARCHIVE = Path(os.environ.get("SHUTTLE_V13_RUN_DIR", "/tmp")) / "operation"

def worker_api():
    client = ShuttleApi(BASE, timeout=12, secure_loopback=os.environ.get("SHUTTLE_SESSION_COOKIE_SECURE") == "true")
    client.operation_token = os.environ["SHUTTLE_OPERATION_TOKEN"]
    return client




class Run:
    def __init__(self, config):
        self.config = config
        self.id = secrets.token_hex(6)
        self.start = datetime.fromisoformat(config["start"])
        self.end = config["minutes"] * 60
        self.now = 0
        self.phase = "preparing"
        self.error = ""
        self.heap = []
        self.seq = itertools.count()
        self.fleet, self.trips, self.actors = {}, {}, {}
        self.routes, self.people = {}, {}
        self.plan = []
        self.projected_events = 0
        self.commands = []
        self.cv = threading.Condition()
        self.snapshot = {"runId": self.id, "phase": self.phase, "config": config, "enabled": True}
        self.admin = worker_api()
        self.admin.operation_token = os.environ["SHUTTLE_OPERATION_TOKEN"]
        self.ledger = {"requests": [], "alerts": [], "tasks": [], "events": [], "taskHistory": []}
        self.floors = {}
        self.rejected = False
        self.interventions = []
        self.deadline = time.monotonic() + RUN_WALL_LIMIT
        self.owns_checkpoint = False

    def put(self, moment, kind, value=None):
        if moment <= self.end:
            heapq.heappush(self.heap, (moment, next(self.seq), kind, value))

    def clock(self, action="advance", **fields):
        return self.admin.post("/api/admin/operation/clock", {
            "action": action, "time": (self.start + timedelta(seconds=self.now)).isoformat(),
            "policy": self.config["policy"], **fields}).data

    def recover_interrupted(self):
        pending = ARCHIVE / "pending.json"
        if not pending.is_file():
            return
        saved = json.loads(pending.read_text())
        run_id = saved["runId"]
        if not re.fullmatch(r"[a-f0-9]{12}", run_id):
            raise ValueError("上次运行标识无效")
        self.floors = saved["floors"]
        self.clock("clear")
        self.clock("scope", requestAfter=str(self.floors["requestAfter"]))
        self.clock()
        prefix = "S" + run_id[:6]
        self.fleet = {vehicle["id"]: {"phase": "withdrawn", "tripId": None}
                      for vehicle in self.admin.get("/api/admin/vehicles").data
                      if vehicle["plateNo"].startswith(prefix)}
        self.cleanup()
        pending.unlink()
        self.fleet.clear()
        self.event_cache.clear()

    def check_deadline(self):
        if time.monotonic() >= self.deadline:
            self.phase = "stopping"
            self.error = "达到本次运行的墙钟时限，正在清理"
            return True
        return False

    def setup(self):
        self.admin.login_admin(os.environ["SHUTTLE_TEST_ADMIN_USERNAME"], os.environ["SHUTTLE_TEST_ADMIN_PASSWORD"])
        self.recover_interrupted()
        self.floors = self.admin.get("/api/admin/operation/cursors").data
        save(ARCHIVE / "pending.json", {"runId": self.id, "floors": self.floors})
        self.owns_checkpoint = True
        self.clock("clear")
        self.clock("scope", requestAfter=str(self.floors["requestAfter"]))
        self.clock()
        self.clock("reset-detector")
        for line in self.admin.get("/api/lines").data:
            lid = line["id"]
            for direction in ("go", "back"):
                stops = self.admin.get(f"/api/line/{lid}/stations?direction={direction}").data
                self.routes[f"{lid}:{direction}"] = {"lineId": lid, "name": line["name"],
                                                      "direction": direction, "stops": stops}
        rng = random.Random(self.config["seed"])
        preset = SCENARIOS.get(self.config["scenario"])
        groups = self.config["fleet"]
        for group in groups:
            route = self.route(group["lineId"], group["direction"])
            if group["initialStation"] and group["initialStation"] not in {s["stationId"] for s in route[:-1]}:
                raise ValueError("初始站不属于该方向或已经是终点")
        demand_routes = (list(self.routes.values()) if self.config["scenario"] == "all_day" else
                         [self.routes[key] for key in dict.fromkeys(f"{g['lineId']}:{g['direction']}" for g in groups)])
        # 运营尾段留给在途车辆和候车乘客。全天预设覆盖整日的波次。
        demand_window = int(self.end * 0.80)
        if preset and preset.peak:
            demand_window = min(demand_window, preset.arrival_window_seconds)
        for i in range(self.config["passengers"]):
            chosen = rng.choice(demand_routes)
            stops = chosen["stops"]
            possible = list(range(len(stops)-1))
            origin = next((j for j in possible if preset and chosen["lineId"] == preset.line_id
                           and chosen["direction"] == preset.direction and stops[j]["stationId"] == preset.station_id), None)
            if origin is None:
                origin = rng.choice(possible)
            dest = rng.randint(origin+1, len(stops)-1)
            flow = self.config["flow"]
            if flow == "uniform":
                stamp = i * demand_window // self.config["passengers"]
            elif flow == "random":
                stamp = rng.randint(0, demand_window)
            elif flow == "burst":
                stamp = rng.randint(demand_window // 3, max(demand_window // 3, demand_window // 3 + min(60, demand_window // 2)))
            else:
                center = rng.choice((.14, .42, .68))
                stamp = max(0, min(demand_window, int(rng.gauss(demand_window * center, max(2, demand_window * .035)))))
            wait = rng.randint(max(1, self.config["wait"] * 2 // 3), self.config["wait"]) * 60
            profile = {"number": i+1, "lineId": chosen["lineId"], "direction": chosen["direction"],
                       "stationId": stops[origin]["stationId"], "destStationId": stops[dest]["stationId"],
                       "arrival": stamp, "maxWait": wait}
            self.plan.append(profile)
            self.put(stamp, "checkin", profile)

        for group in groups:
            for index in range(group["count"]):
                if self.phase == "stopping" or self.check_deadline():
                    return
                self.provision(group, index)
        for moment in range(0, self.end+1, 60):
            self.put(moment, "minute")
        self.put(self.end, "end")

    def provision(self, group, index):
        plate = "S" + self.id[:6] + f"{len(self.fleet)+1:02d}"
        vehicle = self.admin.post("/api/admin/operation/fleet", {
            "plate": plate, "lineId": group["lineId"], "capacity": group["capacity"]}).data
        vid = vehicle["id"]
        car = {**group, "id": vid, "plate": plate, "load": 0, "tripId": None, "phase": "ready",
               "manualDriver": self.config["pattern"] == "mixed" and index >= group["count"] - group["manual"],
               "generation": 0, "index": 0, "initialIndex": 0, "cycles": 0}
        self.fleet[vid] = car
        phone = "196" + str(secrets.randbelow(10**13)).zfill(13)
        password = secrets.token_urlsafe(18)
        self.admin.post("/api/admin/driver/create", {"name": f"仿真司机 {plate}", "phone": phone,
                                                      "password": password, "vehicleId": vid})
        driver = worker_api()
        driver.login_driver(phone, password)
        self.actors[vid] = driver
        if car["manualDriver"]:
            car["credentials"] = {"phone": phone, "password": password}
        stops = self.route(car["lineId"], car["direction"])
        car["initialIndex"] = next((i for i, stop in enumerate(stops) if stop["stationId"] == car["initialStation"]), 0)
        self.admin.post("/api/admin/vehicle/set-running", {"vehicleId": vid, "running": True})
        self.begin_trip(car, car["initialIndex"], plan_at=index * group["headway"])
        self.driver_event(car, "arrive")
        if car["initialLoad"]:
            car["load"] = driver.post_driver_event("/api/driver/calibrate", {
                "vehicleId": vid, "afterLoad": car["initialLoad"]}).data["currentLoad"]
        if car["manualDriver"]:
            car["phase"] = "manual"
        elif index == 0 or self.config["policy"] == "fixed":
            self.vehicle_event(index * group["headway"], "arrive", car)
        else:
            car["phase"] = "reserve"

    def route(self, line, direction):
        return self.routes[f"{line}:{direction}"]["stops"]

    def begin_trip(self, car, index, plan_at):
        car["cycles"] += 1
        trip_id = f"{self.id}-{car['id']}-{car['cycles']}"
        car["tripId"], car["index"] = trip_id, index
        self.clock("bind", vehicleId=str(car["id"]), tripId=trip_id)
        route = self.route(car["lineId"], car["direction"])
        table, arc = [], 0
        for i, stop in enumerate(route):
            table.append({"stationId": stop["stationId"], "name": stop["stationName"],
                          "distance": arc, "segmentDistance": stop["distanceM"],
                          "planned": plan_at if i >= index else None, "arrived": None,
                          "departed": None, "boarded": 0, "alighted": 0, "load": None, "waiting": None})
            if i >= index:
                plan_at += self.travel(car, stop) + car["dwell"]
            arc += stop["distanceM"] or 0
        self.trips[trip_id] = {"id": trip_id, "lineId": car["lineId"], "direction": car["direction"],
                               "plate": car["plate"], "vehicleId": car["id"], "capacity": car["capacity"],
                               "stops": table, "status": "active", "initialLoad": car["load"]}

    @staticmethod
    def travel(car, stop):
        return car["travel"] or stop["travelSec"] or 180

    def vehicle_event(self, moment, kind, car):
        car["nextActivity"] = moment
        self.put(moment, kind, (car["id"], car["generation"]))

    def driver_event(self, car, kind, **fields):
        stop = self.route(car["lineId"], car["direction"])[car["index"]]
        result = self.actors[car["id"]].post_driver_event("/api/driver/" + kind, {
            "vehicleId": car["id"], "stationId": stop["stationId"], "lineId": car["lineId"],
            "direction": car["direction"], **fields}).data
        car["load"] = result["currentLoad"]
        return result

    def checkin(self, person):
        actor = worker_api()
        actor.start_guest()
        phone = "195" + str(secrets.randbelow(10**13)).zfill(13)
        account = actor.post("/api/passenger/register", {
            "name": f"仿真乘客 {person['number']:03d}", "phone": phone,
            "password": secrets.token_urlsafe(18)}).data
        actor.csrf_token = account["csrfToken"]
        result = actor.post("/api/passenger/checkin", {k: person[k] for k in
                            ("lineId", "direction", "stationId", "destStationId")}).data["request"]
        self.people[result["id"]] = (actor, person)
        self.put(self.now + person["maxWait"], "expire", result["id"])

    def expire(self, request_id):
        actor = self.people[request_id][0]
        response = actor.request("POST", "/api/passenger/cancel", {"requestId": request_id})
        if response.code not in {200, 409}:
            raise RuntimeError(f"取消候车失败，业务码 {response.code}")

    def update_tasks(self):
        for task in self.ledger["tasks"]:
            vid = task.get("vehicleId")
            car = self.fleet.get(vid)
            if task["status"] != "assigned" or not car or car["manualDriver"]:
                continue
            driver = self.actors[vid]
            body = {"taskId": task["id"], "vehicleId": vid}
            if self.config["reject"] and not self.rejected:
                self.rejected = True
                driver.post("/api/driver/task/reject", {**body, "reason": "仿真预设司机拒单"})
                self.read()
                return self.update_tasks()
            driver.post("/api/driver/task/accept", body)
            if car["phase"] == "reserve":
                car["phase"] = "ready"
                self.vehicle_event(self.now, "arrive", car)

    def at_stop(self, kind, car):
        route = self.route(car["lineId"], car["direction"])
        i = car["index"]
        stop = route[i]
        station = stop["stationId"]
        trip = self.trips[car["tripId"]]
        row = trip["stops"][i]
        if kind == "arrive":
            self.driver_event(car, "arrive")
            car["phase"] = "dwell"
            self.read()
            final = i == len(route)-1
            due = sum(r.get("tripId") == trip["id"] and not r.get("alightedAt")
                      and r.get("destStationId") == station for r in self.ledger["requests"])
            count = car["load"] if final else min(due, car["load"])
            if count:
                self.driver_event(car, "alight", count=count)
            self.vehicle_event(self.now + car["dwell"], "depart", car)
            return
        self.read()
        waiting = sum(r["status"] == "waiting" and r["stationId"] == station and
                      r["lineId"] == car["lineId"] and r["direction"] == car["direction"]
                      for r in self.ledger["requests"])
        row["waiting"] = waiting
        board = min(waiting, car["capacity"]-car["load"]) if i < len(route)-1 else 0
        if board:
            self.driver_event(car, "board", count=board)
        self.driver_event(car, "depart")
        for task in self.ledger["tasks"]:
            if task.get("vehicleId") == car["id"] and task["status"] == "executing" and task["stationId"] == station:
                self.actors[car["id"]].post("/api/driver/task/complete", {"taskId": task["id"]})
        if i == len(route)-1:
            trip["status"] = "completed"
            previous_direction = car["direction"]
            car["direction"] = "back" if previous_direction == "go" else "go"
            self.begin_trip(car, 0, self.now + car["headway"])
            self.driver_event(car, "arrive")
            car["phase"] = "ready"
            self.vehicle_event(self.now + car["headway"], "arrive", car)
        else:
            travel = self.travel(car, stop)
            car.update(phase="moving", fromIndex=i, moveStart=self.now, moveEnd=self.now+travel)
            car["index"] += 1
            self.vehicle_event(self.now + travel, "arrive", car)

    def intervene(self, command):
        vid = command["vehicleId"]
        car = self.fleet[vid]
        action = command["action"]
        if action == "restore":
            if car["phase"] not in {"fault", "withdrawn"}:
                return
            self.admin.post("/api/admin/vehicle/set-running", {"vehicleId": vid, "running": True})
            car["phase"] = "manual" if car["manualDriver"] else "ready"
            if not car["manualDriver"]:
                self.vehicle_event(self.now, "arrive", car)
        elif action in {"fault", "withdraw"}:
            if car["phase"] in {"fault", "withdrawn"}:
                return
            self.read()
            for task in self.ledger["tasks"]:
                if task.get("vehicleId") == vid and task["status"] in {"assigned", "executing"}:
                    self.admin.post("/api/admin/operation/release", {"taskId": task["id"]})
            self.admin.post("/api/admin/vehicle/set-running", {"vehicleId": vid, "running": False})
            car["generation"] += 1
            car["phase"] = "fault" if action == "fault" else "withdrawn"
        self.interventions.append({"second": self.now, "vehicleId": vid, "action": action})

    def virtual_seconds(self, value):
        return int((datetime.fromisoformat(value) - self.start).total_seconds()) if value else None

    def publish(self, final=False):
        ledger = self.read()
        requests = ledger["requests"]
        # Stop counts, actual times and loads come exclusively from committed driver events.
        for event in itertools.islice(self.event_cache.values(), self.projected_events, None):
            trip = self.trips.get(event.get("tripId"))
            if not trip:
                continue
            row = next((stop for stop in trip["stops"] if stop["stationId"] == event.get("stationId")), None)
            if row is None:
                continue
            kind = event["type"]
            stamp = self.virtual_seconds(event["eventTime"])
            if kind == "arrive":
                row["arrived"] = stamp
            elif kind == "depart":
                row["departed"] = stamp
                row["waiting"] = event.get("waitingAtDeparture", row["waiting"])
            elif kind == "board":
                row["boarded"] += event["count"]
            elif kind == "alight":
                row["alighted"] += abs(event["count"])
            if event.get("afterLoad") is not None:
                row["load"] = event["afterLoad"]
        self.projected_events = len(self.event_cache)
        grouped = {}
        for row in requests:
            if row["status"] == "waiting":
                key = f"{row['lineId']}:{row['direction']}:{row['stationId']}"
                grouped[key] = grouped.get(key, 0) + 1
        car_rows = []
        latest = {v["id"]: v for v in self.admin.get("/api/admin/vehicles").data}
        for vid, car in self.fleet.items():
            trip = self.trips[car["tripId"]] if car["tripId"] else None
            if not trip:
                continue
            v = latest[vid]
            car["load"] = v["currentLoad"]
            route = self.route(car["lineId"], car["direction"])
            moving = car["phase"] == "moving"
            index = car.get("fromIndex", 0) if moving else car["index"]
            if car["manualDriver"]:
                index = next((i for i, stop in enumerate(route) if stop["stationId"] == v.get("currentStationId")), index)
                if index == len(route)-1 and trip["stops"][index]["departed"] is not None:
                    trip["status"] = "completed"
            fraction = min(1, max(0, (self.now-car["moveStart"]) / (car["moveEnd"]-car["moveStart"]))) if moving else 0
            remaining = max(0, car["moveEnd"]-self.now) if moving else 0
            next_index = index+1
            eta = None
            if next_index < len(route):
                stop_wait = max(0, car.get("nextActivity", self.now)-self.now)
                eta = remaining if moving else self.travel(car, route[index]) + stop_wait
                if car["phase"] == "ready":
                    eta += car["dwell"]
            if car["phase"] in {"manual", "fault", "withdrawn", "reserve"} and not moving:
                eta = None
            terminal = None if eta is None else self.now + eta + sum(
                self.travel(car, stop)+car["dwell"] for stop in route[next_index:-1])
            downstream = []
            predicted = car["load"]
            for stop in route[index if not moving else next_index:]:
                sid = stop["stationId"]
                predicted_off = sum(r.get("tripId") == trip["id"] and not r.get("alightedAt")
                                    and r.get("destStationId") == sid for r in requests)
                predicted_off = min(predicted, predicted_off)
                predicted -= predicted_off
                demand = grouped.get(f"{car['lineId']}:{car['direction']}:{sid}", 0)
                predicted_on = min(demand, car["capacity"] - predicted)
                predicted += predicted_on
                downstream.append({"stationId": sid, "name": stop["stationName"], "alight": predicted_off,
                                   "board": predicted_on, "load": predicted, "seats": car["capacity"]-predicted})
            car_rows.append({"id": vid, "plate": car["plate"], "lineId": car["lineId"], "direction": car["direction"],
                             "tripId": trip["id"], "manual": car["manualDriver"], "phase": car["phase"],
                             "capacity": car["capacity"], "load": car["load"], "index": index, "progress": fraction,
                             "moveStart": car.get("moveStart") if moving else None,
                             "moveEnd": car.get("moveEnd") if moving else None,
                             "nextEta": eta, "terminalEta": terminal, "driverStatus": v["status"],
                             "remainingDistance": (route[index]["distanceM"] or 0)*(1-fraction) if eta is not None else None,
                             "predictions": downstream})
        waits = [(datetime.fromisoformat(r["boardedAt"])-datetime.fromisoformat(r["createdAt"])).total_seconds()
                 for r in requests if r.get("boardedAt")]
        metrics = {"checkins": len(requests), "boarded": len(waits),
                   "waiting": sum(r["status"] == "waiting" for r in requests),
                   "cancelled": sum(r["status"] == "cancelled" for r in requests),
                   "alighted": sum(bool(r.get("alightedAt")) for r in requests),
                   "averageWait": round(sum(waits)/len(waits), 1) if waits else None}
        self.snapshot = json.loads(encode({
            "enabled": True, "runId": self.id, "source": "simulation", "phase": self.phase,
            "second": self.now, "config": self.config, "metrics": metrics, "vehicles": car_rows,
            "routes": self.routes, "queues": grouped, "trips": self.trips,
            "alerts": ledger["alerts"], "tasks": ledger["tasks"], "taskHistory": ledger["taskHistory"],
            "error": self.error, "interventions": self.interventions}))
        if final:
            profiles = {rid: person for rid, (_, person) in self.people.items()}
            passengers = [{**r, **({"profile": profiles[r["id"]]} if r["id"] in profiles else {})} for r in requests]
            save(ARCHIVE / (self.id + ".json"), {**self.snapshot, "demandPlan": self.plan, "passengers": passengers,
                                                "events": list(self.event_cache.values())})
            save(ARCHIVE / (self.id + ".summary.json"), {key: self.snapshot[key] for key in
                                                       ("runId", "phase", "metrics", "config")})
        self.clock("refresh")

    def cleanup(self):
        self.read()
        waiting = {row["id"] for row in self.ledger["requests"] if row["status"] == "waiting"}
        for rid in waiting & self.people.keys():
            actor, _ = self.people[rid]
            try:
                actor.post("/api/passenger/cancel", {"requestId": rid})
            except Exception:
                continue
        if self.floors and any(row["status"] == "waiting" for row in self.read()["requests"]):
            self.admin.post("/api/admin/operation/clear-waiting", {"requestAfter": self.floors["requestAfter"]})
            self.read()
        for alert in self.ledger["alerts"]:
            self.admin.post("/api/admin/alert/resolve", {"id": alert["id"]})
        for task in self.ledger["tasks"]:
            if task["status"] in {"assigned", "pending", "executing"}:
                self.admin.post("/api/admin/operation/release", {"taskId": task["id"]})
        for vid, car in self.fleet.items():
            self.admin.post("/api/admin/vehicle/set-running", {"vehicleId": vid, "running": False})
            if car["phase"] not in {"fault", "withdrawn"}:
                car["phase"] = "stopped"
            trip = self.trips.get(car["tripId"])
            if trip and trip["status"] == "active":
                trip["status"] = "interrupted"

    def run(self):
        self.event_cache = {}
        try:
            self.setup()
            if self.phase == "preparing":
                self.phase = "running"
            self.publish()
            wall = time.monotonic()
            previous = 0
            while self.heap and self.phase != "stopping" and not self.check_deadline():
                moment = self.heap[0][0]
                delay = max(0, (moment-previous)/self.config["speed"] - (time.monotonic()-wall))
                with self.cv:
                    if self.phase == "paused" and not self.commands:
                        self.cv.wait(1)
                        wall = time.monotonic()
                        if self.phase == "paused":
                            self.publish()
                        continue
                    if self.commands:
                        command = self.commands.pop(0)
                        self.intervene(command)
                        self.publish()
                        continue
                    if delay and self.phase == "running":
                        self.cv.wait(min(delay, 1))
                        continue
                    if self.phase != "running":
                        break
                    self.now = moment
                self.clock()
                while self.heap and self.heap[0][0] == moment and self.phase == "running" and not self.check_deadline():
                    _, _, kind, value = heapq.heappop(self.heap)
                    if kind == "checkin":
                        self.checkin(value)
                    elif kind == "expire":
                        self.read()
                        if any(r["id"] == value and r["status"] == "waiting" for r in self.ledger["requests"]):
                            self.expire(value)
                    elif kind == "minute":
                        if self.now and self.config["policy"] == "smart":
                            self.admin.post("/api/admin/simulation/cusum/tick", {"intervalSeconds": 60})
                        self.read()
                    elif kind != "end":
                        vid, generation = value
                        car = self.fleet[vid]
                        if car["generation"] == generation:
                            self.at_stop(kind, car)
                self.read()
                self.update_tasks()
                self.publish()
                wall, previous = time.monotonic(), moment
            self.phase = "stopped" if self.phase == "stopping" else "completed"
            for trip in self.trips.values():
                if trip["status"] == "active":
                    trip["status"] = "interrupted"
        except Exception as exc:
            self.phase = "error"
            self.error = str(exc)[:300]
        finally:
            if self.admin.csrf_token and self.owns_checkpoint:
                try:
                    # Preserve the observed endpoint of the experiment before owner cleanup cancels abandoned requests.
                    try:
                        self.publish(final=True)
                    finally:
                        self.cleanup()
                        (ARCHIVE / "pending.json").unlink(missing_ok=True)
                except Exception as exc:
                    self.phase = "error"
                    self.error = f"清理或导出失败：{exc}"[:300]
                    self.snapshot = {**self.snapshot, "phase": self.phase, "error": self.error}
                finally:
                    try:
                        self.clock("clear")
                    except Exception:
                        pass
                    for actor in [self.admin, *self.actors.values(), *(a for a, _ in self.people.values())]:
                        try:
                            actor.logout()
                        except Exception:
                            continue
            else:
                self.snapshot = {**self.snapshot, "phase": self.phase, "error": self.error}
                if self.admin.csrf_token:
                    try:
                        self.clock("clear")
                        self.admin.logout()
                    except Exception:
                        pass
            for vehicle in self.fleet.values():
                vehicle.pop("credentials", None)

    def read(self):
        floor = {**self.floors, "eventAfter": max(self.event_cache, default=self.floors.get("eventAfter", 0))}
        self.ledger = self.admin.get(self.admin.query("/api/admin/operation/ledger", **floor)).data
        self.event_cache.update((event["id"], event) for event in self.ledger.pop("events"))
        return self.ledger
