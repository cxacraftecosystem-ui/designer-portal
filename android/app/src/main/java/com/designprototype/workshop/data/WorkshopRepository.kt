package com.designprototype.workshop.data

import com.designprototype.workshop.transcript.data.TranscribeChoices
import com.designprototype.workshop.transcript.data.UploadTranscribe
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.designprototype.workshop.ui.RecordOpen
import com.designprototype.workshop.ui.mustChangePasswordBlocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okio.BufferedSink
import retrofit2.HttpException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MULTIPART_THRESHOLD = 16L * 1024 * 1024

internal class StorageRefusedError(
    val status: Int,
    message: String,
) : Exception(message) {

    val worthAnotherPass: Boolean get() = status == 408 || status == 429 || status >= 500
}

internal const val DESIGN_WORKSHOP_MEDIA_TAG = "designWorkshop"

internal const val OFFLINE_EXPORT_RECORD = "designWorkshopExport"

internal const val OFFLINE_DESIGN_RATING = "designRating"

internal const val OFFLINE_CUSTOM_QUESTIONNAIRE = "customQuestionnaire"

internal const val OFFLINE_APP_FEEDBACK = "appFeedback"

internal const val OFFLINE_FEEDBACK_REPORT = "feedbackReport"

internal const val OFFLINE_DESIGNER_PROFILE = "designerProfile"

const val STALE_CORRECTION_SENTENCE =
    "Someone changed this record after your edit, so yours wasn't sent. Tap Review changes to compare."

internal const val OFFLINE_MEDIA_ONLY = "recordMediaOnly"

private const val REPICK_PAGE = 50

internal const val QUESTIONNAIRE_HANDOFF_MAX_FILE = 4 * 1024 * 1024

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

fun WorkshopDetailDto.occurrenceDate(): String = startDate ?: date ?: createdAt ?: ""

private val errorBodyJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun Throwable.apiErrorMessage(fallback: String): String = apiRefusal(fallback).message

data class ApiRefusal(
    val message: String,
    val schemaSkew: Boolean,
    val namedFields: List<String> = emptyList(),
)

internal const val CONNECTION_FAILED_SENTENCE = "Could not connect. Check your connection and try again."

private fun Throwable.isTransportFailure(): Boolean =
    this is java.net.UnknownHostException ||
        this is java.net.SocketException ||
        this is java.net.UnknownServiceException ||
        this is java.io.InterruptedIOException ||
        this is javax.net.ssl.SSLException ||
        (this is IOException && (cause is java.io.EOFException || cause is java.net.SocketException))

fun Throwable.apiRefusal(fallback: String): ApiRefusal {
    if (isTransportFailure()) return ApiRefusal(CONNECTION_FAILED_SENTENCE, schemaSkew = false)
    if (this is SerializationException) return ApiRefusal(fallback, schemaSkew = false)
    val plain = message?.takeIf { it.isNotBlank() } ?: fallback
    val http = this as? HttpException ?: return ApiRefusal(plain, schemaSkew = false)
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return ApiRefusal(fallback, schemaSkew = false)
    val detail = (runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
        ?.get("detail")
        ?: return ApiRefusal(fallback, schemaSkew = false)
    val skew = http.code() == 422 && (detail as? JsonArray)?.any { entry ->
        ((entry as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "extra_forbidden"
    } == true
    return ApiRefusal(detailMessage(detail) ?: fallback, schemaSkew = skew, namedFields = detail.locFields())
}

private fun JsonElement.locFields(): List<String> =
    (this as? JsonArray).orEmpty().flatMap { entry ->
        ((entry as? JsonObject)?.get("loc") as? JsonArray).orEmpty().mapNotNull { part ->
            (part as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it != "body" }
        }
    }.distinct()

private fun HttpException.serverDetail(): String? {
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return null
    val body = runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    val detail = body?.get("detail") ?: return null
    return detailMessage(detail)
}

private fun HttpException.dictationCapRefusal(): DwDictationCapRefused {
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val body = raw
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { errorBodyJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    fun number(key: String): Int? = (body?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    return DwDictationCapRefused(
        detail = body?.get("detail")?.let { detailMessage(it) },
        day = (body?.get("dictationDay") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        limit = number("dictationsLimit"),
        retryAfterSeconds = number("retryAfterSeconds"),
    )
}

private fun errorBodyDetail(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val body = runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    val detail = body?.get("detail") ?: return null
    return detailMessage(detail)
}

private fun detailMessage(detail: JsonElement): String? = when (detail) {
    is JsonPrimitive -> detail.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonArray -> detail
        .mapNotNull { entry ->
            ((entry as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull
                ?.removePrefix("Value error, ")?.trim()?.takeIf { it.isNotEmpty() }
        }
        .distinct()
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
    else -> null
}

fun Throwable.isAccountRefusal(): Boolean = (this as? HttpException)?.code() == 403

enum class AccessRefusal {
    PENDING,

    REJECTED,

    SUSPENDED,

    DESIGNER_SUSPENDED,

    QUEUE_FULL,

    BAD_CREDENTIAL,

    UNCLASSIFIED,

    NOT_REFUSED,
}

const val ACCESS_STATUS_HEADER = "X-Access-Status"

fun Throwable.accessRefusal(): AccessRefusal {
    val http = this as? HttpException ?: return AccessRefusal.NOT_REFUSED
    if (http.code() == 401) return AccessRefusal.BAD_CREDENTIAL
    if (http.code() != 403 && http.code() != 503) return AccessRefusal.NOT_REFUSED
    return when (http.response()?.headers()?.get(ACCESS_STATUS_HEADER)?.trim()?.uppercase()) {
        "PENDING" -> AccessRefusal.PENDING
        "REJECTED" -> AccessRefusal.REJECTED
        "SUSPENDED" -> AccessRefusal.SUSPENDED
        "DESIGNER_SUSPENDED" -> AccessRefusal.DESIGNER_SUSPENDED
        "NOT_RECORDED" -> AccessRefusal.QUEUE_FULL
        else -> if (http.code() == 503) AccessRefusal.NOT_REFUSED else AccessRefusal.UNCLASSIFIED
    }
}

enum class SignInHint {
    AMBIGUOUS_IDENTIFIER,

    PASSWORD_NOT_SET,

    NONE,
}

const val SIGN_IN_HINT_HEADER = "X-Sign-In-Hint"

fun Throwable.signInHint(): SignInHint {
    val http = this as? HttpException ?: return SignInHint.NONE
    return when (http.response()?.headers()?.get(SIGN_IN_HINT_HEADER)?.trim()?.uppercase()) {
        "AMBIGUOUS_IDENTIFIER" -> SignInHint.AMBIGUOUS_IDENTIFIER
        "PASSWORD_NOT_SET" -> SignInHint.PASSWORD_NOT_SET
        else -> SignInHint.NONE
    }
}

fun Throwable.signInErrorMessage(): String {
    val fallback = when ((this as? HttpException)?.code()) {
        401 -> "Email or password not accepted."
        403 -> "This account can't sign in. Contact your admin."
        null -> CONNECTION_FAILED_SENTENCE
        else -> "Sign-in failed. Please try again."
    }
    return apiErrorMessage(fallback)
}

private const val UPLOAD_CONCURRENCY = 3

private const val DW_REFERENCE_CACHE_GENERATION = 2

internal fun dwReferenceCacheOwner(workshopId: String): String =
    if (workshopId.isBlank()) workshopId else "$workshopId-$DW_REFERENCE_CACHE_GENERATION"

internal data class InterviewArtisanScope(
    val workshopIds: String?,
    val designWorkshopId: String?,
) {
    val narrowed: Boolean get() = workshopIds != null || designWorkshopId != null

    val key: String get() = "w:${workshopIds.orEmpty()}|dw:${designWorkshopId.orEmpty()}"

    val cacheOwner: String get() = when {
        workshopIds != null -> "w-$workshopIds"
        designWorkshopId != null -> "dw-$designWorkshopId"
        else -> ""
    }
}

internal fun interviewArtisanScope(
    routesToDesignWorkshop: Boolean,
    workshopId: String?,
    designWorkshopId: String?,
): InterviewArtisanScope = if (routesToDesignWorkshop) {
    InterviewArtisanScope(workshopIds = null, designWorkshopId = designWorkshopId?.blankToNull())
} else {
    InterviewArtisanScope(workshopIds = listOf(workshopId.orEmpty()).toQueryCsv(), designWorkshopId = null)
}

internal suspend fun fetchInterviewArtisans(
    api: WorkshopRepositoryApi,
    scope: InterviewArtisanScope,
): List<ArtisanDto> = api.artisans(
    pageSize = 100,
    workshopIds = scope.workshopIds,
    designWorkshopId = scope.designWorkshopId,
).items

private val PROCESS_OUTBOX_SYNC_MUTEX = Mutex()

private val PROCESS_INSPECTION_SYNC_MUTEX = Mutex()

class WorkshopRepository(
    private val api: WorkshopRepositoryApi,
    private val tokenStore: TokenStore
) {
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val referenceReads = SharedReads(loaderScope)

    private val storageClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    private val offlineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ARTISAN_CLEARABLE_COLUMNS = listOf(
        "localName",
        "gender",
        "phone",
        "email",
        "address",
        "notes",
        "dateOfBirth",
        "craftStartDate",
        "experienceYears",
        "experienceMonths",
        "dos",
        "donts"
    )
    private val completeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val syncMutex = PROCESS_OUTBOX_SYNC_MUTEX

    @Volatile
    private var appContextForReplay: Context? = null
    @VisibleForTesting
    internal val sweptStagedObjects = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        DwDictationRun.adopt(this)
    }

    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    fun cachedUser(): UserDto? = tokenStore.getUser()

    suspend fun designWorkshopSchema(
        context: Context,
        forceRefresh: Boolean = false
    ): SchemaResponse {
        val known = StageSchemaStore.peek()
        if (known != null && !forceRefresh) return known
        runCatching { api.designWorkshopSchema() }
            .onSuccess { fetched ->
                if (fetched.stages.isNotEmpty()) StageSchemaStore.store(context, fetched)
            }
        return StageSchemaStore.load(context)
    }

    suspend fun designWorkshopTemplates(context: Context): List<ReportTemplateDto> =
        runCatching { api.designWorkshopTemplates() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { StageSchemaStore.load(context) }
                .getOrNull()
                ?.enums
                ?.get("REPORT_TEMPLATE")
                ?.map { ReportTemplateDto(id = it.value, name = it.label) }
                .orEmpty()

    suspend fun designWorkshops(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
        statusFilter: String? = null,
        workshopKind: String? = null,
        craftName: String? = null,
        state: String? = null,
        mineOnly: Boolean = false
    ): DesignWorkshopPageDto = api.designWorkshops(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.takeIf { it.isNotEmpty() },
        statusFilter = statusFilter?.takeIf { it.isNotBlank() },
        workshopKind = workshopKind?.trim()?.takeIf { it.isNotEmpty() },
        craftName = craftName?.trim()?.takeIf { it.isNotEmpty() },
        state = state?.takeIf { it.isNotBlank() },
        mineOnly = mineOnly
    )

    suspend fun designWorkshopDefaultForMe(): DesignWorkshopDefaultDto = api.designWorkshopDefaultForMe()

    suspend fun createDesignWorkshop(body: DesignWorkshopCreateBody): DesignWorkshopDto {
        val designers = dwDesignerCreateFields(
            chosen = body.designerUserIds,
            lead = body.designerUserId,
        )
        return api.createDesignWorkshop(
            body.copy(
                designerUserId = designers.designerUserId,
                designerUserIds = designers.designerUserIds,
            )
        )
    }

    suspend fun designWorkshop(id: String): DesignWorkshopDetailDto = api.designWorkshop(id)

    suspend fun deleteDesignWorkshop(id: String) = api.deleteDesignWorkshop(id)

    suspend fun designWorkshopStages(id: String): StageListDto = api.designWorkshopStages(id)

    suspend fun designWorkshopStage(id: String, stageKey: String): StageBucketDto =
        api.designWorkshopStage(id, stageKey)

    suspend fun designWorkshopStageAssist(id: String, stageKey: String): DwStageAssistDto? =
        try {
            api.designWorkshopStageAssist(id, stageKey)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    suspend fun stageCopySources(id: String, stageKey: String): DwCopySourcesDto =
        api.stageCopySources(id, stageKey)

    suspend fun downloadRosterProForma(context: Context, id: String): String = withContext(Dispatchers.IO) {
        val response = api.rosterProForma(id)
        if (!response.isSuccessful) {
            throw IllegalStateException(
                errorBodyDetail(response.errorBody()?.string())
                    ?: "Couldn't download the pro-forma. Try again."
            )
        }
        val body = response.body() ?: throw IllegalStateException("Couldn't download the pro-forma. Try again.")
        val name = filenameFromContentDisposition(response.headers()["Content-Disposition"])
            ?: "artisan-list-pro-forma.xlsx"
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, XLSX_MIME)
        tmp.delete()
        location
    }

    suspend fun uploadRoster(context: Context, id: String, uri: Uri): DwArtisanImportReportDto =
        api.uploadRoster(id, workbookPart(context, uri))

    suspend fun rosterImports(id: String): DwArtisanImportListDto = api.rosterImports(id)

    suspend fun confirmCopiedFields(id: String, stageKey: String, fields: List<String>): DwConfirmCopiedDto =
        api.confirmCopiedFields(id, stageKey, DwConfirmCopiedBody(fields))

    suspend fun designWorkshopCustomSectionsRaw(id: String): DwCustomDefinitionDto =
        api.designWorkshopCustomSections(id)

    suspend fun saveDesignWorkshopStage(
        id: String,
        stageKey: String,
        body: StageSaveBody
    ): StageSaveResultDto = api.saveDesignWorkshopStage(id, stageKey, body)

    suspend fun recordDesignWorkshopExport(context: Context, id: String, body: ExportRecordBody) {
        try {
            api.recordDesignWorkshopExport(id, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isTransient(e)) return
            OfflineOutbox.enqueue(
                context,
                PendingEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    type = OFFLINE_EXPORT_RECORD,
                    payloadJson = offlineJson.encodeToString(PendingExportRecord(workshopId = id, body = body)),
                    label = "Export log · ${body.fileName}",
                    createdAt = Instant.now().toString(),
                    ownerUserId = cachedUser()?.id,
                )
            )
        }
    }

    suspend fun designWorkshopReferences(
        context: Context,
        workshopId: String?,
        model: String,
        scope: String,
        filterValue: String,
        limit: Int? = null,
        cacheOnly: Boolean = false,
        onList: (DwReferenceList, truncated: Boolean) -> Unit,
    ): Boolean {
        if (model.isBlank()) return false
        val owner = dwReferenceCacheOwner(workshopId.orEmpty())
        val key = DwReferenceStore.cacheKey(model, scope, owner, filterValue)

        val cached = DwReferenceStore.load(context, key)
            ?: DwReferenceStore.anyForModel(context, model, scope, owner)
                ?.let { whole ->
                    whole.copy(filteredBy = "", items = whole.narrowedTo(filterValue))
                }
        cached?.let { onList(it, it.partial) }
        if (cacheOnly) return false

        val target = workshopId?.takeUnless { it.isBlank() || isLocalOnlyWorkshop(it) } ?: return false

        suspend fun page(offset: Int, size: Int?): DwReferenceResponseDto =
            referenceReads.read("refs|$target|$model|$scope|$filterValue|$offset|$size") {
                api.designWorkshopReferences(
                    id = target,
                    model = model,
                    scope = scope.takeIf { it.isNotBlank() },
                    filterBy = filterValue.takeIf { it.isNotBlank() },
                    search = null,
                    limit = size,
                    offset = offset.takeIf { it > 0 },
                )
            }
        val pages = runCatching {
            if (limit == null) {
                dwReadAllReferencePages { offset, size -> page(offset, size) }
            } else {
                val one = page(0, limit)
                DwReferencePages(options = one.options, first = one, complete = !one.truncated)
            }
        }.getOrNull() ?: return false
        val fetched = pages.first

        val stored = DwReferenceStore.store(
            context = context,
            key = key,
            list = DwReferenceList(
                model = model,
                filteredBy = filterValue,
                items = pages.options,
                tentativeLabel = if (fetched.tentativeFirst) fetched.tentativeLabel else "",
                complete = pages.complete,
            ),
        )
        onList(stored, !pages.complete)
        return true
    }

    suspend fun designWorkshopReferenceSearch(
        workshopId: String?,
        model: String,
        scope: String,
        search: String,
        limit: Int,
        filterValue: String = "",
    ): DwReferenceResponseDto? {
        val target = workshopId?.takeUnless { it.isBlank() || isLocalOnlyWorkshop(it) } ?: return null
        if (model.isBlank() || search.isBlank()) return null
        return runCatching {
            api.designWorkshopReferences(
                id = target,
                model = model,
                scope = scope.takeIf { it.isNotBlank() },
                filterBy = filterValue.takeIf { it.isNotBlank() },
                search = search,
                limit = limit,
            )
        }.getOrNull()
    }

    suspend fun designWorkshopReferenceMerge(
        context: Context,
        workshopId: String?,
        model: String,
        scope: String,
        filterValue: String,
        found: List<DwReferenceOption>,
    ): DwReferenceList? {
        if (model.isBlank() || found.isEmpty()) return null
        val key = DwReferenceStore.cacheKey(model, scope, dwReferenceCacheOwner(workshopId.orEmpty()), filterValue)
        val current = DwReferenceStore.load(context, key)
            ?: DwReferenceList(model = model, filteredBy = filterValue, complete = false)
        val merged = dwMergeReferenceOptions(current, found)
        return if (merged === current) current else DwReferenceStore.store(context, key, merged)
    }

    suspend fun designWorkshopReferenceById(
        workshopId: String,
        model: String,
        scope: String,
        filterValue: String,
        recordId: String,
    ): DwReferenceResponseDto = api.designWorkshopReferences(
        id = workshopId,
        model = model,
        scope = scope.takeIf { it.isNotBlank() },
        filterBy = filterValue.takeIf { it.isNotBlank() },
        search = null,
        recordId = recordId,
        limit = 1,
    )

    suspend fun designWorkshopIdentityOcr(
        context: Context,
        uri: Uri,
        retention: String = DW_RETENTION_DEFAULT,
    ): DwIdentityOcrDto {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Couldn't open that photo.")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "identity-card.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        return api.designWorkshopIdentityOcr(
            file = part,
            retention = parseRetention(retention).toRequestBody("text/plain".toMediaType()),
        )
    }

    suspend fun decideIdentityPhotograph(mediaId: String, decision: String): DwRetentionResultDto =
        api.decideIdentityPhotograph(
            DwRetentionDecisionBody(mediaId = mediaId, decision = parseRetention(decision))
        )

    suspend fun refreshDictationAllowance(context: Context, force: Boolean = false) {
        val userId = cachedUser()?.id?.takeIf { it.isNotBlank() } ?: return
        val stored = DwDictationAllowanceStore.read(context, userId)
        if (!dwDictationAllowanceDue(stored, DwDictationAllowanceStore.fetchedAt(context, userId), dwDictationIstDay(), System.currentTimeMillis(), force)) return
        DwDictationAllowanceStore.markFetched(context, userId)
        val dto = runCatching { api.designWorkshopDictationAllowance() }.getOrNull() ?: return
        dwDictationAllowanceOf(dto, userId)?.let { DwDictationAllowanceStore.write(context, it) }
    }

    suspend fun designWorkshopDictate(
        workshopId: String,
        clip: File,
        languageTag: String,
        interviewId: String? = null,
        artisanIds: Collection<String> = emptyList(),
    ): DwDictateDto {
        val subjects = dictationSubjects(interviewId, artisanIds)
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "dictation.m4a",
            clip.asRequestBody("audio/mp4".toMediaType())
        )
        try {
            return api.designWorkshopDictate(
                workshopId,
                part,
                languageTag.toRequestBody("text/plain".toMediaType()),
                subjects.interviewId?.toRequestBody("text/plain".toMediaType()),
                subjects.artisanIds?.toRequestBody("text/plain".toMediaType()),
            )
        } catch (e: HttpException) {
            if (e.code() == 409) throw DwDictationConsentRefused(e.serverDetail())
            if (e.code() == 503) e.serverDetail()?.let { throw DwDictationNotConfigured(it) }
            if (e.code() == 429) throw e.dictationCapRefusal()
            throw e
        }
    }

    suspend fun designWorkshopRecordDictationConsent(
        workshopId: String,
        decision: DwTier3Consent,
        recordedAt: String?,
        note: String? = null,
    ): DwConsentDecisionDto = api.recordDesignWorkshopDictationConsent(
        id = workshopId,
        body = DwConsentDecisionRequest(
            decision = dwTier3ConsentToken(decision),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            recordedAt = recordedAt,
        ),
    )

    suspend fun designWorkshopProofread(
        context: Context,
        workshopId: String,
        source: DwVerbSource,
        language: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.PROOFREAD) {
        api.designWorkshopProofread(dwVerbWorkshopId(workshopId), dwProofreadBody(source, language))
    }

    suspend fun designWorkshopOnDeviceLayer(
        workshopId: String,
        draft: DwTier2Draft,
    ): DwAiVerbResultDto = try {
        api.designWorkshopOnDeviceLayer(dwVerbWorkshopId(workshopId), dwTier2LayerBody(draft))
    } catch (e: HttpException) {
        throw e.asVerbFailure(DwAiVerb.PROOFREAD)
    }

    suspend fun designWorkshopExpand(
        context: Context,
        workshopId: String,
        note: String,
        language: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.EXPAND) {
        api.designWorkshopExpand(dwVerbWorkshopId(workshopId), dwExpandBody(note, language))
    }

    suspend fun designWorkshopTranslate(
        context: Context,
        workshopId: String,
        source: DwVerbSource,
        targetLanguage: String,
        sourceLanguage: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.TRANSLATE) {
        api.designWorkshopTranslate(
            dwVerbWorkshopId(workshopId),
            dwTranslateBody(source, targetLanguage, sourceLanguage),
        )
    }

    suspend fun designWorkshopCaptionMedia(
        context: Context,
        workshopId: String,
        remoteMediaId: String,
        language: String? = null,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.CAPTION) {
        api.designWorkshopCaptionMedia(
            dwVerbWorkshopId(workshopId),
            DwMediaVerbBody(sourceMediaId = remoteMediaId, language = language?.trim()?.takeIf { it.isNotEmpty() }),
        )
    }

    suspend fun designWorkshopSubtitleMedia(
        context: Context,
        workshopId: String,
        remoteMediaId: String,
    ): DwAiVerbResultDto = runVerb(context, DwAiVerb.SUBTITLES) {
        api.designWorkshopSubtitleMedia(
            dwVerbWorkshopId(workshopId),
            DwMediaVerbBody(sourceMediaId = remoteMediaId),
        )
    }

    private suspend fun runVerb(
        context: Context,
        verb: DwAiVerb,
        call: suspend () -> DwAiVerbResultDto,
    ): DwAiVerbResultDto {
        val userId = cachedUser()?.id?.takeIf { it.isNotBlank() }
        try {
            val result = call()
            dwAiVerbAllowanceOf(result, userId)?.let { DwAiVerbAllowanceStore.write(context, it) }
            return result
        } catch (e: HttpException) {
            val failure = e.asVerbFailure(verb)
            if (userId != null && failure is DwAiVerbCapRefused && !failure.transientThrottle) {
                val today = dwDictationIstDay()
                DwAiVerbAllowanceStore.write(
                    context,
                    dwAiVerbCapSpentRecord(
                        previous = DwAiVerbAllowanceStore.read(context, userId),
                        userId = userId,
                        today = today,
                    ),
                )
            }
            throw failure
        }
    }

    private fun HttpException.asVerbFailure(verb: DwAiVerb): Exception {
        val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val body = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { errorBodyJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        val detail = body?.get("detail")?.let { detailMessage(it) }
        val retryAfterSeconds =
            (body?.get("retryAfterSeconds") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return when {
            code() == 429 -> DwAiVerbCapRefused(detail = detail, retryAfterSeconds = retryAfterSeconds)
            code() == 503 && detail != null -> DwAiVerbNotConfigured(verb, detail)
            else -> DwAiVerbRefused(code(), detail)
        }
    }

    suspend fun downloadDesignWorkshopSubtitles(
        context: Context,
        workshopId: String,
        layerId: String,
        format: DwSubtitleFormat,
        speakers: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val response = api.designWorkshopSubtitleFile(
            id = dwVerbWorkshopId(workshopId),
            layerId = layerId,
            fmt = format.extension,
            speakers = if (speakers) true else null,
        )
        if (!response.isSuccessful) {
            throw DwAiVerbRefused(response.code(), errorBodyDetail(response.errorBody()?.string()))
        }
        val body = response.body()
            ?: throw IllegalStateException("The subtitle download was empty.")
        val name = filenameFromContentDisposition(response.headers()["Content-Disposition"])
            ?: buildString {
                append("subtitles-")
                append(safeDownloadName(layerId) ?: "layer")
                if (speakers) append(".speakers")
                append('.')
                append(format.extension)
            }
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, format.mimeType)
        tmp.delete()
        location
    }

    suspend fun designWorkshopAiLayers(
        workshopId: String,
        kind: String? = null,
        includeText: Boolean = false,
        includeDeleted: Boolean = false,
    ): DwAiLayerListDto = api.designWorkshopAiLayers(
        id = dwVerbWorkshopId(workshopId),
        kind = kind?.trim()?.takeIf { it.isNotEmpty() },
        includeText = if (includeText) true else null,
        includeDeleted = if (includeDeleted) true else null,
    )

    suspend fun acceptDesignWorkshopAiLayer(
        workshopId: String,
        layerId: String,
        note: String? = null,
    ): DwAiLayerDecisionResultDto = api.acceptDesignWorkshopAiLayer(
        id = dwVerbWorkshopId(workshopId),
        layerId = layerId,
        body = DwAiLayerDecisionBody(note = note?.trim()?.takeIf { it.isNotEmpty() }),
    )

    suspend fun unacceptDesignWorkshopAiLayer(
        workshopId: String,
        layerId: String,
        note: String? = null,
    ): DwAiLayerDecisionResultDto = api.unacceptDesignWorkshopAiLayer(
        id = dwVerbWorkshopId(workshopId),
        layerId = layerId,
        body = DwAiLayerDecisionBody(note = note?.trim()?.takeIf { it.isNotEmpty() }),
    )

    suspend fun declineDesignWorkshopAiLayer(workshopId: String, layerId: String) {
        api.declineDesignWorkshopAiLayer(dwVerbWorkshopId(workshopId), layerId)
    }

    suspend fun designWorkshopAiLayer(workshopId: String, layerId: String): DwAiLayerReadDto =
        api.designWorkshopAiLayer(dwVerbWorkshopId(workshopId), layerId)

    suspend fun designWorkshopAiLayerDecisions(workshopId: String): DwAiDecisionHistoryDto =
        api.designWorkshopAiLayerDecisions(dwVerbWorkshopId(workshopId))

    suspend fun registerDesignWorkshopAiLayer(workshopId: String, mediaId: String): DwAiLayerRegisterResultDto =
        api.registerDesignWorkshopAiLayer(dwVerbWorkshopId(workshopId), DwAiLayerRegisterBody(sourceMediaId = mediaId))

    suspend fun designWorkshopTranscripts(workshopId: String): DwTranscriptListDto =
        api.designWorkshopTranscripts(dwVerbWorkshopId(workshopId))

    suspend fun designWorkshopMarketAnalysis(workshopId: String): DwMarketFindings? =
        runCatching { DwMarketAnalysis.fromPayload(api.designWorkshopMarketAnalysis(workshopId)) }
            .getOrNull()

    suspend fun refreshAiVerbAllowance(context: Context) {
        val userId = cachedUser()?.id?.takeIf { it.isNotBlank() } ?: return
        val dto = runCatching { api.designWorkshopAiVerbAllowance() }.getOrNull() ?: return
        dwAiVerbAllowanceOf(dto, userId)?.let { DwAiVerbAllowanceStore.write(context, it) }
    }

    suspend fun eligibleDesignWorkshopViewers(search: String? = null): DwEligibleViewers =
        api.eligibleDesignWorkshopViewers(search).let {
            DwEligibleViewers(users = it.users, truncated = it.truncated, search = search)
        }

    suspend fun designWorkshopViewers(workshopId: String): List<DwViewerDto> =
        api.designWorkshopViewers(workshopId).viewers

    suspend fun setDesignWorkshopViewers(
        workshopId: String,
        userIds: List<String>
    ): List<DwViewerDto> =
        api.setDesignWorkshopViewers(workshopId, DwViewersBody(userIds = userIds)).viewers

    suspend fun eligibleDesignWorkshopInspectors(search: String? = null): DwEligibleInspectors =
        api.eligibleDesignWorkshopInspectors(search).let {
            DwEligibleInspectors(users = it.users, truncated = it.truncated, search = search)
        }

    suspend fun designWorkshopInspectors(workshopId: String): List<DwInspectorDto> =
        api.designWorkshopInspectors(workshopId).inspectors

    suspend fun setDesignWorkshopInspectors(
        workshopId: String,
        userIds: List<String>
    ): List<DwInspectorDto> =
        api.setDesignWorkshopInspectors(workshopId, DwInspectorsBody(userIds = userIds)).inspectors

    suspend fun inspectableDesignWorkshops(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null
    ): DesignWorkshopPageDto = api.inspectableDesignWorkshops(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.takeIf { it.isNotEmpty() }
    )

    suspend fun workshopUnderInspection(workshopId: String): DwInspectionDetailDto =
        api.workshopUnderInspection(workshopId)

    suspend fun readWorkshopUnderInspection(context: Context, workshopId: String): DwInspectionRead {
        val owner = cachedUser()?.id.orEmpty()
        val store = DwInspectionStore.of(context)
        return try {
            val detail = api.workshopUnderInspection(workshopId)
            if (owner.isNotBlank()) {
                withContext(Dispatchers.IO) { store.saveDetail(DwSavedInspection(owner, dwInspectionNow(), detail)) }
            }
            DwInspectionRead.Live(detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) withContext(Dispatchers.IO) { store.forgetWorkshop(workshopId) }
            throw e
        } catch (e: IOException) {
            val saved = withContext(Dispatchers.IO) { store.detail(workshopId, owner) } ?: throw e
            DwInspectionRead.Saved(saved.detail, saved.savedAt)
        }
    }

    suspend fun readInspectableWorkshops(
        context: Context,
        page: Int,
        pageSize: Int,
        search: String?
    ): Pair<DesignWorkshopPageDto, String?> {
        val owner = cachedUser()?.id.orEmpty()
        val store = DwInspectionStore.of(context)
        return try {
            val answer = inspectableDesignWorkshops(page = page, pageSize = pageSize, search = search)
            if (owner.isNotBlank() && page == 1 && search.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    store.saveList(DwSavedInspectionList(owner, dwInspectionNow(), answer.items, answer.total))
                }
            }
            answer to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (page != 1 || !search.isNullOrBlank()) throw e
            val saved = withContext(Dispatchers.IO) { store.list(owner) } ?: throw e
            DesignWorkshopPageDto(
                items = saved.items,
                total = saved.total,
                page = 1,
                pageSize = saved.items.size,
                pages = 1
            ) to saved.savedAt
        }
    }

    suspend fun inspectionReviewQueue(context: Context): Triple<List<DesignWorkshopDto>, Boolean, String?> {
        val owner = cachedUser()?.id.orEmpty()
        val store = DwInspectionStore.of(context)
        val rows = ArrayList<DesignWorkshopDto>()
        var more = false
        try {
            var page = 1
            while (true) {
                val answer = inspectableDesignWorkshops(page = page, pageSize = DW_INSPECTION_QUEUE_PAGE_SIZE)
                rows += answer.items
                if (page >= answer.pages || answer.items.isEmpty()) break
                if (page >= DW_INSPECTION_QUEUE_MAX_PAGES) {
                    more = true
                    break
                }
                page++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val saved = withContext(Dispatchers.IO) { store.list(owner) } ?: throw e
            return Triple(saved.items, saved.total > saved.items.size, saved.savedAt)
        }
        if (owner.isNotBlank()) {
            withContext(Dispatchers.IO) {
                store.saveList(DwSavedInspectionList(owner, dwInspectionNow(), rows, rows.size))
            }
        }
        return Triple(rows, more, null)
    }

    suspend fun recordInspectionFeedback(
        workshopId: String,
        body: DwInspectionFeedbackBody
    ): DwInspectionFeedbackAnswerDto = api.recordInspectionFeedback(workshopId, body)

    suspend fun sendInspectionBack(
        workshopId: String,
        body: DwInspectionFeedbackBody
    ): DwInspectionFeedbackAnswerDto = api.sendInspectionBack(workshopId, body)

    suspend fun setInspectionRoundCleared(workshopId: String, cleared: Boolean): DwInspectionFeedbackAnswerDto =
        if (cleared) api.clearInspectionRound(workshopId) else api.withdrawInspectionClearance(workshopId)

    suspend fun queuedInspectionNotes(context: Context, workshopId: String? = null): List<DwQueuedInspectionNote> {
        val owner = cachedUser()?.id?.takeIf { it.isNotBlank() } ?: return emptyList()
        return withContext(Dispatchers.IO) { DwInspectionStore.of(context).notesFor(owner, workshopId) }
    }

    suspend fun fileInspectionNote(
        context: Context,
        read: DwInspectionDetailDto,
        kind: DwInspectionNoteKind,
        note: String,
        stageKey: String?
    ): DwInspectionSyncReport {
        val owner = cachedUser()?.id?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("This phone is not signed in.")
        val queued = DwQueuedInspectionNote(
            id = java.util.UUID.randomUUID().toString(),
            workshopId = read.id,
            workshopTitle = read.title,
            kind = kind,
            note = note.trim(),
            stageKey = stageKey?.takeIf { it.isNotBlank() },
            recordedAt = dwInspectionNow(),
            ownerUserId = owner,
            draftedRound = read.submissionRound,
            draftedStatus = read.status,
        )
        withContext(Dispatchers.IO) { DwInspectionStore.of(context).add(queued) }
        return syncInspectionNotes(context, onlyWorkshopId = read.id)
    }

    suspend fun refileInspectionNote(
        context: Context,
        noteId: String,
        read: DwInspectionDetailDto
    ): DwInspectionSyncReport {
        val store = DwInspectionStore.of(context)
        withContext(Dispatchers.IO) {
            store.notes().firstOrNull { it.id == noteId }?.let { held ->
                store.update(
                    held.copy(
                        held = null,
                        heldAt = null,
                        draftedRound = read.submissionRound,
                        draftedStatus = read.status
                    )
                )
            }
        }
        return syncInspectionNotes(context, onlyWorkshopId = read.id)
    }

    suspend fun discardInspectionNote(context: Context, noteId: String) {
        withContext(Dispatchers.IO) { DwInspectionStore.of(context).remove(noteId) }
    }

    private val inspectionMutex = PROCESS_INSPECTION_SYNC_MUTEX

    suspend fun syncInspectionNotes(context: Context, onlyWorkshopId: String? = null): DwInspectionSyncReport {
        val user = cachedUser()
        val owner = user?.id?.takeIf { it.isNotBlank() } ?: return DwInspectionSyncReport()
        val store = DwInspectionStore.of(context)
        if (mustChangePasswordBlocks(user)) {
            val waiting = withContext(Dispatchers.IO) { store.notesFor(owner, onlyWorkshopId).count { it.waiting } }
            return DwInspectionSyncReport(waiting = waiting)
        }
        return inspectionMutex.withLock {
            var sent = 0
            var held = 0
            var waiting = 0
            val refreshed = LinkedHashMap<String, DwInspectionDetailDto>()
            val queued = withContext(Dispatchers.IO) { store.notesFor(owner, onlyWorkshopId) }
                .filter { it.waiting }
                .sortedBy { it.recordedAt }
            var offline = false
            for ((workshopId, notes) in queued.groupBy { it.workshopId }) {
                if (offline) {
                    waiting += notes.size
                    continue
                }
                var current = try {
                    api.workshopUnderInspection(workshopId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    val said = e.apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
                    val outcome = dwInspectionSendOutcome(e.code(), said, notes.first().kind)
                    if (outcome is DwInspectionSendOutcome.Refused) {
                        withContext(Dispatchers.IO) {
                            if (e.code() == 404) store.forgetWorkshop(workshopId)
                            notes.forEach { store.update(it.copy(held = outcome.sentence, heldAt = dwInspectionNow())) }
                        }
                        held += notes.size
                    } else {
                        waiting += notes.size
                    }
                    continue
                } catch (e: IOException) {
                    offline = true
                    waiting += notes.size
                    continue
                }
                for (note in notes) {
                    if (offline) {
                        waiting++
                        continue
                    }
                    if (dwInspectionNoteAlreadyFiled(note, current.inspectionFeedback)) {
                        withContext(Dispatchers.IO) { store.remove(note.id) }
                        sent++
                        continue
                    }
                    val check = dwInspectionNoteCheck(note, current.status, current.submissionRound)
                    if (check is DwInspectionNoteCheck.Hold) {
                        withContext(Dispatchers.IO) {
                            store.update(note.copy(held = check.sentence, heldAt = dwInspectionNow()))
                        }
                        held++
                        continue
                    }
                    try {
                        val answer = if (note.kind == DwInspectionNoteKind.SEND_BACK) {
                            api.sendInspectionBack(workshopId, note.body())
                        } else {
                            api.recordInspectionFeedback(workshopId, note.body())
                        }
                        withContext(Dispatchers.IO) { store.remove(note.id) }
                        current = current.withFeedbackAnswer(answer)
                        sent++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HttpException) {
                        val said = e.apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
                        val outcome = dwInspectionSendOutcome(e.code(), said, note.kind)
                        if (outcome is DwInspectionSendOutcome.Refused) {
                            withContext(Dispatchers.IO) {
                                store.update(note.copy(held = outcome.sentence, heldAt = dwInspectionNow()))
                            }
                            held++
                        } else {
                            waiting++
                        }
                    } catch (e: IOException) {
                        offline = true
                        waiting++
                    }
                }
                withContext(Dispatchers.IO) { store.saveDetail(DwSavedInspection(owner, dwInspectionNow(), current)) }
                refreshed[workshopId] = current
            }
            DwInspectionSyncReport(sent = sent, held = held, waiting = waiting, refreshed = refreshed)
        }
    }

    suspend fun designWorkshopProvenance(workshopId: String): DwProvenanceReportDto =
        api.designWorkshopProvenance(workshopId)

    private val reportHistoryApi: DwReportHistoryApi by lazy {
        ApiClient.retrofit(tokenStore).create(DwReportHistoryApi::class.java)
    }

    suspend fun designWorkshopReportHistory(workshopId: String): DwReportHistoryDto =
        reportHistoryApi.designWorkshopReportHistory(workshopId)

    suspend fun designRatingRound(
        round: String,
        workshopId: String,
        entityKey: String,
    ): RoundRankingDto = api.designRatingRound(
        round = round,
        workshopId = workshopId,
        entityKey = entityKey,
    )

    suspend fun designRatingLedger(subjectId: String, round: String): SubjectLedgerDto =
        api.designRatingLedger(subjectId = subjectId, round = round)

    suspend fun submitDesignRating(
        context: Context,
        body: DesignRatingBody,
        label: String,
    ): DwRatingOutcome {
        try {
            return DwRatingOutcome.Sent(api.submitDesignRating(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is HttpException || !isTransient(e)) throw e
            OfflineOutbox.enqueue(
                context,
                PendingEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    type = OFFLINE_DESIGN_RATING,
                    payloadJson = offlineJson.encodeToString(
                        body.copy(ratedAt = Instant.now().toString())
                    ),
                    label = label,
                    createdAt = Instant.now().toString(),
                    ownerUserId = cachedUser()?.id,
                )
            )
            return DwRatingOutcome.Queued
        }
    }

    suspend fun designerRoster(
        page: Int = 1,
        pageSize: Int = 50,
        search: String? = null,
        standing: String? = null,
        roles: String? = null,
        institutions: String? = null,
        dateField: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        sort: String? = null,
        dir: String? = null,
    ): RosterPageDto<DesignerRosterDto> = api.designerRoster(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.ifBlank { null },
        standing = standing,
        roles = roles,
        institutions = institutions,
        dateField = dateField,
        dateFrom = dateFrom,
        dateTo = dateTo,
        sort = sort,
        dir = dir,
    )

    suspend fun designerRosterInstitutions(): RosterInstitutionsDto =
        api.designerRosterInstitutions()

    suspend fun designerDirectory(): List<DesignerDirectoryEntryDto> =
        api.designerDirectory(includeSuspended = true)

    suspend fun addDesignerToRoster(
        email: String,
        fullName: String?,
        institution: String?,
        notes: String?
    ): DesignerRosterDto = api.addDesignerToRoster(
        DesignerRosterCreateBody(
            email = email.trim().lowercase(),
            fullName = fullName?.trim()?.ifBlank { null },
            institution = institution?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null }
        )
    )

    suspend fun updateDesignerRosterEntry(
        id: String,
        email: String? = null,
        fullName: String? = null,
        institution: String? = null,
        notes: String? = null,
        suspendOrRestore: Boolean? = null
    ): DesignerRosterDto = api.updateDesignerRosterEntry(
        id,
        designerRosterUpdateJson(
            email = email?.trim()?.lowercase(),
            fullName = fullName,
            institution = institution,
            notes = notes,
            suspendOrRestore = suspendOrRestore
        )
    )

    suspend fun suspendDesigner(id: String): DesignerRosterDto = api.suspendDesigner(id)

    suspend fun restoreDesigner(id: String): DesignerRosterDto =
        api.updateDesignerRosterEntry(id, designerRosterUpdateJson(suspendOrRestore = true))

    suspend fun accessRoster(
        page: Int = 1,
        pageSize: Int = 25,
        status: String? = null,
        search: String? = null,
        roles: String? = null,
        dateField: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        sort: String? = null,
        dir: String? = null,
    ): RosterPageDto<AccessRosterDto> = api.accessRoster(
        page = page,
        pageSize = pageSize,
        status = status,
        search = search?.trim()?.ifBlank { null },
        roles = roles,
        dateField = dateField,
        dateFrom = dateFrom,
        dateTo = dateTo,
        sort = sort,
        dir = dir,
    )

    suspend fun pendingAccessCount(): PendingAccessCountDto = api.pendingAccessCount()

    suspend fun awaitingInspectionCount(): AwaitingInspectionCountDto = api.awaitingInspectionCount()

    suspend fun addToAccessRoster(
        email: String,
        fullName: String? = null,
        role: String? = null,
        notes: String? = null,
        password: String? = null,
        mustChangePassword: Boolean = true
    ): AccessRosterDto = api.addToAccessRoster(
        AccessRosterCreateBody(
            email = email.trim().lowercase(),
            fullName = fullName?.trim()?.ifBlank { null },
            role = role?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null },
            password = password?.takeIf { it.isNotEmpty() },
            mustChangePassword = if (password.isNullOrEmpty()) null else mustChangePassword
        )
    )

    suspend fun approveAccessRequest(id: String, role: String? = null, notes: String? = null): AccessRosterDto =
        api.decideAccessRequest(
            id,
            AccessDecisionBody(
                decision = AccessDecision.APPROVE,
                role = role?.trim()?.ifBlank { null },
                notes = notes?.trim()?.ifBlank { null }
            )
        )

    suspend fun rejectAccessRequest(id: String, notes: String? = null): AccessRosterDto =
        api.decideAccessRequest(
            id,
            AccessDecisionBody(decision = AccessDecision.REJECT, notes = notes?.trim()?.ifBlank { null })
        )

    suspend fun updateAccessEntry(
        id: String,
        fullName: String? = null,
        role: String? = null,
        notes: String? = null
    ): AccessRosterDto = api.updateAccessEntry(id, accessRosterUpdateJson(fullName, role, notes))

    suspend fun suspendAccessEntry(id: String): AccessRosterDto = api.suspendAccessEntry(id)

    suspend fun designerProfile(userId: String? = null, context: Context? = null): DesignerProfileDto? = try {
        if (userId == null) {
            api.myDesignerProfile().also { own -> rememberOwnDesignerProfile(context, own) }
        } else {
            api.designerProfile(userId)
        }
    } catch (e: HttpException) {
        if (e.code() == 404) null else throw e
    }

    private fun rememberOwnDesignerProfile(context: Context?, profile: DesignerProfileDto) {
        val target = (context ?: appContextForReplay)?.applicationContext ?: return
        val me = cachedUser()?.id.orEmpty()
        if (me.isBlank()) return
        if (profile.userId.isNotBlank() && profile.userId != me) return
        DesignerPrefillCache.saveOwnProfile(target, me, profile)
    }

    suspend fun designerPrefillFor(
        context: Context,
        localWorkshopId: String,
        serverId: String?,
    ): Map<String, JsonElement> {
        if (!serverId.isNullOrBlank()) {
            if (isOnline(context)) {
                val answer = runCatching { api.designWorkshopDesignerPrefill(serverId) }.getOrNull()
                if (answer != null) {
                    DesignerPrefillCache.saveWorkshop(context, serverId, answer.values)
                    return answer.values
                }
            }
            DesignerPrefillCache.workshop(context, serverId)?.let { return it }
        }
        if (!isLocalOnlyWorkshop(localWorkshopId)) return emptyMap()
        val me = cachedUser() ?: return emptyMap()
        val draft = WorkshopDraftStore.load(context, localWorkshopId)
        val owner = draft?.ownerUserId
        val lead = draft?.designerUserId
        if (!owner.isNullOrBlank() && owner != me.id) return emptyMap()
        if (!lead.isNullOrBlank() && lead != me.id) return emptyMap()
        val profile = DesignerPrefillCache.ownProfile(context, me.id) ?: DesignerProfileDto()
        return designerPrefillFromProfile(profile, me.name)
    }

    suspend fun saveDesignerProfileOrQueue(
        context: Context,
        userId: String?,
        body: DesignerProfileUpdateBody,
    ): DesignerProfileDto? {
        if (isOnline(context)) {
            try {
                return saveDesignerProfile(userId, body, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isTransient(e)) throw e
            }
        }
        if (userId == null) {
            cachedUser()?.id?.takeIf { it.isNotBlank() }?.let { me ->
                DesignerPrefillCache.saveOwnProfile(context, me, body.asProfile(DesignerPrefillCache.ownProfile(context, me)))
            }
        }
        val payload = kotlinx.serialization.json.buildJsonObject {
            userId?.let { put("userId", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("body", designerProfileUpdateJson(body))
        }
        OfflineOutbox.enqueue(
            context,
            PendingEntry(
                id = java.util.UUID.randomUUID().toString(),
                type = OFFLINE_DESIGNER_PROFILE,
                payloadJson = payload.toString(),
                label = body.displayName?.takeIf { it.isNotBlank() } ?: "Designer profile",
                createdAt = Instant.now().toString(),
                ownerUserId = cachedUser()?.id,
            )
        )
        return null
    }

    suspend fun saveDesignerProfile(
        userId: String?,
        body: DesignerProfileUpdateBody,
        context: Context? = null,
    ): DesignerProfileDto {
        val encoded = designerProfileUpdateJson(body)
        return if (userId == null) {
            api.updateMyDesignerProfile(encoded).also { own -> rememberOwnDesignerProfile(context, own) }
        } else {
            api.updateDesignerProfile(userId, encoded)
        }
    }

    suspend fun customQuestionnaires(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
        designWorkshopId: String? = null,
        activeOnly: Boolean = true,
        mineOnly: Boolean = false,
        filters: Map<String, String> = emptyMap(),
    ): PageResponse<CustomQuestionnaireSummaryDto> = api.customQuestionnaires(
        page = page,
        pageSize = pageSize,
        search = search?.trim()?.takeIf { it.isNotEmpty() },
        designWorkshopId = designWorkshopId?.takeIf { it.isNotBlank() },
        activeOnly = activeOnly,
        mineOnly = mineOnly,
        filters = filters,
    )

    suspend fun deleteCustomQuestionnaire(id: String) = api.deleteCustomQuestionnaire(id)

    suspend fun formBindings(id: String): FormBindingsDto? = runCatching { api.formBindings(id) }.getOrNull()

    suspend fun restoreCustomQuestionnaire(id: String) {
        api.restoreCustomQuestionnaire(id)
    }

    suspend fun deletedCustomQuestionnaires(): List<CustomQuestionnaireSummaryDto> =
        api.customQuestionnaires(page = 1, pageSize = 100, activeOnly = false, deletedOnly = true).items

    suspend fun deleteCustomEntry(id: String, entryId: String) = api.deleteCustomEntry(id, entryId)

    suspend fun deletedCustomEntries(id: String): List<DeletedCustomEntryDto> = api.deletedCustomEntries(id)

    suspend fun restoreCustomEntry(id: String, entryId: String) {
        api.restoreCustomEntry(id, entryId)
    }

    suspend fun customQuestionnaireOptions(
        designWorkshopId: String? = null
    ): List<CustomQuestionnaireOptionDto> =
        api.customQuestionnaireOptions(designWorkshopId?.takeIf { it.isNotBlank() })

    suspend fun customQuestionnaire(
        id: String,
        includeRetired: Boolean = false
    ): CustomQuestionnaireDto = api.customQuestionnaire(id, includeRetired)

    suspend fun customQuestionnaireCached(
        context: Context,
        id: String,
        includeRetired: Boolean = false,
        acceptEitherCachedView: Boolean = false,
    ): QuestionnaireFormRead {
        val live = runCatching { api.customQuestionnaire(id, includeRetired) }
        live.getOrNull()?.let { form ->
            DwQuestionnaireFormCache.put(context, form, includeRetired, cachedUser()?.id)
            return QuestionnaireFormRead(form = form, fromCache = false)
        }
        val error = live.exceptionOrNull() ?: IllegalStateException("This questionnaire could not be opened.")
        if (error is HttpException && error.code() !in setOf(408, 429) && error.code() < 500) throw error
        val cached = if (acceptEitherCachedView) {
            DwQuestionnaireFormCache.getEither(context, id, preferred = includeRetired, viewerId = cachedUser()?.id)
        } else {
            DwQuestionnaireFormCache.get(context, id, includeRetired, cachedUser()?.id)
        } ?: throw error
        return QuestionnaireFormRead(
            form = cached.form,
            fromCache = true,
            cachedAt = cached.fetchedAt.takeIf { it.isNotBlank() },
        )
    }

    data class QuestionnaireHandoffFile(
        val savedTo: String,
        val shareUri: Uri?,
        val bundle: QuestionnaireBundle,
        val handoffCode: String,
    )

    suspend fun buildQuestionnaireHandoffFile(
        context: Context,
        questionnaireId: String,
    ): QuestionnaireHandoffFile = withContext(Dispatchers.IO) {
        val read = customQuestionnaireCached(
            context = context,
            id = questionnaireId,
            includeRetired = false,
            acceptEitherCachedView = true,
        )
        val bundle = questionnaireBundleOf(read.form)
        if (bundle.sections.none { it.questions.isNotEmpty() }) {
            throw IllegalStateException(
                "No questions yet — nothing to send."
            )
        }
        val now = java.time.OffsetDateTime.now(Naming.IST)
        val bytes = encodeQuestionnaireBundleV2(bundle, newQuestionnaireBundleId(), now.toString())
        val handoffCode = questionnaireHandoffCode(bundle)
        val name = questionnaireHandoffFilename(bundle.title, now.toLocalDate().toString())
        val staging = File(context.cacheDir, name)
        FileOutputStream(staging).use { it.write(bytes) }
        val savedTo = try {
            persistFileToDownloads(context, staging, name, QUESTIONNAIRE_BUNDLE_MIME)
        } finally {
            runCatching { staging.delete() }
        }
        QuestionnaireHandoffFile(
            savedTo = savedTo,
            shareUri = shareUriForSavedFile(context, savedTo),
            bundle = bundle,
            handoffCode = handoffCode,
        )
    }

    suspend fun receiveQuestionnaireHandoff(
        context: Context,
        uri: Uri,
    ): Result<ReceivedQuestionnaire> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    if (out.size() + n > QUESTIONNAIRE_HANDOFF_MAX_FILE) {
                        throw IllegalStateException(
                            "That file is too big to be a questionnaire."
                        )
                    }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }.getOrElse { error ->
            return@withContext Result.failure(
                IllegalStateException(
                    error.message ?: "Couldn't open that file."
                )
            )
        } ?: return@withContext Result.failure(
            IllegalStateException(
                "Couldn't open that file. Save it to the phone first, then pick it."
            )
        )
        when (val read = readQuestionnaireBundle(bytes)) {
            is QuestionnaireBundleRead.Refused -> Result.failure(IllegalStateException(read.message))
            is QuestionnaireBundleRead.Ok -> Result.success(
                QuestionnaireBundleInbox.put(
                    context = context,
                    filename = displayNameForUri(context, uri) ?: "questionnaire.$QUESTIONNAIRE_BUNDLE_EXTENSION",
                    bytes = bytes,
                    bundle = read.bundle,
                    bundleId = read.bundleId,
                )
            )
        }
    }

    private fun displayNameForUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    suspend fun receivedQuestionnaires(context: Context): List<ReceivedQuestionnaire> =
        QuestionnaireBundleInbox.all(context)

    suspend fun discardReceivedQuestionnaire(context: Context, id: String): Boolean =
        QuestionnaireBundleInbox.remove(context, id)

    suspend fun adoptReceivedQuestionnaire(
        context: Context,
        row: ReceivedQuestionnaire,
        onProgress: (sectionsDone: Int, sectionCount: Int) -> Unit = { _, _ -> },
    ): Result<ReceivedQuestionnaire> {
        val bytes = QuestionnaireBundleInbox.bytes(context, row)
            ?: return Result.failure(
                IllegalStateException(
                    "The saved copy is gone. Ask for it again."
                )
            )
        val bundle = when (val read = readQuestionnaireBundle(bytes)) {
            is QuestionnaireBundleRead.Refused -> return Result.failure(IllegalStateException(read.message))
            is QuestionnaireBundleRead.Ok -> read.bundle
        }
        val sections = bundle.sections.filter { it.questions.isNotEmpty() }
        var state = row.copy(failure = null)
        QuestionnaireBundleInbox.update(context, state)

        suspend fun persist(next: ReceivedQuestionnaire) {
            state = next
            QuestionnaireBundleInbox.update(context, next)
        }

        try {
            if (state.remoteId == null) {
                val created = createCustomQuestionnaire(
                    title = bundle.title.ifBlank { "Questionnaire" },
                    description = bundle.description,
                    designWorkshopId = null,
                    kind = bundle.kind ?: row.kind,
                    idempotencyKey = adoptionIdempotencyKey(row.id, "q"),
                )
                persist(state.copy(remoteId = created.id))
            }
            val remoteId = state.remoteId ?: error("unreachable: the questionnaire was just created")

            var sectionIndex = state.sectionsDone
            while (sectionIndex < sections.size) {
                val section = sections[sectionIndex]
                var sectionRemoteId = state.currentSectionRemoteId
                if (sectionRemoteId == null) {
                    val alreadyThere = if (state.sectionCreateStarted) {
                        runCatching { customQuestionnaire(remoteId, includeRetired = true) }
                            .getOrNull()
                            ?.let { matchAdoptedSection(it, section, exactOnly = true) }
                    } else {
                        null
                    }
                    if (alreadyThere != null) {
                        sectionRemoteId = alreadyThere
                    } else {
                        persist(state.copy(sectionCreateStarted = true))
                        val afterSection = addCustomSection(
                            idempotencyKey = adoptionIdempotencyKey(row.id, "s$sectionIndex"),
                            id = remoteId,
                            title = section.title.ifBlank { section.code.ifBlank { "Section" } },
                            code = section.code.takeIf { it.isNotBlank() },
                        )
                        sectionRemoteId = matchAdoptedSection(afterSection, section)
                            ?: return Result.failure(
                                IllegalStateException(
                                    "Section “${section.title}” couldn't be filled. Nothing was lost — try again."
                                )
                            )
                    }
                    persist(
                        state.copy(
                            currentSectionRemoteId = sectionRemoteId,
                            questionsDone = 0,
                            sectionCreateStarted = false,
                        )
                    )
                }

                var questionIndex = state.questionsDone
                while (questionIndex < section.questions.size) {
                    val question = section.questions[questionIndex]
                    addCustomQuestion(
                        id = remoteId,
                        sectionId = sectionRemoteId,
                        prompt = question.prompt,
                        helpText = question.helpText,
                        isRequired = question.isRequired,
                        idempotencyKey = adoptionIdempotencyKey(row.id, "s$sectionIndex", "q$questionIndex"),
                    )
                    questionIndex++
                    persist(state.copy(questionsDone = questionIndex))
                }

                sectionIndex++
                persist(state.copy(sectionsDone = sectionIndex, currentSectionRemoteId = null, questionsDone = 0))
                onProgress(sectionIndex, sections.size)
            }
            persist(state.copy(adoptedAt = Instant.now().toString()))
            return Result.success(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = e.apiErrorMessage("The questionnaire could not be added.")
            persist(state.copy(failure = reason))
            return Result.failure(IllegalStateException(reason))
        }
    }

    private fun matchAdoptedSection(
        form: CustomQuestionnaireDto,
        wanted: QuestionnaireBundleSection,
        exactOnly: Boolean = false,
    ): String? {
        val fresh = form.sections.filter { it.questions.isEmpty() }
        if (wanted.code.isNotBlank()) {
            fresh.firstOrNull { it.code.equals(wanted.code, ignoreCase = true) }?.let { return it.id }
        }
        fresh.firstOrNull { it.title.trim().equals(wanted.title.trim(), ignoreCase = true) }
            ?.let { return it.id }
        if (exactOnly) return null
        return fresh.maxByOrNull { it.sortOrder }?.id
    }

    suspend fun createCustomQuestionnaireOrQueue(
        context: Context,
        title: String,
        description: String?,
        designWorkshopId: String?,
        kind: String? = null,
    ): CustomQuestionnaireDto? {
        val body = CustomQuestionnaireCreateBody(
            title = title.trim(),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            designWorkshopId = designWorkshopId?.takeIf { it.isNotBlank() },
            kind = kind?.takeIf { it.isNotBlank() },
        )
        val key = java.util.UUID.randomUUID().toString()
        if (isOnline(context)) {
            val sent = runCatching { api.createCustomQuestionnaire(body, key) }
            sent.getOrNull()?.let { return it }
            val error = sent.exceptionOrNull()
            if (error != null && error !is CancellationException && !isConnectionFailure(error)) throw error
        }
        val tempId = newTempId()
        val result = queueOfflineEntry(
            context = context,
            type = OFFLINE_CUSTOM_QUESTIONNAIRE,
            payloadJson = offlineJson.encodeToString(body),
            label = "Questionnaire · ${body.title}",
            items = emptyList(),
            idempotencyKey = key,
        )
        OfflineOutbox.setTempId(context, result.entryId, tempId)
        return CustomQuestionnaireDto(
            id = tempId,
            title = body.title,
            description = body.description,
            ownerId = cachedUser()?.id,
            designWorkshopId = body.designWorkshopId,
            kind = body.kind,
        )
    }

    suspend fun pendingQuestionnaire(context: Context, tempId: String): CustomQuestionnaireDto? {
        val entry = OfflineOutbox.all(context).firstOrNull { it.type == OFFLINE_CUSTOM_QUESTIONNAIRE && it.tempId == tempId }
            ?: return null
        val body = runCatching { offlineJson.decodeFromString<CustomQuestionnaireCreateBody>(entry.payloadJson) }.getOrNull()
            ?: return null
        return CustomQuestionnaireDto(
            id = tempId,
            title = body.title,
            description = body.description,
            ownerId = entry.ownerUserId,
            designWorkshopId = body.designWorkshopId,
            kind = body.kind,
        )
    }

    suspend fun pendingQuestionnaires(context: Context): List<CustomQuestionnaireDto> {
        val me = cachedUser()?.id
        return OfflineOutbox.all(context)
            .filter { it.type == OFFLINE_CUSTOM_QUESTIONNAIRE && it.tempId != null && it.createdId == null }
            .filter { !dwDraftIsForAnotherAccount(it.ownerUserId, me) }
            .mapNotNull { pendingQuestionnaire(context, it.tempId!!) }
    }

    suspend fun pendingQuestionnaireOps(context: Context, ids: Set<String>): List<QFormOp> {
        val map = TempIdMap.all(context)
        val wanted = ids.flatMap { listOfNotNull(it, resolveTempId(it, map)) }.toSet()
        return OfflineOutbox.all(context)
            .filter { it.type == OFFLINE_QFORM_OP && it.createdId == null }
            .mapNotNull { runCatching { offlineJson.decodeFromString<QFormOp>(it.payloadJson) }.getOrNull() }
            .filter { it.questionnaireId in wanted || resolveTempId(it.questionnaireId, map) in wanted }
    }

    suspend fun customQuestionnaireForAuthoring(context: Context, id: String): QuestionnaireFormRead {
        val map = TempIdMap.all(context)
        val serverId = resolveTempId(id, map) ?: id
        val base = if (!isTempId(serverId)) {
            customQuestionnaireCached(context, serverId, includeRetired = true)
        } else {
            val queued = pendingQuestionnaire(context, id)
                ?: throw IllegalStateException("This questionnaire is no longer on this phone.")
            QuestionnaireFormRead(form = queued, fromCache = false)
        }
        val ops = pendingQuestionnaireOps(context, setOf(id, serverId))
        return base.copy(form = applyPendingAuthoring(base.form, ops, map))
    }

    suspend fun applyQuestionnaireOp(context: Context, op: QFormOp, idempotencyKey: String?): String? {
        val map = TempIdMap.all(context)
        fun need(id: String?, what: String): String {
            val resolved = resolveTempId(id, map)
            if (resolved == null || isTempId(resolved)) {
                throw QFormParentMissing(
                    "Waiting for its $what to be saved."
                )
            }
            return resolved
        }
        val qid = need(op.questionnaireId, "questionnaire")
        return when (op.op) {
            QFormOp.ADD_SECTION -> {
                val result = api.createCustomSection(qid, CustomSectionCreateBody(title = op.title.orEmpty().trim()), idempotencyKey)
                op.tempId?.let { temp ->
                    createdSectionId(result, op.title.orEmpty(), map.values)?.let { TempIdMap.put(context, temp, it) }
                }
                null
            }
            QFormOp.RENAME_SECTION -> {
                updateCustomSection(qid, need(op.sectionId, "section"), title = op.title)
                null
            }
            QFormOp.ADD_QUESTION -> {
                val sectionId = need(op.sectionId, "section")
                val result = api.createCustomQuestion(
                    qid,
                    sectionId,
                    CustomQuestionCreateBody(
                        prompt = op.prompt.orEmpty().trim(),
                        helpText = op.helpText?.trim()?.ifBlank { null },
                        isRequired = op.isRequired ?: false,
                    ),
                    idempotencyKey,
                )
                op.tempId?.let { temp ->
                    createdQuestionId(result, sectionId, op.prompt.orEmpty(), map.values)?.let { TempIdMap.put(context, temp, it) }
                }
                null
            }
            QFormOp.EDIT_QUESTION -> updateCustomQuestion(
                id = qid,
                questionId = need(op.questionId, "question"),
                prompt = op.prompt,
                helpText = op.helpText,
                changeHelpText = op.changeHelpText,
                isRequired = op.isRequired,
            ).detail
            QFormOp.REMOVE_QUESTION -> removeCustomQuestion(qid, need(op.questionId, "question")).detail
            QFormOp.RENAME_QUESTIONNAIRE -> {
                updateCustomQuestionnaire(
                    id = qid,
                    title = op.title,
                    description = op.description,
                    changeDescription = op.changeDescription,
                )
                null
            }
            QFormOp.REUSE -> {
                reuseQuestionnaire(
                    id = qid,
                    designWorkshopId = op.designWorkshopId?.let { need(it, "design & prototype workshop") },
                    title = op.title,
                    description = op.description,
                    changeDescription = op.changeDescription,
                    idempotencyKey = idempotencyKey,
                )
                null
            }
            else -> throw SerializationException("Unknown questionnaire step: ${op.op}")
        }
    }

    suspend fun authorQuestionnaireOrQueue(context: Context, op: QFormOp, label: String): String {
        val creates = op.op == QFormOp.ADD_SECTION || op.op == QFormOp.ADD_QUESTION
        val key = if (creates) java.util.UUID.randomUUID().toString() else null
        val map = TempIdMap.all(context)
        val waitsForParent = listOf(op.questionnaireId, op.sectionId, op.questionId)
            .any { isTempId(it) && isTempId(resolveTempId(it, map)) }
        val behindOthers = pendingQuestionnaireOps(context, setOf(op.questionnaireId)).isNotEmpty()
        val online = isOnline(context)
        if (!waitsForParent && !behindOthers && online) {
            try {
                return applyQuestionnaireOp(context, op, key) ?: "Saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isTransient(e)) throw e
            }
        }
        OfflineOutbox.enqueue(
            context,
            PendingEntry(
                id = java.util.UUID.randomUUID().toString(),
                type = OFFLINE_QFORM_OP,
                payloadJson = offlineJson.encodeToString(QFormOp.serializer(), op),
                label = label,
                createdAt = Instant.now().toString(),
                ownerUserId = cachedUser()?.id,
                idempotencyKey = key,
            )
        )
        val earlierRefused = behindOthers && OfflineOutbox.all(context).any { entry ->
            entry.type == OFFLINE_QFORM_OP && entry.createdId == null && entry.failure != null &&
                runCatching { offlineJson.decodeFromString(QFormOp.serializer(), entry.payloadJson) }.getOrNull()
                    ?.let { it.questionnaireId == op.questionnaireId || resolveTempId(it.questionnaireId, map) == resolveTempId(op.questionnaireId, map) } == true
        }
        return queuedAuthoringMessage(online = online, waitsForParent = waitsForParent, earlierRefused = earlierRefused)
    }

    suspend fun createCustomQuestionnaire(
        title: String,
        description: String? = null,
        designWorkshopId: String? = null,
        kind: String? = null,
        idempotencyKey: String? = null,
    ): CustomQuestionnaireDto = api.createCustomQuestionnaire(
        CustomQuestionnaireCreateBody(
            title = title.trim(),
            description = description?.trim()?.ifBlank { null },
            designWorkshopId = designWorkshopId?.takeIf { it.isNotBlank() },
            kind = kind?.takeIf { it.isNotBlank() },
        ),
        idempotencyKey,
    )

    suspend fun updateCustomQuestionnaire(
        id: String,
        title: String? = null,
        description: String? = null,
        changeDescription: Boolean = false,
        designWorkshopId: String? = null,
        changeWorkshop: Boolean = false,
        kind: String? = null,
        changeKind: Boolean = false,
        isActive: Boolean? = null,
    ): CustomQuestionnaireDto = api.updateCustomQuestionnaire(
        id,
        customQuestionnaireUpdateJson(
            title = title,
            description = description,
            changeDescription = changeDescription,
            designWorkshopId = designWorkshopId,
            changeWorkshop = changeWorkshop,
            kind = kind,
            changeKind = changeKind,
            isActive = isActive,
        )
    )

    suspend fun addCustomSection(
        id: String,
        title: String,
        code: String? = null,
        idempotencyKey: String? = null,
    ): CustomQuestionnaireDto = api.createCustomSection(
        id,
        CustomSectionCreateBody(
            title = title.trim(),
            code = code?.trim()?.ifBlank { null },
        ),
        idempotencyKey,
    )

    suspend fun updateCustomSection(
        id: String,
        sectionId: String,
        title: String? = null,
        sortOrder: Int? = null,
        isActive: Boolean? = null,
    ): CustomQuestionnaireDto = api.updateCustomSection(
        id,
        sectionId,
        CustomSectionPatchBody(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            sortOrder = sortOrder,
            isActive = isActive,
        )
    )

    suspend fun addCustomQuestion(
        id: String,
        sectionId: String,
        prompt: String,
        helpText: String? = null,
        isRequired: Boolean = false,
        idempotencyKey: String? = null,
    ): CustomQuestionnaireDto = api.createCustomQuestion(
        id,
        sectionId,
        CustomQuestionCreateBody(
            prompt = prompt.trim(),
            helpText = helpText?.trim()?.ifBlank { null },
            isRequired = isRequired,
        ),
        idempotencyKey,
    )

    suspend fun updateCustomQuestion(
        id: String,
        questionId: String,
        prompt: String? = null,
        helpText: String? = null,
        changeHelpText: Boolean = false,
        isRequired: Boolean? = null,
    ): CustomQuestionEditResultDto = api.updateCustomQuestion(
        id,
        questionId,
        customQuestionUpdateJson(
            prompt = prompt,
            helpText = helpText,
            changeHelpText = changeHelpText,
            isRequired = isRequired,
        )
    )

    suspend fun removeCustomQuestion(
        id: String,
        questionId: String
    ): CustomQuestionEditResultDto = api.removeCustomQuestion(id, questionId)

    suspend fun startCustomEntry(
        id: String,
        title: String? = null,
        respondentName: String? = null,
        notes: String? = null,
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
    ): CustomEntryDto = api.createCustomEntry(
        id,
        CustomEntryCreateBody(
            title = title?.trim()?.ifBlank { null },
            respondentName = respondentName?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null },
        ),
        idempotencyKey,
    )

    suspend fun updateCustomEntry(
        id: String,
        entryId: String,
        title: String? = null,
        respondentName: String? = null,
        notes: String? = null,
    ): CustomEntryDto = api.updateCustomEntry(
        id,
        entryId,
        CustomEntryPatchBody(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            respondentName = respondentName?.trim(),
            notes = notes?.trim(),
        )
    )

    suspend fun saveCustomAnswers(
        id: String,
        entryId: String,
        answers: List<CustomAnswerInputBody>,
    ): CustomAnswerSaveResultDto =
        api.saveCustomAnswers(id, entryId, CustomAnswerBatchBody(answers = answers))

    suspend fun downloadQuestionnaireArtefact(
        context: Context,
        artefact: DwQuestionnaireArtefact,
        questionnaireId: String? = null,
        fallbackStem: String = "questionnaire",
    ): String = withContext(Dispatchers.IO) {
        val response = when (artefact) {
            DwQuestionnaireArtefact.PRO_FORMA -> api.questionnaireProForma()
            DwQuestionnaireArtefact.QUESTION_SET -> api.questionnaireQuestionSet(
                requireNotNull(questionnaireId) { "A question set is a download OF a questionnaire." }
            )
            DwQuestionnaireArtefact.FULL_WORKBOOK -> api.questionnaireWorkbook(
                requireNotNull(questionnaireId) { "A workbook is a download OF a questionnaire." }
            )
        }
        if (!response.isSuccessful) {
            throw IllegalStateException(
                errorBodyDetail(response.errorBody()?.string())
                    ?: "That workbook could not be downloaded. Try again."
            )
        }
        val body = response.body()
            ?: throw IllegalStateException("That workbook could not be downloaded. Try again.")
        val name = filenameFromContentDisposition(response.headers()["Content-Disposition"])
            ?: defaultArtefactFilename(artefact, fallbackStem)
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, XLSX_MIME)
        tmp.delete()
        location
    }

    suspend fun uploadQuestionnaireWorkbook(
        context: Context,
        uri: Uri,
        title: String? = null,
        designWorkshopId: String? = null,
    ): QFormUploadResultDto {
        val part = workbookPart(context, uri)
        return api.uploadQuestionnaire(
            file = part,
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.toPlainPart(),
            designWorkshopId = designWorkshopId?.trim()?.takeIf { it.isNotEmpty() }?.toPlainPart(),
        )
    }

    suspend fun reuploadQuestionnaireWorkbook(
        context: Context,
        questionnaireId: String,
        uri: Uri,
        title: String? = null,
    ): QFormUploadResultDto {
        val part = workbookPart(context, uri)
        return api.reuploadQuestionnaire(
            id = questionnaireId,
            file = part,
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.toPlainPart(),
        )
    }

    suspend fun reuseQuestionnaire(
        id: String,
        designWorkshopId: String? = null,
        title: String? = null,
        description: String? = null,
        changeDescription: Boolean = false,
        idempotencyKey: String? = null,
    ): QFormReuseResultDto = api.reuseQuestionnaire(
        id,
        questionnaireReuseJson(
            designWorkshopId = designWorkshopId,
            title = title,
            description = description,
            changeDescription = changeDescription,
        ),
        idempotencyKey,
    )

    suspend fun reuseQuestionnaireOrQueue(
        context: Context,
        id: String,
        designWorkshopId: String? = null,
        title: String? = null,
        description: String? = null,
        changeDescription: Boolean = false,
        label: String,
    ): QFormReuseResultDto? {
        val op = QFormOp(
            op = QFormOp.REUSE,
            questionnaireId = id,
            designWorkshopId = designWorkshopId,
            title = title,
            description = description,
            changeDescription = changeDescription,
        )
        val key = java.util.UUID.randomUUID().toString()
        val map = TempIdMap.all(context)
        val pending = pendingQuestionnaireOps(context, setOf(id)).size
        if (reuseCanSendNow(id, designWorkshopId, map, pending, isOnline(context))) {
            try {
                return reuseQuestionnaire(
                    resolveTempId(id, map) ?: id,
                    resolveTempId(designWorkshopId, map),
                    title,
                    description,
                    changeDescription,
                    key,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isTransient(e)) throw e
            }
        }
        OfflineOutbox.enqueue(
            context,
            PendingEntry(
                id = java.util.UUID.randomUUID().toString(),
                type = OFFLINE_QFORM_OP,
                payloadJson = offlineJson.encodeToString(QFormOp.serializer(), op),
                label = label,
                createdAt = Instant.now().toString(),
                ownerUserId = cachedUser()?.id,
                idempotencyKey = key,
            )
        )
        return null
    }

    private suspend fun workbookPart(context: Context, uri: Uri): okhttp3.MultipartBody.Part {
        val mimeType = context.contentResolver.getType(uri) ?: XLSX_MIME
        val name = displayName(context, uri)?.let { safeDownloadName(it) } ?: "questionnaire.xlsx"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException(
                    "Couldn't open that file. Pick it from Downloads, or copy it to the phone first."
                )
        }
        if (bytes.isEmpty()) {
            throw IllegalStateException("That file is empty.")
        }
        return okhttp3.MultipartBody.Part.createFormData(
            "file",
            name,
            bytes.toRequestBody(mimeType.toMediaType())
        )
    }

    private fun String.toPlainPart(): okhttp3.RequestBody =
        toRequestBody("text/plain".toMediaType())

    private fun defaultArtefactFilename(
        artefact: DwQuestionnaireArtefact,
        stem: String,
    ): String {
        val safe = stem.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60)
            .ifBlank { "questionnaire" }
        return when (artefact) {
            DwQuestionnaireArtefact.PRO_FORMA -> "questionnaire-pro-forma.xlsx"
            DwQuestionnaireArtefact.QUESTION_SET -> "$safe-questions.xlsx"
            DwQuestionnaireArtefact.FULL_WORKBOOK -> "$safe.xlsx"
        }
    }

    suspend fun login(email: String, password: String): SignInOutcome =
        open(api.login(LoginRequest(email = email.trim(), password = password)))

    suspend fun loginWithGoogle(idToken: String): SignInOutcome =
        open(api.googleLogin(GoogleLoginRequest(googleIdToken = idToken)))

    suspend fun loginWithOidc(body: OidcLoginRequest): SignInOutcome = open(api.oidcLogin(body))

    private fun open(response: SignInResponse): SignInOutcome {
        val token = response.accessToken
        val user = response.user
        if (token != null && user != null) {
            tokenStore.setToken(token)
            tokenStore.setUser(user)
            return SignInOutcome.SignedIn(user)
        }
        val challenge = response.challengeToken
        if (response.twoFactorRequired && challenge != null) return SignInOutcome.TwoStepRequired(challenge)
        throw IllegalStateException("The server's answer could not be read. Try again.")
    }

    private val twoFactorApi: TwoFactorApi by lazy {
        ApiClient.retrofit(tokenStore).create(TwoFactorApi::class.java)
    }

    suspend fun verifyTwoStep(challengeToken: String, code: String): UserDto {
        val response = twoFactorApi.verify(TwoFactorVerifyRequest(challengeToken = challengeToken, code = code.trim()))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    suspend fun twoFactorStatus(): TwoFactorStatusDto = twoFactorApi.status()

    suspend fun beginTwoFactorSetup(): TwoFactorSetupDto = twoFactorApi.setup()

    suspend fun cancelTwoFactorSetup() {
        twoFactorApi.cancelSetup()
    }

    suspend fun enableTwoFactor(code: String): List<String> =
        twoFactorApi.enable(TwoFactorCodeRequest(code.trim())).recoveryCodes

    suspend fun disableTwoFactor(code: String) {
        twoFactorApi.disable(TwoFactorCodeRequest(code.trim()))
    }

    suspend fun makeNewRecoveryCodes(code: String): List<String> =
        twoFactorApi.recoveryCodes(TwoFactorCodeRequest(code.trim())).recoveryCodes

    suspend fun resetTwoFactorForUser(userId: String): UserDto = twoFactorApi.resetForUser(userId)

    suspend fun logout(context: Context) {
        val signedOut = tokenStore.getUser()?.id
        tokenStore.clear()
        runCatching { DwQuestionnaireFormCache.forgetAll(context) }
        runCatching { NotificationSync.signOut(context, signedOut) }
        runCatching { ReadCache.evictAll(context) }
        runCatching { com.designprototype.workshop.explorer.data.ExplorerDownloads.forgetAll(context) }
    }

    suspend fun currentUser(): UserDto = api.me()

    suspend fun refreshUser(): UserDto {
        val user = api.me()
        tokenStore.setUser(user)
        return user
    }

    fun holdForPasswordChange(): UserDto? {
        val held = cachedUser()?.copy(mustChangePassword = true) ?: return null
        tokenStore.setUser(held)
        return held
    }

    suspend fun changeOwnPassword(currentPassword: String, newPassword: String) {
        val answer = api.changeOwnPassword(
            ChangePasswordRequest(currentPassword = currentPassword, newPassword = newPassword)
        )
        if (!answer.isSuccessful) throw HttpException(answer)
        answer.headers()[SESSION_TOKEN_HEADER]?.takeIf { it.isNotBlank() }?.let { tokenStore.setToken(it) }
        val profile = runCatching { refreshUser() }.getOrNull() ?: cachedUser()
        if (profile?.mustChangePassword == true) {
            tokenStore.setUser(profile.copy(mustChangePassword = false))
        }
    }

    suspend fun issuePasswordLink(userId: String): IssuedPasswordLinkDto =
        api.issuePasswordLink(IssuePasswordLinkRequest(userId = userId))

    suspend fun revokePasswordLink(linkId: String) {
        api.revokePasswordLink(linkId)
    }

    suspend fun checkPasswordLink(token: String): PasswordLinkCheckDto =
        try {
            api.checkPasswordLinkInBody(PasswordLinkCheckRequest(token = token))
        } catch (refused: HttpException) {
            if (refused.code() != 404 && refused.code() != 405) throw refused
            api.checkPasswordLink(token)
        }

    suspend fun setPasswordWithLink(token: String, password: String) {
        api.setPasswordWithLink(SetPasswordRequest(token = token, password = password))
        tokenStore.clear()
    }

    private val recoveryApi: AccountRecoveryApi by lazy {
        ApiClient.retrofit(tokenStore).create(AccountRecoveryApi::class.java)
    }

    private val notificationApi: NotificationPreferencesApi by lazy {
        ApiClient.retrofit(tokenStore).create(NotificationPreferencesApi::class.java)
    }

    suspend fun notificationPreferences(): NotificationPreferencesDto = notificationApi.read()

    suspend fun saveNotificationPreferences(change: NotificationPreferencesUpdate): NotificationPreferencesDto =
        notificationApi.save(change)

    suspend fun forgotPassword(email: String): String =
        recoveryApi.forgotPassword(ForgotPasswordRequest(email = email.trim().lowercase())).message

    suspend fun saveOwnPassword(
        currentPassword: String?,
        newPassword: String,
        twoFactorCode: String? = null,
        proof: FirstPasswordProof? = null,
    ) {
        if (currentPassword != null) {
            changeOwnPassword(currentPassword, newPassword)
            return
        }
        val answer = recoveryApi.setFirstPassword(firstPasswordRequest(newPassword, twoFactorCode, proof))
        if (!answer.isSuccessful) {
            val refused = HttpException(answer)
            if (answer.headers()[TWO_FACTOR_REQUIRED_HEADER] != null) {
                throw TwoFactorCodeRequired(refused.apiErrorMessage(FIRST_PASSWORD_CODE_MISSING))
            }
            answer.headers()[CONFIRM_PROVIDER_HEADER]?.let { provider ->
                throw ProviderConfirmationRequired(
                    refused.apiErrorMessage(firstPasswordConfirmPrompt(provider)),
                    provider.trim().uppercase(),
                )
            }
            throw refused
        }
        answer.headers()[SESSION_TOKEN_HEADER]?.takeIf { it.isNotBlank() }?.let { tokenStore.setToken(it) }
        val profile = runCatching { refreshUser() }.getOrNull() ?: cachedUser()
        if (profile != null && profile.hasPassword != true) {
            tokenStore.setUser(profile.copy(hasPassword = true, mustChangePassword = false))
        }
    }

    suspend fun emailPasswordLink(userId: String): IssuedPasswordLinkDto =
        recoveryApi.issuePasswordLink(PasswordLinkDeliveryRequest(userId = userId, delivery = PASSWORD_LINK_BY_EMAIL))

    suspend fun setTemporaryPassword(userId: String, password: String): UserDto =
        recoveryApi.setTemporaryPassword(userId, TemporaryPasswordBody(password = password))

    suspend fun signOutEverywhere(userId: String): UserDto = recoveryApi.signOutEverywhere(userId)

    suspend fun mailAvailable(): Boolean {
        knownMailAvailable?.let { return it }
        val answer = runCatching { recoveryApi.mailStatus().available }.getOrNull() ?: return false
        knownMailAvailable = answer
        return answer
    }

    @Volatile
    private var knownMailAvailable: Boolean? = null

    suspend fun createAccount(body: CreateAccountBody): CreatedAccountDto {
        val answer = recoveryApi.createAccount(body.copy(email = body.email.trim().lowercase(), name = body.name.trim()))
        if (answer.isSuccessful) {
            return answer.body() ?: throw IllegalStateException("The server's answer could not be read. Try again.")
        }
        val refused = HttpException(answer)
        if (answer.code() == 409) {
            throw AccountAlreadyExists(
                refused.apiErrorMessage("That address already has an account."),
                answer.headers()[EXISTING_ACCOUNT_HEADER]?.trim()?.ifBlank { null }
            )
        }
        throw refused
    }

    suspend fun stats(): DashboardStats = api.dashboardStats()

    suspend fun addressReference(): AddressReferenceDto =
        cachedAddressReference ?: api.addressReference().also { cachedAddressReference = it }

    @Volatile
    private var cachedAddressReference: AddressReferenceDto? = null

    suspend fun users(): List<UserDto> = api.users(pageSize = 100).items

    suspend fun updateUserQuestionnaireAccess(id: String, canManageQuestionnaire: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageQuestionnaire = canManageQuestionnaire))

    suspend fun updateUserCraftAccess(id: String, canManageCrafts: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageCrafts = canManageCrafts))

    suspend fun updateUserWorkshopAccess(id: String, canManageWorkshops: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageWorkshops = canManageWorkshops))

    suspend fun updateUserReviewAccess(id: String, canReview: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canReview = canReview))

    suspend fun updateUserProvenanceAccess(id: String, canViewProvenance: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canViewProvenance = canViewProvenance))

    suspend fun updateUserDatasetAccess(id: String, canDownloadDataset: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canDownloadDataset = canDownloadDataset))

    suspend fun updateUserRole(id: String, role: String): UserDto =
        api.updateUser(id, UserUpdateRequest(role = role))

    suspend fun userDirectory(): List<UserDto> = api.userDirectory()
    suspend fun dataAccessTiers(): List<DataAccessTierInfo> = api.dataAccessTiers()
    suspend fun dataAccessGrants(): MyGrantsDto = api.dataAccessGrants()
    suspend fun requestDataAccess(ownerId: String, tier: String, note: String?): DataAccessGrantDto =
        api.requestDataAccess(DataAccessRequestBody(ownerId = ownerId, tier = tier, allData = true, requestNote = note?.ifBlank { null }))
    suspend fun grantDataAccess(granteeId: String, tier: String, allData: Boolean, scopeItems: List<DataAccessScopeItemDto>): DataAccessGrantDto =
        api.grantDataAccess(DataAccessGrantBody(granteeId = granteeId, tier = tier, allData = allData, scopeItems = scopeItems))
    suspend fun decideDataAccess(id: String, status: String, tier: String?): DataAccessGrantDto =
        api.decideDataAccess(id, DataAccessDecisionBody(status = status, tier = tier))
    suspend fun revokeDataAccess(id: String): DataAccessGrantDto = api.revokeDataAccess(id)
    suspend fun deleteDataAccess(id: String) = api.deleteDataAccess(id)
    suspend fun entryComments(recordType: String, recordId: String): List<EntryCommentDto> =
        api.entryComments(recordType, recordId)
    suspend fun addEntryComment(recordType: String, recordId: String, body: String): EntryCommentDto =
        api.addEntryComment(EntryCommentBody(recordType = recordType, recordId = recordId, body = body))

    suspend fun deleteEntryComment(commentId: String) = api.deleteEntryComment(commentId)

    suspend fun updateEntryComment(commentId: String, body: String): EntryCommentDto =
        api.updateEntryComment(commentId, EntryCommentUpdateBody(body = body))
    suspend fun recordRevisions(recordType: String, recordId: String): List<RecordRevisionDto> =
        api.recordRevisions(recordType, recordId)

    suspend fun workshopAssignments(workshopId: String): List<WorkshopAssignmentDto> =
        api.workshopAssignments(workshopId)

    suspend fun setWorkshopAssignments(workshopId: String, userIds: List<String>, accessLevel: String? = null): List<WorkshopAssignmentDto> =
        api.setWorkshopAssignments(workshopId, WorkshopAssignmentBody(userIds, accessLevel))

    suspend fun grantWorkshopAccess(workshopId: String, userId: String, accessLevel: String, note: String? = null): WorkshopAssignmentDto =
        api.grantWorkshopAssignment(workshopId, WorkshopGrantBody(userId = userId, accessLevel = accessLevel, note = note?.blankToNull()))

    suspend fun updateWorkshopAccess(workshopId: String, userId: String, accessLevel: String? = null, status: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.updateWorkshopAssignment(
            workshopId,
            userId,
            WorkshopAssignmentUpdateBody(accessLevel = accessLevel, status = status, note = note?.blankToNull())
        )

    suspend fun revokeWorkshopAccess(workshopId: String, userId: String): WorkshopAssignmentDto =
        api.revokeWorkshopAssignment(workshopId, userId)

    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto> = api.workshopAccessLevels()

    suspend fun requestWorkshopAccess(workshopIds: List<String>, accessLevel: String?, note: String?): WorkshopAccessRequestResultDto =
        api.requestWorkshopAccess(
            WorkshopAccessRequestBody(
                workshopIds = workshopIds,
                accessLevel = accessLevel?.blankToNull(),
                note = note?.blankToNull()
            )
        )

    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto> = api.myWorkshopAccess()

    suspend fun workshopAccessQueue(statusFilter: String = "PENDING"): List<WorkshopAssignmentDto> =
        api.workshopAccessRequests(statusFilter)

    suspend fun decideWorkshopAccess(requestId: String, status: String, accessLevel: String? = null, note: String? = null): WorkshopAssignmentDto =
        api.decideWorkshopAccess(
            requestId,
            WorkshopAccessDecisionBody(status = status, accessLevel = accessLevel, note = note?.blankToNull())
        )

    suspend fun taskCounts(view: String = "assigned", statuses: List<String>): Map<String, Int> =
        statuses.associateWith { status ->
            api.tasks(view = view, status = status, pageSize = 1, withDerived = false).total
        }

    suspend fun tasks(
        view: String = "assigned",
        status: String? = null,
        workshopId: String? = null,
        assigneeId: String? = null,
        batchId: String? = null,
        pageSize: Int = 100
    ): List<TaskDto> =
        api.tasks(
            view = view,
            status = status?.blankToNull(),
            workshopId = workshopId?.blankToNull(),
            pageSize = pageSize,
            assigneeId = assigneeId?.blankToNull(),
            batchId = batchId?.blankToNull()
        ).items

    suspend fun task(taskId: String): TaskDto = api.task(taskId)

    suspend fun updateTaskProgress(taskId: String, status: String? = null, progressCount: Int? = null): TaskDto =
        api.updateTask(taskId, TaskUpdateBody(status = status, progressCount = progressCount))

    suspend fun taskOptions(workshopId: String? = null, search: String? = null): TaskOptionsDto =
        api.taskOptions(workshopId?.blankToNull(), search?.trim()?.take(120)?.blankToNull())

    suspend fun createTaskBatch(
        assigneeIds: List<String>,
        workshopId: String? = null,
        recordTypes: List<String> = emptyList(),
        artisanIds: List<String> = emptyList(),
        sectionIds: List<String> = emptyList(),
        targetCount: Int? = null,
        title: String? = null,
        description: String? = null,
        dueAt: String? = null
    ): TaskBatchResultDto =
        api.createTaskBatch(
            TaskBatchCreateBody(
                assigneeIds = assigneeIds,
                workshopId = workshopId?.blankToNull(),
                recordTypes = recordTypes,
                artisanIds = artisanIds,
                sectionIds = sectionIds,
                targetCount = targetCount,
                title = title?.blankToNull(),
                description = description?.blankToNull(),
                dueAt = dueAt?.blankToNull()
            )
        )

    suspend fun taskBatches(
        workshopId: String? = null,
        view: String = "all",
        batchId: String? = null,
        assigneeId: String? = null,
        status: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): PageResponse<TaskBatchDto> =
        api.taskBatches(
            view = view,
            workshopId = workshopId?.blankToNull(),
            batchId = batchId?.blankToNull(),
            assigneeId = assigneeId?.blankToNull(),
            status = status?.blankToNull(),
            page = page,
            pageSize = pageSize
        )

    suspend fun taskProgress(
        workshopId: String? = null,
        assigneeId: String? = null,
        includeFinished: Boolean = true
    ): TaskProgressReportDto =
        api.taskProgress(
            workshopId = workshopId?.blankToNull(),
            assigneeId = assigneeId?.blankToNull(),
            includeFinished = includeFinished
        )

    suspend fun deleteTaskBatch(batchId: String) = api.deleteTaskBatch(batchId)

    suspend fun deleteTask(taskId: String) = api.deleteTask(taskId)

    suspend fun managedSecrets(): List<ManagedSecretDto> = api.managedSecrets()

    suspend fun revealSecret(key: String): ManagedSecretRevealDto = api.revealSecret(key)

    suspend fun aiProviders(): AiCatalogueDto = api.aiProviders()

    suspend fun myAiKeys(): List<UserAiKeyDto> = api.myAiKeys()

    suspend fun setMyAiKey(provider: String, key: String?, model: String?): UserAiKeyDto =
        api.setMyAiKey(provider, UserAiKeySetBody(key = key?.takeIf { it.isNotBlank() }, model = model))

    suspend fun deleteMyAiKey(provider: String): UserAiKeyDto = api.deleteMyAiKey(provider)

    suspend fun testMyAiKey(provider: String): UserAiKeyDto = api.testMyAiKey(provider)

    suspend fun setSecret(key: String, value: String): ManagedSecretDto =
        api.setSecret(key, ManagedSecretSetBody(value = value.trim()))

    suspend fun clearSecret(key: String): ManagedSecretDto = api.clearSecret(key)

    suspend fun testSecret(key: String): ManagedSecretDto = api.testSecret(key)

    suspend fun myPreferences(): PreferencesDto? = api.myPreferences().takeIf { it.exists }

    suspend fun savePreferences(
        theme: String = "system",
        reducedMotion: Boolean = false,
        largerText: Boolean = false,
        highContrast: Boolean = false
    ): PreferencesDto =
        api.updateMyPreferences(
            PreferencesUpdateBody(
                theme = theme,
                reducedMotion = reducedMotion,
                largerText = largerText,
                highContrast = highContrast
            )
        )

    suspend fun savePreferences(preferences: PreferencesDto): PreferencesDto =
        savePreferences(
            theme = preferences.theme,
            reducedMotion = preferences.reducedMotion,
            largerText = preferences.largerText,
            highContrast = preferences.highContrast
        )

    suspend fun search(
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        page: Int = 1,
        pageSize: Int = 10
    ): SearchResultsDto =
        api.search(
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types
                ?.mapNotNull { it.trim().lowercase().blankToNull() }
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(","),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            page = page,
            pageSize = pageSize.coerceIn(1, 50)
        )

    suspend fun mapPoints(
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        level: String? = null,
        focusType: String? = null,
        focusId: String? = null
    ): MapPointsDto =
        api.mapPoints(
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types?.map { it.lowercase() }.toQueryCsv(),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            level = level?.blankToNull(),
            focusType = focusType?.blankToNull(),
            focusId = focusId?.blankToNull()
        )

    suspend fun mapPointRecords(
        key: String,
        q: String? = null,
        craftId: String? = null,
        place: String? = null,
        artisanId: String? = null,
        mediaType: String? = null,
        types: List<String>? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        workshopIds: List<String>? = null,
        level: String? = null
    ): MapPointRecordsDto =
        api.mapPointRecords(
            key = key,
            q = q?.blankToNull(),
            craftId = craftId?.blankToNull(),
            place = place?.blankToNull(),
            artisanId = artisanId?.blankToNull(),
            mediaType = mediaType?.blankToNull(),
            types = types?.map { it.lowercase() }.toQueryCsv(),
            dateFrom = dateFrom?.blankToNull(),
            dateTo = dateTo?.blankToNull(),
            workshopIds = workshopIds.toQueryCsv(),
            level = level?.blankToNull()
        )

    suspend fun dataTree(path: String = ""): DataTreeDto = api.dataTree(path)

    suspend fun locateRecord(recordType: String, recordId: String): String? {
        val body = api.dataLocate(recordType, recordId) as? JsonObject ?: return null
        return (body["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    suspend fun dataManifest(path: String = "", include: String? = null): DataManifestDto =
        api.dataManifest(path, include?.blankToNull())

    suspend fun pendingReviews(pageSize: Int? = null, before: String? = null): PendingReviewListDto =
        api.pendingReviews(pageSize = pageSize, before = before)

    suspend fun approveRecord(recordType: String, recordId: String) {
        api.approveRecord(recordType, recordId, ReviewActionRequest())
    }

    suspend fun rejectRecord(recordType: String, recordId: String) {
        api.rejectRecord(recordType, recordId, ReviewActionRequest())
    }

    suspend fun reviseRecord(recordType: String, recordId: String, notes: String) {
        api.reviseRecord(recordType, recordId, ReviewActionRequest(notes = notes))
    }

    suspend fun editReviewedRecord(recordType: String, recordId: String, fields: Map<String, String>, note: String?) {
        api.editReviewedRecord(
            recordType,
            recordId,
            ReviewEditRequest(fields = fields, note = note?.blankToNull())
        )
    }

    fun installedVersionCode(context: Context): Int {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pkg.versionCode
    }

    suspend fun publishAppUpdate(context: Context): AppReleaseDto {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = installedVersionCode(context)
        val versionName = pkg.versionName ?: versionCode.toString()
        val apk = File(context.applicationInfo.sourceDir)
        val size = apk.length()
        val mime = "application/vnd.android.package-archive"
        val presign = api.presignMedia(
            MediaPresignRequest(
                filename = "design-workshop-v$versionCode.apk",
                mimeType = mime,
                mediaType = "DOCUMENT",
                sizeBytes = size
            )
        )
        withContext(Dispatchers.IO) {
            putToStorage(presign.uploadUrl, presign.headers, size, mime, { FileInputStream(apk) }, null)
        }
        return api.publishAppRelease(
            AppReleasePublishRequest(
                versionCode = versionCode,
                versionName = versionName,
                objectKey = presign.objectKey,
                url = presign.publicUrl,
                sizeBytes = size
            )
        )
    }

    suspend fun latestAppRelease(): AppReleaseDto = api.latestAppRelease()

    suspend fun myFeedback(): FeedbackDto = api.myFeedback()

    suspend fun upsertMyFeedback(request: FeedbackUpsertRequest): FeedbackDto =
        api.upsertMyFeedback(request)

    suspend fun saveMyFeedback(context: Context, request: FeedbackUpsertRequest): FeedbackDto? {
        try {
            return api.upsertMyFeedback(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is HttpException || !isTransient(e)) throw e
            OfflineOutbox.enqueue(
                context,
                PendingEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    type = OFFLINE_APP_FEEDBACK,
                    payloadJson = offlineJson.encodeToString(request),
                    label = "Your rating of the app",
                    createdAt = Instant.now().toString(),
                    ownerUserId = cachedUser()?.id,
                )
            )
            return null
        }
    }

    suspend fun allFeedback(): List<FeedbackDto> = api.allFeedback()

    suspend fun feedbackVocabulary(): FeedbackVocabularyDto = api.feedbackVocabulary()

    suspend fun createFeedbackReport(request: FeedbackReportCreateRequest): FeedbackReportDto =
        api.createFeedbackReport(request)

    suspend fun fileFeedbackReportOrQueue(context: Context, request: FeedbackReportCreateRequest): FeedbackReportDto? {
        val key = java.util.UUID.randomUUID().toString()
        if (isOnline(context)) {
            try {
                return api.createFeedbackReport(request, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isTransient(e)) throw e
            }
        }
        OfflineOutbox.enqueue(
            context,
            PendingEntry(
                id = java.util.UUID.randomUUID().toString(),
                type = OFFLINE_FEEDBACK_REPORT,
                payloadJson = offlineJson.encodeToString(request),
                label = request.subject.ifBlank { "Report" },
                createdAt = Instant.now().toString(),
                ownerUserId = cachedUser()?.id,
                idempotencyKey = key,
            )
        )
        return null
    }

    suspend fun feedbackVocabularyCached(context: Context): CachedRead<FeedbackVocabularyDto> =
        ReadCache.read(context, "feedback-vocabulary", FeedbackVocabularyDto.serializer()) { api.feedbackVocabulary() }

    suspend fun myFeedbackReports(page: Int = 1, pageSize: Int = 25): FeedbackReportPageDto =
        api.myFeedbackReports(page, pageSize)

    suspend fun appSettings(): AppSettingDto = api.appSettings()

    suspend fun updateAppSettings(request: AppSettingUpdateRequest): AppSettingDto =
        api.updateAppSettings(request)

    suspend fun mediaJobs(statusFilter: String? = null, pageSize: Int = 20): List<MediaProcessingJobDto> =
        api.mediaJobs(statusFilter = statusFilter, pageSize = pageSize).items

    suspend fun mediaJobsWithFailedCount(failedOnly: Boolean, pageSize: Int = 20): Pair<List<MediaProcessingJobDto>, Int> {
        if (failedOnly) {
            val page = api.mediaJobs(statusFilter = "FAILED", pageSize = pageSize)
            return page.items to page.total
        }
        return mediaJobs(statusFilter = null, pageSize = pageSize) to mediaJobCount("FAILED")
    }

    suspend fun mediaJobCount(statusFilter: String): Int =
        api.mediaJobs(statusFilter = statusFilter, pageSize = 1).total

    suspend fun retryMediaJob(jobId: String): MediaProcessingJobDto = api.retryMediaJob(jobId)

    suspend fun processMediaJobs(limit: Int? = null): MediaQueueRunDto = api.processMediaJobs(limit)

    suspend fun orphanedMedia(): List<MediaFileDto> = api.orphanMedia()

    suspend fun relinkMedia(mediaId: String, linkedRecordType: String, linkedRecordId: String): MediaFileDto =
        api.relinkMedia(mediaId, MediaRelinkRequest(linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId))

    suspend fun refineTranscript(mediaId: String, translate: Boolean): TranscriptRefineResponse =
        api.refineTranscript(mediaId, TranscriptRefineRequest(translate = translate))

    suspend fun applyTranscript(mediaId: String, text: String): MediaFileDto =
        api.setTranscript(mediaId, TranscriptUpdateRequest(text = text))

    suspend fun transcribeNow(mediaId: String): MediaFileDto = api.transcribeNow(mediaId)

    suspend fun transcriptInsight(mediaId: String): TranscriptInsightViewDto? = insightLoader.load(mediaId)

    suspend fun saveTranscriptInsight(mediaId: String, body: TranscriptInsightPatchRequest): TranscriptInsightViewDto =
        api.patchTranscriptInsight(mediaId, body)

    suspend fun regenerateTranscriptInsight(mediaId: String, discardEdits: Boolean): TranscriptInsightViewDto =
        api.regenerateTranscriptInsight(mediaId, TranscriptInsightRegenerateRequest(discardEdits))

    val transcriptInsightActions: TranscriptInsightActions
        get() = TranscriptInsightActions(save = ::saveTranscriptInsight, regenerate = ::regenerateTranscriptInsight)

    private val insightLoader = CoalescedLoader(
        scope = loaderScope,
        maxBatch = TRANSCRIPT_INSIGHTS_BATCH_MAX,
        fetchBatch = ::transcriptInsightsWithTranslation,
        fetchOne = { id -> insightOrHidden { api.transcriptInsight(id) } }
    )

    private suspend fun transcriptInsightsWithTranslation(ids: List<String>): Map<String, TranscriptInsightViewDto>? {
        val batch = try {
            api.transcriptInsights(ids.joinToString(","), includeTranslation = true)
        } catch (_: HttpException) {
            return emptyMap()
        } catch (_: IOException) {
            return emptyMap()
        }
        return if (batch.translationIncluded) batch.items else null
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        versionCode: Int,
        expectedBytes: Long? = null,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(dir, "design-workshop-v$versionCode.apk")
        val request = Request.Builder().url(url).get().build()
        storageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("The update could not be downloaded. Try again.")
            }
            val body = response.body
                ?: throw IllegalStateException("The update could not be downloaded. Try again.")
            body.byteStream().use { input -> FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) } }
        }
        dwRequireWholeApk(out, expectedBytes)
    }

    suspend fun artisans(
        workshopIds: List<String>? = null,
        designWorkshopId: String? = null,
        createdBy: String? = null,
    ): List<ArtisanDto> =
        api.artisans(
            pageSize = 100,
            workshopIds = workshopIds.toQueryCsv(),
            designWorkshopId = designWorkshopId?.blankToNull(),
            createdBy = createdBy?.blankToNull(),
        ).items

    internal suspend fun interviewArtisans(scope: InterviewArtisanScope): List<ArtisanDto> =
        fetchInterviewArtisans(api, scope)

    suspend fun artisanPage(search: String?, completeness: String? = null, page: Int = 1) = api.artisans(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)
    suspend fun craftPage(search: String?, page: Int = 1) = api.crafts(page = page, pageSize = 500, search = search?.blankToNull())
    suspend fun productPage(search: String?, completeness: String? = null, page: Int = 1) = api.products(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)
    suspend fun toolPage(search: String?, completeness: String? = null, page: Int = 1) = api.tools(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)
    suspend fun processPage(search: String?, page: Int = 1) = api.processes(page = page, pageSize = 100, search = search?.blankToNull())
    suspend fun workshopPage(search: String?, completeness: String? = null, page: Int = 1) = api.workshops(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)
    suspend fun interviewPage(search: String?, completeness: String? = null, page: Int = 1) = api.interviews(page = page, pageSize = 100, search = search?.blankToNull(), completeness = completeness)
    suspend fun completenessRules(context: android.content.Context): CompletenessRules =
        RecordCompletenessStore.refresh(context) { api.completenessRules().toString() }

    suspend fun mediaPage(search: String?, filters: Map<String, String> = emptyMap(), page: Int = 1) =
        api.media(page = page, pageSize = 100, search = search?.blankToNull(), filters = filters)

    suspend fun crafts(createdBy: String? = null): List<CraftDto> {
        val catalogue = CraftCatalogueCache.catalogue.value
        if (createdBy.isNullOrBlank() && catalogue != null) return catalogue.crafts.map(::catalogueCraftDto)
        return readAllListPages { page -> api.crafts(page = page, pageSize = 500, createdBy = createdBy?.blankToNull()) }
    }

    suspend fun searchCrafts(query: String, page: Int = 1, pageSize: Int = 10): PageResponse<CraftDto> =
        api.crafts(page = page, pageSize = pageSize, search = query.trim())

    suspend fun craftCatalogue(context: Context): CraftCatalogue? =
        CraftCatalogueCache.refresh(context) { etag ->
            val response = api.craftCatalogue(ifNoneMatch = etag)
            when {
                response.code() == 304 -> CraftCatalogueCache.Fetch.NotModified
                response.isSuccessful -> CraftCatalogueCache.Fetch.Fresh(
                    body = response.body()?.string().orEmpty(),
                    etag = response.headers()["ETag"],
                )
                else -> throw HttpException(response)
            }
        }

    suspend fun refreshCraftCatalogueIfStale(context: Context) {
        if (CraftCatalogueCache.isStale(context)) runCatching { craftCatalogue(context) }
    }

    suspend fun fullCraftCatalogue(): CraftCatalogue? {
        val response = api.craftCatalogue(includeInactive = true)
        if (!response.isSuccessful) throw HttpException(response)
        return CraftCatalogueCache.parse(response.body()?.string().orEmpty())
    }

    suspend fun products(createdBy: String? = null): List<ProductDetailDto> =
        api.products(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    suspend fun productsForArtisan(artisanId: String, artisanName: String? = null): List<ProductDetailDto> =
        api.products(pageSize = 100, artisanId = artisanId, artisanName = artisanName?.trim()?.ifBlank { null }).items

    suspend fun tools(createdBy: String? = null): List<ToolDetailDto> =
        api.tools(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    suspend fun toolArtisans(toolId: String): List<ArtisanDto> = api.toolArtisans(toolId)

    suspend fun assignToolArtisans(toolId: String, artisanIds: List<String>): List<ArtisanDto> =
        api.assignToolArtisans(toolId, ToolArtisanAssignRequest(artisanIds))

    suspend fun unassignToolArtisan(toolId: String, artisanId: String) = api.unassignToolArtisan(toolId, artisanId)

    suspend fun workshops(createdBy: String? = null, accessibleOnly: Boolean = false): List<WorkshopDetailDto> =
        api.workshops(
            pageSize = 100,
            createdBy = createdBy?.blankToNull(),
            accessibleOnly = true.takeIf { accessibleOnly }
        ).items

    suspend fun workshopsByOccurrence(): List<WorkshopDetailDto> =
        workshops().sortedByDescending { it.occurrenceDate() }

    suspend fun workshopsIMaySubmitTo(): List<WorkshopDetailDto> =
        workshops(accessibleOnly = true).sortedByDescending { it.occurrenceDate() }

    suspend fun workshopTypes(): List<WorkshopTypeOptionDto> = api.workshopTypes()

    suspend fun workshopsIMaySubmitToPage(): PageResponse<WorkshopDetailDto> =
        readEveryPage { page -> api.workshops(page = page, pageSize = 100, accessibleOnly = true) }

    suspend fun workshopSubmissionCheck(workshopId: String): WorkshopSubmissionCheckDto? =
        runCatching { api.workshopSubmissionCheck(workshopId) }.getOrNull()

    suspend fun unmappedRecords(): WorkshopMappingPlanDto = api.unmappedRecords()

    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto = api.mapUnmappedRecords()

    suspend fun fileOneUnmappedRecord(bucket: String, recordId: String, workshopId: String): WorkshopMappingPlanDto =
        api.fileOneUnmappedRecord(bucket, recordId, FileUnmappedRecordBody(workshopId = workshopId))

    suspend fun discardUnmappedRecord(bucket: String, recordId: String): DiscardUnmappedRecordDto =
        api.discardUnmappedRecord(bucket, recordId)

    suspend fun createArtisan(body: ArtisanCreateRequest, idempotencyKey: String? = null): ArtisanDto =
        api.createArtisan(withIdentityAnswer(body), idempotencyKey)

    suspend fun artisan(id: String): ArtisanDetailDto = try {
        api.artisan(id)
    } catch (e: HttpException) {
        val survivor = RecordOpen.mergedSurvivorId(e.code(), peekErrorBody(e))
        if (survivor == null || survivor == id) throw e
        api.artisan(survivor)
    }

    suspend fun artisanCard(id: String): ArtisanCardDto = api.artisanCard(id)

    suspend fun artisanConsent(id: String): ArtisanConsentDto = api.artisanConsent(id)

    suspend fun recordArtisanConsent(id: String, granted: Boolean): ArtisanConsentDto =
        api.recordArtisanConsent(id, ArtisanConsentRequest(state = if (granted) "GRANTED" else "REFUSED"))

    suspend fun artisanCardsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("artisan_cards", Context.MODE_PRIVATE)
        return try {
            val enabled = api.artisanCardStatus().enabled
            prefs.edit().putBoolean("enabled", enabled).apply()
            enabled
        } catch (error: IOException) {
            prefs.getBoolean("enabled", false)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is HttpException && error.code() == 401) prefs.getBoolean("enabled", false) else false
        }
    }

    suspend fun reissueArtisanCard(id: String, reason: String): ArtisanCardDto =
        api.reissueArtisanCard(id, ArtisanCardReissueRequest(reason))

    suspend fun resolveArtisanCard(input: String, register: List<ArtisanDto>): CardResolution {
        val parsed = when (val result = ArtisanCardCode.parse(input)) {
            is ArtisanCardCode.Result.Ok -> result.card
            is ArtisanCardCode.Result.Refused -> return CardResolution.Refused(ArtisanCardCode.CHECK_MESSAGE)
        }
        matchCardInRegister(parsed, register)?.let { row ->
            return CardResolution.Found(row.id, row.name, row.place.ifBlank { null })
        }
        return try {
            val answer = api.resolveArtisanCard(ResolveCardRequest(input.trim()))
            when (answer.status) {
                "FOUND" -> answer.artisan?.let {
                    CardResolution.Found(it.id, it.name, listOfNotNull(it.craft, it.cluster ?: it.place).joinToString(" · ").ifBlank { null })
                } ?: CardResolution.Refused(CARD_NO_MATCH)
                "REPLACED" -> CardResolution.Replaced(answer.artisanId.orEmpty())
                "REMOTE" -> CardResolution.Remote
                else -> CardResolution.Refused(CARD_NO_MATCH)
            }
        } catch (error: HttpException) {
            if ((isTransient(error) && error.code() != 429) || error.code() == 401) CardResolution.Pending(parsed)
            else CardResolution.Refused(error.serverDetail() ?: CARD_NO_MATCH)
        } catch (error: IOException) {
            CardResolution.Pending(parsed)
        }
    }

    suspend fun lookupArtisanByAadhaar(number: String): AadhaarLookupDto =
        api.lookupArtisanByAadhaar(AadhaarLookupRequest(number.trim()))

    suspend fun checkDuplicates(body: DuplicateCheckRequest): List<DuplicateCandidateDto> =
        api.checkDuplicates(body).candidates

    suspend fun updateArtisan(
        id: String,
        body: ArtisanCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
        shape: (JsonObject) -> JsonObject = { it },
    ): ArtisanDetailDto = api.updateArtisan(id, shape(artisanPatchBody(body, clearedLinks)))

    private fun <T> patchBodyWithClearances(
        serializer: KSerializer<T>,
        body: T,
        clearable: Set<String>,
    ): JsonObject {
        val encoded = ApiClient.json.encodeToJsonElement(serializer, body).jsonObject
        val declared = declaredKeys(serializer)
        val out = encoded.toMutableMap()
        for (column in clearable) if (column in declared && column !in out) out[column] = JsonNull
        return JsonObject(out)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun declaredKeys(serializer: KSerializer<*>): Set<String> =
        (0 until serializer.descriptor.elementsCount).mapTo(mutableSetOf()) {
            serializer.descriptor.getElementName(it)
        }

    private fun artisanPatchBody(
        body: ArtisanCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
    ): JsonObject = patchBodyWithClearances(
        ArtisanCreateRequest.serializer(),
        body,
        ARTISAN_CLEARABLE_COLUMNS.toSet() + clearedLinks,
    )

    suspend fun artisanQuestionnaire(id: String): ArtisanQuestionnaireDto = api.artisanQuestionnaire(id)

    suspend fun media(): List<MediaFileDto> = api.media(pageSize = 20).items

    suspend fun mediaList(
        uploadedBy: String? = null,
        search: String? = null,
        mediaType: String? = null,
        statusFilter: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
    ): List<MediaFileDto> = api.media(
        pageSize = 100,
        uploadedBy = uploadedBy?.blankToNull(),
        search = search?.blankToNull(),
        mediaType = mediaType?.blankToNull(),
        statusFilter = statusFilter?.blankToNull(),
        dateFrom = dateFrom?.blankToNull(),
        dateTo = dateTo?.blankToNull(),
    ).items

    suspend fun mediaItem(id: String): MediaFileDto = api.getMedia(id)

    suspend fun mediaItemOrNull(id: String): MediaFileDto? = mediaRowLoader.load(id)

    private var mediaBatchUnsupported = false

    private val mediaRowLoader = CoalescedLoader(
        scope = loaderScope,
        maxBatch = MEDIA_BATCH_MAX,
        fetchBatch = ::mediaRowsTogether,
        fetchOne = { id -> api.getMedia(id) }
    )

    private suspend fun mediaRowsTogether(ids: List<String>): Map<String, MediaFileDto>? {
        if (mediaBatchUnsupported || ids.size == 1) return null
        return try {
            api.getMediaBatch(ids.joinToString(",")).items.associateBy { it.id }
        } catch (error: HttpException) {
            if (error.code() == 404 || error.code() == 405) mediaBatchUnsupported = true
            null
        }
    }

    suspend fun refreshMediaUrl(context: Context, mediaId: String?, failedUrl: String?): String? =
        MediaUrlRefresh.refreshOnce(
            mediaId = mediaId,
            failedUrl = failedUrl,
            httpStatus = null,
            online = ConnectivityObserver.isOnline(context),
        ) { id -> mediaRowLoader.load(id)?.url }

    suspend fun deleteMedia(id: String) = api.deleteMedia(id)

    suspend fun deleteArtisan(id: String) = api.deleteArtisan(id)
    suspend fun deleteCraft(id: String) = api.deleteCraft(id)
    suspend fun deleteProduct(id: String) = api.deleteProduct(id)
    suspend fun deleteTool(id: String) = api.deleteTool(id)
    suspend fun deleteWorkshop(id: String) = api.deleteWorkshop(id)
    suspend fun deleteProcess(id: String) = api.deleteProcess(id)
    suspend fun deleteInterview(id: String) = api.deleteInterview(id)

    data class DatasetDownloadResult(
        val displayLocation: String,
        val saved: Int,
        val total: Int,
        val failed: Int,
        val truncated: Boolean = false,
        val skippedMedia: Int = 0
    )

    private suspend fun <T> readManifest(
        streamed: suspend () -> retrofit2.Response<okhttp3.ResponseBody>,
        buffered: suspend () -> ManifestFallback<T>,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        spool: File,
        onEntry: suspend (entry: T, announcedTotal: Int) -> Unit
    ): ManifestOutcome {
        val response = streamed()
        val body = response.body()
        val ndjson = response.isSuccessful && body != null &&
            ManifestStream.isNdjson(response.headers()["Content-Type"])
        if (!ndjson) {
            runCatching { body?.close() }
            val fallback = buffered()
            for (entry in fallback.files) onEntry(entry, fallback.total)
            return ManifestOutcome(
                total = fallback.total,
                truncated = fallback.truncated,
                unreadable = 0,
                skippedMedia = fallback.skippedMedia
            )
        }
        val total = ManifestStream.count(response.headers()[ManifestStream.TOTAL_HEADER])
        val truncated = ManifestStream.flag(response.headers()[ManifestStream.TRUNCATED_HEADER])
        val skipped = maxOf(0, ManifestStream.count(response.headers()[ManifestStream.SKIPPED_HEADER]))
        try {
            body!!.byteStream().use { input ->
                FileOutputStream(spool).use { out -> input.copyTo(out) }
            }
            var unreadable = 0
            spool.reader(Charsets.UTF_8).use { reader ->
                val lines = ManifestLines(reader, ApiClient.json, deserializer)
                for (entry in lines.entries()) onEntry(entry, total)
                unreadable = lines.unreadable
            }
            return ManifestOutcome(
                total = total,
                truncated = truncated,
                unreadable = unreadable,
                skippedMedia = skipped
            )
        } finally {
            spool.delete()
        }
    }

    private data class ManifestFallback<T>(
        val files: List<T>,
        val total: Int,
        val truncated: Boolean,
        val skippedMedia: Int = 0
    )

    private fun manifestTotal(outcome: ManifestOutcome, seen: Int): Int =
        if (outcome.total >= 0) maxOf(outcome.total, seen) else seen + outcome.unreadable

    suspend fun downloadDataset(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val zipName = Naming.exportFilename("Dataset", "Export", "zip", withTime = true, subject = "All Records")
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        var written = 0
        val outcome = ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            readManifest(
                streamed = { api.datasetManifestStream() },
                buffered = {
                    val manifest = api.datasetManifest()
                    ManifestFallback(
                        files = manifest.files,
                        total = manifest.files.size,
                        truncated = manifest.truncated,
                        skippedMedia = manifest.skippedMedia
                    )
                },
                deserializer = DatasetFileDto.serializer(),
                spool = File(context.cacheDir, "$zipName.manifest")
            ) { f, announced ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.url != null -> writeObject(f.url, zip)
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                written++
                onProgress(written, if (announced >= 0) announced else written)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        val total = manifestTotal(outcome, written)
        DatasetDownloadResult(
            displayLocation = location,
            saved = written - failed,
            total = total,
            failed = total - (written - failed),
            truncated = outcome.truncated,
            skippedMedia = outcome.skippedMedia
        )
    }

    suspend fun downloadReport(context: Context, path: String = ""): String = withContext(Dispatchers.IO) {
        val response = api.dataReport(format = "xlsx", path = path)
        if (!response.isSuccessful) throw IllegalStateException(
            errorBodyDetail(response.errorBody()?.string()) ?: "The report could not be downloaded. Try again."
        )
        val body = response.body() ?: throw IllegalStateException("The report could not be downloaded. Try again.")
        val name = Naming.exportFilename(
            "Dataset", "Report", "xlsx", withTime = true,
            subject = path.substringAfterLast('/').blankToNull() ?: "All Records",
        )
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val location = persistFileToDownloads(context, tmp, name, XLSX_MIME)
        tmp.delete()
        location
    }

    suspend fun downloadDataFolder(
        context: Context,
        path: String,
        include: String? = null,
        folderName: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val zipName = Naming.exportFilename(
            "Dataset", "Export", "zip", withTime = true,
            subject = (folderName ?: path.substringAfterLast('/')).blankToNull() ?: "All Records",
        )
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        var written = 0
        val outcome = ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            readManifest(
                streamed = { api.dataManifestStream(path, include?.blankToNull()) },
                buffered = {
                    val manifest = api.dataManifest(path, include?.blankToNull())
                    ManifestFallback(
                        files = manifest.files,
                        total = manifest.files.size,
                        truncated = manifest.truncated
                    )
                },
                deserializer = DataManifestFileDto.serializer(),
                spool = File(context.cacheDir, "$zipName.manifest")
            ) { f, announced ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.convertToMp4 && f.mediaId != null ->
                            if (!writeConvertedMedia(f.mediaId, zip)) writeObject(f.url, zip)
                        else -> writeObject(f.url, zip)
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                written++
                onProgress(written, if (announced >= 0) announced else written)
            }
        }
        val location = persistFileToDownloads(context, tmp, zipName, "application/zip")
        tmp.delete()
        val total = manifestTotal(outcome, written)
        DatasetDownloadResult(
            displayLocation = location,
            saved = written - failed,
            total = total,
            failed = total - (written - failed),
            truncated = outcome.truncated
        )
    }

    private suspend fun writeConvertedMedia(mediaId: String, sink: java.io.OutputStream): Boolean =
        runCatching {
            val response = api.downloadDataMedia(mediaId, "mp4")
            val body = response.body()
            if (!response.isSuccessful || body == null) return@runCatching false
            body.byteStream().use { it.copyTo(sink) }
            true
        }.getOrDefault(false)

    private suspend fun writeObject(url: String?, sink: java.io.OutputStream) {
        if (url.isNullOrBlank()) return
        if (url.startsWith("/api/")) {
            val response = api.apiPath(url)
            val body = response.body()
            if (!response.isSuccessful || body == null) throw IllegalStateException("The file could not be downloaded. Try again.")
            body.byteStream().use { it.copyTo(sink) }
            return
        }
        storageClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.byteStream()?.copyTo(sink)
        }
    }

    suspend fun downloadDataMedia(
        context: Context,
        mediaId: String,
        filename: String,
        format: String? = null
    ): String = withContext(Dispatchers.IO) {
        val response = api.downloadDataMedia(mediaId, format?.blankToNull())
        if (!response.isSuccessful) throw IllegalStateException("The file could not be downloaded. Try again.")
        val body = response.body() ?: throw IllegalStateException("The file could not be downloaded. Try again.")
        val name = filename.blankToNull()?.replace(Regex("[^A-Za-z0-9._-]+"), "_") ?: mediaId
        val tmp = File(context.cacheDir, name)
        body.byteStream().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
        val mime = response.headers()["Content-Type"]?.substringBefore(';')?.trim().blankToNull()
            ?: "application/octet-stream"
        val location = persistFileToDownloads(context, tmp, name, mime)
        tmp.delete()
        location
    }

    internal fun persistFileToDownloads(context: Context, source: File, name: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("The file could not be saved to Downloads.")
            resolver.openOutputStream(uri).use { out -> source.inputStream().use { it.copyTo(out!!) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val storedName = runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) null
                        else cursor.getString(0)?.takeIf { it.isNotBlank() }
                    }
            }.getOrNull() ?: name
            return "Downloads/$storedName"
        }
        val hasLegacyWrite = context.checkSelfPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasLegacyWrite) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            val dest = File(downloads, name)
            source.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
        val fallback = File(context.filesDir, "downloads").apply { mkdirs() }
        val dest = File(fallback, name)
        source.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    fun shareUriForSavedFile(context: Context, absolutePath: String): Uri? {
        val file = File(absolutePath)
        if (file.absolutePath.startsWith(context.filesDir.absolutePath)) {
            return runCatching {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }
        return mediaStoreDownloadUri(context, absolutePath)
    }

    private fun mediaStoreDownloadUri(context: Context, location: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = location.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name),
                "${MediaStore.Downloads._ID} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }.getOrNull()
    }

    suspend fun mediaForRecord(linkedRecordType: String, linkedRecordId: String): List<MediaFileDto> =
        api.media(pageSize = 100, linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId).items

    suspend fun processes(createdBy: String? = null): List<ProcessDetailDto> =
        api.processes(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    suspend fun process(id: String): ProcessDetailDto = api.process(id)

    suspend fun createProcess(body: ProcessCreateRequest, idempotencyKey: String? = null): ProcessDetailDto =
        api.createProcess(body, idempotencyKey)

    suspend fun updateProcess(
        id: String,
        body: ProcessCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
        shape: (JsonObject) -> JsonObject = { it },
    ): ProcessDetailDto =
        api.updateProcess(id, shape(patchBodyWithClearances(ProcessCreateRequest.serializer(), body, clearedLinks)))

    suspend fun createCraft(body: CraftCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createCraft(body, idempotencyKey)

    suspend fun craft(id: String): CraftDto = api.craft(id)

    suspend fun updateCraft(
        id: String,
        body: CraftCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
        shape: (JsonObject) -> JsonObject = { it },
    ): CraftDto =
        api.updateCraft(id, shape(patchBodyWithClearances(CraftCreateRequest.serializer(), body, clearedLinks)))

    suspend fun createWorkshop(body: WorkshopCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createWorkshop(body, idempotencyKey)

    suspend fun workshop(id: String): WorkshopDetailDto = api.workshop(id)

    suspend fun updateWorkshop(
        id: String,
        body: WorkshopCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
        shape: (JsonObject) -> JsonObject = { it },
    ): WorkshopDetailDto =
        api.updateWorkshop(id, shape(patchBodyWithClearances(WorkshopCreateRequest.serializer(), body, clearedLinks)))

    suspend fun createProduct(body: ProductCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createProduct(body, idempotencyKey)

    suspend fun product(id: String): ProductDetailDto = api.product(id)

    suspend fun updateProduct(
        id: String,
        body: ProductCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
        shape: (JsonObject) -> JsonObject = { it },
    ): ProductDetailDto =
        api.updateProduct(id, shape(patchBodyWithClearances(ProductCreateRequest.serializer(), body, clearedLinks)))

    suspend fun createTool(body: ToolCreateRequest, idempotencyKey: String? = null): CreatedRecordDto =
        api.createTool(body, idempotencyKey)

    suspend fun tool(id: String): ToolDetailDto = api.tool(id)

    suspend fun updateTool(
        id: String,
        body: ToolCreateRequest,
        clearedLinks: Set<String> = WORKSHOP_LINK_KEYS,
        shape: (JsonObject) -> JsonObject = { it },
    ): ToolDetailDto =
        api.updateTool(id, shape(patchBodyWithClearances(ToolCreateRequest.serializer(), body, clearedLinks)))

    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto> = api.questionnaireQuestions()

    suspend fun questionnaireSections(): List<QuestionnaireSectionDto> = api.questionnaireSections()

    suspend fun createQuestionnaireSection(body: QuestionnaireSectionCreateRequest): QuestionnaireSectionDto =
        api.createQuestionnaireSection(body)

    suspend fun updateQuestionnaireSection(id: String, body: QuestionnaireSectionUpdateRequest): QuestionnaireSectionDto =
        api.updateQuestionnaireSection(id, body)

    suspend fun deleteQuestionnaireSection(id: String) {
        api.deleteQuestionnaireSection(id)
    }

    suspend fun reorderQuestionnaireSections(sectionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireSections(QuestionnaireSectionReorderRequest(sectionIds))

    suspend fun createQuestionnaireQuestion(body: QuestionnaireQuestionCreateRequest): QuestionnaireQuestionDto =
        api.createQuestionnaireQuestion(body)

    suspend fun updateQuestionnaireQuestion(id: String, body: QuestionnaireQuestionUpdateRequest): QuestionnaireQuestionDto =
        api.updateQuestionnaireQuestion(id, body)

    suspend fun deleteQuestionnaireQuestion(id: String) {
        api.deleteQuestionnaireQuestion(id)
    }

    suspend fun reorderQuestionnaireQuestions(sectionId: String, questionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireQuestions(QuestionnaireQuestionReorderRequest(sectionId, questionIds))

    suspend fun createQuestionnaireInterview(
        body: QuestionnaireInterviewCreateRequest,
        idempotencyKey: String? = null,
    ): CreatedRecordDto = api.createQuestionnaireInterview(body, idempotencyKey)

    suspend fun interviews(createdBy: String? = null): List<QuestionnaireInterviewDetailDto> =
        api.interviews(pageSize = 100, createdBy = createdBy?.blankToNull()).items

    suspend fun unifiedSearch(query: String, limit: Int = 5): UnifiedSearchResponse =
        api.unifiedSearch(q = query.trim(), limit = limit)

    suspend fun unifiedSearchIn(query: String, limit: Int, scope: String, type: String? = null, page: Int? = null): UnifiedSearchResponse =
        api.unifiedSearch(q = query.trim(), limit = limit, scope = scope, type = type, page = page)

    suspend fun remoteRecord(type: String, id: String): RemoteRecord = api.remoteRecord(type, id)

    suspend fun searchCrafts(query: String, pageSize: Int = 10): List<CraftDto> {
        val catalogue = CraftCatalogueCache.catalogue.value
        if (catalogue != null) return CraftCatalogueCache.search(catalogue, query).take(pageSize).map(::catalogueCraftDto)
        return api.crafts(pageSize = pageSize, search = query.trim()).items
    }

    suspend fun searchProcesses(query: String, pageSize: Int = 10): List<ProcessDetailDto> =
        api.processes(pageSize = pageSize, search = query.trim()).items

    suspend fun searchInterviews(query: String, pageSize: Int = 10): List<QuestionnaireInterviewDetailDto> =
        api.interviews(pageSize = pageSize, search = query.trim()).items


    suspend fun interview(id: String): QuestionnaireInterviewDetailDto = api.interview(id)

    suspend fun updateQuestionnaireInterview(id: String, body: QuestionnaireInterviewUpdateRequest): QuestionnaireInterviewDetailDto =
        api.updateInterview(id, body)

    suspend fun mergeQuestionnaireInterview(id: String, targetId: String): QuestionnaireInterviewDetailDto =
        api.mergeInterviewInto(id, targetId)

    suspend fun completionMatrix(
        artisanId: String? = null,
        workshopIds: List<String>? = null
    ): CompletionMatrixDto =
        api.completionMatrix(artisanId?.blankToNull(), workshopIds.toQueryCsv())

    suspend fun setCompletionCell(artisanId: String, sectionId: String, status: String?) =
        api.setCompletionCell(CompletionCellRequest(artisanId, sectionId, status))

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String? = null,
        batchIndex: Int = 1,
        processingRequests: List<String>? = null,
        stageStep: Int? = null,
        customSegment: String? = null,
        overrideBaseName: String? = null,
        designWorkshopId: String? = null,
        extraMetadata: JsonObject? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): MediaFileDto {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val mediaType = inferMediaType(mimeType)
        return uploadResolved(
            context = context,
            uri = uri,
            mimeType = mimeType,
            mediaType = mediaType,
            originalName = originalName,
            linkedRecordType = linkedRecordType,
            linkedRecordId = linkedRecordId,
            caption = caption,
            location = location,
            titleHint = titleHint,
            batchIndex = batchIndex,
            processingRequests = processingRequests,
            stageStep = stageStep,
            customSegment = customSegment,
            overrideBaseName = overrideBaseName,
            extraMetadata = extraMetadata,
            designWorkshopId = designWorkshopId,
            onProgress = onProgress
        )
    }

    private suspend fun uploadResolved(
        context: Context,
        uri: Uri,
        mimeType: String,
        mediaType: String,
        originalName: String,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String?,
        batchIndex: Int,
        processingRequests: List<String>?,
        stageStep: Int?,
        customSegment: String?,
        overrideBaseName: String? = null,
        extraMetadata: JsonObject? = null,
        designWorkshopId: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): MediaFileDto {
        val resolvedProcessing = processingRequests
            ?: TranscribeChoices.processingFor(uri.toString(), mediaType)
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = titleHint,
            mediaType = mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            caption = caption,
            overrideBaseName = overrideBaseName,
            originalName = originalName
        )
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val target = uploadBytesToS3(
                context = context,
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                source = source,
                linkedRecordType = linkedRecordType,
                linkedRecordId = linkedRecordId,
                onProgress = onProgress
            )
            val media = completeUpload(
                MediaCompleteRequest(
                    originalFilename = filename,
                    mediaType = mediaType,
                    mimeType = mimeType,
                    sizeBytes = source.size,
                    objectKey = target.objectKey,
                    bucket = target.bucket,
                    url = target.publicUrl,
                    caption = caption.blankToNull(),
                    linkedRecordType = linkedRecordType.blankToNull(),
                    linkedRecordId = linkedRecordId.blankToNull(),
                    designWorkshopId = designWorkshopId.blankToNull(),
                    recordedAt = Instant.now().toString(),
                    location = location,
                    processingRequests = resolvedProcessing,
                    extraMetadata = extraMetadata
                ),
                target.checksum
            )
            StagedJournal.drop(target.objectKey)
            return media
        } finally {
            source.cleanup()
        }
    }

    private suspend fun completeUpload(body: MediaCompleteRequest, checksum: String?): MediaFileDto {
        if (checksum == null) return api.completeMedia(body)
        val encoded = completeJson.encodeToJsonElement(MediaCompleteRequest.serializer(), body).jsonObject
        return api.completeMediaChecksummed(JsonObject(encoded + ("checksum" to JsonPrimitive(checksum))))
    }

    suspend fun preuploadObject(
        context: Context,
        uri: Uri,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): StagedMedia {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val mediaType = inferMediaType(mimeType)
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val provisional = "staged-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}" +
                (extension?.let { ".$it" } ?: "")
            val target = uploadBytesToS3(
                context = context,
                filename = provisional,
                mimeType = mimeType,
                mediaType = mediaType,
                source = source,
                linkedRecordType = null,
                linkedRecordId = null,
                onProgress = onProgress
            )
            StagedJournal.record(context, target.objectKey, target.checksum)
            return StagedMedia(
                objectKey = target.objectKey,
                bucket = target.bucket,
                publicUrl = target.publicUrl,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = source.size,
                extension = extension
            )
        } finally {
            source.cleanup()
        }
    }

    private data class UploadTarget(
        val objectKey: String,
        val bucket: String,
        val publicUrl: String?,
        val checksum: String?
    )

    private suspend fun uploadBytesToS3(
        context: Context,
        filename: String,
        mimeType: String,
        mediaType: String,
        source: UploadSource,
        linkedRecordType: String?,
        linkedRecordId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): UploadTarget {
        if (source.size <= MULTIPART_THRESHOLD) {
            suspend fun mint(): MediaPresignResponse {
                val minted = api.presignMedia(
                    MediaPresignRequest(
                        filename = filename,
                        mimeType = mimeType,
                        mediaType = mediaType,
                        sizeBytes = source.size,
                        linkedRecordType = linkedRecordType.blankToNull(),
                        linkedRecordId = linkedRecordId.blankToNull()
                    )
                )
                StagedJournal.record(context, minted.objectKey)
                return minted
            }

            var presign = mint()
            val digest = ContentDigest()
            withContext(Dispatchers.IO) {
                putToStorage(
                    uploadUrl = presign.uploadUrl,
                    headers = presign.headers,
                    contentLength = source.size,
                    mimeType = mimeType,
                    openStream = source.open,
                    onProgress = onProgress,
                    digest = digest,
                    repesign = {
                        try {
                            mint().also { presign = it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            null
                        }
                    },
                )
            }
            return UploadTarget(presign.objectKey, presign.bucket, presign.publicUrl, digest.hex())
        }
        return uploadMultipart(context, filename, mimeType, mediaType, source, linkedRecordType, linkedRecordId, onProgress)
    }

    private suspend fun uploadMultipart(
        context: Context,
        filename: String,
        mimeType: String,
        mediaType: String,
        source: UploadSource,
        linkedRecordType: String?,
        linkedRecordId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): UploadTarget {
        val create = api.createMultipart(
            MultipartCreateRequest(
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = source.size,
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull()
            )
        )
        StagedJournal.record(context, create.objectKey, uploadId = create.uploadId)
        try {
            val partUrls = api.presignMultipartParts(
                MultipartPresignPartsRequest(
                    objectKey = create.objectKey,
                    uploadId = create.uploadId,
                    partNumbers = (1..create.partCount).toList()
                )
            ).urls
            val completed = ArrayList<CompletedPart>(create.partCount)
            val digest = ContentDigest()
            withContext(Dispatchers.IO) {
                source.open().use { input ->
                    var sentTotal = 0L
                    for (partNumber in 1..create.partCount) {
                        val thisSize = minOf(create.partSize, source.size - (partNumber - 1).toLong() * create.partSize)
                        val bytes = readExactly(input, thisSize.toInt())
                        digest.update(bytes, bytes.size)
                        val url = partUrls[partNumber.toString()]
                            ?: throw IllegalStateException("The upload could not be started. Try again.")
                        val base = sentTotal
                        val etag = putPart(
                            url = url,
                            bytes = bytes,
                            onProgress = { sent -> onProgress?.invoke(base + sent, source.size) },
                            repesign = {
                                api.presignMultipartParts(
                                    MultipartPresignPartsRequest(
                                        objectKey = create.objectKey,
                                        uploadId = create.uploadId,
                                        partNumbers = listOf(partNumber)
                                    )
                                ).urls[partNumber.toString()]
                            }
                        )
                        completed.add(CompletedPart(partNumber = partNumber, etag = etag))
                        sentTotal += bytes.size.toLong()
                    }
                }
            }
            val done = api.completeMultipart(
                MultipartCompleteRequest(
                    objectKey = create.objectKey,
                    uploadId = create.uploadId,
                    parts = completed
                )
            )
            return UploadTarget(done.objectKey, done.bucket, done.publicUrl, digest.hex())
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    api.abortMultipart(MultipartAbortRequest(create.objectKey, create.uploadId))
                    StagedJournal.drop(create.objectKey)
                }
            }
            throw t
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

    private suspend fun putPart(
        url: String,
        bytes: ByteArray,
        onProgress: ((sent: Long) -> Unit)?,
        repesign: suspend () -> String?
    ): String {
        val maxAttempts = 3
        var target = url
        var refreshed = false
        var failures = 0
        var lastError: Exception? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            var expired = false
            try {
                val body = StreamingRequestBody(
                    bytes.size.toLong(),
                    null,
                    { java.io.ByteArrayInputStream(bytes) },
                    onProgress?.let { cb -> { sent, _ -> cb(sent) } }
                )
                executeCancellable(storageClient.newCall(Request.Builder().url(target).put(body).build())).use { response ->
                    if (response.isSuccessful) {
                        return response.header("ETag")
                            ?: throw IllegalStateException("The upload could not be confirmed. Try again.")
                    }
                    if (response.code == 403 && !refreshed) expired = true
                    else if (response.code < 500) throw StorageRefusedError(
                        response.code,
                        "The upload was not accepted."
                    )
                    lastError = StorageRefusedError(response.code, "The upload was not accepted.")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (expired) {
                refreshed = true
                target = repesign() ?: throw (lastError ?: IllegalStateException("The upload was not accepted."))
                continue
            }
            failures++
            if (failures >= maxAttempts) break
            delay(800L * failures)
        }
        throw lastError ?: IllegalStateException("The upload did not finish. Try again.")
    }

    suspend fun completeStaged(
        staged: StagedMedia,
        linkedRecordType: String?,
        linkedRecordId: String?,
        recordName: String?,
        caption: String?,
        location: LocationRequest?,
        batchIndex: Int = 1,
        stageStep: Int? = null,
        customSegment: String? = null,
        processingRequests: List<String>? = null,
        overrideBaseName: String? = null,
        extraMetadata: JsonObject? = null
    ): MediaFileDto {
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = recordName,
            mediaType = staged.mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            caption = caption,
            overrideBaseName = overrideBaseName,
            originalName = "media" + (staged.extension?.let { ".$it" } ?: "")
        )
        val resolvedProcessing = processingRequests ?: emptyList()
        val media = completeUpload(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = staged.mediaType,
                mimeType = staged.mimeType,
                sizeBytes = staged.sizeBytes,
                objectKey = staged.objectKey,
                bucket = staged.bucket,
                url = staged.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull(),
                recordedAt = Instant.now().toString(),
                location = location,
                processingRequests = resolvedProcessing,
                extraMetadata = extraMetadata
            ),
            StagedJournal.checksumFor(staged.objectKey)
        )
        StagedJournal.drop(staged.objectKey)
        return media
    }

    suspend fun deleteStaged(objectKey: String) {
        api.deleteMediaObject(objectKey)
        StagedJournal.drop(objectKey)
    }

    suspend fun sweepStagedObjects(context: Context): Int {
        if (!hasToken() || !ConnectivityObserver.isOnline(context)) return 0
        return StagedJournal.sweep(context) { objectKey, uploadId ->
            val abortSettled = uploadId == null || try {
                api.abortMultipart(MultipartAbortRequest(objectKey, uploadId))
                true
            } catch (e: HttpException) {
                true
            } catch (e: IOException) {
                false
            }
            val deleteSettled = try {
                api.deleteMediaObject(objectKey)
                true
            } catch (e: HttpException) {
                e.code() == 409 || e.code() == 403 || e.code() == 404
            } catch (e: IOException) {
                false
            }
            abortSettled && deleteSettled
        }
    }

    fun isOnline(context: Context): Boolean = ConnectivityObserver.isOnline(context)

    suspend fun pendingUploads(context: Context): Int =
        OfflineOutbox.count(context, cachedUser()?.id)

    suspend fun outboxCounts(context: Context): OutboxCounts =
        OfflineOutbox.counts(context, cachedUser()?.id)

    suspend fun resolvePossibleDuplicate(
        context: Context,
        entryId: String,
        samePersonId: String?,
    ): OutboxRetryResult {
        if (samePersonId != null) {
            OfflineOutbox.attachToExisting(context, entryId, samePersonId)
        } else {
            OfflineOutbox.acknowledgeDuplicates(context, entryId)
        }
        return retryOutboxEntry(context, entryId)
    }

    suspend fun retryOutboxEntry(context: Context, entryId: String): OutboxRetryResult {
        val online = isOnline(context)
        OfflineOutbox.clearFailure(context, entryId, cachedUser()?.id)
        val moved = syncOutbox(context)
        val stillQueued = OfflineOutbox.all(context).any { it.id == entryId }
        return OutboxRetryResult(
            requestedSent = !stillQueued,
            refusedSent = if (stillQueued) 0 else 1,
            refusedTried = 1,
            othersSent = (moved - if (stillQueued) 0 else 1).coerceAtLeast(0),
            attempted = online,
        )
    }

    suspend fun retryAllOutboxFailures(context: Context): OutboxRetryResult {
        val online = isOnline(context)
        val signedIn = cachedUser()?.id
        val wereRefused = outboxRetryableFailures(OfflineOutbox.failed(context), signedIn)
            .map { it.id }
            .toSet()
        OfflineOutbox.clearAllFailures(context, signedIn)
        val moved = syncOutbox(context)
        val remaining = OfflineOutbox.all(context).map { it.id }.toSet()
        val refusedThatWent = wereRefused.count { it !in remaining }
        return OutboxRetryResult(
            requestedSent = refusedThatWent > 0,
            refusedSent = refusedThatWent,
            refusedTried = wereRefused.size,
            othersSent = (moved - refusedThatWent).coerceAtLeast(0),
            attempted = online,
        )
    }

    suspend fun discardOutboxEntry(context: Context, entryId: String): Boolean =
        OfflineOutbox.discard(context, entryId)

    suspend fun sendWithoutConflictingAnswers(context: Context, entryId: String): OutboxRetryResult {
        val online = isOnline(context)
        if (!OfflineOutbox.dropConflictingAnswers(context, entryId)) {
            return OutboxRetryResult(false, 0, 1, 0, attempted = false)
        }
        val moved = syncOutbox(context)
        val stillQueued = OfflineOutbox.all(context).any { it.id == entryId }
        return OutboxRetryResult(
            requestedSent = !stillQueued,
            refusedSent = if (stillQueued) 0 else 1,
            refusedTried = 1,
            othersSent = (moved - if (stillQueued) 0 else 1).coerceAtLeast(0),
            attempted = online,
        )
    }

    suspend fun repickOutboxEntry(
        context: Context,
        entryId: String,
        field: String,
        value: String?,
    ): OutboxRetryResult {
        val online = isOnline(context)
        val rewritten = OfflineOutbox.repick(context, entryId, field, value)
        if (!rewritten) return OutboxRetryResult(false, 0, 1, 0, attempted = false)
        val moved = syncOutbox(context)
        val stillQueued = OfflineOutbox.all(context).any { it.id == entryId }
        return OutboxRetryResult(
            requestedSent = !stillQueued,
            refusedSent = if (stillQueued) 0 else 1,
            refusedTried = 1,
            othersSent = (moved - if (stillQueued) 0 else 1).coerceAtLeast(0),
            attempted = online,
        )
    }

    suspend fun retypeOutboxAadhaar(context: Context, entryId: String, typed: String): OutboxRetryResult {
        val number = ArtisanIdentity.normalizeAadhaar(typed)
        require(ArtisanIdentity.isAadhaar(number)) {
            ArtisanIdentity.aadhaarError(number) ?: "Enter the 12-digit Aadhaar number."
        }
        check(AadhaarAtRest.canSeal()) {
            "This phone can't keep the number safely right now. Save the artisan again from its form while online."
        }
        val online = isOnline(context)
        if (!OfflineOutbox.retypeAadhaar(context, entryId, number)) {
            return OutboxRetryResult(false, 0, 1, 0, attempted = false)
        }
        val moved = syncOutbox(context)
        val stillQueued = OfflineOutbox.all(context).any { it.id == entryId }
        return OutboxRetryResult(
            requestedSent = !stillQueued,
            refusedSent = if (stillQueued) 0 else 1,
            refusedTried = 1,
            othersSent = (moved - if (stillQueued) 0 else 1).coerceAtLeast(0),
            attempted = online,
        )
    }

    suspend fun repickOptions(field: String): RepickChoices = when (field) {
        "designWorkshopId" -> runCatching {
            RepickChoices(
                designWorkshops(page = 1, pageSize = REPICK_PAGE).items.map { workshop ->
                    RepickOption(
                        id = workshop.id,
                        label = workshopDisplayTitle(
                            workshop.title,
                            date = workshop.startDate,
                            workshopCode = workshop.workshopCode,
                            clusterName = workshop.clusterName,
                            district = workshop.district,
                            state = workshop.state,
                            craftName = workshop.craftName,
                        ),
                        hint = listOfNotNull(
                            workshop.craftName?.takeIf { it.isNotBlank() },
                            workshop.clusterName?.takeIf { it.isNotBlank() }
                                ?: workshop.state?.takeIf { it.isNotBlank() },
                            workshop.startDate?.take(10)?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").takeIf { it.isNotBlank() },
                    )
                },
                listed = true,
            )
        }.getOrDefault(RepickChoices(emptyList(), listed = false))

        "workshopId" -> runCatching {
            RepickChoices(
                workshopsIMaySubmitTo().map { workshop ->
                    RepickOption(
                        id = workshop.id,
                        label = workshopDisplayTitle(workshop.title, date = workshop.occurrenceDate(), place = workshop.place),
                        hint = workshop.place.takeIf { it.isNotBlank() },
                    )
                },
                listed = true,
            )
        }.getOrDefault(RepickChoices(emptyList(), listed = false))

        else -> RepickChoices(emptyList(), listed = true)
    }

    suspend fun queueOffline(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        mediaUris: List<Uri>,
        recordName: String?,
        caption: String?,
        purposes: Map<Uri, String> = emptyMap(),
        targetId: String? = null,
        unfiled: Map<String, String> = emptyMap(),
        idempotencyKey: String? = null,
        correctionBase: CorrectionBase? = null,
    ): OfflineQueueResult = queueOfflineEntry(
        context, type, payloadJson, label,
        mediaUris.mapIndexed { index, uri ->
            OfflineMediaSpec(
                uri = uri,
                caption = caption,
                recordName = recordName,
                batchIndex = index + 1,
                purpose = purposes[uri],
            )
        },
        targetId = targetId,
        unfiled = unfiled,
        idempotencyKey = idempotencyKey,
        correctionBase = correctionBase,
    )

    suspend fun queueMediaForSavedRecord(
        context: Context,
        recordType: String,
        recordId: String,
        label: String,
        items: List<OfflineMediaSpec>,
    ): OfflineQueueResult = queueOfflineEntry(
        context = context,
        type = OFFLINE_MEDIA_ONLY,
        payloadJson = "",
        label = label,
        items = items.map { it.copy(linkedType = it.linkedType ?: recordType) },
        targetId = recordId,
    )

    suspend fun queueLooseMedia(
        context: Context,
        linkedRecordType: String,
        linkedRecordId: String,
        designWorkshopId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String?,
        items: List<Pair<Int, Uri>>,
    ): OfflineQueueResult = queueOfflineEntry(
        context = context,
        type = OFFLINE_MEDIA_ONLY,
        payloadJson = "",
        label = titleHint?.takeIf { it.isNotBlank() } ?: "Media upload",
        items = items.map { (batchIndex, uri) ->
            OfflineMediaSpec(
                uri = uri,
                caption = caption?.takeIf { it.isNotBlank() },
                recordName = titleHint?.takeIf { it.isNotBlank() },
                batchIndex = batchIndex,
                linkedType = linkedRecordType,
                designWorkshopId = designWorkshopId?.takeIf { it.isNotBlank() },
                location = location,
            )
        },
        targetId = linkedRecordId,
    )

    suspend fun queueOfflineEntry(
        context: Context,
        type: String,
        payloadJson: String,
        label: String,
        items: List<OfflineMediaSpec>,
        targetId: String? = null,
        unfiled: Map<String, String> = emptyMap(),
        idempotencyKey: String? = null,
        correctionBase: CorrectionBase? = null,
    ): OfflineQueueResult = withContext(Dispatchers.IO) {
        val media = mutableListOf<PendingMedia>()
        val unreadable = mutableListOf<String>()
        for ((position, spec) in items.withIndex()) {
            val staged = runCatching {
                OfflineOutbox.stageMedia(
                    context = context,
                    uri = spec.uri,
                    caption = spec.caption,
                    recordName = spec.recordName,
                    customSegment = spec.customSegment,
                    overrideBaseName = spec.overrideBaseName,
                    batchIndex = spec.batchIndex,
                    processing = spec.processing,
                    stageStep = spec.stageStep,
                    linkedType = spec.linkedType,
                    stepIndex = spec.stepIndex,
                    purpose = spec.purpose
                ).copy(designWorkshopId = spec.designWorkshopId, location = spec.location)
            }
            staged.getOrNull()?.let { media.add(it) }
                ?: unreadable.add(spec.uri.lastPathSegment ?: "file ${position + 1}")
        }
        val entryId = java.util.UUID.randomUUID().toString()
        val owner = cachedUser()?.id
        val clientKey = if (idempotencyKey != null) null else clientKeyForNewEntry(type, targetId)
        try {
            OfflineOutbox.enqueue(
                context,
                PendingEntry(
                    id = entryId,
                    type = type,
                    payloadJson = payloadJson,
                    label = label,
                    media = media,
                    createdAt = Instant.now().toString(),
                    targetId = targetId,
                    unfiled = unfiled,
                    ownerUserId = owner,
                    clientKey = clientKey,
                    idempotencyKey = idempotencyKey
                        ?: if (outboxCarriesIdempotencyKey(type, targetId)) java.util.UUID.randomUUID().toString() else null,
                    changedKeys = correctionBase?.changedKeys,
                    baseUpdatedAt = correctionBase?.baseUpdatedAt,
                )
            )
        } catch (e: Throwable) {
            media.forEach { runCatching { File(it.localPath).delete() } }
            throw e
        }
        OfflineQueueResult(entryId = entryId, queuedFiles = media.size, unreadableFiles = unreadable)
    }

    private fun clientKeyForNewEntry(type: String, targetId: String?): String? =
        if (outboxMintsClientKey(type, targetId)) java.util.UUID.randomUUID().toString() else null

    suspend fun syncOutbox(context: Context): Int {
        if (mustChangePasswordBlocks(cachedUser())) return 0
        if (sweptStagedObjects.compareAndSet(false, true)) {
            AppScope.io.launch { runCatching { sweepStagedObjects(context) } }
            AppScope.io.launch { runCatching { OfflineOutbox.reclaimOrphanMedia(context) } }
        }
        AppScope.io.launch { runCatching { refreshDictationAllowance(context) } }
        AppScope.io.launch { runCatching { refreshAiVerbAllowance(context) } }
        AppScope.io.launch { runCatching { syncInspectionNotes(context) } }
        val synced = syncMutex.withLock {
            val queue = OfflineOutbox.all(context)
            OfflineOutbox.takeAlert()?.let { notifyUser(context, it) }
            if (!ConnectivityObserver.isOnline(context)) return@withLock 0
            val signedIn = cachedUser()?.id
            var synced = 0
            for (listed in queue) {
                var queued = listed
                if (dwDraftIsForAnotherAccount(queued.ownerUserId, signedIn)) continue
                if (queued.createdId == null && AadhaarAtRest.numberWithheld(queued.payloadJson)) {
                    if (queued.failure != AadhaarAtRest.RETYPE_REASON) {
                        OfflineOutbox.markFailure(context, queued.id, AadhaarAtRest.RETYPE_REASON)
                        notifyUser(context, "\"${queued.label}\" needs the Aadhaar number again. Open the outbox to type it.")
                    }
                    continue
                }
                if (blocksRetry(queued.failure != null, queued.skewRun)) continue
                if (queued.createdId == null) {
                    val cards = pendingCardsInPayload(queued.payloadJson)
                    if (cards.isNotEmpty()) {
                        when (val linked = resolvePendingCards(cards) { input -> resolveArtisanCard(input, emptyList()) }) {
                            PendingCardOutcome.Offline -> return@withLock synced
                            is PendingCardOutcome.Held -> {
                                if (queued.failure != linked.message) {
                                    OfflineOutbox.markFailure(context, queued.id, linked.message)
                                    notifyUser(context, "\"${queued.label}\" could not be uploaded. ${linked.message}")
                                }
                                continue
                            }
                            is PendingCardOutcome.Resolved -> {
                                queued = queued.copy(payloadJson = substitutePendingCardsInPayload(queued.payloadJson, linked.ids))
                                OfflineOutbox.update(context, queued)
                            }
                        }
                    }
                }
                when (val outcome = replayEntry(context, queued)) {
                    ReplayOutcome.Synced -> {
                        OfflineOutbox.remove(context, queued)
                        synced++
                        val emptyPickers = queued.emptyPickerKeys
                        if (emptyPickers.isNotEmpty()) {
                            notifyUser(
                                context,
                                outboxSentUnfiledMessage(
                                    label = queued.label,
                                    nouns = emptyPickers.mapNotNull { REFERENCE_FIELD_NOUNS[it] },
                                ),
                            )
                        }
                    }
                    is ReplayOutcome.Rejected -> {
                        OfflineOutbox.markFailure(
                            context,
                            queued.id,
                            outcome.reason,
                            skewRun = if (outcome.schemaSkew) APP_RUN else null,
                            conflict = outcome.conflict,
                            danglingField = outcome.danglingField,
                            stale = outcome.stale,
                            currentUpdatedAt = outcome.currentUpdatedAt,
                            duplicateCandidates = outcome.duplicateCandidates,
                            conflictingAnswerIds = outcome.conflictingAnswerIds,
                        )
                        if (queued.failure != outcome.reason) {
                            notifyUser(context, "\"${queued.label}\" could not be uploaded. ${outcome.reason}")
                        }
                    }
                    ReplayOutcome.Retry -> return@withLock synced
                }
            }
            synced
        }

        runCatching {
            com.designprototype.workshop.questionnaire.data.QuestionnaireSync.run(
                context, legacyDrain = {}, serverAnswering = { !ServerReachability.unreachable.value }
            )
        }
        runCatching { WorkshopSyncEngine.syncAll(context, this) }
        return synced
    }

    suspend fun retryDesignWorkshopSync(context: Context, workshopId: String): SyncPassResult =
        WorkshopSyncEngine.retryWorkshop(context, this, workshopId)

    suspend fun syncDesignWorkshops(context: Context): SyncPassResult =
        WorkshopSyncEngine.syncAll(context, this)

    private sealed interface ReplayOutcome {
        data object Synced : ReplayOutcome

        data object Retry : ReplayOutcome

        data class Rejected(
            val reason: String,
            val schemaSkew: Boolean = false,
            val conflict: Boolean = false,
            val danglingField: String? = null,
            val stale: Boolean = false,
            val currentUpdatedAt: String? = null,
            val duplicateCandidates: List<OutboxDuplicateCandidate> = emptyList(),
            val conflictingAnswerIds: List<String> = emptyList(),
        ) : ReplayOutcome
    }

    private suspend fun replayEntry(
        context: Context,
        queued: PendingEntry,
    ): ReplayOutcome {
        appContextForReplay = context.applicationContext
        var entry = queued
        if (entry.createdId == null && entry.idempotencyKey == null &&
            outboxCarriesIdempotencyKey(entry.type, entry.targetId)
        ) {
            entry = entry.copy(idempotencyKey = java.util.UUID.randomUUID().toString())
            OfflineOutbox.update(context, entry)
        }
        if (entry.createdId == null) {
            val created = try {
                writeFromEntry(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isTransient(e)) return ReplayOutcome.Retry
                val recovered = recoverReusedKey(entry, e)
                if (recovered != null) recovered else run {
                val staleAt = if (entry.targetId != null && e is HttpException) {
                    staleCorrectionCurrentVersion(e.code(), peekErrorBody(e))
                } else {
                    null
                }
                if (staleAt != null) {
                    val emptyReview = if (queued.staleRetried) null else emptyStaleReview(context, entry)
                    if (emptyReview != null) {
                        val rebased = entry.copy(
                            baseUpdatedAt = emptyReview.currentUpdatedAt ?: staleAt.ifBlank { null } ?: entry.baseUpdatedAt,
                            staleRetried = true,
                        )
                        OfflineOutbox.update(context, rebased)
                        return replayEntry(context, rebased)
                    }
                    return ReplayOutcome.Rejected(
                        STALE_CORRECTION_SENTENCE,
                        schemaSkew = false,
                        conflict = false,
                        stale = true,
                        currentUpdatedAt = staleAt.ifBlank { null },
                    )
                }
                val owned = if (e is HttpException) ownedAnswersRefusal(e.code(), peekErrorBody(e)) else null
                if (owned != null) {
                    return ReplayOutcome.Rejected(
                        owned.message,
                        schemaSkew = false,
                        conflict = false,
                        conflictingAnswerIds = owned.questionIds,
                    )
                }
                val held = if (entry.targetId == null && e is HttpException) {
                    PossibleDuplicates.heldCandidates(e.code(), peekErrorBody(e))
                } else {
                    null
                }
                if (held != null) {
                    val kept = PossibleDuplicates.forOutbox(held)
                    return ReplayOutcome.Rejected(
                        PossibleDuplicates.traySentence(kept),
                        schemaSkew = false,
                        conflict = false,
                        duplicateCandidates = kept,
                    )
                }
                val clash = isConflictRefusal(e)
                val refusal = e.apiRefusal("This record could not be saved.")
                if (clash) {
                    return ReplayOutcome.Rejected(
                        outboxConflictSentence(
                            said = refusal.message,
                            files = entry.media.size,
                            isCorrection = entry.targetId != null && entry.type != OFFLINE_MEDIA_ONLY,
                        ),
                        schemaSkew = false,
                        conflict = true,
                    )
                }
                val isCorrection = entry.targetId != null && entry.type != OFFLINE_MEDIA_ONLY
                val dangling = if (isMissingReferenceRefusal(e) && !refusal.schemaSkew) {
                    danglingReferenceCandidates(
                        payload = entry.payloadJson,
                        named = refusal.namedFields,
                        isCorrection = isCorrection,
                        namedOnly = (e as? HttpException)?.code() == 422,
                    )
                } else {
                    emptyList()
                }
                if (dangling.isNotEmpty()) {
                    return ReplayOutcome.Rejected(
                        outboxDanglingSentence(
                            said = refusal.message,
                            nouns = dangling.map { key ->
                                referenceFieldNoun(key, recordNoun = outboxRecordNoun(entry.type))
                            },
                            files = entry.media.size,
                            isCorrection = isCorrection,
                        ),
                        schemaSkew = false,
                        conflict = false,
                        danglingField = dangling.joinToString(","),
                    )
                }
                return ReplayOutcome.Rejected(
                    if (refusal.schemaSkew) {
                        skewSentence("This record", refusal.message)
                    } else {
                        refusal.message
                    },
                    schemaSkew = refusal.schemaSkew,
                )
                }
            }
            entry = entry.copy(createdId = created.id, createdStepIds = created.stepIds)
            OfflineOutbox.update(context, entry)
            if (entry.targetId != null && created.updatedAt != null) {
                OfflineOutbox.advanceBase(context, entry.type, entry.targetId!!, created.updatedAt, exceptId = entry.id)
            }
        }

        val alreadyUp = entry.uploadedMedia.toMutableSet()
        val refused = mutableListOf<String>()
        val remaining = entry.media.withIndex().filterNot { (index, _) -> index in alreadyUp }
        val landed = ConcurrentLinkedQueue<FileOutcome>()

        suspend fun persistProgress() {
            var changed = false
            while (true) {
                when (val outcome = landed.poll() ?: break) {
                    is FileOutcome.Uploaded -> alreadyUp.add(outcome.index)
                    is FileOutcome.Refused -> refused.add(outcome.reason)
                }
                changed = true
            }
            if (!changed) return
            entry = entry.copy(uploadedMedia = alreadyUp.sorted())
            OfflineOutbox.update(context, entry)
        }

        var stopped = false
        try {
            for (batch in remaining.chunked(UPLOAD_CONCURRENCY)) {
                try {
                    uploadBatch(context, entry, batch, landed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    stopped = true
                    break
                }
                persistProgress()
            }
        } finally {
            withContext(NonCancellable) { persistProgress() }
        }
        if (stopped) return ReplayOutcome.Retry

        if (refused.isNotEmpty()) {
            return ReplayOutcome.Rejected(
                "Saved, but ${plural(refused.size, "file")} didn't upload: ${refused.distinct().joinToString(" ")} Re-attach them."
            )
        }
        return ReplayOutcome.Synced
    }

    private sealed interface FileOutcome {
        data class Uploaded(val index: Int) : FileOutcome
        data class Refused(val index: Int, val reason: String) : FileOutcome
    }

    private suspend fun uploadBatch(
        context: Context,
        entry: PendingEntry,
        batch: List<IndexedValue<PendingMedia>>,
        landed: ConcurrentLinkedQueue<FileOutcome>
    ): Unit = coroutineScope {
        batch.forEach { (index, pm) ->
            launch(Dispatchers.IO) {
                val target = linkTargetFor(entry, pm)
                if (target == null) {
                    landed.add(
                        FileOutcome.Refused(
                            index,
                            "\"${pm.originalFilename}\" couldn't attach — the record has fewer steps than captured."
                        )
                    )
                    return@launch
                }
                val staged = File(pm.localPath)
                if (!staged.isFile) {
                    landed.add(
                        FileOutcome.Refused(
                            index,
                            "\"${pm.originalFilename}\" is no longer on this phone. Re-attach it from the gallery if you still have it."
                        )
                    )
                    return@launch
                }
                try {
                    uploadLocalFile(context, pm, target.first, target.second, capturedAt = entry.createdAt)
                    landed.add(FileOutcome.Uploaded(index))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isTransient(e)) throw e
                    landed.add(
                        FileOutcome.Refused(index, "\"${pm.originalFilename}\": ${e.apiErrorMessage("it could not be uploaded.")}")
                    )
                }
            }
        }
    }

    private fun linkTargetFor(entry: PendingEntry, pm: PendingMedia): Pair<String, String>? {
        val recordId = entry.createdId ?: return null
        val stepIndex = pm.stepIndex
        if (entry.type == "process" && stepIndex != null) {
            val stepId = entry.createdStepIds.getOrNull(stepIndex) ?: return null
            return "processstep" to stepId
        }
        if (entry.type == OFFLINE_MEDIA_ONLY) {
            return (pm.linkedType ?: return null) to recordId
        }
        return (pm.linkedType ?: entry.type) to recordId
    }

    internal fun isTransient(error: Throwable): Boolean = when (error) {
        is HttpException -> when (val code = error.code()) {
            401 -> true
            408, 429 -> true
            409 -> isIdempotencyInProgress(error)
            else -> code >= 500
        }
        is IOException -> true
        is SerializationException -> false
        is QFormParentMissing -> false
        else -> true
    }

    private suspend fun notifyUser(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun outboxEntries(context: Context): List<PendingEntry> = OfflineOutbox.all(context)

    suspend fun outboxFailures(context: Context): List<PendingEntry> = OfflineOutbox.failed(context)

    suspend fun reviewStaleCorrection(context: Context, entryId: String): StaleCorrectionReview? {
        val entry = OfflineOutbox.all(context).firstOrNull { it.id == entryId } ?: return null
        return reviewCorrection(entry)
    }

    private suspend fun reviewCorrection(entry: PendingEntry): StaleCorrectionReview? {
        val target = entry.targetId ?: return null
        val record: JsonObject = when (entry.type) {
            "artisan" -> offlineJson.encodeToJsonElement(ArtisanDetailDto.serializer(), artisan(target))
            "craft" -> offlineJson.encodeToJsonElement(CraftDto.serializer(), craft(target))
            "product" -> offlineJson.encodeToJsonElement(ProductDetailDto.serializer(), product(target))
            "tool" -> offlineJson.encodeToJsonElement(ToolDetailDto.serializer(), tool(target))
            "workshop" -> offlineJson.encodeToJsonElement(WorkshopDetailDto.serializer(), workshop(target))
            "process" -> offlineJson.encodeToJsonElement(ProcessDetailDto.serializer(), process(target))
            "questionnaire" -> {
                val mine = runCatching {
                    offlineJson.decodeFromString<QuestionnaireInterviewUpdateRequest>(entry.payloadJson)
                }.getOrNull() ?: return null
                val labels = runCatching { questionnaireQuestions() }.getOrDefault(emptyList())
                    .associate { it.id to interviewQuestionLabel(it) }
                return interviewCorrectionReview(mine, interview(target), labels, entry.changedKeys)
            }
            else -> return null
        } as JsonObject
        val mine = runCatching { offlineJson.parseToJsonElement(entry.payloadJson) as JsonObject }.getOrNull()
            ?: return null
        return staleCorrectionReview(mine, record, entry.changedKeys)
    }

    suspend fun keepMyCorrection(context: Context, entryId: String, currentUpdatedAt: String?): Boolean =
        OfflineOutbox.rebaseCorrection(context, entryId, currentUpdatedAt).also { BackgroundSync.requestSoon(context) }

    suspend fun keepTheirVersion(context: Context, entryId: String): Boolean =
        OfflineOutbox.keepServerVersionKeepFiles(context, entryId).also { BackgroundSync.requestSoon(context) }

    private fun withIdentityAnswer(body: ArtisanCreateRequest): ArtisanCreateRequest =
        if (body.pehchanCardAvailable != null) body
        else body.copy(pehchanCardAvailable = body.pehchanCardNumber != null)

    private data class CreatedRecord(
        val id: String,
        val stepIds: List<String> = emptyList(),
        val updatedAt: String? = null,
    )

    private suspend fun recoverReusedKey(entry: PendingEntry, error: Throwable): CreatedRecord? {
        val key = entry.idempotencyKey ?: return null
        if (error !is HttpException || error.code() != 422) return null
        if (peekErrorBody(error)?.contains("IDEMPOTENCY_KEY_REUSED") != true) return null
        val answer = runCatching { api.idempotencyAnswer(key) }.getOrNull() ?: return null
        return createdFromStoredAnswer(answer.body)?.let { (id, steps) -> CreatedRecord(id, steps) }
    }

    private suspend fun emptyStaleReview(context: Context, entry: PendingEntry): StaleCorrectionReview? =
        runCatching { reviewStaleCorrection(context, entry.id)?.takeIf { it.fields.isEmpty() } }.getOrNull()

    private suspend fun writeFromEntry(entry: PendingEntry): CreatedRecord {
        val target = entry.targetId
        if (entry.type == OFFLINE_MEDIA_ONLY) {
            return CreatedRecord(
                target ?: throw IllegalStateException("A media-only outbox entry has no record to attach to")
            )
        }
        if (target == null) return createFromEntry(entry)
        val cleared = entry.clearedLinkKeys
        val narrow: (JsonObject) -> JsonObject = { body ->
            restrictCorrectionBody(body, entry.changedKeys, entry.baseUpdatedAt)
        }
        return when (entry.type) {
            "artisan" -> updateArtisan(
                target,
                offlineJson.decodeFromString<ArtisanCreateRequest>(entry.payloadJson),
                cleared,
                narrow,
            ).let { CreatedRecord(it.id, updatedAt = it.updatedAt) }
            "product" -> updateProduct(target, offlineJson.decodeFromString<ProductCreateRequest>(entry.payloadJson), cleared, narrow)
                .let { CreatedRecord(it.id, updatedAt = it.updatedAt) }
            "tool" -> updateTool(target, offlineJson.decodeFromString<ToolCreateRequest>(entry.payloadJson), cleared, narrow)
                .let { CreatedRecord(it.id, updatedAt = it.updatedAt) }
            "workshop" -> updateWorkshop(target, offlineJson.decodeFromString<WorkshopCreateRequest>(entry.payloadJson), cleared, narrow)
                .let { CreatedRecord(it.id, updatedAt = it.updatedAt) }
            "craft" -> updateCraft(target, offlineJson.decodeFromString<CraftCreateRequest>(entry.payloadJson), cleared, narrow)
                .let { CreatedRecord(it.id, updatedAt = it.updatedAt) }
            "process" -> updateProcess(target, offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson), cleared, narrow)
                .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }, updatedAt = detail.updatedAt) }
            "questionnaire" -> api.updateInterviewJson(
                target,
                interviewCorrectionBody(
                    offlineJson.parseToJsonElement(entry.payloadJson) as JsonObject,
                    entry.changedKeys,
                    entry.baseUpdatedAt,
                    cleared,
                ),
            ).let { CreatedRecord(it.id, updatedAt = it.updatedAt) }
            else -> throw IllegalStateException("Unknown offline correction type: ${entry.type}")
        }
    }

    private suspend fun createFromEntry(entry: PendingEntry): CreatedRecord = when (entry.type) {
        "artisan" -> CreatedRecord(
            api.createArtisan(
                withIdentityAnswer(offlineJson.decodeFromString<ArtisanCreateRequest>(entry.payloadJson)),
                entry.idempotencyKey,
            ).id
        )
        "product" -> CreatedRecord(
            api.createProduct(
                offlineJson.decodeFromString<ProductCreateRequest>(entry.payloadJson)
                    .copy(clientKey = entry.clientKey),
                entry.idempotencyKey,
            ).id
        )
        "tool" -> CreatedRecord(
            api.createTool(
                offlineJson.decodeFromString<ToolCreateRequest>(entry.payloadJson)
                    .copy(clientKey = entry.clientKey),
                entry.idempotencyKey,
            ).id
        )
        "workshop" -> CreatedRecord(
            api.createWorkshop(
                offlineJson.decodeFromString<WorkshopCreateRequest>(entry.payloadJson)
                    .copy(clientKey = entry.clientKey),
                entry.idempotencyKey,
            ).id
        )
        "craft" -> CreatedRecord(
            api.createCraft(offlineJson.decodeFromString<CraftCreateRequest>(entry.payloadJson), entry.idempotencyKey).id
        )
        "questionnaire" -> CreatedRecord(
            api.createQuestionnaireInterview(
                offlineJson.decodeFromString<QuestionnaireInterviewCreateRequest>(entry.payloadJson),
                entry.idempotencyKey,
            ).id
        )
        OFFLINE_CUSTOM_QUESTIONNAIRE -> CreatedRecord(
            api.createCustomQuestionnaire(
                offlineJson.decodeFromString<CustomQuestionnaireCreateBody>(entry.payloadJson),
                entry.idempotencyKey,
            ).id.also { created ->
                entry.tempId?.let { temp -> TempIdMap.put(appContextForReplay ?: return@let, temp, created) }
            }
        )
        OFFLINE_QFORM_OP -> {
            val op = offlineJson.decodeFromString<QFormOp>(entry.payloadJson)
            applyQuestionnaireOp(
                appContextForReplay ?: throw IllegalStateException("No context for a questionnaire step"),
                op,
                entry.idempotencyKey,
            )
            CreatedRecord(op.tempId ?: entry.id)
        }
        "process" -> api.createProcess(
            offlineJson.decodeFromString<ProcessCreateRequest>(entry.payloadJson)
                .copy(clientKey = entry.clientKey),
            entry.idempotencyKey,
        )
            .let { detail -> CreatedRecord(detail.id, detail.steps.map { it.id }) }
        OFFLINE_EXPORT_RECORD -> {
            val queuedExport = offlineJson.decodeFromString<PendingExportRecord>(entry.payloadJson)
            api.recordDesignWorkshopExport(queuedExport.workshopId, queuedExport.body)
            CreatedRecord(queuedExport.workshopId)
        }
        OFFLINE_DESIGN_RATING -> CreatedRecord(
            api.submitDesignRating(
                offlineJson.decodeFromString<DesignRatingBody>(entry.payloadJson)
            ).rating.id
        )
        OFFLINE_FEEDBACK_REPORT -> CreatedRecord(
            api.createFeedbackReport(
                offlineJson.decodeFromString<FeedbackReportCreateRequest>(entry.payloadJson),
                entry.idempotencyKey,
            ).id
        )
        OFFLINE_DESIGNER_PROFILE -> {
            val payload = offlineJson.parseToJsonElement(entry.payloadJson) as JsonObject
            val body = payload["body"] as? JsonObject ?: throw SerializationException("Queued profile has no body")
            val userId = (payload["userId"] as? JsonPrimitive)?.content
            val saved = if (userId == null) api.updateMyDesignerProfile(body) else api.updateDesignerProfile(userId, body)
            CreatedRecord(saved.userId.ifBlank { entry.id })
        }
        OFFLINE_APP_FEEDBACK -> CreatedRecord(
            api.upsertMyFeedback(offlineJson.decodeFromString<FeedbackUpsertRequest>(entry.payloadJson)).id
        )
        else -> throw IllegalStateException("Unknown offline entry type: ${entry.type}")
    }

    suspend fun uploadDesignWorkshopMedia(
        context: Context,
        workshopRemoteId: String,
        file: File,
        originalFilename: String,
        mimeType: String,
        mediaType: String,
        caption: String?,
        recordedAt: String?,
        latitude: Double?,
        longitude: Double?,
        workshopTitle: String?,
        batchIndex: Int = 1,
        transcribe: Boolean? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): MediaFileDto {
        val filename = mediaFilename(
            recordType = DESIGN_WORKSHOP_MEDIA_TAG,
            recordName = workshopTitle,
            mediaType = mediaType,
            index = batchIndex,
            caption = caption,
            originalName = originalFilename
        )
        val source = UploadSource(size = file.length(), open = { FileInputStream(file) }, cleanup = {})
        val target = uploadBytesToS3(
            context = context,
            filename = filename,
            mimeType = mimeType,
            mediaType = mediaType,
            source = source,
            linkedRecordType = DESIGN_WORKSHOP_MEDIA_TAG,
            linkedRecordId = workshopRemoteId,
            onProgress = onProgress
        )
        val media = completeUpload(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = mediaType,
                mimeType = mimeType,
                sizeBytes = file.length(),
                objectKey = target.objectKey,
                bucket = target.bucket,
                url = target.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = DESIGN_WORKSHOP_MEDIA_TAG,
                linkedRecordId = workshopRemoteId,
                recordedAt = recordedAt ?: Instant.now().toString(),
                location = if (latitude != null && longitude != null) {
                    LocationRequest(latitude = latitude, longitude = longitude)
                } else {
                    null
                },
                processingRequests = UploadTranscribe.processingFor(transcribe, mediaType)
            ),
            target.checksum
        )
        StagedJournal.drop(target.objectKey)
        return media
    }

    private suspend fun uploadLocalFile(
        context: Context,
        pm: PendingMedia,
        linkedRecordType: String,
        linkedRecordId: String,
        capturedAt: String? = null,
    ) {
        val file = File(pm.localPath)
        if (!file.isFile) {
            throw IllegalStateException("The stored copy of \"${pm.originalFilename}\" is missing.")
        }
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = pm.recordName,
            mediaType = pm.mediaType,
            index = pm.batchIndex,
            stageStep = pm.stageStep,
            customSegment = pm.customSegment,
            caption = pm.caption,
            overrideBaseName = pm.overrideBaseName,
            originalName = pm.originalFilename
        )
        val source = UploadSource(size = file.length(), open = { FileInputStream(file) }, cleanup = {})
        val target = uploadBytesToS3(
            context = context,
            filename = filename,
            mimeType = pm.mimeType,
            mediaType = pm.mediaType,
            source = source,
            linkedRecordType = linkedRecordType,
            linkedRecordId = linkedRecordId,
            onProgress = null
        )
        completeUpload(
            pendingMediaCompleteRequest(
                pm = pm,
                filename = filename,
                linkedRecordType = linkedRecordType,
                linkedRecordId = linkedRecordId,
                objectKey = target.objectKey,
                bucket = target.bucket,
                publicUrl = target.publicUrl,
                sizeBytes = file.length(),
                recordedAt = capturedAt ?: Instant.now().toString(),
            ),
            target.checksum
        )
        StagedJournal.drop(target.objectKey)
    }

    suspend fun analyzeMeasurement(context: Context, uri: Uri, dimension: String): DwMeasurementReading {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-${dimension}.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, dimension)
        return DwMeasurementReading(
            lengthInches = null,
            breadthInches = null,
            valueInches = response.analysis?.valueInches,
            marker = response.methodMarker,
        )
    }

    suspend fun analyzeMeasurementLengthBreadth(context: Context, uri: Uri): DwMeasurementReading {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-length-breadth.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, null)
        val analysis = response.analysis
        return DwMeasurementReading(
            lengthInches = analysis?.lengthInches,
            breadthInches = analysis?.breadthInches,
            valueInches = null,
            marker = response.methodMarker,
        )
    }

    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private suspend fun putToStorage(
        uploadUrl: String,
        headers: Map<String, String>,
        contentLength: Long,
        mimeType: String,
        openStream: () -> InputStream,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
        digest: ContentDigest? = null,
        repesign: (suspend () -> MediaPresignResponse?)? = null,
    ) {
        val maxAttempts = 3
        var lastError: Exception? = null
        var target = uploadUrl
        var targetHeaders = headers
        var refreshed = false
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var expired = false
            try {
                val body = StreamingRequestBody(contentLength, mimeType.toMediaType(), openStream, onProgress, digest)
                val builder = Request.Builder().url(target).put(body)
                targetHeaders.forEach { (name, value) -> builder.header(name, value) }
                executeCancellable(storageClient.newCall(builder.build())).use { response ->
                    if (response.isSuccessful) return
                    if (response.code == 403 && !refreshed && repesign != null) {
                        expired = true
                        lastError = StorageRefusedError(
                            response.code,
                            "The upload was not accepted."
                        )
                    } else if (response.code < 500) {
                        throw StorageRefusedError(
                            response.code,
                            "The upload was not accepted."
                        )
                    } else {
                        lastError = StorageRefusedError(
                            response.code,
                            "The upload did not finish. Try again."
                        )
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (expired) {
                refreshed = true
                val fresh = repesign?.invoke()
                    ?: throw (lastError ?: StorageRefusedError(403, "The upload was not accepted."))
                target = fresh.uploadUrl
                targetHeaders = fresh.headers
                onProgress?.invoke(0L, contentLength)
                continue
            }
            failures++
            if (failures >= maxAttempts) break
            onProgress?.invoke(0L, contentLength)
            delay(800L * failures)
        }
        throw lastError ?: IllegalStateException("The upload did not finish. Try again.")
    }

    private class UploadSource(val size: Long, val open: () -> InputStream, val cleanup: () -> Unit)

    private fun queryContentSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun resolveUploadSource(context: Context, uri: Uri): UploadSource {
        val size = queryContentSize(context, uri)
        if (size > 0L) {
            return UploadSource(
                size = size,
                open = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected media")
                },
                cleanup = {}
            )
        }
        val temp = File.createTempFile("upload-", ".bin", context.cacheDir)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { out -> input.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("Unable to open selected media")
        }.onFailure { temp.delete(); throw it }
        return UploadSource(
            size = temp.length(),
            open = { FileInputStream(temp) },
            cleanup = { runCatching { temp.delete() } }
        )
    }

    private class StreamingRequestBody(
        private val length: Long,
        private val contentType: MediaType?,
        private val openStream: () -> InputStream,
        private val onProgress: ((sent: Long, total: Long) -> Unit)?,
        private val digest: ContentDigest? = null
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            digest?.reset()
            openStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    digest?.update(buffer, read)
                    sent += read
                    onProgress?.invoke(sent, length)
                }
            }
        }
    }

    private class ContentDigest {
        private val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun reset() = digest.reset()
        fun update(bytes: ByteArray, length: Int) = digest.update(bytes, 0, length)
        fun hex(): String = "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
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


    fun notificationApi(): NotificationApi = object : NotificationApi {
        override suspend fun list(limit: Int): NotificationPageDto = api.notifications(limit)
        override suspend fun markRead(body: NotificationReadBody): NotificationReadResultDto = api.markNotificationsRead(body)
    }

    suspend fun weeklyDigestPreference(): WeeklyDigestPreferenceDto? =
        runCatching { api.weeklyDigestPreference() }.getOrNull()

    suspend fun saveWeeklyDigestPreference(enabled: Boolean): WeeklyDigestPreferenceDto =
        api.saveWeeklyDigestPreference(WeeklyDigestPreferenceBody(enabled))

    suspend fun syncNotifications(context: Context): Int? {
        val userId = tokenStore.getUser()?.id ?: return null
        return NotificationSync.sync(context, notificationApi(), userId)
    }

    suspend fun coverage(query: CoverageQuery): CoverageResponse = api.coverage(query.params())

    suspend fun coverageUnitRecords(
        query: CoverageQuery,
        stateLgd: Int,
        districtLgd: Int?,
        bucket: String? = null,
        cursor: String? = null
    ): CoverageUnitRecords {
        val params = query.params(forUnit = true).toMutableMap()
        bucket?.let { params["bucket"] = it }
        cursor?.let { params["cursor"] = it }
        return api.coverageUnitRecords(stateLgd, districtLgd ?: 0, params)
    }

    suspend fun entitlements(): MinistryEntitlementsDto = api.entitlements()

    fun sessionKey(): String? {
        val token = tokenStore.getToken()?.takeIf { it.isNotBlank() } ?: return null
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

internal fun pendingMediaCompleteRequest(
    pm: PendingMedia,
    filename: String,
    linkedRecordType: String,
    linkedRecordId: String,
    objectKey: String,
    bucket: String?,
    publicUrl: String?,
    sizeBytes: Long,
    recordedAt: String,
): MediaCompleteRequest = MediaCompleteRequest(
    originalFilename = filename,
    mediaType = pm.mediaType,
    mimeType = pm.mimeType,
    sizeBytes = sizeBytes,
    objectKey = objectKey,
    bucket = bucket,
    url = publicUrl,
    caption = pm.caption.blankToNull(),
    linkedRecordType = linkedRecordType.blankToNull(),
    linkedRecordId = linkedRecordId.blankToNull(),
    recordedAt = recordedAt,
    processingRequests = pm.processing ?: UploadTranscribe.legacy(pm.mediaType),
    extraMetadata = mediaPurposeMetadata(pm.purpose),
    designWorkshopId = pm.designWorkshopId.blankToNull(),
    location = pm.location,
)

private fun List<String>?.toQueryCsv(): String? =
    this?.mapNotNull { it.blankToNull() }?.distinct()?.takeIf { it.isNotEmpty() }?.joinToString(",")

private val NAME_KIND_WORD = mapOf(
    "IMAGE" to "Photo",
    "VIDEO" to "Video",
    "AUDIO" to "Recording",
    "PDF" to "Document",
    "DOCUMENT" to "Document"
)

private val NAME_RECORD_TYPE_WORD = mapOf(
    "artisan" to "Artisan",
    "product" to "Product",
    "tool" to "Tool",
    "process" to "Process",
    "processstep" to "Process",
    "workshop" to "Workshop",
    "craft" to "Craft",
    "designworkshop" to "Workshop"
)

private val CAPTION_QUESTION = Regex("""^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b""", RegexOption.IGNORE_CASE)

private val CAPTION_SECTION = Regex("""^section\s+audio:\s*([A-Za-z]{1,3})\b""", RegexOption.IGNORE_CASE)

private val CAPTION_PRE_PROCESS = Regex("""^pre-process\s+media\b""", RegexOption.IGNORE_CASE)

private val CAPTION_RECORD_NAME = Regex(
    """^(?:field media|pre-process media|process stage step \d+|measurement grid image)\s+for\s+(.+)$""",
    RegexOption.IGNORE_CASE
)

private val SEGMENT_STEP = Regex("""^STEP[_-](\d+)""", RegexOption.IGNORE_CASE)

private val CAMEL_BOUNDARY = Regex("""(?<=[a-z0-9])(?=[A-Z])""")

private val NON_LETTERS = Regex("[^a-z]")

private data class QuestionnaireClip(
    val section: String?,
    val answer: Int?,
    val name: String,
    val stamp: String,
    val clip: Int?
)

private fun mediaFilename(
    recordType: String?,
    recordName: String?,
    mediaType: String,
    index: Int,
    stageStep: Int? = null,
    customSegment: String? = null,
    caption: String? = null,
    overrideBaseName: String? = null,
    originalName: String
): String {
    val extension = Naming.splitExtension(originalName.substringAfterLast('/')).second

    val base = overrideBaseName.blankToNull()
    if (base != null && base.startsWith("${NAMING_PORTAL}_")) return Naming.asciiLeaf(base + extension)
    val clip = base?.let(::parseQuestionnaireClip)

    val tag = recordType?.trim()?.lowercase().orEmpty()
    val (what, sequence) = if (clip != null) {
        interviewClipWhat(clip, caption, mediaType, index)
    } else {
        mediaWhat(recordType, mediaType, index, stageStep, customSegment, caption, originalName)
    }
    val supplied = recordName.blankToNull()
        ?: captionRecordName(caption)
        ?: clip?.name
        ?: base
        ?: splitCamel(originalName.substringBeforeLast('.'))
    val subject = trimRedundantTail(supplied, what)
    val isInterview = clip != null || tag == "questionnaire" || tag == "questionnaireinterview"
    return Naming.recordingFilename(
        entity = if (isInterview) "Interview" else NAME_RECORD_TYPE_WORD[tag],
        ext = extension,
        capturedAt = clip?.let { stampInstant(it.stamp) } ?: java.time.Instant.now(),
        craft = if (tag == "craft") subject else null,
        subject = if (tag == "craft") null else subject,
        what = what,
        sequence = sequence,
    )
}

private fun stampInstant(stamp: String): java.time.Instant? = runCatching {
    java.time.LocalDateTime.parse(stamp, java.time.format.DateTimeFormatter.ofPattern("ddMMyyyyHHmm"))
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
}.getOrNull()

private fun twoDigits(index: Int): String = index.coerceAtLeast(1).toString().padStart(2, '0')

private fun mediaWhat(
    recordType: String?,
    mediaType: String,
    index: Int,
    stageStep: Int?,
    customSegment: String?,
    caption: String?,
    originalName: String
): Pair<String, String> {
    val kind = nameKindWord(mediaType)
    val tag = recordType?.trim()?.lowercase().orEmpty()

    if (tag == "questionnaire" || tag == "questionnaireinterview") {
        val (section, answer) = interviewSectionAnswer(caption) ?: return kind to twoDigits(index)
        return kind to (if (answer != null) Naming.questionSequence(section, answer) else Naming.sectionSequence(section))
    }

    gridDescriptor(customSegment, caption, originalName)?.let { return it to twoDigits(index) }

    val step = stageStep ?: SEGMENT_STEP.find(customSegment.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (step != null || tag == "processstep") {
        return "${step?.let { "Step-$it" } ?: "Step"}-$kind" to twoDigits(index)
    }

    val isPreProcess = customSegment?.trim().equals("PRE", ignoreCase = true) ||
        CAPTION_PRE_PROCESS.containsMatchIn(caption.orEmpty().trim())
    if (isPreProcess) return "Pre-Process-$kind" to twoDigits(index)

    return kind to twoDigits(index)
}

private fun gridDescriptor(customSegment: String?, caption: String?, originalName: String): String? {
    val segment = nameLetters(customSegment)
    val file = originalName.substringBeforeLast('.').lowercase()
    val head = nameLetters(caption)
    if (segment.contains("gridlengthbreadth") ||
        file.startsWith("grid-lengthbreadth") ||
        head.startsWith("lengthbreadthgrid")
    ) {
        return "Grid-Measurement-Length-Breadth"
    }
    if (segment.contains("gridheight") || file.startsWith("grid-height") || head.startsWith("heightgrid")) {
        return "Grid-Measurement-Height"
    }
    if (segment.contains("measurementgrid") ||
        file.startsWith("measure-grid") ||
        head.startsWith("measurementgrid")
    ) {
        return "Grid-Measurement"
    }
    return null
}

private fun interviewSectionAnswer(caption: String?): Pair<String, Int?>? {
    val text = caption.orEmpty().trim()
    CAPTION_QUESTION.find(text)?.let { return it.groupValues[1].uppercase() to it.groupValues[2].toIntOrNull() }
    CAPTION_SECTION.find(text)?.let { return it.groupValues[1].uppercase() to null }
    return null
}

private fun interviewClipWhat(
    clip: QuestionnaireClip,
    caption: String?,
    mediaType: String,
    index: Int
): Pair<String, String> {
    val (section, answer) = interviewSectionAnswer(caption) ?: (clip.section to clip.answer)
    val kind = nameKindWord(mediaType)
    if (section == null) return kind to twoDigits(index)
    val sequence = if (answer != null) Naming.questionSequence(section, answer, take = clip.clip) else Naming.sectionSequence(section)
    return kind to sequence
}

private fun nameKindWord(mediaType: String): String = NAME_KIND_WORD[mediaType.uppercase()] ?: "File"

private fun parseQuestionnaireClip(base: String): QuestionnaireClip? {
    val tokens = base.trim().split('_')
    if (tokens.size < 5) return null
    if (tokens[3].length != 6 || !tokens[3].all(Char::isDigit)) return null
    val stamp = tokens[4].takeIf { it.length == 12 && it.all(Char::isDigit) } ?: return null
    val section = tokens[0].takeIf { it.isNotBlank() && !it.equals("SEC", ignoreCase = true) }
    return QuestionnaireClip(
        section = section,
        answer = tokens[1].toIntOrNull(),
        name = tokens[2],
        stamp = stamp,
        clip = tokens.getOrNull(5)?.toIntOrNull()?.takeIf { it > 1 }
    )
}

private fun captionRecordName(caption: String?): String? =
    CAPTION_RECORD_NAME.find(caption.orEmpty().trim())?.groupValues?.get(1)?.blankToNull()

private fun splitCamel(value: String?): String = CAMEL_BOUNDARY.replace(value.orEmpty(), " ")

private fun nameLetters(value: String?): String = value.orEmpty().lowercase().replace(NON_LETTERS, "")

private fun trimRedundantTail(recordName: String, what: String): String {
    if (!what.startsWith("Grid-Measurement")) return recordName
    val trimmed = recordName.trim()
    if (!nameLetters(trimmed).endsWith("measurementgrid")) return trimmed
    val cut = trimmed.lowercase().lastIndexOf("measurement")
    return if (cut > 0) trimmed.substring(0, cut).trim() else trimmed
}
