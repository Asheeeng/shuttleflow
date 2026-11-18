"""本机运营工作进程，供 Spring Boot 在角色校验后访问。"""
import hmac
import json
import os
import re
import signal
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from simulation.operation import ACTIVE, ARCHIVE, Run, configuration, encode, save

TOKEN = os.environ["SHUTTLE_OPERATION_TOKEN"]
CONTROL = threading.Lock()
RUN = None
THREAD = None


def history():
    if not ARCHIVE.is_dir():
        return []
    result = []
    for file in sorted(ARCHIVE.glob("????????????.summary.json"), key=lambda value: value.stat().st_mtime, reverse=True)[:25]:
        result.append(json.loads(file.read_text()))
    return result


def control(body):
    global RUN, THREAD
    action = body.get("action")
    with CONTROL:
        if action in {"start", "save"}:
            cfg = configuration(body.get("config") or {})
            if action == "save":
                save(ARCHIVE / "config.json", cfg)
                return cfg
            if THREAD and THREAD.is_alive():
                raise ValueError("已有运营任务，请先停止并等待清理完成")
            RUN = Run(cfg)
            THREAD = threading.Thread(target=RUN.run, name="operation")
            THREAD.start()
            return {"runId": RUN.id}
        if action == "reset":
            if THREAD and THREAD.is_alive():
                raise ValueError("请先停止运行并等待清理")
            RUN = None
            return {"phase": "idle"}
        if not RUN:
            raise ValueError("当前没有运行")
        if action == "credentials":
            car = RUN.fleet.get(body.get("vehicleId"), {})
            if RUN.phase not in ACTIVE or not car.get("credentials"):
                raise ValueError("当前车辆不是可登录的人工仿真车辆")
            return car["credentials"].copy()
        with RUN.cv:
            if action == "pause" and RUN.phase == "running":
                RUN.phase = "paused"
                RUN.snapshot = {**RUN.snapshot, "phase": "paused"}
            elif action == "resume" and RUN.phase == "paused":
                RUN.phase = "running"
                RUN.snapshot = {**RUN.snapshot, "phase": "running"}
            elif action == "stop" and RUN.phase in ACTIVE:
                RUN.phase = "stopping"
                RUN.snapshot = {**RUN.snapshot, "phase": "stopping"}
            elif action in {"fault", "withdraw", "restore"} and RUN.phase in {"running", "paused"}:
                vehicle = body.get("vehicleId")
                if vehicle not in RUN.fleet or len(RUN.commands) >= 16:
                    raise ValueError("车辆不存在或待处理操作已满")
                RUN.commands.append({"vehicleId": vehicle, "action": action})
            else:
                raise ValueError("当前运行状态不支持此操作")
            RUN.cv.notify_all()
        return {"phase": RUN.phase}


def projection(query):
    role = query.get("role", [""])[0]
    if role in {"ADMIN", "DISPATCHER"}:
        which = query.get("run", [None])[0]
        if which:
            if not re.fullmatch(r"[a-f0-9]{12}", which):
                raise ValueError("无效历史运行号")
            file = ARCHIVE / (which + ".json")
            if not file.is_file():
                raise ValueError("历史运行不存在")
            return json.loads(file.read_text())
        saved = ARCHIVE / "config.json"
        snapshot = RUN.snapshot if RUN else {"enabled": True, "phase": "idle"}
        visits = history()
        return {**snapshot, "history": visits,
                "savedConfig": json.loads(saved.read_text()) if saved.is_file() else None}
    snapshot = RUN.snapshot if RUN else {"enabled": True, "phase": "idle"}
    common = {key: snapshot.get(key) for key in ("enabled", "phase", "second", "routes", "queues", "runId")}
    common["start"] = snapshot.get("config", {}).get("start")
    cars = snapshot.get("vehicles", [])
    if role == "DRIVER":
        vid = query.get("vehicle", [""])[0]
        common["vehicles"] = [car for car in cars if str(car["id"]) == vid]
    else:
        allowed = {"id", "plate", "lineId", "direction", "phase", "index", "progress", "nextEta", "load", "capacity"}
        common["vehicles"] = [{k: v for k, v in car.items() if k in allowed} for car in cars]
    return common


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def handle_request(self, method):
        path = urlsplit(self.path)
        try:
            supplied = self.headers.get("X-Operation-Token", "")
            if not hmac.compare_digest(supplied.encode(), TOKEN.encode()):
                code, data, message = 403, None, "需要本机服务授权"
            elif method == "GET" and path.path == "/state":
                code, data, message = 200, projection(parse_qs(path.query)), "ok"
            elif method == "POST" and path.path == "/control":
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 16384:
                    raise ValueError("配置正文过大或为空")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("配置应为 JSON 对象")
                code, data, message = 200, control(body), "ok"
            else:
                code, data, message = 404, None, "接口不存在"
        except (ValueError, TypeError, KeyError) as exc:
            code, data, message = 400, None, str(exc)
        payload = encode({"code": code, "data": data, "msg": message}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")


def shutdown(*_):
    if RUN and THREAD and THREAD.is_alive():
        with RUN.cv:
            RUN.phase = "stopping"
            RUN.cv.notify_all()
        THREAD.join(timeout=15)
    raise SystemExit(0)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    ThreadingHTTPServer(("127.0.0.1", 18001), Handler).serve_forever()
