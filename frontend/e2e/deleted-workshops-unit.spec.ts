import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DELETED_WORKSHOPS_PAGE_SIZE,
  deletedByLabel,
  deletedWorkshopsCut,
  deletedWorkshopsNotice,
  restoredNotice,
  strandedPageSentence
} from "@/components/admin/deletedWorkshops";
import type { DwSummary } from "@/lib/designWorkshops";
import type { PageResult } from "@/lib/types";

/**
 * A SAFETY NET NOBODY COULD REACH — the trash card, and the sentences that keep it honest.
 *
 * `DELETE /design-workshops/{id}` is a soft delete: the row and all 22 stage entries stay, only
 * `deletedAt` is set. `POST /{id}/restore` undoes it, admin only. `restoreDesignWorkshop` has been a
 * typed client function in `lib/designWorkshops.ts` for as long as the endpoint has existed and had
 * **no caller anywhere in `frontend/`** — because nothing on any surface would list a deleted
 * workshop, so the only admin who could restore one was an admin who had written the id down before
 * deleting it. Meanwhile the delete confirmation promised, in so many words, that an admin could
 * restore it.
 *
 * WHY A NODE SPEC, PARTLY PURE AND PARTLY A SOURCE READ. Two of the states below cannot be produced
 * by any live database — a page past the end of the trash, a deleter whose account has since been
 * closed — and this repository has no React renderer in its devDependencies, so a decision written
 * inside JSX is only ever exercised by somebody looking at a screen. The pure half is tested by
 * CALLING it; the half that lives in request parameters and render branches is read out of the
 * source, exactly as `capped-lists-unit.spec.ts` reads its own.
 *
 * WHAT IT DOES NOT PROVE: that a browser paints any of it, or that the server honours `deletedOnly`.
 * The second is `backend/tests/test_workshop_trash_listing.py`, which asserts the `where` that would
 * reach Prisma and the 403 above it.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

/**
 * The source with its comments removed.
 *
 * Every assertion below that looks for a call would otherwise be satisfiable by a sentence of prose
 * ABOUT that call — and this feature's files are heavily commented, including with the very
 * identifiers under test. A test that a comment can satisfy is a test that survives the deletion of
 * the code it guards.
 */
function code(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

/** A page of the trash as the route answers one. */
function page(loaded: number, total: number, pageNumber = 1): PageResult<DwSummary> {
  return {
    items: Array.from({ length: loaded }, (_, index) => ({ id: `w${index}` }) as DwSummary),
    total,
    page: pageNumber,
    pageSize: DELETED_WORKSHOPS_PAGE_SIZE,
    pages: total ? Math.ceil(total / DELETED_WORKSHOPS_PAGE_SIZE) : 0
  };
}

// --------------------------------------------------------------------------------------
// 1. The cut sentence — rule 10, on the one list where an unstated cut is worst
// --------------------------------------------------------------------------------------

test("a complete page of the trash says nothing about pagination", () => {
  expect(deletedWorkshopsNotice(deletedWorkshopsCut(page(3, 3)))).toBe("");
  expect(deletedWorkshopsNotice(null)).toBe("");
});

test("a cut page prints both numbers and points at the pager", () => {
  const notice = deletedWorkshopsNotice(deletedWorkshopsCut(page(20, 51)));
  expect(notice).toContain("Showing 20 of 51 deleted workshops");
  // The count of what is NOT shown, spelled out. "Showing 20 of 51" alone leaves the reader doing
  // the subtraction to find out whether one workshop is missing from the page or thirty-one are.
  expect(notice).toContain("31");
  expect(notice).toContain("pages below");
});

test("the sentence never names a search box, because this card has none", () => {
  // `cappedListNotice(cut, "pager")` ends "…which are not searched by the box above" and would have
  // been the obvious reuse. Naming a control that is not on screen is the defect that module's own
  // header warns about; only the arithmetic is shared.
  expect(deletedWorkshopsNotice(deletedWorkshopsCut(page(20, 51)))).not.toContain("search");
});

test("a page past the end of the trash says the trash is not empty", () => {
  // Reachable: delete two workshops, open page 2, restore both from another tab. Without this arm
  // the card draws "Nothing has been deleted" over a trash that holds rows — an absence reading as
  // a fact, which is the whole reason this panel exists.
  const notice = deletedWorkshopsNotice(deletedWorkshopsCut(page(0, 40, 3)));
  expect(notice).toContain("the trash is not empty");
  expect(notice).toContain("40");
});

test("a server that sends no total makes the card claim nothing", () => {
  // `apiFetch` casts and validates no schema, so a missing key arrives as undefined. The honest
  // answer is silence, never "Showing 20 of NaN".
  const broken = { ...page(20, 51), total: undefined as unknown as number };
  expect(deletedWorkshopsNotice(deletedWorkshopsCut(broken))).toBe("");
});

// --------------------------------------------------------------------------------------
// 2. Who deleted it — three facts, three sentences, never a blank cell
// --------------------------------------------------------------------------------------

test("a resolved name is printed as itself", () => {
  expect(deletedByLabel({ deletedById: "u1", deletedByName: "Priya" })).toBe("Priya");
});

test("an id with no name says the account is gone, and never guesses at a person", () => {
  // `deletedById` is onDelete: SetNull against User, so the pointer outlives the account. Naming the
  // workshop's creator — the tempting fallback — puts somebody's name against a deletion they did
  // not perform, on the one screen whose purpose is undoing it.
  const label = deletedByLabel({ deletedById: "u-departed", deletedByName: null });
  expect(label).toBe("An account no longer on record");
});

test("no pointer at all reads as not recorded, which is a different fact again", () => {
  expect(deletedByLabel({ deletedById: null, deletedByName: null })).toBe("Not recorded");
  expect(deletedByLabel({})).toBe("Not recorded");
});

// --------------------------------------------------------------------------------------
// 3. The restore notice names what came back
// --------------------------------------------------------------------------------------

test("the restore notice names the workshop, because its row leaves the table", () => {
  expect(restoredNotice("Ikat cluster, Nuapatna")).toContain("Ikat cluster, Nuapatna");
  expect(restoredNotice("Ikat cluster, Nuapatna")).toContain("back on the design workshops list");
});

test("an untitled workshop is a real state and gets words rather than empty quotes", () => {
  // A workshop created and deleted before stage 1 was saved has no title at all.
  expect(restoredNotice("   ")).toBe("That workshop is restored, and is back on the design workshops list.");
});

// --------------------------------------------------------------------------------------
// 4. The wiring, read out of the source
// --------------------------------------------------------------------------------------

test("the client sends both trash parameters, spelled out as literals", () => {
  // `buildQuery` takes no booleans and drops "" as it drops null, so a bare `params.deletedOnly`
  // would be dropped on the floor and the card would silently list LIVE workshops with a Restore
  // button beside each one.
  const source = code(read("lib", "designWorkshops.ts"));
  expect(source).toContain(`deletedOnly: params.deletedOnly ? "true" : undefined`);
  expect(source).toContain(`includeDeleted: params.includeDeleted ? "true" : undefined`);
});

test("restoreDesignWorkshop has a caller — the defect this whole lane closes", () => {
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  expect(card).toContain("restoreDesignWorkshop");
  expect(card).toContain("await restoreDesignWorkshop(");
});

test("the card asks for the trash and only the trash", () => {
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  expect(card).toContain("deletedOnly: true");
  expect(card).toContain("pageSize: DELETED_WORKSHOPS_PAGE_SIZE");
});

test("a failed load never falls through to the empty state", () => {
  // Three branches, in this order: no result yet (loading OR failed), no rows (genuinely empty),
  // rows. Collapsing the first into the second is how a dropped connection reports a full trash as
  // an empty one — this repository's most repeated bug class, on a list of deleted work.
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  // ANCHORED ON THE JSX BRANCHES THEMSELVES, not on the bare expressions: `result.items.length === 0`
  // is also a conjunct of the `strandedPage` const, which is computed above the return, so a plain
  // `indexOf` would compare the wrong two positions and pass or fail for a reason nobody meant.
  const guard = card.indexOf("{!result ? (");
  const empty = card.indexOf(") : result.items.length === 0 ? (");
  expect(guard, "the card no longer distinguishes 'not loaded' from 'nothing here'").toBeGreaterThan(-1);
  expect(empty, "the no-rows branch is no longer the second of the three").toBeGreaterThan(guard);
  // And a first load that failed must SAY it failed rather than sitting on "Loading…" forever.
  expect(card).toContain("error ?");
});

test("an EMPTY PAGE of a non-empty trash is never drawn as an empty trash", () => {
  /*
    The state `deletedWorkshopsNotice` writes its first arm for — "None of the 51 deleted workshops
    could be listed on this page — the trash is not empty" — was being rendered with "Nothing has
    been deleted" directly under it, because the render branched on `items.length === 0` alone and
    total played no part. Two sentences on one screen, flatly contradicting each other, and the one
    a reader believes is the big centred heading. `strandedPage` is the missing half of that test.
  */
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  expect(card).toContain(
    "const strandedPage = result !== null && result.items.length === 0 && result.total > 0;"
  );
  const branch = card.indexOf("{strandedPage ? (");
  const emptyHeading = card.indexOf("Nothing has been deleted");
  expect(branch, "the empty branch no longer separates a page past the end from an empty trash").toBeGreaterThan(-1);
  expect(emptyHeading, "the empty-state heading is not inside the non-stranded arm").toBeGreaterThan(branch);
});

test("past the end of the trash, the sentence points at the pager and counts what is behind it", () => {
  expect(strandedPageSentence(51, 4)).toBe(
    "51 deleted workshops are still on record — on earlier pages. Use the pager below to reach them."
  );
  // One is a real page of the trash, not a rounding case: delete one workshop, page past it.
  expect(strandedPageSentence(1, 2)).toBe(
    "One deleted workshop is still on record — on earlier pages. Use the pager below to reach it."
  );
});

test("on page ONE it never names the pager, because the pager cannot help there", () => {
  /*
    Page 1 with a total and no rows is a RACE, not a page past the end: `list_design_workshops`
    gathers its `count` and its `find_many` concurrently, so a colleague restoring the last rows
    between the two answers a total with nothing to put under it. Telling that reader to use the
    pager is the "name a control that cannot do it" defect `deletedWorkshopsNotice` avoids one
    function up — Previous is disabled on page 1 and Next leads further from the rows, not nearer.
  */
  const first = strandedPageSentence(3, 1);
  expect(first).toBe("3 deleted workshops are still on record, but none came back on this request. Reload the page to see them.");
  expect(first).not.toContain("pager");
  expect(strandedPageSentence(1, 1)).toContain("Reload the page to see it.");
});

test("the pager is drawn from the TOTAL, so a page past the end still has a way back", () => {
  /*
    Keyed to the rows on screen, the pager disappeared from exactly the page that needs it. The page
    number is this component's state and not a URL parameter, so an admin stranded there had no
    control of any kind: no Previous, nothing to edit, no link to press — only a reload, which comes
    back to the same page. A genuinely empty trash still shows no pager, because `total` is 0.
  */
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  expect(card).toContain("{result && result.total > 0 ? (");
  expect(card, "the pager is keyed to the rows on screen again").not.toContain("result && result.items.length > 0 ?");
});

test("the card keeps the rows it has when a later load fails", () => {
  // `setResult(null)` in the catch would blank a full table on one dropped request.
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  expect(card).not.toContain("setResult(null)");
});

test("the admin hub mounts the card and offers a tile that reaches it", () => {
  const hub = code(read("app", "(protected)", "admin", "page.tsx"));
  expect(hub).toContain("<DeletedWorkshopsCard />");
  expect(hub).toContain(`href: "#deleted-workshops"`);
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  expect(card, "the tile's anchor does not exist on the page it links to").toContain(
    `id="deleted-workshops"`
  );
});

test("the delete confirmation names the screen it promises", () => {
  // "An admin can restore it" was true of the API and unreachable in the product for as long as
  // nothing listed a deleted workshop. The sentence now says where.
  const list = read("app", "(protected)", "design-workshops", "page.tsx");
  expect(list).toContain("Deleted workshops");
});
