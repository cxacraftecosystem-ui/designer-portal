/**
 * Numbering and dating a new row — the browser's port of `stage_schema.next_auto_number`.
 *
 * Owner, 2026-10-17 (C6): "S-01", "P-01", "D-01" per row type, the next number automatic, iterations
 * numbered per prototype, schedule dates worked out from the start date. All editable, and an
 * existing row is never renumbered: only a row this client has just ADDED, or one whose scope was
 * just chosen while its number is still blank, is filled.
 */

import type { DwEntity, DwField, DwRow, DwValue } from "@/lib/designWorkshops";

export const AUTO_NUMBER_SEQUENCE = "#";

export function formatAutoNumber(prefix: string, n: number): string {
  return `${prefix}-${String(n).padStart(2, "0")}`;
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function blank(value: DwValue | undefined): boolean {
  return value === null || value === undefined || value === "" || (Array.isArray(value) && value.length === 0);
}

/** The number a NEW row takes in `field`, or null when the field numbers nothing. */
export function nextAutoNumber(field: DwField, rows: readonly DwRow[], scope?: DwValue): string | number | null {
  if (!field.autoNumber) return null;
  const within = field.autoNumberWithin;
  const considered = rows.filter((row) => !within || row[within] === scope);
  let highest = 0;
  if (field.autoNumber === AUTO_NUMBER_SEQUENCE) {
    for (const row of considered) {
      const n = Number.parseInt(String(row[field.key] ?? "0").trim(), 10);
      if (Number.isFinite(n)) highest = Math.max(highest, n);
    }
    return Math.max(highest, considered.length) + 1;
  }
  const pattern = new RegExp(`^\\s*${escapeRegExp(field.autoNumber)}\\s*-?\\s*(\\d+)\\s*$`, "i");
  for (const row of considered) {
    const match = pattern.exec(String(row[field.key] ?? ""));
    if (match) highest = Math.max(highest, Number(match[1]));
  }
  return formatAutoNumber(field.autoNumber, Math.max(highest, considered.length) + 1);
}

/** `start` (yyyy-mm-dd) plus `day - 1` days, as a local yyyy-mm-dd, or null. */
export function dateOfDay(start: string | null | undefined, day: DwValue | undefined): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(start ?? ""));
  const n = Number.parseInt(String(day ?? ""), 10);
  if (!match || !Number.isFinite(n) || n < 1) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + n - 1));
  return date.toISOString().slice(0, 10);
}

export type AutoNumberContext = {
  /** The workshop's start date, for a schedule row's date. */
  startDate?: string | null;
};

const keyOf = (row: DwRow): string => String(row._clientKey ?? row._entryId ?? "");

/**
 * `next` with every auto-numbered or auto-dated field filled where the rules allow — and only there.
 *
 * A row is filled when it is NEW (its key is not among `previous`) or when its scope field has just
 * changed; in both cases only BLANK boxes are written. Rows are numbered in order, each one seeing
 * the numbers given to the rows before it, so a bulk add of ten sketches reads S-04 … S-13.
 */
export function applyAutoNumbers(
  entity: DwEntity,
  previous: readonly DwRow[],
  next: readonly DwRow[],
  context: AutoNumberContext = {}
): DwRow[] {
  const numbered = entity.fields.filter((f) => !f.deprecated && f.autoNumber);
  const dated = entity.fields.filter((f) => !f.deprecated && f.autoDateFromDay);
  if (!numbered.length && !dated.length) return next as DwRow[];
  const before = new Map(previous.map((row) => [keyOf(row), row]));
  let changed = false;
  const out: DwRow[] = [];
  for (const row of next) {
    const old = before.get(keyOf(row));
    let patched: DwRow = row;
    for (const field of numbered) {
      if (!blank(patched[field.key])) continue;
      const within = field.autoNumberWithin;
      const scopeChanged = within ? old !== undefined && old[within] !== patched[within] : false;
      if (old !== undefined && !scopeChanged) continue;
      if (within && blank(patched[within])) continue;
      // The rows before this one as numbered so far, and the EXISTING rows after it — never the
      // other new rows still to come, or a batch would skip numbers.
      const others = [...out, ...next.slice(out.length + 1).filter((r) => before.has(keyOf(r)))];
      const value = nextAutoNumber(field, others, within ? patched[within] : undefined);
      if (value === null) continue;
      patched = { ...patched, [field.key]: value };
    }
    for (const field of dated) {
      if (!blank(patched[field.key])) continue;
      if (old !== undefined && old[field.autoDateFromDay!] === patched[field.autoDateFromDay!]) continue;
      const when = dateOfDay(context.startDate, patched[field.autoDateFromDay!]);
      if (when) patched = { ...patched, [field.key]: when };
    }
    if (patched !== row) changed = true;
    out.push(patched);
  }
  return changed ? out : (next as DwRow[]);
}
