"use client";

import type { DwFieldStamp } from "@/lib/designWorkshops";

/**
 * **WHO LAST SET THIS FIELD**, printed under it in one short line.
 *
 * ── WHY THIS COMPONENT HAD TO BE WRITTEN, AND WHY THAT IS EMBARRASSING ────────────────────────
 *
 * The server has carried per-field authorship for a while: every stage read returns a stamp per
 * field saying who wrote it, when, and — when the value was hydrated from a shared record rather
 * than typed — which record and column it came from. `DwFieldStamp` and `DwStageProvenance` were
 * declared in `lib/designWorkshops.ts` and had **zero consumers** anywhere in the app. Android was
 * the same: `DwFieldStampDto` existed with only a wire test calling it. So the requirement that
 * provenance "stays with the original author unless a field is edited" was true of the database and
 * invisible to every single person using the product.
 *
 * That is the failure this file exists to end, and it is worth naming plainly: a provenance feature
 * nobody can see is not a provenance feature. The value of knowing that a colleague changed the
 * price is entirely in a person reading it on the screen where the price is.
 *
 * ── WHAT IT SAYS, AND THE THREE THINGS IT REFUSES TO SAY ──────────────────────────────────────
 *
 * It answers one question — "where did THIS value come from" — in one of two ways:
 *
 *   * copied from a record  → "From the artisan record, by Sita Devi"   (Sita Devi recorded the
 *                             ARTISAN; she has not touched this workshop)
 *   * typed or changed here → "Ravi Kumar, 14 Aug"
 *
 * TWO, NOT THREE. Provenance follows the original author until somebody edits the field, and then
 * the stamp is REPLACED wholesale — so a hydrated field a designer has typed over reads as that
 * designer's, with no trace of the record, which is exactly right. An earlier draft of this file
 * invented a third "copied, then edited" case and rendered the RECORD's author as the editor; see
 * the note on SOURCE_REFERENCE below for why that sentence was a lie about a real person.
 *
 * What it does NOT do:
 *
 * 1. **It never prints a cuid.** A stamp whose account has since been deleted keeps its `by` id and
 *    loses its `byName`; this renders "a former colleague" rather than `cmf3k2...`, which tells a
 *    designer nothing and looks like a bug.
 * 2. **It never claims a time it does not have.** A stamp with no `at` prints the author alone.
 * 3. **It is never announced to a screen reader as part of the field.** It is `aria-hidden`, and
 *    deliberately: it is not describedby text, and reading "Sita Devi, 14 August" after every one
 *    of forty labels would make a stage form unusable by voice. The same facts are available in
 *    full on the workshop's provenance view, which is a table and reads correctly.
 */

/**
 * The two source values the server writes, and there are exactly two.
 *
 * ``reference`` — hydration wrote this value from a shared canonical record. **`by` is THAT
 * RECORD's author**, not the designer who picked it (`entry_provenance.py`, rule 1).
 * ``designer``  — a person on this workshop typed or changed it; `by` is that person (rule 3).
 *
 * THEY ARE MUTUALLY EXCLUSIVE, WHICH IS THE FACT THIS FILE GOT WRONG ONCE. Rule 3 REPLACES the
 * whole stamp when a value changes, so "copied from a record and then edited here" does not exist
 * as a state: the moment a designer types over a hydrated field, the stamp becomes a plain
 * `designer` one with no `refModel` at all. The first version of this component rendered a
 * `reference` stamp that carried a name as "Sita Devi — edited here", which is a sentence about a
 * person who never touched the field: Sita Devi is whoever recorded the ARTISAN, one table away.
 * Attributing an edit to somebody who did not make it is worse than showing nothing.
 */
const SOURCE_REFERENCE = "reference";

function shortDate(iso?: string): string {
  if (!iso) return "";
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return "";
  // Day and month only. A year on every one of forty fields is noise — and where the year matters
  // (a workshop revisited across two seasons) the provenance view prints it in full.
  //
  // ── THE ORDER IS OURS; ONLY THE MONTH NAME IS THE LOCALE'S ──────────────────────────────────
  //
  // This was `toLocaleDateString(undefined, { day: "numeric", month: "short" })`, which hands the
  // ORDER to the runtime's locale as well as the wording — so the same stamp read "14 Aug" in a
  // browser resolving en-IN and "Aug 14" in one resolving en-US. Android's `shortDay` has always
  // hardcoded the order (`"${date.dayOfMonth} ${date.month.getDisplayName(SHORT, getDefault())}"`)
  // and localises the month name alone, so the two clients disagreed about the same field on the
  // same day for no reason a designer could see.
  //
  // That is the divergence `fieldProvenanceLine`'s own docstring calls "a requirement rather than a
  // nicety", and it went unnoticed because every machine anybody developed on resolved to a
  // day-first locale. The CI runner does not, which is what finally showed it — the first defect
  // this repository's new checks workflow caught that no local run could have.
  //
  // Day-first, month name localised, matching the handset exactly.
  const month = new Intl.DateTimeFormat(undefined, { month: "short" }).format(parsed);
  return `${parsed.getDate()} ${month}`;
}

/** The record a hydrated value came out of, in the words a designer uses for it. */
function recordName(refModel?: string | null): string {
  const named: Record<string, string> = {
    Artisan: "artisan",
    Craft: "craft",
    Process: "process",
    ProductDocumentation: "product",
    ToolDocumentation: "tool"
  };
  return (refModel && named[refModel]) || "linked";
}

/**
 * The sentence for one stamp, or "" when there is nothing honest to say.
 *
 * WORD-FOR-WORD THE SAME AS ANDROID'S `DwFieldStampDto.attribution()`, and that is a requirement
 * rather than a nicety: a designer who reads "From the artisan record, by Sita Devi" on a laptop
 * and something else on the handset for the same field has been told two things by one product,
 * and has no way to know which is true. `DwFieldProvenanceWireTest` and this file's spec assert the
 * same cases so the pair cannot drift silently.
 */
export function fieldProvenanceLine(stamp?: DwFieldStamp | null): string {
  if (!stamp) return "";
  const who = stamp.byName?.trim() || "";
  const when = shortDate(stamp.at);

  if (stamp.source === SOURCE_REFERENCE) {
    // Neither a record nor a person is nothing concrete to say, and "From the linked record" would
    // be a vague sentence pretending to be a fact. The server always sets refModel/refId/refKey when
    // hydration writes a value, so this is not a state it produces — but a build one release ahead
    // might, and silence is the honest fallback. Android returns null here for the same reason.
    if (!stamp.refModel && !who) return "";
    // The record is the subject of this sentence, and the person — when there is one — is the
    // person who recorded THAT record. Never phrased as an edit to this field.
    const record = `From the ${recordName(stamp.refModel)} record`;
    if (who) return `${record}, by ${who}`;
    // An id with no name is an account since deleted. The record still answers, so the clause is
    // dropped rather than replaced with a cuid or with "unknown".
    return record;
  }

  // A designer stamp. No `by` at all means the server had nothing to attribute — an unchanged value
  // on a row written before this column existed — and it must say nothing rather than guess.
  if (!stamp.by) return "";
  const person = who || "A former colleague";
  return when ? `${person}, ${when}` : person;
}

export function FieldProvenance({ stamp }: { stamp?: DwFieldStamp | null }) {
  const line = fieldProvenanceLine(stamp);
  if (!line) return null;
  return (
    <p aria-hidden className="text-[0.6875rem] leading-4 text-ink-300">
      {line}
    </p>
  );
}
