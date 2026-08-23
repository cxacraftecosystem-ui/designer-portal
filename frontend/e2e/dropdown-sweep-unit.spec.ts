import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CAP_HINT_WITHOUT_SEARCH,
  CAP_HINT_WITH_SEARCH,
  RENDER_CAP,
  capNoticeSentence,
  filterOptions,
  groupRows,
  typeaheadIndex,
  type SelectOption
} from "@/components/ui/selectFilter";

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

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

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
  const uses = source.match(/hint=\{capHint \?\? \(withSearch \? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH\)\}/g);
  expect(uses?.length, "both the single- and the multi-select footer").toBe(2);
  expect(
    source,
    "the old unconditional sentence must not come back"
  ).not.toContain("Keep typing to narrow the list.\n");
});

test("the two panels that overrule a long list name the control that does reach the rest", () => {
  for (const [file, path] of [
    ["viewers panel", ["components", "settings", "DesignWorkshopViewersPanel.tsx"]],
    ["design review", ["app", "(protected)", "design-review", "page.tsx"]]
  ] as const) {
    const source = read(...path);
    expect(source, `${file} turns the panel's own filter box off`).toContain("searchable={false}");
    expect(source, `${file} says where the rest are`).toContain('capHint="Use the search box above');
  }
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

test("the pinned set is a snapshot, taken on open and on every query change", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(
    source,
    "the window must not be recomputed from the live selection"
  ).toContain("useSelectList(options, query, withSearch, pins)");
  expect(source).not.toContain("useSelectList(options, query, withSearch, chosen)");
  // Three moments per component: opening, seeding a filter from a keystroke, and typing.
  expect(source.match(/setPins\(/g)?.length, "single-select ×3, multi-select ×4").toBe(7);
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
