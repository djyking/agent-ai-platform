# Harness 阶段六部署与验收状态

截至 **2026-10-21**，公网部署已完成，Ops、Harness 控制台与客服三个入口均已实际登录；客服生成后审核交付、最后 Agent 上的 Rabbit 独立演练与最终资源采样也已取得通过或有效观测证据。运维控制绑定最终 Compose，28 个当前服务运行。主 Rabbit 死信队列新增 6 条已关联到缺少有效值班收件人的 SLA 提醒；该配置缺口单独保留，不等于审计写库故障。当前功能验收不代表长期生产可靠性或全部历史任务成功。

用户已取消真实 24 小时观察作为部署前置条件，原定时任务“完成Harness阶段六连续观察与交付”已 **PAUSED**。第四次观察实际结论为 **`USER_STOPPED_EARLY` / `WAIVED_NOT_PASSED`**，不得写成 `PASSED`。七天目标观察、真实异机恢复及 RPO/RTO 未完成。

部署前完整记录保存在 [历史快照](pre-vps-history-20261021.md)。以下仅记录当前状态；每项结果限于对应时间、制品和检查范围。结构化公开摘要见 [local-evidence.json](local-evidence.json)，其中列明真实目标验收与历史版本的范围。

## 当前部署与真实验收

| 范围 | 已核实事实与边界 |
| --- | --- |
| 公网入口与客服闭环 | [Ops](https://opsagent.cloud/)、[Harness 控制台](https://opsagent.cloud/console/)、[客服](https://opsagent.cloud/support/) 实际官方验证码登录通过。客服操作员创建、审核员审核、操作员重新登录读取交付完成；审核前后答案摘要一致，只有一次模型调用，完成任务的暂停/继续/取消禁用。本次空业务知识场景明确转人工，没有发送外部客户消息。HTTP/TLS 独立记录另验证 TLS 1.3、主机名和已测公网否定路径 |
| 运行组合 | 28 个选定服务，保留 Nacos discovery、ES/Qdrant、OTel/Tempo 和所选 Ops JVM 观测；`reranker`、`operations-lab` 两个退休容器继续停止。当前服务使用 `unless-stopped`；退休容器保持 `restart=no`。不能将 30 个存在的容器全部描述为运行服务 |
| Ops 知识 | 实际验收文档与工单附件链路已有记录；一致性检查为 29 篇已发布文档、62 个已发布分块，ES/Qdrant 缺失与孤儿项均为 0。维护手册实际查询命中 `HYBRID_RRF`。合法草稿预索引与已删除文档的历史失败保留，不将一致性检查当作向量质量评估 |
| RAG/HARNESS | 文档请求和含真实附件的工单请求分别完成 Harness Run，绑定正式发布并记录实际模型调用/计费；旧 LEGACY 答案可原样回读。主会话 SSE 仍归属 `LEGACY_CONVERSATION`，不能称全部会话执行已迁移。无附件工单返回 `no_evidence` 且不调用模型 |
| 权限隔离 | 跨工单文档被拒绝；真实访客访问私有文档、私有工单、他人 Harness 答案与 Agent Run 共四项被拒绝。部分拒绝以 HTTP 200 + 业务码 `40300` 表示。被拒绝请求保留原 `UNKNOWN` 路由且未重放，不将拒绝路径写成执行完成 |
| Nacos 演练 | 配置漂移演练 Run `COMPLETED`、工单 `RESOLVED`、告警已解决，恢复归属 `AGENT_TOOL`；业务 HTTP 200，记录 13 次连续成功 |
| Sentinel 演练 | 后续独立演练经审批由 Agent 工具恢复，HTTP 429 → 200，记录 25 次连续成功，工单及告警关闭；未使用人工或 TTL 恢复冒充 Agent 成功。更早一次诊断参数失败仍保留 |
| Rabbit 演练 | 最后 Agent 镜像上的新演练 `ACCEPTED`：Run `COMPLETED`、工单 `RESOLVED`，经审批调用 `demo_queue_restore`，恢复来源 `AGENT_TOOL`；HTTP 503 → 200，记录 34 次连续成功，独立 Redis 读取到交付回执，DemoRabbit ready=0、consumer=1；未使用人工或 TTL 恢复。前次字段超限失败及人工恢复继续保留，不由本次成功覆盖 |
| 最后 Agent 制品 | `diagnostic-feedback-build-report.json`：1076 tests，零失败/错误、2 项原有条件跳过，构建输入未变化；已部署 JAR 与不可变镜像核验一致。精确错误反馈按冻结 schema 给出字段/长度限制，纠正最多两次，不提高字段上限、不重放 UNKNOWN、不改变审批语义 |
| 运维与观测 | 最终 Agent 更新后已精确重绑控制文件；仅 Agent 镜像/容器改变，其余 27 个当前服务与 2 个退休容器保持原绑定。新备份/续证定时器和只读主机 observer 已启用，旧定时器及配置执行器保持停用；当次 Prometheus 26/26 目标 up |
| 备份与证书 | 03:11 UTC 实际备份覆盖七个数据库、知识附件、配置和证书，文件摘要及库名标记独立复核一致；实际续期检查及一次 ACME dry-run 成功，证书未变化、Nginx 未重载。没有执行恢复演练或保留期删除；跨存储不是原子快照，该次备份属于前一 Agent 镜像，最终控制重绑没有重复备份 |
| Crypto 停用 | 3 个定时器停用并禁用，12 个专属服务 `Restart=no`，6 个当时运行服务停止；两个原失败服务未启动。独立复核无业务残余进程，SSH、代码、配置、数据保留，迁移清单已保存。尚未进行完整 Crypto 数据备份或迁移 |
| 最终资源与队列 | 03:37 UTC 的两个样本相隔 20.114 秒：28 个实例的镜像、容器 ID 和启动时间均匹配最终重绑且可比，18 个 Docker healthcheck 为 healthy，10 个未配置该检查。28 个容器 memory.events 与容器/主机 PSI 增量为 0；MySQL 连接 13/50（含采样连接）。DemoRabbit ready=0、unacked=0、consumer=1；主 Rabbit `ops.platform.audit.dlq` 为 78，较此前 72 新增 6；新增项对应缺少有效排班的 SLA 提醒，未重放或删除 |

最终 Compose SHA-256：`cda33cd6eb819a48b3203193e8ed63005baa3408027e578309c1781b325487c2`。最后 Agent JAR：`72bc0004811b04bb2754a9ffdeaafddc2194c04dde147308b27f95415dd51236`；镜像：`sha256:9d06f119a554520694b88786f027cdf56912b9155bd0ace7258a5ff5a34370b0`。后续变更必须重新核对这些绑定，不能沿用旧制品结论。

Crypto 停止前后约 31 秒，可用物理内存增加 **688.25 MiB**。专属 cgroup 约 **1.98 GiB** 计费消失包含文件缓存，不能等同为同样大小的物理释放。最终部署资源样本的 `MemAvailable` 为 **4690.3 → 4724.3 MiB**，扣除 **2 GiB 规划预留**后为 **2676.3 MiB**，主机未配置 swap。API/worker/support 的 Java 堆已用量分别约 **28.5/35.4/10.9 MiB**，不代表进程 RSS。短窗口内没有新增 memory.events/PSI，不证明代表性峰值容量或长期稳定性；MySQL `pgmajfault` 另增 3，不能概括为所有内存计数均无变化。

## 连续观察、历史失败与未完成项

第四次观察保留 438 个完整分钟窗口（约 7 小时 18 分钟）；26,328 条已发送写请求只读核对为唯一且 `COMPLETED`，无重放。原报告保留停止前 `RUNNING` 字段，实际停止事实由独立中断审计记录。取消部署前置要求不是达成 24 小时门槛，验收状态持续为 **`WAIVED_NOT_PASSED`**。

前三次观察失败、较早 Ops 与客服模型 UNKNOWN、首次 Sentinel 和 Rabbit 诊断失败均保留。后续独立成功不覆盖旧失败、不释放未知结果，也不证明旧调用费用已核清。

独立后续事项：

1. SLA 通知需要为相关服务配置真实值班收件人和有效班次；现有失败记录保留，不擅自分派或重发。每个 SLA 生命周期最多六类唯一事件，单条最多三次消费尝试后进入死信，不自动回放；未处理工单和未来新周期仍可能产生新事件。页面提示无有效排班，但尚无逐条死信投递详情。
2. 七天观察、真实异机恢复、备份恢复演练及 RPO ≤15 分钟 / RTO ≤60 分钟仍需独立验收。
3. 每次交付在 `develop/harness-phase6` 核对两仓精确提交的 GitHub Actions；旧版本绿色不能替代当前提交结果。

## 证据范围

私有原始证据保留在 `.work/phase6/`，不随 Git 公开；此页仅列报告名、计数、摘要和边界，不包含账号、凭据、会话令牌或模型正文。

- 入口与业务：`public-https-routes-20261021.private.json`、`target-acceptance-20261021/readonly-20261021T020855Z/report.strict-recheck.json`、`target-acceptance-20261021/ops-business/acceptance-summary.json`。
- 浏览器与最后 Rabbit：`target-acceptance-20261021/browser-public-acceptance.private.json`、`target-acceptance-20261021/ops-business/drill5-outcome.json`。
- 最后 Agent：`diagnostic-feedback-build-report.json`、`production-control-rebind-20261021/rebind-result.private.json`；后者 SHA-256 为 `3104b49e22198afdb3e0e56b053a25e0e2df82a668da6b64b174e7d49262e817`。
- 运维：`production-ops-final-20261021.private.json`、`production-ops-backup-verified-20261021T031414Z.private.json`、`production-ops-cert-dry-run-verified.private.json`。早于最终重绑的记录不替代重绑结果。
- Crypto：`crypto-migration-stop-20261021/migration-stop-summary.json`。
- 最终资源：`resources-post-crypto-final-20261021T033727Z.private.json`，SHA-256 `e3b975f2196a7e4e85a3b920e26fda97e4326edeb66dbe1553388e7c71ce65ca`。
- 观察：`observation-fourth-user-waiver-reference.json`、`observation-user-stop-20261021T005041Z/interruption-review.json`。

部署前的中台 399 项、客服 29 项 JS 及较早 Ops 1071/1075 项测试均按各自版本保留；不能替代最后 Agent 的 1076 项构建，也不能拿本机结果冒充目标环境业务验收。详细历史见 [历史快照](pre-vps-history-20261021.md)。
