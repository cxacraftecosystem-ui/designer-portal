"use client";

import { Info } from "lucide-react";

import { infoToggleAria } from "@/components/ui/infoToggleAria";

/**
 * The small "i" beside a field label that opens its `details`.
 *
 * A real `<button>` with `aria-expanded`, collapsed by default, one press to open. The glyph is 20px
 * so it sits quietly beside an uppercase label; the `after:` box extends the press target to 44px
 * on every side without moving anything. Never put this INSIDE a `<label>`: a label forwards a click
 * to its control and folds every named descendant into the control's accessible name.
 *
 * The panel is the caller's (it belongs under the label row, above the control), so this only
 * draws the toggle. Pure half: `infoToggleAria.ts`.
 */
export function InfoToggle({
  fieldLabel,
  expanded,
  panelId,
  onToggle
}: {
  fieldLabel: string;
  expanded: boolean;
  panelId: string;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      {...infoToggleAria(fieldLabel, expanded, panelId)}
      data-info-toggle=""
      onClick={onToggle}
      className={
        expanded
          ? "relative inline-grid h-5 w-5 shrink-0 place-items-center rounded-full bg-purple-100 text-purple-700 transition after:absolute after:-inset-3 after:content-['']"
          : "relative inline-grid h-5 w-5 shrink-0 place-items-center rounded-full text-ink-500 transition hover:bg-purple-50 hover:text-purple-700 after:absolute after:-inset-3 after:content-['']"
      }
    >
      <Info className="h-4 w-4" aria-hidden />
    </button>
  );
}

/** The opened guidance. Mounted only while open, so `aria-controls` never points at nothing. */
export function InfoPanel({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <p
      id={id}
      data-info-panel=""
      className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700"
    >
      {children}
    </p>
  );
}
