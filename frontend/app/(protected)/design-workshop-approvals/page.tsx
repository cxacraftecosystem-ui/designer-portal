"use client";

/**
 * REPORTS TO APPROVE — every design & prototype workshop report that has been handed in, as the
 * Ministry Admin reads it: the ones waiting for a decision first, then the approved ones waiting to
 * be handed on, the ones handed on, and the ones with their designers for corrections.
 *
 * ── ONE LIST, FOUR TABS, AND THE SERVER DECIDES WHAT EACH HOLDS ─────────────────────────────────
 *
 * Each tab is the server's `state` (`GET /design-workshop-approvals?state=…`), ordered by the server:
 * the longest wait first on "Waiting for approval", the oldest approval first on "Approved, not yet
 * handed on". Nothing is filtered or re-sorted here, so a count on the tab and the rows under it come
 * from one answer.
 *
 * ── A FAILED READ IS NOT AN EMPTY QUEUE ─────────────────────────────────────────────────────────
 *
 * `null` is "not read yet" and `[]` is "the server answered with none", and an error keeps whatever
 * was on screen and says so — an empty list under an error banner reads as "nothing is waiting for
 * me", which is the one thing this screen must never say by accident.
 */

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Stamp } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
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
import type { PageResult } from "@/lib/types";

const PAGE_SIZE = 20;
/** 300 ms, this app's number. Clearing the box does not wait. */
const SEARCH_DEBOUNCE_MS = 300;
/** The server's `max_length` on `search`. */
const SEARCH_MAX = 120;

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so this list could not be loaded. It is not empty — nothing was read at all. Check the connection and try again.";
  }
  return `${error.message} This list could not be loaded, which is not the same as having nothing to approve.`;
}

/** The one line under a row that says where its report stands, in this tab's terms. */
function standingLine(row: DwApprovalRow, state: DwApprovalQueueState): string {
  if (state === "awaiting") {
    const round = row.submissionRound && row.submissionRound > 1 ? ` · handed in ${row.submissionRound} times` : "";
    return row.lastHandedInAt ? `Waiting since ${formatDate(row.lastHandedInAt)}${round}` : `Waiting for a decision${round}`;
  }
  if (state === "returned") {
    return row.reviewedAt ? `Sent back on ${formatDate(row.reviewedAt)}` : "With its designers for corrections";
  }
  // Approved and handed on: who signed it off, in the words every screen uses.
  return signOffLines(row).join(" ");
}

export default function ReportsToApprovePage() {
  const { user, loading } = useAuth();

  const [state, setState] = useState<DwApprovalQueueState>("awaiting");
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PageResult<DwApprovalRow> | null>(null);
  const [error, setError] = useState<string | null>(null);

  /** A generation counter rather than an abort: `apiFetch` takes no `AbortSignal`. */
  const generation = useRef(0);

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
    listReportsForApproval({ state, page, pageSize: PAGE_SIZE, search: applied || undefined })
      .then((result) => {
        if (generation.current !== current) return;
        setData(result);
        setError(null);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        setError(describeFailure(err));
      });
  }, [state, applied, page, loading, user]);

  const tab = APPROVAL_QUEUE_TABS.find((candidate) => candidate.value === state) ?? APPROVAL_QUEUE_TABS[0];
  const rows = data?.items ?? [];

  return (
    <div>
      <PageHeader
        title="Reports to approve"
        description="Every design & prototype workshop report its designers have handed in. Open one to read it, approve it or send it back with a reason, and hand an approved report on to the office."
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
              // A different tab is a different list: back to page one, and "not read yet" until the
              // new answer arrives rather than the previous tab's rows under the new tab's name.
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

      <section className="panel overflow-hidden" aria-label={tab.label}>
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title={applied ? "No report matches that search" : tab.empty}
              body={
                applied
                  ? `Nothing in ${tab.label.toLowerCase()} matches “${applied}”. Clear the search to see the whole list.`
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
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-ink-900">
                      {workshop.title?.trim() || "Untitled design workshop"}
                    </span>
                    <span className="block truncate text-xs text-ink-500">
                      {workshop.workshopCode ?? "No workshop code yet"}
                      {workshop.designerName ? ` · ${workshop.designerName}` : ""}
                      {workshop.craftName ? ` · ${workshop.craftName}` : ""}
                      {workshop.clusterName ? ` · ${workshop.clusterName}` : ""}
                    </span>
                    <span className="mt-0.5 block text-xs text-ink-700">{standingLine(workshop, state)}</span>
                  </span>
                  <span className="shrink-0">
                    <StatusBadge status={workshop.status} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {data ? <Pagination onPage={setPage} page={data.page} pages={data.pages} total={data.total} /> : null}
      </section>
    </div>
  );
}
