import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * Every tool the stage form loads on demand goes through `deferredTool`, which gives it a local
 * failure panel (so a script that cannot be fetched offline never takes the whole form down) and a
 * place in the warming queue (so the service worker holds it before the designer leaves coverage).
 *
 * Source assertions, for this repository's usual reason: there is no React renderer in
 * devDependencies. A plain `next/dynamic` in any of these files would bring the page-wide failure back.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const FILES: Record<string, string[]> = {
  "components/designworkshop/FieldInput.tsx": ["SignaturePad", "IdentityCardReader", "PhotoMeasureField", "SketchRectifyField", "SketchTraceField"],
  "components/designworkshop/StageReferenceField.tsx": ["WorkshopCodeScanner"],
  "components/designworkshop/InlineRecordDialog.tsx": ["ArtisanForm", "ProcessForm", "ProductForm", "ToolForm"],
  "app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx": ["StageDocumentPreview"]
};

for (const [file, tools] of Object.entries(FILES)) {
  test(`${file}: no next/dynamic, every on-demand tool is a deferredTool and is warmed`, () => {
    const source = read(...file.split("/"));
    expect(source).not.toContain('from "next/dynamic"');
    expect(source).toContain('from "@/components/designworkshop/deferredTool"');
    expect(source).toContain("registerWarm(");
    for (const tool of tools) {
      expect(source).toMatch(new RegExp(String.raw`const ${tool} = (memo\(\s*)?deferredTool\(`));
    }
  });
}

test("the stage page starts warming once the form is up", () => {
  const page = read("app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx");
  expect(page).toContain("useEffect(() => (loading ? undefined : warmDeferredTools()), [loading]);");
});

test("the preview's placeholder closes for real and says so when its script cannot be fetched", () => {
  const page = read("app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx");
  expect(page).not.toContain("onOpenChange={() => undefined}");
  expect(page).toContain("placeholder: (props) => <StagePreviewShell open={props.open ?? true} onOpenChange={props.onOpenChange ?? closePreviewNoop} />");
  expect(page).toContain("failure={<ToolLoadFailed retry={retry} />}");
});

test("the failure sentence is plain and names what to do", () => {
  const source = read("components", "designworkshop", "deferredTool.tsx");
  expect(source).toContain("It needs a connection the first time it is used on this device.");
  expect(source).toContain("Try again");
});
