import type { CSSProperties } from "react";
import { orangeJuiceCharacters, type OrangeJuiceCharacter } from "./characters";
export { orangeJuiceCharacters, type OrangeJuiceCharacter } from "./characters";

/** Decorative official character art; never a user avatar or a work. */
export function CharacterCard({
  character,
  className = "",
}: {
  character: OrangeJuiceCharacter;
  className?: string;
}) {
  const art = orangeJuiceCharacters[character];
  return (
    <span
      className={`character-card ${className}`}
      data-character={character}
      data-portrait={art.src.endsWith(".jpg") || undefined}
      style={
        { "--card-edge": art.edge, "--card-color": art.paper } as CSSProperties
      }
      aria-hidden="true"
    >
      <span className="character-card-back" />
      <img
        src={art.src}
        alt=""
        width="300"
        height="300"
        loading="lazy"
        decoding="async"
        draggable={false}
      />
      <i className="character-spark" />
    </span>
  );
}

export function CharacterBadge({
  character,
  className = "",
}: {
  character: OrangeJuiceCharacter;
  className?: string;
}) {
  const art = orangeJuiceCharacters[character];
  return (
    <span
      className={`character-badge ${className}`}
      data-character={character}
      aria-hidden="true"
      style={
        {
          "--badge-edge": art.edge,
          "--badge-paper": art.paper,
        } as CSSProperties
      }
    >
      <img
        src={art.portrait}
        alt=""
        width="150"
        height="150"
        loading="lazy"
        decoding="async"
        draggable={false}
      />
    </span>
  );
}
