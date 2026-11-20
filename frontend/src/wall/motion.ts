import type { Rect } from "./geometry";
const mix = (a: number, b: number, t: number) => a + (b - a) * t;
export const interpolate = (a: Rect, b: Rect, t: number): Rect => ({
  x: mix(a.x, b.x, t),
  y: mix(a.y, b.y, t),
  width: mix(a.width, b.width, t),
  height: mix(a.height, b.height, t),
});
// One director per block; interrupted transitions retain the current rectangles.
export class MotionDirector {
  current: Rect[];
  private frame = 0;
  private generation = 0;
  constructor(
    rects: Rect[],
    private commit: (rects: Rect[]) => void,
  ) {
    this.current = rects;
  }
  to(target: Rect[], reduced = false) {
    cancelAnimationFrame(this.frame);
    const id = ++this.generation,
      from = this.current.map((r) => ({ ...r })),
      start = performance.now();
    if (reduced) {
      this.current = target;
      this.commit(target);
      return;
    }
    const tick = (now: number) => {
      if (id !== this.generation) return;
      const p = Math.min(1, (now - start) / 420),
        t = 1 - (1 - p) ** 3.4;
      this.current = target.map((r, i) => interpolate(from[i], r, t));
      this.commit(this.current);
      if (p < 1) this.frame = requestAnimationFrame(tick);
    };
    this.frame = requestAnimationFrame(tick);
  }
  destroy() {
    this.generation++;
    cancelAnimationFrame(this.frame);
  }
}
