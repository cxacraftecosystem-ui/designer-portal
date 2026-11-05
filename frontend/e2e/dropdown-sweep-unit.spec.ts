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
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

test("a hint is matched as well as shown, and ranks below every label match", () => {
  const countries: SelectOption[] = [
    { value: "US", label: "🇺🇸 +1", hint: "United States" },
    { value: "IN", label: "🇮🇳 +91", hint: "India" },
    { value: "UY", label: "🇺🇾 +598", hint: "Uruguay" },
    { value: "IE", label: "🇮🇪 +353", hint: "Ireland" }
  ];

  expect(filterOptions(countries, "uruguay").map((option) => option.value)).toEqual(["UY"]);
  expect(filterOptions(countries, "india").map((option) => option.value)).toEqual(["IN"]);

  expect(filterOptions(countries, "+91").map((option) => option.value)).toEqual(["IN"]);

  expect(filterOptions(countries, "ir").map((option) => option.value)).toEqual(["IE"]);
});

test("a label match beats a hint match even when the label match is mid-word", () => {
  const options: SelectOption[] = [
    { value: "hint", label: "Zebra", hint: "Cotton" },
    { value: "label", label: "Recotton", hint: "Zebra" }
  ];
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

test("the cap sentence states the window, counts pinned rows apart, and ends in the caller's advice", () => {
  expect(capNoticeSentence({ shown: 80, pinned: 0, total: 246, hint: CAP_HINT_WITH_SEARCH })).toBe(
    "Showing the first 80 of 246. Keep typing to narrow the list."
  );
  expect(capNoticeSentence({ shown: 80, pinned: 1, total: 246, hint: CAP_HINT_WITH_SEARCH })).toBe(
    "Showing the first 80 of 246, plus 1 already selected. Keep typing to narrow the list."
  );
  expect(CAP_HINT_WITHOUT_SEARCH).not.toContain("typing");
  expect(
    capNoticeSentence({ shown: 80, pinned: 0, total: 2000, hint: "Use the search box above." })
  ).toBe("Showing the first 80 of 2000. Use the search box above.");
});

test("the hint is chosen by whether there IS a filter box, and a caller may name its own control", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  const uses = source.match(
    /const capAdvice = capHint \?\? \(withSearch \? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH\);/g
  );
  expect(uses?.length, "both the single- and the multi-select footer").toBe(2);
  expect(
    source,
    "the old unconditional sentence must not come back"
  ).not.toContain("Keep typing to narrow the list.\n");
});

test("the panel that overrules a long list names the control that does reach the rest", () => {
  const source = read("components", "settings", "DesignWorkshopViewersPanel.tsx");
  expect(source, "the people picker turns the panel's own filter box off").toContain(
    "searchable={false}"
  );
  expect(source, "and says where the rest are").toContain('capHint="Search above to find the rest');
});

test("type-ahead skips disabled rows and answers against the list it was handed", () => {
  const rows: SelectOption[] = [
    { value: "a", label: "Anvil", disabled: true },
    { value: "b", label: "Awl" },
    { value: "c", label: "Bamboo comb" }
  ];
  expect(typeaheadIndex(rows, "a")).toBe(1);
  expect(typeaheadIndex(rows, "b")).toBe(2);
  expect(typeaheadIndex(rows, "z")).toBe(-1);
  expect(typeaheadIndex([{ value: "x", label: "Ahmedābād" }], "ahmed")).toBe(0);
});

test("neither component takes a highlight index out of `options`", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(source).not.toContain("options.findIndex((option) => !option.disabled && fold(");
  const uses = source.match(/typeaheadIndex\(rendered, /g);
  expect(uses?.length, "both components read the rendered list for a highlight").toBe(2);
  expect(source).toContain("typeaheadIndex(options, typed)");
});

test("Home and End stand down inside the filter box, and arrows do not", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  const guards = source.match(/if \(!open \|\| textEntry\) return false;/g);
  expect(guards?.length, "Home and End, in both components").toBe(4);
  const wired = source.match(/onKeyDown=\{onFilterKeyDown\}/g);
  expect(wired?.length, "both filter boxes go through the text-entry branch").toBe(2);
  const handlers = source.match(/function onFilterKeyDown\(event: React\.KeyboardEvent<HTMLInputElement>\) \{\n {4}navigate\(event, true\);/g);
  expect(handlers?.length, "…and it is the same `navigate`, told where it is").toBe(2);
  expect(source).not.toContain('case "ArrowDown":\n        if (!open || textEntry)');
});

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
    const window = source.slice(Math.max(0, at - 1200), at + 1200);
    expect(window, `${name}: the district picker must pass searchable`).toMatch(/\n\s*searchable\b/);
  }
});

test("Tab out of the filter box commits only a highlight the reader moved", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(
    source,
    "the unguarded commit — Tab took row 0 of the query and replaced a filled field"
  ).not.toContain("if (safeHighlight >= 0 && rendered[safeHighlight]) onChange(rendered[safeHighlight].value);");
  expect(source).toContain("if (highlightTouched && safeHighlight >= 0 && rendered[safeHighlight]) {");
  expect(source.match(/setHighlightTouched\(true\)/g)?.length, "arrows, Home, End, hover").toBe(6);
  expect(source.match(/setHighlightTouched\(false\)/g)?.length, "open, seed, and every query change").toBe(4);
});

test("the photo-intake destination picker is the themed control, with its two groups intact", () => {
  const source = read("app", "(protected)", "design-workshops", "[id]", "photos", "page.tsx");
  expect(withoutComments(source), "the raw <select> is gone").not.toMatch(/<select[\s>]/);
  expect(source, "…and so is its concession").not.toContain("THE ONE LONG LIST IN THE APP WITH NO FILTER BOX");
  expect(source).toContain("<Dropdown");
  expect(source).toContain("searchable");
  expect(source).toContain('const PROPOSED_GROUP = "Proposed from the capture date"');
  expect(source).toContain('const EVERYWHERE_GROUP = "Every other place a photograph can go"');
  expect(source).toContain("!proposedKeys.has(destination.key)");
});

test("the sketch/prototype row picker is record-backed and now says so", () => {
  const source = read("components", "sketches", "UploadTabHost.tsx");
  expect(withoutComments(source), "the raw <select> is gone").not.toMatch(/<select[\s>]/);
  const picker = source.slice(source.indexOf("function RowPicker({"));
  expect(picker).toContain("<Dropdown");
  expect(picker).toContain("searchable");
  expect(withoutComments(picker)).not.toMatch(/<label[\s>]/);
  expect(picker).toContain("ariaLabel={label}");
});

test("the roster picker's search box owns its listbox and moves a highlight", () => {
  const source = read("components", "designworkshop", "StageReferenceField.tsx");
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
  const bare = source.match(/"bg-purple-50"/g);
  expect(bare, "every bg-purple-50 must be paired with dark:bg-purple-950").toBeNull();
});

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
  expect(composed?.length, "both triggers").toBe(2);
});

test("the pinned set is a snapshot, taken on open, on a query change, and on a new answer", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  const calls = source.match(/pins,\s+serverDriven\s/g);
  expect(calls?.length, "both components, both passing the frozen set").toBe(2);
  expect(source, "the window must not be recomputed from the live selection").not.toContain(
    "withSearch, chosen"
  );
  expect(source).not.toContain("withSearch, new Set(values)");
  expect(source.match(/setPins\(/g)?.length, "single-select ×4, multi-select ×5").toBe(9);
});

test("a server-searched panel re-snapshots its pins on the ANSWER, never on the keystroke", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  const effects = source.match(/}, \[serverDriven, options(, value)?\]\);/g);
  expect(effects?.length, "one effect per component, keyed on the answer").toBe(2);
  expect(
    withoutComments(source),
    "the multi-select's effect must NOT depend on the live selection"
  ).not.toContain("[serverDriven, options, values]");
  expect(source, "…so it reads it through a ref instead").toContain(
    "setPins(new Set(selectionRef.current));"
  );
  expect(source.match(/if \(!serverDriven\) setPins\(/g)?.length, "seedFilter + onQueryChange, ×2").toBe(4);
});

test("grouping preserves each row's index into the rendered array", () => {
  const rendered: SelectOption[] = [
    { value: "", label: "Leave out" },
    { value: "p1", label: "Proposal one", group: "Proposed" },
    { value: "e1", label: "Everything one", group: "Everywhere" },
    { value: "p2", label: "Proposal two", group: "Proposed" }
  ];
  const buckets = groupRows(rendered);
  expect(buckets.map((bucket) => bucket.group)).toEqual([null, "Proposed", "Everywhere"]);
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
  expect(source).toContain("const CHOOSER_PAGE = RENDER_CAP;");
  expect(source, "the literal must not come back").not.toContain("const CHOOSER_PAGE = 100;");
  expect(RENDER_CAP).toBe(80);
  expect(source).toContain("search: titleQuery.trim() || undefined");
});

test("the chooser no longer asserts absence from a list that has not answered", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  const now = between(source, '{workshopId ? (\n          <p className="text-xs leading-5 text-ink-500" aria-live="polite">', "{workshopId ? (\n        <>");
  expect(now, "the claim is now gated on the list having answered").toContain(") : ready ? (");
  expect(now).toContain("It isn&apos;t in\n                your list above; its round is shown below.");
  const unanswered = now.slice(now.indexOf(") : ready ? ("));
  expect(unanswered.slice(unanswered.indexOf(") : (")), "no absence claim before the list answers").not.toContain(
    "your list above"
  );
  expect(now).toContain("Its round is shown\n                below.");
});

test("the header no longer says the pool round is only what a workshop declared finished", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  const header = between(source, 'title="Design review"\n        description=', 'icon={<Globe2');
  expect(header).toContain("Rate sketches and prototypes from other workshops");
  expect(header, "the false frame must not survive in the description").not.toContain(
    'description="Sketches and prototypes that a design workshop has declared finished'
  );
  expect(source).toContain('hint: "The prototypes in this workshop\'s pool round."');
});

test("the truncation sentence names the date that actually does the sorting", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  const notice = between(source, 'id="design-review-truncation"', "</p>");
  expect(notice).not.toContain("newest first");
  expect(notice).toContain("added most recently");
  expect(notice, "and names the date that orders the rows").toContain("sorted by workshop date");
  expect(notice, "no developer vocabulary in the notice").not.toContain("repository");
});

test("the chooser's honest paragraphs are reachable to assistive technology", () => {
  const source = read("app", "(protected)", "design-review", "page.tsx");
  expect(source).toContain('describedBy="design-review-scope design-review-empty design-review-truncation"');
  expect(source).toContain('id="design-review-empty"');
  expect(source).toContain('id="design-review-truncation"');
  const empty = between(source, 'id="design-review-empty"', "</p>");
  expect(empty).toContain('aria-live="polite"');
  expect(source).toContain("Workshops you can open.");
});

test("every new prop is optional and its absence is the old behaviour", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  for (const declaration of [
    "serverQuery?: SelectServerQuery;",
    "noneLabel?: string;",
    "bulk?: boolean;"
  ]) {
    expect(source, `${declaration} must be optional`).toContain(declaration);
  }
  expect(source, "the bulk button stays on unless a caller turns it off").toContain("bulk = true,");
  expect(source).toContain("const serverDriven = serverQuery != null;");
  expect(source, "a default serverQuery would put every caller on the server branch").not.toContain(
    "serverQuery = {"
  );

  const adapters = read("components", "ui", "Dropdown.tsx");
  expect(adapters.match(/serverQuery=\{serverQuery\}/g)?.length, "Dropdown, MultiSelect, ComboBox").toBe(3);
  expect(adapters.match(/noneLabel=\{noneLabel\}/g)?.length, "Dropdown and ComboBox").toBe(2);
  expect(adapters).toContain("bulk={bulk}");
});

test("`serverQuery` forces the box on, and takes the local filter pass off", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(source).toContain("searchable && !serverAnswered ? filterOptions(options, query) : options");
  expect(source).toContain(
    "const withSearch = serverDriven || createAction != null || (searchable ?? options.length >= SEARCH_THRESHOLD);"
  );
  const forced = source.match(
    /const withSearch = serverDriven \|\| \(searchable \?\? options\.length >= SEARCH_THRESHOLD\);/g
  );
  expect(forced?.length, "the multi-select only").toBe(1);
  expect(source.match(/setOwnQuery\(""\);/g)?.length, "one `close` per component").toBe(2);
  expect(source, "the panel must not re-fetch an unnarrowed list on every dismissal").not.toContain(
    "serverQuery.onChange(\"\")"
  );
});

test("the box is named for what it actually reaches", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(source.match(/placeholder=\{serverDriven \? "Type to search" : "Type to filter"\}/g)?.length).toBe(2);
  expect(source.match(/\? `Search \$\{ariaLabel\}`/g)?.length, "both panels").toBe(2);
  expect(source.match(/\? `Filter \$\{ariaLabel\}`/g)?.length, "…and both keep the old name").toBe(2);
  expect(source, "the pending word is announced once, by the live region").toContain(
    "<span aria-hidden className=\"shrink-0 whitespace-nowrap text-xs text-ink-500\">"
  );
  const searchRow = between(source, "function SearchRow({", "function noneOptionFor(");
  expect(withoutComments(searchRow)).not.toMatch(/animate-|motion\.|transition-transform/);
});

test("the empty line is three-way on a server-searched panel and two-way everywhere else", () => {
  const settled = { pending: false };
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

  expect(
    emptyListSentence({ emptyLabel: "No options", term: "bagru", server: false, ...settled })
  ).toBe("No matches");

  expect(
    emptyListSentence({ emptyLabel: "No options", term: "bagru", server: true, ...settled })
  ).toBe(
    "No matches for “bagru” in the whole list."
  );
  expect(serverNoMatchSentence("  bagru  "), "the term is trimmed before it is quoted").toContain(
    "“bagru”"
  );

  expect(
    emptyListSentence({ emptyLabel: "No design workshops.", term: "bagru", server: true, pending: true })
  ).toBe(SEARCHING_LABEL);
  expect(
    emptyListSentence({ emptyLabel: "No design workshops.", term: "", server: true, pending: true })
  ).toBe(SEARCHING_LABEL);
  expect(emptyListSentence({ emptyLabel: "No options", term: "", server: false, pending: true })).toBe(
    "No options"
  );
});

test("the empty line is asked of the CORPUS, so a `noneLabel` row cannot suppress it", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(source.match(/\{windowed\.length === 0 \? \(/g)?.length, "both components").toBe(2);
  expect(source).not.toContain("{rendered.length === 0 ? (");
  expect(source.match(/shown: windowed\.length - pinned,/g)?.length, "both footers").toBe(2);
});

test("the `noneLabel` row is first, ungrouped, exempt from the cap and hidden while searching", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(source).toContain("(noneRow ? [noneRow, ...windowed] : windowed)");
  expect(source).toContain('return { value: "", label: noneLabel };');
  expect(source).toContain("const noneRow = noneOption && !query.trim() ? noneOption : null;");
  expect(source).toContain('(noneOption && value === "" ? noneOption : undefined)');
  expect(source, "…while keeping the muted rung that says nothing is filed").toContain(
    '(!selected || showingNone) && "text-ink-500"'
  );
  expect(source).toContain('if (options.some((option) => option.value === "")) return null;');
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
  expect(buckets.map((bucket) => bucket.group)).toEqual([
    null,
    "Already on this record",
    "Open",
    "Submitted and archived"
  ]);
  expect(buckets[0].rows.map((row) => row.option.value)).toEqual([""]);
  expect(buckets.flatMap((bucket) => bucket.rows.map((row) => row.index))).toEqual([0, 1, 2, 4, 3]);
});

test("`bulk={false}` removes the button, and a truncated answer stops it claiming `all`", () => {
  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(source).toContain("bulk && bulkRows.length > 0 ? (");
  expect(source, "the rows and the switch must not share a name").not.toContain(
    "const bulk = useMemo("
  );
  expect(source).toContain("`Select the ${bulkRows.length} shown`");
  expect(source).toContain("`Clear the ${bulkRows.length} shown`");
  expect(source).toContain("`Select all ${bulkRows.length}`");
  expect(source).toContain("`Select ${bulkRows.length} matching`");
});

test("a server-reported cut is stated even though its total is unknowable, and it wins", () => {
  expect(
    unknownTotalNoticeSentence({ shown: 80, pinned: 0, term: "bagru", hint: CAP_HINT_WITH_SEARCH })
  ).toBe(
    "Showing the first 80. More match “bagru” than are shown. Keep typing to narrow the list."
  );
  expect(
    unknownTotalNoticeSentence({ shown: 80, pinned: 2, term: "", hint: CAP_HINT_WITH_SEARCH })
  ).toBe(
    "Showing the first 80, plus 2 already selected. There are more than are shown. Keep typing to narrow the list."
  );
  expect(unknownTotalNoticeSentence({ shown: 80, pinned: 0, term: "", hint: "x" })).not.toContain(
    "already selected"
  );

  const both = truncationSentence({
    shown: 80,
    pinned: 0,
    total: 100,
    capped: 20,
    term: "bagru",
    hint: CAP_HINT_WITH_SEARCH,
    serverTruncated: true
  });
  expect(both).toContain("than are shown");
  expect(both, "the arithmetic must not be printed over a truncated count").not.toContain("of 100");

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
  expect(
    listAnnouncement({ total: 74, matched: 6, term: "cot", server: false, pending: false, truncated: false })
  ).toBe("6 of 74 options match cot");
  expect(
    listAnnouncement({ total: 74, matched: 74, term: "", server: false, pending: false, truncated: false })
  ).toBe("74 options");

  expect(
    listAnnouncement({ total: 12, matched: 12, term: "bagru", server: true, pending: false, truncated: false })
  ).toBe("12 options match bagru");
  expect(
    listAnnouncement({ total: 0, matched: 0, term: "bagru", server: true, pending: true, truncated: false })
  ).toBe("Searching for bagru");
  expect(
    listAnnouncement({ total: 0, matched: 0, term: "", server: true, pending: true, truncated: false })
  ).toBe("Loading options");
  expect(
    listAnnouncement({ total: 80, matched: 80, term: "bagru", server: true, pending: false, truncated: true })
  ).toBe("80 options match bagru, and more match than are shown");
  expect(
    listAnnouncement({ total: 80, matched: 80, term: "", server: true, pending: false, truncated: true })
  ).toBe("80 options, and more exist than are shown");
});

test("a multi-select counts what it HOLDS and names what it can, and never the other way round", () => {
  expect(selectionSummarySentence({ selected: 0, names: [] })).toBe("Nothing selected");
  expect(selectionSummarySentence({ selected: 2, names: ["Bagru block printing", "Blue pottery"] })).toBe(
    "2 selected: Bagru block printing, Blue pottery"
  );
  const seven = ["a", "b", "c", "d", "e", "f", "g"];
  expect(selectionSummarySentence({ selected: 7, names: seven })).toBe(
    "7 selected, including a, b, c, d, e, f"
  );
  expect(SUMMARY_NAMES).toBe(6);

  expect(selectionSummarySentence({ selected: 3, names: ["Bagru block printing"] })).toBe(
    "3 selected, including Bagru block printing"
  );
  expect(
    selectionSummarySentence({ selected: 3, names: [] }),
    "no name is not no selection — and the sentence must not trail off into an empty list"
  ).toBe("3 selected");

  const source = read("components", "ui", "SearchableSelect.tsx");
  expect(source).toContain("selected: values.length,");
  expect(source, "the old count is what made the announcement disagree with the button").not.toContain(
    "chosenLabels.length} selected"
  );
});

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

const REFUSE = { mode: "refuse" } as const;

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
        title: "  Bagru block printing  ",
        craftName: "Block printing",
        clusterName: "Bagru",
        state: "Rajasthan",
        startDate: "2026-03-04T00:00:00Z"
      })
    ]),
    { group: true, offPage: REFUSE }
  ).options;

  expect(design[0].label).toBe("Bagru block printing");
  expect(design[0].hint).toBe("Block printing · Bagru · 2026-03-04");

  const cleared = designWorkshopOptions(
    dwList([
      dw({ id: "w2", title: "Kutch weaving", craftName: "Weaving", clusterName: "   ", state: "Gujarat", startDate: "2026-01-09" })
    ]),
    { group: true, offPage: REFUSE }
  ).options;
  expect(cleared[0].hint).toBe("Weaving · Gujarat · 2026-01-09");

  const bareRow = dw({ id: "w3", title: "   " });
  const bare = designWorkshopOptions(dwList([bareRow]), {
    group: true,
    offPage: REFUSE
  }).options;
  expect(bare[0].label).toBe(workshopDisplayTitle(bareRow));
  expect(bare[0].label.startsWith("Workshop")).toBe(true);
  expect(bare[0].hint, "no gaps, no stray separators — the parts that are absent are absent").toBeUndefined();

  const filed = designWorkshopOptions(
    dwList([dw({ id: "w4", title: "Blue pottery", status: "SUBMITTED", craftName: "Pottery", startDate: "2025-11-02" })]),
    { group: true, offPage: REFUSE }
  ).options;
  expect(filed[0].hint).toBe("Submitted · Pottery · 2025-11-02");
  expect(filed[0].disabled, "offered, marked, and never refused").toBeUndefined();

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

  const duringTheLastDay = rowsFor(Date.parse("2026-03-04T23:00:00Z"));
  expect(duringTheLastDay.find((option) => option.value === "closes")?.group).toBeUndefined();

  const theDayAfter = rowsFor(Date.parse("2026-03-05T01:00:00Z"));
  expect(theDayAfter.find((option) => option.value === "closes")?.group).toBe(GROUP_ENDED);

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
  expect(ordered.map((option) => option.value)).toEqual(["a", "c", "b", "d"]);

  const tied = designWorkshopOptions(
    dwList([
      dw({ id: "z", title: "Ajrakh", startDate: "2026-05-05" }),
      dw({ id: "a", title: "Bandhani", startDate: "2026-05-05" }),
      dw({ id: "b", title: "Ajrakh", startDate: "2026-05-05" })
    ]),
    { group: true, offPage: REFUSE }
  ).options;
  expect(tied.map((option) => option.value), "title ascending, then id ascending").toEqual(["b", "z", "a"]);

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
  expect(mixed.every((option) => option.group !== undefined)).toBe(true);
  expect(groupRows(mixed).map((bucket) => bucket.group)).toEqual([GROUP_OPEN, GROUP_SUBMITTED_AND_ARCHIVED]);
  expect(mixed.map((option) => option.value)).toEqual(["open", "sub", "arch"]);

  const ungrouped = designWorkshopOptions(
    dwList([dw({ id: "sub", title: "Blue pottery", status: "SUBMITTED" }), dw({ id: "open", title: "Ajrakh" })]),
    { group: false, offPage: REFUSE }
  ).options;
  expect(ungrouped.every((option) => option.group === undefined)).toBe(true);

  const field = fieldWorkshopOptions(
    fwList([
      fw({ id: "over", title: "Kullu visit", startDate: "2026-02-02", endDate: "2026-02-04" }),
      fw({ id: "live", title: "Sanganer dyeing", startDate: "2026-08-02" })
    ]),
    { group: true, offPage: REFUSE, now: AUG_2026 }
  ).options;
  expect(groupRows(field).map((bucket) => bucket.group)).toEqual([GROUP_OPEN, GROUP_ENDED]);

  for (const built of [openOnly, mixed, ungrouped, field]) {
    expect(built.some((option) => option.value === "")).toBe(false);
  }
});

test("an off-page value is recovered only where the caller said to recover it", () => {
  const stored = dw({ id: "gone", title: "Last year's workshop", startDate: "2025-02-02" });
  const page = [dw({ id: "here", title: "Ajrakh", startDate: "2026-06-06" })];

  const refused = designWorkshopOptions(dwList(page), { group: true, offPage: REFUSE });
  expect(refused.options.map((option) => option.value)).toEqual(["here"]);
  expect(refused.recovered).toBe(false);

  const recovered = designWorkshopOptions(dwList(page), {
    group: true,
    offPage: { mode: "recover", row: stored }
  });
  expect(recovered.options.map((option) => option.value)).toEqual(["gone", "here"]);
  expect(recovered.options[0].group).toBe(GROUP_ON_THIS_RECORD);
  expect(recovered.recovered).toBe(true);
  expect(recovered.drawn).toBe(1);

  const alreadyThere = designWorkshopOptions(dwList(page), {
    group: true,
    offPage: { mode: "recover", row: page[0] }
  });
  expect(alreadyThere.options.map((option) => option.value)).toEqual(["here"]);
  expect(alreadyThere.recovered).toBe(false);

  const midFlight = designWorkshopOptions(
    { kind: "loading" },
    { group: true, offPage: { mode: "recover", row: stored } }
  );
  expect(midFlight.options.map((option) => option.value)).toEqual(["gone"]);
  expect(midFlight.drawn).toBe(0);
  expect(midFlight.options[0].group).toBeUndefined();

  const notYet = designWorkshopOptions(
    { kind: "failed" },
    { group: true, offPage: { mode: "recover", row: null } }
  );
  expect(notYet.options).toEqual([]);

  const withTrash = designWorkshopOptions(
    dwList([...page, dw({ id: "binned", title: "Deleted workshop", deletedAt: "2026-08-01" })]),
    { group: true, offPage: { mode: "recover", row: dw({ id: "filed-in-trash", title: "Deleted, and this record is in it", deletedAt: "2026-08-01" }) } }
  );
  expect(withTrash.options.some((option) => option.value === "binned"), "never offered").toBe(false);
  expect(withTrash.options[0].value, "…and still reported").toBe("filed-in-trash");
  expect(withTrash.options[0].group).toBe(GROUP_ON_THIS_RECORD);
  expect(withTrash.drawn, "the binned row is not counted either").toBe(1);

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
  expect(workshopCutSentence(twoOf196, { searchable: false })).toBe(
    `Showing the first 2 of 196. ${CAP_HINT_WITHOUT_SEARCH}`
  );

  const flagOnly = designWorkshopOptions(dwList([dw({ id: "a", title: "Ajrakh" })], null, true), {
    group: true,
    offPage: REFUSE
  });
  expect(flagOnly.cut).toBe(0);
  expect(workshopCutSentence(flagOnly, { searchable: true })).toBe(
    `Showing the first 1. There are more than are shown. ${CAP_HINT_WITH_SEARCH}`
  );
  expect(workshopCutSentence(flagOnly, { searchable: true, term: "bagru" })).toBe(
    `Showing the first 1. More match “bagru” than are shown. ${CAP_HINT_WITH_SEARCH}`
  );

  const withStored = designWorkshopOptions(dwList([dw({ id: "a", title: "Ajrakh" })], 196), {
    group: true,
    offPage: { mode: "recover", row: dw({ id: "off", title: "Off the page" }) }
  });
  expect(workshopCutSentence(withStored, { searchable: true })).toBe(
    `Showing the first 1 of 196, plus 1 already selected. ${CAP_HINT_WITH_SEARCH}`
  );

  const whole = designWorkshopOptions(dwList([dw({ id: "a", title: "Ajrakh" })]), { group: true, offPage: REFUSE });
  expect(workshopCutSentence(whole, { searchable: true })).toBe("");
  const failed = designWorkshopOptions({ kind: "failed" }, { group: true, offPage: REFUSE });
  expect(workshopCutSentence(failed, { searchable: true })).toBe("");
});

test("the four ways a workshop picker can be empty are four sentences, not one", () => {
  const rows = dwList([dw({ id: "a", title: "Ajrakh" })]);
  const none = dwList([]);

  expect(workshopListNotice({ kind: "loading" }, voice())).toBe("");
  expect(workshopEmptyLabel({ kind: "loading" }, voice())).toBe(SEARCHING_LABEL);

  expect(workshopListNotice({ kind: "failed" }, voice())).toBe(
    "The design workshops list could not be loaded, so this is not showing what exists. Nothing you have entered is at risk — this record can be saved without it."
  );
  expect(workshopEmptyLabel({ kind: "failed" }, voice())).toBe(workshopListNotice({ kind: "failed" }, voice()));

  const offline = workshopListNotice({ kind: "failed" }, voice({ online: false }));
  expect(offline).toBe(
    "This device has not received the design workshops list yet, so there is nothing to pick here. Connect and it will load; this list is never kept on the device, so it always shows current access."
  );
  expect(offline, "a promise R6 forbids").not.toContain("kept on the device from then on");

  expect(workshopListNotice(none, voice())).toBe(
    "No design workshops are open to this account. An administrator can give you access to one."
  );
  expect(workshopListNotice(fwList([]), voice({ table: "field", scoped: false }))).toBe(
    "No workshops have been recorded yet."
  );
  expect(workshopListNotice(fwList([]), voice({ table: "field", scoped: true }))).toBe(
    "No workshops are open to this account. An administrator can give you access to one."
  );

  expect(workshopListNotice(rows, voice())).toBe("");

  expect(workshopListStandsDown(designWorkshopOptions(none, { group: true, offPage: REFUSE }))).toBe(true);
  expect(workshopListStandsDown(designWorkshopOptions(rows, { group: true, offPage: REFUSE }))).toBe(false);
  expect(
    workshopListStandsDown(
      designWorkshopOptions({ kind: "failed" }, { group: true, offPage: { mode: "recover", row: dw({ id: "off", title: "On this record" }) } })
    )
  ).toBe(false);
});

const WORKSHOP_SELECT = ["components", "forms", "WorkshopSelect.tsx"];
const SCOPE_SELECT = ["components", "WorkshopScopeSelect.tsx"];

const UNASSIGNED_GROUP = "Records with no workshop";
const UNASSIGNED_ROW: SelectOption = {
  value: "none",
  label: "Not linked to a workshop",
  group: UNASSIGNED_GROUP
};

test("the record form's workshop box searches the server, at exactly the size it draws", () => {
  const source = read(...WORKSHOP_SELECT);
  const request = withoutComments(
    between(source, "const timer = window.setTimeout(() => {", "SEARCH_DEBOUNCE_MS : 0)")
  );

  expect(request, "the panel's term never reached the request").toContain("search: trimmed || undefined");
  expect(request).toContain('accessibleOnly: "true"');
  expect(request).toContain("pageSize: WORKSHOP_OPTION_PAGE_SIZE");
  expect(request, "the picker's own request is back on the 100-row ceiling").not.toContain("LIST_PAGE_CEILING");
  expect(withoutComments(source), "a bare literal page size").not.toContain("pageSize: 100");

  expect(withoutComments(source)).toContain("serverQuery={search}");
  expect(source).toContain("}, trimmed ? SEARCH_DEBOUNCE_MS : 0);");
});

test("the workshop picker's `none` row has exactly one owner, and it is the primitive", () => {
  const source = withoutComments(read(...WORKSHOP_SELECT));

  expect(source).toContain("noneLabel={NO_FIELD_WORKSHOP}");
  expect(source, "the hand-built row is still here").not.toContain('{ value: "", label:');
  expect(source, "the local copy of the string is still here").not.toContain("const NO_WORKSHOP_LABEL");

  expect(read("lib", "workshopOptions.ts")).toContain('export const NO_FIELD_WORKSHOP = "Not linked to a workshop"');
});

test("a failed workshop read is not an empty workshop list, on either shared control", () => {
  const form = withoutComments(read(...WORKSHOP_SELECT));
  const filter = withoutComments(read(...SCOPE_SELECT));

  expect(form).toContain('setList({ kind: "failed" })');
  expect(filter).toContain('setList({ kind: "failed" })');
  expect(form).not.toContain("setWorkshops([])");
  expect(filter).not.toContain("setWorkshops([])");

  expect(form).toContain("workshopListNotice(list, voice)");
  expect(form).not.toContain("No workshops are open to this account yet. A record can be saved without one");
  expect(form).toContain("emptyLabel={workshopEmptyLabel(list, voice)}");
});

test("the scope sentence is never printed over an answer to a search term", () => {
  const source = withoutComments(read(...WORKSHOP_SELECT));

  expect(source).toContain('const listNotice = list.kind === "ok" && searchApplied ? "" : workshopListNotice(list, voice);');

  expect(source).toContain("const standingDown = !search.pending && !search.value.trim() && workshopListStandsDown(options);");
  expect(source).toContain("disabled={standingDown}");
});

test("the record's own workshop survives the search box, not merely the page cut", () => {
  const source = withoutComments(read(...WORKSHOP_SELECT));

  expect(source).toContain("useRecordOffPage");
  expect(source).toContain("offPage: { mode: \"recover\", row: storedWorkshop }");

  expect(source).toContain("allWorkshops.find((workshop) => workshop.id === workshopId) ?? null");
});

test("the scope filter states its cut, at the size it draws, for the first time", () => {
  const source = read(...SCOPE_SELECT);

  expect(source).toContain("<CappedListNotice");
  expect(source).toContain('setCut(listCut(result, "workshops"))');
  expect(source, "every workshop is fetched, page by page").toContain('listAllPages<Workshop>("/workshops", { pageSize: WORKSHOP_OPTION_PAGE_SIZE })');
  expect(withoutComments(source)).not.toContain("const WORKSHOP_PAGE_SIZE = 100");
});

test("empty still means everything BY ABSENCE, and there is still only one way to spell it", () => {
  const code = withoutComments(read(...SCOPE_SELECT));

  expect(code).toContain('workshopIds.length ? workshopIds.join(",") : undefined');

  expect(code).toContain("bulk={false}");
  expect(code, "a second spelling of everything").not.toContain("Select all");

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

  expect(groupRows([...mixed, UNASSIGNED_ROW]).map((bucket) => bucket.group)).toEqual([
    GROUP_OPEN,
    GROUP_ENDED,
    UNASSIGNED_GROUP
  ]);

  const bare = groupRows([...mixed, { value: "none", label: "Not linked to a workshop" }]);
  expect(bare[0].group).toBeNull();
  expect(bare[0].rows[0].option.value).toBe("none");

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

test("the filter's failure sentence is composed from §3.5's opening plus the clause it owns", () => {
  const source = read(...SCOPE_SELECT);
  const code = withoutComments(source);
  const clause = "Nothing is hidden by it — with no workshop chosen, this screen is showing every record.";
  const filterVoice = voice({ table: "field", scoped: false, online: true, reassurance: clause });
  const shared = workshopListNotice({ kind: "failed" }, filterVoice);

  expect(shared).toBe(
    "The workshops list could not be loaded, so this is not showing what exists. " +
      "Nothing is hidden by it — with no workshop chosen, this screen is showing every record."
  );

  const opening = "The workshops list could not be loaded, so this is not showing what exists.";
  expect(code, "the opening is the module's, not a second copy here").not.toContain(opening);
  expect(code).toContain("reassurance: SCOPE_SHOWS_EVERYTHING");
  expect(code).toContain(clause);

  expect(workshopListNotice({ kind: "failed" }, voice({ table: "field", scoped: false }))).toContain(
    "this record can be saved without it"
  );
  expect(code, "a record-form clause on a filter").not.toContain("this record can be saved without it");

  const sharedOffline = workshopListNotice({ kind: "failed" }, voice({ ...filterVoice, online: false }));
  expect(sharedOffline).toContain("so there is nothing to pick here.");
  expect(`${sharedOffline} ${clause}`).toContain("this screen is showing every record.");
  expect(code).toContain(
    'const notice = list.kind === "failed" && !online ? `${shared} ${SCOPE_SHOWS_EVERYTHING}` : shared;'
  );

  expect(code).toContain("const shared = workshopListNotice(list, voice);");
  expect(code).toContain('table: "field",');
  expect(code).toContain("scoped: false,");
  expect(workshopListNotice(fwList([]), voice({ table: "field", scoped: false }))).toBe(
    "No workshops have been recorded yet."
  );
});

test("the media upload's workshop rows come from the shared builder, not a seventh label shape", () => {
  const source = read("app", "(protected)", "media", "page.tsx");
  const branch = between(source, 'case "workshop": {', 'case "craft": {');

  expect(branch).toContain("fieldWorkshopOptions(");
  expect(branch).not.toContain("sortRecent(page.items)");
  expect(branch).not.toContain('x.title?.trim() || "Untitled workshop"');
  expect(branch).toContain("pageSize: WORKSHOP_OPTION_PAGE_SIZE");
  expect(branch).toContain('listCut(page, "workshops")');
});

const DW_READERS = [
  ["the record forms' picker", ["components", "forms", "DesignWorkshopSelect.tsx"]],
  ["the viewers panel", ["components", "settings", "DesignWorkshopViewersPanel.tsx"]],
  ["the inspectors panel", ["components", "settings", "DesignWorkshopInspectorsPanel.tsx"]],
  ["the questionnaires list", ["app", "(protected)", "questionnaires", "page.tsx"]],
  ["the questionnaire detail", ["app", "(protected)", "questionnaires", "[id]", "page.tsx"]]
] as const;

const DW_CALLERS = [
  ...DW_READERS,
  ["design review", ["app", "(protected)", "design-review", "page.tsx"]],
  ["the upload dialog", ["components", "questionnaires", "UploadDialog.tsx"]],
  ["the reuse dialog", ["components", "questionnaires", "ReuseDialog.tsx"]]
] as const;

test("a failed design-workshop read stops rendering as an account with no design workshops", () => {
  for (const [name, path] of DW_READERS) {
    const source = withoutComments(read(...path));
    expect(source, `${name} must be able to say the read failed`).toContain('{ kind: "failed" }');
    expect(source, `${name} must record whether the device was reachable when it did`).toContain(
      "deviceLooksOffline()"
    );
    expect(source, `${name} must let the shared module pick the sentence`).toMatch(
      /workshop(ListNotice|EmptyLabel)\(/
    );
    expect(source, `${name} must not turn a failure into an empty array`).not.toContain(
      "setSummaries([])"
    );
    expect(source, `${name} must not seed the list with an answer nobody gave`).not.toContain(
      "useState<DwSummary[]>([])"
    );
  }

  const review = withoutComments(read("app", "(protected)", "design-review", "page.tsx"));
  expect(review).toContain('scoped: true, online: !listFailure?.unreachable');
  expect(review).toContain('{ kind: "failed" }');
});

test("every design-workshop read asks for exactly as many rows as a panel can draw", () => {
  for (const [name, path] of DW_READERS) {
    const source = withoutComments(read(...path));
    expect(source, `${name} must name one page size`).toContain("WORKSHOP_OPTION_PAGE_SIZE");
    expect(source, `${name} still asks for a round hundred`).not.toContain("pageSize: 100");
  }
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
    const passesTheConstant =
      source.includes("noneLabel={NO_DESIGN_WORKSHOP}") || source.includes("noneLabel = NO_DESIGN_WORKSHOP");
    expect(passesTheConstant, `${name} must name the shared constant, not a literal`).toBe(true);
    expect(source, `${name} still hand-builds a "" row`).not.toContain('{ value: "", label:');
  }
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
  expect(decided["the record forms' picker"]).toBe("recover");
  expect(decided["the questionnaire detail"]).toBe("recover");
  expect(decided["the inspectors panel"]).toBe("recover");
  expect(decided["the questionnaires list"]).toBe("refuse");
  expect(decided["design review"]).toBe("refuse");
  expect(decided["the upload dialog"]).toBe("refuse");

  expect(withoutComments(read("components", "forms", "DesignWorkshopSelect.tsx"))).toMatch(
    /useRecordOffPageState<DwSummary>\(\s*"\/design-workshops"/
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
    expect(code, `${name} must not also raise the unknown-total arm`).not.toContain(
      "truncated: true"
    );
  }

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
  for (const path of [
    ["app", "(protected)", "questionnaires", "page.tsx"],
    ["app", "(protected)", "questionnaires", "[id]", "page.tsx"]
  ] as const) {
    const source = withoutComments(read(...path));
    expect(source).toContain("workshopListNotice(workshopList, workshopVoice) ||");
    expect(source).toContain("cappedListNotice(");
    expect(source).toContain("workshopsNotice={workshopNotice}");
  }

  const upload = withoutComments(read("components", "questionnaires", "UploadDialog.tsx"));
  expect(upload, "the list's length must not decide whether the field exists").not.toContain(
    "workshops?.length ?"
  );
  expect(upload).toContain("{editing ? null : (");
  expect(upload).toContain("disabled={!workshops?.length}");
});

test("the reuse dialog's annotation moved out of the label it was competing with", () => {
  const dialog = withoutComments(read("components", "questionnaires", "ReuseDialog.tsx"));
  expect(dialog, "the suffix must not be back on the label").not.toContain(
    "— where this one already is`"
  );
  expect(dialog).toContain('`where this one already is · ${option.hint}`');
  expect(dialog).toContain("sourceWorkshopId");
});

test("the viewers panel's workshop box asks the repository, not the page it had already fetched", () => {
  const code = withoutComments(read("components", "settings", "DesignWorkshopViewersPanel.tsx"));
  const field = between(code, 'ariaLabel="Design workshop"', "/>");

  expect(field, "the panel's own box must drive a server query").toContain("serverQuery={{");
  expect(field, "and must not also filter the array it was handed").not.toContain("searchable");
  expect(code, "the term must reach the request").toContain("search: term || undefined");
  expect(field, "the unknown-total arm belongs to a route that cannot count").not.toContain(
    "truncated"
  );
});

test("a search moves the viewers panel's options and never its administration", () => {
  const code = withoutComments(read("components", "settings", "DesignWorkshopViewersPanel.tsx"));

  expect(code, "the selection must survive an answer that no longer holds it").toContain(
    "knownWorkshops.get(workshopId)"
  );
  expect(code).toContain('offPage: { mode: "recover", row: selectedWorkshop }');

  expect(code, "the clear must not key on list membership any more").not.toContain(
    "!offered.some((summary) => summary.id === workshopId)"
  );
  expect(code).toContain("if (!workshopId || !typeFilter || typeNarrowingSuspended) return;");

  expect(code, "the box that suspends the type filter must empty when it has done its job").toContain(
    'setWorkshopSearch("");'
  );
});

test("the type filter stands down while the workshop box holds a term, and says so", () => {
  const source = read("components", "settings", "DesignWorkshopViewersPanel.tsx");
  const code = withoutComments(source);

  expect(code, "the suspension has to be a named fact, not an inline condition").toContain(
    'const typeNarrowingSuspended = workshopSearchTerm !== "";'
  );
  expect(code, "it must govern the narrowing itself").toContain(
    "if (!typeFilter || typeNarrowingSuspended) return { offered: all, untyped: 0 };"
  );
  expect(code, "and the control it belongs to").toContain(
    "disabled={!typeKnown || typeNarrowingSuspended}"
  );
  expect(source, "a stand-down with no sentence is the silent empty picker").toContain(
    "Type filter is off while searching."
  );

  expect(code).toContain(
    'const filteredAway = workshops.kind === "ok" && workshops.rows.length > 0 && offered.length === 0;'
  );
});

test("the viewers panel's cut sentence counts the rows a reader can actually see", () => {
  const code = withoutComments(read("components", "settings", "DesignWorkshopViewersPanel.tsx"));

  expect(code, "the set, not the answer, is what the reader is looking at").toContain(
    "const workshopCut = workshopCutSentence(workshopSet, {"
  );

  expect(code).toContain("searchable: true");
  expect(code, "the no-reach arm went with the box that justified it").not.toContain(
    "cappedListNotice("
  );

  expect(code).toContain('filteredAway || (workshops.kind === "ok" && workshopSearchTerm)');
});

test("a recovered row cannot push the list past the number the panel draws", () => {
  const page = Array.from({ length: RENDER_CAP }, (_, index) =>
    dw({
      id: `w${String(index).padStart(3, "0")}`,
      title: `Workshop ${String(index).padStart(3, "0")}`,
      startDate: `2026-01-01T00:00:${String(index).padStart(2, "0")}Z`
    })
  );

  const whole = designWorkshopOptions(dwList(page, 196), { group: true, offPage: REFUSE });
  expect(whole.options.length).toBe(RENDER_CAP);
  expect(whole.drawn).toBe(RENDER_CAP);
  expect(workshopCutSentence(whole, { searchable: true })).toBe(
    `Showing the first ${RENDER_CAP} of 196. ${CAP_HINT_WITH_SEARCH}`
  );

  const stored = dw({ id: "off-page", title: "Where this record actually is", startDate: "2019-04-04" });
  const recovered = designWorkshopOptions(dwList(page, 196), {
    group: true,
    offPage: { mode: "recover", row: stored }
  });

  expect(recovered.options.length, "never more rows than a panel draws").toBe(RENDER_CAP);
  expect(recovered.options[0].value).toBe("off-page");
  expect(recovered.options[0].group).toBe(GROUP_ON_THIS_RECORD);
  expect(recovered.options.some((option) => option.value === "w000"), "the oldest of the page").toBe(false);
  expect(recovered.options.some((option) => option.value === "w001")).toBe(true);

  expect(recovered.drawn).toBe(RENDER_CAP - 1);
  expect(recovered.cut).toBe(196 - (RENDER_CAP - 1));
  expect(workshopCutSentence(recovered, { searchable: true })).toBe(
    `Showing the first ${RENDER_CAP - 1} of 196, plus 1 already selected. ${CAP_HINT_WITH_SEARCH}`
  );

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

test("the four state sentences take a caller's noun, and drop R6's reason where R6 does not apply", () => {
  const register = (over: Partial<WorkshopListVoice> = {}): WorkshopListVoice => ({
    table: "field",
    noun: "artisans",
    scoped: false,
    accessList: false,
    online: true,
    ...over
  });

  expect(workshopListNotice({ kind: "failed" }, register())).toBe(
    "The artisans list could not be loaded, so this is not showing what exists. Nothing you have entered is at risk — this record can be saved without it."
  );
  expect(workshopListNotice(fwList([]), register())).toBe("No artisans have been recorded yet.");
  expect(workshopListNotice(fwList([]), { table: "design", scoped: true, online: true })).toBe(
    "No design workshops are open to this account. An administrator can give you access to one."
  );

  const offlineRegister = workshopListNotice({ kind: "failed" }, register({ online: false }));
  expect(offlineRegister).toContain("so there is nothing to pick here.");
  expect(offlineRegister).toContain("Connect and it will load.");
  expect(offlineRegister, "a grant rule quoted over a register").not.toContain("current access");
  expect(workshopListNotice({ kind: "failed" }, { table: "field", scoped: true, online: false })).toContain(
    "so it always shows current access"
  );

  const filed = workshopListNotice(
    { kind: "failed" },
    register({ noun: "workshops", reassurance: "Nothing you have typed is lost. The message above says what happened." })
  );
  expect(filed).toBe(
    "The workshops list could not be loaded, so this is not showing what exists. Nothing you have typed is lost. The message above says what happened."
  );
  expect(filed, "a record clause on a surface with no record").not.toContain("this record can be saved without it");
  expect(
    workshopListNotice({ kind: "failed" }, register({ online: false, reassurance: "Not used offline." }))
  ).not.toContain("Not used offline.");
});

test("the process form's two pickers stand down when their list is empty, and stop claiming to be required", () => {
  const source = read("components", "forms", "ProcessForm.tsx");

  expect(source, "R2: a field may only be mandatory where it is answerable").toContain(
    "const artisanRequired = artisanOptions.length > 0;"
  );
  expect(source).toContain("const productRequired = artisanProducts.length > 0;");
  expect(source, "R2b: the validator stands down").toContain("if (artisanRequired && !artisanId) {");
  expect(source).toContain("if (productRequired && !productId) {");
  const bare = withoutComments(source);
  expect(bare, "the unconditional refusal is gone").not.toMatch(/if\s*\(!artisanId\)\s*\{\s*setArtisanError/);
  expect(bare).not.toMatch(/if\s*\(!productId\)/);

  expect(bare).toContain('<Field label="Artisan" required={artisanRequired}>');
  expect(bare).toContain('<Field label="Product" required={productRequired}>');
  expect(bare, "a typed asterisk over a stood-down field").not.toContain('<Field label="Artisan" required>');
  expect(bare).not.toContain('<Field label="Product" required>');

  expect(bare).toContain("emptyLabel={workshopEmptyLabel(artisanList, artisanVoice)}");
  expect(bare).toContain("const artisanNotice = workshopListNotice(artisanList, artisanVoice);");
  expect(bare).toContain("{artisanNotice ?");
  expect(bare).toContain('disabled={artisanList.kind !== "loading" && artisanOptions.length === 0}');
  expect(bare).toContain('noun: "artisans"');
  expect(bare).toContain("scoped: false");
  expect(bare).toContain("accessList: false");
});

test("the record pickers say a failed read failed, on both forms, from one place", () => {
  const hook = read("components", "forms", "recordPickers.ts");

  expect(hook).toContain("setArtisansFailedForCraft(craftRosterKey);");
  expect(hook).toContain("const [artisansFailedForCraft, setArtisansFailedForCraft] = useState<string | null>(null);");
  expect(hook).toContain("craftNotice: workshopListNotice(craftList, craftVoice)");

  for (const form of ["ProductForm.tsx", "ToolForm.tsx"]) {
    const formSource = withoutComments(read("components", "forms", form));
    expect(formSource, form).toContain("<CraftSelect");
    expect(formSource, form).toContain("emptyLabel={craftArtisanEmptyLabel || undefined}");
    expect(formSource, form).toContain("{craftArtisanNotice ?");
    const answered =
      form === "ToolForm.tsx"
        ? "artisansLoadedForCraft === craftRosterKey && artisansForCraft.length === 0"
        : "artisansLoadedForCraft === craftId && artisansForCraft.length === 0";
    expect(formSource, form).toContain(answered);
  }
});

test("the pickers that used to assert non-existence now name the state they are in", () => {
  const workshops = withoutComments(read("app", "(protected)", "workshops", "page.tsx"));
  expect(workshops, "a claim about the register").not.toContain('emptyLabel="No artisans recorded yet"');
  expect(workshops).not.toContain('emptyLabel="No crafts available yet"');
  expect(workshops).toContain("emptyLabel={workshopEmptyLabel(artisanList, artisanVoice)}");
  expect(workshops).toContain("<CraftSelect");

  const tasks = withoutComments(read("components", "tasks", "AssignmentBuilder.tsx"));
  expect(tasks, "three claims from one failed read").not.toContain('emptyLabel="No active questionnaire sections"');
  expect(tasks).toContain('emptyLabelFor("people", "Nobody in a role below yours")');
  expect(tasks).toContain('emptyLabelFor("questionnaire sections", "No active questionnaire sections")');
  expect(tasks).toMatch(/emptyLabelFor\(\s*"artisans",/);
  expect(withoutComments(read("app", "(protected)", "settings", "tasks", "page.tsx"))).toContain(
    "optionsFailed={Boolean(optionsError)}"
  );

  const tools = withoutComments(read("components", "forms", "ToolAssignmentSection.tsx"));
  expect(tools).not.toContain('emptyLabel={craftIds.length ? "No artisans for these crafts" : "Select crafts first"}');
  expect(tools).toMatch(/emptyLabelFor\(\s*"artisans",/);

  const access = withoutComments(read("components", "settings", "WorkshopAccessRequestPanel.tsx"));
  expect(access, "an empty array standing in for a failed read").not.toContain(
    'emptyLabel="No workshops have been recorded yet"'
  );
  expect(access).toContain("emptyLabel={workshopEmptyLabel(workshopList, workshopVoice)}");
  expect(access).toContain("setWorkshopsFailed(true);");

  const designers = withoutComments(read("components", "designworkshop", "WorkshopDesignerPicker.tsx"));
  expect(designers).toContain("workshopEmptyLabel(eligibleList, eligibleVoice)");
  expect(designers).toContain('eligibleList.kind === "ok"');
});

test("a picker's fetch asks for exactly the number of rows the panel can draw", () => {
  const artisanRules = withoutComments(read("components", "questionnaires", "interviewArtisans.ts"));
  expect(artisanRules).toContain("pageSize: LIST_PAGE_CEILING");
  const questionnaire = withoutComments(read("app", "(protected)", "questionnaire", "page.tsx"));
  expect(questionnaire, "the page itself must not fetch artisans again beside the hook").not.toContain(
    'listResource<Artisan>("/artisans"'
  );
  const workshops = withoutComments(read("app", "(protected)", "workshops", "page.tsx"));
  expect(workshops).toContain('listResource<Artisan>("/artisans", { pageSize: RENDER_CAP })');
  expect(workshops, "crafts are the catalogue, not a page").not.toContain('listResource<Craft>("/crafts"');

  expect(workshops).toContain('listResource<User>("/users", { pageSize: LIST_PAGE_CEILING })');
  expect(withoutComments(read("app", "(protected)", "users", "page.tsx"))).toContain(
    'listResource<User>("/users", { pageSize: LIST_PAGE_CEILING })'
  );
});
