/**
 * The half of `backend/app/services/rich_text.py` a report reads: coerce a stored document, print it
 * as plain text, and turn it into report blocks.
 *
 * NOT `lib/richText.ts`, which is the EDITOR's model and is allowed to be generous about what it
 * accepts while somebody is typing. This is the server's reading of the same stored JSON — its
 * budgets, its kind fallbacks, its dropping of an image with no media — because the document a
 * browser builds offline has to say exactly what the server's says about the same answer.
 */

import {
  bookmarkId,
  cleanText,
  makeRun,
  pyStrip,
  splitByScript,
  type Align,
  type Block,
  type ImageRef,
  type ParaStyle,
  type Run,
  type TableBlock
} from "@/lib/offlineReport/model";

const MAX_DOCUMENT_CHARS = 200_000;
const MAX_BLOCKS = 2_000;
const MAX_HEADING_LEVEL = 4;
const MAX_LIST_DEPTH = 3;
const MAX_TABLE_ROWS = 200;
const MAX_TABLE_COLUMNS = 12;

type Mark = "BOLD" | "ITALIC" | "UNDERLINE" | "STRIKETHROUGH" | "CODE" | "SUPERSCRIPT" | "SUBSCRIPT" | "HIGHLIGHT";
const MARKS = new Set<Mark>(["BOLD", "ITALIC", "UNDERLINE", "STRIKETHROUGH", "CODE", "SUPERSCRIPT", "SUBSCRIPT", "HIGHLIGHT"]);
type Kind = "PARAGRAPH" | "HEADING" | "BULLET_ITEM" | "ORDERED_ITEM" | "QUOTE" | "TABLE" | "IMAGE";
const KINDS = new Set<Kind>(["PARAGRAPH", "HEADING", "BULLET_ITEM", "ORDERED_ITEM", "QUOTE", "TABLE", "IMAGE"]);
const ALIGNS = new Set<Align>(["LEFT", "CENTER", "RIGHT", "JUSTIFY"]);

export type RichSpan = { text: string; marks: Set<Mark> };
export type RichBlock = {
  kind: Kind;
  spans: RichSpan[];
  align: Align;
  level: number;
  rows: RichSpan[][][];
  media: string;
  widthPct: number;
};
export type RichDoc = { blocks: RichBlock[] };

const EMPTY: RichDoc = { blocks: [] };

function blockText(block: RichBlock): string {
  if (block.kind === "TABLE") {
    return block.rows.map((row) => row.map((cell) => cell.map((s) => s.text).join("")).join("\t")).join("\n");
  }
  return block.spans.map((s) => s.text).join("");
}

function blockIsEmpty(block: RichBlock): boolean {
  if (block.kind === "IMAGE") return !block.media;
  return !pyStrip(blockText(block));
}

function isListItem(kind: Kind): boolean {
  return kind === "BULLET_ITEM" || kind === "ORDERED_ITEM";
}

function coerceMarks(raw: unknown): Set<Mark> {
  const found = new Set<Mark>();
  // A list, or a set — which is what an already-coerced document carries, as Python's does.
  if (!Array.isArray(raw) && !(raw instanceof Set)) return found;
  for (const item of raw as Iterable<unknown>) {
    const token = String(item).toUpperCase() as Mark;
    if (MARKS.has(token)) found.add(token);
  }
  return found;
}

function coerceAlign(raw: unknown): Align {
  const token = String(raw).toUpperCase() as Align;
  return ALIGNS.has(token) ? token : "LEFT";
}

function coerceKind(raw: unknown): Kind {
  const token = String(raw).toUpperCase() as Kind;
  return KINDS.has(token) ? token : "PARAGRAPH";
}

function isNumber(value: unknown): value is number {
  return typeof value === "number";
}

/** Slices by code point, as Python slices a str. */
function cut(text: string, budget: number): string {
  const chars = Array.from(text);
  return chars.length <= budget ? text : chars.slice(0, Math.max(0, budget)).join("");
}

function len(text: string): number {
  return Array.from(text).length;
}

function coerceSpan(raw: unknown): { text: string; marks: Set<Mark> } | null {
  if (typeof raw === "string") return { text: cleanText(raw), marks: new Set() };
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const record = raw as Record<string, unknown>;
    return { text: cleanText(record.text), marks: coerceMarks(record.marks) };
  }
  return null;
}

function coerceRows(raw: unknown, budgetIn: number): [RichSpan[][][], number] {
  let budget = budgetIn;
  if (!Array.isArray(raw)) return [[], budget];
  const rows: RichSpan[][][] = [];
  for (const rowRaw of raw.slice(0, MAX_TABLE_ROWS)) {
    if (!Array.isArray(rowRaw)) continue;
    const cells: RichSpan[][] = [];
    for (const cellRaw of rowRaw.slice(0, MAX_TABLE_COLUMNS)) {
      const spans: RichSpan[] = [];
      const items = typeof cellRaw === "string" ? [cellRaw] : cellRaw;
      if (!Array.isArray(items)) {
        cells.push([]);
        continue;
      }
      for (const spanRaw of items.slice(0, 64)) {
        const span = coerceSpan(spanRaw);
        if (!span) continue;
        let text = span.text.replace(/\n/g, " ");
        if (budget <= 0) break;
        text = cut(text, budget);
        budget -= len(text);
        if (text) spans.push({ text, marks: span.marks });
      }
      cells.push(spans);
    }
    if (cells.length) rows.push(cells);
    if (budget <= 0) break;
  }
  while (rows.length && !rows[rows.length - 1].some((cell) => cell.some((s) => pyStrip(s.text)))) rows.pop();
  return [rows, budget];
}

function fromPlain(text: unknown): RichDoc {
  const cleaned = cut(cleanText(text), MAX_DOCUMENT_CHARS);
  if (!pyStrip(cleaned)) return EMPTY;
  const blocks = cleaned
    .split("\n")
    .filter((line) => pyStrip(line))
    .map((line) => plainBlock(pyStrip(line)));
  return { blocks: blocks.slice(0, MAX_BLOCKS) };
}

function plainBlock(text: string): RichBlock {
  return { kind: "PARAGRAPH", spans: [{ text, marks: new Set() }], align: "LEFT", level: 0, rows: [], media: "", widthPct: 70 };
}

/** `rich_text.from_json`. */
export function fromJson(raw: unknown): RichDoc {
  if (raw === null || raw === undefined) return EMPTY;
  if (typeof raw === "string") return fromPlain(raw);
  let blocksRaw: unknown;
  if (Array.isArray(raw)) blocksRaw = raw;
  else if (typeof raw === "object") blocksRaw = (raw as Record<string, unknown>).blocks;
  else return fromPlain(String(raw));
  if (!Array.isArray(blocksRaw)) return EMPTY;
  const blocks: RichBlock[] = [];
  let budget = MAX_DOCUMENT_CHARS;
  for (const entry of blocksRaw.slice(0, MAX_BLOCKS)) {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) {
      if (typeof entry === "string") {
        const text = cut(cleanText(entry), budget);
        budget -= len(text);
        if (text) blocks.push(plainBlock(text));
      }
      continue;
    }
    const record = entry as Record<string, unknown>;
    const kind = coerceKind(record.kind);
    const spans: RichSpan[] = [];
    const spansRaw = Array.isArray(record.spans) ? record.spans : [];
    for (const spanRaw of spansRaw.slice(0, 512)) {
      const span = coerceSpan(spanRaw);
      if (!span) continue;
      let text = span.text.replace(/\n/g, " ");
      if (budget <= 0) break;
      text = cut(text, budget);
      budget -= len(text);
      if (text) spans.push({ text, marks: span.marks });
    }
    const levelRaw = record.level;
    let level = isNumber(levelRaw) ? Math.trunc(levelRaw) : 0;
    if (kind === "HEADING") level = Math.max(1, Math.min(MAX_HEADING_LEVEL, level || 1));
    else if (isListItem(kind)) level = Math.max(0, Math.min(MAX_LIST_DEPTH, level));
    else level = 0;
    let media = "";
    let widthPct = 70;
    if (kind === "IMAGE") {
      media = cut(pyStrip(cleanText(record.media)), 64);
      if (!media) continue;
      if (isNumber(record.widthPct)) widthPct = Math.max(10, Math.min(100, record.widthPct));
    }
    let rows: RichSpan[][][] = [];
    if (kind === "TABLE") {
      [rows, budget] = coerceRows(record.rows, budget);
      if (!rows.length) continue;
    }
    blocks.push({ kind, spans, align: coerceAlign(record.align), level, rows, media, widthPct });
    if (budget <= 0) break;
  }
  return { blocks };
}

/** `rich_text.to_plain`. */
export function toPlain(raw: unknown): string {
  const document = fromJson(raw);
  const lines: string[] = [];
  let ordinal = 0;
  for (const block of document.blocks) {
    if (block.kind === "IMAGE") {
      ordinal = 0;
      const caption = pyStrip(blockText(block));
      if (caption) lines.push(caption);
      continue;
    }
    if (block.kind === "TABLE") {
      ordinal = 0;
      for (const row of block.rows) lines.push(row.map((cell) => pyStrip(cell.map((s) => s.text).join(""))).join(" | "));
      continue;
    }
    const text = pyStrip(blockText(block));
    if (block.kind === "ORDERED_ITEM") {
      ordinal += 1;
      lines.push(`${"  ".repeat(block.level)}${ordinal}. ${text}`);
      continue;
    }
    ordinal = 0;
    if (block.kind === "BULLET_ITEM") lines.push(`${"  ".repeat(block.level)}• ${text}`);
    else lines.push(text);
  }
  return pyStrip(lines.join("\n"));
}

/** `rich_text.is_empty`. */
export function isEmptyRich(raw: unknown): boolean {
  if (raw === null || raw === undefined) return true;
  if (typeof raw === "string") return !pyStrip(raw);
  return fromJson(raw).blocks.every(blockIsEmpty);
}

/** `rich_text.stored_text_document`: a string that is a whole stored document, or null. */
export function storedTextDocument(value: string): RichDoc | null {
  const stripped = pyStrip(value);
  if (!stripped.startsWith("{") || !stripped.endsWith("}")) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(stripped);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  if (!Array.isArray((parsed as Record<string, unknown>).blocks)) return null;
  return fromJson(parsed);
}

/** `rich_text.plain_from_stored`. */
export function plainFromStored(value: unknown): unknown {
  if (typeof value === "string") {
    const document = storedTextDocument(value);
    return document === null ? value : toPlain(document);
  }
  if (value && typeof value === "object") return toPlain(value);
  return value;
}

function runsForSpan(span: RichSpan): Run[] {
  const superscript = span.marks.has("SUPERSCRIPT");
  return splitByScript(span.text).map(([text, script]) =>
    makeRun(
      text,
      {
        bold: span.marks.has("BOLD"),
        italic: span.marks.has("ITALIC"),
        underline: span.marks.has("UNDERLINE"),
        strike: span.marks.has("STRIKETHROUGH"),
        superscript,
        subscript: span.marks.has("SUBSCRIPT") && !superscript,
        highlight: span.marks.has("HIGHLIGHT")
      },
      script
    )
  );
}

function tableBlock(block: RichBlock): TableBlock | null {
  const width = Math.max(0, ...block.rows.map((row) => row.length));
  if (!block.rows.length || !width) return null;
  const each = roundTo(100 / width, 2);
  const widths = Array.from({ length: width }, () => each);
  widths[width - 1] = roundTo(100 - each * (width - 1), 2);
  const header = block.rows[0];
  const body = block.rows.slice(1);
  const cellRuns = (row: RichSpan[][], index: number): Run[] => (row[index] ?? []).flatMap(runsForSpan);
  return {
    type: "TABLE",
    columns: widths.map((w, i) => ({
      header: pyStrip((header[i] ?? []).map((s) => s.text).join("")),
      width_pct: w,
      align: "LEFT",
      numeric: false
    })),
    rows: body.map((row) => Array.from({ length: width }, (_, i) => cellRuns(row, i))),
    caption: "",
    total_row: null,
    zebra: true
  };
}

/** Python's `round(x, 2)`, which rounds the binary value half to even. */
function roundTo(value: number, digits: number): number {
  const factor = 10 ** digits;
  const scaled = value * factor;
  const floor = Math.floor(scaled);
  const diff = scaled - floor;
  let rounded: number;
  if (Math.abs(diff - 0.5) < 1e-9) rounded = floor % 2 === 0 ? floor : floor + 1;
  else rounded = Math.round(scaled);
  return rounded / factor;
}

/** `rich_text.to_report_blocks`. */
export function toReportBlocks(
  raw: unknown,
  resolveMedia: ((id: string) => ImageRef | null) | null,
  baseStyle: ParaStyle = "BODY"
): Block[] {
  const document = fromJson(raw);
  const out: Block[] = [];
  let pending: Run[][] = [];
  let pendingOrdered = false;
  const flush = () => {
    if (pending.length) {
      out.push({ type: "BULLETLIST", items: pending, ordered: pendingOrdered });
      pending = [];
    }
  };
  for (const block of document.blocks) {
    if (blockIsEmpty(block)) continue;
    if (block.kind === "TABLE") {
      flush();
      const table = tableBlock(block);
      if (table) out.push(table);
      continue;
    }
    if (block.kind === "IMAGE") {
      flush();
      if (!resolveMedia) continue;
      const ref = resolveMedia(block.media);
      if (!ref) continue;
      out.push({
        type: "IMAGE",
        image: ref,
        width_pct: block.widthPct,
        align: block.align !== "LEFT" ? block.align : "CENTER",
        caption: pyStrip(blockText(block))
      });
      continue;
    }
    const runs = block.spans.flatMap(runsForSpan);
    if (!runs.length) continue;
    if (isListItem(block.kind)) {
      const ordered = block.kind === "ORDERED_ITEM";
      if (pending.length && ordered !== pendingOrdered) flush();
      pendingOrdered = ordered;
      pending.push(runs);
      continue;
    }
    flush();
    if (block.kind === "HEADING") {
      out.push({
        type: "HEADING",
        level: Math.max(1, Math.min(MAX_HEADING_LEVEL, block.level || 1)),
        runs,
        number: "",
        bookmark: bookmarkId("", pyStrip(blockText(block)), out.length)
      });
      continue;
    }
    out.push({
      type: "PARAGRAPH",
      runs,
      style: block.kind === "QUOTE" ? "QUOTE" : block.kind === "PARAGRAPH" ? "BODY" : baseStyle,
      align: block.align
    });
  }
  flush();
  return out;
}

/** `rich_text.plain_runs`: a whole document flattened into one line of runs, for a table cell. */
export function plainRuns(raw: unknown): Run[] {
  const document = fromJson(raw);
  const runs: Run[] = [];
  document.blocks.forEach((block, index) => {
    if (blockIsEmpty(block)) return;
    if (runs.length) runs.push(makeRun(" "));
    if (block.kind === "BULLET_ITEM") runs.push(makeRun("• "));
    else if (block.kind === "ORDERED_ITEM") runs.push(makeRun(`${index + 1}. `));
    runs.push(...block.spans.flatMap(runsForSpan));
  });
  return runs;
}

/** The media ids a stored document places. */
export function richMediaIds(raw: unknown): string[] {
  return fromJson(raw)
    .blocks.filter((b) => b.kind === "IMAGE" && b.media)
    .map((b) => b.media);
}
