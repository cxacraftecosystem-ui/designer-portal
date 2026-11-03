"use client";

import { FilePenLine } from "lucide-react";
import { useRef } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";

export function UnsavedChangesDialog({
  open,
  saving,
  onKeepEditing,
  onDiscard,
  onSave
}: {
  open: boolean;
  saving?: boolean;
  onKeepEditing: () => void;
  onDiscard: () => void;
  onSave: () => void;
}) {
  const keepEditingRef = useRef<HTMLButtonElement | null>(null);

  return (
    <FieldDialog
      open={open}
      onClose={onKeepEditing}
      role="alertdialog"
      tone="warning"
      icon={<FilePenLine className="h-4 w-4" aria-hidden />}
      busy={saving}
      showClose={false}
      dismissOnBackdrop
      initialFocusRef={keepEditingRef}
      title="Unsaved changes"
      description="You have unsaved changes, including any recordings or media you just captured. Save them before leaving, or discard them?"
      footer={
        <>
          <button
            type="button"
            ref={keepEditingRef}
            className="field-button-secondary"
            disabled={saving}
            onClick={onKeepEditing}
          >
            Keep editing
          </button>
          <button type="button" data-danger className="field-danger" disabled={saving} onClick={onDiscard}>
            Discard
          </button>
          <button type="button" className="field-button" disabled={saving} onClick={onSave}>
            {saving ? "Saving…" : "Save"}
          </button>
        </>
      }
    />
  );
}
