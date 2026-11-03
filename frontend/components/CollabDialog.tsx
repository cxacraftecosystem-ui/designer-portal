"use client";

import { CollabPanel } from "@/components/CollabPanel";
import { FieldDialog } from "@/components/dialogs/FieldDialog";

export function CollabDialog({
  recordType,
  recordId,
  onClose
}: {
  recordType: string;
  recordId: string | null;
  onClose: () => void;
}) {
  return (
    <FieldDialog
      open={Boolean(recordId)}
      onClose={onClose}
      title="Comments & edit history"
      className="max-w-lg"
      dismissOnBackdrop={false}
    >
      {recordId ? (
        <div className="mt-4">
          <CollabPanel recordType={recordType} recordId={recordId} />
        </div>
      ) : null}
    </FieldDialog>
  );
}
