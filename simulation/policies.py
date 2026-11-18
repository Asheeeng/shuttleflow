"""与真实API结果分开的两个基线模型：固定班次、简单阈值派车。"""
from __future__ import annotations

import heapq
import math
from collections import deque

from .metrics import summarize_events


def simulate_baseline(arrival_times: list[float], *, policy: str, vehicle_count: int,
                      capacity: int = 13, peak: bool = False, threshold: int = 8,
                      eta_seconds: float = 60.0, round_trip_seconds: float = 900.0,
                      preload: int = 0, alight_fraction: float = 0.3) -> dict:
    """单站离散事件基线。明确为模型，不会调用或改变ShuttleFlow业务接口。"""
    if policy not in {"fixed_timetable", "simple_threshold"}:
        raise ValueError(f"未知基线策略: {policy}")
    if vehicle_count < 0 or capacity <= 0:
        raise ValueError("车辆数和容量无效")

    arrivals = sorted(float(x) for x in arrival_times)
    cycles = max(1, int(math.ceil(len(arrivals) / max(1, capacity * max(1, vehicle_count)))))
    horizon = max(arrivals, default=0.0) + round_trip_seconds * (cycles + 1)
    waiting = deque()
    services = []
    queue_peak = 0
    events = []
    sequence = 0
    for index, stamp in enumerate(arrivals):
        heapq.heappush(events, (stamp, 0, sequence, "arrival", index))
        sequence += 1

    vehicle_available = [0.0] * vehicle_count
    vehicle_load = [0] * vehicle_count
    if vehicle_count:
        vehicle_load[0] = min(capacity, max(0, int(preload)))

    if policy == "fixed_timetable" and vehicle_count:
        headway = 300.0 if peak else 900.0
        departure = headway
        while departure <= horizon:
            heapq.heappush(events, (departure, 1, sequence, "fixed_bus", None))
            sequence += 1
            departure += headway

    elif policy == "simple_threshold" and vehicle_count:
        # arrivals到达时按一个阈值任务派一辆车；任务完成前不重复占用同车。
        # 动态服务事件在事件循环内创建，采用确定性ID顺序作为同成本平手规则。
        pass

    def schedule_threshold_service(now: float) -> None:
        nonlocal sequence
        while len(waiting) >= threshold:
            candidates = [i for i, at in enumerate(vehicle_available) if at <= now]
            if not candidates:
                return
            vehicle_id = min(candidates)
            service_at = now + eta_seconds
            return_at = service_at + round_trip_seconds
            vehicle_available[vehicle_id] = return_at
            heapq.heappush(events, (service_at, 1, sequence, "threshold_bus", vehicle_id))
            sequence += 1
            heapq.heappush(events, (return_at, 2, sequence, "threshold_return", vehicle_id))
            sequence += 1
            # 有空闲车则可为红色高峰一次预约多辆，否则等返回事件唤醒。
            if not any(at <= now for at in vehicle_available):
                return

    boarded_events = []
    for stamp, _, _, kind, payload in sorted_events_iter(events):
        if kind == "arrival":
            waiting.append(stamp)
            queue_peak = max(queue_peak, len(waiting))
            if policy == "simple_threshold":
                schedule_threshold_service(stamp)
            continue

        if kind == "threshold_return":
            if policy == "simple_threshold":
                schedule_threshold_service(stamp)
            continue

        if kind == "fixed_bus":
            available = [i for i, at in enumerate(vehicle_available) if at <= stamp]
            if not available:
                continue
            vehicle_id = min(available)
            vehicle_available[vehicle_id] = stamp + round_trip_seconds
        else:
            vehicle_id = int(payload)
        load = vehicle_load[vehicle_id]
        alight = min(load, int(round(load * alight_fraction)))
        load_after_alight = load - alight
        seats = max(0, capacity - load_after_alight)
        board = min(len(waiting), seats)
        for _ in range(board):
            waiting.popleft()
        vehicle_load[vehicle_id] = load_after_alight + board
        board_event = {"time": stamp, "count": board, "capacity": capacity,
                       "vehicleId": vehicle_id + 1, "loadBefore": load,
                       "alighted": alight, "loadAfter": vehicle_load[vehicle_id]}
        services.append(board_event)
        boarded_events.append(board_event)
        if policy == "simple_threshold" and len(waiting) >= threshold:
            schedule_threshold_service(stamp)

    # 停止窗口内未服务请求不假装完成；记录最后队列数量供报告。
    last_time = max([0.0] + arrivals + [s["time"] for s in services])
    return summarize_events(arrivals, boarded_events, queue_peak,
                            task_created=len(services) if policy == "simple_threshold" else 0,
                            task_completed=len(services) if policy == "simple_threshold" else 0,
                            leftover=max(0, len(arrivals) - sum(s["count"] for s in services)),
                            end_time=last_time)


def sorted_events_iter(events):
    """heapq元素本身已按时间、事件优先级和序号排序。"""
    while events:
        yield heapq.heappop(events)
