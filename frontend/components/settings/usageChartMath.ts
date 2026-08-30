/**
 * THE PURE HALF OF THE USAGE FIGURES — axis arithmetic and the null-splitting, with no JSX in it.
 *
 * ── WHY THIS IS A SEPARATE `.ts` FILE AND NOT A FEW HELPERS INSIDE `usageCharts.tsx` ────────────
 *
 * The same split, for the same reason, as `components/ui/selectFilter.ts` and
 * `components/data/cappedList.ts`: **there is no React renderer in devDependencies**, so a judgement
 * that lives inside a component is only ever exercised by somebody looking at a screen. The
 * judgements below are the ones this whole page turns on — whether a withheld bucket becomes a
 * plotted zero, and whether an axis starts where it must — and those are exactly the judgements that
 * must be pinned by a test rather than by a reviewer's eye. `e2e/usage-figures-unit.spec.ts` calls
 * these directly.
 *
 * ── THE ONE RULE EVERYTHING HERE ENFORCES ──────────────────────────────────────────────────────
 *
 * **`null` IS NOT A NUMBER AND MUST NEVER BECOME ONE.** Every aggregate under `/api/usage` uses
 * `null` for a figure the server declined to state, and `null` becomes `0` through `+`, through
 * `??`, through `Number()`, through `Math.max` and through a sort comparator. Each function below
 * takes `number | null` and drops the nulls rather than coercing them, so the compiler is on the
 * same side of the rule as the comment.
 */

/** A round axis step covering `span` in roughly `targetTicks` steps — copied from
 *  `ReportChart.tsx`'s `niceStep`, which is itself a copy of `report_chart.py`'s `_nice_step`, so
 *  three drawings of one dataset round their axes identically. An axis stepping by 7 is legible and
 *  useless: nobody reads the third gridline as 21. */
export function niceStep(span: number, targetTicks: number): number {
  if (span <= 0 || targetTicks <= 0) return 1;
  const raw = span / targetTicks;
  const power = raw > 0 ? 10 ** Math.floor(Math.log10(raw)) : 1;
  for (const multiplier of [1, 2, 2.5, 5, 10]) {
    if (raw <= multiplier * power) return multiplier * power;
  }
  return 10 * power;
}

/**
 * `[high, step]` for a value axis that **ALWAYS INCLUDES ZERO** and steps by a round number.
 *
 * Zero is not negotiable and it is the most important line in this module. An axis that starts at 40
 * makes a 3% difference look like a threefold one — the commonest way a figure misleads without
 * containing a single wrong number — and the readers of this page are about to quote it into a
 * methods section.
 *
 * **NULLS ARE DROPPED, NOT COERCED.** A withheld bucket must not participate in the axis at all:
 * reading it as zero would drag the baseline of a chart it is not even drawn on, and on a series
 * that is entirely withheld it would produce a 0-to-0 axis with a gridline at every pixel. An
 * all-null or all-zero series returns `[1, 1]` — one gridline above the baseline — which draws an
 * honest empty chart rather than dividing by zero.
 */
export function axisTop(values: Array<number | null>): [number, number] {
  const real = values.filter((value): value is number => value !== null && Number.isFinite(value));
  const high0 = Math.max(...real, 0);
  if (!real.length || high0 <= 0) return [1, 1];
  const step = niceStep(high0, 4);
  return [Math.ceil(high0 / step) * step, step];
}

/** 12,34,567 — the Indian grouping every other figure on this page is printed in, so a reader
 *  comparing a chart label with a table cell never has to convert between two conventions. */
const COUNT = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

/** One axis tick, in the unit the chart is drawn in. Presentation only: the value itself is always
 *  the server's, and nothing here derives a figure from another figure. */
export function tickText(value: number, unit: "count" | "percent" | "ms"): string {
  if (unit === "percent") return `${(value * 100).toFixed(value < 0.1 ? 1 : 0)}%`;
  if (unit === "ms") return `${COUNT.format(Math.round(value))} ms`;
  return COUNT.format(Math.round(value));
}

export type SeriesPoint = { value: number | null; withheld: boolean };

/**
 * A time series split into RUNS OF CONSECUTIVE STATED POINTS.
 *
 * **THIS IS THE FUNCTION THAT KEEPS A LINE CHART HONEST, AND IT IS THE ONE THAT LOOKS LIKE AN
 * OPTIMISATION.** The obvious implementation is one `d` string that skips the missing points — and
 * that draws a confident straight line from the point before a gap to the point after it, straight
 * across exactly the region where the server said it would not give a figure. A reader sees an
 * interpolated trend; the data says "not stated". So every unstated point ENDS a run, and the
 * renderer draws one `<path>` per run with nothing between them.
 *
 * Both kinds of missing end a run, and they are not the same fact: `withheld` is "too few identified
 * accounts for the server to report this", and a bare `null` is "there was nothing to compute a rate
 * from". The caller renders different sentences for the two; both are gaps.
 *
 * Returns arrays of INDICES into the original series, so the caller owns the coordinate system and
 * this module needs to know nothing about pixels.
 */
export function statedRuns(points: SeriesPoint[]): number[][] {
  const runs: number[][] = [];
  let run: number[] = [];
  points.forEach((point, index) => {
    if (point.value === null || point.value === undefined || point.withheld) {
      if (run.length) runs.push(run);
      run = [];
      return;
    }
    run.push(index);
  });
  if (run.length) runs.push(run);
  return runs;
}

/**
 * How often to print an x-axis label, so 168 hourly ticks do not overprint into a grey band.
 *
 * At most twelve labels, whatever the bucket count. The count of buckets is printed beside the chart
 * either way, so this thins the drawing and never the stated facts.
 */
export function labelStride(count: number, maxLabels = 12): number {
  return Math.max(1, Math.ceil(count / maxLabels));
}
