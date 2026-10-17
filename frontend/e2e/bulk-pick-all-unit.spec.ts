import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { BULK_ALL_LABEL, BULK_ALL_NOTHING_LEFT, bulkAllLabel, remainingOptions } from "@/components/designworkshop/bulkPick";

/*
  B3 — "All participants" / "All final products" beside a stage list's bulk picker, on both clients.
  The shortcut is for the workshop's OWN records only; the artisan register never gets one.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

test("the shortcut is offered for the workshop's own lists and never for the artisan register", () => {
  expect(bulkAllLabel("DwParticipant")).toBe("All participants");
  expect(bulkAllLabel("DwFinalProduct")).toBe("All final products");
  expect(bulkAllLabel("DwSketch")).toBe("All sketches");
  expect(bulkAllLabel("Artisan")).toBeNull();
  expect(bulkAllLabel("toString")).toBeNull();
  expect(bulkAllLabel(undefined)).toBeNull();
});

test("an All press adds only what is not on the list, once each, in the list's order", () => {
  const options = [{ id: "a" }, { id: "b" }, { id: "c" }, { id: "b" }, { id: "" }];
  expect(remainingOptions(options, ["a"]).map((option) => option.id)).toEqual(["b", "c"]);
  expect(remainingOptions(options, ["a", "b", "c"])).toEqual([]);
});

test("the picker draws the shortcut, says when nothing is left, and the stage form passes the label", () => {
  const picker = read("components/designworkshop/StageReferenceField.tsx");
  expect(picker).toContain("allLabel?: string | null;");
  expect(picker).toContain("setAllNote(BULK_ALL_NOTHING_LEFT)");
  expect(picker).toContain("remainingOptions(payload.options, alreadyChosen)");
  expect(read("components/designworkshop/EntityForm.tsx")).toContain("allLabel={bulkAllLabel(bulkField.refModel)}");
  expect(BULK_ALL_NOTHING_LEFT).toBe("Everything is already on this list.");
});

test("Android carries the same labels and the same bulk-field rule", () => {
  const kotlin = readFileSync(
    join(ROOT, "..", "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwBulkPick.kt"),
    "utf8"
  );
  for (const [model, label] of Object.entries(BULK_ALL_LABEL)) {
    expect(kotlin).toContain(`"${model}" -> "${label}"`);
  }
  expect(kotlin).toContain('field.tier == "BASIC"');
  expect(kotlin).toContain("field.refFilterBy.isBlank()");
  expect(kotlin).toContain("refs.singleOrNull()");
  expect(kotlin).toContain("Delete rows to make room.");
  expect(read("components/designworkshop/EntityForm.tsx")).toContain("Delete rows to make room.");
  const stage = readFileSync(
    join(ROOT, "..", "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/StageScreen.kt"),
    "utf8"
  );
  expect(stage).toContain(`"${BULK_ALL_NOTHING_LEFT}"`);
  expect(stage).toContain("dwBulkAllLabel(bulkField.refModel)");
});
