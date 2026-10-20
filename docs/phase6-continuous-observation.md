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

请求使用 `http.client.HTTPConnection`。入口仅接受 `http://127.0.0.1` 或 `http://localhost` 加可选合法端口，拒绝用户信息、路径、查询和片段。观察器按线程和 origin 分开复用健康连接，不跨线程共享 socket，不读取系统代理、不创建 TLS 上下文或加载证书库、不跟随重定向。响应正文限制为 2 MiB；完整读取并解析后关闭响应，只有服务未声明关闭且 socket 仍存在时保留连接。空闲达到 5 秒的连接在下一请求发送前关闭并新建；JSON 错误、响应截断、超限或传输失败立即丢弃连接。禁用隐式重新连接，每个样本只发送一次，失败计错，不在同一样本重试；写请求结果未知时保留原幂等键。线程池退出后统一关闭连接。

请求延迟由 `time.perf_counter()` 测量，覆盖客户端任务开始后构造请求、获取身份头、连接、收发完整响应和 JSON 解析的端到端耗时。这是客户端观测值，不等同于服务端处理时间；线程池中等待开始执行的时间不计入该值。Windows Python 3.12 的该时钟使用高分辨率 `QueryPerformanceCounter`，避免 `time.monotonic()` 在本机使用 `GetTickCount64` 时约 15.625 ms 的量化。连续运行时长、调度停顿和时钟一致性仍使用原单调计时与墙钟检查，24 小时门槛不变。

每次执行生成独立观察 ID 与目录：

- `report.json`：实时状态、单调时钟真实经过时间、进程绑定、请求数、错误数、刷新次数及验证边界；`qualityPassed` 初始为 `null`，首次请求、窗口或运行失败时立即变为 `false`，`firstQualityFailure` 保留首次失败的时间、固定原因码以及适用的请求序号或窗口号。
- `windows.jsonl`：逐分钟总请求/读写请求 P95、错误率、队列长度及最老排队年龄、兼容 worker 和 UNKNOWN 调用数量；`completeMinute` 标记完整分钟窗口，报告中的 `completedMinuteWindows` 单独统计此类窗口。中断时也保留已收集的最后一个部分窗口，并将无法取得的服务状态明确标记为不可用。
- `diagnostics.jsonl`：追加并落盘失败样本。每条记录包含十个固定字段：`at`（UTC 记录时间）、`sequence`（适用时的请求序号）、`method`（适用时的 HTTP 方法）、`status`（已收到的 HTTP 状态，尚未收到为 0）、`exceptionClass`（白名单异常类型）、`latencyMillis`（已测得的请求延迟）、`code`（固定错误码），以及 `phase`（固定请求阶段枚举）、`errno`、`winerror`（固定白名单中的整数系统错误码，未取得或不在名单为 `null`）。运行级失败没有请求序号、方法或请求耗时，相应值为 `null` 或 0。文件不记录凭据、令牌、幂等键、Run ID、URL、响应正文或异常文本。
- `commands.sqlite3`：在发出写请求之前持久化幂等键，收到响应后记录 Run ID。响应未知不重试，保留键供原操作查询。

`report.json.transport` 提供固定策略和连接计数，不包含 origin、端点或凭据。`opened` 是分配的单次连接尝试数（包含连接失败），`reused` 是复用次数，`discarded` 包含异常、空闲超时、服务关闭和最终清理，`active` 是仍登记的连接数；这些计数不能替代 HTTP 请求质量证据。

HTTP 状态在收到响应头后立即保留，即使后续收到 HTML 错误页、无效 JSON、截断正文或读取超时，也不会丢失已经获知的状态。成功状态还需满足响应结构：GET 必须包含列表类型的 `items`，POST 必须包含非空字符串 `run.id`。服务遥测接口的 HTTP、JSON 或结构失败同样记录诊断，并将该窗口的服务状态标为不可用，不能因遥测失败而丢弃窗口。

负载为每秒 10 请求，90% Run 列表读取、10% 合成 Run 创建。每分钟要求总请求及读写各自 P95 ≤ 500 ms、错误数为 0、至少两个兼容且持续运行的 worker、无 UNKNOWN 调用。队列数据是时点采样，不宣称为端到端排队延迟 P95；此验证也不包含模型响应耗时、向量检索质量或生产业务吞吐。

窗口按观察循环收集的已完成请求统计；在途请求进入后续窗口，因此单个窗口请求数可以略多或略少于 600。P95 使用排序后第 `ceil(0.95 × 样本数)` 个值，不对多个窗口求平均。任何一个窗口超过 500 ms 都会使本次质量验收不通过，后续正常窗口不能抵消。失败 GET 的状态、异常类型和延迟会写入诊断文件；成功 GET 的逐请求原始延迟未落盘，只有窗口统计。POST 的延迟及幂等键保留在 SQLite 中，不能据此声称已具备完整服务端链路追踪。

只有真实经过至少 86400 秒、连续性、全部窗口门槛和完整覆盖检查均通过，才标记 `PASSED`。覆盖检查要求完整分钟窗口数恰好等于 `duration // 60`，总请求数为 `duration × 10`，其中 GET 为 `duration × 9`、POST 为 `duration`，窗口汇总必须与总计一致；SQLite 必须有 `duration` 条独立写命令，并全部取得 202 状态和唯一 Run ID 回执。对于 86400 秒观察，这对应 1440 个完整分钟窗口、864000 个请求（777600 GET、86400 POST）和 86400 条已接受写回执。回执表示请求已被接受，终止后的业务状态核对另行记录。

循环在判断到达结束时间之前检查连续性，最终收集请求和服务状态后也检查连续性。休眠或调度停顿直接跨过截止时间时，不能仅因已过 86400 秒而验收通过；缺少窗口、请求或写回执也不能以经过时间代替证据。

`completed24Hours` 单独表示连续运行时长已满足，必须同时检查 `qualityPassed` 和最终状态；正常跑满但任一窗口未达标时仍为 `FAILED_SERVICE_TARGET`，即使 `completed24Hours=true`。60 秒试跑最多标记 `SHORT_RUN_PASSED`。仍在运行的报告保持 `RUNNING`，只表示观察进程仍在执行；一旦 `qualityPassed=false`，后续成功窗口不会恢复本次质量结果，也不会覆盖 `firstQualityFailure`。人工终止、机器睡眠、服务故障或代码切换都不能作为成功完成。Linux 7 日观察和异机恢复需在下一轮实际 VPS 环境另行执行。

首次 24 小时观察 `soak-37cce5a194aa44da8b70f37279c651a2` 的第 62 个窗口记录了 599 个请求、零错误，总 P95 与 GET P95 均为 **516 ms**，超过既定 500 ms 门槛；该次观察不能通过质量验收。随后在本机剖析确认，旧客户端即使仅发送 HTTP，逐请求调用 `urllib.request.build_opener` 仍会创建默认 HTTPS handler、初始化 TLS 上下文并加载 Windows 证书库，带来明显的额外测量开销。此发现说明需要修复测量器，不能据此删除失败窗口、反算修改旧延迟，或断言该分钟全部尾延迟都来自测量器。

修复后采用新的观察 ID、目录和完整 86400 秒计时。原报告、全部窗口及命令账本继续保留；中止旧观察时保存最后报告快照、退出或中断事实，以及 SQLite 账本和仍存在的 WAL。对已发出的写请求，用原幂等键或 Run ID 在中台核对结果，将中断对账结果另存，不能通过新键重放来补齐记录。新旧运行、失败前后窗口以及短跑时长均不得拼接，500 ms 和 86400 秒门槛保持不变。

第二次真实观察的第 128 个窗口出现 1 次 GET 错误。旧观察器没有保存该请求的 HTTP 状态或异常类型，原始数据无法还原具体原因；目前不能将其归因于服务端、网络、身份刷新或观察器。该观察器已停止，原报告、窗口和 SQLite 账本保留，引用记录在私密工作目录的 `observation-second-attempt-reference.json`。已发出的 **8858 条写请求**均核对为唯一、已完成，无冲突，也未重放。此次观察不能通过质量验收，其开始时间和经过时长不能沿用到后续观察。

本次补充修复覆盖失败诊断、不可恢复的质量失败状态、响应结构校验、跨截止时间的连续性检查以及完整覆盖检查；观察器单元测试 20 项通过。测试中的超时、时钟跳变和完整 24 小时证据采用本地测试服务或模拟数据，不能代替新的真实 86400 秒观察，也不能证明第二次 GET 错误的具体成因。

第三次观察 `soak-633d38a422f742839bd4c4c589c409c5` 于 2026-10-21 00:50:57（UTC+8）启动，第 21 窗口出现一次 GET `OSError`（尚未收到 HTTP 状态，耗时 17.235 ms），已停止并保留原始证据。**1494 条写请求**全部只读核对为唯一且已完成，无重放。Windows `Tcpip 4227` 警告比该请求失败记录早 16.6251 ms，明确描述出站端点过早复用受限；同期 API、两个 worker 和 Auth 进程存活。时间与错误类型强烈支持短连接频繁创建的解释，但事件没有客户端 PID/端点，旧诊断没有 errno，不能宣称已精确锁定该调用，更不能据此解释第二次失败。第三次的时长与原最早完成时间均不再有效。

第三次失败后增加上述连接复用和阶段/系统错误码诊断，观察器专项 **30 项测试**通过；独立审查未发现阻断问题，连同阶段六打包与内部 TLS 的 **52 项测试**全部通过。真实本地 HTTP 测试覆盖线程及 origin 隔离、空闲更换、服务关闭、失效连接仅失败一次、POST 响应丢失不重发，以及退出清理。测试不放宽 500 ms、零错误、请求覆盖或 86400 秒门槛，不能代替新的独立长跑。5 秒空闲上限不保证服务端不会更早关闭连接；发生此类错误仍正常计入失败。

连接复用版真实独立 smoke `soak-279adb59735745078b33d7833895eb58` 运行 60.047 秒，600 请求（540 GET/60 POST）、零错误，P95 总/GET/POST 为 81.600/81.600/78.145 ms；9 次连接尝试、592 次复用，结束后 active=0。第四次观察 `soak-00d293fc7e434b29963ea84655b8787e` 于 **2026-10-21 01:31:54（UTC+8）**独立启动，最早 **10 月 22 日 01:31:54**满足时长，仍需全部质量与完整性检查；该 smoke 的时间与请求不计入其中。
