# 本机连续观察

`validation/platform/phase6_continuous_observation.py` 对真实 OpsAgent 身份源和真实中台 API 发起连续负载。观察仅使用独立 `phase6-soak` 项目、用户、应用及部署文件中的合成 End 工作流，不调用外部模型或工具，不执行生产业务。

先完成其他联调、冻结代码与配置，并启动一个 API、至少两个使用相同配置 fingerprint 的 worker。观察过程中修改配置、重启 worker 或服务会使这一段连续观察失效，不能将多段短跑拼成 24 小时。

私密配置文件示例（所有值均为部署环境的实际路径或已发布引用）：

```json
{
  "origin": "http://127.0.0.1:8110",
  "authOrigin": "http://127.0.0.1:18101",
  "project": "phase6-soak",
  "applicationCredentialFile": "/private/soak-application.secret",
  "tokenFile": "/private/soak-session.private.json",
  "deploymentFile": "/private/platform.json",
  "releaseRef": {
    "agentId": "synthetic-local-observation",
    "releaseId": "部署中该合成发布的 UUID",
    "digest": "由实际可执行文件 releases 命令给出的摘要"
  },
  "reportDirectory": "/private/observation",
  "processRecordFiles": [
    "/private/platform-api.process.json",
    "/private/platform-worker-1.process.json",
    "/private/platform-worker-2.process.json"
  ]
}
```

`tokenFile` 需包含官方登录获得的 `accessToken` 和 `refreshToken`。观察器每 10 分钟调用官方刷新接口，原子保存轮换后的令牌；禁止与其他任务共享同一观察用户会话。项目授权需要本人 Run 创建、列表、读取以及 `operations:admin`，以读取该隔离项目的 worker/UNKNOWN 状态。进程记录包含启动时的 `pid`、`sha256`、`port`、`startedAt`，服务重启时必须更新，不能用旧记录覆盖新的进程身份。

先短跑验证，再单独启动真实长跑：

```text
python validation/platform/phase6_continuous_observation.py --config /private/observation.private.json --duration 60
python validation/platform/phase6_continuous_observation.py --config /private/observation.private.json --duration 86400
```

Windows 启动后台进程时使用隐藏窗口。脚本不会修改电脑的睡眠、电源或重启策略；休眠、时钟跳变和超过 5 秒的调度停顿都会记录为连续性失败。观察期间电脑与依赖服务必须保持可用。

请求使用 `http.client.HTTPConnection`。入口仅接受 `http://127.0.0.1` 或 `http://localhost` 加可选合法端口，拒绝用户信息、路径、查询和片段。每个请求单独建立并关闭连接，不读取系统代理、不创建 TLS 上下文或加载证书库、不跟随重定向。响应正文限制为 2 MiB；成功、JSON 错误、响应截断及超限时均关闭响应和连接。传输失败记为错误，写请求结果未知时保留原幂等键。

请求延迟由 `time.perf_counter()` 测量，覆盖客户端任务开始后构造请求、获取身份头、连接、收发完整响应和 JSON 解析的端到端耗时。这是客户端观测值，不等同于服务端处理时间；线程池中等待开始执行的时间不计入该值。Windows Python 3.12 的该时钟使用高分辨率 `QueryPerformanceCounter`，避免 `time.monotonic()` 在本机使用 `GetTickCount64` 时约 15.625 ms 的量化。连续运行时长、调度停顿和时钟一致性仍使用原单调计时与墙钟检查，24 小时门槛不变。

每次执行生成独立观察 ID 与目录：

- `report.json`：实时状态、单调时钟真实经过时间、进程绑定、请求数、错误数、刷新次数及验证边界。
- `windows.jsonl`：逐分钟总请求/读写请求 P95、错误率、队列长度及最老排队年龄、兼容 worker 和 UNKNOWN 调用数量。
- `commands.sqlite3`：在发出写请求之前持久化幂等键，收到响应后记录 Run ID。响应未知不重试，保留键供原操作查询。

负载为每秒 10 请求，90% Run 列表读取、10% 合成 Run 创建。每分钟要求总请求及读写各自 P95 ≤ 500 ms、错误数为 0、至少两个兼容且持续运行的 worker、无 UNKNOWN 调用。队列数据是时点采样，不宣称为端到端排队延迟 P95；此验证也不包含模型响应耗时、向量检索质量或生产业务吞吐。

窗口按观察循环收集的已完成请求统计；在途请求进入后续窗口，因此单个窗口请求数可以略多或略少于 600。P95 使用排序后第 `ceil(0.95 × 样本数)` 个值，不对多个窗口求平均。任何一个窗口超过 500 ms 都会使本次质量验收不通过，后续正常窗口不能抵消。GET 的逐请求原始延迟目前未落盘，只有窗口统计；POST 的延迟及幂等键保留在 SQLite 中，不能据此声称已具备完整服务端链路追踪。

只有真实经过至少 86400 秒、连续性和全部窗口门槛均通过，才标记 `PASSED`。`completed24Hours` 单独表示连续运行时长已满足，必须同时检查 `qualityPassed` 和最终状态；正常跑满但任一窗口未达标时仍为 `FAILED_SERVICE_TARGET`。60 秒试跑最多标记 `SHORT_RUN_PASSED`。仍在运行的报告保持 `RUNNING`，这不代表此前窗口都合格；人工终止、机器睡眠、服务故障或代码切换都不能作为成功完成。Linux 7 日观察和异机恢复需在下一轮实际 VPS 环境另行执行。

首次 24 小时观察 `soak-37cce5a194aa44da8b70f37279c651a2` 的第 62 个窗口记录了 599 个请求、零错误，总 P95 与 GET P95 均为 **516 ms**，超过既定 500 ms 门槛；该次观察不能通过质量验收。随后在本机剖析确认，旧客户端即使仅发送 HTTP，逐请求调用 `urllib.request.build_opener` 仍会创建默认 HTTPS handler、初始化 TLS 上下文并加载 Windows 证书库，带来明显的额外测量开销。此发现说明需要修复测量器，不能据此删除失败窗口、反算修改旧延迟，或断言该分钟全部尾延迟都来自测量器。

修复后采用新的观察 ID、目录和完整 86400 秒计时。原报告、全部窗口及命令账本继续保留；中止旧观察时保存最后报告快照、退出或中断事实，以及 SQLite 账本和仍存在的 WAL。对已发出的写请求，用原幂等键或 Run ID 在中台核对结果，将中断对账结果另存，不能通过新键重放来补齐记录。新旧运行、失败前后窗口以及短跑时长均不得拼接，500 ms 和 86400 秒门槛保持不变。
