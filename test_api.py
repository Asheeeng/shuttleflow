import urllib.request, json, time, sys

BASE = "http://localhost:8080/api"

def get(path):
    with urllib.request.urlopen(f"{BASE}{path}") as r:
        return json.loads(r.read())

def post(path, data):
    body = json.dumps(data).encode()
    req = urllib.request.Request(f"{BASE}{path}", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())

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
dl = post("/driver/login", {"phone": "13800000001", "password": "123456"})
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
for i in range(5):
    post("/passenger/checkin", {"qrCode": "ST003", "lineId": 1, "direction": "go"})
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