/**
 * THE ALLOW-LIST READ WITH EVERY FILTER THE FILTER BAR CAN SET — and the four sentences this screen
 * is allowed to say when that read comes back holding nothing.
 *
 * ── WHY THIS IS NOT IN `lib/accessRoster.ts`, WHICH IS WHERE IT BELONGS ──────────────────────────
 *
 * It belongs there and it is going there. `listAccessRoster` still declares exactly the four
 * parameters it declared before this wave — `page`, `pageSize`, `search`, and a SINGLE `status` —
 * and that module is another parcel's territory while req 30 lands, so widening its signature here
 * would be two agents writing one file. The read is therefore assembled beside the screen that
 * needs it, out of the two pieces that are already shared: `buildQuery` (which drops `undefined`,
 * `null` and `""` alike, `lib/api.ts`) and `rosterQueryParams` (which decides what a filter object
 * means on the wire, `components/admin/rosterFilters.ts`).
 *
 * **When `lib/accessRoster.ts` gains the §4.1 parameters, delete `listFilteredAccessRoster` and call
 * `listAccessRoster` with the same spread.** There is nothing else in it. The queue at the top of
 * the screen already calls `listAccessRoster` directly and is deliberately left doing so: it asks a
 * fixed question — `status=PENDING`, oldest page first — that no control on the screen narrows, and
 * routing it through a filter object would be an invitation to narrow it by accident.
 *
 * ── WHY A MODULE OF ITS OWN, BESIDE `page.tsx` ──────────────────────────────────────────────────
 *
 * So that a test can read the sentences and the query builder without mounting React — the same
 * arrangement `sketches-and-prototypes/chooserSentences.ts` and `design-workshops/[id]/report/
 * reportTarget.ts` already use two routes over, each with a `*-unit.spec.ts` that imports it
 * directly. Next treats only `page` / `layout` / `route` files as routes, so a plain `.ts` sitting
 * beside one is a module and nothing else.
 *
 * The property worth pinning from outside a browser is a relationship BETWEEN the sentences rather
 * than any one of them:
 *
 *     ONLY THE STATE THAT ACTUALLY GOT AN ANSWER MAY CLAIM THE LIST IS EMPTY.
 *
 * Three inline string literals in three JSX branches cannot be compared with each other by any
 * test, and "nobody has ever been admitted or turned away" printed over a request that FAILED is a
 * claim about an institution's access control made from a network error. On this screen that
 * reading is alarming enough to act on — an admin who believes the allow-list is empty re-adds
 * addresses that are already on it and collects a row of 409s.
 */

import { rosterLinkParams, rosterQueryParams, type RosterFilters } from "@/components/admin/rosterFilters";
import { apiFetch, buildQuery } from "@/lib/api";
import type { AccessRosterPage } from "@/lib/accessRoster";

/* ────────────────────────────────────────────────────────────────────────────
 * The read
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One page of the allow-list, plus the one field `page_payload` has no room for.
 *
 * `roleMatchTruncated` is DROPDOWN_DESIGN §4.4's flag, and on THIS route it is documented to be
 * always `false`: the access list's tier filter is `admitRole`, a real column on `AccessRoster`
 * (`schema.prisma:4169-4226`), so matching a tier needs no second read and nothing can fall off the
 * end of one. It rides on the envelope only so that both rosters answer the same shape — the
 * designer roster, whose `DesignerRoster` table has no role column at all, has to read the ACCOUNTS
 * that hold a tier and can genuinely run out of budget doing it.
 *
 * It is optional because `apiFetch` casts and does not validate: on a deployment that predates the
 * key it arrives as `undefined`, which every notice in this repository reads as "nothing to say"
 * rather than as a cut (`cappedList.flagCutNotice:196-199`). Declared here rather than in
 * `lib/accessRoster.ts` for the territory reason in the header; it is additive either way.
 */
export type AccessRosterAnswer = AccessRosterPage & {
  roleMatchTruncated?: boolean;
};

/**
 * `GET /access/roster` — one page, narrowed by everything the filter bar holds.
 *
 * ── ONE `buildQuery`, ONE REQUEST, EVERY FILTER AND-ED ───────────────────────────────────────────
 *
 * The pager and the filters are spread into a single query string rather than applied in passes,
 * because a second pass is a second request and two requests over one list is how a screen ends up
 * showing the intersection of two different moments.
 *
 * ── CALLED INSIDE `load()`, NEVER CACHED ────────────────────────────────────────────────────────
 *
 * `rosterQueryParams` resolves date PRESETS to concrete instants at the moment it is called, in the
 * browser, because only the browser knows the reader's clock. Hoisting the result into a `useMemo`
 * would freeze "the last 30 days" at whatever it meant when the component mounted, and an admin who
 * leaves this tab open overnight would keep asking about yesterday — the failure Android's
 * `resolveDateRange(today)` names in the same words.
 *
 * ── WHAT THE DEFAULT STATE SENDS, WHICH IS THE POINT OF RULE (ii) ────────────────────────────────
 *
 * `rosterQueryParams("access", emptyRosterFilters("access"))` returns an object whose every value is
 * `undefined`, and `buildQuery` drops every one of them. So the first request this screen makes is
 * `GET /access/roster?page=1&pageSize=20` — byte for byte the request it made before req 30, with no
 * `status` key at all, which is the server's spelling of *every* status: ACTIVE, PENDING, REJECTED
 * and SUSPENDED. That is not a coincidence to be preserved by care; it is the guarantee
 * `emptyRosterFilters` exists to give, and `e2e/roster-filters-unit.spec.ts` pins it.
 */
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

/**
 * The address bar for a filter state — so a narrowed allow-list is a link an admin can paste to a
 * colleague, which is the whole reason `/admin/access` is ever opened by two people at once.
 *
 * THE PAGE NUMBER IS DELIBERATELY NOT IN IT. `rosterLinkParams` carries the filters and the order
 * and refuses the pager, and this agrees with it: an offset is only meaningful against the exact
 * list it was taken from, and the colleague opening the link is doing so minutes later, after the
 * person in question may have been approved or suspended. "Page 3" would then be three pages into a
 * different list. The filters and the sort reproduce the list; page 1 of it is the honest landing.
 *
 * Default filters produce no keys at all, so the unfiltered screen's address stays the bare path
 * and a bookmark made today does not silently acquire a filter.
 */
export function accessRosterHref(filters: RosterFilters): string {
  return `/admin/access${buildQuery(rosterLinkParams("access", filters))}`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The sentences — §3.5, for a list screen rather than a picker
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE READ FAILED, AND NOTHING IS ON SCREEN. The state this repository is worst at, everywhere.
 *
 * §3.5's fourth sentence — *"could-not-be-listed"* — said for a list rather than for a form field.
 * Its shape is fixed and both halves are load-bearing: **this is not showing what exists**, and
 * **nothing has been changed**. The picker version reassures the reader that the record can still be
 * saved; there is nothing being saved here, so this one reassures them about the thing an admin on
 * this screen would actually fear, which is that the allow-list itself has been emptied.
 *
 * It must never be worded like the empty state below it. A failed read rendered as "nobody has been
 * admitted yet" is a claim about an institution's access control drawn from a network error, and an
 * admin who believes it re-adds addresses that are already on the list.
 */
export const ACCESS_LIST_UNREADABLE_TITLE = "The list could not be loaded";

/** The body of the same state: what it is not, and what has not happened. */
export const ACCESS_LIST_UNREADABLE_BODY =
  "This is not showing what exists, and it is not a claim that nobody may sign in — the request for " +
  "the allow-list did not come back. Nobody's access has been changed by the failure; the message " +
  "above is what the server answered.";

/**
 * A REFRESH FAILED WHILE ROWS WERE ALREADY ON SCREEN — a different fact from the one above, and it
 * is why the rows are deliberately left standing.
 *
 * Replacing a list an admin can still read with "nobody may sign in" is indistinguishable from an
 * emptied institution. So the rows stay and this line says what they are: an older answer. Without
 * it the screen silently disagrees with the server about who is suspended, which on this screen
 * decides whether somebody is told to try again or told to call an administrator.
 */
export const ACCESS_LIST_STALE_NOTE =
  "These rows are the last answer that arrived. The most recent refresh failed, so a decision made " +
  "since then — by you or by another administrator — may not be shown here yet.";

/**
 * THE FILTERS EXCLUDED EVERYONE. Not the same fact as an empty list, and the difference is the
 * whole of §3.5.
 *
 * Reached only when at least one control is narrowing (`hasActiveRosterFilters`), so it can never be
 * printed over an untouched screen — the mistake the closed viewer-picker defect made in reverse,
 * telling a reader to narrow a search they had not made.
 *
 * The body names what clearing gets back IN FULL, refused and suspended rows included, because this
 * screen's widest default is the reason it is usable at all: an admin arrives holding a message
 * from somebody who cannot sign in, and the row that explains it is a REJECTED or a SUSPENDED one.
 */
export const ACCESS_NO_MATCH_TITLE = "Nobody matches these filters";

/** The body of the same state. Says what clearing restores, and that it restores the refusals too. */
export const ACCESS_NO_MATCH_BODY =
  "Clear them to see everyone this application has ever admitted, refused or suspended — the " +
  "refused and suspended entries included, which are the ones that explain why somebody cannot " +
  "sign in.";

/**
 * THE LIST IS GENUINELY EMPTY, ANSWERED AND NONE. §3.5's *genuinely-empty, unscoped*.
 *
 * The one sentence on this screen that may make a claim about the repository, because it is the only
 * one reached from a request that succeeded, with no filter narrowing it and no term in the box.
 */
export const ACCESS_NOBODY_YET_TITLE = "Nobody is on the list yet";

/** The body of the same state: why an empty allow-list is not a locked-out institution. */
export const ACCESS_NOBODY_YET_BODY =
  "Add the first address above — the master admin can always sign in regardless of this list, which " +
  "is what makes it safe to start empty.";

/**
 * THE PAGER IS PAST THE END OF THE LIST — rows exist, none of them are here.
 *
 * A fourth title, for a state that looks like emptiness and is not: `total` is positive and this
 * page holds nothing, which happens for the instant between deciding the last request on the last
 * page and the step-back guard re-reading. `cappedList.cappedListNotice`'s first arm words the body
 * ("None of the 431 entries could be listed here — this is not an empty repository"), and it is
 * tested BEFORE the term arm there for exactly this reason: with `total > 0` the server has said
 * these rows exist, so "nobody matches" would be flatly false.
 */
export const ACCESS_PAST_END_TITLE = "Nothing on this page";

/**
 * THE SERVER SAYS THE TIER FILTER'S ANSWER IS INCOMPLETE — a sentence that should never be reached
 * on this route, and which is here because "should never" is not a reason to be silent.
 *
 * ── WHY NOT `flagCutNotice(truncated, noun, term)`, WHICH IS WHAT §5's W7 ENTRY SPECIFIES ────────
 *
 * Because its no-term arm ends *"The box in this picker only filters what is already listed"*, and
 * on this screen that sentence is FALSE. There is no picker; the box beside this notice is the
 * roster search and its term goes into `GET /access/roster?search=`, over the whole table. Printing
 * it here would teach an admin that this screen's search only sifts the twenty rows already drawn —
 * which is precisely the belief rule (iv) exists to prevent, printed by the one page whose own
 * header records four closed defects of that exact shape. `flagCutNotice` was written for
 * `GET /tasks/options`, whose picker really does filter locally, and its own header says to check
 * the request before reusing it. Checked; it does not fit.
 *
 * ── WHY NOT `roleMatchCutNotice` FROM `components/admin/rosterFilters.ts` EITHER ─────────────────
 *
 * That one is exact for the designer roster and wrong about the mechanism here: it explains that
 * matching a tier means reading the accounts that hold it, which is true of `DesignerRoster` (no
 * role column, joined by email) and false of `AccessRoster`, where `admitRole` is a column. It also
 * says "designers". A reader given a mechanism that does not apply cannot act on it.
 *
 * ── WHAT IT SAYS INSTEAD ────────────────────────────────────────────────────────────────────────
 *
 * The two facts that survive without knowing the mechanism: entries are missing from every page of
 * this filter, and choosing fewer tiers is the move. No number, because this client has not read one
 * — never print a cap you did not read, and `queueCutNotice` makes the same trade in the same words:
 * *"the honest fallback is the fact WITHOUT the numbers. Saying nothing at all would be the one
 * unacceptable answer."*
 *
 * `undefined` is silence, for `flagCutNotice`'s reason: an older deployment does not send the key.
 */
export function accessRoleCutNotice(truncated: boolean | undefined): string {
  if (!truncated) return "";
  return (
    "The server could not match the chosen tiers completely, so some entries are missing from every " +
    "page of this filter — not only from this one. Choosing fewer tiers narrows what has to be " +
    "matched and gives a complete answer; clearing the tier filter lists everyone."
  );
}
