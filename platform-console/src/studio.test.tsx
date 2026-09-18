import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { ApplicationHome, ApplicationStudio } from "./Studio";
import { ConnectionManager, KnowledgeManager } from "./Shared";
import type { Principal } from "./types";
import type { StudioApplication, StudioCapabilities } from "./studio-types";
const principal: Principal = {
  application: "console",
  project: "project",
  subject: "builder",
  permissions: [
    "catalog:read",
    "catalog:write",
    "catalog:validate",
    "catalog:publish",
    "runs:create",
    "runs:read",
    "runs:output:read",
  ],
};
const capabilities: StudioCapabilities = {
  models: [
    { id: "deepseek-profile", provider: "deepseek", model: "deepseek-chat" },
  ],
  tools: [],
  knowledge: [],
};
const application: StudioApplication = {
  id: "test-app",
  name: "资料助手",
  templateId: "research",
  instructions: "依据资料准确回答",
  modelProfileId: "deepseek-profile",
  knowledgeId: "",
  toolIds: [],
  outputFormat: "markdown",
  requireReview: false,
  approvers: [],
  limits: {
    maxTokens: 12000,
    maxModelCalls: 4,
    maxToolCalls: 8,
    maxSteps: 60,
    lifetimeSeconds: 1800,
  },
  samples: [
    {
      id: "sample-a",
      name: "发货说明",
      input: { question: "何时发货" },
      expectedContains: ["工作日"],
    },
  ],
  revision: 1,
  draftDigest: "digest-v1",
  updatedAt: "2026-09-18T00:00:00Z",
  publishedVersions: [],
  disabled: false,
};
function response(body: unknown, etag = '"s1"', status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ETag: etag },
  });
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  location.hash = "";
  sessionStorage.clear();
});
describe("application Studio product workflow", () => {
  it("creates an application directly from a template using a trusted model and creation precondition", async () => {
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/templates"))
        return response({
          items: [
            {
              id: "research",
              name: "资料研究与报告",
              description: "根据资料交付报告",
              defaultDraft: {
                name: "研究助手",
                templateId: "research",
                instructions: "使用证据",
                modelProfileId: "",
              },
            },
          ],
        });
      if (path.endsWith("/capabilities")) return response(capabilities);
      if (path.endsWith("/applications")) return response({ items: [] });
      if (init.method === "PUT")
        return response({ ...application, id: path.split("/").at(-1) });
      throw new Error(`Unexpected ${path}`);
    });
    vi.stubGlobal("fetch", fetch);
    render(<ApplicationHome project="project" principal={principal} />);
    fireEvent.click(
      await screen.findByRole("button", { name: /资料研究与报告/ }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText(/模型连接/)).toHaveValue("deepseek-profile"),
    );
    fireEvent.click(screen.getByRole("button", { name: "创建并进入 Studio" }));
    await waitFor(() => expect(location.hash).toMatch(/^#\/apps\/app-/));
    const write = fetch.mock.calls.find(([, init]) => init.method === "PUT")!;
    expect((write[1].headers as Record<string, string>)["If-Match"]).toBe(
      '"s0"',
    );
    expect(JSON.parse(write[1].body as string)).toMatchObject({
      templateId: "research",
      modelProfileId: "deepseek-profile",
      outputFormat: "markdown",
    });
    expect(
      fetch.mock.calls.filter(([, init]) => init.method === "PUT"),
    ).toHaveLength(1);
  });
  it("blocks an uncertain application write across navigation and requires the exact receipt plus a successful current read", async () => {
    const app = { ...application, id: "uncertain-app" };
    let receiptReads = 0;
    let resourceReads = 0;
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/capabilities")) return response(capabilities);
      if (init.method === "PUT") throw new TypeError("lost response");
      if (path.includes("/commands/")) {
        receiptReads++;
        return receiptReads === 1
          ? response({ code: "NOT_FOUND" }, '"s1"', 404)
          : response({
              status: "COMPLETED",
              resourceId: app.id,
              operation: "save",
              response: { ...app, revision: 2 },
            });
      }
      if (path.endsWith("/uncertain-app")) {
        resourceReads++;
        return response(
          resourceReads >= 3
            ? {
                ...app,
                instructions: "已保存指令",
                revision: 2,
                draftDigest: "digest-v2",
              }
            : app,
          resourceReads >= 3 ? '"s2"' : '"s1"',
        );
      }
      throw new Error(`Unexpected ${path}`);
    });
    vi.stubGlobal("fetch", fetch);
    let view = render(
      <ApplicationStudio project="project" principal={principal} id={app.id} />,
    );
    fireEvent.change(await screen.findByLabelText(/目标与行为/), {
      target: { value: "已保存指令" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByText(/请求结果尚未确认/);
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    view.unmount();
    view = render(
      <ApplicationStudio project="project" principal={principal} id={app.id} />,
    );
    await screen.findByLabelText(/目标与行为/);
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "读取原操作结果" }));
    await screen.findByText(/资源不存在/);
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "读取原操作结果" }));
    await waitFor(() =>
      expect(screen.getByLabelText(/目标与行为/)).toHaveValue("已保存指令"),
    );
    fireEvent.change(screen.getByLabelText(/目标与行为/), {
      target: { value: "下一次修改" },
    });
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeEnabled();
    expect(
      fetch.mock.calls.filter(([, init]) => init.method === "PUT"),
    ).toHaveLength(1);
  });
  it("does not preview unsaved changes and creates a real immutable preview after saving the new revision", async () => {
    let current = { ...application, id: "preview-app" };
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/capabilities")) return response(capabilities);
      if (init.method === "PUT") {
        current = {
          ...current,
          ...JSON.parse(init.body as string),
          revision: 2,
        };
        return response(current, '"s2"');
      }
      if (path.endsWith("/preview"))
        return response(
          {
            ...current,
            revision: 3,
            lastTask: {
              id: "task-one",
              applicationId: current.id,
              runId: "run-one",
              sessionId: "session-one",
              snapshotId: "snapshot-one",
              mode: "PREVIEW",
            },
          },
          '"s3"',
        );
      if (path.endsWith("/tasks/task-one"))
        return response({
          id: "task-one",
          run: {
            status: "COMPLETED",
            output: { visibility: "DENIED", value: "SHOULD_NOT_RENDER" },
            usage: { chargedTokens: 10, modelCalls: 1, toolCalls: 0, steps: 1 },
          },
        });
      return response(current);
    });
    vi.stubGlobal("fetch", fetch);
    render(
      <ApplicationStudio
        project="project"
        principal={principal}
        id={current.id}
      />,
    );
    fireEvent.change(await screen.findByLabelText(/目标与行为/), {
      target: { value: "新版指令" },
    });
    fireEvent.change(screen.getByLabelText(/测试问题或任务/), {
      target: { value: "比较两份资料" },
    });
    expect(screen.getByRole("button", { name: "体验当前草稿" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "体验当前草稿" }),
      ).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "体验当前草稿" }));
    await screen.findByText("当前身份无法读取此任务输出。");
    expect(screen.queryByText("SHOULD_NOT_RENDER")).not.toBeInTheDocument();
    const preview = fetch.mock.calls.find(([path]) =>
      path.endsWith("/preview"),
    )!;
    expect((preview[1].headers as Record<string, string>)["If-Match"]).toBe(
      '"s2"',
    );
    expect(JSON.parse(preview[1].body as string)).toEqual({
      input: { question: "比较两份资料" },
    });
  });
  it("requires an exact current passing experiment and an explicit publication review", async () => {
    const app = {
      ...application,
      id: "publish-app",
      lastExperiment: { id: "experiment-one" },
    };
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/capabilities")) return response(capabilities);
      if (path.includes("/experiments/"))
        return response({
          id: "experiment-one",
          applicationId: app.id,
          draftDigest: app.draftDigest,
          status: "COMPLETED",
          passed: true,
          rows: [],
          contract: { passed: true },
        });
      if (path.endsWith("/publish"))
        return response(
          {
            ...app,
            revision: 2,
            publishedVersions: [
              {
                version: 1,
                draftDigest: app.draftDigest,
                publishedAt: "2026-09-18T00:00:00Z",
                releaseRef: {
                  agentId: app.id,
                  releaseId: "release-one",
                  digest: "release-digest",
                },
              },
            ],
            defaultVersion: 1,
          },
          '"s2"',
        );
      return response(app);
    });
    vi.stubGlobal("fetch", fetch);
    render(
      <ApplicationStudio project="project" principal={principal} id={app.id} />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "发布与接入" }));
    await screen.findByText("当前配置的样本评测已经通过");
    expect(
      screen.getByRole("button", { name: "确认发布当前草稿" }),
    ).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: /我已审阅/ }));
    fireEvent.click(screen.getByRole("button", { name: "确认发布当前草稿" }));
    await screen.findByText("默认版本");
    const publish = fetch.mock.calls.find(([path]) =>
      path.endsWith("/publish"),
    )!;
    expect(JSON.parse(publish[1].body as string)).toEqual({
      reviewConfirmed: true,
      evaluationId: "experiment-one",
    });
    expect((publish[1].headers as Record<string, string>)["If-Match"]).toBe(
      '"s1"',
    );
  });
  it("keeps a passing experiment from an older draft ineligible for publication", async () => {
    const app = {
      ...application,
      id: "stale-evaluation-app",
      lastExperiment: { id: "old-experiment" },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        path.endsWith("/capabilities")
          ? response(capabilities)
          : path.includes("/experiments/")
            ? response({
                id: "old-experiment",
                applicationId: app.id,
                draftDigest: "old-digest",
                status: "COMPLETED",
                passed: true,
                rows: [],
                contract: { passed: true },
              })
            : response(app),
      ),
    );
    render(
      <ApplicationStudio project="project" principal={principal} id={app.id} />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "发布与接入" }));
    expect(screen.getByRole("checkbox", { name: /我已审阅/ })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "确认发布当前草稿" }),
    ).toBeDisabled();
  });
  it("disables draft preview and edits for a user who can create published runs but cannot write the catalog", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        path.endsWith("/capabilities")
          ? response(capabilities)
          : response(application),
      ),
    );
    render(
      <ApplicationStudio
        project="project"
        principal={{
          ...principal,
          permissions: ["catalog:read", "runs:create"],
        }}
        id={application.id}
      />,
    );
    fireEvent.change(await screen.findByLabelText(/测试问题或任务/), {
      target: { value: "试用" },
    });
    expect(screen.getByLabelText(/目标与行为/)).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "体验当前草稿" })).toBeDisabled();
  });
  it("authenticates local-test login without a hard-coded workspace or invented captcha", async () => {
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path === "/console/session")
        return response({ authenticated: false });
      if (path.endsWith("/auth/captcha"))
        return response({
          required: false,
          provider: "local-test",
          notice: "仅本机隔离验收身份",
        });
      if (path === "/console/login")
        return response({
          authenticated: true,
          csrfToken: "csrf",
          principal,
          projects: ["project"],
        });
      if (path.endsWith("/templates") || path.endsWith("/applications"))
        return response({ items: [] });
      if (path.endsWith("/capabilities")) return response(capabilities);
      throw new Error(`Unexpected ${path}`);
    });
    vi.stubGlobal("fetch", fetch);
    render(<App />);
    await screen.findByText("仅本机隔离验收身份");
    expect(screen.queryByAltText("登录验证码")).not.toBeInTheDocument();
    expect(screen.getByLabelText(/工作空间标识/)).toHaveValue("");
    fireEvent.change(screen.getByLabelText(/账号/), {
      target: { value: "builder" },
    });
    fireEvent.change(screen.getByLabelText(/密码/), {
      target: { value: "test-password" },
    });
    fireEvent.click(screen.getByRole("button", { name: "进入工作空间" }));
    await screen.findByText("我的应用");
    const login = fetch.mock.calls.find(([path]) => path === "/console/login")!;
    const body = JSON.parse(login[1].body as string);
    expect(body).not.toHaveProperty("projectId");
    expect(body).not.toHaveProperty("captchaId");
    expect(body).toMatchObject({
      username: "builder",
      password: "test-password",
    });
  });
  it("clears subject restrictions when deliberately creating public workspace knowledge", async () => {
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/collections")) return response({ items: [] });
      if (init.method === "PUT")
        return response(
          {
            ...JSON.parse(init.body as string),
            id: path.split("/").at(-1),
            revision: 1,
            retrieval: "LEXICAL_TEXT_V1",
          },
          '"k1"',
        );
      if (path.endsWith("/documents")) return response({ items: [] });
      throw new Error(`Unexpected ${path}`);
    });
    vi.stubGlobal("fetch", fetch);
    render(<KnowledgeManager project="project" principal={principal} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "创建知识集合" }),
    );
    fireEvent.change(screen.getByLabelText(/集合名称/), {
      target: { value: "公开帮助资料" },
    });
    fireEvent.change(screen.getByLabelText(/资料可见范围/), {
      target: { value: "PUBLIC" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存集合" }));
    await screen.findByRole("button", { name: "新增文本资料" });
    const write = fetch.mock.calls.find(([, init]) => init.method === "PUT")!;
    expect(JSON.parse(write[1].body as string)).toMatchObject({
      visibility: "PUBLIC",
      allowedSubjects: [],
    });
    expect((write[1].headers as Record<string, string>)["If-Match"]).toBe(
      '"k0"',
    );
  });
  it("does not unlock a connection with an absent or mismatched mutation receipt", async () => {
    const connection = {
      id: "connection-uncertain",
      kind: "model",
      name: "DeepSeek",
      enabled: true,
      revision: 0,
      adapter: "deepseek",
      credentialConfigured: true,
      targetApproved: true,
      approvedBindingDigest: "binding",
    };
    let receipts = 0;
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/connections"))
        return response({ items: [connection] }, '"k0"');
      if (init.method === "PUT") throw new TypeError("write response missing");
      if (path.includes("/commands/")) {
        receipts++;
        return response(
          receipts === 1
            ? { status: "NOT_FOUND" }
            : {
                status: "COMPLETED",
                kind: "connection-model",
                id: receipts === 2 ? "some-other-connection" : connection.id,
                operation: "save",
                revision: 1,
              },
        );
      }
      if (path.endsWith(`/model/${connection.id}`))
        return response(
          { ...connection, name: "已确认名称", revision: 1 },
          '"k1"',
        );
      throw new Error(`Unexpected ${path}`);
    });
    vi.stubGlobal("fetch", fetch);
    render(<ConnectionManager project="project" principal={principal} />);
    fireEvent.change(
      await screen.findByLabelText(`连接名称 ${connection.id}`),
      { target: { value: "已确认名称" } },
    );
    fireEvent.click(screen.getByRole("button", { name: "保存名称" }));
    await screen.findByText(/请求结果尚未确认。请先读取/);
    fireEvent.click(screen.getByRole("button", { name: "读取原操作结果" }));
    await screen.findByText(/原操作尚未完成核验/);
    expect(screen.getByRole("button", { name: "保存名称" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "读取原操作结果" }));
    await screen.findByText(/原操作资源不匹配/);
    expect(screen.getByRole("button", { name: "保存名称" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "读取原操作结果" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "保存名称" })).toBeEnabled(),
    );
    expect(
      fetch.mock.calls.filter(([, init]) => init.method === "PUT"),
    ).toHaveLength(1);
  });
  it("restores a pending application command from session storage after a page reload", async () => {
    const id = "page-reloaded-app";
    const { persistPending } = await import("./pending-commands");
    persistPending("studio", `project/console/builder/${id}`, {
      key: "lost-operation-0001",
      resource: id,
    });
    const fetch = vi.fn(async (path: string, init: RequestInit) =>
      path.endsWith("/capabilities")
        ? response(capabilities)
        : path.includes("/commands/")
          ? response({ code: "NOT_FOUND" }, '"s1"', 404)
          : response({ ...application, id }),
    );
    vi.stubGlobal("fetch", fetch);
    render(
      <ApplicationStudio project="project" principal={principal} id={id} />,
    );
    await screen.findByRole("button", { name: "读取原操作结果" });
    expect(screen.getByLabelText(/目标与行为/)).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "读取原操作结果" }));
    await screen.findByText(/资源不存在/);
    expect(
      fetch.mock.calls.filter(([, init]) => init.method !== "GET"),
    ).toHaveLength(0);
  });
  it("renders a permission-redacted experiment without leaking or dereferencing its input", async () => {
    const app = {
      ...application,
      id: "redacted-experiment-app",
      lastExperiment: { id: "redacted-experiment" },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        path.endsWith("/capabilities")
          ? response(capabilities)
          : path.includes("/experiments/")
            ? response({
                id: "redacted-experiment",
                applicationId: app.id,
                draftDigest: app.draftDigest,
                status: "COMPLETED",
                passed: false,
                contract: { passed: true },
                rows: [
                  {
                    sampleId: "sample-hidden",
                    name: "权限已撤销",
                    candidate: {
                      taskId: "task-hidden",
                      runId: "run-hidden",
                      status: "COMPLETED",
                      visibility: "DENIED",
                      passed: false,
                      output: "DO_NOT_SHOW",
                    },
                  },
                ],
              })
            : response(app),
      ),
    );
    render(
      <ApplicationStudio project="project" principal={principal} id={app.id} />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "评测" }));
    await screen.findByText("当前身份无权读取此样本输入。");
    expect(screen.queryByText("DO_NOT_SHOW")).not.toBeInTheDocument();
  });
  it("labels the latest result by its actual task mode instead of presenting a release run as draft preview", async () => {
    const app = {
      ...application,
      id: "released-source-app",
      lastTask: {
        id: "released-task",
        runId: "released-run",
        sessionId: "session-release",
        applicationId: "released-source-app",
        snapshotId: "published-snapshot",
        mode: "RELEASE",
      },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        path.endsWith("/capabilities")
          ? response(capabilities)
          : path.endsWith("/tasks/released-task")
            ? response({
                id: "released-task",
                mode: "RELEASE",
                input: { question: "已发布问题" },
                run: {
                  status: "COMPLETED",
                  output: { visibility: "AVAILABLE", value: "实际发布结果" },
                  usage: {
                    chargedTokens: 3,
                    modelCalls: 1,
                    toolCalls: 0,
                    steps: 1,
                  },
                },
              })
            : response(app),
      ),
    );
    render(
      <ApplicationStudio project="project" principal={principal} id={app.id} />,
    );
    await screen.findByText("实际发布结果");
    expect(screen.getByText("已发布任务")).toBeInTheDocument();
    expect(screen.queryByText("真实草稿预览")).not.toBeInTheDocument();
  });
});
