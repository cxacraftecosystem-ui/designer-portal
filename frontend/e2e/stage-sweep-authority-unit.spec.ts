import { expect, test } from "@playwright/test";

import { emptyStage, stageSweep, unsentAfterPush, type DwDraftStage } from "@/lib/designWorkshopStore";
import type { DwStage } from "@/lib/designWorkshops";

/**
 * The sweep is EARNED, and a deletion it cannot carry is kept rather than dropped.
 *
 * ── THE DEFECT THIS PINS, MEASURED BEFORE IT WAS FIXED ───────────────────────────────────────────
 *
 * Both send sites — the stage page's `save` and the store's `runSync` — armed the server's collection
 * sweep with `stage.removedFrom.length > 0` and nothing else. `removedFrom` grows on ANY row deletion
 * (`patchCollection` compares row counts), and `save_stage` scopes the sweep to
 * `(touched_entities | emptiedEntities) & collection_keys` — every entity the payload NAMES. So one
 * row deleted on a stage this browser had never downloaded deleted every row the office had written
 * in that collection, plus every row in every OTHER collection the payload happened to mention.
 *
 * Against the running API and Postgres, one deletion on a never-read draft holding one row in each of
 * two collections:
 *
 *   PUT … {entries: [processStep×1, tool×1, both merge:true],
 *          replaceCollections: true, emptiedEntities: ["tool"]}
 *   -> HTTP 200 saved=2 created=2 updated=0 removed=5 errors={}
 *
 * Three `tool` rows and two `processStep` rows gone, in a save the page reported as
 * "Stage saved — 2 added, 0 updated, 5 removed". `merge: true` is no defence: it preserves keys
 * INSIDE a row the server matched and says nothing whatever about a row the payload never named.
 * With {@link stageSweep} the identical walk answers `removed=0` with every office row and every one
 * of its keys intact, and an authoritative browser's deletion still answers `removed=1`.
 *
 * ── WHY THERE ARE TWO HALVES TO EVERY CASE BELOW ─────────────────────────────────────────────────
 *
 * A fix that returned `{replaceCollections: false, emptiedEntities: []}` for everything would stop the
 * destruction and silently throw away every deletion instead — so no test here asserts only that the
 * sweep is withheld. Each asks BOTH what the payload carries AND what the draft is left holding, and
 * the last pair carry the chain all the way to the acknowledgement, which is where a withheld
 * deletion would otherwise be marked as sent and lost for good.
 */

const STAGE_KEY = "TRADITIONAL_PROCESS_BASELINE";

/** Two collections and one singleton, the shape this stage really has. */
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
  // THE OTHER HALF. The deletion is not lost — it is held, and named, so the page can say so.
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
  // The ordinary never-downloaded save. It must not produce a sentence about a deletion, or every
  // stage opened in a courtyard would carry a warning about something the designer never did.
  const sweep = stageSweep(STAGE, draft({ removedFrom: [], serverLoadedAt: null }));
  expect(sweep).toEqual({ replaceCollections: false, emptiedEntities: [], withheld: [] });
});

test("a key the registry no longer declares is not a deletion instruction for anything", () => {
  // A draft written when `loomLine` was a collection of this stage, opened after the registry moved
  // on. Android filters the same list against `spec.collections` one line before it sends it.
  const sweep = stageSweep(
    STAGE,
    draft({ removedFrom: ["tool", "loomLine", "traditionalProcess"], serverLoadedAt: 1_700_000_000_000 })
  );
  expect(sweep.emptiedEntities, "only this stage's own COLLECTIONS survive the filter").toEqual(["tool"]);
  // `traditionalProcess` is a singleton: the server ignores it there, but naming it would be this
  // client asserting a deletion the protocol has no meaning for.
  expect(sweep.emptiedEntities).not.toContain("traditionalProcess");
});

test("a reserved key can never reach the wire, because it would 422 the whole stage", () => {
  // `StageSaveIn` refuses an `emptiedEntities` entry starting with `_` — and a 422 refuses the save
  // ENTIRELY, so one bookkeeping artefact would cost every answer on the stage. Neither branch may
  // emit one: with a spec it is filtered as "not a declared collection", and with no spec (a stage
  // whose registry entry this build cannot resolve) it is filtered by name.
  expect(stageSweep(STAGE, draft({ removedFrom: ["_custom"], serverLoadedAt: 1 })).emptiedEntities).toEqual([]);
  expect(stageSweep(null, draft({ removedFrom: ["_custom", "tool"], serverLoadedAt: 1 })).emptiedEntities).toEqual([
    "tool"
  ]);
});

test("a withheld deletion is not acknowledged as sent, so it survives the push that could not carry it", () => {
  const stage = draft({ removedFrom: ["tool"], serverLoadedAt: null, dirtyAt: 1_000 });
  const sweep = stageSweep(STAGE, stage);
  // Exactly what both send sites now hand the acknowledgement: the list the PAYLOAD carried.
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
