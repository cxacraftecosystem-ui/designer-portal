import { customSectionEntity, customStageBlocks } from "@/lib/customSections";
import type { DwDraft, DwDraftStage } from "@/lib/designWorkshopStore";
import {
  inputValue,
  listValue,
  referenceDisplayHint,
  rowTitle,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwRegistry,
  type DwRow,
  type DwStage,
  type DwValue
} from "@/lib/designWorkshops";
import { fromStored, toPlain } from "@/lib/richText";

const TOKEN_RUN = /[\p{L}\p{M}]+|\p{N}+/gu;

const COMBINING_DIACRITICS = /[\u0300-\u036F]/g;

export const MIN_QUERY_CHARS = 2;

export const MAX_HITS = 60;

const SNIPPET_RADIUS = 90;

const SNIPPET_SNAP = 24;

const MAX_HIGHLIGHTS = 12;

const SCORE_EXACT = 2;
const SCORE_PREFIX = 1;

type FoldOffsets = {
  startAt: number[];
  endAt: number[];
};

function fold(text: string, offsets: FoldOffsets | null): string {
  let out = "";
  let index = 0;
  const length = text.length;

  while (index < length) {
    let ascii = index;
    while (ascii < length && text.charCodeAt(ascii) < 0x80) ascii += 1;
    if (ascii > index) {
      out += text.slice(index, ascii).toLowerCase();
      if (offsets) {
        for (let at = index; at < ascii; at += 1) {
          offsets.startAt.push(at);
          offsets.endAt.push(at + 1);
        }
      }
      index = ascii;
      continue;
    }

    const codePoint = String.fromCodePoint(text.codePointAt(index) as number);
    const width = codePoint.length;
    const piece = codePoint.normalize("NFKD").replace(COMBINING_DIACRITICS, "").toLowerCase();
    out += piece;
    if (offsets) {
      for (let unit = 0; unit < piece.length; unit += 1) {
        offsets.startAt.push(index);
        offsets.endAt.push(index + width);
      }
    }
    index += width;
  }
  return out;
}

export function foldForSearch(text: string): string {
  return fold(text, null);
}

function tokensOf(folded: string): string[] {
  const out: string[] = [];
  TOKEN_RUN.lastIndex = 0;
  for (let match = TOKEN_RUN.exec(folded); match; match = TOKEN_RUN.exec(folded)) out.push(match[0]);
  return out;
}

export function tokeniseForSearch(text: string): string[] {
  return tokensOf(foldForSearch(text));
}

export type SnippetSegment = { text: string; match: boolean };

export type SearchSnippet = {
  segments: SnippetSegment[];
  clippedStart: boolean;
  clippedEnd: boolean;
};

export type WorkshopSearchHit = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  entityTitle: string;
  recordTitle: string | null;
  rowKey: string | null;
  fieldKey: string;
  fieldLabel: string;
  anchorFieldKey: string;
  snippet: SearchSnippet;
  score: number;
};

type IndexEntry = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  entityTitle: string;
  recordTitle: string | null;
  rowKey: string | null;
  fieldKey: string;
  fieldLabel: string;
  anchorFieldKey: string;
  text: string;
  folded: string;
  order: number;
};

export type WorkshopSearchIndex = {
  entries: IndexEntry[];
  tokens: string[];
  postings: number[][];
  fieldCount: number;
  stageCount: number;
  buildMs: number;
};

export function emptyWorkshopSearchIndex(): WorkshopSearchIndex {
  return { entries: [], tokens: [], postings: [], fieldCount: 0, stageCount: 0, buildMs: 0 };
}

function entitiesByModel(registry: DwRegistry): Map<string, DwEntity> {
  const out = new Map<string, DwEntity>();
  for (const stage of registry.stages) {
    for (const entity of stage.entities) out.set(entity.name, entity);
  }
  return out;
}

function rowsById(registry: DwRegistry, draft: DwDraft): Map<string, { entity: DwEntity; row: DwRow; index: number }> {
  const out = new Map<string, { entity: DwEntity; row: DwRow; index: number }>();
  for (const stage of registry.stages) {
    const stored = draft.stages[stage.key];
    if (!stored) continue;
    for (const entity of stage.entities) {
      if (entity.cardinality !== "COLLECTION") continue;
      const rows = stored.collections[entity.key] ?? [];
      rows.forEach((row, index) => {
        const entryId = typeof row._entryId === "string" ? row._entryId : "";
        const clientKey = typeof row._clientKey === "string" ? row._clientKey : "";
        if (entryId) out.set(entryId, { entity, row, index });
        if (clientKey) out.set(clientKey, { entity, row, index });
      });
    }
  }
  return out;
}

function enumLabel(registry: DwRegistry, field: DwField, token: string): string {
  const options = field.options ?? (field.enum ? registry.enums[field.enum] : undefined) ?? [];
  const match = options.find((option) => option.value === token);
  return match ? match.label : token;
}

function refLabel(
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  entity: DwEntity,
  field: DwField,
  record: DwEntryData,
  value: string
): string {
  const target = rows.get(value);
  if (target && (!field.refModel || models.get(field.refModel)?.key === target.entity.key)) {
    return rowTitle(target.entity, target.row, target.index);
  }
  return referenceDisplayHint(entity, field, record);
}

function searchableText(
  registry: DwRegistry,
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  entity: DwEntity,
  field: DwField,
  record: DwEntryData
): string {
  const value: DwValue | undefined = record[field.key];
  if (value === null || value === undefined) return "";
  switch (field.type) {
    case "TEXT":
    case "LONG_TEXT":
    case "URL":
    case "EMAIL":
    case "PHONE":
      return inputValue(value).trim();
    case "RICH_TEXT":
      return toPlain(fromStored(value)).trim();
    case "TAGS":
      return listValue(value).join(" · ");
    case "ENUM":
      return enumLabel(registry, field, inputValue(value).trim());
    case "MULTI_ENUM":
      return listValue(value)
        .map((token) => enumLabel(registry, field, token))
        .join(" · ");
    case "REF":
      return refLabel(models, rows, entity, field, record, inputValue(value).trim());
    default:
      return "";
  }
}

export function buildWorkshopSearchIndex(registry: DwRegistry, draft: DwDraft): WorkshopSearchIndex {
  const startedAt = now();
  const models = entitiesByModel(registry);
  const rows = rowsById(registry, draft);

  const entries: IndexEntry[] = [];
  const stagesWithText = new Set<string>();
  let order = 0;

  for (const stage of registry.stages) {
    const stored: DwDraftStage | undefined = draft.stages[stage.key];
    if (!stored) continue;
    const before = entries.length;
    for (const entity of stage.entities) {
      if (entity.cardinality === "SINGLETON") {
        const record = stored.singletons[entity.key];
        if (!record) continue;
        order = collect(registry, models, rows, stage, entity, record, null, null, entries, order);
      } else {
        const collection = stored.collections[entity.key] ?? [];
        collection.forEach((row, index) => {
          const rowKey =
            (typeof row._clientKey === "string" && row._clientKey) ||
            (typeof row._entryId === "string" && row._entryId) ||
            null;
          order = collect(
            registry,
            models,
            rows,
            stage,
            entity,
            row,
            recordTitleOf(models, rows, entity, row, index),
            rowKey,
            entries,
            order
          );
        });
      }
    }
    const customValues = stored.custom ?? {};
    for (const block of customStageBlocks(draft.customDefinition ?? null, stage.key)) {
      order = collect(
        registry,
        models,
        rows,
        stage,
        customSectionEntity(block.section),
        customValues,
        null,
        null,
        entries,
        order
      );
    }

    if (entries.length > before) stagesWithText.add(stage.key);
  }

  const postingsByToken = new Map<string, number[]>();
  entries.forEach((entry, entryIndex) => {
    const seen = new Set<string>();
    for (const token of tokensOf(entry.folded)) {
      if (seen.has(token)) continue;
      seen.add(token);
      const bucket = postingsByToken.get(token);
      if (bucket) bucket.push(entryIndex);
      else postingsByToken.set(token, [entryIndex]);
    }
  });

  const tokens = [...postingsByToken.keys()].sort(compareCodeUnits);
  const postings = tokens.map((token) => postingsByToken.get(token) as number[]);

  return {
    entries,
    tokens,
    postings,
    fieldCount: entries.length,
    stageCount: stagesWithText.size,
    buildMs: now() - startedAt
  };
}

function now(): number {
  return typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now();
}

function compareCodeUnits(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function recordTitleOf(
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  entity: DwEntity,
  row: DwRow,
  index: number
): string {
  const labelField = entity.labelField ? entity.fields.find((field) => field.key === entity.labelField) : undefined;
  if (labelField?.type === "REF") {
    const resolved = refLabel(models, rows, entity, labelField, row, inputValue(row[labelField.key]).trim());
    if (resolved.trim()) return resolved.trim();
  }
  return rowTitle(entity, row, index);
}

function collect(
  registry: DwRegistry,
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  stage: DwStage,
  entity: DwEntity,
  record: DwEntryData,
  recordTitle: string | null,
  rowKey: string | null,
  out: IndexEntry[],
  order: number
): number {
  let next = order;
  for (const field of entity.fields) {
    if (field.deprecated) continue;
    const text = searchableText(registry, models, rows, entity, field, record);
    if (!text) continue;
    out.push({
      stageKey: stage.key,
      stageNumber: stage.number,
      stageTitle: stage.title,
      entityKey: entity.key,
      entityTitle: entity.title,
      recordTitle,
      rowKey,
      fieldKey: field.key,
      fieldLabel: field.label,
      anchorFieldKey: field.captionFor ?? field.key,
      text,
      folded: foldForSearch(text),
      order: next
    });
    next += 1;
  }
  return next;
}

export type WorkshopSearchStatus = "IDLE" | "TOO_SHORT" | "NO_TERMS" | "SEARCHED";

export type WorkshopSearchResult = {
  hits: WorkshopSearchHit[];
  total: number;
  truncated: boolean;
  status: WorkshopSearchStatus;
  terms: string[];
};

function nothing(status: WorkshopSearchStatus, terms: string[] = []): WorkshopSearchResult {
  return { hits: [], total: 0, truncated: false, status, terms };
}

export function searchWorkshop(index: WorkshopSearchIndex, query: string): WorkshopSearchResult {
  const raw = query.trim();
  if (!raw) return nothing("IDLE");
  if ([...raw].length < MIN_QUERY_CHARS) return nothing("TOO_SHORT");

  const terms = tokeniseForSearch(raw);
  if (!terms.length) return nothing("NO_TERMS", terms);

  let surviving: Map<number, number> | null = null;
  for (const term of terms) {
    const perTerm = new Map<number, number>();
    for (const tokenIndex of prefixRange(index.tokens, term)) {
      const weight = index.tokens[tokenIndex] === term ? SCORE_EXACT : SCORE_PREFIX;
      for (const entryIndex of index.postings[tokenIndex]) {
        const existing = perTerm.get(entryIndex);
        if (existing === undefined || existing < weight) perTerm.set(entryIndex, weight);
      }
    }
    if (surviving === null) {
      surviving = perTerm;
    } else {
      const merged = new Map<number, number>();
      const [small, large] = surviving.size <= perTerm.size ? [surviving, perTerm] : [perTerm, surviving];
      for (const [entryIndex, score] of small) {
        const other = large.get(entryIndex);
        if (other !== undefined) merged.set(entryIndex, score + other);
      }
      surviving = merged;
    }
    if (!surviving.size) break;
  }

  const matched = surviving ?? new Map<number, number>();
  const ranked = [...matched.entries()].sort((a, b) => {
    if (b[1] !== a[1]) return b[1] - a[1];
    return index.entries[a[0]].order - index.entries[b[0]].order;
  });

  const hits = ranked.slice(0, MAX_HITS).map(([entryIndex, score]) => {
    const entry = index.entries[entryIndex];
    return {
      stageKey: entry.stageKey,
      stageNumber: entry.stageNumber,
      stageTitle: entry.stageTitle,
      entityKey: entry.entityKey,
      entityTitle: entry.entityTitle,
      recordTitle: entry.recordTitle,
      rowKey: entry.rowKey,
      fieldKey: entry.fieldKey,
      fieldLabel: entry.fieldLabel,
      anchorFieldKey: entry.anchorFieldKey,
      snippet: buildSnippet(entry.text, terms),
      score
    } satisfies WorkshopSearchHit;
  });

  return {
    hits,
    total: ranked.length,
    truncated: ranked.length > hits.length,
    status: "SEARCHED",
    terms
  };
}

function prefixRange(tokens: string[], term: string): number[] {
  let low = 0;
  let high = tokens.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (tokens[middle] < term) low = middle + 1;
    else high = middle;
  }
  const out: number[] = [];
  for (let index = low; index < tokens.length && tokens[index].startsWith(term); index += 1) out.push(index);
  return out;
}

function buildSnippet(text: string, terms: string[]): SearchSnippet {
  const offsets: FoldOffsets = { startAt: [], endAt: [] };
  const folded = fold(text, offsets);

  const ranges: Array<{ from: number; to: number }> = [];
  let ceiling = Number.POSITIVE_INFINITY;
  TOKEN_RUN.lastIndex = 0;
  for (let match = TOKEN_RUN.exec(folded); match; match = TOKEN_RUN.exec(folded)) {
    const from = match.index;
    if (from > ceiling || ranges.length >= MAX_HIGHLIGHTS) break;
    if (!terms.some((term) => match[0].startsWith(term))) continue;
    ranges.push({ from, to: from + match[0].length });
    if (ranges.length === 1) ceiling = from + match[0].length + SNIPPET_RADIUS;
  }
  TOKEN_RUN.lastIndex = 0;

  if (!ranges.length) {
    const head = text.slice(0, SNIPPET_RADIUS * 2);
    return {
      segments: [{ text: collapse(head), match: false }],
      clippedStart: false,
      clippedEnd: head.length < text.length
    };
  }

  const anchor = ranges[0];
  const windowStart = snapForward(folded, Math.max(0, anchor.from - SNIPPET_RADIUS), anchor.from);
  const windowEnd = snapBack(folded, Math.min(folded.length, anchor.to + SNIPPET_RADIUS), anchor.to);

  const segments: SnippetSegment[] = [];
  let cursor = windowStart;
  for (const range of ranges) {
    if (range.to <= windowStart || range.from >= windowEnd) continue;
    const from = Math.max(range.from, windowStart);
    const to = Math.min(range.to, windowEnd);
    if (from > cursor) push(segments, slice(text, offsets, cursor, from), false);
    push(segments, slice(text, offsets, from, to), true);
    cursor = to;
  }
  if (cursor < windowEnd) push(segments, slice(text, offsets, cursor, windowEnd), false);

  return {
    segments,
    clippedStart: windowStart > 0,
    clippedEnd: windowEnd < folded.length
  };
}

function slice(text: string, offsets: FoldOffsets, from: number, to: number): string {
  if (to <= from) return "";
  const start = offsets.startAt[from] ?? 0;
  const end = offsets.endAt[to - 1] ?? text.length;
  return text.slice(start, end);
}

function push(segments: SnippetSegment[], text: string, match: boolean): void {
  const collapsed = collapse(text);
  if (collapsed) segments.push({ text: collapsed, match });
}

function collapse(text: string): string {
  return text.replace(/\s+/g, " ");
}

function snapForward(folded: string, from: number, limit: number): number {
  if (from === 0) return 0;
  for (let index = from; index < Math.min(limit, from + SNIPPET_SNAP); index += 1) {
    if (/\s/.test(folded[index])) return index + 1;
  }
  return from;
}

function snapBack(folded: string, to: number, limit: number): number {
  if (to === folded.length) return to;
  for (let index = to; index > Math.max(limit, to - SNIPPET_SNAP); index -= 1) {
    if (/\s/.test(folded[index - 1])) return index - 1;
  }
  return to;
}

export const FOCUS_FIELD_PARAM = "find";

export const FOCUS_ROW_PARAM = "row";

export function fieldFocusKey(entityKey: string, fieldKey: string): string {
  return `${entityKey}.${fieldKey}`;
}

export type StageFocus = { entityKey: string; fieldKey: string; rowKey: string | null };

export function stageFieldHref(
  workshopId: string,
  stageKey: string,
  entityKey: string,
  anchorFieldKey: string,
  rowKey: string | null = null
): string {
  const params = new URLSearchParams({ [FOCUS_FIELD_PARAM]: fieldFocusKey(entityKey, anchorFieldKey) });
  if (rowKey) params.set(FOCUS_ROW_PARAM, rowKey);
  return `/design-workshops/${workshopId}/stages/${stageKey}?${params.toString()}`;
}

export function workshopHitHref(workshopId: string, hit: WorkshopSearchHit): string {
  return stageFieldHref(workshopId, hit.stageKey, hit.entityKey, hit.anchorFieldKey, hit.rowKey);
}

export function readStageFocus(params: { get(name: string): string | null } | null | undefined): StageFocus | null {
  const raw = params?.get(FOCUS_FIELD_PARAM);
  if (!raw) return null;
  const separator = raw.indexOf(".");
  if (separator <= 0 || separator === raw.length - 1) return null;
  return {
    entityKey: raw.slice(0, separator),
    fieldKey: raw.slice(separator + 1),
    rowKey: params?.get(FOCUS_ROW_PARAM) || null
  };
}

export const FIELD_ANCHOR_ATTRIBUTE = "data-dw-field";

export const ROW_ANCHOR_ATTRIBUTE = "data-dw-row";
