#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(id -u)" -ne 0 ]]; then echo "请使用 sudo 运行此脚本。" >&2; exit 1; fi
if [[ $# -ne 2 ]]; then echo "用法：sudo $0 <备份.sql.gz> <新库名>（例如 shuttle_restore_20261116）" >&2; exit 2; fi
backup="$1"
target="$2"
if [[ ! -r "$backup" ]]; then echo "备份文件不可读。" >&2; exit 1; fi
if [[ ! "$target" =~ ^shuttle_restore_[A-Za-z0-9_]{1,32}$ ]]; then
  echo "目标库必须使用 shuttle_restore_ 前缀，避免覆盖正式库。" >&2
  exit 1
fi
gzip -t "$backup"
exists="$(mysql --protocol=socket --user=root --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$target'")"
if [[ "$exists" != "0" ]]; then echo "目标库已存在，拒绝覆盖。" >&2; exit 1; fi
mysql --protocol=socket --user=root -e "CREATE DATABASE \`$target\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
if ! gzip -dc "$backup" | mysql --protocol=socket --user=root "$target"; then
  echo "恢复导入失败。保留新建目标库供检查；未删除任何数据。" >&2
  exit 1
fi
echo "恢复完成到新库 $target。请验证后再安排切换；正式库未被覆盖。"
