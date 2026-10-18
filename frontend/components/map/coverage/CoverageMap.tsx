"use client";

/**
 * The coverage choropleth: one SVG, on the same projection as the Places map, with no mapping
 * library and no tiles.
 *
 * LAYERS, bottom to top:
 *   1. the national outline (`indiaGeometry.ts`, verified against the Survey of India depiction) as
 *      a neutral base fill — so any part of India without a district polygon reads as India, in the
 *      "no data" tone, and never as blank;
 *   2. district fills, each clipped to that outline;
 *   3. the state borders;
 *   4. markers for workshops and clusters, folded into a count where they crowd.
 *
 * Like `IndiaMap`, the SVG is `aria-hidden`: `CoverageTable` is the text version and the keyboard
 * route to every district. Clicking a district is a pointer shortcut to the same panel.
 */

import { memo, useEffect, useId, useMemo, useState } from "react";

import { loadBorders, type BorderLine } from "@/components/map/borderGeometry";
import { bordersToPath, indiaOutlinePath, project, VIEW_BOX } from "@/components/map/projection";
import { shapePath } from "@/components/map/shapeGeometry";
import { clusterMarkers, type ShadedShape } from "@/components/map/coverage/coverageShade";
import type { CoverageMarker, Metric } from "@/components/map/coverage/coverageTypes";

/** Sequential fills, one hue light to dark; dark mode runs its own steps from the same ramp. */
export const BIN_FILL = [
  "fill-purple-100 dark:fill-purple-900",
  "fill-purple-200 dark:fill-purple-800",
  "fill-purple-400 dark:fill-purple-600",
  "fill-purple-600 dark:fill-purple-400",
  "fill-purple-800 dark:fill-purple-200"
] as const;

const Base = memo(function Base() {
  return (
    <path
      d={indiaOutlinePath()}
      fillRule="evenodd"
      className="fill-field-200 stroke-ink-300"
      strokeWidth={1.2}
      strokeLinejoin="round"
      vectorEffect="non-scaling-stroke"
    />
  );
});

type Props = {
  shaded: ShadedShape[];
  markers: CoverageMarker[];
  metric: Metric;
  focusState: number | null;
  selected: { stateLgd: number; districtLgd: number | null } | null;
  onSelectShape: (shaded: ShadedShape) => void;
  className?: string;
};

export function CoverageMap({ shaded, markers, metric, focusState, selected, onSelectShape, className = "" }: Props) {
  const clipId = useId().replace(/:/g, "");
  const hatchId = `${clipId}-hatch`;
  const [statePath, setStatePath] = useState<string | null>(null);
  const [hover, setHover] = useState<{ x: number; y: number; label: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadBorders("state")
      .then((lines: BorderLine[]) => {
        if (!cancelled) setStatePath(bordersToPath(lines));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const paths = useMemo(
    () => shaded.map((entry) => ({ entry, d: shapePath(entry.shape) })),
    [shaded]
  );

  // Narrowing to one state is the only zoom: fit the view to that state's districts.
  const viewBox = useMemo(() => {
    if (!focusState) return `0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`;
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const { shape } of shaded) {
      if (shape.stateLgd !== focusState) continue;
      for (const ring of shape.rings) {
        for (const [lon, lat] of ring) {
          const { x, y } = project(lon, lat);
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
        }
      }
    }
    if (!Number.isFinite(minX)) return `0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`;
    const pad = Math.max(maxX - minX, maxY - minY) * 0.08 + 8;
    return `${minX - pad} ${minY - pad} ${maxX - minX + pad * 2} ${maxY - minY + pad * 2}`;
  }, [focusState, shaded]);

  const scale = useMemo(() => {
    const width = Number(viewBox.split(" ")[2]);
    return Math.max(0.25, Math.min(1, width / VIEW_BOX.width));
  }, [viewBox]);

  const clusters = useMemo(() => clusterMarkers(markers, 16 * scale), [markers, scale]);

  return (
    <svg
      viewBox={viewBox}
      className={`h-auto w-full max-w-full ${className}`}
      role="presentation"
      aria-hidden="true"
      focusable="false"
      data-testid="coverage-map"
    >
      <defs>
        <clipPath id={clipId}>
          <path d={indiaOutlinePath()} clipRule="evenodd" />
        </clipPath>
        <pattern id={hatchId} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1={0} y1={0} x2={0} y2={6} className="stroke-ink-300" strokeWidth={1.4} />
        </pattern>
      </defs>

      <Base />

      <g clipPath={`url(#${clipId})`} strokeLinejoin="round">
        {paths.map(({ entry, d }) => {
          const isSelected =
            selected !== null &&
            entry.shape.stateLgd === selected.stateLgd &&
            (selected.districtLgd === null || selected.districtLgd === entry.shape.lgd);
          const hatched = metric === "workshops" && entry.noWorkshop;
          const label = entry.unit
            ? `${entry.unit.district ?? entry.unit.state}: ${entry.value}`
            : entry.guests.length
              ? `${entry.guests.map((g) => g.district).join(", ")}: ${entry.value}`
              : `${entry.value}`;
          return (
            <g
              key={entry.shape.lgd}
              className="cursor-pointer"
              data-lgd={entry.shape.lgd}
              onClick={() => onSelectShape(entry)}
              onPointerEnter={() => {
                const { x, y } = project(entry.shape.centroid[0], entry.shape.centroid[1]);
                setHover({ x, y, label });
              }}
              onPointerLeave={() => setHover(null)}
            >
              <path
                d={d}
                fillRule="evenodd"
                className={`${entry.fill >= 0 ? BIN_FILL[entry.fill] : "fill-field-200"} stroke-card`}
                strokeWidth={0.5}
                vectorEffect="non-scaling-stroke"
              />
              {hatched ? <path d={d} fill={`url(#${hatchId})`} className="pointer-events-none" /> : null}
              {entry.guests.length ? (
                <path
                  d={d}
                  fill="none"
                  className="pointer-events-none stroke-ink-700"
                  strokeWidth={1}
                  strokeDasharray="3 2"
                  vectorEffect="non-scaling-stroke"
                />
              ) : null}
              {isSelected ? (
                <path
                  d={d}
                  fill="none"
                  className="pointer-events-none stroke-ink-900"
                  strokeWidth={2}
                  vectorEffect="non-scaling-stroke"
                />
              ) : null}
            </g>
          );
        })}
        {statePath ? (
          <path
            d={statePath}
            fill="none"
            className="pointer-events-none stroke-ink-500"
            strokeWidth={1}
            vectorEffect="non-scaling-stroke"
          />
        ) : null}
      </g>

      <g>
        {clusters.map((cluster) => {
          const first = cluster.markers[0];
          const count = cluster.markers.length;
          const planned = cluster.markers.every((m) => m.kind === "PLANNED");
          const isCluster = cluster.markers.every((m) => m.kind === "CLUSTER");
          const r = (count > 1 ? 7 + Math.min(6, Math.log2(count) * 1.6) : 4.5) * scale;
          return (
            <g
              key={`${first.kind}:${first.id}`}
              className="cursor-pointer"
              onClick={() => {
                const target = shaded.find((s) => s.shape.lgd === first.districtLgd);
                if (target) onSelectShape(target);
              }}
              onPointerEnter={() =>
                setHover({
                  x: cluster.x,
                  y: cluster.y,
                  label: count > 1 ? `${count} workshops and clusters` : first.title
                })
              }
              onPointerLeave={() => setHover(null)}
            >
              {isCluster ? (
                <rect
                  x={cluster.x - r}
                  y={cluster.y - r}
                  width={r * 2}
                  height={r * 2}
                  rx={2 * scale}
                  className="fill-card stroke-ink-900"
                  strokeWidth={1.5}
                  vectorEffect="non-scaling-stroke"
                />
              ) : (
                <circle
                  cx={cluster.x}
                  cy={cluster.y}
                  r={r}
                  className={planned ? "fill-card stroke-ink-900" : "fill-ink-900 stroke-card"}
                  strokeWidth={1.5}
                  strokeDasharray={planned ? "2 1.5" : undefined}
                  vectorEffect="non-scaling-stroke"
                />
              )}
              {count > 1 ? (
                <text
                  x={cluster.x}
                  y={cluster.y + 3.5 * scale}
                  textAnchor="middle"
                  className={`pointer-events-none font-sans font-semibold ${planned || isCluster ? "fill-ink-900" : "fill-card"}`}
                  style={{ fontSize: `${10 * scale}px` }}
                >
                  {count}
                </text>
              ) : null}
            </g>
          );
        })}
      </g>

      {hover ? (
        <g className="pointer-events-none">
          <rect
            x={hover.x + 8 * scale}
            y={hover.y - 26 * scale}
            width={Math.max(60, hover.label.length * 7.4) * scale}
            height={22 * scale}
            rx={6 * scale}
            className="fill-ink-900"
          />
          <text
            x={hover.x + 16 * scale}
            y={hover.y - 11 * scale}
            className="fill-card font-sans font-medium"
            style={{ fontSize: `${13 * scale}px` }}
          >
            {hover.label}
          </text>
        </g>
      ) : null}
    </svg>
  );
}
