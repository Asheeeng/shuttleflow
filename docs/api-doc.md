# 接口文档

**项目名称**：校园摆渡车站点客流上报与AI智能动态调度系统
**版本**：v1.0
**日期**：2026-11-14
**Base URL**：`http://localhost:8080`（后端） / `http://localhost:8000`（AI 服务）

---

## 1. 通用约定

### 1.1 请求头

| Header | 说明 |
|--------|------|
| Authorization | `Bearer <JWT>`（除登录外必填） |
| Content-Type | `application/json` |

### 1.2 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 409 | 冲突（如重复签到） |
| 500 | 服务器错误 |

---

## 2. 乘客端接口

### 2.1 POST /api/passenger/checkin —— 扫码签到上报

**描述**：乘客扫码后上报站点与去向，系统融合入客流队列。

**请求参数**：
```json
{
  "stationId": 101,
  "direction": 1,
  "passengerOpenid": "oX1234567890"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| stationId | Long | 是 | 站点 ID |
| direction | Int | 是 | 1=去向 2=回向 |
| passengerOpenid | String | 是 | 乘客匿名标识 |

**响应示例**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "requestId": 5001,
    "queuePosition": 7,
    "eta": 360,
    "vehicleId": 3,
    "fusedFlow": 18
  }
}
```

### 2.2 GET /api/passenger/eta —— 查询 ETA

**描述**：查询某站点某去向的预计等待时间与队列信息。

**请求参数**（Query）：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| stationId | Long | 是 | 站点 ID |
| direction | Int | 是 | 1=去向 2=回向 |

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "stationId": 101,
    "direction": 1,
    "fusedFlow": 18,
    "eta": 360,
    "etaVehicleId": 3,
    "queueAhead": 7,
    "nearbyVehicles": [
      {"vehicleId": 3, "eta": 360, "load": 8, "capacity": 13},
      {"vehicleId": 1, "eta": 540, "load": 11, "capacity": 13}
    ]
  }
}
```

### 2.3 POST /api/passenger/cancel —— 取消签到

**请求参数**：
```json
{
  "requestId": 5001
}
```

**响应示例**：
```json
{
  "code": 200,
  "message": "已取消",
  "data": { "requestId": 5001, "status": "cancelled" }
}
```

---

## 3. 司机端接口

### 3.1 POST /api/driver/login —— 司机登录

**请求参数**：
```json
{
  "driverNo": "D003",
  "password": "xxx"
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGci...",
    "driverId": 3,
    "vehicleId": 3,
    "routeId": 1,
    "routeName": "1号线"
  }
}
```

### 3.2 POST /api/driver/shift/start —— 设置当日运行

**请求参数**：
```json
{
  "vehicleId": 3,
  "routeId": 1,
  "direction": 1
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": { "shiftId": 2001, "vehicleStatus": "running" }
}
```

### 3.3 POST /api/driver/arrive —— 到站点击

**描述**：司机抵达站点点击触发 arrive 事件。

**请求参数**：
```json
{
  "vehicleId": 3,
  "stationId": 101,
  "timestamp": 1699948800
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "eventId": 9001,
    "vehicleId": 3,
    "stationId": 101,
    "currentLoad": 8,
    "stationFlow": 18
  }
}
```

### 3.4 POST /api/driver/board —— 上车点击

**描述**：司机点击上报本站上车人数。

**请求参数**：
```json
{
  "vehicleId": 3,
  "stationId": 101,
  "count": 5,
  "timestamp": 1699948830
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "eventId": 9002,
    "newLoad": 13,
    "loadRate": 1.0,
    "alert": "满载预警触发"
  }
}
```

### 3.5 POST /api/driver/alight —— 下车点击

**请求参数**：
```json
{
  "vehicleId": 3,
  "stationId": 105,
  "count": 4,
  "timestamp": 1699949200
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": { "eventId": 9003, "newLoad": 9 }
}
```

### 3.6 POST /api/driver/calibrate —— 人数校准

**描述**：司机目测输入当前车内人数，系统卡尔曼融合修正。

**请求参数**：
```json
{
  "vehicleId": 3,
  "observedLoad": 10
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "previousLoad": 9,
    "fusedLoad": 9.6,
    "kalmanK": 0.28
  }
}
```

### 3.7 POST /api/driver/task/accept —— 接单

**请求参数**：
```json
{ "taskId": 3001, "vehicleId": 3 }
```

**响应示例**：
```json
{
  "code": 200,
  "data": { "taskId": 3001, "state": "accepted" }
}
```

### 3.8 POST /api/driver/task/reject —— 拒单

**请求参数**：
```json
{ "taskId": 3001, "vehicleId": 3, "reason": "已满载" }
```

**响应示例**：
```json
{
  "code": 200,
  "data": { "taskId": 3001, "state": "rejected", "escalated": true }
}
```

### 3.9 POST /api/driver/heartbeat —— 车辆心跳

**请求参数**：
```json
{ "vehicleId": 3, "timestamp": 1699948800 }
```

**响应示例**：
```json
{ "code": 200, "data": { "ack": true } }
```

---

## 4. 管理端接口

### 4.1 GET /api/admin/dashboard —— 调度大屏数据

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "routes": [
      {
        "routeId": 1,
        "routeName": "1号线",
        "stations": [
          {"stationId": 101, "name": "北门", "flow": 18, "flowTrend": "up"},
          {"stationId": 102, "name": "图书馆", "flow": 5, "flowTrend": "stable"}
        ],
        "vehicles": [
          {"vehicleId": 1, "load": 7, "capacity": 13, "status": "running"},
          {"vehicleId": 2, "load": 0, "capacity": 13, "status": "idle"}
        ]
      }
    ],
    "alerts": [
      {"alertId": 7001, "type": "CUSUM", "stationId": 101, "message": "客流突变", "timestamp": 1699948800}
    ]
  }
}
```

### 4.2 POST /api/admin/observation —— 人工观测上报

**请求参数**：
```json
{
  "stationId": 101,
  "direction": 1,
  "observedFlow": 22,
  "note": "课间聚集"
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "observationId": 8001,
    "fusedFlow": 20,
    "sources": ["scan:0.5", "driver:0.3", "observe:0.2"]
  }
}
```

### 4.3 POST /api/admin/task/create —— 创建调度任务

**请求参数**：
```json
{
  "stationId": 101,
  "direction": 1,
  "type": "reinforce",
  "autoAssign": true
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "taskId": 3001,
    "state": "assigned",
    "assignedVehicleId": 3,
    "score": 0.82,
    "eta": 360
  }
}
```

### 4.4 GET /api/admin/assign —— 多车分配方案

**请求参数**（Query）：
| 字段 | 类型 | 说明 |
|------|------|------|
| stationIds | String | 逗号分隔站点 ID |
| direction | Int | 方向 |

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "scheme": [
      {"vehicleId": 3, "stationId": 101, "cost": 180.0, "eta": 360},
      {"vehicleId": 5, "stationId": 103, "cost": 210.0, "eta": 420}
    ],
    "totalCost": 390.0,
    "algorithm": "min_cost_max_flow"
  }
}
```

### 4.5 GET /api/admin/cusum —— CUSUM 状态查询

**请求参数**（Query）：
| 字段 | 类型 | 说明 |
|------|------|------|
| stationId | Long | 站点 ID |
| window | Int | 时间窗口（分钟） |

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "stationId": 101,
    "mu0": 12.0,
    "k": 3.0,
    "h": 15.0,
    "points": [
      {"t": 1699948800, "xt": 18, "st": 3.0, "alert": false},
      {"t": 1699948860, "xt": 25, "st": 13.0, "alert": false},
      {"t": 1699948920, "xt": 31, "st": 26.0, "alert": true}
    ],
    "lastAlert": 1699948920
  }
}
```

### 4.6 GET /api/admin/vehicle/list —— 车辆列表

**响应示例**：
```json
{
  "code": 200,
  "data": [
    {"vehicleId": 1, "plate": "贵A·001", "routeId": 1, "status": "running", "load": 7, "capacity": 13},
    {"vehicleId": 2, "plate": "贵A·002", "routeId": 1, "status": "idle", "load": 0, "capacity": 13}
  ]
}
```

### 4.7 GET /api/admin/alert/list —— 预警列表

**请求参数**（Query）：
| 字段 | 类型 | 说明 |
|------|------|------|
| type | String | CUSUM/FULL/OFFLINE/TIMEOUT |
| startTime | Long | 起始时间戳 |
| endTime | Long | 结束时间戳 |

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "total": 3,
    "alerts": [
      {"alertId": 7001, "type": "CUSUM", "stationId": 101, "message": "客流突变+19", "timestamp": 1699948920, "handled": false}
    ]
  }
}
```

### 4.8 GET /api/admin/event/stream —— 事件流水

**响应示例**：
```json
{
  "code": 200,
  "data": [
    {"eventId": 9001, "vehicleId": 3, "stationId": 101, "type": "arrive", "count": 0, "timestamp": 1699948800},
    {"eventId": 9002, "vehicleId": 3, "stationId": 101, "type": "board", "count": 5, "timestamp": 1699948830}
  ]
}
```

### 4.9 GET /api/admin/snapshot —— 站点客流快照

**请求参数**（Query）：
| 字段 | 类型 | 说明 |
|------|------|------|
| stationId | Long | 站点 ID |
| direction | Int | 方向 |
| limit | Int | 条数 |

**响应示例**：
```json
{
  "code": 200,
  "data": [
    {"snapshotId": 6001, "stationId": 101, "flow": 18, "source": "scan+driver", "timestamp": 1699948800},
    {"snapshotId": 6002, "stationId": 101, "flow": 20, "source": "scan+driver+observe", "timestamp": 1699948830}
  ]
}
```

---

## 5. AI 服务接口

**Base URL**：`http://localhost:8000`

### 5.1 POST /predict —— 短时客流预测

**请求参数**：
```json
{
  "stationId": 101,
  "direction": 1,
  "history": [12, 14, 13, 15, 18, 25, 31],
  "horizon": 15,
  "schedulePrior": 13.0
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "stationId": 101,
    "predictions": [
      {"t": 1699948980, "value": 32.4, "confidence": 0.85},
      {"t": 1699949040, "value": 28.1, "confidence": 0.78},
      {"t": 1699949100, "value": 22.0, "confidence": 0.70}
    ],
    "method": "kalman_with_schedule_prior"
  }
}
```

### 5.2 POST /cusum —— CUSUM 突变检测

**请求参数**：
```json
{
  "stationId": 101,
  "observations": [12, 14, 13, 15, 18, 25, 31],
  "mu0": 13.0,
  "k": 3.0,
  "h": 15.0
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "stationId": 101,
    "stats": [
      {"index": 0, "xt": 12, "st": 0.0, "alert": false},
      {"index": 4, "xt": 18, "st": 2.0, "alert": false},
      {"index": 6, "xt": 31, "st": 26.0, "alert": true}
    ],
    "alertCount": 1,
    "firstAlertIndex": 6
  }
}
```

### 5.3 POST /assign —— 多车协同分配

**请求参数**：
```json
{
  "vehicles": [
    {"vehicleId": 1, "currentStationSeq": 3, "load": 11, "capacity": 13, "routeId": 1},
    {"vehicleId": 3, "currentStationSeq": 7, "load": 8, "capacity": 13, "routeId": 1},
    {"vehicleId": 5, "currentStationSeq": 2, "load": 4, "capacity": 13, "routeId": 2}
  ],
  "demands": [
    {"stationId": 101, "stationSeq": 5, "routeId": 1, "flow": 25},
    {"stationId": 103, "stationSeq": 8, "routeId": 1, "flow": 18}
  ],
  "weights": {"alpha": 0.5, "beta": 0.3, "gamma": 0.2}
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "scheme": [
      {"vehicleId": 3, "stationId": 101, "cost": 180.0, "eta": 360},
      {"vehicleId": 1, "stationId": 103, "cost": 450.0, "eta": 900}
    ],
    "totalCost": 630.0,
    "unassigned": [],
    "algorithm": "min_cost_max_flow"
  }
}
```

### 5.4 POST /kalman —— 卡尔曼滤波融合

**请求参数**：
```json
{
  "stationId": 101,
  "prior": 13.0,
  "observations": [14, 18, 12, 20, 17],
  "Q": 2.0,
  "R": 5.0,
  "P0": 1.0
}
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "stationId": 101,
    "steps": [
      {"step": 0, "prior": 13.0, "obs": 14, "K": 0.17, "estimate": 13.17, "P": 0.83},
      {"step": 1, "prior": 13.17, "obs": 18, "K": 0.24, "estimate": 14.37, "P": 1.24}
    ],
    "finalEstimate": 16.2,
    "finalP": 1.45
  }
}
```

### 5.5 GET /ablation —— 消融实验结果

**请求参数**（Query）：
| 字段 | 类型 | 说明 |
|------|------|------|
| scenario | String | 场景名（如 morning_peak） |

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "scenario": "morning_peak",
    "strategies": [
      {
        "name": "S1_仅课表",
        "avgWait": 720,
        "overflowRate": 0.42,
        "dispatchCount": 0
      },
      {
        "name": "S2_课表+卡尔曼",
        "avgWait": 540,
        "overflowRate": 0.28,
        "dispatchCount": 12
      },
      {
        "name": "S3_课表+卡尔曼+CUSUM",
        "avgWait": 360,
        "overflowRate": 0.15,
        "dispatchCount": 28
      },
      {
        "name": "S4_全策略(卡尔曼+CUSUM+多车分配)",
        "avgWait": 210,
        "overflowRate": 0.05,
        "dispatchCount": 45
      }
    ],
    "conclusion": "全策略较仅课表平均等待下降70.8%，滞留率下降88.1%"
  }
}
```

---

## 6. 接口清单汇总

### 6.1 乘客端

| 方法 | URL | 说明 |
|------|-----|------|
| POST | /api/passenger/checkin | 扫码签到 |
| GET | /api/passenger/eta | 查询 ETA |
| POST | /api/passenger/cancel | 取消签到 |

### 6.2 司机端

| 方法 | URL | 说明 |
|------|-----|------|
| POST | /api/driver/login | 登录 |
| POST | /api/driver/shift/start | 设置当日运行 |
| POST | /api/driver/arrive | 到站点击 |
| POST | /api/driver/board | 上车点击 |
| POST | /api/driver/alight | 下车点击 |
| POST | /api/driver/calibrate | 人数校准 |
| POST | /api/driver/task/accept | 接单 |
| POST | /api/driver/task/reject | 拒单 |
| POST | /api/driver/heartbeat | 心跳 |

### 6.3 管理端

| 方法 | URL | 说明 |
|------|-----|------|
| GET | /api/admin/dashboard | 调度大屏 |
| POST | /api/admin/observation | 人工观测 |
| POST | /api/admin/task/create | 创建任务 |
| GET | /api/admin/assign | 多车分配 |
| GET | /api/admin/cusum | CUSUM 状态 |
| GET | /api/admin/vehicle/list | 车辆列表 |
| GET | /api/admin/alert/list | 预警列表 |
| GET | /api/admin/event/stream | 事件流水 |
| GET | /api/admin/snapshot | 客流快照 |

### 6.4 AI 服务

| 方法 | URL | 说明 |
|------|-----|------|
| POST | /predict | 短时预测 |
| POST | /cusum | CUSUM 检测 |
| POST | /assign | 多车分配 |
| POST | /kalman | 卡尔曼融合 |
| GET | /ablation | 消融实验 |

### 6.5 WebSocket

| 通道 | 说明 |
|------|------|
| /ws/admin/dashboard | 大屏实时推送 |
| /ws/driver/{vehicleId} | 司机任务推送 |
| /ws/admin/alert | 预警推送 |