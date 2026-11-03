"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Stamp } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import {
  CLEARED_FILTER_OPTIONS,
  QUEUE_SORT_OPTIONS,
  QueueReadiness
} from "@/components/designworkshop/review/QueueReadiness";
import { Dropdown } from "@/components/ui/Dropdown";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import {
  APPROVAL_QUEUE_TABS,
  listReportsForApproval,
  signOffLines,
  type DwApprovalQueueState,
  type DwApprovalRow
} from "@/lib/designWorkshopApprovals";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canApproveDesignWorkshops } from "@/lib/permissions";
import type { DwClearedFilter, DwQueueSort } from "@/lib/reviewAids";
import type { PageResult } from "@/lib/types";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const PAGE_SIZE = 20;
const SEARCH_DEBOUNCE_MS = 300;
const SEARCH_MAX = 120;

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "No connection — couldn't load the list.";
  }
  return `${error.message} Couldn't load the list.`;
}

function standingLine(row: DwApprovalRow, state: DwApprovalQueueState): string {
  if (state === "awaiting") {
    const round = row.submissionRound && row.submissionRound > 1 ? ` · handed in ${row.submissionRound} times` : "";
    return row.lastHandedInAt ? `Waiting since ${formatDate(row.lastHandedInAt)}${round}` : `Waiting for a decision${round}`;
  }
  if (state === "returned") {
    return row.reviewedAt ? `Sent back on ${formatDate(row.reviewedAt)}` : "With its designers for corrections";
  }
  return signOffLines(row).join(" ");
}

export default function ReportsToApprovePage() {
  const { user, loading } = useAuth();

  const [state, setState] = useState<DwApprovalQueueState>("awaiting");
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [sort, setSort] = useState("");
  const [cleared, setCleared] = useState("");
  const [data, setData] = useState<PageResult<DwApprovalRow> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const generation = useRef(0);

  useEffect(() => {
    const asked = new URLSearchParams(window.location.search).get("state");
    const tab = APPROVAL_QUEUE_TABS.find((candidate) => candidate.value === asked);
    if (tab && tab.value !== "awaiting") setState(tab.value);
  }, []);

  useEffect(() => {
    const term = query.trim();
    const timer = window.setTimeout(
      () => {
        setApplied(term);
        setPage(1);
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    if (loading || !canApproveDesignWorkshops(user)) return;
    const current = generation.current + 1;
    generation.current = current;
    listReportsForApproval({
      state,
      page,
      pageSize: PAGE_SIZE,
      search: applied || undefined,
      sort: (sort || "default") as DwQueueSort,
      cleared: cleared as DwClearedFilter
    })
      .then((result) => {
        if (generation.current !== current) return;
        setData(result);
        setError(null);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        setError(describeFailure(err));
      });
  }, [state, applied, page, loading, user, sort, cleared]);

  const tab = APPROVAL_QUEUE_TABS.find((candidate) => candidate.value === state) ?? APPROVAL_QUEUE_TABS[0];
  const rows = data?.items ?? [];

  return (
    <div>
      <PageHeader
        title="Reports to approve"
        description="Reports handed in by designers."
        icon={<Stamp className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Which reports to list">
        {APPROVAL_QUEUE_TABS.map((candidate) => (
          <button
            key={candidate.value}
            type="button"
            aria-pressed={candidate.value === state}
            className={candidate.value === state ? "field-button" : "field-button-secondary"}
            onClick={() => {
              if (candidate.value === state) return;
              setState(candidate.value);
              setPage(1);
              setData(null);
            }}
          >
            {candidate.label}
          </button>
        ))}
      </div>

      <div className="mb-4">
        <SearchInput
          onChange={(next) => setQuery(next.slice(0, SEARCH_MAX))}
          onSubmit={() => {
            setApplied(query.trim());
            setPage(1);
          }}
          ariaLabel="Search reports"
          placeholder="Search by title, craft, cluster, workshop code or designer"
          value={query}
        />
      </div>

      <div className="mb-4 grid gap-2 sm:grid-cols-2" role="group" aria-label="Order and filter">
        <Dropdown
          value={sort}
          onChange={(next) => {
            setSort(next);
            setPage(1);
          }}
          options={[...QUEUE_SORT_OPTIONS]}
          ariaLabel="Order"
          advanceOnSelect={false}
        />
        <Dropdown
          value={cleared}
          onChange={(next) => {
            setCleared(next);
            setPage(1);
          }}
          options={[...CLEARED_FILTER_OPTIONS]}
          ariaLabel="Inspector verdict"
          advanceOnSelect={false}
        />
      </div>

      <section className="panel overflow-hidden" aria-label={tab.label}>
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title={applied ? "No report matches that search" : tab.empty}
              body={
                applied
                  ? `Nothing matches “${applied}”.`
                  : undefined
              }
            />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {rows.map((workshop) => (
              <li key={workshop.id}>
                <Link
                  className="flex flex-col gap-2 px-4 py-3 transition hover:bg-surface-50 sm:flex-row sm:items-center sm:gap-4"
                  href={`/design-workshop-approvals/${workshop.id}`}
                >
                  <div className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-ink-900">
                      {workshopDisplayTitle(workshop)}
                    </span>
                    <span className="block truncate text-xs text-ink-500">
                      {workshop.workshopCode ?? "No workshop code yet"}
                      {workshop.designerName ? ` · ${workshop.designerName}` : ""}
                      {workshop.craftName ? ` · ${workshop.craftName}` : ""}
                      {workshop.clusterName ? ` · ${workshop.clusterName}` : ""}
                    </span>
                    <span className="mt-0.5 block text-xs text-ink-700">{standingLine(workshop, state)}</span>
                    <QueueReadiness readiness={workshop.readiness} row={workshop} />
                  </div>
                  <span className="shrink-0">
                    <StatusBadge status={workshop.status} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {data && (data as { candidatesTruncated?: boolean }).candidatesTruncated ? (
          <p className="border-t border-line-200 px-4 py-2 text-xs text-ink-500">Ranked from the first 300 in this list.</p>
        ) : null}
        {data ? <Pagination onPage={setPage} page={data.page} pages={data.pages} total={data.total} /> : null}
      </section>
    </div>
  );
}
