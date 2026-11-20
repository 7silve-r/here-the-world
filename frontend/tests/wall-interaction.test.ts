import { describe, expect, it } from "vitest";
import { activateSlot, fogStyle } from "../src/wall/interaction";
import { useAtmosphere } from "../src/shared/atmosphere";

describe("click-only wall expansion", () => {
  it("ignores the removed local switch and keeps atmosphere enabled", () => {
    localStorage.setItem("htw.atmosphere", "off");
    expect(useAtmosphere()).toEqual([true]);
    localStorage.removeItem("htw.atmosphere");
  });
  it("keeps decorative slots selected without ever opening a detail route", () => {
    const first = activateSlot(null, "0,0:1", false);
    expect(first).toEqual({ selectedSlot: "0,0:1", open: false });
    expect(
      activateSlot(first.selectedSlot, first.selectedSlot, false).open,
    ).toBe(false);
    expect(activateSlot(first.selectedSlot, "0,0:2").open).toBe(false);
  });
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
  it("retains full fog regardless of the former local toolbar preference", () => {
    localStorage.setItem("htw.wall.fog.v2", "0");
    expect(fogStyle()).toMatchObject({
      "--wall-fog-blur": "26px",
      "--wall-fog-opacity": 1,
    });
    localStorage.removeItem("htw.wall.fog.v2");
  });
});
