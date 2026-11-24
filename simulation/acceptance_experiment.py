"""验证独立实验启停不修改本机测试业务数据。禁止传入远程地址。"""
import json
import os
import secrets
import subprocess
from urllib.request import Request, urlopen

from simulation.api_client import ShuttleApi
from simulation.operation_lifecycle_check import wait_until


def main():
    base = "http://127.0.0.1:18090"
    admin, rider, anonymous = (ShuttleApi(base) for _ in range(3))
    admin.login_admin(os.environ["SHUTTLE_TEST_ADMIN_USERNAME"], os.environ["SHUTTLE_TEST_ADMIN_PASSWORD"])
    rider.start_guest()
    assert anonymous.request("GET", "/api/admin/experiment/state").code == 401
    assert rider.request("POST", "/api/admin/experiment/control", {"action":"reset"}).code == 401
    assert admin.request("POST", "/api/admin/operation/clock", {"action":"clear"}).code == 404
    assert admin.request("POST", "/api/admin/simulation/cusum/tick", {}).code == 404
    assert admin.request("POST", "/api/admin/ai/kalman", {}).code == 404

    def stable():
        query = "/api/passenger/crowding?lineId=3&direction=back&stationId=20"
        with urlopen(Request("http://127.0.0.1:18010/cusum/status", headers={
                "X-Shuttle-Service-Token":os.environ["SHUTTLE_AI_SERVICE_TOKEN"]}), timeout=3) as response:
            ai = json.load(response)
        return (admin.get(query).data, admin.get("/api/admin/vehicles").data, ai,
                subprocess.check_output(["redis-cli","-p","16389","--json","XRANGE","shuttle:events","-","+"]))

    def control(action, **payload):
        return admin.post("/api/admin/experiment/control", {"action":action, **payload}).data

    def state():
        return admin.get("/api/admin/experiment/state").data

    for policy in ("fixed","threshold","smart"):
        run = control("start",config={"scenario":"class_peak","minutes":100,"passengers":100,"speed":1800,
            "policy":policy,"flow":"burst","wait":45,"fleet":[
                {"lineId":2,"count":4,"capacity":16,"headway":180,"travel":90,"dwell":8}]})
        end = wait_until(state, lambda s: s["phase"]=="completed" and any(
            h["runId"]==run["runId"] for h in s["history"]), timeout=35)
        result = admin.get("/api/admin/experiment/state?run="+run["runId"]).data
        assert end["metrics"]["checkins"]==100 and end["metrics"]["boarded"]>0
        digest = result["traceSha256"] if policy == "fixed" else digest
        assert digest == result["traceSha256"]
        print(policy, end["metrics"])

    start = control("start",config={"scenario":"capacity_shortage","minutes":15,"passengers":26,
                                    "policy":"smart","speed":20,"flow":"uniform"})
    wait_until(state, lambda s: s.get("metrics",{}).get("checkins",0)>0)
    register = rider.post("/api/passenger/register", {
        "name":"实验隔离验收", "phone":f"194{secrets.randbelow(10**8):08}", "password":secrets.token_urlsafe(20)}).data
    rider.csrf_token = register["csrfToken"]
    request = rider.post("/api/passenger/checkin", {
        "lineId":3,"direction":"back","stationId":20,"destStationId":19}).data["request"]
    control("pause")
    paused = state()
    assert paused["phase"] == "paused"
    before = stable()
    assert before[0]["waitingCount"] > 0
    control("resume")
    control("stop")
    wait_until(state, lambda s: s["phase"]=="stopped" and any(
        h["runId"]==start["runId"] for h in s["history"]))
    wait_until(lambda: _reset_when_stopped(control), lambda row: row["ok"])
    assert state()["phase"] == "idle"
    assert stable() == before, "停止或重置污染正式队列、车辆、在线 AI 状态或 Redis 事件"
    rider.post("/api/passenger/cancel", {"requestId":request["id"]})
    rider.logout()
    admin.logout()
    print("正式会话在实验中签到成功，启停和重置不改变候车、车辆、在线 CUSUM、Redis 事件；仍可正常取消。")


def _reset_when_stopped(control):
    try:
        control("reset")
        return {"ok":True}
    except AssertionError:
        return {"ok":False}


if __name__ == "__main__":
    main()
