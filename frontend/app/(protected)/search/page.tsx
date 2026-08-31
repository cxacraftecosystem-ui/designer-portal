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
import { Dropdown } from "@/components/ui/Dropdown";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import {
  useWorkshopScope,
  WorkshopScopeSelect,
  workshopScopeFromSearchParams
} from "@/components/WorkshopScopeSelect";
import { apiFetch, buildQuery, listResource } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import { canViewDesignWorkshopData } from "@/lib/permissions";
import type { Artisan, Craft, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";

type SearchResult = {
  artisans: Artisan[];
  workshops: Workshop[];
  products: ProductDocumentation[];
  tools: ToolDocumentation[];
  media: MediaFile[];
  /**
   * The SIXTH bucket — the twenty-two-stage record this product is named after. The `workshops`
   * bucket above is the LEGACY `Workshop` table, a different model entirely, so before this key
   * existed no design workshop was reachable from the screen labelled "Search".
   *
   * Optional, like every other additive key on this payload: a server that predates it simply does
   * not send it, and the section renders nothing rather than throwing.
   */
  designWorkshops?: DwSummary[];
  /** Rows matching the query in each bucket, ignoring the page window. */
  totals?: Partial<Record<SearchRecordType, number>>;
  /** Every bucket added together — what "N results" means to a researcher. */
  total?: number;
  /** Pages needed by the LONGEST bucket, since one pager walks all six at once. */
  pageCount?: number;
  /**
   * Buckets this account asked for and may not read. The server drops them from the selection and
   * NAMES them here rather than answering 0, because a bucket that comes back empty is
   * indistinguishable from a repository with nothing in it — and that is the sentence a researcher
   * would otherwise carry away.
   */
  typesRefused?: string[];
  /** What a design-workshop text query actually matched. Present only when that bucket was read. */
  designWorkshopSearchScope?: string;
  /**
   * Which stages a workshop matched IN, keyed by workshop id — `{ "dw_1": ["Stage 5: …"] }`.
   *
   * The bucket matches the workshop's own columns OR any answer inside its 22 stages, so a row can
   * come back for a reason that is nowhere on the row: the title says nothing about indigo and a
   * dye-bath answer in stage 5 does. Without this the reader would have to open twenty-two stages
   * to account for the hit.
   *
   * A workshop found by its title alone is simply ABSENT from the map, and the row then prints no
   * matched-in line. An empty array beside it would read as "we looked and found none".
   */
  designWorkshopStageMatches?: Record<string, string[]>;
};

/**
 * One row in a result bucket. `href` is the SAME destination the matching list page uses, so a hit
 * here opens the record instead of dead-ending: artisans, products and tools have `[id]/edit`
 * routes; workshops are edited inline on the workshops list, and a media file opens the object
 * itself (`external`), because neither has a per-record route to link to.
 */
type ResultItem = {
  id: string;
  title: string;
  subtitle: string;
  status: string;
  date: string;
  href: string;
  /** true when `href` leaves the app (an S3 object), so it opens in a new tab. */
  external?: boolean;
  /** Row-action label; defaults to "Open". */
  actionLabel?: string;
  /**
   * WHERE INSIDE THE RECORD THE QUERY MATCHED, when the match was not on anything printed above.
   * Only the design-workshop bucket sets it today (the stage names the server resolved), and the
   * line is drawn only when it has members: a row whose title carries the word needs no explanation.
   */
  matchedIn?: string[];
};

/**
 * How many "matched in" entries a row prints before the rest are counted.
 *
 * A workshop can legitimately answer one word in a dozen of its twenty-two stages, and twelve stage
 * titles on a search row is a paragraph where a subtitle should be. Three is what fits a phone-width
 * row on one line. THE REMAINDER IS COUNTED AND NEVER DROPPED — non-negotiable 10: a list that
 * quietly stops is indistinguishable from a list that ended there, and here it would understate how
 * much of a fortnight's fieldwork the researcher's word actually appears in.
 */
const MATCHED_IN_SHOWN = 3;

/**
 * Rows per bucket per page. `GET /search` caps pageSize at 50 and applies ONE shared skip/take to
 * all five buckets, so this is the page size of every bucket at once.
 */
const PAGE_SIZE = 20;

/** Filters as they were when Search was pressed — the pager must not drift with the live inputs. */
type AppliedFilters = { q: string; filters: SearchFilters };

export default function SearchPage() {
  /**
   * The URL SEEDS the filters, it does not own them.
   *
   * `?type=` narrows the page to one bucket and is how the dashboard's repository totals open — a
   * total is a question ("which 74 tools?") and the honest answer is the list of those tools, not a
   * page of five headings where four are empty. Reading it once on arrival keeps every link that
   * exists today working, while leaving the filters themselves in React state: they now include a
   * place, a date range and a multi-select, and if a chip click were a navigation it would remount
   * the page and quietly throw the other three away.
   *
   * The type filter is also still applied to the RENDER, not only to the request, so it keeps
   * working against an API that does not know `types` yet.
   */
  const searchParams = useSearchParams();
  const { user } = useAuth();

  /**
   * WHICH BUCKETS THIS READER IS OFFERED, and it is five or six rather than always six.
   *
   * Design-workshop data is readable by Professor, Admin and Master Admin
   * (`canViewDesignWorkshopData`, mirroring `deps.can_view_design_workshop_data`). `GET /search`
   * drops the bucket for anybody else and says so in `typesRefused`; offering the chip here would be
   * the UI advertising what the API refuses, which is the one thing `lib/permissions.ts` exists to
   * stop. The other five are unchanged for everybody.
   */
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

  /**
   * THE WORKSHOP SCOPE, and why this page has to have it.
   *
   * The map links here — "Browse as a list" — carrying its own workshop scope, and the two screens
   * share one filter vocabulary precisely so that link means the same set of records on both sides. A
   * search page that read the `workshopIds` in the URL and then ignored it would answer a scoped
   * question with the whole repository, silently, which is worse than not offering the link.
   *
   * `defaultToMostRecent` is FALSE here. `/search` is the general way in to the corpus and its default
   * has always been "everything"; quietly narrowing it to last week's workshop would change what every
   * existing bookmark to this page means. The map and the completion matrix default the other way
   * because they are read DURING a workshop.
   */
  const scope = useWorkshopScope({
    defaultToMostRecent: false,
    initialWorkshopIds: workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()))
  });

  // Arriving from a dashboard total (or any /search?type=… link, including the map's "Browse as a
  // list") must SHOW the list, not an empty form: the click already said what it wanted. An empty q is
  // a valid search here — it means "everything of this type" — which is exactly what a total is asking
  // for, and what a bare workshop scope is asking for too.
  const arrived = useRef(false);
  useEffect(() => {
    if (arrived.current) return;
    const seeded = filtersFromSearchParams(new URLSearchParams(searchParams.toString()), offeredTypes);
    const q = (searchParams.get("q") ?? "").trim();
    const scoped = workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()));
    // The three filters added on 2026-08-31 count as a reason to search on arrival, exactly as a
    // place or a type does: a link that carried `?craftId=…` and then showed a blank form would be
    // the filter looking broken on the one path that proves it works.
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
    setApplied({ q, filters: seeded });
  }, [searchParams, offeredTypes]);

  /**
   * THE CRAFT AND ARTISAN LISTS THE TWO NEW PICKERS NEED — loaded once, and a convenience rather
   * than a dependency: if either lookup fails the picker simply stays hidden and the text, place,
   * type, date and media-type filters still work. That is `SearchScreen.kt`'s rule, in its words.
   *
   * `LIST_PAGE_CEILING` and `listCut`, like `FunnelFilters` — the corpus is larger than one page
   * (178 crafts, 749 artisans when last counted) and a picker that quietly showed the first hundred
   * would be a filter offering a subset of the repository while claiming to offer all of it. What is
   * cut is SAID under the controls, which is the only thing that makes the cap honest.
   */
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [cuts, setCuts] = useState<{ crafts: ListCut | null; artisans: ListCut | null }>({
    crafts: null,
    artisans: null
  });

  useEffect(() => {
    let cancelled = false;
    listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setCrafts(result.items);
        setCuts((previous) => ({ ...previous, crafts: listCut(result, "crafts") }));
      })
      .catch(() => undefined);
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

  // Runs on submit (new `applied` object, even for the same text), on every filter change, on every
  // workshop-scope change and on every page step. Every active filter goes into ONE query —
  // buildQuery drops the keys that resolved to nothing, so an untouched filter costs nothing.
  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    setLoading(true);
    apiFetch<SearchResult>(
      `/search${buildQuery({
        q: applied.q,
        ...searchFilterParams(applied.filters),
        workshopIds: scope.queryValue,
        page,
        pageSize: PAGE_SIZE
      })}`
    )
      .then((data) => {
        if (cancelled) return;
        setResult(data);
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
  }, [applied, page, scope.queryValue]);

  /**
   * A WORKSHOP PICK IS A SEARCH IN ITS OWN RIGHT, and without this it was a control that did nothing.
   *
   * Everything on this page hangs off `applied`, which starts null and is only set by the arrival
   * effect, Search, or a filter chip. So on a bare `/search` — no query, no type, no place — choosing a
   * workshop left `applied` null, both effects returned at their guard, and no request was made: the
   * picker moved, the list did not, and nothing said why. "Everything from this workshop" is a complete
   * question with an empty text box, exactly as "everything of this type" is, so it has to be able to
   * start a search on its own.
   */
  useEffect(() => {
    if (applied || !scope.workshopIds.length) return;
    setApplied({ q: "", filters: EMPTY_SEARCH_FILTERS });
  }, [applied, scope.workshopIds.length]);

  // The chips used to be links, so a narrowed search was a URL you could send someone or reload.
  // They are buttons now — a navigation would remount the page and take the other filters with it —
  // so the address bar is written back by hand instead. history.replaceState rather than the router:
  // this records where the researcher already is, it does not navigate anywhere.
  //
  // The workshop scope is written back too, so the link a researcher copies out of the address bar
  // reproduces the list they are actually looking at — and can be pasted into /map, which reads the
  // same key.
  useEffect(() => {
    if (!applied) return;
    // FROM THE FIRST WRITE-BACK ON, THE ADDRESS BAR IS THIS PAGE'S OUTPUT, not its input. The arrival
    // effect is a one-shot seed guarded by `arrived`, and it now also seeds from `workshopIds` — so
    // without claiming the shot here, this write could hand the seeder a URL the page itself had just
    // written and have it re-seed the filters from a value the user had since changed.
    arrived.current = true;
    window.history.replaceState(
      null,
      "",
      `/search${buildQuery({
        q: applied.q,
        ...filtersToLinkParams(applied.filters),
        workshopIds: scope.queryValue
      })}`
    );
  }, [applied, scope.queryValue]);

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(1);
    setApplied({ q: query.trim(), filters });
  }

  /**
   * A chip, a date or a type tick re-runs at once — it narrows the question already asked rather
   * than asking a new one, so it carries the query text that is IN EFFECT rather than whatever is
   * half-typed in the box. That is the same bargain the box has always made on this page: text is
   * applied when Search is pressed, everything else the moment it changes.
   */
  function changeFilters(next: SearchFilters) {
    setFilters(next);
    setPage(1);
    setApplied((current) => ({ q: current?.q ?? "", filters: next }));
  }

  const show = (id: SearchRecordType) => typeVisible(filters, id);
  // The sixth bucket is dropped from the RENDER as well as from the request whenever this reader is
  // not offered it, so a stale `applied` from a link cannot put a section on screen that the chips
  // above it cannot express or clear.
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
  // /search returns a real `pageCount` (the page count of its longest bucket), so "Next" is exact.
  // The old heuristic — "some bucket exactly filled the page" — walked one page too far whenever a
  // bucket's total happened to be a multiple of PAGE_SIZE, landing the researcher on an empty page.
  //
  // Recomputed from the SELECTED buckets' totals when they are there, because `pageCount` is the
  // longest of all five: an API that ignored `types` would otherwise offer pages that exist only in
  // a bucket this search is not showing. The two fallbacks keep the page working against an API
  // that predates either key.
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
      {/* Above the search box on purpose: a scan is a search whose query is exact, so when a designer
          has the tag in their hand there is nothing to type and nothing to narrow. It reads every
          record type this app prints a code for and opens the record it names. */}
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
      {/*
        Place stays in the form row where it has always been, and the advanced panel therefore does
        not repeat it: one value with two boxes on screen at once is the same confusion the chips and
        the multi-select are carefully avoiding. It is applied with the text, on Search, because it
        is typed — the panel's filters are clicked, so they apply immediately.
      */}
      <SearchFilterBar
        value={filters}
        onChange={changeFilters}
        showPlace={false}
        offeredTypes={offeredTypes}
        // Craft, artisan and media type are this screen's own — the shared filters above them are
        // one implementation, and an addition declared here cannot quietly become a second copy that
        // drifts. `advanceOnSelect={false}` on all three because they NARROW THE LIST BELOW rather
        // than fill a form field, and `searchable` because every option is a record: the count is a
        // fact about this deployment's corpus, not about the control.
        extraFilters={
          <>
            {/* `FieldBlock` and NOT `Field`, and not a bare span with an `ariaLabel` either. A themed
                dropdown is a `<button>`, so its accessible name comes from its own contents — the
                VALUE, and never the question. `FieldBlock` publishes its label's id through context
                and the trigger composes `aria-labelledby="<label> <self>"`, which announces "Craft,
                Bamboo". An explicit `ariaLabel` would win and drop the value; a wrapping `<label>`
                would slam the menu shut on the first pick. */}
            <div className="grid gap-3 sm:grid-cols-3">
              {crafts.length ? (
                <FieldBlock label="Craft">
                  <Dropdown
                    value={filters.craftId}
                    onChange={(craftId) => changeFilters({ ...filters, craftId })}
                    advanceOnSelect={false}
                    searchable
                    options={[
                      { value: "", label: "Any craft" },
                      ...crafts.map((craft) => ({ value: craft.id, label: craft.name }))
                    ]}
                  />
                </FieldBlock>
              ) : null}
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
                {/* No `searchable`: six members of a constant vocabulary, so the option count is a
                    fact about the control rather than about this deployment's corpus. The two above
                    are records and pass it for exactly the opposite reason. */}
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
            {/* Android says exactly this under the same three controls. It matters because two of
                them narrow only some of the buckets: a craft reaches artisans, products and tools,
                a media type reaches media alone, and without the sentence a researcher reads an
                unchanged Workshops list as the filter not working. */}
            <p className="text-xs text-ink-500">
              Craft, artisan and media type narrow only the buckets that carry them.
            </p>
            {/* One line per picker that is not offering everything it claims to filter by. Nothing
                at all when both are whole — rule 10: a list that quietly stops is indistinguishable
                from a place with no records. */}
            {[cuts.crafts, cuts.artisans].map((cut, index) =>
              cut ? (
                <p key={index} className="text-xs text-ink-500">
                  {cappedListNotice(cut)}
                </p>
              ) : null
            )}
          </>
        }
      />

      {/* WHAT THE SERVER REFUSED. A bucket that comes back empty is indistinguishable from a
          repository with nothing in it, so a bucket this account may not read has to be NAMED — it
          is the difference between "no design workshops matched" and "design workshops were not
          looked at". The list is the server's; the sentence is this client's, because it names the
          NEXT MOVE and the API has no business writing a web page's copy. (The other server
          sentence, `designWorkshopSearchScope`, is printed by the bucket it describes, not here.) */}
      {result?.typesRefused?.length ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          Design workshops are not searched at your access level. Ask an admin.
        </p>
      ) : null}

      {/* The workshop scope applies IMMEDIATELY, like the panel filters and unlike the typed boxes
          above: it is a picker, and a picker that needed a second button press to take effect reads
          as broken. */}
      <div className="mb-4 max-w-xl">
        {/* THE PAGE GOES BACK TO 1, as it does for `submit` and every filter chip. The workshop scope
            was the one filter on this page that left pagination standing, so narrowing from four pages
            of results to one, while sitting on page 4, produced five empty buckets and an
            "No more results" message — for a scope that plainly has records in it. `setPage` and
            `setWorkshopIds` are batched in one handler, so exactly one request goes out. */}
        <WorkshopScopeSelect
          scope={{
            ...scope,
            setWorkshopIds: (next: string[]) => {
              setPage(1);
              scope.setWorkshopIds(next);
            }
          }}
          label="Workshops"
        />
      </div>

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
              // Place alone unless the craft really came back. `GET /search` reads artisans with no
              // `include`, so `craft` is never populated here and the old "No craft" fallback
              // labelled EVERY artisan hit as craftless — a statement about the API's include list
              // dressed up as a fact about the artisan. The join is kept for the day the endpoint
              // does include it.
              subtitle: [item.place, item.craft?.name].filter(Boolean).join(" · "),
              status: item.status,
              date: item.createdAt,
              href: `/artisans/${item.id}/edit`
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
              // Workshops are created and edited inline on their list page — there is no
              // /workshops/[id] — so the id travels as `?edit=`, which that page reads via
              // `useEditDeepLink`. Linking to the bare list dropped the id and opened a create form.
              href: `/workshops?edit=${item.id}`,
              actionLabel: "Open in Workshops"
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
              href: `/products/${item.id}/edit`
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
              href: `/tools/${item.id}/edit`
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
              // A media file has no detail page: play/open the object when it has a URL, otherwise
              // fall back to the Miscellaneous Media list.
              href: item.url ?? "/media",
              external: Boolean(item.url),
              actionLabel: item.url ? "Open file" : "Open in Media"
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
                // The promoted columns, which are the axes a researcher filters on — and which are
                // null until stage 1 has been saved, so a freshly opened workshop legitimately shows
                // a title and nothing else rather than a row of the word "null".
                subtitle: [item.workshopCode, item.craftName, item.clusterName ?? item.district, item.state]
                  .filter(Boolean)
                  .join(" · "),
                status: item.status,
                date: item.startDate ?? "",
                href: `/design-workshops/${item.id}`,
                actionLabel: "Open workshop",
                // The stage names come from the SERVER, resolved from its own registry. Deriving
                // them here would be a second copy of the twenty-two titles in a client that has no
                // reason to hold them, and the two would disagree the first time a stage was
                // retitled — on the one line whose whole job is to say where to look.
                matchedIn: result.designWorkshopStageMatches?.[item.id]
              }))}
            />
          ) : null}
          <SearchPager page={page} shown={shown} hasMore={hasMore} loading={loading} onPage={setPage} />
        </div>
      ) : null}
    </>
  );
}

/** A result title / row action: an in-app `<Link>`, or a new-tab `<a>` for an S3 object. */
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
        {/* The server's own sentence about what this bucket's text query matched, printed where the
            matches are. Nothing invents it here: a client that wrote its own would be a second
            description of one rule, able to drift from the server that enforces it. */}
        {note ? <p className="mt-1 text-xs text-ink-500">{note}</p> : null}
      </div>
      <div className="divide-y divide-line-200">
        {items.map((item) => (
          <div key={item.id} className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <ResultLink item={item} className="font-medium text-ink-900 hover:text-purple-700 hover:underline">
                {item.title}
              </ResultLink>
              <div className="text-sm text-ink-700">{item.subtitle}</div>
              {/* WHY THIS ROW CAME BACK, when nothing above it says so. The design-workshop bucket
                  matches stage answers as well as the workshop's own columns, so a hit whose word
                  appears in neither the title nor the subtitle is otherwise unaccountable — the
                  researcher would have to open twenty-two stages to find it. Drawn only when the
                  server named a stage. */}
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
          </div>
        ))}
      </div>
    </section>
  );
}

/**
 * Prev/Next footer for the search results, styled like the shared `<Pagination>` but NOT it: that
 * component prints "Page x of y · n records", and `GET /search` returns neither a page count nor a
 * total — it pages all six buckets with one shared skip/take. So this pager states only what the
 * contract really knows: the page number, how many rows this page holds, and whether another page
 * may exist (some bucket came back full).
 */
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
      <div className="flex gap-2">
        <button className="field-button-secondary" disabled={loading || page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button className="field-button-secondary" disabled={loading || !hasMore} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
