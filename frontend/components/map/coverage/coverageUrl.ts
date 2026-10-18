/** The coverage view's filters in the URL, both directions. Pure, so the unit spec holds the round trip. */

import type { CoverageFilterState } from "@/components/map/coverage/CoverageFilters";
import { METRICS, type Metric } from "@/components/map/coverage/coverageTypes";

/** The filter state a URL describes. Pure, so the unit spec can hold the round trip. */
export function filtersFromParams(params: URLSearchParams): CoverageFilterState {
  const many = (key: string) =>
    params
      .getAll(key)
      .flatMap((value) => value.split(","))
      .map((value) => value.trim())
      .filter(Boolean);
  const metric = params.get("metric") as Metric | null;
  const state = Number(params.get("stateLgd"));
  return {
    craftCode: many("craftCode"),
    scheme: many("scheme"),
    fy: many("fy").map(Number).filter((year) => Number.isInteger(year)),
    stateLgd: Number.isInteger(state) && state > 0 ? state : null,
    level: params.get("level") === "STATE" ? "STATE" : "DISTRICT",
    metric: metric && (METRICS as readonly string[]).includes(metric) ? metric : "workshops",
    peer: params.get("peer") === "1"
  };
}

/** The URL for a filter state, keeping `view=coverage` and dropping defaults. */
export function paramsFromFilters(filters: CoverageFilterState): URLSearchParams {
  const params = new URLSearchParams({ view: "coverage" });
  for (const code of filters.craftCode) params.append("craftCode", code);
  for (const scheme of filters.scheme) params.append("scheme", scheme);
  for (const year of filters.fy) params.append("fy", String(year));
  if (filters.stateLgd) params.set("stateLgd", String(filters.stateLgd));
  if (filters.level !== "DISTRICT") params.set("level", filters.level);
  if (filters.metric !== "workshops") params.set("metric", filters.metric);
  if (filters.peer) params.set("peer", "1");
  return params;
}
