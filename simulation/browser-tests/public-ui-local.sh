#!/usr/bin/env bash
set -Eeuo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../.." && pwd)"
[[ "${1:-}" =~ ^[a-f0-9]{12}$ ]] || { echo '请提供公网验收报告里的 12 位运行号'; exit 1; }
[[ -d "$HERE/node_modules/playwright" ]] || { echo '请先在 simulation/browser-tests 执行 npm ci'; exit 1; }
read -rsp 'HTTPS 入口密码：' SHUTTLE_V13_ACCESS_PASSWORD </dev/tty
echo
read -rsp 'v13admin 管理员密码：' SHUTTLE_V13_ADMIN_PASSWORD </dev/tty
echo
export SHUTTLE_V13_ACCESS_PASSWORD SHUTTLE_V13_ADMIN_PASSWORD
export SHUTTLE_V13_RUN_ID="$1"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/private/tmp/shuttleflow-v13-playwright-browsers}"
export SHUTTLE_BROWSER_OUTPUT="$ROOT/output/playwright/v13-vps"
[[ -d "$PLAYWRIGHT_BROWSERS_PATH" ]] || { echo '缺少项目 Chromium 浏览器，请先安装'; exit 1; }
exec node "$HERE/public-ui.mjs"
