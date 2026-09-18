# 平台本机运行与恢复

适用于本仓库 `harness-platform-service` 的内部试用版本。核心和平台 SQL 状态版本仍为 1；阶段三增加独立 `catalog=1` 组件及诊断 trace 表，不修改已有 SDK Run。阶段二部署示例见 [deployment.example.json](../deploy/platform/deployment.example.json)，完整控制台与独立试点配置见 [阶段三部署说明](phase3-deployment.md)。

第四阶段增加 `studio=1` 和 `capabilities=1` 组件。现有身份默认保持 Ops 适配；独立 loopback 验收身份及通用模型配置见 [身份配置](phase4-identity-and-models.md)。产品对象与边界见 [第四阶段实施记录](phase4-implementation.md)。

## 启动与身份

1. 使用 Java 17、Node.js 24 LTS（至少 24.15.0）与 pnpm 11.19.0，执行 `deploy/platform/build.ps1`，先构建前端再验证、打包 Java。可执行包为 `harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar`。直接运行 `mvnw.cmd clean verify` 只验证 Java；全新检出若未先构建前端，不会包含控制台资源。
2. 准备专用 MySQL 8.0 数据库，使用 InnoDB。首次启动会幂等创建 core/platform 表；DDL 是启动管理动作，不参与 Run 命令事务。平台显式 DDL 见 [V001__platform.sql](../deploy/platform/V001__platform.sql)，核心 DDL 以 `JdbcRunStore.initializeSchema` 为准。身份列使用区分大小写的 `utf8mb4_bin`；未知版本或旧的不兼容排序规则拒绝启动，不能自动改版本号。
3. 配置现有 OpsAgent Auth 身份桥、应用凭据摘要、项目/用户授权。平台不维护用户或密码库；控制台登录由服务端转发至固定的现有身份服务。凭据通过 `env:NAME` 或 `file:absolute-path` 解析，不能写在 manifest、Run 输入、Git 或命令行中。
4. 设置 `HARNESS_CONFIG`、`HARNESS_JDBC_URL`、`HARNESS_JDBC_USER`、`HARNESS_JDBC_PASSWORD`。示例还需要 `HARNESS_SIGNING_KEY`（至少 32 字符）与 `HARNESS_APP_CREDENTIAL`。保护环境文件/密钥文件的本机 ACL。
5. `java -jar <jar> releases <manifest>` 离线输出每个发布的 `releaseRef`；然后 `deploy/platform/start.ps1 -Config <manifest> -Port 8097`。默认只监听 `127.0.0.1`；本阶段验收不开放公网。

每个 API 请求同时发送 `Authorization: Bearer <登记的应用凭据>` 与 `X-Harness-User-Token: <现有 OpsAgent 用户 JWT>`。身份桥校验 JWT、SQL 用户当前状态和应用/项目/用户 grant；JSON 中的主体、权限、URL 或模型配置不能决定执行身份。异步运行仅保存非密钥 delegationId，后续每次 dispatch 重新确认授权并短期签发 RAG audience token。原始用户 JWT 不持久化。委托最长 24 小时，Run 期限按秒向下收紧以不超过委托。

示例使用 `ops-dev`、`opsagent-pilot`；用户号与权限须来自实际配置。创建权限需包含 `runs:create` 和发布所需能力。例如 Ops 检索同时需要 `tool:opsagent/rag-search` 和 `opsagent:rag:search`。工具审批另需发布中的明确 `application/subject` 分配以及 `approvals:read/decide/review`。运行权限和审批权限不默认互相授予。

## 发布、输出与幂等

manifest 是受信部署文件，不是公开 API 输入。每份 release 包含不可变 UUID、图、模型、工具集合、输入/输出 schema、上限和审批分配；摘要排序稳定，不因进程重启改变。同项目同 releaseId 内容改变会拒绝启动。新增发布使用新 UUID；`disabledReleases` 的 `project/releaseUUID` 只阻止新建，已有 Run 保存旧快照。紧急撤销由身份/能力授权控制。

`publicOutput=true` 只用于发布者确认不含受保护资料的结果，例如本仓库合成开发文档；客户端不能设置它。输出还须满足 schema、大小和 `runs:output:read`。受保护 Ops 检索仅对原应用/原用户、未加工的纯检索工作流启用逐引用当前 ACL 核验；任一引用失权、核验失败或运行委托过期时，整段结果 `OMITTED`。跨主体管理员和模型加工的受保护答案也保持省略；不能借删除引用继续返回正文。这是首版受控输出边界，长期历史结果授权与通用答案血缘属于后续扩展。

所有 POST 用新请求唯一的 `Idempotency-Key`，丢响应或 503 后只用原键、原 body 重试。记录按 project/application/subject/method/route 隔离；首次接受结果与状态/归属/事件/额度共用真实 JDBC 事务。相同键不同 body 为 409。审批、控制、对账另要求读到的强 ETag，过期并发条件为 412；不能自动盲重试人工决定。

首版**保留全部 Run、事件、完整幂等响应和已用 key**，没有自动清理。游标有效一小时并绑定主体、权限、项目和过滤条件；事件按持久序号扫描。不要自行删除幂等记录后允许旧键复用。容量与保留期治理在后续阶段补齐；当前数据使用独立受控数据库。

## API 与 worker

11 个操作由同一应用层校验；API 不执行模型循环。manifest 的 `workerEnabled=false` 用于只接受/查询命令的进程，`true` 启用 SQL 调度器。多个进程必须使用相同发布、签名密钥、能力注册、项目配额和全局并发配置；一个数据库是一个调度域，不混入其他 SDK 宿主的活跃 Run。

项目及应用的活跃 Run 数和预留 token 在创建事务内检查；共享 SQL 槽限制全局/项目派发并发。等待、暂停、UNKNOWN 仍占预留；终态不再占用。没有自动提高上限。lease 必须大于工具/模型本地超时加提交余量。远端忽略中断时可能继续运行，平台不能把本地超时或槽回收等同于远端停止。

`GET /health` 实际执行数据库探测。每个 API 响应有 `X-Request-Id` 和 no-store；操作日志含 run/invocation/attempt/trace ID，不记录参数、prompt、凭据或正文。控制台展示共享 SQL 中的诊断 trace；崩溃可能缺失结束 span，持久 Run 事件仍是审计依据。当前没有部署外部集中 OTel 收集器。正常停机应给进程至少 `leaseMillis + 5 秒`，停止新领取、等待正在执行的调用；强制结束进程后保留 SQL 状态，通过租约/fence 恢复，未知写不能自动重发。

## UNKNOWN 与独立证据

先查询 `/unknown-invocation`，独立核验远端持久记录，确认精确目标、参数、版本和唯一 invocation 的结果。没有证据时保持 UNKNOWN；取消、到期或重启不能抹去未知效果。模型 UNKNOWN 不走工具证据入口。

受信 verifier 核验后生成 `PlatformRepository.Evidence` JSON：`id, project, runId, invocation, digest, result:{output,error,receipt}, verifier, verifiedAt`。`invocation` 是存储中的本地 ID，公开 API 的 `invocationRef` 是另一个 opaque 值；`digest` 必须与当前 UNKNOWN 视图一致。离线管理员执行 `java -jar <jar> import-evidence <private-proof.json>`，使用专用数据库管理凭据。CLI 检查精确绑定并只插入不可改写的证据，不执行工具、不替用户批准、不将 Run 标成成功。**CLI 不替代远端核验**，应仅授权给受信 verifier；普通 API 调用方不能上传任意 success 回执。

随后由有权限的调用方提交公开 `evidenceRef + invocationRef + invocationDigest`，携带 UNKNOWN ETag 与幂等键。平台核对存储证据后调用核心 CAS 对账；已有取消进入 CANCELLED，否则 PAUSED，需明确恢复才继续。

## 备份与隔离恢复

1. 停止接受新命令，关闭 worker 领取并等待可确认调用结束。记录 Run/事件/命令/证据数、发布摘要、JAR SHA-256、签名密钥引用和身份桥数据库备份时点。仍在外部执行或 UNKNOWN 的动作必须保留清单。
2. 使用受保护的 MySQL option 文件与 [backup.ps1](../deploy/platform/backup.ps1) 创建单事务逻辑备份。文件包含输入/结果，应按业务数据保护；不要提交到 Git。保存校验和。
3. 恢复到**新建的隔离数据库**，保持 `workerEnabled=false`、无外部模型/写工具连通性。使用 MySQL 客户端的 `source` 或其 stdin 导入，不能覆盖原库。检查 SQL 版本、表记录、run revision/预算/deadline/审批摘要、幂等响应和事件连续性。
4. 比较备份之后的真实外部效果。旧快照中的 PREPARED 也可能在备份之后已经写出，不能因为恢复库里没有结果就重新执行。人工核验不确定动作，完成执行归属交接后才恢复可确认的运行。
5. 恢复应用签名密钥与身份委托库；丢失密钥使旧 ETag/游标失效，丢失委托或撤权会阻止后续执行，不能生成新权限绕过。恢复演练只证明该次隔离样本，不代表生产 RPO/RTO 已达标。

使用 PowerShell 7 的 [restore-proof.ps1](../deploy/platform/restore-proof.ps1) 可在停止写入后复现“新建隔离库、恢复、逐表哈希比较”；脚本不会覆盖原库或启动恢复 worker。

阶段三备份还必须包括四张 `catalog_*` 表、共享 `platform_releases` 和 `harness_platform_trace`。恢复脚本自动检测并比较目录与 trace，共 15 个表组。不要只备份当前默认版本；既有 Run、历史审批与目录依赖均绑定不可变版本。控制台会话仅保存在进程内，重启后重新登录，不从数据库恢复浏览器会话。

第四阶段恢复脚本已改为自动枚举**全部基础表**，包括六张新增 Studio/能力/来源表；不再依赖上面的历史固定表组数。它比较完整行哈希、行数、表结构和表集合，遇到源库在证明期间变化即失败。独立 `local-test` 的私有身份配置、密钥和委托状态目录也需要单独保留；SQL 恢复证明不等于身份目录或生产恢复演练已完成。

Studio 和共享能力的命令有独立的持久收据接口。浏览器对不确定结果只查询精确原命令并核对当前资源版本，查询不到时继续阻断；不使用新键盲重发。调用返回旧快照并不赋予当前读取权限，文档、答案和导出仍逐次校验。未知命令元数据可在同一浏览器会话刷新后恢复，不保存请求体或凭据。

本机进程验收脚本见 `validation/platform/`；真实 GitHub 写与 DeepSeek 质量基线另见 `docs/validation/20260918/`，不要用合成进程 fixture 冒充外部服务质量。
