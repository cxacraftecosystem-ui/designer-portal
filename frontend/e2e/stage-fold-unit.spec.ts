import { expect, test } from "@playwright/test";

import {
  emptyStage,
  foldChangedNothing,
  foldNotice,
  foldStageInto,
  type DwDraftStage
} from "@/lib/designWorkshopStore";
import type { DwStage, DwStageData } from "@/lib/designWorkshops";

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
  number: 6,
  key: "CRAFT_PROCESS",
  title: "Craft process",
  purpose: "",
  notes: "",
  optionalStage: false,
  entities: [
    {
      key: "process",
      name: "process",
      cardinality: "SINGLETON",
      title: "Process",
      description: "",
      parent: "",
      labelField: "",
      fields: [field("summary"), field("dyeing"), field("weaving")]
    },
    {
      key: "tool",
      name: "tool",
      cardinality: "COLLECTION",
      title: "Tools",
      description: "",
      parent: "",
      labelField: "name",
      fields: [field("name")]
    },
    {
      key: "material",
      name: "material",
      cardinality: "COLLECTION",
      title: "Materials",
      description: "",
      parent: "",
      labelField: "name",
      fields: [field("name")]
    }
  ]
};

function serverStage(over: Partial<DwStageData> = {}): DwStageData {
  return { singleton: {}, collections: {}, ...over };
}

function draft(over: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage("CRAFT_PROCESS"), ...over };
}

test("folding earns the authority a dirty stage could never earn before", () => {
  const current = draft({ dirtyAt: 5000, removedFrom: ["material"], serverLoadedAt: null });

  const fold = foldStageInto(SPEC, current, serverStage());

  expect(fold.stage.serverLoadedAt).not.toBeNull();
  expect(fold.stage.removedFrom).toEqual(["material"]);
  expect(fold.stage.dirtyAt).toBe(5000);
});

test("the fold does not date this browser's work by a download", () => {
  const current = draft({ dirtyAt: 5000 });
  const fold = foldStageInto(SPEC, current, serverStage({ collections: { tool: [{ _entryId: "e1", name: "Pit loom" }] } }));
  expect(fold.stage.dirtyAt).toBe(5000);
});

test("a local answer wins over the server's, and the server's unseen answers are added", () => {
  const current = draft({
    dirtyAt: 5000,
    singletons: { process: { summary: "What she told me in the courtyard" } }
  });

  const fold = foldStageInto(
    SPEC,
    current,
    serverStage({ singleton: { summary: "The office's older paragraph", dyeing: "Indigo, twice" } })
  );

  expect(fold.stage.singletons.process.summary).toBe("What she told me in the courtyard");
  expect(fold.stage.singletons.process.dyeing).toBe("Indigo, twice");
  expect(fold.added).toEqual(["dyeing"]);
});

test("an empty string IS an opinion and is not overwritten", () => {
  const current = draft({ dirtyAt: 5000, singletons: { process: { dyeing: "" } } });
  const fold = foldStageInto(SPEC, current, serverStage({ singleton: { dyeing: "Indigo, twice" } }));
  expect(fold.stage.singletons.process.dyeing).toBe("");
  expect(fold.added).toEqual([]);
});

test("a key the registry does not declare never enters the draft", () => {
  const fold = foldStageInto(
    SPEC,
    draft({ dirtyAt: 5000 }),
    serverStage({ singleton: { summary: "kept", inventedByANewerServer: "dropped" } })
  );
  expect(fold.stage.singletons.process).toEqual({ summary: "kept" });
  expect(fold.added).toEqual(["summary"]);
});

test("the protocol's own underscore keys fold silently rather than being announced", () => {
  const fold = foldStageInto(SPEC, draft({ dirtyAt: 5000 }), serverStage({ custom: { _entryId: "x", loomCount: 12 } }));
  expect(fold.stage.custom?._entryId).toBe("x");
  expect(fold.addedCustom).toEqual(["loomCount"]);
});

test("a row this browser already holds is not added a second time", () => {
  const current = draft({
    dirtyAt: 5000,
    collections: {
      tool: [
        { _clientKey: "web-tool-1", name: "Pit loom" },
        { _entryId: "srv-2", name: "Bobbin winder" }
      ]
    }
  });

  const fold = foldStageInto(
    SPEC,
    current,
    serverStage({
      collections: {
        tool: [
          { _clientKey: "web-tool-1", _entryId: "srv-1", name: "Pit loom" },
          { _entryId: "srv-2", name: "Bobbin winder" },
          { _entryId: "srv-3", name: "Warping drum" }
        ]
      }
    })
  );

  expect(fold.stage.collections.tool).toHaveLength(3);
  expect(fold.stage.collections.tool.map((row) => row.name)).toEqual([
    "Pit loom",
    "Bobbin winder",
    "Warping drum"
  ]);
  expect(fold.addedRows).toEqual({ tool: 1 });
});

test("an appended row with no key of its own is given one", () => {
  const fold = foldStageInto(
    SPEC,
    draft({ dirtyAt: 5000 }),
    serverStage({ collections: { tool: [{ name: "Warping drum" }] } })
  );
  expect(fold.stage.collections.tool[0]._clientKey).toBeTruthy();
});

test("a collection the registry does not declare is ignored", () => {
  const fold = foldStageInto(
    SPEC,
    draft({ dirtyAt: 5000 }),
    serverStage({ collections: { retiredEntity: [{ _entryId: "e9", name: "gone" }] } })
  );
  expect(fold.stage.collections.retiredEntity).toBeUndefined();
  expect(fold.addedRows).toEqual({});
});

test("rows in an emptied collection are NOT folded back in, and the collateral is counted", () => {
  const current = draft({ dirtyAt: 5000, removedFrom: ["material"], collections: { material: [] } });

  const fold = foldStageInto(
    SPEC,
    current,
    serverStage({
      collections: {
        material: [
          { _entryId: "srv-1", name: "Cotton" },
          { _entryId: "srv-2", name: "Indigo" }
        ]
      }
    })
  );

  expect(fold.stage.collections.material).toEqual([]);
  expect(fold.sweptRows).toEqual({ material: 2 });
  expect(fold.addedRows.material).toBeUndefined();
});

test("a row the payload still names is not counted as about to be swept", () => {
  const current = draft({
    dirtyAt: 5000,
    removedFrom: ["material"],
    collections: { material: [{ _entryId: "srv-1", name: "Cotton" }] }
  });

  const fold = foldStageInto(
    SPEC,
    current,
    serverStage({
      collections: {
        material: [
          { _entryId: "srv-1", name: "Cotton" },
          { _entryId: "srv-2", name: "Indigo" }
        ]
      }
    })
  );

  expect(fold.sweptRows).toEqual({ material: 1 });
});

test("a fold that changed nothing visible says nothing", () => {
  const fold = foldStageInto(SPEC, draft({ dirtyAt: 5000 }), serverStage());
  expect(foldChangedNothing(fold)).toBe(true);
  expect(foldNotice(fold)).toBeNull();
  expect(fold.stage.serverLoadedAt).not.toBeNull();
});

test("the notice names what appeared and, separately, what the next save will delete", () => {
  const current = draft({ dirtyAt: 5000, removedFrom: ["material"] });
  const fold = foldStageInto(
    SPEC,
    current,
    serverStage({
      singleton: { dyeing: "Indigo, twice" },
      collections: {
        tool: [{ _entryId: "srv-3", name: "Warping drum" }],
        material: [{ _entryId: "srv-1", name: "Cotton" }]
      }
    })
  );

  const notice = foldNotice(fold) ?? "";
  expect(notice).toContain("already saved online");
  expect(notice).toContain("dyeing");
  expect(notice).toContain("1 entry in tool");
  expect(notice).toContain("Nothing you had typed here was changed");
  expect(notice).toContain("not been added back");
  expect(notice).toContain("material");
  expect(notice).toContain("deleted entries are kept");
});

test("a fold that only sweeps still speaks", () => {
  const fold = foldStageInto(
    SPEC,
    draft({ dirtyAt: 5000, removedFrom: ["material"] }),
    serverStage({ collections: { material: [{ _entryId: "srv-1", name: "Cotton" }] } })
  );
  const notice = foldNotice(fold) ?? "";
  expect(notice).toContain("not been added back");
  expect(notice).not.toContain("were already there");
});
