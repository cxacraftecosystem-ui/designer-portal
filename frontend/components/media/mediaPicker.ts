import { RENDER_CAP, type SelectOption } from "@/components/ui/selectFilter";
import { formatDateTime } from "@/lib/format";
import type { MediaFile, MediaType } from "@/lib/types";

export const MEDIA_PICKER_PAGE_SIZE = RENDER_CAP;

export const MEDIA_SEARCH_DEBOUNCE_MS = 300;

export function mediaPickerTypeFilter(allowed: MediaType[] | undefined): MediaType | null {
  if (!allowed || allowed.length !== 1) return null;
  return allowed[0];
}

export function mediaPickerNoun(typeFilter: MediaType | null): { singular: string; plural: string } {
  switch (typeFilter) {
    case "IMAGE":
      return { singular: "photograph", plural: "photographs" };
    case "VIDEO":
      return { singular: "video", plural: "videos" };
    case "AUDIO":
      return { singular: "audio recording", plural: "audio recordings" };
    case "PDF":
      return { singular: "PDF", plural: "PDFs" };
    case "DOCUMENT":
      return { singular: "document", plural: "documents" };
    default:
      return { singular: "file", plural: "files" };
  }
}

export function mediaPickerOptions({
  rows,
  attachedIds
}: {
  rows: MediaFile[];
  attachedIds: string[];
}): SelectOption[] {
  const attached = new Set(attachedIds);
  return rows.map((row) => {
    const hints = [row.mediaType.toLowerCase(), formatDateTime(row.createdAt)];
    if (row.uploadedBy?.name) hints.push(row.uploadedBy.name);
    if (!row.url) hints.push("stored, but this account may not open the file itself");
    if (attached.has(row.id)) hints.push("already attached");
    return {
      value: row.id,
      label: row.caption?.trim() || row.originalFilename,
      hint: hints.join(" · "),
      disabled: attached.has(row.id)
    };
  });
}

export function mediaPickerNotice({
  loading,
  problem,
  query,
  shown,
  total,
  typeFilter
}: {
  loading: boolean;
  problem: string | null;
  query: string;
  shown: number;
  total: number;
  typeFilter: MediaType | null;
}): string {
  const noun = mediaPickerNoun(typeFilter);
  if (problem) return problem;
  if (loading) {
    return shown === 0
      ? `Searching the repository for ${noun.plural}…`
      : `Searching the repository… the ${shown} ${shown === 1 ? noun.singular : noun.plural} listed below ` +
          `${shown === 1 ? "is" : "are"} the answer to the previous search.`;
  }
  if (total === 0) {
    return query.trim()
      ? `No ${noun.singular} in the repository matches “${query.trim()}”. This search covers every ${noun.singular} you may read.`
      :
        `The repository holds no ${noun.singular} you may read yet.`;
  }
  if (shown < total) {
    return (
      `Showing the ${shown} most recently uploaded of ${total} ${noun.plural}. ` +
      `Type in the search box above to reach the rest — it searches every ` +
      `${noun.singular} you may read, not only the ${shown} listed here.`
    );
  }
  return total === 1
    ? `The one ${noun.singular} you may read is listed.`
    : `All ${total} ${noun.plural} you may read are listed.`;
}

export function repositoryEntitlementNotice(rows: MediaFile[]): string | null {
  const withheld = rows.filter((row) => !row.url).length;
  if (!withheld) return null;
  return (
    `${withheld} of the ${rows.length} listed ${withheld === 1 ? "is" : "are"} stored, but this account may not ` +
    `open the ${withheld === 1 ? "file" : "files"} ${withheld === 1 ? "itself" : "themselves"}. ` +
    `${withheld === 1 ? "It" : "They"} can still be attached; whether someone may open a file is checked ` +
    `each time it is opened.`
  );
}

export function capCeilingClause({
  label,
  declaredCap,
  accounted
}: {
  label: string;
  declaredCap: number | null;
  accounted: number;
}): string {
  if (declaredCap === null) return `${label} is full`;
  return (
    `${label} holds at most ${declaredCap} file${declaredCap === 1 ? "" : "s"}, and ` +
    `${accounted} ${accounted === 1 ? "is" : "are"} accounted for`
  );
}

export function acceptRepositoryPicks<T>({ picked, room }: { picked: T[]; room: number }): {
  attach: T[];
  refused: T[];
} {
  const capacity = Math.max(0, room);
  if (picked.length <= capacity) return { attach: picked, refused: [] };
  return { attach: picked.slice(0, capacity), refused: picked.slice(capacity) };
}

export function repositoryRefusalSentence({
  label,
  declaredCap,
  accounted,
  refusedNames
}: {
  label: string;
  declaredCap: number | null;
  accounted: number;
  refusedNames: string[];
}): string | null {
  if (!refusedNames.length) return null;
  const one = refusedNames.length === 1;
  return (
    `${capCeilingClause({ label, declaredCap, accounted })}. Not attached: ${refusedNames.join(", ")}. ` +
    `Remove something already attached, then press Attach again — ${one ? "it is" : "they are"} still ` +
    `ticked in the repository list.`
  );
}
