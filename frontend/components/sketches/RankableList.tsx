"use client";

import { useCallback } from "react";
import { ArrowDown, ArrowUp, GripVertical } from "lucide-react";

import { useDragReorder } from "@/components/hooks/useDragReorder";
import { moveBy, moveTo } from "./reviewRanking";

export type RankableRenderArgs = {
  position: number;
  total: number;
  dragging: boolean;
};

type Props = {
  order: readonly string[];
  labelFor: (id: string) => string;
  renderItem: (id: string, args: RankableRenderArgs) => React.ReactNode;
  onReorder: (next: string[]) => void;
  disabledReason: string | null;
};

export function RankableList({ order, labelFor, renderItem, onReorder, disabledReason }: Props) {
  const total = order.length;
  const locked = disabledReason !== null;

  const drag = useDragReorder({
    order,
    labelFor,
    locked,
    onReorder: useCallback(
      (from: number, to: number) => onReorder(moveTo(order, from, to)),
      [onReorder, order]
    )
  });

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
