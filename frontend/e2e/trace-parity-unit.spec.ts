import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

import { expect, test } from "@playwright/test";

import { Params, SvgPathData, VectorModeParam, defaultTraceParams, sanitizeTraceParams } from "@/lib/trace/engine";
import { buildSvg, shapePathData, type FlatGeometry } from "@/components/sketches/upload/geometryToSvg";

import {
  CANDIDATE_DIR,
  PARITY_CASES,
  REFERENCE_DIR,
  candidatePath,
  engineManifestSha256,
  hasCandidates,
  loadFixture,
  loadFixtureManifest,
  loadParamSets,
  referencePath
} from "./support/traceParity";
import { runParityCase } from "./support/traceParityRun";
import {
  COORD_EPSILON_FLOOR_PX,
  base64ToFloat32,
  compareTraceRecords,
  coordTolerance,
  explainVerdict,
  float32ToBase64,
  hexToBytes,
  type TraceRecord
} from "./support/traceRecord";

/**
 * ════════════════════════════════════════════════════════════════════════════
 * THE PARITY HARNESS — the test that has to exist before "run the same engine on the handset" is an
 * argument rather than an intention.
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ## What is being defended
 *
 * Stage 11 lets a designer photograph a paper sketch and TRACE it into vector line art that reaches a
 * report submitted to a ministry. The web can trace; the handset cannot; and the product's premise is
 * a designer working offline in a village for a fortnight, so the gap is on the client that matters
 * most.
 *
 * The proposal is to run the SAME vendored engine on Android rather than to port it. `lib/trace/`
 * is 46 files copied verbatim out of `D:/Offline-Tracer` with a SHA-256 per file, and its
 * README's whole argument is that `diff -r` against the upstream must stay meaningful. A hand-written
 * Kotlin port would fork a 16,557-line numerical library into a second language — Otsu, Canny,
 * Schneider fitting, thinning, morphology — where two independent implementations will not agree, and
 * the manifest could not detect the divergence because it is a different language.
 *
 * "Run the same engine" is only worth more than "port it" if the two runtimes can be SHOWN to draw
 * the same thing. This file is that showing. Without it the two positions are both assertions and the
 * cheaper one wins.
 *
 * ## What it compares, and what it deliberately does not
 *
 * NOT a pixel diff of a render. Two SVG rasterisers disagree about antialiasing on every curve, so a
 * pixel diff fails for reasons that do not matter — and, worse, a hairline stroke that vanished
 * entirely on one side could pass one. The comparison is on the VECTOR DOCUMENT, in three tiers, and
 * `support/traceRecord.ts` holds both the comparator and the arithmetic behind its tolerance:
 *
 *   TIER 0  structure — shape count, verb sequence, closed flags, styles, notes.  EXACT.
 *   TIER 1  geometry  — every control point.                      1e-3 px + 4 float32 ulps.
 *   TIER 2  the printed drawing — the SVG file, byte for byte.    EXACT, reported before it gates.
 *
 * Read `coordTolerance`'s note in that file before arguing with the epsilon. The short version: 1e-3
 * px is five orders of magnitude below the ~0.5 px that would visibly move a stroke on the page, and
 * a hair under half of the 0.01 px that is one unit in the last place of the printed file. It is not
 * a number chosen to make a run go green, and widening it is not a way to fix a failure.
 *
 * ## The corpus, and why it is committed bytes rather than drawn here
 *
 * `e2e/trace-engine-unit.spec.ts` — the existing oracle, and the file whose fixtures and idioms this
 * one reuses — draws its own bitmaps, and it is right to: it asserts properties that follow from the
 * drawing. A PARITY spec cannot, because the drawing would then have two implementations, one per
 * runtime, and a bug in the second generator would read exactly like a bug in the engine. That is the
 * same argument this wave makes against porting the engine, and it applies to forty lines of fixture
 * generator too.
 *
 * So the fixtures are committed as raw RGBA, gzipped, hashed by their DECOMPRESSED bytes. No image
 * decoder is in the loop on either side — see `fixtures/trace-parity/make-fixtures.mjs`, which also
 * explains why a PNG would have been the wrong container. The tie back to the oracle is kept by
 * assertion: the disc and blank fixtures are proved below to be byte-for-byte what that spec's own
 * `disc()` and `blankSheet()` draw.
 *
 * ## Why the goldens are not circular
 *
 * The oracle's header names the hazard exactly: "a golden captured from the engine's own output would
 * go green against an engine that had started returning a constant". The reference records here ARE
 * captures, so that hazard is real, and it is closed rather than ignored: the disc and blank cases
 * carry the oracle's own arithmetic assertions — one closed outline on the disc's own bounding box,
 * no paths and a sentence for a blank sheet — which a constant-returning engine fails no matter how
 * perfectly it matches its own capture.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * THE ANDROID CONTRACT — what the other half must do, precisely
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The runtime verdict has not landed. This contract is written so it does not have to have: it says
 * what to call, not what to call it from.
 *
 * For every entry of `PARITY_CASES`, the Android half must:
 *
 *   1. Read `<caseId's fixture>.rgba.gz` from its own assets. Ship it as `.rgba.gz` and not as an
 *      image — AAPT crunches PNGs and re-encodes them, which would change the input silently.
 *   2. GUNZIP it and VERIFY the SHA-256 of the payload against `MANIFEST.txt` BEFORE tracing.
 *      A packaging step that alters a byte must fail here, loudly, rather than become a geometry
 *      difference nobody can explain.
 *   3. Parse the `RGBA8 <w> <h>\n` header and hand the payload straight to
 *      `RgbaImage.fromImageData({ data, width, height })`. No `BitmapFactory`, no canvas, no resize.
 *   4. Read the SAME `params.json` and pass the SAME partial tree to `runParityCase`, which
 *      sanitises it. Do not hard-code a parameter tree on the Android side; a default that drifted
 *      on one side would present as a geometry difference.
 *   5. Call `runParityCase` from `e2e/support/traceParityRun.ts` — **the same module, not a
 *      translation of it** — with `runtime: "android-<whatever the spike selected>"`.
 *   6. `JSON.stringify` the returned record and write it to a file named by `recordFileName(caseId)`.
 *   7. Pull the whole directory to `frontend/e2e/fixtures/trace-parity/candidate/` and run this spec.
 *      Every case then compares, in addition to everything it already asserts.
 *
 * The one thing the Android half may reimplement is the SHA-256 (`MessageDigest`) and the file I/O.
 * Everything that decides what a record CONTAINS is shared source, on purpose: a harness whose two
 * halves are two implementations is measuring itself.
 *
 * IF THE VERDICT IS THAT THE HANDSET RUNS A DIFFERENT LANGUAGE'S ENGINE — the upstream's own Kotlin
 * one, which the vendored comments prove exists (`engine/buffers.ts:3` names
 * `android/core-imaging/.../Buffers.kt`; `worker/unthrottledTimers.ts:36` says the engine "is shared
 * with the Kotlin client and is held to bit-for-bit parity against `docs/fixtures/*.json`") — then
 * steps 5 and 6 are the only ones that change: `runParityCase` and `buildTraceRecord` have to be
 * written once in Kotlin, about 120 lines between them, walking layers → shapes → segments in the
 * same order. That is a reimplementation of the HARNESS and not of the engine, it is small enough to
 * read in one sitting, and this file is exactly the instrument that would show it wrong.
 */

const CAPTURE = process.env.TRACE_PARITY_CAPTURE === "1";

/* ════════════════════════════════════════════════════════════════════════════
 * The corpus is what it says it is
 * ════════════════════════════════════════════════════════════════════════════ */

test("every fixture matches the hash MANIFEST.txt records for it", () => {
  const manifest = loadFixtureManifest();
  const named = new Set(PARITY_CASES.map((c) => c.fixture));
  expect(named.size, "every fixture in the manifest should be used by a case, and vice versa").toBe(manifest.size);

  for (const name of named) {
    const declared = manifest.get(name);
    expect(declared, `${name} is used by a case but is not in MANIFEST.txt`).toBeDefined();
    const fixture = loadFixture(name);
    // The hash is of the DECOMPRESSED payload, never of the .gz: DEFLATE compression is not
    // reproducible between implementations, so hashing the compressed file would go red for a reason
    // that has nothing to do with the pixels. `engine/pngEncoder.ts:19-22` draws the same line.
    expect(fixture.sha256, `${name}: the bytes on disk are not the bytes MANIFEST.txt names`).toBe(declared!.sha256);
    expect([fixture.width, fixture.height]).toEqual([declared!.width, declared!.height]);
    expect(fixture.data.length).toBe(declared!.bytes);
  }
});

/**
 * Redrawn here from `e2e/trace-engine-unit.spec.ts`, and the point of the case below is that the two
 * cannot drift. If somebody changes the radius there, the fixture stops matching and this fails —
 * which is far better than two files quietly tracing two different discs and calling both "the disc".
 */
function disc(w: number, h: number): Uint8ClampedArray {
  const data = new Uint8ClampedArray(w * h * 4);
  data.fill(255);
  const cx = w / 2;
  const cy = h / 2;
  const r = Math.min(w, h) * 0.3;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const dx = x + 0.5 - cx;
      const dy = y + 0.5 - cy;
      if (dx * dx + dy * dy <= r * r) {
        const i = (y * w + x) * 4;
        data[i] = 0;
        data[i + 1] = 0;
        data[i + 2] = 0;
      }
    }
  }
  return data;
}

/** Where a {@link disc} of the given size sits: `[minX, minY, maxX, maxY]`. The oracle's own helper. */
function discBounds(w: number, h: number): [number, number, number, number] {
  const r = Math.min(w, h) * 0.3;
  return [w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r];
}

test("the disc and blank fixtures are the oracle's own bitmaps, byte for byte", () => {
  for (const [name, w, h] of [
    ["disc-256x192", 256, 192],
    ["disc-1024x768", 1024, 768]
  ] as const) {
    expect(Buffer.from(loadFixture(name).data), `${name} is no longer the disc trace-engine-unit draws`).toEqual(
      Buffer.from(disc(w, h))
    );
  }
  const blank = loadFixture("blank-256x192");
  expect(blank.data.every((b) => b === 255), "blank-256x192 should be opaque white in every byte").toBe(true);
});

test("params.json really sanitises to what it claims, rather than silently defaulting", () => {
  const sets = loadParamSets();
  expect([...sets.keys()].sort()).toEqual(["default", "outline"]);

  // THE TRAP. `sanitizeTraceParams`'s `enumOf` answers an unrecognised string with the documented
  // default instead of throwing, so `"OUTLNE"` in params.json would trace CENTERLINE in silence and
  // the corpus would stop covering outline mode without a single test going red.
  const outline = sanitizeTraceParams(sets.get("outline")!);
  expect(outline.output.vectorMode).toBe(VectorModeParam.OUTLINE);
  expect(outline.output.fillClosed).toBe(true);
  expect(outline.auto.mode).toBe(Params.AutoMode.OFF);

  // The `default` set is empty and must stay exactly the engine's own defaults — the tree a
  // designer's trace actually runs with. If it ever needs an override, it is no longer "default".
  expect(sanitizeTraceParams(sets.get("default")!)).toEqual(defaultTraceParams());
  expect(defaultTraceParams().output.vectorMode).toBe(VectorModeParam.CENTERLINE);
  expect(defaultTraceParams().auto.mode).toBe(Params.AutoMode.SUGGEST);
});

/* ════════════════════════════════════════════════════════════════════════════
 * The comparator, before it is trusted to judge anything
 * ════════════════════════════════════════════════════════════════════════════ */

function loadReference(caseId: string): TraceRecord {
  return JSON.parse(readFileSync(referencePath(caseId), "utf8")) as TraceRecord;
}

test("the coordinate transport is exact, so a Tier 1 delta is never the record's own rounding", () => {
  // Every reference record's coordinates survive a base64 round-trip bit for bit. This is not
  // decoration: if the transport lost a bit, the harness would report a divergence between two
  // runtimes that had computed identical numbers, and the first response to that would be to widen
  // the epsilon — which would then hide a real one.
  for (const c of PARITY_CASES) {
    const values = base64ToFloat32(loadReference(c.caseId).coordsB64);
    const again = base64ToFloat32(float32ToBase64(values));
    expect(again.length, c.caseId).toBe(values.length);
    for (let i = 0; i < values.length; i++) {
      expect(Object.is(again[i], values[i]), `${c.caseId} coord ${i}`).toBe(true);
    }
  }
});

test("the tolerance is the stated arithmetic and nothing looser", () => {
  expect(COORD_EPSILON_FLOOR_PX).toBe(1e-3);
  // 1e-3 + 4 * max(|x|, 1) * 2^-23, written out at the four magnitudes the note reasons about — so a
  // well-meant edit to the formula has to come back here and restate what it now permits, in px.
  //
  // The `max(|x|, 1)` matters at the origin: a coordinate of 0 is exact in float32 and has no ulp of
  // its own, so the floor carries it and a neighbouring coordinate's ulp is the honest allowance.
  expect(coordTolerance(0)).toBeCloseTo(1.000_476_8e-3, 9);
  expect(coordTolerance(256)).toBeCloseTo(1.122_070_3e-3, 9);
  expect(coordTolerance(1024)).toBeCloseTo(1.488_281_2e-3, 9);
  // 4096 is DECODE_MAX_EDGE_PX — the largest coordinate this product can produce.
  expect(coordTolerance(4096)).toBeCloseTo(2.953_125_0e-3, 9);
  // And it must stay below half of one printed unit at precision 2, or a difference inside tolerance
  // could change the file by more than one digit in the last place.
  expect(coordTolerance(4096)).toBeLessThan(0.005);
  expect(coordTolerance(-1024)).toBe(coordTolerance(1024));
});

test("the comparator passes a record against itself and fails the differences that matter", () => {
  const reference = loadReference("sketch-photo-400x300/outline");

  const identical = compareTraceRecords(reference, reference);
  expect(identical.pass, explainVerdict(identical)).toBe(true);
  expect(identical.printed.equal).toBe(true);
  expect(identical.geometry.worst?.delta).toBe(0);

  const nudged = (multiple: number): TraceRecord => {
    const coords = base64ToFloat32(reference.coordsB64).slice();
    coords[4] = Math.fround(coords[4] + coordTolerance(coords[4]) * multiple);
    return { ...reference, coordsB64: float32ToBase64(coords) };
  };
  // Inside tolerance: passes, and still REPORTS the move, so a run that is quietly drifting towards
  // the limit is visible before it crosses it.
  const inside = compareTraceRecords(reference, nudged(0.4));
  expect(inside.pass).toBe(true);
  expect(inside.geometry.worst!.delta).toBeGreaterThan(0);
  // Outside: fails, and the message names the shape, the coordinate and the axis.
  const outside = compareTraceRecords(reference, nudged(40));
  expect(outside.pass).toBe(false);
  expect(outside.geometry.overTolerance[0].shape).toBe(0);
  expect(outside.geometry.overTolerance[0].axis).toBe("x");

  // Tier 0 has no tolerance, and each of these is a different way for a drawing to be wrong.
  const verbFlipped = compareTraceRecords(reference, { ...reference, verbsHex: `00${reference.verbsHex.slice(2)}` });
  expect(verbFlipped.pass).toBe(false);
  expect(verbFlipped.structure[0]).toContain("verb sequence differs");

  const shapeLost = compareTraceRecords(reference, { ...reference, shapeCount: reference.shapeCount - 1 });
  expect(shapeLost.pass).toBe(false);

  const noteChanged = compareTraceRecords(reference, { ...reference, notes: ["something else"] });
  expect(noteChanged.pass).toBe(false);

  // A record produced from a different input, or by a different vendored copy, is not a parity
  // result at all — and the comparator has to say that rather than report a large delta.
  const otherInput = compareTraceRecords(reference, { ...reference, inputSha256: "0".repeat(64) });
  expect(otherInput.structure.join(" ")).toContain("did not trace the same image");
  const otherEngine = compareTraceRecords(reference, { ...reference, engineManifestSha256: "0".repeat(64) });
  expect(otherEngine.structure.join(" ")).toContain("different vendored copy");
});

/* ════════════════════════════════════════════════════════════════════════════
 * The record is complete: the drawing can be rebuilt from it
 * ════════════════════════════════════════════════════════════════════════════ */

/** Rehydrates the flat arrays `buildSvg` wants from a record. */
function flatGeometryOf(record: TraceRecord): FlatGeometry {
  return {
    coords: base64ToFloat32(record.coordsB64),
    verbs: hexToBytes(record.verbsHex),
    verbStarts: Uint32Array.from(record.verbStarts),
    coordStarts: Uint32Array.from(record.coordStarts),
    closed: Uint8Array.from([...record.closedBits].map((b) => (b === "1" ? 1 : 0))),
    styleTable: record.styles,
    styleIndex: Uint32Array.from(record.styleIndex)
  };
}

test("the product's own SVG writer and the engine's describe the same paths, so Tier 2 is a fair proxy", () => {
  /*
   * Tier 2 records `SvgWriter.write` because it is vendored and therefore pinned by
   * UPSTREAM-MANIFEST.txt, while the file that actually reaches `sketch.lineArtFile` is written by
   * `components/sketches/upload/geometryToSvg.ts` — a component-side module that is edited often.
   * That choice is only sound if the two writers put the same points in the same order, and
   * `geometryToSvg.ts`'s own header admits the risk in its last line: "Two writers is one more than
   * the right number."
   *
   * THEY DO NOT SPELL IT THE SAME, AND THAT IS MEASURED HERE RATHER THAN ASSUMED. Checked
   * 2026-08-27 on `sketch-photo-400x300/outline`: the two `d` strings describe an identical path and
   * differ in three ways, all of them `engine/svgPathData.ts:29-44`'s stated rules, which
   * `geometryToSvg.shapePathData` does not follow —
   *
   *     buildSvg    M7.25 1 C7.83 1.08 8.42 1.17 9 1.25 C9 100.08 9 198.92 9 297.75 C8.33 … 7.25 1 Z
   *     SvgWriter   M7.25 1C7.83 1.08 8.42 1.17 9 1.25 9 100.08 9 198.92 9 297.75 8.33 … 7.25 1Z
   *
   *   - a space after the command letter, which `toD` omits because "the letter is already a delimiter";
   *   - an explicit `C` on every cubic, where `toD` elides the letter for a run of one type;
   *   - a space before `Z`.
   *
   * None of it changes the drawing, and none of it varies between RUNTIMES — both writers are the
   * same code on both sides — so the proxy holds exactly where it needs to: a coordinate that moved
   * between two runtimes moves identically in both files, because both print through the same
   * fixed-point `num` at precision 2. What it does mean is that the product's exported file is about
   * an eighth larger than the engine's own (`svgPathData.ts:35-39` measures the elisions at "roughly
   * one character in eight of the largest thing this app writes") and will not diff cleanly against
   * an SVG optimiser's output. That is a note for whoever owns `components/sketches/upload/`, not a
   * change to make from here.
   *
   * So the assertion is on the PATHS, parsed back by the engine's own reader — which also proves the
   * record is not lossy, since the whole drawing is rebuilt from the record alone.
   */
  const record = loadReference("sketch-photo-400x300/outline");
  const geometry = flatGeometryOf(record);
  const rebuilt = buildSvg(
    { geometry, width: record.docWidth, height: record.docHeight, background: record.background },
    { precision: 2 }
  );
  expect(rebuilt.shapesWritten).toBe(record.shapeCount);
  expect(rebuilt.truncationNote).toBeNull();

  const engineDs = [...record.svg.matchAll(/ d="([^"]*)"/g)].map((m) => m[1]);
  expect(engineDs, "the reference SVG should carry one d per shape").toHaveLength(record.shapeCount);
  for (let i = 0; i < record.shapeCount; i++) {
    const fromProduct = SvgPathData.parse(shapePathData(geometry, i, 2));
    const fromEngine = SvgPathData.parse(engineDs[i]);
    expect(fromProduct, `shape ${i}: one subpath from each writer`).toHaveLength(1);
    expect(fromEngine).toHaveLength(1);
    expect(fromProduct[0].start, `shape ${i}: the two writers disagree about the start point`).toEqual(
      fromEngine[0].start
    );
    expect(fromProduct[0].closed).toBe(fromEngine[0].closed);
    expect(fromProduct[0].segments, `shape ${i}: the two writers describe different geometry`).toEqual(
      fromEngine[0].segments
    );
  }
});

/* ════════════════════════════════════════════════════════════════════════════
 * Every case: run it, hold it to the arithmetic where there is any, compare it
 * ════════════════════════════════════════════════════════════════════════════ */

const printed: string[] = [];

for (const parityCase of PARITY_CASES) {
  test(`parity: ${parityCase.caseId}`, async () => {
    const fixture = loadFixture(parityCase.fixture);
    const paramSets = loadParamSets();
    const { record, params } = await runParityCase({
      caseId: parityCase.caseId,
      runtime: "web-node",
      pixels: fixture.data,
      width: fixture.width,
      height: fixture.height,
      inputSha256: fixture.sha256,
      engineManifestSha256: engineManifestSha256(),
      params: paramSets.get(parityCase.params)!,
      previewLongEdge: parityCase.previewLongEdge
    });

    /* ── THE NON-CIRCULAR ANCHOR ──────────────────────────────────────────
     * These are the oracle's own assertions, and they are what stops the reference records below
     * from being a golden that agrees with a broken engine. They follow from the DRAWING, so an
     * engine returning a constant fails them however perfectly it matches its own capture.
     * ─────────────────────────────────────────────────────────────────── */
    if (parityCase.fixture === "disc-256x192" && parityCase.params === "outline") {
      expect(record.shapeCount, "a disc has one boundary, and flat white has none").toBe(1);
      expect(record.closedBits).toBe("1");
      expect([record.docWidth, record.docHeight]).toEqual([256, 192]);
      // The oracle measured a worst edge of 1.33 px on 2026-08-22 and allowed 3 — "the inward bias
      // of simplify and curve fitting on a boundary that is anti-aliased over one pixel". Re-measured
      // through the record on 2026-08-27: 1.326 px, the same number to two decimals, which is what
      // says the recorded bounds really are `VecDocument.bounds()`. The same allowance is right here
      // for the same reason: room for the fitter to move, none for the outline to be a different shape.
      expectBoundsNear(record, discBounds(256, 192), 3);
    }
    if (parityCase.fixture === "disc-1024x768") {
      // A preview traced at 256 and scaled back up: every error multiplied by four. The oracle
      // measured 4.91 px and allowed 8; re-measured here on 2026-08-27 at 4.907 px.
      expect(record.preview).toBe(true);
      expect([record.docWidth, record.docHeight]).toEqual([1024, 768]);
      expect(record.notes[0]).toMatch(/^Preview at 256x192\./);
      expectBoundsNear(record, discBounds(1024, 768), 8);
    }
    if (parityCase.fixture === "blank-256x192") {
      // "No paths" with no explanation is indistinguishable to a designer from a trace that failed.
      expect(record.shapeCount).toBe(0);
      expect(record.nodeCount).toBe(0);
      expect(record.notes.join(" ")).toContain("No paths were produced");
    }
    if (parityCase.fixture === "one-pixel-1x1" || parityCase.fixture === "hairline-2048x3") {
      // The engine's contract is that it never throws for a legal image. Getting here at all is the
      // assertion; a record with a sentence in it is the shape of the right answer.
      expect(record.docWidth).toBe(fixture.width);
      expect(record.docHeight).toBe(fixture.height);
    }

    // The trace ran with the parameters the case names — not with a tree `enumOf` quietly defaulted.
    const expectedMode = parityCase.params === "outline" ? VectorModeParam.OUTLINE : VectorModeParam.CENTERLINE;
    expect(params.output.vectorMode).toBe(expectedMode);

    if (CAPTURE) {
      mkdirSync(REFERENCE_DIR, { recursive: true });
      writeFileSync(referencePath(parityCase.caseId), `${JSON.stringify(record, null, 2)}\n`, "utf8");
      test.info().annotations.push({ type: "captured", description: referencePath(parityCase.caseId) });
      return;
    }

    /* ── SELF-PARITY ──────────────────────────────────────────────────────
     * The web against its own committed capture. On an unchanged engine this is exact in all three
     * tiers, and that is worth asserting for its own sake: it proves the record carries NOTHING
     * non-deterministic (the four `Date.now()` reads in `engine/pipeline.ts` are deliberately left
     * out — see `traceRecord.ts`), which is the precondition for the cross-runtime comparison
     * meaning anything at all. It is also the alarm that fires when the vendored copy is updated.
     * ─────────────────────────────────────────────────────────────────── */
    const reference = loadReference(parityCase.caseId);
    const self = compareTraceRecords(reference, record);
    expect(
      self.pass,
      `${explainVerdict(self)}\n\nIf lib/trace was updated on purpose, recapture:\n` +
        `  TRACE_PARITY_CAPTURE=1 npx playwright test trace-parity-unit\n` +
        `and say in the commit which upstream copy the new records were taken against.`
    ).toBe(true);
    expect(self.printed.equal, "the web engine no longer prints the same SVG it did when captured").toBe(true);

    /* ── CROSS-RUNTIME ────────────────────────────────────────────────────
     * Present only once somebody has run the Android half and pulled its records in. Absent, the
     * case still did everything above, so this spec is useful on the day it is written rather than
     * on the day the handset can trace.
     * ─────────────────────────────────────────────────────────────────── */
    const candidate = candidatePath(parityCase.caseId);
    if (!existsSync(candidate)) {
      test.info().annotations.push({
        type: "no candidate",
        description: `nothing at ${candidate} — the Android half has not been run for this case`
      });
      return;
    }
    const other = JSON.parse(readFileSync(candidate, "utf8")) as TraceRecord;
    const verdict = compareTraceRecords(reference, other);
    printed.push(
      `${parityCase.caseId} [${other.runtime}] tier2=${verdict.printed.equal ? "identical" : "DIFFERS"} ` +
        `worst=${verdict.geometry.worst ? verdict.geometry.worst.delta.toExponential(2) : "n/a"}px`
    );
    expect(verdict.pass, explainVerdict(verdict)).toBe(true);
  });
}

/**
 * Asserts a record's `VecDocument.bounds()` matches a drawn box, edge by edge, so a failure names
 * the edge that moved.
 *
 * This is `trace-engine-unit.spec.ts`'s own `expectBoundsNear`, reading the recorded bounds instead
 * of calling `document.bounds()` — which is exactly why the record carries them. Recomputing the box
 * here from the control points would be wrong and was tried first: three fitted cubics enclose a
 * circle in a control polygon 25 px wider than the circle, and their ANCHORS enclose a box 55 px
 * narrower than it. Only the engine's cubic-extrema solve gives the drawing's real extent, and
 * reimplementing that in the harness would duplicate the one thing this whole exercise is arguing
 * against duplicating.
 */
function expectBoundsNear(
  record: TraceRecord,
  expected: readonly [number, number, number, number],
  tolerancePx: number
): void {
  const actual = base64ToFloat32(record.boundsB64);
  const edges = ["left", "top", "right", "bottom"] as const;
  for (let i = 0; i < 4; i++) {
    expect(
      Math.abs(actual[i] - expected[i]),
      `${record.caseId} ${edges[i]} edge: traced ${actual[i].toFixed(2)}, drawn ${expected[i].toFixed(2)}`
    ).toBeLessThanOrEqual(tolerancePx);
  }
}

/**
 * The Tier 2 summary, printed once.
 *
 * It runs last because Playwright executes a file's tests in declaration order, and it exists because
 * Tier 2 is REPORTED before it gates. `traceRecord.ts`'s `comparePrinted` note says why: ECMAScript
 * leaves `Math.exp`, `Math.log`, `Math.pow`, `Math.sin`, `Math.cos`, `Math.atan2` and `Math.cbrt`
 * implementation-approximated, this engine uses all of them, and `convolve.gaussianKernel`'s `exp` is
 * upstream of essentially every trace. V8 carries its own fdlibm port; a Hermes or QuickJS build
 * reaches the platform libm, which on Android is bionic's. The upstream has already measured that the
 * two disagree — `engine/edgeDog.ts:101-107` records `gaussianKernel` rounding "differently in the
 * last bit" as a 1.18e-4 cost.
 *
 * **Once the runtime is chosen and this line has read `identical` for every case on a real device,
 * turn Tier 2 into a gate** by asserting `verdict.printed.equal` above alongside `verdict.pass`. At
 * that point byte equality is an observed fact about a specific pair of runtimes and any later change
 * to it is a regression somebody needs to see. Until then, printing it is the honest thing.
 */
test("zz-summary: what the two runtimes printed", () => {
  if (!hasCandidates()) {
    test.info().annotations.push({
      type: "web only",
      description:
        `no records under ${CANDIDATE_DIR}. The web half ran alone: ${PARITY_CASES.length} cases ` +
        `against their committed references. Run the Android half and pull its records here to make ` +
        `this a parity run.`
    });
    return;
  }
  for (const line of printed) test.info().annotations.push({ type: "tier2", description: line });
  expect(printed.length, "candidates exist but no case compared one — check the file names").toBeGreaterThan(0);
});
