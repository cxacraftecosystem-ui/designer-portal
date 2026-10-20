import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { decodePolyline, indiaRings } from "@/components/map/indiaGeometry";
import { decodeRing, decodeShapes } from "@/components/map/shapeGeometry";
import { clusterMarkers } from "@/components/map/coverage/CoverageMap";
import {
  binLabels,
  binOf,
  coverageHref,
  coverageQueryFromParams,
  fyLabel,
  metricFromParams,
  metricValue,
  polygonFills,
  quantileBreaks
} from "@/components/map/coverage/coverageMath";
import type { CoverageUnit } from "@/components/map/coverage/coverageTypes";

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
  expect(binLabels(breaks)[0]).toBe("1–2");
  expect(binLabels(breaks)[4]).toBe("9–10");
  expect(binLabels([1])).toEqual(["1"]);
});

test("financial years: start-year labels", () => {
  expect(fyLabel(2025)).toBe("2025-26");
  expect(fyLabel(2099)).toBe("2099-00");
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
  expect(metricValue(unit({}).counts, "workshops")).toBe(3);
  expect(metricValue(unit({}).counts, "artisans")).toBe(14);
  expect(metricValue(unit({}).counts, "all")).toBe(2 + 1 + 14 + 5 + 3 + 40);
});

test("a district without a polygon is drawn inside its parent", () => {
  const shapes = [{ lgd: 90, stateLgd: 8 }];
  const guest = unit({ districtLgd: 775, district: "Balotra", polygon: "PARENT", parentLgd: 90 });
  const fills = polygonFills([unit({}), guest], "DISTRICT", "workshops", shapes);
  expect(fills.get(90)).toEqual({ value: 6, hostsChild: true });
  expect(polygonFills([], "DISTRICT", "workshops", shapes).size).toBe(0);
});

test("markers fold where they crowd, at their averaged position", () => {
  const marker = (id: string, lon: number) => ({
    kind: "DESIGN_WORKSHOP" as const, id, title: id, clusterName: null, craftCode: null,
    stateLgd: 8, districtLgd: 90, lon, lat: 25.9, precision: "DISTRICT_CENTROID" as const, href: `/design-workshops/${id}`
  });
  const folded = clusterMarkers([marker("a", 71.6), marker("b", 71.6001)]);
  expect(folded).toHaveLength(1);
  expect(folded[0].markers).toHaveLength(2);
  expect(clusterMarkers([marker("a", 71.6), marker("b", 80)])).toHaveLength(2);
});

test("the URL round-trips every filter and keeps view=coverage", () => {
  const params = new URLSearchParams("view=coverage&craftCode=ajrakh,bagru&fy=2025&scheme=OTHER&stateLgd=8&level=STATE&metric=artisans&peer=1");
  const query = coverageQueryFromParams(params);
  expect(query).toEqual({
    craftCode: ["ajrakh", "bagru"], scheme: ["OTHER"], fy: [2025], stateLgd: 8, level: "STATE", peer: true
  });
  const metric = metricFromParams(params);
  expect(metric).toBe("artisans");
  const back = new URL(coverageHref(query, metric), "http://x").searchParams;
  expect(back.get("view")).toBe("coverage");
  expect(coverageQueryFromParams(back)).toEqual(query);
  expect(metricFromParams(back)).toBe("artisans");
  expect(coverageHref(coverageQueryFromParams(new URLSearchParams()), "workshops")).toBe("/map?view=coverage");
});

test("a saved ?metric=records link opens on All records, and is written back as metric=all", () => {
  const params = new URLSearchParams("view=coverage&metric=records");
  expect(metricFromParams(params)).toBe("all");
  expect(coverageHref(coverageQueryFromParams(params), metricFromParams(params))).toBe("/map?view=coverage&metric=all");
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
