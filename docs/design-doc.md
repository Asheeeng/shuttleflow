# 概要设计与详细设计说明书

**项目名称**：校园摆渡车站点客流上报与AI智能动态调度系统
**版本**：v1.0
**日期**：2026-11-14

---

## 1. 系统架构

### 1.1 六层架构

```
┌─────────────────────────────────────────────────────┐
│  ⑥ 应用层 Application                                │
│     乘客端 / 司机端 / 管理端大屏 / 仿真台            │
├─────────────────────────────────────────────────────┤
│  ⑤ 智能层 Intelligence                               │
│     卡尔曼滤波 / CUSUM 检测 / 多车分配 / 派单评分    │
│     (Python + FastAPI 独立服务)                      │
├─────────────────────────────────────────────────────┤
│  ④ 业务层 Business                                   │
│     客流融合 / 预警引擎 / 任务调度 / 车辆管理        │
├─────────────────────────────────────────────────────┤
│  ③ 数据层 Data                                       │
│     MySQL 持久化 / Redis 缓存 / 快照管理             │
├─────────────────────────────────────────────────────┤
│  ② 接入层 Access                                     │
│     REST Controller / WebSocket / 鉴权 / 限流        │
├─────────────────────────────────────────────────────┤
│  ① 感知层 Perception                                 │
│     扫码 / 司机点击 / 人工观测 / 车辆心跳            │
└─────────────────────────────────────────────────────┘
```

| 层 | 职责 | 主要模块 |
|----|------|----------|
| 感知层 | 采集多源原始事件 | CheckinCollector, DriverEventCollector, ObservationCollector, HeartbeatCollector |
| 接入层 | 协议接入与鉴权 | RestController, WebSocketHandler, JwtFilter, RateLimiter |
| 数据层 | 存储与缓存 | PassengerMapper, VehicleMapper, TaskMapper, SnapshotService, RedisCache |
| 业务层 | 核心业务逻辑 | FlowFusionService, AlertEngine, TaskDispatchService, VehicleManager |
| 智能层 | AI 算法 | KalmanFilter, CusumDetector, MultiVehicleAssigner, DispatchScorer, Predictor |
| 应用层 | 用户交互 | 乘客 Vue3 / 司机 Vue3 / 管理大屏 Vue3 / 仿真台 Vue3 |

### 1.2 三闭环模型

```
┌─────────────────── 秒级数据闭环 (Δt = 1s) ───────────────────┐
│  感知层事件 → 接入层 → 业务层融合 → 数据层快照 → 应用层推送  │
│  目标：端到端 ≤ 2 秒，实时反映站点客流与车载载率            │
└──────────────────────────────────────────────────────────────┘

┌─────────────────── 分钟级决策闭环 (Δt = 60s) ───────────────┐
│  快照 → 智能层预测/CUSUM → 业务层预警 → 智能层分配 → 派单  │
│  目标：每分钟生成调度决策，响应客流突变                    │
└──────────────────────────────────────────────────────────────┘

┌─────────────────── 天/周级模型闭环 ────────────────────────┐
│  历史数据 → 智能层模型重训练 → 课表先验更新 → 参数调优     │
│  目标：优化先验均值 μ₀、卡尔曼 R/Q、CUSUM k/h 参数         │
└──────────────────────────────────────────────────────────────┘
```

### 1.3 数据链路

```
乘客扫码 ──┐
司機点击 ──┼──→ 事件队列(Redis) ──→ 融合服务 ──→ 站点客流快照(MySQL)
人工观测 ──┘                        │
                                     ├──→ 卡尔曼滤波 ──→ 估计值
                                     ├──→ CUSUM ───────→ 突变告警
                                     └──→ 守恒递推 ────→ 车内人数

站点客流快照 + 车内人数 ──→ 多车分配(最小费用流) ──→ 分配方案
分配方案 ──→ 派单评分 ──→ 任务创建 ──→ WebSocket推送司机端
司机响应 ──→ 任务状态机迁移 ──→ 车辆状态更新 ──→ 大屏刷新
```

---

## 2. ER 设计

### 2.1 实体关系图

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  乘客     │     │  线路     │     │  站点     │
│ Passenger │     │  Route    │     │  Station  │
├──────────┤     ├──────────┤     ├──────────┤
│ id        │     │ id        │     │ id        │
│ openid    │     │ name      │     │ route_id  │→Route
│ createdAt │     │ direction │     │ seq       │
└──────────┘     │ capacity  │     │ name      │
     │           └──────────┘     └──────────┘
     │                │                 │
     │ 1            1 │               1 │
     │                │                 │
     ▼                ▼                 ▼
┌──────────┐     ┌──────────┐     ┌──────────┐
│  请求     │     │  班次     │     │  快照     │
│ Request   │     │  Shift    │     │ Snapshot  │
├──────────┤     ├──────────┤     ├──────────┤
│ id        │     │ id        │     │ id        │
│ passenger │→P  │ route_id  │→R   │ station_id│→S
│ station_id│→S  │ vehicle_id│→V   │ direction │
│ direction │     │ direction │     │ flow      │
│ status    │     │ startTime │     │ source    │
│ createdAt │     │ status    │     │ timestamp │
└──────────┘     └──────────┘     └──────────┘

┌──────────┐     ┌──────────┐     ┌──────────┐
│  车辆     │     │  任务     │     │  事件     │
│ Vehicle   │     │  Task     │     │  Event    │
├──────────┤     ├──────────┤     ├──────────┤
│ id        │     │ id        │     │ id        │
│ plate     │     │ vehicle_id│→V   │ vehicle_id│→V
│ route_id  │→R  │ station_id│→S   │ station_id│→S
│ capacity  │     │ type      │     │ type      │
│ status    │     │ score     │     │ count     │
│ load      │     │ state     │     │ timestamp │
│ heartbeat │     │ createdAt │     └──────────┘
└──────────┘     │ timeoutAt │
                 └──────────┘
```

### 2.2 核心表结构

#### passenger（乘客）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| openid | VARCHAR(64) | 匿名标识 |
| created_at | DATETIME | 注册时间 |

#### station（站点）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| route_id | BIGINT FK | 所属线路 |
| seq | INT | 站序（去向递增） |
| name | VARCHAR(32) | 站名 |
| direction | TINYINT | 1=去向 2=回向 |

#### route（线路）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| name | VARCHAR(16) | 线路名 |
| capacity | INT | 额定容量(13) |

#### vehicle（车辆）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| plate | VARCHAR(16) | 车牌 |
| route_id | BIGINT FK | 所属线路 |
| capacity | INT | 容量 |
| status | VARCHAR(16) | offline/idle/running/full/maintenance |
| load | INT | 当前车内人数 |
| heartbeat | DATETIME | 最后心跳 |

#### shift（班次）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| route_id | BIGINT FK | 线路 |
| vehicle_id | BIGINT FK | 车辆 |
| direction | TINYINT | 方向 |
| start_time | DATETIME | 发车时间 |
| status | VARCHAR(16) | 状态 |

#### request（乘客请求/签到）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| passenger_id | BIGINT FK | 乘客 |
| station_id | BIGINT FK | 站点 |
| direction | TINYINT | 去向/回向 |
| status | VARCHAR(16) | waiting/boarded/cancelled |
| created_at | DATETIME | 签到时间 |

#### task（调度任务）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| vehicle_id | BIGINT FK | 派发车辆 |
| station_id | BIGINT FK | 目标站点 |
| type | VARCHAR(16) | dispatch/reinforce/rebalance |
| score | DECIMAL(5,2) | 派单评分 |
| state | VARCHAR(16) | 状态机枚举 |
| created_at | DATETIME | 创建时间 |
| timeout_at | DATETIME | 超时时间 |

#### event（事件流水）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| vehicle_id | BIGINT FK | 车辆 |
| station_id | BIGINT FK | 站点 |
| type | VARCHAR(16) | arrive/board/alight/checkin/observe |
| count | INT | 人数 |
| timestamp | DATETIME | 事件时间 |

#### snapshot（客流快照）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| station_id | BIGINT FK | 站点 |
| direction | TINYINT | 方向 |
| flow | INT | 融合客流估计值 |
| source | VARCHAR(32) | 融合来源 |
| timestamp | DATETIME | 快照时间 |

---

## 3. 核心算法设计

### 3.1 守恒方程（车内人数递推）

**目的**：在司机无法精确计数的前提下，递推估计各车辆车内人数。

**公式**：
```
Lᵢ(t + Δt) = max{ 0, Lᵢ(t) + Aᵢ − Bᵢ }
```
- Lᵢ(t)：车辆 i 在 t 时刻车内人数
- Aᵢ：[t, t+Δt] 区间上车人数（board 事件累加）
- Bᵢ：[t, t+Δt] 区间下车人数（alight 事件累加）
- max{0, ·}：防止负数

**实现伪码**：
```python
def conserve_update(vehicle_id, board_events, alight_events, dt):
    L = get_current_load(vehicle_id)
    A = sum(e.count for e in board_events)
    B = sum(e.count for e in alight_events)
    L_new = max(0, L + A - B)
    L_new = min(L_new, capacity)  # 不超过容量
    update_load(vehicle_id, L_new)
    return L_new
```

### 3.2 卡尔曼滤波（客流估计融合）

**目的**：融合课表先验与实时观测，输出平滑的客流估计值。

**状态方程**：
```
预测：  x̂⁻ = x̂      P⁻ = P + Q
增益：  K = P⁻ / (P⁻ + R)
更新：  x̂ = x̂⁻ + K · (z − x̂⁻)
        P = (1 − K) · P⁻
```
- x̂：客流估计值
- z：实时观测值（融合后）
- Q：过程噪声方差（默认 2.0）
- R：观测噪声方差（默认 5.0）
- K：卡尔曼增益 = P/(P+R)

**参数说明**：
- R 大 → 更信任先验（观测噪声大）
- Q 大 → 更信任观测（模型不确定性大）

### 3.3 CUSUM 突变检测

**目的**：检测客流相对于课表基线的突变，触发再调度。

**公式**：
```
Sₜ = max{ 0, Sₜ₋₁ + (xₜ − μ₀ − k) }
当 Sₜ > h → 触发突变告警，Sₜ 重置为 0
```
- xₜ：当前观测客流
- μ₀：课表先验均值（基线）
- k：容差参数（默认 3，允许的正常波动）
- h：检测阈值（默认 15）
- Sₜ：累积和统计量

**特性**：
- 对缓慢漂移不敏感，对突发跳变敏感
- 单侧检测（仅检测上升突变）

### 3.4 多车协同分配（最小费用流）

**目的**：多车多站协同分配，最小化总费用（ETA + 负载惩罚 + 空驶）。

**模型构建**：
```
节点集 V = {超级源 s} ∪ {车辆 vᵢ} ∪ {站点 dⱼ} ∪ {超级汇 t}
边集 E：
  s → vᵢ：容量 1，费用 0
  vᵢ → dⱼ：容量 1，费用 = α·ETA(vᵢ,dⱼ) + β·loadPenalty(vᵢ) + γ·emptyDist(vᵢ,dⱼ)
  dⱼ → t：容量 1，费用 0
求解：最小费用最大流
```

**费用权重**：α=0.5, β=0.3, γ=0.2

**约束**：
- 已满载车辆（load ≥ capacity）不参与
- 跨线分配费用 × 1.5 惩罚

### 3.5 派单评分模型

**目的**：对候选车辆评分，选择最优派发对象。

**公式**：
```
score = ETA_norm × 0.4 + loadRate_norm × 0.4 + status_norm × 0.2
```

**归一化**：
- ETA_norm = 1 − (ETA − ETA_min) / (ETA_max − ETA_min)
- loadRate_norm = 1 − load / capacity
- status_norm：idle=1.0, running=0.6, others=0.2

**选择**：score 最高的车辆，若 score < 0.3 则不派发（标记无车可派）

### 3.6 ETA 估计

**公式**：
```
ETA(vᵢ, dⱼ) = |seq(vᵢ.currentStation) − seq(dⱼ)| × avgInterval + trafficAdjust
```
- avgInterval：站间平均行驶时间（默认 90 秒）
- trafficAdjust：高峰时段 × 1.3

---

## 4. 任务状态机

### 4.1 状态定义

```
                         ┌─────────── timeout(120s) ──────────┐
                         ▼                                     │
  pending ──assign──→ assigned ──accept──→ accepted ──start──→ executing ──complete──→ completed
                         │                │
                         │ reject         │ reject
                         ▼                ▼
                      rejected         rejected
                         │                │
                         └─── escalate ──┘
                                  │
                                  ▼
                             escalated (转人工/重派)
```

### 4.2 状态迁移表

| 当前状态 | 事件 | 目标状态 | 动作 |
|----------|------|----------|------|
| pending | assign | assigned | 推送司机端，启动 120s 超时计时 |
| assigned | accept | accepted | 记录接受时间 |
| assigned | reject | rejected | 触发 escalate |
| assigned | timeout(120s) | timeout | 触发 escalate |
| accepted | start | executing | 车辆状态 → running |
| accepted | reject | rejected | 触发 escalate |
| executing | complete | completed | 车辆状态 → idle，记录完成时间 |
| rejected | escalate | escalated | 重新选车派发或转人工 |
| timeout | escalate | escalated | 重新选车派发或转人工 |

### 4.3 车辆状态机

```
  offline ──login──→ idle ──dispatch──→ running ──load≥95%──→ full
     ↑                ↑                     │                     │
     │ logout         │ complete             │ load<85%            │ load<85%
     │                │                     ▼                     │
     └────────────────┴───────────────── idle ←───────────────────┘
```

---

## 5. 模块设计

### 5.1 后端模块划分

```
com.campus.shuttle
├── perception          # 感知层
│   ├── CheckinCollector
│   ├── DriverEventCollector
│   └── ObservationCollector
├── access              # 接入层
│   ├── controller
│   │   ├── PassengerController
│   │   ├── DriverController
│   │   └── AdminController
│   ├── ws
│   │   └── WebSocketHandler
│   ├── filter
│   │   └── JwtAuthFilter
│   └── config
│       └── RedisConfig
├── data                # 数据层
│   ├── entity
│   ├── mapper
│   ├── service
│   └── cache
├── business            # 业务层
│   ├── FlowFusionService
│   ├── AlertEngine
│   ├── TaskDispatchService
│   ├── VehicleManager
│   └── SnapshotService
├── intelligence        # 智能层（HTTP 调用 Python 服务）
│   ├── KalmanClient
│   ├── CusumClient
│   ├── AssignClient
│   └── PredictClient
└── model               # DTO/VO
```

### 5.2 AI 服务模块划分（Python）

```
ai_service/
├── main.py             # FastAPI 入口
├── api/
│   ├── predict.py      # POST /predict
│   ├── cusum.py        # POST /cusum
│   ├── assign.py       # POST /assign
│   ├── kalman.py       # POST /kalman
│   └── ablation.py     # GET /ablation
├── core/
│   ├── kalman.py       # 卡尔曼滤波实现
│   ├── cusum.py        # CUSUM 实现
│   ├── assign.py       # 最小费用流分配
│   ├── scorer.py       # 派单评分
│   ├── conserve.py     # 守恒方程
│   └── eta.py          # ETA 估计
└── config.py
```

---

## 6. 通信设计

### 6.1 REST 接口

见 `api-doc.md`。

### 6.2 WebSocket 通道

| 通道 | 订阅方 | 推送内容 |
|------|--------|----------|
| `/ws/admin/dashboard` | 管理端大屏 | 站点客流、车载载率、预警事件（秒级） |
| `/ws/driver/{vehicleId}` | 司机端 | 调度任务推送、任务状态变更 |
| `/ws/admin/alert` | 管理端预警中心 | CUSUM 告警、满载预警、超时事件 |

### 6.3 消息格式

```json
{
  "msgId": "uuid",
  "type": "alert|snapshot|task|status",
  "timestamp": 1699948800,
  "payload": { ... }
}
```

---

## 7. 部署架构

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  乘客端 Vue3 │     │  司机端 Vue3 │     │  管理端 Vue3 │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │ HTTP/WS           │ HTTP/WS           │ HTTP/WS
       └────────┬─────────┴───────────────────┘
                ▼
       ┌────────────────┐
       │  Spring Boot    │  ← java -jar shuttle.jar
       │  (port 8080)    │
       └───────┬────────┘
               │ HTTP
               ▼
       ┌────────────────┐
       │  FastAPI AI     │  ← uvicorn main:app --port 8000
       │  (port 8000)    │
       └───────┬────────┘
               │
       ┌───────┴────────┐
       │                │
       ▼                ▼
 ┌──────────┐    ┌──────────┐
 │  MySQL   │    │  Redis   │
 │  (3306)  │    │  (6379)  │
 └──────────┘    └──────────┘
```