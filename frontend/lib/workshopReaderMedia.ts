/**
 * A WORKSHOP'S OWN FILES AND OWN QUESTIONS, FOR THE PEOPLE WHO READ IT AND CHANGE NONE OF IT.
 *
 * Sweep item F5 (2026-10-10). Inspectors read a workshop on `/design-workshop-inspections/[id]` and
 * its directorate post holders on `/officers/monitored/[id]`. Both screens used to count a media
 * field's files and say the read did not carry them, and count the answers to the workshop's own
 * questions without their wording. Both now show them, read-only:
 *
 * - **Files** come from a second read on each surface — `GET /design-workshop-inspections/{id}/media`
 *   and `GET /design-workshop-oversight/assigned/{id}/media` — behind the same read-only loader as
 *   the workshop itself. Every URL on it is a short-lived signature; there is no permanent link, no
 *   storage key, and nothing on these screens can upload, replace, caption or delete a file.
 * - **Questions** arrive on the workshop read itself as `customSections`, the same shape
 *   `GET /design-workshops/{id}/custom-sections` serves the designer.
 *
 * Everything here that decides what to DRAW is a pure function, so `e2e/workshop-reader-media-unit.spec.ts`
 * can pin it without a browser.
 */

import { apiFetch } from "@/lib/api";
import { customAnswerText, customStageBlocks, type DwCustomDefinition, type DwCustomField } from "@/lib/customSections";
import { isFilled, listValue, type DwEntryData, type DwFieldStamp, type DwValue } from "@/lib/designWorkshops";
import type { MediaType } from "@/lib/types";

/** One file as a reader is given it — a whitelist of columns, and `url` only when it was signed. */
export type ReaderMediaFile = {
  id: string;
  originalFilename: string;
  mediaType: MediaType;
  mimeType: string;
  sizeBytes?: number | string;
  /** A signed link that expires in minutes. Absent when the file could not be signed. */
  url?: string | null;
  caption?: string | null;
  transcriptText?: string | null;
  recordedAt?: string | null;
  createdAt?: string | null;
};

export type ReaderMediaList = { items: ReaderMediaFile[]; truncated: boolean };

/** Which surface is reading — the two routes differ only in their prefix. */
export type ReaderSurface = "inspection" | "oversight";

export function readerMediaPath(surface: ReaderSurface, workshopId: string): string {
  const id = encodeURIComponent(workshopId);
  return surface === "inspection"
    ? `/design-workshop-inspections/${id}/media`
    : `/design-workshop-oversight/assigned/${id}/media`;
}

export function getReaderMedia(surface: ReaderSurface, workshopId: string) {
  return apiFetch<ReaderMediaList>(readerMediaPath(surface, workshopId));
}

/** How one file is drawn. Decided by the stored type first, the MIME type second. */
export type ReaderMediaKind = "image" | "audio" | "video" | "document";

export function readerMediaKind(file: Pick<ReaderMediaFile, "mediaType" | "mimeType">): ReaderMediaKind {
  const type = String(file.mediaType ?? "").toUpperCase();
  const mime = String(file.mimeType ?? "").toLowerCase();
  if (type === "IMAGE" || (type === "" && mime.startsWith("image/"))) return "image";
  if (type === "AUDIO" || mime.startsWith("audio/")) return "audio";
  if (type === "VIDEO" || mime.startsWith("video/")) return "video";
  return "document";
}

/**
 * The files one media field holds, resolved against the workshop's own files.
 *
 * `shown` keeps the field's own order. `elsewhere` counts ids that are not among this workshop's
 * files — a file filed against another record (an artisan's portrait, say) — which is a different
 * fact from "no file", so the screen says it in words rather than drawing an empty frame.
 */
export function resolveFieldMedia(
  value: DwValue | undefined,
  byId: ReadonlyMap<string, ReaderMediaFile>
): { shown: ReaderMediaFile[]; elsewhere: number } {
  const ids = listValue(value);
  const shown: ReaderMediaFile[] = [];
  let elsewhere = 0;
  for (const id of ids) {
    const file = byId.get(id);
    if (file) shown.push(file);
    else elsewhere += 1;
  }
  return { shown, elsewhere };
}

export function mediaIndex(list: ReaderMediaList | null | undefined): Map<string, ReaderMediaFile> {
  return new Map((list?.items ?? []).map((file) => [file.id, file]));
}

/** One answered custom question, with its wording and who wrote it. */
export type ReaderCustomAnswer = { field: DwCustomField; text: string; stamp?: DwFieldStamp | null };

/** One section's answered questions, in the order the designer asks them. */
export type ReaderCustomBlock = { title: string; retired: boolean; answers: ReaderCustomAnswer[] };

/**
 * The answers in one stage's `custom` bucket, put back beside their questions.
 *
 * Retired questions that hold an answer are KEPT — an answer given under a wording that was later
 * changed is still evidence — and flagged so the screen can say the question is no longer asked.
 * `unmatched` counts answers whose question this workshop no longer defines at all.
 */
export function customAnswersForStage(
  definition: DwCustomDefinition | null | undefined,
  stageKey: string,
  values: DwEntryData | undefined,
  stamps?: Record<string, DwFieldStamp>
): { blocks: ReaderCustomBlock[]; unmatched: number } {
  const bucket = values ?? {};
  const known = new Set<string>();
  const blocks: ReaderCustomBlock[] = [];
  for (const block of customStageBlocks(definition, stageKey)) {
    const answers: ReaderCustomAnswer[] = [];
    for (const field of block.fields) {
      known.add(field.key);
      const value = bucket[field.key];
      if (!isFilled(value)) continue;
      const text = customAnswerText(field, value).trim();
      if (!text) continue;
      answers.push({ field, text, stamp: stamps?.[field.key] ?? null });
    }
    if (answers.length) blocks.push({ title: block.section.title, retired: block.section.retired, answers });
  }
  const unmatched = Object.entries(bucket).filter(([key, value]) => !known.has(key) && isFilled(value)).length;
  return { blocks, unmatched };
}

/** A file's size the way a person reads one, or "" when it is not known. */
export function readableSize(bytes: number | string | null | undefined): string {
  const n = typeof bytes === "string" ? Number(bytes) : bytes;
  if (typeof n !== "number" || !Number.isFinite(n) || n <= 0) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
