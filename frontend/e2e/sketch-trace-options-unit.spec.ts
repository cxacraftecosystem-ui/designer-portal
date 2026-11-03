import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DenoiseMode,
  EdgeEngine,
  VectorModeParam,
  defaultTraceParams,
  sanitizeTraceParams,
  withOverrides
} from "@/lib/trace/engine/params";
import type { TraceParams } from "@/lib/trace/engine/params";

import {
  CHOICES,
  ESSENTIAL_KEYS,
  PARAM_COUNT,
  PARAM_GROUPS,
  SLIDERS,
  TOGGLES,
  applyParamPatch,
  changedLabels,
  formatValue,
  inactiveReason,
  mergeParams,
  overwriteNotice
} from "@/components/sketches/upload/traceParamTable";
import {
  DEFAULT_DERIVED_SUFFIX,
  MAX_SHAPES_PER_FILE,
  VERB_CUBIC,
  VERB_LINE,
  VERB_QUAD,
  buildSvg,
  derivedFileName,
  shapePathData,
  type FlatGeometry,
  type GeometryStyle
} from "@/components/sketches/upload/geometryToSvg";
import { workingSizeFor } from "@/components/sketches/upload/decodeToPixels";
import { ATTACH_SUFFIX, RENDER_SUFFIX, TRACE_SUFFIX } from "@/components/sketches/upload/traceExport";
import {
  BAND_SOURCE_PIXELS,
  resampleRgba,
  resampleRgbaInBands
} from "@/components/sketches/upload/comparisonPlates";

test("every choice option is a value the engine actually recognises", () => {
  const base = defaultTraceParams();
  for (const spec of CHOICES) {
    for (const option of spec.options) {
      const next = applyParamPatch(base, spec.patch(option.value), sanitizeTraceParams);
      expect(
        spec.read(next),
        `${spec.key} = "${option.value}" did not survive the engine's sanitiser. ` +
          "Either the string is misspelled or the engine renamed the enum member."
      ).toBe(option.value);
    }
  }
});

test("mergeParams agrees with the engine's own withOverrides for every patch the table makes", () => {
  const base = defaultTraceParams();
  const patches = [
    ...SLIDERS.map((s) => s.patch((s.min + s.max) / 2)),
    ...TOGGLES.flatMap((t) => [t.patch(true), t.patch(false)]),
    ...CHOICES.flatMap((c) => c.options.map((o) => c.patch(o.value)))
  ];
  for (const patch of patches) {
    expect(sanitizeTraceParams(mergeParams(base, patch))).toEqual(withOverrides(base, patch));
  }
});

test("mergeParams reaches the one nested section, edge.flow, without flattening its siblings", () => {
  const base = defaultTraceParams();
  const spec = SLIDERS.find((s) => s.key === "edge.flow.sigmaM");
  expect(spec, "the flow slider is the only nested write in the table and must not be removed").toBeTruthy();
  const next = applyParamPatch(base, spec!.patch(7.5), sanitizeTraceParams);
  expect(next.edge.flow.sigmaM).toBeCloseTo(7.5, 5);
  expect(next.edge.flow.sigmaC).toBe(base.edge.flow.sigmaC);
  expect(next.edge.sensitivity).toBe(base.edge.sensitivity);
});

test("every slider writes a value the engine keeps, across its whole declared range", () => {
  const base = defaultTraceParams();
  for (const spec of SLIDERS) {
    for (const value of [spec.min, (spec.min + spec.max) / 2, spec.max]) {
      const next = applyParamPatch(base, spec.patch(value), sanitizeTraceParams);
      const readBack = spec.read(next);
      const expected = spec.step >= 1 ? Math.round(value) : value;
      expect(
        readBack,
        `${spec.key} was set to ${expected} and the engine answered ${readBack}, so this slider's ` +
          "declared range reaches past what the engine will keep."
      ).toBeCloseTo(expected, 5);
    }
  }
});

test("every toggle actually flips the field it names", () => {
  const base = defaultTraceParams();
  for (const spec of TOGGLES) {
    const on = applyParamPatch(base, spec.patch(true), sanitizeTraceParams);
    const off = applyParamPatch(base, spec.patch(false), sanitizeTraceParams);
    expect(spec.read(on), `${spec.key} would not turn on`).toBe(true);
    expect(spec.read(off), `${spec.key} would not turn off`).toBe(false);
  }
});

test("the sharpening controls exist, default to off, and can reach the pipeline's gate", () => {
  const base = defaultTraceParams();
  expect(base.preprocess.unsharpAmount).toBe(0);

  const amount = SLIDERS.find((s) => s.key === "preprocess.unsharpAmount");
  const sigma = SLIDERS.find((s) => s.key === "preprocess.unsharpSigma");
  expect(amount, "the sharpen amount control is the owner's explicit request and must not be dropped").toBeTruthy();
  expect(sigma, "sharpening without a radius is one control pretending to be two").toBeTruthy();

  const sharpened = applyParamPatch(base, amount!.patch(1.4), sanitizeTraceParams);
  expect(sharpened.preprocess.unsharpAmount).toBeGreaterThan(0);
  expect(sharpened.preprocess.unsharpAmount).toBeCloseTo(1.4, 5);

  const widened = applyParamPatch(sharpened, sigma!.patch(3), sanitizeTraceParams);
  expect(widened.preprocess.unsharpSigma).toBeCloseTo(3, 5);
  expect(widened.preprocess.unsharpAmount).toBeCloseTo(1.4, 5);
});

test("the table is internally consistent", () => {
  const keys = [...SLIDERS, ...TOGGLES, ...CHOICES].map((s) => s.key);
  expect(new Set(keys).size, "two controls share a key, so one of them cannot be addressed").toBe(keys.length);

  const labels = [...SLIDERS, ...TOGGLES, ...CHOICES].map((s) => s.label);
  expect(new Set(labels).size, "two controls share a label").toBe(labels.length);

  expect(PARAM_COUNT).toBe(SLIDERS.length + TOGGLES.length + CHOICES.length);

  for (const spec of [...SLIDERS, ...TOGGLES, ...CHOICES]) {
    expect(PARAM_GROUPS, `${spec.key} is in group "${spec.group}", which the panel never renders`).toContain(
      spec.group
    );
    expect(spec.hint.length, `${spec.key} has no hint`).toBeGreaterThan(0);
    expect(spec.hint.toLowerCase()).not.toBe(spec.label.toLowerCase());
  }

  for (const spec of SLIDERS) {
    expect(spec.max, `${spec.key} has an empty range`).toBeGreaterThan(spec.min);
    expect(spec.step, `${spec.key} has a non-positive step`).toBeGreaterThan(0);
  }

  for (const key of ESSENTIAL_KEYS) {
    expect(keys, `"${key}" is listed as essential but is not in the table — a dangling reference`).toContain(key);
  }
});

test("formatValue matches its step's precision", () => {
  expect(formatValue(12.4, 1)).toBe("12");
  expect(formatValue(0.5, 0.1)).toBe("0.5");
  expect(formatValue(0.125, 0.01)).toBe("0.13");
  expect(formatValue(Number.NaN, 1)).toBe("0");
});

test("a preset that overwrites nothing produces no notice at all", () => {
  const base = defaultTraceParams();
  expect(changedLabels(base, base)).toEqual([]);
  expect(overwriteNotice("The “Woodcut” style", base, base)).toBeNull();

  const spec = SLIDERS.find((s) => s.key === "output.simplify")!;
  const moved = applyParamPatch(base, spec.patch(4), sanitizeTraceParams);
  const notice = overwriteNotice("The “Woodcut” style", base, moved);
  expect(notice).toContain("Simplify");
  expect(notice).toContain("one setting");
});

const BLACK_STROKE: GeometryStyle = {
  stroke: 0xff000000,
  strokeWidth: 1.5,
  fill: null,
  fillRule: "EVENODD",
  cap: "ROUND",
  join: "ROUND",
  miterLimit: 4,
  opacity: 1
};

function geometryOf(options: {
  coords: number[];
  verbs: number[];
  closed: number;
  styles?: readonly GeometryStyle[];
}): FlatGeometry {
  return {
    coords: new Float32Array(options.coords),
    verbs: new Uint8Array(options.verbs),
    verbStarts: new Uint32Array([0, options.verbs.length]),
    coordStarts: new Uint32Array([0, options.coords.length]),
    closed: new Uint8Array([options.closed]),
    styleTable: options.styles ?? [BLACK_STROKE],
    styleIndex: new Uint32Array([0])
  };
}

test("a shape's path data follows the verbs and their coordinate counts", () => {
  const geometry = geometryOf({
    coords: [0, 0, 10, 0, 20, 5, 30, 0, 40, 10, 50, 10, 60, 0],
    verbs: [VERB_LINE, VERB_QUAD, VERB_CUBIC],
    closed: 0
  });
  expect(shapePathData(geometry, 0, 2)).toBe("M0 0 L10 0 Q20 5 30 0 C40 10 50 10 60 0");
});

test("a closed shape ends in Z and an open one does not", () => {
  const open = geometryOf({ coords: [0, 0, 10, 0], verbs: [VERB_LINE], closed: 0 });
  const closed = geometryOf({ coords: [0, 0, 10, 0], verbs: [VERB_LINE], closed: 1 });
  expect(shapePathData(open, 0, 2).endsWith("Z")).toBe(false);
  expect(shapePathData(closed, 0, 2).endsWith("Z")).toBe(true);
});

test("a verb list that claims more coordinates than the run holds is truncated, not read past", () => {
  const geometry = geometryOf({ coords: [0, 0, 10, 0], verbs: [VERB_LINE, VERB_CUBIC], closed: 0 });
  expect(shapePathData(geometry, 0, 2)).toBe("M0 0 L10 0");
});

test("a non-finite coordinate becomes 0 rather than poisoning the file", () => {
  const geometry = geometryOf({ coords: [0, 0, Number.NaN, Number.POSITIVE_INFINITY], verbs: [VERB_LINE], closed: 0 });
  const d = shapePathData(geometry, 0, 2);
  expect(d).not.toContain("NaN");
  expect(d).not.toContain("Infinity");
  expect(d).toBe("M0 0 L0 0");
});

test("a finite coordinate beyond the renderer's range is clamped, not zeroed", () => {
  const geometry = geometryOf({ coords: [0, 0, 1e9, -1e9], verbs: [VERB_LINE], closed: 0 });
  expect(shapePathData(geometry, 0, 2)).toBe("M0 0 L10000000 -10000000");
});

test("buildSvg writes a well-formed document with the background it was given", () => {
  const geometry = geometryOf({ coords: [0, 0, 10, 10], verbs: [VERB_LINE], closed: 0 });
  const opaque = buildSvg({ geometry, width: 100, height: 50, background: 0xffffffff });
  expect(opaque.svg).toContain('<?xml version="1.0" encoding="UTF-8"?>');
  expect(opaque.svg).toContain('viewBox="0 0 100 50"');
  expect(opaque.svg).toContain('<rect x="0" y="0" width="100" height="50" fill="#ffffff"/>');
  expect(opaque.svg).toContain('stroke="#000000"');
  expect(opaque.svg.trimEnd().endsWith("</svg>")).toBe(true);
  expect(opaque.shapesWritten).toBe(1);
  expect(opaque.truncationNote).toBeNull();

  const transparent = buildSvg({ geometry, width: 100, height: 50, background: null });
  expect(transparent.svg).not.toContain("<rect");
});

test("a degenerate canvas size still produces a file that opens", () => {
  const geometry = geometryOf({ coords: [0, 0, 1, 1], verbs: [VERB_LINE], closed: 0 });
  const result = buildSvg({ geometry, width: 0, height: Number.NaN, background: null });
  expect(result.svg).toContain('viewBox="0 0 1 1"');
});

test("nothing identifying is written into the file, and a provenance note cannot malform it", () => {
  const geometry = geometryOf({ coords: [0, 0, 1, 1], verbs: [VERB_LINE], closed: 0 });
  const result = buildSvg(
    { geometry, width: 10, height: 10, background: null },
    { provenanceNote: "Traced on the device -- from sketch.jpg --- by the portal" }
  );
  const body = /<!--([\s\S]*?)-->/.exec(result.svg)?.[1];
  expect(body, "the provenance note should be written as an XML comment").toBeTruthy();
  expect(body).not.toContain("--");
  expect(body).toContain("Traced on the device");

  expect(result.svg).not.toMatch(/aadhaar|pehchan|latitude|longitude/i);
});

test("the shape ceiling is reported rather than silently applied", () => {
  const shapeCount = MAX_SHAPES_PER_FILE + 5;
  const geometry: FlatGeometry = {
    coords: new Float32Array([0, 0, 1, 1]),
    verbs: new Uint8Array([VERB_LINE]),
    verbStarts: new Uint32Array(shapeCount + 1).fill(1, 1),
    coordStarts: new Uint32Array(shapeCount + 1).fill(4, 1),
    closed: new Uint8Array(shapeCount),
    styleTable: [BLACK_STROKE],
    styleIndex: new Uint32Array(shapeCount)
  };
  const result = buildSvg({ geometry, width: 10, height: 10, background: null });
  expect(result.shapesWritten).toBe(MAX_SHAPES_PER_FILE);
  expect(result.truncationNote).toBeTruthy();
  expect(result.truncationNote).toContain("Minimum speck");
});

test("a style index pointing outside the table still draws the path", () => {
  const geometry: FlatGeometry = {
    ...geometryOf({ coords: [0, 0, 5, 5], verbs: [VERB_LINE], closed: 0 }),
    styleIndex: new Uint32Array([7])
  };
  const result = buildSvg({ geometry, width: 10, height: 10, background: null });
  expect(result.svg).toContain("<path");
  expect(result.svg).toContain('stroke="#000000"');
});

test("the derived file is named after the photograph it came from", () => {
  expect(derivedFileName("IMG_20260822_104512.jpg", "svg")).toBe("IMG_20260822_104512-line-art.svg");
  expect(derivedFileName("bagru block sketch.jpeg", "png")).toBe("bagru block sketch-line-art.png");
  expect(derivedFileName("sketch#1 (final)/v2.png", "svg")).toBe("sketch_1 _final_v2-line-art.svg");
  expect(derivedFileName("", "svg")).toBe("sketch-line-art.svg");
  expect(derivedFileName(".jpg", "svg")).toBe("sketch-line-art.svg");
  expect(derivedFileName(`${"x".repeat(200)}.jpg`, "svg").length).toBeLessThanOrEqual(80 + "-line-art.svg".length);
});

test("the three artefacts one photograph produces cannot end up sharing a name", () => {
  expect(ATTACH_SUFFIX).toBe(DEFAULT_DERIVED_SUFFIX);
  expect(TRACE_SUFFIX).toBe(ATTACH_SUFFIX);
  expect(RENDER_SUFFIX).not.toBe(ATTACH_SUFFIX);

  const source = "Product-Bagru-Block-Print-Photo-2-200620261153.jpg";
  expect(derivedFileName(source, "svg", TRACE_SUFFIX)).toBe(
    "Product-Bagru-Block-Print-Photo-2-200620261153-line-art.svg"
  );
  expect(derivedFileName(source, "png", RENDER_SUFFIX)).toBe(
    "Product-Bagru-Block-Print-Photo-2-200620261153-traced.png"
  );
  expect(derivedFileName(source, "svg")).toBe(derivedFileName(source, "svg", ATTACH_SUFFIX));

  expect(derivedFileName("sheet.jpg", "png", "cropped/2")).toBe("sheet-cropped_2.png");
  expect(derivedFileName("sheet.jpg", "png", "")).toBe("sheet.png");
});

function plane(width: number, height: number, paint: (x: number, y: number) => [number, number, number, number]) {
  const out = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const [r, g, b, a] = paint(x, y);
      const at = (y * width + x) * 4;
      out[at] = r;
      out[at + 1] = g;
      out[at + 2] = b;
      out[at + 3] = a;
    }
  }
  return out;
}

test("the comparison downscale averages rather than dropping pixels, and never upscales", () => {
  const checker = plane(8, 8, (x, y) => {
    const on = (x + y) % 2 === 0 ? 255 : 0;
    return [on, on, on, 255];
  });
  const half = resampleRgba(checker, 8, 8, 4, 4);
  expect(half.length).toBe(4 * 4 * 4);
  for (let i = 0; i < half.length; i += 4) {
    expect(Math.abs(half[i] - 127)).toBeLessThanOrEqual(2);
    expect(half[i + 3]).toBe(255);
  }

  const halves = plane(8, 4, (x) => (x < 4 ? [255, 0, 0, 255] : [0, 0, 255, 255]));
  const shrunk = resampleRgba(halves, 8, 4, 4, 2);
  expect([shrunk[0], shrunk[1], shrunk[2]]).toEqual([255, 0, 0]);
  const rightmost = (0 * 4 + 3) * 4;
  expect([shrunk[rightmost], shrunk[rightmost + 1], shrunk[rightmost + 2]]).toEqual([0, 0, 255]);

  const same = resampleRgba(halves, 8, 4, 40, 20);
  expect(same.length).toBe(8 * 4 * 4);
  expect(Array.from(same)).toEqual(Array.from(halves));

  expect(resampleRgba(halves, 8, 4, 0, 0).length).toBe(4);
});

test("the banded downscale is byte-identical to the synchronous one, and stops when told to", async () => {
  const edge = Math.ceil(Math.sqrt(BAND_SOURCE_PIXELS + 1));
  const rows = 8;
  const photograph = plane(edge, edge, (x, y) => [(x * 7) % 256, (y * 11) % 256, (x + y) % 256, 255]);

  const oneShot = resampleRgba(photograph, edge, edge, 11, rows);
  const banded = await resampleRgbaInBands(photograph, edge, edge, 11, rows);
  expect(banded).not.toBeNull();
  expect(Array.from(banded ?? [])).toEqual(Array.from(oneShot));

  const small = plane(6, 4, (x) => [x * 10, 0, 0, 255]);
  const whole = await resampleRgbaInBands(small, 6, 4, 60, 40);
  expect(Array.from(whole ?? [])).toEqual(Array.from(small));

  expect(await resampleRgbaInBands(photograph, edge, edge, 11, rows, () => true)).toBeNull();

  const unstopped = await resampleRgbaInBands(photograph, edge, edge, 11, rows, () => false);
  expect(Array.from(unstopped ?? [])).toEqual(Array.from(oneShot));
});

test("the decode cap only ever shrinks, and preserves the aspect ratio", () => {
  expect(workingSizeFor(2480, 3508, 4096)).toEqual({ width: 2480, height: 3508 });

  const reduced = workingSizeFor(8000, 6000, 4096);
  expect(Math.max(reduced.width, reduced.height)).toBe(4096);
  expect(reduced.width / reduced.height).toBeCloseTo(8000 / 6000, 3);

  const extreme = workingSizeFor(12000, 3, 4096);
  expect(extreme.height).toBeGreaterThanOrEqual(1);
});

test("a full round trip: defaults, a style-shaped override, and a hand edit on top", () => {
  const base = defaultTraceParams();

  const styleLike: TraceParams = withOverrides(base, {
    output: { vectorMode: base.output.vectorMode, simplify: 2.5, strokeWidth: 0.8 },
    edge: { sensitivity: 0.72 }
  });

  const sharpen = SLIDERS.find((s) => s.key === "preprocess.unsharpAmount")!;
  const edited = applyParamPatch(styleLike, sharpen.patch(2), sanitizeTraceParams);

  expect(edited.preprocess.unsharpAmount).toBeCloseTo(2, 5);
  expect(edited.output.simplify).toBeCloseTo(2.5, 5);
  expect(edited.output.strokeWidth).toBeCloseTo(0.8, 5);
  expect(edited.edge.sensitivity).toBeCloseTo(0.72, 5);

  expect(changedLabels(styleLike, edited)).toEqual(["Sharpen amount"]);
});

test("the sentence a failed engine load shows is the one traceClient already owns", () => {
  const sentence = "The tracing engine could not be loaded. Check your connection and reload the page.";
  const client = readFileSync(join(__dirname, "..", "lib", "trace", "traceClient.ts"), "utf8");
  const runtime = readFileSync(join(__dirname, "..", "components", "sketches", "upload", "traceRuntime.ts"), "utf8");
  expect(client).toContain(sentence);
  expect(runtime).toContain(sentence);
});

test("the inert-control sentences name the right rows, and only under the settings that make them true", () => {
  const base = defaultTraceParams();
  const keys = new Set([
    ...SLIDERS.map((spec) => spec.key),
    ...TOGGLES.map((spec) => spec.key),
    ...CHOICES.map((spec) => spec.key)
  ]);

  const answered = [
    "edge.blurSigma",
    "edge.flow.sigmaM",
    "edge.xdogPhi",
    "cleanup.skeletonize",
    "cleanup.pruneSpurs",
    "cleanup.maxGap",
    "preprocess.claheClip",
    "preprocess.unsharpSigma",
    "preprocess.denoiseStrength"
  ];
  for (const key of answered) expect(keys.has(key)).toBe(true);

  expect(inactiveReason("preprocess.denoiseStrength", withOverrides(base, { preprocess: { denoise: DenoiseMode.MEDIAN } }))).toBe(
    "The median filter works from a fixed radius the panel does not expose, not from this."
  );
  expect(inactiveReason("preprocess.denoiseStrength", withOverrides(base, { preprocess: { denoise: DenoiseMode.NONE } }))).toBe(
    "The noise filter is set to None."
  );
  expect(
    inactiveReason("preprocess.denoiseStrength", withOverrides(base, { preprocess: { denoise: DenoiseMode.BILATERAL } }))
  ).toBeNull();

  const canny = withOverrides(base, { edge: { engine: EdgeEngine.CANNY } });
  const flow = withOverrides(base, { edge: { engine: EdgeEngine.FDOG } });
  const xdog = withOverrides(base, { edge: { engine: EdgeEngine.XDOG } });
  expect(inactiveReason("edge.blurSigma", canny)).toBeNull();
  expect(inactiveReason("edge.blurSigma", flow)).toBe("Only the Canny engine reads this.");
  expect(inactiveReason("edge.flow.sigmaM", flow)).toBeNull();
  expect(inactiveReason("edge.flow.sigmaM", canny)).toBe("Only the Flow engine reads this.");
  expect(inactiveReason("edge.xdogPhi", xdog)).toBeNull();
  expect(inactiveReason("edge.xdogPhi", flow)).toBeNull();
  expect(inactiveReason("edge.xdogPhi", canny)).toBe("Only the XDoG and Flow engines read this.");

  expect(inactiveReason("cleanup.maxGap", withOverrides(base, { cleanup: { bridgeGaps: false } }))).toBe(
    "“Bridge stroke ends” is off."
  );
  expect(TOGGLES.find((spec) => spec.key === "cleanup.bridgeGaps")?.label).toBe("Bridge stroke ends");
  expect(
    inactiveReason("preprocess.claheClip", withOverrides(base, { preprocess: { claheEnabled: false } }))
  ).toBe("“Equalise local contrast” is off.");
  expect(TOGGLES.find((spec) => spec.key === "preprocess.claheEnabled")?.label).toBe("Equalise local contrast");
  expect(
    inactiveReason("preprocess.unsharpSigma", withOverrides(base, { preprocess: { unsharpAmount: 0 } }))
  ).toBe("“Sharpen amount” is 0.");
  expect(SLIDERS.find((spec) => spec.key === "preprocess.unsharpAmount")?.label).toBe("Sharpen amount");
  expect(
    inactiveReason("preprocess.unsharpSigma", withOverrides(base, { preprocess: { unsharpAmount: 1.5 } }))
  ).toBeNull();

  const outline = withOverrides(base, { output: { vectorMode: VectorModeParam.OUTLINE } });
  const thinned = withOverrides(base, { cleanup: { skeletonize: true } });
  expect(inactiveReason("cleanup.skeletonize", outline)).toBe(
    "Outline mode traces the edge of a region, so nothing is thinned."
  );
  expect(inactiveReason("cleanup.pruneSpurs", withOverrides(thinned, { cleanup: { skeletonize: false } }))).toBe(
    "“Reduce ink to centrelines” is off, so there is no skeleton to prune."
  );
  expect(inactiveReason("cleanup.pruneSpurs", withOverrides(outline, { cleanup: { skeletonize: true } }))).toBe(
    "Outline mode traces the edge of a region, so nothing is thinned."
  );
  expect(inactiveReason("cleanup.pruneSpurs", thinned)).toBeNull();

  const noisyAtDefaults = [...keys].filter((key) => inactiveReason(key, base) !== null);
  for (const key of noisyAtDefaults) expect(answered).toContain(key);
});
