import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

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

function code(path: string): string {
  return readFileSync(join(__dirname, "..", path), "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:"'`])\/\/.*$/gm, "$1");
}

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
