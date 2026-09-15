# 研发知识助手与工程回归基线

这是阶段 1 的第二类场景：研发流程知识的只读问答助手。它以实际 Harness、RAG 和原生 tool-call 消息配对运行，与 OpsAgent 的运维接口和业务数据无关。数据、模型回复和额外的写入故障场景均为合成数据。

本模块验证公共运行机制，**通过率不是模型回答质量分数**。真实模型质量、真实 MCP、真实 MySQL 应由阶段 1 联调入口另行记录。

## 运行

在仓库根目录先构建并安装模块，然后运行 CLI：

```powershell
.\mvnw.cmd -pl harness-evals -am install -B -ntp
.\mvnw.cmd -pl harness-evals exec:java '-Dexec.args=builtin target/engineering-report.json target/eval-data'
```

`target/...` 路径相对于 Maven 当前执行目录；需要固定位置时传入绝对路径。CLI 参数为 `builtin|<dataset.json> <report.json> [data-directory]`。每次执行在数据目录下生成独立 H2 文件目录，不复用上一轮状态。报告写入成功后，如任意断言失败，CLI 抛出异常并使构建失败。数据库只保存合成场景数据，报告不包含 prompt、模型正文、工具参数或任意异常消息。

## 已冻结的数据集

`src/main/resources/datasets/engineering-v1.json` 定义输入及显式 expectations。当前 9 个场景：

| 场景 | 验证机制 |
|---|---|
| 研发发布规范问答 | 真正检索文档、返回引用；脚本模型从实际工具回包组装回答 |
| 其他项目问答 | 不得返回研发项目的文档 |
| 索引适配器返回越权命中 | RAG 返回前再次过滤 scope |
| 模型尝试指定文档 scope | JSON Schema 拒绝，工具 handler 不执行 |
| 模型选择未授予工具 | AgentLoop 拒绝，工具不执行 |
| 合成发布记录获批准 | 完整摘要绑定；批准前零写入；重开 SQL 后不重发 |
| 拒绝合成写入 | 零次工具 dispatch、零次副作用 |
| 已产生副作用但回执丢失 | UNKNOWN；恢复请求被拒绝，重开 SQL 与重复 tick 不重发 |
| SQL 重新打开并恢复 | 模型次数、token、预算、deadline、定义保持；耗尽预算不得再次调用模型 |

只读问答是第二业务场景；后三类写入与恢复案例是公共运行时故障注入，不能视为真实审批流程或外部服务验证。数据集预期变化需要 code review。新增 expectation 名字写错或观测值缺失会导致失败，不会被静默忽略。

JSON 报告记录数据集 SHA-256、场景 kind、逐项 expected/actual/pass、模型/工具尝试次数、合成 token 数、运行状态和本地耗时。耗时包含 JVM 和 H2 开销，不是远程调用延迟或 SLA；token 数来自脚本回包的固定 usage，不能用于成本推断。数据库恢复是 H2 文件关闭连接并重开运行时；没有声称验证 MySQL 的事务/锁兼容性或真实进程崩溃。

## 严格离线回放

`RecordedExchanges.RecordedModel` 和 `RecordedTool` 可从已审查 fixture 重放冻结结果。`model(...)` / `tool(...)` 工厂只构造记录，没有自动抓取真实请求或自动写文件功能；使用 `Json.write` / `Json.convert` 显式保存和读取。

- fingerprint 绑定 actor（subject、project、权限）、nodeId，以及模型 profile/messages/tools 或完整工具 descriptor/arguments。
- 为允许跨 run 回放，排除 runId、invocationId、attemptId、traceId、deadline；模型工具调用原生 ID 仍在 messages 中并参与 fingerprint。回放输入的语义必须完全一致。
- 未命中抛出 `REPLAY_MISS`，重复 fingerprint 拒绝装载；**没有真实调用 fallback**。
- 元数据必须声明 datasetId、project、`SYNTHETIC|REDACTED` 和脱敏审查说明。项目 scope 不一致拒绝。该声明不是自动脱敏或安全证明；保存真实录制前应人工清理凭据、个人信息和业务正文，并在脱敏后的输入上重新计算 fingerprint。
- 回放可以测试工具结果如何影响控制流，不能证明工具本身可用、模型能力或真实延迟。未知结果、权限和审批行为仍由 Harness 处理；不要通过 fixture 模拟实际写操作成功后据此决定真实业务状态。

适用边界：此模块是独立测试/评估工具，业务生产执行无需依赖它，也不会为平台引入在线评测服务或自动采集业务轨迹。
