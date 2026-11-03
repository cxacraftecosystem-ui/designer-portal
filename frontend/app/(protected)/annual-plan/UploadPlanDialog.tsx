"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useRef, useState } from "react";
import { AlertTriangle, FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { describeApiDetail } from "@/lib/api";

import { uploadAnnualPlan, type PlanUploadReport } from "./annualPlan";

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
  planYear: number | null;
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
      const report = await uploadAnnualPlan(chosen, { planYear: planYear ?? undefined, withdrawAbsent });
      reset();
      onUploaded(report);
    } catch (caught) {
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
      title={planYear == null ? "Upload the annual plan" : `Upload the ${planYearLabel} plan`}
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
        <p className="mb-3 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-red-800">
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

      <div className="mt-4 rounded-md border border-line-200 bg-surface-50 px-3 py-1">
        <Toggle
          label="Withdraw the workshops that are in this year's plan and not in this sheet."
          description="Leave this off if the sheet is a partial correction. A workshop that has already been opened is never withdrawn, whatever this says."
          checked={withdrawAbsent}
          disabled={busy}
          onChange={setWithdrawAbsent}
        />
      </div>

      {withdrawAbsent ? (
        <p className="mt-2 flex items-start gap-2 text-sm leading-6 text-amber-800 dark:text-amber-100">
          <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
          Entries missing from this sheet will be marked withdrawn — not deleted. They return if a
          later sheet lists them.
        </p>
      ) : null}
    </FieldDialog>
  );
}
