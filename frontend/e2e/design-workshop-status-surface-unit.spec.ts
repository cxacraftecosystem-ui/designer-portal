import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { DW_LEGAL_TRANSITIONS, dwPatchableFrom, type DwStatus } from "@/lib/designWorkshops";

/**
 * THE THREE PLACES A `DesignWorkshopStatus` SURFACES, PROVED TO KNOW ABOUT ALL EIGHT OF THEM.
 *
 * ── WHY THIS SPEC EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * The review loop added PRE_SUBMISSION, NEEDS_REVISION and APPROVED to the enum on 2026-09-13. Two
 * of the three landed in `StatusBadge` by ACCIDENT — `RecordStatus` already spelled NEEDS_REVISION
 * and APPROVED the same way — and PRE_SUBMISSION, the one genuinely new token, got no entry in
 * either map. `StatusBadge` falls back with `tone[status] ?? tone.DRAFT`, so the headline status of
 * an entire new workflow stage painted the byte-identical grey pill that means "nobody has touched
 * this", on all six surfaces that draw the chip — including the inspecting officers' queue, which
 * applies no status filter and is therefore mostly PRE_SUBMISSION rows. The label was missing too,
 * so `humanize()` answered "Pre submission" while the filter dropdown one page over offered
 * "Pre-submission": a designer filtered by one word and got rows reading another.
 *
 * That component's own header had predicted this failure in as many words, and had already recorded
 * it happening once before, on 2026-08-26, for four statuses at a time. A comment that describes a
 * defect is not a check for it. This file is the check.
 *
 * ── AND THE SECOND HALF: A DERIVATION THAT WAS ONLY A PROMISE ───────────────────────────────────
 *
 * `actionsFor` in the record page claims in bold that "EVERY BUTTON HERE IS CHECKED AGAINST THE
 * SERVER'S OWN GRAPH BY `dwPatchableFrom`". `dwPatchableFrom`, `DW_LEGAL_TRANSITIONS` and
 * `DW_DECISION_EDGES` were added to `lib/designWorkshops.ts` in the same change for exactly that,
 * and then nothing called them for a day: the buttons were a hand-written switch that happened to
 * agree with the server. Two other comments — in `lib/designWorkshops.ts` and in the backend's
 * `design_workshop_review_loop.py` ("impossible by construction") — asserted the same derivation.
 * The trap was laid for whoever tightened the server graph and its mirror together, trusting all
 * three: their button would have 422'd, in the card whose whole job is the workshop's forward act.
 *
 * ── READ OFF DISK, NEVER REMEMBERED ─────────────────────────────────────────────────────────────
 *
 * The status list comes from `DW_LEGAL_TRANSITIONS`' own key set and the maps come out of the real
 * `.tsx`. A hard-coded expectation here would be one more copy of the vocabulary, agreeing with the
 * app only on the day it was typed, which is the genre of defect this file is about. If one of
 * these fails, do not edit the expectation — find which side moved.
 *
 * PURE NODE — no browser, no server, no database. `StatusBadge` is a component with no exported
 * maps and there is no React renderer in devDependencies, so the two literals are lifted out of the
 * source and evaluated: the same treatment `role-ladder-parity-unit.spec.ts` gives `ROLE_RANK` in
 * `deps.py`.
 *
 * Run: `npx playwright test e2e/design-workshop-status-surface-unit.spec.ts --reporter=line`
 */

const BADGE_SRC = readFileSync(join(__dirname, "..", "components", "StatusBadge.tsx"), "utf8");
const RECORD_PAGE_SRC = readFileSync(
  join(__dirname, "..", "app", "(protected)", "design-workshops", "[id]", "page.tsx"),
  "utf8"
);
const LIST_PAGE_SRC = readFileSync(
  join(__dirname, "..", "app", "(protected)", "design-workshops", "page.tsx"),
  "utf8"
);

/** Every member of the enum, derived from the transition graph's own key set. */
const DW_STATUSES = Object.keys(DW_LEGAL_TRANSITIONS) as DwStatus[];

/**
 * One `const NAME: Record<string, string> = { ... };` literal out of the component, evaluated.
 *
 * The closing `};` is matched at column zero, which is how a module-level literal ends in this file.
 * A lazy `[\s\S]*?\}` would stop at the first brace somebody puts inside the body.
 */
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
  // The failure the fallback produces is not "unstyled". It is "styled as one specific OTHER
  // status", which is why equality with DRAFT's string is the assertion and not truthiness.
  const impostors = DW_STATUSES.filter((s) => s !== "DRAFT" && tone[s] === tone.DRAFT);
  expect(impostors, `${impostors.join(", ")} is painted exactly like a draft`).toEqual([]);
});

test("ARCHIVED is a different grey from DRAFT, which is the same rule one rung down", () => {
  // Two statuses that both mean "nothing is happening", for opposite reasons. The component's
  // header argues this at length; without a check it stays an argument rather than a property.
  expect(tone.ARCHIVED).not.toEqual(tone.DRAFT);
});

test("PRE_SUBMISSION is not COMPLETE's pill either, because the two sit on one list", () => {
  // "I have finished with it" against "it is out of my hands" is the distinction a designer reads
  // off this chip on /design-workshops, and both are amber by design. Same family, different rung.
  expect(tone.PRE_SUBMISSION).not.toEqual(tone.COMPLETE);
});

test("the two maps carry the same key set, which is what makes a missing tone visible", () => {
  expect(Object.keys(tone).sort()).toEqual(Object.keys(label).sort());
});

test("a status's badge spells it the way the filter that found it spells it", () => {
  // `STATUS_OPTIONS` on /design-workshops is the control a designer uses to ask for these rows. A
  // chip spelling the answer differently from the question reads as a different status — which is
  // exactly what "Pre submission" (from `humanize`) against "Pre-submission" (the filter) did.
  const options = [...LIST_PAGE_SRC.matchAll(/\{ value: "([A-Z_]*)", label: "([^"]+)" \}/g)];
  expect(options.length, "STATUS_OPTIONS could not be read off /design-workshops").toBeGreaterThan(5);
  for (const [, value, optionLabel] of options) {
    if (!value) continue; // the "Any status" row, which is an absence and not a status
    expect(label[value], `the badge has no curated label for ${value}`).toBe(optionLabel);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * The record page's buttons, against the server's own graph
 * ──────────────────────────────────────────────────────────────────────────── */

/** `const NAME: SubmissionAction = { status: "X", ... }` → NAME → X. */
function submissionActionStatuses(): Record<string, DwStatus> {
  const out: Record<string, DwStatus> = {};
  for (const m of RECORD_PAGE_SRC.matchAll(
    /const ([A-Z_]+): SubmissionAction = \{\s*status: "([A-Z_]+)"/g
  )) {
    out[m[1]] = m[2] as DwStatus;
  }
  return out;
}

/** The `candidatesFor` switch, as `status -> the action constants it returns`. */
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
  // The whole defect was a bold docstring over a hand-written switch. `actionsFor` must both import
  // the mirror and apply it; the import on its own would leave the claim as false as it was.
  expect(RECORD_PAGE_SRC).toContain("dwPatchableFrom,");
  const actionsFor = RECORD_PAGE_SRC.match(
    /function actionsFor\(status: string\): SubmissionAction\[\] \{[\s\S]*?^\}/m
  );
  expect(actionsFor).not.toBeNull();
  expect(actionsFor![0]).toContain("dwPatchableFrom(status)");
  expect(actionsFor![0]).toContain("filter");
});

test("no button the record page offers is an edge a header edit may not take", () => {
  // The filter makes this true by construction today. It is asserted anyway, because the assertion
  // is what survives somebody "simplifying" the filter back out — and because it states, in a place
  // that can fail, the property those three comments promise.
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
    // And the filter must not be silently emptying a card that HAS candidates: that is a third,
    // different reason for an empty button row, and `noActionsReason` has to be able to say which.
    if (names.length > 0) {
      const offered = wanted.filter((to) => patchable.includes(to));
      expect(offered.length, `every candidate from ${from} was struck out by the graph`).toBeGreaterThan(0);
    }
  }
});
