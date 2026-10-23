# 平台真实本机进程验收

2026-09-18，最终候选构建的完整验收 **9/9 通过**。主报告为 [platform-20260918-process-r7-report.json](platform-20260918-process-r7-report.json)，运行器与配置格式见 [validation/platform](../../../validation/platform/README.md)。

此次产生 16 个合成 Run，记录 180 次真实 HTTP 请求。共启动 7 个独立 Java 进程实例，包含两名 worker 的停止与重启；结束时全部由运行器确认退出。MCP fixture 收到 11 次派发、2 次合成写副作用；模型 fixture 共调用 2 次。此前的 r2/r3 预检失败、r4 部署冒烟、r5 证据 SQL 查询失败和 r6 的 7 项子集报告均保留；最终结论仅依据 r7 全量报告。

| 检查 | 实际证据 |
| --- | --- |
| 两个独立 API + worker | A、B 各自完成实际 MCP HTTP 派发，分别关联真实 PID |
| HTTP 创建、查询、列表、事件、幂等与 CAS | 跨进程重复创建得到同 Run；旧 ETag 返回 412；人工等待暂停恢复后原 approval id、digest、expiresAt 均不变 |
| 外部调用进行中的暂停恢复 | 真实 HTTP 调用被 gate 暂停；恢复后工具调用计费 1 次、派发 1 次 |
| SQL admission 配额 | 两个 API 同时创建共 2 个活跃等待 Run；第三个请求 429，取消后持久终止 |
| 双 worker 共享并发 | 6 个读任务各派发一次；fixture 观察到的并发峰值为 1，与全局/项目上限一致 |
| HTTP 审批写入 | 审批前 0 次副作用，审批后 1 次 |
| Java 进程真实重启 | 两个 worker PID 均改变；原 Run 的 deadline、limits 和 22 tokens / 1 model call 保留，没有重复模型调用 |
| UNKNOWN、重启及可信证据对账 | 合成写入先 fsync 再关闭真实 socket；重启后仍只有 1 次副作用；未导入证据返回 409，独立回执核验与离线导入后对账返回 202；已取消 Run 保持 CANCELLED，恢复仍被拒绝 |
| MySQL 正常停止与恢复 | MySQL PID 从 13756 变为 16292；停机期间两个 API health 均为 503 且 SQL 不可连接；恢复后原 Run revision 12、deadline、limits、22 tokens / 1 model call 原样保留，随后审批并完成，无重复模型调用 |

JAR SHA-256：`e1b5eccd9275f1caf3473f930d7ccbbf6d31fb3b125c1c228af17d2f129871f6`。验收使用私有目录中的冻结副本，结束时与当前 target JAR 校验一致。主报告 SHA-256：`22c1d654e9832fa3fc3aa042926ed6fd45fa69432b21e6f82638ae009109fcf9`；其中另含运行器、fixture、manifest 哈希以及逐项 HTTP/SQL 证据。

运行器使用实际 MySQL 与 MySQL 支持的隔离 OpsAgent Auth 宿主。模型与工具是本地合成 fixture；UNKNOWN 是实际本地断线，不能外推为真实 GitHub 断网测试。Java 在 Windows 上的 terminate 是突然终止，报告已明确记录；MySQL 则由独立环境所有者正常关闭、重启，运行器从不终止 mysqld。数据库备份与隔离恢复由主任务单独留证。

这次验证不覆盖原生产 OpsAgent 启动配置、Nacos、ES、MQ、生产模型质量、公网部署或长期运维 SLO。真实 DeepSeek 冻结质量基线、GitHub MCP 写入以及 OpsAgent 业务验收使用同目录中的各自报告，不能由本地合成 fixture 替代。
