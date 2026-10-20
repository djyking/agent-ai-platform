# 独立客服应用

`support-pilot` 是独立 Java 17 客服应用，通过 `harness-platform-client` 调用中台，并使用现有 OpsAgent 账号、验证码和项目授权。浏览器只持有本应用的 HttpOnly 会话 cookie 与 CSRF token；应用凭据、Ops JWT、密码和刷新令牌均不返回浏览器。

当前业务范围为**已发布客服 Agent 的授权知识问答及人工审核**。知识来源由中台发布版本和工具配置决定，可接 OpsAgent 已有知识服务。应用没有内置 FAQ 答案，也不会将合成订单当作真实业务接口。Ops 的 `demo-order` 属于运维故障注入应用，不是客户订单系统；当前未接订单查询、退款、订单修改或客户消息发送。联调用的业务文档和问题必须明确标记为合成数据。

## 启动与配置

```powershell
.\mvnw.cmd -pl support-pilot -am package
java -jar support-pilot/target/support-pilot-0.1.0-SNAPSHOT.jar
```

实际凭据通过受保护的进程环境注入，不放在命令参数、Git 或日志中。

| 环境变量 | 含义 |
| --- | --- |
| `SUPPORT_PLATFORM_ORIGIN` | 中台 origin，HTTPS 或本机 HTTP |
| `SUPPORT_IDENTITY_ORIGIN` | 现有 Ops Auth origin，HTTPS 或本机 HTTP |
| `SUPPORT_PROJECT` | 已授权客服项目，默认 `support-pilot` |
| `SUPPORT_APPLICATION_SECRET` | 已在 Ops 身份桥登记的独立应用凭据 |
| `SUPPORT_PORT` | 本机监听端口，默认 `8098` |
| `SUPPORT_AUTH_MODE` | 默认 `opsagent`；正式模式不接收共享用户 token 或访问码 |
| `SUPPORT_RUNTIME_MODE` | `development`（默认）、`staging`、`production` |
| `SUPPORT_PUBLIC_ORIGIN` | 固定公开 origin；staging/production 必须为 HTTPS |

服务始终绑定 `127.0.0.1`。公网部署由同机 nginx 终止 TLS 并反代；Host 和写请求 Origin 必须精确匹配 `SUPPORT_PUBLIC_ORIGIN`。应用不从任意转发头推断可信 origin。HTTPS origin 下 cookie 自动设置 Secure；同时保留 HttpOnly、SameSite=Strict 和一小时有效期。只有显式 `development + local-test` 才能使用旧访问码，生产和预发布配置会拒绝它。

本机正式联调可省略 `SUPPORT_PUBLIC_ORIGIN`，访问 `http://127.0.0.1:<SUPPORT_PORT>/`。登录界面复用 Ops 的 `/api/auth/captcha` 和 `/api/auth/login`；客服 BFF 接收密码后只向固定身份源转发一次，不保存密码、不创建用户，不向浏览器返回上游 token，也不自动续期。

本机测试模式还需显式配置 `SUPPORT_OPERATOR_USER_TOKEN` / `SUPPORT_OPERATOR_ACCESS_CODE`，可选 `SUPPORT_REVIEWER_USER_TOKEN` / `SUPPORT_REVIEWER_ACCESS_CODE`。访问码至少 24 字符且必须不同；这些环境仅用于合成隔离测试，正式模式不得设置共享 token。

## 身份、发布与权限

先由现有身份管理流程登记客服应用、项目及用户授权，再在中台发布可调用助手。运行时使用登录者自己的 Ops 用户 token 和独立客服应用凭据。登录和每个已登录业务请求都会重新 introspect 当前授权；撤权或身份源故障时不使用历史权限放行。平台 API 仍独立验证每次操作。

- 客服模式要求 `runs:create`、`runs:read`、`runs:list`，具体模型、工具、知识权限由发布契约和现有授权补齐。
- 审核模式要求 `approvals:read`、`approvals:decide`；完整人工审阅还须按审批策略授予 `approvals:review`。选择审核模式不授予新权限；审核员不能通过客服 BFF 创建咨询。
- 每次登录使用独立会话，按 application/project/subject 隔离平台运行记录。审核员只能通过审批接口核对分配给自己的 Run，不获得其他用户完整任务输出的通用访问权。
- 退出删除本应用的服务器会话；身份源故障时也可退出。它不代表注销用户其他应用中的 Ops 会话。

页面从 `/catalog/releases` 发现当前可调用发布，随原请求保存精确 `releaseRef`。客服 BFF 固定要求平台 `requirePublished:true`：平台先核对同键回执，仅对新键验证当前正式发布、启用状态和授权；作者自己的预览与评测快照也不能作为客服新咨询调用。这样发布停用后仍能核对旧请求，且不会重新创建任务。同键变更该约束或输入返回冲突。选择标记 `isDefault` 的正式版本，不从浏览器接受模型地址、工具定义、身份或权限。发布含人工确认节点时，客服把 Run 编号交给已授权审核员；审核员核验完整审批内容并携带当前 ETag、digest、审批 ID 与幂等键决定。

## 请求结果与浏览器边界

不确定的写操作先保存原请求再发送，按 application/project/subject/role 存放在当前标签页 sessionStorage；刷新后只恢复阻塞提示，不自动重放。只有用户明确点击原请求重试才再次发送同一个幂等键和内容。不同用户不会加载前一用户的待确认命令。登录切换和乱序响应也不会把旧用户结果渲染到新会话。

所有内容使用 DOM `textContent`/表单属性渲染，不执行业务返回的 HTML。CSP 仅允许同源脚本和样式；验证码只接收有界的 PNG data URL。会话 cookie 不进入 JavaScript，浏览器保存的待确认请求不包含上游 JWT 或应用凭据。

## 验证

```powershell
.\mvnw.cmd -pl support-pilot -am test
node --test support-pilot/src/test/js/approval.test.mjs support-pilot/src/test/js/auth.test.mjs
```

Java 测试走实际 HTTP，覆盖验证码/登录/introspect、每用户 token 转发、审核权限、撤权、身份故障、登出、CSRF、访问码禁用与生产配置。DOM 测试覆盖审批绑定、乱序、正式登录、切换用户以及未确认请求的刷新恢复。

实际本机联调脚本为 `validation/platform/phase6_support_acceptance.py`。私有配置放在 `.work/`，提供 `supportOrigin`、`syntheticData: true`、三个不同账号的 `operatorLogin` / `secondUserLogin` / `reviewerLogin`、目标 `agentId`、合成 `inputs` 和（需要人工输入时）`reviewInput`。每个 Login 对象仅包含 `username`、`password`、新签发的 `captchaId`、`captchaCode`。脚本不绕过验证码；在启动前通过现有登录验证码流程准备有效值。

```powershell
python validation/platform/phase6_support_acceptance.py --config .work/phase6/support.private.json --report .work/phase6/support-report.json
```

脚本验证三用户分离、精确发布、同键创建去重、跨用户 Run/事件拒绝、审核决定、完成结果和退出。写请求断连立即停止，不自动重复创建。报告只记录 Run ID 与检查状态，不记录密码、token、原始问题或回答；真实本机结果由阶段六交付记录统一汇总。该脚本不代替 24 小时负载、Linux 七天观察或真实客服质量验收。
