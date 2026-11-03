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

export function dateOfDay(start: string | null | undefined, day: DwValue | undefined): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(start ?? ""));
  const n = Number.parseInt(String(day ?? ""), 10);
  if (!match || !Number.isFinite(n) || n < 1) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + n - 1));
  return date.toISOString().slice(0, 10);
}

export type AutoNumberContext = {
  startDate?: string | null;
};

const keyOf = (row: DwRow): string => String(row._clientKey ?? row._entryId ?? "");

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
