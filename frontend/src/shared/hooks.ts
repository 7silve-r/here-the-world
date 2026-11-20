import { useEffect, useState, useSyncExternalStore } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getAuth, safeReturnTo, subscribeAuth } from "../api/client";
export const useAuth = () => useSyncExternalStore(subscribeAuth, getAuth);
export function useLoginGate() {
  const { session } = useAuth(),
    navigate = useNavigate(),
    location = useLocation();
  return (intent?: string) => {
    if (session) return true;
    const url = location.pathname + location.search;
    if (intent) sessionStorage.setItem("htw.intent", intent);
    navigate(`/login?returnTo=${encodeURIComponent(safeReturnTo(url))}`);
    return false;
  };
}
export function useReducedMotion() {
  const read = () =>
    matchMedia("(prefers-reduced-motion: reduce)").matches ||
    document.documentElement.dataset.motion === "REDUCED";
  const [reduced, setReduced] = useState(read);
  useEffect(() => {
    const m = matchMedia("(prefers-reduced-motion: reduce)");
    const change = () => setReduced(read());
    m.addEventListener("change", change);
    window.addEventListener("htw:preferences", change);
    return () => {
      m.removeEventListener("change", change);
      window.removeEventListener("htw:preferences", change);
    };
  }, []);
  return reduced;
}
export function abortableDelay(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, ms);
    const abort = () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  });
}
export async function waitUntilVisible(signal: AbortSignal) {
  if (document.visibilityState !== "hidden") return;
  await new Promise<void>((resolve, reject) => {
    const check = () => {
      if (document.visibilityState !== "hidden") {
        cleanup();
        resolve();
      }
    };
    const abort = () => {
      cleanup();
      reject(new DOMException("Aborted", "AbortError"));
    };
    const cleanup = () => {
      document.removeEventListener("visibilitychange", check);
      signal.removeEventListener("abort", abort);
    };
    document.addEventListener("visibilitychange", check);
    signal.addEventListener("abort", abort, { once: true });
  });
}
