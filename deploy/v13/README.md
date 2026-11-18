# 香港 VPS V1.3 隔离部署

独立 HTTPS 域名为 `v13.shuttleflow-gzu.duckdns.org`。安装需要已验证的 SSH 主机及交互式 sudo，入口和应用使用两个独立的新密码。交付包带 Git SHA 和文件校验清单。

## 服务布局

V1.3 使用独立的 MySQL 实例（3308）、Redis 实例（16379）、FastAPI（18000）、运营进程（18001）、Java（18080）和持久化目录。内部端口全部回环绑定。浏览器使用独立主机的 Secure、HttpOnly 会话 Cookie。Nginx 验证入口账号和现有应用自己的管理员认证，内部调用令牌由反向代理剥离。运营进程的专用令牌只允许它从直连回环地址使用独立限流桶。

V1.3 默认按需启动，各服务分别设有 CPU 与内存上限，总内存硬上限约 1.7 GiB。每次最多 150 名乘客、6 辆车、一次运行、30 分钟墙钟时间。正常结束用业务事件清理候车、任务和车队。进程异常退出时保留恢复标记，下次启动仿真先清理残留。建议在 VPS 观察实际余量后再提高上限。

## 维护与回滚

```sh
sudo systemctl start shuttle-v13.target
sudo systemctl status shuttle-v13.target shuttle-v13-java shuttle-v13-operation
sudo journalctl -u shuttle-v13-operation -n 60 --no-pager
sudo systemctl start shuttle-v13-backup.service
sudo systemctl stop shuttle-v13.target
```

V1.3 的数据库与历史备份放在 `/var/backups/shuttle-v13/`。独立定时器每天执行数据库备份，健康定时器使用现有邮件发送模块和 SMTP 配置。按需停止期间，监控不对已停止的实例告警，仍检查备份时效。

回滚时停掉 `shuttle-v13.target`，禁用两个 V1.3 定时器，移除 `/etc/nginx/sites-enabled/shuttle-v13` 链接，再执行 `sudo nginx -t` 和 `sudo systemctl reload nginx`。保留应用版本、隔离库、证书、历史和备份。V1.2 备份在 `/var/backups/shuttleflow/pre-v13-*/`，只有 V1.2 本身需要恢复时才单独审查恢复步骤。

安装脚本以公开 HTTPS 地址触发 100 乘客、4 辆车的 API 验收，检查实名测试账号产生的请求、自动派单、逐站数据、报告档案及 WSS，写出 `/var/backups/shuttle-v13/public-acceptance.json`。实际浏览器渲染和回放另用项目无头 Chromium 检查。
