import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Onboarding } from "./Onboarding";
import type { Principal } from "./types";

const principal: Principal = {
  application: "console",
  project: "project-a",
  subject: "123",
  permissions: ["onboarding:request", "onboarding:admin"],
};
const entry = {
  id: "request-1",
  revision: 2,
  targetProject: "project-a",
  applicationId: "support",
  name: "客服接入",
  purpose: "知识问答",
  profile: "knowledge-reader",
  requester: "console/123",
  subject: "123",
  state: "APPROVED",
  generation: 1,
  plan: { digest: "a".repeat(64), permissions: ["runs:create"] },
};
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe("controlled application onboarding", () => {
  it("distinguishes approval from runtime activation without offering secrets", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => reply({ items: [entry] })),
    );
    render(<Onboarding project="project-a" principal={principal} />);
    expect(await screen.findByText("已批准，等待配置")).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: "验证配置与身份并启用" }),
    ).toBeNull();
    expect(screen.getByText(/页面不显示应用凭据/)).toBeTruthy();
    expect(screen.queryByLabelText("应用密码")).toBeNull();
  });

  it("requires a reason and a revision for immediate application disable", async () => {
    const calls: [string, RequestInit | undefined][] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, options?: RequestInit) => {
        calls.push([url, options]);
        if (options?.method === "POST")
          return reply({ ...entry, revision: 3, state: "DISABLED" });
        return reply({ items: [{ ...entry, state: "ACTIVE" }] });
      }),
    );
    render(<Onboarding project="project-a" principal={principal} />);
    const button = await screen.findByRole("button", { name: "停用" });
    expect((button as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("本次审批或生命周期操作的理由"), {
      target: { value: "项目退役" },
    });
    fireEvent.click(button);
    await waitFor(() =>
      expect(calls.some(([, options]) => options?.method === "POST")).toBe(
        true,
      ),
    );
    const mutation = calls.find(([, options]) => options?.method === "POST")!;
    expect(mutation[0]).toContain("/request-1/lifecycle");
    expect(mutation[1]?.body).toBe(
      JSON.stringify({ operation: "DISABLE", reason: "项目退役" }),
    );
    expect((mutation[1]?.headers as Record<string, string>)["If-Match"]).toBe(
      '"k2"',
    );
    expect(
      (mutation[1]?.headers as Record<string, string>)["Idempotency-Key"],
    ).toBeTruthy();
  });

  it("persists an unknown command and reconciles it without replay", async () => {
    let posts = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, options?: RequestInit) => {
        if (options?.method === "POST") {
          posts++;
          throw new TypeError("response lost");
        }
        if (url.includes("/commands/")) return reply({ status: "COMPLETED" });
        return reply({ items: [{ ...entry, state: "PROVISIONED" }] });
      }),
    );
    const mounted = render(
      <Onboarding project="project-a" principal={principal} />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "验证配置与身份并启用" }),
    );
    await screen.findByText("原操作结果待核对，已暂停新的提交。");
    mounted.unmount();
    render(<Onboarding project="project-a" principal={principal} />);
    expect(
      await screen.findByRole("button", { name: "核对原操作结果" }),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "核对原操作结果" }));
    await screen.findByText("原操作已完成，可以继续。");
    expect(posts).toBe(1);
    expect(sessionStorage.length).toBe(0);
  });

  it("does not expose administrative controls to ordinary applicants", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => reply({ items: [{ ...entry, state: "ACTIVE" }] })),
    );
    render(
      <Onboarding
        project="project-a"
        principal={{ ...principal, permissions: ["onboarding:request"] }}
      />,
    );
    await screen.findByText("已启用");
    expect(screen.queryByRole("button", { name: "停用" })).toBeNull();
    expect(
      screen.queryByRole("button", { name: "轮换凭据并暂停调用" }),
    ).toBeNull();
    expect(
      (screen.getByLabelText("目标项目") as HTMLInputElement).disabled,
    ).toBe(true);
  });
});
