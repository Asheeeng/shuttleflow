"""启动/停止本机隔离仿真应用；MySQL使用已有的localhost:3308测试实例。"""
from __future__ import annotations

import argparse
import os
import pathlib
import secrets
import shlex
import signal
import socket
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_RUN_DIR = pathlib.Path("/private/tmp/shuttleflow-v13-runtime-3308")
AI_VENV = ROOT / "simulation" / ".venv"
MYSQL_TEST_PORT, REDIS_PORT, AI_PORT, JAVA_PORT = 3308, 16379, 18000, 18080
OPERATION_PORT = 18001
EXPERIMENT_PORT = 18002


def port_available(port: int) -> bool:
    with socket.socket() as sock:
        sock.settimeout(0.2)
        return sock.connect_ex(("127.0.0.1", port)) != 0


def write_env(path: pathlib.Path, values: dict[str, str]) -> None:
    path.write_text("".join(f"export {key}={shlex.quote(str(value))}\n" for key, value in values.items()), encoding="utf-8")
    path.chmod(0o600)


def start_process(run_dir: pathlib.Path, name: str, args: list[str], *, cwd: pathlib.Path,
                  env: dict[str, str]) -> subprocess.Popen:
    log = (run_dir / "logs" / f"{name}.log").open("ab")
    process = subprocess.Popen(args, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                               stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    log.close()
    (run_dir / f"{name}.pid").write_text(str(process.pid), encoding="ascii")
    return process


def find_command(name: str) -> str:
    import shutil
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"缺少本机命令：{name}")
    return path


def ai_python(run_dir: pathlib.Path) -> pathlib.Path:
    """选择具备AI依赖的基础解释器，再建立/校验项目venv，避免沿用shell激活的其他venv。"""
    requirements = ROOT / "simulation" / "requirements-ai.txt"
    version_probe = "import importlib.metadata as m,sys; expected=" + repr({
        "fastapi": "0.135.1", "uvicorn": "0.41.0", "numpy": "2.4.3",
        "requests": "2.32.5", "pydantic": "2.12.5",
    }) + "; sys.exit(0 if all(m.version(k)==v for k,v in expected.items()) else 1)"
    candidates = []
    preferred = os.environ.get("SHUTTLE_V13_AI_BASE_PYTHON")
    if preferred:
        candidates.append(preferred)
    candidates.extend([
        "/opt/homebrew/bin/python3",
        str(pathlib.Path(sys.base_prefix) / "bin" / "python3"),
        str(pathlib.Path(sys.base_prefix) / "bin" / "python3.14"),
        find_command("python3"),
        sys.executable,
    ])
    base_python = None
    for candidate in dict.fromkeys(candidates):
        if not pathlib.Path(candidate).is_file():
            continue
        probe = subprocess.run([candidate, "-c", "import fastapi, uvicorn, numpy, requests"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if probe.returncode == 0:
            base_python = candidate
            break
    if base_python is None:
        raise SystemExit("未找到含fastapi/uvicorn/numpy/requests的Python解释器；请按simulation/requirements-ai.txt准备依赖。")
    venv_python = AI_VENV / "bin" / "python"
    if not venv_python.is_file():
        subprocess.run([base_python, "-m", "venv", "--system-site-packages", str(AI_VENV)], check=True)
    check = subprocess.run([str(venv_python), "-c", version_probe],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if check.returncode != 0:
        log_path = run_dir / "logs" / "python-dependencies.log"
        try:
            with log_path.open("ab") as out:
                subprocess.run([str(venv_python), "-m", "pip", "install", "--disable-pip-version-check",
                                "--no-input", "-r", str(requirements)], stdout=out,
                               stderr=subprocess.STDOUT, check=True)
        except subprocess.CalledProcessError as error:
            raise SystemExit(f"项目AI虚拟环境依赖安装失败；日志：{log_path}") from error
        check = subprocess.run([str(venv_python), "-c", version_probe],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if check.returncode != 0:
            raise SystemExit(f"AI虚拟环境仍缺少固定版本依赖；日志：{log_path}")
    print(f"FastAPI解释器：{venv_python}（项目隔离venv，依赖版本按simulation/requirements-ai.txt校验）。")
    return venv_python


def wait_http(url: str, *, seconds: int, require_body: bytes = b"") -> bool:
    import urllib.error
    import urllib.request
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                body = response.read()
                if response.status == 200 and require_body in body:
                    return True
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.5)
    return False


def wait_for_seeded_admin(env: dict[str, str], seconds: float = 15) -> bool:
    import http.cookiejar
    import json
    import urllib.error
    import urllib.request

    # JDBC readiness can precede the BootstrapAdminRunner on a new test schema.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    payload = json.dumps({"username": env["SHUTTLE_TEST_ADMIN_USERNAME"],
                          "password": env["SHUTTLE_TEST_ADMIN_PASSWORD"]}).encode()
    request = urllib.request.Request(f"http://127.0.0.1:{JAVA_PORT}/api/admin/login",
                                    data=payload, headers={"Content-Type": "application/json"})
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with opener.open(request, timeout=2) as response:
                result = json.load(response)
                if result.get("code") == 200:
                    csrf = result["data"]["csrfToken"]
                    logout = urllib.request.Request(f"http://127.0.0.1:{JAVA_PORT}/api/auth/logout",
                                                     data=b"{}", headers={"Content-Type": "application/json",
                                                                         "X-Shuttle-CSRF": csrf})
                    with opener.open(logout, timeout=2):
                        return True
        except (urllib.error.URLError, TimeoutError, ValueError):
            pass
        time.sleep(0.5)
    return False


def stop_process(run_dir: pathlib.Path, name: str) -> None:
    path = run_dir / f"{name}.pid"
    if not path.exists():
        return
    try:
        pid = int(path.read_text(encoding="ascii").strip())
        os.killpg(pid, signal.SIGTERM)
        for _ in range(170 if name == "operation" else 30):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.1)
        else:
            try: os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError: pass
    except (ValueError, ProcessLookupError, PermissionError):
        pass
    finally:
        try: path.unlink()
        except FileNotFoundError: pass


def launch_services(run_dir: pathlib.Path, env: dict[str, str], ai_executable: pathlib.Path) -> None:
    try:
        redis_args = [find_command("redis-server"), "--bind", "127.0.0.1", "--port", str(REDIS_PORT),
                      "--save", "", "--appendonly", "no", "--dir", str(run_dir), "--daemonize", "no"]
        start_process(run_dir, "redis", redis_args, cwd=ROOT, env=env)
        redis_ready = False
        for _ in range(30):
            proc = subprocess.run([find_command("redis-cli"), "-h", "127.0.0.1", "-p", str(REDIS_PORT), "PING"],
                                  capture_output=True, text=True)
            if proc.returncode == 0 and proc.stdout.strip() == "PONG":
                redis_ready = True
                break
            time.sleep(0.2)
        if not redis_ready:
            raise RuntimeError(f"隔离Redis未就绪；日志：{run_dir / 'logs/redis.log'}")

        with (run_dir / "logs/build.log").open("ab") as out:
            subprocess.run([find_command("mvn"), "-f", str(ROOT / "backend/pom.xml"),
                            "-s", str(ROOT / "maven_settings.xml"), "-DskipTests", "package"],
                           cwd=ROOT, env=env, stdout=out, stderr=subprocess.STDOUT, check=True)

        ai_env = env.copy()
        ai_env.update({"AI_BIND_HOST": "127.0.0.1", "AI_PORT": str(AI_PORT),
                       "SHUTTLE_BACKEND_API_URL": f"http://127.0.0.1:{JAVA_PORT}/api"})
        start_process(run_dir, "ai", [str(ai_executable), "-m", "uvicorn", "main:app", "--host", "127.0.0.1",
                                       "--port", str(AI_PORT)], cwd=ROOT / "ai-service", env=ai_env)
        lab = env.get("SHUTTLE_EXPERIMENT_ENABLED") == "true"
        module, port = ("experiment_server", EXPERIMENT_PORT) if lab else ("operation_server", OPERATION_PORT)
        worker_env = env if not lab else {
            "PATH": env.get("PATH", os.defpath),
            "SHUTTLE_EXPERIMENT_TOKEN": env["SHUTTLE_EXPERIMENT_TOKEN"],
            "SHUTTLE_EXPERIMENT_DIR": str(run_dir / "experiments"),
            "SHUTTLE_EXPERIMENT_CATALOG": str(ROOT / "backend/src/main/resources/sql/init.sql"),
        }
        operation = start_process(run_dir, "operation", [str(ai_executable), "-m", "simulation."+module],
                                  cwd=ROOT, env=worker_env)
        for _ in range(30):
            if operation.poll() is not None:
                raise RuntimeError(f"运营进程启动失败；日志：{run_dir / 'logs/operation.log'}")
            if not port_available(port):
                break
            time.sleep(0.1)
        else:
            raise RuntimeError(f"运营进程未监听；日志：{run_dir / 'logs/operation.log'}")
        start_process(run_dir, "java", [find_command("java"), "-Xms128m", "-Xmx512m", "-jar",
                                        str(ROOT / "backend/target/shuttle-1.0.0.jar"),
                                        "--server.address=127.0.0.1", f"--server.port={JAVA_PORT}"], cwd=ROOT, env=env)
        if not wait_http(f"http://127.0.0.1:{AI_PORT}/health", seconds=30, require_body=b"ok"):
            raise RuntimeError(f"FastAPI健康检查失败；日志：{run_dir / 'logs/ai.log'}")
        if not wait_http(f"http://127.0.0.1:{JAVA_PORT}/api/health/ready", seconds=120, require_body=b"READY"):
            raise RuntimeError(f"Java健康检查失败；日志：{run_dir / 'logs/java.log'}")
        if not wait_for_seeded_admin(env):
            raise RuntimeError(f"仿真管理员未成功初始化；日志：{run_dir / 'logs/java.log'}")
    except Exception:
        for name in ("operation", "java", "ai", "redis"):
            stop_process(run_dir, name)
        raise


def env_overrides(path, env):
    existing = {line[7:].split("=", 1)[0] for line in path.read_text().splitlines() if line.startswith("export ")}
    return {key: env[key] for key in existing | {"SHUTTLE_EXPERIMENT_ENABLED", "SHUTTLE_EXPERIMENT_TOKEN",
            "SHUTTLE_CUSUM_SCHEDULER_ENABLED", "SHUTTLE_DISPATCH_COURSE_PREPOSITION_ENABLED",
            "SHUTTLE_SIMULATION_ENABLED", "SHUTTLE_OPERATION_ENABLED"}}


def start(run_dir: pathlib.Path, mysql_root_cnf: pathlib.Path, lab=False) -> int:
    if run_dir.exists():
        raise SystemExit(f"隔离目录已存在：{run_dir}。为防覆盖，请使用新的--run-dir或先检查旧实例。")
    if not mysql_root_cnf.is_file() or (mysql_root_cnf.stat().st_mode & 0o077):
        raise SystemExit("需要权限0600的本机独立测试MySQL凭据文件；不要使用VPS或生产库账号。")
    if port_available(MYSQL_TEST_PORT):
        raise SystemExit("本机独立MySQL测试端口3308不可用；停止，不会连接3306或其他实例。")
    for port in (REDIS_PORT, AI_PORT, JAVA_PORT, EXPERIMENT_PORT if lab else OPERATION_PORT):
        if not port_available(port):
            raise SystemExit(f"本机端口{port}已被占用；停止，不会连接或修改其服务。")
    for name in ("mysql", "redis-server", "redis-cli", "mvn", "java"):
        find_command(name)
    run_dir.mkdir(parents=True, mode=0o700)
    (run_dir / "logs").mkdir(mode=0o700)
    ai_executable = ai_python(run_dir)

    db_name = "shuttle_v13_sim_" + secrets.token_hex(4)
    base_env = os.environ.copy()
    base_env.update({"SHUTTLE_V13_RUN_DIR": str(run_dir), "SHUTTLE_V13_DB_NAME": db_name,
                     "SHUTTLE_V13_MYSQL_ROOT_CNF": str(mysql_root_cnf)})
    subprocess.run([sys.executable, str(ROOT / "simulation/bootstrap_test_db.py")],
                   cwd=ROOT, env=base_env, check=True)
    env = os.environ.copy()
    for line in (run_dir / "app.env").read_text(encoding="utf-8").splitlines():
        if line.startswith("export ") and "=" in line:
            key, raw = line[7:].split("=", 1)
            env[key] = shlex.split(raw)[0] if raw else ""
    if lab:
        env.update({"SERVER_PORT": str(JAVA_PORT), "SHUTTLE_REDIS_PORT": str(REDIS_PORT),
                    "SHUTTLE_AI_SERVICE_URL": f"http://127.0.0.1:{AI_PORT}",
                    "SHUTTLE_CORS_ALLOWED_ORIGINS": f"http://127.0.0.1:{JAVA_PORT}",
                    "SHUTTLE_SIMULATION_ENABLED": "false", "SHUTTLE_OPERATION_ENABLED": "false",
                    "SHUTTLE_EXPERIMENT_ENABLED": "true", "SHUTTLE_CUSUM_SCHEDULER_ENABLED": "true",
                    "SHUTTLE_DISPATCH_COURSE_PREPOSITION_ENABLED": "true",
                    "SHUTTLE_EXPERIMENT_TOKEN": secrets.token_urlsafe(32)})
        write_env(run_dir / "app.env", env_overrides(run_dir / "app.env", env))
    launch_services(run_dir, env, ai_executable)
    print(f"本机隔离仿真环境已启动：{run_dir}")
    print(f"新测试库={db_name}；Java=127.0.0.1:{JAVA_PORT}；AI=127.0.0.1:{AI_PORT}；独立测试MySQL=127.0.0.1:{MYSQL_TEST_PORT}；仿真Redis=127.0.0.1:{REDIS_PORT}")
    print("app.env权限0600；不会连接或修改VPS、业务数据库或已有Redis。")
    return 0


def refresh(run_dir: pathlib.Path) -> int:
    """重建隔离Java/AI/Redis进程，但复用现有shuttle_v13_sim_*测试schema。"""
    env_file = run_dir / "app.env"
    if not env_file.is_file() or not (run_dir / "logs").is_dir():
        raise SystemExit(f"找不到已启动的本机仿真环境：{run_dir}。先运行runtime.py start。")
    env = os.environ.copy()
    values = {}
    for line in env_file.read_text(encoding="utf-8").splitlines():
        if line.startswith("export ") and "=" in line:
            key, raw = line[7:].split("=", 1)
            values[key] = shlex.split(raw)[0] if raw else ""
    db_name = values.get("SHUTTLE_TEST_DB_NAME", "")
    db_url = values.get("SHUTTLE_DB_URL", "")
    url = db_url[5:] if db_url.startswith("jdbc:") else db_url
    import urllib.parse
    parsed = urllib.parse.urlparse(url)
    if (not db_name.startswith("shuttle_v13_sim_") or parsed.hostname != "127.0.0.1"
            or parsed.port != MYSQL_TEST_PORT or parsed.path.lstrip("/") != db_name):
        raise SystemExit("隔离库校验失败；停止，未重启应用。")
    if values.get("SHUTTLE_EXPERIMENT_ENABLED") == "true":
        raise SystemExit("使用 --lab 启动的环境不允许切换到旧 HTTP 仿真模式；请建立新测试目录。")
    ai_executable = ai_python(run_dir)
    values.update({"SHUTTLE_SIMULATION_ENABLED": "true",
                   "SHUTTLE_OPERATION_ENABLED": "true",
                   "SHUTTLE_OPERATION_TOKEN": values.get("SHUTTLE_OPERATION_TOKEN") or secrets.token_urlsafe(32),
                   "SHUTTLE_CUSUM_SCHEDULER_ENABLED": "false",
                   "SHUTTLE_DISPATCH_COURSE_PREPOSITION_ENABLED": "false"})
    write_env(env_file, values)
    for name in ("operation", "java", "ai", "redis"):
        stop_process(run_dir, name)
    for port in (REDIS_PORT, AI_PORT, JAVA_PORT, OPERATION_PORT):
        if not port_available(port):
            raise SystemExit(f"停止隔离应用后本机端口{port}仍被占用；停止，未复用外部服务。")
    env.update(values)
    launch_services(run_dir, env, ai_executable)
    print(f"本机仿真应用已按当前源码重建并重启；测试库{db_name}未重置，CUSUM按虚拟窗口手动推进。")
    return 0


def stop(run_dir: pathlib.Path) -> int:
    if not run_dir.exists():
        print(f"隔离目录不存在：{run_dir}")
        return 0
    for name in ("operation", "java", "ai", "redis"):
        stop_process(run_dir, name)
    env_file = run_dir / "app.env"
    if env_file.exists():
        env_file.unlink()
    print(f"本机隔离运营进程、Java、AI 与 Redis 已停止；临时app.env凭据已删除，测试库和日志保留在{run_dir}，其他MySQL实例不受影响。")
    return 0


def main() -> int:
    global REDIS_PORT, AI_PORT, JAVA_PORT
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("start", "refresh", "stop"))
    parser.add_argument("--run-dir", type=pathlib.Path, default=DEFAULT_RUN_DIR)
    parser.add_argument("--mysql-root-cnf", type=pathlib.Path,
                        default=ROOT.parent / "shuttleflow" / ".runtime" / "mysql-root.cnf")
    parser.add_argument("--lab", action="store_true", help="用内存实验替换旧 HTTP 仿真")
    args = parser.parse_args()
    if args.lab:
        REDIS_PORT, AI_PORT, JAVA_PORT = 16389, 18010, 18090
    if args.action == "start": return start(args.run_dir, args.mysql_root_cnf, args.lab)
    if args.action == "refresh": return refresh(args.run_dir)
    return stop(args.run_dir)


if __name__ == "__main__":
    sys.exit(main())
