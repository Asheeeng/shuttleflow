"""管理员网页的回环实验进程；没有生产服务连接配置。"""
import hmac
import json
import os
import re
import signal
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from simulation.experiment import Experiment, catalog
from simulation.operation_config import ACTIVE, configuration, encode, save


class Lab:
    def __init__(self, routes, courses, archive):
        self.routes, self.courses, self.archive = routes, courses, archive
        self.lock = threading.Lock()
        self.run = self.thread = None

    def state(self, run_id=None):
        if run_id:
            if not re.fullmatch("[0-9a-f]{12}", run_id):
                raise ValueError("无效运行编号")
            file = self.archive/(run_id+".json")
            if not file.is_file():
                raise ValueError("历史实验不存在")
            return json.loads(file.read_text())
        saved = self.archive/"config.json"
        with self.lock:
            history = [json.loads(p.read_text()) for p in self.summaries()[:25]]
            current = self.run.snapshot if self.run else {"phase":"idle"}
            return {**current, "history":history, "enabled":True, "isolatedExperiment":True,
                    "savedConfig":json.loads(saved.read_text()) if saved.is_file() else None}

    def summaries(self):
        return sorted(self.archive.glob("*.summary.json"), key=lambda p:p.stat().st_mtime, reverse=True)

    def execute(self, run):
        try:
            run.run()
        finally:
            with self.lock:
                for entry in self.summaries()[25:]:
                    (entry.parent/(entry.name.split(".")[0]+".json")).unlink(missing_ok=True)
                    entry.unlink(missing_ok=True)

    def control(self, body):
        action = body.get("action")
        with self.lock:
            if action in ("save","start"):
                cfg = configuration(body.get("config") or {})
                if any(g["manual"] for g in cfg["fleet"]):
                    raise ValueError("算法实验只使用内存车辆，不创建真人司机账号")
                cfg["pattern"] = "auto"
                if action == "save":
                    save(self.archive/"config.json",cfg)
                    return cfg
                if self.thread and self.thread.is_alive():
                    raise ValueError("已有实验正在运行")
                self.run = Experiment(cfg,self.routes,self.courses,self.archive)
                self.thread = threading.Thread(target=self.execute,args=(self.run,),name="experiment")
                self.thread.start()
                return {"runId":self.run.id}
            if action == "reset":
                if self.thread and self.thread.is_alive():
                    raise ValueError("请先停止当前实验")
                self.run = None
                return {"phase":"idle"}
            run = self.run
            if not run:
                raise ValueError("当前没有实验")
            with run.cv:
                if action == "pause" and run.phase == "running":
                    run.phase = "paused"
                elif action == "resume" and run.phase == "paused":
                    run.phase = "running"
                elif action == "stop" and run.phase in ACTIVE:
                    run.phase = "stopping"
                elif action in ("fault","withdraw","restore") and run.phase in ("running","paused"):
                    vehicle = body.get("vehicleId")
                    if type(vehicle) is not int or vehicle not in run.fleet or len(run.commands) >= 16:
                        raise ValueError("车辆不在实验中或待处理操作已满")
                    run.commands.append((action,vehicle))
                else:
                    raise ValueError("当前状态不支持该操作")
                run.snapshot = {**run.snapshot,"phase":run.phase,"second":run.now}
                run.cv.notify_all()
                return {"phase":run.phase}


def main():
    token = os.environ["SHUTTLE_EXPERIMENT_TOKEN"].encode()
    if len(token) < 32:
        raise ValueError("实验服务令牌至少 32 位")
    lab = Lab(*catalog(os.environ["SHUTTLE_EXPERIMENT_CATALOG"]), Path(os.environ["SHUTTLE_EXPERIMENT_DIR"]))

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(5)
            super().setup()

        def log_message(self, *_):
            pass

        def request_lab(self, method):
            url = urlsplit(self.path)
            try:
                if not hmac.compare_digest(self.headers.get("X-Experiment-Token","").encode(),token):
                    code, data, msg = 403,None,"需要实验服务授权"
                elif method == "GET" and url.path == "/state":
                    code, data, msg = 200,lab.state(parse_qs(url.query).get("run",[None])[0]),"ok"
                elif method == "POST" and url.path == "/control":
                    length = int(self.headers.get("Content-Length","0"))
                    if not 0 < length <= 16384:
                        raise ValueError("配置正文过大或为空")
                    body = json.loads(self.rfile.read(length))
                    if not isinstance(body,dict):
                        raise ValueError("配置应为 JSON 对象")
                    code, data, msg = 200,lab.control(body),"ok"
                else:
                    code, data, msg = 404,None,"接口不存在"
            except (ValueError, TypeError, KeyError) as exc:
                code,data,msg = 400,None,str(exc)
            payload = encode(dict(code=code,data=data,msg=msg)).encode()
            self.send_response(200)
            self.send_header("Content-Type","application/json; charset=utf-8")
            self.send_header("Content-Length",str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            self.request_lab("GET")

        def do_POST(self):
            self.request_lab("POST")

    def shutdown(*_):
        if lab.run and lab.thread.is_alive():
            with lab.run.cv:
                lab.run.phase = "stopping"
                lab.run.cv.notify_all()
            lab.thread.join(timeout=10)
        raise SystemExit(0)

    signal.signal(signal.SIGTERM,shutdown)
    signal.signal(signal.SIGINT,shutdown)
    ThreadingHTTPServer(("127.0.0.1",18002),Handler).serve_forever()


if __name__ == "__main__":
    main()
