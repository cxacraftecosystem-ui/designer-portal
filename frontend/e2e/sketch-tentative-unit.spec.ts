import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  TENTATIVE_FIELD_KEY,
  isTentativeRow,
  tentativeField,
  tentativeFirst
} from "@/lib/sketchTentative";
import type { DwEntity, DwRow } from "@/lib/designWorkshops";

function row(entryId: string, tentative?: unknown): DwRow {
  return tentative === undefined
    ? ({ _entryId: entryId, name: entryId } as DwRow)
    : ({ _entryId: entryId, name: entryId, [TENTATIVE_FIELD_KEY]: tentative } as DwRow);
}

const ids = (rows: readonly DwRow[]) => rows.map((r) => r._entryId);

test("tentative rows come first and each group keeps its own stored order", () => {
  const rows = [
    row("a"),
    row("b", true),
    row("c"),
    row("d", true),
    row("e", false),
    row("f", true)
  ];
  expect(ids(tentativeFirst(rows, isTentativeRow))).toEqual(["b", "d", "f", "a", "c", "e"]);
});

test("a list with nothing ticked is returned in exactly the order it arrived", () => {
  const rows = [row("a"), row("b", false), row("c")];
  expect(ids(tentativeFirst(rows, isTentativeRow))).toEqual(["a", "b", "c"]);
});

test("a list with everything ticked is returned in exactly the order it arrived", () => {
  const rows = [row("a", true), row("b", true), row("c", true)];
  expect(ids(tentativeFirst(rows, isTentativeRow))).toEqual(["a", "b", "c"]);
});

test("unticking restores the row to precisely the position it would have had", () => {
  const stored = [row("a"), row("b"), row("c"), row("d")];
  const ticked = stored.map((r) => (r._entryId === "d" ? { ...r, [TENTATIVE_FIELD_KEY]: true } : r));
  expect(ids(tentativeFirst(ticked, isTentativeRow))).toEqual(["d", "a", "b", "c"]);

  const unticked = ticked.map((r) =>
    r._entryId === "d" ? { ...r, [TENTATIVE_FIELD_KEY]: false } : r
  );
  expect(ids(tentativeFirst(unticked, isTentativeRow))).toEqual(ids(stored));
});

test("the partition is generic, so a caller can keep each row's original stage position", () => {
  const rows = [row("a"), row("b", true), row("c")];
  const pairs = rows.map((r, index) => ({ row: r, index }));
  const ordered = tentativeFirst(pairs, (pair) => isTentativeRow(pair.row));
  expect(ordered.map((pair) => pair.index)).toEqual([1, 0, 2]);
});

test("the predicate accepts a real true and refuses everything a checkbox does not draw as ticked", () => {
  expect(isTentativeRow(row("a", true))).toBe(true);
  expect(isTentativeRow(row("a", false))).toBe(false);
  expect(isTentativeRow(row("a"))).toBe(false);
  expect(isTentativeRow(row("a", null))).toBe(false);
  expect(isTentativeRow(row("a", "true"))).toBe(false);
  expect(isTentativeRow(row("a", 1))).toBe(false);
  expect(isTentativeRow(undefined)).toBe(false);
  expect(isTentativeRow(null)).toBe(false);
});

test("the field is found by its registry declaration, and absence is an ordinary state", () => {
  const entity = (fields: DwEntity["fields"]): DwEntity => ({
    key: "sketch",
    name: "DwSketch",
    cardinality: "COLLECTION",
    title: "Sketches",
    description: "",
    parent: "",
    labelField: "name",
    fields
  });
  const declared = {
    key: TENTATIVE_FIELD_KEY,
    label: "Tentative",
    type: "BOOL",
    tier: "BASIC",
    required: false
  } as DwEntity["fields"][number];

  expect(tentativeField(entity([declared]))?.label).toBe("Tentative");
  expect(tentativeField(entity([]))).toBeNull();
  expect(tentativeField(null)).toBeNull();
  expect(tentativeField(entity([{ ...declared, deprecated: true }]))).toBeNull();
  expect(tentativeField(entity([{ ...declared, type: "TEXT" }]))).toBeNull();
});

test("the two surfaces that write an order back do not partition the list they draw", () => {
  const root = join(process.cwd(), "components");
  for (const file of ["designworkshop/EntityForm.tsx", "sketches/ReviewPanel.tsx"]) {
    const source = readFileSync(join(root, file), "utf8");
    expect(source, `${file} must not partition the array it persists`).not.toContain("tentativeFirst");
    expect(source, `${file} must still name the tentative flag`).toContain("sketchTentative");
  }
});
