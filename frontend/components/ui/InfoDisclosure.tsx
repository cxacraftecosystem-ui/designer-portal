"use client";

import { Info } from "lucide-react";

import { infoToggleAria } from "@/components/ui/infoToggleAria";

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
          ? "relative inline-grid h-5 w-5 shrink-0 place-items-center rounded-full bg-purple-100 text-purple-700 dark:bg-purple-900/60 dark:text-purple-200 transition after:absolute after:-inset-3 after:content-['']"
          : "relative inline-grid h-5 w-5 shrink-0 place-items-center rounded-full text-ink-500 transition hover:bg-purple-50 hover:text-purple-700 dark:hover:bg-purple-950 dark:hover:text-purple-200 after:absolute after:-inset-3 after:content-['']"
      }
    >
      <Info className="h-4 w-4" aria-hidden />
    </button>
  );
}

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
