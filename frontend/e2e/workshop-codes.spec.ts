import { readFileSync } from "node:fs";
import { join } from "node:path";

import { test, expect } from "@playwright/test";

import {
  SUPPORTED_VERSIONS,
  WORKSHOP_CODE_VERSION,
  WORKSHOP_RECORD_TYPES,
  decodeWorkshopCode,
  encodeWorkshopCode,
  formatWorkshopCodeForPrint,
  workshopCodeCheck,
  workshopCodeIdForRow,
  unresolvedWorkshopCodeMessage,
  workshopCodeMatchesRow,
  workshopRecordTypeLabel,
  type WorkshopRecordType
} from "@/lib/workshopCodes";
import { buildQrMatrix, encodeQr, isQrAlphanumeric, qrCapacity, QrEncodeError, qrSvgPath, type QrEccLevel } from "@/lib/qrEncode";
import {
  DESIGN_WORKSHOP_SCAN_COPY,
  designWorkshopScanState,
  workshopCodeOpenHref
} from "@/lib/workshopCodeLookup";
import { LOCAL_ID_PREFIX } from "@/lib/designWorkshopStore";

const ARTISAN_ID = "cmsik2jg8000eh8xc1lcy661a";
const CLIENT_KEY = "3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b";

test("a code round-trips to the same record, and the id comes back in the case the repository stores", () => {
  const encoded = encodeWorkshopCode({ recordType: "artisan", id: ARTISAN_ID });
  expect(encoded.ok).toBe(true);
  if (!encoded.ok) return;

  expect(encoded.code).toBe("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD");

  const decoded = decodeWorkshopCode(encoded.code);
  expect(decoded.ok).toBe(true);
  if (!decoded.ok) return;
  expect(decoded.ref).toEqual({ recordType: "artisan", id: ARTISAN_ID });
});

test("a prototype's client key round-trips, so a tag can be printed before the row has ever synced", () => {
  const encoded = encodeWorkshopCode({ recordType: "prototype", id: CLIENT_KEY });
  expect(encoded.ok).toBe(true);
  if (!encoded.ok) return;
  const decoded = decodeWorkshopCode(encoded.code);
  expect(decoded.ok && decoded.ref).toEqual({ recordType: "prototype", id: CLIENT_KEY });
});

test("what a human does to a printed code is tolerated; what it means is not", () => {
  const code = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD";
  expect(decodeWorkshopCode(code.toLowerCase()).ok).toBe(true);
  expect(decodeWorkshopCode(formatWorkshopCodeForPrint(code)).ok).toBe(true);
  expect(decodeWorkshopCode(`  ${code}\n`).ok).toBe(true);
  expect(decodeWorkshopCode("DPW1:P:CMT0PROTOTYPE0001ABC:5QVC").ok).toBe(true);
  expect(workshopCodeCheck("DPW1:P:CMT0PROTOTYPE0001ABC")).toBe("5QVC");
});

test("a corrupted code is refused, and refused for the right reason", () => {
  const good = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD";

  const oneOff = decodeWorkshopCode(good.replace("CMSIK2JG8", "CMSIK2JG9"));
  expect(oneOff.ok).toBe(false);
  expect(oneOff.ok === false && oneOff.reason).toBe("CHECK_FAILED");

  const short = decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661:NEWD");
  expect(short.ok === false && short.reason).toBe("CHECK_FAILED");
  expect(decodeWorkshopCode("DPW1:A:CM:NEWD")).toMatchObject({ ok: false, reason: "MALFORMED" });

  for (const foreign of ["https://example.org/artisans/abc", "4006381333931", "", "   "]) {
    const result = decodeWorkshopCode(foreign);
    expect(result.ok).toBe(false);
    expect(result.ok === false && result.reason).not.toBe("CHECK_FAILED");
  }
  expect(decodeWorkshopCode("https://example.org/x")).toMatchObject({ ok: false, reason: "NOT_A_WORKSHOP_CODE" });
  expect(decodeWorkshopCode(null)).toMatchObject({ ok: false, reason: "EMPTY" });
});

test("versions: this build writes 1, reads every version it declares, and says so about a newer one", () => {
  expect(WORKSHOP_CODE_VERSION).toBe(1);
  expect(SUPPORTED_VERSIONS.has(1)).toBe(true);

  const encoded = encodeWorkshopCode({ recordType: "artisan", id: ARTISAN_ID });
  expect(encoded.ok && encoded.code.startsWith("DPW1:")).toBe(true);

  const future = `DPW9:A:${ARTISAN_ID.toUpperCase()}:${workshopCodeCheck(`DPW9:A:${ARTISAN_ID.toUpperCase()}`)}`;
  const decodedFuture = decodeWorkshopCode(future);
  expect(decodedFuture.ok).toBe(false);
  expect(decodedFuture.ok === false && decodedFuture.reason).toBe("NEWER_VERSION");
  expect(decodedFuture.ok === false && decodedFuture.message).toContain("newer version");

  const unknownType = `DPW1:Z:${ARTISAN_ID.toUpperCase()}:${workshopCodeCheck(`DPW1:Z:${ARTISAN_ID.toUpperCase()}`)}`;
  expect(decodeWorkshopCode(unknownType)).toMatchObject({ ok: false, reason: "UNKNOWN_RECORD_TYPE" });
});

test("no sensitive field can be encoded, whatever a caller hands over", () => {
  const aadhaar = encodeWorkshopCode({ recordType: "artisan", id: "234567890123" });
  expect(aadhaar.ok).toBe(false);
  expect(aadhaar.ok === false && aadhaar.reason).toBe("ID_LOOKS_SENSITIVE");
  expect(encodeWorkshopCode({ recordType: "artisan", id: "2345 6789 0123" }).ok).toBe(false);
  expect(encodeWorkshopCode({ recordType: "artisan", id: "XXXX XXXX 9012" }).ok).toBe(false);
  expect(encodeWorkshopCode({ recordType: "artisan", id: "VW1234567890" }).ok).toBe(false);

  for (const value of ["Ram Kumar", "ram@example.org", "+91 98765 43210", "Bagru, Rajasthan", "ram", "", null, undefined]) {
    expect(encodeWorkshopCode({ recordType: "artisan", id: value }).ok).toBe(false);
  }

  expect(encodeWorkshopCode({ recordType: "artisan", id: "CmSik2jg8000eh8xc1lcy661a" }).ok).toBe(false);
  for (const notARecord of ["designer", "location", "user", "review", "", "Artisan"]) {
    const refused = encodeWorkshopCode({ recordType: notARecord, id: ARTISAN_ID });
    expect(`${notARecord}=${refused.ok}`).toBe(`${notARecord}=false`);
    expect(refused.ok === false && refused.reason).toBe("UNKNOWN_RECORD_TYPE");
  }
});

test("every record type this repository issues has a letter, and no two share one", () => {
  const letters: Record<WorkshopRecordType, string> = {
    artisan: "A",
    craft: "C",
    workshop: "W",
    product: "D",
    process: "S",
    tool: "T",
    questionnaire: "Q",
    media: "M",
    designWorkshop: "G",
    prototype: "P"
  };

  expect([...WORKSHOP_RECORD_TYPES].sort()).toEqual(Object.keys(letters).sort());

  const seen = new Map<string, string>();
  for (const [recordType, letter] of Object.entries(letters) as [WorkshopRecordType, string][]) {
    const encoded = encodeWorkshopCode({ recordType, id: ARTISAN_ID });
    expect(`${recordType}=${encoded.ok}`).toBe(`${recordType}=true`);
    if (!encoded.ok) continue;
    expect(encoded.code).toBe(
      `DPW1:${letter}:${ARTISAN_ID.toUpperCase()}:${workshopCodeCheck(`DPW1:${letter}:${ARTISAN_ID.toUpperCase()}`)}`
    );

    expect(seen.get(letter) ?? recordType).toBe(recordType);
    seen.set(letter, recordType);

    const decoded = decodeWorkshopCode(encoded.code);
    expect(decoded.ok && decoded.ref).toEqual({ recordType, id: ARTISAN_ID });
  }

  for (const letter of Object.values(letters)) expect("ILOU".includes(letter)).toBe(false);
});

test("the anti-PII gate applies to every record type, not only the two it was written for", () => {
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    for (const sensitive of ["234567890123", "2345 6789 0123", "XXXX XXXX 9012", "VW1234567890"]) {
      const refused = encodeWorkshopCode({ recordType, id: sensitive });
      expect(`${recordType}/${sensitive}=${refused.ok}`).toBe(`${recordType}/${sensitive}=false`);
      expect(refused.ok === false && refused.reason).toBe("ID_LOOKS_SENSITIVE");
    }
    expect(encodeWorkshopCode({ recordType, id: null })).toMatchObject({ ok: false, reason: "NO_ID" });
  }
});

test("a cuid from any record type fits the symbol the print box was sized for", () => {
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    const encoded = encodeWorkshopCode({ recordType, id: ARTISAN_ID });
    expect(encoded.ok).toBe(true);
    if (!encoded.ok) continue;
    expect(isQrAlphanumeric(encoded.code)).toBe(true);
    expect(`${recordType}=${encodeQr(encoded.code, "Q").version}`).toBe(`${recordType}=3`);
  }
});

test("every record type is named in words, and a refusal never says the record exists", () => {
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    const label = workshopRecordTypeLabel(recordType);
    expect(label.length).toBeGreaterThan(0);

    const message = unresolvedWorkshopCodeMessage(recordType);
    for (const giveaway of ["permission", "not allowed", "forbidden", "403", "exists", "belongs to another user"]) {
      expect(`${recordType}:${message.toLowerCase().includes(giveaway)}`).toBe(`${recordType}:false`);
    }
  }
  expect(workshopRecordTypeLabel("questionnaire")).toBe("Interview");
});

test("a payload can only contain characters a compact QR symbol carries", () => {
  const encoded = encodeWorkshopCode({ recordType: "prototype", id: CLIENT_KEY });
  expect(encoded.ok && isQrAlphanumeric(encoded.code)).toBe(true);
});

test("a row answers to both the identifiers it is known by", () => {
  const synced = { _entryId: ARTISAN_ID, _clientKey: CLIENT_KEY };
  const unsynced = { _clientKey: CLIENT_KEY };
  expect(workshopCodeIdForRow(synced)).toBe(ARTISAN_ID);
  expect(workshopCodeIdForRow(unsynced)).toBe(CLIENT_KEY);
  expect(workshopCodeIdForRow({})).toBeNull();

  const tag = encodeWorkshopCode({ recordType: "prototype", id: CLIENT_KEY });
  expect(tag.ok).toBe(true);
  if (!tag.ok) return;
  const printedBeforeSync = decodeWorkshopCode(tag.code);
  expect(printedBeforeSync.ok).toBe(true);
  if (!printedBeforeSync.ok) return;
  expect(workshopCodeMatchesRow(printedBeforeSync.ref, synced)).toBe(true);
  expect(workshopCodeMatchesRow(printedBeforeSync.ref, { _entryId: "cmother0000000000000000zz" })).toBe(false);
});

type ReferenceCase = { text: string; version: number; level: QrEccLevel; masks: string[] };

const referenceCases = JSON.parse(readFileSync(join(__dirname, "fixtures", "qr-reference.json"), "utf-8")) as ReferenceCase[];

test("every module of every mask matches an independent encoder", () => {
  expect(referenceCases.length).toBeGreaterThan(3);
  const mismatches: string[] = [];
  for (const entry of referenceCases) {
    for (let mask = 0; mask < 8; mask++) {
      const mine = buildQrMatrix(entry.text, entry.version, entry.level, mask)
        .map((row) => row.map((dark) => (dark ? "1" : "0")).join(""))
        .join("/");
      if (mine !== entry.masks[mask]) mismatches.push(`version ${entry.version}${entry.level}, mask ${mask}, "${entry.text}"`);
    }
  }
  expect(mismatches).toEqual([]);
});

test("the block table is internally consistent with the codeword totals", () => {
  const expected: Record<QrEccLevel, number[]> = {
    L: [25, 47, 77, 114, 154, 195],
    M: [20, 38, 61, 90, 122, 154],
    Q: [16, 29, 47, 67, 87, 108],
    H: [10, 20, 35, 50, 64, 84]
  };
  for (const level of ["L", "M", "Q", "H"] as QrEccLevel[]) {
    for (let version = 1; version <= 6; version++) {
      expect(`${level}${version}=${qrCapacity(version, level)}`).toBe(`${level}${version}=${expected[level][version - 1]}`);
    }
  }
});

test("the symbol picked for a real code is the smallest that fits, at the level printed cards need", () => {
  const artisan = encodeWorkshopCode({ recordType: "artisan", id: ARTISAN_ID });
  expect(artisan.ok).toBe(true);
  if (!artisan.ok) return;
  const symbol = encodeQr(artisan.code, "Q");
  expect(symbol.version).toBe(3);
  expect(symbol.size).toBe(29);
  expect(symbol.matrix.length).toBe(29);
  for (const [row, column] of [
    [0, 0],
    [0, 22],
    [22, 0]
  ]) {
    expect(symbol.matrix[row][column]).toBe(true);
    expect(symbol.matrix[row + 1][column + 1]).toBe(false);
    expect(symbol.matrix[row + 3][column + 3]).toBe(true);
  }

  const prototype = encodeWorkshopCode({ recordType: "prototype", id: CLIENT_KEY });
  expect(prototype.ok && encodeQr(prototype.code, "Q").version).toBe(4);
});

test("a string the encoder cannot carry is refused, never truncated", () => {
  expect(() => encodeQr("X".repeat(qrCapacity(6, "Q") + 1), "Q")).toThrow(QrEncodeError);
  expect(() => encodeQr("lower case", "Q")).toThrow(QrEncodeError);
  expect(() => encodeQr("", "Q")).toThrow(QrEncodeError);
  try {
    encodeQr("X".repeat(200), "Q");
  } catch (error) {
    expect(error instanceof QrEncodeError && error.reason).toBe("TOO_LONG");
  }
});

test("the SVG path covers exactly the dark modules, offset by the quiet zone", () => {
  const symbol = encodeQr("DPW1:P:ABCDEFGH:0000", "M");
  const svg = qrSvgPath(symbol, 4);
  expect(svg.extent).toBe(symbol.size + 8);

  const replay = Array.from({ length: svg.extent }, () => new Array<boolean>(svg.extent).fill(false));
  const command = /M(\d+) (\d+)h(\d+)v1h-\3z/g;
  let drawn = 0;
  for (let match = command.exec(svg.path); match; match = command.exec(svg.path)) {
    for (let offset = 0; offset < Number(match[3]); offset++) replay[Number(match[2])][Number(match[1]) + offset] = true;
    drawn += Number(match[3]);
  }
  expect(svg.path.replace(command, "")).toBe("");
  expect(drawn).toBeGreaterThan(100);
  for (let row = 0; row < svg.extent; row++) {
    for (let column = 0; column < svg.extent; column++) {
      const inside = row >= 4 && column >= 4 && row < 4 + symbol.size && column < 4 + symbol.size;
      expect(`${row},${column}=${replay[row][column]}`).toBe(
        `${row},${column}=${inside ? symbol.matrix[row - 4][column - 4] : false}`
      );
    }
  }
});

test("a design workshop and the repository workshop are different records with different letters", () => {
  const design = encodeWorkshopCode({ recordType: "designWorkshop", id: ARTISAN_ID });
  const repository = encodeWorkshopCode({ recordType: "workshop", id: ARTISAN_ID });
  expect(design.ok && repository.ok).toBe(true);
  if (!design.ok || !repository.ok) return;
  expect(design.code).not.toBe(repository.code);
  expect(design.code.startsWith("DPW1:G:")).toBe(true);
  expect(repository.code.startsWith("DPW1:W:")).toBe(true);

  const back = decodeWorkshopCode(design.code);
  expect(back.ok && back.ref).toEqual({ recordType: "designWorkshop", id: ARTISAN_ID });
  const other = decodeWorkshopCode(repository.code);
  expect(other.ok && other.ref).toEqual({ recordType: "workshop", id: ARTISAN_ID });
});

test("the device-local prefix this grammar refuses is the one the draft store actually mints", () => {
  expect(LOCAL_ID_PREFIX).toBe("dwlocal-");

  const minted = `${LOCAL_ID_PREFIX}0f9a1c33-1c2f-4a0e-9a3b-7c1d2e5f8a90`;
  const refused = encodeWorkshopCode({ recordType: "designWorkshop", id: minted });
  expect(refused.ok).toBe(false);
  expect(refused.ok === false && refused.reason).toBe("ID_IS_DEVICE_LOCAL");
});

test("the handset's device-local prefix is a DIFFERENT string, and this gate covers that one too", () => {
  const kotlin = readFileSync(
    join(__dirname, "..", "..", "android", "app", "src", "main", "java", "com", "designprototype", "workshop", "data", "StageSchema.kt"),
    "utf8"
  );
  const declared = /const val DW_LOCAL_ID_PREFIX: String = "([^"]+)"/.exec(kotlin);
  expect(declared, "StageSchema.kt must still declare DW_LOCAL_ID_PREFIX; this test pins its value").not.toBeNull();
  expect(declared?.[1]).toBe("local-");
  expect(declared?.[1]).not.toBe(LOCAL_ID_PREFIX);

  const handsetId = `${declared?.[1]}2b7e4f10-9d3a-4c81-bf62-15a0c7e93d44`;
  const encoded = encodeWorkshopCode({ recordType: "designWorkshop", id: handsetId });
  expect(encoded.ok).toBe(false);
  expect(encoded.ok === false && encoded.reason).toBe("ID_IS_DEVICE_LOCAL");

  const prefix = `DPW1:G:${handsetId.toUpperCase()}`;
  const decoded = decodeWorkshopCode(`${prefix}:${workshopCodeCheck(prefix)}`);
  expect(decoded.ok).toBe(false);
  expect(decoded.ok === false && decoded.reason).toBe("DEVICE_LOCAL");
});

test("the bare handset prefix is scoped to the design workshop and does not refuse other records", () => {
  const id = "local-2b7e4f10-9d3a-4c81-bf62-15a0c7e93d44";
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    const encoded = encodeWorkshopCode({ recordType, id });
    const expected = recordType === "designWorkshop";
    expect(`${recordType} refused=${encoded.ok === false}`).toBe(`${recordType} refused=${expected}`);
  }
});

test("a workshop that exists only on one device cannot be printed as a code, for any record type", () => {
  const local = "dwlocal-2b7e4f10-9d3a-4c81-bf62-15a0c7e93d44";
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    const refused = encodeWorkshopCode({ recordType, id: local });
    expect(`${recordType}=${refused.ok}`).toBe(`${recordType}=false`);
    expect(`${recordType}=${refused.ok === false && refused.reason}`).toBe(`${recordType}=ID_IS_DEVICE_LOCAL`);
  }

  const attempt = encodeWorkshopCode({ recordType: "designWorkshop", id: local });
  expect(attempt.ok).toBe(false);
  if (attempt.ok) return;
  expect(attempt.message.toLowerCase()).toContain("only on this device");
  expect(attempt.message.toLowerCase()).toContain("sync");
});

test("a hand-typed device-local code is refused on the way in as well, and not as damage", () => {
  const local = "dwlocal-2b7e4f10-9d3a-4c81-bf62-15a0c7e93d44";
  const prefix = `DPW1:G:${local.toUpperCase()}`;
  const typed = `${prefix}:${workshopCodeCheck(prefix)}`;

  const decoded = decodeWorkshopCode(typed);
  expect(decoded.ok).toBe(false);
  expect(decoded.ok === false && decoded.reason).toBe("DEVICE_LOCAL");
});

test("the unresolved message for a design workshop names the next move and never claims a request was sent", () => {
  const message = unresolvedWorkshopCodeMessage("designWorkshop");

  expect(message.toLowerCase()).toContain("admin");

  for (const lie of ["request sent", "requested", "pending", "you have joined", "joined the workshop"]) {
    expect(`${lie}=${message.toLowerCase().includes(lie)}`).toBe(`${lie}=false`);
  }
});

test("a scan never reports a join it cannot know about", () => {
  expect(designWorkshopScanState({ onThisDevice: true, resolution: "offline" })).toBe("ALREADY_ON_THIS_DEVICE");
  expect(designWorkshopScanState({ onThisDevice: false, resolution: "resolved" })).toBe("ACCESS_CONFIRMED");
  expect(designWorkshopScanState({ onThisDevice: false, resolution: "refused" })).toBe("ACCESS_NEEDED");
  expect(designWorkshopScanState({ onThisDevice: false, resolution: "offline" })).toBe("UNKNOWN_NO_SIGNAL");

  expect(designWorkshopScanState({ onThisDevice: true, resolution: "refused" })).toBe("ALREADY_ON_THIS_DEVICE");
});

test("only the two states that can actually open a workshop offer an Open control", () => {
  expect(DESIGN_WORKSHOP_SCAN_COPY.ALREADY_ON_THIS_DEVICE.canOpen).toBe(true);
  expect(DESIGN_WORKSHOP_SCAN_COPY.ACCESS_CONFIRMED.canOpen).toBe(true);
  expect(DESIGN_WORKSHOP_SCAN_COPY.ACCESS_NEEDED.canOpen).toBe(false);
  expect(DESIGN_WORKSHOP_SCAN_COPY.UNKNOWN_NO_SIGNAL.canOpen).toBe(false);

  const details = Object.values(DESIGN_WORKSHOP_SCAN_COPY).map((copy) => copy.detail);
  expect(new Set(details).size).toBe(details.length);
  for (const copy of Object.values(DESIGN_WORKSHOP_SCAN_COPY)) {
    expect(copy.headline.length).toBeGreaterThan(0);
    expect(copy.detail.length).toBeGreaterThan(0);
  }

  for (const [state, copy] of Object.entries(DESIGN_WORKSHOP_SCAN_COPY)) {
    const said = `${copy.headline} ${copy.detail}`.toLowerCase();
    for (const lie of ["joined", "requested", "pending", "request sent", "request has been sent"]) {
      expect(`${state}/${lie}=${said.includes(lie)}`).toBe(`${state}/${lie}=false`);
    }
  }
});

test("a design workshop code opens the design workshop and not the repository workshop", () => {
  expect(workshopCodeOpenHref({ recordType: "designWorkshop", id: ARTISAN_ID })).toBe(
    `/design-workshops/${ARTISAN_ID}`
  );
  expect(workshopCodeOpenHref({ recordType: "workshop", id: ARTISAN_ID })).toBe(`/workshops?edit=${ARTISAN_ID}`);
});
