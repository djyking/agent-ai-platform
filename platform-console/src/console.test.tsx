import {
  fireEvent,
  render,
  screen,
  waitFor,
  cleanup,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResourceEditor } from "./Catalog";
import { JsonField, SpecEditor } from "./SpecEditor";
import type { Principal, Resource } from "./types";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
const principal: Principal = {
  application: "application",
  project: "project",
  subject: "user",
  permissions: [
    "catalog:read",
    "catalog:write",
    "catalog:validate",
    "catalog:publish",
  ],
};
const resource: Resource = {
  type: "RunPolicy",
  id: "limits",
  name: "预算策略",
  revision: 1,
  status: "DRAFT",
  spec: {
    maxTokens: 100,
    maxModelCalls: 1,
    maxToolCalls: 2,
    maxSteps: 10,
    lifetimeSeconds: 60,
  },
  regressionCases: [],
  validation: null,
  versions: [],
  disabled: false,
};
function response(body: unknown, etag = '"c1"', status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ETag: etag },
  });
}

describe("console behavior", () => {
  it("re-reads the exact new draft after an uncertain save and stays blocked until a successful read", async () => {
    let finishRead: (reply: Response) => void = () => {};
    let reads = 0;
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/capabilities"))
        return response({ templates: { RunPolicy: resource.spec } });
      if (path.endsWith("/resources")) return response({ items: [] });
      if (init.method === "PUT") throw new TypeError("Connection lost");
      if (path.endsWith("/RunPolicy/new-limits")) {
        reads++;
        if (reads === 1) throw new TypeError("Read unavailable");
        return new Promise<Response>((resolve) => {
          finishRead = resolve;
        });
      }
      throw new Error(`Unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetch);
    render(
      <ResourceEditor
        project="project"
        type="RunPolicy"
        principal={principal}
      />,
    );
    await waitFor(() =>
      expect(screen.getByLabelText(/显示名称/)).not.toHaveValue(""),
    );
    fireEvent.change(screen.getByLabelText(/资源标识/), {
      target: { value: "new-limits" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await screen.findByText(/请求结果尚未确认/);
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/资源标识/), {
      target: { value: "changed-after-failure" },
    });
    fireEvent.click(screen.getByRole("button", { name: "重新读取" }));
    await screen.findByText(/无法连接平台/);
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "重新读取" }));
    await waitFor(() => expect(reads).toBe(2));
    expect(screen.getByRole("button", { name: "处理中…" })).toBeDisabled();
    expect(
      fetch.mock.calls.filter(([, init]) => init.method === "PUT"),
    ).toHaveLength(1);
    finishRead(
      response({ ...resource, id: "new-limits", revision: 4 }, '"c4"'),
    );
    await screen.findByText(/已读取当前资源/);
    expect(screen.getByLabelText(/资源标识/)).toHaveValue("new-limits");
    expect(screen.getByRole("button", { name: "校验草稿" })).toBeEnabled();
    expect(
      fetch.mock.calls.filter(([, init]) => init.method === "PUT"),
    ).toHaveLength(1);
  });
  it("pins a new reference to the newest enabled release when the backend returns descending versions", () => {
    const changed = vi.fn();
    render(
      <SpecEditor
        type="Agent"
        value={{}}
        onChange={changed}
        resources={[
          {
            ...resource,
            type: "Workflow",
            id: "workflow",
            name: "工作流",
            versions: [3, 2, 1].map((version) => ({
              version,
              digest: `sha256:${version}`,
              publishedAt: "2026-09-18T00:00:00Z",
              disabled: version === 3,
            })),
          },
        ]}
      />,
    );
    fireEvent.change(screen.getByLabelText("工作流"), {
      target: { value: "workflow" },
    });
    expect(changed).toHaveBeenLastCalledWith({
      workflow: { id: "workflow", version: 2 },
    });
  });
  it("publishes only after the real passed validation flag and updated ETag are read", async () => {
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/capabilities"))
        return response({
          types: ["RunPolicy"],
          templates: { RunPolicy: resource.spec },
        });
      if (path.endsWith("/resources")) return response({ items: [resource] });
      if (path.endsWith("/validate"))
        return response(
          {
            ...resource,
            revision: 2,
            status: "VALIDATED",
            validation: { passed: true },
          },
          '"c2"',
        );
      if (path.endsWith("/publish"))
        return response(
          {
            ...resource,
            revision: 3,
            status: "PUBLISHED",
            validation: { passed: true },
            versions: [
              {
                version: 1,
                digest: "sha256:published",
                publishedAt: "2026-09-18T00:00:00Z",
                disabled: false,
              },
            ],
          },
          '"c3"',
        );
      return response(resource);
    });
    vi.stubGlobal("fetch", fetch);
    render(
      <ResourceEditor
        project="project"
        type="RunPolicy"
        id="limits"
        principal={principal}
      />,
    );
    await screen.findByText("预算策略");
    expect(screen.getByRole("button", { name: "发布新版本" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "校验草稿" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "发布新版本" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "发布新版本" }));
    fireEvent.click(screen.getByRole("button", { name: "确认发布当前草稿" }));
    await waitFor(() =>
      expect(fetch.mock.calls.some(([path]) => path.endsWith("/publish"))).toBe(
        true,
      ),
    );
    const publish = fetch.mock.calls.find(([path]) =>
      path.endsWith("/publish"),
    )!;
    expect((publish[1].headers as Record<string, string>)["If-Match"]).toBe(
      '"c2"',
    );
    expect(publish[1].body).toBe("{}");
  });
  it("keeps catalog mutations disabled for a read-only current identity", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        path.endsWith("/resources")
          ? response({ items: [] })
          : path.endsWith("/capabilities")
            ? response({ templates: {} })
            : response(resource),
      ),
    );
    render(
      <ResourceEditor
        project="project"
        type="RunPolicy"
        id="limits"
        principal={{ ...principal, permissions: ["catalog:read"] }}
      />,
    );
    await screen.findByText("预算策略");
    expect(screen.getByRole("button", { name: "校验草稿" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "紧急撤权" })).toBeDisabled();
  });
  it("marks malformed or non-object input invalid instead of silently submitting an old object", () => {
    const changed = vi.fn();
    const valid = vi.fn();
    render(
      <JsonField
        label="运行输入"
        value={{}}
        onChange={changed}
        objectOnly
        onValidity={valid}
      />,
    );
    fireEvent.change(screen.getByLabelText("运行输入"), {
      target: { value: "{ broken" },
    });
    expect(changed).not.toHaveBeenCalled();
    expect(valid).toHaveBeenLastCalledWith(false);
    expect(screen.getByLabelText("运行输入")).toBeInvalid();
    fireEvent.change(screen.getByLabelText("运行输入"), {
      target: { value: '"not an object"' },
    });
    expect(valid).toHaveBeenLastCalledWith(false);
    fireEvent.change(screen.getByLabelText("运行输入"), {
      target: { value: '{"query":"safe"}' },
    });
    expect(valid).toHaveBeenLastCalledWith(true);
    expect(changed).toHaveBeenLastCalledWith({ query: "safe" });
  });
});
