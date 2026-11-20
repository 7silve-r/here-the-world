import { describe, expect, it } from "vitest";
import { decorationForSlot, patternForBlock } from "../src/wall/decoration";
import { template } from "../src/wall/geometry";
import { existsSync } from "node:fs";
import {
  characterRoster,
  orangeJuiceCharacters,
  characterForPath,
} from "../src/shared/characters";

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
    expect(kinds).toEqual(
      new Set(["star", "die", "card", "pudding", ...characterRoster]),
    );
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
      expect(pattern.src).toMatch(
        /^\/media\/(tabletop-.*\.svg|orange-juice\/[a-z/-]+\.(png|jpg))$/,
      );
      for (let i = 0; i < template(col, row).length; i++) {
        const reserved = decorationForSlot(`${col},${row}:${i}`);
        if (reserved) expect(reserved).toEqual(pattern);
      }
    }
  });
  it("keeps character art local and sparse without reserving extra wall slots", () => {
    let characters = 0;
    const blocks = 64 * 64;
    for (let row = -32; row < 32; row++)
      for (let col = -32; col < 32; col++) {
        const pattern = patternForBlock(col, row);
        if (pattern.character) {
          characters++;
          expect(characterRoster).toContain(pattern.character);
          expect(existsSync(`public${pattern.src}`)).toBe(true);
        }
      }
    expect(characters / (blocks * 12)).toBeGreaterThan(0.03);
    expect(characters / (blocks * 12)).toBeLessThan(0.05);
  });
  it("supplies both a card and a local portrait for all twelve characters", () => {
    expect(characterRoster).toHaveLength(12);
    expect(new Set(characterRoster).size).toBe(12);
    for (const id of characterRoster) {
      const art = orangeJuiceCharacters[id];
      expect(existsSync(`public${art.src}`)).toBe(true);
      expect(existsSync(`public${art.portrait}`)).toBe(true);
    }
  });
  it("keeps role decoration stable across page visits without changing route identity", () => {
    expect(characterForPath("/settings")).toBe("suguri");
    expect(characterForPath("/me/bookmarks")).toBe("hime");
    expect(characterForPath("/publish/work_1")).toBe("aru");
    const workPath = "/works/work_not-a-number";
    expect(characterRoster).toContain(characterForPath(workPath));
    expect(characterForPath(workPath)).toBe(characterForPath(workPath));
  });
});
