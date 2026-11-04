"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, mergeById } from "@/components/data/cappedList";
import { Field } from "@/components/FormControls";
import { LateSubmissionDialog } from "@/components/LateSubmissionDialog";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { ComboBox, type DropdownServerQuery } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";
import {
  NO_FIELD_WORKSHOP,
  WORKSHOP_OPTION_PAGE_SIZE,
  deviceLooksOffline,
  fieldWorkshopOptions,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type WorkshopListState,
  type WorkshopListVoice,
  type WorkshopOptionSet
} from "@/lib/workshopOptions";

const DEFAULT_PROBE_LIMIT = 5;

const SEARCH_DEBOUNCE_MS = 300;

const ACCESSIBLE_WORKSHOPS_TTL_MS = 60_000;
let accessibleWorkshopsRequest: Promise<Workshop[]> | null = null;
let accessibleWorkshopsAskedAt = 0;

export function loadAccessibleWorkshops(): Promise<Workshop[]> {
  if (accessibleWorkshopsRequest && Date.now() - accessibleWorkshopsAskedAt > ACCESSIBLE_WORKSHOPS_TTL_MS) {
    accessibleWorkshopsRequest = null;
  }
  if (!accessibleWorkshopsRequest) {
    accessibleWorkshopsAskedAt = Date.now();
    accessibleWorkshopsRequest = listResource<Workshop>("/workshops", {
      pageSize: LIST_PAGE_CEILING,
      accessibleOnly: "true"
    })
      .then((result) => sortWorkshopsByOccurrence(result.items ?? []))
      .catch((error) => {
        accessibleWorkshopsRequest = null;
        throw error;
      });
  }
  return accessibleWorkshopsRequest;
}

export function workshopOccurrenceDate(workshop: Workshop): string {
  return workshop.startDate ?? workshop.date ?? workshop.createdAt ?? "";
}

export function sortWorkshopsByOccurrence(workshops: Workshop[]): Workshop[] {
  return [...workshops].sort((a, b) => workshopOccurrenceDate(b).localeCompare(workshopOccurrenceDate(a)));
}

function endedLocally(workshop: Workshop | undefined): boolean {
  if (!workshop) return false;
  const raw = workshop.endDate ?? workshop.date ?? workshop.startDate;
  if (!raw) return false;
  const end = new Date(raw);
  if (Number.isNaN(end.getTime())) return false;
  return Date.now() >= end.getTime() + 24 * 60 * 60 * 1000;
}

export type WorkshopSelection = {
  workshopId: string;
  touched: boolean;
  workshops: Workshop[];
  loading: boolean;
  list: WorkshopListState<Workshop>;
  options: WorkshopOptionSet;
  search: DropdownServerQuery;
  searchApplied: string;
  check: WorkshopSubmissionCheck | null;
  setWorkshopId: (workshopId: string) => void;
  confirmSubmission: () => Promise<boolean>;
  dialog: { open: boolean; check: WorkshopSubmissionCheck | null; confirm: () => void; cancel: () => void };
};

export function useWorkshopSelection({
  initialWorkshopId,
  isEdit = false,
  resetKey = null
}: {
  initialWorkshopId?: string | null;
  isEdit?: boolean;
  resetKey?: string | null;
} = {}): WorkshopSelection {
  const [known, setKnown] = useState<Workshop[]>([]);
  const [baseline, setBaseline] = useState<Workshop[]>([]);
  const [list, setList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
  const [term, setTerm] = useState("");
  const [searchApplied, setSearchApplied] = useState("");
  const [pending, setPending] = useState(true);
  const [workshopId, setWorkshopIdState] = useState(initialWorkshopId ?? "");
  const [touched, setTouched] = useState(Boolean(initialWorkshopId));
  const [check, setCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogCheck, setDialogCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const checkCache = useRef(new Map<string, WorkshopSubmissionCheck>());
  const touchedRef = useRef(Boolean(initialWorkshopId));
  const resolveRef = useRef<((confirmed: boolean) => void) | null>(null);

  useEffect(() => {
    let cancelled = false;
    const trimmed = term.trim();
    setPending(true);
    const timer = window.setTimeout(() => {
      listResource<Workshop>("/workshops", {
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        accessibleOnly: "true",
        search: trimmed || undefined
      })
        .then((result) => {
          if (cancelled) return;
          const rows = sortWorkshopsByOccurrence(result.items);
          setList({ kind: "ok", rows, total: result.total });
          setSearchApplied(trimmed);
          setKnown((previous) => sortWorkshopsByOccurrence(mergeById(previous, rows)));
          if (!trimmed) setBaseline(rows);
        })
        .catch(() => {
          if (!cancelled) setList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setPending(false);
        });
    }, trimmed ? SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [term]);

  useEffect(() => {
    touchedRef.current = Boolean(initialWorkshopId);
    setTouched(Boolean(initialWorkshopId));
    setWorkshopIdState(initialWorkshopId ?? "");
  }, [resetKey, initialWorkshopId]);

  const fetchCheck = useCallback(async (id: string): Promise<WorkshopSubmissionCheck | null> => {
    if (!id) return null;
    const cached = checkCache.current.get(id);
    if (cached) return cached;
    try {
      const result = await apiFetch<WorkshopSubmissionCheck>(`/workshops/${id}/submission-check`);
      checkCache.current.set(id, result);
      return result;
    } catch {
      return null;
    }
  }, []);

  useEffect(() => {
    if (isEdit || touchedRef.current || !baseline.length) return;
    let cancelled = false;
    (async () => {
      for (const workshop of baseline.slice(0, DEFAULT_PROBE_LIMIT)) {
        const result = await fetchCheck(workshop.id);
        if (cancelled || touchedRef.current) return;
        if (!result || result.canSubmit) {
          setWorkshopIdState(workshop.id);
          return;
        }
      }
      if (!cancelled && !touchedRef.current) setWorkshopIdState(baseline[0].id);
    })();
    return () => {
      cancelled = true;
    };
  }, [baseline, isEdit, fetchCheck]);

  useEffect(() => {
    let cancelled = false;
    setCheck(null);
    if (!workshopId) return;
    fetchCheck(workshopId).then((result) => {
      if (!cancelled) setCheck(result);
    });
    return () => {
      cancelled = true;
    };
  }, [workshopId, fetchCheck]);

  const setWorkshopId = useCallback((next: string) => {
    touchedRef.current = true;
    setTouched(true);
    setWorkshopIdState(next);
  }, []);

  const settleDialog = useCallback((confirmed: boolean) => {
    setDialogOpen(false);
    const resolve = resolveRef.current;
    resolveRef.current = null;
    resolve?.(confirmed);
  }, []);

  const confirmSubmission = useCallback(async (): Promise<boolean> => {
    if (!workshopId) return true;
    const result = await fetchCheck(workshopId);
    if (!result || (!result.outOfWindow && !result.isOver)) return true;
    setDialogCheck(result);
    setDialogOpen(true);
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve;
    });
  }, [workshopId, fetchCheck]);

  useEffect(() => {
    return () => resolveRef.current?.(false);
  }, []);

  const offPageWorkshop = useRecordOffPage<Workshop>("/workshops", workshopId, known);
  const allWorkshops = useMemo(
    () => (offPageWorkshop ? sortWorkshopsByOccurrence(mergeById(known, [offPageWorkshop])) : known),
    [known, offPageWorkshop]
  );

  const storedWorkshop = useMemo(
    () => allWorkshops.find((workshop) => workshop.id === workshopId) ?? null,
    [allWorkshops, workshopId]
  );

  const options = useMemo(
    () => fieldWorkshopOptions(list, { group: true, offPage: { mode: "recover", row: storedWorkshop } }),
    [list, storedWorkshop]
  );

  const search = useMemo<DropdownServerQuery>(
    () => ({ value: term, onChange: setTerm, pending }),
    [term, pending]
  );

  return {
    workshopId,
    touched,
    workshops: allWorkshops,
    loading: list.kind === "loading",
    list,
    options,
    search,
    searchApplied,
    check,
    setWorkshopId,
    confirmSubmission,
    dialog: {
      open: dialogOpen,
      check: dialogCheck,
      confirm: () => settleDialog(true),
      cancel: () => settleDialog(false)
    }
  };
}

export function WorkshopSelect({
  state,
  label = "Workshop",
  onDirty,
  saving
}: {
  state: WorkshopSelection;
  label?: string;
  onDirty?: () => void;
  saving?: boolean;
}) {
  const { workshopId, workshops, list, options, search, searchApplied, check, setWorkshopId, dialog } = state;
  const baseId = useId();
  const scopeNoteId = `${baseId}-scope`;
  const cutNoteId = `${baseId}-cut`;

  const voice: WorkshopListVoice = { table: "field", scoped: true, online: !deviceLooksOffline() };

  const listNotice = list.kind === "ok" && searchApplied ? "" : workshopListNotice(list, voice);

  const standingDown = !search.pending && !search.value.trim() && workshopListStandsDown(options);

  const selected = workshops.find((workshop) => workshop.id === workshopId);
  const blocked = Boolean(check && !check.canSubmit);
  const late = check ? check.outOfWindow || check.isOver : Boolean(workshopId) && endedLocally(selected);
  const endLabel = formatDate(check?.endDate ?? selected?.endDate ?? selected?.date ?? null);

  return (
    <div className="grid min-w-0 content-start gap-1" onInput={(event) => event.stopPropagation()}>
      <Field label={label}>
        <ComboBox
          options={options.options}
          value={workshopId}
          onChange={(next) => {
            setWorkshopId(next);
            onDirty?.();
          }}
          placeholder={list.kind === "loading" ? "Loading workshops…" : "Select or type to search"}
          emptyLabel={workshopEmptyLabel(list, voice)}
          noneLabel={NO_FIELD_WORKSHOP}
          serverQuery={search}
          disabled={standingDown}
          describedBy={`${scopeNoteId} ${cutNoteId}`}
        />
      </Field>
      <CappedListNotice
        cuts={[workshopCutSentence(options, { term: searchApplied, searchable: true })]}
        id={cutNoteId}
      />
      <p id={scopeNoteId} className="text-xs text-ink-500">
        {listNotice || "Only workshops you have access to are listed. Ask an admin if one you worked at is missing."}
      </p>
      {blocked ? (
        <p className="text-xs font-medium text-error-600">
          You are not assigned to this workshop, so you cannot save to it. Ask an admin to assign you to it, or pick
          another workshop.
        </p>
      ) : null}
      {late && !blocked ? (
        <p className="text-xs font-medium text-amber-800 dark:text-amber-100">
          {endLabel === "-" ? "This workshop has already ended." : `This workshop ended on ${endLabel}.`}{" "}
          {check?.needsAdminApproval || !check
            ? "Saving now counts as a late submission and needs an admin's approval."
            : "Saving now is recorded as a late submission."}
        </p>
      ) : null}
      <LateSubmissionDialog
        open={dialog.open}
        workshopTitle={dialog.check?.title ?? selected?.title}
        endDate={dialog.check?.endDate ?? selected?.endDate ?? selected?.date}
        needsAdminApproval={Boolean(dialog.check?.needsAdminApproval)}
        saving={saving}
        onConfirm={dialog.confirm}
        onCancel={dialog.cancel}
      />
    </div>
  );
}
