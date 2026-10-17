package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * This account's e-mail choices — `GET`/`PUT /preferences/notifications` (2026-10-18), the web's
 * Settings card on the handset.
 *
 * OPT-OUT, and every field defaults to the server's default, so an older server that answers only
 * `available` and `emailReviewNotes` reads as "everything on, one e-mail per hand-in". A save sends
 * only the field that changed (`explicitNulls = false` drops the rest), so a choice this build does not
 * know is never overwritten.
 */
interface NotificationPreferencesApi {
    @GET("preferences/notifications")
    suspend fun read(): NotificationPreferencesDto

    @PUT("preferences/notifications")
    suspend fun save(@Body body: NotificationPreferencesUpdate): NotificationPreferencesDto
}

@Serializable
data class NotificationPreferencesDto(
    val available: Boolean = false,
    val emailReviewNotes: Boolean = true,
    val emailWorkshopUpdates: Boolean = true,
    val emailAccessNotices: Boolean = true,
    val emailHandInNotices: String = HandInMail.EACH,
    val reviewsHandIns: Boolean = false,
)

@Serializable
data class NotificationPreferencesUpdate(
    val emailReviewNotes: Boolean? = null,
    val emailWorkshopUpdates: Boolean? = null,
    val emailAccessNotices: Boolean? = null,
    val emailHandInNotices: String? = null,
)

/** How an inspector or the approving authority hears of a hand-in. Words held to the web's. */
object HandInMail {
    const val EACH = "EACH"
    const val DAILY = "DAILY"
    const val OFF = "OFF"

    val OPTIONS: List<Pair<String, String>> = listOf(EACH to "Each time", DAILY to "Once a day", OFF to "Never")

    /** Anything the server did not send as one of the three reads as [EACH]. */
    fun normalised(value: String?): String = value?.trim()?.uppercase()?.takeIf { it in setOf(EACH, DAILY, OFF) } ?: EACH
}

/** The three switches, in the web's order and with its words (`EMAIL_SWITCHES` in EmailNotificationsCard.tsx). */
enum class EmailSwitch(val label: String, val description: String) {
    REVIEW_NOTES("Corrections on my workshops", "When an inspector suggests a correction or sends a workshop back."),
    WORKSHOP_UPDATES("Workshop updates", "When a report is approved or handed on, or you're given a workshop."),
    ACCESS_NOTICES("Requests and comments", "Access requests, decisions on yours, and comments on your records.");

    fun read(dto: NotificationPreferencesDto): Boolean = when (this) {
        REVIEW_NOTES -> dto.emailReviewNotes
        WORKSHOP_UPDATES -> dto.emailWorkshopUpdates
        ACCESS_NOTICES -> dto.emailAccessNotices
    }

    fun update(next: Boolean): NotificationPreferencesUpdate = when (this) {
        REVIEW_NOTES -> NotificationPreferencesUpdate(emailReviewNotes = next)
        WORKSHOP_UPDATES -> NotificationPreferencesUpdate(emailWorkshopUpdates = next)
        ACCESS_NOTICES -> NotificationPreferencesUpdate(emailAccessNotices = next)
    }

    fun applied(dto: NotificationPreferencesDto, next: Boolean): NotificationPreferencesDto = when (this) {
        REVIEW_NOTES -> dto.copy(emailReviewNotes = next)
        WORKSHOP_UPDATES -> dto.copy(emailWorkshopUpdates = next)
        ACCESS_NOTICES -> dto.copy(emailAccessNotices = next)
    }
}
