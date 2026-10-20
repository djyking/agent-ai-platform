# 阶段六交付组件

中台 API、worker、MySQL、TLS 入口、监控和备份沿用 `../phase5/` 中的部署合同。阶段六包另含独立客服 BFF 和匹配的 OpsAgent 适配服务。既有 VPS 的同域 `/console/`、`/v1/`、`/support/` 组合见 [AI 最小部署候选](ai-minimal.md)；用户已授权本轮公网部署，但候选生成不表示已经安装或通过目标环境验收。

内部服务证书、MySQL 主机名验证证书及保留 JRE 公共根的信任库，见 [内部 TLS 材料准备](internal-tls.md)。CA 签名私钥不挂载到任何服务。

1. 核对包内 `bundle-manifest.json` 的两个仓库提交和每个文件 SHA-256。准备私有配置、现有 Ops 身份源、TLS、数据库及应用授权。样例不是可直接公开的配置。
2. 停止旧 API/worker，确认队列已排空、外部结果已核对；保持 UNKNOWN 隔离。以迁移账号运行当前 platform.jar 的 `migrate`。配置变更须提供已核对的旧 governance fingerprint；运行服务仅使用 DML 账号。
3. 按 `opsagent/docs/harness-phase6.md` 顺序显式迁移 Auth/Rag 表，部署包内匹配的 Auth、Knowledge、Ticket、Rag、Agent、Platform、Gateway JAR 及 Ops 前端，统一公共权限依赖版本。保留 Ops 原微服务架构，继续使用其现有基础设施与服务配置。
4. 启动当前版本中台 worker/API，以 `/health/ready` 验证。应用接入通过申请、审批、离线 `onboarding-apply`、配置迁移、运行配置核验完成。审批本身不会发放凭据。
5. 宿主部署时将 `support.jar` 安装到 `/opt/harness/support/`，私有环境文件安装到 `/etc/harness/support.env`（0600），创建专用非 root 用户，再安装 `support.service`。BFF 默认只监听本机回环，同机 nginx 使用 `support.nginx.example.conf` 提供 HTTPS。容器部署可显式设置 `SUPPORT_BIND_ADDRESS=0.0.0.0`，只连接私有 Docker 网络且不发布 BFF 宿主端口；必须保持 `opsagent` 身份模式与明确的 HTTPS `SUPPORT_PUBLIC_ORIGIN`，由受信入口反代。
6. 使用现有身份、实际项目授权和已发布 Agent 验证两套前端。客服目前接授权知识问答及人工审核，不含真实订单、退款或客户消息系统。

升级前须保留原二进制、原配置与可恢复备份。Stage 6 的 Studio/Onboarding 索引 schema 2 不允许旧 schema 1 进程接管；不能把“替换旧 JAR”当作数据库降级。回滚选择经兼容验证的当前 schema 构建，或在隔离实例恢复升级前数据并核对期间所有外部副作用后再切换。任何分支都不得自动重放 UNKNOWN。

ACP 提供固定版本 ACP 0.2.0 的认证文本/JSON 兼容接口，见 `docs/phase6-acp.md`。它不代表 A2A、ACP 全量会话或多媒体兼容。

本轮公网部署须在目标 Linux 机器完成备份与回滚准备、TLS/代理、真实身份、AI/RAG/中台联调及容量验收。Linux 七天观察、真实异机恢复和生产 RPO/RTO 是独立验收项，未实际执行时保留待完成状态，不能从公网可用推断通过。本机报告的时间和范围必须按实记录，不把短时负载折算成连续观察。

`backup-tools.Dockerfile` 记录本轮 Linux sidecar 使用的 MySQL、mysqlbinlog、age 和 Python 版本。构建上下文需自行从 MySQL 官方仓库及 `https://github.com/FiloSottile/age/releases/tag/v1.3.2` 下载指定文件；文件本身不进入源码或交付包。Dockerfile 核对固定 SHA-256 并验证 MySQL RPM 签名。本机 Windows bind mount 不保证 Unix 文件权限，恢复验收脚本在 sidecar 私有 tmpfs 中以 0600 复制 MySQL option file；不放宽 mysql 对可写凭据文件的拒绝规则。
