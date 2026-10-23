# OpsAgent 隔离源码试点

## 现有身份与持久路由验收（2026-09-18）

新增 `IsolatedIdentityBridgeTest` 使用实际 `AuthService`、MyBatis `UserMapper`、`JwtService` 和
`HarnessIdentityController`/`HarnessIdentityService`，连接合成账户 SQL 表。通过真实 HTTP 获取委托并签发
原 `InternalActorTokens`，再走实际 RAG、Knowledge 与 SQL 权限链。它补充旧 11 项回归，不把原 HashMap
Auth fixture 和内存路由测试重新解释成真实接入。

新增的正式业务入口是 `POST /api/rag/harness-search`，请求为 `{requestId, query, topK}`。
它默认选择 `LEGACY` 并复用既有授权检索实现；显式切换后，新请求选择平台异步 Run。
SQL `ops_harness_route` 的唯一键按应用、项目、主体与业务请求固定单一 owner，绑定输入摘要。
同请求不随开关变化；同键不同输入冲突；平台调用失败不转发给旧执行器。
平台创建重试使用同一持久 route UUID 作为幂等键。
旧路径是只读检索，重试会重新检查当前权限并再次检索，避免返回已失效的缓存知识。
本轮不替换生成答案的 `/api/rag/ask`，也不迁移旧工单 Agent 工作流。

OpsAgent 配置默认关闭：`ops.harness.identity.enabled`、`ops.harness.search.enabled`。
部署前显式执行 `harness-identity-schema.sql` / `harness-route-schema.sql`，配置受信应用的凭据摘要与现有用户项目授权。
`ops.harness.search.new-owner` 默认 `LEGACY`；开启新路径需设置平台 origin 与不可变 agent/release/digest。
仓库仅复制 Java/POM 和这两份无凭据迁移 SQL，不复制原应用资源或 `.env`。

`IsolatedBridgeMain` 是供本机平台验收使用的独立进程宿主：真实 Tomcat/Spring MVC、Auth/RAG/Knowledge
实现与专用 MySQL `opsagent_harness_pilot*` schema，合成账户 10/20 与七条合成知识。
它只读取单独的私有 JSON 配置，不加载原服务生产配置；控制停止文件可正常关闭三个服务。
配置字段为 `jdbcUrl/jdbcUser/jdbcPassword/applicationCredential/metadataFile/stopFile/platformOrigin`，
可选 `newOwner/releaseId/releaseDigest`。元数据文件含合成登录 JWT，应只放在忽略的 `.work`，不得共享或提交。
宿主监测配置文件修改时间，仅在同端口重建 RAG 入口；Auth/Knowledge 与 SQL 路由保持不变。

受保护历史检索结果通过 `POST /internal/rag/validate-citations` → Knowledge 的
`POST /internal/agent/validate-citations` 实时核验。每条引用必须有 documentId、chunkId、version，
当前主体必须仍能读取已发布文档，chunk 归属及版本必须一致；任何一条失效或域服务不可用都拒绝全部投影。
此端点不做新检索或模型生成。平台只对原应用和主体、纯 OpsAgent 检索 release 的原样输出使用此检查；
模型加工结果不能借引用放行，原运行委托过期后输出保持 OMITTED。
服务部署需设置 `OPS_KNOWLEDGE_INTERNAL_URL` 为受信 Knowledge origin（默认内部地址端口 8103）。

仍明确保留的边界：NoOp reranker、SQL fallback、不加载 ES/Nacos/MQ、未执行模型生成；
分布式限流及未触达的文件/模型等依赖仍为测试替身，刷新登录/验证码流程不参与。
这可以验证本机受控 HTTP 业务与现有身份模型，不证明原生产部署配置、服务发现或容量已验收。

`run.ps1` 的源码快照步骤只读原 OpsAgent 目录；本轮实现已向原项目新增显式桥接类与迁移文件。
测试仍使用合成账户与每次新生成的签名密钥，不加载原生产部署自动配置。

2026-09-15 已用下述 `run.ps1` 默认离线入口完成一次完整执行（prepare → 隔离 reactor → finalize）：**11 项测试通过，0 失败/错误/跳过**，158 个白名单源码/POM 文件运行前后 hash 一致。测试包含 10 项实际身份/检索链路验证和 1 项明确标注的内存归属策略示例。

## 运行

需要 Java 17、Python 3.12+、本仓库 Maven Wrapper，以及本机 Maven 仓库中的 `harness-integrations-opsagent:0.1.0-SNAPSHOT`。请先在本仓库完成正常 SDK `install`；隔离脚本不会再次构建公共框架 reactor。

从本仓库根目录执行，源码路径由使用者显式提供：

```powershell
./validation/opsagent-isolated/run.ps1 -OpsAgentRoot '<你的 OpsAgent 源码目录>'
```

默认 Maven **离线**，自动测试使用 H2，不使用真实模型、MCP、MySQL 或现有账户数据；新增套件执行实际 Auth 类。
独立的 `start-bridge.ps1` 启动专用 MySQL 宿主。如果依赖尚未缓存，明确允许构建下载公开依赖：

```powershell
./validation/opsagent-isolated/run.ps1 -OpsAgentRoot '<你的 OpsAgent 源码目录>' -AllowDependencyDownload
```

脚本每次创建 `.work/opsagent-isolated/<随机 UUID>/`，不删除旧输出；实际测试命令为复制的 OpsAgent reactor 上的 `test -pl isolation-tests -am`。只运行新隔离测试，未复制原测试/资源，也不执行原 Checkstyle、应用打包或服务启动流程。原服务的依赖版本以提供的 POM 为准；若未来源码 API 改动导致失败，应明确更新桥接测试，不通过替换实际权限代码让测试通过。

## 旧 11 项回归的实现边界

以下表格描述保留的 `IsolatedOpsAgentPilotTest`；新 `IsolatedIdentityBridgeTest` 的实际 Auth、持久路由、引用 ACL
验证见本文开头，报告按用例名称区分这两个套件。

| 环节 | 本试点使用 |
|---|---|
| 执行归属 | 实际 Harness、WorkflowProgram、OpsAgentRetrievalPilot、OpsAgentRagTool；每次一条只读工具调用、0 次模型调用 |
| HTTP 入口 | 手动启动并绑定 `127.0.0.1` 随机端口的真实 Tomcat/Spring MVC；注册实际 InternalAgentController 与 KnowledgeInternalAgentController |
| 身份核验 | 实际 InternalActorTokens 验证签名/issuer/audience/有效期，实际 InternalActorAccess 在 RAG 和 Knowledge 两次读取当前 Auth 状态并取角色交集 |
| Auth | **本地测试 host**：使用实际 tokens 验签后查询独立测试用户表；不是原 Auth 的用户数据库、登录流程或生产 IAM |
| 检索与引用 | 实际 InternalAgentSearchService、ContextAssembler、RerankService、内置 NoOpRerankProvider；明确关闭远程 reranker |
| 知识权限 | 实际 KnowledgeService/KnowledgeRepository，执行真实发布/可见性/所有者 SQL 过滤和再次校验 |
| 数据库 | 全新 H2 内存库，随机名称与随机测试密码；不读取原 SQL 初始化数据 |
| 服务间客户端 | 测试实现 InternalKnowledgeClient，使用实际 HTTP 发送到本机 Knowledge；不验证 Feign/Nacos/网关发现 |
| 其他依赖 | 索引服务禁用 mock 选择 SQL 路径；限流为 mock；文件/解析/MQ/模型等为未调用 mock，并断言无相应调用 |

复制白名单只有选定模块的 `pom.xml` 和 `src/main/java/**/*.java`。选定模块为 common 的七个子模块以及 rag/knowledge；隔离 parent 仅调整模块清单，追加测试模块。原 Java 源码不修改、不检入 Harness 模块。路径解析前拒绝符号链接/junction，解析后再次保证仍在明确提供的源码目录中。

不复制 `.env`、`src/main/resources`、`src/test`、application 配置、目标目录或业务数据。手动 MVC 只装配指定 controller 和统一异常处理，不进行 Spring Boot 自动配置或全包扫描。原 HTTP gateway/JWT filter/限流/自动发现均不属于这个验证范围；实际内部 controller 自身的专用签名与实时权限验证完整参与。

## 合成范围与断言

测试用户 **10、20** 均是独立测试身份；USER/ADMIN/AUDITOR 是合成角色状态。知识种子只有七条明确合成文档：公开已发布、用户 20 私有、未发布草稿、用户 10 私有、逻辑删除、无权关联工单和体验文档。

测试验证：

- 用户 10 只能看到公开文档和自己的私有文档，用户 20 返回不同私有范围，HTTP 结果保留可引用 documentId/chunkId/sourceId。
- Token 中曾有 ADMIN 而当前只有 USER 时降权；当前账号新增 ADMIN 也不能突破已签发身份的角色上限。
- 管理员检索仍排除草稿、删除、无权工单及体验文档。
- 撤销角色、禁用账号、RAG 验证后到 Knowledge 之前禁用账号均失败关闭。
- 错误 audience 和错误签名在实际内部 controller 被拒绝；错误 audience 不触发 Auth/Knowledge 调用。
- 文档可见性变化对后续 Run 生效，Harness 不缓存跨运行授权结果。
- 新生成签名密钥和 bearer token 不进入 Harness 持久状态/事件；没有模型调用、没有文件/MQ写入。

另外保留一项**内存路由策略示例**，仅作原始回归。实际持久 routeLedger 和新增业务入口由新身份桥接套件验证；
原生产部署配置、部署切流与正式观察窗口仍需独立验收。

## 证据和准确边界

每次运行输出：

- `source-manifest.json`：只在忽略的工作目录内，记录白名单源码相对路径和 SHA-256。
- `isolation-tests/target/surefire-reports/`：实际 JUnit 测试结果。
- `isolation-tests/target/isolation-report.json`：可共享的脱敏报告，包含案例结果、实际类/fixture 边界、源码清单摘要及白名单源码运行前后 hash 复核；不包含密钥、token、原配置或原业务内容。

报告的 `externalCalls=0` 指已配置测试调用链没有外部业务服务：RAG/Knowledge/Auth 地址均为 loopback、远程重排关闭、索引 mock。**没有抓包，也没有建立操作系统网络沙箱**；报告明确写出 `networkTrafficCaptured=false`、`runtimeExternalServicesConfigured=false` 和证据依据。允许下载时 Maven 会访问公开依赖仓库，这不属于测试业务运行的外部调用断言。

本试点证明实际 Java 业务实现可以在隔离身份和合成知识条件下通过 Harness 接入；不证明原部署的数据库、认证存储、网关、服务发现、模型质量、Elasticsearch、共享限流或生产路由已经通过验收。
