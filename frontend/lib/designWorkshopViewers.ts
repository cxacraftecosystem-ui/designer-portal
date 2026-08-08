/**
 * Who — other than the person who pressed "create" — may open a design & prototype workshop.
 *
 * WHY THIS EXISTS, because it is the whole point of the file.
 *
 * A design workshop is currently visible to exactly ONE account. `load_workshop_or_404` in
 * `backend/app/services/design_workshops.py` reads
 *
 *     if record.createdById != user.id and not admin:
 *         raise HTTPException(404, "Record not found")
 *
 * so a colleague who opens the same workshop is told it does not exist — not that they may not see
 * it, which would at least be actionable, but that there is nothing there. That is the correct
 * refusal for a stranger and the wrong one for the room the workshop is actually run in. A real
 * Design & Prototype Development Workshop is a fortnight of work by TWO designers alongside a
 * master craftsperson and a reviewing officer, all four of whom have to read the same 22 stages;
 * today the second designer cannot open it at all, and there is no handover whatsoever when a
 * designer leaves mid-season — the record simply becomes unreadable to everyone but an admin, with
 * the fortnight's fieldwork inside it.
 *
 * This module is the client half of the fix: an admin names the accounts that may see one
 * workshop, and the server widens `load_workshop_or_404` for exactly those accounts.
 *
 * THREE THINGS ABOUT THIS WIRE WILL TRIP A READER WHO ASSUMES THE REST OF THE REPOSITORY'S
 * CONVENTIONS.
 *
 * 1. **The PUT REPLACES the whole set.** There is no add endpoint and no remove endpoint: removing
 *    somebody is sending the list WITHOUT them. So a caller must always send the complete intended
 *    membership, and a caller that sends only what it just ticked has silently revoked everybody
 *    else. {@link putDesignWorkshopViewers} takes the whole list for that reason and is named for
 *    it; there is deliberately no `addViewer` helper for somebody to reach for by mistake.
 *
 * 2. **The creator is not in this set's gift.** They hold the workshop because they made it — the
 *    `createdById` branch above is a different clause from the viewer lookup — so an empty viewer
 *    list does not mean "nobody can see this", and sending a list without the creator does not
 *    remove them. Any UI over this must say so, because a list that shows every reader EXCEPT the
 *    one everybody knows has access reads as a bug, and an admin who believes this list is the
 *    complete answer will conclude they have locked a designer out when they have not.
 *
 * 3. **Eligibility is a SET, not a rank threshold, and the server owns it.**
 *    `GET /design-workshops/eligible-viewers` returns only accounts that could actually run a
 *    design workshop — `canRunDesignWorkshops` is DESIGNER/ADMIN/MASTER_ADMIN and a DESIGNER whose
 *    roster row is suspended is excluded, because granting them a viewer row would produce a
 *    workshop they still cannot open. The client must NOT re-derive that list from the user
 *    directory: the two would drift, and the drift shows up as an admin granting access that the
 *    next sign-in refuses, with nothing on screen saying why.
 *
 * A 404 FROM THESE ROUTES MEANS THE DEPLOYMENT PREDATES THE FEATURE, and that is a real state
 * rather than a hypothetical: they are being built in parallel with this screen, and this repository
 * ships its two halves separately. `/design-workshops/eligible-viewers` is the honest probe —
 * FastAPI matches it against `/design-workshops/{id}` on a server without the route, which answers
 * 404 "Record not found" — so {@link viewerAdministrationMissing} exists to tell that apart from a
 * workshop that genuinely is not there, and the panel says which rather than rendering a dead form.
 */

import { ApiError, apiFetch } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import type { UserRole, WorkshopType } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The wire
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One account that may see a workshop it did not create, as `GET /design-workshops/{id}/viewers`
 * returns it.
 *
 * `role` and `name` travel WITH the row rather than being joined against a directory this screen
 * also holds, and that is worth keeping: a viewer whose account has since dropped out of the
 * eligible list — a designer suspended last month — still has to be nameable here, or the one row
 * an admin most needs to see is the one that renders as a bare cuid.
 */
export type DwViewer = {
  userId: string;
  name: string;
  email: string;
  role: UserRole | string;
  /** When the row was written. Null-tolerant: an older row may carry no timestamp. */
  grantedAt?: string | null;
};

export type DwViewerList = { viewers: DwViewer[] };

/**
 * One account `GET /design-workshops/eligible-viewers` offers — see point 3 in the file header.
 *
 * Deliberately NOT `User`. The endpoint returns the four fields needed to name somebody in a
 * picker and nothing else, because the caller is choosing a reader and has no business receiving
 * the capability flags, the auth provider or anything else `User` carries.
 */
export type DwEligibleViewer = {
  id: string;
  name: string;
  email: string;
  role: UserRole | string;
};

export type DwEligibleViewerList = { users: DwEligibleViewer[] };

/** Everyone with a viewer row on this workshop. Admin and master admin only, server-side. */
export function listDesignWorkshopViewers(workshopId: string) {
  return apiFetch<DwViewerList>(`/design-workshops/${workshopId}/viewers`);
}

/**
 * REPLACE the viewer set with exactly `userIds`, and answer with the set as it now stands.
 *
 * Named `put…` and typed to take the whole list because that is what the endpoint means — see
 * point 1 in the file header. The answer is adopted as the new baseline rather than assumed to
 * equal what was sent: the server may keep a row this client did not know about (a creator row, an
 * account added by another admin between this page loading and Save being pressed), and a client
 * that assumed its own payload was the truth would show a membership nobody has.
 */
export function putDesignWorkshopViewers(workshopId: string, userIds: string[]) {
  return apiFetch<DwViewerList>(`/design-workshops/${workshopId}/viewers`, {
    method: "PUT",
    body: JSON.stringify({ userIds })
  });
}

/** The accounts that may be given a viewer row at all. Admin and master admin only, server-side. */
export function listEligibleDesignWorkshopViewers() {
  return apiFetch<DwEligibleViewerList>("/design-workshops/eligible-viewers");
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading a failure honestly
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Is this failure "the server has no such route" rather than "no such workshop"?
 *
 * Only ever asked of {@link listEligibleDesignWorkshopViewers}, and only that call, because it is
 * the one request in the family that carries no id: a 404 from it cannot mean a missing record and
 * therefore means a missing ROUTE. Asking the same question of `/design-workshops/{id}/viewers`
 * would be unanswerable — a 404 there is genuinely either — which is exactly why the probe is
 * pinned to the id-less endpoint instead of being a general helper.
 */
export function viewerAdministrationMissing(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The type filter
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The `WorkshopType` a design workshop inherits, or null when it inherits none.
 *
 * A `DesignWorkshop` carries no type of its own. `WorkshopType` lives on the `Workshop` row a
 * design workshop was started FROM (`DwSummary.workshopId`), which is also the only place the
 * distinction has ever been recorded — see the type's own note in lib/types.ts. So the type of a
 * 22-stage record is the type of the workshop behind it, and there is no third answer to invent.
 *
 * **Null is a real answer and must be shown, never filtered away in silence.** `workshopId` is
 * optional on create — only the title is required to open a design workshop — so a workshop begun
 * in a courtyard on day one legitimately carries no link and therefore no type. A type filter that
 * quietly dropped those rows would show an admin an empty selector over a repository full of
 * workshops, which is this codebase's single most repeated bug class. The caller is expected to
 * COUNT what a filter excludes for this reason and say so on screen.
 */
export function designWorkshopType(
  summary: Pick<DwSummary, "workshopId">,
  typeByWorkshopId: Map<string, WorkshopType>
): WorkshopType | null {
  if (!summary.workshopId) return null;
  return typeByWorkshopId.get(summary.workshopId) ?? null;
}
