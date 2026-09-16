"use client";

/**
 * OPEN THE REAL WORKSHOP THIS PLANNED ROW WAS ALWAYS FOR.
 *
 * ── THE DESIGNER PICKER THAT COULD NOT BE HERE, AND WHY IT NOW IS ───────────────────────────────
 *
 * This block argued at length that a picker could not go on this dialog, and the argument was cited
 * as binding precedent by two other features. **Its endpoint fact is still true; its conclusion is
 * not.** It is rewritten rather than deleted because it is the only record of a constraint that has
 * moved — and because the same constraint still binds, one tier down.
 *
 * STILL TRUE. `GET /design-workshops/eligible-viewers` is `Depends(require_admin)`, `require_admin`
 * is SET membership `{ADMIN, MASTER_ADMIN}`, and a MINISTRY_ADMIN — rank 48, the tier this directory
 * exists for — is refused by it. `WorkshopDesignerPicker` read that endpoint and only that endpoint,
 * so mounting it here would have given an administrator a picker that 403s on the one screen built
 * for them: the same trap as nesting this route under `/admin`, one component down.
 *
 * WHAT CHANGED. A SECOND door landed on a different prefix.
 * `GET /design-workshop-oversight/designers` is `Depends(require_workshop_assigner)` —
 * `OVERSIGHT_ASSIGNER_ROLES`, `{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}` — and its own docstring says
 * it is "THE SAME QUERY `GET /designers/directory` RUNS, AND A DELIBERATELY SMALLER PAYLOAD": four
 * keys, no roster columns, built for exactly this refusal. And the picker grew a `fetchEligible`
 * prop, so which door is read is the caller's to decide rather than the control's to own. The escape
 * hatch this comment used to leave open — "the day `eligible-viewers` grows a gate a ministry
 * administrator passes, the picker drops in here" — was satisfied by a SIBLING endpoint instead of
 * by that one, which is the single shape it did not anticipate, and the reason this had to be read
 * again rather than waited on.
 *
 * WHY THE PICKER CANNOT 403 HERE. Anyone who can open this dialog has already passed
 * `canManageAnnualPlan`, which is `hasRank(user, "MINISTRY_ADMIN")` — {MINISTRY_ADMIN, ADMIN,
 * MASTER_ADMIN}, exactly the three accounts `OVERSIGHT_ASSIGNER_ROLES` names.
 *
 * ⚠ **THE TWO AGREE TODAY BY DIFFERENT MECHANISMS**, and `lib/permissions.ts` says as much where
 * `OVERSIGHT_ASSIGNER_ROLES` is declared: this side is a rank FLOOR, that side is a SET that refuses
 * a Regional Director who outranks an Assistant Director. A twelfth tier landing above rank 48 would
 * pass the floor and be refused by the set. That is survivable rather than silent — the picker
 * renders its own "Unable to load the designers…" line and this dialog goes on promoting with no
 * designer named, which is precisely what it did before this change — but whoever adds such a tier
 * owes this paragraph a reading.
 *
 * ── THE OLD PRECEDENT STILL BINDS, ONE TIER DOWN. DO NOT READ THIS AS CLEARANCE. ────────────────
 *
 * An ASSISTANT DIRECTOR (42) and a REGIONAL DIRECTOR (45) are outside BOTH doors, and
 * `require_workshop_assigner` must not be widened to fit them: `OVERSIGHT_ASSIGNER_ROLES` leaves a
 * Regional Director out on purpose, because the supervised must not choose the supervisor. The
 * sanction-order register, whose audience is rank ≥ 42, therefore still has no designer list it may
 * read and still needs a door of its own.
 *
 * ── NAMING NOBODY IS STILL A REAL ANSWER, AND STILL THE COMMON ONE ──────────────────────────────
 *
 * The picker ships with nothing ticked, and a selection `namedDesignerTeam` resolves to nobody puts
 * NEITHER key on the wire — which is not quite the same claim as "nothing is ticked", and the
 * `named` note on that constant is where the difference is spelled. What resolving to nobody does,
 * so the common case stays a decision and not a gap: the workshop is created with its
 * craft, cluster, state, district, venue, dates and code seeded from the plan row, and the stage-1
 * DESIGNER block is left EMPTY. That is the correct empty — a blank required field is visible to the
 * completeness score and to the report warnings, while somebody else's name in it is visible to
 * nobody. The designers can still be named afterwards from the workshop's own viewers panel.
 *
 * ── AND THE WIRE WAS READY BEFORE THE SCREEN WAS ────────────────────────────────────────────────
 *
 * `POST /annual-plan/{id}/promote` has taken `designerUserId` and `designerUserIds` since it
 * shipped — the same two fields, with the same meanings and the same bounds, that
 * `POST /design-workshops` takes — and folds them through `named_designer_team` before
 * `assert_every_designer_may_be_named` and `attach_the_named_designers` run. Which of the two keys
 * goes out is `designerCreateFields`' decision, shared with the create form so that one rule is
 * spelled once: nobody → neither key, one designer → `designerUserId` alone, several → both.
 */

import { useState } from "react";
import { CalendarRange, ExternalLink } from "lucide-react";

import { listAssignableDesigners } from "@/app/(protected)/officers/oversight";
import { WorkshopDesignerPicker } from "@/components/designworkshop/WorkshopDesignerPicker";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
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
  /**
   * The designers this promotion names, in tick order, and which of them the report carries.
   *
   * HELD ON THE DIALOG AND NOT IN THE PICKER, WHICH IS WHY `reset()` EXISTS. `FieldDialog` renders
   * nothing at all while `open` is false, so the picker is mounted and unmounted with each
   * promotion and cannot carry anything between two of them — but this component is mounted for the
   * life of the page (`page.tsx` renders it outside every branch, with `entry` as its open state).
   * These two values are therefore the only things that could survive one planned row and arrive
   * pre-ticked on the next, which would be somebody else's designers on somebody else's workshop.
   */
  const [chosen, setChosen] = useState<string[]>([]);
  const [lead, setLead] = useState("");

  /**
   * WHO THIS PROMOTION ACTUALLY NAMES — read through the function the SUBMIT reads, never off
   * `chosen.length`.
   *
   * ⚠ `chosen.length === 0` IS NOT "NOBODY IS NAMED", AND THE GAP IS REACHABLE IN FOUR PRESSES.
   * {@link namedDesignerTeam}'s second rule is that a lead standing alone with nothing ticked IS the
   * team — deliberately, so a draft written before the picker was a multi-select does not lose the
   * designer it was opened for. So: tick two designers, choose one as the lead in the chooser that
   * then appears, untick both. `chosen` is empty and `lead` still holds an id, `designerCreateFields`
   * sends `designerUserId` for it, the server grants that account a viewer row and seeds stage 1
   * with their profile — and the sentence below used to answer that state with "**No designer is
   * named** — the designer block of stage 1 is left empty", directly under a panel already printing
   * "Stage 1, stage 3 and the report will carry <their name>".
   *
   * One resolver, one answer: `WorkshopDesignerPicker` computes its own lead line through this same
   * function for this same reason, and says so where it does. A screen that decides by a different
   * expression from the wire is a screen that can only agree with it by coincidence — and the fact
   * it would be wrong about is whose name a ministry reads off the report.
   */
  const named = namedDesignerTeam({ chosen, lead });

  /** Everything this dialog holds about ONE row, cleared on every way out of it. */
  function reset() {
    setError(null);
    setChosen([]);
    setLead("");
  }

  async function submit() {
    if (!entry || busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await promoteAnnualPlanEntry(entry.id, designerCreateFields({ chosen, lead }));
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
      /*
        STILL AN `alertdialog`, NOW THAT IT CARRIES A CONTROL. The act is irreversible — the
        description says so in as many words — and the role is what makes `backdropCloses` false in
        `FieldDialog`, which matters MORE since the picker landed than it did before: a stray click
        on the backdrop would otherwise throw away a designer selection built by hand.
      */
      role="alertdialog"
      title="Open this workshop"
      description="A planned row becomes a real design & prototype workshop. This can only be done once."
      icon={<CalendarRange className="h-4 w-4" aria-hidden />}
      // WIDER THAN THE DEFAULT `max-w-md`, for the picker: a search box, a notice line, a
      // multi-select trigger and the lead chooser do not read at 448px. `UploadPlanDialog`, the
      // other dialog on this screen, is already `max-w-xl` for the same reason.
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

          <div className="mt-4">
            {/*
              THE DOOR IS HANDED IN, AND IT IS THE ONLY ONE THIS AUDIENCE MAY READ — see this file's
              header for the endpoint argument in full. `listAssignableDesigners` is imported from
              the officers parcel and never edited from here; it is `GET
              /design-workshop-oversight/designers`, whose answer is already the four-key
              `{ users, truncated }` shape this prop is typed against, so it is handed over whole
              rather than wrapped.

              NO `offline` FLAG, DELIBERATELY. The picker takes one because the /design-workshops
              create form works offline and mints a local id; nothing on the annual plan does.
              Every call in `annualPlan.ts` is a bare `apiFetch` with no outbox behind it, so a
              promotion attempted with no connection fails as a promotion, not as a picker — and
              passing a flag that is always false would imply a story this screen does not have.

              THE PICKER'S OWN COPY SURVIVES THE MOVE, which is the one thing worth checking before
              reusing it: its sentences assume a workshop that does not exist yet ("stage 1 then
              carries whoever creates the workshop", "this one can still be started"). A promotion
              CREATES a workshop, so every one of them is true here. They are not true on a panel
              that renames the designer of a workshop already running, and that consumer is told to
              ask for copy props rather than fork the control.
            */}
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
                <strong className="text-ink-900">No designer is named</strong> — the designer block
                of stage 1 is left empty, which is the right empty: a blank required field is visible
                to the completeness score and to the report warnings, while somebody else&apos;s name
                in it is visible to nobody. Designers can still be added afterwards on the
                workshop&apos;s own screen.
              </>
            ) : (
              <>
                {/*
                  "NAMED above" AND NOT "TICKED above", which is the same distinction the branch
                  above turns on: with nothing ticked and a lead standing alone this sentence is
                  still the one that renders, and "everybody ticked" would name an empty set while
                  the wire named somebody. The panel above prints who, by name, in either case.
                */}
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
