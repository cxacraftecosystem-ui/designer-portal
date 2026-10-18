package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/**
 * The Monday summary's switch — the handset's half of the routes both portals share:
 *
 *   GET  notifications/digest               -> [WeeklyDigestPreferenceDto]
 *   PUT  notifications/digest  { enabled }  -> the same (403 for an account it is not for)
 *
 * The summary itself arrives as an ordinary notice in the activity list (title and one-line body);
 * there is no digest screen on the handset, and its `/digest/<id>` link is deliberately not opened.
 * The switch is drawn only when [eligible], and works whether or not this deployment sends mail.
 */
@Serializable
data class WeeklyDigestPreferenceDto(
    val eligible: Boolean = false,
    val enabled: Boolean = false,
    val emailAvailable: Boolean = false,
    val schedule: String = "Mondays, 08:00 IST",
    val latest: WeeklyDigestLatestDto? = null,
)

@Serializable
data class WeeklyDigestLatestDto(
    val id: String = "",
    val weekStart: String = "",
    val waiting: Int = 0,
    val overdue: Int = 0,
    val newThisWeek: Int = 0,
    val coverage: WeeklyDigestCoverageDto? = null,
)

@Serializable
data class WeeklyDigestCoverageDto(
    val label: String = "",
    val value: Int = 0,
    val of: Int? = null,
    val percent: Int? = null,
)

@Serializable
data class WeeklyDigestPreferenceBody(val enabled: Boolean)

/** The pure decisions behind the Settings row, apart from Compose so they can be unit-tested. */
object WeeklyDigestSwitch {
    const val LABEL = "Weekly summary"
    const val DESCRIPTION = "Mondays at 08:00: what waits for you, what is overdue, what came in."

    /** Whether the row is drawn at all. Null (not read yet, or the read failed) draws nothing. */
    fun visible(state: WeeklyDigestPreferenceDto?): Boolean = state?.eligible == true

    /** The line under the switch: the description and where the summary arrives. */
    fun description(state: WeeklyDigestPreferenceDto): String =
        "$DESCRIPTION ${if (state.emailAvailable) "By e-mail and here." else "Here, in your notifications."}"

    /** A link the activity list must leave alone: the digest is a web page, with no handset screen. */
    fun isDigestLink(link: String?): Boolean = link?.startsWith("/digest/") == true
}
