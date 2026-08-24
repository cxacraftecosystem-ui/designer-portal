import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { defaultTraceParams, sanitizeTraceParams, withOverrides } from "@/lib/trace/engine/params";
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

/**
 * The UPLOAD tab's tracing options, checked against the engine that has to honour them.
 *
 * WHAT THIS FILE IS FOR. `components/sketches/upload/` offers a designer every engine parameter the
 * upstream dock offers plus the two sharpening controls its own UI never exposed — the total is
 * `PARAM_COUNT` and is asserted below rather than written out here, because this header claimed
 * "twenty-nine" while the table held thirty-two and a reader reconciling the two went looking for
 * three controls that had never been dropped. It does all of it WITHOUT importing the engine — every write is a plain patch object and
 * every enum value is a plain string, because a static import of `engine/params.ts` from a table a
 * page draws itself from would put the engine in that page's bundle (the rule is stated in
 * `lib/trace/traceClient.ts`'s header and in `.claude/skills/gsap/SKILL.md` §2).
 *
 * That decision buys the bundle and sells a guarantee: **nothing in the type system connects
 * `'ZHANG_SUEN'` in the table to `ThinningMode.ZHANG_SUEN` in the engine.** A typo would not fail to
 * compile. It would not even throw — `sanitizeTraceParams`'s `enumOf` helper answers an unrecognised
 * string with the documented default, so a misspelled option would silently select something else
 * and the panel would show a control that does nothing. That is precisely the failure this file
 * exists to make loud, and it is why the first case round-trips **every option of every choice**
 * through the real sanitiser rather than a sample.
 *
 * The same shape of argument covers `mergeParams`, which is a local copy of the merge half of the
 * engine's own `withOverrides`. A copy drifts unless something watches it, so the second case runs
 * both over every patch the table can produce and demands they agree exactly.
 *
 * WHY IT RUNS IN NODE. `-unit` means it needs nothing but node (`e2e/README.md`), and this needs
 * nothing but node: the engine's parameter module imports nothing, the SVG writer imports nothing at
 * all, and the geometry fixtures below are typed arrays this file fills in itself. No browser, no
 * server, no worker.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The table against the engine
 * ──────────────────────────────────────────────────────────────────────────── */

test("every choice option is a value the engine actually recognises", () => {
  const base = defaultTraceParams();
  for (const spec of CHOICES) {
    for (const option of spec.options) {
      const next = applyParamPatch(base, spec.patch(option.value), sanitizeTraceParams);
      // Read it back through the SPEC's own reader, so a patch that writes the right value into the
      // wrong place fails here too rather than looking correct.
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
  // The rest of `edge.flow` has to survive: a shallow spread of `edge` alone would replace the whole
  // flow object with `{ sigmaM }` and quietly reset every other flow setting to undefined-then-default.
  expect(next.edge.flow.sigmaC).toBe(base.edge.flow.sigmaC);
  expect(next.edge.sensitivity).toBe(base.edge.sensitivity);
});

test("every slider writes a value the engine keeps, across its whole declared range", () => {
  const base = defaultTraceParams();
  for (const spec of SLIDERS) {
    for (const value of [spec.min, (spec.min + spec.max) / 2, spec.max]) {
      const next = applyParamPatch(base, spec.patch(value), sanitizeTraceParams);
      const readBack = spec.read(next);
      // An integer-stepped control rounds inside its own patch, so that is what the engine should
      // have been handed and what it should hand back.
      const expected = spec.step >= 1 ? Math.round(value) : value;
      // THE TABLE'S RANGE MUST SIT INSIDE THE ENGINE'S. If it does not, the top of a slider is a lie:
      // the handle moves, the engine clamps the value back, and nothing on screen says so. This is
      // the assertion that catches a max copied from the wrong `Limits` entry.
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

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Sharpening — the thing the owner asked for that upstream never exposed
 * ──────────────────────────────────────────────────────────────────────────── */

test("the sharpening controls exist, default to off, and can reach the pipeline's gate", () => {
  const base = defaultTraceParams();
  // `engine/pipeline.ts` runs the unsharp mask only `if (p.preprocess.unsharpAmount > 0)`. The engine
  // ships with it at 0, which is why the capability was invisible: the parameter was there, the code
  // was there, and no UI could move it off zero.
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
  // Setting the radius must not have switched the amount back off.
  expect(widened.preprocess.unsharpAmount).toBeCloseTo(1.4, 5);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Table integrity
 * ──────────────────────────────────────────────────────────────────────────── */

test("the table is internally consistent", () => {
  const keys = [...SLIDERS, ...TOGGLES, ...CHOICES].map((s) => s.key);
  expect(new Set(keys).size, "two controls share a key, so one of them cannot be addressed").toBe(keys.length);

  const labels = [...SLIDERS, ...TOGGLES, ...CHOICES].map((s) => s.label);
  // `changedLabels` reports by LABEL, so two controls with one label would make a notice ambiguous
  // about which of them a preset moved.
  expect(new Set(labels).size, "two controls share a label").toBe(labels.length);

  expect(PARAM_COUNT).toBe(SLIDERS.length + TOGGLES.length + CHOICES.length);

  for (const spec of [...SLIDERS, ...TOGGLES, ...CHOICES]) {
    expect(PARAM_GROUPS, `${spec.key} is in group "${spec.group}", which the panel never renders`).toContain(
      spec.group
    );
    expect(spec.hint.length, `${spec.key} has no hint`).toBeGreaterThan(0);
    // A hint that merely restates the label teaches nothing, and the whole table was written to a
    // rule against it.
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
  // Null rather than an empty string, so a caller cannot render an empty notice box.
  expect(overwriteNotice("The “Woodcut” style", base, base)).toBeNull();

  const spec = SLIDERS.find((s) => s.key === "output.simplify")!;
  const moved = applyParamPatch(base, spec.patch(4), sanitizeTraceParams);
  const notice = overwriteNotice("The “Woodcut” style", base, moved);
  expect(notice).toContain("Simplify");
  expect(notice).toContain("one setting");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The SVG writer
 * ──────────────────────────────────────────────────────────────────────────── */

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

/** One shape with one segment of each kind, plus an optional second shape, built by hand. */
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
  // Start point, then a line (2 coords), a quad (4) and a cubic (6).
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
  // Two verbs declared, but only enough coordinates for the first. A trace interrupted mid-post is
  // the one way this happens, and half a drawing beats an exception inside a component's render.
  const geometry = geometryOf({ coords: [0, 0, 10, 0], verbs: [VERB_LINE, VERB_CUBIC], closed: 0 });
  expect(shapePathData(geometry, 0, 2)).toBe("M0 0 L10 0");
});

test("a non-finite coordinate becomes 0 rather than poisoning the file", () => {
  // The upstream writer's stated reason: "A single NaN or Infinity anywhere in an SVG attribute makes
  // the renderer drop the element that carries it — silently, and only in some renderers."
  //
  // BOTH become 0, INCLUDING Infinity — the substitution happens before the ±1e7 clamp, so an
  // infinite coordinate does NOT come out as the clamp ceiling. That ordering is `engine/svgWriter.ts`'s
  // and this writer reproduces it character for character; the two were diffed rather than assumed.
  const geometry = geometryOf({ coords: [0, 0, Number.NaN, Number.POSITIVE_INFINITY], verbs: [VERB_LINE], closed: 0 });
  const d = shapePathData(geometry, 0, 2);
  expect(d).not.toContain("NaN");
  expect(d).not.toContain("Infinity");
  expect(d).toBe("M0 0 L0 0");
});

test("a finite coordinate beyond the renderer's range is clamped, not zeroed", () => {
  // The other half of the same rule, and a different branch: a real but absurd coordinate keeps its
  // sign and direction at the ceiling, where a fixed-point renderer can still cope with it.
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
  // A run of hyphens INSIDE the comment would close it early and produce a file no parser accepts.
  // The delimiters `<!--` and `-->` legitimately contain one such run each, so the assertion has to
  // look at the comment's BODY rather than at the whole document.
  const body = /<!--([\s\S]*?)-->/.exec(result.svg)?.[1];
  expect(body, "the provenance note should be written as an XML comment").toBeTruthy();
  expect(body).not.toContain("--");
  expect(body).toContain("Traced on the device");

  // And the identity rule: a file that travels to a ministry carries no person, workshop or place.
  expect(result.svg).not.toMatch(/aadhaar|pehchan|latitude|longitude/i);
});

test("the shape ceiling is reported rather than silently applied", () => {
  // The repository's most repeated bug class is a list that quietly stops. The cap itself is not
  // exercised here — building 200,001 shapes to prove a string — but the branch that reports it is.
  const shapeCount = MAX_SHAPES_PER_FILE + 5;
  const geometry: FlatGeometry = {
    coords: new Float32Array([0, 0, 1, 1]),
    verbs: new Uint8Array([VERB_LINE]),
    // Only the first shape has a real run; the rest are empty, which is enough to make the count
    // large without allocating a million coordinates in a unit test.
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

/* ────────────────────────────────────────────────────────────────────────────
 * 5. Naming and sizing
 * ──────────────────────────────────────────────────────────────────────────── */

test("the derived file is named after the photograph it came from", () => {
  expect(derivedFileName("IMG_20260822_104512.jpg", "svg")).toBe("IMG_20260822_104512-line-art.svg");
  expect(derivedFileName("bagru block sketch.jpeg", "png")).toBe("bagru block sketch-line-art.png");
  // Punctuation a phone gallery allows but an S3 key or a .docx relationship id does not. Only word
  // characters, hyphen, dot and space survive — brackets and slashes included, and a RUN of them
  // collapses to a single underscore rather than one per character.
  expect(derivedFileName("sketch#1 (final)/v2.png", "svg")).toBe("sketch_1 _final_v2-line-art.svg");
  expect(derivedFileName("", "svg")).toBe("sketch-line-art.svg");
  expect(derivedFileName(".jpg", "svg")).toBe("sketch-line-art.svg");
  expect(derivedFileName(`${"x".repeat(200)}.jpg`, "svg").length).toBeLessThanOrEqual(80 + "-line-art.svg".length);
});

test("the three artefacts one photograph produces cannot end up sharing a name", () => {
  // THE FAILURE THIS GUARDS. The panel now makes three files from one photograph: the plate it
  // attaches, the vector trace downloaded to the device, and the rendered raster downloaded to the
  // device. Two of the three are PNGs of the same drawing, so if the render ever picked up the attach's
  // suffix, a designer's downloads folder would hold two different `…-line-art.png` files — one the
  // record's plate and one a display render — with nothing but the byte count to tell them apart.
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
  // The attach's own name is unchanged by the suffix becoming a parameter — this is the name the
  // record already holds and `sketch-trace-panel.spec.ts` asserts on the attached file.
  expect(derivedFileName(source, "svg")).toBe(derivedFileName(source, "svg", ATTACH_SUFFIX));

  // The suffix goes through the same deny-list as the stem, so a caller cannot smuggle a path
  // separator into a filename through the one argument that looks like a constant.
  expect(derivedFileName("sheet.jpg", "png", "cropped/2")).toBe("sheet-cropped_2.png");
  // …and an empty suffix asks for the stem itself rather than leaving a dangling hyphen.
  expect(derivedFileName("sheet.jpg", "png", "")).toBe("sheet.png");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5b. The comparison plate's downscale
 *
 * WHY IT IS TESTED HERE AND NOT IN THE BROWSER SPEC. `resampleRgba` is the only arithmetic in
 * `comparisonPlates.ts` — the rest is canvas plumbing — and it is the part that decides whether the
 * photograph a designer compares a trace against is a fair reduction of the photograph the engine
 * traced or an aliased mess that invents faults. Straight loops over typed arrays, no DOM, so Node.
 * ──────────────────────────────────────────────────────────────────────────── */

/** A w×h RGBA plane, `paint` deciding each pixel's four bytes. */
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
  // A one-pixel checkerboard. NEAREST-NEIGHBOUR would return all black or all white depending on which
  // pixel it happened to land on — which is exactly how a photograph of a pencil sketch turns into a
  // dotted mess and makes the trace beside it look wrong. A box filter returns the mean: 127-ish.
  const checker = plane(8, 8, (x, y) => {
    const on = (x + y) % 2 === 0 ? 255 : 0;
    return [on, on, on, 255];
  });
  const half = resampleRgba(checker, 8, 8, 4, 4);
  expect(half.length).toBe(4 * 4 * 4);
  for (let i = 0; i < half.length; i += 4) {
    expect(Math.abs(half[i] - 127)).toBeLessThanOrEqual(2);
    // Alpha is averaged with everything else, and an opaque source must stay opaque.
    expect(half[i + 3]).toBe(255);
  }

  // Every source pixel is read exactly once: the boxes tile the source. A left half of pure red and a
  // right half of pure blue must come back as the same halves, not as a purple smear or a shifted edge.
  const halves = plane(8, 4, (x) => (x < 4 ? [255, 0, 0, 255] : [0, 0, 255, 255]));
  const shrunk = resampleRgba(halves, 8, 4, 4, 2);
  expect([shrunk[0], shrunk[1], shrunk[2]]).toEqual([255, 0, 0]);
  const rightmost = (0 * 4 + 3) * 4;
  expect([shrunk[rightmost], shrunk[rightmost + 1], shrunk[rightmost + 2]]).toEqual([0, 0, 255]);

  // ASKING FOR MORE PIXELS THAN THERE ARE GETS THE SOURCE, not an interpolated enlargement. The cap
  // can only ever shrink — the same rule `workingSizeFor` holds, and the reason the plates of a small
  // sketch are not blown up to 1024 to be blurry.
  const same = resampleRgba(halves, 8, 4, 40, 20);
  expect(same.length).toBe(8 * 4 * 4);
  expect(Array.from(same)).toEqual(Array.from(halves));

  // A degenerate request cannot produce a zero-length plane, which `createImageData` would throw on.
  expect(resampleRgba(halves, 8, 4, 0, 0).length).toBe(4);
});

test("the banded downscale is byte-identical to the synchronous one, and stops when told to", async () => {
  /*
    WHY THERE ARE TWO ENTRY POINTS AT ALL. Both callers run this over the WHOLE decode — up to 4096px on
    its long edge, so 16.7 million source pixels read once each — on the page thread, once per settled
    trace and once per chosen photograph. That is a long task, which is a frozen tab rather than a slow
    one; `resampleRgbaInBands` does the identical arithmetic in bands and lets go of the thread between
    them. "Identical" is the whole claim, and this is what holds it: a band boundary that lost or
    double-counted a source row would show up as a seam in the comparison plate, which reads as a fault
    in the trace beside it rather than as a fault in a downscale nobody is looking at.
  */
  /*
    SIZED FROM `BAND_SOURCE_PIXELS` RATHER THAN HARD-CODED, so this really does cross band boundaries
    however that budget is retuned: a source of more than one band's worth of pixels reduced to eight
    destination rows is at least two bands, by the arithmetic in `resampleRgbaInBands` itself. A
    hard-coded 37x29 fits in one band and would assert nothing about the seams.
  */
  const edge = Math.ceil(Math.sqrt(BAND_SOURCE_PIXELS + 1));
  const rows = 8;
  const photograph = plane(edge, edge, (x, y) => [(x * 7) % 256, (y * 11) % 256, (x + y) % 256, 255]);

  const oneShot = resampleRgba(photograph, edge, edge, 11, rows);
  const banded = await resampleRgbaInBands(photograph, edge, edge, 11, rows);
  expect(banded).not.toBeNull();
  // BYTE FOR BYTE. A band boundary that lost a source row, or read one twice, is a seam across the
  // comparison plate — which reads as a fault in the trace beside it, not in a downscale.
  expect(Array.from(banded ?? [])).toEqual(Array.from(oneShot));

  // The pass-through branch — asking for at least the source size — is not special-cased into a
  // different answer by one route than by the other.
  const small = plane(6, 4, (x) => [x * 10, 0, 0, 255]);
  const whole = await resampleRgbaInBands(small, 6, 4, 60, 40);
  expect(Array.from(whole ?? [])).toEqual(Array.from(small));

  // A CALLER THAT HAS LOST INTEREST GETS `null` AND PAYS FOR NOTHING. `buildComparisonPlates` passes a
  // check that goes true when a newer trace has settled; without it a slider drag pays for every
  // superseded comparison in full, on the thread it is being dragged on.
  expect(await resampleRgbaInBands(photograph, edge, edge, 11, rows, () => true)).toBeNull();

  // …and a check that never fires cannot change the answer.
  const unstopped = await resampleRgbaInBands(photograph, edge, edge, 11, rows, () => false);
  expect(Array.from(unstopped ?? [])).toEqual(Array.from(oneShot));
});

test("the decode cap only ever shrinks, and preserves the aspect ratio", () => {
  // Already inside the cap: returned untouched, so a scanned A4 is not resampled for nothing.
  expect(workingSizeFor(2480, 3508, 4096)).toEqual({ width: 2480, height: 3508 });

  const reduced = workingSizeFor(8000, 6000, 4096);
  expect(Math.max(reduced.width, reduced.height)).toBe(4096);
  expect(reduced.width / reduced.height).toBeCloseTo(8000 / 6000, 3);

  // A panorama must not collapse to zero on its short edge.
  const extreme = workingSizeFor(12000, 3, 4096);
  expect(extreme.height).toBeGreaterThanOrEqual(1);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. The guarantee the whole panel rests on
 * ──────────────────────────────────────────────────────────────────────────── */

test("a full round trip: defaults, a style-shaped override, and a hand edit on top", () => {
  const base = defaultTraceParams();

  // A style is a complete tree. This stands in for one without loading `engine/styles.ts`, so the
  // case tests the composition rule rather than any particular preset's table.
  const styleLike: TraceParams = withOverrides(base, {
    output: { vectorMode: base.output.vectorMode, simplify: 2.5, strokeWidth: 0.8 },
    edge: { sensitivity: 0.72 }
  });

  const sharpen = SLIDERS.find((s) => s.key === "preprocess.unsharpAmount")!;
  const edited = applyParamPatch(styleLike, sharpen.patch(2), sanitizeTraceParams);

  // The hand edit lands...
  expect(edited.preprocess.unsharpAmount).toBeCloseTo(2, 5);
  // ...and nothing the style set is disturbed by it.
  expect(edited.output.simplify).toBeCloseTo(2.5, 5);
  expect(edited.output.strokeWidth).toBeCloseTo(0.8, 5);
  expect(edited.edge.sensitivity).toBeCloseTo(0.72, 5);

  // And the panel can say exactly what the designer changed away from the style.
  expect(changedLabels(styleLike, edited)).toEqual(["Sharpen amount"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 7. One sentence for "the engine did not arrive"
 * ──────────────────────────────────────────────────────────────────────────── */

test("the sentence a failed engine load shows is the one traceClient already owns", () => {
  // WHY THIS IS ASSERTED ON SOURCE. `traceRuntime.loadTraceRuntime` wraps its two dynamic imports so
  // that a dropped chunk request reaches the panel as a sentence rather than as "Failed to fetch
  // dynamically imported module: …/chunk-<hash>.js" — which is what `SketchTraceField` renders
  // verbatim, because the message is all it has. The words are `traceClient`'s own, and `traceClient`
  // is not this unit's file: if somebody rewords it there, the two screens start disagreeing about
  // one fault. Nothing else can notice that, so this does — and it needs no browser to do it.
  const sentence = "The tracing engine could not be loaded. Check your connection and reload the page.";
  const client = readFileSync(join(__dirname, "..", "lib", "trace", "traceClient.ts"), "utf8");
  const runtime = readFileSync(join(__dirname, "..", "components", "sketches", "upload", "traceRuntime.ts"), "utf8");
  expect(client).toContain(sentence);
  expect(runtime).toContain(sentence);
});
