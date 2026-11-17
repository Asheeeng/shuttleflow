#!/usr/bin/env bash
# ShuttleFlow V1.2 VPS first deployment. Run manually from Tabby as:
#   sudo bash /home/shuttle/vps-deploy/bootstrap-vps-v12.sh
# No SSH, firewall, Nginx public-site, or existing-database changes are made.
# MySQL root is Unix-socket-only (no reusable root password); if the installer
# unexpectedly asks for one, leave it blank as documented by Oracle MySQL.
set -Eeuo pipefail
umask 077

readonly EXPECTED_SHA="5a97573297a729c66c99033412816acbee52d4e5"
readonly DEPLOY_BRANCH="codex/v1.2-hardening-passenger-auth"
readonly REPO_URL="https://github.com/Asheeeng/shuttleflow.git"
readonly MYSQL_APT_DEB="mysql-apt-config_0.8.40-1_all.deb"
readonly MYSQL_APT_SHA256="41a796dd6ea681aef4675d3b0b27924873d4f9777e524facf7693eabdf232e93"
readonly MYSQL_APT_MD5="981ff0a16aab27a0cd97f4c4ee49e9fd"
readonly SOURCE_CHECKOUT="/home/shuttle/shuttleflow"
readonly TEST_HELPERS="/home/shuttle/vps-deploy"
readonly TEST_WORKDIR="/home/shuttle/vps-test"
readonly TEST_RUN_ENV="/run/shuttleflow-vps-test.env"
readonly TEST_OVERRIDE_DIR="/etc/systemd/system/shuttleflow.service.d"
readonly TEST_OVERRIDE="$TEST_OVERRIDE_DIR/90-v12-acceptance.conf"
readonly TEST_APP_ENV="/etc/shuttleflow/acceptance.env"

test_override_active=0
test_event_was_absent=0
test_db_user=""
bootstrap_password=""
bootstrap_password_confirm=""

cleanup() {
  if [[ "$test_override_active" == "1" ]]; then
    rm -f "$TEST_OVERRIDE" "$TEST_APP_ENV" "$TEST_RUN_ENV"
    systemctl daemon-reload || true
    systemctl restart shuttleflow || true
  fi
  if [[ "$test_event_was_absent" == "1" && -r /etc/shuttleflow/secrets.env ]]; then
    set -a; source /etc/shuttleflow/secrets.env; set +a
    REDISCLI_AUTH="${SHUTTLE_REDIS_PASSWORD:-}" redis-cli -h 127.0.0.1 DEL shuttle:events >/dev/null 2>&1 || true
  fi
  if [[ -n "$test_db_user" ]] && command -v mysql >/dev/null 2>&1; then
    mysql --protocol=socket --user=root -e "DROP USER IF EXISTS '$test_db_user'@'127.0.0.1'" >/dev/null 2>&1 || true
  fi
  rm -f "$TEST_RUN_ENV"
  unset bootstrap_password bootstrap_password_confirm bootstrap_username SHUTTLE_DB_PASSWORD \
    SHUTTLE_REDIS_PASSWORD SHUTTLE_AI_SERVICE_TOKEN SHUTTLE_BACKUP_DB_PASSWORD \
    SHUTTLE_MONITOR_DB_PASSWORD TEST_DB_PASSWORD TEST_DRIVER_PASSWORD \
    TEST_ADMIN_PASSWORD TEST_PASSENGER_PASSWORD TEST_DISPATCHER_PASSWORD || true
}
trap cleanup EXIT

fail() { echo "停止：$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "缺少命令 $1"; }
wait_for_health() {
  for _ in $(seq 1 30); do
    if bash /opt/shuttleflow/current/deploy/bin/health-check.sh >/dev/null 2>&1; then
      echo "Java、数据库与AI健康检查通过。"
      return 0
    fi
    sleep 2
  done
  fail "服务重启后未在60秒内就绪；请检查journalctl。"
}

[[ "$EUID" == "0" ]] || fail "请在 Tabby 中交互执行：sudo bash $0"
[[ -t 0 ]] || fail "需要交互式终端；请在 Tabby 中运行本脚本，不要重定向或后台运行。"
[[ "$(id -un)" == "root" ]] || fail "当前没有 root 权限。"

for cmd in apt-get curl git redis-cli systemctl ss ufw dpkg dpkg-query; do
  need "$cmd"
done

if [[ ! -d "$SOURCE_CHECKOUT/.git" ]]; then fail "找不到目标分支工作副本：$SOURCE_CHECKOUT"; fi
local_sha="$(git -C "$SOURCE_CHECKOUT" rev-parse HEAD)"
[[ "$local_sha" == "$EXPECTED_SHA" ]] || fail "本地源提交不是目标 SHA：$local_sha"
remote_sha="$(git ls-remote --exit-code "$REPO_URL" "refs/heads/$DEPLOY_BRANCH" | awk 'NR==1 {print $1}')"
[[ "$remote_sha" == "$EXPECTED_SHA" ]] || fail "GitHub 目标分支已变化：$remote_sha；停止部署。"

. /etc/os-release
[[ "${ID:-}" == "debian" && "${VERSION_ID:-}" == "12" ]] || fail "仅允许 Debian 12。"
[[ "$(dpkg --print-architecture)" == "amd64" ]] || fail "目标架构不是 x86_64/amd64。"

ssh_effective="$(/usr/sbin/sshd -T -C user=shuttle,host=localhost,addr=127.0.0.1)"
grep -qx 'port 22' <<<"$ssh_effective" || fail "SSH 有效端口不是22；不改SSH配置。"
grep -qx 'passwordauthentication no' <<<"$ssh_effective" || fail "SSH密码认证未关闭；不改SSH配置。"
grep -qx 'permitrootlogin no' <<<"$ssh_effective" || fail "root SSH登录未关闭；不改SSH配置。"
grep -qx 'pubkeyauthentication yes' <<<"$ssh_effective" || fail "SSH公钥认证未启用；不改SSH配置。"

ufw_state="$(ufw status verbose)"
grep -q '^Status: active' <<<"$ufw_state" || fail "UFW未处于active；本脚本不会配置防火墙。"
for port in 22 80 443; do
  grep -Eq "^${port}/tcp[[:space:]]+ALLOW" <<<"$ufw_state" || fail "UFW未显示允许 ${port}/tcp；本脚本不会更改防火墙。"
done

mysql_installed=0
if dpkg-query -W -f='${db:Status-Status}' mysql-server 2>/dev/null | grep -q installed; then
  mysql_installed=1
  mysql_version="$(mysql --version 2>/dev/null || true)"
  grep -q 'Ver 8\.0\.' <<<"$mysql_version" || fail "检测到非MySQL 8.0或版本无法核实：$mysql_version"
  [[ -f /etc/mysql/mysql.conf.d/z-shuttleflow.cnf ]] || fail "MySQL已安装但ShuttleFlow本机安全配置不存在；停止续跑。"
  cmp -s "$SOURCE_CHECKOUT/deploy/mysql/shuttleflow.cnf" /etc/mysql/mysql.conf.d/z-shuttleflow.cnf \
    || fail "MySQL配置与本脚本预期不一致；停止，不覆盖。"
  systemctl is-active --quiet mysql || fail "MySQL已安装但服务未运行；停止续跑。"
  mysql --protocol=socket --user=root --batch --skip-column-names -e 'SELECT VERSION()' >/dev/null \
    || fail "MySQL root Unix socket认证不可用；不尝试弱密码或认证绕过。"
  db_exists="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='shuttle_db'")"
  if [[ "$db_exists" == "1" ]]; then
    db_tables="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='shuttle_db'")"
    [[ "$db_tables" == "0" ]] || fail "shuttle_db已包含业务表；停止，不修改已有数据。"
  fi
  echo "检测到前次运行已安装并加固MySQL 8.0；验证通过，将从该阶段续跑。"
elif [[ -d /var/lib/mysql ]] && find /var/lib/mysql -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  fail "/var/lib/mysql已含数据但MySQL Server未安装；停止，需先人工检查和备份。"
fi
if ss -lnt | grep -Eq '(^|[[:space:]])(0\.0\.0\.0|\[::\]):(3306|6379|8000|8080)([[:space:]]|$)'; then
  fail "检测到内部服务监听公网地址；停止，不改网络配置。"
fi
if [[ -e /opt/shuttleflow ]]; then
  [[ -d /opt/shuttleflow/releases && ! -e /opt/shuttleflow/repository && ! -e /opt/shuttleflow/current ]] \
    || fail "检测到非空或不符合预期的 /opt/shuttleflow；停止，不覆盖。"
  find /opt/shuttleflow/releases -mindepth 1 -maxdepth 1 -print -quit | grep -q . \
    && fail "发布目录已有内容；停止，不覆盖。" || true
  [[ "$(stat -c '%U:%G:%a' /opt/shuttleflow)" == "root:shuttleflow:750" ]] \
    || fail "/opt/shuttleflow目录所有权/权限与前次初始化不符。"
  echo "检测到前次运行创建的空发布目录；验证通过，将续跑。"
fi
if [[ -e /etc/shuttleflow ]]; then
  [[ -d /etc/shuttleflow ]] || fail "/etc/shuttleflow不是目录。"
  [[ ! -e /etc/shuttleflow/secrets.env && ! -e /etc/shuttleflow/shuttleflow.env ]] \
    || fail "检测到已有密钥/应用环境文件；停止，不覆盖。"
  find /etc/shuttleflow -mindepth 1 -maxdepth 1 -print -quit | grep -q . \
    && fail "/etc/shuttleflow已有文件；停止，不覆盖。" || true
  [[ "$(stat -c '%U:%G:%a' /etc/shuttleflow)" == "root:root:700" ]] \
    || fail "/etc/shuttleflow目录所有权/权限与前次初始化不符。"
fi
if getent passwd shuttleflow >/dev/null; then
  account_record="$(getent passwd shuttleflow)"
  [[ "$(cut -d: -f6 <<<"$account_record")" == "/var/lib/shuttleflow" && \
     "$(cut -d: -f7 <<<"$account_record")" == "/usr/sbin/nologin" && \
     "$(id -gn shuttleflow)" == "shuttleflow" ]] || fail "已有 shuttleflow 账号与预期不符。"
  echo "检测到前次运行创建的服务账号；验证通过，将续跑。"
elif getent group shuttleflow >/dev/null; then
  fail "已有同名shuttleflow组但账号不存在；停止并人工核对。"
fi
if [[ -e /var/lib/shuttleflow ]]; then
  [[ "$(stat -c '%U:%G:%a' /var/lib/shuttleflow)" == "shuttleflow:shuttleflow:750" ]] \
    || fail "/var/lib/shuttleflow目录所有权/权限与前次初始化不符。"
fi

redis_before="$(redis-cli -h 127.0.0.1 DBSIZE 2>/dev/null || true)"
[[ "$redis_before" == "0" ]] || fail "现有Redis不是空库（DBSIZE=$redis_before）；停止，不重启或清理Redis。"
[[ ! -e /etc/redis/shuttleflow.conf ]] || fail "Redis已有 /etc/redis/shuttleflow.conf；停止，不覆盖。"

if [[ "$mysql_installed" == "1" ]]; then
  echo "只读预检通过：Debian 12/amd64；SSH与UFW保持现状；MySQL 8.0已安装且shuttle_db不存在/为空；Redis当前DBSIZE=0。"
else
  echo "只读预检通过：Debian 12/amd64；SSH与UFW保持现状；MySQL Server未安装且数据目录为空；Redis当前DBSIZE=0。"
fi
echo "MySQL 8.0将来自Oracle官方APT仓库；init.sql含DROP TABLE，但只会在全新、无表的 shuttle_db 执行。"
read -r -p '若确认创建全新空库并初始化，请输入 INIT shuttle_db：' init_confirmation
[[ "$init_confirmation" == "INIT shuttle_db" ]] || fail "未确认空库初始化；没有执行数据库初始化。"
unset init_confirmation

echo "检查系统依赖；只安装缺失包，不执行 full-upgrade。"
required_packages=(ca-certificates curl git openssl gnupg debconf-utils \
  openjdk-17-jre-headless maven python3 python3-venv python3-pip python3-dev \
  build-essential nginx certbot python3-certbot-nginx redis-server ufw logrotate cron)
missing_packages=()
for package in "${required_packages[@]}"; do
  package_state="$(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true)"
  [[ "$package_state" == "installed" ]] || missing_packages+=("$package")
done
if ((${#missing_packages[@]})); then
  echo "安装缺失包：${missing_packages[*]}"
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing_packages[@]}"
else
  echo "依赖包已齐全，跳过APT安装。"
fi
need openssl
need debconf-set-selections

if [[ "$mysql_installed" == "0" ]]; then
  mysql_apt_installed=0
  if dpkg-query -W -f='${db:Status-Status}' mysql-apt-config 2>/dev/null | grep -q installed; then
    mysql_apt_installed=1
  fi
  if [[ "$mysql_apt_installed" == "0" ]]; then
    mysql_apt_tmp="$(mktemp "/tmp/${MYSQL_APT_DEB}.XXXXXX")"
    trap 'rm -f "$mysql_apt_tmp"; cleanup' EXIT
    curl --fail --silent --show-error --location \
      "https://dev.mysql.com/get/${MYSQL_APT_DEB}" -o "$mysql_apt_tmp"
    echo "$MYSQL_APT_SHA256  $mysql_apt_tmp" | sha256sum --check --status || fail "MySQL APT配置包SHA-256不匹配。"
    echo "$MYSQL_APT_MD5  $mysql_apt_tmp" | md5sum --check --status || fail "MySQL APT配置包与Oracle下载页校验值不匹配。"

    cat <<'DEBCONF' | debconf-set-selections
mysql-apt-config mysql-apt-config/select-server select mysql-8.0
mysql-apt-config mysql-apt-config/select-connectors select Disabled
mysql-apt-config mysql-apt-config/select-product select Ok
mysql-apt-config mysql-apt-config/unsupported-platform select debian bookworm
mysql-apt-config mysql-apt-config/repo-distro select debian
mysql-apt-config mysql-apt-config/repo-codename select bookworm
DEBCONF
{
  printf '%s%s\n' 'mysql-community-server mysql-community-server/root-pass password' ' '
  printf '%s%s\n' 'mysql-community-server mysql-community-server/re-root-pass password' ' '
} | debconf-set-selections
    DEBIAN_FRONTEND=noninteractive dpkg -i "$mysql_apt_tmp"
    rm -f "$mysql_apt_tmp"
    trap cleanup EXIT
  fi

  apt-get update
  apt-cache policy mysql-server
  candidate="$(apt-cache policy mysql-server | awk '/Candidate:/ {print $2}')"
  [[ "$candidate" == 8.0.* ]] || fail "APT候选版本不是MySQL 8.0：$candidate；不安装MariaDB或其他版本。"
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends mysql-server mysql-client
  need mysql
  need mysqldump
  mysql_version="$(mysql --version)"
  grep -q 'Ver 8\.0\.' <<<"$mysql_version" || fail "安装后版本校验失败：$mysql_version"
fi

if [[ -e /etc/mysql/mysql.conf.d/z-shuttleflow.cnf ]]; then
  cmp -s "$SOURCE_CHECKOUT/deploy/mysql/shuttleflow.cnf" /etc/mysql/mysql.conf.d/z-shuttleflow.cnf \
    || fail "MySQL配置文件已存在但内容不同；拒绝覆盖。"
else
  install -o root -g root -m 0644 "$SOURCE_CHECKOUT/deploy/mysql/shuttleflow.cnf" \
    /etc/mysql/mysql.conf.d/z-shuttleflow.cnf
fi
systemctl enable --now mysql
if [[ "$mysql_installed" == "0" ]]; then systemctl restart mysql; fi
mysql --protocol=socket --user=root --batch --skip-column-names -e 'SELECT VERSION()' >/dev/null \
  || fail "MySQL root Unix socket认证不可用；停止，不尝试弱密码或认证绕过。"
mysql --protocol=socket --user=root --batch --skip-column-names -e \
  'SHOW VARIABLES WHERE Variable_name IN ("bind_address","require_secure_transport")'
need mysql
need mysqldump

database_exists="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='shuttle_db'")"
if [[ "$database_exists" == "1" ]]; then
  database_tables="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='shuttle_db'")"
  [[ "$database_tables" == "0" ]] || fail "shuttle_db已存在业务表；停止，不初始化、不迁移。"
fi

if ! getent passwd shuttleflow >/dev/null; then
  useradd --system --home /var/lib/shuttleflow --create-home --shell /usr/sbin/nologin shuttleflow
fi
install -d -o root -g shuttleflow -m 0750 /opt/shuttleflow /opt/shuttleflow/releases
install -d -o shuttleflow -g shuttleflow -m 0750 /var/lib/shuttleflow
install -d -o root -g root -m 0700 /etc/shuttleflow /var/backups/shuttleflow

SHUTTLE_DB_NAME=shuttle_db bash "$SOURCE_CHECKOUT/deploy/bin/create-db-user.sh"
new_tables="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='shuttle_db'")"
[[ "$new_tables" == "0" ]] || fail "新建shuttle_db出现非空表集；拒绝运行含DROP TABLE的init.sql。"
mysql --protocol=socket --user=root < "$SOURCE_CHECKOUT/backend/src/main/resources/sql/init.sql"
[[ "$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='shuttle_db'")" -gt 0 ]] \
  || fail "初始化后shuttle_db没有表。"

echo "请设置正式管理员账号。密码不会回显，且只会短暂写入root-only引导环境文件。"
read -r -p '管理员用户名（3至30字符，字母/数字/._-）：' bootstrap_username
[[ "$bootstrap_username" =~ ^[A-Za-z0-9._-]{3,30}$ ]] || fail "管理员用户名格式不符合要求。"
while :; do
  read -r -s -p '管理员密码（至少20位，仅字母/数字/._-）：' bootstrap_password; printf '\n'
  read -r -s -p '再次输入管理员密码：' bootstrap_password_confirm; printf '\n'
  [[ "$bootstrap_password" == "$bootstrap_password_confirm" ]] || { echo "两次输入不一致，请重试。"; continue; }
  [[ "$bootstrap_password" =~ ^[A-Za-z0-9._-]{20,128}$ ]] || { echo "密码格式或长度不符合要求，请重试。"; continue; }
  break
done

cat > /etc/shuttleflow/shuttleflow.env <<EOF
SHUTTLE_BIND_ADDRESS=127.0.0.1
SHUTTLE_SESSION_COOKIE_SECURE=true
SHUTTLE_DB_URL=jdbc:mysql://127.0.0.1:3306/shuttle_db?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
SHUTTLE_DB_POOL_SIZE=12
SHUTTLE_DB_MIN_IDLE=2
SHUTTLE_TOMCAT_MAX_THREADS=64
SHUTTLE_TOMCAT_MAX_CONNECTIONS=128
SHUTTLE_REDIS_HOST=127.0.0.1
SHUTTLE_REDIS_PORT=6379
SHUTTLE_AI_SERVICE_URL=http://127.0.0.1:8000
SHUTTLE_BACKEND_API_URL=http://127.0.0.1:8080/api
AI_BIND_HOST=127.0.0.1
AI_PORT=8000
SHUTTLE_CORS_ALLOWED_ORIGINS=http://127.0.0.1:8080,http://localhost:8080
SHUTTLE_RATE_LIMIT_AUTH=10
SHUTTLE_RATE_LIMIT_AUTH_WINDOW_SECONDS=900
SHUTTLE_RATE_LIMIT_PASSENGER=20
SHUTTLE_RATE_LIMIT_WRITE_WINDOW_SECONDS=60
SHUTTLE_REDIS_EVENT_STREAM_MAX_LENGTH=10000
SHUTTLE_BOOTSTRAP_ADMIN_USERNAME=$bootstrap_username
SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD=$bootstrap_password
EOF
chmod 0600 /etc/shuttleflow/shuttleflow.env
chown root:root /etc/shuttleflow/shuttleflow.env
unset bootstrap_password bootstrap_password_confirm

"$(command -v bash)" "$SOURCE_CHECKOUT/deploy/bin/configure-redis.sh"
redis_authenticated="$(REDISCLI_AUTH="$(awk -F= '/^SHUTTLE_REDIS_PASSWORD=/{print $2}' /etc/shuttleflow/secrets.env)" \
  redis-cli -h 127.0.0.1 PING)"
[[ "$redis_authenticated" == "PONG" ]] || fail "Redis认证健康检查失败。"
if redis-cli -h 127.0.0.1 PING 2>&1 | grep -q '^PONG$'; then
  fail "Redis无需认证仍可访问；拒绝继续。"
fi

cp "$SOURCE_CHECKOUT/deploy/systemd/shuttleflow.service" /etc/systemd/system/shuttleflow.service
cp "$SOURCE_CHECKOUT/deploy/systemd/shuttle-ai.service" /etc/systemd/system/shuttle-ai.service
chmod 0644 /etc/systemd/system/shuttleflow.service /etc/systemd/system/shuttle-ai.service
systemctl daemon-reload

chown root:shuttleflow /opt/shuttleflow /opt/shuttleflow/releases
chmod 2750 /opt/shuttleflow /opt/shuttleflow/releases
# Release contents must be readable/traversable by the dedicated systemd user.
# Keep secrets under /etc/shuttleflow at 0600; only the source release is shared.
umask 0027
git clone --no-tags --depth 1 --single-branch --branch "$DEPLOY_BRANCH" "$REPO_URL" /opt/shuttleflow/repository
cloned_sha="$(git -C /opt/shuttleflow/repository rev-parse HEAD)"
[[ "$cloned_sha" == "$EXPECTED_SHA" ]] || fail "服务器源码SHA不匹配：$cloned_sha"
release_dir="/opt/shuttleflow/releases/$EXPECTED_SHA"
git -C /opt/shuttleflow/repository worktree add --detach "$release_dir" "$EXPECTED_SHA"
# The umask preserves group-read/traverse permissions for the service group as
# Maven and the Python venv create files. Normalize already checked-out source.
chown -R root:shuttleflow "$release_dir"
find "$release_dir" -type d -exec chmod 2750 {} +
find "$release_dir" -type f -exec chmod g+r {} +
find "$release_dir" -type f -perm /0100 -exec chmod g+x {} +
# V1.2.0 tracked shell helpers without executable bits; set them only in this
# isolated worktree so health checks and cron can invoke them directly.
chmod 0750 "$release_dir"/deploy/bin/*.sh
SHUTTLE_DEPLOY_BRANCH="$DEPLOY_BRANCH" SHUTTLE_INSTALL_DIR=/opt/shuttleflow \
  bash /opt/shuttleflow/repository/deploy/bin/update.sh
deployed_sha="$(git -C /opt/shuttleflow/current rev-parse HEAD)"
[[ "$deployed_sha" == "$EXPECTED_SHA" ]] || fail "实际发布SHA不匹配：$deployed_sha"

systemctl enable --now mysql redis-server cron
systemctl enable shuttleflow shuttle-ai
if ! systemctl is-active --quiet shuttleflow || ! systemctl is-active --quiet shuttle-ai; then
  fail "应用/AI服务未active。"
fi
wait_for_health

admin_count="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  'SELECT COUNT(*) FROM shuttle_db.admin')"
[[ "$admin_count" == "1" ]] || fail "首次管理员引导结果异常。"
sed -i '/^SHUTTLE_BOOTSTRAP_ADMIN_USERNAME=/d;/^SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD=/d' \
  /etc/shuttleflow/shuttleflow.env
unset bootstrap_username
systemctl restart shuttleflow
wait_for_health

echo "安装V1.2备份任务、日志轮转和journald限制。"
install -m 0644 "$SOURCE_CHECKOUT/deploy/cron/shuttleflow-backup" /etc/cron.d/shuttleflow-backup
install -m 0644 "$SOURCE_CHECKOUT/deploy/logrotate/shuttleflow-nginx" /etc/logrotate.d/shuttleflow-nginx
install -d -m 0755 /etc/systemd/journald.conf.d
install -m 0644 "$SOURCE_CHECKOUT/deploy/logrotate/journald.conf" \
  /etc/systemd/journald.conf.d/shuttleflow.conf
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

echo "执行真实MySQL备份与恢复到独立新库。"
SHUTTLE_DB_NAME=shuttle_db SHUTTLE_BACKUP_DIR=/var/backups/shuttleflow \
  bash /opt/shuttleflow/current/deploy/bin/backup-mysql.sh
backup_file="$(find /var/backups/shuttleflow -maxdepth 1 -type f -name 'shuttle_db-*.sql.gz' -printf '%T@ %p\n' | sort -nr | awk 'NR==1 {print $2}')"
[[ -n "$backup_file" && "$(stat -c '%a' "$backup_file")" == "600" ]] || fail "备份文件缺失或权限不是0600。"
gzip --test "$backup_file"
restore_db="shuttle_restore_v12_$(date -u +%Y%m%d%H%M%S)"
SHUTTLE_BACKUP_DIR=/var/backups/shuttleflow \
  bash /opt/shuttleflow/current/deploy/bin/restore-mysql-to-new-db.sh "$backup_file" "$restore_db"
for table in line station vehicle admin; do
  source_count="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM shuttle_db.$table")"
  restored_count="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM `$restore_db`.$table")"
  [[ "$source_count" == "$restored_count" ]] || fail "恢复核对不一致：$table $source_count/$restored_count"
done

echo "准备独立测试数据库；功能/并发测试不会写入正式演示库 shuttle_db。"
test_db="shuttle_v12_acceptance_$(date -u +%Y%m%d%H%M%S)"
test_db_user="sfv12t_$(date -u +%H%M%S)"
TEST_DB_PASSWORD="$(openssl rand -hex 32)"
TEST_ADMIN_PASSWORD="$(openssl rand -hex 24)"
TEST_DRIVER_PASSWORD="$(openssl rand -hex 24)"
TEST_PASSENGER_PASSWORD="$(openssl rand -hex 24)"
TEST_DISPATCHER_PASSWORD="$(openssl rand -hex 24)"
TEST_DRIVER_PHONE="198$(shuf -i 10000000-99999999 -n 1)"
mysql --protocol=socket --user=root <<SQL
CREATE DATABASE `$test_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$test_db_user'@'127.0.0.1' IDENTIFIED BY '$TEST_DB_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON `$test_db`.* TO '$test_db_user'@'127.0.0.1';
SQL
test_sql="$(mktemp /tmp/shuttle-init-test.XXXXXX.sql)"
sed "s/shuttle_db/$test_db/g" \
  /opt/shuttleflow/current/backend/src/main/resources/sql/init.sql > "$test_sql"
mysql --protocol=socket --user=root < "$test_sql"
rm -f "$test_sql"

install -d -o shuttle -g shuttle -m 0700 "$TEST_WORKDIR" "$TEST_WORKDIR/output"
for test_file in test_auth.py test_api.py test_full.py; do
  install -o shuttle -g shuttle -m 0644 "$SOURCE_CHECKOUT/$test_file" "$TEST_WORKDIR/$test_file"
done
install -o shuttle -g shuttle -m 0644 "$TEST_HELPERS/test_load.py" "$TEST_WORKDIR/test_load.py"
install -o shuttle -g shuttle -m 0600 /etc/shuttleflow/monitor.cnf "$TEST_WORKDIR/mysql-monitor.cnf"
cat > "$TEST_RUN_ENV" <<EOF
SHUTTLE_BIND_ADDRESS=127.0.0.1
SHUTTLE_SESSION_COOKIE_SECURE=false
SHUTTLE_DB_URL=jdbc:mysql://127.0.0.1:3306/$test_db?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
SHUTTLE_DB_USER=$test_db_user
SHUTTLE_DB_PASSWORD=$TEST_DB_PASSWORD
SHUTTLE_REDIS_HOST=127.0.0.1
SHUTTLE_REDIS_PORT=6379
SHUTTLE_AI_SERVICE_URL=http://127.0.0.1:8000
SHUTTLE_AI_SERVICE_TOKEN=$(awk -F= '/^SHUTTLE_AI_SERVICE_TOKEN=/{print $2}' /etc/shuttleflow/secrets.env)
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
SHUTTLE_TEST_MYSQL_CNF=$TEST_WORKDIR/mysql-monitor.cnf
SHUTTLE_TEST_MYSQL_PORT=3306
SHUTTLE_LOAD_LEVELS=10,30
SHUTTLE_REDIS_PASSWORD=$(awk -F= '/^SHUTTLE_REDIS_PASSWORD=/{print $2}' /etc/shuttleflow/secrets.env)
EOF
chmod 0600 "$TEST_RUN_ENV"
chown shuttle:shuttle "$TEST_RUN_ENV"
test_event_key_existed="$(REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 EXISTS shuttle:events)"
if [[ "$test_event_key_existed" == "0" ]]; then
  test_event_was_absent=1
else
  echo "Redis已有事件流；验收后保留该流，不做删除。"
fi

cat "$TEST_RUN_ENV" >> "$TEST_APP_ENV"
chmod 0600 "$TEST_APP_ENV"
chown root:root "$TEST_APP_ENV"
install -d -o root -g root -m 0755 "$TEST_OVERRIDE_DIR"
[[ ! -e "$TEST_OVERRIDE" ]] || fail "检测到已有验收override，停止避免覆盖。"
cat > "$TEST_OVERRIDE" <<EOF
[Service]
EnvironmentFile=
EnvironmentFile=/etc/shuttleflow/shuttleflow.env
EnvironmentFile=/etc/shuttleflow/secrets.env
EnvironmentFile=$TEST_APP_ENV
EOF
chmod 0644 "$TEST_OVERRIDE"
test_override_active=1
systemctl daemon-reload
systemctl restart shuttleflow
wait_for_health

echo "运行隔离库HTTP回归和VPS 10/30并发压测。正式数据库不会参与测试。"
runuser -u shuttle -- /bin/bash -c \
  'set -Eeuo pipefail; set -a; source /run/shuttleflow-vps-test.env; set +a; cd /home/shuttle/vps-test; python3 test_auth.py; python3 test_api.py; python3 test_full.py; python3 test_load.py'

if [[ "$test_event_was_absent" == "1" ]]; then
  REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 DEL shuttle:events >/dev/null
  test_event_was_absent=0
fi
mysql --protocol=socket --user=root -e "DROP USER '$test_db_user'@'127.0.0.1'"
rm -f "$TEST_WORKDIR/mysql-monitor.cnf"

echo "恢复正式演示库配置并重启服务。"
rm -f "$TEST_OVERRIDE" "$TEST_APP_ENV" "$TEST_RUN_ENV"
systemctl daemon-reload
systemctl restart shuttleflow
test_override_active=0
trap cleanup EXIT
unset TEST_DB_PASSWORD TEST_ADMIN_PASSWORD TEST_DRIVER_PASSWORD TEST_PASSENGER_PASSWORD \
  TEST_DISPATCHER_PASSWORD SHUTTLE_DB_PASSWORD SHUTTLE_REDIS_PASSWORD SHUTTLE_AI_SERVICE_TOKEN

wait_for_health
systemctl restart mysql
systemctl restart redis-server
systemctl restart shuttle-ai shuttleflow
wait_for_health
nginx -t

echo "=== 最终服务状态 ==="
systemctl --no-pager --plain is-active mysql redis-server nginx shuttle-ai shuttleflow
echo "=== 监听端口（只应有内部服务回环监听） ==="
ss -lnt
if ss -lnt | grep -Eq '(^|[[:space:]])(0\.0\.0\.0|\[::\]):(3306|6379|8000|8080)([[:space:]]|$)'; then
  fail "MySQL、Redis或应用出现公网监听。"
fi
echo "=== 资源快照 ==="
free -h
df -h /
echo "=== MySQL版本与关键状态 ==="
mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT VERSION(); SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Slow_queries','Max_used_connections');"
echo "=== Redis认证状态与资源 ==="
set -a; source /etc/shuttleflow/secrets.env; set +a
REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 INFO memory | \
  grep -E '^(used_memory_human|maxmemory_human):'
REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 INFO clients | grep '^connected_clients:'
REDISCLI_AUTH="$SHUTTLE_REDIS_PASSWORD" redis-cli -h 127.0.0.1 XLEN shuttle:events
echo "=== 完成 ==="
echo "代码SHA：$EXPECTED_SHA"
echo "业务仅通过SSH隧道/服务器本机访问；未配置公网HTTP代理或TLS。"
echo "每日备份：/etc/cron.d/shuttleflow-backup；备份目录：/var/backups/shuttleflow"
echo "隔离验收库保留供审查：$test_db"
