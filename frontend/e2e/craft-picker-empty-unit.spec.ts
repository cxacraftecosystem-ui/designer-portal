import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { workshopListNotice } from "@/lib/workshopOptions";

/**
 * AN EMPTY CRAFT REGISTER READS AS EMPTY; ONLY A FAILED READ SAYS IT COULD NOT LOAD.
 *
 * The 0.0.19 emulator smoke read "Couldn't load crafts" online on a deployment with no crafts yet.
 * The handset's half is `RegisterListNoticeTest`; this pins the shared sentence and the web artisan
 * form, whose craft picker said nothing at all in either state.
 */

test("no client tells anybody to type a craft name (REQUIREMENTS_2026-10-20 §2.4)", () => {
  const kotlin = readFileSync(
    join(__dirname, "../../android/app/src/main/java/com/designprototype/workshop/ui/WorkshopOptions.kt"),
    "utf8"
  );
  expect(kotlin).not.toContain("Type a new craft name");
  const web = readFileSync(join(__dirname, "../lib/workshopOptions.ts"), "utf8");
  expect(web).not.toContain("Type a new craft name");
});

test("a failed craft read says it could not load", () => {
  const failed = workshopListNotice(
    { kind: "failed" },
    { table: "field", noun: "crafts", scoped: false, accessList: false, online: true }
  );
  expect(failed).toContain("could not be loaded");
});

test("the artisan form picks its craft from the catalogue, which says its own load state", () => {
  // REQUIREMENTS_2026-10-20 §2.4: the craft list is the database's, and nothing is typed instead.
  // `CraftSelect` draws "Loading crafts…", the offline first-run sentence, or the no-match line.
  const form = readFileSync(join(__dirname, "../components/forms/ArtisanForm.tsx"), "utf8").replace(/\r\n/g, "\n");
  expect(form).toContain("<CraftSelect");
  expect(form).not.toContain("newCraftName");
  expect(form).not.toContain("Or new craft name");
  expect(form).toContain("craftCatalogue.loading");
});
