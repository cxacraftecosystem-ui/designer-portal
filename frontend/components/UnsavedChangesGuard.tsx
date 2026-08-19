"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef } from "react";

/**
 * Lets the round back control in `PageHeader` ask the form below it whether leaving is safe.
 *
 * The two are siblings, not parent and child: the page renders `<PageHeader />` and then
 * `<ArtisanForm />`, so the header cannot see the form's `dirty` flag and the form cannot reach the
 * header's button. That gap is why every form used to carry its own rounded "Back" pill — the pill
 * lived inside the form, where the flag was, so it was the only control that could raise the
 * "Unsaved changes" prompt. The result was two back controls stacked on the same screen, one of
 * which silently discarded work.
 *
 * A context closes the gap the other way round: the form registers an interceptor, the back control
 * calls it, and the pill is no longer needed for anything.
 */

/**
 * Returns true when it has TAKEN RESPONSIBILITY for the navigation — the form is dirty and has put
 * its own dialog on screen — in which case the caller must not navigate. False means "nothing to
 * save, go ahead".
 */
export type LeaveInterceptor = () => boolean;

type GuardApi = {
  /** Push an interceptor and get back the one call that removes THAT entry. */
  register: (interceptor: LeaveInterceptor) => () => void;
  intercept: () => boolean;
};

const GuardContext = createContext<GuardApi | null>(null);

export function UnsavedChangesProvider({ children }: { children: React.ReactNode }) {
  /*
    A STACK, NOT A SLOT — and it is a ref rather than state because registering must not re-render
    the whole protected subtree.

    IT USED TO BE ONE SLOT, under the reasoning "only one form is ever on screen at a time". That
    was true when it was written and `InlineRecordDialog` made it false: it mounts `ArtisanForm`,
    `ProductForm`, `ToolForm` or `ProcessForm` — all four of which call `useLeaveGuard` — ON TOP of
    whatever page opened it, which may itself be a guarded form. With one slot the inner form
    overwrote the outer one on mount and, worse, its cleanup ran `register(null)` unconditionally on
    unmount: opening the record dialog once and closing it left the page underneath with NO
    interceptor for the rest of its life, so its back arrow silently stopped warning about unsaved
    work. Nothing on screen would say so, and the work is lost the first time somebody presses it.

    Nothing in the tree hits that today — the design-workshop stage page keeps its draft in
    IndexedDB and registers no guard, so no screen that currently hosts a reference picker also
    holds one — which is exactly why it is worth fixing now, while it is cheap and while the reason
    is still legible.

    The TOPMOST registration answers, because the innermost form is the one the reader is typing in
    and it is the one whose "Unsaved changes" dialog is on top of the stack of dialogs. Removal is
    BY IDENTITY rather than by popping, so a teardown order React is free to choose cannot disarm
    the wrong form.
  */
  const interceptors = useRef<LeaveInterceptor[]>([]);

  const register = useCallback((next: LeaveInterceptor) => {
    interceptors.current = [...interceptors.current, next];
    return () => {
      interceptors.current = interceptors.current.filter((entry) => entry !== next);
    };
  }, []);
  const intercept = useCallback(() => {
    const top = interceptors.current[interceptors.current.length - 1];
    return top?.() ?? false;
  }, []);
  const value = useMemo(() => ({ register, intercept }), [register, intercept]);

  return <GuardContext.Provider value={value}>{children}</GuardContext.Provider>;
}

/**
 * For the back control. Call before navigating; if it returns true a form has raised its prompt and
 * the navigation must be abandoned.
 *
 * Returns false when there is no provider, so a back control outside the protected shell — the
 * landing and login pages — still just navigates.
 */
export function useLeaveInterceptor(): () => boolean {
  const ctx = useContext(GuardContext);
  return useCallback(() => ctx?.intercept() ?? false, [ctx]);
}

/**
 * For a form. While `dirty` is true, an attempt to leave via the back control calls `onBlocked`
 * instead of navigating — that is the form's cue to open its `UnsavedChangesDialog`.
 *
 * `dirty` and `onBlocked` are read through a ref so that typing into the form does not re-register
 * on every keystroke; the effect depends only on the context identity, which is stable.
 */
export function useLeaveGuard(dirty: boolean, onBlocked: () => void) {
  const ctx = useContext(GuardContext);
  const latest = useRef({ dirty, onBlocked });

  // Refreshed in an effect rather than assigned during render: a render can be thrown away under
  // concurrent rendering, and a ref written on a discarded render would leave the interceptor
  // reading state that never committed. No dependency array, so it tracks every commit.
  useEffect(() => {
    latest.current = { dirty, onBlocked };
  });

  useEffect(() => {
    if (!ctx) return;
    const unregister = ctx.register(() => {
      if (!latest.current.dirty) return false;
      latest.current.onBlocked();
      return true;
    });
    // Unregistering on unmount is what stops a saved-and-navigated form from blocking the NEXT
    // page's back button with a prompt about work that no longer exists.
    //
    // IT REMOVES THIS FORM'S OWN ENTRY AND NOTHING ELSE. It used to be `register(null)`, which
    // emptied the single slot whoever happened to be in it — so a record form mounted in
    // `InlineRecordDialog` over a guarded page disarmed that page on the way out. See the stack in
    // `UnsavedChangesProvider`.
    return unregister;
  }, [ctx]);
}
