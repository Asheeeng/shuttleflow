"""V1.2 HTTP认证、账号审核、重复请求和服务边界检查。仅运行在隔离测试库。"""
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.request

BASE = os.environ.get("SHUTTLE_TEST_API", "http://127.0.0.1:8080/api")
ADMIN_USER = os.environ["SHUTTLE_TEST_ADMIN_USERNAME"]
ADMIN_PASSWORD = os.environ["SHUTTLE_TEST_ADMIN_PASSWORD"]
DRIVER_PHONE = os.environ["SHUTTLE_TEST_DRIVER_PHONE"]
DRIVER_PASSWORD = os.environ["SHUTTLE_TEST_DRIVER_PASSWORD"]
DRIVER_NAME = os.environ.get("SHUTTLE_TEST_DRIVER_NAME", "V12测试司机")


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def call(self, method, path, data=None, csrf=True, expect_network_error=False):
        headers = {}
        body = None
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        if csrf and self.csrf:
            headers["X-Shuttle-CSRF"] = self.csrf
        request = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")
        except urllib.error.URLError:
            if expect_network_error:
                return 0, {}
            raise

    def get(self, path):
        return self.call("GET", path)[1]

    def post(self, path, data, csrf=True):
        return self.call("POST", path, data, csrf)

    def login_admin(self):
        status, body = self.post("/admin/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD}, csrf=False)
        check("管理员登录", status == 200 and body.get("code") == 200, f"HTTP {status}")
        self.csrf = body["data"]["csrfToken"]
        return body["data"]

    def login_driver(self):
        status, body = self.post("/driver/login", {"phone": DRIVER_PHONE, "password": DRIVER_PASSWORD}, csrf=False)
        check("司机登录", status == 200 and body.get("code") == 200, f"HTTP {status}")
        self.csrf = body["data"]["csrfToken"]
        return body["data"]


def check(name, condition, detail=""):
    if not condition:
        raise AssertionError(f"失败：{name} {detail}")
    print(f"通过：{name} {detail}")


anonymous = Client()
status, _ = anonymous.call("GET", "/admin/dashboard")
check("匿名请求不能读取管理数据", status == 401, f"HTTP {status}")
status, _ = anonymous.post("/passenger/checkin", {"stationId": 1, "lineId": 1})
check("匿名乘客写请求被拒绝", status == 401, f"HTTP {status}")
status, _ = anonymous.post("/driver/register", {"phone": "19900000001", "password": "temporary-password"}, csrf=False)
check("公开司机注册已关闭", status in (401, 404), f"HTTP {status}")

guest = Client()
guest_session = guest.get("/auth/session?guest=true")["data"]
guest.csrf = guest_session["csrfToken"]
check("游客会话带CSRF令牌", guest_session["authenticated"] and guest_session["user"]["guest"])
queue_before = guest.get("/passenger/queue")["data"]
before_count = next((q["waitingCount"] for q in queue_before if q["stationId"] == 1 and q["lineId"] == 1 and q["direction"] == "go"), 0)
payload = {"stationId": 1, "lineId": 1, "direction": "go"}
first = guest.post("/passenger/checkin", payload)
second = guest.post("/passenger/checkin", payload)
first_id = first[1]["data"]["request"]["id"]
second_id = second[1]["data"]["request"]["id"]
check("同一会话重复签到幂等", first[0] == 200 and second[0] == 200 and first_id == second_id)
queue_after = guest.get("/passenger/queue")["data"]
after_count = next(q["waitingCount"] for q in queue_after if q["stationId"] == 1 and q["lineId"] == 1 and q["direction"] == "go")
check("重复签到只增加一人", after_count == before_count + 1, f"{before_count}→{after_count}")

phone = "19" + str(time.time_ns())[-16:]
password = os.environ.get("SHUTTLE_TEST_PASSENGER_PASSWORD", "")
if len(password) < 8:
    raise RuntimeError("请设置 SHUTTLE_TEST_PASSENGER_PASSWORD（至少8位）")
status, registered = guest.post("/passenger/register", {"name": "V12测试乘客", "phone": phone, "password": password})
check("乘客可注册并切换为实名会话", status == 200 and registered.get("code") == 200)
guest.csrf = registered["data"]["csrfToken"]
session = guest.get("/auth/session")["data"]
check("实名乘客身份不含访客标记", session["user"]["role"] == "PASSENGER" and not session["user"]["guest"])
check("登录会话响应不含口令字段", "passwordHash" not in json.dumps(session) and "password_hash" not in json.dumps(session))
status, cancelled = guest.post("/passenger/cancel", {"requestId": first_id})
check("乘客账号仍可取消注册前游客请求", status == 200 and cancelled.get("code") == 200)
status, duplicate_cancel = guest.post("/passenger/cancel", {"requestId": first_id})
check("重复取消不重复扣减队列", status == 200 and duplicate_cancel.get("code") == 409)
queue_final = guest.get("/passenger/queue")["data"]
final_count = next(q["waitingCount"] for q in queue_final if q["stationId"] == 1 and q["lineId"] == 1 and q["direction"] == "go")
check("取消后队列回到初始值", final_count == before_count, f"期望{before_count}，实际{final_count}")

guest.post("/auth/logout", {})
status, login_body = guest.post("/passenger/login", {"phone": phone, "password": password}, csrf=False)
check("乘客账号可再次登录", status == 200 and login_body.get("code") == 200)
guest.csrf = login_body["data"]["csrfToken"]
status, bad_login = guest.post("/passenger/login", {"phone": phone, "password": "incorrect-password"}, csrf=False)
check("错误乘客密码被拒绝", status == 200 and bad_login.get("code") == 401)
guest.post("/auth/logout", {})
status, after_logout = guest.call("GET", "/auth/session")
check("退出后会话失效", status == 200 and after_logout["data"]["authenticated"] is False)

admin = Client()
admin.login_admin()
vehicles = admin.get("/admin/vehicles")["data"]
drivers = admin.get("/admin/drivers")["data"]
driver = next((d for d in drivers if d.get("phone") == DRIVER_PHONE), None)
if driver is None:
    assigned = {d.get("defaultVehicleId") for d in drivers if d.get("defaultVehicleId") is not None}
    vehicle = next((v for v in vehicles if v["id"] not in assigned), None)
    check("测试库存在未绑定车辆", vehicle is not None)
    status, created = admin.post("/admin/driver/create", {
        "name": DRIVER_NAME, "phone": DRIVER_PHONE, "password": DRIVER_PASSWORD,
        "vehicleId": vehicle["id"]
    })
    check("管理员创建司机并绑定车辆", status == 200 and created.get("code") == 200)
    driver_id = created["data"]["driver"]["id"]
    driver_vehicle_id = created["data"]["vehicle"]["id"]
else:
    driver_id = driver["id"]
    driver_vehicle_id = driver["defaultVehicleId"]
check("管理员接口不返回司机密码哈希", "passwordHash" not in json.dumps(drivers) and "password_hash" not in json.dumps(drivers))

dispatcher_user = "v12_dispatch_" + str(time.time_ns())[-10:]
dispatcher_password = os.environ.get("SHUTTLE_TEST_DISPATCHER_PASSWORD", "")
if len(dispatcher_password) < 12:
    raise RuntimeError("请设置 SHUTTLE_TEST_DISPATCHER_PASSWORD（至少12位）")
status, dispatcher_body = admin.post("/admin/dispatcher/create", {
    "username": dispatcher_user, "name": "V12测试调度员", "password": dispatcher_password
})
check("管理员创建调度员", status == 200 and dispatcher_body.get("code") == 200)
admin.post("/auth/logout", {})

dispatcher = Client()
status, login_result = dispatcher.post("/admin/login", {"username": dispatcher_user, "password": dispatcher_password}, csrf=False)
check("调度员账号登录", status == 200 and login_result.get("code") == 200)
dispatcher.csrf = login_result["data"]["csrfToken"]
status, denied = dispatcher.post("/admin/driver/create", {"name": "无权", "phone": "19900000101", "password": DRIVER_PASSWORD, "vehicleId": driver_vehicle_id})
check("调度员不能创建司机", status == 403 and denied.get("code") == 403)
status, denied = dispatcher.post("/admin/vehicle/capacity", {"vehicleId": driver_vehicle_id, "capacity": 14})
check("调度员不能更改车辆容量", status == 403 and denied.get("code") == 403)
dispatcher.post("/auth/logout", {})

driver_client = Client()
data = driver_client.login_driver()
check("司机会话返回绑定车辆", data.get("vehicle", {}).get("id") == driver_vehicle_id)
status, _ = driver_client.call("GET", "/vehicles")
check("司机不能读取全量车辆接口", status == 403)
status, _ = driver_client.post("/driver/arrive", {"vehicleId": driver_vehicle_id + 1, "stationId": 1})
check("司机不能操作其他车辆", status == 200 and _.get("code") == 403)
driver_client.post("/auth/logout", {})

try:
    urllib.request.urlopen("http://127.0.0.1:8000/cusum/status")
    raise AssertionError("Python业务接口不应接受无令牌直连")
except urllib.error.HTTPError as error:
    check("FastAPI拒绝无令牌业务访问", error.code == 503)

print("V1.2 HTTP认证、幂等、角色和服务边界检查完成。")
