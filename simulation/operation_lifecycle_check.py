"""在运行中的独立测试应用中验证红色预警、停止及候车清理。"""
import json
import os
from pathlib import Path
import secrets
import time

from simulation.api_client import ShuttleApi


BASE = "http://127.0.0.1:18080"


def wait_until(operation, predicate, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        current = operation()
        if current.get("phase") == "error":
            raise AssertionError(current["error"])
        if predicate(current):
            return current
        time.sleep(0.3)
    raise AssertionError("没有在规定时间内收到仿真状态")


def main():
    admin = ShuttleApi(BASE)
    rider = ShuttleApi(BASE)
    output = Path(os.environ["SHUTTLE_BROWSER_OUTPUT"])
    output.mkdir(exist_ok=True, parents=True)
    admin.login_admin(os.environ["SHUTTLE_TEST_ADMIN_USERNAME"], os.environ["SHUTTLE_TEST_ADMIN_PASSWORD"])
    def command(action, **body):
        return admin.post("/api/admin/operation/control", {"action": action, **body}).data
    def view():
        return admin.get("/api/operation/view").data
    started = command("start", config={
        "scenario": "capacity_shortage", "flow": "uniform", "minutes": 15,
        "passengers": 26, "speed": 5, "wait": 20, "policy": "smart",
    })
    try:
        state = wait_until(view, lambda row: row.get("metrics", {}).get("waiting") == 26 and
                           any(alert["level"] == "red" for alert in row.get("alerts", [])))
        assert not state["vehicles"] and state["metrics"]["waiting"] == 26
        command("pause")
        rider.start_guest()
        account = rider.post("/api/passenger/register", {
            "name": "停止清理验收乘客", "phone": f"194{secrets.randbelow(10**13):013}",
            "password": secrets.token_urlsafe(18)}).data
        rider.csrf_token = account["csrfToken"]
        request = rider.post("/api/passenger/checkin", {
            "lineId": 3, "direction": "back", "stationId": 20, "destStationId": 19}).data["request"]
        wait_until(view, lambda row: row.get("metrics", {}).get("checkins") == 27)
        command("stop")
        wait_until(view, lambda row: row["phase"] == "stopped" and any(
            entry["runId"] == started["runId"] for entry in row["history"]))
        def live_queue():
            return admin.get("/api/passenger/crowding?lineId=3&stationId=20&direction=back").data
        wait_until(live_queue, lambda row: row["waitingCount"] == 0)
        result = admin.get("/api/operation/view?run="+started["runId"]).data
        assert result["phase"] == "stopped"
        assert result["metrics"]["waiting"] == 27
        assert any(p["id"] == request["id"] for p in result["passengers"])
        assert rider.request("POST", "/api/passenger/cancel", {"requestId": request["id"]}).code == 409
        (output / "shortage-and-stop.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print("红色预警、停止、仿真账户与真人测试账户候车清理通过")
    finally:
        rider.logout()
        admin.logout()


if __name__ == "__main__":
    main()
