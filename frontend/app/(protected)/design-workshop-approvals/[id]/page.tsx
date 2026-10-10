"use client";

/**
 * ONE REPORT, READ FOR A SIGN-OFF — every stage as its designers recorded it, what the inspecting
 * officers asked for, every decision taken on it, and the Ministry Admin's own: approve, send back
 * or withdraw an approval, hand on to the office.
 *
 * ── THE BUTTONS ARE THE SERVER'S ANSWER, DRAWN ──────────────────────────────────────────────────
 *
 * Which decisions this report offers, whether THIS reader may take each, and the sentence a refused
 * one shows all come from `approvalActions` (`lib/designWorkshopApprovals.ts`) over the read's
 * `mayDecide`, `decisionRefusal` and `refusals` — because who may decide turns on rows no client
 * can see (designer access, written stages, an inspection post). A refused decision is drawn
 * DISABLED WITH ITS SENTENCE rather than hidden, so a Ministry Admin who worked on a workshop is told
 * why its report is not theirs to sign off instead of looking for a button that is not there.
 *
 * ── EVERY DECISION IS CONFIRMED, AND THE TEXT IT CARRIES IS TYPED FIRST ─────────────────────────
 *
 * Pressing a decision opens its own short form under the buttons — the optional approval note, the
 * mandatory reason for sending a report back, the office and the file for a hand-on — and the form's
 * own button opens the confirmation. What is typed survives a refusal: the box is cleared only when
 * the server has recorded the decision.
 *
 * ── AN APPROVAL IS OF THE REPORT AS IT WAS READ ─────────────────────────────────────────────────
 *
 * Approve sends the read's `submissionRound` and `updatedAt` back. If the designers saved a stage
 * after this page read the report, the server refuses the approval with a sentence asking for a
 * reload — and this page offers the reload — rather than approving content nobody here looked at.
 */

import { use, useEffect, useMemo, useState } from "react";
import { Download, Loader2, Send, Stamp, Undo2 } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { refreshAwaitingApprovalCount } from "@/components/hooks/useAwaitingApprovalCount";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import {
  approvalActions,
  approveReport,
  decisionKindLabel,
  DEFAULT_HANDED_ON_TO,
  downloadReportForApproval,
  getReportForApproval,
  handOnReport,
  MAX_DECISION_NOTE_CHARS,
  MAX_HANDED_ON_TO_CHARS,
  noDecisionSentence,
  pinnableExport,
  REVISE_COPY,
  reviseKindOf,
  reviseReport,
  signOffLines,
  type DwApprovalDetail,
  type DwApprovalVerb
} from "@/lib/designWorkshopApprovals";
import { inspectionFieldReading } from "@/lib/designWorkshopInspections";
import {
  dwFeedbackRounds,
  fetchStageRegistry,
  formFields,
  isFilled,
  overallPercent,
  rowTitle,
  saveBlobToDisk,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwFieldStamp,
  type DwRegistry,
  type DwRow,
  type DwStage,
  type DwStageCompleteness,
  type DwStageData
} from "@/lib/designWorkshops";
import { formatDate, formatDateTime } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canApproveDesignWorkshops } from "@/lib/permissions";

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so this report could not be read. Nothing was loaded at all.";
  }
  if (error.status === 404) {
    return "This report could not be found. It may have been deleted, or the link may be wrong — the list of reports to approve shows every one that has been handed in.";
  }
  return error.message;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The read: one field, one record, one stage
 * ──────────────────────────────────────────────────────────────────────────── */

function ReadField({
  registry,
  entity,
  field,
  row,
  stamp
}: {
  registry: DwRegistry;
  entity: DwEntity;
  field: DwField;
  row: DwEntryData;
  stamp?: DwFieldStamp | null;
}) {
  const reading = inspectionFieldReading(registry, entity, field, row);
  if (reading.kind === "empty") return null;
  return (
    <div className="grid gap-0.5 py-2">
      <span className="field-label">{field.label}</span>
      {reading.kind === "media" ? (
        <span className="text-sm text-ink-500">
          {reading.count} file{reading.count === 1 ? "" : "s"} recorded here. This read does not carry photographs,
          recordings or attachments; the report file below prints the photographs.
        </span>
      ) : (
        <span className="whitespace-pre-wrap text-sm leading-6 text-ink-900">{reading.text}</span>
      )}
      <FieldProvenance stamp={stamp} />
    </div>
  );
}

function ReadRecord({
  registry,
  entity,
  row,
  stamps
}: {
  registry: DwRegistry;
  entity: DwEntity;
  row: DwEntryData;
  stamps: Record<string, DwFieldStamp> | undefined;
}) {
  const fields = formFields(entity);
  const answered = fields.filter((field) => inspectionFieldReading(registry, entity, field, row).kind !== "empty");
  const unanswered = fields.length - answered.length;
  return (
    <div className="grid divide-y divide-line-200">
      {answered.map((field) => (
        <ReadField
          entity={entity}
          field={field}
          key={field.key}
          registry={registry}
          row={row}
          stamp={stamps?.[field.key]}
        />
      ))}
      {/* WHAT WAS LEFT OUT IS COUNTED: a read that drops empty boxes silently and a record that was
          fully answered look identical, and before an approval the difference is the finding. */}
      {unanswered > 0 ? (
        <p className="pt-2 text-xs leading-5 text-ink-500">
          {unanswered} of {fields.length} field{fields.length === 1 ? "" : "s"} here {unanswered === 1 ? "is" : "are"}{" "}
          unanswered and {unanswered === 1 ? "is" : "are"} not listed above.
        </p>
      ) : null}
    </div>
  );
}

function ReadStage({
  registry,
  stage,
  data,
  score
}: {
  registry: DwRegistry;
  stage: DwStage;
  data: DwStageData | undefined;
  /** The workshop-level score for this stage — `DwStageData.completeness` is empty on this read. */
  score: DwStageCompleteness | undefined;
}) {
  const singleton: DwEntryData = data?.singleton ?? {};
  const provenance = data?.provenance;
  const customAnswers = Object.entries(data?.custom ?? {}).filter(([, value]) => isFilled(value)).length;
  const collections = stage.entities.filter((entity) => entity.cardinality === "COLLECTION");
  const singletons = stage.entities.filter((entity) => entity.cardinality !== "COLLECTION");
  const nothingRecorded =
    !data ||
    (Object.keys(singleton).length === 0 &&
      collections.every((entity) => (data.collections?.[entity.key] ?? []).length === 0) &&
      customAnswers === 0);

  return (
    <section className="panel p-4" id={`stage-${stage.key}`}>
      <header className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line-200 pb-3">
        <h2 className="font-display text-lg font-bold text-ink-900">
          {stage.number}. {stage.title}
        </h2>
        <span className="text-xs text-ink-500">
          {score
            ? score.isComplete
              ? "Every required field answered"
              : `${score.requiredFilled} of ${score.requiredTotal} required fields answered`
            : "Nothing recorded"}
          {stage.optionalStage ? " · this stage may be dropped" : ""}
        </span>
      </header>
      {nothingRecorded ? (
        <p className="pt-3 text-sm text-ink-500">
          Nothing has been recorded on this stage.
          {stage.optionalStage ? " The source document marks it as one a workshop may legitimately skip." : ""}
        </p>
      ) : (
        <div className="grid gap-4 pt-3">
          {singletons.map((entity) => (
            <div key={entity.key}>
              {singletons.length > 1 || collections.length > 0 ? (
                <h3 className="mb-1 text-sm font-medium text-ink-700">{entity.title}</h3>
              ) : null}
              <ReadRecord entity={entity} registry={registry} row={singleton} stamps={provenance?.singleton} />
            </div>
          ))}
          {collections.map((entity) => {
            const rows: DwRow[] = data?.collections?.[entity.key] ?? [];
            if (rows.length === 0) return null;
            return (
              <div key={entity.key}>
                <h3 className="mb-1 text-sm font-medium text-ink-700">
                  {entity.title} · {rows.length} {rows.length === 1 ? "row" : "rows"}
                </h3>
                <div className="grid gap-3">
                  {rows.map((row, index) => (
                    <div
                      className="rounded-md border border-line-200 bg-surface-50 p-3"
                      key={row._entryId ?? row._clientKey ?? index}
                    >
                      <p className="mb-1 text-sm font-medium text-ink-900">{rowTitle(entity, row, index)}</p>
                      <ReadRecord
                        entity={entity}
                        registry={registry}
                        row={row}
                        stamps={row._entryId ? provenance?.collections?.[entity.key]?.[row._entryId] : undefined}
                      />
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
          {customAnswers > 0 ? (
            <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
              {customAnswers} answer{customAnswers === 1 ? "" : "s"} to question{customAnswers === 1 ? "" : "s"} this
              workshop&apos;s designer added to this stage {customAnswers === 1 ? "is" : "are"} recorded and printed in
              the report file.
            </p>
          ) : null}
        </div>
      )}
    </section>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The decision
 * ──────────────────────────────────────────────────────────────────────────── */

/** The forms a decision opens, and the one that is open. */
type OpenForm = DwApprovalVerb | null;

function DecisionPanel({
  detail,
  stages,
  onAnswer,
  onReload
}: {
  detail: DwApprovalDetail;
  stages: DwStage[];
  onAnswer: (next: DwApprovalDetail) => void;
  onReload: () => void;
}) {
  const confirm = useConfirm();
  const actions = approvalActions(detail);
  const reviseKind = reviseKindOf(detail);
  const pinnable = pinnableExport(detail);

  const [open, setOpen] = useState<OpenForm>(null);
  const [busy, setBusy] = useState<DwApprovalVerb | "download" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  /** True when the refusal means the report moved since it was read — the reload is offered. */
  const [stale, setStale] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);

  const [approveNote, setApproveNote] = useState("");
  const [reason, setReason] = useState("");
  const [reasonStage, setReasonStage] = useState("");
  const [office, setOffice] = useState(detail.handOnDefaultTo?.trim() || DEFAULT_HANDED_ON_TO);
  const [handOnNote, setHandOnNote] = useState("");
  const [pinExport, setPinExport] = useState(true);
  const [downloadNotice, setDownloadNotice] = useState<string | null>(null);

  function fail(err: unknown, what: string) {
    if (isUnreachable(err)) {
      setProblem(`The repository could not be reached, so nothing was ${what}. What you typed is still here; try again when you have a connection.`);
      setStale(false);
      return;
    }
    const message = err instanceof Error && err.message.trim() ? err.message : "The repository did not say why.";
    setProblem(`Nothing was ${what}: ${message}`);
    // A 409 is the report having moved since this page read it — another decision, or its designers.
    setStale(err instanceof ApiError && err.status === 409);
  }

  async function approve() {
    const agreed = await confirm({
      title: "Approve this report?",
      tone: "warning",
      confirmLabel: "Approve",
      body: (
        <>
          <span className="block">
            The report is approved as you have read it — round {detail.submissionRound ?? 0}. Its stages can no longer be
            changed by anybody unless the approval is withdrawn.
          </span>
          <span className="mt-2 block">
            Handing it on to the office is a separate step, taken afterwards on this screen.
          </span>
        </>
      ),
      note: "The approval is recorded with your name and the date. It can be withdrawn later, with a reason, which sends the report back to its designers."
    });
    if (!agreed) return;
    setBusy("approve");
    setProblem(null);
    setStale(false);
    setOutcome(null);
    try {
      const answer = await approveReport(detail.id, {
        note: approveNote.trim() || null,
        round: detail.submissionRound ?? 0,
        readAt: detail.updatedAt ?? ""
      });
      onAnswer(answer);
      setApproveNote("");
      setOpen(null);
      setOutcome(
        answer.alreadyApproved
          ? `This report was already approved, so nothing more was recorded. ${signOffLines(answer).join(" ")}`
          : "Approved. The report can no longer be changed, and it is ready to be handed on to the office."
      );
      void refreshAwaitingApprovalCount();
    } catch (err) {
      fail(err, "approved");
    } finally {
      setBusy(null);
    }
  }

  async function revise() {
    if (!reviseKind) return;
    const text = reason.trim();
    if (!text) return;
    const copy = REVISE_COPY[reviseKind];
    const agreed = await confirm({
      title: copy.title,
      tone: "warning",
      confirmLabel: copy.confirm,
      body: copy.body,
      note: "Your sentence is recorded with your name against this round, and the designers read it as written. It cannot be edited afterwards."
    });
    if (!agreed) return;
    setBusy("revise");
    setProblem(null);
    setStale(false);
    setOutcome(null);
    try {
      const answer = await reviseReport(detail.id, { note: text, stageKey: reasonStage || null });
      onAnswer(answer);
      setReason("");
      setReasonStage("");
      setOpen(null);
      setOutcome(copy.done);
      void refreshAwaitingApprovalCount();
    } catch (err) {
      fail(err, "sent back");
    } finally {
      setBusy(null);
    }
  }

  async function handOn() {
    const to = office.trim() || DEFAULT_HANDED_ON_TO;
    const exportId = pinExport && pinnable ? pinnable.id : null;
    const agreed = await confirm({
      title: "Hand this report on to the office?",
      tone: "warning",
      confirmLabel: "Hand it on",
      body: (
        <>
          <span className="block">
            The approved report is recorded as handed on to {to}
            {exportId ? ", as the report file named below" : ""}. Nothing is sent from here: send the file the way that
            office receives reports.
          </span>
          <span className="mt-2 block">
            After this the report stays as it is. If the office sends it back, it can be returned to its designers with a
            reason.
          </span>
        </>
      ),
      note: "The hand-on is recorded with your name, the date and the office."
    });
    if (!agreed) return;
    setBusy("handOn");
    setProblem(null);
    setStale(false);
    setOutcome(null);
    try {
      const answer = await handOnReport(detail.id, { note: handOnNote.trim() || null, handedOnTo: to, exportId });
      onAnswer(answer);
      setHandOnNote("");
      setOpen(null);
      setOutcome(`Handed on. ${signOffLines(answer).join(" ")}`);
      void refreshAwaitingApprovalCount();
    } catch (err) {
      fail(err, "handed on");
    } finally {
      setBusy(null);
    }
  }

  async function download(format: "DOCX" | "PDF") {
    setBusy("download");
    setProblem(null);
    setDownloadNotice(null);
    try {
      // `record: true` writes the export to the report's ledger, which is what a hand-on can then
      // name as the file the office received.
      const file = await downloadReportForApproval(detail.id, { formats: [format], record: true });
      saveBlobToDisk(file.blob, file.fileName);
      // WARNINGS TRAVEL BESIDE THE DOWNLOAD, NEVER INSIDE THE DOCUMENT — the report page's rule.
      setDownloadNotice(
        file.warningCount > 0
          ? `Downloaded ${file.fileName}. ${file.warningCount} thing${file.warningCount === 1 ? " was" : "s were"} noted while it was made: ${file.warnings.join("; ")}`
          : `Downloaded ${file.fileName}.`
      );
      // The new export is what a hand-on names, so the read is taken again to see it.
      onReload();
    } catch (err) {
      fail(err, "downloaded");
    } finally {
      setBusy(null);
    }
  }

  const noneSentence = noDecisionSentence(detail);
  const lines = signOffLines(detail);

  return (
    <section className="panel mb-4 grid gap-3 p-4" aria-labelledby="decision-heading">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="decision-heading" className="font-display text-base font-bold tracking-tight text-ink-900">
          Your decision
        </h2>
        <StatusBadge status={detail.status} />
      </div>

      {lines.length ? (
        <div className="grid gap-1 text-sm leading-6 text-ink-700">
          {lines.map((line) => (
            <p key={line}>{line}</p>
          ))}
        </div>
      ) : null}

      {detail.decisionRefusal ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          {detail.decisionRefusal}
        </p>
      ) : null}

      {actions.length === 0 ? (
        noneSentence ? <p className="text-sm leading-6 text-ink-700">{noneSentence}</p> : null
      ) : (
        <div className="grid gap-2">
          <div className="flex flex-wrap gap-2">
            {actions.map((action) => {
              const Icon = action.verb === "approve" ? Stamp : action.verb === "handOn" ? Send : Undo2;
              return (
                <button
                  key={action.verb}
                  type="button"
                  className={action.primary ? "field-button" : "field-button-secondary"}
                  disabled={!action.allowed || busy !== null}
                  aria-expanded={action.allowed ? open === action.verb : undefined}
                  aria-describedby={action.allowed ? undefined : `decision-reason-${action.verb}`}
                  onClick={() => {
                    setOpen((current) => (current === action.verb ? null : action.verb));
                    setProblem(null);
                    setOutcome(null);
                  }}
                >
                  <Icon className="h-4 w-4" aria-hidden />
                  {action.label}
                </button>
              );
            })}
          </div>
          {/* WHY A DECISION IS SWITCHED OFF, IN THE SERVER'S WORDS. The rule-7 sentence is said once,
              above; a status sentence belongs to its button. */}
          {actions
            .filter((action) => !action.allowed && action.reason && action.reason !== detail.decisionRefusal)
            .map((action) => (
              <p id={`decision-reason-${action.verb}`} key={action.verb} className="text-xs leading-5 text-ink-500">
                <span className="font-medium text-ink-700">{action.label}: </span>
                {action.reason}
              </p>
            ))}
          {actions
            .filter((action) => !action.allowed && action.reason === detail.decisionRefusal)
            .map((action) => (
              <span id={`decision-reason-${action.verb}`} key={action.verb} className="sr-only">
                {action.reason}
              </span>
            ))}
        </div>
      )}

      {open === "approve" ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <label className="field-label" htmlFor="approve-note">
            A note with the approval (optional)
          </label>
          <textarea
            className="field-input min-h-20"
            id="approve-note"
            maxLength={MAX_DECISION_NOTE_CHARS}
            onChange={(event) => setApproveNote(event.target.value)}
            placeholder="Anything you want on the record beside the approval — a condition, a reference."
            value={approveNote}
          />
          <div className="flex flex-wrap gap-2">
            <button className="field-button" disabled={busy !== null} onClick={() => void approve()} type="button">
              {busy === "approve" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              Approve this report
            </button>
            <button className="field-button-secondary" disabled={busy !== null} onClick={() => setOpen(null)} type="button">
              Cancel
            </button>
          </div>
        </div>
      ) : null}

      {open === "revise" && reviseKind ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <label className="field-label" htmlFor="revise-reason">
            What needs correcting (required)
          </label>
          <textarea
            className="field-input min-h-24"
            id="revise-reason"
            maxLength={MAX_DECISION_NOTE_CHARS}
            onChange={(event) => setReason(event.target.value)}
            placeholder={REVISE_COPY[reviseKind].placeholder}
            value={reason}
          />
          <label className="field-label" htmlFor="revise-stage">
            Which stage is it about?
          </label>
          <select
            className="field-input"
            id="revise-stage"
            onChange={(event) => setReasonStage(event.target.value)}
            value={reasonStage}
          >
            <option value="">The report as a whole</option>
            {stages.map((stage) => (
              <option key={stage.key} value={stage.key}>
                {stage.title}
              </option>
            ))}
          </select>
          <div className="flex flex-wrap gap-2">
            <button
              className="field-button"
              disabled={busy !== null || reason.trim().length === 0}
              onClick={() => void revise()}
              type="button"
            >
              {busy === "revise" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              {REVISE_COPY[reviseKind].confirm}
            </button>
            <button className="field-button-secondary" disabled={busy !== null} onClick={() => setOpen(null)} type="button">
              Cancel
            </button>
          </div>
        </div>
      ) : null}

      {open === "handOn" ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <label className="field-label" htmlFor="hand-on-office">
            Handed on to
          </label>
          <input
            className="field-input"
            id="hand-on-office"
            maxLength={MAX_HANDED_ON_TO_CHARS}
            onChange={(event) => setOffice(event.target.value)}
            value={office}
          />
          <label className="field-label" htmlFor="hand-on-note">
            A note (optional)
          </label>
          <textarea
            className="field-input min-h-20"
            id="hand-on-note"
            maxLength={MAX_DECISION_NOTE_CHARS}
            onChange={(event) => setHandOnNote(event.target.value)}
            placeholder="How it went, or the reference it went under."
            value={handOnNote}
          />
          <div className="grid gap-1 text-sm leading-6 text-ink-700">
            <span className="field-label">The report file that went</span>
            {pinnable ? (
              <label className="inline-flex items-start gap-2">
                <input
                  checked={pinExport}
                  className="mt-1"
                  onChange={(event) => setPinExport(event.target.checked)}
                  type="checkbox"
                />
                <span>
                  {pinnable.fileName?.trim() || `The ${pinnable.format} file`}, made {formatDateTime(pinnable.generatedAt)}
                  {pinnable.generatedByName ? ` by ${pinnable.generatedByName}` : ""} — recorded as the file the office
                  received.
                </span>
              </label>
            ) : (
              <p>
                No report file has been made since the approval, so none can be named as the one that went. Download one
                below and it is offered here.
              </p>
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            <button className="field-button" disabled={busy !== null} onClick={() => void handOn()} type="button">
              {busy === "handOn" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              Hand it on
            </button>
            <button className="field-button-secondary" disabled={busy !== null} onClick={() => setOpen(null)} type="button">
              Cancel
            </button>
          </div>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-2 border-t border-line-200 pt-3">
        <span className="text-sm text-ink-700">Download the report:</span>
        <button className="field-button-secondary" disabled={busy !== null} onClick={() => void download("DOCX")} type="button">
          {busy === "download" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Download className="h-4 w-4" aria-hidden />}
          .docx
        </button>
        <button className="field-button-secondary" disabled={busy !== null} onClick={() => void download("PDF")} type="button">
          <Download className="h-4 w-4" aria-hidden />
          .pdf
        </button>
      </div>
      {downloadNotice ? <p className="text-xs leading-5 text-ink-500">{downloadNotice}</p> : null}

      {/* BOTH REGIONS ARE MOUNTED FROM FIRST PAINT, so the first message in each is announced. */}
      <div
        role="alert"
        aria-live="assertive"
        className={problem ? "rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700" : "sr-only"}
      >
        {problem}
        {problem && stale ? (
          <button className="ml-2 font-medium underline" onClick={onReload} type="button">
            Reload the report
          </button>
        ) : null}
      </div>
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

/* ────────────────────────────────────────────────────────────────────────────
 * The page
 * ──────────────────────────────────────────────────────────────────────────── */

export default function ReportForApprovalPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { user, loading } = useAuth();

  const [detail, setDetail] = useState<DwApprovalDetail | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registryError, setRegistryError] = useState<string | null>(null);
  /** Bumped to read the report again — after a download, or when a decision found it moved. */
  const [readCount, setReadCount] = useState(0);

  useEffect(() => {
    if (loading || !canApproveDesignWorkshops(user)) return;
    let cancelled = false;
    getReportForApproval(id)
      .then((result) => {
        if (cancelled) return;
        setDetail(result);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        // What was on screen stays on screen; the sentence says the read failed.
        setError(describeFailure(err));
      });
    return () => {
      cancelled = true;
    };
  }, [id, loading, user, readCount]);

  useEffect(() => {
    if (loading || !canApproveDesignWorkshops(user)) return;
    let cancelled = false;
    fetchStageRegistry()
      .then((result) => {
        if (cancelled) return;
        setRegistry(result);
        setRegistryError(null);
      })
      .catch(() => {
        if (cancelled) return;
        setRegistryError(
          "The field list could not be loaded, so the stages below cannot be named or labelled. The report itself was read; try again."
        );
      });
    return () => {
      cancelled = true;
    };
  }, [loading, user]);

  const overall = useMemo(() => overallPercent(detail?.completeness), [detail]);
  const stages: DwStage[] = registry?.stages ?? [];
  const rounds = dwFeedbackRounds(detail?.inspectionFeedback ?? []);
  const decisions = detail?.decisions ?? [];
  const inspectors = detail?.inspectors ?? [];

  return (
    <div>
      <PageHeader
        title={detail?.title?.trim() || "Report to approve"}
        description={
          detail
            ? [detail.workshopCode, detail.craftName, detail.clusterName, detail.district, detail.state]
                .filter(Boolean)
                .join(" · ") || "No workshop code yet — stage 1 has not been saved."
            : undefined
        }
        icon={<Stamp className="h-5 w-5" aria-hidden />}
        actions={detail ? <StatusBadge status={detail.status} /> : undefined}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {registryError ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          {registryError}
        </div>
      ) : null}

      {detail === null && !error ? (
        <section className="panel p-4 text-sm text-ink-700">Loading the report…</section>
      ) : null}

      {detail ? (
        <>
          <DecisionPanel
            detail={detail}
            key={`${detail.id}:${detail.status}:${detail.updatedAt ?? ""}`}
            onAnswer={setDetail}
            onReload={() => setReadCount((count) => count + 1)}
            stages={stages}
          />

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
              <span className="field-label block">Required fields answered</span>
              {overall}% across every stage
            </p>
            <p className="text-sm text-ink-700">
              <span className="field-label block">Handed in</span>
              {detail.submissionRound
                ? `${detail.submissionRound} time${detail.submissionRound === 1 ? "" : "s"}${
                    detail.lastHandedInAt ? `, most recently on ${formatDate(detail.lastHandedInAt)}` : ""
                  }`
                : "Not yet"}
            </p>
            <p className="text-sm text-ink-700 sm:col-span-2">
              <span className="field-label block">Inspected by</span>
              {inspectors.length
                ? inspectors.map((person) => person.name?.trim() || person.email || "An officer no longer named").join(", ")
                : "Nobody has been assigned to inspect this report."}
            </p>
            {detail.oversight?.length ? (
              <p className="text-sm text-ink-700 sm:col-span-2">
                <span className="field-label block">Supervised by</span>
                {detail.oversight
                  .map(
                    (person) =>
                      `${person.name?.trim() || "An officer no longer named"} (${
                        person.capacity === "REGIONAL_DIRECTOR" ? "Regional Director" : "Assistant Director"
                      })`
                  )
                  .join(", ")}
              </p>
            ) : null}
          </section>

          <section className="panel mb-4 grid gap-3 p-4">
            <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Decisions on this report</h2>
            {decisions.length ? (
              <ul className="grid gap-2">
                {decisions.map((decision) => (
                  <li className="rounded-md border border-line-200 bg-field-50 px-3 py-2" key={decision.id}>
                    <p className="text-sm font-medium text-ink-900">{decisionKindLabel(decision.kind)}</p>
                    {decision.note?.trim() ? (
                      <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-ink-700">{decision.note}</p>
                    ) : null}
                    <p className="mt-1 text-xs leading-5 text-ink-500">
                      {decision.actorName?.trim() || "Somebody no longer on record"}
                      {decision.at ? ` · ${formatDateTime(decision.at)}` : ""}
                    </p>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-ink-700">No decision has been taken on this report yet.</p>
            )}
          </section>

          <section className="panel mb-4 grid gap-3 p-4">
            <div className="flex flex-wrap items-center gap-3">
              <h2 className="font-display text-base font-bold tracking-tight text-ink-900">What the officers asked for</h2>
              <span className="text-xs text-ink-500">
                {(detail.inspectionFeedback ?? []).length} on record
                {detail.inspectionFeedbackTruncated ? " (older ones not shown)" : ""}
              </span>
            </div>
            {rounds.length ? (
              rounds.map((group) => (
                <div className="grid gap-2" key={group.round}>
                  <h3 className="field-label">Round {group.round}</h3>
                  <ul className="grid gap-2">
                    {group.rows.map((row) => (
                      <li className="rounded-md border border-line-200 bg-field-50 px-3 py-2" key={row.id}>
                        <p className="whitespace-pre-wrap text-sm leading-6 text-ink-900">{row.note}</p>
                        <p className="mt-1 text-xs leading-5 text-ink-500">
                          {row.actorName?.trim() || "An officer no longer named"}
                          {row.byApprovingAuthority ? " · the approving authority" : ""}
                          {row.stageKey ? ` · about ${row.stageKey}` : " · about the report as a whole"}
                          {row.sentBack ? " · sent the report back" : ""}
                        </p>
                      </li>
                    ))}
                  </ul>
                </div>
              ))
            ) : (
              <p className="text-sm text-ink-700">No correction has been asked for on this report.</p>
            )}
          </section>

          {registry === null ? (
            registryError ? null : <section className="panel p-4 text-sm text-ink-700">Loading the field list…</section>
          ) : (
            <div className="grid gap-4">
              {stages.map((stage) => (
                <ReadStage
                  data={detail.stages?.[stage.key]}
                  key={stage.key}
                  registry={registry}
                  score={detail.completeness?.[stage.key]}
                  stage={stage}
                />
              ))}
            </div>
          )}

        </>
      ) : null}
    </div>
  );
}
