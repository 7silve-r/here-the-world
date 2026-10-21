import { useEffect, useState } from "react";
import {
  Link,
  Navigate,
  useLocation,
  useNavigate,
  useSearchParams,
} from "react-router-dom";
import { ArrowRight, ArrowUpRight, Globe2, Mail } from "lucide-react";
import {
  acceptSession,
  api,
  ApiError,
  clearSession,
  mockMode,
  safeReturnTo,
} from "../api/client";
import { useAuth } from "../shared/hooks";
import { Button, Loading, Notice, Success } from "../shared/ui";
import { Field, passwordValid, text } from "../shared/forms";

let resetToken = "";
export function captureResetToken() {
  if (location.pathname === "/reset-password" && location.hash) {
    resetToken = new URLSearchParams(location.hash.slice(1)).get("token") || "";
    history.replaceState(
      history.state,
      "",
      location.pathname + location.search,
    );
  }
}
export function Protected({ children }: { children: React.ReactNode }) {
  const { session, ready } = useAuth(),
    location = useLocation();
  if (!ready) return <Loading />;
  if (!session)
    return (
      <Navigate
        replace
        to={`/login?returnTo=${encodeURIComponent(safeReturnTo(location.pathname + location.search))}`}
      />
    );
  return <div key={session.user.id}>{children}</div>;
}
const titles: Record<string, [string, string, string]> = {
  login: [
    "WELCOME BACK",
    "世界很大，很高兴又见。",
    "登录，继续你的下一次相遇。",
  ],
  register: [
    "HELLO, WORLD",
    "让世界，多一种看见。",
    "从一个属于你的名字开始。",
  ],
  "verify-email": [
    "ONE MORE STEP",
    "一封邮件，一次相遇。",
    "输入邮箱收到的 6 位验证码。",
  ],
  "forgot-password": [
    "FIND YOUR WAY BACK",
    "总有一条路，回到这里。",
    "我们会向你的邮箱发送密码重置链接。",
  ],
  "reset-password": [
    "A FRESH START",
    "重新开始，也很好。",
    "设置新密码后，请重新登录。",
  ],
};
export default function AuthPage() {
  const location = useLocation(),
    mode = location.pathname.slice(1),
    navigate = useNavigate(),
    [search] = useSearchParams();
  const [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [email, setEmail] = useState(
      () => sessionStorage.getItem("htw.email") || "",
    ),
    [remaining, setRemaining] = useState(0),
    [mockLink, setMockLink] = useState<string | null>(null);
  const returnTo = safeReturnTo(
    search.get("returnTo") || sessionStorage.getItem("htw.returnTo"),
  );
  useEffect(() => {
    if (mode === "reset-password") captureResetToken();
    setError(undefined);
    setMessage("");
    setMockLink(null);
  }, [mode]);
  useEffect(() => {
    if (remaining > 0) {
      const timer = setTimeout(() => setRemaining((v) => v - 1), 1000);
      return () => clearTimeout(timer);
    }
  }, [remaining]);
  const [eyebrow, title, subtitle] = titles[mode] || titles.login;
  const remember = (value: string) => {
    sessionStorage.setItem("htw.email", value);
    sessionStorage.setItem("htw.returnTo", returnTo);
  };
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    setError(undefined);
    setMessage("");
    setBusy(true);
    const mail = text(form, "email").trim().toLowerCase();
    try {
      if (mode === "login") {
        remember(mail);
        const session = await api("login", {
          email: mail,
          password: text(form, "password"),
        });
        acceptSession(session);
        navigate(returnTo, { replace: true });
      } else if (mode === "register") {
        const password = text(form, "password");
        passwordValid(password);
        await api("register", {
          email: mail,
          password,
          username: text(form, "username"),
          displayName: text(form, "displayName"),
        });
        remember(mail);
        setRemaining(60);
        navigate(`/verify-email?returnTo=${encodeURIComponent(returnTo)}`);
      } else if (mode === "verify-email") {
        await api("verifyEmail", { email: mail, code: text(form, "code") });
        remember(mail);
        setMessage("邮箱已验证。现在可以使用密码登录。");
        navigate(`/login?returnTo=${encodeURIComponent(returnTo)}`, {
          replace: true,
        });
      } else if (mode === "forgot-password") {
        await api("forgotPassword", { email: mail });
        remember(mail);
        setMessage("如果这个邮箱存在，重置邮件将很快抵达。请查看收件箱。");
        setRemaining(60);
        if (mockMode) {
          const mock = await import("../mocks/server");
          setMockLink(mock.mockResetLink());
        }
      } else if (mode === "reset-password") {
        const newPassword = text(form, "newPassword");
        passwordValid(newPassword);
        if (!resetToken) throw new Error("缺少密码重置凭证，请重新申请邮件。");
        await api("resetPassword", { token: resetToken, newPassword });
        resetToken = "";
        clearSession();
        navigate("/login", { replace: true });
      }
    } catch (e) {
      setError(e);
      if (e instanceof ApiError) {
        if (e.retryAfter) setRemaining(e.retryAfter);
        if (e.code === "EMAIL_NOT_VERIFIED") {
          remember(mail);
          navigate(`/verify-email?returnTo=${encodeURIComponent(returnTo)}`);
        }
      }
    } finally {
      setBusy(false);
    }
  }
  const query = `?returnTo=${encodeURIComponent(returnTo)}`;
  return (
    <main className="auth-page">
      <section className="auth-art">
        <div className="auth-art-top">
          <Globe2 size={19} />
          <span>HERE, YOU BELONG.</span>
        </div>
        <div className="auth-art-center">
          <span>在此，</span>
          <span>遇见万千。</span>
          <p>
            Every little world
            <br />
            makes the world.
          </p>
        </div>
        <div className="auth-art-bottom">
          山海、日常，以及每一个独一无二的你。
          <ArrowUpRight size={22} />
        </div>
      </section>
      <section className="auth-form">
        <Link to="/" className="back-link">
          ← 回到世界
        </Link>
        <span className="eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p className="muted">{subtitle}</p>
        <Notice error={error} />
        {message && <Success>{message}</Success>}
        <form onSubmit={submit}>
          {mode !== "reset-password" && (
            <Field
              label="邮箱地址"
              name="email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
              error={error}
            />
          )}
          {mode === "register" && (
            <>
              <Field
                label="用户名"
                name="username"
                autoComplete="username"
                placeholder="属于你的英文名字"
                pattern="[a-z0-9_]{3,24}"
                required
                hint="3–24 位小写字母、数字或下划线，注册后不可修改。"
                error={error}
              />
              <Field
                label="昵称"
                name="displayName"
                placeholder="你希望被怎样称呼"
                required
                error={error}
              />
            </>
          )}
          {(mode === "login" || mode === "register") && (
            <Field
              label="密码"
              name="password"
              type="password"
              autoComplete={
                mode === "login" ? "current-password" : "new-password"
              }
              required
              hint={mode === "register" ? "10–128 个字符。" : undefined}
              error={error}
            />
          )}
          {mode === "reset-password" && (
            <Field
              label="新密码"
              name="newPassword"
              type="password"
              autoComplete="new-password"
              required
              hint="10–128 个字符。"
              error={error}
            />
          )}
          {mode === "verify-email" && (
            <Field
              label="邮箱验证码"
              name="code"
              inputMode="numeric"
              pattern="[0-9]{6}"
              autoComplete="one-time-code"
              placeholder="6 位数字"
              required
              error={error}
            />
          )}
          {mode === "login" && (
            <div className="form-inline">
              <span>欢迎带着好奇心回来。</span>
              <Link to={`/forgot-password${query}`}>忘记密码？</Link>
            </div>
          )}
          <Button
            className="primary full"
            busy={busy}
            disabled={remaining > 0 && mode !== "verify-email"}
            type="submit"
          >
            {
              (
                {
                  login: "进入世界",
                  register: "创建账号",
                  "verify-email": "验证邮箱",
                  "forgot-password": "发送重置邮件",
                  "reset-password": "保存新密码",
                } as Record<string, string>
              )[mode]
            }
            <ArrowRight size={16} />
          </Button>
        </form>
        {mode === "verify-email" && (
          <Button
            disabled={remaining > 0 || busy}
            onClick={async () => {
              setBusy(true);
              try {
                const result = await api("resendVerification", { email });
                setRemaining(result.resendAfterSeconds);
                setMessage("请求已接受，请留意邮箱。");
                setError(undefined);
              } catch (e) {
                setError(e);
                if (e instanceof ApiError) setRemaining(e.retryAfter);
              } finally {
                setBusy(false);
              }
            }}
          >
            {remaining > 0 ? `${remaining} 秒后可重新发送` : "重新发送验证码"}
          </Button>
        )}
        <div className="auth-switch">
          {mode === "login" ? (
            <>
              还没有自己的小世界？
              <Link to={`/register${query}`}>
                加入我们 <ArrowUpRight size={14} />
              </Link>
            </>
          ) : (
            <Link to={`/login${query}`}>
              已有账号，前往登录 <ArrowUpRight size={14} />
            </Link>
          )}
        </div>
        {mockMode && (
          <div className="demo-note">
            <Mail size={16} />
            <div>
              <strong>本地演示</strong>
              <p>
                账号 world@example.com · 密码 HelloWorld2026
                <br />
                待验证账号 pending@example.com · 同一密码
                <br />
                模拟验证码首次 123456，重发后 654321（交替）；不会发送真实邮件。
              </p>
              {mockLink && <Link to={mockLink}>打开模拟重置邮件 ↗</Link>}
            </div>
          </div>
        )}
      </section>
    </main>
  );
}
