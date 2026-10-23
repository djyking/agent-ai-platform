# ACP Agent 通信兼容层

本平台支持 ACP 0.2.0 的文本/JSON 通信子集，入口为 `/acp/projects/{project}`。适配器将协议请求映射到现有持久 Run；没有创建第二套执行器、审批记录或重试账本。

协议基线是 [i-am-bee/acp](https://github.com/i-am-bee/acp/tree/e5265ca9fa06c55cd011b1e81ee927f6d80af8f6) 的 OpenAPI 0.2.0 与 Python `acp-sdk==1.0.3`。该上游仓库已归档并转向 A2A；本实现不宣称支持 A2A，也不把 ACP 兼容性等同于整个归档 SDK 的全部能力。

## 支持范围

| 协议操作 | 平台行为 |
|---|---|
| `GET /ping` | 验证应用凭据、当前用户与项目授权 |
| `GET /agents`、`/agents/{name}` | 发现当前调用方可执行的已发布版本；name 为不可变 release UUID |
| `POST /runs` | 单条 user 消息、单个内联 text/plain 或 application/json part；创建持久 Run |
| sync / async / stream | 最长 30 秒同步观察、异步轮询、最长 60 秒 SSE 观察；超过观察窗口后继续读取同一个 Run |
| `GET /runs/{id}` | 当前身份及当前输出授权下的状态与结果 |
| `GET /runs/{id}/events` | 已脱敏事件与当前状态；响应扩展 `next_cursor` 用于分页 |
| `POST /runs/{id}` | 经审阅的审批决定或明确的暂停恢复；不会自动批准 |
| `POST /runs/{id}/cancel` | 原 Run 的持久取消意图 |
| session、多媒体、content_url | 返回明确的 422；不抓取远端附件，不假装提供会话存储 |

明文输入仅适用于输入 schema 恰有一个 string 属性的 Agent；其他输入使用 JSON part，其 content 为完整输入对象的 JSON 字符串。一次请求只包含一个消息/part。输入上限沿用平台 64 KiB 严格 JSON 解析及深度限制。

## 身份与重试

客户端必须配置三类头：

```text
Authorization: Bearer <服务端应用凭据>
X-Harness-User-Token: <现有身份系统用户令牌>
Content-Type: application/json
```

写请求还必须带独立的 `Idempotency-Key`。这是本平台的安全配置要求；可通过官方 SDK 的 `headers` 或底层 `client.headers` 设置。不要把应用凭据交给浏览器。相同键和相同内容恢复同一个 Run；不同内容复用键会冲突。新 Agent 发布不会改变已提交版本。版本退休后会从发现结果消失并拒绝新键创建，但在当前身份仍有权限时，原键及原内容仍能恢复原 Run 的回执。客户端必须保存写键及 Run ID；网络失败后查询或使用同键核实，不能生成新键自动重放。

所有读取及 SSE 每轮观察重新校验身份。其他用户、应用、项目不能借 Run ID 跨域读取；源权限撤回后输出也按现有策略隐藏。UNKNOWN 映射为 awaiting，标记 `external-reconciliation-required`，不得用 resume 当作重试。

Human awaiting 的消息包含 `approval`、`etag`；只有被分配且有审核权限的主体才能读取完整审阅信息。恢复消息必须带精确 `approvalId`、原 digest、ETag 和决定。ACP resume 返回完整 Run 表示，因此调用者还必须能读取该 Run（所有者或具备相应 Run 管理权限）；仅有独立审核权限的审核人使用平台审批界面。ACP 在任何审批或控制写入前检查这个条件，避免“已经批准但响应报无权读取”的不确定结果。平台继续验证投影、授权、revision 与幂等回执。官方 OpenAPI 的恢复 body 声明了 `run_id`，官方 Python SDK 实际省略；本实现兼容省略，出现时必须与路径相等。

## 运行与验证

同步和 SSE 共用最多 8 个观察槽位；饱和时创建前返回 429。观察线程不执行模型或工具。SSE 结束、超时或报错会取消观察 Future；状态不变时每秒发送注释心跳，用于发现断开的连接。尚未开始的观察立即归还槽位，正在进行的读取结束后只归还一次，防止不响应中断的 I/O 绕过并发限制。观察线程池及队列都有上限。断开或观察超时不会创建新任务，也不撤销持久执行；客户端可继续查询 Run。

`validation/platform/phase6_acp_acceptance.py` 使用官方 SDK，对实际本机服务验证发现、两 Agent 输出交接、同键去重、SSE、跨用户隔离、人工恢复和取消。配置仅保存在忽略的 `.work/` 中，令牌从文件读取；公开报告仅包含结果、Run ID、摘要和版本信息。Java 回归另覆盖 UNKNOWN 禁止重放、输出撤权、发布版本绑定、退休后的原键恢复、独立审核人无副作用拒绝、SSE 断连/心跳/中断回收、非法附件与 HTTP 鉴权。

此兼容层适合受控服务间文本协作。多媒体、外部会话存储与 A2A 属于独立协议能力，不在本次承诺范围内。
