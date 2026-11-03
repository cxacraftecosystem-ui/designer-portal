import { expect, test } from "@playwright/test";

import { buildStageEntries, emptyStage, type DwDraftStage } from "@/lib/designWorkshopStore";
import type { DwEntryData, DwStage } from "@/lib/designWorkshops";

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

function neverRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), ...overrides };
}

function alreadyRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), serverLoadedAt: 1_700_000_000_000, ...overrides };
}

const singleton = (values: DwEntryData): Partial<DwDraftStage> => ({
  singletons: { clusterBackground: values }
});

test("a stage this browser never read sends what it has as a MERGE", () => {
  const { entries, rowKeys } = buildStageEntries(STAGE, neverRead(singleton({ artisanHouseholds: 412 })));

  expect(entries).toEqual([{ entityKey: "clusterBackground", data: { artisanHouseholds: 412 }, merge: true }]);
  expect(rowKeys).toEqual([null]);
});

test("an empty singleton is not sent at all when the stage was never read", () => {
  expect(buildStageEntries(STAGE, neverRead()).entries).toEqual([]);
  expect(buildStageEntries(STAGE, neverRead(singleton({}))).entries).toEqual([]);
});

test("a blank string is not an answer, so it does not unlock the send either", () => {
  expect(buildStageEntries(STAGE, neverRead(singleton({ clusterHistory: "" }))).entries).toEqual([]);
  expect(buildStageEntries(STAGE, neverRead(singleton({ clusterHistory: "   " }))).entries).toEqual([]);
  expect(buildStageEntries(STAGE, neverRead(singleton({ artisanHouseholds: null }))).entries).toEqual([]);

  expect(buildStageEntries(STAGE, neverRead(singleton({ artisanHouseholds: 0 }))).entries).toEqual([
    { entityKey: "clusterBackground", data: { artisanHouseholds: 0 }, merge: true }
  ]);
});

test("a stage this browser has read never carries the flag, even when it is empty", () => {
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
  const stages: DwDraftStage[] = [
    neverRead(singleton({ artisanHouseholds: 1 })),
    neverRead({ ...singleton({}), collections: { tool: [{ name: "Pit loom" }] } }),
    alreadyRead(singleton({ artisanHouseholds: 1 })),
    alreadyRead(singleton({})),
    alreadyRead({ ...singleton({}), collections: { tool: [{ name: "Pit loom" }] } })
  ];
  for (const stage of stages) {
    for (const entry of buildStageEntries(STAGE, stage).entries) {
      expect(entry.merge === undefined || entry.merge === true).toBe(true);
    }
  }
});

test("on a stage this browser never read, no entry LACKS merge either", () => {
  const stages: DwDraftStage[] = [
    neverRead(singleton({ artisanHouseholds: 412 })),
    neverRead({ collections: { tool: [{ name: "Pit loom" }] } }),
    neverRead({ collections: { tool: [{ name: "Pit loom", _entryId: "cm3k", _clientKey: "k1" }] } }),
    neverRead({
      ...singleton({ artisanHouseholds: 412 }),
      collections: { tool: [{ name: "Pit loom" }, { name: "Charkha", _entryId: "cm9z" }] },
      custom: { dyesrc: "Madder root, local" }
    })
  ];

  for (const stage of stages) {
    const { entries } = buildStageEntries(STAGE, stage);
    expect(entries.length).toBeGreaterThan(0);
    for (const entry of entries) {
      expect(entry.merge, `${entry.entityKey} was sent without merge from a never-read stage`).toBe(true);
    }
  }
});

const ROW = { name: "Pit loom", _entryId: "cm3k", _ordinal: 4, _clientKey: "k1" };

const SENT_ROW = {
  entityKey: "tool",
  entryId: "cm3k",
  ordinal: 0,
  data: { name: "Pit loom", _clientKey: "k1" }
};

test("a collection row on a stage this browser never read is sent as a MERGE", () => {
  const { entries, rowKeys } = buildStageEntries(STAGE, neverRead({ collections: { tool: [ROW] } }));
  const tools = entries.filter((entry) => entry.entityKey === "tool");

  expect(tools).toEqual([{ ...SENT_ROW, merge: true }]);
  expect(rowKeys[entries.indexOf(tools[0])]).toEqual({ entityKey: "tool", rowIndex: 0 });
});

test("a collection row on a stage this browser HAS read carries no flag, so a cleared field is cleared", () => {
  const { entries } = buildStageEntries(STAGE, alreadyRead({ collections: { tool: [ROW] } }));
  expect(entries.filter((entry) => entry.entityKey === "tool")).toEqual([SENT_ROW]);
});

test("a collection row makes the stage report that it merged, so the push is not acknowledged as a read", () => {
  const { merged } = buildStageEntries(STAGE, neverRead({ collections: { tool: [ROW] } }));
  expect(merged).toBe(true);
});

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
    expect(merged).toBe(entries.some((entry) => entry.merge === true));
  }
});

test("a stage with nothing to send cannot report a merge it did not make", () => {
  const { entries, merged } = buildStageEntries(STAGE, neverRead({}));
  expect(entries).toEqual([]);
  expect(merged).toBe(false);
});
