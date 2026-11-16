"""卡尔曼滤波融合: K=P/(P+R), x=x+K*(z-x), P=(1-K)*P"""
from collections import defaultdict

class KalmanFusion:
    def __init__(self, Q=1.0, R_default=4.0):
        self.Q = Q
        self.R_default = R_default
        self.P = defaultdict(lambda: 10.0)

    def fuse(self, station_id, line_id, direction, observed_count, variance, source):
        key = f"{station_id}_{line_id}_{direction}"
        P_pred = self.P[key] + self.Q
        R = variance if variance > 0 else self.R_default
        K = P_pred / (P_pred + R)
        P_updated = (1 - K) * P_pred
        self.P[key] = P_updated
        confidence = 1.0 / (1.0 + P_updated)
        return {
            "key": key,
            "source": source,
            "observedCount": observed_count,
            "kalmanGain": round(K, 4),
            "confidence": round(confidence, 4),
            "varianceP": round(P_updated, 4),
            "reasonCode": "KALMAN_FUSION",
            "counterfactual": f"若仅用观测值z={observed_count},方差={R};融合后方差降至{round(P_updated,4)}"
        }

    def get_P(self, key):
        return self.P[key]