import { useEffect, useState } from "react";
import {
  Link,
  Navigate,
  useLocation,
  useNavigate,
  useSearchParams,
} from "react-router-dom";
import { ArrowRight, ArrowUpRight } from "lucide-react";
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
import { MatrixPortal } from "../shared/MatrixPortal";

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
const titles: Record<string, [string, string]> = {
  login: ["登录", ""],
  register: ["注册", ""],
  "verify-email": ["验证邮箱", "输入邮箱收到的 6 位验证码。"],
  "forgot-password": ["找回密码", "我们会向你的邮箱发送密码重置链接。"],
  "reset-password": ["重置密码", "设置新密码后，请重新登录。"],
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
  const [title, subtitle] = titles[mode] || titles.login;
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
    <main className={`auth-page entry-auth auth-${mode}`}>
      <div className="auth-visual" aria-hidden="true">
        <MatrixPortal compact />
      </div>
      <section className="auth-form" aria-labelledby="auth-title">
        <Link
          className="entry-wordmark"
          to="/welcome"
          aria-label="Here the World 欢迎页"
        >
          HERE THE WORLD
        </Link>
        {(mode === "login" || mode === "register") && (
          <nav className="auth-tabs" aria-label="账号操作">
            <Link
              to={`/login${query}`}
              aria-current={mode === "login" ? "page" : undefined}
            >
              登录
            </Link>
            <Link
              to={`/register${query}`}
              aria-current={mode === "register" ? "page" : undefined}
            >
              注册
            </Link>
          </nav>
        )}
        <h1 id="auth-title">{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
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
                placeholder="英文用户名"
                pattern="[a-z0-9_]{3,24}"
                required
                hint="3–24 位小写字母、数字或下划线，注册后不可修改。"
                error={error}
              />
              <Field
                label="昵称"
                name="displayName"
                placeholder="昵称"
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
              maxLength={6}
              required
              error={error}
            />
          )}
          {mode === "login" && (
            <div className="form-inline">
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
                  login: "登录",
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
        {mode !== "login" && mode !== "register" && (
          <div className="auth-switch">
            <Link to={`/login${query}`}>
              返回登录 <ArrowUpRight size={14} />
            </Link>
          </div>
        )}
        {mockLink && (
          <Link className="mock-reset-link" to={mockLink}>
            打开模拟重置邮件 <ArrowUpRight size={14} />
          </Link>
        )}
        {mockMode && (
          <details className="auth-demo">
            <summary>本地演示账号</summary>
            <div>
              <p>
                账号 world@example.com · 密码 HelloWorld2026
                <br />
                待验证账号 pending@example.com · 同一密码
                <br />
                模拟验证码首次 123456，重发后 654321（交替）；不会发送真实邮件。
              </p>
            </div>
          </details>
        )}
      </section>
    </main>
  );
}
