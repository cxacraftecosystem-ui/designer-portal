import type { RecordStatus } from "@/lib/types";

/**
 * TWO MAPS, AND A STATUS NEEDS AN ENTRY IN BOTH.
 *
 * Omit the label and it falls through to `humanize()`, which is usually acceptable. Omit the TONE and
 * it silently paints DRAFT grey — which is not a cosmetic default, because grey means "draft"
 * everywhere else in this app. That is a badge asserting the wrong thing about a record, in the one
 * control whose whole job is to say what the record's standing is.
 *
 * ── THE DESIGN-WORKSHOP STATUSES, ADDED 2026-08-26 ──────────────────────────────────────────────
 *
 * `DesignWorkshopStatus` is DRAFT | IN_PROGRESS | COMPLETE | SUBMITTED | ARCHIVED, and four of those
 * five had no tone here — so IN_PROGRESS, COMPLETE, SUBMITTED and ARCHIVED all rendered in the grey
 * that means DRAFT. It went unnoticed for as long as the chip was decoration on a list row. It stopped
 * being decoration when the workshop detail page grew a deliberate submission control and put this
 * chip beside it as the act's confirmation: pressing "Submit" changed one word inside an unchanged
 * grey pill, which is the weakest possible feedback for the most consequential act on the screen.
 *
 * THE WORD IS STILL THE SIGNAL AND THE COLOUR ONLY AGREES WITH IT. Rule 5 of the frontend contract:
 * a signal carried by colour alone is one a colour-blind reader, a greyscale printout and
 * forced-colours mode all lose. `humanize()` already gave every one of these a correct word, so this
 * change adds a second, redundant channel — it never becomes the only one.
 *
 * WHY THESE PARTICULAR TONES:
 * · IN_PROGRESS — purple, the app's action colour, because work is under way on it. It shares the
 *   NEEDS_REVISION treatment deliberately: both mean "somebody is expected to be doing something".
 * · COMPLETE — amber. Complete is NOT submitted, and that distinction is the whole reason the two
 *   statuses exist; amber is this palette's "attention, not yet done with" and it stops COMPLETE
 *   reading as the end of the process.
 * · SUBMITTED — green, the only status here that means an act has been performed and finished.
 * · ARCHIVED — grey, but a DIFFERENT grey from DRAFT: the surface-200 fill and ink-500 text read as
 *   "put away" rather than "not started". Two statuses that both mean "nothing is happening" for
 *   opposite reasons must not be the same pill.
 *
 * The literal status colours (`amber-*`, `success-*`, `error-*`) do NOT invert under dark mode — a
 * known and documented property of this palette, not an oversight here.
 */
const tone: Record<string, string> = {
  DRAFT: "border-line-200 bg-surface-50 text-ink-500",
  PENDING: "border-amber-500/30 bg-amber-100 text-amber-800",
  APPROVED: "border-success-600/25 bg-success-100 text-success-600",
  REJECTED: "border-error-600/25 bg-error-100 text-error-600",
  NEEDS_REVISION: "border-purple-300 bg-purple-50 text-purple-700",
  // ── DesignWorkshopStatus ──
  IN_PROGRESS: "border-purple-300 bg-purple-50 text-purple-700",
  COMPLETE: "border-amber-500/30 bg-amber-100 text-amber-800",
  SUBMITTED: "border-success-600/25 bg-success-100 text-success-600",
  ARCHIVED: "border-line-200 bg-field-200 text-ink-500"
};

const label: Record<string, string> = {
  DRAFT: "Draft",
  PENDING: "Pending",
  APPROVED: "Approved",
  REJECTED: "Rejected",
  NEEDS_REVISION: "Needs revision",
  // Curated rather than left to `humanize()`, which would answer "In progress" correctly and
  // "Complete"/"Submitted"/"Archived" correctly too — they are here so that the two maps carry the
  // same key set, which is what makes a missing tone visible to a reader of this file.
  IN_PROGRESS: "In progress",
  COMPLETE: "Complete",
  SUBMITTED: "Submitted",
  ARCHIVED: "Archived"
};

/** Fallback for statuses without a curated label: SOME_STATUS -> "Some status". */
function humanize(status: string): string {
  const words = status.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function StatusBadge({ status }: { status: RecordStatus | string }) {
  const className = tone[status] ?? tone.DRAFT;
  return (
    <span className={`rounded-full border px-2.5 py-1 text-xs font-medium ${className}`}>
      {label[status] ?? humanize(String(status))}
    </span>
  );
}
