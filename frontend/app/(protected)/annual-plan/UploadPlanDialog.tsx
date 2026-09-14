"use client";

/**
 * CHOOSE THE WORKBOOK, CHOOSE THE YEAR, AND DECIDE WHAT HAPPENS TO THE ROWS IT DOES NOT MENTION.
 *
 * ── THE CHECKBOX IS THE DANGEROUS CONTROL ON THIS SCREEN ────────────────────────────────────────
 *
 * "Withdraw the workshops missing from this sheet" ticked against a PARTIAL correction sheet of
 * twelve rows takes 288 planned workshops out of a ministry's directory in one press. So it ships
 * UNTICKED, its sentence sits beside it rather than in a tooltip, and the server treats everything
 * that is not an explicit yes as a no — three independent defences for one press, because the press
 * is not obviously destructive and its result is a screen that looks tidy.
 *
 * ── `DropCard` AND NOT THE `.file-trigger` LABEL ────────────────────────────────────────────────
 *
 * `DropCard`'s own docstring criticises that pattern by name: its input is `sr-only` inside a label,
 * so the focus ring is invisible and a keyboard reader cannot see where they are. `DropCard` has a
 * real `<button>` tab stop, a drag depth counter that survives a drag over a child element, and a
 * per-file `validate` — and `accept` is a FILTER ON THE DIALOG, never the rule. `validate` is.
 */

import { useRef, useState } from "react";
import { AlertTriangle, FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { describeApiDetail } from "@/lib/api";

import { uploadAnnualPlan, type PlanUploadReport } from "./annualPlan";

/**
 * Four megabytes, matching `MAX_UPLOAD_BYTES` on the route.
 *
 * STATED HERE AS WELL AS ENFORCED THERE, and that is not a duplicated rule: the server's ceiling is
 * the one that decides, and this one exists so the refusal arrives before a ministry letterhead PDF
 * is pushed up a village connection. If the two ever disagree the server wins, loudly, with a 413
 * that names the limit.
 */
const MAX_BYTES = 4 * 1024 * 1024;

export function UploadPlanDialog({
  open,
  onClose,
  planYear,
  planYearLabel,
  onUploaded
}: {
  open: boolean;
  onClose: () => void;
  /** The year the directory screen is showing. Sent so a sheet cannot be filed under the wrong one. */
  planYear: number;
  planYearLabel: string;
  onUploaded: (report: PlanUploadReport) => void;
}) {
  const [chosen, setChosen] = useState<File | null>(null);
  const [withdrawAbsent, setWithdrawAbsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const chooseRef = useRef<HTMLButtonElement | null>(null);

  function reset() {
    setChosen(null);
    setWithdrawAbsent(false);
    setError(null);
  }

  async function submit() {
    if (!chosen || busy) return;
    setBusy(true);
    setError(null);
    try {
      const report = await uploadAnnualPlan(chosen, { planYear, withdrawAbsent });
      reset();
      onUploaded(report);
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
      title={`Upload the ${planYearLabel} plan`}
      description="The whole sheet, every time. Rows already in the plan are corrected, new rows are added, and uploading the same sheet twice changes nothing."
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
            {busy ? "Reading the sheet…" : "Upload"}
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
        label="The plan workbook"
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
              ? `"${file.name}" is larger than 4 MB. A directory of three hundred workshops is well under that — check you have chosen the plan and not a scanned document.`
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

      <label className="mt-4 flex items-start gap-3 rounded-md border border-line-200 bg-surface-50 p-3">
        <input
          type="checkbox"
          className="mt-1 h-4 w-4 shrink-0 accent-purple-700"
          checked={withdrawAbsent}
          disabled={busy}
          onChange={(event) => setWithdrawAbsent(event.currentTarget.checked)}
        />
        <span className="text-sm leading-6 text-ink-700">
          <span className="font-medium text-ink-900">
            Withdraw the workshops that are in this year&apos;s plan and not in this sheet.
          </span>{" "}
          Leave this unticked if the sheet is a partial correction. A workshop that has already been
          opened is never withdrawn, whatever this says.
        </span>
      </label>

      {withdrawAbsent ? (
        <p className="mt-2 flex items-start gap-2 text-sm leading-6 text-amber-800">
          <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
          Every planned row this sheet does not mention will be marked withdrawn. Nothing is deleted
          — a withdrawn row comes back the moment it appears in a later sheet.
        </p>
      ) : null}
    </FieldDialog>
  );
}
