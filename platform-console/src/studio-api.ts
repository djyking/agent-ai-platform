import { useState } from "react";
import { ApiError, commandKey, projectPath, request, type Reply } from "./api";
import { readPending, persistPending, clearPending } from "./pending-commands";
import type { StudioApplication } from "./studio-types";
export const studioPath = (project: string) => `${projectPath(project)}/studio`;
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
