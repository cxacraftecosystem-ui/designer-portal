/**
 * THE DESIGNER ROSTER'S OWN READS — one paged list, one institution vocabulary, and the one fact a
 * row carries about who empanelled it.
 *
 * ── WHY THIS IS NOT IN `lib/designers.ts`, WHICH IS WHERE IT BELONGS ────────────────────────────
 *
 * It belongs there and it should end up there. `lib/designers.ts` already holds
 * {@link listDesignerRoster}, whose parameters are `page`, `pageSize`, `search` and `activeOnly` —
 * the four the route took before DROPDOWN_DESIGN §4.1 widened it — and folding the nine new ones
 * into that function is a strictly smaller change than this file. It is here because this parcel's
 * territory is `app/(protected)/admin/designers/**` and `lib/designers.ts` is being read by other
 * work in the same wave; a shared file edited from two places in one afternoon is how two correct
 * changes produce one broken tree. The colocation is the same shape as
 * `app/(protected)/design-workshops/[id]/report/reportTarget.ts` and
 * `app/(protected)/sketches-and-prototypes/chooserSentences.ts`: a module beside the one page that
 * uses it, which the App Router treats as an ordinary import and not as a route.
 *
 * **When it moves, it is a move and not a rewrite.** `listDesignerRosterPage` is
 * `listDesignerRoster` with `RosterQueryParams` spread into the same `buildQuery`, and the one
 * behavioural difference is stated below.
 *
 * ── THE ONE BEHAVIOURAL DIFFERENCE: `activeOnly` IS NEVER SENT ──────────────────────────────────
 *
 * `GET /designers/roster` keeps `activeOnly` for a client that has not been updated, and `standing`
 * is the new spelling of the same question — but sending both is a **422**, deliberately, rather
 * than letting one silently win. `components/admin/rosterFilters.rosterQueryParams` therefore never
 * emits `activeOnly`, and this function never adds it back. A page that mounted `RosterFilterBar`
 * while still sending `activeOnly` would 422 its whole list the moment somebody chose "Only those
 * suspended", which reads as the screen being broken rather than as two parameters disagreeing.
 *
 * `lib/designers.listDesignerRoster` is left exactly as it is: it is the shape an older caller
 * would use, and nothing else in this frontend calls it.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import type { RosterQueryParams } from "@/components/admin/rosterFilters";
import type { DesignerRosterEntry } from "@/lib/designers";
import type { PageResult } from "@/lib/types";

/**
 * One page of the roster, plus the flag §4.4 puts beside it.
 *
 * `page_payload` has no room for a flag, so the route answers `page_payload(...) |
 * {"roleMatchTruncated": bool}`. It is additive, and **optional here on purpose**: on a deployment
 * that predates it the field arrives as `undefined`, which every notice in this cluster treats as
 * "nothing to say" rather than as a cut (`cappedList.flagCutNotice`, `rosterFilters
 * .roleMatchCutNotice`). `apiFetch` casts and does not validate, so an optional field is the honest
 * type for one the server may not send.
 */
export type DesignerRosterPage = PageResult<DesignerRosterEntry> & {
  /**
   * The role filter read a bounded number of ACCOUNTS and stopped. Not a short page: a matching
   * designer is missing from EVERY page of this answer, because the emails that were never read
   * were never folded into the roster query's WHERE. See `roleMatchCutNotice` for the sentence and
   * why it is not `flagCutNotice`'s.
   */
  roleMatchTruncated?: boolean;
};

export type DesignerRosterPageParams = RosterQueryParams & {
  page: number;
  pageSize: number;
};

/**
 * The roster page, with every §4.1 parameter in ONE request.
 *
 * ⚠ **Build `RosterQueryParams` inside the caller's `load()` and never cache it.** The date presets
 * resolve to concrete instants when `rosterQueryParams` runs, so a params object built once at
 * mount and reused keeps asking about the day the screen was opened — a roster left open overnight
 * would answer "Last 7 days" with yesterday's week, and nothing on screen would say so.
 *
 * One `buildQuery` and not two, because `buildQuery` returns a whole query STRING: a second call
 * would produce a second `?`. `page` and `pageSize` sit beside the filters rather than inside
 * `RosterQueryParams` for the reason that type's header gives — the pager is the page's state, and
 * a filter object carrying a page number is one that can be restored from a link onto the wrong
 * page of a list it has just re-filtered.
 */
export function listDesignerRosterPage(params: DesignerRosterPageParams) {
  return apiFetch<DesignerRosterPage>(`/designers/roster${buildQuery({ ...params })}`);
}

/**
 * The institution vocabulary the picker is built from — DROPDOWN_DESIGN §4.5's one new endpoint.
 *
 * **FROM THE SERVER, NEVER FROM THE PAGE OF ROWS ON SCREEN.** `DesignerRoster.institution` is free
 * text, so an exact-match filter is only usable behind a picker of the values that actually exist;
 * and a picker assembled from the twenty rows currently rendered can only ever offer the
 * institutions those twenty rows happened to carry. An admin filtering for one that is two pages
 * down would find no row for it and read that as "nobody is from there" — rule (iv)'s failure
 * wearing a picker, which is exactly the shape this whole cluster of rules exists to close.
 */
export type RosterInstitutions = {
  /** The distinct institution names, server-ordered. */
  items: string[];
  /**
   * The endpoint's own cut flag, read one row past its cap so it is exact.
   *
   * `undefined` rather than `false` where the field is absent, because those are different facts: a
   * deployment that predates the endpoint's flag has told this client NOTHING about a cut, and
   * `institutionCutNotice` prints nothing for `undefined` for that reason. Defaulting it to `false`
   * here would convert "we do not know" into "we checked, and there is no cut".
   */
  truncated: boolean | undefined;
};

/**
 * `GET /designers/roster/institutions`.
 *
 * **THE SHAPE IS NORMALISED HERE BECAUSE `apiFetch` CASTS AND DOES NOT VALIDATE.** This endpoint is
 * new in the same wave as this screen, so the two can be deployed in either order: a frontend that
 * lands first meets a 404 (the caller's problem — it has a sentence for a read that failed), and a
 * frontend that meets a half-built payload must not hand `undefined` to `institutionOptions`, which
 * would throw inside a render and take the whole roster down over a picker. Every value that
 * reaches the picker is a non-empty trimmed string; anything else is dropped here rather than
 * rendered as a blank row somebody cannot tick.
 */
export async function listDesignerRosterInstitutions(): Promise<RosterInstitutions> {
  const payload = await apiFetch<{ items?: unknown; truncated?: unknown }>(
    "/designers/roster/institutions"
  );
  // A LOOP AND NOT `.filter()`, on purpose. This narrows nothing — it is shape normalisation over a
  // vocabulary of NAMES, not a client-side filter over a fetched page of RECORDS — and the two are
  // one grep apart. `app/(protected)/admin/**` carries an invariant that there is no `.filter()`
  // over a fetched page anywhere in it, asserted by a source sweep; writing this as a `.filter()`
  // would put a hit in front of the next reader of that sweep and cost them the ten minutes it
  // takes to work out that it is the harmless kind.
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

/* ────────────────────────────────────────────────────────────────────────────
 * Reading one row: was this empanelment DERIVED, or did a person make it?
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The opening of the sentence a derived empanelment writes about itself —
 * `app/services/designers.py::DERIVED_EMPANELMENT_NOTE`.
 *
 * ── WHY THE NOTE AND NOT `addedById`, WHICH IS THE FIELD THAT LOOKS RIGHT ───────────────────────
 *
 * Because `addedById` cannot answer the question, and the backend constant's own docstring is where
 * that is written down: it is NULL on a row derived from somebody's sign-in, and it is ALSO NULL on
 * a row an admin created years ago whose account has since been deleted — the relation is
 * `onDelete: SetNull`, so the id quietly becomes NULL without the row changing in any other way. An
 * admin looking at those two rows here would read them as the same thing, which is precisely the
 * confusion this marker exists to remove. The note is the part that stays true, and the backend
 * writes it for exactly this screen: *"it is what the roster screen actually shows a human."*
 *
 * ── MATCHING ON TEXT IS A REAL COST, AND THIS IS WHY IT IS THE CHEAPEST ONE ─────────────────────
 *
 * `roster_payload` sends `id, email, fullName, institution, notes, isActive, revokedAt,
 * firstSeenAt, createdAt, updatedAt, addedById` — verified field by field — and none of the others
 * carries this fact. A `derived: bool` on the payload would be the right answer and is a backend
 * change this parcel may not make; until it exists the choice is a text match or no marker at all,
 * and no marker is the state that sent an admin looking for the colleague who empanelled
 * `sandycraft3@gmail.com` when nobody had.
 *
 * **The failure mode of a reworded backend sentence is benign, and that is deliberate.** The prefix
 * stops matching, the chip stops being drawn, and the note itself still prints VERBATIM in the Note
 * column exactly as every other note does — so the fact is still on screen in full, it has merely
 * stopped being scannable. Nothing is hidden and nothing is claimed falsely, which is the only
 * property that makes a text match acceptable here at all. It is matched as a PREFIX rather than
 * for equality so that an admin who later appends their own line to the note keeps the marker.
 */
const DERIVED_EMPANELMENT_OPENING =
  "Empanelled automatically because this address is admitted on the platform allow-list";

/**
 * Was this row created by the allow-list rather than by a person?
 *
 * Takes only the note, so the same test can be applied to a row from any shape of payload, and so
 * that a reader of this predicate can see there is nothing else it could be reading.
 */
export function isDerivedEmpanelment(entry: Pick<DesignerRosterEntry, "notes">): boolean {
  return (entry.notes ?? "").trimStart().startsWith(DERIVED_EMPANELMENT_OPENING);
}
