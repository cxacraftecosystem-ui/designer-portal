"use client";

import type { ReactNode } from "react";

import { ActionGroup } from "@/components/ui/ActionGroup";

const BASE =
  "inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border px-3 py-1.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-60 pointer-coarse:min-h-11 pointer-coarse:px-4";

const TONE = {
  edit: "border-field-300 text-field-700 dark:border-purple-400/50 dark:text-purple-300 hover:bg-field-100 dark:hover:bg-purple-950/40",
  neutral: "border-line-200 text-ink-700 hover:bg-surface-50",
  danger: "border-red-200 text-red-700 dark:border-red-400/40 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40",
  positive: "border-success-600/30 text-success-600 dark:border-emerald-400/40 dark:text-emerald-400 hover:bg-success-100 dark:hover:bg-emerald-950/40"
} as const;

export type RowActionTone = keyof typeof TONE;

export function rowAction(tone: RowActionTone = "neutral", extra?: string): string {
  return `${BASE} ${TONE[tone]}${extra ? ` ${extra}` : ""}`;
}

export function RowActions({ children }: { children: ReactNode }) {
  return (
    <ActionGroup align="end" inline>
      {children}
    </ActionGroup>
  );
}
