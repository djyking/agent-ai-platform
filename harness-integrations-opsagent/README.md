# OpsAgent 只读检索试点

## 现有身份桥（2026-09-18）

`OpsAgentIdentityClient(URI authOrigin, Duration timeout)` 对接 OpsAgent Auth 的受信应用桥。
`introspect(applicationAuthorization, projectId, userToken)` 验证现有登录 JWT 与当前用户/角色和显式项目授权；
`bind(applicationAuthorization, projectId, userToken, runId, deadline)` 幂等绑定异步 Run；
`current(applicationAuthorization, delegationId, runId)` 重新核验撤权；
`token(applicationAuthorization, delegationId, runId, "rag")` 获取最长 120 秒的现有内部运行身份。

`applicationAuthorization` 是完整 `Bearer <应用凭据>`，`userToken` 是原有登录 JWT。
仅授权绑定存储非秘密 `delegationId`；登录 JWT、应用凭据和短期内部令牌不进入运行快照。
委托最长 24 小时，冻结角色和权限上限；当前授权可以收紧，后续增权不会扩张既有委托。
Auth 中应用凭据仅存 SHA-256，应用/项目/用户授权显式配置，不能通过客户端自报角色签发身份。
调用失败不回退匿名身份；客户端不跟随重定向，默认不记录请求/响应正文。

此桥需要本轮 OpsAgent 新增的 `HarnessIdentityController` / `HarnessIdentityService` 与
`ops-auth-service/src/main/resources/harness-identity-schema.sql` 迁移。
`ops.harness.identity.enabled=true` 才启用；不会修改原用户体系，也不会默认创建应用凭据或授权。
完整本机隔离验证与边界见 [身份桥验收](../validation/opsagent-isolated/README.md)。

`OpsAgentProjectionClient(URI ragOrigin, Duration timeout).allowed(ragAuthorization, citations)`
只发送 documentId、chunkId、version 到域侧，实时确认全部引用仍可读、已发布、chunk 归属与版本匹配。
任何引用失效、委托过期、错误响应或超时都返回 false。平台只允许原应用与原主体读取纯检索 release 的原样结果；
模型加工结果不能借引用核验放行。空引用也不放行，历史检索委托过期后保持 `OMITTED`。

这是业务适配模块：通过 OpsAgent 已有 `POST /internal/rag/search` 获取当前业务身份可见的知识证据与引用。它运行于 Harness 的共同预算、持久状态和工具授权之下，不启动旧 AgentLoop，也不生成模型答案。

原检索合同来自 2026-09-15 本地 OpsAgent 源码。SDK 模块不依赖 OpsAgent 的 Spring/DTO 工程；
2026-09-18 身份、路由和引用核验桥需要 OpsAgent 新增类与显式迁移。详细映射见 [试点说明](../docs/opsagent-pilot.md)。

## 嵌入方式

```java
import io.github.djyking.harness.integrations.opsagent.*;
import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

// trustedIdentityBridge 是宿主实现的 OpsAgentAuthorization。
// 必须以可信业务身份为依据签发/获取 audience=rag 的短期运行身份。
// 回调参数含 endpoint、audience、ExecutionContext，不能用用户表单中的 roles 自行构造身份。
OpsAgentAuthorization trustedIdentityBridge = /* 宿主提供 */ null;
var tool = new OpsAgentRagTool(
    OpsAgentRagConfig.defaults(URI.create("https://rag.example.invalid")),
    trustedIdentityBridge);
var registry = new ToolRegistry();
registry.register(tool.descriptor(), tool);

// 示例用内存存储，跨进程运行时传入同一项目已配置的 JdbcRunStore。
try (var harness = new Harness(new InMemoryRunStore(), (request, context) -> {
    throw new IllegalStateException("This retrieval-only pilot does not invoke a model");
}, registry)) {
    harness.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
    // subject/project 应来自宿主认证，不能取自未验证的请求头。
    var actor = new Actor("authenticated-user", "pilot-project", Set.of(
        "run:create", "run:read", "tool:" + OpsAgentRagTool.KEY, OpsAgentRagTool.PERMISSION));
    var run = harness.start(OpsAgentRetrievalPilot.definition("查询 Redis 内存的方法", 3),
        actor, List.of(OpsAgentRagTool.KEY), new Budget(1000, 1, 1, 20),
        Duration.ofSeconds(45), "authenticated-request-id");
    for (int i = 0; i < 20 && (run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING); i++) {
        run = harness.tick(run.id);
    }
    // run.output 为 {evidence, citations}；失败时检查 status/stopReason。
}
```

以上是宿主接线示例，`null` 明确表示尚未配置身份桥，直接照抄会在构造时失败。生产环境必须提供该回调和持久存储，不接受自动降级到匿名/管理员身份。

可在仓库根目录执行合同测试，无需真实 OpsAgent、模型、MCP 或业务凭据：

```powershell
.\mvnw.cmd -pl harness-integrations-opsagent -am test -B -ntp
```

HTTP fixture 使用假的 bearer 文本，只验证请求形状与 Harness 集成，不验证 OpsAgent JWT 签名、Auth 租约、知识可见性或真实 reranker。真实服务联调必须另外验收。

实际签名、当前角色和知识 SQL 权限由 [OpsAgent 隔离试点](../validation/opsagent-isolated/README.md) 验证：
原 11 项回归加 5 项实际 SQL Auth、持久路由及引用核验，共 16 项。
专用本机 MySQL、真实平台进程及实际公共入口另完成 11 类 HTTP 验收，见
[脱敏报告](../docs/validation/20260918/opsagent-local-mysql-acceptance.json)。
这些使用合成账户与知识，不代表原生产自动配置、网关、ES、限流、模型质量或容量已验收。
