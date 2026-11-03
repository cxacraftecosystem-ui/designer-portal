import { rosterLinkParams, rosterQueryParams, type RosterFilters } from "@/components/admin/rosterFilters";
import { apiFetch, buildQuery } from "@/lib/api";
import type { AccessRosterPage } from "@/lib/accessRoster";

export type AccessRosterAnswer = AccessRosterPage & {
  roleMatchTruncated?: boolean;
};

export function listFilteredAccessRoster(params: {
  page: number;
  pageSize: number;
  filters: RosterFilters;
}): Promise<AccessRosterAnswer> {
  return apiFetch<AccessRosterAnswer>(
    `/access/roster${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      ...rosterQueryParams("access", params.filters)
    })}`
  );
}

export function accessRosterHref(filters: RosterFilters): string {
  return `/admin/access${buildQuery(rosterLinkParams("access", filters))}`;
}

export const ACCESS_LIST_UNREADABLE_TITLE = "The list could not be loaded";

export const ACCESS_LIST_UNREADABLE_BODY =
  "Check your connection and try again. Nobody's access has changed.";

export const ACCESS_LIST_STALE_NOTE =
  "This list may be out of date: the last refresh did not finish, so decisions made since then — by " +
  "you or another administrator — may not show yet.";

export const ACCESS_NO_MATCH_TITLE = "Nobody matches these filters";

export const ACCESS_NO_MATCH_BODY =
  "Clear them to see everyone who has been admitted, refused or suspended.";

export const ACCESS_NOBODY_YET_TITLE = "Nobody is on the list yet";

export const ACCESS_NOBODY_YET_BODY =
  "Add the first address above. The master admin can always sign in.";

export const ACCESS_PAST_END_TITLE = "Nothing on this page";

export function accessRoleCutNotice(truncated: boolean | undefined): string {
  if (!truncated) return "";
  return (
    "Some entries are missing from these results. Choose fewer roles to see the complete list, or " +
    "clear the role filter to list everyone."
  );
}
