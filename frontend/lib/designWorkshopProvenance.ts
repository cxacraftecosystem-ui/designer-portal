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
  /** What the canonical record says now. `null` when that record has been deleted. */
  canonical?: unknown;
  diverged?: boolean;
};

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

/** Every field of one entry whose stored value and canonical value now differ, in field order. */
export function divergedFields(entry: DwProvenanceEntry): Array<[string, DwCanonicalComparison]> {
  return Object.entries(entry.canonical ?? {}).filter(([, comparison]) => comparison?.diverged);
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
