import { ApiError, apiFetch } from "@/lib/api";
import { fetchFile, saveBlobToDisk } from "@/lib/fileDownload";
import type { PageResult } from "@/lib/types";
import { exportFilename } from "@/lib/naming";

export type PlanStanding = "PLANNED" | "PROMOTED" | "WITHDRAWN";

export type PlanProblem = {
  sheet: string | null;
  row: number | null;
  severity: string;
  reason: string;
  value: string | null;
};

export type PlanChange = {
  workshopNo: string;
  sheetRow: number | null;
  field: string;
  fieldLabel: string;
  from: string | null;
  to: string | null;
  reason: string | null;
};

export type PlanAbsentRow = {
  workshopNo: string;
  promoted: boolean;
  reason: string;
};

export type PlanUploadReport = {
  planYear: number;
  planYearLabel: string;
  sheet: string | null;
  sourceFilename: string | null;
  rowsRead: number;
  created: number;
  updated: number;
  unchanged: number;
  reinstated: number;
  withdrawn: number;
  absent: number;
  absentPromoted: number;
  updatedAfterPromotion: number;
  withdrawAbsentRequested: boolean;
  changes: PlanChange[];
  changesTruncated: boolean;
  absentRows: PlanAbsentRow[];
  problems: PlanProblem[];
};

export type AnnualPlanEntry = {
  id: string;
  planYear: number;
  planYearLabel: string;
  workshopNo: string;
  plannedTitle: string | null;
  workshopKind: string | null;
  workshopKindLabel: string | null;
  craftName: string | null;
  craftId?: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  venue: string | null;
  plannedStartDate: string | null;
  plannedEndDate: string | null;
  implementingAgency: string | null;
  sponsor: string | null;
  notes: string | null;
  standing: PlanStanding;
  revision: number;
  sheetRow: number | null;
  sourceFilename: string | null;
  withdrawnAt: string | null;
  designWorkshopId: string | null;
  designWorkshopTitle: string | null;
  promotedAt: string | null;
  sanction?: {
    id: string;
    sanctionOrderNo: string;
    sanctionOrderDate: string | null;
    sanctionAmount: string;
  } | null;
  createdAt: string;
  updatedAt: string;
};

export type AnnualPlanYear = {
  planYear: number;
  planYearLabel: string;
  total: number;
  planned: number;
  promoted: number;
  withdrawn: number;
};

export type AnnualPlanPage = PageResult<AnnualPlanEntry> & { regionalStates?: string[] | null };

export type RegionalDirectorScope = { id: string; name: string; email: string; states: string[] };

export function listPlanYears() {
  return apiFetch<AnnualPlanYear[]>("/annual-plan/years");
}

export function listAnnualPlan(query: string) {
  return apiFetch<AnnualPlanPage>(`/annual-plan${query}`);
}

export function getAnnualPlanEntry(id: string) {
  return apiFetch<AnnualPlanEntry>(`/annual-plan/${id}`);
}

export function updateAnnualPlanNotes(id: string, notes: string | null) {
  return apiFetch<AnnualPlanEntry>(`/annual-plan/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ notes })
  });
}

export function listRegionalDirectors() {
  return apiFetch<{ items: RegionalDirectorScope[] }>("/annual-plan/regional-directors");
}

export function setRegionalDirectorStates(userId: string, states: string[]) {
  return apiFetch<{ id: string; states: string[] }>(
    `/annual-plan/regional-directors/${encodeURIComponent(userId)}`,
    { method: "PUT", body: JSON.stringify({ states }) }
  );
}

export function withdrawAnnualPlanEntry(id: string) {
  return apiFetch<AnnualPlanEntry>(`/annual-plan/${id}/withdraw`, { method: "POST" });
}

export function deleteAnnualPlanEntry(id: string) {
  return apiFetch<void>(`/annual-plan/entries/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export function reinstateAnnualPlanEntry(id: string) {
  return apiFetch<AnnualPlanEntry>(`/annual-plan/${id}/reinstate`, { method: "POST" });
}

export type PromoteResult = {
  entry: AnnualPlanEntry;
  workshop: { id: string; title: string } & Record<string, unknown>;
};

export function promoteAnnualPlanEntry(
  id: string,
  body: { designerUserId?: string | null; designerUserIds?: string[]; craftId?: string }
) {
  return apiFetch<PromoteResult>(`/annual-plan/${id}/promote`, {
    method: "POST",
    body: JSON.stringify({
      designerUserId: body.designerUserId || null,
      designerUserIds: body.designerUserIds ?? [],
      ...(body.craftId ? { craftId: body.craftId } : {})
    })
  });
}

export async function uploadAnnualPlan(
  file: File,
  opts: { planYear?: number; withdrawAbsent?: boolean }
): Promise<PlanUploadReport> {
  const body = new FormData();
  body.append("file", file);
  if (opts.planYear != null) body.append("planYear", String(opts.planYear));
  if (opts.withdrawAbsent) body.append("withdrawAbsent", "true");
  return apiFetch<PlanUploadReport>("/annual-plan/upload", { method: "POST", body });
}

type Workbook = { blob: Blob; fileName: string };

const fetchWorkbook = fetchFile;

export async function downloadPlanProForma(): Promise<void> {
  const { blob, fileName } = await fetchWorkbook("/annual-plan/pro-forma", exportFilename("Annual-Plan", "Pro-Forma", "xlsx", { dated: false }));
  saveBlobToDisk(blob, fileName);
}

export async function downloadPlanExport(query: string, planYearLabel: string): Promise<void> {
  const { blob, fileName } = await fetchWorkbook(
    `/annual-plan/export.xlsx${query}`,
    `annual-plan-${planYearLabel}.xlsx`
  );
  saveBlobToDisk(blob, fileName);
}
