import type * as T from "../api/generated";
import { operations, validateBody, validator } from "./contract";
import {
  absolute,
  capabilities,
  defaultSettings,
  demoUser,
  iso,
  otherUser,
  seedWorks,
} from "./seed";
import { blobUrl, storeBlob } from "./blob-store";

type Account = {
  user: T.PublicUser;
  email: string;
  verified: boolean;
  settings: T.Settings;
  resent: number;
  attempts: number;
  resendCount?: number;
  avatarAssetId?: string | null;
};
type Upload = {
  ticket: T.UploadTicket;
  input: T.UploadInput;
  owner: string;
  complete: boolean;
  cancelled: boolean;
  received: number;
  readyAt: number;
  fail: boolean;
};
type Database = {
  accounts: Account[];
  works: T.WorkDetail[];
  assets: Record<string, T.Asset>;
  assetOwners: Record<string, string>;
  uploads: Record<string, Upload>;
  music: Record<string, { owner: string; value: T.MusicLink }>;
  comments: T.Comment[];
  likes: Record<string, string[]>;
  bookmarks: Record<string, string[]>;
  bookmarkOrder?: Record<string, string[]>;
  deleted: Record<string, string>;
  feeds: Record<string, { ids: string[]; expiresAt: string }>;
  cursors: Record<
    string,
    { path: string; filter: string; offset: number; session?: string }
  >;
  idempotency: Record<string, { body: string; status: number; data: unknown }>;
  workReady: Record<string, number>;
};
const storageKey = "htw.mock.business.v1";
const sessionKey = "htw.mock.session-user"; // Simulation marker only; never a credential.
const passwords = new Map<string, string>([
  ["world@example.com", "HelloWorld2026"],
  ["pending@example.com", "HelloWorld2026"],
  ["yu@example.com", "HelloWorld2026"],
]);
const tokens = new Map<
  string,
  { user: string; expires: number; generation: string }
>();
const authChannel =
  typeof BroadcastChannel !== "undefined"
    ? new BroadcastChannel("htw-auth-v1")
    : null;
authChannel?.addEventListener("message", (event) => {
  if (event.data.type === "session") {
    const s = event.data.session as T.AuthSession;
    tokens.set(s.accessToken, {
      user: s.user.id,
      expires: Date.now() + s.expiresIn * 1000,
      generation: localStorage.getItem(sessionKey) || "",
    });
  }
  if (event.data.type === "clear") tokens.clear();
});
let csrf = "";
let resetCredential:
  { token: string; email: string; expires: number } | undefined;
const ack: T.Ack = { success: true };
const accepted: T.MessageAccepted = { accepted: true, resendAfterSeconds: 60 };
function initial(): Database {
  return {
    accounts: [
      {
        user: demoUser,
        email: "world@example.com",
        verified: true,
        settings: { ...defaultSettings },
        resent: 0,
        attempts: 0,
      },
      {
        user: otherUser,
        email: "yu@example.com",
        verified: true,
        settings: { ...defaultSettings },
        resent: 0,
        attempts: 0,
      },
      {
        user: {
          ...demoUser,
          id: "user_pending",
          username: "pending_world",
          displayName: "等待来信",
        },
        email: "pending@example.com",
        verified: false,
        settings: { ...defaultSettings },
        resent: 0,
        attempts: 0,
      },
    ],
    works: seedWorks(),
    assets: {},
    assetOwners: {},
    uploads: {},
    music: {},
    comments: [],
    likes: {},
    bookmarks: {},
    deleted: {},
    feeds: {},
    cursors: {},
    idempotency: {},
    workReady: {},
  };
}
function read(): Database {
  try {
    const raw = localStorage.getItem(storageKey);
    const db: Database = raw ? JSON.parse(raw) : initial();
    const restoreTicket = (value: unknown) => {
      if (
        value &&
        typeof value === "object" &&
        "uploadId" in value &&
        "assetId" in value
      ) {
        const ticket = value as T.UploadTicket;
        ticket.uploadUrl = absolute(`/mock-storage/${ticket.uploadId}`);
      }
    };
    Object.values(db.uploads).forEach((u) => restoreTicket(u.ticket));
    Object.values(db.idempotency).forEach((record) =>
      restoreTicket(record.data),
    );
    return db;
  } catch {
    return initial();
  }
}
function save(db: Database) {
  localStorage.setItem(
    storageKey,
    JSON.stringify(db, (key, value) =>
      key === "uploadUrl" ? undefined : value,
    ),
  );
}
export type Scenario =
  | "rate-limit"
  | "feed-expired"
  | "version-conflict"
  | "asset-failed"
  | "token-expired"
  | "resource-404";
export function setMockScenario(scenario: Scenario) {
  sessionStorage.setItem("htw.mock.scenario", scenario);
}
function consume(scenario: Scenario) {
  if (sessionStorage.getItem("htw.mock.scenario") === scenario) {
    sessionStorage.removeItem("htw.mock.scenario");
    return true;
  }
  return false;
}
export function resetMock() {
  localStorage.removeItem(storageKey);
  localStorage.removeItem(sessionKey);
  location.assign("/");
}
export const mockResetLink = () =>
  resetCredential ? `/reset-password#token=${resetCredential.token}` : null;
class Failure extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details: T.Error["details"] = [],
    public retry?: number,
  ) {
    super(message);
  }
}
function fail(
  status: number,
  code: string,
  message: string,
  field?: string,
): never {
  throw new Failure(
    status,
    code,
    message,
    field ? [{ field, reason: "NOT_ALLOWED", message }] : [],
  );
}
function publicUser(db: Database, id: string): T.PublicUser {
  const account = db.accounts.find((a) => a.user.id === id);
  const u = account?.user || (id === otherUser.id ? otherUser : demoUser);
  return {
    ...u,
    publishedWorkCount: db.works.filter(
      (w) => w.author.id === id && w.status === "PUBLISHED",
    ).length,
  };
}
function me(db: Database, account: Account): T.Me {
  return {
    ...publicUser(db, account.user.id),
    email: account.email,
    emailVerified: true,
  };
}
function session(db: Database, account: Account): T.AuthSession {
  const accessToken = crypto.randomUUID();
  const generation = localStorage.getItem(sessionKey) || "";
  tokens.set(accessToken, {
    user: account.user.id,
    expires: Date.now() + 900000,
    generation,
  });
  return {
    accessToken,
    tokenType: "Bearer",
    expiresIn: 900,
    user: me(db, account),
  };
}
function authenticate(
  db: Database,
  request: Request,
  required = false,
): Account | undefined {
  const header = request.headers.get("Authorization");
  if (!header) {
    if (required) fail(401, "AUTH_REQUIRED", "请先登录");
    return;
  }
  const token = tokens.get(header.replace(/^Bearer /, ""));
  if (!token) fail(401, "TOKEN_INVALID", "登录凭证无效");
  if (token.generation !== localStorage.getItem(sessionKey))
    fail(401, "SESSION_REVOKED", "会话已结束，请重新登录");
  if (token.expires < Date.now() || consume("token-expired"))
    fail(401, "ACCESS_TOKEN_EXPIRED", "登录凭证已过期");
  return db.accounts.find((a) => a.user.id === token.user);
}
function requiredAccount(db: Database, request: Request) {
  return authenticate(db, request, true)!;
}
function work(db: Database, id: string, account?: Account) {
  const w = db.works.find((w) => w.id === id);
  if (!w || (w.status !== "PUBLISHED" && w.author.id !== account?.user.id))
    fail(404, "WORK_NOT_FOUND", "这件作品暂时无法访问");
  return w;
}
async function assetView(db: Database, a: T.Asset): Promise<T.Asset> {
  const u = Object.values(db.uploads).find((u) => u.ticket.assetId === a.id);
  if (u?.complete && a.status === "PROCESSING" && Date.now() > u.readyAt) {
    a.status = u.fail ? "FAILED" : "READY";
    a.failureCode = u.fail ? "MEDIA_PROCESSING_FAILED" : null;
  }
  if (u && a.status === "READY") {
    const url = await blobUrl(a.id);
    return { ...a, url, thumbnailUrl: a.mediaType === "IMAGE" ? url : null };
  }
  return a;
}
async function detail(
  db: Database,
  w: T.WorkDetail,
  account?: Account,
): Promise<T.WorkDetail> {
  const assets = await Promise.all(
    w.assets.map((a) => assetView(db, db.assets[a.id] || a)),
  );
  const coverAsset = w.coverAsset
    ? await assetView(db, db.assets[w.coverAsset.id] || w.coverAsset)
    : null;
  const resources = [...assets, ...(coverAsset ? [coverAsset] : [])];
  if (w.status === "PROCESSING" && Date.now() > (db.workReady[w.id] || 0)) {
    if (resources.some((a) => a.status === "FAILED")) {
      w.status = "FAILED";
      w.failureCode = "MEDIA_PROCESSING_FAILED";
      w.version++;
    } else if (resources.every((a) => a.status === "READY")) {
      w.status = "PUBLISHED";
      w.publishedAt = iso();
      w.version++;
    }
  }
  const coverUrl =
    coverAsset?.url ||
    (w.kind === "IMAGE" ? assets[0]?.url : null) ||
    w.cover?.url;
  return {
    ...w,
    assets,
    coverAsset,
    cover: coverUrl
      ? {
          url: coverUrl,
          width: coverAsset?.width || w.cover?.width || null,
          height: coverAsset?.height || w.cover?.height || null,
          blurHash: null,
        }
      : null,
    author: publicUser(db, w.author.id),
    viewer: {
      liked: !!account && !!db.likes[w.id]?.includes(account.user.id),
      bookmarked: !!account && !!db.bookmarks[w.id]?.includes(account.user.id),
      isOwner: w.author.id === account?.user.id,
    },
  };
}
function card(w: T.WorkDetail): T.WorkCard {
  const {
    body: _,
    assets: _a,
    coverAsset: _c,
    musicLink: _m,
    updatedAt: _u,
    failureCode: _f,
    ...c
  } = w;
  return c;
}
function version(request: Request, w: T.WorkDetail) {
  if (consume("version-conflict")) {
    w.version++;
  }
  const h = request.headers.get("If-Match");
  if (!h) fail(428, "PRECONDITION_REQUIRED", "缺少作品版本");
  if (h !== `"v${w.version}"`)
    fail(412, "VERSION_CONFLICT", "作品已在其他位置更新，请核对最新版本");
}
function owner(w: T.WorkDetail, account: Account) {
  if (w.author.id !== account.user.id)
    fail(403, "FORBIDDEN", "只有作者可以操作");
}
function applyInput(
  db: Database,
  w: T.WorkDetail,
  b: T.WorkInput,
  account: Account,
  publish = false,
) {
  if (w.kind !== b.kind)
    fail(409, "WORK_KIND_IMMUTABLE", "创建后不能更改作品类型");
  const assets = b.mediaAssetIds.map((id) => {
    const a = db.assets[id];
    if (!a || db.assetOwners[id] !== account.user.id)
      fail(422, "ASSET_OWNERSHIP_INVALID", "无法使用这个文件");
    if (a.purpose !== "WORK_MEDIA" || a.mediaType !== b.kind)
      fail(422, "ASSET_TYPE_MISMATCH", "主媒体类型不符");
    return a;
  });
  const cover = b.coverAssetId ? db.assets[b.coverAssetId] : null;
  if (
    b.coverAssetId &&
    (!cover ||
      cover.purpose !== "WORK_COVER" ||
      cover.mediaType !== "IMAGE" ||
      db.assetOwners[cover.id] !== account.user.id)
  )
    fail(422, "ASSET_TYPE_MISMATCH", "封面需为本人上传的封面图片");
  const music = b.musicLinkId ? db.music[b.musicLinkId] : null;
  if (b.musicLinkId && (!music || music.owner !== account.user.id))
    fail(422, "ASSET_OWNERSHIP_INVALID", "无法使用这个音乐链接");
  if ((b.kind === "TEXT" || b.kind === "MUSIC_LINK") && assets.length)
    fail(422, "VALIDATION_FAILED", "此类型不能关联主文件", "mediaAssetIds");
  if (b.kind !== "MUSIC_LINK" && b.musicLinkId)
    fail(422, "VALIDATION_FAILED", "此类型不能关联音乐链接", "musicLinkId");
  if (publish) {
    if (b.kind === "TEXT" && !b.body.trim())
      fail(422, "VALIDATION_FAILED", "请输入正文", "body");
    if (b.kind === "IMAGE" && (assets.length < 1 || assets.length > 9))
      fail(422, "VALIDATION_FAILED", "图片作品需 1–9 张图片", "mediaAssetIds");
    if (["VIDEO", "AUDIO"].includes(b.kind) && assets.length !== 1)
      fail(422, "VALIDATION_FAILED", "请选择一个主媒体文件", "mediaAssetIds");
    if (b.kind === "MUSIC_LINK" && !music)
      fail(422, "VALIDATION_FAILED", "请先解析音乐链接", "musicLinkId");
    if (
      [...assets, ...(cover ? [cover] : [])].some(
        (a) => a.status === "FAILED" || a.status === "CREATED",
      )
    )
      fail(422, "ASSET_NOT_READY", "文件尚未完成或处理失败");
  }
  if (!b.title.trim()) fail(422, "VALIDATION_FAILED", "请输入标题", "title");
  Object.assign(w, {
    title: b.title.trim(),
    body: b.body.trim(),
    excerpt: Array.from(b.body.trim()).slice(0, 280).join(""),
    assets,
    coverAsset: cover,
    musicLink: music?.value || null,
    updatedAt: iso(),
  });
}
function workInput(w: T.WorkDetail): T.WorkInput {
  return {
    kind: w.kind,
    title: w.title,
    body: w.body,
    mediaAssetIds: w.assets.map((a) => a.id),
    coverAssetId: w.coverAsset?.id || null,
    musicLinkId: w.musicLink?.id || null,
  };
}
function paginate<T>(db: Database, url: URL, list: T[], sessionId?: string) {
  const path = url.pathname,
    filter = url.searchParams.get("status") || "",
    cursor = url.searchParams.get("cursor"),
    limit = Number(url.searchParams.get("limit") || 24);
  if (!Number.isInteger(limit) || limit < 1 || limit > 50)
    fail(422, "VALIDATION_FAILED", "分页数量需为 1–50");
  let offset = 0;
  if (cursor) {
    const c = db.cursors[cursor];
    if (!c || c.path !== path || c.filter !== filter)
      fail(400, "INVALID_CURSOR", "分页游标无效");
    offset = c.offset;
  }
  const identity = (value: T) =>
    typeof value === "string" ? value : (value as { id: string }).id;
  const cursorState = cursor
    ? (db.cursors[cursor] as (typeof db.cursors)[string] & { ids?: string[] })
    : undefined;
  const ids = cursorState?.ids || list.map(identity),
    values = new Map(list.map((value) => [identity(value), value]));
  const items = ids
      .slice(offset, offset + limit)
      .flatMap((id) => (values.has(id) ? [values.get(id)!] : [])),
    hasMore = offset + limit < ids.length;
  let nextCursor: string | null = null;
  if (hasMore) {
    nextCursor = crypto.randomUUID();
    db.cursors[nextCursor] = {
      path,
      filter,
      offset: offset + limit,
      session: sessionId,
      ...{ ids },
    };
  }
  return { items, nextCursor, hasMore };
}
export async function mockStoragePut(
  ticket: T.UploadTicket,
  file: Blob,
  signal?: AbortSignal,
  progress?: (v: number) => void,
) {
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
  const db = read(),
    u = db.uploads[ticket.uploadId];
  if (!u || u.cancelled) fail(404, "UPLOAD_NOT_FOUND", "上传已取消");
  if (Date.parse(ticket.expiresAt) < Date.now())
    fail(410, "UPLOAD_EXPIRED", "上传票据过期，请重试");
  progress?.(10);
  await storeBlob(ticket.assetId, file);
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
  const fresh = read();
  fresh.uploads[ticket.uploadId].received = file.size;
  if (!fresh.uploads[ticket.uploadId].complete)
    fresh.assets[ticket.assetId].status = "UPLOADED";
  save(fresh);
  progress?.(100);
}

async function dispatchMock(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const request =
    input instanceof Request
      ? input
      : new Request(
          new URL(
            input.toString(),
            globalThis.location?.origin || "http://localhost:5173",
          ),
          init,
        );
  const requestId = `req_${crypto.randomUUID()}`,
    url = new URL(request.url),
    path = url.pathname.replace(/^\/api\/v1/, "");
  const db = read();
  let status = 200;
  for (const a of db.accounts)
    if (a.avatarAssetId) a.user.avatarUrl = await blobUrl(a.avatarAssetId);
  const headers = new Headers({
    "Content-Type": "application/json",
    "X-Request-Id": requestId,
    "Cache-Control": "private, no-store",
  });
  try {
    if (request.signal.aborted) throw new DOMException("Aborted", "AbortError");
    const endpoint = operations.find(
      (o) => o.method === request.method && o.regex.test(path),
    );
    if (!endpoint) fail(404, "NOT_FOUND", "未定义的接口");
    const op = endpoint.operation as any,
      match = path.match(endpoint.regex)!,
      params = Object.fromEntries(
        endpoint.parameters.map((name, i) => [
          name,
          decodeURIComponent(match[i + 1]),
        ]),
      );
    let b: any;
    if (op.requestBody) {
      try {
        b = await request.json();
      } catch {
        fail(400, "BAD_REQUEST", "JSON 请求体无效");
      }
    }
    if (op.requestBody) {
      const errors = validateBody(
        op.requestBody.content["application/json"].schema,
        b,
      );
      if (errors.length)
        throw new Failure(422, "VALIDATION_FAILED", "请检查输入内容", errors);
    }
    if (
      request.method === "POST" &&
      path.startsWith("/auth/") &&
      (!csrf ||
        request.headers.get("X-CSRF-Token") !== csrf ||
        request.credentials !== "include")
    )
      fail(403, "CSRF_INVALID", "安全上下文已过期");
    if (request.method !== "GET" && consume("rate-limit"))
      throw new Failure(429, "RATE_LIMITED", "操作频繁，请稍后再试", [], 5);
    const idempotent = ["createWork", "createUpload", "createComment"].includes(
      op.operationId,
    );
    const account = authenticate(
      db,
      request,
      op.security?.length > 0 &&
        !op.security.some((s: any) => Object.keys(s).length === 0) &&
        op.security.some((s: any) => s.bearerAuth),
    );
    const idemKey = `${account?.user.id}:${request.method}:${path}:${request.headers.get("Idempotency-Key")}`;
    if (idempotent) {
      const key = request.headers.get("Idempotency-Key");
      if (!key || !/^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(key))
        fail(422, "VALIDATION_FAILED", "缺少有效的幂等键");
      const old = db.idempotency[idemKey];
      if (old) {
        if (old.body !== JSON.stringify(b))
          fail(409, "IDEMPOTENCY_CONFLICT", "同一意图的请求内容不同");
        return new Response(JSON.stringify({ data: old.data, requestId }), {
          status: old.status,
          headers,
        });
      }
    }
    let data: unknown;
    switch (op.operationId) {
      case "getCapabilities":
        data = capabilities;
        break;
      case "getCsrf":
        csrf = crypto.randomUUID();
        data = {
          csrfToken: csrf,
          expiresAt: new Date(Date.now() + 3600000).toISOString(),
        };
        break;
      case "register": {
        const email = b.email.trim().toLowerCase();
        if (db.accounts.some((a) => a.user.username === b.username))
          fail(409, "USERNAME_TAKEN", "用户名已被使用", "username");
        if (!db.accounts.some((a) => a.email === email)) {
          db.accounts.push({
            user: {
              ...demoUser,
              id: `user_${crypto.randomUUID()}`,
              username: b.username,
              displayName: b.displayName.trim(),
              bio: "",
              publishedWorkCount: 0,
              createdAt: iso(),
            },
            email,
            verified: false,
            settings: { ...defaultSettings },
            resent: Date.now(),
            attempts: 0,
          });
          passwords.set(email, b.password);
        }
        status = 202;
        data = accepted;
        break;
      }
      case "verifyEmail": {
        const a = db.accounts.find(
          (a) => a.email === b.email.trim().toLowerCase(),
        );
        if (!a) fail(422, "VERIFICATION_CODE_INVALID", "验证码不正确", "code");
        if (!a.verified) {
          if (a.attempts >= 5)
            throw new Failure(
              429,
              "VERIFICATION_ATTEMPTS_EXCEEDED",
              "尝试次数过多，请重发验证码",
              [],
              60,
            );
          if (a.resent && Date.now() - a.resent > 600000)
            fail(410, "VERIFICATION_CODE_EXPIRED", "验证码已过期");
          if (b.code !== ((a.resendCount || 0) % 2 ? "654321" : "123456")) {
            a.attempts++;
            fail(422, "VERIFICATION_CODE_INVALID", "验证码不正确", "code");
          }
          a.verified = true;
        }
        data = ack;
        break;
      }
      case "resendVerification": {
        const a = db.accounts.find(
          (a) => a.email === b.email.trim().toLowerCase(),
        );
        if (a) {
          const wait = Math.ceil((a.resent + 60000 - Date.now()) / 1000);
          if (wait > 0)
            throw new Failure(429, "RATE_LIMITED", "请等待后重发", [], wait);
          a.resent = Date.now();
          a.attempts = 0;
          a.resendCount = (a.resendCount || 0) + 1;
        }
        status = 202;
        data = accepted;
        break;
      }
      case "login": {
        const a = db.accounts.find(
          (a) => a.email === b.email.trim().toLowerCase(),
        );
        if (!a || passwords.get(a.email) !== b.password)
          fail(401, "INVALID_CREDENTIALS", "邮箱或密码不正确");
        if (!a.verified) fail(403, "EMAIL_NOT_VERIFIED", "请先验证邮箱");
        localStorage.setItem(sessionKey, `${a.user.id}|${crypto.randomUUID()}`);
        data = session(db, a);
        break;
      }
      case "refreshSession": {
        const id = localStorage.getItem(sessionKey)?.split("|")[0],
          a = db.accounts.find((a) => a.user.id === id && a.verified);
        if (!a) fail(401, "REFRESH_INVALID", "没有可恢复的会话");
        data = session(db, a);
        break;
      }
      case "logout":
        localStorage.removeItem(sessionKey);
        data = ack;
        break;
      case "forgotPassword": {
        const email = b.email.trim().toLowerCase();
        resetCredential = db.accounts.some((a) => a.email === email)
          ? { token: crypto.randomUUID(), email, expires: Date.now() + 1800000 }
          : undefined;
        status = 202;
        data = accepted;
        break;
      }
      case "resetPassword":
        if (!resetCredential || resetCredential.token !== b.token)
          fail(422, "RESET_TOKEN_INVALID", "重置凭证无效");
        if (resetCredential.expires < Date.now())
          fail(410, "RESET_TOKEN_EXPIRED", "重置凭证已过期");
        passwords.set(resetCredential.email, b.newPassword);
        resetCredential = undefined;
        localStorage.removeItem(sessionKey);
        tokens.clear();
        data = ack;
        break;
      case "changePassword": {
        const a = requiredAccount(db, request);
        if (passwords.get(a.email) !== b.currentPassword)
          fail(401, "INVALID_CREDENTIALS", "当前密码不正确", "currentPassword");
        passwords.set(a.email, b.newPassword);
        localStorage.removeItem(sessionKey);
        tokens.clear();
        data = ack;
        break;
      }
      case "getMe":
        data = me(db, requiredAccount(db, request));
        break;
      case "updateMe": {
        const a = requiredAccount(db, request);
        if (b.displayName !== undefined) {
          if (!b.displayName.trim())
            fail(422, "VALIDATION_FAILED", "昵称不能为空", "displayName");
          a.user.displayName = b.displayName.trim();
        }
        if (b.bio !== undefined) a.user.bio = b.bio;
        if (b.avatarAssetId !== undefined) {
          const asset = b.avatarAssetId ? db.assets[b.avatarAssetId] : null;
          if (
            b.avatarAssetId &&
            (!asset ||
              asset.purpose !== "AVATAR" ||
              asset.status !== "READY" ||
              db.assetOwners[asset.id] !== a.user.id)
          )
            fail(422, "ASSET_NOT_READY", "头像尚不可用");
          a.avatarAssetId = b.avatarAssetId;
          a.user.avatarUrl = asset ? await blobUrl(asset.id) : null;
        }
        data = me(db, a);
        break;
      }
      case "getSettings":
        data = requiredAccount(db, request).settings;
        break;
      case "updateSettings": {
        const a = requiredAccount(db, request);
        Object.assign(a.settings, b);
        data = a.settings;
        break;
      }
      case "getUser": {
        if (!db.accounts.some((a) => a.user.id === params.userId))
          fail(404, "USER_NOT_FOUND", "用户不存在");
        data = publicUser(db, params.userId);
        break;
      }
      case "getFeed": {
        const cursor = url.searchParams.get("cursor");
        let sid = cursor ? db.cursors[cursor]?.session : undefined;
        if (cursor && !sid) fail(400, "INVALID_CURSOR", "游标无效");
        if (!sid) {
          sid = crypto.randomUUID();
          db.feeds[sid] = {
            ids: db.works
              .filter((w) => w.status === "PUBLISHED")
              .map((w) => w.id)
              .sort(() => Math.random() - 0.5),
            expiresAt: new Date(Date.now() + 1800000).toISOString(),
          };
        }
        const feed = db.feeds[sid];
        if (
          !feed ||
          Date.parse(feed.expiresAt) < Date.now() ||
          consume("feed-expired")
        )
          fail(
            410,
            "FEED_SESSION_EXPIRED",
            "这次探索已过期，画面已保留，刷新后开始新的探索",
          );
        const cards = await Promise.all(
          feed.ids
            .map((id) =>
              db.works.find((w) => w.id === id && w.status === "PUBLISHED"),
            )
            .filter((w): w is T.WorkDetail => !!w)
            .map(async (w) => card(await detail(db, w, account))),
        );
        data = {
          ...paginate(db, url, cards, sid),
          feedSession: { id: sid, expiresAt: feed.expiresAt },
        };
        break;
      }
      case "listUserWorks":
      case "listMyWorks":
      case "listMyBookmarks": {
        if (
          op.operationId === "listUserWorks" &&
          !db.accounts.some((a) => a.user.id === params.userId)
        )
          fail(404, "USER_NOT_FOUND", "用户不存在");
        const mine =
          op.operationId !== "listUserWorks"
            ? requiredAccount(db, request)
            : account;
        for (const w of db.works) await detail(db, w, mine);
        let list = db.works.filter((w) =>
          op.operationId === "listMyWorks"
            ? w.author.id === mine!.user.id
            : op.operationId === "listMyBookmarks"
              ? w.status === "PUBLISHED" &&
                db.bookmarks[w.id]?.includes(mine!.user.id)
              : w.status === "PUBLISHED" && w.author.id === params.userId,
        );
        const filter = url.searchParams.get("status");
        if (filter) list = list.filter((w) => w.status === filter);
        list.sort(
          (a, b) =>
            b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id),
        );
        if (op.operationId === "listMyBookmarks") {
          const order =
            db.bookmarkOrder?.[mine!.user.id] ||
            Object.keys(db.bookmarks).reverse();
          list.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
        }
        data = paginate(
          db,
          url,
          await Promise.all(
            list.map(async (w) => card(await detail(db, w, mine))),
          ),
        );
        break;
      }
      case "createWork": {
        const a = requiredAccount(db, request);
        const w: T.WorkDetail = {
          id: `work_${crypto.randomUUID()}`,
          kind: b.kind,
          status: "DRAFT",
          version: 1,
          title: "",
          body: "",
          excerpt: "",
          author: a.user,
          cover: null,
          stats: { likeCount: 0, bookmarkCount: 0, commentCount: 0 },
          viewer: { liked: false, bookmarked: false, isOwner: true },
          assets: [],
          coverAsset: null,
          musicLink: null,
          createdAt: iso(),
          publishedAt: null,
          updatedAt: iso(),
          failureCode: null,
        };
        applyInput(db, w, b, a);
        db.works.unshift(w);
        data = await detail(db, w, a);
        status = 201;
        break;
      }
      case "getWork":
        if (consume("resource-404")) fail(404, "WORK_NOT_FOUND", "作品不存在");
        data = await detail(db, work(db, params.workId, account), account);
        break;
      case "updateWork":
      case "publishWork":
      case "deleteWork": {
        const a = requiredAccount(db, request);
        if (
          op.operationId === "deleteWork" &&
          db.deleted[params.workId] === a.user.id
        ) {
          data = ack;
          break;
        }
        const w = work(db, params.workId, a);
        owner(w, a);
        version(request, w);
        if (op.operationId === "deleteWork") {
          db.deleted[w.id] = a.user.id;
          db.works = db.works.filter((x) => x.id !== w.id);
          data = ack;
          break;
        }
        if (op.operationId === "updateWork") {
          if (!["DRAFT", "FAILED"].includes(w.status))
            fail(409, "WORK_NOT_EDITABLE", "当前状态不能修改");
          applyInput(db, w, b, a);
          w.status = "DRAFT";
          w.failureCode = null;
          w.version++;
        } else if (["DRAFT", "FAILED"].includes(w.status)) {
          if (w.status === "FAILED")
            fail(409, "WORK_NOT_EDITABLE", "请先保存修正后的草稿");
          applyInput(db, w, workInput(w), a, true);
          w.status = [
            ...w.assets,
            ...(w.coverAsset ? [w.coverAsset] : []),
          ].every((a) => a.status === "READY")
            ? "PUBLISHED"
            : "PROCESSING";
          w.version++;
          if (w.status === "PUBLISHED") w.publishedAt = iso();
          else db.workReady[w.id] = Date.now() + 2000;
        }
        data = await detail(db, w, a);
        if (w.status === "PROCESSING") status = 202;
        break;
      }
      case "likeWork":
      case "unlikeWork":
      case "bookmarkWork":
      case "unbookmarkWork": {
        const a = requiredAccount(db, request),
          w = work(db, params.workId, a);
        if (w.status !== "PUBLISHED")
          fail(409, "WORK_NOT_PUBLISHED", "作品尚未发布");
        const like = op.operationId.toLowerCase().includes("like"),
          store = like ? db.likes : db.bookmarks;
        const values = (store[w.id] ||= []);
        const index = values.indexOf(a.user.id);
        if (request.method === "PUT" && index < 0) {
          values.push(a.user.id);
          if (!like) {
            db.bookmarkOrder ||= {};
            db.bookmarkOrder[a.user.id] = [
              w.id,
              ...(db.bookmarkOrder[a.user.id] || []).filter(
                (id) => id !== w.id,
              ),
            ];
            delete store[w.id];
            store[w.id] = values;
          }
          w.stats[like ? "likeCount" : "bookmarkCount"]++;
        }
        if (request.method === "DELETE" && index >= 0) {
          if (!like && db.bookmarkOrder?.[a.user.id])
            db.bookmarkOrder[a.user.id] = db.bookmarkOrder[a.user.id].filter(
              (id) => id !== w.id,
            );
          values.splice(index, 1);
          w.stats[like ? "likeCount" : "bookmarkCount"] = Math.max(
            0,
            w.stats[like ? "likeCount" : "bookmarkCount"] - 1,
          );
        }
        data = {
          workId: w.id,
          liked: !!db.likes[w.id]?.includes(a.user.id),
          bookmarked: !!db.bookmarks[w.id]?.includes(a.user.id),
          likeCount: w.stats.likeCount,
          bookmarkCount: w.stats.bookmarkCount,
        };
        break;
      }
      case "listComments":
      case "createComment": {
        const w = work(db, params.workId, account);
        if (w.status !== "PUBLISHED")
          fail(409, "WORK_NOT_PUBLISHED", "作品尚未发布");
        if (op.operationId === "createComment") {
          const a = requiredAccount(db, request);
          if (!b.body.trim())
            fail(422, "VALIDATION_FAILED", "评论不能为空", "body");
          const c: T.Comment = {
            id: `comment_${crypto.randomUUID()}`,
            workId: w.id,
            author: publicUser(db, a.user.id),
            body: b.body.trim(),
            canDelete: true,
            createdAt: iso(),
          };
          db.comments.push(c);
          w.stats.commentCount++;
          data = c;
          status = 201;
        } else
          data = paginate(
            db,
            url,
            db.comments
              .filter((c) => c.workId === w.id)
              .map((c) => ({
                ...c,
                author: publicUser(db, c.author.id),
                canDelete:
                  account?.user.id === c.author.id ||
                  account?.user.id === w.author.id,
              })),
          );
        break;
      }
      case "deleteComment": {
        const a = requiredAccount(db, request),
          c = db.comments.find((c) => c.id === params.commentId);
        if (!c) {
          data = ack;
          break;
        }
        const w = work(db, c.workId, a);
        if (a.user.id !== c.author.id && a.user.id !== w.author.id)
          fail(403, "FORBIDDEN", "无法删除此评论");
        db.comments = db.comments.filter((x) => x.id !== c.id);
        w.stats.commentCount = Math.max(0, w.stats.commentCount - 1);
        data = ack;
        break;
      }
      case "createUpload": {
        const a = requiredAccount(db, request),
          rule = capabilities.uploadRules.find(
            (r) =>
              r.purpose === b.purpose &&
              r.allowedMimeTypes.includes(b.contentType),
          );
        if (!rule) fail(415, "UNSUPPORTED_MEDIA_TYPE", "不支持这个文件格式");
        if (b.sizeBytes > rule.maxBytes)
          fail(413, "FILE_TOO_LARGE", "文件超过大小限制");
        const id = crypto.randomUUID(),
          ticket: T.UploadTicket = {
            uploadId: `upload_${id}`,
            assetId: `asset_${id}`,
            uploadUrl: absolute(`/mock-storage/upload_${id}`),
            method: "PUT",
            headers: { "Content-Type": b.contentType },
            expiresAt: new Date(Date.now() + 900000).toISOString(),
            maxBytes: rule.maxBytes,
          };
        db.uploads[ticket.uploadId] = {
          ticket,
          input: b,
          owner: a.user.id,
          complete: false,
          cancelled: false,
          received: 0,
          readyAt: 0,
          fail: consume("asset-failed") || b.fileName.includes(".fail."),
        };
        db.assetOwners[ticket.assetId] = a.user.id;
        db.assets[ticket.assetId] = {
          id: ticket.assetId,
          purpose: b.purpose,
          mediaType: rule.mediaType,
          status: "CREATED",
          mimeType: b.contentType,
          sizeBytes: b.sizeBytes,
          url: null,
          thumbnailUrl: null,
          width: null,
          height: null,
          durationMs: null,
          failureCode: null,
          createdAt: iso(),
        };
        data = ticket;
        status = 201;
        break;
      }
      case "completeUpload":
      case "cancelUpload": {
        const a = requiredAccount(db, request),
          u = db.uploads[params.uploadId];
        if (!u || u.cancelled || u.owner !== a.user.id)
          fail(404, "UPLOAD_NOT_FOUND", "上传不存在");
        if (op.operationId === "cancelUpload") {
          if (u.complete)
            fail(409, "UPLOAD_ALREADY_COMPLETED", "文件已完成上传");
          u.cancelled = true;
          data = ack;
          break;
        }
        if (Date.parse(u.ticket.expiresAt) < Date.now())
          fail(410, "UPLOAD_EXPIRED", "上传票据已过期");
        if (u.received !== b.sizeBytes || u.received !== u.input.sizeBytes)
          fail(422, "VALIDATION_FAILED", "实际上传大小不符");
        if (!u.complete) {
          u.complete = true;
          u.readyAt = Date.now() + 2500;
          db.assets[u.ticket.assetId].status = "PROCESSING";
        }
        data = await assetView(db, db.assets[u.ticket.assetId]);
        status = 202;
        break;
      }
      case "getAsset": {
        const a = db.assets[params.assetId];
        const publicAsset =
          db.accounts.some(
            (account) => account.avatarAssetId === params.assetId,
          ) ||
          db.works.some(
            (w) =>
              w.status === "PUBLISHED" &&
              (w.assets.some((a) => a.id === params.assetId) ||
                w.coverAsset?.id === params.assetId),
          );
        if (!a || (!publicAsset && db.assetOwners[a.id] !== account?.user.id))
          fail(404, "ASSET_NOT_FOUND", "资源不存在");
        data = await assetView(db, a);
        break;
      }
      case "resolveMusicLink": {
        const a = requiredAccount(db, request),
          urls = b.input.match(/https:\/\/[^\s<>]+/g);
        if (!urls || urls.length !== 1)
          fail(
            400,
            "MUSIC_LINK_INVALID",
            "请输入包含一条 HTTPS 链接的分享文案",
          );
        let parsed: URL;
        try {
          parsed = new URL(urls[0]);
        } catch {
          fail(400, "MUSIC_LINK_INVALID", "音乐链接无效");
        }
        const sourceUrl = parsed.href;
        if (new URL(sourceUrl).username || new URL(sourceUrl).password)
          fail(400, "MUSIC_LINK_INVALID", "音乐链接无效");
        const value: T.MusicLink = {
          id: `music_${crypto.randomUUID()}`,
          provider:
            parsed.hostname === "music.163.com"
              ? "NETEASE"
              : ["open.spotify.com", "spotify.com"].includes(parsed.hostname)
                ? "SPOTIFY"
                : "OTHER",
          sourceUrl,
          title: null,
          artist: null,
          coverUrl: null,
          metadataStatus: "PARTIAL",
          playback: { mode: "EXTERNAL", embedUrl: null },
        };
        db.music[value.id] = { owner: a.user.id, value };
        data = value;
        break;
      }
      default:
        fail(500, "INTERNAL_ERROR", "Mock 未实现这个契约操作");
    }
    const schema =
      op.responses[String(status)].content["application/json"].schema;
    const envelope = { data, requestId };
    const check = validator(schema);
    if (!check(envelope))
      throw new Error(
        `Mock response violates ${op.operationId}: ${JSON.stringify(check.errors)}`,
      );
    if (data && typeof data === "object" && "version" in data)
      headers.set("ETag", `"v${data.version}"`);
    if (idempotent)
      db.idempotency[idemKey] = { body: JSON.stringify(b), status, data };
    save(db);
    return new Response(JSON.stringify(envelope), { status, headers });
  } catch (error) {
    save(db);
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    if (!(error instanceof Failure)) {
      console.error(error);
      throw error;
    }
    if (error.retry) headers.set("Retry-After", String(error.retry));
    return new Response(
      JSON.stringify({
        code: error.code,
        message: error.message,
        requestId,
        details: error.details,
      }),
      { status: error.status, headers },
    );
  }
}
let mockFlight: Promise<unknown> = Promise.resolve();
export function mockFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const run = async () =>
    typeof navigator !== "undefined" && navigator.locks
      ? await navigator.locks.request("htw-mock-business", () =>
          dispatchMock(input, init),
        )
      : await dispatchMock(input, init);
  const response = mockFlight.then(run, run);
  mockFlight = response.catch(() => {});
  return response;
}
