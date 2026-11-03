"use client";

import { DOWNLOAD_ICON, MediaActionButton } from "@/components/media/MediaActionButton";

export function DownloadMediaButton({
  name,
  onDownload,
  unavailableReason = null,
  className = ""
}: {
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
