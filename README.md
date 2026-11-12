# 校园摆渡车站点客流上报与AI智能动态调度系统

MVP V0.2（在V0.1工程上增量升级） · 贵州大学软件工程课程设计。本版本实现三角色 Web 应用、MySQL 持久化、候车状态流转、司机到站及容量管理、基于历史站间耗时的 ETA 和运营管理页面。所有初始化线路、车辆、站间耗时均为模拟数据，不代表贵州大学真实运营情况；本版本采用规则驱动的辅助调度，不包含深度学习或GPS。

## V0.2 产品化升级

- 乘客：公交查询布局、线路示意、下一车ETA、运行车辆状态、底部一键候车与个人记录，适配360px及390px手机视口。
- 司机：车辆工作台、下一站大按钮、上下客校验、调度通知、核实人数后接单/填写理由拒绝、预计下客计划。
- 管理员：统计卡片、站点需求与多车预计运力、预警与计算明细、候选车排序、确认派单、拒绝/超时顺延、人工处理留痕、当日车辆状态。
- 保留V0.1全部测试与业务API；仅增加V2/V3迁移，原V1迁移未改动。升级前源码和数据库备份保存在本地忽略的`.runtime/backups/`，备份不进入源码包。

详细功能、算法和操作见 [V0.2使用与设计说明](docs/V0.2.md)，最终验证及截图见 [当前测试报告](docs/TEST_REPORT.md)。

### 一键课间高峰演示

管理员登录后点击“初始化课间高峰”并确认：建立2辆运行车、2辆待命车、1辆不可用车，B站2人、C站30人有效候车；结合下客计划，C站预计承接12人、缺口18人。全部来自可重复的本地模拟夹具，界面和API明确标注来源。

管理员对推荐的DEMO-02确认派单；以`driver2`登录可接受或填写原因拒绝。拒绝后管理员点击“顺延下一候选车”，以`driver4`接受DEMO-04任务并依次到达B站、C站，支援任务在C站上报后完成。司机确认已安全停靠在驻车站并核实当前人数后才启动运行。也可等待2分钟演示应答超时，再顺延或人工标记处理。切换身份使用不同浏览器个人资料；相同浏览器标签页共享会话。

当前交付环境会初始化到高峰场景；若长时间未报站导致ETA失效或候车过期，重新初始化即可。原V0.1基础演示仍可用“重置演示数据”并输入`RESET DEMO`恢复。

## 快速访问本次已启动的环境

前端：<http://127.0.0.1:5173>（也可使用 <http://localhost:5173>，请在同一组浏览器会话中保持主机名一致）。后端：<http://127.0.0.1:8080/api/health>。项目专用 MySQL：`127.0.0.1:3307`。原有的 `3306` MySQL 未被修改。

| 身份 | 用户名 | 密码 |
| --- | --- | --- |
| 乘客 | passenger | Demo123456! |
| 司机（DEMO-01） | driver | Demo123456! |
| 备勤司机（DEMO-02） | driver2 | Demo123456! |
| 高峰运行司机（DEMO-03） | driver3 | Demo123456! |
| 高峰备勤司机（DEMO-04） | driver4 | Demo123456! |
| 管理员 | admin | Demo123456! |

密码以 BCrypt 哈希存入 `app_user.password_hash`。以上仅为本地演示账号。三个身份要同时演示，请使用三个浏览器个人资料或不同浏览器；同一浏览器的普通标签页共享登录 Cookie，切换身份会影响该浏览器的其他标签页。

## 在另一台 Mac 上启动

### 1. 环境要求

- JDK 17、Maven 3.6.3+（本项目使用系统 Maven，未使用 Maven Wrapper）。
- Node.js 22.12+，建议 Node 22 或 24 LTS；npm。
- Docker Desktop（用于运行 MySQL 8.4），或者本地 MySQL 8。
- Python 3 仅用于接口冒烟脚本及可选的本地数据库启动脚本。

检查：

```bash
java -version
mvn -version
node -v
npm -v
docker compose version
```

缺少工具时可用 Homebrew 安装：

```bash
brew install openjdk@17 maven node@22 python
brew install --cask docker
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$(brew --prefix node@22)/bin:$PATH"
open -a Docker
```

若系统未发现 Homebrew 的 JDK，按 `brew info openjdk@17` 中的提示注册 JDK，然后重新检查 `java -version`。兼容性参考：[Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Vite](https://vite.dev/guide/)。

### 2. 启动 MySQL（首选 Docker Compose）

```bash
cd /path/to/campus-shuttle
cp .env.example .env
```

编辑 `.env`，为 `MYSQL_ROOT_PASSWORD` 和 `DB_PASSWORD` 设置两个本地随机密码；保留 `SPRING_PROFILES_ACTIVE=demo`。脚本会 source 这个文件，因此包含 shell 特殊字符的值需要正确加单引号。数据库 URL 已在示例文件中加引号。

```bash
docker compose up -d --wait
docker compose ps
```

端口默认 `3307`，只绑定本机。若占用，修改 `.env` 的 `DB_PORT` 和 `DB_URL`，二者保持一致。Flyway 在后端首次启动时自动建表；演示初始化只在启用 `demo` 且用户、线路表均为空时执行，不会每次启动产生重复记录。

### 可选：不用 Docker，创建独立 Homebrew MySQL 实例

本次开发环境 Docker CLI 已安装但守护进程未运行，因此实际采用此路径完成真实 MySQL 验收。

```bash
brew install mysql@8.4
export PATH="$(brew --prefix mysql@8.4)/bin:$PATH"
cd /path/to/campus-shuttle
python3 scripts/local-mysql.py
```

**此路径不要先复制 `.env.example`。** 脚本在 `.runtime/mysql-data` 初始化独立实例，在 `127.0.0.1:3307` 启动，自动生成 `.env` 和本地 root 配置，权限均为 `0600`，均被 `.gitignore` 忽略。它不修改本机原有 MySQL。首次初始化临时无密码 root 只存在于这个新库，脚本随即设置随机密码。再次运行复用已有实例与凭据。与 Docker 路径二选一，不能同时占用3307。

停止这个项目实例：

```bash
python3 scripts/local-mysql.py --stop
```

若已有 `.env`，该脚本不会覆盖；若3307已有其他服务，不要继续初始化，先处理端口冲突。若发生进程异常，可查看 `.runtime/mysql.log`；确认本项目进程已退出后才处理残留的 `.runtime/mysql.sock`。

### 3. 启动后端

```bash
cd /path/to/campus-shuttle
./scripts/backend.sh
```

这个脚本加载根目录 `.env` 后执行 `mvn spring-boot:run`。等看到 `Tomcat started on port 8080`，再检查：

```bash
curl http://127.0.0.1:8080/api/health
```

默认只监听本机；所有时间由服务器生成，以 UTC 存储、ISO 8601 传输，浏览器按本地时区显示。

### 4. 启动前端（另开终端）

```bash
cd /path/to/campus-shuttle/frontend
npm ci
npm run dev
```

访问 <http://localhost:5173>。Vite 将 `/api` 代理至后端8080，无需跨域或暴露数据库凭据。页面登录后按角色跳转。乘客、司机使用移动端布局；管理员使用侧边栏和可横向滚动的数据表。

前端生产构建：`npm run build`；本地预览：`npm run preview`（也通过 `/api` 代理连接运行中的后端）。后端打包：先停止正在运行的jar进程，再执行 `cd backend && mvn package`，完成后重新启动；不要覆盖正在运行的同名jar。打包后可在根目录执行 `./scripts/backend.sh --jar`，脚本加载 `.env` 后用 `java -jar` 启动。

## 重复演示 A/B/C/D 场景

1. 管理员登录，点击“重置演示数据”，输入 `RESET DEMO` 并确认。会清空候车与到站记录，保留基础身份、线路和车辆，将 DEMO-01 恢复至 **A站、10/20人、当前服务器时间**。DEMO-02 保持待命。此功能仅在 `demo` 且未启用 `prod` / `production` 时注册；生产环境返回404。
2. 乘客登录，默认选择 C站→D站，提交1人候车。状态为 WAITING，管理员站点客流显示 C站1人。重复提交接口返回409；页面在有请求时显示“确认全部上车”和“取消候车”。
3. 司机登录，确认下一站B站，输入下车1人、上车2人，安全停靠确认后上报。载客人数变为11/20，下一站C站。乘客点击刷新，ETA从基于 A→C 的7分钟改为基于 B→C 的3分钟，管理端车辆信息同步。
4. 乘客确认全部上车，状态变为 BOARDED，管理员C站有效候车人数归零，历史记录保留。司机到站本身不会结束乘客请求，也不会因乘客确认再次加计车辆人数。
5. 新建请求后可演示取消。超时可等10分钟；测试使用可控服务器时钟覆盖精确边界，**没有给正常客户端提供修改时间或期限的接口**。
6. 异常演示：司机下车11人或上车12人（初始10/20时）会拒绝，车辆和事件不变；API测试还覆盖跳站、逆向行程、越权、过期ETA和无运行车辆等情形。

每个端每10秒刷新，也可以立即手动刷新。ETA只是估算：预计到达时刻已过且无新上报时暂停倒计时；最近上报达到15分钟时视为过期；已上报/已驶过的上车站不再匹配。车辆到终点后不自动循环，重复演示请重置。

## 自动化验证

```bash
# 后端：H2仅用于自动化测试，仍执行同一套Flyway迁移
cd /path/to/campus-shuttle/backend
mvn test

# 前端构建
cd ../frontend
npm ci
npm run build

# 真实HTTP + 真实MySQL，要求后端已启动；显式授权重置演示记录
cd ..
python3 scripts/smoke.py --reset-demo
python3 scripts/smoke-v02.py --reset-demo --check-timeout

# 真实Chrome三端联动，要求前后端和数据库均已启动
cd frontend
npm run test:e2e
```

浏览器测试默认使用本机 Google Chrome。没有 Chrome 时可以安装 Chrome，或执行 `npx playwright install chromium` 并在 `playwright.config.js` 删除 `channel: 'chrome'` 后运行。浏览器测试会主动重置演示数据，与接口冒烟脚本**顺序执行**，不要并行运行，以免相互覆盖业务状态。

本次V0.2验证：后端 **56项**（保留30项、新增26项）；原HTTP回归32项；新增HTTP36项，包含派单并发、闭环及真实两分钟超时；Chrome浏览器6条流程。最终执行结果以 [测试报告](docs/TEST_REPORT.md) 为准。详见 [测试报告](docs/TEST_REPORT.md)。

## 目录

```text
campus-shuttle/
├── backend/                 Spring Boot / Spring Security / JPA / Flyway / JUnit
│   └── src/main/resources/db/migration/V1__schema.sql  唯一建表来源
├── frontend/                Vue 3 / Vite / Router / Axios / Element Plus
│   ├── src/views/           Login、Passenger、Driver、Admin
│   └── tests/demo.spec.js   Chrome端到端联动测试
├── database/                建库辅助SQL、初始化说明（种子由Java服务生成哈希和当前时间）
├── docs/                    API、设计说明、测试报告、实际截图
├── scripts/                 后端启动、本地MySQL启动、真实HTTP冒烟脚本
├── docker-compose.yml       仅运行MySQL
├── .env.example             环境变量样例，无真实凭据
└── .runtime/                本地数据库及日志，自动生成，不纳入源码
```

V0.2新增`vehicle_day`（按上海时区日期保存出勤、司机、驻车点和准备时间）、`alighting_plan`（有来源的预计下客计划）、`dispatch_task`（派单、截止时间、接拒单、顺延链和处理审计），共10张业务表。

V0.1核心表：`app_user`（用户/角色/密码哈希）、`shuttle_route`（固定方向线路）、`route_stop`（有序站点及历史耗时）、`vehicle`（车辆和容量）、`run_task`（司机绑定/运行状态/最近站点/人数）、`wait_request`（候车状态与期限）、`arrival_event`（不可自动推断乘客上车的到站记录）。Flyway另维护 `flyway_schema_history`。详细字段和一致性策略见 [设计说明](docs/DESIGN.md)，接口见 [API文档](docs/API.md)。

## 版本边界与已知限制

- 未实现 AI 模型、无人确认的自动派单、真实GPS/硬件、支付、校园统一认证、复杂排班，以及线路/账号/车辆的完整CRUD（已提供当日状态调整）；这些不属于V0.1。
- 单向任务到终点结束本次演示；没有自动折返。后端支持多人及部分上车，界面提供一次确认全部剩余人员上车。
- 当前管理员需求统计和车辆页读取真实数据库，按10秒轮询，多个API不是一个跨请求的全局快照；这是MVP展示，不是高并发生产大屏。历史接口只返回最近100条候车、50条到站，暂不分页。
- 会话保存在后端内存，重启需重新登录。默认仅本机HTTP；实体手机联网访问、HTTPS、生产部署、登录限流、监控备份和压测不在本次验收范围。手机适配已用真实Chrome的390px视口验证。
- 当前Spring Boot管理的Flyway 11.7.2对MySQL8.4会打印“新于已测试版本”提示；本机MySQL8.4.11的首次迁移、JPA校验、持久化及并发HTTP业务均实际通过。升级依赖时应保留这套MySQL冒烟回归。
- 本机Docker守护进程未开启，Compose文件已提供但未实际拉起验收；本次数据库验收采用独立Homebrew MySQL8.4实例。
- 下一阶段优先增加管理员维护与分页、站间耗时采样、ETA置信度、司机与车辆绑定管理，以及规则效果校准；现有规则辅助派单已实现。

停止服务：前后端各自终端 `Ctrl+C`；Docker路径使用 `docker compose down`（保留数据卷）。重启会保留业务记录，只有管理员明确确认的演示重置会清空演示运营记录。
