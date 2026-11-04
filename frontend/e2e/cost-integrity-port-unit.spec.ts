import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { analyseCostIntegrity, rollUp, unsyncedSheetCount, type CostFindingsPayload, type CostRow } from "@/lib/costIntegrity";

const RESOURCES = join(__dirname, "..", "..", "android", "app", "src", "test", "resources");

type CostCase = {
  name: string;
  comment?: string;
  sheets: CostRow[];
  materialLines: CostRow[];
  labourLines: CostRow[];
  labels?: Record<string, string>;
};

const cases: CostCase[] = JSON.parse(readFileSync(join(RESOURCES, "dw-analysis-cases.json"), "utf8")).cost;
const expected: Record<string, CostFindingsPayload> = JSON.parse(
  readFileSync(join(RESOURCES, "dw-analysis-expected.json"), "utf8")
);

test("the shared case table is present and is the one the handset reads", () => {
  expect(cases.length).toBeGreaterThanOrEqual(28);
  for (const one of cases) expect(expected[`cost/${one.name}`], `no golden for ${one.name}`).toBeTruthy();
});

for (const one of cases) {
  test(`cost/${one.name} matches the backend byte for byte`, () => {
    const actual = analyseCostIntegrity({
      sheets: one.sheets,
      materialLines: one.materialLines,
      labourLines: one.labourLines,
      labels: one.labels ?? {}
    });
    expect(actual).toEqual(expected[`cost/${one.name}`]);
  });
}

test("a sheet with no server id yet is counted as unsynced, not as missing", () => {
  expect(unsyncedSheetCount([])).toBe(0);
  expect(unsyncedSheetCount([{ _entryId: "s1" }])).toBe(0);
  expect(unsyncedSheetCount([{ _entryId: "s1" }, {}, { _entryId: "" }])).toBe(2);
});

test("a label padded with a next-line keeps its name, exactly as .strip() leaves it", () => {
  const padded = "\u0085Kota doria, 4m\u0085";

  const unreadable = rollUp("MATERIAL", [{ item: padded, amount: "not a number" }]).unreadable;
  expect(unreadable).toEqual(["Kota doria, 4m"]);

  const findings = analyseCostIntegrity({
    sheets: [{ _entryId: "s1" }],
    materialLines: [{ costSheetRef: "gone", item: padded, amount: "120.00" }],
    labourLines: []
  });
  expect(findings.orphans.map((orphan) => orphan.label)).toEqual(["Kota doria, 4m"]);
  expect(findings.cautions.join(" ")).toContain("Kota doria, 4m");

  expect(rollUp("LABOUR", [{ task: "\uFEFFTie-dye", amount: "x" }]).unreadable).toEqual(["\uFEFFTie-dye"]);
});
