const DIGIT_TOKEN = /[0-9](?:[ -]?[0-9])*/g;

const NON_DIGITS = /[^0-9]/g;

export const AADHAAR_LENGTH = 12;

export type IdentityTextReading = {
  aadhaar: string[];
  rejectedCount: number;
};

export function identityCandidatesFromText(
  text: string | null | undefined,
  aadhaarProblem: (digits: string) => string | null
): IdentityTextReading {
  const aadhaar: string[] = [];
  const seen = new Set<string>();
  let rejectedCount = 0;

  for (const match of (text ?? "").matchAll(DIGIT_TOKEN)) {
    const digits = match[0].replace(NON_DIGITS, "");
    if (digits.length !== AADHAAR_LENGTH) continue;
    if (seen.has(digits)) continue;
    seen.add(digits);
    if (aadhaarProblem(digits)) {
      rejectedCount += 1;
      continue;
    }
    aadhaar.push(digits);
  }

  return { aadhaar, rejectedCount };
}

export function maskIdentityNumber(value: string | null | undefined): string {
  const digits = (value ?? "").replace(NON_DIGITS, "");
  if (digits.length < 4) return "XXXX XXXX XXXX";
  return `XXXX XXXX ${digits.slice(-4)}`;
}

export function groupAadhaarForReading(digits: string): string {
  return digits.replace(/(\d{4})(?=\d)/g, "$1 ");
}
