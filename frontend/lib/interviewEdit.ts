/**
 * What an interview save sends, decided by pure functions so the rules can be pinned without a page.
 *
 * Audit 2026-10-21 (AUDIT_DR_2026-10-21.md):
 *  - C1: an edit used to send `notes: null` from an unseeded box and wipe the stored notes. The box is
 *    now seeded, and `notes` is sent only when it differs from the record.
 *  - H2: Status is never set by the capture form. On an edit it is sent only when the reader picked a
 *    different value from the one stored.
 *  - H4: every answer held was sent, so an answer someone else wrote (often the RESP block prefilled
 *    with today's date) came back 403 and could strand a queued sitting. Only answers that differ from
 *    what the server already holds are sent now.
 */

export type AnswerMap = Record<string, string>;

export interface ResponseRow {
  questionId?: string | null;
  answerText?: string | null;
}

export interface OutgoingResponse {
  questionId: string;
  answerText: string;
}

/** The stored answers of a record, by question id, as the comparison baseline. */
export function answersByQuestion(rows: readonly ResponseRow[] | null | undefined): AnswerMap {
  const out: AnswerMap = {};
  for (const row of rows ?? []) {
    if (row.questionId) out[row.questionId] = row.answerText ?? "";
  }
  return out;
}

/**
 * The answers to send. Blank boxes are never sent; an answer identical to the baseline (the record
 * being edited, or the shared entry a create folds into) is not sent either, because it changes
 * nothing and only invites the authorship check on somebody else's words.
 */
export function responsesToSend(answers: AnswerMap, baseline: AnswerMap | null | undefined): OutgoingResponse[] {
  const out: OutgoingResponse[] = [];
  for (const [questionId, raw] of Object.entries(answers)) {
    const answerText = (raw ?? "").trim();
    if (!answerText) continue;
    const before = baseline?.[questionId];
    if (before !== undefined && before.trim() === answerText) continue;
    out.push({ questionId, answerText });
  }
  return out;
}

/** `{ notes }` only when the box differs from the stored notes; `{}` leaves them untouched. */
export function notesPatch(
  boxValue: string | null | undefined,
  stored: string | null | undefined
): { notes?: string | null } {
  const after = (boxValue ?? "").trim();
  const before = (stored ?? "").trim();
  if (after === before) return {};
  return { notes: after || null };
}

/** `{ status }` only on an edit, by a reader who may pick one, who picked a different value. */
export function statusPatch(options: {
  canPick: boolean;
  editing: boolean;
  picked: string | null | undefined;
  stored: string | null | undefined;
}): { status?: string } {
  if (!options.editing || !options.canPick) return {};
  const picked = (options.picked ?? "").trim();
  if (!picked || picked === (options.stored ?? "")) return {};
  return { status: picked };
}

/**
 * The prefill may fill a box only when it is blank and the shared entry holds no answer for it.
 * Otherwise it would overwrite somebody's words with today's date and the current reader's name.
 */
export function mayPrefill(
  questionId: string,
  current: string | undefined,
  lastPrefill: string | undefined,
  shared: AnswerMap | null | undefined
): boolean {
  if ((shared?.[questionId] ?? "").trim()) return false;
  const value = current ?? "";
  return !value || value === (lastPrefill ?? "");
}

/** The 403 the server sends for answers somebody else wrote. */
export interface OwnedAnswerRefusal {
  code: "ANSWER_OWNED_BY_OTHER";
  message: string;
  questionIds: string[];
}

export function ownedAnswerRefusal(payload: unknown): OwnedAnswerRefusal | null {
  const detail = (payload as { detail?: unknown } | null)?.detail;
  if (!detail || typeof detail !== "object") return null;
  const record = detail as Record<string, unknown>;
  if (record.code !== "ANSWER_OWNED_BY_OTHER") return null;
  const ids = Array.isArray(record.questionIds) ? record.questionIds.filter((id): id is string => typeof id === "string") : [];
  return { code: "ANSWER_OWNED_BY_OTHER", message: String(record.message ?? ""), questionIds: ids };
}

/** One sentence naming the questions another person answered. */
export function ownedAnswerSentence(prompts: readonly string[]): string {
  const named = prompts.filter(Boolean);
  if (!named.length) return "Someone else answered some of these questions. Only they or an admin can change them.";
  const shown = named.slice(0, 3).map((prompt) => `“${prompt}”`).join(", ");
  const more = named.length > 3 ? ` and ${named.length - 3} more` : "";
  return `Someone else answered ${shown}${more}. Only they or an admin can change ${named.length === 1 ? "it" : "them"}.`;
}

/** Extra multipart fields for a dictation from the interview form (H3). */
export function interviewDictationFields(
  interviewId: string | null | undefined,
  artisanIds: readonly string[]
): Record<string, string> {
  const fields: Record<string, string> = {};
  if (interviewId) fields.interviewId = interviewId;
  const ids = artisanIds.filter(Boolean);
  if (ids.length) fields.artisanIds = ids.join(",");
  return fields;
}
