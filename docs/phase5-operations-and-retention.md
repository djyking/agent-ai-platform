# 第五阶段：治理 API 与数据保留

## 权限与运行观察

`GET /v1/projects/{project}/operations` 要求现有身份的 `catalog:read`。普通主体只看自己的 application + subject 聚合，即使持有 `runs:admin` 也不会隐式获得运营管理权限。`operations:admin` 才能看项目整体、worker、共享调用额度、告警、保留计划和本地告警收件箱。平台不会自行给现有身份增授此权限。

返回 `scope`、数据库时间 `generatedAt`、`statusCounts`、`queue`、`usage`、`workers`、`execution`、`alerts`、`retention` 和 `capabilities.admin`。用量为持久保守账本，UNKNOWN 预留不等于供应商已确认计费。备份状态固定标明 `EXTERNAL_EVIDENCE_REQUIRED`，数据库在线不构成备份成功证明。

独立 `GET /internal/metrics` 要求 `HARNESS_METRICS_TOKEN` 的 Bearer 凭据（32–512 个非空白字符）。未配置时 404；用户/应用凭据及伪造 `X-Forwarded-For` 均不能获得指标。该路径不经浏览器 BFF，反向代理应阻止公网路由，只供专用监控网络访问。

指标没有 Run ID、问题正文、模型回答或密钥标签：

- `harness_database_ready`
- `harness_runs{project,status}`
- `harness_tokens_charged{project}`、`harness_tokens_reserved{project}`
- `harness_model_calls_total{project}`、`harness_tool_calls_total{project}`
- `harness_queue_oldest_age_seconds{project}`
- `harness_workers_available{project}`、`harness_unknown_provider_permits{project}`
- `harness_alerts_firing{project}`
- `harness_http_requests_total{route,method,status_class}` 和 `harness_http_request_duration_seconds_{bucket,sum,count}`：进程内同步 API 时延直方图与计数；进程重启重置。route 仅八种固定组、method 仅 read/write/other、status_class 仅 2xx/3xx/4xx/5xx/other，不含原路径、Run ID、请求头或正文。静态资源、health 和指标采集本身不计入。BFF 内部转发只计一次。
- `harness_dependency_calls_window{project,operation}`、`harness_dependency_duration_seconds_window_{sum,mean,max}`：最近 900 秒开始的已记录 MODEL/TOOL trace 数量及秒数统计，是窗口 gauge，不能使用 `rate()`；崩溃时未完成的 span 不推测为零耗时。

指标聚合使用执行索引，不反序列化历史 Run 正文。模型/工具耗时来自脱敏 `harness_platform_trace`，按 900 秒时间范围聚合，并提供 `ix_harness_trace_window(started_at,operation_name,run_id)` 支持范围扫描；一次查询聚合全部配置项目。该索引由显式 schema migration/bootstrap 建立，正常 validate-only 启动缺少索引会拒绝，不能使用 DML 账号默默补 DDL。HTTP 指标每个实例独立抓取后聚合，数据库窗口指标是共享值，多个副本抓取不能相加；备份新鲜度由独立备份采集提供。

## 告警与接收

服务定期刷新固定规则：`UNRESOLVED_EXECUTION`、超过 30 秒的 `QUEUE_DELAY`、45 秒内没有兼容 worker 心跳的 `WORKER_UNAVAILABLE`、`UNRESOLVED_PROVIDER_PERMIT`。数据库故障需由外部探测报警，不能声称故障中的数据库还能接收自己的告警。

每条规则仅在 FIRING / RECOVERED 状态变化时写入 `operations_inbox`；同态轮询不重复发出事件。再次触发会清除旧确认状态并增加发生次数。收件箱与确认记录持久化，服务重启不丢失。实际通知默认仅本地 SQL inbox，部署包测试接收端独立验收；没有向真实群或个人发送通知。

- `GET .../operations/inbox?after=0&limit=100`：按创建时间读取本地事件；同一时间戳可以有多条事件，消费方必须按 `id` 去重，不能把时间戳当成唯一游标。
- `POST .../operations/alerts/{id}/ack`：JSON `{}`，必须携带 `If-Match: "oN"` 和 `Idempotency-Key`。
- `GET .../operations/commands/{key}`：只读同一项目、应用及主体的精确回执。未知写结果先查回执；不能凭超时判断没有执行。

确认告警不处理底层 UNKNOWN，不释放供应商调用预留，不重发模型或工具调用。

## 保留策略与边界

默认：在线任务输入/输出及动态导出 30 天、终态诊断 trace 14 天、审计元数据 180 天、加密恢复归档额外 30 天；备份 30 天由独立备份脚本执行。期限从已持久化的终态时间计算，不从提交时间推断。现有应用配置、不可变发布、知识版本和作为应用配置的评测样本不属于临时任务正文，保留规则不会破坏它们。

只处理已终态、没有 pending invocation、没有 approval 对象、没有有效租约、没有 RESERVED / DISPATCHED / UNKNOWN 供应商调用许可的 Run。UNKNOWN、等待审批/输入及其他非终态不自动清除。若一个评测行还关联未决另一侧，整行相关 Run 的清理被固定保留。无法证明安全的记录计入当前扫描批次的 `pinnedCount`。

保留操作完全显式，不自动清历史：

1. `POST .../operations/retention/preview`，JSON `{"limit":100}`（1–100），只生成 15 分钟有效的主体专属计划。返回 `hasMore/nextCursor` 时，下一批可传 `{"limit":100,"after":"上一批 nextCursor"}`，不重复滞留在旧的固定保留记录前。每批最多扫描 `limit × 10` 条候选上限 1,000 条，`scannedCount/pinnedCount` 是当前批次计数。
2. 审阅 `candidates`、`traceCount`、`pinnedCount`、`expiredArchives`、`expiredAudit`、`expiredInbox`、`archiveAvailable`；候选动作 `ARCHIVE_PAYLOAD` / `TRACE_ONLY` / `AUDIT_ONLY`。
3. `POST .../operations/retention/apply`，JSON `{"planId":"…","digest":"…"}`，必须携带 `Idempotency-Key`。
4. 应用时重新获得项目锁与 Run 行锁，核对 revision、snapshot digest、terminal time、未决引用和租约。任何候选发生变化均回滚整个计划。HTTP 412 表示计划已过期于当前状态，必须重新预览；未知写响应通过命令回执恢复。

`GET .../operations/retention/plans/{id}` 仅原操作主体可读。计划摘要中没有正文；摘要绑定完整服务端计划及主体，而不是只对页面显示字段计算。

`HARNESS_RETENTION_KEY` 是独立的 Base64 编码 32 字节 AES-256 密钥，不能复用应用签名密钥。正文使用 AES-GCM、随机 nonce 和 archive ID 作为 AAD 加密，立即解密校验后才在同一 SQL 事务中清在线副本。没有密钥可以预览；需要正文归档的 apply 返回 `RETENTION_ARCHIVE_KEY_REQUIRED`，不会部分清理。

归档包括原始 Run 快照、事件、命令回执、命令审计、外部证据及相关 Studio 副本。在线清理覆盖：

- `RunState.definition.spec / tools / memory / results / receipts / output`；保留身份、版本、时间、状态、budget、计数、revision、fence、creation key/digest。
- Run 事件的自由 attributes、命令审计 reason、client reference、证据 result。
- 平台命令 response 改为保留原 Run 引用的归档回执；命令 key/digest 永久保留。
- Studio TASK input、受影响评测行 input/expected/output 副本，以及超过期限的历史 Studio command response。当前应用/发布配置不修改。

归档 Run 带 `payloadArchived:true`；所有正常执行和控制入口拒绝变更，旧创建/命令 key 仍返回原引用或幂等冲突，不得重新产生调用。任务 API 不返回 input/产物，Run output 为 OMITTED。归档未增加在线正文下载端点，不会通过运营管理员权限绕过原业务输出权限。

180 天后可删除对应 Run 事件和控制审计；永久防重索引、命令 tombstone、最小所有权及已发生用量继续保留。加密 archive 到期后只能通过下一份审阅计划删除。在线清理不等于所有介质在第 30 天立即销毁：恢复归档和加密备份都有各自额外保留窗口，灾备恢复必须遵守当前数据策略。

每个项目本轮最多扫描 10,000 个 TASK/EXPERIMENT 文档、单项归档最多 10,000 行和约 16 MiB JSON；超过限制返回需要分区的固定错误并保持原数据。大量 Studio 任务需要先做归档分区，不能以截断记录冒充完整归档。计划是有界批次，操作员应复查 `hasMore` 和 `pinnedCount`。

密钥丢失将使归档不可恢复。密钥轮换不能销毁尚未过期归档使用的旧 key；恢复演练需协调备份 SQL、委托文件、签名材料和归档 key。新字段只在归档后写入 true，旧第四阶段 reader 将拒绝这种快照；不支持旧二进制混跑或直接降级处理已归档状态。

## 本机 TLS 代理验收程序

`validation/platform/phase5_proxy_acceptance.py --config <private-config.json> --report <report.json>` 连接已启动的真实 JVM API，临时启动独立 Python TLS 代理。私有配置指定 upstream、publicOrigin、project、凭据文件、证书和密钥路径；只接受显式 loopback 地址。

程序使用受信自签 SAN 证书进行实际 TLS 与主机名验证，检查代理固定覆盖 Host / X-Forwarded-*、Origin、Secure/HttpOnly/SameSite cookie、CSRF、BFF 项目权限、从非受信 `127.0.0.2` 直连伪造 forwarded 头被拒绝、注销后会话不可复用。无模型/工具调用，结果只包含断言名称、状态、非敏感计数；不保存 cookie、CSRF token 或登录口令。报告明确该测试没有执行 nginx、Linux 网络或公网证书签发，它不能替代预发布环境验收。
