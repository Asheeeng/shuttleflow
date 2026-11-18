#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
[[ $EUID == 0 ]] || { echo "请在现有可信 SSH 会话通过 sudo 运行"; exit 1; }
SRC="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
DOMAIN=v13.shuttleflow-gzu.duckdns.org
CONF=/etc/shuttle-v13
ROOT=/opt/shuttle-v13
STATE=/var/lib/shuttle-v13
SITE=/etc/nginx/sites-available/shuttle-v13
LINK=/etc/nginx/sites-enabled/shuttle-v13
BACKUP="/var/backups/shuttleflow/pre-v13-$(date -u +%Y%m%dT%H%M%SZ)"
[[ -f "$SRC/VERSION" && -f "$SRC/shuttle.jar" && -f "$SRC/backend/src/main/resources/sql/init.sql" ]]
[[ "$(cat "$SRC/VERSION")" =~ ^[a-f0-9]{40}$ ]]
(cd "$SRC" && sha256sum -c SHA256SUMS)
[[ ! -e "$CONF" && ! -e "$ROOT" && ! -e "$STATE" && ! -e "$SITE" && ! -L "$LINK" ]] || { echo "发现既有 V1.3 安装，停止。"; exit 1; }
for port in 3308 16379 18000 18001 18080; do
  if ss -Hltn "sport = :$port" | grep -q .; then echo "端口 $port 已占用"; exit 1; fi
done
for tool in /usr/sbin/mysqld /usr/sbin/nginx /usr/bin/certbot /usr/bin/redis-server /usr/bin/java /usr/bin/mysql /usr/bin/mysqldump; do
  [[ -x "$tool" ]] || { echo "缺少 $tool"; exit 1; }
done
available="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
(( available >= 1700000 )) || { echo "可用内存低于 1.7 GB，未启动第二套系统"; exit 1; }
read -rsp '请输入 V1.3 应用管理员新密码（至少 20 位字母数字）: ' ADMIN </dev/tty; echo
read -rsp '请输入 V1.3 HTTPS 入口新密码（至少 20 位字母数字）: ' EDGE </dev/tty; echo
[[ "$ADMIN" =~ ^[A-Za-z0-9]{20,128}$ && "$EDGE" =~ ^[A-Za-z0-9]{20,128}$ && "$ADMIN" != "$EDGE" ]] || {
  echo "密码格式不符或两处密码相同"; exit 1;
}
install -d -m 0700 "$BACKUP"
tar -C /opt -czf "$BACKUP/v12-app.tar.gz" shuttleflow
tar -C /etc -czf "$BACKUP/nginx.tar.gz" nginx
backup_script=/opt/shuttleflow/current/deploy/bin/backup-mysql.sh
[[ -r "$backup_script" ]] || backup_script="$SRC/deploy/bin/backup-mysql.sh"
SHUTTLE_BACKUP_DIR="$BACKUP" bash "$backup_script"
for archive in "$BACKUP"/*.gz; do gzip -t "$archive"; done
echo "V1.2 应用、Nginx 及数据库备份通过 gzip 校验：$BACKUP"

BOOT_PID=
rollback() {
  local status=$?
  if (( status )); then
    [[ -z "$BOOT_PID" ]] || kill "$BOOT_PID" 2>/dev/null || true
    systemctl disable --now shuttle-v13-backup.timer shuttle-v13-monitor.timer 2>/dev/null || true
    systemctl stop shuttle-v13.target 2>/dev/null || true
    if [[ -L "$LINK" && "$(readlink "$LINK")" == "$SITE" ]]; then
      rm "$LINK"
      /usr/sbin/nginx -t && systemctl reload nginx || true
    fi
    echo "安装失败，已撤下 V1.3 路由和服务。保留备份、隔离库及日志。"
  fi
}
trap rollback EXIT
id shuttlev13 >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin shuttlev13
id shuttlev13db >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin shuttlev13db
install -d -m 0755 "$ROOT/releases"
install -d -m 0750 -o root -g shuttlev13 "$ROOT/releases/$(cat "$SRC/VERSION")"
RELEASE="$ROOT/releases/$(cat "$SRC/VERSION")"
cp -R "$SRC"/. "$RELEASE/"
chgrp -R shuttlev13 "$RELEASE"
chmod -R g+rX,go-w "$RELEASE"
ln -s "$RELEASE" "$ROOT/current"
install -d -m 0750 -o root -g shuttlev13 "$CONF"
install -d -m 0700 -o shuttlev13 -g shuttlev13 "$STATE"
install -d -m 0700 -o shuttlev13db -g shuttlev13db /var/lib/shuttle-v13-mysql
install -d -m 0700 -o shuttlev13 -g shuttlev13 /var/lib/shuttle-v13-redis
/usr/bin/python3 -m venv "$ROOT/venv"
"$ROOT/venv/bin/pip" install --disable-pip-version-check --no-input -r "$RELEASE/simulation/requirements-ai.txt"
DB="shuttle_v13_sim_$(openssl rand -hex 4)"
DBPASS="$(openssl rand -hex 32)"
DBROOT="$(openssl rand -hex 32)"
BACKPASS="$(openssl rand -hex 32)"
REDISPASS="$(openssl rand -hex 32)"
TOKEN="$(openssl rand -hex 32)"
AI_TOKEN="$(openssl rand -hex 32)"
cat > "$CONF/app.env" <<ENV
SHUTTLE_DB_URL=jdbc:mysql://127.0.0.1:3308/$DB?useSSL=true&requireSSL=true&serverTimezone=Asia/Shanghai
SHUTTLE_DB_USER=shuttle_v13
SHUTTLE_DB_PASSWORD=$DBPASS
SHUTTLE_DB_POOL_SIZE=8
SHUTTLE_REDIS_HOST=127.0.0.1
SHUTTLE_REDIS_PORT=16379
SHUTTLE_REDIS_PASSWORD=$REDISPASS
SHUTTLE_AI_SERVICE_URL=http://127.0.0.1:18000
SHUTTLE_AI_SERVICE_TOKEN=$AI_TOKEN
SHUTTLE_BACKEND_API_URL=http://127.0.0.1:18080/api
SHUTTLE_BOOTSTRAP_ADMIN_USERNAME=v13admin
SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD=$ADMIN
SHUTTLE_TEST_ADMIN_USERNAME=v13admin
SHUTTLE_TEST_ADMIN_PASSWORD=$ADMIN
SHUTTLE_SESSION_COOKIE_NAME=SHUTTLE_V13_SESSION
SHUTTLE_SESSION_COOKIE_SECURE=true
SHUTTLE_CORS_ALLOWED_ORIGINS=https://$DOMAIN
SHUTTLE_OPERATION_ENABLED=true
SHUTTLE_OPERATION_TOKEN=$TOKEN
SHUTTLE_OPERATION_WALL_SECONDS=1800
SHUTTLE_OPERATION_MAX_PASSENGERS=150
SHUTTLE_OPERATION_MAX_VEHICLES=6
SHUTTLE_SIMULATION_ENABLED=true
SHUTTLE_CUSUM_SCHEDULER_ENABLED=false
SHUTTLE_DISPATCH_COURSE_PREPOSITION_ENABLED=false
SHUTTLE_RATE_LIMIT_AUTH=10
SHUTTLE_RATE_LIMIT_PASSENGER=20
SHUTTLE_V13_RUN_DIR=$STATE
ENV
cat > "$CONF/backup.cnf" <<ENV
[client]
host=127.0.0.1
protocol=tcp
port=3308
user=shuttle_v13_backup
password=$BACKPASS
ENV
printf '%s\n' "$DB" > "$CONF/schema"
cat > "$CONF/redis.conf" <<ENV
bind 127.0.0.1
port 16379
protected-mode yes
requirepass $REDISPASS
maxmemory 64mb
maxmemory-policy noeviction
save ""
appendonly no
dir /var/lib/shuttle-v13-redis
ENV
chgrp shuttlev13 "$CONF/redis.conf"
chmod 0640 "$CONF/redis.conf"

MYSQL_DIR=/var/lib/shuttle-v13-mysql
/usr/sbin/mysqld --no-defaults --initialize-insecure --user=shuttlev13db --datadir="$MYSQL_DIR" >"$BACKUP/v13-mysql-init.log" 2>&1
/usr/sbin/mysqld --no-defaults --datadir="$MYSQL_DIR" --user=shuttlev13db --skip-networking --mysqlx=OFF --socket="$MYSQL_DIR/bootstrap.sock" --pid-file="$MYSQL_DIR/bootstrap.pid" --innodb-buffer-pool-size=128M >>"$BACKUP/v13-mysql-init.log" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 40); do [[ -S "$MYSQL_DIR/bootstrap.sock" ]] && break; sleep 1; done
MYSQL=(/usr/bin/mysql --no-defaults -uroot --protocol=socket --socket="$MYSQL_DIR/bootstrap.sock")
sed -E '/^DROP TABLE IF EXISTS /d; s/\bshuttle_db\b/'"$DB"'/g' "$RELEASE/backend/src/main/resources/sql/init.sql" | "${MYSQL[@]}"
"${MYSQL[@]}" <<SQL
CREATE USER 'shuttle_v13'@'127.0.0.1' IDENTIFIED BY '$DBPASS';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$DB\`.* TO 'shuttle_v13'@'127.0.0.1';
CREATE USER 'shuttle_v13_backup'@'127.0.0.1' IDENTIFIED BY '$BACKPASS';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON \`$DB\`.* TO 'shuttle_v13_backup'@'127.0.0.1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$DBROOT';
SQL
cat > "$CONF/mysql-admin.cnf" <<ENV
[client]
user=root
protocol=socket
socket=/var/lib/shuttle-v13-mysql/mysql.sock
password=$DBROOT
ENV
kill "$BOOT_PID"; wait "$BOOT_PID"; BOOT_PID=
install -m 0644 "$RELEASE"/deploy/v13/units/* /etc/systemd/system/
systemctl daemon-reload
systemctl start shuttle-v13.target
for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 http://127.0.0.1:18080/api/health/ready | grep -q READY; then break; fi
  sleep 1
done
curl -fsS http://127.0.0.1:18080/api/health/ready | grep -q READY
curl -fsS http://127.0.0.1:18000/health >/dev/null
systemctl start shuttle-v13-backup.service
systemctl enable --now shuttle-v13-backup.timer shuttle-v13-monitor.timer

[[ "$(getent ahostsv4 "$DOMAIN" | awk '{print $1}' | sort -u)" == 43.251.117.159 ]]
install -d -m 0755 /var/www/shuttle-v13-acme
sed "s/@DOMAIN@/$DOMAIN/g" "$RELEASE/deploy/v13/nginx-http.conf" > "$SITE"
ln -s "$SITE" "$LINK"
/usr/sbin/nginx -t && systemctl reload nginx
certbot certonly --webroot -w /var/www/shuttle-v13-acme -d "$DOMAIN" --non-interactive --agree-tos --cert-name "$DOMAIN"
certbot renew --cert-name "$DOMAIN" --dry-run
cat > /etc/letsencrypt/renewal-hooks/deploy/shuttle-v13-reload <<'HOOK'
#!/bin/sh
[ "${RENEWED_LINEAGE:-}" = /etc/letsencrypt/live/v13.shuttleflow-gzu.duckdns.org ] || exit 0
/usr/sbin/nginx -t && /usr/bin/systemctl reload nginx
HOOK
chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/shuttle-v13-reload
printf 'v13gate:%s\n' "$(printf '%s\n' "$EDGE" | openssl passwd -6 -stdin)" > "$CONF/htpasswd"
chgrp www-data "$CONF/htpasswd"
chmod 0640 "$CONF/htpasswd"
chmod 0751 "$CONF"
sed "s/@DOMAIN@/$DOMAIN/g" "$RELEASE/deploy/v13/nginx-https.conf" > "$SITE"
/usr/sbin/nginx -t && systemctl reload nginx
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "https://$DOMAIN/")" == 401 ]]
SHUTTLE_V13_ACCESS_PASSWORD="$EDGE" "$ROOT/venv/bin/python" -I "$RELEASE/deploy/v13/public_acceptance.py"
echo "V1.3 安装完成，当前已启动。访问 https://$DOMAIN/login.html?role=admin&next=simulation.html"
