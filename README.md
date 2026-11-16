# 校园摆渡车站点客流上报与AI智能动态调度系统

## 项目简介
面向贵州大学校内固定线路/固定站点的校园摆渡车，构建打通"感知—预测—决策—执行"闭环的Web原型系统。
MVP闭环：扫码签到 → 司机上报 → 队列融合(守恒) → 规则调度 → 任务派发(评分顺延) → 到站/上下客 → 评估

## 技术栈
- 后端：Spring Boot 2.7.18 + MyBatis-Plus 3.5.5 + MySQL 8.0 + Redis + WebSocket
- 前端：本地 Vue 3.5.45 全局构建 + 原生HTML/CSS/JS，无需Node构建
- AI服务：规则调度（MVP），后续迭代接入LightGBM/TCN/CUSUM/OR-Tools

## 线路站点数据
- 1号线：双馨园食堂 ↔ 西校区三食堂（去向9站/回向13站）
- 2号线：双馨园食堂 ↔ 西校区校内交通车站（去向9站/回向13站）
- 3号线：双馨园食堂 ↔ 西校区三食堂（去向10站/回向13站）
- 车辆：1~4号车→1号线，5~8号车→2号线，9~12号车→3号线
- 容量：每车除司机可坐13人
- 班次：7:00-22:00，平峰15分钟/班，高峰(8-9,12-13,17-18)5分钟/班

## 快速启动

> 初始化脚本含 `DROP TABLE`，只允许用于新建且确认为空的数据库。已有 V1.1 数据库使用 [V1.2 兼容迁移](backend/src/main/resources/db/migration/V1.2__security_and_idempotency.sql)，不要对组内数据库执行初始化脚本。当前源码与发布配置均不包含生产数据库口令或默认管理员账号。

### 1. 新建本地空数据库
```bash
sudo mysql -e "CREATE DATABASE shuttle_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
sudo mysql shuttle_db < backend/src/main/resources/sql/init.sql
```
第二行只适用于新建空库；已有业务库必须先备份并执行可审查迁移。

### 2. 配置服务端环境变量
生产部署参考 [`deploy/.env.production.example`](deploy/.env.production.example) 和 [Debian 12 部署手册](docs/deploy/debian12.md)。本机开发可用 `shuttle_app` 最小权限账号；请通过安全环境变量提供 `SHUTTLE_DB_PASSWORD`、`SHUTTLE_AI_SERVICE_TOKEN`。Redis可选，生产环境应设置认证并只监听本机。

### 3. 编译启动后端
```bash
mvn -f backend/pom.xml -DskipTests package
java -jar backend/target/shuttle-1.0.0.jar
```
Java源码目标为8；部署建议使用受支持的Java 17运行时。后端默认只监听 `127.0.0.1:8080`，公网访问应经 HTTPS Nginx 反向代理。Python AI默认只监听 `127.0.0.1:8000`。

### 4. 访问前端
- 入口页：http://localhost:8080/index.html
- 登录页：http://localhost:8080/login.html
- 乘客登录/注册：http://localhost:8080/login.html?role=passenger
- 乘客游客访问：http://localhost:8080/passenger.html?guest=1
- 司机端：http://localhost:8080/driver.html（司机账号由管理员创建并绑定车辆）
- 管理端：http://localhost:8080/admin.html（首次空管理员表由一次性引导环境变量创建）
- 仿真台：http://localhost:8080/simulation.html（管理员/调度员登录后访问）

登录使用 PBKDF2-HMAC-SHA256、HttpOnly 会话 Cookie、会话轮换、CSRF 校验和后端角色权限。乘客可自助注册，也可明确选择游客访问；司机不能公开注册，只能由系统管理员创建账号和车辆绑定。调度员可处理任务，车辆容量与运行状态由系统管理员管理。公开部署前必须轮换历史演示账号、MySQL root 和服务令牌；轮换步骤见 [部署手册](docs/deploy/debian12.md#历史凭据轮换)。

### Python AI 服务

仿真台通过受保护的 Java 管理接口调用 AI 服务，浏览器不会直连 Python 端口。Java 和 FastAPI 进程必须设置相同的 `SHUTTLE_AI_SERVICE_TOKEN`；FastAPI 默认仅监听 `127.0.0.1:8000`。`frontend/vendor/` 中包含固定版本 Vue 生产构建和官方许可证，页面不再依赖 unpkg。

macOS/Linux 示例（在启动 Java 与 Python 的终端都设置同一值）：

```bash
export SHUTTLE_AI_SERVICE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
```

Windows PowerShell 示例：

```powershell
$env:SHUTTLE_AI_SERVICE_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

## 功能说明

### 乘客端
- 扫码签到（选择线路/方向/站点 → 模拟扫码）
- 查看ETA（即将到达车辆的车牌号/到达分钟/拥挤度）
- 查看候车人数
- 取消签到

### 司机端
- 登录绑定车辆
- 设置当日运行（开关）
- 车内人数大字号显示 + 负载条 + 颜色（绿<70%/黄70-90%/红>90%）
- 到站三次点击流程：确认到站 → 下客 → 上客
- 人数校准（+/−修正）
- 调度任务卡片（接单/拒单/完成）

### 管理端
- 调度大屏：站点队列TOP10、车辆负载、课表先验、待处理预警
- 调度台：手动创建调度任务、活跃任务监控、派单/完成
- 车辆管理：当日运行开关、容量和状态修改
- 司机账号：管理员创建/绑定车辆、重置密码
- 账号安全：管理员更新密码并撤销该账号其他活动会话
- 预警中心：确认/抑制/解除
- 事件流水：原子事件审计

## 核心算法（MVP已实现）
- 队列融合：守恒方程 Li(t+Δt)=max{0,Li(t)+Ai-Bi}
- 规则调度：站点候车超阈值(8/15/25)→黄/橙/红预警→自动生成调度任务
- 课表先验：下课时间前10分钟→潜在需求=人数×转化率→预置运力任务
- 任务派发：当日运行车辆硬过滤 → 评分(ETA×0.4+负载率×0.4+状态×0.2) → 选最优 → 失败顺延 → 全失败升级管理员
- ETA：最近到站事件 + 累加站间时长 + 60秒buffer

## 后续迭代项（非MVP）
- 卡尔曼滤波融合（多源观测+置信度）
- CUSUM突变检测（1分钟粒度）
- 15分钟P50/P90短时预测（LightGBM/TCN）
- 多车协同分配（最小费用流/OR-Tools CP-SAT）
- YOLOv8+ByteTrack视觉计数
- 数字孪生仿真+消融实验
