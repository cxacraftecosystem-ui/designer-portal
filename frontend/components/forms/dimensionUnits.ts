export const CM_PER_INCH = 2.54;

const NUMERIC_RE = /^[ \t]*([0-9]+\.?[0-9]*|\.[0-9]+)[ \t]*$/;

const DECIMAL_10_2_MAX_SCALED = 9999999999;

export function parseDimension(text: string): number | null {
  if (!NUMERIC_RE.test(text)) return null;
  const value = Number(text.trim());
  return Number.isFinite(value) ? value : null;
}

function renderScaled(scaled: number): string {
  const whole = Math.floor(scaled / 100);
  const frac = scaled % 100;
  if (frac === 0) return String(whole);
  if (frac % 10 === 0) return `${whole}.${frac / 10}`;
  return `${whole}.${frac < 10 ? `0${frac}` : frac}`;
}

export function cmTextFromInches(text: string): string | null {
  const value = parseDimension(text);
  if (value === null) return null;
  const scaled = Math.floor(value * CM_PER_INCH * 100 + 0.5);
  if (scaled > DECIMAL_10_2_MAX_SCALED) return null;
  return renderScaled(scaled);
}

export function inchesTextFromCm(text: string): string | null {
  const value = parseDimension(text);
  if (value === null) return null;
  const scaled = Math.floor((value / CM_PER_INCH) * 100 + 0.5);
  if (scaled > DECIMAL_10_2_MAX_SCALED) return null;
  return renderScaled(scaled);
}

export function propagate(
  text: string,
  convert: (value: string) => string | null,
  setPartner: (value: string) => void
): void {
  if (text.trim() === "") {
    setPartner("");
    return;
  }
  const next = convert(text);
  if (next !== null) setPartner(next);
}
