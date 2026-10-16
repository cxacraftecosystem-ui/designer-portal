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
 * A RECORD OPENS ON ITS READ-ONLY PAGE (owner ruling, 2026-10-16) — `lib/recordView.ts` is the
 * table every surface now shares, so a button here and a row on the record's list go to one place.
 * Media and interviews gained such a page that day; until then this report could only send either
 * to its list, and `opensTheRecord: false` + `note` remain for the next type that has none — a card
 * whose record cannot be opened SAYS SO rather than offering a button that goes somewhere else.
 */

import { apiFetch } from "@/lib/api";
import { RECORD_VIEW_HREF } from "@/lib/recordView";

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
  // The record's READ-ONLY page (`lib/recordView.ts`, owner ruling 2026-10-16), whose Update button
  // keeps the id on its way to the form.
  artisans: { href: RECORD_VIEW_HREF.artisan, opensTheRecord: true, label: "Open this artisan" },
  products: { href: RECORD_VIEW_HREF.product, opensTheRecord: true, label: "Open this product record" },
  tools: { href: RECORD_VIEW_HREF.tool, opensTheRecord: true, label: "Open this tool record" },
  processes: { href: RECORD_VIEW_HREF.process, opensTheRecord: true, label: "Open this process record" },
  media: { href: RECORD_VIEW_HREF.media, opensTheRecord: true, label: "Open this file" },
  interviews: { href: RECORD_VIEW_HREF.interview, opensTheRecord: true, label: "Open this interview" }
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
 *
 * REFUSED WITH A 403 NAMING THE POST to an admin who inspects or supervises the design workshop the
 * record or file belongs to (2026-10-09) — a filing is a write of that workshop's content. The dialog
 * prints the sentence as the server wrote it (`readableError`), and so must any second caller.
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

/**
 * Delete one unfiled record permanently. The server gates this on admin exactly as it gates the report.
 *
 * AND ANSWERS 409, FOR EVERY ADMIN, ON A ROW A DESIGN WORKSHOP CLAIMS (2026-10-09): such a row is that
 * workshop's evidence, not rubbish nobody filed, and the sentence sends the admin to the record's or
 * the file's own screen. Printed as the server wrote it — it is the only place the way forward is said.
 */
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
  return `${gone} ${files} that were attached to it were kept, under Miscellaneous Media.`;
}

/**
 * The part of `POST /workshops/unmapped/map`'s answer that says what it LEFT ALONE for a post the admin
 * holds — per bucket, totalled, and in one sentence. All optional, because this client and the API
 * deploy separately; each count is null on the preview, which wrote nothing.
 */
export type HeldBackCounts = {
  buckets: ReadonlyArray<{ heldBack?: number | null }>;
  totals: { heldBack?: number | null };
  /** The server's own sentence for them (`workshop_inference.HELD_BACK_DETAIL`), when there are any. */
  heldBackDetail?: string | null;
};

/**
 * How many rows a bulk filing LEFT ALONE because the admin who pressed it inspects or supervises the
 * design workshop they belong to (2026-10-09: filing one there writes that workshop's content). The
 * server's total, else the buckets' counts added up, else null — an answer that reports nothing is
 * "not reported", which must never be printed as "none were left alone".
 */
export function heldBackOf(answer: HeldBackCounts): number | null {
  if (typeof answer.totals.heldBack === "number") return answer.totals.heldBack;
  const counts = answer.buckets
    .map((bucket) => bucket.heldBack)
    .filter((count): count is number => typeof count === "number");
  return counts.length ? counts.reduce((sum, count) => sum + count, 0) : null;
}

/**
 * This client's sentence for `count` such rows, or null for none — the server's `HELD_BACK_ONE_DETAIL`
 * for one row and `HELD_BACK_DETAIL` for several, word for word (both pinned by
 * `e2e/workshop-post-holder-readonly-unit.spec.ts`, which reads them off the server). Only ever the
 * FALLBACK: {@link heldBackSentence} prints the server's own when it sent one.
 */
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

/**
 * What the report says about the rows a bulk filing left alone, or null for none: THE SERVER'S
 * SENTENCE, verbatim, and this client's only where a server reported a count without one. Said beside
 * the count the same press filed, because the tiles still count those rows as "can be filed now" — for
 * the next admin, rightly — and a button that keeps offering to file them must not read as one that
 * did nothing.
 */
export function heldBackSentence(answer: HeldBackCounts): string | null {
  const said = answer.heldBackDetail?.trim();
  return said ? said : heldBackNotice(heldBackOf(answer));
}
