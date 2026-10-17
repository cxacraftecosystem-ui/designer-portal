"use client";

import { Download, RefreshCw } from "lucide-react";

/**
 * The action buttons on a media card, tile or row — Retry and Download — drawn ONE way everywhere.
 * Android's twin is `MediaActionButton` in `ui/MediaDownloadUi.kt`.
 *
 * WHY THIS EXISTS (owner, 2026-10-17: "the buttons for retry and download on media card are not
 * clearly visible"). Both used to be 2px-tall text-xs chips — a bordered word on a white tile, easy
 * to miss and hard to hit with a thumb. Now each is a real TONAL button: icon AND the word, a purple-50
 * ground with purple-800 ink (9.4:1) inside a purple-600 edge (5.2:1 on the card), flipping to a
 * purple-950 ground with purple-200 ink and a purple-500 edge in the dark theme; 44px tall on a phone
 * (any coarse pointer), 36px from `sm` up with a mouse. They wrap onto their own line rather than
 * squeezing a status label, so they never overlap another control.
 *
 * A click never reaches the tile under it — tiles open a preview on click.
 */
export const MEDIA_ACTION_CLASS =
  "inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-md border border-purple-600 bg-purple-50 px-3 text-sm font-semibold text-purple-800 transition enabled:hover:border-purple-700 enabled:hover:bg-purple-100 disabled:cursor-not-allowed disabled:border-ink-300 disabled:bg-surface-50 disabled:text-ink-500 sm:min-h-9 pointer-coarse:min-h-11 dark:border-purple-500 dark:bg-purple-950 dark:text-purple-200 dark:enabled:hover:border-purple-400 dark:enabled:hover:bg-purple-900";

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
      // The EDGE is what makes it a button on a white or surface-50 card: purple-600 is 5.2:1 / 4.9:1
      // there (purple-300 was 1.6:1, and the purple-50 ground alone is about 1.05:1). Dark: purple-500,
      // 5.0:1 on the dark card, around a solid purple-950 ground with purple-200 ink (12:1). Android's
      // `MediaActionColors` is the same pair, measured by `MediaActionColorsTest`.
      className={MEDIA_ACTION_CLASS}
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
