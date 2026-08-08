"use client";

/**
 * The report's infographics, drawn LIVE as SVG — the five forms `ChartBlock` can take.
 *
 * WHY THE BROWSER DRAWS THEM AT ALL, when the file writers rasterise. `report_chart.py` turns one
 * `ChartBlock` into one PNG and hands it to the picture path the .docx writer, the server .pdf
 * writer and the two on-device Kotlin writers already have — one implementation of the figure
 * instead of four, which is why four renderers cannot disagree about the same number. None of that
 * reasoning reaches this screen. A preview exists so a designer can change a cost head and see the
 * bar move; asking the server to re-rasterise a PNG between one keystroke and the next is a round
 * trip per edit on the metered rural connection this whole feature is written for, and a preview
 * that lags a second behind the data is a preview nobody trusts. So the browser draws it, from the
 * SAME `series` the rasteriser is handed, with the same axis, the same rounding, the same slice
 * order and the same colour ramp.
 *
 * WHICH ONE IS THE AUTHORITY. The PNG is — it is what the ministry receives. This drawing is a
 * second drawing of one dataset and a second drawing can diverge, so everything below is a
 * deliberate mirror of `report_chart.py` rather than a fresh design:
 *
 *   * `formatNumber` groups 12,34,567 the Indian way and drops trailing zeros, because the figure
 *     is read beside a cost table that prints the full rupee amount and an officer comparing the
 *     two must not have to convert between them to see they agree.
 *   * `axisBounds` ALWAYS INCLUDES ZERO. A bar chart whose axis starts at 40 makes a 3% difference
 *     look like a threefold one, which is the commonest way a government figure misleads without a
 *     single wrong number in it.
 *   * `niceStep` steps by 1, 2, 2.5 or 5 times a power of ten. An axis stepping by 7 is legible and
 *     useless — nobody reads the third gridline as 21.
 *   * `sliceColours` is a MONOCHROME RAMP, not a categorical palette, and the reason is the
 *     photocopier: every report this app generates is printed, copied and filed at least once, and
 *     a hue-based palette collapses to four indistinguishable greys the first time that happens.
 *   * A negative value cannot be a slice, so the circular kinds drop negatives and NAME them under
 *     the figure. The bar and line kinds keep them and give the axis a baseline, because "adoption
 *     at twelve months was worse than at six" is exactly the finding that figure exists to show.
 *   * A zero total does not divide. Every proportion is taken only after the total is known to be
 *     positive; a circular chart with nothing in it draws an empty ring and says so.
 *
 * NO CHARTING LIBRARY, and this is not a bundle-size argument — it is the same argument the server
 * makes. A library's defaults are its own: its own axis rounding, its own palette, its own idea of
 * where a label goes. Every one of those would be a way for this drawing to disagree with the
 * printed one, and the disagreements would be invisible until somebody laid the screen beside the
 * page. Eighty lines of arithmetic that copy a file in this repository cannot drift the way a
 * dependency's release notes can.
 *
 * WHAT IS DRAWN AND WHAT IS NOT. Axis numbers, category names and value labels are in the picture,
 * because they only mean anything where they are placed. The block's TITLE, UNIT and CAPTION are
 * drawn by `ReportBlock` as real text for the same reason the writers do it that way: the
 * rasteriser's five-by-seven bitmap face cannot draw an Indic script and drops what it cannot
 * draw, so a title baked into the picture would silently lose a craft's local name.
 *
 * THE WHOLE `<svg>` IS `aria-hidden`. It contains nothing focusable and is announced to nobody —
 * the same decision `IndiaMap` makes, and for the same reason: every value in it is printed again
 * as a real table by `ReportBlock`, which is the better of the two interfaces for a screen-reader
 * user rather than a poorer one.
 */

import { createContext, useContext, useMemo, type ReactNode } from "react";

import { REPORT_PALETTE, type DwChartBlock, type DwChartKind } from "@/components/designworkshop/report/previewModel";
import type { ReportPalette } from "@/lib/reportTheme";

/* ────────────────────────────────────────────────────────────────────────────
 * Numbers, as a figure prints them
 * ──────────────────────────────────────────────────────────────────────────── */

/** 12,34,567 rather than 1,234,567 — `_group_indian`, digit for digit. */
function groupIndian(digits: string): string {
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

/**
 * A value as a figure prints it — the counterpart of `report_chart.format_number`.
 *
 * Deliberately NOT the `4.2 L` / `1.3 Cr` abbreviation a dashboard would use. `toLocaleString`
 * with `en-IN` is not used either, and that is not an oversight: it would be one more thing that
 * could differ between two browsers and between a browser and Python, on a figure whose only job
 * is to agree with the printed one. The arithmetic is copied instead.
 */
export function formatNumber(value: number): string {
  if (!Number.isFinite(value)) return "-";
  const sign = value < 0 ? "-" : "";
  const magnitude = Math.abs(value);
  if (magnitude >= 1000 || magnitude === Math.trunc(magnitude)) {
    return sign + groupIndian(magnitude.toFixed(0));
  }
  return sign + magnitude.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}

/** A round axis step covering `span` in roughly `targetTicks` steps — `_nice_step`. */
function niceStep(span: number, targetTicks: number): number {
  if (span <= 0 || targetTicks <= 0) return 1;
  const raw = span / targetTicks;
  const power = raw > 0 ? 10 ** Math.floor(Math.log10(raw)) : 1;
  for (const multiplier of [1, 2, 2.5, 5, 10]) {
    if (raw <= multiplier * power) return multiplier * power;
  }
  return 10 * power;
}

/**
 * `[low, high, step]` for a value axis that includes zero and steps by a round number.
 *
 * The all-zero case returns 0 to 1 rather than 0 to 0: a flat axis draws a baseline and nothing
 * above it, which is the truthful picture of a cost sheet nobody has filled in yet, and it keeps
 * every division below off zero.
 */
function axisBounds(values: number[]): [number, number, number] {
  const high0 = Math.max(...values, 0);
  const low0 = Math.min(...values, 0);
  if (high0 === low0) return [0, 1, 1];
  const step = niceStep(high0 - low0, 4);
  let high = Math.ceil(high0 / step) * step;
  const low = Math.floor(low0 / step) * step;
  if (high === low) high = low + step;
  return [low, high, step];
}

/* ────────────────────────────────────────────────────────────────────────────
 * Colour
 * ──────────────────────────────────────────────────────────────────────────── */

type Rgb = [number, number, number];

function rgbOf(hex: string): Rgb {
  const value = hex.replace("#", "");
  return [
    parseInt(value.slice(0, 2), 16),
    parseInt(value.slice(2, 4), 16),
    parseInt(value.slice(4, 6), 16)
  ];
}

function mix(a: Rgb, b: Rgb, amount: number): Rgb {
  const t = Math.max(0, Math.min(1, amount));
  return [
    Math.round(a[0] + (b[0] - a[0]) * t),
    Math.round(a[1] + (b[1] - a[1]) * t),
    Math.round(a[2] + (b[2] - a[2]) * t)
  ];
}

function css(colour: Rgb): string {
  return `rgb(${colour[0]} ${colour[1]} ${colour[2]})`;
}

const WHITE: Rgb = [255, 255, 255];

/**
 * The palette every figure on this page is drawn in.
 *
 * A CONTEXT AND NOT A MODULE CONSTANT, because the accent is now a choice a designer makes on the
 * screen and the figures have to follow it in the same frame the sheet does. These constants used
 * to be computed once at module load from the DCH indigo, which was correct for exactly as long as
 * there was only one possible colour; the moment the picker shipped, a maroon report would have
 * been previewed with maroon headings, maroon rules, maroon table headers — and navy bars. That is
 * worse than not offering the choice, because it looks like the file will come out that way.
 *
 * The default is {@link REPORT_PALETTE}, so a figure rendered outside a provider is drawn in the
 * indigo it always was rather than throwing or coming out black.
 */
const PaletteContext = createContext<ReportPalette>(REPORT_PALETTE);

export function ReportPaletteProvider({ palette, children }: { palette: ReportPalette; children: ReactNode }) {
  return <PaletteContext.Provider value={palette}>{children}</PaletteContext.Provider>;
}

export function useReportPalette(): ReportPalette {
  return useContext(PaletteContext);
}

/** The bar fill for a value — negatives darkened, exactly as `_draw_cartesian` darkens them. */
function barFill(palette: ReportPalette, value: number): string {
  const soft = rgbOf(palette.accentSoft);
  return css(value >= 0 ? soft : mix(soft, rgbOf(palette.ink), 0.35));
}

/**
 * One step of the ramp per slice, through `accent_soft` at the midpoint — `_slice_colours`.
 *
 * Through the soft accent rather than straight from the accent to white, so the ramp uses BOTH
 * colours the derivation produced instead of fading one of them out.
 */
function sliceColours(palette: ReportPalette, count: number): string[] {
  const accent = rgbOf(palette.accent);
  const soft = rgbOf(palette.accentSoft);
  if (count <= 1) return [css(accent)];
  const out: string[] = [];
  for (let index = 0; index < count; index += 1) {
    const position = index / (count - 1);
    out.push(
      css(position <= 0.5 ? mix(accent, soft, position * 2) : mix(soft, WHITE, (position - 0.5) * 1.55))
    );
  }
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Text, measured the only way an SVG can measure it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Roughly how wide a string is, in user units, at `size`.
 *
 * AN ESTIMATE, AND KNOWINGLY SO. Real measurement needs `getComputedTextLength`, which needs the
 * element to be in the document — so a layout pass, a re-render, and a first frame drawn with the
 * wrong gutter. `report_chart` has the opposite problem and solves it exactly: its face is a fixed
 * five-by-seven bitmap, so `text_width` is a multiplication. 0.55 em is the average advance of the
 * UI sans face over Latin digits and lower case, which is what an axis and a category strip are
 * made of; the consequence of being 10% out is a gutter 10% too wide, not a broken figure.
 */
function textWidth(text: string, size: number): number {
  return text.length * size * 0.55;
}

/**
 * `text` shortened with an ellipsis until it fits — the counterpart of `report_raster.ellipsise`.
 *
 * Ellipsising rather than rotating, which is the same trade the rasteriser makes and for a reason
 * that survives the port: a rotated category strip is unreadable at figure size on paper, and the
 * figure's caption names every category in full anyway.
 */
function ellipsise(text: string, maxWidth: number, size: number): string {
  if (maxWidth <= 0) return "";
  if (textWidth(text, size) <= maxWidth) return text;
  let cut = text.length;
  while (cut > 0 && textWidth(`${text.slice(0, cut)}…`, size) > maxWidth) cut -= 1;
  return cut > 0 ? `${text.slice(0, cut)}…` : "";
}

/* ────────────────────────────────────────────────────────────────────────────
 * Geometry
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The figure's user-space width. Every size below is in these units and the `<svg>` scales the
 * whole thing to whatever `width_pct` of the text column comes to, so a figure at 40% of the
 * column and one at 100% are the same drawing at two sizes rather than two different drawings.
 */
const VIEW_WIDTH = 900;

/**
 * Height as a fraction of width for every kind whose height does not depend on the row count.
 *
 * `report_chart._ASPECT`, to the digit. Close to the golden ratio: tall enough that a small
 * difference between two bars is visible, short enough that a figure and the paragraph
 * introducing it fit on one page together.
 */
const ASPECT = 0.62;

/** Body text inside a figure. Larger than the rasteriser's bitmap face — see the note below. */
const LABEL_SIZE = 19;
const VALUE_SIZE = 19;
const HEADLINE_SIZE = 40;

type Series = Array<[string, number]>;

/** The series with unusable entries removed, plus the note naming what went — `_clean_series`. */
function cleanSeries(series: Series, circular: boolean): { kept: Series; dropped: string[] } {
  const kept: Series = [];
  const dropped: string[] = [];
  for (const [label, raw] of series) {
    const value = typeof raw === "number" ? raw : Number(raw);
    if (!Number.isFinite(value) || (circular && value < 0)) {
      dropped.push(label);
      continue;
    }
    kept.push([label, value]);
  }
  return { kept, dropped };
}

function isCircular(kind: DwChartKind): boolean {
  return kind === "PIE" || kind === "DONUT";
}

/**
 * The pitch of one row of a horizontal bar chart, and therefore the figure's height.
 *
 * NOT COPIED FROM THE RASTERISER, and this is the one measurement that deliberately differs. Its
 * rows are 26 units apart because its face is a five-by-seven bitmap; this drawing sets its type
 * at a size a person reads off a screen, and six cost heads at 26 units apart would have their
 * names touching. The figure is therefore slightly taller here than the PNG is — the ONE way in
 * which the live figure and the printed one are knowingly not the same picture. Everything that
 * carries meaning — which bar is longest, by how much, what every value is — is identical.
 */
const ROW_PITCH = 34;

function horizontalHeight(rows: number): number {
  return Math.max(ROW_PITCH * 3, ROW_PITCH * Math.max(1, rows) + 26);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Vertical bars and the line — one axis, one grid, one category strip
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The two cartesian kinds, which differ only in what is drawn between the axis and the values.
 *
 * THE CATEGORY NAMES SIT UNDER THE PLOT AND ARE TRUNCATED THERE. That is a real limitation of this
 * form and the reason `HORIZONTAL_BAR` exists: a vertical bar chart of six cost heads truncates
 * every one of them to "Mate…", so a template that has words rather than a sequence on the
 * category axis should be asking for the horizontal kind. Truncating is still better than
 * overlapping, and the values table `ReportBlock` prints beneath names every category in full.
 */
function Cartesian({ series, line }: { series: Series; line: boolean }) {
  const palette = useReportPalette();
  const height = VIEW_WIDTH * ASPECT;
  const values = series.map(([, value]) => value);
  const [low, high, step] = axisBounds(values);
  const span = high - low;

  const ticks: number[] = [];
  for (let tick = low; tick <= high + step * 0.001; tick += step) ticks.push(tick);

  const gutter = Math.max(...ticks.map((tick) => textWidth(formatNumber(tick), LABEL_SIZE))) + 10;
  const left = gutter;
  const right = VIEW_WIDTH - 12;
  const top = 16 + VALUE_SIZE;
  const bottom = height - 12 - LABEL_SIZE * 1.6;
  const yOf = (value: number) => bottom - ((value - low) / span) * (bottom - top);
  const zeroY = yOf(0);

  const slot = (right - left) / series.length;
  const barWidth = slot * 0.62;

  return (
    <>
      {/* The grid first, so a bar sits on top of its own gridlines rather than being cut by them.
          The zero rule is drawn at full strength: it is the baseline every bar is measured from,
          and on a chart with negative values it is the only thing that says which way is down. */}
      {ticks.map((tick) => (
        <g key={tick}>
          <line
            x1={left}
            y1={yOf(tick)}
            x2={right}
            y2={yOf(tick)}
            stroke={palette.rule}
            strokeWidth={Math.abs(tick) < 1e-9 ? 1.6 : 1}
            opacity={Math.abs(tick) < 1e-9 ? 1 : 0.55}
          />
          <text
            x={left - 6}
            y={yOf(tick)}
            textAnchor="end"
            dominantBaseline="central"
            fontSize={LABEL_SIZE}
            fill={palette.muted}
          >
            {formatNumber(tick)}
          </text>
        </g>
      ))}

      {line ? (
        <>
          {series.length >= 2 ? (
            <polyline
              points={series.map(([, value], index) => `${left + slot * (index + 0.5)},${yOf(value)}`).join(" ")}
              fill="none"
              stroke={palette.accent}
              strokeWidth={3}
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          ) : null}
          {series.map(([label, value], index) => {
            const x = left + slot * (index + 0.5);
            const y = yOf(value);
            return (
              <g key={`${index}-${label}`}>
                {/* A white disc under the mark so the line does not read through it — the same
                    two-disc mark the rasteriser draws, for the same reason. */}
                <circle cx={x} cy={y} r={7} fill={palette.paper} />
                <circle cx={x} cy={y} r={4.5} fill={palette.accent} />
                <text
                  x={x}
                  y={y - 12}
                  textAnchor="middle"
                  fontSize={VALUE_SIZE}
                  fill={palette.ink}
                >
                  {formatNumber(value)}
                </text>
              </g>
            );
          })}
        </>
      ) : (
        series.map(([label, value], index) => {
          const x = left + slot * index + (slot - barWidth) / 2;
          const y = yOf(value);
          let barTop = value >= 0 ? y : zeroY;
          let barHeight = value >= 0 ? zeroY - y : y - zeroY;
          if (barHeight < 1.5) {
            // A value of exactly zero still gets a visible stub, so the category is not mistaken
            // for one the data omitted entirely — the distinction between "nil" and "not asked".
            barTop = zeroY - 1.5;
            barHeight = 1.5;
          }
          return (
            <g key={`${index}-${label}`}>
              <rect x={x} y={barTop} width={barWidth} height={barHeight} fill={barFill(palette, value)} />
              <text
                x={x + barWidth / 2}
                y={value >= 0 ? barTop - 6 : barTop + barHeight + VALUE_SIZE}
                textAnchor="middle"
                fontSize={VALUE_SIZE}
                fill={palette.ink}
              >
                {formatNumber(value)}
              </text>
            </g>
          );
        })
      )}

      {series.map(([label], index) => {
        const text = ellipsise(label, slot - 6, LABEL_SIZE);
        if (!text) return null;
        return (
          <text
            key={`cat-${index}-${label}`}
            x={left + slot * (index + 0.5)}
            y={bottom + 6 + LABEL_SIZE}
            textAnchor="middle"
            fontSize={LABEL_SIZE}
            fill={palette.muted}
          >
            {text}
          </text>
        );
      })}
    </>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Horizontal bars — the form to use when the categories are words
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Bars running right, with the category name in a left-hand gutter.
 *
 * The form for cost heads, price bands and buyer types — anything whose categories are words
 * rather than a sequence. The gutter is measured off the longest name and then capped at a third
 * of the figure, so one unusually long head cannot squeeze every bar into nothing.
 */
function HorizontalBars({ series }: { series: Series }) {
  const palette = useReportPalette();
  const height = horizontalHeight(series.length);
  const cap = VIEW_WIDTH * 0.34;
  const gutter = Math.min(
    cap,
    Math.max(...series.map(([label]) => textWidth(ellipsise(label, cap, LABEL_SIZE), LABEL_SIZE)), 40) + 10
  );
  const left = gutter;
  const right = VIEW_WIDTH - 12;
  const top = 10;
  const bottom = height - 10;

  const peak = Math.max(...series.map(([, value]) => Math.abs(value)), 0);
  // The one division in this component, guarded here rather than at each use. A series of all
  // zeroes gives every bar the same one-unit stub, which is the honest picture of a cost sheet
  // that has heads but no figures under them yet.
  const unit = peak > 0 ? (right - left - 76) / peak : 0;

  const row = (bottom - top) / series.length;
  const barHeight = Math.min(row * 0.62, 26);

  return (
    <>
      {series.map(([label, value], index) => {
        const centre = top + row * (index + 0.5);
        const length = Math.max(1.5, Math.abs(value) * unit);
        return (
          <g key={`${index}-${label}`}>
            {/* A faint rule the width of the plot behind every bar, so a short one can still be
                read against the bars above and below rather than floating in white space. */}
            <line x1={left} y1={centre} x2={right} y2={centre} stroke={palette.rule} strokeWidth={1} opacity={0.45} />
            <rect x={left} y={centre - barHeight / 2} width={length} height={barHeight} fill={barFill(palette, value)} />
            <text
              x={left - 8}
              y={centre}
              textAnchor="end"
              dominantBaseline="central"
              fontSize={LABEL_SIZE}
              fill={palette.muted}
            >
              {ellipsise(label, gutter - 12, LABEL_SIZE)}
            </text>
            <text x={left + length + 8} y={centre} dominantBaseline="central" fontSize={VALUE_SIZE} fill={palette.ink}>
              {formatNumber(value)}
            </text>
          </g>
        );
      })}
    </>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The pie and the donut
 * ──────────────────────────────────────────────────────────────────────────── */

/** A point on a circle, with twelve o'clock at angle −π/2 and the sweep running clockwise. */
function polar(cx: number, cy: number, radius: number, angle: number): [number, number] {
  return [cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)];
}

/**
 * One slice, as an annulus sector — or a wedge when `inner` is zero.
 *
 * The sweep flag is 1 throughout because SVG's y axis grows DOWN, so an increasing angle from
 * −π/2 travels top → right → bottom: the clockwise order every reader of a printed pie expects to
 * find the first category in, and the order `_draw_circular` accumulates its own sweep in.
 */
function sectorPath(cx: number, cy: number, radius: number, inner: number, start: number, sweep: number): string {
  const end = start + sweep;
  const large = sweep > Math.PI ? 1 : 0;
  const [x0, y0] = polar(cx, cy, radius, start);
  const [x1, y1] = polar(cx, cy, radius, end);
  if (inner <= 0) {
    return `M${cx} ${cy}L${x0} ${y0}A${radius} ${radius} 0 ${large} 1 ${x1} ${y1}Z`;
  }
  const [x2, y2] = polar(cx, cy, inner, end);
  const [x3, y3] = polar(cx, cy, inner, start);
  return `M${x0} ${y0}A${radius} ${radius} 0 ${large} 1 ${x1} ${y1}L${x2} ${y2}A${inner} ${inner} 0 ${large} 0 ${x3} ${y3}Z`;
}

/**
 * A pie or a donut, with a legend naming every slice and its value.
 *
 * THE LEGEND IS NOT OPTIONAL, and it is the judgement `_draw_circular` makes for the same reason.
 * Labels placed on the slices themselves need a leader line for anything under about eight per
 * cent, a leader line needs a layout pass, and a report is not a dashboard — the reader has the
 * page in their hand, so a list beside the figure is both cheaper and easier to read off.
 */
function Circular({ series, donut }: { series: Series; donut: boolean }) {
  const palette = useReportPalette();
  const height = VIEW_WIDTH * ASPECT;
  const legendWidth = Math.min(VIEW_WIDTH * 0.46, VIEW_WIDTH * 0.3 + 120);
  const plotWidth = VIEW_WIDTH - legendWidth;
  const radius = Math.min(plotWidth, height) * 0.4;
  const cx = plotWidth / 2;
  const cy = height / 2;
  const inner = donut ? radius * 0.55 : 0;
  const total = series.reduce((sum, [, value]) => sum + value, 0);
  const colours = sliceColours(palette, series.length);

  const swatch = 18;
  const lineHeight = Math.max(LABEL_SIZE + 10, 28);
  const legendX = plotWidth + 8;
  // Every entry that fits, and no more. What is cut is not lost — `ReportBlock` prints the whole
  // series as a real table under the figure, which is where the numbers are read anyway.
  const visible = Math.max(1, Math.min(series.length, Math.floor((height - 12) / lineHeight)));
  const legendTop = Math.max(6, (height - lineHeight * visible) / 2);

  const slices: Array<{ path: string; colour: string; key: string }> = [];
  if (total > 0) {
    let angle = -Math.PI / 2;
    series.forEach(([label, value], index) => {
      const sweep = (value / total) * Math.PI * 2;
      if (sweep <= 0) return;
      if (sweep >= Math.PI * 2 - 1e-6) {
        // A single category owns the whole circle, and an arc whose ends coincide draws nothing at
        // all — the ring would vanish on exactly the figure that has one answer. Closed as two
        // half sweeps instead.
        slices.push({ path: sectorPath(cx, cy, radius, inner, angle, Math.PI), colour: colours[index], key: `${index}-a` });
        slices.push({
          path: sectorPath(cx, cy, radius, inner, angle + Math.PI, Math.PI),
          colour: colours[index],
          key: `${index}-b`
        });
      } else {
        slices.push({
          path: sectorPath(cx, cy, radius, inner, angle, sweep),
          colour: colours[index],
          key: `${index}-${label}`
        });
      }
      angle += sweep;
    });
  }

  return (
    <>
      {total > 0 ? (
        slices.map((slice) => <path key={slice.key} d={slice.path} fill={slice.colour} />)
      ) : (
        // NOTHING ABOVE IS DIVIDED BY THE TOTAL UNLESS IT IS POSITIVE. An empty ring with the
        // categories still listed says "these heads exist and every one of them is zero", which is
        // a real state of a cost sheet at the start of a workshop and not a failure to draw.
        <>
          <circle
            cx={cx}
            cy={cy}
            r={(radius + Math.max(inner, radius * 0.55)) / 2}
            fill="none"
            stroke={css(mix(WHITE, rgbOf(palette.muted), 0.22))}
            strokeWidth={radius - Math.max(inner, radius * 0.55)}
          />
          <text x={cx} y={cy} textAnchor="middle" dominantBaseline="central" fontSize={LABEL_SIZE} fill={palette.muted}>
            0
          </text>
        </>
      )}

      {donut && total > 0 ? (
        <>
          <circle cx={cx} cy={cy} r={inner} fill={palette.paper} />
          <text
            x={cx}
            y={cy}
            textAnchor="middle"
            dominantBaseline="central"
            fontSize={HEADLINE_SIZE}
            fontWeight={700}
            fill={palette.ink}
          >
            {ellipsise(formatNumber(total), inner * 1.7, HEADLINE_SIZE)}
          </text>
        </>
      ) : null}

      {series.slice(0, visible).map(([label, value], index) => {
        const y = legendTop + lineHeight * index;
        const share = total > 0 ? ` (${Math.round((value / total) * 100)}%)` : "";
        return (
          <g key={`legend-${index}-${label}`}>
            <rect x={legendX} y={y + (lineHeight - swatch) / 2} width={swatch} height={swatch} fill={colours[index]} />
            <text
              x={legendX + swatch + 8}
              y={y + lineHeight / 2}
              dominantBaseline="central"
              fontSize={LABEL_SIZE}
              fill={palette.muted}
            >
              {ellipsise(
                `${label} — ${formatNumber(value)}${share}`,
                VIEW_WIDTH - legendX - swatch - 16,
                LABEL_SIZE
              )}
            </text>
          </g>
        );
      })}
    </>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The figure
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One `ChartBlock`, drawn.
 *
 * EVERY DEGENERATE INPUT HAS A DEFINED PICTURE, which is the promise `render_chart_png` makes and
 * the reason it can say it never raises on the data: no series, one category, a total of zero and
 * every value negative all draw something. A figure a template asked for and the data could not
 * fill has to read as a statement about the record rather than as a rendering fault — an omitted
 * figure looks like a bug, and an empty one that says why is information a designer can act on.
 */
export function ReportChartSvg({ block }: { block: DwChartBlock }) {
  const palette = useReportPalette();
  const circular = isCircular(block.kind);
  const { kept, dropped } = useMemo(() => cleanSeries(block.series ?? [], circular), [block.series, circular]);
  const height = block.kind === "HORIZONTAL_BAR" ? horizontalHeight(kept.length) : VIEW_WIDTH * ASPECT;

  return (
    <svg
      viewBox={`0 0 ${VIEW_WIDTH} ${height}`}
      className="rp-chart"
      role="presentation"
      aria-hidden="true"
      focusable="false"
    >
      <rect x={0} y={0} width={VIEW_WIDTH} height={height} fill={palette.paper} />

      {kept.length === 0 ? (
        <>
          <rect x={0} y={0} width={VIEW_WIDTH} height={height} fill={css(mix(WHITE, rgbOf(palette.rule), 0.18))} />
          <text
            x={VIEW_WIDTH / 2}
            y={height / 2}
            textAnchor="middle"
            dominantBaseline="central"
            fontSize={LABEL_SIZE + 2}
            fill={palette.muted}
          >
            No values recorded.
          </text>
        </>
      ) : circular ? (
        <Circular series={kept} donut={block.kind === "DONUT"} />
      ) : block.kind === "HORIZONTAL_BAR" ? (
        <HorizontalBars series={kept} />
      ) : (
        <Cartesian series={kept} line={block.kind === "LINE"} />
      )}

      {/* What could not be drawn is NAMED, inside the picture, where the rasteriser names it too. A
          pie silently missing its negative slice is a figure whose parts do not add up to its whole
          with nothing anywhere to say why. */}
      {dropped.length ? (
        <text x={6} y={LABEL_SIZE} fontSize={LABEL_SIZE - 2} fill={palette.muted}>
          {ellipsise(
            `Not shown: ${dropped.slice(0, 6).join(", ")}${dropped.length > 6 ? "…" : ""}`,
            VIEW_WIDTH - 12,
            LABEL_SIZE - 2
          )}
        </text>
      ) : null}
    </svg>
  );
}
