/**
 * The contract between a record form and whatever is HOSTING it.
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 * `ArtisanForm`, `ProductForm`, `ToolForm` and `ProcessForm` are each mounted in two places: on
 * their own full-page route, and inside `InlineRecordDialog` over a half-filled design-workshop
 * stage. Every one of them grew `onCreated` for the second host and kept `router.back()` /
 * `router.push()` for the first — and the four callbacks that make the two hosts behave differently
 * were then invented four times, one form at a time, with three of the four missing at least one.
 * Naming the whole contract in one place is what stops the fifth form repeating that.
 *
 * ── THE RULE ──────────────────────────────────────────────────────────────────────────────────
 * A form hosted in a dialog MUST NOT NAVIGATE. Not on save, not on cancel, not on "open the record
 * that already holds this Aadhaar number". The dialog is not a route, so `router.back()` pops the
 * real history entry and the 22-stage record the designer was standing in disappears — from the
 * one control (Cancel) that is the most natural way to back out of a modal.
 */

import type { ArtisanIdentityMatch } from "@/lib/types";

/**
 * What the picker that opened the dialog already knows about the record being created.
 *
 * ── THE DEFECT THIS EXISTS FOR ────────────────────────────────────────────────────────────────
 * The full-page routes seed these same boxes from the query string (`/products/new?artisanId=…`).
 * A dialog has no query string, so the only thing that filled them was `useCarryContext` — the
 * LAST artisan this designer documented anywhere, which is not the artisan on the row they pressed
 * "Create a new product" from. `artisanId` is optional on save while `artisanName` is a required
 * free-text box, so the product saved happily filed under nobody. The server then narrows the
 * picker on exactly that column, so the record was invisible in the control that made it and
 * `describeCreated` could not describe it either — two blank required boxes, an amber panel, and a
 * stage that 422s on submit, seconds after the designer created the record holding both answers.
 *
 * ── EVERY SEEDED VALUE IS VISIBLE AND EDITABLE ────────────────────────────────────────────────
 * Nothing here is written into a hidden input. A seed lands in the same control a designer would
 * have used, showing the same name, and they can change it before saving. Android's inline record
 * host refuses to assert a parent for precisely this reason — "asserting a parent this picker never
 * saw the form choose would be a claim about whose product it is" — and a seed is only allowed to
 * be a DEFAULT they can see, never a claim made behind the form.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 * No identity number of any kind. A seed is copied from a stage row and from the workshop header,
 * and both are readable by everyone who can open the workshop; `sanitizeCarryContext` refuses the
 * same fields for the same reason. Nothing regulated may travel this way.
 */
export type InlineHostSeed = {
  /**
   * The artisan the row cascades from, when the picker's `refFilterBy` field really does hold an
   * `Artisan` id.
   *
   * NOT SET FOR A ROSTER CASCADE. At stage 13 the same-named `artisanRef` holds a `DwParticipant`
   * entry id and the SERVER follows it back to the artisan (`_artisan_id_behind`); a browser
   * cannot, and filing a product under a participant-entry id would be worse than filing it under
   * nobody. `StageReferenceSelect` reads the filter field's own `refModel` to tell the two apart.
   */
  artisanId?: string;
  /** The artisan's name as the row already shows it, so the required free-text box is not blank. */
  artisanName?: string;
  /** The `Workshop` this design workshop is linked to — see {@link InlineHostSeed} on scope. */
  workshopId?: string;
};

/** True when the seed has anything at all to say. Callers use it to decide whether to narrow `applies`. */
export function seedHasArtisan(seed: InlineHostSeed | undefined): boolean {
  return Boolean(seed?.artisanId || seed?.artisanName);
}

/**
 * The four callbacks a dialog host supplies, and what each of them replaces.
 *
 * Every one is OPTIONAL: absent means "no host, behave like the full-page route", which is exactly
 * how these forms behaved before the dialog existed and must go on behaving on their own routes.
 */
export type InlineRecordHostProps<TRecord> = {
  /**
   * The saved record. Replaces `router.push("/products")` and the form's own "saved" panel: the
   * caller selects the record in the picker and hydrates the row from it.
   */
  onCreated?: (record: TRecord) => void;
  /**
   * Back out without saving. Replaces `router.back()` — the defect being that a dialog is not a
   * route, so Cancel navigated the designer out of the stage they were standing in.
   *
   * The dirty prompt stays in front of it: closing the dialog still discards typing, so the
   * "Unsaved changes" question is as load-bearing in a dialog as it is on a page. This is only what
   * "Discard" DOES once the question has been answered.
   */
  onCancel?: () => void;
  /**
   * The save was banked in the offline outbox instead of sent. There is no record and no id.
   *
   * ── WHY THIS IS NOT `onCreated` WITH A PLACEHOLDER ────────────────────────────────────────
   * A REF field must hold a real server id: the report's `ReferencedRecord` join key,
   * `hydrate_entries`' lookup and `canonical_divergence` all resolve on it, and a client-invented
   * id would render for ever as a reference to a deleted record. So the row is deliberately left
   * unlinked, and the host says so in words rather than pretending.
   *
   * ── WHY THE FORMS CANNOT JUST STAY SILENT ─────────────────────────────────────────────────
   * On their own pages they can: `OutboxBanner` sits at the top of the protected layout, names the
   * entry and says where it lives, and the queued branch scrolls up to it. Inside a dialog that
   * banner is behind `FieldDialog`'s overlay on a body whose scroll `FieldDialog` has locked, so
   * both the banner and the scroll are unreachable. The button flipped back from "Saving…" to
   * "Save artisan" and nothing else changed — indistinguishable from a save that failed, which is
   * how a designer banks three copies of one artisan in the outbox.
   */
  onQueued?: () => void;
};

/**
 * The artisan who already holds the identity number, handed back instead of navigated to.
 *
 * `DuplicateArtisanDialog`'s "Open the existing record" did `router.push('/artisans/{id}/edit')`,
 * and the comment beside it reasons entirely from the page host ("Leaving for the other record
 * discards this one either way"). Inside the inline dialog the duplicate is the COMMON case — the
 * designer reached for "Create a new artisan" precisely because the picker's search did not show
 * the person in front of them — and the outcome the prompt exists to surface cost them their place.
 *
 * ONLY THE ID AND THE NAME CROSS. The conflict payload also carries `maskedValue`, and a masked
 * Aadhaar/Pehchan string must never be written onto a stage entry. The name is used as a SEARCH
 * TERM so the picker can ask the server to describe the record; every value that lands on the row
 * comes from that server payload and none from here.
 */
export type UseExistingArtisan = (artisan: ArtisanIdentityMatch) => void;
