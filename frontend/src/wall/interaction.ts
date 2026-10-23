import { useSyncExternalStore, type CSSProperties } from "react";

// Hover/focus are visual only. Activation is the sole entry to geometry changes.
export function activateSlot(selectedSlot: string | null, slot: string) {
  return { selectedSlot: slot, open: selectedSlot === slot };
}

export function readFogLevel(value: string | null) {
  const parsed = value === null ? 100 : Number(value);
  return Number.isFinite(parsed)
    ? Math.round(Math.min(100, Math.max(0, parsed)))
    : 100;
}

// A new presentation version starts at full fog, independent of the old wall toolbar.
const fogKey = "htw.wall.fog.v2";
const fogEvent = "htw:wall-fog";
function subscribeFog(listener: () => void) {
  window.addEventListener(fogEvent, listener);
  window.addEventListener("storage", listener);
  return () => {
    window.removeEventListener(fogEvent, listener);
    window.removeEventListener("storage", listener);
  };
}
export function useWallFog() {
  const level = useSyncExternalStore(subscribeFog, () =>
    readFogLevel(localStorage.getItem(fogKey)),
  );
  return [
    level,
    (value: number) => {
      localStorage.setItem(fogKey, String(readFogLevel(String(value))));
      window.dispatchEvent(new Event(fogEvent));
    },
  ] as const;
}
export type WallAction = "center" | "explore" | "clear" | "retry";
export function requestWallAction(action: WallAction) {
  window.dispatchEvent(new CustomEvent("htw:wall-action", { detail: action }));
}

export function fogStyle(level: number): CSSProperties {
  const amount = readFogLevel(String(level)) / 100;
  return {
    "--wall-fog-blur": `${amount * 10}px`,
    "--wall-fog-opacity": amount,
    "--wall-fog-brightness": 1 - amount * 0.24,
    "--wall-fog-saturation": 1 + amount * 0.18,
  } as CSSProperties;
}
