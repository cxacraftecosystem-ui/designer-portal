"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Boxes, ClipboardList, Hammer, Plus, Users } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
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
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Artisan, PageResult } from "@/lib/types";

/**
 * The block of launch cards a selected artisan reveals, named so the control that reveals it can
 * declare what it controls. Rendered only while something is selected, which is why every
 * `aria-controls` below is conditional — pointing at an id that is not in the document is worse
 * than pointing at nothing.
 */
const LAUNCH_PANEL_ID = "artisan-entry-launchers";

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
  const [selectedArtisan, setSelectedArtisan] = useState<Artisan | null>(null);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);
  /**
   * Which fetch is the current one. Search is debounced and the funnel and pager fire the same
   * effect, so several requests are routinely in flight at once — and without this the answer to an
   * abandoned question could land last and win, showing results for a filter the researcher had
   * already moved on from. Counted rather than aborted because `listResource` takes no signal, and
   * ignoring a late answer is the part that matters.
   */
  const currentLoad = useRef(0);

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

  function artisanEntryHref(path: string, artisan: Artisan) {
    const params = new URLSearchParams({
      artisanId: artisan.id,
      artisanName: artisan.name,
      place: artisan.place
    });
    if (artisan.craftId) params.set("craftId", artisan.craftId);
    if (artisan.craft?.name) params.set("craftName", artisan.craft.name);
    return `${path}?${params.toString()}`;
  }

  // The rows ARE the answer. Every filter on this screen is in the request above, so nothing is
  // narrowed here and `data.total` / `data.pages` describe the same set the table draws.
  const rows = data ? data.items : [];

  return (
    <>
      <PageHeader
        title="Artisans"
        description="Create, search and maintain artisan profiles with craft, place and contact metadata."
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
          placeholder="Search by name, craft, place or notes"
        />
      </div>
      <FunnelFilters value={funnel} onChange={onFunnelChange} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {selectedArtisan ? (
        <section id={LAUNCH_PANEL_ID} className="mb-5 grid gap-3 md:grid-cols-3">
          {[
            { href: artisanEntryHref("/tools/new", selectedArtisan), title: "Make a tool entry", body: "Document a tool used by this artisan.", icon: Hammer },
            { href: artisanEntryHref("/products/new", selectedArtisan), title: "Make a product entry", body: "Record an object, product or sample.", icon: Boxes },
            { href: artisanEntryHref("/questionnaire", selectedArtisan), title: "Start questionnaire", body: "Open the interview with RESP prefilled.", icon: ClipboardList }
          ].map((item) => (
            <Link key={item.href} href={item.href} className="panel group flex min-h-32 items-start gap-3 p-4 transition hover:-translate-y-0.5 hover:shadow-panel">
              <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-field-200 text-field-700">
                <item.icon className="h-5 w-5" aria-hidden />
              </span>
              <span>
                <span className="block font-display font-bold text-xl text-ink">{item.title}</span>
                <span className="mt-1 block text-sm leading-6 text-ink-muted">{item.body}</span>
                <span className="mt-3 block text-xs font-semibold uppercase text-field-700">{selectedArtisan.name}</span>
              </span>
            </Link>
          ))}
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
                  <tr key={artisan.id} className="cursor-pointer hover:bg-field-100" onClick={() => setSelectedArtisan(artisan)}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        {/*
                          A REAL BUTTON, because the row's own `onClick` is the only clickable table
                          row in this frontend and a keyboard could not reach it. The row carries no
                          `tabIndex`, no key handler and — deliberately — no `role="button"`: that
                          role would destroy the implicit `row` role and orphan all seven cells for a
                          screen reader, which is a bigger loss than the one it fixes. The pointer
                          affordance on the row stays exactly as it was; this is the same action
                          offered again as something the Tab key can land on and Enter or Space can
                          press. `stopPropagation` so a mouse click here does not also run the row's
                          handler — every other control in this row already does the same.

                          `aria-expanded` is what tells a reader the press DID something: the launch
                          cards it reveals are rendered above the table, well out of earshot of the
                          row that summoned them. Which is also why this button TOGGLES while the
                          row's own handler stays select-only. `aria-expanded="true"` is a promise
                          that pressing again collapses the region; nothing else in this file ever
                          clears `selectedArtisan`, so without the toggle the attribute would say
                          "expanded" for the rest of the session and the press would do nothing —
                          worse for the reader who trusts it than the pointer-only row it replaced.
                          A mouse user loses nothing: clicking anywhere else on the row still
                          selects, and only this control closes.
                        */}
                        <button
                          type="button"
                          onClick={(event) => {
                            event.stopPropagation();
                            setSelectedArtisan((current) => (current?.id === artisan.id ? null : artisan));
                          }}
                          aria-expanded={selectedArtisan?.id === artisan.id}
                          aria-controls={selectedArtisan?.id === artisan.id ? LAUNCH_PANEL_ID : undefined}
                          className="rounded-sm text-left font-medium text-ink-900 underline-offset-2 hover:underline"
                        >
                          {artisan.name}
                        </button>
                        {/* Identity numbers are regulated data and never printed in a list: the chip
                            only answers "is this artisan's card on file?", which is what a
                            researcher scanning for gaps actually needs to know. */}
                        {artisan.pehchanCardNumber ? (
                          <span
                            className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-semibold text-field-700"
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
                        <Link
                          className={rowAction("edit")}
                          href={`/artisans/${artisan.id}/edit`}
                          onClick={(event) => event.stopPropagation()}
                        >
                          Edit
                        </Link>
                        <button
                          className={rowAction("neutral")}
                          onClick={(event) => {
                            event.stopPropagation();
                            setCollabId(artisan.id);
                          }}
                        >
                          Discuss
                        </button>
                        {adminMode ? (
                          <button
                            className={rowAction("danger")}
                            onClick={(event) => {
                              event.stopPropagation();
                              remove(artisan.id);
                            }}
                          >
                            Delete
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </tr>
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
