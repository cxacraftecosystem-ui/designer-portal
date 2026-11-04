import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const SOURCE = () => readFileSync(join(__dirname, "..", "components", "forms", "ProcessForm.tsx"), "utf8");

function rendered(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"));
}

test("every refusal paragraph carries an id, and the right four are live regions", () => {
  const lines = rendered(SOURCE()).filter((line) => line.includes("text-error-600") && line.includes("<p "));
  const refusals = lines.filter((line) => line.includes("id="));
  expect(refusals.length).toBeGreaterThanOrEqual(6);

  for (const line of refusals) {
    expect(line, `a refusal paragraph with no id: ${line}`).toMatch(/id=[{"]/);
  }

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
  const banner = rendered(SOURCE()).find((line) => line.includes("bg-error-100") && line.includes("<div "));
  expect(banner, "the summary banner is gone — has the file been restructured?").toBeTruthy();
  expect(banner).toContain('role="alert"');
  expect(banner).toContain("id={errorId}");
});

test("the two text boxes are marked invalid and point at their reason", () => {
  const source = SOURCE();
  expect(source).toContain("aria-invalid={!!nameError}");
  expect(source).toContain("aria-describedby={nameError ? nameErrorId : undefined}");
  expect(source).toContain("aria-invalid={!!step.nameError}");
  expect(source).toContain("aria-describedby={step.nameError ? `step-name-${step.key}-error` : undefined}");
});

test("the two dropdowns carry a description and are NOT marked aria-invalid", () => {
  const source = SOURCE();
  expect(source).toContain("describedBy={artisanError ? artisanErrorId : undefined}");
  expect(source).toContain("describedBy={productError ? productErrorId : undefined}");
  expect(source).not.toContain("aria-invalid={!!artisanError}");
  expect(source).not.toContain("aria-invalid={!!productError}");
});

test("the pickers call a control that can actually carry describedBy", () => {
  const source = SOURCE();
  expect(source).toContain('import { Dropdown } from "@/components/ui/Dropdown";');
  const artisan = source.slice(source.indexOf("value={artisanId}") - 400, source.indexOf("value={artisanId}"));
  expect(artisan).toContain("<Dropdown");
});

test("Select no longer swallows a description, for this form or any other caller", () => {
  const controls = readFileSync(join(__dirname, "..", "components", "FormControls.tsx"), "utf8");
  expect(controls).toContain('"aria-describedby": ariaDescribedBy');
  expect(controls).toContain("describedBy={typeof ariaDescribedBy === \"string\" ? ariaDescribedBy : undefined}");
});

test("the pre-process media refusal is bound to the card, not only announced once", () => {
  const source = SOURCE();
  expect(source).toContain("aria-describedby={preMediaError ? preMediaErrorId : undefined}");

  const card = readFileSync(join(__dirname, "..", "components", "forms", "MediaCaptureField.tsx"), "utf8");
  expect(card).toContain('role="group"');
  expect(card).toContain("aria-labelledby={headingId}");
  expect(card).toContain("aria-describedby={ariaDescribedBy ? `${descriptionId} ${ariaDescribedBy}` : descriptionId}");
});
