"use client";

import { Users } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { candidateLine, type DuplicateCandidate } from "@/lib/duplicates";

/**
 * "Already recorded?" — the artisans the server thinks this may be, as the form is filled.
 *
 * Shown, never blocking: the person in the room decides. "Open" goes to the existing record (handed
 * back to the host inside a design-workshop dialog); "Different person" sets every shown row aside,
 * and the create then tells the server they were looked at (`duplicateAcknowledged`).
 */
export function PossibleDuplicates({
  candidates,
  onOpen,
  onDifferent
}: {
  candidates: DuplicateCandidate[];
  onOpen: (candidate: DuplicateCandidate) => void;
  onDifferent: () => void;
}) {
  if (!candidates.length) return null;
  return (
    <section
      aria-labelledby="possible-duplicates-title"
      className="rounded-md border border-amber-500 bg-amber-100 px-3 py-3 text-amber-800"
    >
      <h2 id="possible-duplicates-title" className="flex items-center gap-2 text-sm font-semibold">
        <Users className="h-4 w-4" aria-hidden />
        Already recorded?
      </h2>
      <CandidateRows candidates={candidates} onOpen={onOpen} />
      <ActionGroup className="mt-3">
        <button type="button" className="field-button-secondary" onClick={onDifferent}>
          Different person
        </button>
      </ActionGroup>
    </section>
  );
}

function CandidateRows({
  candidates,
  onOpen
}: {
  candidates: DuplicateCandidate[];
  onOpen: (candidate: DuplicateCandidate) => void;
}) {
  return (
    <ul className="mt-2 grid gap-2">
      {candidates.map((candidate) => (
        <li
          key={candidate.artisan.id}
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-amber-500/40 bg-card px-3 py-2 text-ink-900"
        >
          <div className="min-w-0">
            <p className="truncate text-sm font-medium">{candidate.artisan.name}</p>
            {candidateLine(candidate) ? <p className="truncate text-xs text-ink-500">{candidateLine(candidate)}</p> : null}
            <ul className="mt-1 flex flex-wrap gap-1" aria-label="Why">
              {candidate.reasons.map((reason) => (
                <li
                  key={reason.code}
                  className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] text-ink-700"
                >
                  {reason.label}
                </li>
              ))}
            </ul>
          </div>
          <ActionGroup inline align="end">
            <button type="button" className="field-button-secondary" onClick={() => onOpen(candidate)}>
              Open
            </button>
          </ActionGroup>
        </li>
      ))}
    </ul>
  );
}

/**
 * The same rows as a question, when the server held a save (409 `artisan_possible_duplicate`).
 * Escape and the backdrop mean "Keep editing", the choice that loses nothing.
 */
export function PossibleDuplicateDialog({
  open,
  message,
  candidates,
  onOpen,
  onDifferent,
  onKeepEditing,
  saving
}: {
  open: boolean;
  message: string | null;
  candidates: DuplicateCandidate[];
  onOpen: (candidate: DuplicateCandidate) => void;
  onDifferent: () => void;
  onKeepEditing: () => void;
  saving?: boolean;
}) {
  const keepRef = useRef<HTMLButtonElement | null>(null);
  return (
    <FieldDialog
      open={open}
      onClose={onKeepEditing}
      role="alertdialog"
      tone="warning"
      icon={<Users className="h-4 w-4" aria-hidden />}
      showClose={false}
      dismissOnBackdrop
      initialFocusRef={keepRef}
      title="Already recorded?"
      description={message ?? "This may be someone already recorded."}
      footer={
        <>
          <button type="button" ref={keepRef} className="field-button-secondary" onClick={onKeepEditing}>
            Keep editing
          </button>
          <button type="button" className="field-button" disabled={saving} onClick={onDifferent}>
            {saving ? "Saving…" : "Different person — save"}
          </button>
        </>
      }
    >
      <CandidateRows candidates={candidates.slice(0, 3)} onOpen={onOpen} />
    </FieldDialog>
  );
}
