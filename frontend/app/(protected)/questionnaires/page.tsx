"use client";

/**
 * The designer's own questionnaires — the list, and the two doors into making one.
 *
 * "use client" is not a preference: there is NO server-side data fetching anywhere in this app (the
 * bearer token lives in `localStorage`, which a server component cannot read), so every page that
 * touches the API is a client component.
 *
 * PLURAL. `app/(protected)/questionnaire/` — singular — is the ONE global artisan questionnaire
 * every researcher answers, and it is a different feature with different tables. Nothing here
 * touches it.
 *
 * THE TWO PRIMARY ACTIONS ARE THE SPREADSHEET PAIR, in the order the loop runs: download the blank
 * pro-forma, type your questions into it, upload it back. "Start an empty one" is offered as well
 * but deliberately third and in the quieter treatment — a questionnaire built box-by-box in a
 * browser is the slower path, and putting it first would send designers down it by default when the
 * whole point of the feature is that they build the instrument in Excel.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClipboardList, Download, FileSpreadsheet, Plus, Upload } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextInput } from "@/components/FormControls";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { ArtefactNotice } from "@/components/questionnaires/ArtefactNotice";
import { ReuseDialog } from "@/components/questionnaires/ReuseDialog";
import { UploadDialog } from "@/components/questionnaires/UploadDialog";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { listDesignWorkshops, saveBlobToDisk, type DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import {
  downloadProForma,
  downloadQuestionSet,
  downloadQuestionnaireWorkbook,
  listQuestionnaires,
  type QForm,
  type QFormSummary,
  type QFormUploadReport
} from "@/lib/questionnaireForms";
import { saveOrQueue } from "@/lib/offline";
import type { PageResult } from "@/lib/types";

export default function QuestionnairesPage() {
  const router = useRouter();
  const { toast } = useToast();
  const [data, setData] = useState<PageResult<QFormSummary> | null>(null);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [error, setError] = useState<string | null>(null);
  /**
   * "It is on this device and not yet in the repository" — said after a create that was banked.
   *
   * ITS OWN STATE AND NOT `error`, because it is not one. A queued save succeeded; what differs is
   * WHERE the questionnaire is, and rendering that in the red banner would tell a designer their
   * work had failed when it had not. `OutboxBanner` in the protected layout names the entry itself;
   * this says the one thing that banner cannot — what happens to THIS questionnaire next.
   */
  const [notice, setNotice] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [report, setReport] = useState<QFormUploadReport | null>(null);
  /**
   * The questionnaire the report above is ABOUT, when that is not the one whose page this is — which
   * on a LIST is every case where the sentence has a subject at all.
   *
   * A reuse report describes a row that is not on screen. Left unnamed, the panel's provenance line
   * read "This questionnaire is a copy, and it carries no recorded answers" on a page showing twenty
   * questionnaires, none of which is the copy. Null for the upload path, whose own heading claims no
   * subject.
   */
  const [reportSubject, setReportSubject] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  /*
    THE TWO DICTATED BOXES ARE CONTROLLED, AND THAT IS `DictatedTextInput`'S CONTRACT, NOT A CHOICE.

    Its header states it: it has one mode, because "a control that is sometimes controlled is a
    control whose reset behaviour has to be re-derived at every call site". So the value lives here.
    `submit` still reads `FormData` off the form element — the component renders a real `name` — so
    `createEmpty` is untouched by this.

    NOT CLEARED ANYWHERE, deliberately, because the form it belongs to never cleared itself either:
    a successful create navigates to the new questionnaire and a QUEUED one stays put under a notice
    saying it is banked. Clearing on the queued branch would be a behaviour change dressed as a
    refactor.
  */
  const [newTitle, setNewTitle] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [creating, setCreating] = useState(false);
  const [newWorkshopId, setNewWorkshopId] = useState("");
  const [workshops, setWorkshops] = useState<DwSummary[]>([]);
  /**
   * The row "Reuse at another workshop" was pressed on, or null.
   *
   * ONE DIALOG FOR THE WHOLE TABLE, holding the row it points at, rather than one per row. Twenty
   * mounted dialogs is twenty copies of the target list and twenty title look-ups waiting to fire;
   * the dialog re-seeds itself on open (see its own effect) so swapping the row it points at is safe.
   */
  const [reuseRow, setReuseRow] = useState<QFormSummary | null>(null);
  const skipFirstDebounce = useRef(true);

  /**
   * List pages count fetch generations rather than aborting: `listQuestionnaires` takes no signal,
   * and what matters is IGNORING the late answer. Without this, a typed search whose first response
   * arrives after the second overwrites the newer list with older rows, and the screen shows results
   * for a query nobody can see any more.
   */
  const generation = useRef(0);

  const load = useCallback(async () => {
    const mine = ++generation.current;
    try {
      const result = await listQuestionnaires({ page, pageSize: 20, search: applied || undefined });
      if (mine !== generation.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (mine !== generation.current) return;
      setError(err instanceof Error ? err.message : "Unable to load questionnaires");
      // `data` is deliberately left alone on a failed refresh. Emptying it would replace a list the
      // designer can still read with "no questionnaires yet", which is indistinguishable from having
      // none — the most repeated bug class in this repository.
    }
  }, [page, applied]);

  useEffect(() => {
    load();
  }, [load]);

  // The design workshops a questionnaire can be attached to. Fetched once and shared by the create
  // form and the upload dialog, so the two cannot offer different lists.
  useEffect(() => {
    let cancelled = false;
    listDesignWorkshops({ pageSize: 100 })
      .then((result) => {
        if (!cancelled) setWorkshops(result.items ?? []);
      })
      // Silent: the picker is a convenience, and a questionnaire attaches to a workshop just as well
      // from the detail page. An error banner for a shortcut that failed reads as the page itself
      // being broken.
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  // Live search: 350ms after typing stops, Enter applies immediately. Both go through the same state
  // so the generation guard above stays the only race protection needed.
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = window.setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [query]);

  async function download(fetcher: () => Promise<{ blob: Blob; fileName: string }>, failure: string) {
    setDownloading(true);
    setError(null);
    try {
      const file = await fetcher();
      saveBlobToDisk(file.blob, file.fileName);
    } catch (err) {
      setError(err instanceof Error ? err.message : failure);
    } finally {
      setDownloading(false);
    }
  }

  async function createEmpty(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `event.currentTarget` across an await, so the FormData must be built before any
    // async work — after the first `await` it reads as null and every field comes back empty.
    const form = new FormData(event.currentTarget);
    const title = String(form.get("title") ?? "").trim();
    if (!title) return;
    setCreating(true);
    setError(null);
    try {
      /*
        SENT OR BANKED — `saveOrQueue`, not a bare `createQuestionnaire`, since 2026-08-28.

        The owner asked that a designer building their own questionnaire have it *"work correctly
        offline as well"*. This was a plain POST: pressed with no connection it threw, the banner
        said "Unable to create the questionnaire", and the title the designer had typed was gone.

        WHAT IS QUEUED IS THE ROW, NOT THE TREE, and that is a decision rather than a first
        instalment. A section and a question are separate creates against
        `/questionnaires/{id}/sections` and `.../questions`, and the id they need does not exist
        until this row lands — the outbox replays entries independently and has no way to thread a
        server-minted id from one into the next. So offline a designer gets the thing they actually
        needed in the room: the form EXISTS, named, attached to its workshop, and on the server the
        moment there is signal. The questions are written afterwards, and the notice says so.

        `saveOrQueue` also gets the refusal split right for free: a 4xx is the server having
        answered and is thrown, never banked, because replaying a rejection for ever while reporting
        success is the failure its own header exists to prevent.
      */
      const outcome = await saveOrQueue<QForm>({
        label: `Questionnaire · ${title}`,
        endpoint: "/questionnaires",
        method: "POST",
        body: {
          title,
          description: String(form.get("description") ?? "").trim() || undefined,
          designWorkshopId: newWorkshopId || undefined
        }
      });
      if (outcome.queued) {
        // NOT `router.push` — there is no id to open yet. The banner in the protected layout names
        // the entry and where it lives; this says the one thing that banner cannot, which is what
        // happens to this particular questionnaire next.
        setNotice(
          `“${title}” is saved on this device. It is sent to the repository when the connection ` +
            "returns, and its sections and questions can be written once it has arrived."
        );
        return;
      }
      // A brand-new questionnaire has no sections at all, so the only useful next step is opening it.
      router.push(`/questionnaires/${outcome.saved.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to create the questionnaire");
    } finally {
      setCreating(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Questionnaires"
        description="Questionnaires you built yourself. Download the pro-forma, type your questions into it in Excel, upload it back, and record the answers here — the sheet may arrive with answers already in it or with none at all."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <button
              type="button"
              className="field-button-secondary"
              disabled={downloading}
              onClick={() => download(downloadProForma, "Unable to download the pro-forma")}
            >
              <Download className="h-4 w-4" aria-hidden />
              {downloading ? "Preparing…" : "Download the pro-forma"}
            </button>
            <button type="button" className="field-button" onClick={() => setUploadOpen(true)}>
              <Upload className="h-4 w-4" aria-hidden />
              Upload a filled-in pro-forma
            </button>
          </>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {/*
        A QUEUED CREATE IS NOT AN ERROR AND MUST NOT WEAR THE RED BOX. `role="status"` rather than
        `alert`: nothing went wrong, nothing was lost, and interrupting a screen reader for a save
        that succeeded is the wrong trade. See the `notice` state for what it says that the outbox
        banner cannot.
      */}
      {notice ? (
        <div
          role="status"
          className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
        >
          {notice}
        </div>
      ) : null}

      {/*
        The change report from the upload that just happened, kept on screen until the next one.

        NOT a toast. A toast is `aria-live="polite"`, never interrupts, and disappears on a timer —
        which makes it the wrong home for a list of eleven rows that could not be read and the Excel
        row numbers to find them at. This is the one thing on the page a designer may need to act on.
      */}
      {report ? <UploadReport report={report} subject={reportSubject} className="mb-5" /> : null}

      {/*
        Drawn on the list rather than only on the detail page, because THIS is the screen with a
        "Download .xlsx" button on every row. A designer sending a colleague their questions starts
        here, and the difference between the two files — one of which carries every respondent they
        have ever interviewed — has to be readable at the moment the row action is chosen, not one
        navigation away.
      */}
      <ArtefactNotice className="mb-5" />

      {/*
        ══ THE SEEDED DEFAULT QUESTIONNAIRE, POINTED AT FROM HERE (req 12) ═════════════════════
        A designer asked to "use the default questionnaire" arrives on THIS page, because it is the
        one called "questionnaires". The shared artisan instrument is not here and cannot be: the
        two families are different TABLES, not two filters over one list. This page reads
        `Questionnaire` rows — designer-authored forms, `GET /questionnaires` — while the seeded
        default lives in `QuestionnaireSection` / `QuestionnaireQuestion`, upserted by
        `backend/scripts/seed_questionnaire.py` from `app/data/questionnaire_questions.json` and
        answered at `/questionnaire` (singular). Verified 2026-08-28:

          grep -n "questionnairesection\|questionnairequestion" backend/scripts/seed_questionnaire.py
          grep -n "db.questionnaire\b" backend/app/api/routes/questionnaire_forms.py

        SO THERE IS NO CLIENT-SIDE FILTER HIDING IT AND NONE TO FIX. What was actually missing is
        this line: the default was reachable but never OFFERED — a designer standing on an empty
        "My questionnaires" had no route to the instrument the repository already ships, and the
        two paths differ by one character in the URL and by one letter in the menu. Unifying the two
        lists would be the wrong repair and is explicitly ruled out; a signpost is the right one.

        A DESIGNER IS ENTITLED TO IT, WHICH IS WHY THIS IS AN UNCONDITIONAL LINK AND NOT A GATED
        ONE. `/questionnaire` carries no `ROUTE_GUARDS` row, its nav entry is `can: everyone`
        (`DynamicIslandNav.tsx`, "Take interview"), and on the server `GET /questionnaire/sections`,
        `/questions` and `POST /questionnaire/interviews` all take `get_current_user` and nothing
        more (`backend/app/api/routes/questionnaire.py`). Only EDITING the shared instrument is
        gated — `require_questionnaire_manager`, PROFESSOR+ or the flag — and that is management,
        not use. Drawing this behind a permission would be exactly the "hidden behind a permission
        the API would in fact allow" failure this change was asked to remove.
      */}
      <p className="mb-5 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
        Looking for the repository&apos;s shared artisan questionnaire — the default one, already seeded with its
        sections and questions? It is a different instrument from the ones on this page and it lives on{" "}
        <Link href="/questionnaire" className="font-medium text-purple-700 underline-offset-2 hover:underline">
          Take interview
        </Link>
        , where every signed-in account can answer it. The questionnaires below are the ones you built yourself.
      </p>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search by title or description"
        />
        {/*
          ══ A DESIGNER CAN REACH THIS, AND IT IS GATED BY NOTHING (req 11) ═══════════════════
          Checked rather than assumed, because "the control is hidden behind a permission the API
          would in fact allow" is the specific failure this was asked to rule out. All four rungs
          agree that a DESIGNER (rank 35) may create a questionnaire, and none of them is stricter
          than the endpoint. Verified 2026-08-28:

            · The endpoint. `POST /questionnaires` runs `_require_designer(current_user)` — no rank
              threshold and no ownership test — plus `_require_attachable_workshop` ONLY when the
              body names a `designWorkshopId`:
                grep -n "async def create_questionnaire" -A 12 backend/app/api/routes/questionnaire_forms.py
              `_require_designer` is `can_run_design_workshops`, i.e. the SET {Designer, Admin,
              Master Admin} — a set, not a floor, which is why a PROFESSOR (40) is outside it.
              The attachment half needs no mirror in this form: the workshop dropdown below is fed
              by `listDesignWorkshops`, whose server scope is `visible_to_clause` (creator OR
              viewer-grant), and the attach check is `load_workshop_or_404(for_edit=True)` (creator
              OR admin OR viewer-grant). For a designer those are the same set, so the picker cannot
              offer a target the create call then refuses. Leaving the workshop unchosen — the
              default — skips the check entirely.
            · The route. `ROUTE_GUARDS` gates `/questionnaires` on `canRunDesignWorkshops`, the same
              predicate, so the page opens for exactly the accounts the API serves:
                grep -n "path: \"/questionnaires\"" -A 3 frontend/lib/permissions.ts
            · The menu. `NAV_ITEMS` carries "My questionnaires" with the same `can`, so the
              destination is offered rather than only typeable.
            · This control. UNGATED ON PURPOSE — there is no `can…()` call on it and there must not
              be. Every account that reaches this page has already passed the same predicate the
              endpoint applies, so a second test here could only ever be stricter than the server
              and would hide a door the API would open. `DESIGN_WORKSHOP_CREATOR_ROLES` is the
              nearby trap: STARTING a design workshop is admin-only, and reusing that predicate here
              — they read almost identically — would lock every designer out of building the
              instrument for a workshop they are running.

          It stays the QUIETER, third door, and that is a product decision rather than an oversight:
          the spreadsheet pair above is the fast path and this page's own header comment says why.
          Reachable and unhidden is the requirement; loudest is not.
        */}
        <button type="button" className="field-button-secondary" onClick={() => setFormOpen((open) => !open)}>
          <Plus className="h-4 w-4" aria-hidden />
          {formOpen ? "Close" : "Start an empty one"}
        </button>
      </div>

      {formOpen ? (
        <form onSubmit={createEmpty} className="panel mb-5 grid gap-4 p-4">
          <div>
            <h2 className="font-display text-lg font-bold text-ink-900">Start a questionnaire by hand</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              The slower of the two doors, and it leads to the same place: sections and questions are added on the next
              screen. Most designers download the pro-forma above and type their questions in Excel instead.
            </p>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            {/* Both boxes on this form are prose and both carry a microphone, matching the same
                two boxes on `/questionnaires/[id]` and on Android's "New questionnaire" dialog. The
                workshop dropdown beside it is a pick, not a phrase. */}
            <DictatedTextInput
              name="title"
              label="Title"
              required
              value={newTitle}
              onChange={setNewTitle}
              maxLength={220}
              explainWhenUnavailable={false}
            />
            <FieldBlock label="Attach to a design workshop">
              <Dropdown
                value={newWorkshopId}
                onChange={setNewWorkshopId}
                options={[
                  { value: "", label: "Not attached to a workshop" },
                  ...workshops.map((workshop) => ({ value: workshop.id, label: workshop.title }))
                ]}
                ariaLabel="Attach to a design workshop"
                searchable
              />
            </FieldBlock>
          </div>
          <DictatedTextInput
            name="description"
            label="Description"
            value={newDescription}
            onChange={setNewDescription}
            maxLength={2000}
            explainWhenUnavailable={false}
          />
          {/* Once for the form, because two microphones would otherwise print the same grey
              paragraph twice where a browser has no recogniser. See the component. */}
          <DictationUnavailableNotice />
          <div className="flex gap-2">
            <button className="field-button" disabled={creating}>
              {creating ? "Creating…" : "Create questionnaire"}
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setFormOpen(false)}>
              Cancel
            </button>
          </div>
        </form>
      ) : null}

      <section className="panel overflow-hidden">
        {data === null ? (
          // null is "still loading" and [] is "genuinely none" — a deliberate distinction. Saying
          // "no questionnaires yet" during a fetch is both wrong and discouraging.
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No questionnaires yet"
              body="Download the pro-forma, type your questions into it, and upload it back. You can record the answers here afterwards, whether or not the sheet already has any in it."
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Questionnaire</ResizableTh>
                  <ResizableTh>Design workshop</ResizableTh>
                  <ResizableTh>Owner</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((row) => (
                  <tr key={row.id}>
                    <td className="px-4 py-3">
                      <Link
                        href={`/questionnaires/${row.id}`}
                        className="font-medium text-ink-900 underline-offset-2 hover:underline"
                      >
                        {row.title}
                      </Link>
                      <div className="text-xs text-ink-500">
                        {/* The source file name is null for a questionnaire built in the app, which
                            is a fact worth showing rather than a blank: it tells a designer whether
                            there is a spreadsheet somewhere that this came from. */}
                        {row.sourceFilename ? (
                          <span className="inline-flex items-center gap-1">
                            <FileSpreadsheet className="h-3 w-3" aria-hidden />
                            {row.sourceFilename}
                          </span>
                        ) : (
                          "Built in the app"
                        )}
                        {row.version > 1 ? ` · version ${row.version}` : ""}
                      </div>
                      {/*
                        THE PUBLISHED DEFAULT, NAMED — added 2026-08-28 with the `isShared` column.

                        A designer's list can now contain a form they did not upload, and a row that
                        cannot say why reads as somebody else's work leaking in. The badge carries a
                        WORD and not only a tint, per the rule that colour never carries meaning on
                        its own, and it says what the row is FOR ("everyone can use it") rather than
                        repeating the column's name at the reader.
                      */}
                      {row.isShared ? (
                        <div className="mt-1 inline-flex items-center gap-1 rounded-md border border-purple-200 bg-purple-50 px-1.5 py-0.5 text-[11px] font-medium text-purple-700">
                          <ClipboardList className="h-3 w-3" aria-hidden />
                          Standard form — everyone can use it
                        </div>
                      ) : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{row.designWorkshopTitle ?? "—"}</td>
                    <td className="px-4 py-3 text-ink-700">{row.ownerName ?? "—"}</td>
                    <td className="px-4 py-3 text-ink-700">{row.createdAt ? formatDate(row.createdAt) : "—"}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <Link className={rowAction("edit")} href={`/questionnaires/${row.id}`}>
                          Open
                        </Link>
                        <Link className={rowAction("neutral")} href={`/questionnaires/${row.id}/answer`}>
                          Record answers
                        </Link>
                        {/*
                          UNGATED, exactly like "Download question set" below it and for the same
                          reason: the server does NOT require ownership here, because the instrument
                          already leaves this system for any designer through
                          `/question-set.xlsx`. What the server does gate is the TARGET — the
                          workshop the copy is attached to — and the dialog's dropdown is fed the
                          page's own scoped workshop list, so the picker and the server agree about
                          which workshops this account may write to.
                        */}
                        <button type="button" className={rowAction("neutral")} onClick={() => setReuseRow(row)}>
                          Reuse at another workshop
                        </button>
                        {/*
                          THE SHARING DOWNLOAD COMES FIRST, and its label says what it is rather than
                          what format it is. It is offered on EVERY row, including a colleague's
                          questionnaire, because the server gates it exactly as it gates reading the
                          form — any designer. "Download .xlsx" beside it is the lossless one, which
                          answers 403 for anyone but the owner, a designer on its design workshop, or
                          an admin; it is left in place for everyone rather than hidden, because this
                          list does not know which of those the reader is and a refusal that names
                          the question set is more use than a control that silently vanished.
                        */}
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          disabled={downloading}
                          onClick={() =>
                            download(() => downloadQuestionSet(row.id), "Unable to download that question set")
                          }
                        >
                          Download question set
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          disabled={downloading}
                          onClick={() =>
                            download(() => downloadQuestionnaireWorkbook(row.id), "Unable to download that questionnaire")
                          }
                        >
                          Download .xlsx
                        </button>
                      </RowActions>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {/*
        `key` on the questionnaire id, so the dialog is a FRESH component per row rather than the
        same one re-pointed. Its state — the picked target, the typed title, the titles already at
        that target — is seeded from the row it is about, and React keeps state across a prop change
        on the same element. Without the key, opening it on a second row would show the first row's
        default title in the box.
      */}
      {reuseRow ? (
        <ReuseDialog
          key={reuseRow.id}
          open
          questionnaireId={reuseRow.id}
          sourceTitle={reuseRow.title}
          sourceWorkshopId={reuseRow.designWorkshopId}
          workshops={workshops.map((workshop) => ({ id: workshop.id, title: workshop.title }))}
          onClose={() => setReuseRow(null)}
          onReused={(result) => {
            setReuseRow(null);
            // The same panel the upload path fills, with the same report shape — `provenance.action`
            // is `"reused"` and its sentence is the one thing on this screen that states the copy
            // carried no answers. A toast alone would say it and take it away again.
            setReport(result.report);
            setReportSubject(result.questionnaire.title);
            void load();
            toast({
              tone: "success",
              title: "Questionnaire reused",
              description: `"${result.questionnaire.title}" carries ${result.questionnaire.questionCount} questions and no recorded answers. Open it to record its own.`
            });
          }}
        />
      ) : null}

      <UploadDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        workshops={workshops.map((workshop) => ({ id: workshop.id, title: workshop.title }))}
        onUploaded={(result) => {
          setUploadOpen(false);
          setReport(result.report);
          // Cleared, not left standing: the panel below is now an UPLOAD report, and a stale subject
          // would name the last copy made as the thing this upload happened to.
          setReportSubject(null);
          // The list is refreshed rather than optimistically prepended: the upload may have been an
          // edit of a row already on screen, and re-reading is the only way this page learns which.
          void load();
          toast({
            tone: "success",
            title: "Questionnaire uploaded",
            description: `"${result.questionnaire.title}" now has ${result.questionnaire.questionCount} questions. Open it to record answers.`
          });
        }}
      />
    </>
  );
}
