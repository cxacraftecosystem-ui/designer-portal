import { apiFetch, buildQuery } from "@/lib/api";
import { downloadFile } from "@/lib/fileDownload";
import type { PageResult } from "@/lib/types";
import { exportFilename } from "@/lib/naming";

export type RegisterKind = "design" | "other";

export const DEFAULT_REGISTER_KIND: RegisterKind = "design";

export const REGISTER_KINDS: ReadonlyArray<{
  value: RegisterKind;
  label: string;
  note: string;
}> = [
  {
    value: "design",
    label: "Design & prototype workshops",
    note: "Progress through the 22 stages."
  },
  {
    value: "other",
    label: "Other workshops",
    note: "Field visits, by review status."
  }
];

export const DESIGN_STANDINGS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Everything" },
  { value: "ongoing", label: "Ongoing" },
  { value: "completed", label: "Completed" },
  { value: "registered", label: "Newly registered" }
];

export const OTHER_STANDINGS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Everything" },
  { value: "DRAFT", label: "Draft" },
  { value: "PENDING", label: "Pending" },
  { value: "NEEDS_REVISION", label: "Needs revision" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" }
];

export function standingsFor(kind: RegisterKind) {
  return kind === "design" ? DESIGN_STANDINGS : OTHER_STANDINGS;
}

export type RegisterProgress = {
  percent: number | null;
  stagesTotal: number;
  stagesComplete: number | null;
  requiredTotal: number | null;
  requiredFilled: number | null;
  definitionRead: boolean;
  unscoredReason?: string;
  customSchemaVersion?: string;
};

export type DesignRegisterRow = {
  id: string;
  title: string;
  status: string;
  workshopCode: string | null;
  workshopKind: string | null;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  venue: string | null;
  startDate: string | null;
  endDate: string | null;
  designerName: string | null;
  updatedAt: string | null;
  beneficiaries: number | null;
  standingGroup: string | null;
  progress: RegisterProgress;
  approvedAt?: string | null;
  handedOnAt?: string | null;
  handedOnTo?: string | null;
  received?: { sentAt: string | null; firstOpenedAt: string | null; officeName: string } | null;
};

export function receivedWords(
  received: DesignRegisterRow["received"],
  format: (iso: string) => string
): string | null {
  if (!received?.sentAt) return null;
  return received.firstOpenedAt
    ? `Received by ${received.officeName} · opened ${format(received.firstOpenedAt)}`
    : `Received by ${received.officeName} · not opened yet`;
}

export type OtherRegisterRow = {
  id: string;
  title: string;
  workshopType: string;
  place: string | null;
  date: string | null;
  startDate: string | null;
  endDate: string | null;
  status: string;
  beneficiaries: number | null;
  createdAt: string | null;
  updatedAt: string | null;
};

export type RegisterMeta = {
  scope: "estate" | "posted" | string;
  scopeLabel: string;
  standingVocabulary: string;
  standingGroups: Record<string, string[]>;
  progressScoreCap?: number;
};

export type DesignRegisterPage = PageResult<DesignRegisterRow> & RegisterMeta;
export type OtherRegisterPage = PageResult<OtherRegisterRow> & RegisterMeta;

export type RegisterSummary = {
  designWorkshops: {
    total: number;
    ongoing: number;
    completed: number;
    registered: number;
    unclassified: number;
    scope: string;
    scopeLabel: string;
    attention?: Partial<Record<RegisterAttention, number>>;
    setupIncompleteCapped?: boolean;
    byState?: { state: string | null; count: number }[];
    sanctioned?: { orders: number; amount: string };
  };
  otherWorkshops: { total: number; scope: string; scopeLabel: string };
  scope: string;
  scopeLabel: string;
};

export type RegisterEntitlements = {
  beneficiaries: boolean;
  beneficiariesRefusal: string;
};

export type RegisterQuery = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  standing?: string | null;
  attention?: RegisterAttention | null;
  state?: string | null;
};

export type RegisterAttention = "awaitingApproval" | "returned" | "overdue" | "setupIncomplete";

export const REGISTER_ATTENTION: readonly { value: RegisterAttention; label: string }[] = [
  { value: "awaitingApproval", label: "Awaiting approval" },
  { value: "returned", label: "Returned" },
  { value: "overdue", label: "Overdue" },
  { value: "setupIncomplete", label: "Setup incomplete" }
];

function registerQuery(params: RegisterQuery) {
  return buildQuery({
    page: params.page,
    pageSize: params.pageSize,
    search: params.search ?? undefined,
    standing: params.standing ?? undefined,
    attention: params.attention ?? undefined,
    state: params.state ?? undefined
  });
}

export type StageDataExport = {
  id: string;
  status: "QUEUED" | "RUNNING" | "READY" | "FAILED" | "EXPIRED" | string;
  filters: Record<string, string>;
  workshopCount: number | null;
  rowCount: number | null;
  fileName: string | null;
  fileSizeBytes: number | null;
  error: string | null;
  createdAt: string | null;
  finishedAt: string | null;
  expiresAt: string | null;
};

const EXPORTS = "/ministry-dashboard/stage-data-exports";

export function requestStageDataExport(filters: {
  search?: string;
  standing?: string;
  attention?: string;
  state?: string;
}) {
  return apiFetch<StageDataExport>(EXPORTS, { method: "POST", body: JSON.stringify(filters) });
}

export function listStageDataExports() {
  return apiFetch<{ items: StageDataExport[]; keepDays: number }>(EXPORTS, undefined, {
    redirectOn401: false
  });
}

export function downloadStageDataExport(item: StageDataExport) {
  return downloadFile(
    `${EXPORTS}/${encodeURIComponent(item.id)}/download`,
    item.fileName ?? exportFilename("Ministry", "Export", "xlsx", { subject: "Stage Data" })
  );
}

export function exportsInFlight(items: readonly StageDataExport[]): boolean {
  return items.some((item) => item.status === "QUEUED" || item.status === "RUNNING");
}

export function listRegisterDesignWorkshops(params: RegisterQuery) {
  return apiFetch<DesignRegisterPage>(`/ministry-dashboard/design-workshops${registerQuery(params)}`, undefined, {
    redirectOn401: false
  });
}

export function listRegisterOtherWorkshops(params: RegisterQuery) {
  return apiFetch<OtherRegisterPage>(`/ministry-dashboard/workshops${registerQuery(params)}`, undefined, {
    redirectOn401: false
  });
}

export type PeopleKind = "designers" | "officers" | "inspectors";

export const PEOPLE_KINDS: ReadonlyArray<{
  id: PeopleKind;
  title: string;
  note: string;
  countLabel: string;
}> = [
  {
    id: "designers",
    title: "Designers",
    note: "Who runs workshops, and how far along.",
    countLabel: "designer"
  },
  {
    id: "officers",
    title: "Assistant & Regional Directors",
    note: "Who oversees which workshops.",
    countLabel: "officer"
  },
  {
    id: "inspectors",
    title: "Inspectors",
    note: "Inspections, corrections and send-backs.",
    countLabel: "inspector"
  }
];

export type RegisterPerson = {
  id: string;
  name: string | null;
  email: string | null;
  role: string;
  workshops: number;
  registered: number;
  ongoing: number;
  completed: number;
  unclassifiedStanding: number;
  workshopsScored: number;
  percent: number | null;
  stagesComplete: number | null;
  stagesTotal: number | null;
  requiredTotal: number | null;
  requiredFilled: number | null;
  progressReason: string | null;
  workshopsCreated?: number;
  workshopsNamedOn?: number;
  byCapacity?: Record<string, number>;
  unknownCapacity?: number;
  feedbackFiled?: number | null;
  sendBacks?: number | null;
};

export type RegisterPeoplePage = PageResult<RegisterPerson> & {
  scope: string;
  scopeLabel: string;
  standingVocabulary: string;
  standingGroups: Record<string, string[]>;
  scan: { total: number; read: number; truncated: boolean } & Record<string, unknown>;
  progressScoreCap: number;
  progressRead: boolean;
  progressNote: string | null;
  withheldAccounts: number;
  withheldAccountsNote: string | null;
  includesUnpostedAccounts: boolean;
  unpostedAccountsNote: string | null;
  unpostedAccountsTruncated?: boolean;
  rosterRepresentation?: {
    rosterAdmitted: number;
    rosterWithoutAccount: number;
    rosterOtherRole: number;
    rosterReadTruncated: boolean;
  } | null;
  rosterRepresentationNote?: string | null;
  feedbackRead?: boolean;
  feedbackNote?: string | null;
  feedbackFiledTotal?: number | null;
  feedbackAttributed?: number | null;
  feedbackByAccountsNotListed?: number | null;
};

export function listRegisterPeople(kind: PeopleKind, params: RegisterQuery) {
  return apiFetch<RegisterPeoplePage>(`/ministry-dashboard/${kind}${registerQuery(params)}`, undefined, {
    redirectOn401: false
  });
}

export function personProgressSentence(person: RegisterPerson): string {
  if (typeof person.percent === "number") {
    const scored =
      person.workshopsScored === person.workshops
        ? ""
        : ` across ${person.workshopsScored} of ${person.workshops} workshops`;
    return `${person.percent}% complete${scored}`;
  }
  switch (person.progressReason) {
    case "noWorkshops":
      return "No workshops in this scope";
    case "capped":
      return "Not scored — too many on this page";
    case "unreadable":
      return "Not scored — progress couldn't be loaded";
    default:
      return "Not scored";
  }
}

export function fetchRegisterSummary() {
  return apiFetch<RegisterSummary>("/ministry-dashboard/summary", undefined, { redirectOn401: false });
}

export function fetchRegisterEntitlements() {
  return apiFetch<RegisterEntitlements>("/ministry-dashboard/entitlements", undefined, {
    redirectOn401: false
  });
}

export function downloadRegister(kind: RegisterKind, params: RegisterQuery) {
  const query = registerQuery({ search: params.search, standing: params.standing });
  return kind === "design"
    ? downloadFile(`/ministry-dashboard/design-workshops.csv${query}`, exportFilename("Ministry", "Export", "csv", { subject: "Design Prototype Workshops" }))
    : downloadFile(`/ministry-dashboard/workshops.csv${query}`, exportFilename("Ministry", "Export", "csv", { subject: "Other Workshops" }));
}

export function downloadBeneficiaries() {
  return downloadFile("/export/artisans.csv", exportFilename("Artisan", "Export", "csv"));
}

export function progressSentence(progress: RegisterProgress | null | undefined): string {
  if (!progress) return "Progress isn't available for this workshop.";
  if (progress.unscoredReason === "capped") {
    return "Not scored on this page — narrow the list to score it.";
  }
  if (progress.percent === null || progress.stagesComplete === null) {
    return "Progress couldn't be loaded, so it hasn't been scored.";
  }
  const outstanding = (progress.requiredTotal ?? 0) - (progress.requiredFilled ?? 0);
  const stages = `${progress.stagesComplete} of ${progress.stagesTotal} stages complete`;
  const fields =
    outstanding <= 0
      ? "no required fields outstanding"
      : `${outstanding} required ${outstanding === 1 ? "field" : "fields"} outstanding`;
  return `${stages}, ${fields} — ${progress.percent}%.`;
}

export function hasProgressFigure(progress: RegisterProgress | null | undefined): boolean {
  return !!progress && progress.percent !== null;
}

export function standingMembersSentence(
  meta: Pick<RegisterMeta, "standingGroups"> | null,
  standing: string
): string | null {
  if (!standing || !meta) return null;
  const members = meta.standingGroups?.[standing];
  if (!members || members.length === 0) return null;
  const words = members.map(humaniseStanding);
  if (words.length === 1) return `Covers ${words[0]}.`;
  return `Covers ${words.slice(0, -1).join(", ")} and ${words[words.length - 1]}.`;
}

function humaniseStanding(status: string): string {
  const curated: Record<string, string> = {
    DRAFT: "Draft",
    IN_PROGRESS: "In progress",
    COMPLETE: "Complete",
    PRE_SUBMISSION: "Pre-submission",
    NEEDS_REVISION: "Needs revision",
    SUBMITTED: "Submitted",
    APPROVED: "Approved",
    REJECTED: "Rejected",
    ARCHIVED: "Archived"
  };
  if (curated[status]) return curated[status];
  const words = status.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function unclassifiedSentence(count: number): string | null {
  if (count <= 0) return null;
  return count === 1
    ? "1 workshop has an ungrouped status — see “Everything”."
    : `${count} workshops have ungrouped statuses — see “Everything”.`;
}
