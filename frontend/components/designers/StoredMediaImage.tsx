"use client";

/**
 * A stored media id, drawn as the image it points at.
 *
 * The profile stores a photograph and a signature as MEDIA IDS, never URLs — a pre-signed URL in a
 * database column is a link that expires, so a report generated three months later prints a broken
 * frame and nothing in the stored row says why. Resolving the id is therefore a fetch, and this is
 * the one place both the editor and the read-only view do it.
 *
 * TWO FAILURES ARE NORMAL HERE AND NEITHER MAY RENDER AS A BROKEN IMAGE.
 *
 * * The lookup can fail — the media row was deleted out from under the profile, or the caller is
 *   not entitled to read it. The id is left standing in the profile either way: dropping it would
 *   silently rewrite somebody's record on a page they only opened to look at.
 * * `MediaFile.url` is gated server-side at the encoder, so a row can arrive complete with its
 *   filename and type and NO url at all. That is an entitlement answer, not an error, and the two
 *   are worded differently below because they need different actions from whoever is reading.
 */

import { useEffect, useState } from "react";
import { ImageOff } from "lucide-react";

import { apiFetch } from "@/lib/api";
import type { MediaFile } from "@/lib/types";

type Resolution = { state: "loading" } | { state: "missing" } | { state: "ready"; file: MediaFile };

export function StoredMediaImage({
  mediaId,
  alt,
  /** Tailwind sizing for the frame. Kept at the call site: a signature is wider than a portrait. */
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
    apiFetch<MediaFile>(`/media/${mediaId}`)
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
        This file is no longer readable from here.
      </div>
    );
  }

  const { file } = resolution;
  if (!file.url) {
    return (
      <div className={`grid ${className} place-items-center gap-1 rounded-md border border-line-200 bg-surface-50 p-2 text-center text-xs leading-4 text-ink-500`}>
        <ImageOff className="h-4 w-4" aria-hidden />
        {file.originalFilename} is stored, but this account may not open the file itself.
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
