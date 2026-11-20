const duration = 260;
const friction = 70;
export function glideOffset(velocity: number, elapsed: number) {
  const speed = Math.max(-1.35, Math.min(1.35, velocity));
  const t = Math.max(0, Math.min(duration, elapsed));
  return speed * friction * (1 - Math.exp(-t / friction));
}
export const glideFinished = (elapsed: number) => elapsed >= duration;
