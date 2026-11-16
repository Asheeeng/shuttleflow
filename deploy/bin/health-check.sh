#!/usr/bin/env bash
set -Eeuo pipefail
BASE="${SHUTTLE_PUBLIC_BASE_URL:-http://127.0.0.1:8080}"
AI="${SHUTTLE_AI_HEALTH_URL:-http://127.0.0.1:8000/health}"
curl --fail --silent --show-error --max-time 3 "$BASE/api/health/live" >/dev/null
curl --fail --silent --show-error --max-time 3 "$BASE/api/health/ready" >/dev/null
curl --fail --silent --show-error --max-time 3 "$AI" >/dev/null
echo "Java、数据库就绪检查与 AI 健康检查均通过。"
