import { QueryClient } from "@tanstack/react-query";
import { onIdentityChange } from "./client";
import type { InteractionState, WorkCard, WorkDetail } from "./generated";
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 15000, retry: false, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
});
onIdentityChange(() => {
  queryClient.clear();
  window.dispatchEvent(new Event("htw:identity"));
});
export function syncInteraction(value: InteractionState) {
  const update = (w: WorkCard) => ({
    ...w,
    stats: {
      ...w.stats,
      likeCount: value.likeCount,
      bookmarkCount: value.bookmarkCount,
    },
    viewer: { ...w.viewer, liked: value.liked, bookmarked: value.bookmarked },
  });
  queryClient.setQueryData<WorkDetail>(["work", value.workId], (w) =>
    w ? (update(w) as WorkDetail) : w,
  );
  queryClient.invalidateQueries({
    predicate: (q) => ["list", "comments"].includes(q.queryKey[0] as string),
  });
  window.dispatchEvent(new CustomEvent("htw:interaction", { detail: value }));
}
export function syncWork(value: WorkDetail) {
  const {
    body: _,
    assets: _a,
    coverAsset: _c,
    musicLink: _m,
    updatedAt: _u,
    failureCode: _f,
    ...card
  } = value;
  queryClient.setQueriesData<{ pages: Array<{ items: WorkCard[] }> }>(
    { queryKey: ["list"] },
    (data) =>
      data
        ? {
            ...data,
            pages: data.pages.map((page) => ({
              ...page,
              items: page.items.map((w) => (w.id === card.id ? card : w)),
            })),
          }
        : data,
  );
  window.dispatchEvent(new CustomEvent("htw:work", { detail: card }));
}
