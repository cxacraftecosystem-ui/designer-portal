import { expect, test } from "@playwright/test";

import { emptyStage, unsentAfterPush, type DwDraftStage } from "@/lib/designWorkshopStore";

/**
 * A DELETION MADE WHILE THE PUSH WAS IN FLIGHT MUST STILL REACH THE SERVER.
 *
 * THE DEFECT THIS PINS. `removedFrom` is the only thing that arms the sweep: the sync pass sends
 * `replaceCollections: stage.removedFrom.length > 0` and `emptiedEntities: stage.removedFrom`, and
 * there is no per-row delete endpoint, so a deletion that is not named in a payload does not happen
 * on the server at all. Both places that record a successful push — `markStagePushed` and the sync
 * pass's own transform — compared `dirtyAt` against the payload they had built and then emptied
 * `removedFrom` with no condition whatever. A designer deleting a mis-entered participant row in
 * the moment `DraftSyncBanner`'s automatic pass had that same stage in flight therefore had the
 * deletion flag wiped by an acknowledgement that never carried it: the next pass sent
 * `replaceCollections: false` and `emptiedEntities: []`, the row stayed alive in the repository,
 * and the next clean read put it back on her screen and into the .docx handed to the officer. She
 * deleted it, watched it disappear, and it came back with no message anywhere.
 *
 * WHY THE ASSERTIONS ARE ON THIS FUNCTION AND NOT ON A BROWSER. The whole defect is one decision —
 * "does this acknowledgement describe the stage as it stands now" — taken from two fields and no
 * I/O. Driving it through IndexedDB and a real PUT would test the race rather than the rule, and a
 * race that reproduces one run in fifty is a spec that passes against the bug. `unsentAfterPush`
 * exists so that decision has ONE definition; these are its cases, and both call sites are spread
 * straight from its result.
 */

/** A stage as it stands on disk when the acknowledgement arrives. */
function stageAt(dirtyAt: number | null, removedFrom: string[]): DwDraftStage {
  return { ...emptyStage("PARTICIPANT_PROFILE"), dirtyAt, removedFrom };
}

test("an ordinary push clears both flags", () => {
  // Nothing happened during the round trip: `dirtyAt` and `removedFrom` are exactly what was sent,
  // the server took them, and the stage is settled. This is the common case and the one that keeps
  // the amber "Saved on this device only" chip off a stage that has landed.
  expect(unsentAfterPush(stageAt(1000, ["participant"]), { dirtyAt: 1000, removedFrom: ["participant"] })).toEqual({
    dirtyAt: null,
    removedFrom: []
  });

  // A stage pushed from a clean read — no local edit ever — settles the same way.
  expect(unsentAfterPush(stageAt(null, []), { dirtyAt: null, removedFrom: [] })).toEqual({
    dirtyAt: null,
    removedFrom: []
  });
});

test("a row deleted while the push was in flight keeps its deletion flag", () => {
  // THE DEFECT. The pass built a payload from a stage with nothing deleted, and while it was in
  // flight the designer deleted a participant row — so the PUT carried `emptiedEntities: []` and
  // the server was never told. The acknowledgement used to empty this list anyway.
  const after = unsentAfterPush(stageAt(2000, ["participant"]), { dirtyAt: 1000, removedFrom: [] });
  expect(after.removedFrom).toEqual(["participant"]);
  // Non-empty is precisely what arms `replaceCollections` on the next pass.
  expect(after.removedFrom.length > 0).toBe(true);
  // And the stage stays dirty, so there IS a next pass.
  expect(after.dirtyAt).toBe(2000);
});

test("a SECOND deletion out of a collection the push already named also survives", () => {
  // WHY THE RULE IS NOT "SUBTRACT THE ACKNOWLEDGED KEYS". `removedFrom` holds entity keys, not row
  // keys, so this list is byte-for-byte what was sent even though it now stands for one more
  // deleted row — the one deleted after the payload was built, which the server therefore kept.
  // Subtracting `participant` would drop the flag and resurrect that row: the same defect, one row
  // further along, and invisible to a test that only ever deletes from a fresh collection.
  const after = unsentAfterPush(stageAt(2000, ["participant"]), { dirtyAt: 1000, removedFrom: ["participant"] });
  expect(after.removedFrom).toEqual(["participant"]);
});

test("typing during the flight keeps the stage unsent, as it always did", () => {
  // The guard that was already there, kept as a witness: newer keystrokes must not be marked as
  // sent, or they are never sent at all.
  expect(unsentAfterPush(stageAt(2000, []), { dirtyAt: 1000, removedFrom: [] })).toEqual({
    dirtyAt: 2000,
    removedFrom: []
  });

  // A stage first edited during the flight — `sinceDirtyAt` was null because the payload was built
  // from a stage this device had only ever read.
  expect(unsentAfterPush(stageAt(2000, ["tool"]), { dirtyAt: null, removedFrom: [] })).toEqual({
    dirtyAt: 2000,
    removedFrom: ["tool"]
  });
});

test("an acknowledgement newer than the stage still settles it", () => {
  // A payload built from a LATER edit than the one on disk cannot happen through either call site,
  // but the comparison must not be an equality: a stage whose `dirtyAt` is not strictly newer than
  // the payload's was fully described by it and is settled.
  expect(unsentAfterPush(stageAt(1000, ["participant"]), { dirtyAt: 3000, removedFrom: [] })).toEqual({
    dirtyAt: null,
    removedFrom: []
  });
});
