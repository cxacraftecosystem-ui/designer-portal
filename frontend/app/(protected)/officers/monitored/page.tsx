"use client";

/**
 * WORKSHOPS I MONITOR — the officer's own list, and the first half of the sixth scope.
 *
 * ── WHY A LIST IS HALF THE FEATURE, NOT A CONVENIENCE ─────────────────────────────────────────
 *
 * A scope the list does not honour tells its holder that a workshop exists (they can open it by id)
 * and simultaneously that it does not (it is absent from every list they can reach). Nothing in
 * either client navigates to a workshop by typed id, so an officer with no list has no feature at
 * all — which is exactly the state the inspector tier was in while its whole server side sat
 * finished and uncalled.
 *
 * ── THE EMPTY PAGE IS A REAL ANSWER AND HAS TO BE TOLD FROM A FAILURE ─────────────────────────
 *
 * There is no "all workshops" arm, no rank fallback and no `createdById` arm — an officer creates
 * nothing — so an officer with no oversight row sees an empty page, and that IS the whole scope.
 * `items === null` is "still asking", `[]` is "genuinely none" and `error` is a banner that never
 * empties the list. The correct empty state and a silent failure look identical, and the person
 * reading this has no other surface to cross-check against.
 *
 * ── AN ADMIN IS REFUSED HERE, AND THE REFUSAL NAMES THE OTHER DOOR ───────────────────────────
 *
 * `assert_oversight_surface` answers an ADMIN and a MASTER ADMIN a 403 by name, so this page mirrors
 * the server rather than narrowing it. An admin told only "forbidden" on a READ surface, in a
 * product where admins read everything, will reasonably conclude the deployment is broken — so the
 * panel names Workshop oversight, which is the half of this feature they DO hold.
 */

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Binoculars, Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import type { DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canReadWorkshopOversight, roleLabel } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";
import { listOverseenWorkshops, OVERSIGHT_SEARCH_MAX } from "../oversight";

const PAGE_SIZE = 20;

/** 300 ms, this app's number. Clearing the box does not wait — see the sibling list's note. */
const SEARCH_DEBOUNCE_MS = 300;

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so this list could not be loaded. It is not empty — nothing was read at all. Check the connection and try again.";
  }
  if (error.status === 403) {
    return `${error.message} This list is not empty — it was not read at all.`;
  }
  return `${error.message} This list could not be loaded, which is not the same as having nothing to monitor.`;
}

export default function WorkshopsIMonitorPage() {
  const { user, loading } = useAuth();

  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PageResult<DwSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);

  /** A generation counter rather than an abort: `apiFetch` takes no `AbortSignal`. */
  const generation = useRef(0);

  useEffect(() => {
    const term = query.trim();
    const timer = window.setTimeout(
      () => {
        setApplied(term);
        // BACK TO PAGE ONE WHENEVER THE TERM SETTLES. A narrowed list is a different list, and
        // staying on page 3 of it shows "Page 3 of 1" with nothing under it.
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
    listOverseenWorkshops({ page, pageSize: PAGE_SIZE, search: applied || undefined })
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

  if (!loading && !canReadWorkshopOversight(user)) {
    return (
      <div>
        <PageHeader title="Workshops I monitor" icon={<Binoculars className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          {/*
            PURPLE, DELIBERATELY, AND IT IS THE ONLY ACCENT ON THIS ROUTE THAT DID NOT MOVE.
            /officers/monitored is a ministry surface and `AppShell` stamps `data-surface="ministry"`
            on it, so its panels, borders and header chip took the ministry ramp in 0.0.12. This
            padlock did not, because a refusal is shown to somebody who is NOT a ministry account —
            a designer, a professor, an admin — and putting the ministry's own colour around the
            notice that they are not of the ministry would be a lie told in colour.
          */}
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Officer access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            Workshops I monitor lists the design &amp; prototype workshops a Ministry Admin has
            assigned to this account as its Assistant Director or Regional Director. Designers and
            admins read design &amp; prototype workshops on Design workshops instead; a Ministry
            Admin chooses who monitors a workshop on Workshop oversight.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/design-workshops" className="field-button-secondary">
              Design workshops
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
        title="Workshops I monitor"
        description="The design & prototype workshops a Ministry Admin has assigned you to supervise. You can read every stage of one and change none of it."
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

      <section className="panel overflow-hidden">
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title={
                applied
                  ? "No workshop you monitor matches that search"
                  : "No workshop is assigned to you"
              }
              body={
                applied
                  ? "This searches only the workshops assigned to you, which is the whole of what you can read here. Clear the search to see them all."
                  : "A Ministry Admin assigns an Assistant Director and a Regional Director one workshop at a time, on Workshop oversight. Until they have, there is nothing here to read — this page is not hiding anything from you, and nothing failed to load."
              }
            />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {rows.map((workshop) => (
              <li key={workshop.id}>
                <Link
                  className="flex flex-col gap-2 px-4 py-3 transition hover:bg-surface-50 sm:flex-row sm:items-center sm:gap-4"
                  href={`/officers/monitored/${workshop.id}`}
                >
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-ink-900">
                      {workshop.title?.trim() || "Untitled design workshop"}
                    </span>
                    {/* The code is denormalised from stage 1 and is null until that stage has been
                        saved — so its absence means "stage 1 is not done", not "missing". An officer
                        reads that as a finding rather than as a gap in this screen. */}
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
        {data ? (
          <Pagination onPage={setPage} page={data.page} pages={data.pages} total={data.total} />
        ) : null}
      </section>
    </div>
  );
}
