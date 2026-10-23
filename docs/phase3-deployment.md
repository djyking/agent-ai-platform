# 阶段三本机部署与业务接入

本页给出可复现的内部试用配置。平台提供同源管理控制台，Ops 应用与独立客服应用分别调用平台；它们是三个不同的应用身份，其中两个是业务接入方。沿用现有 OpsAgent 用户与授权源，不新增账号体系。

公开示例文件：

- [phase3-deployment.example.json](../deploy/platform/phase3-deployment.example.json)：两项目、三应用、受信 DeepSeek profile、只读 Ops 检索和合成 FAQ。
- [phase3-catalog.examples.json](../deploy/platform/phase3-catalog.examples.json)：八种资源的草稿正文及一个冻结回归样例，按数组顺序发布。
- [phase3-ops-catalog-permissions.patch](../deploy/platform/phase3-ops-catalog-permissions.patch)：在已有 Ops Harness 身份桥基础上增加四项 catalog 权限白名单及回归测试。

示例仅含公开端点、环境变量引用和合成数据，没有可用凭据。`18081`/`18083` 是需要替换的已有 Auth/RAG 服务地址；本次本机验收的平台端口为 `8099`，客服应用为 `8100`。不要把验收宿主的临时地址当作生产配置。

## 身份与权限准备

在现有身份管理源登记三个不同的应用凭据摘要，并配置项目、用户、必需角色与具体 permission grant。Ops 用户必须仍然有效，JWT 角色与当前用户角色必须满足 grant；白名单认可某个权限不等于自动向用户授予该权限。

| 应用 ID | 项目 | 用途与权限范围 |
| --- | --- | --- |
| `platform-console` | `ops-dev`、`support-pilot` | 管理者按职责授予 `catalog:read/write/validate/publish`；运行管理员另授 `runs:list/read/events:read` 与明确的 `runs:admin` |
| `opsagent-pilot` | `ops-dev` | 业务调用者授 `runs:create/read/list/events:read/output:read/control`，只读检索另需 `tool:opsagent/rag-search`、`opsagent:rag:search` |
| `support-pilot` | `support-pilot` | 客服授 `runs:create/read/list/events:read/output:read/control` 与 `tool:support:faq`；确需模型调用时再授 `model:invoke` |

权限表中的斜线是同前缀并列简写。例如 `catalog:read/write/validate/publish` 表示 `catalog:read`、`catalog:write`、`catalog:validate`、`catalog:publish` 四个独立权限；不是一个字符串。控制统一使用 `runs:control`，不存在 `runs:pause`、`runs:resume` 或 `runs:cancel` 权限。

审核员使用自己的现有身份，授予 `approvals:read`、`approvals:review`、`approvals:decide`，并在发布的 ToolPolicy `approvers` 中精确指定 `applicationId/subject`。示例 `support-pilot/20` 只适用于对应应用及真实用户号；控制台中的同号用户是 `platform-console/20`，不会自动继承这个审批分配。普通审核员可以直接读取审批投影，不必获授跨所有者的完整 Run 读取权。UNKNOWN 核对还需 `runs:reconcile:read` / `runs:reconcile`，仅对受信操作人员授予。

Ops 身份桥的生产类 `HarnessIdentityService` 已有 `runs:admin` 白名单；阶段三另外需要上述四个 catalog 项。先确认已有阶段二身份桥、授权表及领域接入，再在 Ops 仓库用 `git apply --check --ignore-space-change <patch-path>` 检查增量补丁；已包含该修正的部署不用重复应用。补丁只改权限白名单和相关测试，不注册用户、不写真实 grant、不改变角色/委托上限规则。

## 平台受信部署配置

将公开 deployment 示例复制到受保护的本机部署目录，调整已有 Auth/RAG origin、用户号、project/app 集合和预算。文件中的真实配置仍由管理员管理；网页目录不能添加 URL、secretRef、请求头、任意 provider 或工具进程。

| 平台进程环境变量 | 说明 |
| --- | --- |
| `HARNESS_CONFIG` | 私有 deployment 文件的绝对路径 |
| `HARNESS_PORT` | 本机示例 `8099` |
| `HARNESS_JDBC_URL` | 已准备的专用 MySQL 8.0 数据库 JDBC URL |
| `HARNESS_JDBC_USER` / `HARNESS_JDBC_PASSWORD` | 专用数据库凭据，和 URL 分开注入 |
| `HARNESS_SIGNING_KEY` | 至少 32 字符的稳定签名密钥；重启必须保持一致 |
| `HARNESS_CONSOLE_APPLICATION` | `platform-console`，也是默认值 |
| `HARNESS_CONSOLE_APP_CREDENTIAL` | 控制台独立应用凭据，与身份源中登记的摘要一致 |
| `HARNESS_OPS_APP_CREDENTIAL` | Ops 业务应用凭据 |
| `SUPPORT_APPLICATION_SECRET` | 客服独立应用凭据；客服进程使用同一个已登记值 |
| `HARNESS_DEEPSEEK_API_KEY` | 示例受信模型 adapter 的凭据，页面不会获得它 |

示例模型 adapter 为 `deepseek`，HTTPS endpoint 固定在受信 deployment，profile ID 为 `trusted-support-deepseek`。目录中的 ModelProfile 只能选择该受信 profile，并降低输出 token 和超时，不能从浏览器变更模型连接。实际可用模型名由部署者核对后配置；修改已登记不可变 release 的内容时必须使用新 release UUID，不能沿用旧 ID 改摘要。

`bootstrap-support-bindings` 是被 `disabledReleases` 禁止新建 Run 的静态受信锚点：它声明允许的模型 profile、合成工具和公开合成输出边界，使后续目录发布可以引用它们。它本身不执行模型，也不会出现在业务的目录发布列表。FAQ ToolConnection 绑定 `support:faq`，由 `projects:["support-pilot"]` 限定项目；示例只含合成订单/售后资料。Ops 检索继续使用既有 `opsagent/rag-search` 与实时领域 ACL，`publicOutput` 保持 false。

同一数据库里的 API/worker 必须使用相同 deployment、签名密钥、工具契约、项目配额与全局并发配置。示例 lease 为 90 秒，须大于工具/模型调用超时及提交余量。不要把其他不相关 SDK 宿主的活跃 Run 混到这个调度域。

## 构建与启动

需要 Java 17、Node.js 24 LTS（至少 24.15.0）、pnpm 11.19.0、专用 MySQL，以及可用的现有身份桥。依次构建前端再打包平台，Maven 会把 `platform-console/dist` 放到 JAR 的 `static/console`。`dist` 不检入 Git。

```powershell
Push-Location platform-console
pnpm install --frozen-lockfile
pnpm test
pnpm build
Pop-Location
node --test support-pilot/src/test/js/approval.test.mjs
.\mvnw.cmd clean verify
java -jar harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar releases deploy/platform/phase3-deployment.example.json
```

最后一条仅离线解析示例并输出 releaseRef，不解析密钥、不连接数据库、不调用模型。准备好私有配置与进程环境后启动：

```powershell
java -jar harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar
```

打开 `http://127.0.0.1:8099/console/`，使用已有 OpsAgent 账号、密码和图形验证码登录。验证码经 `/console/auth/captcha` 从固定身份 origin 读取；登录经 `/console/login` 转发到已有 Auth 服务。高级入口可用已有用户 JWT 换取会话。JWT 与应用凭据留在服务端，浏览器使用 HttpOnly、SameSite=Strict cookie 和内存 CSRF token。服务端页面同源部署不需要前端环境变量。

前端本机开发可先设置 `HARNESS_DEV_PROXY_TARGET=http://127.0.0.1:8099` 再在 `platform-console` 运行 `pnpm dev`，按 Vite 输出打开 `http://127.0.0.1:5173/console/`。最终联调使用 JAR 提供的同源页面，不以放宽 Origin/CSRF 校验来迁就开发代理。

## 发布八类资源并调用

`phase3-catalog.examples.json` 是公开正文样例集合，不是一个新导入 API。文件的每个 `resources[]` 项包含 `type`、`id`、`body`。在 `support-pilot` 新项目依次发布 ModelProfile、Prompt、ToolConnection、ToolPolicy、RetrievalProfile、Workflow、RunPolicy、Agent；精确接口见 [catalog-api.md](catalog-api.md) 与 [catalog-openapi.json](api/catalog-openapi.json)。

每个资源的初次保存调用 `PUT /v1/projects/support-pilot/catalog/resources/{type}/{id}`，提交对应 `body`，带新逻辑操作唯一的 `Idempotency-Key` 和 `If-Match: "c0"`。随后在同一资源路径后追加 `/validate`、`/publish`，依次 POST，两者正文均 `{}`；每一步使用上一步响应的新 ETag 和新的 key。已有资源应先读取其当前 ETag，不得用脚本覆盖旧版本或清空数据库。发布者仍需明确审阅草稿与回归结果。

八类资源的边界如下：

| 类型 | 示例中的作用 |
| --- | --- |
| ModelProfile | 选择受信模型并收紧限额；本 FAQ Agent 未使用模型 |
| Prompt | 版本化客服提示；本 FAQ Agent 未引用，供后续明确配置的模型流程选择 |
| ToolConnection | 只选择已注册 `support:faq` |
| ToolPolicy | 精确工具引用、执行权限、审批分配和合成公开输出策略 |
| RetrievalProfile | 绑定只读检索连接，不定义新检索 URL |
| Workflow | `search → end`，把 `question` 绑定为检索 `query` |
| RunPolicy | token、模型调用、工具调用、步数及总期限上限 |
| Agent | 固定引用已发布版本，附输入/输出 schema 和冻结回归用例 |

示例 Agent 的回归在真实 WorkflowProgram 上重放固定检索结果，核对精确参数、工具集合及输出；不会访问模型或业务服务。它证明发布配置行为，不能代表中文检索准确率或模型质量。示例 ModelProfile/Prompt 的发布也不会自动产生模型调用。

仓库中的 `Phase3ExamplesTest` 原样加载这两个公开 JSON，在独立 H2 中使用真实 CatalogService/CatalogValidator 依序保存、校验和发布八类资源，再验证默认发布发现；工具执行器若被调用，测试会立即失败，不使用任何线上凭据或网络调用。该测试随 Maven `verify` 执行，防止文档样例与发布契约漂移。

Agent 发布完成后，使用返回的已发布版本号调用 `/resources/Agent/support-faq/default`，正文 `{"version":1}`（新项目的第一版）。SDK/业务应用从 `/catalog/releases` 或 `/catalog/agents/support-faq/default` 取得完整 `releaseRef`，再调用原有 POST `/runs`。默认切换与普通停用只影响新 Run；紧急 revoke 会使已有 Run 的后续能力检查失败关闭，不会重发 UNKNOWN，也不会篡改既有审批内容。

需要人工确认的客服流程可另外发布 `human → end` 或把 human 接在只读节点之后，输出变量指向人工输入，Agent 提供 humanInputSchema，并加入冻结 `humanInputs[nodeId]` 样例。确认行为仍使用同一个 Harness 核心及既有审批 API，不在客户端重建状态机。

## 独立客服应用

平台与身份源就绪后，在单独进程环境配置：

```text
SUPPORT_PLATFORM_ORIGIN=http://127.0.0.1:8099
SUPPORT_PROJECT=support-pilot
SUPPORT_PORT=8100
SUPPORT_APPLICATION_SECRET=<通过受保护环境注入>
SUPPORT_OPERATOR_USER_TOKEN=<现有客服用户JWT>
SUPPORT_OPERATOR_ACCESS_CODE=<至少24字符的随机访问码>
SUPPORT_REVIEWER_USER_TOKEN=<现有审核员JWT>
SUPPORT_REVIEWER_ACCESS_CODE=<另一随机访问码>
```

上面是配置说明，不是包含有效秘密的 env 文件。启动 `java -jar support-pilot/target/support-pilot-0.1.0-SNAPSHOT.jar`，打开 `http://127.0.0.1:8100/`。具体会话、审核员及命令重试说明见 [support-pilot/README.md](../support-pilot/README.md)。其他 Java 接入方可使用 [harness-platform-client](../harness-platform-client/README.md)。

按“订单什么时候发货？”、“可以申请退货吗？”验证 FAQ；人工流程把运行编号交给审核员直接读取 approval。再分别用 Ops 与客服应用创建 Run，验证列表/事件/输出按应用与用户隔离、跨主体访问返回不可见，以及没有人为授予 runs:admin 的只读用户不能绕过边界。应用secret不能进入浏览器、Run输入或事件；未知结果按既有可信证据对账流程处理。

## 验收与保留边界

本机隔离验收使用真实 MySQL 用户/grant/委托，以及已有 Auth 业务类和 JWT 校验；验证码 Redis 在隔离宿主中的替代实现不等于生产 Redis 已验收。生产部署须连接现有 Auth、验证码 Redis、登录限流、Nacos 和必要的领域服务，不得把验收替身复制进生产。现有知识/RAG 权限仍以业务系统为准。

FAQ 使用合成资料与词法检索；不是生产中文语义检索、真实订单系统或退款执行。回归门槛是冻结行为回放，实际 DeepSeek 调用/浏览器/双业务应用的证据由本轮集成报告分别记录。本阶段不实现第四阶段多模型运营、质量评测产品化、集中追踪集群、生产 SLO 或完整自助账号/授权管理。

升级、备份、恢复继续遵循 [platform-operations.md](platform-operations.md)。阶段三需把新增 catalog 表与 Run/事件/幂等/证据账本一并纳入同一备份时点；不能只保存当前默认 Agent 配置。
