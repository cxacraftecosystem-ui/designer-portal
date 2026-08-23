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
  return buckets.sort((a, b) => (a.group === null ? -1 : b.group === null ? 1 : 0));
}
