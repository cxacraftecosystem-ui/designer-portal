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
 * ── WHERE THE DRAG ITSELF LIVES ─────────────────────────────────────────────────────────────────
 *
 * `components/hooks/useDragReorder.ts`, and that file's header carries the whole argument: why
 * pointer events rather than the HTML5 drag API (`dragstart` does not fire for touch on Android
 * Chrome at all), why no dnd dependency, and the five things that make the gesture honest — the
 * rectangle snapshot, the ARRANGEMENT snapshot that abandons a gesture whose ground moved, commit
 * only on release, the spoken announcement, and the drag dying with the list that owns it.
 *
 * It used to live inline here, because this was the only reorderable list in the repository. It
 * moved out on 2026-08-25 when the custom-sections editor needed the same gesture and could not use
 * this component — that screen draws a whole `panel` per row, whereas this one owns its `<ol>`, its
 * numbering column and its three buttons. THE MECHANICS MOVED; THE RENDERING STAYED — with ONE
 * deliberate behaviour change carried in, so do not read this as a pure extraction. The hook now
 * measures a neighbour's displacement as the distance between ADJACENT ROW TOPS, which means every
 * neighbour in the `<ol className="grid gap-3">` below travels the dragged row's height PLUS the
 * 12px gap between rows. The inline version shifted by `getBoundingClientRect().height` alone — the
 * border box, which excludes the gap above it — and so under-shot by exactly one gap per displaced
 * card, every drag, since the day this list shipped. It was small enough to read as sloppy rendering
 * rather than as a wrong target, which is why it lasted; `useDragReorder.ts`'s note on the shift
 * carries the full argument and the reason the naive fallbacks for the last row are wrong.
 *
 * What this file still owns, and what is still the point of it: the position printed on the card as
 * a NUMBER. A rank that exists only as a place in a visual list is a rank a screen-reader user
 * cannot read back — and colour or position alone never carries meaning in this app.
 */

import { useCallback } from "react";
import { ArrowDown, ArrowUp, GripVertical } from "lucide-react";

import { useDragReorder } from "@/components/hooks/useDragReorder";
import { moveBy, moveTo } from "./reviewRanking";

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

export function RankableList({ order, labelFor, renderItem, onReorder, disabledReason }: Props) {
  const total = order.length;
  const locked = disabledReason !== null;

  /**
   * The drag half, from the shared hook.
   *
   * `onReorder` is handed `from`/`to` INDICES by the hook and this component's own prop takes a
   * whole arrangement, so `moveTo` bridges them — which is right rather than merely convenient: the
   * hook has no business knowing that this caller's arrangement is a `string[]` it can rebuild,
   * and `moveTo` is already the pure helper `reviewRanking` exports for exactly this.
   */
  const drag = useDragReorder({
    order,
    labelFor,
    locked,
    onReorder: useCallback(
      (from: number, to: number) => onReorder(moveTo(order, from, to)),
      [onReorder, order]
    )
  });

  /**
   * One arrow press.
   *
   * ANNOUNCED THROUGH THE HOOK'S OWN `announceMove`, so the two paths speak in one voice. They used
   * to be two `setAnnouncement` calls with the same template written twice, which is the shape that
   * drifts: a wording fixed on the drag path and missed on the arrow path would leave a
   * screen-reader user hearing two different sentences for one act.
   */
  const step = useCallback(
    (id: string, delta: number) => {
      if (locked) return;
      const next = moveBy(order, id, delta);
      if (next.every((value, index) => value === order[index])) return;
      onReorder(next);
      const landed = next.indexOf(id);
      if (landed >= 0) drag.announceMove(id, landed);
    },
    [drag, locked, onReorder, order]
  );

  const jump = useCallback(
    (id: string, to: number) => {
      if (locked) return;
      const from = order.indexOf(id);
      if (from < 0) return;
      const next = moveTo(order, from, to);
      if (next.every((value, index) => value === order[index])) return;
      onReorder(next);
      const landed = next.indexOf(id);
      if (landed >= 0) drag.announceMove(id, landed);
    },
    [drag, locked, onReorder, order]
  );

  return (
    <div>
      {/*
        THE LIVE REGION IS RENDERED WHETHER OR NOT THERE IS ANYTHING IN IT. Assistive technology
        only announces mutations inside a region that already existed when the page settled — the
        same rule `Toast`'s always-present viewport follows.
      */}
      <p aria-live="polite" className="sr-only">
        {drag.announcement}
      </p>
      <ol className="grid gap-3">
        {order.map((id, index) => {
          const dragging = drag.draggingKey === id;
          const offset = drag.shiftFor(id);
          return (
            <li
              key={id}
              ref={drag.registerRow(id)}
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
                    {...drag.handleProps(id)}
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
