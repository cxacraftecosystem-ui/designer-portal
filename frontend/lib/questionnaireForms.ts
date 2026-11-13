import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import { filtersToQuery, type Filters } from "@/lib/questionnaire/filters";
import { fetchFile } from "@/lib/fileDownload";
import type { PageResult } from "@/lib/types";
import { exportFilename } from "@/lib/naming";
import { writeBody } from "@/lib/writeBody";

export type QFormQuestion = {
  id: string;
  prompt: string;
  helpText: string | null;
  isRequired: boolean;
  sortOrder: number;
  isActive: boolean;
  retiredAt: string | null;
  supersededById: string | null;
  hasAnswers: boolean;
};

export type QFormSection = {
  id: string;
  code: string;
  title: string;
  sortOrder: number;
  isActive: boolean;
  questions: QFormQuestion[];
};

export type QFormAnswer = {
  id: string;
  questionId: string;
  answerText: string | null;
  notes: string | null;
  answeredById: string | null;
  updatedAt: string | null;
};

export type QFormEntry = {
  id: string;
  title: string;
  respondentName: string | null;
  source: string;
  notes: string | null;
  createdAt: string | null;
  createdById: string | null;
  createdByName: string | null;
  answers: QFormAnswer[];
};

export type QFormEntryRow = {
  id: string;
  questionnaireId: string;
  title: string;
  respondentName: string | null;
  source: string;
  notes: string | null;
  createdById: string | null;
  createdAt: string | null;
  updatedAt: string | null;
};

export type QFormKind = "WORKSHOP_INTERVIEW" | "MARKET_SURVEY";

export const QUESTIONNAIRE_KIND_LABELS: Record<QFormKind, string> = {
  WORKSHOP_INTERVIEW: "Workshop interview",
  MARKET_SURVEY: "Market survey"
};

export const QUESTIONNAIRE_KINDS: QFormKind[] = ["WORKSHOP_INTERVIEW", "MARKET_SURVEY"];

export const QUESTIONNAIRE_KIND_NOT_STATED = "Kind not stated";

export function questionnaireKindLabel(kind: string | null | undefined): string {
  if (!kind) return QUESTIONNAIRE_KIND_NOT_STATED;
  return QUESTIONNAIRE_KIND_LABELS[kind as QFormKind] ?? kind;
}

export type QForm = {
  id: string;
  title: string;
  description: string | null;
  ownerId: string;
  designWorkshopId: string | null;
  isActive: boolean;
  isShared: boolean;
  isDefault?: boolean;
  kind: string | null;
  kindLabel: string;
  version: number;
  sourceFilename: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  sections: QFormSection[];
  questionCount: number;
  entries: QFormEntry[];
};

export type QFormSummary = {
  id: string;
  title: string;
  description: string | null;
  ownerId: string;
  ownerName: string | null;
  designWorkshopId: string | null;
  designWorkshopTitle: string | null;
  isShared: boolean;
  isActive: boolean;
  kind: string | null;
  kindLabel: string;
  version: number;
  sourceFilename: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  deletedAt?: string | null;
  deletedByName?: string | null;
};

export type QFormDeletedEntry = {
  id: string;
  title: string;
  respondentName: string | null;
  source: string;
  createdAt: string | null;
  createdByName: string | null;
  deletedAt: string;
  deletedByName: string | null;
};

export type QFormOption = {
  id: string;
  title: string;
  version: number;
  designWorkshopId: string | null;
  isShared: boolean;
  kind: string | null;
  kindLabel: string;
  attachedElsewhere: boolean;
};

export type QFormProblem = {
  sheet: string | null;
  row: number | null;
  severity: string;
  reason: string;
  value: string | null;
};

export type QFormChangeDetail = {
  action: string;
  questionId: string;
  replacementId?: string | null;
  before?: string | null;
  after?: string | null;
  reason: string;
};

export type QFormProvenance = {
  action: "answersImported" | "answersNotImported" | "reused";
  sourceQuestionnaireId: string | null;
  answersImported?: number;
  answersSkipped?: number;
  entriesCreated?: number;
  reason: string;
};

export type QFormUploadReport = {
  created: number;
  updated?: number;
  superseded: number;
  retired: number;
  removed: number;
  kept?: number;
  unchanged: number;
  sections: number;
  versionBefore: number;
  versionAfter: number;
  problems: QFormProblem[];
  details?: QFormChangeDetail[];
  entriesCreated?: number;
  answersImported?: number;
  answersSkipped?: number;
  provenance?: QFormProvenance | null;
};

export type QFormUploadResult = { questionnaire: QForm; report: QFormUploadReport };

export type QFormReuseResult = QFormUploadResult & { sourceQuestionnaireId: string };

export type QFormQuestionResult = {
  action: "updated" | "superseded" | "deleted" | "retired";
  questionId: string;
  replacementId?: string;
  detail?: string;
  questionnaire: QForm;
};

export type QFormCreateBody = {
  title: string;
  description?: string | null;
  designWorkshopId?: string | null;
  kind?: QFormKind | null;
};

export type QFormUpdateBody = {
  title?: string;
  description?: string | null;
  designWorkshopId?: string | null;
  kind?: QFormKind | null;
  isActive?: boolean;
  isShared?: boolean;
};

export type QFormReuseBody = {
  designWorkshopId?: string | null;
  title?: string;
  description?: string | null;
  kind?: QFormKind | null;
};

export type QFormSectionCreateBody = {
  title: string;
  code?: string | null;
  sortOrder?: number;
};

export type QFormSectionUpdateBody = { title?: string; sortOrder?: number; isActive?: boolean };

export type QFormQuestionCreateBody = {
  prompt: string;
  helpText?: string | null;
  isRequired?: boolean;
  sortOrder?: number;
};

export type QFormQuestionUpdateBody = {
  prompt?: string;
  helpText?: string | null;
  isRequired?: boolean;
  sortOrder?: number;
  sectionId?: string;
};

export type QFormEntryCreateBody = { title?: string | null; respondentName?: string | null; notes?: string | null };

export type QFormEntryUpdateBody = { title?: string; respondentName?: string | null; notes?: string | null };

export type QFormAnswerInput = { questionId: string; answerText?: string | null; notes?: string | null };

export type QFormAnswerSaveResult = {
  saved: {
    created: number;
    updated: number;
    unchanged: number;
    rejected?: { questionId: string; code?: string | null; message?: string | null }[];
  };
  answers: QFormAnswer[];
};

export type QFormListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  designWorkshopId?: string | null;
  activeOnly?: boolean;
  mineOnly?: boolean;
  deletedOnly?: boolean;
  filters?: Filters;
};

function appendQuery(query: string, extra: string): string {
  if (!extra) return query;
  return query ? `${query}&${extra}` : `?${extra}`;
}

export function listQuestionnaires(params: QFormListParams = {}) {
  const shared = params.filters ? filtersToQuery(params.filters) : "";
  return apiFetch<PageResult<QFormSummary>>(
    `/questionnaires${appendQuery(buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search ?? undefined,
      designWorkshopId: params.designWorkshopId ?? undefined,
      activeOnly: params.activeOnly === false ? "false" : undefined,
      mineOnly: params.mineOnly ? "true" : undefined,
      deletedOnly: params.deletedOnly ? "true" : undefined
    }), shared)}`
  );
}

export function restoreQuestionnaire(id: string) {
  return apiFetch<{ id: string; title: string }>(`/questionnaires/${encodeURIComponent(id)}/restore`, { method: "POST" });
}

export function listDeletedEntries(id: string) {
  return apiFetch<QFormDeletedEntry[]>(`/questionnaires/${encodeURIComponent(id)}/deleted-entries`);
}

export function restoreEntry(id: string, entryId: string) {
  return apiFetch<{ id: string; title: string }>(
    `/questionnaires/${encodeURIComponent(id)}/entries/${encodeURIComponent(entryId)}/restore`,
    { method: "POST" }
  );
}

export function listQuestionnaireOptions(designWorkshopId?: string | null) {
  return apiFetch<QFormOption[]>(`/questionnaires/options${buildQuery({ designWorkshopId: designWorkshopId ?? undefined })}`);
}

export function getQuestionnaire(id: string, options?: { includeRetired?: boolean }) {
  return apiFetch<QForm>(`/questionnaires/${id}${buildQuery({ includeRetired: options?.includeRetired ? "true" : undefined })}`);
}

export function createQuestionnaire(body: QFormCreateBody) {
  return apiFetch<QForm>("/questionnaires", { method: "POST", body: JSON.stringify(body) });
}

export function setDefaultQuestionnaire(id: string, isDefault: boolean) {
  return apiFetch<QForm>(`/questionnaires/${encodeURIComponent(id)}/default`, { method: "PUT", body: JSON.stringify({ isDefault }) });
}

export function patchQuestionnaire(id: string, body: QFormUpdateBody) {
  return apiFetch<QForm>(`/questionnaires/${id}`, { method: "PATCH", body: JSON.stringify(body) });
}

export function reuseQuestionnaire(id: string, body: QFormReuseBody = {}) {
  return apiFetch<QFormReuseResult>(`/questionnaires/${id}/reuse`, { method: "POST", body: JSON.stringify(body) });
}

export function createSection(id: string, body: QFormSectionCreateBody) {
  return apiFetch<QForm>(`/questionnaires/${id}/sections`, { method: "POST", body: JSON.stringify(body) });
}

export function patchSection(id: string, sectionId: string, body: QFormSectionUpdateBody) {
  return apiFetch<QForm>(`/questionnaires/${id}/sections/${sectionId}`, { method: "PATCH", body: JSON.stringify(body) });
}

export function createQuestion(id: string, sectionId: string, body: QFormQuestionCreateBody) {
  return apiFetch<QForm>(`/questionnaires/${id}/sections/${sectionId}/questions`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function patchQuestion(id: string, questionId: string, body: QFormQuestionUpdateBody) {
  return apiFetch<QFormQuestionResult>(`/questionnaires/${id}/questions/${questionId}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export function removeQuestion(id: string, questionId: string) {
  return apiFetch<QFormQuestionResult>(`/questionnaires/${id}/questions/${questionId}`, { method: "DELETE" });
}

export function createEntry(id: string, body: QFormEntryCreateBody) {
  return apiFetch<QFormEntryRow>(`/questionnaires/${id}/entries`, { method: "POST", body: JSON.stringify(writeBody(body, "POST")) });
}

export function patchEntry(id: string, entryId: string, body: QFormEntryUpdateBody) {
  return apiFetch<QFormEntryRow>(`/questionnaires/${id}/entries/${entryId}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export function saveAnswers(id: string, entryId: string, answers: QFormAnswerInput[]) {
  return apiFetch<QFormAnswerSaveResult>(`/questionnaires/${id}/entries/${entryId}/answers`, {
    method: "PUT",
    body: JSON.stringify({ answers })
  });
}

export type QFormFile = { blob: Blob; fileName: string };

const fetchWorkbook = fetchFile;

export function downloadProForma() {
  return fetchWorkbook("/questionnaires/pro-forma", exportFilename("Questionnaire", "Pro-Forma", "xlsx", { dated: false }));
}

export function downloadQuestionnaireWorkbook(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/xlsx`, exportFilename("Questionnaire", "Export", "xlsx"));
}

export function downloadQuestionSet(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/question-set.xlsx`, exportFilename("Questionnaire", "Questions", "xlsx"));
}

export function uploadQuestionnaire(
  file: File,
  options?: { title?: string | null; designWorkshopId?: string | null; kind?: QFormKind | null }
) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  if (options?.designWorkshopId) body.append("designWorkshopId", options.designWorkshopId);
  if (options?.kind) body.append("kind", options.kind);
  return apiFetch<QFormUploadResult>("/questionnaires/upload", { method: "POST", body });
}

export function reuploadQuestionnaire(id: string, file: File, options?: { title?: string | null }) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  return apiFetch<QFormUploadResult>(`/questionnaires/${id}/upload`, { method: "POST", body });
}

export function answerableQuestions(section: QFormSection): QFormQuestion[] {
  return section.questions.filter((question) => question.isActive);
}

export function answersByQuestion(entry: QFormEntry | null | undefined): Map<string, QFormAnswer> {
  const map = new Map<string, QFormAnswer>();
  for (const answer of entry?.answers ?? []) map.set(answer.questionId, answer);
  return map;
}

export function answeredCount(form: QForm, entry: QFormEntry): { answered: number; total: number } {
  const answers = answersByQuestion(entry);
  let answered = 0;
  let total = 0;
  for (const section of form.sections) {
    for (const question of answerableQuestions(section)) {
      total += 1;
      if ((answers.get(question.id)?.answerText ?? "").trim()) answered += 1;
    }
  }
  return { answered, total };
}
