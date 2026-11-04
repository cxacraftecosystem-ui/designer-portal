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
import { CompletenessFilter } from "@/components/records/CompletenessFilter";
import { RowCompletenessRing } from "@/components/records/CompletenessRing";
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
import { parseCompletenessFilter, type CompletenessFilter as CompletenessValue } from "@/lib/completenessCore";
import { formatDate } from "@/lib/format";
import { RECORD_UPDATE_HREF, RECORD_VIEW_HREF } from "@/lib/recordView";
import type { Artisan, PageResult } from "@/lib/types";
import { phoneOf } from "@/lib/standardValues";

export default function ArtisansPage() {
  const { canDeleteRecords } = useAdminView();
  const confirm = useConfirm();
  const [data, setData] = useState<PageResult<Artisan> | null>(null);
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelReady, setFunnelReady] = useState(false);
  const [completeness, setCompleteness] = useState<CompletenessValue>("");
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ticked, setTicked] = useState<string[]>([]);
  const cardsEnabled = useArtisanCardsEnabled();
  const skipFirstDebounce = useRef(true);
  const currentLoad = useRef(0);
  const memory = useListMemory("artisans", { query, applied, page, funnel, filters: { completeness } }, data !== null);

  async function load() {
    const generation = (currentLoad.current += 1);
    try {
      const result = await listResource<Artisan>("/artisans", {
        search: applied || undefined,
        workshopId: funnel.workshopId || undefined,
        craftId: funnel.craftId || undefined,
        completeness: completeness || undefined,
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

  useEffect(() => {
    if (!funnelReady) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, applied, funnel, completeness]);

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

  function onFunnelChange(next: FunnelValue, _workshop: FunnelWorkshop | null) {
    if (!funnelReady) {
      const remembered = memory.recall();
      if (remembered) {
        setFunnel(remembered.funnel ?? next);
        setQuery(remembered.query ?? "");
        setApplied(remembered.applied ?? "");
        setPage(remembered.page ?? 1);
        setCompleteness(parseCompletenessFilter(remembered.filters?.completeness));
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

  const rows = data ? data.items : [];

  return (
    <>
      <PageHeader
        title="Artisans"
        description="Create, search and maintain artisan profiles with craft, place and contact details."
        icon={<Users className="h-5 w-5" aria-hidden />}
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
          ariaLabel="Search artisans"
          placeholder="Search by name, craft, place or notes"
        />
      </div>
      <FunnelFilters value={funnel} onChange={onFunnelChange} />
      <div className="mb-3">
        <CompletenessFilter
          value={completeness}
          onChange={(next) => {
            setCompleteness(next);
            setPage(1);
          }}
        />
      </div>
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {cardsEnabled && ticked.length ? (
        <section className="panel mb-3 grid gap-3 p-4" aria-label="Print artisan cards">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-medium text-ink-900">{ticked.length === 1 ? "1 artisan chosen" : `${ticked.length} artisans chosen`}</p>
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
                        <RowCompletenessRing block={artisan.completeness} />
                        <OpenableRowLink href={RECORD_VIEW_HREF.artisan(artisan.id)}>{artisan.name}</OpenableRowLink>
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
                    <td className="px-4 py-3 text-ink-700">{phoneOf(artisan) || artisan.email || "-"}</td>
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
                        {canDeleteRecords ? (
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
