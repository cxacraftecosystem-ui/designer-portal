import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import { fetchFile } from "@/lib/fileDownload";
import { shareInFlight, startNewReadGeneration } from "@/lib/inFlight";
import { forgetMyWorkshopPosts } from "@/lib/myWorkshopPosts";
import type { DwCustomDefinition } from "@/lib/customSections";
import { designerCreateFields } from "@/lib/designWorkshops";
import type {
  DwDecision,
  DwInspectionFeedbackKeys,
  DwStageCompleteness,
  DwStageData,
  DwSummary
} from "@/lib/designWorkshops";
import type { DwReadiness, DwReviewAidKeys } from "@/lib/reviewAids";
import { listDesignWorkshopInspectors, type DwInspector } from "@/lib/designWorkshopInspections";
import {
  canAssignWorkshopOversight,
  canReadWorkshopOversight,
  isDirectorateTier,
  oversightPostsUserMayHold,
  workshopPostsHeldBy,
  type WorkshopPost
} from "@/lib/permissions";
import type { PageResult, User } from "@/lib/types";
import { exportFilename } from "@/lib/naming";

export const OVERSIGHT_SEARCH_MAX = 120;

export const CAPACITIES = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"] as const;
export type DwOversightCapacity = (typeof CAPACITIES)[number];

export const CAPACITY_LABELS: Record<DwOversightCapacity, string> = {
  ASSISTANT_DIRECTOR: "Assistant Director",
  REGIONAL_DIRECTOR: "Regional Director"
};

export type DwOversightAssignment = {
  capacity: DwOversightCapacity;
  userId: string;
  name: string;
  email: string;
  role: string;
  assignedAt: string | null;
  assignedById: string | null;
};

export type DwNamedDesigner = {
  userId: string;
  name: string;
  email: string;
  role: string;
  isLead: boolean;
};

export type DwWorkshopOversight = {
  workshopId: string;
  title: string | null;
  status: string;
  designerName: string | null;
  designers?: DwNamedDesigner[];
  oversight: DwOversightAssignment[];
};

export type DwOfficer = {
  id: string;
  name: string;
  email: string;
  role: string;
  capacities: DwOversightCapacity[];
};

export type DwOfficerDirectory = { users: DwOfficer[]; truncated: boolean };

export type DwAssignableDesigner = { id: string; name: string; email: string; role: string };

export type DwAssignableDesignerList = { users: DwAssignableDesigner[]; truncated: boolean };

export type ImportProblem = {
  sheet: string;
  row: number | null;
  severity: "error" | "warning";
  reason: string;
  value?: string | null;
};

export type ArtisanImportReport = {
  importId: string;
  sheet: string | null;
  rowsRead: number;
  artisansCreated: number;
  artisansLinked: number;
  rowsRefused: number;
  participantsCreated: number;
  problems: ImportProblem[];
};

export type DwArtisanImport = {
  id: string;
  sourceFilename: string | null;
  sheetName: string | null;
  rowsRead: number;
  artisansCreated: number;
  artisansLinked: number;
  rowsRefused: number;
  participantsCreated: number;
  problems: ImportProblem[];
  uploadedBy: string | null;
  createdAt: string | null;
};

export type DwArtisanImportList = { imports: DwArtisanImport[] };

export type DwOversightDetail = DwSummary &
  DwInspectionFeedbackKeys &
  DwReviewAidKeys & {
  inspectors?: Array<{ userId: string; name: string; email?: string; clearedAt?: string | null }>;
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  customSchemaVersion?: string;
  customSections?: DwCustomDefinition;
  oversight?: DwOversightAssignment[];
  approvedByName?: string | null;
  handedOnByName?: string | null;
  decisions?: DwDecision[];
  readOnly?: boolean;
};

export function listOfficers(search?: string) {
  return apiFetch<DwOfficerDirectory>(
    `/design-workshop-oversight/officers${buildQuery({ search: search || undefined })}`
  );
}

export function listAssignableDesigners(search?: string) {
  return apiFetch<DwAssignableDesignerList>(
    `/design-workshop-oversight/designers${buildQuery({ search: search || undefined })}`
  );
}

export type DwStaffingFilter = "staffed" | "unstaffed";

export function listAssignableWorkshops(params: {
  page?: number;
  pageSize?: number;
  search?: string;
  staffed?: DwStaffingFilter;
}) {
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshop-oversight/workshops${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search || undefined,
      staffed: params.staffed
    })}`
  );
}

export type DwOversightCreateBody = {
  title: string;
  workshopKind?: string;
  designerUserIds?: readonly string[];
  lead?: string;
  craftId?: string;
  craftName?: string;
  clusterName?: string;
  state?: string;
  district?: string;
  startDate?: string;
  endDate?: string;
};

export function createOversightWorkshop(body: DwOversightCreateBody) {
  const { designerUserIds, lead, ...rest } = body;
  return apiFetch<DwSummary>("/design-workshop-oversight/workshops", {
    method: "POST",
    body: JSON.stringify({
      ...rest,
      ...designerCreateFields({ chosen: designerUserIds ?? [], lead: lead ?? "" })
    })
  });
}

export function getWorkshopOversight(workshopId: string) {
  const path = `/design-workshop-oversight/${encodeURIComponent(workshopId)}`;
  return shareInFlight(path, () => apiFetch<DwWorkshopOversight>(path));
}

export function putWorkshopOversight(
  workshopId: string,
  body: { assistantDirectorId?: string | null; regionalDirectorId?: string | null }
) {
  return apiFetch<{ oversight: DwOversightAssignment[] }>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}`,
    { method: "PUT", body: JSON.stringify(body) }
  ).finally(() => {
    startNewReadGeneration();
    forgetMyWorkshopPosts();
  });
}

export type DwWorkshopStaffing = {
  designers?: DwNamedDesigner[];
  oversight: DwOversightAssignment[];
  inspectors: DwInspector[];
};

export async function readWorkshopStaffing(workshopId: string): Promise<DwWorkshopStaffing> {
  const [detail, inspection] = await Promise.all([
    getWorkshopOversight(workshopId),
    listDesignWorkshopInspectors(workshopId)
  ]);
  return {
    designers: detail.designers,
    oversight: detail.oversight ?? [],
    inspectors: inspection.inspectors ?? []
  };
}

export async function readHeldWorkshopPosts(
  workshopId: string,
  user: User | null | undefined
): Promise<WorkshopPost[]> {
  if (!user || !canAssignWorkshopOversight(user)) return [];
  try {
    return workshopPostsHeldBy(user, await readWorkshopStaffing(workshopId));
  } catch {
    return [];
  }
}

export type DwViewerRow = { userId: string; name: string; email: string; role: string };

export type DwDesignerReassignment = {
  designerId: string;
  designerName: string | null;
  stagesWritten: string[];
  removedDesigner: DwViewerRow | null;
  stillHaveAccess: DwViewerRow[];
};

export function putWorkshopDesigner(workshopId: string, designerId: string) {
  return apiFetch<DwDesignerReassignment>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/designer`,
    { method: "PUT", body: JSON.stringify({ designerId }) }
  );
}

export type DwDesignerTeamSaved = {
  designers: DwNamedDesigner[];
  designerName: string | null;
  stagesWritten: string[];
  removedDesigners: DwViewerRow[];
};

export function putWorkshopDesigners(
  workshopId: string,
  body: { userIds: string[]; leadUserId?: string }
) {
  return apiFetch<DwDesignerTeamSaved>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/designers`,
    { method: "PUT", body: JSON.stringify(body) }
  );
}

export type DwRosterArtisan = {
  id: string;
  name: string;
  place: string;
  craftName: string | null;
  status: string;
  createdAt: string | null;
};

export type DwRosterList = { artisans: DwRosterArtisan[]; truncated: boolean; total?: number; nextOffset?: number | null };

export function listWorkshopArtisans(workshopId: string, offset = 0) {
  return apiFetch<DwRosterList>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisans${buildQuery({ offset: offset || undefined })}`
  );
}

export function unlinkWorkshopArtisan(workshopId: string, artisanId: string) {
  return apiFetch<{ unlinked: boolean }>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisans/${encodeURIComponent(
      artisanId
    )}`,
    { method: "DELETE" }
  );
}

export function listArtisanImports(workshopId: string) {
  return apiFetch<DwArtisanImportList>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisan-imports`
  );
}

export function listOverseenWorkshops(params: {
  page?: number;
  pageSize?: number;
  search?: string;
  sort?: string;
}) {
  return apiFetch<PageResult<DwSummary & { readiness?: DwReadiness | null }> & { candidatesTruncated?: boolean }>(
    `/design-workshop-oversight/assigned${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search || undefined,
      sort: params.sort || undefined
    })}`
  );
}

export function getOverseenWorkshop(workshopId: string) {
  return apiFetch<DwOversightDetail>(
    `/design-workshop-oversight/assigned/${encodeURIComponent(workshopId)}`
  );
}

export function oversightIsReadOnly(
  detail: { readOnly?: boolean } | null | undefined
): boolean {
  return detail?.readOnly !== false;
}

export function oversightRefusalMeansNoPosts(error: unknown, user: User | null | undefined): boolean {
  return (
    error instanceof ApiError &&
    error.status === 403 &&
    canReadWorkshopOversight(user) &&
    !isDirectorateTier(user)
  );
}

export function oversightEmptyState(
  searched: boolean,
  user: User | null | undefined
): { title: string; body: string } {
  if (searched) {
    return {
      title: "No workshop you monitor matches that search",
      body: "Only the workshops you have been named on are searched. Clear the search to see them all."
    };
  }
  const posts = oversightPostsUserMayHold(user).map((post) => CAPACITY_LABELS[post]);
  return {
    title: `You do not hold any ${posts.length ? posts.join(" or ") : "Assistant Director or Regional Director"} posts`,
    body: "Workshops you're named on will appear here. An admin names officers on Workshop oversight."
  };
}

export type OversightFile = { blob: Blob; fileName: string };

const fetchWorkbook = fetchFile;

export function downloadArtisanProForma(workshopId?: string | null) {
  return fetchWorkbook(
    `/design-workshop-oversight/artisan-pro-forma${buildQuery({ workshopId: workshopId || undefined })}`,
    exportFilename("Artisan", "Pro-Forma", "xlsx", { dated: false })
  );
}

export async function uploadArtisanList(workshopId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<ArtisanImportReport>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisans/upload`,
    { method: "POST", body }
  );
}

export const ARTISAN_ACCEPT =
  ".xlsx,.xlsm,.xltx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export const MAX_ARTISAN_UPLOAD_BYTES = 4 * 1024 * 1024;

export function artisanUploadRefusal(file: File): string | null {
  const name = file.name.toLowerCase();
  if (!/\.(xlsx|xlsm|xltx)$/.test(name)) {
    return `'${file.name}' is not an Excel workbook. Fill in the artisan list pro-forma and upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'.`;
  }
  if (file.size > MAX_ARTISAN_UPLOAD_BYTES) {
    return "That artisan list is over the 4 MB limit; send a smaller file.";
  }
  if (file.size === 0) {
    return "The upload was empty. Attach the filled-in artisan list pro-forma.";
  }
  return null;
}
