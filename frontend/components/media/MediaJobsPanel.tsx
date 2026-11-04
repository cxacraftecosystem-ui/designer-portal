"use client";

import { Fragment, useCallback, useEffect, useId, useState } from "react";
import { AlertTriangle, Ban, CheckCircle2, Clock, ListChecks, Loader2, Play, RotateCcw } from "lucide-react";

import {
  HELD_POST_PENDING_FILES,
  HeldPostNotice,
  MEDIA_HELD_LABEL,
  mediaListNotice,
  mediaWorkshopIds,
  useHeldPostRefusals,
  writesHeld,
  type HeldPostRefusal
} from "@/components/designworkshop/HeldPostNotice";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { formatDateTime } from "@/lib/format";
import {
  listMediaProcessingJobs,
  retryMediaProcessingJob,
  runMediaProcessingQueue,
  type MediaProcessingJob
} from "@/lib/media";
import { isAdmin } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";

const IN_FLIGHT = new Set(["QUEUED", "PROCESSING"]);

const STATUS_TONE: Record<string, string> = {
  QUEUED: "border-line-200 bg-surface-50 text-ink-500",
  PROCESSING: "border-amber-500/30 bg-amber-100 text-amber-800",
  COMPLETED: "border-success-600/25 bg-success-100 text-success-600",
  FAILED: "border-error-600/25 bg-error-100 text-red-800",
  CANCELLED: "border-line-200 bg-surface-50 text-ink-500"
};

const STATUS_LABEL: Record<string, string> = {
  QUEUED: "Queued",
  PROCESSING: "Processing",
  COMPLETED: "Completed",
  FAILED: "Failed",
  CANCELLED: "Cancelled"
};

const JOB_TYPE_LABEL: Record<string, string> = {
  TRANSCRIPTION: "Transcription",
  MEASUREMENT: "Measurement"
};

function humanize(value: string): string {
  const words = value.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function StatusChip({ status }: { status: string }) {
  const key = status.toUpperCase();
  const Icon =
    key === "COMPLETED" ? CheckCircle2 : key === "FAILED" ? AlertTriangle : key === "CANCELLED" ? Ban : key === "PROCESSING" ? Loader2 : Clock;
  return (
    <span
      className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-1 text-xs font-medium ${
        STATUS_TONE[key] ?? STATUS_TONE.QUEUED
      }`}
    >
      <Icon className={`h-3.5 w-3.5 ${key === "PROCESSING" ? "animate-spin" : ""}`} aria-hidden />
      {STATUS_LABEL[key] ?? humanize(status)}
    </span>
  );
}

const FILTERS: Array<{ value: string; label: string }> = [
  { value: "", label: "All" },
  { value: "FAILED", label: "Failed" },
  { value: "QUEUED", label: "Queued" },
  { value: "PROCESSING", label: "Processing" },
  { value: "COMPLETED", label: "Completed" },
  { value: "CANCELLED", label: "Cancelled" }
];

export function MediaJobsPanel() {
  const { user } = useAuth();
  const canOperateQueue = isAdmin(user);

  const [data, setData] = useState<PageResult<MediaProcessingJob> | null>(null);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState("");
  const [failedTotal, setFailedTotal] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyJobId, setBusyJobId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [draining, setDraining] = useState(false);
  const [loadToken, setLoadToken] = useState(0);

  const load = useCallback(async (pageToLoad: number, filter: string) => {
    try {
      if (filter === "FAILED") {
        const result = await listMediaProcessingJobs({ page: pageToLoad, pageSize: 20, statusFilter: filter });
        return { result, failedTotal: result.total };
      }
      const [result, failed] = await Promise.all([
        listMediaProcessingJobs({ page: pageToLoad, pageSize: 20, statusFilter: filter }),
        listMediaProcessingJobs({ page: 1, pageSize: 1, statusFilter: "FAILED" })
      ]);
      return { result, failedTotal: failed.total };
    } catch (err) {
      throw err instanceof Error ? err : new Error("Unable to load media processing jobs");
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    load(page, statusFilter)
      .then(({ result, failedTotal: failed }) => {
        if (cancelled) return;
        setData(result);
        setFailedTotal(failed);
        setError(null);
      })
      .catch((err: Error) => {
        if (cancelled) return;
        setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [page, statusFilter, loadToken, load]);

  const items = data?.items ?? null;
  const anyInFlight = (items ?? []).some((job) => IN_FLIGHT.has(String(job.status).toUpperCase()));
  useEffect(() => {
    if (!anyInFlight) return;
    const timer = window.setInterval(() => setLoadToken((token) => token + 1), 15000);
    return () => window.clearInterval(timer);
  }, [anyInFlight]);

  const offersRetry = (job: MediaProcessingJob) => {
    const status = String(job.status).toUpperCase();
    return canOperateQueue && (status === "FAILED" || status === "CANCELLED");
  };
  const jobWorkshops = (job: MediaProcessingJob) => (job.mediaFile ? mediaWorkshopIds(job.mediaFile) : []);
  const heldFor = useHeldPostRefusals((items ?? []).filter(offersRetry).flatMap(jobWorkshops));
  const holdOf = (job: MediaProcessingJob): HeldPostRefusal => (offersRetry(job) ? heldFor(jobWorkshops(job)) : null);
  const heldNoticeId = useId();

  async function retry(job: MediaProcessingJob) {
    if (writesHeld(holdOf(job))) return;
    setBusyJobId(job.id);
    setError(null);
    setNotice(null);
    try {
      await retryMediaProcessingJob(job.id);
      setNotice(
        `${JOB_TYPE_LABEL[String(job.jobType).toUpperCase()] ?? humanize(String(job.jobType))} re-queued for ` +
          `${job.mediaFile?.originalFilename ?? "this file"}. It will run again shortly.`
      );
      setLoadToken((token) => token + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to re-queue this job");
    } finally {
      setBusyJobId(null);
    }
  }

  async function drain() {
    setDraining(true);
    setError(null);
    setNotice(null);
    try {
      const run = await runMediaProcessingQueue();
      setNotice(
        run.processed === 0
          ?
            "Nothing could run just now — transcription runs in quieter hours and pauses briefly when the transcription service is busy. Queued jobs stay queued."
          : `Ran ${run.processed} job${run.processed === 1 ? "" : "s"}: ${run.succeeded} succeeded, ${run.failed} failed.`
      );
      setLoadToken((token) => token + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to run the queue");
    } finally {
      setDraining(false);
    }
  }

  return (
    <section className="panel mb-5 overflow-hidden" data-testid="media-jobs-panel">
      <div className="flex flex-col gap-3 border-b border-line-200 p-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="flex items-center gap-2 font-display text-lg font-bold text-ink-900">
            <ListChecks className="h-4 w-4 text-field-600 dark:text-purple-300" aria-hidden />
            Media processing jobs
          </h2>
          <p className="mt-1 max-w-2xl text-sm text-ink-muted">
            Transcription and measurement work queued after upload. {canOperateQueue ? "Every account's jobs." : "The jobs you requested."}{" "}
            {failedTotal ? (
              <span className="font-medium text-error-600">
                {failedTotal} failed job{failedTotal === 1 ? "" : "s"} {canOperateQueue ? "can be re-run below." : "— an admin can re-run them."}
              </span>
            ) : (
              "Nothing has failed."
            )}
          </p>
        </div>
        {canOperateQueue ? (
          <button className="field-button-secondary" onClick={drain} disabled={draining}>
            {draining ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Play className="h-4 w-4" aria-hidden />}
            {draining ? "Running queue…" : "Run queue now"}
          </button>
        ) : null}
      </div>

      <div className="flex flex-wrap gap-2 border-b border-line-200 px-4 py-3">
        {FILTERS.map((filter) => {
          const active = statusFilter === filter.value;
          return (
            <button
              key={filter.value || "ALL"}
              type="button"
              aria-pressed={active}
              onClick={() => {
                setStatusFilter(filter.value);
                setPage(1);
              }}
              className={`rounded-full border px-3 py-1.5 text-xs font-semibold transition ${
                active ? "border-purple-700 bg-purple-50 text-purple-700" : "border-line-200 text-ink-700 hover:bg-surface-50"
              }`}
            >
              {filter.label}
              {filter.value === "FAILED" && failedTotal ? ` (${failedTotal})` : ""}
            </button>
          );
        })}
      </div>

      {error ? <div className="border-b border-line-200 bg-red-50 px-4 py-2 text-sm text-red-700">{error}</div> : null}
      {notice ? <div className="border-b border-line-200 bg-field-50 px-4 py-2 text-sm text-ink-700">{notice}</div> : null}
      <HeldPostNotice
        refusal={mediaListNotice((items ?? []).map(holdOf), "re-processed")}
        id={heldNoticeId}
        className="mx-4 my-3"
        sayPending
        pendingSentence={HELD_POST_PENDING_FILES}
      />

      {items === null && error ? (
        <div className="p-4 text-sm text-ink-700">Could not load the processing queue — see the message above.</div>
      ) : items === null ? (
        <div className="p-4 text-sm text-ink-700">Loading processing jobs...</div>
      ) : items.length === 0 ? (
        <div className="p-4">
          <EmptyState
            title="No processing jobs"
            body={
              statusFilter
                ? `Nothing is ${(STATUS_LABEL[statusFilter] ?? statusFilter).toLowerCase()} right now.`
                : "Audio uploads queue a transcription job here as soon as they finish uploading."
            }
          />
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[880px] text-left text-sm">
            <thead className="bg-surface-50 text-xs uppercase text-ink-500">
              <tr>
                {["File", "Job", "Status", "Attempts", "Requested by", "Created", "Actions"].map((heading) => (
                  <th key={heading} className={`px-4 py-3 ${heading === "Actions" ? "text-right" : ""}`}>
                    {heading}
                  </th>
                ))}
              </tr>
            </thead>
            {items.map((job) => {
              const status = String(job.status).toUpperCase();
              const reason = job.error?.trim();
              const retryable = status === "FAILED" || status === "CANCELLED";
              const hold = holdOf(job);
              return (
                <tbody key={job.id} className="border-b border-line-200 align-top">
                  <tr>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{job.mediaFile?.originalFilename ?? "(file removed)"}</div>
                      <div className="text-xs text-ink-500">{job.mediaFile?.mediaType ?? job.mediaFileId}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {JOB_TYPE_LABEL[String(job.jobType).toUpperCase()] ?? humanize(String(job.jobType))}
                    </td>
                    <td className="px-4 py-3">
                      <StatusChip status={status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {job.attempts} of {job.maxAttempts}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{job.requestedBy?.name ?? job.requestedBy?.email ?? "-"}</td>
                    <td className="whitespace-nowrap px-4 py-3 text-ink-700">{formatDateTime(job.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      {retryable && canOperateQueue ? (
                        <RowActions>
                          <button
                            className={rowAction("edit")}
                            onClick={() => retry(job)}
                            disabled={busyJobId === job.id || writesHeld(hold)}
                            aria-describedby={typeof hold === "string" ? heldNoticeId : undefined}
                            data-testid="media-job-retry"
                          >
                            <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                            {busyJobId === job.id ? "Re-queuing…" : "Retry"}
                          </button>
                          {typeof hold === "string" ? (
                            <span className="self-center rounded-md bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">
                              {MEDIA_HELD_LABEL}
                            </span>
                          ) : null}
                        </RowActions>
                      ) : retryable ? (
                        <span className="text-xs text-ink-500">An admin can re-run this</span>
                      ) : (
                        <span className="text-xs text-ink-500">-</span>
                      )}
                    </td>
                  </tr>
                  {reason ? (
                    <tr>
                      <td colSpan={7} className="px-4 pb-3">
                        <div
                          data-testid="media-job-reason"
                          className={`rounded-md border px-3 py-2 text-sm ${
                            status === "FAILED" ? "border-red-200 bg-red-50 text-red-700" : "border-amber-500/30 bg-amber-100 text-amber-800"
                          }`}
                        >
                          <span className="font-semibold">
                            {status === "FAILED" ? "Why it failed: " : "Last attempt failed — retrying automatically: "}
                          </span>
                          {reason}
                        </div>
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              );
            })}
          </table>
        </div>
      )}
      {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
    </section>
  );
}
