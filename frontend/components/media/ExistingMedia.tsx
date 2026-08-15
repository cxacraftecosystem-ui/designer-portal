"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { TranscriptBlock } from "@/components/media/TranscriptBlock";
import { ApiError, apiFetch, listResource } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { MediaFile } from "@/lib/types";
import { useConfirm } from "@/components/dialogs";

// Transcript statuses that mean a job is still running (shared vocabulary with the backend).
const IN_FLIGHT_TRANSCRIPT = new Set(["QUEUED", "PROCESSING", "PENDING", "RUNNING"]);

/**
 * How many attachments one request asks for. 100 is not a preference, it is the CEILING: `GET /media`
 * declares `pageSize: int = Query(20, ge=1, le=100)`, so no single request can ever return more and
 * raising this number silently gets a 422 instead of more files. That ceiling is the whole reason
 * for the paging below — see `refresh`.
 */
const PAGE_SIZE = 100;

/**
 * Shows the media already attached to a saved record (by linked type/id), with each item's
 * upload provenance (who uploaded it, when) and — for audio — its transcript / a "transcribing…"
 * spinner while the Whisper job is still running. Used on every edit page so previously uploaded
 * media is always visible.
 */
export function ExistingMedia({
  linkedRecordType,
  linkedRecordId,
  title = "Previously uploaded media"
}: {
  linkedRecordType: string;
  linkedRecordId: string;
  title?: string;
}) {
  const [items, setItems] = useState<MediaFile[] | null>(null);
  /**
   * How many files the SERVER says are attached, which is not the same number as `items.length` and
   * was being printed as if it were. The panel asked for one page of 100 — the endpoint's maximum —
   * threw `PageResult.total` away, and rendered `{items.length} files already attached`. A record
   * carrying a bulk import (the /media form takes a multi-file selection against one linked record)
   * therefore reported its own PAGE SIZE as a fact: "100 files already attached", for 100 or for
   * 340. Audit 2026-08-15 (MINOR, frontend).
   */
  const [total, setTotal] = useState(0);
  /**
   * How many pages this panel currently holds. It starts at one and only grows, by the button under
   * the list. `/media` orders `createdAt desc`, so the files that fall off the end are the OLDEST —
   * and this panel is the only per-record screen that can open or remove them, which is what made
   * the invisible tail worth a control rather than only an honest sentence.
   */
  const [pagesLoaded, setPagesLoaded] = useState(1);
  const [busy, setBusy] = useState(false);
  const [active, setActive] = useState<PreviewMedia | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  /**
   * Which refresh is the current one. Two can overlap easily now: the 15s transcript poll fires on
   * its own timer while "Show older files" is fetching two pages, and each writes the whole list.
   * Same counter as the list pages (/artisans, /products, /tools, /processes, /media) and for the
   * same reason — `listResource` takes no AbortSignal, so the late answer is ignored rather than
   * cancelled. Without it a poll that started before the button can land after it and drop the older
   * page the designer just asked for, which looks exactly like the button not working.
   */
  const currentRefresh = useRef(0);

  const confirm = useConfirm();
  async function removeMedia(media: MediaFile) {
    const ok = await confirm({
      title: "Remove this file?",
      body: `"${media.caption || media.originalFilename}" will be removed from this record.`,
      note: "The file is permanently deleted from storage. This cannot be undone.",
      confirmLabel: "Remove file",
      tone: "danger"
    });
    if (!ok) return;
    setError(null);
    setRemovingId(media.id);
    try {
      await apiFetch(`/media/${media.id}`, { method: "DELETE" });
      setItems((current) => (current ? current.filter((m) => m.id !== media.id) : current));
      // The count is the server's, so it has to move when we delete a row without re-asking. Left
      // alone, removing a file from a 3-file record would leave the sentence reading "3 files
      // already attached" over two tiles — the same class of lie this panel was just fixed for.
      setTotal((current) => Math.max(0, current - 1));
      setActive((current) => (current && current.key === media.id ? null : current));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Unable to remove this media file.");
    } finally {
      setRemovingId(null);
    }
  }

  /**
   * Load every page this panel is currently showing, oldest request first, and keep the server's
   * `total` beside the rows.
   *
   * It re-reads page 1 rather than appending only the new page on purpose: a delete anywhere in the
   * list shifts every later row forward a slot, so appending page 2 to a stale page 1 would both
   * duplicate a row and skip one. Re-reading is one extra request on a screen that already polls
   * every 15 seconds, and it cannot drift.
   *
   * The loop stops early on a short page so a record with exactly 100 files does not pay for a
   * second, empty request every poll.
   */
  const refresh = useCallback(async () => {
    const generation = (currentRefresh.current += 1);
    setBusy(true);
    try {
      const collected: MediaFile[] = [];
      let serverTotal = 0;
      for (let page = 1; page <= pagesLoaded; page += 1) {
        const result = await listResource<MediaFile>("/media", {
          linkedRecordType,
          linkedRecordId,
          page,
          pageSize: PAGE_SIZE
        });
        if (generation !== currentRefresh.current) return;
        collected.push(...result.items);
        serverTotal = result.total;
        if (result.items.length < PAGE_SIZE) break;
      }
      setItems(collected);
      // `total` is what the sentence prints, so it must never be able to read as FEWER files than
      // are drawn on screen — a server that answers a stale count while a page is being appended
      // would otherwise produce "2 files already attached" above 102 tiles.
      setTotal(Math.max(serverTotal, collected.length));
    } catch {
      if (generation !== currentRefresh.current) return;
      // Keep whatever we already have; only show the empty list on a failed FIRST load.
      setItems((current) => current ?? []);
    } finally {
      if (generation === currentRefresh.current) setBusy(false);
    }
  }, [linkedRecordType, linkedRecordId, pagesLoaded]);

  /**
   * A different record is a different list: blank it and go back to one page. Separate from the
   * fetch effect below because `refresh` also changes identity when `pagesLoaded` grows, and
   * blanking the list there would replace the tiles with "Loading attached media…" every time
   * somebody pressed "Show older files" — the older files would arrive, but the screen would have
   * thrown away everything already on it to get them.
   */
  useEffect(() => {
    setItems(null);
    setTotal(0);
    setPagesLoaded(1);
  }, [linkedRecordType, linkedRecordId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // While any audio transcript is still in flight, re-fetch every 15s so the transcript block
  // updates without a manual page reload. The interval is cleared as soon as nothing is in flight.
  const anyTranscriptInFlight = (items ?? []).some(
    (media) => media.mediaType === "AUDIO" && IN_FLIGHT_TRANSCRIPT.has((media.transcriptStatus ?? "").toUpperCase())
  );

  useEffect(() => {
    if (!anyTranscriptInFlight) return;
    const timer = window.setInterval(() => {
      refresh();
    }, 15000);
    return () => window.clearInterval(timer);
  }, [anyTranscriptInFlight, refresh]);

  if (items === null) return <p className="text-sm text-ink-muted">Loading attached media…</p>;
  if (items.length === 0) return <p className="text-sm text-ink-muted">No media attached to this record yet.</p>;

  /**
   * Attached but not on screen. Clamped at zero because `total` and `items` are written by two
   * different paths — a delete decrements `total` locally, a poll rewrites both — and a transient
   * negative would render "-1 older files are not listed here", which is worse than the lie this
   * whole change removes.
   */
  const hidden = Math.max(0, total - items.length);

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-field-100 p-4">
      <div>
        <h3 className="font-display font-bold text-lg text-ink">{title}</h3>
        {/* `total` — the server's count — not `items.length`, which is only how many of them this
            panel has asked for so far. Those two numbers were the same thing right up until a
            record carried more than one page, which is precisely when the sentence mattered. */}
        <p className="mt-1 text-sm text-ink-muted">
          {total} file{total === 1 ? "" : "s"} already attached. Audio transcripts appear once processing finishes. Use the ✕ on a file to remove it.
        </p>
        {hidden > 0 ? (
          <p className="mt-1 text-sm text-ink-muted">
            Showing the {items.length} most recent. {hidden} older file{hidden === 1 ? " is" : "s are"} not listed here yet
            {" — "}
            <button
              type="button"
              onClick={() => setPagesLoaded((current) => current + 1)}
              disabled={busy}
              className="underline underline-offset-2 hover:text-ink disabled:no-underline disabled:opacity-60"
            >
              {busy ? "loading…" : `show ${Math.min(hidden, PAGE_SIZE)} more`}
            </button>
            .
          </p>
        ) : null}
        {error ? <p className="mt-1 text-sm text-red-700">{error}</p> : null}
      </div>
      <div className="grid gap-3">
        {items.map((media) => {
          const preview: PreviewMedia = {
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
          return (
            <div key={media.id} className="grid gap-2 rounded-md border border-line-200 bg-field-50 p-2 sm:grid-cols-[200px_1fr] sm:items-start">
              <MediaPreviewTile
                item={preview}
                onOpen={() => setActive(preview)}
                onRemove={removingId === media.id ? undefined : () => removeMedia(media)}
                removeLabel="Remove"
              />
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-ink" title={media.originalFilename}>
                  {media.caption || media.originalFilename}
                </div>
                <div className="text-xs text-ink-muted">
                  Uploaded by {media.uploadedBy?.name ?? "Unknown"} · {formatDateTime(media.createdAt)}
                </div>
                {/* An admin re-run replaces the row in place, so the 15s poll (and any later
                    refresh) does not put the stale transcript back on screen a moment later. */}
                <TranscriptBlock
                  media={media}
                  onUpdated={(updated) =>
                    setItems((current) => (current ? current.map((item) => (item.id === updated.id ? updated : item)) : current))
                  }
                />
              </div>
            </div>
          );
        })}
      </div>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </section>
  );
}
