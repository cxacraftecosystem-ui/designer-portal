import { expect, test } from "@playwright/test";

import {
  removalIsADeletion,
  rowsTheServerCouldHold,
  type ServerHeldRows
} from "@/components/designworkshop/EntityForm";
import { emptyStage, foldStageInto, stageSweep, type DwDraftStage } from "@/lib/designWorkshopStore";
import { blankRow, type DwRow, type DwStage, type DwStageData } from "@/lib/designWorkshops";

function field(key: string) {
  return {
    key,
    label: key,
    type: "TEXT",
    required: false,
    tier: "BASIC",
    help: "",
    unit: "",
    options: [],
    refModel: "",
    refScope: "",
    refFilterBy: ""
  } as unknown as DwStage["entities"][number]["fields"][number];
}

const SPEC: DwStage = {
  number: 13,
  key: "PROTOTYPE_DEVELOPMENT",
  title: "Prototype development",
  purpose: "",
  notes: "",
  optionalStage: false,
  entities: [
    {
      key: "prototype",
      name: "prototype",
      cardinality: "COLLECTION",
      title: "Prototypes",
      description: "",
      parent: "",
      labelField: "name",
      fields: [field("name")]
    }
  ]
};

function draft(over: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage("PROTOTYPE_DEVELOPMENT"), ...over };
}

function officeRows(): DwRow[] {
  return Array.from({ length: 6 }, (_, index) => ({ _entryId: `e${index + 1}`, name: `Prototype ${index + 1}` }));
}

function serverStage(rows: DwRow[]): DwStageData {
  return { singleton: {}, collections: { prototype: rows } };
}

function recordsADeletion(before: DwRow[], after: DwRow[], removed: DwRow | undefined, held: ServerHeldRows): boolean {
  return after.length < before.length && removalIsADeletion(removed, held.prototype);
}

function whatTheCountRuleSaid(before: DwRow[], after: DwRow[]): boolean {
  return after.length < before.length;
}

test("a row added and binned in the same breath is not a deletion", () => {
  const stage = draft({ serverLoadedAt: null, lastPushedAt: null, collections: { prototype: [] } });
  const held = rowsTheServerCouldHold(stage);

  const added = blankRow();
  const afterAdd = [added];
  const afterRemove: DwRow[] = [];

  expect(recordsADeletion(afterAdd, afterRemove, added, held)).toBe(false);
  expect(whatTheCountRuleSaid(afterAdd, afterRemove)).toBe(true);
});

test("the autosave banking the blank row first does not turn it into a deletion", () => {
  const added = blankRow();
  const stage = draft({ serverLoadedAt: null, lastPushedAt: null, collections: { prototype: [added] } });

  expect(recordsADeletion([added], [], added, rowsTheServerCouldHold(stage))).toBe(false);
});

test("the phantom made the fold withhold the office's rows and the next save sweep them", () => {
  const withPhantom = draft({ dirtyAt: 5000, removedFrom: ["prototype"], serverLoadedAt: null, collections: { prototype: [] } });

  const fold = foldStageInto(SPEC, withPhantom, serverStage(officeRows()));

  expect(fold.sweptRows.prototype).toBe(6);
  expect(fold.stage.collections.prototype ?? []).toEqual([]);
  expect(fold.stage.serverLoadedAt).not.toBeNull();

  const sweep = stageSweep(SPEC, fold.stage);
  expect(sweep.replaceCollections).toBe(true);
  expect(sweep.emptiedEntities).toEqual(["prototype"]);
});

test("with the deletion never recorded, the fold hands the six rows back and no save sweeps anything", () => {
  const clean = draft({ dirtyAt: 5000, removedFrom: [], serverLoadedAt: null, collections: { prototype: [] } });

  const fold = foldStageInto(SPEC, clean, serverStage(officeRows()));

  expect(fold.sweptRows).toEqual({});
  expect(fold.addedRows.prototype).toBe(6);
  expect((fold.stage.collections.prototype ?? []).length).toBe(6);
  const sweep = stageSweep(SPEC, fold.stage);
  expect(sweep.replaceCollections).toBe(false);
  expect(sweep.emptiedEntities).toEqual([]);
  expect(sweep.withheld).toEqual([]);
});

test("deleting a row the server sent IS a deletion", () => {
  const rows = officeRows();
  const stage = draft({ serverLoadedAt: 9000, lastPushedAt: null, collections: { prototype: rows } });
  const held = rowsTheServerCouldHold(stage);

  expect(recordsADeletion(rows, rows.slice(1), rows[0], held)).toBe(true);
  expect(removalIsADeletion(rows[0], new Set<string>())).toBe(true);
});

test("deleting a row this browser created and pushed IS a deletion", () => {
  const mine = blankRow();
  const pushed = draft({ serverLoadedAt: null, lastPushedAt: 7000, collections: { prototype: [mine] } });

  expect(recordsADeletion([mine], [], mine, rowsTheServerCouldHold(pushed))).toBe(true);
});

test("a removal that reports no row at all is treated as a deletion", () => {
  const rows = officeRows();
  const stage = draft({ serverLoadedAt: null, lastPushedAt: null, collections: {} });

  expect(recordsADeletion(rows, rows.slice(1), undefined, rowsTheServerCouldHold(stage))).toBe(true);
  expect(removalIsADeletion({ name: "no keys at all" }, new Set<string>())).toBe(true);
});

test("a stage that has never been read or pushed holds nothing the server could have", () => {
  const never = draft({ serverLoadedAt: null, lastPushedAt: null, collections: { prototype: officeRows() } });
  expect(rowsTheServerCouldHold(never)).toEqual({});
  expect(rowsTheServerCouldHold(undefined)).toEqual({});
});

test("a read or a push puts every row of the stage in, by client key", () => {
  const rows = [
    { _entryId: "e1", _clientKey: "k1", name: "Pit loom" },
    { _clientKey: "k2", name: "Mine, created here" }
  ];
  const read = draft({ serverLoadedAt: 9000, lastPushedAt: null, collections: { prototype: rows } });
  expect(rowsTheServerCouldHold(read).prototype).toEqual(new Set(["k1", "k2"]));

  const pushed = draft({ serverLoadedAt: null, lastPushedAt: 7000, collections: { prototype: rows } });
  expect(rowsTheServerCouldHold(pushed).prototype).toEqual(new Set(["k1", "k2"]));
});

test("the set GROWS across a push and is never narrowed by one", () => {
  const first = rowsTheServerCouldHold(
    draft({ serverLoadedAt: null, lastPushedAt: 7000, collections: { prototype: [{ _clientKey: "k1" }] } })
  );
  const second = rowsTheServerCouldHold(
    draft({ serverLoadedAt: null, lastPushedAt: 8000, collections: { prototype: [{ _clientKey: "k2" }] } }),
    first
  );

  expect(second.prototype).toEqual(new Set(["k1", "k2"]));
  expect(first.prototype).toEqual(new Set(["k1"]));
});
