import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import { downloadFile } from "@/lib/fileDownload";
import { hasRank } from "@/lib/permissions";
import type { DwStatus } from "@/lib/designWorkshops";
import type { User } from "@/lib/types";
import { exportFilename } from "@/lib/naming";

export type SanctionOrder = {
  id: string;
  sanctionOrderNo: string;
  sanctionOrderDate: string | null;
  sanctionAmount: string;
  designerUserId: string;
  designerName: string;
  designerEmail: string;
  accountCreated: boolean;
  notes: string | null;
  designWorkshopId: string;
  workshopTitle: string;
  workshopStatus: DwStatus | string;
  createdById: string;
  createdByName: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  reportCopyMatches: boolean | null;
  readyForWork: boolean;
  missingMandatory: string[];
  designers: SanctionOrderDesigner[];
  sourceFilename: string | null;
  sheetRow: number | null;
  lastRemindedAt?: string | null;
  plannedWorkshop?: PlannedWorkshopRef | null;
};

export type SanctionEmailStatus = {
  id: string;
  status: "QUEUED" | "SENT" | "FAILED";
  at: string | null;
  error: string | null;
};

export function linkEmailWords(status: SanctionEmailStatus | null | undefined): string | null {
  if (!status) return null;
  if (status.status === "SENT") return "Sign-in e-mail sent";
  if (status.status === "FAILED") return "Sign-in e-mail failed";
  return "Sign-in e-mail sending";
}

export type PlannedWorkshopRef = {
  id: string;
  workshopNo: string;
  planYear: number;
  planYearLabel: string;
  plannedTitle: string | null;
};

export type PlannedWorkshopCandidate = {
  id: string;
  planYear: number;
  planYearLabel: string;
  workshopNo: string;
  plannedTitle: string | null;
  workshopKindLabel: string | null;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  plannedStartDate: string | null;
  plannedEndDate: string | null;
  standing: "PLANNED" | "PROMOTED" | "WITHDRAWN" | string;
  pickable: boolean;
  reason: string | null;
};

export async function findPlannedWorkshops(workshopNo: string) {
  return apiFetch<{ items: PlannedWorkshopCandidate[]; truncated: boolean }>(
    `/sanction-orders/planned-workshops${buildQuery({ workshopNo })}`
  );
}

export type SanctionOrderDesigner = {
  designerUserId: string;
  designerName: string;
  designerEmail: string;
  signInEmail: string;
  accountCreated: boolean;
  position: number;
  linkEmail?: SanctionEmailStatus | null;
};

export type SanctionOrderCredentialLink = {
  id: string;
  link: string | null;
  expiresAt: string;
  purpose: "INVITE" | "RESET";
  deliveredBy: string;
};

export type SanctionOrderCredential = {
  designerUserId: string;
  designerName: string;
  designerEmail: string;
  signInEmail: string;
  link: SanctionOrderCredentialLink | null;
  problem: string | null;
};

export type SanctionOrderCreated = {
  sanctionOrder: SanctionOrder;
  credentialLink: SanctionOrderCredentialLink | null;
  credentialLinkProblem: string | null;
  credentialLinks: SanctionOrderCredential[];
  linksEmailed?: boolean;
  reportCopySeeded?: boolean;
};

export const SANCTION_SEED_FAILED_NOTE =
  "Workshop Setup was not filled from this order. Ask the designer to enter the sanction details in stage 1.";

export type SanctionOrderPage = {
  items: SanctionOrder[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
};

export type SanctionOrderQuery = {
  page?: number;
  pageSize?: number;
  search?: string;
  mine?: boolean;
  dateFrom?: string;
  dateTo?: string;
  sort?: string;
  dir?: string;
};

export function canRecordSanctionOrders(user: User | null | undefined) {
  return hasRank(user, "ASSISTANT_DIRECTOR");
}

export const SANCTION_ORDER_REFUSAL =
  "Only an Assistant Director or above records sanction orders. Ask the officer holding yours " +
  "— the workshop appears in your list once they do.";

export function formatSanctionAmount(amount: string | null | undefined): string {
  const raw = String(amount ?? "").trim();
  if (!raw) return "";
  const value = Number(raw);
  if (!Number.isFinite(value)) return raw;
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value);
}

export const MESSAGE_TEMPLATE =
  "{designerName}, sanction order {sanctionOrderNo} dated {date} has been recorded and a workshop " +
  "has been opened in your name on the Design Prototype Workshop portal.\n\n" +
  "Set your password here — the link works once and expires on {expires}:\n{link}\n\n" +
  "Sign in with {signInEmail}. After signing in, open the workshop and fill in the starred fields " +
  "on Workshop Setup (State, District, Craft, Cluster, Venue, the dates and the rest) before you " +
  "begin.";

export function sanctionMessageFor(
  order: SanctionOrder,
  link: SanctionOrderCredentialLink,
  signInEmail: string
): string {
  return MESSAGE_TEMPLATE.replace("{designerName}", order.designerName || "Hello")
    .replace("{sanctionOrderNo}", order.sanctionOrderNo)
    .replace("{date}", order.sanctionOrderDate ?? "")
    .replace("{expires}", formatLinkExpiry(link.expiresAt))
    .replace("{link}", link.link ?? "")
    .replace("{signInEmail}", signInEmail || order.designerEmail);
}

export function formatLinkExpiry(iso: string): string {
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return iso;
  return when.toLocaleString("en-IN", {
    dateStyle: "medium",
    timeStyle: "short"
  });
}

export function readinessSentence(order: SanctionOrder): string {
  if (order.readyForWork) return "Ready";
  const outstanding = order.missingMandatory.length;
  if (!outstanding) return "Awaiting designer details";
  return `Awaiting designer details — ${outstanding} still needed`;
}

export async function listSanctionOrders(query: SanctionOrderQuery = {}) {
  return apiFetch<SanctionOrderPage>(
    `/sanction-orders${buildQuery({
      page: query.page,
      pageSize: query.pageSize,
      search: query.search,
      mine: query.mine ? "true" : undefined,
      dateFrom: query.dateFrom,
      dateTo: query.dateTo,
      sort: query.sort,
      dir: query.dir
    })}`
  );
}

export async function fetchAwaitingSanctionCount() {
  return apiFetch<{ awaiting: number }>("/sanction-orders/awaiting-count");
}

export async function deleteSanctionOrder(id: string) {
  await apiFetch<void>(`/sanction-orders/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export async function getSanctionOrder(id: string) {
  return apiFetch<SanctionOrder>(`/sanction-orders/${encodeURIComponent(id)}`);
}

export async function recordSanctionOrder(body: {
  sanctionOrderNo: string;
  sanctionOrderDate: string;
  sanctionAmount: string;
  designerName: string;
  designerEmail: string;
  notes?: string | null;
  coDesigners?: { name: string; email: string }[];
  annualPlanEntryId?: string;
}) {
  return apiFetch<SanctionOrderCreated>("/sanction-orders", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export async function updateSanctionNotes(id: string, notes: string | null) {
  return apiFetch<SanctionOrder>(`/sanction-orders/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify({ notes })
  });
}

export async function issueSanctionCredentialLink(
  id: string,
  designerUserId?: string | null,
  deliverBy: "COPY_LINK" | "EMAIL" = "COPY_LINK"
) {
  return apiFetch<SanctionOrderCredentialLink>(
    `/sanction-orders/${encodeURIComponent(id)}/credential-link${buildQuery({
      designerUserId: designerUserId || undefined,
      deliverBy: deliverBy === "EMAIL" ? "EMAIL" : undefined
    })}`,
    { method: "POST" }
  );
}

export type SanctionReminderAnswer = {
  queued: number;
  recent: number;
  optedOut: number;
  noAddress: number;
  lastRemindedAt: string | null;
  missing: string[];
};

export async function remindSanctionDesigners(id: string) {
  return apiFetch<SanctionReminderAnswer>(`/sanction-orders/${encodeURIComponent(id)}/remind`, {
    method: "POST"
  });
}

export function reminderSentence(answer: SanctionReminderAnswer): string {
  const parts: string[] = [];
  if (answer.queued > 0) parts.push(`Reminder sent to ${answer.queued === 1 ? "1 designer" : `${answer.queued} designers`}.`);
  if (answer.recent > 0) parts.push(`${answer.recent === 1 ? "1 was" : `${answer.recent} were`} reminded in the last hour.`);
  if (answer.optedOut > 0) parts.push(`${answer.optedOut === 1 ? "1 has" : `${answer.optedOut} have`} turned these e-mails off.`);
  return parts.join(" ") || "No reminder was sent.";
}

export async function revokeSanctionCredentialLink(id: string, tokenId: string) {
  return apiFetch<{ ok: boolean }>(
    `/sanction-orders/${encodeURIComponent(id)}/credential-link/revoke${buildQuery({ tokenId })}`,
    { method: "POST" }
  );
}


export async function listSanctionDesigners(search?: string) {
  return apiFetch<{
    users: { id: string; name: string; email: string; role: string }[];
    truncated: boolean;
  }>(`/sanction-orders/designers${buildQuery({ search: search || undefined })}`);
}

export type SanctionParseProblem = {
  sheet: string;
  row: number | null;
  severity: string;
  reason: string;
  value: string | null;
};

export type SanctionImportDesigner = {
  name: string;
  email: string;
  canonicalEmail: string;
  userId: string | null;
  accountExists: boolean;
  empanelled: boolean;
  nameSource: string;
};

export type SanctionImportQuestion = {
  code: string;
  question: string;
  candidates: { name: string; email: string; source: string }[];
};

export type SanctionImportRow = {
  sheetRow: number;
  sanctionOrderNo: string;
  sanctionOrderDate: string | null;
  sanctionAmount: string | null;
  notes: string | null;
  verdict: string;
  reason: string | null;
  designers: SanctionImportDesigner[];
  questions: SanctionImportQuestion[];
  unpairedNames: string[];
  unpairedEmails: string[];
};

export type SanctionImportPreview = {
  sheet: string | null;
  sourceFilename: string | null;
  rowsRead: number;
  ready: SanctionImportRow[];
  needsReview: SanctionImportRow[];
  refused: SanctionImportRow[];
  problems: SanctionParseProblem[];
};

export type SanctionImportReport = {
  sheet: string | null;
  sourceFilename: string | null;
  rowsRead: number;
  recorded: number;
  skipped: number;
  refused: number;
  accountsCreated: number;
  credentialLinksIssued: number;
  created: {
    sheetRow: number;
    sanctionOrderId: string;
    sanctionOrderNo: string;
    designWorkshopId: string;
    designers: SanctionOrderDesigner[];
    accountsCreated: number;
    sanctionOrder: SanctionOrder;
    credentialLinks: SanctionOrderCredential[];
  }[];
  importId: string | null;
  problems: SanctionParseProblem[];
};

export async function uploadSanctionOrders(file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<SanctionImportPreview>("/sanction-orders/upload", { method: "POST", body });
}

export async function confirmSanctionImport(body: {
  sheet?: string | null;
  sourceFilename?: string | null;
  rowsRead: number;
  refusedBeforeConfirm: number;
  rows: {
    sheetRow: number;
    action: "record" | "skip";
    sanctionOrderNo: string;
    sanctionOrderDate: string;
    sanctionAmount: string;
    notes?: string | null;
    designers: { name: string; email: string }[];
  }[];
}) {
  return apiFetch<SanctionImportReport>("/sanction-orders/upload/confirm", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export async function downloadSanctionProForma(): Promise<void> {
  await downloadFile("/sanction-orders/pro-forma.xlsx", exportFilename("Sanction-Orders", "Pro-Forma", "xlsx", { dated: false }));
}
