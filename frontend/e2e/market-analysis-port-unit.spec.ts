import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { analysePayload, type MarketFindingsPayload, type MarketRow } from "@/lib/marketAnalysis";

const RESOURCES = join(__dirname, "..", "..", "android", "app", "src", "test", "resources");

type MarketCase = {
  name: string;
  comment?: string;
  responses: MarketRow[];
  competitors: MarketRow[];
  bands: MarketRow[];
  swot: MarketRow[];
};

const cases: MarketCase[] = JSON.parse(readFileSync(join(RESOURCES, "dw-analysis-cases.json"), "utf8")).market;
const expected: Record<string, MarketFindingsPayload> = JSON.parse(
  readFileSync(join(RESOURCES, "dw-analysis-expected.json"), "utf8")
);

function show(value: unknown): string {
  if (typeof value === "number" && Object.is(value, -0)) return "-0";
  if (value === undefined) return "undefined";
  return JSON.stringify(value).replace(
    /[^\x20-\x7e]/g,
    (character) => `\\u${character.charCodeAt(0).toString(16).toUpperCase().padStart(4, "0")}`
  );
}

function differences(actual: unknown, golden: unknown, path = "$"): string[] {
  if (Array.isArray(golden) || Array.isArray(actual)) {
    if (!Array.isArray(actual) || !Array.isArray(golden)) return [`${path}: ${show(actual)} ≠ ${show(golden)}`];
    if (actual.length !== golden.length) {
      return [`${path}: ${actual.length} entries, expected ${golden.length}`];
    }
    return golden.flatMap((entry, index) => differences(actual[index], entry, `${path}[${index}]`));
  }
  const bothObjects =
    typeof golden === "object" && golden !== null && typeof actual === "object" && actual !== null;
  if (bothObjects) {
    const left = actual as Record<string, unknown>;
    const right = golden as Record<string, unknown>;
    const keys = [...new Set([...Object.keys(left), ...Object.keys(right)])].sort();
    return keys.flatMap((key) => differences(left[key], right[key], `${path}.${key}`));
  }
  if (typeof golden === "object" || typeof actual === "object") {
    if (!Object.is(actual, golden)) return [`${path}: ${show(actual)} ≠ ${show(golden)}`];
    return [];
  }
  return Object.is(actual, golden) ? [] : [`${path}: ${show(actual)} ≠ ${show(golden)}`];
}

const KNOWN_PORT_DIVERGENCES = new Set<string>();

test("the shared case table is present and is the one the handset reads", () => {
  expect(cases.length).toBeGreaterThanOrEqual(29);
  for (const one of cases) expect(expected[`market/${one.name}`], `no golden for ${one.name}`).toBeTruthy();
});

test("the difference walker is discriminating, or every case below passes for the wrong reason", () => {
  expect(differences({ a: 1 }, { a: 1 })).toEqual([]);
  expect(differences({ a: 1 }, { a: 2 })).toEqual(["$.a: 1 ≠ 2"]);
  expect(differences({ a: -0 }, { a: 0 })).toEqual(["$.a: -0 ≠ 0"]);
  expect(differences({ a: NaN }, { a: NaN })).toEqual([]);
  expect(differences({ p: null }, { p: { count: 1 } })).toEqual(["$.p: null ≠ {\"count\":1}"]);
  expect(differences({ b: [{ m: "x" }] }, { b: [{ m: "y" }] })).toEqual(['$.b[0].m: "x" ≠ "y"']);
  expect(differences({}, { cautions: [] })).toEqual(["$.cautions: undefined ≠ []"]);
  expect(differences({ b: [] }, { b: [1] })).toEqual(["$.b: 0 entries, expected 1"]);
});

test("every known divergence names a case that is actually in the table", () => {
  for (const name of KNOWN_PORT_DIVERGENCES) {
    expect(cases.some((one) => one.name === name), `${name} is no longer a case in the table`).toBe(true);
  }
});

for (const one of cases) {
  test(`market/${one.name} matches the backend byte for byte`, () => {
    if (KNOWN_PORT_DIVERGENCES.has(one.name)) test.fail();
    const actual = analysePayload({
      responses: one.responses,
      competitors: one.competitors,
      bands: one.bands,
      swot: one.swot
    });
    const golden = expected[`market/${one.name}`];
    expect(
      differences(actual, golden),
      `market/${one.name}${one.comment ? ` — ${one.comment}` : ""}`
    ).toEqual([]);
    expect(actual).toEqual(golden);
  });
}
