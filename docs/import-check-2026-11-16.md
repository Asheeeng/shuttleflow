# 2026-11-16 组内版本导入核验

本轮完成旧分支归档、新版主线导入和材料整理，没有进行业务开发或数据库重置。

| 检查 | 实际结果 |
| --- | --- |
| 组内原文件 | 87个文件导入；除.gitignore补充本机忽略规则，其余SHA-256与原件一致 |
| 三份PDF | 按原件复制，SHA-256一致；提取文字及查看首页作交接参考 |
| Java编译 | `mvn -B -DskipTests compile -s ../maven_settings.xml`，BUILD SUCCESS，48个源文件 |
| 编译环境 | 本机Java17.0.18，Maven3.9.16；输出class major52，为Java8目标字节码 |
| Python语法 | ai-service下6个模块及根目录2个测试脚本通过AST解析；未运行接口逻辑 |
| 发布包完整性 | 原release/shuttle-1.0.0.jar的ZIP CRC通过，文件按原件保留 |
| JAR配置及SQL | 与源码文本行一致；字节差异为Windows/Unix换行形式 |
| SQL发布副本 | release/init.sql与backend/src/main/resources/sql/init.sql字节一致 |
| 前端双份 | frontend与backend/src/main/resources/static下6个文件字节一致 |
| 后端运行、数据库导入、端到端/性能/消融测试 | 本轮未执行 |

编译器提示部分Java代码存在unchecked操作警告，不影响本次编译通过；本轮未改写组内源码修复这些警告。没有将文档中的历史测试结论冒充本机复验结果。

来源及每个文件的大小、SHA-256见[导入清单](import-2026-11-16.json)。新旧架构、账号差异和后续优先项见[仓库交接说明](仓库交接说明.md)。
