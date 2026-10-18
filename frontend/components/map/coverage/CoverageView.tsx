"use client";

/**
 * The Coverage view of `/map`: which districts the programme has reached, and where the gaps are.
 *
 * Every filter lives in the URL (`?view=coverage&craftCode=…&fy=…&scheme=…&stateLgd=…&level=…&metric=…
 * &peer=1`), so a link reproduces the view. The district shapes load only when this view opens.
 * Nothing here talks to a tile server or a mapping library.
 */

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";

import { readableError } from "@/components/review/reviewErrors";
import { CoverageFilters, type CoverageFilterState } from "@/components/map/coverage/CoverageFilters";
import { filtersFromParams, paramsFromFilters } from "@/components/map/coverage/coverageUrl";
import { CoverageGapsPanel } from "@/components/map/coverage/CoverageGapsPanel";
import { CoverageLegend } from "@/components/map/coverage/CoverageLegend";
import { CoverageMap } from "@/components/map/coverage/CoverageMap";
import { shadeShapes, type ShadedShape } from "@/components/map/coverage/coverageShade";
import { CoverageTable } from "@/components/map/coverage/CoverageTable";
import { CoverageUnitPanel } from "@/components/map/coverage/CoverageUnitPanel";
import { UnmatchedPlacesPanel } from "@/components/map/coverage/UnmatchedPlacesPanel";
import {
  fetchCoverage,
  peerCoverageEnabled,
  type CoverageQuery
} from "@/components/map/coverage/coverageApi";
import {
  metricValue,
  quantileBreaks,
  type CoverageResponse,
  type CoverageUnit
} from "@/components/map/coverage/coverageTypes";
import { loadDistrictShapes, type DistrictShape } from "@/components/map/shapeGeometry";

export const COVERAGE_CAPTION =
  "External boundaries of India as depicted by Survey of India. District boundaries are indicative.";

type Selection = { stateLgd: number; districtLgd: number | null; note: string | null; title: string };

export function CoverageView() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [filters, setFilters] = useState<CoverageFilterState>(() =>
    filtersFromParams(new URLSearchParams(searchParams.toString()))
  );
  const [data, setData] = useState<CoverageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [shapes, setShapes] = useState<DistrictShape[] | null>(null);
  const [shapesError, setShapesError] = useState(false);
  const [peerAvailable, setPeerAvailable] = useState(false);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [reload, setReload] = useState(0);
  const [stateNames, setStateNames] = useState<Record<number, string>>({});

  const query: CoverageQuery = useMemo(
    () => ({
      level: filters.level,
      craftCode: filters.craftCode,
      scheme: filters.scheme,
      fy: filters.fy,
      stateLgd: filters.stateLgd,
      peer: filters.peer && peerAvailable
    }),
    [filters, peerAvailable]
  );

  const change = useCallback(
    (next: CoverageFilterState) => {
      setFilters(next);
      setSelection(null);
      router.replace(`${pathname}?${paramsFromFilters(next).toString()}`, { scroll: false });
    },
    [pathname, router]
  );

  useEffect(() => {
    let cancelled = false;
    loadDistrictShapes()
      .then((loaded) => {
        if (!cancelled) setShapes(loaded);
      })
      .catch(() => {
        if (!cancelled) setShapesError(true);
      });
    peerCoverageEnabled().then((on) => {
      if (!cancelled) setPeerAvailable(on);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    fetchCoverage(query, controller.signal)
      .then((answer) => {
        // Remembered across loads, so narrowing to one state keeps every state in the picker.
        setStateNames((known) => {
          const next = { ...known };
          for (const unit of answer.units) next[unit.stateLgd] = unit.state;
          for (const row of answer.gaps.districtsWithoutWorkshop.byState) next[row.stateLgd] = row.state;
          for (const row of answer.gaps.statesWithoutWorkshop) {
            if (row.lgd !== null) next[row.lgd] = row.name;
          }
          return next;
        });
        setData(answer);
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(readableError(cause, "Couldn't load the coverage map."));
      });
    return () => controller.abort();
  }, [query, reload]);

  const breaks = useMemo(() => {
    if (!data) return [];
    return quantileBreaks(
      data.units.filter((u) => (filters.level === "STATE" ? u.districtLgd === null : u.districtLgd !== null)).map((u) => metricValue(u, filters.metric))
    );
  }, [data, filters.level, filters.metric]);

  const shaded = useMemo(
    () => (shapes && data ? shadeShapes(shapes, data.units, filters.metric, breaks, filters.level) : []),
    [shapes, data, filters.metric, breaks, filters.level]
  );

  const parentNote = useMemo(() => {
    const host = shaded.find((s) => s.guests.length);
    if (!host) return null;
    const parent = data?.units.find((u) => u.districtLgd === host.shape.lgd)?.district;
    const guest = host.guests[0].district;
    return parent ? `${guest}: shown inside ${parent} (parent)` : "Shown inside the parent district";
  }, [shaded, data]);

  const openUnit = useCallback((unit: CoverageUnit) => {
    const parent =
      unit.polygon === "PARENT" && unit.parentLgd
        ? `Shown inside ${data?.units.find((u) => u.districtLgd === unit.parentLgd)?.district ?? "its parent district"} (parent)`
        : null;
    setSelection({
      stateLgd: unit.stateLgd,
      districtLgd: unit.districtLgd,
      note: parent,
      title: unit.district ? `${unit.district}, ${unit.state}` : unit.state
    });
  }, [data]);

  const openShape = useCallback(
    (entry: ShadedShape) => {
      if (filters.level === "STATE") {
        setSelection({ stateLgd: entry.shape.stateLgd, districtLgd: null, note: null, title: "" });
        return;
      }
      const guests = entry.guests.map((g) => g.district).filter(Boolean);
      setSelection({
        stateLgd: entry.shape.stateLgd,
        districtLgd: entry.shape.lgd,
        note: guests.length ? `Also drawn here: ${guests.join(", ")}` : null,
        title: ""
      });
    },
    [filters.level]
  );

  const stateList = useMemo(
    () =>
      Object.entries(stateNames)
        .map(([lgd, name]) => ({ lgd: Number(lgd), name }))
        .sort((a, b) => a.name.localeCompare(b.name)),
    [stateNames]
  );

  const isAdmin = Boolean(data?.unmatched.sample);
  const peerOn = query.peer && data?.peer?.status === "OK";

  return (
    <div className="grid grid-cols-1 gap-4" data-testid="coverage-view">
      <CoverageFilters
        value={filters}
        onChange={change}
        catalogue={data?.catalogue ?? null}
        states={stateList}
        peerAvailable={peerAvailable}
      />

      {error ? (
        <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
      ) : null}
      {data?.truncated ? (
        <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          Counts stop at 20,000 rows per kind of record.
        </p>
      ) : null}
      {query.peer && data?.peer?.status === "UNAVAILABLE" ? (
        <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          The Field Repository can&apos;t be reached. Showing this portal only.
        </p>
      ) : null}
      {peerOn && filters.scheme.length ? (
        <p className="text-xs text-ink-500">The Field Repository has no scheme, so its figures ignore that filter.</p>
      ) : null}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="grid min-w-0 grid-cols-1 content-start gap-3">
          <figure className="panel grid min-w-0 grid-cols-1 gap-3 p-3">
            {shapesError ? (
              <p className="text-sm text-ink-500">District shapes didn&apos;t load. The table below has every figure.</p>
            ) : !shapes || !data ? (
              <div className="aspect-[1/1.05] w-full animate-pulse rounded-md bg-surface-50" />
            ) : (
              <CoverageMap
                shaded={shaded}
                markers={data.markers}
                metric={filters.metric}
                focusState={filters.stateLgd}
                selected={selection}
                onSelectShape={openShape}
              />
            )}
            <figcaption className="grid gap-3">
              <CoverageLegend metric={filters.metric} breaks={breaks} parentNote={parentNote} level={filters.level} />
              {data?.markersTruncated ? (
                <p className="text-xs text-ink-500">Showing the first 500 markers.</p>
              ) : null}
              <p className="text-xs text-ink-500" data-testid="coverage-caption">
                {COVERAGE_CAPTION}
              </p>
            </figcaption>
          </figure>

          <section className="panel p-4" aria-label="Coverage table">
            {data ? <CoverageTable units={data.units} showPeer={Boolean(peerOn)} onOpen={openUnit} /> : null}
          </section>
        </div>

        <div className="grid min-w-0 grid-cols-1 content-start gap-4">
          {selection ? (
            <CoverageUnitPanel
              query={query}
              stateLgd={selection.stateLgd}
              districtLgd={selection.districtLgd ?? 0}
              title={selection.title}
              note={selection.note}
              onClose={() => setSelection(null)}
            />
          ) : null}
          {data ? <CoverageGapsPanel gaps={data.gaps} peerIncluded={Boolean(peerOn)} /> : null}
          {isAdmin && data && data.unmatched.count > 0 ? (
            <UnmatchedPlacesPanel onMapped={() => setReload((n) => n + 1)} />
          ) : null}
        </div>
      </div>
    </div>
  );
}
