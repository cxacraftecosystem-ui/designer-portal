"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Plus, Users } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { ArtisanCardSheetActions, useArtisanCardsEnabled } from "@/components/artisans/ArtisanCardSheetActions";
import { OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue, type FunnelWorkshop } from "@/components/FunnelFilters";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { canCreateRecords } from "@/lib/permissions";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useListMemory } from "@/components/hooks/useListMemory";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { RECORD_UPDATE_HREF, RECORD_VIEW_HREF } from "@/lib/recordView";
import type { Artisan, PageResult } from "@/lib/types";

export default function ArtisansPage() {
  const { adminMode } = useAdminView();
  const confirm = useConfirm();
  const [data, setData] = useState<PageResult<Artisan> | null>(null);
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelReady, setFunnelReady] = useState(false);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** Artisans ticked for a card print. Kept across pages so a sheet can gather rows from several. */
  const [ticked, setTicked] = useState<string[]>([]);
  const cardsEnabled = useArtisanCardsEnabled();
  const skipFirstDebounce = useRef(true);
  /**
   * Which fetch is the current one. Search is debounced and the funnel and pager fire the same
   * effect, so several requests are routinely in flight at once — and without this the answer to an
   * abandoned question could land last and win, showing results for a filter the researcher had
   * already moved on from. Counted rather than aborted because `listResource` takes no signal, and
   * ignoring a late answer is the part that matters.
   */
  const currentLoad = useRef(0);
  /**
   * Where the reader was — search, page, funnel, scroll — so Back from an artisan's page lands on
   * the same rows. Applied at the funnel's first answer, the one moment this page learns its own
   * defaults; see `useListMemory`.
   */
  const memory = useListMemory("artisans", { query, applied, page, funnel }, data !== null);

  async function load() {
    const generation = (currentLoad.current += 1);
    try {
      // Every funnel dropdown is sent to the SERVER, exactly as /products, /tools and /processes
      // send theirs. This used to send only `search` and `craftId` under a comment claiming
      // "/artisans supports craftId but not workshopId", and that claim was simply false:
      // `list_artisans` has declared `workshopId` for as long as the others have, and its clause is
      // BROADER than anything this page could do in the browser — it ORs the `Artisan.workshopId`
      // column with the `WorkshopArtisan` join, while the client intersection that used to live
      // below could only see the join rows the workshop row happened to carry.
      //
      // What the browser-side narrowing actually did, on a funnel that ALWAYS opens with a workshop
      // selected (FunnelFilters defaults to the most recently held one): the server returned the 20
      // newest-created artisans of the whole table — 431 measured on this database — and those 20
      // were then intersected against one workshop's people. If that workshop's artisans were
      // entered earlier, the intersection was empty and the page drew "No artisans found" while the
      // `Pagination` beneath it, reading the UNFILTERED envelope, insisted on "Page 1 of 22 · 431
      // records". And when a workshop carried no join rows at all the intersection was skipped
      // entirely, so the same control answered with EVERY artisan in the repository. One filter,
      // two opposite wrong answers, depending on which link path had been used.
      //
      // Do not put the client-side filter back. The narrowing has to be in the WHERE clause or
      // `total`/`pages` describe a different set from the rows, which is what put a pager promising
      // hundreds of records under an empty table.
      const result = await listResource<Artisan>("/artisans", {
        search: applied || undefined,
        workshopId: funnel.workshopId || undefined,
        craftId: funnel.craftId || undefined,
        page,
        pageSize: 20
      });
      if (generation !== currentLoad.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (generation !== currentLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load artisans");
    }
  }

  // Waits for the funnel's initial onChange (default = most recent workshop) before the first fetch.
  useEffect(() => {
    if (!funnelReady) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, applied, funnel]);

  // Live search: debounce typing by 350ms; Enter applies immediately via onSubmit.
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);

  // The selected workshop ROW is deliberately unused — it exists on this callback for pages that
  // genuinely need the workshop's own fields, and reading its `artisans` to narrow the table here is
  // the defect described at the request above.
  function onFunnelChange(next: FunnelValue, _workshop: FunnelWorkshop | null) {
    if (!funnelReady) {
      const remembered = memory.recall();
      if (remembered) {
        setFunnel(remembered.funnel ?? next);
        setQuery(remembered.query ?? "");
        setApplied(remembered.applied ?? "");
        setPage(remembered.page ?? 1);
        // The restored query must not be re-debounced into a page-1 reset. Only when it CHANGES the
        // box: an unchanged value runs no effect, and a flag left up would eat the next keystroke.
        if ((remembered.query ?? "") !== query) skipFirstDebounce.current = true;
        setFunnelReady(true);
        return;
      }
    }
    setFunnel(next);
    setPage(1);
    setFunnelReady(true);
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this artisan record?",
        "This permanently deletes the record. This action cannot be undone.",
        "Media, questionnaire answers and comments attached to this artisan go with it."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/artisans/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete artisan");
    }
  }

  // The rows ARE the answer. Every filter on this screen is in the request above, so nothing is
  // narrowed here and `data.total` / `data.pages` describe the same set the table draws.
  const rows = data ? data.items : [];

  return (
    <>
      <PageHeader
        title="Artisans"
        description="Create, search and maintain artisan profiles with craft, place and contact details."
        icon={<Users className="h-5 w-5" aria-hidden />}
        // Gated, not merely locked: an ungated "New …" invited every tier to press a button that
        // lands on the route guard's refusal. Below researcher the honest UI is no button, matching
        // `require_record_creator` on the server.
        actions={
          canCreateRecords(user) ? (
            <Link className="field-button" href="/artisans/new">
              <Plus className="h-4 w-4" aria-hidden />
              New artisan
            </Link>
          ) : null
        }
      />
      <div className="mb-3">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          // The placeholder names the COLUMNS, not the corpus, and a placeholder stops being the
          // accessible name the moment the box has something in it — so the name is stated here.
          ariaLabel="Search artisans"
          placeholder="Search by name, craft, place or notes"
        />
      </div>
      <FunnelFilters value={funnel} onChange={onFunnelChange} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {cardsEnabled && ticked.length ? (
        <section className="panel mb-3 grid gap-3 p-4" aria-label="Print artisan cards">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-medium text-ink-900">{ticked.length === 1 ? "1 artisan chosen" : `${ticked.length} artisans chosen`}</p>
            {/* a single text control beside the count, not an action row */}
            <button type="button" className="text-sm font-medium text-purple-700 underline dark:text-purple-300" onClick={() => setTicked([])}>
              Clear
            </button>
          </div>
          <ArtisanCardSheetActions source={{ artisanIds: ticked }} count={ticked.length} />
        </section>
      ) : null}
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No artisans found" body="Add an artisan profile before linking product, tool or workshop records." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  {cardsEnabled ? (
                    <th className="w-10 px-4 py-3">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-purple-700"
                        aria-label="Choose every artisan on this page for a card print"
                        checked={rows.length > 0 && rows.every((row) => ticked.includes(row.id))}
                        onChange={(event) => {
                          const ids = rows.map((row) => row.id);
                          setTicked((current) =>
                            event.target.checked
                              ? [...current, ...ids.filter((rowId) => !current.includes(rowId))]
                              : current.filter((rowId) => !ids.includes(rowId))
                          );
                        }}
                      />
                    </th>
                  ) : null}
                  <ResizableTh>Name</ResizableTh>
                  <ResizableTh>Craft</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Contact</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((artisan) => (
                  // The whole row opens the artisan, read-only; the name is the same act as a real
                  // link for the keyboard. Update, Discuss and Delete keep their own clicks — see
                  // `OpenableRow`. The launch cards that a row click used to reveal above this table
                  // now sit on the artisan's own page.
                  <OpenableRow key={artisan.id} href={RECORD_VIEW_HREF.artisan(artisan.id)}>
                    {cardsEnabled ? (
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          className="h-4 w-4 accent-purple-700"
                          aria-label={`Choose ${artisan.name} for a card print`}
                          checked={ticked.includes(artisan.id)}
                          onChange={(event) =>
                            setTicked((current) =>
                              event.target.checked ? [...current, artisan.id] : current.filter((rowId) => rowId !== artisan.id)
                            )
                          }
                        />
                      </td>
                    ) : null}
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <OpenableRowLink href={RECORD_VIEW_HREF.artisan(artisan.id)}>{artisan.name}</OpenableRowLink>
                        {/* Identity numbers are regulated data and never printed in a list: the chip
                            only answers "is this artisan's card on file?", which is what a
                            researcher scanning for gaps actually needs to know. */}
                        {artisan.pehchanCardNumber ? (
                          <span
                            className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-semibold text-field-700 dark:text-purple-300"
                            title="Artisan Pehchan Card number on file"
                          >
                            Pehchan
                          </span>
                        ) : null}
                      </div>
                      <div className="text-xs text-ink-500">{artisan.localName ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">{artisan.craft?.name ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{artisan.place}</td>
                    <td className="px-4 py-3 text-ink-700">{artisan.phone || artisan.email || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={artisan.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDate(artisan.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <Link className={rowAction("edit")} href={RECORD_UPDATE_HREF.artisan(artisan.id)}>
                          Update
                        </Link>
                        <button className={rowAction("neutral")} onClick={() => setCollabId(artisan.id)}>
                          Discuss
                        </button>
                        {adminMode ? (
                          <button data-danger className={rowAction("danger")} onClick={() => remove(artisan.id)}>
                            Delete
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </OpenableRow>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      <CollabDialog recordType="artisan" recordId={collabId} onClose={() => setCollabId(null)} />
    </>
  );
}
