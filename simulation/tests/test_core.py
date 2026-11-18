import threading
import time
import unittest

from simulation.clock import SimulationClock, SimulationStopped
from simulation.metrics import summarize_events
from simulation.policies import simulate_baseline
from simulation.scenarios import SCENARIOS, arrival_trace


class ScenarioTests(unittest.TestCase):
    def test_fixed_seed_reproduces_identical_passenger_trace(self):
        scenario = SCENARIOS["class_peak"]
        self.assertEqual(arrival_trace(scenario, 42), arrival_trace(scenario, 42))
        self.assertNotEqual(arrival_trace(scenario, 42), arrival_trace(scenario, 43))

    def test_baselines_replay_same_arrivals_with_capacity_guard(self):
        arrivals = [float(i * 2) for i in range(20)]
        threshold = simulate_baseline(arrivals, policy="simple_threshold", vehicle_count=1,
                                      threshold=8, eta_seconds=5, round_trip_seconds=60)
        timetable = simulate_baseline(arrivals, policy="fixed_timetable", vehicle_count=1,
                                       peak=False, round_trip_seconds=900)
        self.assertEqual(threshold["已签到人数"], 20)
        self.assertLessEqual(threshold["单趟最大上客人数"], 13)
        self.assertEqual(threshold["已上车人数"] + threshold["未上车人数"], 20)
        self.assertGreater(timetable["平均候车秒"], threshold["平均候车秒"])

    def test_pre_alighting_releases_real_capacity_in_model(self):
        result = simulate_baseline([float(i) for i in range(20)], policy="fixed_timetable",
                                   vehicle_count=1, peak=False, preload=9, alight_fraction=0.3)
        self.assertEqual(result["首趟下客人数"], 3)
        self.assertEqual(result["首趟上客人数"], 7)
        self.assertLessEqual(result["单趟最大上客人数"], 13)

    def test_virtual_clock_pause_resume_and_stop(self):
        clock = SimulationClock(speed=20)
        done = threading.Event()
        worker = threading.Thread(target=lambda: (clock.wait_until(2.0), done.set()), daemon=True)
        worker.start()
        time.sleep(0.01)
        clock.pause()
        frozen = clock.now()
        time.sleep(0.02)
        self.assertAlmostEqual(clock.now(), frozen, delta=0.05)
        clock.resume()
        worker.join(timeout=1.0)
        self.assertTrue(done.is_set())

        stopped = SimulationClock(speed=1)
        stopped.pause()
        error = []
        waiting = threading.Thread(target=lambda: self._capture_stop(stopped, error), daemon=True)
        waiting.start()
        time.sleep(0.01)
        stopped.stop()
        waiting.join(timeout=1.0)
        self.assertTrue(error and isinstance(error[0], SimulationStopped))

    @staticmethod
    def _capture_stop(clock, errors):
        try:
            clock.wait_until(10)
        except Exception as exc:  # test records the expected stop signal
            errors.append(exc)


class MetricTests(unittest.TestCase):
    def test_p95_and_completion_metrics_are_explicit(self):
        metrics = summarize_events([0, 2, 4, 6],
                                   [{"time": 10, "count": 2, "capacity": 13, "loadAfter": 2},
                                    {"time": 20, "count": 1, "capacity": 13, "loadAfter": 1}],
                                   max_queue=4, task_created=2, task_completed=1)
        self.assertEqual(metrics["已上车人数"], 3)
        self.assertEqual(metrics["未上车人数"], 1)
        self.assertEqual(metrics["任务完成率"], 0.5)
        self.assertIsNotNone(metrics["P95候车秒"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
