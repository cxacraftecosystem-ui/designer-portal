import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import { isUnreachable } from "@/lib/offline";
import type { UserRole, WorkshopType } from "@/lib/types";

export type DwViewer = {
  userId: string;
  name: string;
  email: string;
  role: UserRole | string;
  grantedAt?: string | null;
};

export type DwViewerList = { viewers: DwViewer[] };

export type DwEligibleViewer = {
  id: string;
  name: string;
  email: string;
  role: UserRole | string;
};

export type DwEligibleViewerList = { users: DwEligibleViewer[]; truncated: boolean };

export const ELIGIBLE_VIEWER_SEARCH_MAX = 120;

export function listDesignWorkshopViewers(workshopId: string) {
  return apiFetch<DwViewerList>(`/design-workshops/${workshopId}/viewers`);
}

export function putDesignWorkshopViewers(workshopId: string, userIds: string[]) {
  return apiFetch<DwViewerList>(`/design-workshops/${workshopId}/viewers`, {
    method: "PUT",
    body: JSON.stringify({ userIds })
  });
}

export function listEligibleDesignWorkshopViewers(search?: string) {
  return apiFetch<DwEligibleViewerList>(
    `/design-workshops/eligible-viewers${buildQuery({ search: search?.trim() ?? "" })}`
  );
}

export function eligibleViewerNotice({
  truncated,
  offered,
  searched
}: {
  truncated: boolean;
  offered: number;
  searched: boolean;
}): string {
  if (truncated && offered === 0) {
    return "Some eligible accounts couldn't be listed right now. Please try again later.";
  }
  if (truncated && !searched) {
    return "Too many accounts to show them all — search a name or email to reach the rest.";
  }
  if (truncated) return "Too many matches to show them all — narrow the search.";
  if (searched && offered === 0) return "No eligible account matches that search.";
  return "";
}

export function absenceProvesIneligible(listComplete: boolean, userId: string, readerId: string | null): boolean {
  return listComplete && userId !== readerId;
}

export function viewerAdministrationMissing(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

export function viewerAdministrationFailure(
  error: unknown,
  fallback: string,
  readerIsAdmin: boolean
): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "Couldn't connect, so nothing was saved and nothing has changed. Check your connection and try again.";
  }
  if (error.status === 403) {
    return readerIsAdmin
      ? `This couldn't be saved. ${error.message}`
      : `This couldn't be saved. ${error.message} Only admins and the master admin can decide who may see a design workshop.`;
  }
  if (error.status === 409 || error.status === 422) {
    return `This couldn't be saved. ${error.message}`;
  }
  if (error.status === 404) {
    return `${error.message} This workshop may have been deleted since the list was loaded — reload the page to see the current list.`;
  }
  return error.message || fallback;
}

export function designWorkshopType(
  summary: Pick<DwSummary, "workshopId">,
  typeByWorkshopId: Map<string, WorkshopType>
): WorkshopType | null {
  if (!summary.workshopId) return null;
  return typeByWorkshopId.get(summary.workshopId) ?? null;
}
