"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import { Download, ExternalLink, FileText, Headphones, Image as ImageIcon, Loader2, Maximize2, Video, X } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";

import { useMediaUrl } from "@/components/hooks/useMediaUrl";
import { DownloadMediaButton } from "@/components/media/DownloadMediaButton";
import { TranscriptInsights } from "@/components/media/TranscriptInsights";
import { RetryMediaButton } from "@/components/media/MediaActionButton";
import { Markdown } from "@/components/Markdown";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { bytes } from "@/lib/format";
import { downloadMediaUrl } from "@/lib/localMediaDownload";
import type { MediaType } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type PreviewMedia = {
  key: string;
  id?: string | null;
  name: string;
  mediaType: MediaType;
  mimeType?: string | null;
  sizeBytes?: number | string | null;
  url?: string | null;
  caption?: string | null;
  transcriptStatus?: string | null;
  transcriptText?: string | null;
  transcriptError?: string | null;
};

export function resolvePreviewKind(item: Pick<PreviewMedia, "mediaType" | "mimeType" | "name">): MediaType {
  if (item.mediaType === "IMAGE" || item.mediaType === "VIDEO" || item.mediaType === "AUDIO" || item.mediaType === "PDF") {
    return item.mediaType;
  }
  const mime = (item.mimeType ?? "").toLowerCase();
  if (mime.startsWith("image/")) return "IMAGE";
  if (mime.startsWith("video/")) return "VIDEO";
  if (mime.startsWith("audio/")) return "AUDIO";
  if (mime === "application/pdf" || item.name.toLowerCase().endsWith(".pdf")) return "PDF";
  return item.mediaType || "DOCUMENT";
}

function LightboxTranscript({ item }: { item: PreviewMedia }) {
  if (resolvePreviewKind(item) !== "AUDIO" && resolvePreviewKind(item) !== "VIDEO") return null;
  const status = (item.transcriptStatus ?? "").toUpperCase();
  const text = item.transcriptText?.trim();
  if (text) {
    return (
      <div className="rounded-md border border-line-200 bg-field-50 p-3">
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Transcript</div>
        <Markdown text={text} />
        {item.id && status === "COMPLETED" ? <TranscriptInsights mediaId={item.id} readOnly /> : null}
      </div>
    );
  }
  if (!status && !item.id) return null;
  if (["QUEUED", "PROCESSING", "PENDING", "RUNNING"].includes(status) || !status) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
        <span>Transcribing audio… the transcript will appear here once processing finishes.</span>
      </div>
    );
  }
  if (["COMPLETED", "EMPTY", "DONE"].includes(status)) {
    return <div className="rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm text-ink-muted">Transcript completed — no speech detected.</div>;
  }
  return (
    <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      Transcript {status.toLowerCase()}
      {item.transcriptError ? `: ${item.transcriptError}` : "."}
    </div>
  );
}

function mediaLabel(item: PreviewMedia) {
  return [item.mediaType, item.sizeBytes ? bytes(item.sizeBytes) : null, item.mimeType].filter(Boolean).join(" - ");
}

function iconForType(type: MediaType) {
  if (type === "IMAGE") return <ImageIcon className="h-5 w-5" aria-hidden />;
  if (type === "VIDEO") return <Video className="h-5 w-5" aria-hidden />;
  if (type === "AUDIO") return <Headphones className="h-5 w-5" aria-hidden />;
  return <FileText className="h-5 w-5" aria-hidden />;
}

export function MediaPreviewTile({
  item,
  onOpen,
  action,
  onRemove,
  removeLabel = "Discard",
  removeHeld = false,
  removeDescribedBy,
  progress = null,
  failed = false,
  statusLabel = null,
  onRetry,
  onDownload,
  downloadUnavailableReason = null
}: {
  item: PreviewMedia;
  onOpen: () => void;
  action?: ReactNode;
  onRemove?: () => void;
  removeLabel?: string;
  removeHeld?: boolean;
  removeDescribedBy?: string;
  progress?: number | null;
  failed?: boolean;
  statusLabel?: string | null;
  onRetry?: () => void;
  onDownload?: () => void;
  downloadUnavailableReason?: string | null;
}) {
  const kind = resolvePreviewKind(item);
  const { src, onError } = useMediaUrl(item);
  const percent = progress === null ? null : Math.round(Math.min(1, Math.max(0, progress)) * 100);
  return (
    <div
      className="flex min-w-0 cursor-pointer flex-wrap items-start gap-2 rounded-md border border-line-200 bg-field-50 p-2 transition hover:border-purple-300"
      onClick={onOpen}
    >
      <button
        type="button"
        className="relative grid aspect-4/3 min-w-0 flex-[1_1_6rem] place-items-center overflow-hidden rounded-md bg-field-100 text-left text-ink-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-field-600"
        onClick={(event) => {
          event.stopPropagation();
          onOpen();
        }}
        aria-label={`Open preview for ${item.name}`}
      >
        {kind === "IMAGE" && src ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={src} onError={onError} alt={item.caption || item.name} className="h-full w-full object-cover" loading="lazy" />
        ) : kind === "VIDEO" && src ? (
          <video src={src} onError={onError} className="h-full w-full object-cover" muted playsInline />
        ) : kind === "AUDIO" ? (
          <div className="grid gap-2 text-center">
            <div className="mx-auto rounded-full bg-card p-3 text-field-700 dark:text-purple-300 shadow-sm">{iconForType(kind)}</div>
            <span className="text-xs font-semibold">Audio clip</span>
          </div>
        ) : (
          <div className="grid gap-2 text-center">
            <div className="mx-auto rounded-full bg-card p-3 text-field-700 dark:text-purple-300 shadow-sm">{iconForType(kind)}</div>
            <span className="text-xs font-semibold">{kind === "PDF" ? "PDF document" : "Document"}</span>
          </div>
        )}
        <span className="absolute bottom-2 right-2 rounded-full bg-card/95 p-1 text-ink shadow-sm">
          <Maximize2 className="h-3.5 w-3.5" aria-hidden />
        </span>
      </button>
      <div className="grid min-w-0 flex-[999_1_8rem] gap-2">
        <div className="flex min-w-0 items-start gap-2">
          <div className="min-w-0 flex-1">
            <div className="line-clamp-2 break-all text-sm font-medium text-ink" title={item.name}>
              {item.name}
            </div>
            <div className="truncate text-xs text-ink-muted">{mediaLabel(item)}</div>
          </div>
          {onRemove ? (
            <button
              type="button"
              disabled={removeHeld}
              onClick={(event) => {
                event.stopPropagation();
                if (!removeHeld) onRemove();
              }}
              aria-label={`${removeLabel} ${item.name}`}
              aria-describedby={removeHeld ? removeDescribedBy : undefined}
              title={`${removeLabel} ${item.name}`}
              className="grid h-7 w-7 shrink-0 place-items-center rounded-full border border-line-200 bg-card text-ink-muted transition enabled:hover:border-red-300 enabled:hover:bg-red-50 enabled:hover:text-red-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          ) : null}
        </div>
        {percent !== null && !failed ? (
          <ProgressBar percent={percent} aria-hidden fillClassName="transition-all" />
        ) : null}
        {statusLabel || onDownload ? (
          <div className="grid min-w-0 gap-1.5">
            {statusLabel ? (
              <span className={`min-w-0 truncate text-xs ${failed ? "font-medium text-red-800 dark:text-red-300" : "text-ink-muted"}`}>{statusLabel}</span>
            ) : null}
            <span className="flex flex-wrap items-center gap-2">
              {failed && onRetry ? <RetryMediaButton name={item.name} onRetry={onRetry} /> : null}
              {onDownload ? (
                <DownloadMediaButton name={item.name} onDownload={onDownload} unavailableReason={downloadUnavailableReason} />
              ) : null}
            </span>
          </div>
        ) : null}
        {action ? (
          <div className="min-w-0" onClick={(event) => event.stopPropagation()}>
            {action}
          </div>
        ) : null}
      </div>
    </div>
  );
}

async function saveToDevice(url: string, name: string) {
  await downloadMediaUrl(url, name || "media");
}

export function MediaLightbox({ item, onClose }: { item: PreviewMedia; onClose: () => void }) {
  const downOnBackdrop = useRef(false);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const kind = resolvePreviewKind(item);
  const { src, onError } = useMediaUrl(item);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    panel?.focus();

    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key !== "Tab" || !panel) return;
      const stops = Array.from(
        panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), video[controls], [tabindex]:not([tabindex="-1"])')
      ).filter((node) => node.offsetParent !== null);
      if (stops.length === 0) {
        event.preventDefault();
        panel.focus();
        return;
      }
      const first = stops[0];
      const last = stops[stops.length - 1];
      const focused = document.activeElement as HTMLElement | null;
      const outside = !focused || !panel.contains(focused);
      if (event.shiftKey && (outside || focused === first || focused === panel)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (outside || focused === last)) {
        event.preventDefault();
        first.focus();
      }
    }

    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      if (opener?.isConnected) opener.focus();
    };
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-100 grid place-items-center bg-black/70 p-4"
      role="dialog"
      aria-modal="true"
      aria-label={`Preview ${item.name}`}
      onMouseDown={(event) => {
        downOnBackdrop.current = event.target === event.currentTarget;
      }}
      onClick={(event) => {
        if (downOnBackdrop.current && event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        className="grid max-h-[92vh] w-full max-w-5xl gap-3 overflow-hidden rounded-lg bg-field-50 p-4 shadow-2xl focus:outline-hidden"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="truncate font-display font-bold text-2xl text-ink">{item.caption || item.name}</h2>
            <p className="text-sm text-ink-muted">{mediaLabel(item)}</p>
          </div>
          <ActionGroup>
            {src ? (
              <button type="button" className="field-button-secondary" onClick={() => saveToDevice(src, item.name)}>
                <Download className="h-4 w-4" aria-hidden />
                Save
              </button>
            ) : null}
            {src ? (
              <a className="field-button-secondary" href={src} target="_blank" rel="noreferrer">
                <ExternalLink className="h-4 w-4" aria-hidden />
                Open
              </a>
            ) : null}
            <button type="button" className="field-button-secondary" onClick={onClose} aria-label="Close preview">
              <X className="h-4 w-4" aria-hidden />
            </button>
          </ActionGroup>
        </div>
        <div className="grid max-h-[74vh] place-items-center overflow-auto rounded-md bg-card p-3">
          {kind === "IMAGE" && src ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={src} onError={onError} alt={item.caption || item.name} className="max-h-[70vh] max-w-full rounded-md object-contain" />
          ) : kind === "VIDEO" && src ? (
            <video src={src} onError={onError} controls className="max-h-[70vh] w-full rounded-md bg-black" />
          ) : kind === "AUDIO" && src ? (
            <AudioPlayer src={src} className="w-full" />
          ) : kind === "PDF" && src ? (
            <iframe src={src} onError={onError} title={item.name} className="h-[70vh] w-full rounded-md border border-line-200" />
          ) : src ? (
            <div className="grid w-full max-w-md justify-items-center gap-3 rounded-md border border-line-200 bg-field-50 p-6 text-center">
              <div className="rounded-full bg-card p-3 text-field-700 dark:text-purple-300 shadow-sm">{iconForType(kind)}</div>
              <div className="min-w-0 w-full">
                <div className="truncate text-sm font-medium text-ink" title={item.name}>{item.name}</div>
                <div className="text-xs text-ink-muted">{mediaLabel(item)}</div>
              </div>
              <p className="text-sm text-ink-muted">This file type cannot be rendered inline — download it to view.</p>
              <ActionGroup>
                <button type="button" className="field-button" onClick={() => saveToDevice(src, item.name)}>
                  <Download className="h-4 w-4" aria-hidden />
                  Download file
                </button>
                <a className="field-button-secondary" href={src} target="_blank" rel="noreferrer">
                  <ExternalLink className="h-4 w-4" aria-hidden />
                  Open in new tab
                </a>
              </ActionGroup>
            </div>
          ) : (
            <div className="grid gap-3 text-center text-ink-muted">
              {iconForType(kind)}
              <p>No preview is available yet.</p>
            </div>
          )}
        </div>
        <LightboxTranscript item={item} />
      </div>
    </div>
  );
}
