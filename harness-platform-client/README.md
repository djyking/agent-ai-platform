# Java 平台客户端

`harness-platform-client` 是 Java 17 `HttpClient` 的薄封装，仅依赖 Jackson。它调用平台 HTTP API，不依赖 `harness-core`、数据库、Spring 或任何 agent 状态机。输入和结果使用已发布 OpenAPI 对应的 `JsonNode`；响应保留 HTTP 状态、ETag、request ID、Location 和 Retry-After。

```java
var client = new PlatformClient(ClientConfiguration.defaults(
    URI.create("http://127.0.0.1:8099"), "support-pilot",
    SecretProvider.environment("SUPPORT_APPLICATION_SECRET"),
    SecretProvider.environment("SUPPORT_OPERATOR_USER_TOKEN")));
var release = client.defaultRelease("support-faq").body();
var request = new ObjectMapper().createObjectNode();
request.set("releaseRef", release.get("releaseRef"));
request.putObject("inputs").put("question", "订单什么时候发货？");
String key = UUID.randomUUID().toString(); // 本逻辑命令的唯一 key，由调用方保存。
var accepted = client.createRun(request, key);
String runId = accepted.body().path("run").path("id").asText();
var current = client.getRun(runId);
```

凭据通过 `SecretProvider` 在每次请求时解析。提供者可以读取已有进程内会话或专用密钥管理；SDK 不把凭据写入 Run、磁盘、异常文本或日志。应用服务应根据当前用户会话创建/选择用户 token 提供者，不能接受浏览器自报的 subject、权限或应用凭据。默认只接受 HTTPS 或回环 HTTP origin，禁止 URL 用户信息、重定向、任意基路径和查询串。

| 方法 | HTTP 操作 |
| --- | --- |
| `createRun` / `getRun` / `listRuns` | 创建、查询和列出运行 |
| `events` | 持久事件分页 |
| `pause` / `resume` / `cancel` | 三种显式运行控制 |
| `approval` / `decideApproval` | 审批投影与决定 |
| `unknownInvocation` / `reconcileTool` | UNKNOWN 投影与可信证据引用对账 |
| `publishedReleases` / `defaultRelease` | 当前可调用的发布列表与 Agent 默认发布 |

所有写方法都要求显式 `Idempotency-Key`；控制、审批、对账还要求显式强 ETag。SDK 不自动生成 key，不读取最新 ETag 来绕过冲突，也不轮询、重试、重发外部效果。每次方法调用只有一次 `HttpClient.send`。输入与响应有大小限制，凭据不随重定向转发。

`PlatformException` 只保留受限错误代码、HTTP 状态、request ID 和 `outcomeUnknown`。网络中断、响应丢失等命令结果不明确时，先读取 Run/业务记录；确需重试时必须再次传入原 key 与原 body。`412` 应重新读取、重新审阅后明确提交，不能盲目刷新 ETag。`reconcileTool` 只提交由独立 verifier 已核验并导入的 `evidenceRef`，不制造成功回执。

协议测试：`mvnw.cmd -f harness-platform-client/pom.xml test`。测试使用真实本机 HTTP server，覆盖 11 Run 操作、2 个发布发现接口、身份头、ETag/key、错误码、响应限额、重定向拒绝和 socket 中断不重发。
