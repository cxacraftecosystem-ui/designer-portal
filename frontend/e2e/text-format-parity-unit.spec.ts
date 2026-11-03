import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  fieldFormatError,
  formatShownByControl,
  isAadhaarMaskValue,
  pincodeOrSpacedValidationError
} from "@/components/designworkshop/stageFieldFormats";
import { pincodeValidationError } from "@/components/forms/LocationFields";
import {
  EMAIL_PATTERN,
  EMAIL_RE,
  isPhoneNumberShaped,
  phoneValidationError,
  splitStoredPhone
} from "@/lib/textFormats";
import type { DwEntity, DwField, DwTextFormat } from "@/lib/designWorkshops";

const VECTOR_TABLE = join(__dirname, "..", "..", "shared", "text-format-vectors.json");

type FormatVector = {
  format: string;
  value: string;
  error: string | null;
  note?: string;
};

const CASES: FormatVector[] = JSON.parse(readFileSync(VECTOR_TABLE, "utf8")).vectors;

function fieldWith(format: string): DwField {
  return { key: "underTest", label: "Under test", type: "TEXT", format: format as DwTextFormat } as DwField;
}

test("the shared table is not empty and covers every format the client knows", () => {
  expect(CASES.length).toBeGreaterThan(30);
  const covered = new Set(CASES.map((row) => row.format));
  for (const format of ["EMAIL", "PHONE_IN", "AADHAAR", "PEHCHAN", "PINCODE"]) {
    expect(covered.has(format), `no case for ${format}`).toBe(true);
  }
});

test("the browser and the handset agree on the shared vector table", () => {
  const disagreements: string[] = [];
  for (const row of CASES) {
    const got = fieldFormatError(fieldWith(row.format), row.value);
    if (got !== row.error) {
      disagreements.push(
        `format=${row.format || "(none)"} value=${JSON.stringify(row.value)}\n` +
          `  expected: ${JSON.stringify(row.error)}\n` +
          `  got:      ${JSON.stringify(got)}` +
          (row.note ? `\n  the case is here because: ${row.note}` : "")
      );
    }
  }
  expect(disagreements.join("\n\n"), "the browser does not match the shared table").toBe("");
});

test("the email rule and the pattern attribute are one rule, not two literals", () => {
  expect(EMAIL_RE.source).toBe(`^${EMAIL_PATTERN}$`);
  expect(new RegExp(`^(?:${EMAIL_PATTERN})$`).test("designer@dch.gov.in")).toBe(true);
  expect(new RegExp(`^(?:${EMAIL_PATTERN})$`).test("designer@dch")).toBe(false);
});

test("a bare ten-digit legacy phone number survives re-coercion", () => {
  expect(splitStoredPhone("9876543210")).toEqual({ iso2: "IN", digits: "9876543210" });
  expect(phoneValidationError("9876543210")).toBeNull();
  expect(phoneValidationError("+91 9876543210")).toBeNull();
  expect(phoneValidationError("987654321")).toBe("Enter a 10-digit number for +91.");
});

test("a stored phone the box cannot show is still refused, and named", () => {
  expect(phoneValidationError("not a number")).toBe("Enter a 10-digit number for +91.");
  expect(isPhoneNumberShaped("not a number")).toBe(false);

  expect(phoneValidationError("abc9876543210def")).toBe("Enter a 10-digit number for +91.");
  expect(isPhoneNumberShaped("abc9876543210def")).toBe(false);

  expect(phoneValidationError("")).toBeNull();
  expect(phoneValidationError("   ")).toBeNull();

  for (const composed of ["+91 9876543210", "+44 20 7946 0958", "9876543210", "+919876543210", "0674-2345678"]) {
    expect(isPhoneNumberShaped(composed), composed).toBe(true);
  }
});

test("the phone control says what is stored when it cannot show it", () => {
  const source = readFileSync(join(__dirname, "..", "components", "forms", "PhoneField.tsx"), "utf8");
  expect(source, "the stored value it cannot show drives the message").toContain(
    "phoneValidationError(unshown || combined)"
  );
  expect(source, "and the value itself is printed, because the box is empty").toContain(
    "This field holds “{unshown}”"
  );
  expect(source, "only while untouched: once they type, the box IS the value").toContain(
    "!edited && stored !== \"\" && !isPhoneNumberShaped(stored)"
  );
});

test("the PIN code box and the wrapper compute one answer, not two", () => {
  expect(pincodeValidationError("768 029")).toBe("Pincode must be 6 digits — remove any letters or symbols.");
  expect(pincodeOrSpacedValidationError("768 029")).toBeNull();
  expect(pincodeOrSpacedValidationError("768-029")).toBeNull();
  expect(pincodeOrSpacedValidationError("---")).toBe("Pincode must be 6 digits — remove any letters or symbols.");
  expect(pincodeOrSpacedValidationError("")).toBeNull();
});

test("the mask predicate FieldInput draws its sentence from is the accept-arm's own", () => {
  expect(isAadhaarMaskValue("XXXX XXXX 1234")).toBe(true);
  expect(isAadhaarMaskValue("XXXXXXXX1234")).toBe(true);
  expect(isAadhaarMaskValue("XXXX XXXX XXXX")).toBe(true);
  expect(isAadhaarMaskValue("XxamplE 1234")).toBe(false);
  expect(isAadhaarMaskValue("234567890124")).toBe(false);
  expect(isAadhaarMaskValue("")).toBe(false);
});

test("a format refusal is drawn once, by the control that owns the box", () => {
  const participant = {
    key: "participant",
    fields: [
      { key: "phone", label: "Phone", type: "PHONE", format: "PHONE_IN" },
      { key: "pincode", label: "PIN code", type: "TEXT", format: "PINCODE" },
      { key: "state", label: "State", type: "TEXT" },
      { key: "email", label: "Email", type: "EMAIL", format: "EMAIL" },
      { key: "aadhaarNumber", label: "Aadhaar number", type: "TEXT", format: "AADHAAR" }
    ]
  } as unknown as DwEntity;
  const at = (key: string) => participant.fields.find((field) => field.key === key)!;

  expect(formatShownByControl(participant, at("phone"))).toBe(true);
  expect(formatShownByControl(participant, at("pincode"))).toBe(true);
  expect(formatShownByControl(participant, at("email"))).toBe(false);
  expect(formatShownByControl(participant, at("aadhaarNumber"))).toBe(false);
  expect(formatShownByControl(participant, at("state"))).toBe(false);
});

test("a format this build has never heard of enforces nothing", () => {
  expect(fieldFormatError(fieldWith("GSTIN"), "not a gstin")).toBeNull();
  expect(fieldFormatError(fieldWith(""), "anything at all")).toBeNull();
});
