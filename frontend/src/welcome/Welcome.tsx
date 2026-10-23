import { useEffect, useRef, useState, type CSSProperties } from "react";
import { useNavigate } from "react-router-dom";
import { useReducedMotion } from "../shared/hooks";
import { useAtmosphere } from "../shared/atmosphere";
import { MatrixPortal } from "../shared/MatrixPortal";
import { transitionRoute } from "../shared/cover-transition";

const words = [
  {
    text: "Here,",
    positions: [
      [14, 27],
      [21, 23],
      [28, 21],
      [35, 22],
      [41, 25],
    ],
  },
  {
    text: "The",
    positions: [
      [71, 25],
      [78, 29],
      [84, 35],
    ],
  },
  {
    text: "World!",
    positions: [
      [28, 77],
      [37, 80],
      [45, 81],
      [52, 80],
      [59, 78],
      [66, 74],
    ],
  },
];

export default function Welcome() {
  const navigate = useNavigate();
  const reduced = useReducedMotion();
  const [atmosphere] = useAtmosphere();
  const still = reduced || !atmosphere;
  const enteringRef = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [entering, setEntering] = useState(false);
  useEffect(() => {
    void import("../wall/Wall").catch(() => {});
    return () => clearTimeout(timer.current);
  }, []);
  function enter() {
    if (enteringRef.current) return;
    enteringRef.current = true;
    setEntering(true);
    if (still) navigate("/");
    else
      timer.current = setTimeout(
        () => transitionRoute(() => navigate("/")),
        650,
      );
  }
  return (
    <main
      className={`matrix-welcome${entering ? " is-entering" : ""}`}
      data-still={still || undefined}
    >
      <div className="welcome-stage">
        <div className="welcome-matrix">
          <MatrixPortal />
        </div>
        <h1 className="welcome-letterfield" aria-label="Here, The World!">
          {words.map((word, group) => (
            <span key={word.text} aria-hidden="true">
              {Array.from(word.text).map((letter, index) => (
                <span
                  className="welcome-letter-position"
                  key={index}
                  style={
                    {
                      left: `${word.positions[index][0]}%`,
                      top: `${word.positions[index][1]}%`,
                      "--letter-delay": `${-(group * 2 + index * 0.65)}s`,
                      "--letter-duration": `${6 + (index % 3)}s`,
                      "--letter-turn": `${((index % 3) - 1) * 7}deg`,
                    } as CSSProperties
                  }
                >
                  <i>{letter}</i>
                </span>
              ))}
            </span>
          ))}
        </h1>
      </div>
      <p className="welcome-whisper" aria-hidden="true">
        Click anywhere to enter
      </p>
      <button
        className="welcome-entry-surface"
        type="button"
        aria-label="进入作品墙 · Click anywhere to enter"
        aria-busy={entering}
        onClick={enter}
      />
      <div className="welcome-fade" aria-hidden="true" />
    </main>
  );
}
