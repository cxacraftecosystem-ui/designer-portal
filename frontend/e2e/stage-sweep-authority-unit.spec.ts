import { expect, test } from "@playwright/test";

import { emptyStage, stageSweep, unsentAfterPush, type DwDraftStage } from "@/lib/designWorkshopStore";
import type { DwStage } from "@/lib/designWorkshops";

const STAGE_KEY = "TRADITIONAL_PROCESS_BASELINE";

const STAGE: DwStage = {
  number: 5,
  key: STAGE_KEY,
  title: "Traditional process baseline",
  purpose: "",
  notes: "",
  optionalStage: false,
  entities: [
    {
      key: "traditionalProcess",
      name: "DwTraditionalProcess",
      cardinality: "SINGLETON",
      title: "Process",
      description: "",
      parent: "",
      labelField: "",
      fields: [{ key: "processOverview", label: "Overview", type: "LONG_TEXT", tier: "BASIC", required: false }]
    },
    {
      key: "tool",
      name: "DwTool",
      cardinality: "COLLECTION",
      title: "Tools",
      description: "",
      parent: "",
      labelField: "name",
      fields: [{ key: "name", label: "Tool name", type: "TEXT", tier: "BASIC", required: true }]
    },
    {
      key: "processStep",
      name: "DwProcessStep",
      cardinality: "COLLECTION",
      title: "Steps",
      description: "",
      parent: "",
      labelField: "name",
      fields: [{ key: "name", label: "Step name", type: "TEXT", tier: "BASIC", required: true }]
    }
  ]
};

function draft(over: Partial<DwDraftStage>): DwDraftStage {
  return { ...emptyStage(STAGE_KEY), ...over };
}

test("a browser that has never read the stage may not tell the server which rows to keep", () => {
  const sweep = stageSweep(STAGE, draft({ removedFrom: ["tool"], serverLoadedAt: null }));
  expect(sweep.replaceCollections, "the sweep is not armed").toBe(false);
  expect(sweep.emptiedEntities, "and nothing is named for it to act on").toEqual([]);
  expect(sweep.withheld, "the deletion is reported as held back, not discarded").toEqual(["tool"]);
});

test("a browser that HAS read the stage still deletes, which is the whole point of the flag", () => {
  const sweep = stageSweep(STAGE, draft({ removedFrom: ["tool"], serverLoadedAt: 1_700_000_000_000 }));
  expect(sweep.replaceCollections).toBe(true);
  expect(sweep.emptiedEntities).toEqual(["tool"]);
  expect(sweep.withheld, "nothing is being held back when the sweep goes").toEqual([]);
});

test("no deletion, no sweep — a save that deleted nothing may not delete anything", () => {
  const sweep = stageSweep(STAGE, draft({ removedFrom: [], serverLoadedAt: 1_700_000_000_000 }));
  expect(sweep).toEqual({ replaceCollections: false, emptiedEntities: [], withheld: [] });
});

test("a stage this browser has never read and deleted nothing from holds nothing back", () => {
  const sweep = stageSweep(STAGE, draft({ removedFrom: [], serverLoadedAt: null }));
  expect(sweep).toEqual({ replaceCollections: false, emptiedEntities: [], withheld: [] });
});

test("a key the registry no longer declares is not a deletion instruction for anything", () => {
  const sweep = stageSweep(
    STAGE,
    draft({ removedFrom: ["tool", "loomLine", "traditionalProcess"], serverLoadedAt: 1_700_000_000_000 })
  );
  expect(sweep.emptiedEntities, "only this stage's own COLLECTIONS survive the filter").toEqual(["tool"]);
  expect(sweep.emptiedEntities).not.toContain("traditionalProcess");
});

test("a reserved key can never reach the wire, because it would 422 the whole stage", () => {
  expect(stageSweep(STAGE, draft({ removedFrom: ["_custom"], serverLoadedAt: 1 })).emptiedEntities).toEqual([]);
  expect(stageSweep(null, draft({ removedFrom: ["_custom", "tool"], serverLoadedAt: 1 })).emptiedEntities).toEqual([
    "tool"
  ]);
});

test("a withheld deletion is not acknowledged as sent, so it survives the push that could not carry it", () => {
  const stage = draft({ removedFrom: ["tool"], serverLoadedAt: null, dirtyAt: 1_000 });
  const sweep = stageSweep(STAGE, stage);
  const after = unsentAfterPush(stage, { dirtyAt: 1_000, removedFrom: sweep.emptiedEntities });
  expect(after.removedFrom, "the deletion is still owed to the server").toEqual(["tool"]);
  expect(after.dirtyAt, "and the stage is still listed as unsent work").toBe(1_000);
});

test("a deletion that WAS carried is acknowledged, or every sweep would re-run on every pass", () => {
  const stage = draft({ removedFrom: ["tool"], serverLoadedAt: 1_700_000_000_000, dirtyAt: 1_000 });
  const sweep = stageSweep(STAGE, stage);
  const after = unsentAfterPush(stage, { dirtyAt: 1_000, removedFrom: sweep.emptiedEntities });
  expect(after.removedFrom, "the server has been told; the flag is spent").toEqual([]);
  expect(after.dirtyAt).toBeNull();
});
