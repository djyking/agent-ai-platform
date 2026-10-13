# Agent 应用平台服务

Agent 应用平台（Agent Platform）的集中服务，使用 Java 17 / Spring Boot 3.5，复用普通 Java Harness SDK。提供执行、资源目录、应用 Studio 和治理 API，支撑应用的统一构建、发布、运行与治理；多个业务应用共享平台能力，承担内部 Agent 中台的角色。

执行 API 实现冻结的 11 个 `/v1/projects/{projectId}/runs` 操作，支持 MySQL 持久 worker、原子命令账本、归属、审批和 UNKNOWN 对账。共享治理覆盖供应商/模型/工具的并发与速率，以及项目/应用的 token 预算。`harness-platform-service` 模块名、Harness SDK、API 路径和配置前缀保持兼容。

构建：`./mvnw -pl harness-platform-service -am package`（Windows 使用 `mvnw.cmd`）。

- [运行、身份、备份与恢复](../docs/platform-operations.md)
- [部署示例](../deploy/platform/deployment.example.json)：必须替换身份/RAG 地址并配置已登记的应用密钥和真实用户权限。
- [OpenAPI](../docs/api/openapi.json)
- [阶段一、二交付记录](../docs/phase12-implementation.md)
- [第五阶段工程与验收边界](../docs/phase5-implementation.md)：本机 24 小时、Linux 7 天观察及真实跨主机恢复尚未完成，现有本机验证不代表生产验收通过。

HTTP 返回 202 表示命令已持久化；后台 worker 独立推进。`workerEnabled=false` 可启动只服务 API 的进程；另一个相同配置、`workerEnabled=true` 的进程共享同一数据库。不得给旧 Run 热替换定义、权限快照、工具契约或签名密钥。
