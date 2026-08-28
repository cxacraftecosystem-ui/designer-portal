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

/**
 * A FAILED LOAD MAY NOT BE WORDED AS AN ANSWER — the silent-emptiness class, on the browser's
 * Sketches & prototypes chooser.
 *
 * ── THE DEFECT THIS PINS ────────────────────────────────────────────────────────────────────────
 *
 * The handset's copy of this screen wrote a failed list as an empty list, which fell into the
 * `isEmpty()` branch, which renders the answered-and-none sentence. A designer on twelve design
 * workshops, standing in a courtyard with no signal, was told they were on none — and sent to ask
 * an administrator for the twelve they already had. `SketchesAndPrototypesScreen.kt` carries the
 * write-up and `DwSketchChooserSentenceTest` pins the property on that side.
 *
 * The browser never shipped that bug. Both failure branches leave `workshops` NULL precisely so the
 * empty state cannot win a race with an error, and the page's header argues it at length. What was
 * missing was anything STOPPING it: three inline string literals in three JSX branches cannot be
 * compared with one another by any test, and the property that matters is a relationship BETWEEN
 * them. Naming them (`chooserSentences.ts`) is what makes that property testable, which is the
 * whole reason this file can exist.
 *
 * ── THE PROPERTY, NOT THE PROSE ─────────────────────────────────────────────────────────────────
 *
 * Nothing here asserts a whole sentence. Every check below is a rule about what a sentence may and
 * may not CLAIM, so the copy stays free to be improved and the defect stays closed — the same
 * discipline `DwSketchChooserSentenceTest` and `DwRefusalSentenceTruthTest` follow.
 *
 * ── NO BROWSER, NO SERVER ───────────────────────────────────────────────────────────────────────
 *
 * Pure Node under `npm run test:unit`. The sentences live in a plain `.ts` module beside the route,
 * so importing them costs no React — the arrangement `e2e/report-target-unit.spec.ts` already uses
 * for `report/reportTarget.ts`.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const CHOOSER_PAGE = "app/(protected)/sketches-and-prototypes/page.tsx";

/** The two states that did NOT get an answer. Neither may speak as though it had one. */
const FAILURES: [string, string][] = [
  ["offline title", CHOOSER_OFFLINE_TITLE],
  ["offline body", CHOOSER_OFFLINE_BODY],
  ["offline route note", CHOOSER_OFFLINE_ROUTE_NOTE],
  ["refused title", CHOOSER_REFUSED_TITLE],
  ["refused fallback", CHOOSER_REFUSED_FALLBACK]
];

/**
 * THE REGRESSION ITSELF, AS A RULE ABOUT WORDS. Only the state that actually got an answer may send
 * anybody to an admin; a sentence about a request that never landed sends a designer on an errand
 * invented out of a failure.
 *
 * The word is "admin" rather than the handset's "administrator" because that is what this screen
 * says — the ROLE is spelled `admin` and `master admin` throughout the web client. Matching the
 * stem rather than either spelling is what keeps the two clients pinned to one property.
 */
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

/**
 * AND THE CONVERSE. The empty-state sentence must not hedge into failure language: a newly
 * onboarded designer being told something "could not" happen reads a correct, ordinary answer as a
 * broken app, and keeps pressing.
 */
test("the answered-and-none sentence never claims a failure", () => {
  for (const hedge of ["could not", "no connection", "failed", "try again", "unreachable"]) {
    expect(`${CHOOSER_NO_WORKSHOPS_TITLE} ${CHOOSER_NO_WORKSHOPS_BODY}`.toLowerCase()).not.toContain(
      hedge
    );
  }
});

/**
 * THE DISTINCTION IS SAID OUT LOUD, because a reader cannot infer it: an empty list and an unaskable
 * one look identical on screen unless one of them says which it is.
 */
test("the offline sentence separates an empty list from an unaskable one", () => {
  expect(CHOOSER_OFFLINE_BODY).toContain("not an empty archive");
  expect(CHOOSER_OFFLINE_BODY).toContain("could not be loaded");
});

/**
 * A FAILURE MUST SAY WHERE THE WORK CAN STILL BE DONE. "The repository could not be reached" is cold
 * comfort to somebody who came here to open a stage, so the panel names the route that is
 * unaffected — and names it as a route a reader can actually go to.
 */
test("the offline panel names the route that still works", () => {
  expect(CHOOSER_OFFLINE_ROUTE_NOTE).toContain("Design workshops");
  expect(CHOOSER_OFFLINE_ROUTE_NOTE).toContain("unaffected");
  // The label is a real link on screen, so the page must hold the href the note is promising.
  expect(read(CHOOSER_PAGE)).toContain('href="/design-workshops"');
});

/** Three states, three sentences. Any two of them being one is how the defect happened. */
test("the three answers are three different sentences", () => {
  const answers = [CHOOSER_NO_WORKSHOPS_TITLE, CHOOSER_OFFLINE_TITLE, CHOOSER_REFUSED_TITLE];
  expect(new Set(answers).size).toBe(answers.length);
  for (const answer of answers) expect(answer.trim().length).toBeGreaterThan(0);

  // The unreachable and the answered-and-refused panels are two facts with two next moves, so their
  // bodies must not converge either — `offline` and `problem` clear each other for the same reason.
  expect(CHOOSER_OFFLINE_BODY).not.toBe(CHOOSER_REFUSED_FALLBACK);
});

/**
 * AND THE SCREEN ACTUALLY MOUNTS THEM. A named sentence nothing renders is a test passing over a
 * page that still holds its own inline copy — which is the state this change was made to leave.
 */
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

  // And no inline duplicate of the two sentences most likely to be retyped in a hurry.
  expect(page).not.toContain(CHOOSER_REFUSED_FALLBACK);
  expect(page).not.toContain(CHOOSER_NO_WORKSHOPS_TITLE);
});
