import { deflateSync } from "node:zlib";

import { type GreyPlane } from "@/lib/imageQuality";
import { applyHomography, solveHomography, type Point } from "@/lib/photoMeasure";

export type Quad = [Point, Point, Point, Point];

export type PhotographOptions = {
  gradient?: boolean;
  glare?: { x: number; y: number; radius: number };
  background?: number;
};

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

function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typed = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typed), 0);
  return Buffer.concat([length, typed, crc]);
}

export function greyPng(plane: GreyPlane): Buffer {
  const { width, height, data } = plane;

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.writeUInt8(8, 8);
  header.writeUInt8(0, 9);
  header.writeUInt8(0, 10);
  header.writeUInt8(0, 11);
  header.writeUInt8(0, 12);

  const raw = Buffer.alloc((width + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0;
    for (let x = 0; x < width; x++) raw[y * (width + 1) + 1 + x] = data[y * width + x];
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(raw, { level: 6 })),
    chunk("IEND", Buffer.alloc(0))
  ]);
}
