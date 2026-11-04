"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Search, X, Loader2, FolderTree, ExternalLink } from "lucide-react";
import Link from "next/link";

import {
  DESIGN_WORKSHOP_TYPE,
  EMPTY_SEARCH_FILTERS,
  RECORD_TYPES,
  SEARCH_RECORD_TYPES,
  SearchFilterBar,
  filtersToLinkParams,
  hasActiveFilters,
  searchFilterParams,
  typeVisible,
  type SearchFilters,
  type SearchRecordType
} from "@/components/search/SearchFilters";
import { useAuth } from "@/components/AuthProvider";
import { OpenableCard } from "@/components/data/OpenableRow";
import { apiFetch, buildQuery } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import { canRunDesignWorkshops, canViewDesignWorkshopData } from "@/lib/permissions";
import { RECORD_VIEW_HREF } from "@/lib/recordView";
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";

type SearchResult = {
  artisans: Artisan[];
  workshops: Workshop[];
  products: ProductDocumentation[];
  tools: ToolDocumentation[];
  media: MediaFile[];
  designWorkshops?: DwSummary[];
  designWorkshopSearchScope?: string;
  totals?: Partial<Record<SearchRecordType, number>>;
  total?: number;
};

type LocateResult = { path: string | null };

type Row = {
  key: string;
  kind: "Artisan" | "Workshop" | "Product" | "Tool" | "Media" | "Design workshop";
  title: string;
  subtitle: string;
  href: string | null;
  external?: boolean;
  locate: { type: string; id: string } | null;
};

const EMPTY: Row[] = [];

function rowsFrom(
  result: SearchResult,
  filters: SearchFilters,
  offeredTypes: readonly SearchRecordType[],
  mayOpenWorkshops: boolean
): Row[] {
  const rows: Row[] = [];
  for (const a of typeVisible(filters, "artisans") ? (result.artisans ?? []) : []) {
    rows.push({
      key: `artisan-${a.id}`,
      kind: "Artisan",
      title: a.name,
      subtitle: [a.craft?.name, a.place].filter(Boolean).join(" · "),
      href: RECORD_VIEW_HREF.artisan(a.id),
      locate: { type: "artisan", id: a.id }
    });
  }
  for (const p of typeVisible(filters, "products") ? (result.products ?? []) : []) {
    rows.push({
      key: `product-${p.id}`,
      kind: "Product",
      title: p.productName,
      subtitle: p.artisanName ?? "",
      href: RECORD_VIEW_HREF.product(p.id),
      locate: { type: "product", id: p.id }
    });
  }
  for (const t of typeVisible(filters, "tools") ? (result.tools ?? []) : []) {
    rows.push({
      key: `tool-${t.id}`,
      kind: "Tool",
      title: t.toolkitName,
      subtitle: t.artisanName ?? "",
      href: RECORD_VIEW_HREF.tool(t.id),
      locate: { type: "tool", id: t.id }
    });
  }
  for (const w of typeVisible(filters, "workshops") ? (result.workshops ?? []) : []) {
    rows.push({
      key: `workshop-${w.id}`,
      kind: "Workshop",
      title: w.title,
      subtitle: [w.place, w.date ? new Date(w.date).toLocaleDateString() : ""].filter(Boolean).join(" · "),
      href: RECORD_VIEW_HREF.workshop(w.id),
      locate: { type: "workshop", id: w.id }
    });
  }
  for (const m of typeVisible(filters, "media") ? (result.media ?? []) : []) {
    rows.push({
      key: `media-${m.id}`,
      kind: "Media",
      title: m.caption || m.originalFilename,
      subtitle: [m.mediaType, m.uploadedBy?.name].filter(Boolean).join(" · "),
      href: RECORD_VIEW_HREF.media(m.id),
      locate: { type: "media", id: m.id }
    });
  }
  const showDesignWorkshops =
    offeredTypes.includes(DESIGN_WORKSHOP_TYPE) && typeVisible(filters, DESIGN_WORKSHOP_TYPE);
  for (const w of showDesignWorkshops ? (result.designWorkshops ?? []) : []) {
    rows.push({
      key: `design-workshop-${w.id}`,
      kind: "Design workshop",
      title: w.title,
      subtitle: [w.workshopCode, w.craftName, w.clusterName ?? w.district, w.state]
        .filter(Boolean)
        .join(" · "),
      href: mayOpenWorkshops ? `/design-workshops/${w.id}` : null,
      locate: { type: "designWorkshop", id: w.id }
    });
  }
  return rows;
}

const KIND_CLASS: Record<Row["kind"], string> = {
  Artisan: "bg-purple-100 text-purple-800",
  Product: "bg-success-100 text-success-600",
  Tool: "bg-amber-100 text-amber-800",
  Workshop: "bg-purple-50 text-purple-700",
  Media: "bg-surface-50 text-ink-700",
  "Design workshop": "bg-purple-200 text-purple-900"
};

export function DataSearchPanel({ onReveal, toolbar = false }: { onReveal: (path: string) => void; toolbar?: boolean }) {
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const [filters, setFilters] = useState<SearchFilters>(EMPTY_SEARCH_FILTERS);
  const [rows, setRows] = useState<Row[]>(EMPTY);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealing, setRevealing] = useState<string | null>(null);
  const [scopeNote, setScopeNote] = useState<string | null>(null);

  const offeredTypes = useMemo<readonly SearchRecordType[]>(
    () => (canViewDesignWorkshopData(user) ? SEARCH_RECORD_TYPES : RECORD_TYPES),
    [user]
  );
  const mayOpenWorkshops = canRunDesignWorkshops(user);
  const timer = useRef<number | null>(null);
  const generation = useRef(0);
  const typedBefore = useRef({ q: "", place: "" });

  const run = useCallback(
    async (
      text: string,
      active: SearchFilters,
      offered: readonly SearchRecordType[],
      mayOpen: boolean
    ) => {
      const q = text.trim();
      const mine = ++generation.current;
      if (q.length < 2 && !hasActiveFilters(active)) {
        setRows(EMPTY);
        setTotal(0);
        setScopeNote(null);
        setLoading(false);
        return;
      }
      setLoading(true);
      setError(null);
      try {
        const result = await apiFetch<SearchResult>(
          `/search${buildQuery({ q: q || undefined, ...searchFilterParams(active), page: 1, pageSize: 8 })}`
        );
        if (mine !== generation.current) return;
        setRows(rowsFrom(result, active, offered, mayOpen));
        setScopeNote(result.designWorkshopSearchScope ?? null);
        const selected = result.totals
          ? offered
              .filter((type) => typeVisible(active, type))
              .reduce((sum, type) => sum + (result.totals?.[type] ?? 0), 0)
          : (result.total ?? 0);
        setTotal(selected);
      } catch (err) {
        if (mine !== generation.current) return;
        setError(err instanceof Error ? err.message : "Search failed");
        setRows(EMPTY);
        setScopeNote(null);
      } finally {
        if (mine === generation.current) setLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    const typed = query !== typedBefore.current.q || filters.place !== typedBefore.current.place;
    typedBefore.current = { q: query, place: filters.place };
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(
      () => run(query, filters, offeredTypes, mayOpenWorkshops),
      typed ? 300 : 0
    );
    return () => {
      if (timer.current) window.clearTimeout(timer.current);
    };
  }, [query, filters, run, offeredTypes, mayOpenWorkshops]);

  async function reveal(row: Row) {
    if (!row.locate) return;
    setRevealing(row.key);
    try {
      const found = await apiFetch<LocateResult>(`/data/locate${buildQuery(row.locate)}`);
      if (found.path) onReveal(found.path);
      else setError(`"${row.title}" is not filed under any workshop yet, so it has no folder to open.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not locate that record in the tree");
    } finally {
      setRevealing(null);
    }
  }

  return (
    <section role="search" aria-label="Search the repository" className={toolbar ? "grid gap-2" : "panel mb-4 grid gap-3 p-4"}>
      {toolbar ? null : (
      <div>
        <h2 className="font-display text-base font-bold text-ink-900">Search the repository</h2>
        <p className="mt-0.5 text-xs text-ink-500">
          Artisans, products, tools, workshops
          {offeredTypes.includes(DESIGN_WORKSHOP_TYPE) ? ", design workshops" : ""} and media by name, place or
          caption. Open a hit, or show it where it sits in the folders below.
        </p>
      </div>
      )}

      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-300" aria-hidden />
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search by name, place or caption"
          aria-label="Search the repository"
          className="field-input w-full pl-9 pr-9"
        />
        {query ? (
          <button
            type="button"
            onClick={() => setQuery("")}
            aria-label="Clear search"
            className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded text-ink-500 hover:bg-purple-50 hover:text-purple-700"
          >
            <X className="h-3.5 w-3.5" aria-hidden />
          </button>
        ) : null}
      </div>

      {toolbar ? (
        <details className="text-xs">
          <summary className="min-h-9 cursor-pointer py-2 font-semibold text-purple-700 dark:text-purple-300 pointer-coarse:min-h-11">Filters</summary>
          <SearchFilterBar value={filters} onChange={setFilters} className="" offeredTypes={offeredTypes} />
        </details>
      ) : (
        <SearchFilterBar value={filters} onChange={setFilters} className="" offeredTypes={offeredTypes} />
      )}

      {error ? <p className="text-xs text-error-600">{error}</p> : null}

      {scopeNote ? <p className="text-xs text-ink-500">{scopeNote}</p> : null}

      {loading ? (
        <p className="flex items-center gap-2 text-xs text-ink-500">
          <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
          Searching…
        </p>
      ) : null}

      {!loading && rows.length === 0 && !error && (query.trim().length >= 2 || hasActiveFilters(filters)) ? (
        <p className="text-xs text-ink-500">
          {query.trim().length >= 2
            ? `Nothing matches “${query.trim()}”${hasActiveFilters(filters) ? " with these filters" : ""}.`
            : "Nothing matches these filters."}
        </p>
      ) : null}

      {rows.length ? (
        <>
          <ul className="grid gap-1.5">
            {rows.map((row) => (
              <OpenableCard
                as="li"
                key={row.key}
                href={row.href}
                external={row.external}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
              >
                <div className="flex min-w-0 items-center gap-2">
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${KIND_CLASS[row.kind]}`}>
                    {row.kind}
                  </span>
                  <span className="truncate text-sm font-medium text-ink-900">{row.title}</span>
                  {row.subtitle ? <span className="truncate text-xs text-ink-500">{row.subtitle}</span> : null}
                </div>
                <div className="flex shrink-0 items-center gap-3">
                  <button
                    type="button"
                    className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700 dark:text-purple-300 disabled:opacity-50"
                    disabled={revealing === row.key}
                    onClick={() => reveal(row)}
                  >
                    <FolderTree className="h-3.5 w-3.5" aria-hidden />
                    {revealing === row.key ? "Locating…" : "Show in folders"}
                  </button>
                  {row.href ? (
                    row.external ? (
                      <a
                        href={row.href}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700 dark:text-purple-300"
                      >
                        <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                        Open
                      </a>
                    ) : (
                      <Link href={row.href} className="text-xs font-semibold text-purple-700 dark:text-purple-300">
                        Open
                      </Link>
                    )
                  ) : null}
                </div>
              </OpenableCard>
            ))}
          </ul>
          {total > rows.length ? (
            <p className="text-xs text-ink-500">
              Showing {rows.length} of {total}.{" "}
              <Link
                href={`/search${buildQuery({ q: query.trim() || undefined, ...filtersToLinkParams(filters) })}`}
                className="font-semibold text-purple-700 dark:text-purple-300"
              >
                See all results
              </Link>
            </p>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
