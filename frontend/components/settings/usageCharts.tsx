"use client";

import { useId, type ReactNode } from "react";

import { axisTop, labelStride, statedRuns, tickText } from "@/components/settings/usageChartMath";
import { isWithheldRoute } from "@/lib/usage";

const COUNT = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

function useHatchId(): string {
  return `usage-gap-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
}

export function ChartFrame({
  title,
  description,
  caps,
  legend,
  children,
  table
}: {
  title: string;
  description: string;
  caps: ReactNode;
  legend?: ReactNode;
  children: ReactNode;
  table?: ReactNode;
}) {
  return (
    <section className="panel min-w-0 p-4">
      <h2 className="font-display font-bold text-ink-900">{title}</h2>
      <p className="mt-0.5 text-sm leading-6 text-ink-500">{description}</p>
      {legend ? <div className="mt-3">{legend}</div> : null}
      <div className="mt-3 overflow-x-auto">{children}</div>
      <p className="mt-2 text-xs leading-5 text-ink-500">{caps}</p>
      {table}
    </section>
  );
}

export function ChartLegend({ items }: { items: Array<{ label: string; className: string }> }) {
  return (
    <ul className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-1.5 text-xs text-ink-700">
          <span className={`h-2.5 w-2.5 rounded-[2px] ${item.className}`} aria-hidden />
          {item.label}
        </li>
      ))}
    </ul>
  );
}

export const ORDINAL_INK = [
  "text-purple-400 dark:text-purple-200",
  "text-purple-600 dark:text-purple-400",
  "text-purple-700 dark:text-purple-600"
] as const;

export const ORDINAL_BG = [
  "bg-purple-400 dark:bg-purple-200",
  "bg-purple-600 dark:bg-purple-400",
  "bg-purple-700 dark:bg-purple-600"
] as const;

const PRIMARY_INK = "text-purple-700 dark:text-purple-400";

function GapBand({
  hatchId,
  x,
  width,
  top,
  height,
  reason
}: {
  hatchId: string;
  x: number;
  width: number;
  top: number;
  height: number;
  reason: string;
}) {
  return (
    <rect x={x} y={top} width={width} height={height} fill={`url(#${hatchId})`} opacity={0.75}>
      <title>{reason}</title>
    </rect>
  );
}

function GapHatch({ id }: { id: string }) {
  return (
    <defs>
      <pattern id={id} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <line x1="0" y1="0" x2="0" y2="6" className="text-ink-300" stroke="currentColor" strokeWidth="2" />
      </pattern>
    </defs>
  );
}

export type LinePoint = {
  label: string;
  value: number | null;
  withheld: boolean;
  withheldBecause?: string;
  title: string;
};

const W = 720;
const H = 232;
const PAD = { left: 56, right: 14, top: 12, bottom: 34 };
const PLOT_W = W - PAD.left - PAD.right;
const PLOT_H = H - PAD.top - PAD.bottom;

export function UsageLineChart({
  points,
  unit,
  tone = "primary",
  ariaLabel
}: {
  points: LinePoint[];
  unit: "count" | "percent";
  tone?: "primary" | "error";
  ariaLabel: string;
}) {
  const hatchId = useHatchId();
  const [top, step] = axisTop(points.map((point) => point.value));
  const ticks: number[] = [];
  for (let value = 0; value <= top + 1e-9; value += step) ticks.push(value);

  const slot = points.length > 0 ? PLOT_W / points.length : PLOT_W;
  const xOf = (index: number) => PAD.left + slot * (index + 0.5);
  const yOf = (value: number) => PAD.top + PLOT_H - (value / top) * PLOT_H;

  const runs = statedRuns(points).map((indices) =>
    indices.map((index) => ({ x: xOf(index), y: yOf(points[index].value as number) }))
  );

  const ink = tone === "error" ? "text-error-600" : PRIMARY_INK;
  const labelEvery = labelStride(points.length);

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="h-auto w-full min-w-[520px] max-w-[720px]"
      role="img"
      aria-label={ariaLabel}
    >
      <GapHatch id={hatchId} />
      <g className="text-line-200">
        {ticks.map((value) => (
          <line
            key={value}
            x1={PAD.left}
            x2={W - PAD.right}
            y1={yOf(value)}
            y2={yOf(value)}
            stroke="currentColor"
            strokeWidth={1}
          />
        ))}
      </g>
      <g className="text-ink-500" fontSize={11} fill="currentColor">
        {ticks.map((value) => (
          <text key={value} x={PAD.left - 8} y={yOf(value) + 4} textAnchor="end">
            {tickText(value, unit)}
          </text>
        ))}
        {points.map((point, index) =>
          index % labelEvery === 0 ? (
            <text key={point.label + index} x={xOf(index)} y={H - 12} textAnchor="middle">
              {point.label}
            </text>
          ) : null
        )}
      </g>

      {points.map((point, index) =>
        point.value === null || point.withheld ? (
          <GapBand
            hatchId={hatchId}
            key={`gap-${point.label}-${index}`}
            x={xOf(index) - slot / 2}
            width={slot}
            top={PAD.top}
            height={PLOT_H}
            reason={
              point.withheldBecause ??
              (point.withheld
                ? "Hidden: too few people in this period."
                : "No requests in this period.")
            }
          />
        ) : null
      )}

      <g className={ink}>
        {runs.map((segment, index) => (
          <path
            key={index}
            d={segment.map((p, i) => `${i === 0 ? "M" : "L"}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(" ")}
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ))}
        {points.map((point, index) =>
          point.value === null || point.withheld ? null : (
            <circle
              key={`dot-${point.label}-${index}`}
              cx={xOf(index)}
              cy={yOf(point.value)}
              r={4}
              fill="currentColor"
              stroke="rgb(var(--card))"
              strokeWidth={2}
            >
              <title>{`${point.title} — ${tickText(point.value, unit)}`}</title>
            </circle>
          )
        )}
      </g>
    </svg>
  );
}

export type BarRow = { label: string; value: number | null; withheld: boolean; withheldBecause?: string; title: string };

export function UsageBarRows({ rows, unit, ariaLabel }: { rows: BarRow[]; unit: "count" | "ms"; ariaLabel: string }) {
  const hatchId = useHatchId();
  const [top] = axisTop(rows.map((row) => row.value));
  const rowH = 26;
  const labelW = 300;
  const barMax = W - labelW - 90;
  const height = Math.max(1, rows.length) * rowH + 10;

  return (
    <svg
      viewBox={`0 0 ${W} ${height}`}
      className="h-auto w-full min-w-[560px] max-w-[720px]"
      role="img"
      aria-label={ariaLabel}
    >
      <GapHatch id={hatchId} />
      {rows.map((row, index) => {
        const y = index * rowH + 6;
        const withheld = isWithheldRoute(row) || row.value === null;
        return (
          <g key={`${row.label}-${index}`}>
            <text x={0} y={y + 13} fontSize={11} className="text-ink-700" fill="currentColor">
              {row.label.length > 46 ? `${row.label.slice(0, 45)}…` : row.label}
              <title>{row.label}</title>
            </text>
            {withheld ? (
              <GapBand
                hatchId={hatchId}
                x={labelW}
                width={barMax}
                top={y + 3}
                height={14}
                reason={row.withheldBecause ?? "Hidden: too few people used this screen in this period."}
              />
            ) : (
              <g className={PRIMARY_INK}>
                <rect
                  x={labelW}
                  y={y + 3}
                  rx={4}
                  width={Math.max(2, ((row.value ?? 0) / top) * barMax)}
                  height={14}
                  fill="currentColor"
                >
                  <title>{row.title}</title>
                </rect>
              </g>
            )}
            <text
              x={labelW + barMax + 8}
              y={y + 14}
              fontSize={11}
              className={withheld ? "text-ink-500" : "text-ink-900"}
              fill="currentColor"
            >
              {withheld ? "—" : tickText(row.value ?? 0, unit)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

export type PercentileRow = {
  label: string;
  p50: number | null;
  p95: number | null;
  p99: number | null;
  withheld: boolean;
  withheldBecause?: string;
  noTraffic: boolean;
};

export function UsagePercentileRows({ rows, ariaLabel }: { rows: PercentileRow[]; ariaLabel: string }) {
  const hatchId = useHatchId();
  const [top, step] = axisTop(rows.flatMap((row) => [row.p50, row.p95, row.p99]));
  const rowH = 26;
  const labelW = 300;
  const barMax = W - labelW - 90;
  const height = Math.max(1, rows.length) * rowH + 30;
  const xOf = (ms: number) => labelW + (ms / top) * barMax;
  const ticks: number[] = [];
  for (let value = 0; value <= top + 1e-9; value += step) ticks.push(value);

  return (
    <svg
      viewBox={`0 0 ${W} ${height}`}
      className="h-auto w-full min-w-[560px] max-w-[720px]"
      role="img"
      aria-label={ariaLabel}
    >
      <GapHatch id={hatchId} />
      <g className="text-line-200">
        {ticks.map((value) => (
          <line key={value} x1={xOf(value)} x2={xOf(value)} y1={0} y2={height - 22} stroke="currentColor" strokeWidth={1} />
        ))}
      </g>
      <g className="text-ink-500" fontSize={11} fill="currentColor">
        {ticks.map((value) => (
          <text key={value} x={xOf(value)} y={height - 6} textAnchor="middle">
            {tickText(value, "ms")}
          </text>
        ))}
      </g>
      {rows.map((row, index) => {
        const y = index * rowH + 6;
        const unusable = row.withheld || row.p50 === null || row.p95 === null || row.p99 === null;
        return (
          <g key={`${row.label}-${index}`}>
            <text x={0} y={y + 13} fontSize={11} className="text-ink-700" fill="currentColor">
              {row.label.length > 46 ? `${row.label.slice(0, 45)}…` : row.label}
              <title>{row.label}</title>
            </text>
            {unusable ? (
              <GapBand
                hatchId={hatchId}
                x={labelW}
                width={barMax}
                top={y + 3}
                height={14}
                reason={
                  row.withheldBecause ??
                  (row.noTraffic
                    ? "Nobody used this screen in this period."
                    : "Hidden: too few people used this screen in this period.")
                }
              />
            ) : (
              <>
                <line
                  x1={xOf(row.p50 as number)}
                  x2={xOf(row.p99 as number)}
                  y1={y + 10}
                  y2={y + 10}
                  className="text-line-200"
                  stroke="currentColor"
                  strokeWidth={4}
                  strokeLinecap="round"
                />
                {([
                  ["p50", row.p50 as number, ORDINAL_INK[0]],
                  ["p95", row.p95 as number, ORDINAL_INK[1]],
                  ["p99", row.p99 as number, ORDINAL_INK[2]]
                ] as const).map(([name, value, ink]) => (
                  <g key={name} className={ink}>
                    <circle cx={xOf(value)} cy={y + 10} r={5} fill="currentColor" stroke="rgb(var(--card))" strokeWidth={2}>
                      <title>{`${row.label} — ${name} ${tickText(value, "ms")}`}</title>
                    </circle>
                  </g>
                ))}
              </>
            )}
            <text
              x={labelW + barMax + 8}
              y={y + 14}
              fontSize={11}
              className={unusable ? "text-ink-500" : "text-ink-900"}
              fill="currentColor"
            >
              {unusable ? "—" : tickText(row.p99 as number, "ms")}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

export type StackSegment = { label: string; value: number | null; withheld: boolean; withheldBecause?: string };

export function UsageStackedBar({ segments, ariaLabel }: { segments: StackSegment[]; ariaLabel: string }) {
  const drawable = segments.filter((segment) => !segment.withheld && (segment.value ?? 0) > 0);
  const total = drawable.reduce((sum, segment) => sum + (segment.value ?? 0), 0);

  if (!total) {
    return (
      <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-500">
        No requests were recorded in this period.
      </p>
    );
  }

  return (
    <div>
      <div className="flex h-3 w-full gap-[2px] overflow-hidden rounded-full" role="img" aria-label={ariaLabel}>
        {drawable.map((segment, index) => (
          <span
            key={segment.label}
            className={ORDINAL_BG[index % ORDINAL_BG.length]}
            style={{ width: `${(((segment.value ?? 0) / total) * 100).toFixed(2)}%` }}
            title={`${segment.label}: ${COUNT.format(segment.value ?? 0)} requests`}
          />
        ))}
      </div>
      <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-700">
        {drawable.map((segment, index) => (
          <li key={segment.label} className="flex items-center gap-1.5">
            <span className={`h-2.5 w-2.5 rounded-[2px] ${ORDINAL_BG[index % ORDINAL_BG.length]}`} aria-hidden />
            <span className="font-medium text-ink-900">{segment.label}</span>
            <span>{COUNT.format(segment.value ?? 0)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
