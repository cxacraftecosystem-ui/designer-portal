/**
 * The coverage map's wire shapes (`GET /map/coverage` and its three neighbours), and the pure
 * helpers the view needs: financial-year labels, quantile bins and the metric a district is
 * shaded by. Both portals carry the same contract; the designer portal adds design workshops,
 * planned workshops and the Field Repository's `peer` row.
 */

export type CoverageLevel = "STATE" | "DISTRICT";
export type PolygonSource = "OWN" | "PARENT" | "STATE";

export type CoverageCounts = {
  designWorkshops: number;
  plannedWorkshops: number;
  workshops: number;
  artisans: number;
  crafts: number;
  products: number;
  tools: number;
  media: number;
};

export type PeerCounts = {
  workshops: number;
  artisans: number;
  products: number;
  tools: number;
  media: number;
};

export type CoverageUnit = {
  stateLgd: number;
  districtLgd: number | null;
  state: string;
  district: string | null;
  polygon: PolygonSource;
  parentLgd: number | null;
  counts: CoverageCounts;
  peer: PeerCounts | null;
  craftCodes: string[];
  clusters: string[];
};

export type MarkerKind = "DESIGN_WORKSHOP" | "PLANNED" | "CLUSTER" | "WORKSHOP";

export type CoverageMarker = {
  kind: MarkerKind;
  id: string;
  title: string;
  clusterName: string | null;
  craftCode: string | null;
  stateLgd: number;
  districtLgd: number | null;
  lon: number;
  lat: number;
  precision: "SUBJECT_PIN" | "DISTRICT_CENTROID";
  href: string;
};

export type NamedCode = { lgd: number | null; name: string };

export type CoverageGaps = {
  craftsNeverDocumented: Array<{ code: string; name: string }>;
  districtsWithoutWorkshop: {
    count: number;
    byState: Array<{ stateLgd: number; state: string; total: number; without: NamedCode[] }>;
  };
  statesWithoutWorkshop: NamedCode[];
};

export type UnmatchedSample = {
  model: string;
  rawState: string | null;
  rawDistrict: string | null;
  rows: number;
};

export type PeerStatus = "OFF" | "OK" | "UNAVAILABLE";

export type CoverageFilters = {
  level: CoverageLevel;
  craftCode: string[];
  scheme: string[];
  fy: number[];
  stateLgd: number | null;
};

export type CoverageResponse = {
  asOf: string;
  register: { lgdAsOf: string; listVersion: number; districtCount: number; stateCount: number };
  boundaries: { shapesVersion: string | null; depiction: string };
  filters: CoverageFilters;
  catalogue: {
    crafts: Array<{ code: string; name: string }>;
    schemes: Array<{ value: string; label: string }>;
    years: Array<{ fy: number; label: string }>;
  };
  units: CoverageUnit[];
  markers: CoverageMarker[];
  markersTruncated: boolean;
  gaps: CoverageGaps;
  unmatched: { count: number; unplaced?: number; sample?: UnmatchedSample[] };
  peer?: { status: PeerStatus; asOf: string | null; unknownUnits?: number; schemeIgnored?: boolean };
  truncated: boolean;
};

export const BUCKETS = [
  "designWorkshops",
  "plannedWorkshops",
  "workshops",
  "artisans",
  "products",
  "tools",
  "media"
] as const;
export type Bucket = (typeof BUCKETS)[number];

export const BUCKET_LABEL: Record<Bucket, string> = {
  designWorkshops: "Design workshops",
  plannedWorkshops: "Planned workshops",
  workshops: "Field workshops",
  artisans: "Artisans",
  products: "Products",
  tools: "Tools",
  media: "Media"
};

export type RecordStub = {
  id: string;
  title: string;
  href: string;
  craftCode: string | null;
  fy: number | null;
};

export type UnitRecordsResponse = {
  unit: CoverageUnit;
  buckets: Partial<Record<Bucket, { total: number; items: RecordStub[]; nextCursor: string | null }>>;
  peerHref: string | null;
};

export type UnmatchedItem = UnmatchedSample & {
  suggestion: { stateLgd: number; districtLgd: number; reason: string } | null;
};

export type LgdRegister = {
  states: Array<{ lgd: number; name: string }>;
  districts: Array<{ lgd: number; stateLgd: number; name: string }>;
};

export type UnmatchedResponse = { items: UnmatchedItem[]; total: number; register?: LgdRegister };

// ---------------------------------------------------------------------------------------------
// Metric and bins
// ---------------------------------------------------------------------------------------------

export const METRICS = ["workshops", "artisans", "records"] as const;
export type Metric = (typeof METRICS)[number];

export const METRIC_LABEL: Record<Metric, string> = {
  workshops: "Workshops",
  artisans: "Artisans",
  records: "All records"
};

/**
 * The number a district is shaded by. "Workshops" is held design workshops plus field workshops —
 * both are workshops that HAPPENED; planned ones are never added in. "All records" is every
 * documented record this portal holds there. The Field Repository's row is never added to either.
 */
export function metricValue(unit: CoverageUnit, metric: Metric): number {
  const c = unit.counts;
  if (metric === "workshops") return c.designWorkshops + c.workshops;
  if (metric === "artisans") return c.artisans;
  return c.designWorkshops + c.workshops + c.artisans + c.products + c.tools + c.media;
}

export const BIN_COUNT = 5;

/**
 * Upper bounds of five quantile bins over the positive values (zero is its own "none" tone and
 * never a bin). Duplicate bounds collapse, so a corpus where every district holds one record gets
 * one bin rather than five identical ones.
 */
export function quantileBreaks(values: number[], bins = BIN_COUNT): number[] {
  const positive = values.filter((v) => v > 0).sort((a, b) => a - b);
  if (!positive.length) return [];
  const breaks: number[] = [];
  for (let i = 1; i <= bins; i += 1) {
    const index = Math.min(positive.length - 1, Math.ceil((i / bins) * positive.length) - 1);
    const bound = positive[index];
    if (!breaks.includes(bound)) breaks.push(bound);
  }
  return breaks;
}

/** The bin a value falls in: -1 for zero, else 0..breaks.length-1. */
export function binOf(value: number, breaks: number[]): number {
  if (value <= 0 || !breaks.length) return -1;
  const index = breaks.findIndex((bound) => value <= bound);
  return index === -1 ? breaks.length - 1 : index;
}

/**
 * Which of the five fills a bin wears. Fewer than five bins take the DARK end of the ramp, so a
 * corpus with one figure per district shades it clearly rather than in the palest step.
 */
export function fillIndex(bin: number, breaks: number[]): number {
  return bin < 0 ? -1 : bin + Math.max(0, BIN_COUNT - breaks.length);
}

/** "0", "1", "2–4" … for a legend row. */
export function binLabel(index: number, breaks: number[]): string {
  const low = index === 0 ? 1 : breaks[index - 1] + 1;
  const high = breaks[index];
  return low >= high ? `${high}` : `${low}–${high}`;
}

/** A financial year by its start year: 2025 is "2025-26". */
export function fyLabel(fy: number): string {
  return `${fy}-${String((fy + 1) % 100).padStart(2, "0")}`;
}

/** The financial year a moment falls in, in India Standard Time (the year turns on 1 April). */
export function fyOf(when: Date): number {
  const ist = new Date(when.getTime() + 330 * 60_000);
  const year = ist.getUTCFullYear();
  return ist.getUTCMonth() >= 3 ? year : year - 1;
}
