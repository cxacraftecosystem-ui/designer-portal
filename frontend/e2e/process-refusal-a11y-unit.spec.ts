import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE SIX REFUSALS ProcessForm MAKES, AND THE FACT THAT NONE OF THEM REACHED A SCREEN READER.
 *
 * THE DEFECT THIS PINS. `submit()` refuses a save six ways — an empty process name, no artisan, no
 * product, the pre-process box ticked with nothing attached, no steps at all, and an empty step name
 * — and paints a red paragraph for each. A grep of the whole file for `aria-invalid`,
 * `aria-describedby` and `role="alert"` returned nothing at all. Every one of those paragraphs was a
 * colour and a position: no control was marked invalid, no control pointed at its reason, and
 * nothing announced itself when it appeared. Even the summary banner ("Please fill the required
 * fields highlighted above.") had no role — so a researcher using a screen reader pressed Save,
 * heard silence, and had no way to learn that the form had refused, let alone which box was at
 * fault. The focus ladder covered only two of the six, so the dropdown and media refusals moved
 * focus nowhere either.
 *
 * THE TREATMENT DIFFERS BY CONTROL, WHICH IS WHY A BLANKET SWEEP WOULD HAVE BEEN WRONG:
 *
 *  - The two text boxes (process name, step name) take `aria-invalid` AND `aria-describedby`.
 *    `TitleCasedInput` MERGES an incoming `aria-describedby` with its own "Will be saved as …" hint
 *    rather than replacing it, so both are announced.
 *  - The two dropdowns take `aria-describedby` and NOT `aria-invalid`. `SearchableSelect` documents
 *    the reason on its `describedBy` prop: the trigger is a `<button>`, `aria-invalid` is not
 *    supported on the `button` role, and setting it would read in the source as a mark while being
 *    ignored by every screen reader.
 *  - The steps refusal gets `role="alert"` and an id and nothing more, because there is nothing for
 *    the id to bind to: "add at least one step" refuses a section that is by definition empty.
 *  - The media refusal got the same treatment for the same stated reason — "`MediaCaptureField`
 *    accepts no `aria-describedby`" — AND THAT WAS A GAP RATHER THAN A CONCLUSION. It has one now,
 *    and the card binds it: the section is a named `role="group"`, described by its own paragraph
 *    plus whatever the caller passes. The role and the binding answer different moments and both
 *    are kept — the live region speaks when the refusal appears, the description speaks again to a
 *    researcher who tabs back to the card to act on it, which is the only one of the two that is
 *    still available a minute later.
 *
 * AND `role="alert"` IS NOT SPRAYED ON ALL SIX. Refusing an empty form mounts the banner and all six
 * paragraphs in one commit; assertive regions interrupt each other, so seven at once is a queue in
 * which the earliest — the banner — is the likeliest to be cut off. The role is spent only where
 * nothing else can carry the sentence: the banner, the two refusals with no control (media, steps),
 * and the two whose control the focus ladder cannot reach (artisan, product). The process name and
 * step name are the ladder's two rungs, so their paragraph is read on arrival as the input's
 * description and an alert would only say it twice. `submit()` states this under ALERT OR
 * DESCRIPTION. The assertions below pin BOTH halves, so a later sweep cannot quietly flip either.
 *
 * WHY THE DROPDOWNS ARE `Dropdown` AND NOT `FormControls.Select` — AND WHY THAT REASON HAS CHANGED.
 * It used to be that `Select` DROPPED the description: it forwarded only value/onChange/options/
 * disabled/className/ariaLabel, so an `aria-describedby` handed to it landed in a `...rest` spread
 * that these two call sites, passing no `name`, did not even render. The refusal would have looked
 * bound in the source and announced nothing, which is the precise failure this whole spec is about.
 *
 * That was a defect in `Select` affecting every caller in the app, not a fact about this form, and
 * it has been fixed at the source: `FormControls.Select` now translates `aria-describedby` into the
 * dropdown's `describedBy`. What still keeps these two on `Dropdown` is the shape of the data —
 * `Select` builds its list from `<option>` CHILDREN and reports a synthetic `<select>` event, while
 * both pickers here hold an options ARRAY and want the picked value. The assertion below therefore
 * still holds, and still guards the same regression (going back to `Select` unbinds nothing now,
 * but a hand-rolled `<select>` or a bare `<button>` would), while the reason it gives is the true
 * one. A THIRD picker on this form should reach for `Select` like everywhere else.
 *
 * NOT DONE, DELIBERATELY: the focus ladder for the dropdowns. No `id` or `ref` is plumbed through
 * the Select → Dropdown → SearchableSelect chain, so `document.getElementById(focusId)?.focus()` has
 * nothing to aim at. Half-building it — an id that resolves to no element — would be the same class
 * of defect as the paragraphs this fixes.
 *
 * WHY THIS IS A SOURCE READ. This repository has no React renderer in its devDependencies —
 * Playwright is the whole of it — so mounting the form is not available; `existing-media-count-
 * unit.spec.ts` reads its subject the same way and for the same reason. What this cannot prove is
 * that a screen reader speaks the sentence; what it does prove is that the sentence is a live region
 * with an id, and that the controls able to carry a description carry theirs.
 *
 * Every assertion below fails against the file as it was.
 */

const SOURCE = () => readFileSync(join(__dirname, "..", "components", "forms", "ProcessForm.tsx"), "utf8");

/** The rendered lines only — comments in this file quote the attributes it is about. */
function rendered(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"));
}

test("every refusal paragraph carries an id, and the right four are live regions", () => {
  const lines = rendered(SOURCE()).filter((line) => line.includes("text-error-600") && line.includes("<p "));
  // The six refusals: name, artisan, product, pre-process media, steps, step name. `productLoadError`
  // is a seventh red paragraph on this form and is NOT one of them — it reports a list that failed to
  // load, not an answer that was refused — so the count is deliberately not "every red <p>".
  const refusals = lines.filter((line) => line.includes("id="));
  expect(refusals.length).toBeGreaterThanOrEqual(6);

  // THE ID IS THE HALF THAT IS UNCONDITIONAL. Without it the paragraph can be bound to nothing and
  // reached by nothing, which is the defect this whole spec exists for.
  for (const line of refusals) {
    expect(line, `a refusal paragraph with no id: ${line}`).toMatch(/id=[{"]/);
  }

  // The two the focus ladder reaches are read as their input's description, so they must NOT also
  // interrupt. Everything else here must announce itself, because nothing else will.
  const focused = refusals.filter((line) => line.includes("nameErrorId") || line.includes("step-name-"));
  expect(focused.length, "the two focus-ladder refusals should be name and step name").toBe(2);
  for (const line of focused) {
    expect(line, `a focused refusal that also interrupts as an alert: ${line}`).not.toContain('role="alert"');
  }
  for (const line of refusals.filter((line) => !focused.includes(line))) {
    expect(line, `a refusal nothing focuses and nothing announces: ${line}`).toContain('role="alert"');
  }
});

test("the summary banner announces itself", () => {
  // "Please fill the required fields highlighted above." is the only thing said when a refusal has no
  // box of its own to mark, so it is the one that must never be silent.
  const banner = rendered(SOURCE()).find((line) => line.includes("bg-error-100") && line.includes("<div "));
  expect(banner, "the summary banner is gone — has the file been restructured?").toBeTruthy();
  expect(banner).toContain('role="alert"');
  expect(banner).toContain("id={errorId}");
});

test("the two text boxes are marked invalid and point at their reason", () => {
  const source = SOURCE();
  // The process name. `TitleCasedInput` merges the incoming description with its own hint, so this
  // one attribute does not cost the "Will be saved as …" announcement.
  expect(source).toContain("aria-invalid={!!nameError}");
  expect(source).toContain("aria-describedby={nameError ? nameErrorId : undefined}");
  // The step name — a plain `<input className=\"field-input\">`, one per step, so its ids are keyed.
  expect(source).toContain("aria-invalid={!!step.nameError}");
  expect(source).toContain("aria-describedby={step.nameError ? `step-name-${step.key}-error` : undefined}");
});

test("the two dropdowns carry a description and are NOT marked aria-invalid", () => {
  const source = SOURCE();
  expect(source).toContain("describedBy={artisanError ? artisanErrorId : undefined}");
  expect(source).toContain("describedBy={productError ? productErrorId : undefined}");
  // The trap: `aria-invalid` on a `button`-role element announces nothing. If a later sweep adds it
  // here, the source will claim a mark that no reader receives.
  expect(source).not.toContain("aria-invalid={!!artisanError}");
  expect(source).not.toContain("aria-invalid={!!productError}");
});

test("the pickers call a control that can actually carry describedBy", () => {
  const source = SOURCE();
  // See the header: the ORIGINAL reason (Select swallowed it) is fixed at the source, and these two
  // stay on `Dropdown` because they hold an options array rather than `<option>` children. What the
  // assertion guards is unchanged — a picker swapped for something with no `describedBy` at all
  // unbinds both refusals while leaving every assertion above about the paragraphs still green.
  expect(source).toContain('import { Dropdown } from "@/components/ui/Dropdown";');
  const artisan = source.slice(source.indexOf("value={artisanId}") - 400, source.indexOf("value={artisanId}"));
  expect(artisan).toContain("<Dropdown");
});

test("Select no longer swallows a description, for this form or any other caller", () => {
  /*
    THE GENERAL HALF OF THE FIX, pinned where the workaround was written. `FormControls.Select` is
    the app's replacement for the browser `<select>` and is used at thirty-odd call sites; its
    `...rest` spread lands on a mirror `<input aria-hidden="true" tabIndex={-1}>` that is rendered
    at all only when a `name` is set. So `aria-describedby` reached either an element no screen
    reader visits or no element whatsoever — silently, at every one of them.

    ONE SPELLING: the DOM one. `Dropdown` calls it `describedBy`, and accepting that name here as
    well would give one idea two spellings on a component whose whole claim is "same API as the
    browser <select>". It is translated on the way down instead.
  */
  const controls = readFileSync(join(__dirname, "..", "components", "FormControls.tsx"), "utf8");
  expect(controls).toContain('"aria-describedby": ariaDescribedBy');
  expect(controls).toContain("describedBy={typeof ariaDescribedBy === \"string\" ? ariaDescribedBy : undefined}");
});

test("the pre-process media refusal is bound to the card, not only announced once", () => {
  /*
    THE HALF THAT WAS MISSING. The paragraph had `role="alert"` and an id and the id pointed at
    nothing, because `MediaCaptureField` accepted no description of any kind — its own comment in
    this form said so. A live region is heard when it appears and is unreachable afterwards, so a
    researcher who tabbed back to the card to fix the refusal arrived at a control that said
    nothing about why they were there.

    THE ROLE STAYS. The two answer different moments, and the focus ladder cannot reach this card
    (there is no single control to focus), so removing either one loses a real reader.
  */
  const source = SOURCE();
  expect(source).toContain("aria-describedby={preMediaError ? preMediaErrorId : undefined}");

  const card = readFileSync(join(__dirname, "..", "components", "forms", "MediaCaptureField.tsx"), "utf8");
  // A bare <section> with no accessible name is a generic container and would drop the description
  // on the floor, so the fix is the group AND its name together — not the attribute on its own.
  expect(card).toContain('role="group"');
  expect(card).toContain("aria-labelledby={headingId}");
  // The card's own description is kept alongside the caller's, or adding a refusal would silence
  // the sentence explaining what the card is for.
  expect(card).toContain("aria-describedby={ariaDescribedBy ? `${descriptionId} ${ariaDescribedBy}` : descriptionId}");
});
