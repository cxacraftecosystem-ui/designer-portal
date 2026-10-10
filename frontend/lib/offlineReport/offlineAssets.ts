"use client";

/**
 * The static files a report built on this device needs, and keeping them on the device.
 *
 * The .pdf written in the browser is set in Noto Sans, with a Noto face for each Indic script a
 * report can carry — the same families `report_pdf.py` binds on the server — and the map is drawn
 * over the state borders in `public/boundaries`. They are files under `public/`, served by the
 * application, so the service worker (`public/sw.js`) keeps them once fetched; {@link warmReportAssets}
 * fetches them while the report screen is open with a connection, so they are there when it is not.
 */

import type { Script } from "@/lib/offlineReport/model";

export const REPORT_FONT_BASE = "/report-fonts/";

/** The pdf font family for each script, and the files it is made of (regular and bold). */
export const SCRIPT_FONTS: Record<Exclude<Script, "LATIN" | "OTHER">, string> = {
  DEVANAGARI: "NotoSansDevanagari",
  BENGALI: "NotoSansBengali",
  ODIA: "NotoSansOriya",
  GUJARATI: "NotoSansGujarati",
  TAMIL: "NotoSansTamil",
  TELUGU: "NotoSansTelugu",
  KANNADA: "NotoSansKannada",
  MALAYALAM: "NotoSansMalayalam",
  GURMUKHI: "NotoSansGurmukhi"
};

export const LATIN_FONT_FILES = {
  normal: "NotoSans-Regular.ttf",
  bold: "NotoSans-Bold.ttf",
  italics: "NotoSans-Italic.ttf",
  bolditalics: "NotoSans-BoldItalic.ttf"
};

export function scriptFontFiles(family: string): { normal: string; bold: string } {
  return { normal: `${family}-Regular.ttf`, bold: `${family}-Bold.ttf` };
}

export function allReportAssetUrls(): string[] {
  const files = [
    ...Object.values(LATIN_FONT_FILES),
    ...Object.values(SCRIPT_FONTS).flatMap((family) => Object.values(scriptFontFiles(family)))
  ];
  return [...files.map((f) => `${REPORT_FONT_BASE}${f}`), "/boundaries/state-borders.txt", "/boundaries/manifest.json"];
}

/** Fetch every asset once, through the service worker, so it is cached. Best effort and silent. */
export async function warmReportAssets(): Promise<void> {
  if (typeof window === "undefined" || !("serviceWorker" in navigator) || !navigator.serviceWorker.controller) return;
  await Promise.allSettled(allReportAssetUrls().map((url) => fetch(url, { cache: "no-cache" })));
}

/** One asset's bytes, from the network or the service worker's cache. */
export async function fetchAsset(url: string): Promise<ArrayBuffer | null> {
  try {
    const response = await fetch(url);
    return response.ok ? await response.arrayBuffer() : null;
  } catch {
    return null;
  }
}
