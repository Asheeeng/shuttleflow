"""短时客流预测: 15分钟粒度 P50/P90"""
import numpy as np
from collections import defaultdict
import time

class DemandPredictor:
    def __init__(self):
        self.history = defaultdict(list)
        self.conversion_rates = defaultdict(lambda: 0.3)

    def predict(self, station_id, line_id, direction, history=None):
        key = f"{station_id}_{line_id}_{direction}"
        if history:
            self.history[key].extend(history)
        hist = self.history[key][-20:]
        if len(hist) < 3:
            p50 = 5
            p90 = 10
            method = "default"
        else:
            arr = np.array(hist)
            weights = np.exp(np.linspace(-1, 0, len(arr)))
            weighted_avg = np.average(arr, weights=weights)
            std = np.std(arr)
            p50 = max(0, int(weighted_avg))
            p90 = max(p50, int(weighted_avg + 1.28 * std))
            method = "ewma"
        lower = int(p50 * 0.8)
        upper = int(p90 * 1.2)
        return {
            "p50": p50, "p90": p90,
            "lower": lower, "upper": upper,
            "method": method,
            "confidence": min(0.95, 0.5 + len(hist) * 0.02),
            "reasonCode": "EWMA_PREDICT",
            "counterfactual": f"若按历史均值={int(np.mean(hist)) if hist else 5},P50={p50}差值={p50 - (int(np.mean(hist)) if hist else 5)}"
        }

    def update_conversion(self, key, rate):
        self.conversion_rates[key] = rate

    def course_potential(self, student_count, conversion_rate):
        potential = student_count * conversion_rate
        return {
            "potential": int(potential),
            "p50": int(potential * 0.8),
            "p90": int(potential * 1.2),
            "interval": [int(potential * 0.6), int(potential * 1.4)]
        }