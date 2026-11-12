# V0.2 实际测试报告

最终验证时间：2026-11-12 18:59:07 CST。当前版本最终无失败、无跳过的自动化测试。原版本报告保存在 [V0.1报告](TEST_REPORT_V0.1.md)。

## 环境与结果

macOS 15.5 / Apple Silicon；Java 17.0.18；Maven 3.9.16；Node 26.5.1；npm 11.17.0；真实MySQL8.4.11（项目独立实例127.0.0.1:3307）；Spring Boot3.5.7；Vue3、Vite7、Element Plus；本机Chrome通过Playwright执行。

| 验证项 | 实际执行 | 最终结果 |
| --- | --- | --- |
| 后端编译、JUnit与打包 | `cd backend && mvn -B package` | **56通过，0失败，0错误，0跳过**，V0.2可执行jar成功 |
| 原候车、司机、权限集成 | BusinessIntegrationTest | 保留20/20 |
| 原ETA纯计算 | EtaServiceTest | 保留8/8 |
| 原生产环境隔离与错误处理 | ProductionIsolationTest | 保留2/2 |
| 新多车容量纯计算 | CapacityServiceTest | 新增5/5 |
| 新调度、车辆、并发与审计集成 | DispatchIntegrationTest | 新增21/21 |
| 原HTTP/MySQL回归 | `python3 scripts/smoke.py --reset-demo` | **32/32** |
| 新HTTP/MySQL闭环与真实超时 | `python3 scripts/smoke-v02.py --reset-demo --check-timeout` | **36/36** |
| Chrome端到端 | `cd frontend && npm run test:e2e` | **6/6**，最后一轮21.4秒 |
| 前端格式、生产构建 | `npm run format:check`、`npm run build` | 通过，1683模块 |
| 原数据库升级 | 在原MySQL库执行Flyway V2、V3，V1保持不变 | 3条迁移成功，未重建原业务表 |
| 原测试保存证明 | 比较升级前源码备份 | V1迁移、原3个测试类、原浏览器用例和原HTTP脚本均字节一致 |
| 脚本语法 | Python编译、Shell语法检查 | 通过 |

可核验原始输出：[后端](verification/v02/backend-package.log)、[前端构建](verification/v02/frontend-build.log)、[旧HTTP](verification/v02/http-v01-regression.log)、[新HTTP](verification/v02/http-v02-dispatch.log)、[浏览器](verification/v02/browser-e2e.log)、[迁移](verification/v02/mysql-migrations.txt)、[原文件哈希](verification/v02/preserved-v01.json)、[结构化结果](verification/v02/results.json)。

## 覆盖的业务与异常

- 原有候车创建、取消、部分/全部上车、10分钟过期、并发防重复、所有角色权限及CSRF、司机到站事务、超载回滚、ETA正常/过期/多车择早全部保留。
- 多车同站先下后上、共享需求池、上游上客占座、下客释放座位、预测不超载、超过预测窗口/到站时刻已过不计入、候选车途中上游需求扣减。
- 高峰夹具B站2人、C站30人，两运行车可承接C站12人、缺口18人；候选02先于04，检修车05排除；待接单占位不伪装已运行。
- 同车及不同车并发确认仅建立一个覆盖该缺口的任务；真实MySQL同样执行不同车辆并发，结果一个200、一个409。
- 管理员确认派单、司机接单前核实人数、满载或超载拒绝、无权限司机拒绝访问、重复接单拒绝、派单期间修改车辆状态拒绝。
- 拒绝立即升级、管理员顺延到下一候选、原任务保留终态与父子链、无候选明确升级、同一拒绝任务不能重复顺延。
- 司机严格按序报B、C站，实际载客量更新，支援站到站同一事务将任务COMPLETED。候车请求不因此自动上车。
- HTTP真实等待默认2分钟服务器期限，未修改数据库或服务器时钟：任务TIMED_OUT、释放占位、司机仍待命、迟到接单409；管理员可记录人工处理结果和身份。
- 集成测试使用可控Clock覆盖应答期限精确边界、已接受后预计到达+5分钟仍无上报的执行超时；执行超时不伪造车辆停运。
- 当日RUNNING/STANDBY/UNAVAILABLE管理、不可用排除、次日不继承昨日运行状态、预计下客计划总人数校验及到站消费。

## 浏览器六条实际流程

1. 原乘客候车、司机到B站11/20、ETA由7变3分钟、管理端同步、确认上车归零。
2. 原取消候车、受保护角色路由、退出登录。
3. 高峰初始化、预警与推荐、02派单、driver2拒绝、管理员顺延04、driver4接受、B/C依次报站、调度完成。
4. 当日车辆状态编辑为不可用、填写原因保存、360px管理员窄屏检查。
5. 360px乘客底部一键候车实际创建、管理端需求增加；司机预计下客计划实际保存。
6. 管理员撤回待接单、重新派发、司机拒绝、管理员记录人工处理结果，待处理标记消失并显示留痕。

手机视口360×800、390×844，桌面1440×1000；检查无横向文档溢出，管理表格内部允许横向滚动。主调度流程收集pageerror为零。图片经人工查看，未以静态假页面代替接口。

## 关键页面截图

- [乘客手机查询与一键候车](screenshots/v02/passenger-mobile.png)
- [乘客已候车状态](screenshots/v02/passenger-waiting-mobile.png)
- [司机手机车辆工作台](screenshots/v02/driver-mobile.png)
- [司机待接单通知](screenshots/v02/driver-inbox-mobile.png)
- [管理员桌面预警与调度总览](screenshots/v02/admin-desktop.png)
- [管理员任务完成记录](screenshots/v02/dispatch-completed.png)

截图来自真实业务中间状态，非硬编码计数。手机全页截图中的固定底栏位于截图时的视口底部；实际浏览器始终固定在手机视口底边。测试结束后环境已重新初始化高峰场景，当前状态见 [最终演示快照](verification/v02/final-demo-state.json)。

## 发现并修正的问题

1. 新增带事务代理的服务被直接读取实例字段，生产隔离回归的统计调用出现空引用。改为注入依赖、调用服务方法，原30项回归恢复通过。
2. MySQL RR事务在等待线路锁前读过快照，可能看不到刚提交的派单；同车虽有数据库唯一约束兜底，不同车可能重复覆盖。连接改为READ_COMMITTED，保留线路/车辆锁和唯一约束，新增不同车辆并发测试，H2及真实MySQL均通过。
3. 浏览器车辆选择框用例点击内部readonly输入时被占位层拦截。改为点击实际可见的选择框容器；没有用强制点击掩盖问题。
4. 保存对话框后立即缩窄视口，在关闭动画完成前检测产生瞬时溢出失败。增加对话框关闭与布局稳定等待，360px检查通过；没有通过隐藏横向内容来绕过断言。
5. 候选车目标站余座最初仅用驻车点空余座位，可能忽略途中上客。改用同一多车/多站预测器计算，新增上游20人需求导致目标站余座降为3座的测试。

以上均已修正后重跑；最终报告不将之前失败或未执行项目计为通过。

## 未验证与剩余边界

未做实体手机联网、生产部署、压力/渗透、硬件/GPS、机器学习预测测试。Docker守护进程仍未启动，Compose未实际拉起，本次全部真实数据库验证使用Homebrew MySQL8.4.11。Flyway对MySQL8.4的新版本提示仍存在，实际迁移及业务均通过。全量Element Plus前端首包约1.15MB（gzip381KB），生产分发前应拆包；10秒轮询、历史未分页、绑定预置、单向不自动折返等边界见[V0.2说明](V0.2.md)。
