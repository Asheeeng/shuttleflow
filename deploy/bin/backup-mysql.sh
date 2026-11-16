#!/usr/bin/env bash
set -Eeuo pipefail

DB_NAME="${SHUTTLE_DB_NAME:-shuttle_db}"
CNF="${SHUTTLE_BACKUP_CNF:-/etc/shuttleflow/backup.cnf}"
BACKUP_DIR="${SHUTTLE_BACKUP_DIR:-/var/backups/shuttleflow}"
RETAIN_DAYS="${SHUTTLE_BACKUP_RETAIN_DAYS:-14}"
if [[ ! -r "$CNF" ]]; then echo "找不到备份账号配置：$CNF" >&2; exit 1; fi
if [[ ! "$DB_NAME" =~ ^[A-Za-z0-9_]{1,48}$ ]]; then echo "数据库名称格式无效。" >&2; exit 1; fi
if [[ ! "$RETAIN_DAYS" =~ ^[1-9][0-9]{0,2}$ ]]; then echo "备份保留天数无效。" >&2; exit 1; fi

umask 077
install -d -m 0700 "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
tmp="$BACKUP_DIR/.${DB_NAME}-${stamp}.sql.gz.tmp"
out="$BACKUP_DIR/${DB_NAME}-${stamp}.sql.gz"
trap 'rm -f "$tmp"' EXIT
mysqldump --defaults-extra-file="$CNF" --single-transaction --quick --no-tablespaces \
  --skip-add-locks --set-gtid-purged=OFF "$DB_NAME" | gzip -9 > "$tmp"
gzip -t "$tmp"
mv "$tmp" "$out"
chmod 0600 "$out"
find "$BACKUP_DIR" -maxdepth 1 -type f -name "${DB_NAME}-*.sql.gz" -mtime "+$RETAIN_DAYS" -delete
trap - EXIT
echo "备份完成：$out"
