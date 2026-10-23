import type { CSSProperties } from "react";
import { template } from "../wall/geometry";
import { useReducedMotion } from "./hooks";
import { useAtmosphere } from "./atmosphere";

const panes = template(0, 0);

/** Decorative geometry shares the wall vocabulary, without fabricating works. */
export function MatrixPortal({ compact = false }: { compact?: boolean }) {
  const reduced = useReducedMotion();
  const [enabled] = useAtmosphere();
  return (
    <div
      className={`matrix-portal${compact ? " compact" : ""}`}
      data-still={reduced || !enabled || undefined}
      aria-hidden="true"
    >
      <div className="portal-halo" />
      <div className="portal-plane">
        {panes.map((pane, index) => (
          <span
            className="portal-pane"
            key={index}
            style={
              {
                left: `${(pane.x / 12) * 100}%`,
                top: `${(pane.y / 8) * 100}%`,
                width: `${(pane.width / 12) * 100}%`,
                height: `${(pane.height / 8) * 100}%`,
                "--pane-index": index,
              } as CSSProperties
            }
          >
            <i />
          </span>
        ))}
        <div className="portal-aperture">
          <i />
          <i />
          <i />
        </div>
      </div>
    </div>
  );
}
