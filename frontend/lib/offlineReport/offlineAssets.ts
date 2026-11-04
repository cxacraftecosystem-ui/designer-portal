"use client";

import type { Script } from "@/lib/offlineReport/model";

export const REPORT_FONT_BASE = "/report-fonts/";

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

export async function warmReportAssets(): Promise<void> {
  if (typeof window === "undefined" || !("serviceWorker" in navigator) || !navigator.serviceWorker.controller) return;
  await Promise.allSettled(allReportAssetUrls().map((url) => fetch(url, { cache: "no-cache" })));
}

export async function fetchAsset(url: string): Promise<ArrayBuffer | null> {
  try {
    const response = await fetch(url);
    return response.ok ? await response.arrayBuffer() : null;
  } catch {
    return null;
  }
}
