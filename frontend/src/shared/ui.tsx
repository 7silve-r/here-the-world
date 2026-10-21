import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ButtonHTMLAttributes,
  type ReactNode,
} from "react";
import { Link, useLocation } from "react-router-dom";
import {
  ArrowDown,
  ArrowLeft,
  ArrowUpRight,
  AudioLines,
  Bookmark,
  Check,
  ChevronRight,
  Compass,
  Image,
  LoaderCircle,
  Menu,
  Plus,
  Settings,
  Type,
  User,
  Video,
  X,
} from "lucide-react";
import type { WorkCard } from "../api/generated";
import { api, ApiError, clearSession, mockMode } from "../api/client";
import { useAuth } from "./hooks";
import { requestWallAction, useWallFog } from "../wall/interaction";
import { feedSnapshot, subscribeFeed, wallMemory } from "../feed/provider";
const navContext = createContext({
  open: false,
  setOpen: (_value: boolean) => {},
});
export const useNavigation = () => useContext(navContext);
export function NavigationProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setOpen(false), [location.pathname]);
  useEffect(() => {
    const listener = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", listener);
    return () => window.removeEventListener("keydown", listener);
  }, []);
  return (
    <navContext.Provider value={{ open, setOpen }}>
      {children}
    </navContext.Provider>
  );
}
export const kindNames = {
  TEXT: "文字",
  IMAGE: "影像",
  VIDEO: "短片",
  AUDIO: "声音",
  MUSIC_LINK: "音乐",
} as const;
export const kindIcons = {
  TEXT: Type,
  IMAGE: Image,
  VIDEO: Video,
  AUDIO: AudioLines,
  MUSIC_LINK: AudioLines,
};
export function Button({
  children,
  busy,
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean }) {
  return (
    <button
      {...props}
      disabled={props.disabled || busy}
      className={`button ${className}`}
      aria-busy={busy}
    >
      {busy && <LoaderCircle className="spin" size={16} />} {children}
    </button>
  );
}
export function Notice({
  error,
  message,
}: {
  error?: unknown;
  message?: string;
}) {
  const [remaining, setRemaining] = useState(0);
  useEffect(() => {
    setRemaining(error instanceof ApiError ? error.retryAfter : 0);
  }, [error]);
  useEffect(() => {
    if (remaining > 0) {
      const t = setTimeout(() => setRemaining((v) => v - 1), 1000);
      return () => clearTimeout(t);
    }
  }, [remaining]);
  if (!error && !message) return null;
  return (
    <div
      className={`notice ${error ? "error" : ""}`}
      role={error ? "alert" : "status"}
    >
      <span>
        {error instanceof Error ? error.message : message || "请求失败"}
        {remaining > 0 && ` · ${remaining} 秒后可重试`}
      </span>
      {error instanceof ApiError && (
        <>
          <small>请求编号 {error.requestId}</small>
          {error.details.map((d, i) => (
            <small key={i}>
              {d.field ? `${d.field}：` : ""}
              {d.message}
            </small>
          ))}
        </>
      )}
    </div>
  );
}
export function Loading() {
  return (
    <div className="empty" role="status">
      <LoaderCircle className="spin" />
      <p>正在寻找这一刻…</p>
    </div>
  );
}
export function Empty({
  children = "这里还留着一些空白。",
}: {
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <Compass size={36} strokeWidth={1} />
      <p>{children}</p>
      <Link to="/">
        回到世界 <ArrowUpRight size={14} />
      </Link>
    </div>
  );
}
export function Page({
  children,
  eyebrow,
  title,
  back = true,
}: {
  children: ReactNode;
  eyebrow: string;
  title: string;
  back?: boolean;
}) {
  return (
    <main className="page">
      {back && (
        <Link to="/" className="back-link">
          <ArrowLeft size={16} /> 返回作品墙
        </Link>
      )}
      <div className="page-heading">
        <span className="eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
      </div>
      {children}
    </main>
  );
}
export function Header() {
  const { open, setOpen } = useNavigation(),
    { session } = useAuth(),
    isWall = useLocation().pathname === "/";
  const menuButton = (
    <button
      className={`icon-button menu-button ${isWall ? "floating-navigation-toggle" : ""}`}
      aria-expanded={open}
      aria-controls="main-navigation"
      aria-label={open ? "收起导航" : "展开导航"}
      onClick={() => setOpen(!open)}
    >
      {open ? <X size={18} /> : <Menu size={18} />}
    </button>
  );
  if (isWall) return menuButton;
  return (
    <header className="header">
      <div className="brand-group">
        {menuButton}
        <Link to="/" className="brand">
          <span className="brand-symbol">
            h<span>∙</span>
          </span>
          <span>
            here the world<span className="brand-sub">世界在此</span>
          </span>
        </Link>
      </div>
      <span className="header-note">一个世界，无数种看见。</span>
      <div className="header-actions">
        <span className="live-dot" />
        <span className="mode-tag">{mockMode ? "本地漫游" : "共同探索"}</span>
        {session ? (
          <Link
            to="/me/works"
            className="avatar"
            title={session.user.displayName}
          >
            {session.user.avatarUrl ? (
              <img src={session.user.avatarUrl} alt="我的头像" />
            ) : (
              session.user.displayName[0]
            )}
          </Link>
        ) : (
          <Link to="/login" className="login-link">
            登录 <ArrowUpRight size={15} />
          </Link>
        )}
        <Link to="/publish" className="create-link" aria-label="留下作品">
          <Plus size={16} />
          <span>留下作品</span>
        </Link>
      </div>
    </header>
  );
}
export function Navigation() {
  const { open, setOpen } = useNavigation(),
    { session } = useAuth(),
    location = useLocation();
  const [error, setError] = useState<unknown>();
  const [fog, setFog] = useWallFog();
  useSyncExternalStore(subscribeFeed, feedSnapshot);
  if (!open) return null;
  const links = [
    ["/", "探索世界", Compass],
    ["/me/works", "我的作品", Image],
    [
      session ? `/users/${encodeURIComponent(session.user.id)}` : "/login",
      "个人主页",
      User,
    ],
    ["/publish", "发布作品", Plus],
    ["/me/bookmarks", "我的收藏", Bookmark],
    ["/settings", "展示与设置", Settings],
  ] as const;
  return (
    <nav
      id="main-navigation"
      className={`navigation ${location.pathname === "/" ? "in-wall" : ""}`}
      aria-label="主导航"
    >
      <span className="eyebrow">A PLACE TO WANDER</span>
      <h2>
        世界很大，
        <br />
        此刻在这里。
      </h2>
      <div className="nav-links">
        {links.map(([to, label, Icon]) => (
          <Link
            key={label}
            to={to}
            className={location.pathname === to ? "active" : ""}
            onClick={() => setOpen(false)}
          >
            <Icon size={18} />
            {label}
            <ChevronRight size={14} />
          </Link>
        ))}
      </div>
      <div className="nav-foot">
        <p>让好奇心，带你去远方。</p>
        {session ? (
          <button
            onClick={async () => {
              setError(undefined);
              try {
                await api("logout");
                clearSession();
                setOpen(false);
              } catch (e) {
                setError(e);
              }
            }}
          >
            退出登录 <ArrowUpRight size={14} />
          </button>
        ) : (
          <Link to="/login">
            登录 / 加入我们 <ArrowUpRight size={14} />
          </Link>
        )}
        <Notice error={error} />
      </div>
      {location.pathname === "/" && (
        <div className="navigation-wall-controls">
          <label className="fog-control">
            <span>雾化</span>
            <input
              type="range"
              aria-label="雾化程度"
              min="0"
              max="100"
              step="1"
              value={fog}
              onChange={(e) => setFog(Number(e.target.value))}
            />
            <output aria-hidden="true">{fog}%</output>
          </label>
          <div className="navigation-wall-actions">
            <Button onClick={() => requestWallAction("center")}>
              回到起点
            </Button>
            <Button onClick={() => requestWallAction("explore")}>
              重新探索
            </Button>
            <Button onClick={() => requestWallAction("clear")}>收起卡片</Button>
          </div>
          {(wallMemory.expired || wallMemory.error) && (
            <>
              <Notice
                message={
                  wallMemory.expired
                    ? "这次探索已过期，当前画面已为你保留。"
                    : undefined
                }
                error={
                  wallMemory.error ? new Error(wallMemory.error) : undefined
                }
              />
              <Button onClick={() => requestWallAction("retry")}>
                重新加载探索
              </Button>
            </>
          )}
        </div>
      )}
    </nav>
  );
}
export function Artwork({
  work,
  priority = false,
}: {
  work: WorkCard;
  priority?: boolean;
}) {
  const [failed, setFailed] = useState(false);
  const [imageUrl, setImageUrl] = useState(work.cover?.url);
  const retried = useRef(false),
    request = useRef(new AbortController());
  useEffect(() => {
    setFailed(false);
    setImageUrl(work.cover?.url);
    retried.current = false;
    request.current = new AbortController();
    return () => request.current.abort();
  }, [work.id, work.cover?.url]);
  async function reloadCover() {
    const signal = request.current.signal;
    if (retried.current) {
      setFailed(true);
      return;
    }
    retried.current = true;
    try {
      const latest = await api("getWork", undefined, {
        params: { workId: work.id },
        signal,
      });
      if (latest.cover?.url && latest.cover.url !== imageUrl)
        setImageUrl(latest.cover.url);
      else setFailed(true);
    } catch {
      if (!signal.aborted) setFailed(true);
    }
  }
  const Icon = kindIcons[work.kind];
  return (
    <div
      className={`artwork art-${work.kind.toLowerCase()} tone-${Array.from(work.id).reduce((sum, char) => sum + char.charCodeAt(0), 0) % 5}`}
    >
      {work.cover && !failed ? (
        <img
          src={imageUrl}
          draggable={false}
          alt=""
          loading={priority ? "eager" : "lazy"}
          decoding="async"
          onError={() => void reloadCover()}
        />
      ) : work.kind === "TEXT" ? (
        <div className="poem">
          <span className="quote-mark">“</span>
          <p>{work.excerpt}</p>
          <span className="poem-rule" />
        </div>
      ) : work.kind === "AUDIO" ? (
        <div className="sound-art">
          <AudioLines size={52} strokeWidth={0.8} />
          <div className="waveform">
            {Array.from({ length: 31 }, (_, i) => (
              <i
                key={i}
                style={{ height: 10 + Math.abs(Math.sin(i * 2.7)) * 46 }}
              />
            ))}
          </div>
          <span>FIELD NOTES / 声音切片</span>
        </div>
      ) : work.kind === "MUSIC_LINK" ? (
        <div className="record-art">
          <div className="record" />
          <span>在旋律里，抵达别处。</span>
        </div>
      ) : (
        <div className="fallback-art">
          <Icon size={40} strokeWidth={0.8} />
          <span>{failed ? "影像暂未抵达" : kindNames[work.kind]}</span>
        </div>
      )}
      <span className="kind-badge">
        <Icon size={12} />
        {kindNames[work.kind]}
      </span>
      <div className="card-caption">
        <span>
          {work.author.displayName} <ArrowUpRight size={13} />
        </span>
        <h3>{work.title}</h3>
      </div>
      {work.kind === "VIDEO" && <span className="play-disc">▶</span>}
    </div>
  );
}
export function WorkGrid({
  items,
  editable = false,
}: {
  items: WorkCard[];
  editable?: boolean;
}) {
  return (
    <div className="work-grid">
      {items.map((w) => (
        <Link
          className="grid-card"
          key={w.id}
          to={
            editable && w.status !== "PUBLISHED"
              ? `/publish/${encodeURIComponent(w.id)}`
              : `/works/${encodeURIComponent(w.id)}`
          }
        >
          <Artwork work={w} />
          <div className="grid-meta">
            <span>
              {w.status === "PUBLISHED"
                ? w.author.displayName
                : (
                    {
                      DRAFT: "草稿",
                      PROCESSING: "处理中",
                      FAILED: "处理失败",
                    } as Record<string, string>
                  )[w.status]}
            </span>
            <span>♡ {w.stats.likeCount}</span>
          </div>
        </Link>
      ))}
    </div>
  );
}
export function Success({ children }: { children: ReactNode }) {
  return (
    <p className="success">
      <Check size={16} />
      {children}
    </p>
  );
}
export function More({
  hasMore,
  load,
  busy,
}: {
  hasMore: boolean;
  load: () => void;
  busy: boolean;
}) {
  return hasMore ? (
    <div className="more">
      <Button onClick={load} busy={busy}>
        继续看看 <ArrowDown size={15} />
      </Button>
    </div>
  ) : null;
}
