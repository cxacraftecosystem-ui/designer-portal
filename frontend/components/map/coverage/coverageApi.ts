import { apiFetch } from "@/lib/api";

import type {
  CoverageLevel,
  CoverageResponse,
  UnitRecordsResponse,
  UnmatchedResponse
} from "@/components/map/coverage/coverageTypes";

export type CoverageQuery = {
  level: CoverageLevel;
  craftCode: string[];
  scheme: string[];
  fy: number[];
  stateLgd: number | null;
  peer: boolean;
};

/** Repeated parameters, which `buildQuery` cannot express; the API accepts both forms. */
export function coverageSearch(query: Partial<CoverageQuery>, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams();
  if (query.level) params.set("level", query.level);
  for (const code of query.craftCode ?? []) params.append("craftCode", code);
  for (const scheme of query.scheme ?? []) params.append("scheme", scheme);
  for (const year of query.fy ?? []) params.append("fy", String(year));
  if (query.stateLgd) params.set("stateLgd", String(query.stateLgd));
  if (query.peer) params.set("peer", "1");
  for (const [key, value] of Object.entries(extra)) params.set(key, value);
  const text = params.toString();
  return text ? `?${text}` : "";
}

export function fetchCoverage(query: CoverageQuery, signal?: AbortSignal) {
  return apiFetch<CoverageResponse>(`/map/coverage${coverageSearch(query)}`, { signal });
}

export function fetchUnitRecords(
  query: CoverageQuery,
  stateLgd: number,
  districtLgd: number,
  extra: { bucket?: string; cursor?: string } = {},
  signal?: AbortSignal
) {
  const more: Record<string, string> = {};
  if (extra.bucket) more.bucket = extra.bucket;
  if (extra.cursor) more.cursor = extra.cursor;
  const search = coverageSearch({ ...query, stateLgd: null, peer: false }, more);
  return apiFetch<UnitRecordsResponse>(
    `/map/coverage/units/${stateLgd}/${districtLgd}/records${search}`,
    { signal }
  );
}

export function fetchUnmatched() {
  return apiFetch<UnmatchedResponse>("/map/coverage/unmatched");
}

export function createAlias(body: {
  rawState: string | null;
  rawDistrict: string | null;
  stateLgd: number;
  districtLgd: number | null;
}) {
  return apiFetch<{ id: string }>("/map/coverage/aliases", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export async function coverageAllowed(): Promise<boolean> {
  try {
    const answer = await apiFetch<{ coverageMap?: boolean }>("/ministry-dashboard/entitlements", {}, {
      redirectOn401: false
    });
    return Boolean(answer?.coverageMap);
  } catch {
    return false;
  }
}

export async function peerCoverageEnabled(): Promise<boolean> {
  try {
    const answer = await apiFetch<{ coverageEnabled?: boolean }>("/cross-portal/status", {}, {
      redirectOn401: false
    });
    return Boolean(answer?.coverageEnabled);
  } catch {
    return false;
  }
}
