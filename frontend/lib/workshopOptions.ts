/**
 * THE ONE PLACE A WORKSHOP BECOMES A `SelectOption` — for BOTH tables, and for nothing else.
 *
 * ── WHY A MODULE, WHEN "map rows to {value,label}" IS THREE LINES ───────────────────────────────
 *
 * Because it is three lines written eleven times, and the eleven do not agree. Counted on
 * 2026-08-29 and tabulated in `DROPDOWN_DESIGN.md` §1.2, the app currently ships SIX label shapes
 * for one question — `title` alone (seven controls), `title · date` (seven), `title · date` plus a
 * `workshopCode` hint (one), `title` plus a `craft · cluster · date` hint (three), `title · place`
 * (two), `title · date · place — standing` (one) — FIVE sort orders, NINE different strings for the
 * "no workshop" row, and three incompatible answers to where the filter box points. None of that is
 * visible in a screenshot of any single control. It is only visible when a designer meets two of
 * them, which on four of the record forms happens in one glance, because those forms mount both
 * pickers one directly under the other.
 *
 * ── THE FACT THAT SHAPES THIS WHOLE FILE: "WORKSHOP" NAMES TWO TABLES ───────────────────────────
 *
 * `Workshop` is the ordinary field/training visit — gated by `WorkshopAssignment` through
 * `resolve_workshop_access`, carrying a submission window and a late-submission dialog.
 * `DesignWorkshop` is the 22-stage design-and-prototype record — gated by `load_workshop_or_404`
 * through creator / admin / `DesignWorkshopViewer`, with no window and no roster. Two tables, two
 * access systems, two option scopes, and only one of them can have a submission pre-flight at all:
 * `components/forms/DesignWorkshopSelect.tsx` carries the full argument, and its last line is that
 * adding a pre-flight to the other *"would be a request that could only ever say yes"*.
 *
 * So there are TWO builders below and there will never be one. What is unified here is the CONTROL
 * and the VOCABULARY — the label shape, the group headings, the sort, the four "none" strings, the
 * four state sentences, the truncation sentence — and never the LISTS. A single builder over a
 * union of both tables would be a picker offering a designer two rows that mean different things,
 * gate differently, and save into different columns, with nothing on screen to say which is which.
 *
 * ── WHAT THIS MODULE DOES NOT DO ────────────────────────────────────────────────────────────────
 *
 * IT DOES NOT FETCH. Every function here is synchronous and, apart from the one named exception
 * ({@link deviceLooksOffline}, which reads `navigator` and says so), a pure function of its
 * arguments. That is not tidiness — this repository has no React renderer in its devDependencies,
 * Playwright is the whole of it, so a judgement that lives inside a hook cannot be asserted at all.
 * `components/ui/selectFilter.ts` was split out of `SearchableSelect` for exactly this reason and
 * states it at the top of the file. The rows, the total, the debounce, the generation counter and
 * the page size belong to the caller; what arrives here is an answer, and what leaves is a list of
 * options plus an honest account of what the answer left out.
 *
 * IT DOES NOT BUILD THE "NONE" ROW. See {@link NO_DESIGN_WORKSHOP} for which layer owns it and why
 * two layers owning it is the specific bug this file exists to stop.
 *
 * Specification: `DROPDOWN_DESIGN.md` §2.2 (this module), §2.3 (the label), §2.4 (the grouping),
 * §2.5 (the sort), §2.6 (archived / closed / deleted), §2.7 (the "none" row), §2.8 (truncation),
 * §2.9 (off-page recovery), §3.5 (the state sentences).
 */

import {
  CAP_HINT_WITHOUT_SEARCH,
  CAP_HINT_WITH_SEARCH,
  RENDER_CAP,
  SEARCHING_LABEL,
  truncationSentence,
  type SelectOption
} from "@/components/ui/selectFilter";

/* ────────────────────────────────────────────────────────────────────────────
 * The two tables
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Which of the two tables a control is picking from. Never a union of both — see the header.
 *
 * It exists as a type rather than as two duplicated sets of sentence builders because the four
 * state sentences of §3.5 differ between the tables in exactly one word (the noun), and writing
 * them twice is how "design workshops" and "Design Workshops" and "design and prototype workshops"
 * all end up on screen at once.
 */
export type WorkshopTable = "design" | "field";

/**
 * The plural a state sentence is written about.
 *
 * "workshops" and not "field workshops" for the ordinary table, because that is what every screen
 * in this app already calls it — `/workshops`, "Workshops" in the nav, "Not linked to a workshop"
 * on the picker. "Field workshop" is a word this document invented to tell the two tables apart in
 * prose and it has never appeared in front of a designer; introducing it in a sentence that only
 * ever renders when something has gone wrong is the worst possible moment to teach new vocabulary.
 */
function nounFor(table: WorkshopTable): string {
  return table === "design" ? "design workshops" : "workshops";
}

/* ────────────────────────────────────────────────────────────────────────────
 * §2.7 — the four "none" constants, and which layer draws the row
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "" MEANS: this record is not filed under a design workshop.
 *
 * ── THE ROW IS THE PRIMITIVE'S AND THE STRING IS THIS MODULE'S, AND THAT SPLIT IS THE RULING ────
 *
 * `SearchableSelect` gained `noneLabel` in the same pass that produced this file: pass it a string
 * and it draws a first, ungrouped row carrying `value: ""`, exempt from the render cap, hidden
 * while a filter term is active, and read back on the trigger when the value is empty. **So the
 * builders below never prepend a `value: ""` row of their own, and a caller must not either.**
 *
 * The primitive already stands down if it finds a `""` row in the array it was handed
 * (`noneOptionFor`), so a half-done migration renders correctly rather than oddly — but standing
 * down is a safety net for the migration and not a second supported spelling. Two layers each
 * entitled to draw "none" is R1's forbidden second state wearing a dropdown: two rows sharing the
 * React key `""`, a duplicate-key warning, a list offering the same answer twice, and a control
 * that cannot say which of the two is selected. One layer draws it. It is the primitive.
 *
 * ── AND WHY FOUR STRINGS RATHER THAN ONE ────────────────────────────────────────────────────────
 *
 * Because "" means four genuinely different things across the twenty-one controls, and collapsing
 * them would be the same mistake in the other direction. This one is the filing label on a record
 * form. {@link NO_FIELD_WORKSHOP} is the other table's. {@link ATTACH_LATER} is a copy operation
 * where the answer can be deferred rather than declined. {@link TYPE_DETAILS_INSTEAD} is a create
 * flow where free text is the alternative to a link. The nine strings shipping today collapse to
 * these four and to nothing smaller.
 *
 * **`"All workshops"` IS NOT ON THIS LIST AND MAY NEVER BE ADDED.** A control that FILTERS a screen
 * says "everything" by ABSENCE — an empty selection — and not by a row that can be picked (R1,
 * `components/WorkshopScopeSelect.tsx`, whose `queryValue` returns `undefined` rather than `""`).
 * A filter with a none row has two spellings for one state and can no longer tell a default from a
 * deliberate choice. Filters offer their own "All records" button that sets `[]`.
 */
export const NO_DESIGN_WORKSHOP = "Not filed under a design workshop";

/** "" MEANS: this record is not linked to an ordinary workshop. See {@link NO_DESIGN_WORKSHOP}. */
export const NO_FIELD_WORKSHOP = "Not linked to a workshop";

/**
 * "" MEANS: not yet — ask me again later.
 *
 * The copy/reuse flows only. It is not a refusal and must not be worded as one: a designer who has
 * not decided which workshop a reused questionnaire belongs to is answering "later", and offering
 * them "Not linked to a workshop" invites them to record a decision they have not made.
 */
export const ATTACH_LATER = "Don't attach it yet";

/**
 * "" MEANS: there is no workshop record for this — the details are typed in below.
 *
 * The create flow alone, where the fields under the picker are the alternative to the link. It says
 * what to do next, which the other three do not need to because on a record form the answer is
 * simply "nothing".
 */
export const TYPE_DETAILS_INSTEAD = "Do not link a workshop — type the details below";

/* ────────────────────────────────────────────────────────────────────────────
 * §2.4 — the group headings
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The recovered off-page row's heading — see {@link OffPageIntent}.
 *
 * It has its own heading rather than being folded into "Open" because that is what keeps the
 * sentence under the control true. A picker that says "these are the workshops open to you" and
 * then quietly includes one that is NOT open to you, because the record already sits in it, is
 * lying about its own scope in order to be helpful. The heading resolves it: everything under
 * "Open" is in scope, and this row is separately labelled as a fact about the record.
 */
export const GROUP_ON_THIS_RECORD = "Already on this record";

/** `DRAFT` / `IN_PROGRESS` / `COMPLETE`, or an ordinary workshop whose window has not closed. */
export const GROUP_OPEN = "Open";

/** `SUBMITTED` / `ARCHIVED`. Design workshops only — the other table has no such states. */
export const GROUP_SUBMITTED_AND_ARCHIVED = "Submitted and archived";

/** `endDate` in the past. Ordinary workshops only — the other table has no window. */
export const GROUP_ENDED = "Ended";

/**
 * What a workshop with no title reads as.
 *
 * Never an id, and never blank. Everything from `workshopCode` down on a `DwSummary` is
 * DENORMALISED from stage 1 and is null until stage 1 has been saved, so a freshly created workshop
 * legitimately arrives here with a title and nothing else — and a workshop created and abandoned
 * before stage 1 arrives with not even that. A blank row in a listbox is a row a reader cannot
 * describe, cannot search for, and cannot tell from the row above it.
 */
export const UNTITLED_WORKSHOP = "Untitled workshop";

/**
 * The page size EVERY workshop picker asks for. `RENDER_CAP`, and never a round number.
 *
 * ONE NUMBER GOVERNS THE FETCH AND THE RENDER, which is the only arrangement in which two
 * truncation sentences with two different totals cannot both be true at once. `/design-review`
 * fetched 100 workshops into a control that draws 80 and printed "the first 80 of 100" inside the
 * panel with "the first 100 of 350" underneath it, plus a dead band between 81 and 100 where the
 * page said nothing while the panel silently dropped rows. Seven controls still have that shape.
 * `selectFilter.ts` exports `RENDER_CAP` for exactly this and says so; this alias exists so a
 * workshop caller has one name to reach for and cannot reach for `100` by habit.
 */
export const WORKSHOP_OPTION_PAGE_SIZE = RENDER_CAP;

/* ────────────────────────────────────────────────────────────────────────────
 * The rows these builders accept
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The fields of a `DwSummary` this module reads — declared structurally rather than imported.
 *
 * `lib/designWorkshops.ts` is three thousand lines and `DwSummary` carries thirty keys, of which
 * nine matter here. Naming those nine does three things a `Pick<DwSummary, …>` would not: it keeps
 * a pure, testable module from depending on the API layer, it lets a spec build a fixture out of
 * nine fields instead of thirty, and — because the builder is generic over `Row extends
 * DesignWorkshopRow` — it still type-checks the real `DwSummary` at every call site, which is where
 * a drift in the wire shape would need to be noticed anyway.
 */
export type DesignWorkshopRow = {
  id: string;
  title: string;
  /** `DwStatus`, widened to `string` for the same reason the wire type widens it: a server one
   *  release ahead can legitimately send a value this build has never heard of. */
  status: string;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  startDate: string | null;
  createdAt: string | null;
  /** Set means soft-deleted. See {@link designWorkshopOptions} for the one row this does not hide. */
  deletedAt?: string | null;
};

/** The fields of a `Workshop` this module reads. Same argument as {@link DesignWorkshopRow}. */
export type FieldWorkshopRow = {
  id: string;
  title: string;
  place?: string | null;
  date?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  createdAt?: string | null;
};

/* ────────────────────────────────────────────────────────────────────────────
 * §2.9 — off-page value recovery: a required parameter, never a default
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHAT THIS CONTROL DOES ABOUT A STORED VALUE THAT IS NOT IN THE LIST. **Required. No default. Not
 * inferable. Read this before you pick one.**
 *
 * ── THE TWO ANSWERS, AND WHY A MODULE CANNOT CHOOSE BETWEEN THEM ────────────────────────────────
 *
 * `"recover"` merges the record's stored workshop in — fetched by id through the open single-read
 * route, OUTSIDE the list's access scope — and draws it under {@link GROUP_ON_THIS_RECORD}. That is
 * `components/forms/WorkshopSelect.tsx`'s ruling and its argument is that *"withholding it does not
 * withhold anything… hiding the row would convert a read-only fact into a wrong write"*: the record
 * is already in that workshop, the reader can see it on the record page, and a picker that omits it
 * shows a blank box over a filed record and invites somebody to "fix" it by picking a different
 * one. It is re-implemented by hand in three places today.
 *
 * `"refuse"` merges nothing and holds the action until the server has confirmed the destination is
 * open. That is `components/designworkshop/AdoptLocalDraftDialog.tsx`'s ruling, and its argument is
 * that adoption is ONE-WAY AND UNREPEATABLE: a recovered row there is not a fact about a record, it
 * is a DESTINATION, and a stale or out-of-scope one files a fortnight of fieldwork against an id
 * this account cannot open, with nothing in either client able to undo it.
 *
 * The two differ on a fact only the caller holds: **whether this control describes a read that is
 * already true, or authorises a write that is not yet.** No property of the rows, the value, the
 * table or the endpoint distinguishes them, so a default would not be a sensible fallback — it
 * would be this module silently picking one of two behaviours for around twenty call sites, at
 * least one of which it would pick wrongly, invisibly, on the one control where the wrong answer is
 * unrecoverable.
 *
 * ── AND IT IS ALSO WRONG MECHANICALLY, WHICH IS THE HALF THAT SURPRISES PEOPLE ───────────────────
 *
 * The tempting default is not "recover" or "refuse" at all — it is "detect": notice that `value` is
 * not among `rows` and act. That cannot work here and would not work anywhere in this app, because
 * **options arrive over the network.** For the first second or so of every mount `rows` is empty,
 * so EVERY value is unmatched, and a control that acted on the mismatch would spend that second
 * either synthesising a row out of an id it knows nothing about or reporting the record's own
 * workshop as missing — on a rural connection, for rather longer than a second. Recovery is a
 * SECOND REQUEST the caller makes and hands in here; absence of an answer is spelled `row: null`
 * and means "not yet", never "not there".
 *
 * `"refuse"` carries no `row` field at all, deliberately: a caller that has decided to refuse
 * cannot then pass a row by accident, and the decision is legible in the call site rather than in a
 * boolean two arguments away.
 */
export type OffPageIntent<Row> =
  | { mode: "refuse" }
  /** `row: null` means "the by-id read has not answered yet, or there is no stored value". */
  | { mode: "recover"; row: Row | null };

/* ────────────────────────────────────────────────────────────────────────────
 * The list, as the caller holds it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHAT THE READ ANSWERED — three states, and the middle one is the whole reason this type exists.
 *
 * A caller holding `rows: Row[] | null` cannot tell a read that FAILED from a read that answered
 * NOTHING, and every caller in this app that holds one gets it wrong in the same direction.
 * `DesignWorkshopSelect.tsx` is the reference case: `listDesignWorkshops(...).catch(() => null)`
 * followed by `setRows(page?.items ?? [])` turns a timeout into an empty array, which then draws
 * *"You are on no design workshop yet. An administrator can add you to one."* — a confident claim
 * about a grant table, made from a request that never arrived, telling a designer to go and ask an
 * administrator for access they already have.
 *
 * `total` is `null` for a route that cannot count. Only one does — `GET /workshops/requestable`
 * returns a bare array with no envelope — and that is a server defect with a fix already specified;
 * until it lands, `truncated` is how a route says "there were more" without saying how many, which
 * is the split `selectFilter.ts::unknownTotalNoticeSentence` exists to print.
 */
export type WorkshopListState<Row> =
  | { kind: "loading" }
  | { kind: "failed" }
  | {
      kind: "ok";
      rows: readonly Row[];
      /** The corpus size the route reported, or `null` where the route cannot say. */
      total: number | null;
      /** The route said it had more than it sent, without saying how many. */
      truncated?: boolean;
    };

/**
 * What the builders return: the options, and an honest account of what the answer left out.
 *
 * ── WHY NOT JUST `SelectOption[]` ───────────────────────────────────────────────────────────────
 *
 * Because R4 — *every cap, truncation or narrowing is stated on screen, with the number* — is
 * unenforceable if the only thing that crosses this boundary is an array. A caller handed eighty
 * options out of a hundred and ninety-six workshops has no way to know it, and thirteen controls in
 * this app are in precisely that position today: 196 rows in the table, 100 fetched, 80 drawn,
 * nothing said, on five screens including `/search` and `/map`. Handing back the counts alongside
 * the rows is what makes the sentence possible to write; {@link workshopCutSentence} is what makes
 * it impossible to write differently in two places.
 *
 * NONE OF THESE COUNTS INCLUDE THE "none" ROW OR THE RECOVERED ROW. Both are drawn and neither is a
 * row of the corpus this sentence is counting, and folding either in produces the off-by-one that a
 * reader checks their own counting against. The recovered row is reported separately as
 * {@link WorkshopOptionSet.recovered} and reaches the sentence as "plus 1 already selected", which
 * is what it is.
 */
export type WorkshopOptionSet = {
  /**
   * Every row, in the group order of §2.4 and the sort order of §2.5, ready to hand straight to a
   * `Dropdown`. There is no `value: ""` row here — see {@link NO_DESIGN_WORKSHOP}.
   */
  options: SelectOption[];
  /**
   * Workshop rows from the LIST that are drawn. Excludes the recovered off-page row.
   *
   * Read off {@link WorkshopOptionSet.options} and never off the answer's row count, because the
   * two part company at exactly one point: a full page PLUS a recovered row is `RENDER_CAP + 1`
   * options into a control that draws `RENDER_CAP`, and `assemble` trims it. A `drawn` taken from
   * the answer would then be one more than the panel shows, which is the off-by-one a reader checks
   * their own counting against.
   */
  drawn: number;
  /** The corpus size the route reported, or `null` where the route cannot say. */
  total: number | null;
  /** The route had more rows than it sent, whether or not it said how many. */
  truncated: boolean;
  /** `total - drawn`, or 0 where the total is unknown. How many rows the reader is not seeing. */
  cut: number;
  /** The stored off-page workshop was recovered and is in `options` under "Already on this record". */
  recovered: boolean;
};

/* ────────────────────────────────────────────────────────────────────────────
 * §2.3 — the label format, one answer
 * ──────────────────────────────────────────────────────────────────────────── */

/** Blank, whitespace-only and null all mean "not there". A trimmed string, or "". */
function present(value: string | null | undefined): string {
  return typeof value === "string" ? value.trim() : "";
}

/**
 * The first of these that is actually there.
 *
 * §2.3 writes the design workshop's third hint field as `clusterName ?? state`, and `??` alone is
 * not what it means. Both columns are DENORMALISED off stage 1 by `promoted_values()`, and a stage
 * field that was filled and then cleared comes back as `""` rather than as null — so `??` keeps the
 * empty string, the blank is then dropped by the join filter, and the row loses its place entirely
 * while `state` sat there the whole time with the answer in it.
 */
function firstPresent(...values: Array<string | null | undefined>): string {
  for (const value of values) {
    const trimmed = present(value);
    if (trimmed) return trimmed;
  }
  return "";
}

/**
 * The day a workshop ran: the first ten characters of an ISO string.
 *
 * A SLICE AND NOT `formatDate`, matching what both clients' most recent pickers already draw. A
 * localised "28 Aug 2026" is a better sentence and a worse hint: it is longer on a handset row where
 * the hint shares one line with a craft and a cluster, it sorts differently from the string the row
 * was sorted by, and it cannot be typed. `2026-08-28` is what a designer reads off a workshop card
 * and what they type into the box, and `hint` is searched as well as shown.
 */
function day(iso: string | null | undefined): string {
  const trimmed = present(iso);
  return trimmed ? trimmed.slice(0, 10) : "";
}

/**
 * `label` is the title ALONE, and everything that tells two workshops apart goes in `hint`.
 *
 * ── WHY, GIVEN THAT SEVEN CONTROLS CURRENTLY FOLD THE DATE INTO THE LABEL ───────────────────────
 *
 * `filterOptions` ranks a label-prefix match above a word-prefix above a mid-word above a hint
 * match. Folding the date into the label gives every row in the list a shared suffix, which demotes
 * nothing and helps nobody; it also makes the label the wrong length for a handset row and leaves
 * nowhere for a third fact to go, which is how `title · date · place — standing` happened. Keeping
 * the title alone is what makes typing a workshop's title beat a coincidental craft match, and
 * because `hint` is SEARCHED as well as shown, nothing put there becomes unreachable.
 *
 * ── AND WHY `workshopCode` IS NOT IN THE HINT, THOUGH ONE CONTROL PUTS IT THERE TODAY ───────────
 *
 * It is a code an admin reads off a join card, not a fact that tells two workshops apart on screen,
 * and a phone row has no space for it. It stays reachable because the server's `search` already
 * covers `workshopCode` alongside title, craft and cluster, and every one of these pickers is
 * specified to point its box at that search.
 */
function labelFor(title: string | null | undefined): string {
  return present(title) || UNTITLED_WORKSHOP;
}

/** The hint: the present members, joined by the middle dot both clients use everywhere else. */
function hintFrom(parts: Array<string | null | undefined>): string | undefined {
  const joined = parts.map(present).filter(Boolean).join(" · ");
  return joined || undefined;
}

/* ────────────────────────────────────────────────────────────────────────────
 * §2.5 — the sort order, one answer
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A row on its way to becoming an option: the option itself, its heading, and the three keys it is
 * ordered by.
 */
type Classified = {
  option: SelectOption;
  heading: string;
  /** `startDate ?? …` — the date the workshop HAPPENED. "" when nothing dates it. */
  occurrence: string;
  title: string;
  id: string;
};

/**
 * By occurrence, NEWEST FIRST; then title ascending; then id ascending.
 *
 * ── NEVER BY CREATION, AND THE REASON IS ALREADY WRITTEN IN THIS REPOSITORY ─────────────────────
 *
 * *"A workshop entered into the system last is not the workshop that ran last."*
 * (`components/forms/WorkshopSelect.tsx`, which fixed this for ordinary workshops and re-exports
 * `workshopOccurrenceDate` into three consumers rather than let them reimplement it.) Every
 * `DesignWorkshop` picker in the app currently inherits the server's `createdAt desc` and NOT ONE
 * re-sorts, so on that table "most recent" has meant most recently typed in — which on a backlog
 * import is the oldest workshop in the list, sitting at the top of a control whose first row is
 * what a hurried designer picks.
 *
 * ── THE TWO TIE-BREAKS ARE NOT DECORATION ───────────────────────────────────────────────────────
 *
 * A great many workshops share an occurrence date — a five-day cluster visit is five rows with one
 * `startDate`, and rows imported in a batch share the day they were created. Without a total order
 * the list reshuffles between two renders of the same data, so the row under the reader's cursor is
 * not the row that was there when they started moving towards it. Title then id, both ascending,
 * because id is the only key guaranteed unique and it is therefore what makes the order total.
 *
 * ISO-8601 strings compare chronologically, which is why this is `localeCompare` on strings and not
 * `Date.parse`; an unparseable or absent date folds to "" and sorts LAST rather than becoming
 * `NaN` and sorting arbitrarily.
 */
function byOccurrenceThenTitleThenId(a: Classified, b: Classified): number {
  return (
    b.occurrence.localeCompare(a.occurrence) ||
    a.title.localeCompare(b.title) ||
    a.id.localeCompare(b.id)
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * §2.4 — the grouping, one answer, all-or-nothing per render
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Buckets in the fixed order, each internally sorted, flattened — and the headings applied to every
 * row or to none.
 *
 * ── WHY THE ORDER IS CARRIED BY THE ARRAY AND NOT BY A COMPARATOR ON `group` ────────────────────
 *
 * `selectFilter.ts::groupRows` buckets by FIRST APPEARANCE with ungrouped rows first, and it does
 * that deliberately so a caller's ordering still governs and a filtered list cannot reshuffle its
 * own headings. So the reading order of the headings IS the order rows are emitted in, and the way
 * to get "Already on this record", then "Open", then the closed class is to emit them in that
 * order. No primitive change, and no second place where heading order is decided.
 *
 * ── ALL-OR-NOTHING, WHICH IS THE PART THAT LOOKS LIKE A BUG UNTIL YOU HIT IT ────────────────────
 *
 * If ANY row needs a heading, EVERY row gets one. Group a few and leave the rest bare and the bare
 * ones are drawn ABOVE all the headings — that is what "ungrouped first" means — so the open
 * workshops would render as a fourth, unnamed category sitting above "Open", and a reader would
 * reasonably conclude those rows are something other than open workshops.
 *
 * And when only ONE class is present nothing is grouped at all, because a single heading over the
 * whole list is a heading that distinguishes nothing while costing a row of vertical space on a
 * handset. `groupRows` returns a single null bucket when no row carries a group, so this comes out
 * as the plain list it has always been for the twenty controls that only ever see open workshops.
 *
 * ── AND IT NEVER RETURNS MORE ROWS THAN A PANEL CAN DRAW ────────────────────────────────────────
 *
 * `SearchableSelect` windows its corpus at `RENDER_CAP` and prints its own footer about what the
 * window left out. `WORKSHOP_OPTION_PAGE_SIZE` is `RENDER_CAP` precisely so that never fires — but
 * a RECOVERED row is one MORE than the page, so a full page plus the record's own off-page workshop
 * is 81 rows into a control that draws 80, and that state is not exotic: it is an admin editing a
 * record filed under a design workshop older than the newest eighty. What it produced, before this
 * slice, was the exact failure this module's header claims is impossible — the field hint reading
 * *"Showing the first 80 of 196, plus 1 already selected"* directly above the panel's own footer
 * reading *"Showing the first 80 of 81"*, two totals for one cut, with a real workshop row dropped
 * in silence between them.
 *
 * Trimming here rather than leaving it to the primitive drops the SAME row the window would have
 * dropped — the last in reading order, because the buckets are already flattened — and it is the
 * only place that can then report the number honestly: {@link WorkshopOptionSet.drawn} is counted
 * off the result, so `drawn` is what is on screen, `cut` is `total - drawn`, and the panel, handed
 * a corpus that fits, draws no second sentence to contradict the first. The recovered row cannot be
 * the row lost: its heading sorts first in `order`.
 */
function assemble(entries: Classified[], order: readonly string[], group: boolean): SelectOption[] {
  const headings = order.filter((heading) => entries.some((entry) => entry.heading === heading));
  const applyHeadings = group && headings.length > 1;
  const options: SelectOption[] = [];
  for (const heading of headings) {
    const bucket = entries.filter((entry) => entry.heading === heading).sort(byOccurrenceThenTitleThenId);
    for (const entry of bucket) {
      options.push(applyHeadings ? { ...entry.option, group: heading } : entry.option);
    }
  }
  return options.length > RENDER_CAP ? options.slice(0, RENDER_CAP) : options;
}

/**
 * Rows the builders refuse to turn into options at all, on both tables.
 *
 * The `""` id is the guard that matters. A row whose id is empty would be emitted as an option with
 * `value: ""`, which is the "none" row's value — so it would collide with the row the primitive
 * draws, share its React key, offer the same answer twice, and leave the control unable to say
 * which of the two is selected. That is R1's forbidden second state arriving through the data
 * rather than through a prop, and it costs one line to make impossible.
 */
function usable(row: { id: string }): boolean {
  return present(row.id) !== "";
}

/* ────────────────────────────────────────────────────────────────────────────
 * The two builders
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What a builder is told, minus the per-table extras.
 *
 * Exported so a caller assembling it inside a `useMemo` can annotate it and get the missing-field
 * error at the declaration rather than at the call — both fields below are required and neither has
 * a sensible default, which is the whole point of them.
 */
export type WorkshopBuildOptions<Row> = {
  /**
   * Draw the §2.4 headings when more than one class is present.
   *
   * REQUIRED, for the same class of reason `offPage` is, though the cost of getting it wrong is far
   * lower. Pass `true` on any picker that files a record — the difference between an open workshop
   * and a submitted one is a thing the reader must act on. Pass `false` only where the REQUEST has
   * already narrowed the list to one class (a `statusFilter` sent, a `workshopType` filter), where
   * a heading would be a heading over the entire list, saying nothing.
   */
  group: boolean;
  /** What to do about a stored value the list does not contain. Read {@link OffPageIntent} first. */
  offPage: OffPageIntent<Row>;
};

/**
 * The count of the list, and the two facts about what it left out, read off the state.
 *
 * `truncated` is deliberately OR-ed with a derived cut rather than trusted alone: a route that
 * reports a total larger than the page it sent has told us it truncated whether or not it also set
 * the flag, and half the routes in this app report one and not the other.
 */
function cutOf<Row>(
  state: WorkshopListState<Row>,
  drawn: number
): Pick<WorkshopOptionSet, "total" | "truncated" | "cut"> {
  if (state.kind !== "ok") return { total: null, truncated: false, cut: 0 };
  const total = state.total;
  const cut = total == null ? 0 : Math.max(0, total - drawn);
  return { total, truncated: Boolean(state.truncated) || cut > 0, cut };
}

/**
 * `DesignWorkshop` rows → options. One of the two builders; see the header for why there are two.
 *
 * ── §2.6, THE SOFT-DELETE RULE, AND ITS ONE DELIBERATE EXCEPTION ────────────────────────────────
 *
 * A soft-deleted design workshop is NEVER offered. `list_design_workshops` excludes them unless
 * `includeDeleted` is sent, that flag is admin-only, and no picker may send it — but the trash
 * listing exists, so a row carrying `deletedAt` can reach a caller and must not reach a listbox. A
 * picker that offered one would file live fieldwork into the bin.
 *
 * **The recovered off-page row is exempt, and that is not an oversight.** §2.9's premise is that the
 * LIST is narrower than the DOOR: the list filters `deletedAt` while the single read admits an
 * admin to a soft-deleted row, so a record legitimately sits in a workshop the list will never
 * mention. That row is not an offer — it is drawn under "Already on this record", a heading whose
 * entire job is to say "this is where this record is", and hiding it would show a blank picker over
 * a filed record and invite somebody to file it somewhere else. Offering a deleted workshop as a
 * DESTINATION and reporting one as a FACT are opposite operations that happen to draw the same row.
 *
 * ── SUBMITTED AND ARCHIVED ARE OFFERED, GROUPED, HINTED — AND NOT `disabled` ────────────────────
 *
 * A designer legitimately corrects a record already filed under a submitted workshop, and the
 * server does not refuse it. Disabling the row would convert a read-only fact into a wrong write —
 * `WorkshopSelect.tsx`'s argument about withheld rows, applied to the other table. The heading and
 * the `Submitted` / `Archived` prefix on the hint are what stop it being picked by ACCIDENT, which
 * is the actual risk; nothing here needs to stop it being picked on purpose.
 *
 * ── WHAT IS NOT HERE: `currentUserId` ───────────────────────────────────────────────────────────
 *
 * The draft signature carried one, for grouping by door — "workshops you created" against
 * "workshops you were added to". §2.4 rejects that grouping and this builder therefore has no use
 * for the id: both doors open the same workshop with the same filing rights, so the heading would
 * split rows on a fact the reader cannot act on, and status is the axis they must act on. Two axes
 * cannot both be the grouping. The parameter is left out rather than accepted and ignored.
 */
export function designWorkshopOptions<Row extends DesignWorkshopRow>(
  state: WorkshopListState<Row>,
  opts: WorkshopBuildOptions<Row>
): WorkshopOptionSet {
  const rows: readonly Row[] = state.kind === "ok" ? state.rows : [];
  const listed = rows.filter((row) => usable(row) && !present(row.deletedAt));

  const classify = (row: Row, heading: string): Classified => {
    const status = present(row.status).toUpperCase();
    const statusWord =
      heading === GROUP_ON_THIS_RECORD
        ? // The heading already says what this row is; prefixing "Submitted" as well would be the
          // same fact twice on one line, and the line is shared with a craft and a cluster.
          ""
        : status === "SUBMITTED"
          ? "Submitted"
          : status === "ARCHIVED"
            ? "Archived"
            : "";
    return {
      option: {
        value: row.id,
        label: labelFor(row.title),
        hint: hintFrom([statusWord, row.craftName, firstPresent(row.clusterName, row.state), day(row.startDate)])
      },
      heading,
      // `startDate ?? createdAt`, and NOT the `startDate` the hint prints. The hint states the day
      // the workshop RAN and must stay silent when nobody has said; the sort needs a total order and
      // a creation date is the least-wrong stand-in for one. Two different questions, and answering
      // them with one value would either date rows that are undated or shuffle them arbitrarily.
      occurrence: firstPresent(row.startDate, row.createdAt),
      title: labelFor(row.title),
      id: row.id
    };
  };

  const entries: Classified[] = listed.map((row) => {
    const status = present(row.status).toUpperCase();
    const closed = status === "SUBMITTED" || status === "ARCHIVED";
    return classify(row, closed ? GROUP_SUBMITTED_AND_ARCHIVED : GROUP_OPEN);
  });

  const stored = opts.offPage.mode === "recover" ? opts.offPage.row : null;
  const recovered = Boolean(stored && usable(stored) && !listed.some((row) => row.id === stored.id));
  if (stored && recovered) entries.push(classify(stored, GROUP_ON_THIS_RECORD));

  const options = assemble(
    entries,
    [GROUP_ON_THIS_RECORD, GROUP_OPEN, GROUP_SUBMITTED_AND_ARCHIVED],
    opts.group
  );
  // COUNTED OFF THE RESULT AND NOT OFF `listed`, so `drawn` is what is on screen even when
  // `assemble` had to trim — see its note on the 81st row. The recovered row is not one of the
  // corpus these numbers describe, so it comes back out again before the count is reported.
  const drawn = options.length - (recovered ? 1 : 0);
  return { options, drawn, recovered, ...cutOf(state, drawn) };
}

/**
 * `Workshop` rows → options. The other builder; the two never merge — see the header.
 *
 * ── §2.6, THE ENDED WORKSHOP ────────────────────────────────────────────────────────────────────
 *
 * An ended workshop is OFFERED, under its own heading, with `Ended` in front of its hint, and it is
 * not `disabled`. Filing into one is a real thing a researcher does — a record written up a week
 * after the visit — and the server accepts it, pins it to PENDING and flags it for admin approval.
 * `WorkshopSelect`'s pre-flight and `LateSubmissionDialog` are what say so at save time. The
 * division of labour is worth stating because it is easy to duplicate: **the heading is what stops
 * a reader picking one by accident; the dialog is what stops them saving into one by accident.**
 * Neither substitutes for the other, and disabling the row would replace both with a dead end.
 *
 * ── WHAT COUNTS AS "ENDED", WHICH IS NOT WHAT `endDate < now` SAYS ──────────────────────────────
 *
 * The whole of the end day is still in-window — the backend's rule, mirrored locally by
 * `WorkshopSelect.tsx::endedLocally`. Using a naive comparison here would put "Ended" on a workshop
 * that is still accepting submissions TODAY, on the same screen where the late-submission dialog
 * will not fire, so the picker and the save would be telling a researcher two different things
 * about one workshop in the same minute. `now` is a parameter so that the boundary is testable
 * without a clock; it defaults to the real one.
 *
 * `endDate` ALONE decides it, per §2.4 — a workshop with no `endDate` is Open however old its
 * `date` is. That differs from `endedLocally`, which falls back to `date` and `startDate`, and the
 * difference is deliberate: `endedLocally` answers "would saving into this be late?" as a degraded
 * stand-in for a server pre-flight, and guessing an end date is the right call when the alternative
 * is no warning at all. Here the question is "which heading does this row sit under?", and inventing
 * an end date to close a workshop nobody has closed would file it away from the reader for good.
 */
export function fieldWorkshopOptions<Row extends FieldWorkshopRow>(
  state: WorkshopListState<Row>,
  opts: WorkshopBuildOptions<Row> & {
    /** The instant "ended" is measured against. Defaults to now; passed in by specs. */
    now?: number;
  }
): WorkshopOptionSet {
  const rows: readonly Row[] = state.kind === "ok" ? state.rows : [];
  const listed = rows.filter(usable);
  const now = opts.now ?? Date.now();

  const classify = (row: Row, heading: string): Classified => {
    const ended = heading === GROUP_ENDED;
    return {
      option: {
        value: row.id,
        label: labelFor(row.title),
        hint: hintFrom([
          ended ? "Ended" : "",
          row.place,
          day(firstPresent(row.startDate, row.date, row.createdAt))
        ])
      },
      heading,
      // `startDate ?? date ?? createdAt` — `workshopOccurrenceDate`, which is Android parity with
      // `WorkshopDetailDto.occurrenceDate()` and is already the key four of the ten callers sort by.
      occurrence: firstPresent(row.startDate, row.date, row.createdAt),
      title: labelFor(row.title),
      id: row.id
    };
  };

  const entries: Classified[] = listed.map((row) =>
    classify(row, hasEnded(row.endDate, now) ? GROUP_ENDED : GROUP_OPEN)
  );

  const stored = opts.offPage.mode === "recover" ? opts.offPage.row : null;
  const recovered = Boolean(stored && usable(stored) && !listed.some((row) => row.id === stored.id));
  if (stored && recovered) entries.push(classify(stored, GROUP_ON_THIS_RECORD));

  // See the design builder's twin: the count is read off the assembled list, never off `listed`,
  // so a trim at `RENDER_CAP` cannot leave the sentence describing rows the panel is not drawing.
  const options = assemble(entries, [GROUP_ON_THIS_RECORD, GROUP_OPEN, GROUP_ENDED], opts.group);
  const drawn = options.length - (recovered ? 1 : 0);
  return { options, drawn, recovered, ...cutOf(state, drawn) };
}

/**
 * Has this workshop's window closed? The backend's rule: the whole of the end day is in-window.
 *
 * An unparseable or absent `endDate` is OPEN, never ended. Failing the other way would file a
 * workshop under "Ended" on the strength of a string nobody could read, which is a claim about a
 * submission window made from a parse error.
 */
function hasEnded(endDate: string | null | undefined, now: number): boolean {
  const raw = present(endDate);
  if (!raw) return false;
  const end = new Date(raw);
  if (Number.isNaN(end.getTime())) return false;
  return now >= end.getTime() + 24 * 60 * 60 * 1000;
}

/* ────────────────────────────────────────────────────────────────────────────
 * §2.8 — what the list left out, said once
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE TRUNCATION SENTENCE FOR A WORKSHOP PICKER — chosen here, WORDED IN `selectFilter.ts`.
 *
 * Every string this returns comes from `selectFilter.ts::truncationSentence` and its two arms
 * (`capNoticeSentence`, `unknownTotalNoticeSentence`). Nothing new is written here and nothing new
 * may be: the panel's own footer draws those same two sentences, and a picker whose footer and
 * whose field hint describe the same cut in two different wordings has taught the reader that
 * neither is worth reading. What this function adds is the mapping from a server page to that
 * vocabulary, in one place, so twenty callers cannot each map it differently.
 *
 * ── THE ONE JUDGEMENT IT MAKES: WHICH ARM ───────────────────────────────────────────────────────
 *
 * A known total wins. `truncationSentence` rules the other way — a server-reported flag beats a
 * locally-counted cap — and its reason is that "the first 80 of 100" over a server that CUT at 100
 * claims there are twenty more when there may be nine hundred. That reason does not apply here,
 * because `total` on these routes is a count of the corpus and not of the page: it is the honest
 * number, so where it exists it is the one to print. The flag arm is for the route that cannot
 * count at all, and the guard below (`cut === 0`) is what keeps a route that reports both from
 * printing the vaguer of its two true sentences.
 *
 * ── AND THE RECOVERED ROW IS "plus 1 already selected", NOT A ROW OF THE CORPUS ─────────────────
 *
 * It is not one of the `total` and was never on the page. `capNoticeSentence` already has the
 * clause for exactly this shape of row, and using it keeps the arithmetic in the sentence checkable
 * against the rows on screen — which is the only reason a reader ever reads one of these.
 *
 * `searchable` is whether this control's box reaches past the cut. Pass `true` on any picker wired
 * to the server's `search=`; pass `false` where the box is off, and the sentence stops telling the
 * reader to type into a control that is not on screen.
 */
export function workshopCutSentence(
  set: Pick<WorkshopOptionSet, "drawn" | "total" | "truncated" | "cut" | "recovered">,
  opts: { term?: string; searchable: boolean }
): string {
  return truncationSentence({
    shown: set.drawn,
    pinned: set.recovered ? 1 : 0,
    total: set.total ?? set.drawn,
    capped: set.cut,
    term: opts.term ?? "",
    hint: opts.searchable ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH,
    serverTruncated: set.truncated && set.cut === 0
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * §3.5 — the state sentences
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The three facts a sentence about a workshop list needs, handed in together so they cannot
 * disagree.
 *
 * They travel as one object rather than as three arguments because {@link workshopListNotice} and
 * {@link workshopEmptyLabel} are two views of ONE sentence — the notice under the control and the
 * line inside the panel — and a caller that got `scoped` right for one and wrong for the other
 * would print two contradictory claims about the same list, one of them where only a screen-reader
 * user meets it.
 */
export type WorkshopListVoice = {
  table: WorkshopTable;
  /**
   * Did the REQUEST narrow this list to what the account may use?
   *
   * `true` for `GET /workshops?accessibleOnly=true` and for every `DesignWorkshop` list (that table
   * is scoped by grant for everyone but an admin). `false` for a picker that filters a screen over
   * the whole repository — `/media`, `/data`, the funnel.
   *
   * It picks between two sentences that must never be collapsed. *"No workshops are open to this
   * account"* is a statement about a SCOPE and its next move is to ask an administrator; *"No
   * workshops have been recorded yet"* is a statement about the REPOSITORY and its next move is to
   * create one. Sending a designer to an administrator because the repository is empty wastes a
   * day; telling them the repository is empty when they simply have no grants makes them create a
   * duplicate of a workshop that already exists.
   */
  scoped: boolean;
  /**
   * Is this device offline? See {@link deviceLooksOffline} for where the answer comes from and why
   * it is the caller's to give.
   */
  online: boolean;
};

/**
 * Does this device look offline?
 *
 * ── THE ONE IMPURE FUNCTION IN THIS FILE, AND WHY IT IS HERE AT ALL ─────────────────────────────
 *
 * `navigator.onLine === false` is this app's convention for "definitely offline" and is spelled out
 * inline in a dozen files. Every caller of {@link workshopListNotice} needs it, and twelve becoming
 * thirty-two is how a convention becomes a coin flip. It is exported separately from the pure
 * builders so a spec can hand `online` in directly and never touch a global.
 *
 * ── IT IS OPTIMISTIC, DELIBERATELY, AND IN THE SAFE DIRECTION ──────────────────────────────────
 *
 * `navigator.onLine` reports true through a captive portal that routes nothing, so this returns
 * false in a case where the device is effectively offline — and the sentence a caller then prints
 * is *"The list could not be loaded, so this is not showing what exists. Nothing you have entered
 * is at risk."* Every clause of that is still true with no signal. The other error would print
 * *"connect and it will load"* to somebody who IS connected, which is advice they cannot act on
 * about a failure they cannot see. Wrong quietly beats wrong loudly.
 *
 * Not a network probe, per §3.5: the authoritative split is the one the outbox already makes
 * between a transient failure and a refusal. This is the cheap read that stands in for it on the
 * web until a caller has the outbox's answer to hand.
 */
export function deviceLooksOffline(): boolean {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}

/**
 * THE ONE SENTENCE UNDER THE CONTROL. `""` when the list has nothing to explain.
 *
 * ── FOUR STATES, FOUR SENTENCES, AND THE POINT IS THAT THEY ARE FOUR ───────────────────────────
 *
 * The failure this closes is the most repeated bug class in this repository: **absence read as
 * non-existence.** A picker that draws nothing looks identical whether the read failed, the device
 * is offline, the account has no grants, or the table is genuinely empty — and those four have four
 * different next moves (wait, connect, ask an administrator, create one). Today the design-workshop
 * picker answers all four with *"You are on no design workshop yet. An administrator can add you to
 * one"*, which is a confident claim about a grant table produced by `.catch(() => null)`.
 *
 * LOADING SAYS NOTHING. A sentence that appears and vanishes inside a second is noise on a fast
 * connection and, on a slow one, is replaced by a different sentence just as the reader finishes
 * it. The panel covers the wait in the slot where it belongs — see {@link workshopEmptyLabel}.
 *
 * ── THE OFFLINE SENTENCE DOES NOT PROMISE A CACHE, AND §3.5's DOES ─────────────────────────────
 *
 * §3.5's shared offline sentence ends *"Connect once and the list is kept on the device from then
 * on"*, which is right for the artisan, craft and tool registers and is FALSE for these two lists —
 * §3.3 rules "disable with a reason, never cache" for both of them, under R6: **a stale ACCESS list
 * is wrong in the permissive direction.** A cached "which workshops may I file against" reads a
 * grant revoked in March as a grant in September, and a picker is the one control that must not
 * offer what it cannot honour. Printing the shared clause here would promise a designer that the
 * list will be waiting next time, and it will not be — so the first two sentences are §3.5's word
 * for word and the last clause states what actually happens, and why.
 */
export function workshopListNotice<Row>(state: WorkshopListState<Row>, voice: WorkshopListVoice): string {
  const noun = nounFor(voice.table);
  if (state.kind === "loading") return "";
  if (state.kind === "failed") {
    return voice.online
      ? `The ${noun} list could not be loaded, so this is not showing what exists. Nothing you have entered is at risk — this record can be saved without it.`
      : `This device has not received the ${noun} list yet, so there is nothing to pick here. That is not a claim that there are none. Connect and it will load; this list is never kept on the device, because a stored copy of who may file where reads a revoked grant as a grant.`;
  }
  if (state.rows.length > 0) return "";
  return genuinelyEmpty(voice);
}

/** The two "there really are none" sentences, which are two on purpose — see `WorkshopListVoice`. */
function genuinelyEmpty(voice: WorkshopListVoice): string {
  const noun = nounFor(voice.table);
  return voice.scoped
    ? `No ${noun} are open to this account. An administrator can give you access to one.`
    : `No ${noun} have been recorded yet.`;
}

/**
 * The `emptyLabel` handed to the `Dropdown` — NEVER A CLAIM THE STATE DOES NOT SUPPORT.
 *
 * The same sentence {@link workshopListNotice} draws, in the panel instead of under it, plus the
 * one state the notice deliberately stays quiet about. They never both reach a reader: with rows
 * the notice is `""` and this line is unreachable; with no rows the control is stood down, so the
 * panel cannot be opened and only the notice shows. What this covers is the reader who opens a
 * picker that HAS rows and then filters them all away — at which point the panel draws "No matches"
 * or the server's stronger wording and never this string at all.
 *
 * ── LOADING IS `SEARCHING_LABEL`, AND NOT A NEW WORD ───────────────────────────────────────────
 *
 * The panel already draws `SEARCHING_LABEL` in this exact slot when a `serverQuery` answer is
 * outstanding, and announces "Loading options" into its live region for the same instant. A picker
 * that invented a third string for the same second — the design-workshop picker's *"Loading your
 * design workshops…"* is the one shipping today — would mean a control changes its wording
 * depending on whether its box happens to be wired to the server, which is a distinction the reader
 * cannot see and should never be shown. One word for "an answer is outstanding", app-wide.
 *
 * It is a real string and never `""`, because the slot's default is the literal `"No options"` —
 * which on a mid-flight read is the exact claim this whole module exists to stop.
 */
export function workshopEmptyLabel<Row>(state: WorkshopListState<Row>, voice: WorkshopListVoice): string {
  if (state.kind === "loading") return SEARCHING_LABEL;
  return workshopListNotice(state, voice) || genuinelyEmpty(voice);
}

/**
 * Should this control be DISABLED and its field stop being required? (R2, R3.)
 *
 * ── ONE BOOLEAN GOVERNING BOTH, BECAUSE THEY ARE ONE FACT ──────────────────────────────────────
 *
 * R2: *a field may only be mandatory where it is answerable.* `components/forms/LocationFields.tsx`
 * ends both of its required flags in `&& options.length > 0`, and the incident behind it is the one
 * this repository keeps re-learning: a required closed list with no members, a client-side
 * validator refusing the submit, and an interview plus its photographs dying with the tab before
 * the offline outbox was ever reached. A picker with nothing in it must not be able to block a save.
 *
 * R3 is the other half: the control is disabled AND the sentence is on screen. Disabled with no
 * sentence is the silent empty picker that reads as "there are none".
 *
 * ── IT TAKES THE OPTION SET AND NOT THE STATE, WHICH IS THE WHOLE SUBTLETY ─────────────────────
 *
 * A failed read with a recovered off-page row is NOT an empty control: it holds the record's own
 * workshop, and — with `noneLabel` set — a way back out of it. Standing that down would leave a
 * designer looking at a correct value they cannot change, which is worse than the failure that
 * caused it. A failed read with no stored value genuinely has nothing to pick and is stood down.
 * The state cannot tell those apart; the options can.
 */
export function workshopListStandsDown(set: Pick<WorkshopOptionSet, "options">): boolean {
  return set.options.length === 0;
}
