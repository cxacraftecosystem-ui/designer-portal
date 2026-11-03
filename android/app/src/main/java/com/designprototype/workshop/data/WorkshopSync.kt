package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.report.remapInlineMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class StageSyncRecord(
    val signature: String = "",
    val syncedAt: String? = null,
    val failure: String? = null,
    val failedAt: String? = null,
    val permanent: Boolean = false,
    val skewRun: String? = null,
    val attempts: Int = 0,
    val refusedFields: Int = 0,
    val waitingOnFiles: Boolean = false,
    val refusal: DwStageRefusalRecord? = null,
    val withheldCustomKeys: List<String> = emptyList(),
)

@Serializable
data class DraftSyncState(
    val lastSuccessAt: String? = null,
    val lastError: String? = null,
    val createFailure: String? = null,
    val createFailedAt: String? = null,
    val createSkewRun: String? = null,
    val createSentAt: String? = null,
    val stages: Map<String, StageSyncRecord> = emptyMap(),
)

data class WorkshopSyncStatus(
    val workshopId: String,
    val remoteId: String?,
    val pendingStages: Int,
    val pendingMedia: Int,
    val pendingMediaBytes: Long,
    val failedStages: Int,
    val failedMedia: Int,
    val problems: List<String>,
    val lastSuccessAt: String?,
    val lastError: String?,
    val releasableMedia: Int,
    val releasableBytes: Long,
    val refusedAnswers: Int = 0,
    val unsentDeletions: Int = 0,
    val droppedAnswers: Int = 0,
) {
    val isFullySynced: Boolean
        get() = remoteId != null && pendingStages == 0 && pendingMedia == 0 &&
            failedStages == 0 && failedMedia == 0 && refusedAnswers == 0 && unsentDeletions == 0 &&
            droppedAnswers == 0

    val hasFailures: Boolean get() = failedStages > 0 || failedMedia > 0

    val summary: String
        get() = when {
            remoteId == null && pendingStages == 0 && pendingMedia == 0 -> "On this device only"
            isFullySynced -> "Saved online"
            hasFailures -> buildList {
                if (failedStages > 0) add("$failedStages stage${plural(failedStages)} not saved")
                if (failedMedia > 0) add("$failedMedia file${plural(failedMedia)} not uploaded")
            }.joinToString(", ")
            pendingStages == 0 && pendingMedia == 0 &&
                (refusedAnswers > 0 || unsentDeletions > 0 || droppedAnswers > 0) ->
                buildList {
                    if (refusedAnswers > 0) add("$refusedAnswers answer${plural(refusedAnswers)} not accepted")
                    if (unsentDeletions > 0) {
                        add("$unsentDeletions stage${plural(unsentDeletions)} with a deletion not sent")
                    }
                    if (droppedAnswers > 0) {
                        add("$droppedAnswers answer${plural(droppedAnswers)} not stored")
                    }
                }.joinToString(", ") + " — the rest is saved online"
            else -> buildList {
                if (remoteId == null) add("the workshop itself")
                if (pendingStages > 0) add("$pendingStages stage${plural(pendingStages)}")
                if (pendingMedia > 0) add("$pendingMedia file${plural(pendingMedia)}")
            }.joinToString(", ").replaceFirstChar { it.uppercase() } + " waiting to upload"
        }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

data class DwDeviceSyncBanner(
    val headline: String,
    val detail: String,
    val waiting: Boolean,
)

fun dwDeviceSyncBanner(
    workshops: Int,
    stages: Int,
    files: Int,
    bytesText: String,
    failures: Int,
    refusedAnswers: Int,
    unsentDeletions: Int = 0,
    droppedAnswers: Int = 0,
): DwDeviceSyncBanner? {
    if (workshops == 0 && failures == 0 && refusedAnswers == 0 && unsentDeletions == 0 &&
        droppedAnswers == 0
    ) {
        return null
    }
    val waiting = stages > 0 || files > 0 ||
        (refusedAnswers == 0 && unsentDeletions == 0 && droppedAnswers == 0)
    val headline = buildList {
        if (stages > 0) add("$stages stage${plural(stages)}")
        if (files > 0) add("$files file${plural(files)}, $bytesText")
        if (failures > 0) add("$failures not saved")
        if (refusedAnswers > 0) add("$refusedAnswers answer${plural(refusedAnswers)} not accepted")
        if (unsentDeletions > 0) {
            add("$unsentDeletions stage${plural(unsentDeletions)} with a deletion not sent")
        }
        if (droppedAnswers > 0) add("$droppedAnswers answer${plural(droppedAnswers)} not stored")
    }.joinToString(" · ").ifBlank {
        "Waiting to upload"
    }
    val across = "Across $workshops workshop${plural(workshops)} on this device."
    val one = refusedAnswers == 1
    val them = if (one) "it" else "them"
    val dOne = unsentDeletions == 1
    val deletionSentence = "$unsentDeletions stage${plural(unsentDeletions)} " +
        (if (dOne) "has a deletion" else "have deletions") +
        " that ${if (dOne) "has" else "have"} not been sent; syncing alone will not send ${if (dOne) "it" else "them"}. Open the workshop, then ${if (dOne) "the stage" else "those stages"}, with a connection."
    val pOne = droppedAnswers == 1
    val droppedSentence = "$droppedAnswers answer${plural(droppedAnswers)} ${if (pOne) "was" else "were"} not stored — the sections no longer ask ${if (pOne) "that question" else "those questions"}. Open the workshop once with a connection to clear this."
    val detail = buildString {
        append(
            when {
                refusedAnswers > 0 && !waiting -> "$across Everything else is saved online. " +
                    (if (one) "That answer was" else "Those answers were") +
                    " not accepted, so ${if (one) "it" else "they"} will not upload by ${if (one) "itself" else "themselves"}. Open the stage to see which and correct $them."
                refusedAnswers > 0 -> "$across Saved here; the stages and files above upload whenever there is a connection. The $refusedAnswers answer${plural(refusedAnswers)} that ${if (one) "was" else "were"} not accepted will not upload until corrected."
                unsentDeletions > 0 && !waiting ->
                    "$across Everything else is saved online. $deletionSentence"
                unsentDeletions > 0 -> "$across Saved here; the stages and files above upload whenever there is a connection. $deletionSentence"
                droppedAnswers > 0 && !waiting ->
                    "$across Everything else is saved online. $droppedSentence"
                droppedAnswers > 0 -> "$across Saved here; the stages and files above upload whenever there is a connection. $droppedSentence"
                else -> "$across Saved here and editable offline; it uploads whenever there is a connection."
            }
        )
        if (refusedAnswers > 0 && unsentDeletions > 0) {
            append(" ")
            append(deletionSentence)
        }
        if (droppedAnswers > 0 && (refusedAnswers > 0 || unsentDeletions > 0)) {
            append(" ")
            append(droppedSentence)
        }
    }
    return DwDeviceSyncBanner(headline = headline, detail = detail, waiting = waiting)
}

data class SyncPassResult(
    val workshopsCreated: Int = 0,
    val workshopsResumed: Int = 0,
    val stagesSent: Int = 0,
    val mediaUploaded: Int = 0,
    val refused: Int = 0,
    val blockedByRefusal: Int = 0,
    val stoppedOffline: Boolean = false,
    val skipped: Boolean = false,
) {
    val didAnything: Boolean get() =
        workshopsCreated > 0 || workshopsResumed > 0 || stagesSent > 0 || mediaUploaded > 0
}

internal fun WorkshopRepository.isConnectionFailure(error: Throwable): Boolean {
    val http = error as? HttpException ?: return isTransient(error)
    return when (http.code()) {
        401, 408, 429 -> true
        else -> false
    }
}

internal fun isConflictRefusal(error: Throwable): Boolean = (error as? HttpException)?.code() == 409

internal fun isMissingReferenceRefusal(error: Throwable): Boolean =
    (error as? HttpException)?.code().let { it == 404 || it == 422 }

internal val APP_RUN: String = UUID.randomUUID().toString()

internal fun blocksRetry(permanent: Boolean, skewRun: String?): Boolean {
    if (!permanent) return false
    if (skewRun.isNullOrBlank()) return true
    return skewRun == APP_RUN
}

private fun StageSyncRecord?.blocksRetry(): Boolean = blocksRetry(this?.permanent == true, this?.skewRun)

internal sealed interface DwResumedCreate {
    data class Found(val remoteId: String) : DwResumedCreate

    data object Ambiguous : DwResumedCreate

    data object None : DwResumedCreate
}

internal fun dwResumedCreateFrom(
    title: String,
    sessionUserId: String?,
    rows: List<DesignWorkshopDto>,
    claimedRemoteIds: Set<String>,
    createSentAt: String? = null,
): DwResumedCreate {
    if (sessionUserId.isNullOrBlank()) return DwResumedCreate.None
    val floor = dwStampInstant(createSentAt)?.minus(DW_RESUMED_CREATE_LOOKBACK)
    val candidates = rows.filter { row ->
        row.id.isNotBlank() &&
            row.title == title &&
            row.id !in claimedRemoteIds &&
            row.createdById == sessionUserId &&
            (floor == null || dwStampInstant(row.createdAt)?.isBefore(floor) != true)
    }
    if (candidates.size > 1) return DwResumedCreate.Ambiguous
    val only = candidates.firstOrNull() ?: return DwResumedCreate.None
    return DwResumedCreate.Found(only.id)
}

internal val DW_RESUMED_CREATE_LOOKBACK: Duration = Duration.ofDays(7)

private fun dwStampInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
}

internal data class DwDraftClaim(
    val remoteId: String?,
    val title: String,
    val idComesFromTheKeyAlone: Boolean,
    val awaitingCreate: Boolean,
)

internal fun dwClaimedRemoteIds(drafts: List<DwDraftClaim>): Set<String> {
    val awaiting = drafts.filter { it.remoteId == null && it.awaitingCreate }.map { it.title }.toSet()
    return drafts
        .filterNot { it.idComesFromTheKeyAlone && it.title in awaiting }
        .mapNotNull { it.remoteId }
        .toSet()
}

internal const val DW_WORKSHOP_CREATE_AMBIGUOUS =
    "Sent once without a reply, and now several online workshops share this title, so this phone can't tell which is yours. Nothing on this phone has been deleted. Rename this one or delete the duplicate, then tap Try again."

internal fun retriedStageRecord(record: StageSyncRecord): StageSyncRecord =
    if (record.refusedFields > 0 || record.refusal != null) {
        record.copy(permanent = false, skewRun = null)
    } else {
        record.copy(
            failure = null, failedAt = null, permanent = false, skewRun = null,
            waitingOnFiles = false,
        )
    }

private fun Throwable.refusal(fallback: String): ApiRefusal {
    val refusal = apiRefusal(fallback)
    val text = refusal.message
    val code = (this as? HttpException)?.code() ?: return refusal
    if (code < 500) return refusal
    val lead = if (text.endsWith('.') || text.endsWith('!') || text.endsWith('?')) text else "$text."
    return refusal.copy(
        message = "$lead Fix this before retrying — a better connection won't help."
    )
}

internal fun skewSentence(what: String, said: String): String =
    "$what could not be saved because the app needs an update. Nothing you typed is wrong; it will be sent by itself the next time you open the app after an update."

internal class DwOwedDeletions(
    val entities: List<String>,
    val rows: List<String>,
) {
    val any: Boolean get() = entities.isNotEmpty() || rows.isNotEmpty()
}

internal fun dwOwedDeletions(spec: StageDto, stored: StageDraft?): DwOwedDeletions {
    val declared = spec.collections.mapTo(HashSet()) { it.key }
    return DwOwedDeletions(
        entities = stored?.emptiedEntities.orEmpty().filter { it in declared }.distinct(),
        rows = stored?.deletedRowKeys.orEmpty()
            .filter { it.substringBefore(DW_ROW_KEY_SEPARATOR, "") in declared }
            .distinct(),
    )
}

internal fun dwDraftIsForAnotherAccount(ownerUserId: String?, signedInUserId: String?): Boolean =
    ownerUserId != null && signedInUserId != null && ownerUserId != signedInUserId

internal const val DW_DRAFT_OTHER_ACCOUNT_ROW =
    "Captured by another account — sign in as them to continue."

internal fun dwStrandedStages(schema: SchemaResponse, draft: WorkshopDraft): List<StageDraft> {
    val declared = schema.stages.mapTo(HashSet()) { it.key }
    return draft.stages.entries
        .filter { (key, stored) ->
            key !in declared &&
                stored.holdsAnswers()
        }
        .map { it.value }
}

internal fun dwStageSaysNothing(
    spec: StageDto,
    stored: StageDraft?,
    record: StageSyncRecord?,
    workshopIsRemote: Boolean,
): Boolean {
    val empty = stored == null || !stored.holdsAnswers()
    if (!empty) return false
    if (!record?.signature.isNullOrBlank()) return false
    if (workshopIsRemote && isAuthoritative(stored, record) && dwOwedDeletions(spec, stored).any) {
        return false
    }
    return true
}

object WorkshopSyncEngine {

    private val pass = Mutex()

    private val _busyWorkshop = MutableStateFlow<String?>(null)

    val busyWorkshop: StateFlow<String?> = _busyWorkshop.asStateFlow()

    private val _revision = MutableStateFlow(0)

    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    suspend fun statusOf(
        context: Context,
        schema: SchemaResponse,
        draft: WorkshopDraft,
    ): WorkshopSyncStatus = withContext(Dispatchers.Default) {
        val mediaById = draft.media.associateBy { it.id }
        val definition = DwCustomSectionStore.load(context, draft.workshopId)
        val problems = ArrayList<String>()

        draft.sync.createFailure?.let {
            problems += "This workshop could not be saved online: $it"
        }

        var pendingMedia = 0
        var pendingBytes = 0L
        var failedMedia = 0
        var releasable = 0
        var releasableBytes = 0L
        draft.media.forEach { descriptor ->
            val file = WorkshopDraftStore.mediaFile(context, draft.workshopId, descriptor)
            val onDisk = file.isFile
            when {
                descriptor.uploadFailure != null -> {
                    failedMedia++
                    problems += "“${descriptor.originalFilename}” was not uploaded: ${descriptor.uploadFailure}"
                }
                descriptor.isConfirmedRemote -> if (onDisk) {
                    releasable++
                    releasableBytes += file.length()
                }
                else -> {
                    pendingMedia++
                    if (onDisk) pendingBytes += file.length()
                }
            }
        }

        var pendingStages = 0
        var failedStages = 0
        var unsentDeletions = 0
        val remoteId = remoteIdOf(draft)
        schema.stages.forEach { spec ->
            val stored = draft.stages[spec.key]
            val record = draft.sync.stages[spec.key]
            if (record?.permanent == true) {
                failedStages++
                problems += "Stage ${spec.number} (${spec.title}): ${record.failure.orEmpty()}"
                return@forEach
            }
            val owed = dwOwedDeletions(spec, stored)
            if (remoteId != null && !isAuthoritative(stored, record)) {
                if (owed.any) {
                    unsentDeletions++
                    val what = buildString {
                        if (owed.entities.isNotEmpty()) {
                            append("you deleted everything in ${owed.entities.joinToString(", ")}")
                        }
                        if (owed.rows.isNotEmpty()) {
                            if (isNotEmpty()) append(", and ")
                            else append("you deleted ")
                            val n = owed.rows.size
                            append("$n entr${if (n == 1) "y" else "ies"} from ")
                            append(
                                owed.rows
                                    .map { it.substringBefore(DW_ROW_KEY_SEPARATOR, "") }
                                    .distinct()
                                    .joinToString(", ")
                            )
                        }
                    }
                    problems += "Stage ${spec.number} (${spec.title}): $what; that deletion hasn't been sent. Open the stage online to send it."
                }
            }
            if (dwStageSaysNothing(spec, stored, record, workshopIsRemote = remoteId != null)) {
                return@forEach
            }
            val built = buildStageBody(
                spec, stored, mediaById, isAuthoritative(stored, record),
                customHeld = dwCustomHeldFor(definition, spec.key),
                withheldCustomKeys = dwWithheldCustomKeys(definition, spec.key, record),
            )
            if (built.unresolved.isNotEmpty()) {
                pendingStages++
                return@forEach
            }
            if (signatureOf(built.body) != record?.signature) pendingStages++
        }
        dwStrandedStages(schema, draft).forEach { stored ->
            pendingStages++
            problems += "“${stored.title.ifBlank { stored.stageId }}” is no longer in the form, so it can't be sent. Open the app online; if it remains, ask an admin."
        }

        var refusedAnswers = 0
        var droppedAnswers = 0
        draft.sync.stages.forEach { (key, record) ->
            if (record.permanent) return@forEach
            refusedAnswers += record.refusedFields
            droppedAnswers += record.refusal?.droppedCustomKeys?.size ?: 0
            if (record.failure != null) {
                val spec = schema.stages.firstOrNull { it.key == key }
                problems += "Stage ${spec?.number ?: "?"} (${spec?.title ?: key}): ${record.failure}"
            }
        }

        WorkshopSyncStatus(
            workshopId = draft.workshopId,
            remoteId = remoteId,
            pendingStages = pendingStages,
            pendingMedia = pendingMedia,
            pendingMediaBytes = pendingBytes,
            failedStages = failedStages,
            failedMedia = failedMedia,
            problems = problems,
            lastSuccessAt = draft.sync.lastSuccessAt,
            lastError = draft.sync.lastError,
            releasableMedia = releasable,
            releasableBytes = releasableBytes,
            refusedAnswers = refusedAnswers,
            unsentDeletions = unsentDeletions,
            droppedAnswers = droppedAnswers,
        )
    }

    suspend fun syncAll(context: Context, repository: WorkshopRepository): SyncPassResult {
        if (!repository.hasToken() || !ConnectivityObserver.isOnline(context)) {
            return SyncPassResult(skipped = true)
        }
        return pass.withLock {
            val tally = Tally()
            try {
                val schema = repository.designWorkshopSchema(context)
                val ids = WorkshopDraftStore.list(context).sortedBy { it.updatedAt }.map { it.workshopId }
                for (id in ids) {
                    _busyWorkshop.value = id
                    val keepGoing = try {
                        syncOneWorkshop(context, repository, schema, id, tally)
                    } finally {
                        _busyWorkshop.value = null
                    }
                    if (!keepGoing) {
                        tally.stoppedOffline = true
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                tally.stoppedOffline = true
            }
            bump()
            tally.toResult()
        }
    }

    suspend fun retryWorkshop(
        context: Context,
        repository: WorkshopRepository,
        workshopId: String,
    ): SyncPassResult {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(
                media = draft.media.map { it.copy(uploadFailure = null, uploadFailedAt = null) },
                sync = draft.sync.copy(
                    createFailure = null,
                    createFailedAt = null,
                    createSkewRun = null,
                    lastError = null,
                    stages = draft.sync.stages.mapValues { (_, record) ->
                        retriedStageRecord(record)
                    },
                ),
            )
        }
        bump()
        if (!repository.hasToken() || !ConnectivityObserver.isOnline(context)) {
            return SyncPassResult(skipped = true)
        }
        return pass.withLock {
            val tally = Tally()
            try {
                val schema = repository.designWorkshopSchema(context)
                _busyWorkshop.value = workshopId
                if (!syncOneWorkshop(context, repository, schema, workshopId, tally)) {
                    tally.stoppedOffline = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                tally.stoppedOffline = true
            } finally {
                _busyWorkshop.value = null
            }
            bump()
            tally.toResult()
        }
    }

    suspend fun retryOneFile(context: Context, workshopId: String, mediaId: String): Boolean {
        var cleared = false
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(
                media = draft.media.map { item ->
                    if (item.id != mediaId || item.uploadFailure == null) item
                    else {
                        cleared = true
                        item.copy(uploadFailure = null, uploadFailedAt = null)
                    }
                }
            )
        }
        DwMediaUploadProgress.forget(mediaId)
        bump()
        return cleared
    }

    private class Tally {
        var created = 0
        var resumed = 0
        var stages = 0
        var media = 0
        var refused = 0
        var blocked = 0
        var stoppedOffline = false

        fun toResult() = SyncPassResult(
            workshopsCreated = created,
            workshopsResumed = resumed,
            stagesSent = stages,
            mediaUploaded = media,
            refused = refused,
            blockedByRefusal = blocked,
            stoppedOffline = stoppedOffline,
        )
    }

    private suspend fun syncOneWorkshop(
        context: Context,
        repository: WorkshopRepository,
        schema: SchemaResponse,
        workshopId: String,
        tally: Tally,
    ): Boolean {
        var draft = WorkshopDraftStore.load(context, workshopId) ?: return true

        if (dwDraftIsForAnotherAccount(draft.ownerUserId, repository.cachedUser()?.id)) {
            val note = "Captured by another account, so it won't send while you're signed in. Sign in as its designer to send it."
            if (draft.sync.lastError != note) {
                noteSync(context, workshopId) { it.copy(lastError = note) }
            }
            return true
        }

        if (statusOf(context, schema, draft).isFullySynced) return true

        if (remoteIdOf(draft) == null) {
            val session = repository.cachedUser()
            if (blocksRetry(draft.sync.createFailure != null, draft.sync.createSkewRun)) {
                tally.blocked++
                return true
            }
            val resumed = if (draft.sync.createSentAt == null) {
                DwResumedCreate.None
            } else {
                resolveInterruptedCreate(context, repository, draft)
            }
            if (resumed is DwResumedCreate.Ambiguous) {
                tally.refused++
                if (draft.sync.createFailure != DW_WORKSHOP_CREATE_AMBIGUOUS) {
                    noteSync(context, workshopId) {
                        it.copy(
                            createFailure = DW_WORKSHOP_CREATE_AMBIGUOUS,
                            createFailedAt = Instant.now().toString(),
                            createSkewRun = null,
                        )
                    }
                }
                return true
            }
            if (createMustBeDeclined(
                    alreadyOnServer = resumed is DwResumedCreate.Found,
                    sessionMayCreate = mayMintLocalWorkshop(
                        known = session != null,
                        role = session?.role,
                    ),
                )
            ) {
                if (draft.sync.createFailure != DW_WORKSHOP_CREATE_DECLINED_BY_APP) {
                    noteSync(context, workshopId) {
                        it.copy(
                            createFailure = DW_WORKSHOP_CREATE_DECLINED_BY_APP,
                            createFailedAt = Instant.now().toString(),
                            createSkewRun = null,
                        )
                    }
                }
                tally.blocked++
                return true
            }
            val resumedId = (resumed as? DwResumedCreate.Found)?.remoteId
            if (resumedId == null) {
                noteSync(context, workshopId) { it.copy(createSentAt = Instant.now().toString()) }
            }
            val created = if (resumedId != null) null else try {
                repository.createDesignWorkshop(
                    DesignWorkshopCreateBody(
                        title = workshopCreateTitle(draft.title, draft.createdAt),
                        templateId = draft.templateId.ifBlank { "DCH_STANDARD" },
                        workshopKind = draft.workshopKind,
                        designerUserId = draft.designerUserId,
                        designerUserIds = draft.designerUserIds,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (repository.isConnectionFailure(e)) {
                    noteSync(context, workshopId) {
                        it.copy(lastError = e.apiErrorMessage(CONNECTION_FAILED_SENTENCE))
                    }
                    return false
                }
                tally.refused++
                val refusal = e.refusal("This workshop could not be saved.")
                noteSync(context, workshopId) {
                    it.copy(
                        createFailure = if (refusal.schemaSkew) {
                            skewSentence("This workshop", refusal.message)
                        } else {
                            refusal.message
                        },
                        createFailedAt = Instant.now().toString(),
                        createSkewRun = if (refusal.schemaSkew) APP_RUN else null,
                    )
                }
                return true
            }
            val createdId = resumedId ?: created?.id?.takeIf { it.isNotBlank() }
            if (createdId == null) {
                tally.refused++
                noteSync(context, workshopId) {
                    it.copy(
                        createFailure = "the reply couldn't be confirmed. Nothing changed here. If on a Wi-Fi sign-in page, connect properly, check whether the workshop exists, then Try again.",
                        createFailedAt = Instant.now().toString(),
                        createSkewRun = null,
                    )
                }
                return true
            }
            draft = WorkshopDraftStore.updateBookkeeping(context, workshopId) {
                it.copy(
                    remoteId = createdId,
                    sync = it.sync.copy(
                        createFailure = null,
                        createFailedAt = null,
                        createSkewRun = null,
                        lastError = null,
                        createSentAt = null,
                    ),
                )
            } ?: return true
            if (resumedId != null) tally.resumed++ else tally.created++
            bump()
        }
        val remoteId = remoteIdOf(draft) ?: return true

        if (!uploadPending(context, repository, draft, remoteId, tally)) return false

        draft = WorkshopDraftStore.load(context, workshopId) ?: return true

        if (!pushStages(context, repository, draft, remoteId, schema, tally)) return false

        val after = WorkshopDraftStore.load(context, workshopId) ?: return true
        if (statusOf(context, schema, after).isFullySynced) {
            noteSync(context, workshopId) {
                it.copy(lastSuccessAt = Instant.now().toString(), lastError = null)
            }
        }
        return true
    }

    private suspend fun resolveInterruptedCreate(
        context: Context,
        repository: WorkshopRepository,
        draft: WorkshopDraft,
    ): DwResumedCreate {
        val title = workshopCreateTitle(draft.title, draft.createdAt)
        val summaries = WorkshopDraftStore.list(context)
        val locals = summaries
            .filter { isLocalOnlyWorkshop(it.workshopId) }
            .mapNotNull { summary -> WorkshopDraftStore.load(context, summary.workshopId) }
        val awaitingTitles = locals
            .filter { it.remoteId.isNullOrBlank() && it.sync.createSentAt != null }
            .map { it.title }
            .toSet()
        val claims = locals.map { other ->
            DwDraftClaim(
                remoteId = other.remoteId?.takeIf { it.isNotBlank() },
                title = other.title,
                idComesFromTheKeyAlone = false,
                awaitingCreate = other.remoteId.isNullOrBlank() && other.sync.createSentAt != null,
            )
        } + summaries.filterNot { isLocalOnlyWorkshop(it.workshopId) }.map { summary ->
            DwDraftClaim(
                remoteId = summary.workshopId,
                title = summary.title,
                idComesFromTheKeyAlone = summary.title in awaitingTitles &&
                    WorkshopDraftStore.load(context, summary.workshopId)?.remoteId.isNullOrBlank(),
                awaitingCreate = false,
            )
        }
        val page = try {
            repository.designWorkshops(page = 1, pageSize = 100, search = title)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return DwResumedCreate.None
        }
        return dwResumedCreateFrom(
            title = title,
            sessionUserId = repository.cachedUser()?.id,
            rows = page.items,
            claimedRemoteIds = dwClaimedRemoteIds(claims),
            createSentAt = draft.sync.createSentAt,
        )
    }

    private suspend fun uploadPending(
        context: Context,
        repository: WorkshopRepository,
        draft: WorkshopDraft,
        remoteId: String,
        tally: Tally,
    ): Boolean {
        val outstanding = draft.media.filter { !it.isConfirmedRemote && it.uploadFailure == null }
        if (outstanding.isEmpty()) return true

        outstanding.forEachIndexed { index, descriptor ->
            val file = WorkshopDraftStore.mediaFile(context, draft.workshopId, descriptor)
            if (!file.isFile) {
                val missing = "the file is missing from this phone. Restore a backup before removing the attachment."
                noteMediaFailure(context, draft.workshopId, descriptor.id, missing)
                DwMediaUploadProgress.refused(descriptor.id, missing, permanent = true)
                tally.refused++
                return@forEachIndexed
            }

            val uploaded = try {
                repository.uploadDesignWorkshopMedia(
                    context = context,
                    workshopRemoteId = remoteId,
                    file = file,
                    originalFilename = descriptor.originalFilename.ifBlank { file.name },
                    mimeType = descriptor.mimeType,
                    mediaType = descriptor.mediaType,
                    caption = descriptor.caption,
                    recordedAt = descriptor.capturedAt.takeIf { it.isNotBlank() },
                    latitude = descriptor.latitude,
                    longitude = descriptor.longitude,
                    workshopTitle = draft.title,
                    batchIndex = index + 1,
                    transcribe = descriptor.transcribe,
                    onProgress = { sent, total ->
                        DwMediaUploadProgress.sending(descriptor.id, sent, total)
                    },
                )
            } catch (e: CancellationException) {
                DwMediaUploadProgress.forget(descriptor.id)
                throw e
            } catch (e: Throwable) {
                val refused = e as? StorageRefusedError
                if (refused != null) {
                    if (refused.worthAnotherPass) {
                        DwMediaUploadProgress.refused(
                            descriptor.id,
                            "the upload didn't finish; it'll retry next time.",
                            permanent = false,
                        )
                        return@forEachIndexed
                    }
                    val reason = "the upload was refused. The file is still on this phone."
                    noteMediaFailure(context, draft.workshopId, descriptor.id, reason)
                    DwMediaUploadProgress.refused(descriptor.id, reason, permanent = true)
                    tally.refused++
                    return@forEachIndexed
                }
                if (repository.isConnectionFailure(e)) {
                    DwMediaUploadProgress.forget(descriptor.id)
                    noteSync(context, draft.workshopId) {
                        it.copy(lastError = e.apiErrorMessage("The upload could not be completed."))
                    }
                    return false
                }
                val reason = e.refusal("the file could not be uploaded.").message
                noteMediaFailure(
                    context, draft.workshopId, descriptor.id,
                    reason
                )
                DwMediaUploadProgress.refused(descriptor.id, reason, permanent = true)
                tally.refused++
                return@forEachIndexed
            }

            val serverId = uploaded.id.takeIf { it.isNotBlank() }
            if (serverId == null) {
                val unnamed = "the upload couldn't be confirmed. If on a Wi-Fi sign-in page, connect properly and retry."
                noteMediaFailure(context, draft.workshopId, descriptor.id, unnamed)
                DwMediaUploadProgress.refused(descriptor.id, unnamed, permanent = true)
                tally.refused++
                return@forEachIndexed
            }

            withContext(NonCancellable) {
                WorkshopDraftStore.updateBookkeeping(context, draft.workshopId) { stored ->
                    stored.copy(
                        media = stored.media.map { item ->
                            if (item.id != descriptor.id) item else item.copy(
                                remoteMediaId = serverId,
                                uploadedAt = Instant.now().toString(),
                                uploadFailure = null,
                                uploadFailedAt = null,
                            )
                        }
                    )
                }
            }
            DwMediaUploadProgress.sent(descriptor.id)
            tally.media++
            bump()
        }
        return true
    }

    private suspend fun pushStages(
        context: Context,
        repository: WorkshopRepository,
        draft: WorkshopDraft,
        remoteId: String,
        schema: SchemaResponse,
        tally: Tally,
    ): Boolean {
        val mediaById = draft.media.associateBy { it.id }
        val definition = DwCustomSectionStore.load(context, draft.workshopId)
        for (spec in schema.stages.sortedBy { it.number }) {
            val record = draft.sync.stages[spec.key]
            if (record.blocksRetry()) continue

            var stored = draft.stages[spec.key]
            if (dwStageSaysNothing(spec, stored, record, workshopIsRemote = true)) continue

            val pendingCards = stored?.let { pendingCardsIn(JsonObject(it.values)) + pendingCardsIn(JsonObject(it.custom)) }.orEmpty()
            if (pendingCards.isNotEmpty()) {
                when (val outcome = resolvePendingCards(pendingCards) { input -> repository.resolveArtisanCard(input, emptyList()) }) {
                    PendingCardOutcome.Offline -> continue
                    is PendingCardOutcome.Held -> {
                        if (record?.failure != outcome.message) {
                            noteStage(context, draft.workshopId, spec.key) {
                                it.copy(
                                    failure = outcome.message,
                                    failedAt = Instant.now().toString(),
                                    permanent = false,
                                    waitingOnFiles = false,
                                    skewRun = null,
                                    attempts = it.attempts + 1,
                                )
                            }
                        }
                        continue
                    }
                    is PendingCardOutcome.Resolved -> {
                        val updated = WorkshopDraftStore.update(context, draft.workshopId) { current ->
                            val stage = current.stages[spec.key] ?: return@update current
                            current.copy(
                                stages = current.stages + (
                                    spec.key to stage.copy(
                                        values = (substitutePendingCards(JsonObject(stage.values), outcome.ids) as JsonObject).toMap(),
                                        custom = (substitutePendingCards(JsonObject(stage.custom), outcome.ids) as JsonObject).toMap(),
                                    )
                                )
                            )
                        }
                        stored = updated.stages[spec.key]
                    }
                }
            }

            val built = buildStageBody(
                spec, stored, mediaById, isAuthoritative(stored, record),
                customHeld = dwCustomHeldFor(definition, spec.key),
                withheldCustomKeys = dwWithheldCustomKeys(definition, spec.key, record),
            )
            if (built.unresolved.isNotEmpty()) {
                val count = built.unresolved.size
                val note = "$count attached file${plural(count)} " +
                    (if (count == 1) "is" else "are") + " still only on this phone; this stage sends once they upload."
                if (record?.failure != note) {
                    noteStage(context, draft.workshopId, spec.key) {
                        it.copy(
                            failure = note,
                            failedAt = Instant.now().toString(),
                            permanent = false,
                            waitingOnFiles = true,
                            skewRun = null,
                            attempts = it.attempts + 1,
                        )
                    }
                }
                continue
            }

            val signature = signatureOf(built.body)
            if (signature == record?.signature) {
                if (record.failure != null && record.waitingOnFiles) {
                    noteStage(context, draft.workshopId, spec.key) {
                        it.copy(failure = null, failedAt = null, waitingOnFiles = false)
                    }
                }
                continue
            }

            val result = try {
                repository.saveDesignWorkshopStage(remoteId, spec.key, built.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (repository.isConnectionFailure(e)) {
                    noteSync(context, draft.workshopId) {
                        it.copy(lastError = e.apiErrorMessage("The stage could not be sent."))
                    }
                    return false
                }
                tally.refused++
                val refusal = e.refusal("this stage could not be saved.")
                noteStage(context, draft.workshopId, spec.key) {
                    it.copy(
                        failure = if (refusal.schemaSkew) {
                            skewSentence("This stage", refusal.message)
                        } else {
                            refusal.message
                        },
                        failedAt = Instant.now().toString(),
                        permanent = true,
                        skewRun = if (refusal.schemaSkew) APP_RUN else null,
                        attempts = it.attempts + 1,
                    )
                }
                continue
            }

            recordStageSent(context, draft.workshopId, spec.key, signature, built, result)
            tally.stages++
            bump()
        }
        return true
    }

    suspend fun pushStage(
        context: Context,
        repository: WorkshopRepository,
        workshopId: String,
        spec: StageDto,
    ): StagePush {
        val draft = WorkshopDraftStore.load(context, workshopId) ?: return StagePush.NothingToSend
        val remoteId = remoteIdOf(draft) ?: return StagePush.NoRemoteYet
        val stored = draft.stages[spec.key] ?: return StagePush.NothingToSend
        val record = draft.sync.stages[spec.key]
        val definition = DwCustomSectionStore.load(context, workshopId)
        val built = buildStageBody(
            spec,
            stored,
            draft.media.associateBy { it.id },
            isAuthoritative(stored, record),
            customHeld = dwCustomHeldFor(definition, spec.key),
            withheldCustomKeys = dwWithheldCustomKeys(definition, spec.key, record),
        )
        if (built.unresolved.isNotEmpty()) return StagePush.HeldBack(built.unresolved.size)

        val signature = signatureOf(built.body)
        if (signature == record?.signature) return StagePush.AlreadySent

        val result = try {
            repository.saveDesignWorkshopStage(remoteId, spec.key, built.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return StagePush.NotSent
        }
        recordStageSent(context, workshopId, spec.key, signature, built, result)
        bump()
        return StagePush.Sent(result, built.body.entries, built.body.copiedFrom)
    }
}

sealed interface StagePush {
    data object AlreadySent : StagePush

    data class Sent(
        val result: StageSaveResultDto,
        val entries: List<StageEntryBody> = emptyList(),
        val copiedFrom: Map<String, String> = emptyMap(),
    ) : StagePush

    data class HeldBack(val files: Int) : StagePush

    data object NoRemoteYet : StagePush

    data object NothingToSend : StagePush

    data object NotSent : StagePush
}

private val signatureJson = Json { encodeDefaults = true }

private fun remoteIdOf(draft: WorkshopDraft): String? =
    draft.remoteId?.takeIf { it.isNotBlank() }
        ?: draft.workshopId.takeIf { it.isNotBlank() && !isLocalOnlyWorkshop(it) }

internal data class BuiltStage(
    val body: StageSaveBody,
    val unresolved: List<String>,
    val withheldCustom: List<String> = emptyList(),
)

internal fun buildStageBody(
    spec: StageDto,
    stored: StageDraft?,
    mediaById: Map<String, DraftMedia>,
    authoritative: Boolean,
    customHeld: Boolean = false,
    withheldCustomKeys: Set<String> = emptySet(),
): BuiltStage {
    val unresolved = LinkedHashSet<String>()
    val entries = ArrayList<StageEntryBody>()

    spec.singleton?.let { entity ->
        entries += StageEntryBody(
            entityKey = entity.key,
            data = JsonObject(wireData(entity, stored?.values.orEmpty(), mediaById, unresolved)),
            merge = !authoritative,
        )
    }
    spec.collections.forEach { entity ->
        stored?.rowsFor(entity.key).orEmpty().forEachIndexed { index, row ->
            entries += StageEntryBody(
                entityKey = entity.key,
                entryId = (row.values["_entryId"] as? JsonPrimitive)?.content,
                ordinal = index,
                data = JsonObject(
                    wireData(entity, row.values, mediaById, unresolved) +
                        ("_clientKey" to JsonPrimitive(row.id.substringAfter(DW_ROW_KEY_SEPARATOR)))
                ),
                merge = !authoritative,
            )
        }
    }
    val custom = stored?.custom.orEmpty()
        .filterKeys { !it.startsWith("_") && it !in withheldCustomKeys }
    val customAnswered = custom.values.any { DwValues.isFilled(it) }
    val customAuthority = authoritative && stored?.customSeen == true
    if (customAnswered || (customAuthority && customHeld)) {
        entries += StageEntryBody(
            entityKey = CUSTOM_ENTITY_KEY,
            data = JsonObject(custom),
            merge = !customAuthority,
        )
    }

    val collectionKeys = spec.collections.map { it.key }.toSet()
    val emptied = stored?.emptiedEntities.orEmpty().filter { it in collectionKeys }.distinct()

    return BuiltStage(
        StageSaveBody(
            entries = entries,
            replaceCollections = authoritative,
            emptiedEntities = if (authoritative) emptied else emptyList(),
            submit = false,
            copiedFrom = stored?.copiedFrom.orEmpty(),
        ),
        unresolved.toList(),
        withheldCustom = stored?.custom.orEmpty().keys.filter { it in withheldCustomKeys },
    )
}

internal fun dwCustomHeldFor(definition: DwCustomCache?, stageKey: String): Boolean =
    definition.isHeld && customFieldsForStage(definition, stageKey).isNotEmpty()

internal fun dwWithheldCustomKeys(
    definition: DwCustomCache?,
    stageKey: String,
    record: StageSyncRecord?,
): Set<String> {
    val claimed = record?.refusal?.droppedCustomKeys.orEmpty() + record?.withheldCustomKeys.orEmpty()
    if (claimed.isEmpty()) return emptySet()
    val declared: Set<String> = customFieldsForStage(definition, stageKey).mapTo(HashSet()) { it.key }
    return claimed.filterNotTo(LinkedHashSet<String>()) { it in declared }
}

@Suppress("UNUSED_PARAMETER")
internal fun isAuthoritative(stored: StageDraft?, record: StageSyncRecord?): Boolean =
    stored?.stageSeen == true

private fun wireData(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    mediaById: Map<String, DraftMedia>,
    unresolved: MutableSet<String>,
): Map<String, JsonElement> {
    val mediaKeys = entity.fields
        .filter { DwFieldType.of(it.type).isMedia }
        .map { it.key }
        .toSet()
    val richKeys = entity.fields
        .filter { DwFieldType.of(it.type) == DwFieldType.RICH_TEXT }
        .map { it.key }
        .toSet()
    val out = LinkedHashMap<String, JsonElement>()
    values.forEach { (key, value) ->
        if (key.startsWith("_")) return@forEach
        if (key in richKeys) {
            out[key] = swapInlineMediaRefs(value, mediaById, unresolved)
            return@forEach
        }
        if (key !in mediaKeys) {
            out[key] = value
            return@forEach
        }
        swapMediaRefs(value, mediaById, unresolved)?.let { out[key] = it }
    }
    return out
}

private fun swapInlineMediaRefs(
    value: JsonElement,
    mediaById: Map<String, DraftMedia>,
    unresolved: MutableSet<String>,
): JsonElement = remapInlineMedia(value) { token ->
    val descriptor = mediaById[token]
    when {
        descriptor == null -> null
        descriptor.isConfirmedRemote -> descriptor.remoteMediaId
        else -> {
            unresolved += token
            null
        }
    }
} ?: value

private fun swapMediaRefs(
    value: JsonElement?,
    mediaById: Map<String, DraftMedia>,
    unresolved: MutableSet<String>,
): JsonElement? = when {
    value == null || value is kotlinx.serialization.json.JsonNull -> null
    value is JsonArray -> value
        .mapNotNull { swapMediaRefs(it, mediaById, unresolved) }
        .takeIf { it.isNotEmpty() }
        ?.let { JsonArray(it) }
    value is JsonPrimitive -> {
        val token = value.content
        val descriptor = if (token.isBlank()) null else mediaById[token]
        when {
            token.isBlank() -> null
            descriptor == null -> value
            descriptor.isConfirmedRemote -> JsonPrimitive(descriptor.remoteMediaId)
            else -> {
                unresolved += token
                null
            }
        }
    }
    else -> value
}

internal fun signatureOf(body: StageSaveBody): String {
    val encoded = signatureJson.encodeToString(StageSaveBody.serializer(), body)
    return MessageDigest.getInstance("SHA-256")
        .digest(encoded.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private suspend fun noteSync(
    context: Context,
    workshopId: String,
    transform: (DraftSyncState) -> DraftSyncState,
) {
    WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(sync = transform(it.sync)) }
}

private suspend fun noteStage(
    context: Context,
    workshopId: String,
    stageKey: String,
    transform: (StageSyncRecord) -> StageSyncRecord,
) {
    noteSync(context, workshopId) { state ->
        val current = state.stages[stageKey] ?: StageSyncRecord()
        state.copy(stages = state.stages + (stageKey to transform(current)))
    }
}

private suspend fun noteMediaFailure(
    context: Context,
    workshopId: String,
    mediaId: String,
    reason: String,
) {
    WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
        draft.copy(
            media = draft.media.map { item ->
                if (item.id != mediaId) item
                else item.copy(uploadFailure = reason, uploadFailedAt = Instant.now().toString())
            }
        )
    }
}

internal fun dwRefusedAnswerCount(errors: Map<String, JsonElement>): Int =
    errors.values.sumOf { entry -> (entry as? JsonObject)?.size ?: 1 }

private suspend fun recordStageSent(
    context: Context,
    workshopId: String,
    stageKey: String,
    signature: String,
    built: BuiltStage,
    result: StageSaveResultDto,
) {
    val dropped = result.droppedKeys.takeIf { it.isNotEmpty() }
    val droppedCustom = result.droppedCustomKeys.takeIf { it.isNotEmpty() }
    val notes = listOfNotNull(
        dropped?.let { keys ->
            "${keys.size} field${plural(keys.size)} weren't saved because the form changed. Nothing you typed is lost."
        },
        droppedCustom?.let { keys ->
            "${keys.size} custom question${plural(keys.size)} no longer asked, so " +
                (if (keys.size == 1) "it was" else "they were") +
                " not stored. Open this workshop online to refresh."
        },
    ).takeIf { it.isNotEmpty() }?.joinToString(" ")

    val refused = dwRefusedAnswerCount(result.errors)
    val refusalNote = refused.takeIf { it > 0 }?.let { count ->
        "$count answer${if (count == 1) "" else "s"} not accepted; earlier ${if (count == 1) "answer" else "answers"} kept. Open the stage to see which."
    }
    val sweptNote = result.removed
        .takeIf { it > 0 && !built.body.replaceCollections }
        ?.let { count ->
            "$count entr${if (count == 1) "y was" else "ies were"} removed during this save. Open the workshop online to check."
        }

    val allNotes = listOfNotNull(notes, refusalNote, sweptNote)
        .takeIf { it.isNotEmpty() }?.joinToString(" ")

    val sentCopies = built.body.copiedFrom
    val settledSignature =
        if (sentCopies.isEmpty()) signature else signatureOf(built.body.copy(copiedFrom = emptyMap()))
    noteStage(context, workshopId, stageKey) {
        it.copy(
            signature = settledSignature,
            syncedAt = Instant.now().toString(),
            failure = allNotes,
            failedAt = if (allNotes == null) null else Instant.now().toString(),
            permanent = false,
            skewRun = null,
            refusedFields = refused,
            refusal = DwStageRefusalRecord(
                errors = result.errors,
                sent = built.body.entries.map(::dwSentEntryOf),
                at = Instant.now().toString(),
                droppedCustomKeys = result.droppedCustomKeys,
            ).takeIf { !it.isEmpty },
            withheldCustomKeys = built.withheldCustom,
            waitingOnFiles = false,
        )
    }

    val acknowledgedDeletions = built.body.emptiedEntities
    val sweptEntities: Set<String> =
        if (built.body.replaceCollections) {
            built.body.entries.mapTo(HashSet()) { it.entityKey } + acknowledgedDeletions
        } else {
            emptySet()
        }
    if (acknowledgedDeletions.isEmpty() && sweptEntities.isEmpty() && sentCopies.isEmpty()) return
    WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
        val stage = draft.stages[stageKey] ?: return@updateBookkeeping draft
        val remaining = stage.emptiedEntities.filterNot { it in acknowledgedDeletions }
        val remainingRows = stage.deletedRowKeys.filterNot { key ->
            key.substringBefore(DW_ROW_KEY_SEPARATOR, "") in sweptEntities
        }
        val copies = dwCopiesAfterPush(stage.copiedFrom, stage.provenance, sentCopies)
        if (
            remaining.size == stage.emptiedEntities.size &&
            remainingRows.size == stage.deletedRowKeys.size &&
            copies == null
        ) {
            return@updateBookkeeping draft
        }
        draft.copy(
            stages = draft.stages + (
                stageKey to stage.copy(
                    emptiedEntities = remaining,
                    deletedRowKeys = remainingRows,
                    copiedFrom = copies?.first ?: stage.copiedFrom,
                    provenance = copies?.second ?: stage.provenance,
                )
            )
        )
    }
}
