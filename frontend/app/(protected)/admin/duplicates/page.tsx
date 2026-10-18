"use client";

/**
 * /admin/duplicates — likely-duplicate artisans, the decisions on them, and the merges.
 *
 * Nested under /admin, so the `/admin` ROUTE_GUARDS row (`isAdmin`, mirroring `require_admin`, the
 * server's gate on every `/artisan-duplicates` and `/artisan-merges` route) already covers it. Web
 * only: Android has no admin screen for this.
 */

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { CopyCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { readableError } from "@/components/review/reviewErrors";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { useToast } from "@/components/ui/Toast";
import {
  BAND_LABEL,
  listMerges,
  listPairs,
  undoMerge,
  type DuplicateBand,
  type DuplicatePair,
  type MergeRecord,
  type PairPage
} from "@/lib/duplicates";
import { formatDateTime } from "@/lib/format";
import { isAdmin } from "@/lib/permissions";

type Tab = "OPEN" | "NOT_DUPLICATE" | "MERGED";
const TABS: { key: Tab; label: string }[] = [
  { key: "OPEN", label: "Open" },
  { key: "NOT_DUPLICATE", label: "Not duplicates" },
  { key: "MERGED", label: "Merged" }
];
const BANDS: DuplicateBand[] = ["VERY_LIKELY", "LIKELY", "POSSIBLE"];
const PAGE_SIZE = 20;

function Pill({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={
        active
          ? "rounded-full border border-purple-700 bg-purple-700 px-3 py-1 text-sm font-medium text-white"
          : "rounded-full border border-line-200 bg-card px-3 py-1 text-sm text-ink-700 hover:border-purple-300"
      }
    >
      {children}
    </button>
  );
}

function sideText(side: DuplicatePair["a"]): string {
  if (!side) return "";
  return [side.place || side.district, side.craft].filter(Boolean).join(" · ");
}

export default function DuplicatesPage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);
  const { toast } = useToast();
  const [tab, setTab] = useState<Tab>("OPEN");
  const [band, setBand] = useState<DuplicateBand | "">("");
  const [kind, setKind] = useState<"" | "WITHIN" | "CROSS_PORTAL">("");
  const [page, setPage] = useState(1);
  const [pairs, setPairs] = useState<PairPage | null>(null);
  const [merges, setMerges] = useState<{ items: MergeRecord[]; total: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [undoing, setUndoing] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(async () => {
    const mine = ++generation.current;
    setError(null);
    try {
      if (tab === "MERGED") {
        const result = await listMerges(page, PAGE_SIZE);
        if (mine === generation.current) setMerges(result);
      } else {
        const result = await listPairs({ status: tab, band, kind, page, pageSize: PAGE_SIZE });
        if (mine === generation.current) setPairs(result);
      }
    } catch (err) {
      if (mine === generation.current) setError(readableError(err, "Couldn't load duplicates."));
    }
  }, [tab, band, kind, page]);

  useEffect(() => {
    if (authLoading || !permitted) return;
    void load();
  }, [authLoading, permitted, load]);

  if (!authLoading && !permitted) {
    return (
      <>
        <PageHeader title="Duplicate artisans" icon={<CopyCheck className="h-5 w-5" aria-hidden />} />
        <RestrictedPanel title="Admin access required" body="Admins only." />
      </>
    );
  }

  async function undo(record: MergeRecord) {
    setUndoing(record.id);
    try {
      const result = await undoMerge(record.id);
      toast({
        tone: "success",
        title: "Merge undone",
        description: result.keptSurvivorEdits.length
          ? `Kept later edits to: ${result.keptSurvivorEdits.join(", ")}.`
          : undefined
      });
      await load();
    } catch (err) {
      setError(readableError(err, "Couldn't undo the merge."));
    } finally {
      setUndoing(null);
    }
  }

  const items = tab === "MERGED" ? null : pairs?.items ?? null;
  const total = tab === "MERGED" ? merges?.total ?? 0 : pairs?.total ?? 0;

  return (
    <>
      <PageHeader
        title="Duplicate artisans"
        description="Likely duplicates, within this portal and with the Field Repository."
        icon={<CopyCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div className="panel mb-4 grid gap-3 p-4">
        {/* Tabs and filters: segmented choices, not an action row. */}
        <div className="flex flex-wrap gap-2" role="group" aria-label="Show">
          {TABS.map((t) => (
            <Pill key={t.key} active={tab === t.key} onClick={() => { setTab(t.key); setPage(1); }}>
              {t.label}
            </Pill>
          ))}
        </div>
        {tab !== "MERGED" ? (
          <div className="flex flex-wrap gap-2" role="group" aria-label="Filter">
            <Pill active={band === ""} onClick={() => { setBand(""); setPage(1); }}>
              All bands
            </Pill>
            {BANDS.map((b) => (
              <Pill key={b} active={band === b} onClick={() => { setBand(b); setPage(1); }}>
                {BAND_LABEL[b]}
                {pairs?.counts ? ` · ${pairs.counts[b]}` : ""}
              </Pill>
            ))}
            <span className="mx-1 hidden h-6 w-px bg-line-200 sm:inline-block" aria-hidden />
            <Pill active={kind === ""} onClick={() => { setKind(""); setPage(1); }}>Both</Pill>
            <Pill active={kind === "WITHIN"} onClick={() => { setKind("WITHIN"); setPage(1); }}>Within</Pill>
            <Pill active={kind === "CROSS_PORTAL"} onClick={() => { setKind("CROSS_PORTAL"); setPage(1); }}>Field Repository</Pill>
          </div>
        ) : null}
      </div>

      {tab === "MERGED" ? (
        merges === null ? (
          <p className="text-sm text-ink-500">Loading…</p>
        ) : merges.items.length === 0 ? (
          <EmptyState title="No merges yet" />
        ) : (
          <ul className="grid gap-2">
            {merges.items.map((record) => (
              <li key={record.id} className="panel flex flex-wrap items-center justify-between gap-3 p-4">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-ink-900">
                    {record.merged.name ?? "Merged record"} → <Link className="text-purple-700 underline-offset-2 hover:underline dark:text-purple-300" href={`/artisans/${record.survivor.id}`}>{record.survivor.name ?? "Kept record"}</Link>
                  </p>
                  <p className="text-xs text-ink-500">
                    {record.mergedBy.name ? `${record.mergedBy.name} · ` : ""}
                    {formatDateTime(record.createdAt)}
                    {record.undoneAt ? ` · Undone ${formatDateTime(record.undoneAt)}` : record.canUndo ? ` · Undo until ${formatDateTime(record.undoUntil)}` : ""}
                  </p>
                </div>
                {record.canUndo ? (
                  <ActionGroup inline align="end">
                    <button type="button" className="field-button-secondary" disabled={undoing === record.id} onClick={() => undo(record)}>
                      {undoing === record.id ? "Undoing…" : "Undo"}
                    </button>
                  </ActionGroup>
                ) : null}
              </li>
            ))}
          </ul>
        )
      ) : items === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : items.length === 0 ? (
        <EmptyState title={tab === "OPEN" ? "No likely duplicates" : "Nothing marked as different people"} />
      ) : (
        <ul className="grid gap-2">
          {items.map((pair) => (
            <li key={pair.id}>
              <Link
                href={`/admin/duplicates/${pair.id}`}
                className="panel flex flex-wrap items-center justify-between gap-3 p-4 transition hover:border-purple-300"
              >
                <div className="grid min-w-0 gap-1 sm:grid-cols-2 sm:gap-6">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-ink-900">{pair.a?.name}</p>
                    <p className="truncate text-xs text-ink-500">{sideText(pair.a)}</p>
                  </div>
                  <div className="min-w-0">
                    {pair.kind === "CROSS_PORTAL" ? (
                      <>
                        <p className="truncate text-sm font-medium text-ink-900">Field Repository record</p>
                        <p className="truncate text-xs text-ink-500">Matched by {pair.remote?.matchedBy === "PEHCHAN" ? "Pehchan card" : "Aadhaar"}</p>
                      </>
                    ) : (
                      <>
                        <p className="truncate text-sm font-medium text-ink-900">{pair.b?.name}</p>
                        <p className="truncate text-xs text-ink-500">{sideText(pair.b)}</p>
                      </>
                    )}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="rounded-full bg-purple-50 px-2 py-0.5 text-xs font-semibold text-purple-700 dark:bg-purple-950/40 dark:text-purple-300">
                    {BAND_LABEL[pair.band]} · {Math.round(pair.score * 100)}%
                  </span>
                  {pair.reasons.slice(0, 3).map((reason) => (
                    <span key={reason.code} className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] text-ink-700">
                      {reason.label}
                    </span>
                  ))}
                  {pair.creatorSaidDifferent ? (
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] text-amber-800">Creator said different</span>
                  ) : null}
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-4">
        <Pagination page={page} pages={Math.max(1, Math.ceil(total / PAGE_SIZE))} total={total} onPage={setPage} />
      </div>
    </>
  );
}
