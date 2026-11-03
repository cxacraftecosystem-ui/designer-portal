"use client";

import { useRef, useState } from "react";
import { FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { describeApiDetail } from "@/lib/api";
import { uploadSanctionOrders, type SanctionImportPreview } from "@/lib/sanctionOrders";

const MAX_BYTES = 4 * 1024 * 1024;

export function UploadSanctionDialog({
  open,
  onClose,
  onPreview
}: {
  open: boolean;
  onClose: () => void;
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
        <p className="mb-3 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-red-800">
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
        validate={(file) =>
          /\.(xlsx|xlsm|xltx)$/i.test(file.name)
            ? file.size > MAX_BYTES
              ? `"${file.name}" is over 4 MB. Choose the sheet, not a scan.`
              : null
            : `"${file.name}" isn't an Excel workbook. Save it as .xlsx.`
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
