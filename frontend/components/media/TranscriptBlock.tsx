"use client";

import { useEffect, useState } from "react";
import { AudioLines, Loader2 } from "lucide-react";

import { TranscriptInsights } from "@/components/media/TranscriptInsights";
import { TranscriptRefine } from "@/components/media/TranscriptRefine";
import { EditedFlag, MarkdownDocument } from "@/components/richtext/MarkdownDocument";
import { useAuth } from "@/components/AuthProvider";
import { useMediaUrl } from "@/components/hooks/useMediaUrl";
import { TranscriptViewer } from "@/components/transcript/TranscriptViewer";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { transcribeMediaNow } from "@/lib/media";
import { isAdmin } from "@/lib/permissions";
import type { MediaFile } from "@/lib/types";

const PROCESSING = new Set(["QUEUED", "PROCESSING", "PENDING", "RUNNING"]);
const DONE = new Set(["COMPLETED", "DONE", "EMPTY"]);

type TranscriptBlockProps = {
  media: MediaFile;
  onUpdated?: (updated: MediaFile) => void;
  readOnlyReason?: string | null;
  readOnly?: boolean;
};

export function TranscriptBlock(props: TranscriptBlockProps) {
  const [updated, setUpdated] = useState<MediaFile | null>(null);
  const media = updated && updated.id === props.media.id ? updated : props.media;
  const inner = { ...props, media };
  const isAudio = media.mediaType === "AUDIO" || (media.mimeType ?? "").toLowerCase().startsWith("audio/");
  const isVideo = media.mediaType === "VIDEO" || (media.mimeType ?? "").toLowerCase().startsWith("video/");
  const refine = (
    <TranscriptRefine
      media={media}
      readOnly={props.readOnly || Boolean(props.readOnlyReason)}
      onUpdated={(next) => {
        setUpdated(next);
        props.onUpdated?.(next);
      }}
    />
  );
  if (!isAudio && !isVideo) {
    return (
      <>
        <LegacyTranscriptBlock {...inner} />
        {refine}
      </>
    );
  }
  return (
    <>
      <TranscriptViewer
        key={updated?.transcriptText ?? "stored"}
        mediaId={media.id}
        audioUrl={media.url}
        video={isVideo}
        fallback={<LegacyTranscriptBlock {...inner} />}
      />
      {refine}
    </>
  );
}

function LegacyTranscriptBlock({
  media,
  onUpdated,
  readOnlyReason = null,
  readOnly = false
}: {
  media: MediaFile;
  onUpdated?: (updated: MediaFile) => void;
  readOnlyReason?: string | null;
  readOnly?: boolean;
}) {
  const { user } = useAuth();
  const [live, setLive] = useState<MediaFile | null>(null);
  const [running, setRunning] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  useEffect(() => {
    setLive(null);
    setFailure(null);
  }, [media.id]);

  const current = live ?? media;
  const { src: playable } = useMediaUrl(current);
  const status = (current.transcriptStatus ?? "").toUpperCase();
  const isAudio = current.mediaType === "AUDIO" || (current.mimeType ?? "").toLowerCase().startsWith("audio/");
  const hasText = !!current.transcriptText && current.transcriptText.trim().length > 0;

  if (!isAudio && current.mediaType !== "VIDEO" && !status && !hasText) return null;

  const player = isAudio && playable ? <AudioPlayer src={playable} className="mt-2" /> : null;

  async function transcribeNow() {
    if (readOnlyReason) return;
    setRunning(true);
    setFailure(null);
    try {
      const updated = await transcribeMediaNow(media.id);
      setLive(updated);
      onUpdated?.(updated);
    } catch (err) {
      setFailure(err instanceof Error ? err.message : "Transcribing now failed. Try again, or check the AI key on the settings page.");
    } finally {
      setRunning(false);
    }
  }

  const control =
    !readOnly && isAdmin(user) && (current.mediaType === "AUDIO" || current.mediaType === "VIDEO") ? (
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="field-button-secondary"
          onClick={transcribeNow}
          disabled={running || Boolean(readOnlyReason)}
        >
          {running ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <AudioLines className="h-4 w-4" aria-hidden />}
          {running ? "Transcribing…" : hasText ? "Re-transcribe now" : "Transcribe now"}
        </button>
        {readOnlyReason ? (
          <span className="rounded-md bg-amber-100 px-2 py-0.5 text-xs text-amber-800">{readOnlyReason}</span>
        ) : (
          <span className="text-xs text-ink-500">Runs immediately, bypassing the queue and the off-peak window.</span>
        )}
      </div>
    ) : null;

  const failureNote = failure ? (
    <div className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{failure}</div>
  ) : null;

  if (running) {
    return (
      <>
        {player}
        <div className="mt-2 flex items-center gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          <span>Transcribing now…</span>
        </div>
        {control}
        {failureNote}
      </>
    );
  }

  if (hasText) {
    return (
      <>
        {player}
        <div className="mt-2">
          <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Transcript</div>
          <MarkdownDocument
            text={current.transcriptText ?? ""}
            filenameBase={`${current.originalFilename ?? "recording"}-transcript`}
          >
            <EditedFlag edited={current.transcriptEditedAt ? true : undefined} />
          </MarkdownDocument>
        </div>
        {status === "COMPLETED" ? <TranscriptInsights mediaId={current.id} readOnly={readOnly} /> : null}
        {control}
        {failureNote}
      </>
    );
  }

  if (!status) {
    return (
      <>
        {player}
        {control}
        {failureNote}
      </>
    );
  }

  if (PROCESSING.has(status)) {
    return (
      <>
        {player}
        <div className="mt-2 flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          <span>Transcribing audio… refreshes automatically while processing.</span>
        </div>
        {control}
        {failureNote}
      </>
    );
  }

  if (DONE.has(status)) {
    return (
      <>
        {player}
        <div className="mt-2 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm text-ink-muted">Transcript completed — no speech detected.</div>
        {control}
        {failureNote}
      </>
    );
  }

  return (
    <>
      {player}
      <div className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        Transcript {status ? status.toLowerCase() : "unavailable"}
        {current.transcriptError ? `: ${current.transcriptError}` : "."}
      </div>
      {control}
      {failureNote}
    </>
  );
}
