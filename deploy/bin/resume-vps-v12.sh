#!/usr/bin/env bash
# Resume after V1.2 MySQL/bootstrap completed but systemd failed at CHDIR.
# Run in Tabby: sudo bash /home/shuttle/vps-deploy/resume-vps-v12.sh
# This script never initializes, drops, or migrates a database.
set -Eeuo pipefail
umask 0027

readonly SHA="5a97573297a729c66c99033412816acbee52d4e5"
readonly BRANCH="codex/v1.2-hardening-passenger-auth"
readonly REPO_URL="https://github.com/Asheeeng/shuttleflow.git"
readonly ROOT="/opt/shuttleflow"
readonly REPO="$ROOT/repository"
readonly RELEASE="$ROOT/releases/$SHA"
readonly TEST_HELPERS="/home/shuttle/vps-deploy"
readonly TEST_DIR="/home/shuttle/vps-test"
readonly TEST_ENV="/run/shuttleflow-vps-test.env"
readonly APP_ENV="/etc/shuttleflow/shuttleflow.env"
readonly SECRETS="/etc/shuttleflow/secrets.env"
readonly TEST_APP_ENV="/etc/shuttleflow/acceptance.env"
readonly OVERRIDE_DIR="/etc/systemd/system/shuttleflow.service.d"
readonly OVERRIDE="$OVERRIDE_DIR/90-v12-acceptance.conf"

test_override_active=0
test_event_was_absent=0
test_db_user=""

fail() { echo "停止：$*" >&2; exit 1; }
wait_for_health() {
  for _ in $(seq 1 30); do
    if bash "$ROOT/current/deploy/bin/health-check.sh" >/dev/null 2>&1; then
      echo "Java、数据库与AI健康检查通过。"
      return 0
    fi
    sleep 2
  done
  fail "服务重启后未在60秒内就绪；请检查journalctl。"
}
cleanup() {
  if [[ "$test_override_active" == 1 ]]; then
    rm -f "$OVERRIDE" "$TEST_APP_ENV" "$TEST_ENV"
    systemctl daemon-reload || true
    systemctl restart shuttleflow || true
  fi
  if [[ "$test_event_was_absent" == 1 && -r "$SECRETS" ]]; then
    set -a; source "$SECRETS"; set +a
    REDISCLI_AUTH="${SHUTTLE_REDIS_PASSWORD:-}" redis-cli -h 127.0.0.1 DEL shuttle:events >/dev/null 2>&1 || true
  fi
  if [[ -n "$test_db_user" ]] && command -v mysql >/dev/null 2>&1; then
    mysql --protocol=socket --user=root -e "DROP USER IF EXISTS '$test_db_user'@'127.0.0.1'" >/dev/null 2>&1 || true
  fi
  rm -f "$TEST_DIR/mysql-monitor.cnf"
  rm -f "$TEST_ENV"
  unset SHUTTLE_DB_PASSWORD SHUTTLE_REDIS_PASSWORD SHUTTLE_AI_SERVICE_TOKEN \
    TEST_DB_PASSWORD TEST_ADMIN_PASSWORD TEST_DRIVER_PASSWORD TEST_PASSENGER_PASSWORD \
    TEST_DISPATCHER_PASSWORD || true
}
diagnostics() {
  code=$?
  if [[ "$code" != 0 ]]; then
    echo "续跑失败；最近服务日志（不含配置文件内容）：" >&2
    journalctl --no-pager -u shuttleflow -u shuttle-ai -n 80 >&2 || true
  fi
  cleanup
  exit "$code"
}
trap diagnostics EXIT

[[ "$EUID" == 0 && -t 0 ]] || fail "请在Tabby交互运行：sudo bash $0"
[[ -d "$REPO/.git" ]] || fail "找不到已克隆的目标仓库 $REPO"
[[ "$(git -C "$REPO" rev-parse HEAD)" == "$SHA" ]] || fail "服务器源码SHA不是目标提交 $SHA"
remote_sha="$(git ls-remote --exit-code "$REPO_URL" "refs/heads/$BRANCH" | awk 'NR==1 {print $1}')"
[[ "$remote_sha" == "$SHA" ]] || fail "远端目标分支已变化：$remote_sha；停止。"
[[ -d "$RELEASE" && ! -L "$RELEASE" ]] || fail "目标发布目录不存在或不是实体目录：$RELEASE"
[[ -f "$RELEASE/backend/target/shuttle-1.0.0.jar" ]] || fail "目标发布JAR不存在；停止，不重新构建。"
[[ -x "$RELEASE/ai-service/.venv/bin/uvicorn" ]] || fail "AI虚拟环境入口不存在；停止。"
[[ -f "$APP_ENV" && -f "$SECRETS" ]] || fail "应用环境文件未创建；停止，不生成或覆盖凭据。"
[[ -f /etc/systemd/system/shuttleflow.service && -f /etc/systemd/system/shuttle-ai.service ]] \
  || fail "systemd服务单元缺失。"
[[ "$(dpkg-query -W -f='${db:Status-Status}' mysql-server 2>/dev/null)" == installed ]] \
  || fail "MySQL Server未安装。"
mysql --protocol=socket --user=root --batch --skip-column-names -e 'SELECT VERSION()' >/dev/null \
  || fail "MySQL root socket连接失败。"
db_tables="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='shuttle_db'")"
[[ "$db_tables" -gt 0 ]] || fail "shuttle_db尚未初始化；本续跑脚本不会初始化数据库。"
id shuttleflow >/dev/null 2>&1 || fail "shuttleflow系统用户不存在。"
[[ "$(id -gn shuttleflow)" == shuttleflow ]] || fail "shuttleflow主组不符合预期。"

mysql_user_count="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM mysql.user WHERE user='shuttle_app' AND host='127.0.0.1'")"
[[ "$mysql_user_count" == 1 ]] || fail "应用数据库账号不存在；停止，不创建或改写数据库账号。"

set -a; source "$SECRETS"; set +a
redis_auth="$(REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 PING)"
[[ "$redis_auth" == PONG ]] || fail "Redis认证检查失败。"

echo "已验证 MySQL 8.0、初始化表、账号文件、目标SHA和Redis认证；不执行SQL初始化。"
echo "修复发布目录的服务组只读/执行权限（根因：umask曾生成仅root可遍历的目录）。"
systemctl stop shuttleflow shuttle-ai || true
systemctl reset-failed shuttleflow shuttle-ai || true
chown root:shuttleflow "$ROOT" "$ROOT/releases"
chmod 2750 "$ROOT" "$ROOT/releases"
chown -R root:shuttleflow "$RELEASE"
find "$RELEASE" -type d -exec chmod 2750 {} +
find "$RELEASE" -type f -exec chmod g+r {} +
find "$RELEASE" -type f -perm /0100 -exec chmod g+x {} +
chmod 0750 "$RELEASE"/deploy/bin/*.sh

runuser -u shuttleflow -- test -x "$RELEASE/ai-service/.venv/bin/uvicorn" \
  || fail "修复后AI入口仍不可执行。"
runuser -u shuttleflow -- test -r "$RELEASE/backend/target/shuttle-1.0.0.jar" \
  || fail "修复后JAR仍不可读。"
runuser -u shuttleflow -- bash -c "cd '$RELEASE' && test -r backend/target/shuttle-1.0.0.jar" \
  || fail "服务账号仍无法进入发布目录。"

if ! SHUTTLE_DEPLOY_BRANCH="$BRANCH" SHUTTLE_INSTALL_DIR="$ROOT" \
  bash "$REPO/deploy/bin/update.sh"; then
  fail "update.sh仍未通过本机健康检查；已输出服务日志。"
fi
[[ "$(git -C "$ROOT/current" rev-parse HEAD)" == "$SHA" ]] || fail "current SHA不符。"
systemctl enable --now mysql redis-server cron
systemctl enable --now shuttle-ai shuttleflow
wait_for_health

admin_count="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  'SELECT COUNT(*) FROM shuttle_db.admin')"
[[ "$admin_count" == 1 ]] || fail "首次管理员引导未创建唯一管理员。"
sed -i '/^SHUTTLE_BOOTSTRAP_ADMIN_USERNAME=/d;/^SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD=/d' "$APP_ENV"
systemctl restart shuttleflow
wait_for_health

install -m 0644 "$REPO/deploy/cron/shuttleflow-backup" /etc/cron.d/shuttleflow-backup
install -m 0644 "$REPO/deploy/logrotate/shuttleflow-nginx" /etc/logrotate.d/shuttleflow-nginx
install -d -m 0755 /etc/systemd/journald.conf.d /var/backups/shuttleflow
install -m 0644 "$REPO/deploy/logrotate/journald.conf" /etc/systemd/journald.conf.d/shuttleflow.conf
cat > /etc/logrotate.d/shuttleflow-backup <<'LOGROTATE'
/var/log/shuttleflow-backup.log {
    daily
    rotate 14
    compress
    missingok
    notifempty
    create 0600 root root
}
LOGROTATE
chmod 0644 /etc/logrotate.d/shuttleflow-backup
systemctl restart systemd-journald
systemctl enable --now cron
logrotate --debug /etc/logrotate.d/shuttleflow-backup >/dev/null

echo "进行生产库备份与恢复到新库的演练。"
SHUTTLE_DB_NAME=shuttle_db SHUTTLE_BACKUP_DIR=/var/backups/shuttleflow \
  bash "$ROOT/current/deploy/bin/backup-mysql.sh"
backup_file="$(find /var/backups/shuttleflow -maxdepth 1 -type f -name 'shuttle_db-*.sql.gz' -printf '%T@ %p\n' | sort -nr | awk 'NR==1 {print $2}')"
[[ -n "$backup_file" && "$(stat -c '%a' "$backup_file")" == 600 ]] || fail "备份文件或权限检查失败。"
gzip --test "$backup_file"
restore_db="shuttle_restore_v12_$(date -u +%Y%m%d%H%M%S)"
bash "$ROOT/current/deploy/bin/restore-mysql-to-new-db.sh" "$backup_file" "$restore_db"
for table in line station vehicle admin; do
  prod_count="$(mysql --protocol=socket --user=root --batch --skip-column-names -e "SELECT COUNT(*) FROM shuttle_db.$table")"
  copy_count="$(mysql --protocol=socket --user=root --batch --skip-column-names -e "SELECT COUNT(*) FROM ${restore_db}.${table}")"
  [[ "$prod_count" == "$copy_count" ]] || fail "恢复数据不匹配：$table $prod_count/$copy_count"
done

test_db="shuttle_v12_acceptance_$(date -u +%Y%m%d%H%M%S)"
test_db_user="sfv12t_$(date -u +%H%M%S)"
TEST_DB_PASSWORD="$(openssl rand -hex 32)"
TEST_ADMIN_PASSWORD="$(openssl rand -hex 24)"
TEST_DRIVER_PASSWORD="$(openssl rand -hex 24)"
TEST_PASSENGER_PASSWORD="$(openssl rand -hex 24)"
TEST_DISPATCHER_PASSWORD="$(openssl rand -hex 24)"
TEST_DRIVER_PHONE="198$(shuf -i 10000000-99999999 -n 1)"
mysql --protocol=socket --user=root <<SQL
CREATE DATABASE $test_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$test_db_user'@'127.0.0.1' IDENTIFIED BY '$TEST_DB_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON $test_db.* TO '$test_db_user'@'127.0.0.1';
SQL
test_sql="$(mktemp /tmp/shuttle-init-test.XXXXXX.sql)"
sed "s/shuttle_db/$test_db/g" "$RELEASE/backend/src/main/resources/sql/init.sql" > "$test_sql"
mysql --protocol=socket --user=root < "$test_sql"
rm -f "$test_sql"
install -d -o shuttle -g shuttle -m 0700 "$TEST_DIR" "$TEST_DIR/output"
for f in test_auth.py test_api.py test_full.py; do install -o shuttle -g shuttle -m 0644 "$REPO/$f" "$TEST_DIR/$f"; done
install -o shuttle -g shuttle -m 0644 "$TEST_HELPERS/test_load.py" "$TEST_DIR/test_load.py"
install -o shuttle -g shuttle -m 0600 /etc/shuttleflow/monitor.cnf "$TEST_DIR/mysql-monitor.cnf"
cat > "$TEST_ENV" <<EOF
SHUTTLE_BIND_ADDRESS=127.0.0.1
SHUTTLE_SESSION_COOKIE_SECURE=false
SHUTTLE_DB_URL=jdbc:mysql://127.0.0.1:3306/$test_db?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
SHUTTLE_DB_USER=$test_db_user
SHUTTLE_DB_PASSWORD=$TEST_DB_PASSWORD
SHUTTLE_REDIS_HOST=127.0.0.1
SHUTTLE_REDIS_PORT=6379
SHUTTLE_AI_SERVICE_URL=http://127.0.0.1:8000
SHUTTLE_AI_SERVICE_TOKEN=$SHUTTLE_AI_SERVICE_TOKEN
SHUTTLE_BACKEND_API_URL=http://127.0.0.1:8080/api
AI_BIND_HOST=127.0.0.1
AI_PORT=8000
SHUTTLE_CORS_ALLOWED_ORIGINS=http://127.0.0.1:8080,http://localhost:8080
SHUTTLE_RATE_LIMIT_AUTH=5000
SHUTTLE_RATE_LIMIT_AUTH_WINDOW_SECONDS=60
SHUTTLE_RATE_LIMIT_PASSENGER=5000
SHUTTLE_RATE_LIMIT_WRITE_WINDOW_SECONDS=60
SHUTTLE_REDIS_EVENT_STREAM_MAX_LENGTH=10000
SHUTTLE_BOOTSTRAP_ADMIN_USERNAME=v12accept
SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD=$TEST_ADMIN_PASSWORD
SHUTTLE_TEST_API=http://127.0.0.1:8080/api
SHUTTLE_TEST_DB_NAME=$test_db
SHUTTLE_TEST_ADMIN_USERNAME=v12accept
SHUTTLE_TEST_ADMIN_PASSWORD=$TEST_ADMIN_PASSWORD
SHUTTLE_TEST_DRIVER_PHONE=$TEST_DRIVER_PHONE
SHUTTLE_TEST_DRIVER_PASSWORD=$TEST_DRIVER_PASSWORD
SHUTTLE_TEST_DRIVER_NAME=VPS验收司机
SHUTTLE_TEST_PASSENGER_PASSWORD=$TEST_PASSENGER_PASSWORD
SHUTTLE_TEST_DISPATCHER_PASSWORD=$TEST_DISPATCHER_PASSWORD
SHUTTLE_TEST_MYSQL_CNF=$TEST_DIR/mysql-monitor.cnf
SHUTTLE_TEST_MYSQL_PORT=3306
SHUTTLE_LOAD_LEVELS=10,30
SHUTTLE_REDIS_PASSWORD=$SHUTTLE_REDIS_PASSWORD
EOF
chmod 0600 "$TEST_ENV"; chown shuttle:shuttle "$TEST_ENV"
cat > "$TEST_APP_ENV" <<EOF
SHUTTLE_DB_URL=jdbc:mysql://127.0.0.1:3306/$test_db?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
SHUTTLE_DB_USER=$test_db_user
SHUTTLE_DB_PASSWORD=$TEST_DB_PASSWORD
SHUTTLE_SESSION_COOKIE_SECURE=false
SHUTTLE_RATE_LIMIT_AUTH=5000
SHUTTLE_RATE_LIMIT_AUTH_WINDOW_SECONDS=60
SHUTTLE_RATE_LIMIT_PASSENGER=5000
SHUTTLE_RATE_LIMIT_WRITE_WINDOW_SECONDS=60
SHUTTLE_BOOTSTRAP_ADMIN_USERNAME=v12accept
SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD=$TEST_ADMIN_PASSWORD
EOF
chmod 0600 "$TEST_APP_ENV"; chown root:root "$TEST_APP_ENV"
install -d -o root -g root -m 0755 "$OVERRIDE_DIR"
cat > "$OVERRIDE" <<EOF
[Service]
EnvironmentFile=
EnvironmentFile=$APP_ENV
EnvironmentFile=$SECRETS
EnvironmentFile=$TEST_APP_ENV
EOF
chmod 0644 "$OVERRIDE"
test_override_active=1
redis_events_before="$(REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 EXISTS shuttle:events)"
if [[ "$redis_events_before" == 0 ]]; then test_event_was_absent=1; fi
systemctl daemon-reload
systemctl restart shuttleflow
wait_for_health
runuser -u shuttle -- /bin/bash -c \
  'set -Eeuo pipefail; set -a; source /run/shuttleflow-vps-test.env; set +a; cd /home/shuttle/vps-test; python3 test_auth.py; python3 test_api.py; python3 test_full.py; python3 test_load.py'
if [[ "$test_event_was_absent" == 1 ]]; then
  REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 DEL shuttle:events >/dev/null
  test_event_was_absent=0
fi
mysql --protocol=socket --user=root -e "DROP USER '$test_db_user'@'127.0.0.1'"
rm -f "$TEST_DIR/mysql-monitor.cnf" "$OVERRIDE" "$TEST_APP_ENV" "$TEST_ENV"
systemctl daemon-reload
systemctl restart shuttleflow
test_override_active=0
wait_for_health

systemctl restart mysql redis-server shuttle-ai shuttleflow
wait_for_health
nginx -t
systemctl --no-pager --plain is-active mysql redis-server nginx shuttle-ai shuttleflow
ss -lnt
free -h
df -h /
mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT VERSION(); SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Slow_queries','Max_used_connections');"
set -a; source "$SECRETS"; set +a
REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 INFO memory | grep -E '^(used_memory_human|maxmemory_human):'
REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 XLEN shuttle:events
echo "部署SHA：$SHA；应用继续只监听回环地址，公网Nginx仍未代理业务。"
echo "验收数据库保留供审查：$test_db；正式库未参与测试。"
