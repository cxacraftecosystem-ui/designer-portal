"use client";

import { Download, RefreshCw } from "lucide-react";

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
      className={MEDIA_ACTION_CLASS}
    >
      {icon}
      {label}
    </button>
  );
}

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
