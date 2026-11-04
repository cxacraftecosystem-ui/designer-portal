import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const FORMS = join(__dirname, "..", "components", "forms");

const source = (file: string) => readFileSync(join(FORMS, file), "utf8");

function numberInputs(text: string): string[] {
  return text.split(/\r?\n/).filter((line) => line.includes('type="number"'));
}

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
    expect(lines).toHaveLength(names.length);
    for (const name of names) {
      const line = lines.find((candidate) => candidate.includes(`name="${name}"`));
      expect(line, `${file} has no number input named ${name}`).toBeTruthy();
      expect(line, `${name} accepts a negative value`).toContain("min={0}");
    }
  });

  test(`${file}: the server half's refusal has somewhere to be announced`, () => {
    const banner = source(file)
      .split(/\r?\n/)
      .find((line) => line.includes("bg-red-50") && line.includes("<div "));
    expect(banner, `${file} no longer renders its error banner as one line — has it been restructured?`).toBeTruthy();
    expect(banner).toContain('role="alert"');
    expect(banner).toContain("id={errorId}");
  });

  test(`${file}: the form did not turn off native constraint validation`, () => {
    const tag = source(file)
      .split(/\r?\n/)
      .find((line) => line.trim().startsWith("<form "));
    expect(tag, `${file} has no <form> tag — has it been restructured?`).toBeTruthy();
    expect(tag).not.toContain("noValidate");
  });
}
