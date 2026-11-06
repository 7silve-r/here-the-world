import { describe, expect, it } from "vitest";
import { decorationForSlot, patternForBlock } from "../src/wall/decoration";
import { template } from "../src/wall/geometry";

describe("sparse stable board decoration", () => {
  it("reserves one small or medium slot per block at positive and negative coordinates", () => {
    const kinds = new Set<string>();
    for (let row = -12; row <= 12; row++) {
      for (let col = -12; col <= 12; col++) {
        const chosen = template(col, row).flatMap((rect, i) => {
          const decoration = decorationForSlot(`${col},${row}:${i}`);
          if (!decoration) return [];
          kinds.add(decoration.kind);
          expect(rect.width * rect.height).toBeLessThanOrEqual(8);
          expect(decorationForSlot(`${col},${row}:${i}`)).toEqual(decoration);
          return [i];
        });
        expect(chosen).toHaveLength(1);
      }
    }
    expect(kinds.size).toBe(4);
  });
  it("does not claim invalid or unrelated slot keys", () => {
    for (const key of ["", "slot:1", "0,0:12", "NaN,0:2", "1.5,2:3", "1,2:-1"])
      expect(decorationForSlot(key)).toBeNull();
  });
  it("always supplies a graphic while a block awaits works, without bare material tiles", () => {
    for (const [col, row] of [
      [-11, 5],
      [0, 0],
      [19, -8],
    ]) {
      const pattern = patternForBlock(col, row);
      expect(pattern.src).toMatch(/^\/media\/tabletop-.*\.svg$/);
      for (let i = 0; i < template(col, row).length; i++) {
        const reserved = decorationForSlot(`${col},${row}:${i}`);
        if (reserved) expect(reserved).toEqual(pattern);
      }
    }
  });
});
