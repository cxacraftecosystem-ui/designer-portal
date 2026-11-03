"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, Undo2 } from "lucide-react";

import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import {
  DELETED_WORKSHOPS_PAGE_SIZE,
  deletedByLabel,
  deletedWorkshopsCut,
  deletedWorkshopsNotice,
  restoredNotice,
  strandedPageSentence
} from "@/components/admin/deletedWorkshops";
import { readableError } from "@/components/review/reviewErrors";
import { listDesignWorkshops, restoreDesignWorkshop, type DwSummary } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import type { PageResult } from "@/lib/types";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

export function DeletedWorkshopsCard() {
  const [result, setResult] = useState<PageResult<DwSummary> | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const loadSeq = useRef(0);

  const load = useCallback(async (wanted: number) => {
    const seq = ++loadSeq.current;
    try {
      const next = await listDesignWorkshops({
        page: wanted,
        pageSize: DELETED_WORKSHOPS_PAGE_SIZE,
        deletedOnly: true
      });
      if (seq !== loadSeq.current) return;
      setResult(next);
      setError(null);
    } catch (err) {
      if (seq !== loadSeq.current) return;
      setError(readableError(err, "The deleted workshops could not be listed."));
    }
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  async function restore(workshop: DwSummary) {
    setBusyId(workshop.id);
    setError(null);
    setNotice(null);
    try {
      await restoreDesignWorkshop(workshop.id);
      setNotice(restoredNotice(workshop.title));
      const lastOnPage = (result?.items.length ?? 0) <= 1 && page > 1;
      if (lastOnPage) setPage(page - 1);
      else await load(page);
    } catch (err) {
      setError(readableError(err, "That workshop could not be restored."));
    } finally {
      setBusyId(null);
    }
  }

  const cut = result ? deletedWorkshopsCut(result) : null;
  const cutNotice = deletedWorkshopsNotice(cut);
  const strandedPage = result !== null && result.items.length === 0 && result.total > 0;

  return (
    <section id="deleted-workshops" className="panel mt-6 overflow-hidden">
      <div className="border-b border-line-200 px-4 py-3">
        <h2 className="font-display font-bold text-ink-900">Deleted workshops</h2>
        <p className="text-sm text-ink-500">
          Nothing was erased — a deleted workshop keeps every stage recorded against it. Restore one to put it back on
          the design workshops list.
        </p>
        {cutNotice ? <p className="mt-2 text-sm text-ink-700">{cutNotice}</p> : null}
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
        <div className="p-4 text-sm text-ink-500">
          {error ? "The list could not be loaded, so what is in the trash is not known." : "Loading…"}
        </div>
      ) : result.items.length === 0 ? (
        <div className="p-4">
          <div className="rounded-xl border border-dashed border-line-200 bg-field-100 px-6 py-10 text-center">
            <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-field-200 text-field-600 dark:text-purple-300">
              <Undo2 className="h-5 w-5" aria-hidden />
            </div>
            {strandedPage ? (
              <>
                <h3 className="text-base font-medium text-ink">This page of the trash is empty</h3>
                <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-muted">
                  {strandedPageSentence(result.total, result.page)}
                </p>
              </>
            ) : (
              <>
                <h3 className="text-base font-medium text-ink">Nothing has been deleted</h3>
                <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-muted">
                  Deleted design workshops appear here, where they can be restored.
                </p>
              </>
            )}
          </div>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] text-left text-sm">
            <thead className="bg-surface-50 text-xs uppercase text-ink-500">
              <tr>
                <ResizableTh>Workshop</ResizableTh>
                <ResizableTh>Craft</ResizableTh>
                <ResizableTh>Place</ResizableTh>
                <ResizableTh>Deleted</ResizableTh>
                <ResizableTh>Deleted by</ResizableTh>
                <ResizableTh className="text-right">Actions</ResizableTh>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-200">
              {result.items.map((item) => (
                <tr key={item.id}>
                  <td className="px-4 py-3">
                    <div className="font-medium text-ink-900">{workshopDisplayTitle(item)}</div>
                    {item.workshopCode ? <div className="text-xs text-ink-500">{item.workshopCode}</div> : null}
                  </td>
                  <td className="px-4 py-3 text-ink-700">{item.craftName ?? "—"}</td>
                  <td className="px-4 py-3 text-ink-700">
                    {[item.district, item.state].filter(Boolean).join(", ") || "—"}
                  </td>
                  <td className="px-4 py-3 text-ink-700">
                    {item.deletedAt ? formatDateTime(item.deletedAt) : "—"}
                  </td>
                  <td className="px-4 py-3 text-ink-700">{deletedByLabel(item)}</td>
                  <td className="px-4 py-3 text-right">
                    <RowActions>
                      <button
                        type="button"
                        className={rowAction("edit")}
                        disabled={busyId !== null}
                        onClick={() => void restore(item)}
                      >
                        {busyId === item.id ? (
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
