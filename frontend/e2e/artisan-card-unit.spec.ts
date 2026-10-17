import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { cardCheck, cardShortCode, looksLikeArtisanCard, parseArtisanCard } from "@/lib/artisanCard";
import { classifyScanInput } from "@/lib/scanInput";
import { encodeWorkshopCode } from "@/lib/workshopCodes";

/**
 * The artisan QR card's parse rules on the web, held to `shared/artisan-card-vectors.json` — the
 * same file the backend (`tests/test_artisan_card.py`) and both Android apps read. No browser.
 */
type Vectors = {
  alphabet: string;
  shortCodes: { issuer: string; code: string; check: string; shortCode: string }[];
  parse: ({ input: string; expect: Record<string, unknown> } | { input: string; error: string })[];
  tags: { cases: { payload: string; issuer: string; code: string; keyVersion: number; tag: string }[] };
};

const VECTORS: Vectors = JSON.parse(readFileSync(join(__dirname, "../../shared/artisan-card-vectors.json"), "utf8"));

test("the check and the short code match the shared vectors", () => {
  for (const row of VECTORS.shortCodes) {
    expect(cardCheck(row.issuer, row.code)).toBe(row.check);
    expect(cardShortCode(row.issuer, row.code)).toBe(row.shortCode);
  }
});

test("every parse vector parses, or fails for the stated reason", () => {
  for (const row of VECTORS.parse) {
    const parsed = parseArtisanCard(row.input);
    if ("expect" in row) {
      expect(parsed, row.input).toEqual({ ok: true, card: row.expect });
    } else {
      expect(parsed, row.input).toEqual({ ok: false, reason: row.error });
    }
  }
});

test("every signed payload in the tag vectors parses to its parts (the client never checks the tag)", () => {
  for (const row of VECTORS.tags.cases) {
    expect(parseArtisanCard(row.payload)).toEqual({
      ok: true,
      card: { issuer: row.issuer, code: row.code, keyVersion: row.keyVersion, tag: row.tag, typed: false }
    });
  }
});

test("a scan is tried as an artisan card first, then as a DPW1 code", () => {
  const card = classifyScanInput("D-7K3QM-9XA2F-HQ");
  expect(card.kind).toBe("card");

  const payload = classifyScanInput(VECTORS.tags.cases[0].payload);
  expect(payload.kind).toBe("card");

  // A mistyped card is refused with the server's sentence, never handed to the DPW1 decoder.
  expect(classifyScanInput("D-7K3QM-9XA2F-H0")).toEqual({ kind: "refused", message: "Check the code. One character is wrong." });
  expect(looksLikeArtisanCard("D-7K3QM-9XA2F-H0")).toBe(true);

  // An old DPW1:A card still decodes: old printed cards keep scanning.
  const old = encodeWorkshopCode({ recordType: "artisan", id: "clx0abc1230000example" });
  expect(old.ok).toBe(true);
  if (old.ok) {
    const scanned = classifyScanInput(old.code);
    expect(scanned.kind).toBe("workshop");
    if (scanned.kind === "workshop") expect(scanned.ref).toEqual({ recordType: "artisan", id: "clx0abc1230000example" });
  }

  // Free text is neither, and says so through the DPW1 decoder's own refusal.
  expect(classifyScanInput("hello").kind).toBe("refused");
});

test("the QR holds no record id: a card's payload is only the prefix, issuer, version, code and tag", () => {
  for (const row of VECTORS.tags.cases) {
    expect(row.payload).toMatch(/^CXA1:[DF][1-9]:[0-9A-HJKMNP-TV-Z]{10}:[0-9A-HJKMNP-TV-Z]{16}$/);
  }
});
