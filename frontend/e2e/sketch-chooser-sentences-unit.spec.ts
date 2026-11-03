import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CHOOSER_NO_WORKSHOPS_BODY,
  CHOOSER_NO_WORKSHOPS_TITLE,
  CHOOSER_OFFLINE_BODY,
  CHOOSER_OFFLINE_ROUTE_NOTE,
  CHOOSER_OFFLINE_TITLE,
  CHOOSER_REFUSED_FALLBACK,
  CHOOSER_REFUSED_TITLE
} from "@/app/(protected)/sketches-and-prototypes/chooserSentences";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const CHOOSER_PAGE = "app/(protected)/sketches-and-prototypes/page.tsx";

const FAILURES: [string, string][] = [
  ["offline title", CHOOSER_OFFLINE_TITLE],
  ["offline body", CHOOSER_OFFLINE_BODY],
  ["offline route note", CHOOSER_OFFLINE_ROUTE_NOTE],
  ["refused title", CHOOSER_REFUSED_TITLE],
  ["refused fallback", CHOOSER_REFUSED_FALLBACK]
];

test("only the answered-and-none sentence sends anybody to an admin", () => {
  expect(CHOOSER_NO_WORKSHOPS_BODY).toMatch(/\badmin/i);
  for (const [name, sentence] of FAILURES) {
    expect(
      sentence,
      `the '${name}' sentence sends a designer to an admin about a request that failed — that is ` +
        `the defect this test exists for`
    ).not.toMatch(/\badmin/i);
  }
});

test("the answered-and-none sentence never claims a failure", () => {
  for (const hedge of ["could not", "no connection", "failed", "try again", "unreachable"]) {
    expect(`${CHOOSER_NO_WORKSHOPS_TITLE} ${CHOOSER_NO_WORKSHOPS_BODY}`.toLowerCase()).not.toContain(
      hedge
    );
  }
});

test("the offline sentence separates an empty list from an unaskable one", () => {
  expect(CHOOSER_OFFLINE_BODY).toContain("isn't an empty list");
  expect(CHOOSER_OFFLINE_BODY).toContain("couldn't be loaded");
});

test("the offline panel names the route that still works", () => {
  expect(CHOOSER_OFFLINE_ROUTE_NOTE).toContain("Design workshops");
  expect(CHOOSER_OFFLINE_ROUTE_NOTE).toContain("unaffected");
  expect(read(CHOOSER_PAGE)).toContain('href="/design-workshops"');
});

test("the three answers are three different sentences", () => {
  const answers = [CHOOSER_NO_WORKSHOPS_TITLE, CHOOSER_OFFLINE_TITLE, CHOOSER_REFUSED_TITLE];
  expect(new Set(answers).size).toBe(answers.length);
  for (const answer of answers) expect(answer.trim().length).toBeGreaterThan(0);

  expect(CHOOSER_OFFLINE_BODY).not.toBe(CHOOSER_REFUSED_FALLBACK);
});

test("the chooser renders the named sentences rather than its own copies", () => {
  const page = read(CHOOSER_PAGE);
  for (const symbol of [
    "CHOOSER_NO_WORKSHOPS_TITLE",
    "CHOOSER_NO_WORKSHOPS_BODY",
    "CHOOSER_OFFLINE_TITLE",
    "CHOOSER_OFFLINE_BODY",
    "CHOOSER_OFFLINE_ROUTE_LEAD",
    "CHOOSER_OFFLINE_ROUTE_LABEL",
    "CHOOSER_OFFLINE_ROUTE_TAIL",
    "CHOOSER_REFUSED_TITLE",
    "CHOOSER_REFUSED_FALLBACK"
  ]) {
    expect(page, `${symbol} is exported but the chooser does not use it`).toContain(symbol);
  }

  expect(page).not.toContain(CHOOSER_REFUSED_FALLBACK);
  expect(page).not.toContain(CHOOSER_NO_WORKSHOPS_TITLE);
});
