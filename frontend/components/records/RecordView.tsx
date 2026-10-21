"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { MessageSquare, Pencil } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { CollabDialog } from "@/components/CollabDialog";
import { MediaCardGrid } from "@/components/media/MediaCardGrid";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { TranscriptBlock } from "@/components/media/TranscriptBlock";
import { PageHeader } from "@/components/PageHeader";
import { CompletenessMeter } from "@/components/records/CompletenessMeter";
import { StatusBadge } from "@/components/StatusBadge";
import { RecordDangerZone } from "@/components/records/RecordDangerZone";
import { RECORD_LIST_HREF } from "@/components/records/dangerZoneRules";
import type { RecordFieldRow } from "@/components/records/recordFields";
import { ApiError, apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { downloadMediaUrl } from "@/lib/localMediaDownload";
import { isCompletenessKind, type CompletenessBlock } from "@/lib/completenessCore";
import { canUpdateRecord, recordLoadFailure, recordUpdateHref, type ViewableRecord } from "@/lib/recordView";
import type { MediaFile, RecordStatus } from "@/lib/types";

/**
 * A record's READ-ONLY page — what a click anywhere on its row or card opens (owner ruling,
 * 2026-10-16). Every field is printed and none is a control; the one way to change the record is the
 * "Update" button in the header, drawn only for a reader the server would let save
 * (`canUpdateRecord`), which opens the record's existing edit form.
 *
 * ONE SHELL FOR EVERY KIND, so the seven pages cannot drift into seven layouts: the header (back
 * arrow, title, Update, Discuss), the loading and failure states, and a status chip. The body —
 * the field list, the media — is the page's own `children`, built from `recordFields.ts`.
 *
 * A failed load says which failure in words. A 404 here is the usual way a record reads as gone
 * (deleted, or not visible to this account), and "Not Found" — FastAPI's literal body — is not a
 * sentence a researcher can act on.
 */
export function RecordViewPage<T extends { id: string; status?: RecordStatus | string | null }>({
  kind,
  id,
  endpoint,
  noun,
  icon,
  description,
  titleOf,
  collabType,
  onLoadError,
  children
}: {
  kind: ViewableRecord;
  id: string;
  /** The `GET` that returns this record in full, e.g. `/artisans/{id}`. */
  endpoint: string;
  /** "artisan", "product record" — the word the failure and loading lines use. */
  noun: string;
  icon: ReactNode;
  description?: string;
  titleOf: (record: T) => string;
  /** The comments-and-history record type, or omit where the kind has none. */
  collabType?: string;
  /** Handle a failed load (true = handled, draw no error) — the artisan page follows a merge. */
  onLoadError?: (error: unknown) => boolean;
  children: (record: T) => ReactNode;
}) {
  const { user } = useAuth();
  const [record, setRecord] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [collabOpen, setCollabOpen] = useState(false);
  // Through a ref, so a new callback each render does not refetch the record.
  const onLoadErrorRef = useRef(onLoadError);
  useEffect(() => {
    onLoadErrorRef.current = onLoadError;
  });

  useEffect(() => {
    let cancelled = false;
    apiFetch<T>(endpoint)
      .then((loaded) => {
        if (!cancelled) setRecord(loaded);
      })
      .catch((err) => {
        if (cancelled) return;
        if (onLoadErrorRef.current?.(err)) return;
        setError(recordLoadFailure(noun, err instanceof ApiError ? err.status : null, err instanceof Error ? err.message : null));
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, noun]);

  const updateHref = recordUpdateHref(kind, id);
  const mayUpdate = Boolean(updateHref) && canUpdateRecord(user, kind);
  // The server's block (2026-10-18), on the five kinds that have rules. Its pills open the edit form
  // at the missing field for a reader who may update; for anyone else they are plain words.
  const completeness =
    record && isCompletenessKind(kind) ? ((record as { completeness?: CompletenessBlock | null }).completeness ?? null) : null;

  return (
    <>
      <PageHeader
        title={record ? titleOf(record) : `Loading ${noun}…`}
        description={description}
        icon={icon}
        actions={
          record ? (
            <>
              {collabType ? (
                <button type="button" className="field-button-secondary" onClick={() => setCollabOpen(true)}>
                  <MessageSquare className="h-4 w-4" aria-hidden />
                  Discuss
                </button>
              ) : null}
              {mayUpdate && updateHref ? (
                <Link className="field-button" href={updateHref}>
                  <Pencil className="h-4 w-4" aria-hidden />
                  Update
                </Link>
              ) : null}
            </>
          ) : null
        }
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {record ? (
        <div className="grid gap-5">
          {completeness ? <CompletenessMeter block={completeness} updateHref={mayUpdate ? updateHref : null} /> : null}
          {record.status ? (
            <div className="flex items-center gap-2 text-sm text-ink-700">
              <span className="field-label">Status</span>
              <StatusBadge status={record.status} />
            </div>
          ) : null}
          {children(record)}
          <RecordDangerZone noun={noun} endpoint={endpoint} afterHref={RECORD_LIST_HREF[kind] ?? "/dashboard"} />
        </div>
      ) : error ? null : (
        <div className="panel p-4 text-sm text-ink-500">Loading {noun}…</div>
      )}
      {collabType ? <CollabDialog recordType={collabType} recordId={collabOpen ? id : null} onClose={() => setCollabOpen(false)} /> : null}
    </>
  );
}

/**
 * The record's fields as a label column and a value column. A `<dl>`, because that is what this is:
 * a list of terms and what they are, which a screen reader announces as pairs. `pre-line` keeps the
 * line breaks of a numbered Do's list or a long note; `wrap-break-word` keeps a bare URL inside it.
 */
export function RecordFieldList({ title = "Details", rows }: { title?: string; rows: RecordFieldRow[] }) {
  return (
    <section className="panel overflow-hidden" aria-label={title}>
      <h2 className="border-b border-line-200 px-5 py-3 font-display text-lg font-bold text-ink-900">{title}</h2>
      {rows.length === 0 ? (
        <p className="px-5 py-4 text-sm text-ink-500">Nothing else has been recorded for this entry yet.</p>
      ) : (
        <dl className="divide-y divide-line-200">
          {rows.map((row) => (
            <div key={row.label} className="grid gap-1 px-5 py-2.5 sm:grid-cols-[14rem_minmax(0,1fr)] sm:gap-4">
              <dt className="text-sm font-semibold text-ink-900">{row.label}</dt>
              <dd className="min-w-0 whitespace-pre-line wrap-break-word text-sm leading-6 text-ink-700">{row.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

/**
 * Files already in hand on the record (a process's own clips, a step's captures) shown read-only:
 * preview, play, read the transcript, Download. The fetched equivalent for a record whose files are
 * listed by the media endpoint is `ExistingMedia readOnly`.
 */
export function ReadOnlyMediaGallery({ title, items, empty }: { title: string; items: MediaFile[]; empty?: string }) {
  const [active, setActive] = useState<PreviewMedia | null>(null);
  if (items.length === 0) {
    return empty ? <p className="text-sm text-ink-500">{empty}</p> : null;
  }
  return (
    <>
      <MediaCardGrid label={title} maxColumns={2}>
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
            <div key={media.id} className="grid content-start gap-2 rounded-md border border-line-200 bg-field-50 p-2">
              <MediaPreviewTile
                item={preview}
                onOpen={() => setActive(preview)}
                onDownload={media.url ? () => void downloadMediaUrl(media.url as string, media.originalFilename, media.mimeType) : undefined}
              />
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-ink" title={media.originalFilename}>
                  {media.caption || media.originalFilename}
                </div>
                <div className="text-xs text-ink-muted">
                  Uploaded by {media.uploadedBy?.name ?? "Unknown"} · {formatDateTime(media.createdAt)}
                </div>
                <TranscriptBlock media={media} readOnly />
              </div>
            </div>
          );
        })}
      </MediaCardGrid>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </>
  );
}
