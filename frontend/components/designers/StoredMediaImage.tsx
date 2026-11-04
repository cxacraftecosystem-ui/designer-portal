"use client";

import { useEffect, useState } from "react";
import { ImageOff } from "lucide-react";

import { fetchMediaRow } from "@/lib/mediaRows";
import type { MediaFile } from "@/lib/types";

type Resolution = { state: "loading" } | { state: "missing" } | { state: "ready"; file: MediaFile };

export function StoredMediaImage({
  mediaId,
  alt,
  className = "h-28 w-28"
}: {
  mediaId: string;
  alt: string;
  className?: string;
}) {
  const [resolution, setResolution] = useState<Resolution>({ state: "loading" });

  useEffect(() => {
    let cancelled = false;
    setResolution({ state: "loading" });
    fetchMediaRow(mediaId)
      .then((file) => {
        if (!cancelled) setResolution({ state: "ready", file });
      })
      .catch(() => {
        if (!cancelled) setResolution({ state: "missing" });
      });
    return () => {
      cancelled = true;
    };
  }, [mediaId]);

  if (resolution.state === "loading") {
    return (
      <div className={`grid ${className} place-items-center rounded-md border border-line-200 bg-surface-50 text-xs text-ink-500`}>
        Loading…
      </div>
    );
  }

  if (resolution.state === "missing") {
    return (
      <div className={`grid ${className} place-items-center gap-1 rounded-md border border-line-200 bg-surface-50 p-2 text-center text-xs leading-4 text-ink-500`}>
        <ImageOff className="h-4 w-4" aria-hidden />
        This file cannot be shown.
      </div>
    );
  }

  const { file } = resolution;
  if (!file.url) {
    return (
      <div className={`grid ${className} place-items-center gap-1 rounded-md border border-line-200 bg-surface-50 p-2 text-center text-xs leading-4 text-ink-500`}>
        <ImageOff className="h-4 w-4" aria-hidden />
        {file.originalFilename} is saved, but you do not have access to open it.
      </div>
    );
  }

  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={file.url}
      alt={alt}
      loading="lazy"
      className={`${className} rounded-md border border-line-200 bg-card object-contain`}
    />
  );
}
