import { useEffect, useState } from "react";
import { ApiError, commandKey, projectPath, request, type Reply } from "./api";
import { readPending, persistPending, clearPending } from "./pending-commands";
import type { StudioApplication, StudioTaskPage } from "./studio-types";
export const studioPath = (project: string) => `${projectPath(project)}/studio`;

/** Each page is independently fetched so ACL changes never reuse previously rendered outputs. */
export function useStudioTasks(
  project: string,
  actor: string,
  applicationId?: string,
  enabled = true,
) {
  const scope = JSON.stringify([project, actor, applicationId ?? ""]);
  const [paging, setPaging] = useState({
    scope,
    cursor: "",
    previous: [] as string[],
    tick: 0,
  });
  const current =
    paging.scope === scope
      ? paging
      : { scope, cursor: "", previous: [] as string[], tick: 0 };
  const key = JSON.stringify([scope, current.cursor, current.tick, enabled]);
  const [state, setState] = useState<{
    key: string;
    loading: boolean;
    data?: StudioTaskPage;
    error?: unknown;
  }>({ key, loading: true });
  const query = new URLSearchParams({ limit: "20" });
  if (applicationId) query.set("applicationId", applicationId);
  if (current.cursor) query.set("cursor", current.cursor);
  const path = `${studioPath(project)}/tasks?${query}`;
  useEffect(() => {
    if (!enabled) {
      setState({ key, loading: false });
      return;
    }
    const controller = new AbortController();
    setState({ key, loading: true });
    request<StudioTaskPage>(path, { signal: controller.signal })
      .then((reply) => {
        if (!controller.signal.aborted)
          setState({ key, loading: false, data: reply.data });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState({ key, loading: false, error });
      });
    return () => controller.abort();
  }, [key, path, enabled]);
  const active = state.key === key ? state : { key, loading: true };
  return {
    ...active,
    page: current.previous.length + 1,
    hasPrevious: current.previous.length > 0,
    reload: () =>
      setPaging({ scope, cursor: "", previous: [], tick: current.tick + 1 }),
    previous: () => {
      if (active.loading || !current.previous.length) return;
      setPaging({
        ...current,
        cursor: current.previous.at(-1)!,
        previous: current.previous.slice(0, -1),
      });
    },
    next: () => {
      if (active.loading || !active.data?.nextCursor) return;
      setPaging({
        ...current,
        cursor: active.data.nextCursor,
        previous: [
          ...current.previous,
          active.data.pageCursor ?? current.cursor,
        ],
      });
    },
  };
}
export function useStudioCommand(
  project: string,
  subject: string,
  resourceId: string,
  accept: (reply: Reply<StudioApplication>) => void,
) {
  const scope = `${project}/${subject}/${resourceId}`;
  const [pending, setPending] = useState(() =>
    readPending("studio", scope, resourceId),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(
    pending
      ? new ApiError(0, "COMMAND_OUTCOME_UNCONFIRMED", null, true, pending.key)
      : undefined,
  );
  async function execute(
    path: string,
    body: unknown,
    etag: string,
    method = "POST",
  ) {
    if (busy || pending || readPending("studio", scope, resourceId)) return;
    setBusy(true);
    setError(undefined);
    const key = commandKey();
    let dispatched = false;
    try {
      const record = { key, resource: resourceId };
      // Persist before dispatch so refreshing while the request is in flight also remains blocked.
      persistPending("studio", scope, record);
      setPending(record);
      dispatched = true;
      const reply = await request<StudioApplication>(
        `${studioPath(project)}${path}`,
        { method, body, etag, key },
      );
      clearPending("studio", scope);
      setPending(undefined);
      accept(reply);
      return reply.data;
    } catch (failure) {
      setError(failure);
      if (
        dispatched &&
        failure instanceof ApiError &&
        !failure.uncertain &&
        failure.status >= 400 &&
        failure.status < 500
      ) {
        try {
          clearPending("studio", scope);
          setPending(undefined);
        } catch (storageError) {
          setError(storageError);
        }
      }
    } finally {
      setBusy(false);
    }
  }
  async function recover() {
    const record = pending ?? readPending("studio", scope, resourceId);
    if (!record || busy) return;
    setBusy(true);
    setError(undefined);
    try {
      if (!record.key)
        throw new Error(
          "原操作标识损坏，无法核对。请联系管理员检查服务端命令记录，不要重复提交。",
        );
      const receipt = await request<{
        status: string;
        resourceId: string;
        response: StudioApplication;
      }>(`${studioPath(project)}/commands/${encodeURIComponent(record.key)}`);
      if (
        receipt.data.status !== "COMPLETED" ||
        receipt.data.resourceId !== record.resource
      )
        throw new Error(
          "命令尚未确认完成，继续阻止提交。请稍后重新读取同一操作记录。",
        );
      const current = await request<StudioApplication>(
        `${studioPath(project)}/applications/${encodeURIComponent(record.resource)}`,
      );
      if (
        current.data.id !== record.resource ||
        current.data.revision < receipt.data.response.revision
      )
        throw new Error("当前应用与原操作不匹配或版本尚未同步，继续阻止提交。");
      clearPending("studio", scope);
      setPending(undefined);
      accept(current);
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  return {
    execute,
    recover,
    pending,
    busy,
    error,
    blocked: !!pending,
    clearError: () => setError(undefined),
  };
}
