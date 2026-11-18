#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
OLD_RUN_DIR="/private/tmp/shuttleflow-v13-runtime-3308"
RUN_DIR="${SHUTTLE_V13_RUN_DIR:-/private/tmp/shuttleflow-v13-accept-$(date +%Y%m%d%H%M%S)-$$}"
STARTED=0
cleanup() {
  if [[ "$STARTED" == 1 ]]; then
    SHUTTLE_V13_RUN_DIR="$RUN_DIR" python3 "$ROOT/simulation/runtime.py" stop --run-dir "$RUN_DIR" || true
  fi
}
trap cleanup EXIT

if [[ -e "$RUN_DIR" ]]; then
  echo "验收运行目录已存在：$RUN_DIR；为避免覆盖，停止。" >&2
  exit 1
fi
if [[ -f "$OLD_RUN_DIR/app.env" ]]; then
  python3 "$ROOT/simulation/runtime.py" stop --run-dir "$OLD_RUN_DIR"
fi
export SHUTTLE_V13_RUN_DIR="$RUN_DIR"
python3 "$ROOT/simulation/runtime.py" start --run-dir "$RUN_DIR"
STARTED=1
bash "$ROOT/simulation/run_local.sh" --scenario all --seed 20261117 --speed 60
bash "$ROOT/simulation/headless_browser_smoke.sh"
echo "V1.3 HTTP仿真与Playwright无头浏览器验收均已完成；测试库和结果保留供复核。"
