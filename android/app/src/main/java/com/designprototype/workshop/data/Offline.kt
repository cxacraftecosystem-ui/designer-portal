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
    val skewRun: String? = null
)

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
 */
fun offlineSavedMessage(result: OfflineQueueResult, isCorrection: Boolean): String {
    val head = if (isCorrection) {
        "This correction is saved on this device and will be sent when you have a signal. Until then " +
            "the office still sees the earlier version. When it does go, it replaces the whole record " +
            "— so if somebody else edits it before then, your version wins and theirs is lost. Tell " +
            "them if that matters."
    } else {
        "Saved on this device. It will be sent when you have a signal."
    }
    if (result.allFilesQueued) return head
    val names = result.unreadableFiles.joinToString(", ")
    return "$head " +
        "${result.unreadableFiles.size} file(s) could NOT be read and are not in it ($names) — " +
        "the record is safe, those captures are not. Take them again if you still can."
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
     */
    suspend fun count(context: Context): Int =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).count { it.failure == null } } }

    /** Both halves of the queue in ONE read, so the two numbers cannot come from different moments. */
    suspend fun counts(context: Context): OutboxCounts = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read(context)
            OutboxCounts(
                waiting = entries.count { it.failure == null },
                refused = entries.count { it.failure != null },
            )
        }
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
     */
    suspend fun markFailure(
        context: Context,
        entryId: String,
        reason: String,
        skewRun: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId }) {
                write(
                    context,
                    current.map {
                        if (it.id == entryId) {
                            it.copy(failure = reason, failedAt = Instant.now().toString(), skewRun = skewRun)
                        } else {
                            it
                        }
                    }
                )
            }
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
     * @return true when an entry with that id was found and unmarked.
     */
    suspend fun clearFailure(context: Context, entryId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.none { it.id == entryId && it.failure != null }) return@withLock false
            write(
                context,
                current.map {
                    if (it.id == entryId) it.copy(failure = null, failedAt = null, skewRun = null) else it
                }
            )
            true
        }
    }

    /** Unmark every refusal at once, for "try all of them again" after a sign-in or an update. */
    suspend fun clearAllFailures(context: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            val refused = current.count { it.failure != null }
            if (refused == 0) return@withLock 0
            write(context, current.map { it.copy(failure = null, failedAt = null, skewRun = null) })
            refused
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
