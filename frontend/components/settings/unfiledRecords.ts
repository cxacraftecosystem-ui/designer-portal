import { apiFetch } from "@/lib/api";
import { RECORD_VIEW_HREF } from "@/lib/recordView";

export type UnfiledDestination = {
  href: (id: string) => string;
  opensTheRecord: boolean;
  label: string;
  note?: string;
};

export const UNFILED_DESTINATIONS: Record<string, UnfiledDestination> = {
  artisans: { href: RECORD_VIEW_HREF.artisan, opensTheRecord: true, label: "Open this artisan" },
  products: { href: RECORD_VIEW_HREF.product, opensTheRecord: true, label: "Open this product record" },
  tools: { href: RECORD_VIEW_HREF.tool, opensTheRecord: true, label: "Open this tool record" },
  processes: { href: RECORD_VIEW_HREF.process, opensTheRecord: true, label: "Open this process record" },
  media: { href: RECORD_VIEW_HREF.media, opensTheRecord: true, label: "Open this file" },
  interviews: { href: RECORD_VIEW_HREF.interview, opensTheRecord: true, label: "Open this interview" }
};

export function destinationFor(bucket: string): UnfiledDestination | null {
  return UNFILED_DESTINATIONS[bucket] ?? null;
}

export type FiledRecord = {
  bucket: string;
  id: string;
  noun: string;
  title: string;
  workshopId: string;
  workshopTitle: string;
};

export type DiscardedRecord = {
  bucket: string;
  id: string;
  noun: string;
  title: string;
  mediaKept: number;
};

export async function fileUnfiledRecord(
  bucket: string,
  id: string,
  workshopId: string
): Promise<FiledRecord> {
  return apiFetch<FiledRecord>(
    `/workshops/unmapped/${encodeURIComponent(bucket)}/${encodeURIComponent(id)}`,
    { method: "POST", body: JSON.stringify({ workshopId }) }
  );
}

export async function discardUnfiledRecord(bucket: string, id: string): Promise<DiscardedRecord> {
  return apiFetch<DiscardedRecord>(
    `/workshops/unmapped/${encodeURIComponent(bucket)}/${encodeURIComponent(id)}`,
    { method: "DELETE" }
  );
}

export function filedNotice(result: FiledRecord): string {
  return `“${result.title}” is now filed under ${result.workshopTitle}.`;
}

export function discardedNotice(result: DiscardedRecord): string {
  const gone = `“${result.title}” has been deleted permanently.`;
  if (result.mediaKept <= 0) return gone;
  const files = result.mediaKept === 1 ? "1 media file" : `${result.mediaKept} media files`;
  return `${gone} ${files} that were attached to it were kept, under Miscellaneous Media.`;
}

export type HeldBackCounts = {
  buckets: ReadonlyArray<{ heldBack?: number | null }>;
  totals: { heldBack?: number | null };
  heldBackDetail?: string | null;
};

export function heldBackOf(answer: HeldBackCounts): number | null {
  if (typeof answer.totals.heldBack === "number") return answer.totals.heldBack;
  const counts = answer.buckets
    .map((bucket) => bucket.heldBack)
    .filter((count): count is number => typeof count === "number");
  return counts.length ? counts.reduce((sum, count) => sum + count, 0) : null;
}

export function heldBackNotice(count: number | null): string | null {
  if (!count || count <= 0) return null;
  return count === 1
    ? "1 record belongs to a design workshop you inspect or supervise, so it was left as it was: whoever inspects " +
        "or supervises a workshop does not write it. It stays on this report for an administrator who holds no post " +
        "on that workshop."
    : `${count} records belong to a design workshop you inspect or supervise, so they were left as they were: ` +
        "whoever inspects or supervises a workshop does not write it. They stay on this report for an administrator " +
        "who holds no post on that workshop.";
}

export function heldBackSentence(answer: HeldBackCounts): string | null {
  const said = answer.heldBackDetail?.trim();
  return said ? said : heldBackNotice(heldBackOf(answer));
}
