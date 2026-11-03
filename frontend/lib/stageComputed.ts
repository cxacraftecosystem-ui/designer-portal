import type { DwRow, DwValue } from "@/lib/designWorkshops";

export const FEMALE_TOKENS: ReadonlySet<string> = new Set(["female", "f", "woman", "women", "girl", "महिला", "स्त्री"]);

export function isWoman(gender: DwValue | undefined): boolean {
  return FEMALE_TOKENS.has(String(gender ?? "").trim().toLocaleLowerCase());
}

function toInt(value: DwValue | undefined): number | null {
  const n = Number.parseFloat(String(value ?? "").trim());
  return Number.isFinite(n) ? Math.trunc(n) : null;
}

export function absentDays(row: DwRow): Set<string> {
  const raw = row.absentDays;
  if (!Array.isArray(raw)) return new Set();
  return new Set(raw.map((v) => toInt(v)).filter((n): n is number => n !== null).map(String));
}

function isBlankValue(value: DwValue | undefined): boolean {
  return value === null || value === undefined || (typeof value === "string" && !value.trim());
}

export function isLegacyAttendance(row: DwRow): boolean {
  return !Array.isArray(row.absentDays) && !isBlankValue(row.attendedDays);
}

export function attendedDays(row: DwRow, days: readonly number[]): number | null {
  if (!days.length || isLegacyAttendance(row)) return null;
  const absent = absentDays(row);
  return days.filter((day) => !absent.has(String(day))).length;
}

export function namedParticipants(rows: readonly DwRow[]): DwRow[] {
  return rows.filter((row) => String(row.name ?? "").trim() || row.artisanRef);
}

export type RosterValues = {
  ROSTER_COUNT: number | null;
  ROSTER_WOMEN: number | null;
  ROSTER_COMPLETED: number | null;
};

export function rosterValues(rows: readonly DwRow[], days: readonly number[]): RosterValues {
  const people = namedParticipants(rows);
  if (!people.length) return { ROSTER_COUNT: null, ROSTER_WOMEN: null, ROSTER_COMPLETED: null };
  return {
    ROSTER_COUNT: people.length,
    ROSTER_WOMEN: people.filter((p) => isWoman(p.gender)).length,
    ROSTER_COMPLETED:
      days.length && !people.some(isLegacyAttendance)
        ? people.filter((p) => attendedDays(p, days) === days.length).length
        : null
  };
}

export function workshopDays(schedule: readonly DwRow[], fallback: readonly number[] = []): number[] {
  const numbers = [...new Set(schedule.map((r) => toInt(r.dayNumber)).filter((n): n is number => n !== null && n > 0 && n <= 366))];
  numbers.sort((a, b) => a - b);
  return numbers.length ? numbers : [...fallback];
}

export function withAttendance(row: DwRow, day: number, present: boolean): DwRow {
  const absent = absentDays(row);
  if (present) absent.delete(String(day));
  else absent.add(String(day));
  const list = [...absent].sort((a, b) => Number(a) - Number(b));
  return { ...row, absentDays: list };
}
