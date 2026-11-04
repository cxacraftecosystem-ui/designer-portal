import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { photograph, type Quad } from "./support/qrPhotograph";

import { decodeQrFromGrey, decodeQrMatrix } from "@/lib/qrDecode";
import { buildQrMatrix, encodeQr, qrCapacity, type QrEccLevel } from "@/lib/qrEncode";
import { encodeWorkshopCode, decodeWorkshopCode } from "@/lib/workshopCodes";

type ReferenceCase = { text: string; version: number; level: QrEccLevel; why: string; masks: string[] };

const referenceCases = JSON.parse(
  readFileSync(join(__dirname, "fixtures", "qr-decode-reference.json"), "utf-8")
) as ReferenceCase[];

function matrixFromRows(rows: string): boolean[][] {
  return rows.split("/").map((row) => row.split("").map((cell) => cell === "1"));
}

test("every mask of every reference symbol from an independent encoder decodes to its exact text", () => {
  expect(referenceCases.length).toBeGreaterThan(5);
  const wrong: string[] = [];

  for (const entry of referenceCases) {
    for (let mask = 0; mask < 8; mask++) {
      const result = decodeQrMatrix(matrixFromRows(entry.masks[mask]));
      if (!result.ok) {
        wrong.push(`v${entry.version}${entry.level} mask ${mask} (${entry.why}): refused — ${result.reason}`);
        continue;
      }
      if (result.text !== entry.text) {
        wrong.push(`v${entry.version}${entry.level} mask ${mask} (${entry.why}): read "${result.text}"`);
        continue;
      }
      if (result.mask !== mask || result.level !== entry.level || result.version !== entry.version) {
        wrong.push(`v${entry.version}${entry.level} mask ${mask}: reported v${result.version}${result.level} mask ${result.mask}`);
      }
    }
  }

  expect(wrong).toEqual([]);
});

test("the segment modes our own encoder cannot produce are read — byte and numeric", () => {
  const byte = referenceCases.find((entry) => entry.text === "https://example.org/artisans/abc");
  expect(byte, "the byte-mode URL case is missing from the fixture").toBeTruthy();
  const decodedByte = decodeQrMatrix(matrixFromRows((byte as ReferenceCase).masks[0]));
  expect(decodedByte.ok && decodedByte.text).toBe("https://example.org/artisans/abc");

  const numeric = referenceCases.find((entry) => entry.text === "0123456789012345");
  expect(numeric, "the numeric case is missing from the fixture").toBeTruthy();
  const decodedNumeric = decodeQrMatrix(matrixFromRows((numeric as ReferenceCase).masks[3]));
  expect(decodedNumeric.ok && decodedNumeric.text).toBe("0123456789012345");
});

test("a decoded foreign code reaches the payload grammar and is refused as not ours, not as damaged", () => {
  const url = referenceCases.find((entry) => entry.text === "https://example.org/artisans/abc") as ReferenceCase;
  const decoded = decodeQrMatrix(matrixFromRows(url.masks[5]));
  expect(decoded.ok).toBe(true);
  if (!decoded.ok) return;
  const grammar = decodeWorkshopCode(decoded.text);
  expect(grammar).toMatchObject({ ok: false, reason: "NOT_A_WORKSHOP_CODE" });
});

const ARTISAN_ID = "cmsik2jg8000eh8xc1lcy661a";
const CLIENT_KEY = "3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b";

test("every version and level this app can draw round-trips to the exact same string", () => {
  const wrong: string[] = [];
  for (const level of ["L", "M", "Q", "H"] as QrEccLevel[]) {
    for (let version = 1; version <= 6; version++) {
      const capacity = qrCapacity(version, level);
      const text = "DPW1:P:ABCDEFGH:0000".slice(0, Math.min(20, capacity)).padEnd(capacity, "X");
      for (let mask = 0; mask < 8; mask++) {
        const result = decodeQrMatrix(buildQrMatrix(text, version, level, mask));
        if (!result.ok) wrong.push(`v${version}${level} mask ${mask}: refused — ${result.reason}`);
        else if (result.text !== text) wrong.push(`v${version}${level} mask ${mask}: read "${result.text}"`);
      }
    }
  }
  expect(wrong).toEqual([]);
});

test("a real artisan card and an unsynced prototype tag both come back as the record they name", () => {
  for (const [recordType, id] of [
    ["artisan", ARTISAN_ID],
    ["prototype", CLIENT_KEY]
  ] as const) {
    const encoded = encodeWorkshopCode({ recordType, id });
    expect(encoded.ok).toBe(true);
    if (!encoded.ok) return;

    const decoded = decodeQrMatrix(encodeQr(encoded.code, "Q").matrix);
    expect(decoded.ok && decoded.text).toBe(encoded.code);
    expect(decoded.ok && decodeWorkshopCode(decoded.text)).toMatchObject({ ok: true, ref: { recordType, id } });
  }
});

test("a symbol damaged within its error-correction budget still yields the exact identifier", () => {
  const encoded = encodeWorkshopCode({ recordType: "artisan", id: ARTISAN_ID });
  expect(encoded.ok).toBe(true);
  if (!encoded.ok) return;

  const symbol = encodeQr(encoded.code, "Q");
  const damaged = symbol.matrix.map((row) => [...row]);

  for (let row = 14; row < 20; row++) {
    for (let column = 12; column < 20; column++) damaged[row][column] = !damaged[row][column];
  }

  const result = decodeQrMatrix(damaged);
  expect(result.ok && result.text).toBe(encoded.code);
});

test("a symbol damaged BEYOND its budget is refused, and never guesses at an identifier", () => {
  const encoded = encodeWorkshopCode({ recordType: "artisan", id: ARTISAN_ID });
  if (!encoded.ok) return;
  const symbol = encodeQr(encoded.code, "Q");
  const wrecked = symbol.matrix.map((row) => [...row]);

  for (let row = 9; row < 26; row++) {
    for (let column = 9; column < 26; column++) wrecked[row][column] = (row * 7 + column * 3) % 2 === 0;
  }

  const result = decodeQrMatrix(wrecked);
  if (result.ok) expect(result.text).toBe(encoded.code);
  else expect(["DATA_UNRECOVERABLE", "UNREADABLE"]).toContain(result.reason);
});

test("a symbol larger than this app prints is named as foreign, not reported as damaged", () => {
  const size = 17 + 4 * 7;
  const blank = Array.from({ length: size }, () => new Array<boolean>(size).fill(false));
  const result = decodeQrMatrix(blank);
  expect(result.ok).toBe(false);
  if (result.ok) return;
  expect(result.reason).toBe("UNSUPPORTED_VERSION");
  expect(result.message).toMatch(/isn't a workshop code|bigger than any workshop card/i);
});

function cardCode(): string {
  const encoded = encodeWorkshopCode({ recordType: "artisan", id: ARTISAN_ID });
  if (!encoded.ok) throw new Error("the fixture payload no longer encodes");
  return encoded.code;
}

test("a photograph taken straight on decodes to the exact code", () => {
  const code = cardCode();
  const symbol = encodeQr(code, "Q");
  const plane = photograph(symbol.matrix, 520, 400, [
    { x: 120, y: 60 },
    { x: 400, y: 60 },
    { x: 400, y: 340 },
    { x: 120, y: 340 }
  ]);

  const result = decodeQrFromGrey(plane);
  expect(result.ok && result.text).toBe(code);
});

test("a card photographed AT AN ANGLE decodes — the alignment pattern earning its place", () => {
  const code = cardCode();
  const symbol = encodeQr(code, "Q");
  const plane = photograph(symbol.matrix, 640, 480, [
    { x: 96, y: 74 },
    { x: 505, y: 128 },
    { x: 470, y: 430 },
    { x: 132, y: 372 }
  ]);

  const result = decodeQrFromGrey(plane);
  expect(result.ok && result.text).toBe(code);
});

const SCREEN_QUAD: Quad = [
  { x: 150, y: 92 },
  { x: 560, y: 128 },
  { x: 534, y: 452 },
  { x: 176, y: 404 }
];

test("a photograph of a SCREEN — skewed, lit from one side, with glare burnt across its data — decodes", () => {
  const code = cardCode();
  const symbol = encodeQr(code, "H");
  const plane = photograph(symbol.matrix, 700, 520, SCREEN_QUAD, {
    gradient: true,
    glare: { x: 350, y: 270, radius: 52 }
  });

  const result = decodeQrFromGrey(plane);
  expect(result.ok && result.text).toBe(code);
});

test("glare that burns away a FINDER pattern is refused with advice, not read as a wrong code", () => {
  const code = cardCode();
  const symbol = encodeQr(code, "H");
  const plane = photograph(symbol.matrix, 700, 520, SCREEN_QUAD, {
    gradient: true,
    glare: { x: 470, y: 190, radius: 52 }
  });

  const result = decodeQrFromGrey(plane);
  expect(result.ok).toBe(false);
  if (result.ok) return;
  expect(result.reason).toBe("NO_SYMBOL");
  expect(result.message).toMatch(/frame|focus|crop|corner/i);
});

test("a code occupying a small corner of a large photograph is found and read", () => {
  const code = cardCode();
  const symbol = encodeQr(code, "Q");
  const plane = photograph(symbol.matrix, 1400, 1000, [
    { x: 980, y: 690 },
    { x: 1170, y: 700 },
    { x: 1166, y: 886 },
    { x: 978, y: 878 }
  ]);

  const result = decodeQrFromGrey(plane);
  expect(result.ok && result.text).toBe(code);
});

test("a code photographed upside down, and one photographed mirrored, both decode", () => {
  const code = cardCode();
  const symbol = encodeQr(code, "Q");

  const upsideDown = photograph(symbol.matrix, 520, 400, [
    { x: 400, y: 340 },
    { x: 120, y: 340 },
    { x: 120, y: 60 },
    { x: 400, y: 60 }
  ]);
  expect(decodeQrFromGrey(upsideDown).ok && (decodeQrFromGrey(upsideDown) as { text: string }).text).toBe(code);

  const mirrored = photograph(symbol.matrix, 520, 400, [
    { x: 400, y: 60 },
    { x: 120, y: 60 },
    { x: 120, y: 340 },
    { x: 400, y: 340 }
  ]);
  const result = decodeQrFromGrey(mirrored);
  expect(result.ok && result.text).toBe(code);
});

test("a picture with no QR code in it is refused with a sentence that names the next action", () => {
  const width = 480;
  const height = 360;
  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const bands = 120 + 90 * Math.sin((x + y) / 9);
      const blob = 40 * Math.exp(-(((x - 300) ** 2 + (y - 120) ** 2) / 3600));
      data[y * width + x] = bands + blob;
    }
  }

  const result = decodeQrFromGrey({ data, width, height });
  expect(result.ok).toBe(false);
  if (result.ok) return;
  expect(result.reason).toBe("NO_SYMBOL");
  expect(result.message).toMatch(/frame|focus|crop/i);
  expect(result.message.length).toBeGreaterThan(40);
});

test("an image too small to hold a symbol is refused as too small, not as containing no code", () => {
  const data = new Uint8ClampedArray(12 * 12).fill(200);
  const result = decodeQrFromGrey({ data, width: 12, height: 12 });
  expect(result.ok).toBe(false);
  if (result.ok) return;
  expect(result.message).toMatch(/too small/i);
});

test("a located but unreadable symbol reports WHERE it was, so a bigger copy can be cut out", () => {
  const code = cardCode();
  const symbol = encodeQr(code, "Q");
  const quad: Quad = [
    { x: 700, y: 500 },
    { x: 860, y: 500 },
    { x: 860, y: 660 },
    { x: 700, y: 660 }
  ];
  const plane = photograph(symbol.matrix, 1200, 900, quad);
  for (let y = 545; y < 615; y++) {
    for (let x = 745; x < 815; x++) plane.data[y * plane.width + x] = (x * 5 + y * 3) % 2 === 0 ? 20 : 235;
  }

  const result = decodeQrFromGrey(plane);
  if (result.ok) return;
  expect(result.region, "a located symbol must report its region").toBeTruthy();
  const region = result.region as { x: number; y: number; width: number; height: number };
  expect(region.x).toBeLessThan(700 / 1200);
  expect(region.x + region.width).toBeGreaterThan(860 / 1200);
  expect(region.y).toBeLessThan(500 / 900);
  expect(region.y + region.height).toBeGreaterThan(660 / 900);
});
