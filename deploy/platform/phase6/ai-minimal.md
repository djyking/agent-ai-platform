# 既有 OpsAgent 主机的 AI 最小组合

此组合复用 `opsagent.cloud` 的现有 HTTPS 入口、数据库、身份和数据卷，增加中台 API、一个 worker、Support 和内部 TLS 网关。生成器只在本机生成可审阅的私有文件，不连接服务器、签发应用凭据、执行 SQL、启动 Docker、切换请求归属或安装 nginx。

## 输入与产物

必须从目标**当前实际使用**的 Compose 文件及其现有环境导出 `docker compose config --format json`，将 stdout 直接保存到受保护文件，不在终端或聊天显示。此文件可能含秘密。不要用本机开发 Compose、`docker inspect` 的环境数组或旧配置替代。记录原始字节 SHA-256，并将摘要填入 `ai-minimal.inputs.example.json` 的私有副本。生成器验证该摘要；它不证明导出的配置仍与目标当前状态一致。

输入中的新镜像必须是已经验收的制品所构建的 `repository@sha256:…` 或精确本地 `sha256:…` 镜像 ID。Ops 的七个 Java 服务与 Web 全部使用本轮完整验证源码快照的对应制品；不能仅因业务服务源码相同就沿用旧镜像，其打包的公共安全模块可能不同。保留其他基础设施镜像，不在 VPS 运行 Maven。输入的网络列表来自目标实际网络清单；示例地址不是已确认的空闲网段。生成器要求新网段不重叠，并将公网入口的单个固定地址设置为 API 唯一可信代理。

输入格式为 `harness-ai-minimal-inputs-v2`。`configurationReview` 必须绑定同一 `baseComposeSha256`，记录私有复核证据的 `evidenceSha256` 与实际生效的布尔值 `effectiveVectorEnabled`；七个 Java 服务分别填写 `sourcesReviewed=true`、`launchOptionsReviewed=true`、`dynamicConfiguration="disabled"` 及下面定义的 `launchProfile`。这些是有证据支持的复核声明，生成器不会自动读取目标挂载文件、核对证据内容或检查运行中的属性。证据应涵盖现行 managed/secret 文件、profile 与 import 来源、镜像内配置及 Entrypoint/Cmd/Env；必须核对将要替换的新镜像启动约定，不能只核对旧镜像。示例默认未复核，不能直接执行。

该候选只接受已证明关闭动态配置的现状，并以精确 JVM 属性固定 `spring.cloud.nacos.config.enabled=false`；Nacos discovery 不变。仍依赖动态配置、无法确认来源的目标需先单独梳理，不能将 `disabled` 当作跳过复核的选项。`launchProfile="direct"` 禁止非空 Java `command`/`entrypoint` 覆盖、未展开的既有 `env_file`、`SPRING_APPLICATION_JSON` 及 `JAVA_OPTS/JAVA_OPTIONS/JAVA_ARGS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS` 等额外启动入口。镜像内相同入口须在启动选项证据中明确排除。

唯一例外是已审阅的 RAG、Agent、Platform、Gateway 四服务：`launchProfile="reviewed-otel-wrapper"` 还必须提供 `wrapperSha256="386d1d7e2335e35dfe3b80a09b716e669f108008e53635d5adf71ced1da389ed"` 和绑定当前配置证据的 `launchEvidenceSha256`。要求入口严格为 `["/bin/sh","/opt/otel/java-entrypoint.sh"]`，command 为空，原 `JDK_JAVA_OPTIONS` 恰为 `-javaagent:/opt/otel/opentelemetry-javaagent.jar`；wrapper 与 agent 两个目标必须分别具有唯一、绝对路径、只读 bind。其他启动变量及 `JAVA_TOOL_OPTIONS` 的额外 agent 入口仍拒绝。该 wrapper 全文已审：生成运行实例 UUID、设置 OTel 资源属性，最后 `exec java -jar /app/app.jar`，不注入 Spring 属性。

按本次优先保留 AI 功能与内存余量的部署决策，例外分支保留 wrapper 的 `OPS_RUNTIME_INSTANCE_ID` 行为和原文件/挂载，但将 `JDK_JAVA_OPTIONS` 显式设为空，停用已识别的可选 OTel Java agent，并保留 `OTEL_SDK_DISABLED=true`。报告记录 `optionalOtelAgentDisabled` 和具体服务列表。此动作会停止相应监控 instrumentation；它不是对任意 wrapper 或任意 agent 的放行。

```text
python deploy/platform/phase6/ai-minimal.prepare.py \
  --base-compose /private/current-compose.json \
  --inputs /private/ai-minimal.inputs.json \
  --output-directory /private/new-candidate
```

输出目录必须不存在。输出 `compose.ai-minimal.private.json`、`review.json` 及两个 nginx 配置；Linux 上目录为 0700、文件为 0600，Windows 上还需受保护的目录 ACL。生成器不覆盖已有候选，也不会删除失败后的部分输出。Compose 文件可能保留既有秘密，不入 Git、不作为公开日志或 CI artifact。

Compose 使用 `env_file.format: raw`，其 schema 要求 Docker Compose ≥2.30；这不表示所有 ≥2.30 版本都已通过离线解析验收。本项目的 `--no-env-resolution` 契约采用已验证的 5.5.x：本机 5.5.0、CI 和目标环境 5.5.1。CI 显式安装并核对 5.5.1，避免预装 Compose 对不存在的目标私密 env 文件仍做检查；生成配置中的 `env_file.required=true` 保持不变。

在上述已验证版本中，`docker compose … config --no-env-resolution --quiet` 可检查结构而不读取目标私有 env 文件，但不验证实际凭据、证书、挂载权限或运行状态。不要将完整 `config` 输出写入公开日志。

## 服务选择与数据边界

固定保留 12 个 Ops 服务：MySQL、Redis、RabbitMQ、Nacos、Auth、Ticket、Knowledge、RAG、Agent、Platform、Gateway、Web。Agent 和 Platform 仍承担实际工作流执行，不能作为监控组件停用。

`vectorMode=preserve` 使用已复核的 `configurationReview.effectiveVectorEnabled`，不从 `OPS_VECTOR_ENABLED` 推断。后者仅是应用配置中的占位别名，挂载文件直接定义 `ops.knowledge.vector.enabled` 时，别名不保证控制最终值。实际有效值为 true 时同时保留 Elasticsearch 和 Qdrant；为 false 时保留既有 SQL 检索模式。缺少、字符串形式或未复核的值直接拒绝。只有明确选 `disable-approved` 才将启用的向量检索降级为 SQL，并在审阅报告记录；数据卷保留，不能将降级后的行为描述为完整混合检索。

reranker、监控/Trace、Sentinel、观察采集器与演示目标不纳入启动集合。固定的非秘密意图通过追加到 `JAVA_TOOL_OPTIONS` 的精确 `-D` 属性控制，优先于普通文件配置和环境来源；原 JVM 内存与编码参数原样保留。生成器拒绝与受控属性、私有 env 所属字段冲突的既有 `-D`、重复属性及参数文件，不接受任意调用方属性注入。旧显式 truststore 仍须单独复核。对应实时监控和演示工作流可能不可用，不伪造其结果。

| 服务 | 固定精确属性 |
| --- | --- |
| 全部七个 Java 服务 | `spring.cloud.sentinel.enabled=false`、`spring.cloud.sentinel.eager=false`、`spring.cloud.sentinel.transport.dashboard=`、`spring.cloud.nacos.config.enabled=false`、`otel.sdk.disabled=true` |
| Knowledge | `ops.knowledge.vector.enabled=<复核后选择>`、`ops.knowledge.ticket-url=http://ops-ticket-app:8102` |
| RAG | `ops.rag.rerank-enabled=false`、`ops.harness.answer.enabled=true`、`ops.harness.answer.new-owner=LEGACY`、`ops.rag.schema-auto-initialize=false`、`ops.harness.auth-origin=https://harness-identity.internal:8443`、`ops.harness.platform-origin=https://harness-platform.internal:8443`、`ops.agent.auth-url=http://ops-auth-app:8101` |
| Auth | `ops.harness.identity.enabled=true` |
| Ticket | `ops.alertmanager.enabled=false` |
| Platform | `ops.operations.inspection-enabled=false` |

既有完整 `OPS_HARNESS_APPLICATION_CREDENTIAL` 等私有环境变量命名保持不变，凭据和发布引用不写入 `-D` 或公开审阅报告。RAG 中直接读取大写变量的路径继续使用 `OPS_AUTH_INTERNAL_URL` 与 `OPS_KNOWLEDGE_INTERNAL_URL`。`review.json` 列出固定属性和复核声明，正式启动前仍须核对精确目标镜像的有效属性与秘密绑定；生成成功不等于配置已在目标生效。

这是完整选定 Compose，而不是普通叠加文件：继承的 Elasticsearch/Qdrant/Sentinel `depends_on` 被明确重建。已有数据卷和网络使用原名称并设为 external，不新建空卷替代旧卷。服务均为 `restart: "no"`，保持目标原手动启动政策。禁止 `down -v`、`--remove-orphans`、清空数据库或停用 Docker daemon；未选择的旧服务和其他项目不由生成器处理。

新服务的初始内存上限合计 1152 MiB，不代表实测安全容量。API/worker 的连接池分别限制为 4/1；还须计入旧 Ops 连接池、迁移及备份连接。原 MySQL `max_connections=50` 被保留，必须另行核实余量。先验收目标启动、模型及检索峰值，再考虑第二个 worker。

## 私有文件合同

`runtimeDirectory` 下应由实际部署步骤准备：

| 路径 | 内容与约束 |
| --- | --- |
| `private/runtime.env` | Harness 数据库及应用/模型秘密；raw env 每行 `KEY=value`，不使用 shell 引号或展开。`HARNESS_JDBC_URL` 使用 `harness-mysql`，必须 `sslMode=VERIFY_IDENTITY`；专用 DML 用户，不使用 Ops root/app 账号 |
| `private/support.env` | 实际 `SUPPORT_PROJECT` 和独立 `SUPPORT_APPLICATION_SECRET`；不能使用本机测试用户 token/访问码 |
| `private/ops-rag.env` | 完整 `OPS_HARNESS_APPLICATION_ID/CREDENTIAL/PROJECT` 和 `OPS_HARNESS_ANSWER_RELEASE_ID/RELEASE_DIGEST/PROVIDER/MODEL`；不使用仅本机 profile 映射的简称 |
| `private/config/api.json`、`worker.json` | 目标发布、应用、项目与权限配置；`workerEnabled` 分别 false/true；`identityOrigin=https://harness-identity.internal:8443` |
| 同上 `tools[]` | 必须包含 `kind=opsagent-rag`、`origin=https://harness-rag.internal:8443`，否则保护输出核验无 RAG origin，会拒绝读取；不得导入本机合成知识和 Run 账本 |
| `private/config/governance.json` | 目标治理配置与低共享并发，迁移时核对指纹 |
| `private/trust/cacerts` | 从实际 JRE 默认 truststore 复制并导入私有 CA，保留全部公共 CA；类型匹配输入 JKS/PKCS12，候选完整性密码为 `changeit` |
| `private/internal-tls/server-chain.pem`、`server-key.pem` | 叶证书链、叶私钥；SAN 包含三个内部别名，只挂内部 nginx |
| `private/mysql-tls/ca.pem`、`server-cert.pem`、`server-key.pem` | MySQL 叶证书 SAN 包含 `mysql` 和 `harness-mysql`；私钥按实际 MySQL UID 设为可读 |
| `nginx/ai-minimal.locations.conf`、`internal-tls.conf` | 此生成器输出的两个配置，安装后先验证语法 |

私有 CA 签名密钥保留在独立目录，**不得挂载输出根目录或 CA 私钥目录**。网关 UID 101、Java UID 10001、MySQL 实际 UID 对应的私有目录与文件需在安装时明确配置只读权限；生成器不猜测或放宽权限。若信任库使用自定义完整性密码，必须同步 JVM 参数；本候选不自动读取该密码。

2026-10-21 的只读检查发现原 MySQL 自动生成证书没有 SAN，CN 也不是 `mysql`/`harness-mysql`，不能满足所选 JDBC 主机名验证。候选增加 `--ssl-ca/--ssl-cert/--ssl-key` 指向新叶证书，保留原数据目录、旧证书及其他 MySQL 参数。此时数据库未运行，专用用户、`REQUIRE SSL`、授权与真实握手仍待目标验证；不能把证书文件存在当作连接成功。不要降级到 `useSSL=false` 或仅校验 CA 来绕过此阻碍。

## 公网与内部路由

将 `include /etc/nginx/harness-ai-minimal.locations.conf;` 加入现有主域 HTTPS server，保留 `/`、`/api/`、ACME、证书、`www` 行为及已有管理/内部接口封禁。生成器只挂载片段，**不会自动修改当前 active.conf 或启用入口**。原配置已有 `/internal` 与 `/internal/` 拒绝规则，不应重复声明 location。

- `/console/`、`/v1/` 保留路径，转发到 API。
- `/support` 重定向到带尾斜杠路径并保留 query；`/support/` 剥离此前缀，登录及退出 cookie Path 均改写为 `/support/`。公开 origin 仍是 `https://opsagent.cloud`。
- 公网入口清除 `Forwarded`，固定 Host/Proto/Port，替换 X-Forwarded-For。API 只信该入口在专网的固定地址；内部 TLS 网关不在可信代理名单。
- 内部 TLS 使用三个固定 SNI/Host 别名及按方法限定的身份/RAG路径；平台别名只转发 `/v1/projects/`。未知 SNI 拒绝握手，未知路由拒绝；没有任意 URL 代理。各层关闭上游自动重试，未知写不重放。
- 内部 TLS 只到网关，其后为明确私网的 HTTP hop；不声称服务之间全程 TLS。不得将私网 HTTP 地址伪装为 loopback。

## 启用与回滚门槛

先核对备份、现行配置、确切镜像摘要、数据库 additive 迁移和授权；再验证 Compose、nginx、TLS 成功/失败、同域路径/资源、Host/Origin/CSRF、登录退出 cookie，以及真实身份与来源 ACL。本候选始终以 `OPS_HARNESS_ANSWER_NEW_OWNER=LEGACY` 起步；只有目标新请求的完整验证通过后，才另行显式切换新请求归属。

回退优先恢复新请求 LEGACY，保留已经属于 HARNESS 的查询/控制能力、幂等键、Run 和 UNKNOWN。恢复已保存的入口配置前执行 nginx 语法检查。不能回退到不理解新 schema/请求归属的旧二进制，也不能为通过迁移而重置 UNKNOWN。

轻量契约测试（不运行 Maven、不启动服务、不连接 VPS）：

```text
python -m unittest discover -s deploy/platform/phase6/tests -p test_ai_minimal.py -v
```

Docker CLI 可用时另做不依赖 daemon 的 Compose 解析。测试及候选生成成功不代表证书安装、生产身份、端到端联调、容量或连续观察通过。

设置 `HARNESS_SPRING_BINDING_CLASSPATH` 为已存在、已复核的 Spring Boot、Spring Framework 和 SnakeYAML JAR 列表，并按需设置 `JAVA_HOME` 后，同一测试命令还会运行真正的 Spring 属性绑定实验：临时 YAML 直接写入相反值、短别名和两种冲突环境变量拼写，再传入生成器产出的精确 `-D`，逐项检查 Environment、Binder 和占位符解析。实验使用 Java source-file 模式，不运行 Maven、不启动应用或网络服务；未提供 classpath 时该项显式跳过，不能算已验收。

CI 的 Linux 与 Windows 构建均在已有 `clean verify` 成功后强制运行下面的检查，直接选择当次 Boot JAR 中的七个 Spring/Boot/YAML 依赖，验证路径、重复项和大小上限后逐文件写入临时目录；不展开整个应用、不执行应用入口。runner 要求恰好一个绑定测试通过且零跳过，依赖缺失或无效时失败。独立 `api-contract` job 没有构建 JAR，可以显式跳过这项绑定实验，其部署生成器和 runner 错误分支测试仍正常执行；两个实际构建 job 提供必需的绑定覆盖。

```text
python deploy/platform/phase6/tests/run_spring_binding.py \
  --jar harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar
```
