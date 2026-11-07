"use client";

import type { ImgHTMLAttributes, ReactNode } from "react";

import { useMediaUrl } from "@/components/hooks/useMediaUrl";
import type { RefreshableMedia } from "@/lib/mediaUrlRefresh";

export function RefreshingImage({
  media,
  alt,
  fallback,
  ...image
}: {
  media: RefreshableMedia;
  alt: string;
  fallback: ReactNode;
} & Omit<ImgHTMLAttributes<HTMLImageElement>, "src" | "onError" | "alt">) {
  const { src, onError, failed } = useMediaUrl(media);
  if (!src || failed) return <>{fallback}</>;
  // eslint-disable-next-line @next/next/no-img-element
  return <img {...image} src={src} alt={alt} onError={onError} />;
}
