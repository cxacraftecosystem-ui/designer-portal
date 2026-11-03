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

object QuestionnairePortal {
    const val NAME = "designer-portal"

    const val FILES_UNDER_DESIGN_WORKSHOP = true

    val json: Json get() = ApiClient.json

    val apiBaseUrl: String get() = BuildConfig.DEFAULT_API_BASE_URL

    fun retrofit(context: Context): Retrofit = ApiClient.retrofit(TokenStore(context.applicationContext))

    fun token(context: Context): String? = TokenStore(context.applicationContext).getToken()

    fun userId(context: Context): String? = TokenStore(context.applicationContext).getUser()?.id

    fun userName(context: Context): String? = TokenStore(context.applicationContext).getUser()?.name

    fun requestSync(context: Context) = BackgroundSync.requestSoon(context)

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

    suspend fun createArtisan(context: Context, idempotencyKey: String, bodyJson: String): String {
        val api = ApiClient.create(TokenStore(context.applicationContext))
        return api.createArtisan(json.decodeFromString(ArtisanCreateRequest.serializer(), bodyJson), idempotencyKey).id
    }

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

    suspend fun defaultWorkshopId(context: Context): String? =
        runCatching { ApiClient.create(TokenStore(context.applicationContext)).designWorkshopDefaultForMe().workshopId }.getOrNull()

    suspend fun legacyQuestionnaireWaiting(context: Context): Int =
        runCatching {
            OfflineOutbox.all(context).count { entry -> entry.failure == null && entry.type in LEGACY_QUESTIONNAIRE_TYPES }
        }.getOrDefault(0)

    private val LEGACY_QUESTIONNAIRE_TYPES = setOf("questionnaire", "questionnaireinterview", "customQuestionnaire", "questionnaireAuthoring")

    suspend fun resolveFormId(context: Context, id: String): String? =
        if (!com.designprototype.workshop.data.isTempId(id)) id
        else com.designprototype.workshop.data.TempIdMap.all(context.applicationContext)[id]

    @Composable
    fun AnswerField(label: String?, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
        com.designprototype.workshop.ui.RecordProseField(
            label = label, value = value, onValueChange = onValueChange, modifier = modifier, minLines = 2, rich = true, keepFormatting = true
        )
    }

    data class AnswerParts(val text: String, val richText: String?)

    fun answerParts(stored: String): AnswerParts =
        if (com.designprototype.workshop.ui.looksLikeRichDocument(stored)) {
            AnswerParts(com.designprototype.workshop.report.toPlain(com.designprototype.workshop.ui.recordDocFromStored(stored)), stored)
        } else AnswerParts(stored, null)

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
