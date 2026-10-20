import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { craftsEmptyLine, workshopListNotice } from "@/lib/workshopOptions";

/**
 * AN EMPTY CRAFT REGISTER READS AS EMPTY; ONLY A FAILED READ SAYS IT COULD NOT LOAD.
 *
 * The 0.0.19 emulator smoke read "Couldn't load crafts" online on a deployment with no crafts yet.
 * The handset's half is `RegisterListNoticeTest`; this pins the shared sentence and the web artisan
 * form, whose craft picker said nothing at all in either state.
 */

test("the empty sentence names the next move the role allows, and matches the handset", () => {
  expect(craftsEmptyLine(true)).toBe("No crafts yet. Type a new craft name below.");
  expect(craftsEmptyLine(false)).toBe("No crafts yet. Ask a professor or admin to add one.");

  const kotlin = readFileSync(
    join(__dirname, "../../android/app/src/main/java/com/designprototype/workshop/ui/WorkshopOptions.kt"),
    "utf8"
  );
  expect(kotlin).toContain(`"${craftsEmptyLine(true)}"`);
  expect(kotlin).toContain(`"${craftsEmptyLine(false)}"`);
});

test("the empty sentence is never the failure sentence", () => {
  const failed = workshopListNotice(
    { kind: "failed" },
    { table: "field", noun: "crafts", scoped: false, accessList: false, online: true }
  );
  expect(failed).toContain("could not be loaded");
  expect(failed).not.toBe(craftsEmptyLine(true));
  expect(craftsEmptyLine(true)).not.toMatch(/load/i);
  expect(craftsEmptyLine(false)).not.toMatch(/load/i);
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
