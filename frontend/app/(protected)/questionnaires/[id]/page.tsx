"use client";

/**
 * One questionnaire: its sections and questions, editable in place, and the sittings recorded so far.
 *
 * THE WHOLE SCREEN IS SHAPED BY ONE BACKEND RULE — the edit-after-answers rule in
 * `backend/app/services/questionnaire_forms.py`. An answer is evidence, and the words it was given
 * under are part of that evidence, so:
 *
 *   * a question NOBODY has answered is fully editable and really deletable;
 *   * a question WITH answers keeps its wording — rewording it SUPERSEDES it (the original and its
 *     answers stay, the new wording becomes a new question in the same place);
 *   * deleting a question WITH answers RETIRES it: it stops being asked and its answers stay.
 *
 * THE UI SAYS ALL OF THAT BEFORE THE DESIGNER ACTS, NOT AFTER. `question.hasAnswers` arrives on
 * every question for exactly this purpose, so the "Delete" button becomes "Retire" and the rewording
 * box carries its warning while it is still empty. Offering a Delete that the server will convert
 * into something else, and only then explaining, is how a designer comes to believe the app ignored
 * them — and this is a screen where being ignored means losing fieldwork.
 *
 * WHO MAY EDIT. The server splits it two ways and this page mirrors the split exactly rather than
 * inventing a third: `_require_designer` (can_run_design_workshops) to READ this page and record
 * answers against the form, `_require_owner` (the questionnaire's owner, or an admin) to change its
 * questions. That is what lets a designer hand a colleague a form to fill in without also handing
 * them the ability to reword it halfway through the fieldwork — so a non-owner gets the full form,
 * the answer link, and no edit controls, rather than a padlock.
 */

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { ClipboardList, Download, FileSpreadsheet, Lock, Plus, Share2, Upload } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { ArtefactNotice } from "@/components/questionnaires/ArtefactNotice";
import { UploadDialog } from "@/components/questionnaires/UploadDialog";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { QuestionRow } from "@/components/questionnaires/QuestionRow";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { listDesignWorkshops, saveBlobToDisk, type DwSummary } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import { canEditOwnOrAdmin } from "@/lib/permissions";
import {
  answeredCount,
  createQuestion,
  createSection,
  downloadQuestionSet,
  downloadQuestionnaireWorkbook,
  getQuestionnaire,
  patchQuestionnaire,
  patchSection,
  type QForm,
  type QFormUploadReport
} from "@/lib/questionnaireForms";

export default function QuestionnaireDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const router = useRouter();
  const { user } = useAuth();
  const confirm = useConfirm();
  const { toast } = useToast();

  const [form, setForm] = useState<QForm | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [workshops, setWorkshops] = useState<DwSummary[]>([]);
  const [busy, setBusy] = useState(false);
  const [addingTo, setAddingTo] = useState<string | null>(null);
  const [sectionFormOpen, setSectionFormOpen] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [report, setReport] = useState<QFormUploadReport | null>(null);

  /**
   * `includeRetired` is TRUE here and that is the point of the editor.
   *
   * A retired question has to stay visible where its answers are read — a form that hid them would
   * show a designer four questions and six answers, with nothing saying where the other two went.
   * The answer screen makes the opposite choice for the boxes it offers; both are the same rule read
   * from two sides.
   */
  const load = useCallback(async () => {
    try {
      setForm(await getQuestionnaire(id, { includeRetired: true }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load that questionnaire");
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    listDesignWorkshops({ pageSize: 100 })
      .then((result) => {
        if (!cancelled) setWorkshops(result.items ?? []);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  // Mirrors `_require_owner` on every mutating route: the owner, or an admin. Not a rank test —
  // a second designer of equal standing may answer this form and may not reword it.
  const mayEdit = canEditOwnOrAdmin(user, form?.ownerId);

  async function run<T>(work: () => Promise<T>, failure: string): Promise<T | null> {
    setBusy(true);
    setError(null);
    try {
      return await work();
    } catch (err) {
      setError(err instanceof Error ? err.message : failure);
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function addSection(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    // Built before any await: React nulls `currentTarget` across one, and every field would read
    // back empty.
    const data = new FormData(element);
    const title = String(data.get("title") ?? "").trim();
    if (!title) return;
    const next = await run(
      () =>
        createSection(id, {
          title,
          // Left blank the server DERIVES a code from the title, which is what keeps it stable when
          // a section is later inserted above this one. Sending a positional code would not.
          code: String(data.get("code") ?? "").trim() || undefined
        }),
      "Unable to add that section"
    );
    if (!next) return;
    element.reset();
    setSectionFormOpen(false);
    await load();
  }

  async function addQuestion(sectionId: string, event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    const data = new FormData(element);
    const prompt = String(data.get("prompt") ?? "").trim();
    if (!prompt) return;
    const next = await run(
      () =>
        createQuestion(id, sectionId, {
          prompt,
          helpText: String(data.get("helpText") ?? "").trim() || undefined,
          isRequired: data.get("isRequired") === "on"
        }),
      "Unable to add that question"
    );
    if (!next) return;
    element.reset();
    setAddingTo(null);
    await load();
  }

  async function renameSection(sectionId: string, title: string) {
    // A section TITLE may change even when its questions have been answered: a heading is not what
    // an answer answers. Only the question's wording is frozen, which is why this needs no guard.
    const next = await run(() => patchSection(id, sectionId, { title }), "Unable to rename that section");
    if (next) setForm(next);
  }

  async function attachWorkshop(designWorkshopId: string) {
    // The empty option means DETACH, and it has to travel as an explicit null: the route puts
    // `designWorkshopId` back by hand after `clean_data` drops it, so null genuinely detaches.
    // Sending undefined would leave the current attachment in place and the dropdown would spring
    // back on the next load with no error anywhere.
    const next = await run(
      () => patchQuestionnaire(id, { designWorkshopId: designWorkshopId || null }),
      "Unable to change the workshop this questionnaire is attached to"
    );
    if (next) {
      setForm(next);
      setNotice(
        designWorkshopId
          ? "Attached to the design workshop."
          : "Detached from its design workshop. The questionnaire and every answer recorded against it are untouched."
      );
    }
  }

  async function renameQuestionnaire(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const title = String(data.get("title") ?? "").trim();
    if (!title) return;
    // The description is sent as the trimmed STRING, empty one included — never as null. `clean_data`
    // on the server drops nulls, and only `designWorkshopId` is put back by hand afterwards, so a
    // null here is silently discarded: a designer who cleared the box and pressed Save would be
    // shown the old description again with no error anywhere. An empty string is honoured, so
    // clearing the box genuinely clears it. Verified against the running API, not assumed.
    const next = await run(
      () => patchQuestionnaire(id, { title, description: String(data.get("description") ?? "").trim() }),
      "Unable to rename this questionnaire"
    );
    if (next) setForm(next);
  }

  async function deactivate() {
    if (!form) return;
    const ok = await confirm(
      deleteConfirm(
        "Take this questionnaire out of use?",
        `"${form.title}" disappears from every list and every dropdown, and no new answers can be recorded against it.`,
        // Said plainly because it is the whole reason there is no delete here: the answers are
        // somebody's fieldwork, and a designer who reads "delete" will hesitate over something that
        // destroys nothing.
        "Nothing is erased. Every answer already recorded stays in the record and the questionnaire can be brought back."
      )
    );
    if (!ok) return;
    const next = await run(() => patchQuestionnaire(id, { isActive: false }), "Unable to deactivate this questionnaire");
    if (next) {
      setForm(next);
      toast({ tone: "info", title: "Questionnaire taken out of use", description: "Its recorded answers are untouched." });
    }
  }

  async function download() {
    const file = await run(() => downloadQuestionnaireWorkbook(id), "Unable to download this questionnaire");
    if (file) saveBlobToDisk(file.blob, file.fileName);
  }

  /**
   * The QUESTIONS ALONE — no answers, no respondents, no sittings — as a file to send on.
   *
   * NOT gated on `mayEdit`, and that is the whole point of it existing. Reading this form is open to
   * any designer (the server's own rule: "the form is open to any designer and its sittings are
   * not"), and this file is exactly that openly-readable half written into a spreadsheet. Gating it
   * with the lossless download would put the two files back behind one permission and make sharing a
   * questionnaire impossible again — which is what forced the only previous workaround, widening the
   * gate on a workbook full of respondents' names.
   */
  async function downloadQuestions() {
    const file = await run(() => downloadQuestionSet(id), "Unable to download the question set");
    if (file) saveBlobToDisk(file.blob, file.fileName);
  }

  if (error && !form) {
    return (
      <>
        <PageHeader title="Questionnaire" icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      </>
    );
  }

  if (!form) {
    return (
      <>
        <PageHeader title="Questionnaire" icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
        <div className="panel p-4 text-sm text-ink-700">Loading…</div>
      </>
    );
  }

  const activeQuestions = form.sections.reduce(
    (total, section) => total + section.questions.filter((question) => question.isActive).length,
    0
  );

  return (
    <>
      <PageHeader
        title={form.title}
        description={
          form.description ??
          "Sections and questions, editable here. Questions that already have answers keep their wording — the answers recorded against them say what they say."
        }
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <>
            {/*
              UNGATED, AND IT IS THE ONE DOWNLOAD ON THIS PAGE THAT IS. It carries the questions and
              nothing else — no answers, no respondents' names, no sittings — so the server offers it
              to any designer, exactly as it offers the questions themselves through
              `GET /questionnaires/{id}`. Hiding it from a non-owner would restore the state this
              feature was built to end: a designer who wanted a colleague's questions had no way to
              get them, and the only apparent fix was to widen the gate on the file below.
            */}
            <button type="button" className="field-button-secondary" onClick={downloadQuestions} disabled={busy}>
              <Share2 className="h-4 w-4" aria-hidden />
              Download question set
            </button>
            {/*
              GATED BECAUSE THE WORKBOOK CARRIES THE SITTINGS, not just the questions: every
              respondent's name, their notes and every answer recorded against this form. The export
              endpoint used to be open to any designer and is now owner-scoped to match, so leaving
              this button up for everyone would offer a download that answers 403 — the classic
              half-fixed permission, where the UI and the API disagree about who may do this.
              `mayEdit` is owner-or-admin, which is exactly the endpoint's own rule.
            */}
            {mayEdit ? (
              <button type="button" className="field-button-secondary" onClick={download} disabled={busy}>
                <Download className="h-4 w-4" aria-hidden />
                Download .xlsx
              </button>
            ) : null}
            {mayEdit ? (
              <button type="button" className="field-button-secondary" onClick={() => setUploadOpen(true)}>
                <Upload className="h-4 w-4" aria-hidden />
                Re-upload
              </button>
            ) : null}
            <Link className="field-button" href={`/questionnaires/${id}/answer`}>
              Record answers
            </Link>
          </>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}
      {!form.isActive ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          This questionnaire has been taken out of use. It is hidden from the lists and no new answers can be recorded
          against it — the answers already recorded are still here and still exportable.
        </div>
      ) : null}
      {!mayEdit ? (
        // An honest statement of a real boundary, not a padlock over the page. Reading this form and
        // answering it are both open to any designer; only its wording belongs to its author.
        <div className="mb-4 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <Lock className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            This questionnaire belongs to another designer, so its questions cannot be changed here. You can still record
            answers against it, and you can download the <span className="font-medium text-ink-900">question set</span> —
            the questions on their own — to run this instrument yourself. The full .xlsx is not offered, because it
            carries every sitting recorded against this form: the respondents&rsquo; names, the interview notes and every
            answer.
          </span>
        </div>
      ) : null}

      {report ? <UploadReport report={report} className="mb-5" /> : null}

      {/* Both download buttons sit in this page's header, side by side, with the same title on the
          two files they produce. This is the caption for that choice. */}
      <ArtefactNotice className="mb-5" />

      {mayEdit ? (
        <form onSubmit={renameQuestionnaire} className="panel mb-5 grid gap-4 p-4">
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="Title" required>
              <TextInput name="title" defaultValue={form.title} required maxLength={220} key={`title-${form.title}`} />
            </Field>
            {/* FieldBlock, not Field: `Field` is a <label>, and a <label> around a themed dropdown
                forwards a stray click into the menu and slams it shut after one pick. */}
            <FieldBlock label="Design workshop">
              <Dropdown
                value={form.designWorkshopId ?? ""}
                onChange={attachWorkshop}
                options={[
                  { value: "", label: "Not attached to a workshop" },
                  ...workshops.map((workshop) => ({ value: workshop.id, label: workshop.title }))
                ]}
                ariaLabel="Attach to a design workshop"
                searchable
                disabled={busy}
                // This dropdown SAVES on select rather than filling in a form field, so focus must
                // not jump to the next control the way it does in a top-to-bottom record form.
                advanceOnSelect={false}
              />
            </FieldBlock>
          </div>
          <Field label="Description">
            <TextArea name="description" defaultValue={form.description ?? ""} key={`desc-${form.description ?? ""}`} />
          </Field>
          <div className="flex flex-wrap gap-2">
            <button className="field-button" disabled={busy}>
              Save details
            </button>
            {/* `field-danger` alone below, not stacked on `field-button-secondary`: it carries its
                own box (inline-flex, min-h-10, padding, radius), and class order in this repo is
                plain source order — `cn` is a join, not tailwind-merge — so two competing paddings
                would resolve by stylesheet position rather than by the order written here. */}
            {form.isActive ? (
              <button type="button" className="field-danger" onClick={deactivate} disabled={busy}>
                Take out of use
              </button>
            ) : (
              <button
                type="button"
                className="field-button-secondary"
                disabled={busy}
                onClick={async () => {
                  const next = await run(() => patchQuestionnaire(id, { isActive: true }), "Unable to bring this back");
                  if (next) setForm(next);
                }}
              >
                Bring back into use
              </button>
            )}
          </div>
        </form>
      ) : null}

      <section className="mb-5 grid gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-display text-lg font-bold text-ink-900">
            {form.sections.length} {form.sections.length === 1 ? "section" : "sections"} · {activeQuestions}{" "}
            {activeQuestions === 1 ? "question" : "questions"}
          </h2>
          {mayEdit ? (
            <button type="button" className="field-button-secondary" onClick={() => setSectionFormOpen((open) => !open)}>
              <Plus className="h-4 w-4" aria-hidden />
              {sectionFormOpen ? "Close" : "Add a section"}
            </button>
          ) : null}
        </div>

        {sectionFormOpen && mayEdit ? (
          <form onSubmit={addSection} className="panel grid gap-3 p-4 md:grid-cols-[1fr_12rem_auto] md:items-end">
            <Field label="Section title" required>
              <TextInput name="title" required maxLength={220} placeholder="Background" />
            </Field>
            <Field label="Code">
              <TextInput name="code" maxLength={24} placeholder="Derived from the title" />
            </Field>
            <button className="field-button" disabled={busy}>
              Add section
            </button>
          </form>
        ) : null}

        {form.sections.length === 0 ? (
          <div className="panel p-4">
            <EmptyState
              title="No sections yet"
              body={
                mayEdit
                  ? "Add a section above, or upload a filled-in pro-forma — a questionnaire can have as many sections as it needs."
                  : "This questionnaire has no sections yet."
              }
            />
          </div>
        ) : null}

        {form.sections.map((section) => (
          <article key={section.id} className="panel grid gap-3 p-4">
            <header className="flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                {mayEdit ? (
                  <input
                    className="field-input font-medium"
                    defaultValue={section.title}
                    maxLength={220}
                    aria-label={`Title of section ${section.code}`}
                    key={`section-${section.id}-${section.title}`}
                    onBlur={(event) => {
                      const value = event.target.value.trim();
                      if (value && value !== section.title) void renameSection(section.id, value);
                    }}
                  />
                ) : (
                  <h3 className="font-display text-base font-bold text-ink-900">{section.title}</h3>
                )}
                <p className="mt-1 text-xs text-ink-500">
                  Code {section.code}
                  {!section.isActive ? " · retired, kept because answers were recorded under it" : ""}
                </p>
              </div>
              {mayEdit ? (
                <RowActions>
                  <button
                    type="button"
                    className={rowAction("edit")}
                    onClick={() => setAddingTo((current) => (current === section.id ? null : section.id))}
                  >
                    {addingTo === section.id ? "Close" : "Add a question"}
                  </button>
                </RowActions>
              ) : null}
            </header>

            {addingTo === section.id && mayEdit ? (
              <form onSubmit={(event) => addQuestion(section.id, event)} className="grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3">
                <Field label="Question" required>
                  <TextInput name="prompt" required maxLength={2000} placeholder="How many looms do you own?" />
                </Field>
                <Field label="Help text">
                  <TextInput name="helpText" placeholder="Shown under the question when answering" />
                </Field>
                <label className="flex items-center gap-2 text-sm text-ink-700">
                  <input type="checkbox" name="isRequired" className="h-4 w-4 rounded border-line-200" />
                  Required
                </label>
                <div>
                  <button className="field-button" disabled={busy}>
                    Add question
                  </button>
                </div>
              </form>
            ) : null}

            {section.questions.length === 0 ? (
              <p className="text-sm text-ink-500">No questions in this section yet.</p>
            ) : (
              <ol className="grid gap-2">
                {section.questions.map((question, index) => (
                  <QuestionRow
                    key={question.id}
                    questionnaireId={id}
                    question={question}
                    ordinal={index + 1}
                    mayEdit={mayEdit}
                    onChanged={(next, message) => {
                      setForm(next);
                      if (message) setNotice(message);
                    }}
                    onError={setError}
                  />
                ))}
              </ol>
            )}
          </article>
        ))}
      </section>

      <section className="panel grid gap-3 p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="font-display text-lg font-bold text-ink-900">Recorded sittings</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              One sitting is one filled-in copy of this questionnaire. Answers that arrived on the uploaded spreadsheet and
              answers typed in the app are the same thing here — the source column is the only difference.
            </p>
          </div>
          <Link className="field-button-secondary" href={`/questionnaires/${id}/answer`}>
            Record answers
          </Link>
        </div>
        {form.entries.length === 0 ? (
          <p className="text-sm text-ink-500">
            Nothing recorded yet. &ldquo;Record answers&rdquo; starts a sitting — it works whether or not the uploaded sheet
            had any answers in it.
          </p>
        ) : (
          <ul className="grid gap-2">
            {form.entries.map((entry) => {
              const progress = answeredCount(form, entry);
              return (
                <li key={entry.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 p-3">
                  <div className="min-w-0">
                    <p className="font-medium text-ink-900">{entry.respondentName || entry.title}</p>
                    <p className="text-xs text-ink-500">
                      {progress.answered} of {progress.total} answered
                      {entry.createdByName ? ` · started by ${entry.createdByName}` : ""}
                      {entry.createdAt ? ` · ${formatDateTime(entry.createdAt)}` : ""}
                      {/* "UPLOAD" means the answers came in on the spreadsheet's answer columns.
                          Worth naming: a designer looking for the interview they typed in the app
                          needs to tell it from the six that arrived in a file. */}
                      {entry.source === "UPLOAD" ? " · from the uploaded spreadsheet" : ""}
                    </p>
                  </div>
                  <Link className={rowAction("edit")} href={`/questionnaires/${id}/answer?entry=${entry.id}`}>
                    Open
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <UploadDialog
        open={uploadOpen}
        questionnaireId={id}
        onClose={() => setUploadOpen(false)}
        onUploaded={(result) => {
          setUploadOpen(false);
          setReport(result.report);
          setForm(result.questionnaire);
          // Re-read anyway: the response is authoritative for the form, but this page also shows the
          // sittings, and a re-upload can carry new answer columns that become new sittings.
          void load();
          router.refresh();
        }}
      />
    </>
  );
}
