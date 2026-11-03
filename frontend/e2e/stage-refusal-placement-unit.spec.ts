import { expect, test } from "@playwright/test";

import { ROW_REFUSAL_KEY, rowRefusal, withoutRowRefusal } from "@/components/designworkshop/EntityForm";
import {
  countRefusedAnswers,
  placeStageErrors,
  refusedAnswersToShow,
  strandedRefusals,
  type DwRowKey
} from "@/lib/designWorkshopStore";

const ROW_KEYS: DwRowKey[] = [
  null,
  { entityKey: "tool", rowIndex: 0 },
  { entityKey: "tool", rowIndex: 1 },
  { entityKey: "tool", rowIndex: 2 }
];

test("a refusal against a sent position is re-addressed to the row on screen", () => {
  const { decoded, unplaced } = placeStageErrors({ "tool[3]": { name: "This field is required" } }, ROW_KEYS);

  expect(decoded).toEqual({ "tool[2]": { name: "This field is required" } });
  expect(unplaced).toEqual([]);
});

test("a bare scope is left alone — the singleton and the custom container have no row", () => {
  const { decoded, unplaced } = placeStageErrors(
    { traditionalProcess: { stepCount: "Must be a whole number" }, _custom: { dyesrc: "Too long" } },
    ROW_KEYS
  );

  expect(decoded).toEqual({
    traditionalProcess: { stepCount: "Must be a whole number" },
    _custom: { dyesrc: "Too long" }
  });
  expect(unplaced).toEqual([]);
});

test("an index past the end of what was sent is REPORTED, not dropped", () => {
  const { decoded, unplaced } = placeStageErrors({ "tool[9]": { name: "This field is required" } }, ROW_KEYS);

  expect(decoded).toEqual({});
  expect(unplaced).toEqual(["tool[9].name: This field is required"]);
});

test("a scope naming a different entity than the entry at that position is REPORTED, not misplaced", () => {
  const { decoded, unplaced } = placeStageErrors({ "rawMaterial[2]": { source: "Unknown supplier" } }, ROW_KEYS);

  expect(decoded).toEqual({});
  expect(unplaced).toEqual(["rawMaterial[2].source: Unknown supplier"]);
});

test("a bracketed scope resolving to a singleton's slot is REPORTED, not drawn on row 0", () => {
  const { decoded, unplaced } = placeStageErrors({ "tool[0]": { name: "This field is required" } }, ROW_KEYS);

  expect(decoded).toEqual({});
  expect(unplaced).toEqual(["tool[0].name: This field is required"]);
});

test("every field of an unplaceable scope is reported, not just the first", () => {
  const { unplaced } = placeStageErrors(
    { "tool[9]": { name: "This field is required", cost: "Must be a number", source: "Too long" } },
    ROW_KEYS
  );

  expect(unplaced).toHaveLength(3);
  expect(unplaced).toEqual([
    "tool[9].name: This field is required",
    "tool[9].cost: Must be a number",
    "tool[9].source: Too long"
  ]);
});

test("a scope refused with no field map at all is still reported", () => {
  const { decoded, unplaced } = placeStageErrors({ "tool[1]": {} }, ROW_KEYS);

  expect(decoded).toEqual({});
  expect(unplaced).toEqual(["tool[1]: couldn't be saved (no reason given)"]);
});

test("placed and unplaceable refusals in one response are both accounted for", () => {
  const { decoded, unplaced } = placeStageErrors(
    {
      "tool[1]": { name: "This field is required" },
      "tool[9]": { cost: "Must be a number" },
      traditionalProcess: { stepCount: "Must be a whole number" }
    },
    ROW_KEYS
  );

  expect(decoded).toEqual({
    "tool[0]": { name: "This field is required" },
    traditionalProcess: { stepCount: "Must be a whole number" }
  });
  expect(unplaced).toEqual(["tool[9].cost: Must be a number"]);
  expect(Object.keys(decoded).length + unplaced.length).toBe(3);
});

test("an empty error map is not a refusal", () => {
  for (const errors of [{}, null, undefined]) {
    const { decoded, unplaced } = placeStageErrors(errors, ROW_KEYS);
    expect(decoded).toEqual({});
    expect(unplaced).toEqual([]);
  }
});

test("the count sums the field maps, because a designer counts ANSWERS", () => {
  expect(countRefusedAnswers({ "tool[0]": { name: "required", cost: "not a number", source: "too long" } })).toBe(3);
});

test("the count agrees with the handset on the same response", () => {
  const errors = {
    traditionalProcess: { stepCount: "Must be a whole number" },
    "tool[1]": { name: "required", cost: "not a number" },
    _custom: { dyesrc: "too long" }
  };

  expect(countRefusedAnswers(errors)).toBe(4);
  expect(Object.keys(errors).length).toBe(3);
});

test("a scope with an empty field map counts as one, so a refusal can never sum to zero", () => {
  expect(countRefusedAnswers({ "tool[0]": {} })).toBe(1);
  expect(countRefusedAnswers({ "tool[0]": {}, "tool[1]": { name: "required" } })).toBe(2);
});

test("no refusals is zero, so a clean save stays clean", () => {
  expect(countRefusedAnswers({})).toBe(0);
  expect(countRefusedAnswers(null)).toBe(0);
  expect(countRefusedAnswers(undefined)).toBe(0);
});

const ENTITIES = [
  { key: "traditionalProcess", cardinality: "SINGLETON" },
  { key: "tool", cardinality: "COLLECTION" },
  { key: "rawMaterial", cardinality: "COLLECTION" }
];

const ROWS = { tool: [{}, {}, {}], rawMaterial: [{}] };

test("a refusal on a row that is on screen is left to the box", () => {
  expect(strandedRefusals({ "tool[2]": { name: "required" } }, ENTITIES, ROWS)).toEqual([]);
  expect(strandedRefusals({ traditionalProcess: { stepCount: "bad" } }, ENTITIES, ROWS)).toEqual([]);
  expect(strandedRefusals({ _custom: { dyesrc: "too long" } }, ENTITIES, ROWS)).toEqual([]);
});

test("a refusal whose row was DELETED under it is reported instead of vanishing", () => {
  const afterDelete = { tool: [{}, {}], rawMaterial: [{}] };
  expect(strandedRefusals({ "tool[2]": { name: "This field is required" } }, ENTITIES, afterDelete)).toEqual([
    "tool[2].name: This field is required"
  ]);
});

test("emptying a collection strands every refusal that was marked in it", () => {
  const emptied = { tool: [], rawMaterial: [{}] };
  expect(
    strandedRefusals({ "tool[0]": { name: "required", cost: "not a money" } }, ENTITIES, emptied)
  ).toEqual(["tool[0].name: required", "tool[0].cost: not a money"]);
});

test("a scope naming an entity this stage does not declare is reported", () => {
  expect(strandedRefusals({ "sketch[0]": { title: "required" } }, ENTITIES, ROWS)).toEqual([
    "sketch[0].title: required"
  ]);
  expect(strandedRefusals({ somethingElse: { title: "required" } }, ENTITIES, ROWS)).toEqual([
    "somethingElse.title: required"
  ]);
});

test("a bare key naming a COLLECTION is reported — a table draws no entity-level error", () => {
  expect(strandedRefusals({ tool: { name: "required" } }, ENTITIES, ROWS)).toEqual(["tool.name: required"]);
});

test("marked and stranded are complements, so no refusal falls between them", () => {
  const errors = {
    traditionalProcess: { stepCount: "bad" },
    "tool[0]": { name: "required" },
    "tool[5]": { cost: "not a money" },
    "rawMaterial[0]": { source: "required" },
    "sketch[1]": { title: "required" }
  };
  const rows = { tool: [{}, {}], rawMaterial: [{}] };
  const strandedLines = strandedRefusals(errors, ENTITIES, rows);

  expect(strandedLines).toEqual(["tool[5].cost: not a money", "sketch[1].title: required"]);
  const drawnAnswers = countRefusedAnswers(errors) - strandedLines.length;
  expect(drawnAnswers).toBe(3);
});

test("a response captured from the running API decodes as expected", () => {
  const twoRowsOneFieldEach = {
    "tool[1]": { cost: "Cost is not a valid money" },
    "tool[2]": { cost: "Cost is not a valid money" }
  };
  const { decoded, unplaced } = placeStageErrors(twoRowsOneFieldEach, [
    null,
    { entityKey: "tool", rowIndex: 0 },
    { entityKey: "tool", rowIndex: 1 }
  ]);
  expect(decoded).toEqual({
    "tool[0]": { cost: "Cost is not a valid money" },
    "tool[1]": { cost: "Cost is not a valid money" }
  });
  expect(unplaced).toEqual([]);
  expect(countRefusedAnswers(twoRowsOneFieldEach)).toBe(2);

  const oneRowTwoFields = {
    "tool[0]": {
      toolFamily: "Tool family: 'NOT_A_REAL_FAMILY' is not a valid option",
      cost: "Cost is not a valid money"
    }
  };
  expect(countRefusedAnswers(oneRowTwoFields)).toBe(2);
  expect(Object.keys(oneRowTwoFields).length).toBe(1);
});

test("the server's count is used, so a laptop and a handset print one number", () => {
  const errors = { "tool[0]": { name: "required", cost: "not a number" } };
  expect(refusedAnswersToShow(2, errors)).toBe(2);
});

test("a scope refused with an empty field map is never reported as a clean save", () => {
  const emptyMap = { costing: {} };
  expect(countRefusedAnswers(emptyMap)).toBe(1);
  expect(refusedAnswersToShow(0, emptyMap)).toBe(1);
});

test("a genuinely clean save still reports zero", () => {
  expect(refusedAnswersToShow(0, {})).toBe(0);
  expect(refusedAnswersToShow(0, null)).toBe(0);
});

test("a deployment older than the field falls back to counting locally", () => {
  expect(refusedAnswersToShow(undefined, { "tool[0]": { name: "required", cost: "bad" } })).toBe(2);
  expect(refusedAnswersToShow(undefined, {})).toBe(0);
});

test("the server's count wins where counting locally would return a character count", () => {
  const stringScope = { costing: "required" } as unknown as Record<string, Record<string, string>>;
  expect(countRefusedAnswers(stringScope)).toBe(8);
  expect(refusedAnswersToShow(1, stringScope)).toBe(1);
});

const ROW_CONFLICT = "Someone else saved this row first — reopen the stage to see the latest before saving again.";

test("the reserved key is the wire constant, spelled the same on all three clients", () => {
  expect(ROW_REFUSAL_KEY).toBe("_row");
});

test("a row-level refusal is read out of the row's own error map", () => {
  expect(rowRefusal({ [ROW_REFUSAL_KEY]: ROW_CONFLICT })).toBe(ROW_CONFLICT);
  expect(rowRefusal({ name: "This field is required", [ROW_REFUSAL_KEY]: ROW_CONFLICT })).toBe(ROW_CONFLICT);
});

test("no row-level refusal is null, so a field-only refusal draws no card band", () => {
  expect(rowRefusal(undefined)).toBeNull();
  expect(rowRefusal({})).toBeNull();
  expect(rowRefusal({ name: "This field is required" })).toBeNull();
  expect(rowRefusal({ [ROW_REFUSAL_KEY]: "" })).toBeNull();
});

test("the sentence is passed through verbatim and never rewritten", () => {
  expect(rowRefusal({ [ROW_REFUSAL_KEY]: ROW_CONFLICT })).toBe(ROW_CONFLICT);
});

test("stripping the row refusal leaves the field messages exactly as they were", () => {
  const errors = { dyesrc: "Too long", [ROW_REFUSAL_KEY]: ROW_CONFLICT };
  expect(withoutRowRefusal(errors)).toEqual({ dyesrc: "Too long" });
  expect(errors[ROW_REFUSAL_KEY]).toBe(ROW_CONFLICT);
  const untouched = { dyesrc: "Too long" };
  expect(withoutRowRefusal(untouched)).toBe(untouched);
  expect(withoutRowRefusal(undefined)).toBeUndefined();
});

test("a row refusal is counted as an answer, and now has somewhere to be drawn", () => {
  const errors = { "tool[1]": { [ROW_REFUSAL_KEY]: ROW_CONFLICT } };
  expect(countRefusedAnswers(errors)).toBe(1);
  expect(strandedRefusals(errors, ENTITIES, ROWS)).toEqual([]);
  expect(rowRefusal(errors["tool[1]"])).toBe(ROW_CONFLICT);
});

test("a row refusal against a row that is NOT on screen is still stranded, not swallowed", () => {
  const errors = { "tool[7]": { [ROW_REFUSAL_KEY]: ROW_CONFLICT } };
  expect(strandedRefusals(errors, ENTITIES, ROWS)).toEqual([`tool[7]._row: ${ROW_CONFLICT}`]);
});
