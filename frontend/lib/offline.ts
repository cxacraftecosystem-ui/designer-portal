"use client";

/**
 * The offline outbox — the web's port of the Android app's `OfflineOutbox`.
 *
 * A researcher in a village with no signal must be able to finish the interview they are in the
 * middle of. On Android they always could: a save with no connection goes into a local outbox and
 * drains when the network returns. On the web it used to fail at the Save button, and the only
 * honest thing the UI could do was warn them (see `components/dialogs/OfflineDialog`). This is the
 * outbox that warning was apologising for.
 *
 * WHAT IS STORED. One entry per attempted save: the record request (endpoint, method, JSON body)
 * and the files that were attached to it. Files go in as `File` objects — IndexedDB stores them by
 * structured clone, so the bytes, the name and the MIME type all survive a browser restart, which a
 * blob: URL or an in-memory array would not. This matters: the attachments are usually the part
 * that cannot be recreated, because the artisan has gone home.
 *
 * HOW IT DRAINS, AND HOW IT DIFFERS FROM ANDROID. `syncOutbox` replays entries oldest first: create
 * the record, then upload its media against the new id, then delete the entry. The Android outbox
 * stops at the first failure, which is right for a connection that dropped again — but wrong for a
 * request the server will never accept: one 422 at the head of the queue blocks every entry behind
 * it forever, and nothing tells the user why. So the failure is triaged — and the triage itself no
 * longer lives here. `lib/failureTriage.ts` holds ONE table of what every failure IS and what each
 * surface does about it, and this file reads it. What the drain does with each verdict:
 *
 *   - unreachable / transient        → stop the pass, keep everything queued, try again on the next
 *     (no connection, 5xx, 408, 429)   `online` event. Nothing is marked and nothing is lost.
 *   - credential-expired (401)       → stop the pass, mark NOTHING, ask for a sign-in. One expiry is
 *                                      one credential, not a queue full of refused records.
 *   - refused / permanent            → mark THAT entry with the reason, leave it in the outbox for
 *     (4xx; a 0-byte file)             the user to see and discard, and carry on to the next one.
 *                                      One bad record cannot strand the others.
 *   - schema-drift (422, unknown key) → recorded and shown like a refusal, but re-attempted by the
 *                                      next app run. Nobody entered anything wrong: this build and
 *                                      the server's disagree about the shape of the request, and
 *                                      what clears it is an update rather than a person. See
 *                                      `blocksRetry` below — it is what stops a refusal outliving
 *                                      the bug that caused it.
 *
 * THE SAME LINES GOVERN THE MEDIA LEG, and for a while they did not. The media catch below re-threw
 * only what `isUnreachable` recognised — which is a dropped connection and a 408, and nothing else
 * — so a 503 during a deploy window and a 429 from a rate limiter came out of the photograph upload
 * PERMANENTLY refused, while the identical status on the record request one screen up was "try again
 * later". One pass could hold both opinions about one server. That is the class the triage table
 * exists to close: both legs now read the same verdict, from the same function, unwrapped once.
 *
 * A REPLAY IS RESUMABLE, because "create then upload" is two steps and only the first is cheap to
 * repeat — repeating it makes a second record. So each step is written back to the entry the moment
 * it lands (`created`, `createdId`, `uploadedBatches`), and a pass that dies half way through picks
 * up at the media instead of starting the record again. Without that the outbox duplicated every
 * record whose media upload was interrupted, once per sync pass, for as long as the signal stayed
 * bad — the failure mode with the worst timing possible, since a bad signal is why the entry is here.
 *
 * NOTHING IS EVER DELETED BECAUSE THE SERVER SAID 409. This module used to read a 409 as "the create
 * already landed and we simply lost the response", and drop the entry and its files as sent. No
 * endpoint in this API means that: a 409 from /artisans is a clashing Aadhaar, from /crafts a craft
 * of that name, from /questionnaire/interviews the same artisan set already interviewed. So the one
 * answer that means "someone else's record collides with yours" was destroying the record AND the
 * photographs and reporting success. A 409 is now surfaced as a conflict for the researcher to
 * resolve, with everything kept. The lost-response case it was aiming at is covered properly by
 * `created` above, which knows rather than guesses.
 *
 * A FAILED MEDIA UPLOAD IS TRIAGED WHERE IT HAPPENS, not by the pass-level catch. `uploadMediaBatch`
 * escalates a batch in which nothing landed, and it does so with a `MediaBatchError` — which is not
 * an `ApiError`, so the only "is it worth retrying" test this file had said "yes, try again", the pass
 * broke as if the device were offline, and NO FAILURE WAS EVER RECORDED. The entry retried for ever and every entry behind it
 * never drained, under a banner saying "Still no connection" (false) with no Discard offered
 * (because Discard is gated on a failure record that was never written). It needed no server at all:
 * a 0-byte file is refused by `lib/media.ts` before a request is made. So the media leg now catches
 * its own failure, re-throws whatever is still worth retrying (a connection that never arrived, and
 * the statuses {@link underlyingIsTransient} names — which opens the batch escalation and asks about
 * the error the server actually raised), and otherwise records the files against the entry so the existing failure
 * branch writes it, the banner offers a decision, and the loop carries on. Same split, same reason,
 * as the sibling in `lib/designWorkshopStore.ts`, which keeps 408 and 429 on the retryable side too.
 *
 * A BATCH THE SERVER ONLY PARTLY REFUSED IS NOT A FINISHED BATCH. `uploadMediaBatch` throws only when
 * NOTHING landed, so a batch of four photographs in which the server refused one comes back
 * NORMALLY, with the refusal in `failed`. That return used to record the whole batch in
 * `uploadedBatches`, on the reasoning that a replay would duplicate the three that did land — true,
 * and it made the refused one unreachable. While the only exit from a marked entry was Discard that
 * merely froze it; the moment {@link retryOutboxEntry} existed it became a silent deletion, because
 * the next pass skips a recorded batch entirely, finds nothing outstanding, deletes the entry with
 * its files and reports the entry SENT. The photograph the artisan went home before we could retake
 * was gone, and the researcher was told the opposite. So a partly refused batch is NARROWED instead:
 * `BatchResult.uploadedByIndex` says which `File` landed (never the name — two handset photographs
 * are routinely both `IMG_0001.jpg`), the ones that did not are written back as the batch's files,
 * and the index stays out of `uploadedBatches`. A later pass re-sends exactly the set that never
 * landed, which cannot duplicate the set that did.
 *
 * ONE DEVICE DRAINS ONCE, ACROSS EVERY TAB. The module-level `syncing` promise is per-tab, and this
 * banner is mounted in the protected layout — so two open tabs both drained on the same `online`
 * event, both POSTed the same queued entry, and Workshop/ProductDocumentation/ToolDocumentation/
 * Process have no unique constraint to catch it: two government records for one queued save. The
 * pass is therefore held under a Web Lock, exactly as `syncDesignWorkshopDrafts` is, with the same
 * `ifAvailable` decline and the same fall-through where the API is absent. See {@link SYNC_LOCK}.
 *
 * A 401 IS THE CREDENTIAL, NOT THE ENTRY. A seven-day token expiring on a live tab used to mark
 * every entry the pass reached permanently refused — and nothing in this repository ever wrote an
 * outbox failure back to null, so "Sync now" disappeared and the only control left was the one that
 * DELETES the record and its photographs. Android has always treated 401 as a pass-level stop
 * (`WorkshopSync.isConnectionFailure`) and this does now too: the pass stops, nothing is marked, and
 * the banner asks for a sign-in. {@link retryOutboxEntry} is the other half — the first thing in this
 * repository that can clear an outbox failure, which also rescues the far more reachable captive
 * portal case.
 *
 * AND BOTH LEGS ARE NOW SILENT ABOUT IT. The create was given `redirectOn401: false` first, which
 * closed half of it: the media leg calls `uploadMediaBatch`, and `lib/media.ts` gave no caller any
 * way to pass that option down to its own `apiFetch`, so a pass RESUMING an entry whose record
 * already exists sent its first request from there and a token dying in that window still navigated
 * — off whatever screen the researcher was on, mid-edit. `uploadMediaBatch` now takes the same flag
 * and carries it through presign, multipart setup/complete/abort and `/media/complete`, and this
 * pass passes false on both legs. The signed-out case (another tab called `logout`, clearing the
 * token both tabs share) is closed separately, by refusing to start a pass with no token at all.
 *
 * A STORE THAT CANNOT BE READ IS NOT AN EMPTY STORE. See {@link OutboxStoreHealth}.
 */

import { ApiError, apiFetch, getToken } from "@/lib/api";
// `underlyingIsTransient` AND NOT `isTransient` FOR THE DRAIN. They are two readings of one table
// and differ only in whether a wrapper is opened first; the drain needs the opened one, because
// every media failure it meets arrives inside a `MediaBatchError` and a false here MARKS the item
// rather than discarding anything. `isTransient` keeps the as-thrown reading for the interactive
// callers, where a false throws captured bytes away — argued at length in `lib/failureTriage.ts`.
import {
  isCredentialExpiry,
  isTransient,
  isUnreachable,
  schemaRefusalError,
  underlyingIsTransient
} from "@/lib/failureTriage";
import { MediaBatchError, uploadMediaBatch, type BatchResult } from "@/lib/media";

const DB_NAME = "field-repo-outbox";
const DB_VERSION = 1;
const STORE = "entries";

/**
 * One media batch of a queued save: everything `uploadMediaBatch` will need on replay.
 *
 * A LIST of these, not one, because the forms do not attach media in a single lump: a product
 * queues its two measurement-grid photos (each with its own caption naming the dimension) beside
 * the general field media, and a tool adds its numbered process-stage captures on top. Flattening
 * them into one batch would put every file under one caption, and the caption is the only thing
 * that says which photo is the height grid.
 */
export type OutboxMediaBatch = {
  files: File[];
  linkedRecordType: string;
  caption?: string;
  location?: unknown;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: Record<string, unknown>;
  transcribeAudio?: boolean;
  /**
   * Link this batch to the Nth CHILD of the created record instead of the record itself — the
   * process form's per-step media, whose `processstep` ids do not exist until the server has made
   * them. Resolved on replay from the create response's `steps[]`, in the order they were sent.
   */
  stepIndex?: number;
};

export type OutboxEntry = {
  id?: number;
  /** Human label for the banner — "Artisan · Giriraj Prasad", not an endpoint. */
  label: string;
  createdAt: number;
  endpoint: string;
  method: "POST" | "PATCH";
  /** Serialised at queue time so a later schema change cannot alter what the user actually saved. */
  body: string;
  media: OutboxMediaBatch[];
  attempts: number;
  /** Set when the server rejected this permanently; the entry stays for the user to read and discard. */
  failure: string | null;
  /**
   * Set INSTEAD OF a plain permanent mark when the refusal was a client/server dialect mismatch:
   * holds the {@link APP_RUN_ID} that recorded it, so the next run tries again. See
   * {@link blocksRetry}. Optional because entries written before this existed simply have none, and
   * absence means "only a person can clear this" — the behaviour they already had.
   */
  skewRun?: string | null;
  /**
   * The key of the object in the RESPONSE that carries the saved row, when it is not the response.
   *
   * ── WHY THIS EXISTS: A QUEUE THAT REFUSED ITS OWN SUCCESSES ──────────────────────────────────
   *
   * The drain reads `saved.id` off the replay's answer and treats a 2xx carrying no readable id as
   * a captive portal — a PERMANENT failure with a sentence telling the researcher their work is
   * still on the device and may or may not have arrived. That test is right for every endpoint this
   * queue was written for, all of which answer with the created record at the top level.
   *
   * `POST /design-ratings` does not. It answers `{ "rating": {…}, "replayed": bool }`, because the
   * client cannot know whether it is creating, amending or replaying and the route is deliberately
   * one route for all three (see its docstring). So EVERY SUCCESSFUL REPLAY of a queued rating would
   * have been marked as a permanent captive-portal failure and shown to the designer as work that
   * never landed — which is exactly why `components/sketches/ratingsApi.ts` refused to queue a
   * rating at all, in a comment naming this field's absence as the thing that had to change first.
   * The cost of that refusal was the only value on the sketches surface with NO persistence path:
   * a score, an assessment and a suggested change, typed in a courtyard with no signal, refused out
   * loud and gone when the tab closed.
   *
   * NAMED BY THE QUEUEING CALLER RATHER THAN SNIFFED FROM THE ANSWER. The alternative — "if there
   * is no top-level id, look one level down" — would turn a captive portal's own 200 page into a
   * success the moment it happened to contain one nested `id`, on a queue whose whole discipline is
   * that an answer it cannot read is not a save. The caller knows the shape of the route it is
   * queueing; a guess made a fortnight later on a different network does not.
   *
   * ABSENT MEANS THE TOP LEVEL, so every entry written before this existed — and every entry from
   * the six forms that queue records — replays byte-for-byte as it always has.
   */
  savedIdIn?: string;
  /**
   * Replay progress. All optional: entries written before this existed simply have none, and start
   * their replay from the top exactly as they used to.
   *
   * `created` is the load-bearing one — true means the record IS on the server and re-sending the
   * body would make a second one, so the replay must skip straight to the media. The rest say what
   * the media may link to and which batches are already up there.
   */
  created?: boolean;
  createdId?: string | null;
  /** Ids of the created record's children, for batches that address a `processstep` by index. */
  createdStepIds?: string[];
  /** Indices into `media` whose files have already been sent; never sent twice. */
  uploadedBatches?: number[];
  /**
   * WHY A CLOSED-LIST FIELD ON THIS ENTRY IS EMPTY — keyed by the wire name of the column.
   *
   * ── THE TWO ABSENCES THIS QUEUE COULD NOT TELL APART, AND WHAT IT COST ────────────────────
   *
   * A record queued with no workshop against it has always been ONE thing on disk: a `null`, or a
   * key that was never written. It is two completely different acts by the person who made it:
   *
   *   * THEY CHOSE NOTHING. The picker offered four workshops, the researcher opened it and took
   *     the "none" row, because this artisan genuinely belongs to no design workshop.
   *     {@link UNFILED_BY_CHOICE}. That is a decision and the server has to be TOLD it: on a
   *     correction the column already holds a value, and "unfile" is only expressible as an
   *     explicit `{"designWorkshopId": null}`. `services/records.CLEARABLE_KEYS` exists for exactly
   *     this and says what happens without it — *"the save would return 200, the form would show it
   *     unfiled, and the old link would survive in the database."*
   *
   *   * THERE WAS NOTHING TO CHOOSE. The laptop was in a courtyard with no signal, an access list
   *     is never cached (R6 — a stale grant set is wrong in the permissive direction), so the
   *     picker was EMPTY. {@link UNFILED_NO_OPTIONS}. The researcher made no decision at all, and
   *     reading their empty box as one is how a correction composed on the bus home silently strips
   *     a link nobody was ever shown. That absence sends NOTHING for the column and the stored
   *     value stands.
   *
   * That is R1 — *empty means everything BY ABSENCE* — with its sign flipped for a form field:
   * absence means "no change" UNLESS it was chosen. Collapsing the two gives the column two
   * spellings for one state and no way to tell a default from a decision.
   *
   * OPTIONAL, so every entry queued before this existed carries no map, {@link clearedLinkKeys} is
   * empty for it, and the replay omits every link column exactly as it always did. An old queued
   * correction cannot be made to clear a link this build has no evidence anybody asked to clear.
   */
  unfiled?: Record<string, string>;
  /**
   * THIS RECORD POINTS AT AN ID THE SERVER DOES NOT HAVE — the one outcome this queue had no word
   * for. Absent on every other failure.
   *
   * ── WHY IT IS NOT ONE MORE PERMANENT REFUSAL ──────────────────────────────────────────────
   *
   * A 404 — or the 422 an existence check produces — is not transient, is not a 409, and is not a
   * schema refusal. So it fell through to the plain `markFailure` at the bottom of the drain and was
   * parked for ever behind two controls: *Try again*, which fetches the identical 404, and
   * *Discard*, which destroys the last copy of the record and its photographs. THIS FILE ALREADY
   * HAD THE WORDS FOR THAT SHAPE, written about the 409 it was added to close — *"a Try again
   * button that could only ever fetch the identical answer — a dead end wearing the costume of a
   * remedy."* The 409 got its own arm and a route out. A dangling foreign key did not, and neither
   * the banner nor the sentence said WHICH field's id was missing, which is the one fact that makes
   * the remedy obvious.
   *
   * The remedy is small because nothing is lost: the record is still in this browser with its
   * payload intact, and exactly one field in it is wrong. {@link repickOutboxEntry} rewrites that
   * one key and unparks the entry.
   *
   * ── WHAT IS STORED, AND WHY IT CAN NAME MORE THAN ONE ─────────────────────────────────────
   *
   * The wire name of the column — `designWorkshopId` — or, when the server's answer does not name a
   * field and the body carries several ids that could be at fault, EVERY candidate, comma-separated,
   * in {@link REFERENCE_FIELD_NOUNS} order. Read it back with {@link danglingKeys}.
   *
   * NAMING ALL OF THEM IS THE HONEST ANSWER AND PICKING ONE WOULD NOT BE. A 404 from
   * `records.require_record` is the string "Record not found" and a 404 from
   * `design_workshops.load_workshop_or_404` is the same string BY DESIGN — *"a 403 would confirm
   * that the id exists to precisely the caller being turned away"* — so on an artisan carrying both
   * a `workshopId` and a `designWorkshopId` there is nothing in the answer that separates them.
   * Choosing by plausibility would put fieldwork in the wrong place under a sentence claiming
   * certainty.
   */
  danglingField?: string | null;
};

/**
 * The researcher opened the picker and took the "none" row. A DECISION, and the wire must carry it.
 *
 * A plain string rather than a union member on the wire for the reason the Kotlin twin gives: this
 * value is written into a store that a LATER build may write and an EARLIER one may read, and a
 * value neither recognises must simply not be one of the two they act on. See
 * {@link OutboxEntry.unfiled}.
 */
export const UNFILED_BY_CHOICE = "chosen";

/** The picker was empty when this was filled in, so nothing could be chosen. */
export const UNFILED_NO_OPTIONS = "noOptions";

/**
 * EVERY FOREIGN KEY A QUEUED RECORD CAN POINT AT, and the noun each is called by on screen.
 *
 * The keys are `services/records.CLEARABLE_KEYS` minus the two identity numbers, which are VALUES
 * rather than references and can never 404. The nouns are what the forms already call these
 * controls, because the sentence a researcher reads has to name the box they are about to reopen —
 * "a design & prototype workshop", not "designWorkshopId".
 *
 * ORDER IS LOAD-BEARING, which is why this is an array of pairs and not an object read with
 * `Object.keys`. When more than one candidate survives, {@link outboxDanglingSentence} lists them in
 * this order, so two entries refused the same way never word the same ambiguity two different ways.
 * Byte-identical to `Offline.kt`'s `REFERENCE_FIELD_NOUNS`, including the order.
 */
export const REFERENCE_FIELD_NOUNS: ReadonlyArray<readonly [string, string]> = [
  ["designWorkshopId", "design & prototype workshop"],
  ["workshopId", "workshop"],
  ["artisanId", "artisan"],
  ["craftId", "craft"],
  ["productId", "product"],
  ["toolId", "toolkit"],
  ["processId", "process"],
  ["questionnaireInterviewId", "interview"],
  ["locationId", "place"]
];

/** The noun a column is called by on screen, or the key itself when it is not a reference. */
export function referenceFieldNoun(key: string): string {
  return REFERENCE_FIELD_NOUNS.find(([name]) => name === key)?.[1] ?? key;
}

/**
 * The two columns that file a record under a workshop, and the only two a picker on a record form
 * can leave empty.
 *
 * BOTH, TOGETHER, ALWAYS — DROPDOWN_DESIGN §2.7 says so in as many words: *"a sentinel on the wire
 * for BOTH COLUMNS AT ONCE"*. The two pickers have the identical shape and the identical
 * consequence, so a fix reaching one of them would leave a researcher clearing one box successfully
 * and the other box silently, on the same form, one line apart.
 */
export const WORKSHOP_LINK_KEYS: readonly string[] = ["designWorkshopId", "workshopId"];

/**
 * WHICH OF THE TWO ABSENCES A LINK BOX IS IN — the rule that decides it, in ONE place.
 *
 * ── THE THREE INPUTS, AND WHY THE BASELINE IS THE ONE THAT DECIDES ────────────────────────────
 *
 * `selectedId` is what the box holds now. `baselineId` is what it held when the form was BUILT —
 * the stored id on an edit, the prefill on a create — and it is never moved by a click.
 * `hadOptions` is whether the list behind the box had anything in it at all.
 *
 * A blank box over a non-blank baseline is the one combination a person alone can produce: they
 * opened a picker that was showing a workshop and took the "none" row. That is
 * {@link UNFILED_BY_CHOICE} whatever the list looks like at this moment — and it HAS to be read that
 * way, because at this moment the list can be empty: a record filed under a workshop this browser
 * cannot list still draws its recovered off-page row (`useRecordOffPage`), so the control stays
 * enabled and the "none" row stays reachable with the page empty. Deciding this by "is the list
 * empty" would read a deliberate clearance as "there was nothing to choose", omit the key, and
 * leave the researcher looking at an emptied form, a 200, and the old link still in the database.
 *
 * ── AND WHY THE OTHER TWO ARMS ARE WRONG IN THE HARMLESS DIRECTION ────────────────────────────
 *
 * With a blank baseline the column is already empty on the server, so either answer is a no-op on
 * the data and all the choice changes is which SENTENCE the drain prints
 * ({@link outboxSentUnfiledMessage}). `hadOptions` is the honest split: somebody shown four
 * workshops who picked none needs no sentence, and somebody shown an EMPTY picker in a courtyard has
 * to be told the record went up filed under nothing — otherwise that absence is discovered weeks
 * later as a record missing from a workshop's lists, by which time nobody can tell it from a record
 * deliberately filed under nothing.
 *
 * `null` when the box holds something: there is no absence to explain.
 */
export function unfiledLinkReason(selectedId: string, baselineId: string, hadOptions: boolean): string | null {
  if (selectedId.trim()) return null;
  if (baselineId.trim()) return UNFILED_BY_CHOICE;
  return hadOptions ? UNFILED_BY_CHOICE : UNFILED_NO_OPTIONS;
}

/**
 * {@link OutboxEntry.unfiled} for one record form, from the two pickers it mounts.
 *
 * THE KEYS ARE NAMED HERE AND NOWHERE ELSE, and that is the whole reason this short function exists
 * rather than an object literal at each save handler. A mistyped column — `designWorkshopID`,
 * `design_workshop_id` — is an error nowhere: it would ride in {@link clearedLinkKeys}, the replay
 * would write it into the body, the API's `extra="forbid"` would refuse the whole request, and a
 * clearance would turn into a schema refusal three files away.
 *
 * A form that mounts only one of the two pickers passes nothing for the other, and nothing is not a
 * clearance: the column is absent from the map, absent from {@link clearedLinkKeys}, and absent from
 * the replayed body, so the stored value stands. A form cannot un-file a link it never put a control
 * in front of anybody for.
 */
export function workshopUnfiledReasons(reasons: {
  designWorkshop?: string | null;
  workshop?: string | null;
}): Record<string, string> {
  const map: Record<string, string> = {};
  if (reasons.designWorkshop) map.designWorkshopId = reasons.designWorkshop;
  if (reasons.workshop) map.workshopId = reasons.workshop;
  return map;
}

/**
 * The link columns this entry's author DELIBERATELY emptied, and which a replay must therefore send
 * as an explicit `null`.
 *
 * Empty for every entry from every earlier build, which is what makes widening the record safe: no
 * evidence, no clearance, and the replay behaves exactly as it did before this field existed.
 */
export function clearedLinkKeys(entry: Pick<OutboxEntry, "unfiled">): string[] {
  return Object.entries(entry.unfiled ?? {})
    .filter(([, reason]) => reason === UNFILED_BY_CHOICE)
    .map(([key]) => key);
}

/** The columns that were empty because the picker had nothing in it. See {@link OutboxEntry.unfiled}. */
export function emptyPickerKeys(entry: Pick<OutboxEntry, "unfiled">): string[] {
  return Object.entries(entry.unfiled ?? {})
    .filter(([, reason]) => reason === UNFILED_NO_OPTIONS)
    .map(([key]) => key);
}

/** {@link OutboxEntry.danglingField} read back as the list it is. Empty when nothing is dangling. */
export function danglingKeys(entry: Pick<OutboxEntry, "danglingField">): string[] {
  return (entry.danglingField ?? "")
    .split(",")
    .map((key) => key.trim())
    .filter(Boolean);
}

// ---------------------------------------------------------------------------
// IndexedDB plumbing
// ---------------------------------------------------------------------------

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("IndexedDB unavailable"));
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: "id", autoIncrement: true });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Cannot open the offline outbox"));
    });
    // A failed open must not be cached forever — a private-mode tab that later allows storage
    // should get a working outbox rather than the first rejection for the rest of the session.
    dbPromise.catch(() => {
      dbPromise = null;
    });
  }
  return dbPromise;
}

function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb()
    .then(
      (db) =>
        new Promise<T>((resolve, reject) => {
          const transaction = db.transaction(STORE, mode);
          const request = run(transaction.objectStore(STORE));
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error ?? new Error("Offline outbox write failed"));
        })
    )
    .catch((error: unknown) => {
      // Recorded by MODE, because a store that cannot be read and a store that cannot be written are
      // different situations with different sentences: a full disk reads perfectly well. See
      // {@link OutboxStoreHealth}. Nothing is swallowed here — the caller still decides.
      noteStoreFailure(mode === "readonly" ? "read" : "write");
      throw error;
    });
}

/**
 * Read one entry and write it back inside ONE transaction, or do nothing if it has gone.
 *
 * ── WHY EVERY MID-PASS WRITE GOES THROUGH THIS ───────────────────────────────────────────────────
 *
 * The failure and progress writes used to `put` the object the loop was holding — an object that
 * still carries its `id`, read from the store before the network round trip that preceded it. In
 * IndexedDB a `put` of a deleted key does not fail: IT RE-CREATES THE ROW. So when two tabs drained
 * at once (which is what the lease below now prevents, and which nothing prevented on a browser
 * without Web Locks), the loser RESURRECTED the entry the winner had just filed and deleted, and the
 * researcher was left holding a queued copy of a record that is already in the repository.
 *
 * Read-then-write inside one readwrite transaction closes it from the other end: no row, no write.
 * `apply` is handed the row AS IT IS ON DISK rather than the loop's stale copy, so a counter another
 * pass incremented is not rolled back either.
 *
 * Resolves TRUE when a row was written and FALSE when there was nothing to write to — a distinction
 * the caller needs, because "the entry is gone" means the work is done and the pass must stop
 * working on it, not that the write failed.
 */
function updateEntry(id: number, apply: (row: OutboxEntry) => OutboxEntry | null): Promise<boolean> {
  return openDb()
    .then(
      (db) =>
        new Promise<boolean>((resolve, reject) => {
          const transaction = db.transaction(STORE, "readwrite");
          const store = transaction.objectStore(STORE);
          const read = store.get(id) as IDBRequest<OutboxEntry | undefined>;
          let wrote = false;
          read.onsuccess = () => {
            const row = read.result;
            if (!row) return; // Another tab finished and deleted it. Do not put it back.
            const next = apply(row);
            if (!next) return;
            // Issued from inside the read's own success handler, so the transaction is still active.
            const write = store.put(next);
            write.onsuccess = () => {
              wrote = true;
            };
          };
          transaction.oncomplete = () => resolve(wrote);
          transaction.onerror = () => reject(transaction.error ?? new Error("Offline outbox write failed"));
          transaction.onabort = () => reject(transaction.error ?? new Error("Offline outbox write aborted"));
        })
    )
    .catch((error: unknown) => {
      noteStoreFailure("write");
      throw error;
    });
}

// ---------------------------------------------------------------------------
// Subscription — one source of truth for every banner on the page
// ---------------------------------------------------------------------------

const listeners = new Set<() => void>();
let cache: OutboxEntry[] = [];

function publish() {
  listeners.forEach((listener) => listener());
}

export function subscribeOutbox(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Synchronous snapshot for `useSyncExternalStore`; refreshed by {@link refreshOutbox}. */
export function getOutboxSnapshot(): OutboxEntry[] {
  return cache;
}

/** Server render has no IndexedDB — a stable empty array keeps the store from looping. */
const SERVER_SNAPSHOT: OutboxEntry[] = [];
export function getServerOutboxSnapshot(): OutboxEntry[] {
  return SERVER_SNAPSHOT;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Whether this browser's outbox is answering at all
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * When IndexedDB last refused this outbox a READ, and when it last refused it a WRITE.
 *
 * ── AN UNREADABLE OUTBOX RENDERED AS AN EMPTY ONE, WHICH IS A FALSE ALL-CLEAR ────────────────────
 *
 * `refreshOutbox` swallows the rejection and caches an empty list — deliberately, because a store
 * that cannot be read must not throw into whichever render happened to ask, and that policy is not
 * what is being changed here. What was wrong is that the swallowing left NO TRACE. `OutboxBanner`
 * returns `null` for an empty list, so the amber panel carrying the one sentence that answers "may
 * I hand this laptop on" — *they live in this browser, so do not clear its data* — DISAPPEARED on
 * the morning the store stopped answering, holding a fortnight of unsent records. And a read that
 * failed mid-pass made `runSync` report zero remaining, so a click was answered "Nothing to send".
 *
 * The same record as `DwStoreHealth` in `lib/designWorkshopStore.ts`, for the same reason and with
 * the same shape: the two banners sit on the same screens and must not describe one device in two
 * vocabularies.
 *
 * TIMESTAMPS AND NOT BOOLEANS, because "it failed once an hour ago and has worked since" and "it is
 * failing now" want different sentences, and because a successful read may clear the READ flag while
 * a write failure stands on its own — a full disk reads perfectly well.
 */
export type OutboxStoreHealth = {
  /** When a read of the outbox last failed, or null when none has since the last success. */
  readFailedAt: number | null;
  /** When a WRITE last failed. Never cleared by a read. */
  writeFailedAt: number | null;
};

let health: OutboxStoreHealth = { readFailedAt: null, writeFailedAt: null };

/**
 * Stable identity while nothing has changed — `useSyncExternalStore` re-renders on every snapshot
 * whose reference moved, so a fresh object per call would loop the banner for ever.
 */
export function getOutboxHealth(): OutboxStoreHealth {
  return health;
}

const SERVER_HEALTH: OutboxStoreHealth = { readFailedAt: null, writeFailedAt: null };
export function getServerOutboxHealth(): OutboxStoreHealth {
  return SERVER_HEALTH;
}

/** True when this browser can currently be trusted to say what it is holding. */
export function outboxIsAnswering(state: OutboxStoreHealth = health): boolean {
  return state.readFailedAt === null && state.writeFailedAt === null;
}

function noteStoreFailure(kind: "read" | "write"): void {
  const now = Date.now();
  health = kind === "read" ? { ...health, readFailedAt: now } : { ...health, writeFailedAt: now };
  /*
    AND TELLS THE LISTENERS, WITHOUT RE-READING. This used not to publish, on the stated grounds that
    every call site is inside a read or a write that publishes a moment later. That is true of the
    reads and FALSE OF EXACTLY THE WRITES: `queueOffline` rejects at its `tx` and never reaches the
    `refreshOutbox` on the next line, and `updateEntry`'s rejection out of `markFailure` leaves the
    pass before its closing read. Both are the case the red panel's write sentence is written for —
    "a queued record may not have been saved here" — and in both the mark sat on the record
    unrendered while the banner went on showing the last good list. The classic three-of-four miss in
    this repository: the mark, the panel and the sentence, and nothing to say them.

    Publishing HERE rather than at each throwing write is what cannot drift as writes are added, and
    it does not re-read: the rows have not changed by definition, and a `getAll` over a fortnight of
    queued files is the last thing to spend on the laptop whose store just refused one. The sibling
    reaches the same place from the other end — see the `else publish()` on `putDraftStage`'s failed
    write in `lib/designWorkshopStore.ts` and the note above it. The cost is one duplicated render
    per failure on the paths that do go on to read, which is a failure path, once.
  */
  publish();
}

/**
 * The designer has been told, and has decided to carry on. Clears both marks.
 *
 * Nothing is repaired by this — it is an acknowledgement, exactly like `acknowledgeStoreTrouble`'s
 * in the design-workshop store. A warning that cannot be dismissed on a laptop whose disk was freed
 * an hour ago is a warning people stop reading.
 */
export function acknowledgeOutboxTrouble(): void {
  health = { readFailedAt: null, writeFailedAt: null };
  publish();
}

/**
 * Re-read the store into the cache. The result is the cache either way, so no caller has to cope
 * with an exception — but {@link getOutboxHealth} now says whether the array can be believed, and
 * the read flag is cleared ONLY by a read that actually succeeded.
 */
export async function refreshOutbox(): Promise<OutboxEntry[]> {
  try {
    const rows = await tx<OutboxEntry[]>("readonly", (store) => store.getAll() as IDBRequest<OutboxEntry[]>);
    cache = rows.sort((a, b) => a.createdAt - b.createdAt);
    if (health.readFailedAt !== null) health = { ...health, readFailedAt: null };
  } catch {
    // `tx` has already recorded the read failure. The empty cache is kept — the alternative is
    // rendering rows nobody can act on — and the health record is what stops it reading as an
    // all-clear.
    cache = [];
  }
  publish();
  return cache;
}

// ---------------------------------------------------------------------------
// Queue / discard
// ---------------------------------------------------------------------------

let persistenceAsked = false;

/**
 * Ask the browser to stop treating this origin's storage as disposable.
 *
 * An origin that has not been granted persistence holds its IndexedDB on a "best effort" basis: the
 * browser may evict it when the disk fills, which on a field laptop two weeks into a study is when
 * it fills. The design-workshop store has asked for this since its first attached file
 * (`requestPersistence` there, and the note on it explains why the moment matters); the records
 * outbox never did — so a researcher who queues six artisans and their photographs but never opens
 * a design-workshop stage kept a fortnight of work in an evictable origin, and the whole point of
 * this module is that those bytes cannot be recreated because the artisan has gone home.
 *
 * Asked at the first QUEUED SAVE rather than at page load, for the same reason as the sibling: some
 * browsers prompt, and a prompt while somebody is opening a list is noise they dismiss, while a
 * prompt the moment a save is banked on the device is a question they can answer. A refusal is not
 * an error — the bytes are written either way, and telling a researcher their browser might one day
 * evict them is a warning they can do nothing about.
 */
async function requestPersistence(): Promise<void> {
  if (persistenceAsked) return;
  persistenceAsked = true;
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.persist) return;
    if (await navigator.storage.persisted()) return;
    await navigator.storage.persist();
  } catch {
    // A browser that refuses to answer the question is answering it.
  }
}

/**
 * How many saves THIS TAB has banked into the outbox since the page loaded. Only ever rises.
 *
 * ── WHY THE BANNER CANNOT ANSWER THIS BY WATCHING THE ROW COUNT ─────────────────────────────────
 *
 * "Entry saved on this device" is the one message that tells a researcher with no signal that their
 * work went somewhere, and `OutboxBanner` used to infer it from the number of rows going up. Rows go
 * up for two reasons that look identical from there: a save, and A READ. Entries survive a browser
 * restart, so the load that follows a fresh mount is a fortnight of queued records arriving at once,
 * and the banner told a researcher opening the laptop the next morning that her week-old queue had
 * "just been saved on this device with no connection". The defence was a baseline armed by the first
 * successful read — which works, until the store is unreadable AT MOUNT: nothing can arm the
 * baseline, the store recovers, and the next save arms it silently instead of announcing itself. The
 * first offline save after a store hiccup got no confirmation at all, which is the same defect from
 * the other side.
 *
 * A COUNTER OF SAVES CANNOT HAVE EITHER PROBLEM, because it is not a fact about the store: it starts
 * at zero on every page load and moves only where a record is actually banked. A read moves nothing,
 * so there is no baseline to arm and no moment at which arming can be missed.
 */
let queuedHere = 0;

/** Snapshot for `useSyncExternalStore`; published by {@link queueOffline} through the same channel. */
export function getOutboxQueuedHere(): number {
  return queuedHere;
}

/** A server render has queued nothing, and 0 is stable across calls. */
export function getServerOutboxQueuedHere(): number {
  return 0;
}

export async function queueOffline(entry: Omit<OutboxEntry, "id" | "createdAt" | "attempts" | "failure">): Promise<void> {
  // Not awaited: the save must land on the device now, and a permission prompt must never sit
  // between the researcher and their record.
  void requestPersistence();
  await tx("readwrite", (store) =>
    store.add({ ...entry, createdAt: Date.now(), attempts: 0, failure: null } as OutboxEntry)
  );
  // AFTER the write, never before: a save the device refused is not a save, and announcing one would
  // be the reassurance this whole module exists to make true. `refreshOutbox` publishes both.
  queuedHere += 1;
  await refreshOutbox();
}

export async function discardOutboxEntry(id: number): Promise<void> {
  await tx("readwrite", (store) => store.delete(id));
  await refreshOutbox();
}

/**
 * Forgive one recorded refusal so the next pass tries the entry again.
 *
 * THE FIRST THING IN THIS REPOSITORY THAT EVER WROTE AN OUTBOX FAILURE BACK TO NULL, and that
 * absence was the whole defect. `failure` was written at five sites and cleared at none, so once an
 * entry had been marked — by a token that expired mid-drain, by a captive portal answering the POST
 * with its own sign-in page, by a server that was briefly refusing — the banner withdrew "Sync now"
 * and offered exactly one control: Discard, which deletes the record AND its photographs. The only
 * way back was to throw the work away. Android has never done that, and the design-workshop banner
 * next door offers "Try again" for precisely these cases.
 *
 * Nothing is re-sent here and nothing is repaired: the refusal is cleared because the person has
 * read it and decided the conditions have changed (they have signed in again, they are through the
 * portal, the server is back). The pass that follows is what finds out.
 */
export async function retryOutboxEntry(id: number): Promise<void> {
  await updateEntry(id, (row) =>
    row.failure === null && !row.skewRun && !row.danglingField
      ? null
      : // `danglingField` is cleared with the rest for the reason the whole marker exists: it
        // describes THIS refusal, and the person clearing it is saying the conditions have changed.
        // A stale marker would leave the banner offering a re-pick for a refusal nobody is holding.
        { ...row, failure: null, skewRun: null, danglingField: null }
  );
  await refreshOutbox();
}

/**
 * WHICH IDS IN THIS ENTRY COULD BE THE MISSING ONE — the honest answer, which is sometimes several.
 *
 * Reads the queued body for every {@link REFERENCE_FIELD_NOUNS} key it actually carries a non-empty
 * string for, in that array's order. An entry that names none of them is not a dangling-reference
 * failure at all and gets the ordinary refusal arm.
 *
 * PURE, and exported so the ambiguity can be asserted without a server that 404s: the interesting
 * case is an artisan queued with BOTH a `workshopId` and a `designWorkshopId`, where the server's
 * answer is the same string either way.
 */
export function danglingCandidates(body: string): string[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    // A body this build cannot parse is one it did not write. Nothing can be re-picked in it, so it
    // takes the ordinary refusal arm rather than a remedy that could not work.
    return [];
  }
  if (!parsed || typeof parsed !== "object") return [];
  const record = parsed as Record<string, unknown>;
  return REFERENCE_FIELD_NOUNS.filter(([key]) => typeof record[key] === "string" && (record[key] as string).trim()).map(
    ([key]) => key
  );
}

/**
 * IS THIS REFUSAL A REFERENCE THAT IS NOT THERE?
 *
 * ── THE TWO STATUSES, AND WHY THE SECOND IS NARROWED BY ITS WORDS AND THE FIRST IS NOT ────────
 *
 * A **404** on a create or a correction can only mean one thing: a route that reached its handler
 * and could not find something it was told to attach to. Every 404 this queue's six endpoints can
 * produce is `require_record`'s "Record not found" or `load_workshop_or_404`'s identical string.
 *
 * A **422** is mostly validation and only sometimes an existence check, so it is admitted only when
 * the server's own words say so. Reading the prose is a compromise and it is the smaller of the two
 * available ones: the alternative is to treat every 422 as a dangling reference and offer a re-pick
 * for a refusal about a missing product name, which sends the researcher to a dropdown that cannot
 * fix it. Schema refusals are already taken by `schemaRefusalError` before this is asked.
 *
 * A REFUSAL WITH NO CANDIDATE IN THE BODY IS NOT ONE OF THESE, whatever its status: a re-pick
 * dialog with no field to re-pick is the dead end this whole arm exists to remove, wearing a new
 * costume. The caller checks the candidate list, not just this predicate.
 */
export function isDanglingReference(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  if (error.status === 404) return true;
  if (error.status !== 422) return false;
  const said = `${error.message}`.toLowerCase();
  return said.includes("not found") || said.includes("does not exist") || said.includes("no longer exists");
}

/**
 * "a workshop", "an artisan" — the list read out with its articles, and "or" before the last.
 *
 * `a`/`an` from the first letter, which is right for every noun in {@link REFERENCE_FIELD_NOUNS} and
 * is checked by the spec rather than assumed; "interview" is the only vowel among them today.
 */
function anyOneOf(nouns: readonly string[]): string {
  const withArticles = nouns.map((noun) => `${/^[aeiou]/i.test(noun) ? "an" : "a"} ${noun}`);
  if (withArticles.length <= 1) return withArticles[0] ?? "a record";
  return `${withArticles.slice(0, -1).join(", ")} or ${withArticles[withArticles.length - 1]}`;
}

/**
 * WHAT A RESEARCHER IS TOLD WHEN A QUEUED RECORD POINTS AT SOMETHING THE SERVER DOES NOT HAVE.
 *
 * Four things, and each is load-bearing:
 *
 *  1. WHAT IS WRONG, IN THE WORDS OF THE BOX THEY WILL REOPEN — not "designWorkshopId".
 *  2. THAT NOTHING IS LOST, before anything else, because the control beside this sentence is
 *     Discard and a person who has read "the server rejected this" reaches for it.
 *  3. THE SERVER'S OWN WORDS, VERBATIM. "Record not found" adds little, and it is printed anyway,
 *     because the rule this banner keeps is that the API's sentence is never summarised — a route
 *     that starts saying something more useful must not have to wait for a client release.
 *  4. THAT A BARE RETRY CANNOT WORK. Without it the researcher walks up the hill for a signal and
 *     does it again tomorrow.
 *
 * PURE and exported, for the reason every sentence in this file is: it is read by somebody standing
 * in a courtyard, so a spec is the only place it can be checked.
 */
export function outboxDanglingSentence(said: string, nouns: readonly string[], files: number, isCorrection: boolean): string {
  const subject = isCorrection ? "This correction" : "This record";
  const head =
    nouns.length === 1
      ? `${subject} points at ${anyOneOf(nouns)} that is not on the server.`
      : // NOT A GUESS DRESSED AS A FACT. The 404 names no field and the body carries several ids, so
        // the sentence carries several.
        `${subject} points at something that is not on the server. It is ${anyOneOf(nouns)} — the server's answer does not say which.`;
  const carrying = files > 0 ? ` and the ${files} file(s) saved with it` : "";
  const isAre = files > 0 ? "are" : "is";
  const trimmed = said.trim();
  const server = trimmed ? ` The server said: ${/[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`}` : "";
  return (
    `${head} Nothing is lost — open it, choose one that is, and it will send.${server} ` +
    `Nothing has been sent and nothing has been deleted: this entry${carrying} ${isAre} still in this browser. ` +
    "Sending it again unchanged will get the same answer, because what is missing is missing on the server."
  );
}

/**
 * WHAT THE RE-PICK PANEL SAYS WHEN IT HAS NOTHING TO OFFER.
 *
 * Two facts, two sentences, and they are NOT §3.5's — deliberately. §3.5's sentences are said on a
 * FORM, about a record that has not been saved yet, and they end by promising that "this record can
 * be saved without it". Here the record IS saved, in this browser, and it is stuck: that promise
 * would be true and useless, and the standing fact the researcher needs is the opposite one — the
 * entry is not lost while this panel cannot help, and it will still be here when the list can be
 * read.
 *
 * THE READ FAILED is separated from THE SCOPE IS EMPTY because their next moves are a connection and
 * an administrator, and somebody sent to the wrong one loses a day. "No workshops are open to this
 * account" said after a timeout is the single most repeated bug class in this repository, arriving on
 * the one surface whose whole job is to be a way out.
 */
export function repickEmptyLine(noun: string, listed: boolean): string {
  return listed
    ? `No ${noun} is open to this account, so there is nothing to point this at. An administrator can give you access to one. Until then the record stays here — nothing is deleted.`
    : `The ${noun} list could not be read just now, so this is not showing what exists. Nothing has been lost — the record and anything saved with it stay in this browser, and this will work when the list can be read.`;
}

/**
 * WHAT A RESEARCHER IS TOLD WHEN A RECORD GOES UP FILED UNDER NOTHING BECAUSE THE LIST WAS EMPTY.
 *
 * The one outcome of the three that ends in SUCCESS, which is exactly why it needs saying. The entry
 * leaves the queue, the banner's count drops, and every visible sign says the record arrived intact.
 * It did arrive; it arrived UNFILED, because the picker had nothing in it at the moment it was
 * filled in and somebody in a cluster with no signal never saw a choice to make. Say nothing and the
 * record is found missing from a workshop's lists weeks later, by which time nobody can reconstruct
 * which of the two absences it was.
 *
 * IT IS NOT THE DANGLING SENTENCE AND IT IS NOT AN EMPTY-PICKER SENTENCE. Nothing is refused, so
 * there is no remedy to offer and no button to press; the record simply needs filing, on its own
 * screen, whenever there is next a connection. R7: the two failures never share a message.
 */
export function outboxSentUnfiledMessage(label: string, nouns: readonly string[]): string {
  // NO ARTICLES, because "no" already carries the determiner: "there was no design & prototype
  // workshop to choose from" is the sentence, and "there was no A design & prototype workshop" is
  // the kind of seam that tells a reader the words were assembled by a machine.
  const list =
    nouns.length === 0
      ? "workshop"
      : nouns.length === 1
        ? nouns[0]
        : `${nouns.slice(0, -1).join(", ")} or ${nouns[nouns.length - 1]}`;
  return (
    `“${label}” was sent, and it is filed under nothing: there was no ${list} to choose from in this ` +
    "browser when it was saved. That was never a claim that none exist. Open the record and file it now that " +
    "there is a connection."
  );
}

/**
 * Point a queued entry at a different record — the remedy for {@link OutboxEntry.danglingField}.
 *
 * ── WHY THIS EDITS THE PAYLOAD RATHER THAN REOPENING THE FORM ─────────────────────────────────
 *
 * The record is already complete and already in this browser: its media are staged against this
 * entry, its typed values are in `body`, and the whole of what is wrong with it is one id. Reopening
 * the form would mean rebuilding the record from a payload the form does not know how to seed, and
 * every field that failed to round-trip would be silently re-derived from today's state. One key is
 * re-serialised and the entry is unparked.
 *
 * ── CHOOSING NOTHING IS AN ANSWER, AND IT IS RECORDED AS ONE ──────────────────────────────────
 *
 * `chosen === null` means the researcher decided the record belongs to none of them, so the key is
 * written as an explicit `null` AND {@link OutboxEntry.unfiled} is marked {@link UNFILED_BY_CHOICE}
 * — deciding HERE is as deliberate as deciding on the form, and `CLEARABLE_KEYS` is what carries it
 * to a column that already holds a value. Marking it `noOptions` instead would be this file
 * inventing a claim about a list the person was looking at when they answered.
 *
 * The failure and the marker are cleared together, so the next pass simply sends the entry.
 */
export async function repickOutboxEntry(id: number, field: string, chosen: string | null): Promise<void> {
  await updateEntry(id, (row) => {
    let parsed: Record<string, unknown>;
    try {
      const candidate: unknown = JSON.parse(row.body);
      if (!candidate || typeof candidate !== "object") return null;
      parsed = candidate as Record<string, unknown>;
    } catch {
      // Nothing this build can rewrite. Left exactly as it is: a queue entry is fieldwork, and the
      // only door out that is not a successful send is the person-confirmed Discard.
      return null;
    }
    parsed[field] = chosen;
    const unfiled = { ...(row.unfiled ?? {}) };
    if (chosen === null) unfiled[field] = UNFILED_BY_CHOICE;
    else delete unfiled[field];
    return { ...row, body: JSON.stringify(parsed), unfiled, failure: null, skewRun: null, danglingField: null };
  });
  await refreshOutbox();
}

/**
 * Record why an entry did not go — against the row AS IT IS ON DISK. See {@link updateEntry} for why
 * the loop's own copy may not be written back.
 *
 * `danglingField` is WRITTEN ON EVERY CALL, including the calls that pass nothing, and that is not
 * an accident of the default. A refusal is a fresh reading of this entry as it stands NOW: an entry
 * whose missing workshop was re-picked and which is then refused for a clashing Aadhaar must not
 * keep offering a "Re-pick it" button that edits a field nothing is wrong with any more. Same rule
 * and same reason as `skewRun` beside it.
 */
async function markFailure(
  entry: OutboxEntry,
  failure: string,
  skewRun: string | null = null,
  danglingField: string | null = null
): Promise<void> {
  await updateEntry(entry.id!, (row) => ({ ...row, attempts: row.attempts + 1, failure, skewRun, danglingField }));
}

/**
 * Write a replay's progress back to the entry mid-pass.
 *
 * Called after the create lands and after each media batch goes up, so the durable record of what
 * has already happened is never behind what actually happened. The window between the server
 * committing and this returning is the only place a duplicate can still be born; a few milliseconds
 * of IndexedDB is as small as that window gets without idempotency keys on the API.
 *
 * ONLY THE PROGRESS FIELDS ARE WRITTEN, onto the row as it is on disk, and only if the row is still
 * there. A blind `put` of the loop's copy re-created an entry another tab had already filed and
 * deleted — see {@link updateEntry}. FALSE therefore means "this entry is finished and gone", which
 * the caller must treat as a reason to stop working on it rather than as a failed write.
 *
 * `media` IS A PROGRESS FIELD, which is not obvious and is why it is named here. A batch the server
 * only partly refused is narrowed to the files that did NOT land before this is called (see the
 * media loop), so the durable record of "what is still owed" shrinks as the bytes actually go — the
 * same job `uploadedBatches` does for whole batches. Nothing else in this module writes `media` after
 * the queue, and the pass holds the device's only lease, so there is no other writer to clobber.
 */
async function persistProgress(entry: OutboxEntry): Promise<boolean> {
  return updateEntry(entry.id!, (row) => ({
    ...row,
    created: entry.created,
    createdId: entry.createdId,
    createdStepIds: entry.createdStepIds,
    uploadedBatches: entry.uploadedBatches,
    media: entry.media
  }));
}

/**
 * The queued body with the researcher's DELIBERATE clearances written into it as explicit nulls.
 *
 * ── WHY THE STORED BODY DOES NOT ALREADY CARRY THEM ───────────────────────────────────────────
 *
 * Because a form cannot tell the two absences apart at the moment it serialises. Both come out of
 * `FormData` as an empty string, and the queue's whole discipline is that `body` is *"serialised at
 * queue time so a later schema change cannot alter what the user actually saved"*. So the EVIDENCE
 * is stored beside the body ({@link OutboxEntry.unfiled}) and the null is written at replay, which
 * also means an entry queued by an earlier build — which has no evidence — replays byte for byte as
 * it always did.
 *
 * `CLEARABLE_KEYS` on the server is the other half and says what happens without this: *"the save
 * would return 200, the form would show it unfiled, and the old link would survive in the
 * database."*
 *
 * A BODY THAT WILL NOT PARSE IS RETURNED UNTOUCHED rather than rebuilt. It was not written by this
 * build, nothing here can safely reason about it, and sending it exactly as queued is the behaviour
 * it was queued under.
 */
function bodyWithClearances(entry: OutboxEntry): string {
  const cleared = clearedLinkKeys(entry);
  if (cleared.length === 0) return entry.body;
  try {
    const parsed: unknown = JSON.parse(entry.body);
    if (!parsed || typeof parsed !== "object") return entry.body;
    const record = parsed as Record<string, unknown>;
    for (const key of cleared) record[key] = null;
    return JSON.stringify(record);
  } catch {
    return entry.body;
  }
}

/** Files across every batch of one entry — what the researcher stands to lose, in one number. */
function pendingFileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

// ---------------------------------------------------------------------------
// Drain
// ---------------------------------------------------------------------------

/**
 * IS THIS THE NETWORK? ASKED IN ONE PLACE, FOR EVERY SURFACE — see `lib/failureTriage.ts`.
 *
 * These names are re-exported rather than defined here, and the module they come from explains at
 * length why. The short version is the reason this file's own header gives for exporting them in the
 * first place, applied to itself: two implementations of "is this the network" are two different
 * ideas of what offline means, and whichever one is wrong either strands a queue for ever or replays
 * a rejection until somebody clears the browser's storage. This file held THREE of them, and they
 * disagreed: `isTransient` did not follow `cause` and `isUnreachable`, eight lines below it, did;
 * `isSchemaRefusal` demanded an `ApiError` outright, so a 422 met while uploading a photograph read
 * as an ordinary refusal of the file. `lib/designWorkshopStore.ts` carried a fourth inline, twice.
 * Every outbox defect the 2026-08 audit closed was a disagreement between two of them.
 *
 * THE IMPORT PATH DID NOT MOVE, on purpose. Every screen that asks one of these questions asks it
 * through `@/lib/offline`, and none of them wrongly — the outbox is where the questions were first
 * asked and this is still the file that acts on the answers. Re-exporting means every one of them
 * started consulting one implementation without a single screen having to be edited, which is what
 * made the change safe to make at all. HOW MANY there are is deliberately not written down here: two
 * earlier drafts of this comment and its neighbour in `lib/failureTriage.ts` each stated a count, the
 * counts disagreed, and both were wrong. `e2e/failure-triage-unit.spec.ts` measures it instead.
 *
 * `isSchemaRefusal` IS NO LONGER A TYPE GUARD. It unwraps now, so `error is ApiError` would be a lie
 * about a `MediaBatchError`. A caller that has to QUOTE the server's sentence calls
 * `schemaRefusalError`, which hands back the object; the drain below is the one that does.
 *
 * `isTransient` AND `underlyingIsTransient` ARE BOTH EXPORTED, AND WHICH ONE YOU WANT DEPENDS ON WHAT
 * A FALSE DOES. If a false MARKS the item and a true keeps it queued — every drain — take
 * `underlyingIsTransient`, which reads the refusal inside the batch wrapper. If a false DISCARDS work
 * (`FieldInput`'s media catch is the one that does, by falling past `stageOffline`), take
 * `isTransient`, whose answer for a wrapper it cannot read is still "keep it". They are two readings
 * of one table, not two implementations; `lib/failureTriage.ts` says why the pair exists and what
 * would have to change in `components/designworkshop/` for it to collapse back into one.
 */
export {
  isCredentialExpiry,
  isSchemaRefusal,
  isTransient,
  isUnreachable,
  schemaRefusalError,
  serverAskedForTime,
  triageAsThrown,
  triageFailure,
  underlyingError,
  underlyingIsTransient
} from "@/lib/failureTriage";

/* ────────────────────────────────────────────────────────────────────────────
 * How long a recorded refusal is allowed to bind
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THIS RUN OF THE APP. One string, minted when the bundle is first evaluated and stable for the
 * life of the page — a client-side navigation does not re-evaluate a module, a reload does.
 *
 * Its only job is to be DIFFERENT next time, so a failure record can say "the app that recorded me
 * is not the app reading me" without anything having to know what changed.
 *
 * `randomUUID` is absent outside a secure context (an http:// LAN address, which is how a field
 * laptop reaches a locally-hosted stack), so the fallback is not decoration — without it this module
 * would throw at import time on exactly those machines. Same shape and same reason as
 * `newClientKey` in `lib/designWorkshops.ts`.
 */
export const APP_RUN_ID: string = (() => {
  const api = typeof crypto !== "undefined" ? crypto : undefined;
  if (api && typeof api.randomUUID === "function") return api.randomUUID();
  return `run-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
})();

/** The two fields {@link blocksRetry} reads. Satisfied structurally by every failure record here. */
export type RecordedRefusal = {
  /** True when the server ANSWERED and refused, so waiting for a better signal cannot help. */
  permanent: boolean;
  /**
   * The {@link APP_RUN_ID} that recorded a refusal ONLY AN UPDATE CAN CLEAR. Null/absent on every
   * other failure, which is most of them.
   */
  skewRun?: string | null;
};

/**
 * Must this recorded refusal stop the pass from trying the item again on its own?
 *
 * ── WHY "PERMANENT" WAS NOT ENOUGH ──────────────────────────────────────────────────────────────
 *
 * `permanent` means "the server answered, so a better connection will not help", and it is used for
 * two refusals that are nothing alike:
 *
 *   • a refusal the DESIGNER can fix — a rejected field, a workshop an admin deleted, a duplicate.
 *     Retrying it unchanged really will get the same answer for ever, and the banner is right to ask
 *     a person for a decision. `skewRun` is null and this returns true, exactly as before.
 *
 *   • a SCHEMA refusal ({@link isSchemaRefusal}) — this build of the client and this build of the
 *     server disagree about the shape of the request. Nobody typed anything wrong and no edit can
 *     clear it; what clears it is an UPDATE TO ONE OF THE TWO. Marking that permanent means the app
 *     can never recover from a skew even after the skew has gone. It happened: on 2026-08-08 a
 *     client sent the then-unknown `merge` key, every stage came back "merge: Extra inputs are not
 *     permitted", and the record of that refusal outlived the fix — the same banner was still on
 *     screen after the API had been taught `merge`, telling a designer to go and correct an answer
 *     that was never wrong.
 *
 * ── THE TRIGGER, AND WHY IT IS THE APP RUN ──────────────────────────────────────────────────────
 *
 * A schema refusal is re-attempted ONCE PER APP RUN: the run that recorded it does not try again
 * (see the note below), and the next one does, with nobody pressing anything.
 *
 * NOT ON EVERY PASS. A pass runs on the `online` event and on every "Sync now", and in a village
 * with a marginal signal the connectivity event alone fires dozens of times an hour. Against a
 * server that really is too old, 22 stages × every flap is a prepaid-data bill for 422s nobody will
 * read, and a banner that never settles.
 *
 * NOT ON A CLIENT BUILD CHANGE, WHICH IS THE OBVIOUS ANSWER AND THE WRONG ONE. A skew closes when
 * EITHER side is updated, and the client cannot see the server's build: there is no version on any
 * response, and the one server digest the client does hold — `registryVersion` — is documented in
 * `backend/app/services/stage_schema.py::registry_version` as a digest of registry KEYS, TYPES,
 * TIERS, DERIVATIONS and HYDRATION, which the wire schema `extra="forbid"` guards is not part of.
 * Measured, not assumed: the 2026-08-08 skew was closed by teaching the API `merge`
 * (`backend/app/schemas/design_workshops.py`), which moved no registry digest — so a build check
 * would have gone on refusing the stage for ever, which is the bug.
 *
 * The app run is the coarser signal that covers both: every client update is DELIVERED by a new app
 * run, so it is a strict superset of the build check, and it also comes round after a server update
 * without needing to observe one. Its cost is bounded by something a person does — one extra request
 * per stranded item per time the app is opened — which is the property "every pass" does not have.
 *
 * A run that has already tried is remembered by RE-RECORDING the failure with the current run id, so
 * the second pass of one page load does not re-send what the first one just had refused.
 */
export function blocksRetry(refusal: RecordedRefusal | null | undefined): boolean {
  if (!refusal?.permanent) return false;
  if (!refusal.skewRun) return true;
  return refusal.skewRun === APP_RUN_ID;
}

/**
 * Separate the files that can be sent from the ones this device already knows will be refused.
 *
 * TODAY THAT IS EXACTLY THE EMPTY ONES, and they are separated HERE rather than classified after the
 * fact because `lib/media.ts` refuses a 0-byte file before any request is made — and it used to do so
 * with a bare `Error`, which carries nothing anything downstream can classify. `isUnreachable`
 * defaults such a thing to "the connection is at fault", which is the correct default for a
 * `TypeError` out of `fetch` and precisely wrong here: the batch escalation then stopped the whole
 * pass as if the device were offline and came back to the identical empty file on every future
 * connection, jamming every entry behind it. Emptiness is knowable before the call, so it is decided
 * before the call.
 *
 * THE REFUSAL IS TYPED NOW — `LocalRefusalError` in `lib/failureTriage.ts` classifies as `permanent`
 * from wherever it is caught, wrapped or not — and this function is NOT redundant because of it. The
 * type is what protects a caller that forgets to ask; this is what means the question never has to be
 * asked, and what names the empty files in the sentence the researcher reads. A guard that only some
 * callers remember is the shape of half the defects in this file's history, so it now has both.
 *
 * A 0-byte file is not exotic on this hardware: a capture the camera app never finished writing, a
 * file copied off a card that was pulled mid-write.
 */
export function splitUnsendableFiles(files: File[]): { sendable: File[]; empty: File[] } {
  return { sendable: files.filter((file) => file.size > 0), empty: files.filter((file) => file.size === 0) };
}

/**
 * What a batch is STILL OWED after a call that returned — the files that did not land.
 *
 * ── THE DECISION A PARTLY REFUSED BATCH TURNS ON, PULLED OUT SO IT CAN BE ASSERTED ──────────────
 *
 * `uploadMediaBatch` throws only when NOTHING landed, so a batch of four photographs in which the
 * server refused one (413 on the one over the size limit, 415 on the one the phone wrote as heic)
 * RETURNS, with the refusal in `failed`. Recording that batch as uploaded was right about the danger
 * — replaying it would upload the three that landed a second time — and wrong about the remedy: a
 * recorded batch is skipped whole on every later pass, so once a refusal could be cleared, the next
 * pass found nothing outstanding, deleted the entry with the refused photograph inside it, and
 * reported the entry sent. Narrowing the batch to this list instead keeps the danger closed for the
 * files that landed and keeps the ones that did not.
 *
 * `uploadedByIndex` IS READ BY POSITION AND NEVER BY NAME. It is the by-index array before
 * `uploaded`'s compaction, which exists precisely because the two were confused once already — see
 * its note in `lib/media.ts`. Matching on `file.name` would be worse than useless here: two
 * photographs off one handset are routinely both `IMG_0001.jpg`.
 *
 * `attempted` is the SENDABLE slice, not the batch: anything separated out by
 * {@link splitUnsendableFiles} was never offered to the server, so it is not in this array and must
 * come back as still owed — which is what keeps a 0-byte file naming itself on every pass instead of
 * letting the entry reach the discard branch.
 */
export function outstandingFiles(
  batchFiles: File[],
  attempted: File[],
  uploadedByIndex: BatchResult["uploadedByIndex"]
): File[] {
  const landed = new Set<File>();
  uploadedByIndex.forEach((media, position) => {
    const file = attempted[position];
    if (media && file) landed.add(file);
  });
  return batchFiles.filter((file) => !landed.has(file));
}

/**
 * Which files a refused batch is to be reported against.
 *
 * `uploadMediaBatch` throws only when NOTHING in the batch landed, and it names each file on the way
 * out ({@link MediaBatchError.failures}); anything else that reached here is an error about the
 * batch as a whole, so every file attempted is named. Either way the researcher gets file names and
 * not a stack — this list becomes the sentence on the entry, which is the only thing they can act on.
 */
export function refusedFileNames(error: unknown, attempted: File[]): Array<{ name: string }> {
  if (error instanceof MediaBatchError && error.failures.length) {
    return error.failures.map((failure) => ({ name: failure.name }));
  }
  return attempted.map((file) => ({ name: file.name }));
}

/**
 * What a replayed POST/PATCH is allowed to answer, as far as this pass is concerned.
 *
 * Deliberately as loose as it is: the drain reads exactly two things off it — an `id` and, for the
 * process form, a list of created children — and every other field belongs to whichever route was
 * replayed. Typing it any tighter would be this module claiming to know six response shapes it
 * never reads, and the one shape it does have to reach into is nested (see
 * {@link OutboxEntry.savedIdIn}), which no single literal type can express.
 */
type ReplayAnswer = {
  id?: unknown;
  steps?: Array<{ id: string }>;
} & Record<string, unknown>;

/**
 * The saved row inside a replay's answer: the answer itself, or the one key the entry named.
 *
 * A `savedIdIn` naming a key that is absent, null or not an object answers `undefined` — NOT the
 * top-level answer. Falling back would mean a route that changed its response shape, or a captive
 * portal's 200 page, got read as a save because the fallback happened to find an `id` somewhere
 * else; the caller's whole point in naming the key is that it knows where the row lives.
 */
function savedRow(answer: ReplayAnswer | null | undefined, savedIdIn: string | undefined): ReplayAnswer | undefined {
  if (!answer) return undefined;
  if (!savedIdIn) return answer;
  const nested = answer[savedIdIn];
  return nested && typeof nested === "object" ? (nested as ReplayAnswer) : undefined;
}

export type SyncResult = {
  synced: number;
  failed: number;
  remaining: number;
  stoppedOffline: boolean;
  /**
   * ANOTHER TAB IS DRAINING THIS DEVICE'S OUTBOX, so this pass declined and did nothing. A distinct
   * answer because the two sentences the banner already had are both lies here: "Still no
   * connection" sends a researcher out to look for signal they have, and "Nothing to send" tells
   * them a queue that is mid-flight is empty.
   */
  declined: boolean;
  /** The pass stopped because the sign-in is finished. Nothing was marked. See {@link isCredentialExpiry}. */
  credentialExpired: boolean;
  /** This device could not be read during the pass, so `remaining` is not to be believed. */
  storeUnreadable: boolean;
  /**
   * ENTRIES THAT WENT UP FILED UNDER NOTHING because the picker had nothing in it — one sentence per
   * entry, already written by {@link outboxSentUnfiledMessage}.
   *
   * THE ONE REPORT ON THIS TYPE THAT DESCRIBES A SUCCESS, and it is here precisely because every
   * other signal says the save worked. The entry leaves the queue, `synced` goes up, the banner's
   * count goes down — and a link nobody was ever shown a choice about is silently absent. See
   * {@link OutboxEntry.unfiled} for why that absence is not the same as a deliberate one.
   *
   * OPTIONAL, so that a caller building one of these by hand — the drain spec does, eight times —
   * is not obliged to state an outcome it is not asserting. Absent means the same as empty: no entry
   * in this pass went up unfiled. Every reader takes it as `?? []`.
   */
  sentUnfiled?: string[];
};

let syncing: Promise<SyncResult> | null = null;

/** The Web Lock the pass is held under. One name, one holder, across every tab of this origin. */
const SYNC_LOCK = "field-repo-outbox-sync";

/**
 * Replay the outbox oldest first. Concurrent calls (the `online` event and a "Sync now" click
 * landing together) share one pass — replaying an entry twice would create the record twice.
 *
 * ── TWO GUARDS, AND THE MODULE-LEVEL ONE WAS ONLY EVER HALF THE ANSWER ───────────────────────────
 *
 * `syncing` is kept: it is free and it answers the common case. What it cannot do is span a REALM.
 * `OutboxBanner` is mounted in the protected layout, so every open protected tab has its own module
 * instance, its own `syncing`, its own `online` listener and its own drain-on-mount effect. Two tabs
 * left open on a laptop that rejoins the office wifi both fire `online` in the same task, both read
 * the same queued entry, and both POST it. `Workshop`, `ProductDocumentation`, `ToolDocumentation`
 * and `Process` carry no unique constraint that could catch it, so both writes succeed and the
 * repository holds two records for one save — under one researcher's name, in a government index
 * nobody reconciles.
 *
 * `ifAvailable: true`, NEVER A QUEUED WAIT — the same decision, for the same reasons, as
 * `syncDesignWorkshopDrafts`. A second tab that queued would run the identical pass the moment the
 * first finished, which is the duplicate work merely deferred, and a tab closed mid-pass would leave
 * the other blocked on a lock it can no longer see. Declining is correct: the holder is already
 * sending everything this device holds, including whatever the caller was asking about.
 *
 * WHERE WEB LOCKS DO NOT EXIST the pass runs exactly as it did before — the API needs a secure
 * context, and a researcher on an http:// LAN address must not lose the ability to sync at all to
 * gain protection against a race. On those browsers the read-then-write in {@link updateEntry} is
 * what stops the loser resurrecting the winner's entry.
 */
export function syncOutbox(): Promise<SyncResult> {
  if (syncing) return syncing;
  syncing = runSyncUnderLease().finally(() => {
    syncing = null;
  });
  return syncing;
}

/** What a pass that never ran reports: nothing moved, nothing failed, and this is what is still owed. */
async function declinedResult(): Promise<SyncResult> {
  // Counted honestly rather than reported as zero: another tab is working through exactly this list,
  // and "nothing is outstanding" would let the banner disappear while the work was still in flight.
  const remaining = (await refreshOutbox()).length;
  return {
    synced: 0,
    failed: 0,
    remaining,
    // NOT `stoppedOffline`. The connection is fine; the work is being done by somebody else.
    stoppedOffline: false,
    declined: true,
    credentialExpired: false,
    storeUnreadable: health.readFailedAt !== null,
    sentUnfiled: []
  };
}

async function runSyncUnderLease(): Promise<SyncResult> {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  if (!locks?.request) return runSync();
  const held = await locks.request(SYNC_LOCK, { ifAvailable: true }, async (lock) => (lock ? runSync() : null));
  return held ?? declinedResult();
}

async function runSync(): Promise<SyncResult> {
  const entries = await refreshOutbox();
  let synced = 0;
  let failed = 0;
  let stoppedOffline = false;
  let credentialExpired = false;
  // Sentences, not a count. Each names the entry and which control was empty, and the banner toasts
  // them individually: a number would be the fact without the record it is about.
  const sentUnfiled: string[] = [];

  // NOBODY IS SIGNED IN, SO NOTHING CAN BE SENT — and this is the reachable half of the 401 case:
  // another tab signed out and `AuthProvider.logout` cleared the token both tabs share. Answered
  // without a request because a 401 for every entry is a certainty, and there is no sense spending a
  // round trip per queued file to be told so. (It was also the only defence while `uploadMediaBatch`
  // could not be told to stay put on a 401; both legs pass `redirectOn401: false` now, so this is
  // once again just the cheap answer to a question already settled.)
  if (typeof window !== "undefined" && !getToken()) {
    return {
      synced: 0,
      failed: 0,
      remaining: entries.length,
      stoppedOffline: false,
      declined: false,
      credentialExpired: true,
      storeUnreadable: health.readFailedAt !== null,
      sentUnfiled: []
    };
  }

  for (const entry of entries) {
    // Already triaged. `blocksRetry` — not a bare `entry.failure` test — because a refusal recorded
    // because the client and the server disagree about the SHAPE of the request is waiting on an
    // update, not on the user, and holding it for ever means one queued artisan and their
    // photographs are stranded by a skew that an update has since closed.
    if (blocksRetry({ permanent: entry.failure !== null, skewRun: entry.skewRun })) continue;
    // Everything this pass achieves is recorded on `progress` and written through as it happens, so
    // that an interruption resumes rather than restarts. See the resumability note at the top.
    const progress: OutboxEntry = { ...entry };
    try {
      if (!progress.created) {
        try {
          const answer = await apiFetch<ReplayAnswer>(
            progress.endpoint,
            // NOT `progress.body`: see {@link bodyWithClearances}, which carries the argument.
            { method: progress.method, body: bodyWithClearances(progress) },
            // A BACKGROUND PASS MUST NOT NAVIGATE. This runs on an `online` event and on mount, with
            // nobody having asked for anything: a token that expired while the tab sat open would
            // otherwise throw the researcher off whatever screen they were on, mid-edit, with the
            // outbox never getting the chance to say what happened to it. The token is still cleared
            // by `apiFetch` — that is not this flag — and `AppShell` does the soft redirect for a
            // protected route when `AuthProvider` next notices. The pass stops and the banner asks.
            { redirectOn401: false }
          );
          // Every endpoint the outbox replays answers with the saved record — at the top level, or
          // under the one key the entry named when it was queued ({@link OutboxEntry.savedIdIn}) —
          // so a 2xx carrying no id there did not come from one. A captive portal answering the POST
          // with its own 200 page is the field case, and this app already knows they exist (see the
          // "Sync now" note in OutboxBanner). Taking it as success set `createdId` to null, which
          // skipped the media loop in silence and then discarded the entry: no record written, the
          // photographs deleted, and "sent" reported. An answer we cannot read is not a save — keep
          // the entry and say so.
          const saved = savedRow(answer, progress.savedIdIn);
          const createdId = typeof saved?.id === "string" && saved.id ? saved.id : null;
          if (!createdId) {
            const files = pendingFileCount(progress);
            await markFailure(
              progress,
              "The server accepted this but did not say what it saved, so it cannot be confirmed or its files " +
                `attached. This entry${files ? ` and its ${files} file(s)` : ""} are still on this device. If you were ` +
                "on a wi-fi network that asks you to sign in, connect properly and check whether the record arrived " +
                "before discarding this."
            );
            failed += 1;
            continue;
          }
          progress.created = true;
          progress.createdId = createdId;
          progress.createdStepIds = (saved?.steps ?? []).map((step) => step.id);
        } catch (error) {
          // A 409 is a COLLISION WITH SOMEONE ELSE'S RECORD — a clashing Aadhaar, a craft already
          // named that — not an echo of our own earlier create. Deleting the entry here is what used
          // to throw away the queued record and its photographs and then report them as sent. The
          // researcher is the only one who can tell whether the existing record is theirs, so hand
          // it to them with everything intact.
          if (error instanceof ApiError && error.status === 409) {
            const files = pendingFileCount(progress);
            await markFailure(
              progress,
              `The server refused this as a duplicate. ${error.message} Nothing has been sent and nothing has been ` +
                `thrown away — this entry${files ? ` and its ${files} file(s)` : ""} are still on this device. Open the ` +
                "record it clashes with, carry across anything it is missing, then discard this entry."
            );
            failed += 1;
            continue;
          }
          throw error;
        }
        // Persisted BEFORE a single byte of media moves: from here on the record exists, and a pass
        // that dies during the upload must come back to the media, never to the create.
        //
        // A FALSE HERE MEANS THE ENTRY HAS GONE — another tab filed it and deleted it while this
        // create was in flight, which is the race the lease prevents where Web Locks exist and this
        // catches where they do not. Carrying on would upload the files a second time against a
        // second record; there is nothing left to write the progress to, so leave it alone.
        if (!(await persistProgress(progress))) continue;
      }

      const mediaFailed: Array<{ name: string }> = [];
      const uploaded = new Set(progress.uploadedBatches ?? []);
      /** Set when the entry was deleted underneath this pass — see the progress write below. */
      let entryGone = false;
      if (progress.createdId) {
        for (const [index, batch] of progress.media.entries()) {
          if (uploaded.has(index) || !batch.files.length) continue;
          /*
            A 0-BYTE FILE IS REFUSED HERE, WHERE IT CAN BE NAMED, AND NOT BY THE UPLOADER.

            `lib/media.ts` refuses an empty file before any request is made. It used to do so with a
            bare `Error`, which carries no type anything downstream can classify: `isUnreachable`
            defaults to "the connection is at fault", so the batch escalation stopped the pass as
            offline and came back to the identical empty file on every future connection — the whole
            queue jammed by a file the server never saw. Emptiness is knowable BEFORE the call and
            does not need classifying after it, so the files are separated out and reported as what
            they are.

            THAT REFUSAL IS TYPED NOW (`LocalRefusalError`, `lib/failureTriage.ts`), and this split
            stays anyway. The type is the backstop for a caller that forgets; not sending a file the
            device already knows will be refused is still better than sending it, and only this
            branch can name the empty ones in the sentence the researcher reads.

            Not marked as uploaded: nothing was uploaded. The entry is failure-marked below either
            way, and a person who presses Try again gets the same honest answer rather than a jam.
          */
          const { sendable, empty } = splitUnsendableFiles(batch.files);
          mediaFailed.push(...empty.map((file) => ({ name: file.name })));
          if (!sendable.length) continue;
          // A step batch has to wait for the server to mint the step; if the create came back with
          // fewer steps than were queued, say so rather than silently attaching to the wrong one.
          const linkedRecordId =
            batch.stepIndex === undefined ? progress.createdId : (progress.createdStepIds?.[batch.stepIndex] ?? null);
          if (!linkedRecordId) {
            // `sendable`, not `batch.files`: any empty ones were named a few lines up and must not
            // be counted twice in the sentence the researcher reads.
            mediaFailed.push(...sendable.map((file) => ({ name: file.name })));
            continue;
          }
          /*
            THE MEDIA LEG TRIAGES ITS OWN FAILURE, WHICH IS THE SHIP-BLOCKER THIS CLOSES.

            `uploadMediaBatch` escalates a batch in which NOTHING landed, as a `MediaBatchError` —
            not an `ApiError`. The pass-level catch below asks whether it is worth retrying, and the
            only test this file had answered "yes" for anything that is not an `ApiError`, so the
            pass broke as if the device
            were offline: `markFailure` was never reached, the entry retried for ever, EVERY ENTRY
            BEHIND IT NEVER DRAINED, and the banner said "Still no connection" (false) while
            offering no Discard, because Discard is drawn from a failure record that was never
            written. A refused photograph is not a signal problem and must not be retried like one.

            The split is the same one the pass-level catch makes and the same one the sibling in
            `lib/designWorkshopStore.ts` makes: DID ANYTHING REACH THE SERVER. `isUnreachable`
            follows `cause`, so a connection that genuinely dropped still stops the pass by
            re-throwing and changes nothing; an answer is recorded against the files and the loop
            carries on to the next entry.

            THE CREDENTIAL IS CHECKED FIRST because a 401 arrives wrapped here too, and it is about
            the session rather than the file — marking these photographs refused would be the exact
            defect this wave exists to remove.

            AND THE RETRYABLE SET IS THE CREATE LEG'S SET, which for a while it was not. Re-throwing
            only what `isUnreachable` recognises leaves a 503 in a deploy window and a 429 from a
            rate limiter as PERMANENT refusals of the photograph — while the identical status on the
            record request a few lines up is "try again later". `underlyingIsTransient` covers those,
            and it is asked of the error DIRECTLY rather than of `underlyingError(error)`: it follows
            `cause` itself, so hand-unwrapping first is at best redundant and at worst the next place
            two readings drift apart. The sibling `mediaRefusal` in `lib/designWorkshopStore.ts` keeps
            408 and 429 retryable for the same reason.

            IT IS THE `underlying…` ONE AND NOT `isTransient`, WHICH ANSWERS DIFFERENTLY HERE. Every
            failure this line sees is a `MediaBatchError`, and `isTransient` deliberately does not
            open one: it is the reading `FieldInput`'s catch needs, where a false discards a capture
            instead of marking it. Asked here it would answer "worth retrying" to a 415 and this
            branch would re-throw it, which is the ship-blocker verbatim. `lib/failureTriage.ts`
            carries the argument for why the two exist rather than one.

            ALL THREE TESTS ARE KEPT even though the triage table could be read as one question. They
            are three different sentences to the researcher — sign in again, you have no signal, the
            repository is busy — and this line is the place the pass decides which of the three it
            is about to stop with. Collapsing them into `retry !== "never"` would be smaller and
            would lose exactly the distinction the file above spends four hundred lines defending.
          */
          let result: Awaited<ReturnType<typeof uploadMediaBatch>>;
          try {
            result = await uploadMediaBatch({
              files: sendable,
              linkedRecordType: batch.linkedRecordType,
              linkedRecordId,
              caption: batch.caption,
              location: batch.location,
              recordedAt: batch.recordedAt ?? undefined,
              recordedTimezone: batch.recordedTimezone ?? undefined,
              extraMetadata: batch.extraMetadata,
              transcribeAudio: batch.transcribeAudio ?? true,
              // THE SAME REASON AS THE CREATE LEG'S, AND THE HALF THAT USED TO BE MISSING. This pass
              // runs on an `online` event and on mount with nobody having asked for anything, and an
              // entry that is being RESUMED (its record already on the server) sends its very first
              // request from right here — so a seven-day token expiring in that window navigated the
              // researcher to /login mid-edit and took the unsaved screen with it. The token is still
              // cleared by `apiFetch`; the pass stops at `isCredentialExpiry` below and the banner
              // asks for a sign-in, with nothing marked.
              redirectOn401: false
            });
          } catch (error) {
            if (isCredentialExpiry(error) || isUnreachable(error) || underlyingIsTransient(error)) throw error;
            // The server answered and refused the lot. Named per file where the uploader said which.
            mediaFailed.push(...refusedFileNames(error, sendable));
            continue;
          }
          mediaFailed.push(...result.failed);
          /*
            A PARTLY REFUSED BATCH IS NARROWED, NOT RECORDED AS DONE.

            `uploadMediaBatch` throws only when NOTHING landed, so a return can still be carrying a
            refusal — 413 on the one photograph over the size limit, 415 on the one the phone wrote
            as heic. Recording the whole batch in `uploadedBatches` was right about the danger (a
            replay would upload the files that DID land a second time) and wrong about the remedy:
            the next pass skips a recorded batch outright, so once `retryOutboxEntry` could clear the
            failure the refused file was deleted with the entry and the entry reported SENT. Bytes
            that cannot be recreated, destroyed by the button drawn as the safe one.

            So the batch keeps exactly what did not land — see {@link outstandingFiles}, which is
            where that is worked out and why it is read by position rather than by file name.
          */
          const outstanding = outstandingFiles(batch.files, sendable, result.uploadedByIndex);
          if (outstanding.length) {
            // Re-sending exactly the set that never landed cannot duplicate the set that did, which
            // is the whole reason the index may be left out of `uploaded`.
            progress.media = progress.media.map((queued, position) =>
              position === index ? { ...queued, files: outstanding } : queued
            );
          } else {
            uploaded.add(index);
          }
          progress.uploadedBatches = Array.from(uploaded);
          // Gone from under us: another tab finished this entry. Stop, and do not count it again.
          if (!(await persistProgress(progress))) {
            entryGone = true;
            break;
          }
        }
      }
      if (entryGone) continue;
      // The record exists now, so this cannot end in a discard: dropping the entry would delete the
      // refused files and report the save complete. What the entry becomes instead is a list of what
      // is still owed — `created` is on disk, so a later pass resumes at the media rather than
      // filing a second record, and the batches were narrowed above to exactly the files that never
      // landed. That is what makes "Try again" on this sentence a real offer and not a deletion.
      if (mediaFailed.length) {
        await markFailure(
          progress,
          `Saved, but ${mediaFailed.length} file(s) did not upload: ${mediaFailed.map((f) => f.name).join(", ")}. ` +
            "Nothing has been thrown away — they are still on this device, and Try again re-sends only those. If " +
            "they keep being refused, re-attach them on the record."
        );
        failed += 1;
        await refreshOutbox();
        continue;
      }

      /*
        THE RECORD LANDED, AND ONE OF ITS LINK COLUMNS IS EMPTY BECAUSE THE PICKER WAS.

        Read BEFORE the entry is deleted, because after that there is nothing left to read it off.
        This is the third of the three outcomes and the only one that ends in success, which is why
        it needs saying at all: every visible sign — the entry leaving the queue, the count dropping,
        the "sent" toast — says the record arrived intact. It did. It arrived unfiled.
      */
      const unfiledNouns = emptyPickerKeys(progress).map(referenceFieldNoun);
      if (unfiledNouns.length > 0) sentUnfiled.push(outboxSentUnfiledMessage(progress.label, unfiledNouns));
      await discardOutboxEntry(progress.id!);
      synced += 1;
    } catch (error) {
      // THE CREDENTIAL, NOT THE ENTRY, AND IT IS ASKED FIRST. A 401 is not transient (the server
      // answered) so it used to fall through to `markFailure` and mark this entry — and then the
      // next, and the next — permanently refused, with nothing in this repository able to clear the
      // mark afterwards. It is also asked FIRST, before the retry test below, and that ordering is
      // load-bearing for one reason only: a 401 must stop the pass WITHOUT marking anything, and
      // `isCredentialExpiry` is the only test that says so. The reason this comment used to give —
      // that a 401 arriving wrapped from the media leg is not an `ApiError`, so the retry test would
      // answer "Still no connection" — no longer holds: `underlyingIsTransient` opens the wrapper and
      // classifies the 401 as `credential-expired`, which is not in its set either way. The order is
      // kept because the sentence it produces is the point, not because a wrapper could slip past.
      // Nothing is marked and nothing is lost; the banner asks for a sign-in and `retryOutboxEntry`
      // is not even needed, because no failure was written.
      if (isCredentialExpiry(error)) {
        credentialExpired = true;
        break;
      }
      // The opened reading again, for the same reason as the media leg above: a `MediaBatchError`
      // can reach this catch, and what it wraps is the only thing worth asking about.
      if (underlyingIsTransient(error)) {
        stoppedOffline = true;
        break; // Still offline (or the API is down) — everything behind this stays queued.
      }
      // A SCHEMA REFUSAL IS NOT THE USER'S FAULT AND MUST NOT BE HELD FOR EVER. The server could not
      // read the shape of what this build sent, so there is nothing on the record for anybody to
      // correct and no reason to keep the entry — and its photographs — parked once one of the two
      // has been updated. Recorded, shown, and re-attempted by the next app run: see `blocksRetry`.
      // A dialect mismatch met while uploading a photograph arrives wrapped in a `MediaBatchError`,
      // and reading the wrapper turns "these two builds disagree, wait for an update" into "you got
      // this wrong, fix it" — a sentence with nothing behind it that no edit can ever clear. That
      // unwrapping is no longer this branch's business: `lib/failureTriage.ts` follows `cause` once,
      // for every classifier, so the only thing still needed here is the ANSWER ITSELF, to quote.
      // Its sentence names the key the two builds disagree about, which is the one piece of
      // information that tells whoever runs the repository what to update.
      const answered = schemaRefusalError(error);
      if (answered) {
        const files = pendingFileCount(progress);
        await markFailure(
          progress,
          `This copy of the app and the repository are out of step, so the repository could not read what was sent: ` +
            `${answered.message} Nothing you entered is wrong and nothing has been thrown away — this entry` +
            `${files ? ` and its ${files} file(s)` : ""} stays on this device and will be sent by itself once one of ` +
            "the two has been updated. You do not have to do anything.",
          APP_RUN_ID
        );
        failed += 1;
        continue;
      }
      /*
        A REFERENCE THAT IS NOT THERE — R7's after-the-drain half, and the reason this arm sits above
        the plain refusal below rather than beside it.

        Everything under this point is a refusal waiting on a PERSON's decision. This one is waiting
        on one click, and until it had its own arm it was indistinguishable from the others: parked
        for ever behind a *Try again* that fetches the identical 404 and a *Discard* that destroys the
        only copy of the record and its photographs. The entry is still marked and `blocksRetry` still
        holds it — retrying unchanged really is pointless — but it is marked WITH THE FIELD, which is
        what lets `OutboxBanner` draw the third button.

        THE CANDIDATE LIST IS CHECKED, NOT JUST THE STATUS. A 404 on an entry whose body names no
        reference at all cannot be re-pointed at anything, so it falls through and takes the ordinary
        sentence. Offering a re-pick panel with nothing in it would be a second dead end.
      */
      const candidates = isDanglingReference(error) ? danglingCandidates(progress.body) : [];
      if (candidates.length > 0) {
        await markFailure(
          progress,
          outboxDanglingSentence(
            error instanceof Error ? error.message : "",
            candidates.map(referenceFieldNoun),
            pendingFileCount(progress),
            progress.method === "PATCH"
          ),
          null,
          candidates.join(",")
        );
        failed += 1;
        continue;
      }
      await markFailure(progress, error instanceof Error ? error.message : "The server rejected this entry.");
      failed += 1;
    }
  }

  const remaining = (await refreshOutbox()).length;
  // `storeUnreadable` travels WITH the count, because the count is what it disqualifies: a read that
  // failed reports zero remaining, and zero remaining is what the banner used to answer a click with
  // "Nothing to send" — an affirmative all-clear produced by a device that cannot see its own queue.
  return {
    synced,
    failed,
    remaining,
    stoppedOffline,
    declined: false,
    credentialExpired,
    storeUnreadable: health.readFailedAt !== null,
    sentUnfiled
  };
}

// ---------------------------------------------------------------------------
// The one call a form makes
// ---------------------------------------------------------------------------

export type SaveOutcome<T> = { queued: true } | { queued: false; saved: T };

/**
 * Save a record, or queue it if this device has no connection.
 *
 * Deliberately does NOT upload the media when online: the caller keeps its own `uploadMediaBatch`
 * call so its progress bar, per-file retry and eager-staging claim all behave exactly as before.
 * `media` is taken here only so that a QUEUED save can carry the files into IndexedDB with it.
 */
export async function saveOrQueue<T extends { id: string }>({
  label,
  endpoint,
  method,
  body,
  media
}: {
  label: string;
  endpoint: string;
  method: "POST" | "PATCH";
  body: unknown;
  /** Batches to persist WITH a queued save. Ignored when the save goes through online. */
  media?: OutboxMediaBatch[];
}): Promise<SaveOutcome<T>> {
  const payload = JSON.stringify(body);
  const queue = async () => {
    await queueOffline({ label, endpoint, method, body: payload, media: (media ?? []).filter((batch) => batch.files.length) });
    return { queued: true } as const;
  };

  // Known-offline: do not burn a request (and a 30s timeout) to learn what the browser already knows.
  if (typeof navigator !== "undefined" && navigator.onLine === false) return queue();

  try {
    return { queued: false, saved: await apiFetch<T>(endpoint, { method, body: payload }) };
  } catch (error) {
    // Only a request that never reached the server may be queued. A 4xx means the server saw it and
    // said no; queueing that would replay a rejection forever and hide the real problem from the user.
    if (isTransient(error) && !(error instanceof ApiError)) return queue();
    throw error;
  }
}
