import { buildQuery } from "@/lib/api";

export const PLAN_SORTS = ["plannedStartDate", "workshopNo", "district", "updatedAt"] as const;
export type PlanSort = (typeof PLAN_SORTS)[number];

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

export const PLAN_PAGE_SIZE = 50;

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
