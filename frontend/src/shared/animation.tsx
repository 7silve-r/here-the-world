import {
  LazyMotion,
  MotionConfig,
  domMax,
  m,
  AnimatePresence,
} from "motion/react";
import { useLocation } from "react-router-dom";
import { useEffect, type ReactNode } from "react";
import { useReducedMotion } from "./hooks";
import { coverTransitionActive, stopCoverTransition } from "./cover-transition";

export const softEase = [0.22, 1, 0.36, 1] as const;

export function MotionSystem({ children }: { children: ReactNode }) {
  const reduced = useReducedMotion();
  useEffect(() => {
    if (reduced) stopCoverTransition();
  }, [reduced]);
  return (
    <LazyMotion features={domMax} strict>
      <MotionConfig
        reducedMotion={reduced ? "always" : "never"}
        transition={{ duration: reduced ? 0 : 0.32, ease: softEase }}
      >
        {children}
      </MotionConfig>
    </LazyMotion>
  );
}

export function RouteScene({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const reduced = useReducedMotion();
  return (
    <m.div
      key={pathname}
      className="route-scene"
      initial={
        reduced || pathname === "/welcome" || coverTransitionActive()
          ? false
          : { opacity: 0, y: pathname === "/" ? 0 : 20 }
      }
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reduced ? 0 : 0.48, ease: softEase }}
    >
      {pathname !== "/" && pathname !== "/welcome" && !reduced && (
        <span className="route-flare" aria-hidden="true" />
      )}
      {children}
    </m.div>
  );
}

export function Reveal({
  children,
  className,
  delay = 0,
}: {
  children: ReactNode;
  className?: string;
  delay?: number;
}) {
  const reduced = useReducedMotion();
  return (
    <m.div
      className={className}
      initial={reduced ? false : { opacity: 0, y: 10 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.08 }}
      transition={{
        duration: reduced ? 0 : 0.36,
        delay: reduced ? 0 : delay,
        ease: softEase,
      }}
    >
      {children}
    </m.div>
  );
}

export function AnimatedNumber({ value }: { value: number }) {
  const reduced = useReducedMotion();
  return (
    <span className="animated-number" aria-label={String(value)}>
      <AnimatePresence initial={false} mode="popLayout">
        <m.span
          key={value}
          aria-hidden="true"
          initial={reduced ? false : { opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: reduced ? 0 : -6 }}
          transition={{ duration: reduced ? 0 : 0.18 }}
        >
          {value}
        </m.span>
      </AnimatePresence>
    </span>
  );
}
