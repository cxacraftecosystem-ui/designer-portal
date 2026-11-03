"use client";

import { useState } from "react";
import { CalendarRange, ExternalLink } from "lucide-react";

import { listAssignableDesigners } from "@/app/(protected)/officers/oversight";
import { WorkshopDesignerPicker } from "@/components/designworkshop/WorkshopDesignerPicker";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { describeApiDetail } from "@/lib/api";
import { designerCreateFields, namedDesignerTeam } from "@/lib/designWorkshops";

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
  const [chosen, setChosen] = useState<string[]>([]);
  const [lead, setLead] = useState("");
  const [craftId, setCraftId] = useState("");
  const linkedCraftId = entry?.craftId ?? null;

  const named = namedDesignerTeam({ chosen, lead });

  function reset() {
    setError(null);
    setChosen([]);
    setLead("");
    setCraftId("");
  }

  async function submit() {
    if (!entry || busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await promoteAnnualPlanEntry(entry.id, {
        ...designerCreateFields({ chosen, lead }),
        craftId: linkedCraftId ? undefined : craftId || undefined
      });
      reset();
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
        reset();
        onClose();
      }}
      busy={busy}
      role="alertdialog"
      title="Open this workshop"
      description="A planned entry becomes a real design & prototype workshop. This can only be done once."
      icon={<CalendarRange className="h-4 w-4" aria-hidden />}
      className="max-w-xl"
      footer={
        <>
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              reset();
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
        <p className="mb-3 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-red-800">
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

          {linkedCraftId ? null : (
            <div className="mt-4">
              <FieldBlock label="Craft">
                <CraftSelect
                  ariaLabel="Craft"
                  value={craftId}
                  placeholder={entry.craftName ? `Pick the craft for “${entry.craftName}”` : undefined}
                  disabled={busy}
                  onChange={(id) => setCraftId(id)}
                />
              </FieldBlock>
            </div>
          )}

          <div className="mt-4">
            <WorkshopDesignerPicker
              values={chosen}
              onChange={setChosen}
              lead={lead}
              onLeadChange={setLead}
              disabled={busy}
              fetchEligible={listAssignableDesigners}
            />
          </div>

          <p className="mt-3 text-sm leading-6 text-ink-muted">
            Everything above is copied onto the new workshop and into its stage 1.{" "}
            {named.team.length === 0 ? (
              <>
                <strong className="text-ink-900">No designer is named</strong>, so the designer
                section of stage 1 is left empty and shows as still to be filled in. Designers can be
                added later on the workshop&apos;s own screen.
              </>
            ) : (
              <>
                Everybody named above is given access to the workshop as it is created, and the
                designer named as the lead is the one whose profile is copied into stage 1 and stage
                3.
              </>
            )}{" "}
            Correcting the plan later corrects the directory and never the workshop.
          </p>
        </>
      ) : null}
    </FieldDialog>
  );
}
