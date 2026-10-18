"use client";

import type { CoverageUnit } from "@/components/map/coverage/coverageTypes";

type Props = {
  units: CoverageUnit[];
  showPeer: boolean;
  onOpen: (unit: CoverageUnit) => void;
};

/**
 * The text version of the map, standing in for the `aria-hidden` SVG the way `MapPlaceList` does:
 * every unit with a count, state then district, each a button that opens the same panel a click on
 * the map opens. The Field Repository's figures are their own row, never added to ours.
 */
export function CoverageTable({ units, showPeer, onOpen }: Props) {
  if (!units.length) {
    return <p className="text-sm text-ink-500">Nothing recorded under these filters.</p>;
  }
  const sorted = [...units].sort(
    (a, b) => a.state.localeCompare(b.state) || (a.district ?? "").localeCompare(b.district ?? "")
  );
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[36rem] text-sm" data-testid="coverage-table">
        <caption className="sr-only">Coverage by state and district</caption>
        <thead>
          <tr className="border-b border-line-200 text-left text-xs text-ink-500">
            <th scope="col" className="py-2 pr-3 font-medium">Place</th>
            <th scope="col" className="px-2 py-2 text-right font-medium">Held</th>
            <th scope="col" className="px-2 py-2 text-right font-medium">Planned</th>
            <th scope="col" className="px-2 py-2 text-right font-medium">Field workshops</th>
            <th scope="col" className="px-2 py-2 text-right font-medium">Artisans</th>
            <th scope="col" className="px-2 py-2 text-right font-medium">Crafts</th>
            <th scope="col" className="px-2 py-2 text-right font-medium">Records</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((unit) => {
            const c = unit.counts;
            const key = `${unit.stateLgd}:${unit.districtLgd ?? 0}`;
            return [
              <tr key={key} className="border-b border-line-200">
                <th scope="row" className="py-1.5 pr-3 text-left font-normal">
                  <button
                    type="button"
                    className="text-left text-ink-900 underline-offset-2 hover:underline"
                    onClick={() => onOpen(unit)}
                  >
                    {unit.district ? `${unit.district}, ${unit.state}` : unit.state}
                  </button>
                  {unit.polygon === "PARENT" ? <span className="ml-1 text-xs text-ink-500">(in parent)</span> : null}
                </th>
                <td className="px-2 text-right tabular-nums">{c.designWorkshops}</td>
                <td className="px-2 text-right tabular-nums">{c.plannedWorkshops}</td>
                <td className="px-2 text-right tabular-nums">{c.workshops}</td>
                <td className="px-2 text-right tabular-nums">{c.artisans}</td>
                <td className="px-2 text-right tabular-nums">{c.crafts}</td>
                <td className="px-2 text-right tabular-nums">{c.products + c.tools + c.media}</td>
              </tr>,
              showPeer && unit.peer ? (
                <tr key={`${key}:peer`} className="border-b border-line-200 bg-surface-50 text-ink-500">
                  <th scope="row" className="py-1 pr-3 pl-4 text-left text-xs font-normal">Field Repository</th>
                  <td className="px-2 text-right text-xs">–</td>
                  <td className="px-2 text-right text-xs">–</td>
                  <td className="px-2 text-right text-xs tabular-nums">{unit.peer.workshops}</td>
                  <td className="px-2 text-right text-xs tabular-nums">{unit.peer.artisans}</td>
                  <td className="px-2 text-right text-xs">–</td>
                  <td className="px-2 text-right text-xs tabular-nums">
                    {unit.peer.products + unit.peer.tools + unit.peer.media}
                  </td>
                </tr>
              ) : null
            ];
          })}
        </tbody>
      </table>
    </div>
  );
}
