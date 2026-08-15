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
 */

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { DraftingCompass, FileClock, FileText, Images, Layers, ListChecks, ListPlus, QrCode } from "lucide-react";

import { WorkshopSearchPanel } from "@/components/designworkshop/WorkshopSearchPanel";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import {
  getDesignWorkshop,
  overallPercent,
  type DwRegistry,
  type DwStage,
  type DwStageCompleteness,
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
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { buildWorkshopSearchIndex, emptyWorkshopSearchIndex } from "@/lib/workshopSearch";

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
    createdById: draft.ownerUserId ?? "",
    createdAt: new Date(draft.createdAt).toISOString(),
    updatedAt: new Date(draft.updatedAt).toISOString(),
    deletedAt: null
  };
}

export default function DesignWorkshopStagesPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { id } = use(params);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  /** The server's own schema digest for this record, when it could be read. */
  const [serverSchemaVersion, setServerSchemaVersion] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);

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
  /** The header as the local copy holds it, which is the server's whenever nothing is unsent. */
  const detail: DwSummary | null = draft ? { ...draftHeader(draft), id: draft.remoteId ?? draft.localId } : null;
  const unsentStages = draft
    ? Object.values(draft.stages).filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0).length
    : 0;

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
            <StatusBadge status={detail.status} />
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
              <span className="font-medium text-ink-900">Required fields across all 22 stages</span>
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

      {/* Above the stage index and below the header, because the question it answers ("where did I
          write that?") is asked INSTEAD of reading the list below, not after it. */}
      <WorkshopSearchPanel workshopId={id} index={searchIndex} ready={registry !== null && draft !== null} />

      <section className="panel overflow-hidden">
        {registry === null || draft === null ? (
          <div className="p-4 text-sm text-ink-700">Loading the 22 stages…</div>
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
