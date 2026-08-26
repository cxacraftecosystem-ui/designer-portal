"use client";

/**
 * One screen answering "what still stops this workshop being submitted?"
 *
 * THE PROBLEM IT REPLACES. Completeness has always been computed — per stage, on both ends, and
 * correctly — but it was only ever SHOWN per stage. A designer chasing a submission on the last
 * afternoon of a fortnight had to open all 22 stages to discover that four Basic fields, in three of
 * them, were what stood in the way. Twenty-two page loads to find four boxes, on a laptop that is
 * usually offline, is the reason people submit reports with "Not recorded." printed through them.
 *
 * IT COMPUTES NOTHING. Every number and every entry comes from `lib/submissionReadiness`, which is
 * itself an assembly of `localStageCompleteness` — the same scorer the stage form's own progress bar
 * and the server's `stage_completeness` use. Read that file's header before changing anything here:
 * the one rule this feature has is that it must never become a second opinion about whether a field
 * is filled in.
 *
 * IT WORKS WITH NO NETWORK, which is not a nicety. The list is built from the local draft in
 * IndexedDB, exactly as `MarketFindingsPanel` builds its findings, so the question can be asked in
 * the courtyard where the answer changes what happens next. The server read that follows only
 * refreshes the draft; nothing on this page waits for it.
 *
 * THE RANKING IS THE FEATURE. Unfilled Basic fields come first because they are the only things that
 * 422 anything at all — a STAGE CHECK, defined once below and printed in those words on both screens
 * that quote this count. They do NOT refuse the workshop's status, and this screen asserted that they
 * did until the contradiction with the Submission card was found. The report's own checks come second
 * because they change the delivered file without refusing it. Standard and Advanced gaps come last,
 * as counts, behind a disclosure, and are never drawn in the same list — a screen that mixes two
 * hundred suggestions into four obstacles teaches a designer to skim the whole thing.
 */

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertTriangle, ArrowRight, CheckCircle2, FileWarning, ListChecks } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { getDesignWorkshop, type DwRegistry } from "@/lib/designWorkshops";
import {
  adoptServerDetail,
  ensureDraft,
  isLocalWorkshopId,
  loadCustomDefinition,
  loadDraft,
  loadRegistry,
  type CustomDefinitionSource,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { isUnreachable } from "@/lib/offline";
import {
  itemFieldName,
  readinessSummary,
  workshopReadiness,
  type ReadinessItem,
  type WorkshopReadiness
} from "@/lib/submissionReadiness";

/* ────────────────────────────────────────────────────────────────────────────
 * The two acts, named once — and this is the only definition of either
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * TWO DIFFERENT ACTS WERE BOTH CALLED "SUBMIT", ON TWO SCREENS, ABOUT ONE NUMBER.
 *
 * ── THE DEFECT ──────────────────────────────────────────────────────────────────────────────────
 *
 * This screen printed "{n} required fields outstanding / A submit is refused while any of these is
 * empty". The workshop's own Submission card printed THE SAME COUNT under "Submitting is not blocked
 * by this". Both sentences were true, of different mechanisms, and neither named which — so a
 * designer who read both in the same minute was told one number both blocks and does not block them,
 * with nothing on either screen to reconcile it. That is worse than either sentence alone: a count
 * that contradicts itself across two screens teaches a designer that the count means nothing, and
 * this count is the only thing standing between a part-filled workshop and a ministry.
 *
 * ── THE TWO MECHANISMS, READ OUT OF THE CODE RATHER THAN ASSUMED ────────────────────────────────
 *
 * * `PUT /design-workshops/{id}/stages/{key}` with `submit: true` — `save_stage_data`
 *   (`backend/app/api/routes/design_workshops.py:1565`), whose gate is `if result["errors"] and
 *   payload.submit` at `:1590`, over `validate_entry(..., enforce_required=payload.submit)`
 *   (`services/design_workshops.py:3712`). It 422s ONE STAGE while any Basic field of that stage is
 *   empty. The refusal is raised AFTER the transaction has committed, so the stage is written and
 *   then refused — which is why this act's name has to be about the CHECK and not about saving.
 * * `PATCH /design-workshops/{id}` with `status` consults no scorer at all, gates COMPLETE and
 *   SUBMITTED identically, is fully reversible, and nothing anywhere reads the tokens it writes.
 *
 * ── IT IS REACHABLE, WHICH IS THE OPPOSITE OF WHAT THIS REPAIR WAS BRIEFED ON ───────────────────
 *
 * The brief recorded the strict per-stage save as UNREACHABLE from any client, on the evidence that
 * `lib/designWorkshops.ts:1152` and `lib/designWorkshopStore.ts:4888` both write `submit: false`.
 * Checked independently before a word of this copy was written, and it is not so:
 *
 * * `lib/designWorkshops.ts:1152` is `JSON.stringify({ replaceCollections: false, submit: false,
 *   ...body })`. `...body` spreads AFTER, so that is an overridable DEFAULT — and `submit?: boolean`
 *   is a declared member of `DwSaveBody` precisely so a caller can override it.
 * * The stage form does override it. `stages/[stageKey]/page.tsx:2037` is a button labelled
 *   "Save and check required fields" whose handler is `save(true)`, and `save`'s single call to
 *   `saveDesignWorkshopStage` (`:1322`) passes that boolean straight through as `submit`. Its own
 *   success sentence, at `:1422`, is "Stage saved and every required field is filled in."
 * * It is exercised end to end today: `e2e/inline-create-hydration.spec.ts:269` clicks that button
 *   by name and asserts the strict save is ACCEPTED — "this is the 422 the blank required boxes used
 *   to cause". A spec cannot assert the absence of a 422 from an act no client can perform.
 * * `lib/designWorkshopStore.ts:4888` really is a hard `submit: false`, and correctly so: that is
 *   the offline outbox drain, and a background pass must never enforce required fields on a
 *   designer's behalf — a courtyard sync is not a submission.
 *
 * So the refusal this screen has always described is real, and the honest repair is NOT to withdraw
 * it. What this screen never said is that it refuses ONE STAGE, at a button that has to be pressed on
 * that stage, and that it has no bearing whatever on the act on the Submission card. Both facts are
 * now printed, in these exact words, on both screens.
 *
 * ── WHY THE VOCABULARY LIVES IN A PAGE MODULE, WHICH IS NOT WHERE IT BELONGS ────────────────────
 *
 * `lib/` is the right home and this lane was permitted to write exactly two files. It is defined HERE
 * rather than on the Submission card's own page because the import must run in the cheaper direction:
 * this module's imports are a subset of that page's, so the workshop index pays almost nothing to
 * read these two strings, whereas this screen importing from the index would drag
 * `lib/workshopSearch` (1,048 lines), `WorkshopSearchPanel` and `DictationConsentCard` into a route
 * that renders none of them. Moving both constants to a `lib/` module is strictly better and nothing
 * here resists it — but they must stay in ONE place, because two copies of this vocabulary is the
 * defect at the top of this comment with extra steps.
 */
export const STAGE_CHECK_IS =
  "A stage check — “Save and check required fields”, the second button at the foot of any stage — is the only " +
  "act in this app that an empty required field refuses. It saves the stage either way, then refuses THAT ONE " +
  "STAGE while any of its Basic-tier fields is empty, and names the ones it is waiting for.";

/**
 * The other half of the same vocabulary, printed beside {@link STAGE_CHECK_IS} on both screens.
 *
 * The two are deliberately separate constants rather than one paragraph: the Submission card leads
 * with this one, because a designer standing at those buttons is asking "does this stop me?", and
 * this screen leads with the stage check, because a designer reading a list of empty boxes is asking
 * "what are these for?". Same words, same order inside each sentence, opposite order on the page.
 */
export const WORKSHOP_STATUS_IS =
  "The workshop’s status — “Mark complete” and “Submit”, on the Submission card of the workshop itself — " +
  "records where the whole workshop stands and is never refused for an empty field: requirement 12 is explicit " +
  "that a workshop may be submitted part-filled.";

/** The outstanding fields of one stage, under one heading a designer can recognise. */
function StageGroup({ stageNumber, stageTitle, items }: { stageNumber: number; stageTitle: string; items: ReadinessItem[] }) {
  return (
    <li className="border-b border-line-200 last:border-b-0">
      <div className="flex items-center gap-3 px-4 pb-1 pt-3">
        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700">
          {stageNumber}
        </span>
        <h3 className="min-w-0 flex-1 truncate text-sm font-medium text-ink-900">{stageTitle}</h3>
        <span className="shrink-0 text-xs text-ink-500">
          {items.length} required field{items.length === 1 ? "" : "s"}
        </span>
      </div>
      <ul className="grid">
        {items.map((item) => (
          <li key={`${item.stageKey}:${item.label}`}>
            {/* THE WHOLE ROW IS THE LINK. An item a designer cannot act on in one click has failed,
                and a row where only a small "Open" at the end is clickable is one a thumb misses on
                the phone this list is most often read on. */}
            <Link
              href={item.href}
              className="flex items-center gap-3 py-2 pl-14 pr-4 transition hover:bg-surface-50"
            >
              <span className="min-w-0 flex-1">
                <span className="block text-sm text-ink-900">{itemFieldName(item)}</span>
                <span className="mt-0.5 block text-xs text-ink-500">
                  {item.address ? item.address.entityTitle : item.label}
                  {item.address && item.address.occurrences > 1
                    ? // The denominator is what makes this sizeable work rather than one forgotten box.
                      ` · missing in ${item.address.occurrences} of ${item.address.rowCount} rows`
                    : null}
                  {/* Honest about a link that will land on the stage rather than on the box. Silence
                      here would look like the same promise the addressed rows make and quietly not
                      keep it. */}
                  {item.address ? null : " · opens the stage"}
                </span>
              </span>
              <ArrowRight className="h-4 w-4 shrink-0 text-ink-300" aria-hidden />
            </Link>
          </li>
        ))}
      </ul>
    </li>
  );
}

export default function DesignWorkshopReadinessPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { id } = use(params);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  /**
   * How this browser came to hold — or fail to hold — the workshop's OWN questions.
   *
   * `null` while the load is still running; after that it is always one of the three states, because
   * the load effect settles it in a `finally`. Audit 2026-08-15 (MAJOR): the source was read and
   * thrown away, so a definition this browser could not read scored as a definition with nothing in
   * it, and the screen whose entire purpose is answering "can I pack up?" answered yes on evidence it
   * knew it did not have.
   *
   * "unknown" is the only value that changes what this page CLAIMS. "network" and "cache" both mean
   * the questions were counted; which of the two it was is not something a designer has to act on.
   */
  const [definitionSource, setDefinitionSource] = useState<CustomDefinitionSource | null>(null);
  /** True once the load has settled and the workshop's own questions could NOT be read. */
  const uncountedQuestions = definitionSource === "unknown";

  /*
    The same load the stage index performs, and deliberately the same shape: local copy first so the
    list renders with no connection, then a server read that only refreshes it. Copied rather than
    shared because the two pages want different things from the result and a hook that returned
    "everything either might need" would fetch more than either does.
  */
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
        // Without the registry there is no list of required fields at all, so this one is fatal —
        // but say what it is. Rendering "nothing is outstanding" because the declaration could not be
        // read is the single worst thing this page could do.
        setError(
          err instanceof Error
            ? `The list of fields could not be loaded and this browser has no saved copy of it: ${err.message}`
            : "The list of fields could not be loaded and this browser has no saved copy of it."
        );
        return;
      }

      const local = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
      if (cancelled) return;
      if (local) setDraft(local);

      if (isLocalWorkshopId(id) && local && !local.remoteId) return;

      /**
       * The freshest draft this run has produced, readable from the `finally` below.
       *
       * Hoisted out of the `try` for one reason: the `finally` has to answer "did this screen manage
       * to count the workshop's own questions?" on EVERY path, including the ones that threw before
       * `merged` existed. Reading `local` alone there would ignore a definition the server's copy had
       * just brought in.
       */
      let settled: DwDraft | null = local;

      try {
        const detail = await getDesignWorkshop(local?.remoteId ?? id);
        if (cancelled) return;
        const merged = await adoptServerDetail(detail, loadedRegistry);
        if (cancelled) return;
        settled = merged ?? settled;
        /*
          THE WORKSHOP'S OWN QUESTIONS, READ FOR THE SAME REASON THE FIELD REGISTRY IS.

          This page's blocking set is `DwStageCompleteness.missing` and nothing else, and the designer's
          required custom questions are in it — `localCompleteness` counts them off `draft.customDefinition`.
          So a definition this browser has not read is a screen that says "nothing is outstanding" about a
          stage the submit gate will refuse, which is the one thing the header of `lib/submissionReadiness.ts`
          says this page must never do.

          Not awaited and not fatal: it refreshes a list already on screen, and its own failure resolves to
          "unknown" rather than to an empty definition. The draft is re-read afterwards because the
          definition is written to the same record.
        */
        const target = merged ?? local;
        if (target) {
          void loadCustomDefinition(target).then(async (held) => {
            if (cancelled) return;
            /*
              THE SOURCE IS KEPT, AND KEEPING IT IS THE FIX. Audit 2026-08-15 (MAJOR, frontend).

              This used to read `if (cancelled || !held.definition) return;` — the three-state source
              dropped on the floor beside a comment (above) that names the exact rule doing so
              breaks. With `source: "unknown"` the draft's `customDefinition` stays null,
              `customFieldsFor` resolves to `[]`, no required custom question is counted, `blocking`
              is empty, `isSubmittable` is true, and this page renders "Every required field across
              all 22 stages is filled in… Nothing on this workshop will be refused for a missing
              Basic field" about a workshop whose submit the server is about to refuse.

              "unknown" is not "there are none". `CustomDefinitionSource` exists precisely because a
              per-workshop definition has no floor by construction — an empty answer and an unread
              one look identical in the data and mean opposite things. The stage form already honours
              all three states; this screen, whose entire purpose is answering "can I pack up?",
              did not.
            */
            setDefinitionSource(held.source);
            if (!held.definition) return;
            const refreshed = await loadDraft(target.localId);
            if (!cancelled && refreshed) setDraft(refreshed);
          });
        }
        setDraft(target);
        setError(null);
      } catch (err) {
        if (cancelled) return;
        // `isUnreachable`, not `isTransient`: a 5xx is the repository reporting a fault, and telling
        // a designer to go and look at their signal for it sends them to fix the wrong thing.
        if (isUnreachable(err)) {
          setOffline(true);
          if (!local) setError("There is no connection and this browser has no copy of this workshop.");
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
      } finally {
        /*
          THE WIDER DOOR INTO THE SAME FALSE GREEN, and it is the one the finder missed: when
          `getDesignWorkshop` itself fails, the branches above RETURN and `loadCustomDefinition` is
          never called at all. A draft holding a fortnight of answers that has never cached a
          definition then scores the same false green — under an amber banner that positively
          asserts "nothing on this page needs a server to be right".

          Settled in a `finally` so every path leaves this screen with an honest answer about what it
          was able to count. `held` on the draft is the offline truth: a definition read on a previous
          connection is a legitimate "cache", and its absence is exactly the "unknown" this page must
          declare rather than silently score as zero.
        */
        if (!cancelled) {
          setDefinitionSource((current) => current ?? (settled?.customDefinition ? "cache" : "unknown"));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const readiness: WorkshopReadiness | null = useMemo(
    () => (registry && draft ? workshopReadiness(registry, draft, id) : null),
    [registry, draft, id]
  );

  /** The blocking items regrouped under their stage, in the order they were produced (stage order). */
  const groups = useMemo(() => {
    if (!readiness) return [];
    const out: { stageKey: string; stageNumber: number; stageTitle: string; items: ReadinessItem[] }[] = [];
    for (const item of readiness.blocking) {
      const last = out[out.length - 1];
      if (last && last.stageKey === item.stageKey) last.items.push(item);
      else out.push({ stageKey: item.stageKey, stageNumber: item.stageNumber, stageTitle: item.stageTitle, items: [item] });
    }
    return out;
  }, [readiness]);

  const percent = readiness && readiness.requiredTotal > 0
    ? Math.round((100 * readiness.requiredFilled) / readiness.requiredTotal)
    : 100;

  return (
    <>
      <PageHeader
        title="Ready to submit?"
        description="Every required field still outstanding, and what the report would warn about, computed on this device."
        icon={<ListChecks className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All 22 stages
          </Link>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {offline ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          There is no connection, so this is worked out from the copy saved in this browser.
          {/* THE PARITY CLAIM IS CONDITIONAL NOW, AND IT HAS TO BE. It used to assert flatly that
              "nothing on this page needs a server to be right" — which is true of the field registry
              and false of the workshop's own questions, since those live only on the server and in
              whatever this browser has cached of them. Asserting parity while `uncountedQuestions`
              is true is the same false green as the success block below, in a banner a designer is
              more likely to believe precisely because it is explaining itself. */}
          {uncountedQuestions
            ? " The 22 stages' own field list is saved here, so that half is exact — but this workshop's own questions are not, and they are not counted below."
            : " That is the same arithmetic the repository does — nothing on this page needs a server to be right."}
        </div>
      ) : null}

      {uncountedQuestions ? (
        /*
          THE ONE THING THIS SCREEN COULD NOT COUNT, SAID BEFORE THE NUMBER IT AFFECTS.

          `loadCustomDefinition` resolves to `source: "unknown"` when the definition could neither be
          fetched nor found in this browser's cache — and "unknown" is NOT "there are none". A
          designer-defined required question is in `DwStageCompleteness.missing` exactly like a
          registry field, so a definition that could not be read makes every number on this page a
          lower bound rather than an answer.

          Drawn even when the connection is fine, because the two failures are independent: the
          workshop read can succeed and the definition read still fail (a 500 on that route, or a
          definition written by a build this browser cannot parse).
        */
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          This browser could not read the questions this workshop adds of its own, so anything they ask is NOT counted
          below. A required question among them would refuse its own stage&apos;s check without ever appearing on this
          page. Open this workshop again with a connection to read them.
        </div>
      ) : null}

      {readiness === null ? (
        <section className="panel p-4 text-sm text-ink-700">
          {error ? "Nothing could be worked out." : "Working out what is left…"}
        </section>
      ) : (
        <>
          <section className="panel mb-5 grid gap-4 p-4">
            <div>
              <div className="flex items-center justify-between gap-3 text-sm">
                {/* THE SENTENCE, NOT THE PERCENTAGE, IS THE HEADLINE. 94% reads as "nearly there"
                    whether what is left is one date or a stage nobody has opened. */}
                <span className="font-medium text-ink-900">{readinessSummary(readiness)}</span>
                <span className="shrink-0 text-ink-muted">{percent}%</span>
              </div>
              <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-field-200">
                <div
                  className={
                    readiness.isSubmittable
                      ? "h-full rounded-full bg-success-600"
                      : "h-full rounded-full bg-purple-700"
                  }
                  style={{ width: `${Math.max(0, Math.min(100, percent))}%` }}
                />
              </div>
            </div>
            <p className="text-xs leading-5 text-ink-500">
              Only Basic-tier fields are counted here, because those are the ones a stage check refuses without. A
              stage that asks for no required fields reads as complete rather than as 0%.
            </p>
          </section>

          {readiness.isSubmittable ? (
            /* THE COMPLETE CASE IS A STATEMENT, NEVER AN EMPTY LIST. A list with nothing in it and no
               sentence beside it is indistinguishable from a list that failed to load, and this
               screen exists precisely so a designer does not have to go and check by hand. */
            <section className="panel mb-5 grid gap-2 p-4">
              {/* THE CLAIM IS DEGRADED, NOT DECORATED, WHEN THE QUESTIONS COULD NOT BE READ.
                  "Nothing on this workshop will be refused" is a promise about the server's submit
                  gate, and the gate counts the designer's own required questions. Making a promise
                  the screen has no evidence for is precisely the failure this whole section exists
                  to prevent, so the wording narrows to the half that WAS checked — and the amber
                  line above names the half that was not. The tick stays green because everything
                  this page could count is genuinely done. */}
              <p className="flex items-center gap-2 text-sm font-medium text-success-600">
                <CheckCircle2 className="h-4 w-4" aria-hidden />
                {uncountedQuestions
                  ? `Every required field of the standard form, across all ${readiness.stagesTotal} stages, is filled in.`
                  : `Every required field across all ${readiness.stagesTotal} stages is filled in.`}
              </p>
              <p className="text-sm leading-6 text-ink-700">
                {uncountedQuestions ? (
                  <>
                    All {readiness.requiredTotal} of them. This workshop&apos;s own questions could not be read here, so
                    whether a stage check would be refused for one of those is not something this page can answer
                    {readiness.checks.length ? "; the report also has something to say below." : "."}
                  </>
                ) : (
                  <>
                    All {readiness.requiredTotal} of them. No stage would be refused a stage check for a missing Basic
                    field{readiness.checks.length ? ", though the report has something to say below." : "."}
                  </>
                )}
              </p>
            </section>
          ) : (
            <section className="panel mb-5 overflow-hidden">
              <div className="border-b border-line-200 px-4 py-3">
                <h2 className="text-sm font-semibold text-ink-900">
                  {readiness.blocking.length} required field{readiness.blocking.length === 1 ? "" : "s"} outstanding
                </h2>
                {/* THE SENTENCE THAT USED TO READ "A submit is refused while any of these is empty", over a count
                    the Submission card printed under "Submitting is not blocked by this". Both were true, of
                    different acts, and neither named its own — see {@link STAGE_CHECK_IS} for the whole defect and
                    for the evidence that this refusal is REACHABLE and so must not be withdrawn. It keeps its
                    refusal and gains its scope (one stage, at that stage's own button); its counterpart is named
                    beside it, in the words the card uses for the same act. */}
                <p className="mt-0.5 text-xs leading-5 text-ink-500">
                  {STAGE_CHECK_IS} Each row below opens the stage with the box highlighted.
                </p>
                <p className="mt-1 text-xs leading-5 text-ink-500">{WORKSHOP_STATUS_IS}</p>
              </div>
              <ul>
                {groups.map((group) => (
                  <StageGroup
                    key={group.stageKey}
                    stageNumber={group.stageNumber}
                    stageTitle={group.stageTitle}
                    items={group.items}
                  />
                ))}
              </ul>
            </section>
          )}

          {readiness.checks.length ? (
            <section className="panel mb-5 overflow-hidden">
              <div className="border-b border-line-200 px-4 py-3">
                <h2 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
                  <FileWarning className="h-4 w-4 text-amber-800" aria-hidden />
                  What the report would warn about
                </h2>
                {/* "These do not refuse a submit" named no act either, and this section is the one place on
                    the screen where the distinction does no work: these refuse NEITHER act. Said that way, with
                    both names, so the sentence cannot be read as ranking them below only one of the two. */}
                <p className="mt-0.5 text-xs leading-5 text-ink-500">
                  These refuse neither a stage check nor the workshop&apos;s status. They change the file that gets
                  delivered.
                </p>
              </div>
              <ul>
                {readiness.checks.map((check) => (
                  <li key={check.id} className="border-b border-line-200 last:border-b-0">
                    <Link href={check.href} className="flex items-start gap-3 px-4 py-3 transition hover:bg-surface-50">
                      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-medium text-ink-900">{check.title}</span>
                        <span className="mt-0.5 block text-sm leading-6 text-ink-700">{check.detail}</span>
                      </span>
                      <ArrowRight className="mt-0.5 h-4 w-4 shrink-0 text-ink-300" aria-hidden />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {readiness.advisory.length ? (
            /* SUBORDINATE BY CONSTRUCTION, not merely by being lower down: closed by default, no
               status colour, counts rather than named fields. Standard and Advanced answers are what
               make a report good; they are not what makes it acceptable, and a designer with an hour
               left must be able to tell the two apart at a glance. */
            <details className="panel px-4 py-3">
              <summary className="cursor-pointer text-sm font-medium text-ink-700">
                Optional detail not yet filled in ({readiness.advisory.length} stage
                {readiness.advisory.length === 1 ? "" : "s"})
              </summary>
              <p className="mt-2 text-xs leading-5 text-ink-500">
                Standard- and Advanced-tier fields. None of these blocks a submission or produces a report warning —
                they are the depth a report gains when there is time for it.
              </p>
              <ul className="mt-3 grid gap-1">
                {readiness.advisory.map((gap) => (
                  <li key={gap.stageKey}>
                    <Link
                      href={gap.href}
                      className="flex items-center justify-between gap-3 rounded-md px-2 py-1.5 text-sm transition hover:bg-surface-50"
                    >
                      <span className="min-w-0 flex-1 truncate text-ink-700">
                        {gap.stageNumber}. {gap.stageTitle}
                      </span>
                      <span className="shrink-0 text-xs text-ink-500">
                        {gap.optionalFilled} of {gap.optionalTotal}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </details>
          ) : null}
        </>
      )}
    </>
  );
}
