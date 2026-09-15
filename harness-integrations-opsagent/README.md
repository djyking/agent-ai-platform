# OpsAgent 只读检索试点

这是业务适配模块：通过 OpsAgent 已有 `POST /internal/rag/search` 获取当前业务身份可见的知识证据与引用。它运行于 Harness 的共同预算、持久状态和工具授权之下，不启动旧 AgentLoop，也不生成模型答案。

合同来自 2026-09-15 本地 OpsAgent 源码。模块不依赖 OpsAgent 的 Spring/DTO 工程，不修改 OpsAgent 仓库。详细映射、身份边界和迁移方法见 [试点说明](../docs/opsagent-pilot.md)。

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

实际签名、当前角色和知识 SQL 权限的隔离源码链路另由 [OpsAgent 隔离试点](../validation/opsagent-isolated/README.md) 验证，完整入口已通过 11 项测试。它使用新生成的测试身份和合成知识，不代表现有部署的 Auth/数据库/网关已验收。
