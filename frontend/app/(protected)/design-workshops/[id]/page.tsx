"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { BadgeCheck, DraftingCompass, FileClock, FileText, Images, Layers, ListChecks, ListPlus, Loader2, QrCode, Send, SquarePen, Undo2, GitCompareArrows} from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { DictationConsentCard } from "@/components/designworkshop/DictationConsentCard";
import { HeldPostNotice, heldPostReason, useHeldPostRefusal } from "@/components/designworkshop/HeldPostNotice";
import { WorkshopSearchPanel } from "@/components/designworkshop/WorkshopSearchPanel";
import { WorkshopQuestionnairesCard } from "@/components/questionnaires/WorkshopQuestionnairesCard";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { PageHeader } from "@/components/PageHeader";
import { RecordDangerZone } from "@/components/records/RecordDangerZone";
import { StatusBadge } from "@/components/StatusBadge";
import { ActionGroup } from "@/components/ui/ActionGroup";
import {
  dwFeedbackRounds,
  dwFrozenReason,
  dwPatchableFrom,
  getDesignWorkshop,
  patchDesignWorkshop,
  stageProgressPercent,
  type DwDecision,
  type DwInspectionFeedback,
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
import { canApproveDesignWorkshops, canRunDesignWorkshops, isAdmin } from "@/lib/permissions";
import { decisionKindLabel, signOffLines, type DwSignOffFacts } from "@/lib/designWorkshopApprovals";
import { readinessSummary, workshopReadiness, type WorkshopReadiness } from "@/lib/submissionReadiness";
import { neverReconciled } from "@/lib/workshopOpenability";
import { buildWorkshopSearchIndex, emptyWorkshopSearchIndex } from "@/lib/workshopSearch";
import { WORKSHOP_STATUS_IS } from "./readiness/page";

function StageProgress({ score }: { score: DwStageCompleteness | undefined }) {
  const percent = score?.percent ?? 0;
  const complete = score?.isComplete ?? false;
  return (
    <div className="flex min-w-0 flex-1 items-center gap-3">
      <ProgressBar percent={percent} tone={complete ? "done" : "brand"} className="max-w-40" />
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

function draftHeader(draft: DwDraft): Omit<DwSummary, "id"> {
  return {
    title: draft.header.title,
    templateId: draft.header.templateId,
    status: draft.header.status,
    workshopCode: draft.header.workshopCode,
    workshopKind: draft.header.workshopKind,
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
    handedOnAt: draft.header.handedOnAt ?? null,
    dictationConsent: draft.consent?.decision ?? "NOT_RECORDED",
    dictationConsentAt: draft.consent?.recordedAt ?? null,
    dictationConsentById: draft.consent?.recordedById ?? null,
    createdById: draft.ownerUserId ?? "",
    createdAt: new Date(draft.createdAt).toISOString(),
    updatedAt: new Date(draft.updatedAt).toISOString(),
    deletedAt: null
  };
}

type DwRegisterProvenance = {
  sanctionOrder?: { sanctionOrderNo: string; sanctionOrderDate: string | null } | null;
};

function readRegisterProvenance(payload: unknown): DwRegisterProvenance {
  const found: DwRegisterProvenance = {};
  if (!payload || typeof payload !== "object") return found;
  if (!("sanctionOrder" in payload)) return found;
  const value = (payload as { sanctionOrder: unknown }).sanctionOrder;
  if (value === null) {
    found.sanctionOrder = null;
    return found;
  }
  if (value && typeof value === "object") {
    const row = value as { sanctionOrderNo?: unknown; sanctionOrderDate?: unknown };
    if (typeof row.sanctionOrderNo === "string") {
      found.sanctionOrder = {
        sanctionOrderNo: row.sanctionOrderNo,
        sanctionOrderDate: typeof row.sanctionOrderDate === "string" ? row.sanctionOrderDate : null
      };
    }
  }
  return found;
}

function submissionScope(stagesTotal: number | null): string {
  const stages =
    stagesTotal === null
      ? "Every stage stays editable"
      : `All ${stagesTotal} stages stay editable`;
  return (
    `${stages} until the Ministry Admin approves the report.`
  );
}

const SUBMISSION_IS_REVERSIBLE =
  "You can withdraw it until someone acts on it.";

const DW_STATUSES: DwStatus[] = [
  "DRAFT",
  "IN_PROGRESS",
  "COMPLETE",
  "PRE_SUBMISSION",
  "NEEDS_REVISION",
  "SUBMITTED",
  "APPROVED",
  "ARCHIVED"
];

type SubmissionAction = {
  status: DwStatus;
  label: string;
  icon: typeof Send;
  meaning: string;
  primary: boolean;
};

const MARK_COMPLETE: SubmissionAction = {
  status: "COMPLETE",
  label: "Mark complete",
  icon: BadgeCheck,
  meaning: "Fieldwork is finished.",
  primary: false
};

const SEND_FOR_REVIEW: SubmissionAction = {
  status: "PRE_SUBMISSION",
  label: "Hand in for inspection",
  icon: Send,
  meaning: "Officers read it and suggest corrections.",
  primary: true
};

const HAND_BACK_IN: SubmissionAction = {
  status: "PRE_SUBMISSION",
  label: "Hand it back in",
  icon: Send,
  meaning: "Corrections are done; saving a changed stage also does this.",
  primary: true
};

const WITHDRAW: SubmissionAction = {
  status: "IN_PROGRESS",
  label: "Withdraw from inspection",
  icon: Undo2,
  meaning: "Take it back while you work on it.",
  primary: false
};

const REOPEN: SubmissionAction = {
  status: "IN_PROGRESS",
  label: "Reopen for editing",
  icon: Undo2,
  meaning: "Back to In progress.",
  primary: false
};

function candidatesFor(status: string): SubmissionAction[] {
  switch (status) {
    case "DRAFT":
    case "IN_PROGRESS":
      return [MARK_COMPLETE, SEND_FOR_REVIEW];
    case "COMPLETE":
      return [SEND_FOR_REVIEW, REOPEN];
    case "PRE_SUBMISSION":
      return [WITHDRAW];
    case "NEEDS_REVISION":
      return [HAND_BACK_IN, WITHDRAW];
    case "APPROVED":
      return [];
    case "SUBMITTED":
    case "ARCHIVED":
      return [REOPEN, SEND_FOR_REVIEW];
    default:
      return [];
  }
}

function actionsFor(status: string): SubmissionAction[] {
  const patchable = dwPatchableFrom(status);
  return candidatesFor(status).filter((action) => patchable.includes(action.status));
}

function noActionsReason(status: string, signOff: string[] = [], handedOn = false): string | null {
  if (handedOn && status === "SUBMITTED") {
    const said = signOff.length ? `${signOff.join(" ")} ` : "Handed on to the office. ";
    return `${said}To change it, ask the Ministry Admin to return it.`;
  }
  if (actionsFor(status).length > 0) return null;
  if (candidatesFor(status).length > 0) {
    return (
      "No next step from this status. Reload and try again."
    );
  }
  if (status === "APPROVED") {
    const said = signOff.length ? `${signOff.join(" ")} ` : "Approved. ";
    return `${said}To change it, ask the Ministry Admin to withdraw the approval.`;
  }
  return (
    "No next step from this status. Reload and try again."
  );
}

function InspectionFeedbackPanel({
  rows,
  truncated,
  status
}: {
  rows: DwInspectionFeedback[];
  truncated: boolean;
  status: string;
}) {
  const rounds = dwFeedbackRounds(rows);
  if (rounds.length === 0) return null;
  return (
    <section className="panel mb-5 grid gap-3 p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-sm font-medium text-ink-900">What the officers asked for</h2>
        <span className="text-xs text-ink-500">
          {rows.length} suggestion{rows.length === 1 ? "" : "s"} on record
        </span>
      </div>
      {status === "NEEDS_REVISION" ? (
        <p className="text-sm leading-6 text-ink-700">
          Sent back. Fix and save the stages named below — that hands it back in.
        </p>
      ) : null}
      {rounds.map((group) => (
        <div className="grid gap-2" key={group.round}>
          <h3 className="field-label">Round {group.round}</h3>
          <ul className="grid gap-2">
            {group.rows.map((row) => (
              <li className="rounded-md border border-line-200 bg-field-50 px-3 py-2" key={row.id}>
                <p className="whitespace-pre-wrap text-sm leading-6 text-ink-900">{row.note}</p>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  {row.actorName?.trim() || "An officer no longer named"}
                  {row.byApprovingAuthority ? " · the Ministry Admin" : ""}
                  {row.stageKey ? ` · about ${row.stageKey}` : " · about the report as a whole"}
                  {row.sentBack ? " · sent it back" : ""}
                </p>
              </li>
            ))}
          </ul>
        </div>
      ))}
      {truncated ? (
        <p className="text-xs leading-5 text-amber-800 dark:text-amber-100">
          Older suggestions are not shown.
        </p>
      ) : null}
    </section>
  );
}

function SubmissionCard({
  workshopId,
  status,
  readiness,
  neverSent,
  headerUnsent,
  unsentStages,
  offline,
  onStatusChanged,
  readOnlyReason = null,
  signOff = [],
  handedOn = false
}: {
  workshopId: string;
  status: string;
  readiness: WorkshopReadiness | null;
  neverSent: boolean;
  headerUnsent: boolean;
  unsentStages: number;
  offline: boolean;
  onStatusChanged: (status: string) => void;
  readOnlyReason?: string | null;
  signOff?: string[];
  handedOn?: boolean;
}) {
  const { user } = useAuth();
  const confirm = useConfirm();
  const [busy, setBusy] = useState<DwStatus | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  const mayDecide = canRunDesignWorkshops(user) && !readOnlyReason;
  const token = (status ?? "").trim().toUpperCase();
  const known = (DW_STATUSES as string[]).includes(token);
  const actions = handedOn && token === "SUBMITTED" ? [] : actionsFor(token);
  const outstanding = readiness ? readiness.blocking.length : null;
  const scope = submissionScope(readiness ? readiness.stagesTotal : null);

  async function move(action: SubmissionAction) {
    const goingBack = action.status === "IN_PROGRESS";
    const withdrawing = action === WITHDRAW;
    const shortfall =
      outstanding === null
        ? "Couldn't count outstanding required fields."
        : outstanding === 0
          ? "No required field is outstanding."
          : `${outstanding} required field${outstanding === 1 ? " is" : "s are"} still empty. That's allowed.`;

    const unsent =
      goingBack || unsentStages === 0
        ? null
        : `${unsentStages} stage${unsentStages === 1 ? " is" : "s are"} not uploaded yet — others will see this workshop as ` +
          `${action.status.replace(/_/g, " ").toLowerCase()} without ${unsentStages === 1 ? "it" : "them"}. ` +
          "Use Sync now in the banner above.";

    const agreed = await confirm({
      title: withdrawing
        ? "Withdraw this report from inspection?"
        : goingBack
        ? "Reopen this workshop for editing?"
        : outstanding && outstanding > 0
          ? `${action.label} with ${outstanding} required field${outstanding === 1 ? "" : "s"} outstanding?`
          :
            unsentStages > 0
            ? `${action.label} with ${unsentStages} stage${unsentStages === 1 ? "" : "s"} unsent?`
            : `${action.label}?`,
      tone: goingBack ? "neutral" : "warning",
      confirmLabel: action.label,
      body: withdrawing ? (
        "It goes back to In progress. Nothing is deleted, and you can hand it in again."
      ) : goingBack ? (
        "It goes back to In progress. Nothing is deleted."
      ) : (
        <>
          <span className="block">{shortfall}</span>
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
      const updated = await patchDesignWorkshop(workshopId, { status: action.status });
      onStatusChanged(updated.status);
      const stillHere =
        unsentStages === 0
          ? ""
          : ` ${unsentStages} stage${unsentStages === 1 ? " is" : "s are"} not uploaded yet.`;
      setOutcome(
        (withdrawing
          ? "Withdrawn — back to In progress."
          : goingBack
          ? "Reopened."
          : `Saved — now ${(updated.status ?? "")
              .replace(/_/g, " ")
              .toLowerCase()}.`) + stillHere
      );
    } catch (err) {
      if (isUnreachable(err)) {
        setProblem(
          "No connection — status unchanged. Your work is saved on this device."
        );
      } else {
        setProblem(
          err instanceof Error && err.message.trim()
            ? `The status was not changed: ${err.message}`
            : "The status wasn't changed. Please try again."
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
        <StatusBadge status={known ? token : status} />
      </div>

      {readiness ? (
        <p className="text-sm leading-6 text-ink-700">{readinessSummary(readiness)}</p>
      ) : (
        <p className="text-sm leading-6 text-amber-800 dark:text-amber-100">
          Couldn&rsquo;t count what&rsquo;s outstanding. Reload to try again.
        </p>
      )}

      {outstanding && outstanding > 0 ? (
        <p className="text-sm leading-6 text-ink-700">
          {WORKSHOP_STATUS_IS}{" "}
          <Link href={`/design-workshops/${workshopId}/readiness`} className="font-medium text-purple-700 dark:text-purple-300 underline">
            See {outstanding} outstanding field{outstanding === 1 ? "" : "s"}
          </Link>
        </p>
      ) : null}

      {!known ? (
        <p className="text-sm leading-6 text-amber-800 dark:text-amber-100">
          No next step from this status. Reload and try again.
        </p>
      ) : neverSent ? (
        <p className="text-sm leading-6 text-ink-700">
          Not uploaded yet. Sync it from the workshops list to change its status.
        </p>
      ) : readOnlyReason ? (
        <p className="text-sm leading-6 text-ink-700">{readOnlyReason}</p>
      ) : !mayDecide ? (
        <p className="text-sm leading-6 text-ink-700">
          Only this workshop&rsquo;s designers or an admin can change its status.
        </p>
      ) : (
        <>
          <ActionGroup align="start">
            {actions.map((action) => {
              const Icon = action.icon;
              const running = busy === action.status;
              return (
                <button
                  key={action.status}
                  type="button"
                  className={action.primary ? "field-button" : "field-button-secondary"}
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
          </ActionGroup>
          {actions.length === 0 ? (
            <p className="text-sm leading-6 text-ink-700">{noActionsReason(token, signOff, handedOn)}</p>
          ) : null}
          <dl className="grid gap-1">
            {actions.map((action) => (
              <div key={action.status} className="text-xs leading-5">
                <dt className="inline font-medium text-ink-700">{action.label}: </dt>
                <dd className="inline text-ink-500">{action.meaning}</dd>
              </div>
            ))}
          </dl>
        </>
      )}

      {offline && mayDecide && known && !neverSent ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          You&rsquo;re offline. Status changes need a connection.
        </p>
      ) : null}

      {unsentStages > 0 && mayDecide && known && !neverSent ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          {unsentStages} stage{unsentStages === 1 ? " is" : "s are"} not uploaded yet — others would see the workshop as
          finished without {unsentStages === 1 ? "it" : "them"}. Sync from the banner above first.
        </p>
      ) : null}

      {headerUnsent ? (
        <p className="text-xs leading-5 text-ink-500">
          Title or notes not uploaded yet — the status may be out of date.
        </p>
      ) : null}

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

    </section>
  );
}

export default function DesignWorkshopStagesPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { user } = useAuth();

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  const [serverSchemaVersion, setServerSchemaVersion] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  const [unopenable, setUnopenable] = useState(false);
  const [consentByName, setConsentByName] = useState<string | null>(null);
  const [inspectionFeedback, setInspectionFeedback] = useState<DwInspectionFeedback[]>([]);
  const [feedbackTruncated, setFeedbackTruncated] = useState(false);
  const [registerProvenance, setRegisterProvenance] = useState<DwRegisterProvenance>({});
  const [confirmedStatus, setConfirmedStatus] = useState<string | null>(null);
  const [signOff, setSignOff] = useState<DwSignOffFacts | null>(null);
  const [decisions, setDecisions] = useState<DwDecision[]>([]);

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
        setError(
          err instanceof Error
            ? `Couldn't load the stages: ${err.message}`
            : "Couldn't load the stages."
        );
        return;
      }

      const local = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
      if (cancelled) return;
      if (local) setDraft(local);

      if (isLocalWorkshopId(id) && local && !local.remoteId) {
        return;
      }

      try {
        const detail = await getDesignWorkshop(local?.remoteId ?? id);
        if (cancelled) return;
        setServerSchemaVersion(detail.schemaVersion);
        setConsentByName(detail.dictationConsentByName ?? null);
        setInspectionFeedback(detail.inspectionFeedback ?? []);
        setFeedbackTruncated(detail.inspectionFeedbackTruncated === true);
        setSignOff({
          status: String(detail.status ?? ""),
          approvedAt: detail.approvedAt ?? null,
          approvedById: detail.approvedById ?? null,
          approvedByName: detail.approvedByName ?? null,
          handedOnAt: detail.handedOnAt ?? null,
          handedOnById: detail.handedOnById ?? null,
          handedOnByName: detail.handedOnByName ?? null,
          handedOnTo: detail.handedOnTo ?? null
        });
        setDecisions(detail.decisions ?? []);
        setRegisterProvenance(readRegisterProvenance(detail));
        const merged = await adoptServerDetail(detail, loadedRegistry);
        if (cancelled) return;
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
        if (isUnreachable(err)) {
          setOffline(true);
          if (!local) setError("You're offline and this workshop isn't on this device.");
          return;
        }
        if (err instanceof ApiError && err.status === 404 && (!local || neverReconciled(local))) {
          setUnopenable(true);
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const stages = registry?.stages ?? [];
  const stageCount = registry ? registry.stages.length : null;
  const completeness = useMemo(
    () => (registry && draft ? localCompleteness(registry, draft) : {}),
    [registry, draft]
  );
  const searchIndex = useMemo(
    () => (registry && draft ? buildWorkshopSearchIndex(registry, draft) : emptyWorkshopSearchIndex()),
    [registry, draft]
  );
  const readiness = useMemo(
    () => (registry && draft ? workshopReadiness(registry, draft, id) : null),
    [registry, draft, id]
  );
  const detail: DwSummary | null = draft ? { ...draftHeader(draft), id: draft.remoteId ?? draft.localId } : null;
  const unsentStages = draft
    ? Object.values(draft.stages).filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0).length
    : 0;
  const neverSent = draft ? isLocalWorkshopId(id) && !draft.remoteId : false;
  const postRefusal = useHeldPostRefusal(id);
  const frozenReason = detail
    ? dwFrozenReason({
        status: confirmedStatus ?? detail.status,
        handedOnAt: signOff?.handedOnAt ?? detail.handedOnAt ?? null
      })
    : null;

  if (unopenable) {
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
            Workshop not found.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            It doesn&rsquo;t exist or hasn&rsquo;t been shared with you. Ask its designer or an admin to add you.
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
              "Craft, cluster and place come from stage 1."
            : "Loading the workshop record…"
        }
        icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
        actions={
          <>
            {canRunDesignWorkshops(user) && !neverSent && postRefusal === null && frozenReason === null ? (
              <Link href={`/design-workshops/${id}/edit`} className="field-button-secondary">
                <SquarePen className="h-4 w-4" aria-hidden />
                Update details
              </Link>
            ) : null}
            <Link href={`/design-workshops/${id}/codes`} className="field-button-secondary">
              <QrCode className="h-4 w-4" aria-hidden />
              Cards &amp; tags
            </Link>
            <Link href={`/design-workshops/${id}/sketches-and-prototypes`} className="field-button-secondary">
              <DraftingCompass className="h-4 w-4" aria-hidden />
              Sketches &amp; prototypes
            </Link>
            <Link href={`/design-workshops/${id}/photos`} className="field-button-secondary">
              <Images className="h-4 w-4" aria-hidden />
              Import photographs
            </Link>
            <Link href={`/design-workshops/${id}/ai-layers`} className="field-button-secondary">
              <Layers className="h-4 w-4" aria-hidden />
              AI layers
            </Link>
            <Link href={`/design-workshops/${id}/custom-sections`} className="field-button-secondary">
              <ListPlus className="h-4 w-4" aria-hidden />
              This workshop&apos;s own questions
            </Link>
            <Link href={`/design-workshops/${id}/readiness`} className="field-button-secondary">
              <ListChecks className="h-4 w-4" aria-hidden />
              Ready to submit?
            </Link>
            <Link href={`/design-workshops/${id}/report/history`} className="field-button-secondary">
              <FileClock className="h-4 w-4" aria-hidden />
              Report history
            </Link>
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

      <HeldPostNotice refusal={postRefusal} />

      {frozenReason ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {frozenReason}
        </p>
      ) : null}

      {offline || unsentStages ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          {offline
            ? "Offline — changes are saved on this device."
            : null}
          {offline && unsentStages ? " " : null}
          {unsentStages
            ? `${unsentStages} stage${unsentStages === 1 ? " is" : "s are"} not uploaded yet.`
            : null}
        </div>
      ) : null}

      {detail ? (
        <section className="panel mb-5 grid gap-4 p-4">
          <div className="flex flex-wrap items-center gap-3">
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
              <span className="font-medium text-ink-900">
                {readiness
                  ? `${readiness.stagesComplete} of ${readiness.stagesTotal} stages complete`
                  : stageCount === null
                    ? "Stages complete"
                    : `Stages complete, of ${stageCount}`}
              </span>
              <span className="text-ink-muted">
                {readiness
                  ? readiness.blocking.length
                    ? `${readiness.blocking.length} required field${readiness.blocking.length === 1 ? "" : "s"} left`
                    : "No required fields left"
                  : null}
              </span>
            </div>
            <ProgressBar
              percent={
                readiness ? stageProgressPercent(readiness.stagesComplete, readiness.stagesTotal) : 0
              }
              className="mt-1.5 h-2"
              role="progressbar"
              aria-label="Stages complete"
              aria-valuemin={0}
              aria-valuemax={readiness?.stagesTotal ?? 0}
              aria-valuenow={readiness?.stagesComplete ?? 0}
            />
          </div>
          <p className="text-xs leading-5 text-ink-500">
            Progress counts required fields only. Craft, cluster, dates and code come from stage 1.
          </p>
          {registerProvenance.sanctionOrder !== undefined ? (
            <div className="grid gap-1 border-t border-line-200 pt-4">
              <h2 className="text-sm font-medium text-ink-900">Source</h2>
              {registerProvenance.sanctionOrder ? (
                <p className="text-sm leading-6 text-ink-700">
                  Sanction order{" "}
                  <span className="font-medium text-ink-900">
                    {registerProvenance.sanctionOrder.sanctionOrderNo}
                  </span>
                  {registerProvenance.sanctionOrder.sanctionOrderDate
                    ? `, dated ${formatDate(registerProvenance.sanctionOrder.sanctionOrderDate)}.`
                    : "."}
                </p>
              ) : (
                <p className="text-sm leading-6 text-ink-700">
                  No sanction order.
                </p>
              )}
            </div>
          ) : null}
        </section>
      ) : null}

      {detail ? (
        <SubmissionCard
          workshopId={draft?.remoteId ?? id}
          status={confirmedStatus ?? detail.status}
          readiness={readiness}
          neverSent={neverSent}
          headerUnsent={draft?.headerDirtyAt !== null && draft?.headerDirtyAt !== undefined}
          unsentStages={unsentStages}
          offline={offline}
          onStatusChanged={setConfirmedStatus}
          readOnlyReason={heldPostReason(postRefusal)}
          signOff={signOffLines(signOff)}
          handedOn={Boolean(signOff?.handedOnAt ?? detail.handedOnAt)}
        />
      ) : null}

      {detail && !neverSent && canApproveDesignWorkshops(user) ? (
        <p className="mb-5 text-sm leading-6 text-ink-700">
          <Link className="font-medium text-purple-700 dark:text-purple-300 underline" href={`/design-workshop-approvals/${draft?.remoteId ?? id}`}>
            Review this report
          </Link>
        </p>
      ) : null}

      {decisions.length ? (
        <section className="panel mb-5 grid gap-3 p-4">
          <h2 className="text-sm font-medium text-ink-900">Decisions on this report</h2>
          <ul className="grid gap-2">
            {decisions.map((decision) => (
              <li className="rounded-md border border-line-200 bg-field-50 px-3 py-2" key={decision.id}>
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
        </section>
      ) : null}

      <InspectionFeedbackPanel
        rows={inspectionFeedback}
        status={confirmedStatus ?? detail?.status ?? ""}
        truncated={feedbackTruncated}
      />

      {draft ? (
        <DictationConsentCard
          draftLocalId={draft.localId}
          remoteId={draft.remoteId}
          consent={draft.consent?.decision ?? "NOT_RECORDED"}
          recordedAt={draft.consent?.recordedAt ?? null}
          recordedByName={consentByName ?? draft.consent?.recordedByName ?? null}
          synced={draft.consent ? draft.consent.synced : true}
          readOnlyReason={heldPostReason(postRefusal)}
          onRecorded={() => {
            void loadDraft(draft.localId).then((refreshed) => {
              if (refreshed) setDraft(refreshed);
            });
          }}
        />
      ) : null}

      {draft?.remoteId ? <WorkshopQuestionnairesCard designWorkshopId={draft.remoteId} /> : null}

      <WorkshopSearchPanel workshopId={id} index={searchIndex} ready={registry !== null && draft !== null} />

      <section className="panel overflow-hidden">
        {registry === null || draft === null ? (
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
        <p className="mt-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
          The form has new fields since this workshop was last saved.
        </p>
      ) : null}

      <div className="mt-5">
        <RecordDangerZone
          noun="design workshop"
          endpoint={`/design-workshops/${encodeURIComponent(id)}`}
          afterHref="/design-workshops"
          body="Moves this design workshop to Deleted workshops. An admin can restore it."
          confirmBody="An admin can restore it from Deleted workshops."
        />
      </div>
    </>
  );
}
