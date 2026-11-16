"""针对HttpSession、CSRF和角色边界的本地HTTP回归检查。"""
import http.cookiejar
import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080/api"
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf = ""


def call(method, path, data=None, include_csrf=True):
    headers = {}
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    if include_csrf and csrf:
        headers["X-Shuttle-CSRF"] = csrf
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        with client.open(req) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def check(name, condition, detail=""):
    if not condition:
        raise AssertionError(f"失败：{name} {detail}")
    print(f"通过：{name} {detail}")


def login(path, payload):
    global csrf
    status, body = call("POST", path, payload, include_csrf=False)
    check("登录成功", status == 200 and body.get("code") == 200, path)
    csrf = body["data"]["csrfToken"]
    return body["data"]


unauthenticated = urllib.request.build_opener()
try:
    unauthenticated.open(BASE + "/admin/dashboard")
    raise AssertionError("未登录请求不应读取管理员数据")
except urllib.error.HTTPError as error:
    check("匿名会话无法访问管理员接口", error.code == 401, f"HTTP {error.code}")

status, _ = call("POST", "/passenger/checkin", {"stationId": 1, "lineId": 1})
check("匿名写请求必须建立游客会话", status == 401, f"HTTP {status}")

driver = login("/driver/login", {"phone": "13800000001", "password": "123456"})
vehicle_id = driver["vehicle"]["id"]
status, _ = call("POST", "/driver/arrive", {"vehicleId": vehicle_id, "stationId": 1}, include_csrf=False)
check("司机写请求必须通过CSRF校验", status == 403, f"HTTP {status}")
status, result = call("POST", "/driver/arrive", {"vehicleId": vehicle_id + 1, "stationId": 1})
check("司机不能操作其他车辆", status == 200 and result.get("code") == 403)
status, _ = call("GET", "/admin/dashboard")
check("司机角色不能访问管理接口", status == 401, f"HTTP {status}")
call("POST", "/auth/logout", {})

login("/admin/login", {"username": "dispatcher", "password": "123456"})
status, _ = call("POST", "/admin/vehicle/set-running", {"vehicleId": vehicle_id, "running": True})
check("调度员不能修改车辆运行状态", status == 403, f"HTTP {status}")
call("POST", "/auth/logout", {})

login("/admin/login", {"username": "admin", "password": "admin123"})
status, body = call("GET", "/admin/dashboard")
check("管理员会话可以读取控制台", status == 200 and body.get("code") == 200)
status, body = call("GET", "/admin/ai/health")
check("管理员可经后端代理访问AI健康状态", status == 200 and body.get("data", {}).get("available") is True)
call("POST", "/auth/logout", {})
status, body = call("GET", "/auth/session")
check("退出后会话失效", status == 200 and body.get("data", {}).get("authenticated") is False)

try:
    urllib.request.urlopen("http://127.0.0.1:8000/cusum/status")
    raise AssertionError("Python服务业务接口不应接受无令牌直连")
except urllib.error.HTTPError as error:
    check("Python业务接口拒绝无服务令牌请求", error.code == 503, f"HTTP {error.code}")

print("认证边界检查完成。")
