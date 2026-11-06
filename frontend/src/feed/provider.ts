import { api, ApiError, getAuth } from "../api/client";
import type { FeedPage, InteractionState, WorkCard } from "../api/generated";
type Memory = {
  camera: { x: number; y: number };
  slots: Record<string, string>;
  copies: Record<string, string>;
  repeatIndex: number;
  discovered: string[];
  reservedSlots: string[];
  items: Record<string, WorkCard>;
  queue: string[];
  cursor: string | null;
  hasMore: boolean;
  loaded: boolean;
  focus: string | null;
  selectedSlot: string | null;
  expired: boolean;
  error: string | null;
  feedSession: FeedPage["feedSession"] | null;
};
const key = "htw.wall.v1";
function initial(): Memory {
  return {
    camera: { x: 50, y: 112 },
    slots: {},
    copies: {},
    repeatIndex: 0,
    discovered: [],
    reservedSlots: [],
    items: {},
    queue: [],
    cursor: null,
    hasMore: true,
    loaded: false,
    focus: null,
    selectedSlot: null,
    expired: false,
    error: null,
    feedSession: null,
  };
}
function restore(): Memory {
  try {
    const value = sessionStorage.getItem(key);
    return value ? { ...initial(), ...JSON.parse(value) } : initial();
  } catch {
    return initial();
  }
}
export const wallMemory = restore();
Object.values(wallMemory.items).forEach(
  (w) =>
    (w.viewer = {
      liked: false,
      bookmarked: false,
      isOwner: w.author.id === getAuth().session?.user.id,
    }),
);
export const saveWall = () =>
  sessionStorage.setItem(key, JSON.stringify(wallMemory));
const listeners = new Set<() => void>();
let revision = 0;
let flight: Promise<void> | undefined;
let controller: AbortController | undefined;
let generation = 0;
export const subscribeFeed = (fn: () => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};
export const feedSnapshot = () => revision;
function emit() {
  revision++;
  saveWall();
  listeners.forEach((f) => f());
}
function assign() {
  const reserved = new Set(wallMemory.reservedSlots);
  const works = Object.keys(wallMemory.items);
  let changed = false;
  for (const slot of wallMemory.discovered) {
    if (reserved.has(slot) || wallMemory.slots[slot] || wallMemory.copies[slot])
      continue;
    if (wallMemory.queue.length) {
      wallMemory.slots[slot] = wallMemory.queue.shift()!;
      changed = true;
    } else if (!wallMemory.hasMore && works.length) {
      // A visual instance has its own slot identity; the API work stays unique.
      wallMemory.copies[slot] = works[wallMemory.repeatIndex++ % works.length];
      changed = true;
    }
  }
  return changed;
}
function needsContent() {
  const reserved = new Set(wallMemory.reservedSlots);
  return wallMemory.discovered.some(
    (key) =>
      !reserved.has(key) && !wallMemory.slots[key] && !wallMemory.copies[key],
  );
}
export function discoverSlots(keys: string[], reservedKeys: string[] = []) {
  let changed = false;
  const reserved = new Set(wallMemory.reservedSlots);
  for (const key of reservedKeys) {
    // Keep already discovered works in place when introducing decoration.
    if (
      !reserved.has(key) &&
      !wallMemory.slots[key] &&
      !wallMemory.copies[key]
    ) {
      reserved.add(key);
      wallMemory.reservedSlots.push(key);
      changed = true;
    }
  }
  const known = new Set(wallMemory.discovered);
  keys.forEach((key) => {
    if (!known.has(key)) {
      wallMemory.discovered.push(key);
      known.add(key);
      changed = true;
    }
  });
  changed = assign() || changed;
  if (changed) emit();
  if (needsContent()) void loadFeed();
}
export async function loadFeed() {
  if (flight || wallMemory.expired || !wallMemory.hasMore) return flight;
  controller = new AbortController();
  const currentGeneration = generation;
  return (flight = (async () => {
    try {
      wallMemory.error = null;
      const page = await api("getFeed", undefined, {
        query: { limit: 24, cursor: wallMemory.cursor || undefined },
        signal: controller!.signal,
      });
      if (currentGeneration !== generation || controller?.signal.aborted)
        return;
      for (const item of page.items) {
        if (!wallMemory.items[item.id]) wallMemory.queue.push(item.id);
        wallMemory.items[item.id] = item;
      }
      wallMemory.cursor = page.nextCursor;
      wallMemory.hasMore = page.hasMore;
      wallMemory.loaded = true;
      wallMemory.feedSession = page.feedSession;
      assign();
      emit();
    } catch (e) {
      if (currentGeneration !== generation) return;
      if (e instanceof DOMException && e.name === "AbortError") return;
      if (e instanceof ApiError && e.code === "FEED_SESSION_EXPIRED")
        wallMemory.expired = true;
      else wallMemory.error = e instanceof Error ? e.message : "探索加载失败";
      emit();
    }
  })().finally(() => {
    flight = undefined;
    if (
      listeners.size &&
      !wallMemory.error &&
      !wallMemory.expired &&
      wallMemory.hasMore &&
      needsContent()
    )
      void loadFeed();
  }));
}
export const cancelFeed = () => controller?.abort();
export function refreshFeed() {
  generation++;
  controller?.abort();
  Object.assign(wallMemory, initial());
  emit();
}
if (typeof window !== "undefined") {
  window.addEventListener("htw:work", ((event: CustomEvent<WorkCard>) => {
    if (wallMemory.items[event.detail.id]) {
      wallMemory.items[event.detail.id] = event.detail;
      emit();
    }
  }) as EventListener);
  window.addEventListener("htw:interaction", ((
    event: CustomEvent<InteractionState>,
  ) => {
    const v = event.detail,
      w = wallMemory.items[v.workId];
    if (w) {
      w.viewer = { ...w.viewer, liked: v.liked, bookmarked: v.bookmarked };
      w.stats = {
        ...w.stats,
        likeCount: v.likeCount,
        bookmarkCount: v.bookmarkCount,
      };
      emit();
    }
  }) as EventListener);
  window.addEventListener("htw:identity", () => {
    Object.values(wallMemory.items).forEach(
      (w) => (w.viewer = { liked: false, bookmarked: false, isOwner: false }),
    );
    emit();
  });
  window.addEventListener("htw:deleted", ((event: CustomEvent<string>) => {
    delete wallMemory.items[event.detail];
    Object.entries(wallMemory.slots).forEach(([slot, id]) => {
      if (id === event.detail) delete wallMemory.slots[slot];
    });
    Object.entries(wallMemory.copies).forEach(([slot, id]) => {
      if (id === event.detail) delete wallMemory.copies[slot];
    });
    wallMemory.queue = wallMemory.queue.filter((id) => id !== event.detail);
    assign();
    emit();
    if (needsContent()) void loadFeed();
  }) as EventListener);
}
