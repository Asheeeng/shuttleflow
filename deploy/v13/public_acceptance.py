"""HTTPS public API acceptance. Reads the isolated admin credential from a root-only file."""
import base64
from collections import Counter
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import time

import requests

HOST = "v13.shuttleflow-gzu.duckdns.org"
BASE = "https://" + HOST
ROOT = Path("/var/backups/shuttle-v13")
SIZE = 100


def websocket(session, edge):
    key = base64.b64encode(secrets.token_bytes(16)).decode()
    cookies = "; ".join(f"{c.name}={c.value}" for c in session.cookies)
    auth = base64.b64encode(f"v13gate:{edge}".encode()).decode()
    sock = ssl.create_default_context().wrap_socket(socket.create_connection((HOST, 443), timeout=8),
                                                    server_hostname=HOST)
    sock.sendall((
        "GET /ws HTTP/1.1\r\n" + f"Host: {HOST}\r\nOrigin: {BASE}\r\n"
        + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
        + f"Sec-WebSocket-Key: {key}\r\nAuthorization: Basic {auth}\r\nCookie: {cookies}\r\n\r\n"
    ).encode("ascii"))
    stream = sock.makefile("rb")
    if b" 101 " not in stream.readline(2048):
        sock.close()
        raise AssertionError("WSS 未返回 101")
    while stream.readline(2048) not in {b"\r\n", b""}:
        pass
    return sock, stream


def frame(stream):
    start = stream.read(2)
    if len(start) != 2 or start[0] & 15 != 1:
        raise AssertionError("WSS 未收到预期数据帧")
    length = start[1] & 127
    if length in (126, 127):
        length = int.from_bytes(stream.read(2 if length == 126 else 8), "big")
    if length > 65536:
        raise AssertionError("WSS 帧过大")
    return json.loads(stream.read(length))


def main():
    env = dict(line.rstrip().split("=", 1) for line in Path("/etc/shuttle-v13/app.env").read_text().splitlines())
    edge = os.environ["SHUTTLE_V13_ACCESS_PASSWORD"]
    assert requests.get(BASE + "/", timeout=8).status_code == 401
    assert requests.get("http://" + HOST + "/login.html", allow_redirects=False, timeout=8).status_code == 308
    with requests.Session() as session:
        session.auth = "v13gate", edge

        def api(path, payload=None):
            res = (session.get(BASE + path, timeout=10) if payload is None
                   else session.post(BASE + path, json=payload, timeout=10))
            res.raise_for_status()
            body = res.json()
            if body.get("code") != 200:
                raise AssertionError(f"业务码异常：{path} {body.get('code')}")
            return body.get("data")

        login = api("/api/admin/login", {"username": "v13admin", "password": env["SHUTTLE_TEST_ADMIN_PASSWORD"]})
        assert any(c.name == "SHUTTLE_V13_SESSION" and c.secure and c.has_nonstandard_attr("HttpOnly") for c in session.cookies)
        session.headers["X-Shuttle-CSRF"] = login["csrfToken"]
        js = session.get(BASE + "/operation.js", timeout=8)
        js.raise_for_status()
        assert all(label in js.text for label in ("运营总览", "仿真配置", "车辆运行", "智能调度", "策略对比", "高级实验"))
        assert session.get(BASE + "/api/admin/operation/ledger", timeout=8).status_code == 404
        sock, stream = websocket(session, edge)
        cfg = {"scenario":"custom", "start":"2026-11-18T07:53:00", "minutes":55,
               "speed":600, "seed":20261118, "passengers":SIZE, "wait":35, "reject":True,
               "policy":"smart", "flow":"burst", "pattern":"auto",
               "fleet":[{"lineId":1,"direction":"go","count":4,"manual":0,"capacity":16,
                         "initialStation":0,"initialLoad":0,"headway":110,"travel":42,"dwell":14}]}
        run = api("/api/admin/operation/control", {"action":"start","config":cfg})["runId"]
        found = False
        try:
            sock.settimeout(60)
            for _ in range(120):
                if frame(stream).get("type") == "simulation_change":
                    found = True
                    break
        finally:
            stream.close()
            sock.close()
        assert found
        until = time.monotonic() + 900
        while time.monotonic() < until:
            state = api("/api/operation/view")
            if state["phase"] == "error":
                raise AssertionError(state.get("error", "仿真失败"))
            if any(entry["runId"] == run for entry in state.get("history", [])):
                break
            time.sleep(3)
        else:
            raise TimeoutError("运行归档未在验收时限内出现")
        raw = api("/api/operation/view?run=" + run)
        assert raw["phase"] == "completed" and raw["metrics"]["checkins"] == SIZE
        assert len(raw["demandPlan"]) == SIZE and len(raw["vehicles"]) == 4
        assert raw["metrics"]["boarded"] > 0
        assert any(t["action"] == "completed" for t in raw["taskHistory"])
        assert any(s["boarded"] > 0 for t in raw["trips"].values() for s in t["stops"])
        assert all(0 <= e["afterLoad"] <= 16 for e in raw["events"] if e.get("afterLoad") is not None)
        record = {"run":run, "git":Path("/opt/shuttle-v13/current/VERSION").read_text().strip(),
                  "metrics":raw["metrics"],"trips":len(raw["trips"]), "events":len(raw["events"]),
                  "tasks":dict(Counter(t["action"] for t in raw["taskHistory"])),
                  "websocket":True,"secureCookie":True,"staticTabs":True}
        ROOT.mkdir(mode=0o700, exist_ok=True)
        (ROOT / "public-acceptance.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))
        print(json.dumps(record, ensure_ascii=False))
        api("/api/auth/logout", {})


if __name__ == "__main__":
    main()
