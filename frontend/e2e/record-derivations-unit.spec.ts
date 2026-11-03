import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { deriveAge, deriveExperienceYears } from "@/lib/recordDerivations";

type Case = {
  name: string;
  kind: "age" | "experience";
  value: string | null;
  expected: number | null;
};

const golden = JSON.parse(
  readFileSync(join(__dirname, "fixtures", "record-derivation-cases.json"), "utf-8")
) as { on: string; cases: Case[] };

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
  const kinds = new Set(golden.cases.map((one) => one.kind));
  expect(kinds).toEqual(new Set(["age", "experience"]));
  expect(golden.cases.some((one) => one.expected === null)).toBe(true);
  expect(golden.cases.some((one) => typeof one.expected === "number")).toBe(true);
});

test("zero is an answer and blank is not, in both directions", () => {
  expect(deriveExperienceYears("2026-08-23", ON)).toBe(0);
  expect(deriveExperienceYears("", ON)).toBeNull();
  expect(deriveExperienceYears(null, ON)).toBeNull();
  expect(deriveAge("2026-08-23", ON)).toBe(0);
  expect(deriveAge(undefined, ON)).toBeNull();
});

test("the two ISO spellings no client can produce are refused, and that is the known divergence", () => {
  expect(deriveAge("2026-W33-4", ON)).toBeNull();
  expect(deriveAge("2026-227", ON)).toBeNull();
});
