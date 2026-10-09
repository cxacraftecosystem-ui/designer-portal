import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * IN app/globals.css A VENDOR-PREFIXED DECLARATION COMES BEFORE THE STANDARD ONE IT SHADOWS.
 *
 * ── THE FINDING (2026-10-09) ────────────────────────────────────────────────────────────────────
 *
 * `.glass-card` and `.glass-dark` wrote `backdrop-filter: blur(…)` and then
 * `-webkit-backdrop-filter: blur(…)`. The production build minifies the stylesheet with Lightning
 * CSS, and with the prefixed line LAST it kept only that one: every build, under Tailwind 3 and 4
 * alike, shipped `.glass-card{-webkit-backdrop-filter:blur(18px);…}` and nothing else. Chromium does
 * not implement the prefixed property (`CSS.supports("-webkit-backdrop-filter", "blur(2px)")` is
 * false there), so the landing page's transcript card was never frosted in Chromium, and neither was
 * the sign-in card on the paint before `GlassSurface` sets its own inline filter. With the prefixed
 * line FIRST the build keeps both, which is the order every other pair in the file was already in.
 *
 * ── WHAT IS PINNED ──────────────────────────────────────────────────────────────────────────────
 *
 * In every rule of globals.css that declares both a `-webkit-` or `-moz-` property and its standard
 * form, the prefixed declaration comes first. It is a source read, because the order is the whole of
 * the fix and a build is not something a unit spec can run; what the built stylesheet then holds was
 * measured on the day (both declarations, prefixed first, in `.next/static/chunks/*.css`).
 * ⚠ LINE-ENDING AGNOSTIC: the file is normalised to `\n` before it is read.
 */

const CSS = readFileSync(join(__dirname, "..", "app", "globals.css"), "utf8").split("\r\n").join("\n");

/** Every innermost `selector { declarations }` block, comments removed. */
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
  // The glass recipes, the gradient text recipes and the number input at least; a parser that found
  // none would pass this test by finding nothing.
  expect(pairs).toBeGreaterThanOrEqual(5);
  expect(misordered).toEqual([]);
});
