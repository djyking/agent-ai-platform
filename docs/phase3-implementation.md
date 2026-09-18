# 阶段三实施记录

2026-09-18。阶段三本机内部 MVP 已完成。基线 `0d5d06f79c7fa45632a09968302ba52bb1319499`，开发分支 `develop/harness-phase3`；没有合并到 `main`。

## 用户已确认的范围

- 中台前端采用独立控制台，代码放在本仓库；延续本机完整验收与现有 OpsAgent 身份。
- 第二个正式业务准备采用客服助手。本轮先建设独立客服试点应用，使用合成业务数据、独立应用身份和薄 Java 客户端，通过真实平台 API 验证第二调用方。正式客服业务迁移后续开展。
- 现有模型候选包含 DeepSeek、OpenAI、Kimi。用户已授权必要验证费用不限；阶段三按验收需要使用已有 DeepSeek 配置，其他 provider 的运营功能归阶段四。
- 本轮只实施第三阶段，第四阶段另行讨论。
- 本地及 GitHub 开发分支统一 `develop/` 前缀。

## 工作包

1. 项目隔离的资源目录：Agent、ModelProfile、Prompt、ToolConnection、ToolPolicy、RetrievalProfile、Workflow、RunPolicy。
2. 草稿编辑、校验与最小发布回归门槛、不可变资源版本与 AgentRelease、默认版本切换、停用及明确的紧急撤权。
3. 独立中文控制台：现有身份登录、项目切换、配置表单、结构化 Workflow、发布、运行/事件追踪、精确审批和 UNKNOWN 对账。
4. 薄 Java SDK 与独立客服助手试点；沿用 OpsAgent 实际接入作为另一个调用方。
5. 浏览器会话安全、动态撤权、旧 Run 版本保持、API/SQL/进程/浏览器验收及可复现运行说明。

运行与资源的凭据、允许目标仍由受信部署配置控制；目录界面不能任意新增网络目标或获取密钥。控制台通过服务端会话持有应用凭据和现有用户令牌，浏览器只持有 HttpOnly cookie 与 CSRF token。

## 分支调整

用户要求后的本地/远端分支映射：

| 原分支 | 新分支 | 保留的提交 |
|---|---|---|
| `codex/harness-foundation` | `develop/harness-foundation` | `f48fbae6bb8f0901c360215e1a8448d44900185b` |
| `codex/harness-phase1-phase2` | `develop/harness-phase1-phase2` | `0d5d06f79c7fa45632a09968302ba52bb1319499` |
| `codex/harness-phase3` | `develop/harness-phase3` | 从 `0d5d06f79c7fa45632a09968302ba52bb1319499` 继续开发 |
| `codex/harness-write-acceptance-20260918-e623b7c4` | `develop/harness-write-acceptance-20260918-e623b7c4` | `a5885f64aca7f444ecbd64a1d6726b5e8e5d0559` |

新引用逐一核对相同提交后已删除旧引用；没有改写提交历史或合并到 `main`。历史报告中的旧分支名描述的是当时操作，不改写原验收证据。

## 实现与接入

控制台位于 `platform-console/`，React/TypeScript/Vite 构建后进入平台 JAR，以 `/console/` 同源提供。现有账号、密码、验证码经服务端接入 OpsAgent Auth；浏览器仅持有 HttpOnly 会话 cookie，应用凭据与 JWT 不返回浏览器。会话有容量、有效期和精确 Origin/CSRF 校验，身份与权限每次重新核验。

八类资源支持草稿、严格类型校验、不可变版本、版本比较、默认 Agent 版本、停用和显式紧急撤权。所有写入使用 revision/ETag 与幂等键；草稿、版本、发布、命令回执与审计在同一项目 SQL 事务中提交。发布目录由 API 和 worker 共享，无需为发布重启。Agent 发布回归执行真实 WorkflowProgram，冻结外部响应，核对实际工具参数、完整 Prompt 与输出；校验和发布都不调用外部模型。

模型与工具只能引用可信部署中的已登记能力，不能从网页注册 URL、凭据、请求头或任意 provider。现有 Run 保存完整快照；默认切换、普通停用不会改变其配置与审批，紧急 revoke 在后续调用时实时检查。OpsAgent 受保护输出保持实时领域 ACL 核验，不允许发布者将其改成公开输出。

`harness-platform-client` 是 JDK HTTP 薄客户端，支持原有 11 个 Run 操作及两个发布发现入口，不包含执行引擎、不自动重试或跟随重定向。`support-pilot` 是独立 Java 进程，使用独立应用凭据、项目和用户身份，包含合成 FAQ 咨询、人工审核与运行进度。客服试点和 OpsAgent 才是两个业务调用方，管理控制台不计作第二应用。

运行详情展示持久事件、当前授权可见输出、调用用量和共享 SQL trace。trace 只存调用关联、目标、结果与耗时，不保存 Prompt/参数/输出；采集失败不改变业务执行结果。崩溃可能缺少结束 span，运行事件仍是审计依据。

OpsAgent 生产侧本轮仅扩展 `HarnessIdentityService` 的四项目录权限白名单并增加两项测试；不注册新权限给既有用户。补丁见 [阶段三身份增量](../deploy/platform/phase3-ops-catalog-permissions.patch)，实际源码已同步到本机 Ops 仓库。此前 Ops 仓库的其他未提交改动保留。

## 已取得的验收证据

| 验证 | 结果与证据 |
|---|---|
| Java 完整构建 | 本机240项完整构建及新增公开样例1项通过；最终代码在 Linux/Windows 完整 CI 验证241项，0失败/错误/跳过 |
| 控制台行为 | 16 项通过，TypeScript 与 Vite 生产构建通过；含 CSRF/ETag/幂等、不确定写入、精确资源重读、版本排序和只读权限 |
| 后端安全专项 | 26 项通过；完整登录响应 deadline/体积/重定向、隐藏字段与重复键、身份头替换、重复 Origin/Cookie、trace 隔离与日志脱敏 |
| 现有 Ops 身份 | 生产目标测试 7 项通过；隔离真实 Auth/RAG/Knowledge 16 项通过 |
| API 合同 | Run OpenAPI 11 操作、33 正反案例；目录 OpenAPI 14 操作，均通过 |
| MySQL 目录与动态执行 | [7 组、161 次 HTTP 验收](validation/20260918/phase3-catalog-acceptance.json) |
| 真实 DeepSeek | 旧版本 Run 完成只读 FAQ 与 1 次模型调用，账本 156 tokens，2 条成功 trace；只证明本次链路，不作为广泛质量结论 |
| 原有知识检索 | 目录发布 Agent 通过现有身份/领域服务返回 2 条引用，1 次工具调用、0 次模型调用 |
| 浏览器 | 真实账号登录、工作流创建/保存/校验/发布、默认版本/差异、项目切换、有效权限、运行输出与 trace 已核验 |
| 公开样例与客服防串单 | [追加验证](validation/20260918/phase3-additional-verification.json)：八资源原样加载真实 validator 并发布通过；客服 DOM 10 项通过 |
| Ops 原业务入口 | [4 组 HTTP 验收](validation/20260918/phase3-ops-ingress.json)：真实业务 API 使用目录发布，2 条引用；同请求同 Run，改正文409 |
| 客服浏览器全流程 | [实际页面与 API 证据](validation/20260918/phase3-browser-acceptance.json)：独立客服/审核员、创建、暂停/恢复、人工确认及 v2 真实模型完成，166 tokens |
| UNKNOWN 页面对账 | [一次调用/一次合成写入](validation/20260918/phase3-console-unknown.json)：独立回执核验、导入、页面对账、显式恢复后完成，没有重复执行 |
| 备份与恢复 | [15 表组逐项匹配](validation/20260918/phase3-mysql-restore.json)：停止平台写入后备份，恢复到全新隔离库，没有覆盖原库或启动恢复 worker |

目录验收明确验证：v1 Run 等待人工输入时发布 v2、切默认并停用 v1；原 Run 的 releaseRef、deadline、limits、审批 digest 不变，随后按 v1 完成。另一个独立撤权样例进入 `NEEDS_ATTENTION`，工具/模型调用数均为 0。项目、应用、用户的目录与 trace 越权访问也被拒绝。详情见公开报告及可重跑的 [catalog_acceptance.py](../validation/platform/catalog_acceptance.py)。

已记录的平台 JAR SHA-256：`dd9968039b2734658c081e65394ed7e1e4bcdc83e35b2c5e504f5a9c3503c3c5`。浏览器截图：[工作流发布](validation/20260918/phase3-console-publish.png)、[实际调用轨迹](validation/20260918/phase3-console-trace.png)。

客服修复版 JAR SHA-256：`266a7314127ecc1548deadc8991aa6dfc66c8d3738051c9b59b224d2d7293613`。页面证据：[独立审核员](validation/20260918/phase3-support-approval.png)、[完成回答](validation/20260918/phase3-support-completed.png)、[UNKNOWN 对账](validation/20260918/phase3-console-reconciliation.png)。

验收中保留一个真实异常：售后问题 Run `ad15c9d1-e10c-4337-bfe5-2565c44ebe1d` 在工具成功后发生 `MODEL_CALL_OUTCOME_UNKNOWN`，约19.418秒；实际配置与快照均为60秒，不能误判为20秒配置超时。底层传输异常类型未保存，原因无法进一步确证。账本1999是保守预留，不是确认的供应商用量。没有自动重放或伪造完成；随后通过页面明确创建的另一个咨询 `ea8d781f-e617-4cd5-b2f2-7e68db604e7e` 正常完成。该异常反映当前诊断粒度边界，也验证 UNKNOWN 仍受保护。

## 本机入口与复现

- 管理控制台：`http://127.0.0.1:8099/console/`。
- 独立客服：`http://127.0.0.1:8100/`。
- 公开部署和八类型示例：[阶段三部署说明](phase3-deployment.md)。构建入口：[build.ps1](../deploy/platform/build.ps1)。
- 本机私有配置、合成身份访问码及进程日志保存在忽略的 `.work/phase3/`；没有提交密钥。访问码用于建立本机试点会话，不替代平台身份鉴权。

验收使用专用 MySQL 数据库 `harness_platform_phase3` 与 `opsagent_harness_pilot_phase3`。Ops 隔离宿主挂载真实已有 Auth、JWT、MyBatis、RAG 和知识服务；图形验证码生成、哈希与单次验证是真实实现，但 Redis 采用内存测试替代，refresh-token 持久化被隔离。这不是原系统全量生产启动验收。

## 范围边界

本轮是本机内部 MVP。正式客服业务、生产切流、独立账号/组织体系、自助项目和 grant 管理、向量索引托管、并行 Workflow/补偿、多模型运营、质量评测产品化、生产容量/SLO 与第四阶段均未实施。DeepSeek 已真实调用；OpenAI/Kimi 没有因被列为候选就算作已接入。

目录列表最多 1000 项；历史版本、事件和幂等回执长期保留，未实现自动保留期清理。控制台项目页展示当前授权，修改账号/角色/grant 仍在已有身份权威中完成。控制台待办基于当前可见 Run 列表；仅持有审批权限的独立审核员通过明确 Run ID 读取审批，尚无专用跨所有者审批任务索引。

合成 FAQ 使用词法检索，只验收明确示例问句，不代表中文语义检索质量或真实订单查询；试点没有退款、订单修改或对外消息工具。发布回归的冻结样例和真实模型测试分别报告，不混为同一质量证明。

## 最终交付状态

本机阶段三范围验收已完成，包括两个业务调用方、控制台、目录发布、审批、UNKNOWN、真实模型、备份恢复。代码提交 `75c50ca90db4c846eb55a428edd9a408b58e7a78` 的 [GitHub CI](https://github.com/djyking/agent-ai-platform/actions/runs/35318189003) 三项全部成功：Linux、Windows、API 合同。Java 241项、控制台16项及客服 DOM 10项随 CI 检查；[远端证据](validation/20260918/phase3-ci.json) 绑定准确代码提交。

普通 Git HTTPS 推送遇到连接重置，最终通过 GitHub Git Data API 上传完全相同的 blob/tree/commit，核对 SHA 后以非 force 更新 `develop/harness-phase3`。GitHub Actions actor 为 `djyking`；API/MCP 是调用方式，不决定仓库推送身份。截图所示 `openai/codex published releases` 是 GitHub 动态中的另一个仓库发布记录，不是本仓库的提交身份。

未合并 main，未生产部署，第四阶段未启动。真实模型传输 UNKNOWN 的诊断限制和保留记录已在上文披露，不将其包装成供应商全部调用成功。
