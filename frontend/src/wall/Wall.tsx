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
import { ArrowUpRight } from "lucide-react";
import {
  BLOCK_HEIGHT,
  BLOCK_WIDTH,
  pixel,
  reflow,
  slotsForBlock,
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
import { Artwork } from "../shared/ui";
import { useReducedMotion } from "../shared/hooks";
import {
  activateSlot,
  fogStyle,
  useWallFog,
  type WallAction,
} from "./interaction";

function Block({
  column,
  row,
  selectedSlot,
  onActivate,
  onClear,
  dragging,
  reduced,
}: {
  column: number;
  row: number;
  selectedSlot: string | null;
  onActivate: (id: string, key: string) => void;
  onClear: () => void;
  dragging: React.RefObject<boolean>;
  reduced: boolean;
}) {
  const elements = useRef<(HTMLButtonElement | null)[]>([]),
    director = useRef<MotionDirector | null>(null);
  const slots = useMemo(() => slotsForBlock(column, row), [column, row]);
  const selectedIndex = slots.findIndex((slot) => slot.key === selectedSlot);
  const target = selectedIndex < 0 ? null : selectedIndex;
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
    };
  }, [column, row]);
  useEffect(
    () => director.current?.to(reflow(column, row, target).map(pixel), reduced),
    [column, row, target, reduced],
  );
  return (
    <div
      className="wall-block"
      data-block={`${column},${row}`}
      style={{
        transform: `translate(${column * BLOCK_WIDTH}px,${row * BLOCK_HEIGHT}px)`,
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
            className={`wall-tile ${target === i ? "previewing is-selected" : ""} ${w ? "" : "material-tile"}`}
            aria-label={
              w
                ? `${w.title}，${w.author.displayName}，${target === i ? "再次点击进入详情" : "点击展开作品"}`
                : "尚未探索的空间"
            }
            tabIndex={w ? 0 : -1}
            aria-hidden={!w}
            aria-pressed={w ? target === i : undefined}
            onFocus={() => {
              wallMemory.focus = slot.key;
              saveWall();
            }}
            onClick={(e) => {
              if (dragging.current) {
                e.preventDefault();
                return;
              }
              if (!w) return;
              e.currentTarget.focus({ preventScroll: true });
              wallMemory.focus = slot.key;
              onActivate(w.id, slot.key);
            }}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                onClear();
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
            ) : null}
            {w && target === i && (
              <span className="open-hint">
                再次点击，进入详情 <ArrowUpRight size={14} />
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
  const [selectedSlot, setSelectedSlot] = useState<string | null>(
    wallMemory.selectedSlot,
  );
  const [fog] = useWallFog();
  const selection = useRef(selectedSlot);
  const clearSelection = () => {
    selection.current = null;
    setSelectedSlot(null);
    wallMemory.selectedSlot = null;
    saveWall();
  };
  const activate = (id: string, key: string) => {
    const next = activateSlot(selection.current, key);
    selection.current = next.selectedSlot;
    setSelectedSlot(next.selectedSlot);
    wallMemory.selectedSlot = next.selectedSlot;
    saveWall();
    if (next.open) navigate(`/works/${encodeURIComponent(id)}`);
  };
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
  useEffect(() => {
    const listener = (event: Event) => {
      const action = (event as CustomEvent<WallAction>).detail;
      if (action === "clear" || action === "explore") clearSelection();
      if (action === "center" || action === "explore") {
        camera.current = { x: 50, y: 112 };
        paint();
      }
      if (action === "explore" || (action === "retry" && wallMemory.expired)) {
        refreshFeed();
        if (action === "explore") setClip({ ...camera.current });
        else discoverSlots(orderedSlots);
      } else if (action === "retry") void loadFeed();
    };
    window.addEventListener("htw:wall-action", listener);
    return () => window.removeEventListener("htw:wall-action", listener);
  }, [orderedSlots, paint]);
  const finish = (e: React.PointerEvent) => {
    const p = pointer.current;
    if (!p || p.id !== e.pointerId) return;
    if (dragging.current) {
      if (e.type === "pointerup") {
        camera.current = {
          x: p.cx + e.clientX - p.x,
          y: p.cy + e.clientY - p.y,
        };
        paint();
      }
      blockedUntil.current = performance.now() + 180;
      setMoving(false);
    }
    pointer.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId))
      e.currentTarget.releasePointerCapture(e.pointerId);
    saveWall();
    dragging.current = false;
  };
  return (
    <main
      className={`wall ${moving ? "is-dragging" : ""}`}
      ref={viewport}
      aria-label="可向四个方向拖动的作品墙"
      style={fogStyle(fog)}
      onDragStartCapture={(e) => e.preventDefault()}
      onKeyDown={(e) => {
        if (e.key === "Escape") clearSelection();
      }}
      onFocusCapture={(e) => {
        if (!(e.target instanceof HTMLButtonElement) || !e.target.dataset.slot)
          return;
        if (!e.target.matches(":focus-visible")) return;
        const rect = e.target.getBoundingClientRect(),
          bounds = viewport.current!.getBoundingClientRect();
        let dx = 0,
          dy = 0;
        if (rect.left < bounds.left + 12) dx = bounds.left + 12 - rect.left;
        else if (rect.right > bounds.right - 12)
          dx = bounds.right - 12 - rect.right;
        if (rect.top < bounds.top + 60) dy = bounds.top + 60 - rect.top;
        else if (rect.bottom > bounds.bottom - 12)
          dy = bounds.bottom - 12 - rect.bottom;
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
        const element = e.target as HTMLElement;
        // Keep native image dragging and text selection from stealing the gesture.
        e.preventDefault();
        const owner =
          element.closest<HTMLElement>(".wall-tile") || e.currentTarget;
        owner.setPointerCapture(e.pointerId);
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
        if (!dragging.current && Math.hypot(dx, dy) > 6) {
          dragging.current = true;
          setMoving(true);
          // The viewport stays mounted while cards are clipped during a long pan.
          viewport.current!.setPointerCapture(e.pointerId);
        }
        if (dragging.current) {
          camera.current = { x: p.cx + dx, y: p.cy + dy };
          schedule();
        }
      }}
      onPointerUp={finish}
      onPointerCancel={finish}
      onLostPointerCapture={(e) => {
        if (e.target === e.currentTarget) finish(e);
      }}
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
            dragging={dragging}
            reduced={reduced}
            selectedSlot={selectedSlot}
            onActivate={activate}
            onClear={clearSelection}
          />
        ))}
      </div>
    </main>
  );
}
