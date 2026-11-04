import { apiFetch } from "@/lib/api";
import { customAnswerText, customStageBlocks, type DwCustomDefinition, type DwCustomField } from "@/lib/customSections";
import { isFilled, listValue, type DwEntryData, type DwFieldStamp, type DwValue } from "@/lib/designWorkshops";
import type { MediaType } from "@/lib/types";

export type ReaderMediaFile = {
  id: string;
  originalFilename: string;
  mediaType: MediaType;
  mimeType: string;
  sizeBytes?: number | string;
  url?: string | null;
  caption?: string | null;
  transcriptText?: string | null;
  recordedAt?: string | null;
  createdAt?: string | null;
};

export type ReaderMediaList = { items: ReaderMediaFile[]; truncated: boolean };

export type ReaderSurface = "inspection" | "oversight" | "approval";

export function readerMediaPath(surface: ReaderSurface, workshopId: string): string {
  const id = encodeURIComponent(workshopId);
  if (surface === "approval") return `/design-workshop-approvals/${id}/media`;
  return surface === "inspection"
    ? `/design-workshop-inspections/${id}/media`
    : `/design-workshop-oversight/assigned/${id}/media`;
}

export function getReaderMedia(surface: ReaderSurface, workshopId: string) {
  return apiFetch<ReaderMediaList>(readerMediaPath(surface, workshopId));
}

export type ReaderMediaKind = "image" | "audio" | "video" | "document";

export function readerMediaKind(file: Pick<ReaderMediaFile, "mediaType" | "mimeType">): ReaderMediaKind {
  const type = String(file.mediaType ?? "").toUpperCase();
  const mime = String(file.mimeType ?? "").toLowerCase();
  if (type === "IMAGE" || (type === "" && mime.startsWith("image/"))) return "image";
  if (type === "AUDIO" || mime.startsWith("audio/")) return "audio";
  if (type === "VIDEO" || mime.startsWith("video/")) return "video";
  return "document";
}

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

export type ReaderCustomAnswer = { field: DwCustomField; text: string; stamp?: DwFieldStamp | null };

export type ReaderCustomBlock = { title: string; retired: boolean; answers: ReaderCustomAnswer[] };

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

export function readableSize(bytes: number | string | null | undefined): string {
  const n = typeof bytes === "string" ? Number(bytes) : bytes;
  if (typeof n !== "number" || !Number.isFinite(n) || n <= 0) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
