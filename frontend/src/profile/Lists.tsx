import { useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Link, useLocation, useParams } from "react-router-dom";
import { ArrowUpRight, Plus } from "lucide-react";
import { api } from "../api/client";
import { Empty, Loading, More, Notice, Page, WorkGrid } from "../shared/ui";
import type { WorkCard } from "../api/generated";
export default function Lists() {
  const { userId = "" } = useParams(),
    location = useLocation(),
    isUser = location.pathname.startsWith("/users/"),
    isBookmarks = location.pathname === "/me/bookmarks";
  const [status, setStatus] = useState("");
  const user = useQuery({
    queryKey: ["user", userId],
    queryFn: ({ signal }) =>
      api("getUser", undefined, { params: { userId }, signal }),
    enabled: isUser,
  });
  const list = useInfiniteQuery({
    queryKey: ["list", location.pathname, status],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api(
        isUser
          ? "listUserWorks"
          : isBookmarks
            ? "listMyBookmarks"
            : "listMyWorks",
        undefined,
        {
          params: { userId },
          query: {
            cursor: pageParam,
            limit: 24,
            status: !isUser && !isBookmarks && status ? status : undefined,
          },
          signal,
        },
      ),
    getNextPageParam: (p) => (p.hasMore ? p.nextCursor! : undefined),
  });
  const items = [
    ...new Map(
      list.data?.pages.flatMap((p) => p.items).map((w) => [w.id, w]),
    ).values(),
  ] as WorkCard[];
  return (
    <Page
      eyebrow={
        isUser
          ? "A SMALL WORLD"
          : isBookmarks
            ? "KEEP A LITTLE WONDER"
            : "YOUR CREATIVE SPACE"
      }
      title={
        isUser
          ? user.data?.displayName || "一个小世界"
          : isBookmarks
            ? "把喜欢，留在身边。"
            : "你的每一种看见。"
      }
    >
      {isUser && user.data && (
        <div className="profile-intro">
          <span className="avatar large">
            {user.data.avatarUrl ? (
              <img src={user.data.avatarUrl} alt="用户头像" />
            ) : (
              user.data.displayName[0]
            )}
          </span>
          <div>
            <p>
              @{user.data.username} · {user.data.publishedWorkCount} 件作品
            </p>
            <p>{user.data.bio}</p>
          </div>
        </div>
      )}
      {!isUser && !isBookmarks && (
        <div className="list-toolbar">
          <div className="tabs">
            {[
              ["", "全部"],
              ["DRAFT", "草稿"],
              ["PROCESSING", "处理中"],
              ["PUBLISHED", "已发布"],
              ["FAILED", "失败"],
            ].map(([v, label]) => (
              <button
                key={v}
                className={status === v ? "active" : ""}
                onClick={() => setStatus(v)}
              >
                {label}
              </button>
            ))}
          </div>
          <Link className="button primary" to="/publish">
            <Plus size={16} />
            新作品
          </Link>
        </div>
      )}
      {isBookmarks && <p className="section-intro">有些相遇，值得再看一眼。</p>}
      <Notice error={user.error || list.error} />
      {list.isPending ? (
        <Loading />
      ) : items.length ? (
        <WorkGrid items={items} editable={!isUser && !isBookmarks} />
      ) : (
        !list.error && (
          <Empty>
            还没有作品。
            <Link to="/publish">
              留下第一件 <ArrowUpRight size={14} />
            </Link>
          </Empty>
        )
      )}
      <More
        hasMore={list.hasNextPage}
        load={() => list.fetchNextPage()}
        busy={list.isFetchingNextPage}
      />
    </Page>
  );
}
