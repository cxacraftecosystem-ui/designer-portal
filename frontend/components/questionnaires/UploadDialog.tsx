"use client";

/**
 * "Upload a filled-in pro-forma" — the door a designer's own questionnaire comes in through.
 *
 * ONE COMPONENT FOR BOTH UPLOAD ENDPOINTS, because they are the same act from the designer's side:
 * `POST /questionnaires/upload` makes a new questionnaire, `POST /questionnaires/{id}/upload` edits
 * an existing one, and which of the two runs is decided by `questionnaireId` being present. Two
 * dialogs would drift, and the half that drifted would be the re-upload — the rarer path, and the
 * one whose mistakes cost recorded answers.
 *
 * WHAT DIFFERS BETWEEN THE TWO, and it is only ever copy: a re-upload runs the edit-after-answers
 * rule, so this says so before the file is chosen rather than reporting it afterwards. A designer
 * about to re-upload needs to know that rewording an answered question will ADD a question rather
 * than change one, because the alternative is discovering it in the report.
 *
 * THE WORKSHOP DROPDOWN IS ONLY ON THE CREATE PATH. `POST /questionnaires/{id}/upload` accepts a
 * title and nothing else — attaching an existing questionnaire to a workshop is a PATCH, and it
 * lives on the detail page. Offering the picker here would build a control whose value the request
 * cannot carry.
 */

import { useRef, useState } from "react";
import { FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs";
import { Field, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { reuploadQuestionnaire, uploadQuestionnaire, type QFormUploadResult } from "@/lib/questionnaireForms";

export type WorkshopChoice = { id: string; title: string };

export function UploadDialog({
  open,
  onClose,
  onUploaded,
  questionnaireId,
  workshops
}: {
  open: boolean;
  onClose: () => void;
  onUploaded: (result: QFormUploadResult) => void;
  /** Present = re-upload over this questionnaire; absent = create a new one. */
  questionnaireId?: string;
  /** Design workshops this questionnaire may be attached to. Ignored on the re-upload path. */
  workshops?: WorkshopChoice[];
}) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [designWorkshopId, setDesignWorkshopId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const editing = Boolean(questionnaireId);

  function reset() {
    setFile(null);
    setTitle("");
    setDesignWorkshopId("");
    setError(null);
    // The DOM input holds the chosen file independently of React state, so clearing only the state
    // leaves the browser reporting the previous file name and re-choosing THE SAME file fires no
    // change event at all — the second upload of a corrected workbook would silently do nothing.
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function submit() {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const result = questionnaireId
        ? await reuploadQuestionnaire(questionnaireId, file, { title: title.trim() || undefined })
        : await uploadQuestionnaire(file, {
            title: title.trim() || undefined,
            designWorkshopId: designWorkshopId || undefined
          });
      reset();
      onUploaded(result);
    } catch (err) {
      // Kept in the dialog rather than raised to the page: the file the designer picked is still
      // chosen here, so the fix (pick the other file, save it as .xlsx) is one click from the
      // message. A 415, a 413 and the "that workbook came from a different questionnaire" 409 all
      // carry a sentence written to be shown as-is.
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
          ? "The workbook you downloaded from this questionnaire, with your edits. Questions are matched by the Question ID column, so rows you changed are edited rather than added again."
          : "The .xlsx pro-forma with your questions typed into it. It may already contain answers, or none at all — both work."
      }
      icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={!file || busy}>
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
          // Said BEFORE the file is chosen, not reported afterwards. A designer who rewords an
          // answered question is going to get a new question next to the old one; discovering that
          // in the change report, after the fact, reads as the import having duplicated their form.
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            Questions that already have answers keep their wording. If you have reworded one, the original and its answers
            are kept and your new wording is added as a new question — nothing is overwritten and nothing is lost. The
            report afterwards names every question this happened to.
          </p>
        ) : (
          // THE THREE FILES THIS DOOR ACCEPTS, named before one is chosen, because the third of them
          // behaves in a way nobody would guess and finding out afterwards reads as the import
          // having lost data. It has not: a workbook that came out of the app carries answers that
          // are ALREADY recorded here under the names of the people who recorded them, and writing
          // them again would duplicate somebody's fieldwork with a new author stamped on the copy.
          <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            <p className="font-medium text-ink-900">Three files work here, and one of them behaves differently.</p>
            <ul className="mt-1 grid gap-1">
              <li>
                <span className="font-medium text-ink-900">The blank pro-forma</span>, with your questions typed into it —
                and your own answers too, if you ran the interviews on paper. Both are imported.
              </li>
              <li>
                <span className="font-medium text-ink-900">A question set another designer sent you</span> — you get their
                questions in a new questionnaire of your own, ready for your own fieldwork.
              </li>
              <li>
                <span className="font-medium text-ink-900">A workbook downloaded out of the app</span> — its questions are
                imported and its answers are not. They are already recorded here, under the names of the people who
                recorded them.
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
            {file ? file.name : "Excel workbooks only. Save As → Excel Workbook (.xlsx) if yours is an older .xls."}
          </p>
        </FieldBlock>

        <Field label="Title">
          <TextInput
            value={title}
            maxLength={220}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={editing ? "Leave blank to keep the current title" : "Leave blank to use the title on the Details sheet"}
          />
        </Field>

        {!editing && workshops?.length ? (
          // FieldBlock rather than Field: `Field` is a <label>, and a <label> wrapped round a themed
          // dropdown forwards a stray click into the menu and slams it shut after one pick.
          <FieldBlock label="Attach to a design workshop">
            <Dropdown
              value={designWorkshopId}
              onChange={setDesignWorkshopId}
              options={[
                { value: "", label: "Not attached to a workshop" },
                ...workshops.map((workshop) => ({ value: workshop.id, label: workshop.title }))
              ]}
              ariaLabel="Attach to a design workshop"
              searchable
            />
          </FieldBlock>
        ) : null}
      </div>
    </FieldDialog>
  );
}
