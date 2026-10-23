# 第四阶段：应用 Studio、任务工作台与共享能力

日期：2026-09-18。用户已批准产品重设计；开发分支 `develop/harness-phase4`。本记录以实际代码和验收证据为准。

## 第四阶段的调整

目标改为“构建应用 → 同页试用 → 固定样本评测 → 人工发布 → 任务与结果”，原八类资源保留为高级管理入口。继续使用 Java 17 Harness、同一 SQL 执行账本、审批与 UNKNOWN 恢复语义。OpsAgent 是现有领域适配，不再是通用控制台的必选前置服务。

| 切片 | 本次实施 | 验收门槛 |
|---|---|---|
| 4A 产品验证 | 已批准的重设计与交互原型 | 以应用、任务和共享能力组织入口 |
| 4B Studio 纵向流程 | 三种模板；指令/模型/知识/工具/人工请求/预算表单；同页真实体验；固定快照；发布 API；身份可配置 | 脱离 Ops 完成创建、体验、评测、发布；原运行保证保持 |
| 4C 共享能力与使用体验 | 文本知识集合及版本/撤销/ACL；批准的模型/MCP连接启停、诊断、工具发现差异；任务/会话分组；真实结果文件下载 | 当前权限贯穿输入、调用、输出和产物；发现不授权；新任务可停用 |
| 4D 质量与交付 | 历史输入转样本；冻结样本；候选和已发布基线分别执行；模型与版本对比；精确评测发布门槛；默认版本回退 | 旧评测不能发布新草稿/新绑定；结果未知不算通过；回退不改变已有Run |

4C 新网络目标、密钥引用及 MCP 执行绑定由部署管理员在受信配置中登记；控制台管理这些已批准目标的项目启停、诊断和发现结果。普通构建者选能力和创建应用无需改 JSON。任意目标及密钥在线接入、OAuth、stdio 进程隔离继续列为独立扩展，不提供不受控地址输入。

## 产品和运行对象

- 应用草稿使用 `revision` 和 `If-Match: "sN"`。每次操作的 actor 范围幂等回执持久存储；响应丢失后先查精确命令回执，未找到不等于未提交。浏览器保留阻断，不自动重发。
- 体验、评测、发布分别生成不可变快照，固定声明、模型 profile、受信连接/工具契约和知识版本。改变配置会使旧通过评测失效。通用 Runs API 同样执行预览所有者检查和应用停用检查。
- 一个 Task 对应一个真实 Run。Session 只分组任务，不隐含跨任务聊天记忆；用户重新提交是新的调用。人工请求回到原 Run 处理。真实工具写入保留高级运行审批与对账，Studio 首版只组合公开输出策略的只读工具。
- 评测每次最多 10 个样本，预期文本规则不能为空；候选和基线分别计费。人工节点必须真实处理后才继续。文本包含规则、契约有效性和人工发布审阅分别展示，不把规则通过当成语义正确率。
- 模型答案来自受保护知识时采用保守整份输出策略：任何冻结来源当前失权即隐藏整个结果、输入展示和导出。原 Ops 原始检索策略继续保留，不放宽缺少血缘的 Ops 模型综合答案。
- 产物是完成结果的文本/Markdown 导出，使用内容摘要、来源 Run、当前读取权限。没有任意文件写入、代码执行或浏览器沙箱，也没有声称这类能力已完成。

## 身份、模型和知识

现有安装默认 `HARNESS_IDENTITY_PROVIDER=opsagent`。独立验收可显式启用受限于 loopback 的 `local-test`；凭据和 grants 由私有配置提供，不是新的生产账户管理系统。原项目/应用/用户身份与运行委托职责保持，控制台根据权限发现可选项目。

模型 adapter 支持 `deepseek` 和 `openai-compatible`。后者显式选择 token 参数协议，不从供应商名称猜协议或静默降级。连接诊断读取模型元数据，不把它当作模型生成成功；MCP 诊断发现工具及 schema 差异，不调用工具、不自动授权。

知识首版是平台自管文本与词法检索，保留不可变文档版本和 Run 来源记录。没有 PDF 解析、向量数据库、embedding 流水线或多模态知识处理；Ops 知识继续由其适配器处理。文本及引用均作为不受信数据放进 prompt。

详见 [Studio API](phase4-studio-api.md)、[身份与模型配置](phase4-identity-and-models.md)、[知识及连接契约](phase4-shared-capabilities.md)。

## 数据迁移与运行

新增组件 `studio=1`、`capabilities=1`，采用增量建表，不改现有 core/platform/catalog 组件版本。新增 `studio_documents`、`studio_commands`、`capability_records`、`capability_versions`、`capability_commands`、`knowledge_run_sources`。快照仍写入 `platform_releases`；所有任务 Run 使用原事务与预算准入。

构建先执行前端测试/构建，再打 Java 包。可用 `-Dplatform.artifact.name=harness-platform-phase4` 给并行本机验收生成独立包名；运行从私有目录复制的版本化 JAR 启动，避免 Windows 上覆盖正在运行的制品。

验收脚本 [studio_acceptance.py](../validation/platform/studio_acceptance.py) 使用显式私有配置和合成数据，跑真实 HTTP、MySQL、DeepSeek。输出只包含结果、Run 引用和账本用量，不含密钥。不会从失败事务自动重试，不调用真实写工具。浏览器验收另行记录。

## 本机验收结果

- Java 全量 `verify`：57 组、283 项测试，零失败/错误/跳过；前端 31 项及生产构建通过；原客服试点 10 项通过；既有 Run/Catalog OpenAPI 校验通过。[汇总](validation/20260918/phase4-verification.json)
- 真实 HTTP + 独立 MySQL + DeepSeek：`deepseek-v4-pro` 和 `deepseek-flash` 完成知识问答、结构化资料、固定样本评测、首次/候选版本发布、旧评测拦截、回退与已有 Run 稳定性、停用防绕过和当前知识 ACL 撤权隐藏结果/产物。[八项验收](validation/20260918/phase4-studio-acceptance.json)
- 第二家真实服务 OpenAI：`gpt-4.1-mini` 使用通用 Chat Completions adapter，完成独立应用的真实预览、冻结样本评测及发布。两次新模型调用均完成；Kimi 等其他兼容服务未做本轮真实验收。[跨供应商验收](validation/20260918/phase4-openai-acceptance.json)
- 浏览器实际完成模板建应用、保存、同页体验、转样本、真实评测、人工确认发布、已发布任务和下载；新任务页突出结果，运行细节折叠。重启服务后同一任务仍完成、模型与工具调用各为 1 次，未重新执行。[浏览器与持久化证据](validation/20260918/phase4-browser-acceptance.json)
- MySQL 8.0.46：完整备份恢复到新隔离库，21 张表的结构语义、行数及所有列行哈希一致，源库未覆盖、恢复 worker 未启动。[恢复报告](validation/20260918/phase4-mysql-restore.json)；独立回归确认冗余字符集写法等价、真实 charset/collation 变化可检出。[结构回归](validation/20260918/phase4-restore-metadata-regression.json)

真实验收保留了失败证据：一次结构化资料回答漏掉已传入字段，经检查提示快照确认数据完整；明确任务指令后新的运行通过，未降低预期数值规则。首个 OpenAI Run 进入 UNKNOWN；随后同环境无密钥请求复现 Java 直连超时，而显式本机现有代理返回 HTTP 401，定位为连接路径问题。仅验收进程显式配置代理后，新的独立应用通过；原 Run 未重放，保守 token 预留不代表供应商实际账单。[初次失败记录](validation/20260918/phase4-openai-initial-unknown.json)

本机产品入口为 `http://127.0.0.1:8101/console/`，空间 `studio-dev`，使用显式 `local-test` 身份；私有登录、启动配置和备份保存在 Git 忽略的 `.work/phase4/`。这些凭据不进入提交。验收数据均为合成数据，外部业务写操作为零。

## 后续阶段

第五阶段仍是生产治理：容量、SLO/告警、保留与清理、备份恢复、多 worker 故障/网络分区、升级兼容及观测窗口。第六阶段才进行 OpsAgent 和第二业务正式迁移、旧执行职责退出。

本轮不执行生产流量切换或合并 main。长期记忆、自动模型降级、并行子 Agent、分布式补偿、任意插件安装、文件/代码沙箱均不作为已交付功能。
