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
import type { DwClearedFilter, DwQueueSort, DwReadiness, DwReviewAidKeys } from "@/lib/reviewAids";
import type { PageResult } from "@/lib/types";

export type DwApprovalQueueState = "awaiting" | "approved" | "handedOn" | "returned";

export type DwReviseKind = "RETURN" | "WITHDRAW" | "RETURN_FROM_OFFICE";

export type DwApprovalVerb = "approve" | "revise" | "handOn";

export type DwApprovalRow = DwSummary & {
  approvedByName?: string | null;
  handedOnByName?: string | null;
  readiness?: DwReadiness | null;
};

export type DwApprovalPerson = {
  userId: string;
  name: string;
  email?: string;
  role?: string;
  assignedAt?: string | null;
  capacity?: string;
  clearedAt?: string | null;
};

export type DwApprovalExport = {
  id: string;
  format: string;
  templateId?: string | null;
  fileName?: string | null;
  generatedAt: string | null;
  generatedByName?: string | null;
};

export type DwApprovalDetail = DwSummary &
  DwInspectionFeedbackKeys &
  DwReviewAidKeys & {
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
    decisionRefusal?: string | null;
    refusals?: Partial<Record<DwApprovalVerb, string | null>>;
    reviseKind?: DwReviseKind | null;
    handOnDefaultTo?: string;
    latestExport?: DwApprovalExport | null;
    alreadyApproved?: boolean;
    delivery?: DwReportDelivery;
    deliveryFailed?: boolean;
  };

export type DwDeliveryEmail = {
  address: string;
  status: "QUEUED" | "SENT" | "FAILED";
  at: string | null;
  error: string | null;
};

export type DwReportDelivery = {
  configured: boolean;
  addressCount: number;
  mailAvailable?: boolean;
  officeName: string;
  linkDays: number;
  latest: {
    id: string;
    sentAt: string | null;
    expiresAt: string | null;
    state: "LIVE" | "EXPIRED" | "REVOKED";
    officeName: string;
    fileName: string;
    firstOpenedAt: string | null;
    lastOpenedAt: string | null;
    openCount: number;
    emails: DwDeliveryEmail[];
  } | null;
};

export function deliveryOffer(
  detail: DwApprovalDetail | null | undefined
): { kind: "send" | "setup" | "none"; label: string } {
  if (!detail || detail.deletedAt || detail.decisionRefusal) return { kind: "none", label: "" };
  const status = String(detail.status ?? "").toUpperCase();
  let label = "";
  if (status === "PRE_SUBMISSION" && detail.mayDecide?.approve === true) label = "Approve and hand on";
  if (status === "APPROVED" && detail.mayDecide?.handOn === true) label = "Hand on by e-mail";
  if (!label) return { kind: "none", label: "" };
  const delivery = detail.delivery;
  if (delivery?.configured) return { kind: "send", label };
  if (delivery?.mailAvailable === false) return { kind: "none", label: "" };
  if (delivery && delivery.addressCount > 0) return { kind: "none", label: "" };
  return { kind: "setup", label };
}

export function deliveryLines(delivery: DwReportDelivery | null | undefined, format: (iso: string) => string): string[] {
  const latest = delivery?.latest;
  if (!latest) return [];
  const lines = [`E-mailed to ${latest.officeName}${latest.sentAt ? ` on ${format(latest.sentAt)}` : ""}.`];
  if (latest.state === "LIVE" && latest.expiresAt) lines.push(`The link works until ${format(latest.expiresAt)}.`);
  if (latest.state === "EXPIRED") lines.push("The link has expired.");
  lines.push(latest.firstOpenedAt ? `Opened ${format(latest.firstOpenedAt)}.` : "Not opened yet.");
  return lines;
}

export function approveAndHandOn(
  workshopId: string,
  body: { note?: string | null; round?: number; readAt?: string; format?: "PDF" | "DOCX" }
) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/approve-and-hand-on`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function resendReportDelivery(workshopId: string) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/delivery/resend`, { method: "POST" });
}

export type ReportDeliverySettings = {
  addresses: string[];
  officeName: string;
  defaultOfficeName: string;
  mailAvailable: boolean;
};

export function fetchReportDeliverySettings() {
  return apiFetch<ReportDeliverySettings>("/settings/report-delivery");
}

export function saveReportDeliverySettings(body: { addresses: string[]; officeName: string | null }) {
  return apiFetch<ReportDeliverySettings>("/settings/report-delivery", { method: "PUT", body: JSON.stringify(body) });
}

export type ReportDownloadInfo = { workshopTitle: string | null; fileName: string; officeName: string; expiresAt: string };

export function lookupReportDownload(token: string) {
  return apiFetch<ReportDownloadInfo>(
    "/report-deliveries/lookup",
    { method: "POST", body: JSON.stringify({ token }) },
    { redirectOn401: false }
  );
}

export function openReportDownload(token: string) {
  return apiFetch<{ url: string; fileName: string }>(
    "/report-deliveries/download",
    { method: "POST", body: JSON.stringify({ token }) },
    { redirectOn401: false }
  );
}

export type DwApprovalQueueParams = {
  state?: DwApprovalQueueState;
  search?: string | null;
  page?: number;
  pageSize?: number;
  sort?: DwQueueSort;
  cleared?: DwClearedFilter;
};

export function fetchAwaitingApprovalCount() {
  return apiFetch<{ awaiting: number; toHandOn: number }>("/design-workshop-approvals/awaiting-count");
}

export function listReportsForApproval(params: DwApprovalQueueParams) {
  return apiFetch<PageResult<DwApprovalRow>>(
    `/design-workshop-approvals${buildQuery({
      state: params.state,
      search: params.search?.trim() || undefined,
      page: params.page,
      pageSize: params.pageSize,
      sort: params.sort && params.sort !== "default" ? params.sort : undefined,
      cleared: params.cleared || undefined
    })}`
  );
}

export function getReportForApproval(workshopId: string) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}`);
}

export function approveReport(workshopId: string, body: { note?: string | null; round: number; readAt: string }) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/approve`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function reviseReport(
  workshopId: string,
  body: {
    note: string | null;
    stageKey?: string | null;
    fieldKey?: string | null;
    notes?: Array<{ note: string; stageKey?: string | null; fieldKey?: string | null }>;
  }
) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/revise`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function handOnReport(
  workshopId: string,
  body: { note?: string | null; handedOnTo?: string | null; exportId?: string | null }
) {
  return apiFetch<DwApprovalDetail>(`/design-workshop-approvals/${workshopId}/hand-on`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function downloadReportForApproval(workshopId: string, body: DwReportBody) {
  return downloadDesignWorkshopReport(workshopId, body, { via: "approvals" });
}

export const APPROVAL_QUEUE_TABS: ReadonlyArray<{
  value: DwApprovalQueueState;
  label: string;
  empty: string;
}> = [
  {
    value: "awaiting",
    label: "Waiting for approval",
    empty: "Nothing waiting for approval."
  },
  {
    value: "approved",
    label: "Approved, not yet handed on",
    empty: "Nothing waiting to hand on."
  },
  {
    value: "handedOn",
    label: "Handed on",
    empty: "Nothing handed on yet."
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

export const DEFAULT_HANDED_ON_TO = "Office of the Development Commissioner (Handicrafts)";

export const MAX_DECISION_NOTE_CHARS = 4000;

export const MAX_HANDED_ON_TO_CHARS = 220;

export const REVISE_COPY: Record<
  DwReviseKind,
  { button: string; title: string; confirm: string; body: string; placeholder: string; done: string }
> = {
  RETURN: {
    button: "Send back without approving",
    title: "Send this report back without approving it?",
    confirm: "Send it back",
    body: "It goes back to its designers as Needs revision.",
    placeholder: "What needs correcting",
    done: "Sent back."
  },
  WITHDRAW: {
    button: "Withdraw the approval",
    title: "Withdraw the approval of this report?",
    confirm: "Withdraw the approval",
    body: "It goes back to its designers as Needs revision and must be approved again.",
    placeholder: "Why, and what needs correcting",
    done: "Approval withdrawn."
  },
  RETURN_FROM_OFFICE: {
    button: "Return it from the office",
    title: "Return this report from the office?",
    confirm: "Return it",
    body: "This also withdraws the approval. It goes back to its designers as Needs revision.",
    placeholder: "Why, and what needs correcting",
    done: "Returned."
  }
};

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
  primary: boolean;
  allowed: boolean;
  reason: string | null;
};

export const NO_DECISION_REASON =
  "Couldn't check your permission. Reload the page.";

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

export function noDecisionSentence(detail: DwApprovalDetail | null | undefined): string | null {
  if (!detail) return null;
  if (approvalActions(detail).length > 0) return null;
  const status = String(detail.status ?? "").toUpperCase();
  switch (status) {
    case "NEEDS_REVISION":
      return "With its designers for corrections.";
    case "SUBMITTED":
      return "Handed in before reports were approved here — nothing to decide.";
    case "ARCHIVED":
      return "Archived.";
    case "DRAFT":
    case "IN_PROGRESS":
    case "COMPLETE":
      return "Not handed in yet.";
    default:
      return "Unknown status. Reload to update the app.";
  }
}

export function pinnableExport(
  detail: Pick<DwApprovalDetail, "latestExport" | "approvedAt"> | null | undefined
): DwApprovalExport | null {
  const latest = detail?.latestExport;
  if (!latest || !latest.generatedAt || !detail?.approvedAt) return null;
  return new Date(latest.generatedAt).getTime() >= new Date(detail.approvedAt).getTime() ? latest : null;
}

export const DECISION_KIND_LABELS: Record<string, string> = {
  APPROVED: "Approved",
  APPROVAL_WITHDRAWN: "Approval withdrawn",
  RETURNED: "Sent back by the approving authority",
  SENT_BACK: "Sent back by an inspector",
  HANDED_ON: "Handed on to the office"
};

export function decisionKindLabel(kind: string | null | undefined): string {
  return (kind && DECISION_KIND_LABELS[kind]) || "A decision on this report";
}

function nameOrGone(name: string | null | undefined, id: string | null | undefined): string | null {
  const clean = name?.trim();
  if (clean) return clean;
  return id ? "somebody no longer on record" : null;
}

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
