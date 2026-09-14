/**
 * THE ANNUAL PLAN'S WIRE CONTRACT — every type and every call, in one file beside its one page.
 *
 * ── WHY THIS IS NOT IN `lib/annualPlan.ts`, WHICH IS WHERE IT BELONGS ───────────────────────────
 *
 * It belongs there and it should end up there, beside `lib/questionnaireForms.ts` and
 * `lib/sanctionOrders.ts`, which are the same shape. It is here because this parcel's territory is
 * `app/(protected)/annual-plan/**` and `frontend/lib/` is being read and written by other work in
 * the same wave; a shared directory edited from two places in one afternoon is how two correct
 * changes produce one broken tree. The colocation is exactly the pattern
 * `app/(protected)/admin/designers/rosterQuery.ts` records at length, and the App Router treats a
 * non-`page`/`route` module in a route folder as an ordinary import.
 *
 * **When it moves, it is a move and not a rewrite.**
 *
 * ── NO CODEGEN. EVERY FIELD HERE IS A HAND EDIT AND SO IS ITS TWIN ──────────────────────────────
 *
 * There is no API codegen in this repository. A field added to `entry_payload` or to the upload
 * report on the server is invisible here until somebody types it, and `apiFetch` CASTS rather than
 * validating — so a missing key is `undefined` at the point of use and not an error at the point of
 * arrival. The counts in particular are therefore held as DATA on the report panel (see
 * `PlanUploadReport`), so that a count added to the wire cannot be silently left off the screen.
 *
 * There is deliberately NO Kotlin twin: Android calls none of these ten routes, so a
 * `@Serializable` class there would be dead code the next reader has to prove is dead. See
 * `backend/app/services/annual_plan.py`'s module docstring for that decision in full.
 */

import { API_BASE, ApiError, apiFetch, assertApiConfigured, describeApiDetail, getToken } from "@/lib/api";
// BOTH IMPORTED, NEITHER RE-IMPLEMENTED. `lib/aiVerbs.ts` imports the first one exactly this way,
// and `saveBlobToDisk`'s own docstring records why the anchor dance must not be written again:
// revoking the object URL in the same tick as the synthetic click races the browser's read of it,
// and Safari downloads nothing at all with no error anywhere.
import { fileNameFromDisposition, saveBlobToDisk } from "@/lib/designWorkshops";
import type { PageResult } from "@/lib/types";

/** PLANNED / PROMOTED / WITHDRAWN — derived on the server from two columns, never stored. */
export type PlanStanding = "PLANNED" | "PROMOTED" | "WITHDRAWN";

/**
 * One row the parser could not read cleanly. Identical in shape to `QFormProblem`, because it IS
 * the same `ParseProblem` dataclass, shared from `backend/app/services/xlsx_table.py`.
 *
 * `severity` is `"error"` when nothing was stored for that row and `"warning"` when it was stored
 * but something had to be assumed — with ONE documented exception on the server: an unreadable DATE
 * is an `"error"` about the CELL and the row is still imported. The panel renders the two severities
 * under two different headings and never collapses them.
 */
export type PlanProblem = {
  sheet: string | null;
  row: number | null;
  severity: string;
  reason: string;
  value: string | null;
};

/**
 * One field one upload changed.
 *
 * `reason` IS WRITTEN ON THE SERVER TO BE SHOWN VERBATIM. It is the only place an administrator is
 * told that correcting a plan row did NOT correct the workshop already opened from it. Do not
 * paraphrase it, do not shorten it, and do not replace it with an icon — this is the fourth place
 * in the stack that could restate the rule and the one where restating it would cost a ministry
 * administrator their understanding of who owns what.
 */
export type PlanChange = {
  workshopNo: string;
  sheetRow: number | null;
  field: string;
  fieldLabel: string;
  from: string | null;
  to: string | null;
  reason: string | null;
};

/** One row the uploaded sheet did not mention, and what was done about it. */
export type PlanAbsentRow = {
  workshopNo: string;
  promoted: boolean;
  reason: string;
};

/**
 * What one upload did. EVERY KEY IS REQUIRED — none is optional, because the panel holds the counts
 * as data and iterates them: an optional count is a count that renders as nothing at all when the
 * server starts sending it and nobody notices.
 */
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

/** One directory row, exactly as `annual_plan.entry_payload` emits it. */
export type AnnualPlanEntry = {
  id: string;
  planYear: number;
  planYearLabel: string;
  workshopNo: string;
  plannedTitle: string | null;
  workshopKind: string | null;
  workshopKindLabel: string | null;
  craftName: string | null;
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
  createdAt: string;
  updatedAt: string;
};

/** One year the directory holds, with its standing counted. */
export type AnnualPlanYear = {
  planYear: number;
  planYearLabel: string;
  total: number;
  planned: number;
  promoted: number;
  withdrawn: number;
};

export type AnnualPlanPage = PageResult<AnnualPlanEntry>;

// --------------------------------------------------------------------------------------
// Reads
// --------------------------------------------------------------------------------------

export function listPlanYears() {
  return apiFetch<AnnualPlanYear[]>("/annual-plan/years");
}

export function listAnnualPlan(query: string) {
  return apiFetch<AnnualPlanPage>(`/annual-plan${query}`);
}

export function getAnnualPlanEntry(id: string) {
  return apiFetch<AnnualPlanEntry>(`/annual-plan/${id}`);
}

// --------------------------------------------------------------------------------------
// Writes
// --------------------------------------------------------------------------------------

export function updateAnnualPlanNotes(id: string, notes: string | null) {
  return apiFetch<AnnualPlanEntry>(`/annual-plan/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ notes })
  });
}

export function withdrawAnnualPlanEntry(id: string) {
  return apiFetch<AnnualPlanEntry>(`/annual-plan/${id}/withdraw`, { method: "POST" });
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
  body: { designerUserId?: string | null; designerUserIds?: string[] }
) {
  return apiFetch<PromoteResult>(`/annual-plan/${id}/promote`, {
    method: "POST",
    body: JSON.stringify({
      designerUserId: body.designerUserId || null,
      designerUserIds: body.designerUserIds ?? []
    })
  });
}

/**
 * Upload — or re-upload a corrected copy of — one year's directory.
 *
 * EVERY SCALAR IS APPENDED ONLY WHEN TRULY MEANT, and here that is load-bearing rather than
 * fastidious. An appended empty string arrives at the server as `""` rather than as absence, and
 * `_plan_year_or_422` would then have to decide what an empty year means — while an appended
 * `withdrawAbsent: "false"` is harmless only because `_flag` treats everything but an explicit yes
 * as false. The same care `uploadQuestionnaire` takes with its own FormData.
 *
 * `apiFetch` leaves `Content-Type` unset for a `FormData` body so the browser writes the multipart
 * boundary itself. No header work is needed here and adding one breaks the upload.
 */
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

// --------------------------------------------------------------------------------------
// Downloads
// --------------------------------------------------------------------------------------

type Workbook = { blob: Blob; fileName: string };

/**
 * A Bearer fetch that answers a blob and the name the server gave it.
 *
 * The shape is `questionnaireForms.fetchWorkbook`'s, deliberately: `apiFetch` parses JSON, so an
 * .xlsx has to be fetched by hand, and the error branch has to rebuild the `ApiError` that
 * `apiFetch` would otherwise have thrown — otherwise a 403 on the export reaches the screen as a
 * blank box. `statusText` is empty over HTTP/2, which every deployed request is, so it can never be
 * the last resort on its own.
 */
async function fetchWorkbook(path: string, fallbackName: string): Promise<Workbook> {
  assertApiConfigured();

  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api${path}`, { headers, cache: "no-store" });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload
        ? (payload as { detail: unknown }).detail
        : undefined;
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || `The server refused the request (HTTP ${response.status}).`),
      payload
    );
  }
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

/** The blank pro-forma a ministry's directory is typed into. */
export async function downloadPlanProForma(): Promise<void> {
  const { blob, fileName } = await fetchWorkbook("/annual-plan/pro-forma", "annual-plan-pro-forma.xlsx");
  saveBlobToDisk(blob, fileName);
}

/**
 * The directory as it stands, under the filters currently on screen.
 *
 * THE SAME FILTERS AS THE LIST, on purpose: an administrator exporting what they are looking at must
 * get what they are looking at. The server applies them through the same function the list uses.
 */
export async function downloadPlanExport(query: string, planYearLabel: string): Promise<void> {
  const { blob, fileName } = await fetchWorkbook(
    `/annual-plan/export.xlsx${query}`,
    `annual-plan-${planYearLabel}.xlsx`
  );
  saveBlobToDisk(blob, fileName);
}
