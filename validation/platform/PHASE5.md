# 第五阶段验证运行器

工程与真实验收结论见 [实施记录](../../docs/phase5-implementation.md)。这里提供复跑入口；不要把默认值当成已配置的环境，真实凭据仅放忽略的 `.work/`。

## 离线工程检查

```sh
python validation/platform/phase5_deployment_validate.py --compose --report .work/phase5/deployment-validation.json
python validation/platform/phase5_bundle_test.py
```

Java/控制台构建通过后还可用 `phase5_deployment_validate.py --jar JAR_PATH` 离线实读部署示例。Compose 检查不启动 Docker daemon；备份测试使用模拟加密调用，不能证明真实 age/SSH/灾备通过。

## 真实本机 MySQL 与双 JVM

`phase5_execution_acceptance.py` 只接受 `127.0.0.1` 上独立数据库 `harness_phase5_execution`，项目 `phase5-execution`（及 `phase5-secondary`）、JVM 端口 8112/8113、fixture 端口 53967、显式 `local-test` 身份。它创建合成任务，包含批准一次仅写本机 fixture journal 的动作；强杀只针对自己创建的 Java 子进程。不会请求真实模型/业务工具。

私有配置由环境管理者准备，必要字段如下：

| 字段 | 值/职责 |
|---|---|
| `workspace`, `workDir` | 仓库绝对路径，以及该仓库 `.work/` 内独立运行目录 |
| `environmentFile` | `.work/` 下私有 JSON，注入部署/治理/本地身份/SQL/metrics/retention 所需环境变量 |
| `java`, `jar` | Java 17 可执行文件及确定哈希的完整平台 JAR |
| `project`, `ports`, `fixturePort` | 上述强制的隔离项目和端口 |
| `username`, `password` | 专用合成本地用户；须能选择两个项目并有本机管理权限 |
| `mysql` | `client`, `host`, `port`, `user`, `database`, `passwordEnv`；密码从私有环境取得，不放命令行 |
| `historicalRows` | 必须 100000；插入合成终态，不发起十万次远端调用 |
| `expectedToolConcurrency` | 本轮使用 10；治理配置模型并发 5，全局执行 10 |
| `fixtureTokenEnv` | fixture 与 manifest 共享的私有 token 环境变量名 |
| `reuseSyntheticHistory` | 默认 false；仅明确复用时设 true，校验十万条 core/index/platform 双向关联与终态，不再插入 |
| `schemaAlreadyMigrated` | 默认 false；true 必须配合 `HARNESS_SCHEMA_MODE=validate`，由两个 JVM 只读验证既有结构/指纹，不记作迁移通过 |

可调用模块中的 `deployment_template()` 生成两个项目的 fixture manifest；将模型/MCP引用绑定到本机 fixture、合成身份与独立 SQL 账号。环境管理者应使用独立迁移凭据初始化数据库；运行账号只需 DML。第一次默认调用 `migrate`，已有集群须真实停流/停 worker 并提供独立迁移的离线确认，不能为了复跑测试清除 UNKNOWN 或绕过迁移保护。

```sh
python validation/platform/phase5_execution_acceptance.py --config .work/phase5/execution.private.json --report .work/phase5/execution.json --soak-seconds 60
```

报告目标必须尚不存在。验收覆盖两个项目/worker、共享并发/预算、十万历史、10 RPS 90:10 GET/POST、20 RPS 突发、未饱和排队、强杀/断连 UNKNOWN、指标、告警确认和精确单条合成归档。每次失败保存已有观测和失败码，清理自己的进程，不重放未知写。旧故障 Run 可保留用于后续验证。

治理心跳与 Run 故障恢复是不同事务：运行器允许最多 5 秒让 DISPATCHED 收敛为 UNKNOWN，逐次要求同一唯一 permit 始终存在且占容量，最后核对 fixture 仍只有一次派发。

`--checks CHECK_NAME ...` 可独立执行指定检查；报告会显式写明 `selected checks only`，不能充当全套通过记录。20 次治理概览额外测 P95，不将只读运行列表达标外推为管理页达标。

正式本机持续观察需显式 `--soak-seconds 86400` 并真实等待；60 秒检查不会设置 `completed24Hours=true`。Linux 七天观察、真实代理/通知/备份/恢复均需环境独立验收。

## HTTPS 与制品

`phase5_proxy_acceptance.py --config PRIVATE_JSON --report NEW_REPORT` 在本机 TLS 代理前验证真实 JVM 的 Origin、CSRF、cookie 与可信代理。需预备专用 SAN 证书、本地测试身份、JVM 和私有代理配置；不代表 nginx/Linux 已通过。

最终提交后用 `phase5_bundle.py --jar JAR_PATH --jar-sha256 VERIFIED_HASH` 打包。该脚本只接收干净 `develop/` 提交的受审查部署文件和公开文档，不包含测试报告或私有数据。详见 [部署说明](../../docs/phase5-deployment.md)。
