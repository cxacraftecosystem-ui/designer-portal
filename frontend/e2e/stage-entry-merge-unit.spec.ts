import { expect, test } from "@playwright/test";

import { buildStageEntries, emptyStage, type DwDraftStage } from "@/lib/designWorkshopStore";
import type { DwEntryData, DwStage } from "@/lib/designWorkshops";

/**
 * `buildStageEntries` on its own — no browser, no server, no IndexedDB.
 *
 * WHAT IT DECIDES, AND WHY IT IS WORTH A SPEC OF ITS OWN. This function builds the payload every
 * stage save sends, and on 2026-08-08 it gained the one flag that stands between a designer and the
 * office's work: `merge`. `save_stage` replaces a singleton row's `data` WHOLESALE and writes no
 * `RecordRevision` for stage entries, so a browser that had never downloaded a stage and typed one
 * field into it sent `{artisanHouseholds: 412}` and deleted the seven fields written in the office
 * — in place, unrecoverably — while the amber banner above the form promised in so many words that
 * nothing left blank would overwrite an answer recorded elsewhere.
 *
 * The server half is covered by `backend/tests/test_stage_sync.py`, which needs Postgres and the
 * whole stack. The CLIENT half — which entries get the flag, and which must not — is pure, and
 * three lines decide it. Nothing asserted them.
 *
 * THE OMISSION IS AS LOAD-BEARING AS THE FLAG. `APIModel` is `extra="forbid"`, so an API that
 * predates `merge` answers 422 for every entry that carries it, and a client newer than the server
 * is an ordinary state here (a handset updates on wifi, the API updates when somebody deploys).
 * Sending `merge: false` would turn that skew from "the never-downloaded saves are refused" into
 * "every save is refused". So `false` is not sent, and this file pins the ABSENCE of the key rather
 * than its value — `toEqual` on the whole entry is what makes that a real assertion instead of a
 * check that reads `undefined` as agreement.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * A miniature stage: one singleton and one collection
 *
 * Hand-built rather than fetched. The point of a pure module is that it can be checked without the
 * 496-field registry being up, and a fixture that changed when somebody edited stage 1 would make
 * every failure here ambiguous.
 * ──────────────────────────────────────────────────────────────────────────── */

const STAGE: DwStage = {
  number: 4,
  key: "CLUSTER_CRAFT_BACKGROUND",
  title: "Cluster and craft background",
  purpose: "",
  notes: "",
  optionalStage: false,
  entities: [
    {
      key: "clusterBackground",
      name: "DwClusterBackground",
      cardinality: "SINGLETON",
      title: "Cluster background",
      description: "",
      parent: "",
      labelField: "",
      fields: [
        { key: "artisanHouseholds", label: "Artisan households", type: "INT", tier: "BASIC", required: false },
        { key: "clusterHistory", label: "Cluster history", type: "LONG_TEXT", tier: "BASIC", required: false }
      ]
    },
    {
      key: "tool",
      name: "DwTool",
      cardinality: "COLLECTION",
      title: "Tools",
      description: "",
      parent: "",
      labelField: "name",
      fields: [{ key: "name", label: "Tool name", type: "TEXT", tier: "BASIC", required: false }]
    }
  ]
};

/** A stage this browser has never reconciled with the server — `serverLoadedAt` is null. */
function neverRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), ...overrides };
}

/** The same stage after the server's copy was folded in. */
function alreadyRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), serverLoadedAt: 1_700_000_000_000, ...overrides };
}

const singleton = (values: DwEntryData): Partial<DwDraftStage> => ({
  singletons: { clusterBackground: values }
});

/* ────────────────────────────────────────────────────────────────────────────
 * The never-downloaded stage
 * ──────────────────────────────────────────────────────────────────────────── */

test("a stage this browser never read sends what it has as a MERGE", () => {
  const { entries, rowKeys } = buildStageEntries(STAGE, neverRead(singleton({ artisanHouseholds: 412 })));

  // The whole entry, not just the flag: `merge` being true is only half of what has to be true,
  // and `data` still carrying every key this browser holds is the other half.
  expect(entries).toEqual([{ entityKey: "clusterBackground", data: { artisanHouseholds: 412 }, merge: true }]);
  expect(rowKeys).toEqual([null]);
});

test("an empty singleton is not sent at all when the stage was never read", () => {
  // The first half of the guard, and the one that predates `merge`. `{"data": {}}` deletes every
  // answer in the entity, so the payload that says nothing must not be sent — and it is safe to
  // omit because `save_stage` only touches the entities the payload names.
  expect(buildStageEntries(STAGE, neverRead()).entries).toEqual([]);
  expect(buildStageEntries(STAGE, neverRead(singleton({}))).entries).toEqual([]);
});

test("a blank string is not an answer, so it does not unlock the send either", () => {
  // `isFilled` is character-for-character the server's `_is_filled`. A form that rendered and
  // banked an empty box is exactly the never-downloaded case, and reading "" as "the designer
  // typed something" would put the deleting payload back on the wire.
  expect(buildStageEntries(STAGE, neverRead(singleton({ clusterHistory: "" }))).entries).toEqual([]);
  expect(buildStageEntries(STAGE, neverRead(singleton({ clusterHistory: "   " }))).entries).toEqual([]);
  expect(buildStageEntries(STAGE, neverRead(singleton({ artisanHouseholds: null }))).entries).toEqual([]);

  // And a zero IS an answer. A cluster with no power looms recorded as 0 is a finding; dropping it
  // as falsy would be the classic version of this mistake.
  expect(buildStageEntries(STAGE, neverRead(singleton({ artisanHouseholds: 0 }))).entries).toEqual([
    { entityKey: "clusterBackground", data: { artisanHouseholds: 0 }, merge: true }
  ]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The stage this browser HAS read
 * ──────────────────────────────────────────────────────────────────────────── */

test("a stage this browser has read never carries the flag, even when it is empty", () => {
  // The half that stops the fix over-reaching. A browser that has seen the server's copy MEANS it
  // when it omits a key: that is a designer clearing a field, and it must reach the repository.
  // `toEqual` rather than a check on `merge`, because the assertion is that the key is ABSENT —
  // sending `merge: false` to an API that predates the field is a 422 on every stage save.
  expect(buildStageEntries(STAGE, alreadyRead(singleton({ artisanHouseholds: 412 }))).entries).toEqual([
    { entityKey: "clusterBackground", data: { artisanHouseholds: 412 } }
  ]);
  expect(buildStageEntries(STAGE, alreadyRead(singleton({}))).entries).toEqual([
    { entityKey: "clusterBackground", data: {} }
  ]);
  expect(buildStageEntries(STAGE, alreadyRead()).entries).toEqual([
    { entityKey: "clusterBackground", data: {} }
  ]);
});

test("no entry ever carries merge:false", () => {
  // Stated once over every shape at once, because this is the property a later simplification
  // would break: `merge: neverRead` is the obvious tidy-up of the ternary, it is type-correct, it
  // passes every other test in this file — and it would have an API that predates the field refuse
  // EVERY stage save rather than only the never-downloaded ones.
  const stages: DwDraftStage[] = [
    neverRead(singleton({ artisanHouseholds: 1 })),
    neverRead({ ...singleton({}), collections: { tool: [{ name: "Pit loom" }] } }),
    alreadyRead(singleton({ artisanHouseholds: 1 })),
    alreadyRead(singleton({})),
    alreadyRead({ ...singleton({}), collections: { tool: [{ name: "Pit loom" }] } })
  ];
  for (const stage of stages) {
    for (const entry of buildStageEntries(STAGE, stage).entries) {
      // Present-and-true or absent. Never present-and-false, and never present on a collection row.
      expect(entry.merge === undefined || entry.merge === true).toBe(true);
    }
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * Collections are untouched by any of it
 * ──────────────────────────────────────────────────────────────────────────── */

test("a collection row is never a merge, whether the stage was read or not", () => {
  // `merge` is a SINGLETON primitive. A collection row is matched by `_entryId` or `_clientKey`
  // and swept by `replaceCollections`, so "keep the keys I did not send" has no meaning for one —
  // and a flag applied there would be a client asserting a rule the server does not have.
  const rows = { tool: [{ name: "Pit loom", _entryId: "cm3k", _ordinal: 4, _clientKey: "k1" }] };

  for (const stage of [neverRead({ collections: rows }), alreadyRead({ collections: rows })]) {
    const { entries, rowKeys } = buildStageEntries(STAGE, stage);
    const tools = entries.filter((entry) => entry.entityKey === "tool");
    expect(tools).toEqual([
      {
        entityKey: "tool",
        entryId: "cm3k",
        // Derived from the ARRAY ORDER at send time, never from the stored `_ordinal` — a row that
        // carried its old ordinal after a reorder sorts straight back to where it came from.
        ordinal: 0,
        // `_clientKey` stays IN `data` (the server reads it from there and matches on it, so
        // stripping it duplicates the row on a retry); `_entryId` and `_ordinal` are lifted out.
        data: { name: "Pit loom", _clientKey: "k1" }
      }
    ]);
    // The position in the array is what `save_stage` keys its per-field errors by, so the two
    // halves have to be built together or the form decodes one error map against another ordering.
    expect(rowKeys[entries.indexOf(tools[0])]).toEqual({ entityKey: "tool", rowIndex: 0 });
  }
});
