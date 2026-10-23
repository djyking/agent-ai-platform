import { afterEach, describe, expect, it, vi } from "vitest";
import { persistPending, readPending } from "./pending-commands";
afterEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});
describe("pending command metadata", () => {
  it("survives a module reload and persists only command identity, never request data", async () => {
    const item = {
      key: "command-key-0001",
      resource: "app-a",
      body: {
        password: "never-store-me",
        instructions: "private instructions",
      },
    };
    persistPending("studio", "p/console/user/app-a", item);
    const stored = sessionStorage.getItem(sessionStorage.key(0)!);
    expect(JSON.parse(stored!)).toEqual({
      key: item.key,
      resource: item.resource,
    });
    vi.resetModules();
    const reloaded = await import("./pending-commands");
    expect(
      reloaded.readPending("studio", "p/console/user/app-a", "app-a"),
    ).toEqual({ key: item.key, resource: item.resource });
    expect(
      reloaded.readPending("studio", "p/console/other-user/app-a", "app-a"),
    ).toBeUndefined();
  });
  it("treats corrupt stored metadata as unresolved instead of allowing a new command", () => {
    persistPending("knowledge", "p/user/collection-a", {
      key: "command-key-0002",
      resource: "collection-a",
    });
    sessionStorage.setItem(sessionStorage.key(0)!, "{broken");
    expect(
      readPending("knowledge", "p/user/collection-a", "collection-a"),
    ).toEqual({ key: "", resource: "collection-a" });
  });
  it("refuses to dispatch when metadata cannot be persisted", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage blocked");
    });
    expect(() =>
      persistPending("studio", "p/console/user/app-a", {
        key: "command-key-0003",
        resource: "app-a",
      }),
    ).toThrow("请求尚未发送");
  });
});
