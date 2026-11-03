import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const CSS = readFileSync(join(__dirname, "..", "app", "globals.css"), "utf8").split("\r\n").join("\n");

function rules(source: string): Array<{ selector: string; declarations: string[] }> {
  const text = source.replace(/\/\*[\s\S]*?\*\//g, "");
  const found: Array<{ selector: string; declarations: string[] }> = [];
  const block = /([^{}]+)\{([^{}]*)\}/g;
  let match: RegExpExecArray | null;
  while ((match = block.exec(text))) {
    const declarations = match[2]
      .split(";")
      .map((part) => part.trim())
      .filter((part) => part.includes(":"))
      .map((part) => part.slice(0, part.indexOf(":")).trim().toLowerCase());
    found.push({ selector: match[1].trim().split("\n").pop()!.trim(), declarations });
  }
  return found;
}

test("the two glass recipes carry both backdrop-filter declarations, the prefixed one first", () => {
  for (const name of [".glass-card", ".glass-dark"]) {
    const rule = rules(CSS).find((candidate) => candidate.selector === name);
    expect(rule, `${name} is declared in globals.css`).toBeDefined();
    const prefixed = rule!.declarations.indexOf("-webkit-backdrop-filter");
    const standard = rule!.declarations.indexOf("backdrop-filter");
    expect(prefixed, `${name} declares -webkit-backdrop-filter, which Safari still reads`).toBeGreaterThanOrEqual(0);
    expect(standard, `${name} declares backdrop-filter, which Chromium reads`).toBeGreaterThanOrEqual(0);
    expect(prefixed, `${name}: the prefixed line comes first, or the minifier keeps only it`).toBeLessThan(standard);
  }
});

test("every prefixed declaration in globals.css comes before its standard form", () => {
  const misordered: string[] = [];
  let pairs = 0;
  for (const { selector, declarations } of rules(CSS)) {
    declarations.forEach((property, index) => {
      const standard = property.replace(/^-(?:webkit|moz)-/, "");
      if (standard === property) return;
      const at = declarations.indexOf(standard);
      if (at < 0) return;
      pairs += 1;
      if (at < index) misordered.push(`${selector}: ${standard} before ${property}`);
    });
  }
  expect(pairs).toBeGreaterThanOrEqual(5);
  expect(misordered).toEqual([]);
});
