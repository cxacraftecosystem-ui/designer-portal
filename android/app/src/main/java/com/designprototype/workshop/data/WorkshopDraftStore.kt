package com.designprototype.workshop.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The durable local store for a workshop under construction — the whole 22-stage document, every
 * photo, every recording, and every report generated from them — held on the device in a shape the
 * app can read back, not just replay.
 *
 * WHY THIS EXISTS, AND WHY [OfflineOutbox] IS NOT IT. The outbox is a SEND QUEUE. Its entries carry
 * an opaque `payloadJson` create-request nothing local can read a field out of, and
 * [OfflineOutbox.remove] deletes the staged media the instant an entry syncs. It answers "what still
 * has to reach the server", which is a completely different question from "what has the designer
 * captured so far". A 22-stage workshop is filled in over two weeks, out of signal, in a courtyard;
 * it must be openable, editable and printable on the device the entire time, and it must still be
 * all of those things after the process has been killed forty times in between. That is a local read
 * model with durable bytes, which is what this file is.
 *
 * WHY THE CAPTURE PATH ALONE IS NOT ENOUGH. `createAppFile` in MainActivity.kt puts every camera and
 * recorder capture in `context.cacheDir/field-captures/`. cacheDir is the first thing Android
 * reclaims under storage pressure, and it reclaims it WITHOUT ASKING and WITHOUT TELLING the app.
 * A designer who photographs a loom on day one and opens the report on day twelve finds an empty
 * frame, and no log line anywhere says why. So [importMedia] copies the bytes into
 * `filesDir/workshops/<id>/media/` the moment they are captured — filesDir is never evicted — and
 * every later reference is to that copy, never to the content Uri (which the granting app may revoke
 * the moment our process dies) and never to the cache file.
 *
 * ── LAYOUT ON DISK ───────────────────────────────────────────────────────────────────────────────
 *
 *     filesDir/workshops/<workshopId>/draft.json          the whole document
 *     filesDir/workshops/<workshopId>/media/<uuid>.<ext>   every photo/audio/video, durable
 *     filesDir/workshops/<workshopId>/exports/             generated .docx/.pdf
 *     filesDir/workshops-trash/<workshopId>-<stamp>/       what [delete] moves, and never removes
 *
 * One directory per workshop, so a soft delete, a backup, a share or a support export is a single
 * `renameTo` of one subtree rather than a scan for scattered files that will inevitably miss some.
 *
 * ── MECHANISM ────────────────────────────────────────────────────────────────────────────────────
 * The same four-part idiom [OfflineOutbox] and [StagedJournal] use, for the same reasons and in the
 * same order: kotlinx.serialization JSON, a [Mutex], every byte of IO on [Dispatchers.IO], and an
 * atomic temp -> `fd.sync()` -> `renameTo` write with quarantine (never deletion) on corruption.
 * There is deliberately no Room, no DataStore and no WorkManager here; this module has none of those
 * and adding one for a store of at most a few hundred JSON documents would buy a schema compiler and
 * a code-generation step in exchange for nothing the file above does not already do.
 */

/**
 * Bump when the on-disk shape changes, and add a rung to [WorkshopDraftStore.migrate] in the same commit.
 *
 * NOT BUMPED FOR THE SYNC BOOKKEEPING ([WorkshopDraft.sync], [DraftMedia.remoteMediaId] and the
 * fields beside them), and that is the ladder's own rule rather than an omission. Every one of them
 * is purely ADDITIVE and defaulted, which is precisely the case `ignoreUnknownKeys` plus an
 * all-defaulted model already handle losslessly in both directions: a build from before them reads
 * a draft that has them and ignores the keys, a build with them reads a draft that lacks them as
 * "nothing has been synced yet". A rung is owed only to a field that MOVES, is RENAMED or changes
 * MEANING — and spending a version number on an addition trains the next reader to skip the ladder
 * for the change that genuinely needs one.
 *
 * v2 IS A RENAME THAT DELIBERATELY DOES NOT CARRY ITS OLD VALUE ACROSS — `serverBaseline` became
 * [StageDraft.stageSeen]. See the rung in [WorkshopDraftStore.migrate] and the KDoc on that field.
 */
const val WORKSHOP_DRAFT_SCHEMA_VERSION: Int = 2

/**
 * One durable captured file, described well enough to render it without touching the bytes again.
 *
 * EVERY FIELD IS DEFAULTED, without exception, and that is a rule rather than a convenience. The
 * app updates over the air (see the versionCode scheme in build.gradle.kts) and field phones update
 * at wildly different times — a device that has been out of signal for three weeks is running a
 * build older than the one that wrote the draft it is being handed. A field added without a default
 * makes kotlinx throw `MissingFieldException` on decode, and the *only* recovery this file has for
 * an undecodable draft is quarantine: the older build would set aside two weeks of fieldwork as
 * corrupt because a newer build added a caption column. Defaults plus `ignoreUnknownKeys` turn that
 * into a silent, lossless degrade.
 */
@Serializable
data class DraftMedia(
    val id: String = "",
    /**
     * Path RELATIVE to the workshop directory ("media/1f3c….jpg"), never absolute.
     *
     * An absolute path under `/data/user/0/<pkg>/files` is not stable: it moves when the device
     * creates a second user or a work profile, and it is meaningless in a backup restored onto a
     * different handset. The outbox stores absolute paths and gets away with it because its entries
     * are transient; a draft is expected to outlive several of the conditions that change filesDir.
     * Resolve with [WorkshopDraftStore.mediaFile].
     */
    val relativePath: String = "",
    val originalFilename: String = "",
    val mimeType: String = "application/octet-stream",
    /** IMAGE / VIDEO / AUDIO / PDF / DOCUMENT — the same vocabulary [PendingMedia.mediaType] uses. */
    val mediaType: String = "DOCUMENT",
    val sizeBytes: Long = 0L,
    /** When the copy landed on this device, ISO-8601. Not the EXIF time; see [exifDateTime]. */
    val capturedAt: String = "",
    /** Which stage the designer was on when this was captured, and which field it answers. */
    val stageId: String? = null,
    val fieldKey: String? = null,
    val caption: String? = null,
    /**
     * Pixel dimensions as STORED, before [rotationDegrees] is applied.
     *
     * Read once, here, with `inJustDecodeBounds` so no bitmap is ever allocated. The report writer
     * needs them to lay a page out and a thumbnail grid needs them to reserve space; re-deriving
     * them later means decoding a 12-megapixel JPEG's header on whichever thread happened to ask,
     * once per scroll, on a phone that is already the cheapest thing in the room.
     */
    val width: Int = 0,
    val height: Int = 0,
    /**
     * The EXIF orientation resolved to degrees clockwise, 0/90/180/270.
     *
     * Stored resolved rather than as the raw EXIF tag because the tag is famously read wrong. Phones
     * held in portrait record a landscape sensor frame plus an orientation of 6, and anything that
     * ignores it prints every field photo on its side — in a .docx, permanently, at the moment the
     * report is delivered. It is captured HERE, at import, because a downstream re-encode (a resize,
     * a share through another app, a copy that strips metadata) drops EXIF and leaves nothing to
     * recover the rotation from afterwards.
     */
    val rotationDegrees: Int = 0,
    /** True for the mirrored EXIF orientations (2/4/5/7); a front-camera capture is the common one. */
    val mirrored: Boolean = false,
    /** EXIF DateTimeOriginal as written by the camera — when the loom was photographed, not copied. */
    val exifDateTime: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /**
     * SHA-256 of the durable copy, computed during the copy itself so the bytes are read once.
     *
     * Two jobs. It de-duplicates: a designer who re-picks the same photo for three stages should not
     * carry three copies on a 32 GB phone. And it is the only way to tell "the file is gone" from
     * "the file is there but truncated", which is exactly what a kill mid-import leaves behind.
     */
    val sha256: String? = null,
    /**
     * THE SERVER'S MediaFile id, WRITTEN ONLY WHEN THE SERVER HAS ANSWERED WITH IT.
     *
     * Null means the bytes are on this phone and nowhere else, and this field is the only thing in
     * the app entitled to say otherwise. It is not set when the upload starts, not when the PUT of
     * the stage that references the photo succeeds, and not when a request "seems to have gone
     * through" — only when `/media/complete` has come back carrying an id. Anything looser is the
     * failure this store's whole discipline exists to prevent: the app this was forked from deleted
     * its staged media the instant an entry left the send queue ([OfflineOutbox.remove]), so a lost
     * response meant the photographs were gone from the device AND absent from the server, and the
     * draft that still referenced them rendered empty frames in a report a fortnight later with
     * nothing on screen admitting why.
     *
     * It is also what a stage PUT substitutes for [id] on the wire. The server resolves a stage's
     * media field against `MediaFile.id` (`media_resolver`, backend/app/services/design_workshops.py),
     * so sending this device's own UUID would store a reference that resolves to nothing in the
     * server-side report — while the LOCAL draft must keep the local id everywhere, because the
     * on-device report reads the bytes by it (`imageFor`, ui/designworkshop/ReportScreen.kt). Two id
     * spaces, translated on the wire and nowhere else.
     */
    val remoteMediaId: String? = null,
    /** When the server acknowledged [remoteMediaId], ISO-8601. Never set without one. */
    val uploadedAt: String? = null,
    /**
     * Why the server refused this file for good — a 4xx, which no retry will change.
     *
     * The bytes STAY. A refusal is a reason to stop trying and tell somebody, never a reason to
     * destroy the only copy of a loom that was photographed once in a courtyard on a day nobody is
     * going back to. Cleared by a manual retry, which is what makes "the file limit was raised, try
     * again" one tap rather than a support request.
     */
    val uploadFailure: String? = null,
    val uploadFailedAt: String? = null
) {
    /** True once the server holds these bytes under an id it told us. The only definition of "safe". */
    val isConfirmedRemote: Boolean get() = !remoteMediaId.isNullOrBlank()
}

/**
 * One row of a repeating group — a dyeing batch, a tool in an inventory, a costing line.
 *
 * Carries its own [id] rather than relying on list position, because two stage screens editing the
 * same table concurrently reorder it, and a positional edit then rewrites the wrong row.
 */
@Serializable
data class DraftRow(
    val id: String = "",
    val values: Map<String, JsonElement> = emptyMap()
)

/**
 * One of the 22 stages.
 *
 * [values] is `Map<String, JsonElement>` and NOT a class per stage, on purpose. The 22 stages are
 * template-driven and the templates change between studies — a fixed column set means every template
 * edit is an app release, and every phone that has not taken that release yet either loses the new
 * answers or refuses the draft. A JsonElement map carries scalars, numbers, booleans, nested objects
 * and arrays without the store knowing what any of them mean, so an unrecognised key survives a
 * round trip through an older build untouched rather than being dropped on decode.
 */
@Serializable
data class StageDraft(
    val stageId: String = "",
    val title: String = "",
    /** Position in the 22-stage sequence. Stored, not inferred from map order — see [WorkshopDraft.stages]. */
    val order: Int = 0,
    val values: Map<String, JsonElement> = emptyMap(),
    /**
     * Answers to the questions THIS WORKSHOP'S DESIGNER added to this stage. Mirrors the server's
     * `_custom` row one for one: flat, keyed by their own field keys.
     *
     * **A SIBLING OF [values] AND NEVER NESTED INSIDE IT**, and the two wrong homes fail in opposite
     * directions. A plain key placed in [values] is posted inside the core singleton entry, dropped
     * by `validate_entry` (which builds its result by iterating the registry entity's own fields and
     * never reads a key it does not know), returned in `droppedKeys` — and would therefore fire "this
     * phone is running a newer field registry than the server" on every save of every workshop that
     * has a custom section, destroying the one registry-drift signal this repository has. An
     * `_`-prefixed key in [values] never leaves the phone at all, because `wireData` strips them; that
     * escape hatch exists and is used deliberately for `_recordingPlace`, and using it here would mean
     * a designer's answers that sync nowhere.
     *
     * NO [WORKSHOP_DRAFT_SCHEMA_VERSION] RUNG IS OWED, by that constant's own rule: this is purely
     * additive and defaulted, which is the case `ignoreUnknownKeys` plus an all-defaulted model
     * already handle losslessly in both directions. Spending a version number on an addition trains
     * the next reader to skip the ladder for the change that genuinely needs one.
     *
     * `JsonElement` for the reason [values] is: the store carries whatever a type it has never heard
     * of stores, so a v1.1 answer written by a newer build survives a round trip through this one
     * untouched rather than being dropped on decode.
     */
    val custom: Map<String, JsonElement> = emptyMap(),
    /**
     * WHETHER THIS DEVICE HAS EVER READ THE SERVER'S `_custom` ROW FOR THIS STAGE — the one fact that
     * decides whether [custom] may be sent as a REPLACEMENT rather than as a merge.
     *
     * [serverBaseline] cannot answer this and it is worth saying exactly why, because using it was the
     * defect. `recordStageSent` promotes a draft to `serverBaseline = true` after ANY successful save,
     * including a merge save from a stage that was seeded blank because its download failed — so a
     * phone can hold authority over a stage while never having seen the designer's own answers on it
     * at all. The custom answers live in a SEPARATE row that a save carrying no `_custom` entry does
     * not touch, so authority earned on the core stage says nothing whatever about that row.
     *
     * What went wrong with `serverBaseline` in that position: the office answered "looms in working
     * order" on the web; the designer opened the stage in a courtyard where the download failed, typed
     * one core field, and the save landed on the drive home — stamping the baseline. The NEXT save
     * then carried `{"_custom": {}}` with `merge` omitted, which `plan_custom_write` reads as "the
     * designer cleared every custom answer" and writes, wholesale, with no `RecordRevision` behind it.
     * The office's answer was gone in place and the save reported success.
     *
     * True in exactly one situation: the stage's [custom] bucket was seeded from a successful read of
     * the server's own bucket (`StageBucketDto.custom`), which is the only evidence a phone can have
     * about a row it did not write. Once earned it is never given back by a keystroke — see
     * `persistLocally`, which ORs it with what is already on disk.
     *
     * DEFAULTS TO FALSE, which is the conservative answer and the right one for every draft already
     * sitting on a handset: those were written by a build with no custom sections in it at all, so
     * their empty bucket means "never read" and must never be sent as a clearance. The cost of being
     * wrong in this direction is that a designer clearing their LAST custom answer on such a stage
     * does not propagate until the phone next reads the stage; the cost in the other direction is the
     * office's fieldwork.
     *
     * NO [WORKSHOP_DRAFT_SCHEMA_VERSION] RUNG IS OWED, by the same rule [custom] cites: additive and
     * defaulted, lossless in both directions through `ignoreUnknownKeys` and an all-defaulted model.
     */
    val customSeen: Boolean = false,
    /** The repeating group, when the stage has one. Empty for a stage that is only scalars. */
    val rows: List<DraftRow> = emptyList(),
    /** Ids into [WorkshopDraft.media]. The bytes are owned by the draft, not by the stage. */
    val mediaIds: List<String> = emptyList(),
    /**
     * Which keys of [values] the template says must be answered.
     *
     * Stored WITH the stage rather than read from the template at display time so that completeness
     * can be computed offline, on a device whose copy of the template may be older or absent. A
     * progress bar that quietly reads 100% because the template failed to load is how a workshop
     * gets submitted with four empty stages.
     */
    val requiredKeys: List<String> = emptyList(),
    /** How many repeating rows the stage needs before it counts as answered. 0 = the table is optional. */
    val minimumRows: Int = 0,
    /** How many attachments the stage needs. 0 = media is optional here. */
    val minimumMedia: Int = 0,
    val updatedAt: String = "",
    /** Set when the designer explicitly marks the stage done — distinct from "every required key is filled". */
    val completedAt: String? = null,
    /** Free notes that belong to the stage rather than to any one field. */
    val notes: String = "",
    /**
     * WHETHER THIS DEVICE HAS EVER READ THE SERVER'S COPY OF THIS STAGE — the one fact that decides
     * whether the phone may say "these are now exactly the rows" and "these are now exactly the keys".
     *
     * True in exactly two situations, and they are the only two in which the claim is honest:
     *
     *  * the stage was seeded or FOLDED from a SUCCESSFUL `GET .../stages/{key}`, so this device has
     *    seen everything the server had (`StageScreen.fromRemote` and [dwFoldServerStage]); or
     *  * the workshop has no server record at all, so there is nothing on the server this draft could
     *    be missing.
     *
     * It exists because `replaceCollections` used to be hard-coded true. The stage screen tries the
     * server read inside `runCatching{…}.getOrNull()`, and a designer in a courtyard gets null — at
     * which point a BLANK stage was seeded with no message saying the download had failed. One typed
     * field then produced a payload with that field, zero rows for every collection, and
     * `replaceCollections = true`, and the sweep deleted a fortnight of process steps, tools and raw
     * materials that the phone had never seen.
     *
     * ── IT IS EARNED BY HAVING READ, NEVER BY HAVING WRITTEN, AND THAT IS THE RENAME ──────────────
     *
     * This field was `serverBaseline`, and it had a THIRD writer that the two bullets above do not
     * mention: `recordStageSent` set it after ANY successful save, with the comment "the draft and
     * the server agree". After a save whose entries carried `merge = true` that is FALSE — the server
     * holds `previous ∪ sent` and this device has never seen `previous` — so the phone reached
     * authority over a stage it had never read, and the NEXT payload went up as a full replacement
     * containing only the fields this handset happened to hold. `save_stage` writes a singleton's
     * `data` WHOLESALE and records no `RecordRevision`, so the office's fields were gone in place,
     * with the save reporting success. Reproduced against the server's own planner:
     * `plan_custom_write([loomsWorking], sent={}, previous={'loomsWorking': 12}, merge=False)` returns
     * `data={}`.
     *
     * The rename is what makes that unrepeatable rather than merely fixed. A field called "baseline"
     * invites "we have just baselined it"; a field called "seen" cannot be set by a write without the
     * line reading as the lie it is. [customSeen] is the same word for the same reason, one row along.
     *
     * ── WHAT HAPPENS TO THE DRAFTS ALREADY ON HANDSETS, WHICH IS THE POINT OF THE RUNG ────────────
     *
     * A `serverBaseline = true` on disk cannot be attributed: it is indistinguishable between a stage
     * the designer opened with signal and a stage stamped by the defect above. So v2 does not carry
     * it across — every stage on every handset starts again as NOT SEEN and re-earns the fact the
     * only way it can be earned, by a read. That is a bookkeeping change and not a data change: no
     * answer, row, photograph or deletion the designer recorded is touched by it, and the claim it
     * withdraws is one this build cannot show to be true.
     *
     * ITS COST, STATED RATHER THAN HIDDEN: until that read lands, a stage sends `merge` and
     * `replaceCollections = false`, so a CLEARANCE does not propagate — a designer who deletes the
     * last value of a field, or a row out of a collection, on a draft that predates this change will
     * see it stay on the server until the stage has been read once with a connection. The deletion is
     * not lost: [emptiedEntities] is kept rather than cleared by a save that did not carry it (see
     * `recordStageSent`), and it goes up on the first save after the read — AND TAKES EFFECT THERE,
     * which is a second fact and was not true when this was written. `dwFoldServerStage` used to fold
     * the server's rows back into the draft for every entity, including the ones the designer had
     * emptied; the next payload then NAMED all those rows again, so the list travelled exactly as this
     * sentence promised and `replaceCollections` swept nothing. The fold now declines to re-add rows
     * for an entity named here, which is what makes "it goes up" mean the deletion happens. The two
     * halves have to stay together: skip the fold's restore without keeping this field and the
     * instruction is lost; keep the field without skipping the restore and it travels and means
     * nothing. Weighed against the other
     * direction — deleting fieldwork the office typed, in place, with no revision to recover it — a
     * row that lives a little longer on the server is the cheaper failure, and it is the same weighing
     * [customSeen] records for the container beside this one.
     *
     * The read is attempted on EVERY open of a stage that has not been seen, not only on an empty one
     * — see `StageScreen`'s load and [dwFoldServerStage] — so "until the stage is next read" means one
     * online visit rather than never.
     *
     * DEFAULTS TO FALSE, which is the conservative answer and the one a draft written by any earlier
     * build decodes to.
     */
    val stageSeen: Boolean = false,
    /**
     * Collections the designer has EMPTIED on this device and the server has not been told about yet.
     *
     * An emptied collection is INVISIBLE in a stage payload — entries are built from `rowsFor(key)`,
     * which is simply empty — so deleting the LAST row of a collection sends nothing at all and no
     * sweep can see it. With no per-row delete endpoint, naming it here is the only way that deletion
     * ever reaches the server; `StageSaveIn.emptiedEntities` is the field it lands in, and the web
     * tracks the same thing as `removedFrom`.
     *
     * IT IS A RECORD OF AN ACTION, NOT A DESCRIPTION OF THE DRAFT, and the difference is the whole
     * reason it is stored rather than derived. "This draft holds no rows of costSheet" is also true
     * of a stage that was read from the server before the cost sheets were entered on the web, and
     * deriving the list would delete them. "The designer deleted the last cost sheet on this phone"
     * is a thing that happened here, and it is the only thing that justifies a deletion there.
     * Cleared once the server has acknowledged it — see `recordStageSent`.
     */
    val emptiedEntities: List<String> = emptyList(),
    /**
     * Individual rows the designer has DELETED on this device and the server has not been told about
     * yet, as [DraftRow.id] — `entityKey#clientKey`.
     *
     * ── THE HALF [emptiedEntities] CANNOT SEE ────────────────────────────────────────────────────
     *
     * That field gains a key only when a collection goes from having rows to having NONE. Deleting
     * one row of three leaves it untouched, and before this list existed such a deletion left NO
     * record anywhere: `unsentDeletions` could not count it and `isFullySynced` had no term for it.
     *
     * On a stage this phone HAS read that is harmless — the payload names the surviving rows,
     * `replaceCollections` is claimed, and the sweep removes the row the payload no longer names. On
     * a stage it has NOT read, the deletion cannot travel at all, **and the workshop row said "Backed
     * up to the server" while the row was still in the repository and still in the report.** Nothing
     * can count what was never written down, which is what this is.
     *
     * IT IS MADE MORE REACHABLE BY THE `replaceCollections` FIX, NOT LESS. Before that fix the flag
     * was omitted and the server read the omission as true, so a partial deletion did travel — by
     * accident, in the same request that deleted every row the phone had never downloaded. Trading a
     * catastrophe for a silent no-op is the right trade and it is not the end of it.
     *
     * A RECORD OF AN ACTION, exactly as [emptiedEntities] is, and kept honest the same way: a key is
     * dropped again the moment the draft holds that row once more, so a row deleted and re-added
     * before the next save owes nothing. Cleared for the entities an acknowledged payload actually
     * swept — see `recordStageSent`.
     *
     * ADDITIVE AND DEFAULTED, so it owes no rung of [WORKSHOP_DRAFT_SCHEMA_VERSION] by that
     * constant's own rule: a draft written by any earlier build decodes with an empty list, which is
     * the same thing it has always meant.
     */
    val deletedRowKeys: List<String> = emptyList()
)

/** The whole document. One of these per workshop, one file per document. */
@Serializable
data class WorkshopDraft(
    /** Written into every file so [WorkshopDraftStore.migrate] never has to guess. Read the KDoc there. */
    val schemaVersion: Int = WORKSHOP_DRAFT_SCHEMA_VERSION,
    val workshopId: String = "",
    val title: String = "",
    val templateId: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    /**
     * Stages by id, not a list.
     *
     * A stage screen saves ONE stage. With a list, "save stage 14" is a positional splice against a
     * copy that may already be stale, and two screens saving concurrently swap a stage's contents
     * into the wrong slot. Keyed by id it is an idempotent map put, which is the whole reason
     * [WorkshopDraftStore.updateStage] can be safe. Display order comes from [StageDraft.order],
     * never from iteration order — a JSON object's key order is not a contract.
     */
    val stages: Map<String, StageDraft> = emptyMap(),
    /** Every durable file this workshop owns, referenced from stages by [DraftMedia.id]. */
    val media: List<DraftMedia> = emptyList(),
    /** Server id once the workshop has been created remotely; null while it exists only on this device. */
    val remoteId: String? = null,
    /** The account that owns this draft, so a shared field handset never shows one designer another's work. */
    val ownerUserId: String? = null,
    /**
     * HOW FAR THIS WORKSHOP HAS GOT TOWARDS THE SERVER, written back step by step as it happens.
     *
     * Kept INSIDE the draft rather than in a queue beside it, and that is the whole reason a sync
     * can be interrupted safely. A pass that dies between creating the remote record and pushing
     * stage 4 must resume at stage 4 — not repeat the create, which would make a second workshop,
     * and not restart from stage 1, which on one bar of signal means it never reaches stage 22. The
     * facts that decide where to resume are exactly the facts the designer needs to see ("nine
     * stages and four photographs still waiting"), so there is one copy of them and it is durable.
     *
     * Defaulted like everything else here, so a draft written before this field existed simply
     * reads as "nothing has been synced yet" and is pushed in full on the next pass — which, given
     * the server matches on `_clientKey`, updates rather than duplicates. See [DraftSyncState].
     */
    val sync: DraftSyncState = DraftSyncState(),
    /**
     * MAY THIS WORKSHOP'S RECORDINGS LEAVE THE DEVICE FOR A THIRD-PARTY TRANSCRIPTION SERVICE — with
     * who answered and when. Plan §6 answer 3.
     *
     * IN THE DRAFT AND NOT IN A SERVER-BACKED CACHE, and the argument is at [DraftConsent] in full.
     * The short of it: this is AUTHORED here, with the artisan standing in the courtyard and no
     * signal, so a store that can only mirror the server cannot hold it; it is evidence about a named
     * person, so this store's quarantine-never-delete discipline is the right one and
     * `DwReferenceStore`'s delete-and-refetch is not; and it is the only store that works for a
     * workshop with no server row at all, which is every workshop created in a courtyard.
     *
     * NO SCHEMA VERSION BUMP AND NO MIGRATION RUNG, by this file's own rule at
     * [WORKSHOP_DRAFT_SCHEMA_VERSION]: the field is defaulted and so is every field inside it, which
     * is the purely additive case that already round-trips losslessly through an older build.
     *
     * WHICH WRITER TO USE MATTERS. A designer recording an artisan's answer is an act they performed,
     * so it goes through [WorkshopDraftStore.update] and stamps `updatedAt`; a consent LEARNED from the
     * server on a later read is bookkeeping and goes through [WorkshopDraftStore.updateBookkeeping],
     * which does not. Getting that backwards either hides a real edit from the workshop list's
     * `updatedAt` sort or makes a background refresh look like fieldwork.
     */
    val consent: DraftConsent = DraftConsent()
)

/** Per-stage answered/required counts, for a progress list that has to be right offline. */
data class StageCompleteness(
    val stageId: String,
    val title: String,
    val order: Int,
    val filled: Int,
    val required: Int,
    val markedComplete: Boolean
) {
    /** A stage with nothing required is complete by default — otherwise it can never reach 100%. */
    val isComplete: Boolean get() = filled >= required
}

/** The whole document's progress, plus the per-stage breakdown it was summed from. */
data class DraftCompleteness(
    val stages: List<StageCompleteness>,
    val filled: Int,
    val required: Int
) {
    val completeStages: Int get() = stages.count { it.isComplete }
    /** 0f..1f. A draft with nothing required at all reads as complete rather than dividing by zero. */
    val fraction: Float get() = if (required <= 0) 1f else filled.toFloat() / required.toFloat()
}

/** A cheap row for the workshop list: enough to draw the card without decoding every stage. */
data class WorkshopDraftSummary(
    val workshopId: String,
    val title: String,
    val templateId: String,
    val updatedAt: String,
    val stageCount: Int,
    val mediaCount: Int,
    val completeness: DraftCompleteness,
    /**
     * True when this workshop's draft.json could not be read and has been quarantined.
     *
     * The row is still returned. A directory that exists holds media the designer captured, and the
     * list is the only surface that can offer to recover it — dropping the row from the list is how
     * a recoverable failure becomes an invisible one.
     */
    val damaged: Boolean = false
)

object WorkshopDraftStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * One lock for the whole store, not one per workshop.
     *
     * Two stage screens (a tabbed pager keeps neighbours alive, and an auto-save fires on every
     * debounce) can save the same draft in the same millisecond. Without serialisation both read the
     * same document, both write their own stage into their own copy, and whichever renames second
     * silently discards the other's stage — a lost-update no error ever reports and the designer only
     * notices weeks later when a stage is blank. Coarse is correct here: a draft.json is tens of
     * kilobytes, the whole critical section is sub-millisecond, and the expensive part (copying media
     * bytes) is deliberately kept OUTSIDE the lock — see [importMedia].
     */
    private val mutex = Mutex()

    /**
     * Something about the store itself the designer has to be told, waiting to be said once.
     *
     * Same mechanism as [OfflineOutbox.takeAlert]: only quarantine raises one, because quarantine is
     * otherwise a silent failure — the draft simply comes back null and the screen looks like a fresh
     * workshop. Taken and cleared by whichever surface has somewhere to show it.
     */
    private val alert = AtomicReference<String?>(null)

    /** The one pending store-level alert, cleared as it is taken so it is reported exactly once. */
    fun takeAlert(): String? = alert.getAndSet(null)

    // ── Paths ────────────────────────────────────────────────────────────────────────────────────
    // Everything hangs off filesDir. NOT cacheDir (evicted under storage pressure, without warning
    // and without a callback), NOT external storage (needs a permission we deliberately do not hold
    // at runtime, and is world-readable — this is research data about named artisans).

    private fun rootDir(context: Context): File = File(context.filesDir, "workshops").apply { mkdirs() }

    private fun trashDir(context: Context): File = File(context.filesDir, "workshops-trash").apply { mkdirs() }

    /**
     * The directory for one workshop.
     *
     * The id is sanitised into the filename because it reaches here from a server response and from
     * deep links. A raw `../` in an id would put draft.json somewhere else entirely — at best losing
     * the draft, at worst overwriting a neighbouring workshop's. The mapping is deterministic, so the
     * same id always resolves to the same directory across launches.
     */
    fun workshopDir(context: Context, workshopId: String): File =
        File(rootDir(context), safeName(workshopId)).apply { mkdirs() }

    private fun draftFile(context: Context, workshopId: String): File =
        File(workshopDir(context, workshopId), "draft.json")

    private fun mediaDir(context: Context, workshopId: String): File =
        File(workshopDir(context, workshopId), "media").apply { mkdirs() }

    /**
     * Resolve a [DraftMedia] relative path back to a real file on this device.
     *
     * THERE IS NO `exportsDir` BESIDE THIS ONE ANY MORE, and that is a decision rather than an
     * omission — see [com.designprototype.workshop.report.ReportExport] for where a generated report
     * actually lands and why it does not also land here.
     */
    fun mediaFile(context: Context, workshopId: String, relativePath: String): File =
        File(workshopDir(context, workshopId), relativePath)

    fun mediaFile(context: Context, workshopId: String, media: DraftMedia): File =
        mediaFile(context, workshopId, media.relativePath)

    /** Anything that is not plainly safe in a filename becomes '_'; empty ids get a stable stand-in. */
    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_')
            .take(120)
            .ifBlank { "unnamed" }

    // ── Reading ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Read and migrate one draft, or quarantine it and say so. Returns null when there is no draft
     * (a workshop that has never been opened) AND when there was one that could not be read — the
     * caller cannot tell the two apart from the return value alone, which is exactly why
     * [takeAlert] exists and must be surfaced.
     *
     * DECODE IS TWO STEPS ON PURPOSE: text -> JsonObject -> [migrate] -> [WorkshopDraft]. Decoding
     * straight into the data class would run the current build's expectations over an older build's
     * bytes with no chance to reshape them first, so any migration that MOVES or RENAMES a field
     * (rather than merely adding one) would be impossible without a throwaway mirror of every past
     * version of the model.
     */
    private fun readLocked(context: Context, workshopId: String): WorkshopDraft? =
        readDraftIn(workshopDir(context, workshopId), workshopId)

    /**
     * [readLocked] with the workshop's DIRECTORY named outright instead of a [Context].
     *
     * The Context-taking wrapper above is what production calls; this is the whole of the file work,
     * and it is split out so a JVM unit test can put a real draft.json on a real filesystem and read
     * it back. This module's unit tests have no Android framework in them (no Robolectric on the
     * classpath, `testOptions.unitTests.isReturnDefaultValues` for the stubs), so a test cannot
     * manufacture a Context — and the two behaviours that most need proving here, quarantining damage
     * and refusing to overwrite a draft from a newer build, are both statements about bytes on disk.
     * Before this seam existed they could only be checked on a device, which is why they were not
     * checked at all. Folding it back into the Context call would take the proof away again.
     */
    internal fun readDraftIn(dir: File, workshopId: String): WorkshopDraft? {
        val file = File(dir, "draft.json")
        if (!file.exists()) return null

        val text = runCatching { file.readText() }.getOrElse { error ->
            quarantine(file, workshopId, "could not be read (${error.javaClass.simpleName})")
            return null
        }
        // A zero-length draft.json is damage, not an empty document: [writeLocked] never produces
        // one — the smallest thing it can write is a full JSON object — so a blank file is the
        // fingerprint of a process killed between creating the temp file and filling it.
        if (text.isBlank()) {
            quarantine(file, workshopId, "was empty, which means a save was interrupted part-way through")
            return null
        }

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { error ->
            quarantine(file, workshopId, "could not be understood (${error.javaClass.simpleName})")
            return null
        }
        val onDiskVersion = runCatching { root["schemaVersion"]?.jsonPrimitive?.int }.getOrNull() ?: 0
        val migrated = migrate(root, onDiskVersion)

        return runCatching { json.decodeFromJsonElement(WorkshopDraft.serializer(), migrated) }.getOrElse { error ->
            quarantine(file, workshopId, "is in a shape this version cannot read (${error.javaClass.simpleName})")
            null
        }
    }

    /**
     * The version ladder. Every rung takes the document from `version` to `version + 1` and nothing
     * else, so adding the next one never means re-reading the ones below it.
     *
     * WHY A LADDER AND NOT "ignoreUnknownKeys will sort it out". Unknown-key tolerance handles ADDING
     * a field. It does nothing for a field that changes meaning, changes type, or moves — and the
     * store with no version is the one where the release that finally needs such a change discovers
     * it has no way to tell a v1 document from a v2 one and has to guess from the presence of a key.
     * Guessing wrong there corrupts every field draft on every phone at once, which is why this is
     * here on day one with a single no-op rung rather than "when we need it".
     *
     * A DOCUMENT FROM THE FUTURE IS LEFT ALONE. The loop only climbs. A phone running an older build
     * that opens a v3 draft falls through to a plain decode, where `ignoreUnknownKeys` plus the
     * all-defaulted model degrade it to what that build understands. Anything else means the older
     * build refuses the newer draft, and the designer is told their fieldwork is corrupt because
     * their colleague's phone updated first.
     *
     * "LEFT ALONE" IS ABOUT READING, AND IT USED TO BE ONLY HALF TRUE. The degraded copy is what the
     * screen shows, but every save re-encoded it and forced `schemaVersion` back down to this build's
     * constant — so the v3 document became a v2 one with v3's fields gone, stamped as this build's own
     * work, and no later rung could tell. The write side now refuses that: see the future-version
     * check in [writeLocked] and the argument in [quarantineFutureDraft]. Do not "simplify" the two
     * by making this ladder reject a future document instead; that reinstates exactly the refusal the
     * paragraph above rules out.
     */
    private fun migrate(root: JsonObject, fromVersion: Int): JsonObject {
        var document = root
        var version = fromVersion
        while (version < WORKSHOP_DRAFT_SCHEMA_VERSION) {
            document = when (version) {
                // 0 means "no schemaVersion key at all" — a document written by a build from before
                // this store existed, or one whose version field was lost. Structurally it is v1, so
                // the rung only stamps the version on.
                0 -> document
                /*
                  v2: `StageDraft.serverBaseline` became [StageDraft.stageSeen], AND ITS VALUE IS
                  DELIBERATELY NOT CARRIED ACROSS. This rung is a no-op ON PURPOSE, and the no-op is
                  the decision rather than an omission — the old key is simply dropped by
                  `ignoreUnknownKeys` and the new one defaults to false.

                  WHY NOT `renameKey`. The two mean different things. `serverBaseline` was written by
                  a successful read AND by `recordStageSent` after any successful save, including a
                  save whose entries carried `merge = true` from a stage seeded blank by a failed
                  download — so a `true` on disk is indistinguishable between "this device read the
                  server's copy" and "this device wrote to the server once, having read nothing".
                  Carrying it would carry the second, which is the authority that deletes the office's
                  fieldwork on the next save. There is no third field on disk that separates them.

                  IT IS A BOOKKEEPING CHANGE AND NOT A DATA CHANGE. No value, row, photograph,
                  deletion or sync signature is touched; the only thing withdrawn is a CLAIM this
                  build cannot show to be true, and it is withdrawn in the direction that destroys
                  nothing. Every stage re-earns it on its next online open — see [StageDraft.stageSeen]
                  for the cost of the interval and `dwFoldServerStage` for how the read heals it.
                */
                1 -> document
                // Next rung goes here, e.g.
                // 2 -> document.renameKey("summary", "overview")
                else -> return document
            }
            version++
            document = JsonObject(document + ("schemaVersion" to JsonPrimitive(version)))
        }
        return document
    }

    /**
     * Move a damaged draft aside — RENAMED, never deleted, and never overwritten by the next save.
     *
     * This is a designer's two weeks in a courtyard in Bagru. There is no acceptable code path that
     * destroys it because a parser was unhappy: the JSON may be recoverable by hand, and even where
     * it is not, the sibling `media/` directory next to it is untouched and holds every photo. The
     * name carries a timestamp so a second failure cannot clobber the evidence from the first, and
     * it is spoken out loud through [alert] because a quarantined draft otherwise looks exactly like
     * a workshop that was never started.
     */
    private fun quarantine(file: File, workshopId: String, reason: String) {
        val kept = File(file.parentFile, "draft.corrupt-${System.currentTimeMillis()}.json")
        val moved = runCatching { file.renameTo(kept) }.getOrDefault(false)
        alert.set(
            if (moved) {
                "The saved draft for workshop $workshopId $reason. Nothing has been deleted — the file has " +
                    "been kept as ${kept.name} and every photo and recording is still in this workshop's " +
                    "media folder. Please report this before uninstalling the app."
            } else {
                "The saved draft for workshop $workshopId $reason, and could not be set aside. Do not " +
                    "uninstall the app; please report this first."
            }
        )
    }

    /**
     * Where a draft from the future is kept: beside the original, named for the format it came from
     * and timestamped so a second downgrade cannot clobber the evidence from the first.
     */
    private fun futureDraftSibling(target: File, onDiskVersion: Int): File =
        File(target.parentFile, "draft.newer-v$onDiskVersion-${System.currentTimeMillis()}.json")

    /**
     * Set aside a draft written by a LATER build than this one, before this build overwrites it.
     *
     * ── THE DEFECT THIS CLOSES ────────────────────────────────────────────────────────────────────
     *
     * [migrate]'s ladder only climbs, so an older build decodes a v3 document by falling through to a
     * plain decode: `ignoreUnknownKeys` drops every field v3 added and the all-defaulted model fills
     * the gaps. That is the right READ — refusing would tell a designer their fortnight is corrupt
     * because a colleague's phone updated first. The write is where it went wrong. [save], [update]
     * and [updateBookkeeping] all re-encode the whole model and force `schemaVersion` down to this
     * build's constant, so the first debounced stage save turned that v3 document into a well-formed
     * v2 with v3's fields gone — and stamped it as this build's own work, so no future migration rung
     * could ever tell that anything had been dropped. `StageDraft.values` and `custom` are
     * `Map<String, JsonElement>`, so unknown ANSWERS survive; the model's own fields do not.
     *
     * ── HOW A PHONE GETS INTO THAT STATE, WHICH IS WHY THIS IS A DOZEN LINES AND NOT A REDESIGN ───
     *
     * Not through the ordinary routes, and that was checked rather than assumed. The in-app updater
     * fetches the highest versionCode and Android refuses to install a lower one over an installed
     * app; uninstall-then-install wipes `filesDir` and takes the drafts with it; and the manifest sets
     * `allowBackup` without `restoreAnyVersion`, whose default is false, so an auto-backup taken on a
     * newer build is not restored onto an older one. What remains is an operator-driven downgrade —
     * `adb install -d`, or an MDM push that keeps app data — on a fleet that side-loads its own APKs.
     * Rare, but the loss is total and silent, and the never-destroy rule this file is built on does
     * not have an exception for "rare".
     *
     * ── WHY QUARANTINE AND NOT A MERGE ────────────────────────────────────────────────────────────
     *
     * Keeping the parsed `JsonObject` from [readLocked] and encoding the model over it would preserve
     * the unknown fields and the higher version — genuinely forward-compatible round trips — and it is
     * the right answer the day that becomes a requirement. It is not the right answer today: it makes
     * every save a two-document merge, and a merge whose correctness nothing on this fleet exercises.
     * Setting the bytes aside costs one rename, destroys nothing, and is the same move the parser
     * failures above make. The save then proceeds onto a clean file, so the designer keeps working —
     * WHEN the rename actually happened, and only then. If it did not, there is nowhere for the newer
     * build's fields to be except the file itself, and the save is refused instead; see the returned
     * Boolean below and the throw in [writeDraftIn].
     *
     * The name is deliberately NOT `draft.corrupt-` — this file is not corrupt, it is from the future,
     * and an operator triaging a support request needs to know it can be restored by reinstalling the
     * newer build rather than repaired by hand.
     *
     * RETURNS WHETHER THE NEWER BYTES ARE ACTUALLY SAFE, and the caller must obey it. This used to
     * return Unit: it computed `moved`, used it to choose between two sentences — one of which told
     * the designer the draft "could not be set aside" and that they should not save any more work —
     * and then the same call went on to overwrite the file anyway. The warning was accurate about the
     * danger and powerless to prevent it, which is worse than no warning: it is advice the code
     * itself ignores, given to somebody who has no way to act on it in the two milliseconds before
     * the save lands. See [writeDraftIn] for the refusal that now backs it up.
     */
    private fun quarantineFutureDraft(file: File, workshopId: String, onDiskVersion: Int, kept: File): Boolean {
        val moved = runCatching { file.renameTo(kept) }.getOrDefault(false)
        alert.set(
            if (moved) {
                "The saved draft for workshop $workshopId was written by a newer version of this app " +
                    "(draft format $onDiskVersion; this version understands $WORKSHOP_DRAFT_SCHEMA_VERSION). " +
                    "Nothing has been deleted — the newer file has been kept as ${kept.name} and every photo " +
                    "and recording is still in this workshop's media folder. Install the newer version again " +
                    "to get everything it recorded back."
            } else {
                "The saved draft for workshop $workshopId was written by a newer version of this app and " +
                    "could not be set aside, so this save has been refused — nothing has been " +
                    "overwritten. Install the newer version again to keep working on this workshop."
            }
        )
        return moved
    }

    // ── Writing ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Replace draft.json as one indivisible step: temp file in the SAME directory, flushed all the
     * way to storage, then renamed over the real one.
     *
     * Three details, each load-bearing, and the order between them cannot be changed:
     *
     *  1. The temp file is in the same directory as the target — not cacheDir, not a temp dir. A
     *     rename is atomic only WITHIN one filesystem; across one it degrades to copy-then-delete,
     *     which is precisely the torn-write this exists to prevent.
     *  2. `fd.sync()` happens BEFORE the rename. A rename whose bytes are still only in the page
     *     cache publishes the new directory entry pointing at a file that, after a power loss, is
     *     zero-length. That is worse than not saving: the old draft is gone and the new one is empty.
     *  3. The rename is last, and it is what makes the save visible. A reader either sees the whole
     *     previous document or the whole new one, never a mixture — which is the guarantee that lets
     *     [readLocked] treat any half-parsed file as damage rather than as something to salvage.
     *
     * A failure anywhere deletes the temp file and rethrows. The previous draft is still on disk and
     * still complete, so a failed save costs the designer their last edit, never their fortnight.
     *
     * [onDisk] is whatever [readLocked] returned in this same critical section — every caller has one
     * to hand, and passing it explicitly rather than re-reading the file keeps a save to one parse.
     * Null means "nothing was there", which is the first save of a workshop. It exists solely for the
     * future-version check below; see [quarantineFutureDraft] for the argument.
     */
    private fun writeLocked(context: Context, draft: WorkshopDraft, onDisk: WorkshopDraft?) =
        writeDraftIn(workshopDir(context, draft.workshopId), draft, onDisk)

    /**
     * [writeLocked] against a named directory — the same test seam as [readDraftIn], for the same reason.
     *
     * [keptFile] names the file a future draft is set aside as, and it exists for one branch: the one
     * where the set-aside CANNOT be performed, which is the branch that used to overwrite the newer
     * document anyway. That branch is a statement about a rename that fails, and the portable way to
     * stage a rename failure on both the Windows host that runs these tests and a Linux CI box is to
     * hand it a destination it cannot have (an existing non-empty directory) — "make the directory
     * read-only" is a POSIX-only trick and would prove nothing here. Production never passes it.
     */
    internal fun writeDraftIn(
        dir: File,
        draft: WorkshopDraft,
        onDisk: WorkshopDraft?,
        keptFile: (target: File, onDiskVersion: Int) -> File = ::futureDraftSibling
    ) {
        val target = File(dir, "draft.json")
        // A DOCUMENT FROM THE FUTURE IS NEVER OVERWRITTEN IN PLACE. `migrate` deliberately lets an
        // older build READ a newer draft degraded; without this, that same build then wrote it back
        // as its own version with the newer build's fields gone and nothing able to notice. The
        // decoded copy still carries the on-disk number — the ladder only climbs, so it is not
        // rewritten on the way in — which is what makes the test one field and not a second read.
        //
        // AND THE REFUSAL IS REAL. This was a bare call to `quarantineFutureDraft` whose result was
        // dropped on the floor: when the rename failed the store said "could not be set aside — do
        // not save any more work" and then, in the next four lines, saved. Half a door. The whole
        // point of the guard is that the newer build's fields exist in exactly one place; if they
        // cannot be moved somewhere safe, the only way to keep them is not to write. Throwing costs
        // the designer the edit they just made — one debounce, or one field — and it is the same
        // trade `writeLocked` already makes when the temp file cannot be opened, whose KDoc says it
        // outright: "a failed save costs the designer their last edit, never their fortnight". The
        // sentence is a whole one because it reaches the designer verbatim (StageScreen's save
        // handler shows `error.message`), so it must say what happened, that nothing was lost, and
        // the one action that gets their work back.
        if (onDisk != null && onDisk.schemaVersion > WORKSHOP_DRAFT_SCHEMA_VERSION) {
            val moved = quarantineFutureDraft(
                target,
                draft.workshopId,
                onDisk.schemaVersion,
                keptFile(target, onDisk.schemaVersion)
            )
            if (!moved) {
                throw IOException(
                    "This workshop's draft was written by a newer version of this app (draft format " +
                        "${onDisk.schemaVersion}; this version understands $WORKSHOP_DRAFT_SCHEMA_VERSION) " +
                        "and could not be set aside, so this save has been refused rather than overwrite " +
                        "it. Nothing has been lost. Install the newer version again to keep working on " +
                        "this workshop."
                )
            }
        }
        val temp = File(target.parentFile, "${target.name}.writing")
        val bytes = json.encodeToString(WorkshopDraft.serializer(), draft).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // `Files.move(REPLACE_EXISTING)` rather than `File.renameTo`. On the device the two are the
            // same call — both reach `rename(2)`, which replaces the destination atomically, and point
            // 3 above is unchanged. They differ on the host that runs this module's unit tests:
            // `File.renameTo` maps to `MoveFileExW` WITHOUT the replace flag there, so it returns false
            // whenever the target already exists. Measured on this machine, JDK 17.0.2 — renaming over
            // an existing file returned false and left the old contents in place. A draft is replaced
            // on every debounce and created once, so under `renameTo` no host-side test could exercise
            // a second save at all; that is why the future-version rule below went untested for so long.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    // Every public entry point below switches to [Dispatchers.IO] and only THEN takes the lock. Both
    // halves matter and the order between them is not arbitrary. These are called from Compose save
    // handlers and LaunchedEffects that start on the main dispatcher, and reading or writing a
    // 22-stage document off flash on the main thread janks the very frame the designer typed into.
    // Taking the lock inside the dispatcher switch means a coroutine waiting its turn is parked on an
    // IO thread rather than holding the main thread hostage while another finishes its file IO.

    /**
     * Read one draft. Null when the workshop has never been saved, or when its file was quarantined
     * — call [takeAlert] afterwards if the difference matters to the caller.
     */
    suspend fun load(context: Context, workshopId: String): WorkshopDraft? =
        withContext(Dispatchers.IO) { mutex.withLock { readLocked(context, workshopId) } }

    /**
     * Write a whole document, stamping [WorkshopDraft.updatedAt] and [WorkshopDraft.createdAt].
     *
     * WHOLESALE. Whatever is passed becomes the document, so this is only correct when the caller is
     * holding the draft it just read and nothing else can be editing it. When two screens can be
     * editing at once — which on a tabbed 22-stage form is the normal case, not the exotic one — use
     * [update] or [updateStage], which read and modify inside the same critical section and cannot
     * write back a stale copy.
     */
    suspend fun save(context: Context, draft: WorkshopDraft): WorkshopDraft = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = Instant.now().toString()
            val existing = readLocked(context, draft.workshopId)
            val stamped = draft.copy(
                schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION,
                // createdAt is taken from what is already on disk when there is anything there. A
                // caller assembling a draft from a form has no reason to know the original creation
                // time, and letting it supply one means "created" silently becomes "last saved".
                createdAt = existing?.createdAt?.takeIf { it.isNotBlank() }
                    ?: draft.createdAt.takeIf { it.isNotBlank() }
                    ?: now,
                updatedAt = now
            )
            writeLocked(context, stamped, existing)
            stamped
        }
    }

    /**
     * Read-modify-write in ONE critical section: [transform] is handed whatever is on disk right now
     * (or a freshly minted empty draft) and its result is written back before the lock is released.
     *
     * This is the safe way to save. The unsafe way — load, edit in a coroutine, save later — has a
     * window between the two calls in which another screen's save lands and is then overwritten. The
     * window is small and the loss is total and silent, which is the worst possible combination.
     * [transform] therefore runs under the lock and MUST NOT do slow IO or call back into this store,
     * which would deadlock on a non-reentrant [Mutex].
     */
    suspend fun update(
        context: Context,
        workshopId: String,
        transform: (WorkshopDraft) -> WorkshopDraft
    ): WorkshopDraft = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = Instant.now().toString()
            val current = readLocked(context, workshopId)
                ?: WorkshopDraft(workshopId = workshopId, createdAt = now, updatedAt = now)
            val next = transform(current).copy(
                schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION,
                workshopId = workshopId,
                createdAt = current.createdAt.takeIf { it.isNotBlank() } ?: now,
                updatedAt = now
            )
            writeLocked(context, next, current)
            next
        }
    }

    /**
     * Read-modify-write exactly like [update], but WITHOUT touching [WorkshopDraft.updatedAt] — and
     * without creating a draft that is not already there.
     *
     * For facts ABOUT the document rather than facts IN it: the server id a create just returned, a
     * media file's acknowledged remote id, a stage's sync signature. None of those are edits the
     * designer made, and stamping them as edits is visibly damaging: the workshop list is sorted by
     * `updatedAt`, so a background sync pass would silently reshuffle it under the designer's thumb —
     * a row they were reaching for moves because a photograph finished uploading.
     *
     * Returns null when there is no draft on disk. Bookkeeping about a workshop that has been
     * deleted must not resurrect it as an empty document.
     */
    suspend fun updateBookkeeping(
        context: Context,
        workshopId: String,
        transform: (WorkshopDraft) -> WorkshopDraft
    ): WorkshopDraft? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readLocked(context, workshopId) ?: return@withLock null
            val next = transform(current).copy(
                schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION,
                workshopId = workshopId,
                createdAt = current.createdAt,
                updatedAt = current.updatedAt
            )
            writeLocked(context, next, current)
            next
        }
    }

    /**
     * Point a device-only workshop at a workshop that exists on the server.
     *
     * The transaction half of [adoptedIntoWorkshop], which owns the whole argument for what is
     * cleared and what would happen if it were not. Kept apart because the DECISION is the defect and
     * the write is not: everything interesting here can be asserted with no filesystem at all.
     *
     * ── ALREADY POINTED SOMEWHERE MEANS LEAVE IT EXACTLY AS IT IS ────────────────────────────────
     *
     * Re-pointing a draft that already has a server record would orphan whatever has been pushed into
     * the old workshop, and there is no reading of a mis-tap that makes that the intended outcome.
     * The check is [localDraftNeedsAWorkshop] rather than `remoteId == null`, because a workshop
     * created online is keyed by the SERVER's id with `remoteId` still null — asking the field alone
     * would let a designer "adopt" a real workshop into a different one.
     *
     * ── ALLOWED TO EVERY SESSION THAT HOLDS THE DRAFT, INCLUDING A DESIGNER ──────────────────────
     *
     * Deliberately, and it is not a hole in the create rule. Nothing here brings a workshop into
     * existence: the workshop was created by an admin, through `POST /design-workshops`, under the
     * gate. All this decides is which EXISTING workshop this device's unsent work belongs to, which is
     * the designer's own judgement about their own fieldwork. The server still refuses every stage of
     * it unless the account can open that workshop (`load_workshop_or_404`), so a mistaken adoption is
     * refused there and costs a correction rather than a record.
     *
     * Goes through [update] and NOT [updateBookkeeping]: adopting is an act a person performed, and
     * the list sorts by `updatedAt` so the row they just moved should be where they look for it.
     *
     * @return the re-pointed draft, or null when the draft was already pointed at a workshop.
     */
    suspend fun adoptIntoWorkshop(
        context: Context,
        workshopId: String,
        remoteId: String,
    ): WorkshopDraft? {
        val current = load(context, workshopId) ?: return null
        if (!localDraftNeedsAWorkshop(current)) return null
        return update(context, workshopId) { draft ->
            if (!localDraftNeedsAWorkshop(draft)) {
                draft
            } else {
                adoptedIntoWorkshop(draft, remoteId, Instant.now().toString())
            }
        }
    }

    /**
     * Save exactly one stage, leaving the other twenty-one exactly as they are on disk.
     *
     * This is what a stage screen should call. Because [WorkshopDraft.stages] is keyed by id the
     * merge is an idempotent map put, so a stage screen that auto-saves while its neighbour in the
     * pager also auto-saves cannot overwrite the neighbour's answers — each writes its own key into
     * whatever the other most recently left behind.
     */
    suspend fun updateStage(context: Context, workshopId: String, stage: StageDraft): WorkshopDraft =
        update(context, workshopId) { draft ->
            val stamped = stage.copy(
                stageId = stage.stageId,
                updatedAt = Instant.now().toString()
            )
            draft.copy(stages = draft.stages + (stamped.stageId to stamped))
        }

    /** Every draft on the device, newest edit first, skipping nothing — see [WorkshopDraftSummary.damaged]. */
    suspend fun list(context: Context): List<WorkshopDraftSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dirs = rootDir(context).listFiles()?.filter { it.isDirectory }.orEmpty()
            dirs.map { dir ->
                val id = dir.name
                val draft = readLocked(context, id)
                if (draft == null) {
                    // Either never saved or just quarantined. Either way the directory is real and
                    // may hold captured media, so the row is reported rather than skipped: a workshop
                    // that vanishes from the list is a workshop nobody can recover.
                    WorkshopDraftSummary(
                        workshopId = id,
                        title = id,
                        templateId = "",
                        updatedAt = "",
                        stageCount = 0,
                        mediaCount = File(dir, "media").listFiles()?.size ?: 0,
                        completeness = DraftCompleteness(emptyList(), 0, 0),
                        damaged = true
                    )
                } else {
                    WorkshopDraftSummary(
                        workshopId = draft.workshopId.ifBlank { id },
                        title = draft.title,
                        templateId = draft.templateId,
                        updatedAt = draft.updatedAt,
                        stageCount = draft.stages.size,
                        mediaCount = draft.media.size,
                        completeness = completeness(draft)
                    )
                }
                // Sorted by the stamp rather than by the directory's mtime: mtime changes when a
                // media file is written into a subdirectory on some filesystems and not on others,
                // so ordering by it makes the list jump around for reasons the designer cannot see.
            }.sortedByDescending { it.updatedAt }
        }
    }

    // ── Deletion ─────────────────────────────────────────────────────────────────────────────────

    /**
     * SOFT delete: the whole workshop directory is MOVED to `filesDir/workshops-trash/`, intact.
     *
     * Nothing else in this repo has a soft delete, and nothing else in this repo holds the only copy
     * of a research data set. "Delete workshop" on a phone in a courtyard is one mis-tap away from
     * "delete two weeks of interviews, photographs and measurements that exist nowhere else" — there
     * is no server copy for an offline draft, by definition, because being offline is the point. The
     * move is a rename within filesDir, so it is atomic and costs nothing even for a gigabyte of
     * video: the bytes never move, only the directory entry.
     *
     * The trash is deliberately NOT swept on a timer here. Emptying it is a decision a person makes
     * with the storage screen in front of them, not something a background pass does on the morning
     * the designer finally gets signal.
     *
     * Returns where it went, so the caller can offer an undo that is a single [restore] away.
     */
    suspend fun delete(context: Context, workshopId: String): File? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = File(rootDir(context), safeName(workshopId))
            if (!source.exists()) return@withLock null
            val target = File(trashDir(context), "${safeName(workshopId)}-${System.currentTimeMillis()}")
            if (!source.renameTo(target)) {
                // Never fall back to a recursive delete. A failed move must leave the data exactly
                // where it was; the alternative is that the one path guaranteeing recovery is also
                // the path that destroys the data when it goes wrong.
                throw IOException("Could not move workshop $workshopId to the trash; nothing has been deleted")
            }
            target
        }
    }

    /** What is currently in the trash, newest first — the undo surface, and the storage screen's list. */
    suspend fun trashed(context: Context): List<File> = withContext(Dispatchers.IO) {
        mutex.withLock {
            trashDir(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        }
    }

    /**
     * Move a trashed workshop back. Refuses rather than overwriting when a live workshop already
     * holds that directory: the designer restoring a deletion did not ask to destroy whatever has
     * been captured since.
     */
    suspend fun restore(context: Context, trashedDir: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!trashedDir.exists()) return@withLock false
            val originalId = trashedDir.name.substringBeforeLast('-')
            val target = File(rootDir(context), originalId)
            if (target.exists()) return@withLock false
            trashedDir.renameTo(target)
        }
    }

    // ── Media ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Copy a captured file OUT of its content Uri (or out of the cacheDir capture the camera intent
     * wrote) and into this workshop's media directory, then register it in the draft.
     *
     * WHY THE COPY CANNOT WAIT. A content Uri is a permission grant, not a file: it is scoped to the
     * task that received it and is gone after process death, so a draft that stored one has a photo
     * reference that resolves to nothing the next morning. And the camera captures this app makes
     * itself land in `cacheDir/field-captures/` (createAppFile, MainActivity.kt), which Android
     * empties whenever the device is short of space — silently, with no callback, and preferentially
     * when storage is tight, which on a 32 GB field phone two weeks into a study is always.
     *
     * ORDER OF OPERATIONS, and it must not be reversed:
     *
     *  1. Copy the bytes, hash them, and `fd.sync()` — WITHOUT the lock held. Copying a 300 MB video
     *     takes seconds; holding the store lock across it would block every stage screen's auto-save
     *     for the duration, and the auto-saves are what protect the text. The copy is safe unlocked
     *     because it writes to a fresh UUID filename nothing else can be touching.
     *  2. Read dimensions and EXIF from the DURABLE COPY, not from the Uri. One fewer read through
     *     the content provider (which may be a slow network-backed one), and it is the copy whose
     *     metadata we are actually describing.
     *  3. Only then take the lock and register the descriptor. If the process dies between 1 and 3
     *     the bytes are on disk unreferenced — a few wasted megabytes a sweep can reclaim. If it were
     *     reversed, the draft would reference a file that was never finished: a broken image in a
     *     report, which is a data loss the designer discovers at delivery.
     */
    suspend fun importMedia(
        context: Context,
        workshopId: String,
        uri: Uri,
        stageId: String? = null,
        fieldKey: String? = null,
        caption: String? = null
    ): DraftMedia = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            )
            ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "capture-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

        val target = File(mediaDir(context, workshopId), UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: ""))
        val input: InputStream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to read the captured file — it may have already been cleared")

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            input.use { source ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        total += read
                    }
                    out.flush()
                    // The same reason draft.json syncs: without it the bytes are only in the page
                    // cache, and a flat battery leaves a file the directory says is 40 MB and the
                    // disk says is zeros. A photo is worth the fsync; there is exactly one of it.
                    out.fd.sync()
                }
            }
        } catch (e: Throwable) {
            runCatching { target.delete() }
            throw e
        }

        val relativePath = "media/${target.name}"
        val descriptor = DraftMedia(
            id = UUID.randomUUID().toString(),
            relativePath = relativePath,
            originalFilename = originalName,
            mimeType = mimeType,
            mediaType = inferDraftMediaType(mimeType),
            sizeBytes = total,
            capturedAt = Instant.now().toString(),
            stageId = stageId,
            fieldKey = fieldKey,
            caption = caption,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        ).let { base -> if (base.mediaType == "IMAGE") base.withImageMetadata(target) else base }

        // Registration is the only part that needs the lock, and it is a map/list append measured in
        // microseconds — see the ordering note in the KDoc above for why the copy is not in here.
        update(context, workshopId) { draft ->
            val stage = stageId?.let { draft.stages[it] }
            draft.copy(
                media = draft.media + descriptor,
                stages = if (stage == null) draft.stages else {
                    draft.stages + (stage.stageId to stage.copy(mediaIds = stage.mediaIds + descriptor.id))
                }
            )
        }
        descriptor
    }

    /**
     * Dimensions and orientation, read from the durable copy.
     *
     * `inJustDecodeBounds = true` means the decoder parses the header and returns without allocating
     * a single pixel — a full decode of a 12-megapixel JPEG is ~48 MB of heap, and doing that once
     * per import on a low-end handset is an OutOfMemoryError during a photo burst.
     *
     * Uses `android.media.ExifInterface`, the PLATFORM one, not `androidx.exifinterface`. That
     * artifact is not a dependency of this module (see app/build.gradle.kts) and pulling one in for
     * two tag reads is not worth it; the platform class has had TAG_ORIENTATION since API 5 and
     * TAG_DATETIME_ORIGINAL since API 24, and minSdk here is 26. If androidx.exifinterface is ever
     * added for its wider format support (HEIF/RAW write), this is the only call site to switch.
     *
     * Every read is wrapped: a corrupt or partially-written header must degrade to "no metadata", not
     * take down the import and lose the photo that the metadata was merely describing.
     */
    private fun DraftMedia.withImageMetadata(file: File): DraftMedia {
        val bounds = runCatching {
            BitmapFactory.Options().also { options ->
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        }.getOrNull()

        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL

        // The eight EXIF orientations flattened to (degrees clockwise, mirrored). The transpose and
        // transverse cases are rare but real (some front cameras and some editors emit them), and
        // treating them as "normal" prints those photos rotated by 90 degrees in the final report.
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
        val mirrored = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

        val coordinates = FloatArray(2)
        val hasLocation = exif?.let { runCatching { it.getLatLong(coordinates) }.getOrDefault(false) } ?: false

        return copy(
            width = bounds?.outWidth?.takeIf { it > 0 } ?: 0,
            height = bounds?.outHeight?.takeIf { it > 0 } ?: 0,
            rotationDegrees = rotation,
            mirrored = mirrored,
            exifDateTime = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif?.getAttribute(ExifInterface.TAG_DATETIME),
            latitude = if (hasLocation) coordinates[0].toDouble() else null,
            longitude = if (hasLocation) coordinates[1].toDouble() else null
        )
    }

    /**
     * Forget a media item AND remove its bytes.
     *
     * The one place in this file that really deletes, and it is safe to because it is scoped to a
     * single attachment the designer explicitly removed — not to a document, and never triggered by
     * a sync, a parse failure or a cleanup pass. The descriptor goes first and the file second: a
     * kill in between leaves an unreferenced file (wasted space) rather than a referenced missing one
     * (a hole in the report).
     *
     * ── IT DELETES ONE NAMED FILE, NEVER "EVERY FILE NO DESCRIPTOR CLAIMS" ───────────────────────
     *
     * It used to list `media/` and delete every file the post-removal draft did not name, which is a
     * whole-directory sweep decided against a SNAPSHOT — and [importMedia] deliberately copies the
     * bytes with this store's lock NOT held (its step 1 of 3), registering the descriptor only
     * afterwards. So two windows existed, and both are ordinary inside one stage screen: attach
     * multi-selects five photographs and imports them in one coroutine, each copy suspending on
     * `Dispatchers.IO` for the seconds a multi-megabyte file takes, which frees the same scope to run
     * a detach when the designer taps the X on a photograph attached earlier. The sweep then deleted
     * the file the import was mid-copy (the unlink succeeds while the writer's fd is open, so the copy
     * "succeeds" and `fd.sync()` returns) and any file whose descriptor was registered after this
     * function's own `update` returned. The draft was left holding a caption and a stage assignment
     * for bytes that no longer had a directory entry — and `uploadPending` then told the designer the
     * file "is no longer in this workshop's media folder on this device … Nothing has been deleted by
     * the app", which was false and pointed the investigation away from us.
     *
     * The path is captured INSIDE the transform, under the store's lock, so it is the path of the
     * descriptor that was actually removed by this call and not of whatever the directory happened to
     * hold a moment later. Bytes that genuinely have no descriptor are now left alone: they are
     * wasted space, which is recoverable, and the alternative cost them a photograph. If reclaiming
     * them is ever wanted it belongs in a start-up sweep that re-reads the draft under the lock at the
     * moment it decides — `sweepStagedObjects` is the shape of it — and never in a detach.
     */
    suspend fun removeMedia(context: Context, workshopId: String, mediaId: String) {
        var removedPath: String? = null
        val doomed = update(context, workshopId) { draft ->
            // Captured here and nowhere else: this lambda runs under the store's lock, against the
            // document that is actually on disk. Reading the descriptor before or after the `update`
            // would reintroduce exactly the race this function was rewritten to close.
            removedPath = draft.media.firstOrNull { it.id == mediaId }?.relativePath
            draft.copy(
                media = draft.media.filterNot { it.id == mediaId },
                stages = draft.stages.mapValues { (_, stage) ->
                    stage.copy(mediaIds = stage.mediaIds.filterNot { it == mediaId })
                }
            )
        }
        withContext(Dispatchers.IO) {
            val path = detachedFileToDelete(removedPath, doomed.media) ?: return@withContext
            val file = mediaFile(context, workshopId, path)
            // `isFile` for the reason `statusOf` gives: a blank or odd relative path resolves to the
            // workshop directory itself, and `delete()` on a directory is not something this function
            // is ever allowed to attempt.
            if (file.isFile) runCatching { file.delete() }
        }
    }

    /**
     * The ONE file a detach may delete, or null for "delete nothing".
     *
     * Pure, and separate from [removeMedia], so the rule can be pinned by a test with no filesystem —
     * `DwDetachDeletesOneFileTest`. The rule is the whole fix: a detach deletes the bytes of the
     * descriptor it removed and NOTHING else. What it must never go back to is "every file under
     * `media/` that the post-removal draft does not name", which is a directory sweep decided against
     * a snapshot while [importMedia] is copying bytes with this store's lock deliberately not held.
     *
     * Three refusals, each of which was a way the sweep destroyed a photograph or could:
     *  * no descriptor was removed (already detached, or an unknown id) — nothing is ours to delete;
     *  * the descriptor carried no path — a blank one resolves to the workshop DIRECTORY;
     *  * a surviving descriptor still names the same file — the same id can be detached twice and two
     *    descriptors can in principle share a path, and the bytes are still owed to somebody.
     */
    internal fun detachedFileToDelete(removedPath: String?, survivors: List<DraftMedia>): String? {
        val path = removedPath?.takeIf { it.isNotBlank() } ?: return null
        if (survivors.any { it.relativePath == path }) return null
        return path
    }

    /**
     * How many bytes could be reclaimed by [releaseUploadedMedia], and across how many files.
     *
     * Counts ONLY files the server has acknowledged an id for and whose bytes are still here — the
     * exact set that action would delete — so the storage screen can put a real number in front of
     * the designer before they decide. A count that included unconfirmed captures would be offering
     * to delete the only copy of them.
     */
    suspend fun reclaimableMedia(context: Context, draft: WorkshopDraft): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            val files = draft.media
                .filter { it.isConfirmedRemote }
                .map { mediaFile(context, draft.workshopId, it) }
                .filter { it.isFile }
            files.size to files.sumOf { it.length() }
        }

    /**
     * Delete the local bytes of media the SERVER HAS CONFIRMED, and nothing else. Deliberate,
     * user-initiated, and never called by a sync, a timer or a cleanup pass.
     *
     * WHY THE DEFAULT IS TO KEEP EVERYTHING FOR EVER. The phone is where the offline report is
     * generated: `imageFor` in ui/designworkshop/ReportScreen.kt resolves every photograph to a file
     * under `filesDir/workshops/<id>/media/`, and the .docx and .pdf writers read those bytes
     * directly. A workshop whose photographs were uploaded and then swept off the device can no
     * longer be exported on the device that captured it — the export still succeeds, silently, and
     * the report comes out with the pictures missing. That is worse than running out of space,
     * because running out of space says so. So an upload buys the designer a SECOND copy; it never
     * costs them the first one.
     *
     * WHAT THIS STILL GUARANTEES WHEN IT IS RUN. The [DraftMedia] descriptor is KEPT in every case —
     * its caption, its stage, its dimensions and its remote id are the record that the photograph
     * exists and where it now lives, and dropping it would make the file unrecoverable even though
     * the server still holds it. Only bytes that are provably duplicated on the server go, so the
     * worst outcome is a report that has to be generated with a connection instead of without one.
     *
     * Returns how many files were released and how many bytes that freed, so the caller can say it.
     */
    suspend fun releaseUploadedMedia(context: Context, workshopId: String): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            val draft = load(context, workshopId) ?: return@withContext 0 to 0L
            var released = 0
            var bytes = 0L
            draft.media.forEach { descriptor ->
                // The condition is the confirmed remote id and nothing else. Not "the stage synced",
                // not "the upload was attempted" — an id the server said out loud, which is the only
                // evidence that a second copy of these bytes exists anywhere.
                if (!descriptor.isConfirmedRemote) return@forEach
                val file = mediaFile(context, workshopId, descriptor)
                if (!file.isFile) return@forEach
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    released++
                    bytes += length
                }
            }
            released to bytes
        }

    /**
     * Media the draft references but whose bytes are not on disk.
     *
     * Reported, never pruned. A missing file is usually recoverable — restored from a backup, or
     * copied off the phone by hand — and a store that quietly drops the descriptor destroys the only
     * record that the photo ever existed, along with its caption and which stage it answered.
     */
    suspend fun missingMedia(context: Context, draft: WorkshopDraft): List<DraftMedia> =
        withContext(Dispatchers.IO) {
            draft.media.filterNot { mediaFile(context, draft.workshopId, it).exists() }
        }

    // ── Completeness ─────────────────────────────────────────────────────────────────────────────

    /**
     * Per-stage answered/required counts, computed entirely from what is in the draft.
     *
     * Nothing here consults the network or the template cache, because the screen that shows it is
     * the screen a designer looks at in a courtyard to decide whether they can pack up. A progress
     * figure that needs a server to be honest is a progress figure that lies exactly when it matters.
     */
    fun completeness(draft: WorkshopDraft): DraftCompleteness {
        val stages = draft.stages.values.sortedBy { it.order }.map { stage ->
            val requiredKeys = stage.requiredKeys.count()
            val filledKeys = stage.requiredKeys.count { key -> isAnswered(stage.values[key]) }
            // A repeating group and an attachment quota each count as ONE more thing that has to be
            // satisfied, rather than one per row/file: a stage needing three photos should not read
            // as two-thirds done for having two, when its eleven text answers are all still blank.
            val rowsRequired = if (stage.minimumRows > 0) 1 else 0
            val rowsFilled = if (stage.minimumRows > 0 && stage.rows.size >= stage.minimumRows) 1 else 0
            val mediaRequired = if (stage.minimumMedia > 0) 1 else 0
            val mediaFilled = if (stage.minimumMedia > 0 && stage.mediaIds.size >= stage.minimumMedia) 1 else 0
            StageCompleteness(
                stageId = stage.stageId,
                title = stage.title,
                order = stage.order,
                filled = filledKeys + rowsFilled + mediaFilled,
                required = requiredKeys + rowsRequired + mediaRequired,
                markedComplete = stage.completedAt != null
            )
        }
        return DraftCompleteness(
            stages = stages,
            filled = stages.sumOf { it.filled },
            required = stages.sumOf { it.required }
        )
    }

    /**
     * Whether a captured value counts as an answer.
     *
     * Absent, null, blank text, an empty array and an empty object are all "not answered". The blank
     * case is the one that matters: a text field that has been focused and left, or cleared, holds
     * `""`, and counting that as filled is how a stage reports itself complete with nothing in it.
     * `false` and `0` ARE answers — "no, this workshop has no power supply" is a finding.
     */
    private fun isAnswered(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        // `content` is the unquoted text for a string and the literal for a number/boolean, so one
        // blank check covers both. A JSON `null` arrives as [JsonNull] and is caught by the branch
        // above, never here.
        is JsonPrimitive -> value.content.isNotBlank()
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }

    /**
     * The name the providing app gives this file, or its last path segment.
     *
     * `internal` rather than private because the bulk photo intake has to NAME two hundred picked
     * files on screen BEFORE any of them is copied — the whole point of that surface is that nothing
     * moves until a designer confirms — and a second `OpenableColumns` query written beside it would
     * be a second answer to "what is this file called": the intake would list one name and the
     * imported descriptor would record another for the same photograph.
     */
    internal fun displayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    /** Same vocabulary as [OfflineOutbox]'s, so a draft item and an outbox item describe a file identically. */
    private fun inferDraftMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }
}
