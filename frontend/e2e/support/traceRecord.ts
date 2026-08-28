/**
 * The parity record: one trace, flattened to something two runtimes can both write and one function
 * can compare — plus the comparator, and the tolerance, with the arithmetic for the tolerance.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * WHY THIS MODULE EXISTS AND WHAT IT MAY NOT IMPORT
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The argument for running the vendored engine on the handset rather than porting it is that two
 * implementations of 16,557 lines of Otsu, Canny, Schneider fitting, thinning and morphology will
 * not agree, and that the drawing in question ends up printed in a ministry submission. That
 * argument is worth exactly as much as the test behind it — so this file has one rule:
 *
 *      **IT IS THE SAME CODE ON BOTH SIDES.**
 *
 * It imports nothing from `node:`, touches no DOM, no `Buffer`, no `atob`, no `crypto`, and no
 * platform anything. It is the same module the web spec imports and the same module an Android JS
 * host loads, so the record format and the comparison cannot themselves diverge — which would be the
 * comedy version of this whole exercise: a parity harness that fails parity.
 *
 * The one thing it cannot compute portably is a SHA-256, so the input hash is passed IN. Node's
 * `createHash` and a JVM's `MessageDigest` both produce it; neither belongs here.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * THE THREE TIERS, AND WHY ONLY ONE OF THEM HAS AN EPSILON
 * ════════════════════════════════════════════════════════════════════════════
 *
 * A "pixel diff of two renders" is the wrong comparison and the task is right to rule it out: an
 * antialiasing difference in two SVG rasterisers would fail it while the two files were identical,
 * and a stroke that vanished on one side could pass it if it was thin enough. So the comparison is
 * on the document. It has three tiers and they are not the same kind of assertion.
 *
 * ── TIER 0 — STRUCTURE. Exact. No tolerance, and none is defensible. ────────
 *
 *   shape count, the verb byte sequence, the per-shape closed flag, the shape→style mapping, the
 *   document's own width/height/background, the note sentences, and the auto-detected subject id.
 *
 * There is no floating-point story under which 41 paths equal 42, or under which a `C` in one file
 * and an `L` in the other are the same curve. Tier 0 is also the SHARPEST detector in the harness,
 * and that is not obvious, so it is worth writing down: every one of those integers is the output of
 * a DECISION — `>=` against a threshold, a corner accepted or rejected, a blob kept or dropped — and
 * a decision converts an arbitrarily small numeric disagreement into a discrete one. `engine/matte.ts:19-25`
 * says it in the upstream's own words: a 1e-7 difference "is not invisible here the way it is inside
 * a filter — it moves a pixel from one side of the matte to the other". If two runtimes disagree in
 * the last bit of `Math.exp`, Tier 0 is where it surfaces, as a whole path appearing or vanishing.
 *
 * ── TIER 1 — GEOMETRY. Epsilon. See {@link coordTolerance}. ────────────────
 *
 *   every control point, and the float fields of every style entry.
 *
 * Tier 1 is a DIAGNOSTIC as much as a gate. When Tier 2 fails, Tier 1 is what says whether the two
 * runtimes drew the same line to within a micron or moved a stroke by a pixel, and it names the
 * shape, the coordinate index and the size of the move so a human is not left diffing two megabytes
 * of base64.
 *
 * ── TIER 2 — THE PRINTED DRAWING. Exact, on a string. ──────────────────────
 *
 *   the SVG file, byte for byte, at `precision: 2`.
 *
 * This is the tier that actually answers the product question, and it needs no epsilon at all,
 * because the thing that reaches the ministry is a string. `components/sketches/upload/geometryToSvg.ts`
 * writes it (`buildSvg`, `precision = options.precision ?? 2`), matching `engine/svgWriter.ts`'s own
 * `DEFAULT_SVG_OPTIONS.precision = 2`. Two runtimes whose SVG bytes match have produced the same
 * drawing by definition — no argument about ulps required.
 *
 * The upstream already treats this tier this way: `engine/bezierFit.ts:583-589` calls `toD` "a §14
 * string stage compared **exactly**" and rounds every emitted control point through `Math.fround`
 * precisely so that two engines print the same digits.
 *
 * **Tier 2 is reported before it gates.** See {@link ParityVerdict.printed} and the note on
 * {@link comparePrinted} for why, and for what has to happen to the product's claim if it cannot be
 * made green.
 */

/* ════════════════════════════════════════════════════════════════════════════
 * THE RECORD
 * ════════════════════════════════════════════════════════════════════════════ */

/** Bump when a field is added, removed or reinterpreted. A record from another version is refused. */
export const TRACE_RECORD_VERSION = 1;

/** One style entry, structurally `engine/path.VecStyle` with its enums as the strings they are. */
export interface RecordStyle {
  readonly stroke: number | null;
  readonly strokeWidth: number;
  readonly fill: number | null;
  readonly fillRule: string;
  readonly cap: string;
  readonly join: string;
  readonly miterLimit: number;
  readonly opacity: number;
}

export interface TraceRecord {
  readonly version: number;
  /** `<fixture>/<params>` — e.g. `disc-256x192/outline`. The join key between the two halves. */
  readonly caseId: string;
  /** Which runtime wrote it: `"web-node"`, `"android-<runtime>"`. Never compared, always printed. */
  readonly runtime: string;
  /** SHA-256 of the DECOMPRESSED fixture bytes. Compared: a different input is not a parity result. */
  readonly inputSha256: string;
  readonly inputWidth: number;
  readonly inputHeight: number;
  /** SHA-256 of `lib/trace/UPSTREAM-MANIFEST.txt`. A record from a different engine copy is refused. */
  readonly engineManifestSha256: string;
  readonly preview: boolean;

  /* — Tier 0 — */
  readonly docWidth: number;
  readonly docHeight: number;
  readonly background: number | null;
  readonly shapeCount: number;
  readonly nodeCount: number;
  /** Every segment's verb byte, all shapes concatenated, as hex. `worker/protocol.ts` VERB_*. */
  readonly verbsHex: string;
  /**
   * `VecDocument.bounds()` — `[minX, minY, maxX, maxY]` over every shape, as base64 float32.
   *
   * Tier 1, not Tier 0, and carried even though it is derivable from the coordinates, for two
   * reasons. It is the extent of the DRAWING rather than of its control points — `VecPath.bounds()`
   * solves each cubic's extrema (`engine/path.ts:231-262`) instead of taking the control polygon,
   * which for a circle fitted from three cubics is 25 px wider than the circle — so recomputing it
   * outside the engine would mean a second implementation of exactly the thing this harness exists
   * to avoid duplicating. And it is what the existing oracle asserts on, which is what lets the disc
   * cases here carry that oracle's arithmetic and stop the reference records being circular.
   */
  readonly boundsB64: string;
  /** `shapeCount + 1` entries, so a shape's extent is a subtraction. */
  readonly verbStarts: readonly number[];
  readonly coordStarts: readonly number[];
  /** One `0`/`1` character per shape. */
  readonly closedBits: string;
  readonly styleIndex: readonly number[];
  readonly styles: readonly RecordStyle[];
  readonly notes: readonly string[];
  readonly autoSubjectId: string;

  /* — Tier 1 — */
  /**
   * Every coordinate, as base64 of the raw little-endian float32 bytes.
   *
   * Not a JSON number array. `JSON.stringify` on a double IS exactly specified (Number::toString is
   * shortest-round-trip in ECMAScript), so decimals would in principle be lossless — but "in
   * principle" is doing load-bearing work in a sentence about a runtime we have not yet chosen, and
   * a QuickJS or Hermes build with a shortcut in its number printer would corrupt the evidence
   * rather than fail loudly. Raw bytes cannot be approximately right.
   *
   * Every value here is already exactly a float32: `engine/bezierFit.ts:590-602` `Math.fround`s each
   * emitted control point, and the worker's own `SerializedGeometry.coords` is a `Float32Array`.
   */
  readonly coordsB64: string;

  /* — Tier 2 — */
  /** The SVG file, at precision 2, exactly as `buildSvg` would write it for `lineArtFile`. */
  readonly svg: string;
}

/* ════════════════════════════════════════════════════════════════════════════
 * BASE64, WRITTEN OUT
 * ════════════════════════════════════════════════════════════════════════════
 *
 * `Buffer` is Node's, `btoa` is the browser's, and neither is guaranteed on a bare JS runtime
 * embedded in an Android app. Thirty lines here costs nothing and keeps the module's one rule.
 * ════════════════════════════════════════════════════════════════════════════ */

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

export function bytesToBase64(bytes: Uint8Array): string {
  let out = "";
  let i = 0;
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
    out += B64[(n >>> 18) & 63] + B64[(n >>> 12) & 63] + B64[(n >>> 6) & 63] + B64[n & 63];
  }
  const rest = bytes.length - i;
  if (rest === 1) {
    const n = bytes[i] << 16;
    out += B64[(n >>> 18) & 63] + B64[(n >>> 12) & 63] + "==";
  } else if (rest === 2) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8);
    out += B64[(n >>> 18) & 63] + B64[(n >>> 12) & 63] + B64[(n >>> 6) & 63] + "=";
  }
  return out;
}

export function base64ToBytes(text: string): Uint8Array {
  const clean = text.replace(/=+$/, "");
  const out = new Uint8Array((clean.length * 3) >> 2);
  let acc = 0;
  let bits = 0;
  let o = 0;
  for (let i = 0; i < clean.length; i++) {
    const v = B64.indexOf(clean[i]);
    if (v < 0) throw new Error(`base64: unexpected character at ${i}`);
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (acc >>> bits) & 0xff;
    }
  }
  return out;
}

/**
 * Little-endian on both sides, stated rather than inherited.
 *
 * Every Android device this ships to is ARM or x86 and runs little-endian, and every JS `DataView`
 * takes an explicit endianness anyway — but a typed-array `.buffer` reinterpret does NOT, and that
 * is the line where a big-endian host would silently write a byte-swapped record that compares as a
 * total mismatch. `DataView` with an explicit `true` costs one call per coordinate and removes the
 * question.
 */
export function float32ToBase64(values: Float32Array): string {
  const bytes = new Uint8Array(values.length * 4);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < values.length; i++) view.setFloat32(i * 4, values[i], true);
  return bytesToBase64(bytes);
}

export function base64ToFloat32(text: string): Float32Array {
  const bytes = base64ToBytes(text);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const out = new Float32Array(bytes.byteLength >> 2);
  for (let i = 0; i < out.length; i++) out[i] = view.getFloat32(i * 4, true);
  return out;
}

const HEX = "0123456789abcdef";

export function bytesToHex(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i++) out += HEX[bytes[i] >>> 4] + HEX[bytes[i] & 15];
  return out;
}

export function hexToBytes(text: string): Uint8Array {
  if (text.length % 2 !== 0) throw new Error("hex: odd length");
  const out = new Uint8Array(text.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(text.substr(i * 2, 2), 16);
  return out;
}

/* ════════════════════════════════════════════════════════════════════════════
 * THE TOLERANCE
 * ════════════════════════════════════════════════════════════════════════════ */

/**
 * The floor, in document pixels: **1e-3 px**.
 *
 * WHERE 1e-3 COMES FROM, AND WHY IT IS NOT THE UPSTREAM'S 1e-4.
 *
 * The vendored engine already carries a parity tolerance, stated four separate times and always as
 * the same number:
 *
 *   - `engine/edgeFlow.ts:99`   — "an error of 2.0 against a **1e-4 parity tolerance**"
 *   - `engine/edgeDog.ts:101`   — "one ulp of float32 (6e-8) is worth 6e-5 here, **most of the §14
 *                                  tolerance**"
 *   - `engine/convolve.ts:115`  — "2.5e-5 of cross-engine disagreement … **inside the §14 tolerance**,
 *                                  but four fifths of the budget"
 *   - `engine/contrast.ts:142`  — "one LUT step is 1/255, **forty times the parity tolerance**"
 *                                  (1/255/40 = 9.8e-5)
 *
 * That is not a number to be invented here; it is the number the engine was BUILT to satisfy, and
 * `engine/matte.ts:19` explains the machinery that satisfies it (every Kotlin `Float` threshold
 * mirrored through `Math.fround`). But read the four citations again and notice what they are
 * tolerances ON: an image plane in 0..1, a DoG response, an 8-bit code value. **1e-4 is the
 * upstream's tolerance on normalised INTENSITY, not on document COORDINATES**, and using it directly
 * on a coordinate would be borrowing a number's authority without its units.
 *
 * So the coordinate tolerance is derived, in two parts.
 *
 * PART 1 — WHAT AN INTENSITY DISAGREEMENT IS WORTH IN PIXELS. A coordinate becomes a coordinate at
 * the sub-pixel ridge interpolation in Canny (`engine/edgeCanny.ts:57`, "the gradient ridge located
 * to sub-pixel precision"), where an offset is a ratio of neighbouring gradient magnitudes. Perturb
 * those magnitudes by the full 1e-4 budget and the offset moves by roughly 1e-4 divided by the local
 * gradient contrast; on a drawn line against paper — the case this whole feature exists for — that
 * contrast is order 0.5, so the move is ~2e-4 px. A preview traced at 256 from 1024 multiplies it by
 * four on the way back to source coordinates (`e2e/trace-engine-unit.spec.ts` measures exactly that
 * amplification, 1.33 px full-resolution against 4.91 px at a quarter scale), giving ~1e-3 px.
 *
 * PART 2 — WHAT THE REPRESENTATION COSTS. Every coordinate is exactly a float32, so two runtimes
 * that agree perfectly still land on adjacent float32 values whenever the exact result sits near a
 * rounding boundary. One float32 ulp is |x| * 2^-23: 3e-5 px at x=256, 1.2e-4 at 1024, 4.9e-4 at
 * 4096 — which is `DECODE_MAX_EDGE_PX`, the largest coordinate this product can produce
 * (`components/sketches/upload/decodeToPixels.ts:37`). Past about x=1024 the representation, not the
 * arithmetic, is the dominant term, so the tolerance has to carry a relative part or it would be
 * stricter at the top of the frame than the number format allows.
 *
 * Hence `1e-3 + 4 ulp`. Four ulps and not one: a coordinate is the end of a chain of adds, and one
 * ulp of tolerance would fail on rounding alone.
 *
 * PART 3 — THE SANITY CHECK, WHICH IS THE REAL ARGUMENT. A tolerance is honest only if it is far
 * below the smallest difference that could change the printed drawing, and that number is knowable
 * rather than a matter of taste. `buildSvg` prints coordinates at 2 decimal places, so one unit in
 * the last printed place is 0.01 document px. A traced sheet at the 400 px short edge of the photo
 * fixture, printed across a 190 mm A4 text column, is 0.475 mm per px — so 0.01 px is **4.75 µm**,
 * about a fifth of the 21 µm dot a 1200-dpi imagesetter can lay down and a ninth of a 600-dpi one.
 * The worst tolerance this function returns, at the extreme corner of a 4096 px frame, is 3.0e-3 px
 * = **1.4 µm**: below half a printed unit, so two runtimes inside tolerance can differ by at most
 * one digit in the last place of the file and only for a coordinate that lands on a `.xx5` boundary
 * — and that digit is a third of a micron on the page.
 *
 * The difference that WOULD change the printed drawing is about half a source pixel, ~240 µm on that
 * A4 column: a visible shift of a stroke. This tolerance is five orders of magnitude below it. That
 * gap is the point: **a Tier 1 failure is never a rounding argument, it is a real divergence**, and
 * nobody should be tempted to widen the number to make a run go green.
 */
export const COORD_EPSILON_FLOOR_PX = 1e-3;

/** Float32 has a 24-bit significand, so one ulp at |x| is about |x| * 2^-23. */
const F32_ULP_RATIO = Math.pow(2, -23);

/** How many ulps of headroom the floor is allowed on top of. See {@link COORD_EPSILON_FLOOR_PX}. */
const ULP_HEADROOM = 4;

/** @returns the permitted absolute difference, in document pixels, for a coordinate near `value`. */
export function coordTolerance(value: number): number {
  const magnitude = Math.abs(value);
  return COORD_EPSILON_FLOOR_PX + ULP_HEADROOM * (magnitude > 1 ? magnitude : 1) * F32_ULP_RATIO;
}

/**
 * Stroke widths get the same treatment, for a reason worth stating: a width is multiplied by
 * `Mat2D.meanScale()` on the way from working to source resolution (`engine/path.ts:70-80`), so it
 * carries the same amplification a coordinate does and deserves the same allowance.
 */
export const styleTolerance = coordTolerance;

/* ════════════════════════════════════════════════════════════════════════════
 * BUILDING A RECORD
 * ════════════════════════════════════════════════════════════════════════════ */

/** The subset of `engine.VecPath` this reads. Declared structurally so nothing is imported. */
interface PathLike {
  readonly start: { readonly x: number; readonly y: number };
  readonly segments: readonly {
    readonly kind: string;
    readonly to: { readonly x: number; readonly y: number };
    readonly c?: { readonly x: number; readonly y: number };
    readonly c1?: { readonly x: number; readonly y: number };
    readonly c2?: { readonly x: number; readonly y: number };
  }[];
  readonly closed: boolean;
}

interface ShapeLike {
  readonly path: PathLike;
  readonly style: RecordStyle;
}

/** The subset of `engine.TraceResult` this reads. */
export interface TraceResultLike {
  readonly document: {
    readonly width: number;
    readonly height: number;
    readonly background: number | null;
    readonly layers: readonly { readonly shapes: readonly ShapeLike[] }[];
    shapeCount(): number;
    nodeCount(): number;
    bounds(): Float32Array;
  };
  readonly notes: readonly string[];
  readonly autoSubjectId: string;
}

export interface BuildRecordInput {
  readonly caseId: string;
  readonly runtime: string;
  readonly inputSha256: string;
  readonly inputWidth: number;
  readonly inputHeight: number;
  readonly engineManifestSha256: string;
  readonly preview: boolean;
  readonly result: TraceResultLike;
  /** The SVG the shipping path would write. See {@link TraceRecord.svg}. */
  readonly svg: string;
}

const VERB_LINE = 0;
const VERB_QUAD = 1;
const VERB_CUBIC = 2;

/**
 * Flattens a `TraceResult` into a {@link TraceRecord}.
 *
 * The walk is deliberately the SAME walk as `worker/trace.worker.ts:199` `serializeGeometry`: layers
 * in order, shapes in order, start point first, then two/four/six coordinates per segment, styles
 * de-duplicated by value into a table. That is not tidiness — it is what makes a Tier 0 or Tier 1
 * failure here mean something about the geometry the PRODUCT would ship, rather than about a
 * traversal order this file invented. `serializeGeometry` is private to the worker module and the
 * worker module refuses to load outside a worker, so it cannot be reused directly; if it is ever
 * exported, delete this walk and call it.
 */
export function buildTraceRecord(input: BuildRecordInput): TraceRecord {
  const doc = input.result.document;
  const shapes: ShapeLike[] = [];
  for (const layer of doc.layers) for (const shape of layer.shapes) shapes.push(shape);

  let segTotal = 0;
  let coordTotal = 0;
  for (const shape of shapes) {
    segTotal += shape.path.segments.length;
    coordTotal += 2;
    for (const seg of shape.path.segments) {
      coordTotal += seg.kind === "line" ? 2 : seg.kind === "quad" ? 4 : 6;
    }
  }

  const coords = new Float32Array(coordTotal);
  const verbs = new Uint8Array(segTotal);
  const verbStarts: number[] = new Array<number>(shapes.length + 1);
  const coordStarts: number[] = new Array<number>(shapes.length + 1);
  const styleIndex: number[] = new Array<number>(shapes.length);
  const styles: RecordStyle[] = [];
  const styleKeys = new Map<string, number>();
  let closedBits = "";

  let v = 0;
  let c = 0;
  for (let s = 0; s < shapes.length; s++) {
    const shape = shapes[s];
    verbStarts[s] = v;
    coordStarts[s] = c;
    closedBits += shape.path.closed ? "1" : "0";

    const key = styleKeyOf(shape.style);
    let index = styleKeys.get(key);
    if (index === undefined) {
      index = styles.length;
      styles.push(shape.style);
      styleKeys.set(key, index);
    }
    styleIndex[s] = index;

    coords[c++] = shape.path.start.x;
    coords[c++] = shape.path.start.y;
    for (const seg of shape.path.segments) {
      if (seg.kind === "line") {
        verbs[v++] = VERB_LINE;
        coords[c++] = seg.to.x;
        coords[c++] = seg.to.y;
      } else if (seg.kind === "quad") {
        verbs[v++] = VERB_QUAD;
        coords[c++] = seg.c!.x;
        coords[c++] = seg.c!.y;
        coords[c++] = seg.to.x;
        coords[c++] = seg.to.y;
      } else {
        verbs[v++] = VERB_CUBIC;
        coords[c++] = seg.c1!.x;
        coords[c++] = seg.c1!.y;
        coords[c++] = seg.c2!.x;
        coords[c++] = seg.c2!.y;
        coords[c++] = seg.to.x;
        coords[c++] = seg.to.y;
      }
    }
  }
  verbStarts[shapes.length] = v;
  coordStarts[shapes.length] = c;

  return {
    version: TRACE_RECORD_VERSION,
    caseId: input.caseId,
    runtime: input.runtime,
    inputSha256: input.inputSha256,
    inputWidth: input.inputWidth,
    inputHeight: input.inputHeight,
    engineManifestSha256: input.engineManifestSha256,
    preview: input.preview,
    docWidth: doc.width,
    docHeight: doc.height,
    background: doc.background,
    shapeCount: doc.shapeCount(),
    nodeCount: doc.nodeCount(),
    verbsHex: bytesToHex(verbs),
    boundsB64: float32ToBase64(doc.bounds()),
    verbStarts,
    coordStarts,
    closedBits,
    styleIndex,
    styles,
    notes: input.result.notes.slice(),
    autoSubjectId: input.result.autoSubjectId,
    coordsB64: float32ToBase64(coords),
    svg: input.svg
  };
}

function styleKeyOf(style: RecordStyle): string {
  return [
    style.stroke,
    style.strokeWidth,
    style.fill,
    style.fillRule,
    style.cap,
    style.join,
    style.miterLimit,
    style.opacity
  ].join("|");
}

/**
 * WHAT IS DELIBERATELY NOT IN THE RECORD, AND WHY LEAVING IT OUT IS NOT CHEATING.
 *
 * `TraceResult.stages[].millis` and `.totalMillis` are `Date.now()` differences
 * (`engine/pipeline.ts:242,249,323,777` — the ONLY four non-deterministic reads in all 43 engine
 * files; there is no `Math.random` anywhere). A handset is slower than a laptop, so including them
 * would guarantee a red run that says nothing. Their absence is also the reason this record can be
 * regenerated and diffed: two runs on the SAME runtime produce byte-identical records.
 *
 * `TraceResult.preview` (the working-resolution `Mask`), `processedGray` and `distanceTransform` are
 * left out for a different reason: they are intermediate rasters, they are megabytes, and nothing
 * downstream of the trace ships them. If a Tier 0 failure ever needs localising to a stage, the
 * right move is a second, opt-in record carrying per-stage raster hashes — not to inflate this one.
 */

/* ════════════════════════════════════════════════════════════════════════════
 * COMPARING
 * ════════════════════════════════════════════════════════════════════════════ */

export interface CoordDiff {
  /** Index into the flat coordinate array. */
  readonly index: number;
  /** Which shape owns it, and whether it is an x or a y — so a message can name a place. */
  readonly shape: number;
  readonly axis: "x" | "y";
  readonly reference: number;
  readonly candidate: number;
  readonly delta: number;
  readonly tolerance: number;
}

export interface ParityVerdict {
  readonly caseId: string;
  /** Tier 0 and the record's own preconditions. Every entry is a sentence. Empty means clean. */
  readonly structure: readonly string[];
  readonly geometry: {
    readonly compared: number;
    readonly overTolerance: readonly CoordDiff[];
    /** The largest |delta| seen, tolerated or not, and where. Null when nothing was compared. */
    readonly worst: CoordDiff | null;
  };
  readonly printed: {
    readonly equal: boolean;
    /** First differing character index, or -1. */
    readonly firstDiffAt: number;
    /** A short excerpt around the first difference, from each side. Empty when equal. */
    readonly referenceNear: string;
    readonly candidateNear: string;
    readonly referenceLength: number;
    readonly candidateLength: number;
  };
  /** True when Tier 0 and Tier 1 are clean. Tier 2 is reported separately on purpose. */
  readonly pass: boolean;
}

/** How many coordinate differences to keep before giving up on listing them. */
const MAX_LISTED_DIFFS = 12;

export function compareTraceRecords(reference: TraceRecord, candidate: TraceRecord): ParityVerdict {
  const structure: string[] = [];
  const say = (what: string, a: unknown, b: unknown): void => {
    structure.push(`${what}: reference ${JSON.stringify(a)}, candidate ${JSON.stringify(b)}`);
  };

  /* — preconditions. A mismatch here means the two halves did not run the same experiment, which is
       a different and more urgent failure than a divergence, so it is said in those words. — */
  if (reference.version !== candidate.version) say("record version", reference.version, candidate.version);
  if (reference.caseId !== candidate.caseId) say("case id", reference.caseId, candidate.caseId);
  if (reference.inputSha256 !== candidate.inputSha256) {
    structure.push(
      `input bytes differ (reference ${reference.inputSha256.slice(0, 12)}…, candidate ` +
        `${candidate.inputSha256.slice(0, 12)}…) — the two halves did not trace the same image, so ` +
        `nothing below is a parity result`
    );
  }
  if (reference.engineManifestSha256 !== candidate.engineManifestSha256) {
    structure.push(
      `engine copy differs (UPSTREAM-MANIFEST.txt ${reference.engineManifestSha256.slice(0, 12)}… vs ` +
        `${candidate.engineManifestSha256.slice(0, 12)}…) — one side is running a different vendored copy`
    );
  }
  if (reference.preview !== candidate.preview) say("preview", reference.preview, candidate.preview);

  /* — Tier 0 — */
  if (reference.docWidth !== candidate.docWidth) say("document width", reference.docWidth, candidate.docWidth);
  if (reference.docHeight !== candidate.docHeight) say("document height", reference.docHeight, candidate.docHeight);
  if (reference.background !== candidate.background) say("background", reference.background, candidate.background);
  if (reference.shapeCount !== candidate.shapeCount) say("shape count", reference.shapeCount, candidate.shapeCount);
  if (reference.nodeCount !== candidate.nodeCount) say("node count", reference.nodeCount, candidate.nodeCount);
  if (reference.autoSubjectId !== candidate.autoSubjectId) {
    say("auto subject id", reference.autoSubjectId, candidate.autoSubjectId);
  }
  if (reference.verbsHex !== candidate.verbsHex) {
    const at = firstDifference(reference.verbsHex, candidate.verbsHex);
    structure.push(
      `verb sequence differs at segment ${at >> 1} of ${reference.verbsHex.length >> 1} — ` +
        `reference ${verbName(reference.verbsHex, at)}, candidate ${verbName(candidate.verbsHex, at)}`
    );
  }
  if (reference.closedBits !== candidate.closedBits) {
    const at = firstDifference(reference.closedBits, candidate.closedBits);
    structure.push(`closed flag differs at shape ${at}: reference ${reference.closedBits[at]}, candidate ${candidate.closedBits[at]}`);
  }
  compareIntArrays("verbStarts", reference.verbStarts, candidate.verbStarts, structure);
  compareIntArrays("coordStarts", reference.coordStarts, candidate.coordStarts, structure);
  compareIntArrays("styleIndex", reference.styleIndex, candidate.styleIndex, structure);
  compareNotes(reference.notes, candidate.notes, structure);
  compareStyles(reference.styles, candidate.styles, structure);

  /* — Tier 1 — */
  const refCoords = base64ToFloat32(reference.coordsB64);
  const canCoords = base64ToFloat32(candidate.coordsB64);
  const overTolerance: CoordDiff[] = [];
  let worst: CoordDiff | null = null;
  const n = Math.min(refCoords.length, canCoords.length);
  if (refCoords.length !== canCoords.length) {
    say("coordinate count", refCoords.length, canCoords.length);
  }
  for (let i = 0; i < n; i++) {
    const a = refCoords[i];
    const b = canCoords[i];
    // NaN never equals itself, so a pair of NaNs would otherwise read as an infinite delta. The
    // engine substitutes 0 for a non-finite value on the way into an SVG attribute
    // (geometryToSvg.ts `num`) but does NOT do so in the geometry, so this case is reachable.
    if (Number.isNaN(a) && Number.isNaN(b)) continue;
    const delta = Math.abs(a - b);
    const tolerance = coordTolerance(a);
    const diff: CoordDiff = {
      index: i,
      shape: shapeOfCoord(reference.coordStarts, i),
      axis: i % 2 === 0 ? "x" : "y",
      reference: a,
      candidate: b,
      delta,
      tolerance
    };
    if (worst === null || delta > worst.delta) worst = diff;
    if (!(delta <= tolerance) && overTolerance.length < MAX_LISTED_DIFFS) overTolerance.push(diff);
  }

  // The document's extent, under the same tolerance as any other coordinate. Compared separately
  // from the coordinate loop so a failure says "the drawing is a different size" in those words
  // rather than as the thousandth entry of a list of moved control points.
  const refBounds = base64ToFloat32(reference.boundsB64);
  const canBounds = base64ToFloat32(candidate.boundsB64);
  const edges = ["left", "top", "right", "bottom"] as const;
  for (let i = 0; i < 4 && i < refBounds.length && i < canBounds.length; i++) {
    const delta = Math.abs(refBounds[i] - canBounds[i]);
    const tol = coordTolerance(refBounds[i]);
    if (!(delta <= tol)) {
      structure.push(
        `document bounds, ${edges[i]} edge: reference ${refBounds[i]}, candidate ${canBounds[i]} ` +
          `(delta ${delta.toExponential(3)} px, tolerance ${tol.toExponential(3)})`
      );
    }
  }

  const printed = comparePrinted(reference.svg, candidate.svg);

  return {
    caseId: reference.caseId,
    structure,
    geometry: { compared: n, overTolerance, worst },
    printed,
    pass: structure.length === 0 && overTolerance.length === 0
  };
}

/**
 * Tier 2, byte for byte.
 *
 * WHY THIS IS REPORTED RATHER THAN FOLDED INTO `pass`, AT LEAST TO BEGIN WITH.
 *
 * ECMAScript does not specify `Math.exp`, `Math.log`, `Math.pow`, `Math.sin`, `Math.cos`,
 * `Math.atan2` or `Math.cbrt` exactly — they are "implementation-approximated" — and this engine
 * uses every one of them, including `Math.exp` inside `convolve.gaussianKernel`, which is upstream
 * of essentially every trace. V8 carries its own fdlibm port; Hermes and QuickJS reach the platform
 * libm, which on Android is bionic's. Those disagree in the last ulp, and the upstream has already
 * MEASURED that they do: `engine/edgeDog.ts:101-107` records "the engines round `gaussianKernel`
 * differently in the last bit" as a 1.18e-4 cost.
 *
 * So promising byte-identical SVG across two runtimes before one has been chosen would be a promise
 * made out of hope. The harness therefore states the truth in three parts instead:
 *
 *   - Tier 0 and Tier 1 GATE. They are what "the same drawing" means, and they are achievable.
 *   - Tier 2 is MEASURED on every case and printed on every run.
 *   - **Once the runtime is chosen and a first full green run is on record, Tier 2 becomes a gate**
 *     — because at that point byte equality is an observed fact about a specific pair of runtimes,
 *     and any later change to it is a regression somebody needs to see.
 *
 * If Tier 2 cannot be made green for the chosen runtime, that is not a reason to widen a tolerance.
 * It is a fact about the product that has to be said out loud to whoever signs the submission: the
 * handset's line art is equivalent to the browser's to within a micron on the page, not identical to
 * it. The honest fix lives upstream — a table-driven or polynomial `exp` in `engine/convolve.ts`
 * would remove the one dominant seam — and that is an owner decision about a vendored file, not a
 * local edit. Do not make it here.
 */
export function comparePrinted(reference: string, candidate: string): ParityVerdict["printed"] {
  if (reference === candidate) {
    return {
      equal: true,
      firstDiffAt: -1,
      referenceNear: "",
      candidateNear: "",
      referenceLength: reference.length,
      candidateLength: candidate.length
    };
  }
  const at = firstDifference(reference, candidate);
  const from = Math.max(0, at - 40);
  return {
    equal: false,
    firstDiffAt: at,
    referenceNear: reference.slice(from, at + 40),
    candidateNear: candidate.slice(from, at + 40),
    referenceLength: reference.length,
    candidateLength: candidate.length
  };
}

function firstDifference(a: string, b: string): number {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return i;
  return n;
}

function verbName(hex: string, at: number): string {
  const byteAt = (at >> 1) * 2;
  const code = parseInt(hex.substr(byteAt, 2) || "ff", 16);
  return code === VERB_LINE ? "L" : code === VERB_QUAD ? "Q" : code === VERB_CUBIC ? "C" : `?${code}`;
}

function shapeOfCoord(coordStarts: readonly number[], index: number): number {
  // Linear rather than a binary search: the caller stops after MAX_LISTED_DIFFS, so this runs a
  // handful of times per case and a bisection would be more code for no measurable time.
  for (let s = coordStarts.length - 2; s >= 0; s--) if (index >= coordStarts[s]) return s;
  return 0;
}

function compareIntArrays(what: string, a: readonly number[], b: readonly number[], into: string[]): void {
  if (a.length !== b.length) {
    into.push(`${what} length: reference ${a.length}, candidate ${b.length}`);
    return;
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      into.push(`${what}[${i}]: reference ${a[i]}, candidate ${b[i]}`);
      return;
    }
  }
}

/**
 * Notes are compared EXACTLY, and that is on purpose.
 *
 * They are the sentences a designer reads — "Preview at 256x192.", "No paths were produced" — and
 * `worker/protocol.ts` records the upstream rule that every one of them is "rendered without
 * exception (§0.4)". Two clients that say different things about the same trace have already broken
 * the property this whole exercise is defending, even if the geometry matches.
 */
function compareNotes(a: readonly string[], b: readonly string[], into: string[]): void {
  if (a.length !== b.length) {
    into.push(`note count: reference ${a.length} ${JSON.stringify(a)}, candidate ${b.length} ${JSON.stringify(b)}`);
    return;
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) into.push(`note[${i}]: reference ${JSON.stringify(a[i])}, candidate ${JSON.stringify(b[i])}`);
  }
}

function compareStyles(a: readonly RecordStyle[], b: readonly RecordStyle[], into: string[]): void {
  if (a.length !== b.length) {
    into.push(`style table size: reference ${a.length}, candidate ${b.length}`);
    return;
  }
  for (let i = 0; i < a.length; i++) {
    const x = a[i];
    const y = b[i];
    // Colours are packed ARGB integers and fill rule / cap / join are string enums: all exact.
    if (x.stroke !== y.stroke) into.push(`styles[${i}].stroke: ${x.stroke} vs ${y.stroke}`);
    if (x.fill !== y.fill) into.push(`styles[${i}].fill: ${x.fill} vs ${y.fill}`);
    if (x.fillRule !== y.fillRule) into.push(`styles[${i}].fillRule: ${x.fillRule} vs ${y.fillRule}`);
    if (x.cap !== y.cap) into.push(`styles[${i}].cap: ${x.cap} vs ${y.cap}`);
    if (x.join !== y.join) into.push(`styles[${i}].join: ${x.join} vs ${y.join}`);
    // Widths and opacities are measurements and get the coordinate tolerance — see styleTolerance.
    for (const field of ["strokeWidth", "miterLimit", "opacity"] as const) {
      const dx = Math.abs(x[field] - y[field]);
      const tol = styleTolerance(x[field]);
      if (!(dx <= tol)) into.push(`styles[${i}].${field}: ${x[field]} vs ${y[field]} (delta ${dx}, tolerance ${tol})`);
    }
  }
}

/** @returns a verdict rendered as the failure message a human should be shown. */
export function explainVerdict(v: ParityVerdict): string {
  const out: string[] = [`case ${v.caseId}`];
  if (v.structure.length > 0) {
    out.push(`  TIER 0 — structure (${v.structure.length}):`);
    for (const line of v.structure.slice(0, MAX_LISTED_DIFFS)) out.push(`    ${line}`);
  }
  if (v.geometry.overTolerance.length > 0) {
    out.push(`  TIER 1 — geometry: ${v.geometry.overTolerance.length}+ coordinates over tolerance of ${v.geometry.compared}`);
    for (const d of v.geometry.overTolerance) {
      out.push(
        `    shape ${d.shape} coord ${d.index} (${d.axis}): ${d.reference} vs ${d.candidate}` +
          `  delta ${d.delta.toExponential(3)} > tolerance ${d.tolerance.toExponential(3)}`
      );
    }
  } else if (v.geometry.worst !== null) {
    out.push(
      `  TIER 1 — geometry: clean. worst |delta| ${v.geometry.worst.delta.toExponential(3)} px at shape ` +
        `${v.geometry.worst.shape} coord ${v.geometry.worst.index} (tolerance ${v.geometry.worst.tolerance.toExponential(3)})`
    );
  }
  if (v.printed.equal) {
    out.push(`  TIER 2 — printed SVG: identical (${v.printed.referenceLength} bytes)`);
  } else {
    out.push(
      `  TIER 2 — printed SVG: DIFFERS at character ${v.printed.firstDiffAt} ` +
        `(${v.printed.referenceLength} vs ${v.printed.candidateLength} bytes)`
    );
    out.push(`    reference … ${v.printed.referenceNear}`);
    out.push(`    candidate … ${v.printed.candidateNear}`);
  }
  return out.join("\n");
}
