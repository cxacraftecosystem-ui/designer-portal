import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { deriveValue, isDerived } from "@/lib/derivedFields";
import type { DwEntryData, DwField, DwFieldType, DwValue } from "@/lib/designWorkshops";

const FIXTURE = join(__dirname, "fixtures", "derived-field-cases.json");

type GoldenCase = {
  name: string;
  type: string;
  derivedKind: string;
  derivedFrom: string[];
  row: Record<string, DwValue>;
  expected: DwValue | null;
};

const golden = JSON.parse(readFileSync(FIXTURE, "utf8")) as { cases: GoldenCase[] };

function fieldOf(type: string, derivedKind: string, derivedFrom: string[]): DwField {
  return {
    key: "derived",
    label: "Derived",
    type: type as DwFieldType,
    tier: "STANDARD",
    required: false,
    derivedKind: derivedKind as DwField["derivedKind"],
    derivedFrom
  };
}

test("every case answers exactly what the server answered", () => {
  expect(golden.cases.length).toBe(45);

  const disagreements: string[] = [];
  for (const kase of golden.cases) {
    const actual = deriveValue(fieldOf(kase.type, kase.derivedKind, kase.derivedFrom), kase.row as DwEntryData);
    const same = Object.is(actual, kase.expected) || (actual === null && kase.expected === null);
    if (!same) {
      disagreements.push(`${kase.name}: server ${JSON.stringify(kase.expected)}, web ${JSON.stringify(actual)}`);
    }
  }
  expect(disagreements).toEqual([]);
});

test("a half-paisa tie goes to the even digit, the way the save will write it", () => {
  const amount = fieldOf("MONEY", "PRODUCT", ["quantity", "rate"]);
  expect(deriveValue(amount, { quantity: "1.5", rate: "4.75" })).toBe("7.12");
  expect(deriveValue(amount, { quantity: "0.5", rate: "0.25" })).toBe("0.12");
  expect(deriveValue(amount, { quantity: "1.5", rate: "0.25" })).toBe("0.38");
});

test("a factor the server would read as infinite is refused rather than printed", () => {
  const amount = fieldOf("MONEY", "PRODUCT", ["quantity", "rate"]);
  expect(deriveValue(amount, { quantity: "Infinity", rate: "2" })).toBeNull();
  expect(deriveValue(amount, { quantity: "nan", rate: "2" })).toBeNull();
});

test("every control that can hold a derived field actually draws the computed value", () => {
  const source = readFileSync(join(__dirname, "..", "components", "designworkshop", "FieldInput.tsx"), "utf8");
  const branchAfter = (marker: string) => {
    const start = source.indexOf(marker);
    expect(start, `${marker} not found — has FieldInput been restructured?`).toBeGreaterThan(-1);
    const end = source.indexOf('case "', start + marker.length);
    return source.slice(start, end === -1 ? undefined : end);
  };
  expect(branchAfter('case "PERCENT":')).toContain("derivedPlaceholder(field, value, row)");
  expect(branchAfter('case "MONEY":')).toContain("derivedPlaceholder(field, value, row)");
});

test("isDerived answers for the field, not for the row", () => {
  expect(isDerived(fieldOf("MONEY", "PRODUCT", ["quantity", "rate"]))).toBe(true);
  expect(isDerived(fieldOf("MONEY", "", ["quantity"]))).toBe(false);
  expect(isDerived(fieldOf("MONEY", "SUM", []))).toBe(false);
});
