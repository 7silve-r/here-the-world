import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import {
  ArrowUpRight,
  Bookmark,
  Heart,
  MessageCircle,
  Trash2,
} from "lucide-react";
import { api, ApiError } from "../api/client";
import { queryClient, syncInteraction, syncWork } from "../api/cache";
import {
  Artwork,
  Button,
  Empty,
  Loading,
  More,
  Notice,
  Page,
  kindNames,
} from "../shared/ui";
import { useAuth, useLoginGate } from "../shared/hooks";
import { useIntentKey } from "../shared/forms";
import type { WorkDetail } from "../api/generated";
import { AnimatedNumber, Reveal } from "../shared/animation";
import { coverName } from "../shared/cover-transition";
import { localPreferences } from "../settings/Settings";
function Media({ work }: { work: WorkDetail }) {
  const [failed, setFailed] = useState(false);
  const { session } = useAuth();
  const { data: settings } = useQuery({
    queryKey: ["settings", session?.user.id],
    queryFn: ({ signal }) => api("getSettings", undefined, { signal }),
    enabled: !!session,
  });
  const { data: capabilities } = useQuery({
    queryKey: ["capabilities"],
    queryFn: ({ signal }) => api("getCapabilities", undefined, { signal }),
  });
  const autoPlayMedia = session
    ? settings?.autoPlayMedia
    : localPreferences().autoPlayMedia;
  const play = (e: React.SyntheticEvent<HTMLMediaElement>) => {
    document.querySelectorAll<HTMLMediaElement>("audio,video").forEach((el) => {
      if (el !== e.currentTarget) el.pause();
    });
  };
  if (work.kind === "TEXT")
    return (
      <article className="detail-text">
        <p>{work.body}</p>
        <span className="text-signature">— {work.author.displayName}</span>
      </article>
    );
  if (work.kind === "IMAGE")
    return (
      <div className="image-group">
        {work.assets.map((a, index) => (
          <figure key={a.id}>
            {a.url ? (
              <img
                src={a.url}
                alt={work.title}
                className={index === 0 ? "detail-hero" : undefined}
                data-work-cover={work.id}
                style={
                  index === 0
                    ? { viewTransitionName: coverName(work.id) }
                    : undefined
                }
                onError={(e) => {
                  e.currentTarget.hidden = true;
                  e.currentTarget.parentElement!.classList.add(
                    "media-unavailable",
                  );
                }}
              />
            ) : (
              <p>图片正在准备</p>
            )}
          </figure>
        ))}
      </div>
    );
  if (work.kind === "MUSIC_LINK" && work.musicLink) {
    const m = work.musicLink;
    const canEmbed =
      m.playback.mode === "EMBED" &&
      capabilities?.musicEmbedProviders.includes(m.provider) &&
      m.playback.embedUrl?.startsWith("https://");
    return (
      <div className="music-detail">
        <Artwork work={work} sharedCover />
        <p>
          {m.title || work.title} {m.artist && ` / ${m.artist}`}
        </p>
        {!canEmbed ? (
          <a
            className="button primary"
            href={m.sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
          >
            在原平台打开 <ArrowUpRight size={16} />
          </a>
        ) : (
          m.playback.embedUrl && (
            <iframe
              src={m.playback.embedUrl}
              title={m.title || "官方音乐播放器"}
              sandbox="allow-scripts allow-same-origin allow-popups"
              allow="encrypted-media"
            />
          )
        )}
      </div>
    );
  }
  const asset = work.assets[0];
  return (
    <div className="media-detail">
      {work.kind === "AUDIO" && <Artwork work={work} sharedCover />}
      <Notice
        message={
          failed ? "资源暂时无法播放。可以刷新作品重新获取地址。" : undefined
        }
      />
      {asset?.url ? (
        work.kind === "VIDEO" ? (
          <video
            key={asset.url}
            controls
            preload="metadata"
            playsInline
            poster={work.cover?.url}
            onPlay={play}
            onError={() => setFailed(true)}
            autoPlay={autoPlayMedia && document.visibilityState === "visible"}
          >
            <source src={asset.url} type={asset.mimeType} />
            <track kind="captions" />
          </video>
        ) : (
          <audio
            key={asset.url}
            controls
            preload="metadata"
            onPlay={play}
            onError={() => setFailed(true)}
            autoPlay={autoPlayMedia && document.visibilityState === "visible"}
            src={asset.url}
          />
        )
      ) : (
        <p>媒体暂不可用</p>
      )}
    </div>
  );
}
export default function Detail() {
  const { workId = "" } = useParams(),
    navigate = useNavigate(),
    gate = useLoginGate(),
    { session } = useAuth();
  const query = useQuery({
    queryKey: ["work", workId],
    queryFn: ({ signal }) =>
      api("getWork", undefined, { params: { workId }, signal }),
    refetchInterval: (q) =>
      q.state.data?.status === "PROCESSING" &&
      document.visibilityState === "visible"
        ? 2500
        : false,
  });
  const capabilities = useQuery({
    queryKey: ["capabilities"],
    queryFn: ({ signal }) => api("getCapabilities", undefined, { signal }),
  });
  const commentLimit = capabilities.data?.maxCommentLength || 2000;
  const comments = useInfiniteQuery({
    queryKey: ["comments", workId],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api("listComments", undefined, {
        params: { workId },
        query: { cursor: pageParam, limit: 24 },
        signal,
      }),
    getNextPageParam: (page) => (page.hasMore ? page.nextCursor! : undefined),
    enabled: query.data?.status === "PUBLISHED",
  });
  const [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false),
    [comment, setComment] = useState(
      () => sessionStorage.getItem(`htw.comment.${workId}`) || "",
    ),
    [confirm, setConfirm] = useState(false),
    intent = useIntentKey(),
    actionLock = useRef(false),
    resumed = useRef(false);
  const work = query.data;
  useEffect(() => {
    if (work?.status === "PUBLISHED") syncWork(work);
  }, [work]);
  async function interaction(action: "like" | "bookmark") {
    if (
      !work ||
      !gate(JSON.stringify({ type: action, workId })) ||
      actionLock.current
    )
      return;
    actionLock.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const active =
        action === "like" ? work.viewer.liked : work.viewer.bookmarked;
      const operation =
        action === "like"
          ? active
            ? "unlikeWork"
            : "likeWork"
          : active
            ? "unbookmarkWork"
            : "bookmarkWork";
      const value = await api(operation, undefined, { params: { workId } });
      syncInteraction(value);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
      actionLock.current = false;
    }
  }
  useEffect(() => {
    if (!work || !session || resumed.current) return;
    const stored = sessionStorage.getItem("htw.intent");
    if (!stored) return;
    try {
      const pending = JSON.parse(stored);
      if (
        pending.workId === workId &&
        ["like", "bookmark"].includes(pending.type)
      ) {
        resumed.current = true;
        sessionStorage.removeItem("htw.intent");
        void interaction(pending.type);
      }
    } catch {}
  }, [work, session]);
  if (query.isPending) return <Loading />;
  if (query.error)
    return (
      <Page title="作品无法加载">
        <Notice error={query.error} />
        <Empty>作品可能已被移除，或者还未公开。</Empty>
      </Page>
    );
  if (!work) return null;
  return (
    <Page
      eyebrow={`${kindNames[work.kind]} / ${new Date(work.createdAt).toLocaleDateString("zh-CN")}`}
      title={work.title}
    >
      <div className="detail-layout">
        <div className="detail-main">
          <Media work={work} />
          {work.kind !== "TEXT" && work.body && (
            <Reveal>
              <p className="work-body">{work.body}</p>
            </Reveal>
          )}
          <div className="detail-actions">
            <Button
              className={work.viewer.liked ? "selected" : ""}
              busy={busy}
              onClick={() => interaction("like")}
              aria-pressed={work.viewer.liked}
            >
              <Heart
                size={17}
                fill={work.viewer.liked ? "currentColor" : "none"}
              />
              <AnimatedNumber value={work.stats.likeCount} /> 喜欢
            </Button>
            <Button
              className={work.viewer.bookmarked ? "selected" : ""}
              busy={busy}
              onClick={() => interaction("bookmark")}
              aria-pressed={work.viewer.bookmarked}
            >
              <Bookmark
                size={17}
                fill={work.viewer.bookmarked ? "currentColor" : "none"}
              />
              <AnimatedNumber value={work.stats.bookmarkCount} /> 收藏
            </Button>
            <span>
              <MessageCircle size={16} />
              {work.stats.commentCount}
            </span>
          </div>
          <Notice error={error} />
          <section className="comments">
            <h2>
              评论 <span>{work.stats.commentCount}</span>
            </h2>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                sessionStorage.setItem(`htw.comment.${workId}`, comment);
                if (!gate()) return;
                setBusy(true);
                setError(undefined);
                const body = { body: comment };
                try {
                  if (Array.from(comment).length > commentLimit)
                    throw new Error(`评论最多 ${commentLimit} 个字符`);
                  await api("createComment", body, {
                    params: { workId },
                    key: intent.key(body),
                  });
                  intent.reset();
                  setComment("");
                  sessionStorage.removeItem(`htw.comment.${workId}`);
                  await Promise.all([comments.refetch(), query.refetch()]);
                } catch (e) {
                  setError(e);
                } finally {
                  setBusy(false);
                }
              }}
            >
              <label className="field">
                <span>评论内容</span>
                <textarea
                  aria-label="评论内容"
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder="输入评论"
                  required
                  rows={3}
                />
                <small>
                  {Array.from(comment).length} / {commentLimit}
                </small>
              </label>
              <Button busy={busy} type="submit">
                {session ? "发布评论" : "登录后评论"} <ArrowUpRight size={14} />
              </Button>
            </form>
            <Notice error={comments.error} />
            {comments.data?.pages
              .flatMap((p) => p.items)
              .map((c) => (
                <article className="comment" key={c.id}>
                  <Link
                    to={`/users/${encodeURIComponent(c.author.id)}`}
                    className="comment-author"
                  >
                    {c.author.displayName}
                  </Link>
                  <time>{new Date(c.createdAt).toLocaleString("zh-CN")}</time>
                  <p>{c.body}</p>
                  {c.canDelete && (
                    <button
                      className="text-button"
                      disabled={busy}
                      onClick={async () => {
                        setBusy(true);
                        try {
                          await api("deleteComment", undefined, {
                            params: { commentId: c.id },
                          });
                          await comments.refetch();
                          await query.refetch();
                        } catch (e) {
                          setError(e);
                        } finally {
                          setBusy(false);
                        }
                      }}
                    >
                      删除
                    </button>
                  )}
                </article>
              ))}
            {comments.data?.pages[0].items.length === 0 && (
              <p className="muted">暂无评论</p>
            )}
            <More
              hasMore={comments.hasNextPage}
              load={() => comments.fetchNextPage()}
              busy={comments.isFetchingNextPage}
            />
          </section>
        </div>
        <aside className="detail-aside">
          <Link
            to={`/users/${encodeURIComponent(work.author.id)}`}
            className="author-block"
          >
            <span className="avatar large">
              {work.author.avatarUrl ? (
                <img src={work.author.avatarUrl} alt="作者头像" />
              ) : (
                work.author.displayName[0]
              )}
            </span>
            <h3>
              {work.author.displayName} <ArrowUpRight size={16} />
            </h3>
            <small>@{work.author.username}</small>
            <p>{work.author.bio}</p>
          </Link>
          {work.viewer.isOwner && (
            <div className="owner-tools">
              {work.status !== "PUBLISHED" && (
                <Link
                  className="button"
                  to={`/publish/${encodeURIComponent(workId)}`}
                >
                  管理草稿 · {work.status}
                </Link>
              )}
              <Button onClick={() => setConfirm(true)}>
                <Trash2 size={15} />
                删除作品
              </Button>
              {confirm && (
                <div className="delete-confirm">
                  <p>作品删除后将退出所有公开列表。确认删除？</p>
                  <Button
                    busy={busy}
                    onClick={async () => {
                      setBusy(true);
                      try {
                        await api("deleteWork", undefined, {
                          params: { workId },
                          version: work.version,
                        });
                        queryClient.invalidateQueries();
                        window.dispatchEvent(
                          new CustomEvent("htw:deleted", { detail: workId }),
                        );
                        navigate("/me/works");
                      } catch (e) {
                        if (e instanceof ApiError && e.status === 412)
                          await query.refetch();
                        setError(e);
                      } finally {
                        setBusy(false);
                        setConfirm(false);
                      }
                    }}
                  >
                    确认删除
                  </Button>
                  <Button onClick={() => setConfirm(false)}>取消</Button>
                </div>
              )}
            </div>
          )}
        </aside>
      </div>
    </Page>
  );
}
