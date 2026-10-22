import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE DATA BROWSER'S "EDIT RECORD" FOR AN INTERVIEW carries the record's id to where the record is
 * corrected. The retired global interview form (and its `?edit=` deep link, whose tests lived here)
 * is gone (owner decision 2; UNIFY 4.4): an interview is a session of the shared questionnaire, so
 * the link opens its workspace. `/questionnaire?edit=` still lands on the sessions list
 * (`lib/questionnaireLinks.ts`, `questionnaire-dr-web-unit.spec.ts`).
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");
const BROWSER_RAW = read("app/(protected)/data/page.tsx");

/**
 * Source with comments removed, quotes respected.
 *
 * The same helper `ministry-surface-unit.spec.ts` needs and for the same stated reason: "that block
 * states its own prohibitions in prose … so a substring check over the raw text fails on the very
 * sentence that forbids what it is hunting for".
 */
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
    // THE ASSERTION THE WHOLE FILE IS ABOUT. `editHref: () => "/questionnaire"` landed "Edit record"
    // on the blank create form and filing that in made a second sitting.
    // An interview is a session now (UNIFY 4.4): Edit opens its workspace, carrying the id.
    expect(BROWSER).toContain("editHref: (id) => `/questionnaire/sessions/${encodeURIComponent(id)}`");
    expect(BROWSER, "the arm still throws the id away").not.toContain('editHref: () => "/questionnaire"');
  });

  test("only the one arm with no per-record form declares editHref without an id", () => {
    /*
      Generalised so the next arm added cannot reintroduce the shape — but not to ZERO, because one
      zero-argument arm is correct. `media` has no per-record edit route at all: a media file is
      corrected on `/media`, which is a list and an uploader rather than a form keyed to a row, so
      there is no id for the href to carry. Every other arm has somewhere to carry one to.

      Pinned as a NAMED EXEMPTION rather than a smaller regex, so adding a second zero-argument arm
      fails here and has to be argued for, rather than quietly joining a pattern.
    */
    // No `/s` flag: `[^}]` already spans newlines, and the flag needs an ES2018 target this
    // project does not set — it compiled here and failed `tsc` in the same breath.
    const zeroArg = [...BROWSER.matchAll(/(\w+):\s*\{[^}]*?editHref:\s*\(\s*\)\s*=>/g)].map((m) => m[1]);
    expect(
      zeroArg,
      "a browse arm declares editHref with no parameter, so it drops the record id the way the questionnaire arm did"
    ).toEqual(["media"]);
  });
});
