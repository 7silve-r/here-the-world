import { describe, expect, it } from "vitest";
import { activateSlot, fogStyle, readFogLevel } from "../src/wall/interaction";

describe("click-only wall expansion", () => {
  it("selects on first activation and opens only on another activation of that slot", () => {
    const first = activateSlot(null, "0,0:1");
    expect(first).toEqual({ selectedSlot: "0,0:1", open: false });
    expect(activateSlot(first.selectedSlot, "0,0:1").open).toBe(true);
  });
  it("selecting a different block replaces the selection without opening it", () => {
    const next = activateSlot("0,0:1", "-1,2:4");
    expect(next.open).toBe(false);
    expect(next.selectedSlot).toBe("-1,2:4");
    expect(activateSlot(null, next.selectedSlot).open).toBe(false);
  });
  it("normalizes the local-only fog setting and supports turning it fully off", () => {
    expect(readFogLevel(null)).toBe(45);
    expect(readFogLevel("invalid")).toBe(45);
    expect(readFogLevel("-20")).toBe(0);
    expect(readFogLevel("120")).toBe(100);
    expect(fogStyle(0)).toMatchObject({
      "--wall-fog-blur": "0px",
      "--wall-fog-opacity": 0,
      "--wall-fog-brightness": 1,
      "--wall-fog-saturation": 1,
    });
    expect(fogStyle(100)).toMatchObject({
      "--wall-fog-blur": "2.4px",
      "--wall-fog-opacity": 0.5,
    });
  });
});
