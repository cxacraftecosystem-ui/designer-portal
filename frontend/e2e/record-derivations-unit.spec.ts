import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { deriveAge, deriveExperienceYears } from "@/lib/recordDerivations";

/**
 * `lib/recordDerivations.ts` against the SERVER'S OWN ANSWERS — no browser, no server, no database.
 *
 * WHAT THIS FILE IS FOR. An artisan's AGE and their YEARS OF EXPERIENCE are stored nowhere: the
 * record holds `dateOfBirth` and `craftStartDate`, and `records.derive_age` /
 * `records.derive_experience_years` turn them into numbers on every read. That is the whole design —
 * a number written down is right on the day it is typed and silently wrong from then on, and
 * `participant.experienceYears` is a TABLE_COLUMN in the participant table of every submitted
 * report, so the decay would print. But a derivation that only ever happens on the server is a
 * number the researcher cannot see while they are filling the form in, so the artisan record page
 * shows both — and the module that computes them for the screen says of itself, "IT IS A PORT AND
 * NOT A SECOND OPINION". This is what checks that claim. The sibling file
 * `derived-fields-unit.spec.ts` does the same job for the registry's own derivations, and its
 * history is the argument for doing it: two of the three surfaces that computed a cost line held
 * their own arithmetic, and two of them were wrong.
 *
 * THE EXPECTATIONS ARE NOT WRITTEN HERE. `e2e/fixtures/record-derivation-cases.json` is produced by
 * `frontend/tools/regenerate_record_derivation_golden.py`, which runs every case through the actual
 * Python. A golden written by reading the TypeScript would only prove the port agrees with itself.
 * When a case fails, THE PYTHON IS RIGHT — regenerating the golden until it passes is the one move
 * that destroys the point of the file.
 *
 * THE REFERENCE DAY COMES OUT OF THE FIXTURE, and both implementations take it as an argument for
 * the reason the server states: "an age function tested against `now()` passes in March and fails in
 * September, on the birthday of whatever fixture it uses." A suite that drifted with the calendar
 * would be a suite nobody could read a failure out of.
 *
 * WHY A NODE SPEC AND NOT A BROWSER ONE. Every case is one pure call over a string. There is no
 * React renderer in devDependencies, so a judgement written inside JSX is only ever exercised by
 * somebody looking at a screen — which is exactly why the arithmetic lives in a module of its own
 * and this file can reach it.
 */

type Case = {
  name: string;
  kind: "age" | "experience";
  value: string | null;
  expected: number | null;
};

const golden = JSON.parse(
  readFileSync(join(__dirname, "fixtures", "record-derivation-cases.json"), "utf-8")
) as { on: string; cases: Case[] };

/** The fixture's stated day, as the UTC-midnight `Date` both derivations measure against. */
function referenceDay(iso: string): Date {
  const [year, month, day] = iso.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day));
}

const ON = referenceDay(golden.on);

function computed(one: Case): number | null {
  return one.kind === "age" ? deriveAge(one.value, ON) : deriveExperienceYears(one.value, ON);
}

test("every case answers exactly what the backend answers", () => {
  const disagreed: string[] = [];
  for (const one of golden.cases) {
    const ours = computed(one);
    if (ours !== one.expected) {
      disagreed.push(`${one.name}: value ${JSON.stringify(one.value)} — python ${one.expected}, browser ${ours}`);
    }
  }
  expect(
    disagreed,
    `the browser's derivation disagreed with the server's on ${disagreed.length} case(s). THE PYTHON ` +
      `IS RIGHT: fix lib/recordDerivations.ts rather than the golden.\n${disagreed.join("\n")}`
  ).toEqual([]);
});

test("the golden actually exercises both derivations and both outcomes", () => {
  // A golden that quietly lost half its rows would pass the test above in silence, which is the one
  // failure mode a comparison harness cannot see. Counted rather than asserted as a total, so adding
  // a case never means editing this.
  const kinds = new Set(golden.cases.map((one) => one.kind));
  expect(kinds).toEqual(new Set(["age", "experience"]));
  expect(golden.cases.some((one) => one.expected === null)).toBe(true);
  expect(golden.cases.some((one) => typeof one.expected === "number")).toBe(true);
});

test("zero is an answer and blank is not, in both directions", () => {
  // THE DISTINCTION THE WHOLE PORT TURNS ON, asserted where a reader will find it rather than left
  // implicit in two fixture rows. An apprentice who took up the craft this year has zero whole years
  // of experience, and a record with no date has none — so every caller must test for null and never
  // for truthiness. `or` in place of `is not None` on the server side of this same rule is the
  // documented reason the precedence in `REFERENCE_MODELS["Artisan"].data` is spelled out at length.
  expect(deriveExperienceYears("2026-08-23", ON)).toBe(0);
  expect(deriveExperienceYears("", ON)).toBeNull();
  expect(deriveExperienceYears(null, ON)).toBeNull();
  expect(deriveAge("2026-08-23", ON)).toBe(0);
  expect(deriveAge(undefined, ON)).toBeNull();
});

test("the two ISO spellings no client can produce are refused, and that is the known divergence", () => {
  // `datetime.fromisoformat` ALSO accepts the week form and the ordinal form, so on these two
  // strings the server answers a number and this port answers null. They are deliberately kept OUT
  // of the golden — carrying them would pin a divergence as though it were the rule — and asserted
  // here instead, with the reason, so it is a known edge rather than a surprise to whoever next
  // reads a null out of this module.
  //
  // Nothing can produce either one: a `DateField` submits `yyyy-mm-dd`, the API returns
  // `yyyy-mm-ddThh:mm:ssZ`, and Android's `parseFieldDate` reads `dd/mm/yyyy` and `yyyy-mm-dd`.
  // Growing an ISO-8601 parser in the browser to cover a spelling no writer emits would be this
  // module writing its own opinion, which is the one thing a port must not do.
  expect(deriveAge("2026-W33-4", ON)).toBeNull();
  expect(deriveAge("2026-227", ON)).toBeNull();
});
