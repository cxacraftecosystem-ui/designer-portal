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

test("the stage table is the engine's own list, in the engine's own order", () => {
  expect(TRACE_STAGES.map((stage) => stage.id)).toEqual(stageIds());
  expect(TRACE_STAGE_COUNT).toBe(12);

  expect(traceStageIndex("prepare")).toBe(0);
  expect(traceStageIndex("edge")).toBe(6);
  expect(traceStageIndex("document")).toBe(11);
  expect(traceStageIndex("no-such-stage")).toBe(-1);
});

test("the progress sentence numbers a known stage and refuses to number an unknown one", () => {
  expect(traceProgressSentence("edge", "Detecting edges")).toBe("Detecting edges. Stage 7 of 12.");
  expect(traceProgressSentence("mystery", "Doing something new")).toBe("Doing something new");
});

test("the bar is weighted by what the machine measured, and says so when it has not", () => {
  expect(UNWEIGHTED.measured).toBe(false);
  expect(fractionAt(UNWEIGHTED, "prepare", 0)).toBeCloseTo(0, 6);
  expect(fractionAt(UNWEIGHTED, "edge", 6 / 12)).toBeCloseTo(6 / 12, 6);
  expect(PROGRESS_UNMEASURED_NOTE).toContain("counts stages, not time");

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

  expect(progressWeights([])).toBe(UNWEIGHTED);
  expect(progressWeights([{ id: "prepare", millis: 0 }])).toBe(UNWEIGHTED);

  expect(fractionAt(weights, "cleanup", 0.4)).toBeCloseTo(0.4, 6);
  expect(fractionAt(weights, "cleanup", Number.NaN)).toBe(0);
  expect(fractionAt(weights, "cleanup", 4)).toBe(1);
  expect(fractionAt(weights, "cleanup", -4)).toBe(0);
});

test("the difference is an absolute per-channel subtraction, with alpha forced opaque", () => {
  const photograph = new Uint8ClampedArray([200, 10, 90, 255, 0, 255, 128, 255]);
  const trace = new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 255]);
  expect(Array.from(differenceRgba(photograph, trace))).toEqual([
    55, 245, 165, 255,
    0, 255, 128, 255
  ]);

  const same = new Uint8ClampedArray([12, 34, 56, 255, 200, 100, 50, 255]);
  expect(Array.from(differenceRgba(same, same))).toEqual([0, 0, 0, 255, 0, 0, 0, 255]);

  const clearish = new Uint8ClampedArray([10, 20, 30, 0]);
  const opaque = new Uint8ClampedArray([10, 20, 30, 255]);
  expect(Array.from(differenceRgba(clearish, opaque))).toEqual([0, 0, 0, 255]);

  const short = differenceRgba(new Uint8ClampedArray(8), new Uint8ClampedArray(4));
  expect(short.length).toBe(4);
});

test("the two difference sentences are the handset's, and say what is and is not lost", () => {
  expect(COMPARISON_DIFFERENCE_NOTE).toBe(
    "Difference subtracts the two pictures from each other: black where they agree, bright where they " +
      "do not. A line the trace missed and a line it invented both come out bright, and the paper's own " +
      "tone shows as an even dim grey."
  );
  expect(COMPARISON_DIFFERENCE_REFUSAL).toContain("The wipe and the two whole pictures still work");
  expect(COMPARISON_DIFFERENCE_REFUSAL).toContain("the drawing is unaffected");
});

test("every word this view shows is the handset's, including the two it used to invent", () => {

  expect(COMPARISON_DIFFERENCE_PENDING).toBe("Working out the difference picture…");

  expect(COMPARISON_DIFFERENCE_ALT).toBe(
    "The traced drawing and the photograph subtracted from each other. Dark where they agree, " +
      "bright where they differ."
  );
  expect(COMPARISON_DIFFERENCE_ALT).toContain("Dark where they agree");
  expect(COMPARISON_DIFFERENCE_ALT).toContain("bright where they differ");

  expect(COMPARISON_DIFFERENCE_BADGE).toBe("Difference");
  expect(COMPARISON_DIFFERENCE_NOTE.startsWith(COMPARISON_DIFFERENCE_BADGE)).toBe(true);
});

test("the magnification is held between fit and the caller's ceiling", () => {
  expect(clampZoom(1, 6)).toBe(1);
  expect(clampZoom(3.5, 6)).toBe(3.5);
  expect(clampZoom(400, 6)).toBe(6);
  expect(clampZoom(0.25, 6)).toBe(1);
  expect(clampZoom(Number.NaN, 6)).toBe(1);
  expect(clampZoom(9, 1)).toBe(1);

  expect(zoomLabel(2.44)).toBe("2.4×");
  expect(zoomLabel(1)).toBe("1×");
});

test("panning is held inside the picture's own overhang, and there is none at fit", () => {
  const at3x = { zoom: 3, panX: 0, panY: 0 };
  expect(panBy(at3x, 1000, 0, 400, 300).panX).toBeCloseTo(400, 6);
  expect(panBy(at3x, -1000, 0, 400, 300).panX).toBeCloseTo(-400, 6);
  expect(panBy(at3x, 0, 1000, 400, 300).panY).toBeCloseTo(300, 6);
  expect(panBy(at3x, 30, -20, 400, 300)).toEqual({ zoom: 3, panX: 30, panY: -20 });

  expect(panBy(REVEAL_AT_FIT, 500, 500, 400, 300)).toEqual({ zoom: 1, panX: 0, panY: 0 });
  expect(isAtFit(REVEAL_AT_FIT)).toBe(true);
  expect(isAtFit({ zoom: 2, panX: 0, panY: 0 })).toBe(false);

  expect(clampPan({ zoom: 4, panX: 88, panY: 12 }, 0, 0)).toEqual({ zoom: 4, panX: 0, panY: 0 });
});

test("zooming keeps the point under the pointer where it is, and un-zooming returns to centre", () => {
  const frame = { width: 400, height: 300 };

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

  const atCeiling = { zoom: 6, panX: 40, panY: -10 };
  expect(zoomAbout(atCeiling, 2, pointerX, pointerY, frame.width, frame.height, 6)).toEqual(atCeiling);

  expect(zoomAbout(after, 0.01, pointerX, pointerY, frame.width, frame.height, 6)).toEqual({
    zoom: 1,
    panX: 0,
    panY: 0
  });
});

test("the seam converts into the wrapper's own space, and is the identity at fit", () => {
  for (const seam of [0, 12.5, 50, 99, 100]) {
    expect(wrapperPercent(seam, 1, 0, 400)).toBeCloseTo(seam, 9);
  }
  expect(wrapperPercent(37, 3, 100, 0)).toBe(37);

  expect(wrapperPercent(50, 2, 0, 400)).toBeCloseTo(50, 9);
  expect(wrapperPercent(0, 2, 0, 400)).toBeCloseTo(25, 9);
  expect(wrapperPercent(100, 2, 0, 400)).toBeCloseTo(75, 9);

  expect(wrapperPercent(50, 2, 100, 400)).toBeCloseTo(37.5, 9);

  const slack = (400 * (4 - 1)) / 2;
  for (const pan of [-slack, 0, slack]) {
    for (const seam of [0, 50, 100]) {
      const inWrapper = wrapperPercent(seam, 4, panBy({ zoom: 4, panX: 0, panY: 0 }, pan, 0, 400, 300).panX, 400);
      expect(inWrapper).toBeGreaterThanOrEqual(0);
      expect(inWrapper).toBeLessThanOrEqual(100);
    }
  }

  expect(wrapperPercent(0, 2, 500, 400)).toBeLessThan(0);
});

test("the two gesture thresholds are the handset's, and are not zero", () => {
  expect(REVEAL_PEEK_HOLD_MS).toBe(220);
  expect(REVEAL_DRAG_SLOP_PX).toBeGreaterThan(0);
  expect(REVEAL_DRAG_SLOP_PX).toBeLessThan(10);
});
