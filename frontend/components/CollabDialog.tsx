"use client";

import { CollabPanel } from "@/components/CollabPanel";
import { FieldDialog } from "@/components/dialogs/FieldDialog";

/**
 * The modal every list page opens from its row "Discuss" button: comments plus (for the record's
 * owner and admins) its edit history, for one record. Rendered only while `recordId` is set, so a
 * page can pass its `collabId` state straight through.
 *
 * Kept here rather than repeated per page so Discuss looks and behaves the same on artisans,
 * products, tools, workshops and processes.
 *
 * BUILT ON `FieldDialog`, NOT ON A `fixed inset-0` DIV. The hand-rolled overlay this replaced had no
 * `role="dialog"`, no focus trap, no Escape, no focus restore and no scroll lock: a reviewer opening
 * Discuss from a row of the artisan list left focus on the button behind the dimmer, so a screen
 * reader carried on reading the table underneath, a keyboard user had to Tab through every remaining
 * row to reach Close, and Escape did nothing.
 *
 * `dismissOnBackdrop={false}` is not caution for its own sake — `CollabPanel` holds an unsent comment
 * in its own state, and a stray click on the dim area threw a half-typed comment away with no prompt.
 * The X and Escape remain, and both are deliberate acts.
 *
 * `CollabPanel` is mounted only while the dialog is open: it fetches comments and revisions on mount,
 * and a closed dialog must not put two requests per list page on the wire.
 */
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
