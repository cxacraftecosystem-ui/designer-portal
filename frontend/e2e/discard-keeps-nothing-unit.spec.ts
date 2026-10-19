import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * "DISCARD" MUST NOT BRING THE DRAFT BACK — the web half of the 0.0.20 emulator smoke finding.
 *
 * On Android the prompt left the screen without telling the form's on-disk draft, so the form's
 * flush on the way out wrote the typing back and the next visit restored it. The web forms behind
 * the same prompt hold their typing in React state only: nothing is written to the browser until
 * Save, and Save goes to the server or the outbox. So Discard, which resets that state and leaves,
 * leaves nothing behind to come back.
 *
 * This pins that. A form that starts keeping a local draft must also make Discard drop it, and this
 * spec fails the day the first half lands without the second. A source read, for the reason
 * `discarded-work-unit.spec.ts` gives: there is no React renderer in this repository.
 */

const GUARDED = [
  "app/(protected)/crafts/page.tsx",
  "app/(protected)/workshops/page.tsx",
  "app/(protected)/feedback/page.tsx",
  "app/(protected)/design-workshops/page.tsx",
  "app/(protected)/questionnaires/[id]/answer/page.tsx",
  "components/forms/ArtisanForm.tsx",
  "components/forms/ProductForm.tsx",
  "components/forms/ToolForm.tsx",
  "components/forms/ProcessForm.tsx",
  "components/designers/DesignerProfileForm.tsx",
  "components/feedback/FeedbackReportForm.tsx",
  "components/designworkshop/DesignWorkshopHeaderForm.tsx",
  "components/designworkshop/CustomSectionsEditor.tsx",
];

/** The source with comments removed, so a paragraph that names a store is not read as a call. */
function code(path: string): string {
  return readFileSync(join(__dirname, "..", path), "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:"'`])\/\/.*$/gm, "$1");
}

/** Every way a form could leave typing in the browser that outlives the screen. */
const LOCAL_WRITES = [
  /localStorage\s*\.\s*setItem/,
  /sessionStorage\s*\.\s*setItem/,
  /indexedDB\s*\.\s*open/,
  /\bputDraft\w*\s*\(/,
  /\bpatchDraft\w*\s*\(/,
  /\bensureDraft\s*\(/,
  /\bsaveDraft\w*\s*\(/,
];

test("every file listed is still behind the unsaved-changes prompt", () => {
  for (const path of GUARDED) {
    expect(code(path), path).toMatch(
      /UnsavedChangesDialog|useLeaveGuard|onDirtyChange/,
    );
  }
});

test("no form behind the prompt keeps typing in the browser, so Discard leaves nothing to restore", () => {
  for (const path of GUARDED) {
    const source = code(path);
    for (const write of LOCAL_WRITES) {
      expect(
        source,
        `${path} writes a local draft (${write}); make Discard drop it too`,
      ).not.toMatch(write);
    }
  }
});
