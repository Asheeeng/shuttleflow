"""可复算的运营指标计算。"""
from __future__ import annotations

from collections import deque


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((p / 100.0) * len(ordered) + 0.999999) - 1))
    return ordered[index]


def summarize_events(arrival_times: list[float], board_events: list[dict], max_queue: int,
                     *, task_created: int = 0, task_completed: int = 0,
                     leftover: int | None = None, end_time: float | None = None,
                     alert_delay_ms: float | None = None, dispatch_delay_ms: float | None = None) -> dict:
    arrivals = sorted(float(x) for x in arrival_times)
    services = sorted(board_events, key=lambda item: float(item["time"]))
    waiting = deque()
    passenger_index = 0
    waits = []
    unserved = 0
    queue_count = 0
    max_observed_queue = max_queue
    boarded = 0
    for service in services:
        service_time = float(service["time"])
        while passenger_index < len(arrivals) and arrivals[passenger_index] <= service_time:
            waiting.append(arrivals[passenger_index])
            passenger_index += 1
            queue_count += 1
            max_observed_queue = max(max_observed_queue, queue_count)
        count = max(0, int(service.get("count", 0)))
        served_now = min(count, len(waiting))
        for _ in range(served_now):
            arrival_time = waiting.popleft()
            waits.append(max(0.0, service_time - arrival_time))
            queue_count -= 1
        boarded += served_now
    while passenger_index < len(arrivals):
        waiting.append(arrivals[passenger_index])
        passenger_index += 1
        queue_count += 1
        max_observed_queue = max(max_observed_queue, queue_count)
    unserved = len(waiting) if leftover is None else int(leftover)
    offered = sum(max(0, int(s.get("capacity", 0))) for s in services)
    load_rates = [int(s.get("loadAfter", s.get("count", 0))) / max(1, int(s.get("capacity", 0)))
                  for s in services]
    return {
        "平均候车秒": round(sum(waits) / len(waits), 2) if waits else None,
        "P95候车秒": round(percentile(waits, 95), 2) if waits else None,
        "最大已服务候车秒": round(max(waits), 2) if waits else None,
        "最大候车人数": int(max_observed_queue),
        "已签到人数": len(arrivals),
        "已上车人数": boarded,
        "未上车人数": unserved,
        "服务趟数": len(services),
        "单趟最大上客人数": max((int(s.get("count", 0)) for s in services), default=0),
        "首趟下客人数": int(services[0].get("alighted", 0)) if services else 0,
        "首趟上客人数": int(services[0].get("count", 0)) if services else 0,
        "平均车辆载客率": round(sum(load_rates) / len(load_rates), 4) if load_rates else 0.0,
        "座位运力利用率": round(boarded / offered, 4) if offered else 0.0,
        "完成任务数": int(task_completed),
        "创建任务数": int(task_created),
        "任务完成率": round(task_completed / task_created, 4) if task_created else None,
        "预警延迟毫秒": round(alert_delay_ms, 2) if alert_delay_ms is not None else None,
        "派单延迟毫秒": round(dispatch_delay_ms, 2) if dispatch_delay_ms is not None else None,
        "统计截止虚拟秒": round(end_time, 2) if end_time is not None else None,
    }
