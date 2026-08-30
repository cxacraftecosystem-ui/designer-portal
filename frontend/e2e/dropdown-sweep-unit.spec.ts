import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CAP_HINT_WITHOUT_SEARCH,
  CAP_HINT_WITH_SEARCH,
  RENDER_CAP,
  SEARCHING_LABEL,
  SUMMARY_NAMES,
  capNoticeSentence,
  emptyListSentence,
  filterOptions,
  groupRows,
  listAnnouncement,
  selectionSummarySentence,
  serverNoMatchSentence,
  truncationSentence,
  typeaheadIndex,
  unknownTotalNoticeSentence,
  type SelectOption
} from "@/components/ui/selectFilter";
import {
  GROUP_ENDED,
  GROUP_ON_THIS_RECORD,
  GROUP_OPEN,
  GROUP_SUBMITTED_AND_ARCHIVED,
  UNTITLED_WORKSHOP,
  designWorkshopOptions,
  fieldWorkshopOptions,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type DesignWorkshopRow,
  type FieldWorkshopRow,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

/**
 * THE DROPDOWN SWEEP'S REVIEW, PINNED — ten findings, and every assertion below fails against the
 * tree as it was.
 *
 * The pass that made every record-backed picker searchable was reviewed on 2026-08-23 and came back
 * with ten. What they have in common is that not one of them is visible in a screenshot of a working
 * control: a filter box that cannot match a country by name looks identical to one that can until
 * somebody types "india"; a cap notice telling a reader to type into a box that is not there is a
 * correct sentence in the wrong place; a type-ahead writing an index from one array into a highlight
 * that reads another moves the bar by a row or two, on the one list long enough for nobody to notice.
 *
 * WHY PART PURE CALL AND PART SOURCE READ — the split `capped-lists-unit.spec.ts` and
 * `task-picker-cap-unit.spec.ts` already make, and for their reason. The matcher, the two cap
 * sentences and the grouping are pure functions and are tested by CALLING them, which is what
 * `components/ui/selectFilter.ts` was extracted for. The rest — which array an index is taken
 * against, whether a key is allowed to `preventDefault`, whether a call site passes `searchable` —
 * lives inside React components, and this repository has no React renderer in its devDependencies:
 * Playwright is the whole of it. Those are read out of the source.
 *
 * WHAT THE SOURCE READS DO NOT PROVE: that a browser paints or announces any of it. That belongs in
 * a signed-in spec, and for three of these findings it cannot be written honestly at all — the
 * defects only appear past `RENDER_CAP` rows of a real table, and a fixture is a list somebody chose
 * the length of.
 */

/**
 * A source file, with its line endings normalised to `\n` before anything asserts against it.
 *
 * NOT TIDYING — without it a third of the source-read assertions below fail on a Windows checkout
 * and pass on CI, which is the worst arrangement available. `.gitattributes` marks these files as
 * text, so git checks them out CRLF on Windows and LF on the Linux runner, while an assertion
 * spanning two lines is written `"…the ones an\n            admin granted you"` in a spec file that
 * is itself LF. The three `design-review` tests here were failing for exactly that reason and for no
 * other: their claims were true of the file the whole time, and a green CI was reporting the checkout
 * it happened to run on rather than the source. Normalising here means an assertion is compared
 * against the CONTENT of a file and never against the platform that checked it out.
 */
const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

/** The text between two markers, so an assertion cannot drift into a neighbouring call. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/**
 * The source with its comments removed, for the assertions that ban a dead construct.
 *
 * The obvious version of those bans is wrong in a way that quietly punishes the house style: a file
 * that EXPLAINS at length why it no longer renders a raw `<select>` contains the string `<select>`
 * in its explanation, so a plain `toContain` fails on the very prose that proves the fix was
 * deliberate. `capped-lists-unit.spec.ts` needed the same helper for the same reason.
 */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The longest list in the app, searchable by something a reader would type
 * ──────────────────────────────────────────────────────────────────────────── */

test("a hint is matched as well as shown, and ranks below every label match", () => {
  // The country picker's real shape: the code reads, the name rides alongside.
  const countries: SelectOption[] = [
    { value: "US", label: "🇺🇸 +1", hint: "United States" },
    { value: "IN", label: "🇮🇳 +91", hint: "India" },
    { value: "UY", label: "🇺🇾 +598", hint: "Uruguay" },
    { value: "IE", label: "🇮🇪 +353", hint: "Ireland" }
  ];

  // The finding, in one line: this returned NOTHING before the hint existed.
  expect(filterOptions(countries, "uruguay").map((option) => option.value)).toEqual(["UY"]);
  expect(filterOptions(countries, "india").map((option) => option.value)).toEqual(["IN"]);

  // The dial code still works — the hint is an addition, not a replacement.
  expect(filterOptions(countries, "+91").map((option) => option.value)).toEqual(["IN"]);

  // …and the second column cannot outvote the first. "i" is a label match on nothing here, so both
  // hint matches come through, in the caller's own order.
  expect(filterOptions(countries, "ir").map((option) => option.value)).toEqual(["IE"]);
});

test("a label match beats a hint match even when the label match is mid-word", () => {
  const options: SelectOption[] = [
    { value: "hint", label: "Zebra", hint: "Cotton" },
    { value: "label", label: "Recotton", hint: "Zebra" }
  ];
  // "cotton" sits mid-word in the second row's LABEL (rank 2) and at the start of the first row's
  // HINT (rank 3). Every label match, however weak, is the better answer.
  expect(filterOptions(options, "cotton").map((option) => option.value)).toEqual(["label", "hint"]);
});

test("the country picker hands the name down as a hint", () => {
  const source = read("components", "forms", "PhoneField.tsx");
  const options = between(source, "const options = useMemo(", "function emit(");
  expect(options, "the dial code is still the label — the trigger is a 9rem column").toContain(
    "label: `${flagEmoji(entry.iso2)} ${entry.dialCode}`"
  );
  expect(options, "…and the name is the searchable second column").toContain("hint: entry.name");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The cap notice cannot tell a reader to use a box that is not there
 * ──────────────────────────────────────────────────────────────────────────── */

test("the cap sentence states the window, counts pinned rows apart, and ends in the caller's advice", () => {
  expect(capNoticeSentence({ shown: 80, pinned: 0, total: 246, hint: CAP_HINT_WITH_SEARCH })).toBe(
    "Showing the first 80 of 246. Keep typing to narrow the list."
  );
  // Pinned rows are a different KIND of row, so they are never folded into "the first 81".
  expect(capNoticeSentence({ shown: 80, pinned: 1, total: 246, hint: CAP_HINT_WITH_SEARCH })).toBe(
    "Showing the first 80 of 246, plus 1 already selected. Keep typing to narrow the list."
  );
  // And the sentence that is NOT an instruction to use a filter box.
  expect(CAP_HINT_WITHOUT_SEARCH).not.toContain("typing");
  expect(
    capNoticeSentence({ shown: 80, pinned: 0, total: 2000, hint: "Use the search box above." })
  ).toBe("Showing the first 80 of 2000. Use the search box above.");
});

test("the hint is chosen by whether there IS a filter box, and a caller may name its own control", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    The expression moved out of the JSX and onto a named const when the panel grew a second possible
    truncation sentence (`serverQuery.truncated`, whose total is unknowable) — but the RULE it
    encodes is unchanged and is the thing under test: the advice clause is the caller's where they
    gave one, and otherwise depends on whether this panel actually has a box to type into.
  */
  const uses = source.match(
    /const capAdvice = capHint \?\? \(withSearch \? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH\);/g
  );
  expect(uses?.length, "both the single- and the multi-select footer").toBe(2);
  expect(
    source,
    "the old unconditional sentence must not come back"
  ).not.toContain("Keep typing to narrow the list.\n");
});

/*
  ── THIS TEST LOST A SUBJECT, AND LOSING IT IS THE POINT ────────────────────────────────────────

  It used to hold TWO files to this rule: the viewers panel's people picker and `/design-review`'s
  workshop picker. The rule itself is unchanged and is still exactly right — a panel that turns its
  own filter box off over a long list must name the control that does reach the rest, or it prints
  "the rest are not drawn" about an instrument the reader cannot find.

  What changed is that `/design-review` stopped being a control of that kind. `serverQuery` (§2.8)
  points its panel's own box at `GET /design-workshops?search=`, so the box that reaches the rest IS
  this one, `searchable={false}` and the `capHint` naming an external box both went with it, and the
  default clause ("Keep typing to narrow the list") is true there for the first time. Asserting the
  old shape would now pin the defect rather than the fix, and it was in direct contradiction with
  "the three server-searched pickers drive the repository and never re-filter its answer" below,
  which asserts that same file no longer contains `capHint=` at all. Two tests, one file, opposite
  claims: the stale one gives way.

  The viewers panel stays, and stays for a reason that has not moved. Its PEOPLE picker assembles
  its options from three sources — the server's current answer, everyone already holding a row, and
  everyone ticked in this sitting — and pins them; the panel's own box has therefore never been the
  thing to point at the server, and the `SearchInput` above it still is. Its WORKSHOP picker, which
  had the same fault as `/design-review`, took the same fix.
*/
test("the panel that overrules a long list names the control that does reach the rest", () => {
  const source = read("components", "settings", "DesignWorkshopViewersPanel.tsx");
  expect(source, "the people picker turns the panel's own filter box off").toContain(
    "searchable={false}"
  );
  expect(source, "and says where the rest are").toContain('capHint="Use the search box above');
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. One index domain for the highlight
 * ──────────────────────────────────────────────────────────────────────────── */

test("type-ahead skips disabled rows and answers against the list it was handed", () => {
  const rows: SelectOption[] = [
    { value: "a", label: "Anvil", disabled: true },
    { value: "b", label: "Awl" },
    { value: "c", label: "Bamboo comb" }
  ];
  expect(typeaheadIndex(rows, "a")).toBe(1);
  expect(typeaheadIndex(rows, "b")).toBe(2);
  expect(typeaheadIndex(rows, "z")).toBe(-1);
  // Folded on both sides, so a diacritic in the corpus is reachable from a plain keyboard.
  expect(typeaheadIndex([{ value: "x", label: "Ahmedābād" }], "ahmed")).toBe(0);
});

test("neither component takes a highlight index out of `options`", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    THE SHAPE THAT SHIPPED, banned by name: `options.findIndex(...)` whose answer went into
    `setHighlight`. `highlight` indexes `rendered`, and past `RENDER_CAP` the two are different
    lists — so on the one call site that is both non-searchable and long (the design workshop viewer
    picker, a permissions control over up to 2000 accounts) a letter highlighted an unrelated row and
    the Space that followed ticked that person.
  */
  expect(source).not.toContain("options.findIndex((option) => !option.disabled && fold(");
  const uses = source.match(/typeaheadIndex\(rendered, /g);
  expect(uses?.length, "both components read the rendered list for a highlight").toBe(2);
  // The single-select's CLOSED branch still searches the whole corpus, because there it sets a VALUE
  // and nothing is hidden from it.
  expect(source).toContain("typeaheadIndex(options, typed)");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. Home and End belong to the caret inside a text box
 * ──────────────────────────────────────────────────────────────────────────── */

test("Home and End stand down inside the filter box, and arrows do not", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  const guards = source.match(/if \(!open \|\| textEntry\) return false;/g);
  expect(guards?.length, "Home and End, in both components").toBe(4);
  const wired = source.match(/onKeyDown=\{onFilterKeyDown\}/g);
  expect(wired?.length, "both filter boxes go through the text-entry branch").toBe(2);
  const handlers = source.match(/function onFilterKeyDown\(event: React\.KeyboardEvent<HTMLInputElement>\) \{\n {4}navigate\(event, true\);/g);
  expect(handlers?.length, "…and it is the same `navigate`, told where it is").toBe(2);
  // The arrow keys are the other half of the ARIA editable-combobox pattern and must NOT stand down:
  // without them the box is a text field beside a list nobody can reach.
  expect(source).not.toContain('case "ArrowDown":\n        if (!open || textEntry)');
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The district pickers, whose list straddles the threshold per state
 * ──────────────────────────────────────────────────────────────────────────── */

test("all three district pickers pass `searchable` rather than letting the count decide", () => {
  const sites: ReadonlyArray<readonly [string, readonly string[], string]> = [
    ["record forms", ["components", "forms", "LocationFields.tsx"], 'name="district"'],
    ["stage address", ["components", "designworkshop", "StageAddressField.tsx"], 'placeholder="Select district"'],
    ["recording place", ["components", "designworkshop", "StageRecordingPlace.tsx"], 'ariaLabel="District"']
  ];
  for (const [name, path, marker] of sites) {
    const source = read(...path);
    const at = source.indexOf(marker);
    expect(at, `${name}: ${marker} not found`).toBeGreaterThan(-1);
    /*
      Read in a window around the marker rather than over the whole file, because every one of these
      files holds a STATE picker too and a file-wide `toContain("searchable")` would pass on that.
      Goa has 2 districts and Uttar Pradesh 75, so with `SEARCH_THRESHOLD` in charge the same
      required field grew a filter box for one state and lost it for the next.
    */
    const window = source.slice(Math.max(0, at - 1200), at + 1200);
    expect(window, `${name}: the district picker must pass searchable`).toMatch(/\n\s*searchable\b/);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. A keystroke that means "move on" changes nothing
 * ──────────────────────────────────────────────────────────────────────────── */

test("Tab out of the filter box commits only a highlight the reader moved", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(
    source,
    "the unguarded commit — Tab took row 0 of the query and replaced a filled field"
  ).not.toContain("if (safeHighlight >= 0 && rendered[safeHighlight]) onChange(rendered[safeHighlight].value);");
  expect(source).toContain("if (highlightTouched && safeHighlight >= 0 && rendered[safeHighlight]) {");
  // Every deliberate aim at a row arms it; typing and a fresh open do not.
  expect(source.match(/setHighlightTouched\(true\)/g)?.length, "arrows, Home, End, hover").toBe(6);
  expect(source.match(/setHighlightTouched\(false\)/g)?.length, "open, seed, and every query change").toBe(4);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 7. No long list is left on a raw <select>
 * ──────────────────────────────────────────────────────────────────────────── */

test("the photo-intake destination picker is the themed control, with its two groups intact", () => {
  const source = read("app", "(protected)", "design-workshops", "[id]", "photos", "page.tsx");
  expect(withoutComments(source), "the raw <select> is gone").not.toMatch(/<select[\s>]/);
  expect(source, "…and so is its concession").not.toContain("THE ONE LONG LIST IN THE APP WITH NO FILTER BOX");
  expect(source).toContain("<Dropdown");
  expect(source).toContain("searchable");
  // The grouping was the blocker, so it is what must survive the swap.
  expect(source).toContain('const PROPOSED_GROUP = "Proposed from the capture date"');
  expect(source).toContain('const EVERYWHERE_GROUP = "Every other place a photograph can go"');
  // A destination proposed must appear ONCE: two rows sharing a value is a control that cannot say
  // which is selected, and here the value is also the React key.
  expect(source).toContain("!proposedKeys.has(destination.key)");
});

test("the sketch/prototype row picker is record-backed and now says so", () => {
  const source = read("components", "sketches", "UploadTabHost.tsx");
  expect(withoutComments(source), "the raw <select> is gone").not.toMatch(/<select[\s>]/);
  const picker = source.slice(source.indexOf("function RowPicker({"));
  expect(picker).toContain("<Dropdown");
  expect(picker).toContain("searchable");
  // A `<label>` may wrap a `<select>`; it cannot name a `<button>`, so the slot is a `<div>` and the
  // name is carried explicitly. (`label` the PROP is still there — it is the text.)
  expect(withoutComments(picker)).not.toMatch(/<label[\s>]/);
  expect(picker).toContain("ariaLabel={label}");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 8. The roster multi-select has a keyboard
 * ──────────────────────────────────────────────────────────────────────────── */

test("the roster picker's search box owns its listbox and moves a highlight", () => {
  const source = read("components", "designworkshop", "StageReferenceField.tsx");
  // The roster picker is the last export in the file, so the slice runs to the end of it.
  const roster = source.slice(source.indexOf("export function StageReferenceMultiPicker"));
  for (const attribute of [
    'role="combobox"',
    "aria-controls={listboxId}",
    'aria-autocomplete="list"',
    "aria-activedescendant="
  ]) {
    expect(roster, `the roster's box must carry ${attribute}`).toContain(attribute);
  }
  expect(roster, "arrows move the highlight").toContain('if (event.key === "ArrowDown")');
  expect(roster, "Enter ticks the highlighted row").toContain("if (option) toggleOption(option);");
  expect(roster, "…and does not reach the record form's Enter walker").toContain("event.preventDefault();");
  expect(roster, "the listbox is owned").toContain("<ul id={listboxId} role=\"listbox\"");
});

test("no purple highlight in the reference pickers is left without its dark counterpart", () => {
  const source = read("components", "designworkshop", "StageReferenceField.tsx");
  /*
    The brand ramp does not invert — purple-50 is near-white in both themes — so a bare
    `bg-purple-50` paints a white bar across a dark menu. Both pickers in this file had one.
  */
  const bare = source.match(/"bg-purple-50"/g);
  expect(bare, "every bg-purple-50 must be paired with dark:bg-purple-950").toBeNull();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 9. A `Field` names the control inside it
 * ──────────────────────────────────────────────────────────────────────────── */

test("both labelled slots publish their label id, and the trigger composes a name from it", () => {
  const field = read("components", "FormControls.tsx");
  expect(field, "`Field` publishes").toContain("<FieldLabelProvider value={labelId}>");
  expect(field, "…and the span it points at carries the id").toContain('<span id={labelId} className="field-label">');

  const block = read("components", "tasks", "TaskPrimitives.tsx");
  expect(block, "`FieldBlock` publishes too").toContain("<FieldLabelProvider value={id}>");

  const select = read("components", "ui", "SearchableSelect.tsx");
  const composed = select.match(
    /aria-labelledby=\{!ariaLabel && fieldLabelId \? `\$\{fieldLabelId\} \$\{triggerId\}` : undefined\}/g
  );
  /*
    BOTH IDS AND IN THAT ORDER. `aria-labelledby` REPLACES name-from-content, so pointing it at the
    label alone would announce "Craft" and drop the value — swapping one half-named control for the
    other. And it never overrules an explicit `ariaLabel`, which on the multi-select already carries
    the selection summary.
  */
  expect(composed?.length, "both triggers").toBe(2);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 10. Ticking a row must not renumber the list
 * ──────────────────────────────────────────────────────────────────────────── */

test("the pinned set is a snapshot, taken on open, on a query change, and on a new answer", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    The hook takes `pins` — the frozen set — and never the live `chosen`/`values`. The argument moved
    from fourth-of-four to fourth-of-five when `serverAnswered` was added, so the assertion is
    anchored on the argument and not on the whole call.
  */
  // `\s` and not `\n`: this file is checked out CRLF on Windows, and an assertion anchored on a bare
  // newline passes on one developer's machine and fails on the next for a reason that has nothing to
  // do with the code it is guarding.
  const calls = source.match(/pins,\s+serverDriven\s/g);
  expect(calls?.length, "both components, both passing the frozen set").toBe(2);
  expect(source, "the window must not be recomputed from the live selection").not.toContain(
    "withSearch, chosen"
  );
  expect(source).not.toContain("withSearch, new Set(values)");
  // Four moments per component now: opening, seeding a filter from a keystroke, typing, and — on a
  // `serverQuery` control — the answer landing. The multi has a fifth, in its type-ahead open.
  expect(source.match(/setPins\(/g)?.length, "single-select ×4, multi-select ×5").toBe(9);
});

test("a server-searched panel re-snapshots its pins on the ANSWER, never on the keystroke", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    With a server query the array is replaced when each debounced answer lands, several renders after
    the keystroke that asked for it. Pinning on the keystroke pins against the list the reader is
    about to stop looking at; pinning on every tick of a multi-select IS the live-selection defect
    `useSelectList` was written to close. `options` identity is the only honest signal, and the
    multi-select must read its selection through a ref so that ticking cannot re-trigger the effect.
  */
  const effects = source.match(/}, \[serverDriven, options(, value)?\]\);/g);
  expect(effects?.length, "one effect per component, keyed on the answer").toBe(2);
  /*
    Read WITHOUT comments, for the reason `withoutComments` was written: the effect this bans is
    documented at length two lines above it, so a plain `toContain` fails on the very prose that
    proves the exclusion was deliberate.
  */
  expect(
    withoutComments(source),
    "the multi-select's effect must NOT depend on the live selection"
  ).not.toContain("[serverDriven, options, values]");
  expect(source, "…so it reads it through a ref instead").toContain(
    "setPins(new Set(selectionRef.current));"
  );
  // And a query change stands down on that branch, in both components.
  expect(source.match(/if \(!serverDriven\) setPins\(/g)?.length, "seedFilter + onQueryChange, ×2").toBe(4);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Grouping, and the page size that has to agree with the cap
 * ──────────────────────────────────────────────────────────────────────────── */

test("grouping preserves each row's index into the rendered array", () => {
  const rendered: SelectOption[] = [
    { value: "", label: "Leave out" },
    { value: "p1", label: "Proposal one", group: "Proposed" },
    { value: "e1", label: "Everything one", group: "Everywhere" },
    { value: "p2", label: "Proposal two", group: "Proposed" }
  ];
  const buckets = groupRows(rendered);
  // Ungrouped first — it belongs to neither heading, and filing it under the first would make the
  // commonest answer look like a proposal.
  expect(buckets.map((bucket) => bucket.group)).toEqual([null, "Proposed", "Everywhere"]);
  // The indices are the whole point: they are what `highlight`, the option ids and the commit read.
  expect(buckets.flatMap((bucket) => bucket.rows.map((row) => row.index))).toEqual([0, 1, 3, 2]);
  expect(buckets[1].rows.map((row) => row.option.value)).toEqual(["p1", "p2"]);
});

test("an ungrouped list is one bucket, so nothing else in the app pays for grouping", () => {
  const rendered: SelectOption[] = [
    { value: "a", label: "A" },
    { value: "b", label: "B" }
  ];
  expect(groupRows(rendered)).toEqual([
    { group: null, rows: [{ option: rendered[0], index: 0 }, { option: rendered[1], index: 1 }] }
  ]);
});

test("the design-review chooser asks for exactly as many rows as the panel can draw", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  /*
    100 rows into a control that draws 80 printed two truncation sentences with two different totals,
    one directly above the other — and between 81 and 100 the page said nothing at all while the
    panel silently dropped rows. A page size written as the cap cannot drift from it.
  */
  expect(source).toContain("const CHOOSER_PAGE = RENDER_CAP;");
  expect(source, "the literal must not come back").not.toContain("const CHOOSER_PAGE = 100;");
  expect(RENDER_CAP).toBe(80);
  // And the request that makes the search box mean anything.
  expect(source).toContain("search: titleQuery.trim() || undefined");
});

test("the chooser no longer asserts absence from a list that has not answered", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  const now = between(source, "WHICH ROUND IS ACTUALLY OPEN", "{workshopId ? (\n        <>");
  /*
    `listedRow` is derived from `workshops`, which is null for the whole fetch and stays null on
    failure, so the two-branch version asserted "It is not in the shortcut above" about a list that
    had said nothing — while the chooser three inches up was rendering "Looking for the design
    workshops you can open…", both inside `aria-live` regions.
  */
  expect(now, "the claim is now gated on the list having answered").toContain(") : ready ? (");
  expect(now).toContain("has not answered yet, so it cannot say whether this is one of yours.");
  expect(now).toContain("could not be loaded, so it cannot say whether this is one of yours.");
});

test("the header no longer says the pool round is only what a workshop declared finished", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  const header = between(source, 'title="Design review"\n        /*', 'icon={<Globe2');
  /*
    `pool_visible` is `if is_member or admin: return list(subjects)` — the whole collection,
    `peerRoundClosedAt` irrelevant — and `ranked_payload` does not put `pool_open` on the wire, so no
    client can mark which rows were opened. The old description was true only of the stranger branch,
    which is not the branch a dropdown of the whole archive lands an admin on.
  */
  expect(header).toContain("the same pieces its own Review tab lists");
  expect(header, "the false frame must not survive in the description").not.toContain(
    'description="Sketches and prototypes that a design workshop has declared finished'
  );
  // The entity chips restated it too.
  expect(source).toContain('hint: "The prototypes in this workshop\'s pool round."');
});

test("the truncation sentence names the date that actually does the sorting", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  /*
    The server orders `createdAt: "desc"`; `workshopLabel` prints `startDate ?? createdAt`, and
    `startDate` is typed in by hand — so the visible column is not monotonic and "newest first"
    pointed a reader at the wrong end of eighty rows.
  */
  const notice = between(source, 'id="design-review-truncation"', "</p>");
  expect(notice).not.toContain("newest first");
  expect(notice).toContain("most recently added to the repository");
  expect(notice).toContain("not the order the dates on the rows read");
});

test("the chooser's honest paragraphs are reachable to assistive technology", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  expect(source).toContain('describedBy="design-review-scope design-review-empty design-review-truncation"');
  expect(source).toContain('id="design-review-empty"');
  expect(source).toContain('id="design-review-truncation"');
  // The empty case ALSO needs a live region: its trigger is `disabled`, so it is not focusable and
  // no description of it can ever be reached by landing on it.
  const empty = between(source, 'id="design-review-empty"', "</p>");
  expect(empty).toContain('aria-live="polite"');
  // And the scope sentence must account for the first branch of `visible_to_clause`, `createdById`.
  expect(source).toContain("the ones you created, the ones an\n            admin granted you");
});

/* ────────────────────────────────────────────────────────────────────────────
 * W1 — the three capabilities wave 2 is about to code against, and the rule
 *      that every one of them is absent by default
 *
 * DROPDOWN_DESIGN.md §5 "W1 — the select primitive". This primitive has about
 * forty live call sites, so the assertions below are as much about what did NOT
 * change as about what did: each new prop's absent case has to be the behaviour
 * the file had before it existed, or twenty callers break at once.
 * ──────────────────────────────────────────────────────────────────────────── */

test("every new prop is optional and its absence is the old behaviour", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  for (const declaration of [
    "serverQuery?: SelectServerQuery;",
    "noneLabel?: string;",
    "bulk?: boolean;"
  ]) {
    expect(source, `${declaration} must be optional`).toContain(declaration);
  }
  // `bulk` is the only one with a default, and the default is what the button has always done.
  expect(source, "the bulk button stays on unless a caller turns it off").toContain("bulk = true,");
  // The two that gate on presence must gate on presence and never on a default object.
  expect(source).toContain("const serverDriven = serverQuery != null;");
  expect(source, "a default serverQuery would put every caller on the server branch").not.toContain(
    "serverQuery = {"
  );

  const adapters = read("components", "ui", "Dropdown.tsx");
  // All three adapters forward what applies to them, or a wave-2 caller cannot reach the capability
  // at all: #16 and #17 in the design's inventory are `Dropdown`s, not ComboBoxes.
  expect(adapters.match(/serverQuery=\{serverQuery\}/g)?.length, "Dropdown, MultiSelect, ComboBox").toBe(3);
  expect(adapters.match(/noneLabel=\{noneLabel\}/g)?.length, "Dropdown and ComboBox").toBe(2);
  expect(adapters).toContain("bulk={bulk}");
});

test("`serverQuery` forces the box on, and takes the local filter pass off", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    They cannot both be the box. `options` already IS the answer to `query`, so a second local pass
    drops every row the server matched on a column the label does not show — `workshopCode` is in
    `GET /design-workshops`' search and is deliberately not in the hint, so a reader typing a code
    off a join card would have the server find it and the panel hide it.
  */
  expect(source).toContain("searchable && !serverAnswered ? filterOptions(options, query) : options");
  // And a server query with no box is a fetch nobody can reach, so it overrules `searchable={false}`.
  const forced = source.match(
    /const withSearch = serverDriven \|\| \(searchable \?\? options\.length >= SEARCH_THRESHOLD\);/g
  );
  expect(forced?.length, "both components").toBe(2);
  // The caller's term is the caller's: closing the panel clears the LOCAL box only, never theirs.
  expect(source.match(/setOwnQuery\(""\);/g)?.length, "one `close` per component").toBe(2);
  expect(source, "the panel must not re-fetch an unnarrowed list on every dismissal").not.toContain(
    "serverQuery.onChange(\"\")"
  );
});

test("the box is named for what it actually reaches", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    §4.8's rule, applied to the one control this pass adds a state to: every control gets a real
    accessible name, and the name has to be TRUE. "Filter workshops" over a box that searches the
    whole list understates it to exactly the readers who have no panel in front of them to infer it
    from. The pending word is `aria-hidden` because the live region already announces it — hearing
    "Searching" twice for one keystroke is worse than not seeing it at all.
  */
  expect(source.match(/placeholder=\{serverDriven \? "Type to search" : "Type to filter"\}/g)?.length).toBe(2);
  expect(source.match(/\? `Search \$\{ariaLabel\}`/g)?.length, "both panels").toBe(2);
  expect(source.match(/\? `Filter \$\{ariaLabel\}`/g)?.length, "…and both keep the old name").toBe(2);
  expect(source, "the pending word is announced once, by the live region").toContain(
    "<span aria-hidden className=\"shrink-0 whitespace-nowrap text-xs text-ink-500\">"
  );
  // A word, not a spinner: nothing here for either reduced-motion switch to have to reach.
  const searchRow = between(source, "function SearchRow({", " * THE \"NONE\" ROW");
  expect(withoutComments(searchRow)).not.toMatch(/animate-|motion\.|transition-transform/);
});

test("the empty line is three-way on a server-searched panel and two-way everywhere else", () => {
  const settled = { pending: false };
  /*
    Nothing typed: the caller's sentence, and the panel never composes with it. `emptyLabel` is where
    the six sentences of the offline contract land — bundled, cached-and-stale, empty-because-offline,
    could-not-be-listed, genuinely-empty-scoped, genuinely-empty-unscoped — and the panel has no
    business knowing which of them it is holding.
  */
  expect(
    emptyListSentence({
      emptyLabel: "No design workshops have been recorded yet.",
      term: "",
      server: false,
      ...settled
    })
  ).toBe("No design workshops have been recorded yet.");
  expect(
    emptyListSentence({
      emptyLabel: "No design workshops have been recorded yet.",
      term: "",
      server: true,
      ...settled
    })
  ).toBe("No design workshops have been recorded yet.");

  // A local box: exactly the two words this control has always drawn.
  expect(
    emptyListSentence({ emptyLabel: "No options", term: "bagru", server: false, ...settled })
  ).toBe("No matches");

  // A server box that answered: a stronger claim, and it says so, because sixteen controls in this
  // app print "No matches" about records that exist on page 4.
  expect(
    emptyListSentence({ emptyLabel: "No options", term: "bagru", server: true, ...settled })
  ).toBe(
    "No matches for “bagru”. This box searches the whole list, not only the rows drawn here."
  );
  expect(serverNoMatchSentence("  bagru  "), "the term is trimmed before it is quoted").toContain(
    "“bagru”"
  );

  // A server box mid-flight: not empty, and never the caller's "there are none" sentence. This is the
  // arm the local branch has no way to say at all, and the window it covers is a second and a half.
  expect(
    emptyListSentence({ emptyLabel: "No design workshops.", term: "bagru", server: true, pending: true })
  ).toBe(SEARCHING_LABEL);
  expect(
    emptyListSentence({ emptyLabel: "No design workshops.", term: "", server: true, pending: true })
  ).toBe(SEARCHING_LABEL);
  // …and `pending` is inert without a server query, so no existing caller can reach it.
  expect(emptyListSentence({ emptyLabel: "No options", term: "", server: false, pending: true })).toBe(
    "No options"
  );
});

test("the empty line is asked of the CORPUS, so a `noneLabel` row cannot suppress it", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    `rendered` may carry one row that is not in the corpus at all. Reading it here would mean a picker
    whose only row is "Not filed under a design workshop" never printed the sentence saying whether
    the list is empty, still loading or failed to load — the absence-reads-as-non-existence bug
    arriving through the fix for a different one.
  */
  expect(source.match(/\{windowed\.length === 0 \? \(/g)?.length, "both components").toBe(2);
  expect(source).not.toContain("{rendered.length === 0 ? (");
  // …and the counts under the panel are the corpus's too, so a none row is never "the first 81".
  expect(source.match(/shown: windowed\.length - pinned,/g)?.length, "both footers").toBe(2);
});

test("the `noneLabel` row is first, ungrouped, exempt from the cap and hidden while searching", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  // Prepended AFTER the window is cut, so an un-file row can never be the row a truncation drops.
  expect(source).toContain("(noneRow ? [noneRow, ...windowed] : windowed)");
  // Ungrouped, so `groupRows` files it in the null bucket, which is drawn above every heading.
  expect(source).toContain('return { value: "", label: noneLabel };');
  // A query withdraws the ROW — Android's `!searching` rule — and nothing else.
  expect(source).toContain("const noneRow = noneOption && !query.trim() ? noneOption : null;");
  /*
    THE TRIGGER'S LABEL IS NOT GATED ON THE QUERY, and the split is the point of the two names. The
    trigger describes the FIELD'S ANSWER; folded together with the row, opening a picker over a
    cleared design-workshop field and typing one letter made it fall back from "Not filed under a
    design workshop" to "Select a design workshop" and back again on Backspace — a field reporting the
    state of the panel instead of the state of the record.
  */
  expect(source).toContain('(noneOption && value === "" ? noneOption : undefined)');
  expect(source, "…while keeping the muted rung that says nothing is filed").toContain(
    '(!selected || showingNone) && "text-ink-500"'
  );
  /*
    AND IT DEFERS TO A CALLER THAT ALREADY BUILT ONE. `WorkshopSelect` prepends its own `value: ""`
    row today and is specified to adopt `noneLabel` instead; an agent who adds the prop and forgets to
    delete the row would otherwise get two rows sharing the React key `""`, which is a list offering
    the same answer twice and unable to say which of the two is selected.
  */
  expect(source).toContain('if (options.some((option) => option.value === "")) return null;');
  // And it is the single-select's alone: a multi says "none" by holding an empty array.
  const multi = source.slice(source.indexOf("export function SearchableMultiSelect("));
  expect(multi, "no none row on the multi-select").not.toContain("noneOptionFor(");
});

test("the none row keeps `groupRows`' index contract, which is what the highlight reads", () => {
  const rendered: SelectOption[] = [
    { value: "", label: "Not filed under a design workshop" },
    { value: "r", label: "The one already on this record", group: "Already on this record" },
    { value: "o1", label: "Bagru block printing", group: "Open" },
    { value: "s1", label: "Kutch embroidery 2024", group: "Submitted and archived" },
    { value: "o2", label: "Blue pottery", group: "Open" }
  ];
  const buckets = groupRows(rendered);
  // §2.4's reading order: the none row alone and ungrouped, then the recovered row, then the rest in
  // first-appearance order.
  expect(buckets.map((bucket) => bucket.group)).toEqual([
    null,
    "Already on this record",
    "Open",
    "Submitted and archived"
  ]);
  expect(buckets[0].rows.map((row) => row.option.value)).toEqual([""]);
  // The indices still address the RENDERED array — the none row is 0, and nothing else renumbered.
  expect(buckets.flatMap((bucket) => bucket.rows.map((row) => row.index))).toEqual([0, 1, 2, 4, 3]);
});

test("`bulk={false}` removes the button, and a truncated answer stops it claiming `all`", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  /*
    Wired to a filter, "Select all N" manufactures the state this repo forbids: all ticked and nothing
    ticked, both meaning everything. And over a server-truncated page "all" is not all — on a
    permissions control that is an admin told they granted every matching colleague when they granted
    the first eighty.
  */
  expect(source).toContain("bulk && bulkRows.length > 0 ? (");
  expect(source, "the rows and the switch must not share a name").not.toContain(
    "const bulk = useMemo("
  );
  expect(source).toContain("`Select the ${bulkRows.length} shown`");
  expect(source).toContain("`Clear the ${bulkRows.length} shown`");
  // The old wordings survive for the honest case, which is every control that is not truncated.
  expect(source).toContain("`Select all ${bulkRows.length}`");
  expect(source).toContain("`Select ${bulkRows.length} matching`");
});

test("a server-reported cut is stated even though its total is unknowable, and it wins", () => {
  // The caller asks for exactly RENDER_CAP rows, so `capNoticeSentence` can never fire on that
  // branch: without this sentence a list cut on the server is drawn in complete silence.
  expect(
    unknownTotalNoticeSentence({ shown: 80, pinned: 0, term: "bagru", hint: CAP_HINT_WITH_SEARCH })
  ).toBe(
    "Showing the first 80. More match “bagru” than are drawn, and the server did not say how many. Keep typing to narrow the list."
  );
  expect(
    unknownTotalNoticeSentence({ shown: 80, pinned: 2, term: "", hint: CAP_HINT_WITH_SEARCH })
  ).toBe(
    "Showing the first 80, plus 2 already selected. There are more than are drawn, and the server did not say how many. Keep typing to narrow the list."
  );
  // Pinned rows are counted apart here for the same reason as in `capNoticeSentence`.
  expect(unknownTotalNoticeSentence({ shown: 80, pinned: 0, term: "", hint: "x" })).not.toContain(
    "already selected"
  );

  /*
    THE ORDER IS THE RULING. A known total that is itself a truncated count is a worse lie than an
    admitted unknown: "Showing the first 80 of 100" over a server that cut at 100 tells a reader there
    are twenty more when there may be nine hundred, and they stop looking. So with both live, the flag
    wins and the arithmetic is not printed at all.
  */
  const both = truncationSentence({
    shown: 80,
    pinned: 0,
    total: 100,
    capped: 20,
    term: "bagru",
    hint: CAP_HINT_WITH_SEARCH,
    serverTruncated: true
  });
  expect(both).toContain("the server did not say how many");
  expect(both, "the arithmetic must not be printed over a truncated count").not.toContain("of 100");

  // The locally-counted cut is untouched for every control that is not server-searched.
  expect(
    truncationSentence({
      shown: 80,
      pinned: 1,
      total: 246,
      capped: 166,
      term: "",
      hint: CAP_HINT_WITH_SEARCH,
      serverTruncated: false
    })
  ).toBe("Showing the first 80 of 246, plus 1 already selected. Keep typing to narrow the list.");

  // Not cut at all: silence, which is what every whole list has always drawn.
  expect(
    truncationSentence({
      shown: 9,
      pinned: 0,
      total: 9,
      capped: 0,
      term: "",
      hint: CAP_HINT_WITH_SEARCH,
      serverTruncated: false
    })
  ).toBe("");

  // Nothing drawn: silence too. The empty line above is already saying what happened, and "Showing
  // the first 0" underneath it would be a second sentence contradicting the first.
  expect(
    truncationSentence({
      shown: 0,
      pinned: 0,
      total: 0,
      capped: 0,
      term: "bagru",
      hint: CAP_HINT_WITH_SEARCH,
      serverTruncated: true
    })
  ).toBe("");
});

test("the live region says the three things only a server-searched panel knows", () => {
  // The two arms every control has always announced, byte for byte.
  expect(
    listAnnouncement({ total: 74, matched: 6, term: "cot", server: false, pending: false, truncated: false })
  ).toBe("6 of 74 options match cot");
  expect(
    listAnnouncement({ total: 74, matched: 74, term: "", server: false, pending: false, truncated: false })
  ).toBe("74 options");

  // A server answer is the whole answer, so "80 of 80 match" would be arithmetic and not an answer.
  expect(
    listAnnouncement({ total: 12, matched: 12, term: "bagru", server: true, pending: false, truncated: false })
  ).toBe("12 options match bagru");
  // In flight — so silence reads as "wait", not as "there are none".
  expect(
    listAnnouncement({ total: 0, matched: 0, term: "bagru", server: true, pending: true, truncated: false })
  ).toBe("Searching for bagru");
  expect(
    listAnnouncement({ total: 0, matched: 0, term: "", server: true, pending: true, truncated: false })
  ).toBe("Loading options");
  // …and the cut, which a sighted reader gets from the footer and nobody else got at all.
  expect(
    listAnnouncement({ total: 80, matched: 80, term: "bagru", server: true, pending: false, truncated: true })
  ).toBe("80 options match bagru, and more match than are drawn");
  expect(
    listAnnouncement({ total: 80, matched: 80, term: "", server: true, pending: false, truncated: true })
  ).toBe("80 options, and more exist than are drawn");
});

test("a multi-select counts what it HOLDS and names what it can, and never the other way round", () => {
  // Every caller that existed before `serverQuery` hands in a whole list, so these two arms are the
  // sentence this control has always produced, character for character.
  expect(selectionSummarySentence({ selected: 0, names: [] })).toBe("Nothing selected");
  expect(selectionSummarySentence({ selected: 2, names: ["Bagru block printing", "Blue pottery"] })).toBe(
    "2 selected: Bagru block printing, Blue pottery"
  );
  const seven = ["a", "b", "c", "d", "e", "f", "g"];
  expect(selectionSummarySentence({ selected: 7, names: seven })).toBe(
    "7 selected, including a, b, c, d, e, f"
  );
  expect(SUMMARY_NAMES).toBe(6);

  /*
    AND THE ARM `serverQuery` OPENS. The names can only come out of the array in hand, and with a
    server query that array is one answer to one term — so a reader who ticks three workshops and
    then types a fourth term leaves the panel holding three picks it can name one of. Counting the
    names made the button read "3 selected" while its accessible name said "1 selected", and where
    the new answer carried none of them, "Nothing selected" about a control holding three. The count
    is the caller's array; only the names are allowed to degrade.
  */
  expect(selectionSummarySentence({ selected: 3, names: ["Bagru block printing"] })).toBe(
    "3 selected, including Bagru block printing"
  );
  expect(
    selectionSummarySentence({ selected: 3, names: [] }),
    "no name is not no selection — and the sentence must not trail off into an empty list"
  ).toBe("3 selected");

  const source = read("components", "ui", "SearchableSelect.tsx");
  // The count must come from the caller's array and not from the labels it happened to resolve.
  expect(source).toContain("selected: values.length,");
  expect(source, "the old count is what made the announcement disagree with the button").not.toContain(
    "chosenLabels.length} selected"
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 11. One workshop, one option — `lib/workshopOptions.ts`
 *
 * The four columns §1.2 of DROPDOWN_DESIGN tabulates as disagreeing — the label shape, the sort,
 * the grouping and what happens to a stored value that is not on the page — collapsed to one
 * answer each, for BOTH tables, and asserted by CALLING the builders.
 *
 * PURE CALLS ONLY, AND THAT IS ALSO WHY THEY SURVIVE A WINDOWS CHECKOUT. Five specs in this suite
 * anchor on a literal "\n" in a source read and fail on a CRLF tree while passing on CI's LF one.
 * Nothing below reads a source file or matches a newline: every assertion is a function call and a
 * single-line string, which has no line endings to disagree about.
 * ──────────────────────────────────────────────────────────────────────────── */

/** A design-workshop row with only the fields under test named. */
const dw = (over: Partial<DesignWorkshopRow> & { id: string }): DesignWorkshopRow => ({
  title: "",
  status: "IN_PROGRESS",
  craftName: null,
  clusterName: null,
  state: null,
  startDate: null,
  createdAt: null,
  deletedAt: null,
  ...over
});

/** An ordinary-workshop row, same idea. */
const fw = (over: Partial<FieldWorkshopRow> & { id: string }): FieldWorkshopRow => ({ title: "", ...over });

const dwList = (
  rows: DesignWorkshopRow[],
  total: number | null = rows.length,
  truncated = false
): WorkshopListState<DesignWorkshopRow> => ({ kind: "ok", rows, total, truncated });

const fwList = (
  rows: FieldWorkshopRow[],
  total: number | null = rows.length,
  truncated = false
): WorkshopListState<FieldWorkshopRow> => ({ kind: "ok", rows, total, truncated });

/** No off-page merge, which is the arm that must never be reached by default. */
const REFUSE = { mode: "refuse" } as const;

/** A fixed clock, so "has this workshop's window closed" is testable without one. */
const AUG_2026 = Date.parse("2026-08-29T09:00:00Z");

const voice = (over: Partial<WorkshopListVoice> = {}): WorkshopListVoice => ({
  table: "design",
  scoped: true,
  online: true,
  ...over
});

test("the label is the title alone and everything that tells two workshops apart is in the hint", () => {
  const design = designWorkshopOptions(
    dwList([
      dw({
        id: "w1",
        // Trimmed, because a promoted column carries whatever stage 1 was saved with.
        title: "  Bagru block printing  ",
        craftName: "Block printing",
        clusterName: "Bagru",
        state: "Rajasthan",
        startDate: "2026-03-04T00:00:00Z"
      })
    ]),
    { group: true, offPage: REFUSE }
  ).options;

  // THE FINDING, IN TWO LINES: six label shapes ship today and this is the only one. The date is
  // NOT in the label — folding it in gives every row a shared suffix and demotes nothing, and it
  // is what makes typing a title lose to a coincidental craft match.
  expect(design[0].label).toBe("Bagru block printing");
  expect(design[0].hint).toBe("Block printing · Bagru · 2026-03-04");

  // `clusterName ?? state` read as "the first one that is ACTUALLY there". A stage field filled and
  // then cleared comes back as "" rather than null, so `??` alone keeps the blank, the join filter
  // then drops it, and the row loses its place entirely while `state` sat there with the answer.
  const cleared = designWorkshopOptions(
    dwList([
      dw({ id: "w2", title: "Kutch weaving", craftName: "Weaving", clusterName: "   ", state: "Gujarat", startDate: "2026-01-09" })
    ]),
    { group: true, offPage: REFUSE }
  ).options;
  expect(cleared[0].hint).toBe("Weaving · Gujarat · 2026-01-09");

  // A workshop created and abandoned before stage 1 has a title and nothing else — and sometimes
  // not even that. A blank row in a listbox cannot be described, searched for, or told from the
  // row above it.
  const bare = designWorkshopOptions(dwList([dw({ id: "w3", title: "   " })]), {
    group: true,
    offPage: REFUSE
  }).options;
  expect(bare[0].label).toBe(UNTITLED_WORKSHOP);
  expect(bare[0].hint, "no gaps, no stray separators — the parts that are absent are absent").toBeUndefined();

  // §2.6: submitted and archived rows ARE offered, and the status word is what stops one being
  // picked by accident. Not `disabled` — the server accepts the write and disabling it would turn a
  // read-only fact into a wrong write.
  const filed = designWorkshopOptions(
    dwList([dw({ id: "w4", title: "Blue pottery", status: "SUBMITTED", craftName: "Pottery", startDate: "2025-11-02" })]),
    { group: true, offPage: REFUSE }
  ).options;
  expect(filed[0].hint).toBe("Submitted · Pottery · 2025-11-02");
  expect(filed[0].disabled, "offered, marked, and never refused").toBeUndefined();

  // The other table, same shape, its own three facts: `place` where the design row has a craft and
  // a cluster, and the occurrence date it has always sorted by.
  const field = fieldWorkshopOptions(
    fwList([
      fw({ id: "f1", title: "Kullu weaving visit", place: "Kullu", startDate: "2026-02-02", endDate: "2026-02-04" }),
      fw({ id: "f2", title: "Sanganer dyeing", place: "Sanganer", date: "2026-04-01" })
    ]),
    { group: true, offPage: REFUSE, now: AUG_2026 }
  ).options;
  expect(field.find((option) => option.value === "f1")?.hint).toBe("Ended · Kullu · 2026-02-02");
  expect(field.find((option) => option.value === "f2")?.hint).toBe("Sanganer · 2026-04-01");
});

test("a workshop with no end date is open, and the end day itself is still in the window", () => {
  const rowsFor = (now: number) =>
    fieldWorkshopOptions(
      fwList([
        fw({ id: "closes", title: "Cluster visit", startDate: "2026-03-01", endDate: "2026-03-04" }),
        fw({ id: "never", title: "Standing programme", startDate: "2019-06-01" })
      ]),
      { group: true, offPage: REFUSE, now }
    ).options;

  // The whole of the end day is in-window — the backend's rule, mirrored by `endedLocally`. A naive
  // `endDate < now` would print "Ended" on a workshop the late-submission dialog will NOT fire for,
  // so the picker and the save would say two different things about one workshop in one minute.
  const duringTheLastDay = rowsFor(Date.parse("2026-03-04T23:00:00Z"));
  expect(duringTheLastDay.find((option) => option.value === "closes")?.group).toBeUndefined();

  const theDayAfter = rowsFor(Date.parse("2026-03-05T01:00:00Z"));
  expect(theDayAfter.find((option) => option.value === "closes")?.group).toBe(GROUP_ENDED);

  // §2.4 decides this on `endDate` ALONE. A seven-year-old workshop nobody closed is still open,
  // and inventing an end date for it would file it away from the reader for good.
  expect(theDayAfter.find((option) => option.value === "never")?.group).toBe(GROUP_OPEN);
});

test("the sort is by when the workshop RAN, newest first, and it is a total order", () => {
  const ordered = designWorkshopOptions(
    dwList([
      dw({ id: "b", title: "Zari work", startDate: "2026-01-01" }),
      dw({ id: "a", title: "Ajrakh", startDate: "2026-05-05" }),
      dw({ id: "c", title: "Chikankari", createdAt: "2026-03-03" }),
      dw({ id: "d", title: "Dhokra" })
    ]),
    { group: true, offPage: REFUSE }
  ).options;
  // `startDate ?? createdAt`, descending; an undated row folds to "" and sorts LAST rather than
  // becoming NaN and sorting arbitrarily.
  expect(ordered.map((option) => option.value)).toEqual(["a", "c", "b", "d"]);

  // THE TIE-BREAKS ARE NOT DECORATION. A five-day cluster visit is five rows with one startDate, and
  // an import batch shares a createdAt; without a total order the list reshuffles between two
  // renders of the same data and the row under the cursor is not the row that was there.
  const tied = designWorkshopOptions(
    dwList([
      dw({ id: "z", title: "Ajrakh", startDate: "2026-05-05" }),
      dw({ id: "a", title: "Bandhani", startDate: "2026-05-05" }),
      dw({ id: "b", title: "Ajrakh", startDate: "2026-05-05" })
    ]),
    { group: true, offPage: REFUSE }
  ).options;
  expect(tied.map((option) => option.value), "title ascending, then id ascending").toEqual(["b", "z", "a"]);

  // "A workshop entered into the system last is not the workshop that ran last." Every design
  // workshop picker in the app inherits `createdAt desc` from the server and not one re-sorts, so
  // this pair comes back the wrong way round on every one of them today.
  const field = fieldWorkshopOptions(
    fwList([
      fw({ id: "typed-in-today", title: "Backlog entry", startDate: "2019-04-04", createdAt: "2026-08-28" }),
      fw({ id: "ran-this-month", title: "Recent visit", startDate: "2026-08-01", createdAt: "2019-01-01" })
    ]),
    { group: true, offPage: REFUSE, now: AUG_2026 }
  ).options;
  expect(field.map((option) => option.value)).toEqual(["ran-this-month", "typed-in-today"]);
});

test("headings are all-or-nothing, in reading order, and no builder draws a none row", () => {
  const openOnly = designWorkshopOptions(
    dwList([dw({ id: "a", title: "Ajrakh" }), dw({ id: "b", title: "Bandhani", status: "COMPLETE" })]),
    { group: true, offPage: REFUSE }
  ).options;
  // One class present, so no headings at all: a single heading over the whole list distinguishes
  // nothing and costs a row of vertical space on a handset. `groupRows` collapses it to one bucket.
  expect(openOnly.every((option) => option.group === undefined)).toBe(true);
  expect(groupRows(openOnly).map((bucket) => bucket.group)).toEqual([null]);

  const mixed = designWorkshopOptions(
    dwList([
      dw({ id: "sub", title: "Blue pottery", status: "SUBMITTED", startDate: "2026-07-07" }),
      dw({ id: "open", title: "Ajrakh", status: "DRAFT", startDate: "2026-06-06" }),
      dw({ id: "arch", title: "Zari work", status: "ARCHIVED", startDate: "2026-05-05" })
    ]),
    { group: true, offPage: REFUSE }
  ).options;
  // EVERY row gets a heading once ANY row needs one. Group a few and leave the rest bare and the
  // bare ones draw ABOVE all the headings — "ungrouped first" — so the open workshops would render
  // as a fourth, unnamed category sitting above "Open".
  expect(mixed.every((option) => option.group !== undefined)).toBe(true);
  // Reading order comes from the order rows are EMITTED in, because `groupRows` buckets by first
  // appearance. Open before closed, and the two closed states share one heading.
  expect(groupRows(mixed).map((bucket) => bucket.group)).toEqual([GROUP_OPEN, GROUP_SUBMITTED_AND_ARCHIVED]);
  expect(mixed.map((option) => option.value)).toEqual(["open", "sub", "arch"]);

  // `group: false` is for a control whose REQUEST already narrowed the list to one class.
  const ungrouped = designWorkshopOptions(
    dwList([dw({ id: "sub", title: "Blue pottery", status: "SUBMITTED" }), dw({ id: "open", title: "Ajrakh" })]),
    { group: false, offPage: REFUSE }
  ).options;
  expect(ungrouped.every((option) => option.group === undefined)).toBe(true);

  // The other table's third heading is its own, and there is no "Submitted and archived" on it.
  const field = fieldWorkshopOptions(
    fwList([
      fw({ id: "over", title: "Kullu visit", startDate: "2026-02-02", endDate: "2026-02-04" }),
      fw({ id: "live", title: "Sanganer dyeing", startDate: "2026-08-02" })
    ]),
    { group: true, offPage: REFUSE, now: AUG_2026 }
  ).options;
  expect(groupRows(field).map((bucket) => bucket.group)).toEqual([GROUP_OPEN, GROUP_ENDED]);

  // §2.7: the "none" row belongs to `SearchableSelect.noneLabel` and to nothing else. Two layers
  // entitled to draw it means two rows sharing the React key "", a control that cannot say which of
  // them is selected, and R1's forbidden second state wearing a dropdown.
  for (const built of [openOnly, mixed, ungrouped, field]) {
    expect(built.some((option) => option.value === "")).toBe(false);
  }
});

test("an off-page value is recovered only where the caller said to recover it", () => {
  const stored = dw({ id: "gone", title: "Last year's workshop", startDate: "2025-02-02" });
  const page = [dw({ id: "here", title: "Ajrakh", startDate: "2026-06-06" })];

  // REFUSE merges nothing. `AdoptLocalDraftDialog`'s ruling: adoption is one-way and unrepeatable,
  // so a recovered row there is not a fact about a record, it is a DESTINATION.
  const refused = designWorkshopOptions(dwList(page), { group: true, offPage: REFUSE });
  expect(refused.options.map((option) => option.value)).toEqual(["here"]);
  expect(refused.recovered).toBe(false);

  // RECOVER merges it under its own heading, first. `WorkshopSelect`'s ruling: withholding it does
  // not withhold anything, and hiding the row shows a blank box over a filed record.
  const recovered = designWorkshopOptions(dwList(page), {
    group: true,
    offPage: { mode: "recover", row: stored }
  });
  expect(recovered.options.map((option) => option.value)).toEqual(["gone", "here"]);
  expect(recovered.options[0].group).toBe(GROUP_ON_THIS_RECORD);
  expect(recovered.recovered).toBe(true);
  // It is NOT one of the corpus counts. Folding it in produces the off-by-one a reader checks their
  // own counting against.
  expect(recovered.drawn).toBe(1);

  // Already on the page: merged nowhere, counted nowhere, and above all not drawn twice.
  const alreadyThere = designWorkshopOptions(dwList(page), {
    group: true,
    offPage: { mode: "recover", row: page[0] }
  });
  expect(alreadyThere.options.map((option) => option.value)).toEqual(["here"]);
  expect(alreadyThere.recovered).toBe(false);

  // THE MECHANICAL HALF OF §2.9, WHICH IS THE HALF THAT SURPRISES PEOPLE. Options arrive over the
  // network, so for the first second of every mount the list is empty and EVERY value is unmatched.
  // `row: null` is how "the by-id read has not answered" is spelled, and it means "not yet", never
  // "not there" — and the recovered row survives a list that is still loading or has failed, which
  // is the whole reason a designer does not watch their own workshop blink out of the box.
  const midFlight = designWorkshopOptions(
    { kind: "loading" },
    { group: true, offPage: { mode: "recover", row: stored } }
  );
  expect(midFlight.options.map((option) => option.value)).toEqual(["gone"]);
  expect(midFlight.drawn).toBe(0);
  // One class present, so still no heading — the all-or-nothing rule does not except this row.
  expect(midFlight.options[0].group).toBeUndefined();

  const notYet = designWorkshopOptions(
    { kind: "failed" },
    { group: true, offPage: { mode: "recover", row: null } }
  );
  expect(notYet.options).toEqual([]);

  // §2.6 and its one deliberate exception. A soft-deleted workshop is never OFFERED — a picker that
  // offered one would file live fieldwork into the bin — but the list is narrower than the door, so
  // a record can legitimately sit in one, and reporting that is not offering it.
  const withTrash = designWorkshopOptions(
    dwList([...page, dw({ id: "binned", title: "Deleted workshop", deletedAt: "2026-08-01" })]),
    { group: true, offPage: { mode: "recover", row: dw({ id: "filed-in-trash", title: "Deleted, and this record is in it", deletedAt: "2026-08-01" }) } }
  );
  expect(withTrash.options.some((option) => option.value === "binned"), "never offered").toBe(false);
  expect(withTrash.options[0].value, "…and still reported").toBe("filed-in-trash");
  expect(withTrash.options[0].group).toBe(GROUP_ON_THIS_RECORD);
  expect(withTrash.drawn, "the binned row is not counted either").toBe(1);

  // The other table takes the same required parameter and answers it the same way.
  const fieldRecovered = fieldWorkshopOptions(
    fwList([fw({ id: "live", title: "Sanganer dyeing", startDate: "2026-08-02" })]),
    { group: true, offPage: { mode: "recover", row: fw({ id: "off", title: "Somebody else's workshop" }) }, now: AUG_2026 }
  );
  expect(fieldRecovered.options.map((option) => option.value)).toEqual(["off", "live"]);
  expect(groupRows(fieldRecovered.options).map((bucket) => bucket.group)).toEqual([
    GROUP_ON_THIS_RECORD,
    GROUP_OPEN
  ]);
});

test("what the server cut is stated in selectFilter's words, and only there", () => {
  const twoOf196 = designWorkshopOptions(dwList([dw({ id: "a", title: "Ajrakh" }), dw({ id: "b", title: "Bandhani" })], 196), {
    group: true,
    offPage: REFUSE
  });
  expect(twoOf196.cut).toBe(194);
  expect(twoOf196.truncated).toBe(true);
  expect(workshopCutSentence(twoOf196, { searchable: true })).toBe(
    `Showing the first 2 of 196. ${CAP_HINT_WITH_SEARCH}`
  );
  // With the box off the default clause would tell a reader to type into a control that is not on
  // screen — the defect `CAP_HINT_WITHOUT_SEARCH` exists to close.
  expect(workshopCutSentence(twoOf196, { searchable: false })).toBe(
    `Showing the first 2 of 196. ${CAP_HINT_WITHOUT_SEARCH}`
  );

  // A route that cannot count says "there were more" and nothing else. One does — `/workshops/
  // requestable` returns a bare array — and an admitted unknown beats a total that is itself a cap.
  const flagOnly = designWorkshopOptions(dwList([dw({ id: "a", title: "Ajrakh" })], null, true), {
    group: true,
    offPage: REFUSE
  });
  expect(flagOnly.cut).toBe(0);
  expect(workshopCutSentence(flagOnly, { searchable: true })).toBe(
    `Showing the first 1. There are more than are drawn, and the server did not say how many. ${CAP_HINT_WITH_SEARCH}`
  );
  expect(workshopCutSentence(flagOnly, { searchable: true, term: "bagru" })).toBe(
    `Showing the first 1. More match “bagru” than are drawn, and the server did not say how many. ${CAP_HINT_WITH_SEARCH}`
  );

  // The recovered row was never on the page and is not one of the 196, so it is reported as what it
  // is: a selection dragged forward from wherever it really sits.
  const withStored = designWorkshopOptions(dwList([dw({ id: "a", title: "Ajrakh" })], 196), {
    group: true,
    offPage: { mode: "recover", row: dw({ id: "off", title: "Off the page" }) }
  });
  expect(workshopCutSentence(withStored, { searchable: true })).toBe(
    `Showing the first 1 of 196, plus 1 already selected. ${CAP_HINT_WITH_SEARCH}`
  );

  // A whole list says nothing, and so does a list that has not arrived: "Showing the first 0"
  // underneath an empty panel is a second sentence contradicting the first.
  const whole = designWorkshopOptions(dwList([dw({ id: "a", title: "Ajrakh" })]), { group: true, offPage: REFUSE });
  expect(workshopCutSentence(whole, { searchable: true })).toBe("");
  const failed = designWorkshopOptions({ kind: "failed" }, { group: true, offPage: REFUSE });
  expect(workshopCutSentence(failed, { searchable: true })).toBe("");
});

test("the four ways a workshop picker can be empty are four sentences, not one", () => {
  const rows = dwList([dw({ id: "a", title: "Ajrakh" })]);
  const none = dwList([]);

  // Loading says nothing under the control — a sentence that appears and vanishes inside a second
  // is noise — and the panel covers the wait in the slot where it belongs, with the ONE word this
  // app uses for "an answer is outstanding".
  expect(workshopListNotice({ kind: "loading" }, voice())).toBe("");
  expect(workshopEmptyLabel({ kind: "loading" }, voice())).toBe(SEARCHING_LABEL);

  // THE BUG THIS CLOSES: `listDesignWorkshops(...).catch(() => null)` then `page?.items ?? []` turns
  // a timeout into an empty array, which draws "You are on no design workshop yet. An administrator
  // can add you to one" — a confident claim about a grant table made from a read that never arrived.
  expect(workshopListNotice({ kind: "failed" }, voice())).toBe(
    "The design workshops list could not be loaded, so this is not showing what exists. Nothing you have entered is at risk — this record can be saved without it."
  );
  expect(workshopEmptyLabel({ kind: "failed" }, voice())).toBe(workshopListNotice({ kind: "failed" }, voice()));

  // Offline is a different fact with a different next move — and this list is one of the two that
  // R6 forbids caching, so the shared §3.5 clause promising a copy on the device is not printed.
  const offline = workshopListNotice({ kind: "failed" }, voice({ online: false }));
  expect(offline).toBe(
    "This device has not received the design workshops list yet, so there is nothing to pick here. That is not a claim that there are none. Connect and it will load; this list is never kept on the device, because a stored copy of who may file where reads a revoked grant as a grant."
  );
  expect(offline, "a promise R6 forbids").not.toContain("kept on the device from then on");

  // Genuinely empty, and SCOPED versus UNSCOPED are deliberately two sentences: one is a statement
  // about a grant set whose next move is an administrator, the other about the repository whose
  // next move is to create one. Collapsing them sends a designer to an admin for a day, or has them
  // duplicate a workshop that already exists.
  expect(workshopListNotice(none, voice())).toBe(
    "No design workshops are open to this account. An administrator can give you access to one."
  );
  expect(workshopListNotice(fwList([]), voice({ table: "field", scoped: false }))).toBe(
    "No workshops have been recorded yet."
  );
  expect(workshopListNotice(fwList([]), voice({ table: "field", scoped: true }))).toBe(
    "No workshops are open to this account. An administrator can give you access to one."
  );

  // A list with rows has nothing to explain.
  expect(workshopListNotice(rows, voice())).toBe("");

  // R2 and R3 together: nothing to pick means the control is disabled AND the field stops being
  // required — a required closed list with no members refuses the submit before the offline outbox
  // is ever reached, and the interview dies with the tab.
  expect(workshopListStandsDown(designWorkshopOptions(none, { group: true, offPage: REFUSE }))).toBe(true);
  expect(workshopListStandsDown(designWorkshopOptions(rows, { group: true, offPage: REFUSE }))).toBe(false);
  // …but a failed read that still recovered the record's own workshop is NOT an empty control, and
  // standing it down would leave a designer looking at a correct value they cannot change.
  expect(
    workshopListStandsDown(
      designWorkshopOptions({ kind: "failed" }, { group: true, offPage: { mode: "recover", row: dw({ id: "off", title: "On this record" }) } })
    )
  ).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 12. The `Workshop` call sites — the two shared pickers, and the one page picker
 *
 * Section 11 tests the BUILDER. This section tests the three controls migrated onto it, because
 * every defect below is a property of a call site and not of a pure function: which page size went
 * into the request, where the box points, whether a failed read is spelled as an empty list, and
 * whether "everything" still has exactly one spelling on the filter.
 *
 * `components/forms/WorkshopSelect.tsx` is mounted by six screens — the four record forms, the
 * crafts page and the questionnaire — and `components/WorkshopScopeSelect.tsx` by five, including
 * `/search` and `/map`. Neither mount changes; both controls are shared, so what is asserted here is
 * asserted for eleven screens at once. That is also why these are source reads: the judgements live
 * inside React components and this repository has no React renderer in its devDependencies. The two
 * that CAN be made by calling a function are, and they are the two that matter most — the reading
 * order of the sentinel row, and the wording of the filter's failure sentence.
 *
 * NOTHING BELOW MATCHES A NEWLINE, for the reason section 11 gives: `read()` normalises line endings
 * and every marker here is a single line, so a CRLF checkout and CI's LF one compare the same text.
 * ──────────────────────────────────────────────────────────────────────────── */

const WORKSHOP_SELECT = ["components", "forms", "WorkshopSelect.tsx"];
const SCOPE_SELECT = ["components", "WorkshopScopeSelect.tsx"];

/** The sentinel row `WorkshopScopeSelect` appends, and the heading that keeps it where it belongs. */
const UNASSIGNED_GROUP = "Records with no workshop";
const UNASSIGNED_ROW: SelectOption = {
  value: "none",
  label: "Not linked to a workshop",
  group: UNASSIGNED_GROUP
};

test("the record form's workshop box searches the server, at exactly the size it draws", () => {
  const source = read(...WORKSHOP_SELECT);
  // Comment-stripped, because the note beside the request explains at length why the page size is
  // NOT `LIST_PAGE_CEILING` — and the assertion below bans exactly that identifier, so a plain read
  // would fail on the prose that proves the change was deliberate.
  const request = withoutComments(
    between(source, "const timer = window.setTimeout(() => {", "SEARCH_DEBOUNCE_MS : 0)")
  );

  // THE DEFECT, IN ONE ASSERTION. `/workshops` clamps `pageSize` to 100, the table holds 196 rows,
  // and the ComboBox forces a filter box on — one that filtered the array it had been handed. So a
  // researcher typing the title of a workshop sitting at row 140 was told "No matches" about a
  // record that exists, and what anybody does next is save against the wrong workshop or against
  // none at all.
  expect(request, "the panel's term never reached the request").toContain("search: trimmed || undefined");
  // AND-ed with the scope on the server, not OR-ed, so typing cannot reopen a workshop this account
  // may not file against — `list_workshops` appends the narrowing to the same `AND` list.
  expect(request).toContain('accessibleOnly: "true"');
  // One number for the fetch and the render. 100 into a control that draws 80 leaves a band of
  // twenty rows in which the page and the panel each believe the other is saying something.
  expect(request).toContain("pageSize: WORKSHOP_OPTION_PAGE_SIZE");
  expect(request, "the picker's own request is back on the 100-row ceiling").not.toContain("LIST_PAGE_CEILING");
  expect(withoutComments(source), "a bare literal page size").not.toContain("pageSize: 100");

  // The box is wired to the panel, and the debounce is skipped when it is CLEARED: an empty box is
  // the unnarrowed list, the one request that cannot be superseded by the next letter.
  expect(withoutComments(source)).toContain("serverQuery={search}");
  expect(source).toContain("}, trimmed ? SEARCH_DEBOUNCE_MS : 0);");
});

test("the workshop picker's `none` row has exactly one owner, and it is the primitive", () => {
  const source = withoutComments(read(...WORKSHOP_SELECT));

  // §2.7: the string is this file's no longer, and neither is the row. Two layers entitled to draw
  // it is two options sharing the React key "", a list offering one answer twice, and a control that
  // cannot say which of the two is selected.
  expect(source).toContain("noneLabel={NO_FIELD_WORKSHOP}");
  expect(source, "the hand-built row is still here").not.toContain('{ value: "", label:');
  expect(source, "the local copy of the string is still here").not.toContain("const NO_WORKSHOP_LABEL");

  // The label a designer reads is unchanged — this is a move, not a rewording. The other picker on
  // the same four forms could not draw such a row AT ALL, which is what made a record filed under
  // the wrong design workshop uncorrectable on the web; one owner is what fixes both at once.
  expect(read("lib", "workshopOptions.ts")).toContain('export const NO_FIELD_WORKSHOP = "Not linked to a workshop"');
});

test("a failed workshop read is not an empty workshop list, on either shared control", () => {
  const form = withoutComments(read(...WORKSHOP_SELECT));
  const filter = withoutComments(read(...SCOPE_SELECT));

  // Both files spelled a failure `setWorkshops([])`. On the form that drew "No workshops are open to
  // this account yet" over a request that never arrived — a claim about a grant table, sending a
  // researcher to an administrator for access they already hold. On the filter it silently widened
  // the scope from last week's workshop to the whole repository, on five screens.
  expect(form).toContain('setList({ kind: "failed" })');
  expect(filter).toContain('setList({ kind: "failed" })');
  expect(form).not.toContain("setWorkshops([])");
  expect(filter).not.toContain("setWorkshops([])");

  // The four states are told apart by the shared decider, and the old single sentence is gone.
  expect(form).toContain("workshopListNotice(list, voice)");
  expect(form).not.toContain("No workshops are open to this account yet. A record can be saved without one");
  // …and the panel's own line stops being the literal "No options" or a claim the state cannot
  // support: mid-flight it is `SEARCHING_LABEL`, after a failure it is the failure.
  expect(form).toContain("emptyLabel={workshopEmptyLabel(list, voice)}");
});

test("the scope sentence is never printed over an answer to a search term", () => {
  const source = withoutComments(read(...WORKSHOP_SELECT));

  // "No workshops are open to this account" is a claim about a SCOPE. Over the answer to "zzz" it is
  // false, and false in the direction that sends somebody to an administrator about access they
  // already have. The panel says the true thing in that state — `serverNoMatchSentence`, which is a
  // claim about the whole list because the term went to the server.
  expect(source).toContain('const listNotice = list.kind === "ok" && searchApplied ? "" : workshopListNotice(list, voice);');

  // R2 stands an unanswerable field down — and NOT while the box holds a term, because the box is
  // inside the panel: disabling the trigger there would make the term unclearable and lock a reader
  // out of the control with their own keystroke.
  expect(source).toContain("const standingDown = !search.pending && !search.value.trim() && workshopListStandsDown(options);");
  expect(source).toContain("disabled={standingDown}");
});

test("the record's own workshop survives the search box, not merely the page cut", () => {
  const source = withoutComments(read(...WORKSHOP_SELECT));

  // §2.9, routed through the required parameter instead of being re-implemented: the stored row is
  // merged only when the answer does not already hold it, under its own heading, so the scope
  // sentence stays true of everything under "Open".
  expect(source).toContain("useRecordOffPage");
  expect(source).toContain("offPage: { mode: \"recover\", row: storedWorkshop }");

  // AND THE HALF THE SERVER BOX ADDED. The by-id read alone was enough while the options were one
  // fixed page: a workshop already on page one is never fetched by id, correctly. The moment a term
  // narrowed that row out of the answer there was nothing left to merge back, the trigger fell back
  // to the placeholder over a record that HAS a workshop — and the panel does not clear the caller's
  // term on close, so that state survived being dismissed. A blank workshop box is repaired by
  // picking a different workshop, which re-files the record against the wrong fortnight.
  expect(source).toContain("allWorkshops.find((workshop) => workshop.id === workshopId) ?? null");
});

test("the scope filter states its cut, at the size it draws, for the first time", () => {
  const source = read(...SCOPE_SELECT);

  // 196 workshops, 100 fetched, 80 drawn, nothing said, on five screens including /search and /map.
  // R4 outright, and the sharpest single instance of it in the app.
  expect(source).toContain("<CappedListNotice");
  expect(source).toContain('setCut(listCut(result, "workshops"))');
  expect(source).toContain('listResource<Workshop>("/workshops", { pageSize: WORKSHOP_OPTION_PAGE_SIZE })');
  expect(withoutComments(source)).not.toContain("const WORKSHOP_PAGE_SIZE = 100");
});

test("empty still means everything BY ABSENCE, and there is still only one way to spell it", () => {
  const code = withoutComments(read(...SCOPE_SELECT));

  // R1's home. `queryValue` returns `undefined` and not "": `buildQuery` drops the key entirely, and
  // the server reads an ABSENT `workshopIds` as "do not filter".
  expect(code).toContain('workshopIds.length ? workshopIds.join(",") : undefined');

  // THE BUTTON THAT WOULD HAVE BROKEN IT. `MultiSelectDropdown` builds "Select all N" / "Clear all N"
  // unconditionally; wired to a filter it produces the exact state R1 forbids — every row ticked and
  // no row ticked, both meaning "everything", with no way left to tell a default from a deliberate
  // choice. Over a truncated page it is not even true: "all" is 80 of 196, so a ticked-everything
  // scope EXCLUDES the 116 the request never fetched.
  expect(code).toContain("bulk={false}");
  expect(code, "a second spelling of everything").not.toContain("Select all");

  // Everything is said by the one control that says it: a button that writes `[]`.
  expect(code).toContain("onClick={() => setWorkshopIds([])}");
  expect(code, "a selection assembled out of every id there is").not.toContain("workshops.map");
});

test("the sentinel row is drawn LAST, and its heading is the only thing keeping it there", () => {
  const mixed = fieldWorkshopOptions(
    fwList([
      fw({ id: "open", title: "Bagru winter visit", place: "Bagru", startDate: "2026-08-01", endDate: "2026-12-31" }),
      fw({ id: "over", title: "Kutch spring visit", place: "Bhuj", startDate: "2026-02-01", endDate: "2026-02-04" })
    ]),
    { group: true, offPage: REFUSE, now: AUG_2026 }
  ).options;

  // Two classes, so the headings render, and the sentinel sits under its own after both.
  expect(groupRows([...mixed, UNASSIGNED_ROW]).map((bucket) => bucket.group)).toEqual([
    GROUP_OPEN,
    GROUP_ENDED,
    UNASSIGNED_GROUP
  ]);

  // THE TRAP, PINNED, because it is invisible until the list happens to hold two classes:
  // `groupRows` draws UNGROUPED rows FIRST — deliberately, so a caller's ordering governs — so the
  // same row without a heading is lifted to the very top of the panel, above "Open", where it reads
  // as the first and most obvious workshop to pick. That is §2.4's all-or-nothing rule stated as a
  // failure rather than as a rule.
  const bare = groupRows([...mixed, { value: "none", label: "Not linked to a workshop" }]);
  expect(bare[0].group).toBeNull();
  expect(bare[0].rows[0].option.value).toBe("none");

  // And with one class present nothing is grouped, so the sentinel's heading is the only one drawn —
  // eighty plain rows, then a heading, then the row that is not a workshop. Still last.
  const openOnly = fieldWorkshopOptions(
    fwList([
      fw({ id: "a", title: "Ajrakh visit", startDate: "2026-08-01" }),
      fw({ id: "b", title: "Bandhani visit", startDate: "2026-07-01" })
    ]),
    { group: true, offPage: REFUSE, now: AUG_2026 }
  ).options;
  expect(openOnly.every((option) => option.group === undefined)).toBe(true);
  const buckets = groupRows([...openOnly, UNASSIGNED_ROW]);
  expect(buckets.map((bucket) => bucket.group)).toEqual([null, UNASSIGNED_GROUP]);
  expect(buckets[1].rows[0].option.value).toBe("none");
});

test("the filter's failure sentence keeps §3.5's opening and drops the clause about saving a record", () => {
  const source = read(...SCOPE_SELECT);
  const shared = workshopListNotice({ kind: "failed" }, voice({ table: "field", scoped: false, online: true }));
  const sharedOffline = workshopListNotice({ kind: "failed" }, voice({ table: "field", scoped: false, online: false }));

  // The first sentence is the shared one, byte for byte, so a researcher who has met the record
  // form's picker recognises this one.
  const opening = shared.slice(0, shared.indexOf(". ") + 1);
  expect(opening).toBe("The workshops list could not be loaded, so this is not showing what exists.");
  expect(source).toContain(opening);
  const keptOffline = "That is not a claim that there are none.";
  expect(sharedOffline).toContain(keptOffline);
  expect(source).toContain(keptOffline);

  // The last clause is not, and could not be. Both shared sentences end on saving a record against a
  // workshop — "this record can be saved without it", "a stored copy of who may file where reads a
  // revoked grant as a grant" — and nothing is saved from a filter. What a reader needs here is what
  // the screen is therefore showing, which is everything, because "no workshop chosen" is spelled as
  // an absence and an absence is what a failed load leaves behind.
  expect(shared).toContain("this record can be saved without it");
  expect(source, "a record-form clause on a filter").not.toContain("this record can be saved without it");
  expect(source).toContain("with no workshop chosen, this screen is showing every record.");

  // The genuinely-empty arm stays the shared one, and `scoped: false` is what picks the right half of
  // it: this request carries no `accessibleOnly`, so an empty answer means the repository is empty
  // and never that nothing is open to this account.
  expect(withoutComments(source)).toContain('workshopListNotice(list, { table: "field", scoped: false, online })');
  expect(workshopListNotice(fwList([]), voice({ table: "field", scoped: false }))).toBe(
    "No workshops have been recorded yet."
  );
});

test("the media upload's workshop rows come from the shared builder, not a seventh label shape", () => {
  const source = read("app", "(protected)", "media", "page.tsx");
  const branch = between(source, 'case "workshop": {', 'case "craft": {');

  // It shipped `title` alone, sorted by `createdAt` — so the same workshop read one way here, another
  // on the record forms and a third on the funnel, and the creation sort put a workshop entered last
  // week from a backlog import above the one that actually ran yesterday.
  expect(branch).toContain("fieldWorkshopOptions(");
  expect(branch).not.toContain("sortRecent(page.items)");
  expect(branch).not.toContain('x.title?.trim() || "Untitled workshop"');
  // One number for the fetch and the render on a workshop picker; the other seven branches are other
  // tables and keep the ceiling they had.
  expect(branch).toContain("pageSize: WORKSHOP_OPTION_PAGE_SIZE");
  expect(branch).toContain('listCut(page, "workshops")');
});

/* ────────────────────────────────────────────────────────────────────────────
 * 12. W4 — the `DesignWorkshop` callers, made to agree
 *
 * DROPDOWN_DESIGN.md §1.1A lists eleven controls over `GET /design-workshops`
 * and §1.2 tabulates the four columns they disagree in. These are the six that
 * are W4's to move, asserted where the disagreement actually lived: in the call
 * sites. The builders themselves are already covered by section 11's pure calls,
 * so nothing here re-tests a sentence — what it tests is that each site reaches
 * for the shared one instead of writing its own.
 *
 * SOURCE READS, AND THEY ARE SAFE ON A CRLF CHECKOUT because `read` normalises
 * line endings and no marker below spans a line break.
 * ──────────────────────────────────────────────────────────────────────────── */

/** The five sites that hold the read themselves. `/design-review` is the sixth and splits its own
 *  failure two ways already, so it is asserted apart. */
const DW_READERS = [
  ["the record forms' picker", ["components", "forms", "DesignWorkshopSelect.tsx"]],
  ["the viewers panel", ["components", "settings", "DesignWorkshopViewersPanel.tsx"]],
  ["the inspectors panel", ["components", "settings", "DesignWorkshopInspectorsPanel.tsx"]],
  ["the questionnaires list", ["app", "(protected)", "questionnaires", "page.tsx"]],
  ["the questionnaire detail", ["app", "(protected)", "questionnaires", "[id]", "page.tsx"]]
] as const;

/** Every W4 site that draws a design-workshop picker, the two prop-fed dialogs included. */
const DW_CALLERS = [
  ...DW_READERS,
  ["design review", ["app", "(protected)", "design-review", "page.tsx"]],
  ["the upload dialog", ["components", "questionnaires", "UploadDialog.tsx"]],
  ["the reuse dialog", ["components", "questionnaires", "ReuseDialog.tsx"]]
] as const;

test("a failed design-workshop read stops rendering as an account with no design workshops", () => {
  for (const [name, path] of DW_READERS) {
    const source = withoutComments(read(...path));
    /*
      THE DEFECT, AT ITS FIVE SITES. Every one of them held `DwSummary[] | null` or `DwSummary[]`
      behind a `.catch` that wrote an empty array, so a timeout drew exactly like a grant table with
      nothing in it — and then said so: "You are on no design workshop yet. An administrator can add
      you to one", to a designer who already had access and no signal.
    */
    expect(source, `${name} must be able to say the read failed`).toContain('{ kind: "failed" }');
    expect(source, `${name} must record whether the device was reachable when it did`).toContain(
      "deviceLooksOffline()"
    );
    expect(source, `${name} must let the shared module pick the sentence`).toMatch(
      /workshop(ListNotice|EmptyLabel)\(/
    );
    // The two shapes that WERE the bug. Neither may come back under any name.
    expect(source, `${name} must not turn a failure into an empty array`).not.toContain(
      "setSummaries([])"
    );
    expect(source, `${name} must not seed the list with an answer nobody gave`).not.toContain(
      "useState<DwSummary[]>([])"
    );
  }

  // `/design-review` already told `null` from `[]`, and it splits the failure further than the
  // shared helper can — `isUnreachable` is the outbox's own question and `navigator.onLine` is only
  // a stand-in for it — so it feeds `online` from the verdict it already has.
  const review = withoutComments(read("app", "(protected)", "design-review", "page.tsx"));
  expect(review).toContain('scoped: true, online: !listFailure?.unreachable');
  expect(review).toContain('{ kind: "failed" }');
});

test("every design-workshop read asks for exactly as many rows as a panel can draw", () => {
  for (const [name, path] of DW_READERS) {
    const source = withoutComments(read(...path));
    /*
      196 workshops, 100 fetched, 80 drawn, and between 81 and 100 nothing said at all while the
      panel silently dropped rows. One number for the fetch and the render is the only arrangement
      in which two truncation sentences with two different totals cannot both be true at once.
    */
    expect(source, `${name} must name one page size`).toContain("WORKSHOP_OPTION_PAGE_SIZE");
    expect(source, `${name} still asks for a round hundred`).not.toContain("pageSize: 100");
  }
  // The number itself, so the alias cannot quietly become something else.
  expect(RENDER_CAP).toBe(80);
});

test("the un-file row has one owner and one word at every field that files a record", () => {
  const filing = [
    ["the record forms' picker", ["components", "forms", "DesignWorkshopSelect.tsx"]],
    ["the questionnaires list", ["app", "(protected)", "questionnaires", "page.tsx"]],
    ["the questionnaire detail", ["app", "(protected)", "questionnaires", "[id]", "page.tsx"]],
    ["the upload dialog", ["components", "questionnaires", "UploadDialog.tsx"]]
  ] as const;
  for (const [name, path] of filing) {
    const source = withoutComments(read(...path));
    expect(source, `${name} must pass the shared constant`).toContain("noneLabel={NO_DESIGN_WORKSHOP}");
    /*
      TWO LAYERS MUST NOT BOTH BUILD IT. A hand-built `{ value: "", label: … }` beside `noneLabel`
      gives two options sharing the React key "" — a duplicate-key warning, a list offering one
      answer twice, and a trigger that cannot say which of the two is selected. The primitive stands
      its own row down when the caller already built one, so the half-done migration renders
      correctly rather than oddly, which is exactly why this has to be asserted rather than seen.
    */
    expect(source, `${name} still hand-builds a "" row`).not.toContain('{ value: "", label:');
  }
  // The copy dialog's row means something different and keeps its own constant: the answer there can
  // genuinely be deferred and the copy is still made, which is a fact about the operation and not
  // about the field. Four constants, four meanings — never nine strings again.
  const reuse = withoutComments(read("components", "questionnaires", "ReuseDialog.tsx"));
  expect(reuse).toContain("noneLabel={ATTACH_LATER}");
  expect(reuse).not.toContain('{ value: "", label:');
});

test("off-page recovery is decided at every site, and both answers are in use", () => {
  const decided: Record<string, string> = {};
  for (const [name, path] of DW_CALLERS) {
    const source = withoutComments(read(...path));
    const match = source.match(/offPage: \{ mode: "(recover|refuse)"/);
    expect(match, `${name} must pass offPage — the parameter has no default for a reason`).toBeTruthy();
    decided[name] = match![1];
  }
  /*
    THE TWO ANSWERS DIFFER ON A FACT ONLY THE CALLER HOLDS: whether the control describes a read that
    is already true, or authorises a write that is not yet. A record's stored workshop is the first —
    withholding the row does not withhold anything, it turns a read-only fact into a wrong write,
    because a blank box over a filed record invites somebody to file it somewhere else. A create
    form's value can only ever be a row it drew, and `/design-review`'s id is a destination somebody
    pasted, which may well belong to a workshop this account cannot open at all.
  */
  expect(decided["the record forms' picker"]).toBe("recover");
  expect(decided["the questionnaire detail"]).toBe("recover");
  expect(decided["the inspectors panel"]).toBe("recover");
  expect(decided["the questionnaires list"]).toBe("refuse");
  expect(decided["design review"]).toBe("refuse");
  expect(decided["the upload dialog"]).toBe("refuse");

  // And the recovering sites reach for the ONE hook rather than a fourth hand-rolled merge. The
  // inspectors panel had one of the three copies — `options.push({ value: workshopId, … })` after the
  // loop — and it is gone with the label builder it depended on.
  expect(withoutComments(read("components", "forms", "DesignWorkshopSelect.tsx"))).toContain(
    'useRecordOffPage<DwSummary>("/design-workshops"'
  );
  expect(withoutComments(read("app", "(protected)", "questionnaires", "[id]", "page.tsx"))).toContain(
    'useRecordOffPage<DwSummary>('
  );
  expect(
    withoutComments(read("components", "settings", "DesignWorkshopInspectorsPanel.tsx")),
    "the hand-rolled pin must not survive beside the prop that replaced it"
  ).not.toContain("options.push({ value: workshopId,");
});

test("not one design-workshop caller builds its own label any more", () => {
  /*
    SIX LABEL SHAPES FOR ONE QUESTION, and three of them were byte-identical copies of one function
    that each carried a comment saying it was deliberately not shared. `/design-review`'s named the
    trigger for lifting it — "if a fourth caller wants it" — and there were seven.
  */
  for (const [name, path] of DW_CALLERS) {
    const source = withoutComments(read(...path));
    expect(source, `${name} still has a private label builder`).not.toContain(
      "function designWorkshopLabel"
    );
    expect(source, `${name} still joins a title to a date by hand`).not.toContain("` · ${when}`");
  }
  expect(
    withoutComments(read("app", "(protected)", "design-review", "page.tsx")),
    "the helper whose own comment named the trigger for moving it"
  ).not.toContain("function workshopLabel");
  // And the bare title, which is what the questionnaires family drew: two workshops in one craft as
  // two identical rows, which is a choice a reader cannot make.
  for (const path of [
    ["app", "(protected)", "questionnaires", "page.tsx"],
    ["app", "(protected)", "questionnaires", "[id]", "page.tsx"]
  ] as const) {
    expect(withoutComments(read(...path))).not.toContain("label: workshop.title }");
  }
});

test("the three server-searched pickers drive the repository and never re-filter its answer", () => {
  const searched = [
    ["the record forms' picker", ["components", "forms", "DesignWorkshopSelect.tsx"]],
    ["design review", ["app", "(protected)", "design-review", "page.tsx"]],
    ["the inspectors panel", ["components", "settings", "DesignWorkshopInspectorsPanel.tsx"]]
  ] as const;
  for (const [name, path] of searched) {
    const source = read(...path);
    const code = withoutComments(source);
    expect(code, `${name} must hand the term to the panel`).toContain("serverQuery={{");
    expect(code, `${name} must send it to the repository`).toContain("search: ");
    /*
      `truncated` IS DELIBERATELY ABSENT FROM ALL THREE. `GET /design-workshops` reports a real
      `total`, so the field hint prints "Showing the first 80 of 350"; setting the flag as well would
      draw the panel's vaguer "there are more and the server did not say how many" under the same
      list. Two sentences about one cut, in two wordings, is how a reader learns that neither is
      worth reading. The flag arm belongs to a route that cannot count — `/workshops/requestable`.
    */
    expect(code, `${name} must not also raise the unknown-total arm`).not.toContain(
      "truncated: true"
    );
  }

  // The two panels that mounted a second `SearchInput` above a switched-off filter box no longer
  // have either half. The box that reaches past the page is now the panel's own, which is what
  // `serverQuery` was added for.
  const review = withoutComments(read("app", "(protected)", "design-review", "page.tsx"));
  expect(review, "a second box over a narrower scope").not.toContain("<SearchInput");
  expect(review, "the panel's own box is the one that reaches the rest").not.toContain("capHint=");
  const inspectors = withoutComments(read("components", "settings", "DesignWorkshopInspectorsPanel.tsx"));
  const workshopField = between(inspectors, 'ariaLabel="Design workshop"', "/>");
  expect(workshopField).toContain("serverQuery={{");
  expect(workshopField, "the workshop picker's own box is no longer switched off").not.toContain(
    "searchable={false}"
  );
});

test("the questionnaires family says which kind of short list it is holding, in one voice", () => {
  /*
    Neither questionnaires page said ANYTHING about this list at either level — not the cap, not the
    empty state, not the failure — while feeding three controls from one read: the create form, the
    upload dialog and the reuse dialog. So one string is chosen where the read happens and handed
    down, which is what stops three controls wording one answer three ways.
  */
  for (const path of [
    ["app", "(protected)", "questionnaires", "page.tsx"],
    ["app", "(protected)", "questionnaires", "[id]", "page.tsx"]
  ] as const) {
    const source = withoutComments(read(...path));
    expect(source).toContain("workshopListNotice(workshopList, workshopVoice) ||");
    expect(source).toContain("cappedListNotice(");
    expect(source).toContain("workshopsNotice={workshopNotice}");
  }

  /*
    AND THE UPLOAD DIALOG STOPS HIDING ITS FIELD. `{!editing && workshops?.length ? … : null}` meant
    that a designer whose read had failed, or who held no workshop yet, did not get the control at
    all — the silent empty picker in its purest form, with not even a greyed box to wonder about.
    R3's remedy is to draw it, disable it, and say which of the states it is in. The `editing` gate
    is a different thing and stays: the re-upload route accepts a title and nothing else, so there
    the field is one whose value the request could not carry.
  */
  const upload = withoutComments(read("components", "questionnaires", "UploadDialog.tsx"));
  expect(upload, "the list's length must not decide whether the field exists").not.toContain(
    "workshops?.length ?"
  );
  expect(upload).toContain("{editing ? null : (");
  expect(upload).toContain("disabled={!workshops?.length}");
});

test("the reuse dialog's annotation moved out of the label it was competing with", () => {
  const dialog = withoutComments(read("components", "questionnaires", "ReuseDialog.tsx"));
  /*
    It was appended to the title: `${workshop.title} — where this one already is`. The label is what
    the collapsed trigger prints and what `filterOptions` ranks a typed title against, so the suffix
    pushed the workshop's own name off a one-line trigger and demoted an exact-title match to a
    mid-word one. The hint is drawn beneath the label AND searched, so it is just as visible and just
    as findable without competing with the name.
  */
  expect(dialog, "the suffix must not be back on the label").not.toContain(
    "— where this one already is`"
  );
  expect(dialog).toContain('`where this one already is · ${option.hint}`');
  // Annotated and NOT removed, which is the older ruling and is unchanged: a second round of one
  // instrument at the same workshop is an ordinary thing to run.
  expect(dialog).toContain("sourceWorkshopId");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 13. The access console's workshop picker — the last client-side box over a page
 * ──────────────────────────────────────────────────────────────────────────── */

/*
  WHY THIS SITE GETS A SECTION OF ITS OWN, when every other design-workshop picker is covered above
  as a family. Because it is the one that could not simply be handed `serverQuery`: it carries
  a SECOND narrowing — a workshop-type filter built in this browser out of a separately capped read
  of a different table — and the two cannot both be the answer. Everything below is that collision,
  resolved, and each assertion names the screen a reader would otherwise have been looking at.
*/

test("the viewers panel's workshop box asks the repository, not the page it had already fetched", () => {
  const code = withoutComments(read("components", "settings", "DesignWorkshopViewersPanel.tsx"));
  const field = between(code, 'ariaLabel="Design workshop"', "/>");

  /*
    IT WAS `searchable` OVER ONE PAGE OF `GET /design-workshops`, which is the defect §2.8 exists to
    close, standing on an access console: an admin typing the title of a workshop that happened to
    sit on page two was told "No matches" about a workshop that exists, and on this screen that
    reads as "there is no such workshop to grant access to".
  */
  expect(field, "the panel's own box must drive a server query").toContain("serverQuery={{");
  expect(field, "and must not also filter the array it was handed").not.toContain("searchable");
  expect(code, "the term must reach the request").toContain("search: term || undefined");
  /*
    `truncated` stays absent here for the reason every other design-workshop picker leaves it out:
    this route reports a real `total`, so the cut is stated once, underneath, with its number. The
    flag would draw the panel's vaguer "there are more and the server did not say how many" under
    the same list, and two sentences about one cut in two wordings teach a reader to read neither.
  */
  expect(field, "the unknown-total arm belongs to a route that cannot count").not.toContain(
    "truncated"
  );
});

test("a search moves the viewers panel's options and never its administration", () => {
  const code = withoutComments(read("components", "settings", "DesignWorkshopViewersPanel.tsx"));

  /*
    THE SELECTED WORKSHOP IS READ FROM WHAT THE PANEL HAS SEEN, NOT FROM THE CURRENT ANSWER. Three
    things read the chosen workshop's ROW rather than its id, and the load-bearing one is
    `creatorId`: it decides which account is held out of the editable set and is a dependency of the
    viewer read. Off a moving window, typing a colleague's surname into the people box would have
    blanked it — turning the creator's row into an ordinary tickable one that the next Save could
    delete, which is the single row this panel is least entitled to touch.
  */
  expect(code, "the selection must survive an answer that no longer holds it").toContain(
    "knownWorkshops.get(workshopId)"
  );
  /*
    AND THE ROW IS RECOVERED RATHER THAN REFUSED, which is a flip from what this site answered before
    its box went to the server, on §2.9's test rather than on a preference: this control describes an
    administration that is ALREADY TRUE — the roster beneath it belongs to that workshop — instead of
    authorising a one-way write. With "refuse" the trigger would go blank over a roster an admin is
    in the middle of editing, which is the read-only fact turning into a wrong write that
    `WorkshopSelect` names.
  */
  expect(code).toContain('offPage: { mode: "recover", row: selectedWorkshop }');

  /*
    THE TYPE FILTER MAY CLEAR THE SELECTION; THE SEARCH MAY NOT. The old guard asked the LIST
    (`!offered.some(id)`), which was the same question while `offered` could only shrink for one
    reason. With two reasons it is not: a keystroke would have torn down the roster the admin was
    editing, several letters into a name, with nothing on screen to say why.
  */
  expect(code, "the clear must not key on list membership any more").not.toContain(
    "!offered.some((summary) => summary.id === workshopId)"
  );
  expect(code).toContain("if (!workshopId || !typeFilter || typeNarrowingSuspended) return;");

  /*
    AND THE TERM DOES NOT OUTLIVE THE PICK, which is this panel's alone to arrange. The primitive
    never writes "" into a caller's term — closing a menu would otherwise re-fetch the unnarrowed
    list and throw away the narrowing just done — so a caller whose term disables a NEIGHBOURING
    control has to clear it, or that control stays disabled by a cause the reader can no longer see.
    The other three server-searched pickers keep their term, because nothing beside them depends on
    it.
  */
  expect(code, "the box that suspends the type filter must empty when it has done its job").toContain(
    'setWorkshopSearch("");'
  );
});

test("the type filter stands down while the workshop box holds a term, and says so", () => {
  const source = read("components", "settings", "DesignWorkshopViewersPanel.tsx");
  const code = withoutComments(source);

  /*
    THE COLLISION, AND WHY THE LOCAL NARROWING IS THE ONE THAT GIVES WAY. A workshop's type is read
    from a map built here out of `GET /workshops?pageSize=200` — one capped page of a DIFFERENT
    table — while the search reaches every design workshop there is. Letting the map narrow the
    server's answer would drop rows the repository matched, on the strength of a local table that
    cannot say whether it has ever heard of them, and hand the picker an empty options array. The
    panel would then draw its own strongest sentence — "No matches for X. This box searches the whole
    list, not only the rows drawn here" — about a search that DID match. That is absence reading as
    non-existence, arriving through the fix for it.
  */
  expect(code, "the suspension has to be a named fact, not an inline condition").toContain(
    'const typeNarrowingSuspended = workshopSearchTerm !== "";'
  );
  expect(code, "it must govern the narrowing itself").toContain(
    "if (!typeFilter || typeNarrowingSuspended) return { offered: all, untyped: 0 };"
  );
  expect(code, "and the control it belongs to").toContain(
    "disabled={!typeKnown || typeNarrowingSuspended}"
  );
  /*
    SAID, NOT PERFORMED. A control that keeps its value and quietly stops narrowing is one an admin
    reads as broken. The same control already takes this shape for its other stand-down — the type
    map could not be read at all — so this is the established arrangement on this screen rather than
    a new one.
  */
  expect(source, "a stand-down with no sentence is the silent empty picker").toContain(
    "The type filter is off while you are searching."
  );

  /*
    AND THE TWO EMPTINESSES CAN NEVER BE ON SCREEN TOGETHER. `filteredAway` is the type filter's own
    sentence; it is unreachable while a term is typed, because the filter is suspended there — which
    is what stops it and the panel's "No matches for …" describing one empty list two ways.
  */
  expect(code).toContain(
    'const filteredAway = workshops.kind === "ok" && workshops.rows.length > 0 && offered.length === 0;'
  );
});

test("the viewers panel's cut sentence counts the rows a reader can actually see", () => {
  const code = withoutComments(read("components", "settings", "DesignWorkshopViewersPanel.tsx"));

  /*
    IT IS ASKED OF THE BUILT OPTIONS AND NOT OF THE FETCH, and that is the non-obvious half. Two
    narrowings reach this picker — the page the repository sent, and the type filter applied here —
    and the tempting arrangement is to count the first and leave the second to its own paragraph.
    The rule that decides it is the one this sentence exists for: the arithmetic has to be checkable
    against the rows in front of the reader. A filter that leaves twelve rows above a line reading
    "Showing the first 80" is a sentence about a list nobody is looking at, which is exactly what
    the old "Showing the 100 most recent design workshops" line was, one direction over.

    Counting off `WorkshopOptionSet.drawn` is also what keeps the recovered row honest: `assemble`
    trims a full page plus a recovered row back to `RENDER_CAP`, and a count taken from the answer
    would then be one ahead of the panel.
  */
  expect(code, "the set, not the answer, is what the reader is looking at").toContain(
    "const workshopCut = workshopCutSentence(workshopSet, {"
  );

  /*
    THE HINT IS TRUE FOR THE FIRST TIME. This line used to be `cappedListNotice`'s no-reach arm,
    because that was the only honest thing to say about a box that filtered one fetched page. The box
    now reaches the rest, so the shared "Keep typing to narrow the list" is what belongs here — and
    it comes from `selectFilter.ts` through `workshopCutSentence`, so this line and the panel's own
    footer cannot describe one cut in two wordings.
  */
  expect(code).toContain("searchable: true");
  expect(code, "the no-reach arm went with the box that justified it").not.toContain(
    "cappedListNotice("
  );

  /*
    AND THE SCOPE SENTENCE IS ASKED OF THE UNNARROWED READ. "No design workshops are open to this
    account. An administrator can give you access to one." printed under a box somebody has just
    typed into is a claim about a grant table produced by a filtered read; the panel says the true
    thing in the server's own stronger words. A FAILED read still speaks through the term, because
    that failure is not about the term at all.
  */
  expect(code).toContain('filteredAway || (workshops.kind === "ok" && workshopSearchTerm)');
});

/*
  ── THE 81st ROW ────────────────────────────────────────────────────────────────────────────────

  Found by review, 2026-08-30, on the claim that `pageSize === RENDER_CAP` makes two truncation
  sentences with two different totals impossible. It very nearly does. The one arrangement that
  escapes it is §2.9's own: the page is exactly `RENDER_CAP` and the record's stored workshop is not
  on it, so the recovered row makes `RENDER_CAP + 1` options for a panel that windows at
  `RENDER_CAP`. That is not an exotic state — it is an admin opening a record filed under a design
  workshop older than the newest eighty — and what it drew was the field hint "Showing the first 80
  of 196, plus 1 already selected" sitting directly above the panel's own footer "Showing the first
  80 of 81", with one real workshop row dropped in silence between the two.
*/
test("a recovered row cannot push the list past the number the panel draws", () => {
  const page = Array.from({ length: RENDER_CAP }, (_, index) =>
    dw({
      id: `w${String(index).padStart(3, "0")}`,
      title: `Workshop ${String(index).padStart(3, "0")}`,
      // Distinct occurrence dates, so the reading order is total and the row that falls off the end
      // is a known one rather than whichever way a tie happened to break.
      startDate: `2026-01-01T00:00:${String(index).padStart(2, "0")}Z`
    })
  );

  // The control as it stands with nothing recovered: a full page, and the panel draws every row of
  // it, so the ONLY sentence is this one.
  const whole = designWorkshopOptions(dwList(page, 196), { group: true, offPage: REFUSE });
  expect(whole.options.length).toBe(RENDER_CAP);
  expect(whole.drawn).toBe(RENDER_CAP);
  expect(workshopCutSentence(whole, { searchable: true })).toBe(
    `Showing the first ${RENDER_CAP} of 196. ${CAP_HINT_WITH_SEARCH}`
  );

  // …and the same page with the record's own off-page workshop merged in.
  const stored = dw({ id: "off-page", title: "Where this record actually is", startDate: "2019-04-04" });
  const recovered = designWorkshopOptions(dwList(page, 196), {
    group: true,
    offPage: { mode: "recover", row: stored }
  });

  // THE COUNT THE PANEL CAN HONOUR. One more than this and `SearchableSelect` windows the array and
  // prints a second sentence about a total ("of 81") that is not the total of anything.
  expect(recovered.options.length, "never more rows than a panel draws").toBe(RENDER_CAP);
  // The recovered row is the one thing that may not be trimmed: it is the answer the field is
  // currently holding, and dropping it puts the trigger back on the placeholder over a filed record.
  expect(recovered.options[0].value).toBe("off-page");
  expect(recovered.options[0].group).toBe(GROUP_ON_THIS_RECORD);
  // The row that goes is the last in READING order — the oldest-occurring — which is exactly the row
  // the render window would have dropped. Nothing moves except which layer says so.
  expect(recovered.options.some((option) => option.value === "w000"), "the oldest of the page").toBe(false);
  expect(recovered.options.some((option) => option.value === "w001")).toBe(true);

  // AND THE ARITHMETIC IS ABOUT WHAT IS ON SCREEN. `drawn` excludes the recovered row and counts the
  // trim; `cut` is `total - drawn`, so the two numbers a reader can check against the rows in front
  // of them both hold.
  expect(recovered.drawn).toBe(RENDER_CAP - 1);
  expect(recovered.cut).toBe(196 - (RENDER_CAP - 1));
  expect(workshopCutSentence(recovered, { searchable: true })).toBe(
    `Showing the first ${RENDER_CAP - 1} of 196, plus 1 already selected. ${CAP_HINT_WITH_SEARCH}`
  );

  // The other table takes the identical trim, because the primitive under it is the identical panel.
  const fieldPage = Array.from({ length: RENDER_CAP }, (_, index) =>
    fw({ id: `f${String(index).padStart(3, "0")}`, title: `Visit ${index}`, startDate: `2026-02-01T00:00:${String(index).padStart(2, "0")}Z` })
  );
  const fieldSet = fieldWorkshopOptions(fwList(fieldPage, 196), {
    group: true,
    offPage: { mode: "recover", row: fw({ id: "f-off", title: "On the record already" }) },
    now: AUG_2026
  });
  expect(fieldSet.options.length).toBe(RENDER_CAP);
  expect(fieldSet.drawn).toBe(RENDER_CAP - 1);
  expect(fieldSet.options[0].value).toBe("f-off");
});
