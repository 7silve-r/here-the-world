import { hash } from "./geometry";
import {
  orangeJuiceCharacters,
  characterRoster,
  type OrangeJuiceCharacter,
} from "../shared/characters";

type Decoration = {
  kind: string;
  label: string;
  src: string;
  character?: OrangeJuiceCharacter;
};
// Characters occupy half the decorative tiles, about 4% of the whole wall.
const decorations: readonly Decoration[] = [
  { kind: "star", label: "星星", src: "/media/tabletop-star.svg" },
  { kind: "die", label: "骰子", src: "/media/tabletop-die.svg" },
  { kind: "card", label: "卡牌", src: "/media/tabletop-card.svg" },
  { kind: "pudding", label: "布丁", src: "/media/tabletop-pudding.svg" },
];

// One small or medium slot per 12-slot block, stable for all world coordinates.
// Large cover slots remain available for works.
const candidates = [1, 2, 3, 4, 6, 7, 9, 10, 11];
export function patternForBlock(col: number, row: number): Decoration {
  const seed = hash(col - 7, row + 11);
  if (seed & 1) return decorations[(seed >>> 4) % decorations.length];
  const character = characterRoster[(seed >>> 4) % characterRoster.length];
  const art = orangeJuiceCharacters[character];
  return { kind: character, label: art.name, src: art.src, character };
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
