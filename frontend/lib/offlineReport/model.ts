/**
 * The report document model, ported from `backend/app/services/report_model.py`, for the browser to
 * build a workshop's report with no connection.
 *
 * WHAT THIS IS. The server builds a report in two steps — workshop data and a template become a
 * `ReportDocument` of blocks, and the blocks become a .docx, a .pdf or the preview — and the web
 * preview has always drawn those blocks. When the device is offline there is no server to build
 * them, so `lib/offlineReport/builder.ts` builds the same blocks here, in the same shape the preview
 * endpoint serialises them in (`_block_payload`: the class name upper-cased as `type`, then the
 * dataclass fields in snake_case), and `ReportSheets` draws them exactly as it draws the server's.
 *
 * WHICH ONE IS AUTHORITATIVE is written down in `docs/DESIGN_WORKSHOP.md` ("Reports built in the
 * browser"): the server's file, whenever there is a connection. This copy exists so a designer with
 * no signal is never without their report, and `frontend/e2e/offline-report-parity-unit.spec.ts`
 * holds it to the server's output for a fixture workshop block for block.
 *
 * EVERY PYTHON IDIOM THAT CHANGES A CHARACTER IS PORTED, NOT APPROXIMATED. `round()` is half-even,
 * `f"{x:.2f}"` rounds the exact binary value half-even, `f"{x:g}"` drops trailing zeros and switches
 * to an exponent outside 1e-4..1e6, and `str()` of a bool is never the string the template prints.
 * Each has a helper below, and the parity spec is what proves the helpers right.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Text cleaning and scripts
 * ──────────────────────────────────────────────────────────────────────────── */

// Built with the constructor so the `u` flag and property escapes do not depend on the compile target.
const XML_ILLEGAL = new RegExp("[^\\u0009\\u000a\\u000d\\u0020-\\ud7ff\\ue000-\\ufffd\\u{10000}-\\u{10ffff}]", "gu");
const LINE_ENDINGS = new RegExp("\r\n?|\u2028|\u2029", "g");

/** `report_model.clean_text`: a string any document part can carry. */
export function cleanText(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  const text = typeof value === "string" ? value : pyStr(value);
  return text.replace(LINE_ENDINGS, "\n").replace(XML_ILLEGAL, "");
}

export type Script =
  | "LATIN"
  | "DEVANAGARI"
  | "BENGALI"
  | "ODIA"
  | "GUJARATI"
  | "TAMIL"
  | "TELUGU"
  | "KANNADA"
  | "MALAYALAM"
  | "GURMUKHI"
  | "OTHER";

const SCRIPT_RANGES: ReadonlyArray<[number, number, Script]> = [
  [0x0900, 0x097f, "DEVANAGARI"],
  [0x0980, 0x09ff, "BENGALI"],
  [0x0a00, 0x0a7f, "GURMUKHI"],
  [0x0a80, 0x0aff, "GUJARATI"],
  [0x0b00, 0x0b7f, "ODIA"],
  [0x0b80, 0x0bff, "TAMIL"],
  [0x0c00, 0x0c7f, "TELUGU"],
  [0x0c80, 0x0cff, "KANNADA"],
  [0x0d00, 0x0d7f, "MALAYALAM"]
];

// `_NEUTRAL_CATEGORIES`: marks, separators, controls, punctuation, symbols and stray digits.
const NEUTRAL = new RegExp(
  "^[\\p{Mn}\\p{Mc}\\p{Me}\\p{Zs}\\p{Zl}\\p{Zp}\\p{Cc}\\p{Cf}\\p{Pd}\\p{Ps}\\p{Pe}\\p{Pi}\\p{Pf}\\p{Po}\\p{Pc}\\p{Sm}\\p{Sk}\\p{Sc}\\p{Nd}\\p{No}]$",
  "u"
);

function scriptOrNeutral(ch: string): Script | null {
  const cp = ch.codePointAt(0) ?? 0;
  for (const [lo, hi, script] of SCRIPT_RANGES) if (cp >= lo && cp <= hi) return script;
  if (NEUTRAL.test(ch)) return null;
  if (cp < 0x0370) return "LATIN";
  return "OTHER";
}

/** `report_model.split_by_script`: the longest same-script spans, neutrals joining the run before. */
export function splitByScript(text: string): Array<[string, Script]> {
  if (!text) return [];
  const chars = Array.from(text);
  const resolved: Array<Script | null> = chars.map(scriptOrNeutral);
  let carried: Script | null = null;
  for (let i = 0; i < resolved.length; i += 1) {
    if (resolved[i] !== null) carried = resolved[i];
    else resolved[i] = carried;
  }
  let trailing: Script = "LATIN";
  for (let i = resolved.length - 1; i >= 0; i -= 1) {
    if (resolved[i] !== null) trailing = resolved[i] as Script;
    else resolved[i] = trailing;
  }
  const spans: Array<[string, Script]> = [];
  let start = 0;
  for (let i = 1; i < chars.length; i += 1) {
    if (resolved[i] !== resolved[i - 1]) {
      spans.push([chars.slice(start, i).join(""), resolved[i - 1] as Script]);
      start = i;
    }
  }
  spans.push([chars.slice(start).join(""), resolved[resolved.length - 1] as Script]);
  return spans;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Runs and blocks — the preview payload's shape, field for field
 * ──────────────────────────────────────────────────────────────────────────── */

export type Run = {
  text: string;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  strike: boolean;
  script: Script;
  color: string | null;
  superscript: boolean;
  subscript: boolean;
  highlight: boolean;
};

export type RunStyle = Partial<Omit<Run, "text" | "script">>;

export function makeRun(text: string, style: RunStyle = {}, script: Script = "LATIN"): Run {
  return {
    text,
    bold: style.bold ?? false,
    italic: style.italic ?? false,
    underline: style.underline ?? false,
    strike: style.strike ?? false,
    script,
    color: style.color ?? null,
    superscript: style.superscript ?? false,
    subscript: style.subscript ?? false,
    highlight: style.highlight ?? false
  };
}

/** `report_model.runs_of`: cleaned, split at every script boundary. */
export function runsOf(text: unknown, style: RunStyle = {}): Run[] {
  const cleaned = cleanText(text);
  if (!cleaned) return [];
  return splitByScript(cleaned).map(([span, script]) => makeRun(span, style, script));
}

export function runsText(runs: readonly Run[]): string {
  return runs.map((run) => run.text).join("");
}

export type Align = "LEFT" | "CENTER" | "RIGHT" | "JUSTIFY";
export type ParaStyle = "BODY" | "LEAD" | "NOTE" | "QUOTE" | "CAPTION" | "COVER_LINE";

export type ImageRef = {
  source: string;
  width_px: number;
  height_px: number;
  rotation_deg: number;
  mime_type: string;
};

export function imageAspect(image: ImageRef): number {
  const turned = image.rotation_deg === 90 || image.rotation_deg === 270;
  const w = turned ? image.height_px : image.width_px;
  const h = turned ? image.width_px : image.height_px;
  return w > 0 && h > 0 ? w / h : 4 / 3;
}

export type TableColumn = { header: string; width_pct: number; align: Align; numeric: boolean };

export function column(header: string, widthPct: number, opts: { align?: Align; numeric?: boolean } = {}): TableColumn {
  return { header, width_pct: widthPct, align: opts.align ?? "LEFT", numeric: opts.numeric ?? false };
}

export type MapPointKind = "VENUE" | "ARTISAN" | "MARKET" | "OTHER";
export type MapPoint = { label: string; lat: number; lon: number; kind: MapPointKind; count: number };
export type ChartKind = "BAR" | "HORIZONTAL_BAR" | "PIE" | "DONUT" | "LINE";

export type CoverBlock = {
  type: "COVER";
  title: string;
  subtitle: string;
  org_lines: string[];
  logo: ImageRef | null;
  hero_image: ImageRef | null;
  info_rows: Array<[string, string]>;
  footer_lines: string[];
};
export type TocBlock = { type: "TOC"; title: string; depth: number };
export type HeadingBlock = { type: "HEADING"; level: number; runs: Run[]; number: string; bookmark: string };
export type ParagraphBlock = { type: "PARAGRAPH"; runs: Run[]; style: ParaStyle; align: Align };
export type BulletListBlock = { type: "BULLETLIST"; items: Run[][]; ordered: boolean };
export type KeyValueBlock = { type: "KEYVALUE"; pairs: Array<[string, Run[]]>; columns: number; label_width_pct: number };
export type TableBlock = {
  type: "TABLE";
  columns: TableColumn[];
  rows: Run[][][];
  caption: string;
  total_row: Run[][] | null;
  zebra: boolean;
};
export type ImageBlock = { type: "IMAGE"; image: ImageRef; width_pct: number; align: Align; caption: string };
export type ImageGridBlock = { type: "IMAGEGRID"; images: Array<[ImageRef, string]>; columns: number; caption: string };
export type MetricRowBlock = { type: "METRICROW"; metrics: Array<[string, string, string]> };
export type CalloutBlock = { type: "CALLOUT"; kind: string; title: string; runs: Run[] };
export type SignatureBlock = { type: "SIGNATURE"; signatories: Array<[string, string]> };
export type SpacerBlock = { type: "SPACER"; height_pct: number };
export type PageBreakBlock = { type: "PAGEBREAK" };
export type MapBlock = {
  type: "MAP";
  title: string;
  caption: string;
  points: MapPoint[];
  highlight: string[];
  width_pct: number;
  align: Align;
};
export type ChartBlock = {
  type: "CHART";
  kind: ChartKind;
  series: Array<[string, number]>;
  title: string;
  caption: string;
  unit: string;
  width_pct: number;
  align: Align;
};

export type Block =
  | CoverBlock
  | TocBlock
  | HeadingBlock
  | ParagraphBlock
  | BulletListBlock
  | KeyValueBlock
  | TableBlock
  | ImageBlock
  | ImageGridBlock
  | MetricRowBlock
  | CalloutBlock
  | SignatureBlock
  | SpacerBlock
  | PageBreakBlock
  | MapBlock
  | ChartBlock;

export function paragraph(runs: Run[], style: ParaStyle = "BODY", align: Align = "LEFT"): ParagraphBlock {
  return { type: "PARAGRAPH", runs, style, align };
}

export function table(columns: TableColumn[], rows: Run[][][], caption = ""): TableBlock {
  const total = columns.reduce((sum, c) => sum + c.width_pct, 0);
  if (columns.length && Math.abs(total - 100) > 0.5) {
    throw new Error(`TableBlock column widths must sum to 100, got ${total.toFixed(1)}`);
  }
  return { type: "TABLE", columns, rows, caption, total_row: null, zebra: true };
}

export function imageBlock(image: ImageRef, widthPct = 70, caption = "", align: Align = "CENTER"): ImageBlock {
  return { type: "IMAGE", image, width_pct: widthPct, align, caption };
}

export function chartTotal(block: ChartBlock): number {
  return block.series.reduce((sum, [, value]) => sum + value, 0);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Meta, theme, and the builder
 * ──────────────────────────────────────────────────────────────────────────── */

export type PageSize = "A4" | "LETTER";

export function pageSizeMm(size: PageSize): [number, number] {
  return size === "LETTER" ? [215.9, 279.4] : [210, 297];
}

export type ReportTheme = {
  accent: string;
  accent_soft: string;
  ink: string;
  muted: string;
  rule: string;
  table_header_fill: string;
  table_header_text: string;
  zebra_fill: string;
  heading_font: string;
  body_font: string;
  complex_font: string;
  base_size_pt: number;
};

export const DEFAULT_THEME: ReportTheme = {
  accent: "1F3864",
  accent_soft: "2F5496",
  ink: "1B1B1B",
  muted: "5A6B87",
  rule: "B8C4D9",
  table_header_fill: "1F3864",
  table_header_text: "FFFFFF",
  zebra_fill: "F2F5FA",
  heading_font: "Calibri Light",
  body_font: "Calibri",
  complex_font: "Nirmala UI",
  base_size_pt: 10.5
};

export type ReportMeta = {
  title: string;
  subtitle: string;
  author: string;
  organisation: string;
  template_id: string;
  template_name: string;
  workshop_id: string;
  generated_at: string;
  page_size: PageSize;
  margin_mm: number;
  header_text: string;
  footer_text: string;
  show_page_numbers: boolean;
};

export function makeMeta(partial: Partial<ReportMeta> & { title: string }): ReportMeta {
  return {
    subtitle: "",
    author: "",
    organisation: "",
    template_id: "",
    template_name: "",
    workshop_id: "",
    generated_at: "",
    page_size: "A4",
    margin_mm: 25,
    header_text: "",
    footer_text: "",
    show_page_numbers: true,
    ...partial
  };
}

export type ReportDocument = { meta: ReportMeta; theme: ReportTheme; blocks: Block[]; images: ImageRef[] };

/** `report_model._bookmark_id`. */
export function bookmarkId(number: string, text: string, ordinal: number): string {
  const slug = `${number}_${text}`
    .replace(/[^A-Za-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 28);
  return slug ? `_S${ordinal}_${slug}` : `_S${ordinal}`;
}

/** `report_model.DocumentBuilder`: heading numbers counted here and nowhere else. */
export class DocumentBuilder {
  readonly blocks: Block[] = [];
  private counters = [0, 0, 0, 0];

  constructor(
    public meta: ReportMeta,
    public theme: ReportTheme = DEFAULT_THEME
  ) {}

  add(block: Block): this {
    this.blocks.push(block);
    return this;
  }

  heading(text: unknown, level = 1, numbered = true): this {
    const lvl = Math.max(1, Math.min(4, level));
    let number = "";
    if (numbered) {
      this.counters[lvl - 1] += 1;
      for (let i = lvl; i < 4; i += 1) this.counters[i] = 0;
      number = this.counters.slice(0, lvl).join(".");
    }
    const cleaned = cleanText(text);
    return this.add({
      type: "HEADING",
      level: lvl,
      runs: runsOf(cleaned),
      number,
      bookmark: bookmarkId(number, cleaned, this.blocks.length)
    });
  }

  para(text: unknown, style: ParaStyle = "BODY", align: Align = "LEFT"): this {
    const cleaned = cleanText(text);
    if (!cleaned.trim()) return this;
    for (const chunk of cleaned.split("\n\n")) {
      if (!chunk.trim()) continue;
      this.add(paragraph(runsOf(pyStrip(chunk)), style, align));
    }
    return this;
  }

  bullets(items: unknown[], ordered = false): this {
    const kept = items.filter((item) => cleanText(item).trim()).map((item) => runsOf(item));
    if (!kept.length) return this;
    return this.add({ type: "BULLETLIST", items: kept, ordered });
  }

  build(): ReportDocument {
    return { meta: this.meta, theme: this.theme, blocks: [...this.blocks], images: collectImages(this.blocks) };
  }
}

export function collectImages(blocks: readonly Block[]): ImageRef[] {
  const seen = new Map<string, ImageRef>();
  const take = (image: ImageRef | null) => {
    if (image && !seen.has(image.source)) seen.set(image.source, image);
  };
  for (const block of blocks) {
    if (block.type === "IMAGE") take(block.image);
    else if (block.type === "IMAGEGRID") for (const [image] of block.images) take(image);
    else if (block.type === "COVER") {
      take(block.logo);
      take(block.hero_image);
    }
  }
  return [...seen.values()];
}

/* ────────────────────────────────────────────────────────────────────────────
 * Python's formatting, exactly
 * ──────────────────────────────────────────────────────────────────────────── */

/** Python's `str.strip()` with no argument: Unicode whitespace from both ends. */
export function pyStrip(text: string): string {
  return text.replace(/^\s+|\s+$/g, "");
}

/** `" ".join(text.split())`. */
export function collapseSpace(text: string): string {
  return text.split(/\s+/).filter(Boolean).join(" ");
}

/** Python's `round()` to an integer: half to even. */
export function pyRound(value: number): number {
  const floor = Math.floor(value);
  const diff = value - floor;
  if (diff > 0.5) return floor + 1;
  if (diff < 0.5) return floor;
  return floor % 2 === 0 ? floor : floor + 1;
}

/** `f"{value:.{digits}f}"`: the exact binary value, rounded half to even. */
export function pyFixed(value: number, digits: number): string {
  if (!Number.isFinite(value)) return String(value);
  const exact = Math.abs(value).toFixed(Math.min(100, digits + 40));
  const [whole, frac = ""] = exact.split(".");
  const kept = frac.slice(0, digits);
  const rest = frac.slice(digits);
  let roundUp = false;
  if (rest.length) {
    const first = rest[0];
    const tail = rest.slice(1);
    if (first > "5") roundUp = true;
    else if (first === "5") {
      if (/[1-9]/.test(tail)) roundUp = true;
      else {
        const last = (digits ? kept[kept.length - 1] : whole[whole.length - 1]) ?? "0";
        roundUp = Number(last) % 2 === 1;
      }
    }
  }
  let digitsStr = whole + kept;
  if (roundUp) {
    const chars = digitsStr.split("");
    let i = chars.length - 1;
    while (i >= 0) {
      if (chars[i] === "9") {
        chars[i] = "0";
        i -= 1;
      } else {
        chars[i] = String(Number(chars[i]) + 1);
        break;
      }
    }
    digitsStr = (i < 0 ? "1" : "") + chars.join("");
  }
  const intLen = digitsStr.length - digits;
  const body = digits ? `${digitsStr.slice(0, intLen)}.${digitsStr.slice(intLen)}` : digitsStr;
  // Python keeps the sign of a value that rounds to zero: f"{-0.001:.2f}" is "-0.00".
  return value < 0 || Object.is(value, -0) ? `-${body}` : body;
}

/** `f"{value:g}"`: six significant digits, trailing zeros dropped, an exponent outside 1e-4..1e6. */
export function pyG(value: number): string {
  if (value === 0) return Object.is(value, -0) ? "-0" : "0";
  if (!Number.isFinite(value)) return Number.isNaN(value) ? "nan" : value > 0 ? "inf" : "-inf";
  const [mantissa, exponentText] = value.toExponential(5).split("e");
  const exponent = Number(exponentText);
  if (exponent >= -4 && exponent < 6) {
    const fixed = pyFixed(value, Math.max(0, 5 - exponent));
    return fixed.includes(".") ? fixed.replace(/0+$/, "").replace(/\.$/, "") : fixed;
  }
  const trimmed = mantissa.includes(".") ? mantissa.replace(/0+$/, "").replace(/\.$/, "") : mantissa;
  const sign = exponent < 0 ? "-" : "+";
  return `${trimmed}e${sign}${String(Math.abs(exponent)).padStart(2, "0")}`;
}

/** Python's `str()` of a JSON value: an int prints bare, a float as its repr, a list as a list. */
export function pyStr(value: unknown): string {
  if (value === null || value === undefined) return "None";
  if (typeof value === "boolean") return value ? "True" : "False";
  if (typeof value === "number") {
    if (Number.isInteger(value)) return String(value);
    const abs = Math.abs(value);
    if (abs >= 1e16 || abs < 1e-4) {
      const text = value.toExponential();
      const [m, e] = text.split("e");
      const exp = Number(e);
      return `${m}e${exp < 0 ? "-" : "+"}${String(Math.abs(exp)).padStart(2, "0")}`;
    }
    return String(value);
  }
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return `[${value.map(pyRepr).join(", ")}]`;
  if (typeof value === "object") {
    return `{${Object.entries(value as Record<string, unknown>)
      .map(([k, v]) => `${pyRepr(k)}: ${pyRepr(v)}`)
      .join(", ")}}`;
  }
  return String(value);
}

function pyRepr(value: unknown): string {
  if (typeof value === "string") return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
  return pyStr(value);
}

/**
 * Python's `sum()` of floats, which since 3.12 is Neumaier's compensated sum and not a plain
 * left-to-right fold. Table widths are summed this way on the server, and a plain fold differs in
 * the last bit — enough for a width to print as 16.666666666666654 instead of 16.666666666666668.
 */
export function pySum(values: readonly number[]): number {
  let total = 0;
  let compensation = 0;
  for (const value of values) {
    const next = total + value;
    if (Math.abs(total) >= Math.abs(value)) compensation += total - next + value;
    else compensation += value - next + total;
    total = next;
  }
  return total + compensation;
}

/** `_group_indian`: "1234567" -> "12,34,567". */
export function groupIndian(digits: string): string {
  if (digits.length <= 3) return digits;
  let head = digits.slice(0, -3);
  const tail = digits.slice(-3);
  const parts: string[] = [];
  while (head.length > 2) {
    parts.unshift(head.slice(-2));
    head = head.slice(0, -2);
  }
  if (head) parts.unshift(head);
  return [...parts, tail].join(",");
}

/** `f"{n:,}"`: thousands grouped in threes, as Python groups them. */
export function groupThousands(n: number): string {
  const text = String(Math.trunc(Math.abs(n)));
  const grouped = text.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return n < 0 ? `-${grouped}` : grouped;
}

/** `report_builder._as_number`: a finite float, or null for anything that is not one. */
export function asNumber(value: unknown): number | null {
  if (value === null || value === undefined || typeof value === "boolean") return null;
  let number: number;
  if (typeof value === "number") number = value;
  else if (typeof value === "string") {
    const text = value.trim().replace(/_/g, "");
    if (!text || !/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(text)) {
      if (/^[+-]?(inf|infinity|nan)$/i.test(text)) return null;
      return null;
    }
    number = Number(text);
  } else return null;
  return Number.isFinite(number) ? number : null;
}
