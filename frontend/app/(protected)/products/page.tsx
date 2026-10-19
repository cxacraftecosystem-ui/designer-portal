"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Boxes, Plus } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { DownloadCsvButton } from "@/components/DownloadCsvButton";
import { useAuth } from "@/components/AuthProvider";
import { canCreateRecords, canDownloadDataset } from "@/lib/permissions";
import { EmptyState } from "@/components/EmptyState";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue, type FunnelWorkshop } from "@/components/FunnelFilters";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { PageHeader } from "@/components/PageHeader";
import { CompletenessFilter } from "@/components/records/CompletenessFilter";
import { RowCompletenessRing } from "@/components/records/CompletenessRing";
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
import type { PageResult, ProductDocumentation } from "@/lib/types";
import { exportFilename } from "@/lib/naming";

export default function ProductsPage() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const { adminMode } = useAdminView();
  const [data, setData] = useState<PageResult<ProductDocumentation> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelReady, setFunnelReady] = useState(false);
  const [completeness, setCompleteness] = useState<CompletenessValue>("");
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);
  /**
   * Which fetch is the current one — the same counter /artisans, /questionnaires and
   * /design-workshops carry, and for the same reason. The search box debounces the APPLIED TERM,
   * not the request, and the pager and the funnel fire the same effect, so two or three requests
   * are routinely in flight together. Without this the answer to an abandoned question can land
   * LAST and win.
   *
   * The failure is worse than a stale table, because `<Pagination>` below is driven by the ANSWER
   * (`page={data.page}`) while the refetch effect is driven by STATE (`page`). Let request A (page
   * 1) land after request B (page 2) and you get `data.page === 1` with `page === 2`; Next then
   * computes `onPage(data.page + 1)` = `setPage(2)`, React bails on the identical scalar, no
   * dependency changes, no request is issued — the Next button is simply dead until Previous is
   * pressed. Audit 2026-08-15 filed that against this file, /tools, /processes and /media.
   *
   * Counted rather than aborted because `listResource` takes no `AbortSignal`; ignoring the late
   * answer is the part that matters. If you add another `setData`/`setError` to `load()`, put the
   * guard in front of it too — a single unguarded write restores the dead pager.
   */
  const currentLoad = useRef(0);
  /** Search, page, funnel and scroll, so Back from a product's page lands on the same rows. */
  const memory = useListMemory("products", { query, applied, page, funnel, filters: { completeness } }, data !== null);

  async function load() {
    const generation = (currentLoad.current += 1);
    try {
      // /products supports all three funnel params directly.
      const result = await listResource<ProductDocumentation>("/products", {
        search: applied || undefined,
        workshopId: funnel.workshopId || undefined,
        craftId: funnel.craftId || undefined,
        artisanId: funnel.artisanId || undefined,
        completeness: completeness || undefined,
        page,
        pageSize: 20
      });
      if (generation !== currentLoad.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (generation !== currentLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load products");
    }
  }

  // Waits for the funnel's initial onChange (default = most recent workshop) before the first fetch.
  useEffect(() => {
    if (!funnelReady) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, applied, funnel, completeness]);

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

  function onFunnelChange(next: FunnelValue, _workshop: FunnelWorkshop | null) {
    // The funnel's FIRST answer is when this page learns its defaults — and so when a remembered
    // place (Back from a record's page) replaces them. See `useListMemory`.
    const remembered = funnelReady ? null : memory.recall();
    if (remembered) {
      if ((remembered.query ?? "") !== query) skipFirstDebounce.current = true;
      setQuery(remembered.query ?? "");
      setApplied(remembered.applied ?? "");
      setFunnel(remembered.funnel ?? next);
      setPage(remembered.page ?? 1);
      setCompleteness(parseCompletenessFilter(remembered.filters?.completeness));
      setFunnelReady(true);
      return;
    }
    setFunnel(next);
    setPage(1);
    setFunnelReady(true);
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this product record?",
        "This permanently deletes the record. This action cannot be undone.",
        "Photos and files documenting this product are deleted with it."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/products/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete product record");
    }
  }

  return (
    <>
      <PageHeader
        title="Product Documentation"
        description="Structured documentation for products, raw materials, pricing, function, demand and media links."
        icon={<Boxes className="h-5 w-5" aria-hidden />}
        actions={
          <>
            {canDownloadDataset(user) ? <DownloadCsvButton path="/export/products.csv" filename={exportFilename("Product", "Export", "csv")} /> : null}
            {/* Gated, not merely locked: an ungated "New …" invited every tier to press a
                button that lands on the route guard's refusal. Below researcher the honest UI is
                no button, matching `require_record_creator` on the server. */}
            {canCreateRecords(user) ? (
              <Link className="field-button" href="/products/new">
                <Plus className="h-4 w-4" aria-hidden />
                New product
              </Link>
            ) : null}
          </>
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
          placeholder="Search product, craft, artisan, place, materials or remarks"
        />
      </div>
      <FunnelFilters value={funnel} onChange={onFunnelChange} showArtisan />
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
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No product records found" body="Create a product record from field observations and link media after upload." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Product</ResizableTh>
                  <ResizableTh>Craft</ResizableTh>
                  <ResizableTh>Artisan</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Demand</ResizableTh>
                  <ResizableTh>Media</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((product) => (
                  // The whole row opens the record read-only; its name is the same act as a real link.
                  // Update, Discuss, Delete and the preview tiles keep their own clicks (`OpenableRow`).
                  <OpenableRow key={product.id} href={RECORD_VIEW_HREF.product(product.id)}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <RowCompletenessRing block={product.completeness} />
                        <OpenableRowLink href={RECORD_VIEW_HREF.product(product.id)}>{product.productName}</OpenableRowLink>
                      </div>
                      <div className="text-xs text-ink-500">{product.localName ?? product.productType}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">{product.craftName}</td>
                    <td className="px-4 py-3 text-ink-700">{product.artisanName}</td>
                    <td className="px-4 py-3 text-ink-700">{product.place}</td>
                    <td className="px-4 py-3 text-ink-700">{product.marketDemand}</td>
                    <td className="px-4 py-3 text-ink-700">
                      {product.media?.length ? (
                        <div className="grid max-w-[240px] grid-cols-2 gap-2">
                          {product.media.slice(0, 2).map((media) => {
                            const preview = {
                              key: media.id,
                              id: media.id,
                              name: media.originalFilename,
                              mediaType: media.mediaType,
                              mimeType: media.mimeType,
                              sizeBytes: media.sizeBytes,
                              url: media.url,
                              caption: media.caption,
                              transcriptStatus: media.transcriptStatus,
                              transcriptText: media.transcriptText,
                              transcriptError: media.transcriptError
                            };
                            return <MediaPreviewTile key={media.id} item={preview} onOpen={() => setActivePreview(preview)} />;
                          })}
                          {product.media.length > 2 ? <span className="text-xs font-semibold text-ink-muted">+{product.media.length - 2} more</span> : null}
                        </div>
                      ) : (
                        "0"
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={product.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDate(product.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <Link className={rowAction("edit")} href={RECORD_UPDATE_HREF.product(product.id)}>
                          Update
                        </Link>
                        <button className={rowAction("neutral")} onClick={() => setCollabId(product.id)}>
                          Discuss
                        </button>
                        {adminMode ? (
                          <button data-danger className={rowAction("danger")} onClick={() => remove(product.id)}>
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
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
      <CollabDialog recordType="product" recordId={collabId} onClose={() => setCollabId(null)} />
    </>
  );
}
