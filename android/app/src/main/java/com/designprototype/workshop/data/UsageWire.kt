package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val USAGE_CONSENT_NOT_RECORDED: String = "NOT_RECORDED"

const val USAGE_CONSENT_GRANTED: String = "GRANTED"

const val USAGE_CONSENT_REFUSED: String = "REFUSED"

const val USAGE_BASIS_REQUIRED_AT_SIGN_IN: String = "REQUIRED_AT_SIGN_IN"

const val USAGE_BASIS_OFFERED_IN_SETTINGS: String = "OFFERED_IN_SETTINGS"

@Serializable
data class UsageNoticeDto(
    val version: String = "",
    val title: String = "",
    val required: Boolean = true,
    val requiredSentence: String = "",
    val collects: List<String> = emptyList(),
    val doesNotCollect: List<String> = emptyList(),
    val durationCaveat: String = "",
    val readableBy: Map<String, String> = emptyMap(),
    val withdrawal: UsageWithdrawalNoticeDto = UsageWithdrawalNoticeDto(),
    val retention: String = "",
    val document: String = "",
) {
    val isUsable: Boolean get() = version.isNotBlank() && collects.isNotEmpty()
}

@Serializable
data class UsageWithdrawalNoticeDto(
    val where: String = "",
    val costsNothing: String = "",
    val does: List<String> = emptyList(),
    val doesNot: List<String> = emptyList(),
)

@Serializable
data class UsageConsentRecordDto(
    val state: String = USAGE_CONSENT_NOT_RECORDED,
    val at: String? = null,
    val basis: String? = null,
    val version: String? = null,
)

@Serializable
data class UsageConsentGateDto(
    val state: String = USAGE_CONSENT_NOT_RECORDED,
    val required: Boolean = false,
    val reason: String = "",
    val noticeVersion: String = "",
    val agreedVersion: String? = null,
    val agreedAt: String? = null,
    val basis: String? = null,
    val answerAt: String = "",
    val noticeAt: String = "",
)

@Serializable
data class UsageConsentDecisionDto(
    val id: String? = null,
    val decision: String = "",
    val basis: String? = null,
    val noticeVersion: String? = null,
    val note: String? = null,
    val recordedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class UsageWithdrawalOutcomeDto(
    val bufferedDropped: Int = 0,
    val storedDeleted: Int = 0,
    val storedDeleteRan: Boolean = false,
    val explanation: String = "",
)

@Serializable
data class UsageConsentStateDto(
    val userId: String = "",
    val consent: UsageConsentRecordDto = UsageConsentRecordDto(),
    val gate: UsageConsentGateDto = UsageConsentGateDto(),
    val notice: UsageNoticeDto? = null,
    val decisions: List<UsageConsentDecisionDto> = emptyList(),
    val withdrawal: UsageWithdrawalOutcomeDto? = null,
)

@Serializable
data class UsageConsentBody(
    val decision: String,
    val basis: String,
    val noticeVersion: String,
    val recordedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class UsageWithdrawBody(
    val noticeVersion: String,
    val recordedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class UsageWindowDto(
    val from: String = "",
    val to: String = "",
    val days: Int = 0,
    val maxDays: Int = 0,
    val interval: String = "",
    val naiveDatesReadAs: String = "",
)

@Serializable
data class UsageLimitsDto(
    val maxWindowDays: Int = 0,
    val maxRoutesPerRequest: Int = 0,
    val minimumIdentifiedUsers: Int = 0,
    val rowsPerWrite: Int = 0,
    val flushIntervalSeconds: Int = 0,
    val bufferCeiling: Int = 0,
    val maxTimelineBuckets: Int = 0,
    val maxTrailRows: Int = 0,
)

@Serializable
data class UsageRouteRow(
    val routeTemplate: String = "",
    val requests: Int? = null,
    val identifiedUsers: Int? = null,
    val withheld: Boolean = false,
    val withheldBecause: String? = null,
    val ok: Int? = null,
    val clientErrors: Int? = null,
    val serverErrors: Int? = null,
    val avgDurationMs: Int? = null,
    val maxDurationMs: Int? = null,
)

@Serializable
data class UsagePageTotalsDto(
    val routes: Int = 0,
    val routesWithheld: Int = 0,
    val requests: Int = 0,
    val ok: Int = 0,
    val clientErrors: Int = 0,
    val serverErrors: Int = 0,
)

@Serializable
data class UsageRoutesPageDto(
    val items: List<UsageRouteRow> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
    val pages: Int = 0,
    val window: UsageWindowDto = UsageWindowDto(),
    val routeSource: String = "",
    val limits: UsageLimitsDto = UsageLimitsDto(),
    val totalsForThisPage: UsagePageTotalsDto = UsagePageTotalsDto(),
    val notMeasured: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class UsageCollectionConsentDto(
    val unaskedPolicy: String = "",
    val options: List<String> = emptyList(),
    val flowExists: Boolean = false,
    val noticeVersion: String? = null,
    val bases: List<String> = emptyList(),
    val askedAt: String? = null,
    val withdrawalCosts: String? = null,
    val explanation: String = "",
    val consentStateWritten: String = "",
    val refusalCost: String = "",
    val document: String = "",
)

@Serializable
data class UsageCollectionDto(
    val collects: List<String> = emptyList(),
    val doesNotCollect: List<String> = emptyList(),
    val notMeasured: List<String> = emptyList(),
    val consent: UsageCollectionConsentDto = UsageCollectionConsentDto(),
    val readableBy: Map<String, String> = emptyMap(),
    val limits: UsageLimitsDto = UsageLimitsDto(),
    val knownLimitations: List<String> = emptyList(),
    val retention: String = "",
    val document: String = "",
)

@Serializable
data class UsageMyRouteRow(
    val routeTemplate: String = "",
    val requests: Int = 0,
    val ok: Int = 0,
    val clientErrors: Int = 0,
    val serverErrors: Int = 0,
    val avgDurationMs: Int? = null,
    val maxDurationMs: Int? = null,
)

@Serializable
data class UsageMineDto(
    val userId: String = "",
    val window: UsageWindowDto = UsageWindowDto(),
    val requests: Int = 0,
    val routes: List<UsageMyRouteRow> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class UsageEventDto(
    val id: String = "",
    val routeTemplate: String = "",
    val method: String = "",
    val statusCode: Int = 0,
    val durationMs: Int = 0,
    val clientApp: String = "",
    val consentState: String? = null,
    val at: String = "",
)

@Serializable
data class UsageTrailDto(
    val userId: String = "",
    val window: UsageWindowDto = UsageWindowDto(),
    val limit: Int = 0,
    val offset: Int = 0,
    val maxRows: Int = 0,
    val events: List<UsageEventDto> = emptyList(),
    val consent: UsageConsentRecordDto = UsageConsentRecordDto(),
    val gate: UsageConsentGateDto = UsageConsentGateDto(),
    val notes: List<String> = emptyList(),
)

class UsageNoticeStore(context: Context) {
    private val store = context.applicationContext
        .getSharedPreferences("usage_consent_notice", Context.MODE_PRIVATE)

    fun read(): UsageNoticeDto? {
        val raw = store.getString(KEY_NOTICE, null) ?: return null
        val decoded = runCatching {
            ApiClient.json.decodeFromString(UsageNoticeDto.serializer(), raw)
        }.getOrNull()
        return decoded?.takeIf { it.isUsable }
    }

    fun write(notice: UsageNoticeDto) {
        if (!notice.isUsable) return
        store.edit()
            .putString(KEY_NOTICE, ApiClient.json.encodeToString(UsageNoticeDto.serializer(), notice))
            .apply()
    }

    private companion object {
        const val KEY_NOTICE = "notice"
    }
}

fun usageWithheld(row: UsageRouteRow): Boolean = row.withheld

fun usageMetricText(value: Int?): String = value?.let { usageCount(it) } ?: "—"

fun usageDurationText(value: Int?): String = value?.let { "$it ms" } ?: "—"

fun usageCount(value: Int): String {
    val negative = value < 0
    val digits = kotlin.math.abs(value.toLong()).toString()
    if (digits.length <= 3) return if (negative) "-$digits" else digits
    val head = digits.dropLast(3)
    val tail = digits.takeLast(3)
    val grouped = StringBuilder()
    var index = head.length
    while (index > 2) {
        grouped.insert(0, "," + head.substring(index - 2, index))
        index -= 2
    }
    if (index > 0) grouped.insert(0, head.substring(0, index))
    return (if (negative) "-" else "") + grouped.toString() + "," + tail
}

fun usageMoment(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val moment = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: return null
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").format(moment.atZone(ZoneId.systemDefault()))
    }.getOrNull()
}

fun usageDay(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val moment = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: return null
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy").format(moment.atZone(ZoneId.systemDefault()))
    }.getOrNull()
}

fun usageNowStamp(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    .format(Instant.now().atOffset(java.time.ZoneOffset.UTC))

fun usageWindowStart(days: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    .format(Instant.now().minusSeconds(days * 86_400L).atOffset(java.time.ZoneOffset.UTC))
