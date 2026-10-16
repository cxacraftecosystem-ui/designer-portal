import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { fieldReadsRow } from "@/components/designworkshop/FieldInput";
import type { DwEntity } from "@/lib/designWorkshops";

/**
 * THE STAGE FORM RE-DRAWS ONLY THE FIELD BEING TYPED INTO — and never skips one that reads a sibling.
 *
 * `StageFieldInput` skips a field whose own props did not change, and it ignores the record (`row`)
 * for a control `fieldReadsRow` says never reads it. That is what took a keystroke on a busy stage
 * from re-drawing every box of the record to re-drawing one (see `e2e/stage-form-speed.spec.ts`). The
 * danger is the other direction: a control that DOES read a sibling, wrongly classified, would show a
 * stale answer — a derived amount that does not move, a date range that stops checking its other end.
 * So this file holds the classification to the registry and to `FieldInput`'s own source.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");
const SCHEMA = join(ROOT, "..", "android", "app", "src", "main", "assets", "design-workshop-schema.json");
const registry = JSON.parse(readFileSync(SCHEMA, "utf8")) as { stages: Array<{ key: string; entities: DwEntity[] }> };
const pairs = registry.stages.flatMap((stage) => stage.entities.flatMap((entity) => entity.fields.map((field) => ({ entity, field }))));

test("the registry dump is the one this spec reasons over", () => {
  expect(pairs.length).toBeGreaterThan(300);
});

test("a reference, a multi-select and a derived amount are always re-drawn with their record", () => {
  for (const { entity, field } of pairs) {
    if (field.type === "REF" || field.type === "MULTI_ENUM") expect(fieldReadsRow(entity, field), `${entity.key}.${field.key}`).toBe(true);
    if (field.derivedKind) expect(fieldReadsRow(entity, field), `${entity.key}.${field.key} is derived`).toBe(true);
  }
});

test("prose, rich text and plain choices are not re-drawn for a sibling's keystroke", () => {
  const plain = pairs.filter(({ field }) => ["LONG_TEXT", "RICH_TEXT", "BOOL", "ENUM", "TAGS", "TIME"].includes(field.type));
  expect(plain.length).toBeGreaterThan(50);
  for (const { entity, field } of plain) expect(fieldReadsRow(entity, field), `${entity.key}.${field.key}`).toBe(false);
});

test("an unknown field type is assumed to read its record", () => {
  const [{ entity, field }] = pairs;
  expect(fieldReadsRow(entity, { ...field, type: "SOMETHING_NEW" as never })).toBe(true);
});

test("every read of `row` in FieldInput is one fieldReadsRow was written against", () => {
  /*
    A TRIPWIRE, AND THE NUMBER IS THE POINT. `fieldReadsRow` answers branch by branch; a new branch
    of `FieldInput` that reads `row` would be invisible to it, and the field would quietly stop
    following its sibling. Eleven reads today: the address lists, the workshop-name list, the media
    note, the two derived placeholders, the date range, the multi-select, the reference picker, and the
    three photograph tools' sibling reads (sketch sources, identity target, measure targets). If this
    count moves, read the new `row` use, make `fieldReadsRow` answer true for its branch, and then
    change the number.
  */
  const source = read("components/designworkshop/FieldInput.tsx");
  const body = source
    .slice(source.indexOf("export function FieldInput("), source.indexOf("export function fieldReadsRow("))
    .split("\n")
    .filter((line) => !/^\s*(\*|\/\/|\/\*)/.test(line))
    .join("\n");
  expect(body.match(/\brow(\[|\.[a-zA-Z]|\}|\))/g)?.length).toBe(11);
});

test("the stage grid mounts the memoised field, and the memo keeps the newest callbacks", () => {
  const grid = read("components/designworkshop/EntityForm.tsx");
  expect(grid).toContain("<StageFieldInput");
  expect(grid).not.toContain("<FieldInput");
  const field = read("components/designworkshop/FieldInput.tsx");
  // The newest props are recorded before any effect or handler of the same commit can call back.
  expect(field).toMatch(/useInsertionEffect\(\(\) => \{\s*latest\.current = props;\s*\}\);/);
  expect(field).toContain("const onChange = useCallback((value: DwValue) => latest.current.onChange(value), []);");
  expect(field).toContain("const onPatch = useCallback((values: Record<string, DwValue>) => latest.current.onPatch(values), []);");
});
