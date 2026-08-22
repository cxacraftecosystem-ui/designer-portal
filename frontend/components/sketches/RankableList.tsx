"use client";

/**
 * A list a designer can rearrange — with the arrows AND by dragging, because both are required.
 *
 * ── WHY BOTH, AND WHY THE ARROWS ARE THE PRIMARY PATH ───────────────────────────────────────────
 *
 * The owner asked for ranking "using both drag-and-drop and the up/down arrows on the card". That
 * is not two ways of saying one thing: a drag is a pointer gesture and is unreachable from a
 * keyboard, from a switch device and from a screen reader, so drag ALONE would put the one
 * judgement this feature exists to record — the designer's final say — behind a mouse. The arrows
 * are therefore always rendered, always enabled while the list is writable, and are what the
 * keyboard, the assistive layer and the specs drive. The drag handle is an accelerator on top.
 *
 * ── WHY IT IS HAND-ROLLED, WITH POINTER EVENTS AND NO LIBRARY ───────────────────────────────────
 *
 * There is no drag-and-drop anywhere in this repository today and no dnd dependency in
 * `package.json` — checked, not assumed. This project argues every dependency it takes, and what
 * is needed here is a list of eight cards reordered by one finger: `setPointerCapture`, one
 * rectangle snapshot and `moveTo` cover it in about a hundred lines, where the smallest of the
 * usual libraries is tens of kilobytes on a page that field designers load over a village
 * connection. Pointer events rather than the HTML5 drag API for the same reason the rest of the
 * app avoids it: `dragstart`/`dragover` do not fire for touch at all on Android Chrome, and a
 * gesture that works on a laptop and silently does nothing on the phone the fieldwork is done on
 * is worse than no gesture.
 *
 * ── THE THREE THINGS THAT MAKE THE DRAG HONEST ──────────────────────────────────────────────────
 *
 * 1. **The rectangles are snapshotted once, at pointerdown.** The dragged card is translated and
 *    its neighbours are shifted by CSS, so nothing in the layout actually moves while a drag is in
 *    flight. Re-measuring during the gesture would feed the shift back into the measurement and
 *    the target index would oscillate under the finger. THE ARRANGEMENT IS SNAPSHOTTED WITH THEM,
 *    because the measurement is only meaningful against the list it was taken of — see
 *    `DragState.snapshot` and `endDrag`.
 * 2. **Nothing is committed until the pointer is released**, and Escape cancels outright. A
 *    reorder is a write with a person's name on it (see `reviewRanking.arrangeRows`), so a stray
 *    swipe over a card must not be able to stamp an arrangement.
 * 3. **Every move is announced in words** through a polite live region, and the position is also
 *    printed on the card as a number. A rank that exists only as a place in a visual list is a
 *    rank a screen-reader user cannot read back — and colour or position alone never carries
 *    meaning in this app.
 *
 * Motion: the neighbours' shift is a CSS `transition-transform`, which the global reduced-motion
 * rules in `globals.css` zero for both sources (OS preference and the in-app toggle). There is no
 * framer-motion here on purpose — it writes inline styles that CSS cannot reach, and this file
 * would then need its own JS branch for a 120ms slide.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowDown, ArrowUp, GripVertical } from "lucide-react";

import { moveBy, moveTo, sameOrder } from "./reviewRanking";

/** What one row needs to render itself inside the list. */
export type RankableRenderArgs = {
  /** 1-based place in the current arrangement, printed on the card. */
  position: number;
  total: number;
  /** True while this card is the one under the finger. */
  dragging: boolean;
};

type Props = {
  /** The current arrangement, as subject ids. The parent owns it; this component never stores it. */
  order: readonly string[];
  /** A human name for one id — used by the arrow labels and by every announcement. */
  labelFor: (id: string) => string;
  renderItem: (id: string, args: RankableRenderArgs) => React.ReactNode;
  /** Called with the whole new arrangement. Not called when the move would change nothing. */
  onReorder: (next: string[]) => void;
  /**
   * Why this list cannot be rearranged, or null when it can.
   *
   * A SENTENCE RATHER THAN A BOOLEAN, because every caller that disables these controls has a
   * reason a designer needs to read — a pool reviewer is not a member of the workshop that owns
   * the ordinal, a save is in flight, there is no connection. Disabled controls with no
   * explanation are the shape of a page that looks broken.
   */
  disabledReason: string | null;
};

type DragState = {
  id: string;
  pointerId: number;
  from: number;
  to: number;
  startY: number;
  offset: number;
  /** Snapshot of every row's box at pointerdown; see the header. */
  boxes: Array<{ top: number; height: number }>;
  /**
   * The arrangement as it stood at pointerdown, so the release can tell whether it still holds.
   *
   * `from`, `to` and every rectangle in `boxes` are INDICES INTO THIS ARRAY. If the list changes
   * mid-gesture — a Refresh, a background item change, a colleague's row arriving on a sync — those
   * indices address different cards than the ones the finger was over, and committing them moves the
   * wrong piece and then stamps the result with a designer's name as a deliberate arrangement. So the
   * snapshot is kept and compared, and a gesture whose ground moved is abandoned rather than guessed
   * at. Every other path here resolves by ID first, which is why only this one needed it.
   */
  snapshot: readonly string[];
};

export function RankableList({ order, labelFor, renderItem, onReorder, disabledReason }: Props) {
  const rowRefs = useRef(new Map<string, HTMLLIElement>());
  const [drag, setDrag] = useState<DragState | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const total = order.length;
  const locked = disabledReason !== null;

  const announce = useCallback(
    (id: string, index: number) => {
      setAnnouncement(`${labelFor(id)} moved to position ${index + 1} of ${total}.`);
    },
    [labelFor, total]
  );

  const commit = useCallback(
    (next: string[], id: string) => {
      const index = next.indexOf(id);
      onReorder(next);
      if (index >= 0) announce(id, index);
    },
    [announce, onReorder]
  );

  const step = useCallback(
    (id: string, delta: number) => {
      if (locked) return;
      const next = moveBy(order, id, delta);
      if (next.every((value, index) => value === order[index])) return;
      commit(next, id);
    },
    [commit, locked, order]
  );

  const jump = useCallback(
    (id: string, to: number) => {
      if (locked) return;
      const from = order.indexOf(id);
      if (from < 0) return;
      const next = moveTo(order, from, to);
      if (next.every((value, index) => value === order[index])) return;
      commit(next, id);
    },
    [commit, locked, order]
  );

  /*
    THE DRAG IS TORN DOWN ON UNMOUNT AS WELL AS ON RELEASE. A route change mid-gesture would
    otherwise leave the captured pointer's listeners attached to a node React has removed, and the
    next pointerup would run a commit against an order that no longer exists on screen.
  */
  useEffect(() => {
    if (!drag) return;
    function cancelOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setDrag(null);
    }
    window.addEventListener("keydown", cancelOnEscape);
    return () => window.removeEventListener("keydown", cancelOnEscape);
  }, [drag]);

  function beginDrag(event: React.PointerEvent<HTMLButtonElement>, id: string) {
    if (locked || event.button !== 0) return;
    const from = order.indexOf(id);
    if (from < 0) return;
    const boxes = order.map((rowId) => {
      const node = rowRefs.current.get(rowId);
      const box = node?.getBoundingClientRect();
      return { top: box?.top ?? 0, height: box?.height ?? 0 };
    });
    event.currentTarget.setPointerCapture(event.pointerId);
    /*
      THE GESTURE BELONGS TO THIS HANDLE FROM HERE ON. Without `preventDefault` the browser also
      begins a text selection, which drags a blue smear across every card the pointer crosses and,
      on touch, turns the reorder into a long-press selection instead.

      AND THE FOCUS IS TAKEN BACK BY HAND, because that same `preventDefault` is what stops the
      browser focusing the button it was pressed on. Leaving it unfocused would mean a designer who
      dragged a card once could not then nudge it with the arrow keys — the two paths would stop
      being interchangeable at exactly the point somebody switched between them.
    */
    event.preventDefault();
    event.currentTarget.focus();
    setDrag({
      id,
      pointerId: event.pointerId,
      from,
      to: from,
      startY: event.clientY,
      offset: 0,
      boxes,
      snapshot: [...order]
    });
  }

  function moveDrag(event: React.PointerEvent<HTMLButtonElement>) {
    setDrag((current) => {
      if (!current || current.pointerId !== event.pointerId) return current;
      const offset = event.clientY - current.startY;
      // GUARDED, BECAUSE THE SNAPSHOT CAN OUTLIVE THE LIST IT WAS TAKEN OF. A list that shortened
      // mid-gesture leaves `from` past the end of `boxes`, and the unguarded read threw on the next
      // pointermove — an exception inside a state updater, which takes the whole tab down rather
      // than losing a drag. The release path checks the same thing properly and cancels.
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
  }

  function endDrag(event: React.PointerEvent<HTMLButtonElement>) {
    const current = drag;
    setDrag(null);
    if (!current || current.pointerId !== event.pointerId) return;
    if (current.to === current.from) return;
    /*
      THE GESTURE IS ABANDONED IF THE LIST MOVED UNDER IT, and it is said out loud rather than
      swallowed. `from`, `to` and the rectangles are all indices into the snapshot taken at
      pointerdown; if the arrangement is no longer that one, they name different cards, and a commit
      would move a piece nobody dragged and then write `rankFixedBy` over it as a decision. The
      alternative — re-deriving `from` by id and keeping `to` — silently keeps half a stale
      measurement, which is the same guess in a smaller coat.
    */
    if (!sameOrder(current.snapshot, order)) {
      setAnnouncement(
        `${labelFor(current.id)} was not moved: the list changed while it was being dragged. Try again.`
      );
      return;
    }
    commit(moveTo(order, current.from, current.to), current.id);
  }

  /** How far each row is pushed while a drag is in flight, in pixels. */
  const shift = useMemo(() => {
    const map = new Map<string, number>();
    if (!drag) return map;
    const height = drag.boxes[drag.from]?.height ?? 0;
    order.forEach((id, index) => {
      if (index === drag.from) {
        map.set(id, drag.offset);
        return;
      }
      if (index > drag.from && index <= drag.to) map.set(id, -height);
      else if (index < drag.from && index >= drag.to) map.set(id, height);
      else map.set(id, 0);
    });
    return map;
  }, [drag, order]);

  return (
    <div>
      {/*
        THE LIVE REGION IS RENDERED WHETHER OR NOT THERE IS ANYTHING IN IT. Assistive technology
        only announces mutations inside a region that already existed when the page settled — the
        same rule `Toast`'s always-present viewport follows.
      */}
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>
      <ol className="grid gap-3">
        {order.map((id, index) => {
          const dragging = drag?.id === id;
          const offset = shift.get(id) ?? 0;
          return (
            <li
              key={id}
              ref={(node) => {
                if (node) rowRefs.current.set(id, node);
                else rowRefs.current.delete(id);
              }}
              style={offset ? { transform: `translateY(${offset}px)` } : undefined}
              className={
                dragging
                  ? "relative z-10 rounded-lg shadow-panel ring-2 ring-purple-600/40 transition-transform"
                  : "relative rounded-lg transition-transform"
              }
            >
              <div className="flex items-start gap-2">
                <div className="flex shrink-0 flex-col items-center gap-1 pt-4">
                  {/*
                    THE PLACE IS A NUMBER ON THE CARD, not merely a position in a list. It is what
                    a designer says out loud ("prototype 3"), what the report prints, and the only
                    form of the rank a reader who cannot see the arrangement can get at.
                  */}
                  <span className="grid h-7 w-7 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700">
                    {index + 1}
                  </span>
                  <button
                    type="button"
                    className="grid h-8 w-8 place-items-center rounded-md border border-line-200 text-ink-700 transition hover:bg-surface-50 disabled:opacity-40"
                    aria-label={`Move ${labelFor(id)} up`}
                    disabled={locked || index === 0}
                    onClick={() => step(id, -1)}
                  >
                    <ArrowUp className="h-4 w-4" aria-hidden />
                  </button>
                  <button
                    type="button"
                    className="grid h-8 w-8 place-items-center rounded-md border border-line-200 text-ink-700 transition hover:bg-surface-50 disabled:opacity-40"
                    aria-label={`Move ${labelFor(id)} down`}
                    disabled={locked || index === total - 1}
                    onClick={() => step(id, 1)}
                  >
                    <ArrowDown className="h-4 w-4" aria-hidden />
                  </button>
                  {/*
                    THE HANDLE ANSWERS THE KEYBOARD TOO, even though the arrows above it already
                    do. A reader who has found the grip — it is the affordance that LOOKS like
                    reordering — must not have to go back and find two other buttons; and a handle
                    that swallowed the arrow keys while doing nothing would read as broken.
                    `touch-action: none` is what stops the browser claiming the gesture as a scroll
                    before the first pointermove arrives.
                  */}
                  <button
                    type="button"
                    className="grid h-8 w-8 cursor-grab touch-none place-items-center rounded-md border border-line-200 text-ink-500 transition hover:bg-surface-50 active:cursor-grabbing disabled:opacity-40"
                    aria-label={`Reorder ${labelFor(id)} — drag, or use the arrow keys`}
                    disabled={locked}
                    onPointerDown={(event) => beginDrag(event, id)}
                    onPointerMove={moveDrag}
                    onPointerUp={endDrag}
                    onPointerCancel={() => setDrag(null)}
                    onKeyDown={(event) => {
                      if (event.key === "ArrowUp") {
                        event.preventDefault();
                        step(id, -1);
                      } else if (event.key === "ArrowDown") {
                        event.preventDefault();
                        step(id, 1);
                      } else if (event.key === "Home") {
                        event.preventDefault();
                        jump(id, 0);
                      } else if (event.key === "End") {
                        event.preventDefault();
                        jump(id, total - 1);
                      }
                    }}
                  >
                    <GripVertical className="h-4 w-4" aria-hidden />
                  </button>
                </div>
                <div className="min-w-0 flex-1">{renderItem(id, { position: index + 1, total, dragging })}</div>
              </div>
            </li>
          );
        })}
      </ol>
      {locked ? <p className="mt-3 text-xs leading-5 text-ink-muted">{disabledReason}</p> : null}
    </div>
  );
}
