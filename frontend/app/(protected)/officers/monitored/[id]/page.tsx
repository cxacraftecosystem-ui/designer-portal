"use client";

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
  const media = useReaderMedia("oversight", id, detail !== null);
  const tray = useReviewTray(user?.id, id, "supervise");
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
        setRegistryError("The form couldn't be loaded. Check your connection and try again.");
      });
    return () => {
      cancelled = true;
    };
  }, [loading, user]);

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
