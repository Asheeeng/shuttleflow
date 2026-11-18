"""
校园摆渡车 AI 智能调度服务 (Python + FastAPI)
独立部署，通过 HTTP 与 Java 后端通信
启动: python main.py（默认仅监听本机；跨主机部署应单独配置网络策略）
"""
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
import requests, numpy as np, time, logging, os, secrets
from predict import DemandPredictor
from cusum import CusumDetector
from assign import MultiVehicleAllocator
from kalman import KalmanFusion
from eta import EtaPredictor

logging.basicConfig(level=logging.INFO)
app = FastAPI(title="校园摆渡车AI服务", version="1.0.0")

BACKEND = os.getenv("SHUTTLE_BACKEND_API_URL", "http://127.0.0.1:8080/api").rstrip("/")
SERVICE_TOKEN = os.getenv("SHUTTLE_AI_SERVICE_TOKEN", "")
SERVICE_HEADERS = {"X-Shuttle-Service-Token": SERVICE_TOKEN} if SERVICE_TOKEN else {}

@app.middleware("http")
async def require_internal_service_token(request: Request, call_next):
    if request.url.path not in ("/", "/health"):
        supplied = request.headers.get("X-Shuttle-Service-Token", "")
        if not SERVICE_TOKEN or not secrets.compare_digest(supplied, SERVICE_TOKEN):
            return JSONResponse(status_code=503, content={"code":503,"msg":"AI服务内部令牌未配置或无效"})
    return await call_next(request)
predictor = DemandPredictor()
detector = CusumDetector()
allocator = MultiVehicleAllocator()
kalman = KalmanFusion()
eta_calc = EtaPredictor()

class Observation(BaseModel):
    station_id: int
    line_id: int
    direction: str = "go"
    observed_count: int
    prior_count: int = 0
    variance: float = 4.0
    source: str = "camera"

class AssignRequest(BaseModel):
    station_id: int
    line_id: int
    direction: str = "go"
    waiting: int = 0

class CusumInput(BaseModel):
    station_id: int
    line_id: int
    direction: str = "go"
    arrival_rate: float

class PredictRequest(BaseModel):
    station_id: int
    line_id: int
    direction: str = "go"
    history: List[int] = []

@app.get("/")
def root():
    return {"service": "campus-shuttle-ai", "status": "running", "version": "1.0.0"}

@app.get("/health")
def health():
    return {"status": "ok"}

@app.get("/internal/health")
def internal_health():
    return {"status": "ok", "service_token_configured": bool(SERVICE_TOKEN)}

@app.post("/predict")
def predict(req: PredictRequest):
    result = predictor.predict(req.station_id, req.line_id, req.direction, req.history)
    return {"code": 200, "data": result}

@app.post("/cusum")
def cusum(req: CusumInput):
    result = detector.detect(req.station_id, req.line_id, req.direction, req.arrival_rate)
    return {"code": 200, "data": result}

@app.get("/cusum/status")
def cusum_status():
    return {"code": 200, "data": detector.get_status()}

@app.post("/assign")
def assign(req: AssignRequest):
    vehicles = []
    try:
        r = requests.get(f"{BACKEND}/admin/station-vehicles", params={
            "stationId": req.station_id, "lineId": req.line_id, "direction": req.direction
        }, headers=SERVICE_HEADERS, timeout=3)
        if r.status_code != 200:
            return JSONResponse(status_code=503, content={"code":503,"msg":"无法读取对应线路与方向的可用车辆"})
        payload = r.json()
        if payload.get("code") != 200 or not isinstance(payload.get("data"), list):
            return JSONResponse(status_code=503, content={"code":503,"msg":"车辆响应格式无效"})
        vehicles = [v for v in payload["data"]
                    if int(v.get("lineId", -1)) == req.line_id and v.get("direction") == req.direction]
    except Exception:
        return JSONResponse(status_code=503, content={"code":503,"msg":"无法读取线路车辆状态"})
    result = allocator.assign(vehicles, req.waiting, req.station_id, req.line_id, req.direction)
    return {"code": 200, "data": result}

@app.post("/kalman")
def kalman_fuse(obs: Observation):
    result = kalman.fuse(obs.station_id, obs.line_id, obs.direction, obs.observed_count,
                         obs.prior_count, obs.variance, obs.source)
    return {"code": 200, "data": result}

@app.get("/eta")
def eta(line_id: int, station_id: int, direction: str = "go"):
    try:
        r = requests.get(f"{BACKEND}/passenger/eta?lineId={line_id}&stationId={station_id}&direction={direction}", timeout=3)
        if r.status_code == 200:
            return r.json()
    except: pass
    return {"code": 200, "data": []}

@app.get("/course-predict")
def course_predict():
    """课表先验: 潜在需求 = 人数 × 转化率"""
    try:
        r = requests.get(f"{BACKEND}/admin/dashboard", headers=SERVICE_HEADERS, timeout=3)
        if r.status_code == 200:
            data = r.json().get("data", {})
            dispatch = data.get("dispatch", {})
            courses = dispatch.get("upcomingCourses", [])
            results = []
            for c in courses:
                potential = int(c.get("studentCount", 0) * float(c.get("conversionRate", 0.3)))
                results.append({
                    "building": c.get("building"),
                    "endTime": c.get("endTime"),
                    "studentCount": c.get("studentCount"),
                    "conversionRate": c.get("conversionRate"),
                    "potentialDemand": potential,
                    "p50": int(potential * 0.8),
                    "p90": int(potential * 1.2),
                    "action": "preposition" if potential >= 8 else "monitor",
                    "reasonCode": "COURSE_PRIOR",
                    "confidence": 0.75
                })
            return {"code": 200, "data": results}
    except Exception as e:
        return {"code": 500, "msg": str(e)}
    return {"code": 200, "data": []}

@app.get("/ablation")
def ablation(station_id: int, line_id: int, direction: str = "go", waiting: int = 20):
    """消融实验: 对比4种策略"""
    try:
        r = requests.get(f"{BACKEND}/admin/station-vehicles?stationId={station_id}", headers=SERVICE_HEADERS, timeout=3)
        vehicles = r.json().get("data", []) if r.status_code == 200 else []
    except:
        vehicles = []
    results = {}
    for strategy in ["original", "fcfs", "seat_release", "seat_release_reserve"]:
        results[strategy] = allocator.assign(vehicles, waiting, station_id, line_id, direction, strategy)
    return {"code": 200, "data": results}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=os.getenv("AI_BIND_HOST", "127.0.0.1"), port=int(os.getenv("AI_PORT", "8000")))
