"use client";

import {
  Fragment,
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type RefObject
} from "react";

import {
  MediaUrlProvider,
  ReportBlock,
  ReportFigureModeProvider,
  ReportGeometryProvider,
  type ReportFigureModes
} from "@/components/designworkshop/report/ReportBlock";
import { ReportPaletteProvider } from "@/components/designworkshop/report/ReportChart";
import {
  pageGeometry,
  PX_PER_MM,
  REPORT_PALETTE,
  type PageGeometry,
  type PreviewBlock
} from "@/components/designworkshop/report/previewModel";
import {
  packPages,
  type FlowItem,
  type PackedDocument,
  type PageSlice
} from "@/components/designworkshop/report/reportPagination";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import type { ReportPalette } from "@/lib/reportTheme";

const HEAD_CLEARANCE_MM = 6;

const FOOT_RESERVE_MM = 10;

function documentStyles(
  pageWidthMm: number,
  pageHeightMm: number,
  marginMm: number,
  cssPageSize: string,
  palette: ReportPalette
): string {
  return `
.rp-doc {
  position: relative;
  --rp-page-w: ${pageWidthMm}mm;
  --rp-page-h: ${pageHeightMm}mm;
  --rp-margin: ${marginMm}mm;
  --rp-text-w: calc(var(--rp-page-w) - 2 * var(--rp-margin));
  --rp-text-h: calc(var(--rp-page-h) - 2 * var(--rp-margin));
  /* The writer's two clearances — see HEAD_CLEARANCE_MM above for why these are the file's numbers
     and not a measurement of this preview's own running head. */
  --rp-head-clear: ${HEAD_CLEARANCE_MM}mm;
  --rp-foot-reserve: ${FOOT_RESERVE_MM}mm;
  /* What a block may fill on an ordinary page: '(self.top - self.bottom)' in report_pdf, which is
     the column the 0.62 and 0.30 image caps are fractions of. */
  --rp-body-h: calc(var(--rp-text-h) - var(--rp-head-clear) - var(--rp-foot-reserve));
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

/* The stage clips rather than scrolls while the sheets are no wider than the window: the scaler
   has already been sized to fit, and for the one frame before it is measured a scrollbar appearing
   and vanishing is worse than a hairline of overflow nobody sees. 'clip' and not 'hidden' so the
   vertical axis stays visible. Zoomed IN past the fit, the page genuinely is wider than the window
   and the house rule for wide content applies instead: it scrolls inside its own container. */
.rp-stage { overflow-x: clip; }
.rp-stage-pan { overflow-x: auto; overscroll-behavior-x: contain; }
.rp-scaler { transform-origin: top left; }
.rp-pages { display: flex; flex-direction: column; align-items: flex-start; gap: 9mm; width: var(--rp-page-w); }

.rp-sheet {
  box-sizing: border-box;
  width: var(--rp-page-w);
  /* A FIXED height, not a minimum. This one word is the difference between a depiction of a sheet
     of paper and a box that grows to whatever is poured into it; everything in reportPagination
     exists so that nothing has to be poured past it. */
  height: var(--rp-page-h);
  /* The margin, PLUS the writer's two clearances — see HEAD_CLEARANCE_MM. Spending them here
     rather than as padding on '.rp-body' is what keeps the body's border box, its content box and
     the number handed to the packer all one value, which in turn keeps the post-draw overflow
     check ('scrollHeight' against 'clientHeight', far below) an exact subtraction instead of one
     that has to reason about whether a browser counts bottom padding as part of a scrolling area.
     The cover overrides the top back to a bare margin, because '_new_page' applies the head
     clearance only from page two. */
  padding: calc(var(--rp-margin) + var(--rp-head-clear)) var(--rp-margin)
           calc(var(--rp-margin) + var(--rp-foot-reserve));
  display: flex;
  flex-direction: column;
  /* The containing block for the running furniture, which is positioned into the two MARGIN bands
     rather than laid out in the flow — see HEAD_CLEARANCE_MM. An absolutely positioned descendant
     resolves against the PADDING box, which is the text column inset by those same two clearances;
     the two rules below add them back to land on the text column's own edges. */
  position: relative;
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
/* PAGE ONE TAKES NO HEAD CLEARANCE. '_new_page' applies its '- 6 * MM' only when 'self._page > 1',
   so the cover starts at the very top of the text column; it keeps the foot reserve, because
   'self.bottom' is 'margin + 10 * MM' on every page including the first. Nothing else about the
   cover differs, which is why this is one declaration and not a second sheet rule. */
.rp-sheet-cover { padding-top: var(--rp-margin); }

/* min-height: 0 is what lets a flex child be SHORTER than its content — without it the body's
   automatic minimum size is its content's height and the fixed sheet above is pushed open again,
   which is the same defect wearing a different property name. The overflow guard below is a belt:
   the packer's arithmetic decides what goes here, and ReportSheets measures every drawn page
   afterwards and says so on screen if one of them ever disagrees by more than a pixel.

   NO PADDING OF ITS OWN, deliberately: the sheet above already spent the two clearances, so this
   box IS the fillable column and its border box, its content box, the height the probe measures
   and the packer's 'contentPx' are one number with nothing to reconcile. */
.rp-body { flex: 1 1 auto; min-width: 0; min-height: 0; overflow: hidden; }

/* One block, or one piece of one, on one page.
   '.rp-block' establishes a block formatting context so that the block's OWN top margin (a level-1
   heading spends 6.5 mm above itself) is contained rather than collapsing through — the measuring
   pass and the drawing pass then see the identical box, which is the whole reason the pagination
   can be trusted. '.rp-slice' is the window: a fixed height with the block pushed up inside it, so
   the second half of a paragraph is the same line boxes the first half was measured against rather
   than a fresh wrap. */
.rp-slice { overflow: hidden; width: 100%; }
.rp-slice-cover > .rp-block { height: 100%; }
.rp-block { display: flow-root; }
.rp-scaled { transform-origin: top left; }

/* Running furniture. Sizes, colours and the hairline rules are read off report_pdf's
   '_draw_furniture': 7.8 pt in the muted colour, a 0.5 pt rule, header right, footer split.

   BOTH ARE OUT OF FLOW, in the margin band, and cost the text column nothing — which is what
   '_draw_furniture' does, and why the sheet spends the two clearances instead. The head's rule
   sits 2.6 mm above the top of the text column ('self.page_h - self.margin + 2.6 * MM') with its
   type stacking upward from there into the margin; the foot's rule sits exactly ON the bottom of
   the column ('foot_y + 4 * MM' where 'foot_y = self.margin - 4 * MM') with its type below it.
   A head or foot long enough to wrap now grows into the margin and off the sheet, as it does in
   the file, instead of eating a line of the body. */
/* POSITIONED AGAINST THE SHEET'S PADDING BOX, which is the whole sheet: an absolutely positioned
   box resolves 'top'/'bottom' against the padding edge, not the content edge. These two rules used
   to read 'bottom: calc(100% + …)' and 'top: calc(100% + …)' as though the containing block were
   the text column — which put the running head ABOVE the top edge of the paper and the running foot
   BELOW its bottom edge, both drawn over the "Page break" mark between two sheets (measured
   2026-10-16). So: the head's rule 2.6 mm above the top of the text column, the foot's rule on its
   bottom, both inset by the margin to the column's own width — '_draw_furniture' exactly. */
.rp-runhead {
  position: absolute; left: var(--rp-margin); right: var(--rp-margin);
  bottom: calc(100% - var(--rp-margin) + 2.6mm);
  display: flex; justify-content: flex-end;
  font-size: 7.8pt; color: var(--rp-muted);
  border-bottom: 0.5pt solid var(--rp-rule);
  padding-bottom: 1.4mm;
  min-height: 4mm;
}
.rp-runfoot {
  position: absolute; left: var(--rp-margin); right: var(--rp-margin);
  top: calc(100% - var(--rp-margin));
  display: flex; justify-content: space-between; gap: 8mm;
  font-size: 7.8pt; color: var(--rp-muted);
  border-top: 0.5pt solid var(--rp-rule);
  padding-top: 1.4mm;
}
/* The page label is a reserved column, not another word in the footer text. report_pdf does this
   in points -- '_draw_furniture' measures the label, subtracts it plus 4 mm from the text column
   and wraps the footer inside what is left (and logs it when the footer still does not fit). The
   preview reserved nothing, and the label was short enough for that not to show: once it grew from
   "Page 12" to "Page 12 of 240" a long stage-20 footer could wrap it onto a second line, which
   used to push past the page height and make the sheet taller than the paper it is depicting. The
   sheet is a fixed box now and cannot grow; and since the foot was moved out of the flow into the
   margin band a wrap can no longer eat a line of the body either -- it grows downward into the
   margin and is clipped at the edge of the sheet, which is what the file does with the same line.
   The label still never wraps and never shrinks while the footer text gives way, exactly as it
   does in the file. */
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
/* There is deliberately NO ':first-child { margin-top: 0 }' for a heading any more. It used to
   drop the lead above the first heading of a sheet, and a rule that fires in the drawing pass and
   not in the measuring pass is precisely the drift that puts a break in the wrong place --
   report_pdf carries the same lesson twice in as many words ("The two passes must see identical
   geometry"). It is also what the file does: '_block_heading' takes its lead AFTER '_new_page',
   so a heading at the top of a page is indented from the top margin there too. */

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

/* A gallery: the columns are '_block_image_grid's own count (see galleryColumns), each cell is
   padded 2 mm a side as the writer insets it, and the rows are 3 mm apart as its row height adds.
   The photograph is FITTED into the cell, never cropped — the file preserves the aspect ratio. */
.rp-grid { display: grid; column-gap: 0; row-gap: 3mm; }
.rp-grid-cell { margin: 0; padding: 0 2mm; min-width: 0; }
.rp-grid-caption { margin: 1.2mm 0 0; font-size: 7.6pt; line-height: 1.25; font-style: italic; color: var(--rp-muted); text-align: center; }

/* One photograph's BOX. Its width and aspect ratio are set inline from the dimensions the payload
   carries, so it is the same size before the picture loads, after it loads and if it never does —
   see ReportImage. The picture fades in over a quiet ground rather than popping, and the global
   reduced-motion rules zero the transition. */
.rp-photo { display: block; position: relative; overflow: hidden; margin: 0 auto; max-width: 100%; background: var(--rp-zebra); }
.rp-photo > img {
  position: absolute; left: 50%; top: 50%; max-width: none; object-fit: contain;
  transform: translate(-50%, -50%);
  image-orientation: none;
  opacity: 0; transition: opacity 180ms ease-out;
}
.rp-photo > img[data-loaded] { opacity: 1; }
.rp-photo[data-state="missing"] { background: var(--rp-zebra); outline: 0.5pt dashed var(--rp-rule); outline-offset: -0.5pt; }
.rp-photo-missing {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 1.6mm; padding: 3mm;
  text-align: center; font-size: calc(var(--rp-base) - 2pt); color: var(--rp-muted);
}

.rp-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(28mm, 1fr)); gap: 3mm; }
.rp-metric { border: 0.5pt solid var(--rp-rule); padding: 2.5mm 3mm; display: grid; gap: 0.6mm; }
.rp-metric-value { font-size: 17pt; font-weight: 700; color: var(--rp-accent); line-height: 1.1; }
.rp-metric-unit { font-size: 9pt; font-weight: 400; color: var(--rp-muted); }
.rp-metric-label { font-size: calc(var(--rp-base) - 2pt); color: var(--rp-muted); }

.rp-callout { padding: 2.5mm 3mm; background: var(--rp-zebra); border-radius: 2mm; }
.rp-callout p { margin: 0; }
.rp-callout-title { font-weight: 700; margin-bottom: 0.8mm !important; }
.rp-callout-warning { background: #fef3c7; }
.rp-callout-success { background: #dcfce7; }

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
   precisely so the closing "Generated on …" line cannot be pushed onto a second, blank page.
   The cover's slice is given the whole page height, so 'flex: 1 1 auto' still has something to
   grow into. */
.rp-cover { flex: 1 1 auto; display: flex; flex-direction: column; text-align: center; min-height: 100%; }
.rp-cover-head { display: grid; gap: 3mm; justify-items: center; margin-top: 8mm; }
.rp-cover-org { margin: 0; font-size: calc(var(--rp-base) + 0.5pt); color: var(--rp-muted); letter-spacing: 0.04em; }
.rp-cover-logo { background: transparent; }
.rp-cover-title { margin: 14mm 0 0; font-size: 26pt; line-height: 1.18; font-weight: 700; color: var(--rp-accent); }
.rp-cover-subtitle { margin: 3mm 0 0; font-size: 14pt; color: var(--rp-accent-soft); }
/* THE HERO ABSORBS WHATEVER THE COVER HAS LEFT, which is '_block_cover's rule: it measures the
   rest of the page first so the closing line can never be pushed onto a second, blank page. So the
   cover is laid out at exactly one page's height (see the measuring flow) and the photograph's box
   is the one thing in it that may shrink, fitted rather than cropped. Before this the cover was
   measured taller than the paper and the whole title page was drawn shrunk, every time. */
.rp-cover-hero { margin-top: 8mm; flex: 0 1 auto; min-height: 0; background: transparent; }
/* The cover's body: the text column less the foot reserve, less the sheet's two 1px borders, which
   the border-box sheet takes out of its content box too — the same box the cover probe measures. */
.rp-block-cover { height: calc(var(--rp-text-h) - var(--rp-foot-reserve) - 2px); overflow: hidden; }
.rp-block-cover > .rp-cover, .rp-slice-cover > .rp-block > .rp-cover { height: 100%; min-height: 0; }
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

/* THE MEASURING SHELL. Laid out for real — a display:none element has no layout and would measure
   nothing — and parked out of the way rather than hidden, because 'visibility: hidden' and
   'content-visibility' both change what descendants do. Its host is a 0 x 0 clipping box, so the
   off-screen tree cannot lengthen the page or create a scrollable region of its own; clipping
   affects painting and never layout, which is the property being relied on. */
.rp-measure-host { position: absolute; top: 0; left: 0; width: 0; height: 0; overflow: clip; }
.rp-measure { position: absolute; top: 0; left: 0; }
.rp-measure-flow { width: var(--rp-text-w); }

.rp-print-only { display: none; }

/* A neighbouring stage's content on a focused page: drawn where it falls, faded towards the focused
   stage so it reads as context, with a quiet label that takes no space in the flow. Chrome, not
   paper — the label is in the app's own type. */
.rp-context { position: relative; }
.rp-context-body { opacity: 0.32; }
.rp-context-before .rp-context-body { -webkit-mask-image: linear-gradient(to bottom, transparent 0%, #000 70%); mask-image: linear-gradient(to bottom, transparent 0%, #000 70%); }
.rp-context-after .rp-context-body { -webkit-mask-image: linear-gradient(to top, transparent 0%, #000 70%); mask-image: linear-gradient(to top, transparent 0%, #000 70%); }
.rp-context-label {
  position: absolute; left: 50%; z-index: 1; transform: translateX(-50%); white-space: nowrap;
  font-family: var(--font-inter), ui-sans-serif, system-ui, sans-serif; font-size: 0.6875rem; letter-spacing: 0.06em;
  color: rgb(var(--ink-500)); background: rgb(var(--card)); border: 1px solid rgb(var(--line-200)); border-radius: 999px; padding: 0.1rem 0.6rem;
}
.rp-context-before .rp-context-label { bottom: 0; }
.rp-context-after .rp-context-label { top: 0; }

@media print {
  /* ONE SHEET, ONE PHYSICAL PAGE. The sheets are already exactly one page of content each, so the
     browser is given the same paper and asked only to put a break after each one. Before the flow
     existed this block did the opposite -- it released the height and let the browser re-paginate
     -- because an overflowing sheet had to go somewhere; nothing overflows now, and re-paginating
     would undo the layout a designer just approved.

     THE @page BOX NOW CARRIES NO MARGIN, and that is a correction rather than a preference. It
     used to carry the document's margin, and the sheet shed its own padding to match: correct
     while the running head and foot were flex children of the text column, because the sheet WAS
     the text column and the furniture printed inside it. They are not children of it any more --
     they are positioned into the MARGIN band, which is where all four file renderers draw them --
     and nothing can paint into a @page margin box. Leaving the margin there would have printed a
     submission with no running head and no page numbers anywhere on it. So the paper is the whole
     paper and the sheet is printed exactly as it is drawn: its own margin, its own clearances, its
     furniture in the band. */
  @page { size: ${cssPageSize}; margin: 0; }

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
  .rp-stage, .rp-stage-pan { overflow: visible !important; }
  .rp-scaler { transform: none !important; }
  .rp-pages { display: block; width: auto; gap: 0; }
  .rp-break { display: none !important; }
  /* Nothing off-screen may reach the printer. */
  .rp-measure-host { display: none !important; }

  .rp-sheet {
    /* Width, height, padding and position are all inherited from the screen rule now, unchanged:
       with a margin-less @page the drawn sheet and the physical page are the same box, so there is
       nothing left to reconcile and the furniture keeps the band it is positioned into. */
    border: 0 !important; box-shadow: none !important;
    /* A sheet exactly as tall as the @page box can round up by a fraction of a device pixel and
       ask for a second, blank page. It cannot overflow -- the packer measured what is in it
       against this same height -- so clipping the last hairline is the honest guard, and it is the
       same guard the on-screen body already carries. */
    overflow: hidden;
    break-after: page; page-break-after: always;
  }
  .rp-sheet:last-child { break-after: auto; page-break-after: auto; }
  .rp-photo > img { opacity: 1 !important; transition: none !important; }

  /* Colour is meaning in this document — a table header reversed out of navy, an amber warning
     callout — and browsers drop backgrounds when printing unless told not to. */
  .rp-doc { -webkit-print-color-adjust: exact; print-color-adjust: exact; }

  .rp-scroll { overflow: visible !important; }
  /* The page a block sits on is decided above, by measurement, so these are not pagination hints
     any more -- they are what stops the browser breaking INSIDE a slice that is already exactly
     one page's worth of content. */
  .rp-slice { break-inside: avoid; page-break-inside: avoid; }
  .rp-figure, .rp-metric, .rp-callout, .rp-signatures, .rp-toc, .rp-cover-info > div, .rp-kv > div, tr {
    break-inside: avoid; page-break-inside: avoid;
  }
  thead { display: table-header-group; }

  .rp-print-hide { display: none !important; }
  .rp-print-only { display: block !important; }

  /* THE TOTAL IS A SCREEN CLAIM AND DOES NOT TRAVEL INTO THE FILE. On screen the "of M" is safe
     because the strip above the sheets says in as many words what M is -- a count measured with
     this browser's fonts, close to the file's and not guaranteed equal to it -- but that strip
     carries 'data-rp-noprint' and is dropped above with every other piece of chrome, because a
     qualification must not print inside a document going to a ministry. A printed "Page 3 of 5"
     with nothing left to qualify it is precisely the number that gets quoted in a covering email,
     and the .pdf the server generates is the artefact that number has to be true of. Real
     pagination RAISES the cost of overclaiming here rather than removing it: the count is now
     close enough to be believed and still not the file's own. The ordinal alone is what this file
     can stand behind, so that is all it carries. Deleting this rule silently re-arms the failure;
     'e2e/report-page-label-unit.spec.ts' pins it. */
  .rp-of { display: none !important; }
}
`;
}

type BlockRule = {
  keepWithNext?: boolean;
  splittable?: boolean;
  preferWhole?: boolean;
  breakAfter?: boolean;
  isCover?: boolean;
  rows?: string;
  gridRows?: string;
  lines?: string;
  header?: string;
};

const BLOCK_RULES: Record<PreviewBlock["type"], BlockRule> = {
  COVER: { isCover: true },
  TOC: { breakAfter: true },
  HEADING: { keepWithNext: true },
  PARAGRAPH: { splittable: true, lines: ":scope > p, :scope > blockquote" },
  BULLETLIST: { splittable: true, rows: ":scope > .rp-ul > li, :scope > .rp-ol > li" },
  RICHTEXT: { splittable: true, rows: ":scope > .rp-richtext > *" },
  KEYVALUE: { splittable: true, rows: ":scope > .rp-kv > div" },
  TABLE: {
    splittable: true,
    rows: ":scope .rp-table > tbody > tr",
    header: ":scope .rp-table > thead"
  },
  IMAGEGRID: { splittable: true, gridRows: ":scope .rp-grid > *" },
  CALLOUT: { splittable: true, preferWhole: true, lines: ":scope .rp-callout > p" },
  IMAGE: {},
  MAP: {},
  CHART: {},
  METRICROW: {},
  SIGNATURE: {},
  SPACER: {},
  PAGEBREAK: {}
};

type PlannedBlock = {
  blockIndex: number;
  block: PreviewBlock;
  rule: BlockRule;
  breakBefore: boolean;
};

function planFlow(blocks: PreviewBlock[]): PlannedBlock[] {
  const planned: PlannedBlock[] = [];
  let pending = false;
  blocks.forEach((block, blockIndex) => {
    if (block.type === "PAGEBREAK") {
      pending = true;
      return;
    }
    planned.push({ blockIndex, block, rule: BLOCK_RULES[block.type], breakBefore: pending });
    pending = false;
  });
  return planned;
}

function rowBottoms(wrapper: HTMLElement, selector: string): number[] {
  const top = wrapper.getBoundingClientRect().top;
  return Array.from(wrapper.querySelectorAll(selector)).map(
    (element) => element.getBoundingClientRect().bottom - top
  );
}

function gridRowBottoms(wrapper: HTMLElement, selector: string): number[] {
  const top = wrapper.getBoundingClientRect().top;
  const rows = new Map<number, number>();
  for (const element of Array.from(wrapper.querySelectorAll(selector))) {
    const rect = element.getBoundingClientRect();
    const key = Math.round(rect.top - top);
    rows.set(key, Math.max(rows.get(key) ?? 0, rect.bottom - top));
  }
  return Array.from(rows.values());
}

function lineBottoms(wrapper: HTMLElement, selector: string): number[] {
  if (typeof document === "undefined") return [];
  const top = wrapper.getBoundingClientRect().top;
  const bottoms: number[] = [];
  const range = document.createRange();
  for (const element of Array.from(wrapper.querySelectorAll(selector))) {
    try {
      range.selectNodeContents(element);
      for (const rect of Array.from(range.getClientRects())) {
        if (rect.height > 0) bottoms.push(rect.bottom - top);
      }
    } catch {
    }
  }
  range.detach?.();
  return bottoms;
}

function breakOffsetsFor(rule: BlockRule, wrapper: HTMLElement): number[] {
  const offsets: number[] = [];
  if (rule.rows) offsets.push(...rowBottoms(wrapper, rule.rows));
  if (rule.gridRows) offsets.push(...gridRowBottoms(wrapper, rule.gridRows));
  if (rule.lines) offsets.push(...lineBottoms(wrapper, rule.lines));
  return offsets;
}

type Measurement = {
  blocks: PreviewBlock[];
  contentPx: number;
  coverContentPx: number;
  items: FlowItem[];
};

function useMeasuredFlow(
  blocks: PreviewBlock[],
  planned: PlannedBlock[],
  geometry: PageGeometry,
  figureModes: Record<string, boolean>
): { flowRef: RefObject<HTMLDivElement | null>; bodyProbeRef: RefObject<HTMLDivElement | null>; coverProbeRef: RefObject<HTMLDivElement | null>; measurement: Measurement | null } {
  const flowRef = useRef<HTMLDivElement | null>(null);
  const bodyProbeRef = useRef<HTMLDivElement | null>(null);
  const coverProbeRef = useRef<HTMLDivElement | null>(null);
  const [measurement, setMeasurement] = useState<Measurement | null>(null);

  useEffect(() => {
    const flow = flowRef.current;
    const bodyProbe = bodyProbeRef.current;
    const coverProbe = coverProbeRef.current;
    if (!flow || !bodyProbe || !coverProbe) return;

    let alive = true;
    let frame = 0;

    const read = () => {
      frame = 0;
      if (!alive) return;

      const entries = planned;
      const children = Array.from(flow.children) as HTMLElement[];
      if (children.length !== entries.length) return;

      const rects = children.map((child) => child.getBoundingClientRect());
      const items: FlowItem[] = entries.map((entry, index) => {
        const rect = rects[index];
        const previous = index > 0 ? rects[index - 1] : null;
        const rule = entry.rule;
        const header = rule.header
          ? rowBottoms(children[index], rule.header)[0] ?? 0
          : 0;
        return {
          blockIndex: entry.blockIndex,
          heightPx: rect.height,
          gapBeforePx: previous ? Math.max(0, rect.top - previous.bottom) : 0,
          breakBefore: entry.breakBefore,
          breakAfter: rule.breakAfter,
          keepWithNext: rule.keepWithNext ?? false,
          splittable: rule.splittable ?? false,
          breakOffsetsPx: rule.splittable ? breakOffsetsFor(rule, children[index]) : undefined,
          repeatHeaderPx: header > 0 ? header : undefined,
          preferWhole: rule.preferWhole,
          isCover: rule.isCover
        };
      });

      const next: Measurement = {
        blocks,
        contentPx: bodyProbe.getBoundingClientRect().height,
        coverContentPx: coverProbe.getBoundingClientRect().height,
        items
      };
      setMeasurement((current) => (sameMeasurement(current, next) ? current : next));
    };

    const schedule = () => {
      if (frame || !alive) return;
      frame = requestAnimationFrame(read);
    };

    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(schedule);
    if (observer) {
      observer.observe(flow);
      observer.observe(bodyProbe);
      observer.observe(coverProbe);
      for (const child of Array.from(flow.children)) observer.observe(child);
    }
    document.fonts?.ready.then(schedule).catch(() => undefined);
    schedule();

    return () => {
      alive = false;
      observer?.disconnect();
      if (frame) cancelAnimationFrame(frame);
    };
  }, [blocks, planned, figureModes, geometry.widthMm, geometry.heightMm, geometry.marginMm]);

  return { flowRef, bodyProbeRef, coverProbeRef, measurement };
}

function sameMeasurement(a: Measurement | null, b: Measurement): boolean {
  if (!a) return false;
  if (a.blocks !== b.blocks) return false;
  if (Math.abs(a.contentPx - b.contentPx) > 0.5) return false;
  if (Math.abs(a.coverContentPx - b.coverContentPx) > 0.5) return false;
  if (a.items.length !== b.items.length) return false;
  for (let i = 0; i < a.items.length; i += 1) {
    const one = a.items[i];
    const two = b.items[i];
    if (Math.abs(one.heightPx - two.heightPx) > 0.5) return false;
    if (Math.abs((one.gapBeforePx ?? 0) - (two.gapBeforePx ?? 0)) > 0.5) return false;
    if ((one.breakOffsetsPx?.length ?? 0) !== (two.breakOffsetsPx?.length ?? 0)) return false;
    if (Math.abs((one.repeatHeaderPx ?? 0) - (two.repeatHeaderPx ?? 0)) > 0.5) return false;
  }
  return true;
}

function useFitToWidth(pageWidthMm: number): {
  stageRef: RefObject<HTMLDivElement | null>;
  pagesRef: RefObject<HTMLDivElement | null>;
  fitScale: number;
  naturalHeight: number;
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
  const fitScale = available > 0 ? Math.min(1, available / pageWidthPx) : 1;

  return { stageRef, pagesRef, fitScale, naturalHeight };
}

const ZOOM_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2] as const;

export type ReportSheetsProps = {
  blocks: PreviewBlock[];
  pageSize: string;
  marginMm?: number | null;
  headerText: string;
  footerText: string;
  mediaUrls: Record<string, string | null>;
  palette?: ReportPalette;
  paletteChosen?: boolean;
  focusRange?: [number, number] | null;
  compact?: boolean;
  scrollPages?: boolean;
};

export const ReportSheets = memo(function ReportSheets({
  blocks,
  pageSize,
  marginMm,
  headerText,
  footerText,
  mediaUrls,
  palette = REPORT_PALETTE,
  paletteChosen = false,
  focusRange = null,
  compact = false,
  scrollPages = false
}: ReportSheetsProps) {
  const geometry = useMemo(() => pageGeometry(pageSize, marginMm), [pageSize, marginMm]);
  const css = useMemo(
    () => documentStyles(geometry.widthMm, geometry.heightMm, geometry.marginMm, geometry.cssPageSize, palette),
    [geometry, palette]
  );
  const planned = useMemo(() => planFlow(blocks), [blocks]);

  const [figureModes, setFigureModes] = useState<Record<string, boolean>>({});
  const setFigureMode = useCallback((key: string, value: boolean) => {
    setFigureModes((current) => (current[key] === value ? current : { ...current, [key]: value }));
  }, []);
  const modes: ReportFigureModes = useMemo(
    () => ({ modes: figureModes, setMode: setFigureMode }),
    [figureModes, setFigureMode]
  );

  const { flowRef, bodyProbeRef, coverProbeRef, measurement } = useMeasuredFlow(
    blocks,
    planned,
    geometry,
    figureModes
  );

  const packed: PackedDocument | null = useMemo(
    () =>
      measurement
        ? packPages(measurement.items, {
            contentPx: measurement.contentPx,
            coverContentPx: measurement.coverContentPx
          })
        : null,
    [measurement]
  );

  const { stageRef, pagesRef, fitScale, naturalHeight } = useFitToWidth(geometry.widthMm);
  const [zoom, setZoom] = useState<number | "fit">("fit");
  const scale = zoom === "fit" ? fitScale : zoom;
  const fittedHeight = naturalHeight ? naturalHeight * scale : undefined;

  const reduce = useAppReducedMotion();
  const reduceRef = useRef(reduce);
  useEffect(() => {
    reduceRef.current = reduce;
  });

  const anchor = useRef<number | null>(null);
  const rememberAnchor = () => {
    const stage = stageRef.current;
    if (!stage || typeof window === "undefined") return;
    const top = stage.getBoundingClientRect().top + window.scrollY;
    const height = stage.offsetHeight || 1;
    anchor.current = Math.min(1, Math.max(0, (window.scrollY - top) / height));
  };
  useLayoutEffect(() => {
    const ratio = anchor.current;
    anchor.current = null;
    const stage = stageRef.current;
    if (ratio === null || !stage || typeof window === "undefined") return;
    const top = stage.getBoundingClientRect().top + window.scrollY;
    window.scrollTo({
      top: top + ratio * (stage.offsetHeight || 0),
      behavior: reduceRef.current ? "auto" : "smooth"
    });
  }, [scale, stageRef]);

  const chooseZoom = (next: number | "fit") => {
    rememberAnchor();
    setZoom(next);
  };

  const stepZoom = (direction: 1 | -1) => {
    const current = scale;
    const next =
      direction > 0
        ? ZOOM_STEPS.find((step) => step > current + 0.001)
        : [...ZOOM_STEPS].reverse().find((step) => step < current - 0.001);
    if (next !== undefined) chooseZoom(next);
  };

  const sheets = useMemo(() => packed?.pages ?? [], [packed]);
  const shownBlocks = measurement?.blocks ?? blocks;

  const visibleSheets = useMemo(() => {
    if (!focusRange) return sheets;
    const [start, end] = focusRange;
    const hit = sheets.filter((sheet) => sheet.slices.some((slice) => slice.blockIndex >= start && slice.blockIndex < end));
    return hit.length ? hit : sheets;
  }, [sheets, focusRange]);

  const geometryValue = useMemo(
    () => ({
      textWidthMm: geometry.widthMm - 2 * geometry.marginMm,
      bodyHeightMm: geometry.heightMm - 2 * geometry.marginMm - HEAD_CLEARANCE_MM - FOOT_RESERVE_MM
    }),
    [geometry]
  );
  const drawGeometry = useMemo(() => ({ ...geometryValue, measuring: false }), [geometryValue]);
  const measureGeometry = useMemo(() => ({ ...geometryValue, measuring: true }), [geometryValue]);

  const bodyNodes = useRef<Array<HTMLDivElement | null>>([]);
  const [overflow, setOverflow] = useState<{ pages: number; worstPx: number } | null>(null);
  useEffect(() => {
    const nodes = bodyNodes.current.filter((node): node is HTMLDivElement => Boolean(node));
    let count = 0;
    let worst = 0;
    for (const node of nodes) {
      const over = node.scrollHeight - node.clientHeight;
      if (over > 1) {
        count += 1;
        worst = Math.max(worst, over);
      }
    }
    const next = count ? { pages: count, worstPx: Math.round(worst) } : null;
    setOverflow((current) => {
      if (!current && !next) return current;
      if (current && next && current.pages === next.pages && current.worstPx === next.worstPx) return current;
      return next;
    });
  }, [packed, scale]);

  const scaledPages = useMemo(() => {
    const seen = new Set<number>();
    for (const entry of packed?.scaled ?? []) seen.add(entry.pageNumber);
    return Array.from(seen).sort((a, b) => a - b);
  }, [packed]);

  const renderBlock = (blockIndex: number, from: PreviewBlock[] = shownBlocks) => (
    <ReportBlock block={from[blockIndex]} blockKey={String(blockIndex)} />
  );

  const renderSlice = (slice: PageSlice, key: number, isCover = false) => (
    <Fragment key={key}>
      {slice.repeatHeaderPx > 0 ? (
        <div className="rp-slice" style={{ height: slice.repeatHeaderPx, marginTop: slice.gapBeforePx }} aria-hidden>
          <div className="rp-block">{renderBlock(slice.blockIndex)}</div>
        </div>
      ) : null}
      <div
        className={isCover && slice.scale === 1 ? "rp-slice rp-slice-cover" : "rp-slice"}
        data-rp-focus={focusRange && slice.blockIndex === focusRange[0] && slice.part === 0 ? "start" : undefined}
        style={{
          height: isCover && slice.scale === 1 ? "100%" : slice.heightPx * slice.scale,
          marginTop: slice.repeatHeaderPx > 0 ? 0 : slice.gapBeforePx
        }}
      >
        <div
          className={slice.scale < 1 ? "rp-block rp-scaled" : "rp-block"}
          style={{
            marginTop: slice.offsetPx ? -slice.offsetPx : undefined,
            transform: slice.scale < 1 ? `scale(${slice.scale})` : undefined,
            width: slice.scale < 1 ? `${100 / slice.scale}%` : undefined
          }}
        >
          {renderBlock(slice.blockIndex)}
        </div>
      </div>
    </Fragment>
  );

  const renderFocusedSlices = (slices: PageSlice[], isCover: boolean) => {
    const [start, end] = focusRange ?? [0, 0];
    const runs: Array<{ kind: "before" | "in" | "after"; items: Array<[PageSlice, number]> }> = [];
    slices.forEach((slice, index) => {
      const kind = slice.blockIndex < start ? "before" : slice.blockIndex >= end ? "after" : "in";
      const last = runs[runs.length - 1];
      if (last && last.kind === kind) last.items.push([slice, index]);
      else runs.push({ kind, items: [[slice, index]] });
    });
    return runs.map((run, runIndex) =>
      run.kind === "in" ? (
        <Fragment key={runIndex}>{run.items.map(([slice, index]) => renderSlice(slice, index, isCover))}</Fragment>
      ) : (
        <div key={runIndex} className={`rp-context rp-context-${run.kind}`} data-rp-noprint>
          <span className="rp-context-label" aria-hidden>
            {run.kind === "before" ? "Continued from the previous stage" : "The next stage continues here"}
          </span>
          <div className="rp-context-body">{run.items.map(([slice, index]) => renderSlice(slice, index, isCover))}</div>
        </div>
      )
    );
  };

  const providers = (children: React.ReactNode, measuring = false) => (
    <ReportPaletteProvider palette={palette}>
      <MediaUrlProvider urls={mediaUrls}>
        <ReportGeometryProvider value={measuring ? measureGeometry : drawGeometry}>
          <ReportFigureModeProvider value={modes}>{children}</ReportFigureModeProvider>
        </ReportGeometryProvider>
      </MediaUrlProvider>
    </ReportPaletteProvider>
  );

  return (
    <div className={scrollPages ? "rp-doc lg:flex lg:min-h-0 lg:flex-1 lg:flex-col" : "rp-doc"}>
      <style>{css}</style>

      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-xs text-ink-500" data-rp-noprint>
        <span>
          {packed
            ? visibleSheets !== sheets && visibleSheets.length
              ? `${visibleSheets.length === 1 ? "Page" : "Pages"} ${visibleSheets[0].pageNumber}${
                  visibleSheets.length > 1 ? `–${visibleSheets[visibleSheets.length - 1].pageNumber}` : ""
                } of ${sheets.length}`
              : `${sheets.length} page${sheets.length === 1 ? "" : "s"}`
            : "Measuring the pages…"}{" "}
          ·{" "}
          {geometry.label}
          {geometry.marginAssumed
            ? ` · ${geometry.marginMm} mm margins assumed`
            : ` · ${geometry.marginMm} mm margins`}
        </span>
        <span className={compact ? "hidden" : undefined}>
          Page counts are close estimates; Word and the .pdf may break lines differently.
        </span>
        {!compact && (scale < 0.995 || scale > 1.005) ? (
          <span>
            Shown at {Math.round(scale * 100)}%.
          </span>
        ) : null}
        {scaledPages.length ? (
          <span>
            {packed?.scaled.length} block{packed?.scaled.length === 1 ? "" : "s"} taller than one page{" "}
            {packed?.scaled.length === 1 ? "is" : "are"} shown scaled down to fit (page
            {scaledPages.length === 1 ? " " : "s "}
            {scaledPages.join(", ")}); the file splits them across pages.
          </span>
        ) : null}
        {overflow ? (
          <span
            className="rounded-md border border-amber-500/40 bg-amber-100 px-2 py-0.5 text-amber-800"
          >
            {overflow.pages} page{overflow.pages === 1 ? "" : "s"} of content overruns the sheet by up
            to {overflow.worstPx} px and is clipped here; the file is complete.
          </span>
        ) : null}
        {packed?.abandoned.length ? (
          <span className="rounded-md border border-amber-500/40 bg-amber-100 px-2 py-0.5 text-amber-800">
            {packed.abandoned.length} block{packed.abandoned.length === 1 ? "" : "s"} could not be
            laid out and {packed.abandoned.length === 1 ? "is" : "are"} missing from the sheets below.
          </span>
        ) : null}
        <span className={compact ? "hidden" : undefined}>
          {paletteChosen
            ? "In your chosen accent colour."
            : "In the default palette."}
        </span>
      </div>

      <div className="mb-3 flex flex-wrap items-center gap-2" data-rp-noprint>
        <button
          type="button"
          className="rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60"
          onClick={() => stepZoom(-1)}
          disabled={scale <= ZOOM_STEPS[0] + 0.001}
        >
          Zoom out
        </button>
        <span className="min-w-14 text-center text-xs tabular-nums text-ink-500">
          {Math.round(scale * 100)}%
        </span>
        <button
          type="button"
          className="rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60"
          onClick={() => stepZoom(1)}
          disabled={scale >= ZOOM_STEPS[ZOOM_STEPS.length - 1] - 0.001}
        >
          Zoom in
        </button>
        <button
          type="button"
          aria-pressed={zoom === "fit"}
          className={
            zoom === "fit"
              ? "rounded-md border border-purple-300 bg-purple-50 px-2 py-1 text-xs font-medium text-purple-800 transition"
              : "rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
          }
          onClick={() => chooseZoom("fit")}
        >
          Fit to width
        </button>
        <button
          type="button"
          aria-pressed={zoom === 1}
          className={
            zoom === 1
              ? "rounded-md border border-purple-300 bg-purple-50 px-2 py-1 text-xs font-medium text-purple-800 transition"
              : "rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
          }
          onClick={() => chooseZoom(1)}
        >
          Actual size
        </button>
      </div>

      <div className="rp-measure-host" aria-hidden data-rp-noprint>
        <div className="rp-measure">
          <article className="rp-sheet">
            <div className="rp-runhead">
              <span>{headerText}</span>
            </div>
            <div className="rp-body" ref={bodyProbeRef} />
            <div className="rp-runfoot">
              <span>{footerText}</span>
              <span className="rp-pageno">
                Page 8<span className="rp-of"> of 88</span>
              </span>
            </div>
          </article>
          <article className="rp-sheet rp-sheet-cover">
            <div className="rp-body" ref={coverProbeRef} />
          </article>
          <div className="rp-measure-flow">
            <div className="rp-body" ref={flowRef}>
              {providers(
                planned.map((entry) => (
                  <div className={entry.rule.isCover ? "rp-block rp-block-cover" : "rp-block"} key={entry.blockIndex}>
                    {renderBlock(entry.blockIndex, blocks)}
                  </div>
                )),
                true
              )}
            </div>
          </div>
        </div>
      </div>

      <div
        ref={stageRef}
        className={[
          scale > fitScale + 0.001 ? "rp-stage rp-stage-pan" : "rp-stage",
          scrollPages ? "lg:min-h-0 lg:flex-1 lg:overflow-y-auto lg:overscroll-contain lg:rounded-sm lg:border lg:border-line-200 lg:bg-surface-50 lg:p-2" : ""
        ].join(" ")}
      >
        <div className="rp-fit" style={{ height: fittedHeight }}>
          <div
            className="rp-scaler"
            style={{
              transform: scale !== 1 ? `scale(${scale})` : undefined,
              transition: reduce ? "none" : "transform 220ms cubic-bezier(0.16, 1, 0.3, 1)"
            }}
          >
            <div ref={pagesRef} className="rp-pages">
              {providers(
                visibleSheets.map((sheet, index) => (
                  <Fragment key={sheet.pageNumber}>
                    {index > 0 ? (
                      <div className="rp-break" aria-hidden>
                        <span />
                        <span className="rp-break-label">Page break</span>
                        <span />
                      </div>
                    ) : null}
                    <article
                      className={sheet.isCover ? "rp-sheet rp-sheet-cover" : "rp-sheet"}
                      aria-label={sheet.isCover ? "Cover page" : `Page ${sheet.pageNumber} of ${sheets.length}`}
                    >
                      {sheet.isCover ? null : (
                        <div className="rp-runhead" aria-hidden>
                          <span>{headerText}</span>
                        </div>
                      )}

                      <div
                        className="rp-body"
                        ref={(node) => {
                          bodyNodes.current[index] = node;
                        }}
                      >
                        {focusRange
                          ? renderFocusedSlices(sheet.slices, sheet.isCover)
                          : sheet.slices.map((slice, sliceIndex) => renderSlice(slice, sliceIndex, sheet.isCover))}
                      </div>

                      {sheet.isCover ? null : (
                        <div className="rp-runfoot" aria-hidden>
                          <span>{footerText}</span>
                          <span className="rp-pageno">
                            Page {sheet.pageNumber}
                            <span className="rp-of"> of {sheets.length}</span>
                          </span>
                        </div>
                      )}
                    </article>
                  </Fragment>
                ))
              )}
            </div>
          </div>
        </div>
      </div>

      {packed ? null : (
        <p className="text-sm text-ink-500" data-rp-noprint>
          Laying the document out onto pages…
        </p>
      )}
    </div>
  );
});
