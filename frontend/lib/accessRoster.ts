import { apiFetch, buildQuery } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { roleLabel } from "@/lib/permissions";
import type { PageResult, UserRole } from "@/lib/types";

export type AccessStatus = "ACTIVE" | "PENDING" | "REJECTED" | "SUSPENDED";

export type AccessRosterEntry = {
  id: string;
  email: string;
  fullName: string | null;
  status: AccessStatus;
  admitRole: UserRole | null;
  joinedAt: string | null;
  requestedAt: string | null;
  attemptCount: number;
  lastAttemptAt: string | null;
  decidedAt: string | null;
  decidedById: string | null;
  firstSeenAt: string | null;
  notes: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  addedById: string | null;
  addedByName?: string | null;
  decidedByName?: string | null;
};

export type AccessRosterPage = PageResult<AccessRosterEntry>;

export type PendingAccessCount = {
  pending: number;
  capacity: number;
  capReached: boolean;
};

export async function listAccessRoster(params: {
  page?: number;
  pageSize?: number;
  search?: string;
  status?: AccessStatus;
}): Promise<AccessRosterPage> {
  return apiFetch<AccessRosterPage>(
    `/access/roster${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search,
      status: params.status
    })}`
  );
}

export async function fetchPendingAccessCount(): Promise<PendingAccessCount> {
  return apiFetch<PendingAccessCount>("/access/roster/pending-count");
}

export type AccessRosterAddAnswer = AccessRosterEntry & { accountCreated?: boolean; accountPromotionHeld?: string | null };

export async function addToAccessRoster(body: {
  email: string;
  fullName?: string | null;
  role?: UserRole | null;
  notes?: string | null;
  password?: string;
  mustChangePassword?: boolean;
}): Promise<AccessRosterAddAnswer> {
  return apiFetch<AccessRosterAddAnswer>("/access/roster", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export type AccessDecisionAnswer = AccessRosterEntry & { accountPromotionHeld?: string | null };

export async function decideAccessRequest(
  id: string,
  body: { decision: "APPROVE" | "REJECT"; role?: UserRole | null; notes?: string | null }
): Promise<AccessDecisionAnswer> {
  return apiFetch<AccessDecisionAnswer>(`/access/roster/${id}/decision`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function promotionHeldSentence(answer: Pick<AccessDecisionAnswer, "accountPromotionHeld"> | null | undefined): string | null {
  const sentence = answer?.accountPromotionHeld;
  return typeof sentence === "string" && sentence.trim() ? sentence.trim() : null;
}

export async function updateAccessEntry(
  id: string,
  body: { fullName?: string | null; role?: UserRole | null; notes?: string | null }
): Promise<AccessRosterEntry> {
  return apiFetch<AccessRosterEntry>(`/access/roster/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export async function suspendAccessEntry(id: string): Promise<AccessRosterEntry> {
  return apiFetch<AccessRosterEntry>(`/access/roster/${id}`, { method: "DELETE" });
}

export type AccessRefusalKind =
  | "PENDING"
  | "REJECTED"
  | "SUSPENDED"
  | "DESIGNER_SUSPENDED"
  | "QUEUE_FULL"
  | "BAD_CREDENTIAL"
  | "UNCLASSIFIED";

export const ACCESS_STATUS_HEADER = "x-access-status";

export function accessRefusalKind(status: number, header: string | null | undefined): AccessRefusalKind {
  if (status === 401) return "BAD_CREDENTIAL";
  const flag = (header ?? "").trim().toUpperCase();
  switch (flag) {
    case "PENDING":
      return "PENDING";
    case "REJECTED":
      return "REJECTED";
    case "SUSPENDED":
      return "SUSPENDED";
    case "DESIGNER_SUSPENDED":
      return "DESIGNER_SUSPENDED";
    case "NOT_RECORDED":
      return "QUEUE_FULL";
    default:
      return "UNCLASSIFIED";
  }
}

export function accessRefusalChrome(
  kind: AccessRefusalKind
): { heading: string; advice: string; tone: "waiting" | "refused" } | null {
  switch (kind) {
    case "PENDING":
      return {
        heading: "You are on the list, waiting for an administrator",
        advice:
          "An admin must approve your request. Retrying won't speed it up.",
        tone: "waiting"
      };
    case "REJECTED":
      return {
        heading: "Your request was reviewed and not approved",
        advice:
          "Contact an admin if this is a mistake.",
        tone: "refused"
      };
    case "SUSPENDED":
      return {
        heading: "Your access to this application has been suspended",
        advice:
          "Only an admin can restore access.",
        tone: "refused"
      };
    case "DESIGNER_SUSPENDED":
      return {
        heading: "Your designer empanelment has ended",
        advice:
          "Ask an admin to restore you on the designer roster.",
        tone: "refused"
      };
    case "QUEUE_FULL":
      return {
        heading: "Requests to join are temporarily closed",
        advice:
          "The approval queue is full. Contact an admin.",
        tone: "refused"
      };
    default:
      return null;
  }
}

export function accessEntryDetails(entry: AccessRosterEntry): [string, string | null][] {
  const standing =
    entry.status === "ACTIVE"
      ? `May sign in ${entry.admitRole ? `as ${roleLabel(entry.admitRole)}` : "with the default role"}`
      : entry.status === "PENDING"
        ? "Waiting for a decision"
        : `${entry.status === "REJECTED" ? "Refused" : "Suspended"} ${entry.decidedAt ? `since ${formatDate(entry.decidedAt)}` : "(date not recorded)"}`;
  return [
    ["Email", entry.email],
    ["Standing", standing],
    ["Added", entry.createdAt ? formatDate(entry.createdAt) : "Not recorded"],
    ["Added by", entry.addedByName ?? null],
    ...(entry.status === "PENDING" ? [] : [["Decided by", entry.decidedByName ?? null] as [string, string | null]]),
    ["Joined", entry.joinedAt ? formatDate(entry.joinedAt) : "Not yet"],
    [
      "First signed in",
      entry.firstSeenAt ? formatDate(entry.firstSeenAt) : entry.joinedAt ? "Has not signed in yet" : "Has never been admitted"
    ],
    [
      "Requests",
      entry.attemptCount > 0
        ? `${entry.attemptCount} refused attempt${entry.attemptCount === 1 ? "" : "s"}${entry.lastAttemptAt ? `, last ${formatDateTime(entry.lastAttemptAt)}` : ""}`
        : "None"
    ],
    ["Note", entry.notes]
  ];
}
