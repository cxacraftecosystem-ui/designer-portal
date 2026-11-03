import { createHash } from "node:crypto";
import { gzipSync, inflateSync } from "node:zlib";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const E2E = join(HERE, "..", "..");

function pack(width, height, rgba) {
  const header = Buffer.from(`RGBA8 ${width} ${height}\n`, "ascii");
  const body = Buffer.concat([header, Buffer.from(rgba.buffer, rgba.byteOffset, rgba.byteLength)]);
  return { body, gz: gzipSync(body, { level: 9 }) };
}

const sha256 = (buf) => createHash("sha256").update(buf).digest("hex");

function decodePng(bytes) {
  if (bytes.readUInt32BE(0) !== 0x89504e47) throw new Error("not a PNG");
  let pos = 8;
  let width = 0;
  let height = 0;
  let depth = 0;
  let colour = 0;
  let interlace = 0;
  const idat = [];
  while (pos < bytes.length) {
    const len = bytes.readUInt32BE(pos);
    const type = bytes.toString("ascii", pos + 4, pos + 8);
    const data = bytes.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      depth = data[8];
      colour = data[9];
      interlace = data[12];
    } else if (type === "IDAT") {
      idat.push(data);
    } else if (type === "IEND") {
      break;
    }
    pos += 12 + len;
  }
  if (depth !== 8 || interlace !== 0 || (colour !== 0 && colour !== 6)) {
    throw new Error(`unsupported PNG: depth=${depth} colour=${colour} interlace=${interlace}`);
  }
  const bpp = colour === 0 ? 1 : 4;
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * bpp;
  const out = new Uint8ClampedArray(width * height * 4);
  const prev = new Uint8Array(stride);
  const cur = new Uint8Array(stride);
  let r = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[r++];
    for (let x = 0; x < stride; x++) {
      const rawByte = raw[r + x];
      const a = x >= bpp ? cur[x - bpp] : 0;
      const b = prev[x];
      const c = x >= bpp ? prev[x - bpp] : 0;
      let v;
      if (filter === 0) v = rawByte;
      else if (filter === 1) v = rawByte + a;
      else if (filter === 2) v = rawByte + b;
      else if (filter === 3) v = rawByte + ((a + b) >> 1);
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        v = rawByte + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      } else {
        throw new Error(`bad filter ${filter}`);
      }
      cur[x] = v & 0xff;
    }
    for (let x = 0; x < width; x++) {
      const o = (y * width + x) * 4;
      if (colour === 0) {
        const g = cur[x];
        out[o] = g;
        out[o + 1] = g;
        out[o + 2] = g;
        out[o + 3] = 255;
      } else {
        out[o] = cur[x * 4];
        out[o + 1] = cur[x * 4 + 1];
        out[o + 2] = cur[x * 4 + 2];
        out[o + 3] = cur[x * 4 + 3];
      }
    }
    prev.set(cur);
    r += stride;
  }
  return { width, height, data: out };
}

function halve({ width, height, data }) {
  const w = width >> 1;
  const h = height >> 1;
  const out = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      const s0 = (y * 2 * width + x * 2) * 4;
      const s1 = (y * 2 * width + x * 2 + 1) * 4;
      const s2 = ((y * 2 + 1) * width + x * 2) * 4;
      const s3 = ((y * 2 + 1) * width + x * 2 + 1) * 4;
      for (let ch = 0; ch < 4; ch++) {
        out[o + ch] = (data[s0 + ch] + data[s1 + ch] + data[s2 + ch] + data[s3 + ch] + 2) >> 2;
      }
    }
  }
  return { width: w, height: h, data: out };
}

function disc(w, h) {
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
  return { width: w, height: h, data };
}

function blankSheet(w, h) {
  const data = new Uint8ClampedArray(w * h * 4);
  data.fill(255);
  return { width: w, height: h, data };
}

function allBlack(w, h) {
  const data = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < data.length; i += 4) {
    data[i] = 0;
    data[i + 1] = 0;
    data[i + 2] = 0;
    data[i + 3] = 255;
  }
  return { width: w, height: h, data };
}

function onePixel() {
  return { width: 1, height: 1, data: new Uint8ClampedArray([128, 128, 128, 255]) };
}

function hairline(w, h) {
  const data = new Uint8ClampedArray(w * h * 4);
  data.fill(255);
  const mid = h >> 1;
  for (let x = 0; x < w; x++) {
    const i = (mid * w + x) * 4;
    data[i] = 0;
    data[i + 1] = 0;
    data[i + 2] = 0;
  }
  for (const x of [7, w - 8]) {
    for (let y = 0; y < h; y++) {
      const i = (y * w + x) * 4;
      data[i] = 0;
      data[i + 1] = 0;
      data[i + 2] = 0;
    }
  }
  return { width: w, height: h, data };
}

const photoSrc = decodePng(readFileSync(join(E2E, "fixtures", "sketch-on-table.png")));
const photo = halve(halve(photoSrc));

const CASES = [
  ["disc-256x192", disc(256, 192), "The oracle's own disc. One closed outline whose bounding box is arithmetic."],
  ["disc-1024x768", disc(1024, 768), "The oracle's preview input: large enough that a 256 px preview really scales."],
  ["blank-256x192", blankSheet(256, 192), "An empty sheet. Every stage below the edge detector gets an empty mask."],
  [
    "sketch-photo-400x300",
    photo,
    "A photographed sketch: tilted sheet, illumination ramp, drawn strokes. From e2e/fixtures/sketch-on-table.png (1600x1200, 8-bit grey, non-interlaced), halved twice with integer 2x2 box averages."
  ],
  ["all-black-320x240", allBlack(320, 240), "Pathological: opaque black everywhere. No background, so every threshold degenerates."],
  ["one-pixel-1x1", onePixel(), "Pathological: one mid-grey pixel, smaller than every kernel the pipeline convolves with."],
  ["hairline-2048x3", hairline(2048, 3), "Pathological aspect (682:1) with a 1 px rule and two 1 px columns."]
];

const lines = [
  "# Parity fixtures — SHA-256 of the DECOMPRESSED bytes, which is what the engine sees.",
  "#",
  "# The .gz is NOT hashed: DEFLATE compression is not reproducible between implementations, so a",
  "# hash of the compressed file would go red for a reason that has nothing to do with the pixels.",
  "# engine/pngEncoder.ts:19-22 draws the same distinction about its own encoder and says what the",
  "# parity fixtures compare: \"the decoded image, which is exact\".",
  "#",
  "# Regenerate:  node e2e/fixtures/trace-parity/make-fixtures.mjs   (from frontend/)",
  "# Verified by: e2e/trace-parity-unit.spec.ts on load, and the Android half MUST do the same",
  "#              before it traces — see that spec's ANDROID CONTRACT section.",
  "#",
  "# <sha256 of raw RGBA>  <file>  <width>x<height>  <raw bytes>",
  ""
];

for (const [name, img, note] of CASES) {
  const { body, gz } = pack(img.width, img.height, img.data);
  const raw = Buffer.from(img.data.buffer, img.data.byteOffset, img.data.byteLength);
  writeFileSync(join(HERE, `${name}.rgba.gz`), gz);
  lines.push(`# ${note}`);
  lines.push(`${sha256(raw)}  ${name}.rgba.gz  ${img.width}x${img.height}  ${raw.length}`);
  lines.push("");
  console.log(
    `${name.padEnd(24)} ${String(img.width).padStart(5)}x${String(img.height).padEnd(5)}` +
      ` raw=${String(raw.length).padStart(9)} gz=${String(gz.length).padStart(8)} container=${body.length}`
  );
}

writeFileSync(join(HERE, "MANIFEST.txt"), lines.join("\n"));
console.log(`\nwrote ${CASES.length} fixtures + MANIFEST.txt`);
