import type { DwEntryData, DwField, DwValue } from "@/lib/designWorkshops";
import { asNumber, pyRound } from "@/lib/marketAnalysis";

function isoDate(raw: DwValue | undefined): Date | null {
  const text = raw === null || raw === undefined ? "" : String(raw).trim().slice(0, 10);
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text) ?? /^(\d{4})(\d{2})(\d{2})$/.exec(text);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (Number.isNaN(date.getTime())) return null;
  const rolled =
    date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day;
  return rolled ? null : date;
}

const DAY_MS = 24 * 60 * 60 * 1000;

export function deriveValue(field: DwField, row: DwEntryData): DwValue | null {
  const kind = field.derivedKind;
  const from = field.derivedFrom ?? [];
  if (!kind || !from.length) return null;

  if (kind === "DAYS_BETWEEN") {
    const start = isoDate(row[from[0]]);
    const end = isoDate(row[from[1]]);
    if (!start || !end) return null;
    const days = Math.round((end.getTime() - start.getTime()) / DAY_MS) + 1;
    return days > 0 ? days : null;
  }

  if (kind === "PRODUCT") {
    let total = 1;
    for (const key of from) {
      const value = asNumber(row[key]);
      if (value === null) return null;
      total *= value;
    }
    return fixed(total, field);
  }

  if (kind === "SUM") {
    let total = 0;
    let seen = false;
    for (const key of from) {
      const raw = row[key];
      if (raw === null || raw === undefined || raw === "") continue;
      const value = asNumber(raw);
      if (value === null) return null;
      total += value;
      seen = true;
    }
    if (!seen) return null;
    return fixed(total, field);
  }

  return null;
}

function fixed(total: number, field: DwField): DwValue {
  return field.type === "MONEY" ? pyRound(total, 2).toFixed(2) : pyRound(total, 4);
}

export function isDerived(field: DwField): boolean {
  return !!field.derivedKind && !!(field.derivedFrom ?? []).length;
}
