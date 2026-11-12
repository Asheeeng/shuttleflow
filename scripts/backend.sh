#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then echo '请先按照 README 创建 .env 并启动 MySQL'; exit 1; fi
set -a
source .env
set +a
cd backend
if [[ "${1:-}" == "--jar" ]]; then
  exec java -jar target/campus-shuttle-0.2.0.jar
fi
exec mvn spring-boot:run
