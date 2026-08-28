/**
 * WHERE AN UNFILED RECORD OPENS, and the two calls its card's actions make.
 *
 * WHY THIS IS A MODULE AND NOT SIX TERNARIES IN THE PANEL. §13.0 of the frontend contract is about
 * one bug that shipped in six places at once: a link that offers to edit a record and returns the
 * bare LIST route, which renders that page's inline form in CREATE mode — so "open this record"
 * landed on a blank form, the record was nowhere on screen, and filling the form in produced a
 * SECOND record. Every one of the six had independently decided the id was not carryable. The cure
 * there was `RECORD_HREF` / `editHref` — one table per surface — and this is the same table for the
 * types this report deals in, which are not the same six.
 *
 * THREE SHAPES, NOT TWO. §13.0 names two (a real `/{type}/{id}/edit` route, or an inline form on the
 * list page reached with `?edit={id}`). This report also covers two types that have NEITHER, and
 * pretending otherwise is exactly the failure above wearing a politer face: `?edit=` on a page with
 * no reader for it is a query parameter that lands on a blank create form just the same. So a
 * destination says which shape it is, and a card whose record cannot be opened SAYS SO rather than
 * offering a button that goes somewhere else.
 *
 * THE TWO THAT HAVE NO PER-RECORD SURFACE, re-checked 2026-08-28 rather than asserted:
 *
 *   $ ls "frontend/app/(protected)/questionnaire"      → consolidated/  page.tsx
 *   $ grep -n "useEditDeepLink\|searchParams.get" "frontend/app/(protected)/questionnaire/page.tsx"
 *       102:  const searchParams = useSearchParams();
 *       108:  ... searchParams.get("artisanId") ...        ← the ONLY parameter it reads
 *   $ ls "frontend/app/(protected)/media"              → page.tsx
 *   $ grep -n "useSearchParams\|useEditDeepLink" "frontend/app/(protected)/media/page.tsx"
 *       (no matches)
 *
 * `components/map/types.ts` reached the same conclusion about media independently — "Media genuinely
 * has no per-record route, so it alone still lands on its list page" — and `/data`'s `editHref` maps
 * `questionnaire` to a bare `/questionnaire` for the same reason. Re-run the two greps before
 * "fixing" either entry below: the day one of those pages grows a deep link, this table is one line.
 */

import { apiFetch } from "@/lib/api";

/** One record type's opening destination, and how honest a destination it is. */
export type UnfiledDestination = {
  /** Where the button goes. */
  href: (id: string) => string;
  /**
   * True when that href opens THIS record. False when the type has no per-record surface at all and
   * the href can only reach the list the record lives on — in which case `note` must be printed.
   */
  opensTheRecord: boolean;
  /** The button's own words. */
  label: string;
  /** Printed beside the button whenever `opensTheRecord` is false. Never leave the difference unsaid. */
  note?: string;
};

/**
 * Keyed by the `bucket` the server sends (`services/workshop_inference.BUCKETS`), never by a noun —
 * the nouns are the server's to word and it sends them alongside.
 */
export const UNFILED_DESTINATIONS: Record<string, UnfiledDestination> = {
  // The three types with a real edit route (§13.0, first shape).
  artisans: { href: (id) => `/artisans/${id}/edit`, opensTheRecord: true, label: "Open this artisan" },
  products: { href: (id) => `/products/${id}/edit`, opensTheRecord: true, label: "Open this product record" },
  tools: { href: (id) => `/tools/${id}/edit`, opensTheRecord: true, label: "Open this tool record" },
  // Inline form on the list page (§13.0, second shape) — the id travels as `?edit=`, read by
  // `useEditDeepLink` in `app/(protected)/processes/page.tsx:158`. A bare `/processes` here would be
  // the blank-create-form bug.
  processes: { href: (id) => `/processes?edit=${id}`, opensTheRecord: true, label: "Open this process record" },
  // The two with no per-record surface. `id` is deliberately unused — carrying it in the URL would
  // suggest the page reads it, and neither does.
  interviews: {
    href: () => "/questionnaire",
    opensTheRecord: false,
    label: "Open the Questionnaire list",
    note: "A questionnaire interview has no edit page of its own, so this opens the interview list. Find it there by its title."
  },
  media: {
    href: () => "/media",
    opensTheRecord: false,
    label: "Open the Media list",
    note: "A media file has no edit page of its own, so this opens the media list. Find it there by its filename."
  }
};

/** The destination for a bucket, or null when the server sent one this client has never heard of. */
export function destinationFor(bucket: string): UnfiledDestination | null {
  return UNFILED_DESTINATIONS[bucket] ?? null;
}

/** What `POST /workshops/unmapped/{bucket}/{id}` answers. */
export type FiledRecord = {
  bucket: string;
  id: string;
  /** The server's own singular noun for the type — "questionnaire interview", "media file". */
  noun: string;
  title: string;
  workshopId: string;
  workshopTitle: string;
};

/** What `DELETE /workshops/unmapped/{bucket}/{id}` answers. */
export type DiscardedRecord = {
  bucket: string;
  id: string;
  noun: string;
  title: string;
  /**
   * Media rows that pointed at the deleted record and are STILL THERE. Every MediaFile relation is
   * `onDelete: SetNull`, so a delete detaches attachments rather than removing them. Printed, always
   * — see `discardedNotice`.
   */
  mediaKept: number;
};

/**
 * File one record under one workshop.
 *
 * Both ids go through `encodeURIComponent`: they are CUIDs today and a path segment is not the place
 * to assume that stays true.
 */
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

/** Delete one unfiled record permanently. The server gates this on admin exactly as it gates the report. */
export async function discardUnfiledRecord(bucket: string, id: string): Promise<DiscardedRecord> {
  return apiFetch<DiscardedRecord>(
    `/workshops/unmapped/${encodeURIComponent(bucket)}/${encodeURIComponent(id)}`,
    { method: "DELETE" }
  );
}

/**
 * The sentence for a completed file, and the one for a completed delete.
 *
 * Here rather than at the two call sites so the dialog and the panel cannot word the same outcome
 * differently — the dialog closes on success and the panel is what the admin actually reads.
 */
export function filedNotice(result: FiledRecord): string {
  return `“${result.title}” is now filed under ${result.workshopTitle}. It appears under that workshop everywhere: the search box, the map, the data browser, the exports and the completion matrix.`;
}

export function discardedNotice(result: DiscardedRecord): string {
  const gone = `“${result.title}” has been deleted permanently.`;
  // ALWAYS SAID WHEN IT IS NOT ZERO. A delete that quietly left nine photographs in the repository
  // with nothing pointing at them is the same class of silence this whole panel exists to end.
  if (result.mediaKept <= 0) return gone;
  const files = result.mediaKept === 1 ? "1 media file" : `${result.mediaKept} media files`;
  return `${gone} ${files} that were attached to it were NOT deleted — they stay in the repository with nothing pointing at them, under Miscellaneous Media.`;
}
