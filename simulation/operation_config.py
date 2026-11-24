"""共享仿真配置、限制和实验文件写入。"""
import json
import os
from datetime import datetime
from simulation.scenarios import SCENARIOS

POLICIES = {"fixed", "threshold", "smart"}
FLOWS = {"uniform", "random", "peak", "burst"}
RUN_WALL_LIMIT = min(7200, int(os.environ.get("SHUTTLE_OPERATION_WALL_SECONDS", "1800")))
PASSENGER_LIMIT = min(500, int(os.environ.get("SHUTTLE_OPERATION_MAX_PASSENGERS", "500")))
VEHICLE_LIMIT = min(16, int(os.environ.get("SHUTTLE_OPERATION_MAX_VEHICLES", "16")))

ACTIVE = {"preparing", "running", "paused", "stopping"}


def integer(value, minimum, maximum, label):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f"{label}须为 {minimum} 至 {maximum} 的整数")
    return value


def configuration(body):
    if not isinstance(body, dict):
        raise ValueError("运营配置应为 JSON 对象")
    scenario = body.get("scenario", "custom")
    if scenario not in {*SCENARIOS, "all_day", "custom"}:
        raise ValueError("未知场景")
    preset = SCENARIOS.get(scenario)
    raw_fleet = body.get("fleet")
    if raw_fleet is None:
        raw_fleet = [{"lineId": preset.line_id if preset else 1, "direction": preset.direction if preset else "go",
                      "count": len(preset.vehicles) if preset else 3,
                      "initialLoad": preset.preloaded_people if preset else 0}]
    if not isinstance(raw_fleet, list) or not 1 <= len(raw_fleet) <= 6:
        raise ValueError("请配置 1 至 6 组车队")
    fleet = []
    for item in raw_fleet:
        if not isinstance(item, dict):
            raise ValueError("车队格式无效")
        capacity = integer(item.get("capacity", 13), 1, 50, "座位")
        count = integer(item.get("count", 3), 0, 8, "车辆数")
        direction = item.get("direction", "go")
        if direction not in {"go", "back"}:
            raise ValueError("方向必须是去程或回程")
        fleet.append({
            "lineId": integer(item.get("lineId", 1), 1, 3, "线路"), "direction": direction,
            "count": count, "capacity": capacity,
            "initialStation": integer(item.get("initialStation", 0), 0, 100, "初始站点"),
            "initialLoad": integer(item.get("initialLoad", 0), 0, capacity, "初始载客"),
            "headway": integer(item.get("headway", 180), 15, 3600, "发车间隔"),
            "travel": integer(item.get("travel", 0), 0, 1800, "站间秒数"),
            "dwell": integer(item.get("dwell", 20), 1, 600, "停站秒数"),
            "manual": integer(item.get("manual", 0), 0, count, "人工驾驶车辆数"),
        })
    if sum(item["count"] for item in fleet) > VEHICLE_LIMIT:
        raise ValueError(f"单次运行最多 {VEHICLE_LIMIT} 辆车")
    start = datetime.fromisoformat(body.get("start", "2026-11-18T07:50"))
    if start.tzinfo is not None or not 2000 <= start.year <= 2099:
        raise ValueError("请填写上海时区的本地模拟时间")
    policy = body.get("policy", "smart")
    flow = body.get("flow", "peak" if scenario in {"class_peak", "all_day"} else "burst" if scenario == "surge" else "uniform")
    if policy not in POLICIES or flow not in FLOWS:
        raise ValueError("未知调度策略或客流分布")
    minutes = integer(body.get("minutes", 900 if scenario == "all_day" else 100), 1, 1440, "运营分钟")
    stop_budget = sum(minutes * 60 * g["count"] / max(1, (g["travel"] or 150) + g["dwell"]
                      + g["headway"]/14) for g in fleet)
    if stop_budget > 7000:
        raise ValueError("该车队预计超过 7000 次停站，请缩短运营时长或放宽行车间隔")
    return {
        "scenario": scenario, "start": start.isoformat(timespec="seconds"), "policy": policy, "flow": flow,
        "minutes": minutes,
        "speed": integer(body.get("speed", 120), 1, 1800, "运行倍率"),
        "seed": integer(body.get("seed", 20261118), 0, 2**31-1, "种子"),
        "passengers": integer(body.get("passengers", preset.passenger_count if preset else 100), 1, PASSENGER_LIMIT, "乘客数"),
        "wait": integer(body.get("wait", 25), 1, 240, "最大等待分钟"),
        "reject": body.get("reject", scenario == "driver_refusal") is True, "fleet": fleet,
        "pattern": body.get("pattern", "mixed") if body.get("pattern", "mixed") in {"mixed", "auto"} else "mixed",
    }


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(encode(value), encoding="utf-8")
    tmp.chmod(0o600)
    tmp.replace(path)
