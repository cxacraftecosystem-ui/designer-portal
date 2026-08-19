/**
 * Who — other than the person who pressed "create" — may open a design & prototype workshop.
 *
 * WHY THIS EXISTS, because it is the whole point of the file.
 *
 * A design workshop USED TO BE visible to exactly ONE account: `load_workshop_or_404` in
 * `backend/app/services/design_workshops.py` refused anybody but the creator and an admin, so a
 * colleague who opened the same workshop was told it did not exist — not that they may not see it,
 * which would at least be actionable, but that there was nothing there. That is the correct refusal
 * for a stranger and the wrong one for the room the workshop is actually run in. A real Design &
 * Prototype Development Workshop is a fortnight of work by TWO designers alongside a master
 * craftsperson and a reviewing officer, all four of whom have to read the same 22 stages; the
 * second designer could not open it at all, and there was no handover whatsoever when a designer
 * left mid-season — the record simply became unreadable to everyone but an admin, with the
 * fortnight's fieldwork inside it.
 *
 * THAT IS NOW CLOSED ON BOTH HALVES, and the `if record.createdById != user.id and not admin:` this
 * paragraph used to quote no longer exists anywhere in the tree. The live condition also consults
 * `has_viewer_grant(workshop_id, user.id)`, and its own docstring calls that "THREE WAYS IN, not
 * two": the creator, an admin, and anybody an admin has granted a `DesignWorkshopViewer` row.
 *
 * This module is the client half — an admin names the accounts that may see one workshop, and the
 * server admits exactly those accounts. What a grant buys stops at the LOAD: it is read, and the
 * stage writes that go through the same helper. It is not delete and it is not re-granting, both
 * of which are gated separately in `app/services/design_workshop_viewers.py`.
 *
 * FOUR THINGS ABOUT THIS WIRE WILL TRIP A READER WHO ASSUMES THE REST OF THE REPOSITORY'S
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
 * 4. **THE ELIGIBLE LIST IS CAPPED, THE CAP IS REACHED, AND ONLY THE SERVER CAN SEE PAST IT.**
 *    `eligible-viewers` answers at most `ELIGIBLE_VIEWER_LIMIT` (2000) accounts ordered by name, and
 *    that ceiling is not theoretical — measured on this repository, the eligible set is 1344 admins
 *    plus the 1282 designers the roster admits, so the cut lands in the middle of the alphabet.
 *    Every eligible account sorting past it was simply ABSENT from both pickers, and an admin
 *    hunting for a colleague could not tell that from the colleague never having been empanelled.
 *    Those two states must never look identical.
 *
 *    So the endpoint takes `search` and the answer carries `truncated`, and both halves are
 *    obligations on the caller. **The search must be the SERVER'S** — see
 *    {@link listEligibleDesignWorkshopViewers}. A caller that filtered the capped list in the
 *    browser would search only the part of the alphabet that fitted: the same defect wearing a
 *    search box, and harder to see, because an empty result reads as "no such person". And a
 *    `truncated: true` answer must SAY SO on screen, in one sentence, while a `false` one says
 *    nothing at all — a list that quietly stops is indistinguishable from a repository with nobody
 *    in it, which is this codebase's most repeated bug class.
 *
 * A 404 FROM THESE ROUTES MEANS THE DEPLOYMENT PREDATES THE FEATURE, and that is a real state
 * rather than a hypothetical. The routes are IN THE TREE — that half is no longer pending — but this
 * repository ships the browser bundle and the API separately, so a deployment that has not been
 * rolled forward answers exactly this and the screen must be able to tell it apart.
 * `/design-workshops/eligible-viewers` is the honest probe — FastAPI matches it against
 * `/design-workshops/{id}` on a server without the route, which answers 404 "Record not found" — so
 * {@link viewerAdministrationMissing} exists to tell that apart from a workshop that genuinely is
 * not there, and the panel says which rather than rendering a dead form.
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";
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

/**
 * One page of eligible accounts, and whether it is the whole answer.
 *
 * `truncated` is the server's own word — the reference picker in `services/design_workshops` reports
 * its cap under the same name, deliberately, so both clients already know it. It is true when EITHER
 * the account list was cut at the ceiling OR the active-roster read was cut (which does not shorten
 * a list, it removes an eligible designer from it entirely, so it counts as cut). There is no total
 * count on purpose: producing one would cost a second full ILIKE scan to learn one boolean.
 *
 * Typed as required because the endpoint always sends it. `apiFetch` is a plain cast rather than a
 * schema parse, so a deployment that predates the field puts `undefined` here at runtime — hence the
 * `Boolean(...)` at the one place that reads it. That is the same split-deploy reality
 * {@link viewerAdministrationMissing} exists for, and the safe default is the quiet one: an unknown
 * flag says nothing on screen rather than crying truncation at a list that is complete.
 */
export type DwEligibleViewerList = { users: DwEligibleViewer[]; truncated: boolean };

/**
 * Longest `search` the endpoint accepts (`Query(None, max_length=120)`); past it the answer is a 422.
 *
 * Exported so the box the admin types into can cap itself at the server's own number, which makes
 * the refusal unreachable rather than handled. A picker that answers a long paste with a red
 * validation banner has taught the admin nothing they can act on.
 */
export const ELIGIBLE_VIEWER_SEARCH_MAX = 120;

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

/**
 * The accounts that may be given a viewer row at all. Admin and master admin only, server-side.
 *
 * `search` matches `name` OR `email`, case-insensitively, AND IS THE SERVER'S SEARCH — see point 4 in
 * the file header for why that distinction is the whole feature and not a preference. It is folded
 * into the same `WHERE` as the eligibility rule, so it reaches accounts past the 2000-row ceiling;
 * narrowing the array this function returns would not, because that array is what the ceiling already
 * cut. Omitted, blank and whitespace-only are one case — `buildQuery` drops an empty string exactly as
 * it drops `undefined`, so the parameter simply is not sent and the answer is the whole (capped) list.
 *
 * The caller is expected to DEBOUNCE this. An `ILIKE '%term%'` over `User` is a scan no index can
 * answer — the service says so itself — so every keystroke that escapes a debounce is a full scan of
 * the largest table in the database.
 *
 * **THE ORDER IS THE SERVER'S AND MUST NOT BE RE-SORTED HERE.** It is `name` ascending and then `id`,
 * a TOTAL order — the id is not decoration: 137 accounts on this database share the display name the
 * 2000-row cut currently lands on, so without a tiebreaker which of them fell inside the ceiling was
 * Postgres's arbitrary choice and could differ between two identical requests. Re-sorting in the
 * browser would also disagree with Postgres's collation on exactly the names this repository is full
 * of, and would then disagree with the handset, which declines to re-sort for the same reason.
 */
export function listEligibleDesignWorkshopViewers(search?: string) {
  return apiFetch<DwEligibleViewerList>(
    `/design-workshops/eligible-viewers${buildQuery({ search: search?.trim() ?? "" })}`
  );
}

/**
 * THE ONE SENTENCE UNDER THE SEARCH BOX, or "" when the screen must say nothing.
 *
 * Silence is the common answer and the correct one: a complete list has nothing to explain, and a
 * standing note about pagination on every visit is the padding these screens have twice been asked
 * for less of.
 *
 * **A FUNCTION, HERE, RATHER THAN A TERNARY IN THE PANEL** — for the reason
 * `android/…/data/DesignWorkshopViewers.dwViewerOfferNotice` gives, which holds the same four states
 * in the same order and the same words: one of them cannot be produced by any live database, so a
 * decision buried in JSX is only ever exercised by somebody looking at a screen.
 *
 * **THE FOURTH STATE IS THE ONE THAT WAS WRONG.** `truncated` covers two different cuts — the account
 * list stopping at the server's ceiling, and the ACTIVE-ROSTER read stopping at its own — and only the
 * first can be narrowed by typing. When the roster read is what was cut, eligible designers are absent
 * from every possible search and the answer can be `truncated` WITH NO USERS IN IT AT ALL; the earlier
 * three-state spelling then told the admin to "narrow the search" over an empty picker, which is advice
 * that cannot work, and it shadowed the more accurate "no eligible account matches that search". Those
 * two states collapsing into one sentence is the defect this whole feature was fixed for, one layer
 * down, so this case is tested first.
 *
 * @param truncated the server's `truncated` — coerced by the caller, since an older deployment omits it
 * @param offered how many accounts the current answer holds
 * @param searched was a term actually sent (a blank box sends nothing and is not a search)
 */
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
    return "Some eligible accounts could not be listed, and no search can reach them — the server log says why.";
  }
  if (truncated && !searched) {
    return "Too many accounts to show them all — search a name or email to reach the rest.";
  }
  if (truncated) return "Too many matches to show them all — narrow the search.";
  if (searched && offered === 0) return "No eligible account matches that search.";
  return "";
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
