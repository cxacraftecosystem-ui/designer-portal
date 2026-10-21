import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { craftIndex, craftPath, groupCrafts, normaliseCatalogue, searchCraftCatalogue, type CraftCatalogue } from "@/lib/craftCatalogue";

/*
  THE STAGE-1 CRAFT PICKER IS THE CRAFT LIST'S OWN PICKER (owner, 2026-10-21).

  `workshopSetup.craftRef` used to draw the generic reference dropdown, whose rows showed a craft's
  name over its free-text category copy. It now draws `CraftSelect`, like every other craft field in
  both portals and like the Field Repository's Android picker: grouped under "Category ›
  Sub-category", and searched by category and sub-category names as well as the craft's own.
*/

const ROOT = join(__dirname, "..");
const FIXTURE = join(__dirname, "fixtures", "craft-catalogue.json");
const catalogue = normaliseCatalogue(JSON.parse(readFileSync(FIXTURE, "utf8"))) as CraftCatalogue;

test("the stage reference field hands a Craft field to CraftSelect", () => {
  const source = readFileSync(join(ROOT, "components/designworkshop/StageReferenceField.tsx"), "utf8");
  expect(source).toContain('import { CraftSelect } from "@/components/forms/CraftSelect";');
  expect(source).toMatch(/field\.refModel === "Craft" \? \(/);
  expect(source).toContain("<CraftSelect");
  expect(source).toContain("void chooseCraft(id, craft?.name ?? \"\")");
});

test("a sub-category name finds every craft under it, headed by its path", () => {
  const found = searchCraftCatalogue(catalogue, "Hand Block Printing");
  expect(found.length).toBeGreaterThan(1);
  const index = craftIndex(catalogue);
  const heading = "Textiles based Craft Category › Hand Block Printing";
  expect(found.some((craft) => craftPath(index, craft) === heading)).toBe(true);
  expect(groupCrafts(catalogue, found).some((group) => group.heading === heading)).toBe(true);
});

test("a category name finds crafts in that category", () => {
  const index = craftIndex(catalogue);
  const found = searchCraftCatalogue(catalogue, "Textiles based Craft Category");
  expect(found.length).toBeGreaterThan(10);
  expect(found.every((craft) => craftPath(index, craft).startsWith("Textiles based Craft Category › "))).toBe(true);
});
