"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { CalendarRange, Layers } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { listCut, type ListCut } from "@/components/data/cappedList";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { listAllPages } from "@/lib/listAllPages";
import type { Workshop } from "@/lib/types";
import {
  WORKSHOP_OPTION_PAGE_SIZE,
  deviceLooksOffline,
  fieldWorkshopOptions,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import { sortWorkshopsByOccurrence } from "@/components/forms/WorkshopSelect";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

export const UNASSIGNED_WORKSHOP = "none";

const UNASSIGNED_GROUP = "Records with no workshop";

const SCOPE_SHOWS_EVERYTHING =
  "Nothing is hidden by it — with no workshop chosen, this screen is showing every record.";

export type WorkshopScope = {
  workshopIds: string[];
  setWorkshopIds: (next: string[]) => void;
  workshops: Workshop[];
  list: WorkshopListState<Workshop>;
  cut: ListCut | null;
  loading: boolean;
  settling: boolean;
  queryValue: string | undefined;
  summary: string;
};

export function useWorkshopScope({
  defaultToMostRecent = true,
  initialWorkshopIds
}: {
  defaultToMostRecent?: boolean;
  initialWorkshopIds?: string[];
} = {}): WorkshopScope {
  const [list, setList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
  const [cut, setCut] = useState<ListCut | null>(null);
  const [loading, setLoading] = useState(true);
  const [workshopIds, setWorkshopIdsState] = useState<string[]>(initialWorkshopIds ?? []);
  const touched = useRef(Boolean(initialWorkshopIds?.length));
  const [settling, setSettling] = useState(!initialWorkshopIds?.length && defaultToMostRecent);

  useEffect(() => {
    let cancelled = false;
    listAllPages<Workshop>("/workshops", { pageSize: WORKSHOP_OPTION_PAGE_SIZE })
      .then((result) => {
        if (cancelled) return;
        const ordered = sortWorkshopsByOccurrence(result.items);
        setList({ kind: "ok", rows: ordered, total: result.total });
        setCut(listCut(result, "workshops"));
        if (defaultToMostRecent && !touched.current && ordered.length > 0) {
          setWorkshopIdsState([ordered[0].id]);
        }
      })
      .catch(() => {
        if (!cancelled) setList({ kind: "failed" });
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
        setSettling(false);
      });
    return () => {
      cancelled = true;
    };
  }, [defaultToMostRecent]);

  const setWorkshopIds = useCallback((next: string[]) => {
    touched.current = true;
    setWorkshopIdsState(next);
  }, []);

  const workshops = useMemo(() => (list.kind === "ok" ? [...list.rows] : []), [list]);

  const queryValue = useMemo(
    () => (workshopIds.length ? workshopIds.join(",") : undefined),
    [workshopIds]
  );

  const summary = useMemo(() => {
    if (!workshopIds.length) return "Every workshop, and records not linked to one.";
    const named = workshopIds
      .filter((id) => id !== UNASSIGNED_WORKSHOP)
      .map((id) => workshopDisplayTitle(workshops.find((workshop) => workshop.id === id)));
    const includesUnassigned = workshopIds.includes(UNASSIGNED_WORKSHOP);
    if (!named.length) return "Only records not linked to a workshop.";
    const list = named.length <= 3 ? named.join(", ") : `${named.slice(0, 3).join(", ")} and ${named.length - 3} more`;
    return `${list}${includesUnassigned ? ", plus records not linked to a workshop" : ""}.`;
  }, [workshopIds, workshops]);

  return { workshopIds, setWorkshopIds, workshops, list, cut, loading, settling, queryValue, summary };
}

export function WorkshopScopeSelect({
  scope,
  label = "Workshops",
  className = "",
  showSummary = true
}: {
  scope: WorkshopScope;
  label?: string;
  className?: string;
  showSummary?: boolean;
}) {
  const { workshopIds, setWorkshopIds, workshops, list, cut, loading } = scope;
  const baseId = useId();
  const cutNoteId = `${baseId}-cut`;
  const stateNoteId = `${baseId}-state`;

  const options = useMemo(
    () => [
      ...fieldWorkshopOptions(list, { group: true, offPage: { mode: "refuse" } }).options,
      { value: UNASSIGNED_WORKSHOP, label: "Not linked to a workshop", group: UNASSIGNED_GROUP }
    ],
    [list]
  );

  const online = !deviceLooksOffline();
  const voice: WorkshopListVoice = {
    table: "field",
    scoped: false,
    online,
    reassurance: SCOPE_SHOWS_EVERYTHING
  };
  const shared = workshopListNotice(list, voice);
  const notice = list.kind === "failed" && !online ? `${shared} ${SCOPE_SHOWS_EVERYTHING}` : shared;

  const all = workshopIds.length === 0;

  return (
    <div className={`grid min-w-0 content-start gap-1.5 ${className}`}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="field-label inline-flex items-center gap-1.5">
          <CalendarRange className="h-3.5 w-3.5" aria-hidden />
          {label}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            aria-pressed={all}
            onClick={() => setWorkshopIds([])}
            className={`rounded-full border px-2.5 py-0.5 text-[11px] font-medium transition-colors ${
              all
                ? "border-purple-700 bg-purple-700 text-white"
                : "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300 hover:text-purple-700"
            }`}
          >
            All records
          </button>
          {!all && workshops.length > 0 ? (
            <button
              type="button"
              onClick={() => setWorkshopIds([workshops[0].id])}
              className="text-[11px] font-semibold text-purple-700 dark:text-purple-300 hover:underline"
            >
              Most recent only
            </button>
          ) : null}
        </div>
      </div>

      <MultiSelectDropdown
        values={workshopIds}
        onChange={setWorkshopIds}
        options={options}
        searchable
        bulk={false}
        ariaLabel="Choose which workshops to include"
        placeholder={loading ? "Loading workshops…" : all ? "All workshops" : "Select workshops"}
        emptyLabel={workshopEmptyLabel(list, voice)}
        describedBy={`${cutNoteId} ${stateNoteId}`}
      />

      <CappedListNotice cuts={[cut]} id={cutNoteId} />
      {notice ? (
        <p id={stateNoteId} className="text-xs leading-5 text-ink-500">
          {notice}
        </p>
      ) : null}

      {showSummary ? (
        <p className="flex items-start gap-1.5 text-[11px] leading-4 text-ink-500">
          <Layers className="mt-px h-3 w-3 shrink-0" aria-hidden />
          <span>{scope.summary}</span>
        </p>
      ) : null}
    </div>
  );
}

export function workshopScopeLinkParams(workshopIds: string[]) {
  return { workshopIds: workshopIds.length ? workshopIds.join(",") : undefined };
}

export function workshopScopeFromSearchParams(params: URLSearchParams): string[] {
  return (params.get("workshopIds") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}
