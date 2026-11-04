import type { DwBlock, DwPreview, DwRun } from "@/lib/designWorkshops";
import { DEFAULT_ACCENT, paletteFromAccent, type ReportPalette } from "@/lib/reportTheme";

export type PreviewRun = DwRun & { underline?: boolean; strike?: boolean };

export type DwRichSpan = {
  text: string;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  strike: boolean;
  code: boolean;
};

export type DwRichBlockKind = "PARAGRAPH" | "HEADING" | "BULLET_ITEM" | "ORDERED_ITEM" | "QUOTE";

export type DwRichBlock = {
  kind: DwRichBlockKind;
  align: string;
  level: number;
  spans: DwRichSpan[];
};

export type DwMapPinKind = "VENUE" | "ARTISAN" | "MARKET" | "OTHER";

export type DwMapPin = {
  label: string;
  lat: number;
  lon: number;
  kind: DwMapPinKind;
  count: number;
};

type DwRasterFigure = {
  png_base64?: string | null;
  png?: string | null;
  png_width_px?: number | null;
  png_height_px?: number | null;
};

export type DwMapBlock = DwRasterFigure & {
  type: "MAP";
  title: string;
  caption: string;
  points: DwMapPin[];
  highlight: string[];
  width_pct: number;
  align: string;
};

export type DwChartKind = "BAR" | "HORIZONTAL_BAR" | "PIE" | "DONUT" | "LINE";

export type DwChartBlock = DwRasterFigure & {
  type: "CHART";
  kind: DwChartKind;
  series: Array<[string, number]>;
  title: string;
  caption: string;
  unit: string;
  width_pct: number;
  align: string;
};

export type DwRichTextBlock = {
  type: "RICHTEXT";
  blocks: DwRichBlock[];
  caption?: string;
};

export type PreviewBlock = DwBlock | DwMapBlock | DwChartBlock | DwRichTextBlock;

export function rasterSource(block: DwRasterFigure): string | null {
  const raw = block.png_base64 ?? block.png ?? null;
  if (!raw) return null;
  return raw.startsWith("data:") ? raw : `data:image/png;base64,${raw}`;
}

export function countCodeSpans(blocks: PreviewBlock[]): number {
  let found = 0;
  for (const block of blocks) {
    if (block.type !== "RICHTEXT") continue;
    for (const rich of block.blocks) {
      for (const span of rich.spans) if (span.code) found += 1;
    }
  }
  return found;
}

export const REPORT_PALETTE: ReportPalette = paletteFromAccent(DEFAULT_ACCENT);

export type PageGeometry = {
  widthMm: number;
  heightMm: number;
  marginMm: number;
  cssPageSize: string;
  label: string;
  marginAssumed: boolean;
};

export const DEFAULT_MARGIN_MM = 25;

const A4 = { widthMm: 210, heightMm: 297, cssPageSize: "A4 portrait", label: "A4" };
const LETTER = { widthMm: 215.9, heightMm: 279.4, cssPageSize: "letter portrait", label: "US Letter" };

export function pageGeometry(pageSize: string | undefined, marginMm?: number | null): PageGeometry {
  const paper = String(pageSize).toUpperCase() === "LETTER" ? LETTER : A4;
  const sent = typeof marginMm === "number" && Number.isFinite(marginMm) && marginMm >= 0;
  return {
    ...paper,
    marginMm: sent ? Math.min(marginMm as number, paper.widthMm / 3, paper.heightMm / 3) : DEFAULT_MARGIN_MM,
    marginAssumed: !sent
  };
}

export type PreviewMeta = DwPreview["meta"] & { marginMm?: number | null };

export const PX_PER_MM = 96 / 25.4;


export function galleryColumns(requested: number, textWidthMm: number): number {
  let columns = Math.max(1, Math.min(4, Math.trunc(requested) || 1));
  while (columns > 1 && textWidthMm / columns < 45) columns -= 1;
  return columns;
}
