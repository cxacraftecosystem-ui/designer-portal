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

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function code(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

function page(loaded: number, total: number, pageNumber = 1): PageResult<DwSummary> {
  return {
    items: Array.from({ length: loaded }, (_, index) => ({ id: `w${index}` }) as DwSummary),
    total,
    page: pageNumber,
    pageSize: DELETED_WORKSHOPS_PAGE_SIZE,
    pages: total ? Math.ceil(total / DELETED_WORKSHOPS_PAGE_SIZE) : 0
  };
}

test("a complete page of the trash says nothing about pagination", () => {
  expect(deletedWorkshopsNotice(deletedWorkshopsCut(page(3, 3)))).toBe("");
  expect(deletedWorkshopsNotice(null)).toBe("");
});

test("a cut page prints both numbers and points at the pager", () => {
  const notice = deletedWorkshopsNotice(deletedWorkshopsCut(page(20, 51)));
  expect(notice).toContain("Showing 20 of 51 deleted workshops");
  expect(notice).toContain("31");
  expect(notice).toContain("pages below");
});

test("the sentence never names a search box, because this card has none", () => {
  expect(deletedWorkshopsNotice(deletedWorkshopsCut(page(20, 51)))).not.toContain("search");
});

test("a page past the end of the trash says the trash is not empty", () => {
  const notice = deletedWorkshopsNotice(deletedWorkshopsCut(page(0, 40, 3)));
  expect(notice).toContain("the trash is not empty");
  expect(notice).toContain("40");
});

test("a server that sends no total makes the card claim nothing", () => {
  const broken = { ...page(20, 51), total: undefined as unknown as number };
  expect(deletedWorkshopsNotice(deletedWorkshopsCut(broken))).toBe("");
});

test("a resolved name is printed as itself", () => {
  expect(deletedByLabel({ deletedById: "u1", deletedByName: "Priya" })).toBe("Priya");
});

test("an id with no name says the account is gone, and never guesses at a person", () => {
  const label = deletedByLabel({ deletedById: "u-departed", deletedByName: null });
  expect(label).toBe("An account no longer on record");
});

test("no pointer at all reads as not recorded, which is a different fact again", () => {
  expect(deletedByLabel({ deletedById: null, deletedByName: null })).toBe("Not recorded");
  expect(deletedByLabel({})).toBe("Not recorded");
});

test("the restore notice names the workshop, because its row leaves the table", () => {
  expect(restoredNotice("Ikat cluster, Nuapatna")).toContain("Ikat cluster, Nuapatna");
  expect(restoredNotice("Ikat cluster, Nuapatna")).toContain("back on the design workshops list");
});

test("an untitled workshop is a real state and gets words rather than empty quotes", () => {
  expect(restoredNotice("   ")).toBe("That workshop is restored, and is back on the design workshops list.");
});

test("the client sends both trash parameters, spelled out as literals", () => {
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
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  const guard = card.indexOf("{!result ? (");
  const empty = card.indexOf(") : result.items.length === 0 ? (");
  expect(guard, "the card no longer distinguishes 'not loaded' from 'nothing here'").toBeGreaterThan(-1);
  expect(empty, "the no-rows branch is no longer the second of the three").toBeGreaterThan(guard);
  expect(card).toContain("error ?");
});

test("an EMPTY PAGE of a non-empty trash is never drawn as an empty trash", () => {
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
  expect(strandedPageSentence(1, 2)).toBe(
    "One deleted workshop is still on record — on earlier pages. Use the pager below to reach it."
  );
});

test("on page ONE it never names the pager, because the pager cannot help there", () => {
  const first = strandedPageSentence(3, 1);
  expect(first).toBe("3 deleted workshops are still on record, but none could be loaded. Reload the page to see them.");
  expect(first).not.toContain("pager");
  expect(strandedPageSentence(1, 1)).toContain("Reload the page to see it.");
});

test("the pager is drawn from the TOTAL, so a page past the end still has a way back", () => {
  const card = code(read("components", "admin", "DeletedWorkshopsCard.tsx"));
  expect(card).toContain("{result && result.total > 0 ? (");
  expect(card, "the pager is keyed to the rows on screen again").not.toContain("result && result.items.length > 0 ?");
});

test("the card keeps the rows it has when a later load fails", () => {
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
  const list = read("app", "(protected)", "design-workshops", "page.tsx");
  expect(list).toContain("Deleted workshops");
});
