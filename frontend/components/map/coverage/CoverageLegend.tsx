"use client";

import { BIN_FILL } from "@/components/map/coverage/CoverageMap";
import { binLabel, fillIndex, METRIC_LABEL, type Metric } from "@/components/map/coverage/coverageTypes";

type Props = {
  metric: Metric;
  breaks: number[];
  parentNote: string | null;
  level?: "STATE" | "DISTRICT";
};

function Swatch({ className, hatched = false, dashed = false }: { className: string; hatched?: boolean; dashed?: boolean }) {
  return (
    <svg viewBox="0 0 16 12" className="h-3 w-4 shrink-0" aria-hidden="true" focusable="false">
      <defs>
        <pattern id="legend-hatch" width={4} height={4} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1={0} y1={0} x2={0} y2={4} className="stroke-ink-300" strokeWidth={1.2} />
        </pattern>
      </defs>
      <rect x={0.5} y={0.5} width={15} height={11} rx={2} className={className} />
      {hatched ? <rect x={0.5} y={0.5} width={15} height={11} rx={2} fill="url(#legend-hatch)" /> : null}
      {dashed ? (
        <rect x={0.5} y={0.5} width={15} height={11} rx={2} fill="none" className="stroke-ink-700" strokeDasharray="3 2" />
      ) : null}
    </svg>
  );
}

/** Five quantile bins, the "none" tone, and the two outline conventions the map uses. */
export function CoverageLegend({ metric, breaks, parentNote, level = "DISTRICT" }: Props) {
  return (
    <div className="grid gap-2 text-xs text-ink-700" data-testid="coverage-legend">
      <p className="field-label">{METRIC_LABEL[metric]} per {level === "STATE" ? "state" : "district"}</p>
      <ul className="flex flex-wrap gap-x-4 gap-y-1.5">
        {breaks.map((_, index) => (
          <li key={index} className="flex items-center gap-1.5">
            <Swatch className={BIN_FILL[fillIndex(index, breaks)]} />
            {binLabel(index, breaks)}
          </li>
        ))}
        <li className="flex items-center gap-1.5">
          {metric === "workshops" ? (
            <>
              <Swatch className="fill-field-200" hatched />
              No workshop
            </>
          ) : (
            <>
              <Swatch className="fill-field-200" />
              None
            </>
          )}
        </li>
        {parentNote ? (
          <li className="flex items-center gap-1.5">
            <Swatch className="fill-card" dashed />
            {parentNote}
          </li>
        ) : null}
      </ul>
    </div>
  );
}
