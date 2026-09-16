"use client";

/**
 * THE MINISTRY'S ANNUAL DIRECTORY OF PLANNED WORKSHOPS.
 *
 * ── A TOP-LEVEL ROUTE AND NOT `/admin/annual-plan` ──────────────────────────────────────────────
 *
 * `/admin` gates on `isAdmin`, which is SET membership `{ADMIN, MASTER_ADMIN}` — and the hub page
 * re-checks it in the component as well. A MINISTRY_ADMIN at rank 48 is admitted by neither, so a
 * page nested under that prefix would refuse the one tier this directory exists for, twice. The
 * `ROUTE_GUARDS` row in `lib/permissions.ts` carries the same argument.
 *
 * ── EVERY LIST DECISION IS THE SERVER'S ─────────────────────────────────────────────────────────
 *
 * Filtering, searching, sorting and paging all travel as query parameters and are decided in
 * Postgres. A table that sorted the fifty rows it happens to be holding would be lying about which
 * fifty they are, and the lie is invisible because the column header looks like it worked — the
 * rule `app/(protected)/admin/designers/page.tsx` states.
 *
 * ── A ROW HERE IS NOT A WORKSHOP, AND THE SCREEN HAS TO SAY SO ──────────────────────────────────
 *
 * Two hundred to three hundred rows a year, none of which is a workshop until somebody opens it.
 * The standing column, the header copy and the promote dialog all repeat that, because a ministry
 * shown "287 workshops held this year" when 284 of them have not happened is the failure the whole
 * feature is built against.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
import { describeApiDetail } from "@/lib/api";

import {
  downloadPlanExport,
  downloadPlanProForma,
  listAnnualPlan,
  listPlanYears,
  reinstateAnnualPlanEntry,
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
import { UploadPlanDialog } from "./UploadPlanDialog";

/**
 * The label under which the standing column reads, plus the tone it is drawn in.
 *
 * A LOOKUP AND NOT A TERNARY. There are three standings today and the moment a fourth exists a
 * ternary would fold it into whichever branch was second, silently — the defect
 * `UploadReport.tsx`'s own provenance lookup records. `StatusBadge` is deliberately not reused:
 * its tone and label maps do not know these three words, and adding them there would put a
 * directory vocabulary into the component every RECORD list draws its status from.
 */
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

  /**
   * A GENERATION COUNTER, NOT AN ABORT. `listResource`/`apiFetch` take no signal, and what actually
   * matters is ignoring the LATE answer: a slow page 1 landing after a fast page 2 would repaint the
   * table with rows the reader has already scrolled past. The house rule for list pages.
   */
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
      // AN EMPTY ARRAY AND NOT NULL, so the screen stops saying "Loading…" and shows the honest
      // empty state instead. `null` here would leave a spinner running over a failed read for ever.
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
          what === "withdraw" ? "Nothing was deleted. It returns if a later sheet names it." : undefined,
        tone: "success"
      });
    } catch (caught) {
      // A REFUSAL AN ADMINISTRATOR MUST ACT ON IS NOT A TOAST — `aria-live="polite"` never
      // interrupts. Withdrawing a row that is already a workshop is refused with a sentence naming
      // what to do instead, and that belongs where they are looking.
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
        description="The ministry's directory of the workshops planned for the year — number, date, state, district and venue. A row here is a plan, not a workshop: it becomes one only when somebody opens it."
        icon={<CalendarRange className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <button type="button" className="field-button-secondary" onClick={() => void download("pro-forma")}>
              <FileDown className="h-4 w-4" aria-hidden />
              Pro-forma
            </button>
            {/*
              ── THE NEXT TWO BUTTONS LOOK IDENTICAL AND ARE GATED DIFFERENTLY, DELIBERATELY ─────

              EXPORT KEEPS `disabled={planYear == null}`. `exportQuery` is null while there is no
              year (:136-139) and `download("export")` already no-ops on a null query, so without
              the attribute this would be a control that silently does nothing. There is genuinely
              nothing to export out of an empty directory, and the disabled state says so honestly.

              UPLOAD NO LONGER DOES, AND THAT IS THE COLD-START REPAIR. It carried the same
              expression until 0.0.12, and that one `planYear == null` made the whole feature
              unusable on every fresh environment: `GET /annual-plan/years` answers `200 []` over an
              empty table, so `planYear` stays null (:145), `query` is null (:128-134), `refreshRows`
              returns at its guard (:164) BEFORE the generation counter and before any setter, and
              `rows` is null for ever. The screen then said "Loading…" under a select reading "No
              plan uploaded yet" — two state machines contradicting each other — with the one control
              that could have produced a plan year greyed out beside them and the dialog behind it
              not even mounted. The page needed a plan before it could be used and a plan could only
              arrive through the control it disabled while none existed.

              REMOVING IT UNGATES NOTHING. Who may upload is `canManageAnnualPlan`, enforced above
              this page by `AppShell`'s ROUTE_GUARDS row and again on the route by
              `require_annual_plan_manager` — the page rendering at all is the proof that both
              passed. Nor is the year needed on the wire: `upload_annual_plan` resolves it as
              `typed if typed is not None else parsed.planYear`, off the workbook's own Details
              sheet, and the blank pro-forma ships with that cell EMPTY for the administrator to
              fill in. "Download the pro-forma, type the year on its Details sheet, upload it" is
              the designed cold-start path, and these two lines were the only things in front of it.
            */}
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
        }
      />

      {error ? (
        <p className="mb-4 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600">
          {error}
        </p>
      ) : null}

      {report ? <UploadReportPanel report={report} /> : null}

      <div className="panel mb-5 grid gap-3 p-4 md:grid-cols-2 lg:grid-cols-4">
        <div className="grid gap-1">
          <label className="field-label" htmlFor="plan-year">
            Plan year
          </label>
          <select
            id="plan-year"
            className="field-input"
            value={planYear ?? ""}
            onChange={(event) => {
              setPlanYear(event.currentTarget.value ? Number(event.currentTarget.value) : null);
              setPage(1);
            }}
          >
            {years == null ? <option value="">Loading…</option> : null}
            {years?.length === 0 ? <option value="">No plan uploaded yet</option> : null}
            {years?.map((entry) => (
              <option key={entry.planYear} value={entry.planYear}>
                {entry.planYearLabel} · {entry.total} planned
              </option>
            ))}
          </select>
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
            ariaLabel="Which rows to show"
            // `advanceOnSelect={false}` on any dropdown that filters the screen it sits on — moving
            // focus to the next control after a filter change takes the reader away from the list
            // they changed it to see.
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
            // ⚠ PURPLE, AND THE MINISTRY SWAP WAS CONSIDERED HERE AND REFUSED. This is one of the
            // four ministry-only surfaces and a typographic accent does follow the surface — but
            // THIS IS A BUTTON, and the owner's ruling on ministry orange is surface accent only:
            // pale grounds, borders, the header chip, the desk tiles, and never an action control.
            // `tailwind.config.ts` annotates the rung itself "surface accent, never an action
            // colour", and `UploadPlanDialog.tsx` refuses the identical swap on its checkbox with
            // the arithmetic: `ministry-700` is ΔE 0.004 from `amber-800` — the same colour — and
            // `amber-800` is the "Withdrawn" pill `StandingChip` draws in the table directly below
            // this control. Orange here would have made a thing to PRESS and a fact about a planned
            // workshop indistinguishable by ink, a foot apart, and would have been the only
            // orange-inked control in the product.
            //
            // THE DARK-MODE HALF OF THAT SWAP IS KEPT, because it was a real fix and is not about
            // the hue: `text-purple-700` on `bg-card` in dark measures 2.32:1, `purple-300`
            // measures well over the floor. The `<Link>` further down this file stays ministry —
            // it is body copy that navigates, not a control.
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
          {currentYear.total} rows in the {currentYear.planYearLabel} plan · {currentYear.planned}{" "}
          still only planned · {currentYear.promoted} opened as workshops · {currentYear.withdrawn}{" "}
          withdrawn.
        </p>
      ) : null}

      {/*
        ── THREE STATES, NOT TWO, AND THE FIRST ONE IS NEW ─────────────────────────────────────

        `rows === null` is overloaded: it means BOTH "a request is in flight" and "no request was
        ever made", and until 0.0.12 only the first reading was drawn. On a repository whose
        directory is empty the second is the true one — `query` is null, so `refreshRows` returns at
        its guard and no request is ever created — and the screen rendered "Loading…" for ever,
        directly under a select already saying "No plan uploaded yet". Two machines, one screen, two
        incompatible answers.

        This is the same reasoning `refreshYears`'s catch already writes down at :153-155 for
        `years`, applied to the variable it was never applied to.

        ALL THREE CLAUSES ARE LOAD-BEARING. `years != null` keeps the honest "Loading…" while the
        years read is genuinely outstanding. `years.length === 0` is the directory holding no year at
        all. And `planYear == null` is what keeps this branch from lying in the other direction: the
        moment an upload lands, `onUploaded` sets the year (its `setPlanYear`, :577) and a list
        request really is in
        flight for a commit or two before `refreshYears` answers — drawing "No plan has been uploaded
        yet" under the upload report panel that just said one was.

        ⚠ IT INHERITS ONE AMBIGUITY, KNOWINGLY. `refreshYears`'s catch collapses `years` to `[]` as
        well as setting `error`, so a refused or failed years read reaches this branch too and reads
        as "the ministry has not uploaded the plan" — which is exactly the misreading
        `tests/test_annual_plan_web_surface.py` warns about. The red banner above is what tells them
        apart today. The repair is a `yearsFailed` flag rendered in the plan-year select (:313) as
        well as
        here, and it is deliberately not in this change: it is a second state on a second machine,
        and this branch makes the page no less honest than the select it now agrees with.

        THE TWO EMPTY STATES CARRY THE SAME BODY AND DIFFERENT TITLES, which is intended rather than
        a copy-paste: "no plan at all" and "this year's plan is empty" are the distinction the
        feature's own vocabulary turns on, and the NEXT MOVE out of both really is the pro-forma. The
        one case where the second title's body is beside the point is a reader who has typed a search
        term — `rows.items.length === 0` fires for a filter that matched nothing too. That sentence
        belongs to whoever gives the filtered-empty case its own state; it is not this fix.
      */}
      {planYear == null && years != null && years.length === 0 ? (
        <EmptyState
          title="No plan has been uploaded yet"
          body="Download the pro-forma, type the ministry's directory into it, and upload it. Correcting it later is the same act: upload the corrected sheet again."
        />
      ) : rows == null ? (
        <p className="text-sm text-ink-muted">Loading…</p>
      ) : rows.items.length === 0 ? (
        <EmptyState
          title="Nothing in this year's plan yet"
          body="Download the pro-forma, type the ministry's directory into it, and upload it. Correcting it later is the same act: upload the corrected sheet again."
        />
      ) : (
        <div className="panel overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[64rem] text-left text-sm">
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
                  <tr key={entry.id} className="border-t border-line-200 align-top">
                    <td className="px-4 py-3">
                      <span className="font-medium text-ink-900">{entry.workshopNo}</span>
                      <span className="mt-0.5 block text-xs text-ink-muted">
                        rev {entry.revision}
                        {entry.sheetRow != null ? ` · sheet row ${entry.sheetRow}` : ""}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.plannedTitle ?? <span className="text-ink-muted">—</span>}
                      {entry.workshopKindLabel ? (
                        <span className="mt-0.5 block text-xs text-ink-muted">{entry.workshopKindLabel}</span>
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
                          // MINISTRY INK, AND THIS ONE IS ALLOWED TO BE: it is body copy inside a
                          // cell that navigates, not a control — which is exactly the line the
                          // sort-direction button above is on the other side of. The `StandingChip`
                          // a line up deliberately does NOT move: its three tones are this
                          // directory's own vocabulary, and the withdrawn chip is `amber-800`,
                          // which is ΔE 0.004 from `ministry-700` — the same colour to the eye, so
                          // a chip and a link of the same ink would be telling two different kinds
                          // of thing apart by weight alone. That is also why the button above went
                          // back to purple; the argument in full is on it.
                          className="mt-1 block text-xs font-medium text-ministry-700 underline dark:text-ministry-300"
                        >
                          {entry.designWorkshopTitle ?? "Open the workshop"}
                        </Link>
                      ) : null}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-2">
                        {entry.standing === "PLANNED" ? (
                          <>
                            <button
                              type="button"
                              className="field-button-secondary !min-h-8 !px-2.5 !py-1 text-xs"
                              onClick={() => setPromoting(entry)}
                              disabled={busyId === entry.id}
                            >
                              <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                              Open workshop
                            </button>
                            <button
                              type="button"
                              className="field-button-secondary !min-h-8 !px-2.5 !py-1 text-xs"
                              onClick={() => void act(entry, "withdraw")}
                              disabled={busyId === entry.id}
                            >
                              <Undo2 className="h-3.5 w-3.5" aria-hidden />
                              Withdraw
                            </button>
                          </>
                        ) : null}
                        {entry.standing === "WITHDRAWN" ? (
                          <button
                            type="button"
                            className="field-button-secondary !min-h-8 !px-2.5 !py-1 text-xs"
                            onClick={() => void act(entry, "reinstate")}
                            disabled={busyId === entry.id}
                          >
                            <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                            Reinstate
                          </button>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={rows.page} pages={rows.pages} total={rows.total} onPage={setPage} />
        </div>
      )}

      {/*
        MOUNTED UNCONDITIONALLY. It was wrapped in `{planYear != null ? … : null}` until 0.0.12,
        which is the second half of the cold-start deadlock above: with no year the dialog did not
        exist, so `setUploadOpen(true)` flipped a state nothing read and the Upload button — had it
        been enabled on its own — would have looked broken in a new way. `FieldDialog` renders
        nothing while `open` is false, so an always-mounted dialog costs a closed portal and no more.

        `planYearLabel={yearLabel}` AND NOT `yearLabel || String(planYear)`. That fallback was
        harmless only for as long as the dialog could not mount without a year; the moment it can,
        `"" || String(null)` is `"null"`, and the dialog's title is `Upload the ${planYearLabel}
        plan` — which is how "Upload the null plan" reaches a ministry administrator's screen.
        `yearLabel` is already `""` in exactly that case (:188), and the dialog now branches on
        `planYear` for its title rather than on the label being falsy.
      */}
      <UploadPlanDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        planYear={planYear}
        planYearLabel={yearLabel}
        onUploaded={(uploaded) => {
          setUploadOpen(false);
          setReport(uploaded);
          setPage(1);
          /*
            LAND ON THE YEAR THAT WAS JUST UPLOADED, EXPLICITLY.

            `void refreshRows()` below is a NO-OP on the cold-start path: it closes over this
            render's `query`, which is still null. The screen does self-heal one commit later —
            `refreshYears` sets `planYear`, `query` changes, `refreshRows`'s identity changes and the
            effect at :183-185 refires — but nobody reading this handler would see why, and a repair
            that depends on a chain nobody can see is a repair that gets deleted as dead code.

            `current ??` AND NOT A PLAIN SET, which is the populated-table half: re-uploading last
            year's corrected sheet must not yank a reader off the year they were looking at. It only
            fires when there was no year to be on.
          */
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
            description: "Correcting the plan from now on corrects the directory, never the workshop.",
            tone: "success"
          });
        }}
      />
    </>
  );
}
