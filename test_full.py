import urllib.request, urllib.error, http.cookiejar, json, time, sys, subprocess, os, signal

BASE = "http://127.0.0.1:8080/api"
passed = 0
failed = 0
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
    csrf_token = get("/auth/session?guest=true")["data"]["csrfToken"]

def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name} {detail}")
    else:
        failed += 1
        print(f"  [FAIL] {name} {detail}")

print("=== 准备Spring Boot应用 ===")
proc = None
try:
    get("/lines")
    print("检测到现有服务，复用当前应用\n")
except Exception:
    jar_path = os.path.join(os.path.dirname(__file__), "backend", "target", "shuttle-1.0.0.jar")
    proc = subprocess.Popen(["java", "-jar", jar_path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("等待应用启动...")
    ready = False
    for i in range(30):
        time.sleep(2)
        try:
            get("/lines")
            ready = True
            break
        except Exception:
            pass
    if not ready:
        print("应用启动失败!")
        proc.kill()
        sys.exit(1)
    print("应用已启动\n")

try:
    print("=== 1. 线路站点验证 ===")
    for lid, exp_go, exp_back in [(1, 9, 13), (2, 9, 13), (3, 10, 14)]:
        go = get(f"/line/{lid}/stations?direction=go")
        back = get(f"/line/{lid}/stations?direction=back")
        check(f"{lid}号线去向", len(go["data"]) == exp_go, f"期望{exp_go}实际{len(go['data'])}")
        check(f"{lid}号线回向", len(back["data"]) == exp_back, f"期望{exp_back}实际{len(back['data'])}")

    print("\n=== 2. 车辆验证 ===")
    vehicles = get("/vehicles")["data"]
    check("车辆总数", len(vehicles) == 12, f"实际{len(vehicles)}")
    for v in vehicles[:4]:
        check(f"车辆{v['plateNo']}线路", v["lineId"] == 1, f"lineId={v['lineId']}")
    check("车辆容量", all(v["capacity"] == 13 for v in vehicles), "")

    print("\n=== 3. 司机登录+到站作业 ===")
    dl = login_driver()
    check("司机登录", dl["code"] == 200, f"code={dl['code']}")
    vid = dl["data"]["vehicle"]["id"]
    initial_load = dl["data"]["vehicle"]["currentLoad"]
    sr = post("/driver/set-running", {"vehicleId": vid, "running": True})
    check("设置运行", sr["code"] == 200, "")
    ar = post("/driver/arrive", {"vehicleId": vid, "stationId": 1, "direction": "go"})
    check("确认到站", ar["code"] == 200, "")
    al = post("/driver/alight", {"vehicleId": vid, "stationId": 1, "count": 0, "direction": "go"})
    check("确认下客", al["code"] == 200, "")
    bd = post("/driver/board", {"vehicleId": vid, "stationId": 1, "lineId": 1, "count": 3, "direction": "go"})
    check("确认上客3人", bd["code"] == 200 and bd["data"]["currentLoad"] == initial_load + 3, f"初始{initial_load}，当前{bd['data']['currentLoad']}")

    print("\n=== 4. ETA验证 ===")
    eta = get("/passenger/eta?lineId=1&stationId=3&direction=go")["data"]
    check("ETA返回", len(eta) > 0, f"条数={len(eta)}")
    if eta:
        e = eta[0]
        check("ETA字段", "etaMin" in e and "crowding" in e, f"etaMin={e['etaMin']}")

    print("\n=== 5. 乘客签到+队列融合 ===")
    use_guest_session()
    for i in range(20):
        post("/passenger/checkin", {"qrCode": "ST003", "lineId": 1, "direction": "go"})
    login_admin()
    queues = get("/admin/queues")["data"]
    active = [q for q in queues if q["waitingCount"] > 0]
    check("候车站点", len(active) > 0, f"活跃{len(active)}个")
    if active:
        q0 = active[0]
        check("候车人数", q0["waitingCount"] >= 15, f"waiting={q0['waitingCount']}")
        check("置信度", q0["confidence"] is not None, f"conf={q0['confidence']}")

    print("\n=== 6. 预警+任务验证 ===")
    alerts = get("/admin/alerts")["data"]
    check("预警生成", len(alerts) > 0, f"预警数={len(alerts)}")
    tasks = get("/admin/tasks")["data"]
    check("任务生成", len(tasks) > 0, f"任务数={len(tasks)}")
    if tasks:
        t0 = tasks[0]
        check("任务含反事实", "反事实" in (t0.get("suggestion") or "") or "评分" in (t0.get("suggestion") or ""), 
              f"suggestion含反事实/评分")

    print("\n=== 7. 事件流水 ===")
    events = get("/admin/events?limit=20")["data"]
    check("事件记录", len(events) > 0, f"事件数={len(events)}")

    print("\n=== 8. 卡尔曼融合观测更新 ===")
    obs = post("/admin/observation", {"stationId": 3, "lineId": 1, "direction": "go", "observedCount": 10, "variance": 4.0, "source": "camera"})
    check("观测更新", obs["code"] == 200, f"code={obs['code']}")
    if obs["code"] == 200 and obs["data"]:
        check("融合含置信度", "confidence" in obs["data"] or "fusedCount" in obs["data"], f"data={obs['data']}")

    print("\n=== 9. CUSUM突变检测 ===")
    cusum = get("/admin/cusum")["data"]
    check("CUSUM状态", cusum is not None, f"keys={list(cusum.keys()) if isinstance(cusum, dict) else 'N/A'}")

    print("\n=== 10. 多车协同分配 ===")
    assign = get("/admin/assign?stationId=3&lineId=1&direction=go")["data"]
    check("多车分配", assign is not None, f"keys={list(assign.keys()) if isinstance(assign, dict) else 'N/A'}")
    sv = get("/admin/station-vehicles?stationId=1")["data"]
    check("站点车辆", isinstance(sv, list), f"数量={len(sv)}")

    print("\n=== 11. 运营统计KPI(新) ===")
    kpi = get("/admin/stats/kpi")["data"]
    check("KPI-总候车", "totalWaiting" in kpi, f"totalWaiting={kpi.get('totalWaiting')}")
    check("KPI-车辆运行", "vehicleRunning" in kpi, f"vehicleRunning={kpi.get('vehicleRunning')}")
    check("KPI-任务完成率", "taskCompleteRate" in kpi, f"taskCompleteRate={kpi.get('taskCompleteRate')}")
    check("KPI-今日事件", "todayEvents" in kpi, f"todayEvents={kpi.get('todayEvents')}")

    print("\n=== 12. 线路概览(新) ===")
    lo = get("/admin/stats/line-overview")["data"]
    check("线路概览", "1" in lo and "2" in lo and "3" in lo, f"keys={list(lo.keys())}")

    print("\n=== 13. 等待趋势(新) ===")
    wt = get("/admin/stats/waiting-trend?hours=6")["data"]
    check("等待趋势", isinstance(wt, dict), f"keys={list(wt.keys())}")

    print("\n=== 14. 站点排名(新) ===")
    sr = get("/admin/stats/station-ranking")["data"]
    check("站点排名", isinstance(sr, list) and len(sr) > 0, f"数量={len(sr)}")
    if sr:
        check("排名按候车降序", sr[0].get("waiting", 0) >= sr[-1].get("waiting", 0), 
              f"top={sr[0].get('waiting')} bottom={sr[-1].get('waiting')}")

    print("\n=== 15. 统计快照(新) ===")
    snap = post("/admin/stats/snapshot", {})
    check("手动快照", snap["code"] == 200, f"code={snap['code']}")
    today = time.strftime("%Y-%m-%d")
    hist = get(f"/admin/stats/history?date={today}")["data"]
    check("历史统计查询", isinstance(hist, list), f"记录数={len(hist)}")
    sm = get(f"/admin/stats/summary?date={today}")["data"]
    check("统计汇总", "avgWaiting" in sm, f"avgWaiting={sm.get('avgWaiting')}")

    print("\n=== 16. 人数校准 ===")
    login_driver()
    cal = post("/driver/calibrate", {"vehicleId": vid, "afterLoad": 5})
    check("校准人数", cal["code"] == 200 and cal["data"]["currentLoad"] == 5, f"load={cal['data']['currentLoad']}")

    print("\n=== 17. 管理员Dashboard ===")
    login_admin()
    dash = get("/admin/dashboard")["data"]
    check("Dashboard-车辆", "vehicles" in dash, f"车辆数={len(dash.get('vehicles',[]))}")
    check("Dashboard-队列", "queues" in dash, f"队列数={len(dash.get('queues',[]))}")
    check("Dashboard-预警", "alerts" in dash, "")
    check("Dashboard-任务", "tasks" in dash, "")

    print("\n=== 18. 单车辆查询(新) ===")
    v1 = get(f"/vehicle/{vid}")["data"]
    check("单车辆查询", v1 is not None and v1["id"] == vid, f"id={v1['id'] if v1 else 'N/A'}")

    print(f"\n{'='*50}")
    print(f"测试结果: 通过{passed} / 失败{failed} / 总计{passed+failed}")
    print(f"{'='*50}")

finally:
    if proc is not None:
        print("\n停止本测试启动的应用...")
        proc.kill()
        proc.wait()
        print("应用已停止")
