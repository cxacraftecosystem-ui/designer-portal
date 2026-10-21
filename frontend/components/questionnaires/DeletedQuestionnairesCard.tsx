"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, Undo2 } from "lucide-react";

import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { readableError } from "@/components/review/reviewErrors";
import { formatDateTime } from "@/lib/format";
import { listQuestionnaires, restoreQuestionnaire, type QFormSummary } from "@/lib/questionnaireForms";
import type { PageResult } from "@/lib/types";

export const DELETED_QUESTIONNAIRES_PAGE_SIZE = 20;

/** The restore notice, named when the form has a title. */
export function restoredQuestionnaireNotice(title: string): string {
  const named = title.trim();
  return named ? `“${named}” is restored.` : "That questionnaire is restored.";
}

/**
 * THE QUESTIONNAIRE TRASH — soft-deleted forms and the one control that brings one back.
 *
 * `DELETE /questionnaires/{id}` only sets `deletedAt` (owner, 2026-10-20), so a deleted form keeps
 * every section, sitting and answer. A content admin's list is every deleted form; anyone else's is
 * the forms they authored (`ownOnly`), which the server scopes and lets them restore (owner,
 * 22 Oct 2026). `ownOnly` draws nothing until there is something to restore, so an author with an
 * empty trash sees no card. Same shape as `DeletedWorkshopsCard`: server-paged, rows kept on a
 * failed refresh, and a pager on `total` rather than on the rows shown.
 */
export function DeletedQuestionnairesCard({
  onRestored,
  ownOnly = false
}: {
  onRestored?: () => void;
  ownOnly?: boolean;
}) {
  const [result, setResult] = useState<PageResult<QFormSummary> | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const loadSeq = useRef(0);

  const load = useCallback(async (wanted: number) => {
    const seq = ++loadSeq.current;
    try {
      const next = await listQuestionnaires({ page: wanted, pageSize: DELETED_QUESTIONNAIRES_PAGE_SIZE, deletedOnly: true });
      if (seq !== loadSeq.current) return;
      setResult(next);
      setError(null);
    } catch (err) {
      if (seq !== loadSeq.current) return;
      setError(readableError(err, "The deleted questionnaires could not be listed."));
    }
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  async function restore(row: QFormSummary) {
    setBusyId(row.id);
    setError(null);
    setNotice(null);
    try {
      await restoreQuestionnaire(row.id);
      setNotice(restoredQuestionnaireNotice(row.title));
      onRestored?.();
      if ((result?.items.length ?? 0) <= 1 && page > 1) setPage(page - 1);
      else await load(page);
    } catch (err) {
      setError(readableError(err, "That questionnaire could not be restored."));
    } finally {
      setBusyId(null);
    }
  }

  if (ownOnly && !notice && !error && (!result || result.total === 0)) return null;

  return (
    <section id="deleted-questionnaires" className="panel mt-6 overflow-hidden">
      <div className="border-b border-line-200 px-4 py-3">
        <h2 className="font-display font-bold text-ink-900">{ownOnly ? "Your deleted questionnaires" : "Deleted questionnaires"}</h2>
        <p className="text-sm text-ink-500">Restoring one brings back its questions and answers.</p>
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
                <ResizableTh>Questionnaire</ResizableTh>
                <ResizableTh>Owner</ResizableTh>
                <ResizableTh>Deleted</ResizableTh>
                <ResizableTh>Deleted by</ResizableTh>
                <ResizableTh className="text-right">Actions</ResizableTh>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-200">
              {result.items.map((row) => (
                <tr key={row.id}>
                  <td className="px-4 py-3">
                    <div className="font-medium text-ink-900">{row.title}</div>
                    {row.designWorkshopTitle ? <div className="text-xs text-ink-500">{row.designWorkshopTitle}</div> : null}
                  </td>
                  <td className="px-4 py-3 text-ink-700">{row.ownerName ?? "—"}</td>
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
