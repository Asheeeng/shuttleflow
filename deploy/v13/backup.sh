#!/usr/bin/env bash
set -Eeuo pipefail
DB="$(cat /etc/shuttle-v13/schema)"
[[ "$DB" =~ ^shuttle_v13_sim_[a-f0-9]{8}$ ]]
for _ in $(seq 1 40); do
  if mysql --defaults-extra-file=/etc/shuttle-v13/backup.cnf -e 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
SHUTTLE_DB_NAME="$DB" SHUTTLE_BACKUP_CNF=/etc/shuttle-v13/backup.cnf  SHUTTLE_BACKUP_DIR=/var/backups/shuttle-v13 bash /opt/shuttle-v13/current/deploy/bin/backup-mysql.sh
umask 077
target="/var/backups/shuttle-v13/archive-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
if [[ -d /var/lib/shuttle-v13/operation ]]; then
  temp="$(mktemp /var/backups/shuttle-v13/.archive.XXXXXX)"
  trap 'rm -f "$temp"' EXIT
  tar --exclude='*.tmp' --ignore-failed-read -C /var/lib/shuttle-v13 -czf "$temp" operation
  gzip -t "$temp"
  mv "$temp" "$target"
  find /var/backups/shuttle-v13 -maxdepth 1 -type f -name 'archive-*.tar.gz' -mtime +14 -delete
fi
