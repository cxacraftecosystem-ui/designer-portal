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

    suspend fun count(context: Context): Int =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).size } }

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

    /** Remove a synced entry and delete the local media copies it owned. */
    suspend fun remove(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context).filterNot { it.id == entry.id }) }
        entry.media.forEach { runCatching { File(it.localPath).delete() } }
    }

    /** Copy a captured content Uri into local app storage so it survives offline until uploaded. */
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
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("Unable to read the captured media for offline storage")
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
