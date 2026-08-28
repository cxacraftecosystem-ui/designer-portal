import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  MEDIA_PICKER_PAGE_SIZE,
  acceptRepositoryPicks,
  capCeilingClause,
  mediaPickerNoun,
  mediaPickerNotice,
  mediaPickerOptions,
  mediaPickerTypeFilter,
  repositoryEntitlementNotice,
  repositoryRefusalSentence
} from "@/components/media/mediaPicker";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import type { MediaFile, MediaType } from "@/lib/types";

/**
 * THE WEB MEDIA PICKER — choosing a file the repository ALREADY HOLDS as a field's value.
 *
 * Before it, a designer could only ATTACH A NEW UPLOAD, so one loom photographed once could not be
 * pointed at from a second record: the way to get it there was to upload it again, which is two
 * objects, two sets of bytes and nothing anywhere saying they are the same picture.
 *
 * WHAT THESE ASSERTIONS ARE FOR. Every judgement below is one of the four ways a picker of this shape
 * misleads a designer, and three of the four have shipped as bugs elsewhere in this app:
 *
 *  · a list the server truncated, presented as the whole repository (rule 10);
 *  · a list the server NARROWED, presented as the whole repository;
 *  · a ceiling that quietly kept the first N of a selection — the failure `coerce_value` refuses
 *    outright on the server, where there is nobody to tell;
 *  · a row whose `url` was withheld by entitlement, read as a broken or missing file rather than as
 *    the answer it is.
 *
 * They are called rather than clicked because there is no React renderer in this repository's
 * devDependencies — Playwright is the whole of it — which is why the judgements live in
 * `components/media/mediaPicker.ts` and the JSX lives in `MediaRepositoryPicker.tsx`. The handful of
 * assertions at the bottom are SOURCE READS over `FieldInput.tsx`, the same technique
 * `existing-media-count-unit.spec.ts` and `derived-fields-unit.spec.ts` use for the same reason: they
 * cannot prove the browser paints anything, only that the wiring feeding it is the intended one.
 */

const ROW = (over: Partial<MediaFile> = {}): MediaFile => ({
  id: "med_1",
  originalFilename: "loom.jpg",
  mediaType: "IMAGE",
  mimeType: "image/jpeg",
  sizeBytes: 12345,
  objectKey: "media/loom.jpg",
  url: "https://example.invalid/loom.jpg",
  status: "APPROVED",
  createdAt: "2026-08-20T09:30:00.000Z",
  ...over
});

/* ────────────────────────────────────────────────────────────────────────────
 * The page size, which is a render cap and not the endpoint's ceiling
 * ──────────────────────────────────────────────────────────────────────────── */

test("the picker asks for RENDER_CAP rows, not the 100 GET /media will serve", () => {
  // `pageSize` on that route is `Query(20, ge=1, le=100)`, so 100 is available and is the wrong
  // number: `SearchableSelect` draws at most `RENDER_CAP` and prints its own "Showing the first 80 of
  // 100" footer, so a hundred rows produce TWO truncation sentences with TWO different totals, one
  // above the other, and nothing at all is said about the band between 81 and 100. `/design-review`
  // shipped exactly that.
  expect(MEDIA_PICKER_PAGE_SIZE).toBe(RENDER_CAP);
  expect(MEDIA_PICKER_PAGE_SIZE).toBeLessThan(100);
});

/* ────────────────────────────────────────────────────────────────────────────
 * What the list is narrowed to
 * ──────────────────────────────────────────────────────────────────────────── */

test("the narrowing is derived from the capture card's own allowedTypes", () => {
  // The argument is `ALLOWED_TYPES[field.type]` verbatim, so the two halves of one media field cannot
  // come to disagree about what it accepts.
  expect(mediaPickerTypeFilter(["IMAGE"])).toBe("IMAGE");
  expect(mediaPickerTypeFilter(["AUDIO"])).toBe("AUDIO");
  expect(mediaPickerTypeFilter(["VIDEO"])).toBe("VIDEO");
});

test("no list, and more than one kind, both mean ask for everything", () => {
  // A FILE field passes no `allowedTypes` at all — its chooser offers every kind of attachment and
  // nothing is filtered out of it — so the picker must not narrow either.
  expect(mediaPickerTypeFilter(undefined)).toBeNull();
  expect(mediaPickerTypeFilter([])).toBeNull();
  // AND THE ARM THAT MATTERS: `list_media` takes ONE `mediaType` string, not a list. A field
  // accepting two kinds would have to pick one, and the other kind's files would be silently absent
  // from a list that looks complete — the second of the four failures this module is written against.
  // No registry field declares two today, so this is unreachable and correct rather than dead: it is
  // what stops the next such field shipping a half-list.
  expect(mediaPickerTypeFilter(["IMAGE", "VIDEO"] as MediaType[])).toBeNull();
});

test("the noun follows the narrowing, in both numbers", () => {
  expect(mediaPickerNoun("IMAGE")).toEqual({ singular: "photograph", plural: "photographs" });
  expect(mediaPickerNoun("AUDIO")).toEqual({ singular: "audio recording", plural: "audio recordings" });
  // OTHER is the endpoint's own did-not-classify bucket; naming it in copy would invent a category
  // for the reader, so it and the unnarrowed list are both plain "file".
  expect(mediaPickerNoun("OTHER")).toEqual({ singular: "file", plural: "files" });
  expect(mediaPickerNoun(null)).toEqual({ singular: "file", plural: "files" });
});

/* ────────────────────────────────────────────────────────────────────────────
 * The rows
 * ──────────────────────────────────────────────────────────────────────────── */

test("a row already attached is listed and disabled, never hidden", () => {
  const options = mediaPickerOptions({
    rows: [ROW({ id: "a" }), ROW({ id: "b", originalFilename: "warp.jpg" })],
    attachedIds: ["a"]
  });
  // HIDING IT would mean a designer who knows the photograph is in the repository types its name,
  // finds nothing, and concludes it is not there — absence reading as non-existence over a file they
  // attached themselves five minutes ago.
  expect(options.map((option) => option.value)).toEqual(["a", "b"]);
  expect(options[0].disabled).toBe(true);
  expect(options[0].hint).toContain("already attached");
  expect(options[1].disabled).toBeFalsy();
  // `SearchableMultiSelect`'s "select all matching" skips disabled rows, so a bulk tick cannot
  // smuggle a duplicate id into the value either.
});

test("the label is the caption where there is one, and the filename otherwise", () => {
  const options = mediaPickerOptions({
    rows: [ROW({ id: "a", caption: "Pit loom, Bagru" }), ROW({ id: "b", caption: "   " })],
    attachedIds: []
  });
  // The row in the panel and the row in the field then read as the same file — `ExistingMedia` makes
  // the same choice for the same reason. A whitespace-only caption is not a caption.
  expect(options[0].label).toBe("Pit loom, Bagru");
  expect(options[1].label).toBe("loom.jpg");
});

test("a row whose url was withheld says so, and is still pickable", () => {
  const options = mediaPickerOptions({ rows: [ROW({ id: "a", url: null })], attachedIds: [] });
  expect(options[0].hint).toContain("stored, but this account may not open the file itself");
  // NOT DISABLED. What is stored is a media id and who may open the bytes is decided every time the
  // row is read, so refusing it here would be this client inventing a rule the server does not have —
  // and the commonest reason to reach for this picker at all is a colleague's photograph.
  expect(options[0].disabled).toBeFalsy();
});

/* ────────────────────────────────────────────────────────────────────────────
 * What the list SAYS about itself
 * ──────────────────────────────────────────────────────────────────────────── */

const NOTICE = (over: Partial<Parameters<typeof mediaPickerNotice>[0]> = {}) =>
  mediaPickerNotice({
    loading: false,
    problem: null,
    query: "",
    shown: 80,
    total: 80,
    typeFilter: "IMAGE",
    ...over
  });

test("a truncated list names both figures and the box that reaches the rest", () => {
  const notice = NOTICE({ shown: 80, total: 214 });
  expect(notice).toContain("80");
  expect(notice).toContain("214");
  // THE INSTRUCTION MUST NAME THE SERVER BOX. `searchable={false}` does not switch the render cap
  // off, and the default last clause ("Keep typing to narrow the list") would point a reader at a
  // filter box the panel deliberately does not have — §11.5.
  expect(notice).toContain("search box above");
  expect(notice).toContain("every photograph you may read");
  expect(notice).not.toContain("Keep typing");
});

test("a complete list says so rather than borrowing the truncation sentence", () => {
  expect(NOTICE({ shown: 12, total: 12 })).toContain("All 12 photographs you may read are listed.");
  expect(NOTICE({ shown: 1, total: 1 })).toContain("The one photograph you may read is listed.");
});

test("a search in flight does not read as an empty repository", () => {
  // Answered BEFORE the empty cases: on a village connection the request takes the several seconds a
  // designer would otherwise spend deciding the feature does not work.
  expect(NOTICE({ loading: true, shown: 0, total: 0 })).toBe(
    "Searching the repository for photographs…"
  );
});

test("a search in flight over the PREVIOUS answer says which question the rows answer", () => {
  // The worse half of the same defect: the reader has just typed a filename, the rows under the box
  // are the answer to something else, and a sentence reconciling them to the new query is the "No
  // matches" lie one step removed. The rows stay — blanking them per keystroke is its own flicker —
  // and the sentence moves.
  const notice = NOTICE({ loading: true, shown: 12, total: 12, query: "warp" });
  expect(notice).toContain("Searching the repository…");
  expect(notice).toContain("the 12 photographs listed below are the answer to the previous search");
  expect(notice).not.toContain("All 12");
});

test("nothing matched and nothing exists are two different sentences", () => {
  const matched = NOTICE({ query: "warp", shown: 0, total: 0 });
  const exists = NOTICE({ query: "", shown: 0, total: 0 });
  expect(matched).toContain("warp");
  // The claim is about the QUERY, and it says the search reached past this page — otherwise "no
  // matches" is exactly the lie a client-side filter over a truncated list tells.
  expect(matched).toContain("asks the repository, not this page");
  // AND THE EMPTY CASE IS A CLAIM ABOUT THIS ACCOUNT'S VIEW, never about the repository:
  // `list_media` composes `viewable_where`, so a flat "there is none" is how a reader concludes a
  // colleague never uploaded the photograph they are looking at on their own screen.
  expect(exists).toBe("The repository holds no photograph you may read yet.");
  expect(exists).not.toContain("matches");
});

test("a failed search is reported instead of an empty list", () => {
  // The rows already on screen are kept by the component; the sentence is what changes. An empty
  // picker over a failed request is the silent-emptiness class this repository keeps hitting.
  expect(NOTICE({ problem: "The repository could not be searched: offline", shown: 0, total: 0 })).toBe(
    "The repository could not be searched: offline"
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * Entitlement — an answer, not a failure
 * ──────────────────────────────────────────────────────────────────────────── */

test("entitlement is silent when every row carries its url", () => {
  expect(repositoryEntitlementNotice([ROW({ id: "a" }), ROW({ id: "b" })])).toBeNull();
  expect(repositoryEntitlementNotice([])).toBeNull();
});

test("withheld urls are counted and explained without discouraging the pick", () => {
  const one = repositoryEntitlementNotice([ROW({ id: "a", url: null }), ROW({ id: "b" })]);
  expect(one).toContain("1 of the 2 listed is stored");
  expect(one).toContain("may not open the file itself");
  // The half that matters: it must not read as "do not pick this one".
  expect(one).toContain("can still be attached");
  expect(one).toContain("stores a media id");

  const many = repositoryEntitlementNotice([ROW({ id: "a", url: null }), ROW({ id: "b", url: undefined })]);
  expect(many).toContain("2 of the 2 listed are stored");
  expect(many).toContain("may not open the files themselves");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The ceiling — enforced unconditionally, printed only where declared
 * ──────────────────────────────────────────────────────────────────────────── */

test("a declared ceiling is named and reconciled", () => {
  expect(capCeilingClause({ label: "Traditional motif photographs", declaredCap: 20, accounted: 20 })).toBe(
    "Traditional motif photographs holds at most 20 files, and 20 are accounted for"
  );
  expect(capCeilingClause({ label: "Sanction order", declaredCap: 1, accounted: 1 })).toBe(
    "Sanction order holds at most 1 file, and 1 is accounted for"
  );
});

test("an UNDECLARED ceiling prints no number at all", () => {
  // THE CONTRACT, and the half both clients failed until 2026-08-26 in the other direction:
  // `field_to_dict` emits `maxItems` only for a field that declares one, so printing "up to 200"
  // would be this client naming a figure it did not read and the server may change — and a stated cap
  // that is not the enforced cap is worse than no sentence at all
  // (docs/DESIGN_WORKSHOP.md:229-232). Enforcement stays unconditional; only the PRINTING is gated.
  const clause = capCeilingClause({ label: "Process photographs", declaredCap: null, accounted: 200 });
  expect(clause).toBe("Process photographs is full");
  expect(clause).not.toMatch(/\d/);
});

test("the ceiling takes what fits and hands back the rest", () => {
  const picks = ["a", "b", "c", "d", "e"];
  expect(acceptRepositoryPicks({ picked: picks, room: 5 })).toEqual({ attach: picks, refused: [] });
  expect(acceptRepositoryPicks({ picked: picks, room: 2 })).toEqual({
    attach: ["a", "b"],
    refused: ["c", "d", "e"]
  });
  // A full field refuses everything rather than silently attaching nothing and saying nothing.
  expect(acceptRepositoryPicks({ picked: picks, room: 0 })).toEqual({ attach: [], refused: picks });
});

test("a value already over its ceiling refuses everything rather than slicing negative", () => {
  // Not padding: a draft written by an older client enforcing a larger cap really can arrive over the
  // current one, and `picked.slice(0, -3)` would quietly hand back most of the list as accepted.
  expect(acceptRepositoryPicks({ picked: ["a", "b"], room: -3 })).toEqual({
    attach: [],
    refused: ["a", "b"]
  });
});

test("the refusal names the files and tells the reader where they still are", () => {
  const sentence = repositoryRefusalSentence({
    label: "Traditional motif photographs",
    declaredCap: 20,
    accounted: 20,
    refusedNames: ["warp.jpg", "weft.jpg"]
  });
  // NAMES THE FILES: "only 20 photographs are allowed" tells a designer holding 25 nothing about
  // which five to deal with — the same rule `uploadMediaBatch`'s callers are under one step later.
  expect(sentence).toContain("warp.jpg, weft.jpg");
  expect(sentence).toContain("holds at most 20 files");
  // AND ITS LAST CLAUSE IS NOT THE CAPTURE CARD'S. A file the chooser turned away is gone from the
  // browser and must be found on disk again ("pick them again"); a refused repository pick is still
  // ticked in a panel on screen, so sending the reader hunting for it would be wrong.
  expect(sentence).toContain("press Attach again");
  // "the repository list" and not "the list above": the picker is a disclosure and this sentence
  // outlives it being closed, so "above" would name a control that is not on screen — the same
  // defect as a `capHint` pointing at an absent filter box.
  expect(sentence).toContain("still ticked in the repository list");
  expect(sentence).not.toContain("above");
  expect(sentence).not.toContain("pick them again");
});

test("an undeclared ceiling still refuses out loud, and still names the files", () => {
  const sentence = repositoryRefusalSentence({
    label: "Process photographs",
    declaredCap: null,
    accounted: 200,
    refusedNames: ["dye.jpg"]
  });
  // BOTH HALVES, NEITHER TRADED FOR THE OTHER: gating the notice on a declared cap while enforcing
  // 200 turns a loud refusal into a silent drop of the 201st file, which is the one outcome the rule
  // exists to prevent.
  expect(sentence).toContain("Process photographs is full");
  expect(sentence).toContain("dye.jpg");
  expect(sentence).not.toMatch(/\b200\b/);
  expect(repositoryRefusalSentence({ label: "x", declaredCap: 20, accounted: 1, refusedNames: [] })).toBeNull();
});

/* ────────────────────────────────────────────────────────────────────────────
 * The wiring — source reads, for the parts a called function cannot reach
 * ──────────────────────────────────────────────────────────────────────────── */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

/** The text between two markers, so an assertion cannot drift into a neighbouring block. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

test("the picker's request uses the shared page size and the server's own search", () => {
  const source = read("components", "media", "MediaRepositoryPicker.tsx");
  const request = between(source, 'listResource<MediaFile>("/media"', "})");
  expect(request).toContain("search: query.trim() || null");
  expect(request).toContain("pageSize: MEDIA_PICKER_PAGE_SIZE");
  // A literal here would be free to drift from the number that governs what the panel DRAWS.
  expect(request).not.toMatch(/pageSize:\s*\d/);
});

test("both panels turn their own filter box off and name the box that reaches the rest", () => {
  // The MARKUP only — the file's header argues the rule in prose and would otherwise be counted as a
  // third call site, which is exactly the kind of miscount a source read invites.
  const markup = between(read("components", "media", "MediaRepositoryPicker.tsx"), "if (disabled) return null;", "\n}");
  // Two panels — the gallery's multi-select and the single-valued field's single-select — and the
  // rule is the same for both: one box, over the whole table, wired to the server.
  expect(markup.match(/searchable=\{false\}/g)?.length).toBe(2);
  expect(markup.match(/capHint="Use the search box above/g)?.length).toBe(2);
});

test("FieldInput mounts the picker with the enforced ceiling and the printable one kept apart", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  const mount = between(source, "<MediaRepositoryPicker", "/>");
  // `room` is derived from `effectiveMaxItems` (200 where nothing is declared) and is what the picker
  // ENFORCES; `declaredCap` is `declaredMaxItems` (null where nothing is declared) and is the only
  // number anything may PRINT. Handing one figure for both jobs is the defect the pair exists to stop.
  expect(mount).toContain("room={room}");
  expect(mount).toContain("declaredCap={declaredCap}");
  // Derived from the capture card's own list rather than from a second copy of the mapping.
  expect(mount).toContain("typeFilter={mediaPickerTypeFilter(ALLOWED_TYPES[field.type])}");
  // And it is the SAME `allowedTypes` expression the capture card above it is handed.
  expect(source).toContain("allowedTypes={ALLOWED_TYPES[field.type]}");
});

test("a stored id is never cleared, and a withheld url is never drawn as a broken frame", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  // An IMAGE with no url falls through to the paperclip placeholder — the same drawing a PDF gets —
  // so a withheld photograph read as "this is not a picture" and a withheld document said nothing at
  // all. The chip is the worded state, and it is gated on a row that RESOLVED: `undefined` is still
  // in flight and `null` already has its own sentence.
  expect(source).toContain("{file && !file.url ? (");
  expect(source).toContain("Not openable by this account");
  // Nothing in the picker's commit path may drop an id because the bytes are not readable from here.
  const commit = between(source, "function attachExisting", "\n  }");
  expect(commit).not.toContain(".url");
});

test("the two media refusals share the ceiling clause and only the ceiling clause", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  const notice = between(source, "const refusalNotice = (() => {", "})();");
  // One copy of the declared/undeclared branch, because that branch is the contract rather than a
  // wording preference — two copies in one media field is how the two controls would come to state it
  // differently.
  expect(notice).toContain("capCeilingClause({ label: field.label, declaredCap: printableCeiling, accounted })");
  /*
    `printableCeiling` IS `declaredCap` RE-DERIVED, AND THE ASSERTION BELOW IS WHAT KEEPS IT SO.

    It is not read off `declaredCap` because `capCeilingClause` is imported: the React Compiler
    cannot see inside it, so it assumes the object handed over may be mutated and taints everything
    reachable from it — `declaredCap`, which is also a dependency of the `carousel` memo, so the memo
    became unpreservable and `react-hooks/preserve-manual-memoization` failed the lint pointing at a
    line thirty away from the cause. A fresh binding of the same expression breaks the chain.

    The risk that trade introduces is a SECOND definition of the printable ceiling drifting from the
    first, so the expression is pinned here character for character rather than the name alone.
  */
  expect(notice).toContain("const printableCeiling = multiple ? declaredMaxItems(field) : null;");
  expect(source).toContain("const declaredCap = multiple ? declaredMaxItems(field) : null;");
  expect(notice).not.toContain("holds at most");
  expect(notice).not.toContain("is full`");
  // The last clause still differs, and must: this one's files are gone from the browser.
  expect(notice).toContain("this list stays until you do");
});
