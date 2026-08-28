"use client";

/**
 * Reorder a list by dragging — the pointer mechanics, with no opinion about what the rows look like.
 *
 * ── WHY THIS IS A HOOK AND NOT A SECOND COPY OF `RankableList` ───────────────────────────────────
 *
 * `components/sketches/RankableList.tsx` had all of this inline and was the only drag-and-drop in
 * the repository. On 2026-08-25 the owner asked for the same gesture on the custom-sections editor
 * — *"adding new sections or sub sections should be facilitated with a plus button, up down arrows
 * and drag and drop as well"* — and that screen cannot use `RankableList`: it draws a whole `panel`
 * per section with a header, a stage picker, a description box and a nested list of question rows,
 * whereas `RankableList` owns its own `<ol>`, its own numbering column and its own three buttons.
 *
 * So the MECHANICS moved here and the RENDERING stayed where it was. `RankableList` is now a
 * renderer over this hook, and the sections editor is a second one. The alternative — a private
 * re-implementation on the second screen — is what this repository refuses by name elsewhere
 * (`readableError`: "two private re-implementations already exist and a third must not"), and it
 * would be worse here than usual, because every subtlety below is a bug that has already been paid
 * for once.
 *
 * ── WHY POINTER EVENTS AND NO LIBRARY ───────────────────────────────────────────────────────────
 *
 * There is no dnd dependency in `package.json` and this adds none. What is needed is a list of a
 * dozen cards reordered by one finger: `setPointerCapture`, one rectangle snapshot and an index
 * calculation cover it, where the smallest of the usual libraries is tens of kilobytes on pages
 * field designers load over a village connection.
 *
 * Pointer events rather than the HTML5 drag API, which is the decision that matters most:
 * `dragstart`/`dragover` DO NOT FIRE FOR TOUCH AT ALL on Android Chrome. A gesture that works on a
 * laptop and silently does nothing on the phone the fieldwork is done on is worse than no gesture,
 * and worse still on this feature — the handset is where a designer is most likely to be arranging
 * things one-handed.
 *
 * ── THE FIVE THINGS THAT MAKE THE DRAG HONEST ───────────────────────────────────────────────────
 *
 * 1. **The rectangles are snapshotted once, at pointerdown.** The dragged row is translated and its
 *    neighbours are shifted by CSS, so nothing in the layout actually moves while a drag is in
 *    flight. Re-measuring during the gesture would feed the shift back into the measurement and the
 *    target index would oscillate under the finger.
 *
 * 2. **The ARRANGEMENT is snapshotted with them, and a gesture whose ground moved is ABANDONED.**
 *    `from`, `to` and every rectangle are indices into the order as it stood at pointerdown. If the
 *    list changed mid-gesture — a refresh, a colleague's row arriving on a sync, a section deleted
 *    in another tab — those indices address different rows than the ones the finger was over, and
 *    committing them moves the wrong thing and then stamps the result as a deliberate arrangement.
 *    Re-deriving `from` by id and keeping `to` is the tempting repair and is the same guess in a
 *    smaller coat: it keeps half a stale measurement.
 *
 * 3. **Nothing is committed until the pointer is released, and Escape cancels outright.** A reorder
 *    is a write with a person's name on it, so a stray swipe across a card must not be able to
 *    stamp an arrangement.
 *
 * 4. **Every move is announced in words**, through a polite live region the consumer renders. A rank
 *    that exists only as a place in a visual list is a rank a screen-reader user cannot read back.
 *
 * 5. **The drag cannot outlive the list, and the cross-window listeners are released with it.**
 *    `drag` is this hook's own `useState`, so a route change mid-gesture discards it with the
 *    consumer and the next mount starts from `null` — there is no path by which a lifted row
 *    nobody is touching survives to be drawn, and React's handlers leave with the node they were
 *    on. What DOES need a cleanup is anything SCHEDULED rather than remembered: the `keydown`
 *    listener rule 3's Escape half puts on `window`, and the animation frame the auto-scroll below
 *    books. Both effects add theirs only while a gesture is in flight and drop them on release and
 *    on unmount alike.
 *
 *    THIS RULE USED TO READ "the drag is torn down on unmount as well as on release", naming a
 *    teardown of captured-pointer listeners that no line in this file performs. It was corrected
 *    on 2026-08-26 alongside the identical claim in its Android twin (`DwRankableList.kt`'s rule
 *    5, where a `DisposableEffect` was found to be writing to state discarded in the same
 *    breath). Do not make the old sentence true by adding a `releasePointerCapture` or an
 *    unmount `setDrag(null)`: the browser releases capture when the node goes, and state dying
 *    with the component is the mechanism, not a gap in one. A `cancelAnimationFrame` is a
 *    different animal and belongs where it is — a booked frame is not state, nothing discards it
 *    when the component goes, and the callback it holds would scroll the page the designer has
 *    navigated ON to.
 *
 * ── EDGE AUTO-SCROLL, AND THE RECONCILIATION THAT MAKES IT LEGAL ────────────────────────────────
 *
 * Added 2026-08-27. Until then the gesture could only reach a destination ALREADY ON SCREEN, and on
 * a 360×640 handset — where one review card or one custom-section panel is most of the viewport —
 * that is exactly one position, which is what the arrow button beside the grip already does with a
 * bigger target. The grip carries `touch-action: none` (that is what stops the browser claiming the
 * movement as a page scroll before the first `pointermove` arrives), so the page cannot scroll
 * itself out from under the drag either. The gesture therefore has to do the scrolling.
 *
 * **THE HARD PART IS RULE 1, AND THE ANSWER IS ONE TERM.** Every rectangle in `boxes` is in
 * VIEWPORT coordinates as they stood at pointerdown, and a scroll moves every row on screen. The
 * instinct is that the snapshot is now stale and must be re-measured — which is precisely what rule
 * 1 forbids, and re-measuring here would be worse than usual because the auto-scroll is itself
 * driven by the target index, so the oscillation would have a motor attached.
 *
 * It does not need re-measuring, because **a scroll translates EVERY row by the SAME amount, and
 * the index calculation only ever compares rows with each other.** `dragTargetIndex` asks whether
 * the dragged row's centre has passed another row's centre; adding a constant to both sides of that
 * comparison changes nothing. The only thing a scroll genuinely changes is where the FINGER is
 * relative to the list — and that is one number. So the snapshot stays exactly as taken, and the
 * gesture's travel becomes:
 *
 *     travel = (how far the finger has moved) + (how far the scroller has moved)
 *
 * both measured against values captured at pointerdown (`startY`, `Scroller.start`). `offset` keeps
 * its old meaning — finger travel alone — and `scrolled` is the second term, named so a reader can
 * see there are two. The dragged card's own `translateY` is their SUM, which is what pins it under
 * the finger: its layout position has moved up by the scroll, and the extra term puts it back.
 *
 * Nothing about this reads a row's geometry. `scrollTop` is the scroller's own property and is not
 * affected by the CSS transforms this hook hands out, so there is no path from the preview back
 * into the measurement — which is the whole of what rule 1 is protecting.
 *
 * **THE EXTENT IS SNAPSHOTTED TOO, AND THAT ONE IS A RUNAWAY GUARD RATHER THAN TIDINESS.** A CSS
 * transform contributes to its scroll container's scrollable overflow region, so a row translated
 * 400px down genuinely makes the page 400px taller while the finger is holding it there. Without a
 * bound, auto-scrolling toward the bottom would scroll into space the drag itself had just created,
 * which grows `scrolled`, which pushes the card further down, which extends the page again. The
 * bound is the extent as it stood at pointerdown, and it is the RIGHT bound rather than a safe one:
 * `boxes` is fixed at pointerdown, so the destinations this gesture can commit to are exactly the
 * rows that existed then, and the scroll range that reveals all of them is exactly the range that
 * existed then.
 *
 * ── MOTION ──────────────────────────────────────────────────────────────────────────────────────
 *
 * The neighbours' shift is a CSS `transition-transform` applied by the consumer, which the global
 * reduced-motion rules in `globals.css` zero for BOTH sources (the OS preference and the in-app
 * toggle). There is deliberately no framer-motion here: it writes inline styles CSS cannot reach, so
 * this file would then need its own JS branch for a 120ms slide — and the transform this hook
 * returns would be fighting framer for the same property, which §17 of the frontend contract names
 * as its own trap.
 *
 * THE AUTO-SCROLL IS THE EXCEPTION, because it is a scroll driven from JavaScript and CSS cannot
 * reach it at all — so it takes the JS branch the contract requires, through `useAppReducedMotion()`
 * (the OR of the OS query and the in-app toggle; framer's own `useReducedMotion()` sees only the
 * first). **What the preference changes is HOW the list travels and never WHETHER the destination is
 * reachable**: switching auto-scroll off under reduced motion would put the drag back to one
 * position for exactly the readers who asked for less movement, which is a capability taken away by
 * a motion setting. So under reduced motion the list JUMPS a fixed step at a time with a dwell
 * between jumps, instead of gliding at a speed proportional to how deep in the edge zone the finger
 * is. An instant change is the substitute a continuous animation is supposed to degrade to, and the
 * average speed is deliberately in the same range, so nobody is made slower for the preference.
 *
 * The preference is read through a REF and never a dependency, the treatment `useEditDeepLink`
 * argues for at length: `useAppReducedMotion()` reads false on the server and on the first client
 * render by design and flips a tick later once `ThemeProvider` has read storage, so as a dependency
 * it would tear the animation frame down MID-GESTURE for every account that has the toggle on.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/** Move the item at `from` to `to`, closing the gap behind it. Pure. */
export function moveIndex<T>(items: readonly T[], from: number, to: number): T[] {
  const next = [...items];
  if (from < 0 || from >= next.length || to < 0 || to >= next.length) return next;
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

/** One row's box in the pointerdown snapshot. Viewport coordinates, as `getBoundingClientRect` gives them. */
export type DragBox = { top: number; height: number };

/**
 * Which index the dragged row has reached, given how far it has travelled from where it started.
 *
 * PURE, AND EXPORTED SO THE GEOMETRY CAN BE TESTED WITHOUT A POINTER. Nothing in this repository can
 * compose a React tree — there is no renderer in `devDependencies` — so a judgement left inside an
 * event handler is only ever exercised by somebody looking at a screen. This is the same split
 * `components/ui/selectFilter.ts` and `components/data/cappedList.ts` make for the same reason.
 *
 * `travel` is the SUM of the finger's movement and the scroller's, and the two are interchangeable
 * here on purpose — see the reconciliation note in the file header. Every comparison is between two
 * rows of the same snapshot, so a scroll (which moves both by the same amount) cannot change the
 * answer; only the finger's relation to the list can, and `travel` is that relation.
 *
 * Comparing CENTRES rather than edges is what stops the target flipping back and forth while a tall
 * card is halfway past a short one. `Math.min`/`Math.max` rather than a plain assignment so that the
 * furthest row the centre has passed wins, whatever order the rows are visited in.
 */
export function dragTargetIndex(boxes: readonly DragBox[], from: number, travel: number): number {
  const box = boxes[from];
  if (!box) return from;
  const centre = box.top + box.height / 2 + travel;
  let to = from;
  boxes.forEach((other, index) => {
    if (index === from) return;
    const otherCentre = other.top + other.height / 2;
    if (index < from && centre < otherCentre) to = Math.min(to, index);
    if (index > from && centre > otherCentre) to = Math.max(to, index);
  });
  return to;
}

/**
 * How deep into the scroller's top or bottom strip the pointer has to be before the list starts
 * travelling, in CSS pixels.
 *
 * Deep enough that a thumb held at the bottom of a 640px handset viewport is comfortably inside it,
 * shallow enough that the middle of even a short scroller is neutral: two 72px strips leave 496px of
 * a 640px viewport where nothing moves at all. Halved when the scroller is shorter than 144px, so
 * the two strips can never overlap into a scroller with no neutral middle — a control that scrolled
 * wherever you put the finger would be unaimable.
 */
const EDGE_ZONE = 72;

/** Pixels per second at the very edge of the zone, tapering to nothing at its inner lip. */
const MAX_SPEED = 900;

/**
 * The reduced-motion pair: one jump of this many pixels, no more often than this many milliseconds.
 *
 * ~380px/s sustained, which sits inside the range the continuous branch covers, so the preference
 * changes the character of the travel and not how long a designer waits to reach the bottom.
 */
const REDUCED_STEP = 96;
const REDUCED_DWELL_MS = 250;

/**
 * How far the finger must travel before auto-scroll arms, in CSS pixels.
 *
 * A PRESS IS NOT A DRAG. Without this, pressing a grip that happens to sit within `EDGE_ZONE` of the
 * viewport's bottom edge — which on a phone is where the last row's grip always sits — would start
 * the page moving before the designer had asked for anything, and let go of it somewhere else.
 */
const ARM_TRAVEL = 8;

/**
 * How far the scroller should move this frame, signed: negative up, positive down, zero at rest.
 *
 * PURE, for the reason `dragTargetIndex` gives. Five behaviours worth pinning live in here and none
 * of them is visible from a screenshot: the neutral middle, the taper, which edge wins, the
 * saturation once the pointer is past the edge entirely (a pointer dragged off the top of the window
 * scrolls at full speed rather than falling out of the zone and stopping), and the clamp.
 *
 * `elapsedMs` is the time since the last frame on the continuous branch and the time since the last
 * JUMP on the reduced-motion one, which is why the caller resets its accumulator only when this
 * returns something non-zero.
 *
 * THE CLAMP CAN SHORTEN A STEP TO NOTHING AND MUST NEVER TURN ONE AROUND. It is applied in the
 * direction of travel only: a scroller that has somehow ended up past the extent snapshotted at
 * pointerdown (browser scroll anchoring, another script) must be left where it is rather than
 * dragged back, because a correction nobody asked for during a gesture reads as the list fighting
 * the finger.
 */
export function edgeScrollDelta({
  pointer,
  top,
  bottom,
  scroll,
  max,
  elapsedMs,
  reduce
}: {
  /** The pointer's position, in the same client coordinates as `top` and `bottom`. */
  pointer: number;
  /** The scroller's visible edges, in client coordinates. */
  top: number;
  bottom: number;
  /** Where the scroller is now, and the furthest it was allowed to travel when the drag began. */
  scroll: number;
  max: number;
  elapsedMs: number;
  reduce: boolean;
}): number {
  const zone = Math.min(EDGE_ZONE, (bottom - top) / 2);
  if (!(zone > 0)) return 0;
  // How far past the zone's inner lip the pointer is, as a fraction of the zone. Negative in the
  // neutral middle; capped at 1 so a pointer dragged clean off the edge does not accelerate away.
  const above = (top + zone - pointer) / zone;
  const below = (pointer - (bottom - zone)) / zone;
  const depth = Math.min(1, Math.max(above, below));
  if (depth <= 0) return 0;
  const direction = above >= below ? -1 : 1;
  const raw = reduce
    ? elapsedMs >= REDUCED_DWELL_MS
      ? direction * REDUCED_STEP
      : 0
    : direction * depth * MAX_SPEED * (elapsedMs / 1000);
  if (raw > 0) return Math.max(0, Math.min(max - scroll, raw));
  if (raw < 0) {
    const bounded = Math.min(0, Math.max(-scroll, raw));
    // NORMALISED, BECAUSE `Math.max(-0, …)` PRODUCES NEGATIVE ZERO. `-0 === 0` is true, so the
    // caller's at-rest check is unaffected either way — but `Object.is(-0, 0)` is false, which is
    // what `expect(…).toBe(0)` uses, and a clamp that correctly reduced an upward step to nothing
    // would read as a failure in the one place that can check it.
    return bounded === 0 ? 0 : bounded;
  }
  return 0;
}

/**
 * The nearest ancestor that actually scrolls, or the document.
 *
 * MEASURED RATHER THAN CONFIGURED, because one hook serves three renderers with three different
 * page shapes and a fourth will not tell us. `overflow-y` alone is not the test: a `overflow-y:auto`
 * container whose content fits scrolls nowhere, and stopping there would leave the page — the thing
 * that CAN move — undriven. Only the nearest one is driven, which is the same choice
 * `useRevealRow`'s container branch makes and for the same reason: scrolling every scrollable
 * ancestor drags the page out from under a pane somebody deliberately pinned.
 */
function scrollerFor(node: HTMLElement | null): HTMLElement {
  for (let element = node?.parentElement ?? null; element; element = element.parentElement) {
    const overflowY = getComputedStyle(element).overflowY;
    const scrolls = overflowY === "auto" || overflowY === "scroll" || overflowY === "overlay";
    if (scrolls && element.scrollHeight > element.clientHeight) return element;
  }
  return (document.scrollingElement ?? document.documentElement) as HTMLElement;
}

/**
 * The scroller this gesture is driving, snapshotted at pointerdown beside the rectangles.
 *
 * A REF AND NOT PART OF `DragState`, because none of it is drawn: putting a DOM node and a live
 * pointer position into the state that every `pointermove` copies would re-render three consumers
 * for values no consumer reads. The state carries `scrolled` — the one number the preview needs —
 * and this carries the machinery that produces it.
 */
type Scroller = {
  node: HTMLElement;
  /** Its visible edges in client coordinates, and its scroll position and extent at pointerdown. */
  top: number;
  bottom: number;
  start: number;
  max: number;
  /** Where the finger went down, and where it is now — both `clientY`. */
  startY: number;
  pointer: number;
  /** False until the finger has travelled `ARM_TRAVEL`; see that constant. */
  armed: boolean;
};

type DragState = {
  key: string;
  pointerId: number;
  from: number;
  to: number;
  startY: number;
  offset: number;
  /** How far the scroller has travelled since pointerdown. The second half of `travel`; see the header. */
  scrolled: number;
  /** Snapshot of every row's box at pointerdown; see rule 1. */
  boxes: DragBox[];
  /** The arrangement at pointerdown, so the release can tell whether it still holds. See rule 2. */
  snapshot: readonly string[];
};

export type DragReorder = {
  /** Attach to each row's element so the hook can measure it. */
  registerRow: (key: string) => (node: HTMLElement | null) => void;
  /** Spread onto the row's grip button. */
  handleProps: (key: string) => {
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => void;
    onPointerMove: (event: React.PointerEvent<HTMLElement>) => void;
    onPointerUp: (event: React.PointerEvent<HTMLElement>) => void;
    onPointerCancel: () => void;
  };
  /** Pixels this row is currently pushed by. 0 when no drag is in flight. */
  shiftFor: (key: string) => number;
  /** The key of the row under the finger, or null. */
  draggingKey: string | null;
  /**
   * The last thing that happened, in words. The CONSUMER renders this inside an
   * `aria-live="polite"` region that is present from first paint — assistive technology only
   * announces mutations inside a region that already existed, the rule `Toast`'s always-mounted
   * viewport follows.
   */
  announcement: string;
  /**
   * Announce a move the CONSUMER performed (an arrow button), so both paths speak alike.
   *
   * `index` is an index into `order`. The drop path calls this itself and a caller with its own
   * controls calls it too, so both produce one sentence from one place.
   */
  announceMove: (key: string, index: number) => void;
};

export function useDragReorder({
  /** The current arrangement, as stable row keys. The caller owns it; this hook never stores it. */
  order,
  /** A human name for one key — used by every announcement. */
  labelFor,
  /**
   * Called with `from`/`to` when a drag is released on a new position.
   *
   * RETURN `false` TO REFUSE THE MOVE. Anything else (including `undefined`, which is what a
   * void-returning handler gives) means it was performed.
   *
   * ── WHY A RETURN VALUE, AND THE DEFECT THAT FORCED IT ───────────────────────────────────────────
   *
   * The drop path used to announce unconditionally the moment `onReorder` returned, and a handler had
   * no way to say it had declined. `CustomSectionsEditor` declines a cross-section drop — a question
   * cannot move between sections, because the section decides the stage it is asked at and the key
   * namespace its answers live in — and it showed an amber banner saying so. Meanwhile the polite
   * live region said the move HAD happened, with numbers that could not both be real: dragging a
   * question out of a two-question section onto the fourth row of a five-question one announced
   * "moved to position 4 of 2", the position taken from one section and the total from another.
   *
   * A screen-reader user was therefore told a write had occurred, in impossible terms, while the
   * screen said it had been refused. That is worse than silence, and it is worse than the banner
   * alone: the two channels contradicted each other and the accessible one was the one that lied.
   */
  onReorder,
  /** True when the list must not be rearranged (a save in flight, no entitlement). */
  locked = false,
  /**
   * How a move is WORDED, for a caller whose list is not what `order.length` counts.
   *
   * The default sentence is the label, the landing position, and `order.length` as the total. That is
   * exactly right when `order` IS the list the reader is looking at — true for `RankableList`, and
   * true for the SECTION level of the custom-sections editor.
   *
   * It is WRONG when ONE hook spans SEVERAL visible lists. The custom-sections editor drives every
   * question in the workshop from a single hook, because the Rules of Hooks forbid one per section
   * (the sections are rendered inside a map whose length changes), and its ids encode the section
   * they belong to. So the total there is 37 questions across five sections, and a question moved to
   * the fourth slot of a five-question section announced "position 4 of 37" — a position quoted
   * against a total the reader cannot see, which describes nothing. The drag path had that defect
   * from the day it shipped; the arrows, being silent, did not have it at all.
   *
   * ── WHY AN OVERRIDE AND NOT AN "ANNOUNCE IT YOURSELF" ESCAPE HATCH ──────────────────────────────
   *
   * The first attempt exposed a raw `announce(sentence)` plus a flag to suppress the hook's own
   * announcement, leaving the caller to compose its sentence inside `onReorder`. Two problems, one of
   * them fatal. The hook's announcement fires AFTER `onReorder` returns, so that flag was
   * load-bearing rather than optional and forgetting it silently overwrote the caller's words. And
   * the caller's helper needed the hook's own return value to speak — a reference cycle that happens
   * to work at call time and that `react-hooks/exhaustive-deps` was right to complain about.
   *
   * A pure `(key, index) => string` has neither problem. The HOOK calls it, from the one place that
   * announces, so there is exactly one channel and exactly one sentence source and nothing has to
   * remember to suppress anything.
   */
  describeMove
}: {
  order: readonly string[];
  labelFor: (key: string) => string;
  /** `false` refuses the move; see the parameter's own note. */
  onReorder: (from: number, to: number) => void | boolean;
  locked?: boolean;
  describeMove?: (key: string, index: number) => string;
}): DragReorder {
  const rowRefs = useRef(new Map<string, HTMLElement>());
  const scrollerRef = useRef<Scroller | null>(null);
  const [drag, setDrag] = useState<DragState | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const total = order.length;

  /*
    Reduced motion, read through a ref and never as a dependency — the header's last paragraph and
    §17 of the frontend contract both say why. Written in an effect with no dependency array rather
    than during render, the discipline `useEditDeepLink` and `useLeaveGuard` follow: a render can be
    discarded under concurrent rendering, and a ref written on a discarded render would leave the
    animation frame reading a preference that never committed.
  */
  const reduceMotion = useAppReducedMotion();
  const reduceRef = useRef(reduceMotion);
  useEffect(() => {
    reduceRef.current = reduceMotion;
  });

  const announceMove = useCallback(
    (key: string, index: number) => {
      setAnnouncement(
        describeMove
          ? describeMove(key, index)
          : `${labelFor(key)} moved to position ${index + 1} of ${total}.`
      );
    },
    [describeMove, labelFor, total]
  );

  // Rule 3's Escape half — one of the two listeners this hook puts on `window`; see rule 5.
  useEffect(() => {
    if (!drag) return;
    function cancelOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setDrag(null);
    }
    window.addEventListener("keydown", cancelOnEscape);
    return () => window.removeEventListener("keydown", cancelOnEscape);
  }, [drag]);

  /**
   * The auto-scroll clock.
   *
   * KEYED ON WHETHER A DRAG EXISTS AND NOT ON THE DRAG, which is the whole reason this is a separate
   * effect from the Escape one above. `drag` is a new object on every `pointermove`, so an effect
   * depending on it is torn down and rebuilt dozens of times a second — harmless for adding a
   * listener, fatal for a frame loop, which would be cancelled and re-booked before it ever ran and
   * would lose its elapsed-time accumulator every time.
   *
   * IT RUNS EVERY FRAME AND WRITES NOTHING WHEN THERE IS NOTHING TO DO. `edgeScrollDelta` returns 0
   * for a pointer in the neutral middle, so the common case — a drag between two visible rows — costs
   * one arithmetic pass per frame and no re-render at all. It has to be a clock rather than a
   * `pointermove` handler because the case that matters is a finger held STILL at the edge: no
   * pointer event fires, and the list still has to keep coming.
   */
  const dragging = drag !== null;
  useEffect(() => {
    if (!dragging) return;
    let frame = 0;
    let previous = performance.now();
    // Time banked toward the next jump on the reduced-motion branch. Reset only when a jump is
    // actually taken, so a dwell served while the pointer was in the neutral middle is not lost.
    let sinceStep = 0;
    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      const scroller = scrollerRef.current;
      if (!scroller || !scroller.armed) return;
      const elapsed = now - previous;
      previous = now;
      sinceStep += elapsed;
      const reduce = reduceRef.current;
      const delta = edgeScrollDelta({
        pointer: scroller.pointer,
        top: scroller.top,
        bottom: scroller.bottom,
        scroll: scroller.node.scrollTop,
        max: scroller.max,
        elapsedMs: reduce ? sinceStep : elapsed,
        reduce
      });
      if (delta === 0) return;
      sinceStep = 0;
      scroller.node.scrollTop += delta;
      // Read BACK rather than assumed: the delta asked for is not always the delta granted (the end
      // of the extent, a sub-pixel device ratio), and `scrolled` has to be what actually happened or
      // the card stops sitting under the finger.
      const scrolled = scroller.node.scrollTop - scroller.start;
      setDrag((current) => {
        if (!current) return current;
        const box = current.boxes[current.from];
        if (!box) return current;
        const to = dragTargetIndex(current.boxes, current.from, current.offset + scrolled);
        if (scrolled === current.scrolled && to === current.to) return current;
        return { ...current, scrolled, to };
      });
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [dragging]);

  const registerRow = useCallback(
    (key: string) => (node: HTMLElement | null) => {
      if (node) rowRefs.current.set(key, node);
      else rowRefs.current.delete(key);
    },
    []
  );

  const handleProps = useCallback(
    (key: string) => ({
      onPointerDown(event: React.PointerEvent<HTMLElement>) {
        if (locked || event.button !== 0) return;
        const from = order.indexOf(key);
        if (from < 0) return;
        const boxes = order.map((rowKey) => {
          const box = rowRefs.current.get(rowKey)?.getBoundingClientRect();
          return { top: box?.top ?? 0, height: box?.height ?? 0 };
        });
        /*
          THE SCROLLER IS SNAPSHOTTED WITH THE RECTANGLES, and for the same reason they are: it is
          the frame of reference the whole gesture is expressed in. Its visible edges are taken once
          because a viewport that changed size mid-gesture would move the edge zones under a finger
          that had not moved, and its extent is taken once because the transform this hook is about
          to hand out will grow it — the runaway guard argued in the header.

          The document scroller is the fallback and its edges are the window's, not its own box: the
          scrolling element's rectangle is the height of the whole document, which as an "edge zone"
          would mean the strip 72px above the end of the page rather than 72px above the fold.
        */
        const node = rowRefs.current.get(key) ?? null;
        const scroller = scrollerFor(node);
        const documentScroller = scroller === document.scrollingElement || scroller === document.documentElement;
        const view = documentScroller ? null : scroller.getBoundingClientRect();
        scrollerRef.current = {
          node: scroller,
          top: view ? view.top : 0,
          bottom: view ? view.bottom : window.innerHeight,
          start: scroller.scrollTop,
          max: Math.max(0, scroller.scrollHeight - scroller.clientHeight),
          startY: event.clientY,
          pointer: event.clientY,
          armed: false
        };
        event.currentTarget.setPointerCapture(event.pointerId);
        /*
          THE GESTURE BELONGS TO THIS HANDLE FROM HERE ON. Without `preventDefault` the browser also
          begins a text selection, which drags a blue smear across every card the pointer crosses
          and, on touch, turns the reorder into a long-press selection instead.

          AND THE FOCUS IS TAKEN BACK BY HAND, because that same `preventDefault` is what stops the
          browser focusing the button it was pressed on. Leaving it unfocused would mean somebody who
          dragged a card once could not then nudge it with the arrow keys — the two paths would stop
          being interchangeable at exactly the point a person switched between them.
        */
        event.preventDefault();
        event.currentTarget.focus();
        setDrag({
          key,
          pointerId: event.pointerId,
          from,
          to: from,
          startY: event.clientY,
          offset: 0,
          scrolled: 0,
          boxes,
          snapshot: [...order]
        });
      },
      onPointerMove(event: React.PointerEvent<HTMLElement>) {
        const clientY = event.clientY;
        const pointerId = event.pointerId;
        /*
          THE POINTER'S POSITION IS RECORDED OUTSIDE THE UPDATER, because the frame loop needs it and
          a state updater is not a place to have side effects — React calls it twice in StrictMode,
          which `next.config.ts` turns on for every development mount.
        */
        const scroller = scrollerRef.current;
        if (scroller) {
          scroller.pointer = clientY;
          // A press is not a drag; see `ARM_TRAVEL`.
          if (Math.abs(clientY - scroller.startY) >= ARM_TRAVEL) scroller.armed = true;
        }
        setDrag((current) => {
          if (!current || current.pointerId !== pointerId) return current;
          const offset = clientY - current.startY;
          // GUARDED, BECAUSE THE SNAPSHOT CAN OUTLIVE THE LIST IT WAS TAKEN OF. A list that
          // shortened mid-gesture leaves `from` past the end of `boxes`, and the unguarded read
          // threw on the next pointermove — an exception inside a state updater, which takes the
          // whole tab down rather than losing a drag. The release path checks the same thing
          // properly and cancels.
          const box = current.boxes[current.from];
          if (!box) return current;
          // The finger's travel PLUS the scroller's; the header's reconciliation, in one expression.
          const to = dragTargetIndex(current.boxes, current.from, offset + current.scrolled);
          if (offset === current.offset && to === current.to) return current;
          return { ...current, offset, to };
        });
      },
      onPointerUp(event: React.PointerEvent<HTMLElement>) {
        /*
          THIS READS THE RENDER CLOSURE WHILE `onPointerMove` ABOVE USES A FUNCTIONAL UPDATER, AND THE
          ASYMMETRY IS THE POINT. The move handler must compose with moves React has queued but not
          yet rendered, so it goes through `setDrag((current) => …)`. This handler takes the plain
          `drag` from the closure instead, which means it commits the destination of the LAST PAINTED
          FRAME rather than of the last event received. That is a real difference: react-dom 19 puts
          `pointermove` in ContinuousEventPriority — flushed by a Scheduler macrotask — and
          `pointerup` in DiscreteEventPriority, so a move dispatched in the same input batch as the
          release genuinely has not rendered by the time we run. The auto-scroll's frame loop writes
          through the same functional updater and lands on the same side of the trade: a scroll that
          has not been painted has not been shown to anybody either.

          COMMITTING WHAT WAS PAINTED IS THE CORRECT SIDE OF THAT TRADE, because everything the
          designer can see is derived from this same committed `drag`: the neighbours' displacement
          (`shift`, below), `shiftFor`, and the dragged card's own `translateY` (which is
          `drag.offset + drag.scrolled`). A move that never reached this handler never reached the
          preview either, so the screen and the write always agree — what is lost is a sub-frame flick
          that was never shown to anybody. Worst case the swallowed move WAS the whole gesture,
          `current.to` is still `current.from`, the equality check below returns, and the flick does
          nothing at all.

          SO DO NOT "FIX" THIS BY GIVING THE RELEASE A PRIVATE VIEW OF `to` — a ref written by the
          move handler and read only here, or a recompute from `event.clientY`, would INVERT the
          guarantee and commit an arrangement the last painted frame never showed. That is the one
          failure a reorder must not have: a write with a designer's name on it that disagrees with
          what they saw. If the swallowed flick is ever worth chasing, the whole drag record has to
          move into a ref that the PREVIEW reads too, with a tick to drive the re-render.
        */
        const current = drag;
        setDrag(null);
        scrollerRef.current = null;
        if (!current || current.pointerId !== event.pointerId) return;
        if (current.to === current.from) return;
        // Rule 2, said out loud rather than swallowed.
        const unchanged =
          current.snapshot.length === order.length &&
          current.snapshot.every((value, index) => value === order[index]);
        if (!unchanged) {
          setAnnouncement(
            `${labelFor(current.key)} was not moved: the list changed while it was being dragged. Try again.`
          );
          return;
        }
        // ANNOUNCED ONLY IF IT HAPPENED. `false` is a refusal, and a refusing caller owns saying so
        // in its own words — it has the reason and this hook does not.
        if (onReorder(current.from, current.to) !== false) {
          announceMove(current.key, current.to);
        }
      },
      onPointerCancel() {
        setDrag(null);
        scrollerRef.current = null;
      }
    }),
    [announceMove, drag, labelFor, locked, onReorder, order]
  );

  /**
   * How far each row is pushed while a drag is in flight, in pixels.
   *
   * ── WHY THE DRAGGED ROW'S OWN SIZE IS THE RIGHT NUMBER FOR EVERY NEIGHBOUR ───────────────────────
   *
   * A reviewer read this as a bug on variable-height rows: neighbours are all displaced by ONE value
   * taken from the dragged row, while a collection row in `EntityForm` can be expanded and several
   * times taller than its siblings. It is worth writing down why that is not a bug, because the
   * reading is a natural one and the next reader will have it too.
   *
   * Nothing here re-flows. The dragged row is translated and its neighbours are pushed by CSS, so
   * what the preview must simulate is one operation: LIFTING ONE ROW OUT OF THE COLUMN AND PUTTING IT
   * BACK SOMEWHERE ELSE. Lifting a row out closes a gap of exactly that row's extent, and putting it
   * back opens one of exactly the same extent — at both ends, and whatever the neighbours measure. So
   * a single displacement, taken from the DRAGGED row, is not an approximation of the landing; it is
   * the landing.
   *
   * ── WHAT *WAS* MISSING, AND IT IS THE SPACE BETWEEN THE ROWS ────────────────────────────────────
   *
   * `getBoundingClientRect().height` is the row's border box and excludes the flex `gap` above it. A
   * row lifted out of a gapped column frees its own height PLUS one gap, so shifting by height alone
   * under-shot by exactly one gap per displaced row — which on this repo's `gap-3` lists is 12px of
   * overlap, small enough to look like sloppy rendering rather than a wrong target, and present at
   * every call site including the uniform-row one it shipped with.
   *
   * So the displacement is now measured as the DISTANCE BETWEEN ADJACENT ROW TOPS, which carries the
   * gap by construction and needs no knowledge of the container's CSS. Measured from the snapshot, so
   * it is still one measurement taken at pointerdown (rule 1) and still cannot feed back into itself
   * — and a mid-gesture scroll cannot disturb it either, because it is a DIFFERENCE between two rows
   * of that snapshot and a scroll moves both by the same amount.
   *
   * ── AND THE NEIGHBOUR MUST BE IN THE SAME VISUAL LIST, WHICH IS NOT THE SAME AS THE NEXT INDEX ──
   *
   * `order` is not always one list on screen. `CustomSectionsEditor` drives every question in the
   * workshop from one hook (the Rules of Hooks forbid one per section), so `boxes[from + 1]` can be
   * the first question of the NEXT PANEL — across a section header, a stage picker, a description box
   * and a retired-question list. Measured against that, the extent for a 70px row came out several
   * hundred pixels, and dragging the last question of any section threw its neighbour across the
   * panel boundary for the whole gesture. The commit was right; only the preview was nonsense, which
   * reads as the drag having done something wrong.
   *
   * So a neighbour is only used when it is PLAUSIBLY the adjacent row: the gap between two rows of one
   * list is small, and `MAX_ADJACENT_GAP` is the bound on what counts. Beyond it the measurement falls
   * back to the dragged row's own height — the pre-gap answer, which under-shoots by one gap and is
   * far better than over-shooting by a panel.
   *
   * ── THE LAST ROW, WHICH IS WHERE THE OBVIOUS VERSION OF THIS IS WRONG ───────────────────────────
   *
   * Top-to-top only works downwards: there is no row after the last one. The tempting fallback is
   * `box.top - before.top`, and it is a DIFFERENT quantity — the extent of the row ABOVE, which is
   * only the right answer when the two happen to be the same height. On a list whose rows can expand
   * (a collection row with its panel open) that is exactly the case that differs, and dragging the
   * last row would displace everything by a stranger's height.
   *
   * So the gap is derived from the pair that IS measurable — `box.top - (before.top + before.height)`
   * is the space between them — and added to the dragged row's OWN height. Same quantity as the
   * downward case, obtained from the other side.
   *
   * With a single row there is no neighbour, no gap to derive and no drag worth previewing; the row's
   * own height is the honest fallback. With no box at all there is nothing to displace, and 0 is the
   * only answer that is not a fabrication — the earlier `after.top - (box?.top ?? 0)` form would have
   * measured from the viewport's origin and pushed every neighbour by a few hundred pixels.
   *
   * ── AND THE DRAGGED ROW CARRIES BOTH HALVES OF ITS TRAVEL ───────────────────────────────────────
   *
   * `drag.offset + drag.scrolled`, never `drag.offset` alone. The row is laid out normally and so it
   * MOVES WITH THE SCROLL like every other row; the extra term is what puts it back under a finger
   * that did not move. Drop it and the card slides away upward at exactly the speed the auto-scroll
   * is running at — which looks like the gesture having been lost, at the moment the designer is
   * furthest from where they started.
   */
  const shift = useMemo(() => {
    const map = new Map<string, number>();
    if (!drag) return map;
    const box = drag.boxes[drag.from];
    if (!box) return map;
    // A neighbour separated by more than this is not the next row of this list — it is the next
    // PANEL. See the note above. Generous enough for any gap this app's lists use (`gap-3` is 12px,
    // and a bordered row card adds a few more) and far below a section header.
    const MAX_ADJACENT_GAP = 64;
    const adjacent = (gap: number) => gap >= 0 && gap <= MAX_ADJACENT_GAP;
    const after = drag.boxes[drag.from + 1];
    const before = drag.boxes[drag.from - 1];
    const gapAfter = after ? after.top - (box.top + box.height) : null;
    const gapBefore = before ? box.top - (before.top + before.height) : null;
    const extent =
      gapAfter !== null && adjacent(gapAfter)
        ? box.height + gapAfter
        : gapBefore !== null && adjacent(gapBefore)
          ? box.height + gapBefore
          : box.height;
    order.forEach((key, index) => {
      if (index === drag.from) {
        map.set(key, drag.offset + drag.scrolled);
        return;
      }
      if (index > drag.from && index <= drag.to) map.set(key, -extent);
      else if (index < drag.from && index >= drag.to) map.set(key, extent);
      else map.set(key, 0);
    });
    return map;
  }, [drag, order]);

  const shiftFor = useCallback((key: string) => shift.get(key) ?? 0, [shift]);

  return {
    registerRow,
    handleProps,
    shiftFor,
    draggingKey: drag?.key ?? null,
    announcement,
    announceMove
  };
}
