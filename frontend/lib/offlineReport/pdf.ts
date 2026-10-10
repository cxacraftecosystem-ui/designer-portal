/**
 * The .pdf a browser writes from a `ReportDocument`, with pdfmake — the counterpart of
 * `backend/app/services/report_pdf.py`, laid out to the same plan as the .docx beside it
 * (`lib/offlineReport/docx.ts`): a cover page with no running head, contents, numbered headings in
 * the accent, tables with the accent header and zebra rows, photo grids, figures as pictures, and
 * "Page N of M" in the foot.
 *
 * SET IN NOTO, LIKE THE SERVER'S. `report_pdf.py` binds Noto Sans for Latin and the rupee sign and a
 * Noto face per Indic script; this embeds the same families from `public/report-fonts`
 * (`offlineAssets.ts`), and pdfkit's shaper lays out the conjuncts. A script whose face cannot be
 * loaded is named in the warnings beside the download — the text prints, in boxes, exactly as the
 * server's does when it has no face — and the .docx, which names a face rather than embedding one,
 * is unaffected.
 *
 * The pdfmake instance is passed in: the browser build in the app, the Node build in the unit spec.
 */

import {
  pageSizeMm,
  runsOf,
  type Align,
  type Block,
  type ChartBlock,
  type MapBlock,
  type ReportDocument,
  type Run,
  type Script,
  type TableBlock
} from "@/lib/offlineReport/model";
import type { FigureRasteriser, ImageLoader, LoadedImage } from "@/lib/offlineReport/docx";
import { LATIN_FONT_FILES, SCRIPT_FONTS, scriptFontFiles } from "@/lib/offlineReport/offlineAssets";

// pdfmake's document definition is untyped JSON.
type Node = any;

export type PdfMakeLike = {
  virtualfs: { writeFileSync(name: string, content: string, encoding: string): void; existsSync(name: string): boolean };
  addFonts(fonts: Record<string, Record<string, string>>): void;
  createPdf(definition: object): { getBuffer(): Promise<Uint8Array> };
};

/** A font file's bytes by file name, or null when it cannot be had. */
export type FontSource = (file: string) => Promise<ArrayBuffer | null>;

const PT_PER_MM = 72 / 25.4;
const ALIGN: Record<Align, string> = { LEFT: "left", CENTER: "center", RIGHT: "right", JUSTIFY: "justify" };

function base64(bytes: Uint8Array | ArrayBuffer): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (typeof Buffer !== "undefined") return Buffer.from(view).toString("base64");
  let binary = "";
  for (let i = 0; i < view.length; i += 0x8000) binary += String.fromCharCode(...view.subarray(i, i + 0x8000));
  return btoa(binary);
}

const hex = (colour: string) => `#${colour.replace(/^#/, "")}`;

class PdfWriter {
  readonly dropped: string[] = [];
  readonly missingScripts = new Set<Script>();
  private readonly fonts = new Set<string>();
  private readonly images = new Map<string, LoadedImage | null>();
  private readonly textWmm: number;
  private readonly textHmm: number;
  private pendingBreak = false;

  constructor(
    private readonly doc: ReportDocument,
    private readonly pdfmake: PdfMakeLike,
    private readonly loadImage: ImageLoader,
    private readonly rasterise: FigureRasteriser,
    private readonly fontSource: FontSource
  ) {
    const [w, h] = pageSizeMm(doc.meta.page_size);
    this.textWmm = w - 2 * doc.meta.margin_mm;
    this.textHmm = h - 2 * doc.meta.margin_mm;
  }

  private get t() {
    return this.doc.theme;
  }

  /** Register the Latin family and every script family the document uses. */
  async loadFonts(): Promise<boolean> {
    const fonts: Record<string, Record<string, string>> = {};
    const latin: Record<string, string> = {};
    for (const [style, file] of Object.entries(LATIN_FONT_FILES)) {
      const bytes = await this.fontSource(file);
      if (!bytes) return false;
      this.pdfmake.virtualfs.writeFileSync(file, base64(bytes), "base64");
      latin[style] = file;
    }
    fonts.NotoSans = latin;
    const scripts = new Set<Script>();
    const visit = (runs: readonly Run[]) => runs.forEach((r) => scripts.add(r.script));
    for (const block of this.doc.blocks) collectRuns(block, visit);
    for (const script of scripts) {
      if (script === "LATIN" || script === "OTHER") continue;
      const family = SCRIPT_FONTS[script];
      const files = scriptFontFiles(family);
      const regular = await this.fontSource(files.normal);
      const bold = await this.fontSource(files.bold);
      if (!regular) {
        this.missingScripts.add(script);
        continue;
      }
      this.pdfmake.virtualfs.writeFileSync(files.normal, base64(regular), "base64");
      if (bold) this.pdfmake.virtualfs.writeFileSync(files.bold, base64(bold), "base64");
      const boldFile = bold ? files.bold : files.normal;
      fonts[family] = { normal: files.normal, bold: boldFile, italics: files.normal, bolditalics: boldFile };
      this.fonts.add(family);
    }
    this.pdfmake.addFonts(fonts);
    return true;
  }

  private text(runs: readonly Run[], opts: { size?: number; color?: string; bold?: boolean; italic?: boolean } = {}): Node[] {
    return runs
      .filter((run) => run.text)
      .map((run) => {
        const family = run.script !== "LATIN" && run.script !== "OTHER" ? SCRIPT_FONTS[run.script] : null;
        const decoration = [run.underline ? "underline" : null, run.strike ? "lineThrough" : null].filter(Boolean);
        return {
          text: run.text,
          ...(family && this.fonts.has(family) ? { font: family } : {}),
          ...(run.bold || opts.bold ? { bold: true } : {}),
          ...(run.italic || opts.italic ? { italics: true } : {}),
          ...(decoration.length ? { decoration: decoration.length === 1 ? decoration[0] : decoration } : {}),
          ...(run.superscript ? { sup: true } : run.subscript ? { sub: true } : {}),
          ...(run.highlight ? { background: "#FFFF00" } : {}),
          ...(run.color || opts.color ? { color: hex(run.color || opts.color || "") } : {}),
          ...(opts.size ? { fontSize: opts.size } : {})
        };
      });
  }

  private para(runs: readonly Run[], extra: Node = {}, opts: Parameters<PdfWriter["text"]>[1] = {}): Node {
    const node: Node = { text: this.text(runs, opts), ...extra };
    return this.withBreak(node);
  }

  /** A page break the document asked for is carried onto the next node, as pdfmake wants it. */
  private withBreak(node: Node): Node {
    if (this.pendingBreak) {
      this.pendingBreak = false;
      return { ...node, pageBreak: "before" };
    }
    return node;
  }

  private async image(ref: { source: string } & Parameters<ImageLoader>[0]): Promise<LoadedImage | null> {
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

  private picture(loaded: LoadedImage, widthMm: number, maxHeightMm: number, align: Align = "CENTER"): Node {
    const aspect = loaded.heightPx ? loaded.widthPx / loaded.heightPx : 4 / 3;
    let w = widthMm;
    let h = w / aspect;
    if (h > maxHeightMm) {
      h = maxHeightMm;
      w = h * aspect;
    }
    return {
      image: `data:image/${loaded.type === "png" ? "png" : "jpeg"};base64,${base64(loaded.data)}`,
      width: w * PT_PER_MM,
      height: h * PT_PER_MM,
      alignment: ALIGN[align]
    };
  }

  private caption(text: string): Node {
    return { text: this.text(runsOf(text, { italic: true }), { size: 9, color: this.t.muted }), alignment: "center", margin: [0, 2, 0, 8] };
  }

  private table(block: TableBlock, headerless = false): Node[] {
    const t = this.t;
    const body: Node[][] = [];
    if (!headerless) {
      body.push(
        block.columns.map((c) => ({
          text: this.text(runsOf(c.header, { bold: true }), { size: 9, color: t.table_header_text }),
          fillColor: hex(t.table_header_fill),
          alignment: ALIGN[c.align]
        }))
      );
    }
    block.rows.forEach((row, i) => {
      const fill = block.zebra && i % 2 === 1 ? hex(t.zebra_fill) : undefined;
      body.push(
        block.columns.map((c, j) => ({
          text: this.text(row[j] ?? [], { size: 9.5 }),
          alignment: ALIGN[c.numeric ? "RIGHT" : c.align],
          ...(fill ? { fillColor: fill } : {})
        }))
      );
    });
    if (block.total_row) {
      body.push(
        block.columns.map((c, j) => ({
          text: this.text(block.total_row?.[j] ?? [], { size: 9.5, bold: true }),
          alignment: ALIGN[c.numeric ? "RIGHT" : c.align],
          fillColor: hex(t.zebra_fill)
        }))
      );
    }
    const nodes: Node[] = [
      this.withBreak({
        table: { headerRows: headerless ? 0 : 1, dontBreakRows: true, widths: block.columns.map((c) => `${c.width_pct}%`), body },
        layout: {
          hLineWidth: () => 0.5,
          vLineWidth: () => 0.5,
          hLineColor: () => hex(t.rule),
          vLineColor: () => hex(t.rule),
          paddingLeft: () => 5,
          paddingRight: () => 5,
          paddingTop: () => 3.5,
          paddingBottom: () => 3.5
        },
        margin: [0, 2, 0, 6]
      })
    ];
    if (block.caption) nodes.push(this.caption(block.caption));
    return nodes;
  }

  private async figure(block: MapBlock | ChartBlock): Promise<Node[]> {
    const widthMm = (this.textWmm * Math.max(20, Math.min(100, block.width_pct))) / 100;
    let loaded: LoadedImage | null = null;
    try {
      loaded = await this.rasterise(block, widthMm);
    } catch {
      loaded = null;
    }
    if (!loaded) {
      this.dropped.push(block.type === "MAP" ? "map:india" : `chart:${block.kind}`);
      return [];
    }
    const nodes: Node[] = [];
    if (block.title) nodes.push(this.para(runsOf(block.title, { bold: true }), { margin: [0, 4, 0, 3] }, { size: 10.5, color: this.t.accent }));
    nodes.push(this.withBreak({ ...this.picture(loaded, widthMm, this.textHmm * 0.58, block.align), margin: [0, 0, 0, 3] }));
    if (block.caption) nodes.push(this.caption(block.caption));
    return nodes;
  }

  private async block(block: Block): Promise<Node[]> {
    const t = this.t;
    switch (block.type) {
      case "COVER": {
        const nodes: Node[] = [];
        const logo = block.logo ? await this.image(block.logo) : null;
        nodes.push(logo ? { ...this.picture(logo, this.textWmm * 0.22, 28), margin: [0, 0, 0, 12] } : { text: "", margin: [0, 60, 0, 0] });
        for (const line of block.org_lines) nodes.push({ text: this.text(runsOf(line), { size: 11, color: t.muted }), alignment: "center", margin: [0, 0, 0, 3] });
        nodes.push({ text: this.text(runsOf(block.title, { bold: true }), { size: 27, color: t.accent }), alignment: "center", margin: [0, 19, 0, 7] });
        if (block.subtitle) {
          nodes.push({ text: this.text(runsOf(block.subtitle, { italic: true }), { size: 13, color: t.accent_soft }), alignment: "center", margin: [0, 0, 0, 13] });
        }
        const hero = block.hero_image ? await this.image(block.hero_image) : null;
        if (hero) nodes.push({ ...this.picture(hero, this.textWmm * 0.78, this.textHmm * 0.34), margin: [0, 0, 0, 13] });
        if (block.info_rows.length) {
          nodes.push(
            ...this.table(
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
            )
          );
        }
        for (const line of block.footer_lines) nodes.push({ text: this.text(runsOf(line), { size: 9.5, color: t.muted }), alignment: "center", margin: [0, 0, 0, 3] });
        this.pendingBreak = true;
        return nodes;
      }
      case "TOC":
        this.pendingBreak = true;
        return [
          {
            toc: {
              title: { text: this.text(runsOf(block.title, { bold: true }), { size: 16, color: t.accent }), margin: [0, 0, 0, 9] },
              numberStyle: { color: hex(t.muted) }
            }
          }
        ];
      case "HEADING": {
        const sizes = [18, 14, 12, 11];
        const colours = [t.accent, t.accent, t.accent_soft, t.muted];
        const before = [16, 13, 11, 9][block.level - 1];
        const runs = block.number ? [...runsOf(`${block.number}. `, { bold: true }), ...block.runs] : block.runs;
        return [
          this.withBreak({
            text: this.text(runs, { size: sizes[block.level - 1], color: colours[block.level - 1], bold: true }),
            ...(block.level <= 3 ? { tocItem: true, tocMargin: [(block.level - 1) * 12, 0, 0, 0] } : {}),
            id: block.bookmark || undefined,
            headlineLevel: block.level,
            margin: [0, before, 0, 6]
          })
        ];
      }
      case "PARAGRAPH": {
        const styles: Record<string, [number | undefined, string | undefined, number]> = {
          BODY: [undefined, undefined, 0],
          LEAD: [12, t.ink, 0],
          NOTE: [9, t.muted, 0],
          QUOTE: [undefined, t.accent_soft, 21],
          CAPTION: [9, t.muted, 0],
          COVER_LINE: [11, t.muted, 0]
        };
        const [size, color, indent] = styles[block.style] ?? styles.BODY;
        return [
          this.para(
            block.runs,
            { alignment: ALIGN[block.align], margin: [indent, 0, 0, 7], lineHeight: 1.15 },
            { size, color, italic: block.style === "QUOTE" }
          )
        ];
      }
      case "BULLETLIST":
        return [this.withBreak({ [block.ordered ? "ol" : "ul"]: block.items.map((item) => ({ text: this.text(item) })), margin: [0, 0, 0, 7] })];
      case "KEYVALUE": {
        const perRow = Math.max(1, Math.min(2, block.columns));
        const labelPct = block.label_width_pct / perRow;
        const valuePct = 100 / perRow - labelPct;
        const body: Node[][] = [];
        for (let i = 0; i < block.pairs.length; i += perRow) {
          const row: Node[] = [];
          for (const [label, value] of block.pairs.slice(i, i + perRow)) {
            row.push({ text: this.text(runsOf(label, { bold: true }), { size: 9.5, color: t.muted }) });
            row.push({ text: this.text(value) });
          }
          while (row.length < perRow * 2) row.push({ text: "" });
          body.push(row);
        }
        const widths: string[] = [];
        for (let i = 0; i < perRow; i += 1) widths.push(`${labelPct}%`, `${valuePct}%`);
        return [this.withBreak({ table: { widths, body, dontBreakRows: true }, layout: "noBorders", margin: [0, 0, 0, 6] })];
      }
      case "TABLE":
        return this.table(block);
      case "IMAGE": {
        const loaded = await this.image(block.image);
        if (!loaded) return [];
        const nodes = [
          this.withBreak({
            ...this.picture(loaded, (this.textWmm * Math.max(5, Math.min(100, block.width_pct))) / 100, this.textHmm * 0.62, block.align),
            margin: [0, 0, 0, 3]
          })
        ];
        if (block.caption) nodes.push(this.caption(block.caption));
        return nodes;
      }
      case "IMAGEGRID": {
        if (!block.images.length) return [];
        let columns = Math.max(1, Math.min(4, block.columns));
        while (columns > 1 && this.textWmm / columns < 45) columns -= 1;
        const cellW = this.textWmm / columns;
        const body: Node[][] = [];
        for (let start = 0; start < block.images.length; start += columns) {
          const row: Node[] = [];
          for (const [image, caption] of block.images.slice(start, start + columns)) {
            const stack: Node[] = [];
            const loaded = await this.image(image);
            if (loaded) stack.push(this.picture(loaded, cellW - 6, this.textHmm * 0.3));
            if (caption) stack.push({ text: this.text(runsOf(caption, { italic: true }), { size: 8.5, color: t.muted }), alignment: "center", margin: [0, 2, 0, 3] });
            row.push({ stack: stack.length ? stack : [{ text: "" }] });
          }
          while (row.length < columns) row.push({ text: "" });
          body.push(row);
        }
        const nodes: Node[] = [
          this.withBreak({ table: { widths: Array.from({ length: columns }, () => "*"), body, dontBreakRows: true }, layout: "noBorders", margin: [0, 0, 0, 5] })
        ];
        if (block.caption) nodes.push(this.caption(block.caption));
        return nodes;
      }
      case "MAP":
      case "CHART":
        return this.figure(block);
      case "METRICROW": {
        if (!block.metrics.length) return [];
        const row = block.metrics.map(([label, value, unit]) => ({
          stack: [
            {
              text: [
                ...this.text(runsOf(value, { bold: true }), { size: 18, color: t.accent }),
                ...(unit ? this.text(runsOf(` ${unit}`), { size: 9.5, color: t.muted }) : [])
              ],
              alignment: "center"
            },
            { text: this.text(runsOf(label), { size: 8.5, color: t.muted }), alignment: "center" }
          ],
          fillColor: hex(t.zebra_fill),
          margin: [0, 4, 0, 4]
        }));
        return [this.withBreak({ table: { widths: block.metrics.map(() => "*"), body: [row] }, layout: "noBorders", margin: [0, 0, 0, 7] })];
      }
      case "CALLOUT": {
        const kind = block.kind.toUpperCase();
        const fill = ({ INFO: "EAF1FB", WARNING: "FDF3E2", SUCCESS: "EAF6EE" } as Record<string, string>)[kind] ?? "EAF1FB";
        const edge = ({ INFO: t.accent_soft, WARNING: "B7791F", SUCCESS: "2F855A" } as Record<string, string>)[kind] ?? t.accent_soft;
        const stack: Node[] = [];
        if (block.title) stack.push({ text: this.text(runsOf(block.title, { bold: true }), { size: 10, color: edge }), margin: [0, 0, 0, 2] });
        stack.push({ text: this.text(block.runs, { size: 9.5 }) });
        return [
          this.withBreak({
            table: { widths: ["*"], body: [[{ stack, fillColor: hex(fill) }]] },
            layout: { hLineWidth: () => 0, vLineWidth: (i: number) => (i === 0 ? 2 : 0), vLineColor: () => hex(edge) },
            margin: [0, 0, 0, 7]
          })
        ];
      }
      case "SIGNATURE": {
        if (!block.signatories.length) return [];
        const row = block.signatories.map(([name, designation]) => ({
          stack: [
            { canvas: [{ type: "line", x1: 10, y1: 0, x2: (this.textWmm * PT_PER_MM) / block.signatories.length - 20, y2: 0, lineWidth: 0.75, lineColor: hex(t.ink) }] },
            { text: this.text(runsOf(name, { bold: true }), { size: 10 }), alignment: "center", margin: [0, 4, 0, 1] },
            { text: this.text(runsOf(designation), { size: 8.5, color: t.muted }), alignment: "center" }
          ]
        }));
        return [this.withBreak({ table: { widths: block.signatories.map(() => "*"), body: [row] }, layout: "noBorders", margin: [0, 35, 0, 5] })];
      }
      case "SPACER":
        return [this.withBreak({ text: "", margin: [0, 0, 0, block.height_pct * 1.2] })];
      case "PAGEBREAK":
        this.pendingBreak = true;
        return [];
    }
  }

  async build(): Promise<Uint8Array> {
    const content: Node[] = [];
    for (const block of this.doc.blocks) content.push(...(await this.block(block)));
    const t = this.t;
    const meta = this.doc.meta;
    const [w, h] = pageSizeMm(meta.page_size);
    const margin = meta.margin_mm * PT_PER_MM;
    const definition = {
      pageSize: { width: w * PT_PER_MM, height: h * PT_PER_MM },
      pageMargins: [margin, margin, margin, margin],
      info: { title: meta.title, author: meta.author || undefined, subject: meta.subtitle || undefined },
      defaultStyle: { font: "NotoSans", fontSize: t.base_size_pt, color: hex(t.ink), lineHeight: 1.1 },
      header: (page: number) =>
        page === 1 || !meta.header_text
          ? null
          : { text: this.text(runsOf(meta.header_text), { size: 8.5, color: t.muted }), alignment: "right", margin: [margin, margin / 2, margin, 0] },
      footer: (page: number, pages: number) =>
        page === 1
          ? null
          : {
              columns: [
                { text: this.text(runsOf(meta.footer_text), { size: 8.5, color: t.muted }) },
                meta.show_page_numbers ? { text: `Page ${page} of ${pages}`, fontSize: 8.5, color: hex(t.muted), alignment: "right", width: "auto" } : { text: "" }
              ],
              margin: [margin, 8, margin, 0]
            },
      content
    };
    const buffer = await this.pdfmake.createPdf(definition).getBuffer();
    return buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer as ArrayBuffer);
  }
}

function collectRuns(block: Block, visit: (runs: readonly Run[]) => void): void {
  switch (block.type) {
    case "HEADING":
    case "PARAGRAPH":
    case "CALLOUT":
      visit(block.runs);
      break;
    case "BULLETLIST":
      block.items.forEach(visit);
      break;
    case "KEYVALUE":
      block.pairs.forEach(([label, value]) => {
        visit(runsOf(label));
        visit(value);
      });
      break;
    case "TABLE":
      block.columns.forEach((c) => visit(runsOf(c.header)));
      block.rows.forEach((row) => row.forEach(visit));
      block.total_row?.forEach(visit);
      break;
    case "COVER":
      [block.title, block.subtitle, ...block.org_lines, ...block.footer_lines, ...block.info_rows.flat()].forEach((s) => visit(runsOf(s)));
      break;
    case "IMAGE":
      visit(runsOf(block.caption));
      break;
    case "IMAGEGRID":
      block.images.forEach(([, caption]) => visit(runsOf(caption)));
      visit(runsOf(block.caption));
      break;
    case "MAP":
    case "CHART":
      visit(runsOf(block.title));
      visit(runsOf(block.caption));
      break;
    case "METRICROW":
      block.metrics.forEach((m) => m.forEach((s) => visit(runsOf(s))));
      break;
    case "SIGNATURE":
      block.signatories.forEach((s) => s.forEach((x) => visit(runsOf(x))));
      break;
    default:
      break;
  }
}

/** Write the .pdf. `missingScripts` names the scripts no face could be loaded for. */
export async function renderPdf(
  document: ReportDocument,
  pdfmake: PdfMakeLike,
  loadImage: ImageLoader,
  rasterise: FigureRasteriser,
  fontSource: FontSource
): Promise<{ bytes: Uint8Array; dropped: string[]; missingScripts: Script[]; fontsMissing: boolean }> {
  const writer = new PdfWriter(document, pdfmake, loadImage, rasterise, fontSource);
  const fontsOk = await writer.loadFonts();
  if (!fontsOk) return { bytes: new Uint8Array(), dropped: [], missingScripts: [], fontsMissing: true };
  const bytes = await writer.build();
  return { bytes, dropped: writer.dropped, missingScripts: [...writer.missingScripts], fontsMissing: false };
}
