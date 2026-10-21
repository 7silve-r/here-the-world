import { beforeEach, describe, expect, it } from "vitest";
import {
  acceptSession,
  api,
  clearSession,
  getAuth,
  safeReturnTo,
  setTransport,
} from "../src/api/client";
import { mockFetch, setMockScenario } from "../src/mocks/server";
import type { AuthSession } from "../src/api/generated";
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  clearSession();
  setTransport(mockFetch);
});
async function login() {
  const session = await api("login", {
    email: "world@example.com",
    password: "HelloWorld2026",
  });
  acceptSession(session);
  return session;
}
describe("API client session boundaries", () => {
  it("initial CSRF is included and only credentials include on auth POST", async () => {
    let authCredentials: RequestCredentials | undefined,
      businessCredentials: RequestCredentials | undefined,
      header: string | null = null;
    setTransport(async (input, init) => {
      const path = String(input);
      if (path.endsWith("/auth/login")) {
        authCredentials = init?.credentials;
        header = new Headers(init?.headers).get("X-CSRF-Token");
      }
      if (path.endsWith("/me")) businessCredentials = init?.credentials;
      return mockFetch(input, init);
    });
    await login();
    expect(header).toBeTruthy();
    expect(authCredentials).toBe("include");
    await api("getMe");
    expect(businessCredentials).toBe("omit");
  });
  it("shares one refresh among concurrent expired requests, retrying each exactly once", async () => {
    const original = await login();
    let refreshes = 0,
      attempts = 0;
    setTransport(async (input, init) => {
      const path = String(input);
      if (path === "/api/v1/auth/refresh") {
        refreshes++;
        return mockFetch(input, init);
      }
      if (path === "/api/v1/me") {
        attempts++;
        if (
          new Headers(init?.headers).get("Authorization") ===
          `Bearer ${original.accessToken}`
        )
          return new Response(
            JSON.stringify({
              code: "ACCESS_TOKEN_EXPIRED",
              message: "expired",
              requestId: "test",
              details: [],
            }),
            { status: 401, headers: { "Content-Type": "application/json" } },
          );
      }
      return mockFetch(input, init);
    });
    const result = await Promise.all([
      api("getMe"),
      api("getMe"),
      api("getMe"),
    ]);
    expect(result.every((m) => m.emailVerified)).toBe(true);
    expect(refreshes).toBe(1);
    expect(attempts).toBe(6);
    expect(getAuth().session?.accessToken).not.toBe(original.accessToken);
  });
  it("does not recursively retry a revoked session; maps Retry-After", async () => {
    await login();
    setMockScenario("rate-limit");
    await expect(
      api("updateSettings", { theme: "LIGHT" }),
    ).rejects.toMatchObject({ status: 429, retryAfter: 5 });
    await api("logout");
    await expect(api("getMe")).rejects.toMatchObject({
      code: "SESSION_REVOKED",
    });
    expect(getAuth().session).toBe(null);
  });
  it("refuses external returnTo and retains only safe in-site routes", () => {
    for (const value of [
      "https://evil.example",
      "//evil.example",
      "/\\evil.example",
      "/login?returnTo=https://evil.example",
      "javascript:alert(1)",
    ])
      expect(safeReturnTo(value)).toBe("/");
    expect(safeReturnTo("/works/work_01?intent=like")).toBe(
      "/works/work_01?intent=like",
    );
  });
  it("persists neither access tokens nor raw passwords in browser storage", async () => {
    const session: AuthSession = await login();
    const key = crypto.randomUUID(),
      input = {
        purpose: "WORK_MEDIA" as const,
        fileName: "demo.png",
        contentType: "image/png",
        sizeBytes: 4,
      };
    const ticket = await api("createUpload", input, { key });
    const repeated = await api("createUpload", input, { key });
    expect(repeated.uploadUrl).toBe(ticket.uploadUrl);
    const data = Array.from({ length: localStorage.length }, (_, i) =>
      localStorage.getItem(localStorage.key(i)!),
    ).join("");
    expect(data).not.toContain(session.accessToken);
    expect(data).not.toContain("HelloWorld2026");
    expect(data).not.toContain("uploadUrl");
    expect(data).not.toContain(ticket.uploadUrl);
  });
});
