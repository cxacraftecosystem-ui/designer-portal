/**
 * `backend/app/services/report_builder.py`, ported: a workshop's data and a template become the
 * blocks of its report, in the browser, with no connection.
 *
 * READ THE SERVER'S MODULE FOR THE WHY. Every rule below — which fields a template's tier admits,
 * how a collection becomes a table or a run of cards, why an iteration is grouped under its
 * prototype, why a photograph cap is counted per gallery, why a REF prints its label and never an id
 * — is argued at length beside the Python it was ported from, and is not argued a second time here.
 * What this file promises is narrower and checkable: for the same inputs it produces the same blocks
 * and the same warnings, which `frontend/e2e/offline-report-parity-unit.spec.ts` asserts against the
 * server's own output for the shared fixture (`shared/report-parity/`).
 */

import {
  DocumentBuilder,
  asNumber,
  chartTotal,
  cleanText,
  collapseSpace,
  column,
  groupIndian,
  imageBlock,
  pyFixed,
  pyG,
  pyRound,
  pyStr,
  pyStrip,
  pySum,
  runsOf,
  table,
  type Block,
  type ChartBlock,
  type ImageRef,
  type MapPoint,
  type MapPointKind,
  type ReportDocument,
  type ReportMeta,
  type ReportTheme,
  type Run
} from "@/lib/offlineReport/model";
import {
  appendAiLayerAnnexure,
  appendCustomSection,
  appendQuestionnaireAnnexure,
  appendTranscriptAnnexure,
  customScoring,
  pyCompare,
  sectionsHiddenByTier,
  type AiLayerItem,
  type CustomReportField,
  type CustomSectionItem,
  type QuestionnaireItem,
  type TranscriptItem
} from "@/lib/offlineReport/annexures";
import { canonicalState, geocode, type Located } from "@/lib/offlineReport/geocode";
import { isEmptyRich, plainRuns, toPlain, toReportBlocks } from "@/lib/offlineReport/richText";
import { TIER_RANK, pyTruthy, sectionFor, type ReportTemplate, type TemplateSection, type Tier } from "@/lib/offlineReport/templates";

/* ────────────────────────────────────────────────────────────────────────────
 * The registry, as `GET /design-workshops/schema` serves it
 * ──────────────────────────────────────────────────────────────────────────── */

export type RegistryField = {
  key: string;
  label: string;
  type: string;
  tier: string;
  required: boolean;
  unit?: string;
  enum?: string;
  refModel?: string;
  refHydration?: Record<string, string>;
  minItems?: number;
  reportRole?: string;
  columnWidthPct?: number;
  captionFor?: string;
  deprecated?: boolean;
};
export type RegistryEntity = {
  key: string;
  name: string;
  cardinality: string;
  title: string;
  parent: string;
  labelField: string;
  fields: RegistryField[];
};
export type RegistryStage = { number: number; key: string; title: string; entities: RegistryEntity[] };
export type Registry = { version: string; enums: Record<string, Array<{ value: string; label: string }>>; stages: RegistryStage[] };

const MEDIA_TYPES = new Set(["IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO"]);
const NUMERIC_TYPES = new Set(["INT", "DECIMAL", "MONEY", "PERCENT"]);
const FREE_TEXT_TYPES = new Set(["TEXT", "LONG_TEXT", "RICH_TEXT"]);

const roleOf = (f: RegistryField) => f.reportRole || "KEY_VALUE";
const isMedia = (f: RegistryField) => MEDIA_TYPES.has(f.type);
const isNumeric = (f: RegistryField) => NUMERIC_TYPES.has(f.type);
const isFreeText = (f: RegistryField) => FREE_TEXT_TYPES.has(f.type);
const fieldOf = (entity: RegistryEntity, key: string) => entity.fields.find((f) => f.key === key);
const singletonOf = (stage: RegistryStage) => stage.entities.find((e) => e.cardinality === "SINGLETON");
const collectionsOf = (stage: RegistryStage) => stage.entities.filter((e) => e.cardinality === "COLLECTION");

/* ────────────────────────────────────────────────────────────────────────────
 * The data the builder reads — `report_builder.WorkshopData`
 * ──────────────────────────────────────────────────────────────────────────── */

export type ReferencedRecord = { model: string; label: string; photo: string; place: string; district: string; state: string };
export type Row = Record<string, unknown>;

export type WorkshopData = {
  workshopId: string;
  title: string;
  /** One singleton per stage, exactly as `assemble_workshop_data` keeps it. */
  singletons: Record<string, Row>;
  collections: Record<string, Record<string, Row[]>>;
  references: Record<string, ReferencedRecord>;
  districtPoints: Record<string, [number, number]>;
  questionnaires: QuestionnaireItem[];
  transcripts: TranscriptItem[];
  aiLayers: AiLayerItem[];
  customSections: CustomSectionItem[];
};

const singleton = (data: WorkshopData, stageKey: string): Row => data.singletons[stageKey] ?? {};
const rowsOf = (data: WorkshopData, stageKey: string, entityKey: string): Row[] => data.collections[stageKey]?.[entityKey] ?? [];
const referenceOf = (data: WorkshopData, id: unknown): ReferencedRecord | null =>
  typeof id === "string" && id ? (data.references[id] ?? null) : null;

export type MediaResolver = (mediaId: string) => ImageRef | null;

/* ────────────────────────────────────────────────────────────────────────────
 * Value formatting — `format_value` and its helpers
 * ──────────────────────────────────────────────────────────────────────────── */

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const PY_INT = /^\s*[+-]?\d+(?:_\d+)*\s*$/;

/** `_format_date`: "2026-07-06" -> "06 Jul 2026", and anything unparseable printed as it came. */
export function formatDate(iso: string): string {
  const parts = pyStrip(iso).slice(0, 10).split("-");
  if (parts.length !== 3 || !parts.every((p) => PY_INT.test(p))) return cleanText(iso);
  const [year, month, day] = parts.map((p) => Number.parseInt(p.replace(/_/g, ""), 10));
  const index = month - 1;
  if (index > 11 || index < -12) return cleanText(iso);
  const name = MONTHS[(index + 12) % 12];
  const dayText = day < 0 ? `-${String(-day).padStart(1, "0")}` : String(day).padStart(2, "0");
  return `${dayText} ${name} ${year}`;
}

function enumLabel(registry: Registry, enumName: string | undefined, value: string): string {
  const options = registry.enums[enumName ?? ""] ?? [];
  return options.find((o) => o.value === value)?.label ?? value;
}

function iterate(value: unknown): unknown[] {
  if (Array.isArray(value)) return value;
  if (typeof value === "string") return Array.from(value);
  if (value && typeof value === "object") return Object.keys(value as object);
  return [];
}

export function mediaIds(value: unknown): string[] {
  if (!value) return [];
  if (typeof value === "string") return [value];
  if (Array.isArray(value)) return value.filter((v) => pyTruthy(v)).map((v) => pyStr(v));
  return [];
}

/** `report_builder.format_value`. */
export function formatValue(registry: Registry, spec: Pick<RegistryField, "type" | "unit" | "enum" | "refModel">, value: unknown): string {
  if (value === null || value === undefined || value === "" || (Array.isArray(value) && value.length === 0)) return "";
  const t = spec.type;
  const unit = spec.unit ?? "";
  if (t === "RICH_TEXT") return toPlain(value);
  if (t === "ENUM") return enumLabel(registry, spec.enum, pyStr(value));
  if (t === "MULTI_ENUM") {
    if (spec.refModel) return iterate(value).map(pyStr).join(", ");
    return iterate(value)
      .map((v) => enumLabel(registry, spec.enum, pyStr(v)))
      .join(", ");
  }
  if (t === "TAGS") return iterate(value).map(pyStr).join(", ");
  if (t === "BOOL") return pyTruthy(value) ? "Yes" : "No";
  if (t === "MONEY") {
    const amount = asNumber(value);
    if (amount === null) return cleanText(value);
    const [whole, frac] = pyFixed(amount, 2).split(".");
    const sign = whole.startsWith("-") ? "-" : "";
    return `${sign}₹ ${groupIndian(whole.replace(/^-+/, ""))}.${frac}`;
  }
  if (t === "PERCENT") {
    const percent = asNumber(value);
    return percent !== null ? `${pyG(percent)}%` : cleanText(value);
  }
  if (t === "INT" || t === "DECIMAL") {
    const number = asNumber(value);
    if (number === null) return cleanText(value);
    let text = t === "INT" ? pyFixed(number, 0) : pyG(number);
    if (Math.abs(number) >= 10000) {
      const [whole, frac] = pyFixed(Math.abs(number), 2).split(".");
      text = (number < 0 ? "-" : "") + groupIndian(whole);
      const trimmed = frac.replace(/0+$/, "");
      if (t !== "INT" && trimmed) text += "." + trimmed;
    }
    return unit ? pyStrip(`${text} ${unit}`) : text;
  }
  if (t === "DATE") return formatDate(pyStr(value));
  if (t === "GEO") {
    if (value && typeof value === "object" && !Array.isArray(value)) {
      const geo = value as Record<string, unknown>;
      const lat = Number(geo.lat ?? 0);
      const lon = Number(geo.lon ?? 0);
      return `${pyFixed(lat, 5)}, ${pyFixed(lon, 5)}`;
    }
    return cleanText(value);
  }
  if (t === "IMAGE" || t === "IMAGE_LIST") return "";
  if (MEDIA_TYPES.has(t)) {
    const count = mediaIds(value).length;
    if (!count) return "";
    const [singular, plural] =
      t === "AUDIO" ? ["recording", "recordings"] : t === "VIDEO" ? ["video", "videos"] : ["document", "documents"];
    return `${count} ${count === 1 ? singular : plural} attached`;
  }
  const text = cleanText(value);
  return unit && text ? pyStrip(`${text} ${unit}`) : text;
}

const OPAQUE_ID = /^[a-z0-9]{16,}$/;
const looksLikeAnId = (text: string) => OPAQUE_ID.test(pyStrip(text));

const HEADING_CHARS = 80;

/** `_heading_summary`: the first sentence if it is short, else the text cut at a word. */
function headingSummary(text: string): string {
  const collapsed = collapseSpace(text);
  if (!collapsed) return "";
  const chars = Array.from(collapsed);
  for (let i = 0; i < chars.length; i += 1) {
    if (".?!".includes(chars[i]) && (i + 1 === chars.length || chars[i + 1] === " ")) {
      const sentence = chars.slice(0, i + 1).join("");
      if (i + 1 <= HEADING_CHARS) return sentence;
      break;
    }
  }
  if (chars.length <= HEADING_CHARS) return collapsed;
  const cut = chars.slice(0, HEADING_CHARS - 1).join("");
  const spaced = cut.includes(" ") ? cut.slice(0, cut.lastIndexOf(" ")) : cut;
  return `${spaced.replace(/[ ,;:-]+$/, "")}…`;
}

function submissionLine(settings: Row): string {
  const to = pyStrip(cleanText(settings.submittedTo));
  const on = pyStrip(cleanText(settings.submissionDate));
  const when = on ? formatDate(on) : "";
  if (to && when) return `Submitted to ${to} on ${when}`;
  if (to) return `Submitted to ${to}`;
  return when ? `Submitted on ${when}` : "";
}

function geoPoint(value: unknown): [number, number] | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const geo = value as Record<string, unknown>;
  const lat = typeof geo.lat === "number" ? geo.lat : typeof geo.lat === "string" ? asNumber(geo.lat) : null;
  const lon = typeof geo.lon === "number" ? geo.lon : typeof geo.lon === "string" ? asNumber(geo.lon) : null;
  if (lat === null || lon === null) return null;
  if (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001) return null;
  return [lat, lon];
}

/** Python's `round(x, n)`: the correctly rounded decimal, half to even, back as a number. */
const pyRoundTo = (value: number, digits: number) => Number(pyFixed(value, digits));

type MapFacts = { points: MapPoint[]; states: Set<string>; placed: number; total: number; approximate: number };
const emptyFacts = (total = 0): MapFacts => ({ points: [], states: new Set(), placed: 0, total, approximate: 0 });

function foldPoints(found: Array<[string, Located]>, kind: MapPointKind): MapPoint[] {
  const folded = new Map<string, { lat: number; lon: number; label: string; count: number }>();
  for (const [label, place] of found) {
    const lat = pyRoundTo(place.lat, 5);
    const lon = pyRoundTo(place.lon, 5);
    const key = `${lat}|${lon}`;
    const existing = folded.get(key);
    folded.set(key, { lat, lon, label: existing ? existing.label : label, count: (existing?.count ?? 0) + 1 });
  }
  return [...folded.values()].map((p) => ({ label: p.label, lat: p.lat, lon: p.lon, kind, count: p.count }));
}

const MIN_CHART_CATEGORIES = 2;
const PRICE_STEPS = [100, 250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000];
const MAX_PRICE_BANDS = 6;

function priceBands(values: number[]): Array<[string, number]> {
  const positive = values.filter((v) => v > 0).sort((a, b) => a - b);
  if (positive.length < 3) return [];
  const top = positive[positive.length - 1];
  if (top <= positive[0]) return [];
  const step = PRICE_STEPS.find((s) => top / s <= MAX_PRICE_BANDS) ?? PRICE_STEPS[PRICE_STEPS.length - 1];
  const counts = new Map<number, number>();
  for (const value of positive) {
    const index = Math.floor(value / step);
    counts.set(index, (counts.get(index) ?? 0) + 1);
  }
  return [...counts.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([index, count]) => [
      `${groupIndian(pyFixed(index * step, 0))}–${groupIndian(pyFixed((index + 1) * step - 1, 0))}`,
      count
    ]);
}

const FIGURES: Array<[string, string]> = [
  ["OUTPUT_COUNTS", "WORKSHOP_OUTCOMES"],
  ["PROTOTYPE_STATUS", "WORKSHOP_OUTCOMES"],
  ["SURVEY_RESPONDENTS", "MARKET_SURVEY_CAPTURE"],
  ["SURVEY_PRICE_EXPECTATIONS", "MARKET_SURVEY_CAPTURE"],
  ["COST_BY_HEAD", "COSTING_MARKET_LINKAGE"],
  ["PRICE_BANDS", "COSTING_MARKET_LINKAGE"],
  ["ADOPTION", "POST_WORKSHOP_FOLLOWUP"]
];

const COVER_INFO_ROWS = 10;
const REFERENCE_NAME_SOURCES: Record<string, string[]> = { Craft: ["craftName"] };

const MAP_VENUE_STAGE = "WORKSHOP_SETUP";
const MAP_ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING";
const MAP_ROSTER_ENTITY = "participant";
const MAP_ROSTER_PLACE_KEYS = ["village", "district"];
const MAP_ROSTER_STATE_KEY = "state";
const MAP_ROSTER_PIN_KEY = "subjectLocation";

function chart(kind: ChartBlock["kind"], series: Array<[string, number]>, title: string, caption: string, unit = ""): ChartBlock {
  return { type: "CHART", kind, series, title, caption, unit, width_pct: 74, align: "CENTER" };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Completeness — `stage_schema.stage_completeness`
 * ──────────────────────────────────────────────────────────────────────────── */

export type Completeness = {
  requiredTotal: number;
  requiredFilled: number;
  percent: number;
  isComplete: boolean;
  missing: string[];
};

function isFilledValue(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return Boolean(pyStrip(value));
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") {
    if ("blocks" in (value as object)) return !isEmptyRich(value);
    return Object.keys(value as object).length > 0;
  }
  return true;
}

function customFilled(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return Boolean(pyStrip(value));
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") return isFilledValue(value);
  return true;
}

export function stageCompleteness(
  stage: RegistryStage,
  single: Row,
  collections: Record<string, Row[]>,
  opts: { refResolves?: (value: unknown) => boolean; customFields?: CustomReportField[]; customValues?: Record<string, unknown> } = {}
): Completeness {
  let requiredTotal = 0;
  let requiredFilled = 0;
  const missing: string[] = [];
  const countsAsFilled = (f: RegistryField, value: unknown) => {
    if (!isFilledValue(value)) return false;
    if (f.minItems && !(Array.isArray(value) && value.length >= f.minItems)) return false;
    if (f.type !== "REF" || !opts.refResolves) return true;
    return opts.refResolves(value);
  };
  const shortfall = (f: RegistryField, value: unknown) =>
    f.minItems ? `${f.label} (${Array.isArray(value) ? value.length : 0} of ${f.minItems})` : f.label;
  const isRequired = (f: RegistryField) => f.required || Boolean(f.minItems);
  const singleEntity = singletonOf(stage);
  if (singleEntity) {
    for (const f of singleEntity.fields) {
      if (f.deprecated) continue;
      const value = single[f.key];
      if (isRequired(f)) {
        requiredTotal += 1;
        if (countsAsFilled(f, value)) requiredFilled += 1;
        else missing.push(shortfall(f, value));
      }
    }
  }
  const values = opts.customValues ?? {};
  for (const cf of opts.customFields ?? []) {
    if (cf.retired) continue;
    if (cf.required) {
      requiredTotal += 1;
      if (customFilled(values[cf.key])) requiredFilled += 1;
      else missing.push(cf.label || cf.key);
    }
  }
  for (const entity of collectionsOf(stage)) {
    for (const row of collections[entity.key] ?? []) {
      for (const f of entity.fields) {
        if (f.deprecated) continue;
        const value = row[f.key];
        if (isRequired(f)) {
          requiredTotal += 1;
          if (countsAsFilled(f, value)) requiredFilled += 1;
          else missing.push(`${entity.title}: ${shortfall(f, value)}`);
        }
      }
    }
  }
  const deduped = [...new Set(missing)];
  return {
    requiredTotal,
    requiredFilled,
    percent: requiredTotal === 0 ? 100 : pyRound((100 * requiredFilled) / requiredTotal),
    isComplete: requiredFilled >= requiredTotal,
    missing: deduped
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The builder
 * ──────────────────────────────────────────────────────────────────────────── */

export class ReportBuilder {
  readonly doc: DocumentBuilder;
  private readonly stages: Map<string, RegistryStage>;
  private readonly entities = new Map<string, RegistryEntity>();
  private readonly entityStage = new Map<string, string>();
  private readonly rowsById = new Map<string, [RegistryEntity | undefined, Row]>();
  private readonly labelCache = new Map<string, string>();
  private readonly drawn = new Set<string>();
  private readonly overCap = new Map<string, number>();
  private stageKey = "";
  coverFieldsDropped: string[] = [];

  constructor(
    private readonly registry: Registry,
    private readonly data: WorkshopData,
    private readonly template: ReportTemplate,
    private readonly resolveMedia: MediaResolver,
    meta: ReportMeta,
    theme: ReportTheme
  ) {
    this.doc = new DocumentBuilder(meta, theme);
    this.stages = new Map(registry.stages.map((s) => [s.key, s]));
    for (const s of registry.stages) {
      for (const e of s.entities) {
        this.entities.set(e.key, e);
        this.entityStage.set(e.key, s.key);
      }
    }
    for (const entities of Object.values(data.collections)) {
      for (const [entityKey, rows] of Object.entries(entities)) {
        const entity = this.entities.get(entityKey);
        for (const row of rows) {
          const id = row._entryId;
          if (typeof id === "string" && id) this.rowsById.set(id, [entity, row]);
        }
      }
    }
  }

  private get maxTierRank(): number {
    return TIER_RANK[this.template.maxTier];
  }

  private visible(spec: RegistryField): boolean {
    return !spec.deprecated && (TIER_RANK[spec.tier as Tier] ?? 0) <= this.maxTierRank;
  }

  private isFilled(spec: RegistryField, row: Row): boolean {
    if (isMedia(spec)) return mediaIds(row[spec.key]).length > 0;
    return Boolean(this.value(spec, row));
  }

  private rowsFor(stage: RegistryStage, entity: RegistryEntity): Row[] {
    return entity.cardinality === "SINGLETON" ? [singleton(this.data, stage.key)] : rowsOf(this.data, stage.key, entity.key);
  }

  fieldsHiddenByTier(): Array<[RegistryStage, string[]]> {
    const out: Array<[RegistryStage, string[]]> = [];
    for (const stage of this.registry.stages) {
      if (!sectionFor(this.template, stage.key)) continue;
      const lost: string[] = [];
      for (const entity of stage.entities) {
        const rows = this.rowsFor(stage, entity);
        for (const f of entity.fields) {
          if (this.visible(f) || f.deprecated) continue;
          if (roleOf(f) === "HIDDEN") continue;
          if (rows.some((row) => this.isFilled(f, row))) lost.push(f.label);
        }
      }
      if (lost.length) out.push([stage, lost]);
    }
    return out;
  }

  photographsOverCap(): Array<[RegistryStage, number]> {
    return this.registry.stages.filter((s) => this.overCap.get(s.key)).map((s) => [s, this.overCap.get(s.key) ?? 0]);
  }

  attachmentsNamedButNotCarried(): Array<[RegistryStage, number]> {
    const out: Array<[RegistryStage, number]> = [];
    for (const stage of this.registry.stages) {
      if (!sectionFor(this.template, stage.key)) continue;
      let named = 0;
      for (const entity of stage.entities) {
        const rows = this.rowsFor(stage, entity);
        for (const f of entity.fields) {
          if (!isMedia(f) || f.type === "IMAGE" || f.type === "IMAGE_LIST") continue;
          if (!this.visible(f) || roleOf(f) === "HIDDEN") continue;
          for (const row of rows) named += mediaIds(row[f.key]).length;
        }
      }
      if (named) out.push([stage, named]);
    }
    return out;
  }

  private refLabel(refId: unknown, seen: Set<string> = new Set()): string {
    if (typeof refId !== "string" || !refId) return "";
    const cached = this.labelCache.get(refId);
    if (cached !== undefined) return cached;
    if (seen.has(refId)) return "";
    let label = "";
    const found = this.rowsById.get(refId);
    if (found) {
      const [entity, row] = found;
      if (entity) label = this.rowLabelText(entity, row, new Set([...seen, refId]));
    }
    if (!label) {
      const record = referenceOf(this.data, refId);
      if (record) label = record.label;
    }
    this.labelCache.set(refId, label);
    return label;
  }

  private rowLabelText(entity: RegistryEntity, row: Row, seen: Set<string> = new Set()): string {
    if (entity.labelField) {
      const spec = fieldOf(entity, entity.labelField);
      if (spec) {
        let text = spec.type === "REF" ? this.refLabel(row[spec.key], seen) : formatValue(this.registry, spec, row[spec.key]);
        if (spec.type === "TEXT" && looksLikeAnId(text)) text = "";
        if (text) return text;
      }
    }
    for (const spec of entity.fields) {
      if (isFreeText(spec) && this.visible(spec)) {
        const text = formatValue(this.registry, spec, row[spec.key]);
        if (text) return headingSummary(text);
      }
    }
    return "";
  }

  private value(spec: RegistryField, row: Row): string {
    if (spec.type === "REF") {
      const raw = row[spec.key];
      const label = this.refLabel(raw);
      if (label) return label;
      const text = formatValue(this.registry, spec, raw);
      return looksLikeAnId(text) ? "" : text;
    }
    if (spec.type === "MULTI_ENUM" && spec.refModel) {
      const printed: string[] = [];
      for (const token of iterate(row[spec.key] || [])) {
        const text = pyStrip(pyStr(token));
        if (!text) continue;
        const label = this.refLabel(text);
        if (label) printed.push(label);
        else if (!looksLikeAnId(text)) printed.push(text);
      }
      return printed.join(", ");
    }
    const text = formatValue(this.registry, spec, row[spec.key]);
    return spec.type === "TEXT" && looksLikeAnId(text) ? "" : text;
  }

  private printable(entity: RegistryEntity, row: Row, roles: Set<string>): Array<[RegistryField, string]> {
    const out: Array<[RegistryField, string]> = [];
    for (const spec of entity.fields) {
      if (!this.visible(spec) || !roles.has(roleOf(spec))) continue;
      if (spec.captionFor) continue;
      const text = this.value(spec, row);
      if (text) out.push([spec, text]);
      else if (spec.required && this.template.showEmptyNote) out.push([spec, "Not recorded."]);
    }
    return out;
  }

  private imageSources(entity: RegistryEntity, row: Row): Map<string, [RegistryField, string]> {
    const wanted = new Map<string, [RegistryField, string]>();
    for (const spec of entity.fields) {
      if (!this.visible(spec) || (spec.type !== "IMAGE" && spec.type !== "IMAGE_LIST")) continue;
      const captionSpec = entity.fields.find((f) => f.captionFor === spec.key);
      const caption = captionSpec ? formatValue(this.registry, captionSpec, row[captionSpec.key]) : "";
      for (const id of mediaIds(row[spec.key])) if (!wanted.has(id)) wanted.set(id, [spec, caption]);
    }
    for (const spec of entity.fields) {
      if (!this.visible(spec) || spec.type !== "REF") continue;
      const reference = referenceOf(this.data, row[spec.key]);
      if (!reference || !reference.photo) continue;
      if (!wanted.has(reference.photo)) wanted.set(reference.photo, [spec, this.referenceCaption(entity, spec, row, reference)]);
    }
    return wanted;
  }

  private images(entity: RegistryEntity, row: Row, limit = 0): Array<[ImageRef, string]> {
    const found: Array<[ImageRef, string]> = [];
    for (const [id, [spec, caption]] of this.imageSources(entity, row)) {
      const ref = this.resolveMedia(id);
      if (!ref) continue;
      found.push([ref, caption || spec.label]);
      if (limit && found.length >= limit) break;
    }
    return found;
  }

  private imageGroups(entity: RegistryEntity, row: Row, cap = 0): Array<[string, Array<[ImageRef, string]>]> {
    const claims = new Map<string, Array<[string, RegistryField, string]>>();
    for (const [id, [spec, caption]] of this.imageSources(entity, row)) {
      if (!claims.has(spec.key)) claims.set(spec.key, []);
      claims.get(spec.key)?.push([id, spec, caption]);
    }
    const plates: Array<[string, Array<[string, RegistryField, string]>]> = [];
    let shared: number | null = null;
    for (const claimed of claims.values()) {
      if (claimed.length > 1) {
        plates.push([claimed[0][1].label, claimed]);
        continue;
      }
      if (shared === null) {
        shared = plates.length;
        plates.push(["", []]);
      }
      plates[shared][1].push(...claimed);
    }
    const groups: Array<[string, Array<[ImageRef, string]>]> = [];
    for (const [gridCaption, claimed] of plates) {
      const images: Array<[ImageRef, string]> = [];
      let over = 0;
      for (const [id, spec, caption] of claimed) {
        if (cap && images.length >= cap) {
          over += 1;
          continue;
        }
        const ref = this.resolveMedia(id);
        if (!ref) continue;
        images.push([ref, caption || (gridCaption ? "" : spec.label)]);
      }
      if (over) this.noteOverCap(over);
      if (images.length) groups.push([gridCaption, images]);
    }
    return groups;
  }

  private noteOverCap(count: number): void {
    if (count > 0 && this.stageKey) this.overCap.set(this.stageKey, (this.overCap.get(this.stageKey) ?? 0) + count);
  }

  private referenceCaption(entity: RegistryEntity, spec: RegistryField, row: Row, reference: ReferencedRecord): string {
    const sources = ["name", ...(REFERENCE_NAME_SOURCES[reference.model] ?? [])];
    for (const [source, target] of Object.entries(spec.refHydration ?? {})) {
      if (!sources.includes(source)) continue;
      const targetSpec = fieldOf(entity, target);
      if (!targetSpec) continue;
      const frozen = this.value(targetSpec, row);
      if (frozen) return frozen;
    }
    return reference.label || spec.label;
  }

  private cellRuns(spec: RegistryField, row: Row, text: string): Run[] {
    if (spec.type === "RICH_TEXT" && !isEmptyRich(row[spec.key])) return plainRuns(row[spec.key]);
    return runsOf(text);
  }

  private renderNarrative(entity: RegistryEntity, row: Row, level: number, skip: Set<string> = new Set()): boolean {
    let wrote = false;
    const printable = (...roles: string[]) => this.printable(entity, row, new Set(roles)).filter(([s]) => !skip.has(s.key));
    const narrative = printable("NARRATIVE");
    const pairs = printable("KEY_VALUE", "COVER_FIELD", "TABLE_COLUMN");
    const bullets = printable("BULLETS");
    const numbered = this.template.numberHeadings;
    if (pairs.length) {
      this.doc.add({
        type: "KEYVALUE",
        pairs: pairs.map(([s, v]) => [s.label, this.cellRuns(s, row, v)]),
        columns: 2,
        label_width_pct: 30
      });
      wrote = true;
    }
    for (const [spec, text] of narrative) {
      if (spec.type === "RICH_TEXT") {
        const blocks = toReportBlocks(row[spec.key], this.resolveMedia);
        if (blocks.length) {
          if (Array.from(text).length > 160 || blocks.length > 1) this.doc.heading(spec.label, Math.min(4, level + 1), numbered);
          else this.doc.para(`${spec.label}:`);
          for (const block of blocks) this.doc.add(block);
          wrote = true;
          continue;
        }
      }
      if (spec.type === "LONG_TEXT" && Array.from(text).length > 160) {
        this.doc.heading(spec.label, Math.min(4, level + 1), numbered);
        this.doc.para(text);
      } else this.doc.para(`${spec.label}: ${text}`);
      wrote = true;
    }
    for (const [spec, text] of bullets) {
      this.doc.heading(spec.label, Math.min(4, level + 1), numbered);
      if (spec.type === "RICH_TEXT") {
        const blocks = toReportBlocks(row[spec.key], this.resolveMedia);
        if (blocks.length) {
          for (const block of blocks) this.doc.add(block);
          wrote = true;
          continue;
        }
      }
      this.doc.bullets(text.replace(/;/g, "\n").split("\n").map(pyStrip));
      wrote = true;
    }
    return wrote;
  }

  private tableColumns(entity: RegistryEntity): RegistryField[] {
    return entity.fields.filter((f) => this.visible(f) && roleOf(f) === "TABLE_COLUMN" && !isMedia(f)).slice(0, 6);
  }

  private renderTable(entity: RegistryEntity, rows: Row[], section: TemplateSection, level: number): boolean {
    const columns = this.tableColumns(entity);
    if (!columns.length) return this.renderCards(entity, rows, section, level);
    const declared = pySum(columns.map((c) => c.columnWidthPct ?? 0));
    let widths: number[];
    if (declared && Math.abs(declared - 100) < 0.5) widths = columns.map((c) => c.columnWidthPct ?? 0);
    else {
      const weights = columns.map((c) => (isFreeText(c) ? 2 : 1));
      const total = weights.reduce((a, b) => a + b, 0);
      widths = weights.map((w) => (100 * w) / total);
      widths[widths.length - 1] += 100 - pySum(widths);
    }
    const tableRows = rows.map((row) => {
      const printable = new Map(this.printable(entity, row, new Set(["TABLE_COLUMN"])).map(([s, t]) => [s.key, t]));
      return columns.map((spec) => this.cellRuns(spec, row, printable.get(spec.key) || this.value(spec, row)));
    });
    this.doc.add(
      table(
        columns.map((spec, i) =>
          column(spec.label, widths[i], { numeric: isNumeric(spec), align: isNumeric(spec) ? "RIGHT" : "LEFT" })
        ),
        tableRows,
        entity.title
      )
    );
    const columnKeys = new Set(columns.map((c) => c.key));
    rows.forEach((row, i) => {
      const hasExtra = this.printable(entity, row, new Set(["NARRATIVE", "TABLE_COLUMN", "KEY_VALUE", "COVER_FIELD", "BULLETS"])).some(
        ([s]) => !columnKeys.has(s.key)
      );
      const plates = section.includePhotos ? this.imageGroups(entity, row, section.maxPhotos) : [];
      if (!hasExtra && !plates.length) return;
      this.doc.heading(this.rowLabel(entity, row, i + 1), Math.min(4, level + 1), this.template.numberHeadings);
      this.placeImageGroups(plates, section);
      this.renderNarrative(entity, row, level + 1, columnKeys);
    });
    return true;
  }

  private renderCards(entity: RegistryEntity, rows: Row[], section: TemplateSection, level: number): boolean {
    rows.forEach((row, i) => {
      this.doc.heading(this.rowLabel(entity, row, i + 1), Math.min(4, level + 1), this.template.numberHeadings);
      if (section.includePhotos) this.placeImageGroups(this.imageGroups(entity, row, section.maxPhotos), section);
      this.renderNarrative(entity, row, level + 1);
    });
    return rows.length > 0;
  }

  private parentGroups(entity: RegistryEntity, rows: Row[]): Array<[string, Row[]]> | null {
    const parent = entity.parent ? this.entities.get(entity.parent) : undefined;
    if (!parent) return null;
    const link = entity.fields.find((f) => f.type === "REF" && f.refModel === parent.name);
    if (!link) return null;
    const buckets = new Map<string, Row[]>();
    for (const row of rows) {
      const ref = row[link.key];
      const key = typeof ref === "string" && ref ? ref : "";
      if (!buckets.has(key)) buckets.set(key, []);
      buckets.get(key)?.push(row);
    }
    const groups: Array<[string, Row[]]> = [];
    const parentRows = rowsOf(this.data, this.entityStage.get(parent.key) ?? "", parent.key);
    parentRows.forEach((parentRow, i) => {
      const id = parentRow._entryId;
      if (typeof id === "string" && id && buckets.has(id)) {
        const taken = buckets.get(id) ?? [];
        buckets.delete(id);
        if (taken.length) groups.push([this.rowLabel(parent, parentRow, i + 1), taken]);
      }
    });
    const orphans = buckets.get("") ?? [];
    buckets.delete("");
    for (const [ref, taken] of buckets) {
      const label = this.refLabel(ref);
      if (label) groups.push([label, taken]);
      else orphans.push(...taken);
    }
    if (orphans.length) groups.push([`No ${link.label.toLowerCase()} recorded`, orphans]);
    return groups;
  }

  private renderRows(entity: RegistryEntity, rows: Row[], section: TemplateSection, presentation: string, level: number): boolean {
    if (presentation === "TABLE") return this.renderTable(entity, rows, section, level);
    if (presentation === "CARDS") return this.renderCards(entity, rows, section, level);
    if (presentation === "GALLERY") {
      const every = rows.flatMap((row) => this.images(entity, row));
      const shown = every.slice(0, section.maxPhotos || every.length);
      this.noteOverCap(every.length - shown.length);
      this.placeImages(shown, section);
      return true;
    }
    let wrote = false;
    for (const row of rows) wrote = this.renderNarrative(entity, row, level) || wrote;
    return wrote;
  }

  private rowLabel(entity: RegistryEntity, row: Row, index: number): string {
    return this.rowLabelText(entity, row) || `${entity.title} ${index}`;
  }

  private placeImages(images: Array<[ImageRef, string]>, section: TemplateSection): void {
    if (!images.length) return;
    if (images.length === 1) {
      const [ref, caption] = images[0];
      this.doc.add(imageBlock(ref, 62, caption));
      return;
    }
    this.doc.add({ type: "IMAGEGRID", images, columns: Math.max(1, Math.min(4, section.photoColumns)), caption: "" });
  }

  private placeImageGroups(groups: Array<[string, Array<[ImageRef, string]>]>, section: TemplateSection): void {
    for (const [caption, images] of groups) {
      if (!images.length) continue;
      if (images.length === 1) {
        const [ref, imageCaption] = images[0];
        this.doc.add(imageBlock(ref, 62, imageCaption || caption));
        continue;
      }
      this.doc.add({ type: "IMAGEGRID", images, columns: Math.max(1, Math.min(4, section.photoColumns)), caption });
    }
  }

  private venuePoint(setup: Row): MapFacts {
    const keys = ["venue", "village", "block", "district"];
    const label = keys.map((k) => pyStrip(cleanText(setup[k]))).find(Boolean) ?? "";
    const fix = geoPoint(setup.venueLocation);
    if (fix) {
      return { ...emptyFacts(1), points: [{ label: label || "Workshop venue", lat: fix[0], lon: fix[1], kind: "VENUE", count: 1 }], placed: 1 };
    }
    const address = keys
      .map((k) => pyStrip(cleanText(setup[k])))
      .filter(Boolean)
      .join(", ");
    if (!address) return emptyFacts(1);
    const located = geocode(address, setup.state, this.data.districtPoints, cleanText);
    if (!located) return emptyFacts(1);
    return {
      points: [{ label: located.label || label || "Workshop venue", lat: located.lat, lon: located.lon, kind: "VENUE", count: 1 }],
      states: new Set(located.state ? [located.state] : []),
      placed: 1,
      total: 1,
      approximate: located.precise ? 0 : 1
    };
  }

  private artisanPoints(stateHint: unknown): MapFacts {
    const entity = this.entityIn(MAP_ROSTER_STAGE, MAP_ROSTER_ENTITY);
    if (!entity) return emptyFacts();
    const refKeys = entity.fields.filter((f) => f.type === "REF" && f.refModel === "Artisan").map((f) => f.key);
    const rows = rowsOf(this.data, MAP_ROSTER_STAGE, MAP_ROSTER_ENTITY);
    const found: Array<[string, Located]> = [];
    const facts = emptyFacts(rows.length);
    for (const row of rows) {
      const stated = MAP_ROSTER_PLACE_KEYS.map((k) => pyStrip(cleanText(row[k])));
      let text = stated.filter(Boolean).join(", ");
      let placeLabel = stated.find(Boolean) ?? "";
      let state = pyStrip(cleanText(row[MAP_ROSTER_STATE_KEY]));
      if (!text || !state) {
        const reference = refKeys.map((k) => referenceOf(this.data, row[k])).find((r) => r !== null) ?? null;
        if (reference) {
          const refPlace = pyStrip(cleanText(reference.place));
          const refDistrict = pyStrip(cleanText(reference.district));
          const refText = [refPlace, refDistrict].filter(Boolean).join(", ");
          if (refText) {
            text = refText;
            state = pyStrip(cleanText(reference.state));
            placeLabel = refPlace || refDistrict;
          }
        }
      }
      if (!state) state = pyStrip(cleanText(stateHint));
      const fix = geoPoint(row[MAP_ROSTER_PIN_KEY]);
      let located: Located | null;
      if (fix) {
        located = { lat: fix[0], lon: fix[1], state: canonicalState(state) ?? state, label: placeLabel || "Artisan's home", precise: true };
      } else {
        if (!text) continue;
        located = geocode(text, state, this.data.districtPoints, cleanText);
        if (!located) continue;
      }
      found.push([located.label, located]);
      facts.placed += 1;
      if (located.state) facts.states.add(located.state);
      if (!located.precise) facts.approximate += 1;
    }
    facts.points = foldPoints(found, "ARTISAN");
    return facts;
  }

  private renderMap(section: TemplateSection): void {
    const setup = singleton(this.data, MAP_VENUE_STAGE);
    const state = pyStrip(cleanText(setup.state));
    const district = pyStrip(cleanText(setup.district));
    if (!state && !district) return;
    const venue = this.venuePoint(setup);
    const artisans = this.artisanPoints(state);
    const points = [...venue.points, ...artisans.points];
    const highlight = new Set([...venue.states, ...artisans.states]);
    if (state) highlight.add(canonicalState(state) ?? state);
    const approximate = venue.approximate + artisans.approximate;
    const statedVenue = ["venue", "village", "district"]
      .map((k) => pyStrip(cleanText(setup[k])))
      .filter(Boolean)
      .join(", ");
    const sentences: string[] = [];
    if (venue.points.length && statedVenue) sentences.push(`Workshop venue: ${statedVenue}.`);
    if (artisans.placed) {
      sentences.push(
        `Home places of ${artisans.placed} of ${artisans.total} participating artisans, at ${artisans.points.length} location(s).`
      );
    }
    if (artisans.total > artisans.placed) {
      sentences.push(`${artisans.total - artisans.placed} participant(s) recorded no address that could be placed.`);
    }
    if (approximate) {
      sentences.push(
        "A place this atlas cannot resolve is drawn at its state capital, and its pin is labelled with the state rather than with the place."
      );
    }
    if (!points.length) sentences.push("No address in the record could be resolved to a position; the map shows the region only.");
    if (section.pageBreakBefore) this.doc.add({ type: "PAGEBREAK" });
    this.doc.heading(section.heading || "Workshop location and participants' origins", 1, this.template.numberHeadings);
    if (section.intro) this.doc.para(section.intro, "LEAD");
    this.doc.add({
      type: "MAP",
      title: "",
      caption: sentences.join(" "),
      points,
      highlight: [...highlight].filter(Boolean).sort(pyCompare),
      width_pct: 66,
      align: "CENTER"
    });
  }

  private renderStage(stage: RegistryStage, section: TemplateSection): void {
    this.stageKey = stage.key;
    const singletonData = this.data.singletons[stage.key];
    const hasSingleton = Boolean(singletonData && Object.keys(singletonData).length);
    const hasRows = collectionsOf(stage).some((e) => rowsOf(this.data, stage.key, e.key).length > 0);
    if (!hasSingleton && !hasRows && section.omitIfEmpty) return;
    const numbered = this.template.numberHeadings;
    if (section.pageBreakBefore) this.doc.add({ type: "PAGEBREAK" });
    this.doc.heading(section.heading || stage.title, 1, numbered);
    if (section.intro) this.doc.para(section.intro, "LEAD");
    let wrote = false;
    const single = singletonOf(stage);
    if (single && hasSingleton) {
      const data = singletonData as Row;
      wrote = this.renderNarrative(single, data, 1) || wrote;
      const metrics = this.printable(single, data, new Set(["METRIC"]));
      if (metrics.length) {
        this.doc.add({ type: "METRICROW", metrics: metrics.slice(0, 4).map(([s, v]) => [s.label, v, s.unit ?? ""]) });
        wrote = true;
      }
      if (section.includePhotos) {
        const plates = this.imageGroups(single, data, section.maxPhotos);
        if (plates.length) {
          this.placeImageGroups(plates, section);
          wrote = true;
        }
      }
    }
    const collections = collectionsOf(stage);
    for (const entity of collections) {
      if (section.entities.length && !section.entities.includes(entity.key)) continue;
      const rows = rowsOf(this.data, stage.key, entity.key);
      if (!rows.length) {
        if (!section.omitIfEmpty) {
          this.doc.heading(entity.title, 2, numbered);
          this.doc.para(`No ${entity.title.toLowerCase()} were recorded.`, "NOTE");
        }
        continue;
      }
      if (collections.length > 1) this.doc.heading(entity.title, 2, numbered);
      let presentation = section.presentation;
      if (presentation === "AUTO") presentation = this.tableColumns(entity).length ? "TABLE" : "CARDS";
      const groups = this.parentGroups(entity, rows);
      if (groups === null) {
        wrote = this.renderRows(entity, rows, section, presentation, 1) || wrote;
        continue;
      }
      const groupLevel = collections.length > 1 ? 3 : 2;
      for (const [title, groupRows] of groups) {
        this.doc.heading(title, groupLevel, numbered);
        wrote = this.renderRows(entity, groupRows, section, presentation, groupLevel) || wrote;
      }
    }
    if (section.includeFigures) {
      for (const block of this.chartsFor(stage.key)) {
        this.doc.add(block);
        wrote = true;
      }
    }
    if (!wrote && this.template.showEmptyNote) this.doc.para("Not recorded.", "NOTE");
  }

  private entityIn(stageKey: string, entityKey: string): RegistryEntity | undefined {
    return this.stages.get(stageKey)?.entities.find((e) => e.key === entityKey);
  }

  private labelOf(stageKey: string, entityKey: string, fieldKey: string, fallback: string): string {
    const entity = this.entityIn(stageKey, entityKey);
    const spec = entity ? fieldOf(entity, fieldKey) : undefined;
    return spec ? spec.label : fallback;
  }

  private outputCount(stageKey: string, entityKey: string, overrideKey = ""): number {
    const rows = rowsOf(this.data, stageKey, entityKey).length;
    if (!overrideKey) return rows;
    const stated = asNumber(singleton(this.data, "WORKSHOP_OUTCOMES")[overrideKey]);
    if (stated === null || stated < 0) return rows;
    return Math.trunc(stated);
  }

  private countOverrideReason(): string {
    const outcomes = singleton(this.data, "WORKSHOP_OUTCOMES");
    if (!["designsCountOverride", "prototypesCountOverride"].some((k) => asNumber(outcomes[k]) !== null)) return "";
    return pyStrip(cleanText(outcomes.countOverrideReason));
  }

  private enumValues(enumName: string | undefined): string[] {
    return (this.registry.enums[enumName ?? ""] ?? []).map((o) => o.value);
  }

  private figure(id: string): ChartBlock | null {
    if (this.drawn.has(id)) return null;
    const block = this.chartFor(id);
    if (!block || chartTotal(block) <= 0) return null;
    this.drawn.add(id);
    return block;
  }

  private chartFor(id: string): ChartBlock | null {
    switch (id) {
      case "OUTPUT_COUNTS": {
        const counts: Array<[string, number]> = [
          ["Sketches", this.outputCount("SKETCH_DEVELOPMENT", "sketch", "designsCountOverride")],
          ["Prototypes", this.outputCount("PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride")],
          ["Final products", this.outputCount("FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct")]
        ];
        const series = counts.filter(([, n]) => n);
        if (series.length < MIN_CHART_CATEGORIES) return null;
        const reason = this.countOverrideReason();
        return chart("BAR", series, "Designs, prototypes and final products", reason || "Counted from the records in this report, not typed.");
      }
      case "PROTOTYPE_STATUS": {
        const entity = this.entityIn("PROTOTYPE_VALIDATION", "prototypeValidation");
        const spec = entity ? fieldOf(entity, "decision") : undefined;
        if (!spec) return null;
        const tally = new Map<string, number>();
        for (const row of rowsOf(this.data, "PROTOTYPE_VALIDATION", "prototypeValidation")) {
          const token = pyStrip(cleanText(row.decision));
          if (!token) continue;
          const label = enumLabel(this.registry, spec.enum, token);
          tally.set(label, (tally.get(label) ?? 0) + 1);
        }
        if (tally.size < MIN_CHART_CATEGORIES) return null;
        const total = [...tally.values()].reduce((a, b) => a + b, 0);
        return chart("DONUT", [...tally.entries()], "Prototypes by review decision", `${total} prototype(s) reviewed.`);
      }
      case "SURVEY_RESPONDENTS": {
        const entity = this.entityIn("MARKET_SURVEY_CAPTURE", "surveyResponse");
        const spec = entity ? fieldOf(entity, "respondentGroup") : undefined;
        if (!spec) return null;
        const known = this.enumValues(spec.enum);
        const tally = new Map<string, number>();
        let unstated = 0;
        for (const row of rowsOf(this.data, "MARKET_SURVEY_CAPTURE", "surveyResponse")) {
          const token = pyStrip(cleanText(row.respondentGroup));
          if (!token) {
            unstated += 1;
            continue;
          }
          tally.set(token, (tally.get(token) ?? 0) + 1);
        }
        const order = [...known.filter((t) => tally.has(t)), ...[...tally.keys()].filter((t) => !known.includes(t))];
        const series = order.map((t) => [enumLabel(this.registry, spec.enum, t), tally.get(t) ?? 0] as [string, number]);
        if (series.length < MIN_CHART_CATEGORIES) return null;
        const plotted = [...tally.values()].reduce((a, b) => a + b, 0);
        let caption = `${plotted} response(s) plotted, from the rows entered at this workshop.`;
        if (unstated) caption += ` ${unstated} more recorded no respondent group and could not be plotted.`;
        const unentered = this.unenteredResponses();
        if (unentered) {
          caption +=
            ` The survey summary states ${plotted + unstated + unentered} response(s) ` +
            `collected, so ${unentered} were never entered as rows and are in no figure here.`;
        }
        return chart("HORIZONTAL_BAR", series, "Survey responses by respondent group", caption, "responses");
      }
      case "SURVEY_PRICE_EXPECTATIONS": {
        const responses = rowsOf(this.data, "MARKET_SURVEY_CAPTURE", "surveyResponse");
        const prices = responses.map((r) => asNumber(r.priceExpectation)).filter((n): n is number => n !== null && n > 0);
        const bands = priceBands(prices);
        if (bands.length < MIN_CHART_CATEGORIES) return null;
        let caption = `${prices.length} of ${responses.length} response(s) stated a price expectation, in rupees.`;
        const unentered = this.unenteredResponses();
        if (unentered) caption += ` A further ${unentered} response(s) the summary counts were never entered as rows.`;
        return chart("BAR", bands, "What respondents said they would pay", caption, "responses");
      }
      case "COST_BY_HEAD": {
        const heads = ["materialCost", "labourCost", "packagingCost", "finishingCost", "transportCost", "overheadCost"];
        const totals = new Map(heads.map((h) => [h, 0]));
        for (const row of rowsOf(this.data, "COSTING_MARKET_LINKAGE", "costSheet")) {
          for (const key of heads) {
            const amount = asNumber(row[key]);
            if (amount !== null && amount > 0) totals.set(key, (totals.get(key) ?? 0) + amount);
          }
        }
        const series = [...totals.entries()]
          .filter(([, v]) => v > 0)
          .map(([k, v]) => [this.labelOf("COSTING_MARKET_LINKAGE", "costSheet", k, k), v] as [string, number]);
        if (series.length < MIN_CHART_CATEGORIES) return null;
        return chart("HORIZONTAL_BAR", series, "Cost by head, all products", "Summed across every cost sheet recorded at this workshop.", "INR");
      }
      case "PRICE_BANDS": {
        const prices: number[] = [];
        for (const row of rowsOf(this.data, "COSTING_MARKET_LINKAGE", "costSheet")) {
          const amount = [asNumber(row.expectedPrice), asNumber(row.retailPrice)].find((v) => v !== null && v > 0);
          if (amount !== undefined && amount !== null) prices.push(amount);
        }
        const bands = priceBands(prices);
        if (bands.length < MIN_CHART_CATEGORIES) return null;
        return chart("BAR", bands, "Products by price band", `${prices.length} product(s) with a recorded price, in rupees.`, "products");
      }
      case "ADOPTION": {
        const entity = this.entityIn("POST_WORKSHOP_FOLLOWUP", "followUp");
        const spec = entity ? fieldOf(entity, "interval") : undefined;
        if (!spec) return null;
        const order = this.enumValues(spec.enum).filter((t) => t !== "AD_HOC");
        const adopted = new Set(["ADOPTED_IN_PRODUCTION", "ADOPTED_ON_ORDER"]);
        const tally = new Map<string, number>();
        const seen = new Set<string>();
        for (const row of rowsOf(this.data, "POST_WORKSHOP_FOLLOWUP", "followUp")) {
          const token = pyStrip(cleanText(row.interval));
          if (!order.includes(token)) continue;
          seen.add(token);
          if (adopted.has(pyStrip(cleanText(row.adoptionStatus)))) tally.set(token, (tally.get(token) ?? 0) + 1);
        }
        const series = order.filter((t) => seen.has(t)).map((t) => [enumLabel(this.registry, spec.enum, t), tally.get(t) ?? 0] as [string, number]);
        if (series.length < MIN_CHART_CATEGORIES || series.reduce((s, [, v]) => s + v, 0) <= 0) return null;
        return chart("LINE", series, "Products still in production at follow-up", "Counted from follow-up visits that were actually made.", "products");
      }
      default:
        return null;
    }
  }

  private unenteredResponses(): number {
    const stated = asNumber(singleton(this.data, "MARKET_SURVEY_CAPTURE").responsesCollected);
    if (stated === null || stated < 0) return 0;
    return Math.max(0, Math.trunc(stated) - rowsOf(this.data, "MARKET_SURVEY_CAPTURE", "surveyResponse").length);
  }

  private chartsFor(stageKey: string): ChartBlock[] {
    return FIGURES.filter(([, owner]) => owner === stageKey)
      .map(([id]) => this.figure(id))
      .filter((b): b is ChartBlock => b !== null);
  }

  private renderCharts(section: TemplateSection): void {
    const wanted = section.figures.length ? section.figures : FIGURES.map(([id]) => id);
    const blocks = wanted.map((id) => this.figure(id)).filter((b): b is ChartBlock => b !== null);
    if (!blocks.length) return;
    if (section.pageBreakBefore) this.doc.add({ type: "PAGEBREAK" });
    this.doc.heading(section.heading || "The workshop in figures", 1, this.template.numberHeadings);
    if (section.intro) this.doc.para(section.intro, "LEAD");
    for (const block of blocks) this.doc.add(block);
  }

  private renderCover(section: TemplateSection): void {
    const setup = singleton(this.data, "WORKSHOP_SETUP");
    const stage = this.stages.get("WORKSHOP_SETUP");
    const single = stage ? singletonOf(stage) : undefined;
    const rows: Array<[string, string]> = [];
    if (single) {
      for (const f of single.fields) {
        if (roleOf(f) === "COVER_FIELD" && this.visible(f)) {
          const text = formatValue(this.registry, f, setup[f.key]);
          if (text) rows.push([f.label, text]);
        }
      }
    }
    const settings = singleton(this.data, "REPORT_GENERATION");
    let hero: ImageRef | null = null;
    if (single && section.includePhotos) {
      const images = this.images(single, setup, 1);
      if (images.length) hero = images[0][0];
    }
    const logoIds = mediaIds(settings.logo);
    const logo = logoIds.length ? this.resolveMedia(logoIds[0]) : null;
    const org = this.doc.meta.organisation || this.template.organisation;
    const letterhead = cleanText(settings.letterheadText)
      .split("\n")
      .map(pyStrip)
      .filter(Boolean)
      .slice(0, 6);
    const generated = this.doc.meta.generated_at ? `Generated on ${formatDate(this.doc.meta.generated_at.slice(0, 10))}` : "";
    this.doc.add({
      type: "COVER",
      title: this.doc.meta.title,
      subtitle: this.doc.meta.subtitle,
      org_lines: ["Government of India • Ministry of Textiles", org, ...letterhead].filter(Boolean),
      logo,
      hero_image: hero,
      info_rows: rows.slice(0, COVER_INFO_ROWS),
      footer_lines: [submissionLine(settings), generated].filter(Boolean)
    });
    if (rows.length > COVER_INFO_ROWS && !sectionFor(this.template, "WORKSHOP_SETUP")) {
      this.coverFieldsDropped = rows.slice(COVER_INFO_ROWS).map(([label]) => label);
    }
  }

  private renderSummaryMetrics(section: TemplateSection): void {
    const counts: Array<[string, string, string, string]> = [
      ["Artisans", "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", ""],
      ["Sketches", "SKETCH_DEVELOPMENT", "sketch", "designsCountOverride"],
      ["Prototypes", "PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride"],
      ["Final products", "FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct", ""]
    ];
    const reason = this.countOverrideReason();
    const metrics: Array<[string, string, string]> = [];
    for (const [label, stageKey, entityKey, overrideKey] of counts) {
      const n = this.outputCount(stageKey, entityKey, overrideKey);
      if (n) metrics.push([label, String(n), ""]);
    }
    if (!metrics.length) return;
    if (section.heading) this.doc.heading(section.heading, 1, this.template.numberHeadings);
    this.doc.add({ type: "METRICROW", metrics: metrics.slice(0, 4) });
    if (reason) this.doc.para(`Stated counts: ${reason}`, "NOTE");
  }

  private renderSignatures(section: TemplateSection): void {
    const setup = singleton(this.data, "WORKSHOP_SETUP");
    const closing = singleton(this.data, "INSPECTION_CLOSING");
    const signatories: Array<[string, string]> = [];
    const designer = cleanText(setup.designerName);
    if (designer) signatories.push([designer, "Designer"]);
    const agency = cleanText(setup.implementingAgency);
    if (agency) signatories.push([agency, "Implementing Agency"]);
    const officer = cleanText(closing.inspectingOfficer);
    if (officer) signatories.push([officer, "Inspecting Officer"]);
    if (!signatories.length) return;
    if (section.pageBreakBefore) this.doc.add({ type: "PAGEBREAK" });
    this.doc.heading(section.heading || "Certification", 1, this.template.numberHeadings);
    this.doc.para(
      "Certified that the workshop was conducted and the prototypes documented above were " +
        "developed during the period stated on the cover of this report."
    );
    this.doc.add({ type: "SIGNATURE", signatories });
  }

  private renderMediaAnnexure(section: TemplateSection): void {
    const gathered: Array<[ImageRef, string]> = [];
    for (const stage of this.registry.stages) {
      for (const entity of stage.entities) {
        for (const row of this.rowsFor(stage, entity)) {
          for (const [ref, caption] of this.images(entity, row)) gathered.push([ref, caption || stage.title]);
        }
      }
    }
    if (!gathered.length) return;
    if (section.pageBreakBefore) this.doc.add({ type: "PAGEBREAK" });
    this.doc.heading(section.heading || "Photographic record", 1, this.template.numberHeadings);
    this.doc.add({ type: "IMAGEGRID", images: gathered, columns: 3, caption: "" });
  }

  refResolves = (value: unknown): boolean => Boolean(this.refLabel(value));

  completenessOf(stage: RegistryStage): Completeness {
    const [customFields, customValues] = customScoring(this.data.customSections, stage.key);
    const collections: Record<string, Row[]> = {};
    for (const e of collectionsOf(stage)) collections[e.key] = rowsOf(this.data, stage.key, e.key);
    return stageCompleteness(stage, singleton(this.data, stage.key), collections, {
      refResolves: this.refResolves,
      customFields,
      customValues
    });
  }

  private renderCompleteness(section: TemplateSection): void {
    const rows: Run[][][] = this.registry.stages.map((stage) => {
      const score = this.completenessOf(stage);
      return [
        runsOf(`${stage.number}. ${stage.title}`),
        runsOf(`${score.requiredFilled}/${score.requiredTotal}`),
        runsOf(`${score.percent}%`),
        runsOf(score.isComplete ? "Complete" : score.missing.slice(0, 3).join(", "))
      ];
    });
    if (!rows.length) return;
    this.doc.heading(section.heading || "Data completeness", 1, this.template.numberHeadings);
    this.doc.add(
      table(
        [
          column("Stage", 40),
          column("Required fields", 15, { numeric: true }),
          column("Complete", 12, { numeric: true }),
          column("Outstanding", 33)
        ],
        rows
      )
    );
  }

  build(): ReportDocument {
    const numbered = this.template.numberHeadings;
    const customFormat = (type: string, unit: string, value: unknown): string | null => {
      if (!["TEXT", "LONG_TEXT", "RICH_TEXT", "INT", "DECIMAL", "MONEY", "PERCENT", "DATE", "TIME", "BOOL", "ENUM", "MULTI_ENUM", "TAGS", "IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO", "GEO", "REF", "URL", "PHONE", "EMAIL"].includes(type)) {
        return null;
      }
      return formatValue(this.registry, { type, unit }, value);
    };
    for (const section of this.template.sections) {
      const opts = { heading: section.heading, numbered, pageBreakBefore: section.pageBreakBefore };
      switch (section.special) {
        case "COVER":
          this.renderCover(section);
          break;
        case "TOC":
          this.doc.add({ type: "TOC", title: "Contents", depth: 3 });
          break;
        case "SUMMARY_METRICS":
          this.renderSummaryMetrics(section);
          break;
        case "SIGNATURES":
          this.renderSignatures(section);
          break;
        case "ANNEXURE_MEDIA":
          this.renderMediaAnnexure(section);
          break;
        case "ANNEXURE_TRANSCRIPTS":
          appendTranscriptAnnexure(this.doc, this.data.transcripts, opts);
          break;
        case "ANNEXURE_QUESTIONNAIRES":
          appendQuestionnaireAnnexure(
            this.doc,
            this.data.questionnaires,
            opts,
            Object.fromEntries(this.registry.stages.map((s) => [s.key, [s.number, s.title] as [number, string]]))
          );
          break;
        case "ANNEXURE_AI_LAYERS":
          appendAiLayerAnnexure(this.doc, this.data.aiLayers, opts);
          break;
        case "CUSTOM_SECTION":
          appendCustomSection(
            this.doc,
            this.data.customSections.find((i) => i.key === section.customKey),
            { ...opts, maxTierRank: this.maxTierRank },
            customFormat
          );
          break;
        case "COMPLETENESS":
          this.renderCompleteness(section);
          break;
        case "MAP":
          this.renderMap(section);
          break;
        case "CHART":
          this.renderCharts(section);
          break;
        case "ACKNOWLEDGEMENT": {
          const text = singleton(this.data, "INTRODUCTORY_ADMIN_DOCUMENTATION").acknowledgement;
          if (pyStrip(cleanText(text))) {
            this.doc.heading(section.heading || "Acknowledgement", 1, numbered);
            this.doc.para(text);
          }
          break;
        }
        default:
          if (section.stageKey) {
            const stage = this.stages.get(section.stageKey);
            if (stage) this.renderStage(stage, section);
          }
      }
    }
    return this.doc.build();
  }
}

/** `report_builder.build_report`: the document and the warnings that belong beside it. */
export function buildReport(
  registry: Registry,
  data: WorkshopData,
  templateId: string,
  template: ReportTemplate,
  resolveMedia: MediaResolver,
  meta: ReportMeta,
  theme: ReportTheme
): { document: ReportDocument; warnings: string[] } {
  const warnings: string[] = [];
  if (template.id !== templateId && templateId) {
    warnings.push(`Template '${templateId}' is no longer available; the report was generated with '${template.name}' instead.`);
  }
  const builder = new ReportBuilder(registry, data, template, resolveMedia, meta, theme);
  const document = builder.build();
  for (const stage of registry.stages) {
    if (!sectionFor(template, stage.key)) continue;
    const score = builder.completenessOf(stage);
    if (!score.isComplete && score.missing.length) {
      warnings.push(
        `Stage ${stage.number} (${stage.title}): ${score.missing.length} required field(s) ` +
          `not recorded — ${score.missing.slice(0, 4).join(", ")}` +
          (score.missing.length > 4 ? "…" : "")
      );
    }
  }
  const tierTitle = template.maxTier.charAt(0) + template.maxTier.slice(1).toLowerCase();
  const lostByTier = builder.fieldsHiddenByTier();
  if (lostByTier.length) {
    const total = lostByTier.reduce((sum, [, labels]) => sum + labels.length, 0);
    const where = lostByTier.slice(0, 4).map(([s]) => `stage ${s.number}`).join(", ");
    warnings.push(
      `${total} field(s) recorded in this workshop are above ${template.name}'s capture tier ` +
        `(${tierTitle}) and are not in this file — ${where}` +
        (lostByTier.length > 4 ? "…" : "") +
        ". Generate the report with a template that captures every tier to include them."
    );
  }
  const overCap = builder.photographsOverCap();
  if (overCap.length) {
    const total = overCap.reduce((sum, [, n]) => sum + n, 0);
    const where = overCap.slice(0, 4).map(([s]) => `stage ${s.number}`).join(", ");
    warnings.push(
      `${total} photograph(s) recorded in this workshop did not fit ${template.name}'s ` +
        `photograph cap and are not in this file — ${where}` +
        (overCap.length > 4 ? "…" : "") +
        ". Generate the report with a template that prints every photograph to include them."
    );
  }
  const attachments = builder.attachmentsNamedButNotCarried();
  if (attachments.length) {
    const total = attachments.reduce((sum, [, n]) => sum + n, 0);
    const where = attachments.slice(0, 4).map(([s]) => `stage ${s.number}`).join(", ");
    warnings.push(
      `${total} attached file(s) are named in this report but the files themselves are not ` +
        `inside it — ${where}` +
        (attachments.length > 4 ? "…" : "") +
        ". A report file cannot carry a document, a recording or a video; send them alongside it."
    );
  }
  if (builder.coverFieldsDropped.length) {
    const dropped = builder.coverFieldsDropped;
    warnings.push(
      `${dropped.length} cover field(s) did not fit the cover table and ` +
        `the ${template.name} template prints no workshop-setup section to carry them, so ` +
        `they are not in this file: ${dropped.slice(0, 4).join(", ")}` +
        (dropped.length > 4 ? "…" : "") +
        "."
    );
  }
  const hidden = sectionsHiddenByTier(
    template.sections.filter((s) => s.special === "CUSTOM_SECTION").map((s) => data.customSections.find((i) => i.key === s.customKey)),
    TIER_RANK[template.maxTier]
  );
  if (hidden.length) {
    const titles = hidden.map((i) => i.title).sort(pyCompare);
    warnings.push(
      `${hidden.length} of this workshop's own section(s) ask only questions above ` +
        `${template.name}'s capture tier (${tierTitle}) and are not in ` +
        `this file: ` +
        titles.slice(0, 4).join(", ") +
        (titles.length > 4 ? "…" : "")
    );
  }
  return { document, warnings };
}

export type { Block };
