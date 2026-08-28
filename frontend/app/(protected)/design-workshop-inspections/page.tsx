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
    return "This device cannot reach the repository, so this list could not be loaded. It is not empty — nothing was read at all. Check the connection and try again.";
  }
  if (error.status === 403) {
    return `${error.message} This list is not empty — it was not read at all.`;
  }
  return `${error.message} This list could not be loaded, which is not the same as having nothing to inspect.`;
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
        // The list is NOT emptied. An empty table under an error banner reads as "nothing is
        // assigned to me", which is the one thing this screen must never say by accident.
        setError(describeFailure(err));
      });
  }, [applied, page, loading, user]);

  /*
    THE SAME PREDICATE THE API APPLIES, APPLIED HERE TOO — a mirror and not a narrowing.
    `assert_inspection_surface` refuses everybody outside `INSPECTION_ROLES` with a 403, INCLUDING
    admins and the master admin, so this refusal is identical for an admin, a designer and a
    volunteer. It names the door each of them actually wants rather than dead-ending on a padlock,
    because an admin who is told only "forbidden" on a READ surface, in a product where admins read
    everything, will reasonably conclude the deployment is broken.

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
            The inspection surface belongs to the Inspector / Reviewer tier, and shows only the workshops an admin has
            assigned to that account. Designers and admins read design &amp; prototype workshops on Design workshops
            instead, and an admin chooses who inspects a workshop on Manage workshop access.
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
        description="The design & prototype workshops an admin has assigned you to inspect. You can read every stage of one and change none of it."
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
            <EmptyState
              title={applied ? "No workshop under your inspection matches that search" : "No workshop is assigned to you"}
              body={
                applied
                  ? "This searches only the workshops assigned to you, which is the whole of what you can read here. Clear the search to see them all."
                  : "An admin assigns inspections one workshop at a time, on Manage workshop access. Until they have, there is nothing here to read — this page is not hiding anything from you, and nothing failed to load."
              }
            />
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
