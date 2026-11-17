"""在独立测试数据库运行10/30/50/100并发HTTP闭环；不会清库或删除记录。"""
import concurrent.futures
import http.cookiejar
import json
import os
import platform
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("SHUTTLE_TEST_API", "http://127.0.0.1:8080/api")
ADMIN_USER = os.environ["SHUTTLE_TEST_ADMIN_USERNAME"]
ADMIN_PASSWORD = os.environ["SHUTTLE_TEST_ADMIN_PASSWORD"]
DRIVER_PHONE = os.environ["SHUTTLE_TEST_DRIVER_PHONE"]
DRIVER_PASSWORD = os.environ["SHUTTLE_TEST_DRIVER_PASSWORD"]
LEVELS = [int(value) for value in os.environ.get("SHUTTLE_LOAD_LEVELS", "10,30,50,100").split(",") if value.strip()]
if not LEVELS or any(level not in (10, 30, 50, 100) for level in LEVELS):
    raise ValueError("SHUTTLE_LOAD_LEVELS只能包含10、30、50、100")


class HttpClient:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def call(self, method, path, data=None, extra_headers=None):
        headers = dict(extra_headers or {})
        body = None
        if data is not None:
            body = json.dumps(data).encode()
            headers.setdefault("Content-Type", "application/json")
        if self.csrf:
            headers.setdefault("X-Shuttle-CSRF", self.csrf)
        request = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
        started = time.perf_counter()
        try:
            with self.opener.open(request, timeout=20) as response:
                payload = json.loads(response.read() or b"{}")
                return response.status, payload, (time.perf_counter() - started) * 1000
        except urllib.error.HTTPError as error:
            payload = json.loads(error.read() or b"{}")
            return error.code, payload, (time.perf_counter() - started) * 1000

    def get(self, path):
        return self.call("GET", path)[1]

    def post(self, path, body, headers=None):
        return self.call("POST", path, body, headers)

    def login(self, role):
        endpoint, payload = ("/admin/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD}) if role == "ADMIN" else (
            "/driver/login", {"phone": DRIVER_PHONE, "password": DRIVER_PASSWORD})
        status, body, _ = self.call("POST", endpoint, payload)
        if status != 200 or body.get("code") != 200:
            raise RuntimeError(f"{role}登录失败：HTTP {status} {body.get('msg')}")
        self.csrf = body["data"]["csrfToken"]
        return body["data"]

    def cookie_header(self):
        return "; ".join(cookie.name + "=" + cookie.value for cookie in self.jar)


class ProcessSampler:
    def __init__(self):
        self.stop_event = threading.Event()
        self.max_cpu = 0.0
        self.max_rss_kb = 0
        self.components = {}
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self): self.thread.start()
    def stop(self):
        self.stop_event.set()
        self.thread.join(timeout=3)

    def _run(self):
        while not self.stop_event.is_set():
            try:
                output = subprocess.check_output(["ps", "-Ao", "%cpu=,rss=,comm="], text=True, timeout=2)
                cpu = 0.0
                rss = 0
                sample = {name: {"cpu_pct": 0.0, "rss_kb": 0} for name in ("java", "mysql", "redis", "ai")}
                for line in output.splitlines():
                    parts = line.split()
                    if len(parts) < 3: continue
                    command = parts[-1].lower()
                    component = None
                    if "java" in command: component = "java"
                    elif "mysqld" in command: component = "mysql"
                    elif "redis-server" in command: component = "redis"
                    elif "python" in command or "uvicorn" in command: component = "ai"
                    if component:
                        process_cpu = float(parts[0])
                        process_rss = int(parts[1])
                        cpu += process_cpu
                        rss += process_rss
                        sample[component]["cpu_pct"] += process_cpu
                        sample[component]["rss_kb"] += process_rss
                self.max_cpu = max(self.max_cpu, cpu)
                self.max_rss_kb = max(self.max_rss_kb, rss)
                for name, values in sample.items():
                    peak = self.components.setdefault(name, {"cpu_pct": 0.0, "rss_kb": 0})
                    peak["cpu_pct"] = max(peak["cpu_pct"], values["cpu_pct"])
                    peak["rss_kb"] = max(peak["rss_kb"], values["rss_kb"])
            except Exception:
                pass
            self.stop_event.wait(0.25)


def percentile(values, percent):
    ordered = sorted(values)
    if not ordered: return 0
    index = (len(ordered) - 1) * percent
    low = int(index)
    high = min(low + 1, len(ordered) - 1)
    return round(ordered[low] + (ordered[high] - ordered[low]) * (index - low), 2)


def run_batch(label, concurrency, workers, expected_codes=(200,)):
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(workers, range(concurrency)))
    wall = max(time.perf_counter() - started, 0.000001)
    latencies = [result["latency_ms"] for result in results]
    codes = [result["code"] for result in results]
    success = sum(code == 200 for code in codes)
    expected_conflicts = sum(code == 409 for code in codes)
    unexpected = sum(code not in expected_codes for code in codes)
    return {
        "stage": label,
        "concurrency": concurrency,
        "requests": len(results),
        "successful": success,
        "expected_conflict_409": expected_conflicts,
        "unexpected_errors": unexpected,
        "error_rate_pct": round(unexpected * 100 / max(len(results), 1), 2),
        "throughput_rps": round(len(results) / wall, 2),
        "p50_ms": percentile(latencies, 0.50),
        "p95_ms": percentile(latencies, 0.95),
        "p99_ms": percentile(latencies, 0.99),
        "latencies_ms": latencies,
        "codes": codes,
    }, results


def raw_call(method, path, data, headers):
    body = json.dumps(data).encode() if data is not None else None
    request = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.build_opener().open(request, timeout=20) as response:
            result = json.loads(response.read() or b"{}")
            return response.status, result, (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as error:
        result = json.loads(error.read() or b"{}")
        return error.code, result, (time.perf_counter() - started) * 1000


def json_code(result):
    return result[1].get("code", result[0])


def make_guest():
    client = HttpClient()
    session = client.get("/auth/session?guest=true")["data"]
    client.csrf = session["csrfToken"]
    return client


def queue_count(client, station_id, line_id, direction):
    rows = client.get("/passenger/queue")["data"]
    row = next((item for item in rows if item["stationId"] == station_id and item["lineId"] == line_id and item["direction"] == direction), None)
    return 0 if row is None else row["waitingCount"]


def service_status():
    mysql_result = {}
    cnf = os.environ.get("SHUTTLE_TEST_MYSQL_CNF", ".runtime/mysql-root.cnf")
    try:
        port = os.environ.get("SHUTTLE_TEST_MYSQL_PORT", "3308")
        output = subprocess.check_output([
            "mysql", f"--defaults-extra-file={cnf}", "--protocol=tcp", "--host=127.0.0.1",
            "--port", port, "--batch", "--skip-column-names", "-e",
            "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Slow_queries','Max_used_connections')"
        ], text=True, timeout=5)
        mysql_result = {line.split("\t", 1)[0]: int(line.split("\t", 1)[1]) for line in output.splitlines() if "\t" in line}
    except Exception as error:
        mysql_result = {"monitor_error": type(error).__name__}

    redis_result = {}
    redis_host = os.environ.get("SHUTTLE_REDIS_HOST", "127.0.0.1")
    redis_port = os.environ.get("SHUTTLE_REDIS_PORT", "6379")
    redis_password = os.environ.get("SHUTTLE_REDIS_PASSWORD", "")
    prefix = ["redis-cli", "-h", redis_host, "-p", redis_port, "--raw"]
    redis_env = os.environ.copy()
    if redis_password:
        redis_env["REDISCLI_AUTH"] = redis_password
    try:
        memory = subprocess.check_output(prefix + ["INFO", "memory"], text=True, timeout=3, env=redis_env)
        for line in memory.splitlines():
            if line.startswith(("used_memory_human:", "used_memory_peak_human:", "maxmemory_human:")):
                key, value = line.split(":", 1)
                redis_result[key] = value
        clients = subprocess.check_output(prefix + ["INFO", "clients"], text=True, timeout=3, env=redis_env)
        redis_result["connected_clients"] = int(clients.split("connected_clients:", 1)[1].splitlines()[0])
        redis_result["event_stream_length"] = int(subprocess.check_output(prefix + ["XLEN", "shuttle:events"], text=True, timeout=3, env=redis_env).strip())
    except Exception as error:
        redis_result = {"monitor_error": type(error).__name__}
    return {"mysql": mysql_result, "redis": redis_result}


def complete_vehicle_tasks(admin, driver, vehicle_id):
    tasks = admin.get("/admin/tasks?scope=all")["data"]
    for task in tasks:
        if task.get("vehicleId") != vehicle_id:
            continue
        if task.get("status") == "assigned":
            accepted = driver.post("/driver/task/accept", {"taskId": task["id"], "vehicleId": vehicle_id})
            if json_code(accepted) != 200:
                raise AssertionError(f"隔离库任务无法接单：{task['id']} {accepted[1].get('msg')}")
            completed = driver.post("/driver/task/complete", {"taskId": task["id"]})
            if json_code(completed) != 200:
                raise AssertionError(f"隔离库任务无法完成：{task['id']} {completed[1].get('msg')}")
        elif task.get("status") == "executing":
            completed = driver.post("/driver/task/complete", {"taskId": task["id"]})
            if json_code(completed) != 200:
                raise AssertionError(f"隔离库任务无法完成：{task['id']} {completed[1].get('msg')}")


def main():
    started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    services_before = service_status()
    concurrency_summary = []
    sampler = ProcessSampler()
    sampler.start()
    admin = HttpClient()
    admin.login("ADMIN")
    driver = HttpClient()
    driver_data = driver.login("DRIVER")
    vehicle_id = driver_data["vehicle"]["id"]
    line_id = driver_data["vehicle"]["lineId"]
    station_id = 1
    direction = "go"
    all_task_nos = set()

    # 上一轮功能回归可能留下运行中的任务。只在隔离测试库中按合法状态机完成本测试车辆的旧任务。
    complete_vehicle_tasks(admin, driver, vehicle_id)

    set_running = admin.post("/admin/vehicle/set-running", {"vehicleId": vehicle_id, "running": True})
    if json_code(set_running) != 200:
        raise RuntimeError("无法将测试车辆设为运行状态")

    for level in LEVELS:
        print(f"开始 {level} 并发阶段")
        read_stage, _ = run_batch("只读线路", level, lambda _: _get_metric("/lines"))
        concurrency_summary.append(read_stage)

        guests = [make_guest() for _ in range(level)]
        baseline = queue_count(guests[0], station_id, line_id, direction)

        def do_checkin(index):
            client = guests[index]
            status, body, latency = client.call("POST", "/passenger/checkin", {
                "stationId": station_id, "lineId": line_id, "direction": direction
            })
            return {"code": body.get("code", status), "latency_ms": latency, "request_id": body.get("data", {}).get("request", {}).get("id")}

        checkin_stage, checkin_results = run_batch("乘客签到", level, do_checkin)
        if checkin_stage["unexpected_errors"]:
            raise AssertionError(f"{level}并发签到出现错误：{checkin_stage['codes']}")
        ids = [result.get("request_id") for result in checkin_results]
        if any(request_id is None for request_id in ids) or len(set(ids)) != level:
            raise AssertionError(f"{level}个独立游客签到未产生唯一请求编号")
        counted = queue_count(guests[0], station_id, line_id, direction)
        if counted != baseline + level:
            raise AssertionError(f"签到队列不守恒：期望{baseline + level}，实际{counted}")
        concurrency_summary.append(checkin_stage)

        duplicate_cancel_headers = {"Cookie": guests[0].cookie_header(),
                                    "X-Shuttle-CSRF": guests[0].csrf,
                                    "Content-Type": "application/json"}
        def cancel_same_request(_):
            status, body, latency = raw_call("POST", "/passenger/cancel", {"requestId": ids[0]}, duplicate_cancel_headers)
            return {"code": body.get("code", status), "latency_ms": latency}
        duplicate_cancel_stage, _ = run_batch("同请求并发重复取消", 2, cancel_same_request, expected_codes=(200, 409))
        if duplicate_cancel_stage["successful"] != 1 or duplicate_cancel_stage["expected_conflict_409"] != 1 or duplicate_cancel_stage["unexpected_errors"]:
            raise AssertionError(f"重复取消状态转换不原子：{duplicate_cancel_stage['codes']}")
        concurrency_summary.append(duplicate_cancel_stage)

        def do_cancel(index):
            status, body, latency = guests[index].call("POST", "/passenger/cancel", {"requestId": ids[index]})
            return {"code": body.get("code", status), "latency_ms": latency}

        cancel_stage, _ = run_batch("乘客取消", level - 1, lambda index: do_cancel(index + 1))
        if cancel_stage["unexpected_errors"]:
            raise AssertionError(f"{level}并发取消出现错误：{cancel_stage['codes']}")
        restored = queue_count(guests[0], station_id, line_id, direction)
        if restored != baseline:
            raise AssertionError(f"取消后队列不守恒：期望{baseline}，实际{restored}")
        concurrency_summary.append(cancel_stage)
        complete_vehicle_tasks(admin, driver, vehicle_id)

        if level == 100:
            # 同一会话并发重试同一签到，DB唯一键必须把它们合并为一个业务请求。
            duplicate_guest = make_guest()
            duplicate_before = queue_count(duplicate_guest, station_id, line_id, direction)
            duplicate_headers = {"Cookie": duplicate_guest.cookie_header(),
                                 "X-Shuttle-CSRF": duplicate_guest.csrf,
                                 "Content-Type": "application/json"}
            def do_duplicate_checkin(_):
                status, body, latency = raw_call("POST", "/passenger/checkin", {
                    "stationId": station_id, "lineId": line_id, "direction": direction
                }, duplicate_headers)
                request_id = body.get("data", {}).get("request", {}).get("id")
                return {"code": body.get("code", status), "latency_ms": latency, "request_id": request_id}
            duplicate_stage, duplicate_results = run_batch("同会话并发重复签到", level, do_duplicate_checkin)
            duplicate_ids = {item.get("request_id") for item in duplicate_results}
            if duplicate_stage["unexpected_errors"] or len(duplicate_ids) != 1 or None in duplicate_ids:
                raise AssertionError(f"同会话并发重复签到未幂等：{duplicate_stage['codes']}")
            duplicate_count = queue_count(duplicate_guest, station_id, line_id, direction)
            if duplicate_count != duplicate_before + 1:
                raise AssertionError(f"同会话重复签到队列增量错误：{duplicate_before}→{duplicate_count}")
            concurrency_summary.append(duplicate_stage)
            duplicate_cancel = duplicate_guest.post("/passenger/cancel", {"requestId": next(iter(duplicate_ids))})
            if json_code(duplicate_cancel) != 200 or queue_count(duplicate_guest, station_id, line_id, direction) != duplicate_before:
                raise AssertionError("并发重复签到取消后队列未回到基线")
            complete_vehicle_tasks(admin, driver, vehicle_id)

        driver_cookie = driver.cookie_header()
        driver_headers = {"Cookie": driver_cookie, "X-Shuttle-CSRF": driver.csrf}
        def do_report(_):
            headers = dict(driver_headers)
            headers["Idempotency-Key"] = os.urandom(16).hex()
            status, body, latency = driver.call("POST", "/driver/depart", {"vehicleId": vehicle_id}, headers)
            return {"code": body.get("code", status), "latency_ms": latency}
        report_stage, _ = run_batch("司机报站", level, do_report)
        if report_stage["unexpected_errors"]:
            raise AssertionError(f"{level}并发司机报站出现错误：{report_stage['codes']}")
        concurrency_summary.append(report_stage)

        # 司机上客超过额定容量必须被拒绝且不改人数。
        current_vehicle = driver.get("/driver/vehicle/" + str(vehicle_id))["data"]
        over = driver.post("/driver/board", {"vehicleId": vehicle_id, "stationId": station_id,
                                              "lineId": line_id, "direction": direction,
                                              "count": current_vehicle["capacity"] - current_vehicle["currentLoad"] + 1})
        if json_code(over) != 409:
            raise AssertionError(f"超容量上客应拒绝，实际返回 {over}")

        # 并发创建任务，确认任务编号不冲突；仅一辆测试车可分配，其余建议应顺延升级。
        admin_headers = {"Cookie": admin.cookie_header(), "X-Shuttle-CSRF": admin.csrf,
                         "Content-Type": "application/json"}
        def create_task(index):
            payload = {"lineId": line_id, "stationId": station_id, "direction": direction,
                       "priority": 5, "suggestion": f"隔离库并发验收-{level}-{index}"}
            status, body, latency = raw_call("POST", "/admin/task/create", payload, admin_headers)
            task_data = body.get("data") or {}
            return {"code": body.get("code", status), "latency_ms": latency,
                    "task_no": task_data.get("taskNo"), "task_id": task_data.get("id"),
                    "status": task_data.get("status"), "vehicle_id": task_data.get("vehicleId")}
        create_stage, created_tasks = run_batch("调度任务并发创建", level, create_task)
        task_nos = [result.get("task_no") for result in created_tasks]
        if create_stage["unexpected_errors"] or None in task_nos or len(set(task_nos)) != level:
            raise AssertionError(f"并发任务创建失败或任务编号冲突：{task_nos}")
        task = next((result for result in created_tasks if result["status"] == "assigned" and result["vehicle_id"] == vehicle_id), None)
        if task is None:
            raise AssertionError(f"任务没有指派给测试车辆：{created_tasks}")
        if any(number in all_task_nos for number in task_nos):
            raise AssertionError("跨并发批次出现重复taskNo")
        all_task_nos.update(task_nos)
        concurrency_summary.append(create_stage)

        def do_accept(_):
            status, body, latency = raw_call("POST", "/driver/task/accept", {
                "taskId": task["task_id"], "vehicleId": vehicle_id
            }, {"Cookie": driver_cookie, "X-Shuttle-CSRF": driver.csrf,
                "Content-Type": "application/json"})
            return {"code": body.get("code", status), "latency_ms": latency}
        task_stage, task_results = run_batch("调度任务并发接单", level, do_accept, expected_codes=(200, 409))
        if task_stage["successful"] != 1 or task_stage["expected_conflict_409"] != level - 1 or task_stage["unexpected_errors"]:
            raise AssertionError(f"并发接单状态不满足唯一迁移：{task_stage['codes']}")
        complete = driver.post("/driver/task/complete", {"taskId": task["task_id"]})
        if json_code(complete) != 200:
            raise AssertionError(f"执行中的任务无法完成：{complete}")
        concurrency_summary.append(task_stage)

    sampler.stop()
    services_after = service_status()
    host_memory_bytes = None
    try:
        if platform.system() == "Darwin":
            host_memory_bytes = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True).strip())
        elif platform.system() == "Linux":
            host_memory_bytes = int(subprocess.check_output(["awk", "/MemTotal/ {print $2 * 1024}", "/proc/meminfo"], text=True).strip())
    except Exception:
        pass
    report = {
        "test_database": os.environ.get("SHUTTLE_TEST_DB_NAME", "由SHUTTLE_TEST_DB_NAME标注"),
        "started_at_utc": started_at,
        "host": {"os": platform.platform(), "cpu_logical_count": os.cpu_count(), "memory_bytes": host_memory_bytes},
        "levels": LEVELS,
        "host_process_cpu_peak_pct_sum": round(sampler.max_cpu, 2),
        "host_process_rss_peak_mb_sum": round(sampler.max_rss_kb / 1024, 2),
        "process_peak_metrics": {name: {"cpu_pct": round(values["cpu_pct"], 2), "rss_mb": round(values["rss_kb"] / 1024, 2)} for name, values in sampler.components.items()},
        "services_before": services_before,
        "services_after": services_after,
        "stages": concurrency_summary,
        "consistency": "所有签到都得到独立请求ID；每批取消后队列回到基线；超容量上客拒绝；每个并发接单批次仅一次成功，其余为预期409；任务随后完成。"
    }
    out = Path("output/v12-load-results.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"压力验收通过，报告：{out}")
    for row in concurrency_summary:
        print(f"{row['stage']} C={row['concurrency']}: RPS={row['throughput_rps']} P50/P95/P99={row['p50_ms']}/{row['p95_ms']}/{row['p99_ms']}ms 错误率={row['error_rate_pct']}%")
    print(f"峰值CPU累计={report['host_process_cpu_peak_pct_sum']}% RSS累计={report['host_process_rss_peak_mb_sum']}MB")


def _get_metric(path):
    status, body, latency = HttpClient().call("GET", path)
    return {"code": body.get("code", status), "latency_ms": latency}


if __name__ == "__main__":
    main()
