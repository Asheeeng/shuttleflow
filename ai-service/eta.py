"""ETA预测: 最近到站事件 + 站间历史时长 + 实时修正"""
from collections import defaultdict
import numpy as np

class EtaPredictor:
    def __init__(self):
        self.travel_history = defaultdict(list)
        self.buffer_sec = 60

    def update_travel(self, from_station, to_station, actual_sec):
        key = f"{from_station}_{to_station}"
        self.travel_history[key].append(actual_sec)
        if len(self.travel_history[key]) > 50:
            self.travel_history[key] = self.travel_history[key][-50:]

    def predict_eta(self, from_station, to_station, base_travel_sec, current_load=0, capacity=13):
        key = f"{from_station}_{to_station}"
        hist = self.travel_history[key]
        if len(hist) >= 3:
            travel = int(np.median(hist))
            method = "historical_median"
        else:
            travel = base_travel_sec
            method = "base_estimate"
        load_factor = 1.0 + (current_load / capacity) * 0.2 if capacity > 0 else 1.0
        eta_sec = int(travel * load_factor + self.buffer_sec)
        return {
            "etaSec": eta_sec,
            "etaMin": eta_sec // 60,
            "travelSec": travel,
            "loadFactor": round(load_factor, 3),
            "method": method,
            "confidence": min(0.95, 0.6 + len(hist) * 0.01),
            "reasonCode": "ETA_PREDICT",
            "counterfactual": f"基础时长={base_travel_sec}s,历史中位数={travel}s,负载因子={round(load_factor,3)}"
        }