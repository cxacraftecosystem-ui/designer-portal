import { expect, test } from "@playwright/test";

import type { DwDraft, DwDraftStage } from "@/lib/designWorkshopStore";
import { neverReconciled } from "@/lib/workshopOpenability";

/**
 * "IS THERE A DRAFT" AND "IS THERE A WORKSHOP" ARE DIFFERENT QUESTIONS.
 *
 * Audit 2026-08-15 (MAJOR, frontend): a design-workshop id the account may not open rendered as a
 * real, editable 22-stage workshop. `ensureDraft(id)` fabricates a draft for whatever id it is
 * handed — no server call, no ownership check — and every render gate on the stage index keyed on
 * `draft`, not on the 404 the API had just answered. A designer forwarded a colleague's link saw
 * "Untitled design workshop", 0%, twenty-two clickable stage rows and one red line above them; she
 * read 0% as "not started yet", opened stage 4 and typed a day's interview into a record the server
 * will refuse for ever.
 *
 * The gate the two pages now use is this function, and it is the most expensive boolean on either of
 * them: `true` blanks the screen, `false` leaves a designer's only copy of a fortnight's fieldwork on
 * it. These cases are the ones that must never be got wrong in either direction.
 *
 * Run: `npx playwright test e2e/workshop-openability-unit.spec.ts --reporter=line`
 */

function draft(over: Partial<DwDraft> = {}): DwDraft {
  return {
    localId: "dwlocal-abc",
    remoteId: null,
    lastSyncedAt: null,
    stages: {},
    ...over
  } as DwDraft;
}

function stage(over: Record<string, unknown> = {}): DwDraftStage {
  return {
    stageKey: "WORKSHOP_SETUP",
    singletons: {},
    collections: {},
    updatedAt: 0,
    completeness: null,
    dirtyAt: null,
    lastPushedAt: null,
    serverLoadedAt: null,
    removedFrom: [],
    failure: null,
    ...over
  } as DwDraftStage;
}

test("a draft ensureDraft has just fabricated has never been reconciled", () => {
  expect(neverReconciled(draft())).toBe(true);
});

test("A FABRICATED DRAFT CARRIES A remoteId, so remoteId is not the test", () => {
  // This is the trap the function exists to avoid. `ensureDraft` stamps `remoteId` with the id it
  // was passed, so a draft invented for a workshop this account may not open is indistinguishable
  // from a downloaded one by that field alone. Anybody rewriting this check as
  // `draft.remoteId === null` restores the original defect in full.
  expect(neverReconciled(draft({ remoteId: "cly7realserverid" }))).toBe(true);
});

test("a workshop this browser has reconciled as a whole is NOT fabricated", () => {
  expect(neverReconciled(draft({ remoteId: "cly7realserverid", lastSyncedAt: 1_760_000_000_000 }))).toBe(false);
});

test("ONE stage read on a train is enough — that draft holds rows the repository sent", () => {
  // `lastSyncedAt` is stamped by a whole-workshop reconciliation; a stage's `serverLoadedAt` by
  // `adoptServerStage`/`foldStageInto` for that stage alone. A designer who opened one stage and
  // nothing else has the second and not the first, and blanking her screen would hide real rows.
  const held = draft({
    remoteId: "cly7realserverid",
    stages: { CLUSTER_CRAFT_BACKGROUND: stage({ serverLoadedAt: 1_760_000_000_000 }) }
  } as Partial<DwDraft>);
  expect(neverReconciled(held)).toBe(false);
});

test("stages that exist but were never read do not count as a reconciliation", () => {
  // The ordinary offline shape: a designer typed into three stages with no signal. Nothing here came
  // from the repository, so a 404 over it is still the fabricated case.
  const typed = draft({
    stages: {
      WORKSHOP_SETUP: stage({ dirtyAt: 1_760_000_000_000 }),
      WORKSHOP_PLAN: stage({ dirtyAt: 1_760_000_000_001 })
    }
  } as Partial<DwDraft>);
  expect(neverReconciled(typed)).toBe(true);
});

test("a serverLoadedAt of 0 counts as read — epoch is a timestamp, not an absence", () => {
  // Guards against the `!stage.serverLoadedAt` shorthand, which would call a stage read at the Unix
  // epoch unread. Unlikely, and exactly the kind of falsy-check that turns a rescue into a blank
  // screen over somebody's fieldwork.
  const held = draft({ stages: { WORKSHOP_SETUP: stage({ serverLoadedAt: 0 }) } } as Partial<DwDraft>);
  expect(neverReconciled(held)).toBe(false);
});

test("a lastSyncedAt of 0 counts as synced, for the same reason", () => {
  expect(neverReconciled(draft({ lastSyncedAt: 0 }))).toBe(false);
});

test("a draft with no stages object at all degrades to 'never reconciled' rather than throwing", () => {
  // This runs inside a catch handling a 404. A second error thrown out of it would replace an
  // actionable refusal with a blank screen and a console trace.
  expect(neverReconciled({ localId: "x", remoteId: null, lastSyncedAt: null } as unknown as DwDraft)).toBe(true);
});
