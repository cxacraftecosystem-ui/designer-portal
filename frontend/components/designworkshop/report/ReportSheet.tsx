"use client";

/**
 * The preview as PAPER: A4 (or Letter) sheets at their real dimensions, with the cover on its own
 * page, a running head and foot on every page after it, and a visible mark wherever the document
 * declares a break.
 *
 * WHY A SHEET AND NOT A LIST OF BLOCKS. This screen is the last thing a designer looks at before
 * a file goes to a ministry, and what they are being asked is "is this the document?" — a
 * question a scrolling column of cards cannot answer. Whether a photo grid fits beside its
 * caption, whether the cover's info table has crowded the hero photograph off the page, whether
 * the signature block has landed under a heading with nothing between them: all of those are
 * questions about a PAGE, and none of them are visible in a layout that has no pages. So the
 * sheet is drawn at 210 × 297 mm with 25 mm margins, and every measurement inside it is in
 * millimetres and points rather than pixels — which has the second, larger benefit below.
 *
 * WHY MILLIMETRES AND POINTS. Because it makes the browser's own Print to PDF work. A designer on
 * a laptop with no backend reachable — on a train, in a district office, at the end of a workshop
 * day — needs a presentable PDF, and Ctrl+P is the only path they have. A page laid out in `px`
 * and `rem` prints at whatever the browser's scale factor happens to be; a page laid out in `mm`
 * and `pt` prints at the size it says. 10.5 pt here is the same 10.5 pt `ReportTheme.base_size_pt`
 * declares, so the printed result lands within a line or two of the server's own .pdf rather than
 * being a screenshot of a web page. It is NOT the authority — the server's writers are — but it
 * is a real document rather than a picture of one.
 *
 * WHY THE SHEET DOES NOT INVERT IN DARK MODE. Every other surface in this app goes through the
 * `ink-*` / `line-200` / `surface-50` ladders and flips under `data-theme="dark"`, and that rule
 * is not being broken lightly. A sheet is a depiction of a physical object: it is white because
 * the paper is white, and a designer approving how much ink a page carries cannot do that on a
 * dark rendering of it any more than they could approve a photograph shown in negative. Every
 * pixel outside the sheet — the chrome, the panels, the warnings, the page-break marks — is
 * themed as usual, so the only non-inverting region is the one that is standing in for paper.
 *
 * THE COLOUR IS A PROP, AND WHERE IT COMES FROM DECIDES WHETHER THIS IS A PREVIEW OR A GUESS.
 * `GET /report/preview` returns `meta` with the title, subtitle, template and page size and no
 * `ReportTheme`, so the TEMPLATE's own accent is still not knowable here: a DIC report is written
 * in green and an implementing-agency report in maroon, and both are drawn below in the default
 * indigo until somebody chooses otherwise. What IS exact is a colour the designer picks on the
 * page above — `lib/reportTheme` is a port of the server's derivation rather than an
 * approximation of it, so every one of the eight custom properties below is the value the .docx
 * will carry. `paletteChosen` is which of those two the reader is looking at, and the strip above
 * the sheets says it out loud rather than letting them find out when the file opens.
 */

import { Fragment, useEffect, useMemo, useRef, useState, type RefObject } from "react";

import { MediaUrlProvider, ReportBlock } from "@/components/designworkshop/report/ReportBlock";
import { ReportPaletteProvider } from "@/components/designworkshop/report/ReportChart";
import {
  pageGeometry,
  PX_PER_MM,
  REPORT_PALETTE,
  splitIntoSheets,
  type PreviewBlock
} from "@/components/designworkshop/report/previewModel";
import type { ReportPalette } from "@/lib/reportTheme";

/**
 * The whole document's stylesheet, built for one page size.
 *
 * A single `<style>` element rather than Tailwind classes, for the reason in this file's header:
 * the units have to be physical. It is also the only way to reach `@page`, `break-inside` and the
 * print rules at all — Tailwind's `print:` variant can hide a box but cannot tell a browser what
 * size of paper to use or that a table must not be split across two of them.
 *
 * It is a GLOBAL stylesheet while this page is mounted, which is deliberate for exactly one
 * group of rules: the print block has to switch off app chrome (`AppShell`'s 96 px island
 * clearance, the navigation island itself) that this component does not own and cannot reach any
 * other way. Every other selector is prefixed `rp-` and cannot touch anything outside a sheet.
 */
function documentStyles(
  pageWidthMm: number,
  pageHeightMm: number,
  marginMm: number,
  cssPageSize: string,
  palette: ReportPalette
): string {
  return `
.rp-doc {
  --rp-page-w: ${pageWidthMm}mm;
  --rp-page-h: ${pageHeightMm}mm;
  --rp-margin: ${marginMm}mm;
  --rp-text-w: calc(var(--rp-page-w) - 2 * var(--rp-margin));
  --rp-text-h: calc(var(--rp-page-h) - 2 * var(--rp-margin));
  --rp-base: 10.5pt;
  /* The eight colours of the document, derived from ONE accent by lib/reportTheme — the port of
     the server's report_theme.py, so what is interpolated here is what the .docx will be written
     in rather than an approximation of it. ReportChart is handed the same object through
     ReportPaletteProvider and fills its bars and its pie ramp from it: a chart drawn in navy on a
     sheet ruled in maroon is not a preview of anything. */
  --rp-paper: ${palette.paper};
  --rp-ink: ${palette.ink};
  --rp-muted: ${palette.muted};
  --rp-rule: ${palette.rule};
  --rp-accent: ${palette.accent};
  --rp-accent-soft: ${palette.accentSoft};
  --rp-zebra: ${palette.zebra};
  --rp-thead-fill: ${palette.tableHeadFill};
  --rp-thead-text: ${palette.tableHeadText};
}

/* The stage clips rather than scrolls: the scaler below has already been sized to fit, and for
   the one frame before it is measured a scrollbar appearing and vanishing is worse than a
   hairline of overflow nobody sees. 'clip' and not 'hidden' so the vertical axis stays visible. */
.rp-stage { overflow-x: clip; }
.rp-scaler { transform-origin: top left; }
.rp-pages { display: flex; flex-direction: column; align-items: flex-start; gap: 9mm; width: var(--rp-page-w); }

.rp-sheet {
  box-sizing: border-box;
  width: var(--rp-page-w);
  min-height: var(--rp-page-h);
  padding: var(--rp-margin);
  display: flex;
  flex-direction: column;
  background: var(--rp-paper);
  color: var(--rp-ink);
  /* Calibri is what the .docx asks Word for, and a Windows laptop — which is what a design
     workshop runs on — has it. Carlito is the metric-compatible free face Linux ships, and the
     app's own Inter is the last resort. Naming the document's font here rather than the app's is
     what makes the line breaks in the preview land where Word will put them. */
  font-family: Calibri, Carlito, var(--font-inter), ui-sans-serif, system-ui, sans-serif;
  font-size: var(--rp-base);
  line-height: 1.32;
  border: 1px solid #d9dde8;
  box-shadow: 0 8px 32px rgba(46, 16, 101, 0.12);
}
.rp-body { flex: 1 1 auto; min-width: 0; }
.rp-body > * + * { margin-top: 2.1mm; }

/* Running furniture. Sizes, colours and the hairline rules are read off report_pdf's
   '_draw_furniture': 7.8 pt in the muted colour, a 0.5 pt rule, header right, footer split. */
.rp-runhead {
  display: flex; justify-content: flex-end;
  font-size: 7.8pt; color: var(--rp-muted);
  border-bottom: 0.5pt solid var(--rp-rule);
  padding-bottom: 1.4mm; margin-bottom: 7mm;
  min-height: 4mm;
}
.rp-runfoot {
  display: flex; justify-content: space-between; gap: 8mm;
  font-size: 7.8pt; color: var(--rp-muted);
  border-top: 0.5pt solid var(--rp-rule);
  padding-top: 1.4mm; margin-top: 7mm;
}
/* The page label is a reserved column, not another word in the footer text. report_pdf does this
   in points -- '_draw_furniture' measures the label, subtracts it plus 4 mm from the text column
   and wraps the footer inside what is left (and logs it when the footer still does not fit). The
   preview reserved nothing, and the label was short enough for that not to show: once it grew from
   "Page 12" to "Page 12 of 240" a long stage-20 footer could wrap it onto a second line, which
   pushes past 'min-height: var(--rp-page-h)' and makes the sheet taller than the paper it is
   depicting -- the one thing this screen exists to show accurately. So the label never wraps and
   never shrinks, and the footer text is what gives way, exactly as it does in the file. */
.rp-runfoot > .rp-pageno { margin-left: auto; white-space: nowrap; flex: 0 0 auto; }

/* Headings. 17 / 13.5 / 11.5 / 10.5 pt with accent, accent, accent-soft, muted — report_pdf's
   own ladder. The .docx ladder is a shade larger (18 / 14 / 12 / 11); the .pdf is the one
   modelled here because it is the format that is a fixed page. */
.rp-h1, .rp-h2, .rp-h3, .rp-h4 { margin: 0; font-weight: 700; }
.rp-h1 { font-size: 17pt; color: var(--rp-accent); margin-top: 6.5mm; padding-bottom: 1.4mm; border-bottom: 0.7pt solid var(--rp-rule); }
.rp-h2 { font-size: 13.5pt; color: var(--rp-accent); margin-top: 4.5mm; }
.rp-h3 { font-size: 11.5pt; color: var(--rp-accent-soft); margin-top: 4.5mm; }
.rp-h4 { font-size: 10.5pt; color: var(--rp-muted); margin-top: 4.5mm; }
.rp-heading-number { margin-right: 2mm; }
.rp-body > .rp-h1:first-child, .rp-body > .rp-h2:first-child,
.rp-body > .rp-h3:first-child, .rp-body > .rp-h4:first-child { margin-top: 0; }

/* Paragraph styles, from report_pdf's PARA_STYLE table. */
.rp-p { margin: 0; }
.rp-lead { margin: 0; font-size: calc(var(--rp-base) + 1.4pt); }
.rp-note { margin: 0; font-size: calc(var(--rp-base) - 1.4pt); color: var(--rp-muted); }
.rp-quote { margin: 0 0 0 7mm; font-style: italic; color: var(--rp-accent-soft); }
.rp-caption { margin: 1.5mm 0 0; font-size: calc(var(--rp-base) - 2pt); color: var(--rp-muted); font-style: italic; text-align: center; }
.rp-cover-line { margin: 0; font-size: calc(var(--rp-base) + 0.5pt); color: var(--rp-muted); }
.rp-code { font-family: ui-monospace, "Cascadia Mono", Consolas, monospace; font-size: 0.92em; background: var(--rp-zebra); padding: 0 0.6mm; border-radius: 1px; }
.rp-muted { color: var(--rp-muted); }
.rp-num { text-align: right; font-variant-numeric: tabular-nums; }

.rp-ul, .rp-ol { margin: 0; padding-left: 6mm; }
.rp-ul { list-style: disc; }
.rp-ol { list-style: decimal; }
.rp-ul .rp-ul { list-style: circle; }
.rp-ul .rp-ul .rp-ul { list-style: square; }
.rp-ol .rp-ol { list-style: lower-alpha; }
.rp-ol .rp-ol .rp-ol { list-style: lower-roman; }
.rp-ul li, .rp-ol li { margin: 0.6mm 0; }
.rp-richtext > * + * { margin-top: 2.1mm; }

/* Key/value pairs — the borderless two-column table the writers draw, so a long value wraps
   under its own label instead of pushing the next pair off the line. */
.rp-kv { margin: 0; display: grid; gap: 0; }
.rp-kv-2 { grid-template-columns: 1fr 1fr; column-gap: 8mm; }
.rp-kv > div { display: flex; gap: 3mm; padding: 0.9mm 0; border-bottom: 0.4pt solid var(--rp-rule); }
.rp-kv dt { flex: none; color: var(--rp-muted); font-size: calc(var(--rp-base) - 0.6pt); }
.rp-kv dd { margin: 0; min-width: 0; flex: 1 1 auto; }

.rp-table-figure { margin: 0; }
.rp-scroll { overflow-x: auto; }
.rp-table { width: 100%; border-collapse: collapse; font-size: calc(var(--rp-base) - 0.8pt); }
.rp-table th {
  background: var(--rp-thead-fill); color: var(--rp-thead-text);
  font-size: calc(var(--rp-base) - 1.2pt); font-weight: 700;
  padding: 1.4mm 2mm; text-align: left; vertical-align: bottom;
}
.rp-table td { padding: 1.2mm 2mm; vertical-align: top; border-bottom: 0.4pt solid var(--rp-rule); }
.rp-zebra tbody tr:nth-child(even) { background: var(--rp-zebra); }
.rp-table .rp-total td { font-weight: 700; background: var(--rp-zebra); border-top: 0.7pt solid var(--rp-accent-soft); }

.rp-figure { margin: 0; }
.rp-figure-img { display: block; width: 100%; height: auto; }
.rp-figure-title { margin: 0 0 1.5mm; font-weight: 700; font-size: calc(var(--rp-base) - 0.4pt); color: var(--rp-accent); }
.rp-figure-unit { font-weight: 400; color: var(--rp-muted); }
.rp-figure-absent {
  margin: 0; padding: 6mm 4mm; text-align: center;
  font-size: calc(var(--rp-base) - 1.4pt); color: var(--rp-muted);
  border: 0.5pt dashed var(--rp-rule); background: var(--rp-zebra);
}
.rp-figure-note { margin: 1.5mm 0 0; font-size: calc(var(--rp-base) - 1.8pt); color: var(--rp-muted); }
/* IndiaMap is the app's own component and paints with the app's themed ink ladders, which INVERT
   under data-theme="dark". The sheet deliberately does not invert — it is standing in for paper —
   so a designer proofing a report at the end of a workshop day in dark mode got a map whose state
   borders were near-white on white and effectively absent. The light values are restated here, on
   the map's own wrapper, rather than by touching a component four other screens share. */
.rp-map-live {
  border: 0.5pt solid var(--rp-rule);
  background: var(--rp-paper);
  --ink-300: 167 163 188;
  --ink-500: 97 93 122;
  --line-200: 228 226 239;
}
/* The live figures are SVG at a viewBox width of 900 user units, scaled to whatever width_pct of
   the text column comes to. 'display: block' because an inline SVG sits on the text baseline and
   leaves a descender's worth of white under every figure, which on a page proof reads as a
   caption that has drifted away from its picture. */
.rp-chart { display: block; width: 100%; height: auto; }
.rp-chart-live { border: 0.5pt solid var(--rp-rule); background: var(--rp-paper); }
.rp-figure-legend {
  list-style: none; margin: 2mm 0 0; padding: 0;
  display: grid; grid-template-columns: repeat(auto-fit, minmax(48mm, 1fr)); gap: 0.6mm 4mm;
  font-size: calc(var(--rp-base) - 2pt);
}
.rp-figure-legend li { display: flex; align-items: baseline; gap: 1.6mm; }
.rp-legend-ordinal {
  flex: none; min-width: 4.4mm; height: 4.4mm; border-radius: 999px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--rp-accent); color: #fff; font-size: calc(var(--rp-base) - 3pt); font-weight: 700;
}
.rp-legend-label { font-weight: 600; }
.rp-legend-meta { color: var(--rp-muted); }
.rp-figure-values { width: 100%; border-collapse: collapse; margin-top: 2mm; font-size: calc(var(--rp-base) - 1.6pt); }
.rp-figure-values-caption { caption-side: top; text-align: left; color: var(--rp-muted); padding-bottom: 1mm; }
.rp-figure-values th { text-align: left; font-weight: 400; padding: 0.7mm 2mm 0.7mm 0; }
.rp-figure-values td { padding: 0.7mm 0 0.7mm 2mm; border-bottom: 0.4pt solid var(--rp-rule); }
.rp-figure-toggle {
  margin-top: 2mm; display: inline-flex; align-items: center; gap: 0.4rem;
  font-family: var(--font-inter), ui-sans-serif, system-ui, sans-serif;
  font-size: 0.75rem; font-weight: 500; color: var(--rp-accent-soft);
  text-decoration: underline; text-underline-offset: 2px;
}

.rp-grid { display: grid; gap: 3mm; }
.rp-grid-img { display: block; width: 100%; aspect-ratio: 4 / 3; object-fit: cover; }
.rp-photo-pending { display: block; width: 100%; aspect-ratio: 4 / 3; background: var(--rp-zebra); }
.rp-photo-missing {
  display: flex; align-items: flex-start; gap: 1.6mm; padding: 3mm;
  font-size: calc(var(--rp-base) - 2pt); color: var(--rp-muted);
  border: 0.5pt dashed var(--rp-rule); background: var(--rp-zebra);
}

.rp-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(28mm, 1fr)); gap: 3mm; }
.rp-metric { border: 0.5pt solid var(--rp-rule); padding: 2.5mm 3mm; display: grid; gap: 0.6mm; }
.rp-metric-value { font-size: 17pt; font-weight: 700; color: var(--rp-accent); line-height: 1.1; }
.rp-metric-unit { font-size: 9pt; font-weight: 400; color: var(--rp-muted); }
.rp-metric-label { font-size: calc(var(--rp-base) - 2pt); color: var(--rp-muted); }

.rp-callout { border-left: 1.2pt solid var(--rp-accent-soft); padding: 2.5mm 3mm; background: var(--rp-zebra); }
.rp-callout p { margin: 0; }
.rp-callout-title { font-weight: 700; margin-bottom: 0.8mm !important; }
.rp-callout-warning { border-left-color: #92400e; background: #fef3c7; }
.rp-callout-success { border-left-color: #15803d; background: #dcfce7; }

.rp-signatures { display: grid; grid-template-columns: repeat(auto-fit, minmax(42mm, 1fr)); gap: 8mm; margin-top: 12mm; }
.rp-signatures > div { display: grid; gap: 0.8mm; }
.rp-signature-rule { display: block; height: 12mm; border-bottom: 0.6pt solid var(--rp-ink); }
.rp-signature-name { font-weight: 600; }
.rp-signature-role { font-size: calc(var(--rp-base) - 2pt); color: var(--rp-muted); }

.rp-toc { border: 0.5pt solid var(--rp-rule); padding: 4mm; }
.rp-toc-title { margin: 0; font-size: 16pt; font-weight: 700; color: var(--rp-accent); }
.rp-toc-note { margin: 1.5mm 0 0; font-size: calc(var(--rp-base) - 1.4pt); color: var(--rp-muted); }

/* The cover. Laid out as one page top to bottom with the hero photograph absorbing whatever is
   left over, which is what report_pdf's '_block_cover' does — it measures everything first
   precisely so the closing "Generated on …" line cannot be pushed onto a second, blank page. */
.rp-cover { flex: 1 1 auto; display: flex; flex-direction: column; text-align: center; }
.rp-cover-head { display: grid; gap: 3mm; justify-items: center; margin-top: 8mm; }
.rp-cover-org { margin: 0; font-size: calc(var(--rp-base) + 0.5pt); color: var(--rp-muted); letter-spacing: 0.04em; }
.rp-cover-logo { display: block; max-height: 22mm; width: auto; }
.rp-cover-title { margin: 14mm 0 0; font-size: 26pt; line-height: 1.18; font-weight: 700; color: var(--rp-accent); }
.rp-cover-subtitle { margin: 3mm 0 0; font-size: 14pt; color: var(--rp-accent-soft); }
.rp-cover-hero { display: block; margin: 8mm auto 0; max-height: 62mm; max-width: 80%; width: auto; object-fit: contain; }
.rp-cover-info { margin: 8mm auto 0; display: grid; gap: 0; width: 100%; max-width: 120mm; text-align: left; }
.rp-cover-info > div { display: flex; gap: 4mm; padding: 1mm 0; border-bottom: 0.4pt solid var(--rp-rule); }
.rp-cover-info dt { flex: none; width: 45%; color: var(--rp-muted); }
.rp-cover-info dd { margin: 0; min-width: 0; flex: 1 1 auto; }
.rp-cover-foot { margin-top: auto; padding-top: 8mm; }
.rp-cover-foot p { margin: 0; font-size: calc(var(--rp-base) - 1.4pt); color: var(--rp-muted); }

/* Between two sheets, in the app's own themed ink — this is chrome, not paper. */
.rp-break { display: flex; align-items: center; gap: 0.75rem; width: var(--rp-page-w); }
.rp-break span:first-child, .rp-break span:last-child { height: 1px; flex: 1 1 auto; background: rgb(var(--line-200)); }
/* ink-500, NOT the ink-300 placeholder rung — the same correction made on SearchableSelect's and
   StageReferenceField's triggers, for the same reason. This label is 11px, uppercase and letter-
   spaced, which is already the hardest shape to read; at ink-300 it measured 2.43:1 on the sheet,
   barely half the 4.5:1 AA floor. It is not decoration: it is the only thing telling a designer
   where the printed page will split, and laying out a ministry submission means knowing whether a
   table is about to be cut in half. A designer with low vision was being asked to place content
   around a boundary they could not see. ink-500 is 6.6:1 in light and 6.4:1 in dark, and the type
   stays as quiet as the design intends.
   (No backticks in this comment: the whole stylesheet is a JS template literal.) */
.rp-break-label {
  flex: none; font-family: var(--font-inter), ui-sans-serif, system-ui, sans-serif;
  font-size: 0.6875rem; text-transform: uppercase; letter-spacing: 0.14em; color: rgb(var(--ink-500));
}

.rp-print-only { display: none; }

@media print {
  /* The browser owns pagination here, so it also owns the margin: a sheet that carried its own
     25 mm padding would put that padding on the first printed page of a section and none on the
     overflow, and the overflow is exactly where a long table goes. */
  @page { size: ${cssPageSize}; margin: ${marginMm}mm; }

  html, body { background: #fff !important; }
  /* AppShell's 96 px island clearance and the island itself. Neither is reachable from a scoped
     selector, and printing a navigation pill onto page one of a ministry submission is the
     failure this rule exists for. */
  #main-content { padding: 0 !important; max-width: none !important; }
  .nav-island-frame, .nav-sheet-overlay, [data-rp-noprint] { display: none !important; }

  /* EVERYTHING THAT IS NOT THE DOCUMENT IS DROPPED. The page header, the template picker, the
     completeness bar, the warning banners and the settings form are all real, useful chrome and
     not one of them belongs in a file somebody is about to email to a ministry. They are direct
     children of AppShell's <main>, so they are hidden as a group and the sheet's host is put back
     — a per-section opt-out would silently start printing the next section anybody adds. The host
     also sheds the panel it lives in: a 1px border and a purple shadow around page one is the
     tell that a PDF came out of a web page. */
  #main-content > * { display: none !important; }
  #main-content > .rp-host {
    display: block !important;
    padding: 0 !important;
    margin: 0 !important;
    border: 0 !important;
    border-radius: 0 !important;
    box-shadow: none !important;
    background: transparent !important;
  }

  .rp-fit { height: auto !important; }
  .rp-stage { overflow: visible !important; }
  .rp-scaler { transform: none !important; }
  .rp-pages { display: block; width: auto; gap: 0; }
  .rp-break { display: none !important; }

  .rp-sheet {
    width: auto !important; min-height: 0 !important; padding: 0 !important;
    border: 0 !important; box-shadow: none !important;
    break-after: page; page-break-after: always;
  }
  .rp-sheet:last-child { break-after: auto; page-break-after: auto; }

  /* Colour is meaning in this document — a table header reversed out of navy, an amber warning
     callout — and browsers drop backgrounds when printing unless told not to. */
  .rp-doc { -webkit-print-color-adjust: exact; print-color-adjust: exact; }

  .rp-scroll { overflow: visible !important; }
  .rp-figure, .rp-metric, .rp-callout, .rp-signatures, .rp-toc, .rp-cover-info > div, .rp-kv > div, tr {
    break-inside: avoid; page-break-inside: avoid;
  }
  /* A heading alone at the foot of a page is worse than a slightly short page — the same
     judgement report_pdf makes with keepNext, and report_docx with w:keepNext. */
  .rp-h1, .rp-h2, .rp-h3, .rp-h4, .rp-figure-title { break-after: avoid; page-break-after: avoid; }
  thead { display: table-header-group; }

  .rp-print-hide { display: none !important; }
  .rp-print-only { display: block !important; }

  /* THE TOTAL IS A SCREEN CLAIM AND DOES NOT TRAVEL INTO THE FILE. On screen the "of M" is safe
     because the strip above the sheets says in as many words that M is the declared-page floor --
     but that strip carries 'data-rp-noprint' and is dropped above with every other piece of
     chrome, because a warning must not print inside a document going to a ministry. A printed
     "Page 3 of 5" with nothing left to qualify it is precisely the number that gets quoted in a
     covering email. Worse, the rules above hand pagination back to the browser -- the sheet loses
     its min-height and only asks for a break after itself -- so a sheet whose content overflows
     becomes two printed pages, and then the printed total is not a floor, it is simply wrong. The
     ordinal alone is what this file can stand behind, so that is all it carries. Deleting this
     rule silently re-arms the failure; 'e2e/report-page-label-unit.spec.ts' pins it. */
  .rp-of { display: none !important; }
}
`;
}

export type ReportSheetsProps = {
  blocks: PreviewBlock[];
  pageSize: string;
  /** The running head. Empty prints an empty rule, exactly as the .pdf writer does. */
  headerText: string;
  footerText: string;
  mediaUrls: Record<string, string | null>;
  /**
   * The document's colours. Omitted, the sheets are drawn in the default indigo — which is what
   * every caller did before the report page grew a colour picker, and what this page still shows
   * for a designer who has not chosen one.
   */
  palette?: ReportPalette;
  /**
   * Whether that palette is the designer's choice or merely the default.
   *
   * It changes one sentence in the strip above the sheets, and that sentence is the difference
   * between a preview that is telling the truth about the file and one that is quietly not: the
   * template's own accent is not on the preview wire, so an unchosen palette is a guess.
   */
  paletteChosen?: boolean;
};

/**
 * Fit-to-width, measured rather than guessed.
 *
 * An A4 sheet is 793.7 CSS pixels across and the protected shell is at most 1248 wide, so on a
 * laptop nothing is scaled at all. On a phone the sheet is more than twice the viewport, and the
 * house rule for wide content — scroll it inside its own container — is the wrong answer here:
 * a document proof read two centimetres at a time is not a proof. So the sheets are scaled down
 * to fit and the caller says by how much.
 *
 * The wrapper's height has to be corrected by hand because `transform` does not affect layout: a
 * stack of sheets scaled to 60% still reserves 100% of its height, and the page would end in two
 * screens of blank. Both measurements come from one `ResizeObserver`, which reports the
 * PRE-TRANSFORM box — so reading the inner height and then setting the outer height from it
 * cannot feed back into itself.
 */
function useFitToWidth(pageWidthMm: number): {
  stageRef: RefObject<HTMLDivElement | null>;
  pagesRef: RefObject<HTMLDivElement | null>;
  scale: number;
  fittedHeight: number | undefined;
} {
  const stageRef = useRef<HTMLDivElement | null>(null);
  const pagesRef = useRef<HTMLDivElement | null>(null);
  const [available, setAvailable] = useState(0);
  const [naturalHeight, setNaturalHeight] = useState(0);

  useEffect(() => {
    const stage = stageRef.current;
    const pages = pagesRef.current;
    if (!stage || !pages || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        if (entry.target === stage) setAvailable(entry.contentRect.width);
        else setNaturalHeight(entry.contentRect.height);
      }
    });
    observer.observe(stage);
    observer.observe(pages);
    return () => observer.disconnect();
  }, []);

  const pageWidthPx = pageWidthMm * PX_PER_MM;
  // Never scaled UP. A designer looking at a 140%-enlarged page would be judging a photograph's
  // sharpness at a magnification the paper will never be read at.
  const scale = available > 0 ? Math.min(1, available / pageWidthPx) : 1;

  return {
    stageRef,
    pagesRef,
    scale,
    fittedHeight: naturalHeight ? naturalHeight * scale : undefined
  };
}

export function ReportSheets({
  blocks,
  pageSize,
  headerText,
  footerText,
  mediaUrls,
  palette = REPORT_PALETTE,
  paletteChosen = false
}: ReportSheetsProps) {
  const geometry = useMemo(() => pageGeometry(pageSize), [pageSize]);
  const sheets = useMemo(() => splitIntoSheets(blocks), [blocks]);
  const css = useMemo(
    () => documentStyles(geometry.widthMm, geometry.heightMm, geometry.marginMm, geometry.cssPageSize, palette),
    [geometry, palette]
  );
  const { stageRef, pagesRef, scale, fittedHeight } = useFitToWidth(geometry.widthMm);

  return (
    <div className="rp-doc">
      <style>{css}</style>

      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-xs text-ink-500" data-rp-noprint>
        <span>
          {sheets.length} declared page{sheets.length === 1 ? "" : "s"} · {geometry.label}
        </span>
        {/* Every truncation, cap and approximation is stated on screen. Here the approximation is
            the pagination itself: only breaks the template asked for are knowable in a browser. */}
        <span>
          Page breaks the template declares are shown. Where a section runs long the file adds pages this preview
          cannot know about, so the &ldquo;of {sheets.length}&rdquo; in each running foot below is a floor, not the
          final count.
        </span>
        {scale < 0.995 ? <span>Scaled to {Math.round(scale * 100)}% to fit this window; printing uses full size.</span> : null}
        {/* Which of the two colour situations the reader is in. Never silent about it: the
            template's own accent is not on the preview wire, so the default case is a guess and
            has to say so, while the chosen case is exact and is worth saying too — it is the
            reason the designer can trust what they are looking at. */}
        <span>
          {paletteChosen
            ? "Drawn in the accent colour chosen above — the same colour the .docx and .pdf will be written in."
            : "Drawn in the default report palette; each template writes its own accent colour into the file. Choose a colour above to see the document in it."}
        </span>
      </div>

      <div ref={stageRef} className="rp-stage">
        <div className="rp-fit" style={{ height: fittedHeight }}>
          <div className="rp-scaler" style={{ transform: scale < 1 ? `scale(${scale})` : undefined }}>
            <div ref={pagesRef} className="rp-pages">
              <ReportPaletteProvider palette={palette}>
                <MediaUrlProvider urls={mediaUrls}>
                  {sheets.map((sheet, index) => (
                    <Fragment key={sheet.pageNumber}>
                      {index > 0 ? (
                        <div className="rp-break" aria-hidden>
                          <span />
                          <span className="rp-break-label">Page break</span>
                          <span />
                        </div>
                      ) : null}
                      <article
                        className="rp-sheet"
                        aria-label={sheet.isCover ? "Cover page" : `Page ${sheet.pageNumber} of ${sheets.length}`}
                      >
                        {/* The cover carries no running furniture, in the file and here: both
                            writers suppress it on page one, and a craft name printed above a
                            ministry cover would be the first thing an officer saw. */}
                        {sheet.isCover ? null : (
                          <div className="rp-runhead" aria-hidden>
                            <span>{headerText}</span>
                          </div>
                        )}

                        <div className="rp-body">
                          {sheet.blocks.map((block, blockIndex) => (
                            <ReportBlock key={blockIndex} block={block} />
                          ))}
                        </div>

                        {sheet.isCover ? null : (
                          <div className="rp-runfoot" aria-hidden>
                            <span>{footerText}</span>
                            {/* "Page N of M", the label all four FILE renderers print — both .docx
                                writers resolve it from PAGE/NUMPAGES and both PDF renderers take M
                                from their measuring pass. This screen is the fifth surface that
                                prints it and was the last one the fix reached: a designer who
                                proofed "Page 3" here and then handed over a document reading
                                "Page 3 of 12" had two numbers of different shapes for the same
                                page. The M here is the DECLARED page count and is a floor rather
                                than the file's own total — see `splitIntoSheets` for why nothing
                                in a browser can know the rest, and the strip above the sheets for
                                where that is said to the reader.

                                THE "of M" IS ITS OWN ELEMENT SO THAT PRINT CAN DROP IT. The strip
                                that qualifies the number is chrome and does not print, and a
                                total with nothing left to qualify it — in the one artefact a
                                designer emails — is the failure that floor sentence exists to
                                prevent. See the `.rp-of` rule in the print block above. */}
                            <span className="rp-pageno">
                              Page {sheet.pageNumber}
                              <span className="rp-of"> of {sheets.length}</span>
                            </span>
                          </div>
                        )}
                      </article>
                    </Fragment>
                  ))}
                </MediaUrlProvider>
              </ReportPaletteProvider>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
