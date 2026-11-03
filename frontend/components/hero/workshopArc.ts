export type WorkshopArcGroup = {
  from: number;
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

export const STAGE_COUNT = WORKSHOP_ARC.reduce((total, group) => total + (group.to - group.from + 1), 0);

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

export const STAGE_COUNT_WORD = SPELLED[STAGE_COUNT] ?? String(STAGE_COUNT);

export const STAGE_COUNT_WORD_LOWER = STAGE_COUNT_WORD.toLowerCase();

export function arcRangeLabel(group: WorkshopArcGroup): string {
  return group.from === group.to ? String(group.from) : `${group.from}–${group.to}`;
}
