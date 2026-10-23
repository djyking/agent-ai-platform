# 阶段 1 进展与阶段 2 API 交付

> 本文保留规划/历史基线；2026-09-18 阶段一、二实现与最新证据见 [交付记录](phase12-implementation.md)。平台服务已新增，本文早期“尚未实现”描述不代表最新代码状态。

日期：2026-09-15。版本：`0.1.0-SNAPSHOT`，分支：`codex/harness-foundation`。

> 2026-09-18 复核：本文保留交付时的历史状态。代码此后已提交并推送至功能分支 `f48fbae`，远端 CI 已运行但未全绿；本轮 135 项本地测试、9 项工程回归及 API 契约检查通过。最新状态与后续安排见 [当前进度核对与下一步计划](status-and-next-plan-20260918.md)。

阶段 1 已进入实现和验证：真实模型、GitHub 只读 MCP、独立 MySQL 的基础联调通过，OpsAgent 隔离源码检索链路通过，第二类研发知识场景已实现；阶段 2 已完成可校验的 API 草案。目前仍是 SDK 和验证工具，尚无共享执行服务、worker 集群或管理控制台。

## 工作包状态

| 工作包 | 已交付 | 剩余验收 |
|---|---|---|
| P1-1 真实依赖 | DeepSeek V4 原生工具调用、GitHub 官方只读 MCP、MySQL 8.0.46 账本验收；独立 `harness-validation` CLI | 真实外部写工具的专用沙箱审批/断连验收；目前 UNKNOWN 与写入仅为本地合成效果 |
| P1-2 OpsAgent | 固定内部检索接口 adapter、Workflow、11 项 SDK HTTP 契约测试；另有 10 项实际源码隔离链路测试与 1 项内存路由策略测试通过 | 现有部署的可信身份桥、持久请求归属、路由开关和真实环境差异尚待接入验收 |
| P1-3 通用场景与回归 | 研发知识场景、9 项数据集案例、严格离线录制回放、8 项模块测试 | 真实业务回答/检索质量数据集；脚本通过率不能作为模型质量指标 |
| P1-4 接入契约 | SDK/资源/持久状态版本约定；阶段 2 OpenAPI 3.1 与验证器 | 阶段 2 的服务、原子控制事务、应用归属、凭据托管与部署实现 |

阶段 1 的完整验收仍包含业务请求归属/回退与真实受控写操作；不能仅以三项依赖探针通过宣称本阶段或中台已完成。完整阶段划分见 [路线图](agent-platform-roadmap.md)。

## 真实联调证据

所有联调均由用户明确授权。共享报告仅含版本、状态、调用次数、用量或摘要，不含密钥、业务正文、原始响应和连接凭据。

| 目标 | 结果 | 可复核证据 |
|---|---|---|
| DeepSeek | 使用服务端目录实际列出的 `deepseek-v4-pro`，2 次模型调用 + 1 次本地 echo，Run COMPLETED；已知实际 usage 与 Harness 结算均为 **767 tokens** | [模型报告](validation/20260915/live-model.json) |
| GitHub MCP | 官方远程 Streamable HTTP，只读 repos 工具集；`get_file_contents` 读取公开 `github/github-mcp-server` 的 README.md，1 次工具调用、0 次模型调用，Run COMPLETED | [MCP 报告](validation/20260915/live-github-mcp.json) |
| MySQL | 本机 MySQL **8.0.46** 独立实例、专用 `harness_validation_phase1` 数据库，6 组账本检查通过 | [MySQL 报告](validation/20260915/live-mysql.json) |

模型凭据按用户授权从 OpsAgent 的本地配置注入，未改写原配置。原配置的 `deepseek-v4-flash` 未出现在本轮认证查询返回的模型目录中，因此选择实际可用的 V4 Pro。只发送合成协议提示及 `HARNESS_OK`，没有发送 OpsAgent 文档。两次模型调用是固定测试流程，不是给用户追加的账户消费上限；767 是 token 用量，不是货币账单或完整模型质量评估。

GitHub 连接使用本机凭据管理器已有凭据，直接连接 [官方 GitHub MCP 服务](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md) 的 `https://api.githubcopilot.com/mcp/x/repos/readonly`。没有安装本地 MCP 进程，没有新增 PAT，没有写仓库、发送消息或创建 PR。源码版本/文件变化会使报告摘要变化，摘要不是永久内容快照。

原本机 MySQL 服务停止，当前进程无法启动该服务，因此使用本机已安装的 MySQL 程序启动单独的 loopback 实例及数据目录；未进入原服务的数据目录，也未访问 OpsAgent 业务表。验证覆盖创建幂等、并发领取与 fence、控制 CAS 与回滚、延迟保存后的过期租约拒绝、模型用量结算与对象重建、审批与 UNKNOWN 对账。这里的模型/副作用/回执均为合成数据，worker 重建仅指同一 JVM 中重建 Harness/RunStore，不代表真实进程崩溃、MySQL 重启、主从切换或容量测试。

验证实例已通过认证管理命令正常关闭，端口 `53954` 不再监听，合成数据库保留在忽略的 `.work/mysql-phase1/data`。本机复用配置为 `.work/mysql-phase1/my.ini`，临时启动 SQL 已移除，专用密码仅保存在用户绑定的 DPAPI 文件中。该目录是开发机验收数据，不是部署配置或公共 SDK 的依赖。

## OpsAgent 隔离业务试点

用户选择使用隔离环境、合成知识和专用测试用户后，已完成独立入口 [validation/opsagent-isolated](../validation/opsagent-isolated/README.md)。默认离线 `run.ps1` 已完整运行 prepare → Maven 测试 → finalize，**11 项全部通过**，见 [隔离试点报告](validation/20260915/opsagent-isolated.json)。这组独立测试不计入下文 135 项 SDK reactor 测试。

实际执行链是 Harness/Workflow → OpsAgentRagTool → loopback Tomcat/Spring MVC → 原 InternalAgentController → 原签名和当前身份复核 → 原 InternalAgentSearchService → 原 KnowledgeInternalAgentController/KnowledgeService/KnowledgeRepository → H2 合成文档 → 原上下文/引用组装。10 项链路测试验证用户 10/20 各自私有文档范围、ADMIN 与当前角色的交集、不能突破签发时角色上限、角色撤销/账号禁用、RAG 与 Knowledge 之间撤权、错误 audience/签名、后续请求中的可见性变化，以及密钥/token 不进入 Harness 状态和事件。

Auth 用户状态是独立测试 host，reranker 使用原实现的 NoOp 分支，索引禁用后走真实 SQL fallback，分布式限流及文件/MQ/模型依赖是明确的测试替身。没有复制原 `.env` 或 application 资源，也没有发送知识到外部模型。158 个白名单 Java/POM 文件运行前后的 SHA-256 一致。隔离指本次配置、用户和数据；未使用网络抓包或操作系统网络沙箱，报告已注明。

第 11 项为内存中的请求归属策略示例：开关只选择新请求的执行器，已有归属不被切换，处理失败不自动转另一执行器。它没有提供生产持久 routeLedger，也没有改写现有 OpsAgent 入口。现有部署的 Auth 存储、Feign/Nacos/网关、MySQL 知识库、ES、远程重排和切流验收仍是后续工作。

## 工程回归与 API 检查

已执行全仓 `clean install -B -ntp`：**135 项测试，0 失败、0 错误、0 跳过**，制品已安装到本机 Maven 仓库。[构建清单](validation/20260915/build-manifest.json) 记录本地分支/基准提交、未提交状态及 8 个 JAR 的 SHA-256，不将开发 SNAPSHOT 伪装成正式发布。普通 Maven 测试不调用真实模型、远端 MCP 或真实 MySQL；适配器测试使用 loopback HTTP，SQL 测试使用 H2。

| 模块 | 测试数 |
|---|---:|
| core | 46 |
| storage-jdbc | 24 |
| adapters | 17 |
| capabilities | 18 |
| examples | 5 |
| integrations-opsagent | 11 |
| evals | 8 |
| validation | 6 |

独立数据集 CLI 已运行，**9/9 场景通过**，见 [工程回归报告](validation/20260915/engineering-report.json)。报告保留 `SCRIPTED_ENGINEERING_REGRESSION` / `SYNTHETIC` 标记；验证权限、引用、工具选择约束、审批、UNKNOWN 和恢复预算，不以固定模型回包的 token 或本地耗时推算模型费用和 SLA。构建与 CLI 原始日志在忽略的 `.work/phase1-build-final.log`、`.work/phase1-evals-cli.log`。

API 草案已验证 **11 个操作、11 个 schema 示例、31 个 HTTP 请求/响应示例、33 个正反案例**。覆盖 Run 创建/列表/查询/事件、暂停/取消/恢复、审批决定、UNKNOWN 查询与已核验证据对账。见 [API 设计](phase2-api-design.md) 和 [验证方法](api/README.md)。

设计明确区分服务端身份与请求输入、不可变发布版本与运行快照、幂等键与原子 If-Match、审批与人工输入、UNKNOWN 与普通失败。当前 SDK 仍需在阶段 2 增加 expectedRevision 控制入口及同事务命令幂等，不能用 HTTP 层“先查再调用”冒充原子控制。本轮修复了预算耗尽终态可被 pause/cancel 改写的问题，新增三项回归覆盖 pause/cancel/resume 均不能改变耗尽运行。

## 复现与后续入口

```powershell
# 仓库根目录：离线依赖下载完成后，测试不需要业务凭据
.\mvnw.cmd clean install -B -ntp
.\mvnw.cmd -pl harness-evals exec:java '-Dexec.args=builtin .work/engineering-report.json .work/eval-data'
& .work/api-contract-env/Scripts/python.exe docs/api/validate_contract.py
```

真实服务验证需要显式环境变量和新报告路径，见 [验证 CLI](../harness-validation/README.md)；OpsAgent 身份及接口映射见 [试点说明](opsagent-pilot.md)，回归边界见 [数据集说明](../harness-evals/README.md)，后续发布约束见 [SDK 版本约定](sdk-versioning.md)。

代码保留在本地工作区，未提交、推送、公开发布制品或切换 OpsAgent 的现有执行路径。GitHub Actions 尚未远端运行，本地结果不等于远端 CI 通过。
