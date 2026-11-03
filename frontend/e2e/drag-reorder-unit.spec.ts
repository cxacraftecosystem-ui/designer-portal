import { expect, test } from "@playwright/test";

import { moveIndex } from "@/components/hooks/useDragReorder";

const ROWS = ["a", "b", "c", "d", "e"] as const;

test("forward: the item lands at exactly the index it was dropped on, and the gap closes behind it", () => {
  const next = moveIndex(ROWS, 0, 2);

  expect(next, "b and c close up behind the moved row").toEqual(["b", "c", "a", "d", "e"]);
  expect(next.indexOf("a"), "the moved row sits at the index it was dropped on, not one before it").toBe(2);
});

test("backward: the same, in the other direction", () => {
  const next = moveIndex(ROWS, 3, 1);

  expect(next, "the rows it passed shift down by one").toEqual(["a", "d", "b", "c", "e"]);
  expect(next.indexOf("d"), "the moved row sits at the index it was dropped on").toBe(1);
});

test("every in-range destination lands the row exactly there — the announcement says so out loud", () => {
  for (let from = 0; from < ROWS.length; from += 1) {
    for (let to = 0; to < ROWS.length; to += 1) {
      const moved = ROWS[from];
      const next = moveIndex(ROWS, from, to);
      expect(next.indexOf(moved), `moving ${moved} from ${from} to ${to} must land it at ${to}`).toBe(to);
      expect(
        [...next].sort(),
        `and must neither lose nor duplicate a row (${from} → ${to})`
      ).toEqual([...ROWS].sort());
    }
  }
});

test("a no-op destination changes nothing, and the input array is never touched", () => {
  const before = [...ROWS];
  const next = moveIndex(before, 2, 2);

  expect(next, "a row dropped where it was picked up stays where it was").toEqual([...ROWS]);
  expect(before, "the caller's array — which is React state — is left exactly as it was").toEqual([...ROWS]);
  expect(next, "and the answer is a copy, so a caller can hand it straight to setState").not.toBe(before);
});

test("an out-of-range index is refused, and is NOT clamped into a move nobody asked for", () => {
  expect(moveIndex(ROWS, ROWS.length, 0), "a `from` past the end moves nothing").toEqual([...ROWS]);
  expect(moveIndex(ROWS, -1, 0), "a negative `from` moves nothing").toEqual([...ROWS]);
  expect(moveIndex(ROWS, 0, ROWS.length), "a `to` past the end is not clamped to the last row").toEqual([...ROWS]);
  expect(moveIndex(ROWS, 0, -1), "a negative `to` is not clamped to the first row").toEqual([...ROWS]);

  expect(moveIndex([], 0, 0), "an empty list has nowhere to move anything to").toEqual([]);
  expect(moveIndex(["only"], 0, 1), "a single row cannot be moved past itself").toEqual(["only"]);
  expect(moveIndex(["only"], 0, 0), "and moving it onto itself is a no-op, not a loss").toEqual(["only"]);
});

test("it reorders whatever it is given, not only strings", () => {
  const sections = [{ key: "intro" }, { key: "" }, { key: "looms" }];
  const next = moveIndex(sections, 2, 0);

  expect(next.map((section) => section.key), "the objects move, they are not rebuilt").toEqual([
    "looms",
    "intro",
    ""
  ]);
  expect(next[0], "and they are the SAME objects — a copy would drop every unsaved edit on them").toBe(sections[2]);
});
