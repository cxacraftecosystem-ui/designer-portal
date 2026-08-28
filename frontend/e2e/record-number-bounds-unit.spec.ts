import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE BROWSER HALF OF THE NON-NEGATIVE BOUND — every number box on ProductForm and ToolForm.
 *
 * THE DEFECT THIS PINS. A length, a weight, a radius, a cost of making, a replacement cost: none of
 * them has a negative value that means anything, and the design-workshop registry already says so —
 * the fields these columns hydrate into (`product.lengthCm`, `product.costOfMaking`, `tool.lengthCm`,
 * `tool.cost`) are declared `min_value=0` in `stage_definitions.py`. Thirteen `type="number"` inputs
 * across these two forms carried no `min`, so "-40" was typed, accepted, stored, carried into a
 * stage, and refused there — on a row the repository itself had filled in. `yearsInUse` on ToolForm
 * was the single input that already had the bound, which is why the gap read as a decision.
 *
 * WHY `min` AND NOT A HAND-WRITTEN CHECK. Neither form sets `noValidate`, so the browser's own
 * constraint validation runs on submit: it blocks the save, focuses the offending input and names it
 * ("Value must be greater than or equal to 0"). That is a better refusal than anything this form
 * would hand-roll, and it costs one attribute.
 *
 * THE SERVER HALF IS THE OTHER HALF OF THE SAME FIX and is asserted by
 * `backend/tests/test_record_number_bounds.py`. One half alone is worse than neither: `min` does
 * nothing for Android, for the outbox replaying a queued body, or for anything speaking to the API
 * directly, and `ge=0` alone refuses a save the researcher was given no way to see coming.
 *
 * WHY THIS IS A SOURCE READ. This repository has no React renderer in its devDependencies —
 * Playwright is the whole of it — so mounting either form is not available. `existing-media-count-
 * unit.spec.ts` and `derived-fields-unit.spec.ts` read their subjects the same way and for the same
 * reason. What this cannot prove is that a browser enforces `min`; what it does prove is that the
 * attribute the browser enforces is on every box that needs it.
 */

const FORMS = join(__dirname, "..", "components", "forms");

const source = (file: string) => readFileSync(join(FORMS, file), "utf8");

/** Every rendered `type="number"` input in a file, as its own single line of source. */
function numberInputs(text: string): string[] {
  return text.split(/\r?\n/).filter((line) => line.includes('type="number"'));
}

/**
 * The boxes each form must bound, by the `name` the payload builder reads them under.
 *
 * Spelled out rather than derived from the file: a loop over "whatever inputs happen to be there"
 * would stay green if one were deleted, and this list is also the count the fix was measured
 * against — five on ProductForm and nine on ToolForm, of which `yearsInUse` was already bounded.
 *
 * ToolForm is TEN since 2026-08-27. `ToolDocumentation` gained a `heightInches` column that day and
 * the form draws a box for it beside `lengthInches` / `breadthInches`; `ToolCreate` / `ToolUpdate`
 * declare it with the same `ge=0` the other measurements carry, so it owes both halves of the bound
 * like every one of its neighbours. The plain `height` box is still here and still bounded — it was
 * not replaced, it holds what was typed into it in a unit the column cannot name.
 */
const BOUNDED: Record<string, string[]> = {
  "ProductForm.tsx": ["lengthInches", "breadthInches", "heightInches", "costOfMaking", "sellingPrice"],
  "ToolForm.tsx": [
    "yearsInUse",
    "height",
    "width",
    "lengthInches",
    "breadthInches",
    "heightInches",
    "thickness",
    "weight",
    "radius",
    "replacementCost"
  ]
};

for (const [file, names] of Object.entries(BOUNDED)) {
  test(`${file}: every number box declares min={0}`, () => {
    const lines = numberInputs(source(file));
    // No number input may escape the list above — a new one added without a bound has to fail here
    // rather than be quietly outside the loop.
    expect(lines).toHaveLength(names.length);
    for (const name of names) {
      const line = lines.find((candidate) => candidate.includes(`name="${name}"`));
      expect(line, `${file} has no number input named ${name}`).toBeTruthy();
      expect(line, `${name} accepts a negative value`).toContain("min={0}");
    }
  });

  test(`${file}: the server half's refusal has somewhere to be announced`, () => {
    // The half above is the browser's, and it only covers a researcher typing into this page. Every
    // other route to the same column — the outbox replaying a queued body, Android, a stored
    // negative edited on a client that PATCHes a delta — meets `ge=0` on the server instead, and its
    // "Input should be greater than or equal to 0" arrives as a string in `error`. This banner is
    // the whole of where that string is shown, so without `role="alert"` the server half of the fix
    // refuses the save and tells a screen-reader user nothing at all — which is the same defect
    // `process-refusal-a11y-unit.spec.ts` is about, on the two forms that change created it.
    const banner = source(file)
      .split(/\r?\n/)
      .find((line) => line.includes("bg-red-50") && line.includes("<div "));
    expect(banner, `${file} no longer renders its error banner as one line — has it been restructured?`).toBeTruthy();
    expect(banner).toContain('role="alert"');
    expect(banner).toContain("id={errorId}");
  });

  test(`${file}: the form did not turn off native constraint validation`, () => {
    // `min` is inert if the form opts out of the browser's validation, and that validation is the
    // only thing standing between a stored negative and a whole-record 422 on the edit path.
    //
    // The `<form>` TAG and not the whole file: the comment beside the bounds above names
    // `noValidate` in prose (that is what a comment explaining why an attribute is absent looks
    // like), so a file-wide assertion matches its own explanation and fails against the fix.
    const tag = source(file)
      .split(/\r?\n/)
      .find((line) => line.trim().startsWith("<form "));
    expect(tag, `${file} has no <form> tag — has it been restructured?`).toBeTruthy();
    expect(tag).not.toContain("noValidate");
  });
}
