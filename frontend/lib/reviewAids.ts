/**
 * The review aids, on the client (2026-10-17): what makes a handed-in report quicker to read and
 * answer for the inspectors, the directors and the approving authority.
 *
 * - **Field notes in one batch (B2).** A reader flags fields as they read; the drafts collect in a
 *   tray and go out together — "File 3 notes" or "Send back with 3 notes".
 * - **Freshness (A19).** Each note says whether the stage it names was edited after it was filed —
 *   the server compares the stage rows' own timestamps (`editedSince`).
 * - **"Checked, no corrections" (C5).** One verdict per inspector per round; shown to the approver,
 *   never required for approval.
 * - **"Changed since round N" (C13).** The stage data as handed in for each round is kept; this file
 *   compares two of them field by field.
 * - **Queue readiness (B4).** Completeness, this round's notes and inspectors cleared, per row.
 *
 * The pure half (diff, labels, tray) is held by `e2e/review-aids-unit.spec.ts`.
 */

import { apiFetch } from "@/lib/api";
import {
  formFields,
  isFilled,
  rowTitle,
  type DwEntity,
  type DwEntryData,
  type DwFeedbackAnswer,
  type DwInspectionFeedback,
  type DwRow,
  type DwStage,
  type DwStageData,
  type DwSummary,
  type DwValue
} from "@/lib/designWorkshops";
import { plural } from "@/lib/sharingScope";

/* ────────────────────────────────────────────────────────────────────────────
 * Wire shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/** One inspector's "Checked, no corrections" on one round. */
export type DwClearance = {
  inspectorId: string;
  inspectorName: string | null;
  round: number;
  clearedAt: string | null;
};

/** A round whose hand-in copy was kept. */
export type DwRoundSnapshotRef = { round: number; takenAt: string | null };

export type DwRoundSnapshot = DwRoundSnapshotRef & { stages: Record<string, DwStageData> };

/** A queue row's readiness. `percent` null means the server did not score it. */
export type DwReadiness = {
  percent: number | null;
  stagesComplete: number | null;
  stagesTotal: number | null;
  notesThisRound: number;
  inspectors: number;
  cleared: number;
};

/** The keys the review aids add to a detail read. All optional: older servers send none of them. */
export type DwReviewAidKeys = {
  clearances?: DwClearance[];
  myClearedAt?: string | null;
  roundSnapshots?: DwRoundSnapshotRef[];
};

/** A note with the two keys added on 2026-10-17. */
export type DwReviewNote = DwInspectionFeedback & {
  editedSince?: boolean | null;
  actorPost?: string | null;
};

export type DwQueueSort = "default" | "oldest" | "newest" | "complete";
export type DwClearedFilter = "" | "all" | "some" | "none";

/** Which read a call goes through. */
export type DwReviewSurface = "inspection" | "approval" | "oversight";

const BASE: Record<DwReviewSurface, string> = {
  inspection: "/design-workshop-inspections",
  approval: "/design-workshop-approvals",
  oversight: "/design-workshop-oversight/assigned"
};

/* ────────────────────────────────────────────────────────────────────────────
 * Calls
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwNoteDraft = {
  /** Local id for the tray; never sent. */
  id: string;
  note: string;
  stageKey: string | null;
  fieldKey: string | null;
};

type DwNoteItem = { note: string; stageKey?: string | null; fieldKey?: string | null };

function items(drafts: DwNoteDraft[]): DwNoteItem[] {
  return drafts
    .map((draft) => ({ note: draft.note.trim(), stageKey: draft.stageKey, fieldKey: draft.fieldKey }))
    .filter((draft) => draft.note.length > 0);
}

export type DwNotesAnswer = DwFeedbackAnswer & DwReviewAidKeys;

/** File every drafted note at once. Moves nothing. Inspectors and directors. */
export function fileNotes(surface: "inspection" | "oversight", workshopId: string, drafts: DwNoteDraft[]) {
  return apiFetch<DwNotesAnswer>(`${BASE[surface]}/${encodeURIComponent(workshopId)}/feedback/batch`, {
    method: "POST",
    body: JSON.stringify({ notes: items(drafts) })
  });
}

/** An inspector sends the report back with every drafted note. */
export function sendBackWithNotes(workshopId: string, drafts: DwNoteDraft[], cover?: string | null) {
  return apiFetch<DwNotesAnswer>(
    `${BASE.inspection}/${encodeURIComponent(workshopId)}/send-back/batch`,
    { method: "POST", body: JSON.stringify({ notes: items(drafts), note: cover?.trim() || null }) }
  );
}

/** The approver's `/revise` body carrying the tray. */
export function reviseBody(drafts: DwNoteDraft[], cover: string, stageKey: string | null) {
  const notes = items(drafts);
  return notes.length
    ? { note: cover.trim() || null, notes }
    : { note: cover.trim(), stageKey: stageKey || null };
}

/** "Checked, no corrections" on the current round, or take it back. */
export function setRoundCleared(workshopId: string, cleared: boolean) {
  return apiFetch<DwNotesAnswer>(`${BASE.inspection}/${encodeURIComponent(workshopId)}/clearance`, {
    method: cleared ? "POST" : "DELETE"
  });
}

/** The stage data as handed in for one round. */
export function getRoundSnapshot(surface: DwReviewSurface, workshopId: string, round: number) {
  return apiFetch<DwRoundSnapshot>(`${BASE[surface]}/${encodeURIComponent(workshopId)}/rounds/${round}`);
}

/** The approver's queue with the two new controls. */
export function readinessQuery(params: { sort?: DwQueueSort; cleared?: DwClearedFilter }) {
  return {
    sort: params.sort && params.sort !== "default" ? params.sort : undefined,
    cleared: params.cleared || undefined
  };
}


/* ────────────────────────────────────────────────────────────────────────────
 * Labels
 * ──────────────────────────────────────────────────────────────────────────── */

export const POST_LABELS: Record<string, string> = {
  ASSISTANT_DIRECTOR: "Assistant Director",
  REGIONAL_DIRECTOR: "Regional Director"
};

/** "Edited since" / "Untouched" — or null when the server did not say. */
export function freshnessLabel(note: Pick<DwReviewNote, "editedSince">): string | null {
  if (note.editedSince === true) return "Edited since";
  if (note.editedSince === false) return "Untouched";
  return null;
}

/** Who wrote a note, in the register's words. */
export function noteAuthor(note: DwReviewNote): string {
  const name = note.actorName?.trim() || "An officer no longer named";
  if (note.byApprovingAuthority) return `${name} · approving authority`;
  if (note.actorPost && POST_LABELS[note.actorPost]) return `${name} · ${POST_LABELS[note.actorPost]}`;
  return name;
}

/** What a note is about: "12. Costing · Unit cost", the whole report, or the stored key. */
export function noteTarget(
  note: Pick<DwInspectionFeedback, "stageKey" | "fieldKey">,
  stages: DwStage[]
): string {
  if (!note.stageKey) return "The report as a whole";
  const stage = stages.find((candidate) => candidate.key === note.stageKey);
  if (!stage) return note.stageKey;
  const field = note.fieldKey ? fieldLabel(stage, note.fieldKey) : null;
  return field ? `${stage.number}. ${stage.title} · ${field}` : `${stage.number}. ${stage.title}`;
}

function fieldLabel(stage: DwStage, fieldKey: string): string | null {
  for (const entity of stage.entities) {
    const match = entity.fields.find((field) => field.key === fieldKey);
    if (match) return match.label;
  }
  return null;
}

/** The tray's buttons: "File 3 notes", "Send back with 3 notes". */
export function fileLabel(count: number): string {
  return count === 1 ? "File 1 note" : `File ${plural(count, "note")}`;
}

export function sendBackLabel(count: number): string {
  return count === 0 ? "Send back" : `Send back with ${plural(count, "note")}`;
}

/** The chips a queue row carries, in reading order. Each is a word, never colour alone. */
export function readinessChips(
  readiness: DwReadiness | null | undefined,
  row: Pick<DwSummary, "lastHandedInAt" | "status">,
  now: Date = new Date()
): string[] {
  const chips: string[] = [];
  if (!readiness) return chips;
  if (readiness.notesThisRound > 0) chips.push(`${plural(readiness.notesThisRound, "note")} this round`);
  if (readiness.inspectors > 0) {
    chips.push(
      readiness.cleared >= readiness.inspectors
        ? "All inspectors: no corrections"
        : `${readiness.cleared} of ${readiness.inspectors} inspectors: no corrections`
    );
  } else {
    chips.push("No inspector assigned");
  }
  const handed = row.lastHandedInAt ? new Date(row.lastHandedInAt) : null;
  if (handed && Number.isFinite(handed.getTime()) && row.status === "PRE_SUBMISSION") {
    const days = Math.max(0, Math.floor((now.getTime() - handed.getTime()) / 86_400_000));
    chips.push(days === 0 ? "Handed in today" : `Waiting ${plural(days, "day")}`);
  }
  return chips;
}

/** The progress line under a row's bar. */
export function readinessSentence(readiness: DwReadiness | null | undefined): string {
  if (!readiness || readiness.percent === null) return "Not scored";
  const stagesLine =
    readiness.stagesComplete !== null && readiness.stagesTotal !== null
      ? ` · ${readiness.stagesComplete} of ${readiness.stagesTotal} stages complete`
      : "";
  return `${readiness.percent}% of required fields${stagesLine}`;
}

/** Completeness dot for the stage menu. */
export type StageDot = "complete" | "partial" | "empty";

export function stageDot(score: { requiredFilled: number; requiredTotal: number; isComplete?: boolean } | undefined): StageDot {
  if (!score) return "empty";
  if (score.isComplete) return "complete";
  return score.requiredFilled > 0 ? "partial" : "empty";
}

export const STAGE_DOT_LABEL: Record<StageDot, string> = {
  complete: "Complete",
  partial: "Started",
  empty: "Not started"
};

/* ────────────────────────────────────────────────────────────────────────────
 * "Changed since round N"
 * ──────────────────────────────────────────────────────────────────────────── */

export type FieldChange = { before: DwValue | undefined; after: DwValue | undefined };

export type EntityChanges = {
  /** Field changes on a singleton, by field key. */
  fields: Record<string, FieldChange>;
  /** Collection rows, by entry id: added rows, and changed fields of kept rows. */
  addedRows: string[];
  changedRows: Record<string, Record<string, FieldChange>>;
  removedRows: Array<{ title: string; row: DwRow }>;
};

export type StageChanges = {
  entities: Record<string, EntityChanges>;
  customChanged: number;
  count: number;
};

function canonical(value: DwValue | undefined): string {
  if (!isFilled(value)) return "";
  return JSON.stringify(value, (_key, inner) =>
    inner && typeof inner === "object" && !Array.isArray(inner)
      ? Object.fromEntries(Object.entries(inner as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)))
      : inner
  );
}

function fieldChanges(entity: DwEntity, before: DwEntryData, after: DwEntryData): Record<string, FieldChange> {
  const out: Record<string, FieldChange> = {};
  for (const field of formFields(entity)) {
    if (canonical(before[field.key]) !== canonical(after[field.key])) {
      out[field.key] = { before: before[field.key], after: after[field.key] };
    }
  }
  return out;
}

function rowId(row: DwRow, index: number): string {
  return row._entryId ?? row._clientKey ?? `#${index}`;
}

/** What moved between a round's hand-in copy and the report as it is now, stage by stage. */
export function roundChanges(
  stages: DwStage[],
  current: Record<string, DwStageData> | undefined,
  before: Record<string, DwStageData> | undefined
): Record<string, StageChanges> {
  const out: Record<string, StageChanges> = {};
  for (const stage of stages) {
    const now = current?.[stage.key];
    const then = before?.[stage.key];
    const result: StageChanges = { entities: {}, customChanged: 0, count: 0 };
    for (const entity of stage.entities) {
      const changes: EntityChanges = { fields: {}, addedRows: [], changedRows: {}, removedRows: [] };
      if (entity.cardinality === "COLLECTION") {
        const nowRows = now?.collections?.[entity.key] ?? [];
        const thenRows = then?.collections?.[entity.key] ?? [];
        const thenById = new Map(thenRows.map((row, index) => [rowId(row, index), row]));
        const nowIds = new Set<string>();
        nowRows.forEach((row, index) => {
          const id = rowId(row, index);
          nowIds.add(id);
          const old = thenById.get(id);
          if (!old) {
            changes.addedRows.push(id);
            result.count += 1;
            return;
          }
          const moved = fieldChanges(entity, old, row);
          if (Object.keys(moved).length) {
            changes.changedRows[id] = moved;
            result.count += Object.keys(moved).length;
          }
        });
        thenRows.forEach((row, index) => {
          if (!nowIds.has(rowId(row, index))) {
            changes.removedRows.push({ title: rowTitle(entity, row, index), row });
            result.count += 1;
          }
        });
      } else {
        changes.fields = fieldChanges(entity, then?.singleton ?? {}, now?.singleton ?? {});
        result.count += Object.keys(changes.fields).length;
      }
      result.entities[entity.key] = changes;
    }
    const keys = new Set([...Object.keys(now?.custom ?? {}), ...Object.keys(then?.custom ?? {})]);
    for (const key of keys) {
      if (canonical(now?.custom?.[key]) !== canonical(then?.custom?.[key])) result.customChanged += 1;
    }
    result.count += result.customChanged;
    out[stage.key] = result;
  }
  return out;
}

/** "4 changes in 2 stages since round 1" — or that nothing moved. */
export function changesSummary(changes: Record<string, StageChanges>, round: number): string {
  const moved = Object.values(changes).filter((stage) => stage.count > 0);
  if (!moved.length) return `Nothing changed since round ${round}.`;
  const total = moved.reduce((sum, stage) => sum + stage.count, 0);
  return `${plural(total, "change")} in ${plural(moved.length, "stage")} since round ${round}.`;
}

/** The earlier rounds a reader can compare against: kept copies older than the current round. */
export function comparableRounds(
  snapshots: DwRoundSnapshotRef[] | undefined,
  currentRound: number | null | undefined
): number[] {
  const current = currentRound ?? 0;
  return (snapshots ?? [])
    .map((snapshot) => snapshot.round)
    .filter((round) => round < current)
    .sort((a, b) => b - a);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The tray, kept per browser so a reload does not lose drafted notes
 * ──────────────────────────────────────────────────────────────────────────── */

const TRAY_KEY = "dw_review_tray";

/**
 * Which screen a tray belongs to. One account can inspect a workshop AND approve it (a master
 * admin), and a note drafted as an inspector must never leave in the approver's send-back.
 */
export type DwTraySurface = "inspect" | "approve" | "supervise";

export function trayKey(userId: string | undefined, workshopId: string, surface: DwTraySurface): string {
  return `${TRAY_KEY}:${surface}:${userId ?? "anon"}:${workshopId}`;
}

/** Forget every drafted tray in this browser — on sign-out, so a shared machine keeps none. */
export function clearReviewTrays(): void {
  try {
    const storage = window.localStorage;
    const keys: string[] = [];
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (key && key.startsWith(`${TRAY_KEY}:`)) keys.push(key);
    }
    keys.forEach((key) => storage.removeItem(key));
  } catch {
    // Storage blocked: nothing was kept.
  }
}

export function loadTray(userId: string | undefined, workshopId: string, surface: DwTraySurface): DwNoteDraft[] {
  try {
    const raw = window.localStorage.getItem(trayKey(userId, workshopId, surface));
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(parsed)
      ? parsed.filter(
          (item): item is DwNoteDraft =>
            !!item && typeof item === "object" && typeof (item as DwNoteDraft).note === "string"
        )
      : [];
  } catch {
    return [];
  }
}

export function saveTray(
  userId: string | undefined,
  workshopId: string,
  surface: DwTraySurface,
  drafts: DwNoteDraft[]
): void {
  const key = trayKey(userId, workshopId, surface);
  try {
    if (drafts.length) window.localStorage.setItem(key, JSON.stringify(drafts));
    else window.localStorage.removeItem(key);
  } catch {
    // Storage blocked: the tray still works for this visit.
  }
}

/** How many notes one batch may carry — the server's `MAX_BATCH_NOTES`, held equal by a spec. */
export const MAX_TRAY_NOTES = 30;

let draftSeq = 0;

export function newDraft(note: string, stageKey: string | null, fieldKey: string | null): DwNoteDraft {
  draftSeq += 1;
  return { id: `d${Date.now().toString(36)}${draftSeq}`, note, stageKey, fieldKey };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The ministry register: where a row opens (A17)
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The read-only view of a register row that THIS reader may open: the approving authority's read for
 * a Ministry Admin or the master admin, the supervisor's read for an Assistant or Regional Director
 * (the register lists them only the workshops they are posted to), and the record page for any other
 * kind of workshop.
 */
export function registerRowHref(
  kind: "design" | "other",
  id: string,
  reader: { approves: boolean }
): string {
  const safe = encodeURIComponent(id);
  if (kind === "other") return `/workshops/${safe}`;
  return reader.approves ? `/design-workshop-approvals/${safe}` : `/officers/monitored/${safe}`;
}
