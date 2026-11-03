"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useRecordOffPageState } from "@/components/forms/recordPickers";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import {
  designWorkshopDefaultNote,
  readDesignWorkshopDefault
} from "@/lib/designWorkshopDefault";
import {
  designWorkshopOptions,
  deviceLooksOffline,
  NO_DESIGN_WORKSHOP,
  WORKSHOP_OPTION_PAGE_SIZE,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

const SEARCH_DEBOUNCE_MS = 300;

const NO_ROWS: readonly DwSummary[] = [];

export type DesignWorkshopSelectState = {
  workshopId: string;
  setWorkshopId: (id: string) => void;
  prefillWorkshopId: (id: string) => void;
  touched: boolean;
};

export function useDesignWorkshopSelection(
  initial?: string | null,
  resetKey?: string | null
): DesignWorkshopSelectState {
  const [workshopId, setId] = useState<string>(initial ?? "");
  const [touched, setTouched] = useState(false);
  const setWorkshopId = useCallback((id: string) => {
    setId(id);
    setTouched(true);
  }, []);
  const prefillWorkshopId = useCallback((id: string) => setId(id), []);
  useEffect(() => {
    setId(initial ?? "");
    setTouched(false);
  }, [resetKey, initial]);
  return { workshopId, setWorkshopId, prefillWorkshopId, touched };
}

export function DesignWorkshopSelect({
  state,
  initial,
  saving,
  onDirty,
  label = "Design & prototype workshop",
  noneLabel = NO_DESIGN_WORKSHOP
}: {
  state: DesignWorkshopSelectState;
  initial?: string | null;
  saving?: boolean;
  onDirty?: () => void;
  label?: string;
  noneLabel?: string;
}) {
  const [list, setList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  const [online, setOnline] = useState(true);
  const [term, setTerm] = useState("");
  const [pending, setPending] = useState(true);
  const [note, setNote] = useState<string | null>(null);
  const prefilled = useRef(false);
  const generation = useRef(0);

  const { setWorkshopId, prefillWorkshopId } = state;
  const isCreate = initial === undefined;

  useEffect(() => {
    const trimmed = term.trim();
    const mine = ++generation.current;
    setPending(true);
    const timer = window.setTimeout(
      () => {
        listDesignWorkshops({
          page: 1,
          pageSize: WORKSHOP_OPTION_PAGE_SIZE,
          search: trimmed || undefined
        })
          .then((page) => {
            if (generation.current !== mine) return;
            setList({ kind: "ok", rows: page.items, total: page.total });
            setPending(false);
          })
          .catch(() => {
            if (generation.current !== mine) return;
            setOnline(!deviceLooksOffline());
            setList({ kind: "failed" });
            setPending(false);
          });
      },
      trimmed ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [term]);

  useEffect(() => {
    if (!isCreate) return;
    let cancelled = false;
    void readDesignWorkshopDefault().then((fallback) => {
      if (cancelled || prefilled.current || fallback?.workshopId == null) return;
      prefilled.current = true;
      prefillWorkshopId(fallback.workshopId);
      setNote(designWorkshopDefaultNote(fallback));
    });
    return () => {
      cancelled = true;
      prefilled.current = false;
    };
  }, [isCreate, prefillWorkshopId]);

  const rows = list.kind === "ok" ? list.rows : NO_ROWS;
  const { record: stored, failed: storedUnreadable } = useRecordOffPageState<DwSummary>(
    "/design-workshops",
    state.workshopId,
    rows
  );

  const set = useMemo(
    () => designWorkshopOptions(list, { group: true, offPage: { mode: "recover", row: stored } }),
    [list, stored]
  );

  const voice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online }),
    [online]
  );

  const prefillVisible = set.options.some((option) => option.value === state.workshopId);
  useEffect(() => {
    if (note && storedUnreadable && !state.touched) {
      prefillWorkshopId("");
      setNote(null);
    }
  }, [note, storedUnreadable, state.touched, prefillWorkshopId]);

  const searching = term.trim() !== "";
  const notice = list.kind === "ok" && searching ? "" : workshopListNotice(list, voice);
  const cut = workshopCutSentence(set, { term, searchable: true });
  const standDown = list.kind !== "loading" && !searching && workshopListStandsDown(set);

  return (
    <FieldBlock
      label={label}
      hint={
        <>
          {cut ? <p className="text-[11px] leading-4 text-ink-500">{cut}</p> : null}
          {notice ? (
            <p className="text-[11px] leading-4 text-ink-500" aria-live="polite">
              {notice}
            </p>
          ) : null}
          {note && prefillVisible ? <p className="text-[11px] leading-4 text-ink-500">{note}</p> : null}
          <p className="text-[11px] leading-4 text-ink-500">
            Files this record under a design workshop. Doesn&apos;t change who can read it.
          </p>
        </>
      }
    >
      <Dropdown
        value={state.workshopId}
        onChange={(id) => {
          setWorkshopId(id);
          onDirty?.();
          setNote(null);
        }}
        options={set.options}
        noneLabel={noneLabel}
        emptyLabel={workshopEmptyLabel(list, voice)}
        serverQuery={{ value: term, onChange: setTerm, pending }}
        disabled={saving || standDown}
      />
    </FieldBlock>
  );
}
