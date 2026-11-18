#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
RUN_DIR="$(mktemp -d /private/tmp/shuttleflow-operation.XXXXXXXX)"
rmdir "$RUN_DIR"
BROWSER="$ROOT/simulation/browser-tests"
export SHUTTLE_BROWSER_OUTPUT="${SHUTTLE_BROWSER_OUTPUT:-$ROOT/output/operation-v13/$(date +%Y%m%d-%H%M%S)}"
export PLAYWRIGHT_BROWSERS_PATH="${SHUTTLE_V13_BROWSER_PATH:-/private/tmp/shuttleflow-v13-playwright-browsers}"
if [[ ! -x "$BROWSER/node_modules/.bin/playwright" ]]; then
  npm --prefix "$BROWSER" ci --no-audit --no-fund
fi
if ! "$BROWSER/node_modules/.bin/playwright" install --list | rg -q chromium_headless_shell; then
  "$BROWSER/node_modules/.bin/playwright" install --only-shell chromium
fi
trap 'python3 "$ROOT/simulation/runtime.py" stop --run-dir "$RUN_DIR"' EXIT
python3 "$ROOT/simulation/runtime.py" start --run-dir "$RUN_DIR"
source "$RUN_DIR/app.env"
if [[ "${1:-}" != "--lifecycle-only" ]]; then
  node "$BROWSER/operation.mjs"
fi
cd "$ROOT"
python3 -m simulation.operation_lifecycle_check
