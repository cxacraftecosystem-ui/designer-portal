/**
 * The .docx a browser writes from a `ReportDocument`, with the `docx` package — the counterpart of
 * `backend/app/services/report_docx.py`, whose typography this mirrors value for value: the heading
 * sizes and colours, the 9 pt table header in the accent fill with zebra rows, the borderless
 * key/value grid, the cover with the info table and no running head, "Page N of M" in the foot,
 * a Word contents field, and complex-script runs named a `w:cs` face.
 *
 * WHAT IS NOT THE SAME, said here and in `docs/DESIGN_WORKSHOP.md` rather than on screen: the
 * server draws its charts as native Word charts and this draws every chart and the map as a picture,
 * because a picture is what both the .docx and the .pdf written here can carry from one rasteriser.
 *
 * Images arrive through `loadImage` already decoded, oriented and re-encoded as JPEG or PNG
 * (`lib/offlineReport/images.ts`), so nothing here reads EXIF or guesses a format.
 */

import {
  AlignmentType,
  BorderStyle,
  Bookmark,
  Document,
  Footer,
  Header,
  ImageRun,
  LevelFormat,
  Packer,
  PageBreak,
  PageNumber,
  Paragraph,
  ShadingType,
  Table,
  TableCell,
  TableOfContents,
  TableRow,
  TabStopType,
  TextRun,
  VerticalAlign,
  WidthType,
  type IParagraphOptions,
  type ParagraphChild
} from "docx";

import {
  pageSizeMm,
  runsOf,
  type Align,
  type Block,
  type ChartBlock,
  type ImageRef,
  type MapBlock,
  type ReportDocument,
  type Run,
  type TableBlock
} from "@/lib/offlineReport/model";

export type LoadedImage = { data: Uint8Array; type: "jpg" | "png"; widthPx: number; heightPx: number };
export type ImageLoader = (image: ImageRef) => Promise<LoadedImage | null>;
export type FigureRasteriser = (block: MapBlock | ChartBlock, widthMm: number) => Promise<LoadedImage | null>;

const TWIP_PER_MM = 1440 / 25.4;
const PX_PER_MM = 96 / 25.4;

const JC: Record<Align, (typeof AlignmentType)[keyof typeof AlignmentType]> = {
  LEFT: AlignmentType.LEFT,
  CENTER: AlignmentType.CENTER,
  RIGHT: AlignmentType.RIGHT,
  JUSTIFY: AlignmentType.JUSTIFIED
};

type RunOpts = { size?: number; color?: string; bold?: boolean; italic?: boolean };

function textRuns(runs: readonly Run[], theme: ReportDocument["theme"], opts: RunOpts = {}): TextRun[] {
  const out: TextRun[] = [];
  for (const run of runs) {
    if (!run.text) continue;
    const lines = run.text.split("\n");
    const children: Array<string | TextRun> = [];
    lines.forEach((line, i) => {
      if (i) children.push(new TextRun({ text: "", break: 1 }));
      if (line) children.push(line);
    });
    out.push(
      new TextRun({
        children: children as never,
        bold: run.bold || opts.bold || undefined,
        italics: run.italic || opts.italic || undefined,
        underline: run.underline ? {} : undefined,
        strike: run.strike || undefined,
        superScript: run.superscript || undefined,
        subScript: run.subscript || undefined,
        highlight: run.highlight ? "yellow" : undefined,
        color: run.color || opts.color || undefined,
        size: opts.size ? Math.round(opts.size * 2) : undefined,
        font: run.script !== "LATIN" ? { ascii: theme.body_font, hAnsi: theme.body_font, cs: theme.complex_font } : undefined
      })
    );
  }
  return out;
}

function para(children: ParagraphChild[], options: Omit<IParagraphOptions, "children"> & { after?: number; before?: number; line?: number } = {}) {
  const { after = 120, before = 0, line, ...rest } = options;
  return new Paragraph({
    ...rest,
    children,
    spacing: { before, after, ...(line ? { line } : {}) }
  });
}

function emptyPara(after = 120) {
  return para([], { after });
}

class DocxWriter {
  private readonly body: Array<Paragraph | Table | TableOfContents> = [];
  readonly dropped: string[] = [];
  private readonly textWmm: number;
  private readonly textHmm: number;
  private readonly textWtwip: number;
  private readonly images = new Map<string, LoadedImage | null>();

  constructor(
    private readonly doc: ReportDocument,
    private readonly loadImage: ImageLoader,
    private readonly rasterise: FigureRasteriser
  ) {
    const [w, h] = pageSizeMm(doc.meta.page_size);
    this.textWmm = w - 2 * doc.meta.margin_mm;
    this.textHmm = h - 2 * doc.meta.margin_mm;
    this.textWtwip = Math.trunc(this.textWmm * TWIP_PER_MM);
  }

  private get t() {
    return this.doc.theme;
  }

  private async image(ref: ImageRef): Promise<LoadedImage | null> {
    if (!this.images.has(ref.source)) {
      let loaded: LoadedImage | null = null;
      try {
        loaded = await this.loadImage(ref);
      } catch {
        loaded = null;
      }
      this.images.set(ref.source, loaded);
      if (!loaded) this.dropped.push(ref.source);
    }
    return this.images.get(ref.source) ?? null;
  }

  private drawing(loaded: LoadedImage, widthMm: number, maxHeightMm: number): ImageRun {
    const aspect = loaded.heightPx ? loaded.widthPx / loaded.heightPx : 4 / 3;
    let wMm = widthMm;
    let hMm = aspect ? wMm / aspect : widthMm * 0.75;
    if (hMm > maxHeightMm) {
      hMm = maxHeightMm;
      wMm = hMm * aspect;
    }
    return new ImageRun({
      type: loaded.type,
      data: loaded.data,
      transformation: { width: Math.round(wMm * PX_PER_MM), height: Math.round(hMm * PX_PER_MM) }
    });
  }

  private cell(children: Paragraph[], widthTwip: number, opts: { fill?: string; leftBorder?: string; topBorder?: string } = {}) {
    return new TableCell({
      children: children.length ? children : [emptyPara(0)],
      width: { size: widthTwip, type: WidthType.DXA },
      verticalAlign: VerticalAlign.TOP,
      shading: opts.fill ? { type: ShadingType.CLEAR, color: "auto", fill: opts.fill } : undefined,
      borders:
        opts.leftBorder || opts.topBorder
          ? {
              ...(opts.topBorder ? { top: { style: BorderStyle.SINGLE, size: 12, color: opts.topBorder } } : {}),
              ...(opts.leftBorder ? { left: { style: BorderStyle.SINGLE, size: 18, color: opts.leftBorder } } : {})
            }
          : undefined
    });
  }

  private tbl(rows: TableRow[], widths: number[], borders: boolean) {
    const line = { style: BorderStyle.SINGLE, size: 4, color: this.t.rule };
    const none = { style: BorderStyle.NONE, size: 0, color: "auto" };
    const edge = borders ? line : none;
    return new Table({
      rows,
      width: { size: this.textWtwip, type: WidthType.DXA },
      columnWidths: widths,
      layout: "fixed",
      margins: { top: 70, bottom: 70, left: 100, right: 100 },
      borders: { top: edge, bottom: edge, left: edge, right: edge, insideHorizontal: edge, insideVertical: edge }
    });
  }

  private caption(text: string, after = 180) {
    this.body.push(
      para(textRuns(runsOf(text, { italic: true }), this.t, { size: 9, color: this.t.muted }), {
        style: "Caption",
        alignment: AlignmentType.CENTER,
        after
      })
    );
  }

  private async cover(block: Extract<Block, { type: "COVER" }>) {
    const t = this.t;
    const logo = block.logo ? await this.image(block.logo) : null;
    if (logo) this.body.push(para([this.drawing(logo, this.textWmm * 0.22, 28)], { alignment: AlignmentType.CENTER, after: 240 }));
    else this.body.push(emptyPara(1200));
    for (const line of block.org_lines) {
      this.body.push(para(textRuns(runsOf(line), t, { size: 11, color: t.muted }), { alignment: AlignmentType.CENTER, after: 60 }));
    }
    this.body.push(emptyPara(380));
    this.body.push(
      para(textRuns(runsOf(block.title, { bold: true }), t, { size: 27, color: t.accent }), { alignment: AlignmentType.CENTER, after: 140 })
    );
    if (block.subtitle) {
      this.body.push(
        para(textRuns(runsOf(block.subtitle, { italic: true }), t, { size: 13, color: t.accent_soft }), {
          alignment: AlignmentType.CENTER,
          after: 260
        })
      );
    }
    const hero = block.hero_image ? await this.image(block.hero_image) : null;
    if (hero) {
      this.body.push(para([this.drawing(hero, this.textWmm * 0.78, this.textHmm * 0.34)], { alignment: AlignmentType.CENTER, after: 260 }));
    }
    if (block.info_rows.length) {
      this.table(
        {
          type: "TABLE",
          columns: [
            { header: "", width_pct: 32, align: "LEFT", numeric: false },
            { header: "", width_pct: 68, align: "LEFT", numeric: false }
          ],
          rows: block.info_rows.map(([label, value]) => [runsOf(label, { bold: true }), runsOf(value)]),
          caption: "",
          total_row: null,
          zebra: true
        },
        true
      );
    }
    for (const line of block.footer_lines) {
      this.body.push(para(textRuns(runsOf(line), t, { size: 9.5, color: t.muted }), { alignment: AlignmentType.CENTER, after: 60 }));
    }
    this.body.push(new Paragraph({ children: [new PageBreak()] }));
  }

  private table(block: TableBlock, headerless = false) {
    const t = this.t;
    const widths = block.columns.map((c) => Math.trunc((this.textWtwip * c.width_pct) / 100));
    const rows: TableRow[] = [];
    if (!headerless) {
      rows.push(
        new TableRow({
          tableHeader: true,
          cantSplit: true,
          children: block.columns.map((c, i) =>
            this.cell(
              [para(textRuns(runsOf(c.header, { bold: true }), t, { size: 9, color: t.table_header_text }), {
                alignment: JC[c.align],
                after: 0
              })],
              widths[i],
              { fill: t.table_header_fill }
            )
          )
        })
      );
    }
    block.rows.forEach((row, i) => {
      const fill = block.zebra && i % 2 === 1 ? t.zebra_fill : undefined;
      rows.push(
        new TableRow({
          children: block.columns.map((c, j) =>
            this.cell([para(textRuns(row[j] ?? [], t, { size: 9.5 }), { alignment: JC[c.numeric ? "RIGHT" : c.align], after: 0 })], widths[j], {
              fill
            })
          )
        })
      );
    });
    if (block.total_row) {
      rows.push(
        new TableRow({
          children: block.columns.map((c, j) =>
            this.cell(
              [para(textRuns(block.total_row?.[j] ?? [], t, { size: 9.5, bold: true }), { alignment: JC[c.numeric ? "RIGHT" : c.align], after: 0 })],
              widths[j],
              { fill: t.zebra_fill, topBorder: t.accent }
            )
          )
        })
      );
    }
    this.body.push(this.tbl(rows, widths, true));
    this.body.push(emptyPara(100));
    if (block.caption) this.caption(block.caption);
  }

  private keyValues(block: Extract<Block, { type: "KEYVALUE" }>) {
    const t = this.t;
    const perRow = Math.max(1, Math.min(2, block.columns));
    const labelPct = block.label_width_pct / perRow;
    const valuePct = 100 / perRow - labelPct;
    const lw = Math.trunc((this.textWtwip * labelPct) / 100);
    const vw = Math.trunc((this.textWtwip * valuePct) / 100);
    const rows: TableRow[] = [];
    for (let i = 0; i < block.pairs.length; i += perRow) {
      const cells: TableCell[] = [];
      for (const [label, value] of block.pairs.slice(i, i + perRow)) {
        cells.push(this.cell([para(textRuns(runsOf(label, { bold: true }), t, { color: t.muted, size: 9.5 }), { after: 40 })], lw));
        cells.push(this.cell([para(textRuns(value, t), { after: 40 })], vw));
      }
      while (cells.length < perRow * 2) cells.push(this.cell([emptyPara(0)], lw));
      rows.push(new TableRow({ children: cells }));
    }
    const grid: number[] = [];
    for (let i = 0; i < perRow; i += 1) grid.push(lw, vw);
    this.body.push(this.tbl(rows, grid, false));
    this.body.push(emptyPara(100));
  }

  private async imageGrid(block: Extract<Block, { type: "IMAGEGRID" }>) {
    if (!block.images.length) return;
    let columns = Math.max(1, Math.min(4, block.columns));
    while (columns > 1 && this.textWmm / columns < 45) columns -= 1;
    const cellWmm = this.textWmm / columns;
    const cellW = Math.trunc(this.textWtwip / columns);
    const rows: TableRow[] = [];
    for (let start = 0; start < block.images.length; start += columns) {
      const cells: TableCell[] = [];
      for (const [image, caption] of block.images.slice(start, start + columns)) {
        const inner: Paragraph[] = [];
        const loaded = await this.image(image);
        if (loaded) {
          inner.push(para([this.drawing(loaded, cellWmm - 6, this.textHmm * 0.3)], { alignment: AlignmentType.CENTER, after: 40, keepNext: Boolean(caption) }));
        }
        if (caption) {
          inner.push(para(textRuns(runsOf(caption, { italic: true }), this.t, { size: 8.5, color: this.t.muted }), { alignment: AlignmentType.CENTER, after: 60 }));
        }
        cells.push(this.cell(inner, cellW));
      }
      while (cells.length < columns) cells.push(this.cell([emptyPara(0)], cellW));
      rows.push(new TableRow({ cantSplit: true, children: cells }));
    }
    this.body.push(this.tbl(rows, Array.from({ length: columns }, () => cellW), false));
    this.body.push(emptyPara(100));
    if (block.caption) this.caption(block.caption);
  }

  private async figure(block: MapBlock | ChartBlock) {
    const widthMm = (this.textWmm * Math.max(20, Math.min(100, block.width_pct))) / 100;
    let loaded: LoadedImage | null = null;
    try {
      loaded = await this.rasterise(block, widthMm);
    } catch {
      loaded = null;
    }
    if (!loaded) {
      this.dropped.push(block.type === "MAP" ? "map:india" : `chart:${block.kind}`);
      return;
    }
    if (block.title) {
      this.body.push(para(textRuns(runsOf(block.title, { bold: true }), this.t, { size: 10.5, color: this.t.accent }), { after: 60, keepNext: true }));
    }
    this.body.push(para([this.drawing(loaded, widthMm, this.textHmm * 0.58)], { alignment: JC[block.align], after: 60, keepNext: Boolean(block.caption) }));
    if (block.caption) this.caption(block.caption, 200);
  }

  async build(): Promise<Blob> {
    const t = this.t;
    for (const block of this.doc.blocks) {
      switch (block.type) {
        case "COVER":
          await this.cover(block);
          break;
        case "TOC":
          this.body.push(para(textRuns(runsOf(block.title, { bold: true }), t, { size: 16, color: t.accent }), { style: "TOCHeading", after: 180 }));
          this.body.push(new TableOfContents("", { hyperlink: true, headingStyleRange: `1-${Math.max(1, Math.min(4, block.depth))}` }));
          this.body.push(new Paragraph({ children: [new PageBreak()] }));
          break;
        case "HEADING": {
          const label = block.number ? [new TextRun({ text: `${block.number}. `, bold: true })] : [];
          this.body.push(
            new Paragraph({
              style: `Heading${block.level}`,
              keepNext: true,
              keepLines: true,
              spacing: { before: [320, 260, 220, 180][block.level - 1], after: 120 },
              children: [new Bookmark({ id: block.bookmark || `h${this.body.length}`, children: [...label, ...textRuns(block.runs, t)] })]
            })
          );
          break;
        }
        case "PARAGRAPH": {
          const map: Record<string, [string | undefined, number | undefined, string | undefined, number]> = {
            BODY: [undefined, undefined, undefined, 0],
            LEAD: [undefined, 12, t.ink, 0],
            NOTE: ["ReportNote", 9, t.muted, 0],
            QUOTE: ["ReportQuote", undefined, t.accent_soft, 420],
            CAPTION: ["Caption", undefined, undefined, 0],
            COVER_LINE: [undefined, 11, t.muted, 0]
          };
          const [style, size, color, indent] = map[block.style] ?? map.BODY;
          this.body.push(
            para(textRuns(block.runs, t, { size, color, italic: block.style === "QUOTE" }), {
              style,
              alignment: JC[block.align],
              indent: indent ? { left: indent } : undefined,
              after: 140,
              line: 276
            })
          );
          break;
        }
        case "BULLETLIST":
          for (const item of block.items) {
            this.body.push(
              para(textRuns(item, t), {
                numbering: { reference: block.ordered ? "report-numbers" : "report-bullets", level: 0 },
                after: 60,
                line: 276
              })
            );
          }
          break;
        case "KEYVALUE":
          this.keyValues(block);
          break;
        case "TABLE":
          this.table(block);
          break;
        case "IMAGE": {
          const loaded = await this.image(block.image);
          if (!loaded) break;
          this.body.push(
            para([this.drawing(loaded, (this.textWmm * Math.max(5, Math.min(100, block.width_pct))) / 100, this.textHmm * 0.62)], {
              alignment: JC[block.align],
              after: 60,
              keepNext: Boolean(block.caption)
            })
          );
          if (block.caption) this.caption(block.caption, 200);
          break;
        }
        case "IMAGEGRID":
          await this.imageGrid(block);
          break;
        case "MAP":
        case "CHART":
          await this.figure(block);
          break;
        case "METRICROW": {
          if (!block.metrics.length) break;
          const width = Math.trunc(this.textWtwip / block.metrics.length);
          const cells = block.metrics.map(([label, value, unit]) =>
            this.cell(
              [
                para(
                  [
                    ...textRuns(runsOf(value, { bold: true }), t, { size: 18, color: t.accent }),
                    ...(unit ? textRuns(runsOf(` ${unit}`), t, { size: 9.5, color: t.muted }) : [])
                  ],
                  { alignment: AlignmentType.CENTER, after: 20 }
                ),
                para(textRuns(runsOf(label), t, { size: 8.5, color: t.muted }), { alignment: AlignmentType.CENTER, after: 0 })
              ],
              width,
              { fill: t.zebra_fill }
            )
          );
          this.body.push(this.tbl([new TableRow({ children: cells })], block.metrics.map(() => width), false));
          this.body.push(emptyPara(140));
          break;
        }
        case "CALLOUT": {
          const kind = block.kind.toUpperCase();
          const fill = ({ INFO: "EAF1FB", WARNING: "FDF3E2", SUCCESS: "EAF6EE" } as Record<string, string>)[kind] ?? "EAF1FB";
          const edge = ({ INFO: t.accent_soft, WARNING: "B7791F", SUCCESS: "2F855A" } as Record<string, string>)[kind] ?? t.accent_soft;
          const inner: Paragraph[] = [];
          if (block.title) inner.push(para(textRuns(runsOf(block.title, { bold: true }), t, { color: edge, size: 10 }), { after: 40 }));
          inner.push(para(textRuns(block.runs, t, { size: 9.5 }), { after: 0 }));
          this.body.push(this.tbl([new TableRow({ children: [this.cell(inner, this.textWtwip, { fill, leftBorder: edge })] })], [this.textWtwip], false));
          this.body.push(emptyPara(140));
          break;
        }
        case "SIGNATURE": {
          if (!block.signatories.length) break;
          const width = Math.trunc(this.textWtwip / block.signatories.length);
          this.body.push(emptyPara(700));
          const cells = block.signatories.map(([name, designation]) =>
            this.cell(
              [
                new Paragraph({ children: [], spacing: { after: 0 }, border: { bottom: { style: BorderStyle.SINGLE, size: 6, space: 4, color: t.ink } } }),
                para(textRuns(runsOf(name, { bold: true }), t, { size: 10 }), { alignment: AlignmentType.CENTER, after: 20 }),
                para(textRuns(runsOf(designation), t, { size: 8.5, color: t.muted }), { alignment: AlignmentType.CENTER, after: 0 })
              ],
              width
            )
          );
          this.body.push(this.tbl([new TableRow({ children: cells })], block.signatories.map(() => width), false));
          this.body.push(emptyPara(100));
          break;
        }
        case "SPACER":
          this.body.push(emptyPara(Math.trunc(block.height_pct * 24)));
          break;
        case "PAGEBREAK":
          this.body.push(new Paragraph({ children: [new PageBreak()] }));
          break;
      }
    }
    return Packer.toBlob(this.document());
  }

  private document(): Document {
    const t = this.t;
    const meta = this.doc.meta;
    const [w, h] = pageSizeMm(meta.page_size);
    const m = Math.trunc(meta.margin_mm * TWIP_PER_MM);
    const headingStyles = (
      [
        [18, t.accent, 320],
        [14, t.accent, 280],
        [12, t.accent_soft, 240],
        [11, t.muted, 200]
      ] as Array<[number, string, number]>
    ).map(([size, color, before], i) => ({
      id: `Heading${i + 1}`,
      name: `heading ${i + 1}`,
      basedOn: "Normal",
      next: "Normal",
      quickFormat: true,
      run: { font: { ascii: t.heading_font, hAnsi: t.heading_font, cs: t.complex_font }, bold: true, color, size: size * 2 },
      paragraph: { keepNext: true, keepLines: true, spacing: { before, after: 120 }, outlineLevel: i }
    }));
    const footerChildren: ParagraphChild[] = [];
    if (meta.footer_text) footerChildren.push(...textRuns(runsOf(meta.footer_text), t, { size: 8.5, color: t.muted }));
    if (meta.show_page_numbers) {
      footerChildren.push(
        new TextRun({ children: ["\t", "Page ", PageNumber.CURRENT, " of ", PageNumber.TOTAL_PAGES], size: 17, color: t.muted })
      );
    }
    return new Document({
      creator: meta.author || undefined,
      title: meta.title,
      description: meta.subtitle || undefined,
      features: { updateFields: true },
      styles: {
        default: {
          document: {
            run: { font: { ascii: t.body_font, hAnsi: t.body_font, cs: t.complex_font }, color: t.ink, size: Math.round(t.base_size_pt * 2) },
            paragraph: { spacing: { after: 120, line: 276 } }
          }
        },
        paragraphStyles: [
          ...headingStyles,
          {
            id: "Caption",
            name: "caption",
            basedOn: "Normal",
            next: "Normal",
            quickFormat: true,
            run: { italics: true, color: t.muted, size: 17 },
            paragraph: { spacing: { before: 0, after: 200 }, alignment: AlignmentType.CENTER }
          },
          {
            id: "ReportNote",
            name: "Report Note",
            basedOn: "Normal",
            next: "Normal",
            quickFormat: true,
            run: { color: t.muted, size: 18 },
            paragraph: { spacing: { after: 120 } }
          },
          {
            id: "ReportQuote",
            name: "Report Quote",
            basedOn: "Normal",
            next: "Normal",
            quickFormat: true,
            run: { italics: true, color: t.accent_soft },
            paragraph: { indent: { left: 420 }, spacing: { after: 140 } }
          },
          {
            id: "TOCHeading",
            name: "TOC Heading",
            basedOn: "Normal",
            next: "Normal",
            quickFormat: true,
            run: { font: { ascii: t.heading_font, hAnsi: t.heading_font }, bold: true, color: t.accent, size: 32 },
            paragraph: { spacing: { before: 240, after: 120 } }
          }
        ]
      },
      numbering: {
        config: [
          {
            reference: "report-bullets",
            levels: [
              {
                level: 0,
                format: LevelFormat.BULLET,
                text: "•",
                alignment: AlignmentType.LEFT,
                style: { paragraph: { indent: { left: 720, hanging: 360 } } }
              }
            ]
          },
          {
            reference: "report-numbers",
            levels: [
              {
                level: 0,
                format: LevelFormat.DECIMAL,
                text: "%1.",
                alignment: AlignmentType.LEFT,
                style: { paragraph: { indent: { left: 720, hanging: 360 } } }
              }
            ]
          }
        ]
      },
      sections: [
        {
          properties: {
            titlePage: true,
            page: {
              size: { width: Math.trunc(w * TWIP_PER_MM), height: Math.trunc(h * TWIP_PER_MM) },
              margin: { top: m, right: m, bottom: m, left: m, header: 709, footer: 709, gutter: 0 }
            }
          },
          headers: {
            default: new Header({
              children: [
                new Paragraph({
                  alignment: AlignmentType.RIGHT,
                  spacing: { after: 0 },
                  border: { bottom: { style: BorderStyle.SINGLE, size: 6, space: 4, color: t.rule } },
                  children: meta.header_text ? textRuns(runsOf(meta.header_text), t, { size: 8.5, color: t.muted }) : []
                })
              ]
            }),
            first: new Header({ children: [new Paragraph({ children: [] })] })
          },
          footers: {
            default: new Footer({
              children: [
                new Paragraph({
                  spacing: { before: 0, after: 0 },
                  border: { top: { style: BorderStyle.SINGLE, size: 6, space: 4, color: t.rule } },
                  tabStops: [{ type: TabStopType.RIGHT, position: this.textWtwip }],
                  children: footerChildren
                })
              ]
            }),
            first: new Footer({ children: [new Paragraph({ children: [] })] })
          },
          children: this.body
        }
      ]
    });
  }
}

/** Write the .docx. `dropped` names every picture and figure that could not be placed. */
export async function renderDocx(
  document: ReportDocument,
  loadImage: ImageLoader,
  rasterise: FigureRasteriser
): Promise<{ blob: Blob; dropped: string[] }> {
  const writer = new DocxWriter(document, loadImage, rasterise);
  const blob = await writer.build();
  return { blob, dropped: writer.dropped };
}
