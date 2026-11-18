#!/usr/bin/env python3
"""Regenerate CSV, SVG and report from persisted API-run scenario JSON without re-running business events."""
import argparse
import json
import pathlib

from simulation.run import SimulationRunner


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", type=pathlib.Path)
    parser.add_argument("--seed", type=int, default=20261117)
    parser.add_argument("--speed", type=float, default=60.0)
    args = parser.parse_args()
    summary_path = args.result_dir / "summary.json"
    if not summary_path.is_file():
        raise SystemExit("缺少summary.json；不会从不完整场景猜测结果。")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    keys = [row["scenario"] for row in summary.get("scenarios", [])]
    scenarios = []
    for key in keys:
        path = args.result_dir / f"scenario_{key}.json"
        if not path.is_file():
            raise SystemExit(f"场景原始文件缺失：{path}")
        scenarios.append(json.loads(path.read_text(encoding="utf-8")))
    runner = SimulationRunner("http://127.0.0.1:18080", args.speed, args.seed, args.result_dir)
    runner.scenario_results = scenarios
    event_log = args.result_dir / "api_events.jsonl"
    if event_log.is_file():
        runner.events = [json.loads(line) for line in event_log.read_text(encoding="utf-8").splitlines() if line]
    runner._write_outputs()
    print(f"已从{len(scenarios)}个已验证场景JSON重建CSV/SVG/Markdown，不重复发送业务HTTP请求：{args.result_dir}")


if __name__ == "__main__":
    main()
