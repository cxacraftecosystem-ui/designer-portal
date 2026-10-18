"use client";

/**
 * HOW READY A QUEUED REPORT IS, ON ITS ROW (B4): a completeness bar with its sentence, and word chips
 * for this round's notes, the inspectors' verdicts and how long it has waited — so a reader can
 * choose what to open without opening each one. The bar is decoration; the sentence is the signal.
 */

import { ProgressBar } from "@/components/ui/ProgressBar";
import type { DwSummary } from "@/lib/designWorkshops";
import { readinessChips, readinessSentence, type DwReadiness } from "@/lib/reviewAids";

export function QueueReadiness({
  readiness,
  row
}: {
  readiness: DwReadiness | null | undefined;
  row: Pick<DwSummary, "lastHandedInAt" | "status">;
}) {
  if (!readiness) return null;
  const chips = readinessChips(readiness, row);
  return (
    <div className="mt-2 grid max-w-xl gap-1.5">
      {readiness.percent !== null ? (
        <ProgressBar
          aria-hidden
          percent={readiness.percent}
          tone={readiness.percent >= 100 ? "done" : "brand"}
          className="h-1.5"
        />
      ) : null}
      <span className="text-xs text-ink-500">{readinessSentence(readiness)}</span>
      {chips.length ? (
        <span className="flex flex-wrap gap-1.5">
          {chips.map((chip) => (
            <span
              className="inline-flex items-center rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] font-medium leading-4 text-ink-700"
              key={chip}
            >
              {chip}
            </span>
          ))}
        </span>
      ) : null}
    </div>
  );
}

export const QUEUE_SORT_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Default order" },
  { value: "oldest", label: "Oldest first" },
  { value: "newest", label: "Newest first" },
  { value: "complete", label: "Most complete first" }
];

export const CLEARED_FILTER_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any inspector verdict" },
  { value: "all", label: "All inspectors: no corrections" },
  { value: "some", label: "Some inspectors: no corrections" },
  { value: "none", label: "No inspector verdict yet" }
];
