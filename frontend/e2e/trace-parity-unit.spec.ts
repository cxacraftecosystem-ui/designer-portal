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

const CAPTURE = process.env.TRACE_PARITY_CAPTURE === "1";

test("every fixture matches the hash MANIFEST.txt records for it", () => {
  const manifest = loadFixtureManifest();
  const named = new Set(PARITY_CASES.map((c) => c.fixture));
  expect(named.size, "every fixture in the manifest should be used by a case, and vice versa").toBe(manifest.size);

  for (const name of named) {
    const declared = manifest.get(name);
    expect(declared, `${name} is used by a case but is not in MANIFEST.txt`).toBeDefined();
    const fixture = loadFixture(name);
    expect(fixture.sha256, `${name}: the bytes on disk are not the bytes MANIFEST.txt names`).toBe(declared!.sha256);
    expect([fixture.width, fixture.height]).toEqual([declared!.width, declared!.height]);
    expect(fixture.data.length).toBe(declared!.bytes);
  }
});

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

  const outline = sanitizeTraceParams(sets.get("outline")!);
  expect(outline.output.vectorMode).toBe(VectorModeParam.OUTLINE);
  expect(outline.output.fillClosed).toBe(true);
  expect(outline.auto.mode).toBe(Params.AutoMode.OFF);

  expect(sanitizeTraceParams(sets.get("default")!)).toEqual(defaultTraceParams());
  expect(defaultTraceParams().output.vectorMode).toBe(VectorModeParam.CENTERLINE);
  expect(defaultTraceParams().auto.mode).toBe(Params.AutoMode.SUGGEST);
});

function loadReference(caseId: string): TraceRecord {
  return JSON.parse(readFileSync(referencePath(caseId), "utf8")) as TraceRecord;
}

test("the coordinate transport is exact, so a Tier 1 delta is never the record's own rounding", () => {
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
  expect(coordTolerance(0)).toBeCloseTo(1.000_476_8e-3, 9);
  expect(coordTolerance(256)).toBeCloseTo(1.122_070_3e-3, 9);
  expect(coordTolerance(1024)).toBeCloseTo(1.488_281_2e-3, 9);
  expect(coordTolerance(4096)).toBeCloseTo(2.953_125_0e-3, 9);
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
  const inside = compareTraceRecords(reference, nudged(0.4));
  expect(inside.pass).toBe(true);
  expect(inside.geometry.worst!.delta).toBeGreaterThan(0);
  const outside = compareTraceRecords(reference, nudged(40));
  expect(outside.pass).toBe(false);
  expect(outside.geometry.overTolerance[0].shape).toBe(0);
  expect(outside.geometry.overTolerance[0].axis).toBe("x");

  const verbFlipped = compareTraceRecords(reference, { ...reference, verbsHex: `00${reference.verbsHex.slice(2)}` });
  expect(verbFlipped.pass).toBe(false);
  expect(verbFlipped.structure[0]).toContain("verb sequence differs");

  const shapeLost = compareTraceRecords(reference, { ...reference, shapeCount: reference.shapeCount - 1 });
  expect(shapeLost.pass).toBe(false);

  const noteChanged = compareTraceRecords(reference, { ...reference, notes: ["something else"] });
  expect(noteChanged.pass).toBe(false);

  const otherInput = compareTraceRecords(reference, { ...reference, inputSha256: "0".repeat(64) });
  expect(otherInput.structure.join(" ")).toContain("did not trace the same image");
  const otherEngine = compareTraceRecords(reference, { ...reference, engineManifestSha256: "0".repeat(64) });
  expect(otherEngine.structure.join(" ")).toContain("different vendored copy");
});

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

    if (parityCase.fixture === "disc-256x192" && parityCase.params === "outline") {
      expect(record.shapeCount, "a disc has one boundary, and flat white has none").toBe(1);
      expect(record.closedBits).toBe("1");
      expect([record.docWidth, record.docHeight]).toEqual([256, 192]);
      expectBoundsNear(record, discBounds(256, 192), 3);
    }
    if (parityCase.fixture === "disc-1024x768") {
      expect(record.preview).toBe(true);
      expect([record.docWidth, record.docHeight]).toEqual([1024, 768]);
      expect(record.notes[0]).toMatch(/^Preview at 256x192\./);
      expectBoundsNear(record, discBounds(1024, 768), 8);
    }
    if (parityCase.fixture === "blank-256x192") {
      expect(record.shapeCount).toBe(0);
      expect(record.nodeCount).toBe(0);
      expect(record.notes.join(" ")).toContain("No paths were produced");
    }
    if (parityCase.fixture === "one-pixel-1x1" || parityCase.fixture === "hairline-2048x3") {
      expect(record.docWidth).toBe(fixture.width);
      expect(record.docHeight).toBe(fixture.height);
    }

    const expectedMode = parityCase.params === "outline" ? VectorModeParam.OUTLINE : VectorModeParam.CENTERLINE;
    expect(params.output.vectorMode).toBe(expectedMode);

    if (CAPTURE) {
      mkdirSync(REFERENCE_DIR, { recursive: true });
      writeFileSync(referencePath(parityCase.caseId), `${JSON.stringify(record, null, 2)}\n`, "utf8");
      test.info().annotations.push({ type: "captured", description: referencePath(parityCase.caseId) });
      return;
    }

    const reference = loadReference(parityCase.caseId);
    const self = compareTraceRecords(reference, record);
    expect(
      self.pass,
      `${explainVerdict(self)}\n\nIf lib/trace was updated on purpose, recapture:\n` +
        `  TRACE_PARITY_CAPTURE=1 npx playwright test trace-parity-unit\n` +
        `and say in the commit which upstream copy the new records were taken against.`
    ).toBe(true);
    expect(self.printed.equal, "the web engine no longer prints the same SVG it did when captured").toBe(true);

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
