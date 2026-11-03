"use client";

import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import {
  CalendarRange,
  Download,
  ExternalLink,
  FileDown,
  RotateCcw,
  Undo2,
  Upload
} from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { useAuth } from "@/components/AuthProvider";
import { describeApiDetail } from "@/lib/api";
import { canManageAnnualPlan } from "@/lib/permissions";
import { SearchableSelect } from "@/components/ui/SearchableSelect";

import {
  deleteAnnualPlanEntry,
  downloadPlanExport,
  downloadPlanProForma,
  listAnnualPlan,
  listPlanYears,
  reinstateAnnualPlanEntry,
  updateAnnualPlanNotes,
  withdrawAnnualPlanEntry,
  type AnnualPlanEntry,
  type AnnualPlanPage,
  type AnnualPlanYear,
  type PlanUploadReport,
  type PromoteResult
} from "./annualPlan";
import {
  PLAN_SORTS,
  PLAN_SORT_LABELS,
  PLAN_STANDINGS,
  PLAN_STANDING_LABELS,
  annualPlanExportQuery,
  annualPlanQuery,
  type PlanSort,
  type PlanStandingFilter
} from "./annualPlanQuery";
import { PlanUploadReport as UploadReportPanel } from "./PlanUploadReport";
import { PromoteDialog } from "./PromoteDialog";
import { RegionalStatesPanel } from "./RegionalStatesPanel";
import { UploadPlanDialog } from "./UploadPlanDialog";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { useAdminView } from "@/components/AdminViewProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { formatSanctionAmount } from "@/lib/sanctionOrders";

const STANDING: Record<string, { label: string; className: string }> = {
  PLANNED: { label: "Planned", className: "bg-surface-50 text-ink-700 ring-1 ring-line-200" },
  PROMOTED: { label: "Opened", className: "bg-success-100 text-success-600" },
  WITHDRAWN: { label: "Withdrawn", className: "bg-amber-100 text-amber-800" }
};

function StandingChip({ standing }: { standing: string }) {
  const tone = STANDING[standing] ?? {
    label: standing,
    className: "bg-surface-50 text-ink-700 ring-1 ring-line-200"
  };
  return (
    <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${tone.className}`}>
      {tone.label}
    </span>
  );
}

export default function AnnualPlanPage() {
  const { toast } = useToast();
  const { user } = useAuth();
  const manager = canManageAnnualPlan(user);
  const { canDeleteRecords } = useAdminView();
  const confirm = useConfirm();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");

  const [years, setYears] = useState<AnnualPlanYear[] | null>(null);
  const [planYear, setPlanYear] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [standing, setStanding] = useState<PlanStandingFilter>("all");
  const [sort, setSort] = useState<PlanSort>("plannedStartDate");
  const [dir, setDir] = useState<"asc" | "desc">("asc");

  const [rows, setRows] = useState<AnnualPlanPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const [uploadOpen, setUploadOpen] = useState(false);
  const [report, setReport] = useState<PlanUploadReport | null>(null);
  const [promoting, setPromoting] = useState<AnnualPlanEntry | null>(null);

  const load = useRef(0);

  const query = useMemo(
    () =>
      planYear == null
        ? null
        : annualPlanQuery({ planYear, page, search, standing, sort, dir }),
    [planYear, page, search, standing, sort, dir]
  );

  const exportQuery = useMemo(
    () => (planYear == null ? null : annualPlanExportQuery({ planYear, search, standing, sort, dir })),
    [planYear, search, standing, sort, dir]
  );

  const refreshYears = useCallback(async () => {
    try {
      const found = await listPlanYears();
      setYears(found);
      setPlanYear((current) => current ?? found[0]?.planYear ?? null);
    } catch (caught) {
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "The plan years could not be read."
        )
      );
      setYears((current) => current ?? []);
    }
  }, []);

  useEffect(() => {
    void refreshYears();
  }, [refreshYears]);

  const refreshRows = useCallback(async () => {
    if (!query) return;
    const generation = ++load.current;
    try {
      const found = await listAnnualPlan(query);
      if (generation !== load.current) return;
      setRows(found);
      setError(null);
    } catch (caught) {
      if (generation !== load.current) return;
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "The directory could not be read."
        )
      );
      setRows((current) => current ?? { items: [], total: 0, page: 1, pageSize: 0, pages: 0 });
    }
  }, [query]);

  useEffect(() => {
    void refreshRows();
  }, [refreshRows]);

  const currentYear = years?.find((entry) => entry.planYear === planYear) ?? null;
  const yearLabel = currentYear?.planYearLabel ?? (planYear != null ? String(planYear) : "");

  async function act(entry: AnnualPlanEntry, what: "withdraw" | "reinstate") {
    setBusyId(entry.id);
    try {
      await (what === "withdraw" ? withdrawAnnualPlanEntry(entry.id) : reinstateAnnualPlanEntry(entry.id));
      await Promise.all([refreshRows(), refreshYears()]);
      toast({
        title:
          what === "withdraw"
            ? `${entry.workshopNo} was withdrawn from the plan`
            : `${entry.workshopNo} is back in the plan`,
        description:
          what === "withdraw" ? "It returns if a later sheet lists it." : undefined,
        tone: "success"
      });
    } catch (caught) {
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "That could not be done."
        )
      );
    } finally {
      setBusyId(null);
    }
  }

  async function remove(entry: AnnualPlanEntry) {
    const ok = await confirm(deleteConfirm(`Delete ${entry.workshopNo} from the plan?`, undefined, "This cannot be undone."));
    if (!ok) return;
    setBusyId(entry.id);
    try {
      await deleteAnnualPlanEntry(entry.id);
      await Promise.all([refreshRows(), refreshYears()]);
      toast({ title: `${entry.workshopNo} was deleted`, tone: "success" });
    } catch (caught) {
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "That could not be done."
        )
      );
    } finally {
      setBusyId(null);
    }
  }

  async function saveRemarks(entry: AnnualPlanEntry) {
    setBusyId(entry.id);
    try {
      const trimmed = draft.trim();
      await updateAnnualPlanNotes(entry.id, trimmed ? trimmed : null);
      setEditingId(null);
      await refreshRows();
      toast({ title: `Remarks on ${entry.workshopNo} saved`, tone: "success" });
    } catch (caught) {
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "The remarks could not be saved."
        )
      );
    } finally {
      setBusyId(null);
    }
  }

  const regionalStates = rows?.regionalStates ?? null;

  async function download(kind: "pro-forma" | "export") {
    try {
      if (kind === "pro-forma") await downloadPlanProForma();
      else if (exportQuery) await downloadPlanExport(exportQuery, yearLabel);
    } catch (caught) {
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "The workbook could not be downloaded."
        )
      );
    }
  }

  return (
    <>
      <PageHeader
        title="Annual plan"
        description="This year's planned workshops."
        icon={<CalendarRange className="h-5 w-5" aria-hidden />}
        actions={
          manager ? (
          <>
            <button type="button" className="field-button-secondary" onClick={() => void download("pro-forma")}>
              <FileDown className="h-4 w-4" aria-hidden />
              Pro-forma
            </button>
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => void download("export")}
              disabled={planYear == null}
            >
              <Download className="h-4 w-4" aria-hidden />
              Export this list
            </button>
            <button type="button" className="field-button" onClick={() => setUploadOpen(true)}>
              <Upload className="h-4 w-4" aria-hidden />
              Upload the plan
            </button>
          </>
          ) : null
        }
      />

      {manager ? <RegionalStatesPanel /> : null}

      {!manager && rows ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {regionalStates && regionalStates.length
            ? `Your state${regionalStates.length === 1 ? "" : "s"}: ${regionalStates.join(", ")}. You can edit remarks.`
            : "No states assigned to you yet."}
        </p>
      ) : null}

      {error ? (
        <p className="mb-4 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-red-800">
          {error}
        </p>
      ) : null}

      {report ? <UploadReportPanel report={report} /> : null}

      <div className="panel mb-5 grid gap-3 p-4 md:grid-cols-2 lg:grid-cols-4">
        <div className="grid gap-1">
          <span className="field-label">Plan year</span>
          <SearchableSelect
            ariaLabel="Plan year"
            value={planYear == null ? "" : String(planYear)}
            onChange={(value) => {
              setPlanYear(value ? Number(value) : null);
              setPage(1);
            }}
            options={(years ?? []).map((entry) => ({
              value: String(entry.planYear),
              label: `${entry.planYearLabel} · ${entry.total} planned`
            }))}
            placeholder={years == null ? "Loading…" : "Choose a plan year"}
            emptyLabel={years == null ? "Loading…" : "No plan uploaded yet"}
            disabled={years == null}
            advanceOnSelect={false}
          />
        </div>

        <div className="grid gap-1">
          <span className="field-label">Search</span>
          <SearchInput
            value={search}
            onChange={(value) => {
              setSearch(value);
              setPage(1);
            }}
            placeholder="Workshop No., title or venue"
            ariaLabel="Search this year's plan"
          />
        </div>

        <div className="grid gap-1">
          <span className="field-label">Show</span>
          <Dropdown
            value={standing}
            onChange={(value) => {
              setStanding(value as PlanStandingFilter);
              setPage(1);
            }}
            ariaLabel="Which entries to show"
            advanceOnSelect={false}
            options={PLAN_STANDINGS.map((value) => ({ value, label: PLAN_STANDING_LABELS[value] }))}
          />
        </div>

        <div className="grid gap-1">
          <span className="field-label">Order by</span>
          <Dropdown
            value={sort}
            onChange={(value) => {
              setSort(value as PlanSort);
              setPage(1);
            }}
            ariaLabel="Order the plan by"
            advanceOnSelect={false}
            options={PLAN_SORTS.map((value) => ({ value, label: PLAN_SORT_LABELS[value] }))}
          />
          <button
            type="button"
            className="mt-1 justify-self-start text-xs font-medium text-purple-700 underline dark:text-purple-300"
            onClick={() => {
              setDir((current) => (current === "asc" ? "desc" : "asc"));
              setPage(1);
            }}
          >
            {dir === "asc" ? "Oldest first" : "Newest first"}
          </button>
        </div>
      </div>

      {currentYear ? (
        <p className="mb-4 text-sm text-ink-muted">
          {currentYear.total} entries in the {currentYear.planYearLabel} plan · {currentYear.planned}{" "}
          still only planned · {currentYear.promoted} opened as workshops · {currentYear.withdrawn}{" "}
          withdrawn.
        </p>
      ) : null}

      {planYear == null && years != null && years.length === 0 && !manager ? (
        <EmptyState
          title="No rows for your states"
          body="No rows in your states."
        />
      ) : planYear == null && years != null && years.length === 0 ? (
        <EmptyState
          title="No plan has been uploaded yet"
          body="Download the pro-forma, fill it in and upload it."
        />
      ) : rows == null ? (
        <p className="text-sm text-ink-muted">Loading…</p>
      ) : rows.items.length === 0 ? (
        manager ? (
          <EmptyState
            title="Nothing in this year's plan yet"
            body="Download the pro-forma, fill it in and upload it."
          />
        ) : (
          <EmptyState
            title="No rows for your states"
            body="None in your states match."
          />
        )
      ) : (
        <div className="panel overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full min-w-5xl text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase tracking-wide text-ink-500">
                <tr>
                  <th className="px-4 py-2 font-medium">Workshop No.</th>
                  <th className="px-4 py-2 font-medium">Title</th>
                  <th className="px-4 py-2 font-medium">Where</th>
                  <th className="px-4 py-2 font-medium">Dates</th>
                  <th className="px-4 py-2 font-medium">Standing</th>
                  <th className="px-4 py-2 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.items.map((entry) => (
                  <Fragment key={entry.id}>
                  <tr className="border-t border-line-200 align-top">
                    <td className="px-4 py-3">
                      <span className="font-medium text-ink-900">{entry.workshopNo}</span>
                      <span className="mt-0.5 block text-xs text-ink-muted">
                        revision {entry.revision}
                        {entry.sheetRow != null ? ` · sheet row ${entry.sheetRow}` : ""}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.plannedTitle ?? <span className="text-ink-muted">—</span>}
                      {entry.workshopKindLabel ? (
                        <span className="mt-0.5 block text-xs text-ink-muted">{entry.workshopKindLabel}</span>
                      ) : null}
                      {entry.notes ? (
                        <span className="mt-1 block text-xs text-ink-700">Remarks: {entry.notes}</span>
                      ) : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {[entry.venue, entry.district, entry.state].filter(Boolean).join(" · ") || (
                        <span className="text-ink-muted">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.plannedStartDate ?? <span className="text-ink-muted">—</span>}
                      {entry.plannedEndDate ? (
                        <span className="mt-0.5 block text-xs text-ink-muted">to {entry.plannedEndDate}</span>
                      ) : null}
                    </td>
                    <td className="px-4 py-3">
                      <StandingChip standing={entry.standing} />
                      {entry.designWorkshopId ? (
                        <Link
                          href={`/design-workshops/${entry.designWorkshopId}`}
                          className="mt-1 block text-xs font-medium text-ministry-700 underline dark:text-ministry-300"
                        >
                          {entry.designWorkshopTitle ?? "Open the workshop"}
                        </Link>
                      ) : null}
                      {entry.sanction ? (
                        <span className="mt-1 block text-xs text-ink-700">
                          Sanction {entry.sanction.sanctionOrderNo} ·{" "}
                          {formatSanctionAmount(entry.sanction.sanctionAmount)}
                        </span>
                      ) : null}
                    </td>
                    <td className="px-4 py-3">
                      <ActionGroup>
                        <button
                          type="button"
                          className="field-button-secondary min-h-8! px-2.5! py-1! text-xs"
                          onClick={() => {
                            setEditingId(entry.id);
                            setDraft(entry.notes ?? "");
                          }}
                          disabled={busyId === entry.id}
                        >
                          Update remarks
                        </button>
                        {manager && entry.standing === "PLANNED" ? (
                          <>
                            <button
                              type="button"
                              className="field-button-secondary min-h-8! px-2.5! py-1! text-xs"
                              onClick={() => setPromoting(entry)}
                              disabled={busyId === entry.id}
                            >
                              <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                              Open workshop
                            </button>
                            <button
                              type="button"
                              className="field-button-secondary min-h-8! px-2.5! py-1! text-xs"
                              onClick={() => void act(entry, "withdraw")}
                              disabled={busyId === entry.id}
                            >
                              <Undo2 className="h-3.5 w-3.5" aria-hidden />
                              Withdraw
                            </button>
                          </>
                        ) : null}
                        {manager && entry.standing === "WITHDRAWN" ? (
                          <button
                            type="button"
                            className="field-button-secondary min-h-8! px-2.5! py-1! text-xs"
                            onClick={() => void act(entry, "reinstate")}
                            disabled={busyId === entry.id}
                          >
                            <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                            Reinstate
                          </button>
                        ) : null}
                        {manager && canDeleteRecords && entry.standing !== "PROMOTED" ? (
                          <button
                            type="button"
                            data-danger
                            className="field-danger min-h-8! rounded-md px-2.5! py-1! text-xs"
                            onClick={() => void remove(entry)}
                            disabled={busyId === entry.id}
                          >
                            Delete
                          </button>
                        ) : null}
                      </ActionGroup>
                    </td>
                  </tr>
                  {editingId === entry.id ? (
                    <tr className="bg-surface-50">
                      <td colSpan={6} className="px-4 py-3">
                        <label className="field-label" htmlFor={`remarks-${entry.id}`}>
                          Remarks on {entry.workshopNo}
                        </label>
                        <textarea
                          id={`remarks-${entry.id}`}
                          className="field-input mt-1 min-h-20 w-full"
                          maxLength={2000}
                          value={draft}
                          onChange={(event) => setDraft(event.currentTarget.value)}
                        />
                        <p className="mt-1 text-xs text-ink-muted">
                          A later upload with remarks replaces these.
                        </p>
                        <ActionGroup className="mt-2">
                          <button
                            type="button"
                            className="field-button min-h-8! px-2.5! py-1! text-xs"
                            onClick={() => void saveRemarks(entry)}
                            disabled={busyId === entry.id}
                          >
                            {busyId === entry.id ? "Saving…" : "Save remarks"}
                          </button>
                          <button
                            type="button"
                            className="field-button-secondary min-h-8! px-2.5! py-1! text-xs"
                            onClick={() => setEditingId(null)}
                            disabled={busyId === entry.id}
                          >
                            Cancel
                          </button>
                        </ActionGroup>
                      </td>
                    </tr>
                  ) : null}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={rows.page} pages={rows.pages} total={rows.total} onPage={setPage} />
        </div>
      )}

      <UploadPlanDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        planYear={planYear}
        planYearLabel={yearLabel}
        onUploaded={(uploaded) => {
          setUploadOpen(false);
          setReport(uploaded);
          setPage(1);
          setPlanYear((current) => current ?? uploaded.planYear);
          void refreshYears();
          void refreshRows();
        }}
      />

      <PromoteDialog
        entry={promoting}
        onClose={() => setPromoting(null)}
        onPromoted={(result: PromoteResult) => {
          setPromoting(null);
          void refreshRows();
          void refreshYears();
          toast({
            title: `${result.entry.workshopNo} is now a workshop`,
            description: "Later plan changes won't affect the workshop.",
            tone: "success"
          });
        }}
      />
    </>
  );
}
