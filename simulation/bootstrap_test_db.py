#!/usr/bin/env python3
"""只在本机临时MySQL实例创建V1.3仿真数据库和最小权限账号。"""
from __future__ import annotations

import os
import pathlib
import secrets
import shlex
import stat
import subprocess
import sys

RUN_DIR = pathlib.Path(os.environ.get("SHUTTLE_V13_RUN_DIR", "/private/tmp/shuttleflow-v13-runtime"))
PORT = 3308
DB_NAME = os.environ.get("SHUTTLE_V13_DB_NAME", "")
ROOT = pathlib.Path(__file__).resolve().parents[1]
MYSQL_ROOT_CNF = pathlib.Path(os.environ.get(
    "SHUTTLE_V13_MYSQL_ROOT_CNF", str(ROOT.parent / "shuttleflow" / ".runtime" / "mysql-root.cnf")))


def mysql(sql: str) -> str:
    proc = subprocess.run([
        "mysql", f"--defaults-extra-file={MYSQL_ROOT_CNF}", "--protocol=tcp", "--host=127.0.0.1",
        f"--port={PORT}",
        "--batch", "--skip-column-names", "--raw",
    ], input=sql, text=True, capture_output=True, check=True)
    return proc.stdout.strip()


def main() -> int:
    if not DB_NAME.startswith("shuttle_v13_sim_") or not DB_NAME.replace("_", "").isalnum():
        raise SystemExit("数据库名必须以 shuttle_v13_sim_ 开头，只允许字母、数字和下划线。")
    if not RUN_DIR.is_dir():
        raise SystemExit("隔离运行目录不存在；请先运行 simulation/runtime.py start。")
    if not MYSQL_ROOT_CNF.is_file():
        raise SystemExit(f"找不到本机独立测试MySQL凭据文件：{MYSQL_ROOT_CNF}。不要使用正式数据库账号。")
    if stat.S_IMODE(MYSQL_ROOT_CNF.stat().st_mode) & 0o077:
        raise SystemExit("本机测试MySQL凭据文件权限必须为0600或更严格。")
    server = mysql("SELECT @@port, @@bind_address, @@hostname").split("\t")
    if len(server) < 3 or int(server[0]) != PORT or server[1] != "127.0.0.1":
        raise SystemExit("MySQL必须是仅绑定127.0.0.1:3308的独立本机测试实例；停止，未创建数据库。")
    schemas = set(mysql("SELECT schema_name FROM information_schema.schemata").splitlines())
    if DB_NAME in schemas:
        raise SystemExit(f"测试数据库{DB_NAME}已存在；为防覆盖，停止。")

    app_user = "shuttle_v13_app_" + DB_NAME.rsplit("_", 1)[-1]
    app_password = secrets.token_urlsafe(32)
    admin_password = secrets.token_urlsafe(28)
    ai_token = secrets.token_urlsafe(32)
    mysql(f"""
CREATE DATABASE `{DB_NAME}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '{app_user}'@'127.0.0.1' IDENTIFIED BY '{app_password}';
GRANT SELECT, INSERT, UPDATE, DELETE ON `{DB_NAME}`.* TO '{app_user}'@'127.0.0.1';
""")
    sql = (ROOT / "backend/src/main/resources/sql/init.sql").read_text(encoding="utf-8")
    # init.sql内有DROP TABLE，仅导入刚创建且名字受保护的隔离测试库。
    mysql(f"USE `{DB_NAME}`;\n" + sql.replace("shuttle_db", DB_NAME))

    values = {
        "SHUTTLE_V13_RUN_DIR": str(RUN_DIR),
        "SHUTTLE_V13_DB_NAME": DB_NAME,
        "SHUTTLE_TEST_DB_NAME": DB_NAME,
        "SHUTTLE_DB_URL": f"jdbc:mysql://127.0.0.1:{PORT}/{DB_NAME}?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
        "SHUTTLE_DB_USER": app_user,
        "SHUTTLE_DB_PASSWORD": app_password,
        "SHUTTLE_BOOTSTRAP_ADMIN_USERNAME": "v13sim-admin",
        "SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD": admin_password,
        "SHUTTLE_TEST_ADMIN_USERNAME": "v13sim-admin",
        "SHUTTLE_TEST_ADMIN_PASSWORD": admin_password,
        "SHUTTLE_AI_SERVICE_TOKEN": ai_token,
        "SHUTTLE_BIND_ADDRESS": "127.0.0.1",
        "SERVER_PORT": "18080",
        "SHUTTLE_REDIS_HOST": "127.0.0.1",
        "SHUTTLE_REDIS_PORT": "16379",
        "SHUTTLE_REDIS_PASSWORD": "",
        "SHUTTLE_AI_SERVICE_URL": "http://127.0.0.1:18000",
        "SHUTTLE_BACKEND_API_URL": "http://127.0.0.1:18080/api",
        "AI_BIND_HOST": "127.0.0.1",
        "AI_PORT": "18000",
        "SHUTTLE_CORS_ALLOWED_ORIGINS": "http://127.0.0.1:18080,http://localhost:18080",
        "SHUTTLE_RATE_LIMIT_AUTH": "5000",
        "SHUTTLE_RATE_LIMIT_AUTH_WINDOW_SECONDS": "60",
        "SHUTTLE_RATE_LIMIT_PASSENGER": "5000",
        "SHUTTLE_RATE_LIMIT_WRITE_WINDOW_SECONDS": "60",
        "SHUTTLE_TOMCAT_MAX_THREADS": "16",
        "SHUTTLE_TOMCAT_MAX_CONNECTIONS": "64",
        "SHUTTLE_REDIS_EVENT_STREAM_MAX_LENGTH": "2000",
        "SHUTTLE_SIMULATION_ENABLED": "true",
        "SHUTTLE_OPERATION_ENABLED": "true",
        "SHUTTLE_OPERATION_TOKEN": secrets.token_urlsafe(32),
        "SHUTTLE_CUSUM_SCHEDULER_ENABLED": "false",
        "SHUTTLE_DISPATCH_COURSE_PREPOSITION_ENABLED": "false",
    }
    env_file = RUN_DIR / "app.env"
    temp = env_file.with_suffix(".env.tmp")
    temp.write_text("".join(f"export {key}={shlex.quote(value)}\n" for key, value in values.items()), encoding="utf-8")
    temp.chmod(0o600)
    os.replace(temp, env_file)
    print(f"已创建隔离数据库：{DB_NAME}；应用用户仅拥有该库读写权限。")
    print(f"连接配置已写入权限0600的临时文件：{env_file}；密码和令牌未输出。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
