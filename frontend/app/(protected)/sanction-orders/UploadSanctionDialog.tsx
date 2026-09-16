"use client";

/**
 * CHOOSE THE WORKBOOK. **AND THAT IS ALL THIS DIALOG DOES.**
 *
 * It is `annual-plan/UploadPlanDialog.tsx` with one control removed and one added, deliberately, so
 * that an officer who has used the annual plan's uploader recognises this one: the same `DropCard`,
 * the same 4 MB client-side bound with the same note about which side really decides, the same
 * `describeApiDetail` on the error path.
 *
 * ── WHAT IS NOT HERE, AND WHY EACH ABSENCE IS A DECISION ────────────────────────────────────────
 *
 * **NO CHECKBOX.** The annual plan's dangerous control is "withdraw the workshops missing from this
 * sheet", and it is dangerous because that upload is DESTRUCTIVE against rows the sheet does not
 * mention. **There is no delete on the sanction register at all** — an order is the record that
 * money was authorised — so there is nothing here for such a flag to mean, and the annual plan's
 * whole `filterNote` machinery (a filtered export re-uploaded destructively) does not transfer.
 * Saying so is the point: the obvious way to "finish" this dialog is to copy that checkbox across.
 *
 * **NO SCALAR OF ANY KIND ON THE BODY.** The annual plan's upload carries `planYear` and
 * `withdrawAbsent` as `Form()` fields, each typed `str | None` and hand-validated because a bare
 * default is read off the QUERY STRING by FastAPI — a trap its own docstring sets out. A sanction
 * sheet carries its date on every row, so this route has nothing to put there, and the absence is
 * worth writing down because "add a scalar" is the change that walks straight into that trap.
 *
 * ── AND WHAT IS HERE THAT THE ANNUAL PLAN'S HAS NO NEED OF ──────────────────────────────────────
 *
 * **THE SENTENCE SAYING NOTHING IS RECORDED YET.** This upload is the FIRST of two POSTs: it reads
 * the sheet, reconciles it against the rosters and writes nothing at all. An officer who has used
 * the annual plan's uploader has learned that pressing Upload commits — and pressing it here, on the
 * screen that mints accounts and admits people to the platform, is exactly where that assumption
 * would cost the most. So the dialog says what will happen next, before the press rather than after.
 *
 * `DropCard` AND NOT THE `.file-trigger` LABEL, for the reason its own docstring gives: that pattern
 * puts an `sr-only` input inside a label, so the focus ring is invisible and a keyboard reader
 * cannot see where they are. `DropCard` has a real `<button>` tab stop, a drag depth counter that
 * survives a drag over a child element, and a per-file `validate` — and **`accept` is a filter on
 * the dialog, never the rule. `validate` is**, because a dragged file never passes through `accept`.
 */

import { useRef, useState } from "react";
import { FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { describeApiDetail } from "@/lib/api";
import { uploadSanctionOrders, type SanctionImportPreview } from "@/lib/sanctionOrders";

/**
 * Four megabytes, matching `MAX_UPLOAD_BYTES` on the route.
 *
 * STATED HERE AS WELL AS ENFORCED THERE, and that is not a duplicated rule: the server's ceiling is
 * the one that decides, and this one exists so the refusal arrives before a ministry letterhead PDF
 * is pushed up a village connection. If the two ever disagree the server wins, loudly, with a 413
 * that names the limit. A sheet of two hundred orders with six columns is tens of kilobytes, so
 * anything near this is not the sheet.
 */
const MAX_BYTES = 4 * 1024 * 1024;

export function UploadSanctionDialog({
  open,
  onClose,
  onPreview
}: {
  open: boolean;
  onClose: () => void;
  /** The reconciled sheet. Nothing has been recorded when this fires — see the module docstring. */
  onPreview: (preview: SanctionImportPreview) => void;
}) {
  const [chosen, setChosen] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const chooseRef = useRef<HTMLButtonElement | null>(null);

  function reset() {
    setChosen(null);
    setError(null);
  }

  async function submit() {
    if (!chosen || busy) return;
    setBusy(true);
    setError(null);
    try {
      const preview = await uploadSanctionOrders(chosen);
      reset();
      onPreview(preview);
    } catch (caught) {
      // `describeApiDetail` because FastAPI's 422 detail is a LIST that stringifies to
      // "[object Object]" — the reason that helper exists, and the reason two private
      // re-implementations of it already had to be deleted.
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "The upload could not be read."
        )
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={open}
      onClose={() => {
        if (busy) return;
        reset();
        onClose();
      }}
      busy={busy}
      title="Upload a sheet of sanction orders"
      description="Nothing is recorded yet. The sheet is read and checked against the designer roster first, and you are asked about anything that does not add up."
      icon={<FileSpreadsheet className="h-4 w-4" aria-hidden />}
      className="max-w-xl"
      initialFocusRef={chooseRef}
      footer={
        <>
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              reset();
              onClose();
            }}
            disabled={busy}
          >
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={!chosen || busy}>
            <Upload className="h-4 w-4" aria-hidden />
            {busy ? "Reading the sheet…" : "Read the sheet"}
          </button>
        </>
      }
    >
      {error ? (
        <p className="mb-3 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600">
          {error}
        </p>
      ) : null}

      <DropCard
        label="The sanction order sheet"
        buttonLabel="Choose the .xlsx file"
        accept=".xlsx,.xlsm,.xltx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        acceptSentence="An Excel workbook (.xlsx) — the pro-forma, filled in."
        buttonRef={chooseRef}
        disabled={busy}
        // THE RULE, as against `accept` above, which is only the dialog's filter. A file dragged in
        // never passes through that filter at all.
        validate={(file) =>
          /\.(xlsx|xlsm|xltx)$/i.test(file.name)
            ? file.size > MAX_BYTES
              ? `"${file.name}" is larger than 4 MB. A sheet of two hundred sanction orders is a few dozen kilobytes — check you have chosen the sheet and not a scanned copy of the orders.`
              : null
            : `"${file.name}" is not an Excel workbook. Use File > Save As in Excel and choose "Excel Workbook (.xlsx)".`
        }
        onFiles={(files) => {
          setChosen(files[0] ?? null);
          setError(null);
        }}
      >
        {chosen ? (
          <p className="mt-2 text-sm text-ink-700">
            Chosen: <span className="font-medium text-ink-900">{chosen.name}</span>
          </p>
        ) : null}
      </DropCard>

      {/*
        THE TWO-STEP SHAPE, SAID BEFORE THE PRESS. The annual plan's uploader commits on Upload, and
        an officer who has used it will assume this one does too — on the screen that mints accounts
        and admits people to the platform, which is where that assumption costs the most.
      */}
      <div className="mt-4 rounded-md border border-line-200 bg-surface-50 p-3 text-sm leading-6 text-ink-700">
        <p className="font-medium text-ink-900">Nothing is recorded by this step.</p>
        <p className="mt-1">
          The sheet is read, every address is checked against the designer roster and the platform
          allow-list, and you are shown three lists: what will be recorded, what needs a decision from
          you, and what cannot be recorded whatever you say. Orders are only written when you confirm.
        </p>
        <p className="mt-1">
          One order per row. The designer names and the email addresses each go in one cell,{" "}
          <span className="font-medium text-ink-900">separated by commas and in the same order</span>{" "}
          — the first name goes with the first address, and the first designer is the one whose name
          reaches the report.
        </p>
      </div>
    </FieldDialog>
  );
}
