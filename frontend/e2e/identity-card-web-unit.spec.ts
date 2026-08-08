import { expect, test } from "@playwright/test";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { IDENTITY_MAX_EDGE, identityUploadPlan } from "@/lib/identityCardImage";
import {
  groupAadhaarForReading,
  identityCandidatesFromText,
  maskIdentityNumber
} from "@/lib/identityCardText";

/**
 * The browser's own reading of a card: what it will offer a designer, and what it refuses.
 *
 * PURE NODE. No server, no browser, no camera — every assertion here is about the rule that stands
 * between a recogniser's guess and `Artisan.aadhaarNumber`, which is the repository's deduplication
 * key. That rule has to be decidable without a device, or it can only be tested by photographing
 * cards, which is not a thing a build machine can do.
 *
 * ── THE DEFECT THIS FILE EXISTS FOR ───────────────────────────────────────────────────────────
 *
 * An Aadhaar card prints, next to the number, a SIXTEEN-digit VID: "VID : 2345 6789 0124 0831".
 * The server matched candidates with `(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])`, whose
 * lookarounds see only the immediately adjacent CHARACTER — and the character after the twelfth
 * digit of a grouped VID is a SPACE. So "234567890124" was extracted as an Aadhaar candidate off a
 * card whose text carries no Aadhaar number at all.
 *
 * Verhoeff was the only thing standing behind it, and it does not stand: measured over 200,000
 * Verhoeff-valid sixteen-digit numbers, **10.02% have a twelve-digit prefix that also satisfies
 * Verhoeff**. Nor does the human confirmation, which is this feature's whole safety net — the panel
 * prints "2345 6789 0124" and the designer finds those exact twelve digits, in that order, printed
 * on the card in their hand. They confirm, and a number belonging to somebody else becomes an
 * artisan's identity.
 *
 * `backend/tests/test_workshop_transcripts.py::test_a_longer_digit_run_is_not_mined_for_twelve_digit_windows`
 * guarded this and passed, because it used a CONTIGUOUS sixteen-digit run. Cards are printed in
 * groups.
 */

/** Verhoeff-valid, does not start 0 or 1 — the number `identity-ocr-unit.spec.ts` also uses. */
const VALID = "234567890124";

/**
 * A VID whose sixteen digits AND whose first twelve digits both satisfy Verhoeff.
 *
 * Constructed rather than found, and not a contrivance: one Verhoeff-valid sixteen-digit number in
 * ten has this property, so this is what one card in ten looks like.
 */
const TRAP_VID = "2345 6789 0124 0831";

const read = (text: string) => identityCandidatesFromText(text, aadhaarValidationError);

test("a grouped VID does not yield its own first twelve digits", () => {
  const outcome = read(`Ramesh Kumar Meena\nVID : ${TRAP_VID}\nBagru, Jaipur\n`);
  expect(outcome.aadhaar).toEqual([]);
  // And it is not counted as a misread. `rejectedCount` drives the sentence "N numbers were read
  // off that card and every one failed its checksum, so at least one digit was wrong in each" —
  // which would send a designer to re-photograph a card that was photographed perfectly.
  expect(outcome.rejectedCount).toBe(0);
});

test("the other numbers printed on a card are refused before any checksum runs", () => {
  // Every one of these is a real thing an Aadhaar card carries, and every one is a run of digits.
  for (const text of [
    "Enrolment No.: 1234 56789 01234",
    "Bagru, Jaipur, Rajasthan 303007",
    "DOB: 14/08/1979",
    "VID : 9123 4567 8901 2345"
  ]) {
    const outcome = read(text);
    expect(outcome.aadhaar, text).toEqual([]);
    expect(outcome.rejectedCount, text).toBe(0);
  }
});

test("the number itself is read however the card groups it, and offered once", () => {
  expect(read(`Aadhaar No. ${VALID}`).aadhaar).toEqual([VALID]);
  expect(read("2345 6789 0124").aadhaar).toEqual([VALID]);
  expect(read("2345-6789-0124").aadhaar).toEqual([VALID]);
  // The same card read twice — front and back, or two detected blocks — is one candidate.
  expect(read(`${VALID} / 2345-6789-0124`).aadhaar).toEqual([VALID]);
});

test("a misread digit is refused and COUNTED, because that is a different next action", () => {
  // One digit of a valid number changed: twelve digits, starts 2-9, belongs to nobody. Exactly the
  // shape of an OCR misread, and exactly the shape a duplicate artisan is made of.
  const broken = `${VALID.slice(0, -1)}3`;
  expect(aadhaarValidationError(broken)).not.toBeNull();
  const outcome = read(`AADHAAR ${broken}`);
  expect(outcome.aadhaar).toEqual([]);
  // "The card was found and misread" — better light, no glare — as opposed to "nothing was found",
  // which means fill the frame. The count is the only thing that separates the two sentences.
  expect(outcome.rejectedCount).toBe(1);
  // A leading 0 or 1 is a bad crop that swallowed a digit; UIDAI never issues one.
  expect(read("134567890124").rejectedCount).toBe(1);
  expect(read("034567890124").rejectedCount).toBe(1);
});

test("Devanagari digits are not digits", () => {
  // `\d` under the `u` flag and `str.isdigit()` both answer true for "२३४५६७८९०१२३". Stored
  // verbatim it would sit in the unique index as a different string from its ASCII spelling — the
  // same artisan, twice, which is the one thing this column exists to prevent.
  expect(read("२३४५६७८९०१२३").aadhaar).toEqual([]);
  expect(read("२३४५६७८९०१२३").rejectedCount).toBe(0);
});

/**
 * THE AGREEMENT TABLE.
 *
 * The right-hand side is the VERBATIM output of `backend/app/services/identity_ocr.aadhaar_candidates`,
 * printed by running it under the backend venv on 2026-08-09 — not transcribed from reading the
 * Python. Two ports of one rule drift silently; the only defence that survives a year is a fixture
 * generated from one side and asserted on the other.
 *
 * `whole_card` is not invented either: it is the text Tesseract actually returned from a rendered
 * card in the measurement recorded in `docs/DECISION-identity-card-ocr-on-web.md`, including the
 * VID, the enrolment number and the pin code it read alongside the number.
 */
const SERVER_FIXTURES: Record<string, { text: string; aadhaar: string[]; rejected: number }> = {
  plain: { text: "Aadhaar No. 234567890124", aadhaar: ["234567890124"], rejected: 0 },
  grouped: { text: "2345 6789 0124", aadhaar: ["234567890124"], rejected: 0 },
  hyphenated: { text: "2345-6789-0124", aadhaar: ["234567890124"], rejected: 0 },
  vid_only: {
    text: "Ramesh Kumar Meena\nVID : 2345 6789 0124 0831\nBagru, Jaipur\n",
    aadhaar: [],
    rejected: 0
  },
  enrolment: { text: "Enrolment No.: 1234 56789 01234", aadhaar: [], rejected: 0 },
  pincode: { text: "Bagru, Jaipur, Rajasthan 303007", aadhaar: [], rejected: 0 },
  dob: { text: "DOB: 14/08/1979", aadhaar: [], rejected: 0 },
  misread: { text: "AADHAAR 234567890123", aadhaar: [], rejected: 1 },
  twice: { text: "234567890124 / 2345-6789-0124", aadhaar: ["234567890124"], rejected: 0 },
  devanagari: { text: "२३४५६७८९०१२३", aadhaar: [], rejected: 0 },
  leading_one: { text: "134567890124", aadhaar: [], rejected: 1 },
  whole_card: {
    text:
      "Government of India\nRamesh Kumar Meena\nDOB: 14/08/1979\nMale\n" +
      "VID : 9123 4567 8901 2345\nEnrolment No.: 1234/56789/01234\n" +
      "Address: Bagru, Jaipur, Rajasthan 303007\n2345 6789 0124\nAadhaar - Aam Aadmi ka Adhikar\n",
    aadhaar: ["234567890124"],
    rejected: 0
  }
};

test("the browser's rule and the server's rule answer identically", () => {
  for (const [name, expected] of Object.entries(SERVER_FIXTURES)) {
    const outcome = read(expected.text);
    expect(outcome.aadhaar, name).toEqual(expected.aadhaar);
    expect(outcome.rejectedCount, name).toBe(expected.rejected);
  }
});

test("nothing but the confirm panel is given a number to print", () => {
  // Masking is not "hide most of it": anything short of a full number is masked ENTIRELY, so a
  // malformed value can never leak more than a well-formed one leaks.
  expect(maskIdentityNumber(VALID)).toBe("XXXX XXXX 0124");
  expect(maskIdentityNumber("2345 6789 0124")).toBe("XXXX XXXX 0124");
  expect(maskIdentityNumber("012")).toBe("XXXX XXXX XXXX");
  expect(maskIdentityNumber(null)).toBe("XXXX XXXX XXXX");
  expect(maskIdentityNumber("")).toBe("XXXX XXXX XXXX");
  // Same rule as `mask_aadhaar` and `isMaskedIdentityNumber`: an X anywhere means a mask, so a
  // masked value can never be mistaken for a number by the round trip that posts it back.
  expect(/x/i.test(maskIdentityNumber(VALID))).toBe(true);

  // The confirm panel is the one surface that prints it in full, and it prints it the way the card
  // does, because a designer compares group by group and a dropped digit is visible in
  // "1234 5678 901" in a way it is not in "123456789 01".
  expect(groupAadhaarForReading(VALID)).toBe("2345 6789 0124");
});

test("a card photograph is scaled and re-encoded before it is sent anywhere", () => {
  // A 12-megapixel handset photo. The plan is what decides whether a guest-house hotspot carries
  // four megabytes or a few hundred kilobytes.
  expect(identityUploadPlan(4032, 3024)).toEqual({ width: 2000, height: 1500, scaled: true });
  // Portrait is the same factor on the same edge — the longest one, whichever it is.
  expect(identityUploadPlan(3024, 4032)).toEqual({ width: 1500, height: 2000, scaled: true });
  // Already small enough: no scaling, and the caller still re-encodes, because the EXIF block on a
  // small photograph carries the same GPS fix as on a large one.
  expect(identityUploadPlan(1600, 1200)).toEqual({ width: 1600, height: 1200, scaled: false });
  expect(identityUploadPlan(IDENTITY_MAX_EDGE, 1)).toEqual({
    width: IDENTITY_MAX_EDGE,
    height: 1,
    scaled: false
  });
  // A decoder that failed halfway hands over a degenerate size; the arithmetic must not invent one.
  expect(identityUploadPlan(0, 0).scaled).toBe(false);
  expect(identityUploadPlan(Number.NaN, 10).scaled).toBe(false);
});
