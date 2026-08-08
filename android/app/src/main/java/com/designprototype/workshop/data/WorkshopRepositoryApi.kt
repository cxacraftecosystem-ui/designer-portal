package com.designprototype.workshop.data

// THE ONE TYPE THIS LAYER BORROWS FROM THE UI PACKAGE, and the direction is backwards on purpose:
// the consolidated-questionnaire DTOs were declared beside their screen in
// ui/ConsolidatedQuestionnaireScreen.kt while this file and ApiModels.kt were being edited
// concurrently, so re-declaring them here would give the app two incompatible spellings of one wire
// format. If they are ever moved into ApiModels.kt, this import is the only line to delete.
import com.designprototype.workshop.ui.ConsolidatedQuestionnaireDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface WorkshopRepositoryApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("auth/login")
    suspend fun googleLogin(@Body body: GoogleLoginRequest): TokenResponse

    @GET("me")
    suspend fun me(): UserDto

    @GET("reference/address")
    suspend fun addressReference(): AddressReferenceDto

    @GET("dashboard/stats")
    suspend fun dashboardStats(): DashboardStats

    @GET("users")
    suspend fun users(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100
    ): PageResponse<UserDto>

    @PATCH("users/{id}")
    suspend fun updateUser(
        @Path("id") id: String,
        @Body body: UserUpdateRequest
    ): UserDto

    @GET("users/directory")
    suspend fun userDirectory(): List<UserDto>

    @GET("review/pending")
    suspend fun pendingReviews(): PendingReviewListDto

    @POST("app/release")
    suspend fun publishAppRelease(@Body body: AppReleasePublishRequest): AppReleaseDto

    @GET("app/release/latest")
    suspend fun latestAppRelease(): AppReleaseDto

    @GET("settings")
    suspend fun appSettings(): AppSettingDto

    @PUT("settings")
    suspend fun updateAppSettings(@Body body: AppSettingUpdateRequest): AppSettingDto

    @GET("feedback/me")
    suspend fun myFeedback(): FeedbackDto

    @PUT("feedback/me")
    suspend fun upsertMyFeedback(@Body body: FeedbackUpsertRequest): FeedbackDto

    @GET("feedback")
    suspend fun allFeedback(): List<FeedbackDto>

    @POST("review/{type}/{id}/approve")
    suspend fun approveRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

    @POST("review/{type}/{id}/reject")
    suspend fun rejectRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

    // Send back to the creator with mandatory comments (status NEEDS_REVISION). A blank `notes` is a
    // 422 — the whole point is that the creator is told what to fix.
    @POST("review/{type}/{id}/revise")
    suspend fun reviseRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

    // Correct a record's field values from the review queue instead of bouncing it back. Leaves the
    // status untouched unless `approve` is set. See [ReviewEditRequest] for the refused keys.
    @POST("review/{type}/{id}/edit")
    suspend fun editReviewedRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewEditRequest
    ): JsonElement

    @GET("artisans")
    suspend fun artisans(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        // The shared workshop scope, plural. BROADER than the singular `workshopId` filter the form
        // pickers use: it also counts an artisan who merely SAT IN an interview taken at the
        // workshop, so this list and the completion matrix cannot disagree about who was there.
        @Query("workshopIds") workshopIds: String? = null,
        // WHO RECORDED THE ROW — see the block comment on [WorkshopRepository.artisans] for why
        // filtering this on the SERVER is the only correct way to build a "my records" list.
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ArtisanDto>

    @GET("crafts")
    suspend fun crafts(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<CraftDto>

    @POST("artisans")
    suspend fun createArtisan(@Body body: ArtisanCreateRequest): ArtisanDto

    // Pre-flight duplicate check for the artisan form's Aadhaar field. Declared BEFORE `artisan(id)`
    // only for readability — Retrofit matches on the literal path, so "lookup/aadhaar" can never be
    // swallowed by the "{id}" route the way a server-side router would.
    @GET("artisans/lookup/aadhaar")
    suspend fun lookupArtisanByAadhaar(@Query("number") number: String): AadhaarLookupDto

    @GET("artisans/{id}")
    suspend fun artisan(@Path("id") id: String): ArtisanDetailDto

    @PATCH("artisans/{id}")
    suspend fun updateArtisan(@Path("id") id: String, @Body body: ArtisanCreateRequest): ArtisanDetailDto

    @GET("artisans/{id}/questionnaire")
    suspend fun artisanQuestionnaire(@Path("id") id: String): ArtisanQuestionnaireDto

    @POST("crafts")
    suspend fun createCraft(@Body body: CraftCreateRequest): CreatedRecordDto

    @GET("crafts/{id}")
    suspend fun craft(@Path("id") id: String): CraftDto

    @PATCH("crafts/{id}")
    suspend fun updateCraft(@Path("id") id: String, @Body body: CraftCreateRequest): CraftDto

    @GET("products")
    suspend fun products(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("artisanId") artisanId: String? = null,
        @Query("artisanName") artisanName: String? = null,
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ProductDetailDto>

    @GET("products/{id}")
    suspend fun product(@Path("id") id: String): ProductDetailDto

    @PATCH("products/{id}")
    suspend fun updateProduct(@Path("id") id: String, @Body body: ProductCreateRequest): ProductDetailDto

    @GET("tools")
    suspend fun tools(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ToolDetailDto>

    @GET("tools/{id}")
    suspend fun tool(@Path("id") id: String): ToolDetailDto

    @PATCH("tools/{id}")
    suspend fun updateTool(@Path("id") id: String, @Body body: ToolCreateRequest): ToolDetailDto

    @GET("tools/{id}/artisans")
    suspend fun toolArtisans(@Path("id") id: String): List<ArtisanDto>

    @POST("tools/{id}/artisans")
    suspend fun assignToolArtisans(@Path("id") id: String, @Body body: ToolArtisanAssignRequest): List<ArtisanDto>

    @DELETE("tools/{id}/artisans/{artisanId}")
    suspend fun unassignToolArtisan(@Path("id") id: String, @Path("artisanId") artisanId: String)

    @GET("workshops")
    suspend fun workshops(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<WorkshopDetailDto>

    @GET("workshops/{id}")
    suspend fun workshop(@Path("id") id: String): WorkshopDetailDto

    @PATCH("workshops/{id}")
    suspend fun updateWorkshop(@Path("id") id: String, @Body body: WorkshopCreateRequest): WorkshopDetailDto

    // Pre-flight for a record form: what would submitting into this workshop mean for me? Reports
    // only — it never 403s, so a caller must treat a failure as "no answer", never as "refused".
    @GET("workshops/{id}/submission-check")
    suspend fun workshopSubmissionCheck(@Path("id") id: String): WorkshopSubmissionCheckDto

    // Which records name NO workshop, and where each one's own evidence points. Admin-only, a pure read,
    // and the preview the button below acts on. See [WorkshopMappingPlanDto].
    //
    // Declared beside `workshops/{id}` and it does not collide: FastAPI registers the literal
    // `/workshops/unmapped` before the parameterised route, so it is never read as a workshop whose id is
    // the word "unmapped".
    @GET("workshops/unmapped")
    suspend fun unmappedRecords(): WorkshopMappingPlanDto

    // File every unassigned record whose evidence names exactly one workshop. NO BODY — the server
    // re-derives the plan rather than trusting one sent back, so this client cannot ask for an arbitrary
    // row to be moved to an arbitrary workshop. Idempotent: it only ever fills an empty column.
    @POST("workshops/unmapped/map")
    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto

    @Multipart
    @POST("media/analyze-measurement")
    suspend fun analyzeMeasurement(
        @Part file: okhttp3.MultipartBody.Part,
        @Query("dimension") dimension: String? = null
    ): AnalyzeMeasurementResponse

    @POST("media/presign")
    suspend fun presignMedia(@Body body: MediaPresignRequest): MediaPresignResponse

    @POST("media/multipart/create")
    suspend fun createMultipart(@Body body: MultipartCreateRequest): MultipartCreateResponse

    @POST("media/multipart/presign-parts")
    suspend fun presignMultipartParts(@Body body: MultipartPresignPartsRequest): MultipartPresignPartsResponse

    @POST("media/multipart/complete")
    suspend fun completeMultipart(@Body body: MultipartCompleteRequest): MultipartCompleteResponse

    @POST("media/multipart/abort")
    suspend fun abortMultipart(@Body body: MultipartAbortRequest): JsonElement

    @POST("media/complete")
    suspend fun completeMedia(@Body body: MediaCompleteRequest): MediaFileDto

    /**
     * [completeMedia] with the endpoint's optional `checksum` key added to the body. Retrofit binds a
     * body type per method, so carrying that one extra key needs its own declaration; the caller
     * encodes [MediaCompleteRequest] and adds the key, so the two bodies cannot drift apart.
     */
    @POST("media/complete")
    suspend fun completeMediaChecksummed(@Body body: JsonObject): MediaFileDto

    @DELETE("media/object")
    suspend fun deleteMediaObject(@Query("objectKey") objectKey: String)

    @DELETE("media/{id}")
    suspend fun deleteMedia(@Path("id") id: String)

    @GET("media/{id}")
    suspend fun getMedia(@Path("id") id: String): MediaFileDto

    @GET("media")
    suspend fun media(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("linkedRecordType") linkedRecordType: String? = null,
        @Query("linkedRecordId") linkedRecordId: String? = null,
        // `uploadedBy`, NOT `createdBy`. A media row's owner column is `uploadedById` while every
        // record's is `createdById`, and the query key follows the column on both sides of the wire —
        // spelling this one `createdBy` would be silently ignored by the API and hand back the whole
        // repository, which is the exact failure the owner filter exists to prevent.
        @Query("uploadedBy") uploadedBy: String? = null
    ): PageResponse<MediaFileDto>

    // The media processing queue: what became of the transcription job an audio upload enqueued.
    //
    // Open to every signed-in account, but SCOPED SERVER-SIDE — an admin sees every job, everyone
    // else sees only the jobs they requested — so no client-side rank check belongs on this call.
    @GET("media/jobs")
    suspend fun mediaJobs(
        @Query("statusFilter") statusFilter: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): PageResponse<MediaProcessingJobDto>

    // Admin-only (require_admin): put a job back to QUEUED with its lock and error cleared, so the
    // next drain re-runs it. Deliberately NOT ownership-based — the uploader of a failed file may
    // see the failure and still not be the account that may re-run it.
    @POST("media/jobs/{id}/retry")
    suspend fun retryMediaJob(@Path("id") id: String): MediaProcessingJobDto

    // Admin-only (require_admin): drain up to `limit` jobs now instead of waiting for the worker's
    // next tick. TRANSCRIPTION jobs may still be skipped — they only run inside the off-peak window
    // or while the server is idle, and never during a provider rate-limit cooldown.
    @POST("media/jobs/process")
    suspend fun processMediaJobs(@Query("limit") limit: Int? = null): MediaQueueRunDto

    // Admin-only: media whose parent record was deleted (tag columns survive, typed FK nulled) — and
    // the action to re-attach such a file to an existing record so it reappears under it.
    @GET("media/orphans")
    suspend fun orphanMedia(): List<MediaFileDto>

    @POST("media/{id}/relink")
    suspend fun relinkMedia(@Path("id") id: String, @Body body: MediaRelinkRequest): MediaFileDto

    // AI transcript refinement (gpt-4o-mini): turn a raw transcript into a clean interviewer/
    // interviewee conversation, optionally translated to English. Billable — gated behind a cost
    // confirmation in the UI.
    @POST("media/{id}/refine-transcript")
    suspend fun refineTranscript(@Path("id") id: String, @Body body: TranscriptRefineRequest): TranscriptRefineResponse

    // Admin/master-admin: transcribe this audio file now, applying the settings-page transcription
    // mode, bypassing the queue + off-peak window. Returns the updated media row.
    @POST("media/{id}/transcribe-now")
    suspend fun transcribeNow(@Path("id") id: String): MediaFileDto

    // Save an (approved, AI-refined) transcript in place of the stored one. Uploader or admin only.
    @POST("media/{id}/transcript")
    suspend fun setTranscript(@Path("id") id: String, @Body body: TranscriptUpdateRequest): MediaFileDto

    @GET("export/dataset")
    suspend fun datasetManifest(): DatasetManifestDto

    // Styled relational report of the whole dataset (or a subtree) as a .xlsx workbook. Streamed so
    // large workbooks aren't buffered entirely in memory before being written to Downloads.
    @Streaming
    @GET("data/report")
    suspend fun dataReport(
        @Query("format") format: String = "xlsx",
        @Query("path") path: String = ""
    ): Response<ResponseBody>

    // Admin-only record deletion (backend enforces is_admin).
    @DELETE("artisans/{id}")
    suspend fun deleteArtisan(@Path("id") id: String)

    @DELETE("crafts/{id}")
    suspend fun deleteCraft(@Path("id") id: String)

    @DELETE("products/{id}")
    suspend fun deleteProduct(@Path("id") id: String)

    @DELETE("tools/{id}")
    suspend fun deleteTool(@Path("id") id: String)

    @DELETE("workshops/{id}")
    suspend fun deleteWorkshop(@Path("id") id: String)

    @DELETE("processes/{id}")
    suspend fun deleteProcess(@Path("id") id: String)

    @DELETE("questionnaire/interviews/{id}")
    suspend fun deleteInterview(@Path("id") id: String)

    @GET("processes")
    suspend fun processes(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("productId") productId: String? = null,
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ProcessDetailDto>

    @GET("processes/{id}")
    suspend fun process(@Path("id") id: String): ProcessDetailDto

    @POST("processes")
    suspend fun createProcess(@Body body: ProcessCreateRequest): ProcessDetailDto

    @PATCH("processes/{id}")
    suspend fun updateProcess(@Path("id") id: String, @Body body: ProcessCreateRequest): ProcessDetailDto

    @POST("workshops")
    suspend fun createWorkshop(@Body body: WorkshopCreateRequest): CreatedRecordDto

    @POST("products")
    suspend fun createProduct(@Body body: ProductCreateRequest): CreatedRecordDto

    @POST("tools")
    suspend fun createTool(@Body body: ToolCreateRequest): CreatedRecordDto

    @GET("questionnaire/questions")
    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto>

    @GET("questionnaire/sections")
    suspend fun questionnaireSections(): List<QuestionnaireSectionDto>

    @POST("questionnaire/sections")
    suspend fun createQuestionnaireSection(@Body body: QuestionnaireSectionCreateRequest): QuestionnaireSectionDto

    @PATCH("questionnaire/sections/{id}")
    suspend fun updateQuestionnaireSection(
        @Path("id") id: String,
        @Body body: QuestionnaireSectionUpdateRequest
    ): QuestionnaireSectionDto

    @DELETE("questionnaire/sections/{id}")
    suspend fun deleteQuestionnaireSection(@Path("id") id: String)

    @POST("questionnaire/sections/reorder")
    suspend fun reorderQuestionnaireSections(@Body body: QuestionnaireSectionReorderRequest): List<QuestionnaireSectionDto>

    @POST("questionnaire/questions")
    suspend fun createQuestionnaireQuestion(@Body body: QuestionnaireQuestionCreateRequest): QuestionnaireQuestionDto

    @PATCH("questionnaire/questions/{id}")
    suspend fun updateQuestionnaireQuestion(
        @Path("id") id: String,
        @Body body: QuestionnaireQuestionUpdateRequest
    ): QuestionnaireQuestionDto

    @DELETE("questionnaire/questions/{id}")
    suspend fun deleteQuestionnaireQuestion(@Path("id") id: String)

    @POST("questionnaire/questions/reorder")
    suspend fun reorderQuestionnaireQuestions(@Body body: QuestionnaireQuestionReorderRequest): List<QuestionnaireSectionDto>

    @POST("questionnaire/interviews")
    suspend fun createQuestionnaireInterview(@Body body: QuestionnaireInterviewCreateRequest): CreatedRecordDto

    @GET("questionnaire/interviews")
    suspend fun interviews(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<QuestionnaireInterviewDetailDto>

    @GET("questionnaire/interviews/{id}")
    suspend fun interview(@Path("id") id: String): QuestionnaireInterviewDetailDto

    @PATCH("questionnaire/interviews/{id}")
    suspend fun updateInterview(
        @Path("id") id: String,
        @Body body: QuestionnaireInterviewUpdateRequest
    ): QuestionnaireInterviewDetailDto

    @GET("questionnaire/completion")
    suspend fun completionMatrix(
        @Query("artisanId") artisanId: String? = null,
        // The shared workshop scope: comma-joined ids plus the reserved "none". Absent = every
        // workshop, which is why it is nullable rather than defaulted to a string.
        @Query("workshopIds") workshopIds: String? = null
    ): CompletionMatrixDto

    @PUT("questionnaire/completion")
    suspend fun setCompletionCell(@Body body: CompletionCellRequest): JsonElement

    // One artisan's answers gathered from EVERY interview they sat in. Scoped by the shared
    // workshopIds so the same document can be read "as it stands for these workshops" — the whole
    // document is always returned, the scope only decides which sittings feed it.
    @GET("questionnaire/artisans/{id}/consolidated")
    suspend fun consolidatedQuestionnaire(
        @Path("id") artisanId: String,
        @Query("workshopIds") workshopIds: String? = null
    ): ConsolidatedQuestionnaireDto

    // --- Cross-researcher data access (Sharing) ---
    @GET("data-access/tiers")
    suspend fun dataAccessTiers(): List<DataAccessTierInfo>

    @GET("data-access/grants")
    suspend fun dataAccessGrants(): MyGrantsDto

    @POST("data-access/requests")
    suspend fun requestDataAccess(@Body body: DataAccessRequestBody): DataAccessGrantDto

    @POST("data-access/grants")
    suspend fun grantDataAccess(@Body body: DataAccessGrantBody): DataAccessGrantDto

    @POST("data-access/grants/{id}/decide")
    suspend fun decideDataAccess(@Path("id") id: String, @Body body: DataAccessDecisionBody): DataAccessGrantDto

    @POST("data-access/grants/{id}/revoke")
    suspend fun revokeDataAccess(@Path("id") id: String): DataAccessGrantDto

    @DELETE("data-access/grants/{id}")
    suspend fun deleteDataAccess(@Path("id") id: String)

    @GET("data-access/comments")
    suspend fun entryComments(
        @Query("recordType") recordType: String,
        @Query("recordId") recordId: String
    ): List<EntryCommentDto>

    @POST("data-access/comments")
    suspend fun addEntryComment(@Body body: EntryCommentBody): EntryCommentDto

    @GET("data-access/revisions")
    suspend fun recordRevisions(
        @Query("recordType") recordType: String,
        @Query("recordId") recordId: String
    ): List<RecordRevisionDto>

    // --- Workshop assignment (admin) ---
    @GET("workshops/{id}/assignments")
    suspend fun workshopAssignments(@Path("id") id: String): List<WorkshopAssignmentDto>

    @PUT("workshops/{id}/assignments")
    suspend fun setWorkshopAssignments(
        @Path("id") id: String,
        @Body body: WorkshopAssignmentBody
    ): List<WorkshopAssignmentDto>

    // Grant ONE user access at a level (upsert: re-grants a REVOKED/DENIED row) without touching the
    // rest of the roster — unlike the whole-set PUT above.
    @POST("workshops/{id}/assignments")
    suspend fun grantWorkshopAssignment(
        @Path("id") id: String,
        @Body body: WorkshopGrantBody
    ): WorkshopAssignmentDto

    @PATCH("workshops/{id}/assignments/{userId}")
    suspend fun updateWorkshopAssignment(
        @Path("id") id: String,
        @Path("userId") userId: String,
        @Body body: WorkshopAssignmentUpdateBody
    ): WorkshopAssignmentDto

    // Sets the row to REVOKED and RETURNS it — the row is the audit trail, so it is never deleted.
    @DELETE("workshops/{id}/assignments/{userId}")
    suspend fun revokeWorkshopAssignment(
        @Path("id") id: String,
        @Path("userId") userId: String
    ): WorkshopAssignmentDto

    // --- Workshop access requests (user side + the admin's cross-workshop queue) ---
    @GET("workshops/access-levels")
    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto>

    @POST("workshops/access-requests")
    suspend fun requestWorkshopAccess(@Body body: WorkshopAccessRequestBody): WorkshopAccessRequestResultDto

    @GET("workshops/access-requests/mine")
    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto>

    // Admin: the approval queue across ALL workshops. `statusFilter=ALL` widens it to full history.
    @GET("workshops/access-requests")
    suspend fun workshopAccessRequests(
        @Query("statusFilter") statusFilter: String = "PENDING"
    ): List<WorkshopAssignmentDto>

    @POST("workshops/access-requests/{id}/decide")
    suspend fun decideWorkshopAccess(
        @Path("id") id: String,
        @Body body: WorkshopAccessDecisionBody
    ): WorkshopAssignmentDto

    // --- Assigned tasks ---
    // view=assigned (default) is "my tasks"; view=created / view=all are the admin planning views.
    @GET("tasks")
    suspend fun tasks(
        @Query("view") view: String = "assigned",
        @Query("status") status: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        // Admin-only narrowing (ignored on view=assigned, which is hard-pinned to the caller).
        @Query("assigneeId") assigneeId: String? = null,
        @Query("batchId") batchId: String? = null,
        // false skips the data-backed counts (derivedCount comes back null) when only the list is needed.
        @Query("withDerived") withDerived: Boolean? = null
    ): PageResponse<TaskDto>

    @GET("tasks/{id}")
    suspend fun task(@Path("id") id: String): TaskDto

    @PATCH("tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: TaskUpdateBody): TaskDto

    // Withdraw ONE assignment. The creator or an admin only. Used for the pre-batch/single-assignee
    // rows, which have no batchId to delete by.
    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String)

    // --- Task administration (admin; the master admin may assign to anyone but themselves) ---
    // Every picker the assignment builder needs in one call. `workshopId` narrows the artisan list.
    @GET("tasks/options")
    suspend fun taskOptions(@Query("workshopId") workshopId: String? = null): TaskOptionsDto

    // THE assignment endpoint: one scope handed to N people writes N rows sharing a batchId.
    // Validated in full before the first row is written, so a bad id can never leave half a batch.
    @POST("tasks/batch")
    suspend fun createTaskBatch(@Body body: TaskBatchCreateBody): TaskBatchResultDto

    // Assignments grouped back into the action that created them. The filters select which batches
    // are SHOWN; the progress reported is always for the whole batch.
    @GET("tasks/batches")
    suspend fun taskBatches(
        @Query("view") view: String = "all",
        @Query("workshopId") workshopId: String? = null,
        @Query("batchId") batchId: String? = null,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): PageResponse<TaskBatchDto>

    // Per-assignee accountability rollup: reported progress next to derived progress on every line.
    @GET("tasks/progress")
    suspend fun taskProgress(
        @Query("workshopId") workshopId: String? = null,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("includeFinished") includeFinished: Boolean? = null
    ): TaskProgressReportDto

    // Withdraw a whole assignment. Only the admin who sent it (or the master admin) may unsend it.
    @DELETE("tasks/batch/{batchId}")
    suspend fun deleteTaskBatch(@Path("batchId") batchId: String)

    // --- Managed provider keys (MASTER ADMIN ONLY; every route is require_master_admin) ---
    // Cheap by design: no provider is contacted here, so the list costs one query however many
    // keys are configured. The list NEVER carries a value — only /reveal does.
    @GET("secrets")
    suspend fun managedSecrets(): List<ManagedSecretDto>

    // Plaintext of ONE key. The read is audit-logged server-side (who + which key, never the value).
    @GET("secrets/{key}/reveal")
    suspend fun revealSecret(@Path("key") key: String): ManagedSecretRevealDto

    // Set or rotate a key. Live on the next provider call — no restart, no redeploy.
    @PUT("secrets/{key}")
    suspend fun setSecret(@Path("key") key: String, @Body body: ManagedSecretSetBody): ManagedSecretDto

    // Drop the stored override so the deployed environment value applies again. Idempotent, and it
    // RETURNS the key's new state rather than 204ing.
    @DELETE("secrets/{key}")
    suspend fun clearSecret(@Path("key") key: String): ManagedSecretDto

    // Call the provider once with the key in force and persist the verdict onto the row.
    @POST("secrets/{key}/test")
    suspend fun testSecret(@Path("key") key: String): ManagedSecretDto

    // --- Appearance + accessibility preferences (every signed-in user owns their own row) ---
    // Returns an EMPTY OBJECT when the account has never saved any: see [PreferencesDto.exists].
    @GET("preferences/me")
    suspend fun myPreferences(): PreferencesDto

    @PUT("preferences/me")
    suspend fun updateMyPreferences(@Body body: PreferencesUpdateBody): PreferencesDto

    // --- Global search: five buckets sharing one page/pageSize ---
    // Dates are ISO-8601. pageSize is capped at 50 server-side.
    @GET("search")
    suspend fun search(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        // Which buckets to search, comma-joined ("artisans,media"); omitted searches all five. The
        // route reads a repeated parameter too, but one value keeps the query string canonical —
        // the same three buckets cannot arrive spelled two ways depending on tick order.
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        // The workshop SCOPE, comma-joined ids plus the reserved literal "none" for records linked to
        // no workshop. ABSENT means every workshop; never send "" to mean "all", which the server
        // reads as one blank id and matches nothing. See `record_filters.resolve_workshop_ids`.
        @Query("workshopIds") workshopIds: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10
    ): SearchResultsDto

    // --- Map: where the records ARE (aggregate pins, then one pin's records on demand) ---
    // The filter vocabulary is `search`'s, spelled identically and sent from the same place, because
    // a map that answered "Bagru, last 30 days" differently from the search box would leave no way
    // to tell which of the two was lying.
    @GET("map/points")
    suspend fun mapPoints(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        // Which buckets to count, comma-joined; omitted counts all five.
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        // NATION | STATE | DISTRICT — the administrative unit BOTH layers are grouped at. Null lets
        // the server pick its default (DISTRICT) rather than this client hard-coding a second copy
        // of it that could drift.
        @Query("level") level: String? = null,
        // The single-record scope. Both must be sent or neither: one alone is ignored, and the map
        // still draws the whole filtered corpus either way.
        @Query("focusType") focusType: String? = null,
        @Query("focusId") focusId: String? = null
    ): MapPointsDto

    // The point key holds ':' and '|' — "district:Rajasthan|Jaipur", "capture:0.25:107_302".
    // `encoded = false` is DELIBERATE and is the correct setting: Retrofit then percent-encodes the
    // characters that are illegal in a path segment ('|' becomes %7C) and leaves ':' alone, which is
    // legal there, and the route's `{point_key:path}` receives the key decoded and whole. Declaring
    // it `encoded = true` would ship a raw '|' — not a legal URL character — and the request would
    // either be rejected or silently mangled by the first proxy that normalised it.
    @GET("map/points/{key}/records")
    suspend fun mapPointRecords(
        @Path(value = "key", encoded = false) key: String,
        // These MUST be the filters the map was drawn with, level included. The key names an
        // administrative unit; which records sit in it is what the filters decide.
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("level") level: String? = null
    ): MapPointRecordsDto

    // --- Data browser (needs the dataset-download permission; rows are visibility-filtered) ---
    // ONE level of the virtual tree. path="" is the taxonomy chooser, not a folder listing.
    @GET("data/tree")
    suspend fun dataTree(@Query("path") path: String = ""): DataTreeDto

    // Where a record sits in the tree, so a search hit can be turned into a folder to open. Answers
    // `{"path": null}` when nothing files the record yet — that is a fact, not an error.
    @GET("data/locate")
    suspend fun dataLocate(@Query("type") type: String, @Query("id") id: String): JsonElement

    // The flattened subtree below `path`, for client-side zipping. `include` is a CSV of
    // text,images,videos,audios,transcripts,documents,other; omitted means everything.
    @GET("data/manifest")
    suspend fun dataManifest(
        @Query("path") path: String = "",
        @Query("include") include: String? = null
    ): DataManifestDto

    // One media file. Audio defaults to a server-side .mp4 (AAC) conversion; anything else is
    // redirected to the stored object (OkHttp follows it, dropping the auth header cross-host).
    @Streaming
    @GET("data/media/{id}/download")
    suspend fun downloadDataMedia(
        @Path("id") id: String,
        @Query("format") format: String? = null
    ): Response<ResponseBody>

    // --- Design & Prototype Workshops: the 22-stage record and the reports built from it ---
    //
    // The FIRST of these is the one the others depend on. `schema` serves the field registry itself,
    // and every capture screen on this device renders from that payload rather than from its own copy
    // of the field list — which is what keeps the phone, the web form and the report writer describing
    // one workshop the same way. It is a pure constant server-side (no database read), so it is cached
    // by its `version` and re-fetched only when that moves; see [StageSchemaStore].

    @GET("design-workshops/schema")
    suspend fun designWorkshopSchema(): SchemaResponse

    @GET("design-workshops/templates")
    suspend fun designWorkshopTemplates(): List<ReportTemplateDto>

    // The filters read the workshop's PROMOTED columns, not its stage JSON — that denormalisation is
    // the only reason "every workshop on Ikat in Odisha" can be answered without a table scan.
    @GET("design-workshops")
    suspend fun designWorkshops(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("search") search: String? = null,
        @Query("statusFilter") statusFilter: String? = null,
        @Query("craftName") craftName: String? = null,
        @Query("state") state: String? = null,
        @Query("mineOnly") mineOnly: Boolean = false
    ): DesignWorkshopPageDto

    @POST("design-workshops")
    suspend fun createDesignWorkshop(@Body body: DesignWorkshopCreateBody): DesignWorkshopDto

    @GET("design-workshops/{id}")
    suspend fun designWorkshop(@Path("id") id: String): DesignWorkshopDetailDto

    // Soft delete: the row and every stage entry stay, only `deletedAt` is set. Returns 204, so the
    // response is typed as bare Unit rather than a body that does not exist.
    @DELETE("design-workshops/{id}")
    suspend fun deleteDesignWorkshop(@Path("id") id: String)

    @GET("design-workshops/{id}/stages")
    suspend fun designWorkshopStages(@Path("id") id: String): StageListDto

    @GET("design-workshops/{id}/stages/{stageKey}")
    suspend fun designWorkshopStage(
        @Path("id") id: String,
        @Path("stageKey") stageKey: String
    ): StageBucketDto

    // A whole stage in ONE write — see [StageSaveBody]. The response carries the field keys the
    // server DROPPED because this build's registry is ahead of its own, which is the only way a
    // client finds out it is running ahead rather than by having its sync rejected.
    @PUT("design-workshops/{id}/stages/{stageKey}")
    suspend fun saveDesignWorkshopStage(
        @Path("id") id: String,
        @Path("stageKey") stageKey: String,
        @Body body: StageSaveBody
    ): StageSaveResultDto

    // The options behind one REF field's dropdown.
    //
    // `model` is the registry's `refModel`; `filterBy` is the value of the field named by
    // `refFilterBy` on the same row, which is what turns "every product in the cluster" into "this
    // artisan's products". Both are sent as the registry spelled them — the client invents neither,
    // so a scope or a model the server later renames cannot end up with two spellings.
    //
    // `search` is declared because the endpoint offers it, and is deliberately NOT used by the phone's
    // picker: the picker fetches the WHOLE list for a (model, filter) pair so it can be cached and
    // searched with no signal at all (see [DwReferenceStore]). A per-keystroke server search would be
    // faster in an office and useless in a courtyard, which is the wrong trade for this app.
    @GET("design-workshops/{id}/references")
    suspend fun designWorkshopReferences(
        @Path("id") id: String,
        @Query("model") model: String,
        @Query("filterBy") filterBy: String? = null,
        @Query("search") search: String? = null
    ): DwReferenceResponseDto

    // Read the number off a photographed identity card.
    //
    // ONLINE ONLY, and there is no offline substitute: the recognition runs server-side. The caller
    // must therefore check connectivity BEFORE offering the control, because a request that hangs for
    // a two-minute timeout in a village is indistinguishable, to the designer holding the phone, from
    // an app that has crashed.
    @Multipart
    @POST("design-workshops/ocr/identity")
    suspend fun designWorkshopIdentityOcr(
        @Part file: okhttp3.MultipartBody.Part
    ): DwIdentityOcrDto

    // --- Who, besides its creator, may open one design workshop ---------------------------------
    //
    // ADMIN-ONLY, ALL THREE. Every route behind these is `Depends(require_admin)` — `is_admin`, so
    // {ADMIN, MASTER_ADMIN} and nobody else. NOT the workshop's creator and NOT
    // `can_run_design_workshops`: a designer cannot hand out access to their own workshop, which is
    // deliberate (an owner's grant freezes the moment the owner leaves — the handover problem the
    // feature exists to solve, one level up). Nothing in this interface enforces it; the screen does,
    // at the moment of every write, and the server does again. See data/DesignWorkshopViewers.kt.
    //
    // A 404 FROM `eligible-viewers` MEANS THE SERVER PREDATES THE FEATURE, not that a record is
    // missing: it is the one call here carrying no id, and on a server without the route FastAPI
    // matches it against `GET /design-workshops/{workshop_id}` and answers 404 "Record not found".
    // (That same collision is why `api/router.py` must include the viewers router FIRST — a note
    // lives there; it is the server's problem, but this is where a phone would see it.)

    @GET("design-workshops/eligible-viewers")
    suspend fun eligibleDesignWorkshopViewers(): DwEligibleViewerListDto

    @GET("design-workshops/{id}/viewers")
    suspend fun designWorkshopViewers(@Path("id") id: String): DwViewerListDto

    // REPLACES the whole set. There is no add route and no remove route: taking somebody off is
    // sending the list without them, so a caller that posts only what it just ticked has silently
    // revoked everybody else. The answer is the set as the SERVER now holds it, not an echo of what
    // was sent — two admins on the same screen must not each believe their own payload was the
    // outcome.
    @PUT("design-workshops/{id}/viewers")
    suspend fun setDesignWorkshopViewers(
        @Path("id") id: String,
        @Body body: DwViewersBody
    ): DwViewerListDto

    // --- The DESIGNER tier: the roster that gates sign-in, and the profile a report prints ---
    //
    // Two groups of routes under one prefix, and they are gated differently on the server: the roster
    // is `can_manage_designer_roster` (Admin and above, for READS as well as writes — it is a list of
    // named individuals and their institutional standing, not something a peer should browse), while
    // a profile is the owner's or an admin's. Nothing in this interface enforces either; the screens
    // do, and the server does again. See the notes on the screens for why the client check is not
    // merely cosmetic.

    // PAGED, AND READING IT AS A BARE `List` IS THE DEFECT THIS SIGNATURE REPLACES.
    //
    // `GET /designers/roster` answers `page_payload(...)` — the OBJECT
    // `{items,total,page,pageSize,pages}` (backend/app/services/pagination.py:14-21) — exactly as
    // every other list in this API does. kotlinx.serialization cannot decode a JSON object into a
    // `List<T>`, and neither of the leniencies in [ApiClient] bridges it: `isLenient` reads a quoted
    // number as a number and `coerceInputValues` falls back to a default for a null, but a
    // structural array/object mismatch throws. So EVERY open of the roster landed in `.onFailure`
    // and drew "Could not load the designer roster." over an empty list — with the screen, its nav
    // entry, its admin-only permission check and all four mutations shipping and correct behind it,
    // and with the admin editor for another designer's profile unreachable as a consequence.
    //
    // `search` and `activeOnly` are deliberately NOT declared. The screen fetches the roster once
    // and filters it on the device (see `DesignerRosterScreen`), which is the same trade
    // `designWorkshopReferences` makes above: a per-keystroke server search is faster in an office
    // and useless in a courtyard on 2G. The walk over `page` is what keeps that honest.
    @GET("designers/roster")
    suspend fun designerRoster(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100
    ): PageResponse<DesignerRosterDto>

    // The email -> account join, and the only one there is: a roster row is keyed by email and
    // carries no user id, while `/designers/{userId}/profile` can only be reached by an id. See
    // [DesignerDirectoryEntryDto] for why `includeSuspended` is passed rather than left at its
    // default, and for why the DTO is narrower than the payload.
    @GET("designers/directory")
    suspend fun designerDirectory(
        @Query("includeSuspended") includeSuspended: Boolean = true
    ): List<DesignerDirectoryEntryDto>

    @POST("designers/roster")
    suspend fun addDesignerToRoster(@Body body: DesignerRosterCreateBody): DesignerRosterDto

    // A JsonObject rather than a typed body, so a cleared "Notes" box can send an explicit null and
    // actually clear the column — see [designerRosterUpdateJson]. This is also the RESTORE route:
    // `isActive: true` here is the only way back from a suspension, because a DELETE cannot express it.
    @PATCH("designers/roster/{id}")
    suspend fun updateDesignerRosterEntry(
        @Path("id") id: String,
        @Body body: JsonObject
    ): DesignerRosterDto

    // SUSPENDS. It does not delete: the row is the record that the person was ever empanelled, and
    // the server sets `isActive = false` + `revokedAt` and RETURNS the row rather than 204ing, which
    // is what lets the roster screen redraw the suspended state without a second round trip.
    @DELETE("designers/roster/{id}")
    suspend fun suspendDesigner(@Path("id") id: String): DesignerRosterDto

    @GET("designers/me/profile")
    suspend fun myDesignerProfile(): DesignerProfileDto

    // JsonObject for the reason on [DesignerProfileUpdateBody]: the Retrofit converter is configured
    // `explicitNulls = false`, so a typed body could never clear a column.
    @PUT("designers/me/profile")
    suspend fun updateMyDesignerProfile(@Body body: JsonObject): DesignerProfileDto

    // An admin reading or correcting somebody else's profile, from the roster screen. Keyed by USER
    // id and not by profile id, because the profile row may not exist yet — a designer who has signed
    // in but never opened the profile screen has an account and no `DesignerProfile`, and an admin
    // filling in their empanelment number for them is exactly the case that creates one.
    @GET("designers/{userId}/profile")
    suspend fun designerProfile(@Path("userId") userId: String): DesignerProfileDto

    @PUT("designers/{userId}/profile")
    suspend fun updateDesignerProfile(
        @Path("userId") userId: String,
        @Body body: JsonObject
    ): DesignerProfileDto

    // Records a report this DEVICE generated with no network. The bytes are deliberately not
    // uploaded — a designer on a metered field connection should not be charged for a thirty-megabyte
    // report merely to prove one was made; the checksum is enough to match the file later.
    @POST("design-workshops/{id}/exports")
    suspend fun recordDesignWorkshopExport(
        @Path("id") id: String,
        @Body body: ExportRecordBody
    ): JsonElement

    // --- Custom questionnaires: the form a designer authored themselves ---------------------------
    //
    // PLURAL, and the plural is the whole distinction. `questionnaire/…` above is the ONE global
    // artisan questionnaire every researcher answers; `questionnaires/…` here is a form somebody built
    // last Tuesday in the .xlsx pro-forma. Telling the two apart by counting characters is exactly
    // what the backend's own module docstring says a client must not be asked to do, which is why
    // every method below is additionally named `…CustomQuestionnaire…` rather than by its path.
    //
    // THE .xlsx HALF OF THE API IS DELIBERATELY ABSENT. `GET /questionnaires/pro-forma`,
    // `POST /questionnaires/upload` and `POST /questionnaires/{id}/upload` are how the form is BUILT,
    // and a form is built on a laptop in a spreadsheet, not on a handset — a designer picking an
    // .xlsx out of Android's document provider, on a phone with no spreadsheet application, is a
    // worse route to the same place. What the handset is for is the other half: answering.

    @GET("questionnaires")
    suspend fun customQuestionnaires(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("search") search: String? = null,
        @Query("designWorkshopId") designWorkshopId: String? = null,
        // Deactivation is what this API has instead of a delete, so `false` is the only way to see a
        // questionnaire somebody took out of circulation — and its answers with it.
        @Query("activeOnly") activeOnly: Boolean = true,
        @Query("mineOnly") mineOnly: Boolean = false
    ): PageResponse<CustomQuestionnaireSummaryDto>

    // Its own endpoint rather than a page-size-500 list call, for the reason the route gives: a
    // dropdown that silently stops at page one is a designer who cannot find the questionnaire they
    // uploaded this morning.
    @GET("questionnaires/options")
    suspend fun customQuestionnaireOptions(
        @Query("designWorkshopId") designWorkshopId: String? = null
    ): List<CustomQuestionnaireOptionDto>

    // `includeRetired` is what the READ/EDIT screen passes and the ANSWER screen does not. See the
    // KDoc on [CustomQuestionnaireDto] for why that is a difference between two screens and not a
    // detail one caller can decide for both.
    @GET("questionnaires/{id}")
    suspend fun customQuestionnaire(
        @Path("id") id: String,
        @Query("includeRetired") includeRetired: Boolean = false
    ): CustomQuestionnaireDto

    @POST("questionnaires")
    suspend fun createCustomQuestionnaire(
        @Body body: CustomQuestionnaireCreateBody
    ): CustomQuestionnaireDto

    // JsonObject, so a detach can send an explicit null — see [customQuestionnaireUpdateJson].
    @PATCH("questionnaires/{id}")
    suspend fun updateCustomQuestionnaire(
        @Path("id") id: String,
        @Body body: JsonObject
    ): CustomQuestionnaireDto

    @POST("questionnaires/{id}/sections")
    suspend fun createCustomSection(
        @Path("id") id: String,
        @Body body: CustomSectionCreateBody
    ): CustomQuestionnaireDto

    @PATCH("questionnaires/{id}/sections/{sectionId}")
    suspend fun updateCustomSection(
        @Path("id") id: String,
        @Path("sectionId") sectionId: String,
        @Body body: CustomSectionPatchBody
    ): CustomQuestionnaireDto

    @POST("questionnaires/{id}/sections/{sectionId}/questions")
    suspend fun createCustomQuestion(
        @Path("id") id: String,
        @Path("sectionId") sectionId: String,
        @Body body: CustomQuestionCreateBody
    ): CustomQuestionnaireDto

    // Returns the ACTION it took, not the question. Rewording a question that has answers supersedes
    // it instead of overwriting it, and the caller has to be able to say so.
    @PATCH("questionnaires/{id}/questions/{questionId}")
    suspend fun updateCustomQuestion(
        @Path("id") id: String,
        @Path("questionId") questionId: String,
        @Body body: JsonObject
    ): CustomQuestionEditResultDto

    // 200 WITH A BODY, NOT 204, and typed accordingly: this deletes only when nobody has answered,
    // and retires when somebody has. A client that assumed 204-means-gone would leave a question the
    // designer just removed sitting in the list with no explanation.
    @DELETE("questionnaires/{id}/questions/{questionId}")
    suspend fun removeCustomQuestion(
        @Path("id") id: String,
        @Path("questionId") questionId: String
    ): CustomQuestionEditResultDto

    @POST("questionnaires/{id}/entries")
    suspend fun createCustomEntry(
        @Path("id") id: String,
        @Body body: CustomEntryCreateBody
    ): CustomEntryDto

    @PATCH("questionnaires/{id}/entries/{entryId}")
    suspend fun updateCustomEntry(
        @Path("id") id: String,
        @Path("entryId") entryId: String,
        @Body body: CustomEntryPatchBody
    ): CustomEntryDto

    // PUT and not POST: sending the same section twice must not produce two sets of answers. The
    // database agrees, with @@unique([entryId, questionId]).
    @PUT("questionnaires/{id}/entries/{entryId}/answers")
    suspend fun saveCustomAnswers(
        @Path("id") id: String,
        @Path("entryId") entryId: String,
        @Body body: CustomAnswerBatchBody
    ): CustomAnswerSaveResultDto
}
