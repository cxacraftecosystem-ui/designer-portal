"use client";

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

export type UnfiledRow = {
  id: string;
  title: string;
  reasonCopy: string | null;
  candidateTitles: string[];
};

export type UnfiledRecord = {
  bucket: string;
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
  workshops: Array<{ id: string; title: string }>;
  workshopsComplete: boolean;
  onClose: () => void;
  onDone: (notice: string) => void | Promise<void>;
}) {
  const confirm = useConfirm();
  const [choice, setChoice] = useState("");
  const [busy, setBusy] = useState<null | "file" | "discard">(null);
  const [error, setError] = useState<string | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);

  const rowId = record?.row.id;
  const bucket = record?.bucket;

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
        `“${record.row.title}” will be deleted permanently. This cannot be undone.`,
        record.bucket === "media"
          ? "The stored file is deleted from storage as well as the record of it."
          : "Anything attached to it — photographs, recordings, documents — is kept, under Miscellaneous Media."
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
      initialFocusRef={closeRef}
      footer={
        <button type="button" ref={closeRef} className="field-button-secondary" disabled={working} onClick={onClose}>
          Close
        </button>
      }
    >
      {record ? (
        <div className="mt-4 grid gap-4">
          <div className="rounded-md border border-amber-100 bg-amber-100/40 p-2.5">
            <p className="flex items-center gap-1.5 text-xs font-semibold text-amber-800 dark:text-amber-100">
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
            <p className="rounded-md bg-error-100 px-3 py-2 text-xs leading-5 text-red-800">{error}</p>
          ) : null}

          <div className="grid gap-1.5">
            {destination ? (
              <>
                <Link href={destination.href(record.row.id)} className="field-button-secondary justify-start">
                  <ArrowUpRight className="h-4 w-4 shrink-0" aria-hidden />
                  {destination.label}
                </Link>
                {destination.opensTheRecord ? null : (
                  <p className="text-[11px] leading-4 text-ink-500">{destination.note}</p>
                )}
              </>
            ) : (
              <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-[11px] leading-4 text-ink-500">
                This record cannot be opened from here. The two actions below still work.
              </p>
            )}
          </div>

          <FieldBlock
            label="File it under"
            hint={
              workshops.length === 0 ? (
                <p className="text-[11px] leading-4 text-ink-500">
                  There are no workshops to file this under. Create one first, then come back and check
                  again.
                </p>
              ) : (
                <p className="text-[11px] leading-4 text-ink-500">
                  {workshopsComplete
                    ? "Every workshop is offered, including ones without dates."
                    : "Only workshops with dates are listed here. To file something under a workshop without dates, add dates to it, or open the record itself."}{" "}
                  Only fills an empty workshop: if somebody filed this record in the meantime, you are told
                  who.
                </p>
              )
            }
          >
            <Select
              value={choice}
              onChange={(event) => setChoice(event.target.value)}
              disabled={working || workshops.length === 0}
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

          <div className="border-t border-line-200 pt-4">
            <button type="button" className={DANGER_BUTTON_CLASS} disabled={working} onClick={discard}>
              <Trash2 className="h-4 w-4 shrink-0" aria-hidden />
              {busy === "discard" ? "Deleting…" : "Discard and delete permanently"}
            </button>
            <p className="mt-1.5 text-[11px] leading-4 text-ink-500">
              Permanent. This cannot be undone.
            </p>
          </div>
        </div>
      ) : null}
    </FieldDialog>
  );
}
