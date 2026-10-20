"use client";

/**
 * The coverage map as text: every unit with a record, state then district, with its counts. It
 * stands in for the SVG (which is `aria-hidden`) the way `MapPlaceList` does for the Places map,
 * and a row opens the same unit panel a click on the map does.
 *
 * The Field Repository's table. "Workshops" here is held design workshops plus field workshops (the
 * map's own metric); a Planned column appears when any place has one, and the Field Repository's
 * figures sit on their own row under ours, never added in.
 */

import { unitTitle } from "@/components/map/coverage/CoverageUnitPanel";
import { metricValue } from "@/components/map/coverage/coverageMath";
import type { CoverageUnit } from "@/components/map/coverage/coverageTypes";

export function unitKey(unit: Pick<CoverageUnit, "stateLgd" | "districtLgd">): string {
  return `${unit.stateLgd}:${unit.districtLgd ?? 0}`;
}

export function CoverageTable({
  units,
  selectedKey,
  level,
  onSelect,
  showPeer = false
}: {
  units: CoverageUnit[];
  level: "STATE" | "DISTRICT";
  selectedKey: string | null;
  onSelect: (unit: CoverageUnit) => void;
  showPeer?: boolean;
}) {
  if (!units.length) {
    return <p className="text-xs text-ink-500">No records under these filters.</p>;
  }
  const planned = units.some((unit) => (unit.counts.plannedWorkshops ?? 0) > 0);
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm" data-testid="coverage-table">
        <caption className="sr-only">Records per place</caption>
        <thead className="text-[11px] uppercase tracking-wide text-ink-500">
          <tr>
            <th scope="col" className="py-1.5 pr-2 font-medium">Place</th>
            <th scope="col" className="px-1 py-1.5 text-right font-medium">Workshops</th>
            {planned ? <th scope="col" className="px-1 py-1.5 text-right font-medium">Planned</th> : null}
            <th scope="col" className="px-1 py-1.5 text-right font-medium">Artisans</th>
            <th scope="col" className="px-1 py-1.5 text-right font-medium">Crafts</th>
            <th scope="col" className="py-1.5 pl-1 text-right font-medium">Records</th>
          </tr>
        </thead>
        <tbody>
          {units.map((unit) => {
            const key = unitKey(unit);
            const records = unit.counts.products + unit.counts.tools + unit.counts.media;
            const peer = showPeer ? unit.peer : null;
            return [
              <tr
                key={key}
                className={`border-t border-line-200 ${selectedKey === key ? "bg-purple-50 dark:bg-purple-950/40" : ""}`}
              >
                <th scope="row" className="py-1.5 pr-2 font-normal">
                  <button
                    type="button"
                    onClick={() => onSelect(unit)}
                    className="text-left text-purple-700 hover:underline dark:text-purple-300"
                  >
                    {level === "STATE" ? unit.state : unitTitle(unit)}
                  </button>
                </th>
                <td className="px-1 py-1.5 text-right tabular-nums text-ink-700">
                  {metricValue(unit.counts, "workshops")}
                </td>
                {planned ? (
                  <td className="px-1 py-1.5 text-right tabular-nums text-ink-700">{unit.counts.plannedWorkshops ?? 0}</td>
                ) : null}
                <td className="px-1 py-1.5 text-right tabular-nums text-ink-700">{unit.counts.artisans}</td>
                <td className="px-1 py-1.5 text-right tabular-nums text-ink-700">{unit.counts.crafts}</td>
                <td className="py-1.5 pl-1 text-right tabular-nums text-ink-700">{records}</td>
              </tr>,
              peer ? (
                <tr key={`${key}:peer`} className="bg-surface-50 text-xs text-ink-500">
                  <th scope="row" className="py-1 pr-2 pl-3 font-normal">Field Repository</th>
                  <td className="px-1 py-1 text-right tabular-nums">{peer.workshops}</td>
                  {planned ? <td className="px-1 py-1 text-right">–</td> : null}
                  <td className="px-1 py-1 text-right tabular-nums">{peer.artisans}</td>
                  <td className="px-1 py-1 text-right">–</td>
                  <td className="py-1 pl-1 text-right tabular-nums">{peer.products + peer.tools + peer.media}</td>
                </tr>
              ) : null
            ];
          })}
        </tbody>
      </table>
    </div>
  );
}
