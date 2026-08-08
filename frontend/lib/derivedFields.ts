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
 *
 * THE ARITHMETIC IS BORROWED, NOT RESTATED. `asNumber` and `pyRound` live in `lib/marketAnalysis.ts`
 * because that module had to answer the same two questions first — what Python's `float()` accepts,
 * and how Python's `round()` breaks a tie — and both answers are subtle enough that a second copy
 * would be a second opinion. `e2e/derived-fields-unit.spec.ts` pins every case against the server's
 * own output; see `frontend/tools/regenerate_derived_field_golden.py` for how that file is made.
 */

import type { DwEntryData, DwField, DwValue } from "@/lib/designWorkshops";
import { asNumber, pyRound } from "@/lib/marketAnalysis";

/**
 * The row's date at `raw`, as UTC midnight, or null when the server would refuse it too.
 *
 * UTC because the difference has to be a whole number of days regardless of the browser's zone: a
 * local-time parse across a DST boundary is off by one, and "29 days" against a sanction order that
 * says 30 is exactly the kind of discrepancy that gets a utilisation certificate sent back.
 *
 * THREE THINGS IT COPIES FROM `derive_value`, each of which the obvious code gets wrong.
 *
 * `str(value)[:10]`, so a stored datetime ("2026-08-14T23:59:59Z") reads as its date and the compact
 * ISO spelling a non-string value stringifies to ("20260814") is read rather than refused — Python's
 * `date.fromisoformat` takes both. Only these two spellings are handled: the week and ordinal forms
 * that `fromisoformat` also accepts cannot come out of a date input on any of the three clients, and
 * inventing support for them here would be a second opinion about the wire format rather than a port.
 *
 * THE COMPONENTS ARE CHECKED BACK, which is the part `Date.UTC` alone will not do. `Date.UTC(2026, 1,
 * 30)` is not an error — it is the 2nd of March, because the constructor ROLLS OVER — so "2026-02-30"
 * would have produced a confident duration here against a server that answers "not computable". A
 * date nobody can be at is not a date.
 */
function isoDate(raw: DwValue | undefined): Date | null {
  // Python reads `str(row.get(key) or "")`, so every falsy value collapses to "" and fails below.
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
      const value = asNumber(row[key]);
      if (value === null) return null;
      total *= value;
    }
    return fixed(total, field);
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

/**
 * `total` in the shape the server would have stored it.
 *
 * MONEY keeps the server's fixed-2 STRING, so the value the form shows is character-for-character
 * the value the save stores; everything else is a number rounded to four places.
 *
 * NOT `toFixed`. The specification says `toFixed` breaks a tie by picking the LARGER candidate, and
 * Python — which is what `derive_value` runs — rounds to the EVEN digit. 1.5 metres of braid at
 * ₹4.75 is exactly ₹7.125, so the browser printed ₹7.13 into the box while the save wrote ₹7.12,
 * and a designer reading a cost sheet back would find a paisa they had watched the form compute had
 * moved. That is the precise failure this module's header calls worse than showing nothing, because
 * the figure has already been read. Half-paisa totals are ordinary: any odd quantity against a rate
 * ending in 5 lands on one.
 */
function fixed(total: number, field: DwField): DwValue {
  return field.type === "MONEY" ? pyRound(total, 2).toFixed(2) : pyRound(total, 4);
}

/** Whether this field computes itself when left blank. */
export function isDerived(field: DwField): boolean {
  return !!field.derivedKind && !!(field.derivedFrom ?? []).length;
}
