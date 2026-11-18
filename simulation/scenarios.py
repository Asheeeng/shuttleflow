"""仿真场景以课程要求和现有SQL线路/站点为约束；客流均标记为模拟数据。"""
from __future__ import annotations

import hashlib
import random
from dataclasses import dataclass


@dataclass(frozen=True)
class Scenario:
    key: str
    title: str
    line_id: int
    station_id: int
    direction: str
    passenger_count: int
    arrival_window_seconds: int
    vehicles: tuple[int, ...]
    peak: bool
    reject_first_task: bool = False
    preloaded_people: int = 0
    alight_people: int = 0
    force_no_vehicles: bool = False


SCENARIOS = {
    "normal": Scenario("normal", "正常运营", 1, 2, "go", 18, 360, (1,), False),
    "class_peak": Scenario("class_peak", "下课高峰", 2, 14, "go", 34, 90, (5, 6), True),
    "surge": Scenario("surge", "突发客流", 3, 19, "go", 24, 30, (9,), True),
    "multi_vehicle": Scenario("multi_vehicle", "多车竞争与下客释放", 1, 5, "back", 31, 60, (1, 2), True,
                               preloaded_people=9, alight_people=3),
    "capacity_shortage": Scenario("capacity_shortage", "车辆不足", 3, 20, "back", 26, 60, (), True,
                                  force_no_vehicles=True),
    "driver_refusal": Scenario("driver_refusal", "司机拒单与顺延", 2, 15, "back", 18, 180, (5, 6), True,
                               reject_first_task=True),
}


def arrival_trace(scenario: Scenario, seed: int) -> list[dict]:
    """生成固定随机种子的乘客到达序列，不生成正式乘客账号。"""
    material = f"{seed}|{scenario.key}".encode("utf-8")
    scenario_seed = int.from_bytes(hashlib.sha256(material).digest()[:8], "big")
    rng = random.Random(scenario_seed)
    if scenario.passenger_count <= 1:
        offsets = [0.0]
    else:
        step = scenario.arrival_window_seconds / (scenario.passenger_count - 1)
        offsets = [max(0.0, i * step + rng.uniform(-0.2 * step, 0.2 * step))
                   for i in range(scenario.passenger_count)]
        offsets.sort()
    return [{"simulatedPassengerNo": f"{scenario.key}-P{i + 1:03d}",
             "arrivalSecond": round(offset, 3), "source": "simulation"}
            for i, offset in enumerate(offsets)]
