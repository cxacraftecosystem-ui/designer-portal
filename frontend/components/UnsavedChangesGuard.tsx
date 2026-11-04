"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef } from "react";

export type PendingLeave = () => void;

export type LeaveInterceptor = (pending: PendingLeave) => boolean;

type GuardApi = {
  register: (interceptor: LeaveInterceptor) => () => void;
  intercept: (pending: PendingLeave) => boolean;
  complete: (answeredBy: LeaveInterceptor) => void;
  abandon: (answeredBy: LeaveInterceptor) => void;
};

const GuardContext = createContext<GuardApi | null>(null);

export function UnsavedChangesProvider({ children }: { children: React.ReactNode }) {
  const interceptors = useRef<LeaveInterceptor[]>([]);
  const held = useRef<{ act: PendingLeave; blockedBy: LeaveInterceptor } | null>(null);

  const register = useCallback((next: LeaveInterceptor) => {
    interceptors.current = [...interceptors.current, next];
    return () => {
      interceptors.current = interceptors.current.filter((entry) => entry !== next);
      if (held.current?.blockedBy === next) held.current = null;
    };
  }, []);
  const ask = useCallback((pending: PendingLeave, skip: LeaveInterceptor | null) => {
    const stack = [...interceptors.current];
    for (let index = stack.length - 1; index >= 0; index -= 1) {
      const entry = stack[index];
      if (entry === skip) continue;
      if (entry(pending)) {
        held.current = { act: pending, blockedBy: entry };
        return true;
      }
    }
    held.current = null;
    return false;
  }, []);
  const intercept = useCallback((pending: PendingLeave) => ask(pending, null), [ask]);
  const complete = useCallback(
    (answeredBy: LeaveInterceptor) => {
      const pending = held.current;
      if (!pending || pending.blockedBy !== answeredBy) return;
      held.current = null;
      if (!ask(pending.act, answeredBy)) pending.act();
    },
    [ask]
  );
  const abandon = useCallback((answeredBy: LeaveInterceptor) => {
    if (held.current?.blockedBy === answeredBy) held.current = null;
  }, []);
  const value = useMemo(
    () => ({ register, intercept, complete, abandon }),
    [register, intercept, complete, abandon]
  );

  return <GuardContext.Provider value={value}>{children}</GuardContext.Provider>;
}

export function useLeaveInterceptor(): (pending: PendingLeave) => boolean {
  const ctx = useContext(GuardContext);
  return useCallback((pending: PendingLeave) => ctx?.intercept(pending) ?? false, [ctx]);
}

export function useLeaveGuard(dirty: boolean, onBlocked: (pending: PendingLeave) => void) {
  const ctx = useContext(GuardContext);
  const latest = useRef({ dirty, onBlocked });
  const entry = useRef<LeaveInterceptor | null>(null);

  useEffect(() => {
    latest.current = { dirty, onBlocked };
  });

  useEffect(() => {
    if (!ctx) return;
    const interceptor: LeaveInterceptor = (pending) => {
      if (!latest.current.dirty) return false;
      latest.current.onBlocked(pending);
      return true;
    };
    entry.current = interceptor;
    const unregister = ctx.register(interceptor);
    return () => {
      entry.current = null;
      unregister();
    };
  }, [ctx]);

  const completeLeave = useCallback(() => {
    if (entry.current) ctx?.complete(entry.current);
  }, [ctx]);
  const abandonLeave = useCallback(() => {
    if (entry.current) ctx?.abandon(entry.current);
  }, [ctx]);

  return { completeLeave, abandonLeave };
}
