import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DW_DECISION_EDGES,
  DW_FROZEN_APPROVED,
  DW_FROZEN_HANDED_ON,
  DW_LEGAL_TRANSITIONS,
  dwFrozenReason,
  dwIsFrozen,
  dwPatchableFrom,
  type DwStatus
} from "@/lib/designWorkshops";

const BADGE_SRC = readFileSync(join(__dirname, "..", "components", "StatusBadge.tsx"), "utf8");
const RECORD_PAGE_SRC = readFileSync(
  join(__dirname, "..", "app", "(protected)", "design-workshops", "[id]", "page.tsx"),
  "utf8"
);
const LIST_PAGE_SRC = readFileSync(
  join(__dirname, "..", "app", "(protected)", "design-workshops", "page.tsx"),
  "utf8"
);

const DW_STATUSES = Object.keys(DW_LEGAL_TRANSITIONS) as DwStatus[];

function badgeMap(name: string): Record<string, string> {
  const match = BADGE_SRC.match(
    new RegExp(`const ${name}: Record<string, string> = \\{[\\s\\S]*?^\\};`, "m")
  );
  expect(match, `${name} is no longer a module-level Record literal in StatusBadge.tsx`).not.toBeNull();
  const body = match![0]
    .replace(new RegExp(`^const ${name}: Record<string, string> = `), "")
    .replace(/;$/, "");
  return Function(`"use strict"; return (${body});`)() as Record<string, string>;
}

const tone = badgeMap("tone");
const label = badgeMap("label");

test("the two maps were lifted out of the real component and are not empty", () => {
  expect(Object.keys(tone).length).toBeGreaterThan(5);
  expect(Object.keys(label).length).toBeGreaterThan(5);
  expect(tone.DRAFT).toBeTruthy();
});

test("every design-workshop status has its own tone, so none of them falls back to DRAFT grey", () => {
  const missing = DW_STATUSES.filter((status) => tone[status] === undefined);
  expect(
    missing,
    `${missing.join(", ")} would render as tone.DRAFT — the grey pill that means "nobody has ` +
      `touched this" — on all six surfaces that draw this chip`
  ).toEqual([]);
});

test("no status wears DRAFT's exact pill but DRAFT", () => {
  const impostors = DW_STATUSES.filter((s) => s !== "DRAFT" && tone[s] === tone.DRAFT);
  expect(impostors, `${impostors.join(", ")} is painted exactly like a draft`).toEqual([]);
});

test("ARCHIVED is a different grey from DRAFT, which is the same rule one rung down", () => {
  expect(tone.ARCHIVED).not.toEqual(tone.DRAFT);
});

test("PRE_SUBMISSION is not COMPLETE's pill either, because the two sit on one list", () => {
  expect(tone.PRE_SUBMISSION).not.toEqual(tone.COMPLETE);
});

test("the two maps carry the same key set, which is what makes a missing tone visible", () => {
  expect(Object.keys(tone).sort()).toEqual(Object.keys(label).sort());
});

test("a status's badge spells it the way the filter that found it spells it", () => {
  const options = [...LIST_PAGE_SRC.matchAll(/\{ value: "([A-Z_]*)", label: "([^"]+)" \}/g)];
  expect(options.length, "STATUS_OPTIONS could not be read off /design-workshops").toBeGreaterThan(5);
  for (const [, value, optionLabel] of options) {
    if (!value) continue;
    expect(label[value], `the badge has no curated label for ${value}`).toBe(optionLabel);
  }
});

function submissionActionStatuses(): Record<string, DwStatus> {
  const out: Record<string, DwStatus> = {};
  for (const m of RECORD_PAGE_SRC.matchAll(
    /const ([A-Z_]+): SubmissionAction = \{\s*status: "([A-Z_]+)"/g
  )) {
    out[m[1]] = m[2] as DwStatus;
  }
  return out;
}

function candidateTable(): Record<string, string[]> {
  const fn = RECORD_PAGE_SRC.match(
    /function candidatesFor\(status: string\): SubmissionAction\[\] \{[\s\S]*?^\}/m
  );
  expect(fn, "candidatesFor is no longer a module-level switch in the record page").not.toBeNull();
  const table: Record<string, string[]> = {};
  let pending: string[] = [];
  for (const line of fn![0].split("\n")) {
    const caseMatch = line.match(/case "([A-Z_]+)":/);
    if (caseMatch) {
      pending.push(caseMatch[1]);
      continue;
    }
    const ret = line.match(/return \[([^\]]*)\];/);
    if (ret && pending.length > 0) {
      const names = ret[1]
        .split(",")
        .map((n) => n.trim())
        .filter(Boolean);
      for (const status of pending) table[status] = names;
      pending = [];
    }
  }
  return table;
}

test("the record page really does call dwPatchableFrom, rather than only claiming to", () => {
  expect(RECORD_PAGE_SRC).toContain("dwPatchableFrom,");
  const actionsFor = RECORD_PAGE_SRC.match(
    /function actionsFor\(status: string\): SubmissionAction\[\] \{[\s\S]*?^\}/m
  );
  expect(actionsFor).not.toBeNull();
  expect(actionsFor![0]).toContain("dwPatchableFrom(status)");
  expect(actionsFor![0]).toContain("filter");
});

test("no button the record page offers is an edge a header edit may not take", () => {
  const statuses = submissionActionStatuses();
  const table = candidateTable();
  expect(Object.keys(table).length, "no switch arms were read out of candidatesFor").toBeGreaterThan(4);

  for (const [from, names] of Object.entries(table)) {
    const patchable = dwPatchableFrom(from);
    const wanted = names.map((n) => statuses[n]).filter(Boolean);
    const refused = wanted.filter((to) => !patchable.includes(to));
    expect(
      refused,
      `from ${from} the card would draw a button writing ${refused.join(", ")}, which ` +
        `update_design_workshop answers 422 to — the graph allows only ${patchable.join(", ")}`
    ).toEqual([]);
    if (names.length > 0) {
      const offered = wanted.filter((to) => patchable.includes(to));
      expect(offered.length, `every candidate from ${from} was struck out by the graph`).toBeGreaterThan(0);
    }
  }
});

test("a report handed on to the office takes no header edit; a legacy SUBMITTED row keeps its way back", () => {
  expect(dwPatchableFrom("SUBMITTED", { handedOn: true })).toEqual([]);
  expect(dwPatchableFrom("SUBMITTED")).toEqual(expect.arrayContaining(["IN_PROGRESS", "PRE_SUBMISSION", "ARCHIVED"]));
  expect(dwPatchableFrom("SUBMITTED")).not.toContain("NEEDS_REVISION");
  expect(DW_LEGAL_TRANSITIONS.SUBMITTED).toContain("NEEDS_REVISION");
  expect(DW_DECISION_EDGES.some(([a, b]) => a === "SUBMITTED" && b === "NEEDS_REVISION")).toBe(true);
  expect(dwPatchableFrom("APPROVED")).toEqual([]);
});

test("the record page draws no button on a handed-on report, and names who signed an approved one off", () => {
  expect(RECORD_PAGE_SRC).toMatch(/handedOn && token === "SUBMITTED" \? \[\] : actionsFor\(token\)/);
  expect(RECORD_PAGE_SRC).toContain("ask the Ministry Admin to withdraw ");
  expect(RECORD_PAGE_SRC).toContain("ask the Ministry Admin to return it");
  expect(RECORD_PAGE_SRC).not.toMatch(/taken on their own screen/);
});

test("approved and handed-on reports are frozen, a legacy SUBMITTED row is not", () => {
  expect(dwIsFrozen({ status: "APPROVED" })).toBe(true);
  expect(dwIsFrozen({ status: "SUBMITTED", handedOnAt: "2026-10-09T10:00:00Z" })).toBe(true);
  expect(dwIsFrozen({ status: "SUBMITTED", handedOnAt: null })).toBe(false);
  expect(dwIsFrozen({ status: "PRE_SUBMISSION" })).toBe(false);
  expect(dwFrozenReason({ status: "APPROVED" })).toBe(DW_FROZEN_APPROVED);
  expect(dwFrozenReason({ status: "SUBMITTED", handedOnAt: "2026-10-09T10:00:00Z" })).toBe(DW_FROZEN_HANDED_ON);
  expect(dwFrozenReason({ status: "IN_PROGRESS" })).toBeNull();
});
