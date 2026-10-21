import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ArrowUpRight, Check, LockKeyhole, Palette } from "lucide-react";
import { api, clearSession, mockMode, updateSessionUser } from "../api/client";
import type { Asset, Settings as Preferences } from "../api/generated";
import { queryClient } from "../api/cache";
import { useAuth } from "../shared/hooks";
import { Button, Notice, Page } from "../shared/ui";
import { Field, Textarea, passwordValid, text } from "../shared/forms";
import UploadField from "../publishing/UploadField";
const defaults: Preferences = {
  theme: "SYSTEM",
  motion: "SYSTEM",
  autoPlayMedia: false,
};
export function localPreferences(): Preferences {
  try {
    return {
      ...defaults,
      ...JSON.parse(localStorage.getItem("htw.preferences") || "{}"),
    };
  } catch {
    return defaults;
  }
}
export function applyPreferences(value: Preferences) {
  const root = document.documentElement;
  root.dataset.theme = value.theme;
  root.dataset.motion = value.motion;
  window.dispatchEvent(new Event("htw:preferences"));
}
export function PreferencesSync() {
  const { session } = useAuth();
  const query = useQuery({
    queryKey: ["settings", session?.user.id],
    queryFn: ({ signal }) => api("getSettings", undefined, { signal }),
    enabled: !!session,
  });
  useEffect(() => {
    applyPreferences(session ? query.data || defaults : localPreferences());
  }, [session?.user.id, query.data]);
  return null;
}
export default function Settings() {
  const { session } = useAuth(),
    navigate = useNavigate();
  const me = useQuery({
    queryKey: ["me", session?.user.id],
    queryFn: ({ signal }) => api("getMe", undefined, { signal }),
    enabled: !!session,
  });
  const settings = useQuery({
    queryKey: ["settings", session?.user.id],
    queryFn: ({ signal }) => api("getSettings", undefined, { signal }),
    enabled: !!session,
  });
  const capabilities = useQuery({
    queryKey: ["capabilities"],
    queryFn: ({ signal }) => api("getCapabilities", undefined, { signal }),
  });
  const [preferences, setPreferences] = useState(localPreferences),
    [displayName, setDisplayName] = useState(""),
    [bio, setBio] = useState(""),
    [avatars, setAvatars] = useState<Asset[]>([]),
    [avatarTouched, setAvatarTouched] = useState(false),
    [error, setError] = useState<unknown>(),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [avatarUploading, setAvatarUploading] = useState(false);
  useEffect(() => {
    setDisplayName(me.data?.displayName || "");
    setBio(me.data?.bio || "");
  }, [me.data]);
  useEffect(() => {
    setPreferences(settings.data || localPreferences());
  }, [settings.data, session?.user.id]);
  useEffect(() => {
    setAvatars([]);
    setAvatarTouched(false);
    setError(undefined);
    setMessage("");
  }, [session?.user.id]);
  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(undefined);
    setMessage("");
    try {
      await fn();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Page eyebrow="A WORLD THAT FEELS LIKE YOU" title="把这里，调成你的样子。">
      <div className="settings-layout">
        <aside className="settings-index">
          <a href="#profile">01 个人资料</a>
          <a href="#preferences">02 展示偏好</a>
          <a href="#security">03 账号安全</a>
        </aside>
        <div className="settings-main">
          <Notice
            error={error || me.error || settings.error}
            message={message}
          />
          <section id="profile" className="settings-section">
            <h2>
              个人资料 <span>01 / ABOUT YOU</span>
            </h2>
            {session ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void run(async () => {
                    if (avatarUploading)
                      throw new Error("请先等待头像上传完成");
                    if (
                      avatarTouched &&
                      avatars[0] &&
                      avatars[0].status !== "READY"
                    )
                      throw new Error("请等待头像处理完成");
                    const updated = await api("updateMe", {
                      displayName,
                      bio,
                      ...(avatarTouched
                        ? { avatarAssetId: avatars[0]?.id || null }
                        : {}),
                    });
                    updateSessionUser(updated);
                    await queryClient.invalidateQueries({ queryKey: ["me"] });
                    setMessage("个人资料已保存。");
                  });
                }}
              >
                <div className="profile-edit-intro">
                  <span className="avatar large">
                    {me.data?.avatarUrl ? (
                      <img src={me.data.avatarUrl} alt="当前头像" />
                    ) : (
                      displayName[0] || "你"
                    )}
                  </span>
                  <div>
                    <strong>{displayName}</strong>
                    <p className="muted">用一个名字，让世界记住你。</p>
                  </div>
                </div>
                <Field
                  label="昵称"
                  name="displayName"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  required
                  error={error}
                />
                <Textarea
                  label="关于自己"
                  name="bio"
                  value={bio}
                  onChange={setBio}
                  rows={3}
                  hint="最多 500 个字符"
                />
                <div className="field-pair">
                  <Field
                    label="用户名"
                    name="username"
                    value={me.data?.username || ""}
                    readOnly
                  />
                  <Field
                    label="邮箱（已验证）"
                    name="email"
                    value={me.data?.email || ""}
                    readOnly
                  />
                </div>
                {capabilities.data && (
                  <details className="cover-options">
                    <summary>更换头像</summary>
                    <UploadField
                      purpose="AVATAR"
                      mediaType="IMAGE"
                      label="上传头像"
                      capabilities={capabilities.data}
                      onChange={(assets) => {
                        setAvatars(assets);
                        setAvatarTouched(true);
                      }}
                      onBusyChange={setAvatarUploading}
                    />
                    <Button
                      type="button"
                      onClick={() => {
                        setAvatars([]);
                        setAvatarTouched(true);
                      }}
                    >
                      移除头像
                    </Button>
                  </details>
                )}
                <Button type="submit" className="primary" busy={busy}>
                  保存个人资料 <Check size={16} />
                </Button>
              </form>
            ) : (
              <p className="muted">
                登录后，为你的小世界添加一个名字。
                <Link to="/login?returnTo=%2Fsettings">
                  前往登录 <ArrowUpRight size={14} />
                </Link>
              </p>
            )}
          </section>
          <section id="preferences" className="settings-section">
            <h2>
              展示偏好 <span>02 / YOUR PERSPECTIVE</span>
            </h2>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void run(async () => {
                  const value = session
                    ? await api("updateSettings", preferences)
                    : preferences;
                  if (!session)
                    localStorage.setItem(
                      "htw.preferences",
                      JSON.stringify(value),
                    );
                  else
                    queryClient.setQueryData(
                      ["settings", session.user.id],
                      value,
                    );
                  applyPreferences(value);
                  setMessage(
                    session ? "展示偏好已保存。" : "偏好已保存在这台设备。",
                  );
                });
              }}
            >
              <label className="field">
                <span>
                  <Palette size={15} />
                  界面主题
                </span>
                <select
                  aria-label="界面主题"
                  value={preferences.theme}
                  onChange={(e) =>
                    setPreferences({
                      ...preferences,
                      theme: e.target.value as Preferences["theme"],
                    })
                  }
                >
                  <option value="SYSTEM">跟随系统</option>
                  <option value="LIGHT">浅色 · 日光</option>
                  <option value="DARK">深色 · 夜色</option>
                </select>
              </label>
              <label className="field">
                <span>动态效果</span>
                <select
                  aria-label="动态效果"
                  value={preferences.motion}
                  onChange={(e) =>
                    setPreferences({
                      ...preferences,
                      motion: e.target.value as Preferences["motion"],
                    })
                  }
                >
                  <option value="SYSTEM">跟随系统减少动态偏好</option>
                  <option value="REDUCED">减少动态效果</option>
                </select>
              </label>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={preferences.autoPlayMedia}
                  onChange={(e) =>
                    setPreferences({
                      ...preferences,
                      autoPlayMedia: e.target.checked,
                    })
                  }
                />
                <span>
                  允许详情媒体尝试自动播放
                  <small>默认关闭；浏览器可能仍需你手动点击播放。</small>
                </span>
              </label>
              <Button type="submit" busy={busy}>
                保存展示偏好
              </Button>
            </form>
          </section>
          <section id="security" className="settings-section">
            <h2>
              账号安全 <span>03 / KEEP IT YOURS</span>
            </h2>
            {session ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const form = e.currentTarget;
                  void run(async () => {
                    const newPassword = text(form, "newPassword");
                    passwordValid(newPassword);
                    await api("changePassword", {
                      currentPassword: text(form, "currentPassword"),
                      newPassword,
                    });
                    form.reset();
                    clearSession();
                    navigate("/login", { replace: true });
                  });
                }}
              >
                <Field
                  label="当前密码"
                  name="currentPassword"
                  type="password"
                  autoComplete="current-password"
                  required
                  error={error}
                />
                <Field
                  label="新密码"
                  name="newPassword"
                  type="password"
                  autoComplete="new-password"
                  required
                  hint="10–128 个字符。修改后需要重新登录。"
                  error={error}
                />
                <Button busy={busy} type="submit">
                  <LockKeyhole size={16} />
                  更新密码并重新登录
                </Button>
              </form>
            ) : (
              <Link to="/login?returnTo=%2Fsettings">
                登录以管理账号安全 <ArrowUpRight size={14} />
              </Link>
            )}
          </section>
          {mockMode && (
            <details className="mock-controls">
              <summary>开发演示 · 异常场景</summary>
              <p>
                下一次匹配的请求会触发一次所选异常；这些控件仅用于本地 Mock。
              </p>
              <div className="scenario-buttons">
                {[
                  ["rate-limit", "429 限流（下次写入）"],
                  ["feed-expired", "410 作品流过期（下次分页）"],
                  ["version-conflict", "412 草稿版本冲突"],
                  ["asset-failed", "下一个上传处理失败"],
                  ["token-expired", "401 自动共享刷新"],
                  ["resource-404", "404 作品不可用"],
                ].map(([scenario, label]) => (
                  <Button
                    key={scenario}
                    onClick={async () => {
                      const mock = await import("../mocks/server");
                      mock.setMockScenario(
                        scenario as Parameters<typeof mock.setMockScenario>[0],
                      );
                      setMessage(`已安排：${label}`);
                    }}
                  >
                    {label}
                  </Button>
                ))}
              </div>
              <Button
                onClick={async () => {
                  (await import("../mocks/server")).resetMock();
                }}
              >
                重置本地示例数据
              </Button>
              <p>
                注册密码仅保留在当前页面内存；重新载入后可使用演示账号或重新申请模拟密码重置。Mock
                会话标记仅用于演示，无法证明真实 Cookie 与服务端安全行为。
              </p>
            </details>
          )}
        </div>
      </div>
    </Page>
  );
}
