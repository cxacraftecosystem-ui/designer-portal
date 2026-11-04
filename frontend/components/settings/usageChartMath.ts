export function niceStep(span: number, targetTicks: number): number {
  if (span <= 0 || targetTicks <= 0) return 1;
  const raw = span / targetTicks;
  const power = raw > 0 ? 10 ** Math.floor(Math.log10(raw)) : 1;
  for (const multiplier of [1, 2, 2.5, 5, 10]) {
    if (raw <= multiplier * power) return multiplier * power;
  }
  return 10 * power;
}

export function axisTop(values: Array<number | null>): [number, number] {
  const real = values.filter((value): value is number => value !== null && Number.isFinite(value));
  const high0 = Math.max(...real, 0);
  if (!real.length || high0 <= 0) return [1, 1];
  const step = niceStep(high0, 4);
  return [Math.ceil(high0 / step) * step, step];
}

const COUNT = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

export function tickText(value: number, unit: "count" | "percent" | "ms"): string {
  if (unit === "percent") return `${(value * 100).toFixed(value < 0.1 ? 1 : 0)}%`;
  if (unit === "ms") return `${COUNT.format(Math.round(value))} ms`;
  return COUNT.format(Math.round(value));
}

export type SeriesPoint = { value: number | null; withheld: boolean };

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

export function labelStride(count: number, maxLabels = 12): number {
  return Math.max(1, Math.ceil(count / maxLabels));
}
