import { beforeEach, describe, expect, it } from "vitest";
import { setTransport } from "../src/api/client";
import { mockFetch, setMockScenario } from "../src/mocks/server";
import {
  discoverSlots,
  loadFeed,
  refreshFeed,
  saveWall,
  wallMemory,
} from "../src/feed/provider";
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  setTransport(mockFetch);
  refreshFeed();
});
describe("feed provider keeps spatial identity independent of clipping", () => {
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
  it("preserves the wall on 410, and leaves unassigned material after the actual end", async () => {
    discoverSlots(["0,0:0"]);
    await loadFeed();
    const original = { ...wallMemory.slots };
    setMockScenario("feed-expired");
    await loadFeed();
    expect(wallMemory.expired).toBe(true);
    expect(wallMemory.slots).toEqual(original);
    refreshFeed();
    discoverSlots(Array.from({ length: 70 }, (_, i) => `slot:${i}`));
    await loadFeed();
    await loadFeed();
    expect(wallMemory.hasMore).toBe(false);
    expect(Object.values(wallMemory.slots)).toHaveLength(32);
    expect(new Set(Object.values(wallMemory.slots)).size).toBe(32);
    expect(wallMemory.slots["slot:69"]).toBeUndefined();
  });
});
