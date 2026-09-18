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
