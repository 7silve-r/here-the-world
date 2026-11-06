import { beforeEach, describe, expect, it, vi } from "vitest";
import { setTransport } from "../src/api/client";
import { mockFetch, setMockScenario } from "../src/mocks/server";
import { decorationForSlot } from "../src/wall/decoration";
import { slotsForBlock } from "../src/wall/geometry";
import {
  discoverSlots,
  loadFeed,
  refreshFeed,
  saveWall,
  subscribeFeed,
  wallMemory,
} from "../src/feed/provider";
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  setTransport(mockFetch);
  refreshFeed();
});
describe("feed provider keeps spatial identity independent of clipping", () => {
  it("skips decorative reservations without consuming works or changing existing mappings", async () => {
    const keys = [-1, 0, 1, 2].flatMap((col) =>
      slotsForBlock(col, 0).map((s) => s.key),
    );
    const reserved = keys.filter((k) => decorationForSlot(k));
    discoverSlots(keys, reserved);
    await loadFeed();
    await loadFeed();
    expect(wallMemory.reservedSlots).toEqual(reserved);
    for (const key of reserved) expect(wallMemory.slots[key]).toBeUndefined();
    for (const key of reserved) expect(wallMemory.copies[key]).toBeUndefined();
    expect(new Set(Object.values(wallMemory.slots)).size).toBe(32);
    expect(Object.keys(wallMemory.copies)).toHaveLength(
      keys.length - 32 - reserved.length,
    );
    const original = { ...wallMemory.slots };
    const copies = { ...wallMemory.copies };
    discoverSlots([...keys].reverse(), reserved);
    expect(wallMemory.slots).toEqual(original);
    expect(wallMemory.copies).toEqual(copies);
    saveWall();
    expect(
      JSON.parse(sessionStorage.getItem("htw.wall.v1")!).reservedSlots,
    ).toEqual(reserved);
  });
  it("preserves works already mapped to a newly proposed decoration slot", async () => {
    discoverSlots(["0,0:1"]);
    await loadFeed();
    const original = wallMemory.slots["0,0:1"];
    discoverSlots(["0,0:1"], ["0,0:1"]);
    expect(wallMemory.slots["0,0:1"]).toBe(original);
    expect(wallMemory.reservedSlots).not.toContain("0,0:1");
  });
  it("does not fetch works when only decorative slots need filling", () => {
    discoverSlots(["0,0:1"], ["0,0:1"]);
    expect(wallMemory.loaded).toBe(false);
    expect(wallMemory.slots["0,0:1"]).toBeUndefined();
  });
  it("does not reassign existing slots when visibility order changes, and persists camera/pages", async () => {
    discoverSlots(Array.from({ length: 20 }, (_, i) => `0,0:${i}`));
    await loadFeed();
    const original = { ...wallMemory.slots };
    wallMemory.camera = { x: -1234, y: 678 };
    wallMemory.focus = "0,0:3";
    discoverSlots([...Object.keys(original).reverse(), "1,0:0"]);
    await loadFeed();
    for (const [key, id] of Object.entries(original))
      expect(wallMemory.slots[key]).toBe(id);
    saveWall();
    const stored = JSON.parse(sessionStorage.getItem("htw.wall.v1")!);
    expect(stored.camera).toEqual({ x: -1234, y: 678 });
    expect(stored.focus).toBe("0,0:3");
    expect(stored.slots).toEqual(wallMemory.slots);
  });
  it("preserves the wall on 410 without treating expiry as the end of the feed", async () => {
    discoverSlots(["0,0:0"]);
    await loadFeed();
    const original = { ...wallMemory.slots };
    setMockScenario("feed-expired");
    await loadFeed();
    expect(wallMemory.expired).toBe(true);
    expect(wallMemory.slots).toEqual(original);
    expect(wallMemory.copies).toEqual({});
  });
  it("cycles visual instances after the real end while retaining unique API works", async () => {
    discoverSlots(Array.from({ length: 70 }, (_, i) => `slot:${i}`));
    await loadFeed();
    await loadFeed();
    expect(wallMemory.hasMore).toBe(false);
    expect(wallMemory.cursor).toBeNull();
    const ids = Object.keys(wallMemory.items);
    expect(ids).toHaveLength(32);
    expect(Object.values(wallMemory.slots)).toHaveLength(32);
    expect(new Set(Object.values(wallMemory.slots)).size).toBe(32);
    const copies = Object.values(wallMemory.copies);
    expect(copies).toHaveLength(38);
    expect(copies.slice(0, 32)).toEqual(ids);
    expect(copies.slice(32)).toEqual(ids.slice(0, 6));
    for (let i = 0; i < 70; i++)
      expect(
        wallMemory.slots[`slot:${i}`] || wallMemory.copies[`slot:${i}`],
      ).toBeTruthy();
    saveWall();
    const stored = JSON.parse(sessionStorage.getItem("htw.wall.v1")!);
    expect(stored.copies).toEqual(wallMemory.copies);
    expect(stored.repeatIndex).toBe(38);
  });
  it("fills distant blocks in all directions without restarting pagination or changing old instances", async () => {
    const transport = vi.fn(mockFetch);
    setTransport(transport);
    discoverSlots(Array.from({ length: 60 }, (_, i) => `initial:${i}`));
    await loadFeed();
    await loadFeed();
    const primary = { ...wallMemory.slots };
    const original = { ...wallMemory.copies };
    transport.mockClear();
    const keys = [
      [-99, 3],
      [99, -3],
      [3, -99],
      [-3, 99],
    ].flatMap(([col, row]) => slotsForBlock(col, row).map((slot) => slot.key));
    const reserved = keys.filter((key) => decorationForSlot(key));
    discoverSlots(keys, reserved);
    for (const key of keys) {
      if (reserved.includes(key)) {
        expect(wallMemory.copies[key]).toBeUndefined();
        expect(decorationForSlot(key)).toBeTruthy();
      } else {
        expect(wallMemory.items[wallMemory.copies[key]]).toBeDefined();
      }
    }
    const copies = { ...wallMemory.copies };
    discoverSlots([...keys].reverse(), reserved);
    await loadFeed();
    expect(transport).not.toHaveBeenCalled();
    expect(wallMemory.copies).toEqual(copies);
    expect(wallMemory.slots).toEqual(primary);
    for (const [key, id] of Object.entries(original))
      expect(wallMemory.copies[key]).toBe(id);
  });
  it("fills and notifies cached unassigned slots even when no new slots are discovered", async () => {
    const keys = Array.from({ length: 60 }, (_, i) => `cached:${i}`);
    discoverSlots(keys);
    await loadFeed();
    await loadFeed();
    // Older saved walls may have an exhausted feed and no visual instances yet.
    wallMemory.copies = {};
    wallMemory.repeatIndex = 0;
    const listener = vi.fn();
    const unsubscribe = subscribeFeed(listener);
    discoverSlots(keys);
    unsubscribe();
    expect(Object.keys(wallMemory.copies)).toHaveLength(28);
    expect(listener).toHaveBeenCalledOnce();
  });
  it("does not invent works or restart an empty exhausted feed", async () => {
    const transport = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            data: {
              items: [],
              nextCursor: null,
              hasMore: false,
              feedSession: {
                id: "feed_empty",
                expiresAt: "2026-11-07T12:00:00Z",
              },
            },
            requestId: "req_empty",
          }),
          { headers: { "Content-Type": "application/json" } },
        ),
    );
    setTransport(transport);
    discoverSlots(["0,0:0"]);
    await loadFeed();
    discoverSlots(["1,0:0"]);
    await loadFeed();
    expect(transport).toHaveBeenCalledOnce();
    expect(wallMemory.items).toEqual({});
    expect(wallMemory.slots).toEqual({});
    expect(wallMemory.copies).toEqual({});
  });
});
