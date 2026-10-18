import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { choicesPayload, defaultChoices, resultingValue, type MergeField } from "@/lib/duplicates";

/*
  THE MERGE SCREEN'S FIELD TABLE. Defaults follow the kept record (or the other side where it is
  empty), "Both" joins two texts, and locked rows (Aadhaar, Pehchan) are never sent as a choice.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

const fields: MergeField[] = [
  { key: "name", label: "Name", a: "Lakshmi Devi", b: "Laxmi Devi", default: "A", options: ["A", "B"], defaults: { A: "A", B: "B" } },
  { key: "phone", label: "Phone", a: null, b: "9829012345", default: "B", options: ["A", "B"], defaults: { A: "B", B: "B" } },
  { key: "notes", label: "Notes", a: "Old blocks", b: "Dabu", default: "BOTH", options: ["A", "B", "BOTH"], defaults: { A: "BOTH", B: "BOTH" } },
  { key: "aadhaar", label: "Aadhaar", a: null, b: "XXXX XXXX 9012", default: "B", options: ["B"], locked: true }
];

test("defaults follow the survivor, and the server's per-survivor defaults win", () => {
  expect(defaultChoices(fields, "A")).toEqual({ name: "A", phone: "B", notes: "BOTH", aadhaar: "B" });
  expect(defaultChoices(fields, "B")).toEqual({ name: "B", phone: "B", notes: "BOTH", aadhaar: "B" });
});

test("without server defaults an empty side yields to the other", () => {
  const bare = fields.map(({ defaults: _ignored, ...rest }) => rest);
  expect(defaultChoices(bare, "A").phone).toBe("B");
  expect(defaultChoices(bare, "A").name).toBe("A");
});

test("Both joins the two texts", () => {
  expect(resultingValue(fields[2], "BOTH")).toBe("Old blocks\nDabu");
  expect(resultingValue(fields[0], "B")).toBe("Laxmi Devi");
});

test("locked rows and choices a field does not offer are not sent", () => {
  expect(choicesPayload(fields, { name: "B", phone: "A", notes: "BOTH", aadhaar: "A" })).toEqual({ name: "B", phone: "A", notes: "BOTH" });
  expect(choicesPayload(fields, { name: "BOTH" })).toEqual({});
});

test("the table draws a lock, not a radio, on a locked row", () => {
  const table = read("components/duplicates/MergeFieldTable.tsx");
  expect(table).toContain("field.locked ?");
  expect(table).toContain("<Lock");
  const page = read("app/(protected)/admin/duplicates/[id]/page.tsx");
  expect(page).toContain("Merged. Undo within 30 days.");
  expect(page).toContain("disabled={busy || blockers.length > 0}");
});
