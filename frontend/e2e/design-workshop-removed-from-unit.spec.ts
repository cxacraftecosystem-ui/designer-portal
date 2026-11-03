import { expect, test } from "@playwright/test";

import { emptyStage, unsentAfterPush, type DwDraftStage } from "@/lib/designWorkshopStore";

function stageAt(dirtyAt: number | null, removedFrom: string[]): DwDraftStage {
  return { ...emptyStage("PARTICIPANT_PROFILE"), dirtyAt, removedFrom };
}

test("an ordinary push clears both flags", () => {
  expect(unsentAfterPush(stageAt(1000, ["participant"]), { dirtyAt: 1000, removedFrom: ["participant"] })).toEqual({
    dirtyAt: null,
    removedFrom: []
  });

  expect(unsentAfterPush(stageAt(null, []), { dirtyAt: null, removedFrom: [] })).toEqual({
    dirtyAt: null,
    removedFrom: []
  });
});

test("a row deleted while the push was in flight keeps its deletion flag", () => {
  const after = unsentAfterPush(stageAt(2000, ["participant"]), { dirtyAt: 1000, removedFrom: [] });
  expect(after.removedFrom).toEqual(["participant"]);
  expect(after.removedFrom.length > 0).toBe(true);
  expect(after.dirtyAt).toBe(2000);
});

test("a SECOND deletion out of a collection the push already named also survives", () => {
  const after = unsentAfterPush(stageAt(2000, ["participant"]), { dirtyAt: 1000, removedFrom: ["participant"] });
  expect(after.removedFrom).toEqual(["participant"]);
});

test("a deletion written in the payload's own millisecond is not lost to a clock that did not move", () => {
  expect(unsentAfterPush(stageAt(1000, ["participant"]), { dirtyAt: 1000, removedFrom: [] })).toEqual({
    dirtyAt: 1000,
    removedFrom: ["participant"]
  });

  expect(unsentAfterPush(stageAt(1000, ["participant"]), { dirtyAt: 1000, removedFrom: ["participant"] })).toEqual({
    dirtyAt: null,
    removedFrom: []
  });
});

test("typing during the flight keeps the stage unsent, as it always did", () => {
  expect(unsentAfterPush(stageAt(2000, []), { dirtyAt: 1000, removedFrom: [] })).toEqual({
    dirtyAt: 2000,
    removedFrom: []
  });

  expect(unsentAfterPush(stageAt(2000, ["tool"]), { dirtyAt: null, removedFrom: [] })).toEqual({
    dirtyAt: 2000,
    removedFrom: ["tool"]
  });
});

test("an acknowledgement newer than the stage still settles it", () => {
  expect(unsentAfterPush(stageAt(1000, ["participant"]), { dirtyAt: 3000, removedFrom: [] })).toEqual({
    dirtyAt: null,
    removedFrom: []
  });
});
