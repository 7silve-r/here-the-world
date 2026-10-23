import { describe, expect, it } from "vitest";
import { glideFinished, glideOffset } from "../src/wall/inertia";

describe("short, bounded camera glide", () => {
  it("moves monotonically in either direction and settles after 260ms", () => {
    expect(glideOffset(1, 0)).toBe(0);
    const points = [16, 50, 100, 200, 260].map((t) => glideOffset(1, t));
    points.forEach((x, i) => {
      if (i) expect(x).toBeGreaterThan(points[i - 1]);
      expect(x).toBeLessThan(70);
    });
    expect(glideOffset(-1, 100)).toBe(-glideOffset(1, 100));
    expect(glideFinished(259)).toBe(false);
    expect(glideFinished(260)).toBe(true);
    expect(glideOffset(1, 2000)).toBe(glideOffset(1, 260));
  });
  it("caps release speed and handles stationary or stale timestamps", () => {
    expect(glideOffset(100, 260)).toBeLessThan(95);
    expect(glideOffset(-100, 260)).toBeGreaterThan(-95);
    expect(glideOffset(0, 200)).toBe(0);
    expect(glideOffset(1, -100)).toBe(0);
  });
});
