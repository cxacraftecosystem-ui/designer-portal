import { expect, test } from "@playwright/test";

import { stageIds } from "@/lib/trace/engine";
import {
  COMPARISON_DIFFERENCE_ALT,
  COMPARISON_DIFFERENCE_BADGE,
  COMPARISON_DIFFERENCE_NOTE,
  COMPARISON_DIFFERENCE_PENDING,
  COMPARISON_DIFFERENCE_REFUSAL,
  differenceRgba
} from "@/components/sketches/upload/comparisonPlates";
import {
  PROGRESS_UNMEASURED_NOTE,
  TRACE_STAGES,
  TRACE_STAGE_COUNT,
  UNWEIGHTED,
  fractionAt,
  progressWeights,
  traceProgressSentence,
  traceStageIndex
} from "@/components/sketches/upload/traceStages";
import {
  REVEAL_AT_FIT,
  REVEAL_DRAG_SLOP_PX,
  REVEAL_PEEK_HOLD_MS,
  clampPan,
  clampZoom,
  isAtFit,
  panBy,
  wrapperPercent,
  zoomAbout,
  zoomLabel
} from "@/components/ui/reveal1Transform";

/**
 * **THE COMPARISON SURFACE'S ARITHMETIC, WITH NO BROWSER AND NO REACT.**
 *
 * WHY THIS FILE EXISTS. `buildComparisonPlates`, `renderTrace` and the comparator itself had callers
 * on both clients and no covering test anywhere — a parity audit on 2026-08-27 found the whole
 * comparison path uncovered on the portal and on the handset. `sketch-trace-panel.spec.ts` renders the
 * panel in a real browser and asserts what a designer sees; that is the right tool for the wiring and
 * the wrong one for a clamp at a boundary, which would need a pointer gesture staged to land exactly
 * on it. Everything here is a pure function of numbers and strings, so it is called directly.
 *
 * NAMED `-unit`, WHICH IS THIS REPOSITORY'S PROMISE THAT A SPEC NEEDS NO SERVICES. No `page` fixture
 * appears below, so the CI job that runs these specs — which deliberately does not install a browser —
 * can run it. One command:
 *
 *     cd frontend && npx playwright test sketch-compare-unit --reporter=line
 *
 * ── THE THREE THINGS IT IS FOR ────────────────────────────────────────────────────────────────
 *
 *  1. **The difference plate's arithmetic**, which two clients now implement separately and which they
 *     must implement identically. The definition is stated once, in `differenceRgba`'s own docblock and
 *     in the handset's `dwTraceDifferenceRow`, and the cases below are what stops one of them drifting.
 *  2. **The magnifier's clamps**, every one of which is a picture a designer cannot get back if it is
 *     wrong: a pan past the overhang is a plate flicked off its own window.
 *  3. **The twelve stages**, which are a TRANSCRIPTION of a vendored file this unit may not edit. A
 *     transcription that nothing checks is a transcription that is already wrong, so the first case
 *     below reads the real engine and compares.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The twelve stages, against the engine that defines them
 * ──────────────────────────────────────────────────────────────────────────── */

test("the stage table is the engine's own list, in the engine's own order", () => {
  /*
    THE ONE ASSERTION THAT CANNOT BE SATISFIED BY AGREEING WITH ITSELF. `traceStages.ts` exists because
    `SketchTraceField.tsx` may not import `@/lib/trace/*` at the top level — the engine would land in
    the page's bundle — so the table is copied. This spec is under no such rule, so it holds the copy
    against the original. A vendored update that inserts a thirteenth stage fails here rather than
    silently mis-numbering the sentence a designer reads while they wait.
  */
  expect(TRACE_STAGES.map((stage) => stage.id)).toEqual(stageIds());
  expect(TRACE_STAGE_COUNT).toBe(12);

  // Execution order is the contract, not merely the membership: the sentence says "Stage 7 of 12" and
  // a set that agreed while the order did not would number every stage wrongly and still pass.
  expect(traceStageIndex("prepare")).toBe(0);
  expect(traceStageIndex("edge")).toBe(6);
  expect(traceStageIndex("document")).toBe(11);
  expect(traceStageIndex("no-such-stage")).toBe(-1);
});

test("the progress sentence numbers a known stage and refuses to number an unknown one", () => {
  // The LABEL is the engine's, passed through untouched — re-typing engine wording in a client is how
  // the two clients end up describing one operation differently.
  expect(traceProgressSentence("edge", "Detecting edges")).toBe("Detecting edges. Stage 7 of 12.");
  // A stage this build has never heard of gets its label and NO number. A wrong number would be worse
  // than none: it is the one part of the sentence a designer uses to judge how long is left.
  expect(traceProgressSentence("mystery", "Doing something new")).toBe("Doing something new");
});

test("the bar is weighted by what the machine measured, and says so when it has not", () => {
  // BEFORE ANY TRACE: the engine's even twelfths, and `measured` false so the panel prints the sentence
  // that stops a stalling bar reading as a hang.
  expect(UNWEIGHTED.measured).toBe(false);
  expect(fractionAt(UNWEIGHTED, "prepare", 0)).toBeCloseTo(0, 6);
  expect(fractionAt(UNWEIGHTED, "edge", 6 / 12)).toBeCloseTo(6 / 12, 6);
  expect(PROGRESS_UNMEASURED_NOTE).toContain("counts stages, not time");

  /*
    AFTER ONE TRACE, THE POINT OF THE WHOLE FILE. These timings are the shape a real trace has — one
    stage worth more than all the others put together — and the weighted bar must reflect it. Under the
    engine's own spacing `edge` starts at 2/4 = 0.5; weighted by these timings it starts at 0.1, which
    is the difference between a bar that appears to hang for four fifths of the wait and one that does
    not.
  */
  const weights = progressWeights([
    { id: "prepare", millis: 5 },
    { id: "edge", millis: 90 },
    { id: "vectorize", millis: 4 },
    { id: "document", millis: 1 }
  ]);
  expect(weights.measured).toBe(true);
  expect(fractionAt(weights, "prepare", 0)).toBeCloseTo(0, 6);
  expect(fractionAt(weights, "edge", 0.25)).toBeCloseTo(0.05, 6);
  expect(fractionAt(weights, "vectorize", 0.5)).toBeCloseTo(0.95, 6);
  expect(fractionAt(weights, "document", 0.75)).toBeCloseTo(0.99, 6);

  // A PREVIEW REPORTS NO TIMINGS AT ALL, and a trace fast enough for every stage to round to zero is a
  // real case too. Both answer the unweighted table rather than dividing by nothing.
  expect(progressWeights([])).toBe(UNWEIGHTED);
  expect(progressWeights([{ id: "prepare", millis: 0 }])).toBe(UNWEIGHTED);

  // An id the weights have never seen falls back to what the engine sent, and a non-finite fraction
  // falls back to the start of the bar rather than to `NaN%`, which renders as no bar at all.
  expect(fractionAt(weights, "cleanup", 0.4)).toBeCloseTo(0.4, 6);
  expect(fractionAt(weights, "cleanup", Number.NaN)).toBe(0);
  // …and nothing may leave 0..1, whatever a future engine sends.
  expect(fractionAt(weights, "cleanup", 4)).toBe(1);
  expect(fractionAt(weights, "cleanup", -4)).toBe(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The difference plate
 * ──────────────────────────────────────────────────────────────────────────── */

test("the difference is an absolute per-channel subtraction, with alpha forced opaque", () => {
  /*
    THE DEFINITION BOTH CLIENTS IMPLEMENT, pinned here so the two cannot drift. `differenceRgba`'s own
    docblock and the handset's `dwTraceDifferenceRow` state it in the same words: red, green and blue
    subtracted independently with the sign dropped, and NOT a luminance difference — a luminance
    difference needs a set of weights, there is more than one standard set, and the day the clients
    chose different ones the same drawing would produce two different pictures with nothing on either
    screen to say which was right.
  */
  const photograph = new Uint8ClampedArray([200, 10, 90, 255, 0, 255, 128, 255]);
  const trace = new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 255]);
  expect(Array.from(differenceRgba(photograph, trace))).toEqual([
    55, 245, 165, 255,
    // A pixel where the trace is black and the photograph is bright: the whole of each channel.
    0, 255, 128, 255
  ]);

  // AGREEMENT IS BLACK. This is the property a designer reads the picture by, so it is asserted rather
  // than assumed: identical planes must come back all-zero except for the alpha.
  const same = new Uint8ClampedArray([12, 34, 56, 255, 200, 100, 50, 255]);
  expect(Array.from(differenceRgba(same, same))).toEqual([0, 0, 0, 255, 0, 0, 0, 255]);

  /*
    ALPHA IS FORCED, NOT SUBTRACTED. Both plates are opaque by construction — the trace plate is painted
    on white and the photograph is a decode — so a subtracted alpha would be zero everywhere, and an
    invisible picture is indistinguishable from a plate that never got built.
  */
  const clearish = new Uint8ClampedArray([10, 20, 30, 0]);
  const opaque = new Uint8ClampedArray([10, 20, 30, 255]);
  expect(Array.from(differenceRgba(clearish, opaque))).toEqual([0, 0, 0, 255]);

  // A SHORT ANSWER RATHER THAN A READ PAST THE END. The one caller draws both plates to one agreed
  // size first, so this is a guard on a call that should not happen — and it must not produce garbage
  // if it does.
  const short = differenceRgba(new Uint8ClampedArray(8), new Uint8ClampedArray(4));
  expect(short.length).toBe(4);
});

test("the two difference sentences are the handset's, and say what is and is not lost", () => {
  /*
    WORDING IS OWNED ON THE HANDSET AND COPIED HERE VERBATIM — the rule this repository runs on, and
    the reason a designer moving between the two apps mid-workshop reads one description of one
    picture. These are `DW_TRACE_DIFFERENCE_NOTE` and `DW_TRACE_DIFFERENCE_REFUSAL`, the second with
    "This phone" changed to "This browser" because there is no phone here.
  */
  expect(COMPARISON_DIFFERENCE_NOTE).toBe(
    "Difference subtracts the two pictures from each other: black where they agree, bright where they " +
      "do not. A line the trace missed and a line it invented both come out bright, and the paper's own " +
      "tone shows as an even dim grey."
  );
  // THE REFUSAL SAYS WHAT SURVIVED, which is the half that matters: a refusal about a display artefact
  // must never read as though the drawing were gone.
  expect(COMPARISON_DIFFERENCE_REFUSAL).toContain("The wipe and the two whole pictures still work");
  expect(COMPARISON_DIFFERENCE_REFUSAL).toContain("the drawing is unaffected");
});

test("every word this view shows is the handset's, including the two it used to invent", () => {
  /*
    WHY THESE THREE ARE PINNED SEPARATELY FROM THE TWO ABOVE. The note and the refusal were copied
    across deliberately when the view was built; these three were not. Two of them were written twice
    — once on each client, in different words, for the same state — and the third was written on the
    handset and not here at all. A cross-client verification pass found all three, and pinning them
    is what stops the next one being found the same way.

    They are the whole of what the difference view says. The chip that opens it is "Difference" on
    both, the note under it is `DW_TRACE_DIFFERENCE_NOTE` on both, and these are the remaining three:
    what it says while it is thinking, what it says to a screen reader, and the word it writes on the
    picture itself.
  */

  // 1. THE WAIT. `DwSketchTracePanel.kt` prints exactly this, in the same polite live region, behind
  //    the same refusal-wins precedence. The portal used to say "Subtracting the two pictures…".
  expect(COMPARISON_DIFFERENCE_PENDING).toBe("Working out the difference picture…");

  // 2. THE DESCRIPTION. `DwSketchTraceCompare.kt`'s `contentDescription` for this mode, verbatim. The
  //    portal used to say "The difference between the traced drawing and the photograph", which names
  //    the operation and leaves a reader who cannot see the plate unable to read it.
  expect(COMPARISON_DIFFERENCE_ALT).toBe(
    "The traced drawing and the photograph subtracted from each other. Dark where they agree, " +
      "bright where they differ."
  );
  // It carries the two facts a sighted designer takes off the picture in a second, and those are the
  // ones the note under the frame states too — so the frame and its caption cannot contradict.
  expect(COMPARISON_DIFFERENCE_ALT).toContain("Dark where they agree");
  expect(COMPARISON_DIFFERENCE_ALT).toContain("bright where they differ");

  // 3. THE BADGE, which the portal did not draw at all. It is the same word as the chip on purpose:
  //    the picture and the pressed control name each other.
  expect(COMPARISON_DIFFERENCE_BADGE).toBe("Difference");
  expect(COMPARISON_DIFFERENCE_NOTE.startsWith(COMPARISON_DIFFERENCE_BADGE)).toBe(true);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The magnifier
 * ──────────────────────────────────────────────────────────────────────────── */

test("the magnification is held between fit and the caller's ceiling", () => {
  expect(clampZoom(1, 6)).toBe(1);
  expect(clampZoom(3.5, 6)).toBe(3.5);
  // NEVER PAST THE CEILING. Six is the comparator's, matching the handset's `DW_TRACE_MAX_ZOOM`:
  // beyond it a 1024px plate is showing its own pixels rather than the drawing's.
  expect(clampZoom(400, 6)).toBe(6);
  // AND NEVER BELOW FIT. Zooming out past the frame would letterbox a picture inside a frame that is
  // already the picture's own ratio, which is a smaller drawing and nothing else.
  expect(clampZoom(0.25, 6)).toBe(1);
  expect(clampZoom(Number.NaN, 6)).toBe(1);
  // A caller that offers no magnifier gets one that cannot magnify, rather than an unbounded one.
  expect(clampZoom(9, 1)).toBe(1);

  expect(zoomLabel(2.44)).toBe("2.4×");
  expect(zoomLabel(1)).toBe("1×");
});

test("panning is held inside the picture's own overhang, and there is none at fit", () => {
  /*
    THE OVERHANG IS EXACTLY WHAT THE ZOOM ADDED: the wrapper is the frame at fit and `frame * zoom`
    magnified, so it hangs over each edge by `frame * (zoom - 1) / 2`. At 3x in a 400px frame that is
    400 px each way — and one pixel more than that is a plate flicked off its own window with nothing
    on screen to drag it back by.
  */
  const at3x = { zoom: 3, panX: 0, panY: 0 };
  expect(panBy(at3x, 1000, 0, 400, 300).panX).toBeCloseTo(400, 6);
  expect(panBy(at3x, -1000, 0, 400, 300).panX).toBeCloseTo(-400, 6);
  expect(panBy(at3x, 0, 1000, 400, 300).panY).toBeCloseTo(300, 6);
  expect(panBy(at3x, 30, -20, 400, 300)).toEqual({ zoom: 3, panX: 30, panY: -20 });

  // AT FIT THERE IS NOTHING TO PAN, so a drag cannot move the picture at all. Without this a designer
  // could push an unmagnified plate off the frame and be left with an empty box.
  expect(panBy(REVEAL_AT_FIT, 500, 500, 400, 300)).toEqual({ zoom: 1, panX: 0, panY: 0 });
  expect(isAtFit(REVEAL_AT_FIT)).toBe(true);
  expect(isAtFit({ zoom: 2, panX: 0, panY: 0 })).toBe(false);

  // A FRAME THAT HAS NOT BEEN LAID OUT YET pins the picture centred rather than dividing by nothing.
  expect(clampPan({ zoom: 4, panX: 88, panY: 12 }, 0, 0)).toEqual({ zoom: 4, panX: 0, panY: 0 });
});

test("zooming keeps the point under the pointer where it is, and un-zooming returns to centre", () => {
  const frame = { width: 400, height: 300 };

  /*
    THE PROPERTY THAT MAKES A MAGNIFIER USABLE. The reason this control exists is that a pencil line on
    a 1024px plate in a card a few hundred pixels wide is sub-pixel — so the designer has already found
    the line they are suspicious of before they zoom. Magnifying about the CENTRE instead would throw it
    off screen at every step and make them hunt for it again.

    Stated as arithmetic: a point at wrapper offset `w` sits at frame offset `w * zoom + pan`, and after
    a zoom about that point it must sit in the same place.
  */
  const pointerX = 340;
  const pointerY = 60;
  const before = { zoom: 1, panX: 0, panY: 0 };
  const after = zoomAbout(before, 2, pointerX, pointerY, frame.width, frame.height, 6);
  expect(after.zoom).toBe(2);

  const wrapperOffset = (frameOffset: number, half: number, pan: number, zoom: number) =>
    (frameOffset - half - pan) / zoom;
  expect(wrapperOffset(pointerX, frame.width / 2, before.panX, before.zoom)).toBeCloseTo(
    wrapperOffset(pointerX, frame.width / 2, after.panX, after.zoom),
    6
  );
  expect(wrapperOffset(pointerY, frame.height / 2, before.panY, before.zoom)).toBeCloseTo(
    wrapperOffset(pointerY, frame.height / 2, after.panY, after.zoom),
    6
  );

  // A ZOOM THAT CHANGES NOTHING MOVES NOTHING. Without the `z' === z` identity a wheel event that hit
  // the ceiling would drift the picture a little on every further notch.
  const atCeiling = { zoom: 6, panX: 40, panY: -10 };
  expect(zoomAbout(atCeiling, 2, pointerX, pointerY, frame.width, frame.height, 6)).toEqual(atCeiling);

  // COMING BACK TO FIT COMES BACK TO CENTRE, because the overhang at zoom 1 is zero and the clamp says
  // so. A magnifier that left a stale translation behind would show a plate offset inside its frame
  // with no visible cause.
  expect(zoomAbout(after, 0.01, pointerX, pointerY, frame.width, frame.height, 6)).toEqual({
    zoom: 1,
    panX: 0,
    panY: 0
  });
});

test("the seam converts into the wrapper's own space, and is the identity at fit", () => {
  /*
    THE ONE PLACE THE TWO COORDINATE SYSTEMS MEET, and the one that decides whether the join on screen
    is drawn where the pictures actually meet. The SEAM is in frame space — it must not pan away with
    the picture, or at 2x it would leave the frame entirely with no way back — while the layer it clips
    is inside the transform.
  */
  // AT FIT IT IS THE IDENTITY, so an unmagnified comparator emits exactly the clip string it always
  // did. This is what lets the whole magnifier ship without changing a single existing pixel.
  for (const seam of [0, 12.5, 50, 99, 100]) {
    expect(wrapperPercent(seam, 1, 0, 400)).toBeCloseTo(seam, 9);
  }
  // …and before layout, with no box to convert against, it answers its input rather than NaN.
  expect(wrapperPercent(37, 3, 100, 0)).toBe(37);

  /*
    MAGNIFIED, THE CONVERSION IS THE ONE THAT PUTS THE JOIN BACK. At 2x with no pan, the wrapper is
    twice the frame and centred: the frame's centre is the wrapper's centre, and the frame's left edge
    is a quarter of the way across the wrapper.
  */
  expect(wrapperPercent(50, 2, 0, 400)).toBeCloseTo(50, 9);
  expect(wrapperPercent(0, 2, 0, 400)).toBeCloseTo(25, 9);
  expect(wrapperPercent(100, 2, 0, 400)).toBeCloseTo(75, 9);

  // Panned right by a quarter of the frame at 2x, everything shifts back by an eighth of the wrapper.
  expect(wrapperPercent(50, 2, 100, 400)).toBeCloseTo(37.5, 9);

  /*
    EVERY SEAM A DESIGNER CAN REACH LANDS INSIDE 0..100, and this is why the answer needs no clamp of
    its own: a pan that has been through `clampPan` leaves the frame entirely inside the wrapper,
    because the overhang IS what the zoom added. So the whole travel of the seam, at the ceiling, at
    the clamp's own limits, stays in range.
  */
  const slack = (400 * (4 - 1)) / 2;
  for (const pan of [-slack, 0, slack]) {
    for (const seam of [0, 50, 100]) {
      const inWrapper = wrapperPercent(seam, 4, panBy({ zoom: 4, panX: 0, panY: 0 }, pan, 0, 400, 300).panX, 400);
      expect(inWrapper).toBeGreaterThanOrEqual(0);
      expect(inWrapper).toBeLessThanOrEqual(100);
    }
  }

  /*
    WHAT THE MISSING CLAMP BUYS IS TOTALITY. Handed a pan nothing clamped — which is not a state the
    component can be in, and is a state a future caller could hand it — this still answers a number
    rather than pinning the join to the wrapper's edge, and `inset()` renders a negative inset as
    "clip nothing" and one over 100% as "clip everything".
  */
  expect(wrapperPercent(0, 2, 500, 400)).toBeLessThan(0);
});

test("the two gesture thresholds are the handset's, and are not zero", () => {
  /*
    220 ms, THE SAME NUMBER AND FOR THE SAME REASON AS `DW_TRACE_PEEK_HOLD_MS`: a peek that began on
    contact would fire at the start of every pinch, because a two-finger gesture puts one finger down
    first. And it is well under a long-press threshold, because the designer is holding to look rather
    than holding to open a menu.
  */
  expect(REVEAL_PEEK_HOLD_MS).toBe(220);
  // A SLOP THAT IS NOT ZERO. Without it a hand-held pointer's own jitter cancels the peek before it
  // starts, and a press that moves one pixel writes the seam somewhere nobody asked for.
  expect(REVEAL_DRAG_SLOP_PX).toBeGreaterThan(0);
  expect(REVEAL_DRAG_SLOP_PX).toBeLessThan(10);
});
