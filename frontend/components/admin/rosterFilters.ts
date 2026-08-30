/**
 * THE TWO ROSTER SCREENS' FILTER VOCABULARY — every parameter `/admin/access` and `/admin/designers`
 * put on the wire, the URL a filtered roster round-trips through, and the sentences a cut roster
 * prints. `RosterFilterBar` and `SortableTh` render it; the two pages own the fetch and the pager.
 *
 * ── WHY A MODULE AND NOT PROPS AND TERNARIES IN TWO PAGE FILES ────────────────────────────────────
 *
 * The same argument `components/data/cappedList.ts`, `components/ui/selectFilter.ts` and
 * `components/admin/deletedWorkshops.ts` each make, and this file deliberately copies both halves of
 * it:
 *
 * 1. **Two screens must not word one thing two ways.** `/admin/access` and `/admin/designers` are
 *    two lists an admin moves between while holding one thought — "why can this person not sign
 *    in". They already disagree about the same filter today: the access page's widest option reads
 *    "Everyone ever seen" and the designer page's reads "Everyone ever empanelled", which is fine,
 *    and both call the control "Filter by standing" to a screen reader while showing no visible
 *    label at all, which is not. Every string either screen shows for a filter is now declared once,
 *    below, in {@link ROSTER_LABELS} and the option tables.
 * 2. **None of this is reachable from a click, so it has to be reachable from a test.** This
 *    repository has no React renderer in its devDependencies — Playwright is the whole of it — so a
 *    decision written inside a render is only ever exercised by somebody looking at a screen. The
 *    serialisation below is the part that can be wrong in a way nobody sees (a filter that spells
 *    "everything" two ways, a date range resolved against the wrong clock, a sort with no
 *    tiebreak), so it is pure functions here and `e2e/roster-filters-unit.spec.ts` calls them.
 *
 * ── THE FOUR RULES THIS FILE EXISTS TO KEEP (DROPDOWN_DESIGN §4.6) ───────────────────────────────
 *
 * **(i) EMPTY MEANS EVERYTHING, BY ABSENCE.** Never by an all-ticked state. Every multi-valued
 * filter here is an array, `[]` is its default, and `[]` serialises to `undefined` — which
 * `lib/api.buildQuery` drops entirely, so the parameter is ABSENT on the wire and the server reads
 * absence as "do not filter" (`services/record_filters.resolve_workshop_ids:65-67`). There is
 * deliberately no "select all" anywhere: every `MultiSelectDropdown` in `RosterFilterBar` passes
 * `bulk={false}`. Ticking all eight tiers is NOT the same request as ticking none — it excludes
 * every row whose tier is the platform default — and that is the point of the reserved ninth
 * option, not an accident of it.
 *
 * **(ii) SUSPENDED AND REJECTED ROWS STAY LISTED BY DEFAULT.** `emptyRosterFilters` narrows
 * nothing, on either screen, and that is asserted rather than assumed. The rule is already written
 * down in four places — `app/(protected)/admin/designers/page.tsx:27-29`,
 * `app/(protected)/admin/access/page.tsx:98-101`, `backend/app/services/designers.py:106-111`,
 * `AccessRosterScreen.kt:321-324` — and the reason is always the same: an admin arrives BECAUSE
 * somebody cannot log in, and the row refusing them is the one they came to see. A default that
 * hid it would leave them re-adding an address the unique index then rejects, with the explanation
 * nowhere on screen.
 *
 * **(iii) ANY CAP OR TRUNCATION IS STATED, WITH THE NUMBER WHERE THERE IS ONE.** Two cuts can
 * happen behind these controls and neither is visible in the rows: the institution vocabulary stops
 * at a server cap, and the designer role filter stops after reading a bounded number of accounts.
 * {@link institutionCutNotice} and {@link roleMatchCutNotice} are those two sentences.
 *
 * **(iv) FILTERING IS SERVER-SIDE.** Everything here produces a QUERY. There is no `.filter()` over
 * a fetched page in this module, in `RosterFilterBar`, or — per its own header — anywhere in
 * `admin/access/page.tsx`. A client-side box over a server-truncated page answers "No matches"
 * about records that exist, which is `docs/OPEN_FINDINGS.md`'s most-repeated closed defect.
 *
 * ── ONE THING THAT IS BORROWED AND SHOULD NOT STAY BORROWED ──────────────────────────────────────
 *
 * The date presets come from `components/search/SearchFilters.tsx` — `RANGE_IDS` and `resolveRange`,
 * imported rather than reimplemented, because `resolveRange` carries the local-midnight fix
 * (`parseDateInput`: `new Date("2026-07-20")` is UTC midnight, i.e. the previous day west of
 * Greenwich) and a second copy of that is a second chance to get it wrong. DROPDOWN_DESIGN §4.9
 * asks for those two exports to be LIFTED into `components/data/dateRange.ts` and re-exported from
 * `SearchFilters` so nothing moves twice; that file is not in this parcel's territory, so the
 * import points at `SearchFilters` for now. The cost of leaving it is real and worth stating: that
 * module is `"use client"` and also exports a React component, so importing `resolveRange` from it
 * pulls the repository search panel into both admin bundles. Moving the two exports to
 * `components/data/dateRange.ts` and re-pointing this one import is the whole of the fix.
 */

import {
  EMPTY_SEARCH_FILTERS,
  RANGE_IDS,
  resolveRange,
  type RangeId
} from "@/components/search/SearchFilters";
import { ROLE_LABELS, ROLES_BY_RANK } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * Which roster
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The two lists. They are two TABLES with two different jobs and the words must keep them apart:
 * `AccessRoster` says who may reach the product at all, `DesignerRoster` says who the institution
 * recognises as a designer (`lib/accessRoster.ts`'s header, and `lib/designers.ts:6`). A screen
 * that merged them would tell a suspended crowdsource volunteer that their "designer access" ended.
 *
 * One discriminator rather than two components, because the CONTROLS are the same controls — a
 * search box, some multi-selects, one date range, one sort — and the fifteen-odd sentences around
 * them are what must not drift. Where the two genuinely differ (access has no institution; the
 * designer roster's standing is one enum, not a set) the difference is stated at the field, not
 * duplicated across two files.
 */
export type RosterKind = "access" | "designer";

export type RosterDir = "asc" | "desc";

/* ────────────────────────────────────────────────────────────────────────────
 * The reserved tokens — three of them, and each one closes the same hole
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `admitRole IS NULL` — "admitted at the platform default, the lowest rung"
 * (`schema.prisma:4177-4186`). The access screen already renders it as its own phrase in the
 * standing chip ("at the default joining tier"), so it is a value an admin can already SEE and must
 * therefore be able to filter for.
 *
 * WITHOUT THIS OPTION, ticking all eight tiers silently excludes every default-tier admission — the
 * identical failure `UNASSIGNED_WORKSHOP` was invented for (`services/record_filters.py:47-53`) and
 * the one `WorkshopScopeSelect`'s "Not linked to a workshop" row closes. A reserved word rather than
 * an empty string, for that module's reason: an empty string is what a blank control sends, and
 * "the admin chose nothing" must never mean "show me only the orphans".
 */
export const ADMIT_ROLE_DEFAULT = "default";

/**
 * `DesignerRoster.firstSeenAt IS NULL` on the designer roster's role filter.
 *
 * NAMED FOR WHAT THE COLUMN STORES, AND THE DIFFERENCE IS THE POINT. "Has no account" is not a fact
 * this system holds; what it holds is `firstSeenAt`, "set the first time an account with this email
 * signs in, so an admin can see which invitations are outstanding rather than guessing"
 * (`schema.prisma:3962-3964`). Labelling the option "Has never signed in" makes it answerable from
 * one column, needs no second query, and says something true. "No account" would need an unbounded
 * NOT IN over every account the repository has ever had and would STILL be wrong for a provisioned
 * account that has not signed in yet.
 */
export const ROLE_NEVER_SIGNED_IN = "never-signed-in";

/**
 * `institution IS NULL` on the designer roster.
 *
 * ⚠ THE ONE COLLISION IN THIS VOCABULARY, WRITTEN DOWN RATHER THAN LEFT TO BE FOUND.
 * `DesignerRoster.institution` is free text (`schema.prisma:3954`), so an institution can in
 * principle be *called* "none", and its served option would then carry the same `value` as this
 * reserved row. {@link institutionOptions} de-duplicates so the panel never renders two rows with
 * one value, which keeps the control coherent — but the filter for that institution is then
 * unreachable by name, and the server would read the token as the NULL sentinel. This is a property
 * of the wire format DROPDOWN_DESIGN §4.1 fixes, not of this file; the honest fixes are a sentinel
 * free text cannot spell, or filtering that institution through the search box, which does reach it
 * (`search` is OR-ed over `institution` on that route). Raise it with the roster route before
 * "simplifying" the de-duplication away.
 */
export const INSTITUTION_NONE = "none";

/* ────────────────────────────────────────────────────────────────────────────
 * The columns each screen can filter and sort by
 * ──────────────────────────────────────────────────────────────────────────── */

/** The four states an allow-list row can be in, exactly as `app/services/access_roster.py` spells them. */
export const ACCESS_STATUS_TOKENS = ["ACTIVE", "PENDING", "REJECTED", "SUSPENDED"] as const;
export type AccessStatusToken = (typeof ACCESS_STATUS_TOKENS)[number];

/**
 * The designer roster's standing, as ONE enum rather than a set.
 *
 * `isActive` is a boolean, so "both" is the absence of the parameter and there is no third value to
 * tick. Sending `activeOnly=true` alongside `standing=suspended` is a 422 on the server rather than
 * a silent winner, which is why {@link rosterQueryParams} emits `standing` and never `activeOnly`:
 * the older spelling stays on the wire for a client that has not been updated, and this bar is not
 * that client.
 */
export const DESIGNER_STANDING_TOKENS = ["active", "suspended"] as const;
export type DesignerStandingToken = (typeof DESIGNER_STANDING_TOKENS)[number];

/**
 * ONE DATE RANGE PER REQUEST, NOT FIVE — `dateField` names the column, `dateFrom`/`dateTo` bound it.
 *
 * The requirement lists five dates on the allow-list. Five simultaneous From/To pairs would be five
 * index requirements on tables that had none at all on any date column, a query nobody has asked
 * for, and five stacked widgets where one will do. `dateFrom`/`dateTo` is also the spelling eight
 * existing list routes already use, each paired with one `add_date_range` call.
 */
export const ACCESS_DATE_FIELDS = ["added", "requested", "decided", "joined", "firstSeen"] as const;
export type AccessDateField = (typeof ACCESS_DATE_FIELDS)[number];

export const DESIGNER_DATE_FIELDS = ["added", "firstSeen", "revoked"] as const;
export type DesignerDateField = (typeof DESIGNER_DATE_FIELDS)[number];

export type RosterDateField = AccessDateField | DesignerDateField;

export type AccessSort =
  | "added"
  | "email"
  | "name"
  | "standing"
  | "joined"
  | "requested"
  | "decided"
  | "firstSeen"
  | "attempts";

export type DesignerSort = "added" | "email" | "name" | "institution" | "firstSeen" | "revoked";

export type RosterSort = AccessSort | DesignerSort;

/**
 * What a column HOLDS, which is the only thing that decides which way it should read first and how
 * a sort control should describe itself.
 *
 * "Newest first" and "A to Z" are both "the natural first reading" and they are opposite directions,
 * so a sort control that inherits the previous column's direction gives you Z-to-A the first time
 * you click Email after clicking a date. {@link nextRosterSort} takes the new column's own default
 * instead, and {@link sortDirectionPhrase} turns the pair into words a person can act on.
 */
export type SortValues = "date" | "text" | "count" | "enum";

export type RosterSortSpec = {
  /** The direction a FIRST click on this column produces — DROPDOWN_DESIGN §4.3's table. */
  defaultDir: RosterDir;
  values: SortValues;
  /**
   * Can this column be NULL? Postgres puts NULLs last on `asc` and first on `desc`, so a nullable
   * column sorted newest-first opens with every row that has no value at all.
   *
   * That is not a bug, and on one column it is the whole point: `firstSeen desc` floats every
   * OUTSTANDING INVITATION to the top, which is exactly the view Android's device-side sort was
   * built to produce ("an admin opens this screen to answer 'who have I added who has not turned
   * up'") and which now survives as a named, paged sort that is correct across pages instead of a
   * reordering of whichever rows happened to arrive. It is flagged here so a control can SAY so,
   * because a table whose first ten rows are blank in the column you just sorted by reads as broken.
   */
  nullable: boolean;
};

/**
 * Access-list sorts. Every one of them is tiebroken by `id` on the server
 * (`services/records.with_id_tiebreak`), and that is not optional: OFFSET PAGING OVER A NON-TOTAL
 * ORDER MISSES ROWS AND REPEATS OTHERS, AND BOTH ARE SILENT. The ties are not hypothetical here —
 * the access-roster migration inserted every grandfathered row with one `CURRENT_TIMESTAMP`, so
 * four hundred people share a single `createdAt`. The client's whole part in that is to send ONE
 * named column and ONE direction and never to re-sort what came back.
 */
export const ACCESS_SORTS: Record<AccessSort, RosterSortSpec> = {
  added: { defaultDir: "desc", values: "date", nullable: false },
  email: { defaultDir: "asc", values: "text", nullable: false },
  name: { defaultDir: "asc", values: "text", nullable: true },
  standing: { defaultDir: "asc", values: "enum", nullable: false },
  joined: { defaultDir: "desc", values: "date", nullable: true },
  // The queue an admin works oldest-first is this column with `dir=asc`, which is why the header is
  // a toggle and not a fixed order.
  requested: { defaultDir: "desc", values: "date", nullable: true },
  decided: { defaultDir: "desc", values: "date", nullable: true },
  firstSeen: { defaultDir: "desc", values: "date", nullable: true },
  // "Who is hammering the door." Never a filter — `attemptCount` is the one column an
  // UNAUTHENTICATED caller's retries write, and it is read here only as an order.
  attempts: { defaultDir: "desc", values: "count", nullable: false }
};

export const DESIGNER_SORTS: Record<DesignerSort, RosterSortSpec> = {
  added: { defaultDir: "desc", values: "date", nullable: false },
  email: { defaultDir: "asc", values: "text", nullable: false },
  name: { defaultDir: "asc", values: "text", nullable: true },
  institution: { defaultDir: "asc", values: "text", nullable: true },
  firstSeen: { defaultDir: "desc", values: "date", nullable: true },
  revoked: { defaultDir: "desc", values: "date", nullable: true }
};

/**
 * The server's default order on BOTH routes, and therefore the one pair this client leaves off the
 * wire entirely. Everything else is sent explicitly — including `dir=asc`, which the route would
 * otherwise default to `desc` and hand back Z-to-A for a column whose natural reading is A-to-Z.
 */
export const ROSTER_DEFAULT_SORT: RosterSort = "added";
export const ROSTER_DEFAULT_DIR: RosterDir = "desc";

function sortTable(kind: RosterKind): Record<string, RosterSortSpec | undefined> {
  return kind === "access" ? ACCESS_SORTS : DESIGNER_SORTS;
}

/**
 * The spec for a column, or `undefined` where that token does not belong to that roster.
 *
 * `undefined` is a REACHABLE state and not a type hole: `?sort=attempts` is a perfectly good access
 * URL, and pasting it onto `/admin/designers` — which has no attempt count — must land on that
 * screen's default rather than send the server a token it will 422.
 */
export function rosterSortSpec(kind: RosterKind, column: string): RosterSortSpec | undefined {
  return sortTable(kind)[column];
}

/* ────────────────────────────────────────────────────────────────────────────
 * The state the two screens hold
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Everything both roster screens can be narrowed or ordered by, in one object.
 *
 * ONE SHAPE FOR BOTH ROSTERS, with the fields that do not apply to a screen left at their empty
 * value. The alternative — a discriminated union — buys type safety over three fields and costs a
 * second copy of the serialiser, the URL reader, the clear-all and the sort logic, which are the
 * parts that can actually be wrong. {@link rosterQueryParams} guarantees the other half of it: a key
 * that does not belong to a route is never sent to it, whatever this object happens to hold.
 */
export type RosterFilters = {
  /**
   * THE APPLIED TERM — what actually went into the last request, not the keystroke in the box.
   *
   * `RosterFilterBar` keeps the draft internally and pushes it here on a debounce or on Enter, so
   * this field is always safe to render a "no rows match X" sentence against: it can never name a
   * term the server has not been asked about, which is the sentence `searchCutNotice`'s `pending`
   * flag exists to suppress.
   */
  search: string;
  /** ACCESS ONLY. `[]` is EVERY status — including rejected and suspended. See rule (ii). */
  status: AccessStatusToken[];
  /** DESIGNER ONLY. `""` is BOTH standings, by absence. */
  standing: "" | DesignerStandingToken;
  /**
   * Both rosters. `[]` is every tier. May contain the reserved ninth token —
   * {@link ADMIT_ROLE_DEFAULT} on the access list, {@link ROLE_NEVER_SIGNED_IN} on the designer
   * roster — so the two are not interchangeable and a URL carrying one is not read on the other.
   */
  roles: string[];
  /** DESIGNER ONLY. `[]` is every institution, rows with none included. May contain {@link INSTITUTION_NONE}. */
  institutions: string[];
  /**
   * Which date column the range below bounds. Defaults to `added` and NARROWS NOTHING ON ITS OWN —
   * with no bounds resolved, neither this nor the range reaches the wire, so rule (ii) is not
   * touched by having a column pre-selected. That is the whole reason it is safe to have one.
   */
  dateField: RosterDateField;
  /** The preset, resolved to instants at REQUEST time. `"any"` is no date filter at all. */
  range: RangeId;
  /** `yyyy-mm-dd` from the two boxes; read only when `range` is `"custom"`. */
  from: string;
  to: string;
  sort: RosterSort;
  dir: RosterDir;
};

/**
 * THE DEFAULT STATE OF BOTH SCREENS, AND IT NARROWS NOTHING.
 *
 * This is rule (ii) as a value: every filter is at its empty state, so the first page an admin sees
 * holds rejected rows, suspended rows, every tier and every institution.
 * `rosterQueryParams(kind, emptyRosterFilters(kind))` produces an object whose every key is
 * `undefined`, which `buildQuery` renders as the empty string — the two roster routes are asked the
 * exact question they are asked today.
 */
export function emptyRosterFilters(kind: RosterKind): RosterFilters {
  return {
    search: "",
    status: [],
    standing: "",
    roles: [],
    institutions: [],
    // `added` on both rosters — and READ OFF THE PICKER'S OWN FIRST ROW rather than written out a
    // second time here, so the pre-selected column cannot drift from the one at the top of the list
    // the reader is looking at. It narrows nothing on its own, which is what makes a pre-selection
    // safe under rule (ii) at all.
    dateField: (dateFieldOptions(kind)[0]?.value ?? "added") as RosterDateField,
    range: "any",
    from: "",
    to: "",
    sort: ROSTER_DEFAULT_SORT,
    dir: ROSTER_DEFAULT_DIR
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The labels — DROPDOWN_DESIGN §4.8, written once
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * EVERY VISIBLE LABEL AND EVERY ACCESSIBLE NAME IN THE FILTER ROW, and they are the same strings.
 *
 * WHY THEY ARE THE SAME STRINGS. A themed dropdown is a `<button>`, and a `<label>` cannot name a
 * button — so the visible text has to be a `<span>` and the accessible name has to come from
 * `ariaLabel`, which makes it trivially easy to ship a control that SHOWS one word and ANNOUNCES
 * another. `SearchFilters.tsx` does exactly that today (visible "Record time", announced "Filter by
 * when the record was made"), and the cost is not theoretical: a voice-control user who says the
 * words they can see does not reach the control. One constant per label, used for both, makes the
 * mismatch unspellable.
 *
 * WHY THE SEARCH BOXES GET A SENTENCE AND NOT A WORD. `components/SearchInput.tsx` sets
 * `role="searchbox"` with no `<label>` and no `aria-label`, so its placeholder is its ONLY
 * accessible name — asserted as behaviour by `e2e/design-workshop-viewers.spec.ts:514-527`, which
 * tabs onto the box and polls for the role precisely because there is nothing else to poll for.
 * These two boxes search three columns each, and which three is the thing a reader cannot guess:
 * the allow-list's box reaches an admin's private note, and the designer roster's reaches the
 * institution. Naming the columns is the difference between a box you trust and a box you try twice.
 */
export const ROSTER_LABELS = {
  accessSearch: "Search the allow-list by email, name or note",
  designerSearch: "Search the roster by email, name or institution",
  accessStatus: "Standing",
  accessRoles: "Tier they join at",
  designerStanding: "Standing",
  designerRoles: "Tier of the linked account",
  designerInstitutions: "Institution",
  dateField: "Which date",
  dateRange: "Date range",
  datePeriod: "Period",
  dateFrom: "From",
  dateTo: "To",
  clearAll: "Clear every filter"
} as const;

/**
 * THE HINT UNDER THE DESIGNER ROSTER'S ROLE PICKER, and it is not decoration — it says what the
 * filter MEANS, which is not what its label implies.
 *
 * `DesignerRoster` has no role column and no user relation; the join is by lower-cased email. And a
 * roster row whose account is an ADMIN is not gated by this roster at all
 * (`app/services/designers.py:82`), so "role = Admin" over this list answers "which empanelled
 * addresses belong to admins", NOT "which admins may sign in". An admin who reads it the second way
 * draws a conclusion about their own access control from a list that does not govern it.
 */
export const DESIGNER_ROLE_HINT =
  "Matched by email. An admin's row is not gated by this roster, so filtering for Admin lists empanelled addresses that belong to admins.";

/**
 * THE ONE LINE THE ACCESS FILTER ROW CARRIES ABOUT A CONTROL THAT IS NOT THERE.
 *
 * `AccessRoster` has no institution column, and adding the filter by joining to
 * `DesignerRoster.institution` on email would narrow the allow-list to *the subset that is also
 * empanelled as a designer* while presenting itself as an institution filter — silently hiding
 * exactly the pending strangers this screen exists to decide about. That is the same failure the
 * screen's own widest-default rule prevents, one layer down. So the filter is not offered, and the
 * absence is explained rather than left to look like an oversight somebody should "fix".
 */
export const ACCESS_INSTITUTION_NOTE =
  "Institution is not recorded on the allow-list — it is a designer-roster field. Filter by it on the designer roster.";

/* ────────────────────────────────────────────────────────────────────────────
 * The option vocabularies
 * ──────────────────────────────────────────────────────────────────────────── */

export type RosterOption = { value: string; label: string; hint?: string };

/**
 * The allow-list's standing filter, as a MULTI-select over the four states.
 *
 * ── THE LABELS ARE THE STANDING CHIP'S WORDS, NOT THE OLD SINGLE-SELECT'S ────────────────────────
 * The control this replaces was a single-select whose rows read "Only those who may sign in", "Only
 * those refused", and so on. "Only" is a true word for a control where one choice excludes the
 * others and a false one the moment two rows can be ticked together. So the rows carry the same
 * nouns the table's own `StandingChip` prints — "May sign in", "Waiting for a decision", "Refused",
 * "Suspended" — and an admin reading a row and an admin ticking a filter now use one vocabulary.
 *
 * ── THERE IS NO "HIDE SUSPENDED" OPTION, AND THAT IS DELIBERATE ──────────────────────────────────
 * It was considered and rejected twice over. First, rule (ii): a "hide suspended" that defaulted to
 * ON would put this screen's most-needed row out of view for the exact admin who came to find it,
 * and the rule is written down in four places specifically so a new control cannot contradict it.
 * Second, and the reason it is absent rather than merely defaulted off: a control that hides
 * suspended rows is a SECOND SPELLING of ticking the other three, and a filter with two spellings
 * for one state cannot tell a default from a deliberate choice — which is rule (i), the thing this
 * whole file is arranged around. Ticking "May sign in", "Waiting for a decision" and "Refused" says
 * it exactly once, in the same control, and the trigger reads back what was chosen.
 */
export const ACCESS_STATUS_OPTIONS: RosterOption[] = [
  { value: "ACTIVE", label: "May sign in" },
  { value: "PENDING", label: "Waiting for a decision" },
  { value: "REJECTED", label: "Refused" },
  { value: "SUSPENDED", label: "Suspended" }
];

/**
 * The designer roster's standing, as a single-select whose FIRST row is the widest one.
 *
 * `""` first and selected by default, carrying the wording that page has used all along
 * ("Everyone ever empanelled"), because `buildQuery` drops `""` exactly as it drops `null` and the
 * absent parameter is what the server reads as "both standings". The third row is new and is the
 * one this screen could not previously ask for at all: "only the suspended ones" is the query an
 * admin runs when somebody says they have lost access.
 */
export const DESIGNER_STANDING_OPTIONS: RosterOption[] = [
  { value: "", label: "Everyone ever empanelled" },
  { value: "active", label: "Only those who may sign in" },
  { value: "suspended", label: "Only those suspended" }
];

/**
 * THE EIGHT-TIER LADDER PLUS ONE RESERVED ROW, highest tier first.
 *
 * ── IT ITERATES `ROLES_BY_RANK` IN FULL AND MUST NEVER USE `assignableRoles` ─────────────────────
 * `lib/permissions.assignableRoles` narrows the ladder to tiers at or below the caller's own, which
 * is exactly right for the `admitRole` PICKER on the same screen — you cannot grant a tier above
 * your own — and exactly wrong for a FILTER. An admin must be able to filter for rows carrying a
 * tier they could not grant, or every master-admin row becomes invisible to every admin, and the
 * list quietly stops being a complete answer for the person most likely to be auditing it.
 *
 * ── THE ORDER IS THE LADDER'S, AND IT IS SORTED ON THE RANKS ────────────────────────────────────
 * `ROLES_BY_RANK` sorts on the numeric ranks rather than on declaration order, so inserting a tier
 * in the numeric gaps (as INSPECTOR was, at 37) puts it in the right place here without an edit.
 *
 * ── AND IT IS NOT SEARCHABLE ────────────────────────────────────────────────────────────────────
 * Eight roles is exactly `SEARCH_THRESHOLD`, so left to the option count this control would open as
 * a plain list on the day a tier is removed and grow a filter box on the day one is added. It is a
 * closed vocabulary a reader takes in at a glance — the case the threshold exists to separate from
 * a corpus — so `RosterFilterBar` passes `searchable={false}` outright. Android's picker overrides
 * it for the same reason and cites the same number.
 */
export function roleOptions(kind: RosterKind): RosterOption[] {
  const ladder = ROLES_BY_RANK.map((role: UserRole) => ({ value: role, label: ROLE_LABELS[role] }));
  return [
    ...ladder,
    // LAST, and named as what it IS rather than as a tier, because it is not one — it is the
    // absence of one. Same placement and same reason as `WorkshopScopeSelect`'s "Not linked to a
    // workshop" row: it has to be selectable or ticking every tier silently drops a whole class of
    // row, but it does not belong among the tiers in the reading order.
    kind === "access"
      ? {
          value: ADMIT_ROLE_DEFAULT,
          label: "At the default joining tier",
          hint: "No tier was named when this address was admitted"
        }
      : {
          value: ROLE_NEVER_SIGNED_IN,
          label: "Has never signed in",
          hint: "Empanelled, but no account has used this address yet"
        }
  ];
}

/**
 * The served institution vocabulary plus the reserved "none" row.
 *
 * The names come from the server (`GET /designers/roster/institutions`) rather than from the page of
 * rows on screen, because a filter built from one page of a paged list can only ever offer the
 * institutions that page happened to contain — which is rule (iv)'s failure wearing a picker.
 *
 * De-duplicated by value: see {@link INSTITUTION_NONE} for the free-text collision that makes that
 * necessary, and for why the de-duplication is a containment and not a fix.
 */
export function institutionOptions(names: readonly string[]): RosterOption[] {
  const seen = new Set<string>([INSTITUTION_NONE]);
  const rows: RosterOption[] = [];
  names.forEach((name) => {
    const value = name.trim();
    if (!value || seen.has(value)) return;
    seen.add(value);
    rows.push({ value, label: value });
  });
  rows.push({ value: INSTITUTION_NONE, label: "No institution recorded" });
  return rows;
}

/** The date columns each roster can bound, in the order they are offered. §4.1's two enums. */
export function dateFieldOptions(kind: RosterKind): RosterOption[] {
  return kind === "access"
    ? [
        { value: "added", label: "Added to the list" },
        { value: "requested", label: "Access requested" },
        { value: "decided", label: "Decision made" },
        // `joinedAt` is written ONCE: somebody admitted in 2024, suspended, and restored this
        // morning still joined in 2024. It is not "last approved" and must never be labelled as it.
        { value: "joined", label: "Joined the platform" },
        { value: "firstSeen", label: "First signed in" }
      ]
    : [
        { value: "added", label: "Added to the roster" },
        { value: "firstSeen", label: "First signed in" },
        { value: "revoked", label: "Access revoked" }
      ];
}

/**
 * The date presets, as rows.
 *
 * The ids are `SearchFilters`' `RANGE_IDS` — imported, so the two surfaces cannot drift about what
 * "Last 30 days" resolves to — and the labels are that module's, copied byte for byte, because a
 * researcher meeting "Last 30 days" on `/search` and "Past month" here would reasonably wonder
 * whether they mean the same window. A `Record<RangeId, string>` rather than a second array: adding
 * a preset upstream then fails `tsc` here instead of rendering a raw token.
 */
const RANGE_LABELS: Record<RangeId, string> = {
  any: "Any time",
  today: "Today",
  "7d": "Last 7 days",
  "30d": "Last 30 days",
  "90d": "Last 90 days",
  month: "This month",
  year: "This year",
  custom: "Custom range"
};

export const RANGE_OPTIONS: RosterOption[] = RANGE_IDS.map((id) => ({
  value: id,
  label: RANGE_LABELS[id]
}));

/* ────────────────────────────────────────────────────────────────────────────
 * Sorting
 * ──────────────────────────────────────────────────────────────────────────── */

const DIRECTION_PHRASE: Record<SortValues, Record<RosterDir, string>> = {
  date: { desc: "newest first", asc: "oldest first" },
  text: { asc: "A to Z", desc: "Z to A" },
  count: { desc: "most first", asc: "fewest first" },
  enum: { asc: "in standing order", desc: "in reverse standing order" }
};

/** What a direction MEANS on this kind of column, in words a person can act on. */
export function sortDirectionPhrase(values: SortValues, dir: RosterDir): string {
  return DIRECTION_PHRASE[values][dir];
}

/**
 * The direction a click on this column header would produce.
 *
 * Clicking the column that is already sorted FLIPS it. Clicking any other column takes THAT
 * column's own default rather than carrying the current direction across, because "newest first"
 * and "A to Z" are opposite directions and both are the natural first reading of their column:
 * inheriting `desc` from a date onto an email gives Z-to-A, which nobody clicked for.
 */
export function nextSortDir(kind: RosterKind, filters: RosterFilters, column: string): RosterDir {
  const spec = rosterSortSpec(kind, column);
  if (!spec) return filters.dir;
  if (filters.sort === column) return filters.dir === "asc" ? "desc" : "asc";
  return spec.defaultDir;
}

/**
 * The filters with this column sorted. An unknown column is returned unchanged — see
 * {@link rosterSortSpec} for why that is reachable rather than defensive.
 *
 * ⚠ THE CALLER MUST RESET ITS PAGER. A sort change re-orders the whole list, so the rows at
 * `OFFSET 40` are not the rows that were there a moment ago; staying on page 3 lands the reader
 * somewhere arbitrary in a list they have just re-ordered. Every page in this app resets to 1 on a
 * filter change already; this is the same rule for the same reason, and the sort control cannot do
 * it itself because the pager is the page's state.
 */
export function nextRosterSort(kind: RosterKind, filters: RosterFilters, column: string): RosterFilters {
  const spec = rosterSortSpec(kind, column);
  if (!spec) return filters;
  return { ...filters, sort: column as RosterSort, dir: nextSortDir(kind, filters, column) };
}

/**
 * The sort button's title — what a click will DO, not what the column currently is.
 *
 * The current state is carried by `aria-sort` on the `<th>`, which is the ARIA pattern and the
 * first `aria-sort` in this frontend. A control whose name repeats the state and never says the
 * action leaves a reader to guess whether clicking sorts, re-sorts or reverses.
 */
export function sortActionLabel(
  kind: RosterKind,
  filters: RosterFilters,
  column: string,
  label: string
): string {
  const spec = rosterSortSpec(kind, column);
  if (!spec) return label;
  const dir = nextSortDir(kind, filters, column);
  const action = `Sort by ${label}, ${sortDirectionPhrase(spec.values, dir)}`;
  // Said on the control that produces it, because a table opening on ten blank cells in the column
  // you just sorted by reads as a broken screen rather than as the answer to "who has never turned
  // up". Postgres puts NULLs first on `desc`; on this column that IS the outstanding-invitation view.
  return spec.nullable && dir === "desc" ? `${action} — rows with no date sort first` : action;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The wire
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A comma-joined token list in a CANONICAL order, or `undefined` when nothing is chosen.
 *
 * `undefined` and not `""`: `buildQuery` drops both, but `undefined` is what "there is no such
 * filter" looks like everywhere else in this app and `""` is what a blank field sends. Rule (i)
 * lives on this one line.
 *
 * The order is canonical so that the same three ticks cannot produce two different query strings —
 * and therefore two cache entries and two "why did that reload?" — depending on which one the admin
 * ticked first. `SearchFilters.typeList` does the same for the same reason.
 *
 * Comma-joined rather than repeated: the server accepts both spellings deliberately (the web and
 * Android build query strings differently, and "a filter that quietly covered everything because it
 * was spelled the other way would look exactly like the filter not working"), and one value is what
 * `buildQuery` can express.
 */
function tokenList(values: readonly string[], order: readonly string[]): string | undefined {
  const chosen = new Set(values.map((value) => value.trim()).filter(Boolean));
  if (chosen.size === 0) return undefined;
  const ordered = order.filter((token) => chosen.has(token));
  // Anything the caller holds that is not in the canonical order still goes on the wire, at the end.
  // A token this client does not recognise is not this client's to judge: the server answers an
  // unknown one with a 422 that names the valid values, which is a visible refusal, where dropping
  // it here would silently answer a narrower question than the URL asked and look exactly like the
  // filter working.
  const extras = [...chosen]
    .filter((token) => !ordered.includes(token))
    .sort((a, b) => a.localeCompare(b));
  return [...ordered, ...extras].join(",");
}

/** The institution tokens, alphabetical with the reserved "no institution" row last. */
function institutionList(values: readonly string[]): string | undefined {
  const chosen = new Set(values.map((value) => value.trim()).filter(Boolean));
  if (chosen.size === 0) return undefined;
  const named = [...chosen]
    .filter((value) => value !== INSTITUTION_NONE)
    .sort((a, b) => a.localeCompare(b));
  return [...named, ...(chosen.has(INSTITUTION_NONE) ? [INSTITUTION_NONE] : [])].join(",");
}

/** The canonical order of the role tokens for a roster: the ladder, then that roster's reserved row. */
function roleOrder(kind: RosterKind): string[] {
  return [...ROLES_BY_RANK, kind === "access" ? ADMIT_ROLE_DEFAULT : ROLE_NEVER_SIGNED_IN];
}

/**
 * Every key either roster route takes, all optional. `undefined` is ABSENT, which is what the server
 * reads as "do not filter" — see rule (i).
 *
 * `page` and `pageSize` are deliberately NOT here: the pager is the page's state, not the filter
 * bar's, and a filter object that carried a page number would be a filter object that could be
 * restored from a URL onto the wrong page of a list it had just re-filtered.
 */
export type RosterQueryParams = {
  search?: string;
  /** Access only. */
  status?: string;
  /** Designer only. */
  standing?: string;
  roles?: string;
  /** Designer only. */
  institutions?: string;
  dateField?: string;
  dateFrom?: string;
  dateTo?: string;
  sort?: string;
  dir?: RosterDir;
};

/**
 * THE FILTERS AS QUERY KEYS — spread into ONE `buildQuery` call alongside `page` and `pageSize`, so
 * every active filter ANDs into a single request rather than being applied in passes.
 *
 * ── `now` IS A PARAMETER, AND THE DEFAULT IS THE POINT ───────────────────────────────────────────
 * Presets resolve to concrete instants HERE, in the browser, because only the browser knows the
 * reader's clock — and they resolve at REQUEST time, not at pick time, so a screen left open
 * overnight does not keep asking about yesterday. Calling this once and caching the result is the
 * bug that wording exists to prevent. The parameter is there so a test can pin a clock; production
 * passes nothing.
 *
 * ── WHY A KEY THAT DOES NOT BELONG TO A ROUTE IS NEVER SENT TO IT ────────────────────────────────
 * `status` on the designer roster and `institutions` on the access list are not harmless extras: an
 * unknown parameter is either ignored (so the admin's filter silently did nothing) or refused, and
 * the first of those is indistinguishable from the filter being broken. The `kind` gate below is
 * what makes one state object safe to hold for two screens.
 *
 * ── AND WHY `activeOnly` IS NEVER EMITTED ────────────────────────────────────────────────────────
 * The designer route keeps `activeOnly` exactly as it is for a client that has not been updated, and
 * `standing` is the new spelling of the same question. Sending both is a 422 rather than a silent
 * winner, so this bar sends only `standing` — the page's list function must stop sending `activeOnly`
 * in the same change that mounts this bar, or every request 422s the moment "Only those suspended"
 * is chosen.
 */
export function rosterQueryParams(
  kind: RosterKind,
  filters: RosterFilters,
  now: Date = new Date()
): RosterQueryParams {
  const access = kind === "access";
  // `"any"` short-circuits rather than being handed to `resolveRange`, which would answer `{}` for
  // it anyway — the explicit test is what makes it obvious that the DEFAULT state sends no date keys.
  const bounds =
    filters.range === "any"
      ? {}
      : resolveRange(
          { ...EMPTY_SEARCH_FILTERS, range: filters.range, from: filters.from, to: filters.to },
          now
        );
  // A range with no bound is not a filter. "Custom range" with both boxes empty resolves to nothing,
  // and sending a bare `dateField` for it would put a key on the wire that narrows nothing and reads,
  // in a log or a shared link, as a filter that was applied.
  const dated = Boolean(bounds.dateFrom || bounds.dateTo);
  const ordered = filters.sort === ROSTER_DEFAULT_SORT && filters.dir === ROSTER_DEFAULT_DIR;

  return {
    search: filters.search.trim() || undefined,
    status: access ? tokenList(filters.status, ACCESS_STATUS_TOKENS) : undefined,
    standing: access ? undefined : filters.standing || undefined,
    roles: tokenList(filters.roles, roleOrder(kind)),
    institutions: access ? undefined : institutionList(filters.institutions),
    dateField: dated ? filters.dateField : undefined,
    dateFrom: dated ? bounds.dateFrom : undefined,
    dateTo: dated ? bounds.dateTo : undefined,
    // The server's own default pair is left off entirely, so the default state of these screens
    // produces the empty query string and a link to an unfiltered roster is just the path. Anything
    // else is sent as a PAIR: `dir` alone would be read against a column this client did not name,
    // and `sort` alone would be defaulted to `desc` by the route — which is Z-to-A on `email`.
    sort: ordered ? undefined : filters.sort,
    dir: ordered ? undefined : filters.dir
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The URL round-trip
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE FILTERS AS LINK PARAMETERS, so a filtered roster is a link an admin can paste to a colleague.
 *
 * Same shape as `SearchFilters.filtersToLinkParams` and `workshopScopeLinkParams`, and the keys are
 * the wire's keys wherever there is one, so a link and a request say the same words.
 *
 * ── THE DATE IS CARRIED AS THE PRESET, NEVER AS THE RESOLVED INSTANTS ────────────────────────────
 * "Last 30 days" pasted to a colleague tomorrow must mean THEIR last 30 days. Freezing the resolved
 * window into the link would hand them a fixed fortnight-old range that still calls itself "Last 30
 * days" in the control — a filter whose label and behaviour disagree, on a screen somebody is using
 * to decide about a person's access. `range`/`from`/`to` go in the link; `resolveRange` runs again
 * on their clock.
 */
export function rosterLinkParams(
  kind: RosterKind,
  filters: RosterFilters
): Record<string, string | undefined> {
  const access = kind === "access";
  return {
    search: filters.search.trim() || undefined,
    status: access ? tokenList(filters.status, ACCESS_STATUS_TOKENS) : undefined,
    standing: access ? undefined : filters.standing || undefined,
    roles: tokenList(filters.roles, roleOrder(kind)),
    institutions: access ? undefined : institutionList(filters.institutions),
    // Carried whenever it is not the default, so a deliberate choice of column survives the paste
    // even before a range has been set — but the default state still links to a bare path.
    dateField: filters.dateField === "added" ? undefined : filters.dateField,
    range: filters.range === "any" ? undefined : filters.range,
    from: filters.range === "custom" ? filters.from || undefined : undefined,
    to: filters.range === "custom" ? filters.to || undefined : undefined,
    sort: filters.sort === ROSTER_DEFAULT_SORT ? undefined : filters.sort,
    dir:
      filters.sort === ROSTER_DEFAULT_SORT && filters.dir === ROSTER_DEFAULT_DIR
        ? undefined
        : filters.dir
  };
}

/**
 * Repeated parameters AND one comma-joined value, both accepted — the same two spellings the server
 * takes, so a link built by either client resolves in either client.
 */
function readTokens(params: URLSearchParams, key: string): string[] {
  const seen = new Set<string>();
  params.getAll(key).forEach((raw) => {
    raw
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean)
      .forEach((value) => seen.add(value));
  });
  return [...seen];
}

/**
 * The inverse of {@link rosterLinkParams} — a filtered roster restored from a URL.
 *
 * ── UNKNOWN TOKENS ARE KEPT; UNKNOWN MODES ARE NOT, AND THE ASYMMETRY IS DELIBERATE ──────────────
 * A token in a multi-select (`roles`, `status`, `institutions`) is a FILTER VALUE, and this client
 * is not the authority on which of them are valid — the server answers an unknown one with a 422
 * that names the valid values, where dropping it here would quietly answer a narrower question than
 * the link asked and look exactly like the filter working. That is `workshopScopeFromSearchParams`'
 * rule verbatim: "unknown ids are kept — the server judges them."
 *
 * A single-select's value (`range`, `dateField`, `sort`, `dir`, `standing`) is the CONTROL'S OWN
 * MODE, and a mode with no row to render is a control stuck on a value the reader can see the
 * effect of and cannot change. Those fall back to the default, exactly as
 * `SearchFilters.filtersFromSearchParams` falls back for `range`.
 *
 * ── AND `sort` IS VALIDATED AGAINST THE KIND, NOT AGAINST BOTH TABLES ────────────────────────────
 * `?sort=attempts` is a good access URL and a meaningless designer one. Sending it anyway would 422
 * the whole list over a token nothing on the screen even offers.
 */
export function rosterFiltersFromSearchParams(
  kind: RosterKind,
  params: URLSearchParams
): RosterFilters {
  const base = emptyRosterFilters(kind);
  const access = kind === "access";

  const rangeParam = (params.get("range") ?? "").trim();
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  const range: RangeId = (RANGE_IDS as readonly string[]).includes(rangeParam)
    ? (rangeParam as RangeId)
    : from || to
      ? "custom"
      : "any";

  const dateFieldParam = (params.get("dateField") ?? "").trim();
  const dateFields: readonly string[] = access ? ACCESS_DATE_FIELDS : DESIGNER_DATE_FIELDS;
  const dateField = (
    dateFields.includes(dateFieldParam) ? dateFieldParam : base.dateField
  ) as RosterDateField;

  const sortParam = (params.get("sort") ?? "").trim();
  const sort = (rosterSortSpec(kind, sortParam) ? sortParam : ROSTER_DEFAULT_SORT) as RosterSort;
  const dirParam = (params.get("dir") ?? "").trim();
  // The column's own default rather than `ROSTER_DEFAULT_DIR`, so `?sort=email` with no `dir`
  // restores A-to-Z — the order the header would have produced — instead of Z-to-A.
  const dir: RosterDir =
    dirParam === "asc" || dirParam === "desc"
      ? dirParam
      : (rosterSortSpec(kind, sort)?.defaultDir ?? ROSTER_DEFAULT_DIR);

  const standingParam = (params.get("standing") ?? "").trim();
  const standing = (DESIGNER_STANDING_TOKENS as readonly string[]).includes(standingParam)
    ? (standingParam as DesignerStandingToken)
    : "";

  return {
    search: params.get("search") ?? "",
    status: access ? (readTokens(params, "status") as AccessStatusToken[]) : [],
    standing: access ? "" : standing,
    roles: readTokens(params, "roles"),
    institutions: access ? [] : readTokens(params, "institutions"),
    dateField,
    range,
    from,
    to,
    sort,
    dir
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Clear-all
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Is anything NARROWING this list?
 *
 * Read off the CONTROLS, not off the wire, because this is what decides whether "Clear every filter"
 * is on screen: a reader who has set the period to "Custom range" and typed no dates has visibly
 * changed a control and must be able to put it back, even though that state sends no date keys.
 *
 * THE SORT IS NOT A FILTER AND IS NOT COUNTED. Counting it would put a "Clear every filter" button
 * on screen that, when pressed, changed nothing a reader could see — the sort would stay, because
 * {@link clearRosterFilters} deliberately keeps it.
 */
export function hasActiveRosterFilters(kind: RosterKind, filters: RosterFilters): boolean {
  const access = kind === "access";
  return Boolean(
    filters.search.trim() ||
      (access && filters.status.length > 0) ||
      (!access && filters.standing) ||
      filters.roles.length > 0 ||
      (!access && filters.institutions.length > 0) ||
      filters.range !== "any"
  );
}

/**
 * Every filter back to its empty state — WITH THE SORT LEFT EXACTLY AS IT IS.
 *
 * The button says "Clear every filter", and an order is not a filter: it narrows nothing and hides
 * nobody. An admin who has sorted by "first signed in" to find outstanding invitations and then
 * clears a search is still asking that question; throwing their order away as well is a second,
 * unasked-for change dressed up as tidying.
 */
export function clearRosterFilters(kind: RosterKind, filters: RosterFilters): RosterFilters {
  return { ...emptyRosterFilters(kind), sort: filters.sort, dir: filters.dir };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The two cuts these controls can cause, and neither is visible in the rows
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE INSTITUTION VOCABULARY IS CAPPED BY THE SERVER, AND THE CAP HAS TO BE ON SCREEN.
 *
 * `GET /designers/roster/institutions` reads one row past its cap and reports `truncated`, so the
 * flag is exact. Past that point an institution simply has no row in the picker: an admin looking
 * for it finds nothing, and "not in the list" reads as "nobody is from there" — absence read as
 * non-existence, which is this repository's most repeated bug class.
 *
 * WHY THE NUMBER IS `offered` AND NOT A CONSTANT. Never print a cap you did not read. The endpoint's
 * `take` is the server's to change, and a stated cap that is not the enforced cap is worse than no
 * sentence at all — so the number printed is the count of names this control was actually handed,
 * which is a fact this client can see. The flag decides whether there is anything to say; the count
 * only decides the wording.
 *
 * WHY NOT `flagCutNotice` FROM `components/data/cappedList.ts` — the same shape of argument
 * `deletedWorkshops.ts` makes about `cappedListNotice`'s pager arm, and the same conclusion. Its
 * no-term arm ends "The box in this picker only filters what is already listed", which is true and
 * unhelpful, and its instruction is "search for a name above to reach them" — pointing at a box that
 * searches ROSTER ROWS, not institution names. The move that actually reaches a designer whose
 * institution is past the cut is to type the institution into the roster search, which is a real,
 * different instruction: `search` is OR-ed over `institution` on that route. So the sentence is
 * worded here, once, and says the thing that works.
 */
export function institutionCutNotice(truncated: boolean | undefined, offered: number): string {
  // `undefined` is the wire's shape on a deployment that predates the flag: `apiFetch` casts, it
  // does not validate, so an absent field must read as "nothing to say" rather than as a cut. Same
  // guard, same reason, as `cappedList.flagCutNotice`'s.
  if (!truncated) return "";
  if (offered <= 0) {
    return "There are more institutions than this list can hold, so some cannot be ticked here. Type the institution into the search box above instead — it is searched on the server.";
  }
  return `Only the first ${offered} institutions are offered here and there are more, so an institution past that point cannot be ticked. Type its name into the search box above instead — it is searched on the server, over the whole roster.`;
}

/**
 * THE DESIGNER ROLE FILTER READ A BOUNDED NUMBER OF ACCOUNTS, AND SOME MATCHING DESIGNERS ARE
 * THEREFORE MISSING FROM EVERY PAGE OF THIS ANSWER.
 *
 * `DesignerRoster` has no role column and no user relation, so filtering by tier means reading the
 * ACCOUNTS that hold those tiers and folding their emails into the roster query's WHERE. That read
 * is bounded — an unbounded one is not a thing to ship — and when it is cut the consequence is not a
 * short page: it is a MATCHING DESIGNER VANISHING from the list as though they had never been
 * empanelled, on every page, for every filter naming those roles.
 *
 * ── WHY NOT `flagCutNotice(truncated, "designers", term)`, WHICH IS WHAT §4.4 SPECIFIES ──────────
 * Because both of its arms give advice that does not work here, and this is a different fact from
 * the one it words. Its arms are "narrow the search above" and "search for a name above to reach
 * them" — but the cut happened UPSTREAM of the search, in the account read. Narrowing the roster
 * search shrinks the roster query and does not put a single unread account back, so the missing
 * designers stay missing and the reader has been sent to do something that cannot help. That is the
 * shape of defect this whole cluster of rules exists to close, so the sentence is worded here
 * instead, with the move that DOES work: name fewer tiers, and fewer accounts have to be read.
 * `deletedWorkshops.deletedWorkshopsNotice` set the precedent for a screen wording its own cut when
 * the shared arm names a control that will not help; this is that, one screen along. If the shared
 * module later grows an arm for an upstream cut, this should become a call to it.
 *
 * ── THE NUMBER IS OPTIONAL, AND SAYING NOTHING IS NOT ────────────────────────────────────────────
 * The wire carries a boolean; the limit is the server's constant. Where a caller has read the limit
 * it is printed, and where it has not the fact is stated without it — `queueCutNotice` makes exactly
 * this trade and states the reason: "the honest fallback is the fact WITHOUT the numbers. Saying
 * nothing at all would be the one unacceptable answer."
 */
export function roleMatchCutNotice(truncated: boolean | undefined, limit?: number): string {
  if (!truncated) return "";
  const bound =
    typeof limit === "number" && Number.isFinite(limit) && limit > 0
      ? `more than ${limit} accounts`
      : "more accounts than this filter reads in one pass";
  return `Some designers holding the selected tiers are missing from this list. Matching a tier means reading the accounts that hold it, and ${bound} do — the ones past that point were not read, so their roster rows cannot appear on any page of this filter. Choosing fewer tiers reads fewer accounts and gives a complete answer.`;
}
