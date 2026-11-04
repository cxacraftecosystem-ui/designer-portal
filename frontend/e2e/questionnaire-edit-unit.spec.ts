import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");
const BROWSER_RAW = read("app/(protected)/data/page.tsx");

function stripComments(source: string): string {
  let out = "";
  let index = 0;
  let stringChar: string | null = null;

  while (index < source.length) {
    const char = source[index];
    const next = source[index + 1];

    if (stringChar) {
      out += char;
      if (char === "\\") {
        out += next ?? "";
        index += 2;
        continue;
      }
      if (char === stringChar) stringChar = null;
      index += 1;
      continue;
    }
    if (char === '"' || char === "'" || char === "`") {
      stringChar = char;
      out += char;
      index += 1;
      continue;
    }
    if (char === "/" && next === "/") {
      while (index < source.length && source[index] !== "\n") index += 1;
      continue;
    }
    if (char === "/" && next === "*") {
      index += 2;
      while (index < source.length && !(source[index] === "*" && source[index + 1] === "/")) index += 1;
      index += 2;
      continue;
    }
    out += char;
    index += 1;
  }
  return out;
}

const BROWSER = stripComments(BROWSER_RAW);

test.describe("the data browser carries the interview id", () => {
  test("its questionnaire arm no longer discards the id its signature declares", () => {
    expect(BROWSER).toContain("editHref: (id) => `/questionnaire/sessions/${encodeURIComponent(id)}`");
    expect(BROWSER, "the arm still throws the id away").not.toContain('editHref: () => "/questionnaire"');
  });

  test("only the one arm with no per-record form declares editHref without an id", () => {
    const zeroArg = [...BROWSER.matchAll(/(\w+):\s*\{[^}]*?editHref:\s*\(\s*\)\s*=>/g)].map((m) => m[1]);
    expect(
      zeroArg,
      "a browse arm declares editHref with no parameter, so it drops the record id the way the questionnaire arm did"
    ).toEqual(["media"]);
  });
});
