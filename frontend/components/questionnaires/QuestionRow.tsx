"use client";

import { useState } from "react";
import { Archive, History, Lock } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { rowAction } from "@/components/RowActions";
import {
  patchQuestion,
  removeQuestion,
  type QForm,
  type QFormQuestion
} from "@/lib/questionnaireForms";
import { ActionGroup } from "@/components/ui/ActionGroup";

export function QuestionRow({
  questionnaireId,
  question,
  ordinal,
  mayEdit,
  mayRemove = false,
  onChanged,
  onError
}: {
  questionnaireId: string;
  question: QFormQuestion;
  ordinal: number;
  mayEdit: boolean;
  mayRemove?: boolean;
  onChanged: (form: QForm, message?: string) => void;
  onError: (message: string) => void;
}) {
  const confirm = useConfirm();
  const [editing, setEditing] = useState(false);
  const [prompt, setPrompt] = useState(question.prompt);
  const [helpText, setHelpText] = useState(question.helpText ?? "");
  const [busy, setBusy] = useState(false);

  const retired = !question.isActive;

  async function save() {
    const wording = prompt.trim();
    if (!wording) return;
    setBusy(true);
    try {
      const result = await patchQuestion(questionnaireId, question.id, {
        prompt: wording,
        helpText: helpText.trim() || null
      });
      setEditing(false);
      onChanged(result.questionnaire, result.action === "superseded" ? result.detail : undefined);
    } catch (err) {
      onError(err instanceof Error ? err.message : "Unable to save that question");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    const ok = await confirm(
      question.hasAnswers
        ? {
            title: "Retire this question?",
            body: `"${question.prompt}" stops being asked. It has answers recorded against it, so it cannot be deleted — those answers stay in the record and in every export.`,
            confirmLabel: "Retire question",
            tone: "warning"
          }
        : deleteConfirm(
            "Delete this question?",
            `"${question.prompt}" is removed from the questionnaire.`,
            "Nobody has answered it, so nothing is lost."
          )
    );
    if (!ok) return;
    setBusy(true);
    try {
      const result = await removeQuestion(questionnaireId, question.id);
      onChanged(result.questionnaire, result.action === "retired" ? result.detail : undefined);
    } catch (err) {
      onError(err instanceof Error ? err.message : "Unable to remove that question");
    } finally {
      setBusy(false);
    }
  }

  async function toggleRequired() {
    setBusy(true);
    try {
      const result = await patchQuestion(questionnaireId, question.id, { isRequired: !question.isRequired });
      onChanged(result.questionnaire);
    } catch (err) {
      onError(err instanceof Error ? err.message : "Unable to change that question");
    } finally {
      setBusy(false);
    }
  }

  if (retired) {
    return (
      <li className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
        <div className="flex items-start gap-2">
          <Archive className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0">
            <p className="text-sm text-ink-700 line-through decoration-ink-300">{question.prompt}</p>
            <p className="mt-1 text-xs leading-5 text-ink-500">
              {question.supersededById
                ? "Retired — replaced by the next question. Its wording and answers are kept."
                : "Retired. Its answers are kept."}
            </p>
          </div>
        </div>
      </li>
    );
  }

  return (
    <li className="rounded-md border border-line-200 p-3">
      {editing ? (
        <div className="grid gap-2">
          {question.hasAnswers ? (
            <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
              Already answered. New wording becomes a new question here; the original and its answers are kept. Help text
              and Required can change freely.
            </p>
          ) : null}
          <label className="grid gap-1">
            <span className="field-label">Question</span>
            <textarea
              className="field-input min-h-16"
              value={prompt}
              maxLength={2000}
              onChange={(event) => setPrompt(event.target.value)}
            />
          </label>
          <label className="grid gap-1">
            <span className="field-label">Help text</span>
            <input className="field-input" value={helpText} onChange={(event) => setHelpText(event.target.value)} />
          </label>
          <ActionGroup>
            <button type="button" className="field-button" onClick={save} disabled={busy || !prompt.trim()}>
              {busy ? "Saving…" : question.hasAnswers && prompt.trim() !== question.prompt ? "Save as a new question" : "Save"}
            </button>
            <button
              type="button"
              className="field-button-secondary"
              disabled={busy}
              onClick={() => {
                setPrompt(question.prompt);
                setHelpText(question.helpText ?? "");
                setEditing(false);
              }}
            >
              Cancel
            </button>
          </ActionGroup>
        </div>
      ) : (
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="text-sm text-ink-900">
              <span className="text-ink-500">{ordinal}.</span> {question.prompt}
              {question.isRequired ? <span className="ml-1 text-error-600">*</span> : null}
            </p>
            {question.helpText ? <p className="mt-1 text-xs leading-5 text-ink-500">{question.helpText}</p> : null}
            {question.hasAnswers ? (
              <p className="mt-1 inline-flex items-center gap-1 text-xs text-ink-500">
                <History className="h-3 w-3" aria-hidden />
                Answered — its wording is fixed from here
              </p>
            ) : null}
          </div>
          {mayEdit ? (
            <ActionGroup align="end">
              <button type="button" className={rowAction("edit")} onClick={() => setEditing(true)} disabled={busy}>
                Update
              </button>
              <button type="button" className={rowAction("neutral")} onClick={toggleRequired} disabled={busy}>
                {question.isRequired ? "Make optional" : "Make required"}
              </button>
              {mayRemove ? (
                <button type="button" data-danger className={rowAction("danger")} onClick={remove} disabled={busy}>
                  {question.hasAnswers ? (
                    <>
                      <Lock className="h-3 w-3" aria-hidden />
                      Retire
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              ) : null}
            </ActionGroup>
          ) : null}
        </div>
      )}
    </li>
  );
}
