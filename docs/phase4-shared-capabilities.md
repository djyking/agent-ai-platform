# 第四阶段：共享知识与受信连接

本文记录 `KnowledgeService`、`CapabilityService` 的实现边界与 HTTP 契约。执行仍使用现有 Harness、Run 账本、身份委托和 MySQL 事务；这些服务没有创建第二个 Agent 执行器。

## 独立知识集合

平台可以自行保存文本资料，无须 OpsAgent 知识服务。首版支持文本录入、不可变文档版本、固定来源快照、词法检索、永久撤销和当前权限检查。中文查询使用汉字双字组合辅助匹配；这不是向量检索，不代表已完成 PDF 解析、分布式索引、语义召回质量或大规模容量验收。

- `PUBLIC` 表示资料不需要额外的主体白名单；所有接口仍要求项目、应用身份和对应权限，不提供匿名访问。
- `PROJECT` 的 `allowedSubjects` 为空时允许该项目内获授权的主体；非空时按 `Principal.subject` 精确检查，不使用前端提交的身份。
- 创建集合、变更 ACL、停用/重新启用需要 `catalog:publish`。受保护的 `PROJECT` 集合不能改为 `PUBLIC`。
- 文档编辑需要 `catalog:write`，而且编辑者必须拥有集合当前读取权限；每次更新新增不可变版本。撤销后不能重新启用旧文档 ID，应创建新文档。
- 集合列表对发布管理者显示管理元数据，便于恢复停用的集合；这不授予正文读取权。已撤销文档的列表/当前详情不返回正文，历史版本正文也拒绝读取。
- 当前限制为每项目 200 个集合、500 个文档；单文档正文 24,000 字符，单快照最多 500 个文档引用，单次最多 8 条检索结果。界面应真实显示这些边界。

`snapshot(principal, collectionIds)` 返回固定的项目、集合、文档、版本、摘要、可见性引用，不把可变正文带进应用草稿。运行时 `retrieve(currentPrincipal, refs, query, runId)` 从服务器保存的快照读取，逐一检查当前集合 ACL 和撤销状态，在同一事务保存实际命中的 Run 来源，然后返回 `context/citations`。参数不能选择任意文档引用；Studio 的工具处理器从 Run 的发布快照加载引用。

输出访问采用保守策略：当前无权访问任何必需来源时隐藏全文，包括生成答案和从其派生的文本产物。`canReadOutput` 检查 Run 已登记来源；空来源不会被当作公开证明。Studio 也可对服务器持有的完整 `knowledgeRefs` 使用 `canReadSources`，因此未命中资料或任一资料撤销不会通过改变答案文本绕过检查。

任务列表复用单任务的当前授权投影，显示有权读取的问题、状态和产物；失权后不再返回输入或文件。评测页面同样移除失权任务的样本输入、预期文本和结果，不从评测行绕过任务投影。用户主动编写的应用草稿仍按已有 `catalog:read` 授权读取，不宣称从草稿自动追溯用户手填文字的来源。当前任务列表采用逐项查询，最多 2,000 个产品文档；尚未完成分页和大规模性能治理。

## 受信连接

浏览器只操作部署已批准的模型 provider 和 MCP serverId；不能提交 endpoint、secretRef、任意 HTTP 头或新的秘密。创建新的网络目标、凭据引用和 MCP 执行 binding 仍需部署管理员配置。当前没有任意 URL 注册、OAuth 授权界面或热加载远端工具。

- 连接按项目启停，状态、版本和诊断持久保存。`catalog:publish` 可以启停和命名，`catalog:validate` 可以执行连接诊断；`catalog:write` 不足以管理连接。
- DeepSeek 和 OpenAI 兼容模型均可管理。模型诊断仅 GET 对应已批准 API 路径下的 `/models`，不执行生成、不声称模型回答正确、不冒充真实生成验收。
- MCP 使用现有官方 SDK，在已批准的精确 endpoint 初始化和发现工具，限制响应大小、超时和分页。比较输入与输出 schema 的摘要，报告新增、变化和移除；发现不会注册工具或给予执行权限。
- HTTP 重定向不跟随，秘密不返回浏览器，错误只保留清理后的状态码；诊断结果不保存模型响应正文或远端描述。含当前凭据的 MCP schema 被拒绝保存。
- 实际模型和工具调用前，runtime 使用 `assertModelEnabled(project, provider)` 和 `assertToolEnabled(project, toolKey)` 检查当前连接状态。连接状态不更改已有任务的模型参数或工具快照。

## HTTP 契约

所有路径位于 `/v1/projects/{project}`。所有响应 `Cache-Control: no-store`。读取需要新鲜身份与 `catalog:read`；文档正文额外检查当前集合 ACL。

| 方法与路径 | 请求或响应 |
|---|---|
| `GET /knowledge/collections` | `{items:[{id,name,visibility,allowedSubjects,disabled,revision}]}` |
| `GET/PUT /knowledge/collections/{id}` | PUT `{name,visibility,allowedSubjects,disabled}` |
| `GET /knowledge/collections/{id}/documents` | `{items:[{id,collectionId,title,text?,revoked,digest,revision}]}` |
| `GET/PUT /knowledge/collections/{id}/documents/{document}` | PUT `{title,text,revoked}`；GET 撤销文档不含正文 |
| `GET /knowledge/collections/{id}/documents/{document}/versions/{version}` | 固定版本；已撤销文档拒绝访问 |
| `POST /knowledge/query` | `{collections:[ids],query}` → `{context,citations,truncated,retrieval,untrustedSourceData}`；纯读取，无幂等键和 ETag 要求 |
| `GET /capabilities/connections` | `{items:[{id,kind,name,enabled,revision,adapter,credentialConfigured,targetApproved,...}]}` |
| `GET/PUT /capabilities/connections/{kind}/{id}` | `kind=model|mcp`；PUT `{name,enabled}` |
| `POST /capabilities/connections/{kind}/{id}/diagnose` | `{}` → 新版本状态，包含 `diagnostic`，MCP 还包含 `discovery` |
| `GET /knowledge/commands/{key}` | 当前项目、应用、主体的知识写入收据元数据 |
| `GET /capabilities/commands/{key}` | 当前项目、应用、主体的连接操作收据元数据 |

所有 PUT 和连接诊断 POST 要求 `Idempotency-Key`（建议 UUID）和 `If-Match: "k{revision}"`；创建使用 `"k0"`。幂等键在项目、应用、主体内全局唯一，绑定完整操作、目标、请求和原 revision。冲突返回 409，过期 revision 返回 412，缺少条件返回 428。

恢复收据返回 `{status:"COMPLETED",kind,id,operation,revision,responseDigest}`，不返回旧正文；随后读取当前资源确认界面状态。未找到返回 `{status:"NOT_FOUND"}`，不证明在途请求永远不会提交，客户端不能自动重试未知写入。知识 `kind` 为 `knowledge-collection` 或 `knowledge-document`，文档 `id` 为 `collectionId~documentId`；连接为 `connection-model` 或 `connection-mcp`。

## 持久化与验证

新增 `capability_records`、`capability_versions`、`capability_commands`、`knowledge_run_sources`，使用同一 `PlatformRepository` / JDBC 事务与项目锁，MySQL 身份列采用二进制比较。备份恢复必须包含这四张表和 `harness_schema` 中的 `capabilities` 版本记录。

Studio 还增加 `studio_documents`、`studio_commands` 和 `harness_schema` 的 `studio` 记录。`deploy/platform/restore-proof.ps1` 已改为自动枚举源库全部基础表，不再维护第三阶段固定表名单；因此上述六张第四阶段表和未来新增表都会参与验收。脚本对每行所有列在 MySQL 端生成哈希，再按行哈希排序比较整个多重集合，同时核对行数、结构语义哈希和完整表集合。NULL、空字符串、文本和二进制以不同编码参与哈希，报告不输出业务正文。

结构比较使用 MySQL 8 `information_schema` 中解析后的表属性、列定义、实际字符集/排序规则、索引、PK/FK/CHECK 约束和分区定义，不比较 `SHOW CREATE TABLE` 的字面拼写。MySQL 可能把继承的列字符集在恢复后显式输出，因此原脚本会把语义相同的冗余 `CHARACTER SET` 误报为差异；当前元数据指纹消除了这种误报，没有删除或忽略实际字符集/排序规则。仅本库内的外键 schema 名归一为 `SELF`，因为隔离恢复库必须使用新名字；外部 schema 引用仍逐字比较。报告注明 `definitionComparison=MYSQL_INFORMATION_SCHEMA_V1`。

运行恢复证明前应停止新增请求并停下执行 worker，使用新的私有目录及新的报告路径。脚本先记录源摘要，单事务逻辑备份后恢复到随机创建且确认不存在的隔离库，再比较源数据前后是否稳定、隔离库数据和定义是否一致。任何源变化或表集合变化会失败，不以该次结果声称恢复成功；失败留下隔离库和私有 dump 供排查，不启动恢复库 worker、不覆盖源库。报告 `schemaVersion=2`、`tableDiscovery=ALL_BASE_TABLES`，每项包含真实 `tableName`、`rowCount`、`sha256`、`definitionSha256`；旧的 `tableGroup` 字段保留但值改为实际表名。

针对性测试覆盖：版本固定与重建服务后的读取、跨应用和跨项目隔离、来源撤权后隐藏输出、伪造摘要、不可公开化/不可反撤销、旧收据 ACL 防绕过、未知命令恢复、严格 HTTP 请求、项目连接启停、白名单 URL/秘密字段拒绝、模型元数据诊断不重复调用、重定向拒绝、真实 MCP SDK 的 schema 变化发现且零工具执行。

```powershell
.\mvnw.cmd -q -pl harness-platform-service -am '-Dtest=KnowledgeServiceTest,CapabilityServiceTest,CapabilityHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

这些是功能和契约测试；实际运行、浏览器和 MySQL 验收应结合第四阶段整体报告阅读。
