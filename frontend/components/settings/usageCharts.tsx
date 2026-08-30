"use client";

/**
 * THE FIGURES ON /settings/usage, DRAWN AS INLINE SVG.
 *
 * ══ WHY THERE IS NO CHARTING LIBRARY IN THIS REPOSITORY, AND WHY THAT STAYS TRUE ═══════════════
 *
 * `components/designworkshop/report/ReportChart.tsx` argues it for the report figures and the same
 * argument holds here: a library brings its own axis rounding, its own "nice" tick algorithm and its
 * own palette, which is a way for two drawings of one dataset to disagree — and on this page one of
 * the two drawings is a table of the same numbers, sitting directly underneath. `package.json` has
 * no chart dependency; adding one for six figures would be 40–120 KB on an admin route to reproduce
 * `<path d=…>`. `axisBounds` and `niceStep` below are lifted from that file deliberately rather than
 * re-derived, so the two surfaces round an axis the same way.
 *
 * ══ THE THREE STATES A MARK MUST KEEP APART, AND WHY THIS IS THE HARD PART ═════════════════════
 *
 * Every aggregate under `/api/usage` distinguishes:
 *
 *   1. **nothing happened** — `requests: 0`, `withheld: false`. A real, measured zero. It is PLOTTED,
 *      on the baseline, because the axis always includes zero and "no traffic in this hour" is a
 *      fact worth seeing.
 *   2. **withheld** — `withheld: true`, EVERY metric `null`, because fewer identified accounts used
 *      it than the server's floor. It is drawn as a **GAP**: the line breaks, a hatched band marks
 *      where it broke, and the reason is on the mark. **It is never a point at y = 0.**
 *   3. **no distribution** — nulls with `withheld: false`: a screen with no traffic has no
 *      percentiles to report. Also a gap, worded differently.
 *
 * `null` becomes `0` through arithmetic, through `??`, through `Number()` and through a comparator,
 * so **a chart is a far more dangerous renderer of these rows than a table is**: a table cell shows
 * an em dash and a reader moves on, whereas a plotted zero is a confident claim that the server
 * explicitly refused to make. Every path below therefore branches on `isWithheldRoute` (or on a
 * literal `value === null`) BEFORE it computes a coordinate, and the geometry helpers take
 * `number | null` rather than `number` so the compiler is on the same side.
 *
 * ══ COLOUR: THE DESIGN SYSTEM SUPPLIES THE PARAMETERS, THE METHOD IS UNCHANGED ═════════════════
 *
 * The house rule is absolute — **purple-700 is the only action colour, and no data screen carries a
 * second accent.** So where a generic categorical palette would reach for eight hues, this uses ONE
 * hue at validated steps plus composite encoding, which is the adaptation the data-viz method itself
 * prescribes when a design system supplies its own parameters.
 *
 *   * **Single-series line** (traffic) — purple-700 on light (7.59:1 on the card), purple-400 on
 *     dark (7.16:1). One series needs no legend; the chart's title names it.
 *   * **Ordered measures** (p50 → p95 → p99) — an ORDINAL ramp, one hue, monotone lightness, which
 *     is the correct encoding for ordered data rather than a compromise forced by the house rule.
 *     Light: purple-400 / purple-600 / purple-700. Dark: purple-200 / purple-400 / purple-600. Both
 *     sets were run through the data-viz validator in `--ordinal` mode against the real card colours
 *     (`#ffffff` and `#1a1725`) and BOTH PASS every check — monotone lightness, adjacent ΔL ≥ 0.06,
 *     light-end contrast clear of the 2:1 floor, single hue. The first ramp tried, purple-300 as the
 *     light end, FAILED at 1.76:1 and was re-stepped rather than shipped.
 *   * **Categorical** (web / android / api) — the one genuinely unordered encoding here, and it uses
 *     the same ramp. Identity is carried by a legend AND a direct label on every segment AND the
 *     table beneath, so colour carries nothing on its own — which is the floor the method sets. The
 *     ramp is not arbitrary either: it runs from "said it was web" through "said it was android" to
 *     "did not say", most specific to least, and `api` — the residual — takes the recessive end.
 *   * **Error rate** — `error-600`, the repository's reserved status colour, used for the thing it
 *     literally names, never as "series 2". 4.83:1 on the light card and 3.64:1 on the dark one. The
 *     chart is titled "Error rate", the axis is a percentage and the table repeats the figure, so a
 *     reader who cannot see the red loses nothing.
 *
 * **DARK MODE IS SELECTED, NOT FLIPPED.** Brand purple deliberately does not invert in this
 * repository (`tailwind.config.ts`), and there is no themed purple token to reach for — so the marks
 * carry an explicit `dark:` variant, which is precisely what §5's "exception mechanism" is for. The
 * colours travel as `currentColor` off a `text-*`/`dark:text-*` class on the containing `<g>`,
 * because `fill-purple-700` and friends would need every step spelled out as a utility and would
 * silently produce `currentColor`'s fallback if one were purged.
 *
 * ══ ACCESSIBILITY ══════════════════════════════════════════════════════════════════════════════
 *
 * Every chart is `role="img"` with a one-sentence `aria-label` that states the shape and the caps,
 * every mark carries a `<title>` (the browser's own tooltip, which is also what the table cells on
 * this page already use), and **every chart has a table of the same numbers next to it** — which is
 * the method's stated relief for a mark that cannot clear a contrast floor and the honest route for
 * a reader who cannot use a pointer. Nothing here animates, so there is no reduced-motion branch to
 * get wrong.
 */

import { useId, type ReactNode } from "react";

import { axisTop, labelStride, statedRuns, tickText } from "@/components/settings/usageChartMath";
import { isWithheldRoute } from "@/lib/usage";

/** 12,34,567 — the Indian grouping every other figure on this page is printed in, so a reader
 *  comparing a direct label with a table cell never converts between two conventions. */
const COUNT = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

/**
 * The pure half lives in `usageChartMath.ts`, NOT here — see that file's own header. There is no
 * React renderer in devDependencies, so anything decided inside a component is only ever exercised
 * by somebody looking at a screen; the two decisions this page turns on (a withheld point must not
 * become a plotted zero, and an axis must include zero) are pinned by
 * `e2e/usage-figures-unit.spec.ts` calling them directly.
 */

/**
 * A DOM id for an SVG `<pattern>`, derived from `useId` but stripped to characters a fragment
 * reference can carry.
 *
 * `useId()` returns something like `«r3»` on React 19 (and `:r3:` on 18) — deliberately, so the
 * value cannot be used in a CSS selector by accident. `fill="url(#«r3»)"` is a different mechanism
 * and browsers do resolve it, but it is an id that is not a valid XML Name, and relying on that
 * leniency for the one attribute that decides whether a gap band paints at all is not a bet worth
 * taking on a page whose whole purpose is not misrepresenting missing data. Stripping keeps the
 * uniqueness (the counter is what makes it unique) and loses the fragility.
 */
function useHatchId(): string {
  return `usage-gap-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Shared chrome
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One figure: a heading, the drawing, and — always — whatever the server said about it.
 *
 * `caps` is not optional prose. Every one of these routes answers about a NAMED, CAPPED set of
 * screens and none of them answers about the platform, so a figure drawn without its scope printed
 * beside it is a slice presented as a whole. That is rule 10 of this repository's frontend contract
 * wearing a chart.
 */
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
  /** The same numbers as a table. Present on every chart — see the accessibility note above. */
  table?: ReactNode;
}) {
  return (
    <section className="panel p-4">
      <h2 className="font-display font-bold text-ink-900">{title}</h2>
      <p className="mt-0.5 text-sm leading-6 text-ink-500">{description}</p>
      {legend ? <div className="mt-3">{legend}</div> : null}
      <div className="mt-3 overflow-x-auto">{children}</div>
      <p className="mt-2 text-xs leading-5 text-ink-500">{caps}</p>
      {table}
    </section>
  );
}

/**
 * The legend. Present whenever there are two or more series — never optional, because a swatch
 * without a word is identity carried by colour alone.
 */
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

/** The three ordinal steps, as `text-*` classes so `currentColor` carries them into the SVG. See
 *  the colour note at the top: both sets are validator-passing, per mode, not an automatic flip. */
export const ORDINAL_INK = [
  "text-purple-400 dark:text-purple-200",
  "text-purple-600 dark:text-purple-400",
  "text-purple-700 dark:text-purple-600"
] as const;

/** The same three steps as background utilities, for legend swatches and stacked segments. */
export const ORDINAL_BG = [
  "bg-purple-400 dark:bg-purple-200",
  "bg-purple-600 dark:bg-purple-400",
  "bg-purple-700 dark:bg-purple-600"
] as const;

/** The single-series mark. */
const PRIMARY_INK = "text-purple-700 dark:text-purple-400";

/**
 * THE GAP MARK: what is drawn where the server withheld a figure, or had none to report.
 *
 * A hatched vertical band and NOT a point. The band says "there is no value here" in a way a reader
 * cannot mistake for a low one, and the `<title>` carries the server's own reason to a pointer. The
 * hatch is a 45° pattern rather than a flat tint because a flat grey band at the bottom of a chart
 * reads as a bar.
 */
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

/**
 * The one `<defs>` the gap band needs, mounted once per chart — with an id from `useId`.
 *
 * **THE ID MUST BE UNIQUE PER CHART AND THAT IS NOT TIDINESS.** Six of these render on one page, and
 * `url(#id)` resolves to the FIRST match in the document — so a hard-coded id would make every gap
 * band on the page paint from chart one's pattern. That is invisible today, because all six are
 * identical, and would become a real bug the moment somebody parameterised the hatch.
 *
 * The stroke colour is set on the `<line>` itself rather than inherited: `currentColor` inside a
 * `<pattern>` resolves against the pattern's own position in the tree (inside `<defs>`, inheriting
 * the page's ink), NOT against the element that references it — so a hatch written to inherit from
 * the band would come out in heading ink rather than in the recessive grey it is meant to be.
 */
function GapHatch({ id }: { id: string }) {
  return (
    <defs>
      <pattern id={id} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <line x1="0" y1="0" x2="0" y2="6" className="text-ink-300" stroke="currentColor" strokeWidth="2" />
      </pattern>
    </defs>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Line chart — traffic over time, and error rate over time
 * ──────────────────────────────────────────────────────────────────────────── */

export type LinePoint = {
  label: string;
  value: number | null;
  withheld: boolean;
  withheldBecause?: string;
  /** The full bucket label, for the tooltip — the axis tick is abbreviated. */
  title: string;
};

const W = 720;
const H = 232;
const PAD = { left: 56, right: 14, top: 12, bottom: 34 };
const PLOT_W = W - PAD.left - PAD.right;
const PLOT_H = H - PAD.top - PAD.bottom;

/**
 * One measure over time.
 *
 * **ONE SERIES PER CHART, AND NEVER TWO SCALES ON ONE AXIS.** Traffic and error rate are two charts
 * side by side rather than one chart with a right-hand axis. A dual-axis overlay is the single most
 * misleading chart form there is: the crossing point of the two lines is an artefact of where
 * somebody chose to start the second scale, and readers reliably read it as an event.
 */
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

  /*
    THE LINE IS BUILT AS RUNS OF CONSECUTIVE STATED POINTS, NOT AS ONE PATH WITH HOLES.

    A single `d` string that skipped the nulls would join the point before a gap straight to the
    point after it — drawing a confident straight line across exactly the region where the server
    said it would not give a figure. `statedRuns` is in the pure module so that rule is pinned by a
    test rather than by a reviewer noticing a missing branch.
  */
  const runs = statedRuns(points).map((indices) =>
    indices.map((index) => ({ x: xOf(index), y: yOf(points[index].value as number) }))
  );

  const ink = tone === "error" ? "text-error-600" : PRIMARY_INK;
  // Draw at most a dozen x labels however many buckets there are; 168 hourly ticks overprint into a
  // grey band that says nothing. The count is stated beside the chart either way.
  const labelEvery = labelStride(points.length);

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="h-auto w-full min-w-[520px] max-w-[720px]"
      role="img"
      aria-label={ariaLabel}
    >
      <GapHatch id={hatchId} />
      {/* Gridlines and axis labels, recessive by design — they orient, they do not compete. */}
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

      {/* THE GAPS, UNDER THE LINE so the line's own marks stay readable over them. */}
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
                ? "Withheld: too few identified accounts in this period for the server to report it."
                : "Not stated: there were no requests in this period, so there is no rate to report.")
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
              // A 2px ring in the surface colour so overlapping marks stay countable.
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

/* ────────────────────────────────────────────────────────────────────────────
 * Horizontal bars — busiest and slowest screens
 * ──────────────────────────────────────────────────────────────────────────── */

export type BarRow = { label: string; value: number | null; withheld: boolean; withheldBecause?: string; title: string };

/**
 * A ranking, drawn as horizontal bars because the labels are route templates and a route template
 * is far too long to stand under a vertical bar.
 *
 * **THE ORDER IS THE SERVER'S AND IS NOT RE-SORTED HERE.** `/usage/screens` ranks these itself
 * precisely because the correct sort is the one a client is most likely to get wrong: a withheld row
 * carries `null` everywhere, `null` sorts as 0 through JavaScript's comparator, and a naive "slowest
 * first" would file every screen the server REFUSED to report at the fast end of the list. The
 * server excludes them from both orderings and counts them separately; this component draws what it
 * is given, in the order it is given.
 */
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
              // A GAP AND NOT A ZERO-LENGTH BAR. A bar of length zero is indistinguishable from a
              // measured zero, which is the exact confusion this whole page is built to avoid.
              <GapBand
                hatchId={hatchId}
                x={labelW}
                width={barMax}
                top={y + 3}
                height={14}
                reason={row.withheldBecause ?? "Withheld: too few identified accounts used this screen in this window."}
              />
            ) : (
              <g className={PRIMARY_INK}>
                <rect
                  x={labelW}
                  y={y + 3}
                  // 4px rounded data-end, anchored to the baseline at x = labelW.
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
              className={withheld ? "text-ink-300" : "text-ink-900"}
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

/* ────────────────────────────────────────────────────────────────────────────
 * Percentile range plot — p50, p95, p99 per screen
 * ──────────────────────────────────────────────────────────────────────────── */

export type PercentileRow = {
  label: string;
  p50: number | null;
  p95: number | null;
  p99: number | null;
  withheld: boolean;
  withheldBecause?: string;
  /** True where the screen simply had no traffic — a THIRD state, not a withheld one. */
  noTraffic: boolean;
};

/**
 * The distribution of server time per screen, as a range from p50 to p99 with a marker at each
 * percentile — one shared axis for every row.
 *
 * **WHY THIS FORM AND NOT THREE BARS PER SCREEN.** The question a reader has is "how much worse is
 * the tail than the middle", and that is a DISTANCE. A range plot draws the distance directly;
 * grouped bars make the reader measure the difference between two bar lengths, and they need three
 * hues to tell the percentiles apart, which this repository's one-accent rule does not allow and
 * which would be the wrong encoding anyway — p50, p95 and p99 are ORDERED, so an ordinal ramp is
 * the correct answer rather than a compromise.
 *
 * ONE AXIS FOR ALL ROWS, deliberately. Per-row axes would make a 40 ms screen and a 4-second screen
 * draw identical bars, which is the same lie a truncated axis tells.
 */
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
                    ? "No traffic on this screen in this window, so there is no distribution to report. That is not the same fact as a withheld one."
                    : "Withheld: too few identified accounts used this screen in this window.")
                }
              />
            ) : (
              <>
                {/* The track carries the distance p50 → p99; the markers carry the three values. */}
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
              className={unusable ? "text-ink-300" : "text-ink-900"}
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

/* ────────────────────────────────────────────────────────────────────────────
 * Stacked bar — the client split
 * ──────────────────────────────────────────────────────────────────────────── */

export type StackSegment = { label: string; value: number | null; withheld: boolean; withheldBecause?: string };

/**
 * Part-to-whole across three or four clients, as one stacked bar.
 *
 * A **2px card-coloured gap** sits between segments so two adjacent fills never read as one, and a
 * segment is only drawn when it has something in it — a zero-width sliver with a border is a mark
 * that says "a little" when the answer is "none". This is `OutcomeBar` on `/admin/analytics`, and
 * deliberately so: two proportion bars in one product should not be two different objects.
 *
 * **A WITHHELD CLIENT IS NOT DRAWN INTO THE BAR AT ALL**, and its absence is stated underneath
 * rather than silently closing the gap. Folding it in at zero would make the remaining shares add to
 * a whole that excludes it while looking like the whole.
 */
export function UsageStackedBar({ segments, ariaLabel }: { segments: StackSegment[]; ariaLabel: string }) {
  const drawable = segments.filter((segment) => !segment.withheld && (segment.value ?? 0) > 0);
  const total = drawable.reduce((sum, segment) => sum + (segment.value ?? 0), 0);

  if (!total) {
    return (
      <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-500">
        No requests were recorded from any client in this window, so there is no split to draw.
      </p>
    );
  }

  return (
    <div>
      <div className="flex h-3 w-full gap-[2px] overflow-hidden rounded-full" role="img" aria-label={ariaLabel}>
        {drawable.map((segment, index) => (
          <span
            key={segment.label}
            // The ordinal step, wrapping round if a deployment ever reports a fourth client — the
            // server appends anything stored outside its three known values rather than dropping it.
            className={ORDINAL_BG[index % ORDINAL_BG.length]}
            style={{ width: `${(((segment.value ?? 0) / total) * 100).toFixed(2)}%` }}
            title={`${segment.label}: ${COUNT.format(segment.value ?? 0)} requests`}
          />
        ))}
      </div>
      {/* DIRECT LABELS, so identity never rests on the fill. */}
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
