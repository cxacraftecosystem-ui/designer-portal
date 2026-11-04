"use client";

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

export const TOOL_LOAD_FAILED =
  "This could not be opened. It needs a connection the first time it is used on this device.";

type FailedRender<P> = (props: P, retry: () => void) => ReactNode;

class ToolBoundary extends Component<{ fallback: (retry: () => void) => ReactNode; onRetry: () => void; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.warn("[deferredTool] a tool failed to load", error, info.componentStack);
  }

  render() {
    if (this.state.failed) return this.props.fallback(this.props.onRetry);
    return this.props.children;
  }
}

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

const warmers: (() => Promise<void>)[] = [];

export function registerWarm(preload: () => Promise<void>): void {
  if (!warmers.includes(preload)) warmers.push(preload);
}

export const WARM_QUIET_MS = 1500;

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
