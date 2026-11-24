#!/usr/bin/env bash
# 只做切换前的双版本备份、恢复演练及 SSH jail 修复，不发布网站。
set -Eeuo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo '请在已登录的 VPS SSH 终端用 sudo 执行。' >&2; exit 1; }
[[ $# -eq 1 ]] || { echo '请输入当前会话的 SSH_CONNECTION 参数。' >&2; exit 1; }
source_ip="$(python3 - "$1" <<'PY'
import ipaddress, sys
src, sport, dst, dport = sys.argv[1].split()
address = ipaddress.ip_address(src)
ipaddress.ip_address(dst)
assert 0 < int(sport) < 65536 and int(dport) == 22
print(str(address))
PY
)"
base=/var/backups/shuttleflow-cutover
install -d -m 0700 "$base"
backup="$(mktemp -d "$base/$(date -u +%Y%m%dT%H%M%SZ).XXXXXXXX")"
phase='只读检查'
restore_dir=''
restore_pid=''
cleanup() {
  local status=$?
  if [[ -n "$restore_pid" ]]; then
    mysqladmin --no-defaults -uroot --protocol=socket --socket="$restore_dir/mysql.sock" shutdown >/dev/null 2>&1 || kill "$restore_pid" 2>/dev/null || true
    wait "$restore_pid" 2>/dev/null || true
  fi
  if [[ "$restore_dir" == /var/tmp/shuttle-cutover-restore.* && -d "$restore_dir" ]]; then rm -rf -- "$restore_dir"; fi
  if (( status != 0 )); then printf '未完成：%s。已保留受保护的备份和诊断目录：%s\n' "$phase" "$backup" >&2; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
printf '受保护的备份目录：%s\n' "$backup"
v12="$(readlink -e /opt/shuttleflow/current)"
v13="$(readlink -e /opt/shuttle-v13/current)"
[[ "$v12" == /opt/shuttleflow/releases/* && "$v13" == /opt/shuttle-v13/releases/* ]]
v13_db="$(cat /etc/shuttle-v13/schema)"
[[ "$v13_db" =~ ^shuttle_v13_sim_[a-f0-9]{8}$ ]]
# 确认旧实例的应用仍然指向已知业务库，不显示连接字符串。
grep -Eq '^SHUTTLE_DB_URL=jdbc:mysql://127\.0\.0\.1:3306/shuttle_db(\?|$)' /etc/shuttleflow/shuttleflow.env
nginx -t
# 用回环连接和备份账号核对备份实例，不能因配置错误导出另一套应用库。
[[ "$(mysql --defaults-extra-file=/etc/shuttleflow/backup.cnf --batch --skip-column-names -e 'SELECT @@port')" == 3306 ]]
[[ "$(mysql --defaults-extra-file=/etc/shuttle-v13/backup.cnf --batch --skip-column-names -e 'SELECT @@port')" == 3308 ]]
{
  printf 'v12_release=%s\nv13_release=%s\n' "${v12##*/}" "${v13##*/}"
  git -c safe.directory="$v12" -C "$v12" rev-parse --verify HEAD 2>/dev/null || true
  if [[ -f "$v13/VERSION" ]]; then awk '/^[a-f0-9]{40}$/ {print; exit}' "$v13/VERSION"; fi
  find "$v12/backend/target" "$v13" -maxdepth 1 -type f -name '*.jar' -exec sha256sum {} + 2>/dev/null || true
  for unit in shuttleflow shuttle-ai mysql redis-server nginx cron fail2ban shuttle-v13-{java,ai,mysql,redis,operation,backup.timer,monitor.timer}; do
    printf '%s ' "$unit"; systemctl is-active "$unit" || true
  done
  for dir in /var/backups/shuttleflow /var/backups/shuttle-v13; do
    printf 'scheduled_backup_%s=' "${dir##*/}"
    if find "$dir" -maxdepth 1 -name '*.sql.gz' -type f -mmin -1560 -print | awk 'END{exit NR==0}'; then echo fresh; else echo missing_or_old; fi
  done
  free -m
  df -h /
} >"$backup/inventory.txt"
# 验证 Navicat 专用账号仅有指定库的两个权限，并要求 MySQL 加密传输；不显示账号配置内容。
mysql --defaults-extra-file=/etc/shuttle-v13/mysql-admin.cnf --batch --skip-column-names >"$backup/navicat-check.txt" <<SQL
SELECT IF(@@port=3308 AND @@require_secure_transport=1,'mysql_tls_required=yes','mysql_tls_required=no');
SELECT IF(COUNT(*)=1 AND MIN(ssl_type) IN ('ANY','X509','SPECIFIED'),'reader_tls_required=yes','reader_tls_required=no') FROM mysql.user WHERE User='navicat_reader';
SELECT IF(COUNT(*)=2 AND COUNT(DISTINCT PRIVILEGE_TYPE)=2 AND SUM(TABLE_SCHEMA='$v13_db' AND PRIVILEGE_TYPE IN ('SELECT','SHOW VIEW') AND IS_GRANTABLE='NO')=2,'reader_schema_grants=yes','reader_schema_grants=no') FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE LIKE "'navicat_reader'@%";
SELECT IF(EXISTS(SELECT 1 FROM information_schema.USER_PRIVILEGES WHERE GRANTEE LIKE "'navicat_reader'@%" AND PRIVILEGE_TYPE <> 'USAGE') OR EXISTS(SELECT 1 FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE LIKE "'navicat_reader'@%") OR EXISTS(SELECT 1 FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE LIKE "'navicat_reader'@%") OR EXISTS(SELECT 1 FROM mysql.role_edges WHERE TO_USER='navicat_reader') OR EXISTS(SELECT 1 FROM mysql.procs_priv WHERE User='navicat_reader') OR EXISTS(SELECT 1 FROM mysql.global_grants WHERE USER='navicat_reader'),'reader_other_grants=yes','reader_other_grants=no');
SQL
cat "$backup/navicat-check.txt"
phase='应用和配置备份'
need_kib="$(du -sk /opt/shuttleflow /opt/shuttle-v13 /etc/letsencrypt | awk '{sum+=$1} END{print sum*2+1048576}')"
free_kib="$(df -Pk "$backup" | awk 'NR==2{print $4}')"
(( free_kib > need_kib ))
# 归档包含配置和 TLS 私钥，仅在 VPS root 目录留存，不输出文件内容。
tar -C / -czf "$backup/app-config.tar.gz" \
  opt/shuttleflow opt/shuttle-v13 etc/shuttleflow etc/shuttle-v13 etc/nginx etc/letsencrypt \
  etc/fail2ban etc/systemd/system etc/cron.d etc/mysql etc/redis 2>"$backup/tar.log"
gzip -t "$backup/app-config.tar.gz"
tar -tzf "$backup/app-config.tar.gz" >/dev/null
phase='业务数据库备份'
dump() {
  local schema="$1" client="$2" label="$3"
  mysqldump --defaults-extra-file="$client" --single-transaction --quick --no-tablespaces \
    --skip-add-locks --set-gtid-purged=OFF --events --triggers "$schema" \
    2>"$backup/$label-dump.log" | gzip -1 >"$backup/$label.sql.gz"
  gzip -t "$backup/$label.sql.gz"
  gzip -dc "$backup/$label.sql.gz" | awk '/^-- Dump completed on / { ok=1 } END{exit !ok}'
}
dump shuttle_db /etc/shuttleflow/backup.cnf v12
dump "$v13_db" /etc/shuttle-v13/backup.cnf v13
backup_files=(app-config.tar.gz v12.sql.gz v13.sql.gz)
latest_archive="$(find /var/backups/shuttle-v13 -maxdepth 1 -type f -name 'archive-*.tar.gz' -printf '%T@ %p\n' | sort -nr | awk 'NR==1{print $2}')"
if [[ -n "$latest_archive" ]]; then
  install -m 0600 "$latest_archive" "$backup/v13-operation-last.tar.gz"
  gzip -t "$backup/v13-operation-last.tar.gz"
  tar -tzf "$backup/v13-operation-last.tar.gz" >/dev/null
  backup_files+=(v13-operation-last.tar.gz)
fi
(cd "$backup" && sha256sum "${backup_files[@]}" > SHA256SUMS && sha256sum -c SHA256SUMS)
phase='独立 MySQL 恢复演练'
# 不使用生产 MySQL socket/端口。只在独立临时 datadir 中恢复。
(( $(awk '/MemAvailable:/{print $2}' /proc/meminfo) > 1048576 ))
restore_dir="$(mktemp -d /var/tmp/shuttle-cutover-restore.XXXXXXXX)"
chown mysql:mysql "$restore_dir"
/usr/sbin/mysqld --no-defaults --initialize-insecure --datadir="$restore_dir" --user=mysql >"$backup/restore.log" 2>&1
/usr/sbin/mysqld --no-defaults --datadir="$restore_dir" --user=mysql --skip-networking --mysqlx=OFF \
  --socket="$restore_dir/mysql.sock" --pid-file="$restore_dir/mysql.pid" --innodb-buffer-pool-size=64M \
  --performance-schema=OFF >>"$backup/restore.log" 2>&1 &
restore_pid=$!
local_mysql=(mysql --no-defaults -uroot --protocol=socket --socket="$restore_dir/mysql.sock" --batch --skip-column-names)
for _ in {1..35}; do
  if "${local_mysql[@]}" -e 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
for label in v12 v13; do
  "${local_mysql[@]}" -e "CREATE DATABASE restore_$label CHARACTER SET utf8mb4" >>"$backup/restore.log" 2>&1
  gzip -dc "$backup/$label.sql.gz" | "${local_mysql[@]}" "restore_$label" >>"$backup/restore.log" 2>&1
  tables="$("${local_mysql[@]}" -e "SELECT count(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='restore_$label' AND TABLE_TYPE='BASE TABLE'")"
  (( tables > 0 ))
  printf '%s 恢复成功，数据表 %s 张。\n' "$label" "$tables"
done
mysqladmin --no-defaults -uroot --protocol=socket --socket="$restore_dir/mysql.sock" shutdown >/dev/null 2>&1
wait "$restore_pid"
restore_pid=''
phase='Fail2ban 配置测试和启用'
journalctl -q -u ssh.service -o cat --since '1 day ago' | awk '/Accepted publickey/ { found=1 } END { exit !found }'
override=/etc/fail2ban/jail.d/99-shuttle-sshd-journal.local
[[ ! -e "$override" && ! -L "$override" ]] || { echo '检测到已有专用配置，请先人工检查，不覆盖。' >&2; exit 1; }
was_active=0
if systemctl is-active --quiet fail2ban; then was_active=1; fi
revert_jail() {
  rm -f -- "$override"
  if (( was_active )); then systemctl restart fail2ban || true; else systemctl stop fail2ban || true; fi
  exit 1
}
cat >"$backup/sshd-journal.local" <<JAIL
[sshd]
backend = systemd
filter = sshd[journalmatch="_SYSTEMD_UNIT=ssh.service + _COMM=sshd"]
ignoreip = %(known/ignoreip)s 127.0.0.1/8 ::1 $source_ip
JAIL
install -m 0644 "$backup/sshd-journal.local" "$override"
fail2ban-client -t >"$backup/fail2ban-test.log" 2>&1 || revert_jail
systemctl restart fail2ban || revert_jail
for _ in {1..15}; do
  if fail2ban-client status sshd >"$backup/fail2ban-status.txt" 2>&1; then break; fi
  sleep 1
done
systemctl is-active --quiet fail2ban || revert_jail
fail2ban-client status sshd >"$backup/fail2ban-status.txt" 2>&1 || revert_jail
fail2ban-client get sshd journalmatch >"$backup/fail2ban-journalmatch.txt" 2>&1 || revert_jail
grep -Fq 'ssh.service' "$backup/fail2ban-journalmatch.txt" || revert_jail
fail2ban-client get sshd ignoreip >"$backup/fail2ban-ignoreip.txt" 2>&1 || revert_jail
grep -Fq -- "$source_ip" "$backup/fail2ban-ignoreip.txt" || revert_jail
head -n 2 "$backup/inventory.txt"
printf 'Fail2ban SSH jail 已启用 journal；请保留此终端，再开启一个 SSH 会话验证。\n备份校验清单：%s/SHA256SUMS\n本步骤未切换网站。\n' "$backup"
