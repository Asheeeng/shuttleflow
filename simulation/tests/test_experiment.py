import tempfile
import unittest
from pathlib import Path

from simulation.experiment import Experiment, catalog, cusum_step, route_eta, score
from simulation.operation_config import configuration


class ExperimentTests(unittest.TestCase):
    def setUp(self):
        self.routes, self.courses = catalog(Path(__file__).resolve().parents[2] /
                                           "backend/src/main/resources/sql/init.sql")

    def test_java_algorithm_contract_and_route_snapshot(self):
        self.assertEqual(68, sum(len(route["stops"]) for route in self.routes.values()))
        route = self.routes["1:go"]["stops"]
        self.assertEqual(440, route_eta(route, 0, 2))
        self.assertEqual(600, route_eta(route, 2, 0))
        self.assertAlmostEqual(.4*440/1200+.4*5/13+.1, score(440, 5, 13, False))
        self.assertEqual((5.5, 1, 1), cusum_step(0, 0, 9))
        self.assertEqual((11., 2, 2), cusum_step(5.5, 1, 9))
        self.assertEqual((0., 0, 3), cusum_step(11., 2, 13))

    def test_three_strategies_share_trace_and_respect_capacity(self):
        base = {"scenario":"class_peak", "passengers":100, "minutes":100, "wait":45, "flow":"burst",
                "reject":True,"fleet":[{"lineId":2,"count":4,"capacity":16,"headway":180,"travel":90,"dwell":8}]}
        with tempfile.TemporaryDirectory() as directory:
            runs = []
            for policy in ("fixed","threshold","smart"):
                run = Experiment(configuration({**base,"policy":policy}), self.routes, self.courses, Path(directory))
                run.run(realtime=False)
                runs.append(run)
                result = run.snapshot["metrics"]
                self.assertEqual(100, result["checkins"])
                self.assertEqual(result["checkins"], result["boarded"]+result["waiting"]+result["cancelled"])
                self.assertGreater(result["boarded"], 0)
                self.assertTrue(all(0 <= car["load"] <= car["capacity"] for car in run.snapshot["vehicles"]))
                self.assertTrue(all(0 <= stop["load"] <= trip["capacity"] for trip in run.trips.values()
                                    for stop in trip["stops"] if stop["load"] is not None))
                self.assertTrue((Path(directory)/(run.id+".json")).is_file())
            self.assertEqual(runs[0].plan, runs[1].plan)
            self.assertEqual(runs[0].plan, runs[2].plan)
            self.assertFalse(runs[0].tasks)
            self.assertTrue(any(step["action"] == "reassigned" for step in runs[2].steps))
            self.assertTrue(any(t["reason"].startswith("CUSUM") for t in runs[2].tasks))


if __name__ == "__main__":
    unittest.main()
