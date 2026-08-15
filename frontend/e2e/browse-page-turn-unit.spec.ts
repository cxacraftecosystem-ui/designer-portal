import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * A PAGE TURN IN "BROWSE BY TYPE" MUST NOT CLOSE THE RECORD SOMEBODY HAS OPEN.
 *
 * The panel had no pager at all: eight `load()` implementations asked for the 100-row ceiling, threw
 * `PageResult.total` away, and drew the result in a scroll box that simply ended — the first 100 of
 * 2530 media files on the screen whose entire purpose is "browse everything we hold". The fix gave
 * it a `page` state, a `Pagination`, a cut notice, and a reset effect so that changing the record
 * TYPE goes back to page 1. That reset effect's own comment states the rule this spec is named for:
 * clearing the open record on a page turn would close a record a researcher had just opened.
 *
 * The comment was right and the code did not keep it. `recordId` survived a page turn, but every
 * fact ABOUT the record was re-derived from the page underneath it — `rows?.find((row) => row.id ===
 * recordId)` — and the loader effect cleared `recordMedia` on the way. So a page turn emptied the
 * card, and because `recordId` was still set the table branch (`typeKey && !recordId`) stayed hidden
 * too: a panel showing nothing, with a record still named in the box above it.
 *
 * The remedy is structural rather than a rule to remember: the panel holds the ROW (`selected`) and
 * derives `recordId` from it, so there is nothing left for a page load to invalidate.
 *
 * WHY A SOURCE READ. What is being pinned is the shape of a component's state — "the open record is
 * not recomputed from the loaded page" — and there is no React renderer in this repository's
 * devDependencies (Playwright is the whole of it), so mounting `BrowseByTypePanel` is not available
 * at all; `capped-lists-unit.spec.ts` and `discarded-work-unit.spec.ts` read page sources the same
 * way and for the same reason. Every assertion below was run against the pre-fix file on 2026-08-16
 * and each of the four in the first two tests failed against it — see the bans, which name the exact
 * expressions that file contained. What this does NOT prove is that a browser paints the card; that
 * needs a signed-in run and a database with a second page in it.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

/**
 * Comments blanked (with spaces, so reported line numbers still match the file), because the bans
 * below name expressions that the house-style comments beside them are REQUIRED to quote — a
 * structural test that fails on the comment explaining the defect would be training the next author
 * to delete the explanation.
 */
function codeOf(source: string): string {
  const blanked = (match: string) => match.replace(/[^\n]/g, " ");
  return source.replace(/\/\*[\s\S]*?\*\//g, blanked).replace(/\/\/[^\n]*/g, blanked);
}

/** Marker-to-marker, deliberately newline-free markers so line endings cannot decide the result. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the panel been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

const PAGE = ["app", "(protected)", "data", "page.tsx"] as const;

test("the open record is held, not looked up in whichever page is loaded", () => {
  const code = codeOf(read(...PAGE));

  // The row itself is state. `recordId` is derived from it, so the two cannot disagree — the old
  // pair could, and the disagreement rendered an empty panel.
  expect(code).toContain("const [selected, setSelected] = useState<BrowseRow | null>(null)");
  expect(code).toContain('const recordId = selected?.id ?? ""');

  // THE EXPRESSION THIS FINDING IS ABOUT. `rows` is one page of at most LIST_PAGE_CEILING; a record
  // opened from page 1 is absent from page 2, so recovering the card from it is exactly "a page turn
  // closes the record".
  expect(code, "the open record must not be recovered from the loaded page").not.toContain("rows?.find(");
  // And there is no second copy of the selection to fall out of step with the first.
  expect(code, "recordId is derived from `selected`; a setter for it would be a second source of truth").not.toContain(
    "setRecordId("
  );
});

test("a page load does not empty the open record's media", () => {
  const code = codeOf(read(...PAGE));
  const loader = between(code, "setBrowse(null)", "[typeKey, page]");

  // `setRecordMedia(null)` sat here and ran on every page turn, blanking "Media linked to this
  // record" for a record the page turn had nothing to do with. The media effect below clears its own
  // state when `recordId` changes — including to "" when the record type changes — so this line was
  // never doing any work that the record's own effect was not already doing correctly.
  expect(loader, "clearing the record's media in the LIST loader closes the record on a page turn").not.toContain(
    "setRecordMedia("
  );
  // The loader still does the one thing it must: drop the previous page so a fetch in flight never
  // renders as a repository with nothing in it.
  expect(loader).toContain("setBrowse(null)");
});

test("changing the record TYPE still goes back to page 1 and closes the record", () => {
  const code = codeOf(read(...PAGE));
  const reset = between(code, "setPage(1)", "[typeKey]");

  // The other half of the same rule, and the half that must not be lost while fixing the first: an
  // artisan is not a row of the media table, so switching type closes the card and starts again at
  // page 1 — otherwise page 14 of Media is requested of a 178-row Crafts list.
  expect(reset).toContain("setSelected(null)");
});

test("the pagination the reset effect was added for is still wired", () => {
  const source = read(...PAGE);
  const code = codeOf(source);

  // The finding this whole change closed: no pager, no count, `total` discarded in all eight
  // loaders. If a later edit removes any of these the record-survives-a-page-turn rule above is
  // still satisfied — vacuously, because there would be no page to turn. So they are pinned here.
  expect(code).toContain(".load(page)");
  expect(code).toContain("[typeKey, page]");
  expect(code).toContain("onPage={setPage}");
  expect(code).toContain("<Pagination");
  // The sentence beside the pager, which is the half that says what the filter box can and cannot
  // see. `reach="pager"` is only honest where there IS a pager.
  expect(code).toContain('reach="pager"');
});
