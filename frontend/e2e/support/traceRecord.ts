export const TRACE_RECORD_VERSION = 1;

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
  readonly caseId: string;
  readonly runtime: string;
  readonly inputSha256: string;
  readonly inputWidth: number;
  readonly inputHeight: number;
  readonly engineManifestSha256: string;
  readonly preview: boolean;

  readonly docWidth: number;
  readonly docHeight: number;
  readonly background: number | null;
  readonly shapeCount: number;
  readonly nodeCount: number;
  readonly verbsHex: string;
  readonly boundsB64: string;
  readonly verbStarts: readonly number[];
  readonly coordStarts: readonly number[];
  readonly closedBits: string;
  readonly styleIndex: readonly number[];
  readonly styles: readonly RecordStyle[];
  readonly notes: readonly string[];
  readonly autoSubjectId: string;

  readonly coordsB64: string;

  readonly svg: string;
}

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

export const COORD_EPSILON_FLOOR_PX = 1e-3;

const F32_ULP_RATIO = Math.pow(2, -23);

const ULP_HEADROOM = 4;

export function coordTolerance(value: number): number {
  const magnitude = Math.abs(value);
  return COORD_EPSILON_FLOOR_PX + ULP_HEADROOM * (magnitude > 1 ? magnitude : 1) * F32_ULP_RATIO;
}

export const styleTolerance = coordTolerance;

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
  readonly svg: string;
}

const VERB_LINE = 0;
const VERB_QUAD = 1;
const VERB_CUBIC = 2;

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

export interface CoordDiff {
  readonly index: number;
  readonly shape: number;
  readonly axis: "x" | "y";
  readonly reference: number;
  readonly candidate: number;
  readonly delta: number;
  readonly tolerance: number;
}

export interface ParityVerdict {
  readonly caseId: string;
  readonly structure: readonly string[];
  readonly geometry: {
    readonly compared: number;
    readonly overTolerance: readonly CoordDiff[];
    readonly worst: CoordDiff | null;
  };
  readonly printed: {
    readonly equal: boolean;
    readonly firstDiffAt: number;
    readonly referenceNear: string;
    readonly candidateNear: string;
    readonly referenceLength: number;
    readonly candidateLength: number;
  };
  readonly pass: boolean;
}

const MAX_LISTED_DIFFS = 12;

export function compareTraceRecords(reference: TraceRecord, candidate: TraceRecord): ParityVerdict {
  const structure: string[] = [];
  const say = (what: string, a: unknown, b: unknown): void => {
    structure.push(`${what}: reference ${JSON.stringify(a)}, candidate ${JSON.stringify(b)}`);
  };

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
    if (x.stroke !== y.stroke) into.push(`styles[${i}].stroke: ${x.stroke} vs ${y.stroke}`);
    if (x.fill !== y.fill) into.push(`styles[${i}].fill: ${x.fill} vs ${y.fill}`);
    if (x.fillRule !== y.fillRule) into.push(`styles[${i}].fillRule: ${x.fillRule} vs ${y.fillRule}`);
    if (x.cap !== y.cap) into.push(`styles[${i}].cap: ${x.cap} vs ${y.cap}`);
    if (x.join !== y.join) into.push(`styles[${i}].join: ${x.join} vs ${y.join}`);
    for (const field of ["strokeWidth", "miterLimit", "opacity"] as const) {
      const dx = Math.abs(x[field] - y[field]);
      const tol = styleTolerance(x[field]);
      if (!(dx <= tol)) into.push(`styles[${i}].${field}: ${x[field]} vs ${y[field]} (delta ${dx}, tolerance ${tol})`);
    }
  }
}

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
