#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="${SHUTTLE_INSTALL_DIR:-/opt/shuttleflow}"
COMMIT="${SHUTTLE_ROLLBACK_COMMIT:?请设置目标Git提交SHA}"
target="$ROOT/releases/$COMMIT"
if [[ ! -d "$target/backend/target" ]]; then echo "目标版本不在本机发布目录中：$target" >&2; exit 1; fi
if [[ ! -f "$target/backend/target/shuttle-1.0.0.jar" ]]; then echo "目标版本缺少已构建JAR。" >&2; exit 1; fi
ln -sfn "$target" "$ROOT/current.rollback"
mv -Tf "$ROOT/current.rollback" "$ROOT/current"
if ! systemctl restart shuttle-ai shuttleflow; then echo "回滚重启失败，请检查 journalctl。" >&2; exit 1; fi
"$ROOT/current/deploy/bin/health-check.sh"
echo "代码版本已回滚到 $COMMIT。数据库迁移为可加列/约束；回滚不删除数据。"
