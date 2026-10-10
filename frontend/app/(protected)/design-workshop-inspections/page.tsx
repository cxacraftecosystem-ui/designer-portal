"use client";

/**
 * WORKSHOPS TO INSPECT — the inspector's own list, and the first half of the fifth scope.
 *
 * ── WHY A LIST IS HALF THE FEATURE, NOT A CONVENIENCE ─────────────────────────────────────────
 *
 * `list_inspectable_workshops` says it in its own docstring and it is the reason this page was
 * written before anything prettier: a scope the list does not honour tells its holder that a
 * workshop exists (they can open it by id) and simultaneously that it does not (it is absent from
 * every list they can reach). Nothing in either client navigates to a workshop by typed id, so an
 * inspector with no list has no feature at all — which is exactly the state this repository was in
 * while the whole server side sat finished and uncalled.
 *
 * ── THE EMPTY PAGE IS A REAL ANSWER AND HAS TO BE TOLD FROM A FAILURE ─────────────────────────
 *
 * There is no "all workshops" arm, no rank fallback and no `createdById` arm — an inspector creates
 * nothing — so an inspector with no inspection row sees an empty page, and that IS the whole scope.
 * `items === null` is "still asking", `[]` is "genuinely none" and `error` is a banner that never
 * empties the list, which is the house rule and matters more here than on most screens: the correct
 * empty state and a silent failure look identical, and the person reading it has no other surface to
 * cross-check against.
 *
 * ── SINCE 2026-10-09 THE PAGE IS AN ADMIN'S TOO, AND "NONE" HAS ONE SENTENCE ─────────────────
 *
 * The owner's ruling D3 lets a Ministry Admin, an admin and the master admin be appointed to inspect
 * a workshop, so they open this page like an Inspector / Reviewer does and read only the workshops
 * they were appointed to. The empty state says "You do not hold any inspection posts" to everybody
 * ({@link inspectionEmptyState}), and a 403 met by an admin means exactly that — a server that admits
 * them only once they hold a row, or one older than the ruling — so it is drawn as the same empty
 * state rather than as a banner ({@link inspectionRefusalMeansNoPosts}). An Inspector / Reviewer
 * refused here has met a fault and still gets the banner.
 *
 * ── WHY THE ROUTE IS A SIBLING OF /design-workshops AND NOT A PAGE INSIDE IT ──────────────────
 *
 * Because the API's prefix is, for a reason that is a guard rail rather than a filing decision:
 * every caller of every route on `/design-workshop-inspections` is by definition somebody
 * `load_workshop_or_404` turns away, and a route sharing the workshop prefix invites the next reader
 * to "fix" the inconsistency by widening that shared loader — which grants STAGE WRITES, because
 * `load_workshop_or_404(for_edit=True)` performs no role check at all. The client mirrors the split
 * so that nothing on this page can be reached from a `/design-workshops` link or vice versa.
 */

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { FileSearch, Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import {
  ELIGIBLE_INSPECTOR_SEARCH_MAX,
  inspectionEmptyState,
  inspectionRefusalMeansNoPosts,
  listInspectableDesignWorkshops
} from "@/lib/designWorkshopInspections";
import type { DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canInspectDesignWorkshops, roleLabel } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";

const PAGE_SIZE = 20;

/**
 * 300 ms, the same number as every other debounced search in this client. The list's search is an
 * `ILIKE '%term%'` over four columns of `DesignWorkshop`, so a keystroke that escapes the debounce
 * is a scan. Clearing the box does not wait — an empty term is the unnarrowed list.
 */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The failures this page can suffer, told apart in words.
 *
 * The 403 arm is the one worth writing rather than falling through to `error.message`: it is what an
 * account reaches if its tier is changed while the tab is open, and the server's own sentence names
 * the door they now want. `isUnreachable` rather than `isTransient`, for the reason the viewers panel
 * gives — a repository that ANSWERED and then failed must not be reported as a connection problem.
 */
function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "Couldn't connect, so this list couldn't be loaded. Check your connection and try again.";
  }
  if (error.status === 403) {
    return error.message;
  }
  return `${error.message} The list couldn't be loaded. Please try again.`;
}

export default function DesignWorkshopInspectionsPage() {
  const { user, loading } = useAuth();

  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PageResult<DwSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);

  /**
   * A generation counter rather than an abort: `listInspectableDesignWorkshops` goes through
   * `apiFetch`, which takes no `AbortSignal`, and what matters is ignoring the late answer. The
   * debounced search and the page buttons both go through this one effect so the counter stays the
   * only race protection this page needs.
   */
  const generation = useRef(0);

  useEffect(() => {
    const term = query.trim();
    const timer = window.setTimeout(
      () => {
        setApplied(term);
        // BACK TO PAGE ONE WHENEVER THE TERM SETTLES, unconditionally. A narrowed list is a
        // different list, and staying on page 3 of it is how a reader is shown "Page 3 of 1" with
        // nothing under it and concludes the search found nothing.
        setPage(1);
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    if (loading || !canInspectDesignWorkshops(user)) return;
    const current = generation.current + 1;
    generation.current = current;
    listInspectableDesignWorkshops({ page, pageSize: PAGE_SIZE, search: applied || undefined })
      .then((result) => {
        if (generation.current !== current) return;
        setData(result);
        setError(null);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        if (inspectionRefusalMeansNoPosts(err, user)) {
          // NOT A FAILURE: an admin the server will not yet show this list to holds no inspection
          // posts, and that is exactly what the empty state says. See the header.
          setData({ items: [], total: 0, page: 1, pageSize: PAGE_SIZE, pages: 0 });
          setError(null);
          return;
        }
        // The list is NOT emptied. An empty table under an error banner reads as "nothing is
        // assigned to me", which is the one thing this screen must never say by accident.
        setError(describeFailure(err));
      });
  }, [applied, page, loading, user]);

  /*
    THE SAME PREDICATE THE API APPLIES, APPLIED HERE TOO — a mirror and not a narrowing.
    `assert_inspection_surface` refuses everybody who may not be appointed to inspect, which since
    2026-10-09 leaves out a designer, a professor and the two directorate posts and lets in the three
    administering tiers. It names the door each refused reader actually wants rather than
    dead-ending on a padlock.

    `ROUTE_GUARDS` already refuses this path above the page — this is the second of the two lines,
    kept because a page that renders its shell before the guard settles would flash a list header at
    somebody who may not have one.
  */
  if (!loading && !canInspectDesignWorkshops(user)) {
    return (
      <div>
        <PageHeader title="Workshops to inspect" icon={<FileSearch className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Inspector / Reviewer access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            Workshops to inspect lists the design &amp; prototype workshops you have been appointed to inspect. It
            is open to Inspector / Reviewers, Ministry Admins, Admins and the Master Admin. Designers open their
            workshops from Design workshops; inspectors are appointed on Workshop oversight.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/guide" className="field-button-secondary">
              Open the walkthrough
            </Link>
          </div>
        </section>
      </div>
    );
  }

  const rows = data?.items ?? [];

  return (
    <div>
      <PageHeader
        title="Workshops to inspect"
        description="The design & prototype workshops you have been appointed to inspect. You can read every stage of one and change none of it."
        icon={<FileSearch className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      <div className="mb-4">
        <SearchInput
          onChange={(next) => setQuery(next.slice(0, ELIGIBLE_INSPECTOR_SEARCH_MAX))}
          onSubmit={() => {
            setApplied(query.trim());
            setPage(1);
          }}
          // The placeholder lists the columns searched; this names what is being searched.
          ariaLabel="Search design workshops"
          placeholder="Search by title, craft, cluster or workshop code"
          value={query}
        />
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          // null is "still asking" and [] is "genuinely none". Saying "nothing is assigned to you"
          // during a fetch is both wrong and, to somebody who has just been given an inspection,
          // alarming.
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState {...inspectionEmptyState(Boolean(applied))} />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {rows.map((workshop) => (
              <li key={workshop.id}>
                <Link
                  className="flex flex-col gap-2 px-4 py-3 transition hover:bg-surface-50 sm:flex-row sm:items-center sm:gap-4"
                  href={`/design-workshop-inspections/${workshop.id}`}
                >
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-ink-900">
                      {workshop.title?.trim() || "Untitled design workshop"}
                    </span>
                    {/* The code is denormalised from stage 1 and is null until that stage has been
                        saved — so its absence means "stage 1 is not done", not "missing". An
                        inspector reads that as a finding rather than as a gap in this screen. */}
                    <span className="block truncate text-xs text-ink-500">
                      {workshop.workshopCode ?? "No workshop code yet"}
                      {workshop.craftName ? ` · ${workshop.craftName}` : ""}
                      {workshop.clusterName ? ` · ${workshop.clusterName}` : ""}
                    </span>
                  </span>
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
        {data ? <Pagination onPage={setPage} page={data.page} pages={data.pages} total={data.total} /> : null}
      </section>
    </div>
  );
}
