/**
 * The wire shape of `GET /api/analytics/design-workshops`, and the two helpers a caller needs to
 * render it without lying.
 *
 * THIS FILE IS A TYPE DECLARATION, NOT A SECOND IMPLEMENTATION. Every number on this wire is
 * computed by `backend/app/services/workshop_analytics.py`, including every sentence in a `message`
 * field. That is deliberate and it is the opposite of `lib/marketAnalysis.ts`, which IS a port:
 * market analysis reads the one workshop in front of it, so it can and must run on the handset with
 * no network. A cross-workshop comparison's input is the other workshops, which a browser does not
 * have — so there is nothing here to compute, and re-deriving a rate from `adopted / observations`
 * in a component would quietly bypass every floor the server applies.
 *
 * WHICH BRINGS US TO THE ONE RULE THIS FILE EXISTS TO ENFORCE.
 *
 * **`rate: null` is a REFUSAL and never a zero.** The server withholds a rate when a group has too
 * few workshops or too few outcomes behind it; `null` means "we cannot say", and `0` means "nothing
 * was adopted". Rendering the first as the second is the single worst thing this page could do — it
 * would report a total failure of the scheme in exactly the clusters that have simply not been
 * followed up yet. TypeScript will not catch it, because `null` coerces to 0 through arithmetic and
 * through `??`. {@link adoptionRateText} and {@link isWithheld} are the two places that decide, so
 * no component has to remember.
 */

import { apiFetch } from "@/lib/api";

/** How a figure was withheld, or that it was not. */
export type AdoptionGroup = {
  /** ARCHIVE | CRAFT | CLUSTER | STATE */
  dimension: string;
  /** "" for the archive-wide row. */
  label: string;
  workshops: number;
  /** The denominator: outcomes with a decided status. Excludes "not known". */
  observations: number;
  adopted: number;
  trial: number;
  notAdopted: number;
  /** Recorded as "not known" — reported beside the rate, never inside it. */
  unknown: number;
  /** null = withheld. See the note at the top of this file. */
  rate: number | null;
  /** 0–1. Above 0.6 the server's `message` already leads with the caution. */
  largestWorkshopShare: number;
  message: string;
};

export type IntervalCrossSection = {
  interval: string;
  label: string;
  workshops: number;
  observations: number;
  adopted: number;
  rate: number | null;
  message: string;
};

export type Survival = {
  /** False when no follow-up row carries a product reference — see the page's copy. */
  cohortPossible: boolean;
  trackedTo12: number;
  stillAdoptedAt12: number;
  unobservedAfter3: number;
  /** Follow-up rows at a 3/6/12 interval with no product reference — they can join no cohort. */
  rowsWithoutProduct: number;
  message: string;
  crossSections: IntervalCrossSection[];
};

/** A sample's shape. `quantilesReported` is false when it was too small to describe. */
export type Distribution = {
  count: number;
  minimum: number;
  maximum: number;
  mean: number;
  p25: number;
  median: number;
  p75: number;
  quantilesReported: boolean;
};

export type CostRatioGroup = {
  cluster: string;
  workshops: number;
  sheets: number;
  uncostable: number;
  belowCost: number;
  /** null = too few sheets to describe a spread. */
  ratio: Distribution | null;
  message: string;
};

export type RecurringCategory = { category: string; workshops: number; occurrences: number };

export type RecurringTerm = {
  term: string;
  workshops: number;
  occurrences: number;
  examples: string[];
};

export type WorkshopAnalytics = {
  /** Always rendered above the figures. The server puts them first for that reason. */
  cautions: string[];
  notes: string[];
  /** Questions that were asked for and that the field registry cannot answer, with the reason. */
  notComputed: { question: string; reason: string }[];
  truncated: boolean;
  coverage: {
    workshopsInArchive: number;
    workshopsAnalysed: number;
    workshopsWithFollowUp: number;
    workshopsWithCostSheets: number;
    workshopsWithOpportunities: number;
    followUpRows: number;
    rowsWithoutStatus: number;
    supersededRows: number;
    missingDimension: { craft: number; cluster: number; state: number };
  };
  overall: AdoptionGroup;
  byCraft: AdoptionGroup[];
  byCluster: AdoptionGroup[];
  byState: AdoptionGroup[];
  survival: Survival;
  costRatios: { sheetsWithoutCluster: number; groups: CostRatioGroup[] };
  opportunities: {
    workshopsNaming: number;
    categories: RecurringCategory[];
    terms: RecurringTerm[];
  };
  reach: {
    rows: number;
    rowsWithRevenue: number;
    revenue: number;
    rowsWithUnitsSold: number;
    unitsSold: number;
    rowsWithOrders: number;
    orders: number;
    message: string;
  };
};

/**
 * Whether a figure was withheld rather than measured as zero.
 *
 * Written as a function taking the whole group rather than `rate === null` at each call site,
 * because the two conditions read identically at a glance and the wrong one ships silently.
 */
export function isWithheld(group: { rate: number | null }): boolean {
  return group.rate === null;
}

/**
 * A rate as text, or the em dash that stands for "not stated".
 *
 * NOT "0%", NOT "—%", NOT "n/a". A bare em dash beside a populated count is the only rendering that
 * cannot be misread as a measurement, and the row's own message says why it is missing.
 */
export function adoptionRateText(group: { rate: number | null }): string {
  return group.rate === null ? "—" : `${Math.round(group.rate * 100)}%`;
}

export function loadWorkshopAnalytics(): Promise<WorkshopAnalytics> {
  return apiFetch<WorkshopAnalytics>("/analytics/design-workshops");
}
