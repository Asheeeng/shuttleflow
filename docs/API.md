# REST API V0.1

基础路径 `/api`，JSON请求与响应。成功 `{ "code":"OK", "message":"操作成功", "data":... }`；错误 `{ "code":"错误码", "message":"中文说明", "data":null }`。HTTP状态200/400/401/403/404/405/409/415/500分别对应成功、参数/业务输入错误、未登录、无权限/CSRF错误、未找到、方法不支持、冲突、媒体类型错误、非预期服务错误。

## 会话与CSRF

1. GET `/auth/csrf`，保存Cookie，读取data.token和data.headerName。
2. 所有POST加该header（默认`X-CSRF-TOKEN`）及会话Cookie；包括登录。令牌由Spring Security管理，不自行拼造。
3. POST `/auth/login`，JSON `{ "username":"passenger", "password":"Demo123456!" }`。成功返回用户id、username、displayName、role，轮换session ID。
4. GET `/auth/me` 获取当前身份。POST `/auth/logout` 注销并使会话失效；之后重新获取CSRF令牌才能再次登录。

GET `/health`无需登录，返回进程UP；正常启动已执行数据库连接、迁移与实体校验。

## 线路（所有已登录身份）

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | /routes | 全部线路及有序stops |
| GET | /routes/{id} | 指定线路、direction、demo与stops |

stop返回id、routeId、name、stopOrder、secondsFromPrevious。所有演示实体ID以查询为准，不假设固定数字。

## 乘客（仅PASSENGER）

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| POST | /passenger/requests | 创建候车：routeId、originStopId、destinationStopId、partySize（1..20，界面默认1） |
| GET | /passenger/requests/current | 本人有效请求；无有效请求时data=null |
| GET | /passenger/requests | 本人历史，创建时间倒序；查询会将过期记录归档 |
| POST | /passenger/requests/{id}/cancel | 取消本人有效请求；无需body |
| POST | /passenger/requests/{id}/board | body `{}`确认全部剩余；或`{"count":1}`部分上车 |
| GET | /passenger/eta?routeId=...&originStopId=...&destinationStopId=... | 上车站下一辆同向车辆ETA |

候车响应字段：id、routeId、routeName、originStopId、originName、destinationStopId、destinationName、partySize、boardedCount、remaining、createdAt、expiresAt、status。status为WAITING/BOARDED/CANCELLED/EXPIRED。

ETA返回status、message、taskId、vehicleCode、estimatedArrivalAt、remainingSeconds、lastReportAt、serverTime、demo。仅AVAILABLE具有精确剩余秒数；其他状态见设计文档。无车辆是正常业务结果，HTTP200且status=NO_RUNNING_VEHICLE。请求在动作到达服务器前已经超时，会返回EXPIRED，不会误变为已上车/已取消。

## 司机（仅DRIVER）

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | /driver/task | 本人绑定任务、车辆、容量、最近上报、人数确认状态、nextStop |
| POST | /driver/arrivals | 上报严格下一站和人数 |
| GET | /driver/demand | 运行方向上严格后续站点有效需求 |

到站示例（stopId从task.nextStop.id获取）：

```json
{"stopId":2,"countsConfirmed":true,"boarded":2,"alighted":1}
```

无法确认人数：

```json
{"stopId":2,"countsConfirmed":false,"boarded":null,"alighted":null}
```

上一站人数未确认后恢复确认，额外提交`occupancyBefore`（本站上下车前经重新核实的实际人数）；已确认状态不允许随意覆盖这个值。车辆及司机身份不接收客户端指定字段，始终从登录用户与绑定任务推导。

任务响应含occupancy、occupancyConfirmed、occupancyUpdatedAt、remainingCapacity；未确认时剩余容量为null，occupancy是最近有效历史值。上报不会自动更改任何乘客候车状态。

## 管理员（仅ADMIN）

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | /admin/overview | runningVehicles、activeRequests、waitingPeople、recentArrivals（24h）、requestStates、updatedAt、demoResetEnabled |
| GET | /admin/demand | 所有站点stopName、routeName、waitingPeople、requestCount、updatedAt（本次统计时刻） |
| GET | /admin/vehicles | 全车辆，包括无任务待命车；occupancy及更新时间、最近站点、剩余容量 |
| GET | /admin/arrivals | 最近50条事件，按服务器reportedAt和id倒序 |
| GET | /admin/requests | 最近100条候车记录，过期状态按服务器当前时间计算 |
| POST | /admin/demo/reset | 仅demo且非prod/production可用；`{"confirmation":"RESET DEMO"}` |

演示重置清空运营候车和到站记录，重建一个当前有效A站任务；不删除账号、线路、站点和车辆。生产环境不注册初始化和重置Bean，访问重置返回404。

## 主要错误码

| 错误码 | HTTP | 说明 |
| --- | --- | --- |
| VALIDATION_ERROR | 400 | 必填、范围、参数格式或请求JSON错误 |
| INVALID_JOURNEY | 400 | 跨线路、同站或逆向行程 |
| ACTIVE_REQUEST_EXISTS | 409 | 已有有效候车请求 |
| REQUEST_FINISHED | 409 | 终态请求不可再次修改 |
| INVALID_BOARDING_COUNT | 400 | 超过剩余候车人数 |
| INVALID_STOP_SEQUENCE | 400 | 非本线路或不是下一站 |
| INVALID_OCCUPANCY / CAPACITY_EXCEEDED | 400 | 下车超过原人数/人数为负/超载 |
| OCCUPANCY_RECONFIRM_REQUIRED | 400 | 上次未确认人数，需要先核实 |
| COUNTS_REQUIRED / UNCONFIRMED_COUNTS | 400 | 人数确认开关与输入不一致 |
| UNEXPECTED_OCCUPANCY_OVERRIDE | 400 | 已确认人数时不能覆盖原人数 |
| NO_TASK / NOT_RUNNING | 409 | 无绑定任务或任务未运行 |
| BAD_CREDENTIALS / UNAUTHENTICATED | 401 | 密码错误或未登录 |
| FORBIDDEN | 403 | 角色不符或CSRF不正确 |
| CONFIRMATION_REQUIRED | 400 | 未明确确认重置 |
| NOT_FOUND | 404 | 记录不存在、不属于本人、或接口未注册 |
| DATA_CONFLICT | 409 | 数据库约束冲突，操作未提交 |
| METHOD_NOT_ALLOWED / UNSUPPORTED_MEDIA_TYPE | 405 / 415 | 方法或媒体类型不支持 |
| INTERNAL_ERROR | 500 | 意外服务错误，细节仅记录在服务日志 |

可执行的真实会话/CSRF/角色与业务示例参见`scripts/smoke.py`。该脚本不依赖第三方Python库，必须带`--reset-demo`才执行。


## 新增调度与车辆管理API

见 [V0.2使用与设计说明](V0.2.md)，原V0.1接口和业务约束继续有效。
