"use client";

/**
 * "Planned workshop" on the sanction form (owner decision C1, 2026-10-17).
 *
 * One annual-plan row is one workshop, and the officer may pick ONLY a row whose number matches
 * exactly — so this is a number box and a Find button, never a browsable list of the plan (the plan
 * is not this tier's to read). The server answers the exact matches; a row already opened or
 * withdrawn comes back with its reason and cannot be picked. The pick is controlled by the page so
 * the submit can send `annualPlanEntryId` and reset it after an order is recorded.
 */

import { useState } from "react";

import { Field, TextInput } from "@/components/FormControls";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { describeApiDetail } from "@/lib/api";
import { findPlannedWorkshops, type PlannedWorkshopCandidate } from "@/lib/sanctionOrders";

function dateRange(row: PlannedWorkshopCandidate): string | null {
  const start = row.plannedStartDate?.slice(0, 10);
  const end = row.plannedEndDate?.slice(0, 10);
  if (start && end) return `${start} to ${end}`;
  return start ?? end ?? null;
}

/** The one line that identifies a plan row: craft, place and dates, whichever are filled. */
export function plannedRowSummary(row: PlannedWorkshopCandidate): string {
  return [
    row.craftName,
    [row.district, row.state].filter(Boolean).join(", ") || null,
    dateRange(row)
  ]
    .filter(Boolean)
    .join(" · ");
}

export function PlannedWorkshopPicker({
  picked,
  onPick,
  disabled
}: {
  picked: PlannedWorkshopCandidate | null;
  onPick: (row: PlannedWorkshopCandidate | null) => void;
  disabled?: boolean;
}) {
  const [number, setNumber] = useState("");
  const [rows, setRows] = useState<PlannedWorkshopCandidate[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function find() {
    const clean = number.trim();
    if (!clean) return;
    setBusy(true);
    setProblem(null);
    try {
      const answer = await findPlannedWorkshops(clean);
      setRows(answer.items);
      setTruncated(answer.truncated);
      const only = answer.items.filter((row) => row.pickable);
      // One pickable exact match is the ordinary case: pick it, the officer can still clear it.
      onPick(only.length === 1 && answer.items.length === 1 ? only[0]! : null);
    } catch (error) {
      setRows(null);
      const detail = (error as { payload?: { detail?: unknown } } | null)?.payload?.detail;
      setProblem(describeApiDetail(detail, "Could not search the annual plan. Try again."));
    } finally {
      setBusy(false);
    }
  }

  if (picked) {
    return (
      <div className="rounded-md border border-line-200 bg-surface-50 p-3">
        <p className="field-label">Planned workshop</p>
        <p className="mt-1 text-sm font-medium text-ink-900">
          {picked.workshopNo} · {picked.planYearLabel}
          {picked.plannedTitle ? ` · ${picked.plannedTitle}` : ""}
        </p>
        {plannedRowSummary(picked) ? (
          <p className="text-sm text-ink-700">{plannedRowSummary(picked)}</p>
        ) : null}
        <p className="mt-1 text-xs text-ink-500">Workshop Setup is filled from the plan.</p>
        <ActionGroup align="start" className="mt-2">
          <button
            type="button"
            className="field-button-secondary"
            disabled={disabled}
            onClick={() => onPick(null)}
          >
            Remove
          </button>
        </ActionGroup>
      </div>
    );
  }

  return (
    <div className="grid gap-2">
      <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
        <Field label="Planned workshop No. (optional)">
          <TextInput
            value={number}
            onChange={(event) => setNumber(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void find();
              }
            }}
            maxLength={120}
            placeholder="DPW/2026/017"
            disabled={disabled}
          />
        </Field>
        <ActionGroup>
          <button
            type="button"
            className="field-button-secondary"
            disabled={disabled || busy || !number.trim()}
            onClick={() => void find()}
          >
            {busy ? "Finding…" : "Find"}
          </button>
        </ActionGroup>
      </div>
      {problem ? <p className="text-sm text-error-600">{problem}</p> : null}
      {rows !== null && rows.length === 0 ? (
        <p className="text-sm text-ink-500">No planned workshop has exactly that number.</p>
      ) : null}
      {rows && rows.length > 0 ? (
        <ul className="grid gap-2">
          {rows.map((row) => (
            <li key={row.id} className="rounded-md border border-line-200 bg-card p-3">
              <p className="text-sm font-medium text-ink-900">
                {row.workshopNo} · {row.planYearLabel}
                {row.plannedTitle ? ` · ${row.plannedTitle}` : ""}
              </p>
              {plannedRowSummary(row) ? (
                <p className="text-sm text-ink-700">{plannedRowSummary(row)}</p>
              ) : null}
              {row.pickable ? (
                <ActionGroup align="start" className="mt-2">
                  <button
                    type="button"
                    className="field-button"
                    disabled={disabled}
                    onClick={() => onPick(row)}
                  >
                    Use this workshop
                  </button>
                </ActionGroup>
              ) : (
                <p className="mt-1 text-xs text-ink-500">{row.reason}</p>
              )}
            </li>
          ))}
        </ul>
      ) : null}
      {truncated ? <p className="text-xs text-ink-500">Showing the latest 20 plan years.</p> : null}
    </div>
  );
}
