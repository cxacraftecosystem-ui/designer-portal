import { apiFetch } from "@/lib/api";

export type AdoptionGroup = {
  dimension: string;
  label: string;
  workshops: number;
  observations: number;
  adopted: number;
  trial: number;
  notAdopted: number;
  unknown: number;
  rate: number | null;
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
  cohortPossible: boolean;
  trackedTo12: number;
  stillAdoptedAt12: number;
  unobservedAfter3: number;
  rowsWithoutProduct: number;
  message: string;
  crossSections: IntervalCrossSection[];
};

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
  cautions: string[];
  notes: string[];
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

export function isWithheld(group: { rate: number | null }): boolean {
  return group.rate === null;
}

export function adoptionRateText(group: { rate: number | null }): string {
  return group.rate === null ? "—" : `${Math.round(group.rate * 100)}%`;
}

export function loadWorkshopAnalytics(): Promise<WorkshopAnalytics> {
  return apiFetch<WorkshopAnalytics>("/analytics/design-workshops");
}
