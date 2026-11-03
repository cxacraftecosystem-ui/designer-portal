"use client";

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Search } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { RecordCodeScanPanel } from "@/components/RecordCodeScanPanel";
import { RowActions, rowAction } from "@/components/RowActions";
import {
  DESIGN_WORKSHOP_TYPE,
  EMPTY_SEARCH_FILTERS,
  RECORD_TYPES,
  SEARCH_MEDIA_TYPES,
  SEARCH_RECORD_TYPES,
  SearchFilterBar,
  filtersFromSearchParams,
  filtersToLinkParams,
  searchFilterParams,
  typeVisible,
  type SearchFilters,
  type SearchRecordType
} from "@/components/search/SearchFilters";
import { StatusBadge } from "@/components/StatusBadge";
import { useAuth } from "@/components/AuthProvider";
import { cappedListNotice, listCut, LIST_PAGE_CEILING, type ListCut } from "@/components/data/cappedList";
import { OpenableCard } from "@/components/data/OpenableRow";
import { Dropdown } from "@/components/ui/Dropdown";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { CraftSelect } from "@/components/forms/CraftSelect";
import {
  useWorkshopScope,
  WorkshopScopeSelect,
  workshopScopeFromSearchParams
} from "@/components/WorkshopScopeSelect";
import { apiFetch, buildQuery, listResource } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import { canViewDesignWorkshopData } from "@/lib/permissions";
import { RECORD_VIEW_HREF } from "@/lib/recordView";
import type { Artisan, Craft, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { UnifiedResults } from "@/components/search/UnifiedResults";
import { RecordingSearchGroup } from "@/components/questionnaire/RecordingSearchGroup";
import { fetchUnified, UNIFIED_TYPES, unifiedApplies, type UnifiedResponse, type UnifiedType } from "@/lib/unifiedSearch";
import { useUrlScrollMemory } from "@/components/hooks/useListMemory";

type SearchResult = {
  artisans: Artisan[];
  workshops: Workshop[];
  products: ProductDocumentation[];
  tools: ToolDocumentation[];
  media: MediaFile[];
  designWorkshops?: DwSummary[];
  totals?: Partial<Record<SearchRecordType, number>>;
  total?: number;
  pageCount?: number;
  typesRefused?: string[];
  designWorkshopSearchScope?: string;
  designWorkshopStageMatches?: Record<string, string[]>;
};

type ResultItem = {
  id: string;
  title: string;
  subtitle: string;
  status: string;
  date: string;
  href: string;
  external?: boolean;
  actionLabel?: string;
  matchedIn?: string[];
};

const MATCHED_IN_SHOWN = 3;

const PAGE_SIZE = 20;

type SearchAnswer = { unified: UnifiedResponse | null; legacy: SearchResult | null };

type AppliedFilters = { q: string; filters: SearchFilters };

export default function SearchPage() {
  const searchParams = useSearchParams();
  const { user } = useAuth();

  const offeredTypes = useMemo<readonly SearchRecordType[]>(
    () => (canViewDesignWorkshopData(user) ? SEARCH_RECORD_TYPES : RECORD_TYPES),
    [user]
  );

  const [query, setQuery] = useState(searchParams.get("q") ?? "");
  const [filters, setFilters] = useState<SearchFilters>(EMPTY_SEARCH_FILTERS);
  const [applied, setApplied] = useState<AppliedFilters | null>(null);
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unified, setUnified] = useState<UnifiedResponse | null>(null);
  const [unifiedFocus, setUnifiedFocus] = useState<{ type: UnifiedType; page: number } | null>(null);

  const scope = useWorkshopScope({
    defaultToMostRecent: false,
    initialWorkshopIds: workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()))
  });

  const arrived = useRef(false);
  useEffect(() => {
    if (arrived.current) return;
    const seeded = filtersFromSearchParams(new URLSearchParams(searchParams.toString()), offeredTypes);
    const q = (searchParams.get("q") ?? "").trim();
    const scoped = workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()));
    const narrowed =
      seeded.types.length > 0 ||
      Boolean(seeded.place) ||
      seeded.range !== "any" ||
      Boolean(seeded.craftId) ||
      Boolean(seeded.artisanId) ||
      Boolean(seeded.mediaType);
    if (!q && !narrowed && !scoped.length) return;
    arrived.current = true;
    setFilters(seeded);
    const seededPage = Number(searchParams.get("page") ?? "1");
    if (Number.isInteger(seededPage) && seededPage > 1) setPage(seededPage);
    const [focusType, focusPage] = (searchParams.get("focus") ?? "").split(":");
    if ((UNIFIED_TYPES as readonly string[]).includes(focusType)) {
      setUnifiedFocus({ type: focusType as UnifiedType, page: Math.max(1, Number(focusPage) || 1) });
    }
    setApplied({ q, filters: seeded });
  }, [searchParams, offeredTypes]);

  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [cuts, setCuts] = useState<{ artisans: ListCut | null }>({ artisans: null });

  useEffect(() => {
    let cancelled = false;
    listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setArtisans(result.items);
        setCuts((previous) => ({ ...previous, artisans: listCut(result, "artisans") }));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    setLoading(true);
    const params = searchFilterParams(applied.filters);
    const unifiedQuestion = unifiedApplies(applied.q, applied.filters)
      ? fetchUnified({
          q: applied.q,
          types: params.types ? params.types.split(",") : undefined,
          type: unifiedFocus?.type,
          page: unifiedFocus?.page,
          limit: unifiedFocus ? PAGE_SIZE : undefined,
          craftId: params.craftId,
          place: params.place,
          dateFrom: params.dateFrom,
          dateTo: params.dateTo,
          workshopIds: scope.queryValue
        })
      : Promise.resolve({ kind: "legacy" as const });
    unifiedQuestion
      .then((outcome): Promise<SearchAnswer> | SearchAnswer => {
        if (outcome.kind === "unified") return { unified: outcome.data, legacy: null };
        return apiFetch<SearchResult>(
          `/search${buildQuery({
            q: applied.q,
            ...params,
            workshopIds: scope.queryValue,
            page,
            pageSize: PAGE_SIZE
          })}`
        ).then((legacy): SearchAnswer => ({ unified: null, legacy }));
      })
      .then((data: SearchAnswer) => {
        if (cancelled) return;
        setUnified(data.unified);
        setResult(data.legacy);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Search failed");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [applied, page, scope.queryValue, unifiedFocus]);

  useEffect(() => {
    if (applied || !scope.workshopIds.length) return;
    setApplied({ q: "", filters: EMPTY_SEARCH_FILTERS });
  }, [applied, scope.workshopIds.length]);

  useEffect(() => {
    if (!applied) return;
    arrived.current = true;
    window.history.replaceState(
      null,
      "",
      `/search${buildQuery({
        q: applied.q,
        ...filtersToLinkParams(applied.filters),
        workshopIds: scope.queryValue,
        page: page > 1 ? page : undefined,
        focus: unifiedFocus ? `${unifiedFocus.type}:${unifiedFocus.page}` : undefined
      })}`
    );
  }, [applied, scope.queryValue, page, unifiedFocus]);

  useUrlScrollMemory(Boolean(result || unified));

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setUnifiedFocus(null);
    setApplied({ q: query.trim(), filters });
  }

  function changeFilters(next: SearchFilters) {
    setFilters(next);
    setPage(1);
    setUnifiedFocus(null);
    setApplied((current) => ({ q: current?.q ?? "", filters: next }));
  }

  const show = (id: SearchRecordType) => typeVisible(filters, id);
  const showDesignWorkshops = offeredTypes.includes(DESIGN_WORKSHOP_TYPE) && show(DESIGN_WORKSHOP_TYPE);
  const designWorkshops = showDesignWorkshops ? (result?.designWorkshops ?? []) : [];
  const buckets = result
    ? [
        show("artisans") ? result.artisans : [],
        show("workshops") ? result.workshops : [],
        show("products") ? result.products : [],
        show("tools") ? result.tools : [],
        show("media") ? result.media : [],
        designWorkshops
      ]
    : [];
  const shown = buckets.reduce((sum, bucket) => sum + bucket.length, 0);
  const selectedTotals = result?.totals
    ? offeredTypes.filter(show).map((type) => result.totals?.[type] ?? 0)
    : [];
  const pageCount = selectedTotals.length
    ? Math.max(1, Math.ceil(Math.max(...selectedTotals) / PAGE_SIZE))
    : result?.pageCount;
  const hasMore = pageCount !== undefined ? page < pageCount : buckets.some((bucket) => bucket.length === PAGE_SIZE);

  return (
    <>
      <PageHeader
        title="Search"
        description={
          offeredTypes.includes(DESIGN_WORKSHOP_TYPE)
            ? "Artisans, workshops, products, tools, media and design workshops."
            : "Artisans, workshops, products, tools and media."
        }
        icon={<Search className="h-5 w-5" aria-hidden />}
      />
      <RecordCodeScanPanel />
      <form onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-[1fr_220px_auto]">
        <input className="field-input" placeholder="Search repository" value={query} onChange={(event) => setQuery(event.target.value)} />
        <input
          className="field-input"
          placeholder="Place filter"
          value={filters.place}
          onChange={(event) => setFilters({ ...filters, place: event.target.value })}
        />
        <button className="field-button" disabled={loading}>
          <Search className="h-4 w-4" aria-hidden />
          {loading ? "Searching..." : "Search"}
        </button>
      </form>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      <SearchFilterBar
        value={filters}
        onChange={changeFilters}
        showPlace={false}
        offeredTypes={offeredTypes}
        extraFilters={
          <>
            <div className="grid gap-3 sm:grid-cols-3">
              <FieldBlock label="Craft">
                <CraftSelect
                  ariaLabel="Craft"
                  placeholder="Any craft"
                  value={filters.craftId}
                  onChange={(craftId) => changeFilters({ ...filters, craftId })}
                />
              </FieldBlock>
              {artisans.length ? (
                <FieldBlock label="Artisan">
                  <Dropdown
                    value={filters.artisanId}
                    onChange={(artisanId) => changeFilters({ ...filters, artisanId })}
                    advanceOnSelect={false}
                    searchable
                    options={[
                      { value: "", label: "Any artisan" },
                      ...artisans.map((artisan) => ({
                        value: artisan.id,
                        label: [artisan.name, artisan.place].filter(Boolean).join(" · ")
                      }))
                    ]}
                  />
                </FieldBlock>
              ) : null}
              <FieldBlock label="Media type">
                <Dropdown
                  value={filters.mediaType}
                  onChange={(mediaType) => changeFilters({ ...filters, mediaType })}
                  advanceOnSelect={false}
                  options={[
                    { value: "", label: "Any media type" },
                    ...SEARCH_MEDIA_TYPES.map((type) => ({ value: type, label: type }))
                  ]}
                />
              </FieldBlock>
            </div>
            <p className="text-xs text-ink-500">
              Craft, artisan and media type narrow only the result types that have them.
            </p>
            {[cuts.artisans].map((cut, index) =>
              cut ? (
                <p key={index} className="text-xs text-ink-500">
                  {cappedListNotice(cut)}
                </p>
              ) : null
            )}
          </>
        }
      />

      {result?.typesRefused?.length || unified?.typesRefused?.length ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          Design workshops are not searched at your access level. Ask an admin.
        </p>
      ) : null}

      <div className="mb-4 max-w-xl">
        <WorkshopScopeSelect
          scope={{
            ...scope,
            setWorkshopIds: (next: string[]) => {
              setPage(1);
              setUnifiedFocus(null);
              scope.setWorkshopIds(next);
            }
          }}
          label="Workshops"
        />
      </div>

      {unified ? (
        <UnifiedResults
          data={unified}
          focus={unifiedFocus}
          pageSize={PAGE_SIZE}
          loading={loading}
          onFocus={setUnifiedFocus}
        />
      ) : null}
      {result && shown === 0 ? (
        <EmptyState
          title={page > 1 ? "No more results" : "No matching records"}
          body={
            scope.workshopIds.length
              ? "Nothing matches inside the chosen workshops. Widen the workshop scope, or choose All records."
              : page > 1
                ? "Every result type has run out on this page. Go back to see the earlier matches."
                : undefined
          }
        />
      ) : null}
      {result ? (
        <div className="grid gap-5">
          {show("artisans") ? (
          <ResultSection
            title="Artisans"
            items={result.artisans.map((item) => ({
              id: item.id,
              title: item.name,
              subtitle: [item.place, item.craft?.name].filter(Boolean).join(" · "),
              status: item.status,
              date: item.createdAt,
              href: RECORD_VIEW_HREF.artisan(item.id)
            }))}
          />
          ) : null}
          {show("workshops") ? (
          <ResultSection
            title="Workshops"
            items={result.workshops.map((item) => ({
              id: item.id,
              title: item.title,
              subtitle: item.place,
              status: item.status,
              date: item.date,
              href: RECORD_VIEW_HREF.workshop(item.id)
            }))}
          />
          ) : null}
          {show("products") ? (
          <ResultSection
            title="Products"
            items={result.products.map((item) => ({
              id: item.id,
              title: item.productName,
              subtitle: `${item.craftName} · ${item.artisanName} · ${item.place}`,
              status: item.status,
              date: item.createdAt,
              href: RECORD_VIEW_HREF.product(item.id)
            }))}
          />
          ) : null}
          {show("tools") ? (
          <ResultSection
            title="Tools"
            items={result.tools.map((item) => ({
              id: item.id,
              title: item.toolkitName,
              subtitle: `${item.craftName} · ${item.artisanName} · ${item.place}`,
              status: item.status,
              date: item.createdAt,
              href: RECORD_VIEW_HREF.tool(item.id)
            }))}
          />
          ) : null}
          {show("media") ? (
          <ResultSection
            title="Media"
            items={result.media.map((item) => ({
              id: item.id,
              title: item.caption?.trim() || item.originalFilename,
              subtitle: `${item.mediaType} · ${item.mimeType}`,
              status: item.status,
              date: item.createdAt,
              href: RECORD_VIEW_HREF.media(item.id)
            }))}
          />
          ) : null}
          {showDesignWorkshops ? (
            <ResultSection
              title="Design workshops"
              note={result.designWorkshopSearchScope}
              items={designWorkshops.map((item) => ({
                id: item.id,
                title: item.title,
                subtitle: [item.workshopCode, item.craftName, item.clusterName ?? item.district, item.state]
                  .filter(Boolean)
                  .join(" · "),
                status: item.status,
                date: item.startDate ?? "",
                href: `/design-workshops/${item.id}`,
                actionLabel: "Open workshop",
                matchedIn: result.designWorkshopStageMatches?.[item.id]
              }))}
            />
          ) : null}
          <SearchPager page={page} shown={shown} hasMore={hasMore} loading={loading} onPage={setPage} />
        </div>
      ) : null}
      {applied?.q ? (
        <div className="mt-5">
          <RecordingSearchGroup q={applied.q} />
        </div>
      ) : null}
    </>
  );
}

function ResultLink({ item, className, children }: { item: ResultItem; className: string; children: ReactNode }) {
  if (item.external) {
    return (
      <a className={className} href={item.href} target="_blank" rel="noreferrer">
        {children}
      </a>
    );
  }
  return (
    <Link className={className} href={item.href}>
      {children}
    </Link>
  );
}

function ResultSection({ title, items, note }: { title: string; items: ResultItem[]; note?: string }) {
  if (items.length === 0) return null;
  return (
    <section className="panel overflow-hidden">
      <div className="border-b border-line-200 px-4 py-3">
        <h2 className="font-semibold text-ink-900">{title}</h2>
        {note ? <p className="mt-1 text-xs text-ink-500">{note}</p> : null}
      </div>
      <div className="divide-y divide-line-200">
        {items.map((item) => (
          <OpenableCard
            key={item.id}
            href={item.href}
            external={item.external}
            className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
          >
            <div className="min-w-0">
              <ResultLink item={item} className="font-medium text-ink-900 hover:text-purple-700 hover:underline">
                {item.title}
              </ResultLink>
              <div className="text-sm text-ink-700">{item.subtitle}</div>
              {item.matchedIn?.length ? (
                <div className="mt-0.5 text-xs text-ink-500">
                  Matched in {item.matchedIn.slice(0, MATCHED_IN_SHOWN).join(" · ")}
                  {item.matchedIn.length > MATCHED_IN_SHOWN
                    ? ` · and ${item.matchedIn.length - MATCHED_IN_SHOWN} more`
                    : ""}
                </div>
              ) : null}
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <StatusBadge status={item.status} />
              <span className="text-sm text-ink-700">{formatDateTime(item.date)}</span>
              <RowActions>
                <ResultLink item={item} className={rowAction("edit")}>
                  {item.actionLabel ?? "Open"}
                </ResultLink>
              </RowActions>
            </div>
          </OpenableCard>
        ))}
      </div>
    </section>
  );
}

function SearchPager({
  page,
  shown,
  hasMore,
  loading,
  onPage
}: {
  page: number;
  shown: number;
  hasMore: boolean;
  loading: boolean;
  onPage: (page: number) => void;
}) {
  return (
    <div className="panel flex flex-col gap-2 px-4 py-3 text-sm text-ink-700 sm:flex-row sm:items-center sm:justify-between">
      <span>
        Page {page} · {shown} result{shown === 1 ? "" : "s"} on this page · every result type pages together
      </span>
      <ActionGroup>
        <button className="field-button-secondary" disabled={loading || page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button className="field-button-secondary" disabled={loading || !hasMore} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </ActionGroup>
    </div>
  );
}
