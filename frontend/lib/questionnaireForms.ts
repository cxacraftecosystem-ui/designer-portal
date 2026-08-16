/**
 * Custom questionnaires — the form a designer authored themselves — typed for the web client.
 *
 * PLURAL `/questionnaires`, and that is the single most important thing in this file. The singular
 * `/questionnaire` is the ONE global artisan questionnaire every researcher answers, and it is a
 * different object all the way down to the tables (see the block comment above `model Questionnaire`
 * in schema.prisma). Two things with the same word for a name, distinguished by one character, is
 * exactly how a client ends up posting a section to the wrong instrument — so nothing in this file
 * touches the singular prefix, and `app/(protected)/questionnaire/` is a different feature that must
 * keep working.
 *
 * THE LOOP THIS SERVES, in the owner's words: designers make their OWN questionnaires. They download
 * the .xlsx pro-forma, type their questions into it, upload it, and then RECORD THE ANSWERS IN THE
 * APP. The uploaded sheet may already carry answers or may be completely blank, and both have to
 * work — which is why an answer typed into the app and an answer that arrived on the spreadsheet are
 * the same row in the same table (`source: "APP"` vs `"UPLOAD"`), and why nothing below has a
 * separate path for either.
 *
 * TWO SHAPES ON THIS WIRE WILL TRIP A READER:
 *
 * 1. **`PATCH /questionnaires/{id}/questions/{questionId}` does not answer with a question.** It
 *    answers with `{action, questionId, replacementId?, detail?, questionnaire}` — because rewording
 *    a question that already has answers SUPERSEDES it rather than overwriting it, and the caller
 *    has to be told which of the two happened. Same for DELETE, which answers `"deleted"` or
 *    `"retired"`. See {@link QFormQuestionResult}.
 * 2. **The upload endpoints answer with `{questionnaire, report}`, not with a questionnaire.** The
 *    report's `problems` list is a FEATURE, not diagnostics: a designer who uploads forty questions
 *    and is shown thirty-eight, with no way to learn which two are missing, does not trust the
 *    import again. Every caller must render it. See {@link QFormUploadResult}.
 */

import {
  API_BASE,
  ApiError,
  apiFetch,
  assertApiConfigured,
  buildQuery,
  describeApiDetail,
  getToken
} from "@/lib/api";
import type { PageResult } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The form
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One question.
 *
 * `hasAnswers` is the single fact that decides what the editor may offer, and the server sends it on
 * every question precisely so a client can grey the rewording box out BEFORE the designer types into
 * it rather than after they press Save. A question with answers can still have its help text, its
 * required flag and its position changed — none of those alter what a recorded answer asserts — but
 * its WORDING is frozen, because the words an answer was given under are part of that answer.
 *
 * `isActive: false` is a RETIRED question: no longer asked, answers still in the record. It only
 * arrives at all when the caller asked for `includeRetired`.
 */
export type QFormQuestion = {
  id: string;
  prompt: string;
  helpText: string | null;
  isRequired: boolean;
  sortOrder: number;
  isActive: boolean;
  retiredAt: string | null;
  /** The question that replaced this one's wording, when it was superseded rather than merely retired. */
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

/**
 * One sitting — a single filled-in copy of the questionnaire.
 *
 * `source` is `"APP"` for a sitting started on this screen and `"UPLOAD"` for one the spreadsheet
 * carried in its answer columns. Worth showing, because a designer who typed six interviews into
 * Excel and then starts a seventh in the app needs to see all seven in one list and be able to tell
 * which is which.
 */
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

/**
 * A sitting as the CREATE and PATCH endpoints answer, which is not {@link QFormEntry}.
 *
 * Those two return the database row straight through `public_encode`, so `answers` comes back as
 * `null` rather than as a list and `createdByName` is not there at all — only `load_form` assembles
 * the richer shape. Typing both responses as `QFormEntry` would compile perfectly and hand a caller
 * a `null` it believes is an array; the first `.map` over it is a blank screen. A caller that needs
 * the answers re-reads the form.
 */
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

export type QForm = {
  id: string;
  title: string;
  description: string | null;
  ownerId: string;
  designWorkshopId: string | null;
  isActive: boolean;
  /**
   * Bumped on every supersede and every retire, so a client holding a cached copy can tell it is
   * stale with an integer compare rather than a deep diff.
   */
  version: number;
  sourceFilename: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  sections: QFormSection[];
  questionCount: number;
  entries: QFormEntry[];
};

/** The list row. Flatter than {@link QForm} — no sections, no sittings. */
export type QFormSummary = {
  id: string;
  title: string;
  description: string | null;
  ownerId: string;
  ownerName: string | null;
  designWorkshopId: string | null;
  designWorkshopTitle: string | null;
  isActive: boolean;
  version: number;
  sourceFilename: string | null;
  createdAt: string | null;
  updatedAt: string | null;
};

/** `GET /questionnaires/options` — id + label and nothing else, for a dropdown. */
export type QFormOption = {
  id: string;
  title: string;
  version: number;
  designWorkshopId: string | null;
  /** True when this questionnaire is already attached to a DIFFERENT workshop than the one asked about. */
  attachedElsewhere: boolean;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Upload — and the report that must never be swallowed
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row the parser could not read cleanly, in terms a designer can act on.
 *
 * `row` is the 1-based worksheet row exactly as Excel's gutter shows it, so "row 34" means press
 * Ctrl+G and type 34 — which is the whole reason it is worth printing. `severity` is `"error"` when
 * nothing was stored for that row and `"warning"` when it was stored but something had to be
 * assumed.
 */
export type QFormProblem = {
  sheet: string | null;
  row: number | null;
  severity: string;
  reason: string;
  value: string | null;
};

/**
 * One question the edit rule did something non-obvious to, with the sentence to show for it.
 *
 * `reason` is written by the server to be displayed VERBATIM. A designer whose six reworded
 * questions came back as six new questions has to be told that happened and that their answers are
 * safe; they cannot work it out from a question count, and paraphrasing it here would make this
 * client the fifth place that explains the same rule slightly differently.
 */
export type QFormChangeDetail = {
  action: string;
  questionId: string;
  replacementId?: string | null;
  before?: string | null;
  after?: string | null;
  reason: string;
};

/**
 * WHAT THE IMPORT DECIDED TO DO WITH THE ANSWERS THAT WERE ALREADY IN THE WORKBOOK.
 *
 * `null` when there were none, and then there is nothing to say. Otherwise one of two outcomes, and
 * a client that does not print `reason` has re-created the bug this field exists to end:
 *
 * - `answersImported`    — the workbook was filled in BY HAND (no Questionnaire ID, no Question IDs),
 *                          so there is no other recorder anywhere in the picture and the answers are
 *                          recorded as sittings attributed to whoever uploaded it. The ordinary case.
 * - `answersNotImported` — the workbook came OUT OF the platform, so its answers are somebody's
 *                          fieldwork that already exists here under the names of the people who
 *                          recorded it. The questions were imported and the answers were not, because
 *                          writing them again would duplicate that fieldwork under a new author.
 *                          `sourceQuestionnaireId` names where they really live, when the file said.
 *
 * The old behaviour was to import them unconditionally, with `createdById` and `answeredById` set to
 * the uploader — so a designer handed a colleague's workbook silently acquired that colleague's
 * respondents as their own recorded interviews, in their own name, in the questionnaire annexure of
 * the report they submit. Nothing on any screen said so, which is exactly why this field is not
 * optional to render.
 */
export type QFormProvenance = {
  action: "answersImported" | "answersNotImported";
  sourceQuestionnaireId: string | null;
  answersImported?: number;
  answersSkipped?: number;
  entriesCreated?: number;
  /** Written on the server to be shown VERBATIM. Do not paraphrase it here. */
  reason: string;
};

export type QFormUploadReport = {
  created: number;
  updated?: number;
  superseded: number;
  retired: number;
  removed: number;
  unchanged: number;
  sections: number;
  versionBefore: number;
  versionAfter: number;
  problems: QFormProblem[];
  /** Only the re-upload path can produce these — a fresh import has nothing to supersede. */
  details?: QFormChangeDetail[];
  /** Sittings created from answer columns that were already filled in on the sheet. */
  entriesCreated?: number;
  answersImported?: number;
  /** Answers the workbook carried that were deliberately NOT re-recorded. See {@link QFormProvenance}. */
  answersSkipped?: number;
  provenance?: QFormProvenance | null;
};

export type QFormUploadResult = { questionnaire: QForm; report: QFormUploadReport };

/* ────────────────────────────────────────────────────────────────────────────
 * Single-question edits — the two-outcome responses
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What a PATCH or DELETE on one question actually did.
 *
 * FOUR ACTIONS, and a client that assumed one of them is a client that lies to the designer:
 *
 * - `updated`    — the plain edit; nobody had answered it.
 * - `superseded` — it had answers, so the original wording kept them and the new wording was added
 *                  as a NEW question in the same place. `replacementId` names it.
 * - `deleted`    — really gone; nobody had answered it.
 * - `retired`    — it had answers, so it stopped being asked and its answers stayed in the record.
 *
 * The endpoint answers 200 with the action rather than 204 precisely because the outcomes differ.
 * `questionnaire` is the whole form again, WITH retired questions included, so a caller never has to
 * re-fetch to find the row it was just told about.
 */
export type QFormQuestionResult = {
  action: "updated" | "superseded" | "deleted" | "retired";
  questionId: string;
  replacementId?: string;
  detail?: string;
  questionnaire: QForm;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Request bodies
 * ──────────────────────────────────────────────────────────────────────────── */

export type QFormCreateBody = {
  title: string;
  description?: string | null;
  designWorkshopId?: string | null;
};

/**
 * `designWorkshopId: null` DETACHES; omitting the key leaves the attachment alone.
 *
 * That asymmetry is the repo-wide `clean_data` convention and the route puts the key back by hand
 * after `clean_data` has dropped it, so `null` genuinely means detach here — it is not a value that
 * gets silently ignored.
 */
export type QFormUpdateBody = {
  title?: string;
  description?: string | null;
  designWorkshopId?: string | null;
  isActive?: boolean;
};

export type QFormSectionCreateBody = {
  title: string;
  /** Left blank, a stable code is derived from the title — which is what keeps it stable when a section is inserted above it. */
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

/**
 * What one save wrote.
 *
 * `unchanged` is not padding: the endpoint deliberately skips rows whose text did not change, so a
 * save that touched one answer does not re-stamp `answeredById` across a whole section and take
 * authorship of work the saver never did.
 */
export type QFormAnswerSaveResult = {
  saved: { created: number; updated: number; unchanged: number };
  answers: QFormAnswer[];
};

/* ────────────────────────────────────────────────────────────────────────────
 * Endpoints
 * ──────────────────────────────────────────────────────────────────────────── */

export type QFormListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  designWorkshopId?: string | null;
  /** Defaults to true on the server — deactivated questionnaires are out of every list unless asked for. */
  activeOnly?: boolean;
  /** Narrows an ADMIN's view to their own. A non-admin is scoped to their own regardless, so this never widens anything. */
  mineOnly?: boolean;
};

export function listQuestionnaires(params: QFormListParams = {}) {
  return apiFetch<PageResult<QFormSummary>>(
    `/questionnaires${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search ?? undefined,
      designWorkshopId: params.designWorkshopId ?? undefined,
      // `buildQuery` takes no booleans and drops "" exactly as it drops null, so both flags are
      // spelled out as literals and omitted entirely when they are at their server default.
      activeOnly: params.activeOnly === false ? "false" : undefined,
      mineOnly: params.mineOnly ? "true" : undefined
    })}`
  );
}

export function listQuestionnaireOptions(designWorkshopId?: string | null) {
  return apiFetch<QFormOption[]>(`/questionnaires/options${buildQuery({ designWorkshopId: designWorkshopId ?? undefined })}`);
}

/**
 * One questionnaire, its sittings and its answers.
 *
 * `includeRetired` is what the EDITOR asks for and a fresh answer sheet does not. A retired question
 * must stay visible wherever its recorded answers are read — otherwise a sitting shows four answers
 * to a six-question form and looks like it lost two — and must never be offered for a NEW answer.
 * Both screens in this feature therefore ask for retired questions and decide per question whether
 * it is editable, rather than asking the server for a shorter list and losing the recorded answers.
 */
export function getQuestionnaire(id: string, options?: { includeRetired?: boolean }) {
  return apiFetch<QForm>(`/questionnaires/${id}${buildQuery({ includeRetired: options?.includeRetired ? "true" : undefined })}`);
}

export function createQuestionnaire(body: QFormCreateBody) {
  return apiFetch<QForm>("/questionnaires", { method: "POST", body: JSON.stringify(body) });
}

export function patchQuestionnaire(id: string, body: QFormUpdateBody) {
  return apiFetch<QForm>(`/questionnaires/${id}`, { method: "PATCH", body: JSON.stringify(body) });
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

/** Edit one question. May come back as `superseded` rather than `updated` — see {@link QFormQuestionResult}. */
export function patchQuestion(id: string, questionId: string, body: QFormQuestionUpdateBody) {
  return apiFetch<QFormQuestionResult>(`/questionnaires/${id}/questions/${questionId}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

/** Remove one question. Comes back as `deleted` OR `retired` — never assume it is gone. */
export function removeQuestion(id: string, questionId: string) {
  return apiFetch<QFormQuestionResult>(`/questionnaires/${id}/questions/${questionId}`, { method: "DELETE" });
}

/**
 * Start a sitting. NOT owner-gated on the server, and the UI must not gate it either: recording
 * answers is the whole point of attaching a questionnaire to a workshop, and a form only its author
 * may answer is a form nobody uses.
 */
export function createEntry(id: string, body: QFormEntryCreateBody) {
  return apiFetch<QFormEntryRow>(`/questionnaires/${id}/entries`, { method: "POST", body: JSON.stringify(body) });
}

export function patchEntry(id: string, entryId: string, body: QFormEntryUpdateBody) {
  return apiFetch<QFormEntryRow>(`/questionnaires/${id}/entries/${entryId}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

/**
 * Record answers for one sitting. PUT, and idempotent: re-sending an unchanged section writes
 * nothing, and `@@unique([entryId, questionId])` makes that true at the database as well.
 *
 * Sent a SECTION at a time by the answer screen rather than a whole form at once — that is the unit
 * a person actually finishes, and it is what makes a save cheap enough to run on leaving each
 * section instead of only at the end.
 */
export function saveAnswers(id: string, entryId: string, answers: QFormAnswerInput[]) {
  return apiFetch<QFormAnswerSaveResult>(`/questionnaires/${id}/entries/${entryId}/answers`, {
    method: "PUT",
    body: JSON.stringify({ answers })
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The workbook: two downloads and two uploads
 * ──────────────────────────────────────────────────────────────────────────── */

export type QFormFile = { blob: Blob; fileName: string };

/**
 * Fetch one .xlsx by hand, because `apiFetch` cannot.
 *
 * That helper reads every response as JSON or TEXT, and reading a workbook as text hands back a
 * mangled string cast to the caller's type — a download that "succeeds" and produces a file Excel
 * refuses to open. So the fetch is built here with the same three obligations `apiFetch` discharges:
 * refuse the request when this build has no usable API address, attach the bearer token, and turn a
 * failure body into the sentence the server actually sent rather than "[object Object]".
 */
async function fetchWorkbook(path: string, fallbackName: string): Promise<QFormFile> {
  assertApiConfigured();

  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api${path}`, { headers, cache: "no-store" });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it can never be the
    // last resort on its own, or a body-less failure reaches the screen as a blank error box.
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || `The server refused the request (HTTP ${response.status}).`),
      payload
    );
  }
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

/** The blank workbook a designer types their own questions into. */
export function downloadProForma() {
  return fetchWorkbook("/questionnaires/pro-forma", "questionnaire-pro-forma.xlsx");
}

/**
 * This questionnaire as the same workbook — question ids filled in, one Answer/Notes column pair per
 * recorded sitting.
 *
 * The ids are what make re-uploading this file an EDIT of these questions rather than a second copy
 * of them, which is why the download half exists at all and why the UI must send designers here
 * rather than to a fresh pro-forma when they want to change an existing form in Excel.
 */
export function downloadQuestionnaireWorkbook(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/xlsx`, "questionnaire.xlsx");
}

/**
 * The QUESTION SET: this questionnaire's questions, their order, their help text and their required
 * flags — and NO answers, NO respondents' names and NO recorded sittings.
 *
 * THE FILE A DESIGNER SENDS TO ANOTHER DESIGNER, and the reason there are two downloads rather than
 * one. {@link downloadQuestionnaireWorkbook} is deliberately lossless, so it carries every sitting
 * ever recorded against the form; that is why the server refuses it to anyone but the owner, a
 * designer on its design workshop, or an admin, and why sharing a questionnaire used to be
 * impossible. This is the openly-readable half — the same questions `getQuestionnaire` already hands
 * to any designer — written into a spreadsheet, so ANY designer may take it.
 *
 * ITS QUESTION IDs ARE BLANK ON PURPOSE. The receiving designer uploads it through
 * {@link uploadQuestionnaire} and gets their OWN new, empty questionnaire carrying these questions,
 * rather than an edit of the sender's. A UI that offered this file to a "re-upload" control would be
 * offering to fork somebody else's form into their own.
 */
export function downloadQuestionSet(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/question-set.xlsx`, "questionnaire-questions.xlsx");
}

/**
 * Create a questionnaire from a filled-in pro-forma.
 *
 * `title` and `designWorkshopId` are FORM FIELDS on the same multipart body as the file, not query
 * parameters — the route declares them with `Form(...)` for exactly that reason. Appending them to
 * the URL instead would have them silently ignored: an untitled questionnaire attached to nothing,
 * with a 201 saying it went fine.
 */
export function uploadQuestionnaire(file: File, options?: { title?: string | null; designWorkshopId?: string | null }) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  if (options?.designWorkshopId) body.append("designWorkshopId", options.designWorkshopId);
  return apiFetch<QFormUploadResult>("/questionnaires/upload", { method: "POST", body });
}

/**
 * Re-upload an edited workbook over an existing questionnaire.
 *
 * Runs the edit-after-answers rule, so the answer is where `report.details` comes from. A workbook
 * whose Details sheet names a DIFFERENT questionnaire is refused with a 409 rather than applied —
 * that is a designer who picked the wrong file out of their downloads folder, and applying it would
 * retire this questionnaire's entire question set as "absent from the upload" in one press.
 */
export function reuploadQuestionnaire(id: string, file: File, options?: { title?: string | null }) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  return apiFetch<QFormUploadResult>(`/questionnaires/${id}/upload`, { method: "POST", body });
}

/**
 * The server's chosen file name, out of `content-disposition`.
 *
 * Worth honouring rather than inventing one: the server strips the characters Windows forbids in a
 * file name, and a questionnaire named after a craft is routinely saved onto a departmental share,
 * where a name that fails to save is a file that was not delivered.
 */
function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    // A name that is not valid percent-encoding is still a usable name; `decodeURIComponent` throws
    // on a bare "%", and losing the download over a literal percent sign in a craft name is absurd.
    return match[1];
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Small shared readings of the form
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The questions a NEW answer may be recorded against.
 *
 * Retired questions are excluded here and nowhere else, because `save_answers` refuses them with a
 * 422 — a question that was superseded or retired keeps the answers it already has and must not
 * collect new ones, or the wording a designer deliberately replaced quietly carries on gathering
 * evidence. Filtering at the point the payload is built is what keeps the screen from offering a box
 * whose save is guaranteed to fail.
 */
export function answerableQuestions(section: QFormSection): QFormQuestion[] {
  return section.questions.filter((question) => question.isActive);
}

/** Every answer in one sitting, keyed by question, for seeding the boxes. */
export function answersByQuestion(entry: QFormEntry | null | undefined): Map<string, QFormAnswer> {
  const map = new Map<string, QFormAnswer>();
  for (const answer of entry?.answers ?? []) map.set(answer.questionId, answer);
  return map;
}

/**
 * How many of this sitting's answerable questions carry actual text.
 *
 * Blank-but-saved rows do NOT count, matching `_answered_question_ids` on the server: the app writes
 * an empty row when somebody opens a sitting, tabs through it and saves, and counting that as
 * answered would both overstate progress here and — on the server's side of the same test — freeze a
 * question nobody ever answered.
 */
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
