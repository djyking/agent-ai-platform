# 执行 API 契约

`openapi.json` 是 OpenAPI 3.1 / JSON Schema 2020-12 文档，版本 `0.1.0`，由 `harness-platform-service` 实现。 架构、权限映射、CAS 差距及实施要求见 [阶段 2 API 设计](../phase2-api-design.md)。服务地址使用 `.invalid` 占位域名，示例均为合成数据。

在仓库根目录使用独立 Python 环境验证，不修改系统 Python，也不要求启动模型/MCP/数据库：

```powershell
python -m venv .work/api-contract-env
& .work/api-contract-env/Scripts/python.exe -m pip install -r docs/api/requirements.txt
& .work/api-contract-env/Scripts/python.exe docs/api/validate_contract.py
```

Linux/macOS 将解释器路径改为 `.work/api-contract-env/bin/python`。可以将同一文件交给支持 OpenAPI 3.1 的文档渲染器；不要使用只支持 OpenAPI 3.0 的检查器误判 `type: [string, null]` 等合法语法。

检查覆盖：OpenAPI 结构、所有本地引用、各 schema 的合法性、格式校验、正反请求/响应案例、操作 ID 唯一性、鉴权继承、状态码、POST 幂等/If-Match 前置约束，以及文档规定的字节/深度补充边界。整个 JSON 请求的 64KiB/16 层限制不能仅靠局部 schema 实现，必须在服务解析入口执行。

2026-09-15 本地验证结果：OpenAPI 3.1 结构、**11 个操作、11 个 schema 示例、31 个 HTTP 请求/响应示例、33 个正反案例**及引用/策略检查全部通过。

仓库 `Verify` workflow 已加入独立 `api-contract` job，使用相同命令检查契约。远端运行结果以 [当前交付记录](../phase12-implementation.md) 绑定的提交为准。

此脚本不证明 HTTP 服务已存在，也不验证 SQL 事务、IAM、ETag 签名、字段脱敏、游标签名或真实远端调用。平台测试和进程验收另行覆盖以下行为，不能仅凭本脚本宣称通过：

1. 两项目/两应用/两主体访问各端点与幂等重放；未经授权无信息泄露。
2. 同键并发、丢响应后恢复、同键不同体、控制与 worker 并发时的原子 If-Match。
3. 审批 digest/实例/有效期变化、审核字段不可见、撤权，以及 HUMAN_INPUT 与工具批准分离。
4. 写后断连、取消/到期仍保留 UNKNOWN、错误 evidence 拒绝、对账绝不调用远端写工具。
5. 两 worker 领取、共享额度、进程重启、等待到期、事件分页/保留过期与字段脱敏。

本版不生成客户端 SDK；可使用普通 HTTP 客户端调用。应用凭据与用户 JWT 为 AND 鉴权，详见 OpenAPI securitySchemes。
