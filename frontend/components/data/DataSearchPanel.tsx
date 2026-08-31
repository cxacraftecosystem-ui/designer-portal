"use client";

/**
 * Search, at the top of View Data.
 *
 * The Android app puts a search box on its data screen because that is how a researcher actually
 * arrives: they know the artisan's name, not which of nine craft folders they are filed under.
 * Walking the tree is the browsing path; this is the finding path, and until now the web only had
 * the first one here — the search page was somewhere else entirely, and it did not hand you back
 * into the folder you wanted.
 *
 * Every hit therefore does two things. "Open" goes to the record's own page (the same destination
 * the list pages use). "Show in folders" resolves the record to its place in the tree and moves the
 * browser below to it, which is the whole point of having search on this page rather than its own.
 */

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
import { apiFetch, buildQuery } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import { canRunDesignWorkshops, canViewDesignWorkshopData } from "@/lib/permissions";
import type { Artisan, MediaFile, ProductDocumentation, ToolDocumentation, Workshop } from "@/lib/types";

type SearchResult = {
  artisans: Artisan[];
  workshops: Workshop[];
  products: ProductDocumentation[];
  tools: ToolDocumentation[];
  media: MediaFile[];
  /**
   * The SIXTH bucket. The "workshops" bucket above is the LEGACY `Workshop` table — a different model
   * from `DesignWorkshop` with no join between them — so until this panel read this key, the product
   * this repository is named after was unfindable from the search box on the screen whose whole job
   * is finding things.
   *
   * OPTIONAL, because the key is absent from a server that predates it and from a response to an
   * account the API refused the bucket to. Absent and empty are the same thing to read here; what
   * must never happen is a heading with nothing under it.
   */
  designWorkshops?: DwSummary[];
  /**
   * What a text query over that bucket actually matched, in the SERVER's words.
   *
   * Printed verbatim and never paraphrased: it is a statement about what the query did, the server
   * is the only thing that knows, and a client sentence would go stale the day the bucket learns to
   * search something new. Sent only when the bucket was searched, so its presence is also the test
   * for whether to print it.
   */
  designWorkshopSearchScope?: string;
  /** Rows matching in each bucket, ignoring the page window — so "of N" can respect the type filter. */
  totals?: Partial<Record<SearchRecordType, number>>;
  total?: number;
};

/** Where a hit lives in the tree, resolved server-side (`GET /data/locate`). */
type LocateResult = { path: string | null };

type Row = {
  key: string;
  // "Design workshop", SINGULAR AND SPELT OUT, never "Workshop": the two buckets are different tables
  // and a pill a reader cannot tell apart is a pill that says nothing. Same distinction
  // `SearchFilters.TYPE_LABEL` makes for the chip.
  kind: "Artisan" | "Workshop" | "Product" | "Tool" | "Media" | "Design workshop";
  title: string;
  subtitle: string;
  href: string | null;
  external?: boolean;
  /** (recordType, id) the tree can resolve; null for rows the tree does not file individually. */
  locate: { type: string; id: string } | null;
};

const EMPTY: Row[] = [];

/**
 * The buckets are dropped here as well as in the request, because `types` is the one filter key the
 * API may not know yet: an older server answers with all five buckets, and a panel that trusted it
 * would show artisans to someone who asked for media only.
 *
 * `offeredTypes` IS THE ENTITLEMENT HALF AND IT IS SEPARATE FROM THE FILTER. The design-workshop
 * bucket exists for Professor and above only (`canViewDesignWorkshopData`), and `GET /search` drops
 * it from the selection for everybody else — so a panel that rendered `result.designWorkshops`
 * because the filter allowed it would be trusting a key the API is entitled not to send. Both tests,
 * in that order, exactly as `/search` does it.
 */
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
      href: `/artisans/${a.id}/edit`,
      locate: { type: "artisan", id: a.id }
    });
  }
  for (const p of typeVisible(filters, "products") ? (result.products ?? []) : []) {
    rows.push({
      key: `product-${p.id}`,
      kind: "Product",
      title: p.productName,
      subtitle: p.artisanName ?? "",
      href: `/products/${p.id}/edit`,
      locate: { type: "product", id: p.id }
    });
  }
  for (const t of typeVisible(filters, "tools") ? (result.tools ?? []) : []) {
    rows.push({
      key: `tool-${t.id}`,
      kind: "Tool",
      title: t.toolkitName,
      subtitle: t.artisanName ?? "",
      href: `/tools/${t.id}/edit`,
      locate: { type: "tool", id: t.id }
    });
  }
  for (const w of typeVisible(filters, "workshops") ? (result.workshops ?? []) : []) {
    rows.push({
      key: `workshop-${w.id}`,
      kind: "Workshop",
      title: w.title,
      subtitle: [w.place, w.date ? new Date(w.date).toLocaleDateString() : ""].filter(Boolean).join(" · "),
      // The id has to travel: a workshop is edited inline on its list page, so `?edit=` is its
      // equivalent of the `/[id]/edit` route the other record types have. A bare "/workshops" here
      // opened the blank create form, even though `locate` on the next line proves the id was known.
      href: `/workshops?edit=${w.id}`,
      locate: { type: "workshop", id: w.id }
    });
  }
  for (const m of typeVisible(filters, "media") ? (result.media ?? []) : []) {
    rows.push({
      key: `media-${m.id}`,
      kind: "Media",
      title: m.caption || m.originalFilename,
      subtitle: [m.mediaType, m.uploadedBy?.name].filter(Boolean).join(" · "),
      href: m.url ?? null,
      external: true,
      locate: { type: "media", id: m.id }
    });
  }
  // LAST, matching the order `/search` renders its six sections in and the order
  // `SEARCH_RECORD_TYPES` declares — one screen must not read the buckets in a different order from
  // the other.
  const showDesignWorkshops =
    offeredTypes.includes(DESIGN_WORKSHOP_TYPE) && typeVisible(filters, DESIGN_WORKSHOP_TYPE);
  for (const w of showDesignWorkshops ? (result.designWorkshops ?? []) : []) {
    rows.push({
      key: `design-workshop-${w.id}`,
      kind: "Design workshop",
      title: w.title,
      // The promoted columns, which are the axes a researcher filters on - and which are null until
      // stage 1 has been saved, so a freshly opened workshop legitimately shows a title and nothing
      // else rather than a row of the word "null".
      subtitle: [w.workshopCode, w.craftName, w.clusterName ?? w.district, w.state]
        .filter(Boolean)
        .join(" · "),
      /*
        "OPEN" IS OFFERED ONLY TO SOMEBODY THE WORKSHOP ROUTE WILL LET IN, AND FOR A PROFESSOR IT IS
        NOT. Two different predicates govern one row here: `canViewDesignWorkshopData` (Professor and
        above) decides whether the row is READ at all, and `canRunDesignWorkshops` — which
        deliberately EXCLUDES professor, see `deps.can_run_design_workshops` — is what
        `/design-workshops/[id]` is guarded on. So the population this bucket was added for is
        precisely the population that route refuses, and a link would hand them a refusal page for a
        record they are entitled to read.

        "Show in folders" is the right destination for them anyway, and is why this bucket could be
        added here at all: it resolves to `by-design-workshop/<id>` in View Data, which is the
        surface their access was granted for. A null `href` renders no Open link at all — the row
        keeps the one action that works rather than growing a disabled one that explains itself.
      */
      href: mayOpenWorkshops ? `/design-workshops/${w.id}` : null,
      // `designWorkshop`, camelCase, as `GET /data/locate` declares it; it lower-cases what it is
      // handed. The tree path it answers is the `by-design-workshop` taxonomy.
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
  // A DEEPER PURPLE THAN "Workshop", on purpose. The two pills sit in one list over two different
  // tables, and purple-50/700 beside purple-200/900 is the strongest distinction available without
  // reaching for a second accent colour, which a data screen does not get.
  "Design workshop": "bg-purple-200 text-purple-900"
};

export function DataSearchPanel({ onReveal }: { onReveal: (path: string) => void }) {
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const [filters, setFilters] = useState<SearchFilters>(EMPTY_SEARCH_FILTERS);
  const [rows, setRows] = useState<Row[]>(EMPTY);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealing, setRevealing] = useState<string | null>(null);
  /**
   * What the server says a text query over the design-workshop bucket actually matched, or null.
   *
   * IT IS STATE AND NOT A READ OFF THE LAST RESULT because this panel keeps no result object — it
   * keeps rows. Held beside them, cleared with them, so a sentence can never outlive the search it
   * describes and sit under a different query's results.
   */
  const [scopeNote, setScopeNote] = useState<string | null>(null);

  /**
   * Which buckets this panel offers — six for Professor and above, the original five for everybody
   * else. Mirrors `/search`'s own `offeredTypes` exactly, and `deps.can_view_design_workshop_data`
   * through it.
   *
   * THE UI NEVER OFFERS WHAT THE API REFUSES: `GET /search` drops `designWorkshops` from the
   * selection for an account without the rank, so a chip that let a researcher tick it would be a
   * control whose only possible outcome is an empty bucket — and an empty bucket reads as "there are
   * no design workshops", which is the one thing it must never say.
   */
  const offeredTypes = useMemo<readonly SearchRecordType[]>(
    () => (canViewDesignWorkshopData(user) ? SEARCH_RECORD_TYPES : RECORD_TYPES),
    [user]
  );
  // A SECOND, NARROWER PREDICATE FOR ONE LINK. See the `href` in `rowsFrom`: reading these workshops
  // and opening one are different entitlements, and a professor holds only the first.
  const mayOpenWorkshops = canRunDesignWorkshops(user);
  // Every keystroke would be a request; a request per pause is one per word typed.
  const timer = useRef<number | null>(null);
  // Late responses from a query the user has already changed must not overwrite the current rows.
  const generation = useRef(0);
  // The text the last run was scheduled for, so a clicked filter can skip the typist's pause.
  const typedBefore = useRef({ q: "", place: "" });

  // `filters` is passed in rather than closed over so this stays a stable callback: it is the thing
  // the debounce timer fires, and a callback that changed identity on every filter tick would
  // restart the timer instead of the filter doing it. `offered` and `mayOpen` travel the same way and
  // for the same reason — they change when the signed-in account resolves, which is a render this
  // callback must not be rebuilt by.
  const run = useCallback(
    async (
      text: string,
      active: SearchFilters,
      offered: readonly SearchRecordType[],
      mayOpen: boolean
    ) => {
      const q = text.trim();
      const mine = ++generation.current;
      // Two characters of text OR any filter at all. A chip on its own is a real question here —
      // "the media from this workshop week" — and answering it with a blank panel reads as broken.
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
        // Counted over the SELECTED buckets when per-bucket totals are there, so "of N" cannot promise
        // more results than the filter allows; `total` is every bucket added up, which is only the
        // right number when every bucket is being shown.
        //
        // OVER `offered` AND NOT `RECORD_TYPES`, which was a real undercount the moment a sixth
        // bucket existed: "Showing 8 of 12" while the server had matched fourteen, and the missing
        // two were design workshops — the reader is told there is nothing more to see in exactly the
        // bucket this panel had just learned to show.
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
    // Typing waits for a pause; a chip, a date or a type tick is one deliberate click and refreshes
    // at once. Both still go through the single timer, so the cancel-and-reschedule path — and with
    // it the generation guard — stays the only way a request is ever made.
    const typed = query !== typedBefore.current.q || filters.place !== typedBefore.current.place;
    typedBefore.current = { q: query, place: filters.place };
    if (timer.current) window.clearTimeout(timer.current);
    // `offeredTypes` IS A DEPENDENCY, AND THE RE-RUN IT CAUSES IS THE POINT. It is derived from the
    // signed-in account, which resolves after mount — so the first search a professor runs on a cold
    // page would otherwise ask for, and render, five buckets. Re-running when it settles is not a
    // torn-down fetch (nothing is in flight that this cancels beyond the debounce timer it owns) and
    // it costs nothing at all below professor, where the value never changes.
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
    <section className="panel mb-4 grid gap-3 p-4">
      <div>
        <h2 className="font-display text-base font-bold text-ink-900">Search the repository</h2>
        {/* Two sentences, and the list names only what this reader's search actually covers — a
            description that promised design workshops to an account whose bucket the API drops would
            be the panel lying about its own scope. */}
        <p className="mt-0.5 text-xs text-ink-500">
          Artisans, products, tools, workshops
          {offeredTypes.includes(DESIGN_WORKSHOP_TYPE) ? ", design workshops" : ""} and media by name, place or
          caption. Open a hit, or show it where it sits in the folders below.
        </p>
      </div>

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

      {/* Directly under the field, so the chips read as part of the search rather than a separate tool. */}
      <SearchFilterBar value={filters} onChange={setFilters} className="" offeredTypes={offeredTypes} />

      {error ? <p className="text-xs text-error-600">{error}</p> : null}

      {/* THE SERVER'S OWN SENTENCE ABOUT WHAT THE DESIGN-WORKSHOP BUCKET MATCHED, printed verbatim.
          Without it an empty result reads as "no workshop recorded that" rather than "this server did
          not look there" — the bucket's scope is a fact only the server holds, and `/search` prints
          the same string beside its own results. Sent only when the bucket was searched, so its
          presence is the whole condition. */}
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
              <li
                key={row.key}
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
                    className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700 disabled:opacity-50"
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
                        className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700"
                      >
                        <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                        Open
                      </a>
                    ) : (
                      <Link href={row.href} className="text-xs font-semibold text-purple-700">
                        Open
                      </Link>
                    )
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
          {total > rows.length ? (
            <p className="text-xs text-ink-500">
              Showing {rows.length} of {total}.{" "}
              {/* The filters travel with the link, so the full page opens on the same search rather
                  than a wider one the researcher then has to narrow all over again. */}
              <Link
                href={`/search${buildQuery({ q: query.trim() || undefined, ...filtersToLinkParams(filters) })}`}
                className="font-semibold text-purple-700"
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
