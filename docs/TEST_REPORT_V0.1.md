# MVP V0.1 实际测试报告

验证时间：2026-11-12 17:07:33 CST。最终无失败测试；下述结果均由本机实际执行取得。

## 环境

macOS 15.5 / Apple Silicon；Java 17.0.18；Maven 3.9.16；Node 26.5.1；npm 11.17.0；MySQL 8.4.11（独立Homebrew实例127.0.0.1:3307）；Spring Boot 3.5.7；Vue 3.5.45；Vite 7.3.8；Element Plus 2.14.8；Chrome由Playwright驱动。npm锁文件已纳入源码。

## 最终结果

| 验证项 | 实际命令/方式 | 结果 |
| --- | --- | --- |
| 后端单元/集成/权限/生产隔离 | `cd backend && mvn -B package` | 30通过，0失败，0错误，0跳过；jar打包成功 |
| ETA单元测试 | EtaServiceTest | 8/8 |
| 候车/司机/认证集成测试 | BusinessIntegrationTest（H2仅用于测试，执行Flyway） | 20/20 |
| 生产环境禁用演示功能/错误格式 | ProductionIsolationTest | 2/2 |
| 源码格式检查 | `npm run format:check` | 通过 |
| 前端生产构建 | `npm run build` | 通过，1681模块 |
| 真实HTTP+MySQL业务回归 | `python3 scripts/smoke.py --reset-demo` | 32/32断言通过 |
| Chrome真实三端联动 | `npm run test:e2e` | 2/2通过，最后一次7.5秒 |
| 移动端布局 | Chrome 390×844视口；桌面1440×1000 | 三端无横向页面溢出、无pageerror；管理员表格允许内部横向滚动 |
| 独立MySQL首次迁移/JPA校验 | 实际启动MySQL后运行后端 | 成功 |
| 可执行jar启动 | `./scripts/backend.sh --jar` | 成功监听8080，连接MySQL |
| 幂等初始化/重启持久化 | jar重启前后比较7张业务表记录数 | 完全一致，无重复初始化 |
| Compose语法与变量配置 | `docker compose config --quiet` | 通过 |
| Compose实际启动 | 本机Docker守护进程未运行 | 未执行；数据库实际验收采用Homebrew MySQL，未以H2代替 |
| 脚本语法 | `bash -n`、Python编译检查 | 通过 |

原始输出：[后端打包与测试](verification/backend-package.log)、[前端构建](verification/frontend-build.log)、[真实HTTP](verification/http-smoke.log)、[浏览器测试](verification/browser-e2e.log)、[机器可读结果](verification/results.json)。

## 核心场景与边界覆盖

A：实际登录乘客、C→D创建WAITING、管理员C站增加1人、重复请求409；真实MySQL下4个并发登录会话同时创建，仅1个成功、3个409。

B：初始A站10/20人，司机B站下车1人上车2人，事务提交后11/20、最近站点B、下一站C；乘客C站ETA从7分钟变为3分钟；管理员载客量同步；请求仍为WAITING，不自动上车。

C：乘客确认上车→BOARDED，管理端有效需求归零，历史保留；取消请求→CANCELLED。多人部分上车按剩余人数统计。

D：逆向/同站行程、重复/跨线路/跳站上报、超额下车、超载、未确认人数后的重新核实、无登录、乘客越权、管理员冒充司机、CSRF缺失、错误密码、重置未确认均有可解释结果。超载拒绝后任务及事件数量不变。

时间边界使用后端测试注入的可控Clock验证：请求达到10分钟时即使定时任务未执行也不计入需求；定时清理和迟到上车均不会复活终态。ETA覆盖08:00从A出发、08:02看D剩10分钟，B→C重新计算、15分钟精确过期、预计到站已过不保持0、停运无车辆、上报缺失/未来时间戳、多车择早、目标已驶过。

## 开发中发现并修正的问题

1. 首次真实MySQL迁移失败：跨字段CHECK写成列级约束。改成表级CHECK后，仅重建本次新建的项目演示库；最终Flyway迁移与MySQL业务回归成功。保留H2和真实MySQL两层验证。
2. 首轮一个浏览器路由用例在登录响应完成前跳转，导致请求被中断。补充登录完成的URL等待，随后用例通过。
3. 最后构建时覆盖正在运行的同名jar，旧进程发生类加载错误，两个浏览器登录用例失败。停止旧进程，以最终jar重新启动后，重新顺序执行真实HTTP32项与浏览器2项，全部通过。README已说明重新打包前先停jar服务，当前进程后端日志无异常。

以上为已解决问题；最终结果不包含失败用例，也没有将未运行项目记为通过。

## 截图与交付状态

[管理员](screenshots/admin.png)、[乘客手机页](screenshots/passenger-mobile.png)、[司机手机页](screenshots/driver-mobile.png)均来自真实接口联动流程。截图是过程中的候车与到站状态；测试完成后环境恢复至A站10/20人、无有效候车请求，便于重新演示。

## 验证范围与已知限制

未进行实体手机联网、生产部署、压力/渗透、外部硬件或AI模型测试。页面使用10秒轮询；历史接口MVP仅返回最近100/50条，未分页。MySQL8.4下Flyway会发出新于已测试版本的兼容性提示，但本机迁移、持久化、重启与并发操作已实测通过。Compose仅配置校验，未实际启动。高级调度、真实GPS/支付/统一认证、复杂AI与管理员CRUD不属于V0.1。
