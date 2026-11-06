import { hash } from "./geometry";

const decorations = [
  { kind: "star", label: "星星", src: "/media/tabletop-star.svg" },
  { kind: "die", label: "骰子", src: "/media/tabletop-die.svg" },
  { kind: "card", label: "卡牌", src: "/media/tabletop-card.svg" },
  { kind: "pudding", label: "布丁", src: "/media/tabletop-pudding.svg" },
] as const;

// One small or medium slot per 12-slot block, stable for all world coordinates.
// Large cover slots remain available for works.
const candidates = [1, 2, 3, 4, 6, 7, 9, 10, 11];
export function patternForBlock(col: number, row: number) {
  return decorations[hash(col - 7, row + 11) % decorations.length];
}
export function decorationForSlot(key: string) {
  const parts = /^(-?\d+),(-?\d+):(\d+)$/.exec(key);
  if (!parts) return null;
  const [, col, row, index] = parts.map(Number);
  if (![col, row, index].every(Number.isSafeInteger)) return null;
  const seed = hash(col + 17, row - 29);
  const mixed = (seed ^ (seed >>> 16)) >>> 0;
  if (index !== candidates[mixed % candidates.length]) return null;
  return patternForBlock(col, row);
}
