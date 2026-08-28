"use client";

/**
 * A record the ladder REFUSED to file, as something an admin can act on.
 *
 * ── WHAT THIS REPLACED, AND WHY A LIST WAS NOT ENOUGH ─────────────────────────────────────────
 *
 * `WorkshopMappingPanel` reports two groups: the rows whose own evidence settles where they belong
 * (one button files them all) and the rows it deliberately will not guess about — "the evidence
 * points at more than one workshop", "nothing on the record points at a workshop". That refusal is
 * correct and `services/workshop_inference`'s header argues it at length. But the second group was
 * rendered as a flat `<ul>` of names under the words "open the record and choose its workshop by
 * hand", and there was nothing to open: no link, no id on screen, no action. The panel named the
 * person it was deferring to and then gave them nothing to press. Finding one of these records meant
 * going to another page, paging a twenty-row list for a title, and editing it there — and a record
 * that should never have been recorded could not be got rid of from here at all.
 *
 * ── WHY A BUTTON THAT OPENS A DIALOG, AND NOT THREE BUTTONS PER ROW ───────────────────────────
 *
 * The row already carries two lines of reasoning (which rule gave up, and which workshops the
 * evidence pointed at) and this panel sits ABOVE the workshop form on a page that is not about it.
 * Three controls per row, times up to forty rows a bucket, times six buckets, would bury the one
 * sentence an admin came for. So the card is one activatable thing and the three acts live in the
 * dialog it opens — which is also what makes "open the record" affordable: it can be a real link
 * with the sentence explaining where it goes beside it, rather than an icon nobody can read.
 *
 * A `<button type="button">`, never a `<div onClick>`: tab order, Enter/Space, and the global
 * `:focus-visible` outline all come free from the element and none of them come from a div. The card
 * is deliberately NOT `overflow-hidden` — that outline is drawn OUTSIDE the border box at
 * `outline-offset: 2px`, and clipping the card would erase it on every side (the same rule the
 * guide's step card carries).
 *
 * ── COLOUR NEVER CARRIES THE MEANING ─────────────────────────────────────────────────────────
 *
 * The card names its record type in a chip and states the reason in words. The amber block it sits
 * in carries a triangle icon and a sentence. Nothing here is legible only as a tint.
 */

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { AlertTriangle, ArrowUpRight, ChevronRight, FolderInput, Trash2 } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { DANGER_BUTTON_CLASS, FieldDialog } from "@/components/dialogs/FieldDialog";
import { Select } from "@/components/FormControls";
import { readableError } from "@/components/review/reviewErrors";
import {
  destinationFor,
  discardUnfiledRecord,
  discardedNotice,
  fileUnfiledRecord,
  filedNotice
} from "@/components/settings/unfiledRecords";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";

/** The subset of a plan row this card and its dialog read. */
export type UnfiledRow = {
  id: string;
  title: string;
  reasonCopy: string | null;
  candidateTitles: string[];
};

/** One row plus the identity of the bucket it came out of. */
export type UnfiledRecord = {
  bucket: string;
  /** The server's own singular noun for the type. Never invented here — both clients print this one. */
  singular: string;
  row: UnfiledRow;
};

export function UnfiledRecordCard({
  record,
  onOpen
}: {
  record: UnfiledRecord;
  onOpen: () => void;
}) {
  const { row, singular } = record;
  return (
    <button
      type="button"
      onClick={onOpen}
      /*
        The accessible name is the card's own text, in this order: the type, then the title, then the
        reason. No `aria-label` overriding it — a label that differs from the visible words breaks
        "label in name" for anyone driving this by voice, and the visible words are already the two
        things the name has to contain.
      */
      className="grid w-full gap-1 rounded-md border border-line-200 bg-card p-2.5 text-left transition hover:border-purple-300 hover:bg-purple-50"
    >
      <span className="flex items-center gap-1.5">
        <span className="rounded-full bg-field-200 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-ink-900">
          {singular}
        </span>
        <ChevronRight className="ml-auto h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
      </span>
      <span className="text-[11px] font-semibold leading-4 text-ink-900">{row.title}</span>
      {row.reasonCopy ? (
        <span className="text-[11px] leading-4 text-ink-500">Needs a person — {row.reasonCopy}</span>
      ) : null}
      {row.candidateTitles.length ? (
        <span className="text-[11px] leading-4 text-ink-500">
          Evidence points at {row.candidateTitles.join(" or ")}
        </span>
      ) : null}
    </button>
  );
}

/**
 * The three acts, for one card.
 *
 * `record` is kept by the caller across the close so the exit animation plays over the real content
 * instead of an empty card — the same reason `ConfirmProvider` keeps its `options` past `open`.
 *
 * THE DELETE CONFIRM IS `useConfirm`, NOT A SECOND HAND-ROLLED PANEL. It gives the four properties
 * §12.12 demands of a destructive prompt — `role="alertdialog"`, a backdrop that refuses to dismiss,
 * initial focus on Cancel, and a solid red confirm — and it stacks on top of this dialog correctly:
 * `FieldDialog` keeps a stack so only the topmost answers Escape or reclaims stray focus, and the
 * provider's portal node is appended to `<body>` at the moment it opens, i.e. after this one. The
 * pattern is already shipped — `CollabPanel` calls `useConfirm` from inside `CollabDialog`.
 */
export function UnfiledRecordDialog({
  open,
  record,
  workshops,
  workshopsComplete,
  onClose,
  onDone
}: {
  open: boolean;
  record: UnfiledRecord | null;
  /** The workshops to offer, already sorted by the caller. */
  workshops: Array<{ id: string; title: string }>;
  /**
   * True when `workshops` is EVERY workshop (the plan's `allWorkshops`), false when it is only the
   * dated ones the plan's `workshops` list holds — which is what an older server sends, and which
   * silently cannot offer a workshop with no dates. The two cases print different sentences: a
   * picker that is missing rows must say it is missing rows, not imply it is complete.
   */
  workshopsComplete: boolean;
  onClose: () => void;
  /** Called after a successful write, with the sentence to show. The caller re-reads the plan. */
  onDone: (notice: string) => void | Promise<void>;
}) {
  const confirm = useConfirm();
  const [choice, setChoice] = useState("");
  const [busy, setBusy] = useState<null | "file" | "discard">(null);
  const [error, setError] = useState<string | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);

  const rowId = record?.row.id;
  const bucket = record?.bucket;

  // A fresh record is a fresh decision: never carry the previous card's chosen workshop, or its
  // refusal, onto the next one. Keyed on the pair, because two buckets can hold the same id in
  // principle and the choice is only meaningful for one row.
  useEffect(() => {
    setChoice("");
    setError(null);
  }, [bucket, rowId]);

  const destination = record ? destinationFor(record.bucket) : null;
  const working = busy !== null;

  async function file() {
    if (!record || !choice) return;
    setBusy("file");
    setError(null);
    try {
      const result = await fileUnfiledRecord(record.bucket, record.row.id, choice);
      await onDone(filedNotice(result));
    } catch (cause) {
      // Stays IN the dialog. The refusal that matters most here is the 409 — somebody else filed
      // this record while the report was on screen — and it names the workshop it went to, which is
      // information the admin loses if the dialog closes under them.
      setError(readableError(cause, "That record could not be filed just now."));
    } finally {
      setBusy(null);
    }
  }

  async function discard() {
    if (!record) return;
    const ok = await confirm({
      ...deleteConfirm(
        `Delete this ${record.singular} permanently?`,
        // NAMES THE RECORD AND SAYS "permanent" IN WORDS. A destructive prompt that says only
        // "are you sure?" is a prompt about nothing in particular.
        `“${record.row.title}” will be deleted from the repository. This cannot be undone — there is no trash on this record type and nothing to restore it from.`,
        record.bucket === "media"
          ? "The stored file is deleted from storage as well as the record of it."
          : "Anything attached to it — photographs, recordings, documents — is NOT deleted. Those files stay in the repository with nothing pointing at them."
      ),
      confirmLabel: "Delete permanently"
    });
    if (!ok) return;
    setBusy("discard");
    setError(null);
    try {
      const result = await discardUnfiledRecord(record.bucket, record.row.id);
      await onDone(discardedNotice(result));
    } catch (cause) {
      setError(readableError(cause, "That record could not be deleted just now."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      busy={working}
      className="max-w-lg"
      icon={<FolderInput className="h-4 w-4" aria-hidden />}
      title={record ? record.row.title : "Record"}
      description={
        record ? `${record.singular} · not filed under any workshop` : undefined
      }
      // Close, not Cancel: nothing in this dialog is in flight by default and closing loses nothing
      // typed. The destructive act has its own prompt with its own focus rule.
      initialFocusRef={closeRef}
      footer={
        <button type="button" ref={closeRef} className="field-button-secondary" disabled={working} onClick={onClose}>
          Close
        </button>
      }
    >
      {record ? (
        <div className="mt-4 grid gap-4">
          {/* WHY IT IS HERE, repeated. A keyboard user arrives in this dialog without having read
              the card, and the reason is the whole basis of the decision they are about to make. */}
          <div className="rounded-md border border-amber-100 bg-amber-100/40 p-2.5">
            <p className="flex items-center gap-1.5 text-xs font-semibold text-amber-800">
              <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden />
              Left alone by the automatic check
            </p>
            <p className="mt-1 text-xs leading-5 text-ink-700">
              {record.row.reasonCopy ?? "Nothing on the record points at a workshop."}
              {record.row.candidateTitles.length
                ? ` The evidence points at ${record.row.candidateTitles.join(" or ")}.`
                : ""}
            </p>
          </div>

          {error ? (
            <p className="rounded-md bg-error-100 px-3 py-2 text-xs leading-5 text-error-600">{error}</p>
          ) : null}

          {/* 1 — OPEN THE RECORD. A real link, so middle-click and "open in new tab" work and the
                 destination is visible in the status bar before it is pressed. */}
          <div className="grid gap-1.5">
            {destination ? (
              <>
                <Link href={destination.href(record.row.id)} className="field-button-secondary justify-start">
                  <ArrowUpRight className="h-4 w-4 shrink-0" aria-hidden />
                  {destination.label}
                </Link>
                {/* Printed whenever the link cannot reach the record itself. Silence here would be a
                    button that promises one thing and does another — the §13.0 bug exactly. */}
                {destination.opensTheRecord ? null : (
                  <p className="text-[11px] leading-4 text-ink-500">{destination.note}</p>
                )}
              </>
            ) : (
              /* The server sent a bucket this build has never heard of — a client one deploy behind.
                 Saying so beats rendering a link to a page picked by a guess. */
              <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-[11px] leading-4 text-ink-500">
                This build does not know where a “{record.bucket}” record opens, so it cannot offer a
                link to it. The two actions below still work.
              </p>
            )}
          </div>

          {/* 2 — RE-ATTRIBUTE. `FieldBlock`, not `Field`: `Field` is a <label>, and a themed dropdown
                 is a <button>, which a label cannot name (§17). */}
          <FieldBlock
            label="File it under"
            hint={
              /* AN EMPTY PICKER AND A FULL ONE ARE DIFFERENT STATES AND GET DIFFERENT SENTENCES.
                 "Choose a workshop…" over nothing to choose reads as a control that is broken; the
                 honest answer is that the repository holds no workshop yet. */
              workshops.length === 0 ? (
                <p className="text-[11px] leading-4 text-ink-500">
                  There is no workshop in the repository to file this under yet. Create one on this
                  page first, then come back and re-check.
                </p>
              ) : (
                <p className="text-[11px] leading-4 text-ink-500">
                  {workshopsComplete
                    ? "Every workshop is offered, including ones with no dates — a workshop with no dates can never be chosen by the automatic check, which is one reason its records end up on this list."
                    : "This server sent only the workshops that carry dates, so a workshop with no start or end date is NOT in this list. Add dates to it, or open the record itself, to file something there."}{" "}
                  Only fills the empty column: if somebody filed this record while you were reading,
                  this refuses and says who.
                </p>
              )
            }
          >
            <Select
              value={choice}
              onChange={(event) => setChoice(event.target.value)}
              disabled={working || workshops.length === 0}
              // The list is short and constant per deployment, and it is not a list of records the
              // server truncated — leave the filter box off (§17).
              searchable={false}
            >
              <option value="">Choose a workshop…</option>
              {workshops.map((workshop) => (
                <option key={workshop.id} value={workshop.id}>
                  {workshop.title}
                </option>
              ))}
            </Select>
          </FieldBlock>
          <div>
            <button type="button" className="field-button" disabled={working || !choice} onClick={file}>
              {busy === "file" ? "Filing…" : "File this record"}
            </button>
          </div>

          {/* 3 — DISCARD. Its own block, below a rule, so it is never the neighbour of the save. */}
          <div className="border-t border-line-200 pt-4">
            <button type="button" className={DANGER_BUTTON_CLASS} disabled={working} onClick={discard}>
              <Trash2 className="h-4 w-4 shrink-0" aria-hidden />
              {busy === "discard" ? "Deleting…" : "Discard and delete permanently"}
            </button>
            <p className="mt-1.5 text-[11px] leading-4 text-ink-500">
              Permanent. There is no trash for this record type and nothing to restore it from.
            </p>
          </div>
        </div>
      ) : null}
    </FieldDialog>
  );
}
