import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  mockFetch,
  mockStoragePut,
  setMockScenario,
  mockResetLink,
} from "../src/mocks/server";
import type {
  AuthSession,
  UploadTicket,
  WorkDetail,
  WorkInput,
} from "../src/api/generated";
import { operations } from "../src/mocks/contract";
let csrf = "",
  token = "";
const covered = new Set<string>();
async function request(
  path: string,
  method = "GET",
  body?: unknown,
  extra: Record<string, string> = {},
) {
  const matched = operations.find(
    (o) => o.method === method && o.regex.test(path.split("?")[0]),
  );
  if (matched)
    covered.add((matched.operation as { operationId: string }).operationId);
  const response = await mockFetch(`/api/v1${path}`, {
    method,
    credentials: path.startsWith("/auth/") ? "include" : "omit",
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(csrf ? { "X-CSRF-Token": csrf } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...extra,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return {
    status: response.status,
    headers: response.headers,
    json: await response.json(),
  };
}
async function login(email = "world@example.com", password = "HelloWorld2026") {
  token = "";
  const c = await request("/auth/csrf");
  csrf = c.json.data.csrfToken;
  const r = await request("/auth/login", "POST", { email, password });
  expect(r.status).toBe(200);
  token = (r.json.data as AuthSession).accessToken;
  return r;
}
const workBody: WorkInput = {
  kind: "TEXT",
  title: "测试里的一个世界",
  body: "留下一段正文。",
  mediaAssetIds: [],
  coverAssetId: null,
  musicLinkId: null,
};
async function create(body = workBody) {
  return request("/works", "POST", body, {
    "Idempotency-Key": crypto.randomUUID(),
  });
}
async function upload(type = "image/png", purpose = "WORK_MEDIA") {
  const blob = new File([new Uint8Array([1, 2, 3, 4])], "demo.png", { type }),
    input = {
      purpose,
      fileName: blob.name,
      contentType: type,
      sizeBytes: blob.size,
    };
  const r = await request("/uploads", "POST", input, {
    "Idempotency-Key": crypto.randomUUID(),
  });
  expect(r.status).toBe(201);
  const ticket = r.json.data as UploadTicket;
  await mockStoragePut(ticket, blob);
  const complete = await request(
    `/uploads/${ticket.uploadId}/complete`,
    "POST",
    { sizeBytes: blob.size },
  );
  expect(complete.status).toBe(202);
  return { ticket, asset: complete.json.data, blob };
}
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  csrf = "";
  token = "";
  vi.useRealTimers();
});
describe("OpenAPI validated mutable mock", () => {
  it("visitor feed pagination ends without duplicate works; expired snapshot stays explicit", async () => {
    const ids = new Set<string>();
    let cursor: string | undefined;
    do {
      const result = await request(
        `/feed?limit=7${cursor ? `&cursor=${cursor}` : ""}`,
      );
      expect(result.status).toBe(200);
      for (const w of result.json.data.items) {
        expect(ids.has(w.id)).toBe(false);
        expect(w.viewer).toEqual({
          liked: false,
          bookmarked: false,
          isOwner: false,
        });
        ids.add(w.id);
      }
      cursor = result.json.data.nextCursor;
    } while (cursor);
    expect(ids.size).toBe(32);
    const first = await request("/feed?limit=1");
    setMockScenario("feed-expired");
    const expired = await request(`/feed?cursor=${first.json.data.nextCursor}`);
    expect(expired.status).toBe(410);
    expect(expired.json.code).toBe("FEED_SESSION_EXPIRED");
    expect((await request("/system/capabilities")).status).toBe(200);
    expect((await request("/users/user_lin")).status).toBe(200);
    expect((await request("/users/user_lin/works")).status).toBe(200);
    expect((await request("/me")).status).toBe(401);
  });
  it("registration, unverified account, wrong code, resend throttling, verify and refresh", async () => {
    csrf = (await request("/auth/csrf")).json.data.csrfToken;
    const body = {
      email: "fresh@example.com",
      password: "ThisIsAnExample26",
      username: "fresh_world",
      displayName: "新世界",
    };
    expect((await request("/auth/register", "POST", body)).status).toBe(202);
    expect(
      (
        await request("/auth/login", "POST", {
          email: body.email,
          password: body.password,
        })
      ).json.code,
    ).toBe("EMAIL_NOT_VERIFIED");
    expect(
      (
        await request("/auth/verify-email", "POST", {
          email: body.email,
          code: "000000",
        })
      ).json.code,
    ).toBe("VERIFICATION_CODE_INVALID");
    const resend = await request("/auth/resend-verification", "POST", {
      email: body.email,
    });
    expect(resend.status).toBe(429);
    expect(Number(resend.headers.get("Retry-After"))).toBeGreaterThan(0);
    vi.setSystemTime(Date.now() + 61000);
    expect(
      (
        await request("/auth/resend-verification", "POST", {
          email: body.email,
        })
      ).status,
    ).toBe(202);
    expect(
      (
        await request("/auth/verify-email", "POST", {
          email: body.email,
          code: "123456",
        })
      ).status,
    ).toBe(422);
    expect(
      (
        await request("/auth/verify-email", "POST", {
          email: body.email,
          code: "654321",
        })
      ).status,
    ).toBe(200);
    await login(body.email, body.password);
    expect((await request("/auth/refresh", "POST")).status).toBe(200);
    expect((await request("/auth/logout", "POST")).status).toBe(200);
    expect((await request("/me")).json.code).toBe("SESSION_REVOKED");
  });
  it("rejects unknown fields, stale versions, missing preconditions and private drafts", async () => {
    await login();
    expect(
      (
        await request(
          "/works",
          "POST",
          { ...workBody, unexpected: true },
          { "Idempotency-Key": crypto.randomUUID() },
        )
      ).status,
    ).toBe(422);
    const key = crypto.randomUUID();
    const first = await request("/works", "POST", workBody, {
      "Idempotency-Key": key,
    });
    const w = first.json.data as WorkDetail;
    expect(
      (await request("/works", "POST", workBody, { "Idempotency-Key": key }))
        .json.data.id,
    ).toBe(w.id);
    expect(
      (
        await request(
          "/works",
          "POST",
          { ...workBody, title: "另一项意图" },
          { "Idempotency-Key": key },
        )
      ).json.code,
    ).toBe("IDEMPOTENCY_CONFLICT");
    expect((await request(`/works/${w.id}`, "PUT", workBody)).status).toBe(428);
    setMockScenario("version-conflict");
    expect(
      (await request(`/works/${w.id}`, "PUT", workBody, { "If-Match": '"v1"' }))
        .status,
    ).toBe(412);
    const latest = await request(`/works/${w.id}`);
    expect(latest.json.data.version).toBe(2);
    const saved = await request(`/works/${w.id}`, "PUT", workBody, {
      "If-Match": '"v2"',
    });
    expect(saved.json.data.version).toBe(3);
    token = "";
    expect((await request(`/works/${w.id}`)).status).toBe(404);
  });
  it("publishes text, serial idempotent interactions, comments permissions, lists and delete", async () => {
    await login();
    const w = (await create()).json.data as WorkDetail;
    const published = await request(
      `/works/${w.id}/publish`,
      "POST",
      undefined,
      { "If-Match": '"v1"' },
    );
    expect(published.json.data.status).toBe("PUBLISHED");
    const a = await request(`/works/${w.id}/like`, "PUT"),
      b = await request(`/works/${w.id}/like`, "PUT");
    expect(a.json.data.likeCount).toBe(1);
    expect(b.json.data.likeCount).toBe(1);
    expect(
      (await request(`/works/${w.id}/like`, "DELETE")).json.data.likeCount,
    ).toBe(0);
    expect(
      (await request(`/works/${w.id}/bookmark`, "PUT")).json.data.bookmarked,
    ).toBe(true);
    expect((await request("/me/bookmarks")).json.data.items).toHaveLength(1);
    expect(
      (await request(`/works/${w.id}/bookmark`, "DELETE")).json.data.bookmarked,
    ).toBe(false);
    const key = crypto.randomUUID();
    const c = await request(
      `/works/${w.id}/comments`,
      "POST",
      { body: "你好，这里。" },
      { "Idempotency-Key": key },
    );
    expect(
      (
        await request(
          `/works/${w.id}/comments`,
          "POST",
          { body: "你好，这里。" },
          { "Idempotency-Key": key },
        )
      ).json.data.id,
    ).toBe(c.json.data.id);
    expect(
      (await request(`/works/${w.id}/comments`)).json.data.items[0].canDelete,
    ).toBe(true);
    expect(
      (await request(`/comments/${c.json.data.id}`, "DELETE")).status,
    ).toBe(200);
    expect(
      (await request("/me/works?status=PUBLISHED")).json.data.items.some(
        (v: WorkDetail) => v.id === w.id,
      ),
    ).toBe(true);
    expect(
      (
        await request(`/works/${w.id}`, "DELETE", undefined, {
          "If-Match": `"v${published.json.data.version}"`,
        })
      ).status,
    ).toBe(200);
    expect((await request(`/works/${w.id}`)).status).toBe(404);
  });
  it("binary upload → complete → asset READY; saves while processing then publishes asynchronously", async () => {
    await login();
    const { ticket } = await upload();
    const body: WorkInput = {
      ...workBody,
      kind: "IMAGE",
      mediaAssetIds: [ticket.assetId],
    };
    const w = (await create(body)).json.data;
    expect(w.assets[0].status).toBe("PROCESSING");
    const p = await request(`/works/${w.id}/publish`, "POST", undefined, {
      "If-Match": '"v1"',
    });
    expect(p.status).toBe(202);
    expect(p.json.data.status).toBe("PROCESSING");
    vi.setSystemTime(Date.now() + 6000);
    expect((await request(`/assets/${ticket.assetId}`)).json.data.status).toBe(
      "READY",
    );
    const done = await request(`/works/${w.id}`);
    expect(done.json.data.status).toBe("PUBLISHED");
    expect(done.json.data.assets[0].url).toMatch(/^blob:/);
    expect(
      (await request(`/uploads/${ticket.uploadId}`, "DELETE")).status,
    ).toBe(409);
    const second = await request(
      "/uploads",
      "POST",
      {
        purpose: "WORK_COVER",
        fileName: "c.png",
        contentType: "image/png",
        sizeBytes: 4,
      },
      { "Idempotency-Key": crypto.randomUUID() },
    );
    expect(
      (await request(`/uploads/${second.json.data.uploadId}`, "DELETE")).status,
    ).toBe(200);
  });
  it("failed processing becomes FAILED; correction saves as DRAFT", async () => {
    await login();
    setMockScenario("asset-failed");
    const { ticket } = await upload();
    const w = (
      await create({
        ...workBody,
        kind: "IMAGE",
        mediaAssetIds: [ticket.assetId],
      })
    ).json.data;
    expect(
      (
        await request(`/works/${w.id}/publish`, "POST", undefined, {
          "If-Match": '"v1"',
        })
      ).status,
    ).toBe(202);
    vi.setSystemTime(Date.now() + 6000);
    const failed = await request(`/works/${w.id}`);
    expect(failed.json.data.status).toBe("FAILED");
    expect(
      (
        await request(
          `/works/${w.id}`,
          "PUT",
          { ...workBody, kind: "IMAGE" },
          { "If-Match": `"v${failed.json.data.version}"` },
        )
      ).json.data.status,
    ).toBe("DRAFT");
  });
  it("profile, avatar, strict settings and EXTERNAL music flow", async () => {
    await login();
    const { ticket } = await upload("image/png", "AVATAR");
    vi.setSystemTime(Date.now() + 6000);
    expect((await request(`/assets/${ticket.assetId}`)).json.data.status).toBe(
      "READY",
    );
    const me = await request("/me", "PATCH", {
      displayName: "另一个名字",
      bio: "风景正在路上",
      avatarAssetId: ticket.assetId,
    });
    expect(me.json.data.displayName).toBe("另一个名字");
    expect((await request("/me")).json.data.email).toBe("world@example.com");
    expect((await request("/me/settings")).json.data.autoPlayMedia).toBe(false);
    expect(
      (
        await request("/me/settings", "PATCH", {
          theme: "DARK",
          motion: "REDUCED",
          autoPlayMedia: true,
        })
      ).status,
    ).toBe(200);
    expect(
      (await request("/me/settings", "PATCH", { unknown: true })).status,
    ).toBe(422);
    const music = await request("/music-links/resolve", "POST", {
      input: "分享给你 https://music.163.com/",
    });
    expect(music.json.data.playback).toEqual({
      mode: "EXTERNAL",
      embedUrl: null,
    });
    const w = (
      await create({
        ...workBody,
        kind: "MUSIC_LINK",
        body: "",
        musicLinkId: music.json.data.id,
      })
    ).json.data;
    expect(
      (
        await request(`/works/${w.id}/publish`, "POST", undefined, {
          "If-Match": '"v1"',
        })
      ).json.data.status,
    ).toBe("PUBLISHED");
  });
  it("CSRF, rate limit, resource 404, expired token, password reset and change revoke sessions", async () => {
    expect(
      (
        await request("/auth/login", "POST", {
          email: "world@example.com",
          password: "HelloWorld2026",
        })
      ).json.code,
    ).toBe("CSRF_INVALID");
    await login();
    setMockScenario("rate-limit");
    const rate = await create();
    expect(rate.status).toBe(429);
    expect(rate.headers.get("Retry-After")).toBe("5");
    setMockScenario("resource-404");
    expect((await request("/works/work_01")).status).toBe(404);
    setMockScenario("token-expired");
    expect((await request("/me")).json.code).toBe("ACCESS_TOKEN_EXPIRED");
    const newSession = await request("/auth/refresh", "POST");
    token = newSession.json.data.accessToken;
    expect(
      (
        await request("/auth/change-password", "POST", {
          currentPassword: "HelloWorld2026",
          newPassword: "AChangedPassword26",
        })
      ).status,
    ).toBe(200);
    expect((await request("/me")).status).toBe(401);
    token = "";
    expect(
      (
        await request("/auth/forgot-password", "POST", {
          email: "world@example.com",
        })
      ).status,
    ).toBe(202);
    const resetToken = new URL(`http://local${mockResetLink()}`).hash.slice(7);
    expect(
      (
        await request("/auth/reset-password", "POST", {
          token: resetToken,
          newPassword: "HelloWorld2026",
        })
      ).status,
    ).toBe(200);
    await login();
  });
  it("exercises all 37 contract operations", () => {
    expect(covered.size).toBe(37);
  });
});
