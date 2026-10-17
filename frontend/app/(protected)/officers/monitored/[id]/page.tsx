"use client";

/**
 * ONE WORKSHOP UNDER OVERSIGHT — every stage, read-only, with who wrote each field.
 *
 * ── WHY PROVENANCE IS ON THIS SCREEN ──────────────────────────────────────────────────────────
 *
 * `read_overseen_workshop` resolves the per-field authorship names before it answers, deliberately,
 * because "who wrote this field" is most of what supervision is for and the ids without them are
 * unreadable. So this page draws {@link FieldProvenance} under every value — the SAME component the
 * designer's stage form draws under every box, producing the same sentence in the same words,
 * because an officer and the designer they supervise must not be reading two different accounts of
 * who did what.
 *
 * ── `readOnly` IS HONOURED RATHER THAN ASSUMED ───────────────────────────────────────────────
 *
 * `readOnly` IS THE ASSIGNMENT'S PROPERTY AND NOT A ROLE'S, WHICH MATTERS SINCE 2026-09-14. This
 * comment used to say an officer is outside `DESIGN_WORKSHOP_ROLES` so the workshop tree answers a
 * **404** to them, exactly as it does to a professor. The three directorate tiers are now inside
 * that set, so that is no longer the reason and this page must not lean on it: what makes a
 * MONITORED workshop read-only is the oversight assignment it is rendered from, which grants
 * watching and not authoring. Since the owner's ruling of 2026-10-09 that holds in the workshop tree
 * too: whoever holds a post on a workshop — an admin named its Regional Director, say — is refused
 * its writes there for as long as they hold it, and a designer row on it is refused to them.
 *
 * The read-only banner is DECLARED rather than left to be inferred from the absence of buttons: a
 * screen with no Save on it looks the same as a screen whose Save has not loaded yet.
 *
 * ── THE RENDERERS ARE THE INSPECTION SURFACE'S, IMPORTED AND NOT COPIED ──────────────────────
 *
 * `inspectionFieldReading` is a PURE function over the stage registry — it decides whether a field
 * is empty, is media this read does not carry, or is text, and it is the same question on both
 * surfaces. Its name says "inspection" because that scope needed it first; importing it is
 * deliberate, and a second copy here would be two renderers of one registry drifting the first time
 * either is corrected. **If it is ever promoted to `lib/designWorkshops`, this import follows it.**
 *
 * ── THE WORKSHOP'S FILES AND ITS OWN QUESTIONS ARE SHOWN, READ-ONLY (sweep item F5, 2026-10-10) ──
 *
 * The files come from this surface's own second read,
 * `GET /design-workshop-oversight/assigned/{id}/media`, behind the same loader as the workshop, with
 * short-lived signed links and no control that writes — the same components the inspection screen
 * draws them with (`components/designworkshop/ReaderWorkshopMedia.tsx`). The workshop's questions
 * travel on the read as `customSections`, so each stage's custom answers are printed with their
 * wording and their author.
 */

import Link from "next/link";
import { use, useEffect, useMemo, useState } from "react";
import { Binoculars, Loader2, Lock, MessageSquarePlus, Send } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { ReaderFilesPanel, ReaderMediaProvider, useReaderMedia } from "@/components/designworkshop/ReaderWorkshopMedia";
import { NoteComposer, NoteRegister, NoteTray, useReviewTray } from "@/components/designworkshop/review/ReviewNotes";
import { ReviewChip, ReviewReadStage, type FlagTarget } from "@/components/designworkshop/review/ReviewReadStage";
import {
  ReviewDecisionBar,
  ReviewLayout,
  RoundCompareControls,
  useActiveStage,
  useRoundCompare
} from "@/components/designworkshop/review/ReviewWorkspace";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ApiError } from "@/lib/api";
import { fetchStageRegistry, overallPercent, type DwRegistry, type DwStage } from "@/lib/designWorkshops";
import { decisionKindLabel, signOffLines } from "@/lib/designWorkshopApprovals";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canReadWorkshopOversight, roleLabel } from "@/lib/permissions";
import { fileLabel, fileNotes, type DwReviewNote } from "@/lib/reviewAids";
import type { User } from "@/lib/types";
import {
  CAPACITY_LABELS,
  getOverseenWorkshop,
  oversightIsReadOnly,
  oversightRefusalMeansNoPosts,
  type DwOversightDetail
} from "../../oversight";

function describeFailure(error: unknown, user: User | null | undefined): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "Couldn't connect, so this workshop couldn't be loaded. Check your connection and try again.";
  }
  if (oversightRefusalMeansNoPosts(error, user)) {
    // An admin the server will not yet show this surface to holds no post anywhere — so none here.
    return "You aren't an Assistant or Regional Director on any workshop yet. An admin names officers on Workshop oversight.";
  }
  if (error.status === 404) {
    return "This workshop isn't open to you, or was deleted. An admin names officers on Workshop oversight.";
  }
  return error.message;
}

export default function WorkshopUnderOversightPage({
  params
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const { user, loading } = useAuth();

  const [detail, setDetail] = useState<DwOversightDetail | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registryError, setRegistryError] = useState<string | null>(null);
  /* The workshop's files, asked for once the workshop itself has been read. */
  const media = useReaderMedia("oversight", id, detail !== null);
  // C16 (2026-10-17): an Assistant or Regional Director may file notes. Drafts collect in a tray.
  const tray = useReviewTray(user?.id, id);
  const [composing, setComposing] = useState<FlagTarget | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  useEffect(() => {
    if (loading || !canReadWorkshopOversight(user)) return;
    let cancelled = false;
    getOverseenWorkshop(id)
      .then((result) => {
        if (cancelled) return;
        setDetail(result);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(describeFailure(err, user));
      });
    return () => {
      cancelled = true;
    };
  }, [id, loading, user]);

  useEffect(() => {
    if (loading || !canReadWorkshopOversight(user)) return;
    let cancelled = false;
    fetchStageRegistry()
      .then((result) => {
        if (cancelled) return;
        setRegistry(result);
        setRegistryError(null);
      })
      .catch(() => {
        if (cancelled) return;
        // A SEPARATE FAILURE FROM THE WORKSHOP READ, because it means something different: the
        // answers are in hand and the field list that names them is not. Folding it into the error
        // above would say the workshop could not be read when it was.
        setRegistryError("The form couldn't be loaded. Check your connection and try again.");
      });
    return () => {
      cancelled = true;
    };
  }, [loading, user]);

  // EVERY HOOK ABOVE THE REFUSAL BELOW, WITHOUT EXCEPTION — the refusal returns early, and a
  // `useMemo` written under it would run on some renders and not others.
  const overall = useMemo(() => overallPercent(detail?.completeness), [detail]);
  const stages: DwStage[] = useMemo(() => registry?.stages ?? [], [registry]);
  const active = useActiveStage(
    stages.map((stage) => stage.key),
    detail !== null && registry !== null
  );
  const compare = useRoundCompare("oversight", id, stages, detail?.stages, detail?.roundSnapshots, detail?.submissionRound);

  if (!loading && !canReadWorkshopOversight(user)) {
    return (
      <div>
        <PageHeader
          title="Workshop I monitor"
          icon={<Binoculars className="h-5 w-5" aria-hidden />}
        />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          {/*
            PURPLE, DELIBERATELY, ON THE ONE PANEL OF THIS PAGE THAT IS NOT A MINISTRY SURFACE.
            The read-only band above moved to the `ministry` ramp in 0.0.12 and this padlock did
            not: a refusal is shown to somebody who is NOT a ministry account — a designer, a
            professor, an inspector — and painting the ministry's own colour around the notice that
            they are not of the ministry would be a lie told in colour. `AppShell` makes the same
            call one level up and stamps `data-surface="ministry"` only when the page is served.
          */}
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Officer access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            For officers named on this workshop. Designers: use Design workshops.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as{" "}
            <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <div className="mt-7">
            <Link className="field-button" href="/dashboard">
              Back to dashboard
            </Link>
          </div>
        </section>
      </div>
    );
  }

  const readOnly = oversightIsReadOnly(detail);
  const notes = (detail?.inspectionFeedback ?? []) as DwReviewNote[];
  const status = (detail?.status ?? "").trim().toUpperCase();
  const canNote = detail?.mayRecordFeedback === true && (status === "PRE_SUBMISSION" || status === "NEEDS_REVISION");
  const activeStage = stages.find((stage) => stage.key === active) ?? null;

  async function fileTray() {
    if (!tray.drafts.length) return;
    setBusy(true);
    setProblem(null);
    setOutcome(null);
    try {
      const count = tray.drafts.length;
      const answer = await fileNotes("oversight", id, tray.drafts);
      setDetail((held) => (held ? { ...held, ...answer } : held));
      tray.clear();
      setOutcome(count === 1 ? "Note filed." : `${count} notes filed.`);
    } catch (err) {
      setProblem(
        isUnreachable(err)
          ? "No connection — nothing was filed. Your notes are kept."
          : err instanceof Error && err.message.trim()
            ? `Nothing was filed: ${err.message}`
            : "Nothing was filed. Try again."
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <PageHeader
        title={detail?.title?.trim() || "Workshop I monitor"}
        description={
          detail
            ? [
                detail.workshopCode,
                detail.craftName,
                detail.clusterName,
                detail.district,
                detail.state
              ]
                .filter(Boolean)
                .join(" · ") || "No workshop code yet — stage 1 has not been saved."
            : undefined
        }
        icon={<Binoculars className="h-5 w-5" aria-hidden />}
        actions={detail ? <StatusBadge status={detail.status} /> : undefined}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {registryError ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          {registryError}
        </div>
      ) : null}

      {/*
        S0's inline-swap row for this file. A plain `p` and NOT a `.panel`, which is what keeps it
        out of the scoped `[data-surface="ministry"] .panel` rule's way — that selector is (0,2,0)
        and would beat a border utility written on the same element.

        THE `dark:` PAIR IS NOT OPTIONAL HERE. The ministry ramp is literal and does not invert, so
        `bg-ministry-50` under a dark `bg-card` is a pale slab, and `text-ministry-700` on it is
        2.44:1. Both halves move together: `ministry-950/40` ground, `ministry-300` ink.
      */}
      {detail && readOnly ? (
        <p className="mb-4 rounded-md border border-ministry-300 bg-ministry-50 px-3 py-2 text-xs leading-5 text-ink-700 dark:border-ministry-900 dark:bg-ministry-950/40">
          <span className="font-semibold text-ministry-700 dark:text-ministry-300">Read-only.</span>{" "}
          Shown as the designers recorded it.{canNote ? " You can file notes." : ""}
        </p>
      ) : null}

      {detail === null && !error ? (
        <section className="panel p-4 text-sm text-ink-700">Loading the workshop…</section>
      ) : null}

      {detail ? (
        <>
          <section className="panel mb-4 grid gap-2 p-4 sm:grid-cols-2">
            <p className="text-sm text-ink-700">
              <span className="field-label block">Dates</span>
              {formatDate(detail.startDate)}
              {detail.endDate ? ` – ${formatDate(detail.endDate)}` : ""}
            </p>
            <p className="text-sm text-ink-700">
              <span className="field-label block">Designer</span>
              {detail.designerName?.trim() || "Not recorded on stage 1"}
            </p>
            <p className="text-sm text-ink-700">
              <span className="field-label block">Venue</span>
              {detail.venue?.trim() || "Not recorded on stage 1"}
            </p>
            <p className="text-sm text-ink-700">
              <span className="field-label block">Required fields answered</span>
              {overall}% across every stage
            </p>
          </section>

          <section className="panel mb-4 p-4">
            <h2 className="font-display text-base font-bold tracking-tight text-ink-900">
              Who supervises this workshop
            </h2>
            {detail.oversight?.length ? (
              <ul className="mt-2 grid gap-2 sm:grid-cols-2">
                {detail.oversight.map((row) => (
                  <li
                    key={row.capacity}
                    className="rounded-md border border-line-200 bg-surface-50 px-3 py-2"
                  >
                    <p className="field-label">{CAPACITY_LABELS[row.capacity] ?? row.capacity}</p>
                    <p className="mt-1 truncate text-sm font-medium text-ink-900">{row.name}</p>
                    <p className="truncate text-xs text-ink-500">{row.email}</p>
                  </li>
                ))}
              </ul>
            ) : (
              // A DIFFERENT SENTENCE FROM "nothing loaded". You are reading this page, so an
              // oversight row naming you exists; an empty list here would be the server contradicting
              // itself, and saying so is more useful than drawing an empty box.
              <p className="mt-2 text-sm text-ink-700">
                No Assistant Director or Regional Director is recorded on this workshop.
              </p>
            )}
          </section>

          {detail.inspectors?.length ? (
            <section className="panel mb-4 grid gap-2 p-4">
              <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Inspectors</h2>
              <ul className="flex flex-wrap gap-2">
                {detail.inspectors.map((person) => (
                  <li className="flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-2.5 py-1 text-sm" key={person.userId}>
                    <span>{person.name?.trim() || person.email || "An officer no longer named"}</span>
                    {person.clearedAt ? (
                      <ReviewChip tone="fresh">{`Checked, no corrections · ${formatDate(person.clearedAt)}`}</ReviewChip>
                    ) : (
                      <ReviewChip tone="neutral">No verdict yet</ReviewChip>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {canNote ? (
            <section className="panel mb-4 grid gap-3 p-4" aria-labelledby="officer-notes-heading">
              <h2 id="officer-notes-heading" className="font-display text-base font-bold tracking-tight text-ink-900">
                Your notes
              </h2>
              <ActionGroup>
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={() => setComposing({ stageKey: active ?? "", fieldKey: null })}
                >
                  <MessageSquarePlus className="h-4 w-4" aria-hidden />
                  Add a note
                </button>
              </ActionGroup>
              <p className="text-xs leading-5 text-ink-500">Or use Flag beside any field. Notes go to the designers.</p>
              <div role="alert" className={problem ? "rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" : "sr-only"}>
                {problem}
              </div>
              <p
                role="status"
                className={outcome ? "rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm text-success-600" : "sr-only"}
              >
                {outcome}
              </p>
            </section>
          ) : null}

          {canNote ? (
            <NoteTray
              actions={
                <button type="button" className="field-button" disabled={busy} onClick={() => void fileTray()}>
                  {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Send className="h-4 w-4" aria-hidden />}
                  {fileLabel(tray.drafts.length)}
                </button>
              }
              drafts={tray.drafts}
              onRemove={tray.remove}
              stages={stages}
            />
          ) : null}

          {notes.length || canNote ? (
            <NoteRegister notes={notes} stages={stages} truncated={detail.inspectionFeedbackTruncated} />
          ) : null}

          <ReaderFilesPanel
            failure={media.failure}
            list={media.list}
            onRetry={media.retry}
            state={media.state}
          />
          {/* WHERE THE REPORT STANDS, AND WHO DECIDED — the sign-off is the Ministry Admin's, and a
              supervisor reading the workshop is owed who approved it, who handed it on, and every
              decision on the way. */}
          {signOffLines(detail).length || detail.decisions?.length ? (
            <section className="panel mb-4 grid gap-3 p-4">
              <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Decisions on this report</h2>
              {signOffLines(detail).map((line) => (
                <p className="text-sm leading-6 text-ink-700" key={line}>
                  {line}
                </p>
              ))}
              {detail.decisions?.length ? (
                <ul className="grid gap-2">
                  {detail.decisions.map((decision) => (
                    <li className="rounded-md border border-line-200 bg-surface-50 px-3 py-2" key={decision.id}>
                      <p className="text-sm font-medium text-ink-900">{decisionKindLabel(decision.kind)}</p>
                      {decision.note?.trim() ? (
                        <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-ink-700">{decision.note}</p>
                      ) : null}
                      <p className="mt-1 text-xs leading-5 text-ink-500">
                        {decision.actorName?.trim() || "Somebody no longer on record"}
                        {decision.at ? ` · ${formatDate(decision.at)}` : ""}
                      </p>
                    </li>
                  ))}
                </ul>
              ) : null}
            </section>
          ) : null}

          <RoundCompareControls compare={compare} />

          {registry === null ? (
            registryError ? null : (
              <section className="panel p-4 text-sm text-ink-700">Loading the form…</section>
            )
          ) : (
            <ReaderMediaProvider list={media.list} state={media.state}>
              <ReviewLayout active={active} changes={compare.changes} completeness={detail.completeness} stages={stages}>
                {stages.map((stage) => (
                  <ReviewReadStage
                    changes={compare.changes?.[stage.key]}
                    data={detail.stages?.[stage.key]}
                    definition={detail.customSections ?? null}
                    key={stage.key}
                    notes={notes.filter((note) => note.stageKey === stage.key)}
                    onFlag={canNote ? setComposing : undefined}
                    onlyChanges={compare.onlyChanges && compare.changes !== null}
                    registry={registry}
                    score={detail.completeness?.[stage.key]}
                    stage={stage}
                  />
                ))}
              </ReviewLayout>
            </ReaderMediaProvider>
          )}
          <NoteComposer onAdd={tray.add} onClose={() => setComposing(null)} stages={stages} target={composing} />

          {canNote ? (
            <ReviewDecisionBar stage={activeStage}>
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => setComposing({ stageKey: active ?? "", fieldKey: null })}
              >
                <MessageSquarePlus className="h-4 w-4" aria-hidden />
                Note
              </button>
              <button type="button" className="field-button" disabled={busy || !tray.drafts.length} onClick={() => void fileTray()}>
                <Send className="h-4 w-4" aria-hidden />
                {tray.drafts.length ? fileLabel(tray.drafts.length) : "File notes"}
              </button>
            </ReviewDecisionBar>
          ) : null}
        </>
      ) : null}
    </div>
  );
}
