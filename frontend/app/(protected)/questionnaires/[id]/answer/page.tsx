"use client";

/**
 * RECORDING THE ANSWERS. The half of this feature the whole thing exists for.
 *
 * A designer downloads the pro-forma, types their questions into it, uploads it — and then answers
 * it here. THE UPLOADED SHEET MAY HAVE CARRIED NO ANSWERS AT ALL, and that is the ordinary case, not
 * the edge one: a designer who is about to run the interviews has an instrument, not data. So this
 * screen never assumes a sitting exists, never assumes a question has been answered before, and
 * starts a sitting from nothing in one press.
 *
 * WHAT A "SITTING" IS. One filled-in copy of the questionnaire — `QuestionnaireFormEntry` on the
 * server. Answers that arrived in the spreadsheet's answer columns are sittings too (`source:
 * "UPLOAD"`), stored in the same table as the ones typed here, which is why this screen needs no
 * special case for either and why a designer who typed six interviews into Excel can carry on with
 * the seventh in the app.
 *
 * SAVING IS BY SECTION, NOT BY FORM, and it is a PUT.
 *
 *   * By section because that is the unit a person finishes. A twelve-section instrument saved only
 *     at the end is a lost interview when the tab closes.
 *   * PUT because sending the same section twice must not produce two sets of answers.
 *     `@@unique([entryId, questionId])` makes that true at the database as well, and `save_answers`
 *     skips rows whose text did not change — so re-saving a section a designer merely walked back
 *     through writes nothing and does not re-stamp authorship on answers somebody else recorded.
 *
 * NOTHING LEAVES THIS SCREEN WITHOUT ASKING, and the reason is that three exits used to. "Each
 * section is saved as you move through it" was true of the two section buttons and of nothing else:
 * the round back arrow, the header's "Edit the questionnaire" link and — worst, because it does not
 * even navigate — the Sitting dropdown all discarded the section in progress without a word. So
 * every one of them now goes through `requestExit`, which is the only way to reach `leaveNow`, and
 * the tab-close path is covered by a `beforeunload` because no interceptor can see that one. If you
 * add a fourth exit, add it to `Exit` rather than wiring a handler of its own.
 *
 * RETIRED QUESTIONS ARE SHOWN AND NOT OFFERED. `save_answers` refuses them with a 422: a question
 * that was superseded or retired keeps the answers it already has and must never collect new ones,
 * or the wording a designer deliberately replaced quietly carries on gathering evidence. But the
 * answers it already has must still be READABLE here, or a sitting shows four answers to what was a
 * six-question form and looks like it lost two. So the form is loaded WITH retired questions, they
 * are drawn read-only, and they are excluded when the payload is built.
 */

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Archive, ChevronLeft, ChevronRight, ClipboardList, Plus, Save } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
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
  saveAnswers,
  type QForm,
  type QFormEntry,
  type QFormSection
} from "@/lib/questionnaireForms";

export default function AnswerQuestionnairePage() {
  return (
    // Next 16: `useSearchParams` must sit inside a Suspense boundary.
    <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading…</div>}>
      <AnswerPageBody />
    </Suspense>
  );
}

function AnswerPageBody() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const search = useSearchParams();
  const router = useRouter();
  const { toast } = useToast();
  const { user, loading: authLoading } = useAuth();

  const [form, setForm] = useState<QForm | null>(null);
  /**
   * Set only while the form on screen came out of this browser's storage because nothing could reach
   * the server. Null again the moment a live read succeeds.
   */
  const [cached, setCached] = useState<{ at: string | null; version: number } | null>(null);
  const [entryId, setEntryId] = useState<string>("");
  const [sectionIndex, setSectionIndex] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [starting, setStarting] = useState(false);
  const [startOpen, setStartOpen] = useState(false);

  /**
   * The text in the boxes, keyed by question id.
   *
   * Held in React state rather than read off the DOM at save time, because the section the designer
   * is leaving is the section being saved: by the time a "next" click has re-rendered, the inputs it
   * would have been read from are gone. Seeded from the sitting's stored answers whenever the sitting
   * changes, never on every render — retyping over somebody's answer as they type it is the failure
   * that shape prevents.
   */
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [notes, setNotes] = useState<Record<string, string>>({});
  /** Question ids whose box has been touched since the last successful save of their section. */
  const dirty = useRef<Set<string>>(new Set());
  /**
   * The size of that set, MIRRORED INTO STATE, and the mirror is not redundant.
   *
   * The ref is what `saveSection` reads, because it must see the very latest keystroke even when it
   * was called from a handler that closed over an older render. But a ref changing re-renders
   * nothing, so nothing derived from it can decide anything: `useLeaveGuard` takes a BOOLEAN and the
   * `beforeunload` listener is installed by an effect, and both of those need a value that changes
   * to notice that the first answer of a section has just been typed. Keeping the count in state is
   * what makes "is there unsaved work" answerable by something other than the save button.
   *
   * EVERY WRITE TO `dirty.current` MUST BE PAIRED WITH `syncDirty()`, and the pairing is now
   * enforced by a test rather than by this sentence, because the sentence alone did not hold.
   *
   * The first version of this mirror carried exactly this comment and still shipped broken: the two
   * writes that happen while a designer is actually WORKING — the answer box's `onChange` and the
   * note box's `onChange` — called `dirty.current.add(question.id)` and nothing else. The four
   * `syncDirty()` calls in the file all sat beside writes that EMPTY the set (the two seeding
   * branches, the post-save delete loop, Discard), so `dirtyCount` was 0 for the entire time a
   * section was being typed. `useLeaveGuard` was registered with `false` and the `beforeunload`
   * effect returned early, which meant the round back arrow and the tab close discarded a sitting's
   * answers exactly as they had before the guard was added — two of the four exits this whole
   * arrangement exists to close, unguarded behind a guard that looked present.
   *
   * `markAnswerDirty` is the answer to that: the per-question write and its mirror are ONE call, so
   * a handler cannot do half of it. Do not inline it back into the handlers "because it is two
   * lines" — the two lines coming apart is the defect. The bulk writes below still pair by hand
   * because they genuinely live at different times (the ref inside a `for` loop over a server
   * response, the state once at the end of it), which is why there is no single setter for those.
   */
  const [dirtyCount, setDirtyCount] = useState(0);
  const syncDirty = useCallback(() => setDirtyCount(dirty.current.size), []);
  /** Mark one question's box as touched — the ref for `saveSection`, the count for the guards. */
  const markAnswerDirty = useCallback(
    (questionId: string) => {
      dirty.current.add(questionId);
      syncDirty();
    },
    [syncDirty]
  );

  /**
   * WHAT THE DESIGNER ASKED TO DO WHILE THERE WAS UNSAVED WORK — the thing the prompt is about.
   *
   * Every exit from this screen used to be its own decision about typed answers, and all but two of
   * them decided the answers were expendable. The round back arrow navigated (nothing had registered
   * an interceptor). The header's "Edit the questionnaire" link navigated. The SITTING dropdown was
   * the worst of the three, because it did not even leave: it re-seeded the boxes from another
   * sitting's stored answers and emptied `dirty.current` in the same commit, so the eleven answers
   * typed in front of a respondent were gone AND the record of their having been typed was gone, and
   * a later press of "Save this section" sent nothing and said nothing (`saveSection` returns early
   * on an empty list, above the toast).
   *
   * So an exit is now a VALUE rather than a handler: the request is recorded here, the prompt goes
   * up, and the exit is performed by `leaveNow` only after Save or Discard has answered the question
   * the prompt asks. Null means no prompt is on screen.
   */
  type Exit =
    | { kind: "back" }
    /** Switch to another sitting — a move WITHIN this screen, which is why it needs its own kind. */
    | { kind: "sitting"; entryId: string }
    | { kind: "edit" };
  const [pendingExit, setPendingExit] = useState<Exit | null>(null);

  /**
   * WITH retired questions — see the file header. The read-only rows they produce are the only place
   * a previously-recorded answer to a retired question can still be seen.
   *
   * READ THROUGH THE CACHE. A designer standing in front of a respondent with no signal can now open
   * this screen and READ the questions they are about to ask, and read what has already been
   * recorded in this sitting. They still cannot SAVE — `saveSection` fails loudly and the banner
   * says so before the first question is asked, not after a section has been typed — because
   * whether a question may still be answered is a fact only the server holds.
   *
   * The stored copy is served only when nothing reached the server. A 403 (a revoked grant), a 404
   * and a 5xx all still reach the error banner: each of those is the server answering, and a copy
   * that contradicted it would be this screen inventing an entitlement.
   */
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

  /**
   * WAITS FOR THE SESSION. `load` depends on the viewer's id — the stored copy is stamped with whose
   * it is and refused to any other account on a shared field laptop — and `AuthProvider` resolves
   * `user` after mount, so without this guard the whole form, its sittings and every answer would be
   * fetched twice on every open.
   */
  useEffect(() => {
    if (authLoading) return;
    void load();
  }, [authLoading, load]);

  /**
   * Which sitting is open.
   *
   * `?entry=` wins when it names one that exists — that is the link from the detail page's list, and
   * honouring it is what makes "Open" mean this sitting rather than whichever happens to be first.
   * Otherwise nothing is auto-selected: picking a sitting for somebody would mean a designer who
   * meant to start a new interview types it into the last one instead, over answers that were
   * already there.
   */
  useEffect(() => {
    if (!form || entryId) return;
    const wanted = search.get("entry");
    if (wanted && form.entries.some((entry) => entry.id === wanted)) setEntryId(wanted);
  }, [form, entryId, search]);

  const entry: QFormEntry | null = useMemo(
    () => form?.entries.find((candidate) => candidate.id === entryId) ?? null,
    [form, entryId]
  );

  /**
   * Which sitting the boxes currently hold, so a re-read of the form does not retype them.
   *
   * `entry` is derived from `form`, so it is a NEW object every time the form is re-read — and every
   * successful save re-reads it. Seeding on each of those would overwrite whatever the designer has
   * typed into the next section while the save was in flight, which is a lost answer with nothing on
   * screen to say so. Seeding is therefore keyed on the sitting's ID: switching sittings re-seeds,
   * saving does not.
   */
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
    const seededText: Record<string, string> = {};
    const seededNotes: Record<string, string> = {};
    for (const answer of entry.answers) {
      seededText[answer.questionId] = answer.answerText ?? "";
      seededNotes[answer.questionId] = answer.notes ?? "";
    }
    setDraft(seededText);
    setNotes(seededNotes);
    // Emptying the set here is correct ONLY because nothing reaches this effect with unsaved work
    // any more: the sitting switch is gated by `requestExit` and every other route to a different
    // `entry.id` (the `?entry=` link, a freshly started sitting) begins with an empty set. It used
    // to be the line that destroyed the evidence — see `pendingExit`.
    dirty.current = new Set();
    syncDirty();
  }, [entry, syncDirty]);

  const sections = form?.sections ?? [];
  const section: QFormSection | null = sections[sectionIndex] ?? null;
  const progress = form && entry ? answeredCount(form, entry) : null;

  async function startSitting(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Built before any await: React nulls `currentTarget` across one.
    const data = new FormData(event.currentTarget);
    setStarting(true);
    setError(null);
    try {
      const made = await createEntry(id, {
        respondentName: String(data.get("respondentName") ?? "").trim() || undefined,
        title: String(data.get("title") ?? "").trim() || undefined,
        notes: String(data.get("notes") ?? "").trim() || undefined
      });
      // The create response is the raw row and carries no answers, so the form is re-read rather
      // than patched locally — that is also what puts the new sitting into the picker.
      await load();
      setEntryId(made.id);
      setSectionIndex(0);
      setStartOpen(false);
      // The URL is made to name the sitting so a reload, a bookmark or a back button all return to
      // the interview in progress rather than to an unchosen picker.
      router.replace(`/questionnaires/${id}/answer?entry=${made.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start a sitting");
    } finally {
      setStarting(false);
    }
  }

  /**
   * Save the section on screen.
   *
   * ONLY THE QUESTIONS THAT WERE TOUCHED are sent. The endpoint is idempotent and would ignore
   * unchanged rows anyway, but sending a whole section every time means a designer who opened a
   * sitting to read it and moved on writes rows against every question in it — and a blank row is
   * still a row, which changes nothing about what is answered but does put this user's id on
   * answers they did not give.
   */
  const saveSection = useCallback(
    async (target: QFormSection | null, options?: { silent?: boolean }): Promise<boolean> => {
      if (!target || !entryId) return true;
      const answers = answerableQuestions(target)
        .filter((question) => dirty.current.has(question.id))
        .map((question) => ({
          questionId: question.id,
          answerText: draft[question.id] ?? "",
          notes: notes[question.id]?.trim() ? notes[question.id] : null
        }));
      if (!answers.length) {
        /*
          A NO-OP THAT SAYS SO, because the wordless version was indistinguishable from a broken one.
          Audit 2026-08-15 (MINOR, frontend).

          This early return sat above `setSaving`, above the request and above the success toast, so
          pressing "Save this section" with nothing dirty produced NOTHING: no toast, no error, no
          spinner, no state change. That control is the one a designer presses when she is unsure —
          and "there was nothing to send" and "what you typed has already been discarded" looked
          identical, which removed the last chance to notice a loss while the respondent was still in
          the room.

          The SILENT callers are left silent on purpose. `goToSection` and "Finish this sitting" pass
          `{ silent: true }` and pass through here on every section a designer merely read; a toast
          on each of those is a toast nobody reads by the fourth section. The defect was only ever
          that the explicit button shared their wordless return.
        */
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
        await saveAnswers(id, entryId, answers);
        for (const answer of answers) dirty.current.delete(answer.questionId);
        // Only what was SENT is cleared, and only after the server took it — a question typed into
        // while the request was in flight is still unsaved, and the prompt must still know about it.
        syncDirty();
        // Re-read so the progress counter and the detail page's "N of M answered" agree with what
        // is stored rather than with what this tab believes it sent.
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
        // A 422 here is a real sentence written for the designer — "only the person who recorded
        // this answer, or an admin, can change it", or a question that has since been retired. It is
        // shown as a banner rather than a toast because it is something they must act on, and
        // `aria-live="polite"` never interrupts.
        setError(err instanceof Error ? err.message : "Unable to save those answers");
        return false;
      } finally {
        setSaving(false);
      }
    },
    [draft, notes, entryId, id, load, toast, syncDirty]
  );

  /**
   * Perform an exit. Called only when there is nothing unsaved, or from the prompt's Save/Discard.
   *
   * The sitting arm does exactly what the dropdown's `onChange` used to do inline; moving it here is
   * the whole fix, because it is now reachable ONLY through `requestExit`.
   */
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
      setEntryId(exit.entryId);
      setSectionIndex(0);
      router.replace(
        exit.entryId ? `/questionnaires/${id}/answer?entry=${exit.entryId}` : `/questionnaires/${id}/answer`
      );
    },
    [id, router]
  );

  /** Every exit goes through here: straight out when nothing is unsaved, otherwise via the prompt. */
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

  /**
   * Hands the prompt to the round back control in the page header, which is the same arrangement
   * every record form in the app uses. The boolean must be `dirtyCount > 0` and not
   * `dirty.current.size > 0`: the guard re-reads its inputs on every commit, and a ref that mutates
   * without one would leave it holding `false` for the whole first section a designer types.
   */
  useLeaveGuard(dirtyCount > 0, () => setPendingExit({ kind: "back" }));

  /**
   * The tab-close half, which no interceptor can cover — closing the tab never reaches React Router.
   * Written inline rather than through `useUnsavedChanges` (lib/forms.ts) because that hook owns its
   * own `dirty` state and this screen's dirty set is a ref maintained per question id; the same shape
   * `ProcessForm` uses for the same reason.
   */
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
    // Saving BEFORE moving is what makes "next" safe. A designer who fills a section in and presses
    // next has finished it; asking them to also press Save is how a section gets lost.
    const ok = await saveSection(section, { silent: true });
    if (!ok) return;
    setSectionIndex(nextIndex);
    // The section heading is above the fold on arrival rather than wherever the previous section's
    // scroll position left the page.
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
        description="One sitting is one filled-in copy of this questionnaire. Each section is saved as you move through it."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          // STILL A LINK, deliberately: it keeps the URL in the status bar, and a middle-click or a
          // ctrl-click still opens the questionnaire in a second tab, which leaves THIS tab and its
          // unsaved answers exactly where they were. Only the plain left-click — the one that takes
          // the answers with it — is held back for the prompt, and only while there is something to
          // lose. `BackButton`'s interceptor cannot cover this: it is a different element entirely.
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
      {/*
        BEFORE THE FIRST QUESTION IS ASKED, not after a section has been typed. This is the screen the
        cache exists for — a designer sitting with a respondent, reading the next question off a copy
        — and it is also the screen where the refusal to save offline costs the most, so the sentence
        that says both is at the top of it and not tucked beside the Save button.
      */}
      {cached ? (
        <div className="mb-4 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
          {cachedQuestionnaireNotice(formatDateTime(cached.at), cached.version)}
        </div>
      ) : null}
      {!form.isActive ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          This questionnaire has been taken out of use, so no new sittings can be started against it. Answers already
          recorded are still readable here.
        </div>
      ) : null}

      <section className="panel mb-5 grid gap-3 p-4">
        <div className="grid gap-3 md:grid-cols-[1fr_auto] md:items-end">
          <FieldBlock label="Sitting">
            <Dropdown
              value={entryId}
              // NOT `setEntryId` directly, and this is the finding this screen was reported for.
              // Changing `entryId` changes `entry`, which fires the seeding effect, which overwrites
              // every box from the OTHER sitting's stored answers and empties the dirty set — so a
              // designer who opened this dropdown merely to check what a respondent said in an
              // earlier interview lost the section she was in the middle of, silently. It is an exit
              // from the current sitting even though the URL barely changes, so it asks like one.
              onChange={(next) => requestExit({ kind: "sitting", entryId: next })}
              options={[
                { value: "", label: form.entries.length ? "Choose a sitting" : "No sittings recorded yet" },
                ...form.entries.map((candidate) => ({
                  value: candidate.id,
                  // Names the source, because a designer looking for the interview they typed in the
                  // app has to tell it from the ones that arrived in the spreadsheet's columns.
                  label: `${candidate.respondentName || candidate.title}${
                    candidate.source === "UPLOAD" ? " · from the spreadsheet" : ""
                  }`
                }))
              ]}
              ariaLabel="Sitting"
              searchable
              // This dropdown SWITCHES the screen it sits on rather than filling in a form field, so
              // focus must not jump to the next control.
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
              <Field label="Notes">
                <TextArea name="notes" placeholder="Where and when this was recorded, anything about the conditions" />
              </Field>
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
      </section>

      {!sections.length ? (
        <div className="panel p-4">
          <EmptyState
            title="This questionnaire has no questions yet"
            body="Add sections and questions on the edit screen, or upload a filled-in pro-forma, and then come back to record the answers."
          />
        </div>
      ) : !entry ? (
        <div className="panel p-4">
          <EmptyState
            title="No sitting open"
            body={
              form.entries.length
                ? "Choose a sitting above to carry on with it, or start a new one."
                : "Start a sitting above. It works whether or not the spreadsheet you uploaded already had answers in it."
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

          <ol className="grid gap-4">
            {section.questions.map((question, index) => {
              const stored = answersByQuestion(entry).get(question.id);
              if (!question.isActive) {
                // READ-ONLY, and drawn rather than hidden. The recorded answer is still evidence; the
                // question simply may not gather any more of it.
                return (
                  <li key={question.id} className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
                    <div className="flex items-start gap-2">
                      <Archive className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
                      <div className="min-w-0">
                        <p className="text-sm text-ink-700">
                          <span className="text-ink-500">{index + 1}.</span> {question.prompt}
                        </p>
                        <p className="mt-1 text-xs leading-5 text-ink-500">
                          This question has been retired, so it can no longer be answered. What was recorded against it
                          stays in the record.
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
                      maxLength={8000}
                      onChange={(event) => {
                        // THE KEYSTROKE THAT ARMS THE GUARDS. `markAnswerDirty`, never a bare
                        // `dirty.current.add(...)`: the ref alone re-renders nothing, so the back
                        // arrow and the tab close would both stay unguarded through the whole
                        // section being typed. See the note on `markAnswerDirty`.
                        markAnswerDirty(question.id);
                        setDraft((prev) => ({ ...prev, [question.id]: event.target.value }));
                      }}
                    />
                  </label>
                  <details className="text-xs text-ink-500">
                    <summary className="cursor-pointer">Add a note about this answer</summary>
                    <input
                      className="field-input mt-1"
                      value={notes[question.id] ?? ""}
                      placeholder="Context, uncertainty, who said it"
                      onChange={(event) => {
                        // A note is unsaved work too — it travels in the same payload as the answer
                        // and is lost by the same exits. Same helper, same reason.
                        markAnswerDirty(question.id);
                        setNotes((prev) => ({ ...prev, [question.id]: event.target.value }));
                      }}
                    />
                  </details>
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
            <div className="flex flex-wrap gap-2">
              <button type="button" className="field-button-secondary" disabled={saving} onClick={() => saveSection(section)}>
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
                    const ok = await saveSection(section, { silent: true });
                    if (!ok) return;
                    toast({
                      tone: "success",
                      title: "Answers recorded",
                      description: "Every section of this sitting has been saved. You can reopen it and carry on at any time."
                    });
                    router.push(`/questionnaires/${id}`);
                  }}
                >
                  Finish this sitting
                </button>
              )}
            </div>
          </footer>
        </section>
      ) : null}

      {/*
        ONE PROMPT FOR ALL FOUR EXITS — the back arrow, the header link, the sitting dropdown and
        (through `beforeunload`) the tab close. Four separate handlers is how three of them came to
        decide, independently and wrongly, that typed answers were expendable.

        Save runs the section save the "Save this section" button runs, NOT the silent one: if the
        server refuses — a 422 saying only the person who recorded an answer may change it, or that a
        question has since been retired — the banner is the whole point, and the exit is abandoned so
        the designer is looking at the section the message is about. Discard empties the dirty set
        first, because for the sitting switch the seeding effect is what runs next and it must not
        find work it would silently carry into the other sitting's boxes.
      */}
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
