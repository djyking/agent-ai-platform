# Harness 管理控制台

独立 React + TypeScript + Vite 应用，构建产物由平台服务在同源 `/console/` 下提供。
界面为中文，包含真实资源目录、结构化 Workflow 编辑、发布与回归、运行账本、审批、人工输入、UNKNOWN 对账、持久化调用轨迹和项目权限视图。

## 构建与验证

使用 Node.js 24 LTS（至少 24.15.0）与 pnpm 11.19.0。依赖与版本由 `pnpm-lock.yaml` 固定。

```powershell
pnpm install --frozen-lockfile
pnpm test
pnpm build
```

`dist/` 不检入 Git。主服务打包流程应在构建前执行上述命令，然后把 `dist/` 的内容放到服务静态资源的 `console/` 目录。前端不直接引用原 OpsAgent 服务或外部字体、图片与分析脚本。

本机开发可以运行 `pnpm dev`；Vite 将 `/console/session`、`/console/login`、`/console/auth/*`、`/console/api/*` 代理到 `127.0.0.1:8097`。最终集成验收必须使用平台同源页面；开发代理的 Origin/CSRF 行为仍由后端校验，不能为了开发关闭服务端校验。

开发后端地址可用服务端环境变量 `HARNESS_DEV_PROXY_TARGET` 覆盖；此变量不会注入浏览器构建产物。验收后端使用 8099 时：

```powershell
$env:HARNESS_DEV_PROXY_TARGET = 'http://127.0.0.1:8099'
pnpm dev
```

开发页面为 `http://127.0.0.1:5173/console/`（端口被占用时以 Vite 实际输出为准）；生产不依赖 Vite，直接使用平台的 `/console/`。

## 身份与网络边界

- 主登录方式为已有 OpsAgent 账号、密码及图形验证码。验证码来自 `GET /console/auth/captcha`，登录走 `POST /console/login`。
- 高级入口允许已有访问令牌通过 `POST /console/session` 换取会话。密码、令牌仅存在于表单瞬时状态，提交完成即清空；不写入 `localStorage` 或 `sessionStorage`。
- 应用凭据与用户 JWT 留在服务端。浏览器使用同源 HttpOnly cookie 与内存中的 CSRF token，不能读取或拼装 Bearer 身份头。
- 切换项目使用已验证会话 `POST /console/session {projectId}`；服务端重新认证并轮换会话和 CSRF。页面不提供账号、角色或授权来源的替代实现。
- 所有业务读取与写入经 `/console/api/projects/{project}/...` BFF；错误、未授权、无数据和加载状态均有明确表现。不包含用于展示的内置业务假数据。
- API 客户端不自动重试请求、不跟随重定向。写操作使用新的显式操作幂等键，带当前视图 ETag；超时、连接断开或响应体中断会显示“结果未确认”，要求先读取当前状态并核对记录。
- 按钮依据已知权限限制操作，但授权始终由服务端重新校验。不可见记录不会借助前端状态绕过权限。

## 配置发布

八类资源：Agent、ModelProfile、Prompt、ToolConnection、ToolPolicy、RetrievalProfile、Workflow、RunPolicy。草稿读写与 validate/publish/default/disable/revoke 均使用 Catalog API 和 `"c{revision}"` ETag。

模型与工具只能从服务端 capabilities 返回的受信目录中选择。Agent/Workflow 使用精确 `{id,version}` 引用。Workflow 提供 tool/model/agent/condition/human/end 节点表单，重命名节点会同时更新起点和连接；悬空引用显示警示并交给后端校验。

回归表单支持固定工具结果、模型回答、人工输入、预期输出、工具列表、精确工具参数及完整模型消息。发布按钮仅在服务端 `validation.passed=true` 且当前草稿未修改时启用。版本可查看原始配置、比较差异、停用/恢复或切换 Agent 默认版本。

普通停用阻止新运行；紧急撤权影响已有运行的后续调用。两种操作在不同入口说明范围，均呈现精确目标并要求显式确认。

## 运行与人工处理

- 创建运行从真实发布列表选择固定 releaseRef，显示摘要和输入 Schema；输入 JSON 无效时不能沿用旧值提交。
- 暂停、恢复、取消使用当前 Run ETag，显示控制请求与最终状态的区别。
- 工具审批精确展示摘要、修订、有效期及可审阅字段；未完整审阅不可批准。人工输入原样作为 `input`，审计理由单独作为 `reason`，拒绝时不携带输入。
- UNKNOWN 仅接受已经独立验证并导入的平台 `evidenceRef`。界面不接受工具成功断言、任意证据 URL 或原始 ToolResult，不提供重放按钮。
- 审计事件与持久化调用轨迹分开呈现。Trace 关联展示节点、调用、尝试、耗时和结果；审计仍以 Run 事件账本为准。

## 自动化测试范围

Vitest + Testing Library 覆盖 CSRF/ETag/幂等键传输、网络与响应中断后的不确定结果、禁止自动重试、会话切换、审批输入/理由隔离、Workflow 引用重命名、真实校验标志驱动发布、只读权限按钮和 JSON 校验。测试中的 HTTP fixture 仅用于行为测试；实际应用未内置 mock API 或演示运行。

真实 OpsAgent 登录、MySQL 发布/运行、跨身份权限以及浏览器交互由主任务的集成验收报告记录，不由前端单元测试宣称覆盖。
