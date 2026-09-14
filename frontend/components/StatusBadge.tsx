import type { RecordStatus } from "@/lib/types";

/**
 * TWO MAPS, AND A STATUS NEEDS AN ENTRY IN BOTH.
 *
 * Omit the label and it falls through to `humanize()`, which is usually acceptable. Omit the TONE and
 * it silently paints DRAFT grey — which is not a cosmetic default, because grey means "draft"
 * everywhere else in this app. That is a badge asserting the wrong thing about a record, in the one
 * control whose whole job is to say what the record's standing is.
 *
 * ── THE DESIGN-WORKSHOP STATUSES, ADDED 2026-08-26, EXTENDED 2026-09-14 ────────────────────────
 *
 * `DesignWorkshopStatus` was DRAFT | IN_PROGRESS | COMPLETE | SUBMITTED | ARCHIVED on 2026-08-26 and
 * four of those five had no tone here — so IN_PROGRESS, COMPLETE, SUBMITTED and ARCHIVED all
 * rendered in the grey that means DRAFT. It went unnoticed for as long as the chip was decoration on
 * a list row. It stopped being decoration when the workshop detail page grew a deliberate submission
 * control and put this chip beside it as the act's confirmation: pressing the forward button changed
 * one word inside an unchanged grey pill, which is the weakest possible feedback for the most
 * consequential act on the screen.
 *
 * ON 2026-09-14 THE ENUM GREW AGAIN AND THE SAME BUG CAME BACK. The review loop added
 * PRE_SUBMISSION, NEEDS_REVISION and APPROVED to `DesignWorkshopStatus`
 * (`frontend/lib/designWorkshops.ts:539-547`, eight members now). Two of the three landed here by
 * accident — `RecordStatus` already spells NEEDS_REVISION and APPROVED the same way — so
 * PRE_SUBMISSION was the single new token with no entry, and `tone[status] ?? tone.DRAFT` handed it
 * the byte-identical DRAFT string on all six surfaces that draw this chip: the designer's record
 * page and list (`design-workshops/[id]/page.tsx:839`, `design-workshops/page.tsx:1850`), the
 * inspector's queue and detail (`design-workshop-inspections/page.tsx:247`, `[id]/page.tsx:700`) and
 * the officers' monitored list and detail (`officers/monitored/page.tsx:213`, `[id]/page.tsx:413`).
 * The inspector's queue applies no status filter, so PRE_SUBMISSION is most of what is ON that
 * queue: every report waiting for an officer was painted exactly like one nobody had typed into.
 * The label was missing too, so `humanize()` answered "Pre submission" while the filter dropdown one
 * page over offers "Pre-submission" — a designer filtered by one word and got rows reading another.
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
 * · PRE_SUBMISSION — amber, and a DIFFERENT amber from COMPLETE's: same wash, but the border goes to
 *   full-strength amber-800 instead of COMPLETE's amber-500/30 hairline. Four things were weighed.
 *   (a) Amber rather than purple because the pill beside "Hand in for inspection" is the only
 *   confirmation that act has a confirmation: the edge into PRE_SUBMISSION comes from IN_PROGRESS
 *   and from COMPLETE, so a deeper purple would have changed almost nothing at the exact instant the
 *   change IS the feedback, which is the 2026-08-26 bug in a new coat. (b) Amber rather than green,
 *   because green here means an act finished (SUBMITTED, APPROVED) and a handed-in report is an act
 *   BEGUN — somebody else now owes it a decision. (c) Not COMPLETE's pill, because COMPLETE and
 *   PRE_SUBMISSION sit on one list and are exactly the distinction a designer needs to read at a
 *   glance: "I have finished with it" against "it is out of my hands". That is the ARCHIVED/DRAFT
 *   rule applied a second time — same family, different rung — rather than a new hue. (d) Not a
 *   filled purple-700 pill, which would have been unmistakable and would also have read as a second
 *   button beside the real one; purple-700 is the action colour and a non-interactive chip must not
 *   wear it.
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
  PRE_SUBMISSION: "border-amber-800 bg-amber-100 text-amber-800",
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
  // Curated for a second reason as well: `humanize()` answers "Pre submission", and the status
  // filter on `/design-workshops` (`STATUS_OPTIONS`, design-workshops/page.tsx:181), the refusal
  // sentences the server speaks ("in pre-submission") and the record page's own copy all hyphenate
  // it. A chip that spells the status differently from the control that filtered for it reads as a
  // different status.
  PRE_SUBMISSION: "Pre-submission",
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
