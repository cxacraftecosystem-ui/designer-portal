import { expect, test } from "@playwright/test";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { IDENTITY_MAX_EDGE, identityUploadPlan } from "@/lib/identityCardImage";
import {
  groupAadhaarForReading,
  identityCandidatesFromText,
  maskIdentityNumber
} from "@/lib/identityCardText";

const VALID = "234567890124";

const TRAP_VID = "2345 6789 0124 0831";

const read = (text: string) => identityCandidatesFromText(text, aadhaarValidationError);

test("a grouped VID does not yield its own first twelve digits", () => {
  const outcome = read(`Ramesh Kumar Meena\nVID : ${TRAP_VID}\nBagru, Jaipur\n`);
  expect(outcome.aadhaar).toEqual([]);
  expect(outcome.rejectedCount).toBe(0);
});

test("the other numbers printed on a card are refused before any checksum runs", () => {
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
  expect(read(`${VALID} / 2345-6789-0124`).aadhaar).toEqual([VALID]);
});

test("a misread digit is refused and COUNTED, because that is a different next action", () => {
  const broken = `${VALID.slice(0, -1)}3`;
  expect(aadhaarValidationError(broken)).not.toBeNull();
  const outcome = read(`AADHAAR ${broken}`);
  expect(outcome.aadhaar).toEqual([]);
  expect(outcome.rejectedCount).toBe(1);
  expect(read("134567890124").rejectedCount).toBe(1);
  expect(read("034567890124").rejectedCount).toBe(1);
});

test("Devanagari digits are not digits", () => {
  expect(read("२३४५६७८९०१२३").aadhaar).toEqual([]);
  expect(read("२३४५६७८९०१२३").rejectedCount).toBe(0);
});

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
  expect(maskIdentityNumber(VALID)).toBe("XXXX XXXX 0124");
  expect(maskIdentityNumber("2345 6789 0124")).toBe("XXXX XXXX 0124");
  expect(maskIdentityNumber("012")).toBe("XXXX XXXX XXXX");
  expect(maskIdentityNumber(null)).toBe("XXXX XXXX XXXX");
  expect(maskIdentityNumber("")).toBe("XXXX XXXX XXXX");
  expect(/x/i.test(maskIdentityNumber(VALID))).toBe(true);

  expect(groupAadhaarForReading(VALID)).toBe("2345 6789 0124");
});

test("a card photograph is scaled and re-encoded before it is sent anywhere", () => {
  expect(identityUploadPlan(4032, 3024)).toEqual({ width: 2000, height: 1500, scaled: true });
  expect(identityUploadPlan(3024, 4032)).toEqual({ width: 1500, height: 2000, scaled: true });
  expect(identityUploadPlan(1600, 1200)).toEqual({ width: 1600, height: 1200, scaled: false });
  expect(identityUploadPlan(IDENTITY_MAX_EDGE, 1)).toEqual({
    width: IDENTITY_MAX_EDGE,
    height: 1,
    scaled: false
  });
  expect(identityUploadPlan(0, 0).scaled).toBe(false);
  expect(identityUploadPlan(Number.NaN, 10).scaled).toBe(false);
});
