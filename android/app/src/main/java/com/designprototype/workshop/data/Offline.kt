package com.designprototype.workshop.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline outbox. When the device has no connection, a new record (and copies of its captured media)
 * is persisted to local app storage instead of being sent to the server. A sync pass (on reconnect or
 * app start) replays every queued entry — creating the record, then uploading its media — and only
 * then removes the local copy, so field data is never lost in transit.
 */

/** A captured file copied into local app storage, plus the params needed to upload it later. */
@Serializable
data class PendingMedia(
    val localPath: String,
    val originalFilename: String,
    val mimeType: String,
    val mediaType: String,
    val caption: String? = null,
    val recordName: String? = null,
    val customSegment: String? = null,
    val overrideBaseName: String? = null,
    val batchIndex: Int = 1,
    val stageStep: Int? = null,
    val processing: List<String>? = null,
    // Override the link target type (e.g. "processstep" for a process step's media). Null = the entry's
    // own type. `stepIndex` (process only) selects which created step's server id to attach to on sync.
    val linkedType: String? = null,
    val stepIndex: Int? = null,
    /**
     * WHAT THIS FILE IS FOR, where it is not just another photograph of the record — today only
     * [MEASUREMENT_GRID_PURPOSE]. Sent as `extraMetadata.purpose` by [WorkshopRepository.uploadLocalFile].
     *
     * IT HAS TO SURVIVE THE OUTBOX, and for a while it did not. The marker is written by the form's
     * grid section into `MediaCaptureState.purposes` and read by `uploadAttachments` — which is the
     * ONLINE path. A new product or tool saved with no signal goes through `trySaveOffline` ->
     * `queueOffline` instead and never reaches that function, so the grid shot was staged here
     * unmarked and uploaded unmarked on reconnect. Offline is this app's primary field path: the
     * designer measuring an object on graph paper is standing in a cluster with no bars, which is the
     * exact case the marker exists for. The .docx handed to a Development Commissioner's office went
     * on printing a sheet of ruled paper captioned as the tool, and neither of the server's
     * transitional clauses covers this path — [uploadLocalFile] builds its own `mediaFilename(...)`
     * (never `grid-`/`measure-grid-`) and the caption it carries is "Field media for X".
     *
     * DEFAULTED, so an entry queued by an older build still decodes — the same reason every field in
     * [PendingEntry]'s replay-progress block is defaulted, and it matters for the same reason: the
     * queue on a device that has been offline for a fortnight predates the build that reads it.
     */
    val purpose: String? = null
)

/** One queued create: the record type, its serialized create request, and the media to attach after. */
@Serializable
data class PendingEntry(
    val id: String,
    val type: String,
    val payloadJson: String,
    val label: String,
    val media: List<PendingMedia> = emptyList(),
    val createdAt: String,
    /**
     * THE RECORD THIS ENTRY IS AIMED AT, when it is not creating one. Null = a create, which is
     * every entry any build before this one ever wrote.
     *
     * Two things queue against a record that already exists, and both were losing work outright:
     *
     *  - AN EDIT. `trySaveOffline` used to answer false for `isEdit` and the form then fell through
     *    to the online path, which threw an IOException that reached the designer as a raw OkHttp
     *    message. NOTHING WAS PERSISTED ANYWHERE — a record form has no draft file — so twenty
     *    artisan corrections made on the bus home were twenty corrections gone. With this set, the
     *    replay calls the record's UPDATE route instead of its create route (see
     *    `WorkshopRepository.createFromEntry`), which is the only difference between the two.
     *
     *  - MEDIA WITH NOWHERE ELSE TO GO. `uploadAttachments` runs AFTER the record POST has landed,
     *    so a connection that dies in between left the record saved and the photographs held only
     *    as content Uris in Compose state, pointed at `cacheDir` — and told the designer to re-open
     *    the record and re-attach them, which was an instruction they could not carry out: the Uris
     *    die with the screen and `cacheDir` is reclaimed without warning. Those files are now staged
     *    and queued against the saved record's id with an empty [payloadJson] and [type]
     *    [OFFLINE_MEDIA_ONLY], so the replay attaches them and creates nothing.
     *
     * DEFAULTED, for the reason every field in this class is defaulted: the queue on a device that
     * has been offline for a fortnight was written by the build that was installed a fortnight ago.
     */
    val targetId: String? = null,
    /**
     * REPLAY PROGRESS, written back as each step lands. All defaulted, so an entry queued by an
     * older build decodes into "nothing has happened yet" and replays exactly as it used to.
     *
     * [createdId] is the load-bearing one: non-null means the record IS on the server, and re-sending
     * the body would make a SECOND one. A replay that finds it set skips the create and goes straight
     * to the media. Without it, every media failure re-created the record on the next pass — once per
     * pass, for as long as the signal stayed bad, which is the whole reason the entry is here.
     */
    val createdId: String? = null,
    /** A created process's step ids in submit order, for the media that attaches to a `processstep`. */
    val createdStepIds: List<String> = emptyList(),
    /** Indices into [media] whose bytes are already on the server. Never uploaded twice. */
    val uploadedMedia: List<Int> = emptyList(),
    /**
     * The server's final answer, when it refused this entry for a reason no retry will change.
     *
     * The entry and its copied files STAY on the device — being refused is not a reason to destroy
     * the only copy of a day's fieldwork — and the sync pass steps over it instead of stopping, so
     * one unacceptable record cannot strand the records queued behind it.
     */
    val failure: String? = null,
    /** When [failure] was recorded, ISO-8601. */
    val failedAt: String? = null,
    /**
     * The app run that recorded a refusal ONLY AN UPDATE CAN CLEAR. Null on every other failure.
     *
     * The same field, for the same reason, as [StageSyncRecord.skewRun] — and it belongs here too
     * because a queued record is posted as an `APIModel` (`extra="forbid"`), so a handset that has
     * learned a new key before the API has gets `extra_forbidden` on a create exactly as the design
     * workshop pass does. Marked plainly permanent, that stranded a queued artisan AND their
     * photographs for good, on the device least able to be told to clear its storage.
     *
     * Defaulted, so an entry queued by an earlier build decodes with null and behaves exactly as it
     * did — sticking until a person deals with it. See [blocksRetry] for the whole policy.
     */
    val skewRun: String? = null,
    /**
     * THE SERVER SAYS SOMEBODY ELSE'S RECORD ALREADY OCCUPIES THIS — an answered 409, and an
     * outcome of its own rather than one more anonymous [failure].
     *
     * ── WHY A SIXTH KIND OF "NOT SYNCED", WHEN FIVE ALREADY SEEMS LIKE PLENTY ──────────────────
     *
     * This queue tells five apart today, and each earns its own sentence because each ends
     * differently for the designer holding the phone:
     *
     *   1. WAITING              — [failure] null. A connection moves it. Cloud-off icon, no action.
     *   2. TRANSIENT            — nothing is written down at all: `replayEntry` answers `Retry`, the
     *                             pass stops, the queue keeps its order. `WorkshopRepository.isTransient`.
     *   3. REFUSED, ON A PERSON — [failure] set, [skewRun] null. A bad field, a permission, a record
     *                             the office deleted. [blocksRetry] parks it until somebody taps Try again.
     *   4. REFUSED, ON A BUILD  — [skewRun] set. The two builds disagree about the SHAPE of the
     *                             request; nobody typed anything wrong, and the next app run re-attempts it.
     *   5. SAVED, FILES REFUSED — [createdId] set with files still outstanding. The record IS on the
     *                             server, so the entry may never be replayed and may never be deleted.
     *
     * A 409 is none of those. Nothing on the record is wrong, no update to either build will clear
     * it, and it is not waiting on a permission: the register simply already holds a record that
     * occupies the same identity — a clashing Aadhaar (`backend/app/api/routes/artisans.py`, whose
     * detail NAMES the existing artisan and their place), a craft already called that
     * (`backend/app/api/routes/crafts.py`), an interview that already exists for this exact set of
     * artisans (`backend/app/api/routes/questionnaire.py::_DUPLICATE_SET_DETAIL`). The only person
     * who can say whether that existing record is this same fieldwork or somebody else's is the
     * designer, and the only way through it is a comparison they make with their own eyes. Folded
     * into kind 3 it read as "the server rejected this record" beside a Try again button that could
     * only ever fetch the identical answer — a dead end wearing the costume of a remedy.
     *
     * ── AND WHY THE ANSWER IS *NEVER* "OUR CREATE MUST HAVE LANDED" ────────────────────────────
     *
     * `frontend/lib/offline.ts` opens with the incident, in these words: the web outbox "used to read
     * a 409 as 'the create already landed and we simply lost the response', and drop the entry and
     * its files as sent. No endpoint in this API means that" — so the one answer that means somebody
     * else's record collides with yours "was destroying the record AND the photographs and reporting
     * success". The lost-response case it was reaching for is [createdId]'s job, which KNOWS rather
     * than guesses. Nothing on this path may delete: not the entry, not the staged bytes under
     * `outbox/media/`. [OfflineOutbox.discard] stays the only door, and only a person opens it.
     *
     * DEFAULTED, like every field in this class and for the identical reason: the queue on a handset
     * that has been out of coverage for a fortnight was written by the build installed a fortnight
     * ago, and an entry from it must decode into the behaviour it was queued under — an ordinary
     * refusal that waits for a person — rather than into a claim this build invented for it.
     */
    val conflict: Boolean = false,
    /**
     * WHY A CLOSED-LIST FIELD ON THIS ENTRY IS EMPTY — keyed by the wire name of the column.
     *
     * ── THE TWO ABSENCES THIS QUEUE COULD NOT TELL APART, AND WHAT IT COST ────────────────────
     *
     * A record queued with no workshop against it has always been ONE thing on disk: a `null`, or a
     * key that was never written. But it is two completely different acts by the person who made it:
     *
     *   * THEY CHOSE NOTHING. The picker offered four workshops, the designer opened it, and picked
     *     the "None" row because this artisan genuinely belongs to no design workshop.
     *     [UNFILED_BY_CHOICE]. That is a decision, and the server has to be TOLD it: on a correction
     *     the column already holds a value, and "unfile" is only expressible as an explicit
     *     `{"designWorkshopId": null}` — `services/records.CLEARABLE_KEYS` exists for precisely this
     *     and says what happens without it, "the save would return 200, the form would show it
     *     unfiled, and the old link would survive in the database".
     *
     *   * THERE WAS NOTHING TO CHOOSE. The device was in a courtyard with no signal, the access list
     *     is never cached (`WorkshopRepository.kt:3918-3923` — "a picker is the one control that
     *     must not offer what it cannot honour"), so the picker was EMPTY. [UNFILED_NO_OPTIONS]. The
     *     designer made no decision at all, and reading their empty box as one is how a correction
     *     composed on the bus home silently strips a link nobody was ever shown. So this absence
     *     sends NOTHING for the column and the stored value stands.
     *
     * That is R1 — *empty means everything BY ABSENCE, never by an all-ticked state* — with its sign
     * flipped for a form field: absence means "no change" UNLESS it was chosen. Collapsing the two
     * gives the column two spellings for one state and no way to tell a default from a decision,
     * which is the failure R1 is written against.
     *
     * ── AND IT IS NOT THE SAME FAILURE AS A DANGLING ID ───────────────────────────────────────
     *
     * See [danglingField]. An empty picker is fixed BEFORE the save, by offering something answerable
     * or standing the field down; a dangling id is fixed AFTER the drain, on the record already on
     * the device, by re-picking. R7: they must never be collapsed into one message.
     *
     * DEFAULTED TO EMPTY, and that is the whole compatibility story: an entry queued by any earlier
     * build carries no map, `clearedLinkKeys` is therefore empty, and the replay omits every link
     * column exactly as it does today. An old queued correction cannot be made to clear a workshop
     * link this build has no evidence anybody asked to clear.
     */
    val unfiled: Map<String, String> = emptyMap(),
    /**
     * THIS RECORD POINTS AT AN ID THE SERVER DOES NOT HAVE — a fifth outcome beside the four above,
     * and the one this queue had no word for. Null on every other failure.
     *
     * ── WHY IT IS NOT ONE MORE KIND-3 REFUSAL ─────────────────────────────────────────────────
     *
     * A 404 — or the 422 an existence check produces — is not transient
     * ([WorkshopRepository.isTransient]), not a 409 (`isConflictRefusal`) and not `extra_forbidden`
     * ([ApiRefusal.schemaSkew]). So it fell to kind 3, REFUSED-ON-A-PERSON, and was parked for ever
     * behind two buttons: *Try again*, which fetches the identical 404, and *Throw away*, which
     * destroys the last copy of the record and its photographs. `frontend/lib/offline.ts` has the
     * words for that shape, written about the 409 it was added to close: *"a Try again button that
     * could only ever fetch the identical answer — a dead end wearing the costume of a remedy."*
     * The 409 got its own arm and a route out. A dangling foreign key did not — and neither the tray
     * nor `outboxFailureRows` said WHICH field's id was missing, which is the one fact that makes the
     * remedy obvious.
     *
     * The remedy exists and is small, because nothing is lost: the record is still on this phone with
     * its payload intact, and exactly one field in it is wrong. `WorkshopRepository.repickOutboxEntry`
     * rewrites that one key and unparks the entry.
     *
     * ── WHAT IS ACTUALLY STORED, AND WHY IT CAN NAME MORE THAN ONE ────────────────────────────
     *
     * The wire name of the column, e.g. `designWorkshopId` — or, when the server's answer does not
     * name a field and the payload carries several ids that could be at fault, EVERY candidate,
     * comma-separated, in [REFERENCE_FIELD_NOUNS] order. Read it back with [danglingKeys].
     *
     * Naming all of them is the honest answer and picking one would not be. A 404 body from
     * `records.require_record` is the string "Record not found" and a 404 from
     * `design_workshops.load_workshop_or_404` is the same string BY DESIGN — *"a 403 would confirm
     * that the id exists to precisely the caller being turned away"* — so on an artisan carrying both
     * a `workshopId` and a `designWorkshopId` there is nothing in the answer that separates them.
     * `DwResumedCreate.Ambiguous` refuses the identical coin toss for the identical reason: choosing
     * by plausibility would put fieldwork in the wrong place under a sentence claiming certainty.
     *
     * DEFAULTED TO NULL, so an entry queued by an earlier build decodes into the behaviour it was
     * queued under — an ordinary refusal that waits for a person — rather than into a claim about a
     * missing reference that this build invented on its behalf. The same rule, for the same reason,
     * as [conflict] and [skewRun].
     */
    val danglingField: String? = null,
    /**
     * THE ACCOUNT THAT CAPTURED THIS ENTRY, so a shared field handset never sends one designer's
     * fieldwork under another's token. Null = captured before this field existed.
     *
     * ── THE SAME BOUNDARY AS [WorkshopDraft.ownerUserId], ONE QUEUE ALONG ─────────────────────
     *
     * The design-workshop side already carries this stamp and `dwDraftIsForAnotherAccount` already
     * enforces it, and the paragraph on `WorkshopSyncEngine.syncOneWorkshop` names exactly what its
     * absence costs. This queue had the identical hole with a shorter fuse. Two designers share one
     * handset — the case both fields are written for. A captures a fortnight of artisans, products
     * and photographs with no signal; A signs out, and `logout()` clears the token store and the
     * form cache and NOTHING ELSE, so the queue and its staged bytes stay on disk. B signs in, and
     * `MainActivity`'s sign-in effect calls `syncOutbox` within the second. Every one of A's queued
     * records was then created on the server under B's token: B is `createdById`, the rows land in
     * B's lists, and A has to be granted access to their own fieldwork — which is the wrong
     * `createdById` outcome `WorkshopDraft.ownerUserId`'s own KDoc names, arriving by the door
     * nobody had shut.
     *
     * IT IS WORSE HERE THAN ON A DRAFT, in one respect. A workshop draft is a document a person can
     * be walked back through; a queued record is DELETED the moment it syncs ([OfflineOutbox.remove]),
     * taking its staged captures with it. By the time anybody notices the attribution, the only copy
     * of the evidence is a row in somebody else's list.
     *
     * READ BY `WorkshopRepository.syncOutbox` AND BY NOTHING ELSE, which is the mistake this field
     * exists not to repeat: `WorkshopDraft.ownerUserId` was written to disk and read by nothing for
     * the whole of its first life, and a permission boundary that is only recorded is not a boundary.
     *
     * DEFAULTED TO NULL, for the reason every field in this class is defaulted and with the same
     * consequence spelled out on [conflict] and [danglingField]: the queue on a handset that has
     * been out of coverage for a fortnight was written by the build installed a fortnight ago, and
     * an entry from it must replay under the behaviour it was queued under. Null therefore PASSES —
     * exactly as a null owner passes `dwDraftIsForAnotherAccount` — because refusing it would be a
     * silent, total drain stop on every handset upgraded into this build, which strands real
     * fieldwork rather than merely misfiling it.
     */
    val ownerUserId: String? = null,
    /**
     * THE IDEMPOTENCY KEY THIS CREATE CARRIES — minted once when the entry was queued, sent on every
     * replay, so a create whose answer was lost lands exactly once.
     *
     * ── THE DUPLICATE [createdId] CANNOT SEE ───────────────────────────────────────────────────
     *
     * [createdId] one field up is proof this handset RECEIVED an answer, and `replayEntry` skips the
     * create when it is set. What it cannot see is the case where no answer ever arrived: the POST
     * landed, the server wrote the row, and the reply died in a tunnel. Nothing was learned here, the
     * entry is still queued, and the next pass files a SECOND government record for one save — under
     * one designer's name, in an index nobody reconciles. The web outbox names the missing piece by
     * name (`frontend/lib/offline.ts`, `persistProgress`): *"a few milliseconds of IndexedDB is as
     * small as that window gets without idempotency keys on the API."*
     *
     * THE TWO COMPOSE RATHER THAN COMPETE, and the order is deliberate. [createdId] is checked first
     * because it is free — a local fact, no request at all — and answers the same-device,
     * same-profile replay. This key answers the one [createdId] structurally cannot: a lost reply, a
     * queue restored onto a second handset, or a drain after a sign-out and back in. The server, not
     * this file, then decides that a create it has already performed is this same create, and answers
     * with the row it made the first time.
     *
     * ── WHERE IT IS AND IS NOT SENT ───────────────────────────────────────────────────────────
     *
     * ONLY ON CREATES OF THE FOUR RECORD TYPES THE SERVER GUARDS — workshop, product, tool, process.
     * `artisan` and `craft` are deliberately absent: both are ALREADY idempotent under a better key
     * than this one could be (`Artisan.aadhaarNumber @unique` with a pre-write 409 that NAMES the
     * clashing artisan; `Craft.name @unique`), and a second mechanism beside them would be two guards
     * that can disagree about what a duplicate is. See [WorkshopRepository.queueOfflineEntry], which
     * holds the list.
     *
     * NEVER ON A CORRECTION. The server's UPDATE schemas do not declare `clientKey` and every request
     * body there is `extra="forbid"`, so a correction carrying one would be a 422 with
     * `extra_forbidden` — read by this queue as a disagreement between BUILDS and re-attempted once
     * per app run, for ever, on a prepaid connection. The mint is gated on [targetId] being null, and
     * [WorkshopRepository.writeFromEntry]'s correction branch never reads this field.
     *
     * DEFAULTED TO NULL, for the reason every field in this class is defaulted: an entry queued by an
     * earlier build decodes with null, sends no key, and replays exactly as it always did.
     */
    val clientKey: String? = null,
) {
    /**
     * The link columns this entry's author DELIBERATELY emptied, and which a replay must therefore
     * send as an explicit `null`. See [unfiled].
     *
     * Empty for every entry from every earlier build, which is what makes widening the class safe:
     * no evidence, no clearance, and the replay behaves exactly as it did before this field existed.
     */
    val clearedLinkKeys: Set<String>
        get() = unfiled.filterValues { it == UNFILED_BY_CHOICE }.keys

    /** The columns that were empty because the picker had nothing in it. See [unfiled]. */
    val emptyPickerKeys: Set<String>
        get() = unfiled.filterValues { it == UNFILED_NO_OPTIONS }.keys

    /** [danglingField] read back as the list it is. Empty when nothing is dangling. */
    val danglingKeys: List<String>
        get() = danglingField?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/**
 * The designer opened the picker and chose the "none" row. A DECISION, and the wire has to carry it.
 *
 * Stored as a plain string rather than an enum for the reason [PendingMedia.purpose] is one: this
 * value is written into a JSON file that a LATER build may write and an EARLIER build may read (an
 * APK downgrade is an ordinary event in this fleet — see `WorkshopDraftDowngradeTest`), and an enum
 * name kotlinx cannot resolve throws, which `OfflineOutbox.read` can only treat as a damaged queue.
 * A string it does not recognise is simply not one of the two it acts on, and the entry replays as
 * an old one would. See `designWorkshopPrefillNote` for the same rule stated about a served value:
 * *"an unknown value must never be dressed as one of the two known ones"*.
 */
const val UNFILED_BY_CHOICE = "chosen"

/** The picker was empty when this was filled in, so nothing could be chosen. See [PendingEntry.unfiled]. */
const val UNFILED_NO_OPTIONS = "noOptions"

/**
 * EVERY FOREIGN KEY A QUEUED RECORD CAN POINT AT, and the noun each one is called by on screen.
 *
 * The keys are `services/records.CLEARABLE_KEYS` minus the two identity numbers, which are values
 * rather than references and can never 404. The nouns are what the forms already call these
 * controls, because the sentence a designer reads has to name the box they are about to reopen —
 * "a design & prototype workshop", not "designWorkshopId".
 *
 * ORDER IS LOAD-BEARING and that is why this is a `linkedMapOf`. When more than one candidate
 * survives, [outboxDanglingSentence] lists them in this order, so two entries refused the same way
 * never word the same ambiguity two different ways.
 */
internal val REFERENCE_FIELD_NOUNS: Map<String, String> = linkedMapOf(
    "designWorkshopId" to "design & prototype workshop",
    "workshopId" to "workshop",
    "artisanId" to "artisan",
    "craftId" to "craft",
    "productId" to "product",
    "toolId" to "toolkit",
    "processId" to "process",
    "questionnaireInterviewId" to "interview",
    "locationId" to "place",
)

/**
 * The two columns that file a record under a workshop, and the only two a picker on a record form
 * can leave empty.
 *
 * Both, together, always — §2.7 of the dropdown design says so in as many words: *"a sentinel on the
 * wire for BOTH COLUMNS AT ONCE"*. `WorkshopPickerState.value()` and
 * `DesignWorkshopPickerState.value()` have the identical shape and the identical consequence, so a
 * fix that reached one of them would leave a designer clearing one box successfully and the other
 * box silently, on the same form, one line apart.
 */
internal val WORKSHOP_LINK_KEYS: Set<String> = setOf("designWorkshopId", "workshopId")

/**
 * WHICH OF THE TWO ABSENCES A WORKSHOP BOX IS IN — the rule that decides it, in ONE place, for both
 * pickers and both columns.
 *
 * ── WHY IT IS NOT SPELLED AT THE CALL SITES ───────────────────────────────────────────────────
 *
 * Because it is four lines of reasoning about a value that DESTROYS A LINK when it is wrong in one
 * direction and merely stays quiet when it is wrong in the other, and the two call sites sit on two
 * screens in two files: `WorkshopPickerState` in `MainActivity.kt`, `DesignWorkshopPickerState` in
 * `ui/DesignWorkshopPicker.kt`. [WORKSHOP_LINK_KEYS] above already names what a rule that reaches
 * one of them and not the other costs — *"a designer clearing one box successfully and the other box
 * silently, on the same form, one line apart"* — and a rule COPIED into both files is that same
 * defect with a delay on it, waiting for whichever of the two is edited next.
 *
 * ── THE THREE INPUTS, AND WHY THE BASELINE IS THE ONE THAT DECIDES ────────────────────────────
 *
 * [selectedId] is what the box holds now. [baselineId] is what it held when the form was BUILT — the
 * stored id on an edit, the prefill on a create — and it is never moved by a tap: both states keep it
 * `private set` and write it only in `applyDefault`. [hadOptions] is whether the list behind the box
 * had anything in it at all.
 *
 * A blank box over a non-blank baseline is therefore the one combination a person alone can produce:
 * they opened a picker that was showing a workshop and chose the "None" row. That is
 * [UNFILED_BY_CHOICE] whatever the list looks like at this moment — and it HAS to be read that way,
 * because at this moment the list can be empty. A record filed under a workshop this device cannot
 * list still draws its off-page row (`offPageWorkshopRow`), so the control stays enabled and the
 * "None" row stays reachable with `workshops` empty. Deciding this by "is the list empty" would read
 * a deliberate clearance as "there was nothing to choose", omit the key, and leave the designer
 * looking at an emptied form, a 200, and the old link still in the database — precisely the defect
 * `WorkshopRepository.patchBodyWithClearances` was written to end, reintroduced one layer above it.
 *
 * ── AND WHY THE OTHER TWO ARMS ARE WRONG IN THE HARMLESS DIRECTION ────────────────────────────
 *
 * With a blank baseline the column is already empty on the server, so either answer is a no-op on the
 * data and the only thing the choice changes is which SENTENCE the drain prints
 * ([outboxSentUnfiledMessage]). [hadOptions] is the honest split: a designer who was shown four
 * workshops and picked none needs no sentence at all, and a designer who was shown an EMPTY picker in
 * a courtyard has to be told the record went up filed under nothing — otherwise that absence is
 * discovered weeks later as a record missing from a workshop's lists, by which time nobody can tell
 * it from a record deliberately filed under nothing. That is R7's collapse arriving by the back door,
 * and one sentence at the drain is the whole of the remedy.
 *
 * Null when the box holds something: there is no absence to explain and nothing for the wire to say.
 */
internal fun unfiledLinkReason(selectedId: String, baselineId: String, hadOptions: Boolean): String? =
    when {
        // Something is chosen. Nothing to report, and the id itself is what goes on the wire.
        selectedId.isNotBlank() -> null
        // The box HELD a workshop when this form was built and does not now. Only a tap does that.
        baselineId.isNotBlank() -> UNFILED_BY_CHOICE
        // Never held one, but the list was there to pick from. A decision, and it needs no sentence.
        hadOptions -> UNFILED_BY_CHOICE
        // Never held one and there was nothing to hold. The drain says so when the record lands.
        else -> UNFILED_NO_OPTIONS
    }

/**
 * [PendingEntry.unfiled] for one record form, from the two pickers it mounts.
 *
 * THE KEYS ARE NAMED HERE AND NOWHERE ELSE, and that is the whole reason this two-line function
 * exists rather than a `mapOf` at each save handler. A mistyped column — `designWorkshopID`,
 * `design_workshop_id` — is not an error anywhere: [PendingEntry.clearedLinkKeys] would carry it,
 * `patchBodyWithClearances` would ask the request class whether it declares that name, the answer
 * would be no, and the key would be silently skipped. The designer's clearance would then vanish
 * between two files with no failure anywhere on the path, which is the same "exit zero is not
 * evidence" shape as the defect this sentinel was added to close.
 *
 * A form that mounts only one of the two pickers passes null for the other, and null is not a
 * clearance: the column is absent from the map, absent from [PendingEntry.clearedLinkKeys], and
 * absent from the replayed body, so the stored value stands. A form cannot un-file a link it never
 * put a control in front of anybody for.
 */
internal fun workshopUnfiledReasons(
    designWorkshop: String? = null,
    workshop: String? = null,
): Map<String, String> = buildMap {
    designWorkshop?.let { put("designWorkshopId", it) }
    workshop?.let { put("workshopId", it) }
}

/** One captured media item to stage for an offline entry (input form for staging). */
data class OfflineMediaSpec(
    val uri: Uri,
    val caption: String? = null,
    val recordName: String? = null,
    val customSegment: String? = null,
    val overrideBaseName: String? = null,
    val batchIndex: Int = 1,
    val stageStep: Int? = null,
    val processing: List<String>? = null,
    val linkedType: String? = null,
    val stepIndex: Int? = null,
    /** See [PendingMedia.purpose]; a form builds this from its `MediaCaptureState.purposes` map. */
    val purpose: String? = null
)

/**
 * WHAT ACTUALLY WENT INTO THE QUEUE, so the form can say it rather than say "Saved".
 *
 * A save with no signal used to answer a bare Boolean, and a Boolean has exactly one sentence
 * available to it. That sentence — "Saved on this device. It'll upload automatically when you're
 * back online." — was said whether all eight photographs were copied or none of them were, because
 * an unreadable file threw and the throw was flattened to `false` by the call site, which then went
 * down the online path and lost the record outright.
 *
 * @property unreadableFiles the captures whose bytes could not be read at all. NAMED rather than
 *   counted, because the designer's next act is to look at the gallery and decide which one to take
 *   again, and "one file failed" does not tell them which.
 */
data class OfflineQueueResult(
    val entryId: String,
    val queuedFiles: Int,
    val unreadableFiles: List<String> = emptyList(),
) {
    val allFilesQueued: Boolean get() = unreadableFiles.isEmpty()
}

/**
 * The sentence a form shows after a save with no signal.
 *
 * PURE, and here rather than in the seven forms, because seven copies of a sentence about whether
 * fieldwork is safe is seven chances for one of them to keep promising a photograph that is not
 * there. Pinned by `OfflineQueueMessageTest`.
 *
 * @param isCorrection an edit to a record the server already holds, which is a different promise: a
 *   new record does not exist anywhere until it is sent, whereas a correction has an older version
 *   of itself sitting on the server in the meantime, and the designer needs to know that the office
 *   is still reading the old one.
 *
 * ── WHY THE CORRECTION SENTENCE SAYS WHO WINS ─────────────────────────────────────────────────
 *
 * Because a queued correction WILL win, and nothing else in the app said so. `writeFromEntry` replays
 * it as a whole create-shaped body through `updateArtisan`/`updateProduct`/… with no version and no
 * `If-Match`, so a correction composed on the bus and drained hours later overwrites anything anybody
 * else changed in between, field for field, with nobody told. The questionnaire interview form refuses
 * to queue an edit for exactly this hazard and spends a paragraph explaining why (see MainActivity's
 * questionnaire save handler); the six record types accept it, which is a defensible trade for a
 * register a small team keeps — losing a courtyard's fieldwork to a refusal is worse than a rare
 * overwrite — but it is not defensible to make it silently. "The office still sees the earlier
 * version" told the designer the half that costs them nothing and left out the half that costs
 * somebody else their edit.
 *
 * Closing it properly needs the record's version as the queued write's precondition, exactly as the
 * custom questionnaire's write does. Until then this sentence is the whole of the warning, which is
 * why it is here and not in a comment.
 *
 * ── AND WHY IT IS THREE CLAUSES RATHER THAN FIVE (2026-09-03) ─────────────────────────────────
 *
 * It closed with *"Tell them if that matters"* — an instruction naming nobody, addressed to a
 * designer who has just put the phone in their pocket, on a toast that is gone in five seconds. The
 * facts a person can act on are that the correction is safe, that the office is reading the old
 * version meanwhile, and that it will overwrite whatever it lands on. Everything else on this
 * surface competes with those three for the two seconds it is read in, which is the standing rule
 * for both clients: one line, state the fact, name the act, and put the argument up here.
 */
fun offlineSavedMessage(result: OfflineQueueResult, isCorrection: Boolean): String {
    val head = if (isCorrection) {
        "Correction saved on this device — sent when you have a signal. Until then the office " +
            "still sees the earlier version, and when it goes it replaces the whole record: your " +
            "version wins over any edit made in between."
    } else {
        "Saved on this device. It will be sent when you have a signal."
    }
    if (result.allFilesQueued) return head
    val names = result.unreadableFiles.joinToString(", ")
    return "$head " +
        "${result.unreadableFiles.size} file(s) could NOT be read and are not in it ($names) — " +
        "the record is safe, those captures are not. Take them again if you still can."
}

/**
 * "1 file" / "3 files" — what the designer is actually deciding about when they read a refusal.
 *
 * Counted rather than named here, unlike [OfflineQueueResult.unreadableFiles]: that list is about
 * captures that FAILED and the next act is to go and retake a particular one, whereas these are
 * intact and the only question is how much is riding on the entry.
 */
private fun stagedFiles(n: Int): String = if (n == 1) "1 file" else "$n files"

/** The server-written half of a refusal, made to end in a full stop so two sentences do not run on. */
private fun endStopped(said: String): String {
    val text = said.trim()
    if (text.isEmpty()) return ""
    return if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) text else "$text."
}

/**
 * WHAT A DESIGNER READS WHEN THE REGISTER ALREADY HOLDS A RECORD THAT CLASHES WITH THIS ONE.
 *
 * ── THE GAP THIS CLOSES ───────────────────────────────────────────────────────────────────────
 *
 * The web has had a dedicated branch for an answered 409 since the incident quoted on
 * [PendingEntry.conflict] (`frontend/lib/offline.ts`, the `error.status === 409` arm of `runSync`),
 * and its closing clause is the whole value of it: *"Open the record it clashes with, carry across
 * anything it is missing, then discard this entry."* This handset had no such branch. A clashing
 * Aadhaar, a craft already named that and an artisan set already interviewed all came out of
 * `replayEntry` as `Rejected(refusal.message)` — the same shape as a field that is too long and a
 * permission this account does not hold — so the tray printed the server sentence under a Try again
 * button that could only ever fetch the identical 409, and offered no third thing to do. The
 * designer's two visible options were to keep pressing a button that cannot work, or to delete a
 * day of fieldwork to make the row go away.
 *
 * ── WHAT THE SENTENCE HAS TO CARRY, AND WHY EACH CLAUSE IS IN IT ──────────────────────────────
 *
 *  1. THAT NOTHING WAS SAVED. A refusal a designer half-reads is a refusal they assume went through
 *     eventually. This says it did not, first, in the first clause.
 *  2. THE SERVER'S OWN WORDS, VERBATIM. `artisans.py::_identity_conflict` NAMES the artisan and
 *     their place ("Giriraj Prasad (Bhuj) is already recorded with this Aadhaar number") precisely so
 *     the designer can go and find them; summarising that into "duplicate" throws away the only
 *     thing on the screen they can act on. `questionnaire.py::_DUPLICATE_SET_DETAIL` and
 *     `crafts.py` are written the same way. Only the punctuation is touched — see [endStopped].
 *  3. THAT NOTHING HAS BEEN DELETED, and how much is still here. The count is the number the person
 *     is really deciding about when they consider the Throw away button.
 *  4. THAT RETRYING ALONE CANNOT WORK, and why — the clash is not on this phone. Without this the
 *     designer walks up the hill for a signal, and then does it again tomorrow; the records banner
 *     already spends a paragraph on that exact walk (`outboxDeviceBanner`).
 *  5. AN ORDER OF OPERATIONS THAT ENDS SOMEWHERE. Open the other record, carry the missing details
 *     across, THEN discard this one. The ordering is load-bearing: the entry is the last copy of
 *     both the record and its photographs, and this is the one screen in the app that can say so
 *     before the delete rather than after it.
 *
 * ── AND WHY EACH CLAUSE IS NOW ONE CLAUSE (2026-09-03) ────────────────────────────────────────
 *
 * All five facts survive; the reasoning behind them does not appear on screen any more. This was
 * four long sentences carrying their own justifications — "because what is in the way is not on this
 * phone", "and only then", "Nothing has been sent and nothing has been deleted" — read by somebody
 * standing in a courtyard deciding whether to press a red button. A tray row that has to be read
 * twice is a tray row that gets read none, and the button under it deletes the only copy of a day's
 * fieldwork. The argument for every clause is in this KDoc, where it can be checked and cannot be
 * skimmed past; the sentence states what happened, what is still here, and what to do.
 *
 * PURE, and here rather than in the tray, for [offlineSavedMessage]'s reason: it is read by somebody
 * standing in a courtyard with no connection, so a JVM test is the only place it can be checked.
 * Pinned by `OutboxConflictTest`.
 *
 * @param said the server's own `detail`, already unwrapped by `apiRefusal`.
 * @param files how many staged captures are still on this device with the entry. 0 omits the clause
 *   rather than printing "0 files", which reads as an accusation that something went missing.
 * @param isCorrection this entry is an edit to a record the server already holds ([PendingEntry.targetId]).
 *   A different remedy and a different standing fact: there is nothing to "carry across" — the
 *   record exists — and the office is meanwhile still reading the version before this correction.
 */
fun outboxConflictSentence(said: String, files: Int, isCorrection: Boolean): String {
    val carrying = if (files > 0) " and the ${stagedFiles(files)} saved with it" else ""
    // Agreement, because the subject grows a second half whenever there are files. "This entry and
    // the 3 files saved with it IS still here" is the kind of sentence a person stops reading, and
    // everything that matters is in the clause after it.
    val isAre = if (files > 0) "are" else "is"
    val server = endStopped(said).let { if (it.isEmpty()) "" else " $it" }
    // The clause every arm shares, written once: a designer moving between a clashing artisan and a
    // clashing craft must not be told two different stories about what a clash costs them. It states
    // the outcome only — WHY a retry cannot work is clause 4 of the KDoc above, and it is the kind of
    // reasoning that belongs there rather than on a tray row somebody reads standing up.
    val standing = "Retrying alone gets the same answer."
    return if (isCorrection) {
        "Not applied — it clashes with a record the register already holds.$server Nothing was " +
            "deleted: the correction$carrying $isAre still here, and the office still reads the " +
            "earlier version. $standing Open the clashing record, make the change there, then " +
            "discard this entry."
    } else {
        "Not saved — the register already holds a clashing record.$server Nothing was deleted: " +
            "this entry$carrying $isAre still here. $standing Open the clashing record, copy " +
            "anything missing, then discard this entry."
    }
}

/**
 * THE RECORD TYPES WHOSE CREATE ROUTES ACCEPT AN IDEMPOTENCY KEY — four, and the list is the
 * decision rather than a convenience.
 *
 * Spelled as the literal strings `queueOfflineEntry` is handed, because that is what a
 * [PendingEntry.type] is on disk, and a queue file written a fortnight ago is read by whatever
 * string it holds. The server side of the same list is `services/records.CLIENT_KEY_FIELD`'s four
 * routes; the web side is `CLIENT_KEY_ENDPOINTS` in `frontend/lib/offline.ts`.
 */
private val CLIENT_KEY_TYPES = setOf("workshop", "product", "tool", "process")

/**
 * Should an entry of this shape be given a [PendingEntry.clientKey]?
 *
 * ── WHY `artisan` AND `craft` ARE DELIBERATELY ABSENT ──────────────────────────────────────────
 *
 * Both are ALREADY idempotent under a better key than any this handset could mint.
 * `Artisan.aadhaarNumber` is `@unique` and `artisans._guard_identity_conflicts` answers a pre-write
 * 409 that NAMES the artisan already holding the number — the sentence [outboxConflictSentence]
 * quotes verbatim so a designer can go and find them. `Craft.name` is `@unique` with its own 409.
 * A second mechanism beside either would be two guards that can disagree about what a duplicate is,
 * and the 409 arm of `WorkshopRepository.replayEntry` is written on the assumption that a clash is
 * SOMEBODY ELSE'S record — which a key colliding with our own earlier create would falsify.
 *
 * The remaining queued types are not creates of a guarded record at all: [OFFLINE_MEDIA_ONLY]
 * performs no create, `questionnaire` is guarded by `QuestionnaireInterview.artisanSetKey @unique`,
 * and [OFFLINE_EXPORT_RECORD] / [OFFLINE_DESIGN_RATING] post to routes that are idempotent by
 * construction — the rating route answers `replayed` for precisely this reason.
 *
 * ── AND WHY A CORRECTION NEVER GETS ONE ────────────────────────────────────────────────────────
 *
 * A non-null [PendingEntry.targetId] is an edit, and an edit goes to the record's PATCH route, whose
 * server schema does NOT declare `clientKey`. Every request body on that API is `extra="forbid"`, so
 * a key on a correction is a 422 carrying `extra_forbidden` — which this queue reads as a
 * disagreement between BUILDS and re-attempts once per app run, for ever, on a prepaid connection.
 * The gate is here, at queue time, so the key is never even written to disk against a correction.
 *
 * A correction has its own idempotency question and this is not it — see [offlineSavedMessage]'s
 * "WHY THE CORRECTION SENTENCE SAYS WHO WINS", and `expectedUpdatedAt` on the server's six update
 * schemas, which is the half that is built and waiting for a client to send it.
 *
 * PURE, and here rather than in the repository, for [outboxConflictSentence]'s reason: being wrong
 * costs either a duplicate government record or a 422 that strands a fortnight of fieldwork, and
 * neither shows up on a machine with a signal. Pinned by `OutboxClientKeyTest`.
 */
fun outboxMintsClientKey(type: String, targetId: String?): Boolean =
    targetId == null && type in CLIENT_KEY_TYPES

/** Reader for a queued payload — lenient, because it was written by a build that is not this one. */
private val payloadReader = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * WHICH OF THIS ENTRY'S IDS COULD BE THE ONE THE SERVER DOES NOT HAVE.
 *
 * ── IT READS THE PAYLOAD BECAUSE THE ANSWER DOES NOT NAME A FIELD ─────────────────────────────
 *
 * `records.require_record` raises `404 {"detail":"Record not found"}` and
 * `design_workshops.load_workshop_or_404` raises the byte-identical string on purpose, so that a
 * caller who may not see a workshop cannot tell it apart from one that does not exist. Excellent for
 * the repository and useless to the designer holding the phone: the sentence they get names no box.
 *
 * So the candidates come from what this entry actually SENT. A key present in the payload with a
 * non-blank id is something the server could have failed to find; a key that is absent, null or
 * blank cannot be, and is never offered as a suspect.
 *
 * [named] is the server's own answer when it gave one — pydantic puts the field in `loc` on a 422,
 * e.g. `["body","designWorkshopId"]` — and it WINS OUTRIGHT over the payload scan. Knowing beats
 * inferring, and that ordering is the whole reason this takes the argument rather than always
 * guessing from the body.
 *
 * @param isCorrection this entry updates a record that already exists ([PendingEntry.targetId]). The
 *   record ITSELF is then a candidate — `require_record(db.artisan, artisan_id)` runs before any
 *   payload key is looked at, so an artisan an admin deleted at the office answers the same 404 as a
 *   workshop that never existed. Naming only the payload's keys there would send a designer to
 *   re-pick a workshop for a record that is gone, which is a remedy that cannot work.
 * @param namedOnly ONLY a field the server itself named may count. Pass true for a 422, and that is
 *   not a nicety: a 422 is FastAPI's answer for a value it will not accept, so a name that is three
 *   hundred characters long, an Aadhaar that fails its checksum and a date in the wrong format all
 *   arrive with the same status as a reference a validator could not resolve. Scanning the payload
 *   for ids on one of those would print *"this record points at an artisan that is not on the
 *   server"* over a refusal about a name — a sentence that is confidently wrong, offering a remedy
 *   that cannot work, on a row whose real reason the designer can act on in ten seconds. A 404 has
 *   no such ambiguity: on every route this outbox replays it means one of the ids in this request
 *   could not be found.
 * @return the candidate keys in [REFERENCE_FIELD_NOUNS] order, plus [TARGET_RECORD_KEY] first when
 *   [isCorrection]. Empty when nothing in this entry can dangle, which is the caller's signal that
 *   the refusal is about something else and must stay an ordinary one.
 */
internal fun danglingReferenceCandidates(
    payload: String,
    named: List<String> = emptyList(),
    isCorrection: Boolean = false,
    namedOnly: Boolean = false,
): List<String> {
    // The server named it. One certainty beats nine possibilities, and it is the only branch that can
    // reach a key the payload scan would have missed — a reference nested inside a list of steps, say.
    val certain = named.filter { it in REFERENCE_FIELD_NOUNS }
    if (certain.isNotEmpty()) return REFERENCE_FIELD_NOUNS.keys.filter { it in certain }
    if (namedOnly) return emptyList()
    val sent = runCatching { payloadReader.parseToJsonElement(payload) as? JsonObject }.getOrNull()
    val present = REFERENCE_FIELD_NOUNS.keys.filter { key ->
        val value = (sent?.get(key) as? JsonPrimitive)?.contentOrNull
        !value.isNullOrBlank()
    }
    if (!isCorrection) return present
    return listOf(TARGET_RECORD_KEY) + present
}

/**
 * The record a CORRECTION is aimed at, as a candidate beside the payload's own keys.
 *
 * Not a column and never sent on the wire — it is the `{id}` in the PATCH path — which is why it is
 * spelled with a character no JSON key from this API can contain. A designer reading the sentence
 * does not care about that distinction; they care that "the artisan you corrected" is one of the
 * things that might have gone, and it is the one whose remedy is not a re-pick.
 */
internal const val TARGET_RECORD_KEY = "@target"

/** The noun for one candidate key, for the sentence a person reads. */
internal fun referenceFieldNoun(key: String, recordNoun: String): String =
    if (key == TARGET_RECORD_KEY) recordNoun else REFERENCE_FIELD_NOUNS[key] ?: "record"

/** "a workshop", "a workshop or an artisan", "a workshop, an artisan or a craft". */
private fun anyOneOf(nouns: List<String>): String {
    val withArticles = nouns.map { noun ->
        // Written out rather than computed from the first letter: "an interview" and "a workshop" are
        // both regular, and the day somebody adds "hour" this is a line to change rather than a bug.
        if (noun.first().lowercaseChar() in "aeiou") "an $noun" else "a $noun"
    }
    return when (withArticles.size) {
        0 -> "something"
        1 -> withArticles.single()
        else -> withArticles.dropLast(1).joinToString(", ") + " or " + withArticles.last()
    }
}

/**
 * WHAT A DESIGNER READS WHEN A QUEUED RECORD POINTS AT AN ID THE SERVER DOES NOT HAVE.
 *
 * ── AND WHY IT MAY NEVER BE THE EMPTY-PICKER SENTENCE ─────────────────────────────────────────
 *
 * R7, and the design document is explicit that the two must not share words: *"an empty dropdown is
 * fixed BEFORE the save, by offering something answerable or standing the field down; a dangling id
 * is fixed AFTER the drain, on the record already on the device, by re-picking."* The empty-picker
 * sentences (§3.5 — "This device has not received the {noun} list yet…") are said on a form, about a
 * record that is about to save perfectly well. This one is said in the tray, about a record that IS
 * saved, locally, and needs one field changed. Reusing either for the other tells the designer to do
 * the one thing that cannot help.
 *
 * ── THE CLAUSES, AND WHY EACH IS HERE ─────────────────────────────────────────────────────────
 *
 *  1. WHICH FIELD. The spine is the design document's own sentence, byte for byte for the ordinary
 *     one-candidate case: *"This record points at a {field} that is not on the server. Nothing is
 *     lost — open it, choose one that is, and it will send."* Without the field name the row is the
 *     server's "Record not found", which a designer reads as an accusation about their record.
 *  2. NOTHING IS LOST, AND HOW MUCH IS STILL HERE. Same count, same reason, as
 *     [outboxConflictSentence]: it is the number the person is really deciding about when they look
 *     at the Throw away button next to it.
 *  3. THE SERVER'S OWN WORDS, VERBATIM. "Record not found" adds little here, and it is printed
 *     anyway, because the rule this tray keeps is that the API's sentence is never summarised —
 *     `isConflictRefusal` spends a paragraph on why a client must read the status and print the body
 *     rather than branch on its prose, and a route that starts saying something more useful must not
 *     have to wait for a client release to be heard.
 *  4. THAT A BARE RETRY CANNOT WORK. Without it the designer walks up the hill for a signal and does
 *     it again tomorrow; `outboxDeviceBanner` spends a paragraph on that walk.
 *
 * PURE, and pinned by `OutboxDanglingReferenceTest`, for [outboxConflictSentence]'s reason: it is
 * read by somebody standing in a courtyard, so a JVM test is the only place it can be checked.
 *
 * @param said the server's own `detail`, already unwrapped by `apiRefusal`.
 * @param nouns what might be missing, already turned into nouns a person uses — see
 *   [referenceFieldNoun]. More than one is an honest ambiguity, never a guess; see
 *   [PendingEntry.danglingField].
 * @param files staged captures still on this device with the entry. 0 omits the clause rather than
 *   printing "0 files", which reads as an accusation that something went missing.
 */
fun outboxDanglingSentence(
    said: String,
    nouns: List<String>,
    files: Int,
    isCorrection: Boolean,
): String {
    val subject = if (isCorrection) "This correction" else "This record"
    val head = if (nouns.size == 1) {
        "$subject points at ${anyOneOf(nouns)} that is not on the server."
    } else {
        // NOT A GUESS DRESSED AS A FACT. The server's 404 names no field and the payload carries
        // several ids, so the sentence carries several — the same refusal `DwResumedCreate.Ambiguous`
        // makes about picking a workshop by plausibility.
        "$subject points at something that is not on the server. It is ${anyOneOf(nouns)} — the " +
            "server's answer does not say which."
    }
    val carrying = if (files > 0) " and the ${stagedFiles(files)} saved with it" else ""
    val isAre = if (files > 0) "are" else "is"
    val server = endStopped(said).let { if (it.isEmpty()) "" else " The server said: $it" }
    // The design document's own clause is kept whole because it is ALREADY the terse recipe — state,
    // act, reassure, in one line. What went (2026-09-03) is everything after it that argued its case:
    // "because what is missing is missing on the server" is why a retry cannot work, and that belongs
    // in the KDoc above rather than on a row read standing up beside a delete button.
    return "$head Nothing is lost — open it, choose one that is, and it will send.$server This " +
        "entry$carrying $isAre still here; nothing was deleted. Retrying unchanged gets the same " +
        "answer."
}

/**
 * WHAT A DESIGNER IS TOLD WHEN A RECORD GOES UP FILED UNDER NOTHING BECAUSE THE LIST WAS EMPTY.
 *
 * The third of the three outcomes, and the only one that ends in success — which is exactly why it
 * needs saying. The entry leaves the queue, the banner's count drops, and every visible sign says
 * the record arrived intact. It did arrive; it arrived UNFILED, because the picker had nothing in it
 * at the moment it was filled in, and the designer standing in a cluster with no bars never saw a
 * choice to make. Say nothing here and the record is discovered missing from a workshop's lists
 * weeks later, by which time nobody can reconstruct which of the two absences it was.
 *
 * IT IS NOT THE DANGLING SENTENCE AND IT IS NOT THE EMPTY-PICKER SENTENCE. Nothing is refused, so
 * there is no remedy to offer and no button to press; the record simply needs filing, on the record's
 * own screen, whenever the designer next has a connection.
 *
 * ── WHAT THE SENTENCE NO LONGER ARGUES ON SCREEN (2026-09-03) ─────────────────────────────────
 *
 * It carried the clause *"That was never a claim that none exist."* — a disclaimer about a claim the
 * app had not made, aimed at a misreading nobody has reported, on the one notification here that
 * follows a SUCCESS. That is the argument for why the absence has to be stated at all, and it is
 * stated in this KDoc two paragraphs up. What the designer does next is open the record and file it;
 * the sentence now says that and stops. The empty picker itself is named, because the designer is
 * being told which of the two absences this was and the noun is the whole of that.
 *
 * @param nouns the controls that were empty, already in a person's words.
 */
fun outboxSentUnfiledMessage(label: String, nouns: List<String>): String {
    // NO ARTICLES, because "no" already carries the determiner: "there was no design & prototype
    // workshop to choose from" is the sentence, and "there was no A design & prototype workshop" is
    // the kind of seam that tells a reader the words were assembled by a machine and can be skimmed.
    val list = when (nouns.size) {
        0 -> "workshop"
        1 -> nouns.single()
        else -> nouns.dropLast(1).joinToString(", ") + " or " + nouns.last()
    }
    return "“$label” was sent, filed under nothing: there was no $list to choose from on this " +
        "device. Open the record and file it now."
}

/**
 * ONE CHOICE IN THE TRAY'S RE-PICK DIALOG.
 *
 * A projection and not a DTO, for [OutboxFailureRow]'s reason: the dialog is drawn over a queue
 * screen and has no business holding a workshop's whole record to draw two lines of it.
 */
data class RepickOption(val id: String, val label: String, val hint: String? = null)

/**
 * The answer to "what may this entry be re-pointed at", with the one fact that decides what an empty
 * list MEANS.
 *
 * [listed] IS THE WHOLE VALUE OF THIS TYPE. `emptyList()` on its own is three different facts —
 * still asking, the read failed, the scope holds none — and the one a reader assumes is the one that
 * says *there are none*, which on this screen would tell a designer their only route out is closed.
 * See `repickEmptyLine`, and DROPDOWN_DESIGN §3.5, which spends a table on the same distinction for
 * the pickers on the forms.
 */
data class RepickChoices(val options: List<RepickOption>, val listed: Boolean)

/**
 * WHAT THE RE-PICK DIALOG SAYS WHEN IT HAS NOTHING TO OFFER.
 *
 * Two facts, two sentences, and they are NOT §3.5's — deliberately, and this is the fourth place
 * this document has had to say so. §3.5's sentences are said on a FORM, about a record that has not
 * been saved yet, and they end by promising that "this record can be saved without it". Here the
 * record IS saved, on this phone, and it is stuck: the promise would be true and useless, and the
 * standing fact the designer needs is the opposite one — the entry is not lost while this dialog
 * cannot help, and it will still be here when the list can be read.
 *
 * THE READ FAILED is separated from THE SCOPE IS EMPTY for the reason those two are separated
 * everywhere else in this repository: their next moves are a connection and an administrator, and a
 * designer sent to the wrong one of those loses a day. `"No workshops are open to this account"` said
 * after a timeout is the single most repeated bug class in this repo, arriving on the one screen
 * whose whole job is to be a way out.
 *
 * PURE, and pinned by `OutboxDanglingReferenceTest`.
 */
fun repickEmptyLine(noun: String, listed: Boolean): String = if (!listed) {
    "The $noun list could not be read just now, so this is not showing what exists. Nothing has been " +
        "lost — the record and anything saved with it stay on this phone, and this will work when the " +
        "list can be read."
} else {
    "No $noun is open to this account, so there is nothing to point this at. An administrator can " +
        "give you access to one. Until then the record stays here — nothing is deleted."
}

/**
 * WHAT A PERSON IS ASKED BEFORE ANYTHING IN THIS APP DELETES UNSENT FIELDWORK.
 *
 * `OfflineOutbox.discard` is the only door out of the queue that is not a successful send, and this
 * is the sentence in front of it. It is here rather than inside the tray's `AlertDialog` for
 * [offlineSavedMessage]'s reason and one sharper one: the act it introduces is irreversible and
 * takes the photographs with it, so the words have to be checkable without a handset.
 *
 * THE CONFLICT ARM IS NOT DECORATION. On every other refused row the thing being deleted is the only
 * thing the row is about. On a clash the row NAMES A RECORD ON THE SERVER — "Giriraj Prasad (Bhuj)
 * is already recorded with this Aadhaar number" — and an unqualified "this cannot be undone" over
 * that reads, to somebody who has just been told an artisan already exists, as an offer to delete
 * that artisan. So the clash arm says which of the two goes and which stays, in that order, and then
 * says the thing the designer cannot see for themselves: what is in this copy and not in the other
 * one goes with it.
 *
 * AND THE SAVED ARM EXISTS BECAUSE THE REASSURANCE WAS NOT ALWAYS TRUE. "Nothing about it has
 * reached the server" was said on EVERY refused row, and one of the six kinds this queue tells apart
 * is kind 5 on [PendingEntry.createdId] — SAVED, FILES REFUSED. On that row the tray prints the
 * server's own "It was saved, but 2 file(s) were refused… Re-attach them on the record."
 * (`WorkshopRepository.replayEntry`), so the row and the dialog over it stated opposite facts on one
 * screen. The reading a designer acts on is the dialog's: they conclude the save failed outright,
 * throw the entry away, re-enter the record — and the register now holds it twice while the staged
 * captures, whose only copy the entry was, are gone. So the arm leads with the record surviving and
 * ends with the one thing that does not.
 *
 * BOTH ARMS SAY IT IN ONE CLAUSE EACH NOW (2026-09-03), and this is the surface where that matters
 * most in the whole app: it is the last thing read before an irreversible delete of unsent
 * fieldwork, inside an `AlertDialog` whose confirm button is right beneath it. "it stays exactly
 * where it is, on the server" and "this does not take it back out: it stays in the register" were
 * each the same fact said twice; a dialog a person skims is a dialog whose warning did not happen.
 * Every fact is still here — nothing was traded away for the shorter line.
 *
 * @param files how many staged captures go with the entry. ZERO OMITS THE CLAUSE ALTOGETHER rather
 *   than printing "and 0 files saved with it", which is what the tray said before this moved out of
 *   the composable — a record queued with no attachments is the common case for a craft or a
 *   correction, and being told a number of files that is zero invites a second look for the ones
 *   that must have gone missing. See [stagedFiles].
 * @param savedOnServer [PendingEntry.createdId] is set: the create or the correction LANDED, and
 *   only the media is outstanding. MUTUALLY EXCLUSIVE WITH [isConflict] by construction and checked
 *   first: `WorkshopRepository.replayEntry` wraps its whole create leg, the 409 arm included, in
 *   `if (entry.createdId == null)`, so a clash means no record of ours was written.
 */
fun outboxDiscardConfirmation(
    label: String,
    files: Int,
    isConflict: Boolean,
    savedOnServer: Boolean = false,
    /**
     * This entry points at an id the server does not have ([PendingEntry.danglingField]).
     *
     * THE ONE ROW IN THIS TRAY WITH A REMEDY THAT WORKS, so it is the one row where an unqualified
     * "this cannot be undone" is most expensive: everything else here is waiting on a permission, a
     * newer build, or a comparison only a person can make, and this is waiting on one tap. A designer
     * who reads a dead end and believes it deletes a day's fieldwork that was one dropdown away from
     * sending. So the arm names the other button before the red one.
     *
     * MUTUALLY EXCLUSIVE WITH [isConflict] by construction — one is a 409 and the other a 404/422 —
     * and with [savedOnServer], which is checked first for the reason given above it.
     */
    isDangling: Boolean = false,
): String {
    val carrying = if (files > 0) " and the ${stagedFiles(files)} saved with it" else ""
    val opening = "“$label”$carrying will be deleted from this device. This cannot be undone"
    if (savedOnServer) {
        // The files are named again, after the record's fate rather than before it, because on this
        // one row they are the whole of what is actually lost — and the remedy for them is on a
        // different screen, so it has to be said before the delete rather than after it.
        val orphaned = if (files > 0) {
            " The ${stagedFiles(files)} are the part the server never got — attach them to the " +
                "record there instead, if you still can."
        } else {
            ""
        }
        return "$opening. The record is already on the server and stays there — entering it again " +
            "would leave two of it.$orphaned"
    }
    val head = "$opening, and nothing about it has reached the server."
    if (isDangling) {
        return "$head Only one thing about it is wrong — the workshop or record it points at is not " +
            "there — and Re-pick it fixes that without losing anything. Deleting is for a record you " +
            "no longer want at all."
    }
    if (!isConflict) return head
    return "$head The record it clashes with is not touched. What goes is this phone's copy — " +
        "anything in it the other record does not have goes too. Check that first."
}

/** Live connectivity check (validated internet, not just an attached interface). */
object ConnectivityObserver {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

object OfflineOutbox {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /**
     * Something the researcher has to be told about the queue itself, waiting to be said once.
     *
     * Only [read] raises one, and only when a queue file would not parse — a silent failure with no
     * other symptom, because the queue simply comes back empty and the banner counting entries drops
     * to zero. Taken (and cleared) by the sync pass, which has a Context and can say it out loud.
     */
    private val alert = AtomicReference<String?>(null)

    private fun dir(context: Context): File = File(context.filesDir, "outbox").apply { mkdirs() }
    private fun mediaDir(context: Context): File = File(dir(context), "media").apply { mkdirs() }
    private fun queueFile(context: Context): File = File(dir(context), "queue.json")

    /** The one pending queue-level alert, cleared as it is taken so it is reported exactly once. */
    fun takeAlert(): String? = alert.getAndSet(null)

    /**
     * Read the queue, or quarantine it and say so.
     *
     * A FILE THAT WILL NOT PARSE IS NEVER TREATED AS AN EMPTY QUEUE. It used to be — a bare
     * `getOrDefault(emptyList())` — and the next [write] then overwrote the damage with whatever was
     * left, so a queue full of unsent fieldwork became zero entries, with no error, no trace and no
     * way back. The bytes are moved aside under a name a support request can ask for instead, the
     * researcher is told the count they can see is not the whole story, and the app carries on able
     * to queue new work (refusing to would turn one bad file into a device that cannot save at all).
     *
     * A ZERO-LENGTH file counts as damage too: [write] never produces one — an empty queue is the two
     * bytes `[]` — so a truncated file is the fingerprint of the non-atomic write this class used to
     * do, killed between truncating the old contents and writing the new ones.
     */
    private fun read(context: Context): List<PendingEntry> {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { error ->
            quarantine(file, "could not be read (${error.javaClass.simpleName})")
            return emptyList()
        }
        if (text.isBlank()) {
            quarantine(file, "was empty, which means a save was interrupted part-way through")
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<PendingEntry>>(text) }.getOrElse { error ->
            quarantine(file, "could not be understood (${error.javaClass.simpleName})")
            emptyList()
        }
    }

    /** Move a damaged queue file out of the way, keeping every byte, and raise the alert. */
    private fun quarantine(file: File, reason: String) {
        val kept = File(file.parentFile, "queue.damaged-${System.currentTimeMillis()}.json")
        val moved = runCatching { file.renameTo(kept) }.getOrDefault(false)
        alert.set(
            if (moved) {
                "The list of records saved on this device $reason. Nothing has been deleted — it has been " +
                    "kept as ${kept.name} in the app's storage. Please report this before uninstalling the app."
            } else {
                "The list of records saved on this device $reason, and could not be set aside. Please report " +
                    "this before uninstalling the app."
            }
        )
    }

    /**
     * Replace the queue file with [entries] as one indivisible step: write a temp file, flush it to
     * the actual storage, then rename it over the real one.
     *
     * This used to be a plain `writeText`, which truncates the file and then writes — so a process
     * killed in that window (a low-memory kill, a flat battery, the researcher swiping the app away
     * while it drained) left a truncated or half-written queue, and the read above turned that into
     * an empty one. Every record queued on that device was gone, silently. A rename within one
     * directory is atomic: either the old file is there or the whole new one is, never a mixture.
     * The `sync()` matters as much as the rename — a rename whose bytes are still only in the page
     * cache publishes an empty file after a power loss.
     */
    private fun write(context: Context, entries: List<PendingEntry>) {
        val target = queueFile(context)
        val temp = File(target.parentFile, "${target.name}.writing")
        val bytes = json.encodeToString(entries).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    // Every read and write below runs on the IO dispatcher. These are called from `LaunchedEffect`s
    // and save handlers that start on the main dispatcher, and reading the whole queue — every
    // record, every media descriptor — off flash on the main thread is how a form janks on the frame
    // it saves. The lock is taken INSIDE the dispatcher switch so a waiter never holds a main-thread
    // frame while another coroutine finishes its file IO.

    suspend fun all(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context) } }

    /**
     * How many entries a connection will actually move — refusals EXCLUDED.
     *
     * ── THIS USED TO BE `read(context).size` AND THAT WAS THE LIE ─────────────────────────────
     *
     * The one caller is `WorkshopRepository.pendingUploads`, and the one thing it feeds is the
     * banner in `MainActivity`, which draws the number under a cloud-off icon and the words
     * "uploading when you're online". [PendingEntry.failure] is the server's FINAL answer, and
     * `syncOutbox` steps over such an entry for ever — so counting it here put a record the server
     * has permanently refused inside a sentence promising it was on its way, and kept it there for
     * ever. `notifyUser`'s own KDoc names this defect; the Toast it added lasts five seconds in a
     * courtyard and fires only when the REASON changes, so the durable surface stayed wrong.
     *
     * The design-workshop side spent a hundred lines eliminating exactly this class of bug —
     * `dwDeviceSyncBanner` computes a `waiting` flag that is false when the only outstanding items
     * are ones a connection will not move, and gates the cloud-off icon on it. [counts] and
     * [outboxDeviceBanner] are that treatment applied to the records queue. Refusals are NOT hidden
     * by being dropped from this number: they are counted separately and given their own sentence,
     * their own colour and a retry.
     *
     * ── IT IS [counts]'s FIRST NUMBER AND NOT A SECOND OPINION (2026-09-03) ───────────────────
     *
     * This used to carry its own copy of the predicate — `read(context).count { it.failure == null }`
     * — which was the whole of "waiting" until an entry captured by ANOTHER ACCOUNT became a third
     * thing a connection will not move ([PendingEntry.ownerUserId]). Two copies of one test is the
     * shape of defect this file has already paid for twice; the copy that was not updated is always
     * the one on the surface somebody is looking at. So there is one classification, in [counts],
     * and this is a projection of it.
     */
    suspend fun count(context: Context, signedInUserId: String? = null): Int =
        counts(context, signedInUserId).waiting

    /**
     * Every part of the queue in ONE read, so the numbers cannot come from different moments.
     *
     * ── AND WHY IT TAKES THE SIGNED-IN ACCOUNT (2026-09-03) ───────────────────────────────────
     *
     * Because a third of these entries stopped being "waiting" the day `syncOutbox` learned to skip
     * an entry captured by somebody else — see [PendingEntry.ownerUserId]. Such an entry has no
     * [PendingEntry.failure] (nothing was ever sent, and nothing was refused), so it fell into
     * `waiting`, and the banner then drew it under a cloud-off icon promising it was on its way.
     * That is word for word the defect `outboxDeviceBanner` was written to end — a number inside a
     * sentence a connection cannot make true — reached by a third door, and the design-workshop side
     * closed the identical one first (`dwDeviceSyncBanner`'s `waiting` flag).
     *
     * NULL MEANS NOBODY IS SIGNED IN, and then nothing is another account's: the classification
     * matches [dwDraftIsForAnotherAccount] exactly rather than keeping a second opinion about the
     * same rule, and a pass cannot run in that state anyway.
     */
    suspend fun counts(context: Context, signedInUserId: String? = null): OutboxCounts =
        withContext(Dispatchers.IO) {
            // The IO and the lock are here; the classification is [outboxCountsOf], which is pure and
            // pinned on a desktop JVM. One read, one moment, one opinion about each entry.
            mutex.withLock { outboxCountsOf(read(context), signedInUserId) }
        }

    suspend fun enqueue(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context) + entry) }
    }

    /**
     * Write [entry] back over the queued copy carrying the same id.
     *
     * Read-modify-write under the lock and addressed by id, never by writing a snapshot back
     * wholesale: a sync pass replays a snapshot taken at its start while the researcher may still be
     * saving new records into the same file, and a wholesale write would delete them. An entry that
     * is no longer queued is left alone rather than resurrected.
     */
    suspend fun update(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entry.id }) {
                write(context, current.map { if (it.id == entry.id) entry else it })
            }
        }
    }

    /**
     * Record why the server will never accept this entry. The entry and its files are KEPT: a refusal
     * is a reason to stop retrying and tell someone, not a reason to destroy the only copy.
     *
     * @param skewRun pass [APP_RUN] — and ONLY that — when the refusal is one no person can act on
     *   because this build of the app and this build of the API disagree about the shape of the
     *   request. Written on EVERY call rather than only when set, so an entry refused for a dialect
     *   mismatch on one pass and for a genuinely bad field on the next stops being retried: a stale
     *   run stamp would go on describing a refusal that is no longer what is standing in the way.
     * @param conflict pass true — and only for an answered 409 — when the register already holds a
     *   record occupying this one's identity. See [PendingEntry.conflict]. Written on EVERY call for
     *   [skewRun]'s reason and one more: an entry that clashed on one pass and was refused for a bad
     *   field on the next must stop being described as a clash, or the tray goes on telling the
     *   designer to open a record that has nothing to do with what is now standing in the way.
     * @param danglingField pass the column — or the comma-separated candidates — when the refusal is
     *   a 404/422 saying this record points at an id the server does not have. See
     *   [PendingEntry.danglingField]. Written on EVERY call for the two reasons above and one that is
     *   sharper here: this is the only refusal in the queue with a THIRD button under it, and an
     *   entry that dangled on one pass and was refused for a bad field on the next would otherwise
     *   keep offering a Re-pick that now edits the wrong field of a record nothing is wrong with.
     */
    suspend fun markFailure(
        context: Context,
        entryId: String,
        reason: String,
        skewRun: String? = null,
        conflict: Boolean = false,
        danglingField: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId }) {
                write(
                    context,
                    current.map {
                        if (it.id == entryId) {
                            it.copy(
                                failure = reason,
                                failedAt = Instant.now().toString(),
                                skewRun = skewRun,
                                conflict = conflict,
                                danglingField = danglingField,
                            )
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    /**
     * REWRITE ONE REFERENCE ON A QUEUED RECORD, because the id it points at is not on the server.
     *
     * ── THE REMEDY THAT DID NOT EXIST, AND THE ONE THAT DID ───────────────────────────────────
     *
     * A dangling foreign key left exactly two doors: *Try again*, which fetches the identical 404,
     * and *Throw away*, which destroys the last copy of the record and its photographs. The record
     * is intact — payload, staged captures and all — and precisely one key in it is wrong. This
     * changes that key and nothing else.
     *
     * NOTHING ELSE IS TOUCHED, and that is the whole safety argument. The payload is re-serialised
     * from the object it parsed to, so every other answer the designer typed is byte-identical;
     * [PendingEntry.media] is not looked at; [PendingEntry.createdId] is not looked at, because this
     * path is only ever reached for an entry whose create leg refused, which by construction leaves
     * it null. A caller that reached here with a created id would be asking to change a field on a
     * record that is already on the server, and this is not the door for that.
     *
     * THE FAILURE IS CLEARED, exactly as [clearFailure] clears it: the designer has said "try it as
     * it stands now", and a stale refusal would leave the entry parked describing an id it no longer
     * carries. [PendingEntry.unfiled] is updated with it — choosing nothing HERE is as deliberate a
     * choice as choosing nothing on the form was, and it has to reach the wire as an explicit null
     * rather than as an omitted key, or the re-pick reports success and files the record nowhere.
     *
     * @param value the new id, or null/blank for "file it under nothing".
     * @return true when an entry with that id was found and rewritten.
     */
    suspend fun repick(
        context: Context,
        entryId: String,
        field: String,
        value: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            val entry = current.firstOrNull { it.id == entryId } ?: return@withLock false
            val body = runCatching { payloadReader.parseToJsonElement(entry.payloadJson) as? JsonObject }
                .getOrNull() ?: return@withLock false
            val chosen = value?.trim()?.takeIf { it.isNotEmpty() }
            val rewritten = body.toMutableMap().apply {
                if (chosen == null) remove(field) else put(field, JsonPrimitive(chosen))
            }
            val unfiled = entry.unfiled.toMutableMap().apply {
                if (chosen == null) put(field, UNFILED_BY_CHOICE) else remove(field)
            }
            write(
                context,
                current.map {
                    if (it.id == entryId) {
                        it.copy(
                            payloadJson = Json.encodeToString(
                                JsonObject.serializer(),
                                JsonObject(rewritten),
                            ),
                            unfiled = unfiled,
                            failure = null,
                            failedAt = null,
                            skewRun = null,
                            conflict = false,
                            danglingField = null,
                        )
                    } else {
                        it
                    }
                }
            )
            true
        }
    }

    /** Every entry the server has refused, for a surface that wants to show or discard them. */
    suspend fun failed(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).filter { it.failure != null } } }

    /**
     * THE INVERSE OF [markFailure], WHICH DID NOT EXIST — and its absence was the dead end.
     *
     * `blocksRetry(failure != null, skewRun = null)` is unconditionally true, so an entry marked
     * with an ordinary refusal was skipped by every pass for the rest of the installation's life,
     * and this class offered nothing that could ever unmark it. `WorkshopSyncEngine.retryWorkshop`
     * covers design workshops; the records queue had no equivalent, and `outboxFailures` — the
     * durable half, documented as "readable through outboxFailures by whatever screen shows it
     * next" — was called by nothing at all.
     *
     * The contrast that made it indefensible is forty lines away in [WorkshopDraftStore]:
     * `DraftMedia.uploadFailure` is "cleared by a manual retry, which is what makes 'the file limit
     * was raised, try again' one tap rather than a support request". A refused artisan is the same
     * shape of problem — a field the designer can correct, a permission an administrator can grant,
     * a limit somebody can raise — and it deserved the same one tap.
     *
     * [skewRun] IS CLEARED TOO. A person choosing to retry has said "try it as it stands now"; a
     * stale run stamp left behind would go on describing a disagreement between builds that may no
     * longer be what is standing in the way.
     *
     * SO IS [PendingEntry.conflict], for that reason exactly. A designer taps Try again on a clash
     * because they have just deleted the duplicate at the office, or established that the record it
     * clashed with is somebody else's after all — so the NEXT answer decides what this entry is, and
     * a stale flag would leave the tray telling them to go and open a record the server has stopped
     * objecting to. Nothing about the ENTRY itself is cleared: the payload and every staged file stay
     * exactly where they are, which is the invariant [PendingEntry.conflict] spends a paragraph on.
     *
     * AN ENTRY ANOTHER ACCOUNT CAPTURED IS NOT UNMARKED (2026-09-03), for the argument written out on
     * [clearAllFailures]: `syncOutbox` will not send it, so clearing the refusal sends nothing and
     * destroys the only sentence saying why. [signedInUserId] defaults to null — "nobody is signed
     * in, so nothing is another account's" — leaving every existing caller unchanged.
     *
     * @return true when an entry with that id was found and unmarked.
     */
    suspend fun clearFailure(
        context: Context,
        entryId: String,
        signedInUserId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (
                current.none {
                    it.id == entryId && it.failure != null &&
                        !dwDraftIsForAnotherAccount(it.ownerUserId, signedInUserId)
                }
            ) {
                return@withLock false
            }
            write(
                context,
                current.map {
                    if (it.id == entryId) {
                        // [PendingEntry.danglingField] goes with the rest, for the reason the whole
                        // block above gives: a person tapping Try again has said "try it as it stands
                        // now", and a stale marker would leave the tray offering a Re-pick for a
                        // workshop the server has stopped objecting to — or, worse, for the wrong
                        // field, once the next answer names a different one.
                        it.copy(
                            failure = null,
                            failedAt = null,
                            skewRun = null,
                            conflict = false,
                            danglingField = null,
                        )
                    } else {
                        it
                    }
                }
            )
            true
        }
    }

    /**
     * Unmark every refusal at once, for "try all of them again" after a sign-in or an update.
     *
     * ── EXCEPT THE ONES THIS SESSION IS NOT ALLOWED TO SEND (2026-09-03) ──────────────────────
     *
     * `syncOutbox` skips an entry whose [PendingEntry.ownerUserId] is not the signed-in account, so
     * clearing ITS refusal accomplishes exactly one thing: it destroys the server's own sentence.
     * Nothing is sent, `outboxFailureRows` no longer lists the entry (it filters on `failure`), and a
     * fortnight of another designer's fieldwork goes back to being invisible work that no pass will
     * ever move — with the one line that explained why now gone from the disk. The bulk button was
     * the wide version of that: one tap wiped the reason off every refused entry on a shared handset.
     *
     * SKIPPED, NOT REFUSED. The other entries are cleared exactly as before and the count returned is
     * of the ones actually unmarked, so "tried N again" names the number a person can act on.
     *
     * [signedInUserId] DEFAULTS TO NULL, which — through [dwDraftIsForAnotherAccount] — means "nobody
     * is signed in, so nothing is another account's" and leaves every existing caller unchanged.
     */
    suspend fun clearAllFailures(
        context: Context,
        signedInUserId: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            // THE SAME SELECTION `retryAllOutboxFailures` COUNTS, asked of the one function that owns
            // it. Two walks of one queue is how the clearing and the counting come to disagree about
            // which entries a tap was about — see [outboxRetryableFailures].
            val clearable = outboxRetryableFailures(current, signedInUserId).mapTo(HashSet()) { it.id }
            if (clearable.isEmpty()) return@withLock 0
            write(
                context,
                current.map {
                    if (it.id !in clearable) {
                        it
                    } else {
                        it.copy(
                            failure = null,
                            failedAt = null,
                            skewRun = null,
                            conflict = false,
                            danglingField = null,
                        )
                    }
                }
            )
            clearable.size
        }
    }

    /**
     * DISCARD one entry and its copied bytes, on a person's explicit instruction and never otherwise.
     *
     * [remove] exists for an entry that is SYNCED; this exists for one a person has read the refusal
     * on and decided to abandon — a duplicate artisan, a record captured by mistake. Separate from
     * [remove] so that no automatic path can reach it: nothing in `syncOutbox` calls this, and the
     * whole policy of this file is that being refused is not a reason to destroy the only copy of a
     * day's fieldwork. Only a human is allowed to make that call.
     */
    suspend fun discard(context: Context, entryId: String): Boolean = withContext(Dispatchers.IO) {
        val doomed = mutex.withLock {
            val current = read(context)
            val entry = current.firstOrNull { it.id == entryId } ?: return@withLock null
            write(context, current.filterNot { it.id == entryId })
            entry
        } ?: return@withContext false
        doomed.media.forEach { runCatching { File(it.localPath).delete() } }
        true
    }

    /**
     * Delete staged bytes in `outbox/media/` that no queued entry references any more.
     *
     * ── THE LEAK THIS RECLAIMS ────────────────────────────────────────────────────────────────
     *
     * `queueOfflineEntry` stages every file and THEN enqueues. A throw part-way through — an
     * unreadable Uri, a full disk — left every file already copied sitting in this directory with
     * nothing pointing at it, and there was no sweeper anywhere in the app: `StagedJournal` reclaims
     * S3 objects, not local files. On the phone least able to be told to clear its storage, a
     * repeatedly-failing save leaked a photograph's worth of flash each time, silently, for ever.
     * The staging path itself no longer leaks (it rolls back), and this reclaims what earlier builds
     * already left behind.
     *
     * [graceMillis] IS THE WHOLE SAFETY ARGUMENT. Staging happens milliseconds before the enqueue,
     * but it happens OUTSIDE this lock — so a file younger than the grace period may belong to a
     * save that is still in flight, and deleting it would turn a working save into a record whose
     * photograph vanished. A day is many orders of magnitude more than any save takes and costs
     * nothing but a day of the leak persisting.
     *
     * @return how many files were reclaimed.
     */
    suspend fun reclaimOrphanMedia(
        context: Context,
        graceMillis: Long = 24L * 60 * 60 * 1000,
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val referenced = read(context).flatMap { entry -> entry.media.map { it.localPath } }.toSet()
            val cutoff = System.currentTimeMillis() - graceMillis
            var reclaimed = 0
            mediaDir(context).listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.absolutePath in referenced) return@forEach
                if (file.lastModified() > cutoff) return@forEach
                if (runCatching { file.delete() }.getOrDefault(false)) reclaimed++
            }
            reclaimed
        }
    }

    /** Remove a synced entry and delete the local media copies it owned. */
    suspend fun remove(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context).filterNot { it.id == entry.id }) }
        entry.media.forEach { runCatching { File(it.localPath).delete() } }
    }

    /**
     * Copy a captured content Uri into local app storage so it survives offline until uploaded.
     *
     * ── FLUSHED, FSYNCED AND MEASURED, LIKE EVERY INDEX WRITE BESIDE IT ───────────────────────
     *
     * [write] and `QuestionnaireBundleInbox.write` both end in `fd.sync()`, and the reason given there
     * applies harder to these bytes than to the index: the index can be rebuilt from the queue, and a
     * photograph cannot be re-taken once the designer has walked away from the loom. A `copyTo` inside
     * `use` flushes the stream, but the bytes may still be in the page cache when a low-memory kill or
     * a flat battery arrives, and the entry pointing at them is already written down — so the record
     * syncs later carrying a truncated or empty file, and it does so silently.
     *
     * AND A ZERO-LENGTH COPY IS RAISED RATHER THAN RETURNED. The whole point of `OfflineQueueResult`'s
     * `unreadableFiles` is that a capture whose bytes could not be read is NAMED, so the designer knows
     * which one to take again; a zero-byte staged file is exactly that failure, and returning it as a
     * `PendingMedia` would tick the photograph off as saved and lose it at the far end instead.
     */
    fun stageMedia(
        context: Context,
        uri: Uri,
        caption: String?,
        recordName: String?,
        customSegment: String?,
        overrideBaseName: String?,
        batchIndex: Int,
        processing: List<String>?,
        stageStep: Int? = null,
        linkedType: String? = null,
        stepIndex: Int? = null,
        purpose: String? = null
    ): PendingMedia {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val target = File(mediaDir(context), UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: ""))
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                val bytes = input.copyTo(output, 64 * 1024)
                output.flush()
                output.fd.sync()
                bytes
            }
        } ?: throw IllegalStateException("Unable to read the captured media for offline storage")
        // MEASURED AFTER THE SYNC, so this is what is actually on the disk and not what the stream
        // believed it had handed over. Cleaned up on the way out: a zero-byte file left behind under
        // `outbox/media/` has nothing pointing at it and only `reclaimOrphanMedia` to find it.
        if (copied <= 0L || target.length() != copied) {
            runCatching { target.delete() }
            throw IllegalStateException("Unable to read the captured media for offline storage")
        }
        return PendingMedia(
            localPath = target.absolutePath,
            originalFilename = originalName,
            mimeType = mimeType,
            mediaType = inferMediaType(mimeType),
            caption = caption,
            recordName = recordName,
            customSegment = customSegment,
            overrideBaseName = overrideBaseName,
            batchIndex = batchIndex,
            stageStep = stageStep,
            processing = processing,
            linkedType = linkedType,
            stepIndex = stepIndex,
            purpose = purpose
        )
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun inferMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }
}
