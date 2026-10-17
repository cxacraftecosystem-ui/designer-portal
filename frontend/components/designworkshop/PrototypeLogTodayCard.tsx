"use client";

/**
 * "Log today" on each prototype (owner, A15, 2026-10-17).
 *
 * A daily log row used to mean re-picking the prototype and typing the date, across ten prototypes
 * for two weeks. One tap here adds the row with the prototype and today's date set, and a second
 * button reuses the stage name logged last for that prototype.
 */

import { NotebookPen } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import type { DwRow } from "@/lib/designWorkshops";
import { lastStageName, localToday, logTodayRow, prototypeRefOf as refOf } from "@/lib/prototypeLog";

export { PROTOTYPE_STAGE } from "@/lib/prototypeLog";

export function PrototypeLogTodayCard({
  prototypes,
  logs,
  onAdd,
  disabled = false
}: {
  prototypes: readonly DwRow[];
  logs: readonly DwRow[];
  onAdd: (row: DwRow) => void;
  disabled?: boolean;
}) {
  const named = prototypes.filter((p) => refOf(p) && (String(p.name ?? "").trim() || String(p.prototypeCode ?? "").trim()));
  if (!named.length) return null;
  const today = localToday();
  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="log-today-heading">
      <h2 id="log-today-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <NotebookPen className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        Daily log
      </h2>
      <ul className="grid gap-2">
        {named.map((prototype) => {
          const last = lastStageName(prototype, logs);
          const title = [prototype.prototypeCode, prototype.name].filter((v) => String(v ?? "").trim()).join(" · ");
          const loggedToday = logs.some((l) => String(l.prototypeRef ?? "") === refOf(prototype) && l.logDate === today);
          return (
            <li
              key={refOf(prototype)}
              className="flex flex-col gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 sm:flex-row sm:items-center"
            >
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-ink-900">{title}</p>
                {loggedToday ? <p className="text-xs text-ink-500">Logged today</p> : null}
              </div>
              <ActionGroup inline align="end" className="max-sm:w-full">
                <button type="button" className="field-button-secondary" disabled={disabled} onClick={() => onAdd(logTodayRow(prototype))}>
                  Log today
                </button>
                {last ? (
                  <button
                    type="button"
                    className="field-button-secondary"
                    disabled={disabled}
                    onClick={() => onAdd(logTodayRow(prototype, last))}
                  >
                    Again: {last}
                  </button>
                ) : null}
              </ActionGroup>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
