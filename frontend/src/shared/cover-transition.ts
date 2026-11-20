import { flushSync } from "react-dom";
import { queryClient } from "../api/cache";
import { api } from "../api/client";

let active: ViewTransition | undefined;
let workPage: typeof import("../works/Detail") | undefined;
export const preparedWorkPage = () => workPage?.default;
export const loadWorkPage = () =>
  import("../works/Detail").then((page) => {
    workPage = page;
    return page;
  });
export const coverTransitionActive = () =>
  document.documentElement.dataset.coverTransition === "true";
export const motionReduced = () =>
  matchMedia("(prefers-reduced-motion: reduce)").matches ||
  document.documentElement.dataset.motion === "REDUCED";
export const coverName = (workId: string) =>
  `work-${Array.from(workId, (char) => char.codePointAt(0)!.toString(16)).join("-")}`;

export function stopCoverTransition() {
  active?.skipTransition();
  active = undefined;
  delete document.documentElement.dataset.coverTransition;
}

export function transitionRoute(action: () => void) {
  if (
    motionReduced() ||
    !document.startViewTransition ||
    document.visibilityState === "hidden"
  ) {
    stopCoverTransition();
    action();
    return;
  }
  active?.skipTransition();
  document.documentElement.dataset.coverTransition = "true";
  let committed = false;
  try {
    const transition = document.startViewTransition(() => {
      if (!committed) {
        committed = true;
        flushSync(action);
      }
    });
    active = transition;
    void transition.ready?.catch(() => {});
    void transition.finished
      .catch(() => {})
      .finally(() => {
        if (active === transition) {
          active = undefined;
          delete document.documentElement.dataset.coverTransition;
        }
      });
  } catch {
    active = undefined;
    delete document.documentElement.dataset.coverTransition;
    if (!committed) action();
  }
}

// Prepare the route during selection, before the second activation snapshots the cover.
export function prepareWork(workId: string) {
  return Promise.allSettled([
    loadWorkPage(),
    queryClient.prefetchQuery({
      queryKey: ["work", workId],
      queryFn: ({ signal }) =>
        api("getWork", undefined, { params: { workId }, signal }),
    }),
  ]);
}
