package com.designprototype.workshop.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class GoogleLoginRequest(
    val googleIdToken: String
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "bearer",
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val canManageQuestionnaire: Boolean = false,
    /**
     * RETIRED GRANTS. The server still serves both columns and `PATCH /users` still accepts them,
     * so they stay here to decode — but `can_manage_crafts` / `can_manage_workshops` stopped reading
     * them (deps.py): both powers are Professor-by-rank alone now, because a grant that lifted
     * someone below the taxonomy over it was invisible in the role column. Nothing in this app may
     * consult them for a permission decision; the predicates in AppNavigation.kt and MainActivity.kt
     * are rank-only, and the toggles that set them are gone from the user admin card.
     */
    val canManageCrafts: Boolean = false,
    val canManageWorkshops: Boolean = false,
    val canReview: Boolean = false,
    val canViewProvenance: Boolean = false,
    val canDownloadDataset: Boolean = false,
    val authProvider: String? = null
)

/**
 * `GET /dashboard/stats`.
 *
 * THE TOP-LEVEL TOTALS ARE THE REPOSITORY'S, not the caller's uploads. They used to be filtered by
 * row visibility, so below Professor "Artisans" meant "artisans you entered" while the label said
 * otherwise — an account that had uploaded nothing read a screen of zeroes indistinguishable from an
 * empty repository. Reading is open now, so these are the true totals and the caller's own
 * contribution is answered separately in [mine]. Never render one under the other's label.
 */
@Serializable
data class DashboardStats(
    val totalArtisans: Int = 0,
    val totalWorkshops: Int = 0,
    val totalProductRecords: Int = 0,
    val totalToolRecords: Int = 0,
    val totalMediaFiles: Int = 0,
    val pendingSubmissions: Int = 0,
    /**
     * This account's own contribution, same keys as above so both rows render from one template.
     *
     * NULLABLE ON PURPOSE: a device can be talking to an API deployed before this block existed, and
     * a missing `mine` must read as "this server does not answer that question" — hide the row —
     * rather than as six honest zeroes, which would tell the user their work is gone.
     */
    val mine: DashboardStatsMine? = null,
    /** The repository's newest entries across the four record types, newest first, capped at ten. */
    val recentSubmissions: List<DashboardRecentSubmissionDto> = emptyList()
)

/** The caller's own uploads. Deliberately the SAME field names as [DashboardStats]'s totals. */
@Serializable
data class DashboardStatsMine(
    val totalArtisans: Int = 0,
    val totalWorkshops: Int = 0,
    val totalProductRecords: Int = 0,
    val totalToolRecords: Int = 0,
    val totalMediaFiles: Int = 0,
    val pendingSubmissions: Int = 0
)

/**
 * One row of the dashboard's recent-activity list. [type] is the SINGULAR record type — `artisan`,
 * `workshop`, `product`, `tool` — because it names one row, not a search bucket.
 *
 * [title] is whichever name column the record type carries, so it is null for a row that has none.
 * [createdByName] is whose work it is: the list could once only hold the reader's own rows, so it
 * went unshown; now that the list is the repository's, an unattributed title is one nobody can
 * follow up on. Still nullable — the account may have been deleted.
 */
@Serializable
data class DashboardRecentSubmissionDto(
    val id: String = "",
    val type: String = "",
    val status: String = "",
    val createdAt: String? = null,
    val title: String? = null,
    val place: String? = null,
    val createdByName: String? = null
)

@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val pages: Int
)

@Serializable
data class ArtisanDto(
    val id: String,
    val name: String,
    val place: String,
    val status: String,
    val craftId: String? = null,
    val craft: CraftDto? = null,
    /**
     * The captured coordinate. `GET /artisans` has always sent this — the field was simply not
     * declared here, so it was discarded at parse time and the artisan list was the one record type
     * with no position. The map screen needs it; [ArtisanDetailDto] already carried it.
     *
     * Deliberately the only thing added: this DTO carries no `aadhaarNumber` or `pehchanCardNumber`
     * and must not start to. Any screen that plots artisans reads THIS type precisely so that the
     * identity fields are not in scope to leak.
     */
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null
)

@Serializable
data class CraftDto(
    val id: String,
    val name: String,
    val localName: String? = null,
    val category: String? = null,
    val place: String? = null,
    val description: String? = null,
    // The workshop this craft was documented at. Persisted since the workshop-linkage migration
    // (Craft.workshopId), so it round-trips; still nullable, because a craft may carry no workshop
    // and every row created before that migration has none.
    val workshopId: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class CreatedRecordDto(
    val id: String
)

@Serializable
data class ToolArtisanAssignRequest(
    val artisanIds: List<String>
)

@Serializable
data class MediaPresignRequest(
    val filename: String,
    val mimeType: String,
    val mediaType: String,
    val sizeBytes: Long,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null
)

@Serializable
data class MediaPresignResponse(
    val uploadUrl: String,
    val method: String = "PUT",
    val objectKey: String,
    val bucket: String,
    val headers: Map<String, String> = emptyMap(),
    val publicUrl: String? = null
)

@Serializable
data class MediaCompleteRequest(
    val originalFilename: String,
    val mediaType: String,
    val mimeType: String,
    val sizeBytes: Long,
    val objectKey: String,
    val bucket: String? = null,
    val url: String? = null,
    val caption: String? = null,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null,
    val processingRequests: List<String> = emptyList()
)

// --- S3 multipart upload (large files: chunk for transfer, S3 stitches into one object) ---

@Serializable
data class MultipartCreateRequest(
    val filename: String,
    val mimeType: String,
    val mediaType: String,
    val sizeBytes: Long,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null
)

@Serializable
data class MultipartCreateResponse(
    val objectKey: String,
    val uploadId: String,
    val bucket: String,
    val partSize: Long,
    val partCount: Int,
    val publicUrl: String? = null
)

@Serializable
data class MultipartPresignPartsRequest(
    val objectKey: String,
    val uploadId: String,
    val partNumbers: List<Int>
)

@Serializable
data class MultipartPresignPartsResponse(
    val urls: Map<String, String> = emptyMap()
)

@Serializable
data class CompletedPart(
    val partNumber: Int,
    val etag: String
)

@Serializable
data class MultipartCompleteRequest(
    val objectKey: String,
    val uploadId: String,
    val parts: List<CompletedPart>
)

@Serializable
data class MultipartCompleteResponse(
    val objectKey: String,
    val bucket: String,
    val publicUrl: String? = null
)

@Serializable
data class MultipartAbortRequest(
    val objectKey: String,
    val uploadId: String
)

// --- Over-the-air app update ---

@Serializable
data class AppReleaseDto(
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String? = null,
    val notes: String? = null,
    val objectKey: String? = null
)

@Serializable
data class AppReleasePublishRequest(
    val versionCode: Int,
    val versionName: String,
    val objectKey: String,
    val url: String? = null,
    val notes: String? = null
)

// --- In-app feedback (quantitative rating + qualitative comment) ---

@Serializable
data class FeedbackDto(
    val id: String = "",
    val userId: String = "",
    // Quantitative (each 1–5): overall rating + per-aspect sub-ratings.
    val rating: Int? = null,
    val easeOfUse: Int? = null,
    val reliability: Int? = null,
    val performance: Int? = null,
    val design: Int? = null,
    val features: Int? = null,
    val recommend: Int? = null,
    // Qualitative free text.
    val comment: String? = null,
    val likeMost: String? = null,
    val improve: String? = null,
    val bugs: String? = null,
    val featureRequests: String? = null,
    val role: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val user: UserDto? = null
)

@Serializable
data class FeedbackUpsertRequest(
    val rating: Int? = null,
    val easeOfUse: Int? = null,
    val reliability: Int? = null,
    val performance: Int? = null,
    val design: Int? = null,
    val features: Int? = null,
    val recommend: Int? = null,
    val comment: String? = null,
    val likeMost: String? = null,
    val improve: String? = null,
    val bugs: String? = null,
    val featureRequests: String? = null,
    val role: String? = null
)

@Serializable
data class MediaRelinkRequest(
    val linkedRecordType: String,
    val linkedRecordId: String
)

@Serializable
data class TranscriptRefineRequest(
    val translate: Boolean = false
)

@Serializable
data class TranscriptUpdateRequest(
    val text: String
)

@Serializable
data class TranscriptRefineResponse(
    val available: Boolean = true,
    val status: String? = null,
    val refined: String? = null,
    val model: String? = null,
    val translated: Boolean = false,
    val message: String? = null
)

@Serializable
data class MeasurementAnalysisDto(
    val valueInches: Double? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val confidence: Double? = null,
    val notes: String? = null
)

@Serializable
data class AnalyzeMeasurementResponse(
    val available: Boolean = false,
    val status: String? = null,
    val analysis: MeasurementAnalysisDto? = null,
    val message: String? = null
)

@Serializable
data class MediaFileDto(
    val id: String,
    val originalFilename: String,
    val mediaType: String,
    val mimeType: String? = null,
    /**
     * The fetchable object URL — ABSENT WHENEVER THIS ACCOUNT MAY NOT TAKE THE FILE, which is now a
     * routine state rather than only the sign of an incomplete upload.
     *
     * Reading the repository is open to every signed-in account, so every media ROW travels: name,
     * type, caption, transcript, parent record, uploader. But a URL is not a description of a file, it
     * IS the file, so the server withholds it (and `objectKey`, from which it can be rebuilt) unless
     * the caller may download that uploader's data — themselves, a professor or above, a holder of the
     * dataset-download permission, or someone the uploader granted access to. See
     * `records._MEDIA_URL_KEYS`.
     *
     * So treat null as "not for you" OR "not uploaded yet" and offer no player either way. It is not an
     * error and must never be reported as one.
     */
    val url: String? = null,
    val caption: String? = null,
    val transcriptStatus: String? = null,
    val transcriptText: String? = null,
    val transcriptError: String? = null,
    val uploadedBy: UserDto? = null,
    val createdAt: String? = null,
    val linkedRecordType: String? = null,
    val linkedRecordId: String? = null
)

/**
 * One row of `GET /media/jobs` — the queue that turns an uploaded audio file into a transcript.
 *
 * WHY THIS DTO EXISTS AT ALL. The queue drains itself (the server starts a worker at boot), so
 * transcription worked and nobody noticed the half that did not: a job that used up its attempts
 * stopped in FAILED with the provider's reason in [error] — a field no screen on any client read.
 * The media row simply kept saying "Transcript: FAILED", with no cause, and there was no way to ask
 * for it again from the phone or from the web. Reading these rows is what makes the failure sayable.
 *
 * [mediaFile] arrives through the server's masked encoder with NO viewer, so its `url` is ALWAYS
 * absent here even for a master admin — take the name and the type from it, never the bytes.
 */
@Serializable
data class MediaProcessingJobDto(
    val id: String,
    /** TRANSCRIPTION or MEASUREMENT. */
    val jobType: String,
    /** QUEUED · PROCESSING · COMPLETED · FAILED · CANCELLED. */
    val status: String,
    val attempts: Int = 0,
    val maxAttempts: Int = 0,
    /** The reason, verbatim from the provider. The whole point of the row — never hide it. */
    val error: String? = null,
    /** Backoff gate: a QUEUED job whose runAfter is still ahead is waiting, not stuck. */
    val runAfter: String? = null,
    val completedAt: String? = null,
    val createdAt: String? = null,
    val mediaFileId: String,
    val mediaFile: MediaFileDto? = null,
    val requestedBy: UserDto? = null
)

/** `POST /media/jobs/process` — how much of the queue that manual drain actually moved. */
@Serializable
data class MediaQueueRunDto(
    val processed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0
)

@Serializable
data class UserUpdateRequest(
    val role: String? = null,
    val canManageQuestionnaire: Boolean? = null,
    val canManageCrafts: Boolean? = null,
    val canManageWorkshops: Boolean? = null,
    val canReview: Boolean? = null,
    val canViewProvenance: Boolean? = null,
    val canDownloadDataset: Boolean? = null
)

/**
 * Who created a record awaiting review. A trimmed shape on purpose: `/review/pending` embeds only
 * id/name/role, so this cannot reuse [UserDto] (whose `email` is required and absent here).
 */
@Serializable
data class ReviewCreatorDto(
    val id: String,
    val name: String = "",
    val role: String? = null
)

/** One record awaiting review, as surfaced by GET /review/pending. */
@Serializable
data class PendingReviewDto(
    val recordType: String,
    val id: String,
    val label: String,
    val place: String? = null,
    val createdAt: String? = null,
    val createdBy: ReviewCreatorDto? = null,
    // Submitted after its workshop ended: only an admin/master admin may edit or approve it, so the
    // queue warns before the reviewer discovers it as a 403.
    val needsAdminApproval: Boolean = false
)

@Serializable
data class PendingReviewListDto(
    val items: List<PendingReviewDto> = emptyList(),
    val total: Int = 0
)

/** Optional reviewer notes sent with approve/reject; MANDATORY on "send for revision" (422 without). */
@Serializable
data class ReviewActionRequest(
    val notes: String? = null
)

/**
 * A reviewer correcting a record in place instead of bouncing it back to its creator.
 *
 * [fields] is validated server-side against the record type's own PATCH schema, so only real columns
 * are accepted and every rule the normal edit path enforces still applies. Send ONLY the keys that
 * actually changed: the API refuses `status`, `extraMetadata`, `workshopId`, `location`, `artisanIds`,
 * `craftIds`, `responses`, `steps`, `recordedAt` and `recordedTimezone` with a 422.
 *
 * The record's status is left ALONE unless [approve] is set — an edit is not an approval.
 */
@Serializable
data class ReviewEditRequest(
    val fields: Map<String, String>,
    val note: String? = null,
    val approve: Boolean = false
)

@Serializable
data class CraftCreateRequest(
    val name: String,
    val localName: String? = null,
    val category: String? = null,
    val description: String? = null,
    val place: String? = null,
    // The workshop this craft was documented at. Every record form now opens with the workshop
    // picker, so the field is sent for craft too. NOTE: the API's Pydantic base sets
    // `extra="forbid"`, so this key is NOT optional at the wire level — a backend that predates
    // `CraftCreate.workshopId` rejects the whole request with 422. Ship the two together. When no
    // workshop is selected the value is null and `explicitNulls = false` omits the key entirely,
    // which is the backwards-compatible "no workshop named" path.
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata"
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WorkshopCreateRequest(
    val title: String,
    /**
     * Which KIND of workshop this is: `DESIGN_PROTOTYPE` or `OTHER`.
     *
     * A Design & Prototype Development Workshop and an ordinary documentation visit are both
     * `Workshop` rows, and nothing distinguished them — so the design-workshop picker had to offer
     * every craft-documentation visit ever recorded. Defaults to OTHER, which is what every row
     * recorded before this column implicitly was, so an older build sending nothing is unchanged.
     *
     * [EncodeDefault] because this same model is the body of the update PATCH, which the API reads
     * with `exclude_unset` — an omitted key means "leave the stored value alone". The request Json
     * is built with `encodeDefaults = false`, so without this the ONE edit that cannot be saved is
     * demoting a design-prototype workshop back to OTHER: the value matches the default, the key is
     * dropped, and the row silently stays DESIGN_PROTOTYPE. Same trap [ArtisanCreateRequest] avoids
     * by making its flag nullable; the enum keeps its non-null default and forces the key instead.
     */
    @EncodeDefault
    val workshopType: String = "OTHER",
    val date: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val place: String,
    val description: String? = null,
    val notes: String? = null,
    val artisanIds: List<String>? = null,
    val craftIds: List<String>? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ArtisanCreateRequest(
    val name: String,
    val localName: String? = null,
    val gender: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val place: String,
    val address: String? = null,
    val notes: String? = null,
    // Identity. `aadhaarNumber` is the repository's deduplication key (UNIQUE in the DB) and travels as
    // BARE 12 digits — the form's grouped "1234 5678 9012" display is presentation only. The API also
    // normalises spacing, but sending the clean value keeps the wire form and the stored form identical.
    val aadhaarNumber: String? = null,
    // Nullable on purpose even though the API's create model defaults it to true: the request Json is
    // built with `encodeDefaults = false`, so a non-null default would be DROPPED from the payload
    // whenever it matched — and a PATCH that omits the flag cannot flip a "No" record back to "Yes".
    // With null as the default, whatever the form sets is always on the wire.
    /**
     * ── THE TWO FACTS THE DESIGN WORKSHOP ASKS OF EVERY ARTISAN ──────────────────────────────
     *
     * `participant.age` and `participant.experienceYears` are `fromref` fields on the workshop's
     * participant table — their help text promises the designer the picker fills them in — and the
     * Artisan table had no column behind either until now. So an artisan IMPORTED into a workshop
     * arrived with both boxes blank and one ADDED from inside a workshop had nowhere to record
     * them, and the designer typed both back in from a printout. `experienceYears` is a report
     * table column, so the blank printed in every submitted report.
     *
     * A DATE, NOT AN AGE: the workshop shows an age and the server derives it from this every time
     * it is read, because an age written down is wrong within a year and nothing would notice.
     * Sent as `yyyy-MM-dd`; the API accepts a bare date.
     */
    val dateOfBirth: String? = null,
    /** Years practising the craft. 0..90, matching the stage registry's own bounds exactly. */
    val experienceYears: Int? = null,
    val pehchanCardAvailable: Boolean? = null,
    val pehchanCardNumber: String? = null,
    // Newline-separated, numbered Do's (positive prompt) and Don'ts (negative prompt). Required on the
    // form; the backend requires them on create (nullable here so an update PATCH stays flexible).
    val dos: String? = null,
    val donts: String? = null,
    val craftId: String? = null,
    val craftName: String? = null,
    // The workshop this artisan was documented at (see [CraftCreateRequest.workshopId]).
    val workshopId: String? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProductCreateRequest(
    val craftName: String,
    val place: String,
    val artisanName: String,
    val productName: String,
    val localName: String? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val productType: String = "OTHER",
    val timeTakenToCompleteProduct: String? = null,
    val size: String? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val heightInches: Double? = null,
    val costOfMaking: Double? = null,
    val sellingPrice: Double? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val marketDemand: String = "UNKNOWN",
    val rawMaterialsUsed: String? = null,
    val mainToolsUsed: String? = null,
    val productFunctionUse: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolCreateRequest(
    val craftName: String,
    val place: String,
    val artisanName: String,
    val toolkitName: String,
    val localName: String? = null,
    val englishName: String? = null,
    val processUsedIn: String? = null,
    val material: String? = null,
    val yearsInUse: Int? = null,
    val height: Double? = null,
    val width: Double? = null,
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val thickness: Double? = null,
    val weight: Double? = null,
    val radius: Double? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val maker: String = "UNKNOWN",
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val traditionType: String = "UNKNOWN",
    val replacementCost: Double? = null,
    val suggestionsForToolImprovement: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val status: String = "PENDING",
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

@Serializable
data class LocationRequest(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val address: String? = null,
    @SerialName("placeName") val placeName: String? = null,
    /*
     * THE STATED ADDRESS — where the SUBJECT is, as a statement by the researcher. Six real columns
     * on Location (see the backend's LocationInput and migration
     * 20260727120000_location_stated_address), all six null unless the researcher supplied them: the
     * API forbids unknown keys but is happy with these absent, and `explicitNulls = false` drops
     * them from the body.
     *
     * [state] and [district] must be canonical names from GET /reference/address — the server
     * validates against the very lists it serves, and resolves the district WITHIN its state because
     * several district names belong to two states at once. [pincode] is the bare six digits, no
     * spaces. [village] is free text, because no closed list of Indian villages exists.
     */
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
    val pincode: String? = null,
    /**
     * The optional pin the researcher dropped on the SUBJECT'S place.
     *
     * Deliberately not [latitude]/[longitude], which mean the DEVICE. Send the pair or neither — the
     * server refuses half a pin (`_pin_is_a_pair`), because a latitude on its own is 111 km of
     * meridian stored in the one column that exists to be precise.
     */
    val subjectLatitude: Double? = null,
    val subjectLongitude: Double? = null,
    /**
     * When the device took this reading, ISO 8601 with an offset — the "at" in "captured at".
     *
     * A column that has existed on Location since the beginning and that nothing has ever written,
     * which is why every stored coordinate is undated. A reading with no time on it cannot be told
     * apart from a reading taken a month later at a desk in another state, and that is exactly the
     * question the fifteen pilot records leave a reader unable to answer.
     */
    val capturedAt: String? = null,
    /**
     * The stated address AGAIN, in the shape this app used before the four columns above existed.
     *
     * Both shapes are sent on every save and neither may be dropped — see the long note at the top
     * of ui/LocationFields.kt for why, and the server's `lift_stated_address` for the half that
     * normalises them into one. Kept as a raw [JsonObject] rather than a `Map<String, String>` for
     * two reasons: a value this client does not understand round-trips through an edit instead of
     * being dropped on the floor, and a nested blob written by some future writer cannot fail the
     * decode of a whole artisan list.
     */
    val extraMetadata: JsonObject? = null
)

/**
 * `GET /reference/address` — the canonical Indian state / union-territory list and the pincode rule.
 *
 * Fetched rather than hard-coded, deliberately. A list copied into this file would be a second copy
 * of the server's, and the day the two disagree is the day a researcher picks a state the API
 * refuses. The payload is a pure constant server-side, so it costs one request.
 */
@Serializable
data class AddressReferenceDto(
    val version: Int = 1,
    val states: List<String> = emptyList(),
    val unionTerritories: List<String> = emptyList(),
    /** The flat list a single-group dropdown binds to, in the server's own order. */
    val statesAndUnionTerritories: List<String> = emptyList(),
    /**
     * The districts of each state, absent on a server older than reference version 2.
     *
     * Nullable on purpose rather than defaulted to an empty table: the district dropdown has to be
     * able to tell "this deployment does not serve districts yet" from "this state genuinely has
     * none", and only the first of those is worth explaining to the researcher.
     */
    val districts: AddressDistrictsDto? = null
)

/**
 * The district list, shipped inside the address reference so a state dropdown and its district
 * dropdown can never hold two different vintages of the same table.
 *
 * [asOf] and [listVersion] travel with the names so an exported dataset can record which vintage it
 * was coded against — districts are created, renamed and merged several times a year.
 */
@Serializable
data class AddressDistrictsDto(
    val source: String? = null,
    val sourceUrl: String? = null,
    val asOf: String? = null,
    val listVersion: Int = 0,
    val count: Int = 0,
    /** State name to its districts, in the register's own order. */
    val byState: Map<String, List<String>> = emptyMap()
)

@Serializable
data class QuestionnaireQuestionDto(
    val id: String,
    val sectionId: String? = null,
    val sectionCode: String,
    val sectionTitle: String,
    val prompt: String,
    val sortOrder: Int,
    val isActive: Boolean = true
)

@Serializable
data class QuestionnaireSectionDto(
    val id: String,
    val code: String,
    val title: String,
    val sortOrder: Int,
    val isActive: Boolean = true,
    val questions: List<QuestionnaireQuestionDto> = emptyList()
)

@Serializable
data class QuestionnaireSectionCreateRequest(
    val code: String,
    val title: String,
    val sortOrder: Int? = null,
    val isActive: Boolean = true
)

@Serializable
data class QuestionnaireSectionUpdateRequest(
    val code: String? = null,
    val title: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null
)

@Serializable
data class QuestionnaireSectionReorderRequest(
    val sectionIds: List<String>
)

@Serializable
data class QuestionnaireQuestionCreateRequest(
    val sectionId: String,
    val prompt: String,
    val sortOrder: Int? = null,
    val isActive: Boolean = true
)

@Serializable
data class QuestionnaireQuestionUpdateRequest(
    val sectionId: String? = null,
    val prompt: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null
)

@Serializable
data class QuestionnaireQuestionReorderRequest(
    val sectionId: String,
    val questionIds: List<String>
)

@Serializable
data class QuestionnaireResponseRequest(
    val questionId: String,
    val answerText: String? = null,
    val notes: String? = null
)

/**
 * NOTE: there is deliberately no `interviewDate` here. The date of an interview is no longer a form
 * field on either client — the server derives it from `recordedAt` (see the questionnaire route's
 * `derive_interview_date`), which is the moment the interview was actually captured rather than a
 * date a researcher retypes (and mistypes) at the end of a long session. Do not re-add it.
 */
@Serializable
data class QuestionnaireInterviewCreateRequest(
    val title: String,
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String = "PENDING",
    val artisanIds: List<String> = emptyList(),
    val responses: List<QuestionnaireResponseRequest> = emptyList(),
    // The workshop this interview was conducted at (see [CraftCreateRequest.workshopId]).
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata",
    val location: LocationRequest? = null
)

// ---------------------------------------------------------------------------
// Read models used by the browse / edit-existing screens. All fields are made
// optional so partial server payloads never break deserialization. Decimal
// columns arrive as JSON numbers (FastAPI encodes Decimal as float).
// ---------------------------------------------------------------------------

@Serializable
data class LocationDto(
    val id: String? = null,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val address: String? = null,
    @SerialName("placeName") val placeName: String? = null,
    /**
     * The stated address, column by column; see [LocationRequest] for every rule that governs them.
     *
     * ALL FOUR OF district/village/subjectLatitude/subjectLongitude WERE MISSING HERE, and their
     * absence was silent data loss rather than a gap in a read model. `LocationDto.toRequest()`
     * builds the body an edit re-sends, `attach_location` writes a BRAND NEW Location row from that
     * body, and `forbid_clearing_location` deliberately does not demand a stated address on update —
     * so a colleague opening a web-entered record on the phone to fix a phone number PATCHed the
     * district, the village and the pin away, successfully, with nothing on screen to say so.
     */
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
    val pincode: String? = null,
    val subjectLatitude: Double? = null,
    val subjectLongitude: Double? = null,
    /** When the device took this reading; see [LocationRequest.capturedAt]. */
    val capturedAt: String? = null,
    /** The pre-column shape of the stated address; see [LocationRequest.extraMetadata]. */
    val extraMetadata: JsonObject? = null
)

@Serializable
data class ArtisanDetailDto(
    val id: String,
    val name: String,
    val localName: String? = null,
    val gender: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val place: String = "",
    val address: String? = null,
    val notes: String? = null,
    // The artisan record returns the FULL Aadhaar number (every other surface — the data browser, the
    // .xlsx report, exports — gets the "XXXX XXXX 9012" mask), because the edit form has to show the
    // researcher what is stored before they change it.
    val aadhaarNumber: String? = null,
    val dateOfBirth: String? = null,
    val experienceYears: Int? = null,
    val pehchanCardAvailable: Boolean = true,
    val pehchanCardNumber: String? = null,
    val dos: String? = null,
    val donts: String? = null,
    val craftId: String? = null,
    val craft: CraftDto? = null,
    // The workshop this artisan was documented at (see [CraftDto.workshopId]).
    val workshopId: String? = null,
    val status: String = "PENDING",
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

/**
 * The artisan already holding a searched-for Aadhaar number. Deliberately only the fields that let a
 * researcher recognise the person and go to them — the number itself is never echoed back.
 */
@Serializable
data class ArtisanIdentityMatchDto(
    val id: String,
    val name: String = "",
    val place: String? = null,
    val craft: String? = null,
    val workshop: String? = null
)

/**
 * Answer from `GET /artisans/lookup/aadhaar`. "Not found" is the expected, successful answer (the
 * endpoint never 404s), so [found] false with a null [artisan] is the normal case, not an error.
 */
@Serializable
data class AadhaarLookupDto(
    val found: Boolean = false,
    val artisan: ArtisanIdentityMatchDto? = null
)

@Serializable
data class ProductDetailDto(
    val id: String,
    val productName: String = "",
    val localName: String? = null,
    val craftName: String = "",
    val artisanName: String = "",
    val place: String = "",
    val productType: String = "OTHER",
    val timeTakenToCompleteProduct: String? = null,
    val size: String? = null,
    // Decimal columns arrive from the API as JSON strings (e.g. "12.5"); typing them Double? broke
    // list parsing. The forms read them via numToText(). Request DTOs keep Double? (they send numbers).
    val lengthInches: String? = null,
    val breadthInches: String? = null,
    val heightInches: String? = null,
    val costOfMaking: String? = null,
    val sellingPrice: String? = null,
    val marketDemand: String = "UNKNOWN",
    val rawMaterialsUsed: String? = null,
    val mainToolsUsed: String? = null,
    val productFunctionUse: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val measurementAnalysisStatus: String? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class ToolDetailDto(
    val id: String,
    val toolkitName: String = "",
    val localName: String? = null,
    val englishName: String? = null,
    val craftName: String = "",
    val artisanName: String = "",
    val place: String = "",
    val processUsedIn: String? = null,
    val material: String? = null,
    val yearsInUse: Int? = null,
    // Decimal columns arrive as JSON strings; typed String? to keep list parsing from failing.
    val height: String? = null,
    val width: String? = null,
    val lengthInches: String? = null,
    val breadthInches: String? = null,
    val thickness: String? = null,
    val weight: String? = null,
    val radius: String? = null,
    val maker: String = "UNKNOWN",
    val traditionType: String = "UNKNOWN",
    val replacementCost: Double? = null,
    val suggestionsForToolImprovement: String? = null,
    val remarks: String? = null,
    val artisanId: String? = null,
    val craftId: String? = null,
    val workshopId: String? = null,
    val status: String = "PENDING",
    val measurementAnalysisStatus: String? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class WorkshopArtisanLinkDto(
    val artisanId: String,
    val artisan: ArtisanDto? = null
)

@Serializable
data class WorkshopCraftLinkDto(
    val craftId: String,
    val craft: CraftDto? = null
)

@Serializable
data class WorkshopDetailDto(
    val id: String,
    val title: String = "",
    /** See [WorkshopCreateRequest.workshopType]. Defaulted so an older server's payload still decodes. */
    val workshopType: String = "OTHER",
    val place: String = "",
    val description: String? = null,
    val notes: String? = null,
    val date: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: String = "PENDING",
    val artisans: List<WorkshopArtisanLinkDto> = emptyList(),
    val crafts: List<WorkshopCraftLinkDto> = emptyList(),
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class ArtisanAnswerDto(
    val responseId: String,
    val questionId: String,
    val prompt: String? = null,
    val sectionCode: String? = null,
    val sectionTitle: String? = null,
    val sortOrder: Int = 0,
    val answerText: String? = null,
    val notes: String? = null,
    val interviewId: String? = null,
    val interviewTitle: String? = null,
    val answeredByName: String? = null
)

@Serializable
data class ArtisanQuestionnaireDto(
    val artisanId: String,
    val answered: List<ArtisanAnswerDto> = emptyList(),
    val total: Int = 0,
    // Every interview this artisan belongs to (alone, in a subset, or in a larger set), with its
    // recordings and the co-artisans — so a group recording surfaces for each member individually.
    val interviews: List<ArtisanInterviewDto> = emptyList()
)

@Serializable
data class ArtisanInterviewDto(
    val interviewId: String,
    val title: String = "",
    val notes: String? = null,
    val interviewDate: String? = null,
    val place: String? = null,
    val language: String? = null,
    val status: String? = null,
    val artisanCount: Int = 0,
    val coArtisans: List<String> = emptyList(),
    val media: List<MediaFileDto> = emptyList()
)

@Serializable
data class InterviewResponseDto(
    val questionId: String,
    val answerText: String? = null,
    val notes: String? = null,
    val answeredBy: UserDto? = null
)

@Serializable
data class QuestionnaireInterviewDetailDto(
    val id: String,
    val title: String = "",
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String = "PENDING",
    val artisans: List<WorkshopArtisanLinkDto> = emptyList(),
    val responses: List<InterviewResponseDto> = emptyList(),
    // The workshop this interview was conducted at (see [CraftDto.workshopId]).
    val workshopId: String? = null,
    val location: LocationDto? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

// ---------------------------------------------------------------------------
// Process documentation: a making/using process tied to a product, with ordered
// steps that each carry their own media. Steps are sequential (files named 1A,
// 1B…) or a group of activities (files named 1-G1, 1-G2…).
// ---------------------------------------------------------------------------

@Serializable
data class ProcessStepRequest(
    val id: String? = null,
    val name: String,
    val stepType: String = "SEQUENTIAL",
    val sortOrder: Int = 0,
    val notes: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProcessCreateRequest(
    val name: String,
    val productId: String,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val preProcessAvailable: Boolean = false,
    val notes: String? = null,
    /**
     * [EncodeDefault] because this model is ALSO the body of the update PATCH, which the API reads
     * with `exclude_unset=True` — an omitted key means "leave the stored value alone". The request
     * Json is built with `encodeDefaults = false` (ApiClient.kt), so without this the ONE edit that
     * cannot be saved is the edit BACK to the default: the value matches, the key is dropped, the
     * form shows the new value, the save reports success, and the database never changed.
     */
    @EncodeDefault
    val status: String = "PENDING",
    val steps: List<ProcessStepRequest> = emptyList(),
    // The workshop this process was documented at (see [CraftCreateRequest.workshopId]).
    val workshopId: String? = null,
    val recordedAt: String? = null,
    val recordedTimezone: String = "Asia/Kolkata"
)

@Serializable
data class ProcessStepDto(
    val id: String,
    val name: String = "",
    val stepType: String = "SEQUENTIAL",
    val sortOrder: Int = 0,
    val notes: String? = null,
    val media: List<MediaFileDto> = emptyList()
)

@Serializable
data class ProcessDetailDto(
    val id: String,
    val name: String = "",
    val productId: String = "",
    val preProcessAvailable: Boolean = false,
    val notes: String? = null,
    val status: String = "PENDING",
    val product: ProductDetailDto? = null,
    val steps: List<ProcessStepDto> = emptyList(),
    val media: List<MediaFileDto> = emptyList(),
    // The workshop this process was documented at (see [CraftDto.workshopId]).
    val workshopId: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val createdBy: UserDto? = null,
    val extraMetadata: JsonObject? = null
)

@Serializable
data class DatasetFileDto(
    val path: String,
    val url: String? = null,
    val content: String? = null
)

@Serializable
data class DatasetManifestDto(
    val files: List<DatasetFileDto> = emptyList(),
    val totalFiles: Int = 0,
    val totalMedia: Int = 0,
    /**
     * True when ANY table hit the server's per-row cap, so this manifest is a PART of the dataset
     * presented in the same shape as the whole of it.
     *
     * The server has always sent this (`backend/app/api/routes/export.py`, set when EXPORT_TAKE or
     * MEDIA_TAKE is reached and documented on the route) and this DTO used to drop it on the floor,
     * so the download said "Saved to Downloads — 4,312/4,312 files" over an archive missing
     * everything past the cap. Both numbers were true and the sentence was not. A partial archive
     * that presents itself as complete is worse than a failed one, because nobody goes back for the
     * rest — the web says exactly that beside its own copy of this warning
     * (frontend/app/(protected)/sharing/page.tsx).
     *
     * Defaulted to false so an older server that omits the key still parses.
     */
    val truncated: Boolean = false,
    /**
     * How many media rows the server could not address at all — a row whose object key or signed URL
     * could not be produced, as distinct from a table that hit its cap.
     *
     * The server OR-s this into [truncated] on purpose (`export.py`: a zip short by an unaddressable
     * file is exactly as partial as one short by a capped row), so a client that only reads
     * [truncated] is already warned. It is carried separately because the two states are a different
     * conversation for whoever has to go and find the missing files.
     *
     * Defaulted to 0 so an older server that omits the key still parses. On the streamed path the
     * same number arrives as `X-Dataset-Skipped`.
     */
    val skippedMedia: Int = 0
)

@Serializable
data class QuestionnaireInterviewUpdateRequest(
    val title: String? = null,
    val place: String? = null,
    val language: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val artisanIds: List<String>? = null,
    val responses: List<QuestionnaireResponseRequest>? = null,
    val workshopId: String? = null,
    val recordedTimezone: String? = null,
    val location: LocationRequest? = null
)

// --- Questionnaire completion matrix (artisans x sections) ---

@Serializable
data class CompletionMatrixDto(
    val sections: List<CompletionSectionDto> = emptyList(),
    val artisans: List<CompletionArtisanDto> = emptyList(),
    val cells: List<CompletionCellDto> = emptyList(),
    /**
     * How many questionnaire interviews the CHOSEN WORKSHOP SCOPE cannot see, because they name no
     * workshop at all.
     *
     * The number exists so the failure mode can never be silent again. An interview with no `workshopId`
     * counts towards no workshop scope — which is correct, it genuinely does not say where it was taken —
     * but it is also exactly the shape of the bug that had this matrix reporting "nothing was covered at
     * this workshop" while twenty-five interviews sat in the repository unlinked, leaving only the admin
     * overrides visible. Zero whenever the scope cannot hide anything: no workshop chosen, or the
     * unassigned records explicitly included.
     */
    val unassignedInterviews: Int = 0,
    /**
     * True while an admin's mark is keyed on (artisan, section) ALONE, i.e. is not per workshop.
     *
     * The server states it rather than leaving each client to work out whether it matters: the workshop
     * scope narrows the green DERIVED from recordings, but a marked cell keeps its colour under every
     * scope, and a reader watching a cell stay green as the scope moves deserves to be told why.
     */
    val overridesAreRepositoryWide: Boolean = false
)

@Serializable
data class CompletionSectionDto(
    val id: String,
    val code: String,
    val title: String,
    val sortOrder: Int = 0
)

@Serializable
data class CompletionArtisanDto(
    val id: String,
    val name: String
)

@Serializable
data class CompletionCellDto(
    val artisanId: String,
    val sectionId: String,
    val derived: Boolean = false,
    // null = no admin override (fall back to `derived`); else COMPLETED | NEEDS_REVIEW | NEEDS_REDO.
    val status: String? = null,
    val setByName: String? = null
)

@Serializable
data class CompletionCellRequest(
    val artisanId: String,
    val sectionId: String,
    // null clears the override.
    val status: String? = null
)

@Serializable
data class AppSettingDto(
    val transcriptionMode: String = "REFINED_TRANSLATED",
    val batchWindowEnabled: Boolean = false,
    val batchWindowStart: String = "02:00",
    val batchWindowEnd: String = "05:00",
    val batchTimezone: String = "Asia/Kolkata"
)

@Serializable
data class AppSettingUpdateRequest(
    val transcriptionMode: String? = null,
    val batchWindowEnabled: Boolean? = null,
    val batchWindowStart: String? = null,
    val batchWindowEnd: String? = null,
    val batchTimezone: String? = null
)

// ---------------------------------------------------------------------------
// Records that name no workshop — GET /workshops/unmapped and POST /workshops/unmapped/map.
//
// WHY THIS EXISTS. Every control that narrows by workshop reads one column, `workshopId`. A record with
// that column empty counts towards NO workshop scope — while remaining perfectly visible under "All
// records". Since both clients OPEN scoped to the most recent workshop, the result reads as "nothing was
// documented here" rather than as a filter excluding data sitting right there. That is exactly what
// happened: 25 questionnaire interviews and 924 media files, every one recorded at the single workshop in
// the repository, none carrying its id, and a completion matrix showing nothing but the cells an admin had
// ticked by hand.
//
// The server decides everything. It reads the evidence, it names the rung that decided each row, and it
// refuses to guess when the evidence is ambiguous — see `backend/app/services/workshop_inference.py`. This
// client renders the report and presses the button; it never proposes a mapping of its own.
//
// Every field defaults, like the map payloads above and for the same reason: an admin screen that cannot
// decode a new field is a screen that shows nothing at all.
// ---------------------------------------------------------------------------

/** One row the ladder looked at: where it would be filed, or why it was left alone. */
@Serializable
data class WorkshopMappingRowDto(
    val id: String = "",
    val title: String = "",
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    /** `PARENT` | `ARTISANS` | `WINDOW`, or null when the row could not be settled. */
    val rung: String? = null,
    /** The server's own sentence for that rung, so both clients word a decision identically. */
    val rungCopy: String? = null,
    /** `NO_EVIDENCE` | `AMBIGUOUS`, or null when the row WAS settled. */
    val reason: String? = null,
    val reasonCopy: String? = null,
    /** Every workshop the deciding rung pointed at. More than one is what AMBIGUOUS means. */
    val candidateTitles: List<String> = emptyList()
)

@Serializable
data class WorkshopMappingRungCountDto(
    val rung: String = "",
    val copy: String = "",
    val count: Int = 0
)

@Serializable
data class WorkshopMappingReasonCountDto(
    val reason: String = "",
    val copy: String = "",
    val count: Int = 0
)

@Serializable
data class WorkshopMappingWorkshopCountDto(
    val workshopId: String = "",
    val title: String = "",
    val count: Int = 0
)

/**
 * One record type's share of the gap. [singular]/[plural] are the server's own nouns, so the two clients
 * cannot call the same bucket two different things.
 *
 * [rows] is CAPPED by the server — the counts are the answer and the rows are the evidence for
 * spot-checking it — with unresolved rows listed FIRST, because those are what an admin has to act on.
 * [rowsTruncated] says the cap was hit. [applied] is null on the preview and the number actually written
 * on the response to the button.
 */
@Serializable
data class WorkshopMappingBucketDto(
    val bucket: String = "",
    val singular: String = "",
    val plural: String = "",
    val unassigned: Int = 0,
    val resolved: Int = 0,
    val unresolved: Int = 0,
    val byRung: List<WorkshopMappingRungCountDto> = emptyList(),
    val byReason: List<WorkshopMappingReasonCountDto> = emptyList(),
    val byWorkshop: List<WorkshopMappingWorkshopCountDto> = emptyList(),
    val rows: List<WorkshopMappingRowDto> = emptyList(),
    val rowsTruncated: Boolean = false,
    val applied: Int? = null
)

@Serializable
data class WorkshopMappingWindowDto(
    val id: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = ""
)

@Serializable
data class WorkshopMappingTotalsDto(
    val unassigned: Int = 0,
    val resolved: Int = 0,
    val unresolved: Int = 0,
    /** Null on the preview; the number of rows actually written on the response to the button. */
    val applied: Int? = null
)

@Serializable
data class WorkshopMappingPlanDto(
    /** The workshops as time spans, so the screen can say what the dates were read as. */
    val workshops: List<WorkshopMappingWindowDto> = emptyList(),
    val buckets: List<WorkshopMappingBucketDto> = emptyList(),
    val totals: WorkshopMappingTotalsDto = WorkshopMappingTotalsDto()
)

// --- Cross-researcher data access (Sharing) ---

@Serializable
data class DataAccessTierInfo(val tier: String, val description: String)

@Serializable
data class DataAccessScopeItemDto(
    val recordType: String,
    val recordId: String
)

@Serializable
data class DataAccessGrantDto(
    val id: String,
    val ownerId: String,
    val granteeId: String,
    val tier: String,
    val status: String,
    val allData: Boolean = true,
    val requestNote: String? = null,
    val decisionNote: String? = null,
    val owner: UserDto? = null,
    val grantee: UserDto? = null,
    val scopeItems: List<DataAccessScopeItemDto> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class MyGrantsDto(
    val incoming: List<DataAccessGrantDto> = emptyList(),
    val outgoing: List<DataAccessGrantDto> = emptyList()
)

@Serializable
data class DataAccessRequestBody(
    val ownerId: String,
    val tier: String = "DOWNLOAD",
    val allData: Boolean = true,
    val requestNote: String? = null
)

@Serializable
data class DataAccessGrantBody(
    val granteeId: String,
    val tier: String = "DOWNLOAD",
    val allData: Boolean = true,
    val scopeItems: List<DataAccessScopeItemDto> = emptyList(),
    val decisionNote: String? = null
)

@Serializable
data class DataAccessDecisionBody(
    val status: String,
    val tier: String? = null,
    val decisionNote: String? = null
)

@Serializable
data class EntryCommentDto(
    val id: String,
    val recordType: String,
    val recordId: String,
    val authorId: String,
    val body: String,
    val author: UserDto? = null,
    val createdAt: String
)

@Serializable
data class EntryCommentBody(
    val recordType: String,
    val recordId: String,
    val body: String
)

@Serializable
data class RevisionChange(
    val old: JsonElement? = null,
    val new: JsonElement? = null
)

@Serializable
data class RecordRevisionDto(
    val id: String,
    val recordType: String,
    val recordId: String,
    val editedBy: UserDto? = null,
    val changes: Map<String, RevisionChange> = emptyMap(),
    val createdAt: String
)

// ---------------------------------------------------------------------------
// Workshop access. ONE row per (workshop, user) carries the whole two-sided
// conversation: an admin grants/revokes, a user requests and is approved or
// denied. Only status == "GRANTED" confers access — a PENDING row confers
// nothing, and DENIED/REVOKED rows are kept as history, never deleted.
// ---------------------------------------------------------------------------

/**
 * Answer from `GET /workshops/{id}/submission-check` — what submitting a record into ONE workshop
 * would mean for the signed-in user, asked BEFORE the record is sent. The endpoint never 403s; it
 * only reports, so a failure to reach it must never block a save.
 *
 * - [canSubmit] false: the workshop is curated and this user is not on its roster, so a create would
 *   come back 403. Say so at pick time instead of after the form is filled in.
 * - [needsAdminApproval] true: the submission IS accepted, but it is pinned to PENDING and only an
 *   admin or master admin can approve it — a professor cannot. Admins are the approval authority, so
 *   an admin submitting late sees [outOfWindow] true with [needsAdminApproval] false.
 *
 * Only the eight documented keys are modelled; the endpoint also returns accessLevel/requestStatus/
 * restricted/canEdit, which the record forms have no use for (`ignoreUnknownKeys` drops them).
 */
@Serializable
data class WorkshopSubmissionCheckDto(
    val workshopId: String? = null,
    val title: String? = null,
    val endDate: String? = null,
    /** The whole of the end day has passed. */
    val isOver: Boolean = false,
    /** Submitting now falls outside [startDate, endDate] — before it opened, or after it closed. */
    val outOfWindow: Boolean = false,
    val needsAdminApproval: Boolean = false,
    val assigned: Boolean = true,
    val canSubmit: Boolean = true
)

/** VIEW < CONTRIBUTE < EDIT. The ladder and its human definitions come from the API. */
@Serializable
data class WorkshopAccessLevelDto(
    val level: String,
    val description: String = ""
)

@Serializable
data class WorkshopAssignmentDto(
    val id: String,
    val workshopId: String,
    val userId: String,
    val accessLevel: String = "CONTRIBUTE",
    // PENDING | GRANTED | DENIED | REVOKED. Anything but GRANTED means no access.
    val status: String = "GRANTED",
    val requestNote: String? = null,
    val decisionNote: String? = null,
    val decidedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val user: UserDto? = null,
    // Present on the cross-workshop views (/access-requests and /access-requests/mine), where a row
    // has to name the workshop it belongs to; null on a single workshop's roster.
    val workshop: WorkshopDetailDto? = null,
    val assignedBy: UserDto? = null,
    val requestedBy: UserDto? = null,
    val decidedBy: UserDto? = null
)

/** Legacy whole-set roster replacement (PUT). Still supported; POST/PATCH/DELETE are per-user. */
@Serializable
data class WorkshopAssignmentBody(
    val userIds: List<String>,
    val accessLevel: String? = null
)

/**
 * Ask for access to SEVERAL workshops at once — that is how the need arrives (a researcher joining a
 * project wants the same access to a whole season), and filing them one at a time produces a queue
 * nobody works through. Idempotent per workshop server-side.
 */
@Serializable
data class WorkshopAccessRequestBody(
    val workshopIds: List<String>,
    val accessLevel: String? = null,
    val note: String? = null
)

/** Per-workshop result of a multi-select request: CREATED | ALREADY_PENDING | ALREADY_GRANTED | RE_REQUESTED. */
@Serializable
data class WorkshopAccessOutcomeDto(
    val workshopId: String,
    val outcome: String
)

@Serializable
data class WorkshopAccessRequestResultDto(
    val outcomes: List<WorkshopAccessOutcomeDto> = emptyList(),
    val requests: List<WorkshopAssignmentDto> = emptyList()
)

/** Admin answer to a PENDING request: status is GRANTED or DENIED. */
@Serializable
data class WorkshopAccessDecisionBody(
    val status: String,
    val accessLevel: String? = null,
    val note: String? = null
)

/** Admin grants ONE user access at a level without disturbing the rest of the roster (upsert). */
@Serializable
data class WorkshopGrantBody(
    val userId: String,
    val accessLevel: String? = null,
    val note: String? = null
)

/** Admin changes one roster row: its level, its status (GRANTED | DENIED | REVOKED), or both. */
@Serializable
data class WorkshopAssignmentUpdateBody(
    val accessLevel: String? = null,
    val status: String? = null,
    val note: String? = null
)

// ---------------------------------------------------------------------------
// Assigned tasks. One row is always exactly ONE assignee; handing the same
// scope to N people writes N rows sharing a batchId. Scope is five orthogonal
// dimensions: workshop x recordTypes x artisans x sections x targetCount.
// ---------------------------------------------------------------------------

@Serializable
data class TaskArtisanDto(
    val id: String,
    val name: String = "",
    val place: String? = null
)

@Serializable
data class TaskSectionDto(
    val id: String,
    val code: String = "",
    val title: String = "",
    val sortOrder: Int = 0
)

/**
 * A task with its scope already resolved by the server — workshop title, artisan names, section
 * codes and both progress numbers — so a task board renders from this one call.
 *
 * [progressCount] is what the assignee CLAIMS; [derivedCount] is what the database can see them
 * having actually produced. The two answer different questions and the gap between them is the whole
 * point of the accountability view, so neither ever overwrites the other. [derivedCount] is null when
 * the count could not be run, and [percentComplete] is null for an open-ended task (no target).
 */
@Serializable
data class TaskDto(
    val id: String,
    val title: String = "",
    val description: String? = null,
    // OPEN | IN_PROGRESS | DONE | CANCELLED
    val status: String = "OPEN",
    val dueAt: String? = null,
    val completedAt: String? = null,
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val recordTypes: List<String> = emptyList(),
    val recordTypeLabels: List<String> = emptyList(),
    val artisans: List<TaskArtisanDto> = emptyList(),
    val sections: List<TaskSectionDto> = emptyList(),
    val targetCount: Int? = null,
    val progressCount: Int = 0,
    val percentComplete: Int? = null,
    val isOverdue: Boolean = false,
    val derivedCount: Int? = null,
    val derivedTarget: Int? = null,
    val derivedBreakdown: Map<String, Int> = emptyMap(),
    val batchId: String? = null,
    val assigneeId: String? = null,
    val assignee: UserDto? = null,
    val createdById: String? = null,
    val createdBy: UserDto? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    // The raw scope-id columns, next to the resolved [artisans]/[sections] above. An admin screen
    // needs the ids to pre-tick a picker; the resolved rows are only what the server could still find.
    val artisanIds: List<String> = emptyList(),
    val sectionIds: List<String> = emptyList(),
    // Legacy single-record link ("finish THIS product"), orthogonal to the scope block.
    val recordType: String? = null,
    val recordId: String? = null
)

/**
 * What an ASSIGNEE may change: where the task stands, and how much of it is done. Scope, due date and
 * reassignment stay with whoever handed the work out — sending anything else is a 403.
 */
@Serializable
data class TaskUpdateBody(
    val status: String? = null,
    val progressCount: Int? = null
)

// ---------------------------------------------------------------------------
// Task ADMINISTRATION (admin / master admin): hand work out, then hold it to
// account. Every route behind these DTOs is `require_admin` server-side.
// ---------------------------------------------------------------------------

/**
 * `user_brief()` — just enough to name a person on a task board, never any privilege material.
 *
 * Distinct from [UserDto] on purpose: the brief carries [roleLabel] ("Master Admin") and carries
 * none of the permission booleans, so it can never be mistaken for an authorisation source.
 */
@Serializable
data class TaskUserDto(
    val id: String,
    val name: String = "",
    val email: String? = null,
    val role: String = "",
    /** Human label for [role] as the server words it — use this, don't re-derive it on the client. */
    val roleLabel: String = ""
)

/** One entry of the record-type catalogue: `{value:"tool", label:"tool", pluralLabel:"tools"}`. */
@Serializable
data class TaskRecordTypeOptionDto(
    val value: String,
    val label: String = "",
    val pluralLabel: String = ""
)

/** A workshop as the assignment picker shows it (not the full [WorkshopDetailDto]). */
@Serializable
data class TaskWorkshopOptionDto(
    val id: String,
    val title: String = "",
    val place: String? = null,
    val date: String? = null
)

/**
 * `GET /tasks/options` — every picker the assignment builder needs, in one call.
 *
 * [assignees] is already filtered to the people THIS admin may assign to (strictly below their own
 * tier; the master admin sees everyone but themselves), so the client must never widen it.
 * [artisans] narrows to the workshop when `workshopId` was passed.
 */
@Serializable
data class TaskOptionsDto(
    val recordTypes: List<TaskRecordTypeOptionDto> = emptyList(),
    val assignees: List<TaskUserDto> = emptyList(),
    val workshops: List<TaskWorkshopOptionDto> = emptyList(),
    val artisans: List<TaskArtisanDto> = emptyList(),
    val sections: List<TaskSectionDto> = emptyList()
)

/**
 * `POST /tasks/batch` — assign ONE scope to several people at once. Writes one row per assignee, all
 * sharing a generated `batchId`.
 *
 * The scope must contain work: `recordTypes` and/or `sectionIds` non-empty, or the API 422s. Empty
 * [artisanIds]/[sectionIds] mean "not narrowed", not "nothing" — and because the JSON encoder skips
 * values equal to their default, an empty list is simply not sent, which is exactly that meaning.
 * [title] is optional: omit it and the server derives a readable one from the scope. [dueAt] is
 * ISO-8601 (e.g. `2026-08-01T00:00:00Z`).
 */
@Serializable
data class TaskBatchCreateBody(
    val assigneeIds: List<String>,
    val workshopId: String? = null,
    val recordTypes: List<String> = emptyList(),
    val artisanIds: List<String> = emptyList(),
    val sectionIds: List<String> = emptyList(),
    val targetCount: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val dueAt: String? = null
)

/** One member of a batch: who it went to and where they have got to. */
@Serializable
data class TaskBatchAssigneeDto(
    val taskId: String,
    val user: TaskUserDto? = null,
    val status: String = "OPEN",
    val progressCount: Int = 0,
    val derivedCount: Int? = null,
    /** Null for an open-ended task (no target) that is neither DONE nor CANCELLED. */
    val percentComplete: Int? = null,
    val completedAt: String? = null
)

/**
 * `GET /tasks/batches` item — one assignment ACTION rolled back up.
 *
 * [batchId] is null for rows written before batching existed and for single-assignee creates; use
 * [key] (the batchId, or `task:{id}`) as the stable list key. The counts always describe the WHOLE
 * batch even when the request filtered by assignee or status — "3 of 5 done" stops meaning anything
 * if the filter silently dropped two of the five.
 *
 * [reportedTotal] is what the assignees CLAIM; [derivedTotal] is what the repository can actually
 * find them having produced (null when the counts could not be run). The gap is the point.
 */
@Serializable
data class TaskBatchDto(
    val key: String,
    val batchId: String? = null,
    val title: String = "",
    val description: String? = null,
    val dueAt: String? = null,
    val createdAt: String? = null,
    val createdBy: TaskUserDto? = null,
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val recordTypes: List<String> = emptyList(),
    val recordTypeLabels: List<String> = emptyList(),
    val artisans: List<TaskArtisanDto> = emptyList(),
    val sections: List<TaskSectionDto> = emptyList(),
    val targetCount: Int? = null,
    val assigneeCount: Int = 0,
    /** OPEN / IN_PROGRESS / DONE / CANCELLED -> how many of the batch's rows are in that state. */
    val statusCounts: Map<String, Int> = emptyMap(),
    val doneCount: Int = 0,
    val openCount: Int = 0,
    val overdueCount: Int = 0,
    val reportedTotal: Int = 0,
    val derivedTotal: Int? = null,
    val percentComplete: Int = 0,
    val assignees: List<TaskBatchAssigneeDto> = emptyList()
)

/** `POST /tasks/batch` response: the new batch plus every row it wrote. */
@Serializable
data class TaskBatchResultDto(
    val batchId: String = "",
    val title: String = "",
    val created: Int = 0,
    val batch: TaskBatchDto? = null,
    val tasks: List<TaskDto> = emptyList()
)

/** One person's line on the accountability rollup, with their tasks attached. */
@Serializable
data class TaskProgressAssigneeDto(
    val user: TaskUserDto? = null,
    val taskCount: Int = 0,
    val statusCounts: Map<String, Int> = emptyMap(),
    val openCount: Int = 0,
    val overdueCount: Int = 0,
    /** Sum of the quotas they were given; null when none of their tasks carries one. */
    val targetTotal: Int? = null,
    val reportedTotal: Int = 0,
    val derivedTotal: Int? = null,
    val percentComplete: Int = 0,
    val tasks: List<TaskDto> = emptyList()
)

/**
 * `GET /tasks/progress` — the accountability rollup: who has what, and how far along they really are.
 *
 * [truncated] is true when the 2000-row scan window was hit, which makes this a PARTIAL picture; say
 * so rather than presenting it as the whole truth. Assignees arrive busiest-outstanding first.
 */
@Serializable
data class TaskProgressReportDto(
    val workshopId: String? = null,
    val workshopTitle: String? = null,
    val assigneeCount: Int = 0,
    val taskCount: Int = 0,
    val doneCount: Int = 0,
    val openCount: Int = 0,
    val overdueCount: Int = 0,
    val truncated: Boolean = false,
    val assignees: List<TaskProgressAssigneeDto> = emptyList()
)

// ---------------------------------------------------------------------------
// Managed provider keys (MASTER ADMIN ONLY). Every /secrets route is behind
// `require_master_admin`, not merely `require_admin`.
// ---------------------------------------------------------------------------

/**
 * One manageable API key. This shape NEVER carries the value — only a four-character [hint] — so a
 * list screen can be rendered without a credential ever reaching it. Only [ManagedSecretRevealDto]
 * carries plaintext, and fetching it is audit-logged server-side.
 *
 * [source] is `database` (an override is stored here), `environment` (only the deployed env var) or
 * `unset`. [lastStatus] is `UNKNOWN` / `OK` / `FAILED` and only changes when a test is run.
 */
@Serializable
data class ManagedSecretDto(
    val key: String,
    val label: String = "",
    val description: String? = null,
    /** True when the key resolves to something at all — stored override OR environment value. */
    val configured: Boolean = false,
    val source: String = "unset",
    /** Last four characters of the effective value, so two keys can be told apart safely. */
    val hint: String? = null,
    val lastStatus: String = "UNKNOWN",
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
    /** Display name (or email) of whoever last saved the override; null for environment values. */
    val updatedBy: String? = null,
    val updatedAt: String? = null
)

/**
 * `GET /secrets/{key}/reveal` — the eye button, one key at a time. [value] is the value actually in
 * force (the stored override, else the environment value), and null when the key is unset or a
 * stored value can no longer be decrypted.
 */
@Serializable
data class ManagedSecretRevealDto(
    val key: String,
    val value: String? = null,
    val source: String = "unset"
)

/** `PUT /secrets/{key}` — set or rotate one key. Blank is a 422; use DELETE to fall back to the env. */
@Serializable
data class ManagedSecretSetBody(
    val value: String
)

// ---------------------------------------------------------------------------
// Per-user appearance + accessibility preferences.
// ---------------------------------------------------------------------------

/**
 * `GET /preferences/me`, `PUT /preferences/me`.
 *
 * The GET returns an EMPTY OBJECT when the account has never saved any — which decodes here to a
 * row with a null [id]. Read that as "this account has no opinion yet" (see [exists]) and keep
 * whatever the device already applied, seeding the server with it, rather than snapping the user
 * back to the defaults below.
 *
 * [theme] is `system` | `light` | `dark`; anything else is a 422 on save.
 */
@Serializable
data class PreferencesDto(
    val id: String? = null,
    val userId: String? = null,
    val updatedAt: String? = null,
    val theme: String = "system",
    /** Force reduced motion. ORs with the OS setting; it can never switch the OS preference off. */
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
) {
    /** False when the server returned `{}` — the account has no saved row yet. */
    val exists: Boolean get() = id != null
}

/** `PUT /preferences/me`. Sent WHOLE on every save: an omitted field falls back to off/system. */
@Serializable
data class PreferencesUpdateBody(
    val theme: String = "system",
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
)

// ---------------------------------------------------------------------------
// Global search.
// ---------------------------------------------------------------------------

/** Per-bucket match counts for the CURRENT filters — the whole result set, not just this page. */
@Serializable
data class SearchTotalsDto(
    val artisans: Int = 0,
    val workshops: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0
)

/**
 * `GET /search` — five buckets sharing one page/pageSize.
 *
 * Each bucket is its own slice of its own result set, so a page can be full in one bucket and empty
 * in another; [totals] is how many matches each bucket has in total and [pageCount] is the last page
 * of the LONGEST bucket (at least 1, so an empty result still reads as "page 1 of 1"). Every row is
 * already filtered by what the caller is allowed to see.
 */
@Serializable
data class SearchResultsDto(
    val query: String? = null,
    val page: Int = 1,
    val pageSize: Int = 10,
    val artisans: List<ArtisanDto> = emptyList(),
    val workshops: List<WorkshopDetailDto> = emptyList(),
    val products: List<ProductDetailDto> = emptyList(),
    val tools: List<ToolDetailDto> = emptyList(),
    val media: List<MediaFileDto> = emptyList(),
    val totals: SearchTotalsDto = SearchTotalsDto(),
    /** Every bucket's matches added together. */
    val total: Int = 0,
    val pageCount: Int = 1
)

// ---------------------------------------------------------------------------
// Data browser: a lazily-explorable file-system view over the repository.
// Gated by the dataset-download permission AND by row visibility.
// ---------------------------------------------------------------------------

/** One breadcrumb. The server resolves clean names, so never derive these from the path segments. */
@Serializable
data class DataCrumbDto(
    val name: String = "",
    val path: String = ""
)

/** One labelled field of a record folder's info card. Both sides are already display-ready text. */
@Serializable
data class DataInfoFieldDto(
    val label: String = "",
    val value: String = ""
)

/** The info card shown on a record folder (workshop / artisan / product / tool / process / interview). */
@Serializable
data class DataFolderInfoDto(
    val title: String = "",
    val fields: List<DataInfoFieldDto> = emptyList()
)

/**
 * One of the three ways the same repository can be browsed, served with EVERY tree level so the
 * client can offer the other two without a second call. [isDefault] marks the one to open on.
 */
@Serializable
data class DataTaxonomyDto(
    val id: String,
    val name: String = "",
    val path: String = "",
    val description: String = "",
    @SerialName("default") val isDefault: Boolean = false
)

/**
 * One row of a tree level. [kind] is `folder` or `file`.
 *
 * A folder carries [recordType] (`workshop`, `artisan`, `product`, `tool`, `process`, `interview`,
 * `craft`, `category`, `taxonomy`, ...) and is opened by re-requesting the tree at its [path].
 * A file is either GENERATED TEXT — [content] holds the whole body inline (details.txt, answers.txt,
 * notes.txt, *.transcript.md), nothing to download — or a real media object, in which case
 * [mediaId] / [mediaType] / [url] / [sizeBytes] are set. [transcriptAvailable] means that media row
 * carries transcript text.
 */
@Serializable
data class DataTreeEntryDto(
    val name: String = "",
    val path: String = "",
    val kind: String = "file",
    val recordType: String? = null,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val url: String? = null,
    val sizeBytes: Long? = null,
    val transcriptAvailable: Boolean = false,
    val content: String? = null
) {
    val isFolder: Boolean get() = kind == "folder"
}

/**
 * `GET /data/tree?path=` — ONE level of the virtual tree (lazy: only this level's queries run).
 *
 * The root (`path=""`) is not a folder listing but the taxonomy chooser. [info] is populated on
 * record folders and null everywhere else. [truncated] is true when the 500-row per-level cap was
 * hit. [taxonomy] is which taxonomy the current path sits in, and null at the root.
 */
@Serializable
data class DataTreeDto(
    val path: String = "",
    val crumbs: List<DataCrumbDto> = emptyList(),
    val entries: List<DataTreeEntryDto> = emptyList(),
    val info: DataFolderInfoDto? = null,
    val truncated: Boolean = false,
    val taxonomies: List<DataTaxonomyDto> = emptyList(),
    val taxonomy: String? = null
)

/**
 * One file of a flattened subtree. [path] is relative to the requested folder and is what a zip
 * entry should be named.
 *
 * Exactly one of [content] (generated text, inline) and [url] (an object to fetch) is meaningful.
 * When [convertToMp4] is true the entry is audio that the SERVER will re-encode: fetch
 * `data/media/{mediaId}/download?format=mp4`, and only fall back to [url] (the original, named
 * [originalPath]) if that fails.
 */
@Serializable
data class DataManifestFileDto(
    val path: String = "",
    val url: String? = null,
    val originalPath: String? = null,
    val content: String? = null,
    val mediaId: String? = null,
    val mediaType: String? = null,
    val convertToMp4: Boolean = false
)

/**
 * `GET /data/manifest?path=&include=` — the flattened subtree below a path, for client-side zipping.
 * [truncated] is true when the walk hit its depth/file ceiling.
 */
@Serializable
data class DataManifestDto(
    val files: List<DataManifestFileDto> = emptyList(),
    val totalFiles: Int = 0,
    val totalMedia: Int = 0,
    val truncated: Boolean = false
)

// ---------------------------------------------------------------------------
// Map: WHERE the repository's records are.
// GET /map/points and GET /map/points/{key}/records.
//
// TWO LAYERS, NEVER ADDED TOGETHER. A record has two different true locations: where its craft
// COMES FROM (the ORIGIN layer, built from the subject's stated address, else the legacy free-text
// place through the server's town atlas) and where it was RECORDED (the CAPTURE layer, built from
// the device's GPS fix). One record can appear in both — its craft is from Bagru and it was
// documented at a Kharagpur workshop — so `summary.originRecords` and `summary.captureRecords` are
// two denominators over the same corpus. Adding them either double-counts that record or hides one
// of its two truths; the layer a pin belongs to is [MapPointDto.layer].
//
// Every field here has a default, including the ones the server always sends. These payloads are the
// only thing standing between a phone and a blank map, and a server that gains a summary field must
// not be able to turn the whole screen into a parse error.
// ---------------------------------------------------------------------------

/**
 * The five record buckets a pin folds, in the API's PLURAL vocabulary. A fixed five-key object, not
 * a map: the keys are the wire's own closed list, so naming them is what lets a caller read
 * `counts.artisans` instead of guessing at a string key that a typo would silently turn into null.
 *
 * Each defaults to 0 because a bucket the caller did not ask for is simply absent from the payload,
 * and "not counted" draws the same as "none here" on a pin that was never counting it.
 */
@Serializable
data class MapCountsDto(
    val artisans: Int = 0,
    val workshops: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0
)

/**
 * One pin. [key] is what identifies it to `GET /map/points/{key}/records`, and is opaque: it is one
 * of `nation:india`, `state:<State>`, `district:<State>|<District>` or
 * `capture:<cell size>:<lat cell>_<lon cell>`. It contains ':' and '|', so it is URL-encoded on the
 * way back (Retrofit does that — see `WorkshopRepositoryApi.mapPointRecords`). NEVER parse it to derive
 * a label: [label], [region], [state] and [district] are the server's own resolved names.
 *
 * [layer] is `ORIGIN` or `CAPTURE`. [precision] is `SUBJECT_PIN` | `MEASURED` | `TOWN` | `DISTRICT`
 * | `STATE` | `NATION` and [source] is `SUBJECT_PIN` | `STATED_ADDRESS` | `PLACE_TEXT` |
 * `DEVICE_FIX` — together they say how much of this position is a measurement and how much is a
 * lookup, which is the difference between "we stood here" and "we know the district".
 *
 * [latitude]/[longitude] are always sent for a point (the server cannot build one without a
 * position) and are the WEIGHTED MEAN of everything folded in, not the corner of a grid cell.
 *
 * ORIGIN-only: [places] — every finer place name and free-text spelling that folded in, longest
 * first, so grouping to a district moves that detail into the panel instead of losing it;
 * [pinnedRecords] — how many of [total] are positioned by a real coordinate; [fromPlaceText] — how
 * many reached this pin through the legacy prose column, i.e. which records to go and fill in.
 *
 * CAPTURE-only: [fixes] — how many separate GPS fixes this pin stands in for, so "317 records" is
 * not mistaken for one measurement; [spreadMetres] — how much ground they cover; [medianAccuracy] —
 * metres, null when no fix reported one. All three default so an ORIGIN pin, which omits them, is
 * not a decoding failure; branch on [layer], never on `fixes > 0`.
 */
@Serializable
data class MapPointDto(
    val key: String = "",
    val layer: String = "",
    val label: String = "",
    val region: String = "",
    val state: String? = null,
    val district: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val precision: String = "",
    val source: String = "",
    val total: Int = 0,
    val counts: MapCountsDto = MapCountsDto(),
    val places: List<String> = emptyList(),
    val pinnedRecords: Int = 0,
    val fromPlaceText: Int = 0,
    val fixes: Int = 0,
    val spreadMetres: Int = 0,
    val medianAccuracy: Double? = null,
    /**
     * The finer breakdown of this point, for the list's expandable row. See [MapPointChildDto].
     *
     * Empty at DISTRICT level and empty whenever the point holds only ONE child, both decided by the
     * server: a disclosure whose content restates the row it hangs under is a control that does nothing,
     * and a reader who opens one learns to stop opening them.
     */
    val children: List<MapPointChildDto> = emptyList(),
    /** True when a point holds more children than the server will send. Stated, never silent. */
    val childrenTruncated: Boolean = false
)

/**
 * One entry inside a point's expandable row — the same place, one administrative level down.
 *
 * WHY POINTS HAVE CHILDREN AT ALL. At NATION level the whole country is a single dot and therefore a
 * single row, and at STATE level a state is one dot and one row. "Tap a pin, the list scrolls to its row"
 * has nowhere to go at those levels: the row a reader lands on is the row they already had. So each point
 * carries the level below it — the states inside the nation, the districts inside the state — and the list
 * renders that as a disclosure. DISTRICT points have none, because a district is the finest unit an Indian
 * address names.
 *
 * [key] is a REAL point key at [level], which is what makes the disclosure navigable rather than
 * decorative: tapping one switches the map to [level] and selects that key, landing on exactly the pin the
 * Detail control would have drawn. [level] is `NATION` | `STATE` | `DISTRICT`, null on an older server.
 *
 * A CAPTURE point's children are tighter GPS clusters rather than administrative units, so their [label]
 * is the layer's own "Recorded here" and [region] is the coordinate — a measured fix has no administrative
 * name to borrow, and lending it one would erase the distinction between the two layers.
 */
@Serializable
data class MapPointChildDto(
    val key: String = "",
    val level: String? = null,
    val layer: String = "",
    val label: String = "",
    val region: String = "",
    val state: String? = null,
    val district: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val precision: String = "",
    val source: String = "",
    val total: Int = 0,
    val counts: MapCountsDto = MapCountsDto(),
    val fixes: Int = 0,
    val spreadMetres: Int = 0
)

/**
 * Records the map could NOT place, grouped by what their place column said. Blank, unreadable and
 * "prose the atlas cannot resolve, with no state picked" all land here as one honest bucket rather
 * than as a pin somewhere plausible. [label] is `No place recorded` when there was no text at all.
 *
 * This list must be shown. It is the count of records the map is silently not drawing, and hiding it
 * makes a map of 60 records look like a map of the whole repository.
 */
@Serializable
data class MapUnplacedDto(
    val label: String = "",
    val total: Int = 0,
    val counts: MapCountsDto = MapCountsDto()
)

/**
 * The one record the caller asked to see in context (`focusType` + `focusId`). The map still draws
 * the whole filtered corpus — "in context" is meant literally — and this only names which pins hold
 * the record, in [pointKeys], computed by the same ladder at the same level so a focus key always
 * matches a drawn pin. Usually two keys: its origin pin and its capture pin.
 *
 * [type] is the PLURAL bucket name here (`artisans`, ...), matching what the request asked for.
 */
@Serializable
data class MapFocusDto(
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val place: String? = null,
    val pointKeys: List<String> = emptyList(),
    /**
     * False when the current filters — the workshop scope above all — exclude this record, so its
     * [pointKeys] ring no drawn pin.
     *
     * REPORTED RATHER THAN ENFORCED, and that is the whole reason it exists. The server used to resolve
     * a focused record through the same narrowed clause the pins were built from and 404 when it missed,
     * which took the ENTIRE map response down: "show this record on the map" from anything older than
     * the most recent workshop produced an error screen instead of a map with one unringed pin.
     *
     * Defaults TRUE so an API that predates the field reads as it always behaved, where being out of
     * scope was not representable at all.
     */
    val inScope: Boolean = true
)

/**
 * How much of the structured address the corpus in play actually holds — the map's own quality,
 * reported so "why is this record at the state capital" has an answer a researcher can act on (go
 * and fill in the district, or drop a pin) instead of looking like a bug.
 *
 * [locations] is the denominator for all four: how many `Location` rows the filtered corpus touches.
 */
@Serializable
data class MapAddressCompletenessDto(
    val locations: Int = 0,
    val withState: Int = 0,
    val withDistrict: Int = 0,
    val withPincode: Int = 0,
    val withSubjectPin: Int = 0
)

/**
 * What the map is standing on. [records] is how many records the filters matched at all; [byType]
 * breaks that down over the same five buckets as a pin's counts.
 *
 * [originExcludes] names the buckets that CANNOT reach the origin layer — media has no place column
 * of its own, a photograph inherits the record it belongs to — so the asymmetry is stated rather
 * than left for a reader to notice. [captureTruncated] is true when more locations were in play than
 * the server would read fixes for, i.e. the capture layer is incomplete.
 *
 * [clusterKilometres] is the radius fixes were merged at for the current level, which is what a
 * "Recorded here" pin is really claiming. [anchoredDistricts] / [anchorPins] say how well the server
 * knows where districts ARE and how much of that is measured rather than seeded from the atlas;
 * [anchorsTruncated] means the anchor read hit its ceiling, so some districts fall back to coarser
 * positions.
 */
@Serializable
data class MapSummaryDto(
    val records: Int = 0,
    val byType: MapCountsDto = MapCountsDto(),
    val originRecords: Int = 0,
    val captureRecords: Int = 0,
    val unplacedRecords: Int = 0,
    val originExcludes: List<String> = emptyList(),
    val captureTruncated: Boolean = false,
    val clusterKilometres: Int = 0,
    val address: MapAddressCompletenessDto = MapAddressCompletenessDto(),
    val anchoredDistricts: Int = 0,
    val anchorPins: Int = 0,
    val anchorsTruncated: Boolean = false
)

/**
 * `GET /map/points` — every pin for the current filters, both layers, plus what would not place.
 *
 * [scope] is `all` (no filter narrowed anything), `filtered`, or `record` (a focus was requested).
 * [level] is the administrative unit the pins are GROUPED at — `NATION` | `STATE` | `DISTRICT` — and
 * [levels] is the vocabulary to build the level toggle from, served so the client's list and the
 * server's cannot drift apart. [types] is which buckets were counted, in the server's own order.
 *
 * [points] is already sorted: biggest first, ties by label.
 */
@Serializable
data class MapPointsDto(
    val scope: String = "",
    val level: String = "",
    val levels: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val points: List<MapPointDto> = emptyList(),
    val unplaced: List<MapUnplacedDto> = emptyList(),
    val focus: MapFocusDto? = null,
    val summary: MapSummaryDto = MapSummaryDto(),
    /**
     * The level every point's [MapPointDto.children] are keyed at — the level to switch the map to when a
     * reader drills into one. Null at DISTRICT, where there are no children, and null on an older server.
     *
     * Read from the server rather than re-derived here, so this client cannot hold a different idea of the
     * ladder than the server does.
     */
    val childLevel: String? = null
)

/**
 * One record behind a pin. [type] is the PLURAL bucket name (`artisans`, `workshops`, `products`,
 * `tools`, `media`) — the same vocabulary the request's `types` filter uses.
 *
 * Hand-picked columns, and that is the security property: no identity field is in this shape at any
 * rank, so a map cannot leak one however it renders a row.
 */
@Serializable
data class MapPointRecordDto(
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val place: String? = null,
    val craft: String? = null,
    val status: String = "",
    val createdAt: String? = null
)

/**
 * `GET /map/points/{key}/records` — the records behind ONE pin, fetched on demand so a pin can be
 * navigated FROM rather than only looked at.
 *
 * MUST BE REQUESTED WITH THE SAME FILTERS THE MAP WAS DRAWN WITH, including `level` and the workshop
 * scope. The key alone does not determine the answer: it names an administrative unit, and which
 * records sit in that unit is exactly what the filters decide. [truncated] is true when the
 * per-bucket cap was hit, so [total] is a floor, not the count.
 */
@Serializable
data class MapPointRecordsDto(
    val key: String = "",
    val items: List<MapPointRecordDto> = emptyList(),
    /**
     * How many rows are IN [items] — not how many records the pin holds.
     *
     * [total] is the same number under an older name, kept because clients read it. Do not compare
     * either against `MapPointDto.total`: that one is the pin's corpus count, this one is the length of
     * a list capped at [cap] PER RECORD TYPE, so a pin holding 317 files answers with 40 here. A client
     * that reads the two identically-named fields as the same quantity reports 277 missing records.
     * [truncated] is the field that actually says which situation this is.
     */
    val shown: Int = 0,
    val total: Int = 0,
    val cap: Int = 0,
    val truncated: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The DESIGNER tier: the roster that gates its sign-in, and the profile a report prints.
//
// TWO TABLES, TWO SHAPES, AND THEY ARE NOT THE SAME FACT — which is why there is no shared base
// type here even though both carry an `institution`. `DesignerRoster` is the ADMIN's statement that
// an individual is empanelled and may sign in at all; `DesignerProfile` is the DESIGNER's own
// statement of who they are, and it is the thing that gets typeset onto the cover of every report
// they generate. Folding the two into one DTO is how a screen ends up letting an admin rewrite
// somebody's biography, or letting a designer edit their own permission to log in. Neither is a
// thing this app may make possible, so the two travel separately all the way down.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One row of the institution's designer roster, as `GET /designers/roster` serves it.
 *
 * THE ROW USUALLY EXISTS BEFORE THE ACCOUNT DOES. That is the whole point of the table: an admin
 * empanels somebody by email, and the account provisions itself the first time that person signs in
 * with Google. So there is no user id here, [fullName] is whatever the admin typed rather than
 * anything an account confirmed, and [firstSeenAt] is null for exactly as long as the invitation is
 * outstanding — which is the one fact the roster screen exists to show. A null [firstSeenAt] must
 * never be rendered as "never" or as an error; it means "has not accepted yet".
 *
 * EVERY FIELD IS DEFAULTED, without exception. A field phone updates over the air and may be running
 * a build older than the server it is talking to; a non-defaulted field the server has not started
 * sending yet (or has stopped sending) makes kotlinx throw `MissingFieldException` and takes the
 * entire roster screen down over one column nobody was reading.
 */
@Serializable
data class DesignerRosterDto(
    val id: String = "",
    /** Lower-cased server-side on write. The join key to `User.email`. */
    val email: String = "",
    val fullName: String? = null,
    val institution: String? = null,
    val notes: String? = null,
    /**
     * False suspends sign-in WITHOUT deleting the history of the empanelment.
     *
     * Defaults TRUE so a server that predates the column — or a payload truncated by a proxy — reads
     * as "this person may sign in", which is the state a roster row is created in. Defaulting to
     * false would paint every row on the screen as suspended and invite an admin to "restore" people
     * who were never suspended.
     */
    val isActive: Boolean = true,
    val revokedAt: String? = null,
    /** Set the first time an account with this email signs in. Null = the invitation is outstanding. */
    val firstSeenAt: String? = null,
    /**
     * THERE IS NO `userId` HERE, and a field for one used to be — always null, because
     * `roster_payload` (backend/app/services/designers.py:107-121) has never sent one and the
     * `DesignerRoster` table has no user column to send. The roster screen's "Open designer profile"
     * action was rendered only `row.userId?.let { … }`, so it rendered for nobody and the admin
     * editor behind it was unreachable in the shipped APK.
     *
     * The account behind a row is resolved the way the web resolves it — by lower-cased EMAIL
     * against `GET /designers/directory`; see [DesignerDirectoryEntryDto] and
     * `accountsByEmail`. Do not put the field back: a nullable column that the only producer never
     * populates reads at every call site as "sometimes present", and that reading is what hid this.
     */
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val addedById: String? = null,
    /**
     * Who empanelled them, when the server joins it — which THIS server does not.
     *
     * Kept because it costs nothing and a deployment that starts sending it is then honoured
     * immediately, but the roster card must not depend on it: `roster_payload` sends [addedById]
     * alone, so a card that only read this name printed nothing at all. The screen falls back to
     * looking the id up in the directory it already fetched.
     */
    val addedByName: String? = null
)

/**
 * One account from `GET /designers/directory` — the accounts an admin may hand a workshop to.
 *
 * WHY THE ROSTER SCREEN NEEDS IT AT ALL. A roster row is keyed by EMAIL and carries no account id
 * (see [DesignerRosterDto]), while `/designers/{userId}/profile` can only be reached by an id. This
 * endpoint is the only join between the two, and the web's roster page does exactly the same thing
 * with it — `new Map(rows.map((row) => [row.email.toLowerCase(), row.id]))` in
 * `frontend/app/(protected)/admin/designers/page.tsx`. Both clients therefore resolve a row to a
 * person the same way, and neither fabricates an id from the email, which would send an admin to a
 * 404 on a control they were invited to tap.
 *
 * `includeSuspended=true` is what the roster screen asks for, and it must: the row an admin is
 * looking at is very often the SUSPENDED one — the designer who says they cannot sign in — and the
 * whole reason to open their profile from here is to check the empanelment details before restoring
 * them. The default (`false`) hides exactly those people.
 *
 * Narrow on purpose. The payload carries `role`, `institution`, `rosterId`, `rosterActive`,
 * `canSignIn`, `firstSeenAt` and `hasProfile` as well; `ignoreUnknownKeys` drops them. The roster
 * screen already has every one of those facts from the roster row itself, and a second copy of
 * "is this person suspended" arriving by a different route is two answers to one question.
 */
@Serializable
data class DesignerDirectoryEntryDto(
    val id: String = "",
    /** Compared LOWER-CASED. The roster lower-cases on write; `User.email` is not guaranteed to be. */
    val email: String = "",
    val name: String? = null
)

/**
 * `POST /designers/roster` — empanel somebody.
 *
 * The email is the only required fact and the only one that matters; everything else is the admin's
 * note to themselves about whom they added and why. [fullName] is what the roster screen shows while
 * the invitation is outstanding, because until the person signs in there is no account to read a
 * name from.
 */
@Serializable
data class DesignerRosterCreateBody(
    val email: String,
    val fullName: String? = null,
    val institution: String? = null,
    val notes: String? = null,
    val isActive: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// THE PLATFORM ALLOW-LIST: who may sign in AT ALL, and the queue of people asking to.
//
// NOT THE DESIGNER ROSTER ABOVE, THOUGH THEY SIT NEXT TO EACH OTHER AND READ ALIKE. `DesignerRoster`
// says who the institution recognises as a DESIGNER; `AccessRoster` says who may reach this
// application at all, whatever their tier. Two tables, two endpoints, two refusals with two
// different remedies — an admin who suspends the wrong one takes away something they did not mean
// to, and the person is told the wrong reason for it. Everything here carries the word `Access`.
//
// WHAT AN UNAUTHENTICATED STRANGER CAN PUT IN THIS TABLE IS THE EMAIL ADDRESS AND NOTHING ELSE. A
// PENDING row is written by the login endpoint only AFTER a password or a Google token has verified;
// it carries no display name from the identity provider and no free text at all, and one address is
// one row however many times it is tried. So the queue an admin reads on the phone is a list of
// addresses, dates and integers, with nowhere in it for a stranger to write a sentence pretending to
// come from the product. Do not add a field here that renders text an unauthenticated caller
// supplied; the server does not store one.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One row of the platform allow-list, as `GET /access/roster` serves it.
 *
 * FOUR STATES, IN [status]: `ACTIVE` admits; `PENDING` is somebody who proved an identity and was
 * turned away, recorded so an administrator can decide; `REJECTED` is an administrator's answer, and
 * it does NOT reopen when the person tries again (their next attempt bumps [attemptCount] and leaves
 * the status alone); `SUSPENDED` is access ended after it was granted.
 *
 * [joinedAt] IS THE "date of joining the platform" THE REQUIREMENT ASKS FOR, and the server writes
 * it once: somebody admitted in 2024, suspended, and restored this morning still joined in 2024. Do
 * not render it as "approved on" — an admin reading a joining date of last Tuesday draws the wrong
 * conclusion about every record that person created.
 *
 * EVERY FIELD IS DEFAULTED, for [DesignerRosterDto]'s reason: a handset updates over the air and may
 * be older than the server it is talking to, and one non-defaulted field the server has not started
 * sending makes kotlinx throw and takes the whole screen down over a column nobody was reading.
 */
@Serializable
data class AccessRosterDto(
    val id: String = "",
    /** Lower-cased server-side. The join key to `User.email`, and the only attacker-supplied value here. */
    val email: String = "",
    /** ADMIN-TYPED ONLY. Never a display name from Google — see the section note above. */
    val fullName: String? = null,
    /**
     * `ACTIVE` / `PENDING` / `REJECTED` / `SUSPENDED`.
     *
     * Defaults to PENDING rather than to ACTIVE, which is the opposite of [DesignerRosterDto.isActive]
     * and deliberately so: an unreadable roster row must not be drawn as somebody who may sign in.
     * The screen's actions read this, so failing towards "waiting for a decision" costs an admin one
     * extra look, where failing towards "admitted" would have them believe an unadmitted address is
     * already in.
     */
    val status: String = "PENDING",
    /** The tier the account is created at (or lifted to) on first sign-in. Null = the platform default. */
    val admitRole: String? = null,
    val joinedAt: String? = null,
    val requestedAt: String? = null,
    /** How many times this address has been refused. Rises on every attempt, including after a rejection. */
    val attemptCount: Int = 0,
    val lastAttemptAt: String? = null,
    val decidedAt: String? = null,
    val decidedById: String? = null,
    /** Stamped the first time an admitted address actually got in. Null = admitted, never arrived. */
    val firstSeenAt: String? = null,
    val notes: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val addedById: String? = null
)

/**
 * `GET /access/roster/pending-count` — THE NOTIFICATION, in the only channel either application has.
 *
 * There is no email sender and no push transport in this codebase, so "tell the admins somebody is
 * waiting" is a number on a screen they already open. Its own endpoint precisely so a badge can be
 * drawn without fetching a page of rows — on this client it rides the app-wide poll that already
 * runs while somebody is signed in, so it costs no timer of its own.
 *
 * [capReached] is not decoration. Past [capacity] the server stops RECORDING new requests and tells
 * the person to contact an administrator directly, so a queue that has stopped growing means either
 * nobody is asking or the product has stopped listening — and an admin cannot tell which without it.
 */
@Serializable
data class PendingAccessCountDto(
    val pending: Int = 0,
    val capacity: Int = 0,
    val capReached: Boolean = false
)

/**
 * `POST /access/roster` — admit an address by hand, before it has an account.
 *
 * ACTIVE IMMEDIATELY, not pending: an admin typing an address into the box IS the approval, and
 * there is nobody else for the request to be routed to. A duplicate answers 409 naming the existing
 * row rather than overwriting it — the row it would overwrite is usually the pending request that
 * records how long the person has been waiting.
 */
@Serializable
data class AccessRosterCreateBody(
    val email: String,
    val fullName: String? = null,
    /** Null means the platform default joining tier — the lowest rung. */
    val role: String? = null,
    val notes: String? = null
)

/**
 * `POST /access/roster/{id}/decision` — approve or refuse a waiting request.
 *
 * A DATA CLASS AND NOT A HAND-BUILT JsonObject, unlike [designerRosterUpdateJson]: this body is not
 * applied with `exclude_unset` semantics that a null could corrupt. `decision` is required, and the
 * server reads `role` only on APPROVE and only up to the deciding admin's own tier.
 */
@Serializable
data class AccessDecisionBody(
    /** "APPROVE" or "REJECT". Spelled by [AccessDecision] rather than typed at the call site. */
    val decision: String,
    val role: String? = null,
    val notes: String? = null
)

/** The two answers an administrator can give, so no call site spells them as strings. */
object AccessDecision {
    const val APPROVE = "APPROVE"
    const val REJECT = "REJECT"
}

/** The four states of an [AccessRosterDto], spelled once. Compared against [AccessRosterDto.status]. */
object AccessStatus {
    const val ACTIVE = "ACTIVE"
    const val PENDING = "PENDING"
    const val REJECTED = "REJECTED"
    const val SUSPENDED = "SUSPENDED"
}

/**
 * The signed-in designer's own profile, as `GET /designers/me/profile` serves it — every column of
 * `DesignerProfile` plus the identifiers the client may not write.
 *
 * These twenty values are typed once and copied into every report the designer generates, which is
 * the reason the screen that edits them is worth building at all: a cover page that says "Designer:"
 * and nothing after it is a document that cannot be submitted.
 *
 * [photoMediaId] and [signatureMediaId] are MEDIA IDS, resolved through `GET /media/{id}` exactly as
 * a stage field's photograph is — never URLs. A URL stored in a profile column would be a pre-signed
 * link that expires, so a report generated three months later would print a broken image and nothing
 * in the stored row would say why.
 */
@Serializable
data class DesignerProfileDto(
    val id: String = "",
    val userId: String = "",
    val displayName: String? = null,
    /** Written in the local script where the designer wants it that way; printed verbatim. */
    val localName: String? = null,
    val designation: String? = null,
    val institution: String? = null,
    val department: String? = null,
    val qualification: String? = null,
    val specialisation: String? = null,
    val experienceYears: Int? = null,
    /** The paragraph that appears as "Designer's profile" in stage 3 of every report. */
    val biography: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val addressLine: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val photoMediaId: String? = null,
    val signatureMediaId: String? = null,
    val empanelmentNo: String? = null,
    /** ISO-8601, and a STRING rather than a date — see [DesignerProfileUpdateBody.empanelmentDate]. */
    val empanelmentDate: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

/**
 * The body of `PUT /designers/{…}/profile`.
 *
 * NOT SENT THROUGH THE RETROFIT CONVERTER. It is encoded by [designerProfileUpdateJson] into a
 * [JsonObject] instead, and that is not a style choice — it is the only way this screen can clear a
 * field at all. `ApiClient`'s Json is configured `explicitNulls = false`, so a null property is
 * OMITTED from the wire body; the server applies the body with `exclude_unset`, so an omitted key
 * means "leave the stored value alone". Encoded the ordinary way, a designer who deleted the
 * contents of their "Department" box would watch the old department reappear on the next load, every
 * time, with no error and nothing on screen to blame. An explicit JSON `null` is what says "clear".
 *
 * The screen renders EVERY column, so sending every key on every save is correct: there is no field
 * this client left unshown whose value it could be trampling. A future PARTIAL editor must narrow
 * this encoder rather than reuse it.
 */
data class DesignerProfileUpdateBody(
    val displayName: String? = null,
    val localName: String? = null,
    val designation: String? = null,
    val institution: String? = null,
    val department: String? = null,
    val qualification: String? = null,
    val specialisation: String? = null,
    /** Bounded 0..70 by the server, matching the registry's `designerExperience` field it prefills. */
    val experienceYears: Int? = null,
    val biography: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val addressLine: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val photoMediaId: String? = null,
    val signatureMediaId: String? = null,
    val empanelmentNo: String? = null,
    /**
     * An ISO-8601 date STRING, matching every other date this API accepts (see
     * `DesignWorkshopCreateBody.startDate`). A body that took a real date here and a string there
     * would 422 exactly one of the two screens, for a reason its author could not see from either.
     */
    val empanelmentDate: String? = null
)

/**
 * A text box's contents as the API should read them: the trimmed string, or an explicit JSON `null`
 * when the box is empty.
 *
 * The blank-to-null fold happens HERE and not on the server, because `""` and `null` are different
 * instructions to a nullable column and the difference shows up in the printed report: an empty
 * string satisfies "is this column set?" while carrying nothing to typeset, so a cover page ends up
 * with a designation line that is present, blank, and impossible to distinguish from a layout bug.
 */
private fun textOrNull(value: String?): JsonElement {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) JsonNull else JsonPrimitive(trimmed)
}

/**
 * [DesignerProfileUpdateBody] as the wire body, with EVERY key present and cleared fields carrying
 * an explicit JSON `null`.
 *
 * See the note on [DesignerProfileUpdateBody] for why this cannot go through the Retrofit converter:
 * `explicitNulls = false` would drop the very keys that mean "clear this", and the server's
 * `exclude_unset` would then leave the old value in place. The failure is silent and only visible on
 * the next load, which is far enough from the action that nobody connects the two.
 */
fun designerProfileUpdateJson(body: DesignerProfileUpdateBody): JsonObject = buildJsonObject {
    put("displayName", textOrNull(body.displayName))
    put("localName", textOrNull(body.localName))
    put("designation", textOrNull(body.designation))
    put("institution", textOrNull(body.institution))
    put("department", textOrNull(body.department))
    put("qualification", textOrNull(body.qualification))
    put("specialisation", textOrNull(body.specialisation))
    put("experienceYears", body.experienceYears?.let(::JsonPrimitive) ?: JsonNull)
    put("biography", textOrNull(body.biography))
    put("phone", textOrNull(body.phone))
    put("email", textOrNull(body.email))
    put("website", textOrNull(body.website))
    put("addressLine", textOrNull(body.addressLine))
    put("city", textOrNull(body.city))
    put("state", textOrNull(body.state))
    put("pincode", textOrNull(body.pincode))
    put("photoMediaId", textOrNull(body.photoMediaId))
    put("signatureMediaId", textOrNull(body.signatureMediaId))
    put("empanelmentNo", textOrNull(body.empanelmentNo))
    put("empanelmentDate", textOrNull(body.empanelmentDate))
}

/**
 * The body of `PATCH /designers/roster/{id}` — a correction, or a restore.
 *
 * BUILT KEY BY KEY RATHER THAN FROM A DATA CLASS, and the asymmetry with the profile encoder above is
 * deliberate rather than an oversight. [email] is the row's unique join key to `User.email`, and the
 * server applies this body with `exclude_unset`, so a present-and-null `email` would be read as
 * "clear the address" — which on the one column that decides whether a person can sign in is not a
 * risk worth carrying for the convenience of one uniform encoder. It is therefore sent only when the
 * admin actually changed it to something non-blank. The three descriptive columns DO accept an
 * explicit null, because emptying the "Notes" box has to be able to mean emptying the notes.
 *
 * [isActive] is sent only when [suspendOrRestore] is non-null. Suspension is expressed through this
 * key as well as through `DELETE /designers/roster/{id}` because restoring somebody is not an act a
 * DELETE can express, and a screen that could suspend but not restore would push admins toward
 * deleting the row — destroying the record that the person was ever recognised.
 */
/**
 * The body of `PATCH /access/roster/{id}` — correcting the admin-typed columns of an allow-list row.
 *
 * BUILT KEY BY KEY, FOR THE SAME REASON [designerProfileUpdateJson] IS: this client is configured
 * with `explicitNulls = false`, so a data class carrying `fullName = null` would encode to `{}` and
 * the server's `exclude_unset` would leave the old value in place. Emptying the Notes box has to be
 * able to mean emptying the notes, and a silent no-op that only shows up on the next load is the
 * worst possible version of that.
 *
 * THERE IS NO `email` KEY AND THERE MUST NEVER BE ONE. The server's PATCH does not accept it, and
 * that is deliberate on both sides: the address IS the gate, so an admin fixing a spelling must not
 * be able to hand one person's admission to a different mailbox — the loser would simply stop being
 * able to sign in, while the row on screen still said they may. Moving an admission to a new address
 * is `follow_email_change`, which happens when an ADMIN edits the account's email, not this.
 *
 * THERE IS NO `status` KEY EITHER. Every transition goes through the decision or suspend endpoint so
 * that the stamps that accompany it — who decided, when, the joining date — are written by one piece
 * of code that cannot forget them.
 */
fun accessRosterUpdateJson(
    fullName: String? = null,
    role: String? = null,
    notes: String? = null
): JsonObject = buildJsonObject {
    put("fullName", textOrNull(fullName))
    // Null here means "the platform default joining tier", which is a real and choosable answer —
    // the picker's first option — so it is sent as an explicit null rather than omitted.
    put("role", textOrNull(role))
    put("notes", textOrNull(notes))
}

fun designerRosterUpdateJson(
    email: String? = null,
    fullName: String? = null,
    institution: String? = null,
    notes: String? = null,
    suspendOrRestore: Boolean? = null
): JsonObject = buildJsonObject {
    email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", JsonPrimitive(it)) }
    put("fullName", textOrNull(fullName))
    put("institution", textOrNull(institution))
    put("notes", textOrNull(notes))
    suspendOrRestore?.let { put("isActive", JsonPrimitive(it)) }
}

// ------------------------------------------------------------------------------------------------
// CUSTOM QUESTIONNAIRES — the form a designer authored themselves, at /api/questionnaires (PLURAL).
//
// EVERY TYPE BELOW CARRIES A `Custom` PREFIX, and that is not a style tic. This file already holds
// `QuestionnaireSectionDto`, `QuestionnaireQuestionDto` and `QuestionnaireResponseRequest`, which
// describe the ONE global artisan questionnaire served from /api/questionnaire (SINGULAR) — a
// different record with the same word for a name, kept apart on the server all the way down to
// separate tables (see the module docstring on backend/app/api/routes/questionnaire_forms.py). The
// two shapes are close enough to compile against each other in several places: both have `id`,
// `prompt`, `sortOrder`, `isActive`. Picking the wrong one out of an import list would therefore
// build cleanly and fail at runtime as a deserialization error on a screen a designer is standing in
// a room using. The prefix is what makes the wrong choice visible while it is being made.
//
// A designer builds the form in the .xlsx pro-forma on a laptop and answers it on this handset, so
// the upload half of the API is deliberately absent from this client — see the notes on the screens.
// ------------------------------------------------------------------------------------------------

/** One row of `GET /questionnaires` — the list, without the sections or the answers. */
@Serializable
data class CustomQuestionnaireSummaryDto(
    val id: String,
    val title: String = "",
    val description: String? = null,
    val ownerId: String? = null,
    val ownerName: String? = null,
    val designWorkshopId: String? = null,
    val designWorkshopTitle: String? = null,
    /**
     * False means DEACTIVATED, which is what this API has instead of a delete: the questionnaire
     * leaves every list and every dropdown and its answers stay. A client that read this as "deleted"
     * and hid the row outright would leave a fortnight of somebody's fieldwork unreachable from the
     * app that recorded it.
     */
    val isActive: Boolean = true,
    val version: Int = 1,
    val sourceFilename: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class CustomQuestionDto(
    val id: String,
    val prompt: String = "",
    val helpText: String? = null,
    val isRequired: Boolean = false,
    val sortOrder: Int = 0,
    /** False = RETIRED. It keeps the answers already recorded against it and must never collect new ones. */
    val isActive: Boolean = true,
    val retiredAt: String? = null,
    /** Set when this question was reworded after it had answers: the id of the wording that replaced it. */
    val supersededById: String? = null,
    /**
     * Whether anybody has answered this question yet, and therefore the single fact the editor has to
     * consult BEFORE the designer types. A question with answers cannot be reworded in place (the
     * server supersedes it instead) and cannot be deleted (it retires). Offering a Delete that the
     * API will convert into something else, and only saying so afterwards, is how a designer comes to
     * believe the app threw away work it in fact kept.
     */
    val hasAnswers: Boolean = false,
)

@Serializable
data class CustomSectionDto(
    val id: String,
    val code: String = "",
    val title: String = "",
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val questions: List<CustomQuestionDto> = emptyList(),
)

@Serializable
data class CustomAnswerDto(
    val id: String? = null,
    val questionId: String = "",
    val answerText: String? = null,
    val notes: String? = null,
    val answeredById: String? = null,
    val updatedAt: String? = null,
)

/**
 * One SITTING: a single filled-in copy of the questionnaire.
 *
 * [source] is `"APP"` for a sitting started on a client and `"UPLOAD"` for one the parser lifted out
 * of an answer column in the spreadsheet. Both are ordinary sittings with ordinary answers — the
 * value is worth showing, because it tells a designer where an answer they did not type came from,
 * but nothing in this app may branch on it.
 */
@Serializable
data class CustomEntryDto(
    val id: String,
    val title: String = "",
    val respondentName: String? = null,
    val source: String? = null,
    val notes: String? = null,
    val createdAt: String? = null,
    val createdById: String? = null,
    val createdByName: String? = null,
    val answers: List<CustomAnswerDto> = emptyList(),
)

/**
 * One whole questionnaire: the form, its sittings and their answers, from `GET /questionnaires/{id}`.
 *
 * WHAT `includeRetired` CHANGES, because it is the difference between two screens rather than a
 * detail: asked for, retired questions and retired sections are present in [sections] — which is what
 * the read/edit screen needs, since a retired question still has answers hanging off it and they have
 * to be readable. Not asked for, they are absent, which is what the ANSWER screen needs: a retired
 * question offered for a new answer is a wording the designer deliberately replaced quietly carrying
 * on gathering evidence, and the server refuses the save with a 422 anyway.
 */
@Serializable
data class CustomQuestionnaireDto(
    val id: String,
    val title: String = "",
    val description: String? = null,
    val ownerId: String? = null,
    val designWorkshopId: String? = null,
    val isActive: Boolean = true,
    val version: Int = 1,
    val sourceFilename: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val sections: List<CustomSectionDto> = emptyList(),
    val questionCount: Int = 0,
    val entries: List<CustomEntryDto> = emptyList(),
)

/** `GET /questionnaires/options` — the attach-to-a-workshop dropdown, id and label and nothing else. */
@Serializable
data class CustomQuestionnaireOptionDto(
    val id: String,
    val title: String = "",
    val version: Int = 1,
    val designWorkshopId: String? = null,
    val attachedElsewhere: Boolean = false,
)

@Serializable
data class CustomQuestionnaireCreateBody(
    val title: String,
    val description: String? = null,
    val designWorkshopId: String? = null,
)

@Serializable
data class CustomSectionCreateBody(
    val title: String,
    /** Left null the server derives a stable code from the title, which is what the pro-forma does. */
    val code: String? = null,
    val sortOrder: Int? = null,
)

/**
 * A section's title and position. Safe as a TYPED body — unlike the question and questionnaire
 * patches below — because none of these three columns is meaningfully nullable: there is no "clear
 * the title" instruction to express, so nothing here needs to send an explicit JSON null past a
 * converter configured with `explicitNulls = false`.
 */
@Serializable
data class CustomSectionPatchBody(
    val title: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null,
)

@Serializable
data class CustomQuestionCreateBody(
    val prompt: String,
    val helpText: String? = null,
    /**
     * DECLARED WITHOUT A KOTLIN DEFAULT, deliberately. The Retrofit converter is configured
     * `encodeDefaults = false` (ApiClient.kt), so `isRequired: Boolean = false` would be dropped from
     * the body whenever it was false — which is harmless here only by accident, and stops being
     * harmless the moment anybody copies this shape into a PATCH. No default, always encoded.
     */
    val isRequired: Boolean,
    val sortOrder: Int? = null,
)

@Serializable
data class CustomEntryCreateBody(
    val title: String? = null,
    val respondentName: String? = null,
    val notes: String? = null,
)

@Serializable
data class CustomEntryPatchBody(
    val title: String? = null,
    val respondentName: String? = null,
    val notes: String? = null,
)

@Serializable
data class CustomAnswerInputBody(
    val questionId: String,
    /**
     * OMITTED WHEN NULL, which the server reads back as null — and that is exactly the instruction
     * "the designer cleared this box". The converter drops null fields, Pydantic's own default for
     * this field is null, so the round trip survives the omission. Do not "fix" this by sending an
     * empty string: `""` is stored as an answer, and the edit-after-answers rule counts a blank
     * answer as no answer, so the two would drift apart on the one question it matters for.
     */
    val answerText: String? = null,
    val notes: String? = null,
)

@Serializable
data class CustomAnswerBatchBody(
    /** No default, so an empty batch is still sent as `{"answers": []}` rather than as `{}`. */
    val answers: List<CustomAnswerInputBody>,
)

@Serializable
data class CustomAnswerSaveCountsDto(
    val created: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
)

@Serializable
data class CustomAnswerSaveResultDto(
    val saved: CustomAnswerSaveCountsDto = CustomAnswerSaveCountsDto(),
    /** Every answer now on the sitting, not only the ones just sent. */
    val answers: List<CustomAnswerDto> = emptyList(),
)

/**
 * What `PATCH`/`DELETE` on one question actually did.
 *
 * [action] is one of `updated`, `superseded`, `retired`, `deleted` — FOUR outcomes from two verbs,
 * which is why this is a 200 with a body rather than a 204. [detail] is the server's own sentence
 * explaining a supersede or a retire, written to be shown verbatim; a client that summarised it into
 * "Saved" would leave a designer whose six reworded questions came back as six NEW questions with no
 * way to find out that had happened, or that their answers were safe.
 */
@Serializable
data class CustomQuestionEditResultDto(
    val action: String = "",
    val questionId: String? = null,
    val replacementId: String? = null,
    val detail: String? = null,
    val questionnaire: CustomQuestionnaireDto? = null,
)

/**
 * The body of `PATCH /questionnaires/{id}` — rename, re-describe, attach to a workshop, deactivate.
 *
 * A JsonObject and not a data class, for the reason spelled out on [designerProfileUpdateJson]:
 * `designWorkshopId` is the one field here that is meaningfully NULLABLE, because sending an explicit
 * null is how a questionnaire is DETACHED from its workshop. The converter's `explicitNulls = false`
 * would drop that key, the server applies the body with `exclude_unset=True`, and the detach would
 * therefore return 200 having changed nothing — a silent no-op the designer only discovers when the
 * questionnaire is still hanging off the wrong workshop a week later.
 *
 * [changeWorkshop] is what separates "leave the attachment alone" from "detach it": the key is
 * written only when the caller says it is deciding the attachment, and [designWorkshopId] blank then
 * means detach.
 */
fun customQuestionnaireUpdateJson(
    title: String? = null,
    description: String? = null,
    changeDescription: Boolean = false,
    designWorkshopId: String? = null,
    changeWorkshop: Boolean = false,
    isActive: Boolean? = null,
): JsonObject = buildJsonObject {
    // Sent only when non-blank. `title` has `min_length=1` on the server, so a present-and-empty
    // title is a 422 on a request the designer thinks is a rename of something else entirely.
    title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", JsonPrimitive(it)) }
    if (changeDescription) put("description", textOrNull(description))
    if (changeWorkshop) put("designWorkshopId", textOrNull(designWorkshopId))
    isActive?.let { put("isActive", JsonPrimitive(it)) }
}

/**
 * The body of `PATCH /questionnaires/{id}/questions/{questionId}`.
 *
 * A JsonObject for the same reason as the questionnaire patch above, on a different field: the
 * server reads `helpText` out of `model_fields_set`, so clearing the help text needs the key present
 * and null. A typed body would silently leave the old hint under a question the designer just
 * emptied.
 *
 * [prompt] is the field that can turn this request into something other than an edit. Sent on a
 * question that already has answers, the server SUPERSEDES rather than overwrites — see
 * [CustomQuestionEditResultDto]. It is therefore sent only when the caller actually changed the
 * wording, so that ticking "required" on an answered question is not read as a rewording of it.
 */
fun customQuestionUpdateJson(
    prompt: String? = null,
    helpText: String? = null,
    changeHelpText: Boolean = false,
    isRequired: Boolean? = null,
    sortOrder: Int? = null,
): JsonObject = buildJsonObject {
    prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { put("prompt", JsonPrimitive(it)) }
    if (changeHelpText) put("helpText", textOrNull(helpText))
    isRequired?.let { put("isRequired", JsonPrimitive(it)) }
    sortOrder?.let { put("sortOrder", JsonPrimitive(it)) }
}

// ---------------------------------------------------------------------------------------------
// A designer's OWN provider keys — `GET /ai/providers`, `GET|PUT|DELETE /me/ai-keys[/…]`
//
// SEPARATE FROM [ManagedSecretDto] AND ITS OPPOSITE IN EVERY RESPECT. That one describes the
// DEPLOYMENT's keys, is master-admin only, and has a reveal endpoint because a master admin
// sometimes has to compare a stored key against a provider dashboard. These describe ONE PERSON's
// own key, billed to their own card at their own provider, and there is deliberately no reveal
// route at all — nobody, administrator included, has any business reading somebody else's personal
// credential. The last four characters are the most this app can ever show.
// ---------------------------------------------------------------------------------------------

/**
 * One model a designer can choose, and the honest list of what it can be used for.
 *
 * [tasks] IS NOT DECORATION AND MUST BE RENDERED. It carries the enum names the server uses —
 * PROOFREAD, EXPAND, SUMMARISE, TRANSLATE, TRANSCRIBE, CAPTION — and it is how a designer learns
 * BEFORE choosing that, for instance, no Claude model can transcribe audio. Dropping it from the
 * screen would let somebody paste a Claude key believing their recordings were now on their own
 * account, and find out otherwise from a bill that never arrives.
 */
@Serializable
data class AiModelDto(
    val id: String,
    val label: String = "",
    val note: String = "",
    val tasks: List<String> = emptyList(),
    /** Indicative USD per million tokens. Null for models the provider does not price per token. */
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null
)

/** One provider: how to get a key, what it costs, and which models it offers. */
@Serializable
data class AiProviderDto(
    val provider: String,
    val label: String = "",
    /** What a key from this provider starts with, checked before an obviously-wrong paste is sent. */
    val keyPrefix: String? = null,
    val consoleUrl: String = "",
    val pricingUrl: String = "",
    /** The accordion: how to get a key, one action per step, in the order they will be done. */
    val howTo: List<String> = emptyList(),
    val defaultModel: String = "",
    val models: List<AiModelDto> = emptyList()
)

/**
 * `GET /ai/providers`.
 *
 * [pricesCheckedOn] TRAVELS WITH EVERY PRICE THIS APP PRINTS. The figures go stale — providers
 * re-price, and some of the current rates are introductory — and a stale price shown as current is
 * a small lie told to somebody deciding how to spend their own money.
 */
@Serializable
data class AiCatalogueDto(
    val pricesCheckedOn: String = "",
    val tasks: List<String> = emptyList(),
    val providers: List<AiProviderDto> = emptyList()
)

/**
 * One provider row for the signed-in person. Carries no plaintext, ever — see the block comment
 * above on why there is no reveal.
 */
@Serializable
data class UserAiKeyDto(
    val provider: String,
    val label: String = "",
    /** True when a key is stored AND can still be decrypted. */
    val configured: Boolean = false,
    /** A stored key the server can no longer decrypt: the owner must paste it again. */
    val unreadable: Boolean = false,
    val hint: String? = null,
    val model: String = "",
    /** False when the saved model is no longer one this app offers — the row says so rather than
     *  silently correcting it, because the designer's own choice is what is being overridden. */
    val modelKnown: Boolean = true,
    val lastStatus: String = "UNKNOWN",
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
    val updatedAt: String? = null
)

/** `PUT /me/ai-keys/{provider}`. Both fields are optional: sending only [model] changes the model
 *  without making somebody find their key again, which is what stops a UI teaching people to keep
 *  a credential somewhere convenient and less safe. */
@Serializable
data class UserAiKeySetBody(
    val key: String? = null,
    val model: String? = null
)
