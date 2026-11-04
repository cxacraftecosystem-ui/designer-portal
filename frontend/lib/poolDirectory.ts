import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult } from "@/lib/types";

export type PoolWorkshop = {
  workshopId: string;
  title: string;
  startDate: string | null;
  endDate: string | null;
  openCounts: Partial<Record<"prototype" | "sketch", number>>;
};

export function listPoolWorkshops(params: { page?: number; pageSize?: number; search?: string | null }) {
  return apiFetch<PageResult<PoolWorkshop>>(
    `/design-ratings/workshops${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search && params.search.trim() ? params.search.trim() : undefined
    })}`
  );
}

export function poolCountsLabel(counts: PoolWorkshop["openCounts"]): string {
  const prototypes = counts.prototype ?? 0;
  const sketches = counts.sketch ?? 0;
  const parts: string[] = [];
  if (prototypes) parts.push(`${prototypes} prototype${prototypes === 1 ? "" : "s"}`);
  if (sketches) parts.push(`${sketches} sketch${sketches === 1 ? "" : "es"}`);
  return parts.length ? `${parts.join(" and ")} open` : "Nothing open";
}

export function poolFirstKind(counts: PoolWorkshop["openCounts"]): "prototype" | "sketch" {
  return (counts.prototype ?? 0) > 0 || (counts.sketch ?? 0) === 0 ? "prototype" : "sketch";
}
