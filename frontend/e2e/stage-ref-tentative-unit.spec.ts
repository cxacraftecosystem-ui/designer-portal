import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { scopeNoticeLines } from "@/components/designworkshop/StageReferenceField";
import type { DwField, DwReferenceOption, DwReferencePayload } from "@/lib/designWorkshops";

const ROOT = join(__dirname, "..");
const source = readFileSync(join(ROOT, "components/designworkshop/StageReferenceField.tsx"), "utf8").replace(/\r\n/g, "\n");

const END_OF_FUNCTION = ["", "}", ""].join("\n");

const SKETCH_REF = {
  key: "sketchRef",
  label: "From sketch",
  type: "REF",
  refModel: "DwSketch",
  refScope: "WORKSHOP"
} as unknown as DwField;

function option(id: string, tentative?: boolean): DwReferenceOption {
  const base: DwReferenceOption = { id, label: id, sublabel: "", data: {} };
  return tentative === undefined ? base : { ...base, tentative };
}

function payload(overrides: Partial<DwReferencePayload> = {}): DwReferencePayload {
  return {
    model: "DwSketch",
    scope: "WORKSHOP",
    scopedToWorkshop: true,
    filtered: false,
    truncated: false,
    tentativeFirst: true,
    tentativeLabel: "Tentative",
    options: [option("a", true), option("b", false)],
    ...overrides
  };
}

test("a reordered list says why, in the registry's own word", () => {
  expect(scopeNoticeLines(SKETCH_REF, payload())).toContain(
    "“Tentative” rows come first; the rest keep their stage order."
  );
});

test("the word is whatever the registry calls it, never a literal in the component", () => {
  const lines = scopeNoticeLines(SKETCH_REF, payload({ tentativeLabel: "Not settled" }));
  expect(lines[0]).toBe("“Not settled” rows come first; the rest keep their stage order.");
});

test("a picker showing nothing ticked says nothing about the order", () => {
  expect(scopeNoticeLines(SKETCH_REF, payload({ options: [option("a", false), option("b", false)] }))).toEqual([]);
});

test("a server that predates the feature is not spoken for", () => {
  const older = payload({ tentativeFirst: undefined, tentativeLabel: undefined, options: [option("a"), option("b")] });
  expect(scopeNoticeLines(SKETCH_REF, older)).toEqual([]);
});

test("a model with no such flag draws no word even when the server answered", () => {
  const plain = payload({ model: "DwPrototype", tentativeFirst: false, tentativeLabel: "", options: [option("a")] });
  expect(scopeNoticeLines(SKETCH_REF, plain)).toEqual([]);
});

test("the cap sentence says what the cap now falls on first", () => {
  const lines = scopeNoticeLines(SKETCH_REF, payload({ truncated: true }));
  expect(lines).toContain(
    "First 50 matches, tentative first. Type more to narrow."
  );
  expect(lines.some((line) => line.includes("Type more to narrow"))).toBe(true);
});

test("an unordered truncated list keeps exactly the sentence it always had", () => {
  const lines = scopeNoticeLines(
    SKETCH_REF,
    payload({ truncated: true, tentativeFirst: false, tentativeLabel: "", options: [option("a")] })
  );
  expect(lines).toEqual(["First 50 matches. Type more to narrow."]);
});

test("the two sentences are ordered why-then-how-short, and both are said", () => {
  const lines = scopeNoticeLines(SKETCH_REF, payload({ truncated: true }));
  expect(lines).toHaveLength(2);
  expect(lines[0]).toContain("rows come first");
  expect(lines[1]).toContain("First 50 matches");
});

test("the picker draws the chip from the payload and never from a hardcoded word", () => {
  const body = source.slice(source.indexOf("function TentativeChip("));
  const chip = body.slice(0, body.indexOf(END_OF_FUNCTION) + END_OF_FUNCTION.length);
  expect(chip).toContain("option.tentative !== true");
  const markup = chip.slice(chip.indexOf("return ("));
  expect(markup).toContain("{word}");
  expect(markup).not.toMatch(/Tentative/);
  expect(source).toContain("payload.tentativeLabel");
  expect(source.split("tentativeWordFor(payload)").length - 1).toBe(2);
});

test("the picker sorts nothing of its own", () => {
  expect(source).not.toContain(".sort(");
  expect(source).not.toContain("tentativeFirst(");
});

test("both pickers in the file draw the chip, not just the single-select one", () => {
  expect(source.split("<TentativeChip ").length - 1).toBe(2);
});
