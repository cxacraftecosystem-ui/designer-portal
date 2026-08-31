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

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { ClipboardList, CopyPlus, Download, FileSpreadsheet, Lock, Plus, Share2, Upload } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { ArtefactNotice } from "@/components/questionnaires/ArtefactNotice";
import { cappedListNotice, cutOf } from "@/components/data/cappedList";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { ReuseDialog } from "@/components/questionnaires/ReuseDialog";
import { UploadDialog } from "@/components/questionnaires/UploadDialog";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { QuestionRow } from "@/components/questionnaires/QuestionRow";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { listDesignWorkshops, saveBlobToDisk, type DwSummary } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import { canEditOwnOrAdmin, isAdmin } from "@/lib/permissions";
import { cachedQuestionnaireNotice, loadQuestionnaireWithCache } from "@/lib/questionnaireFormCache";
import {
  designWorkshopOptions,
  deviceLooksOffline,
  NO_DESIGN_WORKSHOP,
  WORKSHOP_OPTION_PAGE_SIZE,
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

/**
 * "The read has not answered, or it failed", as ONE stable array.
 *
 * `useRecordOffPage` has `rows` in its dependency list, so a fresh `[]` per render would re-run its
 * effect on every keystroke elsewhere on this page.
 */
const NO_WORKSHOP_ROWS: readonly DwSummary[] = [];

export default function QuestionnaireDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const confirm = useConfirm();
  const { toast } = useToast();

  const [form, setForm] = useState<QForm | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * What the design-workshop read answered — three states, and `[]` was never one of them.
   *
   * It was `useState<DwSummary[]>([])` behind a `.catch(() => undefined)`, so a timeout drew exactly
   * like an account with no workshops: a picker offering only "Not attached to a workshop", on the
   * page whose whole job at that moment is to say which workshop this questionnaire belongs to.
   */
  const [workshopList, setWorkshopList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  /** Was the device reachable when that read FAILED? Captured in the catch — see
   *  `components/forms/DesignWorkshopSelect.tsx` for why it is not read at render time. */
  const [workshopsOnline, setWorkshopsOnline] = useState(true);
  const [busy, setBusy] = useState(false);
  const [addingTo, setAddingTo] = useState<string | null>(null);
  const [sectionFormOpen, setSectionFormOpen] = useState(false);
  /*
    ── THE FIVE DICTATED BOXES ON THIS PAGE ARE CONTROLLED, BY `DictatedTextInput`'S CONTRACT ─────

    Its header refuses an uncontrolled mode in as many words — "one mode, not two, because a control
    that is sometimes controlled is a control whose reset behaviour has to be re-derived at every
    call site" — so the value lives up here. The boxes still render a real `name`, so `addSection`,
    `addQuestion` and `renameQuestionnaire` keep reading `FormData` off the form element and none of
    them changed shape.

    EACH IS CLEARED WHERE THE `element.reset()` IT REPLACED USED TO CLEAR IT, and that is the whole
    reason this is not a one-line swap: `form.reset()` rewrites the DOM node and tells React nothing,
    so a box React still believes holds text is re-painted with that text on the very next render.
    `addSection` and `addQuestion` both call it; the rename form does not, and re-seeds from the
    server instead — see the effect below.
  */
  const [sectionTitle, setSectionTitle] = useState("");
  const [questionPrompt, setQuestionPrompt] = useState("");
  const [questionHelp, setQuestionHelp] = useState("");
  const [renameTitle, setRenameTitle] = useState("");
  const [uploadOpen, setUploadOpen] = useState(false);
  const [report, setReport] = useState<QFormUploadReport | null>(null);
  const [reuseOpen, setReuseOpen] = useState(false);
  /**
   * The copy the reuse just made, kept so this page can hand over a LINK to it.
   *
   * A toast cannot: it is plain text on a timer. The copy is the thing the designer wants next — it
   * is the one they will be recording answers into — and finishing the reuse by leaving them on the
   * original with no route to the new row is how a designer presses the button a second time.
   */
  const [reused, setReused] = useState<{ id: string; title: string } | null>(null);
  /**
   * Set only while the form on screen came out of this browser's storage because nothing could
   * reach the server. Null the moment a live read succeeds — a copy that stopped being a copy must
   * stop saying it is one.
   */
  const [cached, setCached] = useState<{ at: string | null; version: number } | null>(null);

  /**
   * `includeRetired` is TRUE here and that is the point of the editor.
   *
   * A retired question has to stay visible where its answers are read — a form that hid them would
   * show a designer four questions and six answers, with nothing saying where the other two went.
   * The answer screen makes the opposite choice for the boxes it offers; both are the same rule read
   * from two sides.
   *
   * READ THROUGH THE CACHE, so a designer with no signal can still open a colleague's instrument and
   * read the questions. `loadQuestionnaireWithCache` serves a stored copy ONLY when nothing reached
   * the server: a 403, a 404 and a 5xx all still land in the error banner below, because each of
   * those is the server answering and answering something a stale copy would contradict. Everything
   * on this page that WRITES still needs the network and still fails loudly when it is not there.
   */
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

  /**
   * WAITS FOR THE SESSION, and that wait is what stops the read happening twice.
   *
   * `load` depends on the viewer's id, because the stored copy is stamped with whose it is and is
   * refused to any other account. `AuthProvider` resolves `user` from `GET /me` after mount, so
   * without this guard the page would read the questionnaire once with no id — a read that can
   * neither store its answer nor be served from storage — and then read the whole form, its sittings
   * and every answer a second time the moment the session landed.
   */
  useEffect(() => {
    if (authLoading) return;
    load();
  }, [authLoading, load]);

  useEffect(() => {
    let cancelled = false;
    // NEVER 100 INTO A CONTROL THAT DRAWS 80 — one number governs the fetch and the render, so two
    // truncation sentences with two different totals cannot both be true at once.
    listDesignWorkshops({ pageSize: WORKSHOP_OPTION_PAGE_SIZE })
      .then((result) => {
        if (!cancelled) setWorkshopList({ kind: "ok", rows: result.items ?? [], total: result.total });
      })
      // Still no banner — the read is a convenience and a failed convenience must not read as the
      // page being broken — but no longer SILENT: the failure is recorded and the sentence goes on
      // the control it is about.
      .catch(() => {
        if (cancelled) return;
        setWorkshopsOnline(!deviceLooksOffline());
        setWorkshopList({ kind: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * THIS QUESTIONNAIRE'S OWN WORKSHOP, FETCHED BY ID WHEN THE PAGE DOES NOT HOLD IT.
   *
   * The list is at most eighty rows ordered `createdAt desc`. A questionnaire attached last season
   * points at a workshop nowhere near them, and this picker used to draw its trigger on a value with
   * no matching option — which `SearchableSelect` renders as the placeholder, so the field read
   * "Not attached to a workshop" over a questionnaire that WAS attached, on the one control that
   * writes that attachment. One press of anything else and the link is gone.
   *
   * Called before the page's `if (!form)` return, because a hook may not be conditional; an empty id
   * is a no-op inside the hook.
   */
  const workshopRows = workshopList.kind === "ok" ? workshopList.rows : NO_WORKSHOP_ROWS;
  const storedWorkshop = useRecordOffPage<DwSummary>(
    "/design-workshops",
    form?.designWorkshopId ?? "",
    workshopRows
  );

  /**
   * The rows, and `offPage: "recover"` — the opposite answer from the list page's create form, and
   * the reason the parameter is required rather than defaulted.
   *
   * This control describes a read that is ALREADY TRUE: the questionnaire is attached to that
   * workshop, the fact is printed on this page, and withholding the row does not withhold anything —
   * it turns a read-only fact into a wrong write, because a picker that cannot draw its own current
   * value invites somebody to "fix" it by choosing another. The create form on `/questionnaires`
   * passes `"refuse"` because a questionnaire that does not exist yet has nothing to recover.
   */
  const workshopSet = useMemo(
    () =>
      designWorkshopOptions(workshopList, {
        group: true,
        offPage: { mode: "recover", row: storedWorkshop }
      }),
    [workshopList, storedWorkshop]
  );
  /** SCOPED — `list_design_workshops` narrows by `visible_to_clause`, so an empty answer is about
   *  this account's grants and its next move is an administrator, not a new workshop. */
  const workshopVoice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: workshopsOnline }),
    [workshopsOnline]
  );
  /** ONE SLOT, TWO SENTENCES THAT CANNOT BOTH APPLY — which of the four empty states this is, or the
   *  numbered cut. Handed down to the reuse dialog so the two controls cannot word one read twice. */
  const workshopNotice =
    workshopListNotice(workshopList, workshopVoice) ||
    cappedListNotice(
      workshopList.kind === "ok"
        ? cutOf(workshopList.rows.length, workshopList.total ?? 0, "design workshops")
        : null
    );
  /** The rows themselves, for the reuse dialog, which takes an answer rather than a read. */
  const workshops = workshopRows;

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
    // `reset()` clears the CODE box beside it, which is still uncontrolled; the title is React's.
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
    // `reset()` clears the "Required" tick; these two are React's.
    setQuestionPrompt("");
    setQuestionHelp("");
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

  /**
   * Set or clear the questionnaire's KIND.
   *
   * SAVES ON SELECT, like `attachWorkshop` above and unlike the title box, because it is a pick and
   * not a phrase — there is nothing to proofread before committing it, and a designer who chose a
   * kind and navigated away without pressing "Save details" would have chosen nothing.
   *
   * THE BLANK ROW TRAVELS AS AN EXPLICIT `null`, the same asymmetry the workshop dropdown has and
   * for the same mechanism: `clean_data` drops keys, and `kind` is named in the route's
   * `_QUESTIONNAIRE_CLEARABLE_COLUMNS` so the null survives it and genuinely clears the column.
   * `undefined` would leave the old kind standing and the dropdown would spring back on the next
   * load with no error anywhere.
   */
  async function setKind(kind: string) {
    const next = await run(
      () => patchQuestionnaire(id, { kind: (kind || null) as QFormKind | null }),
      "Unable to change the kind of this questionnaire"
    );
    if (next) {
      setForm(next);
      setNotice(
        kind
          ? `Filed as ${questionnaireKindLabel(kind)}. The report puts its answers under that stage.`
          : "Kind cleared. The report leaves its answers unfiled until a kind is chosen."
      );
    }
  }

  /*
    THE RENAME BOX RE-SEEDS FROM THE SERVER, WHICH IS WHAT ITS `key` USED TO DO.

    The box was `<TextInput defaultValue={form.title} key={`title-${form.title}`} />` — uncontrolled,
    remounted whenever the stored title changed, which is how it picked up a rename that landed from
    somewhere else and how it showed the SAVED value rather than the typed one after a failed save.
    A controlled box gets the same behaviour from an effect on the same dependency, and keeps the
    `key` off a component that would lose its dictation state to a remount mid-sentence.
  */
  useEffect(() => {
    setRenameTitle(form?.title ?? "");
  }, [form?.title]);

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
              UNGATED, beside the other ungated control and for the same reason: the server does not
              require ownership to reuse, because these questions already leave this system for any
              designer through the button to the left. The gate is on the TARGET workshop, checked
              through `load_workshop_or_404(..., for_edit=True)` — the same helper the attach path
              uses — and the dialog's dropdown is fed the scoped workshop list this page already
              holds, so the picker and the server agree about which workshops may be written to.

              DELIBERATELY HERE AND NOT IN THE `mayEdit` DETAILS PANEL BELOW, where the "Design
              workshop" dropdown lives. Those two controls differ by exactly one thing — whether the
              ORIGINAL keeps its workshop — and sitting them side by side would invite a designer who
              wanted a second copy to MOVE their live instrument off the workshop whose fieldwork is
              already running against it.
            */}
            <button type="button" className="field-button-secondary" onClick={() => setReuseOpen(true)} disabled={busy}>
              <CopyPlus className="h-4 w-4" aria-hidden />
              Reuse at another workshop
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
      {/*
        THE COPY SAYS IT IS A COPY, and it says it above the questions rather than beside the Save
        controls — a designer reads the instrument before they use it, and "this is not live" arriving
        after they have typed a section is the sentence arriving too late to be worth saying. Amber
        with a static border, not a tint alone: this is a state, and a state carried by colour only is
        a state a greyscale print and a colour-blind reader never get.
      */}
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
          This questionnaire has been taken out of use. It is hidden from the lists and no new answers can be recorded
          against it — the answers already recorded are still here and still exportable.
        </div>
      ) : null}
      {/*
        THE PUBLISHED DEFAULT, SAID ON THE PAGE — added 2026-08-28 with the `isShared` column.

        A designer opening this form may not have uploaded it, and until this line existed nothing on
        screen said why they could see it. Saying it here also answers the question the Open button
        raises next: they may READ and ANSWER it and may not reword it, which is what the `mayEdit`
        panel below already explains — this says whose form it is.
      */}
      {form.isShared ? (
        <div className="mb-4 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-sm leading-6 text-purple-900">
          This is the <strong className="font-semibold">standard questionnaire</strong>, published by an administrator
          for every designer. You can record answers against it exactly as you would your own; its wording belongs to
          whoever published it.
        </div>
      ) : null}
      {!mayEdit ? (
        /*
          ══ THE REFUSAL, AND THE WAY FORWARD BESIDE IT ══════════════════════════════════════════

          There is no `/questionnaires/[id]/edit` route in this app — editing is IN PLACE on this
          page, behind `mayEdit` — so for a designer handed a colleague's form, or looking at the
          published standard form, this notice is the entire explanation of why the page has no
          controls on it. It used to end at what they could not do plus a download, and left out the
          one action that actually gets them an editable instrument: REUSE, which copies the
          questions into a questionnaire they own. `POST /questionnaires/{id}/reuse` is ungated for
          exactly this reason, and the "Reuse at another workshop" button is already in the header
          above — but a reader who has just been told they may not edit does not go looking for a
          button whose label is about workshops.

          SHORTER THAN WHAT IT REPLACES. House rule: UI copy is terse, and the reasoning lives here.
          The .xlsx paragraph is gone from the screen — the button that download belongs to is not
          rendered for this reader, so a sentence explaining its absence explained a control they
          cannot see. `ArtefactNotice` below still carries the question-set/xlsx distinction.
        */
        <div className="mb-4 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <Lock className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            This form belongs to another designer, so its questions cannot be changed here. You can record answers
            against it, download the <span className="font-medium text-ink-900">question set</span>, or{" "}
            <button
              type="button"
              className="font-medium text-purple-700 underline underline-offset-2 hover:text-purple-800"
              onClick={() => setReuseOpen(true)}
            >
              reuse it into a copy of your own
            </button>{" "}
            and edit that.
          </span>
        </div>
      ) : null}

      {/*
        THE COPY, WITH A ROUTE TO IT. Kept on screen until the next action rather than toasted away:
        the new questionnaire is where the next sitting gets recorded, and this page is the ORIGINAL
        — every control on it edits the form whose fieldwork is already running.
      */}
      {reused ? (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <span>
            <span className="font-medium text-ink-900">&ldquo;{reused.title}&rdquo;</span> now carries these questions and no
            recorded answers. This questionnaire and every sitting against it are untouched.
          </span>
          <Link className={rowAction("edit")} href={`/questionnaires/${reused.id}`}>
            Open the copy
          </Link>
        </div>
      ) : null}

      {/* `subject` is the COPY's title, because a reuse report is never about the questionnaire whose
          page this is. Without it the panel headed its own provenance line "This questionnaire is a
          copy, and it carries no recorded answers" — two lines under the banner above saying this
          questionnaire is untouched, and about the form whose fieldwork is running. `reused` is null
          for the upload path, where the report IS about this page's subject. */}
      {report ? <UploadReport report={report} subject={reused?.title ?? null} className="mb-5" /> : null}

      {/* Both download buttons sit in this page's header, side by side, with the same title on the
          two files they produce. This is the caption for that choice. */}
      <ArtefactNotice className="mb-5" />

      {mayEdit ? (
        <form onSubmit={renameQuestionnaire} className="panel mb-5 grid gap-4 p-4">
          {/* ONCE FOR THE WHOLE PAGE. Five boxes carry a microphone here, and every one of them
              passes `explainWhenUnavailable={false}`: `OnDeviceDictationButton` prints its own
              "this browser cannot dictate" sentence, which is right for a form with one microphone
              and is five copies of one grey paragraph on this one. See that component. */}
          <DictationUnavailableNotice />
          <div className="grid gap-3 md:grid-cols-2">
            {/*
              ── THE AUTHORING SURFACE GETS THE MICROPHONE TOO — 2026-08-28 ─────────────────────
              The owner asked that dictation be "a default for other record pages as well", and this
              page was drawing six bare boxes: a designer builds a questionnaire on the same handset
              they later carry into the workshop. Five of the six are prose and take a microphone;
              the sixth is `code` below, which does not — see the note there.

              `key` IS STILL LOAD-BEARING. `DictatedTextInput` seeds itself from `defaultValue` once
              and then owns the value, exactly as the uncontrolled `<TextInput>` did, so a rename
              that lands from the server needs the same remount to be seen. Dropping it here would
              leave the box showing the OLD title after a successful save.
            */}
            <DictatedTextInput
              name="title"
              label="Title"
              required
              value={renameTitle}
              onChange={setRenameTitle}
              maxLength={220}
              explainWhenUnavailable={false}
            />
            {/* FieldBlock, not Field: `Field` is a <label>, and a <label> around a themed dropdown
                forwards a stray click into the menu and slams it shut after one pick. */}
            <FieldBlock
              label="Design workshop"
              hint={
                /* WHICH OF THE FOUR EMPTY STATES, or the numbered cut. This control had neither, at
                   either level, while being the one place the attachment is written. `aria-live`
                   because the trigger is not somewhere a reader can land while it is disabled. */
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
                /* THE UN-FILE ROW IS THE PRIMITIVE'S, and its label is the shared constant. Here it
                   is not decoration: picking it is what DETACHES a questionnaire, `attachWorkshop`
                   sends the explicit null the route puts back by hand after `clean_data` drops it,
                   and the hand-built row it replaces was one of nine strings for four meanings. */
                noneLabel={NO_DESIGN_WORKSHOP}
                emptyLabel={workshopEmptyLabel(workshopList, workshopVoice)}
                ariaLabel="Attach to a design workshop"
                searchable
                /* R2/R3, with one addition this control needs: a FAILED read that recovered the
                   questionnaire's own workshop is NOT an empty control — it holds that row and, with
                   the un-file row beside it, a way back out — so `workshopListStandsDown` reads the
                   OPTIONS rather than the state. Never disabled while the read is in flight:
                   `workshopEmptyLabel` answers "Searching…" in the panel, and a disabled trigger
                   cannot be opened to read it. */
                disabled={
                  busy || (workshopList.kind !== "loading" && workshopListStandsDown(workshopSet))
                }
                // This dropdown SAVES on select rather than filling in a form field, so focus must
                // not jump to the next control the way it does in a top-to-bottom record form.
                advanceOnSelect={false}
              />
            </FieldBlock>
            {/*
              THE KIND — what decides which stage of the report this instrument's answers land in.
              `FieldBlock` for the reason stated two controls up, and no `searchable` because this is
              a two-member constant vocabulary rather than a list of records.
            */}
            <FieldBlock
              label="Kind"
              hint={
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  Decides which stage of the report this questionnaire&rsquo;s answers are filed under.
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
                // Saves on select, so focus must not jump on to the next control.
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
          <div className="flex flex-wrap gap-2">
            <button className="field-button" disabled={busy}>
              Save details
            </button>
            {/* `field-danger` alone below, not stacked on `field-button-secondary`: it carries its
                own box (inline-flex, min-h-10, padding, radius), and class order in this repo is
                plain source order — `cn` is a join, not tailwind-merge — so two competing paddings
                would resolve by stylesheet position rather than by the order written here. */}
            {/*
              PUBLISH / WITHDRAW — ADMIN ONLY, and the guard is `isAdmin(user)` rather than `mayEdit`.

              `mayEdit` is "the owner, or an admin", which is every designer for their own forms.
              Ticking this does not change the owner's form; it changes what every OTHER designer in
              the country sees in their list and their attach dropdown, which is a repository-wide
              act. The server enforces the same rule and answers 403 to anybody else — this hides a
              control the API would refuse rather than being the gate itself.

              WITHDRAWING IS THE SAME AUTHORITY AS PUBLISHING, so one control does both. A designer
              must no more be able to take the standard form away from everybody than to give it.
            */}
            {isAdmin(user) ? (
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
            {/* NO MICROPHONE ON `code`, and it is the one box on this page that must not have one:
                it is a short identifier — "S1", "BG" — that prints beside every question in the
                download and is derived from the title when left empty. A recogniser returns the
                nearest DICTIONARY WORD, so "S1" comes back as "Yes one"; the rule is the same one
                the record forms apply to a phone number and a measurement. */}
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
                {/* The two longest boxes on the page — a prompt runs to 2,000 characters — and the
                    two an interviewer is most likely to compose out loud while thinking about the
                    sitting they are designing it for. `DictatedTextInput` and not `DictatedTextArea`
                    because the boxes they replace are one-line: the shape of the box follows the
                    shape of the answer, and changing it here would also change how Enter behaves
                    (`lib/formNav.isAdvanceableInput` opts textareas out of the Enter-walk). */}
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

      <ReuseDialog
        open={reuseOpen}
        questionnaireId={id}
        sourceTitle={form.title}
        sourceWorkshopId={form.designWorkshopId}
        // The page's own scoped list — `GET /design-workshops`, narrowed server-side by
        // `visible_to_clause` to workshops this account created or holds a viewer grant on. The same
        // list the "Design workshop" dropdown above is built from, so the two cannot disagree about
        // where this account may put a questionnaire.
        /* NARROWED, NOT HANDED WHOLE: `ReuseTarget` is the nine fields the picker reads, so the
           dialog stays clear of `DwSummary`'s thirty and of the API layer behind them — and the nine
           are what the shared builder needs to draw a title with its craft, its cluster and the day
           it ran, in place of the bare title that made two workshops in one craft indistinguishable. */
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
          // `load()` is deliberately NOT called. Nothing about THIS questionnaire changed — the reuse
          // wrote a different row entirely — and re-reading would only make the screen flicker while
          // implying the original was edited.
          toast({
            tone: "success",
            title: "Questionnaire reused",
            description: `"${result.questionnaire.title}" carries ${result.questionnaire.questionCount} questions and none of the answers recorded here.`
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
          // The reuse banner AND the reuse report's subject both come off `reused`, and an upload has
          // just made both of them wrong: "this questionnaire and every sitting against it are
          // untouched" is a sentence about a form nobody had edited yet.
          setReused(null);
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
