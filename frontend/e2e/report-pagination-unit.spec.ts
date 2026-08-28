import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { packPages, type FlowItem } from "@/components/designworkshop/report/reportPagination";

/**
 * THE PREVIEW STOPPED BEING A4 THE MOMENT REAL CONTENT ARRIVED, AND THIS IS WHAT REPLACED IT.
 *
 * `ReportSheet.tsx` drew a sheet at `min-height: var(--rp-page-h)` and `previewModel.splitIntoSheets`
 * cut the block stream ONLY where the template declared a break — a `PAGEBREAK` block or a `COVER`.
 * A minimum is not a page: one long stage rendered as a single "sheet" several times taller than
 * A4, so the screen whose entire job is to answer "does this fit on the page?" answered it by
 * making the page bigger. `splitIntoSheets`'s own docstring conceded that "a sheet below can
 * therefore be a page and a half of the real document".
 *
 * `reportPagination.packPages` is the replacement: blocks flowing into fixed-height pages, breaking
 * where the paper runs out. It is PURE for the reason `components/ui/selectFilter.ts` and
 * `components/data/cappedList.ts` are pure — there is no React renderer in this repository's
 * devDependencies, so a judgement made inside JSX is only ever exercised by somebody looking at a
 * screen, and several of the cases below (a single block taller than a whole page; a keep-with-next
 * heading landing exactly at a page foot) are ones no ordinary document produces on demand.
 *
 * EVERY RULE ASSERTED HERE IS THE SERVER'S, NOT THE BROWSER'S. There are four renderers of this
 * document — `report_docx.py`, `report_pdf.py` and the two on-device Kotlin writers — a ministry
 * receives what they produce, and the preview's job is to LAY OUT the blocks they consume, never to
 * invent a rule of its own. So the tests are written against `report_pdf.py`'s behaviour and cite
 * it: `place_row` repeating a table header over a continuation, `_block_heading` reserving one body
 * line beneath itself, `_cut_row` refusing to divide a block that would fit a page of its own,
 * `_block_cover` ending with `_new_page()`.
 *
 * The last two tests are source reads, for the reason the sibling unit specs give: a component
 * cannot be mounted here, and the two things they pin — a FIXED page height, and a page count the
 * screen does not overclaim — are the two halves of the defect this change exists to end.
 */

const PAGE = 1000;

/** A block with the boring answers filled in, so each test states only what it is about. */
function block(partial: Partial<FlowItem> & { blockIndex: number; heightPx: number }): FlowItem {
  return {
    breakBefore: false,
    keepWithNext: false,
    splittable: false,
    ...partial
  };
}

/** Which blocks, in order, put a slice on each page. */
function shape(items: FlowItem[], contentPx = PAGE, coverContentPx?: number): number[][] {
  return packPages(items, { contentPx, coverContentPx }).pages.map((page) =>
    page.slices.map((slice) => slice.blockIndex)
  );
}

test("a block that fits stays on the page, and the page keeps its room for the next one", () => {
  const packed = packPages(
    [
      block({ blockIndex: 0, heightPx: 300 }),
      block({ blockIndex: 1, heightPx: 300, gapBeforePx: 20 })
    ],
    { contentPx: PAGE }
  );

  expect(packed.pages).toHaveLength(1);
  expect(packed.pages[0].slices.map((slice) => slice.blockIndex)).toEqual([0, 1]);
  expect(packed.pages[0].usedPx).toBeCloseTo(620, 5);
  expect(packed.divided).toEqual([]);
  expect(packed.scaled).toEqual([]);
});

test("the gap above a block is spent mid-page and eaten by a page break", () => {
  // `_new_page` resets the cursor to the top margin; the space that would have sat between two
  // blocks does not travel across the break with the second of them. A gap that DID survive would
  // put an unexplained indent at the top of every continuation page.
  const packed = packPages(
    [
      block({ blockIndex: 0, heightPx: 900 }),
      block({ blockIndex: 1, heightPx: 200, gapBeforePx: 40 })
    ],
    { contentPx: PAGE }
  );

  expect(packed.pages).toHaveLength(2);
  expect(packed.pages[1].slices[0].gapBeforePx).toBe(0);
  expect(packed.pages[1].usedPx).toBeCloseTo(200, 5);
});

test("a block that overflows by a hair moves whole rather than being drawn past the paper", () => {
  // One pixel. This is the case the old `min-height` sheet answered by growing: the sheet simply
  // became 1001 px tall and stopped depicting a page.
  const packed = packPages(
    [block({ blockIndex: 0, heightPx: 400 }), block({ blockIndex: 1, heightPx: 601 })],
    { contentPx: PAGE }
  );

  expect(shape([block({ blockIndex: 0, heightPx: 400 }), block({ blockIndex: 1, heightPx: 601 })])).toEqual([
    [0],
    [1]
  ]);
  expect(packed.pages[0].usedPx).toBeLessThanOrEqual(PAGE);
  expect(packed.pages[1].usedPx).toBeLessThanOrEqual(PAGE);
});

test("half a pixel over is not over — sub-pixel measurement must not start a page", () => {
  // `getBoundingClientRect` is sub-pixel, so a block measured at 1000.0000001 against a 1000 px
  // content box is the same block. Treating that as an overflow would break a page for a rounding
  // error, and would do it on every page of the document.
  expect(shape([block({ blockIndex: 0, heightPx: 1000.2 })])).toEqual([[0]]);
});

test("a paragraph is divided at its line boxes, and the pieces say which is which", () => {
  // `_draw_lines` calls `_ensure(line.height)` once per line, so a paragraph breaks between lines.
  const lines = Array.from({ length: 30 }, (_unused, index) => (index + 1) * 50); // 50 px per line
  const packed = packPages(
    [block({ blockIndex: 0, heightPx: 1500, splittable: true, breakOffsetsPx: lines })],
    { contentPx: PAGE }
  );

  expect(packed.pages).toHaveLength(2);
  expect(packed.divided).toEqual([0]);
  expect(packed.pages[0].slices[0]).toMatchObject({ part: 0, parts: 2, offsetPx: 0, heightPx: 1000 });
  expect(packed.pages[1].slices[0]).toMatchObject({ part: 1, parts: 2, offsetPx: 1000, heightPx: 500 });
});

test("a divided block is cut on a legal offset, never at the pixel where the page ran out", () => {
  // The offsets ARE the line boxes; cutting anywhere else slices a line of type in half
  // horizontally, which is a thing no printer has ever done.
  const packed = packPages(
    [block({ blockIndex: 0, heightPx: 900, splittable: true, breakOffsetsPx: [120, 380, 640] })],
    { contentPx: 500 }
  );

  expect(packed.pages[0].slices[0].heightPx).toBe(380);
  expect(packed.pages[1].slices[0].offsetPx).toBe(380);
});

test("a table repeats its header over every continuation, the way place_row does", () => {
  // `place_row` calls `place_header()` after `_new_page()` — "exactly as Word repeats a
  // `<w:tblHeader/>` row over a body row it has split". The header costs room on the continuation
  // page, so the packer has to reserve it rather than merely draw it.
  const rows = [80, 160, 240, 320, 400, 480, 560, 640, 720];
  const packed = packPages(
    [
      block({
        blockIndex: 0,
        heightPx: 800,
        splittable: true,
        breakOffsetsPx: rows,
        repeatHeaderPx: 40
      })
    ],
    { contentPx: 500 }
  );

  expect(packed.pages).toHaveLength(2);
  expect(packed.pages[0].slices[0].repeatHeaderPx).toBe(0);
  expect(packed.pages[1].slices[0].repeatHeaderPx).toBe(40);
  // The first page takes rows up to the 480 offset — all 500 px of it. The continuation then has
  // 500 less the 40 px header, so the header is RESERVED rather than drawn over the rows: 320 px
  // of table under 40 px of head comes to 360, inside the page, where 320 + 40 charged to a full
  // 500 would not have been.
  expect(packed.pages[0].slices[0].heightPx).toBe(480);
  expect(packed.pages[1].slices[0].heightPx).toBe(320);
  expect(packed.pages[1].usedPx).toBe(360);
});

test("an unsplittable block taller than a page gets a page of its own and is SCALED, not clipped", () => {
  // The .pdf never has this for a picture: it caps a photograph at 0.62 of the text column
  // (`_block_image`) and a chart at 0.58 (`_block_figure`), so it scales too. For anything else
  // the .pdf cuts mid-cell, which the browser cannot do without measuring inside every cell — so
  // the preview scales and REPORTS it. A silently clipped figure is the one outcome that is not
  // allowed: it is a designer approving a page that does not exist.
  const packed = packPages(
    [block({ blockIndex: 0, heightPx: 200 }), block({ blockIndex: 1, heightPx: 2500 })],
    { contentPx: PAGE }
  );

  expect(packed.pages).toHaveLength(2);
  expect(packed.pages[1].slices).toHaveLength(1);
  expect(packed.pages[1].slices[0].scale).toBeCloseTo(0.4, 5);
  expect(packed.scaled).toEqual([{ blockIndex: 1, pageNumber: 2, scale: 0.4 }]);
  // Nothing is dropped and nothing is left over.
  expect(packed.pages[1].slices[0].offsetPx).toBe(0);
  expect(packed.pages[1].slices[0].heightPx).toBe(2500);
  expect(packed.abandoned).toEqual([]);
});

test("a heading at the foot of a page turns the page and takes its section with it", () => {
  // `_block_heading` reserves `lead + its own lines + trail + self.base_size * 1.32` — that last
  // term is ONE BODY LINE, and it is the whole of keep-with-next: "a heading with nothing under it
  // is still an orphan even if it fits". So a heading that FITS the remaining room and whose first
  // following line does not must still move.
  const items = [
    block({ blockIndex: 0, heightPx: 880 }),
    block({ blockIndex: 1, heightPx: 60, keepWithNext: true }), // fits the last 120 px on its own
    block({ blockIndex: 2, heightPx: 400, splittable: true, breakOffsetsPx: [100, 200, 300] })
  ];

  expect(shape(items)).toEqual([[0], [1, 2]]);
});

test("a heading whose section does fit beneath it stays where it is", () => {
  // The mirror of the case above, so the rule is not "a heading always turns the page".
  const items = [
    block({ blockIndex: 0, heightPx: 600 }),
    block({ blockIndex: 1, heightPx: 60, keepWithNext: true }),
    block({ blockIndex: 2, heightPx: 900, splittable: true, breakOffsetsPx: [100, 200, 300] })
  ];

  expect(shape(items)).toEqual([[0, 1, 2], [2]]);
});

test("consecutive headings are kept together with the first line under the last of them", () => {
  const items = [
    block({ blockIndex: 0, heightPx: 800 }),
    block({ blockIndex: 1, heightPx: 60, keepWithNext: true }),
    block({ blockIndex: 2, heightPx: 50, keepWithNext: true }),
    block({ blockIndex: 3, heightPx: 400, splittable: true, breakOffsetsPx: [100, 200, 300] })
  ];

  // 60 + 50 + 100 = 210 does not fit in the 200 left, so the whole run moves rather than the
  // second heading being stranded under the first.
  expect(shape(items)).toEqual([[0], [1, 2, 3]]);
});

test("a keep-run that could never fit a page does not turn a page to gain nothing", () => {
  // The clamp in `keepRunHeight`. `_block_table` paid for the opposite reading once: reserving a
  // header plus an over-tall first row "turned a page the row was going to start on anyway: the
  // table's own first page came out completely blank".
  const items = [
    block({ blockIndex: 0, heightPx: 100 }),
    block({ blockIndex: 1, heightPx: 900, keepWithNext: true }),
    block({ blockIndex: 2, heightPx: 900 })
  ];

  expect(shape(items)).toEqual([[0, 1], [2]]);
});

test("a declared PAGEBREAK still wins, even with most of the page left", () => {
  // A break the template asked for is a break in the FILE — both writers honour it exactly — so it
  // outranks every measurement here. `ReportSheet.planFlow` folds the `PAGEBREAK` block itself onto
  // the following block as `breakBefore`, which is the form asserted.
  const items = [
    block({ blockIndex: 0, heightPx: 50 }),
    block({ blockIndex: 2, heightPx: 50, breakBefore: true }),
    block({ blockIndex: 3, heightPx: 50 })
  ];

  expect(shape(items)).toEqual([[0], [2, 3]]);
});

test("a cover is exactly one page, carries nothing else, and gets the taller content box", () => {
  // `_block_cover` ends with `self._new_page()` and neither writer draws furniture on page one —
  // which is why the cover's content box is the whole sheet less its margins and an ordinary
  // page's is that less the running head and foot.
  const packed = packPages(
    [
      block({ blockIndex: 0, heightPx: 1100, isCover: true }),
      block({ blockIndex: 1, heightPx: 200 })
    ],
    { contentPx: PAGE, coverContentPx: 1150 }
  );

  expect(packed.pages.map((page) => page.isCover)).toEqual([true, false]);
  expect(packed.pages[0].slices.map((slice) => slice.blockIndex)).toEqual([0]);
  // 1100 fits the cover's 1150 and would NOT have fitted an ordinary page's 1000.
  expect(packed.scaled).toEqual([]);
});

test("a callout is moved whole while it fits a page and divided only when it fits none", () => {
  // One `_cut_row` over the whole flow, whose docstring calls the fits-a-page case "an ordinary
  // break": the block is not cut, it is moved. Cutting a callout that would have sat happily on the
  // next page splits a boxed aside across a page boundary for no reason.
  const offsets = [200, 400, 600, 800, 1000, 1200];
  const fits = [
    block({ blockIndex: 0, heightPx: 500 }),
    block({ blockIndex: 1, heightPx: 800, splittable: true, preferWhole: true, breakOffsetsPx: offsets })
  ];
  expect(shape(fits)).toEqual([[0], [1]]);

  const doesNot = [
    block({ blockIndex: 0, heightPx: 500 }),
    block({ blockIndex: 1, heightPx: 1400, splittable: true, preferWhole: true, breakOffsetsPx: offsets })
  ];
  // Too tall for ANY page, so `preferWhole` stops applying and the block is divided at its own
  // legal offsets — starting in the 500 px left on this page rather than wasting them. That is
  // `_cut_row` exactly: it returns "an ordinary break" only for `height <= one page`, and
  // otherwise cuts against `budget = self.y - self.bottom - padding`, which is the room left here.
  expect(shape(doesNot)).toEqual([[0, 1], [1]]);
});

test("a splittable block with no legal cut points behaves as an unsplittable one", () => {
  // "The measurer could not find a place to cut this" and "this may not be cut" have to produce the
  // same drawing, or a block would be sliced at an arbitrary pixel because a selector missed.
  const items = [
    block({ blockIndex: 0, heightPx: 600 }),
    block({ blockIndex: 1, heightPx: 600, splittable: true, breakOffsetsPx: [] })
  ];
  expect(shape(items)).toEqual([[0], [1]]);
});

test("every block reaches a page, and nothing is abandoned", () => {
  // The property that matters most, over a document with one of everything in it: a block that
  // silently failed to be placed is a section missing from a document somebody already approved.
  const items: FlowItem[] = [
    block({ blockIndex: 0, heightPx: 1100, isCover: true }),
    block({ blockIndex: 1, heightPx: 80, keepWithNext: true }),
    block({ blockIndex: 2, heightPx: 2400, splittable: true, breakOffsetsPx: [300, 700, 1100, 1500, 1900, 2300] }),
    block({ blockIndex: 3, heightPx: 3000 }),
    block({ blockIndex: 4, heightPx: 120, breakBefore: true }),
    block({ blockIndex: 5, heightPx: 700, splittable: true, preferWhole: true, breakOffsetsPx: [350] }),
    block({ blockIndex: 6, heightPx: 260, gapBeforePx: 18 })
  ];
  const packed = packPages(items, { contentPx: PAGE, coverContentPx: 1150 });

  const placed = new Set(packed.pages.flatMap((page) => page.slices.map((slice) => slice.blockIndex)));
  expect([...placed].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6]);
  expect(packed.abandoned).toEqual([]);
  for (const page of packed.pages) {
    expect(page.usedPx).toBeLessThanOrEqual(page.capacityPx + 0.5);
  }
});

test("a document of nothing but breaks still renders as one sheet", () => {
  // Carried forward from `splitIntoSheets`: an empty sheet reads as "this template produced
  // nothing", which is the truth; zero sheets would render as an empty panel indistinguishable
  // from a load that had not finished.
  const packed = packPages([], { contentPx: PAGE });
  expect(packed.pages).toHaveLength(1);
  expect(packed.pages[0].slices).toEqual([]);
});

test("THE 6,000-CHARACTER LONG_TEXT ROW: the table divides, and the one row that fits no page is scaled alone", () => {
  // `report_pdf.py`'s module docstring names this case as the one the fit-or-turn-the-page loop
  // cannot answer on its own: "A table row carrying a 6,000-character LONG_TEXT answer is taller
  // than A4, so 'start a new one' never makes it fit", and an early version drew the remainder at a
  // negative y — outside the paper, in a file that opened perfectly. It is reachable from this
  // corpus, so it is pinned here rather than left to a screenshot.
  //
  // A table is splittable BY ROW, so the oversized row is not a cut point but a boundary: the rows
  // above it and below it break normally, and the row itself lands alone on a page.
  const rows = block({
    blockIndex: 0,
    heightPx: 1270,
    splittable: true,
    breakOffsetsPx: [50, 1250, 1270], // header + row, then THE row, then a short last row
    repeatHeaderPx: 30
  });
  const out = packPages([rows], { contentPx: PAGE });

  expect(out.pages).toHaveLength(3);
  // The row that fits no page gets a page of its own, scaled to fit it, and every continuation
  // still reserves the repeated header the way `place_row` -> `place_header` does.
  const big = out.pages[1].slices[0];
  expect(big.offsetPx).toBe(50);
  expect(big.heightPx).toBe(1200);
  expect(big.scale).toBeLessThan(1);
  expect(big.repeatHeaderPx).toBe(30);
  // Scaled, therefore ANNOUNCED: the strip prints the count and the page numbers, because this is
  // the one place the preview knowingly draws something the file will draw differently — the .pdf
  // CUTS that row mid-cell through `_cut_row` and repeats the header overleaf.
  expect(out.scaled).toEqual([{ blockIndex: 0, pageNumber: 2, scale: big.scale }]);
  expect(out.abandoned).toEqual([]);
  // Nothing is drawn past the paper on any page — which is the whole of the owner's complaint.
  for (const page of out.pages) expect(page.usedPx).toBeLessThanOrEqual(page.capacityPx + 0.5);
});

test("a 6,000-character ANSWER, as prose rather than a table cell, needs no scaling at all", () => {
  // The same answer rendered as a PARAGRAPH divides at its line boxes (`_draw_lines`), so it just
  // flows: two pages, full size, nothing announced. Worth pinning beside the row above, because it
  // is the difference between "the corpus has a case that scales" and "long answers scale".
  const prose = block({
    blockIndex: 0,
    heightPx: 1400,
    splittable: true,
    breakOffsetsPx: Array.from({ length: 100 }, (_, i) => (i + 1) * 14)
  });
  const out = packPages([prose], { contentPx: PAGE });

  expect(out.pages).toHaveLength(2);
  expect(out.scaled).toEqual([]);
  expect(out.abandoned).toEqual([]);
  expect(out.pages[0].slices[0].heightPx).toBe(994); // the last line box at or under 1000
  expect(out.pages[1].slices[0].offsetPx).toBe(994);
  for (const page of out.pages) expect(page.usedPx).toBeLessThanOrEqual(page.capacityPx + 0.5);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The two source reads
 * ──────────────────────────────────────────────────────────────────────────── */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");
const SHEET = "components/designworkshop/report/ReportSheet.tsx";

test("the sheet's height is FIXED, which is the whole of the visual defect", () => {
  const sheet = read(SHEET);
  // One word. `min-height` lets a sheet grow past the paper it is depicting, and everything in
  // `reportPagination` exists so that nothing has to be poured past it.
  expect(sheet).toMatch(/\.rp-sheet \{[^}]*\n\s*height: var\(--rp-page-h\);/);
  // Read as a DECLARATION inside the rule, not as a substring of the file: the header comment
  // above names the old `min-height` in order to record why it was wrong, and a bare substring
  // test would read that sentence as the bug it warns about.
  expect(sheet).not.toMatch(/\.rp-sheet \{[^}]*min-height/);
  // And the body may be shorter than its content, or the fixed sheet above is pushed open again by
  // a flex item's automatic minimum size — the same defect under a different property name.
  expect(sheet).toMatch(/\.rp-body \{[^}]*min-height: 0;[^}]*overflow: hidden;/);
});

test("print still yields one physical page per sheet", () => {
  const sheet = read(SHEET);
  const print = sheet.slice(sheet.indexOf("@media print"));
  // The sheet keeps its fixed height in print too. It used to release it and hand pagination back
  // to the browser, because an overflowing sheet had to go somewhere; nothing overflows now, and
  // re-paginating would undo the layout a designer has just approved. What CHANGED is where the
  // height comes from: the print rule used to restate it as the text column and strip the sheet's
  // padding, because the @page box carried the document's margin and the sheet was the column. The
  // running furniture is in the margin band now, so the sheet has to BE the paper — hence a
  // margin-less @page and no geometry override here at all.
  expect(print).toMatch(/@page \{ size: \$\{cssPageSize\}; margin: 0; \}/);
  expect(print).not.toMatch(/height: var\(--rp-text-h\) !important;/);
  expect(print).not.toMatch(/\.rp-sheet \{[^}]*padding: 0 !important;/);
  expect(print).toMatch(/break-after: page; page-break-after: always;/);
  expect(print).not.toMatch(/min-height: 0 !important/);
  // Nothing off-screen may reach the printer.
  expect(print).toMatch(/\.rp-measure-host \{ display: none !important; \}/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The content box, which is the writer's and not this preview's
 * ──────────────────────────────────────────────────────────────────────────── */

test("the running furniture is drawn in the MARGIN and costs the text column nothing", () => {
  const sheet = read(SHEET);
  // `report_pdf._draw_furniture`, in as many words: "The lines stack INTO THE MARGIN — upward for
  // the head, downward for the foot — and never into the text column, so nothing here moves
  // `self.y` and pagination is untouched." A head or foot laid out as a flex child of the sheet
  // eats the body instead, which is what this preview used to do: 24.8 mm of furniture by its own
  // declarations against the writer's 16 mm of clearance, so every ordinary A4 page held 222 mm
  // where the file gives 231 mm — a body line and a half missing from every page, compounding
  // into whole sheets the .pdf does not have.
  expect(sheet).toMatch(/\.rp-runhead \{\s*\n\s*position: absolute;/);
  expect(sheet).toMatch(/\.rp-runfoot \{\s*\n\s*position: absolute;/);
  // And neither may go back to spending the body's height on a collapse margin.
  expect(sheet).not.toMatch(/\.rp-runhead \{[^}]*margin-bottom: 7mm/);
  expect(sheet).not.toMatch(/\.rp-runfoot \{[^}]*margin-top: 7mm/);
  // The sheet is their containing block, or `bottom: calc(100% + …)` resolves against the page.
  expect(sheet).toMatch(/\.rp-sheet \{[^}]*position: relative;/);
});

test("the two clearances are the writer's numbers, and the cover skips the head one", () => {
  const sheet = read(SHEET);
  // report_pdf.py:516 `self.bottom = self.margin + 10 * MM` and :635 `self.y = self.top - 6 * MM`.
  expect(sheet).toMatch(/const HEAD_CLEARANCE_MM = 6;/);
  expect(sheet).toMatch(/const FOOT_RESERVE_MM = 10;/);
  // Spent on the SHEET, so `.rp-body` is exactly the fillable column and the probe that measures
  // it needs no padding arithmetic — which is also what keeps the post-draw overflow check an
  // exact `scrollHeight - clientHeight`.
  expect(sheet).toMatch(/padding: calc\(var\(--rp-margin\) \+ var\(--rp-head-clear\)\)/);
  expect(sheet).toMatch(/calc\(var\(--rp-margin\) \+ var\(--rp-foot-reserve\)\);/);
  expect(sheet).not.toMatch(/\.rp-body \{[^}]*padding/);
  // Page one takes no head clearance — `_new_page` applies it only when `self._page > 1` — and
  // still takes the foot reserve, because `self.bottom` is set for every page including the first.
  expect(sheet).toMatch(/\.rp-sheet-cover \{ padding-top: var\(--rp-margin\); \}/);
});

test("BOTH CLIENTS TAKE THE SAME TWO CLEARANCES, or one document paginates two ways", () => {
  // The web and the phone each lay this document out for themselves, so nothing but a read like
  // this stops them drifting. They diverged once already and in both directions at once: the web
  // measured its own furniture out of the text column while Android took the writer's constants,
  // so an ordinary page differed by 8.8 mm and the cover by 10 mm the other way.
  const sheet = read(SHEET);
  const android = readFileSync(
    join(ROOT, "..", "android", "app", "src", "main", "java", "com", "designprototype",
      "workshop", "ui", "designworkshop", "DwReportSheets.kt"),
    "utf8"
  );
  expect(android).toMatch(/DW_HEAD_CLEARANCE_MM = 6f/);
  expect(android).toMatch(/DW_FOOT_RESERVE_MM = 10f/);
  expect(sheet).toMatch(/const HEAD_CLEARANCE_MM = 6;/);
  expect(sheet).toMatch(/const FOOT_RESERVE_MM = 10;/);
});
