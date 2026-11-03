"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";

import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { answerFormTarget } from "@/lib/questionnaireLinks";
import { Archive, ChevronLeft, ChevronRight, ClipboardList, Plus, Save } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { appendDictatedPhrase, clampToColumn } from "@/components/richtext/dictatedValue";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { InstrumentPicker, SHARED_INSTRUMENT } from "@/components/questionnaires/InstrumentPicker";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { formatDateTime } from "@/lib/format";
import { cachedQuestionnaireNotice, loadQuestionnaireWithCache } from "@/lib/questionnaireFormCache";
import {
  answerableQuestions,
  answeredCount,
  answersByQuestion,
  createEntry,
  patchEntry,
  saveAnswers,
  type QForm,
  type QFormEntry,
  type QFormSection
} from "@/lib/questionnaireForms";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { isContentAdmin } from "@/lib/permissions";
import {
  blankRequiredIds,
  LOCKED_ANSWER_NOTE,
  lockedQuestionIds,
  mayEditSitting,
  rejectedBanner,
  rejectionsByQuestion,
  REQUIRED_ANSWER_NOTE,
  requiredBanner
} from "@/lib/sittingAnswers";

const ANSWER_MAX = 8000;

export default function AnswerQuestionnairePage() {
  return (
    <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading…</div>}>
      <AnswerOrSession />
    </Suspense>
  );
}

function AnswerOrSession() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const config = useQuestionnaireConfig();
  const v2 = Boolean(config?.v2Enabled);
  useEffect(() => {
    if (v2) router.replace(answerFormTarget(params.id));
  }, [v2, params.id, router]);
  if (config === null || v2) return <div className="panel p-4 text-sm text-ink-500">Loading…</div>;
  return <AnswerPageBody />;
}

function AnswerPageBody() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const search = useSearchParams();
  const router = useRouter();
  const { toast } = useToast();
  const { user, loading: authLoading } = useAuth();

  const [form, setForm] = useState<QForm | null>(null);
  const [cached, setCached] = useState<{ at: string | null; version: number } | null>(null);
  const [entryId, setEntryId] = useState<string>("");
  const [sectionIndex, setSectionIndex] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [starting, setStarting] = useState(false);
  const [startOpen, setStartOpen] = useState(false);

  const [draft, setDraft] = useState<Record<string, string>>({});
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [rejections, setRejections] = useState<Record<string, string>>({});
  const [missingRequired, setMissingRequired] = useState<Set<string>>(new Set());
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [savingDetails, setSavingDetails] = useState(false);
  const dirty = useRef<Set<string>>(new Set());
  const [dirtyCount, setDirtyCount] = useState(0);
  const syncDirty = useCallback(() => setDirtyCount(dirty.current.size), []);
  const markAnswerDirty = useCallback(
    (questionId: string) => {
      dirty.current.add(questionId);
      syncDirty();
    },
    [syncDirty]
  );

  type Exit =
    | { kind: "back" }
    | { kind: "sitting"; entryId: string }
    | { kind: "instrument"; target: string }
    | { kind: "edit" };
  const [pendingExit, setPendingExit] = useState<Exit | null>(null);

  const viewerId = user?.id ?? null;
  const load = useCallback(async () => {
    try {
      const read = await loadQuestionnaireWithCache(id, { includeRetired: true, viewerId });
      setForm(read.form);
      setCached(read.fromCache ? { at: read.cachedAt, version: read.cachedVersion ?? read.form.version } : null);
      setError(null);
      return read.form;
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load that questionnaire");
      return null;
    }
  }, [id, viewerId]);

  useEffect(() => {
    if (authLoading) return;
    void load();
  }, [authLoading, load]);

  useEffect(() => {
    if (!form || entryId) return;
    const wanted = search.get("entry");
    if (wanted && form.entries.some((entry) => entry.id === wanted)) setEntryId(wanted);
  }, [form, entryId, search]);

  const entry: QFormEntry | null = useMemo(
    () => form?.entries.find((candidate) => candidate.id === entryId) ?? null,
    [form, entryId]
  );

  const seededFor = useRef<string | null>(null);

  useEffect(() => {
    if (!entry) {
      if (seededFor.current === null) return;
      seededFor.current = null;
      setDraft({});
      setNotes({});
      dirty.current = new Set();
      syncDirty();
      return;
    }
    if (seededFor.current === entry.id) return;
    seededFor.current = entry.id;
    setRejections({});
    setMissingRequired(new Set());
    setDetailsOpen(false);
    const seededText: Record<string, string> = {};
    const seededNotes: Record<string, string> = {};
    for (const answer of entry.answers) {
      seededText[answer.questionId] = answer.answerText ?? "";
      seededNotes[answer.questionId] = answer.notes ?? "";
    }
    setDraft(seededText);
    setNotes(seededNotes);
    dirty.current = new Set();
    syncDirty();
  }, [entry, syncDirty]);

  const sections = form?.sections ?? [];
  const section: QFormSection | null = sections[sectionIndex] ?? null;
  const progress = form && entry ? answeredCount(form, entry) : null;
  const contentAdmin = isContentAdmin(user);
  const locked = useMemo(
    () => lockedQuestionIds(entry?.answers ?? [], user?.id ?? null, contentAdmin),
    [entry, user?.id, contentAdmin]
  );
  const canEditSitting =
    !!entry &&
    !!form &&
    mayEditSitting({
      viewerId: user?.id ?? null,
      entryCreatedById: entry.createdById,
      formOwnerId: form.ownerId,
      isContentAdmin: contentAdmin
    });

  async function saveSittingDetails(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    if (!entry) return;
    setSavingDetails(true);
    setError(null);
    try {
      const title = String(data.get("title") ?? "").trim();
      await patchEntry(id, entry.id, {
        respondentName: String(data.get("respondentName") ?? "").trim() || null,
        ...(title ? { title } : {}),
        notes: String(data.get("notes") ?? "").trim() || null
      });
      await load();
      setDetailsOpen(false);
      toast({ tone: "success", title: "Sitting details saved" });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save the sitting details");
    } finally {
      setSavingDetails(false);
    }
  }

  async function startSitting(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setStarting(true);
    setError(null);
    try {
      const made = await createEntry(id, {
        respondentName: String(data.get("respondentName") ?? "").trim() || undefined,
        title: String(data.get("title") ?? "").trim() || undefined,
        notes: String(data.get("notes") ?? "").trim() || undefined
      });
      await load();
      setEntryId(made.id);
      setSectionIndex(0);
      setStartOpen(false);
      router.replace(`/questionnaires/${id}/answer?entry=${made.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start a sitting");
    } finally {
      setStarting(false);
    }
  }

  const saveSection = useCallback(
    async (
      target: QFormSection | null,
      options?: { silent?: boolean; requireComplete?: boolean }
    ): Promise<boolean> => {
      if (!target || !entryId) return true;
      if (options?.requireComplete) {
        const blank = blankRequiredIds(target.questions, draft);
        setMissingRequired(new Set(blank));
        if (blank.length) {
          setError(requiredBanner(blank.length));
          return false;
        }
      }
      const answers = answerableQuestions(target)
        .filter((question) => dirty.current.has(question.id) && !locked.has(question.id))
        .map((question) => ({
          questionId: question.id,
          answerText: draft[question.id] ?? "",
          notes: notes[question.id]?.trim() ? notes[question.id] : null
        }));
      if (!answers.length) {
        if (!options?.silent) {
          toast({
            tone: "info",
            title: `Nothing new to save in "${target.title}"`,
            description: "Every answer in this section is already recorded."
          });
        }
        return true;
      }

      setSaving(true);
      setError(null);
      try {
        const result = await saveAnswers(id, entryId, answers);
        const refused = rejectionsByQuestion(result?.saved?.rejected);
        for (const answer of answers) {
          if (refused[answer.questionId]) continue;
          dirty.current.delete(answer.questionId);
          syncDirty();
        }
        syncDirty();
        setRejections((current) => {
          const next = { ...current };
          for (const answer of answers) delete next[answer.questionId];
          return { ...next, ...refused };
        });
        const refusedCount = Object.keys(refused).length;
        if (refusedCount) {
          await load();
          setError(rejectedBanner(refusedCount));
          return false;
        }
        await load();
        if (!options?.silent) {
          toast({
            tone: "success",
            title: `Saved "${target.title}"`,
            description: `${answers.length} ${answers.length === 1 ? "answer" : "answers"} recorded.`
          });
        }
        return true;
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to save those answers");
        return false;
      } finally {
        setSaving(false);
      }
    },
    [draft, notes, entryId, id, load, toast, syncDirty, locked]
  );

  const leaveNow = useCallback(
    (exit: Exit) => {
      if (exit.kind === "back") {
        router.back();
        return;
      }
      if (exit.kind === "edit") {
        router.push(`/questionnaires/${id}`);
        return;
      }
      if (exit.kind === "instrument") {
        router.push(exit.target === SHARED_INSTRUMENT ? "/questionnaire" : `/questionnaires/${exit.target}/answer`);
        return;
      }
      setEntryId(exit.entryId);
      setSectionIndex(0);
      router.replace(
        exit.entryId ? `/questionnaires/${id}/answer?entry=${exit.entryId}` : `/questionnaires/${id}/answer`
      );
    },
    [id, router]
  );

  const requestExit = useCallback(
    (exit: Exit) => {
      if (dirty.current.size === 0) {
        leaveNow(exit);
        return;
      }
      setPendingExit(exit);
    },
    [leaveNow]
  );

  useLeaveGuard(dirtyCount > 0, () => setPendingExit({ kind: "back" }));

  useEffect(() => {
    if (dirtyCount === 0) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirtyCount]);

  async function goToSection(nextIndex: number) {
    const ok = await saveSection(section, { silent: true, requireComplete: nextIndex > sectionIndex });
    if (!ok) return;
    setSectionIndex(nextIndex);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  if (error && !form) {
    return (
      <>
        <PageHeader title="Record answers" icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      </>
    );
  }

  if (!form) {
    return (
      <>
        <PageHeader title="Record answers" icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
        <div className="panel p-4 text-sm text-ink-700">Loading…</div>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={`Record answers · ${form.title}`}
        description="Each section saves as you go."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <Link
            className="field-button-secondary"
            href={`/questionnaires/${id}`}
            onClick={(event) => {
              if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return;
              if (dirty.current.size === 0) return;
              event.preventDefault();
              setPendingExit({ kind: "edit" });
            }}
          >
            Edit the questionnaire
          </Link>
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
      {!form.isActive ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          Out of use — no new sittings. Existing answers are kept.
        </div>
      ) : null}

      <section className="panel mb-5 grid gap-3 p-4">
        <div className="mb-3 max-w-xl">
          <InstrumentPicker
            value={id}
            allowed
            hint="The shared questionnaire is on Take interview."
            onChange={(next) => {
              if (next === id) return;
              requestExit({ kind: "instrument", target: next });
            }}
          />
        </div>

        <div className="grid gap-3 md:grid-cols-[1fr_auto] md:items-end">
          <FieldBlock label="Sitting">
            <Dropdown
              value={entryId}
              onChange={(next) => requestExit({ kind: "sitting", entryId: next })}
              options={[
                { value: "", label: form.entries.length ? "Choose a sitting" : "No sittings recorded yet" },
                ...form.entries.map((candidate) => ({
                  value: candidate.id,
                  label: `${candidate.respondentName || candidate.title}${
                    candidate.source === "UPLOAD" ? " · from the spreadsheet" : ""
                  }`
                }))
              ]}
              ariaLabel="Sitting"
              searchable
              advanceOnSelect={false}
            />
          </FieldBlock>
          {form.isActive ? (
            <button type="button" className="field-button" onClick={() => setStartOpen((open) => !open)}>
              <Plus className="h-4 w-4" aria-hidden />
              {startOpen ? "Close" : "Start a new sitting"}
            </button>
          ) : null}
        </div>

        {startOpen && form.isActive ? (
          <form onSubmit={startSitting} className="grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3 md:grid-cols-2">
            <Field label="Who is answering">
              <TextInput name="respondentName" maxLength={220} placeholder="Ramesh Kumar" />
            </Field>
            <Field label="Label for this sitting">
              <TextInput name="title" maxLength={220} placeholder="Defaults to the name above" />
            </Field>
            <div className="md:col-span-2">
              <DictatedTextArea
                name="notes"
                label="Notes"
                helper="Where, when and conditions"
                explainWhenUnavailable={false}
              />
            </div>
            <div className="md:col-span-2">
              <button className="field-button" disabled={starting}>
                {starting ? "Starting…" : "Start recording answers"}
              </button>
            </div>
          </form>
        ) : null}

        {entry ? (
          <p className="text-xs text-ink-500">
            {progress ? `${progress.answered} of ${progress.total} answered` : null}
            {entry.createdByName ? ` · started by ${entry.createdByName}` : ""}
            {entry.createdAt ? ` · ${formatDateTime(entry.createdAt)}` : ""}
          </p>
        ) : null}
        {entry && canEditSitting && !cached ? (
          detailsOpen ? (
            <form
              key={entry.id}
              onSubmit={saveSittingDetails}
              className="grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3 md:grid-cols-2"
            >
              <Field label="Who is answering">
                <TextInput name="respondentName" maxLength={220} defaultValue={entry.respondentName ?? ""} />
              </Field>
              <Field label="Label for this sitting">
                <TextInput name="title" maxLength={220} defaultValue={entry.title} />
              </Field>
              <div className="md:col-span-2">
                <DictatedTextArea name="notes" label="Notes" defaultValue={entry.notes ?? ""} explainWhenUnavailable={false} />
              </div>
              <div className="md:col-span-2">
                <ActionGroup>
                  <button type="button" className="field-button-secondary" onClick={() => setDetailsOpen(false)}>
                    Cancel
                  </button>
                  <button className="field-button" disabled={savingDetails}>
                    {savingDetails ? "Saving…" : "Save details"}
                  </button>
                </ActionGroup>
              </div>
            </form>
          ) : (
            <ActionGroup>
              <button type="button" className="field-button-secondary" onClick={() => setDetailsOpen(true)}>
                Edit sitting details
              </button>
            </ActionGroup>
          )
        ) : null}
      </section>

      {!sections.length ? (
        <div className="panel p-4">
          <EmptyState
            title="This questionnaire has no questions yet"
            body="Add questions first, or upload a pro-forma."
          />
        </div>
      ) : !entry ? (
        <div className="panel p-4">
          <EmptyState
            title="No sitting open"
            body={
              form.entries.length
                ? "Choose a sitting above, or start a new one."
                : "Start a sitting above."
            }
          />
        </div>
      ) : section ? (
        <section className="panel grid gap-4 p-4">
          <header className="flex flex-wrap items-baseline justify-between gap-2">
            <div>
              <p className="eyebrow">
                Section {sectionIndex + 1} of {sections.length}
              </p>
              <h2 className="font-display text-lg font-bold text-ink-900">{section.title}</h2>
            </div>
            <p className="text-xs text-ink-500">Saved automatically when you move to another section.</p>
          </header>

          <DictationUnavailableNotice />

          <ol className="grid gap-4">
            {section.questions.map((question, index) => {
              const stored = answersByQuestion(entry).get(question.id);
              if (!question.isActive) {
                return (
                  <li key={question.id} className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
                    <div className="flex items-start gap-2">
                      <Archive className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
                      <div className="min-w-0">
                        <p className="text-sm text-ink-700">
                          <span className="text-ink-500">{index + 1}.</span> {question.prompt}
                        </p>
                        <p className="mt-1 text-xs leading-5 text-ink-500">
                          Retired. Existing answers are kept.
                        </p>
                        {stored?.answerText ? (
                          <p className="mt-2 rounded-md border border-line-200 bg-card px-3 py-2 text-sm text-ink-900">
                            {stored.answerText}
                          </p>
                        ) : null}
                      </div>
                    </div>
                  </li>
                );
              }
              const isLocked = locked.has(question.id);
              const questionNote = rejections[question.id]
                ? rejections[question.id]
                : missingRequired.has(question.id) && !(draft[question.id] ?? "").trim()
                  ? REQUIRED_ANSWER_NOTE
                  : null;
              return (
                <li key={question.id} className="grid gap-1">
                  <label className="grid gap-1">
                    <span className="text-sm font-medium text-ink-900">
                      <span className="text-ink-500">{index + 1}.</span> {question.prompt}
                      {question.isRequired ? <span className="ml-1 text-error-600">*</span> : null}
                    </span>
                    {question.helpText ? <span className="text-xs leading-5 text-ink-500">{question.helpText}</span> : null}
                    <textarea
                      className="field-input min-h-16"
                      value={draft[question.id] ?? ""}
                      maxLength={ANSWER_MAX}
                      readOnly={isLocked}
                      aria-invalid={questionNote ? true : undefined}
                      aria-describedby={
                        [
                          (draft[question.id] ?? "").length >= ANSWER_MAX ? `${question.id}-full` : null,
                          isLocked ? `${question.id}-locked` : null,
                          questionNote ? `${question.id}-problem` : null
                        ]
                          .filter(Boolean)
                          .join(" ") || undefined
                      }
                      onChange={(event) => {
                        markAnswerDirty(question.id);
                        setDraft((prev) => ({ ...prev, [question.id]: event.target.value }));
                      }}
                    />
                  </label>
                  {isLocked ? (
                    <p id={`${question.id}-locked`} className="text-xs leading-5 text-ink-500">
                      {LOCKED_ANSWER_NOTE}
                    </p>
                  ) : null}
                  {questionNote ? (
                    <p id={`${question.id}-problem`} className="text-xs leading-5 text-error-600">
                      {questionNote}
                    </p>
                  ) : null}
                  {isLocked ? null : (
                  <OnDeviceDictationButton
                    fieldLabel={`answer ${index + 1}`}
                    explainWhenUnavailable={false}
                    onCommit={(phrase) => {
                      markAnswerDirty(question.id);
                      setDraft((prev) => ({
                        ...prev,
                        [question.id]: clampToColumn(appendDictatedPhrase(prev[question.id] ?? "", phrase), ANSWER_MAX)
                      }));
                    }}
                  />
                  )}
                  {(draft[question.id] ?? "").length >= ANSWER_MAX ? (
                    <p id={`${question.id}-full`} className="text-xs leading-5 text-ink-500">
                      Full — {ANSWER_MAX.toLocaleString("en-IN")} characters maximum.
                    </p>
                  ) : null}
                  {isLocked ? null : (
                  <details className="text-xs text-ink-500">
                    <summary className="cursor-pointer">Add a note about this answer</summary>
                    <input
                      className="field-input mt-1"
                      value={notes[question.id] ?? ""}
                      placeholder="Context, uncertainty, who said it"
                      aria-label={`Note about answer ${index + 1}`}
                      onChange={(event) => {
                        markAnswerDirty(question.id);
                        setNotes((prev) => ({ ...prev, [question.id]: event.target.value }));
                      }}
                    />
                    <OnDeviceDictationButton
                      fieldLabel={`the note about answer ${index + 1}`}
                      explainWhenUnavailable={false}
                      onCommit={(phrase) => {
                        markAnswerDirty(question.id);
                        setNotes((prev) => ({
                          ...prev,
                          [question.id]: appendDictatedPhrase(prev[question.id] ?? "", phrase)
                        }));
                      }}
                    />
                  </details>
                  )}
                </li>
              );
            })}
          </ol>

          <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-line-200 pt-3">
            <button
              type="button"
              className="field-button-secondary"
              disabled={sectionIndex === 0 || saving}
              onClick={() => goToSection(sectionIndex - 1)}
            >
              <ChevronLeft className="h-4 w-4" aria-hidden />
              Previous section
            </button>
            <ActionGroup>
              <button
                type="button"
                className="field-button-secondary"
                disabled={saving}
                onClick={() => saveSection(section, { requireComplete: true })}
              >
                <Save className="h-4 w-4" aria-hidden />
                {saving ? "Saving…" : "Save this section"}
              </button>
              {sectionIndex < sections.length - 1 ? (
                <button type="button" className="field-button" disabled={saving} onClick={() => goToSection(sectionIndex + 1)}>
                  Next section
                  <ChevronRight className="h-4 w-4" aria-hidden />
                </button>
              ) : (
                <button
                  type="button"
                  className="field-button"
                  disabled={saving}
                  onClick={async () => {
                    const ok = await saveSection(section, { silent: true, requireComplete: true });
                    if (!ok) return;
                    toast({
                      tone: "success",
                      title: "Answers recorded",
                      description: "All sections saved."
                    });
                    router.push(`/questionnaires/${id}`);
                  }}
                >
                  Finish this sitting
                </button>
              )}
            </ActionGroup>
          </footer>
        </section>
      ) : null}

      <UnsavedChangesDialog
        open={pendingExit !== null}
        saving={saving}
        onKeepEditing={() => setPendingExit(null)}
        onDiscard={() => {
          const exit = pendingExit;
          dirty.current = new Set();
          syncDirty();
          setPendingExit(null);
          if (exit) leaveNow(exit);
        }}
        onSave={() => {
          const exit = pendingExit;
          void (async () => {
            const ok = await saveSection(section, { silent: true });
            setPendingExit(null);
            if (!ok || !exit) return;
            leaveNow(exit);
          })();
        }}
      />
    </>
  );
}
