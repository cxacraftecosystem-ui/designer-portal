package com.designprototype.workshop.questionnaire.portal

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.designprototype.workshop.BuildConfig
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.ArtisanCreateRequest
import com.designprototype.workshop.data.BackgroundSync
import com.designprototype.workshop.data.OfflineOutbox
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.questionnaire.data.QWorkshopOption
import com.designprototype.workshop.questionnaire.ui.NewArtisan
import com.designprototype.workshop.ui.DwQrLiveScanControl
import kotlinx.serialization.json.Json
import retrofit2.Retrofit

/**
 * THE DESIGNER PORTAL'S HALF OF THE SHARED QUESTIONNAIRE TREES. NOT SHARED.
 *
 * `questionnaire/data`, `questionnaire/ui` and `recording` are the same Kotlin in both portals once
 * the package prefix is normalised (`scripts/questionnaire_android.py`, UNIFY U10). Everything that
 * genuinely differs between the portals is reached through this one object, which documentation-
 * portal declares with the same members over its own code:
 *
 * - the HTTP stack ([retrofit], [json]): this app's `ApiClient`, so the gateway retry, the session
 *   signals and the clock headers apply to every questionnaire call;
 * - who is signed in ([userId]) and how a background pass is asked for ([requestSync]: JobScheduler
 *   here, WorkManager there);
 * - what a session files under ([FILES_UNDER_DESIGN_WORKSHOP]: design workshops here) and the
 *   workshops the start sheet offers ([workshops], [defaultWorkshopId]);
 * - creating an artisan registered on the phone ([artisanCreateJson], [createArtisan]);
 * - the old outbox entries that must drain through the shims before the v2 queue runs
 *   ([legacyQuestionnaireWaiting]);
 * - the live QR reader ([LiveQrScan]): CameraX's Compose viewfinder here.
 */
object QuestionnairePortal {
    const val NAME = "designer-portal"

    /** Sessions here file under a design workshop (`designWorkshopId`); Field Repository's under a workshop. */
    const val FILES_UNDER_DESIGN_WORKSHOP = true

    val json: Json get() = ApiClient.json

    val apiBaseUrl: String get() = BuildConfig.DEFAULT_API_BASE_URL

    fun retrofit(context: Context): Retrofit = ApiClient.retrofit(TokenStore(context.applicationContext))

    fun token(context: Context): String? = TokenStore(context.applicationContext).getToken()

    fun userId(context: Context): String? = TokenStore(context.applicationContext).getUser()?.id

    fun userName(context: Context): String? = TokenStore(context.applicationContext).getUser()?.name

    /** Send what is queued as soon as there is a network, app open or not (`data/BackgroundSync.kt`). */
    fun requestSync(context: Context) = BackgroundSync.requestSoon(context)

    /** The artisan create an offline registration replays before its session (a frozen body). */
    fun artisanCreateJson(artisan: NewArtisan, workshopId: String?): String = json.encodeToString(
        ArtisanCreateRequest.serializer(),
        ArtisanCreateRequest(
            name = artisan.name.trim(),
            place = artisan.place.trim(),
            aadhaarNumber = artisan.aadhaarDigits.takeUnless { artisan.aadhaarNotShared }?.ifBlank { null },
            dos = artisan.dos.trim().ifBlank { null },
            donts = artisan.donts.trim().ifBlank { null },
            designWorkshopId = workshopId?.ifBlank { null },
        )
    )

    /** Creates the artisan an offline registration queued; returns the server's id. */
    suspend fun createArtisan(context: Context, idempotencyKey: String, bodyJson: String): String {
        val api = ApiClient.create(TokenStore(context.applicationContext))
        return api.createArtisan(json.decodeFromString(ArtisanCreateRequest.serializer(), bodyJson), idempotencyKey).id
    }

    /** The design workshops this account may file a session under, newest first (B30). */
    suspend fun workshops(context: Context): List<QWorkshopOption> {
        val api = ApiClient.create(TokenStore(context.applicationContext))
        return api.designWorkshops(page = 1, pageSize = 100).items.filter { it.deletedAt == null }.map { w ->
            QWorkshopOption(
                id = w.id,
                title = w.title.ifBlank { w.workshopCode ?: w.id },
                code = w.workshopCode,
                place = listOfNotNull(w.venue?.ifBlank { null }, w.district?.ifBlank { null }).joinToString(", ").ifBlank { null },
                district = w.district?.ifBlank { null },
                state = w.state?.ifBlank { null },
                craft = w.craftName?.ifBlank { null },
                updatedAt = w.updatedAt ?: w.createdAt,
            )
        }
    }

    /** The design workshop this account was most recently given (the start sheet's default, B30). */
    suspend fun defaultWorkshopId(context: Context): String? =
        runCatching { ApiClient.create(TokenStore(context.applicationContext)).designWorkshopDefaultForMe().workshopId }.getOrNull()

    /**
     * Old-format questionnaire entries still waiting in this app's outbox: the global form's interview
     * creates and the forms' sitting entries queued by builds before v2. They drain through the
     * server's shims first (UNIFY §4.5), and the v2 queue waits for them.
     */
    suspend fun legacyQuestionnaireWaiting(context: Context): Int =
        runCatching {
            OfflineOutbox.all(context).count { entry -> entry.failure == null && entry.type in LEGACY_QUESTIONNAIRE_TYPES }
        }.getOrDefault(0)

    /**
     * The global form's interviews (`questionnaire`), a form created offline (`customQuestionnaire`)
     * and the forms' queued authoring and sitting operations (`questionnaireAuthoring`). A session on
     * a form made on this phone names the form's temporary id, so the forms land first.
     */
    private val LEGACY_QUESTIONNAIRE_TYPES = setOf("questionnaire", "questionnaireinterview", "customQuestionnaire", "questionnaireAuthoring")

    /**
     * The server's id for a form this phone created offline, once it has landed; the id itself for
     * any other form; null while the form is still waiting in the outbox (the session waits too).
     */
    suspend fun resolveFormId(context: Context, id: String): String? =
        if (!com.designprototype.workshop.data.isTempId(id)) id
        else com.designprototype.workshop.data.TempIdMap.all(context.applicationContext)[id]

    /** The live QR reader of the join screen: one tap opens the camera, a read code comes back once. */
    @Composable
    fun LiveQrScan(
        label: String,
        pickLabel: String?,
        onText: (String) -> Unit,
        onRefusal: (String) -> Unit,
        modifier: Modifier = Modifier,
        alongside: (@Composable () -> Unit)? = null
    ) {
        DwQrLiveScanControl(
            enabled = true, onText = onText, onRefusal = onRefusal, modifier = modifier, label = label,
            pictureLabel = pickLabel, captureLabel = null, hint = null, alongside = alongside
        )
    }
}
