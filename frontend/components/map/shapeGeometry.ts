/**
 * District polygons for the coverage map, loaded only when the Coverage view opens.
 *
 * `public/boundaries/district-shapes.json` is written by `scripts/build_boundaries.py --shapes`:
 * every district the register codes and the boundary source draws, clipped to the verified national
 * outline (`indiaGeometry.ts`) and simplified at 0.01 degrees, in the same polyline encoding the
 * outline uses. The build refuses to write a file whose polygons leave the outline or put Gilgit,
 * Aksai Chin or Muzaffarabad in the wrong union territory, and the backend suite re-checks the
 * committed file.
 *
 * The national outline is NOT in this file. The map draws `indiaGeometry.ts` as a base fill and
 * clips every district fill to it, so any part of India without a district polygon still reads as
 * India.
 */

import { decodePolylines, type Ring } from "@/components/map/indiaGeometry";
import { project } from "@/components/map/projection";

export const SHAPES_URL = "/boundaries/district-shapes.json";

export type DistrictShape = {
  lgd: number;
  stateLgd: number;
  rings: Ring[];
  centroid: [number, number];
};

type ShapesFile = {
  version: string;
  scale: number;
  districts: Array<{ lgd: number; stateLgd: number; rings: string[]; centroid: [number, number] }>;
};

/** One encoded ring back to closed [lon, lat] points. */
export function decodeRing(encoded: string): Ring {
  const record = decodePolylines(encoded, false)[0];
  if (!record || !record.points.length) return [];
  return [...record.points, record.points[0]];
}

export function decodeShapes(file: ShapesFile): DistrictShape[] {
  return file.districts.map((row) => ({
    lgd: row.lgd,
    stateLgd: row.stateLgd,
    rings: row.rings.map(decodeRing).filter((ring) => ring.length >= 4),
    centroid: row.centroid
  }));
}

/** A district's rings as one even-odd SVG path, through the one projection the map uses. */
export function shapePath(rings: Ring[]): string {
  let d = "";
  for (const ring of rings) {
    ring.forEach(([lon, lat], index) => {
      const { x, y } = project(lon, lat);
      d += `${index === 0 ? "M" : "L"}${Math.round(x * 10) / 10} ${Math.round(y * 10) / 10}`;
    });
    d += "Z";
  }
  return d;
}

let pending: Promise<DistrictShape[]> | null = null;

/** Fetched once per session; a failed fetch is forgotten so the next open can retry. */
export function loadDistrictShapes(): Promise<DistrictShape[]> {
  if (pending) return pending;
  pending = fetch(SHAPES_URL, { cache: "force-cache" })
    .then((response) => {
      if (!response.ok) throw new Error(`${SHAPES_URL} returned ${response.status}`);
      return response.json() as Promise<ShapesFile>;
    })
    .then(decodeShapes)
    .catch((cause: unknown) => {
      pending = null;
      throw cause;
    });
  return pending;
}
