"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Link2, RefreshCw } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import {
  UnfiledRecordCard,
  UnfiledRecordDialog,
  type UnfiledRecord
} from "@/components/settings/UnfiledRecordCard";
import { heldBackSentence } from "@/components/settings/unfiledRecords";
import { apiFetch } from "@/lib/api";
import { isAdmin } from "@/lib/permissions";

type MappingRow = {
  id: string;
  title: string;
  workshopId: string | null;
  workshopTitle: string | null;
  rung: string | null;
  rungCopy: string | null;
  reason: string | null;
  reasonCopy: string | null;
  candidateTitles: string[];
};

type MappingBucket = {
  bucket: string;
  singular: string;
  plural: string;
  unassigned: number;
  resolved: number;
  unresolved: number;
  byRung: Array<{ rung: string; copy: string; count: number }>;
  byReason: Array<{ reason: string; copy: string; count: number }>;
  byWorkshop: Array<{ workshopId: string; title: string; count: number }>;
  rows: MappingRow[];
  rowsTruncated: boolean;
  applied: number | null;
  heldBack?: number | null;
};

export type WorkshopMappingPlan = {
  workshops: Array<{ id: string; title: string; start: string; end: string }>;
  allWorkshops?: Array<{ id: string; title: string }>;
  buckets: MappingBucket[];
  totals: {
    unassigned: number;
    resolved: number;
    unresolved: number;
    applied: number | null;
    heldBack?: number | null;
  };
  heldBackDetail?: string | null;
};

function noun(bucket: MappingBucket, count: number): string {
  return count === 1 ? bucket.singular : bucket.plural;
}

function formatDay(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleDateString();
}

function formatLastDay(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  date.setDate(date.getDate() - 1);
  return date.toLocaleDateString();
}

export function WorkshopMappingPanel() {
  const { user } = useAuth();
  const [plan, setPlan] = useState<WorkshopMappingPlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [applied, setApplied] = useState<number | null>(null);
  const [heldBack, setHeldBack] = useState<string | null>(null);
  const [selected, setSelected] = useState<UnfiledRecord | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [rowNotice, setRowNotice] = useState<string | null>(null);

  const canAct = isAdmin(user);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPlan(await apiFetch<WorkshopMappingPlan>("/workshops/unmapped"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The unmapped records could not be read.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function apply() {
    setApplying(true);
    setError(null);
    try {
      const result = await apiFetch<WorkshopMappingPlan>("/workshops/unmapped/map", { method: "POST" });
      setApplied(result.totals.applied ?? 0);
      setHeldBack(heldBackSentence(result));
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The records could not be mapped.");
    } finally {
      setApplying(false);
    }
  }

  const afterRowAction = useCallback(
    async (notice: string) => {
      setRowNotice(notice);
      setDialogOpen(false);
      await load();
    },
    [load]
  );

  const buckets = (plan?.buckets ?? []).filter((bucket) => bucket.unassigned > 0);
  const totals = plan?.totals;

  const pickerWorkshops = useMemo(() => {
    const complete = plan?.allWorkshops;
    const source = complete ?? (plan?.workshops ?? []).map((entry) => ({ id: entry.id, title: entry.title }));
    return [...source].sort((a, b) => a.title.localeCompare(b.title));
  }, [plan]);
  const pickerComplete = Boolean(plan?.allWorkshops);

  return (
    <section className="panel p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 font-display text-sm font-bold text-ink-900">
            <Link2 className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
            Records not filed under a workshop
          </h2>
          <p className="mt-1 max-w-2xl text-xs leading-5 text-ink-500">
            Records without a workshop only show under <span className="font-medium text-ink-700">All records</span>.
            This files them where the evidence points.
            Records filed under a design &amp; prototype workshop are not listed here.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          disabled={loading || applying}
          className="field-button-secondary shrink-0"
        >
          <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} aria-hidden />
          Re-check
        </button>
      </div>

      {error ? (
        <p className="mt-3 rounded-md bg-error-100 px-3 py-2 text-xs text-red-800">{error}</p>
      ) : null}

      {loading && !plan ? (
        <p className="mt-3 text-xs text-ink-500">Finding records with no workshop…</p>
      ) : null}

      {plan && totals ? (
        <>
          {totals.unassigned === 0 ? (
            <p className="mt-3 flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-700">
              <Check className="h-4 w-4 shrink-0 text-success-600 dark:text-emerald-400" aria-hidden />
              Every record is filed under a workshop.
            </p>
          ) : (
            <>
              <dl className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
                <Stat label="Not filed" value={totals.unassigned} />
                <Stat label="Can be filed now" value={totals.resolved} tone="good" />
                <Stat label="Need a person" value={totals.unresolved} tone={totals.unresolved ? "warn" : undefined} />
              </dl>

              <ul className="mt-3 grid gap-2">
                {buckets.map((bucket) => (
                  <li key={bucket.bucket} className="rounded-md border border-line-200 bg-surface-50 p-3">
                    <p className="text-xs font-semibold text-ink-900">
                      {bucket.unassigned} {noun(bucket, bucket.unassigned)}
                      {bucket.applied !== null ? (
                        <span className="ml-1 font-normal text-success-600 dark:text-emerald-400">
                          — {bucket.applied} filed just now
                        </span>
                      ) : (
                        <span className="ml-1 font-normal text-ink-500">
                          — {bucket.resolved} can be filed
                          {bucket.unresolved ? `, ${bucket.unresolved} need a person` : ""}
                        </span>
                      )}
                    </p>

                    {bucket.byWorkshop.length ? (
                      <p className="mt-1 text-[11px] leading-4 text-ink-500">
                        Filed under{" "}
                        {bucket.byWorkshop
                          .map((entry) => `${entry.title} (${entry.count})`)
                          .join(", ")}
                        .
                      </p>
                    ) : null}

                    {bucket.byRung.length ? (
                      <ul className="mt-1.5 grid gap-0.5">
                        {bucket.byRung.map((entry) => (
                          <li key={entry.rung} className="text-[11px] leading-4 text-ink-700">
                            <span className="font-semibold text-ink-900">{entry.count}</span> because{" "}
                            {entry.copy}.
                          </li>
                        ))}
                      </ul>
                    ) : null}

                    {bucket.unresolved ? (
                      (() => {
                        const stuck = bucket.rows.filter((row) => !row.workshopId);
                        return (
                          <div className="mt-2 rounded border border-amber-100 bg-amber-100/40 p-2">
                            <p className="flex items-center gap-1.5 text-[11px] font-semibold text-amber-800 dark:text-amber-100">
                              <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden />
                              {canAct
                                ? "Skipped — file or discard by hand"
                                : "Skipped — file by hand"}
                            </p>
                            {canAct ? (
                              <ul className="mt-1.5 grid gap-1.5 sm:grid-cols-2">
                                {stuck.map((row) => {
                                  const record: UnfiledRecord = {
                                    bucket: bucket.bucket,
                                    singular: bucket.singular,
                                    row
                                  };
                                  return (
                                    <li key={row.id}>
                                      <UnfiledRecordCard
                                        record={record}
                                        onOpen={() => {
                                          setSelected(record);
                                          setDialogOpen(true);
                                        }}
                                      />
                                    </li>
                                  );
                                })}
                              </ul>
                            ) : (
                              <ul className="mt-1 grid gap-0.5">
                                {stuck.map((row) => (
                                  <li key={row.id} className="text-[11px] leading-4 text-ink-700">
                                    <span className="font-medium text-ink-900">{row.title}</span>
                                    {row.reasonCopy ? <span className="text-ink-500"> — {row.reasonCopy}</span> : null}
                                    {row.candidateTitles.length ? (
                                      <span className="text-ink-500"> ({row.candidateTitles.join(" or ")})</span>
                                    ) : null}
                                  </li>
                                ))}
                              </ul>
                            )}
                            {stuck.length < bucket.unresolved ? (
                              <p className="mt-1.5 text-[11px] leading-4 text-ink-500">
                                {bucket.unresolved - stuck.length} more{" "}
                                {bucket.unresolved - stuck.length === 1 ? "is" : "are"} hidden. Re-check after filing these.
                              </p>
                            ) : null}
                          </div>
                        );
                      })()
                    ) : null}
                  </li>
                ))}
              </ul>

              {totals.resolved > 0 ? (
                <div className="mt-3 flex flex-wrap items-center gap-3">
                  <button type="button" onClick={apply} disabled={applying} className="field-button">
                    {applying
                      ? "Filing…"
                      : `File ${totals.resolved} record${totals.resolved === 1 ? "" : "s"} under its workshop`}
                  </button>
                  <p className="text-[11px] leading-4 text-ink-500">
                    Only fills empty workshops. Safe to repeat.
                  </p>
                </div>
              ) : null}
            </>
          )}

          {rowNotice ? (
            <p className="mt-3 flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
              <Check className="h-4 w-4 shrink-0 text-success-600 dark:text-emerald-400" aria-hidden />
              {rowNotice}
            </p>
          ) : null}

          {applied !== null ? (
            <p className="mt-3 flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-700">
              <Check className="h-4 w-4 shrink-0 text-success-600 dark:text-emerald-400" aria-hidden />
              {applied === 0
                ? heldBack
                  ? "Nothing was filed."
                  : "Nothing left to file."
                : `${applied} record${applied === 1 ? "" : "s"} filed.`}
            </p>
          ) : null}
          {applied !== null && heldBack ? (
            <p className="mt-2 flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
              {heldBack}
            </p>
          ) : null}

          {plan.workshops.length ? (
            <ul className="mt-3 grid gap-0.5 text-[11px] leading-4 text-ink-500">
              {plan.workshops.map((workshop) => (
                <li key={workshop.id}>
                  {workshop.title} — read as {formatDay(workshop.start)} to {formatLastDay(workshop.end)}
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-3 text-[11px] leading-4 text-ink-500">
              No workshop has dates, so none can be matched by date.
            </p>
          )}
        </>
      ) : null}

      {canAct ? (
        <UnfiledRecordDialog
          open={dialogOpen}
          record={selected}
          workshops={pickerWorkshops}
          workshopsComplete={pickerComplete}
          onClose={() => setDialogOpen(false)}
          onDone={afterRowAction}
        />
      ) : null}
    </section>
  );
}

function Stat({
  label,
  value,
  tone
}: {
  label: string;
  value: number;
  tone?: "good" | "warn";
}) {
  return (
    <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
      <dt className="text-[11px] font-medium uppercase tracking-wide text-ink-500">{label}</dt>
      <dd
        className={`mt-0.5 font-display text-xl font-bold ${
          tone === "good" ? "text-success-600" : tone === "warn" ? "text-amber-800" : "text-ink-900"
        }`}
      >
        {value}
      </dd>
    </div>
  );
}
