/**
 * The Node half of the parity harness: reading the corpus, and saying what each case is.
 *
 * This file may use `node:` — it is the web side's plumbing, not the shared contract. Everything the
 * two runtimes must agree on lives in `traceRecord.ts`, which imports nothing at all. Keep the line
 * there: a helper that migrates into this file becomes a helper the Android half cannot call.
 */

import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { gunzipSync } from "node:zlib";

export const PARITY_DIR = join(__dirname, "..", "fixtures", "trace-parity");
export const REFERENCE_DIR = join(PARITY_DIR, "reference");
/** Where an `adb pull` is expected to leave the handset's records. Absent on an ordinary run. */
export const CANDIDATE_DIR = join(PARITY_DIR, "candidate");

export const sha256Hex = (bytes: Uint8Array | Buffer): string =>
  createHash("sha256").update(bytes).digest("hex");

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures
 * ──────────────────────────────────────────────────────────────────────────── */

export interface Fixture {
  readonly name: string;
  readonly width: number;
  readonly height: number;
  readonly data: Uint8ClampedArray;
  /** SHA-256 of the decompressed `w*h*4` payload — NOT of the header and NOT of the .gz. */
  readonly sha256: string;
}

/**
 * Reads one `<name>.rgba.gz`.
 *
 * The container is `RGBA8 <w> <h>\n` then `w*h*4` bytes; `make-fixtures.mjs`'s header says why it is
 * that and not a PNG. The parse is deliberately strict — a short payload is an error rather than a
 * silent truncation, because `RgbaImage.fromImageData` would accept a longer buffer without comment
 * and a shorter one only at the very end of a trace.
 */
export function loadFixture(name: string): Fixture {
  const body = gunzipSync(readFileSync(join(PARITY_DIR, `${name}.rgba.gz`)));
  const newline = body.indexOf(0x0a);
  if (newline < 0) throw new Error(`${name}: no header terminator`);
  const header = body.toString("ascii", 0, newline).split(" ");
  if (header[0] !== "RGBA8" || header.length !== 3) throw new Error(`${name}: bad header ${JSON.stringify(header)}`);
  const width = Number(header[1]);
  const height = Number(header[2]);
  const payload = body.subarray(newline + 1);
  const expected = width * height * 4;
  if (payload.length !== expected) {
    throw new Error(`${name}: header says ${width}x${height} (${expected} bytes), payload is ${payload.length}`);
  }
  return {
    name,
    width,
    height,
    data: new Uint8ClampedArray(payload.buffer, payload.byteOffset, payload.byteLength),
    sha256: sha256Hex(payload)
  };
}

/** `<sha256> <file> <w>x<h> <bytes>` per non-comment line of MANIFEST.txt, keyed by fixture name. */
export function loadFixtureManifest(): Map<string, { sha256: string; width: number; height: number; bytes: number }> {
  const out = new Map<string, { sha256: string; width: number; height: number; bytes: number }>();
  const text = readFileSync(join(PARITY_DIR, "MANIFEST.txt"), "utf8");
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed.length === 0 || trimmed.startsWith("#")) continue;
    const [sha, file, dims, bytes] = trimmed.split(/\s+/);
    const [w, h] = dims.split("x");
    out.set(file.replace(/\.rgba\.gz$/, ""), {
      sha256: sha,
      width: Number(w),
      height: Number(h),
      bytes: Number(bytes)
    });
  }
  return out;
}

/**
 * SHA-256 of `lib/trace/UPSTREAM-MANIFEST.txt`.
 *
 * Stamped into every record so a reference captured against one vendored copy cannot be silently
 * compared against a run of a different one. `lib/trace/README.md` §1 makes that manifest the only
 * identity the vendored engine has — there is no upstream commit hash to name — so hashing the
 * manifest is the closest thing to "which engine produced this" that exists.
 */
export function engineManifestSha256(): string {
  return sha256Hex(readFileSync(join(__dirname, "..", "..", "lib", "trace", "UPSTREAM-MANIFEST.txt")));
}

/* ────────────────────────────────────────────────────────────────────────────
 * Parameter sets
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Strips `_` keys, which `params.json` uses for prose.
 *
 * JSON cannot hold a comment and this corpus needs one badly — every set is a choice that has to
 * justify itself. `sanitizeTraceParams` ignores unknown keys anyway (it builds a fresh tree field by
 * field), so leaving them in would be harmless; they are removed so that a record's `params` echo is
 * the parameters and nothing else.
 */
function stripProse(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripProse);
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (k === "_") continue;
      out[k] = stripProse(v);
    }
    return out;
  }
  return value;
}

export function loadParamSets(): Map<string, Record<string, unknown>> {
  const raw = JSON.parse(readFileSync(join(PARITY_DIR, "params.json"), "utf8")) as Record<string, unknown>;
  const out = new Map<string, Record<string, unknown>>();
  for (const [id, value] of Object.entries(raw)) {
    if (id === "_") continue;
    out.set(id, stripProse(value) as Record<string, unknown>);
  }
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The corpus
 * ──────────────────────────────────────────────────────────────────────────── */

export interface ParityCase {
  /** `<fixture>/<params>`, or `<fixture>/<params>@preview<edge>`. The join key between the halves. */
  readonly caseId: string;
  readonly fixture: string;
  readonly params: string;
  /** Non-null runs `Pipeline.runPreview(img, params, longEdge)` instead of `Pipeline.run`. */
  readonly previewLongEdge: number | null;
}

const plain = (fixture: string, params: string): ParityCase => ({
  caseId: `${fixture}/${params}`,
  fixture,
  params,
  previewLongEdge: null
});

const preview = (fixture: string, params: string, longEdge: number): ParityCase => ({
  caseId: `${fixture}/${params}@preview${longEdge}`,
  fixture,
  params,
  previewLongEdge: longEdge
});

/**
 * Every case both halves must run, in this order.
 *
 * WHY THESE AND NOT MORE. Each one is here because it can fail in a way the others cannot:
 *
 *  - `disc-*` — the only cases whose right answer is arithmetic rather than a capture. They are what
 *    stops the goldens from being circular: an engine that had started returning a constant would
 *    match its own reference record perfectly and still fail the disc's bounding-box assertion in
 *    `trace-parity-unit.spec.ts`. The existing oracle's own reasoning, reused.
 *  - `disc-1024x768@preview256` — the trace runs at a quarter scale and the geometry is scaled back
 *    up, so every disagreement in it is multiplied by four. It is the corpus's amplifier.
 *  - `blank-256x192` — no paths at all, and a sentence instead. Two runtimes that disagree about the
 *    sentence have already broken the thing this exercise defends, even with identical geometry.
 *  - `sketch-photo-400x300` — the case the product exists for, and the only one with real texture,
 *    a ramped illumination and strokes of varying width. Under `default` it is also the only case
 *    that exercises the classifier, the thinner and the skeleton tracer together.
 *  - `all-black-320x240` — no background anywhere, so Otsu has nothing to separate and every
 *    threshold lands on a degenerate branch. Branch choices are Tier 0 failures, which is the point.
 *  - `one-pixel-1x1` — smaller than every kernel, and 255 px below `Limits.MIN_WORKING_EDGE`.
 *  - `hairline-2048x3` — 682:1. The working-resolution clamp and a 3-row neighbourhood at once.
 *
 * Both parameter sets on the pathological three, because a degenerate branch in CENTERLINE is a
 * different branch from a degenerate branch in OUTLINE and they are exactly where the two disagree.
 */
export const PARITY_CASES: readonly ParityCase[] = [
  plain("disc-256x192", "outline"),
  plain("disc-256x192", "default"),
  preview("disc-1024x768", "outline", 256),
  plain("blank-256x192", "outline"),
  plain("sketch-photo-400x300", "default"),
  plain("sketch-photo-400x300", "outline"),
  plain("all-black-320x240", "outline"),
  plain("all-black-320x240", "default"),
  plain("one-pixel-1x1", "outline"),
  plain("one-pixel-1x1", "default"),
  plain("hairline-2048x3", "outline"),
  plain("hairline-2048x3", "default")
];

/** A caseId as a file name: `/` and `@` are not portable across the two file systems in play. */
export const recordFileName = (caseId: string): string => `${caseId.replace(/[/@]/g, "_")}.json`;

export const referencePath = (caseId: string): string => join(REFERENCE_DIR, recordFileName(caseId));
export const candidatePath = (caseId: string): string => join(CANDIDATE_DIR, recordFileName(caseId));

export const hasCandidates = (): boolean =>
  existsSync(CANDIDATE_DIR) && PARITY_CASES.some((c) => existsSync(candidatePath(c.caseId)));
