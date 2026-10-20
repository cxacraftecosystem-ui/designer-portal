/**
 * The coverage map's wire shapes — `GET /map/coverage` and its sub-routes, exactly as
 * `backend/app/services/coverage.py` builds them. Field for field the Field Repository's
 * `coverageTypes.ts`; the designer portal adds the optional fields only it sends (design and planned
 * workshops, the Field Repository's `peer` row, the admin register) and never renames a shared one.
 * Android mirrors this file in `data/CoverageModels.kt`.
 */

export type CoverageLevel = "STATE" | "DISTRICT";

/** How a unit's outline is drawn: its own polygon, the parent district's, or its state's. */
export type PolygonSource = "OWN" | "PARENT" | "STATE";

export type CoverageCounts = {
  workshops: number;
  artisans: number;
  crafts: number;
  products: number;
  tools: number;
  media: number;
  /** Designer portal only. */
  designWorkshops?: number;
  plannedWorkshops?: number;
};

/** Designer portal only: the Field Repository's figures for the same unit, never added to ours. */
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

export type CoverageMarker = {
  kind: "DESIGN_WORKSHOP" | "PLANNED" | "CLUSTER" | "WORKSHOP";
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

export type GapDistrict = { lgd: number | null; name: string };

export type CoverageGaps = {
  craftsNeverDocumented: Array<{ code: string; name: string }>;
  districtsWithoutWorkshop: {
    count: number;
    byState: Array<{ stateLgd: number; state: string; total: number; without: GapDistrict[] }>;
  };
  statesWithoutWorkshop: Array<{ lgd: number; name: string }>;
};

export type UnmatchedItem = {
  model: "DesignWorkshop" | "AnnualPlanEntry" | "Location" | "Workshop";
  rawState: string | null;
  rawDistrict: string | null;
  rows: number;
  suggestion: { stateLgd: number; districtLgd: number | null; reason: string } | null;
};

/** Designer portal only: whether the Field Repository's figures were asked for and reached. */
export type PeerStatus = "OFF" | "OK" | "UNAVAILABLE";

export type CoverageResponse = {
  asOf: string;
  register: { lgdAsOf: string; listVersion: number; districtCount: number; stateCount: number };
  boundaries: { shapesVersion: string | null; depiction: string };
  filters: {
    level: CoverageLevel;
    craftCode: string[];
    scheme: string[];
    fy: number[];
    stateLgd: number | null;
  };
  catalogue: {
    crafts: Array<{ code: string; name: string }>;
    schemes: Array<{ value: string; label: string }>;
    years: Array<{ fy: number; label: string }>;
  };
  units: CoverageUnit[];
  markers: CoverageMarker[];
  markersTruncated: boolean;
  gaps: CoverageGaps;
  unmatched: { count: number; unplaced?: number; sample?: UnmatchedItem[] };
  /** Designer portal only. */
  peer?: { status: PeerStatus; asOf: string | null; unknownUnits?: number; schemeIgnored?: boolean };
  truncated: boolean;
};

export type CoverageBucket =
  | "designWorkshops"
  | "plannedWorkshops"
  | "workshops"
  | "artisans"
  | "products"
  | "tools"
  | "media";

export type UnitRecordsResponse = {
  unit: Omit<CoverageUnit, "counts" | "peer" | "craftCodes" | "clusters"> &
    Partial<Pick<CoverageUnit, "counts" | "peer">>;
  buckets: Partial<
    Record<
      CoverageBucket,
      {
        total: number;
        items: Array<{ id: string; title: string; href: string; craftCode: string | null; fy: number | null }>;
        nextCursor: string | null;
      }
    >
  >;
  peerHref: string | null;
};

/** Designer portal only: the LGD register the admin alias pickers choose from. */
export type LgdRegister = {
  states: Array<{ lgd: number; name: string }>;
  districts: Array<{ lgd: number; stateLgd: number; name: string }>;
};

export type UnmatchedResponse = { items: UnmatchedItem[]; total: number; register?: LgdRegister };

/** What the choropleth colours by. `records` is read as `all` for saved links (`coverageMath`). */
export type CoverageMetric = "workshops" | "artisans" | "all";

/** The filters the view keeps in the URL. `peer` is the designer portal's Field Repository switch. */
export type CoverageQuery = {
  level: CoverageLevel;
  craftCode: string[];
  scheme: string[];
  fy: number[];
  stateLgd: number | null;
  peer?: boolean;
};
