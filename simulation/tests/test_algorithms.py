import importlib.util
import pathlib
import unittest

AI_DIR = pathlib.Path(__file__).resolve().parents[2] / "ai-service"


def load_module(name):
    spec = importlib.util.spec_from_file_location(name, AI_DIR / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ConnectedAlgorithmTests(unittest.TestCase):
    def test_kalman_returns_fused_count_from_previous_queue_estimate(self):
        KalmanFusion = load_module("kalman").KalmanFusion
        fusion = KalmanFusion()
        result = fusion.fuse(2, 1, "go", observed_count=20, prior_count=4,
                             variance=4.0, source="simulation")
        self.assertEqual(result["previousEstimate"], 4)
        self.assertGreater(result["fusedCount"], 4)
        self.assertLess(result["fusedCount"], 20)

    def test_cusum_red_signal_is_deterministic(self):
        CusumDetector = load_module("cusum").CusumDetector
        detector = CusumDetector(k=1.5, h_yellow=5, h_orange=10, h_red=20)
        result = detector.detect(19, 3, "go", 30)
        self.assertEqual(result["level"], "red")
        self.assertEqual(result["action"], "dispatch")

    def test_allocator_never_assigns_more_than_capacity_or_waiting(self):
        allocator = load_module("assign").MultiVehicleAllocator()
        result = allocator.assign([
            {"vehicleId": 1, "plateNo": "9", "capacity": 13, "currentLoad": 12},
            {"vehicleId": 2, "plateNo": "10", "capacity": 13, "currentLoad": 4},
        ], waiting=30, station_id=19, line_id=3, direction="go")
        total = sum(x["boardCount"] for x in result["allocation"])
        self.assertLessEqual(total, 30)
        self.assertLessEqual(total + result["leftover"], 30)
        for allocation in result["allocation"]:
            vehicle = next(v for v in result["vehicles"] if v["vehicleId"] == allocation["vehicleId"])
            self.assertLessEqual(allocation["boardCount"], vehicle["assignable"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
