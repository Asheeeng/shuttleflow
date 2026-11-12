# MVP V0.1 设计说明

## 架构与模块

B/S前后端分离，单体后端、单一MySQL。所有角色共享数据库，前端通过Axios请求REST接口；开发代理负责转发，不另建角色服务。选择Spring Data JPA统一访问数据，Flyway负责结构版本，`ddl-auto=validate`禁止应用自动修改表。

- `AuthController` / `SecurityConfig`：BCrypt校验、HttpOnly会话Cookie、session ID轮换、CSRF和服务端角色校验。
- `CatalogService`：固定方向线路、站序和行程有效性。
- `WaitingService`：创建/取消/上车/过期、并发控制、有效需求聚合。
- `DriverService`：绑定任务推导身份与车辆、严格下一站校验、载客量、到站事件原子提交。
- `EtaService`：独立的无GPS估算策略，静态纯计算入口便于替换与单元测试。
- `DemoService`：限demo环境的幂等初始化和显式确认重置。
- `BusinessController`：角色REST接口、Bean Validation和统一响应；`ErrorAdvice`处理业务及参数错误。

浏览器端路由守卫改善体验；接口权限完全由Spring Security执行。认证不使用LocalStorage令牌，密码哈希从不返回给前端。前后端默认只监听127.0.0.1，适用于本机演示。

## 数据表

| 表 | 关键字段 | 关系与约束 |
| --- | --- | --- |
| app_user | id、username、password_hash、display_name、role | 用户名唯一；角色限定PASSENGER/DRIVER/ADMIN |
| shuttle_route | id、name、direction、demo | 一个ID代表一个固定运行方向，反向应为另一条线路 |
| route_stop | id、route_id、name、stop_order、seconds_from_previous | route_id+stop_order唯一；首站0秒，其余表示从上一站到本站的历史平均耗时 |
| vehicle | id、code、capacity | 车辆号唯一、容量为正 |
| run_task | driver_id、vehicle_id、route_id、status、last_stop_id、last_report_at、occupancy、occupancy_confirmed、occupancy_updated_at | 每名司机和每辆车至多一个当前任务；外键关联身份、车辆、线路与站点 |
| wait_request | user_id、route_id、origin_stop_id、destination_stop_id、party_size、boarded_count、created_at、expires_at、status、active_user_id、renewal_count | active_user_id可空唯一；人数边界和状态关联CHECK；终态保留历史；预留续期次数 |
| arrival_event | task_id、driver_id、stop_id、reported_at、boarded、alighted、occupancy_after、counts_confirmed | 服务器时间；人数未确认则上下车数为NULL；关联任务及实际司机 |

共7张业务表，Flyway额外使用版本历史表。业务主键自增，无须依赖固定ID。演示重置保留基础表，重新生成任务和到站记录；前端不写死ID。

## 候车状态与并发

允许流转：WAITING→CANCELLED、WAITING→BOARDED、WAITING→EXPIRED。多人部分上车时仍为WAITING，只有 boarded_count=party_size 时结束。

创建及用户操作在事务内先锁定 `app_user` 行。同一用户的并发提交串行执行。创建前同步清理该用户过期WAITING请求，释放 `active_user_id`。新WAITING写入active_user_id=user_id；所有终态设为NULL。数据库唯一索引再次阻止同一用户同时存在多个有效占位，CHECK防止状态和占位字段不一致。并发并不依赖前端按钮禁用。

每30秒执行过期批处理；有效需求查询始终执行 `status='WAITING' AND expires_at>server_now`，在调度尚未运行的窗口也不会误计。统计人数为party_size-boarded_count。查询本人记录会同步归档过期请求；迟到的取消或上车动作返回EXPIRED，不会复活请求。

## 到站与人数一致性

司机ID来自认证会话，由任务找到车辆，客户端只提供站点和人数，不能传入任意司机或车辆身份。使用任务行悲观写锁，要求目标站属于该任务线路且为严格下一站（不可跳站/重复）。在一个事务内校验并更新任务与插入到站记录，异常整体回滚。

`new = old - alighted + boarded`。验证old与结果都在0..capacity、下车不超过old、人数非负。计算使用long中间值防止溢出。Bean Validation限制输入人数，Service再次验证核心不变量。

未确认人数的上报只更新站点和到站时间，保留最近有效occupancy及occupancy_updated_at，置occupancy_confirmed=false；返回的剩余容量为NULL。后续恢复确认时，须输入经现场核实的上下车前人数，再计算本次变动。载客量不会由候车请求自动推断，避免把系统需求当成实际人数。

## ETA规则

模拟线路A→B→C→D：相邻历史耗时240、180、300秒，全部保存在route_stop。`EtaService.next`先验证同向有效行程，按route_id筛选任务；`estimate`使用最近站序累加到上车站的站间时间，再以服务器当前时间求剩余秒数。选择预计到达时间最早的有效运行车辆。

- 仅RUNNING参与，待命/停运不参与。
- 无上报、未来时间戳或上报达到15分钟，返回STALE_REPORT。
- 已上报目标站或已驶过，不再预测该站，返回STATION_ALREADY_REPORTED。
- 预计时刻<=现在但没有新上报，返回AWAITING_REPORT，remainingSeconds为NULL，避免无限0分钟。
- 精确可估算时返回AVAILABLE及服务器时间、预计时刻、剩余秒数；页面按服务器时差倒计时，到0立即变为等待更新。
- 无运行车辆返回NO_RUNNING_VEHICLE。

司机最新上报后下一次查询从新站点重新累加。乘客查看的是上车站ETA，目的站用于同方向校验，不把目的地当作上车ETA目标。没有GPS、实时交通或停站时间，因此只作演示估算，不提供精确位置或真实运营承诺。

## 扩展边界

当前单向线路和任务不自动折返；后续可增加班次及方向建模。未来可在EtaService前增加历史站间采样，在独立调度策略模块消费有效需求，而无需修改候车状态机。优先加入管理维护、分页与可观测性，再考虑预测模型。


## V0.2增量模块与容量算法

见 [V0.2使用与设计说明](V0.2.md)，原V0.1接口和业务约束继续有效。
