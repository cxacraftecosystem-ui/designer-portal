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
  const rescored: RankedItem[] = [item("a", 1, 1), item("b", 3, 2), item("c", 2, 3)];
  expect(openingOrder(rescored, fixed)).toEqual(before);
  expect(openingOrder(rescored, null)).toEqual(["a", "c", "b"]);
});

test("a hand-made arrangement survives a refresh: gone ids drop, new ones are appended", () => {
  const arrangement = ["c", "a", "b"];
  expect(reconcileOrder(arrangement, ITEMS)).toEqual(["c", "a", "b"]);

  const withoutB = [item("a", 2, 1), item("c", 1, 3)];
  expect(reconcileOrder(arrangement, withoutB)).toEqual(["c", "a"]);

  const withD = [...ITEMS, item("d", 1, 4)];
  expect(reconcileOrder(arrangement, withD)).toEqual(["c", "a", "b", "d"]);
});

test("the arrow and the drag share one primitive, and it clamps rather than wrapping", () => {
  const order = ["a", "b", "c"];
  expect(moveBy(order, "c", -1)).toEqual(["a", "c", "b"]);
  expect(moveBy(order, "a", 1)).toEqual(["b", "a", "c"]);
  expect(moveBy(order, "a", -1)).toEqual(order);
  expect(moveBy(order, "c", 1)).toEqual(order);
  expect(moveBy(order, "zz", 1)).toEqual(order);
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

  expect(
    fixedOrderStamp([{ _entryId: "a" }, { _entryId: "b", rankFixedBy: "Meera", rankFixedAt: "2026-08-20" }])
  ).toEqual({ by: "Meera", at: "2026-08-20" });

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
  expect(arranged.every((row) => row.rankFixedBy === "Meera" && row.rankFixedAt === "2026-08-20")).toBe(true);
  expect(arranged[1]).toMatchObject({ _entryId: "a", name: "Stool", sketchNo: "S-1" });

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
  const rows: DwRow[] = [{ _entryId: "a" }, { _clientKey: "local-1" }, { _entryId: "b" }];
  const arranged = arrangeRows(rows, ["b", "a"], null);
  expect(arranged.map((row) => row._entryId ?? row._clientKey)).toEqual(["b", "a", "local-1"]);
});

test("the arrangement controls are offered only where the server sent the raw ordinal", () => {
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
  expect(stageKeyForEntity(registry, "prototypeNotes")).toBeNull();
  expect(stageKeyForEntity(registry, "nothing")).toBeNull();
});

test("the subtitle names the piece's own identifier and is empty when there is none", () => {
  expect(rowSubtitle({ sketchNo: "S-1", designerName: "Meera" })).toBe("S-1 · Meera");
  expect(rowSubtitle({ name: "Stool" })).toBe("");
  expect(rowSubtitle(undefined)).toBe("");
});

test("a local arrangement outranks a stale server ordinal", () => {
  const stamp = { by: "Meera", at: "2026-08-22" };
  const rows: DwRow[] = [
    { _entryId: "c", rankFixedBy: "Meera", rankFixedAt: "2026-08-22" },
    { _entryId: "a", rankFixedBy: "Meera", rankFixedAt: "2026-08-22" },
    { _entryId: "b", rankFixedBy: "Meera", rankFixedAt: "2026-08-22" }
  ];
  expect(heldOrder(rows)).toEqual(["c", "a", "b"]);
  expect(openingOrder(ITEMS, stamp, heldOrder(rows))).toEqual(["c", "a", "b"]);
  expect(openingOrder(ITEMS, stamp, null)).toEqual(placedOrder(ITEMS));

  const withD = [...ITEMS, item("d", 4, 4)];
  expect(openingOrder(withD, stamp, ["c", "zz", "a"])).toEqual(["c", "a", "b", "d"]);

  expect(openingOrder(ITEMS, null, ["a", "b", "c"])).toEqual(scoreOrder(ITEMS));

  expect(heldOrder([{ _clientKey: "local-1" }, { _entryId: "a" }])).toEqual(["a"]);
});

test("a pool reviewer's list is the SCORE order, and the page must not claim otherwise", () => {
  expect(fixedOrderStamp([])).toBeNull();
  expect(openingOrder(ITEMS, fixedOrderStamp([]))).toEqual(scoreOrder(ITEMS));
  expect(openingOrder(ITEMS, fixedOrderStamp([]))).not.toEqual(placedOrder(ITEMS));

  const panel = readFileSync(join(__dirname, "..", "components", "sketches", "ReviewPanel.tsx"), "utf8");
  expect(panel, "the pool banner must not claim to be showing the workshop's arrangement").not.toContain(
    "<span className=\"font-semibold text-ink-900\">This is the workshop&apos;s own arrangement.</span>"
  );
  expect(panel, "the pool banner has to name the order it is actually showing").toContain(
    "These are in score order"
  );
});

test("a failed rating cannot wipe the boxes: `saving` is not a dependency of the effect that resets them", () => {
  const card = readFileSync(join(__dirname, "..", "components", "sketches", "ReviewCard.tsx"), "utf8");

  const deps = card
    .split(/\r?\n/)
    .find((line) => line.includes("[mine?.score, mine?.comment, mine?.suggestion"));
  expect(deps, "the effect that follows a server-held rating was not found — has it been renamed?").toBeTruthy();
  expect(deps ?? "", "`saving` in this dependency array fires the effect on the FAILURE path").not.toContain(
    "saving"
  );

  expect(card, "the in-flight guard has to survive as a ref read").toContain("if (savingRef.current) return;");
  expect(card, "the ref has to be set where the flag is set").toContain("savingRef.current = true;");
  expect(card, "and cleared where it is cleared").toContain("savingRef.current = false;");
});

test("the card prints the designers' placement, not the same number twice", () => {
  const card = readFileSync(join(__dirname, "..", "components", "sketches", "ReviewCard.tsx"), "utf8");
  expect(card, "the designers' order has to come off the wire's own field").toContain("item.placedPosition");
  expect(card, "the on-screen index is not the designers' order").not.toContain("Placed {position}");
});

test("the drag commits against the list it was measured on, or not at all", () => {
  const drag = readFileSync(join(__dirname, "..", "components", "hooks", "useDragReorder.ts"), "utf8");
  expect(drag, "the arrangement has to be snapshotted with the rectangles").toContain("snapshot: [...order]");

  const release = drag.slice(drag.indexOf("onPointerUp("), drag.indexOf("onPointerCancel("));
  expect(release, "the release has to compare the snapshot it measured against").toContain("current.snapshot");
  expect(
    release.indexOf("current.snapshot"),
    "and it has to compare BEFORE it reorders, or the guard is decoration"
  ).toBeLessThan(release.indexOf("onReorder("));
  expect(release, "a gesture whose ground moved is abandoned, not guessed at").toContain("return;");

  expect(drag, "the snapshot can outlive the list it was taken of").toContain("if (!box) return current;");
});
