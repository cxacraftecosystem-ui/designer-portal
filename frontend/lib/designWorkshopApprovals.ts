/**
 * REPORTS TO APPROVE — the Ministry Admin's sign-off on a design & prototype workshop's report, as
 * the web reads and writes it: the queue, one report read for a decision, the decisions themselves
 * (approve, send back or withdraw an approval, hand on to the office), and the report file.
 *
 * ── WHO DECIDES, AND WHO SAYS SO ────────────────────────────────────────────────────────────────
 *
 * The screen opens for {@link canApproveDesignWorkshops} (`lib/permissions.ts`), the mirror of the
 * server's `require_approving_authority`. Whether THIS account may decide on THIS report — nobody
 * signs off work they authored or inspected — turns on rows no client can see, so the server answers
 * it per report (`mayDecide`, `decisionRefusal`, `refusals`) and this module only DRAWS that answer.
 * {@link approvalActions} fails closed: a server that sent no `mayDecide` gets no live button.
 *
 * ── WHY A PURE HALF ─────────────────────────────────────────────────────────────────────────────
 *
 * There is no React renderer in devDependencies, so a judgement written inside JSX is only ever
 * exercised by somebody looking at a screen. Which buttons a report offers, what each is called and
 * which sentence a disabled one shows are decided here, where
 * `e2e/design-workshop-approvals-unit.spec.ts` can call them.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import {
  downloadDesignWorkshopReport,
  type DwDecision,
  type DwInspectionFeedbackKeys,
  type DwReportBody,
  type DwStageCompleteness,
  type DwStageData,
  type DwSummary
} from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import type { PageResult } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The wire
 * ──────────────────────────────────────────────────────────────────────────── */

/** The four tabs of the queue, as the server's `state` parameter spells them. */
export type DwApprovalQueueState = "awaiting" | "approved" | "handedOn" | "returned";

/** What `/revise` would do from the report's present status. */
export type DwReviseKind = "RETURN" | "WITHDRAW" | "RETURN_FROM_OFFICE";

/** The three decisions, as `mayDecide` and `refusals` key them. */
export type DwApprovalVerb = "approve" | "revise" | "handOn";

/** One row of the queue: the summary, plus the two names the queue resolves in one batch. */
export type DwApprovalRow = DwSummary & {
  approvedByName?: string | null;
  handedOnByName?: string | null;
};

/** An account holding a post on the report — an inspector, or one of its two directors. */
export type DwApprovalPerson = {
  userId: string;
  name: string;
  email?: string;
  role?: string;
  assignedAt?: string | null;
  /** On an oversight row only: ASSISTANT_DIRECTOR or REGIONAL_DIRECTOR. */
  capacity?: string;
};

/** The newest exported file of the report — what a hand-on can name as the file the office received. */
export type DwApprovalExport = {
  id: string;
  format: string;
  templateId?: string | null;
  fileName?: string | null;
  generatedAt: string | null;
  generatedByName?: string | null;
};

/**
 * One report as the Ministry Admin reads it for a decision. The designer's read without the
 * recordings — and with what THIS reader may decide on it.
 *
 * `updatedAt` (a summary key) is the READ STAMP: an approval sends it back as `readAt`, and the
 * server refuses the approval if the report changed after it was opened.
 */
export type DwApprovalDetail = DwSummary &
  DwInspectionFeedbackKeys & {
    stages: Record<string, DwStageData>;
    completeness: Record<string, DwStageCompleteness>;
    schemaVersion: string;
    customSchemaVersion?: string;
    decisions?: DwDecision[];
    approvedByName?: string | null;
    approvedByRole?: string | null;
    handedOnByName?: string | null;
    inspectors?: DwApprovalPerson[];
    oversight?: DwApprovalPerson[];
    readOnly?: boolean;
    mayDecide?: Partial<Record<DwApprovalVerb, boolean>>;
    /** Why this account may not decide on this report at all (it authored or inspects it). */
    decisionRefusal?: string | null;
    /** Why one decision is not available right now — the report's status, in a sentence. */
    refusals?: Partial<Record<DwApprovalVerb, string | null>>;
    reviseKind?: DwReviseKind | null;
    handOnDefaultTo?: string;
    latestExport?: DwApprovalExport | null;
    /** True on the answer to an approve that found the report already approved: nothing was written. */
    alreadyApproved?: boolean;
  };

export type DwApprovalQueueParams = {
  state?: DwApprovalQueueState;
  search?: string | null;
  page?: number;
  pageSize?: number;
};

/** How many reports wait for a decision, and how many approved ones wait to be handed on. */
export function fetchAwaitingApprovalCount() {
  return apiFetch<{ awaiting: number; toHandOn: number }>("/design-workshop-approvals/awaiting-count");
}

export function listReportsForApproval(params: DwApprovalQueueParams) {
  return apiFetch<PageResult<DwApprovalRow>>(
    `/design-workshop-approvals${buildQuery({
      state: params.state,
      search: params.search?.trim() || undefined,
      page: params.page,
      pageSize: params.pageSize
    })}`
  );
}

export function getReportForApproval(workshopId: string) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}`);
}

/**
 * Approve the report as it was read. `round` and `readAt` are copied off the read the decision was
 * taken on — never typed — so an approval cannot land on a round or a version nobody opened.
 */
export function approveReport(workshopId: string, body: { note?: string | null; round: number; readAt: string }) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/approve`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/** Send the report back without approving it, withdraw its approval, or return it from the office. */
export function reviseReport(
  workshopId: string,
  body: { note: string; stageKey?: string | null; fieldKey?: string | null }
) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/revise`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/** Hand the approved report on to the office. Records who, when, to which office and which file. */
export function handOnReport(
  workshopId: string,
  body: { note?: string | null; handedOnTo?: string | null; exportId?: string | null }
) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/hand-on`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/** The report file, generated through the Ministry Admin's own door. */
export function downloadReportForApproval(workshopId: string, body: DwReportBody) {
  return downloadDesignWorkshopReport(workshopId, body, { via: "approvals" });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The queue's tabs
 * ──────────────────────────────────────────────────────────────────────────── */

export const APPROVAL_QUEUE_TABS: ReadonlyArray<{
  value: DwApprovalQueueState;
  label: string;
  /** What an empty tab says. A list that is empty must say which kind of empty it is. */
  empty: string;
}> = [
  {
    value: "awaiting",
    label: "Waiting for approval",
    empty: "No report is waiting for approval. A report arrives here when its designers hand it in."
  },
  {
    value: "approved",
    label: "Approved, not yet handed on",
    empty: "No approved report is waiting to be handed on to the office."
  },
  {
    value: "handedOn",
    label: "Handed on",
    empty: "No report has been handed on to the office yet."
  },
  {
    value: "returned",
    label: "Returned",
    empty: "No report is with its designers for corrections."
  }
];

export function queueTab(value: string | null | undefined) {
  return APPROVAL_QUEUE_TABS.find((tab) => tab.value === value) ?? APPROVAL_QUEUE_TABS[0];
}

/* ────────────────────────────────────────────────────────────────────────────
 * The decisions a report offers, and the words for each
 * ──────────────────────────────────────────────────────────────────────────── */

/** Where a report goes when nobody says otherwise. The DCH template is addressed to this office. */
export const DEFAULT_HANDED_ON_TO = "Office of the Development Commissioner (Handicrafts)";

/** The longest note or reason the server takes — `MAX_DECISION_NOTE_CHARS`. */
export const MAX_DECISION_NOTE_CHARS = 4000;

/** The longest office name a hand-on records. */
export const MAX_HANDED_ON_TO_CHARS = 220;

/**
 * The words for each of `/revise`'s three meanings. One verb on the server, three different acts to
 * the person pressing it: a report sent back before anybody approved it, an approval taken back, and
 * a report taken back from the office.
 */
export const REVISE_COPY: Record<
  DwReviseKind,
  { button: string; title: string; confirm: string; body: string; placeholder: string; done: string }
> = {
  RETURN: {
    button: "Send back without approving",
    title: "Send this report back without approving it?",
    confirm: "Send it back",
    body: "The report goes back to its designers as Needs revision, with your sentence as the correction to make. It comes back here when they hand it in again.",
    placeholder: "Say what needs correcting. The designers read this as written.",
    done: "Sent back. The report is with its designers for corrections, and your sentence is on the record."
  },
  WITHDRAW: {
    button: "Withdraw the approval",
    title: "Withdraw the approval of this report?",
    confirm: "Withdraw the approval",
    body: "The report is no longer approved. It goes back to its designers as Needs revision, with your reason as the correction to make, and it has to be approved again before it can be handed on.",
    placeholder: "Say why the approval is withdrawn and what needs correcting. The designers read this as written.",
    done: "Approval withdrawn. The report is with its designers for corrections, and your reason is on the record."
  },
  RETURN_FROM_OFFICE: {
    button: "Return it from the office",
    title: "Return this report from the office?",
    confirm: "Return it",
    body: "The report was handed on to the office. Returning it takes the approval back as well: it goes to its designers as Needs revision, with your reason as the correction to make, and has to be approved and handed on again. The hand-on stays on the record.",
    placeholder: "Say why it is returned and what needs correcting. The designers read this as written.",
    done: "Returned. The report is with its designers for corrections, and the approval and the hand-on before it stay on the record."
  }
};

/** What `/revise` would do from here, taking the server's word and deriving it only when absent. */
export function reviseKindOf(detail: Pick<DwApprovalDetail, "status" | "handedOnAt" | "reviseKind">): DwReviseKind | null {
  if (detail.reviseKind !== undefined) return detail.reviseKind ?? null;
  const status = String(detail.status ?? "").toUpperCase();
  if (status === "PRE_SUBMISSION") return "RETURN";
  if (status === "APPROVED") return "WITHDRAW";
  if (status === "SUBMITTED" && detail.handedOnAt) return "RETURN_FROM_OFFICE";
  return null;
}

export type DwApprovalAction = {
  verb: DwApprovalVerb;
  label: string;
  /** The main act for this status draws as the primary button. */
  primary: boolean;
  allowed: boolean;
  /** The server's sentence for why not; null when allowed. */
  reason: string | null;
};

/** When the server sent no sentence for a refused decision — said rather than left as a dead button. */
export const NO_DECISION_REASON =
  "The repository did not say whether you may take this decision on this report, so it is not offered. Reload the page to ask again.";

/**
 * THE BUTTONS A REPORT OFFERS, IN ORDER, AND FOR EACH WHETHER IT MAY BE PRESSED AND WHY NOT.
 *
 * OFFERED BY STATUS: approve and send back from Pre-submission; hand on and withdraw once approved;
 * return from the office once handed on. A report anywhere else offers nothing, and the screen says
 * why in {@link noDecisionSentence}.
 *
 * ALLOWED BY THE SERVER ONLY. `mayDecide[verb] === true` — an absent key is a refusal, never a yes —
 * and the reason a refused button shows is the server's: the rule-7 sentence first (this account may
 * not decide on this report at all), then the decision's own.
 */
export function approvalActions(detail: DwApprovalDetail | null | undefined): DwApprovalAction[] {
  if (!detail || detail.deletedAt) return [];
  const status = String(detail.status ?? "").toUpperCase();
  const revise = reviseKindOf(detail);
  const verbs: Array<{ verb: DwApprovalVerb; label: string; primary: boolean }> = [];
  if (status === "PRE_SUBMISSION") {
    verbs.push({ verb: "approve", label: "Approve", primary: true });
  }
  if (status === "APPROVED") {
    verbs.push({ verb: "handOn", label: "Hand on to the office", primary: true });
  }
  if (revise && (status === "PRE_SUBMISSION" || status === "APPROVED" || status === "SUBMITTED")) {
    verbs.push({ verb: "revise", label: REVISE_COPY[revise].button, primary: false });
  }
  return verbs.map(({ verb, label, primary }) => {
    const allowed = detail.mayDecide?.[verb] === true && !detail.decisionRefusal;
    const reason = allowed
      ? null
      : (detail.decisionRefusal?.trim() || detail.refusals?.[verb]?.trim() || NO_DECISION_REASON);
    return { verb, label, primary, allowed, reason };
  });
}

/** Why a report offers no decision at all, by its status. Null while it offers one. */
export function noDecisionSentence(detail: DwApprovalDetail | null | undefined): string | null {
  if (!detail) return null;
  if (approvalActions(detail).length > 0) return null;
  const status = String(detail.status ?? "").toUpperCase();
  switch (status) {
    case "NEEDS_REVISION":
      return "This report is with its designers for corrections. It comes back here when they hand it in again.";
    case "SUBMITTED":
      return "This report was handed in before reports were approved on this platform, so there is no approval to give, withdraw or hand on.";
    case "ARCHIVED":
      return "This report is archived. It comes back here if its designers hand it in again.";
    case "DRAFT":
    case "IN_PROGRESS":
    case "COMPLETE":
      return "This report has not been handed in, so there is nothing to decide yet. It arrives here when its designers hand it in.";
    default:
      return "This version of the app does not recognise this report's status, so it offers no decision. Reload the page; if it persists, this browser needs a newer build.";
  }
}

/**
 * The exported file a hand-on can name: the newest export, and only when it was made after the
 * approval — a file from before the approval is not the approved report. Null otherwise.
 */
export function pinnableExport(
  detail: Pick<DwApprovalDetail, "latestExport" | "approvedAt"> | null | undefined
): DwApprovalExport | null {
  const latest = detail?.latestExport;
  if (!latest || !latest.generatedAt || !detail?.approvedAt) return null;
  return new Date(latest.generatedAt).getTime() >= new Date(detail.approvedAt).getTime() ? latest : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The record, in words
 * ──────────────────────────────────────────────────────────────────────────── */

export const DECISION_KIND_LABELS: Record<string, string> = {
  APPROVED: "Approved",
  APPROVAL_WITHDRAWN: "Approval withdrawn",
  RETURNED: "Sent back by the approving authority",
  SENT_BACK: "Sent back by an inspector",
  HANDED_ON: "Handed on to the office"
};

/** A decision's label; a verb this build has never heard of is still a decision, said so. */
export function decisionKindLabel(kind: string | null | undefined): string {
  return (kind && DECISION_KIND_LABELS[kind]) || "A decision on this report";
}

/** A name, or what to say when the account behind an id has gone. Never a guess. */
function nameOrGone(name: string | null | undefined, id: string | null | undefined): string | null {
  const clean = name?.trim();
  if (clean) return clean;
  return id ? "somebody no longer on record" : null;
}

/** Everything a screen needs to say who signed a report off — from a summary, a detail or a row. */
export type DwSignOffFacts = {
  status?: string | null;
  approvedAt?: string | null;
  approvedById?: string | null;
  approvedByName?: string | null;
  handedOnAt?: string | null;
  handedOnById?: string | null;
  handedOnByName?: string | null;
  handedOnTo?: string | null;
};

/**
 * "Approved by A on 9 Oct 2026." and "Handed on to the Office of … by B on 10 Oct 2026." — the two
 * lines every reader of a signed-off report is shown. A name the read did not carry is left out
 * rather than guessed; an id with no name is "somebody no longer on record". Empty when the report
 * is not approved and was never handed on.
 */
export function signOffLines(facts: DwSignOffFacts | null | undefined): string[] {
  if (!facts) return [];
  const lines: string[] = [];
  if (facts.approvedAt) {
    const by = nameOrGone(facts.approvedByName, facts.approvedById);
    lines.push(`Approved${by ? ` by ${by}` : ""} on ${formatDate(facts.approvedAt)}.`);
  }
  if (facts.handedOnAt) {
    const by = nameOrGone(facts.handedOnByName, facts.handedOnById);
    const office = facts.handedOnTo?.trim() || "the office";
    lines.push(`Handed on to ${office}${by ? ` by ${by}` : ""} on ${formatDate(facts.handedOnAt)}.`);
  }
  return lines;
}
