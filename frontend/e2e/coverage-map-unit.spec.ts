import { expect, test } from "@playwright/test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { CoverageMap, HoverLabel, type CoverageMapProps } from "@/components/map/coverage/CoverageMap";
import { binOf, polygonFills, quantileBreaks } from "@/components/map/coverage/coverageMath";
import type { CoverageUnit } from "@/components/map/coverage/coverageTypes";
import { project } from "@/components/map/projection";
import type { DistrictShape } from "@/components/map/shapeGeometry";

/*
  THE DESIGNER PORTAL'S COVERAGE MAP DRAWS AS THE FIELD REPOSITORY'S DOES. Rendered to markup from a
  fixture payload — no browser, no network — so the layer order, the fills and the focus are held
  without anybody looking at a screen.
*/

const square = (lon: number, lat: number, size = 0.5): Array<[number, number]> => [
  [lon, lat],
  [lon + size, lat],
  [lon + size, lat + size],
  [lon, lat + size],
  [lon, lat]
];

const shapes: DistrictShape[] = [
  { lgd: 90, stateLgd: 8, rings: [square(71, 25)], centroid: [71.25, 25.25] },
  { lgd: 91, stateLgd: 8, rings: [square(72, 25)], centroid: [72.25, 25.25] },
  { lgd: 92, stateLgd: 8, rings: [square(73, 25)], centroid: [73.25, 25.25] },
  { lgd: 200, stateLgd: 9, rings: [square(77, 28)], centroid: [77.25, 28.25] },
  { lgd: 201, stateLgd: 9, rings: [square(78, 28)], centroid: [78.25, 28.25] }
];

const unit = (stateLgd: number, districtLgd: number, workshops: number): CoverageUnit => ({
  stateLgd,
  districtLgd,
  state: `State ${stateLgd}`,
  district: `District ${districtLgd}`,
  polygon: "OWN",
  parentLgd: null,
  counts: { workshops, artisans: 0, crafts: 0, products: 0, tools: 0, media: 0, designWorkshops: 0, plannedWorkshops: 0 },
  peer: null,
  craftCodes: [],
  clusters: []
});

const units = [unit(8, 90, 1), unit(8, 91, 9), unit(9, 200, 4)];
const fills = polygonFills(units, "DISTRICT", "workshops", shapes);
const breaks = quantileBreaks([...fills.values()].map((fill) => fill.value));

function render(patch: Partial<CoverageMapProps> = {}): string {
  const props: CoverageMapProps = {
    shapes,
    fills,
    breaks,
    noWorkshop: new Set([92, 201]),
    markers: [],
    selectedLgd: null,
    stateLgd: null,
    names: new Map(shapes.map((shape) => [shape.lgd, `District ${shape.lgd}`])),
    onSelectPolygon: () => {},
    statePath: "M0 0L10 10",
    ...patch
  };
  return renderToStaticMarkup(createElement(CoverageMap, props));
}

function districtPath(markup: string, lgd: number): string {
  const match = markup.match(new RegExp(`<path[^>]*data-lgd="${lgd}"[^>]*>`));
  expect(match, `district ${lgd}`).not.toBeNull();
  return match![0];
}

test("a district with no figure is transparent over the base tone", () => {
  const markup = render();
  expect(districtPath(markup, 92)).toContain("fill-transparent");
  expect(districtPath(markup, 201)).toContain("fill-transparent");
});

test("the lowest bin is purple-300", () => {
  expect(binOf(1, breaks)).toBe(0);
  expect(districtPath(render(), 90)).toContain("fill-purple-300");
});

test("every no-workshop district is hatched by ONE path", () => {
  const markup = render();
  expect(markup.match(/data-testid="coverage-hatch"/g) ?? []).toHaveLength(1);
});

test("state borders are drawn outside the clip group, over the fills", () => {
  const markup = render();
  const group = markup.match(/<g clip-path="url\(#[^"]+\)" data-testid="coverage-districts">[\s\S]*?<\/g>/);
  expect(group).not.toBeNull();
  expect(group![0]).not.toContain("coverage-state-borders");
  const after = markup.indexOf("coverage-state-borders");
  expect(after).toBeGreaterThan(markup.indexOf(group![0]) + group![0].length - 1);
});

test("a focused state draws only its own districts", () => {
  const markup = render({ stateLgd: 9 });
  const drawn = [...markup.matchAll(/data-lgd="(\d+)"/g)].map((match) => Number(match[1])).sort();
  expect(drawn).toEqual([200, 201]);
  expect(markup).not.toContain('viewBox="0 0 ');
});

test("the hover label is centred above the district, and keeps its on-screen size", () => {
  const shape = shapes[0];
  const full = renderToStaticMarkup(createElement(HoverLabel, { shape, name: "Barmer", value: 6 }));
  expect(full).toContain('text-anchor="middle"');
  const { x } = project(shape.centroid[0], shape.centroid[1]);
  const textX = Number(full.match(/<text[^>]* x="([\d.]+)"/)![1]);
  expect(textX).toBeCloseTo(x, 3);
  const rectX = Number(full.match(/<rect[^>]* x="([\d.-]+)"/)![1]);
  const width = Number(full.match(/<rect[^>]* width="([\d.]+)"/)![1]);
  expect(rectX + width / 2).toBeCloseTo(x, 3);
  const zoomed = renderToStaticMarkup(createElement(HoverLabel, { shape, name: "Barmer", value: 6, scale: 0.25 }));
  expect(full).toContain("font-size:12px");
  expect(zoomed).toContain("font-size:3px");
});

test("planned and clustered markers wear their own rims; a design workshop is a purple disc", () => {
  const marker = (id: string, kind: "DESIGN_WORKSHOP" | "PLANNED" | "CLUSTER", lon: number) => ({
    kind, id, title: id, clusterName: null, craftCode: null, stateLgd: 8, districtLgd: 90, lon, lat: 25.2,
    precision: "DISTRICT_CENTROID" as const, href: `/x/${id}`
  });
  const markup = render({
    markers: [marker("a", "DESIGN_WORKSHOP", 71.2), marker("b", "PLANNED", 75), marker("c", "CLUSTER", 80)]
  });
  expect(markup).toContain('data-marker="design"');
  expect(markup).toContain('data-marker="planned"');
  expect(markup).toContain('data-marker="cluster"');
  expect(markup).toContain("fill-purple-700");
  expect(markup).not.toMatch(/fill-ink-900[^"]*"[^>]*r=/);
});
