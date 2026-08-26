import { expect, test } from "@playwright/test";

import { moveIndex } from "@/components/hooks/useDragReorder";

/**
 * THE ONE PURE FUNCTION UNDER EVERY DRAG IN THE APP, PINNED.
 *
 * WHY THIS SPEC EXISTS. `useDragReorder` was extracted from `RankableList` on 2026-08-25 so the
 * custom-sections editor could reuse the gesture, and `moveIndex` came out with it. Three writers go
 * through it: `EntityForm`'s collection rows (`commitMove` → `onRowsChange(moveIndex(rows, …))`), the
 * workshop's custom SECTIONS, and the questions inside one section. The sketch/prototype ranking is
 * NOT one of them and deliberately is not — `RankableList` commits through `reviewRanking.moveTo`
 * instead, for a reason spelled out at the object test below. All three of the real writers end up in
 * what a ministry report prints: the sections ARE its running order, the questions are the order they
 * are asked in on a handset in a courtyard, and a collection row's place in the array IS its ordinal,
 * rewritten from the array order at send time. So "the item ends up somewhere near where it was
 * dropped" is not good enough, and neither is a failure that is only visible as a list that looks
 * slightly wrong.
 *
 * WHAT MAKES IT WORTH ITS OWN TESTS RATHER THAN A GLANCE. Three of its properties are load-bearing
 * somewhere the reader cannot see:
 *
 *   • `useDragReorder` announces the DESTINATION INDEX it asked for — "moved to position 4 of 9" —
 *     and never re-derives where the item actually landed. If `moveIndex` put it anywhere other
 *     than exactly `to`, the polite live region would state a position that is not the one on
 *     screen, and only a screen-reader user would ever find out.
 *   • `CustomSectionsEditor` calls it inside a state updater — `setSections((current) =>
 *     moveIndex(current, from, to))`. Mutating `current` would be mutating React state in place.
 *   • the same editor derives `from` and `to` from composite `sectionIndex:fieldIndex` ids spanning
 *     EVERY section, so an out-of-range pair is a real reachable input and must be refused rather
 *     than clamped into a move nobody asked for.
 *
 * WHAT IT PINS, AND HOW. BEHAVIOUR ONLY — arrays in, arrays out. `review-ranking-unit.spec.ts`
 * failed on 2026-08-25 because it pinned a literal call (`sameOrder(current.snapshot, order)`) that
 * a refactor legitimately replaced while keeping every guarantee, and the lesson is written into
 * its own header. Nothing below reads a line of source.
 *
 * WHAT IT DOES NOT COVER. The gesture: `setPointerCapture`, the rectangle snapshot, the arrangement
 * snapshot that abandons a drag whose ground moved, and the release. Those are a pointer sequence
 * over a React tree, not a pure function; the snapshot guard is pinned from the source in
 * `review-ranking-unit.spec.ts`, which is the closest a repository with no React renderer in its
 * devDependencies can get.
 *
 * PURE NODE — no browser, no server, no IndexedDB.
 * Run: `npx playwright test e2e/drag-reorder-unit.spec.ts --reporter=line`
 */

const ROWS = ["a", "b", "c", "d", "e"] as const;

test("forward: the item lands at exactly the index it was dropped on, and the gap closes behind it", () => {
  /*
    THE FAILURE THIS PINS. The off-by-one that a "remove then insert" reorder invites: splicing the
    item out shortens the array, so inserting at the ORIGINAL target index leaves it one place short
    of where the finger let go. On a nine-prototype ranking that is a list which refuses to accept
    the last position — a designer drags a card to the bottom, it settles second from bottom, and
    the arrangement they signed off is not the one they arranged.
  */
  const next = moveIndex(ROWS, 0, 2);

  expect(next, "b and c close up behind the moved row").toEqual(["b", "c", "a", "d", "e"]);
  expect(next.indexOf("a"), "the moved row sits at the index it was dropped on, not one before it").toBe(2);
});

test("backward: the same, in the other direction", () => {
  /*
    THE ASYMMETRY THIS PINS. Dragging UP needs no compensation and dragging DOWN does, so an
    implementation that "fixed" the forward off-by-one by adjusting the target unconditionally
    breaks the upward direction instead — and upward is the one the arrow buttons use most, because
    a designer promoting a favourite reaches for it repeatedly.
  */
  const next = moveIndex(ROWS, 3, 1);

  expect(next, "the rows it passed shift down by one").toEqual(["a", "d", "b", "c", "e"]);
  expect(next.indexOf("d"), "the moved row sits at the index it was dropped on").toBe(1);
});

test("every in-range destination lands the row exactly there — the announcement says so out loud", () => {
  /*
    WHY THIS IS ASSERTED EXHAUSTIVELY RATHER THAN AT TWO SAMPLE POINTS. `useDragReorder`'s release
    runs `onReorder(from, to)` and then `announceMove(key, to)`: the number a screen-reader user
    hears is the number the hook ASKED for, never the number the row ended up at. The two agreeing
    is a property of this function alone, it is invisible on screen, and the reader who depends on
    it is the one reader who cannot check.
  */
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
  /*
    TWO FAILURES IN ONE TEST, BOTH INVISIBLE UNTIL LATER.

    A drag released on the row it started on is the commonest gesture there is — a designer picks a
    card up, thinks better of it, and puts it down. `useDragReorder` already returns early when
    `to === from`, so this is the belt to that brace: the arrow path and the keyboard path call
    straight through.

    AND THE INPUT IS NOT MUTATED. `CustomSectionsEditor` calls this inside
    `setSections((current) => moveIndex(current, from, to))`, so a splice against `current` would be
    a splice against React state: the reorder would appear to work, and the NEXT render — a save, a
    refetch, a re-key — would draw an arrangement nobody chose, with no way to trace where it came
    from.
  */
  const before = [...ROWS];
  const next = moveIndex(before, 2, 2);

  expect(next, "a row dropped where it was picked up stays where it was").toEqual([...ROWS]);
  expect(before, "the caller's array — which is React state — is left exactly as it was").toEqual([...ROWS]);
  expect(next, "and the answer is a copy, so a caller can hand it straight to setState").not.toBe(before);
});

test("an out-of-range index is refused, and is NOT clamped into a move nobody asked for", () => {
  /*
    THE FAILURE THIS PINS, AND WHY IT IS REACHABLE. `CustomSectionsEditor` runs ONE question-level
    hook over composite `sectionIndex:fieldIndex` ids spanning every section in the workshop, and
    resolves `from`/`to` back through that list. A list that shortened between the measurement and
    the release — a section deleted, a question retired, a draft reloaded — yields indices that
    address rows which are no longer there.

    REFUSED RATHER THAN CLAMPED, and the distinction matters: `moveTo` in `reviewRanking` clamps
    `to` into range, which is right for a caller that has already resolved the row by id, and wrong
    here. Clamping an index that came out of a stale measurement silently files a question at the
    top or the bottom of a section it was never dragged near — and the answers recorded against it
    are stored per (workshop, stage) under its key, so the arrangement is what the report prints.
    Doing nothing is the honest answer, and it is also what leaves the hook's snapshot guard free to
    speak: "was not moved: the list changed while it was being dragged."
  */
  expect(moveIndex(ROWS, ROWS.length, 0), "a `from` past the end moves nothing").toEqual([...ROWS]);
  expect(moveIndex(ROWS, -1, 0), "a negative `from` moves nothing").toEqual([...ROWS]);
  expect(moveIndex(ROWS, 0, ROWS.length), "a `to` past the end is not clamped to the last row").toEqual([...ROWS]);
  expect(moveIndex(ROWS, 0, -1), "a negative `to` is not clamped to the first row").toEqual([...ROWS]);

  // The degenerate lists, where every index is out of range. A workshop with one section is the
  // ordinary state of a workshop somebody has just created.
  expect(moveIndex([], 0, 0), "an empty list has nowhere to move anything to").toEqual([]);
  expect(moveIndex(["only"], 0, 1), "a single row cannot be moved past itself").toEqual(["only"]);
  expect(moveIndex(["only"], 0, 0), "and moving it onto itself is a no-op, not a loss").toEqual(["only"]);
});

test("it reorders whatever it is given, not only strings", () => {
  /*
    NOT PEDANTRY — ALL THREE CALLERS HAND IT OBJECTS, AND NOT ONE OF THEM HANDS IT STRINGS.
    `CustomSectionsEditor` moves whole sections (`moveIndex(current, from, to)` over the section
    buffer) and whole question rows (`moveIndex(section.fields, fromField, toField)`), and
    `EntityForm` moves whole `DwRow`s (`onRowsChange(moveIndex(rows, from, to))`). A change that
    narrowed this to `string[]` — or that compared elements rather than moving them — would reorder
    the wrong thing on every screen that uses it.

    AND `RankableList`, THE ONE PLACE THAT DOES MOVE ID STRINGS, IS NOT A CALLER OF THIS FUNCTION.
    It commits through `reviewRanking.moveTo` — the drag and the arrows alike — and that is
    deliberate, not something the extraction forgot to finish. The two helpers hold OPPOSITE
    contracts for an out-of-range `to`: `moveIndex` refuses it (pinned above), because its callers
    resolve indices out of a measurement that may be stale and clamping one silently files a row
    somewhere nobody dragged it; `moveTo` clamps it, because its callers have already resolved the
    row by id and mean "as far as it goes". `moveBy` is built on that clamp — it hands `from + delta`
    straight down with no bounds check of its own — so re-pointing that component at `moveIndex` in
    the name of using one helper would change what a step off the end of the list means. If the
    exhaustive from × to sweep above is wanted for the ranking as well, add the same loop over
    `moveTo` in `review-ranking-unit.spec.ts` rather than moving the caller.
  */
  const sections = [{ key: "intro" }, { key: "" }, { key: "looms" }];
  const next = moveIndex(sections, 2, 0);

  expect(next.map((section) => section.key), "the objects move, they are not rebuilt").toEqual([
    "looms",
    "intro",
    ""
  ]);
  expect(next[0], "and they are the SAME objects — a copy would drop every unsaved edit on them").toBe(sections[2]);
});
