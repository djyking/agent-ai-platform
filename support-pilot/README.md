# 独立客服助手试点

`support-pilot` 是独立 Java 17 应用，通过 `harness-platform-client` 访问运行平台。它有独立应用凭据、项目和由平台按应用/用户隔离的持久运行记录；平台控制台不计作这个业务应用。前端提供已发布助手选择、输入表单、订单/售后合成咨询、运行记录与事件、暂停/继续/取消，以及独立审核员的人工确认。

## 启动

```powershell
.\mvnw.cmd -pl support-pilot -am package
java -jar support-pilot/target/support-pilot-0.1.0-SNAPSHOT.jar
```

通过受保护的进程环境设置下列配置；不要把实际凭据写入命令参数、Git、网页或普通日志。

| 环境变量 | 含义 |
| --- | --- |
| `SUPPORT_PLATFORM_ORIGIN` | 平台 origin，例如 `http://127.0.0.1:8099` |
| `SUPPORT_PROJECT` | 专用项目，默认 `support-pilot` |
| `SUPPORT_PORT` | 客服服务端口，默认 `8098`；本机阶段三验收使用 `8100` |
| `SUPPORT_APPLICATION_SECRET` | 已在现有身份桥登记的独立客服应用凭据 |
| `SUPPORT_OPERATOR_USER_TOKEN` | 当前客服用户的现有 OpsAgent JWT |
| `SUPPORT_OPERATOR_ACCESS_CODE` | 随机客服访问码，至少 24 字符 |
| `SUPPORT_REVIEWER_USER_TOKEN` | 可选：现有审核员的 OpsAgent JWT |
| `SUPPORT_REVIEWER_ACCESS_CODE` | 启用审核员时必须设置，至少 24 字符且不同于客服访问码 |

打开 `http://127.0.0.1:<SUPPORT_PORT>/`。应用只绑定回环地址，校验精确 Host 与变更请求的 Origin。访问码只用于建立本机业务会话，不替代平台的 OpsAgent 身份/权限校验；它不能自行注册用户或创建上游权限。浏览器使用一小时的 HttpOnly、SameSite=Strict cookie 和独立 CSRF token，不接收应用凭据或上游 JWT。会话在应用重启后失效，Run 仍由平台保存。生产使用还需要正式登录/会话集成、HTTPS、访问码生命周期等部署工作。

## 发布与试用

先在平台给应用 `support-pilot` 配置独立授权，并在对应项目发布 FAQ 助手。页面通过 `/catalog/releases` 发现当前可调用发布，优先选择标记 `isDefault` 的版本，创建时由后端核对发布并提交其完整 `releaseRef`。网页不能设置模型端点、工具注册、权限或应用身份。输入表单支持字符串、数值和布尔字段；复杂输入 schema 暂不适用于客服页面。

推荐合成问题是“订单什么时候发货？”和“可以申请退货吗？”。本机词法 FAQ 语料应包含这些问句；该基线用于证明业务接入和发布流程，不是生产中文语义检索或模型质量结论。只配置只读 FAQ 与人工确认，不配置真实退款、订单修改或对外消息能力。

客服提交咨询后可刷新记录和进度。若发布含人工输入节点，客服把运行编号交给审核员；审核员用自己的访问码进入工作台，直接查询该 Run 的审批投影，核验完整内容后填写答复并确认或拒绝。审核员不需要跨用户读取整个 Run，也不能借审核页面创建咨询。

审核页面显示运行编号、审批编号、运行修订及审批摘要。修改运行编号或重新查询立即清除旧答复和说明；后到达的旧查询响应不会替换当前审批。每次提交前再次核对当前输入和已加载审批是否属于同一运行，会话失效后清除待重试请求，重新登录不会自动补交旧决定。

所有控制与决定携带当前 ETag 和明确的幂等键。断连后页面保留原请求，只有用户点击“使用同一请求重试”才再次提交；不会自动重复执行命令。ETag 冲突须刷新并重新审阅。渲染助手名称、schema 标签、用户输入、输出与事件时仅使用 DOM `textContent`/表单属性，配合禁止 inline script 的 CSP，不把内容作为 HTML 执行。

定向测试：`mvnw.cmd -pl support-pilot -am test`。安装 `platform-console` 的锁定依赖后，在仓库根目录运行 `node --test support-pilot/src/test/js/approval.test.mjs`，通过实际 DOM 事件回归审批切换、乱序、失效与原请求重试。客户端与应用 HTTP 测试使用合成身份和本机服务，真实 MySQL/OpsAgent/发布流程由平台阶段三集成验收另行记录。
