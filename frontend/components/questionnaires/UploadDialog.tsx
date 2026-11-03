"use client";

import { useId, useRef, useState } from "react";
import { FileSpreadsheet, Upload } from "lucide-react";

import { attachHold, HeldPostNotice, useHeldPostRefusal, writesHeld } from "@/components/designworkshop/HeldPostNotice";
import { FieldDialog } from "@/components/dialogs";
import { Field, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import {
  QUESTIONNAIRE_KINDS,
  QUESTIONNAIRE_KIND_LABELS,
  reuploadQuestionnaire,
  uploadQuestionnaire,
  type QFormKind,
  type QFormUploadResult
} from "@/lib/questionnaireForms";
import { designWorkshopOptions, NO_DESIGN_WORKSHOP, type DesignWorkshopRow } from "@/lib/workshopOptions";

export type WorkshopChoice = DesignWorkshopRow;

export function UploadDialog({
  open,
  onClose,
  onUploaded,
  questionnaireId,
  workshops,
  workshopsNotice
}: {
  open: boolean;
  onClose: () => void;
  onUploaded: (result: QFormUploadResult) => void;
  questionnaireId?: string;
  workshops?: WorkshopChoice[];
  workshopsNotice?: string;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [designWorkshopId, setDesignWorkshopId] = useState("");
  const [kind, setKind] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const editing = Boolean(questionnaireId);
  const workshopHold = useHeldPostRefusal(editing ? null : designWorkshopId || null);
  const hold = attachHold(workshopHold, "this questionnaire");
  const holdNoticeId = useId();

  function reset() {
    setFile(null);
    setTitle("");
    setDesignWorkshopId("");
    setError(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function submit() {
    if (!file || writesHeld(hold)) return;
    setBusy(true);
    setError(null);
    try {
      const result = questionnaireId
        ? await reuploadQuestionnaire(questionnaireId, file, { title: title.trim() || undefined })
        : await uploadQuestionnaire(file, {
            title: title.trim() || undefined,
            designWorkshopId: designWorkshopId || undefined,
            kind: (kind || undefined) as QFormKind | undefined
          });
      reset();
      onUploaded(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to read that workbook");
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={open}
      onClose={close}
      busy={busy}
      title={editing ? "Upload an edited copy of this questionnaire" : "Upload a filled-in pro-forma"}
      description={
        editing
          ? "Your edited workbook. Rows are matched by Question ID."
          : "The .xlsx pro-forma with your questions, with or without answers."
      }
      icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button
            type="button"
            className="field-button"
            onClick={submit}
            disabled={!file || busy || writesHeld(hold)}
            aria-describedby={typeof hold === "string" ? holdNoticeId : undefined}
          >
            {busy ? "Reading the workbook…" : editing ? "Upload and apply" : "Upload questionnaire"}
          </button>
        </>
      }
    >
      <div className="grid gap-4">
        {error ? (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
        ) : null}

        {editing ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            Reworded questions that have answers are added as new questions; nothing is overwritten.
          </p>
        ) : (
          <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            <p className="font-medium text-ink-900">You can upload:</p>
            <ul className="mt-1 grid gap-1">
              <li>
                <span className="font-medium text-ink-900">The pro-forma</span> — questions and any answers are imported.
              </li>
              <li>
                <span className="font-medium text-ink-900">A shared question set</span> — becomes your own questionnaire.
              </li>
              <li>
                <span className="font-medium text-ink-900">A downloaded workbook</span> — questions only; answers are already here.
              </li>
            </ul>
          </div>
        )}

        <FieldBlock label="Workbook" required>
          <label className="file-trigger">
            <Upload className="h-4 w-4" aria-hidden />
            {file ? "Choose a different file" : "Choose an .xlsx file"}
            <input
              ref={fileInputRef}
              type="file"
              className="sr-only"
              accept=".xlsx,.xlsm,.xltx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              onChange={(event) => {
                setFile(event.target.files?.[0] ?? null);
                setError(null);
              }}
            />
          </label>
          <p className="mt-1 text-xs leading-5 text-ink-500">
            {file ? file.name : ".xlsx only."}
          </p>
        </FieldBlock>

        <Field label="Title">
          <TextInput
            value={title}
            maxLength={220}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={editing ? "Keep current title" : "Use the title in the workbook"}
          />
        </Field>

        {editing ? null : (
          <FieldBlock
            label="Attach to a design workshop"
            hint={
              <p className="mt-1 text-xs leading-5 text-ink-500" aria-live="polite">
                {workshopsNotice ||
                  "Not listed? Leave it unattached and attach it later."}
              </p>
            }
          >
            <Dropdown
              value={designWorkshopId}
              onChange={setDesignWorkshopId}
              options={
                designWorkshopOptions(
                  { kind: "ok", rows: workshops ?? [], total: (workshops ?? []).length },
                  { group: false, offPage: { mode: "refuse" } }
                ).options
              }
              noneLabel={NO_DESIGN_WORKSHOP}
              emptyLabel="No design workshop is listed here."
              ariaLabel="Attach to a design workshop"
              searchable
              disabled={!workshops?.length}
            />
          </FieldBlock>
        )}
        {!editing && (
          <FieldBlock
            label="Kind"
            hint={
              <p className="mt-1 text-xs leading-5 text-ink-500">
                Where the report files its answers.
              </p>
            }
          >
            <Dropdown
              value={kind}
              onChange={setKind}
              options={QUESTIONNAIRE_KINDS.map((value) => ({
                value,
                label: QUESTIONNAIRE_KIND_LABELS[value]
              }))}
              noneLabel="Not stated"
              ariaLabel="Kind"
            />
          </FieldBlock>
        )}
        <HeldPostNotice refusal={hold} id={holdNoticeId} className="" sayPending />
      </div>
    </FieldDialog>
  );
}
