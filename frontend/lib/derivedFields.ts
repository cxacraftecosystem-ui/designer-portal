/**
 * Fields that compute themselves — the browser's port of `stage_schema.derive_value`.
 *
 * WHY THIS EXISTS ON THE CLIENT AT ALL. The server computes these too, on save, and that is what
 * makes the stored value right for the Android app and for anything talking to the API directly.
 * But a number that only materialises after a round trip is a number the designer cannot check
 * against the sanction order lying on the table in front of them: they type the two dates, look
 * for the duration the help text promised, and see an empty box. Computing it here means it
 * appears in the same frame as the second date.
 *
 * IT IS A PORT, NOT A SECOND OPINION. The rule is declared once in the registry — `derivedKind`
 * and `derivedFrom` on the field descriptor, served by `GET /design-workshops/schema` — so this
 * file only interprets what the server sent. Adding a derivation means adding it to the registry
 * and to the two interpreters; inventing one here would produce a figure the save then overwrote,
 * which is worse than no figure at all because the designer has already read it.
 *
 * `null` means NOT COMPUTABLE, never 0. A start date with no end date has no duration, and "0
 * days" on a cover page is a wrong fact rather than a missing one.
 */

import type { DwEntryData, DwField, DwValue } from "@/lib/designWorkshops";

function numeric(raw: DwValue | undefined): number | null {
  if (raw === null || raw === undefined || raw === "") return null;
  const value = Number(String(raw).replace(/,/g, ""));
  return Number.isFinite(value) ? value : null;
}

function isoDate(raw: DwValue | undefined): Date | null {
  if (typeof raw !== "string" || !raw.trim()) return null;
  // Parsed as UTC midnight so the difference is a whole number of days regardless of the
  // browser's zone — a local-time parse across a DST boundary is off by one, and "29 days"
  // against a sanction order that says 30 is exactly the kind of discrepancy that gets a
  // utilisation certificate sent back.
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(raw.trim());
  if (!match) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return Number.isNaN(date.getTime()) ? null : date;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** What `field` computes to from `row`, or null when it cannot be computed. */
export function deriveValue(field: DwField, row: DwEntryData): DwValue | null {
  const kind = field.derivedKind;
  const from = field.derivedFrom ?? [];
  if (!kind || !from.length) return null;

  if (kind === "DAYS_BETWEEN") {
    const start = isoDate(row[from[0]]);
    const end = isoDate(row[from[1]]);
    if (!start || !end) return null;
    // INCLUSIVE of both days, matching the server: a workshop running the 12th to the 14th is
    // three days, which is what its attendance register says.
    const days = Math.round((end.getTime() - start.getTime()) / DAY_MS) + 1;
    return days > 0 ? days : null;
  }

  if (kind === "PRODUCT") {
    let total = 1;
    for (const key of from) {
      const value = numeric(row[key]);
      if (value === null) return null;
      total *= value;
    }
    // MONEY keeps the server's fixed-2 string, so the value the form shows is character-for-
    // character the value the save stores.
    return field.type === "MONEY" ? total.toFixed(2) : Number(total.toFixed(4));
  }

  if (kind === "SUM") {
    // BLANK MEANS ZERO HERE, and that is the whole difference from PRODUCT, which gives up the
    // moment one factor is missing. A cost sheet's total is the sum of six heads of which four are
    // optional, so requiring all six would leave `totalCost` empty for every workshop with no
    // packaging or transport cost — most of them. But a row with NONE of them filled has no total:
    // it is an empty row, not a zero-rupee product, and "₹ 0.00" in a cost sheet a ministry reads
    // is a claim rather than a blank.
    //
    // A blank and an unparseable value must NOT be treated alike, which is why this cannot simply
    // reuse `numeric` and skip its nulls: "abc" has to abandon the whole sum the way the server
    // does, or the browser would quietly show a total the save then disagrees with.
    let total = 0;
    let seen = false;
    for (const key of from) {
      const raw = row[key];
      if (raw === null || raw === undefined || raw === "") continue;
      const value = numeric(raw);
      if (value === null) return null;
      total += value;
      seen = true;
    }
    if (!seen) return null;
    return field.type === "MONEY" ? total.toFixed(2) : Number(total.toFixed(4));
  }

  return null;
}

/** Whether this field computes itself when left blank. */
export function isDerived(field: DwField): boolean {
  return !!field.derivedKind && !!(field.derivedFrom ?? []).length;
}
