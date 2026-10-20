/**
 * The coverage routes, typed. Every call goes through `apiFetch`; nothing here reaches a map tile
 * server or any third party — the coverage view draws only bundled geometry.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import { coverageParams } from "@/components/map/coverage/coverageMath";
import type {
  CoverageBucket,
  CoverageQuery,
  CoverageResponse,
  UnitRecordsResponse,
  UnmatchedResponse
} from "@/components/map/coverage/coverageTypes";

export function fetchCoverage(query: CoverageQuery, signal?: AbortSignal) {
  return apiFetch<CoverageResponse>(`/map/coverage${buildQuery(coverageParams(query))}`, { signal });
}

export function fetchUnitRecords(
  stateLgd: number,
  districtLgd: number | null,
  query: CoverageQuery,
  options: { bucket?: CoverageBucket; cursor?: string | null; signal?: AbortSignal } = {}
) {
  const params = {
    ...coverageParams({ ...query, stateLgd: null, peer: false }),
    bucket: options.bucket,
    cursor: options.cursor ?? undefined
  };
  return apiFetch<UnitRecordsResponse>(
    `/map/coverage/units/${stateLgd}/${districtLgd ?? 0}/records${buildQuery(params)}`,
    { signal: options.signal }
  );
}

export function fetchUnmatched(signal?: AbortSignal) {
  return apiFetch<UnmatchedResponse>("/map/coverage/unmatched", { signal });
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

/** Designer portal only: the ministry entitlement that opens the Coverage view. */
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

/** Designer portal only: whether the Field Repository's figures can be shown beside ours. */
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
