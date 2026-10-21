import type { CSSProperties } from "react";

// Hover/focus are visual only. Activation is the sole entry to geometry changes.
export function activateSlot(selectedSlot: string | null, slot: string) {
  return { selectedSlot: slot, open: selectedSlot === slot };
}

export function readFogLevel(value: string | null) {
  const parsed = value === null ? 45 : Number(value);
  return Number.isFinite(parsed)
    ? Math.round(Math.min(100, Math.max(0, parsed)))
    : 45;
}

export function fogStyle(level: number): CSSProperties {
  const amount = readFogLevel(String(level)) / 100;
  return {
    "--wall-fog-blur": `${amount * 2.4}px`,
    "--wall-fog-opacity": amount * 0.5,
    "--wall-fog-brightness": 1 - amount * 0.22,
    "--wall-fog-saturation": 1 - amount * 0.5,
  } as CSSProperties;
}
