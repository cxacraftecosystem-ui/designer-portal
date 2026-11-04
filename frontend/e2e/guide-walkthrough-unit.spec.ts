import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { GUIDE_STEPS } from "@/components/guide/steps";

const ROOT = join(__dirname, "..");
const PROTECTED = join(ROOT, "app", "(protected)");

const WORKSHOP_ARC = [
  "designer-profile",
  "design-workshop",
  "design-workshop-codes",
  "design-workshop-questionnaires",
  "design-workshop-stages",
  "design-workshop-sketches",
  "design-review",
  "design-workshop-readiness",
  "design-workshop-report",
  "design-workshop-history",
  "design-workshop-inspection"
];

const ARC_ROUTES: Record<string, string> = {
  "designer-profile": "/designers/profile`",
  "design-workshop": "/design-workshops`",
  "design-workshop-codes": "/design-workshops/[id]/codes`",
  "design-workshop-questionnaires": "/questionnaires`",
  "design-workshop-stages": "/design-workshops/[id]/stages/[stageKey]`",
  "design-workshop-sketches": "/sketches-and-prototypes`",
  "design-review": "/design-review`",
  "design-workshop-readiness": "/design-workshops/[id]/readiness`",
  "design-workshop-report": "/design-workshops/[id]/report`",
  "design-workshop-history": "/design-workshops/[id]/report/history`",
  "design-workshop-inspection": "/design-workshop-inspections`"
};

test("the guide carries the design & prototype workshop arc, after the record steps", () => {
  const ids = GUIDE_STEPS.map((step) => step.id);
  expect(ids, "the screens WALKTHROUGH.md names").toEqual(expect.arrayContaining(WORKSHOP_ARC));
  expect(ids.indexOf(WORKSHOP_ARC[0])).toBeGreaterThan(ids.indexOf("view-data"));
  const arcStart = ids.indexOf(WORKSHOP_ARC[0]);
  expect(
    ids.slice(arcStart),
    "every step from the first arc id onward is part of the arc, in order"
  ).toEqual(WORKSHOP_ARC);
});

test("every step links to a route that exists", () => {
  for (const step of GUIDE_STEPS) {
    const path = step.href.split("?")[0].replace(/^\//, "");
    const dir = join(PROTECTED, path);
    expect(existsSync(join(dir, "page.tsx")), `${step.id} → ${step.href}`).toBe(true);
  }
});

test("the anchors are unique, because the rail scrolls to them by id", () => {
  const ids = GUIDE_STEPS.map((step) => step.id);
  expect(new Set(ids).size).toBe(ids.length);
});

test("the workshop arc says the stage carries a COPY, and never that the report reads the record", () => {
  const stages = GUIDE_STEPS.find((step) => step.id === "design-workshop-stages");
  expect(stages, "the stage step is where the rule belongs").toBeTruthy();
  const prose = [stages!.summary, stages!.why, ...stages!.fields, ...stages!.watch].join(" ");
  expect(prose, "the copy rule is stated, not implied").toMatch(/copies its values into this stage when you save it/i);
  expect(prose).toMatch(/report prints that copy/i);
  const arc = GUIDE_STEPS.filter((step) => WORKSHOP_ARC.includes(step.id));
  for (const step of arc) {
    const all = [step.summary, step.why, ...step.fields, ...step.watch].join(" ");
    expect(all, `${step.id} must not promise a live re-read`).not.toMatch(
      /re-?reads? the (live )?record|shows the linked record|reads the linked record|stays in sync with the record/i
    );
  }
});

test("the printed guide still declares every screen of the arc", () => {
  expect(
    Object.keys(ARC_ROUTES),
    "one printed-guide route per arc id, in arc order"
  ).toEqual(WORKSHOP_ARC);
  const walkthrough = readFileSync(join(ROOT, "..", "docs", "WALKTHROUGH.md"), "utf8");
  for (const id of WORKSHOP_ARC) {
    expect(walkthrough, `WALKTHROUGH.md names ${ARC_ROUTES[id]} for ${id}`).toContain(
      ARC_ROUTES[id]
    );
  }
});
