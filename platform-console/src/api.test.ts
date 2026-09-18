import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, request, setSessionToken } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
  setSessionToken({ authenticated: false });
});
describe("same-origin session API", () => {
  it("sends current CSRF, ETag and exact command key without browser bearer credentials", async () => {
    setSessionToken({ authenticated: true, csrfToken: "csrf-current" });
    const fetch = vi.fn().mockResolvedValue(
      new Response('{"accepted":true}', {
        status: 200,
        headers: { ETag: '"c4"', "X-Request-Id": "req-1" },
      }),
    );
    vi.stubGlobal("fetch", fetch);
    const reply = await request(
      "/projects/ops-dev/catalog/resources/Prompt/prompt-one",
      {
        method: "PUT",
        body: { spec: { system: "Test" } },
        etag: '"c3"',
        key: "one-operation",
      },
    );
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][0]).toBe(
      "/console/api/projects/ops-dev/catalog/resources/Prompt/prompt-one",
    );
    const init = fetch.mock.calls[0][1];
    expect(init.credentials).toBe("same-origin");
    expect(init.cache).toBe("no-store");
    expect(init.headers).toMatchObject({
      "X-CSRF-Token": "csrf-current",
      "If-Match": '"c3"',
      "Idempotency-Key": "one-operation",
    });
    expect(init.headers.Authorization).toBeUndefined();
    expect(init.headers["X-Harness-User-Token"]).toBeUndefined();
    expect(reply.etag).toBe('"c4"');
  });
  it("does not retry a mutation whose outcome is uncertain", async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError("connection lost"));
    vi.stubGlobal("fetch", fetch);
    await expect(
      request("/projects/p/runs", {
        method: "POST",
        body: {},
        key: "fixed-command-key",
      }),
    ).rejects.toMatchObject({
      uncertain: true,
      commandKey: "fixed-command-key",
      code: "COMMAND_OUTCOME_UNCONFIRMED",
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("keeps a truncated successful mutation response uncertain without replay", async () => {
    const fetch = vi.fn().mockResolvedValue({
      status: 202,
      ok: true,
      headers: new Headers(),
      text: () => Promise.reject(new TypeError("body interrupted")),
    });
    vi.stubGlobal("fetch", fetch);
    await expect(
      request("/projects/p/runs", {
        method: "POST",
        body: {},
        key: "same-key",
      }),
    ).rejects.toMatchObject({
      uncertain: true,
      code: "RESPONSE_BODY_UNCONFIRMED",
      commandKey: "same-key",
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("preserves stale revision rejection and request correlation without retrying", async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response('{"error":{"code":"PRECONDITION_FAILED"}}', {
        status: 412,
        headers: { "X-Request-Id": "req-stale" },
      }),
    );
    vi.stubGlobal("fetch", fetch);
    await expect(
      request("/projects/p/runs/id/pause", {
        method: "POST",
        etag: '"old"',
        key: "k",
        body: {},
      }),
    ).rejects.toMatchObject({
      status: 412,
      code: "PRECONDITION_FAILED",
      requestId: "req-stale",
      uncertain: false,
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("rotates CSRF for session project changes and clears it on logout", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    setSessionToken({ authenticated: true, csrfToken: "rotated" });
    await request("", {
      session: true,
      method: "POST",
      body: { projectId: "other" },
    });
    expect(fetch.mock.calls[0][0]).toBe("/console/session");
    expect(fetch.mock.calls[0][1].headers["X-CSRF-Token"]).toBe("rotated");
    expect(fetch.mock.calls[0][1].body).toBe('{"projectId":"other"}');
    setSessionToken({ authenticated: false });
    fetch.mockResolvedValue(new Response("{}", { status: 200 }));
    await request("", { session: true });
    expect(fetch.mock.calls[1][1].headers["X-CSRF-Token"]).toBeUndefined();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});
