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
 * 5. **The drag cannot outlive the list, and the one cross-window listener is released with it.**
 *    `drag` is this hook's own `useState`, so a route change mid-gesture discards it with the
 *    consumer and the next mount starts from `null` — there is no path by which a lifted row
 *    nobody is touching survives to be drawn, and React's handlers leave with the node they were
 *    on. What DOES need a cleanup is the `keydown` listener rule 3's Escape half puts on
 *    `window`: the effect below adds it only while a gesture is in flight and removes it on
 *    release and on unmount alike.
 *
 *    THIS RULE USED TO READ "the drag is torn down on unmount as well as on release", naming a
 *    teardown of captured-pointer listeners that no line in this file performs. It was corrected
 *    on 2026-08-26 alongside the identical claim in its Android twin (`DwRankableList.kt`'s rule
 *    5, where a `DisposableEffect` was found to be writing to state discarded in the same
 *    breath). Do not make the old sentence true by adding a `releasePointerCapture` or an
 *    unmount `setDrag(null)`: the browser releases capture when the node goes, and state dying
 *    with the component is the mechanism, not a gap in one.
 *
 * ── MOTION ──────────────────────────────────────────────────────────────────────────────────────
 *
 * The neighbours' shift is a CSS `transition-transform` applied by the consumer, which the global
 * reduced-motion rules in `globals.css` zero for BOTH sources (the OS preference and the in-app
 * toggle). There is deliberately no framer-motion here: it writes inline styles CSS cannot reach, so
 * this file would then need its own JS branch for a 120ms slide — and the transform this hook
 * returns would be fighting framer for the same property, which §17 of the frontend contract names
 * as its own trap.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

/** Move the item at `from` to `to`, closing the gap behind it. Pure. */
export function moveIndex<T>(items: readonly T[], from: number, to: number): T[] {
  const next = [...items];
  if (from < 0 || from >= next.length || to < 0 || to >= next.length) return next;
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

type DragState = {
  key: string;
  pointerId: number;
  from: number;
  to: number;
  startY: number;
  offset: number;
  /** Snapshot of every row's box at pointerdown; see rule 1. */
  boxes: Array<{ top: number; height: number }>;
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
  const [drag, setDrag] = useState<DragState | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const total = order.length;

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

  // Rule 3's Escape half — and the whole of rule 5's cleanup: the one listener this hook puts on `window`.
  useEffect(() => {
    if (!drag) return;
    function cancelOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setDrag(null);
    }
    window.addEventListener("keydown", cancelOnEscape);
    return () => window.removeEventListener("keydown", cancelOnEscape);
  }, [drag]);

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
          boxes,
          snapshot: [...order]
        });
      },
      onPointerMove(event: React.PointerEvent<HTMLElement>) {
        const clientY = event.clientY;
        const pointerId = event.pointerId;
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
          const centre = box.top + box.height / 2 + offset;
          let to = current.from;
          current.boxes.forEach((other, index) => {
            if (index === current.from) return;
            const otherCentre = other.top + other.height / 2;
            if (index < current.from && centre < otherCentre) to = Math.min(to, index);
            if (index > current.from && centre > otherCentre) to = Math.max(to, index);
          });
          if (offset === current.offset && to === current.to) return current;
          return { ...current, offset, to };
        });
      },
      onPointerUp(event: React.PointerEvent<HTMLElement>) {
        /*
          THIS READS THE RENDER CLOSURE WHILE `onPointerMove` ABOVE USES A FUNCTIONAL UPDATER, AND THE
          ASYMMETRY IS THE POINT. The move handler must compose with moves React has queued but not
          yet rendered, so it goes through `setDrag((current) => …)` at :261. This handler takes the
          plain `drag` from the closure instead, which means it commits the destination of the LAST
          PAINTED FRAME rather than of the last event received. That is a real difference: react-dom
          19 puts `pointermove` in ContinuousEventPriority — flushed by a Scheduler macrotask — and
          `pointerup` in DiscreteEventPriority, so a move dispatched in the same input batch as the
          release genuinely has not rendered by the time we run.

          COMMITTING WHAT WAS PAINTED IS THE CORRECT SIDE OF THAT TRADE, because everything the
          designer can see is derived from this same committed `drag`: the neighbours' displacement
          (`shift`, below), `shiftFor`, and the dragged card's own `translateY` (which is just
          `drag.offset`). A move that never reached this handler never reached the preview either, so
          the screen and the write always agree — what is lost is a sub-frame flick that was never
          shown to anybody. Worst case the swallowed move WAS the whole gesture, `current.to` is still
          `current.from`, the equality check below returns, and the flick does nothing at all.

          SO DO NOT "FIX" THIS BY GIVING THE RELEASE A PRIVATE VIEW OF `to` — a ref written by the
          move handler and read only here, or a recompute from `event.clientY`, would INVERT the
          guarantee and commit an arrangement the last painted frame never showed. That is the one
          failure a reorder must not have: a write with a designer's name on it that disagrees with
          what they saw. If the swallowed flick is ever worth chasing, the whole drag record has to
          move into a ref that the PREVIEW reads too, with a tick to drive the re-render.
        */
        const current = drag;
        setDrag(null);
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
   * it is still one measurement taken at pointerdown (rule 1) and still cannot feed back into itself.
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
        map.set(key, drag.offset);
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
