"""为本地验收新建独立MySQL测试库；只创建新库和账号，不更新/删除已有库。"""
import configparser
import os
import secrets
import shlex
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CNF = Path(os.environ.get("SHUTTLE_TEST_MYSQL_CNF", ROOT / ".runtime/mysql-root.cnf"))
PORT = os.environ.get("SHUTTLE_TEST_MYSQL_PORT", "3308")
if not CNF.is_file():
    raise SystemExit(f"找不到MySQL本机管理凭据文件：{CNF}")

suffix = secrets.token_hex(3)
fresh_db = f"shuttle_v12_fresh_{suffix}"
upgrade_db = f"shuttle_v11_upgrade_{suffix}"
db_user = f"sfv12_{suffix}"
db_password = secrets.token_hex(32)
ai_token = secrets.token_hex(32)


def mysql(args, sql=None):
    command = ["mysql", f"--defaults-extra-file={CNF}", "--protocol=tcp", "--host=127.0.0.1",
               "--port", PORT, "--batch", "--skip-column-names"] + args
    return subprocess.run(command, input=sql, text=True, check=True, capture_output=True).stdout


schemas = set(mysql(["-e", "SELECT schema_name FROM information_schema.schemata"]).splitlines())
if fresh_db in schemas or upgrade_db in schemas:
    raise SystemExit("测试库名称意外已存在；停止，不覆盖已有数据。")

mysql([], f"""
CREATE DATABASE `{fresh_db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE `{upgrade_db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '{db_user}'@'127.0.0.1' IDENTIFIED BY '{db_password}';
GRANT SELECT, INSERT, UPDATE, DELETE ON `{fresh_db}`.* TO '{db_user}'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON `{upgrade_db}`.* TO '{db_user}'@'127.0.0.1';
""")

fresh_sql = (ROOT / "backend/src/main/resources/sql/init.sql").read_text().replace("shuttle_db", fresh_db)
mysql([], fresh_sql)

old_sql = subprocess.run(["git", "show", "8b2c0ba:backend/src/main/resources/sql/init.sql"],
                         cwd=ROOT, check=True, capture_output=True, text=True).stdout
upgrade_sql = old_sql.replace("shuttle_db", upgrade_db)
mysql([], upgrade_sql)

legacy_admin_password = secrets.token_hex(24)
legacy_driver_password = secrets.token_hex(24)
mysql([upgrade_db], f"""
UPDATE admin SET password='{legacy_admin_password}' WHERE username='admin';
UPDATE driver SET password='{legacy_driver_password}' WHERE phone='13800000001';
""")
migration = (ROOT / "backend/src/main/resources/db/migration/V1.2__security_and_idempotency.sql").read_text()
mysql([upgrade_db], migration)

def env_text(values):
    return "".join(f"{key}={shlex.quote(str(value))}\n" for key, value in values.items())


base = {
    "SHUTTLE_BIND_ADDRESS": "127.0.0.1",
    "SHUTTLE_SESSION_COOKIE_SECURE": "false",
    "SHUTTLE_REDIS_HOST": "127.0.0.1",
    "SHUTTLE_REDIS_PORT": "6379",
    "SHUTTLE_REDIS_PASSWORD": "",
    "SHUTTLE_AI_SERVICE_URL": "http://127.0.0.1:8000",
    "SHUTTLE_AI_SERVICE_TOKEN": ai_token,
    "SHUTTLE_BACKEND_API_URL": "http://127.0.0.1:8080/api",
    "AI_BIND_HOST": "127.0.0.1",
    "AI_PORT": "8000",
    "SHUTTLE_CORS_ALLOWED_ORIGINS": "http://127.0.0.1:8080,http://localhost:8080",
    "SHUTTLE_RATE_LIMIT_AUTH": "5000",
    "SHUTTLE_RATE_LIMIT_AUTH_WINDOW_SECONDS": "60",
    "SHUTTLE_RATE_LIMIT_PASSENGER": "5000",
    "SHUTTLE_RATE_LIMIT_WRITE_WINDOW_SECONDS": "60",
    "SHUTTLE_TOMCAT_MAX_THREADS": "128",
    "SHUTTLE_TOMCAT_MAX_CONNECTIONS": "256",
    "SHUTTLE_REDIS_EVENT_STREAM_MAX_LENGTH": "10000",
    "SHUTTLE_TEST_API": "http://127.0.0.1:8080/api",
}

fresh_admin_password = secrets.token_urlsafe(24)
fresh_driver_password = secrets.token_urlsafe(20)
fresh_passenger_password = secrets.token_urlsafe(18)
fresh_dispatcher_password = secrets.token_urlsafe(20)
fresh_phone = "198" + "".join(secrets.choice("0123456789") for _ in range(15))

fresh = dict(base)
fresh.update({
    "SHUTTLE_DB_URL": f"jdbc:mysql://127.0.0.1:3308/{fresh_db}?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
    "SHUTTLE_DB_USER": db_user,
    "SHUTTLE_DB_PASSWORD": db_password,
    "SHUTTLE_BOOTSTRAP_ADMIN_USERNAME": "v12admin",
    "SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD": fresh_admin_password,
    "SHUTTLE_TEST_DB_NAME": fresh_db,
    "SHUTTLE_TEST_ADMIN_USERNAME": "v12admin",
    "SHUTTLE_TEST_ADMIN_PASSWORD": fresh_admin_password,
    "SHUTTLE_TEST_DRIVER_PHONE": fresh_phone,
    "SHUTTLE_TEST_DRIVER_PASSWORD": fresh_driver_password,
    "SHUTTLE_TEST_DRIVER_NAME": "V12并发验收司机",
    "SHUTTLE_TEST_PASSENGER_PASSWORD": fresh_passenger_password,
    "SHUTTLE_TEST_DISPATCHER_PASSWORD": fresh_dispatcher_password,
})

upgrade = dict(base)
upgrade_admin_password = legacy_admin_password
upgrade_driver_password = legacy_driver_password
upgrade.update({
    "SHUTTLE_DB_URL": f"jdbc:mysql://127.0.0.1:3308/{upgrade_db}?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
    "SHUTTLE_DB_USER": db_user,
    "SHUTTLE_DB_PASSWORD": db_password,
    "SHUTTLE_BOOTSTRAP_ADMIN_USERNAME": "",
    "SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD": "",
    "SHUTTLE_TEST_DB_NAME": upgrade_db,
    "SHUTTLE_TEST_ADMIN_USERNAME": "admin",
    "SHUTTLE_TEST_ADMIN_PASSWORD": upgrade_admin_password,
    "SHUTTLE_TEST_DRIVER_PHONE": "13800000001",
    "SHUTTLE_TEST_DRIVER_PASSWORD": upgrade_driver_password,
    "SHUTTLE_TEST_DRIVER_NAME": "司机01",
    "SHUTTLE_TEST_PASSENGER_PASSWORD": secrets.token_urlsafe(18),
    "SHUTTLE_TEST_DISPATCHER_PASSWORD": secrets.token_urlsafe(20),
})

runtime = ROOT / ".runtime"
runtime.mkdir(exist_ok=True)
for name, values in (("v12-fresh.env", fresh), ("v12-upgrade.env", upgrade)):
    path = runtime / name
    path.write_text(env_text(values))
    path.chmod(0o600)

print(f"已创建独立测试库：{fresh_db}（V1.2空库）和 {upgrade_db}（V1.1克隆后升级）。")
print("测试凭据只写入 .runtime/v12-fresh.env 与 .runtime/v12-upgrade.env；没有修改 shuttle_db。")
