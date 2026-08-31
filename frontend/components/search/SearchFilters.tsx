"use client";

/**
 * The filter controls behind BOTH repository searches: the Search page and the search panel at the
 * top of View Data.
 *
 * They were drifting apart — the Search page had type chips and a place box, the View Data panel
 * had a text field and nothing else — which meant the same question got two different answers
 * depending on which screen a researcher happened to be standing on. The vocabulary (what a "type"
 * is, what "Last 30 days" resolves to, how the filters become a query string) lives here once, so
 * the two screens cannot disagree again.
 */

import { useState, type ReactNode } from "react";
import { SlidersHorizontal } from "lucide-react";

import { DateField } from "@/components/forms/DateTimeField";
import { Dropdown } from "@/components/ui/Dropdown";

/**
 * The five buckets EVERY search-shaped screen has, in the order the results are rendered.
 *
 * The map's vocabulary as well as the search box's, which is why the sixth bucket below is not in
 * here: `GET /map/points` groups by `locationId`/`place` and a design workshop has neither column,
 * so sending it there is a 422 rather than an empty bucket. Mirrors
 * `backend/app/services/record_filters.py::RECORD_TYPES`.
 */
export const RECORD_TYPES = ["artisans", "workshops", "products", "tools", "media"] as const;
export type RecordType = (typeof RECORD_TYPES)[number];

/**
 * The SIXTH bucket, on `GET /search` only.
 *
 * The "workshops" bucket above is the LEGACY `Workshop` table — a different model from
 * `DesignWorkshop`, with no join between them — so until this existed neither a design workshop,
 * nor its stages, nor its fields was reachable from the screen labelled "Search". Mirrors
 * `backend/app/services/record_filters.py::DESIGN_WORKSHOP_TYPE`, spelling included: the API's
 * `types` parameter folds case for the comparison and echoes back its own spelling, so a client that
 * sent `designworkshops` would get `designWorkshops` back and fail its own `types.includes` test.
 */
export const DESIGN_WORKSHOP_TYPE = "designWorkshops" as const;

/** The six buckets `GET /search` returns. `SEARCH_TYPES` on the server. */
export const SEARCH_RECORD_TYPES = [...RECORD_TYPES, DESIGN_WORKSHOP_TYPE] as const;
export type SearchRecordType = (typeof SEARCH_RECORD_TYPES)[number];

/**
 * Chip vocabulary: the buckets plus "all". Also the `?type=` values the dashboard links use.
 *
 * WHICH buckets a given bar offers is the CALL SITE's answer (`SearchFilterBar`'s `offeredTypes`),
 * not this constant's: the map may not offer the sixth, and neither may a screen whose reader has
 * no design-workshop access. This list is the widest set that exists, so a label is never missing.
 */
export const CHIP_IDS = ["all", ...SEARCH_RECORD_TYPES] as const;
export type ChipId = (typeof CHIP_IDS)[number];

export const TYPE_LABEL: Record<ChipId, string> = {
  all: "Everything",
  artisans: "Artisans",
  workshops: "Workshops",
  products: "Products",
  tools: "Tools",
  media: "Media",
  // Android's `SearchRecordTypes` label for the same bucket. Plural, and NOT "Workshops" — the two
  // buckets are different tables and a reader has to be able to tell which one a heading counts.
  designWorkshops: "Design workshops"
};

/**
 * Media types `GET /search` accepts for `mediaType`, in the backend enum's own order.
 * `android/.../ui/SearchScreen.kt::SEARCH_MEDIA_TYPES` is the same list in the same order.
 */
export const SEARCH_MEDIA_TYPES = ["IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"] as const;

export const RANGE_IDS = ["any", "today", "7d", "30d", "90d", "month", "year", "custom"] as const;
export type RangeId = (typeof RANGE_IDS)[number];

const RANGE_OPTIONS: Array<{ value: RangeId; label: string }> = [
  { value: "any", label: "Any time" },
  { value: "today", label: "Today" },
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
  { value: "90d", label: "Last 90 days" },
  { value: "month", label: "This month" },
  { value: "year", label: "This year" },
  { value: "custom", label: "Custom range" }
];

export type SearchFilters = {
  /**
   * The record types to search. EMPTY MEANS EVERYTHING — the set never lists every bucket
   * explicitly, so "nothing ticked" and "everything ticked" cannot both exist and mean the same
   * thing.
   */
  types: SearchRecordType[];
  place: string;
  range: RangeId;
  /** `yyyy-mm-dd` from the two date inputs; only read when `range` is "custom". */
  from: string;
  to: string;
  /**
   * THE THREE FILTERS THE API AND ANDROID BOTH HAD AND THE WEB DID NOT (added 2026-08-31).
   *
   * `GET /search` has taken `craftId`, `artisanId` and `mediaType` all along
   * (`backend/app/api/routes/search.py`), and `android/.../ui/SearchScreen.kt` has had real pickers
   * for all three. The web sent none of them, so the same three questions had two different answers
   * depending on which client the researcher was holding — which is the exact drift the header of
   * this file says it exists to prevent, occurring inside the vocabulary it defines.
   *
   * THEY LIVE IN THE SHARED VALUE AND THE CONTROLS DO NOT, which is Android's shape exactly: its
   * `SearchFilters` data class carries all three and its screen renders the pickers through an
   * `extraFilters` slot. The reason is that the pickers need the craft and artisan LISTS, and a
   * shared bar that fetched them would make every consumer — the map, the View Data panel — pay two
   * requests for controls they do not draw.
   *
   * Empty string means "do not filter", the same way a blank place box does, and `buildQuery` drops
   * it. An id is a cuid, so there is no reserved value to collide with.
   */
  craftId: string;
  artisanId: string;
  mediaType: string;
};

export const EMPTY_SEARCH_FILTERS: SearchFilters = {
  types: [],
  place: "",
  range: "any",
  from: "",
  to: "",
  craftId: "",
  artisanId: "",
  mediaType: ""
};

function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function endOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999);
}

function daysBefore(date: Date, days: number) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() - days);
}

/**
 * `yyyy-mm-dd` as a LOCAL date. `new Date("2026-07-20")` is parsed as UTC midnight, which is the
 * previous day for anyone west of Greenwich — the one bug every date filter is born with.
 */
function parseDateInput(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Presets become concrete instants HERE, not on the server: "Last 30 days" means 30 days as the
 * researcher's clock sees them, and only the browser knows that clock. The boundaries are built in
 * local time and serialised as UTC instants, so an inclusive end date really does include the whole
 * of that day instead of stopping at its midnight.
 */
export function resolveRange(filters: SearchFilters, now: Date = new Date()): { dateFrom?: string; dateTo?: string } {
  const untilNow = { dateTo: endOfDay(now).toISOString() };
  switch (filters.range) {
    case "today":
      return { dateFrom: startOfDay(now).toISOString(), ...untilNow };
    case "7d":
      return { dateFrom: daysBefore(now, 6).toISOString(), ...untilNow };
    case "30d":
      return { dateFrom: daysBefore(now, 29).toISOString(), ...untilNow };
    case "90d":
      return { dateFrom: daysBefore(now, 89).toISOString(), ...untilNow };
    case "month":
      return { dateFrom: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(), ...untilNow };
    case "year":
      return { dateFrom: new Date(now.getFullYear(), 0, 1).toISOString(), ...untilNow };
    case "custom": {
      const from = parseDateInput(filters.from);
      const to = parseDateInput(filters.to);
      return {
        dateFrom: from ? startOfDay(from).toISOString() : undefined,
        dateTo: to ? endOfDay(to).toISOString() : undefined
      };
    }
    default:
      return {};
  }
}

/**
 * The filters as query keys for `GET /search`, to be spread into one `buildQuery` call alongside
 * `q` and the pager — every active filter therefore ANDs into a single request rather than being
 * applied in passes.
 *
 * `types` is sent comma-joined and is the one key the API may not know yet. That is survivable
 * because it is an optimisation, not the filter itself: both screens also drop the buckets they
 * were not asked for when they render, so an API that ignores `types` still shows the right rows —
 * it just counts and pages the hidden buckets too.
 */
export function searchFilterParams(filters: SearchFilters) {
  return {
    place: filters.place.trim() || undefined,
    types: typeList(filters),
    // `GET /map/points` takes all three as well, so a map opened from a filtered search narrows the
    // same way. A screen that draws no picker leaves them blank and sends nothing.
    craftId: filters.craftId || undefined,
    artisanId: filters.artisanId || undefined,
    mediaType: filters.mediaType || undefined,
    ...resolveRange(filters)
  };
}

/**
 * The selected types as one canonical `types=` value. Always in bucket order and de-duplicated, so
 * the same three types cannot produce two different query strings — and therefore two cache entries
 * and two "why did that reload?" — depending on which control the researcher ticked first.
 */
function typeList(filters: SearchFilters): string | undefined {
  const ordered = SEARCH_RECORD_TYPES.filter((type) => filters.types.includes(type));
  return ordered.length ? ordered.join(",") : undefined;
}

/** Whether a bucket survives the type filter — the client half of the `types` contract. */
export function typeVisible(filters: SearchFilters, type: SearchRecordType) {
  return filters.types.length === 0 || filters.types.includes(type);
}

/**
 * How many filters the disclosure is hiding. Types are deliberately NOT counted: the chip row shows
 * them whether the panel is open or shut, and a badge that counts something already on screen reads
 * as a second, disagreeing filter.
 */
export function activeFilterCount(filters: SearchFilters) {
  return (
    (filters.place.trim() ? 1 : 0) +
    (filters.range !== "any" ? 1 : 0) +
    (filters.craftId ? 1 : 0) +
    (filters.artisanId ? 1 : 0) +
    (filters.mediaType ? 1 : 0)
  );
}

/** True when anything at all is narrowing the search — used to decide whether a bare filter searches. */
export function hasActiveFilters(filters: SearchFilters) {
  return filters.types.length > 0 || activeFilterCount(filters) > 0;
}

/**
 * Filters carried in a URL. `type` keeps the singular name and the single-value form the dashboard
 * totals already link with (`/search?type=tools`), and simply accepts a comma list as well, so every
 * link that exists today still resolves.
 */
export function filtersFromSearchParams(
  params: URLSearchParams,
  offeredTypes: readonly SearchRecordType[] = RECORD_TYPES
): SearchFilters {
  // CASE IS FOLDED FOR THE COMPARISON AND THE VOCABULARY'S OWN SPELLING IS KEPT, which the plain
  // `.toLowerCase()` filter did not need while every bucket name was lower-case. `designWorkshops`
  // is the first that is not, and lowering it would drop the one link the search page itself writes.
  // Same rule, same reason, as `resolve_types` on the server.
  const requested = (params.get("type") ?? "")
    .split(",")
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean);
  const fromParam = params.get("from") ?? "";
  const toParam = params.get("to") ?? "";
  const from = parseDateInput(fromParam) ? fromParam : "";
  const to = parseDateInput(toParam) ? toParam : "";
  const rangeParam = (params.get("range") ?? "").trim() as RangeId;
  const range: RangeId = (RANGE_IDS as readonly string[]).includes(rangeParam)
    ? rangeParam
    : from || to
      ? "custom"
      : "any";
  return {
    // ``offeredTypes`` and not the widest list: reading a bucket a screen does not offer would put
    // the map into a state its own chips cannot express or undo, and would send the map endpoint a
    // bucket it answers 422 for.
    types: offeredTypes.filter((type) => requested.includes(type.toLowerCase())),
    place: params.get("place") ?? "",
    range,
    from,
    to,
    craftId: params.get("craftId") ?? "",
    artisanId: params.get("artisanId") ?? "",
    mediaType: params.get("mediaType") ?? ""
  };
}

/** The same filters as `/search` link params, so a hit found in View Data opens fully filtered. */
export function filtersToLinkParams(filters: SearchFilters) {
  return {
    type: typeList(filters),
    place: filters.place.trim() || undefined,
    range: filters.range !== "any" ? filters.range : undefined,
    from: filters.range === "custom" ? filters.from || undefined : undefined,
    to: filters.range === "custom" ? filters.to || undefined : undefined,
    craftId: filters.craftId || undefined,
    artisanId: filters.artisanId || undefined,
    mediaType: filters.mediaType || undefined
  };
}

const CHIP_BASE = "rounded-full border px-3 py-1 text-xs font-medium transition-colors";
const CHIP_ON = "border-purple-700 bg-purple-700 text-white";
const CHIP_PART = "border-purple-300 bg-purple-50 text-purple-700 hover:border-purple-400";
const CHIP_OFF = "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300 hover:text-purple-700";

/**
 * Chips, a disclosure, and the advanced panel behind it.
 *
 * THE CHIPS AND THE MULTI-SELECT ARE ONE PIECE OF STATE, not two. `filters.types` is the only store
 * of which types are being searched; the chip row and the checkbox list are two editors of that same
 * set, which is why they can never fall out of step:
 *
 *   - a chip is the shortcut for "only this" — clicking one REPLACES the set with that single type,
 *     and "Everything" empties it;
 *   - a checkbox adds or removes one member and leaves the rest alone.
 *
 * The chip row keeps saying what the set is even when the set is something chips alone cannot
 * express: with two or more types selected no chip is the solid "this is the filter" purple, the
 * members are drawn in the lighter included style instead, and a line of text says how many types
 * are in play. So the state is always legible from the chips, and clicking one is always the same
 * promise — "narrow to just this".
 */
export function SearchFilterBar({
  value,
  onChange,
  className = "mb-4",
  showPlace = true,
  offeredTypes = RECORD_TYPES,
  extraFilters
}: {
  value: SearchFilters;
  onChange: (next: SearchFilters) => void;
  className?: string;
  /** False where the screen already has its own place box, so the field is not asked for twice. */
  showPlace?: boolean;
  /**
   * Which buckets this screen offers. Defaults to the five every screen has; the search page passes
   * six, and passes five again for a reader without design-workshop access — the UI never offers
   * what the API refuses.
   */
  offeredTypes?: readonly SearchRecordType[];
  /**
   * Extra controls for the advanced panel, rendered under the shared ones.
   *
   * A SLOT AND NOT THREE MORE PROPS, which is `android/.../ui/SearchScreen.kt`'s shape exactly and
   * for its stated reason: the shared filters above are ONE implementation, and an addition
   * declared at the call site cannot quietly become a second copy that drifts. The craft and artisan
   * pickers need record LISTS, and a shared bar that fetched them would make the map and the View
   * Data panel pay two requests for controls they do not draw.
   */
  extraFilters?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const hidden = activeFilterCount(value);
  const multiple = value.types.length > 1;
  const chipIds: ChipId[] = ["all", ...offeredTypes];

  function chipClass(id: ChipId) {
    if (id === "all") return value.types.length === 0 ? CHIP_ON : CHIP_OFF;
    if (value.types.length === 1 && value.types[0] === id) return CHIP_ON;
    return value.types.includes(id as SearchRecordType) ? CHIP_PART : CHIP_OFF;
  }

  function pickChip(id: ChipId) {
    onChange({ ...value, types: id === "all" ? [] : [id as SearchRecordType] });
  }

  function toggleType(type: SearchRecordType) {
    const next = value.types.includes(type)
      ? value.types.filter((current) => current !== type)
      : [...value.types, type];
    // Held in bucket order so the state reads the same as the row of chips above it, whatever order
    // the ticks went in. `typeList` re-derives it anyway; this keeps what is stored honest too.
    onChange({ ...value, types: offeredTypes.filter((current) => next.includes(current)) });
  }

  return (
    <div className={className}>
      <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter results by record type">
        {chipIds.map((id) => (
          <button
            key={id}
            type="button"
            aria-pressed={id === "all" ? value.types.length === 0 : value.types.includes(id as SearchRecordType)}
            onClick={() => pickChip(id)}
            className={`${CHIP_BASE} ${chipClass(id)}`}
          >
            {TYPE_LABEL[id]}
          </button>
        ))}
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
          className={`inline-flex items-center gap-1.5 ${CHIP_BASE} ${open || hidden ? CHIP_PART : CHIP_OFF}`}
        >
          <SlidersHorizontal className="h-3.5 w-3.5" aria-hidden />
          {hidden ? `Filters · ${hidden}` : "Filters"}
        </button>
      </div>

      {multiple ? (
        <p className="mt-1.5 text-xs text-ink-500">
          Searching {value.types.length} record types. A chip narrows to just that one; tick more under Filters.
        </p>
      ) : null}

      {open ? (
        <div className="mt-2 grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3">
          <div className="grid gap-3 sm:grid-cols-2">
            {showPlace ? (
              <label className="grid gap-1">
                <span className="field-label">Place</span>
                <input
                  className="field-input"
                  value={value.place}
                  placeholder="Any place"
                  onChange={(event) => onChange({ ...value, place: event.target.value })}
                />
              </label>
            ) : null}
            <div className="grid gap-1">
              <span className="field-label">Record time</span>
              <Dropdown
                ariaLabel="Filter by when the record was made"
                value={value.range}
                onChange={(range) => onChange({ ...value, range: range as RangeId })}
                advanceOnSelect={false}
                options={RANGE_OPTIONS}
              />
            </div>
          </div>

          {value.range === "custom" ? (
            // Only reachable once "Custom range" has been chosen, so the presets — which are what
            // nearly every search actually wants — never have to walk past a calendar to be used.
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <label className="field-label" htmlFor="search-range-from">
                  From
                </label>
                <DateField
                  id="search-range-from"
                  value={value.from}
                  max={value.to || undefined}
                  onChange={(from) => onChange({ ...value, from })}
                />
              </div>
              <div className="grid gap-1">
                <label className="field-label" htmlFor="search-range-to">
                  To
                </label>
                <DateField
                  id="search-range-to"
                  value={value.to}
                  min={value.from || undefined}
                  onChange={(to) => onChange({ ...value, to })}
                />
              </div>
            </div>
          ) : null}

          <fieldset className="grid gap-1.5">
            <legend className="field-label">Record types</legend>
            <p className="text-xs text-ink-500">
              The same setting as the chips above. Tick any number; nothing ticked searches everything.
            </p>
            <div className="flex flex-wrap gap-x-4 gap-y-1.5">
              {offeredTypes.map((type) => (
                <label key={type} className="flex items-center gap-2 text-sm text-ink-700">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-purple-700"
                    checked={value.types.includes(type)}
                    onChange={() => toggleType(type)}
                  />
                  {TYPE_LABEL[type]}
                </label>
              ))}
            </div>
          </fieldset>

          {extraFilters ? <div className="grid gap-3">{extraFilters}</div> : null}

          {/* A FILTER WITH NO CONTROL ON THIS SCREEN STILL HAS TO BE NAMED. `filtersFromSearchParams`
              reads craft, artisan and media type out of the URL for every consumer, and only the
              screen that passes `extraFilters` draws pickers for them — so a /search link pasted into
              /map arrives narrowed by three filters that screen has no box for. The count badge
              would say "Filters · 3" and nothing would say by what. Clear all below removes them. */}
          {!extraFilters && (value.craftId || value.artisanId || value.mediaType) ? (
            <p className="text-xs text-ink-500">
              Also narrowed by{" "}
              {[value.craftId ? "craft" : null, value.artisanId ? "artisan" : null, value.mediaType ? "media type" : null]
                .filter(Boolean)
                .join(", ")}{" "}
              from the link that opened this page. Clear all filters removes them.
            </p>
          ) : null}

          {hasActiveFilters(value) ? (
            <div>
              <button
                type="button"
                className="text-xs font-semibold text-purple-700 hover:underline"
                onClick={() => onChange(EMPTY_SEARCH_FILTERS)}
              >
                Clear all filters
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
