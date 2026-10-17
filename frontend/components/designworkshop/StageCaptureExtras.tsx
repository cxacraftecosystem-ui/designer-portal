"use client";

/**
 * Everything the stage form gained on 2026-10-17 to cut retyping, in one place so the stage page
 * mounts it with one line: suggestions (`StageAssistPanel`), attendance (stage 3), "Log today"
 * (stage 13), the market-survey sittings (stage 8), one row per selected file (stages 8 and 11),
 * and the counts that replace typed boxes (`useComputedCounts`).
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { ClipboardList, ImagePlus } from "lucide-react";

import { AttendanceCard } from "@/components/designworkshop/AttendanceCard";
import { PROTOTYPE_STAGE, PrototypeLogTodayCard } from "@/components/designworkshop/PrototypeLogTodayCard";
import { StageAssistPanel } from "@/components/designworkshop/StageAssistPanel";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { blankRow, type DwEntryData, type DwRow, type DwStage, type DwValue } from "@/lib/designWorkshops";
import { isLocalWorkshopId, stageLocalMedia } from "@/lib/designWorkshopStore";
import {
  fetchStageAssist,
  fillValues,
  rowMatches,
  suggestedRows,
  type DwAssist,
  type DwAssistSuggestion
} from "@/lib/stageAssist";
import { attendedDays, rosterValues, workshopDays } from "@/lib/stageComputed";

export const ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING";
export const SURVEY_STAGE = "MARKET_SURVEY_CAPTURE";
export const CLOSING_STAGE = "INSPECTION_CLOSING";
export const SETUP_STAGE = "WORKSHOP_SETUP";

/** B11: the collections that take one row per selected photograph, and the box each file fills. */
export const ROWS_FROM_FILES: Record<string, { entityKey: string; fieldKey: string; noun: string }> = {
  SKETCH_DEVELOPMENT: { entityKey: "sketch", fieldKey: "image", noun: "sketch" },
  MARKET_SURVEY_CAPTURE: { entityKey: "competitorProduct", fieldKey: "photo", noun: "product" }
};

/** The stage's assist payload, refetched whenever `token` moves (after each save). */
export function useStageAssist(workshopId: string | null | undefined, stageKey: string, token: number): DwAssist | null {
  const [assist, setAssist] = useState<DwAssist | null>(null);
  useEffect(() => {
    if (!workshopId || isLocalWorkshopId(workshopId)) return;
    let cancelled = false;
    fetchStageAssist(workshopId, stageKey)
      .then((payload) => {
        if (!cancelled) setAssist(payload);
      })
      .catch(() => {
        // Offline or refused: the form works without suggestions, and says nothing about it.
      });
    return () => {
      cancelled = true;
    };
  }, [workshopId, stageKey, token]);
  return assist;
}

const keysOf = (row: DwRow) => [row._entryId, row._clientKey].filter(Boolean).map(String);

/** The workshop days attendance is taken on, from this stage's schedule or the server's answer. */
export function attendanceDaysFor(collections: Record<string, DwRow[]>, assist: DwAssist | null): number[] {
  return workshopDays(collections.workshopScheduleDay ?? [], assist?.attendanceDays ?? []);
}

/**
 * C7/C8: keep each computed box equal to what the rows come to — the derived-field pattern, so the
 * draft, the live preview and the save all carry the same number. Nothing is written when nothing
 * can be counted, which leaves a value typed on an older workshop where it was.
 */
export function useComputedCounts({
  stageKey,
  assist,
  singleton,
  collections,
  patchSingleton,
  patchRows
}: {
  stageKey: string;
  assist: DwAssist | null;
  singleton: DwEntryData;
  collections: Record<string, DwRow[]>;
  patchSingleton: (values: Record<string, DwValue>) => void;
  patchRows: (entityKey: string, rows: DwRow[]) => void;
}) {
  const wanted = useMemo(() => {
    const single: Record<string, number> = {};
    const rows: Record<string, (row: DwRow) => number | null> = {};
    if (stageKey === ROSTER_STAGE) {
      const days = attendanceDaysFor(collections, assist);
      const values = rosterValues(collections.participant ?? [], days);
      if (values.ROSTER_COUNT !== null) single.totalArtisans = values.ROSTER_COUNT;
      if (values.ROSTER_WOMEN !== null) single.womenParticipants = values.ROSTER_WOMEN;
      rows.participant = (row) => attendedDays(row, days);
    } else if (stageKey === SURVEY_STAGE && assist?.sittings) {
      const count = (collections.surveyResponse ?? []).length + assist.sittings.reduce((sum, s) => sum + (s.count || 0), 0);
      if (count) single.responsesCollected = count;
    } else if (stageKey === CLOSING_STAGE && assist) {
      const completed = assist.computed.singleton.participantsCompleted;
      if (typeof completed === "number") single.participantsCompleted = completed;
      const byRow = assist.computed.rows.certificate ?? {};
      rows.certificate = (row) => {
        for (const key of keysOf(row)) {
          const value = byRow[key]?.daysAttended;
          if (typeof value === "number") return value;
        }
        return null;
      };
    }
    return { single, rows };
  }, [stageKey, assist, collections]);

  const patchSingletonRef = useRef(patchSingleton);
  const patchRowsRef = useRef(patchRows);
  useEffect(() => {
    patchSingletonRef.current = patchSingleton;
    patchRowsRef.current = patchRows;
  });

  useEffect(() => {
    const changed: Record<string, DwValue> = {};
    for (const [key, value] of Object.entries(wanted.single)) {
      if (singleton[key] !== value) changed[key] = value;
    }
    if (Object.keys(changed).length) patchSingletonRef.current(changed);
    for (const [entityKey, compute] of Object.entries(wanted.rows)) {
      const field = entityKey === "participant" ? "attendedDays" : "daysAttended";
      const current = collections[entityKey] ?? [];
      let dirty = false;
      const next = current.map((row) => {
        const value = compute(row);
        if (value === null || row[field] === value) return row;
        dirty = true;
        return { ...row, [field]: value };
      });
      if (dirty) patchRowsRef.current(entityKey, next);
    }
  }, [wanted, singleton, collections]);
}

/** Apply one suggestion to the form: a FILL into blank boxes, or ADD_ROWS appended. */
export function applySuggestion(
  suggestion: DwAssistSuggestion,
  singleton: DwEntryData,
  collections: Record<string, DwRow[]>,
  patchSingleton: (values: Record<string, DwValue>) => void,
  patchRows: (entityKey: string, rows: DwRow[]) => void
) {
  const rows = collections[suggestion.entityKey] ?? [];
  if (suggestion.kind === "ADD_ROWS") {
    patchRows(suggestion.entityKey, [...rows, ...suggestedRows(suggestion)]);
    return;
  }
  if (suggestion.rowKey === null) {
    patchSingleton(fillValues(suggestion, singleton));
    return;
  }
  patchRows(
    suggestion.entityKey,
    rows.map((row) => (rowMatches(row, suggestion.rowKey) ? { ...row, ...fillValues(suggestion, row) } : row))
  );
}

export function StageCaptureExtras({
  stage,
  workshopId,
  assist,
  singleton,
  collections,
  patchSingleton,
  patchRows,
  disabled
}: {
  stage: DwStage;
  workshopId: string;
  assist: DwAssist | null;
  singleton: DwEntryData;
  collections: Record<string, DwRow[]>;
  patchSingleton: (values: Record<string, DwValue>) => void;
  patchRows: (entityKey: string, rows: DwRow[]) => void;
  disabled: boolean;
}) {
  const [applied, setApplied] = useState<ReadonlySet<string>>(() => new Set());
  // A fresh answer from the server is the new truth about what is still worth offering.
  useEffect(() => setApplied(new Set()), [assist]);
  const suggestions = (assist?.suggestions ?? []).filter((s) => !(s.kind === "ADD_ROWS" && applied.has(s.id)));
  const fromFiles = ROWS_FROM_FILES[stage.key];

  return (
    <>
      <StageAssistPanel
        suggestions={suggestions}
        singleton={singleton}
        collections={collections}
        disabled={disabled}
        onApply={(suggestion) => {
          applySuggestion(suggestion, singleton, collections, patchSingleton, patchRows);
          setApplied((current) => new Set([...current, suggestion.id]));
        }}
      />
      {stage.key === ROSTER_STAGE ? (
        <AttendanceCard
          days={attendanceDaysFor(collections, assist)}
          participants={collections.participant ?? []}
          startDate={assist?.startDate}
          disabled={disabled}
          onChange={(rows) => patchRows("participant", rows)}
        />
      ) : null}
      {stage.key === PROTOTYPE_STAGE ? (
        <PrototypeLogTodayCard
          prototypes={collections.prototype ?? []}
          logs={collections.prototypeStageLog ?? []}
          disabled={disabled}
          onAdd={(row) => patchRows("prototypeStageLog", [...(collections.prototypeStageLog ?? []), row])}
        />
      ) : null}
      {stage.key === SURVEY_STAGE && assist?.sittings?.length ? <SurveySittingsCard assist={assist} /> : null}
      {fromFiles && stage.entities.some((e) => e.key === fromFiles.entityKey) ? (
        <RowsFromFiles
          workshopId={workshopId}
          stageKey={stage.key}
          entityKey={fromFiles.entityKey}
          fieldKey={fromFiles.fieldKey}
          noun={fromFiles.noun}
          title={stage.entities.find((e) => e.key === fromFiles.entityKey)?.title ?? ""}
          rows={collections[fromFiles.entityKey] ?? []}
          onRows={(rows) => patchRows(fromFiles.entityKey, rows)}
          disabled={disabled}
        />
      ) : null}
    </>
  );
}

/** C8: the linked questionnaire sittings, counted and summarised — never copied in as rows. */
function SurveySittingsCard({ assist }: { assist: DwAssist }) {
  const sittings = assist.sittings ?? [];
  const total = sittings.reduce((sum, s) => sum + (s.count || 0), 0);
  return (
    <section className="panel grid gap-2 p-4" aria-labelledby="survey-sittings-heading">
      <h2 id="survey-sittings-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <ClipboardList className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        Questionnaire sittings · {total}
      </h2>
      <ul className="grid gap-1 text-sm text-ink-700">
        {sittings.map((s) => (
          <li key={s.questionnaireId} className="flex flex-wrap justify-between gap-x-3">
            <span className="min-w-0 truncate">{s.title}</span>
            <span className="text-ink-500">
              {s.count} {s.count === 1 ? "sitting" : "sittings"}
              {s.latest ? ` · latest ${s.latest}` : ""}
            </span>
          </li>
        ))}
      </ul>
      <p className="text-xs text-ink-500">Counted in “Responses collected”.</p>
    </section>
  );
}

/**
 * B11: one row per selected photograph. Each file is kept on this device and uploads with the rest
 * of the stage; the row is numbered (C6) and left unnamed, so it reads as incomplete until named.
 */
function RowsFromFiles({
  workshopId,
  stageKey,
  entityKey,
  fieldKey,
  noun,
  title,
  rows,
  onRows,
  disabled
}: {
  workshopId: string;
  stageKey: string;
  entityKey: string;
  fieldKey: string;
  noun: string;
  title: string;
  rows: DwRow[];
  onRows: (rows: DwRow[]) => void;
  disabled: boolean;
}) {
  const input = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  async function take(files: File[]) {
    if (!files.length) return;
    setBusy(true);
    setNotice(null);
    try {
      const created: DwRow[] = [];
      for (const file of files) {
        const row = blankRow();
        const { ref } = await stageLocalMedia(workshopId, file, {
          stageKey,
          entityKey,
          fieldKey,
          clientKey: row._clientKey ?? null,
          caption: title
        });
        created.push({ ...row, [fieldKey]: ref });
      }
      onRows([...rows, ...created]);
      setNotice(`Added ${created.length} ${created.length === 1 ? noun : `${noun}s`}. Name each one.`);
    } catch {
      setNotice("Those photos couldn't be added. Try again.");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  return (
    <section className="panel grid gap-2 p-4">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <p className="min-w-0 flex-1 text-sm text-ink-700">{title}: one row per photo.</p>
        <ActionGroup inline align="end" className="max-sm:w-full">
          <button
            type="button"
            className="field-button-secondary"
            disabled={disabled || busy}
            onClick={() => input.current?.click()}
          >
            <ImagePlus className="h-4 w-4" aria-hidden />
            {busy ? "Adding…" : "Add from photos"}
          </button>
        </ActionGroup>
      </div>
      <input
        ref={input}
        type="file"
        accept="image/*"
        multiple
        className="hidden"
        onChange={(event) => void take(Array.from(event.target.files ?? []))}
      />
      {notice ? (
        <p className="text-xs text-ink-500" role="status">
          {notice}
        </p>
      ) : null}
    </section>
  );
}
