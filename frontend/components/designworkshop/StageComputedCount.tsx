"use client";

/**
 * A count the stage works out for itself, drawn where the typed box used to be (owner, C7/C8).
 *
 * Read-only: `useComputedCounts` on the stage page keeps the value equal to the rows, and the server
 * stores the same figure on save. A value with nothing to count it from was typed on an older
 * workshop, and is shown as it was.
 */

import type { DwField, DwValue } from "@/lib/designWorkshops";

const SOURCE: Record<string, string> = {
  ROSTER_COUNT: "Counted from the roster.",
  ROSTER_WOMEN: "Counted from the roster.",
  ATTENDANCE_DAYS: "From the attendance ticks.",
  ROSTER_COMPLETED: "Present on every day.",
  SURVEY_RESPONSES: "Responses plus questionnaire sittings."
};

export function StageComputedCount({ field, value, labelId }: { field: DwField; value: DwValue | undefined; labelId: string }) {
  const shown = typeof value === "number" || (typeof value === "string" && value.trim() !== "") ? String(value) : "—";
  return (
    <div className="grid gap-0.5">
      <output
        aria-labelledby={labelId}
        className="block rounded-md border border-line-200 bg-surface-50 px-3.5 py-2.5 text-sm font-medium tabular-nums text-ink-900"
      >
        {shown}
        {field.unit && shown !== "—" ? ` ${field.unit}` : ""}
      </output>
      <span className="text-xs text-ink-500">{SOURCE[field.computedKind ?? ""] ?? "Worked out from the stage."}</span>
    </div>
  );
}
