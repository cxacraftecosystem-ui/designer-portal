"use client";

/**
 * The Aadhaar chip on a row of the Users screen, and — for an administrator looking at somebody
 * else's account — the decision on a name that did not match.
 *
 * Draws NOTHING while the server has both Aadhaar features off, and nothing for an account that has
 * never verified. The chip carries a word as well as a colour, like every status on that screen.
 */

import { useState } from "react";

import { AadhaarStatusChip } from "@/components/aadhaar/AadhaarVerifyPanel";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import {
  aadhaarEnabled,
  aadhaarErrorMessage,
  aadhaarFeaturesOf,
  accountColumnsOf,
  decideAadhaar,
  METHOD_LABEL
} from "@/lib/aadhaar";
import { isAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

export function AadhaarUserBadge({
  account,
  viewer,
  onDecided
}: {
  account: User;
  viewer: User | null | undefined;
  onDecided: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!aadhaarEnabled(aadhaarFeaturesOf(viewer))) return null;
  const columns = accountColumnsOf(account);
  if (!columns.aadhaarVerifyStatus) return null;
  const mayDecide =
    columns.aadhaarVerifyStatus === "NAME_MISMATCH" && isAdmin(viewer) && viewer?.id !== account.id;

  async function decide(accept: boolean) {
    setBusy(true);
    setError(null);
    try {
      await decideAadhaar({ kind: "user", id: account.id }, accept);
      setOpen(false);
      onDecided();
    } catch (err) {
      setError(aadhaarErrorMessage(err, "The decision could not be saved. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <span className="mt-1 flex flex-wrap items-center gap-1.5">
      <AadhaarStatusChip summary={{ status: columns.aadhaarVerifyStatus }} />
      {mayDecide ? (
        <button type="button" className="text-xs font-medium text-purple-700 dark:text-purple-300 underline" onClick={() => setOpen(true)}>
          Review
        </button>
      ) : null}
      {mayDecide ? (
        <FieldDialog
          open={open}
          onClose={() => setOpen(false)}
          busy={busy}
          tone="warning"
          title="Aadhaar name differs"
          description="UIDAI's signature on the document checked out, but the names do not match closely enough to accept on their own. Decide whether they are the same person."
          footer={
            <>
              <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setOpen(false)}>
                Later
              </button>
              <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void decide(false)}>
                Do not accept
              </button>
              <button type="button" className="field-button" disabled={busy} onClick={() => void decide(true)}>
                Accept as the same person
              </button>
            </>
          }
        >
          <dl className="grid gap-2 text-sm">
            <div>
              <dt className="field-label">Name on the account</dt>
              <dd className="text-ink-900">{account.name}</dd>
            </div>
            <div>
              <dt className="field-label">Name as UIDAI holds it</dt>
              <dd className="text-ink-900">{columns.aadhaarVerifyName ?? "UIDAI returned no name for this method"}</dd>
            </div>
            <div>
              <dt className="field-label">How</dt>
              <dd className="text-ink-700">
                {columns.aadhaarVerifyMethod ? METHOD_LABEL[columns.aadhaarVerifyMethod] : "—"}
                {columns.aadhaarVerifyLast4 ? ` · Aadhaar ending ${columns.aadhaarVerifyLast4}` : ""}
              </dd>
            </div>
          </dl>
          {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
        </FieldDialog>
      ) : null}
    </span>
  );
}
