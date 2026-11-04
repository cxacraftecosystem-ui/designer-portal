"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { deferredTool, registerWarm } from "@/components/designworkshop/deferredTool";
import { Check, ChevronDown, Loader2, Pencil, Plus, ScanLine, Search, X } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import {
  INLINE_MODEL_NOUN,
  InlineRecordDialog,
  isInlineCreatable,
  type InlineCreatableModel
} from "@/components/designworkshop/InlineRecordDialog";
import { useLinkedWorkshopId } from "@/components/designworkshop/LinkedWorkshop";
import type { ScanResolution } from "@/components/designworkshop/WorkshopCodeScanner";
import { resolveCardForScan, type CardScanOutcome } from "@/lib/artisanCardScan";
import type { InlineHostSeed } from "@/components/forms/inlineRecordHost";
import {
  geoValue,
  hydrateFromReference,
  inputValue,
  isMultiField,
  listStageReferences,
  referenceDisplayHint,
  referenceHydrationFor,
  stringifyRefValue,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwReferenceOption,
  type DwReferencePayload,
  type DwValue
} from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { canManageCrafts } from "@/lib/permissions";
import { loadStageReferences } from "@/lib/referenceCache";
import { workshopRecordTypeLabel, type WorkshopCodeRef, type WorkshopRecordType } from "@/lib/workshopCodes";
import { cachedListLine } from "@/lib/workshopOptions";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { BULK_ALL_NOTHING_LEFT, cutShortNote, remainingOptions } from "@/components/designworkshop/bulkPick";

const WorkshopCodeScanner = deferredTool(
  () => import("@/components/designworkshop/WorkshopCodeScanner").then((m) => m.WorkshopCodeScanner),
  { placeholder: () => <p className="min-h-10 text-xs leading-5 text-ink-500">Getting the scanner ready…</p> }
);
registerWarm(WorkshopCodeScanner.preload);

const SEARCH_DEBOUNCE_MS = 300;

const REFERENCE_PAGE = 50;

const REFERENCE_PAGE_MAX = 200;

type PickerState = {
  payload: DwReferencePayload | null;
  loading: boolean;
  problem: string | null;
  cachedAt: string | null;
};

function useReferenceOptions({
  workshopId,
  field,
  filterValue,
  query,
  active
}: {
  workshopId: string;
  field: DwField;
  filterValue: string;
  query: string;
  active: boolean;
}): PickerState {
  const [state, setState] = useState<PickerState>({ payload: null, loading: false, problem: null, cachedAt: null });
  const generation = useRef(0);

  useEffect(() => {
    if (!active || !field.refModel) return;
    const current = generation.current + 1;
    generation.current = current;
    setState((previous) => ({ ...previous, loading: true, problem: null }));
    const model = field.refModel as string;
    const filterBy = field.refFilterBy ? filterValue || null : null;
    const search = query.trim() || null;
    const request = () => listStageReferences(workshopId, { model, scope: field.refScope, filterBy, search });
    const timer = window.setTimeout(() => {
      if (search || filterBy) {
        request()
          .then((payload) => {
            if (generation.current !== current) return;
            setState({ payload, loading: false, problem: null, cachedAt: null });
          })
          .catch((error) => {
            if (generation.current !== current) return;
            setState({
              payload: null,
              loading: false,
              problem: error instanceof Error ? error.message : "The list could not be loaded.",
              cachedAt: null
            });
          });
        return;
      }
      void loadStageReferences<DwReferencePayload>({
        workshopId,
        model,
        scope: field.refScope,
        isEmpty: (payload) => payload.options.length === 0,
        fetch: request,
        onPayload: (payload, cachedAt) => {
          if (generation.current !== current) return;
          setState({ payload, loading: cachedAt !== null, problem: null, cachedAt });
        }
      }).then((outcome) => {
        if (generation.current !== current) return;
        if (outcome.source === "none") {
          setState({
            payload: null,
            loading: false,
            problem: outcome.error instanceof Error ? outcome.error.message : "The list could not be loaded.",
            cachedAt: null
          });
          return;
        }
        setState((previous) => ({ ...previous, loading: false }));
      });
    }, query ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [workshopId, field.refModel, field.refScope, field.refFilterBy, filterValue, query, active]);

  return state;
}

export function scopeNoticeLines(
  field: DwField,
  payload: DwReferencePayload | null,
  cachedAt?: string | null
): string[] {
  if (!payload) return [];
  const lines: string[] = [];
  if (cachedAt) {
    lines.push(cachedListLine(payload.options.length, field.label.toLowerCase(), formatDate(cachedAt)));
  }
  if (field.refScope === "WORKSHOP" && !payload.scopedToWorkshop) {
    lines.push(
      "Not linked to a workshop yet — showing all records."
    );
  }
  if (field.refScope === "WORKSHOP" && payload.scopedToWorkshop && !payload.filtered && !payload.options.length) {
    lines.push(
      "No records in the linked workshop yet. Create one here."
    );
  }
  const tentativeWord = payload.tentativeFirst ? (payload.tentativeLabel || "").trim() : "";
  const showsTentative = Boolean(tentativeWord) && payload.options.some((option) => option.tentative === true);
  if (showsTentative) {
    lines.push(`“${tentativeWord}” rows come first; the rest keep their stage order.`);
  }
  if (payload.truncated) {
    lines.push(
      showsTentative
        ? `First ${REFERENCE_PAGE} matches, tentative first. Type more to narrow.`
        : `First ${REFERENCE_PAGE} matches. Type more to narrow.`
    );
  }
  return lines;
}

function TentativeChip({ option, word }: { option: DwReferenceOption; word: string }) {
  if (!word || option.tentative !== true) return null;
  return (
    <span className="shrink-0 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
      {word}
    </span>
  );
}

function tentativeWordFor(payload: DwReferencePayload | null): string {
  if (!payload?.tentativeFirst) return "";
  return (payload.tentativeLabel || "").trim();
}

function ScopeNotice({
  field,
  payload,
  cachedAt
}: {
  field: DwField;
  payload: DwReferencePayload | null;
  cachedAt?: string | null;
}) {
  const lines = scopeNoticeLines(field, payload, cachedAt);
  if (!lines.length) return null;
  return <p className="px-3 pb-2 text-xs leading-5 text-ink-500">{lines.join(" ")}</p>;
}

type CreatedRecord = {
  id: string;
  name?: string | null;
  productName?: string | null;
  toolkitName?: string | null;
};

function createdLabel(record: CreatedRecord): string {
  return (record.name || record.productName || record.toolkitName || "").trim();
}

async function describeCreated(
  workshopId: string,
  field: DwField,
  filterValue: string,
  record: CreatedRecord
): Promise<DwReferenceOption | null> {
  return describeRecord(workshopId, field, filterValue, record.id, createdLabel(record));
}

async function describeRecord(
  workshopId: string,
  field: DwField,
  filterValue: string,
  recordId: string,
  label: string
): Promise<DwReferenceOption | null> {
  try {
    const payload = await listStageReferences(workshopId, {
      model: field.refModel as string,
      scope: field.refScope,
      filterBy: field.refFilterBy ? filterValue || null : null,
      search: label.trim() || null,
      limit: REFERENCE_PAGE_MAX
    });
    return payload.options.find((option) => option.id === recordId) ?? null;
  } catch {
    return null;
  }
}

export function inlineSeed({
  entity,
  field,
  row,
  filterValue,
  linkedWorkshopId
}: {
  entity?: DwEntity;
  field: DwField;
  row?: DwEntryData;
  filterValue?: string;
  linkedWorkshopId: string | null;
}): InlineHostSeed {
  const seed: InlineHostSeed = {};
  const filterField =
    entity && field.refFilterBy ? entity.fields.find((candidate) => candidate.key === field.refFilterBy) : undefined;
  if (entity && row && filterField && filterValue && filterField.refModel === "Artisan") {
    seed.artisanId = filterValue;
    const name = referenceDisplayHint(entity, filterField, row);
    if (name) seed.artisanName = name;
  }
  if (linkedWorkshopId) seed.workshopId = linkedWorkshopId;
  return seed;
}

const QUEUED_OFFLINE_NOTICE =
  "Saved on this device. Once it uploads, choose it here to link it.";

const CRAFT_REGISTER_LINK = "Add it to the list (new tab)";

const CRAFT_REGISTER_BLOCKED = "Not on the list? Ask an admin to add it.";

export const SCANNED_TYPE_REF_MODEL: Partial<Record<WorkshopRecordType, string>> = {
  artisan: "Artisan",
  craft: "Craft",
  product: "ProductDocumentation",
  process: "Process",
  tool: "ToolDocumentation",
  prototype: "DwPrototype"
};

const REF_MODEL_TYPE: Record<string, WorkshopRecordType> = Object.fromEntries(
  Object.entries(SCANNED_TYPE_REF_MODEL).map(([type, model]) => [model, type as WorkshopRecordType])
);

function article(noun: string): string {
  return /^[aeiou]/i.test(noun) ? "an" : "a";
}

export function pickerTakesScans(field: DwField): boolean {
  return Boolean(field.refModel && REF_MODEL_TYPE[field.refModel]);
}

async function refuseCardHere(): Promise<CardScanOutcome> {
  return { ok: false, message: "That's an artisan card. Scan this record's own tag." };
}

function pickerNoun(field: DwField): string {
  const known = field.refModel ? REF_MODEL_TYPE[field.refModel] : undefined;
  return known ? workshopRecordTypeLabel(known).toLowerCase() : field.label.toLowerCase();
}

export function scanTypeRefusal(field: DwField, ref: WorkshopCodeRef): string | null {
  const wanted = field.refModel ? REF_MODEL_TYPE[field.refModel] : undefined;
  if (wanted === ref.recordType) return null;
  const scanned = workshopRecordTypeLabel(ref.recordType).toLowerCase();
  if (!wanted) {
    return (
      `That code is for ${article(scanned)} ${scanned}. “${field.label}” entries have no printed code — choose one from the list.`
    );
  }
  const noun = pickerNoun(field);
  return (
    `That code is for ${article(scanned)} ${scanned}; “${field.label}” takes ${article(noun)} ${noun}.`
  );
}

export type ScanLookup = { ok: true; option: DwReferenceOption } | { ok: false; message: string };

function outOfScopeRefusal(option: DwReferenceOption): string {
  return (
    `“${option.label}” belongs to a different workshop. Nothing on this row has been changed. Link that record to this workshop, or choose another.`
  );
}

function unresolvedRefusal(field: DwField, cascadeLabel: string): string {
  const noun = pickerNoun(field);
  const reasons = [
    "it may not be in the records",
    "it may belong to work you don't have access to",
    ...(cascadeLabel ? [`it may not belong to the ${cascadeLabel.toLowerCase()} chosen on this row`] : [])
  ];
  return (
    `No ${noun} here matches that code — ${reasons.join(", or ")}. Search by name instead.`
  );
}

export function scanLookupOutcome({
  field,
  ref,
  payload,
  cascadeLabel
}: {
  field: DwField;
  ref: WorkshopCodeRef;
  payload: DwReferencePayload;
  cascadeLabel: string;
}): ScanLookup {
  const exact = payload.options.find((option) => option.id === ref.id);
  if (exact) return { ok: true, option: exact };
  if (ref.recordType === "prototype" && !payload.truncated && payload.options.length === 1) {
    return { ok: true, option: payload.options[0] };
  }
  if (payload.outOfScope === true && payload.outOfScopeOption) {
    return { ok: false, message: outOfScopeRefusal(payload.outOfScopeOption) };
  }
  return { ok: false, message: unresolvedRefusal(field, cascadeLabel) };
}

const SCAN_OFFLINE_NOTICE =
  "No connection. Scan again later, or search by name.";

const SCAN_LOOKUP_FAILED_NOTICE =
  "Couldn't look up that code. Try again, or search by name.";

const SCANNER_OPENED = "dw-stage-scanner-opened";

type PickerNotice = { tone: "warn" | "done"; text: string };

export function refusedByUnsavedWork(retry: string): PickerNotice {
  return {
    tone: "warn",
    text: `Unsaved work on this stage. Answer the prompt, then ${retry}.`
  };
}

export type ScanHeld = { id: string; filter: string; option: DwReferenceOption };

export function scanCommitDecision({
  held,
  ref,
  resolution,
  filterValue,
  field
}: {
  held: ScanHeld | null;
  ref: WorkshopCodeRef;
  resolution: ScanResolution;
  filterValue: string;
  field: DwField;
}): { commit: DwReferenceOption | null; notice: string | null } {
  if (!resolution.ok || !held || held.id !== ref.id) return { commit: null, notice: null };
  if (held.filter !== filterValue) {
    return {
      commit: null,
      notice:
        "The list changed while that code was being looked up. Nothing on this row has been changed — scan again."
    };
  }
  return {
    commit: held.option,
    notice: `“${held.option.label}” is now chosen for “${field.label}”.`
  };
}

export function StageReferenceSelect({
  workshopId,
  entity,
  field,
  row,
  value,
  onChange,
  onPatch,
  disabled,
  labelId,
  recordFormMountedOver = null
}: {
  workshopId: string;
  entity: DwEntity;
  field: DwField;
  row: DwEntryData;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  disabled?: boolean;
  labelId: string;
  recordFormMountedOver?: string | null;
}) {
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const { user } = useAuth();
  const craftManager = canManageCrafts(user);
  const [notice, setNotice] = useState<PickerNotice | null>(null);
  const [pending, setPending] = useState<{ id: string; label: string } | null>(null);
  const [inlineDialog, setInlineDialog] = useState<{ mode: "create" } | { mode: "edit"; id: string } | null>(null);
  const [scanOpen, setScanOpen] = useState(false);
  const scanHit = useRef<ScanHeld | null>(null);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const selectedId = inputValue(value);
  const filterValue = field.refFilterBy ? inputValue(row[field.refFilterBy]) : "";
  const awaitingCascade = Boolean(field.refFilterBy) && !filterValue;
  const linkedWorkshopId = useLinkedWorkshopId();
  const seed = useMemo(
    () => inlineSeed({ entity, field, row, filterValue, linkedWorkshopId }),
    [entity, field, row, filterValue, linkedWorkshopId]
  );
  const { payload, loading, problem, cachedAt } = useReferenceOptions({
    workshopId,
    field,
    filterValue,
    query,
    active: open && !awaitingCascade
  });
  const options = payload?.options ?? [];
  const tentativeWord = tentativeWordFor(payload);

  const chosenLabel =
    options.find((option) => option.id === selectedId)?.label ||
    (pending?.id === selectedId ? pending.label : "") ||
    referenceDisplayHint(entity, field, row);

  const latestRow = useRef(row);
  useEffect(() => {
    latestRow.current = row;
  }, [row]);

  const hydration = useRef(0);
  const supersede = useCallback(() => {
    hydration.current += 1;
    setPending(null);
  }, []);

  const lastFilter = useRef(filterValue);
  useEffect(() => {
    if (!field.refFilterBy) return;
    if (lastFilter.current === filterValue) return;
    const had = lastFilter.current;
    lastFilter.current = filterValue;
    if (!selectedId) return;
    onChange(null);
    setNotice({
      tone: "warn",
      text: had
        ? "The record this list depends on changed, so the previous choice was cleared — pick one from the new list."
        : "Choose the record above first; this list narrows to it."
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterValue, field.refFilterBy]);

  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  const scannerId = `${baseId}-scanner`;
  useEffect(() => {
    if (!scanOpen) return;
    if (awaitingCascade) {
      setScanOpen(false);
      return;
    }
    window.dispatchEvent(new CustomEvent(SCANNER_OPENED, { detail: scannerId }));
    const onAnother = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== scannerId) setScanOpen(false);
    };
    window.addEventListener(SCANNER_OPENED, onAnother);
    return () => window.removeEventListener(SCANNER_OPENED, onAnother);
  }, [awaitingCascade, scanOpen, scannerId]);

  const safeHighlight = highlight >= 0 && highlight < options.length ? highlight : 0;

  const commitChoice = useCallback(
    (option: DwReferenceOption) => {
      supersede();
      const patch = hydrateFromReference(entity, field, option, row, selectedId);
      onPatch({ ...patch, [field.key]: option.id });
      setOpen(false);
      setQuery("");
      setNotice(null);
    },
    [entity, field, row, selectedId, onPatch, supersede]
  );

  const interceptLeave = useLeaveInterceptor();
  const choose = useCallback(
    (option: DwReferenceOption) => {
      if (option.id !== selectedId && interceptLeave(() => commitChoice(option))) {
        setNotice(refusedByUnsavedWork("choose the record again"));
        return false;
      }
      commitChoice(option);
      return true;
    },
    [commitChoice, interceptLeave, selectedId]
  );

  const liveScan = useRef({ choose, field, filterValue });
  useEffect(() => {
    liveScan.current = { choose, field, filterValue };
  }, [choose, field, filterValue]);

  const chooseCraft = useCallback(
    async (id: string, name: string) => {
      if (!id) {
        onChange(null);
        return;
      }
      const listed = (payload?.options ?? []).find((option) => option.id === id);
      if (listed) {
        choose(listed);
        return;
      }
      const described = await describeRecord(workshopId, field, filterValue, id, name);
      if (described) {
        choose(described);
        return;
      }
      onChange(id);
    },
    [choose, field, filterValue, onChange, payload, workshopId]
  );

  const beforeEdit = useRef<DwReferenceOption | null>(null);

  const adoptEdited = useCallback(
    async (recordId: string, label: string) => {
      const before = beforeEdit.current;
      const after = await describeRecord(workshopId, field, filterValue, recordId, label);
      beforeEdit.current = null;
      if (!after) {
        setNotice({
          tone: "warn",
          text: "Saved, but this row didn't refresh. Pick the record again to update it."
        });
        return;
      }
      const mapping = referenceHydrationFor(entity, field);
      const patch: Record<string, DwValue> = {};
      for (const [sourceKey, targetKey] of Object.entries(mapping)) {
        const target = entity.fields.find((candidate) => candidate.key === targetKey);
        if (!target || target.deprecated) continue;
        if (isMultiField(target)) continue;
        if (target.type === "GEO") {
          const nextPoint = geoValue(after.data?.[sourceKey] as DwValue);
          if (!nextPoint) continue;
          const heldPoint = geoValue(row[targetKey]);
          const wasPoint = before ? geoValue(before.data?.[sourceKey] as DwValue) : null;
          const sameAsHydrated =
            heldPoint !== null &&
            wasPoint !== null &&
            heldPoint.lat === wasPoint.lat &&
            heldPoint.lon === wasPoint.lon;
          if (heldPoint && !sameAsHydrated) continue;
          if (heldPoint && heldPoint.lat === nextPoint.lat && heldPoint.lon === nextPoint.lon) continue;
          patch[targetKey] = nextPoint;
          continue;
        }
        const current = inputValue(row[targetKey]).trim();
        const was = before ? stringifyRefValue(before.data?.[sourceKey]) : null;
        const mayWrite = !current || (was !== null && current === was);
        if (!mayWrite) continue;
        const next = stringifyRefValue(after.data?.[sourceKey]);
        if (next === current) continue;
        patch[targetKey] = next === null ? null : next;
      }
      if (Object.keys(patch).length) onPatch(patch);
      if (!before) {
        setNotice({
          tone: "warn",
          text: "Saved. Boxes you'd already filled in were left as they are."
        });
      }
    },
    [entity, field, filterValue, onPatch, row, workshopId]
  );

  const adoptCreated = useCallback(
    async (record: CreatedRecord) => {
      const label = createdLabel(record);
      const previous = selectedId;
      onChange(record.id);
      setPending({ id: record.id, label });
      setNotice(null);
      hydration.current += 1;
      const generation = hydration.current;

      const described = await describeCreated(workshopId, field, filterValue, record);
      if (hydration.current !== generation) return;
      setPending(null);

      if (!described) {
        setNotice({
          tone: "warn",
          text: previous
            ? "Saved and linked, but its details didn't load, so the old values were cleared. Fill them in, or pick it again."
            : "Saved and linked, but its details didn't load. Fill the boxes in, or pick it again."
        });
      }

      const option: DwReferenceOption = described ?? { id: record.id, label, sublabel: "", data: {} };
      onPatch({
        ...hydrateFromReference(entity, field, option, latestRow.current, previous),
        [field.key]: record.id
      });
    },
    [entity, field, filterValue, onChange, onPatch, selectedId, workshopId]
  );

  const cascadeLabel = field.refFilterBy
    ? (entity.fields.find((candidate) => candidate.key === field.refFilterBy)?.label ?? field.refFilterBy)
    : "";

  const resolveScan = useCallback(
    async (ref: WorkshopCodeRef): Promise<ScanResolution> => {
      scanHit.current = null;
      const wrongType = scanTypeRefusal(field, ref);
      if (wrongType) return { ok: false, message: wrongType };
      const askedUnder = liveScan.current.filterValue;
      if (field.refFilterBy && !askedUnder) {
        return {
          ok: false,
          message: `Answer “${cascadeLabel}” first.`
        };
      }
      let payload: DwReferencePayload;
      try {
        payload = await listStageReferences(workshopId, {
          model: field.refModel as string,
          scope: field.refScope,
          filterBy: field.refFilterBy ? askedUnder || null : null,
          recordId: ref.id,
          limit: 1
        });
      } catch (error) {
        return { ok: false, message: isUnreachable(error) ? SCAN_OFFLINE_NOTICE : SCAN_LOOKUP_FAILED_NOTICE };
      }
      const outcome = scanLookupOutcome({ field, ref, payload, cascadeLabel: field.refFilterBy ? cascadeLabel : "" });
      if (!outcome.ok) return { ok: false, message: outcome.message };
      scanHit.current = { id: ref.id, filter: askedUnder, option: outcome.option };
      return { ok: true, label: outcome.option.label, detail: outcome.option.sublabel || undefined };
    },
    [cascadeLabel, field, workshopId]
  );

  const commitScan = useCallback((ref: WorkshopCodeRef, resolution: ScanResolution) => {
    const held = scanHit.current;
    scanHit.current = null;
    const { choose: chooseNow, field: fieldNow, filterValue: filterNow } = liveScan.current;
    const decision = scanCommitDecision({ held, ref, resolution, filterValue: filterNow, field: fieldNow });
    if (decision.commit && !chooseNow(decision.commit)) {
      setNotice(refusedByUnsavedWork("scan the card again"));
      return;
    }
    if (decision.notice) setNotice({ tone: decision.commit ? "done" : "warn", text: decision.notice });
  }, []);

  const cardScan = useRef<{ ref: WorkshopCodeRef; resolution: ScanResolution } | null>(null);
  const resolveCardScan = useCallback(
    async (raw: string): Promise<CardScanOutcome> => {
      cardScan.current = null;
      const card = await resolveCardForScan(raw);
      if (!card.ok) return card;
      const ref: WorkshopCodeRef = { recordType: "artisan", id: card.artisanId };
      const resolution = await resolveScan(ref);
      cardScan.current = { ref, resolution };
      if (!resolution.ok) return { ok: false, message: resolution.message };
      return { ...card, label: resolution.label, detail: resolution.detail ?? card.detail, href: "", hrefLabel: "" };
    },
    [resolveScan]
  );
  const commitCardScan = useCallback(
    (outcome: CardScanOutcome) => {
      const held = cardScan.current;
      cardScan.current = null;
      if (held && outcome.ok) commitScan(held.ref, held.resolution);
    },
    [commitScan]
  );

  const emptyLine = useMemo(() => {
    if (awaitingCascade) {
      return `Answer “${cascadeLabel}” first.`;
    }
    if (loading) return "Searching…";
    if (problem) return problem;
    if (payload?.filtered && !options.length) {
      return "Nothing recorded under it yet.";
    }
    if (query.trim()) return `Nothing matches “${query.trim()}”.`;
    return "No records to choose from yet.";
  }, [awaitingCascade, cascadeLabel, loading, problem, payload, options.length, query]);

  return (
    <div className="grid gap-1" ref={wrapperRef}>
      {field.refModel === "Craft" ? (
        <>
          <CraftSelect
            labelledBy={labelId}
            value={selectedId}
            required={field.required}
            disabled={disabled}
            canAddCraft={craftManager}
            onChange={(id, craft) => void chooseCraft(id, craft?.name ?? "")}
          />
          {!disabled && !craftManager && !selectedId ? (
            <p className="text-xs leading-5 text-ink-500">{CRAFT_REGISTER_BLOCKED}</p>
          ) : null}
        </>
      ) : (
      <div className="relative">
        <button
          type="button"
          className="field-input flex w-full items-center justify-between gap-2 text-left"
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-labelledby={labelId}
          disabled={disabled}
          onClick={() => setOpen((current) => !current)}
        >
          <span className={chosenLabel ? "min-w-0 flex-1 truncate text-ink-900" : "min-w-0 flex-1 truncate text-ink-500"}>
            {chosenLabel || (selectedId ? "Selected" : "Search and select")}
          </span>
          <ChevronDown className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        </button>

        {open ? (
          <div className="absolute left-0 right-0 top-full z-10 mt-1 overflow-hidden rounded-md border border-line-200 bg-card shadow-panel">
            <div className="flex items-center gap-2 border-b border-line-200 px-3 py-2">
              <Search className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
              <input
                ref={inputRef}
                className="min-w-0 flex-1 bg-transparent text-sm text-ink-900 outline-hidden placeholder:text-ink-300"
                type="text"
                role="combobox"
                aria-expanded
                aria-controls={listboxId}
                aria-autocomplete="list"
                aria-activedescendant={options.length ? `${baseId}-opt-${safeHighlight}` : undefined}
                placeholder={`Search ${field.label.toLowerCase()}`}
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setHighlight(0);
                }}
                onKeyDown={(event) => {
                  if (event.key === "ArrowDown") {
                    event.preventDefault();
                    setHighlight((current) => Math.min(current + 1, Math.max(0, options.length - 1)));
                  } else if (event.key === "ArrowUp") {
                    event.preventDefault();
                    setHighlight((current) => Math.max(current - 1, 0));
                  } else if (event.key === "Enter") {
                    event.preventDefault();
                    const option = options[safeHighlight];
                    if (option) choose(option);
                  } else if (event.key === "Escape") {
                    event.preventDefault();
                    setOpen(false);
                  }
                }}
              />
              {loading ? <Loader2 className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
            </div>

            <ul id={listboxId} role="listbox" aria-labelledby={labelId} className="max-h-72 overflow-y-auto">
              {options.length ? (
                options.map((option, index) => {
                  const chosen = option.id === selectedId;
                  return (
                    <li key={option.id} id={`${baseId}-opt-${index}`} role="option" aria-selected={chosen}>
                      <button
                        type="button"
                        className={`flex w-full items-start gap-2 px-3 py-2 text-left transition ${
                          index === safeHighlight
                            ? "bg-purple-50 dark:bg-purple-950"
                            : "hover:bg-surface-50"
                        }`}
                        onMouseEnter={() => setHighlight(index)}
                        onClick={() => choose(option)}
                      >
                        <span className="mt-0.5 h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300">
                          {chosen ? <Check className="h-4 w-4" aria-hidden /> : null}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm text-ink-900">{option.label}</span>
                          {option.sublabel ? (
                            <span className="block truncate text-xs text-ink-500">{option.sublabel}</span>
                          ) : null}
                        </span>
                        <TentativeChip option={option} word={tentativeWord} />
                      </button>
                    </li>
                  );
                })
              ) : (
                <li className="px-3 py-3 text-sm text-ink-500">{emptyLine}</li>
              )}
            </ul>
            {isInlineCreatable(field.refModel) && !disabled ? (
              <button
                type="button"
                className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 dark:text-purple-300 transition hover:bg-purple-50"
                onClick={() => {
                  setOpen(false);
                  setInlineDialog({ mode: "create" });
                }}
              >
                <Plus className="h-4 w-4 shrink-0" aria-hidden />
                {query.trim()
                  ? `Create “${query.trim()}” as a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`
                  : `Create a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`}
              </button>
            ) : null}
            {field.refModel === "Craft" && !disabled ? (
              craftManager ? (
                <a
                  href={query.trim() ? `/crafts?new=${encodeURIComponent(query.trim())}` : "/crafts"}
                  target="_blank"
                  rel="noopener"
                  className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 dark:text-purple-300 transition hover:bg-purple-50"
                >
                  <Plus className="h-4 w-4 shrink-0" aria-hidden />
                  {CRAFT_REGISTER_LINK}
                </a>
              ) : (
                <p className="border-t border-line-200 px-3 py-2.5 text-xs leading-5 text-ink-500">{CRAFT_REGISTER_BLOCKED}</p>
              )
            ) : null}
            <ScopeNotice field={field} payload={payload} cachedAt={cachedAt} />
          </div>
        ) : null}
      </div>
      )}

      {!disabled && pickerTakesScans(field) ? (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
            aria-expanded={scanOpen}
            aria-controls={scanOpen && !awaitingCascade ? scannerId : undefined}
            aria-label={`${
              scanOpen ? `Close the card reader for “${field.label}”` : `Scan a card or tag into “${field.label}”`
            }, in ${entity.title}`}
            disabled={awaitingCascade}
            onClick={() => setScanOpen((current) => !current)}
          >
            <ScanLine className="h-3 w-3" aria-hidden />
            {scanOpen ? `Close the card reader for “${field.label}”` : `Scan a card or tag into “${field.label}”`}
          </button>
          {awaitingCascade ? (
            <span className="text-xs text-ink-500">
              Answer “{cascadeLabel}” first.
            </span>
          ) : null}
        </div>
      ) : null}

      {scanOpen && !disabled && !awaitingCascade && pickerTakesScans(field) ? (
        <div id={scannerId}>
          <WorkshopCodeScanner
            resolve={resolveScan}
            onResolved={commitScan}
            {...(field.refModel === "Artisan"
              ? { resolveCard: resolveCardScan, onCardResolved: commitCardScan }
              : { resolveCard: refuseCardHere })}
            description={`Scan ${article(pickerNoun(field))} ${pickerNoun(field)}'s card or tag.`}
          />
        </div>
      ) : null}

      {selectedId ? (
        <div className="flex flex-wrap items-center gap-2">
          <ActionGroup className="max-sm:w-full"><button
            type="button"
            className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
            disabled={disabled}
            onClick={() => {
              const unlink = () => {
                supersede();
                onChange(null);
                setNotice(null);
              };
              if (interceptLeave(unlink)) {
                setNotice(refusedByUnsavedWork("press “Clear the link” again"));
                return;
              }
              unlink();
            }}
          >
            <X className="h-3 w-3" aria-hidden />
            Clear the link
          </button>
          {isInlineCreatable(field.refModel) && !disabled && recordFormMountedOver !== selectedId ? (
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded-md border border-line-200 px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
              onClick={() => {
                beforeEdit.current = null;
                void describeRecord(workshopId, field, filterValue, selectedId, chosenLabel).then(
                  (option) => {
                    beforeEdit.current = option;
                  }
                );
                setInlineDialog({ mode: "edit", id: selectedId });
              }}
            >
              <Pencil className="h-3 w-3" aria-hidden />
              Edit this {INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}
            </button>
          ) : null}</ActionGroup>
          <span className="text-xs text-ink-500">
            Unlinking keeps the details it filled in.
          </span>
        </div>
      ) : null}

      {pending ? (
        <p className="text-xs leading-5 text-ink-500">
          Filling in the saved details of “{pending.label || "the new record"}”…
        </p>
      ) : null}

      {notice ? (
        notice.tone === "done" ? (
          <p className="text-xs leading-5 text-ink-500">{notice.text}</p>
        ) : (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
            {notice.text}
          </p>
        )
      ) : null}

      {inlineDialog && isInlineCreatable(field.refModel) ? (
        <InlineRecordDialog
          open
          model={field.refModel}
          recordId={inlineDialog.mode === "edit" ? inlineDialog.id : undefined}
          onClose={() => setInlineDialog(null)}
          seed={inlineDialog.mode === "create" ? seed : undefined}
          onCreated={(record) => {
            if (inlineDialog.mode === "edit") {
              void adoptEdited(inlineDialog.id, createdLabel(record) || chosenLabel);
              return;
            }
            void adoptCreated(record);
          }}
          onQueued={() => {
            supersede();
            setNotice({ tone: "warn", text: QUEUED_OFFLINE_NOTICE });
          }}
          onUseExisting={(artisan) => {
            void adoptCreated({ id: artisan.id, name: artisan.name });
          }}
        />
      ) : null}
    </div>
  );
}

export function StageReferenceMultiPicker({
  workshopId,
  field,
  alreadyChosen,
  onAdd,
  disabled,
  triggerLabel,
  allLabel
}: {
  workshopId: string;
  field: DwField;
  alreadyChosen: string[];
  onAdd: (options: DwReferenceOption[]) => number | void;
  disabled?: boolean;
  triggerLabel: string;
  allLabel?: string | null;
}) {
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<DwReferenceOption[]>([]);
  const [highlight, setHighlight] = useState(0);
  const [creating, setCreating] = useState(false);
  const [describing, setDescribing] = useState<string | null>(null);
  const [createProblem, setCreateProblem] = useState<string | null>(null);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const [allBusy, setAllBusy] = useState(false);
  const [allNote, setAllNote] = useState<string | null>(null);
  const linkedWorkshopId = useLinkedWorkshopId();
  const seed = useMemo(() => inlineSeed({ field, linkedWorkshopId }), [field, linkedWorkshopId]);

  const { payload, loading, problem, cachedAt } = useReferenceOptions({
    workshopId,
    field,
    filterValue: "",
    query,
    active: open
  });
  const options = payload?.options ?? [];
  const tentativeWord = tentativeWordFor(payload);
  const existing = useMemo(() => new Set(alreadyChosen), [alreadyChosen]);
  const pickedIds = useMemo(() => new Set(picked.map((option) => option.id)), [picked]);

  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      const target = event.target as Element | null;
      if (target?.closest?.("[data-field-dialog-overlay]")) return;
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const safeHighlight = highlight >= 0 && highlight < options.length ? highlight : 0;

  const toggleOption = useCallback(
    (option: DwReferenceOption) => {
      if (existing.has(option.id)) return;
      setPicked((current) =>
        current.some((item) => item.id === option.id)
          ? current.filter((item) => item.id !== option.id)
          : [...current, option]
      );
    },
    [existing]
  );

  const tickCreated = useCallback(
    async (record: CreatedRecord) => {
      setCreateProblem(null);
      setDescribing(createdLabel(record) || "the new record");
      const option = await describeCreated(workshopId, field, "", record);
      setDescribing(null);
      if (!option) {
        setCreateProblem(
          "Saved, but not ticked. Search for it above and tick it."
        );
        return;
      }
      setPicked((current) => (current.some((entry) => entry.id === option.id) ? current : [...current, option]));
    },
    [field, workshopId]
  );

  async function addAll() {
    const model = field.refModel;
    if (!model) return;
    setAllBusy(true);
    setAllNote(null);
    const latest: { payload: DwReferencePayload | null } = { payload: null };
    const outcome = await loadStageReferences<DwReferencePayload>({
      workshopId,
      model,
      scope: field.refScope,
      isEmpty: (payload) => payload.options.length === 0,
      fetch: () =>
        listStageReferences(workshopId, {
          model,
          scope: field.refScope,
          filterBy: null,
          search: null,
          limit: REFERENCE_PAGE_MAX
        }),
      onPayload: (payload) => {
        latest.payload = payload;
      }
    });
    setAllBusy(false);
    const payload = latest.payload;
    if (!payload) {
      setAllNote(outcome.error instanceof Error ? outcome.error.message : "The list could not be loaded.");
      return;
    }
    const fresh = remainingOptions(payload.options, alreadyChosen);
    if (!fresh.length) {
      setAllNote(BULK_ALL_NOTHING_LEFT);
      return;
    }
    const added = onAdd(fresh);
    const note = cutShortNote(payload.truncated, fresh.length, typeof added === "number" ? added : fresh.length, triggerLabel);
    if (note) setAllNote(note);
  }

  return (
    <div className="relative" ref={wrapperRef}>
      <div className="flex flex-wrap items-center gap-2">
      <button
        type="button"
        className="field-button-secondary"
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => {
          setOpen((current) => !current);
          setPicked([]);
          setHighlight(0);
          setCreateProblem(null);
          setAllNote(null);
        }}
      >
        {triggerLabel}
      </button>
      {allLabel ? (
        <button
          type="button"
          className="field-button-secondary"
          disabled={disabled || allBusy}
          aria-describedby={allNote ? `${baseId}-all-note` : undefined}
          onClick={() => {
            void addAll();
          }}
        >
          {allBusy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Plus className="h-4 w-4" aria-hidden />}
          {allLabel}
        </button>
      ) : null}
      </div>
      {allLabel ? (
        <p id={`${baseId}-all-note`} role="status" className={allNote ? "mt-1 text-xs text-ink-500" : "sr-only"}>
          {allNote ?? ""}
        </p>
      ) : null}

      {open ? (
        <div className="absolute right-0 z-10 mt-1 w-[min(28rem,90vw)] overflow-hidden rounded-md border border-line-200 bg-card shadow-panel">
          <div className="flex items-center gap-2 border-b border-line-200 px-3 py-2">
            <Search className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <input
              className="min-w-0 flex-1 bg-transparent text-sm text-ink-900 outline-hidden placeholder:text-ink-300"
              type="text"
              autoFocus
              role="combobox"
              aria-expanded
              aria-controls={listboxId}
              aria-autocomplete="list"
              aria-activedescendant={options.length ? `${baseId}-opt-${safeHighlight}` : undefined}
              aria-label={`Search ${field.label.toLowerCase()}`}
              placeholder={`Search ${field.label.toLowerCase()}`}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setHighlight(0);
              }}
              onKeyDown={(event) => {
                if (event.key === "ArrowDown") {
                  event.preventDefault();
                  setHighlight((current) => Math.min(current + 1, Math.max(0, options.length - 1)));
                } else if (event.key === "ArrowUp") {
                  event.preventDefault();
                  setHighlight((current) => Math.max(current - 1, 0));
                } else if (event.key === "Enter") {
                  event.preventDefault();
                  const option = options[safeHighlight];
                  if (option) toggleOption(option);
                } else if (event.key === "Escape") {
                  event.preventDefault();
                  setOpen(false);
                }
              }}
            />
            {loading ? <Loader2 className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
          </div>

          <ul id={listboxId} role="listbox" aria-multiselectable className="max-h-72 overflow-y-auto">
            {options.length ? (
              options.map((option, index) => {
                const used = existing.has(option.id);
                const ticked = pickedIds.has(option.id);
                return (
                  <li key={option.id} id={`${baseId}-opt-${index}`} role="option" aria-selected={ticked || used}>
                    <button
                      type="button"
                      className={`flex w-full items-start gap-2 px-3 py-2 text-left transition ${
                        used
                          ? "cursor-not-allowed opacity-60"
                          : ticked
                            ? "bg-purple-50 dark:bg-purple-950"
                            : "hover:bg-surface-50"
                      } ${
                        index === safeHighlight && !used
                          ? "ring-1 ring-inset ring-purple-600/40 dark:ring-purple-400/40"
                          : ""
                      }`}
                      disabled={used}
                      onMouseEnter={() => {
                        if (!used) setHighlight(index);
                      }}
                      onClick={() => toggleOption(option)}
                    >
                      <span
                        aria-hidden
                        className={`mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-sm border ${
                          ticked || used ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card"
                        }`}
                      >
                        {ticked || used ? <Check className="h-3 w-3" /> : null}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm text-ink-900">{option.label}</span>
                        {option.sublabel ? (
                          <span className="block truncate text-xs text-ink-500">{option.sublabel}</span>
                        ) : null}
                        {used ? <span className="block text-xs text-ink-500">Already on this list</span> : null}
                      </span>
                      <TentativeChip option={option} word={tentativeWord} />
                    </button>
                  </li>
                );
              })
            ) : (
              <li className="px-3 py-3 text-sm text-ink-500">
                {loading ? "Searching…" : problem ? problem : query.trim() ? `Nothing matches “${query.trim()}”.` : "No records to choose from yet."}
              </li>
            )}
          </ul>

          {isInlineCreatable(field.refModel) && !disabled ? (
            <button
              type="button"
              className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 dark:text-purple-300 transition hover:bg-purple-50"
              onClick={() => setCreating(true)}
            >
              <Plus className="h-4 w-4 shrink-0" aria-hidden />
              {query.trim()
                ? `Create “${query.trim()}” as a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`
                : `Create a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`}
            </button>
          ) : null}

          <ScopeNotice field={field} payload={payload} cachedAt={cachedAt} />

          {describing ? (
            <p className="border-t border-line-200 px-3 py-2 text-xs leading-5 text-ink-500">
              Loading the saved details of “{describing}”…
            </p>
          ) : null}
          {createProblem ? (
            <p className="border-t border-line-200 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
              {createProblem}
            </p>
          ) : null}

          <div className="flex items-center justify-between gap-2 border-t border-line-200 px-3 py-2">
            <span className="text-xs text-ink-500" id={`${baseId}-count`}>
              {picked.length ? `${picked.length} selected` : "Nothing selected yet"}
            </span>
            <ActionGroup>
              <button type="button" className="text-xs font-medium text-ink-500 underline" onClick={() => setOpen(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="field-button"
                aria-describedby={`${baseId}-count`}
                disabled={!picked.length}
                onClick={() => {
                  onAdd(picked);
                  setPicked([]);
                  setOpen(false);
                }}
              >
                Add {picked.length || ""}
              </button>
            </ActionGroup>
          </div>
        </div>
      ) : null}

      {creating && isInlineCreatable(field.refModel) ? (
        <InlineRecordDialog
          open
          model={field.refModel}
          onClose={() => setCreating(false)}
          seed={seed}
          onCreated={(record) => {
            void tickCreated(record);
          }}
          onQueued={() => {
            setDescribing(null);
            setCreateProblem(QUEUED_OFFLINE_NOTICE);
          }}
          onUseExisting={(artisan) => {
            void tickCreated({ id: artisan.id, name: artisan.name });
          }}
        />
      ) : null}
    </div>
  );
}
