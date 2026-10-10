"use client";

/**
 * WHICH STATES EACH REGIONAL DIRECTOR ANSWERS FOR ON THE ANNUAL PLAN — the Ministry Admin's half of
 * the scope.
 *
 * A Regional Director opens this screen narrowed to the rows of the states assigned here, and may
 * correct the remarks on those rows and on no others; with nothing assigned they see an empty plan.
 * The server decides both (`annual_plan.plan_scope`), so this panel only writes the assignment and
 * shows it. Mounted for an annual-plan manager only; the routes behind it refuse anybody else.
 *
 * Collapsed by default: it is set once a year, and the directory is what the screen is for.
 */

import { useCallback, useState } from "react";
import { MapPinned } from "lucide-react";

import { OFFLINE_STATES } from "@/components/forms/LocationFields";
import { describeApiDetail } from "@/lib/api";

import { listRegionalDirectors, setRegionalDirectorStates, type RegionalDirectorScope } from "./annualPlan";

function problemOf(caught: unknown, fallback: string): string {
  return describeApiDetail(
    (caught as { payload?: { detail?: unknown } })?.payload?.detail,
    caught instanceof Error && caught.message ? caught.message : fallback
  );
}

export function RegionalStatesPanel() {
  const [open, setOpen] = useState(false);
  const [directors, setDirectors] = useState<RegionalDirectorScope[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [adding, setAdding] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    try {
      const answer = await listRegionalDirectors();
      setDirectors(answer.items);
      setError(null);
    } catch (caught) {
      setError(problemOf(caught, "The Regional Directors could not be read."));
      setDirectors((current) => current ?? []);
    }
  }, []);

  async function save(director: RegionalDirectorScope, states: string[]) {
    setBusyId(director.id);
    setError(null);
    try {
      const answer = await setRegionalDirectorStates(director.id, states);
      setDirectors((current) =>
        (current ?? []).map((other) => (other.id === director.id ? { ...other, states: answer.states } : other))
      );
      setAdding((current) => ({ ...current, [director.id]: "" }));
    } catch (caught) {
      setError(problemOf(caught, "That change could not be saved."));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section className="panel mb-5 grid gap-3 p-4" data-testid="regional-states-panel">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 font-display text-sm font-bold text-ink-900">
            <MapPinned className="h-4 w-4 text-ink-500" aria-hidden />
            Regional Directors&apos; states
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            A Regional Director sees the rows of the states assigned here, and may correct the remarks on those rows
            and on no others. With no state assigned they see an empty plan. Uploading, exporting, opening a workshop,
            withdrawing and reinstating stay with you.
          </p>
        </div>
        <button
          type="button"
          className="field-button-secondary"
          aria-expanded={open}
          onClick={() => {
            const next = !open;
            setOpen(next);
            if (next) void load();
          }}
        >
          {open ? "Hide" : "Assign states"}
        </button>
      </div>

      {open && error ? (
        <p role="alert" className="rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600">
          {error}
        </p>
      ) : null}

      {open && directors == null ? <p className="text-sm text-ink-muted">Loading…</p> : null}

      {open && directors?.length === 0 ? (
        <p className="text-sm text-ink-muted">
          There is no Regional Director account. An account given the Regional Director role on Users appears here.
        </p>
      ) : null}

      {open && directors?.length ? (
        <ul className="grid gap-3">
          {directors.map((director) => {
            const busy = busyId === director.id;
            const choice = adding[director.id] ?? "";
            const offered = OFFLINE_STATES.filter((name) => !director.states.includes(name));
            return (
              <li key={director.id} className="grid gap-2 rounded-md border border-line-200 px-3 py-2">
                <p className="text-sm text-ink-900">
                  <span className="font-medium">{director.name || director.email}</span>
                  {director.name ? <span className="text-ink-muted"> · {director.email}</span> : null}
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  {director.states.length ? (
                    director.states.map((state) => (
                      <span
                        key={state}
                        className="inline-flex items-center gap-1 rounded-full bg-surface-50 px-2 py-0.5 text-xs text-ink-700 ring-1 ring-line-200"
                      >
                        {state}
                        <button
                          type="button"
                          className="font-medium text-ink-500 underline"
                          disabled={busy}
                          aria-label={`Remove ${state} from ${director.name || director.email}`}
                          onClick={() => void save(director, director.states.filter((other) => other !== state))}
                        >
                          remove
                        </button>
                      </span>
                    ))
                  ) : (
                    <span className="text-xs text-ink-muted">No state assigned — they see an empty plan.</span>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <label className="sr-only" htmlFor={`add-state-${director.id}`}>
                    Add a state for {director.name || director.email}
                  </label>
                  <select
                    id={`add-state-${director.id}`}
                    className="field-input max-w-xs"
                    value={choice}
                    disabled={busy}
                    onChange={(event) => {
                      const value = event.currentTarget.value;
                      setAdding((current) => ({ ...current, [director.id]: value }));
                    }}
                  >
                    <option value="">Choose a state to add…</option>
                    {offered.map((name) => (
                      <option key={name} value={name}>
                        {name}
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    className="field-button-secondary"
                    disabled={busy || !choice}
                    onClick={() => void save(director, [...director.states, choice])}
                  >
                    {busy ? "Saving…" : "Add"}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}
