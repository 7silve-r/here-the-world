import { useEffect, useRef, useSyncExternalStore, type RefObject } from "react";
import { useReducedMotion } from "./hooks";

const key = "htw.atmosphere";
const eventName = "htw:atmosphere";
function subscribe(listener: () => void) {
  window.addEventListener(eventName, listener);
  window.addEventListener("storage", listener);
  return () => {
    window.removeEventListener(eventName, listener);
    window.removeEventListener("storage", listener);
  };
}
export function useAtmosphere() {
  const enabled = useSyncExternalStore(
    subscribe,
    () => localStorage.getItem(key) !== "off",
  );
  return [
    enabled,
    (value: boolean) => {
      localStorage.setItem(key, value ? "on" : "off");
      window.dispatchEvent(new Event(eventName));
    },
  ] as const;
}
export type Drift = { x: number; y: number; at: number };
type Burst = { x: number; y: number; at: number; power: number };
const burstEvent = "htw:light-burst";
export function pulseLight(element: HTMLElement, power = 1) {
  const rect = element.getBoundingClientRect();
  window.dispatchEvent(
    new CustomEvent(burstEvent, {
      detail: {
        x: rect.x + rect.width / 2,
        y: rect.y + rect.height / 2,
        power,
      },
    }),
  );
}

export function AmbientDust({
  drift,
  backdrop = false,
}: {
  drift?: RefObject<Drift>;
  backdrop?: boolean;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const idleDrift = useRef<Drift>({ x: 0, y: 0, at: 0 });
  const movement = drift || idleDrift;
  const reduced = useReducedMotion();
  const [enabled] = useAtmosphere();
  useEffect(() => {
    if (!enabled || reduced || !canvas.current) return;
    const node = canvas.current;
    const context = node.getContext("2d");
    if (!context) return;
    let width = 0,
      height = 0,
      frame = 0,
      previous = 0;
    let seed = 73;
    const pointer = {
      x: innerWidth / 2,
      y: innerHeight / 2,
      tx: innerWidth / 2,
      ty: innerHeight / 2,
      active: false,
    };
    const trail: { x: number; y: number; at: number }[] = [];
    const bursts: Burst[] = [];
    const colors = ["99,92,255", "14,223,255", "255,57,156", "255,168,61"];
    const random = () =>
      ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
    const particles = Array.from(
      { length: innerWidth < 640 ? 28 : 54 },
      () => ({
        x: random(),
        y: random(),
        radius: 0.5 + random() * 1.5,
        depth: 0.3 + random() * 0.7,
        phase: random() * Math.PI * 2,
        color: colors[Math.floor(random() * colors.length)],
      }),
    );
    const resize = () => {
      const rect = node.getBoundingClientRect();
      width = rect.width;
      height = rect.height;
      const ratio = Math.min(devicePixelRatio || 1, 1.5);
      node.width = Math.round(width * ratio);
      node.height = Math.round(height * ratio);
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
    };
    const observer = new ResizeObserver(resize);
    observer.observe(node);
    resize();
    const track = (event: PointerEvent) => {
      if (event.pointerType === "touch" && !event.buttons) return;
      const rect = node.getBoundingClientRect();
      pointer.tx = event.clientX - rect.left;
      pointer.ty = event.clientY - rect.top;
      pointer.active = true;
      trail.push({ x: pointer.tx, y: pointer.ty, at: performance.now() });
      if (trail.length > 22) trail.shift();
    };
    const leave = () => {
      pointer.active = false;
    };
    const burst = (event: Event) => {
      const detail = (event as CustomEvent<Omit<Burst, "at">>).detail;
      const rect = node.getBoundingClientRect();
      if (!document.hidden)
        bursts.push({
          ...detail,
          x: detail.x - rect.left,
          y: detail.y - rect.top,
          at: performance.now(),
        });
      if (bursts.length > 5) bursts.shift();
    };
    window.addEventListener("pointermove", track, { passive: true });
    document.documentElement.addEventListener("pointerleave", leave);
    window.addEventListener(burstEvent, burst);
    const draw = (now: number) => {
      frame = 0;
      if (document.hidden) return;
      if (now - previous >= 32) {
        const dt = previous ? Math.min(48, now - previous) / 1000 : 0;
        previous = now;
        context.clearRect(0, 0, width, height);
        context.globalCompositeOperation = "screen";
        // Three flowing ribbons share one canvas, with no full-screen blur passes.
        for (let band = 0; band < 3; band++) {
          const phase = now * 0.00022 + band * 2.1;
          const cy =
            height * (0.2 + band * 0.3) + Math.sin(phase) * height * 0.12;
          const gradient = context.createLinearGradient(0, cy, width, cy);
          gradient.addColorStop(0, `rgba(${colors[band]},0)`);
          gradient.addColorStop(
            0.35,
            `rgba(${colors[band]},${backdrop ? 0.18 : 0.11})`,
          );
          gradient.addColorStop(
            0.65,
            `rgba(${colors[(band + 1) % 4]},${backdrop ? 0.22 : 0.15})`,
          );
          gradient.addColorStop(1, `rgba(${colors[(band + 1) % 4]},0)`);
          for (const stroke of [0, 1]) {
            context.beginPath();
            context.moveTo(-40, cy);
            context.bezierCurveTo(
              width * 0.25,
              cy - height * 0.38 * Math.cos(phase),
              width * 0.65,
              cy + height * 0.35 * Math.sin(phase),
              width + 40,
              cy - height * 0.14,
            );
            context.strokeStyle = gradient;
            context.lineWidth = stroke ? 1.4 : Math.min(width, height) * 0.075;
            context.globalAlpha = stroke ? 0.8 : 0.3;
            context.stroke();
          }
        }
        context.globalAlpha = 1;
        pointer.x += (pointer.tx - pointer.x) * Math.min(1, dt * 9);
        pointer.y += (pointer.ty - pointer.y) * Math.min(1, dt * 9);
        if (pointer.active) {
          const halo = context.createRadialGradient(
            pointer.x,
            pointer.y,
            0,
            pointer.x,
            pointer.y,
            250,
          );
          halo.addColorStop(0, "rgba(116,72,255,0.19)");
          halo.addColorStop(0.42, "rgba(0,201,255,0.065)");
          halo.addColorStop(1, "rgba(0,201,255,0)");
          context.fillStyle = halo;
          context.fillRect(pointer.x - 250, pointer.y - 250, 500, 500);
        }
        while (trail.length && now - trail[0].at > 380) trail.shift();
        for (let i = 1; i < trail.length; i++) {
          const fade = Math.max(0, 1 - (now - trail[i].at) / 380);
          context.strokeStyle = `rgba(${colors[i % 3]},${fade * 0.65})`;
          context.lineWidth = fade * 2.4;
          context.beginPath();
          context.moveTo(trail[i - 1].x, trail[i - 1].y);
          context.lineTo(trail[i].x, trail[i].y);
          context.stroke();
        }
        while (bursts.length && now - bursts[0].at > 1100) bursts.shift();
        for (const b of bursts) {
          const t = Math.min(1, (now - b.at) / 1100),
            fade = (1 - t) ** 2;
          const radius = (22 + (1 - (1 - t) ** 3) * 280) * b.power;
          const glow = context.createRadialGradient(
            b.x,
            b.y,
            0,
            b.x,
            b.y,
            radius,
          );
          glow.addColorStop(0, `rgba(255,112,207,${fade * 0.33})`);
          glow.addColorStop(0.7, `rgba(93,78,255,${fade * 0.13})`);
          glow.addColorStop(1, "rgba(0,223,255,0)");
          context.fillStyle = glow;
          context.fillRect(b.x - radius, b.y - radius, radius * 2, radius * 2);
          context.strokeStyle = `rgba(93,231,255,${fade * 0.7})`;
          context.lineWidth = 1.5;
          context.beginPath();
          context.arc(b.x, b.y, radius, 0, Math.PI * 2);
          context.stroke();
          for (let spark = 0; spark < 28; spark++) {
            const angle = spark * 2.39996;
            const reach = radius * (0.65 + (spark % 5) * 0.12);
            const length = 4 + fade * 18 * b.power;
            context.strokeStyle = `rgba(${colors[spark % 4]},${fade})`;
            context.lineWidth = spark % 3 === 0 ? 2 : 1;
            context.beginPath();
            context.moveTo(
              b.x + Math.cos(angle) * reach,
              b.y + Math.sin(angle) * reach,
            );
            context.lineTo(
              b.x + Math.cos(angle) * (reach + length),
              b.y + Math.sin(angle) * (reach + length),
            );
            context.stroke();
          }
        }
        const wind = Math.exp(-Math.max(0, now - movement.current.at) / 130);
        for (const p of particles) {
          p.x +=
            ((Math.sin(now * 0.00022 + p.phase) * 4 +
              movement.current.x * wind * 14) *
              p.depth *
              dt) /
            Math.max(width, 1);
          p.y +=
            ((-9 - p.depth * 9 + movement.current.y * wind * 12) * dt) /
            Math.max(height, 1);
          p.x = (p.x + 1) % 1;
          p.y = (p.y + 1) % 1;
          const x = p.x * width,
            y = p.y * height;
          const alpha =
            0.22 + p.depth * 0.25 + Math.sin(now * 0.0007 + p.phase) * 0.06;
          const color = p.color;
          const glow = context.createRadialGradient(
            x,
            y,
            0,
            x,
            y,
            p.radius * 5,
          );
          glow.addColorStop(0, `rgba(${color},${alpha})`);
          glow.addColorStop(1, `rgba(${color},0)`);
          context.fillStyle = glow;
          context.beginPath();
          context.arc(x, y, p.radius * 5, 0, Math.PI * 2);
          context.fill();
          context.fillStyle = `rgba(${color},${alpha + 0.15})`;
          context.beginPath();
          context.arc(x, y, p.radius * 0.6, 0, Math.PI * 2);
          context.fill();
        }
      }
      frame = requestAnimationFrame(draw);
    };
    const visibility = () => {
      cancelAnimationFrame(frame);
      previous = 0;
      if (!document.hidden) frame = requestAnimationFrame(draw);
    };
    document.addEventListener("visibilitychange", visibility);
    visibility();
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      window.removeEventListener("pointermove", track);
      document.documentElement.removeEventListener("pointerleave", leave);
      window.removeEventListener(burstEvent, burst);
      document.removeEventListener("visibilitychange", visibility);
    };
  }, [enabled, reduced, movement, backdrop]);
  return enabled && !reduced ? (
    <canvas
      ref={canvas}
      className={`ambient-dust ${backdrop ? "ambient-backdrop" : ""}`}
      aria-hidden="true"
    />
  ) : null;
}
