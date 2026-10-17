"use client";

/**
 * A TOOL WHOSE SCRIPT ARRIVES WHEN IT IS FIRST SHOWN — AND WHICH CANNOT TAKE THE STAGE FORM DOWN WITH IT.
 *
 * The stage form loads the signature pad, the card reader, the photo and sketch tools, the code
 * scanner, the four inline record forms and the document preview on demand, so the script every stage
 * waits for stays small. Splitting the script moved a failure mode with it: a chunk this browser has
 * never fetched is not in the service worker's cache, so a designer who opened the stage with a signal
 * and then pressed "sign" in a courtyard without one met a failed import. Through `next/dynamic` that
 * failure was a render error, and the nearest boundary was the page's — the whole form was replaced by
 * the error screen, with typing still inside the autosave debounce. The same happens online in a tab
 * left open across a deploy, when the old chunk's hash no longer exists.
 *
 * Two things answer it, and both live here so no call site can have one without the other:
 *
 * 1. **A local boundary per tool.** A failed load draws one plain sentence and a "Try again" button
 *    in the tool's own place; the rest of the form keeps working and keeps what was typed. "Try
 *    again" builds a fresh lazy component, so the import is really re-attempted rather than the
 *    remembered failure being replayed.
 * 2. **Warming.** {@link warmDeferredTools} fetches every deferred script once the form is up, one at
 *    a time, only while the browser is online and the designer has paused, so the service worker
 *    holds them before the designer walks out of coverage. A tool used for the first time offline is
 *    then served from the cache like the rest of the form.
 */

import {
  Component,
  Suspense,
  lazy,
  useCallback,
  useState,
  type ComponentType,
  type ErrorInfo,
  type LazyExoticComponent,
  type ReactNode
} from "react";

/** What a tool shows when its script could not be fetched. Plain words: what happened, what to do. */
export const TOOL_LOAD_FAILED =
  "This could not be opened. It needs a connection the first time it is used on this device.";

type FailedRender<P> = (props: P, retry: () => void) => ReactNode;

class ToolBoundary extends Component<{ fallback: (retry: () => void) => ReactNode; onRetry: () => void; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    // Kept in the console for whoever debugs next; the designer sees the sentence instead.
    console.warn("[deferredTool] a tool failed to load", error, info.componentStack);
  }

  render() {
    if (this.state.failed) return this.props.fallback(this.props.onRetry);
    return this.props.children;
  }
}

/** The default failure panel: one sentence and a way to try again, holding the tool's place. */
export function ToolLoadFailed({ retry }: { retry: () => void }) {
  return (
    <div role="alert" className="flex min-h-10 flex-wrap items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
      <span className="min-w-0 flex-1">{TOOL_LOAD_FAILED}</span>
      <button type="button" className="field-button-secondary min-h-8 px-3 py-1 text-xs" onClick={retry}>
        Try again
      </button>
    </div>
  );
}

export type DeferredTool<P> = ComponentType<P> & { preload: () => Promise<void> };

/**
 * Wrap a dynamically imported component: `placeholder` while its script is on the way (it gets the
 * same props, so a placeholder can keep a working close button), `failed` if the script cannot be
 * fetched (defaults to {@link ToolLoadFailed}).
 */
export function deferredTool<P extends object>(
  load: () => Promise<ComponentType<P>>,
  options: { placeholder: (props: P) => ReactNode; failed?: FailedRender<P> }
): DeferredTool<P> {
  const make = (): LazyExoticComponent<ComponentType<P>> => lazy(() => load().then((component) => ({ default: component })));
  let current = make();

  function Deferred(props: P) {
    const [attempt, setAttempt] = useState(0);
    const retry = useCallback(() => {
      current = make();
      setAttempt((value) => value + 1);
    }, []);
    const Loaded = current as unknown as ComponentType<P>;
    return (
      <ToolBoundary
        key={attempt}
        onRetry={retry}
        fallback={(again) => (options.failed ? options.failed(props, again) : <ToolLoadFailed retry={again} />)}
      >
        <Suspense fallback={options.placeholder(props)}>
          <Loaded {...props} />
        </Suspense>
      </ToolBoundary>
    );
  }

  const tool = Deferred as DeferredTool<P>;
  tool.preload = () => load().then(() => undefined, () => undefined);
  return tool;
}

/* ──────────────────────────────────────────────────────────────────────────────────────────────────
   WARMING
   ────────────────────────────────────────────────────────────────────────────────────────────────── */

const warmers: (() => Promise<void>)[] = [];

/**
 * Register a script to warm. Called at module scope by each file that declares a deferred tool, so a
 * new tool is warmed by being declared and nobody has to remember a second list.
 */
export function registerWarm(preload: () => Promise<void>): void {
  if (!warmers.includes(preload)) warmers.push(preload);
}

/** How long the keyboard and pointer must have been still before the next script is fetched. */
export const WARM_QUIET_MS = 1500;

/**
 * Fetch every registered script, one at a time, while the browser is online and the designer is not
 * typing. Returns a cancel function. Safe to call on every stage open: a script already fetched costs
 * nothing the second time, because the module registry answers it.
 */
export function warmDeferredTools(): () => void {
  if (typeof window === "undefined") return () => undefined;
  let cancelled = false;
  let lastActivity = Date.now();
  let handle: number | null = null;
  const queue = [...warmers];
  const note = () => {
    lastActivity = Date.now();
  };
  const idle = (callback: () => void) => {
    const w = window as Window & { requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number };
    if (w.requestIdleCallback) return w.requestIdleCallback(callback, { timeout: 4000 });
    return window.setTimeout(callback, 200);
  };
  const cancelIdle = (id: number) => {
    const w = window as Window & { cancelIdleCallback?: (id: number) => void };
    if (w.cancelIdleCallback) w.cancelIdleCallback(id);
    window.clearTimeout(id);
  };
  const step = () => {
    handle = null;
    if (cancelled || queue.length === 0) return stop();
    if (!navigator.onLine) {
      // Nothing to fetch with; pick up again when the connection returns.
      window.addEventListener("online", resume, { once: true });
      return;
    }
    if (Date.now() - lastActivity < WARM_QUIET_MS) {
      handle = window.setTimeout(() => {
        handle = idle(step);
      }, WARM_QUIET_MS);
      return;
    }
    const next = queue.shift();
    if (!next) return stop();
    void next().finally(() => {
      if (!cancelled) handle = idle(step);
    });
  };
  const resume = () => {
    if (!cancelled && handle === null) handle = idle(step);
  };
  const stop = () => {
    window.removeEventListener("keydown", note, true);
    window.removeEventListener("pointerdown", note, true);
    window.removeEventListener("online", resume);
  };
  window.addEventListener("keydown", note, true);
  window.addEventListener("pointerdown", note, true);
  handle = idle(step);
  return () => {
    cancelled = true;
    if (handle !== null) cancelIdle(handle);
    stop();
  };
}
