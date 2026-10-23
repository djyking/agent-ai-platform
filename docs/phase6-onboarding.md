# 第六阶段：受控项目与应用接入

本模块复用 OpsAgent 现有用户、角色与身份桥授权表，不创建第二套账号。申请、审批、配置和生效验证是不同步骤；审批通过不表示应用已经获得执行权限。

## 可执行流程

1. 用户从控制台「应用接入」申请当前项目的新应用，填写名称、业务用途与维护责任，并选择固定权限范围。平台级管理员可从已有项目申请新项目。申请中的维护用户固定为当前身份用户，不接受调用者自报用户、权限、URL、密钥或 secretRef。
2. 具备 `onboarding:admin` 的项目管理员审批或拒绝。新项目还要求 `onboarding:platform-admin`。审批生成可核对的无秘密计划及摘要，并建立默认禁止调用的 SQL 控制记录。
3. 部署管理员排空并隔离 API 与 worker，使用单独的数据库管理凭据执行 `onboarding-apply`。工具检查活动 Run、worker 心跳、执行槽和未决调用；存在 UNKNOWN 调用时拒绝继续，不擅自解决或重放。
4. 工具在受限私密目录中生成应用凭据和 deployment 候选文件，并实际写入 OpsAgent 身份库应用/成员授权。候选文件引用凭据文件路径；原 deployment 不会被覆盖。此时状态是 `PROVISIONED`，平台 SQL 仍禁止该应用调用。
5. 管理员核对候选文件，按第五阶段迁移流程显式迁移配置 fingerprint、切换配置并重启全部 API/worker，检查 ready。申请用户使用原有登录态点击「验证配置与身份并启用」。
6. 验证要求当前实际 deployment 的身份/能力配置摘要与已生成候选相同、服务器持有的应用凭据指纹一致，并使用当前用户原始令牌向真实身份源 introspect。摘要只排除实例开关 `workerEnabled` 和进程 `concurrency`，允许 API 与 worker 分开部署，仍绑定所有身份、项目额度、工具、模型、发布及 lease 配置。应用、项目、用户和权限集合必须完全一致，才能设为 `ACTIVE`。

新项目默认额度为总活动 Run 2、预留 token 100000、单应用活动 Run 2、单应用预留 token 100000、worker 并发 1。需要不同额度时应先修改受信配置方案并重新审阅，不允许申请者从浏览器突破额度。新项目复用服务器已有模型/工具连接，不允许申请者引入 URL 或远端凭据。新项目同时加入申请时的控制台应用，并只为申请用户增加同一个已审批权限范围的成员授权，保证新项目可使用已有控制台；不会赋予管理员权限。

权限范围：

| 范围 | 用途 |
| --- | --- |
| `assistant` | 本人任务的创建、读取、控制、模型调用及 Studio 知识 |
| `knowledge-reader` | 上述能力加现有 OpsAgent 授权检索 |
| `builder` | 上述检索能力加资源目录读取、编辑、验证和发布 |

固定范围均不包含管理员、他人任务访问、审批决定、预算修改或任意远端写权限。特定业务需要写工具时，应走既有资源/工具策略审阅，不能通过申请字段直接申请万能权限。

## 离线配置命令

应用执行方式：

```text
java -jar harness-platform.jar onboarding-apply /private/onboarding-command.json
```

命令文件没有凭据：

```json
{
  "sponsorProject": "support",
  "requestId": "已审批申请编号",
  "revision": 2,
  "planDigest": "控制台已审批计划摘要",
  "requiredRole": "USER",
  "expectedApplicationRevision": -1,
  "operator": "deployment-operator"
}
```

`requiredRole` 必须是申请用户在现有身份系统中的实际 `USER`、`ADMIN` 或 `DEMO` 角色。`expectedApplicationRevision=-1` 表示身份库尚无该应用；轮换或停用填实际已核验的 application revision。初次创建 revision 为 0，随后每次轮换/停用加 1。命令的 `revision` 是中台申请修订，二者不可混用。

必需环境变量：

- `HARNESS_CONFIG`：当前已部署的受信 deployment 文件。
- `HARNESS_ONBOARDING_PRIVATE_DIR`：已存在的管理员私密输出根目录；生成子目录及文件设置仅所有者访问权限（Windows ACL / Linux 0700、0600）。
- `HARNESS_ONBOARDING_OFFLINE_ACK=drained-isolated-cluster`：已排空并持续隔离整个集群；数据库检查不能证明网络入口已经被隔离，管理员仍须保持隔离直至迁移和重启完成。
- `HARNESS_MIGRATION_JDBC_URL/USER/PASSWORD`：中台管理数据库连接。
- `HARNESS_ONBOARDING_IDENTITY_JDBC_URL/USER/PASSWORD`：OpsAgent 身份库的管理连接。

前置条件：已执行中台 onboarding schema 迁移；身份库已执行包含 `ops_harness_application.revision`、`ops_harness_grant.revision` 的身份桥显式迁移。工具不隐式建表、不种子用户、不自动赋予已有系统角色。

输出位于 `<requestId>-g<generation>/`：

- `operation.json`：命令摘要和源配置摘要，不含凭据。
- `application.credential`：随机 256 位应用凭据，只交付给维护方服务端和执行节点；浏览器不接收应用凭据。
- `deployment.json`：精确候选配置，原文件保留。候选包含当前机器绝对 secret 文件引用；迁移到 VPS 时必须采用目标机器的受信路径重新执行/核验部署，不应原样使用 Windows 路径。

输出目录与原配置都应留在忽略目录或专用部署私密目录，禁止 Git 提交。应用 credential 和部署节点 secret 文件必须通过受控私密渠道交付，不通过页面、日志、操作记录、普通通知或 GitHub 分发。若使用独立 API/worker 主机，管理员需将同一凭据放到各节点相同受信路径，并保持整个配置摘要一致。

两个数据库不支持一个分布式事务：身份写入先提交，中台记录随后提交。期间平台 gate 保持禁止。相同命令重试使用相同私密文件，并核对 identity revision、凭据哈希和成员权限；与原方案不一致的现有授权会拒绝覆盖。配置候选和私密输出使用写入临时文件后移动，不会把半写入文件当成完整凭据。已有不同输出、软链接和重定向目录均拒绝。

## 凭据轮换和停用

- **轮换**：管理员先给出理由；SQL gate 立即暂停调用，状态变为 `ROTATION_APPROVED`。排空后使用当前已部署配置和新计划执行 CLI，生成新的 credential 并以身份库 revision CAS 替换凭据哈希。旧凭据立即不能通过身份源认证。使用新配置迁移、重启，再由原申请用户验证后恢复调用。
- **停用**：管理员给出理由后，SQL gate 立即拒绝该应用的新认证和后续执行授权。历史 Run 不删除、不改所有者、不换执行器。之后在排空状态运行 CLI 将 OpsAgent application 的 `enabled` 改为 0；页面区分「中台已停用，身份源待处理」与「中台和身份源均已停用」。
- 已停用的申请不能直接重新激活或轮换。重新开放须经过新的审阅和显式管理流程，不能复用一个旧激活请求绕过停用。
- 如果停用发生在任务执行中，后续授权检查会拒绝，已发出的外部调用仍按原执行/UNKNOWN 对账策略处理；gate 不是远端撤销或已经发生副作用的回滚。

上述启用、轮换和停用的对象仅是申请中的业务应用，不是整个目标项目。新项目中为申请用户加入的既有控制台应用授权独立存在；配置迁移后该用户可以按已审批 profile 使用控制台，业务应用尚未验证或已停用不会连带撤销此成员授权。若要停用整个项目或撤销成员，必须另行审核并撤销对应控制台授权及项目入口，不能把业务应用停用当成项目停用。

## API 与并发语义

路径均在 `/v1/projects/{sponsorProject}/onboarding`，控制台通过既有会话 BFF 使用：

| 方法 | 子路径 | 行为 |
| --- | --- | --- |
| GET | 空路径，`after`、`limit` | 有界游标分页；普通用户仅见本人申请，管理员见项目申请 |
| GET | `/{id}` | 当前申请、计划、修订和审计 |
| POST | `/{id}` | 创建，`If-Match: "k0"` |
| POST | `/{id}/decision` | APPROVE / REJECT，必须有理由 |
| POST | `/{id}/lifecycle` | ROTATE / DISABLE，必须有理由 |
| POST | `/{id}/verify` | 以原用户令牌核对真实身份和服务器配置，空对象请求体 |
| GET | `/commands/{key}` | 查询当前主体原操作回执 |

所有写请求要求 `Idempotency-Key` 和修订 `If-Match: "kN"`。同键不同内容冲突；并发修改只有一个成功；申请版本、操作回执、所有者索引和 gate 更新共用中台 SQL 事务。控制台在发请求前将无秘密的操作标识存入会话存储；响应丢失后阻止新提交并先查原回执，不重放写操作。普通用户在 SQL 中先按项目和申请者过滤，再执行有界游标分页，每页最多 100 条及一条下一页探测记录，不泄漏他人申请编号。onboarding schema 2 在离线迁移中回填并校验已有申请的所有者索引；运行账号不执行 DDL。

## 验证边界

`OnboardingServiceTest` 的 8 个测试使用隔离 H2 数据库，覆盖双 JDBC 离线配置→数据库哈希/成员写入→运行配置与身份复核→轮换旧凭据失效→双侧停用链路，以及幂等、并发 CAS、租户隔离、禁止权限/secretRef 输入、活动 Run 拒绝、私密文件冲突、所有者分页和 schema 1→2 迁移。前端测试覆盖审批与生效区别、停用理由/修订、未知写恢复和权限控制。

2026-10-20 另行完成了真实本机双库联调：独立 MySQL `harness_onboarding_phase6`、真实 OpsAgent `ops_auth_phase6` 身份源、独立官方登录用户和 API 8128，共 33 项检查通过。实际执行请求、审批、离线配置、两次配置迁移与重启、实际身份验证启用、轮换、停用；核对新项目控制台授权不含管理员权限、旧凭据失效和最终停用后身份源 introspect 返回 401。测试未创建或重分配 Run、未调用外部模型，完成后已停止独立 8128 服务。私密报告位于本机忽略目录 `.work/phase6/local/onboarding-e2e/report.json`，不提交凭据或候选配置。首轮遇到本机 MySQL 连接数上限，保留失败证据并在扩容后用原幂等操作完成，未用新的申请掩盖失败。

这些工程验证不等同于 VPS 部署、真实生产用户开通或异机凭据交付完成；目标环境需用相同流程执行并记录证据。
