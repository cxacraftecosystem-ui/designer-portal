import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { gunzipSync } from "node:zlib";

export const PARITY_DIR = join(__dirname, "..", "fixtures", "trace-parity");
export const REFERENCE_DIR = join(PARITY_DIR, "reference");
export const CANDIDATE_DIR = join(PARITY_DIR, "candidate");

export const sha256Hex = (bytes: Uint8Array | Buffer): string =>
  createHash("sha256").update(bytes).digest("hex");

export interface Fixture {
  readonly name: string;
  readonly width: number;
  readonly height: number;
  readonly data: Uint8ClampedArray;
  readonly sha256: string;
}

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

export function engineManifestSha256(): string {
  return sha256Hex(readFileSync(join(__dirname, "..", "..", "lib", "trace", "UPSTREAM-MANIFEST.txt")));
}

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

export interface ParityCase {
  readonly caseId: string;
  readonly fixture: string;
  readonly params: string;
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

export const recordFileName = (caseId: string): string => `${caseId.replace(/[/@]/g, "_")}.json`;

export const referencePath = (caseId: string): string => join(REFERENCE_DIR, recordFileName(caseId));
export const candidatePath = (caseId: string): string => join(CANDIDATE_DIR, recordFileName(caseId));

export const hasCandidates = (): boolean =>
  existsSync(CANDIDATE_DIR) && PARITY_CASES.some((c) => existsSync(candidatePath(c.caseId)));
