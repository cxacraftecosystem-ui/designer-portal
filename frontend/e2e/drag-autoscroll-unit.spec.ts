import { expect, test } from "@playwright/test";

import { dragTargetIndex, edgeScrollDelta } from "@/components/hooks/useDragReorder";

/**
 * THE GEOMETRY UNDER EDGE AUTO-SCROLL, PINNED — including the one property the whole design rests on.
 *
 * WHY THIS SPEC EXISTS. Until 2026-08-27 a drag on either client could only reach a destination
 * ALREADY ON SCREEN. On a 360×640 handset one review card or one custom-section panel is most of the
 * viewport, so that is exactly one position — which is what the arrow button beside the grip already
 * does, with a bigger target. The grip carries `touch-action: none` (that is what stops the browser
 * reading the movement as a page scroll before the first `pointermove` arrives), so the page will not
 * scroll itself either. `useDragReorder` therefore drives the scroller itself, and this file is what
 * holds the arithmetic that makes that legal.
 *
 * THE PROPERTY THAT MATTERS IS THE SECOND BLOCK BELOW, and it is worth saying why before the tests.
 * Rule 1 of the hook is that every rectangle is snapshotted ONCE, at pointerdown, because
 * re-measuring mid-gesture feeds the neighbours' CSS shift back into the measurement and the target
 * index oscillates under the finger. Auto-scroll looks like it must break that rule — the rows really
 * do move on screen — and if it did, the oscillation would now have a motor attached, because the
 * scroll is itself driven by the target index.
 *
 * It does not, because a scroll translates EVERY row by the same amount and `dragTargetIndex` only
 * ever compares rows with each other. The snapshot survives untouched and the scroll enters as one
 * extra term in the travel. That is a claim about arithmetic, it is invisible on screen, and the two
 * ways of getting it wrong (re-measure the boxes; or keep the snapshot and forget the term) both
 * produce a drag that lands somewhere the designer did not aim. So it is asserted here, exhaustively,
 * rather than argued for in a comment alone.
 *
 * WHAT IT DOES NOT COVER. The gesture itself: `setPointerCapture`, the arming threshold that keeps a
 * press from becoming a scroll, the animation frame the hook books, and the scroller it picks by
 * walking the DOM. Those are a pointer sequence over a React tree and a live layout, and there is no
 * React renderer in this repository's `devDependencies`; `drag-reorder-unit.spec.ts` and
 * `review-ranking-unit.spec.ts` carry the rest of what can be reached without one.
 *
 * PURE NODE — no browser, no server, no IndexedDB.
 * Run: `npx playwright test e2e/drag-autoscroll-unit.spec.ts --reporter=line`
 */

/**
 * Five rows of DELIBERATELY UNEQUAL height, in viewport coordinates as `getBoundingClientRect` gives
 * them: a 12px gap between each, one card expanded to 180px, one short 40px row.
 *
 * Uniform rows would let a wrong formula pass. `EntityForm`'s collection rows expand in place and the
 * custom-sections editor's questions do not, so unequal is the real shape on two of the three screens
 * this hook serves.
 */
const BOXES = [
  { top: 100, height: 60 },
  { top: 172, height: 180 },
  { top: 364, height: 40 },
  { top: 416, height: 60 },
  { top: 488, height: 60 }
];

/** Every row moved by the same amount — what a scroll of `by` physically does to the viewport. */
function scrolled(by: number) {
  return BOXES.map((box) => ({ ...box, top: box.top - by }));
}

test("the target is the furthest row whose centre the dragged centre has passed", () => {
  /*
    THE FAILURE THIS PINS. Comparing EDGES rather than centres: a 60px row dragged down over the
    180px card would "pass" it the moment its top crossed that card's top, so the target would flip
    the instant the gesture began and flip back on the way past — a card that changes its mind under
    the finger. Centres are what make a tall neighbour take half its own height to win.
  */
  // Row 0's centre starts at 130. Row 1's centre is at 262, so 131px of travel is not yet enough.
  expect(dragTargetIndex(BOXES, 0, 131), "one pixel short of the tall card's centre is still home").toBe(0);
  expect(dragTargetIndex(BOXES, 0, 133), "and one pixel past it lands on the tall card").toBe(1);

  // Row 4's centre is at 518; row 2's is at 384, so 135px upward clears it and row 1's does not.
  expect(dragTargetIndex(BOXES, 4, -135), "upward, the same rule in the other direction").toBe(2);
  expect(dragTargetIndex(BOXES, 4, -257), "and further up again once the tall card's centre is passed").toBe(1);
});

test("a gesture that has not moved has not moved anything", () => {
  for (let from = 0; from < BOXES.length; from += 1) {
    expect(dragTargetIndex(BOXES, from, 0), `row ${from} at rest stays at ${from}`).toBe(from);
  }
});

test("a `from` the snapshot no longer holds yields no target at all, rather than throwing", () => {
  /*
    THE SNAPSHOT CAN OUTLIVE THE LIST IT WAS TAKEN OF — a refresh, a colleague's row arriving on a
    sync, a section deleted in another tab. The hook's own guard returns early on the missing box, and
    this is the same guarantee one level down: the pure function answers `from` rather than reading
    past the end, because it is called from inside a React state updater and inside an animation
    frame, and an exception in either takes the tab down rather than losing a drag.
  */
  expect(dragTargetIndex(BOXES, BOXES.length, 400), "a row past the end of the snapshot").toBe(BOXES.length);
  expect(dragTargetIndex(BOXES, -1, 400), "and a negative one").toBe(-1);
  expect(dragTargetIndex([], 0, 400), "and a snapshot with nothing in it").toBe(0);
});

test("A SCROLL AND A FINGER ARE THE SAME PIXELS: only the sum of the two decides the target", () => {
  /*
    THE RECONCILIATION, HALF ONE. `useDragReorder` keeps `offset` (how far the finger has travelled)
    and `scrolled` (how far the scroller has travelled) apart, because the dragged card's own
    `translateY` needs both named — the card is laid out normally, so it moves WITH the scroll, and
    the second term is what puts it back under a finger that did not move. But the index calculation
    must not be able to tell them apart: a designer who reaches the fourth row by dragging their
    thumb 200px and one who holds it at the edge until 200px of list has gone by underneath must land
    in the same place, or the same gesture means two things depending on how the viewport happened to
    be sized.
  */
  for (let from = 0; from < BOXES.length; from += 1) {
    for (const travel of [-300, -140, -1, 0, 1, 140, 300]) {
      const whole = dragTargetIndex(BOXES, from, travel);
      for (const finger of [0, travel / 4, travel / 2, travel]) {
        expect(
          dragTargetIndex(BOXES, from, finger + (travel - finger)),
          `row ${from}: ${finger}px of finger and ${travel - finger}px of scroll must land where ${travel}px of either does`
        ).toBe(whole);
      }
    }
  }
});

test("A SCROLL DOES NOT INVALIDATE THE SNAPSHOT: translating every row by the same amount changes nothing", () => {
  /*
    THE RECONCILIATION, HALF TWO — AND THIS IS THE ONE THAT LETS RULE 1 STAND.

    After a 300px scroll the rows really are 300px higher on screen, so the rectangles taken at
    pointerdown no longer describe where anything is. The instinct is that they must be re-measured.
    They must not: re-measuring is exactly what rule 1 forbids, and it would be worse here than
    anywhere, because the auto-scroll is DRIVEN by the target index — the oscillation would have a
    motor attached to it.

    They do not need to be, because every comparison inside `dragTargetIndex` is between two rows of
    ONE snapshot, and adding a constant to both sides of a comparison changes neither side of it. So
    the fresh measurement and the stale one give the same answer for the same travel, at every row and
    every distance. That is asserted here as a sweep rather than at a sample point, because "it agreed
    for the case I tried" is how a formula that is right in the middle and wrong at the ends survives.
  */
  for (const by of [-400, -120, 120, 400]) {
    const afterScroll = scrolled(by);
    for (let from = 0; from < BOXES.length; from += 1) {
      for (const travel of [-300, -140, 0, 140, 300]) {
        expect(
          dragTargetIndex(afterScroll, from, travel),
          `a ${by}px scroll must not change where row ${from} lands after ${travel}px of travel`
        ).toBe(dragTargetIndex(BOXES, from, travel));
      }
    }
  }
});

test("and the scroll term is load-bearing: dropping it lands the row somewhere nobody aimed", () => {
  /*
    THE OTHER WAY TO GET IT WRONG, and it is the one that looks tidiest in a diff. Having established
    above that the snapshot survives a scroll untouched, it is tempting to conclude that the scroll
    can simply be ignored — keep the rectangles, keep `offset` as the finger's travel, and let the
    browser sort the rest out. That is the bug, not the fix. `offset` alone answers "where would this
    row be if the list had stayed still", and the list did not stay still.

    Concretely: a thumb held at the bottom edge of a phone travels almost nothing while 300px of list
    goes by beneath it. Without the second term that gesture commits the row it started on.
  */
  const finger = 20;
  const byScroll = 300;

  expect(
    dragTargetIndex(BOXES, 0, finger),
    "the finger alone has not reached the tall card's centre"
  ).toBe(0);
  expect(
    dragTargetIndex(BOXES, 0, finger + byScroll),
    "and with the scroll counted in, the same gesture is three rows down"
  ).toBe(3);
});

/*
  ══════════════════════════════════════════════════════════════════════════════════════════════════
  THE EDGE ZONE ITSELF
  ══════════════════════════════════════════════════════════════════════════════════════════════════

  A 640px-tall viewport, the handset this feature exists for, with the document scroller: `top` 0,
  `bottom` 640. One frame at 60fps is ~16ms.
*/
const VIEWPORT = { top: 0, bottom: 640 };
const FRAME = 16;
const AT_REST = { ...VIEWPORT, scroll: 500, max: 2000, elapsedMs: FRAME, reduce: false };

test("the middle of the scroller is neutral, and so is the zone's inner lip", () => {
  /*
    THE FAILURE THIS PINS. A zone with no neutral middle is a control nobody can aim: the list would
    creep whenever the finger was anywhere, and a designer trying to drop a card two rows down would
    watch the destination slide away from them. The lip is asserted as well as the centre because an
    off-by-one there — `<=` where `<` was meant — is the difference between "starts when you reach
    the strip" and "already running when you get there".
  */
  expect(edgeScrollDelta({ ...AT_REST, pointer: 320 }), "dead centre").toBe(0);
  expect(edgeScrollDelta({ ...AT_REST, pointer: 100 }), "well above the bottom strip").toBe(0);
  expect(edgeScrollDelta({ ...AT_REST, pointer: 640 - 72 }), "exactly on the bottom strip's inner lip").toBe(0);
  expect(edgeScrollDelta({ ...AT_REST, pointer: 72 }), "exactly on the top strip's inner lip").toBe(0);
});

test("it travels toward the edge the finger is nearest, faster the deeper in it is", () => {
  const shallow = edgeScrollDelta({ ...AT_REST, pointer: 600 });
  const deep = edgeScrollDelta({ ...AT_REST, pointer: 636 });

  expect(shallow, "down the page, toward the bottom edge").toBeGreaterThan(0);
  expect(deep, "and faster nearer the edge").toBeGreaterThan(shallow);
  expect(edgeScrollDelta({ ...AT_REST, pointer: 40 }), "up the page, toward the top edge").toBeLessThan(0);
});

test("past the edge it saturates rather than accelerating away or falling out of the zone", () => {
  /*
    A POINTER CAN LEAVE THE SCROLLER. A mouse dragged off the top of the window keeps reporting
    negative `clientY`, and a thumb on a phone reaches the bezel. Two wrong answers are available:
    let the depth keep growing, and the list flies past every row at a speed nothing on screen
    explains; or treat "outside the zone" as "not in the zone", and the list stops dead at exactly
    the moment the designer has pushed hardest. It holds at the edge's speed instead.
  */
  const atEdge = edgeScrollDelta({ ...AT_REST, pointer: 640 });
  expect(edgeScrollDelta({ ...AT_REST, pointer: 900 }), "far below the scroller").toBe(atEdge);

  const atTop = edgeScrollDelta({ ...AT_REST, pointer: 0 });
  expect(edgeScrollDelta({ ...AT_REST, pointer: -400 }), "far above it").toBe(atTop);
  expect(atTop, "and it is still travelling").toBeLessThan(0);
});

test("a scroller too short for two strips still has a neutral point between them", () => {
  /*
    `EDGE_ZONE` is 72px and this hook has no say in how tall its container is — `EntityForm`'s
    collection sits inside a disclosure and `CustomSectionsEditor`'s questions inside a panel. Two
    72px strips will not fit in a 100px box, and unhalved they would overlap into a scroller where
    every position is in both strips at once. Halved, the strips meet at the centre and stop there.
  */
  const short = { top: 200, bottom: 300, scroll: 50, max: 400, elapsedMs: FRAME, reduce: false };

  expect(edgeScrollDelta({ ...short, pointer: 250 }), "the meeting point of the two strips").toBe(0);
  expect(edgeScrollDelta({ ...short, pointer: 295 }), "below it, downward").toBeGreaterThan(0);
  expect(edgeScrollDelta({ ...short, pointer: 205 }), "above it, upward").toBeLessThan(0);
});

test("it will not travel past the extent that existed when the finger went down", () => {
  /*
    THE RUNAWAY GUARD, AND IT IS NOT BELT-AND-BRACES. A CSS transform contributes to its scroll
    container's scrollable overflow region, so a row translated 400px down genuinely makes the page
    400px taller for as long as the finger holds it there. Unbounded, auto-scrolling toward the
    bottom would scroll into space the drag had just created, which grows the travel, which pushes
    the card further down, which extends the page again — a gesture that accelerates off the end of
    a list with nothing at the bottom of it.

    The bound is the extent as it stood at pointerdown, and it is the RIGHT bound rather than a safe
    one: the rectangles are fixed at pointerdown too, so the only destinations this gesture can
    commit to are the rows that existed then.
  */
  expect(
    edgeScrollDelta({ ...VIEWPORT, pointer: 640, scroll: 2000, max: 2000, elapsedMs: FRAME, reduce: false }),
    "already at the bottom of the snapshotted extent"
  ).toBe(0);
  expect(
    edgeScrollDelta({ ...VIEWPORT, pointer: 0, scroll: 0, max: 2000, elapsedMs: FRAME, reduce: false }),
    "already at the top"
  ).toBe(0);

  const remaining = edgeScrollDelta({
    ...VIEWPORT,
    pointer: 640,
    scroll: 1996,
    max: 2000,
    elapsedMs: FRAME,
    reduce: false
  });
  expect(remaining, "the last frame is shortened to whatever is left, not refused").toBe(4);
});

test("the clamp shortens a step and never turns one around", () => {
  /*
    A SCROLLER CAN END UP PAST THE EXTENT THIS GESTURE SNAPSHOTTED — browser scroll anchoring moving
    it, another script, or the page having grown under the drag's own transform. Correcting that
    mid-gesture is the wrong instinct: the clamp exists to stop the list running away, not to drag it
    back to where it was, and a viewport that hauls itself upward while a finger is pushing down reads
    as the control fighting the designer.
  */
  expect(
    edgeScrollDelta({ ...VIEWPORT, pointer: 640, scroll: 2400, max: 2000, elapsedMs: FRAME, reduce: false }),
    "a downward step past the extent is refused, not reversed"
  ).toBe(0);
  expect(
    edgeScrollDelta({ ...VIEWPORT, pointer: 0, scroll: -50, max: 2000, elapsedMs: FRAME, reduce: false }),
    "and an upward one from above the top is refused, not reversed"
  ).toBe(0);
});

test("reduced motion JUMPS a whole step at a time, and never glides — but still gets there", () => {
  /*
    BOTH HALVES OF THIS ARE THE POINT, AND THE SECOND IS THE ONE THAT IS EASY TO LOSE.

    Switching auto-scroll OFF under reduced motion would be the obvious reading of the preference and
    is a capability taken away by a motion setting: the drag would go back to reaching exactly one
    position, for precisely the readers who asked for less movement, while everybody else kept the
    feature. The repository's rule is that reduced motion changes HOW something travels and never
    WHETHER the destination is reachable — the same rule `useRevealRow` follows when it swaps
    `behavior: "smooth"` for `"auto"` rather than declining to scroll.

    So the list moves in whole jumps with a dwell between them, which is the substitute a continuous
    animation is meant to degrade to. Nothing fractional ever comes out of this branch: a "step" that
    was scaled by the frame time would be a glide again, drawn at a lower frame rate.
  */
  const zone = { ...VIEWPORT, pointer: 636, scroll: 500, max: 2000, reduce: true };

  expect(edgeScrollDelta({ ...zone, elapsedMs: FRAME }), "one frame into the dwell, nothing moves").toBe(0);
  expect(edgeScrollDelta({ ...zone, elapsedMs: 249 }), "one millisecond short of the dwell, still nothing").toBe(0);
  expect(edgeScrollDelta({ ...zone, elapsedMs: 250 }), "and then a whole jump").toBe(96);
  expect(
    edgeScrollDelta({ ...zone, elapsedMs: 4000 }),
    "a long wait is still ONE jump — the dwell is a floor, not a budget that accumulates"
  ).toBe(96);
  expect(edgeScrollDelta({ ...zone, pointer: 4, elapsedMs: 250 }), "and upward, the same jump").toBe(-96);
  expect(
    edgeScrollDelta({ ...zone, pointer: 320, elapsedMs: 4000 }),
    "the neutral middle is still neutral however long the dwell has been served"
  ).toBe(0);
});

test("reduced motion travels at a rate the pointer branch itself covers", () => {
  /*
    THE THING THAT WOULD MAKE THE TEST ABOVE A HOLLOW COMPROMISE. A step small enough or a dwell long
    enough turns "reachable" into "reachable in theory": a designer with the preference on would sit
    holding a card at the edge of a forty-row list watching it creep, and would reasonably conclude
    the gesture was broken rather than considerate. 96px every 250ms is ~384px per second, which sits
    INSIDE the band the continuous branch already covers — quicker than a finger resting just inside
    the strip, slower than one pressed against the edge. So the preference changes the character of
    the travel and not how long anybody waits to cross a list.

    Rates are compared rather than deltas because the two branches are quoted in different units: one
    is pixels per frame, the other pixels per jump.
  */
  const perSecondReduced = (96 / 250) * 1000;
  const perSecond = (pointer: number) => (edgeScrollDelta({ ...AT_REST, pointer }) / FRAME) * 1000;

  expect(perSecondReduced, "quicker than a finger only just inside the strip").toBeGreaterThan(perSecond(580));
  expect(perSecondReduced, "slower than one held against the very edge").toBeLessThan(perSecond(640));
});
