"use client";

import { createContext, useContext, useMemo, type ReactNode } from "react";

import { REPORT_PALETTE, type DwChartBlock, type DwChartKind } from "@/components/designworkshop/report/previewModel";
import type { ReportPalette } from "@/lib/reportTheme";

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

export function formatNumber(value: number): string {
  if (!Number.isFinite(value)) return "-";
  const sign = value < 0 ? "-" : "";
  const magnitude = Math.abs(value);
  if (magnitude >= 1000 || magnitude === Math.trunc(magnitude)) {
    return sign + groupIndian(magnitude.toFixed(0));
  }
  return sign + magnitude.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}

function niceStep(span: number, targetTicks: number): number {
  if (span <= 0 || targetTicks <= 0) return 1;
  const raw = span / targetTicks;
  const power = raw > 0 ? 10 ** Math.floor(Math.log10(raw)) : 1;
  for (const multiplier of [1, 2, 2.5, 5, 10]) {
    if (raw <= multiplier * power) return multiplier * power;
  }
  return 10 * power;
}

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

const PaletteContext = createContext<ReportPalette>(REPORT_PALETTE);

export function ReportPaletteProvider({ palette, children }: { palette: ReportPalette; children: ReactNode }) {
  return <PaletteContext.Provider value={palette}>{children}</PaletteContext.Provider>;
}

export function useReportPalette(): ReportPalette {
  return useContext(PaletteContext);
}

function barFill(palette: ReportPalette, value: number): string {
  const soft = rgbOf(palette.accentSoft);
  return css(value >= 0 ? soft : mix(soft, rgbOf(palette.ink), 0.35));
}

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

function textWidth(text: string, size: number): number {
  return text.length * size * 0.55;
}

function ellipsise(text: string, maxWidth: number, size: number): string {
  if (maxWidth <= 0) return "";
  if (textWidth(text, size) <= maxWidth) return text;
  let cut = text.length;
  while (cut > 0 && textWidth(`${text.slice(0, cut)}…`, size) > maxWidth) cut -= 1;
  return cut > 0 ? `${text.slice(0, cut)}…` : "";
}

const VIEW_WIDTH = 900;

const ASPECT = 0.62;

const LABEL_SIZE = 19;
const VALUE_SIZE = 19;
const HEADLINE_SIZE = 40;

type Series = Array<[string, number]>;

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

const ROW_PITCH = 34;

function horizontalHeight(rows: number): number {
  return Math.max(ROW_PITCH * 3, ROW_PITCH * Math.max(1, rows) + 26);
}

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

function polar(cx: number, cy: number, radius: number, angle: number): [number, number] {
  return [cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)];
}

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
  const visible = Math.max(1, Math.min(series.length, Math.floor((height - 12) / lineHeight)));
  const legendTop = Math.max(6, (height - lineHeight * visible) / 2);

  const slices: Array<{ path: string; colour: string; key: string }> = [];
  if (total > 0) {
    let angle = -Math.PI / 2;
    series.forEach(([label, value], index) => {
      const sweep = (value / total) * Math.PI * 2;
      if (sweep <= 0) return;
      if (sweep >= Math.PI * 2 - 1e-6) {
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
