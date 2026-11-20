export type Rect = { x: number; y: number; width: number; height: number };
export type Slot = Rect & {
  key: string;
  block: string;
  index: number;
  column: number;
  row: number;
};
export const PITCH = 76,
  GAP = 0,
  BLOCK_WIDTH = 12 * PITCH,
  BLOCK_HEIGHT = 8 * PITCH;
// Original exact-cover vocabulary. No Folia source or generated tables are used.
const base: Rect[] = [
  [0, 0, 5, 4],
  [5, 0, 3, 2],
  [8, 0, 4, 2],
  [5, 2, 4, 2],
  [9, 2, 3, 2],
  [0, 4, 3, 4],
  [3, 4, 3, 2],
  [6, 4, 3, 2],
  [9, 4, 3, 4],
  [3, 6, 2, 2],
  [5, 6, 2, 2],
  [7, 6, 2, 2],
].map(([x, y, width, height]) => ({ x, y, width, height }));
export function hash(x: number, y: number) {
  return (Math.imul(x, 73856093) ^ Math.imul(y, 19349663)) >>> 0;
}
function mirrored(rect: Rect, flipX: boolean, flipY: boolean): Rect {
  return {
    ...rect,
    x: flipX ? 12 - rect.x - rect.width : rect.x,
    y: flipY ? 8 - rect.y - rect.height : rect.y,
  };
}
export function template(column: number, row: number): Rect[] {
  const h = hash(column, row);
  return base.map((r) => mirrored(r, !!(h & 1), !!(h & 2)));
}
const distance = (a: Rect, b: Rect) =>
  (a.x + a.width / 2 - b.x - b.width / 2) ** 2 +
  (a.y + a.height / 2 - b.y - b.height / 2) ** 2;
export function reflow(
  column: number,
  row: number,
  target: number | null,
): Rect[] {
  const original = template(column, row);
  if (target === null) return original;
  const chosen = original[target],
    flipX = chosen.x + chosen.width / 2 > 6,
    flipY = chosen.y + chosen.height / 2 > 4;
  const candidates: Rect[] = [];
  for (let y = 0; y < 8; y += 2)
    for (let x = 6; x < 12; x += 3)
      candidates.push({ x, y, width: 3, height: 2 });
  candidates.splice(
    0,
    1,
    { x: 6, y: 0, width: 1, height: 2 },
    { x: 7, y: 0, width: 2, height: 2 },
  );
  candidates.push(
    { x: 0, y: 6, width: 3, height: 2 },
    { x: 3, y: 6, width: 3, height: 2 },
  );
  const available = candidates.map((r) => mirrored(r, flipX, flipY));
  return original.map((r, i) => {
    if (i === target)
      return mirrored({ x: 0, y: 0, width: 6, height: 6 }, flipX, flipY);
    let best = 0;
    available.forEach((v, j) => {
      if (distance(r, v) < distance(r, available[best])) best = j;
    });
    return available.splice(best, 1)[0];
  });
}
export function pixel(rect: Rect): Rect {
  return {
    x: rect.x * PITCH,
    y: rect.y * PITCH,
    width: rect.width * PITCH - GAP,
    height: rect.height * PITCH - GAP,
  };
}
export function visibleBlocks(
  camera: { x: number; y: number },
  viewport: { width: number; height: number },
  scale = 1,
): { column: number; row: number; key: string }[] {
  const margin = 180,
    left = (-camera.x - margin) / scale,
    top = (-camera.y - margin) / scale,
    right = (viewport.width - camera.x + margin) / scale,
    bottom = (viewport.height - camera.y + margin) / scale;
  const blocks = [];
  for (
    let row = Math.floor(top / BLOCK_HEIGHT);
    row <= Math.floor(bottom / BLOCK_HEIGHT);
    row++
  )
    for (
      let column = Math.floor(left / BLOCK_WIDTH);
      column <= Math.floor(right / BLOCK_WIDTH);
      column++
    )
      blocks.push({ column, row, key: `${column},${row}` });
  return blocks
    .sort(
      (a, b) =>
        (a.column * BLOCK_WIDTH - left) ** 2 +
        (a.row * BLOCK_HEIGHT - top) ** 2 -
        ((b.column * BLOCK_WIDTH - left) ** 2 +
          (b.row * BLOCK_HEIGHT - top) ** 2),
    )
    .slice(0, 33);
}
export function slotsForBlock(column: number, row: number): Slot[] {
  const block = `${column},${row}`;
  return template(column, row).map((r, index) => ({
    ...pixel(r),
    x: column * BLOCK_WIDTH + r.x * PITCH,
    y: row * BLOCK_HEIGHT + r.y * PITCH,
    key: `${block}:${index}`,
    block,
    index,
    column,
    row,
  }));
}
export function stableHit(column: number, row: number, x: number, y: number) {
  return template(column, row).findIndex(
    (r) =>
      x >= r.x * PITCH &&
      x < (r.x + r.width) * PITCH &&
      y >= r.y * PITCH &&
      y < (r.y + r.height) * PITCH,
  );
}
