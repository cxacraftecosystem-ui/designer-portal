import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { sliceStageBlocks, spliceStageBlocks, stableBlocks, stageBlockRange } from "@/components/designworkshop/report/stageSlice";
import { galleryColumns } from "@/components/designworkshop/report/previewModel";

/**
 * THE LIVE PREVIEW'S PURE HALF, AGAINST THE SHARED VECTORS.
 *
 * `shared/report-parity/stage-slice-vectors.json` is read here and by the handset's
 * `DwStageSliceTest`, so the two clients locate a stage — and splice a live copy of it into the
 * saved document — the same way. Behaviour only: no source text of either module is asserted.
 *
 * PURE NODE. Run: `npx playwright test e2e/stage-slice-unit.spec.ts --reporter=line`
 */

type Block = { type: string; level?: number; runs?: Array<{ text: string }> };

const vectors = JSON.parse(
  readFileSync(join(__dirname, "..", "..", "shared", "report-parity", "stage-slice-vectors.json"), "utf8")
) as {
  range: Array<{ name: string; blocks: string[]; title: string; expected: [number, number] | null }>;
  splice: Array<{ name: string; base: string[]; live: string[]; title: string; expected: string[] | null }>;
};

function block(spec: string): Block {
  const heading = /^H(\d):(.*)$/.exec(spec);
  if (heading) return { type: "HEADING", level: Number(heading[1]), runs: [{ text: heading[2] }] };
  if (spec === "COVER") return { type: "COVER" };
  return { type: "PARAGRAPH", runs: [{ text: spec }] };
}

function spec(b: Block): string {
  if (b.type === "HEADING") return `H${b.level}:${b.runs?.[0]?.text ?? ""}`;
  if (b.type === "COVER") return "COVER";
  return b.runs?.[0]?.text ?? "";
}

test.describe("stageBlockRange / sliceStageBlocks", () => {
  for (const vector of vectors.range) {
    test(vector.name, () => {
      const blocks = vector.blocks.map(block);
      expect(stageBlockRange(blocks, vector.title)).toEqual(vector.expected);
      const slice = sliceStageBlocks(blocks, vector.title);
      expect(slice === null ? null : slice.map(spec)).toEqual(
        vector.expected === null ? null : vector.blocks.slice(vector.expected[0], vector.expected[1])
      );
    });
  }
});

test.describe("spliceStageBlocks", () => {
  for (const vector of vectors.splice) {
    test(vector.name, () => {
      const out = spliceStageBlocks(vector.base.map(block), vector.live.map(block), vector.title);
      expect(out === null ? null : out.map(spec)).toEqual(vector.expected);
    });
  }
});

test.describe("stableBlocks", () => {
  test("unchanged blocks keep their identity, a changed one does not", () => {
    const before = ["H1:Setup", "a", "b"].map(block);
    const after = ["H1:Setup", "a", "b changed"].map(block);
    const out = stableBlocks(before, after);
    expect(out[0]).toBe(before[0]);
    expect(out[1]).toBe(before[1]);
    expect(out[2]).toBe(after[2]);
  });

  test("an insertion near the top does not mark everything below it as changed", () => {
    const before = ["H1:Setup", "a", "b", "c"].map(block);
    const after = ["H1:Setup", "new", "a", "b", "c"].map(block);
    const out = stableBlocks(before, after);
    expect(out[2]).toBe(before[1]);
    expect(out[3]).toBe(before[2]);
    expect(out[4]).toBe(before[3]);
  });

  test("an identical rebuild returns the previous array itself", () => {
    const before = ["H1:Setup", "a"].map(block);
    expect(stableBlocks(before, ["H1:Setup", "a"].map(block))).toBe(before);
  });
});

test.describe("galleryColumns — report_pdf._block_image_grid's column rule", () => {
  test("clamps to 1..4 and drops a column while a cell would be narrower than 45 mm", () => {
    expect(galleryColumns(3, 160)).toBe(3);
    expect(galleryColumns(4, 160)).toBe(3);
    expect(galleryColumns(6, 200)).toBe(4);
    expect(galleryColumns(0, 160)).toBe(1);
    expect(galleryColumns(2, 80)).toBe(1);
  });
});
