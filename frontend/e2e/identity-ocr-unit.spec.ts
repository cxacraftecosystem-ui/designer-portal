import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import {
  DW_OCR_IDENTITY_PATH,
  identityChoices,
  normalizePehchan,
  type DwIdentityOcrResult
} from "@/lib/designWorkshops";
import { canCreateRecords, canRunDesignWorkshops } from "@/lib/permissions";
import type { User } from "@/lib/types";

const LIVE_PAYLOAD: DwIdentityOcrResult = JSON.parse(`
{
  "aadhaarCandidates": [
    {
      "value": "234567890124",
      "kind": "AADHAAR",
      "confidence": 0.8,
      "masked": "XXXX XXXX 0124"
    }
  ],
  "pehchanCandidates": [],
  "rejectedAadhaarCount": 0,
  "provider": "gemini",
  "requiresConfirmation": true
}
`);

test("the live payload yields the candidate the reader used to miss entirely", () => {
  const choices = identityChoices(LIVE_PAYLOAD, "AADHAAR", aadhaarValidationError);
  expect(choices).toHaveLength(1);
  expect(choices[0].value).toBe("234567890124");
  expect(choices[0].kind).toBe("AADHAAR");
  expect(choices[0].confidence).toBe(0.8);

  expect(identityChoices({ number: "234567890124" } as DwIdentityOcrResult, "AADHAAR", aadhaarValidationError)).toEqual([]);
});

test("a candidate that fails the checksum is refused, not offered with a warning", () => {
  const result: DwIdentityOcrResult = {
    aadhaarCandidates: [{ value: "234567890124" }, { value: "234567890123" }],
    pehchanCandidates: []
  };
  const choices = identityChoices(result, "AADHAAR", aadhaarValidationError);
  expect(choices.map((choice) => choice.value)).toEqual(["234567890124"]);
});

test("each field is offered only the candidates it can hold", () => {
  const result: DwIdentityOcrResult = {
    aadhaarCandidates: [{ value: "2345 6789 0124", kind: "AADHAAR" }],
    pehchanCandidates: [{ value: "pm-vw/12345", kind: "PEHCHAN" }]
  };

  expect(identityChoices(result, "AADHAAR", aadhaarValidationError).map((c) => c.value)).toEqual(["234567890124"]);
  expect(identityChoices(result, "PEHCHAN", aadhaarValidationError).map((c) => c.value)).toEqual(["PMVW12345"]);
  expect(identityChoices(result, "ANY", aadhaarValidationError).map((c) => c.kind)).toEqual(["AADHAAR", "PEHCHAN"]);
});

test("the same number read twice is offered once, and a code too short to be a card is refused", () => {
  const twice: DwIdentityOcrResult = {
    aadhaarCandidates: [{ value: "234567890124" }, { value: "2345-6789-0124" }]
  };
  expect(identityChoices(twice, "AADHAAR", aadhaarValidationError)).toHaveLength(1);

  expect(identityChoices({ pehchanCandidates: [{ value: "AB" }] }, "PEHCHAN", aadhaarValidationError)).toEqual([]);
  expect(normalizePehchan("pm vw/123 45")).toBe("PMVW12345");
});

test("the form's guard and the reader's guard are different rules, and they do not nest", () => {
  const as = (role: string) => ({ role } as unknown as User);

  for (const role of ["RESEARCHER", "INSPECTOR", "PROFESSOR"]) {
    expect(canCreateRecords(as(role)), `${role} may open the artisan form`).toBe(true);
    expect(canRunDesignWorkshops(as(role)), `${role} must NOT be offered the card reader`).toBe(false);
  }
  for (const role of [
    "DESIGNER",
    "MINISTRY_ADMIN",
    "REGIONAL_DIRECTOR",
    "ASSISTANT_DIRECTOR",
    "ADMIN",
    "MASTER_ADMIN"
  ]) {
    expect(canRunDesignWorkshops(as(role)), `${role} is in the set the endpoint admits`).toBe(true);
  }
  expect(canRunDesignWorkshops(null)).toBe(false);
});

test("an empty answer is an ordinary result, not a crash", () => {
  expect(identityChoices({}, "ANY", aadhaarValidationError)).toEqual([]);
  expect(identityChoices({ aadhaarCandidates: null, pehchanCandidates: null }, "ANY", aadhaarValidationError)).toEqual([]);
  expect(identityChoices({ aadhaarCandidates: [{ value: null }] }, "AADHAAR", aadhaarValidationError)).toEqual([]);
});

test("the reader's capability probe is a GET the server answers, so the form opens with no console error", () => {
  const routes = readFileSync(
    join(__dirname, "..", "..", "backend", "app", "api", "routes", "design_workshops.py"),
    "utf8"
  );
  expect(DW_OCR_IDENTITY_PATH).toBe("/design-workshops/ocr/identity");
  expect(routes).toMatch(/@router\.get\("\/ocr\/identity"\)\r?\nasync def identity_ocr_probe\(/);
  expect(routes).toMatch(/"ocrIdentity": True/);
});
