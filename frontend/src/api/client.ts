import { endpoints } from "./generated";
import type {
  AuthSession,
  Error as ServerError,
  Me,
  Operations,
} from "./generated";

export const mockMode = import.meta.env.VITE_API_MODE !== "real";
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId = "",
    public details: ServerError["details"] = [],
    public retryAfter = 0,
  ) {
    super(message);
  }
}
let transport: typeof fetch = fetch.bind(globalThis);
export async function configureTransport() {
  if (mockMode) transport = (await import("../mocks/server")).mockFetch;
}
export function setTransport(value: typeof fetch) {
  transport = value;
}
type AuthState = { session: AuthSession | null; ready: boolean };
let auth: AuthState = { session: null, ready: false };
const listeners = new Set<() => void>();
const cleanups = new Set<() => void>();
export const subscribeAuth = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
export const getAuth = () => auth;
export const onIdentityChange = (fn: () => void) => {
  cleanups.add(fn);
  return () => {
    cleanups.delete(fn);
  };
};
function setSession(session: AuthSession | null, ready = true) {
  const changed = auth.session?.user.id !== session?.user.id;
  auth = { session, ready };
  if (changed) cleanups.forEach((fn) => fn());
  listeners.forEach((fn) => fn());
}
const channel =
  typeof BroadcastChannel !== "undefined"
    ? new BroadcastChannel("htw-auth-v1")
    : null;
channel?.addEventListener("message", (e) => {
  if (e.data.type === "session") {
    setSession(e.data.session);
  }
  if (e.data.type === "clear") setSession(null);
});
export function clearSession() {
  setSession(null);
  cleanups.forEach((fn) => fn());
  channel?.postMessage({ type: "clear" });
}
export function acceptSession(session: AuthSession) {
  setSession(session);
  channel?.postMessage({ type: "session", session });
}
export function updateSessionUser(user: Me) {
  if (auth.session) acceptSession({ ...auth.session, user });
}
const timedSignal = (signal?: AbortSignal) =>
  signal
    ? AbortSignal.any([signal, AbortSignal.timeout(20000)])
    : AbortSignal.timeout(20000);
let csrf: { csrfToken: string; expiresAt: string } | undefined;
let csrfFlight: Promise<void> | undefined;
async function ensureCsrf(force = false) {
  if (!force && csrf && Date.parse(csrf.expiresAt) > Date.now() + 10000) return;
  return (csrfFlight ||= (async () => {
    const r = await transport("/api/v1/auth/csrf", {
      credentials: "include",
      signal: timedSignal(),
    });
    const body = await r.json();
    if (!r.ok) throw asError(r, body);
    csrf = body.data;
  })().finally(() => {
    csrfFlight = undefined;
  }));
}
function asError(response: Response, body: ServerError) {
  return new ApiError(
    response.status,
    body.code,
    body.message,
    body.requestId,
    body.details || [],
    Number(response.headers.get("Retry-After")) || 0,
  );
}
let refreshFlight: Promise<void> | undefined;
async function performRefresh() {
  await ensureCsrf();
  const execute = async () => {
    let r = await transport("/api/v1/auth/refresh", {
      method: "POST",
      credentials: "include",
      headers: { "X-CSRF-Token": csrf!.csrfToken },
      signal: timedSignal(),
    });
    let b = await r.json();
    if (r.status === 403 && b.code === "CSRF_INVALID") {
      await ensureCsrf(true);
      r = await transport("/api/v1/auth/refresh", {
        method: "POST",
        credentials: "include",
        headers: { "X-CSRF-Token": csrf!.csrfToken },
        signal: timedSignal(),
      });
      b = await r.json();
    }
    if (!r.ok) throw asError(r, b);
    acceptSession(b.data);
  };
  const original = auth.session?.accessToken;
  if (typeof navigator !== "undefined" && navigator.locks) {
    await navigator.locks.request("htw-refresh", async () => {
      if (auth.session?.accessToken && auth.session.accessToken !== original)
        return;
      await execute();
    });
  } else await execute();
}
export function refreshSession() {
  return (refreshFlight ||= performRefresh()
    .catch((e) => {
      clearSession();
      throw e;
    })
    .finally(() => {
      refreshFlight = undefined;
    }));
}
export async function bootstrap() {
  try {
    await configureTransport();
    await ensureCsrf();
    await refreshSession();
  } catch (e) {
    if (!(e instanceof ApiError && e.code === "REFRESH_INVALID"))
      console.info("Session restore unavailable");
  } finally {
    auth = { ...auth, ready: true };
    listeners.forEach((fn) => fn());
  }
}
export type RequestOptions = {
  params?: Record<string, string>;
  query?: Record<string, string | number | undefined>;
  key?: string;
  version?: number;
  signal?: AbortSignal;
};
export async function api<K extends keyof Operations>(
  operation: K,
  input?: Operations[K]["input"],
  options: RequestOptions = {},
): Promise<Operations[K]["output"]> {
  const endpoint = endpoints[operation];
  let path: string = endpoint.path.replace(/\{([^}]+)\}/g, (_, key) =>
    encodeURIComponent(options.params?.[key] || ""),
  );
  const query = new URLSearchParams();
  Object.entries(options.query || {}).forEach(([k, v]) => {
    if (v !== undefined) query.set(k, String(v));
  });
  if (query.size) path += `?${query}`;
  const isAuth = path.startsWith("/auth/");
  if (isAuth && endpoint.method === "POST") await ensureCsrf();
  const execute = async (
    retryAuth: boolean,
    retryCsrf: boolean,
  ): Promise<Operations[K]["output"]> => {
    const headers = new Headers();
    if (input !== undefined) headers.set("Content-Type", "application/json");
    if (auth.session && (!isAuth || operation === "changePassword"))
      headers.set("Authorization", `Bearer ${auth.session.accessToken}`);
    if (isAuth && endpoint.method === "POST")
      headers.set("X-CSRF-Token", csrf!.csrfToken);
    if (options.key) headers.set("Idempotency-Key", options.key);
    if (options.version !== undefined)
      headers.set("If-Match", `"v${options.version}"`);
    let response: Response;
    const signal = timedSignal(options.signal);
    try {
      response = await transport(`/api/v1${path}`, {
        method: endpoint.method,
        headers,
        body: input === undefined ? undefined : JSON.stringify(input),
        credentials: isAuth ? "include" : "omit",
        signal,
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") throw e;
      throw new ApiError(
        0,
        "NETWORK_ERROR",
        "网络连接失败，请保留当前内容并重试",
      );
    }
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    let body;
    try {
      body = await response.json();
    } catch {
      throw new ApiError(
        response.status,
        "INVALID_RESPONSE",
        "服务响应格式无效，请稍后重试",
        response.headers.get("X-Request-Id") || "",
      );
    }
    if (response.ok) return body.data;
    if (body.code === "CSRF_INVALID" && retryCsrf && isAuth) {
      await ensureCsrf(true);
      return execute(retryAuth, false);
    }
    if (
      body.code === "ACCESS_TOKEN_EXPIRED" &&
      retryAuth &&
      operation !== "refreshSession"
    ) {
      await refreshSession();
      return execute(false, retryCsrf);
    }
    if (
      [
        "AUTH_REQUIRED",
        "TOKEN_INVALID",
        "SESSION_REVOKED",
        "REFRESH_INVALID",
      ].includes(body.code)
    )
      clearSession();
    throw asError(response, body);
  };
  return execute(true, true);
}
export function safeReturnTo(value: string | null | undefined) {
  if (
    !value ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\")
  )
    return "/";
  try {
    const parsed = new URL(value, "http://local");
    if (
      parsed.origin !== "http://local" ||
      ["/login", "/register", "/reset-password"].includes(parsed.pathname)
    )
      return "/";
    return parsed.pathname + parsed.search;
  } catch {
    return "/";
  }
}
