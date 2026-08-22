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
 *
 * AND THE CONTROL HANDS OVER WHAT IT WAS ABOUT TO DO, so the answer that means "leave" can finish it
 * rather than describe it. See {@link PendingLeave} for the defect that came of refusing an act and
 * then dropping it on the floor.
 */

/**
 * WHAT THE CONTROL WAS ABOUT TO DO WHEN A FORM STOPPED IT.
 *
 * ── WHY THE REFUSAL HAS TO CARRY IT ─────────────────────────────────────────────────────────────
 * This guard REFUSES a navigation, it does not delay one: the interceptor returns true and the
 * control abandons what it was doing. While nothing carried the abandoned act anywhere, "Discard"
 * had nothing to finish — the form emptied and the page stayed exactly where it was, so the designer
 * pressed Back, was asked, answered "yes, throw it away, I am leaving", and then had to press Back a
 * second time. The one answer that means "leave" delivered the throwing away and not the leaving.
 *
 * ── AND WHY IT CANNOT BE GUESSED AT THE OTHER END ───────────────────────────────────────────────
 * A `router.back()` invented by the form or by its host would be right for one of the four controls
 * that reach this guard and wrong for the other three. On a stage page they are `BackButton`'s
 * `router.back()` or its explicit `href`; the stage page's own `leave(action)`, whose act is
 * "previous stage" / "next stage" and lands on the WRONG stage if guessed;
 * `CollectionTable.toggleRow`, which is not a navigation at all and would throw a designer off the
 * page entirely — the commonest of the four, since collapsing a row is ordinary browsing; and
 * `StageReferenceField`, where the act is a WRITE onto the row rather than any kind of leaving, and
 * where a card reader is one of the things that can start it. So the act travels from the control
 * that owns it and is never reconstructed.
 */
export type PendingLeave = () => void;

/**
 * Returns true when it has TAKEN RESPONSIBILITY for the navigation — the form is dirty and has put
 * its own dialog on screen — in which case the caller must not navigate. False means "nothing to
 * save, go ahead".
 *
 * It is HANDED the act it is refusing so that the form's own answer can finish it; the provider
 * holds the same act, so a form that ignores the argument (as all four record forms do today) loses
 * nothing — see {@link useLeaveGuard}'s return value for the two calls that finish or forget it.
 */
export type LeaveInterceptor = (pending: PendingLeave) => boolean;

type GuardApi = {
  /** Push an interceptor and get back the one call that removes THAT entry. */
  register: (interceptor: LeaveInterceptor) => () => void;
  /** Ask the stack, innermost first, stopping at the first that blocks. See the provider. */
  intercept: (pending: PendingLeave) => boolean;
  /**
   * "Discard" — answered by the form that blocked, which is why it must identify itself. Runs the
   * held act, after asking every OTHER registered form whether it too has something to say.
   */
  complete: (answeredBy: LeaveInterceptor) => void;
  /** "Keep editing" — answered by the form that blocked. Forgets the act rather than banking it. */
  abandon: (answeredBy: LeaveInterceptor) => void;
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

    THE STAGE PAGE NOW HITS IT, WHICH IT DID NOT WHEN THE PARAGRAPH ABOVE WAS WRITTEN. Its own
    draft is still durable in IndexedDB and it still registers no guard of its own — but four of its
    entities now EMBED a repository record page (`StageRecordEmbed`), so the page hosts one or two of
    those four forms directly, each with its own `useLeaveGuard`. Stage
    TRADITIONAL_PROCESS_BASELINE is the two: `traditionalProcess` is a mirror-point SINGLETON, so its
    `ProcessForm` is mounted from first paint, and `tool` is a mirror-point COLLECTION, so a
    `ToolForm` joins it the moment any tool row is opened.

    ASKED IN ORDER, TOPMOST FIRST, UNTIL ONE TAKES RESPONSIBILITY — and it used to be "the topmost
    answers, full stop". That was right while the stack could only be a page under a dialog, where
    the top entry is the one the reader is typing in. It is wrong for two SIBLING forms on one page:
    a dirty `ProcessForm` plus a freshly opened, clean `ToolForm` row meant the back arrow asked the
    tool form, was told there was nothing to save, and navigated — the half-typed process gone with
    no prompt. Walking down until something blocks keeps the innermost-first ordering that was
    right about dialogs and stops a clean neighbour answering for a dirty one.

    STILL AT MOST ONE DIALOG. The walk STOPS at the first interceptor that returns true, because
    returning true means that form has already put its own "Unsaved changes" dialog on screen;
    asking the rest would stack a second and a third over it. The designer answers one, and if
    another form is also dirty {@link complete} meets that one on the way through rather than
    running the act — so a second dirty form costs a second prompt, which is correct, and no longer
    a second press of the control, which was not.

    Removal is BY IDENTITY rather than by popping, so a teardown order React is free to choose cannot
    disarm the wrong form.
  */
  const interceptors = useRef<LeaveInterceptor[]>([]);
  /*
    THE ACT THAT WAS REFUSED, AND WHO REFUSED IT — see {@link PendingLeave} for why it has to survive.

    ONE SLOT, because there is at most one prompt on screen at a time (the walk above stops at the
    first blocker) and a second attempt to leave supersedes the first: pressing Back, keeping
    editing, and then pressing "next stage" must go to the next stage, not back.

    THE BLOCKER IS RECORDED WITH IT, and that is the whole of what makes finishing it safe. `complete`
    and `abandon` are answers to a PARTICULAR prompt, so they are refused unless the form answering is
    the form that raised it — otherwise a sibling form's Discard, or a save that happened to run
    later, would fire a navigation nobody asked for. The same identity is what drops the act when the
    blocking form unmounts: an exit held for a form that no longer exists can never be answered.
  */
  const held = useRef<{ act: PendingLeave; blockedBy: LeaveInterceptor } | null>(null);

  const register = useCallback((next: LeaveInterceptor) => {
    interceptors.current = [...interceptors.current, next];
    return () => {
      interceptors.current = interceptors.current.filter((entry) => entry !== next);
      if (held.current?.blockedBy === next) held.current = null;
    };
  }, []);
  /**
   * The walk, shared by the first attempt and by {@link complete}'s re-ask.
   *
   * `skip` is the form that has just answered. It is excluded rather than trusted to answer false:
   * "Discard" clears the dirty flag through React state, and this runs inside the very click handler
   * that set it, so the interceptor would still read `dirty === true` and re-open the prompt it has
   * just been dismissed from.
   */
  const ask = useCallback((pending: PendingLeave, skip: LeaveInterceptor | null) => {
    // Copied before the walk: an interceptor may unregister as a side effect of being asked, and
    // mutating the array being iterated would skip its neighbour.
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
      // Another dirty form gets its prompt instead of the exit — which is the same rule as the first
      // attempt, applied to an exit that is now one answer further along.
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

/**
 * For a control that is about to leave. Hand it WHAT you were about to do; if it returns true a form
 * has raised its prompt, the act has been banked, and the caller must not perform it.
 *
 * THE ARGUMENT IS NOT OPTIONAL, deliberately. Every caller has an act — that is what makes it a
 * caller — and an optional one would let the next control refuse an exit that nothing can ever
 * finish, which is precisely the defect this parameter exists to end. See {@link PendingLeave}.
 *
 * Returns false when there is no provider, so a back control outside the protected shell — the
 * landing and login pages — still just performs its act.
 */
export function useLeaveInterceptor(): (pending: PendingLeave) => boolean {
  const ctx = useContext(GuardContext);
  return useCallback((pending: PendingLeave) => ctx?.intercept(pending) ?? false, [ctx]);
}

/**
 * For a form. While `dirty` is true, an attempt to leave via any guarded control calls `onBlocked`
 * instead of performing the act — that is the form's cue to open its `UnsavedChangesDialog`.
 *
 * `onBlocked` IS HANDED THE ACT, and the same act is held by the provider, so a form may finish the
 * exit either way. Every one of today's callers is a bare `() => setBackPromptOpen(true)`, which
 * stays valid — a zero-argument callback satisfies a one-argument type — so nothing had to change to
 * start banking the act.
 *
 * ── WHAT A FORM MUST DO TO ACTUALLY FINISH THE EXIT ─────────────────────────────────────────────
 * Call `completeLeave()` from the answer that means "leave" and `abandonLeave()` from the answer that
 * means "stay". Both are scoped to THIS form's own prompt (see the provider's `held`), so they are
 * no-ops when the prompt on screen belongs to a sibling form and cannot fire a navigation left over
 * from an attempt the designer talked themselves out of ten minutes ago.
 *
 * AND `completeLeave()` GOES IN THE `else` BRANCH, BESIDE `leaveAfterDiscard()` — NEVER BESIDE
 * `resetDirty()`. This is the whole of the care the change needs, so it is spelled out rather than
 * left to be inferred. All four record forms answer ONE `UnsavedChangesDialog` for TWO different
 * questions and tell them apart afterwards, with `promptFromCancel`:
 *
 *     resetDirty();
 *     if (promptFromCancel) leave();     // the form's own Cancel: "empty this form, I am staying"
 *     else leaveAfterDiscard();          // a host control's exit: "take me off this screen"
 *
 * `resetDirty()` sits ABOVE that branch and runs for both. A `completeLeave()` placed there fires on
 * the form's own Cancel too — running a banked `router.back()`, stage push or row collapse the
 * designer never asked for, which is precisely the defect `promptFromCancel` was added to prevent.
 * In the `else` branch it can only ever finish an exit a host control really began. `abandonLeave()`
 * belongs on `onKeepEditing`, beside the `setPromptFromCancel(false)` already there, so a prompt the
 * designer talks themselves out of leaves nothing sitting in `held`.
 *
 * TODAY NOTHING CALLS THEM, and that is a fenced hand-over rather than dead code: the four record
 * forms and `StageRecordEmbed` belonged to another group in the wave that added this. "Discard"
 * therefore still runs `resetDirty()` and then `onDiscardAndLeave`, and
 * `StageRecordEmbed.handleDiscardAndLeave` clears the form and tells the designer the page did not
 * move — which is honest, and one press worse than it needs to be. `StageReferenceField`'s three
 * refusal notices say the same thing for the same reason. Those sentences, and that embed's own
 * header and callback (which still describe a world in which nothing carried the act at all), are
 * corrected by the same change that adds the two calls — `e2e/inline-record-host-unit.spec.ts` has
 * the `fixme` that demands all of it together.
 *
 * `dirty` and `onBlocked` are read through a ref so that typing into the form does not re-register
 * on every keystroke; the effect depends only on the context identity, which is stable.
 */
export function useLeaveGuard(dirty: boolean, onBlocked: (pending: PendingLeave) => void) {
  const ctx = useContext(GuardContext);
  const latest = useRef({ dirty, onBlocked });
  /*
    THIS MOUNT'S OWN ENTRY IN THE STACK, so `completeLeave`/`abandonLeave` can say who is answering.
    The provider compares by identity and nothing else, which is what stops one form answering a
    prompt another form raised. Null between unmount and re-register, and both calls are no-ops then.
  */
  const entry = useRef<LeaveInterceptor | null>(null);

  // Refreshed in an effect rather than assigned during render: a render can be thrown away under
  // concurrent rendering, and a ref written on a discarded render would leave the interceptor
  // reading state that never committed. No dependency array, so it tracks every commit.
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
    // Unregistering on unmount is what stops a saved-and-navigated form from blocking the NEXT
    // page's back button with a prompt about work that no longer exists.
    //
    // IT REMOVES THIS FORM'S OWN ENTRY AND NOTHING ELSE. It used to be `register(null)`, which
    // emptied the single slot whoever happened to be in it — so a record form mounted in
    // `InlineRecordDialog` over a guarded page disarmed that page on the way out. See the stack in
    // `UnsavedChangesProvider`.
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
