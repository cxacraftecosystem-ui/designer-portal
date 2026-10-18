"use client";

/**
 * NOTES ON A REVIEW SCREEN: the composer a Flag opens, the tray drafts collect in, and the register
 * of what has been filed — shared by the inspection, approval and supervision screens (B2, A19).
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { MessageSquarePlus, Trash2 } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { dwFeedbackRounds, type DwStage } from "@/lib/designWorkshops";
import {
  freshnessLabel,
  loadTray,
  MAX_TRAY_NOTES,
  newDraft,
  noteAuthor,
  noteTarget,
  saveTray,
  type DwNoteDraft,
  type DwTraySurface,
  type DwReviewNote
} from "@/lib/reviewAids";
import { plural } from "@/lib/sharingScope";

import { ReviewChip, type FlagTarget } from "./ReviewReadStage";

const MAX_NOTE_CHARS = 4000;

/** The drafted notes of one reader on one workshop and screen, kept in this browser until sent. */
export function useReviewTray(userId: string | undefined, workshopId: string, surface: DwTraySurface) {
  const [drafts, setDrafts] = useState<DwNoteDraft[]>([]);
  const loaded = useRef<string | null>(null);

  useEffect(() => {
    const key = `${surface}:${userId ?? ""}:${workshopId}`;
    if (loaded.current === key) return;
    loaded.current = key;
    setDrafts(loadTray(userId, workshopId, surface));
  }, [userId, workshopId, surface]);

  const update = useCallback(
    (next: (current: DwNoteDraft[]) => DwNoteDraft[]) => {
      setDrafts((current) => {
        const value = next(current);
        saveTray(userId, workshopId, surface, value);
        return value;
      });
    },
    [userId, workshopId, surface]
  );

  return {
    drafts,
    full: drafts.length >= MAX_TRAY_NOTES,
    add: (note: string, target: FlagTarget) =>
      update((current) =>
        current.length >= MAX_TRAY_NOTES ? current : [...current, newDraft(note, target.stageKey || null, target.fieldKey)]
      ),
    remove: (id: string) => update((current) => current.filter((draft) => draft.id !== id)),
    clear: () => update(() => [])
  };
}

/** The note a Flag (or the bar's Note) opens: what it is about, and the sentence. */
export function NoteComposer({
  target,
  stages,
  onClose,
  onAdd
}: {
  /** Null while closed. */
  target: FlagTarget | null;
  stages: DwStage[];
  onClose: () => void;
  onAdd: (note: string, target: FlagTarget) => void;
}) {
  const [note, setNote] = useState("");
  const [stageKey, setStageKey] = useState("");
  const [fieldKey, setFieldKey] = useState<string | null>(null);
  const textRef = useRef<HTMLTextAreaElement>(null);
  const [openFor, setOpenFor] = useState<FlagTarget | null>(null);

  // Reset the box each time it opens for a new target (state from the previous render, not an effect).
  if (target !== openFor) {
    setOpenFor(target);
    if (target) {
      setNote("");
      setStageKey(target.stageKey);
      setFieldKey(target.fieldKey);
    }
  }

  const about = noteTarget({ stageKey: stageKey || null, fieldKey }, stages);

  function submit() {
    const text = note.trim();
    if (!text) return;
    onAdd(text, { stageKey, fieldKey: stageKey ? fieldKey : null });
    onClose();
  }

  return (
    <FieldDialog
      open={target !== null}
      onClose={onClose}
      title="Add a note"
      description={about}
      icon={<MessageSquarePlus className="h-4 w-4" aria-hidden />}
      initialFocusRef={textRef}
      className="max-w-lg"
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="field-button" disabled={!note.trim()} onClick={submit}>
            Add to notes
          </button>
        </>
      }
    >
      <div className="grid gap-3">
        <div className="grid gap-1">
          <span className="field-label">About</span>
          <SearchableSelect
            ariaLabel="Which stage is it about?"
            value={stageKey}
            onChange={(next) => {
              setStageKey(next);
              setFieldKey(null);
            }}
            noneLabel="The report as a whole"
            options={stages.map((stage) => ({ value: stage.key, label: `${stage.number}. ${stage.title}` }))}
            advanceOnSelect={false}
          />
        </div>
        <div className="grid gap-1">
          <label className="field-label" htmlFor="review-note-text">
            What should change
          </label>
          <textarea
            ref={textRef}
            id="review-note-text"
            className="field-input min-h-28"
            maxLength={MAX_NOTE_CHARS}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) submit();
            }}
            placeholder="What's wrong and what it should say"
          />
        </div>
      </div>
    </FieldDialog>
  );
}

/** The drafted notes, waiting to go out together. The caller supplies the sending buttons. */
export function NoteTray({
  drafts,
  stages,
  onRemove,
  actions,
  footnote
}: {
  drafts: DwNoteDraft[];
  stages: DwStage[];
  onRemove: (id: string) => void;
  actions: React.ReactNode;
  footnote?: string | null;
}) {
  if (!drafts.length) return null;
  return (
    <section className="panel mb-4 grid gap-3 p-4" aria-labelledby="note-tray-heading">
      <h2 id="note-tray-heading" className="font-display text-base font-bold tracking-tight text-ink-900">
        Your notes · {drafts.length}
      </h2>
      <ul className="grid gap-2">
        {drafts.map((draft) => (
          <li className="flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2" key={draft.id}>
            <span className="min-w-0 flex-1">
              <span className="block text-xs font-medium text-ink-500">{noteTarget(draft, stages)}</span>
              <span className="block whitespace-pre-wrap text-sm leading-6 text-ink-900">{draft.note}</span>
            </span>
            <button
              type="button"
              className="inline-flex min-h-11 items-center gap-1 rounded-md px-2 text-xs font-medium text-ink-500 hover:bg-error-100 hover:text-error-600 sm:min-h-8"
              onClick={() => onRemove(draft.id)}
              aria-label={`Remove the note about ${noteTarget(draft, stages)}`}
            >
              <Trash2 className="h-3.5 w-3.5" aria-hidden />
              Remove
            </button>
          </li>
        ))}
      </ul>
      {drafts.length >= MAX_TRAY_NOTES ? (
        <p className="text-xs text-ink-700">{MAX_TRAY_NOTES} notes is the most one send carries. Send these first.</p>
      ) : null}
      <ActionGroup align="end">{actions}</ActionGroup>
      {footnote ? <p className="text-xs leading-5 text-ink-500">{footnote}</p> : null}
    </section>
  );
}

/** Everything filed so far, by round: what it is about, who said it, and whether it was acted on. */
export function NoteRegister({
  notes,
  stages,
  truncated,
  title = "Notes on this report"
}: {
  notes: DwReviewNote[];
  stages: DwStage[];
  truncated?: boolean;
  title?: string;
}) {
  const rounds = dwFeedbackRounds(notes) as Array<{ round: number; rows: DwReviewNote[] }>;
  const untouched = notes.filter((note) => note.editedSince === false).length;
  return (
    <section className="panel mb-4 grid gap-3 p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="font-display text-base font-bold tracking-tight text-ink-900">{title}</h2>
        <span className="text-xs text-ink-500">
          {plural(notes.length, "note")}
          {untouched ? ` · ${untouched} untouched` : ""}
          {truncated ? " · older ones not shown" : ""}
        </span>
      </div>
      {rounds.length ? (
        rounds.map((group) => (
          <div className="grid gap-2" key={group.round}>
            <h3 className="field-label">Round {group.round}</h3>
            <ul className="grid gap-2">
              {group.rows.map((row) => {
                const fresh = freshnessLabel(row);
                return (
                  <li className="rounded-md border border-line-200 bg-field-50 px-3 py-2" key={row.id}>
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-xs font-medium text-ink-700">{noteTarget(row, stages)}</span>
                      {fresh ? <ReviewChip tone={row.editedSince ? "fresh" : "stale"}>{fresh}</ReviewChip> : null}
                      {row.sentBack ? <ReviewChip tone="neutral">Sent back</ReviewChip> : null}
                    </div>
                    <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-ink-900">{row.note}</p>
                    <p className="mt-1 text-xs leading-5 text-ink-500">{noteAuthor(row)}</p>
                  </li>
                );
              })}
            </ul>
          </div>
        ))
      ) : (
        <p className="text-sm text-ink-700">No notes yet.</p>
      )}
    </section>
  );
}
