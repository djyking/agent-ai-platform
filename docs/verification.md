# 首版完整基础版验证记录

日期：2026-09-15。代码分支：`codex/harness-foundation`，版本：`0.1.0-SNAPSHOT`。

本文保留阶段 0 的历史验证范围。后续真实模型、GitHub MCP、MySQL 联调及新增模块测试见 [阶段 1 进展](phase1-progress.md)。

## 构建环境与结果

- Windows、JDK 17.0.19、项目 Maven Wrapper 3.9.11。
- 已执行全仓 `clean verify`、`clean install`；最终补充未知写结果回归后执行全仓 `install -B -ntp`，全部成功，最终制品已更新到本机 Maven 仓库。
- 最终共 **107 项测试，0 失败、0 错误、0 跳过**。统计来源为各模块 `target/surefire-reports/TEST-*.xml`。
- GitHub Actions 已配置 Windows/Linux + JDK 17；尚未推送，不能将本地通过称为远端 CI 已通过。
- 本地构建日志保存在忽略的 `.work/verify-first.log`、`.work/verify-final.log`、`.work/verify-release.log`。其他开发者可在仓库根目录用 `mvnw.cmd clean verify -B -ntp` 复现测试。

| 模块 | 测试数 | 覆盖重点 |
|---|---:|---|
| harness-core | 43 | 原生工具调用配对、跨 Run invocation 身份、冻结配置、预算结算、权限/精确审批、队列期间取消/撤权/审批过期、超时、未知写对账、重试上限、遥测故障隔离、内存账本与 SQL 共用不变量 |
| harness-storage-jdbc | 24 | 创建幂等与并发 claim、数据库时钟、锁等待后的租约校验、fence/CAS、事务回滚、文件库重开、真实 Harness + SQL 的模型结算/审批/UNKNOWN 对账 |
| harness-adapters | 17 | 实际 HTTP 客户端和官方 MCP SDK、本机服务初始化/分页、动态凭据、响应大小/超时、契约漂移、原生 tool_call_id、Harness 治理链、OTel 导出与出站 traceparent |
| harness-capabilities | 18 | Prompt 文件版本/变量、RAG 范围和检索中撤权、引用长度、有界 Workflow、人工等待与独立写审批、恢复后消费已保存结果、嵌套 Agent |
| harness-examples | 5 | 两个通用示例、文件 H2 重开、错误路径拒绝、审批区分和一次合成写入 |

## 独立 JVM 示例验收

使用忽略目录 `.work/acceptance` 的文件 H2。下面每个 CLI 命令分别启动 JVM，退出后再执行下一条，并检查返回 JSON。

1. `qa`：COMPLETED，2 次脚本模型调用、1 次 RAG 工具调用、325 个合成 usage token；返回 `seedlings` 的授权引用，不包含另一个项目的文档。
2. `approval`：WAITING_INPUT，0 次写入；保存 run 与人工输入的 digest。
3. 新 JVM `decide ... HUMAN_DIGEST approve yes`：进入 WAITING_APPROVAL，仍为 0 次写入；生成不同的工具审批 digest，参数固定为 `demo-feature / enabled=true`。
4. 新 JVM `decide ... WRITE_DIGEST approve synthetic-test-only`：COMPLETED，工具调用计数为 1，`simulatedChanges=1`，回执固定为该 Run 的 invocation。
5. 新 JVM 再次 `tick` 同一 Run：保持 COMPLETED、工具计数 1、`simulatedChanges=1`。

本地验收 Run：QA 为 `5a994ded-6060-4340-ae12-618c84abbde9`；审批 Workflow 为 `69c1c599-c48f-478e-ac76-b38a5cd0aac7`。最终制品的 `demo-approval` CLI 也已运行，Run 为 `d15bfd48-d845-4de5-9073-e798cb6b1424`，完成且只产生一次合成写入。JSON 结果保存在 `.work/cli-*.json`；这些是开发机证据，不作为源码/测试依赖。

## 已验证的关键失败语义

- 未批准、拒绝、过期、digest 不匹配、工具契约变更或当前权限被撤回，都不执行对应工具。排队期间发生的变化在真正 dispatch 时再次检查。
- 任意写操作返回 UNKNOWN，或在 `IN_FLIGHT` 后失去 worker，保留不确定结果和对账入口；取消/暂停/总期限到期不会掩盖副作用。即使内部写工具声明 `retrySafe`，UNKNOWN 也不自动重放。
- 只读工具按策略退避重试；每次尝试累计调用额度，resume 不重置 `maxAttempts`。已知暂时失败的幂等内部写工具可按策略重试。
- 调用过程中暂停/取消，已知结果先保留；未知结果停止供对账。框架超时返回不代表任意远端效果已撤销。
- 已知模型 usage 通过同一 SQL 事务精确结算；未知 usage 保留原预留；恢复不更换冻结模型/Prompt 输入或重置总预算/期限。
- 同一注册项完成最后校验和实际调用；Telemetry 的 outcome/wrap/close 故障被隔离，已有结果不会因 exporter 故障被当作未执行。

## 未验证与后续验收

没有调用真实 DeepSeek 账号、第三方 MCP 或真实 MySQL；没有访问 OpsAgent 业务库、使用其密钥或修改业务代码。模型和 MCP 集成测试只访问测试内启动的 loopback HTTP 服务；H2 的 MySQL 兼容模式不能替代真实 MySQL 验证。

真实联调需宿主明确模型端点/模型名/凭据位置与用量上限、MCP 地址/凭据/允许工具及测试资源、独立 MySQL 开发库。先用合成数据测试认证、权限、分页、超时和 SQL 事务，再评估业务接入。吞吐/长期运行压力、跨实例共享限流、外部服务 SLA 和中台运维不是这 107 项测试的结论。

未提交、推送或发布公共制品；开源许可证与制品仓库仍由项目所有者确定。
