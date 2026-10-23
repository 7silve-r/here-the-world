import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
  type ButtonHTMLAttributes,
  type ReactNode,
} from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { AnimatePresence, m } from "motion/react";
import {
  ArrowDown,
  ArrowUpRight,
  AudioLines,
  Bookmark,
  Check,
  ChevronRight,
  Compass,
  Image,
  LoaderCircle,
  Plus,
  Settings,
  Type,
  User,
  Video,
} from "lucide-react";
import type { WorkCard } from "../api/generated";
import { api, ApiError, clearSession } from "../api/client";
import { useAuth, useReducedMotion } from "./hooks";
import { Reveal, softEase } from "./animation";
import { coverName, prepareWork, transitionRoute } from "./cover-transition";
import { pulseLight, useAtmosphere } from "./atmosphere";
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
  useLayoutEffect(() => setOpen(false), [location.pathname]);
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
  IMAGE: "图片",
  VIDEO: "视频",
  AUDIO: "音频",
  MUSIC_LINK: "音乐链接",
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
  const reduced = useReducedMotion();
  const [ripple, setRipple] = useState<{
    key: number;
    x: number;
    y: number;
  } | null>(null);
  const serial = useRef(0);
  return (
    <button
      {...props}
      disabled={props.disabled || busy}
      className={`button ${className}`}
      aria-busy={busy}
      onPointerDown={(e) => {
        props.onPointerDown?.(e);
        if (
          reduced ||
          e.defaultPrevented ||
          props.disabled ||
          busy ||
          e.button !== 0
        )
          return;
        const rect = e.currentTarget.getBoundingClientRect();
        pulseLight(e.currentTarget, 0.45);
        setRipple({
          key: ++serial.current,
          x: e.clientX - rect.left,
          y: e.clientY - rect.top,
        });
      }}
    >
      {ripple && (
        <span
          key={ripple.key}
          aria-hidden="true"
          className="press-ripple"
          style={{ left: ripple.x, top: ripple.y }}
          onAnimationEnd={() => setRipple(null)}
        />
      )}
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
    <m.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className={`notice ${error ? "error" : ""}`}
      role={error ? "alert" : "status"}
    >
      {!error && message && /成功|已保存|已更新|已发布/.test(message) && (
        <Check size={16} className="confirm-check" />
      )}
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
    </m.div>
  );
}
export function Loading() {
  return (
    <div className="empty" role="status">
      <LoaderCircle className="spin" />
      <p>加载中…</p>
    </div>
  );
}
export function Empty({ children = "暂无内容" }: { children?: ReactNode }) {
  return (
    <div className="empty">
      <Compass size={36} strokeWidth={1} />
      <p>{children}</p>
      <Link to="/">
        返回首页 <ArrowUpRight size={14} />
      </Link>
    </div>
  );
}
export function Page({
  children,
  eyebrow,
  title,
}: {
  children: ReactNode;
  eyebrow?: string;
  title: string;
}) {
  return (
    <main className="page">
      <Reveal className="page-heading">
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h1>{title}</h1>
      </Reveal>
      {children}
    </main>
  );
}
export function NavigationToggle() {
  const { open, setOpen } = useNavigation();
  return (
    <button
      className="icon-button menu-button floating-navigation-toggle"
      aria-expanded={open}
      aria-controls="main-navigation"
      aria-label={open ? "收起导航" : "展开导航"}
      onClick={() => setOpen(!open)}
    >
      <m.svg
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        aria-hidden="true"
      >
        <m.path animate={{ d: open ? "M6 6L18 18" : "M4 7L20 7" }} />
        <m.path d="M4 12L20 12" animate={{ opacity: open ? 0 : 1 }} />
        <m.path animate={{ d: open ? "M6 18L18 6" : "M4 17L20 17" }} />
      </m.svg>
    </button>
  );
}
export function Navigation() {
  const { open, setOpen } = useNavigation(),
    { session } = useAuth(),
    location = useLocation();
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>();
  const [fog, setFog] = useWallFog();
  const [atmosphere, setAtmosphere] = useAtmosphere();
  const reduced = useReducedMotion();
  useSyncExternalStore(subscribeFeed, feedSnapshot);
  const links = [
    ["/welcome", "欢迎页", Compass],
    ["/", "作品墙", Compass],
    ["/me/works", "我的作品", Image],
    [
      session ? `/users/${encodeURIComponent(session.user.id)}` : "/login",
      "个人主页",
      User,
    ],
    ["/publish", "发布作品", Plus],
    ["/me/bookmarks", "我的收藏", Bookmark],
    ["/settings", "设置", Settings],
  ] as const;
  return (
    <AnimatePresence>
      {open && (
        <m.nav
          key="navigation"
          initial={reduced ? false : { opacity: 0, y: -8, scale: 0.97 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: reduced ? 0 : -5, scale: reduced ? 1 : 0.98 }}
          transition={{ duration: reduced ? 0 : 0.2, ease: softEase }}
          style={{ transformOrigin: "top left" }}
          id="main-navigation"
          className="navigation floating-navigation"
          aria-label="主导航"
        >
          <div className="nav-links">
            {links.map(([to, label, Icon]) => (
              <Link
                key={label}
                to={to}
                className={location.pathname === to ? "active" : ""}
                onClick={(e) => {
                  setOpen(false);
                  if (
                    to !== "/" ||
                    e.button ||
                    e.metaKey ||
                    e.ctrlKey ||
                    e.shiftKey ||
                    e.altKey
                  )
                    return;
                  e.preventDefault();
                  transitionRoute(() => navigate("/"));
                }}
              >
                <Icon size={18} />
                {label}
                <ChevronRight size={14} />
              </Link>
            ))}
          </div>
          <div className="nav-foot">
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
                登录 / 注册 <ArrowUpRight size={14} />
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
              <label className="checkbox-label atmosphere-control">
                <input
                  type="checkbox"
                  aria-label="动态光效"
                  checked={atmosphere}
                  disabled={reduced}
                  onChange={(e) => setAtmosphere(e.target.checked)}
                />
                <span>
                  动态光效{reduced && <small>减少动态时自动暂停</small>}
                </span>
              </label>
              <div className="navigation-wall-actions">
                <Button onClick={() => requestWallAction("center")}>
                  回到起点
                </Button>
                <Button onClick={() => requestWallAction("explore")}>
                  重新探索
                </Button>
                <Button onClick={() => requestWallAction("clear")}>
                  收起卡片
                </Button>
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
        </m.nav>
      )}
    </AnimatePresence>
  );
}
export function Artwork({
  work,
  priority = false,
  sharedCover = false,
}: {
  work: WorkCard;
  priority?: boolean;
  sharedCover?: boolean;
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
          data-work-cover={work.id}
          style={
            sharedCover ? { viewTransitionName: coverName(work.id) } : undefined
          }
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
                style={
                  {
                    height: 10 + Math.abs(Math.sin(i * 2.7)) * 46,
                    "--bar": i,
                  } as CSSProperties
                }
              />
            ))}
          </div>
        </div>
      ) : work.kind === "MUSIC_LINK" ? (
        <div className="record-art">
          <div className="record" />
        </div>
      ) : (
        <div className="fallback-art">
          <Icon size={40} strokeWidth={0.8} />
          <span>{failed ? "封面加载失败" : kindNames[work.kind]}</span>
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
  const reduced = useReducedMotion();
  const navigate = useNavigate();
  const opening = useRef(false);
  return (
    <div className="work-grid">
      <AnimatePresence initial={true} mode="popLayout">
        {items.map((w, index) => (
          <m.div
            key={w.id}
            className="grid-cell"
            layout="position"
            initial={reduced ? false : { opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, scale: reduced ? 1 : 0.98 }}
            transition={{
              duration: reduced ? 0 : 0.3,
              delay: reduced ? 0 : Math.min(index, 7) * 0.025,
              ease: softEase,
              layout: { type: "spring", stiffness: 360, damping: 36 },
            }}
          >
            <Link
              className="grid-card"
              key={w.id}
              to={
                editable && w.status !== "PUBLISHED"
                  ? `/publish/${encodeURIComponent(w.id)}`
                  : `/works/${encodeURIComponent(w.id)}`
              }
              onPointerEnter={() => {
                if (w.status === "PUBLISHED") void prepareWork(w.id);
              }}
              onClick={async (e) => {
                if (
                  e.button ||
                  e.metaKey ||
                  e.ctrlKey ||
                  e.shiftKey ||
                  e.altKey ||
                  (editable && w.status !== "PUBLISHED")
                )
                  return;
                e.preventDefault();
                if (opening.current) return;
                opening.current = true;
                const anchor = e.currentTarget;
                const image = anchor.querySelector<HTMLImageElement>(
                  "img[data-work-cover]",
                );
                await prepareWork(w.id);
                if (!anchor.isConnected) {
                  opening.current = false;
                  return;
                }
                if (image?.isConnected)
                  image.style.viewTransitionName = coverName(w.id);
                transitionRoute(() =>
                  navigate(`/works/${encodeURIComponent(w.id)}`),
                );
                opening.current = false;
              }}
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
          </m.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
export function Success({ children }: { children: ReactNode }) {
  return (
    <p className="success">
      <Check size={16} className="confirm-check" />
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
