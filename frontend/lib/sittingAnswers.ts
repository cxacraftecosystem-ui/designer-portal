/**
 * The custom-questionnaire answer screen's rules, as pure functions (audit 2026-10-21).
 *
 *  - M5: an answer somebody else recorded is LOCKED for a reader who is not a content admin — the
 *    handset has drawn it read-only since it shipped (`QuestionnaireAnswerScreen.kt`), the web let it
 *    be typed over and then refused the whole section. The server now saves per item and returns the
 *    answers it would not take; those are shown under their own question.
 *  - L7: a required question left blank holds the section, with a message under the question.
 *  - L8: who may correct a sitting's respondent, label and notes — the server's `_may_rewrite_entry`.
 */

export interface AnswerRow {
  questionId: string;
  answerText: string | null;
  answeredById: string | null;
}

export interface RequiredQuestion {
  id: string;
  isActive: boolean;
  isRequired: boolean;
}

export interface RejectedAnswer {
  questionId: string;
  code?: string | null;
  message?: string | null;
}

/** Question ids whose stored answer belongs to someone else and so may not be typed over. */
export function lockedQuestionIds(
  answers: readonly AnswerRow[],
  viewerId: string | null | undefined,
  isContentAdmin: boolean
): Set<string> {
  const locked = new Set<string>();
  if (isContentAdmin) return locked;
  for (const answer of answers) {
    if (!(answer.answerText ?? "").trim()) continue;
    if (answer.answeredById && answer.answeredById !== viewerId) locked.add(answer.questionId);
  }
  return locked;
}

export const LOCKED_ANSWER_NOTE = "Answered by someone else. Only they or an admin can change it.";

/** Required, active questions whose box is blank. */
export function blankRequiredIds(questions: readonly RequiredQuestion[], draft: Record<string, string>): string[] {
  return questions
    .filter((question) => question.isActive && question.isRequired && !(draft[question.id] ?? "").trim())
    .map((question) => question.id);
}

export const REQUIRED_ANSWER_NOTE = "Required — answer this before moving on.";

export function requiredBanner(count: number): string {
  return count === 1 ? "One required question is unanswered." : `${count} required questions are unanswered.`;
}

/** The server's per-item refusals, by question, with a sentence for each. */
export function rejectionsByQuestion(rejected: readonly RejectedAnswer[] | null | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const item of rejected ?? []) {
    if (!item?.questionId) continue;
    const message = (item.message ?? "").trim();
    out[item.questionId] =
      message ||
      (item.code === "QUESTION_RETIRED"
        ? "This question was retired, so it can no longer be answered."
        : item.code === "ANSWER_OWNED_BY_OTHER"
          ? LOCKED_ANSWER_NOTE
          : "This answer was not saved.");
  }
  return out;
}

export function rejectedBanner(count: number): string {
  return count === 1 ? "One answer was not saved — see the note under it." : `${count} answers were not saved — see the notes under them.`;
}

/** May this reader correct the sitting's respondent, label and notes? Mirrors `_may_rewrite_entry`. */
export function mayEditSitting(options: {
  viewerId: string | null | undefined;
  entryCreatedById: string | null | undefined;
  formOwnerId: string | null | undefined;
  isContentAdmin: boolean;
}): boolean {
  if (options.isContentAdmin) return true;
  if (!options.viewerId) return false;
  return options.entryCreatedById === options.viewerId || options.formOwnerId === options.viewerId;
}
