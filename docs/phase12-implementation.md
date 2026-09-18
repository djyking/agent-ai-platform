# 阶段一、二实施与验收记录

2026-09-18。本轮承接用户确认的“先本机完整验收、接现有身份、复用当前 GitHub 仓库”，实现集中执行服务并补齐阶段一真实接入。代码分支 `codex/harness-phase1-phase2`；基线 `f48fbae6bb8f0901c360215e1a8448d44900185b`。本机阶段一、二验收已闭合，历史规划保留在 [原评估](status-and-next-plan-20260918.md)。

## 已实现

| 范围 | 具体交付 |
|---|---|
| 核心可靠性 | 确定性取消竞争修复；带 expectedRevision 的控制/审批/对账；等待清扫；JDBC 连接内嵌套事务和整体回滚 |
| 阶段一身份/路由 | 使用现有 OpsAgent AuthService、JWT、SQL 用户及权限；应用/项目 grant、运行委托、动态撤权；新业务入口持久冻结 LEGACY/HARNESS 归属和准确 release，失败不自动切另一执行器 |
| 阶段一真实写/质量 | 指定 GitHub 仓库独立分支的审批写、响应受控丢弃和独立对账；预先冻结的 10 例合成检索/真实 DeepSeek 回答质量基线 |
| 阶段二服务 | 新 `harness-platform-service`：11 个 HTTP 操作、持久 worker、项目/应用/主体隔离、原子幂等响应、共享配额、签名 ETag/游标、不可变发布、Secret Provider |
| 审批与输出 | 审批分配、摘要、review grant、理由与输入分离；UNKNOWN 受信证据离线导入及精确对账；纯 Ops 检索结果读取时逐引用复查当前 ACL |
| 运维 | 可执行 Boot JAR、部署示例、SQL 迁移、健康探测、脱敏操作日志、备份脚本和隔离恢复流程；实际 Java/MySQL 进程验收脚本 |

SDK 仍可独立嵌入，不依赖 Spring。集中服务承担 API 与 worker 职责，复用相同 core/storage/adapters/capabilities。应用侧无需自己调用 tick。

## 实际证据

| 验收 | 结果与证据 |
|---|---|
| GitHub 真实受控写 | 7/7，通过 [写验收报告](validation/20260918/github-write-acceptance.json)。只建专用分支和两个合成文件，3 次 MCP 写，无重发 |
| 真实模型质量基线 | 10/10，10 次 `deepseek-v4-pro` 调用、5038 已知 token；金标 recall 7/7、必需事实 6/6、引用规则 10/10、零测试 marker 泄露。[冻结计划](validation/20260918/live-quality-plan-v1.json) / [逐例结果](validation/20260918/live-quality-v1.json) |
| OpsAgent + 平台 + MySQL HTTP | 11/11，包含真实现有身份类/SQL grants、两条执行路径、平台受保护输出、实时 ACL/版本撤销、路由切换与重启、失败不回退、研发第二场景。[报告](validation/20260918/opsagent-local-mysql-acceptance.json) |
| 两 Java 进程与 MySQL 故障恢复 | **9/9 通过**；等待态暂停恢复、审批写、共享配额、并发峰值 1、非零预算跨进程恢复、真实 socket 断开后的 UNKNOWN/独立证据对账、MySQL 正常停/启。[最终报告](validation/20260918/platform-20260918-process-r7-report.json) |

普通 Maven 测试、OpsAgent 源码测试、真实进程故障验收和外部服务报告分别记账，不把重叠案例简单相加。目录中保留早期失败/子集报告，最终通过以本页指定报告为准。

## 最后验收

- **全仓 clean verify：9 模块，198 项，0 失败/错误/跳过。** [构建清单](validation/20260918/build-manifest.json) 逐项记录 XML、日志、源码快照和 JAR 哈希；平台 31 项包含 11 HTTP 操作、8 个身份组合与 56 个跨归属检查、事务回滚、独立 worker、ACL 投影和离线回执导入。
- **最终平台 JAR SHA-256：** `e1b5eccd9275f1caf3473f930d7ccbbf6d31fb3b125c1c228af17d2f129871f6`。完整 9 项进程验收和 OpsAgent HTTP 11 项复验使用同一 JAR。
- **MySQL 重启：** 原专用进程正常关闭后两个 API health 均为 503，SQL 不可连接；新 PID 启动相同数据目录后，原 Run 保留 deadline、limits、22 tokens / 1 model call，审批后完成且模型没有重发。
- **备份隔离恢复：** 两个平台数据库各恢复到全新独立库，10 组表数据/哈希逐项一致，保留已接受命令、预算、审批与 UNKNOWN 证据；恢复库未启动 worker。[故障验收库报告](validation/20260918/mysql-platform-restore.json) / [Ops 平台库报告](validation/20260918/mysql-ops-platform-restore.json)。未覆盖原库，没有尝试重放外部效果。
- **OpsAgent 最终源码：** [隔离测试 16/16](validation/20260918/opsagent-isolated-report.json)，[Auth/RAG/Knowledge 定向 verify 15/15，含 Checkstyle 与打包](validation/20260918/opsagent-targeted-verify.json)。仅本任务 10 个新增文件保存在 [可复核补丁](validation/20260918/opsagent-phase12.patch) 和 [文件哈希](validation/20260918/opsagent-phase12-files.json)，保留原工作区其他未提交修改。HTTP 宿主与最终 Ops 源码的一处错误码防溢出加固差异已在报告注明，源码测试覆盖该加固。
- **原 CI 取消竞争：** [确定性前后对比](validation/20260918/cancellation-race.json) 在旧实现得到 RUNNING/IN_FLIGHT、零派发；修复后 CANCELLED/PREPARED、零派发。不是增加 sleep 或放宽断言。另修复等待审批/人工输入暂停后恢复误失败，保留原审批实例和期限。
- **API 契约：** 11 操作、11 schema 示例、31 HTTP 示例和 33 正反案例通过。`Verify` workflow 分别检查 Windows、Linux、API，并关闭矩阵 fail-fast，避免一个失败把另一个证据取消。首轮远端 Linux/API 通过，Windows 揭示旧 100ms 响应超时测试未确认已派发的前提；已仅用测试预热和服务端双门闩修正，定向 16/16 通过，已验收生产 JAR 不变，见 [诊断与修复](validation/20260918/ci-timeout-test-diagnosis.json)。远端对应提交结果见 [该功能分支的 Actions](https://github.com/djyking/agent-ai-platform/actions?query=branch%3Acodex%2Fharness-phase1-phase2)。

## 接入与当前边界

- 运行步骤、两种认证头、发布、审批、对账、备份与恢复见 [平台运维说明](platform-operations.md)。[OpenAPI](api/openapi.json) 为实际 11 操作契约。
- OpsAgent 使用本机隔离的真实 Auth/RAG/Knowledge 组件、MySQL 和合成用户/知识；原生产 Boot/Nacos/ES/MQ 配置没有整套启动或切流。新增 `/api/rag/harness-search`，原 `/api/rag/ask` 的完整生成链路没有被宣称迁移。默认新请求归属已恢复 LEGACY。
- GitHub 响应丢失是收到真实成功响应后受控丢弃；持久进程/断 socket 故障另由本机合成工具验证。两种证据不混为“杀进程重试真实 GitHub 写”。
- 质量基线是小型英文合成语料和自动规则，不代表全部中文业务答案、向量检索、生产性能或所有提示攻击均已验收。
- 受保护输出首版只支持纯 Ops 检索、原应用/原用户及有效运行委托；跨主体管理员、模型加工答案和委托过期的历史正文保持省略。通用血缘和长期历史结果授权留待后续设计。
- 当前保留全部幂等记录和事件；尚无自动保留期清理、生产 RPO/RTO/SLO 或集中观测后台。阶段三的资源目录、发布界面、控制台和薄 SDK 尚未实现。

这些交付把能力推进到可本机验收的内部集中执行服务。完整中台 MVP 仍按原路线在阶段三达成，不能据此宣称生产级中台或全业务迁移已经完成。
