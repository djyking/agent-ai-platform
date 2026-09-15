# 真实依赖联调入口

本模块提供显式 CLI，用合成输入验证真实模型协议、一个明确选定的只读 MCP 工具，以及专用 MySQL schema 的账本语义。它不启动中台服务，不读取其他应用的配置或凭据，也不自动发现生产环境。

本模块的普通 Maven `test` **不访问真实模型、远端 MCP 或 MySQL**；配置测试和 SQL 验收算法的离线测试使用进程内 H2。只有显式运行 `model`、`mcp-read`、`mysql` 命令才连接对应服务。全仓适配器测试可能启动 loopback HTTP fixture，仍不是外部服务联调。

## 1. 构建、配置与命令

从仓库根目录执行：

```powershell
.\mvnw.cmd -pl harness-validation -am install -B -ntp
$acceptanceRunTag = [Guid]::NewGuid().ToString('N')
.\mvnw.cmd -pl harness-validation exec:java "-Dexec.args=preflight .work/preflight-$acceptanceRunTag.json"
```

程序只读取当前进程环境变量，不自动加载 `.env`。根目录 `.env.example` 是配置说明，不是自动加载器；凭据由已选定的本机安全配置或宿主秘密管理机制注入当前进程，不写入参数 JSON、Git、报告或命令文本。缺项不会回退读取 OpsAgent 的密钥。

全部 CLI 参数为：

```text
preflight <new-report.json>
model     <new-report.json>
mcp-read  <new-report.json>
mysql     <new-report.json>
```

报告路径按当前执行目录解析，允许使用绝对路径。**报告文件必须不存在**，以免覆盖已有验收证据；每次重新运行使用新文件名。命令执行失败也会先写入经过控制的失败报告，再使进程失败。路径/用法错误和报告写入失败不能保证产生报告。

### Preflight：只读本地配置检查

`preflight` 不连接任何服务，输出 `networkCalls=0`，只检查各命令的必需配置是否非空及 MCP 认证模式是否合法：

- `MISSING_CONFIGURATION`：列出缺少的变量名。
- `CONFIGURED_NOT_VERIFIED`：必需变量已存在，**不等于可连接或已通过验收**。

它不会验证模型调用形状参数、端点可达性、参数文件是否存在、MCP tool schema、MySQL schema/用户权限等；这些由对应显式命令验证。Preflight 顶层状态为 `INSPECTED`，即使还有缺项也不是联调通过。

## 2. 模型原生工具调用验证

| 环境变量 | 必需性与默认值 | 含义 |
|---|---|---|
| `HARNESS_MODEL_ENDPOINT` | 必需，无默认值 | 完整的 `/chat/completions` URL，例如 `https://api.deepseek.com/chat/completions` |
| `HARNESS_MODEL_NAME` | 必需，无默认值 | 该端点当前可用的模型名；不依据示例自动选模型或降级 |
| `DEEPSEEK_API_KEY` | 必需，无默认值 | Bearer 凭据，仅由已配置进程环境提供 |
| `HARNESS_MODEL_CALL_LIMIT` | 必需，只接受 `2` | 此验收用例的固定协议形状：模型请求工具一次，收到回包后再回答一次 |
| `HARNESS_MODEL_MAX_OUTPUT_TOKENS` | 可选，默认 `128`，范围 `32..512` | 每次模型调用的最大输出 token 参数 |

`HARNESS_MODEL_CALL_LIMIT=2` 是**这个测试的固定调用形状，不是用户授权消费总额、账户余额或平台消费上限**。重复执行 CLI 会产生新的调用；程序没有账户级金额控制，也不会从该变量推断可运行任意多轮测试。

示例只设置非敏感配置；执行前应已明确模型与此次用量范围，并安全注入 `DEEPSEEK_API_KEY`：

```powershell
$env:HARNESS_MODEL_ENDPOINT = 'https://api.deepseek.com/chat/completions'
$env:HARNESS_MODEL_NAME = '<当前已选定且可用的模型名>'
$env:HARNESS_MODEL_CALL_LIMIT = '2'
$env:HARNESS_MODEL_MAX_OUTPUT_TOKENS = '128'
.\mvnw.cmd -pl harness-validation exec:java "-Dexec.args=model .work/live-model-$acceptanceRunTag.json"
```

实际运行是真实 Harness + 模型 HTTP adapter。模型只看到合成标记 `HARNESS_OK` 和内部只读 `validation_echo`；该工具仅回传标记，没有业务副作用。验收要求完成、两次模型调用、一次 echo，以及最终回答包含工具返回标记。它验证原生工具请求/结果配对和已知 usage 结算，不验证领域回答质量。

固定运行约束：总期限 2 分钟、12000 token 的核心预算、2 次模型、1 次工具、30 步；HTTP 连接超时 5 秒、请求/模型超时 45 秒、响应上限 262144 字节。核心 token 预留是保守估计；真实 usage 以供应商返回为准，不把预算解释为供应商账户的绝对账单上限。模型拒绝或无法按形状完成时验收失败，不自动切换模型或无限重试。

## 3. MCP 只读工具验证

| 环境变量 | 必需性与默认值 | 含义 |
|---|---|---|
| `HARNESS_MCP_ENDPOINT` | 必需，无默认值 | 完整 Streamable HTTP MCP 端点 |
| `HARNESS_MCP_READ_TOOL` | 必需，无默认值 | 此次允许执行的远端工具精确名称 |
| `HARNESS_MCP_ARGUMENTS_FILE` | 必需，无默认值 | UTF-8 JSON 参数文件，必须是对象且不超过 65536 字节 |
| `HARNESS_MCP_AUTH` | 可选，默认 `bearer` | 只接受 `bearer` 或显式 `none` |
| `HARNESS_MCP_TOKEN` | bearer 模式必需 | Bearer 凭据；`none` 模式不读取、不发送该变量 |

主机/模型端点均只允许 HTTPS；本机 loopback 可使用 HTTP。URL 不能含 userinfo、query 或 fragment，凭据只能放受控请求头。

该入口按指定工具名建立白名单，把它声明为只读、单次尝试、不重试；不会让模型选择其他工具。**本地 `readOnly=true` 无法把一个真实写工具变成只读工具**：工具名称、参数和服务端授权必须先确认。报告中的 `hostDeclaredReadOnly=true` 仅表示宿主采用了此策略，不能单独证明第三方工具行为。

### GitHub 官方远端只读示例

使用 GitHub 官方 MCP 限定 repos 工具集的只读端点 `https://api.githubcopilot.com/mcp/x/repos/readonly`，指定远端工具 `get_file_contents`，只读取公开仓库 `github/github-mcp-server` 的 README。该端点与本轮实际联调路径一致。示例文件 [github-read-arguments.json](github-read-arguments.json) 不含凭据和私有仓库信息：

```powershell
$env:HARNESS_MCP_ENDPOINT = 'https://api.githubcopilot.com/mcp/x/repos/readonly'
$env:HARNESS_MCP_AUTH = 'bearer'
$env:HARNESS_MCP_READ_TOOL = 'get_file_contents'
$env:HARNESS_MCP_ARGUMENTS_FILE = (Resolve-Path 'harness-validation/github-read-arguments.json').Path
.\mvnw.cmd -pl harness-validation exec:java "-Dexec.args=mcp-read .work/live-github-mcp-$acceptanceRunTag.json"
```

执行前由已授权流程注入 `HARNESS_MCP_TOKEN`。示例不要求读取私有仓库或写权限，也不执行 OAuth 登录。公开 README 和服务端 schema 可能随时间变化；固定 ref 到提交 SHA 可用于需要固定内容的后续验证。

验收经过官方 MCP SDK 的初始化、工具发现、schema 校验和一次真实 `tools/call`；运行中不调用模型。`toolCalls=1` 是业务工具调用计数，不表示只有一个 HTTP 请求，握手/工具发现等协议请求另行发生。固定上限为发现 4 页/256 工具、响应 1 MiB、连接超时 5 秒、请求/工具超时 15 秒、Run 期限 90 秒。工具 schema 或返回体超过这些验收上限会失败，需要单独评估，不能自动扩大目标或权限。

## 4. 专用 MySQL schema 验证

| 环境变量 | 必需性与默认值 | 含义 |
|---|---|---|
| `HARNESS_MYSQL_HOST` | 必需，无默认值 | DNS 名称或 IPv4；此 CLI 不接受 IPv6 字面量 |
| `HARNESS_MYSQL_PORT` | 可选，默认 `3306`，范围 `1..65535` | MySQL 端口 |
| `HARNESS_MYSQL_DATABASE` | 必需，无默认值 | 已预建的专用 schema，必须匹配 `harness_validation_[a-z0-9_]{1,40}` |
| `HARNESS_MYSQL_USER` | 必需，无默认值 | 仅用于该验收 schema 的数据库用户 |
| `HARNESS_MYSQL_PASSWORD` | 必需、非空，无默认值 | 数据库密码 |
| `HARNESS_MYSQL_TLS_MODE` | 本机默认 `DISABLED`；远端默认 `VERIFY_IDENTITY` | 远端只允许 `VERIFY_IDENTITY`；`DISABLED` 仅适用于精确的 `localhost`/`127.0.0.1` |

CLI 不创建 database，不接受 OpsAgent 业务 schema 名称。宿主先准备专用 schema 与可在该 schema 创建表/索引/外键并 SELECT、INSERT、UPDATE 的账户；无需给业务 schema、DROP 或 DELETE 权限。`initializeSchema` 创建/检查 `harness_schema`、`harness_runs`、`harness_run_events`，事务表要求 InnoDB。重复验收用新的 UUID 命名空间添加合成记录，不清空旧数据，也不自动清理表。

```powershell
$env:HARNESS_MYSQL_HOST = '127.0.0.1'
$env:HARNESS_MYSQL_PORT = '3306'
$env:HARNESS_MYSQL_DATABASE = 'harness_validation_dev'
$env:HARNESS_MYSQL_USER = '<专用 schema 用户>'
$env:HARNESS_MYSQL_TLS_MODE = 'DISABLED'
.\mvnw.cmd -pl harness-validation exec:java "-Dexec.args=mysql .work/live-mysql-$acceptanceRunTag.json"
```

执行前由已授权流程注入 `HARNESS_MYSQL_PASSWORD`。远端使用可验证的 TLS 身份链；不要将远端失败改成不校验证书。JDBC URL 由结构化变量生成，固定连接超时 5 秒、socket 超时 15 秒；本机连接开启 `allowPublicKeyRetrieval`，远端不启用。

此命令要求服务端产品名为 MySQL，并确认当前 catalog 与指定 schema 相同。六类验收为：

1. 并发创建的作用域幂等与同键不同内容拒绝。
2. 并发 claim 只有一个 worker 获得租约，旧 fence 无法提交。
3. 控制操作 CAS、冻结基线保护与失败事务回滚。
4. 另一连接持有行锁并延迟保存时，过期租约被拒绝；未直接观测服务端锁等待队列，不把此项等同于严格证明 SQL 进入锁等待的时间顺序。
5. 已知模型 usage 精确结算；重建 Harness/RunStore 后消费已保存模型结果。
6. 审批等待恢复、错 digest 拒绝、合成写后回执丢失保留 UNKNOWN、取消与已核验合成回执入账。

**MySQL 是真实数据库；模型、工具副作用和对账回执都是合成的。** `modelWasScripted=true` 必须保留。验收会针对自身创建的合成行主动设置过期租约并制造锁竞争；不会访问业务表。所谓 worker reconstruction 是在同一 JVM 内关闭并重建对象，不是杀进程、数据库重启、主从切换或网络分区测试。这六项通过不能推断高可用、生产吞吐或灾难恢复已经通过。

## 5. 报告和本轮证据

报告顶层包含 schemaVersion、command、UTC 时间和状态：

| 状态 | 含义 |
|---|---|
| `INSPECTED` | 只完成 preflight 配置缺项检查 |
| `PASSED` | 指定命令的本次断言通过，不扩大到其他模型、工具、数据库版本或生产环境 |
| `FAILED` | 该次命令失败；包含异常类型，若是受控错误码则附 failureCode；应先检查报告和配置再决定是否重跑 |

模型/MCP 报告记录 Run 状态、调用次数和 token。模型另记模型名、usage 是否已知和供应商已知 usage 汇总；MCP 另记工具 key、契约/参数/输出摘要。MySQL 报告记录产品/版本、六项检查名称及合成边界。报告不输出密钥、JDBC URL、完整 prompt、工具参数或远端响应正文，也不直接复制任意异常消息。

本轮三份 `PASSED` 脱敏报告已保存至 [docs/validation/20260915](../docs/validation/20260915)：真实模型原生工具往返、GitHub 公开文件只读调用、MySQL 8.0.46 的六项账本验证。MySQL 最终报告使用收窄锁等待时序表述后的第二次验证；模型/MCP 未为此重复调用。具体结论、范围和未完成项以 [阶段 1 进展与验证记录](../docs/phase1-progress.md) 为准。

工具只读联调通过不表示 OpsAgent 已接入业务身份与资源授权；MySQL 验收通过不表示已迁移业务表；模型协议通过不代表真实业务质量。SDK 发布和旧 Run 兼容性要求参见 [版本契约](../docs/sdk-versioning.md)。
