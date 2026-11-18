#!/usr/bin/env python3
"""通过乘客、司机和管理员真实HTTP API运行可复现的运营仿真。"""
from __future__ import annotations

import argparse
import csv
import heapq
import ipaddress
import json
import os
import pathlib
import queue
import secrets
import threading
import time
import urllib.parse
from datetime import datetime

from simulation.api_client import ApiResponse, ShuttleApi, ShuttleApiError
from simulation.clock import SimulationClock, SimulationStopped
from simulation.metrics import summarize_events
from simulation.policies import simulate_baseline
from simulation.scenarios import SCENARIOS, Scenario, arrival_trace


ROOT = pathlib.Path(__file__).resolve().parents[1]
RESULTS = ROOT / "simulation" / "results"


class SimulationRunner:
    def __init__(self, api_base: str, speed: float, seed: int, output_dir: pathlib.Path,
                 interactive: bool = False):
        self.api_base = api_base.rstrip("/")
        self.speed = speed
        self.seed = seed
        self.output_dir = output_dir
        self.clock = SimulationClock(speed)
        self.interactive = interactive
        self.admin: ShuttleApi | None = None
        self.drivers: dict[int, ShuttleApi] = {}
        self.vehicles: list[dict] = []
        self.events: list[dict] = []
        self.task_records: list[dict] = []
        self.scenario_results: list[dict] = []
        self.current_scenario: str | None = None
        self._seq = 0
        self._event_log = None
        self._stop_input = threading.Event()
        self._interactive_thread: threading.Thread | None = None

    def safety_check(self) -> None:
        parsed = urllib.parse.urlparse(self.api_base)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise SystemExit("安全停止：仿真器只允许连接本机HTTP测试服务，不接受公网或VPS地址。")
        db_name = os.environ.get("SHUTTLE_TEST_DB_NAME", "")
        jdbc_value = os.environ.get("SHUTTLE_DB_URL", "")
        if jdbc_value.startswith("jdbc:"):
            jdbc_value = jdbc_value[5:]
        jdbc = urllib.parse.urlparse(jdbc_value)
        if not db_name.startswith("shuttle_v13_sim_") or jdbc.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise SystemExit("安全停止：SHUTTLE_TEST_DB_NAME必须为shuttle_v13_sim_*，JDBC主机必须是回环地址。")
        if urllib.parse.unquote(jdbc.path.lstrip("/")) != db_name:
            raise SystemExit("安全停止：SHUTTLE_TEST_DB_NAME与实际JDBC数据库名不一致。")
        try:
            ipaddress.ip_address(parsed.hostname) if parsed.hostname != "localhost" else ipaddress.ip_address("127.0.0.1")
        except ValueError as error:
            raise SystemExit("安全停止：API地址不是回环地址。") from error
        if not os.environ.get("SHUTTLE_SIMULATION_ENABLED", "").lower() == "true":
            raise SystemExit("安全停止：测试服务未启用本地仿真profile。")

    def start(self) -> None:
        self.safety_check()
        self.output_dir.mkdir(parents=True, exist_ok=False)
        event_log_path = self.output_dir / "api_events.jsonl"
        self._event_log = event_log_path.open("x", encoding="utf-8")
        os.chmod(event_log_path, 0o600)
        self.admin = ShuttleApi(self.api_base)
        username = os.environ["SHUTTLE_TEST_ADMIN_USERNAME"]
        password = os.environ["SHUTTLE_TEST_ADMIN_PASSWORD"]
        self.admin.login_admin(username, password)
        self.vehicles = self.admin.get("/api/admin/vehicles").data or []
        needed_vehicle_ids = sorted({vehicle_id for scenario in SCENARIOS.values() for vehicle_id in scenario.vehicles})
        existing_drivers = self.admin.get("/api/admin/drivers").data or []
        for vehicle_id in needed_vehicle_ids:
            vehicle = self._vehicle(vehicle_id)
            driver_password = secrets.token_urlsafe(18)
            existing = next((d for d in existing_drivers
                             if int(d.get("defaultVehicleId", -1)) == vehicle_id
                             and d.get("enabled", 1) == 1), None)
            if existing:
                phone = existing["phone"]
                if not phone:
                    raise RuntimeError(f"测试司机{existing.get('id')}缺少手机号；安全停止，不覆盖绑定信息。")
                self.admin.post("/api/admin/driver/reset-password", {
                    "driverId": existing["id"], "password": driver_password,
                })
                driver_id = int(existing["id"])
                account_event = "driver_test_account_reused"
            else:
                phone = "198" + "".join(secrets.choice("0123456789") for _ in range(8))
                created = self.admin.post("/api/admin/driver/create", {
                    "name": f"仿真司机{vehicle_id}", "phone": phone,
                    "password": driver_password, "vehicleId": vehicle_id,
                }).data
                driver_id = int(created["driver"]["id"])
                account_event = "driver_test_account_created"
            driver = ShuttleApi(self.api_base)
            driver.login_driver(phone, driver_password)
            self.drivers[vehicle_id] = driver
            self._record(account_event, 0, {
                "vehicleId": vehicle_id, "driverId": driver_id,
                "source": "simulation", "credential": "memory_only",
            })
        self._write_json("environment.json", {
            "source": "simulated", "seed": self.seed, "speedMultiplier": self.speed,
            "testDatabase": os.environ["SHUTTLE_TEST_DB_NAME"],
            "api": self.api_base, "vehiclesPrepared": needed_vehicle_ids,
            "note": "数据库、乘客请求、任务和事件仅存在于V1.3本机独立测试实例。",
        })
        if self.interactive:
            self._interactive_thread = threading.Thread(target=self._read_controls, daemon=True)
            self._interactive_thread.start()

    def _read_controls(self) -> None:
        while not self._stop_input.is_set():
            try:
                line = input("仿真控制 [p暂停/r继续/s停止] > ").strip().lower()
            except EOFError:
                return
            if line == "p":
                self.clock.pause(); print("仿真已暂停")
            elif line == "r":
                self.clock.resume(); print("仿真继续")
            elif line == "s":
                self.clock.stop(); print("收到停止请求"); return

    def _vehicle(self, vehicle_id: int) -> dict:
        for v in self.vehicles:
            if int(v["id"]) == int(vehicle_id):
                return v
        raise RuntimeError(f"初始化SQL中找不到车辆ID={vehicle_id}")

    def _record(self, event_type: str, sim_time: float, data: dict) -> None:
        row = {"source": "simulated", "scenario": self.current_scenario,
               "event": event_type, "simulatedSecond": round(sim_time, 3), **data}
        self.events.append(row)
        if self._event_log:
            self._event_log.write(json.dumps(row, ensure_ascii=False) + "\n")
            self._event_log.flush()

    def _queue_count(self, scenario: Scenario) -> int:
        rows = self.admin.get("/api/admin/queues").data or []
        for row in rows:
            if (int(row.get("stationId", -1)) == scenario.station_id
                    and int(row.get("lineId", -1)) == scenario.line_id
                    and row.get("direction") == scenario.direction):
                return int(row.get("waitingCount", 0))
        return 0

    def _tasks(self) -> list[dict]:
        return self.admin.get(self.admin.query("/api/admin/tasks", scope="all")).data or []

    def _route_tasks(self, scenario: Scenario) -> list[dict]:
        return [t for t in self._tasks()
                if int(t.get("lineId", -1)) == scenario.line_id
                and int(t.get("stationId", -1)) == scenario.station_id
                and t.get("direction") == scenario.direction]

    def _set_fleet(self, scenario: Scenario) -> None:
        self.vehicles = self.admin.get("/api/admin/vehicles").data or []
        for vehicle in self.vehicles:
            vehicle_id = int(vehicle["id"])
            if vehicle.get("runningToday") == 1 or vehicle.get("status") != "offline":
                response = self.admin.request("POST", "/api/admin/vehicle/set-running",
                                              {"vehicleId": vehicle_id, "running": False})
                if response.code not in (200, 409):
                    raise ShuttleApiError("/api/admin/vehicle/set-running", response)
                if response.code == 409:
                    raise RuntimeError(f"隔离仿真库内车辆{vehicle_id}仍被任务占用，停止该场景。")
        for vehicle_id in scenario.vehicles:
            self.admin.post("/api/admin/vehicle/set-running", {"vehicleId": vehicle_id, "running": True})
            driver = self.drivers[vehicle_id]
            driver.post_driver_event("/api/driver/arrive", {"vehicleId": vehicle_id,
                                                              "stationId": scenario.station_id,
                                                              "direction": scenario.direction})
            initial_load = scenario.preloaded_people if scenario.vehicles[0] == vehicle_id else 0
            driver.post_driver_event("/api/driver/calibrate", {
                "vehicleId": vehicle_id, "afterLoad": initial_load,
            })
            self._record("simulated_initial_load", 0, {
                "vehicleId": vehicle_id, "load": initial_load,
                "source": "simulated_driver_calibration",
            })

    def _preview_multi_vehicle(self, scenario: Scenario, before_count: int) -> dict:
        before = {int(v["id"]): (v.get("currentLoad"), v.get("status"))
                  for v in self.admin.get("/api/admin/vehicles").data or []}
        query = self.admin.query("/api/admin/assign", stationId=scenario.station_id,
                                 lineId=scenario.line_id, direction=scenario.direction)
        response = self.admin.get(query)
        after_count = self._queue_count(scenario)
        after = {int(v["id"]): (v.get("currentLoad"), v.get("status"))
                 for v in self.admin.get("/api/admin/vehicles").data or []}
        if before_count != after_count or before != after:
            raise AssertionError("多车协同预览意外修改了正式业务状态；隔离仿真中断。")
        data = response.data or {}
        self._record("multi_vehicle_preview_only", 0, {
            "waiting": before_count, "strategy": data.get("strategy"),
            "solver": data.get("solver", "Java/heuristic"),
            "leftover": data.get("leftover"), "mutatedBusinessState": False,
        })
        return data

    def run_scenario(self, scenario: Scenario, *, scenario_speed: float | None = None) -> dict:
        if self.clock.stopped:
            raise SimulationStopped("仿真已停止")
        self.current_scenario = scenario.key
        old_speed = self.clock.speed
        self.clock.set_speed(scenario_speed or self.speed)
        self._set_fleet(scenario)
        scenario_base = self.clock.now()
        arrival_events = arrival_trace(scenario, self.seed)
        self._write_json(f"arrivals_{scenario.key}.json", {
            "scenario": scenario.key, "source": "simulated", "seed": self.seed,
            "lineId": scenario.line_id, "stationId": scenario.station_id,
            "direction": scenario.direction, "passengers": arrival_events,
        })
        print(f"开始场景：{scenario.title}；乘客={scenario.passenger_count}；方向={scenario.direction}；车辆={list(scenario.vehicles)}")
        route_task_ids = {int(t["id"]) for t in self._route_tasks(scenario)}
        created_tasks: set[int] = set()
        scheduled_jobs: dict[int, dict] = {}
        assignment_idempotency_checked: set[int] = set()
        completed_tasks: set[int] = set()
        escalated_tasks: set[int] = set()
        rejected_once = False
        arrival_times: list[float] = []
        board_events: list[dict] = []
        max_queue = 0
        expected_queue = self._queue_count(scenario)
        if expected_queue != 0:
            raise RuntimeError(f"场景{scenario.key}队列初始值为{expected_queue}，要求独立空队列。")
        preview_done = False
        idempotency_checked = False
        capacity_guard_checked = False
        idempotency_ok = False
        capacity_guard_ok = False
        threshold_mono: float | None = None
        alert_delays: list[float] = []
        dispatch_delays: list[float] = []
        scenario_tasks: list[dict] = []
        scheduler_tick_count = 0
        max_arrival = max((float(x["arrivalSecond"]) for x in arrival_events), default=0.0)
        # CUSUM的到达率定义为每分钟；补齐最后一个完整虚拟分钟，避免把单个尾部事件按1~2秒窗口放大。
        minute_count = max(1, int((max_arrival + 59.999999) // 60.0))
        tick_windows = [(60.0 * i, 60) for i in range(1, minute_count + 1)]
        tick_index = 0
        arrival_index = 0
        heap: list[tuple[float, int, int, int]] = []
        serial = 0
        cursor_time = 0.0

        def current_route_tasks() -> list[dict]:
            return [t for t in self._tasks()
                    if int(t.get("lineId", -1)) == scenario.line_id
                    and int(t.get("stationId", -1)) == scenario.station_id
                    and t.get("direction") == scenario.direction]

        def schedule_task(task: dict, virtual_now: float) -> None:
            nonlocal serial
            task_id = int(task["id"])
            if task_id in scheduled_jobs or task_id in completed_tasks or task_id in escalated_tasks:
                return
            vehicle_id = task.get("vehicleId")
            if vehicle_id is None:
                return
            vehicle_id = int(vehicle_id)
            eta_rows = self.admin.get(self.admin.query("/api/passenger/eta", lineId=scenario.line_id,
                                                       stationId=scenario.station_id,
                                                       direction=scenario.direction)).data or []
            eta = next((int(v.get("etaSec", 60)) for v in eta_rows
                        if int(v.get("vehicleId", -1)) == vehicle_id), 60)
            due = virtual_now + max(1, eta)
            scheduled_jobs[task_id] = {"vehicleId": vehicle_id, "due": due, "task": task}
            heapq.heappush(heap, (due, serial, task_id, vehicle_id))
            self._record("task_scheduled_for_driver_execution", virtual_now, {
                "taskId": task_id, "vehicleId": vehicle_id, "etaSeconds": eta,
                "status": task.get("status"),
            })

        def sync_tasks(virtual_now: float) -> None:
            nonlocal rejected_once
            tasks = current_route_tasks()
            for task in tasks:
                task_id = int(task["id"])
                if task_id not in route_task_ids:
                    route_task_ids.add(task_id)
                    created_tasks.add(task_id)
                    scenario_tasks.append(task)
                    self._record("dispatch_task_created", virtual_now, {
                        "taskId": task_id, "taskNo": task.get("taskNo"),
                        "direction": task.get("direction"), "reasonCode": task.get("reasonCode"),
                        "status": task.get("status"), "vehicleId": task.get("vehicleId"),
                    })
                    if threshold_mono is not None:
                        dispatch_delays.append((time.monotonic() - threshold_mono) * 1000)
                if task.get("direction") != scenario.direction:
                    raise AssertionError(f"任务{task_id}方向{task.get('direction')}与场景方向不一致。")
                status = task.get("status")
                if status == "escalated":
                    escalated_tasks.add(task_id)
                    continue
                if status == "completed":
                    completed_tasks.add(task_id)
                    continue
                if status != "assigned" or task_id in scheduled_jobs:
                    continue
                if task_id not in assignment_idempotency_checked:
                    original_vehicle_id = int(task["vehicleId"])
                    repeated = self.admin.post("/api/admin/task/assign", {"taskId": task_id}).data or {}
                    assignment_idempotency_checked.add(task_id)
                    if repeated.get("status") != "assigned" or int(repeated.get("vehicleId", -1)) != original_vehicle_id:
                        raise AssertionError("重复派发已指派任务时改变了车辆或状态。")
                    self._record("duplicate_assignment_idempotency", virtual_now, {
                        "taskId": task_id, "vehicleId": original_vehicle_id, "statusUnchanged": True,
                    })
                vehicle_id = int(task["vehicleId"])
                if scenario.reject_first_task and not rejected_once:
                    rejected_once = True
                    reject_response = self.drivers[vehicle_id].post("/api/driver/task/reject", {
                        "taskId": task_id, "reason": "模拟司机拒单测试",
                    }).data
                    new_vehicle_id = reject_response.get("vehicleId")
                    self._record("driver_rejected_task", virtual_now, {
                        "taskId": task_id, "rejectedVehicleId": vehicle_id,
                        "reassignedVehicleId": new_vehicle_id,
                        "newStatus": reject_response.get("status"),
                    })
                    if new_vehicle_id is None or int(new_vehicle_id) == vehicle_id:
                        raise AssertionError("拒单后任务没有顺延到另一辆候选车。")
                    if reject_response.get("status") != "assigned":
                        raise AssertionError("拒单顺延后的任务状态不是assigned。")
                    task = next((x for x in current_route_tasks() if int(x["id"]) == task_id), reject_response)
                    vehicle_id = int(task["vehicleId"])
                schedule_task(task, virtual_now)

        def run_due_jobs(virtual_now: float) -> None:
            nonlocal capacity_guard_checked, capacity_guard_ok, expected_queue
            while heap and heap[0][0] <= virtual_now:
                due, _, task_id, vehicle_id = heapq.heappop(heap)
                job = scheduled_jobs.get(task_id)
                if not job or task_id in completed_tasks or task_id in escalated_tasks:
                    continue
                task = next((t for t in current_route_tasks() if int(t["id"]) == task_id), job["task"])
                if task.get("status") != "assigned" or int(task.get("vehicleId", -1)) != vehicle_id:
                    continue
                driver = self.drivers[vehicle_id]
                accepted = driver.post("/api/driver/task/accept", {"taskId": task_id, "vehicleId": vehicle_id}).data or {}
                if accepted.get("status") != "executing":
                    raise AssertionError(f"司机接单后的任务状态应为executing，实际为{accepted.get('status')}。")
                arrived = driver.post_driver_event("/api/driver/arrive", {"vehicleId": vehicle_id,
                                                                          "stationId": scenario.station_id,
                                                                          "direction": scenario.direction}).data or {}
                if arrived.get("direction") != scenario.direction:
                    raise AssertionError("司机到站后的车辆方向与场景不一致。")
                current = driver.get(f"/api/driver/vehicle/{vehicle_id}").data
                before_load = int(current.get("currentLoad", 0))
                if before_load > 0:
                    if scenario.preloaded_people and not any(e.get("event") == "driver_alighted" for e in self.events
                                                            if e.get("scenario") == scenario.key):
                        alight = min(before_load, scenario.alight_people)
                    else:
                        alight = min(before_load, max(0, int(round(before_load * 0.3))))
                    if alight:
                        driver.post_driver_event("/api/driver/alight", {"vehicleId": vehicle_id,
                                                                        "stationId": scenario.station_id,
                                                                        "direction": scenario.direction,
                                                                        "count": alight})
                        self._record("driver_alighted", due, {"vehicleId": vehicle_id,
                                                               "count": alight, "source": "simulated_demand"})
                current = driver.get(f"/api/driver/vehicle/{vehicle_id}").data
                load = int(current.get("currentLoad", 0))
                capacity = int(current.get("capacity", 13))
                seats = max(0, capacity - load)
                waiting = self._queue_count(scenario)
                if not capacity_guard_checked:
                    capacity_guard_checked = True
                    before = load
                    invalid = driver.request("POST", "/api/driver/board", {
                        "vehicleId": vehicle_id, "stationId": scenario.station_id,
                        "lineId": scenario.line_id, "direction": scenario.direction,
                        "count": seats + 1,
                    })
                    after = int(driver.get(f"/api/driver/vehicle/{vehicle_id}").data.get("currentLoad", -1))
                    capacity_guard_ok = invalid.code == 409 and after == before
                    if not capacity_guard_ok:
                        raise AssertionError("超容量上客请求未被拒绝或改变了车辆载客数。")
                    self._record("over_capacity_guard", due, {
                        "rejectedCode": invalid.code, "loadBefore": before, "loadAfter": after,
                        "source": "simulated_negative_test",
                    })
                board_count = min(waiting, seats)
                queue_before = waiting
                if queue_before != expected_queue:
                    raise AssertionError(f"上客前业务队列{queue_before}与仿真账本{expected_queue}不一致。")
                if board_count > 0:
                    driver.post_driver_event("/api/driver/board", {"vehicleId": vehicle_id,
                                                                   "stationId": scenario.station_id,
                                                                   "lineId": scenario.line_id,
                                                                   "direction": scenario.direction,
                                                                   "count": board_count})
                after_load = int(driver.get(f"/api/driver/vehicle/{vehicle_id}").data.get("currentLoad", 0))
                queue_after = self._queue_count(scenario)
                if queue_before - queue_after != board_count:
                    raise AssertionError(f"上车{board_count}人但队列只减少{queue_before - queue_after}人。")
                expected_queue -= board_count
                driver.post_driver_event("/api/driver/depart", {"vehicleId": vehicle_id})
                completed_response = driver.post("/api/driver/task/complete", {"taskId": task_id}).data or {}
                if completed_response.get("status") != "completed":
                    raise AssertionError(f"任务完成接口没有闭环，状态为{completed_response.get('status')}。")
                final_task = next((t for t in current_route_tasks() if int(t["id"]) == task_id), {})
                if final_task.get("status") != "completed":
                    raise AssertionError(f"数据库任务状态不是completed，实际为{final_task.get('status')}。")
                final_vehicle = driver.get(f"/api/driver/vehicle/{vehicle_id}").data or {}
                if final_vehicle.get("status") != "running":
                    raise AssertionError(f"任务完成后车辆状态应释放为running，实际为{final_vehicle.get('status')}。")
                completed_tasks.add(task_id)
                board_event = {"time": due, "count": board_count, "capacity": capacity,
                               "vehicleId": vehicle_id, "loadBefore": load,
                               "alighted": before_load - load, "loadAfter": after_load}
                board_events.append(board_event)
                self._record("driver_boarded_and_completed", due, {
                    "taskId": task_id, "vehicleId": vehicle_id,
                    "boardCount": board_count, "queueBefore": queue_before,
                    "queueAfter": queue_after, "loadAfter": after_load,
                })
                sync_tasks(due)

        def run_cusum_tick(virtual_now: float, interval_seconds: int) -> None:
            nonlocal scheduler_tick_count, threshold_mono
            tick_started = time.monotonic()
            threshold_mono = tick_started
            response = self.admin.post("/api/admin/simulation/cusum/tick", {"intervalSeconds": interval_seconds})
            scheduler_tick_count += 1
            data = response.data or {}
            if int(data.get("processedEvents", 0)) > 0:
                alert_delays.append((time.monotonic() - tick_started) * 1000)
            self._record("java_cusum_business_tick", virtual_now, {
                "processedEvents": data.get("processedEvents"),
                "processedThroughEventId": data.get("processedThroughEventId"),
                "intervalSeconds": interval_seconds,
                "source": "real_event_table_and_java_detector",
            })
            sync_tasks(virtual_now)

        try:
            while arrival_index < len(arrival_events) or heap or tick_index < len(tick_windows):
                next_arrival = (float(arrival_events[arrival_index]["arrivalSecond"])
                                if arrival_index < len(arrival_events) else float("inf"))
                next_job = heap[0][0] if heap else float("inf")
                next_tick = tick_windows[tick_index][0] if tick_index < len(tick_windows) else float("inf")
                local_time = min(next_arrival, next_job, next_tick)
                self.clock.wait_until(scenario_base + local_time)
                cursor_time = local_time
                # At equal virtual timestamps, passenger arrivals are recorded before driver service and CUSUM sampling.
                if next_arrival <= next_job and next_arrival <= next_tick:
                    item = arrival_events[arrival_index]
                    arrival_index += 1
                    guest = ShuttleApi(self.api_base)
                    guest.start_guest()
                    body = {"lineId": scenario.line_id, "stationId": scenario.station_id,
                            "direction": scenario.direction}
                    check_started = time.monotonic()
                    response = guest.post("/api/passenger/checkin", body)
                    result = response.data or {}
                    request_obj = result.get("request") or {}
                    request_id = int(request_obj["id"])
                    waiting_before = expected_queue
                    expected_queue += 1
                    observed_queue = self._queue_count(scenario)
                    if observed_queue != expected_queue:
                        raise AssertionError(f"签到后候车队列={observed_queue}，期望{expected_queue}。")
                    arrival_times.append(float(item["arrivalSecond"]))
                    max_queue = max(max_queue, observed_queue)
                    self._record("passenger_checkin", item["arrivalSecond"], {
                        "simulatedPassengerNo": item["simulatedPassengerNo"],
                        "requestId": request_id, "queueBefore": waiting_before,
                        "queueAfter": observed_queue, "lineId": scenario.line_id,
                        "stationId": scenario.station_id, "direction": scenario.direction,
                        "httpLatencyMs": round(response.elapsed_ms, 2),
                    })
                    if not idempotency_checked:
                        duplicate = guest.post("/api/passenger/checkin", body)
                        duplicate_request = (duplicate.data or {}).get("request", {})
                        duplicate_queue = self._queue_count(scenario)
                        idempotency_ok = int(duplicate_request.get("id", -1)) == request_id and duplicate_queue == observed_queue
                        idempotency_checked = True
                        if not idempotency_ok:
                            raise AssertionError("重复签到产生了重复请求或重复扣加了候车人数。")
                        self._record("checkin_idempotency", item["arrivalSecond"], {
                            "sameRequestId": True, "queueBeforeDuplicate": observed_queue,
                            "queueAfterDuplicate": duplicate_queue,
                        })
                    if not preview_done and observed_queue >= 8 and observed_queue < 15 and scenario.vehicles:
                        preview = self._preview_multi_vehicle(scenario, observed_queue)
                        preview_done = True
                        self._record("multi_vehicle_preview_result", item["arrivalSecond"], {
                            "strategy": preview.get("strategy"), "solver": preview.get("solver", "Java fallback"),
                            "leftover": preview.get("leftover"), "appliedToVehicles": False,
                        })
                    if waiting_before < 8 <= observed_queue or waiting_before < 15 <= observed_queue or waiting_before < 25 <= observed_queue:
                        threshold_started = check_started
                        self.admin.get("/api/admin/alerts")
                        alert_delays.append((time.monotonic() - threshold_started) * 1000)
                        threshold_mono = threshold_started
                    sync_tasks(float(item["arrivalSecond"]))
                    guest.logout()
                elif next_job <= next_tick:
                    run_due_jobs(next_job)
                else:
                    run_cusum_tick(next_tick, tick_windows[tick_index][1])
                    tick_index += 1

            # Process any final task created by the last arrival/tick whose due time was queued after the final tick.
            while heap:
                due = heap[0][0]
                self.clock.wait_until(scenario_base + due)
                run_due_jobs(due)
            final_queue = self._queue_count(scenario)
            if final_queue != expected_queue:
                raise AssertionError("最终候车数与签到/上车守恒不一致。")
            final_tasks = current_route_tasks()
            actual_created = len(created_tasks)
            actual_completed = sum(1 for t in final_tasks if int(t["id"]) in created_tasks and t.get("status") == "completed")
            actual_escalated = sum(1 for t in final_tasks if int(t["id"]) in created_tasks and t.get("status") == "escalated")
            metrics = summarize_events(arrival_times, board_events, max_queue,
                                       task_created=actual_created, task_completed=actual_completed,
                                       leftover=final_queue, end_time=cursor_time,
                                       alert_delay_ms=(sum(alert_delays) / len(alert_delays) if alert_delays else None),
                                       dispatch_delay_ms=(sum(dispatch_delays) / len(dispatch_delays) if dispatch_delays else None))
            metrics.update({
                "scenario": scenario.key, "scenarioTitle": scenario.title,
                "lineId": scenario.line_id, "stationId": scenario.station_id,
                "direction": scenario.direction, "source": "simulated",
                "任务升级数": actual_escalated, "CUSUM检测tick数": scheduler_tick_count,
                "重复签到幂等通过": idempotency_ok,
                "超容量保护通过": capacity_guard_ok if board_events else None,
                "拒单顺延已测试": scenario.reject_first_task,
                "多车预览solver": next((e.get("solver") for e in self.events
                                          if e.get("scenario") == scenario.key
                                          and e.get("event") == "multi_vehicle_preview_result"), None),
            })
            result = {"metrics": metrics, "tasks": final_tasks,
                      "arrivalTimes": arrival_times, "boardEvents": board_events,
                      "previewDone": preview_done,
                      "generatedTaskIds": sorted(created_tasks),
                      "completedTaskIds": sorted(completed_tasks),
                      "escalatedTaskIds": sorted(escalated_tasks)}
            self.scenario_results.append(result)
            self._write_json(f"scenario_{scenario.key}.json", result)
            print(f"完成场景：{scenario.title}；上车={metrics['已上车人数']}；剩余={metrics['未上车人数']}；任务完成率={metrics['任务完成率']}")
            for vehicle_id in scenario.vehicles:
                self.admin.post("/api/admin/vehicle/set-running", {"vehicleId": vehicle_id, "running": False})
            self.clock.set_speed(old_speed)
            return result
        except SimulationStopped:
            self.clock.set_speed(old_speed)
            self._write_json("partial_state.json", {"scenario": scenario.key,
                                                     "processedArrivals": arrival_index,
                                                     "generatedTasks": sorted(created_tasks)})
            raise
        except Exception:
            self.clock.set_speed(old_speed)
            raise

    def run(self, scenario_keys: list[str]) -> pathlib.Path:
        try:
            self.start()
            # 先运行突发客流，使Java CUSUM tick在该场景内处理隔离的新增事件，再跑其余场景。
            ordered = list(scenario_keys)
            if "surge" in ordered:
                ordered.remove("surge"); ordered.insert(0, "surge")
            for key in ordered:
                scenario = SCENARIOS[key]
                self.run_scenario(scenario, scenario_speed=1.0 if key == "surge" else self.speed)
                self.current_scenario = None
            for vehicle in self.vehicles:
                vehicle_id = int(vehicle["id"])
                live = next((v for v in (self.admin.get("/api/admin/vehicles").data or [])
                             if int(v["id"]) == vehicle_id), {})
                if live.get("runningToday") == 1:
                    self.admin.post("/api/admin/vehicle/set-running", {"vehicleId": vehicle_id, "running": False})
            self._write_outputs()
            return self.output_dir
        except Exception as error:
            if self.output_dir.exists():
                self._write_json("failure.json", {
                    "source": "simulated", "scenario": self.current_scenario,
                    "errorType": type(error).__name__, "error": str(error)[:1000],
                    "eventsWritten": len(self.events),
                })
            raise
        finally:
            if self.admin:
                try: self.admin.logout()
                except Exception: pass
            for driver in self.drivers.values():
                try: driver.logout()
                except Exception: pass
            self._stop_input.set()
            if self._event_log:
                self._event_log.close()
                self._event_log = None

    def _write_json(self, name: str, data) -> None:
        path = self.output_dir / name
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def _write_outputs(self) -> None:
        summary = [item["metrics"] for item in self.scenario_results]
        environment_file = self.output_dir / "environment.json"
        environment = json.loads(environment_file.read_text(encoding="utf-8")) if environment_file.is_file() else {}
        runtime_database = os.environ.get("SHUTTLE_TEST_DB_NAME") or environment.get("testDatabase")
        arrivals_by_scenario = {
            key: arrival_trace(SCENARIOS[key], self.seed) for key in SCENARIOS
            if any(result["metrics"]["scenario"] == key for result in self.scenario_results)
        }
        strategy_rows = []
        for result in self.scenario_results:
            metrics = result["metrics"]
            scenario = SCENARIOS[metrics["scenario"]]
            arrivals = [float(x["arrivalSecond"]) for x in arrivals_by_scenario[scenario.key]]
            base_count = len(scenario.vehicles)
            for policy in ("fixed_timetable", "simple_threshold"):
                modeled = simulate_baseline(arrivals, policy=policy, vehicle_count=base_count,
                                             capacity=13, peak=scenario.peak,
                                             threshold=8, eta_seconds=60,
                                             preload=scenario.preloaded_people,
                                             alight_fraction=0.3)
                strategy_rows.append({"scenario": scenario.key, "strategy": policy,
                                      "kind": "离散模型基线", **modeled})
            strategy_rows.append({"scenario": scenario.key, "strategy": "current_api_dispatch",
                                  "kind": "真实HTTP业务接口", **metrics})
        if strategy_rows:
            fields = sorted({key for row in strategy_rows for key in row})
            with (self.output_dir / "strategy_metrics.csv").open("w", newline="", encoding="utf-8-sig") as f:
                writer = csv.DictWriter(f, fieldnames=fields)
                writer.writeheader(); writer.writerows(strategy_rows)
        with (self.output_dir / "scenario_metrics.csv").open("w", newline="", encoding="utf-8-sig") as f:
            fields = sorted({key for row in summary for key in row}) if summary else []
            writer = csv.DictWriter(f, fieldnames=fields)
            if fields:
                writer.writeheader(); writer.writerows(summary)
        self._write_json("summary.json", {
            "source": "simulated", "seed": self.seed, "speedMultiplier": self.speed,
            "runtimeDatabase": runtime_database,
            "scenarios": summary,
            "algorithmBoundary": {
                "current_api_dispatch": "Java阈值规则 + CUSUM业务tick + TaskService方向过滤/车辆评分，真实HTTP事件驱动",
                "multi_vehicle_preview": "仅建议，不改车辆/队列；CP-SAT未安装时为启发式，不计入任务派单指标",
                "fixed_timetable": "使用相同客流序列的离散模型，不连接业务接口",
                "simple_threshold": "使用相同客流序列的离散模型，不连接业务接口",
            },
        })
        self._write_chart(strategy_rows)
        self._write_report(strategy_rows)

    def _write_chart(self, rows: list[dict]) -> None:
        # 标准库SVG图，不依赖额外绘图库或远程素材。
        scenarios = [r["metrics"]["scenario"] for r in self.scenario_results]
        strategies = ["fixed_timetable", "simple_threshold", "current_api_dispatch"]
        colors = {strategies[0]: "#2a6f97", strategies[1]: "#e09f3e", strategies[2]: "#2a9d8f"}
        width, height, left, top, chart_w, chart_h = 1080, max(360, 150 + 70 * len(scenarios)), 120, 70, 880, max(180, 55 * len(scenarios))
        keys = ["平均候车秒", "最大候车人数"]
        bars = []
        values = []
        for scenario in scenarios:
            for strategy in strategies:
                row = next(r for r in rows if r["scenario"] == scenario and r["strategy"] == strategy)
                value = row.get("平均候车秒")
                if value is not None:
                    values.append(float(value))
                bars.append((scenario, strategy, value))
        max_value = max(values or [1])
        svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
               '<rect width="100%" height="100%" fill="#ffffff"/>',
               '<text x="30" y="34" font-size="22" font-family="Arial,sans-serif" font-weight="700">ShuttleFlow V1.3 调度对照：平均候车时间</text>']
        slot_h = chart_h / max(1, len(scenarios))
        for idx, scenario in enumerate(scenarios):
            base_y = top + idx * slot_h
            svg.append(f'<text x="{left-12}" y="{base_y+22}" text-anchor="end" font-size="14">{scenario}</text>')
            for j, strategy in enumerate(strategies):
                row = next(r for r in rows if r["scenario"] == scenario and r["strategy"] == strategy)
                value = row.get("平均候车秒")
                y = base_y + 4 + j * 17
                if value is None:
                    svg.append(f'<text x="{left}" y="{y+12}" font-size="11">无已上车乘客</text>')
                    continue
                bar_w = max(2, chart_w * float(value) / max_value)
                svg.append(f'<rect x="{left}" y="{y}" width="{bar_w:.1f}" height="13" fill="{colors[strategy]}"/>')
                svg.append(f'<text x="{left+bar_w+6:.1f}" y="{y+11}" font-size="11">{float(value):.1f}s</text>')
        legend_y = height - 28
        for j, strategy in enumerate(strategies):
            x = 190 + j * 250
            svg.append(f'<rect x="{x}" y="{legend_y-11}" width="14" height="14" fill="{colors[strategy]}"/>')
            svg.append(f'<text x="{x+22}" y="{legend_y}" font-size="12">{strategy}</text>')
        svg.append('</svg>')
        (self.output_dir / "dispatch_effect.svg").write_text("\n".join(svg), encoding="utf-8")

    def _write_report(self, rows: list[dict]) -> None:
        summary_file = self.output_dir / "summary.json"
        report_environment = json.loads(summary_file.read_text(encoding="utf-8")) if summary_file.is_file() else {}
        runtime_database = report_environment.get("runtimeDatabase") or os.environ.get("SHUTTLE_TEST_DB_NAME", "unknown")
        lines = ["# ShuttleFlow V1.3 运营仿真与调度对照报告", "",
                 f"- 仿真来源：全部为模拟数据；随机种子：`{self.seed}`。",
                 f"- API：`{self.api_base}`；隔离数据库：`{runtime_database}`。",
                 "- 仅本机测试实例。没有连接公网VPS、正式数据库或正式Redis。",
                 "- 完整智能调度统计只使用真实HTTP业务闭环数据；固定班次与简单阈值为同序列离散模型基线。",
                 "- 多车协同/CP-SAT页面接口是只读预览，不计为已执行任务；当前环境未安装OR-Tools时使用启发式。", "",
                 "## 场景结果", "",
                 "| 场景 | 真实API上车 | 未上车 | 平均候车(s) | P95(s) | 最大队列 | 车辆载客率 | 座位利用率 | 任务完成率 | 预警延迟(ms) | 派单延迟(ms) | CUSUM tick |",
                 "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
        for result in self.scenario_results:
            m = result["metrics"]
            avg = "—" if m["平均候车秒"] is None else f"{m['平均候车秒']:.1f}"
            p95 = "—" if m["P95候车秒"] is None else f"{m['P95候车秒']:.1f}"
            rate = "—" if m["任务完成率"] is None else f"{m['任务完成率']:.0%}"
            alert_ms = "—" if m["预警延迟毫秒"] is None else f"{m['预警延迟毫秒']:.1f}"
            dispatch_ms = "—" if m["派单延迟毫秒"] is None else f"{m['派单延迟毫秒']:.1f}"
            lines.append(f"| {m['scenarioTitle']} | {m['已上车人数']} | {m['未上车人数']} | {avg} | {p95} | {m['最大候车人数']} | {m['平均车辆载客率']:.0%} | {m['座位运力利用率']:.0%} | {rate} | {alert_ms} | {dispatch_ms} | {m['CUSUM检测tick数']} |")
        lines.extend(["", "## 策略对照", "", "`strategy_metrics.csv`保存相同到达序列的两个离散基线和一个真实API结果。", "",
                      "| 场景 | 策略 | 类型 | 平均候车(s) | P95(s) | 最大队列 | 车辆载客率 | 座位利用率 | 任务完成率 | 预警延迟(ms) | 派单延迟(ms) |",
                      "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"])
        for row in rows:
            avg = "—" if row.get("平均候车秒") is None else f"{row['平均候车秒']:.1f}"
            p95 = "—" if row.get("P95候车秒") is None else f"{row['P95候车秒']:.1f}"
            rate = "—" if row.get("任务完成率") is None else f"{row['任务完成率']:.0%}"
            alert_ms = "—" if row.get("预警延迟毫秒") is None else f"{row['预警延迟毫秒']:.1f}"
            dispatch_ms = "—" if row.get("派单延迟毫秒") is None else f"{row['派单延迟毫秒']:.1f}"
            lines.append(f"| {row['scenario']} | {row['strategy']} | {row['kind']} | {avg} | {p95} | {row['最大候车人数']} | {row['平均车辆载客率']:.0%} | {row['座位运力利用率']:.0%} | {rate} | {alert_ms} | {dispatch_ms} |")
        actual = {r["scenario"]: r for r in rows if r["strategy"] == "current_api_dispatch"}
        simple = {r["scenario"]: r for r in rows if r["strategy"] == "simple_threshold"}
        improved = [key for key in sorted(actual.keys() & simple.keys())
                    if actual[key].get("平均候车秒") is not None and simple[key].get("平均候车秒") is not None
                    and actual[key]["平均候车秒"] < simple[key]["平均候车秒"]]
        regressed = [key for key in sorted(actual.keys() & simple.keys())
                     if actual[key].get("平均候车秒") is not None and simple[key].get("平均候车秒") is not None
                     and actual[key]["平均候车秒"] > simple[key]["平均候车秒"]]
        lines.extend(["", "## 本seed观察（仅为模拟结果）", "",
                      f"- 在有已上车乘客的场景中，真实API组平均候车低于简单阈值基线：{', '.join(improved) if improved else '无'}；高于基线：{', '.join(regressed) if regressed else '无'}。不能据此宣称真实校园效果。",
                      "- 未上车数与任务升级数仍需一起看；容量不足场景刻意关闭所有车辆，因此真实API组服务为0，属于预期安全结果。",
                      "- 司机拒单场景实际调用拒单接口，验证任务改派后完成；重复签到、重复派单和超容量负向请求在本次HTTP运行中均通过。",
                      "- 当前自动任务主链是Java阈值+CUSUM+方向约束车辆评分。CP-SAT仅在只读多车建议接口出现，不作为在线实际派单效果。"])
        lines.extend(["", "## 自动校验", "",
                      "- 所有乘客签到、司机到站/下客/上客/发车、任务接单/拒单/完成均通过真实HTTP业务接口。",
                      "- 仿真程序检查重复签到幂等、方向一致、队列守恒、超容量请求拒绝、拒单顺延和多车任务分配。",
                      "- 每个候车签到记录、任务事件和API延迟在 `api_events.jsonl`；同一随机种子输入保存在 `arrivals_<scenario>.json`。",
                      "- SVG图：`dispatch_effect.svg`。原始汇总：`summary.json`、`scenario_metrics.csv`、`strategy_metrics.csv`。",
                      "", "## 基线假设", "",
                      "- 车辆核载13人；ETA基线按当前站点数据给出的60秒同站缓冲；简单阈值为8人；下客量按仿真场景输入或30%估计。",
                      "- 固定班次按README高峰5分钟、平峰15分钟，并限制仿真车队车辆数；该策略是离散基线，不是产品内在线调度。",
                      "- 平均/P95候车时间只对实际已上车乘客统计；未上车人数单列，不将未完成请求伪装为完成。", ""])
        (self.output_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="ShuttleFlow隔离测试库真实HTTP运营仿真")
    parser.add_argument("--api", default="http://127.0.0.1:18080")
    parser.add_argument("--scenario", choices=["all", *SCENARIOS.keys()], default="all")
    parser.add_argument("--seed", type=int, default=20261117)
    parser.add_argument("--speed", type=float, default=60.0, help="仿真虚拟时间加速倍数")
    parser.add_argument("--interactive", action="store_true", help="启用p暂停/r继续/s停止终端控制")
    parser.add_argument("--output", type=pathlib.Path, default=None)
    args = parser.parse_args()
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output = args.output or (RESULTS / f"v13-{stamp}-seed{args.seed}")
    keys = list(SCENARIOS) if args.scenario == "all" else [args.scenario]
    runner = SimulationRunner(args.api, args.speed, args.seed, output, args.interactive)
    try:
        path = runner.run(keys)
    except SimulationStopped:
        print("仿真已按用户指令停止；部分结果保存在", output)
        return 130
    except Exception as error:
        print(f"仿真在场景{runner.current_scenario or '初始化'}失败：{type(error).__name__}: {error}")
        print(f"诊断文件和已写API事件（如有）保存在：{output}")
        return 1
    print("仿真完成，报告目录：", path)
    print("生成：report.md、arrival JSON、api_events.jsonl、CSV指标和dispatch_effect.svg。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
