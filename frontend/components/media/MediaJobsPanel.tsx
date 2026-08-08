"use client";

import { Fragment, useCallback, useEffect, useState } from "react";
import { AlertTriangle, Ban, CheckCircle2, Clock, ListChecks, Loader2, Play, RotateCcw } from "lucide-react";

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

/**
 * THE MEDIA PROCESSING QUEUE, MADE VISIBLE AND RE-RUNNABLE.
 *
 * The queue has always drained itself (app/main.py starts the worker; app/worker.py ticks it), so
 * transcription worked and the missing half went unnoticed: a job that exhausted its attempts landed
 * in FAILED with the provider's reason in `MediaProcessingJob.error` — a column no screen on either
 * client read. The file's row just kept saying "Transcript FAILED" with nothing after the colon, and
 * nobody, at any rank, could ask for it again. "Failed", no reason, no retry, is what this panel
 * exists to end, so the reason is rendered in full (not truncated, not a tooltip) on its own line.
 *
 * PERMISSIONS, mirroring the three routes exactly (backend/app/api/routes/media.py):
 *   GET  /media/jobs            get_current_user — everyone; the SERVER narrows a non-admin to the
 *                               jobs they requested, so the panel itself is never hidden.
 *   POST /media/jobs/{id}/retry require_admin    — Retry renders only for `isAdmin(user)`.
 *   POST /media/jobs/process    require_admin    — so does "Run queue now".
 *
 * Admin VIEW is deliberately not ANDed into those two. This is not admin chrome — /media is an open
 * route and the list is owner-scoped — and an admin who turned the toggle off still needs to be able
 * to re-run a failed transcription, which is a repair, not a privileged surface.
 */

/** Statuses that mean the queue still has work in hand, so the list should keep refreshing itself. */
const IN_FLIGHT = new Set(["QUEUED", "PROCESSING"]);

/**
 * One tone + icon + word per status. Colour never carries the meaning alone: the icon and the label
 * survive colour-blindness, greyscale printing and forced-colours mode, and the PROCESSING spinner
 * is paired with its own word so a reduced-motion reader (who gets a still spinner) loses nothing.
 */
const STATUS_TONE: Record<string, string> = {
  QUEUED: "border-line-200 bg-surface-50 text-ink-500",
  PROCESSING: "border-amber-500/30 bg-amber-100 text-amber-800",
  COMPLETED: "border-success-600/25 bg-success-100 text-success-600",
  FAILED: "border-error-600/25 bg-error-100 text-error-600",
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

/** SOME_STATUS -> "Some status", for anything the enum grows before this file hears about it. */
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

/** The five states plus "All", as filter pills. Empty value means "no statusFilter parameter". */
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
  // Fetch generation, not an AbortController: listResource takes no signal, and what actually
  // matters is ignoring the answer that arrives after a newer one (the filter pills and the poll
  // both fire loads).
  const [loadToken, setLoadToken] = useState(0);

  const load = useCallback(async (pageToLoad: number, filter: string) => {
    try {
      const [result, failed] = await Promise.all([
        listMediaProcessingJobs({ page: pageToLoad, pageSize: 20, statusFilter: filter }),
        // A failed job is ordered by createdAt like every other, so on a 12-page queue it can sit
        // anywhere. Counting them separately is what lets the Failed pill say how many there are
        // instead of leaving a reader to page through looking for one.
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
        // Keep whatever is on screen; only an empty FIRST load should leave the panel blank, and
        // then it says why rather than reading as "there are no jobs".
        setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [page, statusFilter, loadToken, load]);

  const items = data?.items ?? null;
  // While the queue still has work in hand, re-read every 15s (the interval ExistingMedia uses for
  // the same reason) so a job moving QUEUED -> PROCESSING -> COMPLETED does not need a page reload.
  const anyInFlight = (items ?? []).some((job) => IN_FLIGHT.has(String(job.status).toUpperCase()));
  useEffect(() => {
    if (!anyInFlight) return;
    const timer = window.setInterval(() => setLoadToken((token) => token + 1), 15000);
    return () => window.clearInterval(timer);
  }, [anyInFlight]);

  async function retry(job: MediaProcessingJob) {
    setBusyJobId(job.id);
    setError(null);
    setNotice(null);
    try {
      await retryMediaProcessingJob(job.id);
      setNotice(
        `${JOB_TYPE_LABEL[String(job.jobType).toUpperCase()] ?? humanize(String(job.jobType))} re-queued for ` +
          `${job.mediaFile?.originalFilename ?? "this file"}. The worker picks it up on its next pass.`
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
          ? // Not "nothing to do": transcription only runs inside the off-peak window or while the
            // server is idle, and never during a rate-limit cooldown, so an empty drain is usually a
            // closed window rather than an empty queue. Saying "nothing to run" here would have an
            // admin press it twice and conclude the button is broken.
            "No job was eligible to run just now — transcription waits for the off-peak window (or an idle server) and pauses during a provider cooldown. Queued jobs stay queued."
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
            <ListChecks className="h-4 w-4 text-field-600" aria-hidden />
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

      {items === null ? (
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
              // A QUEUED job can carry the previous attempt's error while it waits out its backoff.
              // Showing it (labelled as a retry in progress) is the difference between "stuck" and
              // "already trying again" — two states that otherwise look identical from here.
              const reason = job.error?.trim();
              const retryable = status === "FAILED" || status === "CANCELLED";
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
                            disabled={busyJobId === job.id}
                            data-testid="media-job-retry"
                          >
                            <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                            {busyJobId === job.id ? "Re-queuing…" : "Retry"}
                          </button>
                        </RowActions>
                      ) : retryable ? (
                        // Never a button that would 403: retry is require_admin while this list is
                        // owner-scoped, so the person who uploaded the file can see the failure and
                        // still not be the one who may re-run it. Say who can.
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
