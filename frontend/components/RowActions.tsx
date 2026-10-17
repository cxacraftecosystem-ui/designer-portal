"use client";

import type { ReactNode } from "react";

import { ActionGroup } from "@/components/ui/ActionGroup";

/**
 * The standard action cluster every list row ends with: separate rounded buttons, one per action,
 * never a dropdown or a bare text link. Edit sits first in the brand outline, Discuss (comments +
 * edit history) next in the neutral outline, and a destructive action last in red — that one is
 * admin-only and must always confirm before it fires.
 *
 * Pages import `rowAction(tone)` for the class string (so a row can render a `<Link>`, a `<button>`
 * or an `<a>` and still look identical) and `RowActions` for the right-aligned wrapper. Keeping the
 * classes here is what stops each page from drifting its own variant of the same three buttons.
 */

/*
  TOUCH TARGET (UX audit DW-08, 2026-10-16): a row action was ~30px tall, so on a phone Reject sat a
  thumb's width from Approve. `min-h-11` (44px) on a coarse pointer only — desktop density is
  unchanged — and `pointer-coarse:` is Tailwind 4's own variant for `@media (pointer: coarse)`.
*/
const BASE =
  "inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border px-3 py-1.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-60 pointer-coarse:min-h-11 pointer-coarse:px-4";

/*
  DARK PAIRS (UX audit DW-04): purple, red and success are literal colours that do not invert, so on
  the dark card these inks measured 1.73:1, 2.72:1 and 3.5:1 and the review queue's Edit nearly
  vanished. Each tone now names its dark ink, border and hover wash.
*/
const TONE = {
  /** Primary row action — edit/open the record. */
  edit: "border-field-300 text-field-700 dark:border-purple-400/50 dark:text-purple-300 hover:bg-field-100 dark:hover:bg-purple-950/40",
  /** Secondary row actions — Discuss, Assign, and anything else non-destructive. */
  neutral: "border-line-200 text-ink-700 hover:bg-surface-50",
  /** Destructive row action — delete/reject. Gate on admin view and confirm before running. */
  danger: "border-red-200 text-red-700 dark:border-red-400/40 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40",
  /** Affirmative decision — approve. */
  positive: "border-success-600/30 text-success-600 dark:border-emerald-400/40 dark:text-emerald-400 hover:bg-success-100 dark:hover:bg-emerald-950/40"
} as const;

export type RowActionTone = keyof typeof TONE;

/** Class string for one row-action button. */
export function rowAction(tone: RowActionTone = "neutral", extra?: string): string {
  return `${BASE} ${TONE[tone]}${extra ? ` ${extra}` : ""}`;
}

/** A row's action buttons, laid out by the shared `ActionGroup` rule. */
export function RowActions({ children }: { children: ReactNode }) {
  return <ActionGroup align="end">{children}</ActionGroup>;
}
