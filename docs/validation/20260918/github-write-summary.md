# GitHub 官方 MCP 受控写验收

2026-09-18，本轮显式 `github-write` 验收 **PASSED**，7 项检查全部通过。结果对应本次指定仓库、分支和合成文件，不扩展为生产写工具、远端幂等或故障恢复的整体保证。

证据：[`github-write-discover.json`](github-write-discover.json) 为写前只读能力/权限检查；[`github-write-acceptance.json`](github-write-acceptance.json) 记录审批 digest、工具策略、调用次数、Run 事件以及独立核验的 commit/blob SHA。

## 远端实际变化

- 仓库：`djyking/agent-ai-platform`。
- 新分支：[`codex/harness-write-acceptance-20260918-e623b7c4`](https://github.com/djyking/agent-ai-platform/tree/codex/harness-write-acceptance-20260918-e623b7c4)，从 `main` 的 `7e83eefd68fb23612b00221d25c4a22cf04fb92d` 创建。
- 只创建下表两个合成文件；没有更新默认分支、现有功能源码或工作流，没有创建 PR、Issue、评论，也没有删除资源。

| 文件 | 提交 SHA | Blob SHA |
|---|---|---|
| [validation/sandbox/20260918-e623b7c4/approved.txt](https://github.com/djyking/agent-ai-platform/blob/4c0c077e6dcb801c65cb1c315f645dcd9baa9b07/validation/sandbox/20260918-e623b7c4/approved.txt) | `4c0c077e6dcb801c65cb1c315f645dcd9baa9b07` | `d3ee46b66b0da8bc8aa307d97efa7ecd8a14581c` |
| [validation/sandbox/20260918-e623b7c4/lost-response.txt](https://github.com/djyking/agent-ai-platform/blob/a5885f64aca7f444ecbd64a1d6726b5e8e5d0559/validation/sandbox/20260918-e623b7c4/lost-response.txt) | `a5885f64aca7f444ecbd64a1d6726b5e8e5d0559` | `5b987ed3068b3020ea27df3ef57b8e2b32263fd5` |

官方 MCP 地址为 `https://api.githubcopilot.com/mcp/`。共派发 **3 次写工具调用**：`create_branch` 1 次，`create_or_update_file` 2 次；收到 3 个成功 MCP 响应，其中 1 个被包装器受控丢弃。模型调用为 0。

## 本次实际验证

1. 工具采用宿主精确参数范围护栏、必需审批、不可自动重试、最大一次尝试；发现能力本身不授予权限。
2. 每项操作在 Harness `WAITING_APPROVAL` 时，由独立 REST GET 确认远端目标不存在；额外 tick 没有发出写调用。
3. 错误审批 digest 被拒绝；匹配 digest 由已获本次范围授权的宿主批准。这里没有声称用户逐次点击 UI。
4. 批准后完成真实 MCP 建分支和建文件；独立 REST 核验内容、按 Git blob 格式计算的 SHA、提交父链和唯一变更文件。
5. 第二次文件写成功返回后，包装器刻意不把结果交给 Harness，而是抛出 UNKNOWN；Run 保留 `NEEDS_ATTENTION` 和未知结果。
6. UNKNOWN 恢复被拒绝；取消、同进程重建 Harness 和 tick 没有重发；取消后恢复也被拒绝。
7. 独立只读核验形成回执，通过 `reconcileTool` 入账，最终 `CANCELLED`，分支 HEAD 和总写次数保持不变。

## 可复现入口与限制

新增可配置 `github-write-discover`、`github-write` CLI，以及 `harness-validation/run-github-write.ps1`。参数、授权范围绑定与环境凭据用法见 [`harness-validation/README.md`](../../../harness-validation/README.md)。重跑须选择新的明确授权 tag；此 tag 已有资源，入口会拒绝重新写。失败后先只读核验可能已发生的副作用，不自动重试。

本次新增 4 个离线测试：精确范围拒绝越界、重复参数不再派发、完整审批/UNKNOWN/对账流程、Git blob SHA 算法。`mvnw.cmd -pl harness-validation test -B -ntp` 共 **10/10 通过**；普通测试没有访问 GitHub。

响应丢失采用**收到真实成功响应后的受控丢弃**，不是物理网络断线。账本采用 `InMemoryRunStore`，只重建了同一进程中的 Harness 对象，不是杀进程或持久化恢复验证。Maven `exec:java` 退出时出现官方 SDK Reactor daemon 清理警告，命令和验收断言仍成功；这一现象没有被当作远端写失败或忽略的测试失败。
