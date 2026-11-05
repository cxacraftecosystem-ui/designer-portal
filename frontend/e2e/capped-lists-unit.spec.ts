import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  cappedListNotice,
  cutOf,
  LIST_PAGE_CEILING,
  listCut,
  mergeById,
  queuePageNotice,
  type ListCut
} from "@/components/data/cappedList";
import { craftChangeClearsArtisan, craftsChangeClearsArtisans } from "@/components/forms/recordPickers";
import type { Artisan, PageResult } from "@/lib/types";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

function codeOf(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

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

test("a complete list says nothing at all", () => {
  expect(listCut(page(20, 20, (i) => ({ id: String(i) })), "artisans")).toBeNull();
  expect(listCut(page(0, 0, (i) => ({ id: String(i) })), "artisans")).toBeNull();
  expect(cappedListNotice(null)).toBe("");
});

test("a list exactly as long as the ceiling is not assumed to be cut", () => {
  expect(listCut(page(100, 100, (i) => ({ id: String(i) })), "crafts")).toBeNull();
});

test("a cut list prints both numbers and says the search box cannot reach the rest", () => {
  const cut = listCut(page(100, 749, (i) => ({ id: String(i) })), "artisans");
  expect(cut).toEqual({ noun: "artisans", loaded: 100, total: 749 });

  const sentence = cappedListNotice(cut);
  expect(sentence).toContain("100");
  expect(sentence).toContain("749");
  expect(sentence).toContain("649");
  expect(sentence).toContain("typing here searches only the 100 shown");
  expect(sentence).not.toContain("pager");
});

test("where there IS a pager the sentence names it instead", () => {
  const sentence = cappedListNotice(cutOf(100, 2530, "media"), "pager");
  expect(sentence).toContain("use the pager");
  expect(sentence).toContain("2530");
  expect(sentence).toContain("not searched by the box above");
});

test("an answer with nothing in it is never told to narrow or to page", () => {
  const sentence = cappedListNotice(cutOf(0, 431, "artisans"));
  expect(sentence).toContain("431");
  expect(sentence).toContain("but they do exist");
  expect(sentence).not.toContain("typing here");
  expect(sentence).not.toContain("pager");
  expect(cappedListNotice(cutOf(0, 431, "artisans"), "pager")).toBe(
    cappedListNotice(cutOf(0, 431, "artisans"))
  );
});

test("a total the wire did not carry says nothing rather than claiming a cut of NaN", () => {
  const missing = { items: [{ id: "a" }], page: 1, pageSize: 100, pages: 1 } as unknown as PageResult<{ id: string }>;
  expect(listCut(missing, "artisans")).toBeNull();
});

test("the review queue's count line is a number and never an admission", () => {
  expect(queuePageNotice(50, 766, true)).toBe("Showing 50 of 766 waiting, newest first.");
  expect(queuePageNotice(50, 50, false)).toBe("");
  expect(queuePageNotice(50, undefined, undefined)).toBe("");
  expect(queuePageNotice(50, undefined, true)).toBe("Showing the newest 50 waiting.");
  for (const sentence of [queuePageNotice(50, 766, true), queuePageNotice(50, undefined, true)]) {
    expect(sentence).not.toMatch(/oldest|at most|limit|holds|cannot|not shown/);
  }
});

test("the review screen reads the envelope it used to throw away", () => {
  const source = read("app", "(protected)", "review", "page.tsx");

  expect(source).toContain("apiFetch<PendingEnvelope>");
  expect(source).toContain("<CappedListNotice");
  expect(source).toContain("queuePageNotice(items.length, queue.total, queue.more)");
  expect(source).toContain("Load older");
  expect(source).toContain("total={queue.total}");
  expect(source, "the length of a capped list is the one figure that is certainly wrong")
    .not.toContain("total={items.length}");
});

test("merging option pages adds rows and never removes one", () => {
  const first = [artisan("a", "c1"), artisan("b", "c1")];
  const merged = mergeById(first, [artisan("b", "c1"), artisan("z", "c2")]);
  expect(merged.map((row) => row.id)).toEqual(["a", "b", "z"]);
  expect(mergeById(first, [artisan("a", "c1")])).toBe(first);
  expect(mergeById(first, [])).toBe(first);
});

test("the artisans list sends the funnel's workshop to the server", () => {
  const source = read("app", "(protected)", "artisans", "page.tsx");
  const request = between(source, 'listResource<Artisan>("/artisans"', "});");

  expect(request, "the workshop pick must reach the server").toContain("workshopId: funnel.workshopId");
  expect(request).toContain("craftId: funnel.craftId");
});

test("the artisans list no longer intersects one server page in the browser", () => {
  const source = read("app", "(protected)", "artisans", "page.tsx");

  expect(codeOf(source), "the client-side workshop intersection must be gone").not.toContain(
    "workshopArtisanIds"
  );

  const claim = "supports craftId but not workshopId";
  expect(codeOf(source), "the false claim must not be live code").not.toContain(claim);
  if (source.includes(claim)) {
    expect(source, "the claim may only survive as history, explicitly refuted").toContain(
      "that claim was simply false"
    );
  }
});

test("the funnel's artisan options are narrowed by the server, not by filtering a cut page", () => {
  const source = read("components", "FunnelFilters.tsx");
  const request = between(source, 'listResource<Artisan>("/artisans"', "})");

  expect(request, "the workshop must narrow the QUERY, not the array").toContain("workshopId: value.workshopId");
  expect(request).toContain("craftId: value.craftId");

  const effect = between(source, 'listResource<Artisan>("/artisans"', "]);");
  expect(effect).toContain("value.workshopId");

  expect(codeOf(source)).not.toContain("workshopArtisanIds");
});

test("the funnel keeps every list's total and renders the notice", () => {
  const source = read("components", "FunnelFilters.tsx");

  expect(source).toContain('listCut(workshopResult, "workshops")');
  expect(source).toContain("<CraftSelect");
  expect(source).toContain('listCut(result, "artisans")');
  expect(source, "a cut list must say so on screen").toContain("<CappedListNotice");
  expect(source).toContain("LIST_PAGE_CEILING");
});

test("an artisan merely absent from the loaded page does not count as the wrong craft", () => {
  expect(
    craftChangeClearsArtisan({ nextCraftId: "c2", artisanId: "off-page", artisans: [artisan("a", "c1")] })
  ).toBe(false);

  expect(
    craftChangeClearsArtisan({ nextCraftId: "c2", artisanId: "a", artisans: [artisan("a", "c1")] })
  ).toBe(true);

  expect(
    craftChangeClearsArtisan({ nextCraftId: "c1", artisanId: "a", artisans: [artisan("a", "c1")] })
  ).toBe(false);

  expect(craftChangeClearsArtisan({ nextCraftId: "c2", artisanId: "", artisans: [] })).toBe(false);
  expect(
    craftChangeClearsArtisan({ nextCraftId: "", artisanId: "a", artisans: [artisan("a", "c1")] })
  ).toBe(false);
});

test("a craft DESELECTION drops only the artisans it actually contradicts", () => {
  const artisans = [artisan("a1", "c1"), artisan("a2", "c2"), artisan("a3", null)];

  expect(
    craftsChangeClearsArtisans({
      nextCraftIds: ["c1"],
      removedCraftIds: ["c2"],
      artisanIds: ["a1", "a2"],
      artisans
    })
  ).toEqual(["a2"]);

  expect(
    craftsChangeClearsArtisans({
      nextCraftIds: ["c1", "c2"],
      removedCraftIds: [],
      artisanIds: ["a1", "a2"],
      artisans
    })
  ).toEqual([]);

  expect(
    craftsChangeClearsArtisans({
      nextCraftIds: ["c1"],
      removedCraftIds: ["c2"],
      artisanIds: ["off-page", "a3"],
      artisans
    })
  ).toEqual([]);

  expect(
    craftsChangeClearsArtisans({
      nextCraftIds: [],
      removedCraftIds: ["c1", "c2"],
      artisanIds: ["a1", "a2", "a3", "off-page"],
      artisans
    })
  ).toEqual(["a1", "a2"]);
});

test("ADDING a craft drops nobody, including artisans of crafts that were never ticked", () => {
  const artisans = [artisan("a-of-x", "x"), artisan("b-of-warli", "warli")];

  expect(
    craftsChangeClearsArtisans({
      nextCraftIds: ["x", "z"],
      removedCraftIds: [],
      artisanIds: ["a-of-x", "b-of-warli"],
      artisans
    })
  ).toEqual([]);

  expect(
    craftsChangeClearsArtisans({
      nextCraftIds: ["x"],
      removedCraftIds: ["z"],
      artisanIds: ["a-of-x", "b-of-warli"],
      artisans
    })
  ).toEqual([]);

  expect(
    craftsChangeClearsArtisans({
      nextCraftIds: ["x"],
      removedCraftIds: ["warli"],
      artisanIds: ["a-of-x", "b-of-warli"],
      artisans
    })
  ).toEqual(["b-of-warli"]);
});

test("both record forms route the craft change through that one decision", () => {
  for (const file of ["ProductForm.tsx", "ToolForm.tsx"]) {
    const source = read("components", "forms", file);
    const rule = file === "ToolForm.tsx" ? "craftsChangeClearsArtisans(" : "craftChangeClearsArtisan(";
    expect(source, `${file} must not re-derive the clear rule`).toContain(rule);
    expect(source, `${file} still carries the old absence-is-the-wrong-craft test`).not.toContain(
      "!artisans.some((a) => a.id === artisanId && a.craftId === next)"
    );
    expect(source).toContain("useCraftAndArtisanOptions(");
    expect(source).toContain("<CappedListNotice");
  }
});

test("the artisan roster is asked for by craft rather than filtered out of the newest hundred", () => {
  const source = read("components", "forms", "recordPickers.ts");
  const request = between(source, 'const result = await listResource<Artisan>("/artisans", {', "});");
  expect(request).toContain("craftIds: ids.join(\",\")");
  expect(request, "an old API must still narrow when there is one craft to narrow by").toContain(
    "craftId: ids.length === 1 ? ids[0] : undefined"
  );
  expect(request).toContain("LIST_PAGE_CEILING");
  expect(source).toContain('"artisans of these crafts" : "artisans of this craft"');
  expect(source).toContain("useRecordsOffPage");
  expect(source, "the singular is still here for the single-select forms").toContain("useRecordOffPage");
});

test("every record picker in the web forms reports its cut", () => {
  for (const file of ["ProductForm.tsx", "ToolForm.tsx", "ProcessForm.tsx", "ToolAssignmentSection.tsx", "WorkshopSelect.tsx"]) {
    const source = read("components", "forms", file);
    expect(source, `${file} draws a capped picker with no notice`).toContain("<CappedListNotice");
    expect(source, `${file} still asks for a bare literal page size`).not.toContain("pageSize: 100");
  }
  expect(read("components", "forms", "ArtisanForm.tsx")).toContain("<CraftSelect");
  for (const file of ["WorkshopSelect.tsx", "ProcessForm.tsx"]) {
    expect(read("components", "forms", file), `${file} cannot draw its own current value`).toContain(
      "useRecordOffPage"
    );
  }
});

test("the craft picker in every record form can draw its own current value", () => {
  for (const file of ["ArtisanForm.tsx", "ProductForm.tsx", "ToolForm.tsx"]) {
    const code = codeOf(read("components", "forms", file));
    expect(code, `${file} must pick its craft from the catalogue`).toContain("<CraftSelect");
    expect(code, `${file} must name a craft that is not on the list`).toContain("fallbackLabels={craftFallbackLabels}");
    expect(code, `${file} still reads the paged craft list`).not.toContain('listResource<Craft>("/crafts"');
  }
});

test("Data's browse panel has a pager, a total and a notice", () => {
  const panel = read("components", "dataExplorer", "BrowseByType.tsx");
  expect(panel, "the cut is stated in words").toContain("const cut = current ? browseCutLine(current, noun) : null;");
  expect(panel, "no pager was the finding").toContain("aria-label={`${def.label} pages`}");
  expect(panel).toContain("Page {current.page} of {current.pages}");
  const page = read("app", "(protected)", "data", "page.tsx");
  expect(page).toContain("pageSize: LIST_PAGE_CEILING");
  expect(page).not.toContain("pageSize: 100");
});

test("changing the record type restarts at page one", () => {
  expect(read("components", "dataExplorer", "BrowseByType.tsx")).toContain('onChange({ type: value, page: 1, q: "" })');
});

test("the media upload form says how much of the record type its entry picker holds", () => {
  const source = read("app", "(protected)", "media", "page.tsx");

  expect(source).toContain("Promise<EntryOptions>");
  expect(source).toContain("cut: ListCut | null");
  expect(source, "the ComboBox filters only what it was handed").toContain("<CappedListNotice");
  expect(source).toContain("const params = { pageSize: LIST_PAGE_CEILING };");

  const loader = between(source, "async function loadEntryOptions", "\n}\n");
  const counted = loader.match(/cut: listCut\(page, /g) ?? [];
  expect(counted.length, "one branch per paged record type").toBe(7);
  expect(source).toContain('<CraftSelect name="linkedRecordId"');
});

test("the ceiling is the server's number, named once", () => {
  expect(LIST_PAGE_CEILING).toBe(100);
  const cut: ListCut | null = listCut(page(LIST_PAGE_CEILING, 2530, (i) => ({ id: String(i) })), "media files");
  expect(cut?.loaded).toBe(LIST_PAGE_CEILING);
});
