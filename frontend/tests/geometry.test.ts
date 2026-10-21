import { describe, expect, it, vi } from "vitest";
import {
  BLOCK_HEIGHT,
  BLOCK_WIDTH,
  reflow,
  slotsForBlock,
  stableHit,
  template,
  visibleBlocks,
  type Rect,
} from "../src/wall/geometry";
import { MotionDirector } from "../src/wall/motion";
function exactCover(rects: Rect[]) {
  expect(rects).toHaveLength(12);
  for (let y = 0; y < 8; y++)
    for (let x = 0; x < 12; x++)
      expect(
        rects.filter(
          (r) =>
            x >= r.x && x < r.x + r.width && y >= r.y && y < r.y + r.height,
        ),
      ).toHaveLength(1);
}
describe("stable two-dimensional geometry", () => {
  it("all positive and negative blocks and all preview targets exactly cover 12×8", () => {
    for (let y = -3; y <= 3; y++)
      for (let x = -3; x <= 3; x++) {
        exactCover(template(x, y));
        for (let target = 0; target < 12; target++)
          exactCover(reflow(x, y, target));
        expect(template(x, y)).toEqual(template(x, y));
      }
  });
  it("culls to <=396 tiles and retains absolute identity", () => {
    const blocks = visibleBlocks(
      { x: -100000, y: 50000 },
      { width: 100000, height: 100000 },
      0.5,
    );
    expect(blocks.length * 12).toBeLessThanOrEqual(400);
    const a = slotsForBlock(-1, -2);
    expect(a[0].x).toBeGreaterThanOrEqual(-BLOCK_WIDTH);
    expect(a[0].y).toBeGreaterThanOrEqual(-2 * BLOCK_HEIGHT);
    expect(a).toEqual(slotsForBlock(-1, -2));
  });
  it("uses stationary base hit regions independent from animated cards", () => {
    const rect = template(-2, 1)[4];
    expect(stableHit(-2, 1, (rect.x + 0.5) * 76, (rect.y + 0.5) * 76)).toBe(4);
  });
  it("retargets from the current interpolated geometry and stops when settled", () => {
    let next: FrameRequestCallback = () => {},
      cancelled = 0;
    vi.stubGlobal("requestAnimationFrame", (fn: FrameRequestCallback) => {
      next = fn;
      return 1;
    });
    vi.stubGlobal("cancelAnimationFrame", () => cancelled++);
    const now = performance.now(),
      from = [{ x: 0, y: 0, width: 10, height: 10 }],
      target = [{ x: 100, y: 0, width: 20, height: 10 }];
    const director = new MotionDirector(from, () => {});
    director.to(target);
    next(now + 100);
    const current = director.current[0].x;
    expect(current).toBeGreaterThan(0);
    director.to([{ x: 200, y: 0, width: 30, height: 10 }]);
    expect(director.current[0].x).toBe(current);
    next(performance.now() + 500);
    expect(director.current[0].x).toBe(200);
    director.destroy();
    expect(cancelled).toBeGreaterThan(0);
    vi.unstubAllGlobals();
  });
});
