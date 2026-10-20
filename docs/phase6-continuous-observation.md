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

每次执行生成独立观察 ID 与目录：

- `report.json`：实时状态、单调时钟真实经过时间、进程绑定、请求数、错误数、刷新次数及验证边界。
- `windows.jsonl`：逐分钟总请求/读写请求 P95、错误率、队列长度及最老排队年龄、兼容 worker 和 UNKNOWN 调用数量。
- `commands.sqlite3`：在发出写请求之前持久化幂等键，收到响应后记录 Run ID。响应未知不重试，保留键供原操作查询。

负载为每秒 10 请求，90% Run 列表读取、10% 合成 Run 创建。每分钟要求总请求及读写各自 P95 ≤ 500 ms、错误数为 0、至少两个兼容且持续运行的 worker、无 UNKNOWN 调用。队列数据是时点采样，不宣称为端到端排队延迟 P95；此验证也不包含模型响应耗时、向量检索质量或生产业务吞吐。

只有真实经过至少 86400 秒、连续性和全部窗口门槛均通过，才标记 `PASSED` 且 `completed24Hours=true`。60 秒试跑最多标记 `SHORT_RUN_PASSED`。仍在运行的报告保持 `RUNNING`；人工终止、机器睡眠、服务故障、代码切换都不能标为完成。Linux 7 日观察和异机恢复需在下一轮实际 VPS 环境另行执行。
