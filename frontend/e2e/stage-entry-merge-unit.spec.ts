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
      // Present-and-true or absent. Never present-and-false — on ANY entry, of any cardinality.
      expect(entry.merge === undefined || entry.merge === true).toBe(true);
    }
  }
});

test("on a stage this browser never read, no entry LACKS merge either", () => {
  /*
    THE COMPLEMENT OF THE TEST ABOVE, AND THE HOLE IT LEFT OPEN FOR AN ENTIRE ARM.

    "No entry ever carries merge:false" is satisfied just as well by an entry carrying NOTHING, so
    it was true, green, and blind to the defect: the collection loop sent no flag at all, and the
    one assertion that swept every entry at once nodded it through. A one-sided invariant only
    catches the mistake it was written for — and the mistake that actually happened was the other
    one.

    Stated over the whole payload rather than per-arm on purpose. The next entry kind added to
    `buildStageEntries` (a fourth arm, after singleton, collection and `_custom`) gets this
    assertion for free, and gets it in the direction that loses a designer's answers if it is
    forgotten — which is precisely how the collection arm came to be missing it.
  */
  const stages: DwDraftStage[] = [
    neverRead(singleton({ artisanHouseholds: 412 })),
    neverRead({ collections: { tool: [{ name: "Pit loom" }] } }),
    neverRead({ collections: { tool: [{ name: "Pit loom", _entryId: "cm3k", _clientKey: "k1" }] } }),
    // Every arm at once — singleton, two collection rows and `_custom` — which is also the shape
    // that proves the flag is not being set by one arm and read off another.
    neverRead({
      ...singleton({ artisanHouseholds: 412 }),
      collections: { tool: [{ name: "Pit loom" }, { name: "Charkha", _entryId: "cm9z" }] },
      custom: { dyesrc: "Madder root, local" }
    })
  ];

  for (const stage of stages) {
    const { entries } = buildStageEntries(STAGE, stage);
    // Guard the guard: a stage that sent nothing would satisfy the loop below vacuously, and three
    // of these four cases exist only to put entries in it.
    expect(entries.length).toBeGreaterThan(0);
    for (const entry of entries) {
      expect(entry.merge, `${entry.entityKey} was sent without merge from a never-read stage`).toBe(true);
    }
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * Collections obey the SAME rule, and this file used to assert that they did not
 *
 * WHAT WAS HERE BEFORE, because a corrected test is only as good as the reason it was wrong.
 * A test named "a collection row is never a merge, whether the stage was read or not" pinned the
 * never-read row as carrying NO flag, and justified it: "`merge` is a SINGLETON primitive. A
 * collection row is matched by `_entryId` or `_clientKey` and swept by `replaceCollections`, so
 * 'keep the keys I did not send' has no meaning for one — and a flag applied there would be a
 * client asserting a rule the server does not have."
 *
 * Every clause of that is about the wrong thing. Addressing (`_entryId`/`_clientKey`) decides WHICH
 * row is written; the sweep (`replaceCollections`/`emptiedEntities`) decides WHICH rows are
 * soft-deleted. Neither has any bearing on what happens to the keys INSIDE the row that was
 * matched — and `save_stage` writes that row's `data` wholesale. The server's `if entry.merge and
 * previous:` is not gated on cardinality and `previous` is filled for any row it matched, by
 * client key included; `StageEntryIn.merge` documents itself as keys "already stored under this
 * ROW". So the rule the client was said to be inventing is the rule the server already had, and
 * this test was holding the data-loss open with a `toEqual` that read as rigour.
 *
 * MEASURED, against the running API and Postgres, before the flag was added: the office wrote six
 * fields into one `tool` row; a never-read browser holding only `name` in that row sent
 * `{"name":"Pit loom","_clientKey":…}`; the server answered `HTTP 200 saved=1 updated=1 removed=0
 * errors={}` and the row became `{"name": "Pit loom"}` — five fields gone in place, 0
 * `RecordRevision` rows to recover them. That is the same walk, and the same silence, that the
 * singleton arm above was fixed for.
 * ──────────────────────────────────────────────────────────────────────────── */

const ROW = { name: "Pit loom", _entryId: "cm3k", _ordinal: 4, _clientKey: "k1" };

/** What every assertion below shares: the row's shape, with the flag left to the caller. */
const SENT_ROW = {
  entityKey: "tool",
  entryId: "cm3k",
  // Derived from the ARRAY ORDER at send time, never from the stored `_ordinal` — a row that
  // carried its old ordinal after a reorder sorts straight back to where it came from.
  ordinal: 0,
  // `_clientKey` stays IN `data` (the server reads it from there and matches on it, so
  // stripping it duplicates the row on a retry); `_entryId` and `_ordinal` are lifted out.
  data: { name: "Pit loom", _clientKey: "k1" }
};

test("a collection row on a stage this browser never read is sent as a MERGE", () => {
  const { entries, rowKeys } = buildStageEntries(STAGE, neverRead({ collections: { tool: [ROW] } }));
  const tools = entries.filter((entry) => entry.entityKey === "tool");

  // `toEqual` on the whole entry: the flag is half of it, and `data` still carrying every key this
  // browser holds is the other half.
  expect(tools).toEqual([{ ...SENT_ROW, merge: true }]);
  // The position in the array is what `save_stage` keys its per-field errors by, so the two
  // halves have to be built together or the form decodes one error map against another ordering.
  expect(rowKeys[entries.indexOf(tools[0])]).toEqual({ entityKey: "tool", rowIndex: 0 });
});

test("a collection row on a stage this browser HAS read carries no flag, so a cleared field is cleared", () => {
  // The half that stops the fix over-reaching, and it is not symmetry for its own sake: a browser
  // that has seen the server's copy MEANS it when it omits a key. Measured on the wire — a read
  // browser sending `{name, localName}` over a six-field row deleted `toolType`, `usedFor`,
  // `material` and `source`, which is a designer clearing four boxes and must keep working.
  const { entries } = buildStageEntries(STAGE, alreadyRead({ collections: { tool: [ROW] } }));
  expect(entries.filter((entry) => entry.entityKey === "tool")).toEqual([SENT_ROW]);
});

test("a collection row makes the stage report that it merged, so the push is not acknowledged as a read", () => {
  // `merged` is what stops the NEXT save from deleting what this one preserved: a merge push leaves
  // the server holding a superset of this browser's copy, so `markStagePushed` must not stamp
  // `serverLoadedAt`. Before the collection arm carried the flag, a stage whose only content was
  // collection rows reported `merged: false` and the push WAS acknowledged as a read — so the
  // second save came through as a wholesale replace even once the first had been made safe.
  const { merged } = buildStageEntries(STAGE, neverRead({ collections: { tool: [ROW] } }));
  expect(merged).toBe(true);
});

/*
  ── THE MERGE FLAG THE ACKNOWLEDGEMENT HAS TO SEE ────────────────────────────────────────────────

  `buildStageEntries` returns `merged` so that `markStagePushed` can refuse to stamp `serverLoadedAt`
  after a merge push. Everything about WHY is written up on `markStagePushed`'s `mergedEntries`; what
  matters here is that the flag is derived from the entries actually built rather than tracked beside
  the loops, because there are THREE places that set `merge: true` (the singleton arm, the collection
  loop and the `_custom` arm) and a flag set at one and forgotten at another would be wrong in the
  direction that loses a designer's answers. Deriving it is what made the collection arm's fix complete
  the moment the flag was added, rather than needing a second edit here that could have been missed.

  These assertions fail against the code as it stood before that change: `merged` did not exist, so
  the destructure yields `undefined` and every `toBe(true)` below fails.
*/

test("a never-read stage reports that it merged, so the push is not acknowledged as a read", () => {
  const { merged } = buildStageEntries(STAGE, neverRead(singleton({ code: "W-1" })));
  expect(merged).toBe(true);
});

test("a stage this browser has read reports no merge", () => {
  const { merged } = buildStageEntries(STAGE, alreadyRead(singleton({ code: "W-1" })));
  expect(merged).toBe(false);
});

test("the flag is read off the entries, so it agrees with them whichever arm set the flag", () => {
  for (const stage of [
    neverRead(singleton({ code: "W-1" })),
    alreadyRead(singleton({ code: "W-1" }))
  ]) {
    const { entries, merged } = buildStageEntries(STAGE, stage);
    // The property the derivation guarantees: `merged` is true exactly when some entry says so.
    expect(merged).toBe(entries.some((entry) => entry.merge === true));
  }
});

test("a stage with nothing to send cannot report a merge it did not make", () => {
  // No entries at all — the never-read empty-singleton rule. There is nothing to acknowledge, and in
  // particular nothing that would justify stamping the stage as read.
  const { entries, merged } = buildStageEntries(STAGE, neverRead({}));
  expect(entries).toEqual([]);
  expect(merged).toBe(false);
});
