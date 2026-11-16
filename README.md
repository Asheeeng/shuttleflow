# 校园摆渡车站点客流上报与AI智能动态调度系统

## 项目简介
面向贵州大学校内固定线路/固定站点的校园摆渡车，构建打通"感知—预测—决策—执行"闭环的Web原型系统。
MVP闭环：扫码签到 → 司机上报 → 队列融合(守恒) → 规则调度 → 任务派发(评分顺延) → 到站/上下客 → 评估

## 技术栈
- 后端：Spring Boot 2.7.18 + MyBatis-Plus 3.5.5 + MySQL 8.0 + Redis + WebSocket
- 前端：Vue3 (CDN) + 原生HTML/CSS/JS，无需node构建
- AI服务：规则调度（MVP），后续迭代接入LightGBM/TCN/CUSUM/OR-Tools

## 线路站点数据
- 1号线：双馨园食堂 ↔ 西校区三食堂（去向9站/回向13站）
- 2号线：双馨园食堂 ↔ 西校区校内交通车站（去向9站/回向13站）
- 3号线：双馨园食堂 ↔ 西校区三食堂（去向10站/回向13站）
- 车辆：1~4号车→1号线，5~8号车→2号线，9~12号车→3号线
- 容量：每车除司机可坐13人
- 班次：7:00-22:00，平峰15分钟/班，高峰(8-9,12-13,17-18)5分钟/班

## 快速启动

> 本仓库的初始化脚本包含 `DROP TABLE`，只可用于确认可丢弃的空演示库。不要对已有组内或个人数据库直接执行。登录改造首次启动时会以可重复的 `ALTER TABLE` 增加密码哈希列，并将旧演示密码迁移为 PBKDF2-HMAC-SHA256 哈希；业务数据行不会被删除。

### 1. 初始化数据库
```bash
mysql -u root -p"Root@123456" < backend/src/main/resources/sql/init.sql
```

### 2. 启动Redis（可选，不启动不影响核心功能）
确保Redis在 localhost:6379 运行（无密码）。未启动时事件总线降级，不影响队列融合和调度。

### 3. 编译启动后端
```bash
# 在仓库根目录执行；Windows/macOS/Linux均使用同一相对路径
mvn -f backend/pom.xml -s maven_settings.xml -DskipTests package
java -jar backend/target/shuttle-1.0.0.jar
```
后端启动在 http://localhost:8080。Java需为8或更高版本；源码目标字节码保持Java 8。

### 4. 访问前端
- 入口页：http://localhost:8080/index.html
- 登录页：http://localhost:8080/login.html
- 乘客端：http://localhost:8080/passenger.html（游客访问，无需注册）
- 司机端：http://localhost:8080/driver.html（未登录时进入司机登录；演示账号 13800000001 至 13800000012，密码 123456）
- 管理端：http://localhost:8080/admin.html（管理员 admin/admin123；调度员 dispatcher/123456）
- 仿真台：http://localhost:8080/simulation.html（管理员/调度员登录后访问）

首次启动会把演示账号旧口令转换为 Java 8 自带的 PBKDF2-HMAC-SHA256（310,000 次迭代、每条口令独立随机盐），随后清空旧明文密码列；账号和密码仍保持兼容。司机注册设置的密码至少 8 位。登录使用 HttpOnly 会话 Cookie、服务端角色校验和 CSRF 令牌；退出会使会话失效。调度员可查看运营数据与处理调度任务，车辆状态修改仅系统管理员可操作。

### Python AI 服务

仿真台通过受保护的 Java 管理接口调用 AI 服务，浏览器不会直连 Python 端口。Java 和 FastAPI 进程必须设置相同的 `SHUTTLE_AI_SERVICE_TOKEN`；FastAPI 默认仅监听 `127.0.0.1:8000`。跨主机部署时应通过环境变量配置相同令牌、`SHUTTLE_AI_SERVICE_URL` 和 `AI_BIND_HOST`，并用防火墙限制服务端口，不要将令牌写入前端或提交到仓库。

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
- 车辆管理：当日运行开关、状态修改
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
