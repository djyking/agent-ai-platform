# 阶段三资源目录与发布 API

机器合同见 [catalog-openapi.json](api/catalog-openapi.json)。原有 11 项 Run API 不变；本页新增入口为 `/v1/projects/{project}/catalog`，继续使用应用 Bearer 凭据和 `X-Harness-User-Token`。浏览器通过本地控制台 BFF 使用 HttpOnly 会话，不能获取应用凭据。

## 权限与入口

| 操作 | 路径（相对目录入口） | 权限 |
|---|---|---|
| 受信候选、模板 | GET `/capabilities` | `catalog:read` |
| 列表、详情 | GET `/resources?type=Agent`、`/resources/{type}/{id}` | `catalog:read` |
| 创建/保存草稿 | PUT `/resources/{type}/{id}` | `catalog:write` |
| 校验与回归 | POST `/resources/{type}/{id}/validate` | `catalog:validate` |
| 发布 | POST `/resources/{type}/{id}/publish` | `catalog:publish` |
| 停用/恢复资源 | POST `/resources/{type}/{id}/disable` | `catalog:publish` |
| 停用/恢复指定版本 | POST `/resources/{type}/{id}/versions/{version}/disable` | `catalog:publish` |
| 紧急撤权/解除 | POST `/resources/{type}/{id}/revoke` | `catalog:publish` |
| Agent 默认版本 | POST `/resources/Agent/{id}/default` | `catalog:publish` |
| 不可变版本与比较 | GET `/resources/{type}/{id}/versions/{version}`、`/versions/{version}/diff?against=1` | `catalog:read` |
| 可用 Agent 发布列表 | GET `/releases`、`/agents/{id}/default` | `runs:create` 或 `catalog:read` |

类型仅接受 `Agent`、`ModelProfile`、`Prompt`、`ToolConnection`、`ToolPolicy`、`RetrievalProfile`、`Workflow`、`RunPolicy`。所有查询和变更绑定可信身份中的项目，资源引用不能指定另一个项目。应用必须在该项目的可信部署配置中注册；增加目录权限仍由现有身份系统完成。

本阶段不新增项目创建、账号注册或 grant 写入 API。项目/应用边界由可信部署清单维护，用户、管理/发布/审批角色和业务授权由现有 OpsAgent 身份管理源维护；控制台展示当前有效项目和权限。该复用是 MVP 的明确边界，不声称已经实现独立组织账号或全套自助授权后台。

每个变更须提供 `Idempotency-Key` 和 `If-Match: "c{revision}"`；初次创建使用 `"c0"`。服务端在同一数据库事务内获取项目锁、核对 revision、写资源/版本/AgentRelease，并保存精确响应和审计。相同用户/应用/项目/路径/键的相同请求返回原响应，不重复发布；同键不同正文或 ETag 为 `409 IDEMPOTENCY_CONFLICT`，过期 revision 为 `412 PRECONDITION_FAILED`，缺少前置条件为 `428 PRECONDITION_REQUIRED`。HTTP 错误只返回稳定代码及 requestId，不返回 SQL、异常栈、凭据或部署地址。

PUT 正文为 `{ "name": "显示名", "spec": {...}, "regressionCases": [...] }`。可先保存草稿，发布前必须通过该草稿内容的校验。保存后旧校验失效。`validate`/`publish` 正文为 `{}`；`disable` 为 `{ "disabled": true }`，恢复为 false；`revoke` 为 `{ "revoked": true }`；Agent `default` 为 `{ "version": 2 }`。变更返回完整资源和新 ETag；详情包含最近 100 条元数据审计，审计不保存凭据或请求正文。

## 版本绑定与生效边界

每个资源版本不可覆盖。所有引用都是 `{ "id": "resource-id", "version": 1 }`，不能引用“最新”。Agent 的 Workflow、Prompt、ModelProfile、ToolPolicy、ToolConnection、RetrievalProfile、RunPolicy 引用展开成精确依赖清单；发布时生成不可变 `AgentRelease`。ToolConnection 和 ModelProfile 同时绑定已注册运行能力的 digest；受信部署后来更换相同 key 的契约，旧发布的新 Run 会失败关闭，要求重新校验发布。

调用方从 `/releases` 或默认版本入口取得 `{agentId, version, name, releaseRef, inputSchema, outputSchema, isDefault}`，把原样 `releaseRef` 交给既有 POST `/runs`。API 和 worker 共享数据库发布目录，无须为目录发布重启。Run 仍保存自己创建时的完整 release/Workflow/Prompt/model/limits 快照。

只持有 `runs:create` 的业务调用方只能发现自己同时具有所需执行权限的发布版本；具有 `catalog:read` 的目录管理者可查看项目中的可用发布。实际创建和每次调用仍重新检查身份权限，列表不是授权凭证。

- **默认版本切换、资源/版本 disable**：影响新 Run；不改变已创建 Run 的配置、待审批内容、审批摘要或 UNKNOWN。
- **显式 revoke**：紧急撤销资源能力，既有 Run 的后续执行权限检查会读取当前数据库状态并拒绝调用；不会移除账本、重新执行 UNKNOWN 或自动改派其他版本。控制、审批和对账仍走原权限规则。
- 现有身份系统的用户权限撤销继续逐次检查。普通发布权限不能绕过业务 ACL，OpsAgent 原始检索输出仍受实时领域权限和版本校验；使用 OpsAgent 受保护检索的 ToolPolicy 不能设成 publicOutput。

## 受信配置与回归门槛

目录不能注册新 URL、MCP 进程、凭据、请求头或任意模型 provider。`ToolConnection` 只能选择部署已注册且属于该项目的 toolKey；`ModelProfile` 只能选择部署中该项目已有的 trustedProfile，并可缩减超时和输出预算。模型/工具期限必须适合 worker lease；工具权限和审批要求继续使用同一运行时检查。capabilities 只返回安全的模型名称/预算、工具键/策略/schema，不返回 secretRef、endpoint、模型自由参数或真实凭据。

资源请求有 64 KiB 上限、重复 JSON 键拒绝、字段白名单、严格类型及本地 schema 引用检查。正文和 JSON 字符串内嵌参数均检查禁止的凭据/连接字段、已加载凭据原值和常见凭据格式，错误不回显正文。这不是通用保密分类器；发布者仍应只放授权的配置和合成/已授权回归样例。

非 Agent 资源做类型、引用、权限和受信能力契约校验。Agent 必须有 1–20 个冻结用例；执行真实 `WorkflowProgram` 状态机，使用冻结的外部结果，**不会调用模型、MCP 或业务工具**。

```json
{
  "name": "read-one-value",
  "inputs": {"value": 7},
  "toolResults": {"read": {"value": 7}},
  "expectedToolArguments": {"read": {"value": 7}},
  "expectedToolKeys": ["test:read"],
  "expectedOutput": {"value": 7}
}
```

工具节点须提供 `toolResults[nodeId]` 和 `expectedToolArguments[nodeId]`，逐次核对实际参数和发布工具集。模型节点须提供 `modelResults[nodeId]` 字符串和 `expectedPrompts[nodeId]` 完整消息数组，逐项比较实际渲染后的消息；消息结构为 `{role,content,toolCalls:[],toolCallId:null}`。人工节点须提供符合 humanInputSchema 的 `humanInputs[nodeId]`。最终输出还必须匹配 outputSchema 和 expectedOutput。缺少样例、错误 Prompt、错误参数、输出不一致、流程失败或超出步数/调用预算均不能发布。

报告保存测试输入摘要、逐例实际输出/工具集合/调用参数及 Prompt 摘要、结果摘要和时间。发布会再次重放并核对结果摘要，保存后必须重新验证。该门槛验证配置行为和冻结基线，不宣称进行了真实模型语义质量测评；新增真实模型重跑与第四阶段质量产品化另行确定范围和费用。

## 数据与升级

新增 `catalog_resources`、`catalog_versions`、`catalog_release_links`、`catalog_commands` 四表，复用 `platform_releases` 和项目锁；`harness_schema` 新增独立 `catalog=1` 组件。启动建表是幂等增量迁移，不修改既有 Run 表或历史快照。MySQL 使用 InnoDB，身份/项目/资源键采用 utf8mb4_bin。备份、恢复需包含这些目录表与同一时间点的核心/平台账本；不能仅复制当前默认 Agent。

当前管理资源列表最多返回 1000 项，版本与审计长期保存，未引入自动清理或生产规模检索服务。这是内部 MVP 的范围，生产保留期、容量和升级兼容矩阵按后续治理阶段验收。
