#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
RUN_DIR="${SHUTTLE_V13_RUN_DIR:-/private/tmp/shuttleflow-v13-runtime-3308}"
if [[ ! -r "$RUN_DIR/app.env" ]]; then
  python3 "$ROOT/simulation/runtime.py" start --run-dir "$RUN_DIR"
else
  # 应用源码可能已更新；重启隔离Java/AI/Redis以使用当前分支构建，但不重置测试库。
  python3 "$ROOT/simulation/runtime.py" refresh --run-dir "$RUN_DIR"
fi
set -a
source "$RUN_DIR/app.env"
set +a
cd "$ROOT"
exec python3 -m simulation.run "$@"
