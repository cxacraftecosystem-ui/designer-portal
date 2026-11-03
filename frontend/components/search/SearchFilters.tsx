"use client";

import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { useState, type ReactNode } from "react";
import { SlidersHorizontal } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";

export const RECORD_TYPES = ["artisans", "workshops", "products", "tools", "media"] as const;
export type RecordType = (typeof RECORD_TYPES)[number];

export const DESIGN_WORKSHOP_TYPE = "designWorkshops" as const;

export const SEARCH_RECORD_TYPES = [...RECORD_TYPES, DESIGN_WORKSHOP_TYPE] as const;
export type SearchRecordType = (typeof SEARCH_RECORD_TYPES)[number];

export const CHIP_IDS = ["all", ...SEARCH_RECORD_TYPES] as const;
export type ChipId = (typeof CHIP_IDS)[number];

export const TYPE_LABEL: Record<ChipId, string> = {
  all: "Everything",
  artisans: "Artisans",
  workshops: "Workshops",
  products: "Products",
  tools: "Tools",
  media: "Media",
  designWorkshops: "Design workshops"
};

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
  types: SearchRecordType[];
  place: string;
  range: RangeId;
  from: string;
  to: string;
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

function parseDateInput(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? null : date;
}

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

export function searchFilterParams(filters: SearchFilters) {
  return {
    place: filters.place.trim() || undefined,
    types: typeList(filters),
    craftId: filters.craftId || undefined,
    artisanId: filters.artisanId || undefined,
    mediaType: filters.mediaType || undefined,
    ...resolveRange(filters)
  };
}

function typeList(filters: SearchFilters): string | undefined {
  const ordered = SEARCH_RECORD_TYPES.filter((type) => filters.types.includes(type));
  return ordered.length ? ordered.join(",") : undefined;
}

export function typeVisible(filters: SearchFilters, type: SearchRecordType) {
  return filters.types.length === 0 || filters.types.includes(type);
}

export function activeFilterCount(filters: SearchFilters) {
  return (
    (filters.place.trim() ? 1 : 0) +
    (filters.range !== "any" ? 1 : 0) +
    (filters.craftId ? 1 : 0) +
    (filters.artisanId ? 1 : 0) +
    (filters.mediaType ? 1 : 0)
  );
}

export function hasActiveFilters(filters: SearchFilters) {
  return filters.types.length > 0 || activeFilterCount(filters) > 0;
}

export function filtersFromSearchParams(
  params: URLSearchParams,
  offeredTypes: readonly SearchRecordType[] = RECORD_TYPES
): SearchFilters {
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
  showPlace?: boolean;
  offeredTypes?: readonly SearchRecordType[];
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
            <div className="grid gap-3 sm:grid-cols-2">
              <CalendarField
                id="search-range-from"
                label="From"
                value={value.from}
                max={value.to || undefined}
                maxLabel="To"
                onChange={(from) => onChange({ ...value, from })}
              />
              <CalendarField
                id="search-range-to"
                label="To"
                value={value.to}
                min={value.from || undefined}
                minLabel="From"
                onChange={(to) => onChange({ ...value, to })}
              />
            </div>
          ) : null}

          <div className="grid gap-1.5">
            <span className="field-label" id="search-record-types-label">
              Record types
            </span>
            <p className="text-xs text-ink-500" id="search-record-types-hint">
              The same setting as the chips above. Pick any number; none picked searches everything.
            </p>
            <SearchableMultiSelect
              ariaLabel="Record types"
              describedBy="search-record-types-hint"
              options={offeredTypes.map((type) => ({ value: type, label: TYPE_LABEL[type] }))}
              values={value.types}
              onChange={(types) =>
                onChange({ ...value, types: offeredTypes.filter((current) => types.includes(current)) })
              }
              placeholder="Every record type"
              bulk={false}
              confirmOnSelect={false}
            />
          </div>

          {extraFilters ? <div className="grid gap-3">{extraFilters}</div> : null}

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
                className="text-xs font-semibold text-purple-700 dark:text-purple-300 hover:underline"
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
