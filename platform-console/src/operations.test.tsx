import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Operations } from "./Operations";
import type { Principal } from "./types";

const principal: Principal = {
  application: "console",
  project: "project",
  subject: "operator",
  permissions: ["catalog:read", "operations:admin"],
};
const policy = {
  payloadDays: 30,
  archiveDays: 30,
  traceDays: 14,
  auditDays: 180,
  backupDays: 30,
};
const overview = {
  project: "project",
  scope: "PROJECT",
  generatedAt: Date.now(),
  statusCounts: { QUEUED: 2, RUNNING: 3, NEEDS_ATTENTION: 1 },
  queue: { queued: 2, oldestAgeMs: 4000 },
  usage: {
    reservedTokens: 1200,
    chargedTokens: 500,
    modelCalls: 6,
    toolCalls: 2,
  },
  capabilities: { admin: true },
  retention: policy,
  backup: { status: "EXTERNAL_EVIDENCE_REQUIRED" },
  alerts: [
    {
      id: "UNRESOLVED_EXECUTION",
      state: "FIRING",
      severity: "ACTION_REQUIRED",
      value: 1,
      revision: 4,
      observedAt: Date.now(),
      acknowledged: false,
      occurrences: 2,
    },
  ],
  execution: {
    databaseTime: Date.now(),
    modelConcurrency: 5,
    calendarBudgetConfigured: true,
    workers: [
      {
        workerId: "worker-one",
        state: "RUNNING",
        heartbeatAt: Date.now(),
        activeCount: 3,
        fingerprintMatches: true,
      },
    ],
    pools: [
      {
        poolId: "model:deepseek",
        requestsInMinute: 6,
        openUntil: 0,
        occupied: 3,
        unknown: 1,
      },
    ],
    budgets: [
      {
        scope: "project:project",
        period: "D:2026-09-20",
        reservedTokens: 1200,
        chargedTokens: 500,
        limitTokens: 20000,
      },
    ],
    unknownPermits: [
      {
        permitId: "permit-unknown",
        runId: "run-unknown",
        poolId: "model:deepseek",
        updatedAt: Date.now(),
      },
    ],
  },
};
const plan = {
  planId: "plan-reviewed",
  digest: "a".repeat(64),
  createdAt: Date.now(),
  expiresAt: Date.now() + 900000,
  policy,
  scannedCount: 4,
  traceCount: 4,
  pinnedCount: 2,
  hasMore: true,
  nextCursor: "cursor-v1",
  archiveAvailable: true,
  candidates: [
    {
      runId: "run-old",
      revision: 4,
      terminalAt: Date.now() - 200 * 86400000,
      action: "ARCHIVE_PAYLOAD",
      pruneAudit: true,
      traceCount: 4,
    },
  ],
  expiredArchives: ["archive-expired"],
  expiredAudit: ["audit-expired"],
  expiredInbox: ["inbox-expired"],
};
function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe("operations governance and reviewed retention", () => {
  it("keeps non-admin views free of administrative controls and shared execution details", async () => {
    const fetch = vi.fn(async () =>
      response({
        ...overview,
        capabilities: { admin: false },
        scope: "OWN_TASKS",
      }),
    );
    vi.stubGlobal("fetch", fetch);
    render(
      <Operations
        project="project"
        principal={{ ...principal, permissions: ["catalog:read"] }}
      />,
    );
    await screen.findByText(/当前应用与用户的运行视图/);
    expect(
      screen.queryByRole("button", { name: "确认已知晓" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /预览本批清理计划/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "执行节点" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("permit-unknown")).not.toBeInTheDocument();
    expect(fetch.mock.calls).toHaveLength(1);
  });

  it("renders actual worker, pool, calendar ledger and unknown-permit fields with their accounting limits", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => response(overview)),
    );
    render(<Operations project="project" principal={principal} />);
    await screen.findByText("worker-one");
    expect(screen.getByText("与当前配置一致")).toBeInTheDocument();
    expect(screen.getByText(/平台模型并发上限：5/)).toBeInTheDocument();
    expect(screen.getByText("D:2026-09-20")).toBeInTheDocument();
    expect(screen.getByText("20,000")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "run-unknown" })).toHaveAttribute(
      "href",
      "#/runs/run-unknown",
    );
    expect(screen.getByText("permit-unknown")).toBeInTheDocument();
    expect(
      screen.getByText(/日与月是同一用量的不同周期视图/),
    ).toBeInTheDocument();
    expect(screen.getByText(/需要备份作业与隔离恢复证据/)).toBeInTheDocument();
  });

  it("requires review of combined payload, audit, trace and expired archive actions before applying exact plan", async () => {
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/retention/preview")) return response(plan);
      if (path.endsWith("/retention/apply"))
        return response({
          status: "APPLIED",
          archivedRuns: 1,
          deletedTraces: 4,
          deletedArchives: 1,
          deletedAuditRows: 3,
        });
      if (init.method === "GET") return response(overview);
      throw new Error("Unexpected request");
    });
    vi.stubGlobal("fetch", fetch);
    render(<Operations project="project" principal={principal} />);
    fireEvent.click(
      await screen.findByRole("button", { name: /预览本批清理计划/ }),
    );
    const apply = await screen.findByRole("button", {
      name: "执行已核对的计划",
    });
    expect(apply).toBeDisabled();
    expect(screen.getByText(/归档再保留 30 天/)).toBeInTheDocument();
    expect(
      screen.getByText("清理该任务到期审计与执行事件"),
    ).toBeInTheDocument();
    expect(screen.getByText("清理 4 条到期诊断记录")).toBeInTheDocument();
    expect(screen.getByText("archive-expired")).toBeInTheDocument();
    expect(screen.getByText("audit-expired")).toBeInTheDocument();
    expect(screen.getByText("inbox-expired")).toBeInTheDocument();
    expect(
      fetch.mock.calls.filter(([path]) => path.endsWith("/retention/apply")),
    ).toHaveLength(0);
    fireEvent.click(
      screen.getByRole("checkbox", { name: /我已核对上述全部动作/ }),
    );
    fireEvent.click(apply);
    await screen.findByText(/计划已执行/);
    const command = fetch.mock.calls.find(([path]) =>
      path.endsWith("/retention/apply"),
    )!;
    expect(JSON.parse(command[1].body as string)).toEqual({
      planId: plan.planId,
      digest: plan.digest,
    });
    expect(
      (command[1].headers as Record<string, string>)["Idempotency-Key"],
    ).toBeTruthy();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("previews the next cursor without applying the previous reviewed plan", async () => {
    let previews = 0;
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.endsWith("/retention/preview")) {
        previews++;
        return response(
          previews === 1
            ? plan
            : {
                ...plan,
                planId: "next-plan",
                nextCursor: undefined,
                hasMore: false,
              },
        );
      }
      if (init.method === "GET") return response(overview);
      throw new Error("No mutation expected");
    });
    vi.stubGlobal("fetch", fetch);
    render(<Operations project="project" principal={principal} />);
    fireEvent.click(
      await screen.findByRole("button", { name: /预览本批清理计划/ }),
    );
    fireEvent.click(await screen.findByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "预览下一批" }));
    await waitFor(() => expect(previews).toBe(2));
    await waitFor(() => expect(screen.getByRole("checkbox")).not.toBeChecked());
    expect(
      screen.getByRole("button", { name: "执行已核对的计划" }),
    ).toBeDisabled();
    const calls = fetch.mock.calls.filter(([path]) =>
      path.endsWith("/retention/preview"),
    );
    expect(JSON.parse(calls[1][1].body as string)).toEqual({
      limit: 100,
      after: "cursor-v1",
    });
    expect(
      fetch.mock.calls.some(([path]) => path.endsWith("/retention/apply")),
    ).toBe(false);
  });

  it("blocks unknown acknowledgements across reload and retains blocking on missing exact receipt", async () => {
    let receipts = 0;
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.includes("/commands/"))
        return ++receipts === 1
          ? response({ code: "NOT_FOUND" }, 404)
          : response({ status: "ACKNOWLEDGED" });
      if (path.endsWith("/ack")) throw new TypeError("lost response");
      if (init.method === "GET") return response(overview);
      throw new Error("Unexpected request");
    });
    vi.stubGlobal("fetch", fetch);
    let view = render(<Operations project="project" principal={principal} />);
    fireEvent.click(await screen.findByRole("button", { name: "确认已知晓" }));
    await screen.findByText(/有一项操作结果尚未确认/);
    await screen.findByText(/请求结果尚未确认/);
    const ack = fetch.mock.calls.find(([path]) => path.endsWith("/ack"))!;
    const key = (ack[1].headers as Record<string, string>)["Idempotency-Key"];
    expect((ack[1].headers as Record<string, string>)["If-Match"]).toBe('"o4"');
    const stored = sessionStorage.getItem(sessionStorage.key(0)!)!;
    expect(JSON.parse(stored)).toEqual({ key, resource: "operations" });
    view.unmount();
    view = render(<Operations project="project" principal={principal} />);
    expect(
      await screen.findByRole("button", { name: "确认已知晓" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: /预览本批清理计划/ }),
    ).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "核对原操作回执" }));
    await screen.findByText(/资源不存在/);
    expect(screen.getByRole("button", { name: "确认已知晓" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "核对原操作回执" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "确认已知晓" })).toBeEnabled(),
    );
    expect(
      fetch.mock.calls.filter(([path]) => path.endsWith("/ack")),
    ).toHaveLength(1);
    expect(
      fetch.mock.calls
        .filter(([path]) => path.includes("/commands/"))
        .every(([path]) => path.endsWith(key)),
    ).toBe(true);
    expect(sessionStorage.length).toBe(0);
  });

  it("cannot apply payload archival when the encryption key is unavailable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        response(
          path.endsWith("/retention/preview")
            ? { ...plan, archiveAvailable: false }
            : overview,
        ),
      ),
    );
    render(<Operations project="project" principal={principal} />);
    fireEvent.click(
      await screen.findByRole("button", { name: /预览本批清理计划/ }),
    );
    fireEvent.click(await screen.findByRole("checkbox"));
    expect(
      screen.getByRole("button", { name: "执行已核对的计划" }),
    ).toBeDisabled();
    expect(screen.getByText(/归档密钥尚未配置/)).toBeInTheDocument();
  });
});
