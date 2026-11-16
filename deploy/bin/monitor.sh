#!/usr/bin/env bash
set -Eeuo pipefail
if [[ -r /etc/shuttleflow/secrets.env ]]; then set -a; source /etc/shuttleflow/secrets.env; set +a; fi
echo "=== 主机内存与磁盘 ==="
free -h
df -h /
echo "=== 服务状态 ==="
systemctl --no-pager --full status shuttleflow shuttle-ai mysql redis-server || true
echo "=== 应用进程 RSS/CPU ==="
ps -eo pid,comm,%cpu,rss --sort=-rss | awk 'NR==1 || /java|uvicorn|python|mysqld|redis-server/' | head -20
echo "=== MySQL连接、慢查询计数 ==="
mysql --defaults-extra-file=/etc/shuttleflow/monitor.cnf --batch --skip-column-names \
  -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Slow_queries','Questions','Max_used_connections')"
echo "=== Redis内存、客户端、事件流 ==="
REDISCLI_AUTH="${SHUTTLE_REDIS_PASSWORD:?请先加载 secrets.env}" redis-cli -h 127.0.0.1 INFO memory | grep -E 'used_memory_human|maxmemory_human'
REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 INFO clients | grep 'connected_clients'
REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 XLEN shuttle:events
