#!/usr/bin/env bash
set -Eeuo pipefail
for _ in $(seq 1 60); do
  if mysqladmin --no-defaults --host=127.0.0.1 --port=3308 --connect-timeout=1 ping >/dev/null 2>&1 &&
      REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -p 16379 PING | grep -qx PONG &&
      curl -fsS --max-time 2 http://127.0.0.1:18000/health >/dev/null; then exit 0; fi
  sleep 1
done
exit 1
