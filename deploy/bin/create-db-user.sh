#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo 运行此脚本。" >&2
  exit 1
fi

DB_NAME="${SHUTTLE_DB_NAME:-shuttle_db}"
if [[ ! "$DB_NAME" =~ ^[A-Za-z0-9_]{1,48}$ ]]; then
  echo "数据库名称格式无效。" >&2
  exit 1
fi

SECRETS=/etc/shuttleflow/secrets.env
BACKUP_CNF=/etc/shuttleflow/backup.cnf
if [[ -e "$SECRETS" || -e "$BACKUP_CNF" ]]; then
  echo "检测到已有密钥文件；为避免意外轮换或覆盖，脚本停止。" >&2
  exit 1
fi

APP_EXISTS="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM mysql.user WHERE user='shuttle_app' AND host='127.0.0.1'")"
BACKUP_EXISTS="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM mysql.user WHERE user='shuttle_backup' AND host='localhost'")"
if [[ "$APP_EXISTS" != "0" || "$BACKUP_EXISTS" != "0" ]]; then
  echo "检测到同名数据库账号；请先人工核对，不会修改已有账号密码。" >&2
  exit 1
fi

APP_PASSWORD="$(openssl rand -hex 32)"
BACKUP_PASSWORD="$(openssl rand -hex 32)"
MONITOR_PASSWORD="$(openssl rand -hex 32)"
REDIS_PASSWORD="$(openssl rand -hex 32)"
AI_TOKEN="$(openssl rand -hex 32)"
install -d -o root -g root -m 0750 /etc/shuttleflow

mysql --protocol=socket --user=root <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'shuttle_app'@'127.0.0.1' IDENTIFIED BY '$APP_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$DB_NAME\`.* TO 'shuttle_app'@'127.0.0.1';
CREATE USER 'shuttle_backup'@'localhost' IDENTIFIED BY '$BACKUP_PASSWORD';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON \`$DB_NAME\`.* TO 'shuttle_backup'@'localhost';
CREATE USER 'shuttle_monitor'@'localhost' IDENTIFIED BY '$MONITOR_PASSWORD';
GRANT SELECT ON performance_schema.* TO 'shuttle_monitor'@'localhost';
SQL

umask 077
cat > "$SECRETS" <<EOF
SHUTTLE_DB_USER=shuttle_app
SHUTTLE_DB_PASSWORD=$APP_PASSWORD
SHUTTLE_BACKUP_DB_USER=shuttle_backup
SHUTTLE_BACKUP_DB_PASSWORD=$BACKUP_PASSWORD
SHUTTLE_MONITOR_DB_USER=shuttle_monitor
SHUTTLE_MONITOR_DB_PASSWORD=$MONITOR_PASSWORD
SHUTTLE_REDIS_PASSWORD=$REDIS_PASSWORD
SHUTTLE_AI_SERVICE_TOKEN=$AI_TOKEN
EOF
cat > "$BACKUP_CNF" <<EOF
[client]
user=shuttle_backup
password=$BACKUP_PASSWORD
host=localhost
protocol=socket
EOF
cat > /etc/shuttleflow/monitor.cnf <<EOF
[client]
user=shuttle_monitor
password=$MONITOR_PASSWORD
host=localhost
protocol=socket
EOF
chmod 0600 "$SECRETS" "$BACKUP_CNF" /etc/shuttleflow/monitor.cnf
chown root:root "$SECRETS" "$BACKUP_CNF" /etc/shuttleflow/monitor.cnf
echo "已创建数据库及最小权限账号。密钥保存在 root-only 文件中；未输出到终端。"
echo "请将 Redis 密码配置到 redis.conf 的 requirepass，并由 systemd 为 Java 与 AI 服务加载 secrets.env。"
