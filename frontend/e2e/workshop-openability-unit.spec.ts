import { expect, test } from "@playwright/test";

import type { DwDraft, DwDraftStage } from "@/lib/designWorkshopStore";
import { neverReconciled } from "@/lib/workshopOpenability";

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
  expect(neverReconciled(draft({ remoteId: "cly7realserverid" }))).toBe(true);
});

test("a workshop this browser has reconciled as a whole is NOT fabricated", () => {
  expect(neverReconciled(draft({ remoteId: "cly7realserverid", lastSyncedAt: 1_760_000_000_000 }))).toBe(false);
});

test("ONE stage read on a train is enough — that draft holds rows the repository sent", () => {
  const held = draft({
    remoteId: "cly7realserverid",
    stages: { CLUSTER_CRAFT_BACKGROUND: stage({ serverLoadedAt: 1_760_000_000_000 }) }
  } as Partial<DwDraft>);
  expect(neverReconciled(held)).toBe(false);
});

test("stages that exist but were never read do not count as a reconciliation", () => {
  const typed = draft({
    stages: {
      WORKSHOP_SETUP: stage({ dirtyAt: 1_760_000_000_000 }),
      WORKSHOP_PLAN: stage({ dirtyAt: 1_760_000_000_001 })
    }
  } as Partial<DwDraft>);
  expect(neverReconciled(typed)).toBe(true);
});

test("a serverLoadedAt of 0 counts as read — epoch is a timestamp, not an absence", () => {
  const held = draft({ stages: { WORKSHOP_SETUP: stage({ serverLoadedAt: 0 }) } } as Partial<DwDraft>);
  expect(neverReconciled(held)).toBe(false);
});

test("a lastSyncedAt of 0 counts as synced, for the same reason", () => {
  expect(neverReconciled(draft({ lastSyncedAt: 0 }))).toBe(false);
});

test("a draft with no stages object at all degrades to 'never reconciled' rather than throwing", () => {
  expect(neverReconciled({ localId: "x", remoteId: null, lastSyncedAt: null } as unknown as DwDraft)).toBe(true);
});
