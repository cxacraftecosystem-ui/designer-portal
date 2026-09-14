"use client";

/**
 * OPEN THE REAL WORKSHOP THIS PLANNED ROW WAS ALWAYS FOR.
 *
 * ── WHY THERE IS NO DESIGNER PICKER ON THIS DIALOG ──────────────────────────────────────────────
 *
 * `POST /annual-plan/{id}/promote` accepts `designerUserId` and `designerUserIds` — the same two
 * fields, with the same meanings and the same bounds, that `POST /design-workshops` takes — and
 * this dialog deliberately sends neither.
 *
 * The reason is the one this whole feature keeps running into: **`GET
 * /design-workshops/eligible-viewers` is `Depends(require_admin)`, and `require_admin` is SET
 * membership `{ADMIN, MASTER_ADMIN}`.** A MINISTRY_ADMIN — rank 48, the tier this directory exists
 * for — is refused by it. `WorkshopDesignerPicker` reads that endpoint, so putting it here would
 * give an administrator a picker that 403s, on the one screen built for them: the same trap as
 * nesting this route under `/admin`, one component down.
 *
 * WHAT PROMOTING WITHOUT A NAME ACTUALLY DOES, so the omission is a decision and not a gap: the
 * workshop is created with its craft, cluster, state, district, venue, dates and code seeded from
 * the plan row, and the stage-1 DESIGNER block is left EMPTY. That is the correct empty — a blank
 * required field is visible to the completeness score and to the report warnings, while somebody
 * else's name in it is visible to nobody. Naming the designers is then the viewers panel's job on
 * the workshop itself, which is where an admin does it today anyway.
 *
 * The two fields stay on the wire, tested and bounded, so that the day `eligible-viewers` grows a
 * gate a ministry administrator passes, the picker drops in here and the request body already
 * takes it.
 */

import { useState } from "react";
import { CalendarRange, ExternalLink } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { describeApiDetail } from "@/lib/api";

import { promoteAnnualPlanEntry, type AnnualPlanEntry, type PromoteResult } from "./annualPlan";

export function PromoteDialog({
  entry,
  onClose,
  onPromoted
}: {
  entry: AnnualPlanEntry | null;
  onClose: () => void;
  onPromoted: (result: PromoteResult) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!entry || busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await promoteAnnualPlanEntry(entry.id, {});
      onPromoted(result);
    } catch (caught) {
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "The workshop could not be opened."
        )
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={entry !== null}
      onClose={() => {
        if (busy) return;
        setError(null);
        onClose();
      }}
      busy={busy}
      role="alertdialog"
      title="Open this workshop"
      description="A planned row becomes a real design & prototype workshop. This can only be done once."
      icon={<CalendarRange className="h-4 w-4" aria-hidden />}
      footer={
        <>
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              setError(null);
              onClose();
            }}
            disabled={busy}
          >
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={busy}>
            <ExternalLink className="h-4 w-4" aria-hidden />
            {busy ? "Opening…" : "Open the workshop"}
          </button>
        </>
      }
    >
      {error ? (
        <p className="mb-3 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600">
          {error}
        </p>
      ) : null}

      {entry ? (
        <>
          <dl className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 text-sm">
            <div className="flex gap-2">
              <dt className="w-32 shrink-0 text-ink-muted">Workshop No.</dt>
              <dd className="font-medium text-ink-900">{entry.workshopNo}</dd>
            </div>
            {entry.plannedTitle ? (
              <div className="flex gap-2">
                <dt className="w-32 shrink-0 text-ink-muted">Title</dt>
                <dd className="text-ink-900">{entry.plannedTitle}</dd>
              </div>
            ) : null}
            {entry.venue || entry.district || entry.state ? (
              <div className="flex gap-2">
                <dt className="w-32 shrink-0 text-ink-muted">Where</dt>
                <dd className="text-ink-900">
                  {[entry.venue, entry.district, entry.state].filter(Boolean).join(" · ")}
                </dd>
              </div>
            ) : null}
            {entry.plannedStartDate ? (
              <div className="flex gap-2">
                <dt className="w-32 shrink-0 text-ink-muted">Dates</dt>
                <dd className="text-ink-900">
                  {entry.plannedStartDate}
                  {entry.plannedEndDate ? ` — ${entry.plannedEndDate}` : ""}
                </dd>
              </div>
            ) : null}
          </dl>

          <p className="mt-3 text-sm leading-6 text-ink-muted">
            Everything above is copied onto the new workshop and into its stage 1.{" "}
            <strong className="text-ink-900">No designer is named</strong> — the designer block of
            stage 1 is left empty, and the designers who will run it are added on the workshop&apos;s
            own screen. Correcting the plan later corrects the directory and never the workshop.
          </p>
        </>
      ) : null}
    </FieldDialog>
  );
}
