# 数据库初始化

数据库为 MySQL 8（本机验收8.4.11），正式演示禁止用H2替代。

1. Docker Compose 根据 `.env` 创建 `campus_shuttle` 和应用账号；或用 `scripts/local-mysql.py` 创建独立Homebrew实例及随机凭据。
2. 启动Spring Boot，由Flyway按版本执行 [迁移目录](../backend/src/main/resources/db/migration) 中的V1、V2、V3；原V1保持不变。该目录是唯一结构来源，避免复制SQL漂移。
3. `DemoService` 仅在 `demo & !prod & !production` 激活且用户和线路表都为空时写入演示用户、线路、站点、车辆、任务及A站初始到站记录。密码运行时BCrypt哈希、到站时间为服务器当前时间；不使用固定过期SQL时间。
4. 管理员显式输入 `RESET DEMO` 后，通过同一服务在事务内重置运营记录。基础ID保留，不在每次启动重置。

`00_create_database.sql` 仅供自行配置MySQL的用户创建数据库；业务账号请自行设置随机密码并仅授予该库权限。真实凭据只写本地忽略的 `.env`，不写入SQL或Git。

不要手动编辑已在长期环境应用的Flyway迁移；下一版本结构变更应增加V2迁移。
