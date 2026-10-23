# 第五阶段公开验收证据

范围：本机工程验证。此目录不含真实凭据、cookie、私有配置、业务正文或数据库备份。实现与未完成项目见 [实施记录](../../phase5-implementation.md)，复跑方法见 [运行器说明](../../../validation/platform/PHASE5.md)。

| 文件 | 内容 |
|---|---|
| `execution.json` | 最终制品、两 JVM、两项目、十万合成历史、10/20 RPS、排队、强杀、未知写、告警、归档及治理页采样 |
| `real-models.json` | 最终制品的真实模型调用结果与用量；不保存回复正文 |
| `https-proxy.json` | 本机 TLS 代理和真实 JVM 的 16 项会话/CSRF/可信代理检查 |
| `schema-migration.json` | 无活跃 Run/held permit 的本机控制台库从平台 schema 1 升至 2、保留核心版本和验证归属投影 |
| `deployment.json` | 部署 fixture、Compose 静态解析、JAR 对部署/治理示例的离线契约解析 |
| `verification.json` | 运行环境、制品哈希和自动化测试汇总 |
| `earlier-attempts.json` | 之前失败与局部通过的记录，不能替代最终验收 |

本机持续检查实际为 60 秒，`completed24Hours=false`。Linux 环境、七天观察、真实异机备份/恢复、RPO/RTO 和月度可用性没有被这些测试证明。故障和压力测试仅访问隔离的合成 fixture；真实 DeepSeek/OpenAI 联调单独记录，不把合成历史数量当成真实模型调用量。

历史尝试发现并修复：fixture 第二项目身份选择、十万行装载时的 MySQL 小 buffer/redo 配置、历史计数正则、UNKNOWN 心跳的时序断言，以及持项目锁做全历史汇总造成的创建尾延迟。统计查询还增加平台归属投影，避免每次治理概览跨表读取十万条历史。失败记录保留，最终测试没有放宽 500ms/2s 门槛。

源码/文档由最终 Git 提交绑定；JAR 由报告中的 SHA256 绑定。这不是签名供应链证明。交付 ZIP 为避免混入私密验收内容，排除此目录；需要证据的接收者应从对应仓库提交读取。
