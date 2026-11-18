#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
RUN_DIR="${SHUTTLE_V13_RUN_DIR:-/private/tmp/shuttleflow-v13-runtime-3308}"
exec python3 "$ROOT/simulation/runtime.py" stop --run-dir "$RUN_DIR"
