"use client";

/**
 * ONE WORKSHOP UNDER INSPECTION — every stage, read-only, with who wrote each field.
 *
 * ── WHAT AN INSPECTION IS FOR ───────────────────────────────────────────────────────────────────
 *
 * "Who wrote this field" is most of what an inspection is for, so every value carries the same
 * {@link FieldProvenance} line the designer's stage form draws. There is no stage form, no Save, no
 * delete and no report button here: the server refuses all of it. What an inspector writes is a
 * NOTE — filed, or sent back with the report — and, since 2026-10-17, a verdict.
 *
 * `readOnly` STAYS TRUE ON THIS PAYLOAD AND THE NOTES ARE GATED ON A SECOND KEY, `mayRecordFeedback`,
 * which fails CLOSED on its absence. Every link into the workshop tree is asked for through
 * {@link inspectionMayOpen}, which is false for a read: those pages 404 for an inspector.
 *
 * ── THE REVIEW LAYOUT (2026-10-17) ──────────────────────────────────────────────────────────────
 *
 * A fixed stage menu and decision bar (A18), a Flag on every field that drafts a note into a tray and
 * one "File 3 notes" / "Send back with 3 notes" (B2), each note marked "Edited since" or "Untouched"
 * (A19), "Checked, no corrections" for the round (C5), and "Changed since round N" (C13). The
 * single-note routes are unchanged — Android's queued notes replay through them.
 *
 * ── THE REGISTRY IS THE LIST OF STAGES, NOT THE PAYLOAD ─────────────────────────────────────────
 *
 * Iterating the registry rather than `Object.keys(detail.stages)` is what makes an untouched stage
 * appear at all — on an inspection, a stage with nothing in it is a finding.
 */

import Link from "next/link";
import { use, useEffect, useMemo, useState } from "react";
import { FileSearch, Loader2, Lock, MessageSquarePlus, Send, Undo2 } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { ReaderFilesPanel, ReaderMediaProvider, useReaderMedia } from "@/components/designworkshop/ReaderWorkshopMedia";
import { NoteComposer, NoteRegister, NoteTray, useReviewTray } from "@/components/designworkshop/review/ReviewNotes";
import { ReviewReadStage, type FlagTarget } from "@/components/designworkshop/review/ReviewReadStage";
import {
  ReviewDecisionBar,
  ReviewLayout,
  RoundCompareControls,
  useActiveStage,
  useRoundCompare
} from "@/components/designworkshop/review/ReviewWorkspace";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { Toggle } from "@/components/ui/Toggle";
import { ApiError } from "@/lib/api";
import { signOffLines } from "@/lib/designWorkshopApprovals";
import {
  getWorkshopUnderInspection,
  inspectionIsReadOnly,
  inspectionMayOpen,
  inspectionRefusalMeansNoPosts,
  type DwInspectionDetail
} from "@/lib/designWorkshopInspections";
import {
  dwMayRecordFeedback,
  fetchStageRegistry,
  overallPercent,
  type DwInspectionFeedbackKeys,
  type DwRegistry,
  type DwStage
} from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canInspectDesignWorkshops, roleLabel } from "@/lib/permissions";
import {
  fileLabel,
  fileNotes,
  sendBackLabel,
  sendBackWithNotes,
  setRoundCleared,
  type DwNotesAnswer,
  type DwReviewAidKeys,
  type DwReviewNote
} from "@/lib/reviewAids";
import type { User } from "@/lib/types";

type Detail = DwInspectionDetail & DwInspectionFeedbackKeys & DwReviewAidKeys;

/**
 * The failures this page can suffer, told apart in words. The 404 is deliberately vague: the server
 * gives the same one for "not an inspector", "no such workshop", "deleted" and "not yours".
 */
function describeFailure(error: unknown, user: User | null | undefined): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) return "No connection. Try again.";
  if (inspectionRefusalMeansNoPosts(error, user)) return "You aren't appointed to inspect any workshop.";
  if (error.status === 403) return error.message;
  if (error.status === 404) return "This workshop isn't open to you.";
  return error.message || "This workshop couldn't be loaded.";
}

/** Why notes are closed, by the report's status — the server's refusal, said before the press. */
function closedInspectionSentence(status: string, handedOnAt: string | null): string {
  if (status === "APPROVED") return "Approved — inspection is closed.";
  if (status === "SUBMITTED" && handedOnAt) return "Handed on to the office — inspection is closed.";
  return "Not handed in for inspection yet.";
}

function failureSentence(err: unknown, what: string): string {
  if (isUnreachable(err)) return `No connection — nothing was ${what}. Your notes are kept.`;
  return err instanceof Error && err.message.trim() ? `Nothing was ${what}: ${err.message}` : `Nothing was ${what}. Try again.`;
}

export default function WorkshopUnderInspectionPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { user, loading } = useAuth();
  const confirm = useConfirm();

  const [detail, setDetail] = useState<Detail | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registryError, setRegistryError] = useState<string | null>(null);
  const [composing, setComposing] = useState<FlagTarget | null>(null);
  const [busy, setBusy] = useState<"file" | "send" | "clear" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  const allowed = !loading && canInspectDesignWorkshops(user);
  const media = useReaderMedia("inspection", id, detail !== null);
  const tray = useReviewTray(user?.id, id, "inspect");

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    getWorkshopUnderInspection(id)
      .then((result) => {
        if (cancelled) return;
        setDetail(result as Detail);
        setError(null);
      })
      .catch((err) => {
        if (!cancelled) setError(describeFailure(err, user));
      });
    return () => {
      cancelled = true;
    };
  }, [id, allowed, user]);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    fetchStageRegistry()
      .then((result) => {
        if (cancelled) return;
        setRegistry(result);
        setRegistryError(null);
      })
      .catch(() => {
        // A separate failure: the answers are in hand and the field list that names them is not.
        if (!cancelled) setRegistryError("Couldn't load the form. Try again.");
      });
    return () => {
      cancelled = true;
    };
  }, [allowed]);

  // EVERY HOOK ABOVE THE REFUSAL BELOW — the refusal returns early.
  const stages: DwStage[] = useMemo(() => registry?.stages ?? [], [registry]);
  const overall = useMemo(() => overallPercent(detail?.completeness), [detail]);
  const active = useActiveStage(
    stages.map((stage) => stage.key),
    detail !== null && registry !== null
  );
  const compare = useRoundCompare("inspection", id, stages, detail?.stages, detail?.roundSnapshots, detail?.submissionRound);

  if (!loading && !canInspectDesignWorkshops(user)) {
    return (
      <div>
        <PageHeader title="Workshop under inspection" icon={<FileSearch className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">Inspector / Reviewer access required</h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">Only appointed inspectors can open it here.</p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
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

  const readOnly = inspectionIsReadOnly(detail);
  const mayOpenTheWorkshop = inspectionMayOpen("stages", detail);
  const notes = (detail?.inspectionFeedback ?? []) as DwReviewNote[];
  const mayRecord = dwMayRecordFeedback(detail);
  const status = (detail?.status ?? "").trim().toUpperCase();
  const underReview = status === "PRE_SUBMISSION" || status === "NEEDS_REVISION";
  const canWrite = mayRecord && underReview;
  const round = detail?.submissionRound ?? 0;
  const myNotesThisRound = notes.some((note) => note.actorId === user?.id && note.round === round);
  const activeStage = stages.find((stage) => stage.key === active) ?? null;

  /** The server answers with the register as it now stands; it replaces ours, never appends. */
  function adopt(answer: DwNotesAnswer) {
    setDetail((held) => (held ? { ...held, ...answer } : held));
  }

  async function send(kind: "file" | "send") {
    if (!tray.drafts.length) return;
    if (kind === "send") {
      const agreed = await confirm({
        title: "Send this report back to its designers?",
        tone: "warning",
        confirmLabel: sendBackLabel(tray.drafts.length),
        body: (
          <>
            <span className="block">The report moves to Needs revision for its designers.</span>
            <span className="mt-2 block">Saving a corrected stage hands it back in.</span>
          </>
        ),
        note: "Recorded under your name. It can't be edited afterwards."
      });
      if (!agreed) return;
    }
    setBusy(kind);
    setProblem(null);
    setOutcome(null);
    try {
      const count = tray.drafts.length;
      const answer = kind === "send" ? await sendBackWithNotes(id, tray.drafts) : await fileNotes("inspection", id, tray.drafts);
      adopt(answer);
      tray.clear();
      setOutcome(kind === "send" ? "Sent back for revision." : count === 1 ? "Note filed." : `${count} notes filed.`);
    } catch (err) {
      setProblem(failureSentence(err, kind === "send" ? "sent back" : "filed"));
    } finally {
      setBusy(null);
    }
  }

  async function setCleared(next: boolean) {
    setBusy("clear");
    setProblem(null);
    setOutcome(null);
    try {
      adopt(await setRoundCleared(id, next));
    } catch (err) {
      setProblem(failureSentence(err, "recorded"));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div>
      <PageHeader
        title={detail?.title?.trim() || "Workshop under inspection"}
        description={
          detail
            ? [detail.workshopCode, detail.craftName, detail.clusterName, detail.district, detail.state].filter(Boolean).join(" · ") ||
              "No workshop code yet"
            : undefined
        }
        icon={<FileSearch className="h-5 w-5" aria-hidden />}
        actions={detail ? <StatusBadge status={detail.status} /> : undefined}
      />

      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {registryError ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          {registryError}
        </div>
      ) : null}

      {detail && readOnly ? (
        <p className="mb-4 rounded-md border border-purple-300 bg-purple-50 px-3 py-2 text-xs leading-5 text-ink-700">
          <span className="font-semibold text-purple-700 dark:text-purple-300">Read-only.</span> Shown as the designers recorded it.
        </p>
      ) : null}

      {detail && signOffLines(detail).length ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {signOffLines(detail).join(" ")}
        </p>
      ) : null}

      {detail === null && !error ? <section className="panel p-4 text-sm text-ink-700">Loading the workshop…</section> : null}

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

          {mayOpenTheWorkshop ? (
            <p className="mb-4 text-sm">
              <Link className="text-purple-700 underline-offset-2 hover:underline dark:text-purple-300" href={`/design-workshops/${id}`}>
                Open this workshop
              </Link>
            </p>
          ) : null}

          <section className="panel mb-4 grid gap-3 p-4" aria-labelledby="your-review-heading">
            <h2 id="your-review-heading" className="font-display text-base font-bold tracking-tight text-ink-900">
              Your review · round {round}
            </h2>
            {!mayRecord ? (
              <p className="text-sm leading-6 text-ink-700">Notes aren&apos;t available right now.</p>
            ) : !underReview ? (
              <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
                {closedInspectionSentence(status, detail.handedOnAt ?? null)}
              </p>
            ) : (
              <>
                <Toggle
                  label="Checked, no corrections"
                  description={myNotesThisRound ? "You've filed notes on this round." : "Shown to the approving authority."}
                  status={detail.myClearedAt ? `Since ${formatDate(detail.myClearedAt)}` : undefined}
                  checked={Boolean(detail.myClearedAt)}
                  onChange={(next) => void setCleared(next)}
                  busy={busy === "clear"}
                  busyLabel="Saving…"
                  disabled={myNotesThisRound && !detail.myClearedAt}
                />
                <ActionGroup>
                  <button type="button" className="field-button-secondary" onClick={() => setComposing({ stageKey: active ?? "", fieldKey: null })}>
                    <MessageSquarePlus className="h-4 w-4" aria-hidden />
                    Add a note
                  </button>
                </ActionGroup>
                <p className="text-xs leading-5 text-ink-500">Or use Flag beside any field.</p>
              </>
            )}
            <div
              role="alert"
              className={problem ? "rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700" : "sr-only"}
            >
              {problem}
            </div>
            <p
              role="status"
              className={outcome ? "rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm leading-6 text-success-600" : "sr-only"}
            >
              {outcome}
            </p>
          </section>

          {canWrite ? (
            <NoteTray
              actions={
                <>
                  <button type="button" className="field-button-secondary" disabled={busy !== null} onClick={() => void send("file")}>
                    {busy === "file" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Send className="h-4 w-4" aria-hidden />}
                    {fileLabel(tray.drafts.length)}
                  </button>
                  <button type="button" className="field-button" disabled={busy !== null} onClick={() => void send("send")}>
                    {busy === "send" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Undo2 className="h-4 w-4" aria-hidden />}
                    {sendBackLabel(tray.drafts.length)}
                  </button>
                </>
              }
              drafts={tray.drafts}
              footnote="Sending back moves it to Needs revision. Neither can be undone."
              onRemove={tray.remove}
              stages={stages}
            />
          ) : null}

          <NoteRegister notes={notes} stages={stages} truncated={detail.inspectionFeedbackTruncated} />

          <ReaderFilesPanel failure={media.failure} list={media.list} onRetry={media.retry} state={media.state} />

          <RoundCompareControls compare={compare} />

          {registry === null ? (
            registryError ? null : <section className="panel p-4 text-sm text-ink-700">Loading the form…</section>
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
                    onFlag={canWrite ? setComposing : undefined}
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

          {canWrite ? (
            <ReviewDecisionBar stage={activeStage}>
              <button type="button" className="field-button-secondary" onClick={() => setComposing({ stageKey: active ?? "", fieldKey: null })}>
                <MessageSquarePlus className="h-4 w-4" aria-hidden />
                Note
              </button>
              <button
                type="button"
                className="field-button"
                disabled={busy !== null}
                onClick={() =>
                  tray.drafts.length ? void send("send") : setComposing({ stageKey: active ?? "", fieldKey: null })
                }
              >
                <Undo2 className="h-4 w-4" aria-hidden />
                {sendBackLabel(tray.drafts.length)}
              </button>
            </ReviewDecisionBar>
          ) : null}
        </>
      ) : null}
    </div>
  );
}
