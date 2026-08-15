import { expect, test } from "@playwright/test";

import {
  emptyStage,
  foldChangedNothing,
  foldNotice,
  foldStageInto,
  type DwDraftStage
} from "@/lib/designWorkshopStore";
import type { DwStage, DwStageData } from "@/lib/designWorkshops";

/**
 * ON THE WEB, A WITHHELD DELETION HAD NO WAY BACK. THIS IS THE FUNCTION THAT GIVES IT ONE.
 *
 * THE DEFECT THIS PINS. `serverLoadedAt` is the browser's authority to claim "these are now exactly
 * the rows", and it was stamped by exactly one thing: `adoptServerStage` on a stage whose `dirtyAt`
 * was null. A stage holding an unsent deletion is ALWAYS dirty — `removedFrom` and `dirtyAt` are kept
 * and cleared together by `unsentAfterPush` — so the adopt was refused every time, the stage stayed
 * dirty, and the deletion was owed for ever. The row the designer deleted stayed alive in the
 * repository and printed in the .docx handed to the officer, while the banner correctly and uselessly
 * reported work that had not landed.
 *
 * The fix is not to adopt (that would overwrite a courtyard's work with an older server copy). It is
 * to FOLD: add only what this browser has never seen, keep every local value, honour the recorded
 * deletion by declining to add those rows back, and stamp the authority. Android has done exactly
 * this since `dwFoldServerStage`, and these cases are deliberately the web's copy of the twelve in
 * `StageAuthorityEarnedByReadingTest` — because two surfaces that fold differently produce two copies
 * of one workshop that disagree about the fieldwork, with nothing in either saying so.
 *
 * WHY THE ASSERTIONS ARE ON THIS FUNCTION AND NOT ON A BROWSER. The whole thing is a decision taken
 * from two objects with no I/O in it, and its mistake would be the silent overwrite of a designer's
 * own text. Driving it through IndexedDB and a real GET would test the plumbing and leave the rule
 * untested.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures
 * ──────────────────────────────────────────────────────────────────────────── */

function field(key: string) {
  // Only `key` is read by `splitSingletons`; the rest of `DwField` is shape the registry carries and
  // this decision never consults. Written out rather than cast so a field gaining a REQUIRED member
  // fails here loudly instead of being silently absent from every case below.
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

/* ────────────────────────────────────────────────────────────────────────────
 * The authority, which is the whole point
 * ──────────────────────────────────────────────────────────────────────────── */

test("folding earns the authority a dirty stage could never earn before", () => {
  // A stage holding an unsent deletion: dirty, and naming the collection it was deleted from. Under
  // the old branch this returned untouched with `serverLoadedAt` still null, for ever.
  const current = draft({ dirtyAt: 5000, removedFrom: ["material"], serverLoadedAt: null });

  const fold = foldStageInto(SPEC, current, serverStage());

  expect(fold.stage.serverLoadedAt).not.toBeNull();
  // The two things that must survive it: the deletion is still owed, and the stage is still dirty, so
  // the very next save both carries the deletion AND is entitled to state it.
  expect(fold.stage.removedFrom).toEqual(["material"]);
  expect(fold.stage.dirtyAt).toBe(5000);
});

test("the fold does not date this browser's work by a download", () => {
  // `dirtyAt` is when the DESIGNER last edited. Rows arriving from the server are not an edit, and
  // bumping it here would make an untouched stage look freshly worked on to every comparison that
  // reads it — including `unsentAfterPush`, which decides whether an acknowledgement still describes
  // the stage it was built from.
  const current = draft({ dirtyAt: 5000 });
  const fold = foldStageInto(SPEC, current, serverStage({ collections: { tool: [{ _entryId: "e1", name: "Pit loom" }] } }));
  expect(fold.stage.dirtyAt).toBe(5000);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Nothing the designer typed is overwritten
 * ──────────────────────────────────────────────────────────────────────────── */

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

  // Kept, verbatim. This is the local-wins rule and it is the one this function must never break.
  expect(fold.stage.singletons.process.summary).toBe("What she told me in the courtyard");
  // Added, because this browser has never held an opinion about it.
  expect(fold.stage.singletons.process.dyeing).toBe("Indigo, twice");
  expect(fold.added).toEqual(["dyeing"]);
});

test("an empty string IS an opinion and is not overwritten", () => {
  // The stage form REMOVES a key whose value went blank rather than storing "", so a key present and
  // empty did not come from a clearance — it came from a server that holds "", a rich-text control
  // that wrote an empty document, or an older build. Treating it as absent would overwrite an edit
  // rather than reveal one. This is the case an `isFilled` test would get wrong.
  const current = draft({ dirtyAt: 5000, singletons: { process: { dyeing: "" } } });
  const fold = foldStageInto(SPEC, current, serverStage({ singleton: { dyeing: "Indigo, twice" } }));
  expect(fold.stage.singletons.process.dyeing).toBe("");
  expect(fold.added).toEqual([]);
});

test("a key the registry does not declare never enters the draft", () => {
  // `splitSingletons` copies across only what the registry declares. A field the server holds and this
  // build has never heard of would otherwise be posted back inside a core entry, dropped server-side,
  // and returned in `droppedKeys` — firing the registry-drift banner on every save.
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

/* ────────────────────────────────────────────────────────────────────────────
 * Rows: matched on identity, appended, never doubled
 * ──────────────────────────────────────────────────────────────────────────── */

test("a row this browser already holds is not added a second time", () => {
  // Matched on `_clientKey` first and `_entryId` second — the same identity `save_stage` matches on.
  // Without this, one fold would double every row of every costing table this browser had ever sent.
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
    // The local rows keep their place and their order — the server's ordinals describe the server's
    // list, and splicing them in would reshuffle rows under a cursor.
    "Pit loom",
    "Bobbin winder",
    "Warping drum"
  ]);
  expect(fold.addedRows).toEqual({ tool: 1 });
});

test("an appended row with no key of its own is given one", () => {
  // The same reason `withClientKeys` mints it on the adopt path: a row with no key cannot be matched
  // by a replayed save and would be inserted a second time.
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

/* ────────────────────────────────────────────────────────────────────────────
 * The one asymmetry: a deletion the designer recorded is honoured
 * ──────────────────────────────────────────────────────────────────────────── */

test("rows in an emptied collection are NOT folded back in, and the collateral is counted", () => {
  // THE CASE THE WHOLE FUNCTION TURNS ON. Folding these rows back would reverse the deletion: the next
  // payload would name every row again and `replaceCollections` would sweep nothing. The designer's
  // explicit action wins over an inference.
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
  // Counted as what the next save will ACTUALLY remove, so the designer can act before it does.
  expect(fold.sweptRows).toEqual({ material: 2 });
  expect(fold.addedRows.material).toBeUndefined();
});

test("a row the payload still names is not counted as about to be swept", () => {
  // Reachable in the ordinary way: empty a collection, then start it again with a fresh row before the
  // stage is next read. The sweep removes what the payload does not NAME, and the payload names every
  // row the draft still holds — so counting this one would be a false alarm in a sentence whose whole
  // job is to be believed.
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

/* ────────────────────────────────────────────────────────────────────────────
 * What it says
 * ──────────────────────────────────────────────────────────────────────────── */

test("a fold that changed nothing visible says nothing", () => {
  const fold = foldStageInto(SPEC, draft({ dirtyAt: 5000 }), serverStage());
  expect(foldChangedNothing(fold)).toBe(true);
  expect(foldNotice(fold)).toBeNull();
  // But it still earned the authority, which is the point: a stage whose server copy holds nothing new
  // is exactly the stage whose withheld deletion should now be allowed to travel.
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
  expect(notice).toContain("read from the server");
  expect(notice).toContain("dyeing");
  expect(notice).toContain("1 row in tool");
  expect(notice).toContain("Nothing you had typed here was changed");
  // The second half points the other way and is the one a designer may need to act on.
  expect(notice).toContain("NOT been added back");
  expect(notice).toContain("material");
  // The remedy named is one they can actually carry out: they cannot retype rows they have never been
  // shown, so the sentence names the fact that makes it recoverable by somebody who can.
  expect(notice).toContain("records a deletion rather than erasing the row");
});

test("a fold that only sweeps still speaks", () => {
  // The "added" clause is built only when there is something in it, so this case used to be the one a
  // naive implementation would leave silent — which is the half that matters most.
  const fold = foldStageInto(
    SPEC,
    draft({ dirtyAt: 5000, removedFrom: ["material"] }),
    serverStage({ collections: { material: [{ _entryId: "srv-1", name: "Cotton" }] } })
  );
  const notice = foldNotice(fold) ?? "";
  expect(notice).toContain("NOT been added back");
  expect(notice).not.toContain("were already there");
});
