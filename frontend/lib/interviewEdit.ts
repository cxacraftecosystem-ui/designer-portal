export type AnswerMap = Record<string, string>;

export interface ResponseRow {
  questionId?: string | null;
  answerText?: string | null;
}

export interface OutgoingResponse {
  questionId: string;
  answerText: string;
}

export function answersByQuestion(rows: readonly ResponseRow[] | null | undefined): AnswerMap {
  const out: AnswerMap = {};
  for (const row of rows ?? []) {
    if (row.questionId) out[row.questionId] = row.answerText ?? "";
  }
  return out;
}

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

export function notesPatch(
  boxValue: string | null | undefined,
  stored: string | null | undefined
): { notes?: string | null } {
  const after = (boxValue ?? "").trim();
  const before = (stored ?? "").trim();
  if (after === before) return {};
  return { notes: after || null };
}

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

export function ownedAnswerSentence(prompts: readonly string[]): string {
  const named = prompts.filter(Boolean);
  if (!named.length) return "Someone else answered some of these questions. Only they or an admin can change them.";
  const shown = named.slice(0, 3).map((prompt) => `“${prompt}”`).join(", ");
  const more = named.length > 3 ? ` and ${named.length - 3} more` : "";
  return `Someone else answered ${shown}${more}. Only they or an admin can change ${named.length === 1 ? "it" : "them"}.`;
}

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
