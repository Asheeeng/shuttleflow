#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
[[ $EUID == 0 ]] || { echo "请使用交互式 sudo"; exit 1; }
CONF=/etc/shuttle-v13
ROOT=/opt/shuttle-v13
DOMAIN=v13.shuttleflow-gzu.duckdns.org
SITE=/etc/nginx/sites-available/shuttle-v13
LINK=/etc/nginx/sites-enabled/shuttle-v13
RELEASE="$(readlink -f "$ROOT/current")"
[[ "$RELEASE" =~ ^/opt/shuttle-v13/releases/[a-f0-9]{40}$ ]] || exit 1
[[ -f "$RELEASE/VERSION" && "$(cat "$RELEASE/VERSION")" == "${RELEASE##*/}" ]]
[[ -s "$CONF/app.env" && -s "$CONF/mysql-admin.cnf" && -s "$CONF/backup.cnf" && -s "$CONF/schema" ]]
[[ "$(cat "$CONF/schema")" =~ ^shuttle_v13_sim_[a-f0-9]{8}$ ]]
[[ -d /var/lib/shuttle-v13-mysql/mysql && -f /etc/systemd/system/shuttle-v13.target ]]
[[ ! -e "$LINK" && ! -L "$LINK" ]] || { echo "V1.3 已有启用站点，请先核对发布状态"; exit 1; }
(cd "$RELEASE" && sha256sum -c --quiet SHA256SUMS)

EDGE="${SHUTTLE_V13_ACCESS_PASSWORD:-}"
if [[ -z "$EDGE" ]]; then
  read -rsp '请重新输入 V1.3 HTTPS 入口密码（至少 20 位字母数字）: ' EDGE </dev/tty
  echo
fi
[[ "$EDGE" =~ ^[A-Za-z0-9]{20,128}$ ]] || { echo "HTTPS 入口密码格式不符"; exit 1; }
while IFS= read -r line; do
  if [[ "$line" == "SHUTTLE_TEST_ADMIN_PASSWORD=$EDGE" ]]; then
    echo "HTTPS 入口密码与应用管理员密码必须不同"
    exit 1
  fi
done < "$CONF/app.env"
unset line
unset SHUTTLE_V13_ACCESS_PASSWORD

on_error() {
  local rc=$?
  if (( rc == 0 )); then return; fi
  echo "续装失败（退出码 $rc）。保留 V1.3 数据和原有备份。"
  systemctl show shuttle-v13-{mysql,redis,ai,java,operation}.service -p Id -p ActiveState -p Result -p ExecMainStatus || true
  systemctl disable --now shuttle-v13-backup.timer shuttle-v13-monitor.timer 2>/dev/null || true
  if [[ -L "$LINK" && "$(readlink "$LINK")" == "$SITE" ]]; then
    rm "$LINK"
    /usr/sbin/nginx -t && systemctl reload nginx || true
  fi
  systemctl stop shuttle-v13.target 2>/dev/null || true
}
trap on_error EXIT

# Never follow virtualenv symlinks into /usr or grant writes to the runtime user.
[[ -d "$ROOT/venv" && ! -L "$ROOT/venv" ]]
find "$ROOT/venv" -xdev \( -type d -o -type f \) -exec chgrp shuttlev13 {} +
find "$ROOT/venv" -xdev \( -type d -o -type f \) -exec chmod g+rX,go-w,o-rwx {} +
runuser -u shuttlev13 -- "$ROOT/venv/bin/python" -I -c 'import uvicorn,fastapi,numpy,requests; print("V1.3 服务账号已验证 Python 执行和依赖导入")'
systemctl reset-failed shuttle-v13-{mysql,redis,ai,java,operation}.service shuttle-v13.target || true
systemctl start shuttle-v13.target
for _ in $(seq 1 90); do
  if curl -fsS --max-time 2 http://127.0.0.1:18080/api/health/ready 2>/dev/null | grep -q READY; then break; fi
  sleep 1
done
curl -fsS --max-time 3 http://127.0.0.1:18080/api/health/ready | grep -q READY
curl -fsS http://127.0.0.1:18000/health >/dev/null
systemctl start shuttle-v13-backup.service
systemctl enable --now shuttle-v13-backup.timer shuttle-v13-monitor.timer

[[ "$(getent ahostsv4 "$DOMAIN" | awk '{print $1}' | sort -u)" == 43.251.117.159 ]]
install -d -m 0755 /var/www/shuttle-v13-acme
sed "s/@DOMAIN@/$DOMAIN/g" "$RELEASE/deploy/v13/nginx-http.conf" > "$SITE"
ln -s "$SITE" "$LINK"
/usr/sbin/nginx -t && systemctl reload nginx
certbot certonly --webroot -w /var/www/shuttle-v13-acme -d "$DOMAIN" --non-interactive --agree-tos --keep-until-expiring --cert-name "$DOMAIN"
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
