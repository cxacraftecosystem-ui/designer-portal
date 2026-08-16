/**
 * Synthetic photographs of QR symbols, and a PNG encoder to hand them to a browser as a real file.
 *
 * WHY THE PHOTOGRAPHS ARE SYNTHETIC — the same argument `e2e/sketch-rectify.spec.ts` and
 * `e2e/photo-measure.spec.ts` make, and `e2e/image-quality.spec.ts` makes about its JPEGs. Every
 * image built here is CONSTRUCTED FROM ITS ANSWER: a known matrix, warped by a homography written
 * down in advance, lit by a gradient chosen in advance, with a glare blob of a chosen radius in a
 * chosen place. "It still decoded" is then a statement about the decoder. A test against a checked-in
 * photograph could only assert that the module still returns whatever it returned the day it was
 * written, could never be made harder on purpose, and would rot silently the first time somebody
 * re-saved the file in an image editor.
 *
 * WHY IT IS HERE AND NOT IN A SPEC. Two specs need it — `qr-decode-unit.spec.ts` drives the pure
 * decoder with a `GreyPlane`, and `workshop-codes-ui.spec.ts` drives the real upload control with a
 * PNG built from the same plane — and two copies of a photograph simulator is how one of them comes
 * to be quietly easier than the other, which would make the UI test pass on an image the decoder
 * would never see in the field.
 */

import { deflateSync } from "node:zlib";

import { type GreyPlane } from "@/lib/imageQuality";
import { applyHomography, solveHomography, type Point } from "@/lib/photoMeasure";

/** Four points in TL, TR, BR, BL order — the order every quad in this repository uses. */
export type Quad = [Point, Point, Point, Point];

export type PhotographOptions = {
  /** Darken one side of the frame, the way a room lit from one wall does. */
  gradient?: boolean;
  /** Burn a saturated elliptical highlight into the picture, the way glare on a SCREEN does. */
  glare?: { x: number; y: number; radius: number };
  /** The grey the picture is outside the card. Defaults to 150 — a table, not a void. */
  background?: number;
};

/**
 * Render a module matrix into a greyscale image as though it had been PHOTOGRAPHED.
 *
 * The symbol is drawn into the quadrilateral `quad`, which need not be a rectangle — that is the
 * whole point, and it is how skew gets into a test. Everything else is deliberately unkind:
 *
 *  - `gradient` darkens one side of the frame, the way a room lit from one wall does;
 *  - `glare` burns a saturated elliptical blob into the picture, the way a specular highlight on a
 *    SCREEN does — which is not recoverable information, so surviving it is a claim about the error
 *    correction rather than about the threshold;
 *  - a three-by-three blur is applied last, because a photograph has a lens and a decoder that only
 *    ever sees hard module edges is a decoder tested on screenshots of itself.
 *
 * The quiet zone is drawn as part of the symbol's own square, four modules wide, because a symbol
 * printed hard against a dark background does not scan and pretending otherwise would make every
 * test easier than reality.
 */
export function photograph(
  matrix: boolean[][],
  width: number,
  height: number,
  quad: Quad,
  options?: PhotographOptions
): GreyPlane {
  const size = matrix.length;
  const quiet = 4;
  const extent = size + quiet * 2;
  const symbolQuad: Point[] = [
    { x: 0, y: 0 },
    { x: extent, y: 0 },
    { x: extent, y: extent },
    { x: 0, y: extent }
  ];
  // image → symbol, so each output pixel can ask which module it fell on.
  const toSymbol = solveHomography(quad, symbolQuad);
  if (!toSymbol) throw new Error("the test's own quadrilateral is degenerate");

  const raw = new Float64Array(width * height);
  const background = options?.background ?? 150;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const point = applyHomography(toSymbol, { x: x + 0.5, y: y + 0.5 });
      let value = background;
      if (point.x >= 0 && point.y >= 0 && point.x < extent && point.y < extent) {
        const column = Math.floor(point.x) - quiet;
        const row = Math.floor(point.y) - quiet;
        const inside = row >= 0 && column >= 0 && row < size && column < size;
        value = inside && matrix[row][column] ? 35 : 225;
      }
      raw[y * width + x] = value;
    }
  }

  if (options?.gradient) {
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        // 55% of full brightness on one edge, 100% on the other. A global threshold cannot survive
        // this and `sketchRectify`'s own spec proves it; a local one cancels it.
        raw[y * width + x] *= 0.55 + (0.45 * x) / width;
      }
    }
  }

  if (options?.glare) {
    const { x: cx, y: cy, radius } = options.glare;
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const falloff = Math.exp(-(((x - cx) ** 2 + (y - cy) ** 2) / (radius * radius)));
        raw[y * width + x] = Math.min(255, raw[y * width + x] + 260 * falloff);
      }
    }
  }

  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      let total = 0;
      let count = 0;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const sx = x + dx;
          const sy = y + dy;
          if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
          total += raw[sy * width + sx];
          count++;
        }
      }
      data[y * width + x] = total / count;
    }
  }
  return { data, width, height };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Encoding a plane as a real PNG
 *
 * WHY A PNG AND NOT A JPEG. A JPEG's chroma subsampling and ringing around hard edges attack a QR
 * symbol at exactly the frequency it lives at, so a JPEG fixture would make every upload test a
 * measurement of the JPEG encoder's quality setting. The decoder's tolerance of real compression is
 * the photograph simulator's blur and glare above; the FILE only has to be a file a browser opens.
 *
 * WHY IT IS HAND-WRITTEN. `frontend/package.json` carries no image library, for the reasons
 * `lib/qrEncode.ts` and `lib/qrDecode.ts` set out at length. A greyscale, non-interlaced, unfiltered
 * PNG is a fixed 8-byte signature and three chunks against a specification frozen in 1996, and Node
 * supplies the only hard part (the zlib stream) in its standard library.
 * ──────────────────────────────────────────────────────────────────────────── */

/** The CRC-32 table PNG specifies, built once at module load from its stated polynomial. */
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes: Buffer): number {
  let c = 0xffffffff;
  for (const byte of bytes) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** One PNG chunk: length, type, data, and the CRC over type and data. */
function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typed = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typed), 0);
  return Buffer.concat([length, typed, crc]);
}

/**
 * A greyscale 8-bit PNG of a luma plane — a real file a real browser really decodes.
 *
 * Colour type 0 (greyscale), bit depth 8, no interlacing, and filter type 0 ("None") on every row.
 * Filtering exists to help the compressor and buys nothing here; leaving it off means the bytes in
 * the file are the bytes of the plane, so a fixture that decodes to something other than what was
 * drawn is a bug in this function and nowhere else.
 */
export function greyPng(plane: GreyPlane): Buffer {
  const { width, height, data } = plane;

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.writeUInt8(8, 8); // bit depth
  header.writeUInt8(0, 9); // colour type: greyscale
  header.writeUInt8(0, 10); // compression: deflate
  header.writeUInt8(0, 11); // filter method: adaptive (with "None" chosen per row below)
  header.writeUInt8(0, 12); // interlace: none

  const raw = Buffer.alloc((width + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0; // filter type "None"
    for (let x = 0; x < width; x++) raw[y * (width + 1) + 1 + x] = data[y * width + x];
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(raw, { level: 6 })),
    chunk("IEND", Buffer.alloc(0))
  ]);
}
