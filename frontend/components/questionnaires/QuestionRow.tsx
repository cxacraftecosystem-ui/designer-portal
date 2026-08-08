"use client";

/**
 * One question in the editor, and the place the edit-after-answers rule is made visible.
 *
 * THE CONTROLS CHANGE BEFORE THE DESIGNER TOUCHES THEM, driven by `question.hasAnswers`, which the
 * server sends on every question for precisely this purpose:
 *
 *   * nobody has answered it  → "Delete" deletes, and rewording rewords.
 *   * somebody has answered it → the button says "Retire", and the wording box says out loud that a
 *     change will ADD a question rather than replace this one.
 *
 * WHY NOT JUST OFFER DELETE AND EXPLAIN AFTERWARDS. Because the server does not refuse — it converts.
 * A designer who presses Delete and is then told the question was retired instead has been overruled
 * by a machine on a screen about their own instrument, and the next thing they do is press it again.
 * Saying it first turns the same behaviour into a choice they made.
 *
 * A RETIRED QUESTION IS STILL DRAWN, greyed and uneditable, because its answers are still in the
 * record and a form that hid it would show four questions above six answers with nothing explaining
 * where the other two went.
 */

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

export function QuestionRow({
  questionnaireId,
  question,
  ordinal,
  mayEdit,
  onChanged,
  onError
}: {
  questionnaireId: string;
  question: QFormQuestion;
  ordinal: number;
  mayEdit: boolean;
  /** The whole form as the server returned it, plus the sentence to show when something non-obvious happened. */
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
      // `detail` is the server's own sentence for a supersede, and it is shown verbatim — this is
      // the fourth place in the stack that could paraphrase the rule, and the one where being
      // paraphrased would cost a designer their trust in what just happened to their question.
      onChanged(result.questionnaire, result.action === "superseded" ? result.detail : undefined);
    } catch (err) {
      onError(err instanceof Error ? err.message : "Unable to save that question");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    // TWO DIFFERENT CONFIRMATIONS, because they are two different acts. Reusing one sentence for
    // both would either frighten somebody deleting a typo or reassure somebody about a deletion that
    // is not happening.
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
      // Safe on an ANSWERED question, and deliberately still offered there: the required flag does
      // not alter what a recorded answer asserts, so the rule leaves it editable. Only the wording
      // is frozen.
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
                ? "Retired and replaced by the question that follows it. Its original wording and the answers given under it are kept — an answer means what it meant when it was given."
                : "Retired. It is no longer asked, and the answers recorded against it are still in the record."}
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
            // Shown while the box is still EMPTY of changes, not after Save. amber-100/amber-800 are
            // the brand rungs; amber-50 and amber-200 are stock Tailwind and do not pair with them.
            <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
              This question already has answers. Changing its wording will keep the original question and its answers, and
              add your new wording as a NEW question in the same place — nothing is overwritten. Help text and the required
              flag can be changed freely.
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
          <div className="flex flex-wrap gap-2">
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
          </div>
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
            <div className="flex flex-wrap justify-end gap-2">
              <button type="button" className={rowAction("edit")} onClick={() => setEditing(true)} disabled={busy}>
                Edit
              </button>
              <button type="button" className={rowAction("neutral")} onClick={toggleRequired} disabled={busy}>
                {question.isRequired ? "Make optional" : "Make required"}
              </button>
              {/*
                THE LABEL IS THE HONEST ONE, decided by `hasAnswers` rather than by what the button
                does to the URL. Offering "Delete" on a question the server will retire instead is
                the single most misleading control this screen could carry.
              */}
              <button type="button" className={rowAction("danger")} onClick={remove} disabled={busy}>
                {question.hasAnswers ? (
                  <>
                    <Lock className="h-3 w-3" aria-hidden />
                    Retire
                  </>
                ) : (
                  "Delete"
                )}
              </button>
            </div>
          ) : null}
        </div>
      )}
    </li>
  );
}
