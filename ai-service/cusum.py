"""CUSUM突变检测: St = max{0, St-1 + (xt - μ0 - k)}"""
from collections import defaultdict

class CusumDetector:
    def __init__(self, k=1.5, h_yellow=5.0, h_orange=10.0, h_red=20.0):
        self.k = k
        self.h_yellow = h_yellow
        self.h_orange = h_orange
        self.h_red = h_red
        self.states = defaultdict(lambda: {"St": 0.0, "mu0": 2.0})

    def detect(self, station_id, line_id, direction, arrival_rate):
        key = f"{station_id}_{line_id}_{direction}"
        s = self.states[key]
        s["St"] = max(0, s["St"] + (arrival_rate - s["mu0"] - self.k))
        s["mu0"] = s["mu0"] * 0.9 + arrival_rate * 0.1
        level = None
        if s["St"] >= self.h_red:
            level = "red"
            s["St"] = 0
        elif s["St"] >= self.h_orange:
            level = "orange"
        elif s["St"] >= self.h_yellow:
            level = "yellow"
        return {
            "key": key,
            "St": round(s["St"], 2),
            "mu0": round(s["mu0"], 2),
            "arrivalRate": arrival_rate,
            "level": level,
            "action": "dispatch" if level in ("red", "orange") else ("alert" if level == "yellow" else "monitor"),
            "reasonCode": f"CUSUM_{level.upper()}" if level else "CUSUM_NORMAL",
            "confidence": min(0.95, 0.6 + s["St"] * 0.02)
        }

    def get_status(self):
        return {k: {"St": round(v["St"], 2), "mu0": round(v["mu0"], 2)} for k, v in self.states.items()}