package com.designprototype.workshop.data

import android.content.Context
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming
import retrofit2.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface WorkshopRepositoryApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): SignInResponse

    @POST("auth/login")
    suspend fun googleLogin(@Body body: GoogleLoginRequest): SignInResponse

    @POST("auth/login")
    suspend fun oidcLogin(@Body body: OidcLoginRequest): SignInResponse

    @GET("me")
    suspend fun me(): UserDto

    @POST("auth/change-password")
    suspend fun changeOwnPassword(@Body body: ChangePasswordRequest): Response<ChangePasswordResponse>

    @POST("auth/password-links")
    suspend fun issuePasswordLink(@Body body: IssuePasswordLinkRequest): IssuedPasswordLinkDto

    @POST("auth/password-links/{id}/revoke")
    suspend fun revokePasswordLink(@Path("id") id: String): Map<String, Boolean>

    @POST("auth/set-password/check")
    suspend fun checkPasswordLinkInBody(@Body body: PasswordLinkCheckRequest): PasswordLinkCheckDto

    @GET("auth/set-password")
    suspend fun checkPasswordLink(@Query("token") token: String): PasswordLinkCheckDto

    @POST("auth/set-password")
    suspend fun setPasswordWithLink(@Body body: SetPasswordRequest): Map<String, Boolean>


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
    suspend fun pendingReviews(
        @Query("pageSize") pageSize: Int? = null,
        @Query("before") before: String? = null
    ): PendingReviewListDto

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

    @GET("feedback/vocabulary")
    suspend fun feedbackVocabulary(): FeedbackVocabularyDto

    @GET("idempotency-keys/{key}")
    suspend fun idempotencyAnswer(@Path("key") key: String): IdempotencyAnswerDto

    @POST("feedback/reports")
    suspend fun createFeedbackReport(@Body body: FeedbackReportCreateRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): FeedbackReportDto

    @GET("feedback/reports/mine")
    suspend fun myFeedbackReports(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 25
    ): FeedbackReportPageDto

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

    @POST("review/{type}/{id}/revise")
    suspend fun reviseRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

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
        @Query("workshopIds") workshopIds: String? = null,
        @Query("designWorkshopId") designWorkshopId: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("completeness") completeness: String? = null,
        @Query("search") search: String? = null,
        @Header("X-Client-App") clientApp: String = "android",
    ): PageResponse<ArtisanDto>

    @GET("crafts")
    suspend fun crafts(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null,
        @Query("search") search: String? = null
    ): PageResponse<CraftDto>

    @GET("crafts/catalogue")
    suspend fun craftCatalogue(
        @Header("If-None-Match") ifNoneMatch: String? = null,
        @Query("includeInactive") includeInactive: Boolean? = null,
    ): Response<ResponseBody>

    @POST("artisans")
    suspend fun createArtisan(
        @Body body: ArtisanCreateRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Header("X-Client-App") clientApp: String = "android",
    ): ArtisanDto

    @POST("artisan-duplicates/check")
    suspend fun checkDuplicates(@Body body: DuplicateCheckRequest): DuplicateCheckResponse

    @POST("artisans/lookup/aadhaar")
    suspend fun lookupArtisanByAadhaar(@Body body: AadhaarLookupRequest): AadhaarLookupDto

    @GET("artisans/{id}")
    suspend fun artisan(@Path("id") id: String): ArtisanDetailDto

    @GET("artisans/{id}/consent")
    suspend fun artisanConsent(@Path("id") id: String): ArtisanConsentDto

    @POST("artisans/{id}/consent")
    suspend fun recordArtisanConsent(@Path("id") id: String, @Body body: ArtisanConsentRequest): ArtisanConsentDto

    @GET("artisans/{id}/card")
    suspend fun artisanCard(@Path("id") id: String, @Query("source") source: String = "ANDROID"): ArtisanCardDto

    @POST("artisans/{id}/card/reissue")
    suspend fun reissueArtisanCard(@Path("id") id: String, @Body body: ArtisanCardReissueRequest): ArtisanCardDto

    @GET("artisan-cards/status")
    suspend fun artisanCardStatus(): ArtisanCardStatusDto

    @POST("artisan-cards/resolve")
    suspend fun resolveArtisanCard(@Body body: ResolveCardRequest): ResolveCardDto

    @PATCH("artisans/{id}")
    suspend fun updateArtisan(@Path("id") id: String, @Body body: JsonObject): ArtisanDetailDto

    @GET("artisans/{id}/questionnaire")
    suspend fun artisanQuestionnaire(@Path("id") id: String): ArtisanQuestionnaireDto

    @POST("crafts")
    suspend fun createCraft(@Body body: CraftCreateRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): CreatedRecordDto

    @GET("crafts/{id}")
    suspend fun craft(@Path("id") id: String): CraftDto

    @PATCH("crafts/{id}")
    suspend fun updateCraft(@Path("id") id: String, @Body body: JsonObject): CraftDto

    @GET("products")
    suspend fun products(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("artisanId") artisanId: String? = null,
        @Query("artisanName") artisanName: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("completeness") completeness: String? = null,
        @Query("search") search: String? = null
    ): PageResponse<ProductDetailDto>

    @GET("products/{id}")
    suspend fun product(@Path("id") id: String): ProductDetailDto

    @PATCH("products/{id}")
    suspend fun updateProduct(@Path("id") id: String, @Body body: JsonObject): ProductDetailDto

    @GET("tools")
    suspend fun tools(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null,
        @Query("completeness") completeness: String? = null,
        @Query("search") search: String? = null
    ): PageResponse<ToolDetailDto>

    @GET("tools/{id}")
    suspend fun tool(@Path("id") id: String): ToolDetailDto

    @PATCH("tools/{id}")
    suspend fun updateTool(@Path("id") id: String, @Body body: JsonObject): ToolDetailDto

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
        @Query("createdBy") createdBy: String? = null,
        @Query("accessibleOnly") accessibleOnly: Boolean? = null,
        @Query("completeness") completeness: String? = null,
        @Query("search") search: String? = null
    ): PageResponse<WorkshopDetailDto>

    @GET("workshops/{id}")
    suspend fun workshop(@Path("id") id: String): WorkshopDetailDto

    @PATCH("workshops/{id}")
    suspend fun updateWorkshop(@Path("id") id: String, @Body body: JsonObject): WorkshopDetailDto

    @GET("workshops/{id}/submission-check")
    suspend fun workshopSubmissionCheck(@Path("id") id: String): WorkshopSubmissionCheckDto

    @GET("workshops/unmapped")
    suspend fun unmappedRecords(): WorkshopMappingPlanDto

    @POST("workshops/unmapped/map")
    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto

    @POST("workshops/unmapped/{bucket}/{recordId}")
    suspend fun fileOneUnmappedRecord(
        @Path("bucket") bucket: String,
        @Path("recordId") recordId: String,
        @Body body: FileUnmappedRecordBody,
    ): WorkshopMappingPlanDto

    @DELETE("workshops/unmapped/{bucket}/{recordId}")
    suspend fun discardUnmappedRecord(
        @Path("bucket") bucket: String,
        @Path("recordId") recordId: String,
    ): DiscardUnmappedRecordDto

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

    @POST("media/complete")
    suspend fun completeMediaChecksummed(@Body body: JsonObject): MediaFileDto

    @DELETE("media/object")
    suspend fun deleteMediaObject(@Query("objectKey") objectKey: String)

    @DELETE("media/{id}")
    suspend fun deleteMedia(@Path("id") id: String)

    @GET("media/{id}")
    suspend fun getMedia(@Path("id") id: String): MediaFileDto

    @GET("media/batch")
    suspend fun getMediaBatch(@Query("ids") ids: String): MediaBatchDto

    @GET("media")
    suspend fun media(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("linkedRecordType") linkedRecordType: String? = null,
        @Query("linkedRecordId") linkedRecordId: String? = null,
        @Query("uploadedBy") uploadedBy: String? = null,
        @Query("search") search: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("statusFilter") statusFilter: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @QueryMap(encoded = true) filters: Map<String, String> = emptyMap()
    ): PageResponse<MediaFileDto>

    @GET("media/jobs")
    suspend fun mediaJobs(
        @Query("statusFilter") statusFilter: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): PageResponse<MediaProcessingJobDto>

    @POST("media/jobs/{id}/retry")
    suspend fun retryMediaJob(@Path("id") id: String): MediaProcessingJobDto

    @POST("media/jobs/process")
    suspend fun processMediaJobs(@Query("limit") limit: Int? = null): MediaQueueRunDto

    @GET("media/orphans")
    suspend fun orphanMedia(): List<MediaFileDto>

    @POST("media/{id}/relink")
    suspend fun relinkMedia(@Path("id") id: String, @Body body: MediaRelinkRequest): MediaFileDto

    @POST("media/{id}/refine-transcript")
    suspend fun refineTranscript(@Path("id") id: String, @Body body: TranscriptRefineRequest): TranscriptRefineResponse

    @POST("media/{id}/transcribe-now")
    suspend fun transcribeNow(@Path("id") id: String): MediaFileDto

    @GET("media/{id}/insights")
    suspend fun transcriptInsight(@Path("id") id: String): TranscriptInsightViewDto

    @GET("media/insights")
    suspend fun transcriptInsights(
        @Query("ids") ids: String,
        @Query("includeTranslation") includeTranslation: Boolean? = null
    ): TranscriptInsightBatchDto

    @PATCH("media/{id}/insights")
    suspend fun patchTranscriptInsight(@Path("id") id: String, @Body body: TranscriptInsightPatchRequest): TranscriptInsightViewDto

    @POST("media/{id}/insights/regenerate")
    suspend fun regenerateTranscriptInsight(@Path("id") id: String, @Body body: TranscriptInsightRegenerateRequest): TranscriptInsightViewDto

    @POST("media/{id}/transcript")
    suspend fun setTranscript(@Path("id") id: String, @Body body: TranscriptUpdateRequest): MediaFileDto

    @Streaming
    @GET("export/dataset")
    suspend fun datasetManifestStream(@Query("stream") stream: Int = 1): Response<ResponseBody>

    @GET("export/dataset")
    suspend fun datasetManifest(): DatasetManifestDto

    @Streaming
    @GET("data/report")
    suspend fun dataReport(
        @Query("format") format: String = "xlsx",
        @Query("path") path: String = ""
    ): Response<ResponseBody>

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
        @Query("createdBy") createdBy: String? = null,
        @Query("search") search: String? = null
    ): PageResponse<ProcessDetailDto>

    @GET("processes/{id}")
    suspend fun process(@Path("id") id: String): ProcessDetailDto

    @POST("processes")
    suspend fun createProcess(@Body body: ProcessCreateRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): ProcessDetailDto

    @PATCH("processes/{id}")
    suspend fun updateProcess(@Path("id") id: String, @Body body: JsonObject): ProcessDetailDto

    @POST("workshops")
    suspend fun createWorkshop(@Body body: WorkshopCreateRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): CreatedRecordDto

    @POST("products")
    suspend fun createProduct(@Body body: ProductCreateRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): CreatedRecordDto

    @POST("tools")
    suspend fun createTool(@Body body: ToolCreateRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): CreatedRecordDto

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
    suspend fun createQuestionnaireInterview(@Body body: QuestionnaireInterviewCreateRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): CreatedRecordDto

    @GET("questionnaire/interviews")
    suspend fun interviews(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null,
        @Query("completeness") completeness: String? = null,
        @Query("search") search: String? = null
    ): PageResponse<QuestionnaireInterviewDetailDto>

    @GET("records/completeness-rules")
    suspend fun completenessRules(): kotlinx.serialization.json.JsonElement

    @GET("questionnaire/interviews/{id}")
    suspend fun interview(@Path("id") id: String): QuestionnaireInterviewDetailDto

    @PATCH("questionnaire/interviews/{id}")
    suspend fun updateInterview(
        @Path("id") id: String,
        @Body body: QuestionnaireInterviewUpdateRequest
    ): QuestionnaireInterviewDetailDto

    @PATCH("questionnaire/interviews/{id}")
    suspend fun updateInterviewJson(
        @Path("id") id: String,
        @Body body: JsonObject
    ): QuestionnaireInterviewDetailDto

    @POST("questionnaire/interviews/{id}/merge-into/{targetId}")
    suspend fun mergeInterviewInto(
        @Path("id") id: String,
        @Path("targetId") targetId: String
    ): QuestionnaireInterviewDetailDto

    @GET("questionnaire/completion")
    suspend fun completionMatrix(
        @Query("artisanId") artisanId: String? = null,
        @Query("workshopIds") workshopIds: String? = null
    ): CompletionMatrixDto

    @PUT("questionnaire/completion")
    suspend fun setCompletionCell(@Body body: CompletionCellRequest): JsonElement

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

    @DELETE("data-access/comments/{id}")
    suspend fun deleteEntryComment(@Path("id") id: String)

    @PATCH("data-access/comments/{id}")
    suspend fun updateEntryComment(@Path("id") id: String, @Body body: EntryCommentUpdateBody): EntryCommentDto

    @GET("data-access/revisions")
    suspend fun recordRevisions(
        @Query("recordType") recordType: String,
        @Query("recordId") recordId: String
    ): List<RecordRevisionDto>

    @GET("workshops/{id}/assignments")
    suspend fun workshopAssignments(@Path("id") id: String): List<WorkshopAssignmentDto>

    @PUT("workshops/{id}/assignments")
    suspend fun setWorkshopAssignments(
        @Path("id") id: String,
        @Body body: WorkshopAssignmentBody
    ): List<WorkshopAssignmentDto>

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

    @DELETE("workshops/{id}/assignments/{userId}")
    suspend fun revokeWorkshopAssignment(
        @Path("id") id: String,
        @Path("userId") userId: String
    ): WorkshopAssignmentDto

    @GET("workshops/access-levels")
    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto>

    @POST("workshops/access-requests")
    suspend fun requestWorkshopAccess(@Body body: WorkshopAccessRequestBody): WorkshopAccessRequestResultDto

    @GET("workshops/access-requests/mine")
    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto>

    @GET("workshops/access-requests")
    suspend fun workshopAccessRequests(
        @Query("statusFilter") statusFilter: String = "PENDING"
    ): List<WorkshopAssignmentDto>

    @POST("workshops/access-requests/{id}/decide")
    suspend fun decideWorkshopAccess(
        @Path("id") id: String,
        @Body body: WorkshopAccessDecisionBody
    ): WorkshopAssignmentDto

    @GET("tasks")
    suspend fun tasks(
        @Query("view") view: String = "assigned",
        @Query("status") status: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("batchId") batchId: String? = null,
        @Query("withDerived") withDerived: Boolean? = null
    ): PageResponse<TaskDto>

    @GET("tasks/{id}")
    suspend fun task(@Path("id") id: String): TaskDto

    @PATCH("tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: TaskUpdateBody): TaskDto

    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String)

    @GET("tasks/options")
    suspend fun taskOptions(
        @Query("workshopId") workshopId: String? = null,
        @Query("search") search: String? = null
    ): TaskOptionsDto

    @POST("tasks/batch")
    suspend fun createTaskBatch(@Body body: TaskBatchCreateBody): TaskBatchResultDto

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

    @GET("tasks/progress")
    suspend fun taskProgress(
        @Query("workshopId") workshopId: String? = null,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("includeFinished") includeFinished: Boolean? = null
    ): TaskProgressReportDto

    @DELETE("tasks/batch/{batchId}")
    suspend fun deleteTaskBatch(@Path("batchId") batchId: String)

    @GET("ai/providers")
    suspend fun aiProviders(): AiCatalogueDto

    @GET("me/ai-keys")
    suspend fun myAiKeys(): List<UserAiKeyDto>

    @PUT("me/ai-keys/{provider}")
    suspend fun setMyAiKey(
        @Path("provider") provider: String,
        @Body body: UserAiKeySetBody
    ): UserAiKeyDto

    @DELETE("me/ai-keys/{provider}")
    suspend fun deleteMyAiKey(@Path("provider") provider: String): UserAiKeyDto

    @POST("me/ai-keys/{provider}/test")
    suspend fun testMyAiKey(@Path("provider") provider: String): UserAiKeyDto

    @GET("secrets")
    suspend fun managedSecrets(): List<ManagedSecretDto>

    @GET("secrets/{key}/reveal")
    suspend fun revealSecret(@Path("key") key: String): ManagedSecretRevealDto

    @PUT("secrets/{key}")
    suspend fun setSecret(@Path("key") key: String, @Body body: ManagedSecretSetBody): ManagedSecretDto

    @DELETE("secrets/{key}")
    suspend fun clearSecret(@Path("key") key: String): ManagedSecretDto

    @POST("secrets/{key}/test")
    suspend fun testSecret(@Path("key") key: String): ManagedSecretDto

    @GET("preferences/me")
    suspend fun myPreferences(): PreferencesDto

    @PUT("preferences/me")
    suspend fun updateMyPreferences(@Body body: PreferencesUpdateBody): PreferencesDto

    @GET("search/unified")
    suspend fun unifiedSearch(
        @Query("q") q: String,
        @Query("limit") limit: Int = 5,
        @Query("types") types: String? = null,
        @Query("scope") scope: String? = null,
        @Query("type") type: String? = null,
        @Query("page") page: Int? = null,
        @Query("artisanIds") artisanIds: String? = null,
        @Query("sort") sort: String? = null,
    ): UnifiedSearchResponse

    @GET("search/remote/{type}/{id}")
    suspend fun remoteRecord(@Path("type") type: String, @Path("id") id: String): RemoteRecord

    @GET("search")
    suspend fun search(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10,
        @QueryMap filters: Map<String, String> = emptyMap()
    ): SearchResultsDto

    @GET("map/points")
    suspend fun mapPoints(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("level") level: String? = null,
        @Query("focusType") focusType: String? = null,
        @Query("focusId") focusId: String? = null
    ): MapPointsDto

    @GET("map/points/{key}/records")
    suspend fun mapPointRecords(
        @Path(value = "key", encoded = false) key: String,
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

    @GET("map/coverage")
    suspend fun coverage(@QueryMap query: Map<String, String>): CoverageResponse

    @GET("map/coverage/units/{state}/{district}/records")
    suspend fun coverageUnitRecords(
        @Path("state") state: Int,
        @Path("district") district: Int,
        @QueryMap query: Map<String, String>
    ): CoverageUnitRecords

    @GET("ministry-dashboard/entitlements")
    suspend fun entitlements(): MinistryEntitlementsDto

    @GET("data/tree")
    suspend fun dataTree(@Query("path") path: String = ""): DataTreeDto

    @GET("data/locate")
    suspend fun dataLocate(@Query("type") type: String, @Query("id") id: String): JsonElement

    @Streaming
    @GET("data/manifest")
    suspend fun dataManifestStream(
        @Query("path") path: String = "",
        @Query("include") include: String? = null,
        @Query("stream") stream: Int = 1
    ): Response<ResponseBody>

    @GET("data/manifest")
    suspend fun dataManifest(
        @Query("path") path: String = "",
        @Query("include") include: String? = null
    ): DataManifestDto

    @Streaming
    @GET
    suspend fun apiPath(@Url path: String): Response<ResponseBody>

    @Streaming
    @GET("data/media/{id}/download")
    suspend fun downloadDataMedia(
        @Path("id") id: String,
        @Query("format") format: String? = null
    ): Response<ResponseBody>

    @GET("design-workshops/schema")
    suspend fun designWorkshopSchema(): SchemaResponse

    @GET("design-workshops/templates")
    suspend fun designWorkshopTemplates(): List<ReportTemplateDto>

    @GET("design-workshops")
    suspend fun designWorkshops(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("search") search: String? = null,
        @Query("statusFilter") statusFilter: String? = null,
        @Query("workshopKind") workshopKind: String? = null,
        @Query("craftName") craftName: String? = null,
        @Query("state") state: String? = null,
        @Query("mineOnly") mineOnly: Boolean = false
    ): DesignWorkshopPageDto

    @GET("design-workshops/default-for-me")
    suspend fun designWorkshopDefaultForMe(): DesignWorkshopDefaultDto

    @POST("design-workshops")
    suspend fun createDesignWorkshop(@Body body: DesignWorkshopCreateBody): DesignWorkshopDto

    @GET("design-workshops/{id}")
    suspend fun designWorkshop(@Path("id") id: String): DesignWorkshopDetailDto

    @GET("design-workshops/{id}/designer-prefill")
    suspend fun designWorkshopDesignerPrefill(@Path("id") id: String): DesignerPrefillDto

    @DELETE("design-workshops/{id}")
    suspend fun deleteDesignWorkshop(@Path("id") id: String)

    @GET("design-workshops/{id}/stages")
    suspend fun designWorkshopStages(@Path("id") id: String): StageListDto

    @GET("design-workshops/{id}/stages/{stageKey}")
    suspend fun designWorkshopStage(
        @Path("id") id: String,
        @Path("stageKey") stageKey: String
    ): StageBucketDto

    @GET("design-workshops/{id}/stages/{stageKey}/assist")
    suspend fun designWorkshopStageAssist(
        @Path("id") id: String,
        @Path("stageKey") stageKey: String
    ): DwStageAssistDto

    @GET("design-workshops/{id}/stages/{stageKey}/copy-sources")
    suspend fun stageCopySources(
        @Path("id") id: String,
        @Path("stageKey") stageKey: String
    ): DwCopySourcesDto

    @Streaming
    @GET("design-workshops/{id}/roster/pro-forma")
    suspend fun rosterProForma(@Path("id") id: String): Response<ResponseBody>

    @Multipart
    @POST("design-workshops/{id}/roster/upload")
    suspend fun uploadRoster(
        @Path("id") id: String,
        @Part file: okhttp3.MultipartBody.Part,
    ): DwArtisanImportReportDto

    @GET("design-workshops/{id}/roster/imports")
    suspend fun rosterImports(@Path("id") id: String): DwArtisanImportListDto

    @POST("design-workshops/{id}/stages/{stageKey}/confirm-copied")
    suspend fun confirmCopiedFields(
        @Path("id") id: String,
        @Path("stageKey") stageKey: String,
        @Body body: DwConfirmCopiedBody
    ): DwConfirmCopiedDto

    @PUT("design-workshops/{id}/stages/{stageKey}")
    suspend fun saveDesignWorkshopStage(
        @Path("id") id: String,
        @Path("stageKey") stageKey: String,
        @Body body: StageSaveBody
    ): StageSaveResultDto

    @GET("design-workshops/{id}/provenance")
    suspend fun designWorkshopProvenance(@Path("id") id: String): DwProvenanceReportDto

    @POST("design-ratings")
    suspend fun submitDesignRating(@Body body: DesignRatingBody): DesignRatingSavedDto

    @GET("design-ratings/subjects/{subjectId}")
    suspend fun designRatingLedger(
        @Path("subjectId") subjectId: String,
        @Query("round") round: String,
    ): SubjectLedgerDto

    @GET("design-ratings/rounds/{round}")
    suspend fun designRatingRound(
        @Path("round") round: String,
        @Query("workshopId") workshopId: String,
        @Query("entityKey") entityKey: String,
    ): RoundRankingDto

    @GET("design-workshops/{id}/custom-sections")
    suspend fun designWorkshopCustomSections(@Path("id") id: String): DwCustomDefinitionDto

    @GET("design-workshops/{id}/references")
    suspend fun designWorkshopReferences(
        @Path("id") id: String,
        @Query("model") model: String,
        @Query("scope") scope: String? = null,
        @Query("filterBy") filterBy: String? = null,
        @Query("search") search: String? = null,
        @Query("recordId") recordId: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): DwReferenceResponseDto

    @Multipart
    @POST("design-workshops/ocr/identity")
    suspend fun designWorkshopIdentityOcr(
        @Part file: okhttp3.MultipartBody.Part,
        @Part("retention") retention: okhttp3.RequestBody? = null
    ): DwIdentityOcrDto

    @POST("design-workshops/ocr/identity/retention")
    suspend fun decideIdentityPhotograph(
        @Body body: DwRetentionDecisionBody
    ): DwRetentionResultDto

    @GET("design-workshops/dictation-allowance")
    suspend fun designWorkshopDictationAllowance(): DwDictationAllowanceDto

    @Multipart
    @POST("design-workshops/{id}/dictate")
    suspend fun designWorkshopDictate(
        @Path("id") id: String,
        @Part file: okhttp3.MultipartBody.Part,
        @Part("languageHint") languageHint: okhttp3.RequestBody,
        @Part("interviewId") interviewId: okhttp3.RequestBody? = null,
        @Part("artisanIds") artisanIds: okhttp3.RequestBody? = null
    ): DwDictateDto

    @POST("design-workshops/{id}/dictation-consent")
    suspend fun recordDesignWorkshopDictationConsent(
        @Path("id") id: String,
        @Body body: DwConsentDecisionRequest
    ): DwConsentDecisionDto

    @POST("design-workshops/{id}/ai-layers/proofread")
    suspend fun designWorkshopProofread(
        @Path("id") id: String,
        @Body body: DwProofreadBody
    ): DwAiVerbResultDto

    @POST("design-workshops/{id}/ai-layers/on-device")
    suspend fun designWorkshopOnDeviceLayer(
        @Path("id") id: String,
        @Body body: Map<String, String>
    ): DwAiVerbResultDto

    @POST("design-workshops/{id}/ai-layers/expand")
    suspend fun designWorkshopExpand(
        @Path("id") id: String,
        @Body body: DwExpandBody
    ): DwAiVerbResultDto

    @POST("design-workshops/{id}/ai-layers/translate")
    suspend fun designWorkshopTranslate(
        @Path("id") id: String,
        @Body body: DwTranslateBody
    ): DwAiVerbResultDto

    @POST("design-workshops/{id}/ai-layers/caption")
    suspend fun designWorkshopCaptionMedia(
        @Path("id") id: String,
        @Body body: DwMediaVerbBody
    ): DwAiVerbResultDto

    @POST("design-workshops/{id}/ai-layers/subtitles")
    suspend fun designWorkshopSubtitleMedia(
        @Path("id") id: String,
        @Body body: DwMediaVerbBody
    ): DwAiVerbResultDto

    @Streaming
    @GET("design-workshops/{id}/ai-layers/{layerId}/subtitles.{fmt}")
    suspend fun designWorkshopSubtitleFile(
        @Path("id") id: String,
        @Path("layerId") layerId: String,
        @Path("fmt") fmt: String,
        @Query("speakers") speakers: Boolean? = null
    ): Response<ResponseBody>

    @GET("design-workshops/{id}/ai-layers")
    suspend fun designWorkshopAiLayers(
        @Path("id") id: String,
        @Query("kind") kind: String? = null,
        @Query("includeText") includeText: Boolean? = null,
        @Query("includeDeleted") includeDeleted: Boolean? = null
    ): DwAiLayerListDto

    @GET("design-workshops/{id}/ai-layers/decisions")
    suspend fun designWorkshopAiLayerDecisions(@Path("id") id: String): DwAiDecisionHistoryDto

    @GET("design-workshops/{id}/ai-layers/{layerId}")
    suspend fun designWorkshopAiLayer(
        @Path("id") id: String,
        @Path("layerId") layerId: String
    ): DwAiLayerReadDto

    @POST("design-workshops/{id}/ai-layers")
    suspend fun registerDesignWorkshopAiLayer(
        @Path("id") id: String,
        @Body body: DwAiLayerRegisterBody
    ): DwAiLayerRegisterResultDto

    @GET("design-workshops/{id}/transcripts")
    suspend fun designWorkshopTranscripts(@Path("id") id: String): DwTranscriptListDto

    @GET("design-workshops/ai-verb-allowance")
    suspend fun designWorkshopAiVerbAllowance(): DwAiVerbAllowanceDto

    @GET("design-workshops/{id}/market-analysis")
    suspend fun designWorkshopMarketAnalysis(@Path("id") id: String): JsonObject

    @POST("design-workshops/{id}/ai-layers/{layerId}/accept")
    suspend fun acceptDesignWorkshopAiLayer(
        @Path("id") id: String,
        @Path("layerId") layerId: String,
        @Body body: DwAiLayerDecisionBody
    ): DwAiLayerDecisionResultDto

    @POST("design-workshops/{id}/ai-layers/{layerId}/unaccept")
    suspend fun unacceptDesignWorkshopAiLayer(
        @Path("id") id: String,
        @Path("layerId") layerId: String,
        @Body body: DwAiLayerDecisionBody
    ): DwAiLayerDecisionResultDto

    @DELETE("design-workshops/{id}/ai-layers/{layerId}")
    suspend fun declineDesignWorkshopAiLayer(
        @Path("id") id: String,
        @Path("layerId") layerId: String
    )

    @GET("design-workshops/eligible-viewers")
    suspend fun eligibleDesignWorkshopViewers(
        @Query("search") search: String? = null
    ): DwEligibleViewerListDto

    @GET("design-workshops/{id}/viewers")
    suspend fun designWorkshopViewers(@Path("id") id: String): DwViewerListDto

    @PUT("design-workshops/{id}/viewers")
    suspend fun setDesignWorkshopViewers(
        @Path("id") id: String,
        @Body body: DwViewersBody
    ): DwViewerListDto

    @GET("design-workshop-inspections/eligible-inspectors")
    suspend fun eligibleDesignWorkshopInspectors(
        @Query("search") search: String? = null
    ): DwEligibleInspectorListDto

    @GET("design-workshop-inspections/{id}/inspectors")
    suspend fun designWorkshopInspectors(@Path("id") id: String): DwInspectorListDto

    @PUT("design-workshop-inspections/{id}/inspectors")
    suspend fun setDesignWorkshopInspectors(
        @Path("id") id: String,
        @Body body: DwInspectorsBody
    ): DwInspectorListDto

    @GET("design-workshop-inspections")
    suspend fun inspectableDesignWorkshops(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("search") search: String? = null
    ): DesignWorkshopPageDto

    @GET("design-workshop-inspections/{id}")
    suspend fun workshopUnderInspection(@Path("id") id: String): DwInspectionDetailDto

    @POST("design-workshop-inspections/{id}/feedback")
    suspend fun recordInspectionFeedback(
        @Path("id") id: String,
        @Body body: DwInspectionFeedbackBody
    ): DwInspectionFeedbackAnswerDto

    @POST("design-workshop-inspections/{id}/send-back")
    suspend fun sendInspectionBack(
        @Path("id") id: String,
        @Body body: DwInspectionFeedbackBody
    ): DwInspectionFeedbackAnswerDto

    @POST("design-workshop-inspections/{id}/clearance")
    suspend fun clearInspectionRound(@Path("id") id: String): DwInspectionFeedbackAnswerDto

    @DELETE("design-workshop-inspections/{id}/clearance")
    suspend fun withdrawInspectionClearance(@Path("id") id: String): DwInspectionFeedbackAnswerDto

    @GET("designers/roster")
    suspend fun designerRoster(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
        @Query("search") search: String? = null,
        @Query("standing") standing: String? = null,
        @Query("roles") roles: String? = null,
        @Query("institutions") institutions: String? = null,
        @Query("dateField") dateField: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("sort") sort: String? = null,
        @Query("dir") dir: String? = null
    ): RosterPageDto<DesignerRosterDto>

    @GET("designers/roster/institutions")
    suspend fun designerRosterInstitutions(): RosterInstitutionsDto

    @GET("designers/directory")
    suspend fun designerDirectory(
        @Query("includeSuspended") includeSuspended: Boolean = true
    ): List<DesignerDirectoryEntryDto>

    @POST("designers/roster")
    suspend fun addDesignerToRoster(@Body body: DesignerRosterCreateBody): DesignerRosterDto

    @PATCH("designers/roster/{id}")
    suspend fun updateDesignerRosterEntry(
        @Path("id") id: String,
        @Body body: JsonObject
    ): DesignerRosterDto

    @DELETE("designers/roster/{id}")
    suspend fun suspendDesigner(@Path("id") id: String): DesignerRosterDto

    @GET("access/roster")
    suspend fun accessRoster(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("status") status: String? = null,
        @Query("search") search: String? = null,
        @Query("roles") roles: String? = null,
        @Query("dateField") dateField: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("sort") sort: String? = null,
        @Query("dir") dir: String? = null
    ): RosterPageDto<AccessRosterDto>

    @GET("access/roster/pending-count")
    suspend fun pendingAccessCount(): PendingAccessCountDto

    @GET("design-workshop-inspections/awaiting-count")
    suspend fun awaitingInspectionCount(): AwaitingInspectionCountDto

    @GET("notifications")
    suspend fun notifications(@Query("limit") limit: Int): NotificationPageDto

    @POST("notifications/read")
    suspend fun markNotificationsRead(@Body body: NotificationReadBody): NotificationReadResultDto

    @GET("notifications/digest")
    suspend fun weeklyDigestPreference(): WeeklyDigestPreferenceDto

    @PUT("notifications/digest")
    suspend fun saveWeeklyDigestPreference(@Body body: WeeklyDigestPreferenceBody): WeeklyDigestPreferenceDto

    @POST("access/roster")
    suspend fun addToAccessRoster(@Body body: AccessRosterCreateBody): AccessRosterDto

    @POST("access/roster/{id}/decision")
    suspend fun decideAccessRequest(
        @Path("id") id: String,
        @Body body: AccessDecisionBody
    ): AccessRosterDto

    @PATCH("access/roster/{id}")
    suspend fun updateAccessEntry(
        @Path("id") id: String,
        @Body body: JsonObject
    ): AccessRosterDto

    @DELETE("access/roster/{id}")
    suspend fun suspendAccessEntry(@Path("id") id: String): AccessRosterDto

    @GET("designers/me/profile")
    suspend fun myDesignerProfile(): DesignerProfileDto

    @PUT("designers/me/profile")
    suspend fun updateMyDesignerProfile(@Body body: JsonObject): DesignerProfileDto

    @GET("designers/{userId}/profile")
    suspend fun designerProfile(@Path("userId") userId: String): DesignerProfileDto

    @PUT("designers/{userId}/profile")
    suspend fun updateDesignerProfile(
        @Path("userId") userId: String,
        @Body body: JsonObject
    ): DesignerProfileDto

    @POST("design-workshops/{id}/exports")
    suspend fun recordDesignWorkshopExport(
        @Path("id") id: String,
        @Body body: ExportRecordBody
    ): JsonElement

    @Streaming
    @GET("questionnaires/pro-forma")
    suspend fun questionnaireProForma(): Response<ResponseBody>

    @Streaming
    @GET("questionnaires/{id}/xlsx")
    suspend fun questionnaireWorkbook(@Path("id") id: String): Response<ResponseBody>

    @Streaming
    @GET("questionnaires/{id}/question-set.xlsx")
    suspend fun questionnaireQuestionSet(@Path("id") id: String): Response<ResponseBody>

    @Multipart
    @POST("questionnaires/upload")
    suspend fun uploadQuestionnaire(
        @Part file: okhttp3.MultipartBody.Part,
        @Part("title") title: okhttp3.RequestBody? = null,
        @Part("designWorkshopId") designWorkshopId: okhttp3.RequestBody? = null,
    ): QFormUploadResultDto

    @Multipart
    @POST("questionnaires/{id}/upload")
    suspend fun reuploadQuestionnaire(
        @Path("id") id: String,
        @Part file: okhttp3.MultipartBody.Part,
        @Part("title") title: okhttp3.RequestBody? = null,
    ): QFormUploadResultDto

    @GET("questionnaires")
    suspend fun customQuestionnaires(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("search") search: String? = null,
        @Query("designWorkshopId") designWorkshopId: String? = null,
        @Query("activeOnly") activeOnly: Boolean = true,
        @Query("mineOnly") mineOnly: Boolean = false,
        @Query("deletedOnly") deletedOnly: Boolean = false,
        @QueryMap(encoded = true) filters: Map<String, String> = emptyMap(),
    ): PageResponse<CustomQuestionnaireSummaryDto>

    @DELETE("questionnaires/{id}")
    suspend fun deleteCustomQuestionnaire(@Path("id") id: String)

    @POST("questionnaires/{id}/restore")
    suspend fun restoreCustomQuestionnaire(@Path("id") id: String): JsonElement

    @DELETE("questionnaires/{id}/entries/{entryId}")
    suspend fun deleteCustomEntry(@Path("id") id: String, @Path("entryId") entryId: String)

    @GET("questionnaires/{id}/deleted-entries")
    suspend fun deletedCustomEntries(@Path("id") id: String): List<DeletedCustomEntryDto>

    @POST("questionnaires/{id}/entries/{entryId}/restore")
    suspend fun restoreCustomEntry(@Path("id") id: String, @Path("entryId") entryId: String): JsonElement

    @GET("questionnaires/options")
    suspend fun customQuestionnaireOptions(
        @Query("designWorkshopId") designWorkshopId: String? = null
    ): List<CustomQuestionnaireOptionDto>

    @GET("questionnaire/forms/{id}/bindings")
    suspend fun formBindings(@Path("id") id: String): FormBindingsDto

    @GET("questionnaires/{id}")
    suspend fun customQuestionnaire(
        @Path("id") id: String,
        @Query("includeRetired") includeRetired: Boolean = false
    ): CustomQuestionnaireDto

    @POST("questionnaires")
    suspend fun createCustomQuestionnaire(
        @Body body: CustomQuestionnaireCreateBody,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): CustomQuestionnaireDto

    @PATCH("questionnaires/{id}")
    suspend fun updateCustomQuestionnaire(
        @Path("id") id: String,
        @Body body: JsonObject
    ): CustomQuestionnaireDto

    @POST("questionnaires/{id}/reuse")
    suspend fun reuseQuestionnaire(
        @Path("id") id: String,
        @Body body: JsonObject,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): QFormReuseResultDto

    @POST("questionnaires/{id}/sections")
    suspend fun createCustomSection(
        @Path("id") id: String,
        @Body body: CustomSectionCreateBody,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
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
        @Body body: CustomQuestionCreateBody,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): CustomQuestionnaireDto

    @PATCH("questionnaires/{id}/questions/{questionId}")
    suspend fun updateCustomQuestion(
        @Path("id") id: String,
        @Path("questionId") questionId: String,
        @Body body: JsonObject
    ): CustomQuestionEditResultDto

    @DELETE("questionnaires/{id}/questions/{questionId}")
    suspend fun removeCustomQuestion(
        @Path("id") id: String,
        @Path("questionId") questionId: String
    ): CustomQuestionEditResultDto

    @POST("questionnaires/{id}/entries")
    suspend fun createCustomEntry(
        @Path("id") id: String,
        @Body body: CustomEntryCreateBody,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): CustomEntryDto

    @PATCH("questionnaires/{id}/entries/{entryId}")
    suspend fun updateCustomEntry(
        @Path("id") id: String,
        @Path("entryId") entryId: String,
        @Body body: CustomEntryPatchBody
    ): CustomEntryDto

    @PUT("questionnaires/{id}/entries/{entryId}/answers")
    suspend fun saveCustomAnswers(
        @Path("id") id: String,
        @Path("entryId") entryId: String,
        @Body body: CustomAnswerBatchBody
    ): CustomAnswerSaveResultDto

    @GET("workshop-types")
    suspend fun workshopTypes(): List<WorkshopTypeOptionDto>
}

@Serializable
data class WorkshopTypeOptionDto(
    val id: String = "",
    val key: String = "",
    val label: String = "",
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val routesToDesignWorkshop: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class MinistryEntitlementsDto(
    val coverageMap: Boolean = false
)
