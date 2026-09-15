# 阶段 1：OpsAgent 已授权知识检索试点

当前交付包括独立业务适配模块、最小运行定义、localhost HTTP 合同测试，以及用户选定的实际 OpsAgent 源码隔离试点。现有部署未调用，原业务路由未修改，内部身份签发密钥未复制到适配器。另一路真实模型验证已获用户授权复用 OpsAgent 的模型凭据，详见 [阶段 1 进展](phase1-progress.md)。隔离身份和知识权限链路已通过；现有部署的身份、数据权限、延迟与质量仍需另行验收。

## 选择与边界

选择 `ops-rag-service` 已有的 `POST /internal/rag/search`，由 Harness 执行两节点 Workflow：`search → done`。请求只有 `query` 和 `topK`；服务返回已授权证据与可引用来源，不调用原 AgentLoop，也不生成答案。这样可先验证工具注册、项目权限、业务身份传递、运行状态和引用完整性。

OpsAgent 的这条服务会调用内部知识检索、配置的 reranker 与上下文组装器。**“只读”不等于“零费用”：外部 reranker、向量检索的实际数据发送与费用取决于 OpsAgent 环境。** 真实联调前必须确认该配置和测试数据授权。适配器仅发一次 HTTP 请求，工具 `maxAttempts=1`；没有内部重试，也没有失败时的公开检索回退。

业务类型、路径和权限名只存在于 `harness-integrations-opsagent`。基础 Harness、RAG 公共接口和其他应用不需要认识 OpsAgent。

## 已核实的源代码映射

核实日期：2026-09-15。本地源树 `D:/myselfProject/opsagent` 的 HEAD 为 `f79a76b924f74d6d9f24dbc3f35ce01111811ef8`。本地源树有未提交内容，因此下面记录的是当日工作区中读取的合同；部署环境是否一致仍需核对。

| 项目 | 实际 OpsAgent 源码与合同 | 新适配器行为 |
| --- | --- | --- |
| HTTP 入口 | `ops-rag-service/.../InternalAgentController.java:84`，`POST /internal/rag/search` | 直接访问宿主配置的 RAG 服务 origin，加固定路径；不猜测网关前缀 |
| 请求 | `InternalAgentDtos.java:122`：`query` 非空且最多 2000 字符，`topK` 1..20 | JSON Schema 与直接调用校验；拒绝额外 `roles/admin/documentIds` 等字段 |
| 封装 | `ops-common-core/.../ApiResponse.java`：`code/message/data/traceId`，成功 `code=0` | 同时检查 HTTP 状态与业务码；不将服务原始错误信息写入运行结果 |
| 响应 | `InternalAgentDtos.java:130`：`data.evidence` 与 `data.citations` | 保留 evidence 及引用 ID、文档 ID/名称、页码、来源字段；丢弃未声明的扩展字段 |
| 引用 | `RagService.java:1392` 的 `Source`，`sourceId` 如 `S1` | 验证唯一 sourceId、正数 chunkId/documentId，拒绝破损或超过边界的响应 |
| 当前权限 | `InternalAgentSearchService` 调用内部 knowledge，`InternalActorAccess` 复核 Auth | 不在中台侧伪造可见文档列表；上游拒绝即失败 |
| 身份 | `InternalActorTokens` / `InternalActorAccess` | 通过宿主 `OpsAgentAuthorization` 回调提供实时短期 Authorization；回调和 token 不进入 RunState |

输出示例（合成数据）：

```json
{
  "evidence": "[S1] Use INFO memory.",
  "citations": [{
    "sourceId": "S1", "chunkId": 10, "documentId": 7,
    "documentName": "Redis guide", "page": 2,
    "sourceType": "KNOWLEDGE_DOCUMENT"
  }]
}
```

引用中的 `sourceUrl` 仅为来源数据，适配器不会访问它。展示层需转义内容，外链另行校验。证据是未受信任的检索材料；未来接入回答模型时必须与系统指令分开。凭据不落库不代表业务证据不落库：query、evidence 和 citations 会随 RunState 保存，生产宿主需按项目权限、保留期限和加密要求管理。

## 身份桥是实接前置条件

OpsAgent 内部接口要求独立签名运行身份，不是普通登录 JWT，也不信任公网 Actor Header。当前源码要求 issuer=`ops-agent-internal`、唯一 audience=`rag`，令牌最长 120 秒且不超过身份租约；包含受信任的 userId、username、角色上限、runId、targetCode 和 leaseUntil。目标服务会向 Auth 再次复核活动状态和租约，并将当前角色与运行角色上限取交集。

平台 `Actor(subject, project, permissions)` 没有这些业务字段，不能据此推导 OpsAgent 的 userId 或管理员角色。宿主需实现下面的可信桥：

1. 根据已认证的 subject/project 找到经过验证的 OpsAgent 用户绑定，并校验该用户允许使用当前业务身份；绑定来源不能是工具参数。
2. 在单次调用中将 Harness 的 runId、当前业务目标与租约交给受信任签发方，使用 OpsAgent 的既有签发契约获取 audience=`rag` 的短期令牌。若在可信 OpsAgent 进程嵌入，可调用既有签发组件；独立中台服务需明确部署身份代理/签发适配，现有源码并未提供可直接假定的通用中台签发 API。
3. `OpsAgentAuthorization.authorization(endpoint, audience, context)` 仅返回本次 Authorization 值。错误不得暴露密钥；不可获得身份时拒绝调用。

该模块**没有实现新的签发服务、没有复制内部共享 secret、没有绕过签名校验**。测试中的 `Bearer fixture-token` 是 localhost fixture 输入，真实 OpsAgent 会拒绝它。身份桥未提供前，不能把 HTTP 合同通过视为真实认证联调通过。

HTTP 配置仅接受 HTTPS 远程 origin 或显式 loopback HTTP，拒绝 URL 内嵌凭据、查询参数和 fragment，不跟随重定向。直接连接远端 `http://ops-rag-service:...` 不在默认允许范围；需可信 HTTPS 入口或经授权的本地转发。body 上限、证据字符数、引用数和总 deadline 均有限制。

上游权限撤销在 Harness 中保留为 `NEEDS_ATTENTION`，`stopReason=OPSAGENT_RETRIEVAL_DENIED`，表示已知拒绝、需要处理身份配置；它不产生检索结果，不安排重试，也不回退到公开接口。单次尝试上限不会因人工 resume 重置；权限恢复后的新验证需创建明确的新请求。

## 新请求单一执行归属

试点暂不改 OpsAgent 请求路由。宿主后续接线时，需先在既有业务入口确定并持久保存请求归属，再进行一次分发；不能在执行失败后把同一请求交给另一执行器重新运行。

```text
key = (authenticatedProject, authenticatedSubject, businessRequestId)
owner = routeLedger.getOrCreateAtomically(key, selectedOwnerForNewRequests)
if owner == HARNESS:
    start Harness with creationKey = businessRequestId
else:
    invoke existing OpsAgent entry with its original idempotency contract
```

`routeLedger` 是宿主待接入的事务性归属账本，不是本模块已提供的持久路由服务。归属与输入摘要绑定；相同 key/不同输入应冲突，相同请求重试复用原归属。切换配置只影响此前未分配的新 key。查询旧 Run 按原归属读，旧运行由原执行器收尾，不自动导入新 Harness；UNKNOWN 也不触发跨执行器回退。即使本试点只读，也从开始保持这一规则，后续写工具不能采用双执行验证。

## 已完成的隔离源码试点

用户已明确选择“隔离环境、合成知识文档和专用测试用户”。据此新增 [可复现入口](../validation/opsagent-isolated/README.md)，显式读取本机 OpsAgent Java/POM 源码到独立构建目录，以实际内部 Controller、签名/角色复核、RAG 搜索、知识服务和 SQL 权限过滤串接 Harness。原 resources/配置/凭据不参与；Auth 用户状态、索引禁用与限流等替身边界在入口说明中逐项标注。

完整离线入口已通过 **10 项实际源码链路测试和 1 项内存路由策略测试**，158 个白名单源码/POM hash 前后相同。涵盖两个测试用户的不同私有范围、角色上限/撤销、账号禁用、跨服务撤权、错误 audience/签名、引用与可见性变化、凭据不进入状态/事件。证据见 [隔离报告](validation/20260915/opsagent-isolated.json)。这组测试与 SDK 模块的 11 项 HTTP fixture 合同测试分别记录。

隔离宿主使用每次新生成的测试签名密钥，未提供面向现有部署的签发服务或生产身份绑定。内存路由示例仅验证选择策略，不能替代前述持久归属账本与实际入口接线。

## 本次合同回归与部署环境验收

合同测试覆盖实际 HttpClient wire、Harness Workflow→工具→结果、创建幂等、工具权限不足、业务拒绝、每次调用重新取凭据、非法参数、不跟随 redirect、响应大小和调用 deadline、无凭据拒绝、损坏引用与错误脱敏。它使用合成 Redis 指引，不涉及真实内部文档，也不代表真实搜索召回质量。

真实联调需提供/确认以下内容后，按一次完整 Run 验收：

- 专用测试 RAG origin，以及对该环境生效的可信身份桥；至少两个可区分知识可见范围的测试用户。
- 一组经授权的合成或测试知识文档及查询；确认 reranker/检索后端是否调用外部供应商、费用和数据发送许可。
- 成功检索与引用回查；账号禁用/权限撤销/租约过期后拒绝；低权限用户不能检索高权限文档。
- 对比相同用户和查询在原接口与新 Harness 入口的返回，记录引用一致性、延迟、状态、尝试次数；不会将同一写请求发给两个执行器。
- 使用测试数据库重启 worker 后可继续待执行 Run，已完成请求不会重复派发，身份 token 不在状态/事件中出现。

可运行命令及宿主嵌入示例见 [模块 README](../harness-integrations-opsagent/README.md)。这些前置条件未具备时，阶段 1 的真实 OpsAgent 验收仍是待办。
