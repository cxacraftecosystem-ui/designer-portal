"use client";

import { Download, RefreshCw } from "lucide-react";

/**
 * The action buttons on a media card, tile or row — Retry and Download — drawn ONE way everywhere.
 * Android's twin is `MediaActionButton` in `ui/MediaDownloadUi.kt`.
 *
 * WHY THIS EXISTS (owner, 2026-10-17: "the buttons for retry and download on media card are not
 * clearly visible"). Both used to be 2px-tall text-xs chips — a bordered word on a white tile, easy
 * to miss and hard to hit with a thumb. Now each is a real TONAL button: icon AND the word, a purple-50
 * ground with purple-800 ink (8.9:1) and a purple-300 border, flipping to purple-950/40 with
 * purple-100 ink in the dark theme (well over AA); 44px tall on a phone, 36px from `sm` up. They wrap
 * onto their own line rather than squeezing a status label, so they never overlap another control.
 *
 * A click never reaches the tile under it — tiles open a preview on click.
 */
export function MediaActionButton({
  label,
  icon,
  onClick,
  disabled = false,
  accessibleLabel,
  title
}: {
  label: string;
  icon: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  /** The full name for a screen reader — "Download interview-3.webm". */
  accessibleLabel?: string;
  title?: string;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={(event) => {
        event.stopPropagation();
        if (!disabled) onClick();
      }}
      aria-label={accessibleLabel}
      title={title}
      className="inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-md border border-purple-300 bg-purple-50 px-3 text-sm font-semibold text-purple-800 transition enabled:hover:border-purple-400 enabled:hover:bg-purple-100 disabled:cursor-not-allowed disabled:border-line-200 disabled:bg-surface-50 disabled:text-ink-500 sm:min-h-9 dark:border-purple-700 dark:bg-purple-950/40 dark:text-purple-100 dark:enabled:hover:bg-purple-900/60"
    >
      {icon}
      {label}
    </button>
  );
}

/** "Retry" on a failed upload. */
export function RetryMediaButton({ name, onRetry, disabled }: { name?: string; onRetry: () => void; disabled?: boolean }) {
  return (
    <MediaActionButton
      label="Retry"
      icon={<RefreshCw className="h-4 w-4" aria-hidden />}
      onClick={onRetry}
      disabled={disabled}
      accessibleLabel={name ? `Retry ${name}` : undefined}
    />
  );
}

export const DOWNLOAD_ICON = <Download className="h-4 w-4" aria-hidden />;
