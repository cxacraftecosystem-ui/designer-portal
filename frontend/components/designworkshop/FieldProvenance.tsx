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
 * It answers one question — "where did THIS value come from" — in one of three ways:
 *
 *   * typed by a person        → "Sita Devi, 14 Aug"
 *   * copied from a record     → "From the artisan record"
 *   * copied, then edited      → "Sita Devi, 14 Aug — edited here"
 *
 * The third is the one the whole model turns on: provenance follows the ORIGINAL author until
 * somebody edits the field, and then it is theirs. A reader has to be able to tell those two apart
 * at a glance or the distinction may as well not be stored.
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

/** The stamp's `source` values that mean "this was copied from a shared record, untouched here". */
const COPIED = new Set(["reference", "hydration", "carry", "prefill"]);

function shortDate(iso?: string): string {
  if (!iso) return "";
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return "";
  // Day and month only. A year on every one of forty fields is noise — and where the year matters
  // (a workshop revisited across two seasons) the provenance view prints it in full.
  return parsed.toLocaleDateString(undefined, { day: "numeric", month: "short" });
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

export function fieldProvenanceLine(stamp?: DwFieldStamp | null): string {
  if (!stamp) return "";
  const copied = COPIED.has(String(stamp.source ?? ""));
  // A copied value with no author is the ordinary hydration case: the record is the author.
  if (copied && !stamp.by) return `From the ${recordName(stamp.refModel)} record`;
  const who = stamp.byName?.trim() || (stamp.by ? "a former colleague" : "");
  if (!who) return copied ? `From the ${recordName(stamp.refModel)} record` : "";
  const when = shortDate(stamp.at);
  const base = when ? `${who}, ${when}` : who;
  // THE CLAUSE THE MODEL TURNS ON. A stamp that names both a person AND a source record means the
  // value arrived from the record and was then changed here — which is exactly the moment
  // provenance moves from the record's author to the editor, and the reader has to see it.
  return copied ? `${base} — edited here` : base;
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
