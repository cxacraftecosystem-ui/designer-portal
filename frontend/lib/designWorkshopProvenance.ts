import { apiFetch } from "@/lib/api";
import type { DwFieldStamp } from "@/lib/designWorkshops";

/**
 * `GET /design-workshops/{id}/provenance` — the admin-only authorship and divergence view.
 *
 * ── WHAT THIS ANSWERS THAT NO OTHER READER CAN ────────────────────────────────────────────────
 *
 * Every designer on a workshop already sees the per-field stamps: they ride on the ordinary stage
 * read and render under each box, because knowing that a colleague changed the price is part of
 * working on the record. This endpoint adds the half nobody else can see — for every value that was
 * COPIED from a shared canonical record, what that record says **today**, beside what this workshop
 * stored.
 *
 * That comparison needs an endpoint of its own because it is not derivable from anything else.
 * Once a value is hydrated onto a stage entry it is an ordinary string in `data`: a hydrated village
 * and a typed village are the same bytes, deliberately (see `REFERENCE_HYDRATION`, which exists so a
 * workshop keeps what the designer saw on the day). Only the `reference` stamp — which names the
 * record and the column — plus a live read of that record can say "this workshop says Barpali and
 * the artisan record now says Bargarh".
 *
 * ── DIVERGENCE IS NOT AN ERROR, AND THIS VIEW MUST NEVER IMPLY IT IS ──────────────────────────
 *
 * A workshop is a DATED OBSERVATION and is supposed to keep what was captured on the day. An
 * artisan who moved village after the workshop makes every participant row "diverge", and every one
 * of those rows is correct. What an admin needs is to be able to SEE it — for an audit, or to
 * understand why a report and the live directory disagree — which before this was impossible. So
 * the copy here is comparative and never corrective: no warning colours, no "fix", no count of
 * "problems".
 */

export type DwCanonicalComparison = {
  /** What this workshop holds — the value on the report. */
  stored?: unknown;
  /**
   * What the canonical record says now.
   *
   * `null` MEANS "THAT COLUMN IS EMPTY", AND NOTHING MORE. It does not mean the record is gone —
   * that is [recordDeleted], and the two must never be conflated. A present artisan record whose
   * phone number has since been cleared answers `canonical: null, recordDeleted: false`, and
   * reading the deleted sentence off this field told an admin a record sitting in the directory
   * had been deleted. See the note on `recordDeleted`.
   */
  canonical?: unknown;
  /**
   * Stored and canonical are both present and no longer equal.
   *
   * FALSE FOR A DELETED RECORD, which is the trap. `canonical_divergence` computes this as
   * `source is not None and str(stored) != str(canonical)` — so when the record is gone there is
   * nothing to compare against and `diverged` is false while `recordDeleted` is true
   * (`backend/tests/test_entry_provenance.py::test_a_deleted_record_reads_as_deleted_rather_than_disappearing`
   * pins that exact tuple). Filtering on this field alone therefore DROPS the deleted-record rows,
   * which is the one row an auditor most needs. Use [hasMoved], never `diverged` by itself.
   */
  diverged?: boolean;
  /**
   * The canonical record itself no longer exists.
   *
   * THE CASE REFERENCE HYDRATION EXISTS FOR: this workshop now holds the only copy of what the
   * designer saw. Not an error and not a fault — a repository that merges duplicate artisans
   * produces this in the ordinary course — and what it changes is who holds the last copy, not who
   * was wrong.
   */
  recordDeleted?: boolean;
};

/**
 * Has this field's canonical record moved away from what the workshop stored — in EITHER of the two
 * ways it can?
 *
 * Both halves are load-bearing. `diverged` is "the record says something else now"; `recordDeleted`
 * is "there is no record any more", and the server reports the second with `diverged: false`
 * because there was nothing left to compare. Omitting the deleted rows would make "the record is
 * gone" look identical to "the field was never copied from one" — the single distinction an admin
 * opened this view to make.
 */
export function hasMoved(comparison: DwCanonicalComparison | undefined): boolean {
  return Boolean(comparison?.diverged || comparison?.recordDeleted);
}

/**
 * The deleted-record sentence, rendered in the canonical column in place of a value.
 *
 * A CONSTANT AND NOT A LITERAL IN THE PAGE, because the handset says the same sentence
 * (`DW_PROVENANCE_RECORD_DELETED` in `android/.../data/DwProvenanceReport.kt`) and an admin who
 * reads one wording on a laptop and another on a phone has been told two things by one product.
 */
export const RECORD_DELETED_TEXT = "The record this came from no longer exists";

/**
 * The canonical column of one comparison row: the deleted sentence, or the value.
 *
 * KEYED ON `recordDeleted` AND NEVER ON `canonical == null`. Those are different facts and the
 * page rendered the wrong one: a record that is present and has simply had a column blanked also
 * answers `canonical: null`, and telling an admin it had been deleted would send them looking for
 * a record that is sitting there. An empty column is the em dash, which is what "this side says
 * nothing" looks like everywhere else on the screen.
 */
export function canonicalText(comparison: DwCanonicalComparison): string {
  return comparison.recordDeleted ? RECORD_DELETED_TEXT : comparisonText(comparison.canonical);
}

export type DwProvenanceEntry = {
  entryId: string;
  stageKey: string;
  entityKey: string;
  ordinal?: number | null;
  /**
   * Who created the ROW, reported beside the per-field answers and never instead of them.
   *
   * It is still a true and useful fact — somebody started this participant row — and showing both
   * is what makes the thing this feature exists for visible: a row created by one designer whose
   * fields are now attributed to three other people.
   */
  createdById?: string | null;
  fields?: Record<string, DwFieldStamp>;
  canonical?: Record<string, DwCanonicalComparison>;
};

export type DwProvenanceReport = { entries: DwProvenanceEntry[] };

export async function fetchWorkshopProvenance(workshopId: string): Promise<DwProvenanceReport> {
  return apiFetch<DwProvenanceReport>(`/design-workshops/${workshopId}/provenance`);
}

/**
 * A value as one line of text, for a comparison column.
 *
 * `null` and `undefined` are rendered as an em dash rather than as the words "null" or "empty":
 * on this screen a blank cell means "this record says nothing here", which is a real answer and
 * one of the more interesting divergences — a workshop holding a phone number the canonical record
 * has since had cleared.
 */
export function comparisonText(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  // An object here is a GEO point or a media reference. JSON is ugly and it is the honest rendering:
  // inventing a prose summary for a shape this view does not model would be a worse lie than braces.
  return JSON.stringify(value);
}

/**
 * Every field of one entry that has something to show, in field order.
 *
 * [hasMoved] and not `comparison.diverged`: a deleted record reports `diverged: false`, and
 * filtering on that alone dropped the most interesting row on the page.
 */
export function divergedFields(entry: DwProvenanceEntry): Array<[string, DwCanonicalComparison]> {
  return Object.entries(entry.canonical ?? {}).filter(([, comparison]) => hasMoved(comparison));
}

/**
 * How many entries and fields diverge, for the summary line.
 *
 * Counted rather than described, because the one thing an admin opening this page wants first is
 * whether there is anything here at all — a workshop with no divergence is the common case and
 * should say so in one line rather than making somebody scroll twenty-two stages to find out.
 */
export function divergenceTally(report: DwProvenanceReport): { entries: number; fields: number } {
  let entries = 0;
  let fields = 0;
  for (const entry of report.entries ?? []) {
    const diverged = divergedFields(entry);
    if (diverged.length) {
      entries += 1;
      fields += diverged.length;
    }
  }
  return { entries, fields };
}
