"use client";

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

export function useImageDimensions(source: string): Dimensions | undefined {
  useSyncExternalStore(
    subscribe,
    () => version,
    () => 0
  );
  return known.get(source);
}

const CONCURRENCY = 4;

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
