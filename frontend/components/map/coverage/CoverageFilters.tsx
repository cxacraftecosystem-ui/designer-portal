"use client";

import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import {
  METRIC_LABEL,
  METRICS,
  type CoverageResponse,
  type Metric
} from "@/components/map/coverage/coverageTypes";

export type CoverageFilterState = {
  craftCode: string[];
  scheme: string[];
  fy: number[];
  stateLgd: number | null;
  level: "STATE" | "DISTRICT";
  metric: Metric;
  peer: boolean;
};

type Props = {
  value: CoverageFilterState;
  onChange: (next: CoverageFilterState) => void;
  catalogue: CoverageResponse["catalogue"] | null;
  states: Array<{ lgd: number; name: string }>;
  peerAvailable: boolean;
};

const pill = (on: boolean) =>
  `min-h-9 rounded-full border px-3 text-sm transition ${
    on
      ? "border-purple-700 bg-purple-700 text-white dark:border-purple-400 dark:bg-purple-400 dark:text-purple-950"
      : "border-line-200 bg-card text-ink-900 hover:border-purple-300 hover:bg-purple-50"
  }`;

export function CoverageFilters({ value, onChange, catalogue, states, peerAvailable }: Props) {
  const set = (patch: Partial<CoverageFilterState>) => onChange({ ...value, ...patch });
  const crafts = (catalogue?.crafts ?? []).map((c) => ({ value: c.code, label: c.name }));
  const years = (catalogue?.years ?? []).map((y) => ({ value: String(y.fy), label: `FY ${y.label}` }));
  const stateOptions = states.map((s) => ({ value: String(s.lgd), label: s.name }));

  return (
    <div className="grid min-w-0 grid-cols-1 gap-4 rounded-md border border-line-200 bg-surface-50 p-3" data-testid="coverage-filters">
      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        <div className="grid min-w-0 gap-1.5">
          <span className="field-label">Craft</span>
          <SearchableMultiSelect
            values={value.craftCode}
            onChange={(craftCode) => set({ craftCode })}
            options={crafts}
            placeholder="All crafts"
            ariaLabel="Craft"
            searchable
            bulk={false}
          />
        </div>
        <div className="grid min-w-0 gap-1.5">
          <span className="field-label">Financial year</span>
          <SearchableSelect
            value={value.fy[0] ? String(value.fy[0]) : ""}
            onChange={(fy) => set({ fy: fy ? [Number(fy)] : [] })}
            options={years}
            placeholder="All years"
            ariaLabel="Financial year"
            clearable
            advanceOnSelect={false}
          />
        </div>
        <div className="grid min-w-0 gap-1.5">
          <span className="field-label">State</span>
          <SearchableSelect
            value={value.stateLgd ? String(value.stateLgd) : ""}
            onChange={(code) => set({ stateLgd: code ? Number(code) : null })}
            options={stateOptions}
            placeholder="All of India"
            ariaLabel="State"
            clearable
            advanceOnSelect={false}
          />
        </div>
      </div>

      {catalogue?.schemes.length ? (
        <div className="grid gap-1.5">
          <span className="field-label">Scheme</span>
          <div role="group" aria-label="Scheme" className="flex flex-wrap gap-2">
            {catalogue.schemes.map((scheme) => {
              const on = value.scheme.includes(scheme.value);
              return (
                <button
                  key={scheme.value}
                  type="button"
                  aria-pressed={on}
                  className={pill(on)}
                  onClick={() =>
                    set({
                      scheme: on ? value.scheme.filter((s) => s !== scheme.value) : [...value.scheme, scheme.value]
                    })
                  }
                >
                  {scheme.label}
                </button>
              );
            })}
          </div>
        </div>
      ) : null}

      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <div className="grid gap-1.5">
          <span className="field-label">Shade by</span>
          <div role="group" aria-label="Shade by" className="flex flex-wrap gap-2">
            {METRICS.map((metric) => (
              <button
                key={metric}
                type="button"
                aria-pressed={value.metric === metric}
                className={pill(value.metric === metric)}
                onClick={() => set({ metric })}
              >
                {METRIC_LABEL[metric]}
              </button>
            ))}
          </div>
        </div>
        <div className="grid gap-1.5">
          <span className="field-label">Group by</span>
          <div role="group" aria-label="Group by" className="flex flex-wrap gap-2">
            {(["DISTRICT", "STATE"] as const).map((level) => (
              <button
                key={level}
                type="button"
                aria-pressed={value.level === level}
                className={pill(value.level === level)}
                onClick={() => set({ level })}
              >
                {level === "DISTRICT" ? "District" : "State"}
              </button>
            ))}
          </div>
        </div>
        {peerAvailable ? (
          <Toggle
            className="min-w-[16rem] flex-1"
            label="Field Repository"
            description="Show its figures beside ours."
            checked={value.peer}
            onChange={(peer) => set({ peer })}
            testId="coverage-peer-toggle"
          />
        ) : null}
      </div>
    </div>
  );
}
