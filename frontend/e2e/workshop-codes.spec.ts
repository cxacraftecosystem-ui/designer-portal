/**
 * The payload grammar and the QR encoder, tested as pure functions — no browser, no server.
 *
 * WHY THESE ARE UNIT TESTS IN A PLAYWRIGHT FILE. This project has no second test runner for the
 * frontend; `lib/marketAnalysis.ts` is exercised the same way, and adding vitest to run four
 * hundred lines of arithmetic would be a build dependency bought for nothing. Playwright's runner
 * executes these in node with the `@/` alias resolved, which is all a pure module needs.
 *
 * WHAT IS NOT TESTED HERE, SAID PLAINLY: **a real camera cannot be driven in CI.** Chromium's
 * `--use-fake-device-for-media-stream` can feed a synthetic video track, but `BarcodeDetector` —
 * the API the scanner actually depends on — is not implemented in the Playwright Chromium build at
 * all, so a "camera scan" test would be a test of a mock of the feature, passing whether or not the
 * scanner works. It would be worse than no test, because it would look like coverage. The scanner's
 * OTHER path — feature detection, the honest refusal, and the manual-entry box that carries the
 * whole feature on a browser without the API — IS driven end to end, in
 * `workshop-codes-ui.spec.ts`. The camera path is verified by hand on a device.
 */

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
/*
  THE DRAFT STORE, IMPORTED FOR ONE CONSTANT — and safe to import in node despite being an IndexedDB
  module, because it touches `indexedDB` only inside functions (`openDb` guards on
  `typeof indexedDB === "undefined"`); `design-workshop-create-gate-unit.spec.ts` already imports it
  the same way. The constant is here so the duplicate declaration in the pure grammar module can be
  pinned against the real one rather than trusted.
*/
import { LOCAL_ID_PREFIX } from "@/lib/designWorkshopStore";

const ARTISAN_ID = "cmsik2jg8000eh8xc1lcy661a";
/** What a prototype row is known by before it has ever reached the server. */
const CLIENT_KEY = "3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b";

/* ────────────────────────────────────────────────────────────────────────────
 * The payload
 * ──────────────────────────────────────────────────────────────────────────── */

test("a code round-trips to the same record, and the id comes back in the case the repository stores", () => {
  const encoded = encodeWorkshopCode({ recordType: "artisan", id: ARTISAN_ID });
  expect(encoded.ok).toBe(true);
  if (!encoded.ok) return;

  // The whole shape is asserted, not just that it round-trips: the printed string is a contract
  // with every card already in a workshop, and a silent change to it strands them.
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
  // Lower case, the grouping formatWorkshopCodeForPrint adds, and stray whitespace all survive.
  expect(decodeWorkshopCode(code.toLowerCase()).ok).toBe(true);
  expect(decodeWorkshopCode(formatWorkshopCodeForPrint(code)).ok).toBe(true);
  expect(decodeWorkshopCode(`  ${code}\n`).ok).toBe(true);
  // Crockford's confusables, applied to the CHECK only: O reads as 0 and I/L as 1.
  expect(decodeWorkshopCode("DPW1:P:CMT0PROTOTYPE0001ABC:5QVC").ok).toBe(true);
  expect(workshopCodeCheck("DPW1:P:CMT0PROTOTYPE0001ABC")).toBe("5QVC");
});

test("a corrupted code is refused, and refused for the right reason", () => {
  const good = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD";

  // ONE character of the id changed. This is the case the check exists for: the mutated id is a
  // perfectly well-formed cuid that collides with nobody, so nothing downstream could ever notice.
  const oneOff = decodeWorkshopCode(good.replace("CMSIK2JG8", "CMSIK2JG9"));
  expect(oneOff.ok).toBe(false);
  expect(oneOff.ok === false && oneOff.reason).toBe("CHECK_FAILED");

  // A tag torn across its last character: still a well-formed identifier, still refused.
  const short = decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661:NEWD");
  expect(short.ok === false && short.reason).toBe("CHECK_FAILED");
  // Short enough that it is no longer an identifier at all, which is a different sentence: there
  // is nothing here to check against the card character by character.
  expect(decodeWorkshopCode("DPW1:A:CM:NEWD")).toMatchObject({ ok: false, reason: "MALFORMED" });

  // Something that is not ours at all. It must not be reported as damaged — a shop barcode is not
  // a broken workshop tag, and telling a designer to "check the card" would be nonsense.
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

  // A card printed by a LATER build. "Update the app" and "the tag is damaged" send a designer to
  // two completely different places, so these must not collapse into one refusal.
  const future = `DPW9:A:${ARTISAN_ID.toUpperCase()}:${workshopCodeCheck(`DPW9:A:${ARTISAN_ID.toUpperCase()}`)}`;
  const decodedFuture = decodeWorkshopCode(future);
  expect(decodedFuture.ok).toBe(false);
  expect(decodedFuture.ok === false && decodedFuture.reason).toBe("NEWER_VERSION");
  expect(decodedFuture.ok === false && decodedFuture.message).toContain("newer version");

  // A record type a later build might add, met by this one.
  const unknownType = `DPW1:Z:${ARTISAN_ID.toUpperCase()}:${workshopCodeCheck(`DPW1:Z:${ARTISAN_ID.toUpperCase()}`)}`;
  expect(decodeWorkshopCode(unknownType)).toMatchObject({ ok: false, reason: "UNKNOWN_RECORD_TYPE" });
});

test("no sensitive field can be encoded, whatever a caller hands over", () => {
  // The one that matters most. Artisan.aadhaarNumber is the repository's deduplication key and is
  // regulated personal data; a QR is public the moment the card is printed.
  const aadhaar = encodeWorkshopCode({ recordType: "artisan", id: "234567890123" });
  expect(aadhaar.ok).toBe(false);
  expect(aadhaar.ok === false && aadhaar.reason).toBe("ID_LOOKS_SENSITIVE");
  // The spaced form people actually type, and the mask the API hands to a caller not entitled to
  // the real number.
  expect(encodeWorkshopCode({ recordType: "artisan", id: "2345 6789 0123" }).ok).toBe(false);
  expect(encodeWorkshopCode({ recordType: "artisan", id: "XXXX XXXX 9012" }).ok).toBe(false);
  // Pehchan, in the shape normalize_pehchan stores it: upper-case alphanumerics, no separators.
  expect(encodeWorkshopCode({ recordType: "artisan", id: "VW1234567890" }).ok).toBe(false);

  // Everything else a caller could wrongly pass, refused by the identifier grammar rather than by
  // a list of banned shapes — a list would only ever cover the fields somebody thought of.
  for (const value of ["Ram Kumar", "ram@example.org", "+91 98765 43210", "Bagru, Rajasthan", "ram", "", null, undefined]) {
    expect(encodeWorkshopCode({ recordType: "artisan", id: value }).ok).toBe(false);
  }

  // An id with a capital in it is refused rather than upper-cased into an id that does not exist:
  // the payload is upper case, so a mixed-case id could not be decoded back to itself.
  expect(encodeWorkshopCode({ recordType: "artisan", id: "CmSik2jg8000eh8xc1lcy661a" }).ok).toBe(false);
  // A record type this build does not print. It used to be "craft" — crafts now carry a letter of
  // their own, so the assertion is re-pointed at things this repository genuinely does not issue
  // codes for rather than dropped: a caller handing over a designer, a location or a free-text label
  // must still be refused, and the refusal must still be UNKNOWN_RECORD_TYPE rather than a silently
  // encoded code with a letter nobody assigned.
  for (const notARecord of ["designer", "location", "user", "review", "", "Artisan"]) {
    const refused = encodeWorkshopCode({ recordType: notARecord, id: ARTISAN_ID });
    expect(`${notARecord}=${refused.ok}`).toBe(`${notARecord}=false`);
    expect(refused.ok === false && refused.reason).toBe("UNKNOWN_RECORD_TYPE");
  }
});

test("every record type this repository issues has a letter, and no two share one", () => {
  // THE FAILURE THIS PREVENTS: a letter meaning one kind of record here and another on the handset
  // produces a code that scans cleanly and opens the WRONG record, with the check digit agreeing all
  // the way. The letters are therefore pinned as values, not merely asserted to exist — changing one
  // strands every card and tag already printed against it.
  const letters: Record<WorkshopRecordType, string> = {
    artisan: "A",
    craft: "C",
    workshop: "W",
    product: "D",
    process: "S",
    tool: "T",
    questionnaire: "Q",
    media: "M",
    // desiGn workshop. `W` is the repository `Workshop` and could not be reused: it is live on tags
    // today, and the rule is that a live letter never moves. Pinned here the day it was chosen, so
    // that it can never be quietly re-pointed at something else afterwards.
    designWorkshop: "G",
    prototype: "P"
  };

  // The list is the whole list: a type added to the grammar and forgotten here fails on length.
  expect([...WORKSHOP_RECORD_TYPES].sort()).toEqual(Object.keys(letters).sort());

  const seen = new Map<string, string>();
  for (const [recordType, letter] of Object.entries(letters) as [WorkshopRecordType, string][]) {
    const encoded = encodeWorkshopCode({ recordType, id: ARTISAN_ID });
    expect(`${recordType}=${encoded.ok}`).toBe(`${recordType}=true`);
    if (!encoded.ok) continue;
    expect(encoded.code).toBe(
      `DPW1:${letter}:${ARTISAN_ID.toUpperCase()}:${workshopCodeCheck(`DPW1:${letter}:${ARTISAN_ID.toUpperCase()}`)}`
    );

    // INJECTIVE. TYPE_FROM_LETTER is inverted from TYPE_LETTER, so a duplicate letter would not be a
    // type error — it would silently drop one record type out of the decoder, and every code of that
    // type would resolve to the other one.
    expect(seen.get(letter) ?? recordType).toBe(recordType);
    seen.set(letter, recordType);

    // And it comes back as the same type, which is the half a printed card depends on.
    const decoded = decodeWorkshopCode(encoded.code);
    expect(decoded.ok && decoded.ref).toEqual({ recordType, id: ARTISAN_ID });
  }

  // None of the letters people misread off a card. The check digit's alphabet drops I, L, O and U;
  // the type letter is typed by the same person in the same box and is NOT confusable-corrected, so
  // it must not use them either.
  for (const letter of Object.values(letters)) expect("ILOU".includes(letter)).toBe(false);
});

test("the anti-PII gate applies to every record type, not only the two it was written for", () => {
  // The gate lives before the letter lookup's success path, so adding a record type cannot route
  // around it — but that is the sort of thing a refactor breaks silently, and the field it would
  // leak is Aadhaar.
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    for (const sensitive of ["234567890123", "2345 6789 0123", "XXXX XXXX 9012", "VW1234567890"]) {
      const refused = encodeWorkshopCode({ recordType, id: sensitive });
      expect(`${recordType}/${sensitive}=${refused.ok}`).toBe(`${recordType}/${sensitive}=false`);
      expect(refused.ok === false && refused.reason).toBe("ID_LOOKS_SENSITIVE");
    }
    // And an unsaved record still refuses with NO_ID rather than encoding an empty reference.
    expect(encodeWorkshopCode({ recordType, id: null })).toMatchObject({ ok: false, reason: "NO_ID" });
  }
});

test("a cuid from any record type fits the symbol the print box was sized for", () => {
  // WorkshopCodeSheet's 26mm box clears 0.6mm per module up to version 4. Every id in this
  // repository is a 25-character cuid (backend/prisma/schema.prisma), and the type letter is one
  // character, so no new record type can push the payload past the version this box was sized for.
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

    // THE RULE THIS PINS: the API answers 404 rather than 403 for a record the caller may not see,
    // so that a card cannot be used to enumerate the repository. The sentence the scanner shows must
    // not undo that by hinting which of the two happened.
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

  // A tag printed on Monday, before the row synced, must still resolve on Friday after it has.
  const tag = encodeWorkshopCode({ recordType: "prototype", id: CLIENT_KEY });
  expect(tag.ok).toBe(true);
  if (!tag.ok) return;
  const printedBeforeSync = decodeWorkshopCode(tag.code);
  expect(printedBeforeSync.ok).toBe(true);
  if (!printedBeforeSync.ok) return;
  expect(workshopCodeMatchesRow(printedBeforeSync.ref, synced)).toBe(true);
  expect(workshopCodeMatchesRow(printedBeforeSync.ref, { _entryId: "cmother0000000000000000zz" })).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The symbol
 * ──────────────────────────────────────────────────────────────────────────── */

type ReferenceCase = { text: string; version: number; level: QrEccLevel; masks: string[] };

/**
 * Reference matrices from a SECOND implementation, so this is a check and not a restatement.
 *
 * Regenerate with the script beside this fixture's provenance note:
 *
 *   backend/.venv/Scripts/python.exe scripts/qr_oracle.py > frontend/e2e/fixtures/qr-reference.json
 *
 * which drives `reportlab.graphics.barcode.qrencoder` — already in `backend/.venv` for the report
 * writer — over the same strings this app prints, and dumps all eight masks per case.
 */
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
  // Every row of BLOCKS is checkable by arithmetic against ISO 18004 table 9, and a transcription
  // slip in it would produce a symbol that is subtly unreadable rather than obviously wrong. The
  // capacities below are the standard's published alphanumeric figures.
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
  // Finder patterns in all three corners, which is the cheapest structural proof the matrix is a
  // symbol rather than noise.
  for (const [row, column] of [
    [0, 0],
    [0, 22],
    [22, 0]
  ]) {
    expect(symbol.matrix[row][column]).toBe(true);
    expect(symbol.matrix[row + 1][column + 1]).toBe(false);
    expect(symbol.matrix[row + 3][column + 3]).toBe(true);
  }

  // An offline prototype's UUID client key needs a bigger symbol, and WorkshopCodeSheet's 26mm box
  // is sized for exactly this one. If this ever exceeds version 4, that box must grow with it.
  const prototype = encodeWorkshopCode({ recordType: "prototype", id: CLIENT_KEY });
  expect(prototype.ok && encodeQr(prototype.code, "Q").version).toBe(4);
});

test("a string the encoder cannot carry is refused, never truncated", () => {
  // A truncated identifier scans cleanly and resolves to the wrong record or to none — silently.
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

  // Replay the path back into a grid and compare it with the matrix it came from. A path that drew
  // one module short, or one module offset, is invisible on screen and unreadable on paper.
  const replay = Array.from({ length: svg.extent }, () => new Array<boolean>(svg.extent).fill(false));
  const command = /M(\d+) (\d+)h(\d+)v1h-\3z/g;
  let drawn = 0;
  for (let match = command.exec(svg.path); match; match = command.exec(svg.path)) {
    for (let offset = 0; offset < Number(match[3]); offset++) replay[Number(match[2])][Number(match[1]) + offset] = true;
    drawn += Number(match[3]);
  }
  // Nothing in the path that the regular expression above did not understand — a path with an
  // unparsed command would compare equal here while drawing something else on paper.
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

/* ────────────────────────────────────────────────────────────────────────────
 * The design workshop, and joining one by code
 * ──────────────────────────────────────────────────────────────────────────── */

test("a design workshop and the repository workshop are different records with different letters", () => {
  // THE FAILURE THIS PREVENTS. `Workshop` and `DesignWorkshop` are two tables with two id spaces and
  // two list pages, and the word "workshop" means either one depending on who is speaking. A single
  // letter serving both would send a scanned card to whichever table the reader guessed.
  const design = encodeWorkshopCode({ recordType: "designWorkshop", id: ARTISAN_ID });
  const repository = encodeWorkshopCode({ recordType: "workshop", id: ARTISAN_ID });
  expect(design.ok && repository.ok).toBe(true);
  if (!design.ok || !repository.ok) return;
  expect(design.code).not.toBe(repository.code);
  expect(design.code.startsWith("DPW1:G:")).toBe(true);
  expect(repository.code.startsWith("DPW1:W:")).toBe(true);

  // And each comes back as ITSELF. This is the half a printed card depends on.
  const back = decodeWorkshopCode(design.code);
  expect(back.ok && back.ref).toEqual({ recordType: "designWorkshop", id: ARTISAN_ID });
  const other = decodeWorkshopCode(repository.code);
  expect(other.ok && other.ref).toEqual({ recordType: "workshop", id: ARTISAN_ID });
});

test("the device-local prefix this grammar refuses is the one the draft store actually mints", () => {
  /*
    `workshopCodes.ts` declares "dwlocal-" itself rather than importing it, because that module is
    pure and the draft store is IndexedDB. This test is the thing that makes the duplication safe:
    it imports BOTH and asserts they agree. If somebody renames the store's prefix, the refusal below
    silently stops firing and a device-local workshop becomes printable again — with no other test in
    the tree noticing, because every one of them would still pass.
  */
  expect(LOCAL_ID_PREFIX).toBe("dwlocal-");

  // And the id the store actually mints is refused, not merely the literal prefix. This is the shape
  // `createLocalDraft` writes: the prefix plus a UUID.
  const minted = `${LOCAL_ID_PREFIX}0f9a1c33-1c2f-4a0e-9a3b-7c1d2e5f8a90`;
  const refused = encodeWorkshopCode({ recordType: "designWorkshop", id: minted });
  expect(refused.ok).toBe(false);
  expect(refused.ok === false && refused.reason).toBe("ID_IS_DEVICE_LOCAL");
});

test("the handset's device-local prefix is a DIFFERENT string, and this gate covers that one too", () => {
  /*
    THE CROSS-CLIENT HALF, READ OFF THE KOTLIN DECLARATION RATHER THAN TRUSTED TO A COMMENT.

    Android does not spell this the way the browser does: `StageSchema.kt` declares
    `DW_LOCAL_ID_PREFIX = "local-"` and `WorkshopListScreen.kt` mints `DW_LOCAL_ID_PREFIX + UUID`, so
    a handset's device-only workshop is `local-<uuid>`. Several Kotlin prose comments say "dwlocal-"
    and are wrong about their own constant, which is exactly why this reads the DECLARATION from disk
    — the same way `design-workshop-designer-access-unit.spec.ts` reads the page it asserts about.
    Believing the prose is how the web gate came to cover one spelling and claim it covered both.
  */
  const kotlin = readFileSync(
    join(__dirname, "..", "..", "android", "app", "src", "main", "java", "com", "designprototype", "workshop", "data", "StageSchema.kt"),
    "utf8"
  );
  const declared = /const val DW_LOCAL_ID_PREFIX: String = "([^"]+)"/.exec(kotlin);
  expect(declared, "StageSchema.kt must still declare DW_LOCAL_ID_PREFIX; this test pins its value").not.toBeNull();
  expect(declared?.[1]).toBe("local-");
  // The two clients disagree, on purpose recorded rather than by accident tolerated. If they are
  // ever unified, this line fails and the gate's second arm can go.
  expect(declared?.[1]).not.toBe(LOCAL_ID_PREFIX);

  // AND THE SHAPE A HANDSET MINTS IS REFUSED, both directions. Before this arm existed,
  // `DPW1:G:LOCAL-…` encoded cleanly and decoded back to a ref with no refusal at all — so a scan
  // landed on "the workshop is there and you have not been added to it yet", which is a confident
  // lie about a workshop that exists on one handset and nowhere else.
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
  /*
    THE ASYMMETRY, PINNED SO IT IS A DECISION AND NOT A DRIFT. "dwlocal-" is refused for every record
    type because nothing in this repository could plausibly issue it. Bare "local-" is eight generic
    characters, and refusing it everywhere would mean a real record whose id merely began that way
    could never be printed — a worse failure than the one being prevented, and a silent one. A
    device-local WORKSHOP is the only record either client mints under it.
  */
  const id = "local-2b7e4f10-9d3a-4c81-bf62-15a0c7e93d44";
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    const encoded = encodeWorkshopCode({ recordType, id });
    const expected = recordType === "designWorkshop";
    expect(`${recordType} refused=${encoded.ok === false}`).toBe(`${recordType} refused=${expected}`);
  }
});

test("a workshop that exists only on one device cannot be printed as a code, for any record type", () => {
  /*
    THE DIVERGENCE CASE, WHICH IS THE WHOLE POINT OF THE FEATURE.

    A `dwlocal-` id passes ID_PATTERN perfectly — lower case, hyphenated, the right length — so
    without a dedicated gate it prints a clean, checksummed, scannable code that resolves on exactly
    one device for ever. The colleague who scans it is told there is no such workshop and starts
    their own, which is the two-rival-copies outcome the code was supposed to prevent, reached
    THROUGH the code.
  */
  const local = "dwlocal-2b7e4f10-9d3a-4c81-bf62-15a0c7e93d44";
  for (const recordType of WORKSHOP_RECORD_TYPES) {
    const refused = encodeWorkshopCode({ recordType, id: local });
    expect(`${recordType}=${refused.ok}`).toBe(`${recordType}=false`);
    // Named, not merely refused: "that is not an identifier" would send a designer looking for a
    // malformed id, when the truth is that the workshop simply has not been shared yet.
    expect(`${recordType}=${refused.ok === false && refused.reason}`).toBe(`${recordType}=ID_IS_DEVICE_LOCAL`);
  }

  // The refusal says what to do, and does not blame the designer or the card.
  const attempt = encodeWorkshopCode({ recordType: "designWorkshop", id: local });
  expect(attempt.ok).toBe(false);
  if (attempt.ok) return;
  expect(attempt.message.toLowerCase()).toContain("only on this device");
  expect(attempt.message.toLowerCase()).toContain("sync");
});

test("a hand-typed device-local code is refused on the way in as well, and not as damage", () => {
  // The encoder will not print one, so this is reachable only by somebody typing it — but a refusal
  // that said "this code is damaged, read it off the card again" would send them round a loop that
  // cannot terminate, because the card is fine and the id is real. It is its own answer.
  const local = "dwlocal-2b7e4f10-9d3a-4c81-bf62-15a0c7e93d44";
  const prefix = `DPW1:G:${local.toUpperCase()}`;
  const typed = `${prefix}:${workshopCodeCheck(prefix)}`;

  const decoded = decodeWorkshopCode(typed);
  expect(decoded.ok).toBe(false);
  expect(decoded.ok === false && decoded.reason).toBe("DEVICE_LOCAL");
});

test("the unresolved message for a design workshop names the next move and never claims a request was sent", () => {
  const message = unresolvedWorkshopCodeMessage("designWorkshop");

  // The join case: a colleague has just shared this. Say who can fix it.
  expect(message.toLowerCase()).toContain("admin");

  /*
    AND IT MUST NOT IMPLY A JOIN OR A PENDING REQUEST. Granting access is
    `PUT /design-workshops/{id}/viewers`, which is `Depends(require_admin)`; there is no endpoint a
    designer's scan could queue an access request into, so any wording suggesting one has been
    lodged would leave somebody waiting on an admin who will never be shown anything.
  */
  for (const lie of ["request sent", "requested", "pending", "you have joined", "joined the workshop"]) {
    expect(`${lie}=${message.toLowerCase().includes(lie)}`).toBe(`${lie}=false`);
  }
});

test("a scan never reports a join it cannot know about", () => {
  /*
    THE STATE MACHINE'S REASON FOR EXISTING is the offline row below. A device with no signal and no
    local draft cannot tell whether the scanner is on the workshop, and both confident answers are
    harmful: "joined" is how two devices end up working on rival copies, and "no access" sends a
    designer to bother an admin for a grant they may already hold.
  */
  expect(designWorkshopScanState({ onThisDevice: true, resolution: "offline" })).toBe("ALREADY_ON_THIS_DEVICE");
  expect(designWorkshopScanState({ onThisDevice: false, resolution: "resolved" })).toBe("ACCESS_CONFIRMED");
  expect(designWorkshopScanState({ onThisDevice: false, resolution: "refused" })).toBe("ACCESS_NEEDED");
  expect(designWorkshopScanState({ onThisDevice: false, resolution: "offline" })).toBe("UNKNOWN_NO_SIGNAL");

  // A draft already here beats a refusal. The designer is looking at their own fortnight of stages;
  // telling them they have no access would be false about what is on their screen, and the server
  // still enforces the grant at sync whatever this says.
  expect(designWorkshopScanState({ onThisDevice: true, resolution: "refused" })).toBe("ALREADY_ON_THIS_DEVICE");
});

test("only the two states that can actually open a workshop offer an Open control", () => {
  expect(DESIGN_WORKSHOP_SCAN_COPY.ALREADY_ON_THIS_DEVICE.canOpen).toBe(true);
  expect(DESIGN_WORKSHOP_SCAN_COPY.ACCESS_CONFIRMED.canOpen).toBe(true);
  // Offering "Open" for a workshop the device cannot load is how somebody concludes the app is
  // broken rather than that they are not on the workshop yet.
  expect(DESIGN_WORKSHOP_SCAN_COPY.ACCESS_NEEDED.canOpen).toBe(false);
  expect(DESIGN_WORKSHOP_SCAN_COPY.UNKNOWN_NO_SIGNAL.canOpen).toBe(false);

  // Every state says something, and no two say the same thing — four states that shared a sentence
  // would be a state machine the reader cannot tell they are in.
  const details = Object.values(DESIGN_WORKSHOP_SCAN_COPY).map((copy) => copy.detail);
  expect(new Set(details).size).toBe(details.length);
  for (const copy of Object.values(DESIGN_WORKSHOP_SCAN_COPY)) {
    expect(copy.headline.length).toBeGreaterThan(0);
    expect(copy.detail.length).toBeGreaterThan(0);
  }

  /*
    NO STATE MAY SOUND LIKE A JOIN OR A LODGED REQUEST — all four, not just the offline one.

    This used to check `UNKNOWN_NO_SIGNAL` alone for the single word "joined", which left the two
    states that most invite the wrong wording unguarded: ACCESS_NEEDED is the sentence a designer
    reads when the server refused them, and "we have requested access for you" is the natural,
    comforting and completely false thing to write there. `PUT /design-workshops/{id}/viewers` is
    `Depends(require_admin)` and there is no route a scan could queue a request into, so any of these
    words would leave somebody waiting on an admin who will never be shown anything.
  */
  for (const [state, copy] of Object.entries(DESIGN_WORKSHOP_SCAN_COPY)) {
    const said = `${copy.headline} ${copy.detail}`.toLowerCase();
    for (const lie of ["joined", "requested", "pending", "request sent", "request has been sent"]) {
      expect(`${state}/${lie}=${said.includes(lie)}`).toBe(`${state}/${lie}=false`);
    }
  }
});

test("a design workshop code opens the design workshop and not the repository workshop", () => {
  // Two record types, two routes, and the pair that would be silently swapped if either table were
  // written out twice. `/workshops?edit=` is the craft-documentation record's list page.
  expect(workshopCodeOpenHref({ recordType: "designWorkshop", id: ARTISAN_ID })).toBe(
    `/design-workshops/${ARTISAN_ID}`
  );
  expect(workshopCodeOpenHref({ recordType: "workshop", id: ARTISAN_ID })).toBe(`/workshops?edit=${ARTISAN_ID}`);
});
