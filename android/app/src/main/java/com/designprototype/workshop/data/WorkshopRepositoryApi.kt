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

    /**
     * The signed-in account replacing its own password. The route `mustChangePassword` sends
     * somebody to, and until 2026-08-31 nothing on either client called it.
     *
     * IT DOES NOT REVOKE SESSIONS, unlike redeeming a link, and the difference is who is asking: a
     * person changing their own password from inside a session they are using has not lost control
     * of anything, and signing them out of their own handset for tidiness is the worse answer.
     */
    @POST("auth/change-password")
    suspend fun changeOwnPassword(@Body body: ChangePasswordRequest): Map<String, Boolean>

    /**
     * Mint a set-password link for another account. Admin only, and throttled PER SUBJECT.
     *
     * The throttle belongs to the person being reset rather than to the administrator, because
     * redeeming a link revokes that account's sessions: without it an admin could sign a colleague
     * out of their own laptop as often as they could press the button, and two admins taking turns
     * is the same harm. A 429 here carries the server's own sentence and a `retry-after`.
     */
    @POST("auth/password-links")
    suspend fun issuePasswordLink(@Body body: IssuePasswordLinkRequest): IssuedPasswordLinkDto

    /**
     * Withdraw a link that has not been used yet — "I pasted that into the wrong window".
     *
     * The credential fingerprint alone cannot answer this: the account's password has not changed,
     * so the token still verifies and would go on working until it expired. This route is the reason
     * the `PasswordResetToken` table exists at all.
     */
    @POST("auth/password-links/{id}/revoke")
    suspend fun revokePasswordLink(@Path("id") id: String): Map<String, Boolean>

    /**
     * Is this link still good? UNAUTHENTICATED, because the person holding one cannot sign in — that
     * is the whole point of holding it. [ApiClient] adds no bearer header when the store is empty,
     * so this works from a signed-out handset with no special client.
     */
    @GET("auth/set-password")
    suspend fun checkPasswordLink(@Query("token") token: String): PasswordLinkCheckDto

    /** Redeem a link. Also unauthenticated; the token is the entire authority and is checked four ways. */
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

    /**
     * The closed lists a feedback report is filed against, with their labels.
     *
     * ONE DEFINITION, SERVED. The web form and this one render the same dropdowns from the same
     * response, so a category added on the server reaches both without either being rebuilt and
     * neither can invent a member the API would refuse.
     */
    @GET("feedback/vocabulary")
    suspend fun feedbackVocabulary(): FeedbackVocabularyDto

    /**
     * File one grievance, suggestion, recommendation or bug report.
     *
     * A POST AND NOT THE PUT ABOVE, and the difference is the whole reason this route exists:
     * `feedback/me` upserts one row per account, so a second submission destroyed the first.
     */
    @POST("feedback/reports")
    suspend fun createFeedbackReport(@Body body: FeedbackReportCreateRequest): FeedbackReportDto

    /**
     * This account's own reports, newest first, each with its status and whoever answered it.
     *
     * The redressal half: a mechanism that cannot show a person their grievance was seen is not one.
     * Scoped by the caller's token — there is no `userId` parameter to get wrong.
     */
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
        // WHOSE RECORDS, ASKED FOR BY NAME — and it must be asked for, never sifted for.
        //
        // Reading the repository is OPEN (`backend/app/services/records.py` viewable_where returns
        // {}), so page one of this list is the newest hundred rows of the WHOLE archive. Filtering
        // those hundred client-side on `createdById` is the defect this parameter exists to prevent:
        // MEASURED against the running API as designer@example.org, /api/artisans holds total=431
        // with page one carrying rows from 34 distinct creators and NONE of that designer's own,
        // while ?createdBy=<them> returns their real total. A designer whose records are older than
        // the newest hundred is shown an empty My Activity and told they have recorded nothing.
        //
        // Every list route below takes this; MediaFile owns its rows through `uploadedById` and so
        // spells the parameter `uploadedBy`. The query key follows the column on both sides of the
        // wire. Rationale on the server: backend/app/api/routes/artisans.py.
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ArtisanDto>

    @GET("crafts")
    suspend fun crafts(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        // Server-side ownership filter — see the note on [artisans].
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

    /**
     * A JSON BODY AND NOT [ArtisanCreateRequest], SO THAT A CLEARED BOX CAN BE SENT AS `null`.
     *
     * `ApiClient.json` sets `explicitNulls = false`, which is right for a create and silently wrong
     * for a PATCH: a null property is DROPPED from the payload, an absent key means "leave this
     * column alone" to `payload.model_dump(exclude_unset=True)`, and so the server's
     * `_CLEARABLE_COLUMNS` — the columns it deliberately allows a client to NULL — were unreachable
     * from this app. The form offered a clear button on "Practising since" (and on the birthday, the
     * phone, the experience number, the address …), the save reported success, and the old value was
     * still in the database. `services/records.clean_data` names that failure exactly: "A FIELD THAT
     * CANNOT BE CLEARED IS A 200 THAT DOES NOTHING, which is the worst answer an API can give."
     *
     * The body is still DERIVED from [ArtisanCreateRequest] rather than hand-assembled — see
     * `WorkshopRepository.artisanPatchBody`, which encodes the request with the wire encoder and then
     * puts the explicit nulls back — so a field added to the request class is still sent from here,
     * which is the same rule `completeMediaChecksummed` follows one screen over.
     */
    @PATCH("artisans/{id}")
    suspend fun updateArtisan(@Path("id") id: String, @Body body: JsonObject): ArtisanDetailDto

    @GET("artisans/{id}/questionnaire")
    suspend fun artisanQuestionnaire(@Path("id") id: String): ArtisanQuestionnaireDto

    @POST("crafts")
    suspend fun createCraft(@Body body: CraftCreateRequest): CreatedRecordDto

    @GET("crafts/{id}")
    suspend fun craft(@Path("id") id: String): CraftDto

    /**
     * A JSON BODY, FOR THE REASON SPELLED OUT ON [updateArtisan] AND ONE MORE.
     *
     * `explicitNulls = false` plus `model_dump(exclude_unset=True)` means a null property is dropped
     * and an absent key means "leave the stored value alone", so the two workshop links — both in
     * `services/records.CLEARABLE_KEYS` — could not be CLEARED from this client at all. The picker
     * drew a "None" row, the save answered 200, and the record stayed filed where it was. See
     * `WorkshopRepository.patchBodyWithClearances`, which encodes the request with the wire encoder
     * and then puts the explicit nulls back, so a field added to the request class is still sent.
     */
    @PATCH("crafts/{id}")
    suspend fun updateCraft(@Path("id") id: String, @Body body: JsonObject): CraftDto

    @GET("products")
    suspend fun products(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("artisanId") artisanId: String? = null,
        @Query("artisanName") artisanName: String? = null,
        // Server-side ownership filter — see the note on [artisans].
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ProductDetailDto>

    @GET("products/{id}")
    suspend fun product(@Path("id") id: String): ProductDetailDto

    /**
     * A JSON BODY, FOR THE REASON SPELLED OUT ON [updateArtisan] AND ONE MORE.
     *
     * `explicitNulls = false` plus `model_dump(exclude_unset=True)` means a null property is dropped
     * and an absent key means "leave the stored value alone", so the two workshop links — both in
     * `services/records.CLEARABLE_KEYS` — could not be CLEARED from this client at all. The picker
     * drew a "None" row, the save answered 200, and the record stayed filed where it was. See
     * `WorkshopRepository.patchBodyWithClearances`, which encodes the request with the wire encoder
     * and then puts the explicit nulls back, so a field added to the request class is still sent.
     */
    @PATCH("products/{id}")
    suspend fun updateProduct(@Path("id") id: String, @Body body: JsonObject): ProductDetailDto

    @GET("tools")
    suspend fun tools(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        // Server-side ownership filter — see the note on [artisans].
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ToolDetailDto>

    @GET("tools/{id}")
    suspend fun tool(@Path("id") id: String): ToolDetailDto

    /**
     * A JSON BODY, FOR THE REASON SPELLED OUT ON [updateArtisan] AND ONE MORE.
     *
     * `explicitNulls = false` plus `model_dump(exclude_unset=True)` means a null property is dropped
     * and an absent key means "leave the stored value alone", so the two workshop links — both in
     * `services/records.CLEARABLE_KEYS` — could not be CLEARED from this client at all. The picker
     * drew a "None" row, the save answered 200, and the record stayed filed where it was. See
     * `WorkshopRepository.patchBodyWithClearances`, which encodes the request with the wire encoder
     * and then puts the explicit nulls back, so a field added to the request class is still sent.
     */
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
        // Server-side ownership filter — see the note on [artisans].
        @Query("createdBy") createdBy: String? = null,
        /**
         * WHICH WORKSHOPS THIS ACCOUNT MAY ACTUALLY FILE AGAINST — the narrowing every PICKER must
         * ask for, and the parameter this client did not have.
         *
         * Reading the repository is open to every signed-in account on purpose (the server's
         * `records.viewable_where` returns `{}`), so `GET /workshops` serves the WHOLE table — 196
         * rows on this deployment — and until this existed every record form's workshop dropdown on
         * the handset offered all of them, including curated rosters the designer is not on. The
         * refusal then arrived from `enforce_workshop_submission` as a 403 AFTER a whole artisan
         * record had been typed in a courtyard, and for the ordinary case the pre-flight red
         * sentence could not warn either: on an uncurated workshop everybody implicitly holds
         * CONTRIBUTE, so `canSubmit` is true and there is nothing to say. The web picker was scoped
         * (`accessibleOnly: "true"` in `components/forms/WorkshopSelect`) and this half was not,
         * which left the handset with the pre-change behaviour under a parity comment.
         *
         * NULL AND NOT `false` WHEN OFF, so a read surface's request is byte-for-byte the request it
         * always was: Retrofit drops a null `@Query`. The server's default is `False` either way.
         *
         * ONLY A PICKER SENDS IT. The roster panel, the access-request screen (you request access to
         * what you cannot reach), the workshop scope filters and the browse/re-link lists are READS,
         * and narrowing those would empty a data view of rows this account is entitled to read — the
         * "a scoped column matched nothing so a full corpus rendered empty" failure this app has
         * already shipped once. See [WorkshopRepository.workshopsIMaySubmitTo] versus
         * [WorkshopRepository.workshopsByOccurrence].
         */
        @Query("accessibleOnly") accessibleOnly: Boolean? = null
    ): PageResponse<WorkshopDetailDto>

    @GET("workshops/{id}")
    suspend fun workshop(@Path("id") id: String): WorkshopDetailDto

    /**
     * A JSON BODY, FOR THE REASON SPELLED OUT ON [updateArtisan] AND ONE MORE.
     *
     * `explicitNulls = false` plus `model_dump(exclude_unset=True)` means a null property is dropped
     * and an absent key means "leave the stored value alone", so the two workshop links — both in
     * `services/records.CLEARABLE_KEYS` — could not be CLEARED from this client at all. The picker
     * drew a "None" row, the save answered 200, and the record stayed filed where it was. See
     * `WorkshopRepository.patchBodyWithClearances`, which encodes the request with the wire encoder
     * and then puts the explicit nulls back, so a field added to the request class is still sent.
     */
    @PATCH("workshops/{id}")
    suspend fun updateWorkshop(@Path("id") id: String, @Body body: JsonObject): WorkshopDetailDto

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

    /**
     * File ONE record the ladder could not settle, under the workshop an admin names.
     *
     * THE COMPANION TO THE "left alone" LIST. The ladder deliberately refuses a row whose evidence is
     * absent or points two ways, and until this route existed that report was where the story
     * stopped: an admin who could see "this interview was at Bagru" had no way to say so without
     * leaving the screen and hunting the record down in another list.
     *
     * NOT A GENERAL "MOVE THIS RECORD" ROUTE. The server writes only where `workshopId` is still
     * NULL, so it can close a gap and can never quietly re-file something a person already decided;
     * a row filed since the report was read answers 409 naming the workshop it went to.
     */
    @POST("workshops/unmapped/{bucket}/{recordId}")
    suspend fun fileOneUnmappedRecord(
        @Path("bucket") bucket: String,
        @Path("recordId") recordId: String,
        @Body body: FileUnmappedRecordBody,
    ): WorkshopMappingPlanDto

    /**
     * Delete ONE unfiled record permanently. Admin and master admin only.
     *
     * 200 WITH A BODY where every per-type delete answers 204, and the body is the point: every
     * `MediaFile` relation is `onDelete: SetNull`, so deleting a parent DETACHES its attachments
     * rather than removing them. The count of what survived is the difference between "deleted
     * permanently" and "deleted permanently, and its nine photographs are still in the repository
     * with nothing pointing at them" — and a client cannot say the second sentence off a 204.
     */
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

    // ONE LIST ROUTE, AND UNTIL NOW THIS CLIENT COULD ONLY PAGE THROUGH IT BLINDLY.
    //
    // `list_media` (backend/app/api/routes/media.py, `@router.get("")`) accepts `search`,
    // `mediaType`, `statusFilter`, `dateFrom` and `dateTo` beside the two link keys below, and this
    // binding declared none of the five — so the handset asked for page after page of the whole
    // archive and sifted the answer in memory, while /media on the web debounces a term straight
    // into the query string (300ms, `frontend/app/(protected)/media/page.tsx`). That is not a
    // platform difference; it is capability the server already offers being discarded at the
    // Retrofit interface.
    //
    // WHY A SERVER-SIDE TERM AND NOT A `filter {}` OVER THE PAGE. Reading media is OPEN across this
    // repository, so page one is the newest rows of everything anybody has ever uploaded. A
    // client-side filter over that answers "nothing found" for a file that exists and sits on page
    // nine — absence reading as non-existence, which is non-negotiable 10 of the frontend contract:
    // "Truncation, caps and skipped work must be stated on screen. A list that quietly stops is
    // indistinguishable from a place with no records — the single most repeated bug class in this
    // repo." (Quoted from .claude/skills/field-repo-frontend/SKILL.md on 2026-08-27; re-find it with
    // `grep -n "must be stated on screen" .claude/skills/field-repo-frontend/SKILL.md` rather than
    // by line number, which moves.) The server folds `search` into the WHERE, ORed across
    // `originalFilename`, `caption` and `mimeType`, so it reaches past page one — the same argument
    // the web states in full at /media.
    //
    // VISIBILITY IS NOT AT RISK FROM `search`. The route AND-composes its visibility clause with the
    // search OR precisely so a term can never widen what an account may see, so nothing here needs a
    // client-side re-check of the rows that come back.
    //
    // THE TWO ENUM PARAMETERS ARE SINGLE-VALUED AND STRICT, AND "ALL" IS A 422, NOT "NO FILTER".
    // Both go through `records.enum_filter_or_422`, which refuses anything outside the enum with a
    // 422 naming the allowed values — written that way because a value Prisma cannot compare used
    // to come back as a 500 with a stack trace. So:
    //   • [mediaType]    IMAGE | VIDEO | AUDIO | PDF | DOCUMENT | OTHER
    //   • [statusFilter] DRAFT | PENDING | APPROVED | REJECTED | NEEDS_REVISION
    // Upper case, one value, and `null` — never `""`, never `"ALL"` — is how a picker's empty option
    // says "no filter". A dropdown labelling its empty row "All" must map it to null before it gets
    // here. `workshopAccessRequests` further down IS a route where `statusFilter=ALL` widens the
    // list to full history, and `mediaJobs` immediately below spells its parameter the same way
    // while checking it against a THIRD enum (`MEDIA_PROCESSING_JOB_STATUSES`, the JOB's status and
    // not the file's) — three routes, one parameter name, three vocabularies. Do not copy a value
    // between them: passing `RECORD_STATUSES` to the jobs route is a mistake already made once on
    // the server, where it 422'd every request the panel makes.
    //
    // [dateFrom]/[dateTo] are ISO-8601 and INCLUSIVE AT BOTH ENDS (`records.add_date_range` builds
    // `gte`/`lte`), compared against `createdAt` — the upload instant, not the day the fieldwork
    // happened. Spelled String for the reason `search` and `mapPoints` above are: the wire format is
    // the contract, and a client-side date type would put a formatter between it and this call.
    @GET("media")
    suspend fun media(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("linkedRecordType") linkedRecordType: String? = null,
        @Query("linkedRecordId") linkedRecordId: String? = null,
        // Server-side ownership filter — see the note on [artisans]. MediaFile owns its rows through
        // `uploadedById`, so this one route spells the parameter `uploadedBy`.
        @Query("uploadedBy") uploadedBy: String? = null,
        // APPENDED rather than slotted in beside `uploadedBy` in the server's own order: every call
        // site passes these by name, and appending keeps a positional call — if one is ever written
        // — meaning what it meant before this line existed.
        @Query("search") search: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("statusFilter") statusFilter: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null
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

    // The whole-repository download manifest.
    //
    // TWO DECLARATIONS OF ONE ROUTE, AND THE STREAMED ONE IS THE ONE TO USE. The typed call below
    // returns a fully-materialised DTO, which sends the response through Retrofit's
    // kotlinx-serialization converter — `Serializer.FromString`, i.e. `decodeFromString(body
    // .string())`, i.e. the entire body as one contiguous ByteArray and then one contiguous String.
    // The manifest is unbounded in BYTES (the server caps the entry COUNT at 20,000 media rows plus
    // 6x5,000 record rows, and inlines every details.txt, every answers.txt and every transcript),
    // so on a large repository that single allocation is what throws
    // `OutOfMemoryError: Failed to allocate a N byte allocation` on the handset. See
    // data/ManifestStream.kt for the full account.
    //
    // The typed one is KEPT, not deleted, because it is the fallback for a server that predates
    // `?stream=1`: such a server ignores the unknown parameter and answers `application/json`, and
    // this app has to be able to finish the download against it — handsets update on their own
    // schedule and a build that only works against a new server breaks every phone in the field on
    // the day of a rollback. It must not be used for anything else: a new caller wanting "the list
    // of files" should take the streamed route and consume it a line at a time.
    @Streaming
    @GET("export/dataset")
    suspend fun datasetManifestStream(@Query("stream") stream: Int = 1): Response<ResponseBody>

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
        // Server-side ownership filter — see the note on [artisans].
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<ProcessDetailDto>

    @GET("processes/{id}")
    suspend fun process(@Path("id") id: String): ProcessDetailDto

    @POST("processes")
    suspend fun createProcess(@Body body: ProcessCreateRequest): ProcessDetailDto

    /**
     * A JSON BODY, FOR THE REASON SPELLED OUT ON [updateArtisan] AND ONE MORE.
     *
     * `explicitNulls = false` plus `model_dump(exclude_unset=True)` means a null property is dropped
     * and an absent key means "leave the stored value alone", so the two workshop links — both in
     * `services/records.CLEARABLE_KEYS` — could not be CLEARED from this client at all. The picker
     * drew a "None" row, the save answered 200, and the record stayed filed where it was. See
     * `WorkshopRepository.patchBodyWithClearances`, which encodes the request with the wire encoder
     * and then puts the explicit nulls back, so a field added to the request class is still sent.
     */
    @PATCH("processes/{id}")
    suspend fun updateProcess(@Path("id") id: String, @Body body: JsonObject): ProcessDetailDto

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
        // Server-side ownership filter — see the note on [artisans].
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

    /**
     * Withdraw a comment. 204, or 403 when it is somebody else's and this account is not an admin.
     *
     * THE HANDSET COULD POST AND NOT UNPOST, which is a worse asymmetry than it sounds. A comment is
     * the only free-text a designer writes ABOUT a record rather than into it, and the one written by
     * mistake — on the wrong artisan, or naming somebody — could be removed in a browser and not on
     * the device it was typed on. The route has existed the whole time; only this declaration was
     * missing, so the two surfaces disagreed about whether a comment can be taken back.
     *
     * `delete_comment` returns 204 for an id that does not exist, so a double tap is not an error.
     */
    @DELETE("data-access/comments/{id}")
    suspend fun deleteEntryComment(@Path("id") id: String)

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
    //
    // THREE OF THOSE PICKERS ARE CAPPED, AND WITHOUT [search] THE ROWS PAST THE CAP WERE
    // UNREACHABLE. The server takes 500 assignees, 200 workshops and 500 artisans
    // (`TASK_OPTION_USER_LIMIT` / `_WORKSHOP_LIMIT` / `_ARTISAN_LIMIT`; re-check with
    // `grep -n "TASK_OPTION_.*_LIMIT = " backend/app/api/routes/tasks.py`), and the route's own
    // docstring records that two of those three caps were already live on this deployment's measured
    // population — 3632 accounts, 731 artisans, docs/OPEN_FINDINGS.md, 2026-08-13.
    //
    // A picker that filters IN MEMORY over a capped list therefore shows an admin looking for a
    // colleague whose name sorts late in the alphabet exactly what it shows them for a colleague who
    // has no account at all — the "hidden from you vs nobody matched" failure the eligible-viewers
    // picker was fixed for, reopened in a different endpoint, and non-negotiable 10 of the frontend
    // contract ("Truncation, caps and skipped work must be stated on screen") wearing a search box.
    // The server folds the term into the WHERE of all three queries, so it reaches PAST the cap
    // rather than searching the first 500 names and stopping at the exact ceiling the parameter was
    // added to get past.
    //
    // ONE PARAMETER FOR ALL THREE PICKERS, NOT THREE, and the reason is a silent failure rather than
    // tidiness: FastAPI DISCARDS a query parameter the route does not declare, so a handset sending
    // `assigneeSearch` at a server that only knows `search` would draw a search box that narrows
    // nothing and reports "no matches" — the very defect being closed here, in different clothes.
    // Send the term of the picker being typed into.
    //
    // THE RULE THAT TRAVELS WITH IT, for whoever wires the dialog: never read the absence of an
    // ALREADY-SELECTED id from a narrowed list as "that record is gone" and clear the selection.
    // `ProductForm` on the web does exactly that against a capped page and unlinks the artisan.
    //
    // 120 CHARACTERS is the server's `max_length`; a longer term is a 422 on a request the admin
    // reads as a search that failed. Trim and cap before sending, or let the box itself be capped.
    @GET("tasks/options")
    suspend fun taskOptions(
        @Query("workshopId") workshopId: String? = null,
        @Query("search") search: String? = null
    ): TaskOptionsDto

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
    // ── A designer's OWN provider keys ────────────────────────────────────────────────────
    //
    // NO REVEAL ROUTE, and that is a deliberate absence rather than an omission: the secrets API
    // above has one because the deployment's keys belong to the organisation, and nobody has the
    // equivalent need for somebody else's personal credential. The server takes the owner from the
    // token, so none of these carries a user id — there is no shape of request that reads or
    // writes another person's key.

    /** The catalogue every settings screen is built from: providers, models, capabilities, how-to. */
    @GET("ai/providers")
    suspend fun aiProviders(): AiCatalogueDto

    /** This person's own keys, one row per provider, with no plaintext in any of them. */
    @GET("me/ai-keys")
    suspend fun myAiKeys(): List<UserAiKeyDto>

    /** Save or rotate a key, or change only the model by sending a body with no key. */
    @PUT("me/ai-keys/{provider}")
    suspend fun setMyAiKey(
        @Path("provider") provider: String,
        @Body body: UserAiKeySetBody
    ): UserAiKeyDto

    /** Remove it; this work goes back to whatever key the server itself is set up with. */
    @DELETE("me/ai-keys/{provider}")
    suspend fun deleteMyAiKey(@Path("provider") provider: String): UserAiKeyDto

    /** Ask the provider whether the stored key works, now, and remember the answer. */
    @POST("me/ai-keys/{provider}/test")
    suspend fun testMyAiKey(@Path("provider") provider: String): UserAiKeyDto

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
    //
    // Same pair, same reason, as `datasetManifest`/`datasetManifestStream` above: a folder manifest
    // with `include=transcripts` (or no filter at all) inlines every transcript body in the subtree,
    // so it is unbounded in bytes too. The DOWNLOAD path takes the streamed one. The typed one stays
    // because DataBrowserScreen's transcript panel genuinely needs the whole list resident — it
    // indexes into `transcriptsByFolder` on every toggle — and because it is the fallback for an
    // older server that does not know `?stream=1`.
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
        /**
         * One of the registry's `WORKSHOP_KIND` tokens, or null for "every type".
         *
         * EMPTY MEANS EVERYTHING, BY ABSENCE — Retrofit omits a null `@Query` entirely, so "no type
         * chosen" is a request with no such parameter rather than one asking for the empty string.
         * The route 422s a token it does not recognise instead of answering an empty list, which is
         * the behaviour a stale build wants: a filter this handset knows and the server does not
         * says so, rather than telling a designer that no workshop of that type exists.
         */
        @Query("workshopKind") workshopKind: String? = null,
        @Query("craftName") craftName: String? = null,
        @Query("state") state: String? = null,
        @Query("mineOnly") mineOnly: Boolean = false
    ): DesignWorkshopPageDto

    /**
     * The design workshop this account was most recently given access to — the smart default every
     * record form opens on.
     *
     * ONE ENDPOINT BECAUSE THERE IS ONE QUESTION, asked from seven forms on this client and seven on
     * the web. Deriving "most recently allocated" here would mean fourteen implementations of it,
     * and they would not agree — the server reads `DesignWorkshopViewer.createdAt`, which is when an
     * admin actually allocated the workshop and which no client can see.
     *
     * IT IS A SUGGESTION AND NEVER A SCOPE. The picker still lists everything the server admits and
     * every write is still gated by `load_workshop_or_404`.
     */
    @GET("design-workshops/default-for-me")
    suspend fun designWorkshopDefaultForMe(): DesignWorkshopDefaultDto

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

    /**
     * The whole authorship picture for one workshop — ADMIN AND MASTER ADMIN ONLY.
     *
     * `require_admin` in all but name: `workshop_provenance` raises 403 itself rather than through a
     * dependency, so {ADMIN, MASTER_ADMIN} and nobody else — not a PROFESSOR, and not the workshop's
     * own designers. That is the line `is_admin` draws everywhere else in this API, and it is drawn
     * here because this call crosses OUT of the workshop into the shared record tables and reports
     * one account's data beside another's.
     *
     * A DESIGNER LOSES NOTHING BY NOT HAVING IT, which is why the phone HIDES the control rather than
     * greying it: every per-field stamp still renders under their own boxes on every stage, off the
     * ordinary `GET /stages` read. What this adds is the canonical comparison, and only that.
     *
     * NOT CACHED AND NOT QUEUED, unlike almost everything else in this feature. The whole answer is
     * "what do the shared records say TODAY", so an answer held on the device is the one thing this
     * report may never be: a workshop's own values are a dated observation and are safe to hold, and
     * the other column is by definition not. See [WorkshopRepository.designWorkshopProvenance].
     *
     * It answers for SOFT-DELETED workshops too, deliberately (`load_workshop_or_404` admits admins
     * to them): an audit of who wrote what is most needed on a record somebody has deleted.
     */
    @GET("design-workshops/{id}/provenance")
    suspend fun designWorkshopProvenance(@Path("id") id: String): DwProvenanceReportDto

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // DESIGN REVIEW — the three rating routes.
    //
    // THEIR OWN PREFIX, AND NOT NESTED UNDER `design-workshops`, and that is a permission fact
    // rather than a naming preference. Every route under `design-workshops` goes through
    // `load_workshop_or_404`, which admits the workshop's creator, an admin and the holder of a
    // viewer grant — and what it admits is READ PLUS all 22 stage WRITES. The pool round is by
    // definition the designers that helper turns away, so serving it from that prefix would have
    // meant teaching the shared loader about POOL and handing every designer in the country write
    // access to every finished workshop's fieldwork. `/design-ratings` is a separate, narrow door
    // that leads to the rateable rows and their scores and nothing else about the workshop.
    //
    // EVERY REFUSAL ON ALL THREE IS 404 WITH ONE SENTENCE — "Record not found" — whether the record
    // is missing, soft-deleted, of an entity nobody rates, or simply not this caller's to see. The
    // data set is keyed by cuid and a 403 would turn any designer login into an enumeration of the
    // ministry's archive. So NO CALLER HERE MAY BRANCH ON 404 TO SAY "you do not have access":
    // `lib/workshopCodeLookup.ts` carries the same rule for the scanners.
    //
    // A 503 is the one refusal worth telling apart, and the server writes the sentence for it: the
    // ledger table is not in that deployment's generated client yet, which a restart does not fix
    // and a migration does. `apiErrorMessage` surfaces it verbatim.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Submit a rating, or amend the one this caller already left.
     *
     * ONE ROUTE FOR BOTH, AND 200 ON THE CREATE PATH TOO. The client cannot know which of create,
     * amend and replay it is asking for — that is the point of the endpoint — so a status that
     * varied between them would be a fact about the server's state dressed as a fact about the
     * request. [DesignRatingSavedDto.replayed] is where the difference is reported, and a replay is
     * a SUCCESS: the outbox delivered the same capture twice, which is the ordinary behaviour of a
     * phone with a flaky connection, and no second row can exist.
     *
     * A 403 HERE IS NOT LIKE THE 404s. It is the one deliberate exception in this router — a
     * designer may not rate their own work — and the server says so in a sentence written for the
     * person holding the phone. It is answered as a 403 rather than a 404 precisely because the
     * caller demonstrably knows this record exists: it is theirs. Render it as written; see
     * `DwDesignRatings`' header for why this client cannot pre-empt it.
     */
    @POST("design-ratings")
    suspend fun submitDesignRating(@Body body: DesignRatingBody): DesignRatingSavedDto

    /**
     * Who rated one sketch or prototype, when, and how — redacted server-side on the way out.
     *
     * The `round` is a QUERY parameter and defaults to PEER on the server. It is always sent
     * explicitly from here: a ledger read for the round the screen is NOT showing would put another
     * round's rows under this one's heading, and a default that agrees with this screen today is one
     * somebody can change on the server tomorrow.
     */
    @GET("design-ratings/subjects/{subjectId}")
    suspend fun designRatingLedger(
        @Path("subjectId") subjectId: String,
        @Query("round") round: String,
    ): SubjectLedgerDto

    /**
     * One round's pieces, each with its score, its DEFAULT position and its PLACED position.
     *
     * `workshopId` IS REQUIRED FOR BOTH ROUNDS, POOL INCLUDED — see [RoundRankingDto] for why that
     * is structural and not an unfinished API.
     *
     * `entityKey` defaults to `prototype` on the server and is likewise always sent: this screen
     * offers both rateable entities, and letting the server pick would make the chips silently
     * disagree with the list under them the first time that default moved.
     */
    @GET("design-ratings/rounds/{round}")
    suspend fun designRatingRound(
        @Path("round") round: String,
        @Query("workshopId") workshopId: String,
        @Query("entityKey") entityKey: String,
    ): RoundRankingDto

    /**
     * The questions THIS WORKSHOP'S DESIGNER added to it, and the digest of them.
     *
     * READ-ONLY ON THIS CLIENT, AND THE PUT IS DELIBERATELY NOT BOUND. Authoring a definition is a
     * write to a server-owned contract with an ordering rule the phone cannot evaluate: a replace is
     * not a delete, so what is absent is RETIRED if it has answers and REMOVED if it does not, and
     * rewording an answered field SUPERSEDES it — decisions made against answer rows this handset
     * does not hold. `WorkshopRepository`'s "Custom questionnaires" block already refuses exactly this
     * class of offline write, in writing, for exactly this reason. The definition is authored in the
     * browser, where a 422 from an `extra="forbid"` envelope is read by the person who caused it.
     *
     * Retired sections and fields are ALWAYS in the response — see [DwCustomSections]' header.
     */
    @GET("design-workshops/{id}/custom-sections")
    suspend fun designWorkshopCustomSections(@Path("id") id: String): DwCustomDefinitionDto

    // The options behind one REF field's dropdown.
    //
    // `model` is the registry's `refModel`; `scope` is its `refScope`; `filterBy` is the value of the
    // field named by `refFilterBy` on the same row, which is what turns "every product in the cluster"
    // into "this artisan's products". All three are sent as the registry spelled them — the client
    // invents none of them, so a scope or a model the server later renames cannot end up with two
    // spellings.
    //
    // `scope` WAS MISSING FROM THIS SIGNATURE AND THAT MADE THE NARROWING DEAD ON THE WIRE FOR THE
    // WHOLE HANDSET. The route declares `scope: str = Query(REF_SCOPE_ALL, max_length=16)`
    // (backend/app/api/routes/design_workshops.py), and `reference_options` adds the workshop clause
    // only under `if scope == REF_SCOPE_WORKSHOP and spec.workshop_where and record.workshopId`
    // (backend/app/services/design_workshops.py) — so an OMITTED parameter is not "the server works it
    // out", it is the server being told ALL. `WorkshopRepository.designWorkshopReferences` has always
    // taken a `scope` and always spent it on the cache key alone; the four WORKSHOP-scoped REF fields
    // in the bundled registry (`processStep.processRef` → Process, `existingProduct.artisanRef` →
    // Artisan, `existingProduct.productRef` and `prototype.productRef` → ProductDocumentation) were
    // therefore answered with the first fifty rows of the WHOLE table, name-ascending, on every phone.
    // The browser has always sent it (`scope: field.refScope`, frontend/components/designworkshop/
    // StageReferenceField.tsx), so the two surfaces narrowed differently for the same field — the
    // parity class this repository keeps getting bitten by. [DwField.refScope]'s own KDoc already
    // describes this parameter as something "the picker sends straight back"; until this line existed
    // that sentence described an intention, not the wire.
    //
    // NULLABLE, AND BLANK MUST BECOME NULL AT THE CALL SITE. `refScope` defaults to "" when the
    // registry did not say, and Retrofit sends a non-null value even when it is empty: `scope=` is
    // parsed by FastAPI as the empty string, which `reference_options` answers with a 422 ("scope must
    // be one of ALL, WORKSHOP") rather than by falling back. That 422 is swallowed by the repository's
    // deliberate `runCatching { … }.getOrNull() ?: return`, so the symptom would not be an error — it
    // would be a picker that silently never refreshes again. Omitting the parameter is the only way to
    // say "the server did not tell us a scope, so choose your own default".
    //
    // `search` is declared because the endpoint offers it, and is deliberately NOT used by the phone's
    // picker: the picker fetches the WHOLE list for a (model, scope, filter) triple so it can be cached
    // and searched with no signal at all (see [DwReferenceStore]). A per-keystroke server search would
    // be faster in an office and useless in a courtyard, which is the wrong trade for this app.
    //
    // `recordId` IS THE SCANNED HALF, AND IT IS THE ONLY WAY A PRINTED CODE CAN BECOME AN OPTION.
    // Every other clause the route composes searches PROSE — `spec.search_fields` is a `contains`
    // over names, local names and places, and `id` is in none of them — so a designer scanning a
    // colleague's product card got back the empty list that is byte-identical to "no such record".
    // Sent ONLY by the picker's scan panel and never by the list fetch above: it is additive on the
    // server (absent, the endpoint answers exactly as it always did) and a list fetch that quietly
    // carried one would cache a one-row answer over the whole register.
    //
    // IT DOES NOT REPLACE THE SCOPE OR THE CASCADE, on either side of the wire. `reference_options`
    // appends an `id` clause and nothing else, deliberately, because a by-id lookup that dropped the
    // artisan filter would offer one artisan's work under another's name. So this parameter travels
    // WITH `scope` and `filterBy`, not instead of them.
    //
    // `limit` IS DECLARED FOR THE BY-ID CALL AND IS THE GUARD AGAINST AN OLD SERVER. An id clause
    // matches at most one row, so a by-id answer can never honestly be truncated — and a deployment
    // that has never heard of `recordId` does not refuse the unknown query parameter, it IGNORES it
    // and returns the ordinary list. Asked with `limit=1` that arrives as a visible `truncated: true`
    // instead of as a one-row list a caller might read a record out of, which is exactly how the
    // browser asks (`limit: 1` in `StageReferenceField.resolveScan`). The list fetch sends nothing
    // and keeps the route's own default.
    @GET("design-workshops/{id}/references")
    suspend fun designWorkshopReferences(
        @Path("id") id: String,
        @Query("model") model: String,
        @Query("scope") scope: String? = null,
        @Query("filterBy") filterBy: String? = null,
        @Query("search") search: String? = null,
        @Query("recordId") recordId: String? = null,
        @Query("limit") limit: Int? = null
    ): DwReferenceResponseDto

    // Read the number off a photographed identity card — THE SERVER HALF OF A LADDER WHOSE FIRST
    // RUNG IS THIS PHONE.
    //
    // THIS COMMENT USED TO SAY "ONLINE ONLY, and there is no offline substitute: the recognition runs
    // server-side", AND THAT STOPPED BEING TRUE. `MlKitIdentityCardRecognizer` reads an Aadhaar
    // number on the handset out of a bundled model, with no connection and no Play Services download,
    // and `DwIdentityCardControl.send` asks it FIRST — this route is reached only when the phone found
    // nothing AND there is a connection, because a large vision model still reads a creased or glared
    // card that a bundled 10 MB one gives up on. What remains genuinely online-only is a PEHCHAN
    // number, which has no checksum and therefore cannot be picked out of raw recognised text without
    // guessing; `IdentityCardText`'s header has that argument in full.
    //
    // THE CONNECTIVITY CHECK IS STILL MANDATORY BEFORE THIS CALL, for the reason it always was: a
    // request that hangs for a two-minute timeout in a village is indistinguishable, to the designer
    // holding the phone, from an app that has crashed. What changed is what the check gates. It no
    // longer decides whether the CONTROL is offered — gating that on signal was the state a designer
    // in a courtyard is always in — only whether this request is worth making.
    //
    // `retention` DECLARES WHAT THE CALLER INTENDS TO DO WITH ITS OWN COPY. It is not an instruction:
    // this route has no storage path and its reply says `photograph.stored: false` regardless. It is
    // sent so that the one request in which an unmasked identity document crosses the wire carries
    // the designer's answer to "are you keeping this". A FORM FIELD and not a query parameter,
    // because the route declares it `Form(...)` — see `scan_identity_card`.
    //
    // OMITTING IT IS SAFE BY CONSTRUCTION. `parse_retention` maps everything it does not recognise
    // — missing, blank, "keep", true — onto DISCARD and never raises, so every build shipped before
    // this parameter existed gets the safe half. That is exactly why the default here is
    // [DW_RETENTION_DEFAULT] rather than a nullable with no value: a caller that forgets should land
    // on the same answer the server would have assumed, not on a different one.
    @Multipart
    @POST("design-workshops/ocr/identity")
    suspend fun designWorkshopIdentityOcr(
        @Part file: okhttp3.MultipartBody.Part,
        @Part("retention") retention: okhttp3.RequestBody? = null
    ): DwIdentityOcrDto

    // KEEP THIS IDENTITY PHOTOGRAPH, OR DELETE IT — the decision about a picture that is ALREADY
    // stored, which is the only kind this route accepts.
    //
    // `DISCARD` HARD-DELETES: the S3 object first, then the row, and a storage failure refuses the
    // whole request with a 502 rather than deleting the row anyway. That ordering is deliberately
    // the inverse of `media.delete_media`, which deletes the row and then makes a best-effort
    // attempt at the object — a defensible trade for a photograph of a loom and the wrong one here,
    // because it can end with the JPEG in the bucket and nothing in the database that knows it is
    // there. Everything else in `design_workshops.py` soft-deletes; this route is the stated
    // exception and means it.
    //
    // SO A 502 FROM HERE MEANS NOTHING WAS DELETED. It is not a partial failure to be retried
    // silently or reported as "removed": the row survives, it still points at the bytes, and the
    // designer presses the button again. Show the server's sentence.
    //
    // NOT AUTO-RETRIED anywhere in this client, unlike a presign. Both outcomes change durable state
    // — one deletes a file, the other writes an accountability record — and a request this client
    // repeated on its own would be one decision recorded twice, or a 502 from a half-finished delete
    // turned into a second delete attempt with nobody watching.
    @POST("design-workshops/ocr/identity/retention")
    suspend fun decideIdentityPhotograph(
        @Body body: DwRetentionDecisionBody
    ): DwRetentionResultDto

    // One dictated passage, written down by the SAME provider chain the workshop's own recordings go
    // through — rung 2 of the dictation ladder (see [dwDictationLadder]).
    //
    // ONLINE ONLY, SYNCHRONOUS, AND IT STORES NOTHING. The route holds a worker for the whole
    // provider round trip and returns the text; there is no job, no id and no queue behind it. So
    // this call must never be handed to [OfflineOutbox] the way a record create is: the designer is
    // standing in front of a field waiting for words to appear in it, and a transcript that turns up
    // after the next sync is not a dictation. With no signal, rung 2 is simply unavailable.
    //
    // THE FORM FIELD IS `languageHint` BECAUSE THAT IS WHAT THE ROUTE DECLARES
    // (the `dictate_for_workshop` signature in backend/app/api/routes/design_workshops.py, which
    // declares the same two parts as its id-less sibling), checked against it
    // rather than remembered. The web sends the same name (`frontend/lib/designWorkshops.ts` appends
    // `languageHint`); a note here claiming it sends `language` was true of an older browser build and
    // has been corrected on that side, so it is deleted rather than left to send the next reader
    // looking for a mismatch that no longer exists.
    //
    // A 503 means the deployment has no transcription provider configured, not that the clip was
    // bad. It is shown as "not configured" and remembered for the run — see [DwDictationRun].
    //
    // A 429 is either this designer's daily allowance being spent or the courtesy rate limiter in
    // front of the whole API, told apart by the body and only one of them remembered — see
    // [DwDictationCapRefused]. The CAP is enforced on both dictation routes, per DESIGNER, so nothing
    // about the URL below changes it.
    //
    // THE WORKSHOP ID IS IN THE PATH BECAUSE IT IS THE ONLY THING THAT MAKES THE CONSENT GATE REAL, AND
    // THIS COMMENT USED TO SAY THE OPPOSITE. It read: "`/dictate` remains an unconsented door, and
    // closing it is a separate, dated change to both clients." This is that change, so the sentence is
    // deleted rather than softened — leaving it would be a false claim about the app's own behaviour, of
    // exactly the kind this repository files as a defect.
    //
    // WHAT MOVED AND WHAT DID NOT. The body is unchanged — the same `file`, the same `languageHint` —
    // and the response carries the same keys; that is the server's own guarantee, written into
    // `dictate_for_workshop`'s docstring ("the same multipart body … to this URL instead"). Only the URL
    // moved. `POST /design-workshops/dictate` still exists up there and still takes no id, so it can
    // consult no workshop's `dictationConsent` column and hands the clip straight to the provider chain;
    // retiring it is the server's dated decision, and this client's part is simply not to use it. The
    // browser posts to this same URL (`dictateAudio` in frontend/lib/designWorkshops.ts, whose
    // `workshopId` is required for the reason given below), so the two surfaces are gated alike.
    //
    // A 409 IS THE GATE ITSELF: this workshop's consent is NOT_RECORDED or REFUSED. It carries a
    // sentence naming the next move, and the next move is A PERSON DECIDING and never a retry — which is
    // why the repository reads that sentence out rather than turning the code into one of its own. See
    // [DwDictationConsentRefused].
    //
    // THE ID IS REQUIRED AND HAS NO DEFAULT, on purpose, and the web half made the same choice for the
    // same reason: an optional id would make "the call site that forgot" indistinguishable from "the
    // call site that meant it", and the forgotten one is the one that sends an artisan's recorded voice
    // through the ungated door.
    /**
     * How many server dictations this designer has left today, and where the day ends.
     *
     * ASKED WITHOUT SPENDING ANYTHING — two primary-key reads on the server and no upload. Without
     * it, a handset opened for the first time this morning learns the ceiling only by uploading six
     * megabytes to be refused, which is the exact failure `DwDictationUpload` records for the 503,
     * once per prose field on the stage.
     *
     * NOT WORKSHOP-SCOPED, deliberately: the allowance is a fact about the signed-in account, and the
     * route takes no user parameter for the reason its own docstring gives.
     */
    @GET("design-workshops/dictation-allowance")
    suspend fun designWorkshopDictationAllowance(): DwDictationAllowanceDto

    @Multipart
    @POST("design-workshops/{id}/dictate")
    suspend fun designWorkshopDictate(
        @Path("id") id: String,
        @Part file: okhttp3.MultipartBody.Part,
        @Part("languageHint") languageHint: okhttp3.RequestBody
    ): DwDictateDto

    // One workshop's answer to "may its recordings and dictation leave the device for a third-party
    // transcription service", with who recorded it and when — plan §6 answer 3.
    //
    // ITS OWN ROUTE RATHER THAN `PATCH /{id}`, which is the server's decision: that route's writable
    // set is a hand-written tuple copied in a loop and it records neither the actor nor the moment,
    // and those two facts are the entire content of a consent. Two writes happen behind this one call
    // — the workshop's three columns and a row in its append-only decision log — because a consent can
    // be WITHDRAWN, and "granted on the 3rd, withdrawn on the 9th" is only answerable from a log.
    //
    // GATED ON {DESIGNER, ADMIN, MASTER_ADMIN} server-side, the same set as running a workshop at all;
    // the screen refuses the same set, in words rather than with a greyed button.
    @POST("design-workshops/{id}/dictation-consent")
    suspend fun recordDesignWorkshopDictationConsent(
        @Path("id") id: String,
        @Body body: DwConsentDecisionRequest
    ): DwConsentDecisionDto

    // --- The five AI verbs, and the layers they produce -----------------------------------------
    //
    // Proofread, expand, translate, caption, subtitle. See data/DwAiVerbs.kt for the bodies, the
    // vocabulary, the allowance and the pre-press ladder; this block is the six calls and the four
    // that decide what becomes of a result.
    //
    // ONE GATE CHAIN IN FRONT OF ALL FIVE, in this order, and every one of them can be the answer:
    // `_require_designer` (403) -> `load_workshop_or_404(for_edit=True)` (404, or 409 for a workshop
    // that is soft-deleted) -> the workshop's dictation consent for THIS verb (409) -> the per-designer
    // daily ceiling (429). It is the same `DesignWorkshop.dictationConsent` column rung 2 of the
    // dictation ladder is gated on, asked with a description of what THIS verb sends and where — a
    // caption goes to Gemini and there is no dictation and nothing to type, which is why
    // `dictation_consent.send_for` exists and why the sentence must be shown verbatim rather than
    // classified. [DwAiVerbRefused] carries it.
    //
    // NONE OF THESE MAY EVER BE QUEUED. [OfflineOutbox] exists for record creates that can be replayed;
    // a verb is a provider round trip somebody pays for, counted by `ai_verb_cap.spend` for every run
    // that REACHED a provider including one that then failed. A run banked today and replayed in three
    // days would be charged against a day the designer is not having, over a workshop whose consent may
    // have been withdrawn in between. With no signal these are simply unavailable, and
    // [DW_VERBS_NEED_A_CONNECTION] says so in words including the clause that nothing was queued.
    //
    // AND NONE OF THEM IS AUTO-RETRIED, which is [ApiClient.isSafelyRetriable]'s doing and is worth
    // knowing rather than discovering: a POST is retried only when its path is one of the four
    // side-effect-free upload-setup calls, and these are not among them. A 504 from CloudFront over a
    // verb that the origin actually ran would otherwise spend a second run of the allowance and store a
    // second layer saying the same thing.
    //
    // THE WORKSHOP ID IS THE SERVER'S AND IS REQUIRED. A workshop that exists only on this device has
    // no row for `load_workshop_or_404` to find, and every press would answer a bare 404 "Record not
    // found" — a sentence about a missing record rather than about an unsent workshop. The web shipped
    // exactly that and review caught it; [dwVerbWorkshopId] and [dwVerbGate] are the two guards.

    @POST("design-workshops/{id}/ai-layers/proofread")
    suspend fun designWorkshopProofread(
        @Path("id") id: String,
        @Body body: DwProofreadBody
    ): DwAiVerbResultDto

    // NO `sourceLayerId` ON THIS BODY AND THERE MUST NEVER BE ONE. `AiExpandIn` has no such field so
    // that a client cannot even ask: an expansion invents sentences, and run over an artisan's
    // transcript it would put invented words in a named person's mouth in a document a ministry
    // officer reads. See [dwExpandBody] for the whole argument and the four other places it is kept.
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

    // `sourceMediaId` IS A CLAIM AND NEVER AN AUTHORISATION, which is the server's own wording:
    // `GET /api/media` hands every signed-in account the id of every file in the repository, so
    // `_verb_source_media` checks the id against this workshop's own attached files AND against the
    // caller's media entitlement before any bytes leave the object store. It must be a
    // [DraftMedia.remoteMediaId] — a media id this device invented names nothing up there.
    @POST("design-workshops/{id}/ai-layers/caption")
    suspend fun designWorkshopCaptionMedia(
        @Path("id") id: String,
        @Body body: DwMediaVerbBody
    ): DwAiVerbResultDto

    // THE ONE VERB THAT COSTS A SECOND UPLOAD OF AUDIO THIS SYSTEM HAS ALREADY TRANSCRIBED, and the
    // route calls that "a defect rather than a design": both transcription providers already return
    // timings and both discard them one line after parsing, so nothing in the archive can be subtitled
    // without sending the recording again. Say so before the press — [DW_SUBTITLES_SECOND_UPLOAD_NOTE].
    // `language` is deliberately not sent: `AiMediaVerbIn` documents that subtitles ignore it, because
    // a cue list is in whatever language was spoken.
    @POST("design-workshops/{id}/ai-layers/subtitles")
    suspend fun designWorkshopSubtitleMedia(
        @Path("id") id: String,
        @Body body: DwMediaVerbBody
    ): DwAiVerbResultDto

    /**
     * One SUBTITLES layer as a `.srt` or `.vtt` file a player can open.
     *
     * `@Streaming` and a raw [Response] rather than a decoded body, for the two reasons the
     * questionnaire downloads state: Retrofit would otherwise buffer the whole file, and a non-2xx
     * must arrive as a RESPONSE so the server's own sentence can be read out of it rather than
     * surfacing as "HTTP 422".
     *
     * NOT GATED ON ACCEPTANCE, matching the route, which is deliberate and says so: *"requiring
     * acceptance first would mean accepting subtitles nobody has watched, which is the opposite of
     * what acceptance is for."* This is the designer looking at what the model produced, in the only
     * form in which subtitles can be judged, which is played against the video. Rule 3 is untouched —
     * this is not a report, and the annexure refuses an unaccepted layer twice over.
     *
     * `speakers` IS OFF BY DEFAULT AND THE LABELS ARE A MODEL'S GUESS. Nobody told the engine how many
     * people were in the room; it decided from the audio and can merge two quiet voices or split one
     * person who moved away from the microphone. The `.vtt` carries that caution inside the file as a
     * WebVTT `NOTE`; SubRip has no comment syntax and cannot, so a `.srt` carries the labels alone.
     * The server also puts `.speakers` in the filename, precisely so the two files can be told apart
     * in a downloads folder — which is why the name is taken from `Content-Disposition` and never
     * invented here.
     */
    @Streaming
    @GET("design-workshops/{id}/ai-layers/{layerId}/subtitles.{fmt}")
    suspend fun designWorkshopSubtitleFile(
        @Path("id") id: String,
        @Path("layerId") layerId: String,
        @Path("fmt") fmt: String,
        @Query("speakers") speakers: Boolean? = null
    ): Response<ResponseBody>

    // --- What becomes of a layer: read it, accept it, take a name off it, decline it -------------
    //
    // RULE 1 OF THE LAYERING LAW LIVES HERE. A verb's output is a ROW BESIDE the designer's words and
    // never a replacement for them, inert until a named person accepts it — so a client that could run
    // the five verbs and not reach these four would produce layers that can never legitimately reach a
    // report, which is the whole feature with its point removed.
    //
    // LISTABLE BY ANYONE WHO CAN READ THE WORKSHOP; READABLE PER RECORDING, WHICH IS NOT THE SAME GATE.
    // The provenance — which tier, which model, accepted by whom — is what a reviewer opens the screen
    // for and is nobody's recording. A layer's TEXT is a stored copy of a transcript, gated per media
    // file, so a row standing on a recording this account may not read comes back with `textWithheld`
    // true and no text, preview, payload or character count. See [DwAiLayerDto].

    @GET("design-workshops/{id}/ai-layers")
    suspend fun designWorkshopAiLayers(
        @Path("id") id: String,
        @Query("kind") kind: String? = null,
        // OFF BY DEFAULT ON THE SERVER AND LEFT OFF HERE. A workshop can hold twenty-five interviews
        // and an hour of speech is tens of kilobytes, so a list with the text in would be megabytes on
        // one bar of signal — and unread, because a list is scanned by `preview` and `textChars`. Ask
        // for it when showing ONE layer to the person about to put their name to it.
        @Query("includeText") includeText: Boolean? = null,
        // Layers a designer declined are soft-deleted and kept, so "the model proposed this and a
        // person said no" stays answerable. Out of the default list, because a declined suggestion
        // re-offered is the same suggestion.
        @Query("includeDeleted") includeDeleted: Boolean? = null
    ): DwAiLayerListDto

    // TWO WRITES BEHIND ONE CALL, and both come back: the layer gains `acceptedAt`/`acceptedById` (the
    // current state the report builder reads) and a decision row is appended (the history, which is
    // what survives a withdrawal). The audit being visible in the response is what stops a client
    // rendering acceptance as a checkbox.
    //
    // REFUSED 403 WHEN THIS ACCOUNT MAY NOT READ THE RECORDING THE LAYER STANDS ON. An acceptance is
    // somebody stating they read this text and the report prints their name beside it; a signature on
    // a page the signer is not allowed to open is worth less than no signature.
    @POST("design-workshops/{id}/ai-layers/{layerId}/accept")
    suspend fun acceptDesignWorkshopAiLayer(
        @Path("id") id: String,
        @Path("layerId") layerId: String,
        @Body body: DwAiLayerDecisionBody
    ): DwAiLayerDecisionResultDto

    // `unaccept` AND NOT `withdraw`, which is the route's own spelling and is checked against it.
    //
    // DELIBERATELY NOT GATED THE WAY ACCEPT IS: taking a name off states nothing about the text and
    // must stay reachable even after the recording's permissions have changed under it, or a grant
    // withdrawn between the acceptance and the doubt would trap an accepted layer in a report with
    // nobody able to unaccept it. The response still withholds the TEXT; what is not gated is the act.
    @POST("design-workshops/{id}/ai-layers/{layerId}/unaccept")
    suspend fun unacceptDesignWorkshopAiLayer(
        @Path("id") id: String,
        @Path("layerId") layerId: String,
        @Body body: DwAiLayerDecisionBody
    ): DwAiLayerDecisionResultDto

    // DECLINE. Soft, 204, and it does not touch what the layer was made from: `deletion_plan` sets
    // exactly `deletedAt` and `deletedById` on exactly one row and has no branch that reads
    // `sourceMediaId` or `sourceLayerId`. A 409 when other layers derive from this one — deleting a
    // raw transcript out from under a cleaned one would leave the cleaned one describing something no
    // screen will show.
    @DELETE("design-workshops/{id}/ai-layers/{layerId}")
    suspend fun declineDesignWorkshopAiLayer(
        @Path("id") id: String,
        @Path("layerId") layerId: String
    )

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

    // `search` MATCHES NAME OR EMAIL AND IS APPLIED BY THE SERVER, inside the same query as the
    // eligibility rule. Null omits the parameter entirely, which is the whole (capped) list. It is not
    // a convenience over a list this client already holds: the answer is capped at 2000 accounts and
    // the cap is reached on a real repository, so an eligible colleague sorting past the cut cannot be
    // reached any other way. Filtering the capped list on the phone would search only the part of the
    // alphabet that fitted — the same defect wearing a search box. Over 120 characters the server
    // answers 422; `dwViewerSearchTerm` is what keeps this side inside that.
    @GET("design-workshops/eligible-viewers")
    suspend fun eligibleDesignWorkshopViewers(
        @Query("search") search: String? = null
    ): DwEligibleViewerListDto

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

    // --- THE FIFTH SCOPE: who INSPECTS one design workshop, and what an inspector may read -------
    //
    // ITS OWN PREFIX, `design-workshop-inspections`, AND THAT IS A GUARD RAIL RATHER THAN A FILING
    // DECISION. `/design-workshops` already carries `GET /{workshop_id}`, which swallows any literal
    // path mounted after it — but the deciding reason is stronger than route ordering: the caller of
    // every route below is, by definition, somebody `load_workshop_or_404` turns away. An inspector
    // is not in `DESIGN_WORKSHOP_ROLES`, so that loader 404s them, and a route sharing that prefix
    // invites the next reader to "fix" the inconsistency by widening the shared loader — which
    // grants STAGE WRITES, because `load_workshop_or_404(..., for_edit=True)` performs no role check
    // at all. Do not move these five up beside the viewers block.
    //
    // TWO DOORS, AND THEY ARE NOT NESTED. The two administration routes are `Depends(require_admin)`
    // — the inspected must not choose the inspector, so a designer gets no say at all, not even a
    // "suggest" route. The three read routes are `Depends(require_inspector)`, which is set
    // membership on {INSPECTOR} and **403s an ADMIN and a MASTER ADMIN by name**. So an account that
    // may call the first pair may not call the second, and vice versa: this is the one family in
    // this interface where the two gates are DISJOINT rather than one being a widening of the other.
    // Nothing here enforces either; the screens do, from `canInspectDesignWorkshops` and
    // `FieldPermissions.isAdmin`, and the server does again.
    //
    // EVERY ROUTE AN INSPECTOR CAN REACH IS A GET, AND THAT IS THE FEATURE. There is no PATCH twin,
    // no stage save and no report route on this prefix, and `load_inspectable_workshop_or_404` takes
    // no `for_edit` parameter — so there is no argument a request could carry that turns a read into
    // a write. Adding a non-GET here is not an extension of this block; it is the end of the scope.
    // See data/DesignWorkshopInspections.kt, which also records why none of this is cached offline.
    //
    // A 404 FROM `eligible-inspectors` MEANS THE DEPLOYMENT PREDATES THE FEATURE, not that a record
    // is missing: it is the one call in the family carrying no id, so on a server without the route
    // FastAPI matches it against `GET /{workshop_id}` and answers 404 "Record not found".
    // `dwInspectionAdministrationMissing` is what tells those apart, and it is pinned to this call
    // because asking it of `/{id}/inspectors` would be unanswerable.

    // `search` MATCHES NAME OR EMAIL AND IS APPLIED BY THE SERVER, inside the same query as the
    // eligibility rule — filtering the answer on the phone would search only the part of the
    // alphabet that fitted under `ELIGIBLE_INSPECTOR_LIMIT`. Over 120 characters the server answers
    // 422; `dwInspectorSearchTerm` is what keeps this side inside that.
    @GET("design-workshop-inspections/eligible-inspectors")
    suspend fun eligibleDesignWorkshopInspectors(
        @Query("search") search: String? = null
    ): DwEligibleInspectorListDto

    @GET("design-workshop-inspections/{id}/inspectors")
    suspend fun designWorkshopInspectors(@Path("id") id: String): DwInspectorListDto

    // REPLACES the whole set, exactly as the viewers PUT does: there is no add route and no remove
    // route, so a caller that posts only what it just ticked has silently ended everybody else's
    // inspection. The answer is the set as the SERVER now holds it rather than an echo of what was
    // sent. An unknown, ineligible, barred or already-on-the-workshop id refuses the ENTIRE call
    // with a 422 naming the account and the remedy — never a silent skip.
    @PUT("design-workshop-inspections/{id}/inspectors")
    suspend fun setDesignWorkshopInspectors(
        @Path("id") id: String,
        @Body body: DwInspectorsBody
    ): DwInspectorListDto

    // THE INSPECTOR'S OWN LIST. Paged like every other list in this API, and answering
    // `workshop_summary` rows — the same serialiser `GET /design-workshops` uses, which is why it
    // decodes into [DesignWorkshopPageDto] rather than a type of its own.
    //
    // AN INSPECTOR WITH NO INSPECTION ROW SEES AN EMPTY PAGE, AND THAT IS THE WHOLE SCOPE. There is
    // no "all workshops" arm, no rank fallback and no `createdById` arm — an inspector creates
    // nothing. So an empty answer here is a fact about assignments and never a failure, which is
    // why the screen over it keeps "not yet loaded", "loaded and empty" and "failed" as three
    // distinct states.
    //
    // NO `mineOnly`, NO `statusFilter`, NO `craftName`, NO `state`. The route declares none of them:
    // `APIModel` is not in play for query parameters, but FastAPI ignores unknown ones silently,
    // which is worse — a filter sent here would appear to work and narrow nothing.
    @GET("design-workshop-inspections")
    suspend fun inspectableDesignWorkshops(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("search") search: String? = null
    ): DesignWorkshopPageDto

    // ONE WORKSHOP UNDER INSPECTION — the read-only twin of `GET /design-workshops/{id}`, and the
    // differences are the point rather than an omission: no `transcripts`, no
    // `dictationConsentByName`, and `readOnly: true` on the wire. Decoded into its OWN type and not
    // into [DesignWorkshopDetailDto]; see [DwInspectionDetailDto] for why one shared type would make
    // "is this writable" a question nobody can answer from the payload alone.
    @GET("design-workshop-inspections/{id}")
    suspend fun workshopUnderInspection(@Path("id") id: String): DwInspectionDetailDto

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
    // ── THE FILTERS ARE DECLARED NOW, AND THE TRADE THIS COMMENT USED TO RECORD IS REVERSED ──────
    //
    // What stood here said that `search` and `activeOnly` were deliberately NOT declared, because the
    // screen fetched the whole roster once and filtered it on the device — *"a per-keystroke server
    // search is faster in an office and useless in a courtyard on 2G"* — and that the walk over
    // `page` was what kept the trade honest.
    //
    // IT DID NOT KEEP IT HONEST, AND THE ARGUMENT HAD THE WRONG SUBJECT. The walk stopped at 500
    // rows (100 × 5) against a table `design_workshop_viewers.py:106` counts at about 1,300, so on
    // this repository the device-side box was already searching a PREFIX of the roster and answering
    // "no match" about designers who exist. Worse, the walk read `createdAt desc` from page one, so
    // what it kept was the NEWEST empanelments and what it lost was the OLDEST — precisely the row
    // this screen is opened for, the designer empanelled two seasons ago standing in front of the
    // admin saying they cannot sign in. And the courtyard argument is about the wrong screen: this is
    // an ADMIN roster read by an administrator at a desk, not a record form filled in a village, and
    // the offline answer for it was never a stale local filter but an honest sentence saying the
    // roster needs the network (DROPDOWN_DESIGN §3.5).
    //
    // So every filter goes to the server, one page comes back, and nothing is narrowed on the device
    // — rule (iv) of §4.6. Five requests became one, which is also the cheaper trade on 2G.
    //
    // EVERY PARAMETER IS NULLABLE AND NULL IS OMITTED BY RETROFIT, which is rule (i): "everything" is
    // spelled by ABSENCE and has no second spelling. `roles`, `institutions` and `status` are
    // COMMA-JOINED single values rather than repeated keys — §4.1 accepts both, and the comma is the
    // spelling that fails LOUDLY (a 422 naming the valid values) against a server that has not
    // learned the parameter, where a repeated key is silently reduced to its last value. See
    // `ui/RosterFilters.tokenList`.
    //
    // `activeOnly` IS GONE FROM THIS SIGNATURE AND MUST NOT COME BACK. `standing` is the same
    // question in the new grammar, and sending both is a 422 rather than a silent winner
    // (§4.1). The server keeps accepting `activeOnly` for clients that have not been updated;
    // this one is not that client.
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

    // The vocabulary behind the institution filter — see [RosterInstitutionsDto].
    //
    // NEW IN THE SAME WAVE AS THE FILTER, so the two halves can be deployed in either order and this
    // client must survive both. A handset that lands first meets a 404, which is an ANSWERED refusal
    // and not a transient one, so the picker prints §3.5's could-not-be-listed sentence rather than
    // pretending the repository holds no institutions. The screen never blocks on it: the roster
    // itself is readable, filterable and suspendable with this list missing.
    @GET("designers/roster/institutions")
    suspend fun designerRosterInstitutions(): RosterInstitutionsDto

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

    // ── The PLATFORM allow-list: who may sign in at all ──────────────────────────────────────────
    //
    // A DIFFERENT LIST FROM THE DESIGNER ROSTER ABOVE, on a different prefix, with a different
    // permission behind it (`require_access_manager`). The roster says who is empanelled as a
    // designer; this says who may reach the application, and the sign-in gate reads THIS one for
    // every account except the master admin's.
    //
    // `search` and `status` have ALWAYS been declared here — this screen has been server-filtered and
    // server-paged since it was written, because the table holds every address the institution has
    // ever admitted OR REFUSED, including every stranger who has ever tried a password against the
    // front door, and it grows without bound in a direction nobody controls. The pending queue is
    // fetched as its own `?status=PENDING` page for the same reason.
    //
    // WHAT REQUIREMENT 30 ADDS IS THE REST OF §4.1'S GRAMMAR, and one change to a parameter that was
    // already here: `status` is now COMMA-JOINED and multi-valued. A single value is byte-identical
    // to what this client sent before — `?status=PENDING` — and the server's own note says a lone
    // value must stay behaviourally identical as the parameter becomes plural, which is why the
    // pending queue's own call is untouched by any of this.
    //
    // `institutions` is NOT declared, and its absence is a decision rather than an omission:
    // `AccessRoster` has no institution column, and joining to `DesignerRoster.institution` by email
    // would narrow the allow-list to the subset that is ALSO empanelled as a designer while calling
    // itself an institution filter — silently hiding exactly the pending strangers this screen exists
    // to decide about. `ui/RosterFilters.ACCESS_INSTITUTION_NOTE` says so on the screen.
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

    // THE NOTIFICATION. One integer, cheap enough to ride the app-wide poll that already runs while
    // somebody is signed in — which is why this client adds no timer of its own for it.
    @GET("access/roster/pending-count")
    suspend fun pendingAccessCount(): PendingAccessCountDto

    @POST("access/roster")
    suspend fun addToAccessRoster(@Body body: AccessRosterCreateBody): AccessRosterDto

    // Approve or refuse a waiting request. APPROVING also lifts an existing account to the granted
    // tier when that is higher than it already holds — never lower — so approving somebody at
    // Researcher cannot demote a professor.
    @POST("access/roster/{id}/decision")
    suspend fun decideAccessRequest(
        @Path("id") id: String,
        @Body body: AccessDecisionBody
    ): AccessRosterDto

    // A JsonObject for the reason on [accessRosterUpdateJson]: `explicitNulls = false` would drop the
    // very keys that mean "clear this", and the server's `exclude_unset` would then keep the old
    // value with nothing on screen to say so.
    @PATCH("access/roster/{id}")
    suspend fun updateAccessEntry(
        @Path("id") id: String,
        @Body body: JsonObject
    ): AccessRosterDto

    // SUSPENDS, and answers 200 with the suspended row. It does not delete — the row holds the
    // joining date, the attempt history and who admitted them, and because the sign-in gate reads a
    // MISSING row as PENDING, a real delete would put the person straight back into the queue they
    // were just removed from.
    @DELETE("access/roster/{id}")
    suspend fun suspendAccessEntry(@Path("id") id: String): AccessRosterDto

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
    // ── THE .xlsx HALF OF THE API: ABSENT UNTIL 2026-08-16, NOW BOUND. THE DECISION WAS REVERSED ──
    //
    // WHAT THIS COMMENT USED TO SAY, KEPT SO THE REVERSAL IS LEGIBLE RATHER THAN MYSTERIOUS:
    //
    //     "THE .xlsx HALF OF THE API IS DELIBERATELY ABSENT. `GET /questionnaires/pro-forma`,
    //      `POST /questionnaires/upload` and `POST /questionnaires/{id}/upload` are how the form is
    //      BUILT, and a form is built on a laptop in a spreadsheet, not on a handset — a designer
    //      picking an .xlsx out of Android's document provider, on a phone with no spreadsheet
    //      application, is a worse route to the same place. What the handset is for is the other
    //      half: answering."
    //
    // WHO REVERSED IT AND WHY. The user asked for BOTH surfaces on the handset explicitly — the
    // downloads and the uploads — in the brief that added the questionnaire-interchange feature
    // (2026-08-16). A requirement from the person the app is for outranks a client author's
    // judgement about what a phone is for, and this note is not left standing as a reason the
    // binding "should" be absent while the binding is right there underneath it.
    //
    // THE OLD ARGUMENT WAS ALSO PARTLY WRONG, AND THE WRONG PART IS THE POINT OF THE FEATURE. It
    // reasoned entirely about BUILDING a form. Three of these six endpoints build nothing:
    // `question-set.xlsx` is how one designer HANDS another an instrument, and receiving it is
    // `POST /questionnaires/upload`. That exchange happens between two people standing in the same
    // courtyard holding phones, and the laptop argument never covered it. Nor did it cover a
    // designer who wants their own fieldwork out of the app while they are still at the site.
    //
    // WHAT REMAINS TRUE, AND IS NOW SAID ON SCREEN INSTEAD OF BEING ENFORCED BY AN ABSENCE: a phone
    // is a poor place to AUTHOR forty questions. So the pro-forma download exists to be sent
    // somewhere else, and the list screen says so rather than pretending the handset is where the
    // typing happens.
    //
    // THE THREE DOWNLOADS ARE THREE ROUTES AND NOT ONE ROUTE WITH A FLAG, exactly as the server
    // made them, and this client keeps them apart all the way to the button. `download_question_set`
    // states the reason: `?questionsOnly=` would put the difference between "a question list" and
    // "every respondent I have ever interviewed" inside a boolean that defaults. See
    // [DwQuestionnaireArtefact].

    // The blank workbook a questionnaire is typed into. `_require_designer` only — it contains
    // nothing about anybody.
    @Streaming
    @GET("questionnaires/pro-forma")
    suspend fun questionnaireProForma(): Response<ResponseBody>

    // ONE QUESTIONNAIRE, LOSSLESSLY: every sitting, every respondent's name, every answer, and the
    // retired questions too. Gated on the server to the owner, an admin, or a designer working on
    // its design workshop — the same rule `read_questionnaire` applies to the sittings, because
    // this workbook IS the sittings.
    //
    // A 403 here is not a bug to be retried: it carries the server's own sentence, which names the
    // question set as the thing to download instead. Show it verbatim.
    @Streaming
    @GET("questionnaires/{id}/xlsx")
    suspend fun questionnaireWorkbook(@Path("id") id: String): Response<ResponseBody>

    // THE QUESTIONS ALONE — no answers, no respondents, no sittings. Any designer may take it, which
    // is a WIDER gate than the workbook above and deliberately so: `read_questionnaire` already
    // hands the questions of any questionnaire to any designer, and this is that same openly
    // readable half written into a spreadsheet. The difference between this and `/xlsx` is not a
    // filter that could be forgotten — `load_question_set` never issues the entry or answer queries
    // at all.
    @Streaming
    @GET("questionnaires/{id}/question-set.xlsx")
    suspend fun questionnaireQuestionSet(@Path("id") id: String): Response<ResponseBody>

    // Create a questionnaire from a filled-in workbook.
    //
    // `title` AND `designWorkshopId` ARE FORM FIELDS ON THE MULTIPART BODY, NOT QUERY PARAMETERS,
    // and the route's docstring says why it matters: declared as bare defaults on the server they
    // would have been read as query parameters, and a client that appended them to the body would
    // have had them silently ignored — an untitled questionnaire attached to nothing, with a 201
    // saying it went fine. `@Part` is therefore correct here and `@Query` would not be.
    //
    // THE RESPONSE CARRIES `report.problems` AND `report.provenance`, AND BOTH ARE THE FEATURE.
    // A workbook that came out of the platform imports its QUESTIONS ONLY — its answers are
    // fieldwork already recorded in this database under the names of the people who recorded it,
    // and writing them again would fork that fieldwork under one re-stamped author. The server says
    // which branch it took; see [QFormChangeReportDto] and [qFormProvenanceNotice].
    @Multipart
    @POST("questionnaires/upload")
    suspend fun uploadQuestionnaire(
        @Part file: okhttp3.MultipartBody.Part,
        @Part("title") title: okhttp3.RequestBody? = null,
        @Part("designWorkshopId") designWorkshopId: okhttp3.RequestBody? = null,
    ): QFormUploadResultDto

    // Re-upload an edited workbook OVER an existing questionnaire. Owner-only on the server.
    //
    // A 409 means the file's Details sheet names a DIFFERENT questionnaire — the designer picked the
    // wrong .xlsx out of their downloads folder. That refusal is protecting them from retiring this
    // questionnaire's entire question set as "absent from the upload" in one press, so its sentence
    // is shown as written rather than turned into "upload failed".
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

    // Use this questionnaire again, as a template, at ANOTHER design workshop.
    //
    // THE OWNER ASKED FOR THIS IN THESE WORDS: questionnaires "would usually be scoped to the
    // workshops, but the designers would have the permission to use the same questionnaire later on
    // for a different workshop as well in case they want to reuse the same template." The server
    // built and tested the route; this client bound eighteen of the module's nineteen
    // `questionnaires/…` routes and not this one, so a designer standing at the second workshop with
    // the instrument from the first had no way to lift it from the handset. With it bound the two
    // sides match route for route (re-check: `grep -c '^@router\.'` in
    // backend/app/api/routes/questionnaire_forms.py against the `@…("questionnaires…")` count here;
    // 19 = 19 on 2026-08-27).
    //
    // IT COPIES; IT DOES NOT SHARE. Questions and sections come across, sittings and answers do not,
    // and the original keeps every answer ever recorded against it. See [questionnaireReuseJson] for
    // the body's three optional fields (an EMPTY body is meaningful: an unattached copy) and
    // [QFormReuseResultDto] for what comes back.
    //
    // NOT OWNER-GATED ON THE SERVER, AND THIS CLIENT MUST NOT GATE IT EITHER. That is argued in the
    // route's own docstring and it is not an oversight: the QUESTIONS of any questionnaire already
    // leave this system for any designer through `questionnaires/{id}/question-set.xlsx` bound
    // above, whose rule is "this file is exactly the openly readable half". Refusing here would
    // refuse in JSON what the .xlsx door hands over, and be routed around by downloading that file
    // and re-uploading it — which produces the same row with NO provenance recorded at all. What IS
    // gated is the TARGET: `_require_attachable_workshop` wants workshop creator, admin or a viewer
    // grant, so offer only workshops this account can already write to (404 for one it cannot see,
    // 409 for a soft-deleted one), and ask BEFORE anything is written so a refusal leaves no orphan.
    //
    // A DEACTIVATED SOURCE IS STILL REUSABLE and that is deliberate — `isActive: false` is this
    // API's stand-in for a delete, and a retired instrument is exactly the thing a designer wants to
    // lift for a new round. Do not filter it out of whatever list offers this.
    //
    // JsonObject for the reason [customQuestionnaireUpdateJson] gives, on a different field:
    // `description` is a tri-state the server reads through `exclude_unset`, and the converter's
    // `explicitNulls = false` would drop the explicit null that means "start it empty".
    @POST("questionnaires/{id}/reuse")
    suspend fun reuseQuestionnaire(
        @Path("id") id: String,
        @Body body: JsonObject
    ): QFormReuseResultDto

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
