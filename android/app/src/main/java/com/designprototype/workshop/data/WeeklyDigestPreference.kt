package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

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

object WeeklyDigestSwitch {
    const val LABEL = "Weekly summary"
    const val DESCRIPTION = "Mondays at 08:00: what waits for you, what is overdue, what came in."

    fun visible(state: WeeklyDigestPreferenceDto?): Boolean = state?.eligible == true

    fun description(state: WeeklyDigestPreferenceDto): String =
        "$DESCRIPTION ${if (state.emailAvailable) "By e-mail and here." else "Here, in your notifications."}"

    fun isDigestLink(link: String?): Boolean = link?.startsWith("/digest/") == true
}
