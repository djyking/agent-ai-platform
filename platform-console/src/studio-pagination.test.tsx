import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useStudioTasks } from "./studio-api";
import { TaskWorkspace } from "./Studio";
import type { Principal } from "./types";

function page(id: string, next: string | null, cursor: string) {
  return new Response(
    JSON.stringify({
      items: [
        {
          id,
          applicationId: "assistant",
          runId: `run-${id}`,
          mode: "RELEASE",
          sessionId: "session",
          snapshotId: "snapshot",
        },
      ],
      nextCursor: next,
      pageCursor: cursor,
      hasMore: next !== null,
    }),
  );
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  location.hash = "";
});

describe("Studio task pagination", () => {
  it("re-fetches previous pages within the original snapshot and refresh starts a new snapshot", async () => {
    const fetch = vi.fn(async (path: string) => {
      const cursor = new URL(path, "http://local").searchParams.get("cursor");
      return cursor === "next-token"
        ? page("second", null, "second-page")
        : page("first", "next-token", "snapshot-first");
    });
    vi.stubGlobal("fetch", fetch);
    const { result } = renderHook(() =>
      useStudioTasks("project", "client/alice", "assistant"),
    );
    await waitFor(() => expect(result.current.data?.items[0].id).toBe("first"));
    act(() => result.current.next());
    await waitFor(() =>
      expect(result.current.data?.items[0].id).toBe("second"),
    );
    expect(result.current.page).toBe(2);
    act(() => result.current.previous());
    await waitFor(() => expect(result.current.data?.items[0].id).toBe("first"));
    expect(
      new URL(fetch.mock.calls.at(-1)![0], "http://local").searchParams.get(
        "cursor",
      ),
    ).toBe("snapshot-first");
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(
      new URL(fetch.mock.calls.at(-1)![0], "http://local").searchParams.has(
        "cursor",
      ),
    ).toBe(false);
    expect(result.current.page).toBe(1);
  });

  it("clears cursor, history and old payload when actor or application changes", async () => {
    let resolveOld: ((response: Response) => void) | undefined;
    const fetch = vi.fn((path: string) => {
      const url = new URL(path, "http://local");
      if (url.searchParams.get("cursor") === "next-token")
        return new Promise<Response>((resolve) => {
          resolveOld = resolve;
        });
      return Promise.resolve(
        page(
          url.searchParams.get("applicationId") ?? "all",
          "next-token",
          "first",
        ),
      );
    });
    vi.stubGlobal("fetch", fetch);
    const { result, rerender } = renderHook(
      ({ actor, app }) => useStudioTasks("project", actor, app),
      { initialProps: { actor: "client/alice", app: "one" } },
    );
    await waitFor(() => expect(result.current.data?.items[0].id).toBe("one"));
    act(() => result.current.next());
    await waitFor(() => expect(resolveOld).toBeDefined());
    rerender({ actor: "client/bob", app: "two" });
    expect(result.current.data).toBeUndefined();
    await waitFor(() => expect(result.current.data?.items[0].id).toBe("two"));
    await act(async () => resolveOld!(page("alice-private", null, "stale")));
    expect(result.current.data?.items[0].id).toBe("two");
    expect(result.current.page).toBe(1);
    expect(result.current.hasPrevious).toBe(false);
    expect(
      new URL(fetch.mock.calls.at(-1)![0], "http://local").searchParams.has(
        "cursor",
      ),
    ).toBe(false);
  });

  it("renders reachable next/previous controls and server-side application filter", async () => {
    const fetch = vi.fn(async (path: string) => {
      const url = new URL(path, "http://local");
      if (url.pathname.endsWith("/applications"))
        return new Response(
          JSON.stringify({
            items: [
              {
                id: "assistant",
                name: "助手",
                defaultVersion: 0,
                disabled: false,
              },
            ],
          }),
        );
      return url.searchParams.has("cursor")
        ? page("second", null, "second-page")
        : page("first", "next-token", "first-page");
    });
    vi.stubGlobal("fetch", fetch);
    const principal: Principal = {
      application: "client",
      project: "project",
      subject: "alice",
      permissions: ["runs:read"],
    };
    render(<TaskWorkspace project="project" principal={principal} />);
    await screen.findByText(/任务 first/);
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText(/任务 second/);
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "上一页" })).toBeEnabled();
    fireEvent.change(screen.getByLabelText("按应用筛选任务"), {
      target: { value: "assistant" },
    });
    await screen.findByText(/任务 first/);
    const last = new URL(fetch.mock.calls.at(-1)![0], "http://local");
    expect(last.searchParams.get("applicationId")).toBe("assistant");
    expect(last.searchParams.has("cursor")).toBe(false);
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
  });
});
