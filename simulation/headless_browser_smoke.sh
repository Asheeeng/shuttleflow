#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
RUN_DIR="${SHUTTLE_V13_RUN_DIR:-/private/tmp/shuttleflow-v13-runtime-3308}"
PACKAGE_DIR="$ROOT/simulation/browser-tests"
ARTIFACT_DIR="$ROOT/output/playwright/v13"
PLAYWRIGHT_BROWSERS="${SHUTTLE_V13_BROWSER_PATH:-/private/tmp/shuttleflow-v13-playwright-browsers}"

[[ -r "$RUN_DIR/app.env" ]] || { echo "隔离环境配置不存在：$RUN_DIR/app.env" >&2; exit 1; }
command -v node >/dev/null || { echo "缺少Node.js；Playwright项目依赖无法运行。" >&2; exit 1; }
command -v npm >/dev/null || { echo "缺少npm；Playwright项目依赖无法安装。" >&2; exit 1; }
[[ -f "$PACKAGE_DIR/package-lock.json" ]] || { echo "缺少项目Playwright lockfile。" >&2; exit 1; }
set -a
source "$RUN_DIR/app.env"
set +a
export PLAYWRIGHT_BROWSERS_PATH="$PLAYWRIGHT_BROWSERS"
export SHUTTLE_TEST_API_BASE="http://127.0.0.1:18080"
export SHUTTLE_BROWSER_OUTPUT="$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR" "$PLAYWRIGHT_BROWSERS_PATH"
chmod 0700 "$ARTIFACT_DIR" "$PLAYWRIGHT_BROWSERS_PATH"

if [[ ! -x "$PACKAGE_DIR/node_modules/.bin/playwright" ]]; then
  (cd "$PACKAGE_DIR" && npm ci --no-audit --no-fund)
fi
CHROMIUM_PATH="$(cd "$PACKAGE_DIR" && node -e "console.log(require('playwright').chromium.executablePath())")"
if [[ ! -x "$CHROMIUM_PATH" ]]; then
  (cd "$PACKAGE_DIR" && ./node_modules/.bin/playwright install chromium)
fi
(cd "$PACKAGE_DIR" && node smoke.mjs)
