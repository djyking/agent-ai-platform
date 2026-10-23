# 保留当前页面流程的部署配置

`full-ui.prepare.py` 生成当前 OpsAgent 页面流程与 Harness 集成的私有 Compose 候选，不连接服务器或执行部署。原 `ai-minimal.prepare.py` 默认输入格式、18 服务选择和关闭可选能力的契约保持不变；它不能代替本配置完成“现有页面流程全部正常”的验收。

## 服务集合与边界

本配置要求已核实的向量检索为开启状态，并保留 Elasticsearch、Qdrant。选中 28 个服务：

- 既有核心：MySQL、Redis、RabbitMQ、Nacos、Auth、Ticket、Knowledge、RAG、Agent、Platform、Gateway、Web、Elasticsearch、Qdrant。
- 页面流程：`ops-demo-order-app`、`demo-redis`、`demo-rabbitmq`、Prometheus、Alertmanager、`observation-collector`、`otel-collector`、Tempo、Grafana、Sentinel dashboard。
- Harness：API、一个 Worker、Support、内部 TLS 代理。

订单/通知 Demo 是当前 `/automation` 页面真实演练的执行目标。订单、通知共享 Demo JVM，独立 Redis 与 RabbitMQ 的网络和数据卷原样保留；不能与核心中间件合并。Prometheus 与 Alertmanager 负责故障告警和恢复回执；OTel 与 Tempo 支持当前拓扑的“实际调用”视图；采集器支持基础设施观测和持续巡检。主 RabbitMQ 承担真实工单、知识解析/索引和异步事件，不能因启动时没有流量而删除。

2026-10-21 的只读查询确认 Grafana 与 Sentinel dashboard 在远端 CMDB 中均为 `ACTIVE`，采集器也有对应健康检查，因此保留。Sentinel dashboard 与应用内 Sentinel 运行功能不同：本配置保留二者，不能只停止 dashboard 就把应用内限流也关闭。后续如需移除控制台，必须同步处理纳管范围和采集目标，并验证页面行为。

不选 `operations-lab`：当前前端 `InspectionRuns.vue` 只展示 `READ_ONLY` 工作流，`OperationsView.vue` 只保留 `HEALTH_CHECK`；后端历史 `ISOLATED_DRILL` 不再有当前页面执行入口。现有业务订单与通知演练不使用该历史实验容器。不选择 Kubernetes；当前 Compose 集合没有 Kubernetes 服务，不能将关键词或历史文档当作已占用内存。

仍采用已授权的 `ops.rag.rerank-enabled=false`，因此不选择 3 GiB 上限的本地 reranker。保留混合召回、引用和索引管理，但结果排序可能变化；这不是“排序结果完全不变”的承诺。若恢复真实重排，需要另行增加已验证的容量和配置。

## 输入与生成

沿用 [ai-minimal.md](ai-minimal.md) 的真实目标基础快照、网络、私有 TLS/env、数据库和反向代理准备。输入键不增加任意参数注入：

- `format` 必须为 `harness-full-ui-inputs-v1`。
- `images` 在原映射之外必须提供 `ops-demo-order-app` 的不可变镜像引用，绑定已验收的完整源码/共享依赖快照。
- `configurationReview.services` 必须覆盖原七个 Ops Java 服务和 `ops-demo-order-app`。
- Demo 的 `dynamicConfiguration` 必须为 `managed-demo-nacos`，表示其业务/演练 Nacos 配置来源已经逐项复核；其余七个服务仍要求一般 Spring Nacos 配置导入为 `disabled`。这不关闭 Demo 自有 Nacos 配置客户端或 Sentinel 规则数据源。
- RAG、Agent、Platform、Gateway、Demo 必须使用 `reviewed-otel-wrapper`，同一已审查 wrapper SHA 和每个服务独立的 `launchEvidenceSha256`。保留精确 `JDK_JAVA_OPTIONS=-javaagent:/opt/otel/opentelemetry-javaagent.jar`、实例 UUID 行为及只读挂载；未知入口、额外 agent 和参数文件仍拒绝。

```sh
python deploy/platform/phase6/full-ui.prepare.py \
  --base-compose /private/base-compose.json \
  --inputs /private/full-ui-inputs.json \
  --output-directory /private/new-full-ui-candidate
```

输出为 `compose.full-ui.private.json`、`review.json` 和同一套已验证的 Harness nginx include。目标目录必须不存在。旧默认生成器拒绝 full-ui 输入，避免误用精简配置。

本配置以明确 JVM 属性启用 Sentinel、OTel、告警 webhook 和持续巡检。保留已有服务的依赖、网络、存储及普通配置，拒绝引用集合之外的依赖；既有唯一公网入口仍为 Web 的 80/443。全部服务保持 `restart: no`，等待分批验证后再单独评估重启策略。它不修改现有数据库、消息队列、身份、权限或 UNKNOWN 记录。

## 上线前验收

生成成功仅表示配置约束通过。先核实原 spot/crypto 等业务是否正常；暂时失败或未恢复的服务释放的内存不能作为长期共存余量。既有目标快照上，本配置保留的 28 服务上限合计约 14,368 MiB；上限不等于实际 RSS，但不能用它宣称 12 GB 主机容量足够。分批启动，逐项记录真实代表性峰值、cgroup 事件、GC/线程和数据库连接数，并预留至少 1 GiB、优先 2 GiB 物理内存；尚未启动的依赖和其他项目恢复预算必须计入。

公网开放前验证：

1. 登录/验证码、用户身份、工单与附件 ACL、知识上传解析、审核和索引管理、RAG 引用及真实回答。
2. 三个当前演练入口：Redis 配置漂移、Sentinel 规则回退、RabbitMQ 消费暂停；核对真实故障、告警建单、Agent/人工审批、恢复探针、resolved 回执与工单收口。
3. Nacos 受控配置读取/版本/发布、应用实际生效、流量规则与应用内 Sentinel 运行态。
4. 拓扑实际调用、真实指标与基础设施采样、持续巡检、Grafana/Sentinel 当前纳管节点状态。
5. Harness/Support 的公网 HTTPS、内部 TLS、代理 cookie、身份及负向权限检查。

验收不得重放历史模型 UNKNOWN。运行时属性和数据源未验证前，不把页面能打开或容器健康等同于业务完整通过。

## 配置验证

```sh
python -m unittest discover -s deploy/platform/phase6/tests -p 'test_ai_minimal.py'
python -m unittest discover -s deploy/platform/phase6/tests -p 'test_full_ui.py'
python deploy/platform/phase6/tests/run_spring_binding.py --profile full-ui --jar /path/to/accepted-boot.jar
```

这些检查验证原默认契约、当前页面依赖、隔离网络/卷、已审查 OTel 入口、受控属性和拒绝不完整配置；最后一项使用已有 Boot JAR 中的依赖逐一验证八个应用的真实 Spring 绑定，不运行 Maven、不发送业务或模型请求。普通 unittest 未设置依赖 classpath 时会跳过此项，不能把跳过视作已验证。
