# 第五阶段 Linux 部署、监控与灾备包

状态：本机工程产物；用户已确认暂无 Linux 预发布服务器。此包不启动后台观察、不开放公网、不发送真实群通知、不执行历史数据删除。Linux 实际部署、异机灾备、RPO/RTO、HTTPS 浏览器路径和至少 7 天观察分别等待环境验收，不能由配置检查替代。

## 1. 拓扑与边界

[部署包](../deploy/platform/phase5/)采用一个 API/BFF、两个 worker、现有 MySQL；BFF 重启后重新登录。`api.json` 的 `workerEnabled=false`，两个 worker 使用同一 `worker.json` 和共享限额。外部 MySQL 和身份系统继续沿用已批准设施，不在 Compose 内隐式新建生产数据库。不使用 Kubernetes、消息队列或 Redis。

`compose.yaml` 的业务入口只绑定宿主 `127.0.0.1:8443`，无 HTTP 明文入口。Nginx 使用已取得的 TLS 证书；API 与 worker 没有宿主端口。Nginx 容器固定在专用子网 `172.30.81.10`，Tomcat 仅信任这个代理 IP；Nginx 清空 `Forwarded`，重新设置 `X-Forwarded-*`，固定外部 host/port。代理拒绝 `/internal/` 和 `/health`。部署前确认该子网不冲突，不要把 `internal-proxies` 放宽成整个局域网。改公网监听和域名证书需要环境部署时落实。

`HARNESS_ENVIRONMENT=staging|production`、`HARNESS_SCHEMA_MODE=validate`、现有 `opsagent` 身份是运行契约。生产不得启用 `local-test`。运行账号仅 `SELECT/INSERT/UPDATE/DELETE`；schema 由独立迁移账号的 `migrate` 命令创建/升级。模型和签名密钥依然使用私有环境引用。

## 2. 构建与准备

从源码构建时，先完成控制台与仓库检查，将确定提交的服务 JAR 放为 `deploy/platform/phase5/platform.jar`，记录 JAR SHA-256。示例（Linux，完整源码仓库根目录，需要 JDK17、Maven3.9+、Node24与项目要求的pnpm）：

```sh
pnpm --dir platform-console install --frozen-lockfile
pnpm --dir platform-console test
pnpm --dir platform-console build
mvn verify -Dplatform.artifact.name=harness-platform-phase5
cp harness-platform-service/target/harness-platform-phase5-0.1.0-SNAPSHOT.jar deploy/platform/phase5/platform.jar
docker build -t harness-platform:COMMIT_SHA deploy/platform/phase5
docker image inspect harness-platform:COMMIT_SHA --format '{{.Id}}'
```

如果收到的是本轮交付 ZIP，包内已经有 `deploy/platform/phase5/platform.jar`；ZIP不含源码/Maven工程，不执行上面的源码构建命令。先核对ZIP的独立SHA-256和manifest中的JAR SHA-256，再在解压根目录执行以下命令，并用manifest的真实提交号替换 `COMMIT_SHA`：

```sh
sha256sum deploy/platform/phase5/platform.jar
docker build -t harness-platform:COMMIT_SHA deploy/platform/phase5
docker image inspect harness-platform:COMMIT_SHA --format '{{.Id}}'
```

`.dockerignore` 仅允许 Dockerfile 与 JAR 进入构建上下文，不会打包私有配置。应用制品部署使用确定提交的标签或仓库 digest；第三方组件版本已固定为版本标签，正式预发布应把实际拉取的 image digest 写入发布记录。尚未在此 Windows 主机拉取镜像，未虚构 digest 或声称镜像兼容性已经验证。

复制 `.env.example` 为 `.env`，填写实际应用镜像。包内 `deployment.example.json` 是完整部署模板，`governance.example.json` 是独立治理模板，不能把治理字段拼到Deployment JSON里。部署模板提供2个试点项目、全局10个执行槽、每项目5个槽；治理模板限制最多5个模型调用，并将正式日月预算留空。它们只是结构模板，不会在身份系统注册项目或授予权限。

先将部署模板复制到私有 `/etc/harness/deployment.json`，替换 `identity.example.invalid`、项目/应用身份、审批人、模型profile等实际配置，保留已有不可变release标识和内容（升级时不得改写旧发布）。再执行：

```sh
cd deploy/platform/phase5
python3 prepare.py --manifest /etc/harness/deployment.json --origin https://harness.example.internal:8443 --private ./private
cp governance.example.json private/config/governance.json
```

已有治理集群不能直接用示例覆盖治理配置，需保留当前已核实参数并按后述指纹CAS迁移。`prepare.py --origin` 的端口须与 `.env` 的 `HARNESS_HTTPS_PORT` 相同；域名必须解析到实际入口。默认入口仍只监听宿主loopback，外部浏览器访问使用受控HTTPS入口/隧道，不能因此改成HTTP或去掉Origin校验。

`prepare.py` 只生成 API/worker 两份配置和静态 Nginx 配置；不创建用户、密钥或网络连接。准备下列私有文件，限制 Linux 文件权限：

| 文件 | 内容 |
|---|---|
| `private/runtime.env` | 参考 `runtime.env.example`；运行 SQL 账号、签名、应用凭据、模型 key、指标 token、归档加密 key |
| `private/migration.env` | 同一确定配置与所需环境引用；使用 `HARNESS_MIGRATION_JDBC_URL/USER/PASSWORD` 注入迁移账号（兼容同进程 `HARNESS_JDBC_*`），不分发给 API/worker |
| `private/config/governance.json` | 本轮治理参数；`HARNESS_GOVERNANCE_CONFIG=/run/harness/governance.json`，三实例必须完全一致 |
| `private/tls/fullchain.pem`、`privkey.pem` | 对应外部域名的完整 TLS 链及私钥 |
| `private/metrics-token` | 与 `HARNESS_METRICS_TOKEN` 完全相同的 32+ 字符随机值；Prometheus 只读 |
| `private/grafana-password` | 单独随机 Grafana 管理员密码 |
| `private/alert-evidence/` | 本地模拟告警记录目录，只存事件状态和有限标签 |
| `HARNESS_BACKUP_METRICS_DIRECTORY` 指定宿主目录 | 与备份JSON的 `metricsDirectory` 一致，默认示例为 `/var/lib/harness/backup-metrics`；供textfile exporter只读 |

私有配置及证书归属宿主管理员；容器运行用户 UID 10001 需要读取 `private/config/`，告警测试 sink UID 10001 需要写 `private/alert-evidence/`。Prometheus/Grafana 镜像用户需要读取相应独立 secret 文件，不要因此把整个 `private/` 设为全员可读。实际 UID 和 bind mount 权限在 Linux 预发布时验证。数据库 JDBC 必须验证 CA/hostname，运行账号强制 SSL；JDK 若需要内部 CA，导入专用 truststore 并挂载只读，不能关闭验证。

`prepare.py` 默认创建0700目录和0600文件，宿主root生成时必须再赋予容器最小读取权限，例如把config目录/文件分组为10001并设为0750/0640；告警目录归10001并保持0700。Prometheus默认UID/GID65534、Grafana默认UID472的secret读取权限分别核对。此步骤不能只检查文件存在，否则容器会因Permission denied启动失败。外部数据库、身份与模型地址要从容器网络可达；容器里的127.0.0.1不指向宿主服务。

## 3. 数据库权限与独立迁移

`db_accounts.py` 从权限为 `0600` 的私有 JSON 读密码，通过 stdin 把 SQL 发送到 `mysql`；密码不会进入命令行、stdout 或失败异常。账号已存在时拒绝自动覆盖，按已有账号授权另行核对。

```json
{
  "database": "harness_platform",
  "accountHost": "192.0.2.10",
  "runtime": {"user": "harness_runtime", "password": "32_OR_MORE_RANDOM_BASE64URL_CHARACTERS"},
  "migration": {"user": "harness_migration", "password": "ANOTHER_32_OR_MORE_RANDOM_BASE64URL_VALUE"},
  "backup": {"user": "harness_backup", "password": "ANOTHER_DISTINCT_RANDOM_BASE64URL_VALUE"}
}
```

`192.0.2.10` 是文档占位地址，必须改为数据库实际看到的部署来源IP。连接外部MySQL时Docker通常经宿主NAT，不能照搬容器子网 `172.30.%` 作为数据库用户Host。所有示例密码必须替换；`db_accounts.py` 会拒绝上述公开占位密码。首次部署的MySQL option file应使用 `[client]`、`protocol=TCP`、实际host/port/user/password，并设置 `ssl-mode=VERIFY_IDENTITY` 与可信 `ssl-ca`，权限0600且只给对应运维用户读取。

```sh
python3 db_accounts.py --private-config /etc/harness/accounts.private.json --admin-defaults /etc/harness/admin.mysql.cnf
docker compose config --quiet
docker compose --profile migration run --rm migrate
docker compose up -d api worker1 worker2 ingress
```

上述无断言的迁移命令仅适用于全新空库。对已有 harness 库，必须先实际停止或隔离全部旧 API/BFF 和 worker，排空非终态任务、未决调用及占用槽，核对其他主机/服务管理器不会自动重启旧实例，然后显式运行：

```sh
HARNESS_MIGRATION_OFFLINE_ACK=drained-isolated-cluster docker compose --profile migration run --rm migrate
```

Compose 默认不填写此断言，不能通过复制配置就假装已经停机。该值是操作人确认，不能证明停机；CLI 仍会在任何 DDL/配置写入前检查已有 Run、槽位、permit、近期 worker 及治理指纹。配置变更分支在写治理指纹前再次核验 CAS/排空条件；同指纹 schema 升级依赖前置检查及操作人持续隔离集群。第四阶段旧进程不具备第五阶段心跳，尤其需要操作人在数据库之外完成隔离。

账号最小分工：runtime 仅 DML；migration 增加 CREATE/ALTER/INDEX/REFERENCES，不授予 DROP 或全局权限；backup 在本库读取，并因一致性坐标、轮换与远程 binlog 读取需要全局 RELOAD/REPLICATION CLIENT/REPLICATION SLAVE。备份账号具有敏感读取能力，独立保存且不提供给业务进程。不要并行运行迁移和未知版本 worker；先排空或冻结准入，固定旧 Run 的兼容执行版本，失败时保留原制品及数据。

`migrate` 是独立进程，不创建业务 Runtime、外部连接器或 HTTP listener。治理参数变更时须在 `private/migration.env` 提供旧值 `HARNESS_EXPECTED_GOVERNANCE_FINGERPRINT` 作 CAS，并确认不存在活跃 Run、permit 或 worker；不能通过启动一个使用新配额的 worker 偷改其他实例共享约束。错误断言、旧指纹不符或尚未排空应在 DDL/种子写入前拒绝；不把失败的迁移当作可回退的整库事务，MySQL DDL 仍然有隐式提交边界。

升级门槛：先用隔离恢复库执行新版本迁移与旧快照读取，确认兼容矩阵；再停止新准入、排空旧 worker、备份、迁移、启动验证、恢复准入。运行模式的 schema 不匹配必须拒绝启动。无兼容证明时禁止仅替换旧 JAR 回退新 schema；使用确定版本和隔离恢复/对账方案。历史请求幂等、UNKNOWN 和人工等待不能在升级时清除。Compose 的 `stop_grace_period=120s` 只是退出窗口，不证明远端调用已停止。

## 4. 监控、告警与接收证据

```sh
docker compose --profile monitoring up -d prometheus alertmanager alert-test-sink node-exporter grafana
```

Prometheus 每 15 秒用独立 Bearer token 抓取三实例 `/internal/metrics`。Grafana 面板使用 `max by (...)` 聚合共享数据库指标，避免三实例重复计数。API/DB 不可用、UNKNOWN、长期排队、治理告警、备份/日志/异机副本缺证据分别有规则。Alertmanager 按告警/项目/实例分组去重，并发送恢复事件；默认仅发送 Docker 内的测试接收端。宿主 Grafana/Prometheus/Alertmanager 管理端口均只绑定 127.0.0.1，远程查看先走受控隧道。

API P95 面板按固定路由组对进程内 histogram 使用 `rate`，包含实际授权与同步 HTTP 处理，不包含异步模型生成时长。模型/工具均值面板来自共享 SQL 最近 900 秒的已完成 trace，用 `max by(project,operation)` 避免多实例重复统计；该窗口值是 gauge，不能使用 `rate`。进程重启使 HTTP counter 归零，未完成或崩溃前未记录的调用不会被虚构成耗时样本。

`alert_sink.py` 接收 `/alerts` 的 HTTP 通知，只写接收时间、firing/resolved 和 allowlist 标签；不保存 annotations、prompt、正文或密钥。本机 fixture 已能真实发送 firing/resolved 并断言收到，**这不是实际 Prometheus→Alertmanager 规则触发链验收**。预发布必须分别制造一次规则触发和恢复，记录从异常发生到规则、接收端、值守确认的时间。切换真实通知接收人/渠道在配置明确后执行，当前包不发送真实群消息。

## 5. 加密备份、二次副本与保留

Linux 宿主前提：Python 3.11+、MySQL 8.4 系列 `mysql/mysqldump/mysqlbinlog`、age 1.x、OpenSSH、systemd、私有 tmpfs。`mysql.production.cnf` 是与现有 MySQL 合并的目标配置，不自动覆盖服务器配置；ROW binlog、`sync_binlog=1`、`innodb_flush_log_at_trx_commit=1`、TLS 均需实测确认。

binlog 包含 MySQL 整实例数据，而非只含指定库。因此日志归档要求独立平台实例，并由运维将 `dedicatedInstanceConfirmed` 设为 true；共享了 OpsAgent 等其他系统的实例不在此默认备份范围。不要为方便恢复默默把其他系统日志纳入此包。

复制 `backup.example.json` 到 `/etc/harness/backup.json`，填写私有 MySQL option file、age **公钥接收人** 文件、密文目录、tmpfs、私有恢复状态、标量指标目录和独立备份主机。age 解密私钥应保存在独立受控位置，不放在业务容器或备份运行用户可写目录。SSH 必须预置并核实known_hosts；通过 `sshIdentity`、`sshKnownHosts` 指向 `/etc/harness/` 下的专用文件，备份用户只读。脚本使用BatchMode、IdentitiesOnly与StrictHostKeyChecking，不依赖被systemd `ProtectHome` 屏蔽的 `~/.ssh`，也不自动接受新主机密钥。暂未有独立主机时保持 `confirmedIndependentHost=false`，异机指标显示缺证据。远端 `/srv/harness-backups` 需预先存在、只给备份账号写，远端需Python3.11+用于流式SHA-256核验。

```sh
python3 backup.py full --config /etc/harness/backup.json
python3 backup.py binlog --config /etc/harness/backup.json
python3 backup.py offsite --config /etc/harness/backup.json
python3 backup.py retention-preview --config /etc/harness/backup.json
```

完整备份使用 `--single-transaction --source-data=2` 捕获一致性快照及日志坐标，禁止在备份窗口执行 DDL。数据库 dump 与私有状态先放在 `/dev/shm` 下 0700 tmpfs，再整体打包并经 age 认证加密；目的盘只落密文与不含正文的回执。主机需禁用 swap 或使用受控加密 swap，否则 tmpfs 本身不保证不落明文盘。文件 SHA-256 用于传输校验，不替代 age 的认证解密。失败时不推进成功时间戳。

私有状态目录必须包含确定版本的部署配置、签名密钥各有效代、`HARNESS_RETENTION_KEY`（独立的 32-byte AES 密钥）、应用凭据引用、身份委托恢复材料及运维清单。密钥丢失时对应 SQL 归档无法解密，禁止把新 key 当作旧 key 使用。采用不可变版本的状态快照；密钥/委托轮换与完整备份协调，不能一边覆盖文件一边备份。远程身份提供方本身不由本库 dump 恢复，其可用性和撤权状态必须另核验。

日志归档先关闭当前 binlog，仅读取已闭合文件；已归档密文需 checksum 一致才算存在。异机同步复制密文并在远端 Python 校验 SHA-256，后续轮次重新核对已有远端文件；有任意链文件失败不推进副本成功指标。副本时间戳采用日志归档时间，不用“重试成功时刻”掩盖陈旧数据。服务端丢失/purge日志、archive gap 和 stale 指标必须告警，不能自动放宽 RPO。

`systemd/` 提供每日完整备份、每 5 分钟闭合日志/二次副本、每周保留预览的 service/timer。先建立 `harness-backup` 系统用户、授权目录、tmpfs 和锁文件，再手工跑通后显式安装/启用；本轮未安装或启用这些后台任务。共用 `flock` 防止任务重叠，锁冲突使该次失败并留待下一轮；全量备份时间过长导致日志陈旧会告警，需根据实测拆分快照/归档窗口，不能声称固定配置天然满足15分钟。

service固定从 `/opt/harness-deploy/backup.py` 启动，安装时需将包内备份脚本复制到该目录，并创建service列出的 `/var/backups/harness`、`/var/lib/harness`、`/dev/shm/harness-backup`，赋予备份用户权限。指标目录只包含可公开给监控的标量时间戳，可0755/0644；其他密文和私有文件继续0700/0600。调整备份JSON的路径后，同时调整systemd `ReadWritePaths` 和Compose的 `HARNESS_BACKUP_METRICS_DIRECTORY`，避免写入被沙箱阻断或监控读取另一个空目录。

保留默认30天且只有 `retention-preview`，脚本没有删除动作。候选列表不等于可删除：必须保留覆盖最早恢复点的前置全量以及连续日志链；异机保留也要独立审核。任务 payload、诊断与审计数据的保留由平台治理流程管理，与备份文件保留分开。

## 6. 隔离恢复和时间点恢复

`recovery.compose.yaml` 单独创建无 API/worker 的 MySQL 实例，Docker network 为 internal，端口仅映射 `127.0.0.1:13306`，事件调度器和 binlog 关闭。准备独立恢复 root password 文件并显式启动；生产原库不挂载到此实例。`restore.py` 拒绝原 server UUID、非 loopback/13306 目标、已存在的同名目标库、启用事件/binlog的恢复库，以及缺失/混源/断序日志；不执行覆盖或 DROP。

恢复用option file必须是独立的 `[client] host=127.0.0.1, port=13306, protocol=TCP, user=root` 与该恢复实例密码；不要复用生产defaults。恢复实例显式允许其隔离网络内的root连接以支持Docker端口映射，只在loopback公开13306，不能沿用为生产数据库配置。

```sh
docker compose -f recovery.compose.yaml up -d
python3 restore.py --full /restore/full.tar.gz.age --binlog /restore/logs-1.tar.gz.age --binlog /restore/logs-2.tar.gz.age --identity /secure/recovery.agekey --stop-at 2026-09-20T12:00:00Z --report /restore/plan.json
python3 restore.py --full /restore/full.tar.gz.age --binlog /restore/logs-1.tar.gz.age --binlog /restore/logs-2.tar.gz.age --identity /secure/recovery.agekey --stop-at 2026-09-20T12:00:00Z --report /restore/applied.json --target-defaults /secure/recovery.mysql.cnf --apply-isolated --isolation-ack workers-absent-network-isolated
```

前一命令只解密、校验和生成恢复计划，后一命令才恢复。恢复时间为 **UTC、排他上界、秒精度**，应选在确定事务边界之后并对照业务记录；时区与同秒多事务必须单独验证。脚本将完整快照坐标作为第一个 binlog 的起点，在同一个 mysqlbinlog 进程里读取连续文件，并过滤本业务库。只能使用 ROW 日志；平台不得跨数据库写事务。时间点超过已归档覆盖、早于完整快照完成时刻均拒绝。

导出的报告区分数据库过程秒数与完整 RTO；即使 SQL 恢复成功也保持 `rpoVerified=false`、`rtoVerified=false`。数据库恢复后仍没有应用或 worker，私有状态只在 tmpfs校验、不自动安装。随后必须：

1. 对照灾难时点独立 canary/业务流水，核实实际丢失窗口、schema、表数、行内容和发布/审批/幂等引用；恢复到历史时点时不能直接与当前源库总行数要求相等。
2. 对每项在途/UNKNOWN/已完成但尚未落库的模型、工具和业务写操作，按供应商回执/外部系统记录对账。恢复数据库不能撤销真实已发生副作用，禁止以“快照里没有”判定未执行。
3. 校验身份提供方当前授权、撤权、委托与 key 版本。过期授权不能随备份复活；保留旧签名验证能力与更换生产密钥是两项独立动作。
4. 先启动只有查询入口的兼容运行版本，完成输出授权验证；确认人工请求和审批后再决定启用哪一代 worker。所有临时恢复环境在验证完成前禁止连外部工具/模型地址。
5. 从发现/宣布故障、恢复、核验、外部效果处置到约定服务安全恢复完整计时。RPO≤15分钟、RTO≤60分钟必须在独立主机/真实数据量/真实权限路径演练后签收。

## 7. 本机检查与待环境项

```sh
python validation/platform/phase5_deployment_validate.py --compose --jar harness-platform-service/target/harness-platform-phase5-0.1.0-SNAPSHOT.jar --report .work/phase5/deployment-validation.json
```

校验包括私有状态打包的模拟加密调用、失败不生成成功回执、binlog坐标/连续性/混源/覆盖拒绝、恢复目标保护、路径穿越/符号链接拒绝、保留不删除、最小权限、受信反代生成、真实本机 HTTP 告警触发/恢复记录，以及 Docker Compose 两个文件的静态解析。模拟 age 调用不等于真实加密恢复演练。

提供 `--jar` 时，还会用JDK17+直接调用该制品的 `Deployment.load`、每个Release的 `validateRelease` 和 `ExecutionGovernanceConfig.load`，实读包内两个JSON示例；报告记录实际JAR SHA-256。该检查不创建Spring运行时、不解析密钥引用、不连接数据库/身份/模型服务，CI在Linux与Windows源码构建后都运行。省略 `--jar` 只能完成脚本fixture检查，报告会明确Java契约未执行。

本节验证脚本属于完整源码仓库；交付ZIP排除了测试脚本与报告，ZIP接收者应从对应Git提交取得源码后运行，不能在只有部署包的目录直接执行此命令。

待 Linux 环境才能验证：镜像启动和用户权限、CA/TLS/Origin/CSRF浏览器链、MySQL最小权限运行/迁移、真实 age 加解密+binlog时间点恢复、Prometheus→Alertmanager 触发/恢复链、异机SSH校验、RPO/RTO和7天持续观察。Windows 本机发现 Docker CLI，但 Docker Linux daemon 未运行，本轮未启动 Docker Desktop 或安装系统软件。

## 8. 交付 ZIP 与制品来源

提交并确认工作树干净后，用已通过验证的 JAR 及其 SHA-256 生成可交付包：

```sh
python validation/platform/phase5_bundle.py --jar harness-platform-service/target/harness-platform-phase5-0.1.0-SNAPSHOT.jar --jar-sha256 VERIFIED_JAR_SHA256
```

脚本只允许 `develop/` 干净提交；从确定的 Git 对象读取已跟踪部署白名单和完整公开文档，排除 `docs/validation/`、私有配置、证书和测试报告。部署目录出现未审查的已跟踪文件、链接或疑似秘密文件时拒绝打包；JAR必须与调用者提供的验证哈希一致，并含第五阶段关键服务类和控制台，旧第四阶段JAR会被拒绝。工作树未提交时不提供强制发布开关。

Git提交号绑定的是包内部署源文件/文档，JAR来源依赖交付者提交的已验证哈希；当前JAR没有内嵌Git构建证明，脚本不把“同时记录提交和哈希”伪称为已独立证明二者同源。交付者应保存实际构建/测试记录并核对JAR哈希，正式制品供应链证明后续再接入CI签名。

输出位于 `.work/phase5/dist/harness-phase5-COMMIT.zip`；包内 JAR 固定为 `deploy/platform/phase5/platform.jar`，`bundle-manifest.json` 记录提交、分支、JAR 与每个载荷文件的 SHA-256，写入后逐文件读回校验，ZIP 另有 `.sha256` 文件。manifest 明确写明 `local engineering; Linux pending`、24小时观察未完成、Linux7天观察待环境，不赋予生产验收标签。接收者仍须单独准备私有配置和受控密钥。
