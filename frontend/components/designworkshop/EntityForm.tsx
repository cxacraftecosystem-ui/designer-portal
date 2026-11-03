"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, ArrowDown, ArrowUp, ChevronDown, GripVertical, Plus, Trash2 } from "lucide-react";

import { RefSuggestion } from "@/components/designworkshop/RefSuggestion";
import { StageFieldInput, type StageCaptureContext } from "@/components/designworkshop/FieldInput";
import {
  MirroredFieldsDisclosure,
  StageRecordEmbed,
  embeddedRecordId,
  mirrorPointFor,
  mirrorRefField,
  splitMirroredFields
} from "@/components/designworkshop/StageRecordEmbed";
import { fieldFormatError } from "@/components/designworkshop/stageFieldFormats";
import { StageReferenceMultiPicker } from "@/components/designworkshop/StageReferenceField";
import { bulkAllLabel } from "@/components/designworkshop/bulkPick";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { moveIndex, useDragReorder } from "@/components/hooks/useDragReorder";
import { FLASH_MS } from "@/components/hooks/useRevealRow";
import { findMissingViews } from "@/lib/imageQuality";
import { isTentativeRow, tentativeField } from "@/lib/sketchTentative";
import { FIELD_ANCHOR_ATTRIBUTE, ROW_ANCHOR_ATTRIBUTE, type StageFocus } from "@/lib/workshopSearch";
import {
  blankRow,
  hydrateFromReference,
  inputValue,
  captionFieldFor,
  formFields,
  isFilled,
  rowTitle,
  splitByTier,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwFieldStamp,
  type DwReferenceOption,
  type DwRow,
  type DwValue
} from "@/lib/designWorkshops";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type FieldErrors = Record<string, string> | undefined;

export const ROW_REFUSAL_KEY = "_row";

export function rowRefusal(errors: FieldErrors): string | null {
  const message = errors?.[ROW_REFUSAL_KEY];
  return typeof message === "string" && message.length > 0 ? message : null;
}

export function withoutRowRefusal(errors: FieldErrors): FieldErrors {
  if (!errors || !(ROW_REFUSAL_KEY in errors)) return errors;
  const rest = { ...errors };
  delete rest[ROW_REFUSAL_KEY];
  return rest;
}

function RowRefusalLine({ message, className }: { message: string; className: string }) {
  return (
    <p role="alert" className={className}>
      {message}
    </p>
  );
}

function FieldCell({
  entity,
  field,
  wide,
  rowKey,
  focused,
  children
}: {
  entity: DwEntity;
  field: DwField;
  wide: boolean;
  rowKey: string | null;
  focused: boolean;
  children: React.ReactNode;
}) {
  const reduce = useAppReducedMotion();
  const reduceRef = useRef(reduce);
  useEffect(() => {
    reduceRef.current = reduce;
  });

  const cell = useRef<HTMLDivElement | null>(null);
  const [flash, setFlash] = useState(false);

  useEffect(() => {
    if (!focused) return;
    const node = cell.current;
    if (!node) return;
    const frame = requestAnimationFrame(() => {
      node.scrollIntoView({
        behavior: reduceRef.current ? "auto" : "smooth",
        block: "center",
        inline: "nearest"
      });
      setFlash(true);
    });
    const timer = setTimeout(() => setFlash(false), FLASH_MS);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(timer);
    };
  }, [focused]);

  return (
    <div
      ref={cell}
      className={wide ? "fr-flash-row md:col-span-2" : "fr-flash-row"}
      data-flash={flash ? "true" : undefined}
      {...{ [FIELD_ANCHOR_ATTRIBUTE]: `${entity.key}.${field.key}` }}
      {...(rowKey ? { [ROW_ANCHOR_ATTRIBUTE]: rowKey } : {})}
    >
      {children}
    </div>
  );
}

function FieldGrid({
  entity,
  fields,
  data,
  onChange,
  onPatch,
  workshopId,
  errors,
  disabled,
  stageKey,
  rowKey,
  anchorRowKey,
  capture,
  focus,
  provenance,
  recordFormMountedOver = null
}: {
  entity: DwEntity;
  fields: DwField[];
  data: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  errors: FieldErrors;
  disabled?: boolean;
  stageKey?: string;
  rowKey?: string | null;
  anchorRowKey?: string | null;
  capture?: StageCaptureContext;
  focus?: StageFocus;
  provenance?: Record<string, DwFieldStamp>;
  recordFormMountedOver?: string | null;
}) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {fields.map((field) => {
        const captionField = captionFieldFor(entity, field.key);
        const wide =
          field.type === "LONG_TEXT" ||
          field.type === "RICH_TEXT" ||
          field.type === "GEO" ||
          field.type === "IMAGE" ||
          field.type === "IMAGE_LIST" ||
          field.type === "FILE" ||
          field.type === "AUDIO" ||
          field.type === "VIDEO";
        return (
          <FieldCell
            key={field.key}
            entity={entity}
            field={field}
            wide={wide}
            rowKey={anchorRowKey ?? rowKey ?? null}
            focused={
              focus?.entityKey === entity.key &&
              focus.fieldKey === field.key &&
              (focus.rowKey ?? null) === (anchorRowKey ?? rowKey ?? null)
            }
          >
            <StageFieldInput
              field={field}
              value={data[field.key]}
              onChange={(next) => onChange(field.key, next)}
              entity={entity}
              row={data}
              onPatch={onPatch}
              workshopId={workshopId}
              disabled={disabled}
              error={errors?.[field.key] ?? null}
              place={{ stageKey, entityKey: entity.key, rowKey }}
              capture={capture}
              stamp={provenance?.[field.key] ?? null}
              recordFormMountedOver={recordFormMountedOver}
              caption={
                captionField
                  ? {
                      field: captionField,
                      value: data[captionField.key],
                      onChange: (next) => onChange(captionField.key, next)
                    }
                  : undefined
              }
            />
            {field.refSuggest ? (
              <RefSuggestion
                entity={entity}
                field={field}
                row={data}
                workshopId={workshopId}
                onPatch={onPatch}
                disabled={disabled}
              />
            ) : null}
          </FieldCell>
        );
      })}
    </div>
  );
}

function MissingViewsHint({ entity, data }: { entity: DwEntity; data: DwEntryData }) {
  const findings = useMemo(
    () => findMissingViews(entity.key, data as Record<string, unknown>),
    [entity.key, data]
  );
  if (!findings.length) return null;
  return (
    <div className="mt-3 flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <p className="text-xs leading-5">{findings[0].message}</p>
    </div>
  );
}

function focusIsIn(focus: StageFocus | undefined, entity: DwEntity, fields: DwField[]): boolean {
  if (!focus || focus.entityKey !== entity.key) return false;
  return fields.some((field) => field.key === focus.fieldKey);
}

export function refusedIn(fields: DwField[], errors: FieldErrors): number {
  if (!errors) return 0;
  return fields.filter((field) => Boolean(errors[field.key])).length;
}

function needsAttentionIn(fields: DwField[], errors: FieldErrors, row: DwEntryData): number {
  return fields.filter(
    (field) =>
      !field.deprecated &&
      (Boolean(errors?.[field.key]) || Boolean(fieldFormatError(field, row[field.key])))
  ).length;
}

function AdvancedDisclosure({
  id,
  count,
  refused = 0,
  defaultOpen = false,
  children
}: {
  id: string;
  count: number;
  refused?: number;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const refusedBefore = useRef(refused);
  useEffect(() => {
    const had = refusedBefore.current;
    refusedBefore.current = refused;
    if (refused > 0 && had === 0) setOpen(true);
  }, [refused]);

  if (!count) return null;
  return (
    <div className="mt-4 border-t border-line-200 pt-4">
      <button
        type="button"
        className="inline-flex items-center gap-2 text-sm font-medium text-purple-700 dark:text-purple-300 transition hover:text-purple-800 dark:hover:text-purple-200"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen((current) => !current)}
      >
        <ChevronDown className={`h-4 w-4 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
        More detail
        <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">{count}</span>
        {refused ? (
          <span className="rounded-full bg-error-100 px-2 py-0.5 text-xs font-medium text-red-800">
            {refused} to fix
          </span>
        ) : null}
      </button>
      {open ? (
        <div id={id} className="mt-4">
          {children}
        </div>
      ) : null}
    </div>
  );
}

function MirroredEntityBody({
  entity,
  refField,
  data,
  onChange,
  onPatch,
  workshopId,
  errors,
  disabled,
  stageKey,
  rowKey,
  anchorRowKey,
  capture,
  focus,
  provenance,
  idPrefix
}: {
  entity: DwEntity;
  refField: DwField;
  data: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  errors: FieldErrors;
  disabled?: boolean;
  stageKey?: string;
  rowKey?: string | null;
  anchorRowKey?: string | null;
  capture?: StageCaptureContext;
  focus?: StageFocus;
  provenance?: Record<string, DwFieldStamp>;
  idPrefix: string;
}) {
  const groups = useMemo(() => {
    const { pickers, mirrored, workshopOnly } = splitMirroredFields(entity, refField, formFields(entity));
    const workshop = splitByTier(workshopOnly);
    return {
      pickers,
      workshopPrimary: workshop.primary,
      workshopAdvanced: workshop.advanced,
      mirrored
    };
  }, [entity, refField]);

  const grid = (fields: DwField[], recordFormMountedOver: string | null = null) => (
    <FieldGrid
      entity={entity}
      fields={fields}
      data={data}
      onChange={onChange}
      onPatch={onPatch}
      workshopId={workshopId}
      errors={errors}
      disabled={disabled}
      stageKey={stageKey}
      rowKey={rowKey}
      anchorRowKey={anchorRowKey}
      capture={capture}
      focus={focus}
      provenance={provenance}
      recordFormMountedOver={recordFormMountedOver}
    />
  );

  const focusHere = (focus?.rowKey ?? null) === (anchorRowKey ?? rowKey ?? null);

  const mirroredNeeded = groups.mirrored.some((field) => field.required && !isFilled(data[field.key]));

  return (
    <StageRecordEmbed
      entity={entity}
      refField={refField}
      row={data}
      workshopId={workshopId}
      onPatch={onPatch}
      mountOnRequest={(anchorRowKey ?? rowKey ?? null) !== null}
      picker={grid(groups.pickers, embeddedRecordId(refField, data) || null)}
      workshopFields={
        <>
          {grid(groups.workshopPrimary)}
          <MissingViewsHint entity={entity} data={data} />
          <AdvancedDisclosure
            id={`${idPrefix}-advanced`}
            count={groups.workshopAdvanced.length}
            refused={needsAttentionIn(groups.workshopAdvanced, errors, data)}
            defaultOpen={focusHere && focusIsIn(focus, entity, groups.workshopAdvanced)}
          >
            {grid(groups.workshopAdvanced)}
          </AdvancedDisclosure>
        </>
      }
      mirroredFields={
        <MirroredFieldsDisclosure
          id={`${idPrefix}-mirrored`}
          count={groups.mirrored.length}
          refused={needsAttentionIn(groups.mirrored, errors, data)}
          defaultOpen={mirroredNeeded || (focusHere && focusIsIn(focus, entity, groups.mirrored))}
        >
          {grid(groups.mirrored)}
        </MirroredFieldsDisclosure>
      }
    />
  );
}

function mirrorFor(entity: DwEntity): DwField | null {
  const point = mirrorPointFor(entity);
  return point ? mirrorRefField(entity, point) : null;
}

export function EntityForm({
  entity,
  data,
  onChange,
  onPatch,
  workshopId,
  errors,
  disabled,
  stageKey,
  capture,
  focus,
  provenance
}: {
  entity: DwEntity;
  data: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  errors?: FieldErrors;
  disabled?: boolean;
  stageKey?: string;
  capture?: StageCaptureContext;
  focus?: StageFocus;
  provenance?: Record<string, DwFieldStamp>;
}) {
  const { primary, advanced } = useMemo(() => splitByTier(formFields(entity)), [entity]);
  const mirror = useMemo(() => mirrorFor(entity), [entity]);
  const refusedRow = rowRefusal(errors);

  return (
    <section className="panel p-4">
      <header className="mb-4">
        <h2 className="font-display text-lg font-bold text-ink-900">{entity.title}</h2>
        {entity.description ? <p className="mt-1 text-sm leading-6 text-ink-muted">{entity.description}</p> : null}
      </header>
      {refusedRow ? (
        <RowRefusalLine
          message={refusedRow}
          className="mb-4 rounded-md bg-error-100 px-3 py-2 text-sm font-medium leading-5 text-red-800"
        />
      ) : null}
      {mirror ? (
        <MirroredEntityBody
          entity={entity}
          refField={mirror}
          data={data}
          onChange={onChange}
          onPatch={onPatch}
          workshopId={workshopId}
          errors={errors}
          disabled={disabled}
          stageKey={stageKey}
          capture={capture}
          focus={focus}
          provenance={provenance}
          idPrefix={`entity-${entity.key}`}
        />
      ) : (
        <>
          <FieldGrid
            entity={entity}
            fields={primary}
            data={data}
            onChange={onChange}
            onPatch={onPatch}
            workshopId={workshopId}
            errors={errors}
            disabled={disabled}
            stageKey={stageKey}
            capture={capture}
            focus={focus}
            provenance={provenance}
          />
          <MissingViewsHint entity={entity} data={data} />
          <AdvancedDisclosure
            id={`advanced-${entity.key}`}
            count={advanced.length}
            refused={needsAttentionIn(advanced, errors, data)}
            defaultOpen={focusIsIn(focus, entity, advanced)}
          >
            <FieldGrid
              entity={entity}
              fields={advanced}
              data={data}
              onChange={onChange}
              onPatch={onPatch}
              workshopId={workshopId}
              errors={errors}
              disabled={disabled}
              stageKey={stageKey}
              capture={capture}
              focus={focus}
              provenance={provenance}
            />
          </AdvancedDisclosure>
        </>
      )}
    </section>
  );
}

function rowProgress(entity: DwEntity, row: DwRow): { filled: number; total: number } {
  const required = entity.fields.filter((field) => field.required && !field.deprecated);
  return {
    filled: required.filter((field) => isFilled(row[field.key])).length,
    total: required.length
  };
}

export type ServerHeldRows = Record<string, Set<string>>;

export type StageRowProvenance = {
  serverLoadedAt: number | null;
  lastPushedAt: number | null;
  collections: Record<string, DwRow[]>;
};

export function rowsTheServerCouldHold(
  stage: StageRowProvenance | null | undefined,
  carried: ServerHeldRows = {}
): ServerHeldRows {
  const held: ServerHeldRows = {};
  for (const [entityKey, keys] of Object.entries(carried)) held[entityKey] = new Set(keys);
  if (!stage) return held;
  if (stage.serverLoadedAt === null && stage.lastPushedAt === null) return held;
  for (const [entityKey, rows] of Object.entries(stage.collections ?? {})) {
    const set = held[entityKey] ?? new Set<string>();
    for (const row of rows) if (row._clientKey) set.add(row._clientKey);
    held[entityKey] = set;
  }
  return held;
}

export function removalIsADeletion(removed: DwRow | undefined, held: ReadonlySet<string> | undefined): boolean {
  if (!removed) return true;
  if (removed._entryId) return true;
  if (!removed._clientKey) return true;
  return held?.has(removed._clientKey) ?? false;
}

function keyOf(row: DwRow, index: number): string {
  return row._clientKey ?? row._entryId ?? `index-${index}`;
}

const STAGE_ROW_CAP = 500;

const STAGE_ROW_CAP_NOTICE_AT = Math.floor(STAGE_ROW_CAP * 0.9);

const DROPPED_NAMES_SHOWN = 6;

export type StageEntryBudget = {
  rows: number;
  other: number;
  otherCertain: number;
  otherLabel: string;
};

export function stageEntryBudget(
  entities: readonly DwEntity[],
  collections: Record<string, DwRow[]>,
  singletons: Record<string, DwEntryData>,
  custom: DwEntryData
): StageEntryBudget {
  let rows = 0;
  let singletonEntries = 0;
  let singletonAnswered = 0;
  for (const entity of entities) {
    if (entity.cardinality === "SINGLETON") {
      singletonEntries += 1;
      if (Object.values(singletons[entity.key] ?? {}).some((value) => isFilled(value))) singletonAnswered += 1;
      continue;
    }
    rows += (collections[entity.key] ?? []).length;
  }
  const customEntry = Object.keys(custom).length > 0 ? 1 : 0;
  const customCertain = customEntry && Object.values(custom).some((value) => isFilled(value)) ? 1 : 0;
  const parts: string[] = [];
  if (singletonEntries > 0) parts.push(singletonEntries === 1 ? "this stage's own fields" : "this stage's own field sets");
  if (customEntry) parts.push("its custom questions");
  return {
    rows,
    other: singletonEntries + customEntry,
    otherCertain: singletonAnswered + customCertain,
    otherLabel: parts.join(" and ")
  };
}

export function CollectionTable({
  entity,
  rows,
  onRowsChange,
  workshopId,
  provenance,
  errorsByIndex,
  disabled,
  stageKey,
  capture,
  focus,
  stageEntries
}: {
  entity: DwEntity;
  rows: DwRow[];
  onRowsChange: (rows: DwRow[], removed?: DwRow) => void;
  workshopId: string;
  errorsByIndex?: Record<number, FieldErrors>;
  disabled?: boolean;
  stageKey?: string;
  capture?: StageCaptureContext;
  focus?: StageFocus;
  provenance?: Record<string, Record<string, DwFieldStamp>>;
  stageEntries: StageEntryBudget;
}) {
  const { primary, advanced } = useMemo(() => splitByTier(formFields(entity)), [entity]);
  const mirror = useMemo(() => mirrorFor(entity), [entity]);
  const [openKey, setOpenKey] = useState<string | null>(
    focus?.entityKey === entity.key ? focus.rowKey : null
  );


  const interceptLeave = useLeaveInterceptor();
  function toggleRow(rowKey: string) {
    const closing = openKey !== null && openKey !== rowKey ? openKey : openKey === rowKey ? rowKey : null;
    const open = () => setOpenKey((current) => (current === rowKey ? null : rowKey));
    if (closing !== null && interceptLeave(open)) return;
    open();
  }

  function patchRow(index: number, key: string, value: DwValue) {
    onRowsChange(rows.map((row, position) => (position === index ? { ...row, [key]: value } : row)));
  }

  function patchRowMany(index: number, values: Record<string, DwValue>) {
    if (!Object.keys(values).length) return;
    onRowsChange(rows.map((row, position) => (position === index ? { ...row, ...values } : row)));
  }

  const bulkField = useMemo(() => {
    const refs = formFields(entity).filter(
      (field) => field.type === "REF" && field.refModel && !field.refFilterBy && field.tier === "BASIC"
    );
    return refs.length === 1 ? refs[0] : null;
  }, [entity]);

  const rowsHere = rows.length;
  const rowsElsewhere = Math.max(0, stageEntries.rows - rowsHere);
  const stageTotal = stageEntries.rows + stageEntries.other;
  const stageFloor = stageEntries.rows + stageEntries.otherCertain;
  const stageExact = stageEntries.other === stageEntries.otherCertain;
  const stageHeld = stageExact ? `${stageTotal}` : `${stageFloor}–${stageTotal}`;
  const stageRoom = Math.max(0, STAGE_ROW_CAP - stageTotal);

  const [bulkRefusal, setBulkRefusal] = useState<string | null>(null);

  function addFromReferences(options: DwReferenceOption[]): number {
    if (!bulkField) return 0;
    const taken = options.slice(0, stageRoom);
    const dropped = options.slice(stageRoom);
    const refused = dropped.length;
    const created = taken.map((option) => {
      const row = blankRow();
      return { ...row, ...hydrateFromReference(entity, bulkField, option, row, ""), [bulkField.key]: option.id };
    });
    if (created.length) onRowsChange([...rows, ...created]);
    setOpenKey(null);
    setBulkRefusal(
      refused === 0
        ? null
        : `Added ${taken.length} of ${options.length} — room for ${stageRoom} on this stage. Not added: ` +
            `${dropped
              .slice(0, DROPPED_NAMES_SHOWN)
              .map((option) => option.label)
              .join(", ")}` +
            `${refused > DROPPED_NAMES_SHOWN ? ` and ${refused - DROPPED_NAMES_SHOWN} more` : ""}. Delete rows to make room.`
    );
    return created.length;
  }

  const dragIds = useMemo(() => rows.map(keyOf), [rows]);

  const tentativeFlag = useMemo(() => tentativeField(entity), [entity]);

  function commitMove(from: number, to: number) {
    if (from === to) return;
    if (from < 0 || from >= rows.length) return;
    if (to < 0 || to >= rows.length) return;
    onRowsChange(moveIndex(rows, from, to));
  }

  const drag = useDragReorder({
    order: dragIds,
    locked: Boolean(disabled),
    labelFor: (key) => {
      const index = dragIds.indexOf(key);
      const row = rows[index];
      return row ? rowTitle(entity, row, index) : entity.title;
    },
    onReorder: commitMove
  });

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= rows.length) return;
    commitMove(index, target);
    drag.announceMove(dragIds[index], target);
  }

  function addRow() {
    if (stageRoom < 1) return;
    const row = blankRow();
    onRowsChange([...rows, row]);
    setOpenKey(row._clientKey ?? null);
  }

  function removeRow(index: number) {
    const removed = rows[index];
    onRowsChange(
      rows.filter((_, position) => position !== index),
      removed
    );
    if (openKey && removed && keyOf(removed, index) === openKey) setOpenKey(null);
  }

  const capState: "over" | "full" | "near" | null =
    stageTotal > STAGE_ROW_CAP
      ? "over"
      : stageTotal >= STAGE_ROW_CAP
        ? "full"
        : stageTotal >= STAGE_ROW_CAP_NOTICE_AT
          ? "near"
          : null;
  const rowsAtCap = capState === "over" || capState === "full";
  const capBreakdown = [
    `${rowsHere} in this list`,
    rowsElsewhere > 0 ? `${rowsElsewhere} in this stage's other lists` : null,
    stageEntries.other > 0 ? `${stageEntries.other} for ${stageEntries.otherLabel}` : null
  ]
    .filter((part): part is string => part !== null)
    .reduce((sentence, part, index, parts) =>
      index === 0 ? part : index === parts.length - 1 ? `${sentence} and ${part}` : `${sentence}, ${part}`
    );
  const capNotice =
    capState === null
      ? null
      : capState === "over"
        ? `Stage over the ${STAGE_ROW_CAP}-entry save limit — ${stageHeld} held: ${capBreakdown}. ` +
          `${stageFloor > STAGE_ROW_CAP ? "Saves will fail" : "Saves may fail"} until you delete rows.`
        : capState === "full"
          ? `Stage full — ${stageHeld} of ${STAGE_ROW_CAP} entries: ${capBreakdown}.`
          : `${stageHeld} of ${STAGE_ROW_CAP} entries — ${capBreakdown}. ${stageRoom} left.`;

  const statusNotices = [
    bulkRefusal ? { key: "bulk", text: bulkRefusal } : null,
    capNotice ? { key: "cap", text: capNotice } : null
  ].filter((notice): notice is { key: string; text: string } => notice !== null);

  return (
    <section className="panel p-4">
      <header className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">{entity.title}</h2>
          {entity.description ? <p className="mt-1 text-sm leading-6 text-ink-muted">{entity.description}</p> : null}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {bulkField ? (
            <StageReferenceMultiPicker
              workshopId={workshopId}
              field={bulkField}
              alreadyChosen={rows.map((row) => inputValue(row[bulkField.key])).filter(Boolean)}
              onAdd={addFromReferences}
              disabled={disabled || rowsAtCap}
              triggerLabel={`Add several from ${bulkField.label.toLowerCase()}`}
              allLabel={bulkAllLabel(bulkField.refModel)}
            />
          ) : null}
          <button type="button" className="field-button" disabled={disabled || rowsAtCap} onClick={addRow}>
            <Plus className="h-4 w-4" aria-hidden />
            Add {entity.title.replace(/s$/, "").toLowerCase()}
          </button>
        </div>
      </header>

      <p aria-live="polite" className="sr-only">
        {drag.announcement}
      </p>

      <div role="status">
        {statusNotices.length ? (
          <div className="mb-3 flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
            <div className="grid min-w-0 flex-1 gap-2">
              {statusNotices.map((notice) => (
                <p key={notice.key}>{notice.text}</p>
              ))}
            </div>
          </div>
        ) : null}
      </div>

      {rows.length === 0 ? (
        <p className="rounded-md border border-dashed border-line-200 bg-surface-50 px-4 py-6 text-center text-sm text-ink-muted">
          No {entity.title.toLowerCase()} yet.
        </p>
      ) : (
        <ol className="grid gap-2">
          {rows.map((row, index) => {
            const rowKey = keyOf(row, index);
            const open = openKey === rowKey;
            const panelId = `row-${entity.key}-${rowKey}`;
            const progress = rowProgress(entity, row);
            const rowErrors = errorsByIndex?.[index];
            const rowRefused = rowRefusal(rowErrors);
            const rowShift = drag.shiftFor(rowKey);
            const rowDragging = drag.draggingKey === rowKey;
            return (
              <li
                key={rowKey}
                ref={drag.registerRow(rowKey)}
                style={rowShift ? { transform: `translateY(${rowShift}px)` } : undefined}
                className={
                  rowDragging
                    ? "relative z-10 rounded-md border border-line-200 bg-card shadow-panel ring-2 ring-purple-600/40 transition-transform"
                    : "relative rounded-md border border-line-200 bg-card transition-transform"
                }
              >
                <div className="flex flex-wrap items-center gap-2 px-3 py-2">
                  <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700">
                    <span aria-hidden>{index + 1}</span>
                    <span className="sr-only">
                      Position {index + 1} of {rows.length}.
                    </span>
                  </span>
                  <button
                    type="button"
                    className="min-w-0 flex-1 truncate text-left text-sm font-medium text-ink-900"
                    aria-expanded={open}
                    aria-controls={open ? panelId : undefined}
                    onClick={() => toggleRow(rowKey)}
                  >
                    {rowTitle(entity, row, index)}
                  </button>
                  {tentativeFlag && isTentativeRow(row) ? (
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                      {tentativeFlag.label}
                    </span>
                  ) : null}
                  {progress.total ? (
                    <span
                      className={
                        progress.filled >= progress.total
                          ? "rounded-full bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600"
                          : "rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                      }
                    >
                      {progress.filled}/{progress.total} required
                    </span>
                  ) : null}
                  {rowErrors && Object.keys(rowErrors).length ? (
                    <span className="rounded-full bg-error-100 px-2 py-0.5 text-xs font-medium text-red-800">
                      {Object.keys(rowErrors).length} to fix
                    </span>
                  ) : null}
                  <ActionGroup className="shrink-0">
                    <button
                      type="button"
                      className="grid h-8 w-8 cursor-grab touch-none place-items-center rounded-md border border-line-200 text-ink-500 transition hover:bg-surface-50 active:cursor-grabbing disabled:opacity-40"
                      aria-label={`Reorder ${rowTitle(entity, row, index)} — drag, or use the arrow keys`}
                      disabled={disabled || rows.length < 2}
                      {...drag.handleProps(rowKey)}
                      onKeyDown={(event) => {
                        if (event.key === "ArrowUp") {
                          event.preventDefault();
                          move(index, -1);
                        } else if (event.key === "ArrowDown") {
                          event.preventDefault();
                          move(index, 1);
                        }
                      }}
                    >
                      <GripVertical className="h-4 w-4" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className="grid h-8 w-8 place-items-center rounded-md border border-line-200 text-ink-700 transition hover:bg-surface-50 disabled:opacity-40"
                      aria-label={`Move ${rowTitle(entity, row, index)} up`}
                      disabled={disabled || index === 0}
                      onClick={() => move(index, -1)}
                    >
                      <ArrowUp className="h-4 w-4" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className="grid h-8 w-8 place-items-center rounded-md border border-line-200 text-ink-700 transition hover:bg-surface-50 disabled:opacity-40"
                      aria-label={`Move ${rowTitle(entity, row, index)} down`}
                      disabled={disabled || index === rows.length - 1}
                      onClick={() => move(index, 1)}
                    >
                      <ArrowDown className="h-4 w-4" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className="grid h-8 w-8 place-items-center rounded-md border border-red-200 text-error-600 transition hover:bg-error-100 disabled:opacity-40"
                      aria-label={`Delete ${rowTitle(entity, row, index)}`}
                      disabled={disabled}
                      onClick={() => removeRow(index)}
                    >
                      <Trash2 className="h-4 w-4" aria-hidden />
                    </button>
                  </ActionGroup>
                </div>
                {rowRefused ? (
                  <RowRefusalLine
                    message={rowRefused}
                    className="border-t border-line-200 bg-error-100 px-3 py-2 text-sm font-medium leading-5 text-red-800"
                  />
                ) : null}
                {open ? (
                  <div id={panelId} className="border-t border-line-200 p-3">
                    {mirror ? (
                      <MirroredEntityBody
                        entity={entity}
                        refField={mirror}
                        data={row}
                        onChange={(key, value) => patchRow(index, key, value)}
                        onPatch={(values) => patchRowMany(index, values)}
                        workshopId={workshopId}
                        errors={rowErrors}
                        disabled={disabled}
                        stageKey={stageKey}
                        rowKey={row._clientKey ?? null}
                        anchorRowKey={rowKey}
                        capture={capture}
                        focus={focus}
                        provenance={row._entryId ? provenance?.[String(row._entryId)] : undefined}
                        idPrefix={panelId}
                      />
                    ) : (
                      <>
                        <FieldGrid
                          entity={entity}
                          fields={primary}
                          data={row}
                          onChange={(key, value) => patchRow(index, key, value)}
                          onPatch={(values) => patchRowMany(index, values)}
                          capture={capture}
                          workshopId={workshopId}
                          errors={rowErrors}
                          disabled={disabled}
                          stageKey={stageKey}
                          rowKey={row._clientKey ?? null}
                          anchorRowKey={rowKey}
                          focus={focus}
                          provenance={row._entryId ? provenance?.[String(row._entryId)] : undefined}
                        />
                        <MissingViewsHint entity={entity} data={row} />
                        <AdvancedDisclosure
                          id={`${panelId}-advanced`}
                          count={advanced.length}
                          refused={needsAttentionIn(advanced, rowErrors, row)}
                          defaultOpen={focus?.rowKey === rowKey && focusIsIn(focus, entity, advanced)}
                        >
                          <FieldGrid
                            entity={entity}
                            fields={advanced}
                            data={row}
                            onChange={(key, value) => patchRow(index, key, value)}
                            onPatch={(values) => patchRowMany(index, values)}
                            capture={capture}
                            workshopId={workshopId}
                            provenance={row._entryId ? provenance?.[String(row._entryId)] : undefined}
                            errors={rowErrors}
                            disabled={disabled}
                            stageKey={stageKey}
                            rowKey={row._clientKey ?? null}
                            anchorRowKey={rowKey}
                            focus={focus}
                          />
                        </AdvancedDisclosure>
                      </>
                    )}
                  </div>
                ) : null}
              </li>
            );
          })}
        </ol>
      )}
      {rows.length ? (
        <p className="mt-3 text-xs text-ink-500">
          Changes apply when you save.{" "}
          Rows print in this order — drag or use the arrows to reorder.
        </p>
      ) : null}
    </section>
  );
}
