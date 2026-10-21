"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, Undo2 } from "lucide-react";

import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { readableError } from "@/components/review/reviewErrors";
import { apiFetch, listResource } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { PageResult, QuestionnaireInterview } from "@/lib/types";

export const DELETED_INTERVIEWS_PAGE_SIZE = 20;

/** The restore notice, named when the interview has a title. */
export function restoredInterviewNotice(title: string | null | undefined): string {
  const named = (title ?? "").trim();
  return named ? `“${named}” is restored.` : "That interview is restored.";
}

/** The server's own sentence for a set another live interview now holds (`INTERVIEW_SET_TAKEN`). */
export function interviewRestoreRefusal(payload: unknown): string | null {
  const detail = (payload as { detail?: unknown } | null)?.detail;
  if (!detail || typeof detail !== "object") return null;
  const record = detail as Record<string, unknown>;
  if (record.code !== "INTERVIEW_SET_TAKEN") return null;
  const message = String(record.message ?? "").trim();
  return message || "Another interview now covers these artisans. Merge or delete it first.";
}

export function listDeletedInterviews(page: number) {
  return listResource<QuestionnaireInterview>("/questionnaire/interviews", {
    page,
    pageSize: DELETED_INTERVIEWS_PAGE_SIZE,
    deletedOnly: "true"
  });
}

export function restoreInterview(id: string) {
  return apiFetch<QuestionnaireInterview>(`/questionnaire/interviews/${id}/restore`, { method: "POST" });
}

/**
 * THE INTERVIEW TRASH (H5, 21 Oct 2026). `DELETE /questionnaire/interviews/{id}` is a soft delete,
 * so the answers, the artisan links and the recordings stay; a content admin brings it back here.
 * Same shape as `DeletedQuestionnairesCard`.
 */
export function DeletedInterviewsCard({ onRestored }: { onRestored?: () => void }) {
  const [result, setResult] = useState<PageResult<QuestionnaireInterview> | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const loadSeq = useRef(0);

  const load = useCallback(async (wanted: number) => {
    const seq = ++loadSeq.current;
    try {
      const next = await listDeletedInterviews(wanted);
      if (seq !== loadSeq.current) return;
      setResult(next);
      setError(null);
    } catch (err) {
      if (seq !== loadSeq.current) return;
      setError(readableError(err, "The deleted interviews could not be listed."));
    }
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  async function restore(row: QuestionnaireInterview) {
    setBusyId(row.id);
    setError(null);
    setNotice(null);
    try {
      await restoreInterview(row.id);
      setNotice(restoredInterviewNotice(row.title));
      onRestored?.();
      if ((result?.items.length ?? 0) <= 1 && page > 1) setPage(page - 1);
      else await load(page);
    } catch (err) {
      const taken = interviewRestoreRefusal((err as { payload?: unknown } | null)?.payload);
      setError(taken ?? readableError(err, "That interview could not be restored."));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section id="deleted-interviews" className="panel mt-6 overflow-hidden">
      <div className="border-b border-line-200 px-4 py-3">
        <h2 className="font-display font-bold text-ink-900">Deleted interviews</h2>
        <p className="text-sm text-ink-500">Restoring one brings back its answers and recordings.</p>
        <div aria-live="polite">
          {notice ? (
            <p className="mt-2 rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">
              {notice}
            </p>
          ) : null}
          {error ? (
            <p className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
          ) : null}
        </div>
      </div>

      {!result ? (
        <div className="p-4 text-sm text-ink-500">{error ? "Could not load the list." : "Loading…"}</div>
      ) : result.items.length === 0 ? (
        <div className="p-4 text-sm text-ink-500">
          {result.total > 0 ? "Nothing on this page. Use the pager below." : "Nothing has been deleted."}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] text-left text-sm">
            <thead className="bg-surface-50 text-xs uppercase text-ink-500">
              <tr>
                <ResizableTh>Interview</ResizableTh>
                <ResizableTh>Artisans</ResizableTh>
                <ResizableTh>Deleted</ResizableTh>
                <ResizableTh>Deleted by</ResizableTh>
                <ResizableTh className="text-right">Actions</ResizableTh>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-200">
              {result.items.map((row) => (
                <tr key={row.id}>
                  <td className="px-4 py-3 font-medium text-ink-900">{row.title}</td>
                  <td className="px-4 py-3 text-ink-700">
                    {(row.artisans ?? []).map((link) => link.artisan.name).join(", ") || "—"}
                  </td>
                  <td className="px-4 py-3 text-ink-700">{row.deletedAt ? formatDateTime(row.deletedAt) : "—"}</td>
                  <td className="px-4 py-3 text-ink-700">{row.deletedByName ?? "Not recorded"}</td>
                  <td className="px-4 py-3 text-right">
                    <RowActions>
                      <button
                        type="button"
                        className={rowAction("edit")}
                        disabled={busyId !== null}
                        onClick={() => void restore(row)}
                      >
                        {busyId === row.id ? (
                          <>
                            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                            Restoring…
                          </>
                        ) : (
                          <>
                            <Undo2 className="h-4 w-4" aria-hidden />
                            Restore
                          </>
                        )}
                      </button>
                    </RowActions>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {result && result.total > 0 ? (
        <Pagination page={result.page} pages={result.pages} total={result.total} onPage={setPage} />
      ) : null}
    </section>
  );
}
