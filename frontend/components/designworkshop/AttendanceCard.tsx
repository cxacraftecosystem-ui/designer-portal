"use client";

/**
 * Daily attendance — one tick-list per workshop day, starting "All present" (owner, C7, 2026-10-17).
 *
 * Writes `participant.absentDays` and nothing else; days attended, the completion count and the
 * certificates' days are counted from it (`lib/stageComputed`, stored by the save). An untouched
 * roster is everybody present every day, so a designer only ticks the exceptions off.
 */

import { useState } from "react";
import { CalendarCheck } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import type { DwRow } from "@/lib/designWorkshops";
import { dateOfDay } from "@/lib/autoNumber";
import { absentDays, namedParticipants, withAttendance } from "@/lib/stageComputed";

export function AttendanceCard({
  days,
  participants,
  startDate,
  onChange,
  disabled = false
}: {
  days: readonly number[];
  participants: readonly DwRow[];
  startDate?: string | null;
  onChange: (rows: DwRow[]) => void;
  disabled?: boolean;
}) {
  const [picked, setPicked] = useState<number | null>(null);
  const people = namedParticipants(participants);
  if (!days.length || !people.length) return null;
  const day = picked !== null && days.includes(picked) ? picked : days[0];
  const present = people.filter((p) => !absentDays(p).has(String(day))).length;

  const write = (update: (row: DwRow) => DwRow) => {
    onChange(participants.map((row) => (people.includes(row) ? update(row) : row)));
  };

  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="attendance-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="attendance-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <CalendarCheck className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
          Attendance
        </h2>
        <p className="text-xs text-ink-500" aria-live="polite">
          {present === people.length ? "All present" : `${present} of ${people.length} present`}
        </p>
      </div>

      {/* A choice between named days, not an action row. */}
      <div className="flex flex-wrap gap-2" role="group" aria-label="Workshop day">
        {days.map((n) => {
          const when = dateOfDay(startDate, n);
          return (
            <button
              key={n}
              type="button"
              aria-pressed={n === day}
              onClick={() => setPicked(n)}
              className={
                n === day
                  ? "rounded-full bg-purple-700 px-3 py-1.5 text-xs font-medium text-white"
                  : "rounded-full border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 hover:border-purple-300"
              }
            >
              Day {n}
              {when ? ` · ${formatDay(when)}` : ""}
            </button>
          );
        })}
      </div>

      <ul className="grid gap-1 sm:grid-cols-2">
        {people.map((person, index) => {
          const isPresent = !absentDays(person).has(String(day));
          const id = `attendance-${day}-${index}`;
          return (
            <li key={String(person._clientKey ?? person._entryId ?? index)}>
              <label htmlFor={id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md px-2 hover:bg-surface-50">
                <input
                  id={id}
                  type="checkbox"
                  className="h-4 w-4 accent-purple-700"
                  checked={isPresent}
                  disabled={disabled}
                  onChange={(event) => write((row) => (row === person ? withAttendance(row, day, event.target.checked) : row))}
                />
                <span className="min-w-0 truncate text-sm text-ink-900">{String(person.name ?? "").trim() || "Unnamed"}</span>
              </label>
            </li>
          );
        })}
      </ul>

      {present < people.length ? (
        <ActionGroup align="start">
          <button
            type="button"
            className="field-button-secondary"
            disabled={disabled}
            onClick={() => write((row) => withAttendance(row, day, true))}
          >
            Mark all present
          </button>
        </ActionGroup>
      ) : null}
    </section>
  );
}

function formatDay(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}
