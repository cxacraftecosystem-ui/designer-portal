import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { packPages, type FlowItem } from "@/components/designworkshop/report/reportPagination";

const PAGE = 1000;

function block(partial: Partial<FlowItem> & { blockIndex: number; heightPx: number }): FlowItem {
  return {
    breakBefore: false,
    keepWithNext: false,
    splittable: false,
    ...partial
  };
}

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
  expect(shape([block({ blockIndex: 0, heightPx: 1000.2 })])).toEqual([[0]]);
});

test("a paragraph is divided at its line boxes, and the pieces say which is which", () => {
  const lines = Array.from({ length: 30 }, (_unused, index) => (index + 1) * 50);
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
  const packed = packPages(
    [block({ blockIndex: 0, heightPx: 900, splittable: true, breakOffsetsPx: [120, 380, 640] })],
    { contentPx: 500 }
  );

  expect(packed.pages[0].slices[0].heightPx).toBe(380);
  expect(packed.pages[1].slices[0].offsetPx).toBe(380);
});

test("a table repeats its header over every continuation, the way place_row does", () => {
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
  expect(packed.pages[0].slices[0].heightPx).toBe(480);
  expect(packed.pages[1].slices[0].heightPx).toBe(320);
  expect(packed.pages[1].usedPx).toBe(360);
});

test("an unsplittable block taller than a page gets a page of its own and is SCALED, not clipped", () => {
  const packed = packPages(
    [block({ blockIndex: 0, heightPx: 200 }), block({ blockIndex: 1, heightPx: 2500 })],
    { contentPx: PAGE }
  );

  expect(packed.pages).toHaveLength(2);
  expect(packed.pages[1].slices).toHaveLength(1);
  expect(packed.pages[1].slices[0].scale).toBeCloseTo(0.4, 5);
  expect(packed.scaled).toEqual([{ blockIndex: 1, pageNumber: 2, scale: 0.4 }]);
  expect(packed.pages[1].slices[0].offsetPx).toBe(0);
  expect(packed.pages[1].slices[0].heightPx).toBe(2500);
  expect(packed.abandoned).toEqual([]);
});

test("a heading at the foot of a page turns the page and takes its section with it", () => {
  const items = [
    block({ blockIndex: 0, heightPx: 880 }),
    block({ blockIndex: 1, heightPx: 60, keepWithNext: true }),
    block({ blockIndex: 2, heightPx: 400, splittable: true, breakOffsetsPx: [100, 200, 300] })
  ];

  expect(shape(items)).toEqual([[0], [1, 2]]);
});

test("a heading whose section does fit beneath it stays where it is", () => {
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

  expect(shape(items)).toEqual([[0], [1, 2, 3]]);
});

test("a keep-run that could never fit a page does not turn a page to gain nothing", () => {
  const items = [
    block({ blockIndex: 0, heightPx: 100 }),
    block({ blockIndex: 1, heightPx: 900, keepWithNext: true }),
    block({ blockIndex: 2, heightPx: 900 })
  ];

  expect(shape(items)).toEqual([[0, 1], [2]]);
});

test("a declared PAGEBREAK still wins, even with most of the page left", () => {
  const items = [
    block({ blockIndex: 0, heightPx: 50 }),
    block({ blockIndex: 2, heightPx: 50, breakBefore: true }),
    block({ blockIndex: 3, heightPx: 50 })
  ];

  expect(shape(items)).toEqual([[0], [2, 3]]);
});

test("a cover is exactly one page, carries nothing else, and gets the taller content box", () => {
  const packed = packPages(
    [
      block({ blockIndex: 0, heightPx: 1100, isCover: true }),
      block({ blockIndex: 1, heightPx: 200 })
    ],
    { contentPx: PAGE, coverContentPx: 1150 }
  );

  expect(packed.pages.map((page) => page.isCover)).toEqual([true, false]);
  expect(packed.pages[0].slices.map((slice) => slice.blockIndex)).toEqual([0]);
  expect(packed.scaled).toEqual([]);
});

test("a callout is moved whole while it fits a page and divided only when it fits none", () => {
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
  expect(shape(doesNot)).toEqual([[0, 1], [1]]);
});

test("a splittable block with no legal cut points behaves as an unsplittable one", () => {
  const items = [
    block({ blockIndex: 0, heightPx: 600 }),
    block({ blockIndex: 1, heightPx: 600, splittable: true, breakOffsetsPx: [] })
  ];
  expect(shape(items)).toEqual([[0], [1]]);
});

test("every block reaches a page, and nothing is abandoned", () => {
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
  const packed = packPages([], { contentPx: PAGE });
  expect(packed.pages).toHaveLength(1);
  expect(packed.pages[0].slices).toEqual([]);
});

test("THE 6,000-CHARACTER LONG_TEXT ROW: the table divides, and the one row that fits no page is scaled alone", () => {
  const rows = block({
    blockIndex: 0,
    heightPx: 1270,
    splittable: true,
    breakOffsetsPx: [50, 1250, 1270],
    repeatHeaderPx: 30
  });
  const out = packPages([rows], { contentPx: PAGE });

  expect(out.pages).toHaveLength(3);
  const big = out.pages[1].slices[0];
  expect(big.offsetPx).toBe(50);
  expect(big.heightPx).toBe(1200);
  expect(big.scale).toBeLessThan(1);
  expect(big.repeatHeaderPx).toBe(30);
  expect(out.scaled).toEqual([{ blockIndex: 0, pageNumber: 2, scale: big.scale }]);
  expect(out.abandoned).toEqual([]);
  for (const page of out.pages) expect(page.usedPx).toBeLessThanOrEqual(page.capacityPx + 0.5);
});

test("a 6,000-character ANSWER, as prose rather than a table cell, needs no scaling at all", () => {
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
  expect(out.pages[0].slices[0].heightPx).toBe(994);
  expect(out.pages[1].slices[0].offsetPx).toBe(994);
  for (const page of out.pages) expect(page.usedPx).toBeLessThanOrEqual(page.capacityPx + 0.5);
});

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");
const SHEET = "components/designworkshop/report/ReportSheet.tsx";

test("the sheet's height is FIXED, which is the whole of the visual defect", () => {
  const sheet = read(SHEET);
  expect(sheet).toMatch(/\.rp-sheet \{[^}]*\n\s*height: var\(--rp-page-h\);/);
  expect(sheet).not.toMatch(/\.rp-sheet \{[^}]*min-height/);
  expect(sheet).toMatch(/\.rp-body \{[^}]*min-height: 0;[^}]*overflow: hidden;/);
});

test("print still yields one physical page per sheet", () => {
  const sheet = read(SHEET);
  const print = sheet.slice(sheet.indexOf("@media print"));
  expect(print).toMatch(/@page \{ size: \$\{cssPageSize\}; margin: 0; \}/);
  expect(print).not.toMatch(/height: var\(--rp-text-h\) !important;/);
  expect(print).not.toMatch(/\.rp-sheet \{[^}]*padding: 0 !important;/);
  expect(print).toMatch(/break-after: page; page-break-after: always;/);
  expect(print).not.toMatch(/min-height: 0 !important/);
  expect(print).toMatch(/\.rp-measure-host \{ display: none !important; \}/);
});

test("the running furniture is drawn in the MARGIN and costs the text column nothing", () => {
  const sheet = read(SHEET);
  expect(sheet).toMatch(/\.rp-runhead \{\s*\n\s*position: absolute;/);
  expect(sheet).toMatch(/\.rp-runfoot \{\s*\n\s*position: absolute;/);
  expect(sheet).not.toMatch(/\.rp-runhead \{[^}]*margin-bottom: 7mm/);
  expect(sheet).not.toMatch(/\.rp-runfoot \{[^}]*margin-top: 7mm/);
  expect(sheet).toMatch(/\.rp-sheet \{[^}]*position: relative;/);
});

test("the two clearances are the writer's numbers, and the cover skips the head one", () => {
  const sheet = read(SHEET);
  expect(sheet).toMatch(/const HEAD_CLEARANCE_MM = 6;/);
  expect(sheet).toMatch(/const FOOT_RESERVE_MM = 10;/);
  expect(sheet).toMatch(/padding: calc\(var\(--rp-margin\) \+ var\(--rp-head-clear\)\)/);
  expect(sheet).toMatch(/calc\(var\(--rp-margin\) \+ var\(--rp-foot-reserve\)\);/);
  expect(sheet).not.toMatch(/\.rp-body \{[^}]*padding/);
  expect(sheet).toMatch(/\.rp-sheet-cover \{ padding-top: var\(--rp-margin\); \}/);
});

test("BOTH CLIENTS TAKE THE SAME TWO CLEARANCES, or one document paginates two ways", () => {
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
