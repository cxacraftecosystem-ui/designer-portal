/**
 * The list-picking decisions that are pure, lifted out of `ui/SearchableSelect` so they can be
 * CALLED rather than only looked at.
 *
 * WHY THIS FILE EXISTS AT ALL, because "constants and a substring test" is not a reason to add a
 * module. Three of the judgements below have already been wrong in ways no screenshot showed:
 *
 * - matching searched only the label, so the 246-row country picker had a filter box that could not
 *   find a country by name — the one column a reader would actually type;
 * - the cap notice ended in "Keep typing to narrow the list" whether or not there was anything to
 *   type into, which on the one panel in the app that turns its filter box OFF told the reader to
 *   use a control that is not on screen;
 * - the render cap and a caller's own page size were two independent numbers, so a page that asked
 *   for 100 rows drew 80 of them and printed two different truncation totals one above the other.
 *
 * Each of those is a pure function of its inputs and none of them can be reached from a Playwright
 * click, because this repository has no React renderer in its devDependencies — Playwright is the
 * whole of it (see `e2e/capped-lists-unit.spec.ts`, which made the same split for the same reason,
 * and `components/data/cappedList.ts`, the module it tests). So the judgements live here, the JSX
 * lives there, and `e2e/dropdown-sweep-unit.spec.ts` exercises these by calling them.
 */

/**
 * A picker row.
 *
 * `hint` and `group` are the two fields added on 2026-08-23, and each closes a gap that was costing
 * a real control its search:
 *
 * - **`hint` is secondary text that is MATCHED AS WELL AS SHOWN.** Android's `SelectOption` has had
 *   it from the beginning (`android/.../ui/SearchableSelect.kt:137`, `data class SelectOption(value,
 *   label, hint)`) and its matcher appends the hint to the haystack; the web's had no such field, so
 *   a caller with two things to say about a row had to choose between putting the second one in the
 *   label — where it lengthens every row of a long list — and dropping it, which is what
 *   `PhoneField` did to all 246 country names.
 * - **`group` is a heading above a run of rows**, the `<optgroup>` the themed dropdown could not
 *   express. Its absence is the whole reason the photo-intake page was still on a raw `<select>`:
 *   "Proposed from the capture date" against "Every other place a photograph can go" is the argument
 *   that control makes, and flattening it into 200 labels repeats one long prefix on every row.
 *
 * THE VALUE IS DELIBERATELY NOT SEARCHED, which is where the web parts company with the handset, and
 * that is worth stating rather than leaving to look like an oversight. Kotlin's `matches()` folds
 * `value` into the haystack "because some lists (dial codes, status names) carry the meaning there
 * rather than in the label". On the web a value is usually a CUID — `cmg8x2…` — and a twenty-five
 * character random string matches a great many two-letter queries, so folding it in would put
 * unrelated artisans above the one whose name was typed. Anything a web caller wants searched goes
 * in `label` or `hint`, both of which the reader can see; a match against text nobody can see is
 * indistinguishable from a bug.
 */
export type SelectOption = {
  value: string;
  label: string;
  /** Secondary text, drawn after the label and searched with it. */
  hint?: string;
  /** Heading this row sits under. Rows with no group are drawn first, ungrouped. */
  group?: string;
  disabled?: boolean;
};

/**
 * At or above this many options the panel grows a search box.
 *
 * The number is not a guess — it is where this app's lists actually divide. Every fixed vocabulary
 * tops out at seven: Yes/No (2), access level (3), gender (4), tradition (4), status (4-5), market
 * demand (5), product type (6), maker (7). Every list backed by records starts at nine: crafts (9),
 * artisans (16), products (18), users (20), tools (74), country dial codes (252). So a single
 * threshold separates a closed vocabulary the reader takes in at a glance from a corpus they have
 * to hunt through, and a four-option enum keeps the plain list it deserves.
 *
 * It is only ever the DEFAULT. Where the options come from records the call site passes `searchable`
 * outright — see `SearchableSelectProps.searchable` for that rule and why a count cannot carry it.
 */
export const SEARCH_THRESHOLD = 8;

/**
 * Rows rendered at once, past which the list is capped and the footer says so.
 *
 * Cheaper than a virtualiser and better teaching: with 252 dial codes the way to reach Uruguay is
 * to type, not to flick a finger for ten seconds on a rural handset. The cap only limits what is
 * DRAWN — filtering and "select all" both still see every match, so nothing is silently unreachable.
 *
 * EXPORTED SO A CALLER CAN ASK FOR EXACTLY THIS MANY ROWS. `/design-review` fetched 100 workshops
 * into a control that draws 80, which produced two truncation sentences with two different totals
 * ("the first 80 of 100" inside the panel, "the first 100 of 350" underneath it) and a dead band
 * between 81 and 100 where the page said nothing at all while the panel silently dropped rows. A
 * page size written as `RENDER_CAP` cannot drift from the number that governs it.
 */
export const RENDER_CAP = 80;

/**
 * Diacritics folded so "Jodhpur" is reachable by typing "jodhpur" and "Ahmedābād" by "ahmedabad".
 *
 * RUNS OF WHITESPACE COLLAPSE TOO, on both sides of the comparison, and that half was a real miss.
 * `FormControls.Select` already collapses the labels it builds out of `<option>` children — a label
 * split over two source lines otherwise carries the author's newline and indent into the menu — but
 * a label handed straight in as an `options` array gets no such treatment, and this app builds
 * those by template: `{artisan.name} · {artisan.place}` with an empty `place` leaves two spaces
 * behind. Typing what the row visibly reads as ("block printing", one space) then matched nothing,
 * which looks exactly like "there is no such craft". Folding both the needle and the haystack to
 * single spaces means the reader is compared against what they can see, not against the source.
 */
export function fold(text: string) {
  return text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

/** Where in a folded haystack the needle sits, graded 0 (best) to 2. -1 for no match at all. */
function rankWithin(hay: string, needle: string): number {
  const index = hay.indexOf(needle);
  if (index < 0) return -1;
  if (index === 0) return 0;
  return /[\s\-–—/(,.·]/.test(hay[index - 1]) ? 1 : 2;
}

/**
 * Matches, ordered so that Enter picks what the reader meant.
 *
 * Plain substring order is not good enough: typing "co" into the tool list would put "Bamboo comb"
 * above "Cotton hank" purely because it was entered first, and Enter would then take the wrong one.
 * Ranking label-prefix above word-prefix above mid-word puts the obvious answer on top, and the
 * sort is stable so equally-good matches keep the caller's ordering.
 *
 * THE HINT IS SEARCHED, AND IT RANKS BELOW EVERY LABEL MATCH. Both halves matter. Searching it is
 * what makes a two-column row answerable by either column — the country picker's rows read "🇮🇳 +91"
 * with "India" beside them, and before this a reader could reach Uruguay by its dial code and by
 * nothing else. Ranking hint matches last is what stops the secondary column outvoting the primary
 * one: typing "in" must leave every label beginning "in" above every row merely hinted "…India…".
 */
export function filterOptions(options: SelectOption[], query: string): SelectOption[] {
  const needle = fold(query.trim());
  if (!needle) return options;
  const ranked: Array<{ option: SelectOption; rank: number; at: number }> = [];
  options.forEach((option, at) => {
    const onLabel = rankWithin(fold(option.label), needle);
    if (onLabel >= 0) {
      ranked.push({ option, rank: onLabel, at });
      return;
    }
    // 3 and above: every label match, however weak, is a better answer than a hint match.
    const onHint = option.hint ? rankWithin(fold(option.hint), needle) : -1;
    if (onHint >= 0) ranked.push({ option, rank: 3 + onHint, at });
  });
  ranked.sort((a, b) => a.rank - b.rank || a.at - b.at);
  return ranked.map((entry) => entry.option);
}

/**
 * The row a type-ahead keystroke should land on: label prefix only, and never a disabled row.
 *
 * MATCHED AGAINST WHATEVER LIST THE CALLER HANDS IN, and that is the entire reason it takes one
 * instead of closing over the options. Type-ahead used to search `options` and write the answer
 * into `highlight`, which indexes the RENDERED array — the same array only while the render cap does
 * not bite. On the branch where it does (a non-searchable list past 80 rows, i.e. the design-workshop
 * viewer picker, which is a permissions control) pressing a letter highlighted an unrelated row, and
 * a following Space or Enter ticked that person. Two lists, one index domain.
 */
export function typeaheadIndex(options: SelectOption[], typed: string): number {
  const needle = fold(typed);
  if (!needle) return -1;
  return options.findIndex((option) => !option.disabled && fold(option.label).startsWith(needle));
}

/**
 * What a reader is told to do about a capped list when the panel HAS a filter box.
 *
 * A named constant rather than an inline string so the sentence below cannot be read as the odd one
 * out — they are two answers to one question and have to be looked at together.
 */
export const CAP_HINT_WITH_SEARCH = "Keep typing to narrow the list.";

/**
 * …and when it has not.
 *
 * "Keep typing to narrow the list" used to be printed unconditionally, and on the one panel in the
 * app that passes `searchable={false}` over a long list — the design-workshop viewer picker, whose
 * options are up to 2000 accounts — it instructed the reader to type into a box that is not there.
 * That is worse than saying nothing: the notice is the single sentence on screen whose whole job is
 * to describe this list, and a reader who follows it and finds no box concludes the panel is broken
 * rather than that it is capped.
 *
 * This default states the cap and stops. A call site that turns search off over a long list SHOULD
 * pass its own `capHint` naming the control that does reach the rest — the viewers panel has a
 * server-backed search box above the picker and now says so.
 */
export const CAP_HINT_WITHOUT_SEARCH = "The rest are not drawn — narrow the list to reach them.";

/**
 * The "N of M shown" sentence.
 *
 * `shown` is the WINDOW — the first N matches — and `pinned` is counted separately because the two
 * are not the same kind of row: a pinned row is a selection dragged forward from wherever it really
 * sits in the corpus (see `useSelectList`). Adding them together and calling the sum "the first 81"
 * misdescribes the list a reader is looking at, and this is the one sentence on screen whose whole
 * job is to describe it.
 */
export function capNoticeSentence({
  shown,
  pinned,
  total,
  hint
}: {
  shown: number;
  pinned: number;
  total: number;
  hint: string;
}): string {
  const plus = pinned > 0 ? `, plus ${pinned} already selected` : "";
  return `Showing the first ${shown} of ${total}${plus}. ${hint}`;
}

/**
 * THE SAME SENTENCE WHEN THE SERVER REPORTS THE CUT AS A FLAG AND NOT AS A TOTAL.
 *
 * WHY A SECOND FUNCTION RATHER THAN `capNoticeSentence` WITH A CLEVER `total`. A panel driven by
 * `SearchableSelectProps.serverQuery` holds the server's answer, not the corpus, and its caller asks
 * for exactly `RENDER_CAP` rows — so `filtered.length` never exceeds the cap, `capNoticeSentence`
 * never fires, and a list that was cut on the server would be drawn in complete silence. That is
 * R4's failure with the numbers on the other side of the wire. What the routes can cheaply say is a
 * BOOLEAN, read one past the take, exactly as `GET /tasks/options` already reports
 * `workshopsTruncated` (`backend/app/api/routes/tasks.py`) — counting the corpus to print "of 3632"
 * is a second query for a number nobody acts on. `components/data/cappedList.ts::flagCutNotice`
 * makes the identical split one layer up, for the identical reason, and this is its in-panel twin;
 * the wording is deliberately close enough that a page and its picker read as one voice.
 *
 * **This sentence WINS over `capNoticeSentence` whenever both could be drawn**, and the reason is
 * that a known total which is itself a truncated count is worse than admitting the total is
 * unknown: "Showing the first 80 of 100" over a server that cut at 100 tells the reader there are
 * twenty more when there may be nine hundred, and they stop looking.
 *
 * The two arms are `flagCutNotice`'s and are the same judgement. With nothing typed the reader has
 * been handed a slice and must be told the box is the way past it; with a term typed the cut is a
 * cut of the MATCHES, and telling somebody to search when they already have is how a picker teaches
 * a reader that searching does not work — the only useful instruction left is to narrow it.
 */
export function unknownTotalNoticeSentence({
  shown,
  pinned,
  term,
  hint
}: {
  shown: number;
  pinned: number;
  term: string;
  hint: string;
}): string {
  const plus = pinned > 0 ? `, plus ${pinned} already selected` : "";
  const trimmed = term.trim();
  const more = trimmed
    ? `More match “${trimmed}” than are drawn, and the server did not say how many.`
    : `There are more than are drawn, and the server did not say how many.`;
  return `Showing the first ${shown}${plus}. ${more} ${hint}`;
}

/**
 * What the panel says while a `serverQuery` answer is outstanding.
 *
 * A WORD, NOT A SPINNER, and that is the whole design rather than a shortcut. This repository's
 * non-negotiable is that "a signal that only exists as motion is a signal reduced-motion readers
 * never get" — every pulse has to be paired with a static state anyway, so a control whose only
 * pending signal is a static state has nothing left to pair. It also costs nothing to announce: the
 * same word goes into the panel's `role="status"` region through `listAnnouncement`, where a spinner
 * could never have gone.
 *
 * The ellipsis is the single U+2026 character, matching every other "…" in this app's copy rather
 * than three periods, so a screen reader pauses instead of spelling out dots.
 */
export const SEARCHING_LABEL = "Searching…";

/**
 * "Your query matched nothing" — the `serverQuery` wording, which is a STRONGER claim than the
 * local one and therefore may not share its sentence.
 *
 * A panel filtering the array it was handed says "No matches", and that sentence is true only of
 * the rows it holds. Sixteen controls in this app shipped exactly that over one server-truncated
 * page, and `app/(protected)/design-review/page.tsx` names what it cost: *"typing a real workshop's
 * title that happens to sit on page 4 answered 'No matches' — absence reading as non-existence."*
 * With the box going to the server the answer finally IS about the whole list, and the sentence has
 * to say so — otherwise a reader trained by those sixteen controls to distrust "No matches" goes on
 * distrusting the one control that has earned it, and keeps hunting for a record that is not there.
 *
 * The term is quoted back because a debounced server box can answer a query the reader has already
 * typed past; seeing which term the answer is about is how they tell a stale panel from a real
 * absence.
 */
export function serverNoMatchSentence(term: string): string {
  return `No matches for “${term.trim()}”. This box searches the whole list, not only the rows drawn here.`;
}

/**
 * THE PANEL'S LIVE REGION, in one place, because it is the only description of the list a screen
 * reader gets and it now has to describe two very different controls.
 *
 * The non-server arms are byte-for-byte what both components have always announced — "N of M
 * options match X" while filtering, "M options" otherwise — because ~40 call sites and their specs
 * depend on those exact strings and this pass adds capability without moving anything.
 *
 * The server arms exist because the old sentence goes from useless to actively wrong on that
 * branch. `filtered` IS `options` when the server did the filtering, so the filtering arm would
 * announce "80 of 80 options match bagru", which is arithmetic rather than an answer; and neither
 * arm can say the two things that only exist there — that a request is in flight (so silence means
 * "wait", not "nothing"), and that the server had more than it sent (so this is not the whole
 * answer). Both are facts a sighted reader gets from the panel, and a reader who does not see the
 * panel got nothing at all.
 *
 * `term` must be handed in ALREADY BLANKED when there is no filter box, which is how the callers
 * express "this control is not searching" without a fifth flag.
 */
export function listAnnouncement({
  total,
  matched,
  term,
  server,
  pending,
  truncated
}: {
  /** `options.length` — every row the control was handed. */
  total: number;
  /** `filtered.length` — the rows matching the term. Equal to `total` on the server branch. */
  matched: number;
  /** The live filter term, or "" where there is no filter box. */
  term: string;
  /** The box drives a server query, so `matched` is the server's answer and not a local narrowing. */
  server: boolean;
  /** A server answer is outstanding. */
  pending: boolean;
  /** The server had more rows matching than it sent. */
  truncated: boolean;
}): string {
  const trimmed = term.trim();
  if (server && pending) return trimmed ? `Searching for ${trimmed}` : "Loading options";
  if (server) {
    const more = truncated
      ? trimmed
        ? ", and more match than are drawn"
        : ", and more exist than are drawn"
      : "";
    return trimmed ? `${total} options match ${trimmed}${more}` : `${total} options${more}`;
  }
  if (trimmed) return `${matched} of ${total} options match ${trimmed}`;
  return `${total} options`;
}

/**
 * How many of a multi-select's picks are read out by name before the summary switches to a sample.
 *
 * Six is a listening budget rather than a measurement: past about that many names in one breath a
 * screen-reader user is being read a list they cannot hold, and the count in front of them is the
 * part they were actually asking for. Named here beside the sentence that spends it, the way
 * `CAP_HINT_WITH_SEARCH` sits beside `capNoticeSentence`, so the number and its one use cannot drift.
 */
export const SUMMARY_NAMES = 6;

/**
 * WHAT A MULTI-SELECT'S TRIGGER IS CALLED — the count it holds, and as many of the names as can be
 * resolved and read.
 *
 * ── THE COUNT AND THE NAMES ARE TWO QUESTIONS, AND ONE NUMBER USED TO ANSWER BOTH ────────────────
 * The summary was built by looking every selected value up in `options` and counting what came back,
 * which is correct exactly while `options` carries every pick. `SearchableSelectProps.serverQuery`
 * ends that: there the array is one answer to one term, replaced whenever the reader types. So
 * ticking three workshops and then typing a fourth term left the button reading "3 selected" while
 * its accessible name said "1 selected: Bagru block printing" — and where the new answer carried none
 * of the three, "Nothing selected", spoken about a control holding three. A picker that reports its
 * own state one way to a sighted reader and another to a screen reader has told one of them a lie,
 * and on the design-workshop viewer picker the lie is about who has been granted access.
 *
 * So `selected` — the length of the caller's array — is always the number, and it is the NAMES that
 * degrade: every name known and few enough to read gets the full list; otherwise "including" says
 * plainly that what follows is a sample. That word is already this control's vocabulary for the same
 * situation past `SUMMARY_NAMES`, and it is exactly as true of a row the current page cannot name as
 * of a seventh one.
 *
 * ── AND IT CHANGES NOTHING FOR A CALLER THAT HANDS IN A WHOLE LIST ───────────────────────────────
 * Which is every caller that existed before `serverQuery`: `names.length === selected` there, so the
 * first two arms produce the sentence this control has always produced, character for character. The
 * same repair reaches one older case by the same door — a stored value the fetched page does not
 * contain (the off-page row `DROPDOWN_DESIGN.md` §2.9 leaves the CALLER to recover) was until now
 * quietly subtracted from the total the trigger announced, so a picker missing a row also miscounted
 * the ones it had.
 */
export function selectionSummarySentence({
  selected,
  names
}: {
  /** `values.length` — every pick the control is holding, nameable or not. */
  selected: number;
  /** The labels that could be resolved from the options actually in hand, in the caller's order. */
  names: string[];
}): string {
  if (selected <= 0) return "Nothing selected";
  if (names.length === selected && selected <= SUMMARY_NAMES) {
    return `${selected} selected: ${names.join(", ")}`;
  }
  // No names at all is its own arm rather than a trailing ", including " with nothing after it: the
  // whole answer is off the current page, and a sentence that trails off reads like a bug rather
  // than like a control saying honestly that it cannot name what it is holding.
  if (!names.length) return `${selected} selected`;
  return `${selected} selected, including ${names.slice(0, SUMMARY_NAMES).join(", ")}`;
}

/**
 * THE ONE LINE DRAWN WHERE THE CORPUS HAS NO ROWS — three facts on a `serverQuery` control and two
 * everywhere else, and they must never share a sentence.
 *
 * Without a server query this is exactly what the panel has always drawn: "No matches" when a query
 * excluded everything, and the caller's `emptyLabel` when there was no query — because "your search
 * found nothing" and "there is nothing here" are different facts with different next moves, and
 * collapsing them is how a picker tells a designer that a workshop they can see the name of does not
 * exist.
 *
 * A server query adds the third, and it is the one the local control never had a way to say: **the
 * answer has not arrived yet.** An empty list mid-flight is not an empty list, and drawing the
 * `emptyLabel` at it — "No design workshops have been recorded yet" — is a claim about the
 * repository made from a read that has not finished. That is the single most repeated bug class in
 * this repo arriving through a door the local branch does not have, and on the rural connections
 * this app is built for the window it arrives in is a second and a half wide.
 *
 * `emptyLabel` is the caller's and is never composed with anything here. It is where the six
 * sentences of the offline contract land — bundled, cached-and-stale, empty-because-offline,
 * could-not-be-listed, genuinely-empty-scoped and genuinely-empty-unscoped — and the panel has no
 * business knowing which of them it is holding.
 */
export function emptyListSentence({
  emptyLabel,
  term,
  server,
  pending
}: {
  emptyLabel: string;
  term: string;
  server: boolean;
  pending: boolean;
}): string {
  if (server && pending) return SEARCHING_LABEL;
  if (!term.trim()) return emptyLabel;
  return server ? serverNoMatchSentence(term) : "No matches";
}

/**
 * Which truncation sentence a panel owes the reader, or "" for the case that owes none.
 *
 * Here rather than in the JSX because the ORDER of the branches is a ruling and not a formatting
 * choice: **a server-reported truncation beats a locally-counted one whenever both could be drawn.**
 *
 * A caller with `serverQuery` asks for exactly `RENDER_CAP` rows, so `capped` is normally 0 there and
 * the question never arises. Where it does arise the local count is the one that must give way,
 * because a total that is itself a truncated count is a worse lie than an admitted unknown: over a
 * server that cut at 100, "Showing the first 80 of 100" tells a reader there are twenty more they
 * have not seen when there may be nine hundred, and they stop looking.
 *
 * `shown` and `total` are always counts of the CORPUS. A "none" row and a pinned row are not rows of
 * the list this sentence is counting, and folding either in produces the off-by-one notice a reader
 * checks their own counting against.
 */
export function truncationSentence({
  shown,
  pinned,
  total,
  capped,
  term,
  hint,
  serverTruncated
}: {
  /** The window — the first N rows of the corpus, pinned rows excluded. */
  shown: number;
  pinned: number;
  /** `filtered.length` — every row matching, as far as this client can see. */
  total: number;
  /** How many matching rows the render cap dropped. */
  capped: number;
  term: string;
  hint: string;
  /** The server said it had more than it sent. */
  serverTruncated: boolean;
}): string {
  // Nothing was drawn, so there is no window to describe and the empty line above is already saying
  // what happened. "Showing the first 0" underneath it would be a second sentence contradicting the
  // first. Not reachable from a well-behaved route — a server that reports a cut has by definition
  // sent at least the rows it cut at — but this is the arm `cappedListNotice` also gives its own
  // words to, and silence is the right ones here.
  if (shown <= 0) return "";
  if (serverTruncated) return unknownTotalNoticeSentence({ shown, pinned, term, hint });
  if (capped > 0) return capNoticeSentence({ shown, pinned, total, hint });
  return "";
}

/** A run of rows under one heading. `group: null` is the ungrouped run, which is drawn first. */
export type OptionGroup = {
  group: string | null;
  rows: Array<{ option: SelectOption; index: number }>;
};

/**
 * Rows bucketed by `group`, KEEPING each row's index into the rendered array.
 *
 * The index is the whole point of returning a structure rather than sorting in place. `highlight`,
 * `aria-activedescendant`, the option element ids and the Enter/Space commit all index the rendered
 * array, and a grouped render that renumbered its rows would be the same class of defect as the
 * type-ahead that wrote an index from one list into a highlight that read another: the highlight
 * lands on a row nobody pointed at, and in a multi-select the next Space ticks it.
 *
 * Order is first-appearance, ungrouped rows first, so a caller's ordering still governs and a
 * filtered list cannot reshuffle its own headings. Returns a single `group: null` bucket when
 * nothing carries a group, which is every list in the app but one.
 */
export function groupRows(rendered: SelectOption[]): OptionGroup[] {
  if (!rendered.some((option) => option.group)) {
    return [{ group: null, rows: rendered.map((option, index) => ({ option, index })) }];
  }
  const buckets: OptionGroup[] = [];
  const at = new Map<string | null, OptionGroup>();
  rendered.forEach((option, index) => {
    const key = option.group ?? null;
    let bucket = at.get(key);
    if (!bucket) {
      bucket = { group: key, rows: [] };
      at.set(key, bucket);
      buckets.push(bucket);
    }
    bucket.rows.push({ option, index });
  });
  // Ungrouped rows first: they are the ones a caller wrote with no opinion about grouping (the photo
  // picker's "Leave out — I will attach this one myself"), and drawing them under the first heading
  // would file them in a group they are not in.
  //
  // `SearchableSelectProps.noneLabel` depends on this ordering rather than on a special case of its
  // own: the "none" row is prepended ungrouped, so it lands in this bucket and is drawn above every
  // heading — which is the reading order DROPDOWN_DESIGN §2.4 specifies for the workshop pickers
  // ("(ungrouped, first) — the none row only", then "Already on this record", then "Open"). Filing
  // "Not filed under a design workshop" under "Open" would make un-filing look like a workshop.
  return buckets.sort((a, b) => (a.group === null ? -1 : b.group === null ? 1 : 0));
}
