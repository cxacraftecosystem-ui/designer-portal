import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { decodePolyline, indiaRings } from "@/components/map/indiaGeometry";
import { decodeRing, decodeShapes } from "@/components/map/shapeGeometry";
import { clusterMarkers, shadeShapes } from "@/components/map/coverage/coverageShade";
import {
  binLabel,
  binOf,
  fillIndex,
  fyLabel,
  fyOf,
  metricValue,
  quantileBreaks,
  type CoverageUnit
} from "@/components/map/coverage/coverageTypes";
import { filtersFromParams, paramsFromFilters } from "@/components/map/coverage/coverageUrl";

/*
  THE COVERAGE MAP'S PURE HALF: the shape decoder (the outline's own scheme, reused rather than
  copied), quantile bins, financial-year labels, the URL round trip, and the rule that the coverage
  bundle never reaches for a tile server or a mapping library.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

/** The build's encoder, restated: a ring as one record, no hole flag, deltas from zero. */
function encode(points: Array<[number, number]>): string {
  const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  let out = "";
  const varint = (value: number) => {
    let zig = value < 0 ? ~(value << 1) : value << 1;
    for (;;) {
      const chunk = zig & 0x1f;
      zig >>= 5;
      out += ALPHABET[chunk | (zig ? 0x20 : 0)];
      if (!zig) return;
    }
  };
  let x = 0;
  let y = 0;
  varint(points.length);
  for (const [lon, lat] of points) {
    const qx = Math.round(lon * 1000);
    const qy = Math.round(lat * 1000);
    varint(qx - x);
    varint(qy - y);
    x = qx;
    y = qy;
  }
  return out;
}

test("a district ring round-trips through the outline's decoder", () => {
  const ring: Array<[number, number]> = [
    [71.392, 25.746],
    [71.9, 25.746],
    [71.9, 26.1],
    [71.392, 26.1]
  ];
  const decoded = decodeRing(encode(ring));
  expect(decoded).toEqual([...ring, ring[0]]);
  expect(decodePolyline(encode(ring), false)).toHaveLength(1);
  const shapes = decodeShapes({ version: "x", source: "test", asOf: "2026-10-18", scale: 1000, districts: [{ lgd: 90, stateLgd: 8, rings: [encode(ring)], centroid: [71.6, 25.9] }] });
  expect(shapes[0].rings[0]).toHaveLength(5);
});

test("the national outline still decodes after the decoder was exported", () => {
  const rings = indiaRings();
  expect(rings.length).toBe(307);
  expect(rings.reduce((n, polygon) => n + polygon.length, 0)).toBe(308);
});

test("the committed shapes file decodes, when it is present", () => {
  const path = join(ROOT, "public/boundaries/district-shapes.json");
  test.skip(!existsSync(path), "no shapes file in this checkout");
  const file = JSON.parse(readFileSync(path, "utf8"));
  const shapes = decodeShapes(file);
  expect(shapes.length).toBeGreaterThan(700);
  for (const shape of shapes.slice(0, 40)) expect(shape.rings.length).toBeGreaterThan(0);
});

test("quantile bins: zero is never a bin, duplicates collapse, five at most", () => {
  expect(quantileBreaks([0, 0, 0])).toEqual([]);
  expect(quantileBreaks([1, 1, 1, 1])).toEqual([1]);
  const breaks = quantileBreaks([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  expect(breaks).toEqual([2, 4, 6, 8, 10]);
  expect(binOf(0, breaks)).toBe(-1);
  expect(binOf(1, breaks)).toBe(0);
  expect(binOf(5, breaks)).toBe(2);
  expect(binOf(99, breaks)).toBe(4);
  expect(binLabel(0, breaks)).toBe("1–2");
  expect(binLabel(4, breaks)).toBe("9–10");
  expect(binLabel(0, [1])).toBe("1");
  // Fewer bins than fills take the dark end, so a single figure is not drawn in the palest step.
  expect(fillIndex(0, [1])).toBe(4);
  expect(fillIndex(0, breaks)).toBe(0);
  expect(fillIndex(-1, breaks)).toBe(-1);
});

test("financial years: start-year labels, and the turn at 00:00 IST on 1 April", () => {
  expect(fyLabel(2025)).toBe("2025-26");
  expect(fyLabel(2099)).toBe("2099-00");
  expect(fyOf(new Date("2026-03-31T18:29:00Z"))).toBe(2025);
  expect(fyOf(new Date("2026-03-31T18:30:00Z"))).toBe(2026);
});

const unit = (patch: Partial<CoverageUnit>): CoverageUnit => ({
  stateLgd: 8,
  districtLgd: 90,
  state: "Rajasthan",
  district: "Barmer",
  polygon: "OWN",
  parentLgd: null,
  counts: { designWorkshops: 2, plannedWorkshops: 3, workshops: 1, artisans: 14, crafts: 2, products: 5, tools: 3, media: 40 },
  peer: { workshops: 9, artisans: 99, products: 0, tools: 0, media: 0 },
  craftCodes: [],
  clusters: [],
  ...patch
});

test("planned workshops and the Field Repository are never added into a shade", () => {
  expect(metricValue(unit({}), "workshops")).toBe(3);
  expect(metricValue(unit({}), "artisans")).toBe(14);
  expect(metricValue(unit({}), "records")).toBe(2 + 1 + 14 + 5 + 3 + 40);
});

test("a district without a polygon is drawn inside its parent and named as a guest", () => {
  const shapes = [{ lgd: 90, stateLgd: 8, rings: [], centroid: [71.6, 25.9] as [number, number] }];
  const guest = unit({ districtLgd: 775, district: "Balotra", polygon: "PARENT", parentLgd: 90 });
  const shaded = shadeShapes(shapes, [unit({}), guest], "workshops", [6], "DISTRICT");
  expect(shaded[0].value).toBe(6);
  expect(shaded[0].guests.map((g) => g.district)).toEqual(["Balotra"]);
  const empty = shadeShapes(shapes, [], "workshops", [], "DISTRICT");
  expect(empty[0].noWorkshop).toBe(true);
  expect(empty[0].bin).toBe(-1);
});

test("markers fold where they crowd", () => {
  const marker = (id: string, lon: number) => ({
    kind: "DESIGN_WORKSHOP" as const, id, title: id, clusterName: null, craftCode: null,
    stateLgd: 8, districtLgd: 90, lon, lat: 25.9, precision: "DISTRICT_CENTROID" as const, href: `/design-workshops/${id}`
  });
  expect(clusterMarkers([marker("a", 71.6), marker("b", 71.6001)])).toHaveLength(1);
  expect(clusterMarkers([marker("a", 71.6), marker("b", 80)])).toHaveLength(2);
});

test("the URL round-trips every filter and keeps view=coverage", () => {
  const params = new URLSearchParams("view=coverage&craftCode=ajrakh,bagru&fy=2025&scheme=OTHER&stateLgd=8&level=STATE&metric=artisans&peer=1");
  const filters = filtersFromParams(params);
  expect(filters).toEqual({
    craftCode: ["ajrakh", "bagru"], scheme: ["OTHER"], fy: [2025], stateLgd: 8,
    level: "STATE", metric: "artisans", peer: true
  });
  const back = paramsFromFilters(filters);
  expect(back.get("view")).toBe("coverage");
  expect(filtersFromParams(back)).toEqual(filters);
  expect(paramsFromFilters(filtersFromParams(new URLSearchParams())).toString()).toBe("view=coverage");
});

test("the coverage bundle reaches for no tile server and no mapping library", () => {
  const dir = join(ROOT, "components/map/coverage");
  const files = [...readdirSync(dir).map((f) => `components/map/coverage/${f}`), "components/map/shapeGeometry.ts"];
  for (const file of files) {
    const text = read(file);
    expect(text, file).not.toMatch(/maplibre|maptiler|mapbox|leaflet|googleapis|tile\.openstreetmap/i);
  }
});

test("the caption names the official depiction", () => {
  expect(read("components/map/coverage/CoverageView.tsx")).toContain(
    "External boundaries of India as depicted by Survey of India. District boundaries are indicative."
  );
});
