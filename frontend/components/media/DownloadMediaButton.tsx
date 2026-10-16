"use client";

import { DOWNLOAD_ICON, MediaActionButton } from "@/components/media/MediaActionButton";

/**
 * The one Download control every upload surface draws — capture tiles, recorded clips, a stage's
 * on-this-device files, the offline outbox, a dictation being sent, and the uploaded chips — so it
 * reads and sits the same everywhere: the repo's download icon and the word "Download" on the
 * tonal `MediaActionButton` (clearly visible, 44px on a phone — owner's report, 2026-10-17).
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
      <MediaActionButton
        label="Download"
        icon={DOWNLOAD_ICON}
        onClick={onDownload}
        disabled={unavailable}
        accessibleLabel={`Download ${name}`}
        title={unavailable ? undefined : `Download ${name}`}
      />
    </span>
  );
}
