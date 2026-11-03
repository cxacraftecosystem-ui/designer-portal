"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Binoculars, Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { QUEUE_SORT_OPTIONS, QueueReadiness } from "@/components/designworkshop/review/QueueReadiness";
import { EmptyState } from "@/components/EmptyState";
import { Dropdown } from "@/components/ui/Dropdown";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import type { DwReadiness } from "@/lib/reviewAids";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canReadWorkshopOversight, roleLabel } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";
import {
  listOverseenWorkshops,
  OVERSIGHT_SEARCH_MAX,
  oversightEmptyState,
  oversightRefusalMeansNoPosts
} from "../oversight";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const PAGE_SIZE = 20;

const SEARCH_DEBOUNCE_MS = 300;

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "Couldn't connect, so this list couldn't be loaded. Check your connection and try again.";
  }
  if (error.status === 403) {
    return error.message;
  }
  return `${error.message} The list couldn't be loaded. Please try again.`;
}

export default function WorkshopsIMonitorPage() {
  const { user, loading } = useAuth();

  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PageResult<DwSummary & { readiness?: DwReadiness | null }> | null>(null);
  const [sort, setSort] = useState("");
  const [error, setError] = useState<string | null>(null);

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
    if (loading || !canReadWorkshopOversight(user)) return;
    const current = generation.current + 1;
    generation.current = current;
    listOverseenWorkshops({ page, pageSize: PAGE_SIZE, search: applied || undefined, sort: sort || undefined })
      .then((result) => {
        if (generation.current !== current) return;
        setData(result);
        setError(null);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        if (oversightRefusalMeansNoPosts(err, user)) {
          setData({ items: [], total: 0, page: 1, pageSize: PAGE_SIZE, pages: 0 });
          setError(null);
          return;
        }
        setError(describeFailure(err));
      });
  }, [applied, page, loading, user, sort]);

  if (!loading && !canReadWorkshopOversight(user)) {
    return (
      <div>
        <PageHeader title="Workshops I monitor" icon={<Binoculars className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Officer access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            For Assistant Directors, Regional Directors, Ministry Admins and admins. Designers: use
            Design workshops.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <ActionGroup className="mt-7">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/design-workshops" className="field-button-secondary">
              Design workshops
            </Link>
          </ActionGroup>
        </section>
      </div>
    );
  }

  const rows = data?.items ?? [];

  return (
    <div>
      <PageHeader
        title="Workshops I monitor"
        description="Workshops you're named on as Assistant or Regional Director. Read-only; you can file notes."
        icon={<Binoculars className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      <div className="mb-4">
        <SearchInput
          onChange={(next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX))}
          onSubmit={() => {
            setApplied(query.trim());
            setPage(1);
          }}
          ariaLabel="Search design workshops"
          placeholder="Search by title, craft, cluster or workshop code"
          value={query}
        />
      </div>
      <div className="mb-4 max-w-sm">
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
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState {...oversightEmptyState(Boolean(applied), user)} />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {rows.map((workshop) => (
              <li key={workshop.id}>
                <Link
                  className="flex flex-col gap-2 px-4 py-3 transition hover:bg-surface-50 sm:flex-row sm:items-center sm:gap-4"
                  href={`/officers/monitored/${workshop.id}`}
                >
                  <div className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-ink-900">
                      {workshopDisplayTitle(workshop)}
                    </span>
                    <span className="block truncate text-xs text-ink-500">
                      {workshop.workshopCode ?? "No workshop code yet"}
                      {workshop.craftName ? ` · ${workshop.craftName}` : ""}
                      {workshop.clusterName ? ` · ${workshop.clusterName}` : ""}
                    </span>
                    <QueueReadiness readiness={workshop.readiness} row={workshop} />
                  </div>
                  <span className="shrink-0 text-xs text-ink-500">
                    {formatDate(workshop.startDate)}
                    {workshop.endDate ? ` – ${formatDate(workshop.endDate)}` : ""}
                  </span>
                  <span className="shrink-0">
                    <StatusBadge status={workshop.status} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {data ? (
          <Pagination onPage={setPage} page={data.page} pages={data.pages} total={data.total} />
        ) : null}
      </section>
    </div>
  );
}
