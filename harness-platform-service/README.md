# 集中执行服务

Java 17 / Spring Boot 3.5，复用普通 Java Harness SDK。实现冻结的 11 个 `/v1/projects/{projectId}/runs` 操作，MySQL 持久 worker、原子命令账本、归属、审批和 UNKNOWN 对账。

构建：`./mvnw -pl harness-platform-service -am package`（Windows 使用 `mvnw.cmd`）。

- [运行、身份、备份与恢复](../docs/platform-operations.md)
- [部署示例](../deploy/platform/deployment.example.json)：必须替换身份/RAG 地址并配置已登记的应用密钥和真实用户权限。
- [OpenAPI](../docs/api/openapi.json)
- [阶段一、二交付记录](../docs/phase12-implementation.md)

HTTP 返回 202 表示命令已持久化；后台 worker 独立推进。`workerEnabled=false` 可启动只服务 API 的进程；另一个相同配置、`workerEnabled=true` 的进程共享同一数据库。不得给旧 Run 热替换定义、权限快照、工具契约或签名密钥。
