"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClipboardList, Download, FileSpreadsheet, Plus, Upload } from "lucide-react";

import { attachHold, HeldPostNotice, useHeldPostRefusal, writesHeld } from "@/components/designworkshop/HeldPostNotice";
import { useListMemory } from "@/components/hooks/useListMemory";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextInput } from "@/components/FormControls";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { cappedListNotice, cutOf } from "@/components/data/cappedList";
import { OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { FilterBar } from "@/components/questionnaire/FilterBar";
import { useUrlFilters } from "@/components/questionnaire/useUrlFilters";
import { useAdminView } from "@/components/AdminViewProvider";
import { ArtefactNotice } from "@/components/questionnaires/ArtefactNotice";
import { DeletedQuestionnairesCard } from "@/components/questionnaires/DeletedQuestionnairesCard";
import { OpenBundleButton } from "@/components/questionnaires/BundleControls";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { useAuth } from "@/components/AuthProvider";
import { qs } from "@/lib/questionnaire/qs";
import { inTab } from "@/lib/questionnaireLinks";
import { ReuseDialog } from "@/components/questionnaires/ReuseDialog";
import { UploadDialog } from "@/components/questionnaires/UploadDialog";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { listEveryDesignWorkshop, saveBlobToDisk, type DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import {
  downloadProForma,
  downloadQuestionSet,
  downloadQuestionnaireWorkbook,
  listQuestionnaires,
  questionnaireKindLabel,
  QUESTIONNAIRE_KINDS,
  QUESTIONNAIRE_KIND_LABELS,
  type QForm,
  type QFormSummary,
  type QFormUploadReport
} from "@/lib/questionnaireForms";
import { saveOrQueue } from "@/lib/offline";
import { questionnaireCreateBody } from "@/lib/recordPayloads";
import { queueForm } from "@/lib/questionnaireAuthoringOutbox";
import type { PageResult } from "@/lib/types";
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
import { ActionGroup } from "@/components/ui/ActionGroup";

const NO_WORKSHOP_ROWS: readonly DwSummary[] = [];

export default function QuestionnairesPage() {
  const router = useRouter();
  const { toast } = useToast();
  const { adminMode, canDeleteRecords } = useAdminView();
  const { user: me } = useAuth();
  const v2 = Boolean(useQuestionnaireConfig()?.v2Enabled);
  const [tab, setTab] = useState<"mine" | "shared" | "workshop" | "deleted">("mine");
  const [data, setData] = useState<PageResult<QFormSummary> | null>(null);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [report, setReport] = useState<QFormUploadReport | null>(null);
  const [reportSubject, setReportSubject] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [creating, setCreating] = useState(false);
  const [newWorkshopId, setNewWorkshopId] = useState("");
  const createWorkshopHold = useHeldPostRefusal(newWorkshopId || null);
  const createHold = attachHold(createWorkshopHold, "this questionnaire");
  const createHoldId = useId();
  const [newKind, setNewKind] = useState("");
  const [showDeactivated, setShowDeactivated] = useState(false);
  const [workshopList, setWorkshopList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  const [workshopsOnline, setWorkshopsOnline] = useState(true);
  const [reuseRow, setReuseRow] = useState<QFormSummary | null>(null);
  const skipFirstDebounce = useRef(true);

  const generation = useRef(0);
  const { filters: sharedFilters, setFilters: setSharedFilters, ready: filtersReady } = useUrlFilters();

  const load = useCallback(async () => {
    const mine = ++generation.current;
    try {
      const result = await listQuestionnaires({
        page,
        pageSize: 20,
        search: applied || undefined,
        activeOnly: showDeactivated ? false : undefined,
        filters: sharedFilters
      });
      if (mine !== generation.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (mine !== generation.current) return;
      setError(err instanceof Error ? err.message : "Unable to load questionnaires");
    }
  }, [page, applied, showDeactivated, sharedFilters]);

  const memory = useListMemory(
    "questionnaires",
    { query, applied, page, filters: { showDeactivated: showDeactivated ? "1" : "" } },
    data !== null
  );
  const [placeRestored, setPlaceRestored] = useState(false);
  useEffect(() => {
    if (!placeRestored || !filtersReady) return;
    load();
  }, [load, placeRestored, filtersReady]);

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

  const workshopSet = useMemo(
    () => designWorkshopOptions(workshopList, { group: true, offPage: { mode: "refuse" } }),
    [workshopList]
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
  const workshops = workshopList.kind === "ok" ? workshopList.rows : NO_WORKSHOP_ROWS;

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

  useEffect(() => {
    const remembered = memory.recall();
    if (remembered) {
      if ((remembered.query ?? "") !== "") skipFirstDebounce.current = true;
      setQuery(remembered.query ?? "");
      setApplied(remembered.applied ?? "");
      setPage(remembered.page ?? 1);
      setShowDeactivated(remembered.filters?.showDeactivated === "1");
    }
    setPlaceRestored(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    const form = new FormData(event.currentTarget);
    const title = String(form.get("title") ?? "").trim();
    if (!title || writesHeld(createHold)) return;
    setCreating(true);
    setError(null);
    if (typeof navigator !== "undefined" && navigator.onLine === false) {
      const tempId = queueForm(me?.id ?? null, {
        title,
        description: String(form.get("description") ?? "").trim() || undefined,
        designWorkshopId: newWorkshopId || undefined,
        kind: newKind || undefined
      });
      setCreating(false);
      router.push(`/questionnaires/${tempId}`);
      return;
    }
    try {
      const outcome = await saveOrQueue<QForm>({
        label: `Questionnaire · ${title}`,
        endpoint: "/questionnaires",
        method: "POST",
        body: questionnaireCreateBody(form, { designWorkshopId: newWorkshopId, kind: newKind })
      });
      if (outcome.queued) {
        setNotice(
          `“${title}” saved on this device. Add questions once it uploads.`
        );
        return;
      }
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
        description="Your own questionnaires. Build them from the pro-forma."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <button type="button" className="field-button" onClick={() => setFormOpen((open) => !open)} data-new-form>
              {qs("newForm")}
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setUploadOpen(true)}>
              <Upload className="h-4 w-4" aria-hidden />
              {qs("uploadWorkbook")}
            </button>
            <button
              type="button"
              className="field-button-secondary"
              disabled={downloading}
              onClick={() => download(downloadProForma, "Unable to download the pro-forma")}
            >
              <Download className="h-4 w-4" aria-hidden />
              {downloading ? "Preparing…" : qs("downloadProForma")}
            </button>
            <OpenBundleButton />
          </>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {notice ? (
        <div
          role="status"
          className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
        >
          {notice}
        </div>
      ) : null}

      {report ? <UploadReport report={report} subject={reportSubject} className="mb-5" /> : null}

      <ArtefactNotice className="mb-5" />

      {v2 ? (
        <p className="mb-5 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          Answer a form in a{" "}
          <Link href="/questionnaire" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            session
          </Link>
          .
        </p>
      ) : null}

      <FilterBar
        filters={sharedFilters}
        onChange={(next) => {
          setSharedFilters(next);
          setPage(1);
        }}
        placeholder=""
        search={false}
        hide={["q", "questionnaireId"]}
      />

      <div className="mb-4" data-forms-tabs>
        <SegmentedChoice
          label={qs("formsTitle")}
          value={tab}
          onChange={(v) => setTab(v as typeof tab)}
          options={[
            { value: "mine", label: qs("formsMine") },
            { value: "shared", label: qs("formsShared") },
            { value: "workshop", label: qs("formsWorkshop") },
            ...(canDeleteRecords ? [{ value: "deleted", label: qs("formsDeleted") }] : [])
          ]}
        />
      </div>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          ariaLabel="Search questionnaires"
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
              Add questions on the next screen, or use the pro-forma above.
            </p>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <DictatedTextInput
              name="title"
              label="Title"
              required
              value={newTitle}
              onChange={setNewTitle}
              maxLength={220}
              explainWhenUnavailable={false}
            />
            <FieldBlock
              label="Attach to a design workshop"
              hint={
                workshopNotice ? (
                  <p className="mt-1 text-xs leading-5 text-ink-500" aria-live="polite">
                    {workshopNotice}
                  </p>
                ) : null
              }
            >
              <Dropdown
                value={newWorkshopId}
                onChange={setNewWorkshopId}
                options={workshopSet.options}
                noneLabel={NO_DESIGN_WORKSHOP}
                emptyLabel={workshopEmptyLabel(workshopList, workshopVoice)}
                ariaLabel="Attach to a design workshop"
                searchable
                disabled={workshopList.kind !== "loading" && workshopListStandsDown(workshopSet)}
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
                value={newKind}
                onChange={setNewKind}
                options={QUESTIONNAIRE_KINDS.map((kind) => ({
                  value: kind,
                  label: QUESTIONNAIRE_KIND_LABELS[kind]
                }))}
                noneLabel="Not stated"
                ariaLabel="Kind"
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
          <DictationUnavailableNotice />
          <HeldPostNotice refusal={createHold} id={createHoldId} className="" sayPending />
          <ActionGroup>
            <button
              className="field-button"
              disabled={creating || writesHeld(createHold)}
              aria-describedby={typeof createHold === "string" ? createHoldId : undefined}
            >
              {creating ? "Creating…" : "Create questionnaire"}
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setFormOpen(false)}>
              Cancel
            </button>
          </ActionGroup>
        </form>
      ) : null}

      <div className="mb-4 flex items-start justify-between gap-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2.5">
        <Toggle
          label="Show deactivated"
          checked={showDeactivated}
          onChange={(next) => {
            setShowDeactivated(next);
            setPage(1);
          }}
        />
        <p className="max-w-md text-right text-xs leading-5 text-ink-500">
          Deactivating keeps every answer.
        </p>
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No questionnaires yet"
              body="Download the pro-forma, add your questions and upload it."
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
                {data.items.filter((row) => inTab(row, tab, me?.id ?? null)).map((row) => (
                  <OpenableRow key={row.id} href={`/questionnaires/${row.id}`}>
                    <td className="px-4 py-3">
                      <OpenableRowLink href={`/questionnaires/${row.id}`}>{row.title}</OpenableRowLink>
                      <div className="text-xs text-ink-500">
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
                      <div className="mt-1 flex flex-wrap items-center gap-1.5">
                        {row.isShared ? (
                          <span className="inline-flex items-center gap-1 rounded-md border border-purple-200 bg-purple-50 px-1.5 py-0.5 text-[11px] font-medium text-purple-700">
                            <ClipboardList className="h-3 w-3" aria-hidden />
                            Standard form — everyone can use it
                          </span>
                        ) : null}
                        {!row.isActive ? (
                          <span className="inline-flex items-center gap-1 rounded-md border border-amber-500/30 bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-800">
                            Deactivated
                          </span>
                        ) : null}
                        {row.kind ? (
                          <span className="inline-flex items-center gap-1 rounded-md border border-line-200 bg-surface-50 px-1.5 py-0.5 text-[11px] font-medium text-ink-700">
                            {row.kindLabel || questionnaireKindLabel(row.kind)}
                          </span>
                        ) : null}
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
                        <button type="button" className={rowAction("neutral")} onClick={() => setReuseRow(row)}>
                          Reuse at another workshop
                        </button>
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
                  </OpenableRow>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {tab === "deleted" || adminMode || !canDeleteRecords ? (
        <DeletedQuestionnairesCard ownOnly={!canDeleteRecords} onRestored={() => void load()} />
      ) : null}

      {reuseRow ? (
        <ReuseDialog
          key={reuseRow.id}
          open
          questionnaireId={reuseRow.id}
          sourceTitle={reuseRow.title}
          sourceWorkshopId={reuseRow.designWorkshopId}
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
          onClose={() => setReuseRow(null)}
          onReused={(result) => {
            setReuseRow(null);
            setReport(result.report);
            setReportSubject(result.questionnaire.title);
            void load();
            toast({
              tone: "success",
              title: "Questionnaire reused",
              description: `"${result.questionnaire.title}" has ${result.questionnaire.questionCount} questions, without answers.`
            });
          }}
        />
      ) : null}

      <UploadDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
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
        onUploaded={(result) => {
          setUploadOpen(false);
          setReport(result.report);
          setReportSubject(null);
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
