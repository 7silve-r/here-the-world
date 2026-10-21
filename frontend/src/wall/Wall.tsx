import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import { useNavigate } from "react-router-dom";
import { ArrowUpRight, Expand, Move, RotateCcw } from "lucide-react";
import {
  BLOCK_HEIGHT,
  BLOCK_WIDTH,
  pixel,
  reflow,
  slotsForBlock,
  stableHit,
  template,
  visibleBlocks,
  type Rect,
} from "./geometry";
import { MotionDirector } from "./motion";
import {
  cancelFeed,
  discoverSlots,
  feedSnapshot,
  loadFeed,
  refreshFeed,
  saveWall,
  subscribeFeed,
  wallMemory,
} from "../feed/provider";
import { Artwork, Button, Notice, useNavigation } from "../shared/ui";
import { useReducedMotion } from "../shared/hooks";

function Block({
  column,
  row,
  onOpen,
  dragging,
  reduced,
  shift,
}: {
  column: number;
  row: number;
  onOpen: (id: string, key: string) => void;
  dragging: React.RefObject<boolean>;
  reduced: boolean;
  shift: number;
}) {
  const elements = useRef<(HTMLButtonElement | null)[]>([]),
    director = useRef<MotionDirector | null>(null),
    root = useRef<HTMLDivElement>(null);
  const [target, setTarget] = useState<number | null>(null),
    timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined),
    touch = useRef(false),
    touchPreview = useRef<number | null>(null),
    pending = useRef<number | null>(null);
  const slots = useMemo(() => slotsForBlock(column, row), [column, row]);
  useLayoutEffect(() => {
    const commit = (rects: Rect[]) =>
      rects.forEach((r, i) => {
        const el = elements.current[i];
        if (el) {
          el.style.transform = `translate3d(${r.x}px,${r.y}px,0)`;
          el.style.width = `${r.width}px`;
          el.style.height = `${r.height}px`;
        }
      });
    const rects = template(column, row).map(pixel);
    commit(rects);
    director.current = new MotionDirector(rects, commit);
    return () => {
      director.current?.destroy();
      clearTimeout(timer.current);
    };
  }, [column, row]);
  useEffect(
    () => director.current?.to(reflow(column, row, target).map(pixel), reduced),
    [column, row, target, reduced],
  );
  const preview = (index: number | null) => {
    if (pending.current === index) return;
    pending.current = index;
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setTarget(index), 120);
  };
  return (
    <div
      ref={root}
      className="wall-block"
      data-block={`${column},${row}`}
      style={{
        transform: `translate(${column * BLOCK_WIDTH + shift}px,${row * BLOCK_HEIGHT}px)`,
      }}
      onPointerMove={(e) => {
        if (dragging.current || e.pointerType === "touch") return;
        const rect = root.current!.getBoundingClientRect();
        const s = rect.width / BLOCK_WIDTH;
        const hit = stableHit(
          column,
          row,
          (e.clientX - rect.left) / s,
          (e.clientY - rect.top) / s,
        );
        preview(hit < 0 ? null : hit);
      }}
      onPointerLeave={() => {
        if (!touch.current) preview(null);
      }}
    >
      {slots.map((slot, i) => {
        const id = wallMemory.slots[slot.key],
          w = id ? wallMemory.items[id] : undefined;
        return (
          <button
            ref={(el) => {
              elements.current[i] = el;
            }}
            key={slot.key}
            data-slot={slot.key}
            className={`wall-tile ${target === i ? "previewing" : ""} ${w ? "" : "material-tile"}`}
            aria-label={
              w
                ? `${w.title}，${w.author.displayName}，预览后打开作品`
                : "尚未探索的空间"
            }
            tabIndex={w ? 0 : -1}
            aria-hidden={!w}
            onFocus={() => {
              setTarget(i);
              wallMemory.focus = slot.key;
              saveWall();
            }}
            onBlur={() => setTarget(null)}
            onPointerDown={(e) => {
              touch.current = e.pointerType === "touch";
            }}
            onClick={(e) => {
              if (dragging.current) {
                e.preventDefault();
                return;
              }
              if (!w) return;
              if (touch.current && touchPreview.current !== i) {
                touchPreview.current = i;
                setTarget(i);
                return;
              }
              wallMemory.focus = slot.key;
              saveWall();
              onOpen(w.id, slot.key);
            }}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                setTarget(null);
                e.currentTarget.blur();
              }
              if (e.key.startsWith("Arrow")) {
                e.preventDefault();
                const nodes = Array.from(
                  document.querySelectorAll<HTMLButtonElement>(
                    ".wall-tile:not(.material-tile)",
                  ),
                );
                const current = e.currentTarget.getBoundingClientRect();
                const cx = current.x + current.width / 2,
                  cy = current.y + current.height / 2;
                const next = nodes
                  .filter((n) => n !== e.currentTarget)
                  .map((n) => {
                    const r = n.getBoundingClientRect(),
                      dx = r.x + r.width / 2 - cx,
                      dy = r.y + r.height / 2 - cy;
                    const forward =
                      e.key === "ArrowRight"
                        ? dx
                        : e.key === "ArrowLeft"
                          ? -dx
                          : e.key === "ArrowDown"
                            ? dy
                            : -dy;
                    const sideways =
                      e.key === "ArrowRight" || e.key === "ArrowLeft" ? dy : dx;
                    return {
                      n,
                      score:
                        forward > 0
                          ? forward + Math.abs(sideways) * 3
                          : Infinity,
                    };
                  })
                  .sort((a, b) => a.score - b.score)[0];
                if (next && Number.isFinite(next.score)) next.n.focus();
              }
            }}
          >
            {w ? (
              <Artwork work={w} priority={column === 0 && row === 0} />
            ) : (
              <span className="material-label">
                {wallMemory.hasMore ? "正在发现…" : "世界还有留白"}
                <span>HERE / ELSEWHERE</span>
              </span>
            )}
            {w && target === i && (
              <span className="open-hint">
                {touch.current ? "再点一次，进入作品" : "进入这一刻"}{" "}
                <ArrowUpRight size={14} />
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

export default function Wall() {
  useSyncExternalStore(subscribeFeed, feedSnapshot);
  const navigate = useNavigate(),
    { open } = useNavigation(),
    reduced = useReducedMotion();
  const world = useRef<HTMLDivElement>(null),
    viewport = useRef<HTMLDivElement>(null),
    camera = useRef({ ...wallMemory.camera }),
    dragging = useRef(false),
    pointer = useRef<{
      id: number;
      x: number;
      y: number;
      cx: number;
      cy: number;
    } | null>(null),
    raf = useRef(0),
    lastClip = useRef({ x: Infinity, y: Infinity }),
    blockedUntil = useRef(0);
  const [size, setSize] = useState({
      width: window.innerWidth,
      height: window.innerHeight,
    }),
    [clip, setClip] = useState({ ...camera.current }),
    [moving, setMoving] = useState(false);
  const scale = size.width < 640 ? 0.62 : size.width < 1000 ? 0.8 : 1;
  const blocks = useMemo(
    () => visibleBlocks(clip, size, scale),
    [clip, size, scale],
  );
  const orderedSlots = useMemo(
    () =>
      blocks
        .flatMap((b) => slotsForBlock(b.column, b.row))
        .sort((a, b) => {
          const cx = (size.width / 2 - clip.x) / scale,
            cy = (size.height / 2 - clip.y) / scale;
          return (
            (a.x + a.width / 2 - cx) ** 2 +
            (a.y + a.height / 2 - cy) ** 2 -
            ((b.x + b.width / 2 - cx) ** 2 + (b.y + b.height / 2 - cy) ** 2)
          );
        })
        .map((s) => s.key),
    [blocks, clip, scale, size],
  );
  useEffect(() => {
    discoverSlots(orderedSlots);
  }, [orderedSlots]);
  const paint = useCallback(() => {
    raf.current = 0;
    const c = camera.current;
    if (world.current)
      world.current.style.transform = `translate3d(${c.x}px,${c.y}px,0) scale(${scale})`;
    wallMemory.camera = { ...c };
    if (
      Math.abs(c.x - lastClip.current.x) > 100 ||
      Math.abs(c.y - lastClip.current.y) > 100
    ) {
      lastClip.current = { ...c };
      setClip({ ...c });
    }
  }, [scale]);
  const schedule = () => {
    if (!raf.current) raf.current = requestAnimationFrame(paint);
  };
  useLayoutEffect(() => {
    paint();
  }, [paint]);
  useEffect(() => {
    const observer = new ResizeObserver((entries) =>
      setSize({
        width: entries[0].contentRect.width,
        height: entries[0].contentRect.height,
      }),
    );
    observer.observe(viewport.current!);
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      camera.current.x -= e.shiftKey ? e.deltaY : e.deltaX;
      camera.current.y -= e.shiftKey ? e.deltaX : e.deltaY;
      schedule();
    };
    const node = viewport.current!;
    node.addEventListener("wheel", wheel, { passive: false });
    return () => {
      observer.disconnect();
      node.removeEventListener("wheel", wheel);
      cancelAnimationFrame(raf.current);
      saveWall();
    };
  }, [paint]);
  useEffect(() => {
    if (wallMemory.focus) {
      const node = document.querySelector<HTMLButtonElement>(
        `[data-slot="${wallMemory.focus}"]`,
      );
      node?.focus({ preventScroll: true });
    }
    return () => cancelFeed();
  }, []);
  const finish = (e: React.PointerEvent) => {
    const p = pointer.current;
    if (!p || p.id !== e.pointerId) return;
    if (dragging.current) {
      blockedUntil.current = performance.now() + 350;
      setMoving(false);
    }
    pointer.current = null;
    viewport.current?.releasePointerCapture(e.pointerId);
    saveWall();
    setTimeout(() => {
      dragging.current = false;
    }, 0);
  };
  return (
    <main
      className={`wall ${moving ? "is-dragging" : ""} ${open ? "nav-open" : ""}`}
      ref={viewport}
      aria-label="可向四个方向拖动的作品墙"
      onFocusCapture={(e) => {
        if (!(e.target instanceof HTMLButtonElement) || !e.target.dataset.slot)
          return;
        const rect = e.target.getBoundingClientRect(),
          bounds = viewport.current!.getBoundingClientRect();
        let dx = 0,
          dy = 0;
        if (rect.left < bounds.left + 12) dx = bounds.left + 12 - rect.left;
        else if (rect.right > bounds.right - 12)
          dx = bounds.right - 12 - rect.right;
        if (rect.top < bounds.top + 60) dy = bounds.top + 60 - rect.top;
        else if (rect.bottom > bounds.bottom - 85)
          dy = bounds.bottom - 85 - rect.bottom;
        if (dx || dy) {
          camera.current = {
            x: camera.current.x + dx,
            y: camera.current.y + dy,
          };
          schedule();
        }
      }}
      onPointerDown={(e) => {
        if (e.button !== 0 || e.isPrimary === false) return;
        pointer.current = {
          id: e.pointerId,
          x: e.clientX,
          y: e.clientY,
          cx: camera.current.x,
          cy: camera.current.y,
        };
        dragging.current = false;
      }}
      onPointerMove={(e) => {
        const p = pointer.current;
        if (!p || p.id !== e.pointerId) return;
        const dx = e.clientX - p.x,
          dy = e.clientY - p.y;
        if (!dragging.current && Math.hypot(dx, dy) > 7) {
          dragging.current = true;
          setMoving(true);
          viewport.current!.setPointerCapture(e.pointerId);
        }
        if (dragging.current) {
          camera.current = { x: p.cx + dx, y: p.cy + dy };
          schedule();
        }
      }}
      onPointerUp={finish}
      onPointerCancel={finish}
      onClickCapture={(e) => {
        if (performance.now() < blockedUntil.current) {
          e.preventDefault();
          e.stopPropagation();
        }
      }}
    >
      <div className="wall-world" ref={world}>
        {blocks.map((b) => (
          <Block
            key={b.key}
            column={b.column}
            row={b.row}
            shift={open ? (b.column < 0 ? -320 : 320) : 0}
            dragging={dragging}
            reduced={reduced}
            onOpen={(id) => navigate(`/works/${encodeURIComponent(id)}`)}
          />
        ))}
      </div>
      <div className="wall-topline" aria-hidden="true">
        <span>THE WORLD IS AN OPEN CANVAS</span>
        <span>
          随心游走，自在相遇 <span className="tiny-cross">＋</span>
        </span>
      </div>
      <div className="wall-bottom">
        <div className="explore-label">
          <span className="eyebrow">YOUR DAILY DOSE OF ELSEWHERE</span>
          <strong>
            不设边界，只管好奇。<span>Explore the unexpected.</span>
          </strong>
        </div>
        <div className="wall-tools">
          <span className="touch-instruction">点按预览，再点打开</span>
          <span className="drag-instruction">
            <Move size={14} /> 拖动画布 · 悬停预览 · 点击进入
          </span>
          <button
            className="icon-button"
            aria-label="回到起点"
            onClick={() => {
              camera.current = { x: 50, y: 112 };
              paint();
            }}
          >
            <Expand size={18} />
          </button>
          <button
            className="icon-button"
            aria-label="开启新的探索"
            onClick={() => {
              refreshFeed();
              camera.current = { x: 50, y: 112 };
              paint();
              discoverSlots(orderedSlots);
            }}
          >
            <RotateCcw size={16} />
          </button>
        </div>
      </div>
      {(wallMemory.expired || wallMemory.error) && (
        <div className="wall-notice">
          <Notice
            message={
              wallMemory.expired
                ? "这次探索已过期，当前画面已为你保留。"
                : undefined
            }
            error={wallMemory.error ? new Error(wallMemory.error) : undefined}
          />
          <Button
            onClick={() => {
              if (wallMemory.expired) {
                refreshFeed();
                discoverSlots(orderedSlots);
              } else void loadFeed();
            }}
          >
            {wallMemory.expired ? "开始新的探索" : "重试"}
          </Button>
        </div>
      )}
    </main>
  );
}
