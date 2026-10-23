# 本地平台进程验收

`process_acceptance.py` 启动两个独立的 Java API + worker 进程，共享专用 MySQL，并通过真实 HTTP 调用平台 API。模型和 MCP 工具由只监听回环地址的合成 fixture 提供。它不访问 GitHub、不调用收费模型、不操作生产库。

先由环境所有者准备本地 MySQL `127.0.0.1:53955/harness_platform_phase2`、可用的 OpsAgent Auth 验收 host 和专用 `platform-acceptance` 授权。私有 `.work/mysql-phase12/ops-host-metadata.private.json` 保存端点与用户 token；平台数据库和签名凭据以同目录 DPAPI 文件保存。不要把私有文件、完整日志或 token 加入版本库。

```powershell
python validation/platform/process_acceptance.py prepare --workspace 'D:\myselfProject\agent-ai-platform' --java 'D:\jdk17.0.19\jdk-17.0.19\bin\java.exe'
python validation/platform/process_acceptance.py run --config '<prepare 输出的 config.private.json>'
```

配置格式见 `config.schema.json`。`prepare` 复制已构建 JAR 到新私有目录并冻结 SHA-256，生成六种受限 release 和独立报告路径；不会启动服务或提交 Run。每次验收必须使用新目录，失败证据不会被覆盖。身份 host 改址或需要新构建时应重新 prepare。

运行器检查真实双 worker、HTTP 幂等及 ETag、调用中的暂停恢复、SQL 配额与并发、审批写入、保留非零预算的 Java 进程重启、UNKNOWN 的真实本地断线及离线回执核对、MySQL 正常重启恢复。合成写工具先将副作用 fsync，再关闭真实 socket；fixture 不去重，任何重复发送都会增加副作用计数。核对回执由不同凭据读取，再用 JAR 的 `import-evidence` 离线命令导入已绑定的 UNKNOWN invocation；公开 API 不能自己制造可信回执。

运行器只终止自己 `Popen` 创建的 Java 子进程。Windows `terminate()` 属于突然终止，报告会明确记录。运行器从不终止 MySQL。MySQL 重启由独立控制者通过工作目录内 `mysql-restart` 握手执行：

1. 出现 `stop.request.json` 后，核对端口、库名与请求 ID，正常关闭专用 MySQL；写入 `stop.ack.json`，内容为 `requestId`、`phase: stopped`、`at`、旧 `mysqlPid`、`normalShutdown: true`、`exitCode: 0`。
2. 运行器证实两个 API health 503 与 SQL 不可用后写入 `start.request.json`；控制者启动相同专用数据目录，写入 `start.ack.json`，内容为相同 `requestId`、`phase: started`、`at`、新 `mysqlPid`。
3. 运行器验证原 Run、deadline、limits、usage 并继续完成 Run。等待握手有 15 分钟上限；缺少握手不会被认定为通过。

公开 JSON 报告只保存状态、哈希、PID、受限数据库投影及合成 fixture 证据。它证明的是本地 Java/MySQL/Auth 部署和进程恢复边界，不能替代真实远端模型质量测试、GitHub 写入测试或正式生产 OpsAgent 启动验收。

## 第三阶段目录与原业务入口验收

`catalog_acceptance.py` 使用已启动的本地平台和私有身份元数据，创建八类目录资源并验证不可变发布、冻结回放、CAS/幂等、默认版本切换、旧 Run 保持原版本、资源紧急撤销及跨主体/应用权限。每次运行使用独立资源前缀，并推进 `support-assistant` 和 `ops-knowledge-agent` 的默认版本。它执行一次真实模型调用，不应为重复查看报告而再次运行。

```powershell
python validation/platform/catalog_acceptance.py `
  --credentials-file .work/phase3/credentials.private.json `
  --identity-metadata .work/phase3/ops-metadata.private.json `
  --deployment-file .work/phase3/platform.private.json `
  --platform-jar harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar `
  --report docs/validation/20260918/phase3-catalog-acceptance.json
```

`ops_catalog_ingress_acceptance.py` 读取已通过的目录报告，把其 Ops Agent 不可变版本中的相同资源引用和冻结回归案例发布为 `ops-readonly`，与现有隔离宿主固定的业务 Agent ID 对齐。已存在相同的可用版本时复用；不会清库或重启身份服务。随后通过私有宿主配置的同端口热加载，将新业务请求归属切换为 `HARNESS`，验证真实 `/api/rag/harness-search` 创建目录 Run、两个受保护引用、同业务请求不重复执行和不同正文返回 409。**成功后保留 HARNESS 配置**，原 SQL 路由归属记录不变。每次执行产生一个新的只读检索 Run，模型调用为零。

```powershell
python validation/platform/ops_catalog_ingress_acceptance.py `
  --credentials-file .work/phase3/credentials.private.json `
  --identity-metadata .work/phase3/ops-metadata.private.json `
  --deployment-file .work/phase3/platform.private.json `
  --private-host-config .work/phase3/ops-host.private.json `
  --catalog-report docs/validation/20260918/phase3-catalog-acceptance.json `
  --platform-jar harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar `
  --report docs/validation/20260918/phase3-ops-ingress.json
```

两个脚本只接受本地平台。公开报告包含 releaseRef、Run ID、调用次数和摘要，不保存凭据、模型正文或知识片段。验证范围仍为专用 MySQL、合成账户/知识及实际桥接代码，不代表正式生产切流验收。

## 阶段三 UNKNOWN 页面验收

`console_unknown_acceptance.py` 复用 loopback `WireFixture`，只在本机 journal 写入合成 effect 后断开连接，不访问真实业务。它默认读取忽略的 `.work/phase3/`：`platform.private.json`、`ops-host.private.json`、`ops-metadata.private.json`（user10Token/user20Token）、`credentials.private.json`（consoleApplication）、`platformDb-client.private.ini` 和 `opsDb-client.private.ini`。先准备本页前述独立身份/项目环境；默认平台8099、fixture53966、支持项目support-pilot。

1. `python validation/platform/console_unknown_acceptance.py prepare` 生成独立工具/release、授权 SQL 与 staged 配置。检查生成的 `prepared.json`；仅把新增项合并到活动 manifest，不能用旧 staged 文件覆盖期间的新变更。授权 SQL 只给合成 console 用户10增加这一项工具权限，在专用 Ops 验收数据库执行，无需重启身份服务。
2. `python -u validation/platform/console_unknown_acceptance.py serve` 启动 fixture。Windows 后台使用 `Start-Process -WindowStyle Hidden`；确认 `/health` 正常后，仅重启平台载入新增能力。
3. `python validation/platform/console_unknown_acceptance.py create --approve` 显式创建并批准一次合成调用，等待 `NEEDS_ATTENTION`。省略 `--approve` 时可先在控制台审批。脚本发送前持久化幂等键与正文，已有尝试会拒绝新建，绝不自动重复未知写。
4. `python validation/platform/console_unknown_acceptance.py evidence --import-verified` 用独立 verifier 读取已 fsync 回执，核对 SQL 中精确 UNKNOWN 的工具、参数及 invocation 后，调用现有证据导入 CLI；不会提交对账命令。
5. 在控制台打开 `reconciliation-ready.json` 指定的 Run，核验摘要并提交其中的 evidenceRef；对账后显式恢复。`python validation/platform/console_unknown_acceptance.py inspect` 检查完成状态以及恰好1次dispatch/1次effect/1次工具调用。

所有私密生成物保留在 `.work/phase3/unknown-fixture/`。结束后创建该目录的 `fixture.stop` 可停止该脚本的服务；恢复后的 Run 不会重新执行工具。保留原 journal 与回执作为审计证据；对外报告只复制脱敏检查结果。
