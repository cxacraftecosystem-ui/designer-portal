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
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { ArtefactNotice } from "@/components/questionnaires/ArtefactNotice";
import { UploadDialog } from "@/components/questionnaires/UploadDialog";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { listDesignWorkshops, saveBlobToDisk, type DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import {
  createQuestionnaire,
  downloadProForma,
  downloadQuestionSet,
  downloadQuestionnaireWorkbook,
  listQuestionnaires,
  type QFormSummary,
  type QFormUploadReport
} from "@/lib/questionnaireForms";
import type { PageResult } from "@/lib/types";

export default function QuestionnairesPage() {
  const router = useRouter();
  const { toast } = useToast();
  const [data, setData] = useState<PageResult<QFormSummary> | null>(null);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [report, setReport] = useState<QFormUploadReport | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newWorkshopId, setNewWorkshopId] = useState("");
  const [workshops, setWorkshops] = useState<DwSummary[]>([]);
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
      const made = await createQuestionnaire({
        title,
        description: String(form.get("description") ?? "").trim() || undefined,
        designWorkshopId: newWorkshopId || undefined
      });
      // A brand-new questionnaire has no sections at all, so the only useful next step is opening it.
      router.push(`/questionnaires/${made.id}`);
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
        The change report from the upload that just happened, kept on screen until the next one.

        NOT a toast. A toast is `aria-live="polite"`, never interrupts, and disappears on a timer —
        which makes it the wrong home for a list of eleven rows that could not be read and the Excel
        row numbers to find them at. This is the one thing on the page a designer may need to act on.
      */}
      {report ? <UploadReport report={report} className="mb-5" /> : null}

      {/*
        Drawn on the list rather than only on the detail page, because THIS is the screen with a
        "Download .xlsx" button on every row. A designer sending a colleague their questions starts
        here, and the difference between the two files — one of which carries every respondent they
        have ever interviewed — has to be readable at the moment the row action is chosen, not one
        navigation away.
      */}
      <ArtefactNotice className="mb-5" />

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
            <Field label="Title" required>
              <TextInput name="title" required maxLength={220} />
            </Field>
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
          <Field label="Description">
            <TextInput name="description" maxLength={2000} />
          </Field>
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

      <UploadDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        workshops={workshops.map((workshop) => ({ id: workshop.id, title: workshop.title }))}
        onUploaded={(result) => {
          setUploadOpen(false);
          setReport(result.report);
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
