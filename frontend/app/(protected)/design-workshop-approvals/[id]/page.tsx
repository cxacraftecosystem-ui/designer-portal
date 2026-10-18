"use client";

/**
 * ONE REPORT, READ FOR A SIGN-OFF — every stage as its designers recorded it, with its photographs,
 * what the officers asked for, every decision taken on it, and the approving authority's own:
 * approve, send back or withdraw an approval, hand on to the office.
 *
 * ── THE BUTTONS ARE THE SERVER'S ANSWER, DRAWN ──────────────────────────────────────────────────
 *
 * Which decisions this report offers, whether THIS reader may take each, and the sentence a refused
 * one shows all come from `approvalActions` (`lib/designWorkshopApprovals.ts`) over the read's
 * `mayDecide`, `decisionRefusal` and `refusals` — who may decide turns on rows no client can see. A
 * refused decision is drawn DISABLED WITH ITS SENTENCE rather than hidden.
 *
 * ── THE REVIEW LAYOUT (A18, B2, C13, 2026-10-17) ────────────────────────────────────────────────
 *
 * A fixed stage menu with completeness dots, a fixed decision bar that preselects the stage in view,
 * a Flag on every field that drafts a note into a tray, one "Send back with N notes", each note
 * marked "Edited since" or "Untouched" (A19), each inspector's "Checked, no corrections" (C5), and a
 * "Changed since round N" comparison against the copy kept at each hand-in. See
 * `components/designworkshop/review/`.
 *
 * ── AN APPROVAL IS OF THE REPORT AS IT WAS READ ─────────────────────────────────────────────────
 *
 * Approve sends the read's `submissionRound` and `updatedAt` back. If the designers saved a stage
 * after this page read the report, the server refuses with a sentence asking for a reload — and this
 * page offers the reload — rather than approving content nobody here looked at.
 */

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { CheckCircle2, Download, Loader2, MessageSquarePlus, Send, Stamp, Undo2 } from "lucide-react";

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
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { refreshAwaitingApprovalCount } from "@/components/hooks/useAwaitingApprovalCount";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { Toggle } from "@/components/ui/Toggle";
import { ApiError } from "@/lib/api";
import {
  approvalActions,
  approveAndHandOn,
  approveReport,
  decisionKindLabel,
  deliveryLines,
  deliveryOffer,
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
  resendReportDelivery,
  reviseReport,
  signOffLines,
  type DwApprovalDetail,
  type DwApprovalVerb
} from "@/lib/designWorkshopApprovals";
import { fetchStageRegistry, overallPercent, saveBlobToDisk, type DwRegistry, type DwStage } from "@/lib/designWorkshops";
import { formatDate, formatDateTime } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canApproveDesignWorkshops, isAdmin } from "@/lib/permissions";
import { plural } from "@/lib/sharingScope";
import { reviseBody, sendBackLabel, type DwNoteDraft, type DwReviewNote } from "@/lib/reviewAids";
import { SearchableSelect } from "@/components/ui/SearchableSelect";

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "No connection — couldn't load this report.";
  }
  if (error.status === 404) {
    return "Report not found. Check Reports to approve.";
  }
  return error.message;
}

/** The forms a decision opens, and the one that is open. */
type OpenForm = DwApprovalVerb | null;

const DECISION_ICON = { approve: Stamp, handOn: Send, revise: Undo2 } as const;

function DecisionPanel({
  detail,
  stages,
  open,
  setOpen,
  reasonStage,
  setReasonStage,
  drafts,
  onSent,
  onAnswer,
  onReload
}: {
  detail: DwApprovalDetail;
  stages: DwStage[];
  open: OpenForm;
  setOpen: (next: OpenForm) => void;
  reasonStage: string;
  setReasonStage: (next: string) => void;
  /** The tray: a send-back carries every drafted note. */
  drafts: DwNoteDraft[];
  onSent: () => void;
  onAnswer: (next: DwApprovalDetail) => void;
  onReload: () => void;
}) {
  const confirm = useConfirm();
  const { user } = useAuth();
  const actions = approvalActions(detail);
  const offer = deliveryOffer(detail);
  const delivered = deliveryLines(detail.delivery, formatDateTime);
  const reviseKind = reviseKindOf(detail);
  const pinnable = pinnableExport(detail);

  const [busy, setBusy] = useState<DwApprovalVerb | "download" | "deliver" | "resend" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  /** True when the refusal means the report moved since it was read — the reload is offered. */
  const [stale, setStale] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);

  const [approveNote, setApproveNote] = useState("");
  const [reason, setReason] = useState("");
  const [office, setOffice] = useState(detail.handOnDefaultTo?.trim() || DEFAULT_HANDED_ON_TO);
  const [handOnNote, setHandOnNote] = useState("");
  const [pinExport, setPinExport] = useState(true);
  const [download, setDownload] = useState<{ text: string; reused: "DOCX" | "PDF" | null } | null>(null);
  const [deliveryFailed, setDeliveryFailed] = useState(false);

  function fail(err: unknown, what: string) {
    if (isUnreachable(err)) {
      setProblem(`No connection — nothing was ${what}. Your text is kept.`);
      setStale(false);
      return;
    }
    const message = err instanceof Error && err.message.trim() ? err.message : "Try again.";
    setProblem(`Nothing was ${what}: ${message}`);
    setStale(err instanceof ApiError && err.status === 409);
  }

  async function approve() {
    const agreed = await confirm({
      title: "Approve this report?",
      tone: "warning",
      confirmLabel: "Approve",
      body: (
        <>
          <span className="block">Approves round {detail.submissionRound ?? 0} as you read it.</span>
          <span className="mt-2 block">Handing it on to the office is a separate step.</span>
          {drafts.length ? (
            <span className="mt-2 block font-medium text-ink-900">
              {plural(drafts.length, "drafted note")} won&apos;t be sent and will be discarded.
            </span>
          ) : null}
        </>
      ),
      note: "Recorded under your name. You can withdraw it later with a reason."
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
      if (drafts.length) onSent(); // the confirm said they would be discarded
      setOpen(null);
      setOutcome(answer.alreadyApproved ? `Already approved. ${signOffLines(answer).join(" ")}` : "Approved. Ready to hand on.");
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
    if (!text && drafts.length === 0) return;
    const copy = REVISE_COPY[reviseKind];
    const agreed = await confirm({
      title: copy.title,
      tone: "warning",
      confirmLabel: drafts.length ? sendBackLabel(drafts.length) : copy.confirm,
      body: copy.body,
      note: "Recorded under your name. It can't be edited afterwards."
    });
    if (!agreed) return;
    setBusy("revise");
    setProblem(null);
    setStale(false);
    setOutcome(null);
    try {
      const answer = await reviseReport(detail.id, reviseBody(drafts, text, reasonStage || null));
      onAnswer(answer);
      setReason("");
      setReasonStage("");
      setOpen(null);
      onSent();
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
            Recorded as handed on to {to}
            {exportId ? ", as the file named below" : ""}. Send the file yourself.
          </span>
          <span className="mt-2 block">If the office sends it back, you can return it to its designers.</span>
        </>
      ),
      note: "Recorded under your name."
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

  async function deliver() {
    const office = detail.delivery?.officeName || DEFAULT_HANDED_ON_TO;
    const approving = String(detail.status ?? "").toUpperCase() === "PRE_SUBMISSION";
    const agreed = await confirm({
      title: approving ? "Approve and hand on this report?" : "Hand on this report by e-mail?",
      tone: "warning",
      confirmLabel: offer.label,
      body: (
        <span className="block">
          {approving ? `Approves round ${detail.submissionRound ?? 0} as you read it, makes the PDF and ` : "Makes the PDF and "}
          e-mails {office} a link that lasts {detail.delivery?.linkDays ?? 7} days.
          {approving && drafts.length ? (
            <span className="mt-2 block font-medium text-ink-900">
              {plural(drafts.length, "drafted note")} won&apos;t be sent and will be discarded.
            </span>
          ) : null}
        </span>
      ),
      note: "Recorded under your name."
    });
    if (!agreed) return;
    setBusy("deliver");
    setProblem(null);
    setStale(false);
    setOutcome(null);
    try {
      const answer = await approveAndHandOn(detail.id, {
        note: approveNote.trim() || null,
        round: detail.submissionRound ?? 0,
        readAt: detail.updatedAt ?? "",
        format: "PDF"
      });
      onAnswer(answer);
      setApproveNote("");
      if (approving && drafts.length) onSent(); // the confirm said they would be discarded
      setOpen(null);
      setDeliveryFailed(answer.deliveryFailed === true);
      setOutcome(
        answer.deliveryFailed
          ? `Handed on to ${office}. The e-mail did not go — send the link.`
          : `Handed on and e-mailed to ${answer.delivery?.latest?.officeName ?? office}.`
      );
      void refreshAwaitingApprovalCount();
    } catch (err) {
      fail(err, "handed on");
    } finally {
      setBusy(null);
    }
  }

  async function resend() {
    setBusy("resend");
    setProblem(null);
    setOutcome(null);
    try {
      const answer = await resendReportDelivery(detail.id);
      onAnswer(answer);
      setOutcome(deliveryFailed ? `E-mailed to ${answer.delivery?.latest?.officeName ?? "the office"}.` : "A new link was e-mailed. The old one no longer works.");
      setDeliveryFailed(false);
    } catch (err) {
      fail(err, "sent");
    } finally {
      setBusy(null);
    }
  }

  async function downloadFile(format: "DOCX" | "PDF", fresh = false) {
    setBusy("download");
    setProblem(null);
    setDownload(null);
    try {
      // `record: true` writes the export to the report's ledger, which a hand-on can then name.
      const file = await downloadReportForApproval(detail.id, { formats: [format], record: true, fresh });
      saveBlobToDisk(file.blob, file.fileName);
      const notes = file.warningCount > 0 ? ` Notes: ${file.warnings.join("; ")}` : "";
      // A22: an unchanged report comes back from its stored file — said, with a way to rebuild.
      setDownload(
        file.reusedFrom
          ? { text: `Same as the file from ${formatDateTime(file.reusedFrom)} — nothing has changed.${notes}`, reused: format }
          : { text: `Downloaded ${file.fileName}.${notes}`, reused: null }
      );
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
    <section className="panel mb-4 grid scroll-mt-28 gap-3 p-4" id="decision-panel" aria-labelledby="decision-heading">
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
          <ActionGroup align="start">
            {actions.map((action) => {
              const Icon = DECISION_ICON[action.verb];
              return (
                <button
                  key={action.verb}
                  type="button"
                  className={action.primary ? "field-button" : "field-button-secondary"}
                  disabled={!action.allowed || busy !== null}
                  aria-expanded={action.allowed ? open === action.verb : undefined}
                  aria-describedby={action.allowed ? undefined : `decision-reason-${action.verb}`}
                  onClick={() => {
                    setOpen(open === action.verb ? null : action.verb);
                    setProblem(null);
                    setOutcome(null);
                  }}
                >
                  <Icon className="h-4 w-4" aria-hidden />
                  {action.verb === "revise" && drafts.length ? sendBackLabel(drafts.length) : action.label}
                </button>
              );
            })}
          </ActionGroup>
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

      {offer.kind === "send" ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <ActionGroup>
            <button className="field-button" disabled={busy !== null} onClick={() => void deliver()} type="button">
              {busy === "deliver" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Send className="h-4 w-4" aria-hidden />}
              {offer.label}
            </button>
          </ActionGroup>
          <p className="text-xs leading-5 text-ink-500">
            E-mails {detail.delivery?.officeName} a download link that lasts {detail.delivery?.linkDays ?? 7} days.
          </p>
        </div>
      ) : null}
      {offer.kind === "setup" ? (
        <p className="text-xs leading-5 text-ink-500">
          To e-mail reports to the office from here, {isAdmin(user) ? "" : "ask an administrator to "}set its addresses
          {isAdmin(user) ? (
            <>
              {" "}
              in{" "}
              <Link className="font-medium text-purple-700 underline underline-offset-2" href="/settings#report-delivery">
                Settings
              </Link>
            </>
          ) : null}
          .
        </p>
      ) : null}
      {delivered.length ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-card p-3">
          <p className="text-sm leading-6 text-ink-700">{delivered.join(" ")}</p>
          {detail.delivery?.latest?.emails.length ? (
            <ul className="flex flex-wrap gap-2 text-xs">
              {detail.delivery.latest.emails.map((email) => (
                <li
                  key={email.address}
                  title={email.error ?? undefined}
                  className={
                    email.status === "FAILED"
                      ? "rounded-full bg-error-100 px-2 py-0.5 font-medium text-error-600"
                      : email.status === "SENT"
                        ? "rounded-full bg-success-100 px-2 py-0.5 font-medium text-success-600"
                        : "rounded-full bg-amber-100 px-2 py-0.5 font-medium text-amber-800"
                  }
                >
                  {email.address} · {email.status === "SENT" ? "sent" : email.status === "FAILED" ? "failed" : "sending"}
                </li>
              ))}
            </ul>
          ) : null}
          {detail.delivery?.configured && !detail.decisionRefusal ? (
            <ActionGroup>
              <button className="field-button-secondary" disabled={busy !== null} onClick={() => void resend()} type="button">
                {busy === "resend" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
                Send a new link
              </button>
            </ActionGroup>
          ) : null}
        </div>
      ) : null}
      {deliveryFailed && !delivered.length && !detail.decisionRefusal ? (
        <ActionGroup>
          <button className="field-button" disabled={busy !== null} onClick={() => void resend()} type="button">
            {busy === "resend" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Send className="h-4 w-4" aria-hidden />}
            Send the link
          </button>
        </ActionGroup>
      ) : null}

      {open === "approve" ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <label className="field-label" htmlFor="approve-note">
            Note with the approval (optional)
          </label>
          <textarea
            className="field-input min-h-20"
            id="approve-note"
            maxLength={MAX_DECISION_NOTE_CHARS}
            onChange={(event) => setApproveNote(event.target.value)}
            placeholder="Optional note"
            value={approveNote}
          />
          <ActionGroup>
            <button className="field-button" disabled={busy !== null} onClick={() => void approve()} type="button">
              {busy === "approve" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              Approve this report
            </button>
            <button className="field-button-secondary" disabled={busy !== null} onClick={() => setOpen(null)} type="button">
              Cancel
            </button>
          </ActionGroup>
        </div>
      ) : null}

      {open === "revise" && reviseKind ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          {drafts.length ? (
            <p className="text-sm text-ink-700">Your {drafts.length === 1 ? "note goes" : `${drafts.length} notes go`} with it.</p>
          ) : null}
          <label className="field-label" htmlFor="revise-reason">
            {drafts.length ? "Covering note (optional)" : "What needs correcting (required)"}
          </label>
          <textarea
            className="field-input min-h-24"
            id="revise-reason"
            maxLength={MAX_DECISION_NOTE_CHARS}
            onChange={(event) => setReason(event.target.value)}
            placeholder={REVISE_COPY[reviseKind].placeholder}
            value={reason}
          />
          {drafts.length ? null : (
            <>
              <span className="field-label">Which stage is it about?</span>
              <SearchableSelect
                ariaLabel="Which stage is it about?"
                value={reasonStage}
                onChange={setReasonStage}
                noneLabel="The report as a whole"
                options={stages.map((stage) => ({ value: stage.key, label: `${stage.number}. ${stage.title}` }))}
                advanceOnSelect={false}
              />
            </>
          )}
          <ActionGroup>
            <button
              className="field-button"
              disabled={busy !== null || (reason.trim().length === 0 && drafts.length === 0)}
              onClick={() => void revise()}
              type="button"
            >
              {busy === "revise" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              {drafts.length ? sendBackLabel(drafts.length) : REVISE_COPY[reviseKind].confirm}
            </button>
            <button className="field-button-secondary" disabled={busy !== null} onClick={() => setOpen(null)} type="button">
              Cancel
            </button>
          </ActionGroup>
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
            Note (optional)
          </label>
          <textarea
            className="field-input min-h-20"
            id="hand-on-note"
            maxLength={MAX_DECISION_NOTE_CHARS}
            onChange={(event) => setHandOnNote(event.target.value)}
            placeholder="Optional reference"
            value={handOnNote}
          />
          <div className="grid gap-1 text-sm leading-6 text-ink-700">
            <span className="field-label">The report file that went</span>
            {pinnable ? (
              <Toggle
                label={`${pinnable.fileName?.trim() || `The ${pinnable.format} file`}, made ${formatDateTime(pinnable.generatedAt)}${
                  pinnable.generatedByName ? ` by ${pinnable.generatedByName}` : ""
                }`}
                description="Recorded as the file the office received."
                checked={pinExport}
                onChange={setPinExport}
              />
            ) : (
              <p>No file since the approval. Download one below to name it.</p>
            )}
          </div>
          <ActionGroup>
            <button className="field-button" disabled={busy !== null} onClick={() => void handOn()} type="button">
              {busy === "handOn" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              Hand it on
            </button>
            <button className="field-button-secondary" disabled={busy !== null} onClick={() => setOpen(null)} type="button">
              Cancel
            </button>
          </ActionGroup>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-2 border-t border-line-200 pt-3">
        <span className="text-sm text-ink-700">Download the report:</span>
        <ActionGroup className="max-sm:w-full">
          <button className="field-button-secondary" disabled={busy !== null} onClick={() => void downloadFile("DOCX")} type="button">
            {busy === "download" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Download className="h-4 w-4" aria-hidden />}
            .docx
          </button>
          <button className="field-button-secondary" disabled={busy !== null} onClick={() => void downloadFile("PDF")} type="button">
            <Download className="h-4 w-4" aria-hidden />
            .pdf
          </button>
        </ActionGroup>
      </div>
      {download ? (
        <p className="flex flex-wrap items-center gap-2 text-xs leading-5 text-ink-500">
          {download.text}
          {download.reused ? (
            <button
              type="button"
              className="font-medium text-purple-700 underline-offset-2 hover:underline dark:text-purple-300"
              disabled={busy !== null}
              onClick={() => void downloadFile(download.reused as "DOCX" | "PDF", true)}
            >
              Rebuild it
            </button>
          ) : null}
        </p>
      ) : null}

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
          outcome ? "rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm leading-6 text-success-600" : "sr-only"
        }
      >
        {outcome}
      </p>
    </section>
  );
}

export default function ReportForApprovalPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { user, loading } = useAuth();
  const reduce = useAppReducedMotion();

  const [detail, setDetail] = useState<DwApprovalDetail | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registryError, setRegistryError] = useState<string | null>(null);
  /** Bumped to read the report again — after a download, or when a decision found it moved. */
  const [readCount, setReadCount] = useState(0);
  const [open, setOpen] = useState<OpenForm>(null);
  const [reasonStage, setReasonStage] = useState("");
  const [composing, setComposing] = useState<FlagTarget | null>(null);

  const allowed = !loading && canApproveDesignWorkshops(user);
  const media = useReaderMedia("approval", id, detail !== null);
  const tray = useReviewTray(user?.id, id, "approve");

  useEffect(() => {
    if (!allowed) return;
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
  }, [id, allowed, readCount]);

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
        if (!cancelled) setRegistryError("Couldn't load field names. Try again.");
      });
    return () => {
      cancelled = true;
    };
  }, [allowed]);

  const stages: DwStage[] = useMemo(() => registry?.stages ?? [], [registry]);
  const overall = useMemo(() => overallPercent(detail?.completeness), [detail]);
  const active = useActiveStage(
    stages.map((stage) => stage.key),
    detail !== null && registry !== null
  );
  const activeStage = stages.find((stage) => stage.key === active) ?? null;
  const compare = useRoundCompare("approval", id, stages, detail?.stages, detail?.roundSnapshots, detail?.submissionRound);
  const notes = (detail?.inspectionFeedback ?? []) as DwReviewNote[];
  const decisions = detail?.decisions ?? [];
  const inspectors = detail?.inspectors ?? [];
  const actions = detail ? approvalActions(detail) : [];
  const cleared = inspectors.filter((person) => person.clearedAt).length;

  function openDecision(verb: DwApprovalVerb) {
    setOpen(verb);
    if (verb === "revise" && active) setReasonStage(active);
    document.getElementById("decision-panel")?.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
  }

  return (
    <div>
      <PageHeader
        title={detail?.title?.trim() || "Report to approve"}
        description={
          detail
            ? [detail.workshopCode, detail.craftName, detail.clusterName, detail.district, detail.state].filter(Boolean).join(" · ") ||
              "No workshop code yet"
            : undefined
        }
        icon={<Stamp className="h-5 w-5" aria-hidden />}
        actions={detail ? <StatusBadge status={detail.status} /> : undefined}
      />

      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {registryError ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          {registryError}
        </div>
      ) : null}

      {detail === null && !error ? <section className="panel p-4 text-sm text-ink-700">Loading the report…</section> : null}

      {detail ? (
        <>
          <DecisionPanel
            detail={detail}
            drafts={tray.drafts}
            key={`${detail.id}:${detail.status}:${detail.updatedAt ?? ""}`}
            onAnswer={setDetail}
            onReload={() => setReadCount((count) => count + 1)}
            onSent={tray.clear}
            open={open}
            reasonStage={reasonStage}
            setOpen={setOpen}
            setReasonStage={setReasonStage}
            stages={stages}
          />

          <NoteTray
            actions={
              <button
                type="button"
                className="field-button"
                disabled={!actions.some((action) => action.verb === "revise" && action.allowed)}
                onClick={() => openDecision("revise")}
              >
                <Undo2 className="h-4 w-4" aria-hidden />
                {sendBackLabel(tray.drafts.length)}
              </button>
            }
            drafts={tray.drafts}
            footnote="Notes go out when you send the report back."
            onRemove={tray.remove}
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
            <div className="text-sm text-ink-700 sm:col-span-2">
              <span className="field-label block">
                Inspected by{inspectors.length ? ` · ${cleared} of ${inspectors.length} checked, no corrections` : ""}
              </span>
              {inspectors.length ? (
                <ul className="mt-1 flex flex-wrap gap-2">
                  {inspectors.map((person) => (
                    <li className="flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-2.5 py-1" key={person.userId}>
                      <span>{person.name?.trim() || person.email || "An officer no longer named"}</span>
                      {person.clearedAt ? (
                        <ReviewChip tone="fresh">{`Checked, no corrections · ${formatDate(person.clearedAt)}`}</ReviewChip>
                      ) : (
                        <ReviewChip tone="neutral">No verdict yet</ReviewChip>
                      )}
                    </li>
                  ))}
                </ul>
              ) : (
                "Nobody is assigned to inspect this report."
              )}
            </div>
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
                    {decision.note?.trim() ? <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-ink-700">{decision.note}</p> : null}
                    <p className="mt-1 text-xs leading-5 text-ink-500">
                      {decision.actorName?.trim() || "Somebody no longer on record"}
                      {decision.at ? ` · ${formatDateTime(decision.at)}` : ""}
                    </p>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-ink-700">No decisions yet.</p>
            )}
          </section>

          <NoteRegister notes={notes} stages={stages} title="What the officers asked for" truncated={detail.inspectionFeedbackTruncated} />

          <ReaderFilesPanel failure={media.failure} list={media.list} onRetry={media.retry} state={media.state} />

          <RoundCompareControls compare={compare} />

          {registry === null ? (
            registryError ? null : <section className="panel p-4 text-sm text-ink-700">Loading the field list…</section>
          ) : (
            <ReaderMediaProvider list={media.list} state={media.state}>
              <ReviewLayout active={active} changes={compare.changes} completeness={detail.completeness} stages={stages}>
                {stages.map((stage) => (
                  <ReviewReadStage
                    changes={compare.changes?.[stage.key]}
                    data={detail.stages?.[stage.key]}
                    key={stage.key}
                    notes={notes.filter((note) => note.stageKey === stage.key)}
                    onFlag={setComposing}
                    onlyChanges={compare.onlyChanges && compare.changes !== null}
                    registry={registry}
                    score={detail.completeness?.[stage.key]}
                    stage={stage}
                  />
                ))}
              </ReviewLayout>
            </ReaderMediaProvider>
          )}

          <NoteComposer
            onAdd={tray.add}
            onClose={() => setComposing(null)}
            stages={stages}
            target={composing}
          />

          <ReviewDecisionBar stage={activeStage}>
            {actions
              .filter((action) => action.verb !== "handOn" || action.allowed)
              .map((action) => {
                const Icon = action.verb === "approve" ? CheckCircle2 : DECISION_ICON[action.verb];
                return (
                  <button
                    key={action.verb}
                    type="button"
                    className={action.primary ? "field-button" : "field-button-secondary"}
                    disabled={!action.allowed}
                    onClick={() => openDecision(action.verb)}
                  >
                    <Icon className="h-4 w-4" aria-hidden />
                    {action.verb === "revise" && tray.drafts.length ? sendBackLabel(tray.drafts.length) : action.label}
                  </button>
                );
              })}
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => setComposing({ stageKey: active ?? "", fieldKey: null })}
            >
              <MessageSquarePlus className="h-4 w-4" aria-hidden />
              Draft a note
            </button>
          </ReviewDecisionBar>
        </>
      ) : null}
    </div>
  );
}
