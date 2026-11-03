import { apiFetch, buildQuery } from "@/lib/api";
import type { RosterQueryParams } from "@/components/admin/rosterFilters";
import type { DesignerRosterEntry } from "@/lib/designers";
import type { PageResult } from "@/lib/types";

export type DesignerRosterPage = PageResult<DesignerRosterEntry> & {
  roleMatchTruncated?: boolean;
};

export type DesignerRosterPageParams = RosterQueryParams & {
  page: number;
  pageSize: number;
};

export function listDesignerRosterPage(params: DesignerRosterPageParams) {
  return apiFetch<DesignerRosterPage>(`/designers/roster${buildQuery({ ...params })}`);
}

export type RosterInstitutions = {
  items: string[];
  truncated: boolean | undefined;
};

export async function listDesignerRosterInstitutions(): Promise<RosterInstitutions> {
  const payload = await apiFetch<{ items?: unknown; truncated?: unknown }>(
    "/designers/roster/institutions"
  );
  const items: string[] = [];
  if (Array.isArray(payload?.items)) {
    for (const name of payload.items) {
      if (typeof name !== "string") continue;
      const trimmed = name.trim();
      if (trimmed) items.push(trimmed);
    }
  }
  return {
    items,
    truncated: typeof payload?.truncated === "boolean" ? payload.truncated : undefined
  };
}

const DERIVED_EMPANELMENT_OPENING =
  "Empanelled automatically because this address is admitted on the platform allow-list";

export function isDerivedEmpanelment(entry: Pick<DesignerRosterEntry, "notes">): boolean {
  return (entry.notes ?? "").trimStart().startsWith(DERIVED_EMPANELMENT_OPENING);
}
