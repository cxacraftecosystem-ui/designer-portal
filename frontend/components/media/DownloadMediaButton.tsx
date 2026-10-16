"use client";

import { Download } from "lucide-react";

/**
 * The one Download control every upload surface draws — capture tiles, recorded clips, a stage's
 * on-this-device files, the offline outbox, a dictation being sent, and the uploaded chips — so it
 * reads and sits the same everywhere: the repo's download icon, the word "Download", a compact
 * secondary button.
 *
 * `unavailableReason` disables it and puts the sentence in a tooltip on a wrapper (a disabled
 * button fires no pointer events, so a `title` on the button itself never shows in Chromium). It is
 * meant for one case only: the bytes this device held are genuinely gone. A file still uploading is
 * never a reason — the local copy is what this button reads.
 */
export function DownloadMediaButton({
  name,
  onDownload,
  unavailableReason = null,
  className = ""
}: {
  /** The file's name, for the accessible label: "Download interview-3.webm". */
  name: string;
  onDownload: () => void;
  unavailableReason?: string | null;
  className?: string;
}) {
  const unavailable = Boolean(unavailableReason);
  return (
    <span className={`inline-flex shrink-0 ${className}`} title={unavailableReason ?? undefined}>
      <button
        type="button"
        disabled={unavailable}
        onClick={(event) => {
          // Tiles open a preview on click; a download must not also open the lightbox.
          event.stopPropagation();
          if (!unavailable) onDownload();
        }}
        aria-label={`Download ${name}`}
        title={unavailable ? undefined : `Download ${name}`}
        className="inline-flex items-center gap-1 rounded-sm border border-line-200 bg-card px-2 py-0.5 text-xs font-semibold text-purple-700 dark:text-purple-300 transition enabled:hover:border-purple-300 enabled:hover:bg-purple-50 disabled:cursor-not-allowed disabled:text-ink-500 disabled:opacity-60"
      >
        <Download className="h-3.5 w-3.5" aria-hidden />
        Download
      </button>
    </span>
  );
}
