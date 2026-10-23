import type { Session } from "./types";

export type Reply<T> = {
  data: T;
  etag: string | null;
  requestId: string | null;
};
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    public requestId: string | null,
    public uncertain = false,
    public commandKey?: string,
  ) {
    super(code);
  }
}
let csrfToken = "";
export function setSessionToken(session: Session) {
  csrfToken = session.csrfToken ?? "";
}
export function projectPath(project: string) {
  return `/projects/${encodeURIComponent(project)}`;
}
export function commandKey() {
  return crypto.randomUUID();
}

export async function request<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    etag?: string | null;
    key?: string;
    signal?: AbortSignal;
    session?: boolean;
    consoleEndpoint?: boolean;
  } = {},
): Promise<Reply<T>> {
  const method = options.method ?? "GET";
  const mutation = !["GET", "HEAD"].includes(method);
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (mutation && csrfToken) headers["X-CSRF-Token"] = csrfToken;
  if (options.etag) headers["If-Match"] = options.etag;
  if (options.key) headers["Idempotency-Key"] = options.key;
  let response: Response;
  try {
    response = await fetch(
      options.session
        ? "/console/session"
        : options.consoleEndpoint
          ? "/console" + path
          : "/console/api" + path,
      {
        method,
        headers,
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        signal: options.signal,
        ...(options.body !== undefined
          ? { body: JSON.stringify(options.body) }
          : {}),
      },
    );
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError(
      0,
      mutation ? "COMMAND_OUTCOME_UNCONFIRMED" : "NETWORK_UNAVAILABLE",
      null,
      mutation,
      options.key,
    );
  }
  const requestId = response.headers.get("X-Request-Id");
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw new ApiError(
      response.status,
      "RESPONSE_BODY_UNCONFIRMED",
      requestId,
      mutation,
      options.key,
    );
  }
  let body: unknown;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(
      response.status,
      "INVALID_RESPONSE",
      requestId,
      mutation,
      options.key,
    );
  }
  if (!response.ok) {
    const payload = body as { code?: string; error?: { code?: string } } | null;
    throw new ApiError(
      response.status,
      payload?.error?.code ?? payload?.code ?? `HTTP_${response.status}`,
      requestId,
      mutation && response.status >= 500,
      options.key,
    );
  }
  return { data: body as T, etag: response.headers.get("ETag"), requestId };
}

export const errorText = (error: unknown) => {
  if (!(error instanceof ApiError))
    return error instanceof Error
      ? error.message
      : "操作未完成，请重新读取当前状态。";
  if (error.code === "AUTH_PROVIDER_UNAVAILABLE")
    return "当前身份服务暂不可用。可以稍后重试，或使用已有账号的有效访问令牌接入。";
  if (error.uncertain)
    return "请求结果尚未确认。请先读取当前状态并核对记录，不要重复提交。";
  if (error.status === 401) return "会话已失效，请重新登录。";
  if (error.status === 403)
    return "当前身份没有此操作权限。请联系项目管理员核对授权。";
  if (error.status === 404) return "资源不存在，或当前身份不可见。";
  if (error.status === 412 || error.status === 428)
    return "记录已变化。请刷新并核对最新版本，再重新操作。";
  if (error.status === 409)
    return "当前状态不允许此操作，或幂等请求发生冲突。请刷新核对。";
  if (error.status === 422 || error.status === 400)
    return "输入未通过校验，请核对表单与发布契约。";
  return error.status === 0
    ? "无法连接平台，请检查服务后重试读取。"
    : "请求未完成，请查看错误编号并核对平台状态。";
};
