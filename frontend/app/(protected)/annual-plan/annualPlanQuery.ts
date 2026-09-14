/**
 * EVERY QUERY PARAMETER THE DIRECTORY SENDS, BUILT IN ONE PLACE.
 *
 * Beside the page, exactly as `app/(protected)/admin/designers/rosterQuery.ts` is — and for the one
 * reason that matters here: the LIST and the EXPORT must send the same filters. An export that
 * filtered differently would hand an administrator a "corrected" sheet missing rows they were
 * looking at, and if they then ticked "withdraw the workshops missing from this sheet", the upload
 * would withdraw exactly those rows. One builder, two callers, no way to disagree.
 *
 * `buildQuery` DROPS `""` EXACTLY AS IT DROPS null/undefined, so an empty search box, an unchosen
 * state and an unchosen district all simply do not travel. That is why `standing` carries the
 * literal word `"all"` rather than an empty string: "every standing" has to be SAYABLE, and a
 * parameter that cannot be sent cannot be told apart from one nobody set.
 *
 * NO CLIENT-SIDE FILTERING OR SORTING ANYWHERE. The rule `app/(protected)/admin/designers/page.tsx`
 * states: a table that sorts the fifty rows it happens to be holding is a table that lies about
 * which fifty they are, and the lie is invisible because the column header looks like it worked.
 */

import { buildQuery } from "@/lib/api";

/** The four orderings the server offers. A fifth here without one there silently falls back. */
export const PLAN_SORTS = ["plannedStartDate", "workshopNo", "district", "updatedAt"] as const;
export type PlanSort = (typeof PLAN_SORTS)[number];

/** The four answers to "which rows", `"all"` included — see the header for why it is a word. */
export const PLAN_STANDINGS = ["all", "planned", "promoted", "withdrawn"] as const;
export type PlanStandingFilter = (typeof PLAN_STANDINGS)[number];

export const PLAN_SORT_LABELS: Record<PlanSort, string> = {
  plannedStartDate: "Date",
  workshopNo: "Workshop No.",
  district: "District",
  updatedAt: "Last changed"
};

export const PLAN_STANDING_LABELS: Record<PlanStandingFilter, string> = {
  all: "Every row",
  planned: "Planned only",
  promoted: "Already opened",
  withdrawn: "Withdrawn"
};

export type AnnualPlanQuery = {
  planYear: number;
  page?: number;
  pageSize?: number;
  search?: string;
  state?: string;
  district?: string;
  standing?: PlanStandingFilter;
  sort?: PlanSort;
  dir?: "asc" | "desc";
};

/**
 * ⚠ THE SERVER CLAMPS `pageSize` AT 100 AND DECLARES 100, so this default is a real number and not
 * a hopeful one. `GET /designers/roster` declares 200 and is clamped to 100 one function down, which
 * is a latent lie this feature deliberately did not copy.
 */
export const PLAN_PAGE_SIZE = 50;

/** The list's query string, `?`-prefixed, ready to append to `/annual-plan`. */
export function annualPlanQuery(params: AnnualPlanQuery): string {
  return buildQuery({
    planYear: params.planYear,
    page: params.page ?? 1,
    pageSize: params.pageSize ?? PLAN_PAGE_SIZE,
    search: params.search?.trim() || undefined,
    state: params.state || undefined,
    district: params.district || undefined,
    standing: params.standing ?? "all",
    sort: params.sort ?? "plannedStartDate",
    dir: params.dir ?? "asc"
  });
}

/**
 * The EXPORT's query string: the same filters, and deliberately no paging.
 *
 * `page`/`pageSize` are omitted rather than set high. The export is capped server-side at
 * `MAX_PLAN_ROWS`, which is the same ceiling the parser enforces on the way in, so the round trip
 * is closed: anything this can download is something that can be uploaded back.
 */
export function annualPlanExportQuery(params: AnnualPlanQuery): string {
  return buildQuery({
    planYear: params.planYear,
    search: params.search?.trim() || undefined,
    state: params.state || undefined,
    district: params.district || undefined,
    standing: params.standing ?? "all",
    sort: params.sort ?? "plannedStartDate",
    dir: params.dir ?? "asc"
  });
}
