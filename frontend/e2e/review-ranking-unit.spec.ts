import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  arrangeRows,
  fixedOrderStamp,
  heldOrder,
  mayArrange,
  moveBy,
  moveTo,
  openingOrder,
  placedOrder,
  reconcileOrder,
  rowSubtitle,
  sameOrder,
  scoreOrder,
  stageKeyForEntity,
  todayStamp,
  type RankedItem
} from "@/components/sketches/reviewRanking";
import type { DwRegistry, DwRow } from "@/lib/designWorkshops";

/**
 * THE DEFAULT-VERSUS-OVERRIDE RULE, PINNED — the subtlest thing in Sketches & Prototypes.
 *
 * WHY THIS SPEC EXISTS. The owner asked for a list "sorted by the quantitative data by default"
 * where "the final order is the designer's decision — they have the final say". Those two clauses
 * are in tension by construction, and the failure they describe is silent: a designer arranges ten
 * prototypes deliberately, somebody rates one of them the next morning, and the list they settled
 * re-sorts itself under them with nothing on screen to say it happened. Nothing about that failure
 * is visible in a screenshot, in a type, or in a render — it is a question about which of two
 * arrays a function returns, which is exactly what a unit spec can hold still.
 *
 * WHAT IT COVERS. The two orders and the choice between them; the reconciliation that keeps a
 * hand-made arrangement across a refresh; the arrow/drag primitive both controls share; the reading
 * of the `rankFixedBy`/`rankFixedAt` stamp, including the half-written cases; and the write-back
 * that turns an order into stage rows without touching anything else on them.
 *
 * WHAT IT DOES NOT COVER. The drag gesture itself (a pointer sequence, not a pure function) and the
 * permission rule (server-side, and pinned by `backend/tests/test_design_ratings*.py`).
 *
 * PURE NODE — no browser, no server, no IndexedDB.
 * Run: `npx playwright test e2e/review-ranking-unit.spec.ts --reporter=line`
 */

/** One ranked row, with only the fields these functions read. */
function item(
  subjectId: string,
  defaultPosition: number,
  placedPosition: number,
  extra: Partial<RankedItem> = {}
): RankedItem {
  return {
    subjectId,
    entityKey: "prototype",
    label: subjectId.toUpperCase(),
    workshopId: "w1",
    score: null,
    ratingCount: 0,
    defaultPosition,
    placedPosition,
    myRating: null,
    ...extra
  };
}

/*
  THE FIXTURE IS A LIST WHOSE TWO ORDERS DISAGREE, deliberately. A list where the scores and the
  arrangement happen to agree passes every one of these assertions under an implementation that
  returns the wrong one of them, which is the whole failure this file is about.
*/
const ITEMS: RankedItem[] = [
  item("a", 3, 1),
  item("b", 1, 2),
  item("c", 2, 3)
];

test("the two orders are read off the two fields the server sends, and they differ", () => {
  expect(scoreOrder(ITEMS)).toEqual(["b", "c", "a"]);
  expect(placedOrder(ITEMS)).toEqual(["a", "b", "c"]);
  expect(sameOrder(scoreOrder(ITEMS), placedOrder(ITEMS))).toBe(false);
});

test("an unstamped list opens in score order and a stamped one opens in the designers' order", () => {
  expect(openingOrder(ITEMS, null)).toEqual(["b", "c", "a"]);
  expect(openingOrder(ITEMS, { by: "Meera", at: "2026-08-20" })).toEqual(["a", "b", "c"]);
});

test("a fixed list does not re-sort when the scores move", () => {
  const fixed = { by: "Meera", at: "2026-08-20" };
  const before = openingOrder(ITEMS, fixed);
  // The same pieces, rated again overnight: every default position has changed and no ordinal has.
  const rescored: RankedItem[] = [item("a", 1, 1), item("b", 3, 2), item("c", 2, 3)];
  expect(openingOrder(rescored, fixed)).toEqual(before);
  // And the same list with the stamp cleared DOES follow the new scores — which is what "return to
  // the default order" has to mean for the override to be worth recording at all.
  expect(openingOrder(rescored, null)).toEqual(["a", "c", "b"]);
});

test("a hand-made arrangement survives a refresh: gone ids drop, new ones are appended", () => {
  const arrangement = ["c", "a", "b"];
  expect(reconcileOrder(arrangement, ITEMS)).toEqual(["c", "a", "b"]);

  const withoutB = [item("a", 2, 1), item("c", 1, 3)];
  expect(reconcileOrder(arrangement, withoutB)).toEqual(["c", "a"]);

  /*
    A NEW PIECE GOES TO THE END, NOT INTO THE MIDDLE ON ITS SCORE. Slotting it in by score would be
    the computed order rearranging a list a designer had fixed — the exact thing the override rule
    forbids — and it would do it on the strength of a single first rating.
  */
  const withD = [...ITEMS, item("d", 1, 4)];
  expect(reconcileOrder(arrangement, withD)).toEqual(["c", "a", "b", "d"]);
});

test("the arrow and the drag share one primitive, and it clamps rather than wrapping", () => {
  const order = ["a", "b", "c"];
  expect(moveBy(order, "c", -1)).toEqual(["a", "c", "b"]);
  expect(moveBy(order, "a", 1)).toEqual(["b", "a", "c"]);
  // At the ends nothing moves — and nothing wraps round, which would send the top card to the
  // bottom on a stray press of the up arrow.
  expect(moveBy(order, "a", -1)).toEqual(order);
  expect(moveBy(order, "c", 1)).toEqual(order);
  // An id that is no longer in the list is a no-op rather than a throw: a refresh can remove a
  // piece between a card being rendered and its arrow being pressed.
  expect(moveBy(order, "zz", 1)).toEqual(order);
  // The drag path: move by index, clamped at both ends, and a move onto its own index changes
  // nothing (so a click that registers as a one-pixel drag cannot stamp an arrangement).
  expect(moveTo(order, 0, 2)).toEqual(["b", "c", "a"]);
  expect(moveTo(order, 2, -5)).toEqual(["c", "a", "b"]);
  expect(moveTo(order, 1, 1)).toEqual(order);
  expect(moveTo(order, 9, 0)).toEqual(order);
});

test("the stamp is read off the rows, and half a stamp is not a stamp", () => {
  expect(fixedOrderStamp([{ _entryId: "a" }, { _entryId: "b" }])).toBeNull();
  expect(fixedOrderStamp([{ _entryId: "a", rankFixedBy: "Meera" }])).toBeNull();
  expect(fixedOrderStamp([{ _entryId: "a", rankFixedAt: "2026-08-20" }])).toBeNull();
  expect(fixedOrderStamp([{ _entryId: "a", rankFixedBy: "   ", rankFixedAt: "2026-08-20" }])).toBeNull();

  // One stamped row is enough — the stamp describes the ARRANGEMENT, and a row written by an older
  // build or not yet synced from a handset must not read as "nobody fixed this".
  expect(
    fixedOrderStamp([{ _entryId: "a" }, { _entryId: "b", rankFixedBy: "Meera", rankFixedAt: "2026-08-20" }])
  ).toEqual({ by: "Meera", at: "2026-08-20" });

  // Two disagreeing stamps: the most recent one is the one on screen.
  expect(
    fixedOrderStamp([
      { _entryId: "a", rankFixedBy: "Meera", rankFixedAt: "2026-08-19" },
      { _entryId: "b", rankFixedBy: "Anil", rankFixedAt: "2026-08-21" }
    ])
  ).toEqual({ by: "Anil", at: "2026-08-21" });
});

test("todayStamp is the yyyy-mm-dd a DATE field in this registry stores", () => {
  expect(todayStamp(new Date(2026, 7, 3))).toBe("2026-08-03");
  expect(/^\d{4}-\d{2}-\d{2}$/.test(todayStamp())).toBe(true);
});

test("an order becomes stage rows: rearranged, every row stamped, nothing else touched", () => {
  const rows: DwRow[] = [
    { _entryId: "a", name: "Stool", sketchNo: "S-1" },
    { _entryId: "b", name: "Tray" },
    { _entryId: "c", name: "Lamp" }
  ];
  const arranged = arrangeRows(rows, ["c", "a", "b"], { by: "Meera", at: "2026-08-20" });
  expect(arranged.map((row) => row._entryId)).toEqual(["c", "a", "b"]);
  // EVERY row carries the stamp, not only the one that moved: "is this list fixed?" must not depend
  // on which row a reader happens to look at.
  expect(arranged.every((row) => row.rankFixedBy === "Meera" && row.rankFixedAt === "2026-08-20")).toBe(true);
  // The rest of the row is untouched — a reorder is a reorder, and a stage save replaces a row's
  // data wholesale with no revision behind it.
  expect(arranged[1]).toMatchObject({ _entryId: "a", name: "Stool", sketchNo: "S-1" });

  /*
    THE ORDINAL IS DELIBERATELY NOT WRITTEN. `buildStageEntries` derives it from the array order at
    send time and ignores any stored `_ordinal`; a second opinion written here would be the one that
    loses, silently, and the reorder would look like it had not taken.
  */
  expect(arranged.some((row) => "_ordinal" in row)).toBe(false);
});

test("clearing the stamp blanks both fields, which is what the registry spells 'still the default'", () => {
  const rows: DwRow[] = [
    { _entryId: "a", rankFixedBy: "Meera", rankFixedAt: "2026-08-20" },
    { _entryId: "b", rankFixedBy: "Meera", rankFixedAt: "2026-08-20" }
  ];
  const cleared = arrangeRows(rows, ["b", "a"], null);
  expect(cleared.every((row) => row.rankFixedBy === "" && row.rankFixedAt === "")).toBe(true);
  expect(fixedOrderStamp(cleared)).toBeNull();
});

test("a row the order cannot name is kept, not dropped", () => {
  /*
    A ROW CREATED ON THIS DEVICE AND NOT YET PUSHED HAS NO `_entryId`, so it cannot appear in an
    order that came from the server. Dropping it here would delete an unsent sketch out of the local
    draft on the next save.
  */
  const rows: DwRow[] = [{ _entryId: "a" }, { _clientKey: "local-1" }, { _entryId: "b" }];
  const arranged = arrangeRows(rows, ["b", "a"], null);
  expect(arranged.map((row) => row._entryId ?? row._clientKey)).toEqual(["b", "a", "local-1"]);
});

test("the arrangement controls are offered only where the server sent the raw ordinal", () => {
  // The ordinal travels only to the workshop's own party and to admins — the same set the stage
  // save admits — so its presence is the honest answer to "may I write a new order back?".
  expect(mayArrange([item("a", 1, 1, { ordinal: 0 }), item("b", 2, 2, { ordinal: 1 })])).toBe(true);
  expect(mayArrange([item("a", 1, 1, { ordinal: 0 }), item("b", 2, 2)])).toBe(false);
  expect(mayArrange(ITEMS)).toBe(false);
  expect(mayArrange([])).toBe(false);
});

test("the stage a rateable entity lives in comes from the registry, not from a hardcoded number", () => {
  const registry = {
    version: "v",
    enums: {},
    stages: [
      {
        number: 11,
        key: "SKETCH_DEVELOPMENT",
        title: "",
        purpose: "",
        notes: "",
        optionalStage: false,
        entities: [
          { key: "sketch", name: "DwSketch", cardinality: "COLLECTION", title: "", description: "", parent: "", labelField: "name", fields: [] }
        ]
      },
      {
        number: 13,
        key: "PROTOTYPE_DEVELOPMENT",
        title: "",
        purpose: "",
        notes: "",
        optionalStage: false,
        entities: [
          { key: "prototype", name: "DwPrototype", cardinality: "COLLECTION", title: "", description: "", parent: "", labelField: "name", fields: [] },
          { key: "prototypeNotes", name: "X", cardinality: "SINGLETON", title: "", description: "", parent: "", labelField: "", fields: [] }
        ]
      }
    ]
  } as unknown as DwRegistry;
  expect(stageKeyForEntity(registry, "sketch")).toBe("SKETCH_DEVELOPMENT");
  expect(stageKeyForEntity(registry, "prototype")).toBe("PROTOTYPE_DEVELOPMENT");
  // A singleton is not a rankable collection, and an entity nobody declares has no stage.
  expect(stageKeyForEntity(registry, "prototypeNotes")).toBeNull();
  expect(stageKeyForEntity(registry, "nothing")).toBeNull();
});

test("the subtitle names the piece's own identifier and is empty when there is none", () => {
  expect(rowSubtitle({ sketchNo: "S-1", designerName: "Meera" })).toBe("S-1 · Meera");
  expect(rowSubtitle({ name: "Stool" })).toBe("");
  expect(rowSubtitle(undefined)).toBe("");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The two orders, part two: what happens when the DEVICE and the SERVER disagree
 * ──────────────────────────────────────────────────────────────────────────── */

test("a local arrangement outranks a stale server ordinal", () => {
  /*
    THE WINDOW THIS IS ABOUT. A reorder is written to the draft immediately and goes up on the next
    sync — which on this fleet can be days later, or never for a stage the repository refused. In
    that window `placedPosition` (the server's ordinal) still describes the PRE-reorder list, while
    the draft's row array already IS the designer's arrangement. Ordering by the server's number
    there rendered the old order underneath the banner "this order was settled deliberately — fixed
    by <them> on <today>": the arrangement looked thrown away and the sentence insisted it had not.
  */
  const stamp = { by: "Meera", at: "2026-08-22" };
  // The server still thinks a-b-c (the placed positions). The device holds c-a-b, which is what the
  // designer actually did before the sync was deferred.
  const rows: DwRow[] = [
    { _entryId: "c", rankFixedBy: "Meera", rankFixedAt: "2026-08-22" },
    { _entryId: "a", rankFixedBy: "Meera", rankFixedAt: "2026-08-22" },
    { _entryId: "b", rankFixedBy: "Meera", rankFixedAt: "2026-08-22" }
  ];
  expect(heldOrder(rows)).toEqual(["c", "a", "b"]);
  expect(openingOrder(ITEMS, stamp, heldOrder(rows))).toEqual(["c", "a", "b"]);
  // Without the local copy — the pool surface, which cannot read the stage — the server's ordinal is
  // all there is, and that is what it gets.
  expect(openingOrder(ITEMS, stamp, null)).toEqual(placedOrder(ITEMS));

  /*
    THE LOCAL ORDER IS RECONCILED, NOT TRUSTED WHOLESALE. A piece deleted by a colleague drops out
    and a new one is appended, exactly as a hand-made arrangement is across a refresh — otherwise a
    stale draft could resurrect a subject the ranking no longer names.
  */
  const withD = [...ITEMS, item("d", 4, 4)];
  expect(openingOrder(withD, stamp, ["c", "zz", "a"])).toEqual(["c", "a", "b", "d"]);

  // AN UNFIXED LIST IGNORES THE ROWS ENTIRELY. The scores govern by the owner's own rule, and the
  // draft's row sequence there is merely whatever order the stage form happens to hold — reading it
  // would quietly redefine "the default order" as "the stage's row order".
  expect(openingOrder(ITEMS, null, ["a", "b", "c"])).toEqual(scoreOrder(ITEMS));

  // Rows with no `_entryId` cannot appear in an order keyed by subject id, so they are skipped here
  // and picked up by `arrangeRows`, which keeps them.
  expect(heldOrder([{ _clientKey: "local-1" }, { _entryId: "a" }])).toEqual(["a"]);
});

test("a pool reviewer's list is the SCORE order, and the page must not claim otherwise", () => {
  /*
    WHY THIS IS PINNED HERE AND NOT LEFT TO THE BANNER. On the POOL round `readsStageRows` is false,
    so the panel holds no rows, so the stamp is null by construction — and `openingOrder` therefore
    returns the score order. The page once said "this is the workshop's own arrangement" over exactly
    this list while every card underneath said "(this list is in score order)". Three statements on
    one screen, two of them wrong, which is the confusion the whole default-versus-override rule
    exists to prevent, reached from the other side.
  */
  expect(fixedOrderStamp([])).toBeNull();
  expect(openingOrder(ITEMS, fixedOrderStamp([]))).toEqual(scoreOrder(ITEMS));
  expect(openingOrder(ITEMS, fixedOrderStamp([]))).not.toEqual(placedOrder(ITEMS));

  const panel = readFileSync(join(__dirname, "..", "components", "sketches", "ReviewPanel.tsx"), "utf8");
  // The sentence that was wrong, in the words it was wrong in. It may not come back.
  expect(panel, "the pool banner must not claim to be showing the workshop's arrangement").not.toContain(
    "<span className=\"font-semibold text-ink-900\">This is the workshop&apos;s own arrangement.</span>"
  );
  expect(panel, "the pool banner has to name the order it is actually showing").toContain(
    "These are in score order"
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * The card's boxes, pinned by reading the source
 *
 * WHY A SOURCE READ. `ReviewCard` is a React component and this repository has no React renderer in
 * its devDependencies — Playwright is the whole of it — so a spec cannot mount it, type into it and
 * reject its request. `existing-media-count-unit.spec.ts` and `derived-fields-unit.spec.ts` read
 * their subjects the same way and for the same reason. What this cannot prove is what the browser
 * paints; what it does prove is that the one line whose behaviour caused the loss still says what
 * stops it.
 * ──────────────────────────────────────────────────────────────────────────── */

test("a failed rating cannot wipe the boxes: `saving` is not a dependency of the effect that resets them", () => {
  /*
    THE FAILURE THIS PINS. The effect that copies a server-held rating into the three controls listed
    `saving` in its dependency array, so it re-ran on the true→false transition at the END of every
    submission — including the failing one, where `catch` sets the message and `finally` clears the
    flag in a single render. For a first-time rating `mine` is null, so the body blanked the comment
    and the suggestion, one line under a message reading "What you have written is still in the
    boxes — try again once you have signal." Offline is the ordinary path on this fleet: a designer
    in a courtyard writes a paragraph, presses Submit, and loses it under a promise that it is safe.
  */
  const card = readFileSync(join(__dirname, "..", "components", "sketches", "ReviewCard.tsx"), "utf8");

  const deps = card
    .split(/\r?\n/)
    .find((line) => line.includes("[mine?.score, mine?.comment, mine?.suggestion"));
  expect(deps, "the effect that follows a server-held rating was not found — has it been renamed?").toBeTruthy();
  expect(deps ?? "", "`saving` in this dependency array fires the effect on the FAILURE path").not.toContain(
    "saving"
  );

  // And the guard is still there, read from a ref: a refresh landing DURING a submission must still
  // not yank the text out from under the person writing it.
  expect(card, "the in-flight guard has to survive as a ref read").toContain("if (savingRef.current) return;");
  expect(card, "the ref has to be set where the flag is set").toContain("savingRef.current = true;");
  expect(card, "and cleared where it is cleared").toContain("savingRef.current = false;");
});

test("the card prints the designers' placement, not the same number twice", () => {
  /*
    `position` was the ON-SCREEN INDEX, and while the list is unfixed — the default state, and the
    only state a pool reviewer ever sees — the list IS the score order, so the card printed
    `defaultPosition` twice under a comment claiming the two could be compared. The designers' number
    is `placedPosition`; the place on screen is already the numbered chip in `RankableList`.
  */
  const card = readFileSync(join(__dirname, "..", "components", "sketches", "ReviewCard.tsx"), "utf8");
  expect(card, "the designers' order has to come off the wire's own field").toContain("item.placedPosition");
  expect(card, "the on-screen index is not the designers' order").not.toContain("Placed {position}");
});

test("the drag commits against the list it was measured on, or not at all", () => {
  /*
    `endDrag` committed `moveTo(order, current.from, current.to)` — two indices computed from a
    snapshot taken at pointerdown, applied to whatever `order` had become by the release. If the
    parent re-derived the list mid-gesture (a Refresh, a colleague's row arriving on a sync) those
    indices addressed different cards, so the wrong piece moved, the announcement named the piece
    that had been dragged rather than the one that moved, and the result was stamped `rankFixedBy` as
    somebody's deliberate arrangement. Every other path here resolves by id first.
  */
  const list = readFileSync(join(__dirname, "..", "components", "sketches", "RankableList.tsx"), "utf8");
  expect(list, "the arrangement has to be snapshotted with the rectangles").toContain("snapshot: [...order]");
  expect(list, "and compared at release before anything is committed").toContain(
    "if (!sameOrder(current.snapshot, order))"
  );
  // The unguarded rectangle read threw inside a state updater on a list that shortened mid-gesture.
  expect(list, "the snapshot can outlive the list it was taken of").toContain("if (!box) return current;");
});
