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

test("the artisan form says empty only for a read that answered, and failure only for one that failed", () => {
  const form = readFileSync(join(__dirname, "../components/forms/ArtisanForm.tsx"), "utf8").replace(/\r\n/g, "\n");
  const notice = form.slice(form.indexOf("const craftNotice ="), form.indexOf("const craftNotice =") + 500);
  expect(notice).toContain('craftListState === "loaded"');
  expect(notice).toContain("craftsEmptyLine(canManageCrafts(user))");
  expect(notice).toContain('{ kind: "failed" }');
  // Drawn on the page, not only inside a panel a disabled control cannot open.
  expect(form).toContain("emptyLabel={craftNotice || undefined}");
  expect(form).toMatch(/\{craftNotice \? <p className="mt-1 text-xs leading-5 text-ink-500">\{craftNotice\}<\/p> : null\}/);
});
