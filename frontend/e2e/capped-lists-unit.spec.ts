import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  cappedListNotice,
  cutOf,
  LIST_PAGE_CEILING,
  listCut,
  mergeById,
  type ListCut
} from "@/components/data/cappedList";
import { craftChangeClearsArtisan } from "@/components/forms/recordPickers";
import type { Artisan, PageResult } from "@/lib/types";

/**
 * ONE PAGE RENDERED AS THOUGH IT WERE THE WHOLE LIST — five sites, one defect.
 *
 * Every list route in this application clamps `pageSize` to `MAX_PAGE_SIZE = 100`
 * (`backend/app/services/pagination.py`) and answers `{ items, total, page, pageSize, pages }`. The
 * funnel's three dropdowns, every record picker in the web forms, View Data's "Browse by type", and
 * the /media upload form's linked-record picker each asked for that ceiling, kept `.items`, threw
 * `total` away, and drew the result with nothing on screen distinguishing a short list from a cut
 * one. Counted against this repository's Postgres on 2026-08-15, every table behind those controls
 * is past the ceiling: **MediaFile 2530 · ProductDocumentation 878 · Artisan 749 · Workshop 196 ·
 * Craft 178 · ToolDocumentation 177 · Process 177**. So "not in this list" and "not in the
 * repository" rendered identically — the class of defect the design-workshop viewer picker already
 * cost this repository once (docs/OPEN_FINDINGS.md, closed 2026-08-13, 353 invisible accounts).
 *
 * WHY THIS IS A NODE SPEC, PARTLY PURE AND PARTLY A SOURCE READ. `components/data/cappedList.ts` is
 * deliberately pure — the same shape as `lib/designWorkshopViewers.eligibleViewerNotice`, for the
 * same reason: one of its states cannot be produced by any live database, so a decision buried in
 * JSX is only ever exercised by somebody looking at a screen. That half is tested by CALLING it. The
 * other half lives in params objects and effect dependency arrays inside page components, and this
 * repository has no React renderer in its devDependencies — Playwright is the whole of it — so those
 * are read out of the source, exactly as `questionnaire-workshop-filter-unit.spec.ts` and
 * `derived-fields-unit.spec.ts` read theirs. Every assertion below fails against the tree as it was.
 *
 * WHAT THE SOURCE READS DO NOT PROVE: that a browser paints the sentence. That belongs in a
 * signed-in spec against a real database, and cannot be written honestly against a stub — the whole
 * defect is that a table grew past a number, and a fixture is a list somebody chose the length of.
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
 * The source with its comments removed, for the assertions that ban a dead identifier.
 *
 * WHY THIS EXISTS, because the obvious version of these tests is wrong in a way that quietly
 * punishes the house style. Three of the bans below are "this expression must not come back":
 * `workshopArtisanIds`, the client-side intersection it drove, and the false claim that
 * `/artisans` takes no `workshopId`. But the comments that now stand where those used to are
 * REQUIRED to name them — a "do not put the client-side filter back" note that cannot say what the
 * filter was called is not a warning, it is a riddle. Run against the raw file, the ban therefore
 * fails on the very comment that closes the finding, and the only ways to make it pass are to
 * delete the explanation or to misspell the identifier inside it. Both are worse than the defect.
 *
 * So the bans run against CODE and the prose is asserted separately, positively — see the
 * refutation check in the artisans test, which is the assertion with the actual teeth: it is not
 * enough that the false claim is no longer acted on, it must be recorded as false so the next
 * reader does not re-derive it.
 *
 * Block comments first, then line comments. Safe here because no string literal in any file this
 * spec reads contains `//` — verified by grep across all four on 2026-08-15, and if that ever stops
 * being true this helper starts eating code and these tests fail loudly rather than silently
 * passing.
 */
function codeOf(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

/** A `PageResult` of `loaded` rows out of `total`, which is the only shape these helpers read. */
function page<T>(loaded: number, total: number, row: (index: number) => T): PageResult<T> {
  return {
    items: Array.from({ length: loaded }, (_, index) => row(index)),
    total,
    page: 1,
    pageSize: LIST_PAGE_CEILING,
    pages: Math.ceil(total / LIST_PAGE_CEILING)
  };
}

const artisan = (id: string, craftId: string | null): Artisan =>
  ({ id, name: `Artisan ${id}`, place: "Bagru", craftId }) as unknown as Artisan;

/* ────────────────────────────────────────────────────────────────────────────
 * The shared primitive: when there is something to say, and what it says
 * ──────────────────────────────────────────────────────────────────────────── */

test("a complete list says nothing at all", () => {
  // Silence is the common answer and the correct one. The repository owner has twice asked for less
  // text on these screens, so a standing note about pagination on every visit is not acceptable —
  // and this is what makes the sentence mean something when it does appear.
  expect(listCut(page(20, 20, (i) => ({ id: String(i) })), "artisans")).toBeNull();
  expect(listCut(page(0, 0, (i) => ({ id: String(i) })), "artisans")).toBeNull();
  expect(cappedListNotice(null)).toBe("");
});

test("a list exactly as long as the ceiling is not assumed to be cut", () => {
  // 100 loaded out of 100 is a complete answer that happens to fill the page. Reporting a cut here
  // — which a `loaded >= LIST_PAGE_CEILING` test would — is the mirror of the defect: a screen that
  // cries truncation at a whole list teaches its reader to ignore the sentence.
  expect(listCut(page(100, 100, (i) => ({ id: String(i) })), "crafts")).toBeNull();
});

test("a cut list prints both numbers and says the search box cannot reach the rest", () => {
  const cut = listCut(page(100, 749, (i) => ({ id: String(i) })), "artisans");
  expect(cut).toEqual({ noun: "artisans", loaded: 100, total: 749 });

  const sentence = cappedListNotice(cut);
  // BOTH numbers, always. "Showing the first 100" alone leaves the reader guessing whether that is
  // most of the corpus or an eighth of it, and the difference is whether they go looking elsewhere
  // or conclude the record was never created.
  expect(sentence).toContain("100");
  expect(sentence).toContain("749");
  expect(sentence).toContain("649");
  // The pickers' ComboBox filters the array it was handed (components/ui/SearchableSelect), so it
  // cannot reach past the cut. The sentence must say so rather than invite a search that fails.
  expect(sentence).toContain("typing here searches only the 100 shown");
  expect(sentence).not.toContain("pager");
});

test("where there IS a pager the sentence names it instead", () => {
  // View Data's browse panel is the one site in this class with a real second page, so it is the one
  // site allowed to say "use the pager". Saying it anywhere else would be advice that cannot work —
  // the mistake the viewer picker's notice was fixed for on 2026-08-13.
  const sentence = cappedListNotice(cutOf(100, 2530, "media"), "pager");
  expect(sentence).toContain("use the pager");
  expect(sentence).toContain("2530");
  expect(sentence).toContain("not searched by the box above");
});

test("an answer with nothing in it is never told to narrow or to page", () => {
  // THE STATE NO LIVE DATABASE PRODUCES, tested first for exactly that reason: page one of a
  // non-empty list always holds rows, so this arm is only reachable through a page past the end —
  // and it is where silence does the most damage, because the control renders "No entries for this
  // type" over a repository holding hundreds.
  const sentence = cappedListNotice(cutOf(0, 431, "artisans"));
  expect(sentence).toContain("431");
  expect(sentence).toContain("this is not an empty repository");
  expect(sentence).not.toContain("typing here");
  expect(sentence).not.toContain("pager");
  // And the same is true with a pager on screen: nothing loaded is not something paging fixes.
  expect(cappedListNotice(cutOf(0, 431, "artisans"), "pager")).toBe(
    cappedListNotice(cutOf(0, 431, "artisans"))
  );
});

test("a total the wire did not carry says nothing rather than claiming a cut of NaN", () => {
  // `apiFetch` is a plain cast, not a schema parse, so a deployment predating the field puts
  // `undefined` here at runtime. The safe default is the quiet one.
  const missing = { items: [{ id: "a" }], page: 1, pageSize: 100, pages: 1 } as unknown as PageResult<{ id: string }>;
  expect(listCut(missing, "artisans")).toBeNull();
});

test("merging option pages adds rows and never removes one", () => {
  // A picker holds several overlapping pages — the repository-wide one, the craft-scoped one, and
  // the single row looked up by id. Replacing the array with the newest answer is what made an edit
  // form forget the artisan it was editing the moment a craft was picked, and these arrays are also
  // handed to `carryScope`, where a missing id is read as "not reachable from this form".
  const first = [artisan("a", "c1"), artisan("b", "c1")];
  const merged = mergeById(first, [artisan("b", "c1"), artisan("z", "c2")]);
  expect(merged.map((row) => row.id)).toEqual(["a", "b", "z"]);
  // Nothing new to add returns the same array, so a merge cannot churn a dependency array.
  expect(mergeById(first, [artisan("a", "c1")])).toBe(first);
  expect(mergeById(first, [])).toBe(first);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Finding 1 — the Artisans list dropped the funnel's workshop
 * ──────────────────────────────────────────────────────────────────────────── */

test("the artisans list sends the funnel's workshop to the server", () => {
  const source = read("app", "(protected)", "artisans", "page.tsx");
  const request = between(source, 'listResource<Artisan>("/artisans"', "});");

  // `list_artisans` has declared `workshopId` all along (backend/app/api/routes/artisans.py:198) and
  // its clause is BROADER than anything the browser could do: it ORs the `Artisan.workshopId` column
  // with the `WorkshopArtisan` join, while the intersection this replaces could only see join rows.
  expect(request, "the workshop pick must reach the server").toContain("workshopId: funnel.workshopId");
  // The craft param is the control: it was always sent, so its presence proves the marker landed on
  // the artisans request and not on some other list.
  expect(request).toContain("craftId: funnel.craftId");
});

test("the artisans list no longer intersects one server page in the browser", () => {
  const source = read("app", "(protected)", "artisans", "page.tsx");

  // The intersection gave two opposite wrong answers depending on which link path a workshop had
  // used: an empty table under a pager reading "Page 1 of 22 · 431 records" when the workshop's
  // people were older than the newest 20, and EVERY artisan in the repository when the workshop
  // carried no join rows at all. Both are only possible while the narrowing is done here.
  expect(codeOf(source), "the client-side workshop intersection must be gone").not.toContain(
    "workshopArtisanIds"
  );

  // THE CLAIM THAT CAUSED IT, held to a stricter standard than mere absence.
  //
  // The old code carried the comment `/artisans supports craftId but not workshopId`, which was
  // false when it was written — `list_artisans` has declared the parameter as long as the other list
  // routes have. A false claim in a comment is why this survived three readings: each one checked
  // the note instead of the route. Deleting it would leave the next reader free to re-derive it from
  // the same wrong guess, so the requirement is not that the sentence is gone but that wherever it
  // still appears it is marked false. Hence: banned from code, and required to carry its refutation
  // in prose.
  const claim = "supports craftId but not workshopId";
  expect(codeOf(source), "the false claim must not be live code").not.toContain(claim);
  if (source.includes(claim)) {
    expect(source, "the claim may only survive as history, explicitly refuted").toContain(
      "that claim was simply false"
    );
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * Finding 2 — the shared funnel's three dropdowns
 * ──────────────────────────────────────────────────────────────────────────── */

test("the funnel's artisan options are narrowed by the server, not by filtering a cut page", () => {
  const source = read("components", "FunnelFilters.tsx");
  const request = between(source, 'listResource<Artisan>("/artisans"', "})");

  expect(request, "the workshop must narrow the QUERY, not the array").toContain("workshopId: value.workshopId");
  expect(request).toContain("craftId: value.craftId");

  // The dependency array is the other half and either alone is still broken: with the param but not
  // the dependency the options are whatever the last craft change happened to fetch, under a
  // workshop label claiming to have narrowed them.
  const effect = between(source, 'listResource<Artisan>("/artisans"', "]);");
  expect(effect).toContain("value.workshopId");

  // The compounding filter itself must be gone: `artisans.filter(a => workshopArtisanIds.has(a.id))`
  // over the newest 100 of 749 is what made a workshop whose people predate that hundred render as
  // "All artisans" plus a few recent names. Banned from CODE only — the comment standing where it
  // used to has to be able to name it, see `codeOf`.
  expect(codeOf(source)).not.toContain("workshopArtisanIds");
});

test("the funnel keeps every list's total and renders the notice", () => {
  const source = read("components", "FunnelFilters.tsx");

  expect(source).toContain('listCut(workshopResult, "workshops")');
  expect(source).toContain('listCut(craftResult, "crafts")');
  expect(source).toContain('listCut(result, "artisans")');
  expect(source, "a cut list must say so on screen").toContain("<CappedListNotice");
  // 196 workshops and 178 crafts on this database, so all three arms are live, not hypothetical.
  expect(source).toContain("LIST_PAGE_CEILING");
});

/* ────────────────────────────────────────────────────────────────────────────
 * Finding 3 — the record pickers, and the link a craft correction destroyed
 * ──────────────────────────────────────────────────────────────────────────── */

test("an artisan merely absent from the loaded page does not count as the wrong craft", () => {
  // THE SHARP HALF OF THE FINDING. Both forms asked
  //   !artisans.some((a) => a.id === artisanId && a.craftId === next)
  // which is false for two unrelated reasons — the craft differs, or the artisan is not in the array
  // at all — and cleared the link for both. Against a 100-row page of 749 artisans the second reason
  // was the ordinary one on any older record, `artisanId` is in the backend's `CLEARABLE_KEYS`, so
  // the save wrote an explicit null and destroyed the artisan-to-product link under a 200.
  //
  // This case is the regression: the old expression returns TRUE here (clear it) and the new one
  // returns FALSE (leave it alone).
  expect(
    craftChangeClearsArtisan({ nextCraftId: "c2", artisanId: "off-page", artisans: [artisan("a", "c1")] })
  ).toBe(false);

  // Known and genuinely of another craft: still cleared, because that is what the guard is FOR.
  expect(
    craftChangeClearsArtisan({ nextCraftId: "c2", artisanId: "a", artisans: [artisan("a", "c1")] })
  ).toBe(true);

  // Known and of the chosen craft: untouched.
  expect(
    craftChangeClearsArtisan({ nextCraftId: "c1", artisanId: "a", artisans: [artisan("a", "c1")] })
  ).toBe(false);

  // Nothing selected, or the craft being unlinked entirely: nothing to clear either way.
  expect(craftChangeClearsArtisan({ nextCraftId: "c2", artisanId: "", artisans: [] })).toBe(false);
  expect(
    craftChangeClearsArtisan({ nextCraftId: "", artisanId: "a", artisans: [artisan("a", "c1")] })
  ).toBe(false);
});

test("both record forms route the craft change through that one decision", () => {
  for (const file of ["ProductForm.tsx", "ToolForm.tsx"]) {
    const source = read("components", "forms", file);
    expect(source, `${file} must not re-derive the clear rule`).toContain("craftChangeClearsArtisan(");
    expect(source, `${file} still carries the old absence-is-the-wrong-craft test`).not.toContain(
      "!artisans.some((a) => a.id === artisanId && a.craftId === next)"
    );
    // The shared loader is what makes `known` reliable: it merges the craft's own roster and looks
    // the record's artisan up by id, so "absent" stops meaning "absent from page one".
    expect(source).toContain("useCraftAndArtisanOptions(");
    expect(source).toContain("<CappedListNotice");
  }
});

test("the artisan roster is asked for by craft rather than filtered out of the newest hundred", () => {
  const source = read("components", "forms", "recordPickers.ts");
  const request = between(source, 'listResource<Artisan>("/artisans", { craftId', "})");
  expect(request).toContain("LIST_PAGE_CEILING");
  // 749 artisans over 178 crafts: per-craft, one page is the whole answer in practice, and the cut
  // is still reported for the day some craft is that large.
  expect(source).toContain('listCut(result, "artisans of this craft")');
  // The record's own artisan is fetched by id, which is what makes the clear rule decidable at all.
  expect(source).toContain("useRecordOffPage");
});

test("every record picker in the web forms reports its cut", () => {
  // The finding named eleven call sites; these are the ones in components/forms. WorkshopSelect and
  // ArtisanForm also recover the record's own row by id, because a picker that cannot draw its own
  // current value invites the one action that really does change the record: picking something else.
  for (const file of ["ProductForm.tsx", "ToolForm.tsx", "ProcessForm.tsx", "ArtisanForm.tsx", "ToolAssignmentSection.tsx", "WorkshopSelect.tsx"]) {
    const source = read("components", "forms", file);
    expect(source, `${file} draws a capped picker with no notice`).toContain("<CappedListNotice");
    expect(source, `${file} still asks for a bare literal page size`).not.toContain("pageSize: 100");
  }
  for (const file of ["ArtisanForm.tsx", "WorkshopSelect.tsx", "ProcessForm.tsx"]) {
    expect(read("components", "forms", file), `${file} cannot draw its own current value`).toContain(
      "useRecordOffPage"
    );
  }
});

/**
 * THE SAME RULE, ON THE CRAFT PICKER OF ALL THREE FORMS — the sibling half that was missed.
 *
 * `useRecordOffPage` was added to `ArtisanForm`'s craft dropdown for a reason that is not specific to
 * artisans: `/crafts` is clamped to 100 rows and ordered NAME ASCENDING (deliberately — see the
 * ordering comment in `routes/crafts.py`), this database holds 178 crafts, so the cut is stable and
 * always falls in the same place. `ProductForm` and `ToolForm` draw the identical dropdown from the
 * identical list and did not get it: opening a product or a toolkit of a craft that sorts past the
 * cut showed `<option value="">Unlinked / type below</option>` selected — the browser's fallback
 * when `value` matches no option — beside a filled-in "Craft name" box. The record's link was fine;
 * the form said it was not, and the repair that suggests itself rewrites it.
 *
 * The shared hook covers the ARTISAN for those two forms, which is why the gap survived a review:
 * `useCraftAndArtisanOptions` calls `useRecordOffPage` for the artisan only. This test asks the
 * question per PICKER rather than per file, so the next form to grow a craft dropdown is caught.
 */
test("the craft picker in every record form can draw its own current value", () => {
  for (const file of ["ArtisanForm.tsx", "ProductForm.tsx", "ToolForm.tsx"]) {
    const code = codeOf(read("components", "forms", file));

    // The by-id recovery itself, spelled the same way in all three — reuse of ArtisanForm's hook,
    // not a variant of it. A variant is how this came to be missing from two forms out of three.
    expect(code, `${file}'s craft picker cannot draw a craft that is off page one`).toContain(
      'useRecordOffPage<Craft>("/crafts", craftId, crafts)'
    );
    expect(code, `${file} fetches the off-page craft and then does not offer it`).toContain(
      "mergeById(crafts, [offPageCraft])"
    );

    // And the dropdown is actually built from the merged list. Fetching the craft and then mapping
    // the unmerged page would leave the screen exactly as wrong as before.
    const select = between(code, 'name="craftId"', "</Select>");
    expect(select, `${file}'s craft <Select> must map craftOptions`).toContain("craftOptions.map(");
    expect(select, `${file}'s craft <Select> still maps the raw page`).not.toContain("crafts.map(");

    // The carried craft is judged against the same merged list: "not on page one" is not "you can no
    // longer reach it", and pruning on it drops a good link out of the carry bag.
    expect(between(code, 'carryScope("craft"', ")"), `${file} prunes a carried craft off the raw page`).toContain(
      "craftOptions"
    );
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * Finding 4 — View Data's "Browse by type"
 * ──────────────────────────────────────────────────────────────────────────── */

test("View Data's browse panel has a pager, a total and a notice", () => {
  const source = read("app", "(protected)", "data", "page.tsx");

  // `load` had no page parameter to give it — `() => Promise<BrowseRow[]>` — so there was nothing to
  // drive a pager with even if one had been drawn.
  expect(source).toContain("load: (page: number) => Promise<BrowsePage>");
  expect(source, "the envelope must survive the mapping").toContain("function browsePage<T>(");
  expect(source, "no pager was the finding").toContain("<Pagination");
  // This is the one site allowed to say "use the pager", so it is the one site that passes it.
  expect(source).toContain('reach="pager"');
  // 2530 media files and 749 artisans behind this panel, at a 100-row page.
  expect(source).not.toContain("pageSize: 100");
});

test("changing the record type restarts at page one without closing the open record", () => {
  const source = read("app", "(protected)", "data", "page.tsx");
  // Two effects on purpose: resetting on every PAGE change would close the record a researcher had
  // just opened, and not resetting on a TYPE change would ask /crafts for page 14 of the media list.
  expect(between(source, "setPage(1);", "}, [typeKey]);")).toContain("setSelected(null)");
  expect(source).toContain("}, [typeKey, page]);");

  /*
    THE SECOND HALF OF THIS TEST'S OWN TITLE, which the two assertions above do not reach and never
    did. They pin the TYPE-change effect; "without closing the open record" is a claim about a PAGE
    change, and while that claim was false — the card was re-derived from the loaded page with
    `rows?.find(...)`, and the list loader cleared `recordMedia` on every fetch — this test passed.
    A green test standing over a live defect is read as cover for it, so the rule is asserted here
    rather than only described in the title, and in full in `browse-page-turn-unit.spec.ts`.
  */
  const code = codeOf(source);
  expect(code, "the open record must be held, not looked up in whichever page is loaded").toContain(
    'const recordId = selected?.id ?? ""'
  );
  expect(code, "recovering the card from the loaded page IS closing it on a page turn").not.toContain("rows?.find(");
  expect(
    between(code, "setBrowse(null)", "[typeKey, page]"),
    "clearing the record's media in the LIST loader empties the open record on a page turn"
  ).not.toContain("setRecordMedia(");
});

/* ────────────────────────────────────────────────────────────────────────────
 * Finding 5 — the /media upload form's linked-record picker
 * ──────────────────────────────────────────────────────────────────────────── */

test("the media upload form says how much of the record type its entry picker holds", () => {
  const source = read("app", "(protected)", "media", "page.tsx");

  // This one decides what a NEWLY UPLOADED file is attached to, and the upload button requires only
  // `linkedType` — so an unreachable record means the batch lands attached to nothing, does not show
  // in that record's "Previously uploaded media", and has to be repaired through the relink route.
  expect(source).toContain("Promise<EntryOptions>");
  expect(source).toContain("cut: ListCut | null");
  expect(source, "the ComboBox filters only what it was handed").toContain("<CappedListNotice");
  expect(source).toContain("const params = { pageSize: LIST_PAGE_CEILING };");

  // All eight branches must carry the total, not just the one somebody happened to look at.
  const loader = between(source, "async function loadEntryOptions", "\n}\n");
  const counted = loader.match(/cut: listCut\(page, /g) ?? [];
  expect(counted.length, "one branch per linkable record type").toBe(8);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The ceiling itself
 * ──────────────────────────────────────────────────────────────────────────── */

test("the ceiling is the server's number, named once", () => {
  // `normalize_pagination` does `min(page_size, MAX_PAGE_SIZE)` with `MAX_PAGE_SIZE = 100`, and every
  // list route also declares `pageSize: int = Query(20, ge=1, le=100)`. Asking for more is refused,
  // not honoured — which is why the answer to this defect is a sentence and a scoped query rather
  // than a bigger number.
  expect(LIST_PAGE_CEILING).toBe(100);
  const cut: ListCut | null = listCut(page(LIST_PAGE_CEILING, 2530, (i) => ({ id: String(i) })), "media files");
  expect(cut?.loaded).toBe(LIST_PAGE_CEILING);
});
