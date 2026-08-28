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

/**
 * THE TRASH — deleted design workshops, and the one control that brings one back.
 *
 * WHY IT EXISTS. `DELETE /design-workshops/{id}` is a soft delete: the row and every one of its 22
 * stage entries stay, only `deletedAt` is set, and the confirmation on `/design-workshops` promises
 * in so many words that "an admin can restore it". `POST /{id}/restore` has been deployed the whole time
 * and `restoreDesignWorkshop` has been a typed client function the whole time — with NO CALLER on
 * any surface, because nothing anywhere would list a deleted workshop. The only admin who could use
 * the safety net was one who had written the id down before deleting. This card is the missing half.
 *
 * MOUNTED INSIDE `/admin`'S OWN ADMIN BRANCH, and it does not re-check the role — the hub is
 * `isAdmin(user)` above it and admin chrome besides, so an admin browsing with admin view off never
 * renders this component at all and never asks the server for the trash. The gate that matters is
 * the server's: `deletedOnly` is refused with 403 for anyone below admin, exactly as the restore is.
 *
 * THE SHAPE IS THE RECOVERED-RECORDINGS CARD ONE SECTION UP, deliberately — one admin hub, one kind
 * of panel — with one difference that is not cosmetic: `/media/orphans` returns the whole list and
 * pages in the browser, while this route is paged by the SERVER, so `page` here is a request
 * parameter and the sentence above the table says how many rows are not on it.
 */
export function DeletedWorkshopsCard() {
  const [result, setResult] = useState<PageResult<DwSummary> | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  /**
   * The fetch-race guard this repository uses on every list page: count the generations and ignore
   * the late answer. `listResource` takes no `AbortSignal`, and what matters is not cancelling the
   * request but refusing to render an older page over a newer one — an admin clicking Next twice
   * quickly would otherwise settle on page 1's rows under page 2's pager.
   */
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
      // The rows already on screen are KEPT on a later failure, and only a failed FIRST load leaves
      // `result` null — that is what makes "we could not ask" (the error branch below) and "nothing
      // has been deleted" (the empty branch) two different things on screen. A card that emptied
      // itself on a dropped connection would report a full trash as an empty one.
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
      // Restoring the last row of a later page would leave the admin looking at an empty page of a
      // non-empty trash. Stepping back is the same reload either way — `setPage` re-runs the effect.
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
  /**
   * THIS PAGE HOLDS NOTHING AND THE TRASH IS NOT EMPTY — the state an empty-state panel must never
   * be drawn over.
   *
   * Read off `total` rather than off `cut`: `deletedWorkshopsCut` answers null when the server sent
   * no usable total (`cutOf`'s `Number.isFinite` guard), and an unknown total must fall back to the
   * ordinary empty state rather than to a page that tells an admin to go looking for rows nobody
   * can count. `total > 0` with no rows on the page is the one shape that is unambiguous.
   */
  const strandedPage = result !== null && result.items.length === 0 && result.total > 0;

  return (
    <section id="deleted-workshops" className="panel mt-6 overflow-hidden">
      <div className="border-b border-line-200 px-4 py-3">
        <h2 className="font-display font-bold text-ink-900">Deleted workshops</h2>
        <p className="text-sm text-ink-500">
          {/*
            The delete confirmation's own words, kept in step on purpose: it promises that nothing is
            erased and that an admin can restore it, and this is the screen that promise points at.
          */}
          Nothing was erased — a deleted workshop keeps every stage recorded against it. Restore one to put it back on
          the design workshops list.
        </p>
        {cutNotice ? <p className="mt-2 text-sm text-ink-700">{cutNotice}</p> : null}
        {/*
          ONE LIVE REGION, MOUNTED EMPTY AND NEVER UNMOUNTED. Assistive technology announces
          mutations inside a region that already existed, so a `<p aria-live>` that appears only once
          there is something to say is a sentence nobody hears — the same reason `Toast` renders its
          viewport with no toasts in it. The Restore button removes its own row from the table, so
          for a screen-reader user this sentence is the only evidence of what happened.
        */}
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
          {/*
            Three states, never two. A failed first load says so above in the banner and says here
            that the list is unknown; it must never fall through to the empty state, which would
            report an unread trash as an empty one.
          */}
          {error ? "The list could not be loaded, so what is in the trash is not known." : "Loading…"}
        </div>
      ) : result.items.length === 0 ? (
        <div className="p-4">
          {/*
            FOUR STATES, NOT THREE — and this is the branch that had only half of one. "No rows came
            back" is TWO different facts and they must never share a panel: the trash really is empty
            (`total === 0`), or this PAGE is past the end of a trash that is not (`total > 0`, which
            is what `strandedPage` is). Drawing "Nothing has been deleted" over the second one is
            this repository's most repeated bug class — a list that stops reading as a place with no
            records — and the sentence above the table would be contradicting it in the same breath,
            since `deletedWorkshopsNotice` already says the trash is not empty in exactly this state.

            It is reachable without a race, too: an admin on page 2 while a colleague restores those
            rows from another browser lands here on the next load.

            EmptyState's own markup rather than the component, in both arms: it hardcodes an <h2> and
            this card already has one — two level-2 headings inside one section is a structure a
            screen reader reads as two sections. Same look, correct level.
          */}
          <div className="rounded-xl border border-dashed border-line-200 bg-field-100 px-6 py-10 text-center">
            <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-field-200 text-field-600">
              <Undo2 className="h-5 w-5" aria-hidden />
            </div>
            {strandedPage ? (
              <>
                <h3 className="text-base font-medium text-ink">This page of the trash is empty</h3>
                <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-muted">
                  {/* The sentence is in the pure module with this card's others, not a ternary here:
                      it has two arms and neither can be produced by any live database, so written in
                      JSX it would only ever be exercised by somebody looking at a screen. */}
                  {strandedPageSentence(result.total, result.page)}
                </p>
              </>
            ) : (
              <>
                <h3 className="text-base font-medium text-ink">Nothing has been deleted</h3>
                <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-muted">
                  Every design workshop in the repository is live. Deleting one puts it here, where it can be restored.
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
                    <div className="font-medium text-ink-900">{item.title || "Untitled workshop"}</div>
                    {/*
                      The code is the identity an admin recognises a workshop by on paper, and it is
                      denormalised from stage 1 — so it is legitimately null on a workshop deleted
                      before that stage was saved, and the row must read as complete without it.
                    */}
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
                            {/* The word, not only the spinner: under reduced motion the spin is
                                zeroed by globals.css and a wordless button would say nothing. */}
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

      {/*
        ON `total`, NOT ON THE ROWS ON SCREEN. Keyed to `items.length` the pager vanished from the
        one page that most needs it — a page past the end of a non-empty trash, where Previous is the
        only way back and the rows are all behind it. An admin left there had no control at all: the
        page number lives in this component's state, not in the URL, so there was nothing to edit and
        no link to press. A genuinely empty trash still shows no pager, because `total` is 0.
      */}
      {result && result.total > 0 ? (
        <Pagination page={result.page} pages={result.pages} total={result.total} onPage={setPage} />
      ) : null}
    </section>
  );
}
