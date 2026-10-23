# 第五阶段执行治理

本模块提供持久化执行限额和故障隔离，不替代提供商账单或业务审批。默认值为整个数据库最多 5 个模型请求、单 provider 5 并发 / 120 次每 UTC 分钟、单 tool 10 并发 / 300 次每 UTC 分钟；部署可使用独立可信 JSON 配置覆盖。

## 一致性和升级

`ExecutionGovernanceConfig` 是管理员部署配置，公开 API 和 Studio 不能修改它。`ExecutionGovernance` 将治理配置、部署执行配置以及主机提供的身份/实际密钥摘要组成共享指纹。每次调度、心跳和调用准入核对数据库指纹；不匹配的实例拒绝执行。摘要不包含可还原的密钥，API不输出部署密钥或正文。

一个数据库只允许一个治理集群。worker 每秒记录数据库时钟心跳、活跃任务数和停机状态；判断失联使用数据库时间。升级变更必须停止实例、排空全部非终态 Run，并解决所有保留的调用占位。`migrateConfiguration(expectedFingerprint)` 使用旧指纹 CAS，只在无活跃 Run、无 RESERVED / DISPATCHED / UNKNOWN 占位、无近期运行 worker 时换代。本版不宣称支持不兼容版本同时接管活跃 Run；不存在强制跳过的开关。普通生产启动提供 validate-only 路径，不执行 DDL。

## 调度与调用准入

调度逐项目交错读取就绪任务，跨实例轮换起始项目，复用数据库全局 / 项目执行槽位。等待截止时间使用 `harness_run_index.expirable_at` 索引；日常扫描不反序列化历史快照。活跃 Run 配额通过索引中的非终态集合计算。`harness_run_index` 的状态、用量、更新时间和首次终态时间与核心状态同事务维护，迁移时只回填缺失行。

运营总览使用同一窄索引的 `platform_project_id`、`platform_application_id`、`platform_subject_id`、`platform_created_at`、`platform_reserved_tokens` 五个可空扩展列；这些列由平台迁移和创建事务维护，独立 SDK 不依赖平台模块，既有核心状态/用量更新保留这些维度。创建失败会同时回滚核心 Run、平台归属与扩展列。项目与个人总览分别使用覆盖索引聚合，避免每次把十万条历史逐行关联平台大记录。告警刷新只查询所属项目的 `QUEUED` / `NEEDS_ATTENTION`，在同一短项目锁内完成读取和告警转换，不聚合终态历史。

此次扩展将 `platform` schema 从 1 升为 2，另记录 `platform-run-projection=1`；核心 `run-index=1` 不变。显式离线迁移回填旧平台归属，校验五个值、孤立投影、索引顺序及 MySQL 身份字段二进制排序规则，再发布新平台版本，旧平台程序会拒绝启动。生产启动只读核验、不修复。迁移仍要求排空非终态 Run 和解决保留占位；UNKNOWN 不会因本次索引升级被清理或自动重放。

provider / tool 请求发出前取得持久化调用占位，检查数据库 fence、租约、并发、UTC 分钟速率和熔断窗口。无容量时保持 PREPARED 并排队，attempt/modelCalls/toolCalls 不增加，也不触发远程调用。已准入但未发出的占位可在租约失效后释放；已发出调用不会因客户端等待超时或进程心跳失效而自动释放。

连续已知失败达到阈值时，仅打开对应 provider / tool 的熔断窗口；窗口后允许再次尝试。没有隐式模型切换。调用已经终止并返回已知结果时释放占位；结果不明则置 UNKNOWN，保留占位和 Run 的预算预留。平台使用的 gate 禁止 UNKNOWN 自动重试，包括只读工具；独立 SDK 未配置 gate 的既有策略保持兼容。

迟到结果必须通过核心现有的 revision / fence / lease 检查才能写回状态。真实远程调用在等待方超时后最终结束，可以释放该调用的并发占位，但不能覆盖已经失效的 Run 状态。进程丢失后无法观察到调用终止的 UNKNOWN 占位，需要管理员独立确认外部调用已经结束，使用 `resolveUnknownPermit(permitId, evidence)` 记录证据后释放；它不会更改或重放 Run 的未知结果。

## 日月预算语义

`budgets` 按项目配置 dailyTokens / monthlyTokens / applicationDailyTokens / applicationMonthlyTokens；0 或未配置表示未启用该限额。默认配置没有擅自决定正式运营预算。预留发生在接受 Run 的同一数据库事务，冻结其接受时间所属的 UTC 日和月。每个 Run 预留本次 maxTokens，项目和调用方 application 各计一份。application 是 API 身份应用，不是 Studio 中某个 Agent 应用 ID。

已知终态按核心 chargedTokens 结算并释放剩余预留；未知结果、人工等待和其他非终态继续保留完整预算。日月限额表示**按接受日期归属的 Run 预算**，不是按每次提供商调用发生日期归属的财务账单：跨午夜执行的 Run 继续属于接受日。入账使用真实已知 tokens；提供商未返回 usage 时延续核心保守预留计费。需在对账页面和正式成本治理中保持此命名。

## 验证范围

定向自动化覆盖多实例共享上限、跨实例配置冲突、熔断/速率持久化、过期 fence 阻断外部调用、调用超时后占位不释放、迟到返回、独立证据释放 UNKNOWN、预算预留/结算、无容量不消耗调用次数，以及跨项目交错调度。真实 Linux 多机观察和生产容量承诺需要后续预发布环境证据；本机单元测试不能替代这些证据。

## UNKNOWN 调用占位的运维核验

管理员先通过提供商、远程工具自身的权威记录，独立确认该调用已经终止，再创建不提交到 Git 的证据引用文件。例如：

```json
{
  "permitId": "<占位 UUID>",
  "evidenceRef": "incident-20260920-001",
  "verifier": "operator",
  "verifiedAt": 1790000000000,
  "externalCallStopped": true
}
```

`verifiedAt` 是真实核验时刻的 Unix 毫秒数，上例必须替换；不能使用未来时间。证据文件只填写引用及核验元数据，不放凭据或业务正文。使用与平台相同的部署、身份和治理配置环境变量执行：

```text
java -jar harness-platform-phase5-0.1.0-SNAPSHOT.jar resolve-permit .work/private-evidence.json
```

命令核对共享配置指纹并记录证据，然后释放该调用的并发占位。它**不会自动判断外部调用状态**，也不会自动解除 Run 的 `NEEDS_ATTENTION`、修改结果、减少未知 Run 的预算预留或重新执行调用。Run 的未知结果仍需按原有核验流程处理。

如果命令提交成功但终端回执丢失，可使用相同 `permitId` 和完全相同的证据重查；这是幂等确认，不会重写首次核验时间或 Run。已释放但证据不同、占位仍在其他状态或占位不存在，均返回冲突，不能将其视为新的确认成功。
