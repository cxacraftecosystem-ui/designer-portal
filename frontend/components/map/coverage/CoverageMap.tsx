"use client";

/** @jsxImportSource react */

import { memo, useEffect, useId, useMemo, useState } from "react";

import { loadBorders } from "@/components/map/borderGeometry";
import { binOf, type PolygonFill } from "@/components/map/coverage/coverageMath";
import type { CoverageMarker } from "@/components/map/coverage/coverageTypes";
import { bordersToPath, indiaOutlinePath, project, VIEW_BOX } from "@/components/map/projection";
import { shapePath, type DistrictShape } from "@/components/map/shapeGeometry";

export type { PolygonFill } from "@/components/map/coverage/coverageMath";

export const BIN_CLASSES = [
  "fill-purple-300 dark:fill-purple-800",
  "fill-purple-400 dark:fill-purple-700",
  "fill-purple-500 dark:fill-purple-600",
  "fill-purple-700 dark:fill-purple-400",
  "fill-purple-900 dark:fill-purple-200"
] as const;

export const CLUSTER_CELL = 22;

const LABEL_FONT = 12;

export type CoverageMapProps = {
  shapes: DistrictShape[];
  fills: Map<number, PolygonFill>;
  breaks: number[];
  noWorkshop: Set<number>;
  markers: CoverageMarker[];
  selectedLgd: number | null;
  stateLgd: number | null;
  names: Map<number, string>;
  onSelectPolygon: (lgd: number) => void;
  statePath?: string | null;
};

export type MarkerCluster = { x: number; y: number; markers: CoverageMarker[] };

export function clusterMarkers(markers: CoverageMarker[], cell: number = CLUSTER_CELL): MarkerCluster[] {
  const cells = new Map<string, MarkerCluster>();
  for (const marker of markers) {
    const { x, y } = project(marker.lon, marker.lat);
    const key = `${Math.floor(x / cell)}:${Math.floor(y / cell)}`;
    const found = cells.get(key);
    if (found) {
      found.markers.push(marker);
      found.x = (found.x * (found.markers.length - 1) + x) / found.markers.length;
      found.y = (found.y * (found.markers.length - 1) + y) / found.markers.length;
    } else {
      cells.set(key, { x, y, markers: [marker] });
    }
  }
  return [...cells.values()];
}

export function stateBox(shapes: DistrictShape[], stateLgd: number | null): string {
  const full = `0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`;
  if (stateLgd === null) return full;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const shape of shapes) {
    if (shape.stateLgd !== stateLgd) continue;
    for (const ring of shape.rings) {
      for (const [longitude, latitude] of ring) {
        const { x, y } = project(longitude, latitude);
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
  }
  if (!Number.isFinite(minX)) return full;
  const pad = Math.max(maxX - minX, maxY - minY) * 0.08 + 8;
  return `${(minX - pad).toFixed(1)} ${(minY - pad).toFixed(1)} ${(maxX - minX + pad * 2).toFixed(1)} ${(
    maxY - minY + pad * 2
  ).toFixed(1)}`;
}

export function viewScale(viewBox: string): number {
  const width = Number(viewBox.split(" ")[2]);
  if (!Number.isFinite(width) || width <= 0) return 1;
  return Math.max(0.05, Math.min(1, width / VIEW_BOX.width));
}

type MarkerStyle = "design" | "planned" | "cluster" | "field";

function markerStyle(markers: CoverageMarker[]): MarkerStyle {
  if (markers.every((marker) => marker.kind === "PLANNED")) return "planned";
  if (markers.every((marker) => marker.kind === "CLUSTER")) return "cluster";
  if (markers.some((marker) => marker.kind === "DESIGN_WORKSHOP")) return "design";
  return "field";
}

function CoverageMapImpl({
  shapes,
  fills,
  breaks,
  noWorkshop,
  markers,
  selectedLgd,
  stateLgd,
  names,
  onSelectPolygon,
  statePath: givenStatePath = null
}: CoverageMapProps) {
  const id = useId().replace(/:/g, "");
  const clipId = `cov-clip-${id}`;
  const hatchId = `cov-hatch-${id}`;
  const outline = useMemo(() => indiaOutlinePath(), []);
  const [loadedStatePath, setStatePath] = useState<string | null>(null);
  const statePath = givenStatePath ?? loadedStatePath;
  const [hovered, setHovered] = useState<number | null>(null);

  useEffect(() => {
    if (givenStatePath) return;
    let live = true;
    loadBorders("state")
      .then((lines) => {
        if (live) setStatePath(bordersToPath(lines));
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [givenStatePath]);

  const visible = useMemo(
    () => (stateLgd === null ? shapes : shapes.filter((shape) => shape.stateLgd === stateLgd)),
    [shapes, stateLgd]
  );
  const paths = useMemo(() => new Map(visible.map((shape) => [shape.lgd, shapePath(shape)])), [visible]);
  const hatchPath = useMemo(
    () =>
      visible
        .filter((shape) => noWorkshop.has(shape.lgd))
        .map((shape) => paths.get(shape.lgd) ?? "")
        .join(""),
    [visible, noWorkshop, paths]
  );
  const viewBox = useMemo(() => stateBox(shapes, stateLgd), [shapes, stateLgd]);
  const scale = viewScale(viewBox);
  const clusters = useMemo(() => clusterMarkers(markers, CLUSTER_CELL * scale), [markers, scale]);
  const hoveredShape = hovered !== null ? visible.find((shape) => shape.lgd === hovered) : undefined;

  return (
    <svg viewBox={viewBox} className="block h-auto w-full lg:max-h-[calc(100dvh-17rem)]" aria-hidden data-testid="coverage-map">
      <defs>
        <clipPath id={clipId}>
          <path d={outline} clipRule="evenodd" />
        </clipPath>
        <pattern id={hatchId} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1={0} y1={0} x2={0} y2={6} className="stroke-ink-300" strokeWidth={1.2} />
        </pattern>
      </defs>

      <path d={outline} fillRule="evenodd" className="fill-field-100 stroke-line-200" strokeWidth={1} />

      <g clipPath={`url(#${clipId})`} data-testid="coverage-districts">
        {visible.map((shape) => {
          const fill = fills.get(shape.lgd);
          const bin = fill ? binOf(fill.value, breaks) : -1;
          const selected = selectedLgd === shape.lgd;
          return (
            <path
              key={shape.lgd}
              d={paths.get(shape.lgd)}
              fillRule="evenodd"
              data-lgd={shape.lgd}
              className={`cursor-pointer transition-opacity ${
                bin >= 0 ? BIN_CLASSES[bin] : "fill-transparent"
              } ${selected ? "stroke-ink-900" : fill?.hostsChild ? "stroke-ink-500" : "stroke-line-200"} hover:opacity-80`}
              strokeWidth={selected ? 2 : fill?.hostsChild ? 1 : 0.5}
              strokeDasharray={fill?.hostsChild && !selected ? "3 2" : undefined}
              onClick={() => onSelectPolygon(shape.lgd)}
              onMouseEnter={() => setHovered(shape.lgd)}
              onMouseLeave={() => setHovered((current) => (current === shape.lgd ? null : current))}
            />
          );
        })}
        {hatchPath ? (
          <path
            d={hatchPath}
            fillRule="evenodd"
            fill={`url(#${hatchId})`}
            className="pointer-events-none"
            data-testid="coverage-hatch"
          />
        ) : null}
      </g>

      {statePath ? (
        <path
          d={statePath}
          className="pointer-events-none fill-none stroke-ink-500"
          strokeWidth={1.1}
          data-testid="coverage-state-borders"
        />
      ) : null}
      <path d={outline} fillRule="evenodd" className="pointer-events-none fill-none stroke-ink-700" strokeWidth={1.2} />

      {clusters.map((cluster) => (
        <MarkerMark
          key={`${cluster.markers[0].kind}-${cluster.markers[0].id}`}
          cluster={cluster}
          scale={scale}
          onSelect={onSelectPolygon}
        />
      ))}

      {hoveredShape ? (
        <HoverLabel
          shape={hoveredShape}
          name={names.get(hoveredShape.lgd) ?? ""}
          value={fills.get(hoveredShape.lgd)?.value ?? 0}
          scale={scale}
        />
      ) : null}
    </svg>
  );
}

function MarkerMark({
  cluster,
  scale,
  onSelect
}: {
  cluster: MarkerCluster;
  scale: number;
  onSelect: (lgd: number) => void;
}) {
  const many = cluster.markers.length > 1;
  const first = cluster.markers[0];
  const style = markerStyle(cluster.markers);
  const r = (many ? 9 : 4.5) * scale;
  const rim = 2 * scale;
  const disc =
    style === "design"
      ? "fill-purple-700 stroke-white dark:fill-purple-300 dark:stroke-purple-950"
      : "fill-white stroke-purple-700 dark:fill-purple-950 dark:stroke-purple-300";
  const ink =
    style === "design" ? "fill-white dark:fill-purple-950" : "fill-purple-800 dark:fill-purple-200";
  return (
    <g
      className="cursor-pointer"
      data-marker={style}
      onClick={() => {
        if (first.districtLgd !== null) onSelect(first.districtLgd);
      }}
    >
      <circle
        cx={cluster.x}
        cy={cluster.y}
        r={r}
        className={disc}
        strokeWidth={rim}
        strokeDasharray={style === "planned" ? `${2.2 * scale} ${1.6 * scale}` : undefined}
      />
      {style === "cluster" ? (
        <circle
          cx={cluster.x}
          cy={cluster.y}
          r={r + rim * 1.5}
          className="fill-none stroke-purple-700 dark:stroke-purple-300"
          strokeWidth={rim * 0.6}
        />
      ) : null}
      {many ? (
        <text
          x={cluster.x}
          y={cluster.y + 3.5 * scale}
          textAnchor="middle"
          className={`pointer-events-none font-sans font-semibold ${ink}`}
          style={{ fontSize: `${10 * scale}px` }}
        >
          {cluster.markers.length}
        </text>
      ) : null}
    </g>
  );
}

export function HoverLabel({
  shape,
  name,
  value,
  scale = 1
}: {
  shape: DistrictShape;
  name: string;
  value: number;
  scale?: number;
}) {
  if (!name) return null;
  const { x, y } = project(shape.centroid[0], shape.centroid[1]);
  const text = `${name} · ${value}`;
  const width = (text.length * 7.4 + 16) * scale;
  const height = 24 * scale;
  const lift = 10 * scale;
  return (
    <g className="pointer-events-none" data-testid="coverage-hover-label">
      <rect x={x - width / 2} y={y - lift - height} width={width} height={height} rx={6 * scale} className="fill-ink-900" />
      <text
        x={x}
        y={y - lift - height / 2 + (LABEL_FONT * scale) / 3}
        textAnchor="middle"
        className="fill-card font-sans font-medium"
        style={{ fontSize: `${LABEL_FONT * scale}px` }}
      >
        {text}
      </text>
    </g>
  );
}

export const CoverageMap = memo(CoverageMapImpl);
