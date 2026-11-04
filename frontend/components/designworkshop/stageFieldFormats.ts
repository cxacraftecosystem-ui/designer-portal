import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { pincodeValidationError } from "@/components/forms/LocationFields";
import { addressListRole } from "@/components/designworkshop/stageFieldRoles";
import { emailValidationError, phoneValidationError } from "@/lib/textFormats";
import { inputValue, type DwEntity, type DwField, type DwValue } from "@/lib/designWorkshops";

const SEPARATORS = /[\s\u2010-\u2015-]+/g;

const AADHAAR_MASK_RE = /^X{8}(?:\d{4}|X{4})$/;

export function isAadhaarMaskValue(value: string | null | undefined): boolean {
  return AADHAAR_MASK_RE.test((value ?? "").replace(SEPARATORS, "").trim());
}

export function aadhaarOrMaskValidationError(value: string | null | undefined): string | null {
  const raw = (value ?? "").trim();
  if (!raw) return null;
  const measured = raw.replace(SEPARATORS, "") || raw;
  if (AADHAAR_MASK_RE.test(measured)) return null;
  return aadhaarValidationError(measured);
}

const PEHCHAN_MIN_LENGTH = 4;
const PEHCHAN_MAX_LENGTH = 32;

export function pehchanValidationError(value: string | null | undefined): string | null {
  const raw = (value ?? "").trim();
  if (!raw) return null;
  const measured = raw.replace(/[^A-Za-z0-9]+/g, "").toUpperCase() || raw;
  if (!/^[A-Za-z0-9]+$/.test(measured)) return "Pehchan card number must be letters and digits only.";
  if (measured.length < PEHCHAN_MIN_LENGTH || measured.length > PEHCHAN_MAX_LENGTH) {
    return `Pehchan card number must be between ${PEHCHAN_MIN_LENGTH} and ${PEHCHAN_MAX_LENGTH} characters.`;
  }
  return null;
}

export function pincodeOrSpacedValidationError(value: string | null | undefined): string | null {
  const raw = (value ?? "").trim();
  if (!raw) return null;
  return pincodeValidationError(raw.replace(SEPARATORS, "") || raw);
}

const FORMATS: Record<string, (value: string) => string | null> = {
  EMAIL: emailValidationError,
  PHONE_IN: phoneValidationError,
  AADHAAR: aadhaarOrMaskValidationError,
  PEHCHAN: pehchanValidationError,
  PINCODE: pincodeOrSpacedValidationError
};

export function fieldFormatError(field: DwField, value: DwValue | null | undefined): string | null {
  if (!field.format) return null;
  const rule = FORMATS[field.format];
  if (!rule) return null;
  const text = inputValue(value);
  return text.trim() ? rule(text) : null;
}

export function formatShownByControl(entity: DwEntity, field: DwField): boolean {
  if (!field.format) return false;
  if (field.type === "PHONE") return true;
  return addressListRole(entity, field)?.role === "pincode";
}
