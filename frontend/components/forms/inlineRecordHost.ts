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
 *
 * ── THERE IS NOW A THIRD HOST, AND IT IS NOT A DIALOG ─────────────────────────────────────────
 * A design-workshop stage EMBEDS these forms in the page itself — the record page copied over as
 * it is, with the stage's own workshop-specific questions added at the bottom of the same list of
 * fields. That host is neither a route of its own nor a modal, and it needs one thing a dialog
 * never did: somewhere to put its extra fields INSIDE the form (`footerFields`). It is declared on
 * {@link InlineRecordHostProps} with the other four, for the reason this whole file exists: the
 * alternative is each of the four forms inventing its own spelling of it.
 *
 * ── THE FLAG THAT IS DELIBERATELY NOT HERE: "EMBEDDED, SO DO NOT PROMPT" ──────────────────────
 * That host was also given a boolean which held each form's `dirty` flag false, reasoning from the
 * stage page's decision 6 (`app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx`):
 * that page dropped its own "unsaved changes" prompt because every edit is written to a durable
 * IndexedDB draft, so nothing is lost by leaving. IT IS GONE, AND IT MUST NOT COME BACK ON THAT
 * ARGUMENT. The durability is a property of the STAGE's fields, which `lib/designWorkshopStore`
 * writes. It is not a property of a record form's fields: nothing in `ArtisanForm`, `ProductForm`,
 * `ToolForm` or `ProcessForm` writes to any store — the name, the Aadhaar digits, the picked files
 * and the captured fix live in React state and in uncontrolled DOM, and are read only at submit by
 * `new FormData(...)`. Leaving the stage with a half-typed artisan on it therefore destroys real
 * work, which is the case decision 6's own last sentence reserves against: the prompt "still has to
 * mean something an hour later on a form that genuinely is holding unsaved work". A host that wants
 * a silent embedded form has to make the form's fields durable FIRST. Suppressing the question does
 * not make the answer true, and a suppressed prompt is indistinguishable from one that was answered.
 *
 * ── THE SECOND THING DELIBERATELY NOT HERE: A HOST-SUPPLIED MEDIA STAGING OWNER ───────────────
 * `MediaCaptureField` takes an optional `stagingOwnerId`, and the obvious next move is a host prop
 * feeding it — the stage embed re-keys and remounts the whole form on every save and on Discard,
 * and a per-mount owner means `useEagerStaging` releases its claim, after which `lib/media` aborts
 * the transfer and DELETES the object already in storage two seconds later. It was measured and
 * NOT done, because on these four forms it is the wrong half of a pair:
 *
 * `useEagerStaging`'s own `ownerKey` note and `StagePendingMediaProvider`'s header both say it, in
 * the same words: "Shipping only the stable owner id would be worse than shipping neither: the
 * object would survive with nothing left in the browser able to link it, so it would leak rather
 * than be cleaned up." A stable owner keeps the OBJECT alive; it does nothing for the `File[]`. In
 * `ArtisanForm`, `ProductForm`, `ToolForm` and `ProcessForm` that array is `useState` INSIDE the
 * form, so the remount that releases the owner destroys the browser's last reference in the same
 * commit. Today the two die together, which is a cleanup. Pin the owner and the object outlives
 * every reference to it.
 *
 * WHAT WOULD HAVE TO COME FIRST looked like the same fix `FieldInput` got: hoist the file lists
 * above the remount — a host-owned store keyed by surface, which is what `StagePendingMediaProvider`
 * is — and only then name a stable owner for each. Two props, added together, or neither.
 *
 * ── MEASURED AGAIN 2026-08-22, AND THE ANSWER IS STILL NEITHER — FOR A SECOND REASON ──────────
 * The pair was re-examined to be built, and hoisting the file list turns out to be WRONG here
 * rather than merely insufficient, which is not true of the control `FieldInput` fixed. A stage
 * media control is unmounted by an accident of layout (its row was collapsed) and its files should
 * plainly survive that. These four forms are re-keyed by `StageRecordEmbed` for exactly two reasons
 * and both of them MEAN "throw the attachments away":
 *
 *  * AFTER A SAVE. `uploadMediaBatch` claims the staged objects with `takeStagedFor`, synchronously
 *    and before its first `await`, precisely "so a form that unmounts the instant it saves can never
 *    delete an object the save is about to link" — so the objects that mattered are already out of
 *    the store and out of the owner's reach before the remount happens. A hoisted list would instead
 *    carry the just-linked photographs into the fresh edit-mode mount, where the next Save would
 *    upload and link every one of them a second time.
 *  * ON CANCEL AND ON DISCARD. Emptying the form is the whole request. A list that survived it would
 *    be a Cancel that did not cancel.
 *
 * AND THE THIRD UNMOUNT — the picker above re-pointing the row, which re-keys the form over a
 * DIFFERENT record — must delete them too: those files were attached to the artisan the designer
 * abandoned, and carrying them onto the newly chosen one would attach a stranger's photographs to
 * her record. (That path asks no question first, which is a real gap, but it is a missing prompt in
 * `StageReferenceSelect` and not something an owner id could answer.)
 *
 * WHAT IS LEFT IS THE ACCIDENTAL UNMOUNT — a collection row collapsing under an open record form —
 * and that one is already guarded: all four forms mark themselves dirty when a file is attached
 * (`ProcessForm` counts `preFiles.length` and each step's `files.length` into its signature; the
 * other three call `markDirty` from `onFilesChange`), and `CollectionTable.toggleRow` asks
 * `useLeaveInterceptor` before it closes anything. So the designer is asked, and Discard means what
 * it says. NEITHER PROP, THEN — and the reason is no longer "the other half is missing" but "there
 * is nothing left for the pair to save".
 *
 * ── MEASURED A THIRD TIME 2026-08-22, AND THE PREMISES ARE NOW PINNED ─────────────────────────
 * A third pass was sent to build the store outright and re-derived the same answer, so the reason
 * this paragraph keeps being re-litigated is that the argument above rests on three facts that live
 * in files this contract does not own — any of which could be changed by someone who never reads it,
 * silently turning a measured refusal into a stale one:
 *
 *  1. All four forms count an attached file as unsaved work, so `CollectionTable.toggleRow`'s
 *     interceptor asks before the one unmount that is an accident rather than a decision.
 *  2. None of the four clears its `File[]` on the SAVE path, which is why hoisting the list above
 *     the remount would carry just-linked photographs into the next mount to be linked again.
 *  3. `uploadMediaBatch` and `uploadMediaFile` both claim the staged objects synchronously, before
 *     their first `await`, which is what makes the post-save remount a cleanup rather than a race.
 *
 * All three are now asserted in `e2e/inline-record-host-unit.spec.ts` (section 2b), one test each,
 * so a fourth reader is told by a red test whether the refusal still holds instead of having to
 * re-measure the tree to find out. They pin the FACTS and not the conclusion: building the store is
 * allowed the moment one of them has changed to make it safe.
 *
 * AND PREMISE 2 IS A PRICE, NOT A CLOSED DOOR — said plainly, because the paragraph above reads as
 * though the double-link were unfixable and it is only uncosted. A host-owned store would be keyed
 * by surface rather than global, and `StageRecordEmbed` already learns of every accepted write in one
 * place (`handleSaved`, which routes to `adoptCreated` or `adoptEdited`), so clearing that surface's
 * entry there is where the save-time clear would go. What premise 2 establishes is therefore "a store
 * with no save-time clear links the same photographs twice", not "no clear is possible". The pair
 * stays refused because it needs BOTH halves plus that third piece, in three files owned by three
 * groups, to buy back one already-guarded unmount — so the next pass should cost the whole change
 * rather than re-derive a door that was never locked.
 *
 * A CARD THAT UNMOUNTS UNDER A FORM THAT STAYS IS A DIFFERENT CASE and carries no such hazard:
 * there the file list IS hoisted (it is the form's own state), so a stable owner is safe rather
 * than half of a pair. `ProcessForm`'s pre-process card — mounted only while "Pre-processes
 * available" is ticked — is the one such place in the four, and it passes one. What that buys is
 * bounded: a stable owner cancels a pending release only if the card comes back inside
 * `RELEASE_GRACE_MS` (2 s), so it saves the misclick and not the change of mind ten seconds later.
 * The files survive either way, because they are the form's; only the upload is redone.
 */

import type { ReactNode } from "react";

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
 * What a host supplies, and what each of them replaces.
 *
 * Every one is OPTIONAL: absent means "no host, behave like the full-page route", which is exactly
 * how these forms behaved before the dialog existed and must go on behaving on their own routes.
 */
export type InlineRecordHostProps<TRecord> = {
  /**
   * Extra fields to render as the LAST thing inside the `<form>`, above the Cancel/Save row.
   *
   * ── IT IS NOT `FieldDialog`'s `footer`, WHICH IS WHY IT IS NOT CALLED THAT ────────────────
   * `FieldDialog` already has a `footer`, and it means the opposite thing: the dialog's ACTION ROW,
   * rendered as a right-aligned flex row of buttons under everything else. A dozen callers fill it
   * that way — `ConfirmDialog`, `UnsavedChangesDialog`, `AdoptLocalDraftDialog`, `OfflineDialog`,
   * `RecordCode` and the rest. `InlineRecordDialog` mounts these four forms INSIDE that very
   * component, so a slot spelled `footer` one component away from a `footer` holding buttons would
   * read as the same idea while being its opposite: this is fields BEFORE the buttons, not controls
   * after the content. The name carries the difference so the next reader does not have to know
   * both files. Put buttons in here and they land in the middle of the form, above its own two.
   *
   * ── WHY IT IS INSIDE THE FORM AND NOT ABOVE OR BELOW IT ───────────────────────────────────
   * The design-workshop stage asks a handful of questions the repository record does not hold —
   * what this artisan did at THIS workshop, which prototype this tool was used on — and the brief
   * for that screen is that the record page appears "exactly as it is" with those questions at the
   * bottom of the same continuous list of fields. Rendered outside the `<form>` they would be a
   * second panel with a second idea of where the form ends, and the one control that submits both
   * would be sitting in the middle of them.
   *
   * IT IS A SLOT AND NOT A FIELD LIST: this file has no opinion about what the host puts in it, and
   * the forms neither read it nor submit it. Whatever the host renders is the host's to collect —
   * these four forms build their payload from `new FormData(event.currentTarget)`, so a plain input
   * placed here is submitted with the record and a controlled one is not, and that is the host's
   * decision to make rather than something to be guessed here.
   */
  footerFields?: ReactNode;
  /**
   * The saved record. Replaces `router.push("/products")` and the form's own "saved" panel: the
   * caller selects the record in the picker and hydrates the row from it.
   *
   * ── A HOST MAY UNMOUNT THE FORM ON IT, AND A PARTLY-FAILED SAVE PAYS FOR THAT ─────────────
   * `InlineRecordDialog` calls this and then `onClose()`; `StageRecordEmbed` calls it and re-keys
   * the form. Either way, calling this is the last thing a form gets to do. That matters on the one
   * path where the record IS written and something else is not: media uploads run AFTER the write,
   * and the banner naming which files were lost is the only place those file names exist. The
   * handoff closes over that banner, so the file names are gone.
   *
   * THIS PARAGRAPH USED TO DESCRIBE A HANDOFF NOBODY IMPLEMENTED, and the correction is the
   * paragraph rather than the code. It said the four forms "set the banner, stay mounted, and call
   * this from a button beside it, once the message has been read". No form has ever had that
   * button: all four set the banner and call this inline, and the comment beside that line in each
   * of them argues for it at length. The argument is right and the contract was wrong —
   *
   *  * A record that exists over a row nobody linked is the worse loss, and it is the SILENT one.
   *    A missing photograph is named on screen and recoverable by re-opening the record, which is
   *    what the message says to do. An unlinked REF surfaces hours later as a 422 on stage submit,
   *    naming a required reference to a record the designer remembers creating — and the obvious
   *    next move is to create it a second time.
   *  * A form left standing in CREATE mode over a record that now exists is a duplicate waiting to
   *    be pressed. `ProcessForm` carries `committed` for exactly that, and `StageRecordEmbed`'s
   *    T-REMOUNT names the same hazard for the other three.
   *  * The button could not be waited for anyway. Nothing keeps the designer on the surface, and a
   *    handoff that only happens if they press something is a handoff that sometimes does not.
   *
   * WHAT THE HOSTS DO ABOUT THE LOST MESSAGE, since a silent loss would not be acceptable:
   * `StageRecordEmbed`'s own confirmation says to check the attachments, unconditionally — true
   * whether or not anything failed, and not a claim that something did. If the file names are ever
   * to survive, the fix is to report the failures ALONGSIDE the record — a second argument here, or
   * a companion callback — not to hold the record back behind a button.
   */
  onCreated?: (record: TRecord) => void;
  /**
   * Back out without saving. Replaces `router.back()` — the defect being that a dialog is not a
   * route, so Cancel navigated the designer out of the stage they were standing in.
   *
   * The dirty prompt stays in front of it: closing the dialog still discards typing, so the
   * "Unsaved changes" question is as load-bearing in a dialog as it is on a page. This is only what
   * "Discard" DOES once the question has been answered.
   *
   * ANY HOST THAT IS NOT A ROUTE MUST SUPPLY IT — a dialog, and equally the stage page that embeds
   * the form inline. `leave()` falls back to `router.back()` when it is absent, which is correct on
   * /artisans/new and destructive anywhere else, and the prompt is not a safety net for a host that
   * forgot: it only asks once something has been typed, so the first Cancel click on an untouched
   * form goes straight through to the fallback and pops the host's own history entry.
   */
  onCancel?: () => void;
  /**
   * "Discard", answered to a prompt the HOST'S OWN back control raised. Complete the exit it began.
   *
   * ── THE DEFECT THIS EXISTS FOR ────────────────────────────────────────────────────────────
   * There are two ways out of these forms and until this member there was one callback for both.
   * The form's own Cancel means "empty this form, I am staying"; the page header's back arrow means
   * "take me off this screen". Both raise the same `UnsavedChangesDialog`, so both used to end in
   * {@link onCancel} — and in a dialog host that reads correctly, because the dialog visibly closes
   * and the two exits genuinely are the same act.
   *
   * IN THE STAGE EMBED THEY ARE NOT. Its `onCancel` remounts the form in place, which is the only
   * thing that could clear boxes living in React state and uncontrolled DOM. So a designer pressed
   * Back, was asked, answered Discard — and lost everything they had typed while STAYING ON THE
   * PAGE, with a second press of Back still required to do the thing they had asked for. The one
   * answer that is supposed to mean "yes, throw it away, I am leaving" delivered the throwing away
   * and not the leaving.
   *
   * ── WHAT A HOST DOES WITH IT ──────────────────────────────────────────────────────────────
   * Whatever it can honestly do about its back control having been refused. `useLeaveGuard` does not
   * DELAY a navigation, it REFUSES one: the control abandons what it was doing, so nothing the host
   * can see is still in flight. The act itself is banked by `UnsavedChangesGuard` against the form
   * that blocked — which is why a host's job here is to clear what it hosts and SAY where the
   * designer is, and the finishing belongs to the form (see the paragraph below).
   *
   * ── ABSENT MEANS "THE TWO EXITS ARE THE SAME", WHICH IS TRUE OF BOTH OTHER HOSTS ──────────
   * The forms fall back to the ordinary exit (`onCancel`, or `router.back()` with no host at all),
   * so a page host and a dialog host need no change: on `/artisans/new` the back arrow's Discard
   * still pops history, and in `InlineRecordDialog` it still closes the dialog. Only a host that
   * can be LEFT WITHOUT BEING CLOSED has anything to add here — which today is the stage embed,
   * and it is the host the defect was reported on.
   *
   * ── THE EMBED SUPPLIES IT NOW, AND WHAT IT CAN DO WITH IT IS BOUNDED ──────────────────────
   * `StageRecordEmbed.handleDiscardAndLeave` is the one host implementation, and it clears the form
   * and says that the page did not move rather than moving it. A HOST STILL CANNOT MOVE IT: this
   * member asks a host to finish the exit its back control began, and no host can name that exit.
   * Four controls on a stage page can raise the prompt and they want four different things — the
   * header arrow's `router.back()` or its explicit `href`, "previous stage" / "next stage", a
   * collection row's own collapse (not a navigation at all), and `StageReferenceField` re-pointing
   * the row at another record — so a `router.back()` chosen here would be right for one and wrong,
   * or actively destructive, for the other three.
   *
   * WHAT CHANGED IS THAT THE ACT NOW SURVIVES THE REFUSAL. `components/UnsavedChangesGuard.tsx`
   * hands each interceptor the act it is blocking and HOLDS it against the form that blocked, and all
   * four of those callers now pass their own act in; the blocking form is given `completeLeave()` and
   * `abandonLeave()` and is the only party allowed to answer. So finishing the exit is no longer a
   * host's problem or this contract's — it is one line in the FORM, in the `else` branch beside
   * `leaveAfterDiscard()` (never beside `resetDirty()`, which runs for the form's own Cancel too).
   * Until those calls land, this member's implementation costs one extra press and says so out loud,
   * which is the half of the defect that was losing work.
   *
   * AND ITS SENTENCE SCOPES THE PROMISE TO ITS OWN ROW, which is not fussiness. A host implementation
   * knows only that the form IT hosts has gone clean; `UnsavedChangesProvider` walks the whole stack
   * and stops at the FIRST interceptor that blocks, one dialog at a time. Stage
   * TRADITIONAL_PROCESS_BASELINE mounts two of these forms at once, so "press again and it will go
   * through" is false there whenever both are dirty — the second press meets the other form's
   * prompt, correctly. Any future host writing a notice here owes the same qualification.
   */
  onDiscardAndLeave?: () => void;
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
 * The members above that say nothing about the record's TYPE, for the four forms to intersect into
 * their own props.
 *
 * A `Pick` off the real declaration rather than a second type with the same fields: the whole point
 * of this file is that the contract is written down once, and a copy would be four forms agreeing
 * with a copy instead of with the contract. Each form declares its own `initial`, `seed` and
 * per-record `onCreated` inline because those genuinely differ and carry per-form prose; these are
 * identical in all four and have nothing per-form to say. THIS IS WHERE THE NEXT HOST-WIDE MEMBER
 * GOES — the alternative, demonstrated by `onCancel` and `onQueued`, is the same paragraph written
 * out four times with three of the four eventually drifting or going missing. `onDiscardAndLeave`
 * was added here for exactly that reason and reached all four forms in one line.
 */
export type InlineRecordSurfaceProps = Pick<InlineRecordHostProps<unknown>, "footerFields" | "onDiscardAndLeave">;

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
