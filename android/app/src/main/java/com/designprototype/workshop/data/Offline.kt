package com.designprototype.workshop.data

import com.designprototype.workshop.transcript.data.SpokenLanguageChoices
import com.designprototype.workshop.transcript.data.TranscribeChoices
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
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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
    val linkedType: String? = null,
    val stepIndex: Int? = null,
    val purpose: String? = null,
    val designWorkshopId: String? = null,
    val location: LocationRequest? = null,
    val spokenLanguage: String? = null,
)

@Serializable
data class PendingEntry(
    val id: String,
    val type: String,
    val payloadJson: String,
    val label: String,
    val media: List<PendingMedia> = emptyList(),
    val createdAt: String,
    val targetId: String? = null,
    val createdId: String? = null,
    val createdStepIds: List<String> = emptyList(),
    val uploadedMedia: List<Int> = emptyList(),
    val failure: String? = null,
    val failedAt: String? = null,
    val skewRun: String? = null,
    val conflict: Boolean = false,
    val unfiled: Map<String, String> = emptyMap(),
    val danglingField: String? = null,
    val ownerUserId: String? = null,
    val clientKey: String? = null,
    val idempotencyKey: String? = null,
    val changedKeys: Set<String>? = null,
    val baseUpdatedAt: String? = null,
    val staleCorrection: Boolean = false,
    val currentUpdatedAt: String? = null,
    val tempId: String? = null,
    val staleRetried: Boolean = false,
    val duplicateCandidates: List<OutboxDuplicateCandidate> = emptyList(),
    val attachedToExisting: Boolean = false,
    val conflictingAnswerIds: List<String> = emptyList(),
) {
    val clearedLinkKeys: Set<String>
        get() = unfiled.filterValues { it == UNFILED_BY_CHOICE }.keys

    val emptyPickerKeys: Set<String>
        get() = unfiled.filterValues { it == UNFILED_NO_OPTIONS }.keys

    val danglingKeys: List<String>
        get() = danglingField?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

const val UNFILED_BY_CHOICE = "chosen"

const val UNFILED_NO_OPTIONS = "noOptions"

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

internal val WORKSHOP_LINK_KEYS: Set<String> = setOf("designWorkshopId", "workshopId")

internal fun unfiledLinkReason(selectedId: String, baselineId: String, hadOptions: Boolean): String? =
    when {
        selectedId.isNotBlank() -> null
        baselineId.isNotBlank() -> UNFILED_BY_CHOICE
        hadOptions -> UNFILED_BY_CHOICE
        else -> UNFILED_NO_OPTIONS
    }

internal fun workshopUnfiledReasons(
    designWorkshop: String? = null,
    workshop: String? = null,
): Map<String, String> = buildMap {
    designWorkshop?.let { put("designWorkshopId", it) }
    workshop?.let { put("workshopId", it) }
}

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
    val purpose: String? = null,
    val designWorkshopId: String? = null,
    val location: LocationRequest? = null,
)

data class OfflineQueueResult(
    val entryId: String,
    val queuedFiles: Int,
    val unreadableFiles: List<String> = emptyList(),
) {
    val allFilesQueued: Boolean get() = unreadableFiles.isEmpty()
}

fun offlineSavedMessage(
    result: OfflineQueueResult,
    isCorrection: Boolean,
    serverDown: Boolean = false,
    signInNeeded: Boolean = false,
): String {
    val head = if (signInNeeded && !isCorrection) {
        "Saved on this phone; it will upload once you sign in again."
    } else if (serverDown && !isCorrection) {
        "Saved on this phone; it will upload when the server is reachable."
    } else if (isCorrection) {
        "Correction saved on this phone; the office still sees the earlier version. It will be sent when you have a signal."
    } else {
        "Saved on this phone. It sends when you're online."
    }
    if (result.allFilesQueued) return head
    val names = result.unreadableFiles.joinToString(", ")
    return "$head ${plural(result.unreadableFiles.size, "file")} couldn't be read and ${if (result.unreadableFiles.size == 1) "wasn't" else "weren't"} kept ($names) — the record is safe. Take them again if you still can."
}

fun serverDidNotAnswer(error: Throwable): Boolean = when (error) {
    is HttpException -> error.code().let { it == 408 || it == 429 || it >= 500 }
    is IOException -> true
    else -> false
}

fun sessionExpired(error: Throwable): Boolean = error is HttpException && error.code() == 401

private fun stagedFiles(n: Int): String = if (n == 1) "1 file" else "$n files"

private fun endStopped(said: String): String {
    val text = said.trim()
    if (text.isEmpty()) return ""
    return if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) text else "$text."
}

fun outboxConflictSentence(said: String, files: Int, isCorrection: Boolean): String {
    val carrying = if (files > 0) " and the ${stagedFiles(files)} saved with it" else ""
    val isAre = if (files > 0) "are" else "is"
    val server = endStopped(said).let { if (it.isEmpty()) "" else " $it" }
    val standing = "Retrying alone gets the same answer."
    return if (isCorrection) {
        "Not applied — it clashes with an existing record.$server Nothing was deleted: your correction$carrying $isAre still here, and the office still reads the earlier version. $standing Open the clashing record, make the change there, then discard this entry."
    } else {
        "Not saved — a matching record exists.$server Nothing was deleted: this entry$carrying $isAre still here. $standing Open the clashing record, copy anything missing, then discard this entry."
    }
}

private val CLIENT_KEY_TYPES = setOf("workshop", "product", "tool", "process")

fun outboxMintsClientKey(type: String, targetId: String?): Boolean =
    targetId == null && type in CLIENT_KEY_TYPES

private val payloadReader = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun danglingReferenceCandidates(
    payload: String,
    named: List<String> = emptyList(),
    isCorrection: Boolean = false,
    namedOnly: Boolean = false,
): List<String> {
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

internal const val TARGET_RECORD_KEY = "@target"

internal fun referenceFieldNoun(key: String, recordNoun: String): String =
    if (key == TARGET_RECORD_KEY) recordNoun else REFERENCE_FIELD_NOUNS[key] ?: "record"

private fun anyOneOf(nouns: List<String>): String {
    val withArticles = nouns.map { noun ->
        if (noun.first().lowercaseChar() in "aeiou") "an $noun" else "a $noun"
    }
    return when (withArticles.size) {
        0 -> "something"
        1 -> withArticles.single()
        else -> withArticles.dropLast(1).joinToString(", ") + " or " + withArticles.last()
    }
}

fun outboxDanglingSentence(
    said: String,
    nouns: List<String>,
    files: Int,
    isCorrection: Boolean,
): String {
    val subject = if (isCorrection) "This correction" else "This record"
    val head = if (nouns.size == 1) {
        "$subject points at ${anyOneOf(nouns)} that could not be found."
    } else {
        "$subject points at something that could not be found. It is ${anyOneOf(nouns)}."
    }
    val carrying = if (files > 0) " and the ${stagedFiles(files)} saved with it" else ""
    val isAre = if (files > 0) "are" else "is"
    val server = endStopped(said).let { if (it.isEmpty()) "" else " $it" }
    return "$head Nothing is lost — open it, choose another, and it will send.$server This entry$carrying $isAre still here; nothing was deleted. Retrying unchanged gets the same answer."
}

fun outboxSentUnfiledMessage(label: String, nouns: List<String>): String {
    val list = when (nouns.size) {
        0 -> "workshop"
        1 -> nouns.single()
        else -> nouns.dropLast(1).joinToString(", ") + " or " + nouns.last()
    }
    return "“$label” was sent, filed under nothing: there was no $list to choose from on this device. Open the record and file it now."
}

data class RepickOption(val id: String, val label: String, val hint: String? = null)

data class RepickChoices(val options: List<RepickOption>, val listed: Boolean)

fun repickEmptyLine(noun: String, listed: Boolean): String = if (!listed) {
    "The $noun list could not be read just now. The record and its files stay on this phone; try again later."
} else {
    "No $noun is open to this account. An administrator can give you access to one. The record stays here; nothing is deleted."
}

fun outboxDiscardConfirmation(
    label: String,
    files: Int,
    isConflict: Boolean,
    savedOnServer: Boolean = false,
    isDangling: Boolean = false,
): String {
    val carrying = if (files > 0) " and the ${stagedFiles(files)} saved with it" else ""
    val opening = "“$label”$carrying will be deleted from this phone. This cannot be undone"
    if (savedOnServer) {
        val orphaned = if (files > 0) {
            " The ${stagedFiles(files)} weren't uploaded — re-attach them if you can."
        } else {
            ""
        }
        return "$opening. The record is already online; re-entering it would duplicate it.$orphaned"
    }
    val head = "$opening, and none of it has been uploaded."
    if (isDangling) {
        return "$head Only its link is broken — Re-pick it fixes that without losing anything."
    }
    if (!isConflict) return head
    return "$head The record it clashes with is not touched. What goes is this phone's copy — anything in it the other record does not have goes too. Check that first."
}

object ConnectivityObserver {
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var answerForTests: Boolean? = null

    fun isOnline(context: Context): Boolean {
        answerForTests?.let { return it }
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

    private val alert = AtomicReference<String?>(null)

    private fun dir(context: Context): File = File(context.filesDir, "outbox").apply { mkdirs() }
    private fun mediaDir(context: Context): File = File(dir(context), "media").apply { mkdirs() }
    private fun queueFile(context: Context): File = File(dir(context), "queue.json")

    fun takeAlert(): String? = alert.getAndSet(null)

    private fun read(context: Context): List<PendingEntry> {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { error ->
            quarantine(file, "could not be read")
            return emptyList()
        }
        if (text.isBlank()) {
            quarantine(file, "was not saved completely")
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<PendingEntry>>(text) }
            .map { entries -> entries.map { it.copy(payloadJson = AadhaarAtRest.openPayload(it.payloadJson)) } }
            .getOrElse { error ->
                quarantine(file, "could not be read")
                emptyList()
            }
    }

    private fun quarantine(file: File, reason: String) {
        val kept = File(file.parentFile, "queue.damaged-${System.currentTimeMillis()}.json")
        val moved = runCatching { file.renameTo(kept) }.getOrDefault(false)
        alert.set(
            if (moved) {
                "Saved records on this phone $reason. A copy is kept. Ask your admin before uninstalling."
            } else {
                "Saved records on this phone $reason and couldn't be set aside. Ask your admin before uninstalling."
            }
        )
    }

    private fun write(context: Context, entries: List<PendingEntry>) {
        val target = queueFile(context)
        val temp = File(target.parentFile, "${target.name}.writing")
        val sealed = entries.map { it.copy(payloadJson = AadhaarAtRest.sealPayload(it.payloadJson)) }
        val bytes = json.encodeToString(sealed).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
            }
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    suspend fun all(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context) } }

    suspend fun count(context: Context, signedInUserId: String? = null): Int =
        counts(context, signedInUserId).waiting

    suspend fun counts(context: Context, signedInUserId: String? = null): OutboxCounts =
        withContext(Dispatchers.IO) {
            mutex.withLock { outboxCountsOf(read(context), signedInUserId) }
        }

    suspend fun enqueue(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context) + entry) }
        BackgroundSync.requestSoon(context)
    }

    suspend fun update(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entry.id }) {
                write(context, current.map { if (it.id == entry.id) entry else it })
            }
        }
    }

    suspend fun markFailure(
        context: Context,
        entryId: String,
        reason: String,
        skewRun: String? = null,
        conflict: Boolean = false,
        danglingField: String? = null,
        stale: Boolean = false,
        currentUpdatedAt: String? = null,
        duplicateCandidates: List<OutboxDuplicateCandidate> = emptyList(),
        conflictingAnswerIds: List<String> = emptyList(),
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
                                staleCorrection = stale,
                                currentUpdatedAt = currentUpdatedAt,
                                duplicateCandidates = duplicateCandidates,
                                conflictingAnswerIds = conflictingAnswerIds,
                            )
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    suspend fun dropConflictingAnswers(context: Context, entryId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            val entry = current.firstOrNull { it.id == entryId } ?: return@withLock false
            if (entry.conflictingAnswerIds.isEmpty()) return@withLock false
            val rewritten = withoutAnswers(entry.payloadJson, entry.conflictingAnswerIds) ?: return@withLock false
            write(
                context,
                current.map {
                    if (it.id == entryId) {
                        it.copy(
                            payloadJson = rewritten,
                            failure = null,
                            failedAt = null,
                            skewRun = null,
                            conflict = false,
                            danglingField = null,
                            duplicateCandidates = emptyList(),
                            staleCorrection = false,
                            conflictingAnswerIds = emptyList(),
                        )
                    } else {
                        it
                    }
                }
            )
            true
        }
    }

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
                            duplicateCandidates = emptyList(),
                            staleCorrection = false,
                            conflictingAnswerIds = emptyList(),
                        )
                    } else {
                        it
                    }
                }
            )
            true
        }
    }

    suspend fun retypeAadhaar(context: Context, entryId: String, number: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(context)
                val entry = current.firstOrNull { it.id == entryId } ?: return@withLock false
                val rewritten = AadhaarAtRest.retype(entry.payloadJson, number) ?: return@withLock false
                write(
                    context,
                    current.map {
                        if (it.id == entryId) {
                            it.copy(payloadJson = rewritten, failure = null, failedAt = null)
                        } else {
                            it
                        }
                    }
                )
                true
            }
        }

    suspend fun rebaseCorrection(context: Context, entryId: String, currentUpdatedAt: String?): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(context)
                if (current.none { it.id == entryId && it.staleCorrection }) return@withLock false
                write(
                    context,
                    current.map {
                        if (it.id != entryId) it else it.copy(
                            baseUpdatedAt = currentUpdatedAt ?: it.currentUpdatedAt ?: it.baseUpdatedAt,
                            failure = null,
                            failedAt = null,
                            conflict = false,
                            staleCorrection = false,
                            currentUpdatedAt = null,
                        )
                    }
                )
                true
            }
        }

    suspend fun keepServerVersionKeepFiles(context: Context, entryId: String): Boolean =
        withContext(Dispatchers.IO) {
            var found = false
            val dropped = mutex.withLock {
                val current = read(context)
                val entry = current.firstOrNull { it.id == entryId && it.staleCorrection }
                    ?: return@withLock null
                found = true
                if (entry.media.isEmpty()) {
                    write(context, current.filterNot { it.id == entryId })
                    return@withLock entry
                }
                write(
                    context,
                    current.map {
                        if (it.id != entryId) it else it.copy(
                            type = OFFLINE_MEDIA_ONLY,
                            payloadJson = "",
                            media = it.media.map { m -> m.copy(linkedType = m.linkedType ?: it.type, stepIndex = null) },
                            failure = null,
                            failedAt = null,
                            conflict = false,
                            staleCorrection = false,
                            currentUpdatedAt = null,
                            changedKeys = null,
                            baseUpdatedAt = null,
                            idempotencyKey = null,
                        )
                    }
                )
                null
            }
            dropped?.media?.forEach { runCatching { File(it.localPath).delete() } }
            found
        }

    suspend fun advanceBase(context: Context, type: String, targetId: String, updatedAt: String, exceptId: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(context)
                if (current.none { advancesWith(it, type, targetId, exceptId) }) return@withLock
                write(context, current.map {
                    if (advancesWith(it, type, targetId, exceptId)) it.copy(baseUpdatedAt = updatedAt) else it
                })
            }
        }

    suspend fun setTempId(context: Context, entryId: String, tempId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId }) {
                write(context, current.map { if (it.id == entryId) it.copy(tempId = tempId) else it })
            }
        }
    }

    suspend fun failed(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).filter { it.failure != null } } }

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
                        it.copy(
                            failure = null,
                            failedAt = null,
                            skewRun = null,
                            conflict = false,
                            danglingField = null,
                            duplicateCandidates = emptyList(),
                            staleCorrection = false,
                            conflictingAnswerIds = emptyList(),
                        )
                    } else {
                        it
                    }
                }
            )
            true
        }
    }

    suspend fun attachToExisting(context: Context, entryId: String, artisanId: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(context)
                val held = current.firstOrNull { it.id == entryId }
                if (held == null || !samePersonAllowed(held, artisanId)) return@withLock false
                write(context, current.map { if (it.id == entryId) samePersonEntry(it, artisanId) else it })
                true
            }
        }

    suspend fun acknowledgeDuplicates(context: Context, entryId: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(context)
                val held = current.firstOrNull { it.id == entryId }
                if (held == null || held.duplicateCandidates.isEmpty()) return@withLock false
                write(context, current.map { if (it.id == entryId) differentPersonEntry(it) else it })
                true
            }
        }

    suspend fun clearAllFailures(
        context: Context,
        signedInUserId: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
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
                            duplicateCandidates = emptyList(),
                            staleCorrection = false,
                            conflictingAnswerIds = emptyList(),
                        )
                    }
                }
            )
            clearable.size
        }
    }

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

    suspend fun remove(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context).filterNot { it.id == entry.id }) }
        entry.media.forEach { runCatching { File(it.localPath).delete() } }
    }

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
            processing = processing ?: TranscribeChoices.processingFor(uri.toString(), inferMediaType(mimeType)),
            linkedType = linkedType,
            stepIndex = stepIndex,
            purpose = purpose,
            spokenLanguage = SpokenLanguageChoices.forKey(uri.toString())
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


private val IDEMPOTENT_CREATE_TYPES = setOf(
    "artisan", "craft", "product", "tool", "workshop", "process", "questionnaire",
    "customQuestionnaire", "feedbackReport",
)

fun outboxCarriesIdempotencyKey(type: String, targetId: String?): Boolean =
    targetId == null && type in IDEMPOTENT_CREATE_TYPES

fun isIdempotencyInProgress(error: retrofit2.HttpException): Boolean =
    error.code() == 409 &&
        error.response()?.headers()?.get("Idempotency-Status")?.equals("in-progress", ignoreCase = true) == true

data class CorrectionBase(val changedKeys: Set<String>, val baseUpdatedAt: String?)

fun correctionChangedKeys(request: JsonObject, record: JsonObject): Set<String> =
    request.keys.filterTo(LinkedHashSet()) { key ->
        val mine = request[key]
        if (key !in record) return@filterTo !isBlankJson(mine)
        !sameJson(mine, record[key])
    }

private fun isBlankJson(value: kotlinx.serialization.json.JsonElement?): Boolean = when (value) {
    null, kotlinx.serialization.json.JsonNull -> true
    is JsonPrimitive -> value.isString && value.content.isBlank()
    is kotlinx.serialization.json.JsonArray -> value.isEmpty()
    else -> false
}

private fun sameJson(a: kotlinx.serialization.json.JsonElement?, b: kotlinx.serialization.json.JsonElement?): Boolean {
    if (isBlankJson(a) && isBlankJson(b)) return true
    if (a is JsonPrimitive && b is JsonPrimitive) {
        if (a.isString != b.isString) {
            return a.content.toDoubleOrNull()?.let { it == b.content.toDoubleOrNull() } ?: (a.content == b.content)
        }
        if (!a.isString) return a.content.toDoubleOrNull()?.let { it == b.content.toDoubleOrNull() } ?: (a.content == b.content)
        return a.content == b.content
    }
    return a == b
}

fun restrictCorrectionBody(body: JsonObject, changed: Set<String>?, expectedUpdatedAt: String?): JsonObject {
    val kept = if (changed == null) body else JsonObject(body.filterKeys { it in changed })
    if (expectedUpdatedAt.isNullOrBlank()) return kept
    return JsonObject(kept + ("expectedUpdatedAt" to JsonPrimitive(expectedUpdatedAt)))
}

fun staleCorrectionCurrentVersion(code: Int, body: String?): String? {
    if (code != 409 || body.isNullOrBlank()) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val detail = root["detail"] as? JsonObject ?: return null
    if ((detail["code"] as? JsonPrimitive)?.content != "record_changed") return null
    return (detail["currentUpdatedAt"] as? JsonPrimitive)?.content ?: ""
}

data class StaleCorrectionField(val key: String, val mine: String, val theirs: String)

data class StaleCorrectionReview(val fields: List<StaleCorrectionField>, val currentUpdatedAt: String?)

fun staleCorrectionReview(mine: JsonObject, record: JsonObject, changedKeys: Set<String>?): StaleCorrectionReview {
    val keys = changedKeys ?: mine.keys
    val fields = keys.mapNotNull { key ->
        val a = mine[key]
        val b = record[key]
        if (sameJsonForReview(a, b)) null else StaleCorrectionField(key, readableJson(a), readableJson(b))
    }
    val now = (record["updatedAt"] as? JsonPrimitive)?.content
    return StaleCorrectionReview(fields, now)
}

private fun sameJsonForReview(a: kotlinx.serialization.json.JsonElement?, b: kotlinx.serialization.json.JsonElement?): Boolean =
    readableJson(a) == readableJson(b)

private fun readableJson(value: kotlinx.serialization.json.JsonElement?): String = when (value) {
    null, kotlinx.serialization.json.JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

data class OwnedAnswersRefusal(val message: String, val questionIds: List<String>)

fun ownedAnswersRefusal(code: Int, body: String?): OwnedAnswersRefusal? {
    if (code != 403 || body.isNullOrBlank()) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val detail = root["detail"] as? JsonObject ?: return null
    if ((detail["code"] as? JsonPrimitive)?.contentOrNull != "ANSWER_OWNED_BY_OTHER") return null
    val ids = (detail["questionIds"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { id -> id.isNotEmpty() } }
        .orEmpty()
    if (ids.isEmpty()) return null
    val message = (detail["message"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        .ifEmpty { "Another researcher wrote some of these answers." }
    return OwnedAnswersRefusal(message, ids.distinct())
}

fun withoutAnswers(payloadJson: String, questionIds: Collection<String>): String? {
    val body = runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return null
    val drop = questionIds.toSet()
    val responses = body["responses"] as? kotlinx.serialization.json.JsonArray ?: return payloadJson
    val kept = responses.filterNot { item ->
        ((item as? JsonObject)?.get("questionId") as? JsonPrimitive)?.contentOrNull in drop
    }
    val rewritten = body.toMutableMap().apply { put("responses", kotlinx.serialization.json.JsonArray(kept)) }
    return Json.encodeToString(JsonObject.serializer(), JsonObject(rewritten))
}

fun peekErrorBody(error: retrofit2.HttpException): String? = runCatching {
    val source = error.response()?.errorBody()?.source() ?: return@runCatching null
    source.request(Long.MAX_VALUE)
    source.buffer.clone().readUtf8()
}.getOrNull()

internal fun advancesWith(entry: PendingEntry, type: String, targetId: String, exceptId: String): Boolean =
    entry.id != exceptId && entry.type == type && entry.targetId == targetId &&
        entry.createdId == null && entry.failure == null && entry.baseUpdatedAt != null

fun createdFromStoredAnswer(body: kotlinx.serialization.json.JsonElement?): Pair<String, List<String>>? {
    val obj = body as? JsonObject ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
    val steps = (obj["steps"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
        .orEmpty()
    return id to steps
}

@Serializable
data class IdempotencyAnswerDto(
    val statusCode: Int = 0,
    val body: kotlinx.serialization.json.JsonElement? = null,
)

internal fun samePersonAllowed(entry: PendingEntry, artisanId: String): Boolean =
    entry.createdId == null && entry.targetId == null &&
        entry.duplicateCandidates.any { it.id == artisanId }

internal fun samePersonEntry(entry: PendingEntry, artisanId: String): PendingEntry = entry.copy(
    createdId = artisanId,
    attachedToExisting = true,
    duplicateCandidates = emptyList(),
    failure = null,
    failedAt = null,
    conflict = false,
)

internal fun differentPersonEntry(entry: PendingEntry): PendingEntry = entry.copy(
    payloadJson = PossibleDuplicates.payloadWithAcknowledged(
        entry.payloadJson,
        entry.duplicateCandidates.map { it.id },
    ),
    duplicateCandidates = emptyList(),
    failure = null,
    failedAt = null,
    conflict = false,
)
