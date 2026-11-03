package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

private val DW_DICTATION_IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

fun dwDictationIstDay(nowMillis: Long = System.currentTimeMillis()): String =
    Instant.ofEpochMilli(nowMillis).atOffset(DW_DICTATION_IST).toLocalDate().toString()

@Serializable
data class DwDictationAllowance(
    val userId: String = "",
    val day: String = "",
    val limit: Int? = null,
    val remaining: Int? = null,
)

data class DwDictationCapView(
    val spent: Boolean,
    val limit: Int?,
)

private val DW_DICTATION_CAP_UNKNOWN = DwDictationCapView(spent = false, limit = null)

fun dwDictationCapView(
    stored: DwDictationAllowance?,
    userId: String?,
    today: String,
): DwDictationCapView {
    if (stored == null || userId.isNullOrBlank()) return DW_DICTATION_CAP_UNKNOWN
    if (stored.userId != userId || stored.day.isBlank() || stored.day != today) {
        return DW_DICTATION_CAP_UNKNOWN
    }
    val remaining = stored.remaining ?: return DwDictationCapView(spent = false, limit = stored.limit)
    return DwDictationCapView(spent = remaining <= 0, limit = stored.limit)
}

fun dwDictationAllowanceOf(dto: DwDictateDto, userId: String?): DwDictationAllowance? =
    dwDictationAllowanceOf(dto.dictationDay, dto.dictationsLimit, dto.dictationsRemaining, userId)

fun dwDictationAllowanceOf(dto: DwDictationAllowanceDto, userId: String?): DwDictationAllowance? =
    dwDictationAllowanceOf(dto.dictationDay, dto.dictationsLimit, dto.dictationsRemaining, userId)

private fun dwDictationAllowanceOf(
    dictationDay: String?,
    limit: Int?,
    remaining: Int?,
    userId: String?,
): DwDictationAllowance? {
    val day = dictationDay?.trim().orEmpty()
    if (day.isEmpty() || userId.isNullOrBlank()) return null
    return DwDictationAllowance(userId = userId, day = day, limit = limit, remaining = remaining)
}

fun dwDictationCapSpentRecord(
    previous: DwDictationAllowance?,
    userId: String,
    today: String,
): DwDictationAllowance = DwDictationAllowance(
    userId = userId,
    day = today,
    limit = previous?.takeIf { it.userId == userId && it.day == today }?.limit,
    remaining = 0,
)

object DwDictationAllowanceStore {

    private const val PREFS = "dw_dictation_allowance"
    private const val KEY_PREFIX = "allowance:"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context, userId: String?): DwDictationAllowance? {
        if (userId.isNullOrBlank()) return null
        val raw = preferences(context).getString(key(userId), null) ?: return null
        val row = runCatching {
            json.decodeFromString(DwDictationAllowance.serializer(), raw)
        }.getOrNull()
        if (row == null || row.userId != userId) {
            clear(context, userId)
            return null
        }
        return row
    }

    fun write(context: Context, allowance: DwDictationAllowance) {
        if (allowance.userId.isBlank()) return
        preferences(context).edit()
            .putString(
                key(allowance.userId),
                json.encodeToString(DwDictationAllowance.serializer(), allowance)
            )
            .apply()
    }

    fun clear(context: Context, userId: String?) {
        if (userId.isNullOrBlank()) return
        preferences(context).edit().remove(key(userId)).apply()
    }

    private fun key(userId: String) = "$KEY_PREFIX$userId"
}
