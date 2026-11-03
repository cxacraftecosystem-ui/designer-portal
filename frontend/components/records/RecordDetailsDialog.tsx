"use client";

import type { ReactNode } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type RecordDetail = readonly [label: string, value: ReactNode | null];

export function RecordDetailsDialog({
  open,
  onClose,
  title,
  description,
  details,
  onUpdate,
  onDelete,
  children
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  details: RecordDetail[];
  onUpdate?: () => void;
  onDelete?: () => void;
  children?: ReactNode;
}) {
  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      footer={
        <ActionGroup align="end">
          <button type="button" className="field-button-secondary" onClick={onClose}>
            Close
          </button>
          {onUpdate ? (
            <button type="button" className="field-button" onClick={onUpdate}>
              Update
            </button>
          ) : null}
          {onDelete ? (
            <button type="button" data-danger className="field-danger" onClick={onDelete}>
              Delete
            </button>
          ) : null}
        </ActionGroup>
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
