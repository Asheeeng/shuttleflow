#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$(id -u)" -ne 0 ]]; then echo "请使用 sudo 运行此脚本。" >&2; exit 1; fi
if [[ ! -r /etc/shuttleflow/secrets.env ]]; then echo "请先运行 create-db-user.sh 生成 secrets.env。" >&2; exit 1; fi
set -a
source /etc/shuttleflow/secrets.env
set +a
: "${SHUTTLE_REDIS_PASSWORD:?Redis密码未配置}"
CONF=/etc/redis/shuttleflow.conf
MAIN=/etc/redis/redis.conf
if [[ -e "$CONF" ]]; then echo "配置已存在：$CONF；拒绝覆盖，请人工核对。" >&2; exit 1; fi
install -d -o redis -g redis -m 0750 /etc/redis
install -o root -g redis -m 0640 "$(dirname "$0")/../redis/shuttleflow.conf" "$CONF"
printf '\nrequirepass %s\n' "$SHUTTLE_REDIS_PASSWORD" >> "$CONF"
if ! grep -Fxq 'include /etc/redis/shuttleflow.conf' "$MAIN"; then
  printf '\n# ShuttleFlow loopback and memory policy\ninclude /etc/redis/shuttleflow.conf\n' >> "$MAIN"
fi
systemctl restart redis-server
systemctl is-active --quiet redis-server
echo "Redis已限制为本机监听并启用认证和内存上限。"
