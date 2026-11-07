import type { CSSProperties } from "react";

// Hover/focus are visual only. Activation is the sole entry to geometry changes.
export function activateSlot(
  selectedSlot: string | null,
  slot: string,
  canOpen = true,
) {
  return { selectedSlot: slot, open: canOpen && selectedSlot === slot };
}

export type WallAction = "center" | "explore" | "clear" | "retry";
export function requestWallAction(action: WallAction) {
  window.dispatchEvent(new CustomEvent("htw:wall-action", { detail: action }));
}

export function fogStyle(): CSSProperties {
  return {
    "--wall-fog-blur": "26px",
    "--wall-fog-opacity": 1,
    "--wall-fog-brightness": 0.97,
    "--wall-fog-saturation": 0.97,
  } as CSSProperties;
}
