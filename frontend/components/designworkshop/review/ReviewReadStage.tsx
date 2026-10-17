"use client";

/**
 * ONE STAGE, READ FOR A REVIEW — shared by the inspection, approval and supervision screens.
 *
 * Every answered field with who wrote it, the photographs inline (A23), the notes filed about each
 * field beside it, a "Flag" on each field that drafts a note about it (B2), and — when the reader
 * compares with an earlier round — what changed and what it was (C13).
 *
 * It renders values through the same interpreters the designer's form uses
 * (`inspectionFieldReading`), never the designer's controls: those would draw pickers and buttons
 * aimed at routes these readers are refused.
 */

import { Flag } from "lucide-react";

import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import { ReaderCustomAnswers, ReaderMediaValue } from "@/components/designworkshop/ReaderWorkshopMedia";
import type { DwCustomDefinition } from "@/lib/customSections";
import { inspectionFieldReading } from "@/lib/designWorkshopInspections";
import {
  formFields,
  isFilled,
  rowTitle,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwFieldStamp,
  type DwRegistry,
  type DwRow,
  type DwStage,
  type DwStageCompleteness,
  type DwStageData
} from "@/lib/designWorkshops";
import { freshnessLabel, type DwReviewNote, type EntityChanges, type FieldChange, type StageChanges } from "@/lib/reviewAids";
import { plural } from "@/lib/sharingScope";

export type FlagTarget = { stageKey: string; fieldKey: string | null };

/** The small word chips the review read uses. Each carries its meaning in words. */
export function ReviewChip({ tone, children }: { tone: "changed" | "fresh" | "stale" | "neutral"; children: string }) {
  const tones = {
    changed: "border-purple-300 bg-purple-50 text-purple-800 dark:border-purple-700 dark:bg-purple-950/50 dark:text-purple-200",
    fresh: "border-success-600/30 bg-success-100 text-success-600",
    stale: "border-amber-500/40 bg-amber-100 text-amber-800",
    neutral: "border-line-200 bg-surface-50 text-ink-700"
  } as const;
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium leading-4 ${tones[tone]}`}>
      {children}
    </span>
  );
}

function NoteLine({ note }: { note: DwReviewNote }) {
  const fresh = freshnessLabel(note);
  return (
    <li className="flex flex-wrap items-baseline gap-x-2 gap-y-1 rounded-md border border-amber-500/30 bg-amber-100 px-2.5 py-1.5 text-xs leading-5 text-amber-800">
      <span className="min-w-0 flex-1 whitespace-pre-wrap">
        <span className="font-semibold">Round {note.round}: </span>
        {note.note}
      </span>
      {fresh ? <ReviewChip tone={note.editedSince ? "fresh" : "stale"}>{fresh}</ReviewChip> : null}
    </li>
  );
}

function FieldNotes({ notes }: { notes: DwReviewNote[] }) {
  if (!notes.length) return null;
  return <ul className="mt-1 grid gap-1">{notes.map((note) => <NoteLine key={note.id} note={note} />)}</ul>;
}

function FlagButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={`Flag ${label}`}
      className="inline-flex min-h-11 shrink-0 items-center gap-1 rounded-md px-2 text-xs font-medium text-ink-500 transition hover:bg-purple-50 hover:text-purple-700 sm:min-h-8 dark:hover:bg-purple-950/40 dark:hover:text-purple-300"
    >
      <Flag className="h-3.5 w-3.5" aria-hidden />
      Flag
    </button>
  );
}

function readingText(registry: DwRegistry, entity: DwEntity, field: DwField, row: DwEntryData): string {
  const reading = inspectionFieldReading(registry, entity, field, row);
  if (reading.kind === "text") return reading.text;
  if (reading.kind === "media") return plural(reading.count, "file");
  return "Empty";
}

function ReadField({
  registry,
  entity,
  field,
  row,
  stamp,
  change,
  notes,
  onFlag
}: {
  registry: DwRegistry;
  entity: DwEntity;
  field: DwField;
  row: DwEntryData;
  stamp?: DwFieldStamp | null;
  change?: FieldChange;
  notes: DwReviewNote[];
  onFlag?: () => void;
}) {
  const reading = inspectionFieldReading(registry, entity, field, row);
  if (reading.kind === "empty" && !change && !notes.length) return null;
  const was = change ? readingText(registry, entity, field, { [field.key]: change.before ?? null }) : null;
  return (
    <div className="grid gap-0.5 py-2">
      <div className="flex items-center justify-between gap-2">
        <span className="flex flex-wrap items-center gap-2">
          <span className="field-label">{field.label}</span>
          {change ? <ReviewChip tone="changed">{isFilled(change.before) ? "Changed" : "New"}</ReviewChip> : null}
        </span>
        {onFlag ? <FlagButton label={field.label} onClick={onFlag} /> : null}
      </div>
      {reading.kind === "media" ? (
        <ReaderMediaValue value={row[field.key]} />
      ) : reading.kind === "text" ? (
        <span className="whitespace-pre-wrap text-sm leading-6 text-ink-900">{reading.text}</span>
      ) : (
        <span className="text-sm text-ink-500">Empty</span>
      )}
      {change && isFilled(change.before) ? (
        <span className="whitespace-pre-wrap text-xs leading-5 text-ink-500">Was: {was}</span>
      ) : null}
      <FieldProvenance stamp={stamp} />
      <FieldNotes notes={notes} />
    </div>
  );
}

function ReadRecord({
  registry,
  stageKey,
  entity,
  row,
  stamps,
  changes,
  notesByField,
  onlyChanges,
  onFlag
}: {
  registry: DwRegistry;
  stageKey: string;
  entity: DwEntity;
  row: DwEntryData;
  stamps: Record<string, DwFieldStamp> | undefined;
  changes?: Record<string, FieldChange>;
  notesByField?: Map<string, DwReviewNote[]>;
  onlyChanges: boolean;
  onFlag?: (target: FlagTarget) => void;
}) {
  const fields = formFields(entity);
  const shown = fields.filter((field) => {
    if (onlyChanges && !changes?.[field.key]) return false;
    return (
      inspectionFieldReading(registry, entity, field, row).kind !== "empty" ||
      Boolean(changes?.[field.key]) ||
      Boolean(notesByField?.get(field.key)?.length)
    );
  });
  const unanswered = fields.filter((field) => inspectionFieldReading(registry, entity, field, row).kind === "empty").length;
  return (
    <div className="grid divide-y divide-line-200">
      {shown.map((field) => (
        <ReadField
          change={changes?.[field.key]}
          entity={entity}
          field={field}
          key={field.key}
          notes={notesByField?.get(field.key) ?? []}
          onFlag={onFlag ? () => onFlag({ stageKey, fieldKey: field.key }) : undefined}
          registry={registry}
          row={row}
          stamp={stamps?.[field.key]}
        />
      ))}
      {!onlyChanges && unanswered > 0 ? (
        <p className="pt-2 text-xs leading-5 text-ink-500">
          {unanswered} of {plural(fields.length, "field")} unanswered.
        </p>
      ) : null}
    </div>
  );
}

function rowKey(row: DwRow, index: number): string {
  return row._entryId ?? row._clientKey ?? `#${index}`;
}

export function ReviewReadStage({
  registry,
  stage,
  data,
  score,
  definition,
  notes,
  changes,
  onlyChanges = false,
  onFlag
}: {
  registry: DwRegistry;
  stage: DwStage;
  data: DwStageData | undefined;
  /** The workshop-level score for this stage — `DwStageData.completeness` is empty on these reads. */
  score: DwStageCompleteness | undefined;
  definition?: DwCustomDefinition | null;
  /** Every note filed about this stage. */
  notes: DwReviewNote[];
  /** What moved since the round being compared with, when one is. */
  changes?: StageChanges;
  onlyChanges?: boolean;
  onFlag?: (target: FlagTarget) => void;
}) {
  const singleton: DwEntryData = data?.singleton ?? {};
  const provenance = data?.provenance;
  const customAnswers = Object.entries(data?.custom ?? {}).filter(([, value]) => isFilled(value)).length;
  const collections = stage.entities.filter((entity) => entity.cardinality === "COLLECTION");
  const singletons = stage.entities.filter((entity) => entity.cardinality !== "COLLECTION");
  const nothingRecorded =
    !data ||
    (Object.keys(singleton).length === 0 &&
      collections.every((entity) => (data.collections?.[entity.key] ?? []).length === 0) &&
      customAnswers === 0);

  const fieldKeys = new Set(stage.entities.flatMap((entity) => entity.fields.map((field) => field.key)));
  const stageNotes = notes.filter((note) => !note.fieldKey || !fieldKeys.has(note.fieldKey));
  const byField = new Map<string, DwReviewNote[]>();
  for (const note of notes) {
    if (note.fieldKey && fieldKeys.has(note.fieldKey)) {
      byField.set(note.fieldKey, [...(byField.get(note.fieldKey) ?? []), note]);
    }
  }
  const changeCount = changes?.count ?? 0;

  return (
    <section className="panel scroll-mt-28 p-4" id={`stage-${stage.key}`} data-stage-key={stage.key}>
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-line-200 pb-3">
        <h2 className="font-display text-lg font-bold text-ink-900">
          {stage.number}. {stage.title}
        </h2>
        <span className="flex flex-wrap items-center gap-2">
          {changes ? (
            <ReviewChip tone={changeCount ? "changed" : "neutral"}>
              {changeCount ? `${plural(changeCount, "change")}` : "No changes"}
            </ReviewChip>
          ) : null}
          <span className="text-xs text-ink-500">
            {score
              ? score.isComplete
                ? "Complete"
                : `${score.requiredFilled} of ${score.requiredTotal} required`
              : "Nothing recorded"}
            {stage.optionalStage ? " · optional" : ""}
          </span>
          {onFlag ? <FlagButton label={stage.title} onClick={() => onFlag({ stageKey: stage.key, fieldKey: null })} /> : null}
        </span>
      </header>

      {stageNotes.length ? (
        <div className="pt-3">
          <FieldNotes notes={stageNotes} />
        </div>
      ) : null}

      {nothingRecorded && !changeCount ? (
        <p className="pt-3 text-sm text-ink-500">Nothing recorded.</p>
      ) : onlyChanges && changes && !changeCount ? null : (
        <div className="grid gap-4 pt-3">
          {singletons.map((entity) => {
            const entityChanges: EntityChanges | undefined = changes?.entities[entity.key];
            if (onlyChanges && !Object.keys(entityChanges?.fields ?? {}).length) return null;
            return (
              <div key={entity.key}>
                {singletons.length > 1 || collections.length > 0 ? (
                  <h3 className="mb-1 text-sm font-medium text-ink-700">{entity.title}</h3>
                ) : null}
                <ReadRecord
                  changes={entityChanges?.fields}
                  entity={entity}
                  notesByField={byField}
                  onFlag={onFlag}
                  onlyChanges={onlyChanges}
                  registry={registry}
                  row={singleton}
                  stageKey={stage.key}
                  stamps={provenance?.singleton}
                />
              </div>
            );
          })}
          {collections.map((entity) => {
            const rows: DwRow[] = data?.collections?.[entity.key] ?? [];
            const entityChanges = changes?.entities[entity.key];
            const removed = entityChanges?.removedRows ?? [];
            const visible = onlyChanges
              ? rows.filter((row, index) => {
                  const id = rowKey(row, index);
                  return entityChanges?.addedRows.includes(id) || Boolean(entityChanges?.changedRows[id]);
                })
              : rows;
            const entityNotes = entity.fields.flatMap((field) => byField.get(field.key) ?? []);
            if (!visible.length && !removed.length && !entityNotes.length) return null;
            return (
              <div key={entity.key}>
                <h3 className="mb-1 text-sm font-medium text-ink-700">
                  {entity.title} · {rows.length === 1 ? "1 entry" : `${rows.length} entries`}
                </h3>
                <FieldNotes notes={entityNotes} />
                <div className="mt-2 grid gap-3">
                  {visible.map((row, index) => {
                    const id = rowKey(row, index);
                    const added = entityChanges?.addedRows.includes(id) ?? false;
                    return (
                      <div className="rounded-md border border-line-200 bg-surface-50 p-3" key={id}>
                        <p className="mb-1 flex flex-wrap items-center gap-2 text-sm font-medium text-ink-900">
                          {rowTitle(entity, row, index)}
                          {added ? <ReviewChip tone="changed">New</ReviewChip> : null}
                        </p>
                        <ReadRecord
                          changes={added ? undefined : entityChanges?.changedRows[id]}
                          entity={entity}
                          onFlag={onFlag}
                          onlyChanges={onlyChanges && !added}
                          registry={registry}
                          row={row}
                          stageKey={stage.key}
                          stamps={row._entryId ? provenance?.collections?.[entity.key]?.[row._entryId] : undefined}
                        />
                      </div>
                    );
                  })}
                  {removed.map((gone, index) => (
                    <p
                      className="rounded-md border border-line-200 bg-card px-3 py-2 text-sm text-ink-500"
                      key={`removed-${index}`}
                    >
                      Removed: {gone.title}
                    </p>
                  ))}
                </div>
              </div>
            );
          })}
          {customAnswers > 0 && (!onlyChanges || (changes?.customChanged ?? 0) > 0) ? (
            definition !== undefined ? (
              <ReaderCustomAnswers definition={definition} stageKey={stage.key} stamps={provenance?.custom} values={data?.custom} />
            ) : (
              <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
                {plural(customAnswers, "answer")} to this workshop&apos;s own questions — printed in the report file.
              </p>
            )
          ) : null}
        </div>
      )}
    </section>
  );
}
