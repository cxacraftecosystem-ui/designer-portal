"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { ClipboardList, CopyPlus, Download, FileSpreadsheet, Lock, Plus, Share2, Upload } from "lucide-react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { HeldPostNotice, useHeldPostRefusal } from "@/components/designworkshop/HeldPostNotice";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { ArtefactNotice } from "@/components/questionnaires/ArtefactNotice";
import { DeletedSittings } from "@/components/questionnaires/DeletedSittings";
import { cappedListNotice, cutOf } from "@/components/data/cappedList";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { ReuseDialog } from "@/components/questionnaires/ReuseDialog";
import { DownloadBundleButton } from "@/components/questionnaires/BundleControls";
import { FormAdminPanel, FormSessions } from "@/components/questionnaires/FormAdminPanel";
import { OfflineFormDraft, PendingAuthoring } from "@/components/questionnaires/PendingAuthoring";
import { isTempId, queueQuestion, queueSection } from "@/lib/questionnaireAuthoringOutbox";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { answerFormTarget } from "@/lib/questionnaireLinks";
import { UploadDialog } from "@/components/questionnaires/UploadDialog";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { QuestionRow } from "@/components/questionnaires/QuestionRow";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { listEveryDesignWorkshop, saveBlobToDisk, type DwSummary } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import { apiFetch } from "@/lib/api";
import { canEditOwnOrAdmin, isContentAdmin, mayDeleteQuestionnaire } from "@/lib/permissions";
import { cachedQuestionnaireNotice, loadQuestionnaireWithCache } from "@/lib/questionnaireFormCache";
import { Toggle } from "@/components/ui/Toggle";
import {
  designWorkshopOptions,
  deviceLooksOffline,
  NO_DESIGN_WORKSHOP,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import {
  answeredCount,
  createQuestion,
  createSection,
  downloadQuestionSet,
  downloadQuestionnaireWorkbook,
  patchQuestionnaire,
  patchSection,
  questionnaireKindLabel,
  QUESTIONNAIRE_KINDS,
  QUESTIONNAIRE_KIND_LABELS,
  type QForm,
  type QFormKind,
  type QFormUploadReport
} from "@/lib/questionnaireForms";
import { ActionGroup } from "@/components/ui/ActionGroup";

const NO_WORKSHOP_ROWS: readonly DwSummary[] = [];

export default function QuestionnairePage() {
  const params = useParams<{ id: string }>();
  if (isTempId(params.id)) return <OfflineFormDraft id={params.id} />;
  return <QuestionnaireDetailPage />;
}

function QuestionnaireDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const confirm = useConfirm();
  const { toast } = useToast();
  const { adminMode, canDeleteRecords } = useAdminView();
  const [deletedSittingsToken, setDeletedSittingsToken] = useState(0);

  const [form, setForm] = useState<QForm | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [workshopList, setWorkshopList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  const [workshopsOnline, setWorkshopsOnline] = useState(true);
  const [busy, setBusy] = useState(false);
  const [addingTo, setAddingTo] = useState<string | null>(null);
  const [sectionFormOpen, setSectionFormOpen] = useState(false);
  const [sectionTitle, setSectionTitle] = useState("");
  const [questionPrompt, setQuestionPrompt] = useState("");
  const [questionHelp, setQuestionHelp] = useState("");
  const [renameTitle, setRenameTitle] = useState("");
  const [uploadOpen, setUploadOpen] = useState(false);
  const [report, setReport] = useState<QFormUploadReport | null>(null);
  const [reuseOpen, setReuseOpen] = useState(false);
  const [reused, setReused] = useState<{ id: string; title: string } | null>(null);
  const [cached, setCached] = useState<{ at: string | null; version: number } | null>(null);

  const viewerId = user?.id ?? null;
  const load = useCallback(async () => {
    try {
      const read = await loadQuestionnaireWithCache(id, { includeRetired: true, viewerId });
      setForm(read.form);
      setCached(read.fromCache ? { at: read.cachedAt, version: read.cachedVersion ?? read.form.version } : null);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load that questionnaire");
    }
  }, [id, viewerId]);

  useEffect(() => {
    if (authLoading) return;
    load();
  }, [authLoading, load]);

  useEffect(() => {
    let cancelled = false;
    listEveryDesignWorkshop()
      .then((result) => {
        if (!cancelled) setWorkshopList({ kind: "ok", rows: result.items ?? [], total: result.total });
      })
      .catch(() => {
        if (cancelled) return;
        setWorkshopsOnline(!deviceLooksOffline());
        setWorkshopList({ kind: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const workshopRows = workshopList.kind === "ok" ? workshopList.rows : NO_WORKSHOP_ROWS;
  const storedWorkshop = useRecordOffPage<DwSummary>(
    "/design-workshops",
    form?.designWorkshopId ?? "",
    workshopRows
  );

  const workshopSet = useMemo(
    () =>
      designWorkshopOptions(workshopList, {
        group: true,
        offPage: { mode: "recover", row: storedWorkshop }
      }),
    [workshopList, storedWorkshop]
  );
  const workshopVoice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: workshopsOnline }),
    [workshopsOnline]
  );
  const workshopNotice =
    workshopListNotice(workshopList, workshopVoice) ||
    cappedListNotice(
      workshopList.kind === "ok"
        ? cutOf(workshopList.rows.length, workshopList.total ?? 0, "design workshops")
        : null
    );
  const workshops = workshopRows;

  const owns = canEditOwnOrAdmin(user, form?.ownerId);
  const attachedHold = useHeldPostRefusal(form?.designWorkshopId ?? null);
  const mayEdit = owns && attachedHold === null;
  const mayDelete = mayEdit && mayDeleteQuestionnaire(user, form?.ownerId);
  const v2 = Boolean(useQuestionnaireConfig()?.v2Enabled);
  const mayDeleteSittings = mayEdit && canDeleteRecords;

  async function deleteForm() {
    if (!form) return;
    const ok = await confirm(
      deleteConfirm(`Delete “${form.title}”?`, "Its questions and answers are kept.", "Its author or an admin can restore it.")
    );
    if (!ok) return;
    const done = await run(() => apiFetch(`/questionnaires/${id}`, { method: "DELETE" }), "Unable to delete this questionnaire");
    if (done !== null) router.push("/questionnaires");
  }

  async function deleteEntry(entryId: string, label: string) {
    const ok = await confirm(deleteConfirm(`Delete the answers from “${label}”?`, undefined, "An admin can restore them."));
    if (!ok) return;
    await run(async () => {
      await apiFetch(`/questionnaires/${id}/entries/${entryId}`, { method: "DELETE" });
      setForm((current) => (current ? { ...current, entries: current.entries.filter((entry) => entry.id !== entryId) } : current));
      setDeletedSittingsToken((token) => token + 1);
      return true;
    }, "Unable to delete those answers");
  }

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
    const data = new FormData(element);
    const title = String(data.get("title") ?? "").trim();
    if (!title) return;
    if (typeof navigator !== "undefined" && navigator.onLine === false) {
      queueSection(user?.id ?? null, id, { title, code: String(data.get("code") ?? "").trim() || undefined });
      element.reset();
      setSectionTitle("");
      setSectionFormOpen(false);
      return;
    }
    const next = await run(
      () =>
        createSection(id, {
          title,
          code: String(data.get("code") ?? "").trim() || undefined
        }),
      "Unable to add that section"
    );
    if (!next) return;
    element.reset();
    setSectionTitle("");
    setSectionFormOpen(false);
    await load();
  }

  async function addQuestion(sectionId: string, event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    const data = new FormData(element);
    const prompt = String(data.get("prompt") ?? "").trim();
    if (!prompt) return;
    if (typeof navigator !== "undefined" && navigator.onLine === false) {
      queueQuestion(user?.id ?? null, id, sectionId, {
        prompt,
        helpText: String(data.get("helpText") ?? "").trim() || undefined,
        isRequired: data.get("isRequired") === "on"
      });
      element.reset();
      setQuestionPrompt("");
      setQuestionHelp("");
      setAddingTo(null);
      return;
    }
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
    setQuestionPrompt("");
    setQuestionHelp("");
    setAddingTo(null);
    await load();
  }

  async function renameSection(sectionId: string, title: string) {
    const next = await run(() => patchSection(id, sectionId, { title }), "Unable to rename that section");
    if (next) setForm(next);
  }

  async function attachWorkshop(designWorkshopId: string) {
    const next = await run(
      () => patchQuestionnaire(id, { designWorkshopId: designWorkshopId || null }),
      "Couldn't change the workshop"
    );
    if (next) {
      setForm(next);
      setNotice(
        designWorkshopId
          ? "Attached to the design workshop."
          : "Detached. Answers are kept."
      );
    }
  }

  async function setKind(kind: string) {
    const next = await run(
      () => patchQuestionnaire(id, { kind: (kind || null) as QFormKind | null }),
      "Unable to change the kind of this questionnaire"
    );
    if (next) {
      setForm(next);
      setNotice(
        kind
          ? `Filed as ${questionnaireKindLabel(kind)}.`
          : "Kind cleared."
      );
    }
  }

  useEffect(() => {
    setRenameTitle(form?.title ?? "");
  }, [form?.title]);

  async function renameQuestionnaire(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const title = String(data.get("title") ?? "").trim();
    if (!title) return;
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
        `"${form.title}" is hidden and takes no new answers.`,
        "Answers are kept. You can bring it back."
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
          "Sections and questions."
        }
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <button type="button" className="field-button-secondary" onClick={downloadQuestions} disabled={busy}>
              <Share2 className="h-4 w-4" aria-hidden />
              Download question set
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setReuseOpen(true)} disabled={busy}>
              <CopyPlus className="h-4 w-4" aria-hidden />
              Reuse at another workshop
            </button>
            {owns ? (
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
            <DownloadBundleButton form={form} />
            <Link className="field-button" href={v2 ? answerFormTarget(id) : `/questionnaires/${id}/answer`}>
              {v2 ? "Start session" : "Record answers"}
            </Link>
            {mayDelete ? (
              <button type="button" data-danger className="field-danger" onClick={deleteForm} disabled={busy}>
                Delete
              </button>
            ) : null}
          </>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {cached ? (
        <div className="mb-4 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
          {cachedQuestionnaireNotice(formatDateTime(cached.at), cached.version)}
        </div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}
      {!form.isActive ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          Out of use. Existing answers are kept and exportable.
        </div>
      ) : null}
      {form.isShared ? (
        <div className="mb-4 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-sm leading-6 text-purple-900">
          The <strong className="font-semibold">standard questionnaire</strong>. You can record answers but not edit it.
        </div>
      ) : null}
      <HeldPostNotice
        refusal={
          !owns
            ? null
            : typeof attachedHold === "string"
              ? `Read-only: attached to a workshop you oversee. ${attachedHold}`
              : attachedHold
        }
        sayPending
      />
      {!owns ? (
        <div className="mb-4 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <Lock className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            Another designer&apos;s form. You can record answers, download the{" "}
            <span className="font-medium text-ink-900">question set</span>, or{" "}
            <button
              type="button"
              className="font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2 hover:text-purple-800 dark:hover:text-purple-200"
              onClick={() => setReuseOpen(true)}
            >
              reuse it into a copy of your own
            </button>{" "}
            and edit that.
          </span>
        </div>
      ) : null}

      {reused ? (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <span>
            <span className="font-medium text-ink-900">&ldquo;{reused.title}&rdquo;</span> now has these questions, without answers.
          </span>
          <Link className={rowAction("edit")} href={`/questionnaires/${reused.id}`}>
            Open the copy
          </Link>
        </div>
      ) : null}

      {report ? <UploadReport report={report} subject={reused?.title ?? null} className="mb-5" /> : null}

      <ArtefactNotice className="mb-5" />

      {mayEdit ? (
        <form onSubmit={renameQuestionnaire} className="panel mb-5 grid gap-4 p-4">
          <DictationUnavailableNotice />
          <div className="grid gap-3 md:grid-cols-2">
            <DictatedTextInput
              name="title"
              label="Title"
              required
              value={renameTitle}
              onChange={setRenameTitle}
              maxLength={220}
              explainWhenUnavailable={false}
            />
            <FieldBlock
              label="Design workshop"
              hint={
                workshopNotice ? (
                  <p className="mt-1 text-xs leading-5 text-ink-500" aria-live="polite">
                    {workshopNotice}
                  </p>
                ) : null
              }
            >
              <Dropdown
                value={form.designWorkshopId ?? ""}
                onChange={attachWorkshop}
                options={workshopSet.options}
                noneLabel={NO_DESIGN_WORKSHOP}
                emptyLabel={workshopEmptyLabel(workshopList, workshopVoice)}
                ariaLabel="Attach to a design workshop"
                searchable
                disabled={
                  busy || (workshopList.kind !== "loading" && workshopListStandsDown(workshopSet))
                }
                advanceOnSelect={false}
              />
            </FieldBlock>
            <FieldBlock
              label="Kind"
              hint={
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  Where the report files its answers.
                </p>
              }
            >
              <Dropdown
                value={form.kind ?? ""}
                onChange={setKind}
                options={QUESTIONNAIRE_KINDS.map((kind) => ({
                  value: kind,
                  label: QUESTIONNAIRE_KIND_LABELS[kind]
                }))}
                noneLabel="Not stated"
                ariaLabel="Kind"
                disabled={busy}
                advanceOnSelect={false}
              />
            </FieldBlock>
          </div>
          <DictatedTextArea
            key={`desc-${form.description ?? ""}`}
            name="description"
            label="Description"
            defaultValue={form.description ?? ""}
            explainWhenUnavailable={false}
          />
          <ActionGroup>
            <button className="field-button" disabled={busy}>
              Save details
            </button>
            {isContentAdmin(user) ? (
              <button
                type="button"
                className="field-button-secondary"
                disabled={busy}
                onClick={async () => {
                  const next = await run(
                    () => patchQuestionnaire(id, { isShared: !form.isShared }),
                    form.isShared
                      ? "Unable to withdraw this as the standard questionnaire"
                      : "Unable to publish this as the standard questionnaire"
                  );
                  if (next) setForm(next);
                }}
              >
                {form.isShared ? "Withdraw as the standard form" : "Publish as the standard form"}
              </button>
            ) : null}
            {form.isActive ? (
              <button type="button" data-danger className="field-danger" onClick={deactivate} disabled={busy}>
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
          </ActionGroup>
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
            <DictatedTextInput
              name="title"
              label="Section title"
              required
              value={sectionTitle}
              onChange={setSectionTitle}
              placeholder="Background"
              maxLength={220}
              explainWhenUnavailable={false}
            />
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
                  ? "Add a section above, or upload a pro-forma."
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
                  {!section.isActive ? " · retired" : ""}
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
                <DictatedTextInput
                  name="prompt"
                  label="Question"
                  required
                  value={questionPrompt}
                  onChange={setQuestionPrompt}
                  placeholder="How many looms do you own?"
                  maxLength={2000}
                  explainWhenUnavailable={false}
                />
                <DictatedTextInput
                  name="helpText"
                  label="Help text"
                  value={questionHelp}
                  onChange={setQuestionHelp}
                  placeholder="Shown under the question when answering"
                  explainWhenUnavailable={false}
                />
                <Toggle name="isRequired" label="Required" />
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
                    mayRemove={mayDelete}
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
              Each sitting is one filled-in copy.
            </p>
          </div>
          <Link className="field-button-secondary" href={`/questionnaires/${id}/answer`}>
            Record answers
          </Link>
        </div>
        {form.entries.length === 0 ? (
          <p className="text-sm text-ink-500">
            Nothing recorded yet. Use &ldquo;Record answers&rdquo; to start.
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
                      {entry.source === "UPLOAD" ? " · from the uploaded spreadsheet" : ""}
                    </p>
                  </div>
                  <RowActions>
                    <Link className={rowAction("edit")} href={`/questionnaires/${id}/answer?entry=${entry.id}`}>
                      Open
                    </Link>
                    {mayDeleteSittings ? (
                      <button
                        type="button"
                        data-danger
                        className={rowAction("danger")}
                        onClick={() => deleteEntry(entry.id, entry.respondentName || entry.title)}
                        disabled={busy}
                      >
                        Delete
                      </button>
                    ) : null}
                  </RowActions>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <PendingAuthoring formId={id} sections={form.sections.map((s) => ({ id: s.id, title: s.title }))} onLanded={() => void load()} />
      <FormAdminPanel questionnaireId={id} isDefault={Boolean(form.isDefault)} />
      <FormSessions questionnaireId={id} />

      {mayDeleteSittings && adminMode ? (
        <DeletedSittings questionnaireId={id} refreshToken={deletedSittingsToken} onRestored={() => void load()} />
      ) : null}

      <ReuseDialog
        open={reuseOpen}
        questionnaireId={id}
        sourceTitle={form.title}
        sourceWorkshopId={form.designWorkshopId}
        workshops={workshops.map((workshop) => ({
          id: workshop.id,
          title: workshop.title,
          status: workshop.status,
          craftName: workshop.craftName,
          clusterName: workshop.clusterName,
          state: workshop.state,
          startDate: workshop.startDate,
          createdAt: workshop.createdAt,
          deletedAt: workshop.deletedAt
        }))}
        workshopsNotice={workshopNotice}
        onClose={() => setReuseOpen(false)}
        onReused={(result) => {
          setReuseOpen(false);
          setReport(result.report);
          setReused({ id: result.questionnaire.id, title: result.questionnaire.title });
          toast({
            tone: "success",
            title: "Questionnaire reused",
            description: `"${result.questionnaire.title}" has ${result.questionnaire.questionCount} questions, without answers.`
          });
        }}
      />

      <UploadDialog
        open={uploadOpen}
        questionnaireId={id}
        onClose={() => setUploadOpen(false)}
        onUploaded={(result) => {
          setUploadOpen(false);
          setReport(result.report);
          setReused(null);
          setForm(result.questionnaire);
          void load();
          router.refresh();
        }}
      />
    </>
  );
}
