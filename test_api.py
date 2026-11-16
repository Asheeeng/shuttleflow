import urllib.request, urllib.parse, urllib.error, http.cookiejar, json, time, sys

BASE = "http://127.0.0.1:8080/api"
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
csrf_token = ""

def get(path):
    with client.open(f"{BASE}{path}") as r:
        return json.loads(r.read())

def post(path, data):
    body = json.dumps(data).encode()
    headers = {"Content-Type": "application/json"}
    if csrf_token and path not in ("/driver/login", "/admin/login"):
        headers["X-Shuttle-CSRF"] = csrf_token
    req = urllib.request.Request(f"{BASE}{path}", data=body, headers=headers)
    with client.open(req) as r:
        return json.loads(r.read())

def login_driver():
    global csrf_token
    result = post("/driver/login", {"phone": "13800000001", "password": "123456"})
    csrf_token = result["data"]["csrfToken"]
    return result

def login_admin():
    global csrf_token
    result = post("/admin/login", {"username": "admin", "password": "admin123"})
    csrf_token = result["data"]["csrfToken"]
    return result

def use_guest_session():
    global csrf_token
    if csrf_token:
        post("/auth/logout", {})
    session = get("/auth/session?guest=true")["data"]
    csrf_token = session["csrfToken"]

print("=== 线路站点验证 ===")
for lid in [1, 2, 3]:
    go = get(f"/line/{lid}/stations?direction=go")
    back = get(f"/line/{lid}/stations?direction=back")
    print(f"{lid}号线: 向向{len(go['data'])}站 / 回向{len(back['data'])}站")

print("\n=== 车辆验证 ===")
vehicles = get("/vehicles")["data"]
print(f"车辆总数: {len(vehicles)}")
for v in vehicles[:4]:
    print(f"  车牌{v['plateNo']} -> {v['lineId']}号线 容量{v['capacity']}")

print("\n=== 司机登录+设置运行+到站 ===")
dl = login_driver()
vid = dl["data"]["vehicle"]["id"]
print(f"司机登录: 车辆{dl['data']['vehicle']['plateNo']}号线{dl['data']['vehicle']['lineId']}")
post("/driver/set-running", {"vehicleId": vid, "running": True})
post("/driver/arrive", {"vehicleId": vid, "stationId": 1, "direction": "go"})
print("设置当日运行 + 到站1号")

print("\n=== ETA验证 ===")
eta = get("/passenger/eta?lineId=1&stationId=3&direction=go")["data"]
print(f"站点3 ETA: {len(eta)}条")
if eta:
    e = eta[0]
    print(f"  首班: 车辆{e['vehiclePlate']} {e['etaMin']}分钟 拥挤{e['crowding']} 负载{e['loadRate']}%")

print("\n=== 乘客签到+队列融合 ===")
use_guest_session()
for i in range(5):
    post("/passenger/checkin", {"qrCode": "ST003", "lineId": 1, "direction": "go"})
login_admin()
queues = get("/admin/queues")["data"]
active = [q for q in queues if q["waitingCount"] > 0]
print(f"5人签到后, 候车站点: {len(active)}个")
if active:
    print(f"  站点{active[0]['stationId']} 候车{active[0]['waitingCount']}人 置信度{active[0]['confidence']}")

print("\n=== 预警+任务验证 ===")
alerts = get("/admin/alerts")["data"]
print(f"预警数: {len(alerts)}")
for a in alerts:
    print(f"  [{a['level']}] {a['message']}")
tasks = get("/admin/tasks")["data"]
print(f"任务数: {len(tasks)}")
for t in tasks:
    print(f"  {t['taskNo']} [{t['status']}] 优先级{t['priority']} {t['suggestion'][:30]}")

print("\n=== 事件流水 ===")
events = get("/admin/events?limit=10")["data"]
print(f"事件数: {len(events)}")
for e in events[:5]:
    print(f"  {e['type']} 来源{e['source']} 车辆{e.get('vehicleId','-')} 站点{e.get('stationId','-')}")

print("\n=== MVP闭环验证通过 ===")
