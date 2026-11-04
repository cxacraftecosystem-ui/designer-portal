import { expect, test } from "@playwright/test";

import { dragTargetIndex, edgeScrollDelta } from "@/components/hooks/useDragReorder";

const BOXES = [
  { top: 100, height: 60 },
  { top: 172, height: 180 },
  { top: 364, height: 40 },
  { top: 416, height: 60 },
  { top: 488, height: 60 }
];

function scrolled(by: number) {
  return BOXES.map((box) => ({ ...box, top: box.top - by }));
}

test("the target is the furthest row whose centre the dragged centre has passed", () => {
  expect(dragTargetIndex(BOXES, 0, 131), "one pixel short of the tall card's centre is still home").toBe(0);
  expect(dragTargetIndex(BOXES, 0, 133), "and one pixel past it lands on the tall card").toBe(1);

  expect(dragTargetIndex(BOXES, 4, -135), "upward, the same rule in the other direction").toBe(2);
  expect(dragTargetIndex(BOXES, 4, -257), "and further up again once the tall card's centre is passed").toBe(1);
});

test("a gesture that has not moved has not moved anything", () => {
  for (let from = 0; from < BOXES.length; from += 1) {
    expect(dragTargetIndex(BOXES, from, 0), `row ${from} at rest stays at ${from}`).toBe(from);
  }
});

test("a `from` the snapshot no longer holds yields no target at all, rather than throwing", () => {
  expect(dragTargetIndex(BOXES, BOXES.length, 400), "a row past the end of the snapshot").toBe(BOXES.length);
  expect(dragTargetIndex(BOXES, -1, 400), "and a negative one").toBe(-1);
  expect(dragTargetIndex([], 0, 400), "and a snapshot with nothing in it").toBe(0);
});

test("A SCROLL AND A FINGER ARE THE SAME PIXELS: only the sum of the two decides the target", () => {
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

const VIEWPORT = { top: 0, bottom: 640 };
const FRAME = 16;
const AT_REST = { ...VIEWPORT, scroll: 500, max: 2000, elapsedMs: FRAME, reduce: false };

test("the middle of the scroller is neutral, and so is the zone's inner lip", () => {
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
  const atEdge = edgeScrollDelta({ ...AT_REST, pointer: 640 });
  expect(edgeScrollDelta({ ...AT_REST, pointer: 900 }), "far below the scroller").toBe(atEdge);

  const atTop = edgeScrollDelta({ ...AT_REST, pointer: 0 });
  expect(edgeScrollDelta({ ...AT_REST, pointer: -400 }), "far above it").toBe(atTop);
  expect(atTop, "and it is still travelling").toBeLessThan(0);
});

test("a scroller too short for two strips still has a neutral point between them", () => {
  const short = { top: 200, bottom: 300, scroll: 50, max: 400, elapsedMs: FRAME, reduce: false };

  expect(edgeScrollDelta({ ...short, pointer: 250 }), "the meeting point of the two strips").toBe(0);
  expect(edgeScrollDelta({ ...short, pointer: 295 }), "below it, downward").toBeGreaterThan(0);
  expect(edgeScrollDelta({ ...short, pointer: 205 }), "above it, upward").toBeLessThan(0);
});

test("it will not travel past the extent that existed when the finger went down", () => {
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
  const perSecondReduced = (96 / 250) * 1000;
  const perSecond = (pointer: number) => (edgeScrollDelta({ ...AT_REST, pointer }) / FRAME) * 1000;

  expect(perSecondReduced, "quicker than a finger only just inside the strip").toBeGreaterThan(perSecond(580));
  expect(perSecondReduced, "slower than one held against the very edge").toBeLessThan(perSecond(640));
});
