"use client";

/**
 * The Ministry dashboard's actionable half (owner decisions C14 and C15, 2026-10-17).
 *
 * - `AttentionCards` — four clickable cards and a "sanctioned" figure. A card is a NARROWING of the
 *   list below it, counted by the server over the reader's scope; pressing it again clears it.
 * - `StateBreakdown` — workshops per state, each a narrowing too.
 * - `MinistryAnalyticsPanel` — the cross-workshop findings, read only, for the Ministry Admin
 *   (whole archive) and Regional Directors (their posted workshops). Closed until asked for.
 * - `StageDataExportPanel` — one spreadsheet of stage data across the filtered workshops, identity
 *   numbers masked, built in the background. Polls while a job is in flight.
 *
 * Selected states carry a tick and a word, never colour alone (rule 5). Every figure is the server's.
 */

import { useCallback, useEffect, useState } from "react";
import { ChartNoAxesCombined, Check, ChevronDown, Download, FileSpreadsheet } from "lucide-react";

import { WorkshopAnalyticsReport } from "@/components/analytics/WorkshopAnalyticsReport";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  REGISTER_ATTENTION,
  downloadStageDataExport,
  exportsInFlight,
  listStageDataExports,
  requestStageDataExport,
  type RegisterAttention,
  type RegisterSummary,
  type StageDataExport
} from "@/lib/ministryDashboard";
import { formatSanctionAmount } from "@/lib/sanctionOrders";
import { loadWorkshopAnalytics, type WorkshopAnalytics } from "@/lib/workshopAnalytics";

const POLL_MS = 5000;

function message(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

const cardClass = (selected: boolean) =>
  selected
    ? "flex h-full w-full flex-col items-start gap-1 rounded-md border border-ministry-600 bg-ministry-50 p-4 text-left transition-shadow hover:shadow-md dark:bg-ministry-950/40"
    : "flex h-full w-full flex-col items-start gap-1 rounded-md border border-line-200 bg-card p-4 text-left transition-shadow hover:border-ministry-300 hover:shadow-md";

export function AttentionCards({
  summary,
  selected,
  onSelect
}: {
  summary: RegisterSummary["designWorkshops"];
  selected: RegisterAttention | "";
  onSelect: (value: RegisterAttention | "") => void;
}) {
  const counts = summary.attention;
  if (!counts) return null;
  return (
    <section aria-labelledby="ministry-attention" className="mb-6">
      <h2 id="ministry-attention" className="mb-3 font-display text-lg font-bold text-ink-900">
        Needs attention
      </h2>
      <div role="group" aria-label="Needs attention" className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        {REGISTER_ATTENTION.map((card) => {
          const on = selected === card.value;
          return (
            <button
              key={card.value}
              type="button"
              aria-pressed={on}
              onClick={() => onSelect(on ? "" : card.value)}
              className={cardClass(on)}
            >
              <span className="flex w-full items-center gap-1.5 text-xs font-medium text-ink-500">
                {card.label}
                {on ? <Check className="ml-auto h-4 w-4 text-ministry-700 dark:text-ministry-300" aria-hidden /> : null}
              </span>
              <span className="font-display text-2xl font-bold tabular-nums text-ink-900">
                {counts[card.value] ?? 0}
                {card.value === "setupIncomplete" && summary.setupIncompleteCapped ? "+" : ""}
              </span>
            </button>
          );
        })}
        {summary.sanctioned ? (
          <div className="panel col-span-2 p-4 lg:col-span-1">
            <div className="text-xs font-medium text-ink-500">Sanctioned</div>
            <div className="mt-1 font-display text-2xl font-bold tabular-nums text-ink-900">
              {formatSanctionAmount(summary.sanctioned.amount)}
            </div>
            <div className="text-xs text-ink-500">
              {summary.sanctioned.orders === 1 ? "1 order" : `${summary.sanctioned.orders} orders`}
            </div>
          </div>
        ) : null}
      </div>
    </section>
  );
}

export function StateBreakdown({
  rows,
  selected,
  onSelect
}: {
  rows: { state: string | null; count: number }[] | undefined;
  selected: string;
  onSelect: (value: string) => void;
}) {
  if (!rows || rows.length === 0) return null;
  return (
    <section aria-labelledby="ministry-states" className="mb-6">
      <h2 id="ministry-states" className="mb-3 font-display text-lg font-bold text-ink-900">
        By state
      </h2>
      <div role="group" aria-label="By state" className="flex flex-wrap gap-2">
        {rows.map((row) => {
          const value = row.state ?? "none";
          const on = selected === value;
          return (
            <button
              key={value}
              type="button"
              aria-pressed={on}
              onClick={() => onSelect(on ? "" : value)}
              className={
                on
                  ? "inline-flex items-center gap-1.5 rounded-full border border-ministry-700 bg-ministry-700 px-3 py-1.5 text-xs font-semibold text-white"
                  : "inline-flex items-center gap-1.5 rounded-full border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 transition hover:border-ministry-300"
              }
            >
              {on ? <Check className="h-3.5 w-3.5 shrink-0" aria-hidden /> : null}
              {row.state ?? "State not recorded"}
              <span className="tabular-nums">{row.count}</span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

export function MinistryAnalyticsPanel() {
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<WorkshopAnalytics | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (!open || data) return;
    let cancelled = false;
    loadWorkshopAnalytics()
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((error) => {
        if (!cancelled) setProblem(message(error, "Couldn't load the analytics."));
      });
    return () => {
      cancelled = true;
    };
  }, [open, data]);

  return (
    <section className="panel mb-6 p-4">
      <button
        type="button"
        className="flex w-full items-center gap-3 rounded-md text-left"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="grid h-9 w-9 place-items-center rounded-lg bg-field-200 text-field-600" aria-hidden>
          <ChartNoAxesCombined className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block font-display text-base font-bold tracking-tight text-ink-900">Analytics</span>
          <span className="block text-xs text-ink-500">Adoption, costs and opportunities. Read only.</span>
        </span>
        <ChevronDown className={`h-4 w-4 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <div className="mt-4">
          {problem ? <p className="text-sm text-error-600">{problem}</p> : null}
          {!data && !problem ? <p className="text-sm text-ink-500">Reading the archive…</p> : null}
          {data ? <WorkshopAnalyticsReport data={data} /> : null}
        </div>
      ) : null}
    </section>
  );
}

function statusWord(item: StageDataExport): string {
  switch (item.status) {
    case "QUEUED":
      return "Waiting";
    case "RUNNING":
      return "Preparing";
    case "READY":
      return "Ready";
    case "EXPIRED":
      return "Expired";
    default:
      return "Failed";
  }
}

export function StageDataExportPanel({
  filters
}: {
  filters: { search?: string; standing?: string; attention?: string; state?: string };
}) {
  const [items, setItems] = useState<StageDataExport[] | null>(null);
  const [keepDays, setKeepDays] = useState(7);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const answer = await listStageDataExports();
      setItems(answer.items);
      setKeepDays(answer.keepDays);
    } catch (error) {
      setProblem(message(error, "Couldn't read your exports."));
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    if (!items || !exportsInFlight(items)) return;
    const timer = window.setTimeout(() => void reload(), POLL_MS);
    return () => window.clearTimeout(timer);
  }, [items, reload]);

  async function request() {
    setBusy(true);
    setProblem(null);
    try {
      await requestStageDataExport(filters);
      await reload();
    } catch (error) {
      setProblem(message(error, "Couldn't start the export. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel mb-6 p-4" aria-labelledby="ministry-stage-data">
      <div className="flex items-center gap-3">
        <span className="grid h-9 w-9 place-items-center rounded-lg bg-field-200 text-field-600" aria-hidden>
          <FileSpreadsheet className="h-4 w-4" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 id="ministry-stage-data" className="font-display text-base font-bold tracking-tight text-ink-900">
            Stage data
          </h2>
          <p className="text-xs text-ink-500">
            One spreadsheet for the workshops shown, a sheet per record type. Identity numbers masked.
          </p>
        </div>
      </div>
      <ActionGroup className="mt-3">
        <button type="button" className="field-button" disabled={busy} onClick={() => void request()}>
          {busy ? "Starting…" : "Prepare stage data"}
        </button>
      </ActionGroup>
      {problem ? <p className="mt-2 text-sm text-error-600">{problem}</p> : null}
      {items && items.length ? (
        <ul className="mt-3 divide-y divide-line-200 rounded-md border border-line-200">
          {items.map((item) => (
            <li key={item.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm">
              <span className="min-w-0 flex-1">
                <span className="block font-medium text-ink-900">
                  {statusWord(item)}
                  {item.workshopCount != null
                    ? ` · ${item.workshopCount === 1 ? "1 workshop" : `${item.workshopCount} workshops`}`
                    : ""}
                </span>
                <span className="block text-xs text-ink-500">
                  {item.createdAt ? formatDateTime(item.createdAt) : ""}
                  {item.error ? ` · ${item.error}` : ""}
                </span>
              </span>
              {item.status === "READY" ? (
                <ActionGroup inline align="end">
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={() =>
                      void downloadStageDataExport(item).catch((error) =>
                        setProblem(message(error, "Couldn't download the file."))
                      )
                    }
                  >
                    <Download className="h-4 w-4" aria-hidden />
                    Download
                  </button>
                </ActionGroup>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {items && items.length ? (
        <p className="mt-2 text-xs text-ink-500">Files are kept for {keepDays} days.</p>
      ) : null}
    </section>
  );
}
