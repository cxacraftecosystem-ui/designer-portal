"use client";

import type { ReactNode } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";

/** One label and its value, read-only. A row whose value is null is left out rather than printed blank. */
export type RecordDetail = readonly [label: string, value: ReactNode | null];

/**
 * The read-only view of a record whose whole record is its row — an account, a roster entry, a
 * sanction order — opened by a press anywhere on that row (owner ruling, 2026-10-16: a record opens
 * to be read; changing it is the separate "Update"). A dialog and not a route, because there is
 * nothing to show beyond what the row carries; what the dialog adds is the room to read it without
 * a row of action buttons under the pointer.
 *
 * `onUpdate` draws the Update button. The caller passes it only where the row's own Update would be
 * drawn — never a wider decision than the row makes.
 */
export function RecordDetailsDialog({
  open,
  onClose,
  title,
  description,
  details,
  onUpdate,
  children
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  details: RecordDetail[];
  onUpdate?: () => void;
  children?: ReactNode;
}) {
  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      footer={
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={onClose}>
            Close
          </button>
          {onUpdate ? (
            <button type="button" className="field-button" onClick={onUpdate}>
              Update
            </button>
          ) : null}
        </div>
      }
    >
      <dl className="mt-4 grid gap-2 text-sm">
        {details
          .filter(([, value]) => value !== null && value !== undefined && value !== "")
          .map(([label, value]) => (
            <div key={label} className="grid gap-0.5 sm:grid-cols-[13rem_minmax(0,1fr)] sm:gap-3">
              <dt className="font-semibold text-ink-900">{label}</dt>
              <dd className="whitespace-pre-line break-words text-ink-700">{value}</dd>
            </div>
          ))}
      </dl>
      {children}
    </FieldDialog>
  );
}
