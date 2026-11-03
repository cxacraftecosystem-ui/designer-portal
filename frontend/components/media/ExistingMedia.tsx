"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import {
  HELD_POST_PENDING_FILES,
  HeldPostNotice,
  MEDIA_HELD_LABEL,
  mediaHeldReason,
  mediaListNotice,
  mediaWorkshopIds,
  mediaWriteHold,
  useHeldPostRefusals,
  writesHeld,
  type HeldPostRefusal
} from "@/components/designworkshop/HeldPostNotice";
import { MediaCardGrid } from "@/components/media/MediaCardGrid";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { TranscriptBlock } from "@/components/media/TranscriptBlock";
import { MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { ApiError, apiFetch, listResource } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { downloadMediaUrl } from "@/lib/localMediaDownload";
import type { MediaFile } from "@/lib/types";
import { useConfirm } from "@/components/dialogs";
import { useAdminView } from "@/components/AdminViewProvider";

const IN_FLIGHT_TRANSCRIPT = new Set(["QUEUED", "PROCESSING", "PENDING", "RUNNING"]);

const PAGE_SIZE = 100;

const CHOOSER_FLOOR = 2;

export function ExistingMedia({
  linkedRecordType,
  linkedRecordId,
  recordHold,
  title = "Previously uploaded media",
  readOnly = false
}: {
  linkedRecordType: string;
  linkedRecordId: string;
  recordHold: HeldPostRefusal;
  title?: string;
  readOnly?: boolean;
}) {
  const [items, setItems] = useState<MediaFile[] | null>(null);
  const { canDeleteRecords } = useAdminView();
  const mayRemove = !readOnly && canDeleteRecords;
  const [total, setTotal] = useState(0);
  const [pagesLoaded, setPagesLoaded] = useState(1);
  const [busy, setBusy] = useState(false);
  const [active, setActive] = useState<PreviewMedia | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [picked, setPicked] = useState<string[]>([]);
  const currentRefresh = useRef(0);

  const confirm = useConfirm();
  const heldFor = useHeldPostRefusals((items ?? []).flatMap(mediaWorkshopIds));
  const holdOf = (media: MediaFile): HeldPostRefusal => mediaWriteHold([recordHold, heldFor(mediaWorkshopIds(media))]);
  const heldNoticeId = useId();

  async function removeMedia(media: MediaFile) {
    if (writesHeld(holdOf(media))) return;
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
      setTotal((current) => Math.max(0, current - 1));
      setActive((current) => (current && current.key === media.id ? null : current));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Unable to remove this media file.");
    } finally {
      setRemovingId(null);
    }
  }

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
      setTotal(Math.max(serverTotal, collected.length));
    } catch {
      if (generation !== currentRefresh.current) return;
      setItems((current) => current ?? []);
    } finally {
      if (generation === currentRefresh.current) setBusy(false);
    }
  }, [linkedRecordType, linkedRecordId, pagesLoaded]);

  useEffect(() => {
    setItems(null);
    setTotal(0);
    setPagesLoaded(1);
    setPicked([]);
  }, [linkedRecordType, linkedRecordId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (items === null) return;
    setPicked((current) => {
      if (!current.length) return current;
      const live = new Set(items.map((media) => media.id));
      const kept = current.filter((id) => live.has(id));
      return kept.length === current.length ? current : kept;
    });
  }, [items]);

  const chooserOptions = useMemo<DropdownOption[]>(
    () =>
      (items ?? []).map((media) => ({
        value: media.id,
        label: media.caption || media.originalFilename,
        hint: `${media.mediaType.toLowerCase()} · ${formatDateTime(media.createdAt)}`
      })),
    [items]
  );

  const shown = useMemo(() => {
    if (!picked.length) return items ?? [];
    const want = new Set(picked);
    return (items ?? []).filter((media) => want.has(media.id));
  }, [items, picked]);

  const anyTranscriptInFlight = (items ?? []).some(
    (media) => (media.mediaType === "AUDIO" || media.mediaType === "VIDEO") && IN_FLIGHT_TRANSCRIPT.has((media.transcriptStatus ?? "").toUpperCase())
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

  const hidden = Math.max(0, total - items.length);

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-field-100 p-4">
      <div>
        <h3 className="font-display font-bold text-lg text-ink">{title}</h3>
        <p className="mt-1 text-sm text-ink-muted">
          {total} file{total === 1 ? "" : "s"} already attached. Audio transcripts appear once processing finishes.
          {mayRemove ? " Use the ✕ on a file to remove it." : " Open a file to preview it, or download it."}
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
        {error ? <p className="mt-1 text-sm text-red-700 dark:text-red-400">{error}</p> : null}
        <HeldPostNotice
          refusal={mediaListNotice(shown.map(holdOf), "removed or re-transcribed")}
          id={heldNoticeId}
          className="mt-2"
          sayPending
          pendingSentence={HELD_POST_PENDING_FILES}
        />
      </div>
      {items.length >= CHOOSER_FLOOR ? (
        <div className="grid gap-1">
          <span className="field-label">Which files to show</span>
          <MultiSelectDropdown
            values={picked}
            onChange={setPicked}
            options={chooserOptions}
            placeholder={`All ${items.length} file${items.length === 1 ? "" : "s"}`}
            ariaLabel="Which attached files to show"
            searchable
            confirmOnSelect={false}
          />
          {picked.length ? (
            <p className="text-xs text-ink-muted">
              Showing {shown.length} of the {items.length} files loaded.{" "}
              <button
                type="button"
                onClick={() => setPicked([])}
                className="underline underline-offset-2 hover:text-ink"
              >
                show all
              </button>
              .
            </p>
          ) : (
            <p className="text-xs text-ink-muted">
              Every attached file is listed below. Pick one or more to narrow it — this only changes what you are
              looking at, and never what is attached to the record.
            </p>
          )}
        </div>
      ) : null}
      <MediaCardGrid label={title} maxColumns={2}>
        {shown.map((media) => {
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
          const hold = holdOf(media);
          return (
            <div key={media.id} className="grid content-start gap-2 rounded-md border border-line-200 bg-field-50 p-2">
              <MediaPreviewTile
                item={preview}
                onOpen={() => setActive(preview)}
                onRemove={!mayRemove || removingId === media.id ? undefined : () => removeMedia(media)}
                removeLabel="Remove"
                removeHeld={writesHeld(hold)}
                removeDescribedBy={typeof hold === "string" ? heldNoticeId : undefined}
                statusLabel={typeof hold === "string" ? MEDIA_HELD_LABEL : null}
                onDownload={
                  readOnly && media.url
                    ? () => void downloadMediaUrl(media.url as string, media.originalFilename, media.mimeType)
                    : undefined
                }
              />
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-ink" title={media.originalFilename}>
                  {media.caption || media.originalFilename}
                </div>
                <div className="text-xs text-ink-muted">
                  Uploaded by {media.uploadedBy?.name ?? "Unknown"} · {formatDateTime(media.createdAt)}
                </div>
                <TranscriptBlock
                  media={media}
                  readOnly={readOnly}
                  readOnlyReason={mediaHeldReason(hold)}
                  onUpdated={(updated) =>
                    setItems((current) => (current ? current.map((item) => (item.id === updated.id ? updated : item)) : current))
                  }
                />
              </div>
            </div>
          );
        })}
      </MediaCardGrid>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </section>
  );
}
