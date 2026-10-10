/**
 * THE POOL DIRECTORY — the workshops that have opened a sketch or a prototype to the pool.
 *
 * Sweep item F8 (2026-10-10). `GET /design-ratings/workshops` lists every workshop holding at least
 * one piece whose `peerRoundClosedAt` is set, for the people who review the pool — exactly the
 * accounts `/design-review` admits. Each row is the title, the dates and how many pieces of each kind
 * are open, and nothing else about the workshop; every row listed is a POOL round that answers.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult } from "@/lib/types";

export type PoolWorkshop = {
  workshopId: string;
  title: string;
  startDate: string | null;
  endDate: string | null;
  /** Open pieces per rateable kind — always both keys, zero included. */
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

/** "2 prototypes and 1 sketch open" — the kinds with nothing open are left out. */
export function poolCountsLabel(counts: PoolWorkshop["openCounts"]): string {
  const prototypes = counts.prototype ?? 0;
  const sketches = counts.sketch ?? 0;
  const parts: string[] = [];
  if (prototypes) parts.push(`${prototypes} prototype${prototypes === 1 ? "" : "s"}`);
  if (sketches) parts.push(`${sketches} sketch${sketches === 1 ? "" : "es"}`);
  return parts.length ? `${parts.join(" and ")} open` : "Nothing open";
}

/** Which kind to show first when a row is opened: prototypes when any are open, else sketches. */
export function poolFirstKind(counts: PoolWorkshop["openCounts"]): "prototype" | "sketch" {
  return (counts.prototype ?? 0) > 0 || (counts.sketch ?? 0) === 0 ? "prototype" : "sketch";
}
