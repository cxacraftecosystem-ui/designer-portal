/** Which value each district polygon carries, and how markers fold where they crowd. Pure. */

import { project } from "@/components/map/projection";
import type { DistrictShape } from "@/components/map/shapeGeometry";
import {
  binOf,
  fillIndex,
  metricValue,
  type CoverageMarker,
  type CoverageUnit,
  type Metric
} from "@/components/map/coverage/coverageTypes";

export type ShadedShape = {
  shape: DistrictShape;
  value: number;
  bin: number;
  /** The fill step, see `fillIndex`; -1 is the "none" tone. */
  fill: number;
  /** Units drawn inside this polygon because their own district has none (lineage). */
  guests: CoverageUnit[];
  unit: CoverageUnit | null;
  noWorkshop: boolean;
};

/**
 * Which value each polygon carries. At DISTRICT level a polygon holds its own unit plus any unit
 * whose district is drawn inside it (`polygon: "PARENT"`). At STATE level every district of a state
 * carries the state's figure. Pure, so the unit spec can hold it.
 */
export function shadeShapes(
  shapes: DistrictShape[],
  units: CoverageUnit[],
  metric: Metric,
  breaks: number[],
  level: "STATE" | "DISTRICT"
): ShadedShape[] {
  const own = new Map<number, CoverageUnit>();
  const guests = new Map<number, CoverageUnit[]>();
  const byState = new Map<number, CoverageUnit>();
  for (const unit of units) {
    if (level === "STATE") {
      if (unit.districtLgd === null) byState.set(unit.stateLgd, unit);
      continue;
    }
    if (unit.districtLgd === null) continue;
    if (unit.polygon === "PARENT" && unit.parentLgd) {
      const list = guests.get(unit.parentLgd) ?? [];
      list.push(unit);
      guests.set(unit.parentLgd, list);
    } else if (unit.polygon === "OWN") {
      own.set(unit.districtLgd, unit);
    }
  }
  return shapes.map((shape) => {
    const unit = level === "STATE" ? byState.get(shape.stateLgd) ?? null : own.get(shape.lgd) ?? null;
    const visitors = level === "STATE" ? [] : guests.get(shape.lgd) ?? [];
    const all = [unit, ...visitors].filter((u): u is CoverageUnit => u !== null);
    const value = all.reduce((sum, u) => sum + metricValue(u, metric), 0);
    const held = all.reduce((sum, u) => sum + metricValue(u, "workshops"), 0);
    const bin = binOf(value, breaks);
    return { shape, value, bin, fill: fillIndex(bin, breaks), guests: visitors, unit, noWorkshop: held === 0 };
  });
}

export type Cluster = { x: number; y: number; markers: CoverageMarker[] };

/** Markers closer than `cell` user units fold into one counted mark. */
export function clusterMarkers(markers: CoverageMarker[], cell = 16): Cluster[] {
  const cells = new Map<string, Cluster>();
  for (const marker of markers) {
    const { x, y } = project(marker.lon, marker.lat);
    const key = `${Math.floor(x / cell)}:${Math.floor(y / cell)}`;
    const found = cells.get(key);
    if (found) found.markers.push(marker);
    else cells.set(key, { x, y, markers: [marker] });
  }
  return [...cells.values()];
}
