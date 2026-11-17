# ShuttleFlow V1.2 · Debian 12 单机部署手册

目标主机：Debian 12、4 vCPU、4 GB RAM、50 GB SSD。应用、AI、MySQL 和 Redis 都只监听本机；公网只开放 SSH、HTTP、HTTPS，应用通过 Nginx 使用 HTTPS/WSS。

**本手册只描述部署步骤，没有连接或修改正式 VPS。** V1.2 代码仍需在目标机或等价 Debian 测试机执行完整安装、恢复与压力验收后，才能作为服务器验收通过。

## 资源起始预算

| 服务 | 初始预算 | 配置入口 |
| --- | ---: | --- |
| Debian、Nginx、系统缓存与安全余量 | 约 900 MB | `free`、`vmstat`、`df` |
| Spring Boot JVM | `-Xmx1024m`，进程上限 1536 MB | `deploy/systemd/shuttleflow.service` |
| MySQL 8 | InnoDB buffer pool 768 MB，最多80连接 | `deploy/mysql/shuttleflow.cnf` |
| Redis | 最多256 MB，禁止自动淘汰事件 | `deploy/redis/shuttleflow.conf` |
| FastAPI/算法 | 单worker，进程上限512 MB、CPU 1核 | `deploy/systemd/shuttle-ai.service` |

这是首轮调优值，不是实测结论。部署后先看峰值 RSS、CPU、连接池等待和慢查询，再决定调整；不要在4GB主机上同时提高所有上限。

## 1. 安装Debian运行环境

先升级系统并配置 SSH 公钥登录；确认云安全组保留正确 SSH 端口，再开启主机防火墙：

```bash
sudo apt update
sudo apt full-upgrade
sudo apt install -y openjdk-17-jre-headless openjdk-17-jdk maven \
  python3 python3-venv python3-dev build-essential git curl openssl \
  nginx certbot python3-certbot-nginx redis-server ufw mysql-client
```

项目以Java 8字节码编译，建议使用Debian 12默认的Java 17运行。MySQL 8使用Oracle官方APT仓库；下载并安装仓库配置包时选择Debian `bookworm` 与 `mysql-8.0`，之后执行 `sudo apt update && sudo apt install mysql-server`。请勿把MariaDB替代MySQL用于这次验收。[MySQL官方APT仓库安装说明](https://dev.mysql.com/doc/refman/8.4/en/linux-installation-apt-repo.html)列出Debian 12的`bookworm`配置和安装流程。

开放必要端口。若SSH使用了非22端口，先替换规则再启用：

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

安全组与`ufw`都不要开放8080、8000、3306、6379。

## 2. 创建专用系统用户和服务目录

```bash
sudo useradd --system --home /var/lib/shuttleflow --create-home \
  --shell /usr/sbin/nologin shuttleflow
sudo install -d -o root -g shuttleflow -m 0750 /opt/shuttleflow
sudo install -d -o root -g shuttleflow -m 0750 /opt/shuttleflow/releases
sudo install -d -o shuttleflow -g shuttleflow -m 0750 /var/lib/shuttleflow
sudo install -d -o root -g root -m 0700 /etc/shuttleflow
```

首次从私有/组内仓库克隆源码后，检查 `origin` 指向 `Asheeeng/shuttleflow`，再将仓库放到`/opt/shuttleflow/repository`。生产服务器使用只读Deploy Key，不在仓库或环境文件存放GitHub个人令牌。更新脚本会按Git提交建立独立release目录，不修改正在运行的源码目录。

```bash
sudo git clone --branch codex/v1.2-hardening-passenger-auth \
  https://github.com/Asheeeng/shuttleflow.git /opt/shuttleflow/repository
```

## 3. 配置MySQL、Redis与服务密钥

将MySQL监听地址与内存设置保存为`/etc/mysql/mysql.conf.d/z-shuttleflow.cnf`：

```bash
sudo cp deploy/mysql/shuttleflow.cnf /etc/mysql/mysql.conf.d/z-shuttleflow.cnf
sudo systemctl restart mysql
sudo ss -lntp | grep '3306'
```

结果应只显示`127.0.0.1:3306`。以root运行账号脚本；它会保留已有数据库、不重置数据，如果检测到同名账号或已有密钥文件则停止：

```bash
sudo deploy/bin/create-db-user.sh
```

脚本创建`shuttle_app@127.0.0.1`，仅授予业务库的`SELECT/INSERT/UPDATE/DELETE`；另建只读备份账号和只读监控账号。生成的口令放在`/etc/shuttleflow/secrets.env`、`backup.cnf`、`monitor.cnf`，权限为`0600`，不会打印到终端。MySQL root 只用于本机管理和迁移，不供应用连接。

配置Redis仅本机监听、认证及资源上限。脚本会把`secrets.env`中的`SHUTTLE_REDIS_PASSWORD`写到root-only Redis配置片段，不打印口令：

```bash
sudo deploy/bin/configure-redis.sh
```

检查`ss -lntp`只显示回环地址。Redis设置`maxmemory 256mb`、`noeviction`、AOF每秒刷盘；Java事件流通过`SHUTTLE_REDIS_EVENT_STREAM_MAX_LENGTH`限制在约10000条。MySQL是持久业务数据的唯一事实来源，Redis故障会记录日志并降级，不允许把Redis作为数据库备份。

复制非密钥模板并设置严格权限。密钥值从脚本生成的`secrets.env`读取，不要提交或放在网页目录：

```bash
sudo cp deploy/.env.production.example /etc/shuttleflow/shuttleflow.env
sudo chown root:root /etc/shuttleflow/shuttleflow.env
sudo chmod 0600 /etc/shuttleflow/shuttleflow.env
sudoedit /etc/shuttleflow/shuttleflow.env
```

将域名填入`SHUTTLE_CORS_ALLOWED_ORIGINS`，确认数据库URL使用`127.0.0.1`，保留`SHUTTLE_BIND_ADDRESS=127.0.0.1`、`AI_BIND_HOST=127.0.0.1`和`SHUTTLE_SESSION_COOKIE_SECURE=true`。两个systemd服务加载相同的`secrets.env`，其中的AI内部令牌不能写入`frontend/`。

## 4. 新建数据库或升级现有V1.1库

### 全新空库

初始化SQL会`DROP TABLE`。只在刚创建、没有需要保留数据的`shuttle_db`执行：

```bash
sudo mysql shuttle_db < backend/src/main/resources/sql/init.sql
```

此版本不会创建默认管理员或司机账号。管理员由一次性环境变量引导；司机之后由管理员在「司机账号」页面创建并绑定车辆。

### 已有V1.1数据库

先执行一份数据库备份，并把备份复制到另一台受控存储。执行以下预检查；查询结果中重复记录或容量异常必须先由运营方确认处理：

```sql
SELECT phone, COUNT(*) FROM passenger WHERE phone IS NOT NULL GROUP BY phone HAVING COUNT(*) > 1;
SELECT phone, COUNT(*) FROM driver WHERE phone IS NOT NULL GROUP BY phone HAVING COUNT(*) > 1;
SELECT default_vehicle_id, COUNT(*) FROM driver WHERE default_vehicle_id IS NOT NULL GROUP BY default_vehicle_id HAVING COUNT(*) > 1;
SELECT id, capacity, current_load FROM vehicle WHERE capacity <= 0 OR current_load < 0 OR current_load > capacity;
```

V1.2迁移新增乘客密码哈希列、候车幂等键、唯一约束和容量/状态约束，不删除表或行。它会因重复手机号、重复车辆绑定或非法旧状态而失败，此时保留原库并先核对数据；不要删除冲突记录来绕过迁移。

```bash
sudo mysql shuttle_db < backend/src/main/resources/db/migration/V1.2__security_and_idempotency.sql
```

迁移后验证新增列、索引和约束，再启动V1.2。`PasswordMigrationRunner`会兼容旧司机/管理员明文列，转成PBKDF2哈希并清空旧列；这不替代账号口令轮换。

## 5. 初始化管理员和配置systemd

首次管理员密码至少14位。先将`SHUTTLE_BOOTSTRAP_ADMIN_USERNAME`和`SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD`临时加入`/etc/shuttleflow/shuttleflow.env`，然后部署并启动Java服务。引导只在管理员表为空时执行；已有管理员数据绝不被覆盖。确认账号可登录后，立即从环境文件删除这两项并重启。

```bash
sudo cp deploy/systemd/shuttleflow.service /etc/systemd/system/
sudo cp deploy/systemd/shuttle-ai.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now redis-server mysql
sudo systemctl enable --now shuttle-ai shuttleflow
sudo systemctl status shuttle-ai shuttleflow
sudo journalctl -u shuttleflow -n 100 --no-pager
sudo journalctl -u shuttle-ai -n 100 --no-pager
```

systemd将Java堆限制为1GB、应用进程上限约1.5GB，AI单worker进程上限512MB。服务异常退出会自动重启。日志写入journald；Nginx日志用`deploy/logrotate/shuttleflow-nginx`轮转。建议将`deploy/logrotate/journald.conf`安装到`/etc/systemd/journald.conf.d/`，将日志空间上限设为系统磁盘的200MB级别。

## 6. 配置HTTPS与WebSocket反向代理

先为域名创建仅HTTP的临时Nginx server，确认DNS已指向VPS并申请证书：

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
sudo certbot --nginx -d shuttle.example.edu
```

随后将`deploy/nginx/shuttleflow-rate-zones.conf`安装到`/etc/nginx/conf.d/`，将`deploy/nginx/shuttleflow-proxy.conf`复制到`/etc/nginx/snippets/shuttleflow-proxy.conf`，将`deploy/nginx/shuttleflow.conf`复制到`/etc/nginx/sites-available/shuttleflow`。把示例域名替换为真实域名，确保证书路径与Certbot输出一致，再启用站点：

```bash
sudo ln -s /etc/nginx/sites-available/shuttleflow /etc/nginx/sites-enabled/shuttleflow
sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
```

配置把`Host`、`X-Real-IP`、`X-Forwarded-For`和`X-Forwarded-Proto`传给Spring，`/ws`使用HTTP/1.1 Upgrade支持WSS。Nginx覆盖客户端提交的`X-Real-IP`；应用只在来自本机代理时读取它。Cookie的`Secure`属性由`SHUTTLE_SESSION_COOKIE_SECURE=true`启用，`SameSite=Lax`和`HttpOnly`继续生效。不要从公网直连8080或8000。

## 7. 首次部署、更新和回滚

脚本要求`/opt/shuttleflow/repository`已克隆本仓库，部署用户可以读取Git远端，root可写release目录。运行：

```bash
sudo env SHUTTLE_DEPLOY_BRANCH=codex/v1.2-hardening-passenger-auth \
  /opt/shuttleflow/repository/deploy/bin/update.sh
```

每次必须显式设置`SHUTTLE_DEPLOY_BRANCH`；未设置时脚本会拒绝运行，不会默认拉取V1.1的`main`。部署本分支的命令如下：

```bash
sudo env SHUTTLE_DEPLOY_BRANCH=codex/v1.2-hardening-passenger-auth \
  /opt/shuttleflow/current/deploy/bin/update.sh
```

脚本拉取指定分支、构建Java包、建立Python虚拟环境、原子切换`current`链接并进行健康检查。构建失败不切换；服务重启或就绪检查失败会恢复先前链接并重启服务。脚本不会自动执行SQL或删除数据库。数据库迁移应在应用更新前由管理员单独审查和执行。

回滚到本机保留的提交：

```bash
sudo SHUTTLE_ROLLBACK_COMMIT=<旧Git提交SHA> /opt/shuttleflow/current/deploy/bin/rollback.sh
```

V1.2数据库迁移只加列和约束，旧代码可忽略新增列。若约束使旧服务写入失败，应停止切换并检查迁移兼容性；不要用`DROP`或全库恢复覆盖上线后的新数据。数据库恢复脚本只允许导入到新的`shuttle_restore_*`数据库。

## 8. 自动备份、恢复和监控

安装备份任务并保证脚本由root运行：

```bash
sudo cp deploy/cron/shuttleflow-backup /etc/cron.d/shuttleflow-backup
sudo chmod 0644 /etc/cron.d/shuttleflow-backup
sudo cp deploy/logrotate/shuttleflow-nginx /etc/logrotate.d/shuttleflow-nginx
sudo install -d /etc/systemd/journald.conf.d
sudo cp deploy/logrotate/journald.conf /etc/systemd/journald.conf.d/shuttleflow.conf
sudo systemctl restart systemd-journald
sudo install -d -o root -g root -m 0700 /var/backups/shuttleflow
sudo deploy/bin/backup-mysql.sh
```

每天UTC 02:30执行MySQL一致性压缩备份；脚本校验gzip并原子改名，文件权限为`0600`，默认保留14天。定期将加密备份复制到异地受控存储。恢复演练只能写入全新库名，目标库已存在时脚本会拒绝：

```bash
sudo deploy/bin/restore-mysql-to-new-db.sh /var/backups/shuttleflow/shuttle_db-YYYYMMDDTHHMMSSZ.sql.gz shuttle_restore_20261116
```

使用`deploy/bin/health-check.sh`检查Java存活、MySQL就绪和AI健康；`deploy/bin/monitor.sh`查看CPU/RSS、磁盘、MySQL连接/慢查询、Redis内存/连接/事件流长度。WebSocket建立和关闭日志会包含当前连接数，可用`journalctl -u shuttleflow`筛选。

上线首周至少每日核对：JVM RSS及GC、MySQL `Threads_connected`/`Slow_queries`/连接池等待、Redis `used_memory`/`connected_clients`/`XLEN shuttle:events`、AI进程RSS、WebSocket连接数量。事件流上限不能替代数据库事件表归档策略；事件长期增长需按业务保留周期另行制定分区或归档流程。

## 9. 历史凭据轮换

V1.1之前的配置和发布包曾出现MySQL root口令、演示管理员/司机口令；其中部分还存在于Git历史。按已泄露处理，不能只删除当前文件后继续复用：

1. 为MySQL root设置全新强口令，并撤销公网访问。应用始终使用`shuttle_app`，备份使用`shuttle_backup`。
2. 登录V1.2管理员端「账号安全」，修改系统管理员密码；管理员会话其他会话立即失效。停用不再使用的旧管理员/调度员账号。
3. 在「司机账号」页面为仍在职司机逐个重置强口令；重置会撤销该司机HTTP会话和WebSocket。清退未使用的旧司机账号。
4. 重新生成并同步`SHUTTLE_AI_SERVICE_TOKEN`，更换Redis密码和任何以前出现在日志、终端历史或个人配置中的口令。
5. 设置`SHUTTLE_SESSION_COOKIE_SECURE=true`，配置HTTPS；SSH改用密钥登录并关闭密码认证。
6. 保留原Git提交历史用于审计；所有历史值均按已泄露处理，不把它们重新写进新提交。

## 验收边界

本手册不代表已部署到香港VPS，也不声称Debian 12/4GB目标机通过压力验收。V1.2验收报告将分别记录本机真实HTTP/数据库压测结果、浏览器业务闭环、未验证的Debian安装项和正式VPS部署待办。
