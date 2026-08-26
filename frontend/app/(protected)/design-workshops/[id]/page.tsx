"use client";

/**
 * One design workshop: its header, and the 22-stage index with completeness against each stage.
 *
 * THE LIST OF STAGES IS NOT WRITTEN HERE. It is `registry.stages`, fetched once per tab from
 * `GET /design-workshops/schema` and cached by version. A twenty-third stage, or a renamed one,
 * appears on this page — and on Android — with no client change, which is the entire reason the
 * field registry is served rather than duplicated.
 *
 * WHAT "COMPLETE" MEANS, and why the page says it out loud. `stage_completeness` scores BASIC-tier
 * fields only, because BASIC is the tier the report actually needs and the tiers exist so a
 * workshop held in a village without mains power can still produce one. A stage with no required
 * fields at all therefore reads 100%, not 0% — dividing by zero to decide whether a designer may
 * submit is how a stage becomes permanently unsubmittable. Nobody reading a progress bar guesses
 * that rule, so the legend under the list states it.
 *
 * THE LOCAL DRAFT IS READ FIRST. `lib/designWorkshopStore` holds the whole workshop in IndexedDB, so
 * this index renders — with real per-stage progress — on a laptop that has had no signal since the
 * tab was opened, and the server read that follows only refreshes it. Progress shown offline is
 * computed by `localStageCompleteness`, which mirrors the server's `stage_completeness` exactly: a
 * progress figure that needs a server to be honest is a progress figure that lies at the one moment
 * a designer uses it, which is deciding in a courtyard whether they can pack up.
 *
 * AND THIS IS WHERE THE WORKSHOP IS FINISHED. {@link SubmissionCard} is the deliberate final act —
 * "Mark complete" and "Submit", each writing its own status through `PATCH /design-workshops/{id}` —
 * and it is the ONE thing on this page that is not offline-first, for a reason its own header sets
 * out. It is never gated on completeness: requirement 12 asks for partial submission in as many
 * words, so the control is offered with required fields outstanding and NAMES how many.
 *
 * TWO THINGS THAT ACT NOW SAYS AND DID NOT, both because a reviewer found the card claiming coverage
 * it did not have:
 *
 * * WHICH ACT THE OUTSTANDING COUNT IS ABOUT. There are two refusable-sounding acts in this app and
 *   they are not the same one; {@link STAGE_CHECK_IS} and {@link WORKSHOP_STATUS_IS} name them, and
 *   this page and `/readiness` print those same two sentences rather than each paraphrasing them.
 * * WHAT THE REPOSITORY HAS NOT HEARD. The count is computed from the LOCAL draft, so it can read
 *   "No required field is outstanding" about answers only this browser holds. `unsentStages` — the
 *   figure the amber banner above already draws — is threaded into the card so the confirmation says
 *   so before the act, not after it.
 */

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { BadgeCheck, DraftingCompass, FileClock, FileText, Images, Layers, ListChecks, ListPlus, Loader2, QrCode, Send, Undo2, GitCompareArrows} from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { DictationConsentCard } from "@/components/designworkshop/DictationConsentCard";
import { WorkshopSearchPanel } from "@/components/designworkshop/WorkshopSearchPanel";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import {
  getDesignWorkshop,
  overallPercent,
  patchDesignWorkshop,
  type DwRegistry,
  type DwStage,
  type DwStageCompleteness,
  type DwStatus,
  type DwSummary
} from "@/lib/designWorkshops";
import {
  adoptServerDetail,
  ensureDraft,
  isLocalWorkshopId,
  loadCustomDefinition,
  loadDraft,
  loadRegistry,
  localCompleteness,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { ApiError } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canRunDesignWorkshops, isAdmin } from "@/lib/permissions";
import { readinessSummary, workshopReadiness, type WorkshopReadiness } from "@/lib/submissionReadiness";
import { neverReconciled } from "@/lib/workshopOpenability";
import { buildWorkshopSearchIndex, emptyWorkshopSearchIndex } from "@/lib/workshopSearch";
/*
  THE ONE IMPORT ON THIS PAGE THAT IS NOT A LIBRARY, AND IT IS DELIBERATE RATHER THAN LAZY.

  These two sentences are the shared vocabulary for the two acts a designer can confuse for each
  other, and the whole point of the repair that introduced them is that ONE definition serves both
  screens — two paraphrases of "a submit is refused"/"submitting is not blocked" is the defect. A
  `lib/` module is the right home; the lane that wrote this was allowed two files, so the definition
  sits on the readiness screen and this page reads it. The direction is not arbitrary: that module's
  imports are a subset of this one's, so nothing new lands in either route's bundle, whereas defining
  them here would drag `lib/workshopSearch` and two panels into a screen that renders none of them.
  Read the docstring above `STAGE_CHECK_IS` before changing either string — it carries the evidence
  that the per-stage refusal is REAL, which is what stops the wording drifting back to "a submit".
*/
import { STAGE_CHECK_IS, WORKSHOP_STATUS_IS } from "./readiness/page";

/**
 * The progress bar for one stage.
 *
 * Colour never carries the meaning on its own: the percentage is printed beside the bar and the
 * state is worded ("Complete", "3 of 7 required"), so the judgement survives colour-blindness,
 * greyscale printing and forced-colours mode. `success`/`amber` are the palette's two LITERAL
 * status colours and deliberately do not invert — "done" must read identically in both themes.
 */
function StageProgress({ score }: { score: DwStageCompleteness | undefined }) {
  const percent = score?.percent ?? 0;
  const complete = score?.isComplete ?? false;
  return (
    <div className="flex min-w-0 flex-1 items-center gap-3">
      <div className="h-1.5 w-full max-w-40 overflow-hidden rounded-full bg-field-200">
        <div
          className={complete ? "h-full rounded-full bg-success-600" : "h-full rounded-full bg-purple-700"}
          style={{ width: `${Math.max(0, Math.min(100, percent))}%` }}
        />
      </div>
      <span className="shrink-0 text-xs text-ink-500">
        {complete
          ? "Complete"
          : score
            ? `${score.requiredFilled} of ${score.requiredTotal} required`
            : "Not started"}
      </span>
    </div>
  );
}

/** One row of the stage index. */
function StageRow({
  workshopId,
  stage,
  score
}: {
  workshopId: string;
  stage: DwStage;
  score: DwStageCompleteness | undefined;
}) {
  const collectionCounts = Object.entries(score?.collectionCounts ?? {}).filter(([, count]) => count > 0);
  return (
    <li className="border-b border-line-200 last:border-b-0">
      <Link
        href={`/design-workshops/${workshopId}/stages/${stage.key}`}
        className="flex flex-col gap-2 px-4 py-3 transition hover:bg-surface-50 sm:flex-row sm:items-center sm:gap-4"
      >
        <span
          className={
            score?.isComplete
              ? "grid h-8 w-8 shrink-0 place-items-center rounded-full bg-purple-700 text-xs font-semibold text-white"
              : "grid h-8 w-8 shrink-0 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700"
          }
        >
          {stage.number}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2">
            <span className="font-medium text-ink-900">{stage.title}</span>
            {/* The source document's reviewer marked some stages as possibly droppable. Saying so
                is what lets a designer skip one on purpose rather than worry about the gap. */}
            {stage.optionalStage ? (
              <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">Optional stage</span>
            ) : null}
          </span>
          <span className="mt-0.5 block text-sm leading-6 text-ink-muted">{stage.purpose}</span>
          {collectionCounts.length ? (
            <span className="mt-1 block text-xs text-ink-500">
              {collectionCounts
                .map(([entityKey, count]) => {
                  const entity = stage.entities.find((candidate) => candidate.key === entityKey);
                  return `${count} ${(entity?.title ?? entityKey).toLowerCase()}`;
                })
                .join(" · ")}
            </span>
          ) : null}
        </span>
        <StageProgress score={score} />
      </Link>
    </li>
  );
}

/**
 * The draft's header in the shape this page already draws — everything except the id, which the
 * caller supplies because a workshop created offline has a local one and a synced one has the
 * server's.
 */
function draftHeader(draft: DwDraft): Omit<DwSummary, "id"> {
  return {
    title: draft.header.title,
    templateId: draft.header.templateId,
    status: draft.header.status,
    workshopCode: draft.header.workshopCode,
    scheme: null,
    craftName: draft.header.craftName,
    clusterName: draft.header.clusterName,
    state: draft.header.state,
    district: draft.header.district,
    venue: draft.header.venue,
    startDate: draft.header.startDate,
    endDate: draft.header.endDate,
    designerName: draft.header.designerName,
    implementingAgency: null,
    sponsor: null,
    notes: draft.header.notes,
    workshopId: draft.header.workshopId,
    // The artisan's answer as THIS DEVICE holds it, so the consent row states what is on record with
    // no connection. See `DwDraft.consent` — a local answer is what the screen reads and is NOT what
    // the server's gate reads, which is why the row says whether it has been sent.
    dictationConsent: draft.consent?.decision ?? "NOT_RECORDED",
    dictationConsentAt: draft.consent?.recordedAt ?? null,
    dictationConsentById: draft.consent?.recordedById ?? null,
    createdById: draft.ownerUserId ?? "",
    createdAt: new Date(draft.createdAt).toISOString(),
    updatedAt: new Date(draft.updatedAt).toISOString(),
    deletedAt: null
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The final submission
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHAT SUBMITTING ACTUALLY DOES, AND EVERY CLAUSE OF IT WAS READ OUT OF THE SERVER RATHER THAN
 * ASSUMED.
 *
 * This sentence is the most dangerous thing on the card, because a designer who believes a submitted
 * workshop is sealed will stop correcting it, and a designer who believes it was sent will stop
 * chasing the officer who never received it. So each clause names where it comes from:
 *
 * * **It does not lock anything.** The tokens `COMPLETE`, `SUBMITTED` and `ARCHIVED` appear in
 *   exactly two places in the whole of `backend/app` — the `DESIGN_WORKSHOP_STATUSES` frozenset that
 *   validates the PATCH body, and one comment. There is no gate anywhere that reads them.
 * * **Stages stay editable, and saving one does not un-submit it.** `save_stage` reads the status for
 *   one purpose only — `if workshop_status == "DRAFT": header["status"] = "IN_PROGRESS"` — and its own
 *   comment gives the reason in as many words: *"Correcting a typo in a submitted report should not
 *   un-submit it."* The permission helper every stage write goes through, `load_workshop_or_404`,
 *   tests `deletedAt` and the three ways in, and never the status.
 * * **Nothing is produced and nothing is sent.** `POST /{id}/report` is what makes the .docx or .pdf
 *   and it does not consult the status; no route, queue or notification in this repository reacts to
 *   a status change at all.
 *
 * What is left is a word on a record and a filter on a list — which is worth doing, and is worth
 * being honest about the size of. Stated in full here rather than summarised, because a designer only
 * reads this once and the confirmation dialog repeats the same string.
 */
/*
  THE STAGE COUNT IS READ, NEVER WRITTEN, and that is the whole of this function over the constant it
  replaces.

  It used to say "All 22 stages stay editable afterwards" as a literal. The list of stages is
  `registry.stages`, served from `GET /design-workshops/schema` precisely so a twenty-third stage
  needs no client change — and this sentence is printed inside the confirmation of every forward act
  and again at the foot of the card, which made it the one place a registry change would turn into a
  false promise to a designer at the moment they submit to a ministry. `readiness.stagesTotal` is
  `registry.stages.length` (`lib/submissionReadiness.ts:473`) and the readiness screen already prints
  it; the card is now a second reader of the same figure.

  NULL IS NOT ZERO. When the registry or the local draft could not be read there is no readiness
  object and therefore no count, and "All 0 stages stay editable" would be an absurd sentence in the
  one dialog that must not be absurd. The claim degrades to the form that needs no number and is
  exactly as true.
*/
function submissionScope(stagesTotal: number | null): string {
  const stages =
    stagesTotal === null
      ? "Every stage stays editable afterwards"
      : `All ${stagesTotal} stages stay editable afterwards`;
  return (
    `Marking a workshop complete or submitted does not lock it. ${stages} and saving one does not ` +
    "undo it — the repository checks only that a workshop has not been deleted, never what its status " +
    "says. It also produces and sends nothing: the .docx or .pdf is still made on the Report screen, " +
    "and no file leaves this system until somebody sends one. What changes is the word on this record, " +
    "and where the workshop appears in the status filter on the workshops list."
  );
}

/**
 * THE ACT IS REVERSIBLE, AND SAYING SO IS ONLY SAFE BECAUSE THE SERVER AGREES.
 *
 * `PATCH /design-workshops/{id}` copies `status` into the row through a plain field loop with no
 * transition table, no ordering rule and no one-way check: any of the five statuses may follow any
 * other, and `COMPLETE` and `SUBMITTED` are not gated differently from each other. So *Reopen for
 * editing* below is a real control and not a hopeful one.
 *
 * This is written as its own constant, beside {@link submissionScope}, because the brief for this
 * work put it sharply and correctly: an irreversible act presented as reversible is the worse error.
 * If a future deploy makes `SUBMITTED` one-way on the server, this string is the thing that has to
 * change first, and it is easier to find here than inside a dialog call.
 */
const SUBMISSION_IS_REVERSIBLE =
  "This can be undone. Reopen for editing, on this same card, puts the workshop back to In progress — " +
  "the repository lets any status follow any other, so nothing here is a one-way door.";

/** The five members of `DesignWorkshopStatus`, in the order a workshop travels through them. */
const DW_STATUSES: DwStatus[] = ["DRAFT", "IN_PROGRESS", "COMPLETE", "SUBMITTED", "ARCHIVED"];

/**
 * One forward or backward step this card offers, and the exact status it writes.
 *
 * **ONE BUTTON PER STATUS, NEVER ONE BUTTON PER INTENTION.** `DesignWorkshopStatus` models COMPLETE
 * and SUBMITTED as two separate members and the workshops list filters on them separately, so a
 * single "Finish" control would have to pick one of them on the designer's behalf and never tell them
 * which — and the designer who later filtered the list by Submitted and could not find their workshop
 * would have no way to discover why. Each button names its status and writes that status and nothing
 * else.
 */
type SubmissionAction = {
  status: DwStatus;
  label: string;
  icon: typeof Send;
  /** What this act means in the designer's terms, printed under the button row. */
  meaning: string;
  /** Primary styling for the act this card exists for; secondary for the rest. */
  primary: boolean;
};

const MARK_COMPLETE: SubmissionAction = {
  status: "COMPLETE",
  label: "Mark complete",
  icon: BadgeCheck,
  meaning: "The fieldwork is finished — nothing more is expected to be added.",
  primary: false
};

const SUBMIT: SubmissionAction = {
  status: "SUBMITTED",
  label: "Submit",
  icon: Send,
  meaning: "This is the version being handed on. Requirement 12 is explicit that it may be submitted part-filled.",
  primary: true
};

const REOPEN: SubmissionAction = {
  status: "IN_PROGRESS",
  label: "Reopen for editing",
  icon: Undo2,
  meaning: "Back to In progress, so the record does not claim to be finished while it is being worked on.",
  primary: false
};

/**
 * Which steps to offer from where.
 *
 * NEITHER FORWARD ACT IS A PREREQUISITE FOR THE OTHER, because the server does not make it one — and
 * that is why DRAFT and IN_PROGRESS offer both rather than making a designer press Mark complete to
 * reach Submit. From SUBMITTED only the way back is offered: demoting a submitted workshop to
 * *complete* is a distinction nobody has ever asked for, whereas reopening it is the thing somebody
 * needs the afternoon they spot an error.
 *
 * ARCHIVED IS DELIBERATELY NOT OFFERED AS A DESTINATION, and it is reachable — the same PATCH accepts
 * it, so an admin or an API client can put a workshop there. Archiving is not part of submitting and
 * nothing in this repository treats an archived workshop differently from any other, so a button for
 * it here would be a second, unasked-for feature whose only effect is a word. A workshop that ARRIVES
 * archived still gets the way out.
 */
function actionsFor(status: string): SubmissionAction[] {
  switch (status) {
    case "DRAFT":
    case "IN_PROGRESS":
      return [MARK_COMPLETE, SUBMIT];
    case "COMPLETE":
      return [SUBMIT, REOPEN];
    case "SUBMITTED":
    case "ARCHIVED":
      return [REOPEN];
    default:
      // A status this build has never heard of, sent by a newer server. Offering a step from it would
      // be guessing at a ladder we do not have; RULE 10 says the screen must say that rather than
      // draw nothing and read as a workshop with no controls.
      return [];
  }
}

/**
 * THE DELIBERATE FINAL SUBMISSION — requirement 12/13's second half, which had no client at all.
 *
 * ── THE DEFECT THIS CLOSES ──────────────────────────────────────────────────────────────────────
 *
 * Progressive saving already worked end to end: every stage saves independently, the local draft
 * lives in IndexedDB, and a stage save advances DRAFT → IN_PROGRESS exactly once. `save_stage`'s own
 * comment then says the later statuses *"are the designer's to set, through PATCH, and only theirs"*
 * — and no client offered it. `patchDesignWorkshop` had exactly one caller in the whole web app, the
 * outbox in `lib/designWorkshopStore.ts`, and that call sends a fixed list of header fields which
 * does not include `status`; the Android data layer writes no status either. So COMPLETE, SUBMITTED
 * and ARCHIVED were three statuses **the workshops list offered a filter for and nothing on any
 * surface could ever produce** — a filter that always returns nothing, over a submission act that
 * existed on the server and nowhere else.
 *
 * ── IT IS NOT GATED ON COMPLETENESS, AND THAT IS THE POINT OF REQUIREMENT 12 ─────────────────────
 *
 * *"Designers should be able to progressively fill and submit information as the workshop progresses
 * rather than being forced to complete the entire report in a single submission."* Partial submission
 * is the asked-for behaviour, not a loophole, and the server agrees — the PATCH consults no scorer.
 * So every button here is live with required fields outstanding. What the card does instead is COUNT
 * them, name the count in the confirmation, and link to `/readiness`, which already lists them field
 * by field with a link to each box.
 *
 * ── IT COUNTS NOTHING ITSELF ────────────────────────────────────────────────────────────────────
 *
 * The count is `workshopReadiness(...).blocking.length` and the sentence is `readinessSummary(...)`,
 * both from `lib/submissionReadiness`, whose header forbids a second opinion about whether a field is
 * filled in: *"the day it disagreed the designer would be told to go and fill in a field the Save
 * button was perfectly happy with."* This card is a reader of that module, exactly as the readiness
 * screen is.
 */
function SubmissionCard({
  workshopId,
  status,
  readiness,
  neverSent,
  headerUnsent,
  unsentStages,
  offline,
  onStatusChanged
}: {
  /** The id to PATCH — the server's, never a local draft id. */
  workshopId: string;
  /** The status as this screen currently believes it to be. */
  status: string;
  /** Null when the field registry or the local draft could not be read, so nothing can be counted. */
  readiness: WorkshopReadiness | null;
  /** True while this workshop exists only in this browser and has no server row to PATCH. */
  neverSent: boolean;
  /** True when this device holds header edits the repository has not heard, so the status may be stale. */
  headerUnsent: boolean;
  /**
   * How many stages hold answers or deletions the repository has never heard.
   *
   * THE CARD WAS BLIND TO THIS WHILE QUOTING A COUNT COMPUTED FROM IT. `readiness` comes from
   * `workshopReadiness(registry, draft, id)` — the LOCAL IndexedDB draft, which is the right source,
   * because the server's score reads lower than the stage a designer just filled in. The consequence
   * nobody had drawn out: type into stage 12, do not press Save stage (the stage form writes only to
   * IndexedDB until it is pressed), come back here, press Submit. The card truthfully printed "No
   * required field is outstanding" and truthfully reported success — about answers the repository has
   * never seen. The workshop then reads SUBMITTED to every other account while the fieldwork sits on
   * one laptop, which is the exact failure mode this repository's offline rules exist against.
   *
   * HANDED DOWN, NEVER RECOUNTED. The page computes this from `draft.stages` for the amber banner
   * above, with the same predicate `DraftSyncBanner`'s `outstanding()` uses, and a second count in
   * here would be a second opinion about what is unsent — the same mistake `lib/submissionReadiness`
   * exists to avoid about what is unfilled.
   *
   * IT DOES NOT BLOCK THE ACT. Requirement 12 is that submission is never blocked; this is a
   * disclosure at the decision point, not a gate.
   */
  unsentStages: number;
  /** The page's own verdict from its last read: the repository did not answer. */
  offline: boolean;
  /** The status the server confirmed, handed up so the page can render it. */
  onStatusChanged: (status: string) => void;
}) {
  const { user } = useAuth();
  const confirm = useConfirm();
  /** The status being written, so exactly one button spins and the rest disable. */
  const [busy, setBusy] = useState<DwStatus | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  const mayDecide = canRunDesignWorkshops(user);
  const token = (status ?? "").trim().toUpperCase();
  const known = (DW_STATUSES as string[]).includes(token);
  const actions = actionsFor(token);
  const outstanding = readiness ? readiness.blocking.length : null;
  /* One sentence, printed in two places — the dialog body and the card's footer — and they must not
     be able to disagree about how many stages there are. See {@link submissionScope}. */
  const scope = submissionScope(readiness ? readiness.stagesTotal : null);

  async function move(action: SubmissionAction) {
    const goingBack = action.status === "IN_PROGRESS";
    /*
      THE OUTSTANDING COUNT IS IN THE TITLE, NOT BURIED IN THE BODY, and it is the whole reason this
      confirmation exists. A designer submitting on the last afternoon of a fortnight is not asking
      "am I sure" — they are asking "how much of this is still blank", and a dialog that answers that
      in its first line is the difference between a considered part-submission and a surprise.

      IT NEVER REFUSES. There is no branch below that returns early on an incomplete workshop; the
      count changes the wording and nothing else.

      A COUNT THAT COULD NOT BE TAKEN IS SAID OUT LOUD rather than rendered as zero, which is the one
      answer that would be wrong in the dangerous direction: "no required field is outstanding" is
      exactly what a designer would want to hear and exactly what nobody here can promise when the
      field registry or this browser's copy of the workshop could not be read.
    */
    const shortfall =
      outstanding === null
        ? "How many required fields are still outstanding could not be counted on this device, so this " +
          "dialog cannot tell you. It is being recorded either way."
        : outstanding === 0
          ? "No required field is outstanding."
          : `${outstanding} required field${outstanding === 1 ? " is" : "s are"} still outstanding, and this ` +
            "workshop will be recorded that way. That is allowed — partial submission is what this is for.";

    /*
      SUBMITTING OVER WORK THE REPOSITORY HAS NEVER SEEN IS ALLOWED AND MUST NOT BE SILENT.

      This sentence exists because the one above it can read "No required field is outstanding" about
      a stage that has never left this browser — the count is scored on the local draft, which is the
      union of what the server holds and what has not been sent. The dialog is the last place a
      designer can be told; the amber banner at the top of the page is not enough, because a card that
      goes to the trouble of naming the unsent-HEADER case immediately below reads as having covered
      the unsent-STAGE case too.

      IT DOES NOT REFUSE, and there is no branch below that returns early on it. Requirement 12 is
      that submission is never blocked; being told what you are submitting over is not a block.

      Null for the way back, which sends nothing and claims nothing: reopening a workshop with unsent
      stages is the correct thing to do with unsent stages.
    */
    const unsent =
      goingBack || unsentStages === 0
        ? null
        : `${unsentStages} stage${unsentStages === 1 ? " is" : "s are"} saved on this device only, and this act ` +
          `does not send ${unsentStages === 1 ? "it" : "them"}: a status goes straight to the repository, while ` +
          "stage answers travel through the offline queue. So the count above is about answers this browser " +
          "holds and the repository may not — this workshop will read " +
          `${action.status.replace(/_/g, " ").toLowerCase()} to every other account while that fieldwork is still ` +
          "on this laptop. Recording it anyway is allowed. The amber banner at the top of this page is where that " +
          "work is listed, and where Sync now appears while it is waiting on the network.";

    const agreed = await confirm({
      title: goingBack
        ? "Reopen this workshop for editing?"
        : outstanding && outstanding > 0
          ? `${action.label} with ${outstanding} required field${outstanding === 1 ? "" : "s"} outstanding?`
          : // THE UNSENT COUNT TAKES THE TITLE ONLY WHEN NOTHING IS OUTSTANDING, AND THAT IS THE EXACT
            // CASE THE DEFECT WAS FOUND IN. With required fields outstanding, that count keeps the
            // headline — it is the reason this confirmation exists — and `unsent` names the stages in
            // the body. With NOTHING outstanding the old title was the blandest string this dialog can
            // produce, "Submit?", over the most misleading state it can be in: a workshop that reads
            // complete because the answers proving otherwise have never left this browser. Two counts
            // will not fit in one title, so the title carries whichever one is the surprise.
            unsentStages > 0
            ? `${action.label} with ${unsentStages} stage${unsentStages === 1 ? "" : "s"} unsent?`
            : `${action.label}?`,
      // `warning` for the two forward acts and never `danger`: `ConfirmDialog`'s own docstring
      // reserves amber for "decisions that are consequential but recoverable (denying a request, a
      // late submission)" — which is this act, named — and reserves danger for the destructive ones,
      // where initial focus moves to Cancel so no reflex Enter can delete anything. Nothing here
      // deletes anything and every step is reversible on the server, so putting the keyboard on
      // Cancel would be theatre; the danger rule stays where it belongs, on the workshop's soft
      // delete. `neutral` for the way back, which is the plain "are you sure" it looks like.
      tone: goingBack ? "neutral" : "warning",
      confirmLabel: action.label,
      body: goingBack ? (
        "The workshop goes back to In progress, so the record stops claiming to be finished while it is " +
        "still being worked on. Nothing is deleted, no stage changes, and Mark complete and Submit both " +
        "stay available."
      ) : (
        <>
          <span className="block">{shortfall}</span>
          {/* Between the count and the scope, and in the emphasised weight, because it is the clause
              that changes what the count MEANS. A designer who skims this dialog must not be able to
              skim past the one sentence saying the repository has not heard half of what is being
              recorded as finished. */}
          {unsent ? <span className="mt-2 block font-medium text-ink-900">{unsent}</span> : null}
          <span className="mt-2 block">{scope}</span>
        </>
      ),
      note: goingBack ? null : SUBMISSION_IS_REVERSIBLE
    });
    if (!agreed) return;

    setBusy(action.status);
    setProblem(null);
    setOutcome(null);
    try {
      /*
        THE SERVER'S ANSWER IS WHAT GOES ON SCREEN, NOT THE STATUS WE ASKED FOR. `PATCH` returns the
        updated `workshop_summary`, so `updated.status` is the row as it now stands — and reporting
        our own request back would be the "success for an act that did not happen" failure this
        repository's offline rules exist against, for the one case where a future server starts
        rewriting or refusing a transition.
      */
      const updated = await patchDesignWorkshop(workshopId, { status: action.status });
      onStatusChanged(updated.status);
      /*
        AND THE SUCCESS SENTENCE CARRIES IT TOO, because "Recorded on the repository" is precisely the
        sentence a designer stops reading after. The status genuinely did land; the stages did not, and
        this act sent none of them. Appended rather than replacing the confirmation of what DID happen:
        both halves are true and a designer needs both.
      */
      const stillHere =
        unsentStages === 0
          ? ""
          : ` ${unsentStages} stage${unsentStages === 1 ? " is" : "s are"} still saved on this device only — the ` +
            "status is on the repository, that fieldwork is not.";
      setOutcome(
        (goingBack
          ? "Reopened. The workshop is back to In progress on the repository."
          : `Recorded on the repository. This workshop now reads ${(updated.status ?? "")
              .replace(/_/g, " ")
              .toLowerCase()}. Every stage is still editable.`) + stillHere
      );
    } catch (err) {
      /*
        NO OUTBOX FOR THIS ONE, AND THE FAILURE SENTENCE HAS TO SAY SO.

        Every other act on this screen survives a dead connection because the local draft holds it —
        stages, the artisan's consent, photographs. A status change does not: the only writer of the
        draft header is `patchDraftHeader`, which stamps `headerDirtyAt` and arms the outbox's PATCH,
        and that PATCH sends a FIXED list of header fields with no `status` key in it. Routing a
        status through it would therefore never send the status AND would leave `headerDirtyAt` set,
        which makes `adoptServerDetail` refuse to refresh this workshop's header from the server for
        as long as it stands — freezing the chip on this device's guess and hiding every later status
        change. So this act is online-only by construction, and a designer who presses it in a
        courtyard is told that plainly rather than being shown a success they did not get.
      */
      if (isUnreachable(err)) {
        setProblem(
          "The repository could not be reached, so nothing was changed and the status is unchanged. " +
            "This one act needs a connection: unlike your stages, a status is not held in the offline " +
            "queue. Everything you have typed is still saved on this device — try again when you have signal."
        );
      } else {
        // `apiFetch` has already run FastAPI's `detail` through `describeApiDetail`, so `message` is a
        // sentence and not "[object Object]" — a 403 from `_require_designer`, or the 409 from
        // `load_workshop_or_404` on a workshop an admin has soft-deleted, both arrive readable.
        setProblem(
          err instanceof Error && err.message.trim()
            ? `The status was not changed: ${err.message}`
            : "The status was not changed, and the repository did not say why."
        );
      }
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel mb-5 grid gap-4 p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-sm font-medium text-ink-900">Submission</h2>
        {/* THE ONE STATUS CHIP ON THIS PAGE, AND IT IS HERE RATHER THAN IN THE DETAIL PANEL ABOVE
            ON PURPOSE. It used to sit in that panel's first row, which was fine while nothing could
            change it; now that a button can, the chip has to be beside the button. A designer who
            presses Submit and has to scroll to find out whether it took has not been confirmed
            anything — and had both chips stayed, they would have been able to DISAGREE, because this
            one reads the status the server just returned while the panel's read the local draft
            header, which `adoptServerDetail` deliberately declines to refresh while header edits are
            unsent. Two chips on one screen disagreeing about one record is the worse of the two. */}
        <StatusBadge status={known ? token : status} />
      </div>

      {readiness ? (
        <p className="text-sm leading-6 text-ink-700">{readinessSummary(readiness)}</p>
      ) : (
        // RULE 10: the count is missing, so the screen says the count is missing. Rendering nothing
        // here would read as "there is nothing outstanding", which is the one wrong answer.
        <p className="text-sm leading-6 text-amber-800">
          What is still outstanding could not be counted — the count needs the field list and this browser&apos;s copy
          of the workshop, and one of them could not be read. The buttons below still work; they simply cannot tell
          you how much is blank. Reloading this page is what fixes it — Ready to submit? is built from the same two
          things and would be just as blind, connection or no connection.
        </p>
      )}

      {outstanding && outstanding > 0 ? (
        /*
          TWO ACTS, TWO NAMES, AND THE SAME TWO SENTENCES THE READINESS SCREEN PRINTS.

          This block used to open "Submitting is not blocked by this." while `/readiness` said of the
          identical count "A submit is refused while any of these is empty". Both were true. They are
          about DIFFERENT ACTS — a `PATCH` of the workshop's status, which consults no scorer, and a
          `PUT` of one stage with `submit: true`, which 422s that stage — and neither sentence said
          which, so the two screens contradicted each other about the only number a designer uses to
          decide whether they can pack up. The definitions live in one place now, on the readiness
          screen, and both screens render them verbatim; see {@link STAGE_CHECK_IS}.

          THIS SIDE LEADS WITH THE WORKSHOP'S STATUS because the reader is standing at those two
          buttons asking "does this stop me?" — and the answer, still, is no.

          THE LINK IS STILL THE POINT. The count on its own sends a designer back through the stage
          index to find what it is counting; `/readiness` names every field and links to the box.
        */
        <div className="grid gap-1.5 text-sm leading-6 text-ink-700">
          <p>{WORKSHOP_STATUS_IS}</p>
          <p>
            {STAGE_CHECK_IS}{" "}
            <Link href={`/design-workshops/${workshopId}/readiness`} className="font-medium text-purple-700 underline">
              See the {outstanding} outstanding field{outstanding === 1 ? "" : "s"}
            </Link>{" "}
            if you would rather fill them in first.
          </p>
        </div>
      ) : null}

      {!known ? (
        // A status from a newer server. Say what is on the record, and that this build has no step to
        // offer from it — a card with no buttons and no sentence reads as broken.
        <p className="text-sm leading-6 text-amber-800">
          This workshop&apos;s status is one this version of the app does not know, so it offers no next step from
          it. The word above is what the repository holds. Reload the page; if it persists, this browser needs a
          newer build than the server is running against.
        </p>
      ) : neverSent ? (
        // A workshop created offline that has never reached the repository. There is no row to PATCH,
        // so a button here would be a button that cannot work — and a SENTENCE, not a disabled
        // control, for the reason `DictationConsentCard` gives: a greyed control refuses a press
        // without saying why, which is how somebody concludes the app is broken.
        <p className="text-sm leading-6 text-ink-700">
          This workshop was created on this device and has not reached the repository yet, so there is nothing there
          to mark complete or submit. Everything you have typed is saved here. Sync it from the workshops list first —
          the status can be set the moment it lands.
        </p>
      ) : !mayDecide ? (
        // The affordance, matching the server's own `_require_designer` set. Not the boundary: the
        // route refuses this account whether or not this button is drawn.
        <p className="text-sm leading-6 text-ink-700">
          Only the designers running this workshop, or an administrator, can mark it complete or submit it. You can
          read it and its stages; ask whoever is running it to record the submission.
        </p>
      ) : (
        <>
          <div className="flex flex-wrap gap-2">
            {actions.map((action) => {
              const Icon = action.icon;
              const running = busy === action.status;
              return (
                <button
                  key={action.status}
                  type="button"
                  className={action.primary ? "field-button" : "field-button-secondary"}
                  // Disabled only while a write is in flight, and never on incompleteness — see this
                  // component's header. Two statuses cannot be written at once.
                  disabled={busy !== null}
                  onClick={() => void move(action)}
                >
                  {running ? (
                    <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                  ) : (
                    <Icon className="h-4 w-4" aria-hidden />
                  )}
                  {running ? "Recording…" : action.label}
                </button>
              );
            })}
          </div>
          <dl className="grid gap-1">
            {actions.map((action) => (
              <div key={action.status} className="text-xs leading-5">
                {/* What each button MEANS, in the designer's terms, because two adjacent verbs the
                    server treats identically are otherwise indistinguishable from each other. */}
                <dt className="inline font-medium text-ink-700">{action.label}: </dt>
                <dd className="inline text-ink-500">{action.meaning}</dd>
              </div>
            ))}
          </dl>
        </>
      )}

      {offline && mayDecide && known && !neverSent ? (
        // Stated BEFORE the press rather than only in the failure, because the press costs the
        // designer a confirmation dialog they can already be told is going to fail.
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          There was no connection when this page last read the repository. A status change is the one act on this
          screen that is not held in the offline queue, so it will be refused until there is signal.
        </p>
      ) : null}

      {unsentStages > 0 && mayDecide && known && !neverSent ? (
        /*
          STATED BEFORE THE PRESS, beside the offline warning above and for the same reason: a
          designer is owed the fact that changes what the act means before they spend a confirmation
          on it. Gated on there being an act to press at all — with no button on this card the
          page-level banner above has already said the same number and this would be noise.

          AMBER AND A FULL PANEL, not the quiet grey of the header line below it, because §12.11
          chooses the treatment by MEANING and these two facts do not mean the same thing: an unsent
          header makes the CHIP stale, which is recoverable by waiting, while unsent stages make a
          submission a claim about work nobody else can see. Not red: nothing has gone wrong and
          nothing here is refused.
        */
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          {unsentStages} stage{unsentStages === 1 ? " is" : "s are"} saved on this device only. Marking this workshop
          complete or submitting it records the WORKSHOP&apos;s status on the repository and sends none of that work,
          so the workshop would read finished to everybody else while those answers are still in this browser. The act
          is not blocked and the confirmation says this again; the amber banner at the top of this page is where that
          work is listed and sent.
        </p>
      ) : null}

      {headerUnsent ? (
        // The status shown can genuinely lag the repository here, and only this flag reveals it —
        // `adoptServerDetail` keeps the LOCAL header while `headerDirtyAt` is set, precisely so a
        // background read cannot overwrite an unsent edit, which also means it does not refresh the
        // status. Silence would let the chip quietly contradict the repository.
        <p className="text-xs leading-5 text-ink-500">
          This device is holding edits to this workshop&apos;s title or notes that the repository has not heard yet,
          so the status above is the one this browser last knew. It is refreshed once those edits are sent.
        </p>
      ) : null}

      {/*
        BOTH REGIONS ARE MOUNTED FROM FIRST PAINT, AND THAT IS THE WHOLE FIX.

        Assistive technology only announces mutations inside a region THAT ALREADY EXISTED; a live
        region created together with its first message is silently dropped by most screen readers.
        `Toast`'s viewport is the precedent and its own header says so in as many words — it renders
        the region with an empty queue for exactly this reason. Here, `problem` had no live role at
        all and `outcome` had `role="status"` on an element that did not exist until it had something
        to say, so pressing Submit with no connection put a red sentence on screen that was never
        spoken to anybody, and the success that a sighted designer reads as their receipt was
        announced only by luck.

        THE TWO TREATMENTS ARE CHOSEN BY MEANING (§12.11), NOT BY SYMMETRY. `problem` is a failure the
        designer has to act on — the status was NOT changed and nothing is queued to retry it — so it
        is `role="alert"`, assertive, and worth interrupting a reader for. `outcome` is a notice that
        something they asked for happened, so it is `role="status"`, polite, and must not interrupt.

        `sr-only` RATHER THAN AN EMPTY BOX. The element is always rendered; when it has nothing to say
        it is absolutely positioned and 1×1, so it is in the accessibility tree and contributes no row
        to this `grid gap-4` panel — an always-visible empty `<p>` would add two gaps of dead space
        under the card. `design-review/page.tsx:936` and `CustomSectionsEditor.tsx:801` use the same
        idiom. It must stay a class swap on ONE element: swapping in `hidden` (`display: none`) or
        remounting the node would take the region out of the accessibility tree and put this defect
        straight back.
      */}
      <p
        role="alert"
        aria-live="assertive"
        className={
          problem
            ? "rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700"
            : "sr-only"
        }
      >
        {problem}
      </p>
      <p
        role="status"
        aria-live="polite"
        className={
          outcome
            ? "rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm leading-6 text-success-600"
            : "sr-only"
        }
      >
        {outcome}
      </p>

      <p className="text-xs leading-5 text-ink-500">{scope}</p>
    </section>
  );
}

export default function DesignWorkshopStagesPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { id } = use(params);
  // Only to decide whether the admin-only provenance link is offered. The route and the
  // endpoint are both gated independently; this is the affordance, not the boundary.
  const { user } = useAuth();

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  /** The server's own schema digest for this record, when it could be read. */
  const [serverSchemaVersion, setServerSchemaVersion] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  /**
   * The repository refused to hand this workshop over, and this browser has never held a copy of it.
   *
   * WHY IT IS A SEPARATE STATE FROM `error`, AND WHY IT GATES THE WHOLE PAGE. Audit 2026-08-15
   * (MAJOR, frontend). `ensureDraft(id)` below FABRICATES a local draft for any id it is handed —
   * no server call, no ownership check — so `draft` is non-null within milliseconds of mount and
   * every render gate on this page keys on `draft`, not on `error`. A designer forwarded a
   * colleague's link, or one who mistyped a cuid, got a 404 from the API and then a complete,
   * convincing workshop: "Untitled design workshop", 0%, a status badge, twenty-two clickable stage
   * rows and the Cards & tags / Import photographs / Report buttons, with one red line above it.
   * Reading 0% as "not started yet" she opened stage 4 — where the same fabricated draft makes the
   * stage form seed itself, clear `loading` and render fully editable — and typed a day's interview
   * into a record that will never be accepted. Nor is it recoverable by browsing: the list page
   * prepends a local draft only when it has no `remoteId`, and this one has the route's.
   *
   * The 404 is the ONLY status that raises this. `isUnreachable` and 5xx both mean "the repository
   * did not answer about this workshop", where the local copy is the right thing to show and the
   * amber offline banner is the right thing to say. A 404 means the repository answered, and
   * `load_workshop_or_404` deliberately gives the same 404 for "no such record" and "not one this
   * account may open" — so the sentence below must stay ambiguous between them, exactly as the
   * server is.
   *
   * The second half of the test is what keeps a genuine offline-created workshop safe: a draft that
   * has ever been reconciled (`lastSyncedAt`, or any stage with `serverLoadedAt`) holds real
   * fieldwork read from the repository, and a later 404 — an admin soft-deleted it — must not blank
   * a screen that is the designer's only copy of it.
   */
  const [unopenable, setUnopenable] = useState(false);
  /** Who recorded the consent, as the server resolved it. Null = this screen cannot name them. */
  const [consentByName, setConsentByName] = useState<string | null>(null);
  /**
   * The status the SERVER confirmed on this page's own PATCH, held apart from the draft.
   *
   * HELD APART FOR THE SAME REASON `consentByName` IS: the draft is this device's copy, and this is a
   * fact only the server can state. It is not written back into the draft header on purpose —
   * `patchDraftHeader` is the only writer of that header, it stamps `headerDirtyAt`, and the outbox
   * PATCH it arms sends a fixed list of header fields that has no `status` key. So a status pushed
   * through it would never reach the server AND would make `adoptServerDetail` decline to refresh
   * this workshop's header from the repository for as long as the flag stood — freezing the chip and
   * hiding every later status change. Null until this session changes one, after which it wins over
   * the draft's copy, which is by then knowingly stale.
   */
  const [confirmedStatus, setConfirmedStatus] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      let loadedRegistry: DwRegistry | null = null;
      try {
        const loaded = await loadRegistry();
        if (cancelled) return;
        loadedRegistry = loaded.registry;
        setRegistry(loaded.registry);
      } catch (err) {
        if (cancelled) return;
        // With no registry there is no stage list at all, so this one IS fatal — but say what it is
        // rather than rendering twenty-two empty rows and letting a designer conclude the workshop
        // is empty.
        setError(
          err instanceof Error
            ? `The list of stages could not be loaded and this browser has no saved copy of it: ${err.message}`
            : "The list of stages could not be loaded and this browser has no saved copy of it."
        );
        return;
      }

      // THE LOCAL COPY FIRST. It renders the whole index with no network, and it renders faster than
      // a request when there is one.
      const local = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
      if (cancelled) return;
      if (local) setDraft(local);

      if (isLocalWorkshopId(id) && local && !local.remoteId) {
        // Nothing to reconcile against: this workshop has never been to the server. That is a
        // legitimate state for the whole of a fortnight, not an error.
        return;
      }

      try {
        const detail = await getDesignWorkshop(local?.remoteId ?? id);
        if (cancelled) return;
        setServerSchemaVersion(detail.schemaVersion);
        // The single-record read is the ONLY place the acceptor's name is resolved — `consent_keys`
        // leaves it out of the list because it would be a name lookup per row in a paged endpoint.
        // Held apart from the draft because the draft is this device's copy and a name is not on it.
        setConsentByName(detail.dictationConsentByName ?? null);
        const merged = await adoptServerDetail(detail, loadedRegistry);
        if (cancelled) return;
        /*
          THE WORKSHOP'S OWN QUESTIONS ARE READ HERE TOO, AND WITHOUT THIS THE INDEX WOULD DISAGREE WITH
          THE STAGE FORM ABOUT ITS OWN ARITHMETIC.

          `localCompleteness` scores the designer's required custom questions off `draft.customDefinition`
          — that is what taught every reader of it at once, including the readiness screen. But the copy on
          the draft is only written where a definition is actually fetched, and until now that was the
          stage form alone: a designer who opened this index before opening any stage would have seen
          twenty-two bars counting the registry's fields only, then watched one of them drop when they came
          back from a stage. Two arithmetics in one workshop, minutes apart, with nothing on screen to say
          why — which is exactly the failure the server carries `customSchemaVersion` beside its scores to
          prevent.

          Not awaited: it refreshes numbers already on screen and its failure is swallowed into a source of
          "unknown", so it cannot delay or fail the index. The draft is re-read afterwards because the
          definition is written to the same record — reading it from `merged` would show the bars scored
          against a definition that had just been superseded on disk.
        */
        const target = merged ?? local;
        if (target) {
          void loadCustomDefinition(target).then(async (held) => {
            if (cancelled || !held.definition) return;
            const refreshed = await loadDraft(target.localId);
            if (!cancelled && refreshed) setDraft(refreshed);
          });
        }
        setDraft(target);
        setError(null);
      } catch (err) {
        if (cancelled) return;
        // `isUnreachable`, NOT `isTransient` — the same split the list page and the stage form now
        // make. `isTransient` answers "is it worth retrying" and counts every 5xx as yes, so a
        // repository that answered and then failed raised the "there is no connection" banner and
        // sent the designer to look at their signal for a fault the server had already reported.
        if (isUnreachable(err)) {
          // A workshop already drawn from the local copy must not be replaced by an error box.
          setOffline(true);
          if (!local) setError("There is no connection and this browser has no copy of this workshop.");
          return;
        }
        // A 404 over a draft this browser has never reconciled means the record was FABRICATED by
        // `ensureDraft` and there is nothing behind it. See {@link unopenable} — everything on this
        // page is gated on it, because a red line above a complete-looking workshop was read as a
        // transient glitch and typed into.
        if (err instanceof ApiError && err.status === 404 && (!local || neverReconciled(local))) {
          setUnopenable(true);
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
      }
    })();
    // The `cancelled` flag is the right race guard for a one-shot mount effect (a generation
    // counter is for a list that refetches on filter changes, an AbortSignal for a fetch that
    // accepts one). Without it, navigating away mid-flight sets state on an unmounted tree.
    return () => {
      cancelled = true;
    };
  }, [id]);

  const stages = registry?.stages ?? [];
  /**
   * How many stages there are, or null while nobody knows yet.
   *
   * NULL AND NOT ZERO, deliberately — the same distinction `submissionScope` draws two hundred lines
   * up, and the reason `items === null` and `items === []` are kept apart everywhere in this app. An
   * empty registry and an unread one are different facts, and a sentence built on the first while the
   * second is true reads as a workshop with no stages in it.
   *
   * Read off `registry` rather than off `readiness`, which is also in scope: `readiness` is null
   * whenever the local draft could not be read, and the number of stages is a property of the
   * REGISTRY alone. Taking it from readiness would make the label go vague on a designer whose
   * IndexedDB was cleared, for a count that had nothing to do with their draft.
   */
  const stageCount = registry ? registry.stages.length : null;
  /**
   * Progress, scored against what is on THIS DEVICE.
   *
   * Deliberately the local figure rather than the server's, even when both are available: the local
   * draft is the union of what the server holds and what has not been sent yet, so the server's
   * score would read lower than the stage a designer just filled in and would tell them to go and
   * do work they have already done.
   */
  const completeness = useMemo(
    () => (registry && draft ? localCompleteness(registry, draft) : {}),
    [registry, draft]
  );
  const percent = overallPercent(completeness);
  /**
   * The search index over everything this device holds for this workshop.
   *
   * Built ONCE per draft load and not per keystroke — measured at 40–80ms for the flagship
   * workshop's 1,393 written answers, which is a fraction of the IndexedDB read that produced the
   * draft in the first place, and it buys a query that answers in under 4ms. It is memoised on the
   * same two objects the progress bars are, so a save that replaces the draft rebuilds it and
   * nothing else does.
   */
  const searchIndex = useMemo(
    () => (registry && draft ? buildWorkshopSearchIndex(registry, draft) : emptyWorkshopSearchIndex()),
    [registry, draft]
  );
  /**
   * What still blocks a submission, assembled by `lib/submissionReadiness` and by nothing here.
   *
   * Memoised on the same two objects the progress bars are, so a stage save that replaces the draft
   * recounts it and nothing else does. Null while either input is missing — the submission card then
   * says the count is unknown rather than implying it is zero.
   */
  const readiness = useMemo(
    () => (registry && draft ? workshopReadiness(registry, draft, id) : null),
    [registry, draft, id]
  );
  /** The header as the local copy holds it, which is the server's whenever nothing is unsent. */
  const detail: DwSummary | null = draft ? { ...draftHeader(draft), id: draft.remoteId ?? draft.localId } : null;
  const unsentStages = draft
    ? Object.values(draft.stages).filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0).length
    : 0;
  /**
   * This workshop exists only in this browser: no repository row, so nothing to PATCH a status onto.
   *
   * The SAME test the mount effect uses to decide there is nothing to reconcile against, and
   * deliberately not `draft.remoteId === null` — `ensureDraft` stamps `remoteId` with whatever id it
   * was handed, so that test is the trap `neverReconciled` exists to warn about.
   */
  const neverSent = draft ? isLocalWorkshopId(id) && !draft.remoteId : false;

  if (unopenable) {
    /*
      A DEAD END, AND DELIBERATELY NOT A WORKSHOP WITH A WARNING ON IT.

      Returning early — before the header's eight action links, before the detail panel, before the
      search panel and before the `<ol>` of twenty-two stage rows — is the whole fix. Rendering any
      of those beside a message is what produced the defect: the message read as a transient glitch
      and the workshop below it read as real, so a designer opened stage 4 and typed into it.

      THE WORDING STAYS AMBIGUOUS BETWEEN THE TWO CAUSES because the server's is. `load_workshop_or_404`
      answers the identical 404 for "no such record" and "not one this account may open", with a
      comment explaining that it will not distinguish them — telling a designer which one it is would
      confirm the existence of a record she may not see. "Ask the designer who sent you the link" is
      the remedy for both, and it is a remedy she can act on today rather than a status code.
    */
    return (
      <>
        <PageHeader
          title="Design workshop"
          description="This link could not be opened."
          icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
          actions={
            <Link href="/design-workshops" className="field-button-secondary">
              All design workshops
            </Link>
          }
        />
        <section className="panel grid gap-3 p-4">
          <p className="text-sm font-medium text-ink-900">
            There is no design workshop at this address that this account can open.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            Either no such workshop exists, or it belongs to another designer and has not been shared with you. Nothing
            has been created here and nothing you type would be saved, so this page stops rather than offering you an
            empty workshop to fill in. If a colleague sent you this link, ask them to add you as a viewer of their
            workshop — an administrator can also do it — and then open the link again.
          </p>
        </section>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={detail?.title ?? "Design workshop"}
        description={
          detail
            ? [detail.craftName, detail.clusterName, detail.state].filter(Boolean).join(" · ") ||
              "Craft, cluster and place are filled in from stage 1."
            : "Loading the workshop record…"
        }
        icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
        actions={
          <>
            {/* Tags are printed once, early, for the whole workshop, so the control belongs to the
                workshop and not to stage 13: a designer looking for them is looking for "this
                workshop", not for the stage that happens to hold the prototype rows. */}
            <Link href={`/design-workshops/${id}/codes`} className="field-button-secondary">
              <QrCode className="h-4 w-4" aria-hidden />
              Cards &amp; tags
            </Link>
            {/* THE PAGE EXISTED FOR A DAY WITH NOTHING POINTING AT IT. It shipped with its own route
                and a nav entry for the POOL round (`/design-review`), but the per-workshop half was
                reachable only by typing the URL with a workshop id in it — the wave that built it
                could not add this link because this file belonged to another unit, and the gap went
                straight past a green deploy. Added 2026-08-23, after the owner went looking for it.

                Belongs to the WORKSHOP rather than to a stage even though sketches live in stage 11
                and prototypes in stage 13: the review round spans both, and ranking one against the
                other is the whole point. Hanging it off either stage would ask a designer to pick a
                stage before they could compare across them. */}
            <Link href={`/design-workshops/${id}/sketches-and-prototypes`} className="field-button-secondary">
              <DraftingCompass className="h-4 w-4" aria-hidden />
              Sketches &amp; prototypes
            </Link>
            {/* Belongs to the WORKSHOP, not to any one stage: a camera dump spans the whole
                fortnight and the whole point of the intake is that it decides which stage each
                photograph goes to. Hanging it off a stage would ask the designer for the answer the
                feature exists to produce. */}
            <Link href={`/design-workshops/${id}/photos`} className="field-button-secondary">
              <Images className="h-4 w-4" aria-hidden />
              Import photographs
            </Link>
            {/* Belongs to the WORKSHOP for the same reason the photo intake does: a layer's chain
                starts at a recording or a photograph hanging off an audio or image field in any of
                the 22 stages, and one recording's transcript, its cleaned rewrite and a summary of
                that are one chain wherever the clip was captured. It is deliberately NOT on the
                report screen — see that page's header for the argument, the short form of which is
                that everything on the report screen is a per-file choice that writes nothing, and an
                acceptance puts a person's name on model output for good. */}
            <Link href={`/design-workshops/${id}/ai-layers`} className="field-button-secondary">
              <Layers className="h-4 w-4" aria-hidden />
              AI layers
            </Link>
            {/* Belongs to the WORKSHOP because a definition IS one: the server replaces the whole set in
                one write so that "one definition, one digest" stays atomic, and a section on stage 11 and
                another on stage 17 are one instrument sharing one digest. It is also the only place the
                answer state for every stage is in the same tab, which is what lets the editor say whether
                an edit will retire a question or delete it BEFORE the button is pressed. */}
            <Link href={`/design-workshops/${id}/custom-sections`} className="field-button-secondary">
              <ListPlus className="h-4 w-4" aria-hidden />
              This workshop&apos;s own questions
            </Link>
            {/* The question this whole index is read to answer, answered directly. The list below
                shows 22 progress bars; what a designer chasing a submission actually wants is the
                four fields inside them, and finding those by opening every stage is what this
                replaces. Placed before Report because it is what decides whether generating one is
                worth doing yet. */}
            <Link href={`/design-workshops/${id}/readiness`} className="field-button-secondary">
              <ListChecks className="h-4 w-4" aria-hidden />
              Ready to submit?
            </Link>
            {/* The record of what has ALREADY been sent, beside the control for producing the next
                file. A report submitted to a ministry is revised three or four times, and the
                question asked months later — "did the cost sheet change before you resubmitted?" —
                is about the files that exist, not the one being made now. */}
            <Link href={`/design-workshops/${id}/report/history`} className="field-button-secondary">
              <FileClock className="h-4 w-4" aria-hidden />
              Report history
            </Link>
            {/* ADMIN ONLY, AND HIDDEN RATHER THAN GREYED for the reason the create control is: a
                designer has lost nothing by not having this — every per-field stamp still renders
                under their own boxes on every stage — so a disabled control would advertise a
                capability they do not need and cannot get. The route is gated independently in
                lib/permissions.ts and on the server; this is the affordance, not the boundary. */}
            {isAdmin(user) ? (
              <Link href={`/design-workshops/${id}/provenance`} className="field-button-secondary">
                <GitCompareArrows className="h-4 w-4" aria-hidden />
                Authorship &amp; divergence
              </Link>
            ) : null}
            <Link href={`/design-workshops/${id}/report`} className="field-button">
              <FileText className="h-4 w-4" aria-hidden />
              Report
            </Link>
          </>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {offline || unsentStages ? (
        // The two facts a designer needs before they decide anything on this page, and neither is
        // guessable from the progress bars: whether what is on screen is the repository's copy, and
        // whether anything here has yet to reach it.
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          {offline
            ? "There is no connection, so this is the copy saved in this browser. It is complete and editable — everything you change is kept here."
            : null}
          {offline && unsentStages ? " " : null}
          {unsentStages
            ? `${unsentStages} stage${unsentStages === 1 ? " is" : "s are"} saved on this device only and ${unsentStages === 1 ? "has" : "have"} not reached the repository yet.`
            : null}
        </div>
      ) : null}

      {detail ? (
        <section className="panel mb-5 grid gap-4 p-4">
          <div className="flex flex-wrap items-center gap-3">
            {/* THE STATUS CHIP MOVED OUT OF THIS ROW, DOWN TO THE SUBMISSION CARD. It belonged here
                while the status was read-only; now that a control on this page writes it, the chip
                has to be beside that control — and keeping a second copy here would have let the two
                disagree, because the card's reads the status the server just returned while this row
                reads the local draft header, which `adoptServerDetail` deliberately declines to
                refresh while header edits are unsent. See `SubmissionCard`. */}
            {detail.workshopCode ? (
              <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">{detail.workshopCode}</span>
            ) : null}
            {detail.startDate ? (
              <span className="text-sm text-ink-muted">
                {formatDate(detail.startDate)}
                {detail.endDate ? ` – ${formatDate(detail.endDate)}` : ""}
              </span>
            ) : null}
          </div>
          <div>
            <div className="flex items-center justify-between text-sm">
              {/*
                DERIVED, NOT THE LITERAL 22. Same class of defect as the one `submissionScope` was
                fixed for on this very screen: a count typed into a sentence is a promise that a
                registry change makes false, and this screen would then be able to disagree with its
                own submission dialog, which reads `readiness.stagesTotal`.

                THE NUMBER IS DROPPED RATHER THAN GUESSED when the registry has not arrived. "every
                stage" is true whatever the count turns out to be; "all 0 stages" and "all 22 stages"
                are both assertions this component cannot make yet, and one of them is the wrong kind
                of wrong — it looks right.

                ⚠ `report/page.tsx` still carries this string as a literal, and it is left alone
                deliberately rather than overlooked: `e2e/web-surface-gaps-unit.spec.ts` uses it as
                the ANCHOR it slices that page's source at (`page.indexOf("Required fields across all
                22 stages")`), so changing it there without changing the spec in the same commit turns
                two assertions into silent passes over the tail of a file. That is a two-file change
                and `frontend/e2e/` was another lane's while this was written. Until it is made, the
                two screens read identically at 22 stages and only diverge if the registry moves — at
                which point THIS one is the one telling the truth.
              */}
              <span className="font-medium text-ink-900">
                {stageCount === null
                  ? "Required fields across every stage"
                  : `Required fields across all ${stageCount} stages`}
              </span>
              <span className="text-ink-muted">{percent}%</span>
            </div>
            <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-field-200">
              <div className="h-full rounded-full bg-purple-700" style={{ width: `${percent}%` }} />
            </div>
          </div>
          {/* The two things that go stale silently, both stated rather than assumed. */}
          <p className="text-xs leading-5 text-ink-500">
            Progress counts Basic-tier fields only — the tier the report needs. A stage that asks for no required fields
            reads as complete rather than as 0%. Craft, cluster, dates and the workshop code above are filled in from
            stage 1 and stay blank until it is saved.
          </p>
        </section>
      ) : null}

      {/*
        DIRECTLY UNDER THE WORKSHOP'S OWN PANEL, AND ABOVE EVERYTHING ELSE ON THE PAGE.

        The submission is a fact about the WORKSHOP, like the progress bar it sits under, and it is
        the act the whole index is read on the way to. It cannot go at the foot of the page, under
        twenty-two stage rows: the designer who needs it is the one on the last afternoon of a
        fortnight deciding whether they can pack up, and a control they have to scroll past the whole
        record to find is one they will look for in the header actions instead — where "Ready to
        submit?" links to a screen that only ever LISTED what was outstanding and could never finish
        the job.

        Drawn only once the local draft has loaded, exactly as the consent card below is: the card's
        first line is the status, and drawing it against an empty header would show every workshop as
        a draft for a moment and invite a designer to submit one that had already been submitted.
      */}
      {/* `unopenable` is deliberately NOT re-tested here: that state returns early, above this whole
          tree, and a predicate that can never fire is how a rule quietly comes back. */}
      {detail ? (
        <SubmissionCard
          // The SERVER's id and never the draft's local one — this is the thing being PATCHed. For a
          // never-synced workshop there is no such id, and `neverSent` is what the card says so with.
          workshopId={draft?.remoteId ?? id}
          // The server's answer to this session's own PATCH wins over the draft's copy, which by then
          // is knowingly stale — see {@link confirmedStatus}.
          status={confirmedStatus ?? detail.status}
          readiness={readiness}
          neverSent={neverSent}
          headerUnsent={draft?.headerDirtyAt !== null && draft?.headerDirtyAt !== undefined}
          // THE FIGURE THE AMBER BANNER ABOVE ALREADY DRAWS, handed down rather than recounted in the
          // card. Without it the card could report a submission over stages the repository has never
          // heard of — see {@link SubmissionCard}'s `unsentStages`.
          unsentStages={unsentStages}
          offline={offline}
          onStatusChanged={setConfirmedStatus}
        />
      ) : null}

      {/*
        ABOVE THE SEARCH PANEL AND THE STAGE INDEX, AND THAT PLACEMENT IS THE POINT.

        Six different refusals — server dictation and all five AI verbs — end with "Open the
        workshop's own screen and record the artisan's answer to that question". This IS that screen,
        and a designer arriving from one of those sentences must find the control without reading
        twenty-two progress bars first. It sits below the workshop's own detail panel because the
        answer belongs to the workshop rather than to any stage.

        Drawn only once the local draft has loaded: the card's whole job is to state what is on
        record, and drawing it against "NOT_RECORDED" while the draft is still being read would show
        every workshop as unanswered for a moment and invite a designer to re-ask a question that
        already has an answer.
      */}
      {draft ? (
        <DictationConsentCard
          draftLocalId={draft.localId}
          remoteId={draft.remoteId}
          consent={draft.consent?.decision ?? "NOT_RECORDED"}
          recordedAt={draft.consent?.recordedAt ?? null}
          // The device's own name for the recorder is used when the server has not named one — after
          // an offline answer there is no server copy to resolve, and "Recorded" with no name at all
          // would read as though nobody had.
          recordedByName={consentByName ?? draft.consent?.recordedByName ?? null}
          synced={draft.consent ? draft.consent.synced : true}
          onRecorded={() => {
            // Re-read from IndexedDB rather than patching state here: `recordDraftConsent` is the
            // one writer, and a second reconstruction of the record in this component is how the
            // screen and the store come to disagree about what is on record.
            void loadDraft(draft.localId).then((refreshed) => {
              if (refreshed) setDraft(refreshed);
            });
          }}
        />
      ) : null}

      {/* Above the stage index and below the header, because the question it answers ("where did I
          write that?") is asked INSTEAD of reading the list below, not after it. */}
      <WorkshopSearchPanel workshopId={id} index={searchIndex} ready={registry !== null && draft !== null} />

      <section className="panel overflow-hidden">
        {registry === null || draft === null ? (
          // NO NUMBER HERE AT ALL, and that is the point rather than a shortening: this branch renders
          // precisely because the registry has NOT been read, so the stage count is the one fact this
          // line cannot know. Printing 22 while waiting to find out is a guess dressed as a fact.
          <div className="p-4 text-sm text-ink-700">Loading the stages…</div>
        ) : (
          <ol>
            {stages.map((stage) => (
              <StageRow key={stage.key} workshopId={id} stage={stage} score={completeness[stage.key]} />
            ))}
          </ol>
        )}
      </section>

      {registry && serverSchemaVersion && registry.version !== serverSchemaVersion ? (
        // Not decoration. `schemaVersion` is the digest of the registry this record was last
        // WRITTEN against; a mismatch means fields have been added, removed or retyped since, and a
        // designer who sees a new box appear on a stage they finished last week deserves to know
        // why rather than assuming they missed it.
        <p className="mt-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
          The field list has changed since this workshop was last saved. Stages may now ask for fields that did not exist
          when they were filled in; re-opening and saving a stage brings it up to date.
        </p>
      ) : null}
    </>
  );
}
