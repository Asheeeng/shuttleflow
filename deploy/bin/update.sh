#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="${SHUTTLE_INSTALL_DIR:-/opt/shuttleflow}"
REPO="$ROOT/repository"
RELEASES="$ROOT/releases"
BRANCH="${SHUTTLE_DEPLOY_BRANCH:-main}"
JAVA_UNIT=shuttleflow
AI_UNIT=shuttle-ai
mkdir -p "$RELEASES"
exec 9>/run/lock/shuttleflow-deploy.lock
flock -n 9 || { echo "已有部署任务运行，退出。" >&2; exit 1; }

if [[ ! -d "$REPO/.git" ]]; then
  echo "请先将 Git 仓库克隆到 $REPO" >&2
  exit 1
fi
git -C "$REPO" fetch --no-tags origin "$BRANCH"
commit="$(git -C "$REPO" rev-parse FETCH_HEAD)"
release="$RELEASES/$commit"
if [[ ! -d "$release" ]]; then
  git -C "$REPO" worktree add --detach "$release" "$commit"
fi

if [[ ! -x "$release/ai-service/.venv/bin/uvicorn" ]]; then
  python3 -m venv "$release/ai-service/.venv"
  "$release/ai-service/.venv/bin/pip" install --disable-pip-version-check -r "$release/ai-service/requirements.txt"
fi
if [[ ! -f "$release/backend/target/shuttle-1.0.0.jar" ]]; then
  mvn -f "$release/backend/pom.xml" -DskipTests package
fi
test -s "$release/backend/target/shuttle-1.0.0.jar"

previous=""
if [[ -L "$ROOT/current" ]]; then previous="$(readlink -f "$ROOT/current")"; fi
ln -sfn "$release" "$ROOT/current.next"
mv -Tf "$ROOT/current.next" "$ROOT/current"

rollback() {
  if [[ -n "$previous" && -d "$previous" ]]; then
    ln -sfn "$previous" "$ROOT/current.rollback"
    mv -Tf "$ROOT/current.rollback" "$ROOT/current"
    systemctl restart "$AI_UNIT" "$JAVA_UNIT" || true
  else
    rm -f "$ROOT/current"
    systemctl stop "$AI_UNIT" "$JAVA_UNIT" || true
  fi
}

if ! systemctl restart "$AI_UNIT" "$JAVA_UNIT"; then
  echo "服务重启失败，恢复上一版本。" >&2
  rollback
  exit 1
fi

ready=0
for _ in $(seq 1 30); do
  if SHUTTLE_PUBLIC_BASE_URL=http://127.0.0.1:8080 SHUTTLE_AI_HEALTH_URL=http://127.0.0.1:8000/health \
       "$ROOT/current/deploy/bin/health-check.sh" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" -ne 1 ]]; then
  echo "新版本未通过就绪检查，恢复上一版本。" >&2
  rollback
  exit 1
fi
echo "部署完成：$commit"
echo "回滚方式：SHUTTLE_ROLLBACK_COMMIT=<旧提交> $ROOT/current/deploy/bin/rollback.sh"
