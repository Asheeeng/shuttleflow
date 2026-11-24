# ShuttleFlow V1.3 校园校车运营仿真器

仿真器使用乘客、管理员、司机真实HTTP业务接口；调度任务必须由Java服务生成并经过接单/拒单、到站、上下客和完成接口闭环。它不会直接写数据库，不会把“模型预测成功”伪装成业务成功。

## 安全边界

- 默认API只允许 `http://127.0.0.1:18080`，数据库名必须以 `shuttle_v13_sim_` 开头，JDBC主机必须是回环地址。
- `simulation/runtime.py start` 使用此前V1.2本机验收专用、仅监听127.0.0.1:3308的MySQL实例，在其中新建随机 `shuttle_v13_sim_*` schema 和该库专属最小权限账号；另外创建隔离Redis实例（16379），FastAPI/Java绑定127.0.0.1:18000/18080。它读取本机0600的 `.runtime/mysql-root.cnf`，不输出密钥。端口冲突或实例非3308/非回环时停止，不改用3306、6379、8080、8000或远端服务。
- 创建专属仿真数据库和只对该库有读写权的应用账号。初始化SQL只对新建随机库执行；管理员、司机口令和AI内部令牌只存入临时运行目录`app.env`（0600），从不写入报告/仓库。
- 乘客以隔离测试库内的游客会话签到，不批量创建乘客账号。仿真司机仅创建5个左右的隔离测试账号。
- 测试profile禁用墙钟CUSUM自动调度，仿真器按虚拟分钟调用受管理员会话保护的 `/api/admin/simulation/cusum/tick`；该入口在应用默认配置下关闭。每个tick处理刚由HTTP接口产生的真实事件。
- SQLite/正式V1.2、香港VPS、正式MySQL/Redis、个人Chrome均不触碰。输出所有客流标记 `source=simulated`。

## 启动与运行

一键执行完整HTTP与无头浏览器验收：

```bash
bash simulation/acceptance_local.sh
```

脚本每次创建新运行目录和唯一前缀测试schema；运行六类真实HTTP场景、导出报告，再用headless Chromium验收工作台。结束时停止本次Java/AI/Redis并删除临时`app.env`，保留测试库和原始结果。不会清理或覆盖3308上的其他schema。

单独调试服务时可执行：

```bash
python3 simulation/runtime.py start
```

支持 `normal`、`class_peak`、`surge`、`multi_vehicle`、`capacity_shortage`、`driver_refusal` 单场景；更换种子可复现实验输入。

交互控制：

```bash
bash simulation/run_local.sh --scenario all --seed 20261117 --speed 60 --interactive
```

在提示符输入 `p` 暂停、`r` 继续、`s` 停止。默认时间加速60倍；突发场景通过虚拟分钟批次驱动Java CUSUM，不等待墙钟一分钟。

运行单项HTTP场景：

```bash
bash simulation/run_local.sh --scenario normal --seed 20261117 --speed 60
```

停止本机服务并保留隔离测试库文件：`bash simulation/stop_local.sh`。

## 输出

每次运行创建 `simulation/results/v13-<时间>-seed<种子>/`，包含：

- `arrivals_<scenario>.json`：固定种子的乘客到达序列。
- `api_events.jsonl`：HTTP操作结果摘要、队列守恒、任务状态、拒单、CUSUM tick和防超载检查；不含Cookie、密码、CSRF令牌。
- `scenario_<scenario>.json` 与 `scenario_metrics.csv`：六类真实接口场景的原始汇总。
- `strategy_metrics.csv`：固定班次、简单阈值与真实API智能调度在相同到达序列下的比较。
- `dispatch_effect.svg`：平均候车时间对照图。
- `report.md`、`summary.json`：实验方法、算法边界与结果。

基线模型的固定班次按README高峰5分钟/平峰15分钟，简单阈值使用8人；两者不连接业务接口。真实API组使用Java阈值预警、Java CUSUM tick和TaskService车辆评分/方向过滤。多车CP-SAT/启发式接口是只读分配建议，不会执行车辆任务，也不计入真实调度完成率。预计下客30%仅用于仿真与分配建议，属于显式模拟假设。

## 自动测试

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s simulation/tests -p 'test_*.py' -v
mvn -f backend/pom.xml -s maven_settings.xml -Dtest=TaskServiceDispatchTest,SurgeDetectionServiceTest test
```

自动化浏览器验收使用项目`simulation/browser-tests/`管理的Playwright 1.65.1和Chromium；默认显式`headless:true`，不启动个人Chrome、不读取其用户数据。脚本通过本机API取得短时测试会话，截图后注销；Cookie/CSRF临时文件权限0600并在退出时删除。截图写入`output/playwright/v13/`。浏览器只检查仿真工作台渲染，业务闭环由仿真器HTTP客户端执行。

```bash
bash simulation/headless_browser_smoke.sh
```

## 正式站候选方案：独立内存算法实验

`python3 simulation/runtime.py start --lab --run-dir /private/tmp/shuttle-lab-unique`
在备用回环端口运行正式模式的验收版（Java 18090、AI 18010、Redis 16389、实验 18002），只新建本机测试 MySQL 3308 的随机库。启动后可用管理员访问 `http://127.0.0.1:18090/simulation.html`；引用该次受限 `app.env` 后运行 `python3 -m simulation.acceptance_experiment`。结束用同一 `--run-dir` 和 `stop` 清理。线上运行实验 unit 本身不需要 MySQL、Redis 或 FastAPI 凭据。旧 HTTP 联调模式的 `start` 不允许部署到正式站。
