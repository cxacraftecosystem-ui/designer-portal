"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { EmptyState } from "@/components/EmptyState";
import { FeedbackReportCard } from "@/components/feedback/FeedbackReportCard";
import { Pagination } from "@/components/Pagination";
import { apiFetch } from "@/lib/api";
import type { FeedbackReport, FeedbackReportPage } from "@/lib/feedback";

export function MyFeedbackReports({
  refreshToken
}: {
  refreshToken: number;
}) {
  const [items, setItems] = useState<FeedbackReport[] | null>(null);
  const [total, setTotal] = useState(0);
  const [pages, setPages] = useState(0);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);

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
        <div className="mb-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
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
