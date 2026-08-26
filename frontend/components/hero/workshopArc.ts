/**
 * The shape of a design & prototype workshop, as the landing page draws it — and the ONE place the
 * number of stages is stated on this page.
 *
 * ── WHERE THE FACTS COME FROM ────────────────────────────────────────────────────────────────
 *
 * `backend/app/services/stage_definitions.py` declares `STAGE_1` … `STAGE_22` and collects them in
 * `ALL_STAGES` (that file's last lines). Every `title` below is a compression of the real
 * `StageSpec.title`s in that range, in that order:
 *
 *    1 Workshop Setup & Cover Information          12 Sketch Review & Shortlisting
 *    2 Introduction & Administrative Documentation  13 Prototype Development
 *    3 Workshop Plan, Participants & Opening        14 Prototype Iteration & Testing
 *    4 Cluster, Area & Craft Background             15 Prototype Selection & Validation
 *    5 Traditional Process, Tools & Raw Materials   16 Final Product Documentation
 *    6 Existing Products & Artisan Baseline         17 Costing, Packaging & Market Linkage
 *    7 Market Survey Planning                       18 Workshop Outcomes, Problems & Feedback
 *    8 Market Survey & Field Data                   19 Inspection & Closing
 *    9 Market Analysis & Design Direction           20 Report Generation & Submission
 *   10 Design Brief & Concept                       21 Data Quality & Archive
 *   11 Sketch Development                           22 Post-Workshop Follow-up
 *
 * The registry the browser renders forms from is FETCHED at runtime (`fetchStageRegistry` in
 * `lib/designWorkshops.ts`), so there is nothing statically importable to derive this from on a
 * PRERENDERED page with no auth — an import of that module would put an authenticated API client
 * in the landing page's bundle and still not know the count until a request had been answered.
 * Copied, therefore, and copied ONCE.
 *
 * ── WHY THE COUNT IS DERIVED FROM THE ARC AND NOT TYPED BESIDE IT ─────────────────────────────
 *
 * `AccessLadder.tsx` carries the argument at length and it was learned the expensive way there: a
 * heading that spells a count over a list that renders its own rows is two claims a reader can see
 * at once, and they do not rot together. So {@link STAGE_COUNT} is the SUM of the groups below and
 * {@link STAGE_COUNT_WORD} is spelled from that sum. Split a group, merge two, or extend the last
 * one when stage 23 arrives, and every sentence on the page that states the number moves with it —
 * the hero paragraph, this section's lede and the FAQ all read this export rather than the word.
 *
 * The ranges are also RENDERED (`1–3`, `4–6`, …), which is the other half of the guarantee: a gap
 * or an overlap between two groups cannot hide inside a correct-looking total, because the badges
 * on screen would run `1–3`, `5–6` in front of the reader.
 */

/** One arc of the fortnight: a contiguous run of stages, and what a designer does across it. */
export type WorkshopArcGroup = {
  /** First stage number in the run, inclusive. */
  from: number;
  /** Last stage number in the run, inclusive. */
  to: number;
  title: string;
  copy: string;
};

export const WORKSHOP_ARC: readonly WorkshopArcGroup[] = [
  {
    from: 1,
    to: 3,
    title: "Setup, plan and participants",
    copy:
      "Cover information, the administrative papers a sanctioned workshop travels with, and who is actually in the room."
  },
  {
    from: 4,
    to: 6,
    title: "The cluster and its baseline",
    copy:
      "The area's craft background, the traditional process with its tools and raw materials, and the products the artisans already make."
  },
  {
    from: 7,
    to: 9,
    title: "Market survey and direction",
    copy:
      "Planning the survey, the field data it brings back, and the analysis that turns that data into a design direction."
  },
  {
    from: 10,
    to: 12,
    title: "Brief, sketches, shortlist",
    copy: "The design brief, the sketches drawn from it, and the review that shortlists them."
  },
  {
    from: 13,
    to: 16,
    title: "Prototypes, then documentation",
    copy:
      "Development, iteration and testing, selection and validation — then the chosen product documented properly."
  },
  {
    from: 17,
    to: 19,
    title: "Costing, outcomes, closing",
    copy:
      "Costing, packaging and market linkage; what worked and what did not; inspection and the closing of the workshop."
  },
  {
    from: 20,
    to: 22,
    title: "Report, archive, follow-up",
    copy:
      "Generating and submitting the report, the data-quality pass over the record, and the follow-up after everybody has gone home."
  }
];

/** How many stages the arc above covers. See the header: this is the sum, never a typed literal. */
export const STAGE_COUNT = WORKSHOP_ARC.reduce((total, group) => total + (group.to - group.from + 1), 0);

/**
 * The count, spelled, because prose wants a word.
 *
 * The same idiom as `AccessLadder.NUMBER_WORD` and deliberately a separate table rather than an
 * import: that map is a private detail of a component whose whole subject is the role ladder, and
 * reaching across for it would make a change to the ladder's heading able to change this sentence.
 * The lookup FALLS BACK to the numeral rather than to a stale word, so an unmapped count renders
 * "23 stages" and never the wrong word.
 */
const SPELLED: Record<number, string | undefined> = {
  18: "Eighteen",
  19: "Nineteen",
  20: "Twenty",
  21: "Twenty-one",
  22: "Twenty-two",
  23: "Twenty-three",
  24: "Twenty-four",
  25: "Twenty-five"
};

/** Sentence-initial form, e.g. `Twenty-two`. */
export const STAGE_COUNT_WORD = SPELLED[STAGE_COUNT] ?? String(STAGE_COUNT);

/** Mid-sentence form, e.g. `twenty-two`. Lower-cased from the one source, never typed twice. */
export const STAGE_COUNT_WORD_LOWER = STAGE_COUNT_WORD.toLowerCase();

/** `1–3`, with an EN DASH, which is the correct dash for a numeric range. */
export function arcRangeLabel(group: WorkshopArcGroup): string {
  return group.from === group.to ? String(group.from) : `${group.from}–${group.to}`;
}
