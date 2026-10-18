"use client";

import { ChevronDown } from "lucide-react";

import type { CoverageGaps } from "@/components/map/coverage/coverageTypes";

/** Crafts nobody has documented, and the districts no workshop has reached, by state. */
export function CoverageGapsPanel({ gaps, peerIncluded }: { gaps: CoverageGaps; peerIncluded: boolean }) {
  const crafts = gaps.craftsNeverDocumented;
  const byState = gaps.districtsWithoutWorkshop.byState;
  return (
    <section className="panel grid gap-4 p-4" aria-labelledby="coverage-gaps-title" data-testid="coverage-gaps">
      <h2 id="coverage-gaps-title" className="display-title text-lg">
        Gaps
      </h2>

      <details className="group" open={crafts.length > 0 && crafts.length <= 12}>
        <summary className="flex cursor-pointer list-none items-center justify-between gap-3 text-sm font-medium text-ink-900">
          <span>
            Crafts never documented
            {peerIncluded ? <span className="ml-1 text-ink-500">(both portals)</span> : null}
          </span>
          <span className="flex items-center gap-2">
            <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs tabular-nums" data-testid="gap-crafts-count">
              {crafts.length}
            </span>
            <ChevronDown className="h-4 w-4 transition group-open:rotate-180" aria-hidden />
          </span>
        </summary>
        {crafts.length ? (
          <ul className="mt-2 flex flex-wrap gap-1.5">
            {crafts.map((craft) => (
              <li key={craft.code} className="rounded-full border border-line-200 px-2.5 py-0.5 text-xs text-ink-700">
                {craft.name}
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-2 text-sm text-ink-500">Every shared craft has a record.</p>
        )}
      </details>

      <div className="grid gap-2">
        <p className="flex items-center justify-between gap-3 text-sm font-medium text-ink-900">
          <span>Districts without a workshop</span>
          <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs tabular-nums" data-testid="gap-districts-count">
            {gaps.districtsWithoutWorkshop.count}
          </span>
        </p>
        <ul className="grid gap-1">
          {byState.map((row) => (
            <li key={row.stateLgd}>
              <details className="group rounded-md border border-line-200">
                <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-2 text-sm">
                  <span className="min-w-0 truncate text-ink-900">{row.state}</span>
                  <span className="flex shrink-0 items-center gap-2 text-xs tabular-nums text-ink-500">
                    {row.without.length} of {row.total}
                    <ChevronDown className="h-4 w-4 transition group-open:rotate-180" aria-hidden />
                  </span>
                </summary>
                <p className="px-3 pb-2 text-xs leading-5 text-ink-700">
                  {row.without.map((d) => d.name).join(", ")}
                </p>
              </details>
            </li>
          ))}
        </ul>
        {gaps.statesWithoutWorkshop.length ? (
          <p className="text-xs text-ink-500">
            {gaps.statesWithoutWorkshop.length === 1
              ? "1 state or union territory has no workshop yet."
              : `${gaps.statesWithoutWorkshop.length} states and union territories have no workshop yet.`}
          </p>
        ) : null}
      </div>
    </section>
  );
}
