"use client";

import { useEffect, useState } from "react";
import { AudioLines, Loader2 } from "lucide-react";

import { Markdown } from "@/components/Markdown";
import { useAuth } from "@/components/AuthProvider";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { transcribeMediaNow } from "@/lib/media";
import { isAdmin } from "@/lib/permissions";
import type { MediaFile } from "@/lib/types";

const PROCESSING = new Set(["QUEUED", "PROCESSING", "PENDING", "RUNNING"]);
const DONE = new Set(["COMPLETED", "DONE", "EMPTY"]);

/**
 * Inline transcript for an audio (or any transcribable) media file. Audio items first render an
 * inline themed player, then the transcript below it:
 * - While the backend transcription job is queued/processing, shows a spinner ("buffer icon").
 * - When done, shows the transcript text (or an "empty" note).
 * - On failure / unavailable, shows the reason so it never silently disappears.
 * Renders nothing for non-audio media with no transcript data.
 *
 * ANDROID PARITY — the "Transcribe now" / "Re-transcribe now" control at the foot of this block is
 * the web half of MainActivity's `MediaWithTranscript` -> `TranscribeNowButton`, which has shipped on
 * the phone since the feature landed. Until it did, a researcher could re-run a transcript standing
 * in a workshop and not at a desk: the identical account, the identical file, and one client simply
 * had no way to ask. The labels are Android's, verbatim, because the same person moves between the
 * two apps mid-workshop.
 *
 * `POST /media/{id}/transcribe-now` is `require_admin` AND 400s anything that is not AUDIO, so the
 * button renders only when both hold — never a control that exists to be refused.
 */
export function TranscriptBlock({ media, onUpdated }: { media: MediaFile; onUpdated?: (updated: MediaFile) => void }) {
  const { user } = useAuth();
  // The transcript is hoisted into local state (Android: `remember(media.id)`) so a fresh run
  // replaces what is on screen immediately, instead of waiting for the parent's next list fetch.
  const [live, setLive] = useState<MediaFile | null>(null);
  const [running, setRunning] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  useEffect(() => {
    setLive(null);
    setFailure(null);
  }, [media.id]);

  const current = live ?? media;
  const status = (current.transcriptStatus ?? "").toUpperCase();
  const isAudio = current.mediaType === "AUDIO" || (current.mimeType ?? "").toLowerCase().startsWith("audio/");
  const hasText = !!current.transcriptText && current.transcriptText.trim().length > 0;

  if (!isAudio && !status && !hasText) return null;

  // Inline playback above whatever transcript state renders below.
  const player = isAudio && current.url ? <AudioPlayer src={current.url} className="mt-2" /> : null;

  async function transcribeNow() {
    setRunning(true);
    setFailure(null);
    try {
      const updated = await transcribeMediaNow(media.id);
      setLive(updated);
      onUpdated?.(updated);
    } catch (err) {
      // Android reverts to the previous status and says nothing, which leaves an admin pressing a
      // button that appears to do nothing. Name the failure instead — this whole track exists
      // because a transcript that did not happen said nothing about why.
      setFailure(err instanceof Error ? err.message : "Transcribing now failed. Try again, or check the AI key on the settings page.");
    } finally {
      setRunning(false);
    }
  }

  /**
   * Admin-only, audio-only re-run. `hasText` picks the label exactly as Android does: with a
   * transcript already on screen this REPLACES it, and a control that said "Transcribe now" over
   * existing text would not be saying so.
   */
  // `mediaType === "AUDIO"` EXACTLY, not the mime-sniffing `isAudio` the player uses: the route tests
  // the stored mediaType column and 400s everything else, so a DOCUMENT row carrying an `audio/*`
  // mime would render a button that can only be refused.
  const control =
    isAdmin(user) && current.mediaType === "AUDIO" ? (
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <button type="button" className="field-button-secondary" onClick={transcribeNow} disabled={running}>
          {running ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <AudioLines className="h-4 w-4" aria-hidden />}
          {running ? "Transcribing…" : hasText ? "Re-transcribe now" : "Transcribe now"}
        </button>
        <span className="text-xs text-ink-500">Runs immediately, bypassing the queue and the off-peak window.</span>
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
        <div className="mt-2 rounded-md border border-line-200 bg-field-50 p-3">
          <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Transcript</div>
          <Markdown text={current.transcriptText ?? ""} />
        </div>
        {control}
        {failureNote}
      </>
    );
  }

  if (PROCESSING.has(status) || (isAudio && !status)) {
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
