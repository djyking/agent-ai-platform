# 第六阶段双仓库工程包

`validation/platform/phase6_bundle.py` 打包中台、独立客服 BFF、OpsAgent Auth/Knowledge/Ticket/RAG/Agent/Platform/Gateway 和 Ops 前端。它只接受中台仓库与 `.work/opsagent-phase6` 隔离 worktree 的两个干净、已提交 `develop/` 分支。不会替用户提交，也不读取原 OpsAgent 工作目录中的未提交修改。

包包含：

- 两个仓库的完整提交 SHA、分支和打包时 clean 状态。
- 含 ACP、Onboarding、Studio/Onboarding schema 2 和控制台的中台 JAR，以及独立客服 JAR。
- 七个 OpsAgent JAR、完整 `ops-web/dist` 和本轮七个显式身份/问答 schema、升级、恢复及保留 SQL。即使应用自身源码没有变化，也统一核对本轮构建的公共依赖，避免旧镜像混用旧权限代码。
- 第五阶段部署/备份/监控文件和第六阶段客服部署、恢复工具镜像构建文件及公开文档；包含既有 Ops 主机的最小组合生成器、同域入口和内部 TLS 生成器。包内是无秘密的工具与模板，不包含生成的生产配置或证书。
- 每个文件的 SHA-256、字节数，以及外部 ZIP 校验文件。

包不含 `.work`、私密配置、密钥、令牌、会话、测试报告、数据库备份、下载的安装包或历史业务数据。Ops 基础库建库及旧环境历史迁移仍按匹配提交的原部署规程执行；包内新增 SQL 不代替原服务完整安装过程。

先完成前后端构建和相应测试，核对实际使用的二进制，再计算每个 JAR 的 SHA-256。以下命令只读取已构建前端并输出其规范化清单摘要，不打包：

```text
python validation/platform/phase6_bundle.py --ops-repository .work/opsagent-phase6 --inspect-frontend
```

在本机忽略目录保存 artifact 清单。`path` 可以是对应仓库根目录的相对路径；以下 SHA 均是待填占位符，必须换成调用者已核验值：

```json
{
  "platform": {"path": "harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar", "sha256": "<平台已验SHA256>"},
  "support": {"path": "support-pilot/target/support-pilot-0.1.0-SNAPSHOT.jar", "sha256": "<客服已验SHA256>"},
  "ops-auth": {"path": "ops-auth-service/target/ops-auth-service-1.0.0-SNAPSHOT.jar", "sha256": "<Auth已验SHA256>"},
  "ops-knowledge": {"path": "ops-knowledge-service/target/ops-knowledge-service-1.0.0-SNAPSHOT.jar", "sha256": "<Knowledge已验SHA256>"},
  "ops-ticket": {"path": "ops-ticket-service/target/ops-ticket-service-1.0.0-SNAPSHOT.jar", "sha256": "<Ticket已验SHA256>"},
  "ops-rag": {"path": "ops-rag-service/target/ops-rag-service-1.0.0-SNAPSHOT.jar", "sha256": "<Rag已验SHA256>"},
  "ops-agent": {"path": "ops-agent-service/target/ops-agent-service-1.0.0-SNAPSHOT.jar", "sha256": "<Agent已验SHA256>"},
  "ops-platform": {"path": "ops-platform-service/target/ops-platform-service-1.0.0-SNAPSHOT.jar", "sha256": "<Platform已验SHA256>"},
  "ops-gateway": {"path": "ops-gateway/target/ops-gateway-1.0.0-SNAPSHOT.jar", "sha256": "<Gateway已验SHA256>"},
  "ops-web": {"path": "ops-web/dist", "sha256": "<上述前端规范化清单SHA256>"}
}
```

两仓库提交后执行：

```text
python validation/platform/phase6_bundle.py --repository . --ops-repository .work/opsagent-phase6 --artifacts .work/phase6/artifacts.private.json --observation-report .work/phase6/observation/<真实观察ID>/report.json
```

输出到 `.work/phase6/dist/harness-phase6-<中台SHA前12位>-<OpsSHA前12位>.zip`，已有交付物不会覆盖。程序从精确 Git blob 读取公开部署文件，用调用者给出的 SHA 核对实际 JAR 和前端；打包后读回 ZIP 校验每个文件，并再次核对两个仓库和所有构建产物没有变化。禁止符号链接、junction、路径穿越、同名/大小写冲突条目和未审阅部署文件。

仓库提交证明公开源文件与部署文件的版本，调用者核验 SHA 证明包内二进制与已核验产物相同；当前没有内嵌的“从该提交编译”签名证明，manifest 明确保留这一限制。

连续观察报告只导出无秘密的状态、观察 ID、真实经过时间和读取时报告摘要，不复制报告内容或本机私有路径。`RUNNING` 只能来自最近 180 秒内更新的报告。`PASSED` 要求实际经过所请求的至少 86400 秒、完整分钟数准确、连续性及质量检查均通过、没有首失败记录或未对账写请求。86400 秒必须有 864000 个请求，其中 GET 777600、POST 86400，错误为零。

打包器还读取报告同目录的 `windows.jsonl`，核验 1440 个完整分钟的顺序、时间、读写数量、每窗质量和最终窗口与报告一致，并只导出窗口文件摘要与已核验请求数量。缺窗、重复窗、部分窗、非有限延迟、失败窗或汇总不一致均拒绝 `PASSED`。短跑、失败、终止保持原状态，不能折算为 24 小时。Linux 七天观察、真实异机恢复和公网部署在此本机工程包中仍标为目标环境待执行；后续已授权部署需要独立记录目标验收，不从本机结果推断完成。

代码会核验报告字段的一致性，不提供独立的远端时间或防篡改证明；应保留原始逐分钟窗口和命令账本，供交付后复核。打包时的状态是快照，长跑完成后应另外保存完整验收记录，不覆盖已经交付的 ZIP。
