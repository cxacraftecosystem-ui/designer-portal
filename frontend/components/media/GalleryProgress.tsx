"use client";

import { galleryProgress, type GalleryCounts } from "@/components/media/photoGate";

export function GalleryProgress({
  counts,
  floor,
  label,
  labelledBy,
  className = ""
}: {
  counts: GalleryCounts;
  floor: number;
  label: string;
  labelledBy?: string;
  className?: string;
}) {
  const progress = galleryProgress({ counts, floor });

  return (
    <div className={`grid gap-1.5 ${className}`}>
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-xs leading-5 text-ink-500">{label}</span>
        <span className="shrink-0 text-xs font-semibold tabular-nums text-ink-900">{progress.readout}</span>
      </div>
      <div
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={floor}
        aria-valuenow={progress.held}
        aria-valuetext={progress.words}
        aria-labelledby={labelledBy}
        className="progress-track h-2"
      >
        <div
          className="progress-fill transition-[width] duration-300 ease-out"
          style={{ width: `${progress.percent}%` }}
        />
      </div>
      <p className="text-xs leading-5 text-ink-500">{progress.words}</p>
    </div>
  );
}
