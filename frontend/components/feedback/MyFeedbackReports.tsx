"use client";

/**
 * Everything THIS account has filed, and where each of them has got to.
 *
 * ── THIS COMPONENT IS THE REDRESSAL HALF, AND IT IS THE POINT OF THE WHOLE FEATURE ─────────────
 *
 * The brief, verbatim: *"A grievance mechanism that cannot show a person their grievance was seen
 * is not a redressal mechanism."* Before this existed, a person who reported something had no way
 * to find out whether anybody had read it — the table had no status column, the only reader was the
 * master admin, and nothing anywhere listed a submission back to its author. So this list is not a
 * convenience view of a write path; it is the half that makes the write path mean anything.
 *
 * ── IT PAGES, AND IT SAYS SO WHEN IT IS SHOWING YOU PART OF THE LIST ───────────────────────────
 *
 * Rule 10: truncation must be stated on screen, because a list that quietly stops is
 * indistinguishable from a place with no records. `GET /feedback/reports/mine` answers the house
 * envelope, so `total` and `pages` are exact rather than inferred from a length, and the footer
 * says which slice of what is on screen.
 *
 * ── A FAILED LOAD DOES NOT EMPTY THE SCREEN ────────────────────────────────────────────────────
 *
 * `items === null` is "loading", `items === []` is "you have not filed anything" (§13). A fetch
 * that fails after a successful one keeps what is already drawn and adds the sentence, because
 * "you have never reported anything" is a factual claim and a dropped request is not evidence for
 * it — least of all on the screen somebody opened to check whether their complaint still exists.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { EmptyState } from "@/components/EmptyState";
import { FeedbackReportCard } from "@/components/feedback/FeedbackReportCard";
import { Pagination } from "@/components/Pagination";
import { apiFetch } from "@/lib/api";
import type { FeedbackReport, FeedbackReportPage } from "@/lib/feedback";

export function MyFeedbackReports({
  /**
   * Bumped by the page when a new report is filed, so this list re-reads.
   *
   * A NUMBER AND NOT THE REPORT ITSELF: splicing the created row into local state would show it
   * with the right words and the wrong POSITION the moment the reader is on page 2, and would
   * quietly disagree with `total`. One re-read costs a request that a person has just chosen to
   * make anyway, and it cannot be subtly wrong.
   */
  refreshToken
}: {
  refreshToken: number;
}) {
  const [items, setItems] = useState<FeedbackReport[] | null>(null);
  const [total, setTotal] = useState(0);
  const [pages, setPages] = useState(0);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);

  /**
   * A generation counter, not an abort: `apiFetch` takes no signal and what matters is IGNORING the
   * late answer, never cancelling it (§14.5). Paging quickly through three pages must leave page
   * three on screen, whichever response happens to land last.
   */
  const load = useRef(0);

  const read = useCallback(async (wanted: number) => {
    const generation = ++load.current;
    try {
      const result = await apiFetch<FeedbackReportPage>(`/feedback/reports/mine?page=${wanted}&pageSize=10`);
      if (generation !== load.current) return;
      setItems(result.items);
      setTotal(result.total);
      setPages(result.pages);
      setError(null);
    } catch (err) {
      if (generation !== load.current) return;
      setError(err instanceof Error ? err.message : "Could not read your reports.");
      // Only a FAILED FIRST load empties the list. A later failure keeps what is on screen — see
      // the header, and `ExistingMedia`, which does the same thing for the same reason.
      setItems((current) => current ?? []);
    }
  }, []);

  useEffect(() => {
    void read(page);
  }, [read, page, refreshToken]);

  return (
    <section className="mt-8">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="font-display text-lg font-bold text-ink-900">Your reports</h2>
        {total > 0 ? (
          <p className="text-xs text-ink-500">
            {total} {total === 1 ? "report" : "reports"}
          </p>
        ) : null}
      </div>

      {error ? (
        <div className="mb-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}

      {!items ? (
        <div className="panel p-4 text-sm text-ink-500">Loading...</div>
      ) : items.length === 0 ? (
        <div className="panel p-4">
          <EmptyState
            title="Nothing reported yet"
            body="Anything you send above appears here, with who has read it and what they said back."
          />
        </div>
      ) : (
        <>
          <div className="grid gap-3">
            {items.map((report) => (
              <FeedbackReportCard key={report.id} report={report} />
            ))}
          </div>
          {/*
            Wrapped in a `panel` because `Pagination` draws a bare `border-t` footer meant to sit
            INSIDE a bordered container — the list pages all mount it as the last child of one. Left
            loose under a stack of cards it reads as a rule ruled across the page from nowhere.
          */}
          {pages > 1 ? (
            <div className="panel mt-3">
              <Pagination page={page} pages={pages} total={total} onPage={setPage} />
            </div>
          ) : null}
        </>
      )}
    </section>
  );
}
