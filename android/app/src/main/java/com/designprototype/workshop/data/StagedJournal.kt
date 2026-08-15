package com.designprototype.workshop.data

import android.content.Context
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One key in the bucket that no record points at yet, and the process that put it there. */
@Serializable
internal data class StagedObject(
    val objectKey: String,
    val owner: String,
    val checksum: String? = null,
    /**
     * The S3 multipart upload this key belongs to, when it is one.
     *
     * WITHOUT IT THE SWEEP CANNOT RECLAIM A LARGE FILE. Until a multipart upload is completed there
     * is no object at the key at all — only uploaded parts, which are billed and which nothing but
     * `AbortMultipartUpload` can remove, and which that call needs the uploadId to name. The journal
     * recorded the key alone, so a kill part-way through a long video (exactly the transfer most
     * likely to be interrupted, on exactly the connection most likely to interrupt it) left parts in
     * the bucket that no sweep, then or ever, could find its way back to.
     */
    val uploadId: String? = null
)

/**
 * Journal of staged objects — bytes already in the bucket that no record points at yet — and the
 * sweep that reclaims the ones nothing ever claimed.
 *
 * Every upload presigns a key and pushes the bytes well before `/media/complete` links them to a
 * record; eager pre-upload widens that gap to however long the researcher spends typing. If the
 * process dies in between (app swiped away mid-transfer, a low-memory kill, a flat battery in a
 * workshop) the object stays in storage and nothing in memory knows it exists. So the key goes to
 * disk the moment it is presigned, with the same file + Mutex + kotlinx mechanism the offline outbox
 * uses, and the next launch deletes whatever is still unclaimed.
 *
 * Ownership is per process, not per object age: an entry written by THIS process belongs to a form
 * the user may still be filling in, so only entries carrying some earlier [owner] are swept. (The
 * web has to approximate that with a heartbeat because one browser has many tabs; a phone has one
 * process, and a process that is gone cannot still be waiting to save.)
 */
object StagedJournal {
    /** Identifies this process's entries; anything else in the file was left behind by a dead one. */
    private val owner = UUID.randomUUID().toString()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    // Captured on the first record/sweep so [drop] and [checksumFor] — reached from save paths that
    // carry no Context — can still reach the file. An applicationContext, so nothing is leaked.
    @Volatile
    private var appContext: Context? = null

    /**
     * WHERE THE JOURNAL LIVES, AND WHY THE FILE LAYER BELOW TAKES A DIRECTORY RATHER THAN A CONTEXT.
     *
     * Every public entry point still takes a [Context] — that is the only thing a caller has — and
     * resolves it here, once. The read/write/salvage layer underneath is handed the DIRECTORY, which
     * is the whole of what it needs and the whole of what it may touch.
     *
     * That seam is not decoration. The defect this file was fixed for is a torn write and a damaged
     * file read as an empty one, and the only way to prove either is closed is to put a truncated
     * file on disk and read it back. This module's JVM unit tests have no Android framework in them
     * (`testOptions.unitTests.isReturnDefaultValues`, and no Robolectric on the classpath), so a test
     * cannot manufacture a `Context` — but it can manufacture a temp directory. Collapsing this back
     * into a Context-only API would move the proof to an instrumented run that nothing in CI performs,
     * which is how this class came to have no test in the first place.
     */
    private fun file(dir: File): File = File(dir, "staged-objects.json")

    private fun dirOf(context: Context): File = context.filesDir

    /**
     * The owner stamped on an entry recovered out of a damaged journal.
     *
     * It must not equal [owner] and must not equal any UUID a past run could have generated, because
     * [sweep] reclaims exactly the entries whose owner is not this process's. A recovered key belongs
     * to a run that was killed — that is what damaged the file — so it is an orphan by construction
     * and has to be swept, not held back as though a form on this screen might still claim it.
     */
    internal const val RECOVERED_OWNER = "recovered-from-damaged-journal"

    /**
     * The path of a damaged journal whose bytes are STILL THE ONLY COPY OF THEMSELVES, because the
     * set-aside below could not be performed. Null whenever the last read found nothing to rescue or
     * rescued it successfully.
     *
     * WHY A FLAG AND NOT A COMMENT SAYING "BE CAREFUL". [quarantine] used to rename the file with a
     * bare `runCatching { file.renameTo(kept) }` — which swallows the exception AND ignores the
     * `false` that a failed rename returns — and then rewrote the journal regardless. So on the one
     * path where the rescue did not happen, the very next line destroyed the bytes the rescue existed
     * to keep, and nothing anywhere said so. Not writing inside [quarantine] closes only half of that:
     * every entry point is `read` then `write` in one critical section, so `record`'s own write would
     * finish the destruction a few microseconds later, carrying only the entries salvage could decode
     * and dropping the fragment it could not. This flag is what lets [write] refuse.
     *
     * Re-decided on every [read] — which every write in this class is preceded by — so a rescue that
     * failed once because the file was momentarily unrenamable does not wedge the journal for the life
     * of the process: the next read retries the set-aside against a fresh timestamp and, when it
     * works, the refusal disappears on its own.
     */
    @Volatile
    private var unrescued: String? = null

    /** Where a damaged journal is kept. Timestamped so a second failure cannot clobber the first. */
    private fun damagedSibling(file: File): File =
        File(file.parentFile, "staged-objects.damaged-${System.currentTimeMillis()}.json")

    /**
     * Read the journal, or set a damaged one aside and rescue what is still legible from it.
     *
     * A FILE THAT WILL NOT PARSE IS NEVER TREATED AS AN EMPTY JOURNAL. It used to be — a bare
     * `runCatching { … }.getOrDefault(emptyList())` — paired with a `writeText` that truncates before
     * it writes. So a kill inside that window left a truncated array, the next read yielded zero
     * entries with no error and no trace, and the next [record] overwrote the remains. What is lost
     * there is not visible to anyone: it is the `uploadId` of a multipart upload, and until a
     * multipart is completed there is no object at the key at all — only uploaded parts, which are
     * billed and which nothing but `AbortMultipartUpload` can remove, and which that call needs the
     * uploadId to name. Losing the journal entry means those bytes can never be reclaimed by anything,
     * then or later. This is the same silent-emptiness failure `OfflineOutbox.read` and
     * `WorkshopDraftStore.readLocked` each carry a paragraph about; the three now behave alike.
     *
     * A ZERO-LENGTH FILE COUNTS AS DAMAGE TOO. [write] never produces one — it deletes the file for an
     * empty journal rather than writing `[]` — so a blank file is the fingerprint of exactly the torn
     * write this used to be able to do.
     *
     * Called under [mutex] by every entry point, which is what makes it safe for a READ to write: the
     * quarantine and the rewrite below happen inside the same critical section as the caller's own
     * update, so no other coroutine can observe the half-recovered state.
     *
     * [keptFile] NAMES THE FILE A DAMAGED JOURNAL IS SET ASIDE AS, and exists for the same reason the
     * layer below takes a directory rather than a Context: the failure path has to be provable on the
     * host. A rescue that cannot be performed is the branch that used to destroy the journal, and the
     * only way to stage it portably is to hand the rescue a destination it cannot use — a rename onto
     * a non-empty directory fails on Windows and on Linux alike, where "make the directory read-only"
     * does not. Production never passes it; leave the default alone and this reads exactly as before.
     */
    internal fun read(dir: File, keptFile: (File) -> File = ::damagedSibling): List<StagedObject> {
        val file = file(dir)
        // Re-decided here, before anything can conclude otherwise: a journal that is absent or intact
        // has no unrescued damage in it, whatever an earlier read of an earlier state concluded. Kept
        // to THIS file rather than cleared wholesale, so that reading one store can never lift the
        // protection over another — production has a single journal in filesDir, but a flag that is
        // right only because there happens to be one of them is a trap for whoever adds the second.
        if (unrescued == file.path) unrescued = null
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { return quarantine(dir, file, keptFile(file), emptyList()) }
        if (text.isBlank()) return quarantine(dir, file, keptFile(file), emptyList())
        return runCatching { json.decodeFromString<List<StagedObject>>(text) }
            .getOrElse { quarantine(dir, file, keptFile(file), salvage(text)) }
    }

    /**
     * Move a damaged journal aside, keeping every byte, and re-journal whatever [recovered] could be
     * read out of it so the next sweep can still name those keys.
     *
     * The rename comes FIRST and the rewrite second. If the rewrite then fails, the original bytes are
     * still on disk under a name a support request can ask for; the other order would leave a moment
     * in which neither file holds them. Nothing here is shown to the designer — no fieldwork is at
     * risk, only storage the operator pays for — which is why this quarantines silently where
     * `OfflineOutbox.quarantine` raises an alert. That asymmetry is deliberate: a card about a bucket
     * key is a card a designer in a courtyard can do nothing with.
     *
     * ── AND THE RENAME IS CHECKED, WHICH IT WAS NOT ───────────────────────────────────────────────
     *
     * This was `runCatching { file.renameTo(kept) }` followed unconditionally by the rewrite. That
     * swallows the exception and, worse, discards the `false` a failed rename RETURNS — `renameTo`
     * reports failure by return value, not by throwing, so `runCatching` sees a perfectly successful
     * call that did nothing. The rewrite then replaced (or, for an unsalvageable file, DELETED — see
     * [write], where an empty journal is the absence of the file) the one copy of the bytes this
     * function exists to keep. A durability fix that loses the data on its unhappy path is worse than
     * no fix at all, because everything downstream now trusts it.
     *
     * So: `Files.move` rather than `renameTo`, for the reason spelled out at length in [write] — on
     * the host that runs these unit tests `renameTo` cannot replace an existing destination and
     * answers `false`, which is precisely the signal that was being thrown away — and if the move does
     * not happen, NOTHING IS WRITTEN. The damaged file stays exactly where it is, the next read
     * retries the rescue with a fresh name, and [unrescued] stops [write] from finishing off what this
     * function declined to do.
     *
     * What the caller sees either way is the salvage, never an empty list it cannot tell apart from
     * "nothing was staged". That is this repository's standing rule and the whole subject of the
     * finding this class was fixed for: a silent emptiness is the worst outcome available. The keys
     * are returned even when they could not be re-journalled, so THIS launch's sweep can still name
     * them; if the process dies first, the damaged file is still on disk for the next one to try.
     */
    private fun quarantine(
        dir: File,
        file: File,
        kept: File,
        recovered: List<StagedObject>
    ): List<StagedObject> {
        // Stamped as somebody else's, whatever they claimed to be: see [RECOVERED_OWNER].
        val orphaned = recovered.map { it.copy(owner = RECOVERED_OWNER) }
        val setAside = runCatching {
            Files.move(file.toPath(), kept.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!setAside) {
            unrescued = file.path
            return orphaned
        }
        // The rewrite may still fail — a full disk, a directory that vanished — and that is survivable
        // in a way the set-aside is not: the bytes are already under [kept], and the keys are in the
        // list being returned, so this launch's sweep still reclaims them and the next launch finds
        // the salvage in the damaged file. Hence a `runCatching` here and a hard refusal above.
        runCatching { write(dir, orphaned) }
        return orphaned
    }

    /**
     * The entries still legible in a journal the array parser gave up on.
     *
     * A truncated file is `[{…},{…},{"objectKey":"med` — every entry before the kill is intact and
     * only the last one is a fragment, so scanning for balanced top-level `{}` runs and decoding each
     * on its own recovers all but one key. [StagedObject] has no nested objects, so brace depth is a
     * complete description of an entry's extent; the quote/escape tracking is here because an S3 key
     * is caller-supplied text and may legally contain a brace.
     *
     * Deliberately lenient and deliberately not a JSON parser: anything that does not decode is
     * skipped rather than aborting the salvage, because one unreadable fragment must not cost the
     * twenty keys in front of it.
     */
    private fun salvage(text: String): List<StagedObject> {
        val recovered = ArrayList<StagedObject>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching { json.decodeFromString<StagedObject>(text.substring(start, index + 1)) }
                            .onSuccess { recovered.add(it) }
                        start = -1
                    }
                    if (depth < 0) return recovered // structurally hopeless from here on
                }
            }
        }
        return recovered
    }

    /**
     * Replace the journal as one indivisible step: temp file in the SAME directory, flushed all the
     * way to storage, then renamed over the real one.
     *
     * This used to be a bare `writeText`, which truncates and then writes, so a kill in that window
     * left a truncated array — the damage [read] above now has to cope with. It is the identical
     * three-step `OfflineOutbox.write` and `WorkshopDraftStore.writeLocked` use, and each detail is
     * load-bearing in the same way: the temp file must be in the same directory or the rename is not
     * atomic, and `fd.sync()` must precede the rename or a power loss publishes a directory entry
     * pointing at zero bytes. The KDoc on this object has always claimed "the same file + Mutex +
     * kotlinx mechanism the offline outbox uses"; until now that was true of the mutex and false of
     * the write.
     *
     * An EMPTY journal is the absence of the file, not `[]` on disk. That is what lets [read] treat a
     * zero-length file as damage rather than as an empty document.
     *
     * A JOURNAL WHOSE DAMAGE COULD NOT BE SET ASIDE IS NOT REPLACED — it is refused, out loud. See
     * [unrescued] for why the refusal has to live here and not only in [quarantine]: every entry point
     * is a `read` and then a `write` in one critical section, so the write a few lines after a failed
     * rescue is the one that would actually destroy the bytes, and it would do it while carrying only
     * what salvage could decode. The throw is deliberate and it is the loud half of the fix. It costs
     * a `record` its journal line for the key being uploaded, which is a real cost — but the condition
     * that produces it is a filesystem that will not rename a file inside a directory it is still
     * writing to, i.e. one on which the write below was about to fail anyway, and between an entry it
     * cannot add and entries it would silently erase, this class keeps what it already has.
     */
    internal fun write(dir: File, entries: List<StagedObject>) {
        val target = file(dir)
        if (unrescued == target.path) {
            throw IOException(
                "The staged-object journal ${target.name} is damaged and could not be set aside, so it " +
                    "will not be overwritten; the keys it holds are still reclaimable from it."
            )
        }
        if (entries.isEmpty()) {
            target.delete()
            return
        }
        val temp = File(target.parentFile, "${target.name}.writing")
        val bytes = json.encodeToString(entries).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // `Files.move(REPLACE_EXISTING)` and NOT `File.renameTo`, which `OfflineOutbox` and
            // `StageSchemaStore` still use.
            // On the device they are the same call — both reach `rename(2)`, which replaces the
            // destination atomically. They are NOT the same on the host that runs this module's unit
            // tests: `File.renameTo` maps to `MoveFileExW` without the replace flag, so it returns
            // FALSE whenever the target already exists. Measured on this machine, JDK 17.0.2:
            // renaming over an existing file returns false and leaves the old contents in place. A
            // journal is replaced far more often than it is created, so with `renameTo` every test of
            // a second write would fail here for a reason the product does not have — which is how a
            // durable store ends up with no test at all.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    /**
     * Note a presigned key as staged, before the bytes move, so a kill mid-transfer is still covered.
     * Called again with the [checksum] once the transfer has hashed the content — the staged object's
     * hash has to outlive the upload coroutine to reach the save that eventually claims it.
     *
     * MERGES rather than replaces. Each call knows one thing: the presign knows the [uploadId], the
     * finished transfer knows the [checksum], and neither knows the other's. Overwriting the row
     * would mean whichever came second erased what the first had learned — and for a multipart that
     * is the only handle on its uploaded parts.
     */
    suspend fun record(
        context: Context,
        objectKey: String,
        checksum: String? = null,
        uploadId: String? = null
    ): Unit =
        // On IO throughout: these are reached from save paths running on the main dispatcher, and the
        // journal is worthless if writing it stutters the form the researcher is filling in.
        withContext(Dispatchers.IO) {
            mutex.withLock {
                appContext = context.applicationContext
                val dir = dirOf(context)
                val entries = read(dir)
                val existing = entries.firstOrNull { it.objectKey == objectKey }
                write(
                    dir,
                    entries.filterNot { it.objectKey == objectKey } + StagedObject(
                        objectKey = objectKey,
                        owner = owner,
                        checksum = checksum ?: existing?.checksum,
                        uploadId = uploadId ?: existing?.uploadId
                    )
                )
            }
        }

    /** The content hash recorded for a staged object while its bytes were uploaded, if any. */
    suspend fun checksumFor(objectKey: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock null
            read(dirOf(context)).firstOrNull { it.objectKey == objectKey }?.checksum
        }
    }

    /** Forget a key: it is now attached to a record, or has been deliberately deleted. */
    suspend fun drop(objectKey: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock
            val dir = dirOf(context)
            val entries = read(dir)
            if (entries.any { it.objectKey == objectKey }) write(dir, entries.filterNot { it.objectKey == objectKey })
        }
    }

    /**
     * Delete every object a previous run left staged, returning how many were reclaimed. [delete] is
     * handed the key and, for an interrupted multipart, the [StagedObject.uploadId] its parts belong
     * to; it reports whether the server settled the key — removed it, or refused because a record now
     * owns it. Anything unsettled (no signal, a gateway failure) stays journalled for the next launch
     * rather than being silently abandoned in the bucket.
     */
    suspend fun sweep(context: Context, delete: suspend (objectKey: String, uploadId: String?) -> Boolean): Int {
        val orphans = withContext(Dispatchers.IO) {
            mutex.withLock {
                appContext = context.applicationContext
                read(dirOf(context)).filter { it.owner != owner }.map { it.objectKey to it.uploadId }
            }
        }
        var reclaimed = 0
        for ((objectKey, uploadId) in orphans) {
            if (delete(objectKey, uploadId)) {
                drop(objectKey)
                reclaimed++
            }
        }
        return reclaimed
    }
}
