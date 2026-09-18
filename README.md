# Agent AI Platform

可嵌入 Java 应用的 Agent Harness 与公共能力，当前版本 `0.1.0-SNAPSHOT`。支持业务应用通过 Maven 引用 SDK，也提供复用同一内核的集中执行服务。

已实现 AgentLoop、内部/MCP 工具治理、模型适配、Prompt、轻量 RAG、Workflow、SQL 持久化以及日志/tracing。嵌入式 SDK 不依赖 Spring 或 OpsAgent 业务代码；`harness-platform-service` 提供集中执行、资源目录与发布、现有身份接入和持久 worker，独立中文控制台位于 `platform-console/`。

## 快速运行

需要 JDK 17 和首次下载 Maven/依赖时的网络连接。仓库自带 Maven Wrapper 3.9.11，不需要修改系统 Maven。

```powershell
# 在仓库根目录运行；install 同时验证并安装到本机 Maven 仓库
.\mvnw.cmd clean install -B -ntp

# 示例一：Agent → 授权检索 → 带引用的回答
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=qa D:/harness-demo'

# 示例二：人工输入 → 条件分支 → 独立写审批（在等待处停止）
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=approval D:/harness-demo'

# 明确的自动演示：只批准、修改本地 H2 中的合成测试资源
.\mvnw.cmd -pl harness-examples exec:java '-Dexec.args=demo-approval D:/harness-demo'
```

Linux/macOS 使用 `bash ./mvnw`，将目录替换为本机路径；需要直接 `./mvnw` 时先执行 `chmod +x mvnw`。模型调用采用确定性脚本，示例无需模型密钥或外部 MCP。文件数据库支持退出 JVM 后继续审批和执行；完整命令见 [示例说明](harness-examples/README.md)。

## 模块边界

| 模块 | 职责 | 引用方式 |
|---|---|---|
| `harness-core` | 公共契约、AgentProgram、Harness、工具注册/校验、权限/审批、预算、重试、受限执行池、运行事件、内存存储与测试替身 | 必选 |
| `harness-storage-jdbc` | SQL 执行账本、状态与事件事务、数据库时钟租约、revision/fence、创建幂等、H2/MySQL 适配 | 持久运行时选择 |
| `harness-adapters` | DeepSeek 文本与原生工具调用、官方 MCP SDK Streamable HTTP、OpenTelemetry 桥接 | 按外部协议选择 |
| `harness-capabilities` | 版本化 Prompt、授权检索与引用、顺序/条件/人工等待/嵌套 Agent 的有界 Workflow | 按需求选择 |
| `harness-examples` | 问答与审批工作流、文件数据库、跨命令恢复 CLI | 开发验证 |
| `harness-integrations-opsagent` | OpsAgent 内部知识检索适配、可信宿主身份扩展点、检索 Workflow | OpsAgent 试点按需引用 |
| `harness-evals` | 第二类研发知识场景、9 项工程回归数据集、严格离线录制回放 | 回归与评估 |
| `harness-validation` | 显式触发的真实模型、GitHub 受控写、质量基线及独立 MySQL 验收 CLI | 接入验证 |
| `harness-platform-service` | 11 操作 HTTP API、OpsAgent 身份桥、SQL worker、平台归属/幂等/审批/共享配额 | 集中服务部署 |
| `harness-platform-client` | JDK HTTP 薄客户端、运行/审批/对账与已发布 Agent 发现，不包含执行引擎 | 独立业务调用方 |
| `support-pilot` | 独立应用身份的合成客服试点，FAQ、人工确认、运行记录 | 接入示例 |
| `platform-console` | React/TypeScript 控制台、八类资源、Workflow 编辑、发布回归、运行/事件/trace、审批与 UNKNOWN 对账 | 与平台服务同源部署 |

模型管理分两层：SDK 的 `ModelProfile` 固定某次运行的 provider、模型名、参数和限额，`ModelRouter` 负责宿主显式注册的 provider 路由；控制台可选择受信模型并缩减预算，密钥中心和供应商成本治理留待后续。Prompt、RAG、Workflow 是可选能力包，统一通过 Harness 执行模型/工具动作。

## 业务项目如何引用

执行上面的 `install` 后，业务项目添加所需模块。例如：

```xml
<dependency>
  <groupId>io.github.djyking</groupId>
  <artifactId>harness-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

SQL、MCP/模型协议、组合能力分别增加同版本的 `harness-storage-jdbc`、`harness-adapters`、`harness-capabilities`。跨机器共享时再发布到约定的 Maven 制品库；目前未发布制品、未决定许可证。

最小调用示例（省略标准 import）：

```java
var profile = new ModelProfile("demo", "scripted", "local", 16000, 256, 5000, Json.object());
var definition = new ProgramDefinition("hello", "1", "agent", Json.tree(
    new AgentProgram.AgentSpec(profile, List.of(Message.text("user", "Hello")), 2)));
// 正式宿主应传入已认证身份和当前授权策略；这里是本地示例。
var actor = new Actor("demo-user", "demo-project", Set.of("run:create", "run:read", "model:invoke"));
try (var harness = new Harness(new InMemoryRunStore(),
        new ScriptedModel(ScriptedModel.answer("Hello from Harness")), new ToolRegistry())) {
    var run = harness.start(definition, actor, List.of(), Budget.defaults(),
            Duration.ofMinutes(1), "request-001");
    while (run.status == RunStatus.QUEUED) {
        var next = harness.tick(run.id);
        if (next.revision == run.revision) break; // 重试时间未到，交回宿主调度器
        run = next;
    }
    System.out.println(run.status);
}
```

公共契约位于 `io.github.djyking.harness.core.Contracts`，其余核心类在同一包，`ScriptedModel` 位于 `.core.testing`。正式宿主负责调度 `tick`/`tickReady`、提供认证与动态授权、管理 DataSource/凭据，并展示/提交审批。SDK 不自启后台轮询；使用集中服务时，平台 worker 负责调度。内部执行池承担有截止时间的模型和工具调用。

## 已实现的工程约束

- 工具由宿主显式注册；MCP 发现结果与服务端注解不能自行获得执行权限。工具集、schema 与本地策略固定在每次运行中，真正调用时再检查当前授权和契约。
- 审批绑定运行、身份、步骤、工具契约及参数；人工输入和写工具审批是两个独立步骤。替换工具不会使旧审批授权新契约。
- 意图与 `IN_FLIGHT` 边界先落库，再发起外部调用。无远端幂等保证的未知写操作进入 `NEEDS_ATTENTION`，通过已核验回执对账，不自动重放。
- 预算覆盖 token、模型/工具调用次数、步骤与总期限；重试和恢复继续累计。已知模型用量按实际结算，未知用量保留保守预留。默认执行池 4 并发、32 等待，可配置。
- 每次尝试都有全局 invocation/attempt 标识、持久事件和可插拔 span。默认结构化日志不写模型正文、工具参数或凭据；数据快照仍需要宿主保护。

## 当前范围与后续建设

RAG 提供小语料词法检索及外部检索扩展点；Workflow 提供有界图执行和结构化节点编辑。尚无向量索引托管、并行工作流/补偿。平台已有 SQL 多 worker 与项目/应用基本配额，尚未实现生产容量治理与分布式速率限制。MCP 首版为 Streamable HTTP 和宿主管理的请求头凭据；stdio、OAuth 流程、模型流式/多模态是后续适配方向。

阶段一、二包括现有 OpsAgent 身份桥、持久请求路由、真实 GitHub 受控写及 DeepSeek 质量基线，以及集中执行 API/worker，见 [阶段一、二交付记录](docs/phase12-implementation.md)。阶段三增加控制台、资源目录与发布、薄 HTTP 客户端和客服试点，验收状态与边界见 [阶段三实施记录](docs/phase3-implementation.md)。

阶段四将首页重做为应用 Studio：从模板构建、同页真实体验、固定样本评测到发布，再进入任务与结果工作台。新增独立身份选择、文本知识版本/ACL、模型与 MCP 连接生命周期，原高级资源和运行治理继续保留。范围、验证与后续阶段见 [第四阶段实施记录](docs/phase4-implementation.md)。

构建完整控制台服务使用 `./deploy/platform/build.ps1`（Node.js 24、pnpm 11.19.0、JDK 17）；访问服务的 `/console/`。部署与三应用配置见 [阶段三部署说明](docs/phase3-deployment.md)，资源协议见 [目录 API](docs/catalog-api.md)，备份与恢复见 [平台运维说明](docs/platform-operations.md)。直接运行 Maven 仅构建 Java；控制台资源需先执行 `pnpm --dir platform-console install --frozen-lockfile` 与 `pnpm --dir platform-console build`。

- [2026-09-18 当前进度核对与下一步计划](docs/status-and-next-plan-20260918.md)
- [架构与可靠性语义](docs/architecture.md)
- [方案 A 实施记录](docs/plan-a-implementation.md)
- [从 Harness 到 Agent 中台的分阶段路线图](docs/agent-platform-roadmap.md)
- [阶段 1 进展与真实联调证据](docs/phase1-progress.md)
- [阶段 2 执行 API 设计](docs/phase2-api-design.md) · [OpenAPI 与契约验证](docs/api/README.md)
- [OpsAgent 接入试点](docs/opsagent-pilot.md)
- [OpsAgent 隔离源码试点复现](validation/opsagent-isolated/README.md)
- [研发场景与回归基线](harness-evals/README.md)
- [真实服务验证 CLI](harness-validation/README.md)
- [SDK 版本与状态兼容](docs/sdk-versioning.md)
- [验证记录](docs/verification.md)
- [模型、MCP 与 tracing 接入](harness-adapters/README.md)
- [Prompt、RAG 与 Workflow](harness-capabilities/README.md)
- [SQL 存储和迁移](harness-storage-jdbc/README.md)
