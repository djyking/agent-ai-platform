/** Only operation identity is retained. Request bodies, answers and credentials never enter storage. */
export type PendingCommand = { key: string; resource: string };
const PREFIX = "harness.pending-command.v1:";
const storageKey = (namespace: string, scope: string) =>
  `${PREFIX}${namespace}:${encodeURIComponent(scope)}`;
export function readPending(
  namespace: string,
  scope: string,
  resource: string,
): PendingCommand | undefined {
  try {
    const value = sessionStorage.getItem(storageKey(namespace, scope));
    if (value === null) return;
    let item: PendingCommand;
    try {
      item = JSON.parse(value) as PendingCommand;
    } catch {
      return { key: "", resource };
    }
    if (
      item !== null &&
      typeof item === "object" &&
      typeof item.key === "string" &&
      /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(item.key) &&
      item.resource === resource
    )
      return { key: item.key, resource };
    // Corrupt metadata does not prove that the original request was not applied.
    return { key: "", resource };
  } catch {
    // Mutations call persistPending before dispatch and fail closed if storage is unavailable.
    return undefined;
  }
}
export function persistPending(
  namespace: string,
  scope: string,
  item: PendingCommand,
) {
  try {
    const key = storageKey(namespace, scope);
    const text = JSON.stringify({ key: item.key, resource: item.resource });
    sessionStorage.setItem(key, text);
    if (sessionStorage.getItem(key) !== text)
      throw new Error("Storage verification failed");
  } catch {
    throw new Error(
      "浏览器无法保存本次操作标识，请先启用当前站点的会话存储。请求尚未发送。",
    );
  }
}
export function clearPending(namespace: string, scope: string) {
  try {
    const key = storageKey(namespace, scope);
    sessionStorage.removeItem(key);
    if (sessionStorage.getItem(key) !== null)
      throw new Error("Storage removal failed");
  } catch {
    throw new Error(
      "操作结果已确认，但浏览器未能清除待核对标识。请先恢复会话存储，再读取原操作结果。",
    );
  }
}

export function listPending(
  namespace: string,
  scopePrefix: string,
): PendingCommand[] {
  const items: PendingCommand[] = [];
  try {
    const prefix = `${PREFIX}${namespace}:`;
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i);
      if (!key?.startsWith(prefix)) continue;
      const scope = decodeURIComponent(key.slice(prefix.length));
      if (!scope.startsWith(scopePrefix)) continue;
      const resource = scope.slice(scopePrefix.length);
      if (!resource) continue;
      const record = readPending(namespace, scope, resource);
      if (record) items.push(record);
    }
  } catch {
    /* Mutations still fail closed when session storage is unavailable. */
  }
  return items;
}
