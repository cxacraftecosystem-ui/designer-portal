"use client";

/**
 * The pixel size of each photograph the preview draws, learned once per photograph and shared by
 * every copy of every block that shows it.
 *
 * WHY THIS EXISTS. `ImageRef.width_px`/`height_px` come from the media row's `extraMetadata`, and a
 * photograph uploaded from a browser carries none — measured 2026-10-16: every one of the forty
 * photographs on a seeded workshop arrived as `0 × 0`. The file writers do not mind, because they
 * read the real size off the bytes (`report_pdf._image_box`); the preview has to size a photograph's
 * BOX before its bytes arrive, or a picture loading moves every line and every page break below it.
 * So the size is read off the picture once — probed in document order, a few at a time, through the
 * same URL the sheet will draw (the browser cache serves both) — and every box that shows that
 * photograph takes it from here.
 *
 * Notifications are batched to one per animation frame, so forty photographs arriving together
 * cause one re-measure of the document, not forty.
 */

import { useSyncExternalStore } from "react";

export type Dimensions = { width: number; height: number };

const known = new Map<string, Dimensions>();
const listeners = new Set<() => void>();
const inFlight = new Set<string>();
let version = 0;
let scheduled = false;

function notify() {
  if (scheduled) return;
  scheduled = true;
  const flush = () => {
    scheduled = false;
    version += 1;
    for (const listener of listeners) listener();
  };
  if (typeof requestAnimationFrame === "function") requestAnimationFrame(flush);
  else setTimeout(flush, 0);
}

export function noteImageDimensions(source: string, width: number, height: number): void {
  if (!(width > 0 && height > 0)) return;
  const current = known.get(source);
  if (current && current.width === width && current.height === height) return;
  known.set(source, { width, height });
  notify();
}

export function imageDimensions(source: string): Dimensions | undefined {
  return known.get(source);
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Re-renders the caller when any photograph's size is learned; returns the size for `source`. */
export function useImageDimensions(source: string): Dimensions | undefined {
  useSyncExternalStore(
    subscribe,
    () => version,
    () => 0
  );
  return known.get(source);
}

const CONCURRENCY = 4;

/**
 * Learn the sizes of these photographs, in the order given (document order, so the first pages
 * settle first), at most {@link CONCURRENCY} at a time. Already known or already asked: skipped.
 */
export function probeImageDimensions(entries: Array<[source: string, url: string]>): void {
  if (typeof Image === "undefined") return;
  const queue = entries.filter(([source]) => !known.has(source) && !inFlight.has(source));
  queue.forEach(([source]) => inFlight.add(source));
  let next = 0;
  const pump = () => {
    if (next >= queue.length) return;
    const [source, url] = queue[next++];
    const image = new Image();
    image.decoding = "async";
    const done = () => {
      inFlight.delete(source);
      if (image.naturalWidth > 0) noteImageDimensions(source, image.naturalWidth, image.naturalHeight);
      pump();
    };
    image.onload = done;
    image.onerror = done;
    image.src = url;
  };
  for (let i = 0; i < CONCURRENCY; i += 1) pump();
}
