"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ExternalLink, X } from "lucide-react";

import { readableError } from "@/components/review/reviewErrors";
import { fetchUnitRecords, type CoverageQuery } from "@/components/map/coverage/coverageApi";
import {
  BUCKET_LABEL,
  BUCKETS,
  fyLabel,
  type Bucket,
  type UnitRecordsResponse
} from "@/components/map/coverage/coverageTypes";

type Props = {
  query: CoverageQuery;
  stateLgd: number;
  districtLgd: number;
  title: string;
  note: string | null;
  onClose: () => void;
};

/** One district's counts and the records behind them, a page per bucket. */
export function CoverageUnitPanel({ query, stateLgd, districtLgd, title, note, onClose }: Props) {
  const [data, setData] = useState<UnitRecordsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [more, setMore] = useState<Bucket | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError(null);
    fetchUnitRecords(query, stateLgd, districtLgd, {}, controller.signal)
      .then(setData)
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(readableError(cause, "Couldn't load these records."));
      });
    return () => controller.abort();
  }, [query, stateLgd, districtLgd]);

  async function loadMore(bucket: Bucket) {
    const current = data?.buckets[bucket];
    if (!current?.nextCursor) return;
    setMore(bucket);
    try {
      const page = await fetchUnitRecords(query, stateLgd, districtLgd, { bucket, cursor: current.nextCursor });
      const next = page.buckets[bucket];
      if (next) {
        setData((previous) =>
          previous
            ? {
                ...previous,
                buckets: {
                  ...previous.buckets,
                  [bucket]: { ...next, items: [...current.items, ...next.items] }
                }
              }
            : previous
        );
      }
    } catch (cause) {
      setError(readableError(cause, "Couldn't load more."));
    } finally {
      setMore(null);
    }
  }

  const peer = data?.unit.peer ?? null;
  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="coverage-unit-title" data-testid="coverage-unit-panel">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id="coverage-unit-title" className="display-title text-lg">
            {data ? (data.unit.district ? `${data.unit.district}, ${data.unit.state}` : data.unit.state) : title}
          </h2>
          {note ? <p className="text-xs text-ink-500">{note}</p> : null}
        </div>
        <button type="button" onClick={onClose} className="field-button-secondary min-h-9 px-2" aria-label="Close">
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      {error ? <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p> : null}
      {!data && !error ? <p className="text-sm text-ink-500">Loading…</p> : null}

      {data ? (
        <>
          <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {BUCKETS.map((bucket) => (
              <div key={bucket} className="rounded-md bg-surface-50 px-3 py-2">
                <dt className="text-xs text-ink-500">{BUCKET_LABEL[bucket]}</dt>
                <dd className="font-display text-lg font-semibold tabular-nums text-ink-900">
                  {data.unit.counts[bucket]}
                </dd>
              </div>
            ))}
          </dl>

          {peer ? (
            <div className="rounded-md border border-line-200 px-3 py-2 text-sm" data-testid="coverage-unit-peer">
              <p className="field-label mb-1">Field Repository</p>
              <p className="text-ink-700">
                {peer.workshops} workshops · {peer.artisans} artisans · {peer.products} products · {peer.tools} tools ·{" "}
                {peer.media} media
              </p>
            </div>
          ) : null}

          {Object.keys(data.buckets).length === 0 ? (
            <p className="text-sm text-ink-500">No records here under these filters.</p>
          ) : null}

          {BUCKETS.filter((bucket) => data.buckets[bucket]).map((bucket) => {
            const entry = data.buckets[bucket]!;
            return (
              <div key={bucket} className="grid gap-1.5">
                <p className="flex items-center justify-between text-sm font-medium text-ink-900">
                  <span>{BUCKET_LABEL[bucket]}</span>
                  <span className="text-xs tabular-nums text-ink-500">{entry.total}</span>
                </p>
                <ul className="grid gap-1">
                  {entry.items.map((item) => (
                    <li key={`${bucket}:${item.id}`}>
                      <Link
                        href={item.href}
                        className="flex items-center justify-between gap-3 rounded-md px-2 py-1.5 text-sm text-ink-900 hover:bg-purple-50"
                      >
                        <span className="min-w-0 truncate">{item.title}</span>
                        {item.fy ? <span className="shrink-0 text-xs text-ink-500">FY {fyLabel(item.fy)}</span> : null}
                      </Link>
                    </li>
                  ))}
                </ul>
                {entry.nextCursor ? (
                  <button
                    type="button"
                    className="field-button-secondary mx-auto w-fit"
                    disabled={more === bucket}
                    onClick={() => loadMore(bucket)}
                  >
                    {more === bucket ? "Loading…" : `Show more (${entry.total - entry.items.length})`}
                  </button>
                ) : null}
              </div>
            );
          })}

          {data.peerHref ? (
            <a href={data.peerHref} target="_blank" rel="noreferrer" className="field-button-secondary mx-auto w-fit">
              Open in Field Repository
              <ExternalLink className="h-4 w-4" aria-hidden />
            </a>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
