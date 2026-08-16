/**
 * Keep the photograph of the identity card, or delete it — the designer's answer, never the app's.
 *
 * ── THE DEFECT THIS MODULE EXISTS TO CLOSE ────────────────────────────────────────────────────
 *
 * `IdentityCardCapture` on the artisan form never stores the picture: the `File` comes out of an
 * input, goes into one request body, and the input is cleared. That has always been true and its own
 * file header says so at length.
 *
 * `IdentityCardReader` on the design-workshop stage form is a different story, and it was never
 * written down anywhere. That reader is rendered UNDER a media field and reads the number off
 * photographs the designer has ALREADY attached there — and attaching there is the ordinary media
 * flow, which by the time the reader appears has presigned a PUT, put the bytes in S3, and created a
 * `MediaFile` row through `/media/complete`. So on that surface an unmasked identity document was
 * durably in the repository BEFORE anyone was offered a thought about it, and nothing ever asked.
 * The app decided. That is the hole; this module is the choice that fills it.
 *
 * ── WHY A PHOTOGRAPH IS A HOLE AND THE NUMBER IS NOT ──────────────────────────────────────────
 *
 * `artisan_identity.mask_aadhaar` and `records.mask_identity_number` mask the digits on every
 * exported surface — every list, every report, every download — and that machinery is sound. It
 * cannot touch a JPEG. A photograph of the card IS the number, unmasked, in the one form the masking
 * rule cannot reach. Keeping it is therefore not a storage preference; it is a decision to hold
 * regulated personal data, and it is made by a person whose name goes on it.
 *
 * ── THE TWO WORDS, AND WHY THERE IS NO THIRD ──────────────────────────────────────────────────
 *
 * DISCARD deletes the object and the row. Not `deletedAt` — `design_workshops.py` opens by stating
 * that nothing in it hard-deletes, and it is right about workshops for exactly the reason it gives
 * (a fortnight of fieldwork must survive a mis-tap) and inverted about this: a soft-deleted
 * photograph of somebody's Aadhaar card is a retained photograph of somebody's Aadhaar card with a
 * flag on it, and the designer who pressed Delete would be entitled to believe otherwise.
 *
 * STORE keeps it and writes who decided that, by name, and when, onto the file itself.
 *
 * The default is DISCARD everywhere, including for a value this client fails to send: the server's
 * `parse_retention` maps everything it does not recognise onto DISCARD, so a bug here loses a
 * photograph that can be retaken in ten seconds rather than retaining one nobody knows about.
 */

import { apiFetch } from "@/lib/api";

/** The whole vocabulary. Mirrors `identity_ocr.RETENTION_DECISIONS`, which pins the same pair. */
export type DwPhotoRetention = "DISCARD" | "STORE";

/**
 * The safe half, named rather than spelled at each call site.
 *
 * Every default in this feature — the radio the panel mounts with, the argument `readIdentityCard`
 * falls back to, the value the server assumes for a client that says nothing — is this constant or
 * the server's copy of it. A reader checking "is the default the safe one" has one place to look.
 */
export const DW_PHOTO_RETENTION_DEFAULT: DwPhotoRetention = "DISCARD";

export const DW_IDENTITY_RETENTION_PATH = "/design-workshops/ocr/identity/retention";

/** What the server says it did. `deleted` is a hard delete; there is no soft one on this route. */
export type DwPhotoRetentionResult = {
  mediaId?: string | null;
  decision?: DwPhotoRetention | string | null;
  deleted?: boolean | null;
  objectDeleted?: boolean | null;
  retention?: {
    decision?: string | null;
    decidedById?: string | null;
    decidedByName?: string | null;
    decidedAt?: string | null;
  } | null;
};

/**
 * Record the designer's decision about one stored identity photograph.
 *
 * NOT AUTO-RETRIED, unlike the presign/complete calls in `lib/media.ts`. Both outcomes here change
 * durable state — one deletes a file, the other writes an accountability record — and a request
 * this client repeated on its own would be a decision the designer made once being recorded twice,
 * or a 502 from a half-finished delete being turned into a second delete attempt with no one
 * watching. The panel shows the failure and the designer presses again, which is the retry.
 */
export function decideIdentityPhotograph(
  mediaId: string,
  decision: DwPhotoRetention
): Promise<DwPhotoRetentionResult> {
  return apiFetch<DwPhotoRetentionResult>(DW_IDENTITY_RETENTION_PATH, {
    method: "POST",
    body: JSON.stringify({ mediaId, decision })
  });
}

/**
 * The sentence a panel shows once the decision has landed.
 *
 * Here rather than in each component so the stage reader and the artisan form cannot come to say
 * two different things about the same outcome — and so the wording is testable without mounting a
 * React tree. It states what happened in the past tense and it does not hedge: "deleted" means the
 * file and the record of it are gone, which is what the route did.
 */
export function retentionOutcomeSentence(result: DwPhotoRetentionResult): string {
  if (result.deleted) {
    return "That photograph has been deleted — the file and the record of it are both gone. The number you confirmed is unaffected.";
  }
  const who = result.retention?.decidedByName?.trim();
  return who
    ? `That photograph is kept on this record, in ${who}'s name. It is a photograph of an identity document, so it is stored unmasked and anyone who can open this record can see it.`
    : "That photograph is kept on this record. It is a photograph of an identity document, so it is stored unmasked and anyone who can open this record can see it.";
}

/**
 * What the panel says while nobody has decided yet.
 *
 * Deliberately not reassuring. A designer who reads this and walks away has left an unmasked
 * identity document on the record, so the copy says that rather than "you can decide later" — the
 * screen is where the cost of not deciding is paid, and it is the only place it is visible.
 */
export const DW_PHOTO_UNDECIDED =
  "This photograph was uploaded to the record when you attached it, so it is stored right now. Choose what happens to it: an identity card is stored unmasked, unlike the number itself, which this repository masks everywhere it is shown.";
