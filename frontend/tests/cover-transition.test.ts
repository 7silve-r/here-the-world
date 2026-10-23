import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  coverName,
  coverTransitionActive,
  stopCoverTransition,
  transitionRoute,
} from "../src/shared/cover-transition";

let page: {
  documentElement: { dataset: Record<string, string> };
  visibilityState: string;
  startViewTransition?: (update: () => void) => unknown;
};
beforeEach(() => {
  page = { documentElement: { dataset: {} }, visibilityState: "visible" };
  vi.stubGlobal("document", page);
  vi.stubGlobal("matchMedia", () => ({ matches: false }));
});
afterEach(() => {
  stopCoverTransition();
  vi.unstubAllGlobals();
});

describe("cover navigation", () => {
  it("navigates once when transitions are unsupported", () => {
    const action = vi.fn();
    transitionRoute(action);
    expect(action).toHaveBeenCalledTimes(1);
    expect(coverTransitionActive()).toBe(false);
  });
  it("honors reduced motion and background tabs", () => {
    const start = vi.fn();
    page.startViewTransition = start;
    const action = vi.fn();
    page.documentElement.dataset.motion = "REDUCED";
    transitionRoute(action);
    delete page.documentElement.dataset.motion;
    page.visibilityState = "hidden";
    transitionRoute(action);
    expect(action).toHaveBeenCalledTimes(2);
    expect(start).not.toHaveBeenCalled();
  });
  it("does not navigate twice if a transition fails after committing", () => {
    page.startViewTransition = (update) => {
      update();
      throw new Error("capture unavailable");
    };
    const action = vi.fn();
    transitionRoute(action);
    expect(action).toHaveBeenCalledTimes(1);
    expect(coverTransitionActive()).toBe(false);
    page.startViewTransition = () => {
      throw new Error("unsupported");
    };
    transitionRoute(action);
    expect(action).toHaveBeenCalledTimes(2);
  });
  it("supersedes an old transition without its cleanup clearing the new transition", async () => {
    let firstDone!: () => void, secondDone!: () => void;
    const skip = vi.fn();
    page.startViewTransition = (update) => {
      update();
      return {
        skipTransition: skip,
        finished: new Promise<void>((resolve) => {
          firstDone = resolve;
        }),
      };
    };
    const action = vi.fn();
    transitionRoute(action);
    page.startViewTransition = (update) => {
      update();
      return {
        skipTransition: vi.fn(),
        finished: new Promise<void>((resolve) => {
          secondDone = resolve;
        }),
      };
    };
    transitionRoute(action);
    expect(skip).toHaveBeenCalledTimes(1);
    firstDone();
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(coverTransitionActive()).toBe(true);
    secondDone();
    await vi.waitFor(() => expect(coverTransitionActive()).toBe(false));
    expect(action).toHaveBeenCalledTimes(2);
  });
  it("keeps distinct work identifiers distinct and valid as CSS transition names", () => {
    expect(coverName("作品 / 一")).toMatch(/^work-[a-f0-9-]+$/);
    expect(coverName("a b")).not.toBe(coverName("a_b"));
  });
});
