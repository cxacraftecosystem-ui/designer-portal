import { expect, test } from "@playwright/test";

import {
  removalIsADeletion,
  rowsTheServerCouldHold,
  type ServerHeldRows
} from "@/components/designworkshop/EntityForm";
import { emptyStage, foldStageInto, stageSweep, type DwDraftStage } from "@/lib/designWorkshopStore";
import { blankRow, type DwRow, type DwStage, type DwStageData } from "@/lib/designWorkshops";

/**
 * ADDING A ROW AND BINNING IT MUST NOT DELETE ROWS NOBODY HAS EVER SEEN.
 *
 * THE DEFECT THIS PINS. `patchCollection` on the stage page decided that a row had been DELETED by
 * comparing array lengths — `rows.length < (collections[entityKey] ?? []).length` — and pushed the
 * entity into `removedFrom`. That is a true statement about the array and a false one about the
 * repository. Press "Add prototype", see the empty panel open, realise it belongs on the next stage,
 * press the bin: net change nothing, and the count test recorded a deletion of a row that had never
 * been anywhere. `putDraftStage` UNIONS `removedFrom` on the way in — deliberately, so a form cannot
 * disarm a deletion the draft still holds — so the phantom could not be withdrawn by anything the
 * designer did next.
 *
 * WHAT MADE IT DESTRUCTIVE RATHER THAN NOISY. A browser that has never read a stage is refused the
 * authority to sweep, so the phantom used to sit there doing nothing. `foldStageInto` ended that: it
 * EARNS that authority for a dirty stage on the next online open, and on the way it reads
 * `removedFrom` as an instruction — a collection named there is deliberately not folded back in and
 * its server rows are counted in `sweptRows`. One phantom entry therefore makes the fold withhold the
 * office's six prototype rows AND stamp `serverLoadedAt`; the next save sends `replaceCollections`
 * with `emptiedEntities: ['prototype']`, carries no prototype rows, and `sweep_entities` soft-deletes
 * all six under an HTTP 200 reading "Stage saved — 0 added, 1 updated, 6 removed". No second user, no
 * concurrency, and no save of the phantom itself is needed.
 *
 * WHY THE ASSERTIONS ARE ON THESE FUNCTIONS AND NOT ON A BROWSER. The defect is one decision — "is
 * this removal a deletion" — taken from a row and a set of row keys, with no I/O in it, plus its
 * consequence two functions downstream. `whatTheCountRuleSaid` below is the rule as it shipped, kept
 * in the file so the second half of every case can assert what the defect DID: a spec that only
 * exercised the fixed rule would pass just as happily against a version of it that never fires.
 * Driving it through IndexedDB, a real GET and a real PUT would test the plumbing and leave the rule
 * — the part that was wrong — untested.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures
 * ──────────────────────────────────────────────────────────────────────────── */

function field(key: string) {
  // Only `key` and the entity's cardinality are read by anything below; the rest is shape the
  // registry carries. Written out rather than cast so a field gaining a REQUIRED member fails here
  // loudly instead of being silently absent. Same fixture as `stage-fold-unit.spec.ts`.
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

/** Stage 13, reduced to the one collection the report is about. */
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

/** The six rows written up in the office, as the server hands them back. */
function officeRows(): DwRow[] {
  return Array.from({ length: 6 }, (_, index) => ({ _entryId: `e${index + 1}`, name: `Prototype ${index + 1}` }));
}

function serverStage(rows: DwRow[]): DwStageData {
  return { singleton: {}, collections: { prototype: rows } };
}

/**
 * `patchCollection`'s rule as it stands now: a shorter array AND a row that could exist upstream.
 *
 * A local mirror rather than an import, because the real one is a closure inside a route component
 * that cannot be called without React. The two must stay identical — the only thing this adds to
 * `removalIsADeletion` is the length comparison, which is what says "this call is a removal at all".
 */
function recordsADeletion(before: DwRow[], after: DwRow[], removed: DwRow | undefined, held: ServerHeldRows): boolean {
  return after.length < before.length && removalIsADeletion(removed, held.prototype);
}

/** The rule as it SHIPPED, and the whole defect: shorter array, therefore delete rows on the server. */
function whatTheCountRuleSaid(before: DwRow[], after: DwRow[]): boolean {
  return after.length < before.length;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The removal that is not a deletion
 * ──────────────────────────────────────────────────────────────────────────── */

test("a row added and binned in the same breath is not a deletion", () => {
  // Stage 13 opened in the field with no signal: `adoptServerStage` was never reached, so this
  // browser has never read the stage and holds no rows for it.
  const stage = draft({ serverLoadedAt: null, lastPushedAt: null, collections: { prototype: [] } });
  const held = rowsTheServerCouldHold(stage);

  // "Add prototype": one row minted by `blankRow()`, carrying a fresh `_clientKey` and nothing else.
  const added = blankRow();
  const afterAdd = [added];
  // ...and the bin, on the row just created. Net change: nothing, anywhere.
  const afterRemove: DwRow[] = [];

  expect(recordsADeletion(afterAdd, afterRemove, added, held)).toBe(false);
  // THE DEFECT, stated. The rule that shipped called this a deletion of repository rows, and every
  // consequence below follows from that one `true`.
  expect(whatTheCountRuleSaid(afterAdd, afterRemove)).toBe(true);
});

test("the autosave banking the blank row first does not turn it into a deletion", () => {
  // WHY THE TEST IS PROVENANCE AND NOT PRESENCE. The autosave writes the stage to IndexedDB 800 ms
  // after the last keystroke, so a designer who takes longer than that to change their mind — which
  // is nearly all of them; the panel has to be read before it can be rejected — has the blank row
  // banked on the draft before they press the bin. A rule that asked "is this row in the banked
  // copy" would call THIS a deletion and the phantom would survive the fix in its own headline
  // scenario. What decides it is whether the stage has ever crossed the wire, and this one has not.
  const added = blankRow();
  const stage = draft({ serverLoadedAt: null, lastPushedAt: null, collections: { prototype: [added] } });

  expect(recordsADeletion([added], [], added, rowsTheServerCouldHold(stage))).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * What the phantom went on to do — the whole chain, in order
 * ──────────────────────────────────────────────────────────────────────────── */

test("the phantom made the fold withhold the office's rows and the next save sweep them", () => {
  // Days later, back in signal. The stage is dirty (the autosave banked the add and the remove), so
  // `adoptServerStage` takes the dirty branch and folds. THIS IS THE `removedFrom` THE COUNT RULE
  // WROTE — nothing else about this draft differs from the case below.
  const withPhantom = draft({ dirtyAt: 5000, removedFrom: ["prototype"], serverLoadedAt: null, collections: { prototype: [] } });

  const fold = foldStageInto(SPEC, withPhantom, serverStage(officeRows()));

  // The fold refuses to add the six rows back, because `removedFrom` says the designer emptied this
  // collection — and it stamps the authority anyway.
  expect(fold.sweptRows.prototype).toBe(6);
  expect(fold.stage.collections.prototype ?? []).toEqual([]);
  expect(fold.stage.serverLoadedAt).not.toBeNull();

  // And the save after it is now entitled to say "these are now exactly the rows", over a payload
  // that names none of them. `sweep_entities` unions `emptiedEntities` into what it deletes, so this
  // is six committed repository rows gone under a 200.
  const sweep = stageSweep(SPEC, fold.stage);
  expect(sweep.replaceCollections).toBe(true);
  expect(sweep.emptiedEntities).toEqual(["prototype"]);
});

test("with the deletion never recorded, the fold hands the six rows back and no save sweeps anything", () => {
  // The same designer, the same Add-then-Delete, the same days offline — with `removedFrom` left
  // empty because nothing was ever deleted. This is the assertion that fails against the old rule.
  const clean = draft({ dirtyAt: 5000, removedFrom: [], serverLoadedAt: null, collections: { prototype: [] } });

  const fold = foldStageInto(SPEC, clean, serverStage(officeRows()));

  expect(fold.sweptRows).toEqual({});
  expect(fold.addedRows.prototype).toBe(6);
  expect((fold.stage.collections.prototype ?? []).length).toBe(6);
  // The rows are on screen again, and the save that follows states nothing about deleting anything.
  const sweep = stageSweep(SPEC, fold.stage);
  expect(sweep.replaceCollections).toBe(false);
  expect(sweep.emptiedEntities).toEqual([]);
  // Nor is the designer told a deletion is being held back for them — `withheld` is what the page
  // turns into the "you deleted rows and this browser cannot send it yet" sentence, and a phantom
  // there is a paragraph about a deletion that never happened.
  expect(sweep.withheld).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The deletions that must still be recorded — the half a narrower fix breaks
 * ──────────────────────────────────────────────────────────────────────────── */

test("deleting a row the server sent IS a deletion", () => {
  // A stage read online this morning: six rows, each carrying the id the server minted. Deleting one
  // must arm the sweep, because there is no per-row delete endpoint and `removedFrom` is the only
  // way the deletion can reach the repository at all.
  const rows = officeRows();
  const stage = draft({ serverLoadedAt: 9000, lastPushedAt: null, collections: { prototype: rows } });
  const held = rowsTheServerCouldHold(stage);

  expect(recordsADeletion(rows, rows.slice(1), rows[0], held)).toBe(true);
  // Belt and braces: a server row is admitted by its `_entryId` alone, so it is still a deletion
  // even for a page whose held set has somehow lost it.
  expect(removalIsADeletion(rows[0], new Set<string>())).toBe(true);
});

test("deleting a row this browser created and pushed IS a deletion", () => {
  // THE CASE `_entryId` ALONE WOULD GET WRONG, and the reason the held set exists at all. A row
  // created here and saved keeps its `_clientKey` — `save_stage` matches on it — and the response
  // never writes a `_entryId` back into the copy on screen. So the only evidence that the server
  // holds this row is that the stage it lives in has been pushed.
  const mine = blankRow();
  const pushed = draft({ serverLoadedAt: null, lastPushedAt: 7000, collections: { prototype: [mine] } });

  expect(recordsADeletion([mine], [], mine, rowsTheServerCouldHold(pushed))).toBe(true);
});

test("a removal that reports no row at all is treated as a deletion", () => {
  // The safe direction, chosen deliberately. A future caller that shrinks the list without saying
  // which row went costs one redundant `replaceCollections` over rows the payload carries anyway;
  // the opposite default would lose a deletion the designer watched happen, silently and for ever.
  const rows = officeRows();
  const stage = draft({ serverLoadedAt: null, lastPushedAt: null, collections: {} });

  expect(recordsADeletion(rows, rows.slice(1), undefined, rowsTheServerCouldHold(stage))).toBe(true);
  // Same rule for a row with no identity of any kind: it cannot be shown never to have existed
  // upstream, so it is not assumed innocent.
  expect(removalIsADeletion({ name: "no keys at all" }, new Set<string>())).toBe(true);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The set itself
 * ──────────────────────────────────────────────────────────────────────────── */

test("a stage that has never been read or pushed holds nothing the server could have", () => {
  const never = draft({ serverLoadedAt: null, lastPushedAt: null, collections: { prototype: officeRows() } });
  // Rows are present — they could only have got there locally — and the answer is still empty. This
  // is the one asymmetry in the rule and it is the one that closes the defect.
  expect(rowsTheServerCouldHold(never)).toEqual({});
  // Nothing at all to describe, and no throw: the load path calls this with `undefined` for a stage
  // no draft record exists for yet.
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
  // WHY `carried` EXISTS. The save banks the rows it sent, and a set rebuilt from scratch after it
  // would forget everything the session had already established — including, on the partial-refusal
  // path, rows the server accepted from an earlier PUT. Growth only: nothing here may ever REMOVE a
  // key, because that would turn a real deletion back into a phantom.
  const first = rowsTheServerCouldHold(
    draft({ serverLoadedAt: null, lastPushedAt: 7000, collections: { prototype: [{ _clientKey: "k1" }] } })
  );
  const second = rowsTheServerCouldHold(
    draft({ serverLoadedAt: null, lastPushedAt: 8000, collections: { prototype: [{ _clientKey: "k2" }] } }),
    first
  );

  expect(second.prototype).toEqual(new Set(["k1", "k2"]));
  // The carried set is COPIED, not aliased: `serverHeld.current` is a ref the page reassigns, and a
  // function that mutated its argument would edit the answer a previous render was still holding.
  expect(first.prototype).toEqual(new Set(["k1"]));
});
