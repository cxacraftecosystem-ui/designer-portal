import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { GUIDE_STEPS } from "@/components/guide/steps";

/**
 * THE IN-APP WALKTHROUGH AND `docs/WALKTHROUGH.md` ARE TWO RENDERINGS OF ONE THING.
 *
 * That is the printed guide's own maintenance rule, and it had already been broken: `WALKTHROUGH.md`
 * grew a section called *The design & prototype workshop itself* — six screens ending in the report
 * handed to a ministry officer — while `components/guide/steps.ts` still declared exactly the ten
 * repository RECORD steps. A designer who opened the in-app Walkthrough looking for the one
 * deliverable the fortnight exists to produce found ten ways to file a record and no path to the
 * document. The divergence was written into both files as a note rather than left to be discovered,
 * which is the right thing to do with a gap you cannot close in the same commit — and this spec is
 * what stops the next one being silent.
 *
 * WHAT IS PINNED HERE AND WHY EACH IS THE FAILURE THAT ACTUALLY HAPPENS:
 *
 *  1. The workshop arc EXISTS and comes after the record steps, because the records exist first and
 *     the stage form points at them.
 *  2. Every step's `href` is a route that is really in the tree. A guide whose links 404 is worse
 *     than no guide: the reader concludes the feature is gone.
 *  3. The `id`s are unique. The rail scrolls to `#${id}` and the URL hash survives a reload, so a
 *     duplicate silently sends two steps to one anchor.
 *  4. NO STEP MAY SAY A REFERENCE PICKER RE-READS A LIVE RECORD. This is the one claim in the arc
 *     that is not a screen description: choosing a record COPIES its values onto the stage entry and
 *     the report prints the copy. Softening it into "the report shows the linked record" is the
 *     opposite of what the system does, and the difference is a document already in an officer's
 *     hands changing under him.
 */

const ROOT = join(__dirname, "..");
const PROTECTED = join(ROOT, "app", "(protected)");

/**
 * The ids of the design & prototype workshop arc, in the order `WALKTHROUGH.md` prints them.
 *
 * THIS LIST HELD SIX IDS UNTIL 2026-08-26, AND THE ORDER OF TWO OF THEM WAS WRONG — which is the
 * failure this spec exists to catch, arriving from the inside. The arc grew three screens
 * (`designer-profile`, `design-workshop-sketches`, `design-review`) and *Cards & tags* moved AHEAD
 * of *Stages*, because a code card is what a participant scans to reach a stage form and so has to
 * exist before the form is worth opening. `components/guide/steps.ts` and `docs/WALKTHROUGH.md` were
 * both rewritten to that nine-step order and this constant was not, so the spec was asserting a
 * printed order neither rendering had printed since. The two renderings agreeing is the whole
 * subject here, and a stale THIRD copy of the order is no better than a divergence between them:
 * update this list in the same edit as the other two, never afterwards.
 */
const WORKSHOP_ARC = [
  "designer-profile",
  "design-workshop",
  "design-workshop-codes",
  "design-workshop-stages",
  "design-workshop-sketches",
  "design-review",
  "design-workshop-readiness",
  "design-workshop-report",
  "design-workshop-history"
];

test("the guide carries the design & prototype workshop arc, after the record steps", () => {
  const ids = GUIDE_STEPS.map((step) => step.id);
  expect(ids, "the screens WALKTHROUGH.md names").toEqual(expect.arrayContaining(WORKSHOP_ARC));
  // AFTER, not before. The order is the order the work happens in, and a guide that opened on the
  // 22-stage form would be teaching a designer to point at records that do not exist yet.
  expect(ids.indexOf(WORKSHOP_ARC[0])).toBeGreaterThan(ids.indexOf("view-data"));
  // And in the printed order, so the two renderings can be read side by side.
  expect(ids.filter((id) => WORKSHOP_ARC.includes(id))).toEqual(WORKSHOP_ARC);
});

test("every step links to a route that exists", () => {
  /*
    Resolved against the app directory rather than by fetching, so this needs no server. A `?new=1`
    query is a screen's own create affordance and is stripped; what is checked is that the segment
    before it is a real route folder holding a page.
  */
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
  /*
    THE ONE CLAIM IN THIS ARC THAT IS NOT A SCREEN DESCRIPTION. Its authority is `REFERENCE_HYDRATION`
    in `backend/app/services/stage_schema.py`: hydration copies at SAVE time and the report reads the
    frozen copy. A guide sentence saying the report shows the linked record would teach a designer
    that editing an artisan next week updates a document handed over last month — which is the one
    thing this application must never do, and the reason the whole hydration table exists.
  */
  const stages = GUIDE_STEPS.find((step) => step.id === "design-workshop-stages");
  expect(stages, "the stage step is where the rule belongs").toBeTruthy();
  const prose = [stages!.summary, stages!.why, ...stages!.fields, ...stages!.watch].join(" ");
  expect(prose, "the copy rule is stated, not implied").toMatch(/COPIES its values/);
  expect(prose).toMatch(/report prints that copy/i);
  // The wordings that would invert it.
  const arc = GUIDE_STEPS.filter((step) => WORKSHOP_ARC.includes(step.id));
  for (const step of arc) {
    const all = [step.summary, step.why, ...step.fields, ...step.watch].join(" ");
    expect(all, `${step.id} must not promise a live re-read`).not.toMatch(
      /re-?reads? the (live )?record|shows the linked record|reads the linked record|stays in sync with the record/i
    );
  }
});

test("the printed guide still declares every screen of the arc", () => {
  /*
    THE OTHER HALF OF THE PAIR, read rather than duplicated. If somebody adds a screen to
    `WALKTHROUGH.md` and not to `steps.ts` — which is exactly what happened once — this fails and
    names the file to open. It matches on the ROUTES rather than on the headings, because a heading
    is prose somebody will reword and a route is the thing a designer has to be able to reach.

    THE LIST GUARDED SIX ROUTES WHILE THE ARC CARRIED NINE SCREENS, from 2026-08-26 until this line
    was written. A pair-check that covers two thirds of the pair is worse than none, because it
    reports green over the gap: the designer profile, the sketches screen and the design review could
    each have been dropped from the printed guide without a single test going red. The rule is that
    this list holds one route per id in `WORKSHOP_ARC` — add to both together, or neither.
  */
  const walkthrough = readFileSync(join(ROOT, "..", "docs", "WALKTHROUGH.md"), "utf8");
  for (const route of [
    "/designers/profile`",
    "/design-workshops`",
    "/design-workshops/[id]/codes`",
    "/design-workshops/[id]/stages/[stageKey]`",
    "/sketches-and-prototypes`",
    "/design-review`",
    "/design-workshops/[id]/readiness`",
    "/design-workshops/[id]/report`",
    "/design-workshops/[id]/report/history`"
  ]) {
    expect(walkthrough, `WALKTHROUGH.md names ${route}`).toContain(route);
  }
});
