"use client";

/**
 * The preview as PAPER: A4 (or Letter) sheets at their real dimensions, with the blocks FLOWED
 * into them — a page ends where the paper runs out, and what does not fit starts the next one.
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
 * ── AND UNTIL THIS CHANGE IT STOPPED BEING A PAGE THE MOMENT REAL CONTENT ARRIVED ────────────
 *
 * The sheet was `min-height: var(--rp-page-h)`. A minimum is not a page: a sheet GREW past the
 * paper instead of ending at it, so one long stage rendered as a single "sheet" several times
 * taller than A4 — and the only breaks that existed at all were the ones the template DECLARED,
 * because `splitIntoSheets` never measured any text. The screen whose whole job is to answer "does
 * this fit on the page?" answered it by making the page bigger. So now:
 *
 *   1. every block is rendered ONCE into an off-screen container at exactly the page's content
 *      width, and its height, the gap above it and the offsets at which it may legally be cut are
 *      MEASURED (`useMeasuredFlow` below);
 *   2. `reportPagination.packPages` — pure, deterministic, no DOM, tested directly by
 *      `frontend/e2e/report-pagination-unit.spec.ts` — flows those measurements into fixed-height
 *      pages under the rules `backend/app/services/report_pdf.py` uses;
 *   3. each page is drawn as a FIXED-height sheet, and a block divided across pages is drawn as a
 *      window onto the same laid-out box rather than laid out a second time.
 *
 * THIS IS STILL NOT A FIFTH RENDERER, and that constraint governs everything here. `GET
 * /report/preview` returns the same `ReportDocument` that `report_docx.py`, `report_pdf.py` and
 * the two on-device Kotlin writers consume; this file LAYS OUT those blocks and never re-derives,
 * re-words or re-orders one. Where a splitting rule appears below it is cited to the writer it was
 * read from, because a preview that invented its own would be the only one of the five nobody ever
 * opens a file to check.
 *
 * WHY MILLIMETRES AND POINTS. Because it makes the browser's own Print to PDF work. A designer on
 * a laptop with no backend reachable — on a train, in a district office, at the end of a workshop
 * day — needs a presentable PDF, and Ctrl+P is the only path they have. A page laid out in `px`
 * and `rem` prints at whatever the browser's scale factor happens to be; a page laid out in `mm`
 * and `pt` prints at the size it says. 10.5 pt here is the same 10.5 pt `ReportTheme.base_size_pt`
 * declares, so the printed result lands within a line or two of the server's own .pdf rather than
 * being a screenshot of a web page. It is NOT the authority — the server's writers are — but it
 * is a real document rather than a picture of one. Now that a sheet is exactly one page, the print
 * rules keep it that way: one sheet, one physical page, rather than handing pagination back to the
 * browser at the moment of printing.
 *
 * WHY THE SHEET DOES NOT INVERT IN DARK MODE. Every other surface in this app goes through the
 * `ink-*` / `line-200` / `surface-50` ladders and flips under `data-theme="dark"`, and that rule
 * is not being broken lightly. A sheet is a depiction of a physical object: it is white because
 * the paper is white, and a designer approving how much ink a page carries cannot do that on a
 * dark rendering of it any more than they could approve a photograph shown in negative. Every
 * pixel outside the sheet — the chrome, the panels, the warnings, the page-break marks, the zoom
 * control — is themed as usual, so the only non-inverting region is the one standing in for paper.
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

import {
  Fragment,
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

/* ────────────────────────────────────────────────────────────────────────────
 * What the running furniture costs the text column: NOTHING, plus two clearances
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE FURNITURE IS DRAWN INTO THE MARGIN, NOT INTO THE TEXT COLUMN, and every file renderer of
 * this document says so in as many words. `report_pdf._draw_furniture` (report_pdf.py:730): "The
 * lines stack INTO THE MARGIN — upward for the head, downward for the foot — and never into the
 * text column, so nothing here moves ``self.y`` and pagination is untouched." `PdfWriter.kt`'s
 * `drawFurniture` is the same routine on the phone.
 *
 * What the column DOES lose is two fixed clearances, and they are the writer's numbers rather than
 * anything measurable here:
 *
 *   report_pdf.py:516   `self.bottom = self.margin + 10 * MM`   # room for the running foot
 *   report_pdf.py:635   `self.y = self.top - 6 * MM`            # clearance under the running head,
 *                                                               # on every page after the first
 *
 * So an ordinary page holds `text_h - 16 mm` and the cover — page one, which takes no head
 * clearance because `_new_page` applies it only when `self._page > 1` — holds `text_h - 10 mm`.
 *
 * THIS PREVIEW USED TO MEASURE ITS OWN FURNITURE AND SUBTRACT THAT INSTEAD, which is a reasonable
 * instinct and was wrong, because the furniture it was measuring sat IN the flow: `.rp-runhead`
 * and `.rp-runfoot` were flex children of the sheet, so their 7 mm collapse margins, 1.4 mm
 * padding, hairline rules and line of 7.8 pt type came out of the body — 24.8 mm of it against the
 * writer's 16 mm, by the declarations a few dozen lines below. An ordinary A4 page therefore held
 * 222 mm of content here against the file's 231 mm: 8.8 mm, a body line and a half, MISSING FROM
 * EVERY PAGE, which on a long report compounds into whole extra sheets that the .pdf does not have.
 * The cover had the mirror-image fault in the other direction, measured with no furniture at all
 * and so believed 10 mm TALLER than the file's.
 *
 * The furniture is now positioned into the two margin bands, out of flow, exactly where the writers
 * draw it, and the sheet spends the two clearances as padding of its own — so `.rp-body` is the
 * fillable column and nothing else. `DwReportSheets.kt` reads the
 * same two constants (`DW_HEAD_CLEARANCE_MM`, `DW_FOOT_RESERVE_MM`) and
 * `e2e/report-pagination-unit.spec.ts` pins both files against each other, so the phone and the
 * laptop cannot go back to paginating one document two ways.
 */
const HEAD_CLEARANCE_MM = 6;

/** See {@link HEAD_CLEARANCE_MM}. `report_pdf.py:516`, `PdfWriter.kt:197`. */
const FOOT_RESERVE_MM = 10;

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
.rp-runhead {
  position: absolute; left: 0; right: 0;
  bottom: calc(100% + var(--rp-head-clear) + 2.6mm);
  display: flex; justify-content: flex-end;
  font-size: 7.8pt; color: var(--rp-muted);
  border-bottom: 0.5pt solid var(--rp-rule);
  padding-bottom: 1.4mm;
  min-height: 4mm;
}
.rp-runfoot {
  position: absolute; left: 0; right: 0;
  top: calc(100% + var(--rp-foot-reserve));
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
   precisely so the closing "Generated on …" line cannot be pushed onto a second, blank page.
   The cover's slice is given the whole page height, so 'flex: 1 1 auto' still has something to
   grow into. */
.rp-cover { flex: 1 1 auto; display: flex; flex-direction: column; text-align: center; min-height: 100%; }
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

/* THE MEASURING SHELL. Laid out for real — a display:none element has no layout and would measure
   nothing — and parked out of the way rather than hidden, because 'visibility: hidden' and
   'content-visibility' both change what descendants do. Its host is a 0 x 0 clipping box, so the
   off-screen tree cannot lengthen the page or create a scrollable region of its own; clipping
   affects painting and never layout, which is the property being relied on. */
.rp-measure-host { position: absolute; top: 0; left: 0; width: 0; height: 0; overflow: clip; }
.rp-measure { position: absolute; top: 0; left: 0; }
.rp-measure-flow { width: var(--rp-text-w); }

.rp-print-only { display: none; }

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

/* ────────────────────────────────────────────────────────────────────────────
 * Where a block may be cut, and what must stay with it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The splitting rules, one per block type, each read off `report_pdf.py`.
 *
 * `Record<PreviewBlock["type"], …>` and not a lookup with a default: an unhandled block type must
 * make the COMPILER point at this table, the same arrangement `ReportBlock`'s exhaustive switch
 * relies on. A block type that fell through to a safe default would paginate as an indivisible
 * lump and nothing would say so.
 *
 * `rows` / `gridRows` / `lines` name where the legal cuts are, in the markup `ReportBlock` emits;
 * `header` names a table head that a continuation page redraws, which is what `place_row` does
 * after `_new_page()`.
 */
type BlockRule = {
  keepWithNext?: boolean;
  splittable?: boolean;
  /** Move it whole while it fits any page; divide only when it fits none. `_cut_row`'s rule. */
  preferWhole?: boolean;
  breakAfter?: boolean;
  isCover?: boolean;
  /** Cut after any of these elements' bottom edges. */
  rows?: string;
  /** Same, but the elements share rows and the row's tallest member decides the cut. */
  gridRows?: string;
  /** Cut at line-box bottoms inside these elements. */
  lines?: string;
  /** Redrawn above every continuation. */
  header?: string;
};

const BLOCK_RULES: Record<PreviewBlock["type"], BlockRule> = {
  // One page to itself and no running furniture, in the file and here: `_block_cover` ends with
  // `self._new_page()` and `_draw_furniture` is suppressed on page one.
  COVER: { isCover: true },
  // `_block_toc` ends with `_new_page()`. The preview's contents block is three lines — it says it
  // cannot know the page numbers rather than inventing them — so there is nothing in it to cut.
  TOC: { breakAfter: true },
  // `_block_heading` reserves its lead, its own lines, its trail AND one body line before drawing.
  HEADING: { keepWithNext: true },
  // `_draw_lines` calls `_ensure(line.height)` once per line, so a paragraph breaks between lines.
  PARAGRAPH: { splittable: true, lines: ":scope > p, :scope > blockquote" },
  // `_block_bullets` ensures per item. Only TOP-LEVEL items are cut points: a nested list belongs
  // to the item that owns it, and the .docx keeps them together through one numbering id.
  BULLETLIST: { splittable: true, rows: ":scope > .rp-ul > li, :scope > .rp-ol > li" },
  // Nothing sends RICHTEXT yet (see previewModel). Its children are the paragraphs and lists
  // `to_report_blocks` would have flattened into their own blocks, so they are the cut points.
  RICHTEXT: { splittable: true, rows: ":scope > .rp-richtext > *" },
  // `_simple_grid` lays one pair at a time and `_cut_row`s each of them.
  KEYVALUE: { splittable: true, rows: ":scope > .rp-kv > div" },
  // `place_row` breaks between rows and repeats the header over the continuation.
  TABLE: {
    splittable: true,
    rows: ":scope .rp-table > tbody > tr",
    header: ":scope .rp-table > thead"
  },
  // `_block_image_grid` walks `range(0, len(images), columns)` and `_ensure`s one row at a time,
  // locked, so a row of photographs is never split from its captions.
  IMAGEGRID: { splittable: true, gridRows: ":scope .rp-grid > *" },
  // One `_cut_row` over the whole flow: moved whole while it fits a page, cut only when it does
  // not. Line boxes are the cut points for that last case.
  CALLOUT: { splittable: true, preferWhole: true, lines: ":scope .rp-callout > p" },
  // `_draw_image` reserves the whole box and the .pdf caps the picture at a fraction of the text
  // column (0.62 for a photograph, 0.58 for a figure) so that it always fits — it scales rather
  // than splits, and so does the preview when one is taller than a page.
  IMAGE: {},
  MAP: {},
  CHART: {},
  // `_block_metrics` and `_block_signatures` both `_ensure` the whole height and draw inside
  // `_locked`: one visual unit, never divided.
  METRICROW: {},
  SIGNATURE: {},
  // A percentage of the text column with no `_ensure` of its own. Indivisible; it is a gap.
  SPACER: {},
  // Never rendered: folded into the following block's `breakBefore` by `planFlow`.
  PAGEBREAK: {}
};

type PlannedBlock = {
  /** Index into the caller's `blocks` array — what a slice carries back so the block can be drawn. */
  blockIndex: number;
  block: PreviewBlock;
  rule: BlockRule;
  breakBefore: boolean;
};

/**
 * The renderable blocks, with declared breaks folded onto the block that follows them.
 *
 * A `PAGEBREAK` draws nothing — it is an instruction, and both file writers honour it exactly — so
 * it is not a member of the flow; it becomes `breakBefore` on the next block, which is the form
 * the packer acts on and the form the unit test asserts.
 *
 * A document ending in a `PAGEBREAK` therefore shows no trailing blank sheet. The .pdf's
 * `_new_page()` would emit one; no template in `report_templates.TEMPLATES` ends with a break, and
 * a blank sheet at the end of a preview reads as a fault in the preview rather than as a page of
 * the document. Same judgement `splitIntoSheets` made, carried forward deliberately.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Measuring
 * ──────────────────────────────────────────────────────────────────────────── */

/** Bottom edges of a set of elements, in px from the wrapper's own top. */
function rowBottoms(wrapper: HTMLElement, selector: string): number[] {
  const top = wrapper.getBoundingClientRect().top;
  return Array.from(wrapper.querySelectorAll(selector)).map(
    (element) => element.getBoundingClientRect().bottom - top
  );
}

/**
 * Bottom edges of GRID rows: the tallest member of each row decides where the row ends.
 *
 * Taking each cell's own bottom would offer a cut through the middle of a row — a photograph on
 * one page and its neighbour's caption on the next, which is exactly what `_block_image_grid`
 * opens a locked region to prevent.
 */
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

/**
 * Bottom edges of every LINE BOX inside a set of elements.
 *
 * `Range.getClientRects()` is the only way to reach a line box from script — there is no element
 * for one — and it is what makes a paragraph break between lines the way `_draw_lines` does rather
 * than being moved whole. It can return more than one rect per line (one per inline fragment), so
 * near-equal bottoms are collapsed by `usableOffsets` in the packer.
 */
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
      // A block whose contents cannot be ranged simply offers no cut points and is moved whole,
      // which is the safe direction: a wrong cut point would slice a line of type in half.
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
  /**
   * The content box of an ordinary page: the text column less the writer's head clearance and foot
   * reserve, read off a probe sheet that carries the real running furniture.
   *
   * STILL MEASURED RATHER THAN COMPUTED, even though {@link HEAD_CLEARANCE_MM} and
   * {@link FOOT_RESERVE_MM} would give the same two numbers by arithmetic. The probe is what proves
   * the furniture is out of the flow: if anybody ever restyles `.rp-runhead` back into the sheet's
   * flex column, the probe body shrinks, this number follows it, and the packer keeps agreeing with
   * the box the browser is actually drawing. Arithmetic would keep agreeing with the file while the
   * screen quietly disagreed with both — which is the exact failure this pair of numbers just had.
   */
  contentPx: number;
  /** The cover's, off a probe with no head clearance — `_new_page` applies that only after page 1. */
  coverContentPx: number;
  items: FlowItem[];
};

/**
 * Render every block once, off screen, at exactly the page's content width — then measure it.
 *
 * WHAT FORCES A RE-MEASURE, AND WHY IT IS AN OBSERVER RATHER THAN A DEPENDENCY LIST. The obvious
 * triggers are in the dependency array: the page geometry (a different paper or margin is a
 * different text column), the blocks themselves, and the figure toggles that change a block's
 * height. The ones that are NOT knowable from props are what the observer is for — a media URL
 * resolving and turning a placeholder into a photograph, a web font swapping in, a theme flip
 * repainting the live map. Each of those changes a height without changing a prop, and a
 * ResizeObserver over the measured children catches all of them without this file having to
 * enumerate them. It measures at most once per frame.
 *
 * ZOOM IS NOT ONE OF THEM, DELIBERATELY. The zoom control is a `transform` on the drawn sheets and
 * the measuring shell sits OUTSIDE it, so a block is measured at the paper's own size whatever the
 * reader has the sheets scaled to. That is the point: pagination must not depend on how big the
 * page looks in a window, or a designer would proof one document at 50% and hand over another.
 *
 * NO REF GUARD, DELIBERATELY. `reactStrictMode` is on, so every mount runs setup → cleanup → setup;
 * an effect that claims a ref on start and releases it only on completion deadlocks 100% of the
 * time under that (`useEditDeepLink` shipped exactly that bug). There is nothing to claim here: the
 * observer and the frame handle are both created in setup and destroyed in cleanup, and the only
 * flag is the ordinary "do not set state after unmount" one.
 */
function useMeasuredFlow(
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
      // The measurer renders one wrapper per planned block, so a mismatch means the DOM this
      // effect is reading is not the DOM this render produced. Measuring it anyway would pin
      // heights to the wrong blocks; the next frame re-reads.
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
    // A face that swaps in after first paint re-wraps every line in the document.
    document.fonts?.ready.then(schedule).catch(() => undefined);
    schedule();

    return () => {
      alive = false;
      observer?.disconnect();
      if (frame) cancelAnimationFrame(frame);
    };
    // DEPENDED ON BY IDENTITY, WHICH IS SAFE HERE AND IS THE POINT. `planned` is memoised in the
    // caller against `blocks`, so its identity changes exactly when the payload does, and
    // `figureModes` is returned unchanged by its own setter when a toggle is set to what it already
    // was. So a parent re-render does not restart the observer and a new document does — which is
    // what a measuring pass has to key off. A flattened string key was the alternative and it would
    // have had to encode every block's CONTENT to be correct: two documents with the same block
    // types can have different row boundaries at the same total height, and nothing would fire.
  }, [planned, figureModes, geometry.widthMm, geometry.heightMm, geometry.marginMm]);

  return { flowRef, bodyProbeRef, coverProbeRef, measurement };
}

/** Cheap structural equality, so an observer that fires with nothing changed does not re-render. */
function sameMeasurement(a: Measurement | null, b: Measurement): boolean {
  if (!a) return false;
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

/* ────────────────────────────────────────────────────────────────────────────
 * Fitting the sheets into the window
 * ──────────────────────────────────────────────────────────────────────────── */

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
  // Never scaled UP by the FIT. A designer looking at a 140%-enlarged page would be judging a
  // photograph's sharpness at a magnification the paper will never be read at — but asking for
  // that magnification deliberately is a different matter, which is what the zoom control is for.
  const fitScale = available > 0 ? Math.min(1, available / pageWidthPx) : 1;

  return { stageRef, pagesRef, fitScale, naturalHeight };
}

/** The zoom steps, in the order the control walks them. 100% is the paper's own size. */
const ZOOM_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2] as const;

/* ────────────────────────────────────────────────────────────────────────────
 * The component
 * ──────────────────────────────────────────────────────────────────────────── */

export type ReportSheetsProps = {
  blocks: PreviewBlock[];
  pageSize: string;
  /**
   * `ReportMeta.margin_mm`, when the payload carries it.
   *
   * It does — `preview_report` sends `marginMm` beside `pageSize` as of 2026-08-28 — and it stays
   * optional here, because an older server, or a response cached before that deploy, carries
   * neither the key nor any hint that it is missing. Absent, `previewModel.pageGeometry` falls back
   * to the dataclass's own default of 25 and raises `marginAssumed`, which the strip above the
   * sheets prints: the margin decides the text column, the text column decides where every line
   * wraps, and a preview paginating against the wrong one would be confidently wrong.
   */
  marginMm?: number | null;
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

export function ReportSheets({
  blocks,
  pageSize,
  marginMm,
  headerText,
  footerText,
  mediaUrls,
  palette = REPORT_PALETTE,
  paletteChosen = false
}: ReportSheetsProps) {
  const geometry = useMemo(() => pageGeometry(pageSize, marginMm), [pageSize, marginMm]);
  const css = useMemo(
    () => documentStyles(geometry.widthMm, geometry.heightMm, geometry.marginMm, geometry.cssPageSize, palette),
    [geometry, palette]
  );
  const planned = useMemo(() => planFlow(blocks), [blocks]);

  /**
   * The one piece of state inside a block that changes its HEIGHT: the map figure's
   * live-map / printed-figure toggle.
   *
   * It is lifted here so that the measured copy and the drawn copy of a block cannot disagree.
   * Left inside `ReportMapFigure`, a designer switching to the printed figure would grow a sheet
   * the packer had already sized for the live map, and the growth would be CLIPPED by the fixed
   * page — a silent truncation, and on the one screen whose job is to show what is on the page.
   */
  const [figureModes, setFigureModes] = useState<Record<string, boolean>>({});
  const setFigureMode = useCallback((key: string, value: boolean) => {
    setFigureModes((current) => (current[key] === value ? current : { ...current, [key]: value }));
  }, []);
  const modes: ReportFigureModes = useMemo(
    () => ({ modes: figureModes, setMode: setFigureMode }),
    [figureModes, setFigureMode]
  );

  const { flowRef, bodyProbeRef, coverProbeRef, measurement } = useMeasuredFlow(
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

  // Read through a ref, never a dependency: `useAppReducedMotion` is false on the server and on the
  // first client render and flips once ThemeProvider has read storage, so as a dependency it would
  // tear a live effect down in production for exactly the readers who asked for less motion.
  const reduce = useAppReducedMotion();
  const reduceRef = useRef(reduce);
  useEffect(() => {
    reduceRef.current = reduce;
  });

  /**
   * Keep the reader's place across a zoom change.
   *
   * Changing the scale changes the whole stack's height, so without this the page a designer was
   * reading slides out from under them by however much the sheets above it grew. The fraction of
   * the stage that was above the viewport is preserved. The scroll itself is motion, so it takes
   * the JS branch of the reduced-motion preference as well as the CSS one — the global rules in
   * `globals.css` reach `scroll-behavior` in a stylesheet and cannot reach a `scrollTo` option.
   */
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

  // Named `sheets`/`sheet` and not `pages`/`page` because the running foot's label is pinned as
  // SOURCE TEXT from two directions — `e2e/report-page-label-unit.spec.ts` here and
  // `backend/tests/test_report_parity.py` from the other side of the repository — so that a page
  // label fixed on one of the five renderers and not the others is caught by a read rather than by
  // a ministry officer holding two documents. Renaming the binding would quietly unpin both.
  const sheets = packed?.pages ?? [];

  /**
   * The drawn pages, checked against the arithmetic that produced them.
   *
   * The packer decides what fits; this reads back what actually landed. They should never disagree
   * — the same DOM was measured at the same width — but "should never" is how a silent clip gets
   * shipped, and the body is `overflow: hidden`, so a disagreement would hide content rather than
   * showing it. One sentence in the strip is the whole of the safety net.
   */
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

  const renderBlock = (blockIndex: number) => (
    <ReportBlock block={blocks[blockIndex]} blockKey={String(blockIndex)} />
  );

  const renderSlice = (slice: PageSlice, key: number, isCover = false) => (
    <Fragment key={key}>
      {/* A table's header, redrawn above the continuation exactly as `place_row` redraws it — the
          whole table, clipped to the head's own height, so the columns resolve to the identical
          widths rather than to a second table's guess at them. */}
      {slice.repeatHeaderPx > 0 ? (
        <div className="rp-slice" style={{ height: slice.repeatHeaderPx, marginTop: slice.gapBeforePx }} aria-hidden>
          <div className="rp-block">{renderBlock(slice.blockIndex)}</div>
        </div>
      ) : null}
      <div
        /* THE COVER'S SLICE IS GIVEN THE WHOLE PAGE, and it is the one place a slice is not its
           measured height. `_block_cover` lays the cover out top to bottom "with the hero
           photograph absorbing whatever is left over" and its closing "Generated on …" line at the
           foot; on screen that foot is `margin-top: auto` inside a flex column, which needs a
           definite height above it to push against. Measured height, and the line simply follows
           the info table halfway up an otherwise empty page. Nothing inside the cover grows to
           fill — the hero is capped at 62 mm — so this only moves the closing line, and only when
           the cover was not scaled down to fit in the first place. */
        className={isCover && slice.scale === 1 ? "rp-slice rp-slice-cover" : "rp-slice"}
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

  const providers = (children: React.ReactNode) => (
    <ReportPaletteProvider palette={palette}>
      <MediaUrlProvider urls={mediaUrls}>
        <ReportFigureModeProvider value={modes}>{children}</ReportFigureModeProvider>
      </MediaUrlProvider>
    </ReportPaletteProvider>
  );

  return (
    <div className="rp-doc">
      <style>{css}</style>

      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-xs text-ink-500" data-rp-noprint>
        <span>
          {packed ? `${sheets.length} page${sheets.length === 1 ? "" : "s"}` : "Measuring the pages…"} ·{" "}
          {geometry.label}
          {/* The margin decides the text column and the text column decides every line break, so an
              assumed one is an assumption about where the pages fall. Said here rather than left in
              a code comment, because the reader is the person it would mislead. */}
          {geometry.marginAssumed
            ? ` · ${geometry.marginMm} mm margins assumed (the preview payload does not carry the margin)`
            : ` · ${geometry.marginMm} mm margins`}
        </span>
        {/* ─ WHAT THE PAGE COUNT IS WORTH, NOW THAT IT IS A REAL ONE ─────────────────────────
            It used to say the count was a FLOOR, because only the breaks the template declared
            were honoured and everything else ran on. The blocks are measured now and the pages
            below are real pages — but they are measured with THIS BROWSER's fonts, while the .pdf
            is laid out by ReportLab in whichever face it resolved and the .docx is paginated by
            Word when the file is opened. Those disagree about where a line wraps, and a document
            that disagrees about a line can disagree about a page. Making the preview trustworthy
            raises the cost of overclaiming rather than removing it, so the sentence gets more
            precise rather than going away. */}
        <span>
          Pages are measured in this browser, so a line that wraps differently in Word or in the
          generated .pdf can move a break: treat the &ldquo;of {sheets.length}&rdquo; in each running foot below as a
          close estimate of the file&rsquo;s own count rather than as the file&rsquo;s own count.
        </span>
        {scale < 0.995 || scale > 1.005 ? (
          <span>
            Shown at {Math.round(scale * 100)}% of the paper&rsquo;s size; printing uses full size.
          </span>
        ) : null}
        {/* Never silent about a block the preview had to shrink. The .pdf caps a photograph at 0.62
            of the text column and a chart at 0.58 so that neither can be too tall; anything else
            that overruns a whole page is CUT by the file and scaled by the preview, and a designer
            approving a figure has to know which of those they are looking at. */}
        {scaledPages.length ? (
          <span>
            {packed?.scaled.length} block{packed?.scaled.length === 1 ? "" : "s"} taller than one page{" "}
            {packed?.scaled.length === 1 ? "is" : "are"} shown scaled down to fit (page
            {scaledPages.length === 1 ? " " : "s "}
            {scaledPages.join(", ")}) — nothing is cut off here, and the file divides such a block
            across pages instead.
          </span>
        ) : null}
        {overflow ? (
          <span
            /* A tinted chip, not bare coloured text. amber-800 is a LITERAL hex and does not invert,
               so on the dark canvas it would sit at about a third of the contrast it has on the
               light one; over the amber-100 fill — also literal — it reads the same in both themes.
               That pairing is the house rule for an amber notice and this is the one place on the
               strip that carries a warning rather than a fact. */
            className="rounded-md border border-amber-500/40 bg-amber-100 px-2 py-0.5 text-amber-800"
          >
            {overflow.pages} page{overflow.pages === 1 ? "" : "s"} of content overruns the sheet by up
            to {overflow.worstPx} px and is clipped below. This is a fault in the preview&rsquo;s
            measurement, not in the document — open the generated file to see the section in full.
          </span>
        ) : null}
        {packed?.abandoned.length ? (
          <span className="rounded-md border border-amber-500/40 bg-amber-100 px-2 py-0.5 text-amber-800">
            {packed.abandoned.length} block{packed.abandoned.length === 1 ? "" : "s"} could not be
            laid out and {packed.abandoned.length === 1 ? "is" : "are"} missing from the sheets below.
          </span>
        ) : null}
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

      {/* The zoom control. Real buttons in DOM order, themed like every other piece of chrome —
          nothing here is paper. `aria-pressed` on Fit because it is a mode, not an action. */}
      <div className="mb-3 flex flex-wrap items-center gap-2" data-rp-noprint>
        <button
          type="button"
          className="rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60"
          onClick={() => stepZoom(-1)}
          disabled={scale <= ZOOM_STEPS[0] + 0.001}
        >
          Zoom out
        </button>
        <span className="min-w-[3.5rem] text-center text-xs tabular-nums text-ink-500">
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

      {/* THE MEASURING SHELL. Two probe sheets give the content box of an ordinary page and of the
          cover — measured rather than derived, so whatever the running furniture actually costs is
          what the packer is told — and the flow below is every block laid out once at exactly the
          page's content width. */}
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
          {/* The cover carries no furniture in either writer — but it does still carry the foot
              reserve, because `self.bottom` is `margin + 10 mm` on every page including page one;
              what page one skips is the 6 mm head clearance, which `_new_page` applies only when
              `self._page > 1`. Measured off its own probe rather than subtracted, for the same
              reason as above. */}
          <article className="rp-sheet rp-sheet-cover">
            <div className="rp-body" ref={coverProbeRef} />
          </article>
          <div className="rp-measure-flow">
            <div className="rp-body" ref={flowRef}>
              {providers(
                planned.map((entry) => (
                  <div className="rp-block" key={entry.blockIndex}>
                    {renderBlock(entry.blockIndex)}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>

      <div ref={stageRef} className={scale > fitScale + 0.001 ? "rp-stage rp-stage-pan" : "rp-stage"}>
        <div className="rp-fit" style={{ height: fittedHeight }}>
          <div
            className="rp-scaler"
            style={{
              transform: scale !== 1 ? `scale(${scale})` : undefined,
              // CSS covers the CSS half of reduced motion (globals.css zeroes every transition
              // duration), and this covers the JS half: the branch is explicit so a reader can see
              // that the preference was honoured rather than inferred from a stylesheet.
              transition: reduce ? "none" : "transform 220ms cubic-bezier(0.16, 1, 0.3, 1)"
            }}
          >
            <div ref={pagesRef} className="rp-pages">
              {providers(
                sheets.map((sheet, index) => (
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
                      {/* The cover carries no running furniture, in the file and here: both
                          writers suppress it on page one, and a craft name printed above a
                          ministry cover would be the first thing an officer saw. */}
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
                        {sheet.slices.map((slice, sliceIndex) => renderSlice(slice, sliceIndex, sheet.isCover))}
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
                              page. The M here is measured, in this browser, with this browser's
                              fonts — see `previewModel` for why that is close to the file's count
                              and not identical to it, and the strip above the sheets for where
                              that is said to the reader.

                              THE "of M" IS ITS OWN ELEMENT SO THAT PRINT CAN DROP IT. The strip
                              that qualifies the number is chrome and does not print, and a
                              total with nothing left to qualify it — in the one artefact a
                              designer emails — is the failure that sentence exists to prevent.
                              See the `.rp-of` rule in the print block above. */}
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

      {/* `items === null` versus `items === []`, in the shape this screen needs it: nothing is
          drawn until the blocks have been measured, because sheets drawn from an unmeasured flow
          would paginate once at the wrong place and then jump. */}
      {packed ? null : (
        <p className="text-sm text-ink-500" data-rp-noprint>
          Laying the document out onto pages…
        </p>
      )}
    </div>
  );
}
