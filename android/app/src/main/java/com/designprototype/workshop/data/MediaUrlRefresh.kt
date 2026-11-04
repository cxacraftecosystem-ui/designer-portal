package com.designprototype.workshop.data

import java.util.Collections
import java.util.concurrent.TimeUnit

object MediaUrlRefresh {

    enum class Verdict {
        REFRESH,

        OFFLINE,

        NO_RECORD,

        ALREADY_TRIED,

        GONE,
    }

    private const val SIGNATURE_PARAM = "X-Amz-Signature"
    private const val DATE_PARAM = "X-Amz-Date"
    private const val EXPIRES_PARAM = "X-Amz-Expires"

    private val EXPIRY_SLACK_MS = TimeUnit.SECONDS.toMillis(10)

    private val AMZ_DATE = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$""")

    private val MONTH_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    private val attempts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    fun isPresigned(url: String?): Boolean =
        url != null && url.contains("$SIGNATURE_PARAM=") && url.contains("$EXPIRES_PARAM=")

    fun expiresAtMillis(url: String?): Long? {
        if (url.isNullOrBlank()) return null
        val query = url.substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
        var stamp: String? = null
        var lifetime: Long? = null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            when (key) {
                DATE_PARAM -> stamp = value
                EXPIRES_PARAM -> lifetime = value.toLongOrNull()
            }
        }
        val signedAt = epochMillisOf(stamp ?: return null) ?: return null
        val seconds = lifetime ?: return null
        if (seconds <= 0L) return null
        return signedAt + seconds * 1000L
    }

    fun isExpired(url: String?, nowMillis: Long): Boolean {
        val expiresAt = expiresAtMillis(url) ?: return false
        return nowMillis >= expiresAt - EXPIRY_SLACK_MS
    }

    fun attemptKey(mediaId: String, url: String?): String = mediaId + "\n" + (url ?: "")

    fun hasAttempted(mediaId: String, url: String?): Boolean =
        attempts.contains(attemptKey(mediaId, url))

    fun resetLedger() {
        attempts.clear()
    }

    fun verdictFor(
        mediaId: String?,
        url: String?,
        httpStatus: Int?,
        online: Boolean,
    ): Verdict {
        if (mediaId.isNullOrBlank()) return Verdict.NO_RECORD
        if (!online) return Verdict.OFFLINE
        if (hasAttempted(mediaId, url)) return Verdict.ALREADY_TRIED
        if (httpStatus == null) return Verdict.REFRESH
        return if (httpStatus == 403) Verdict.REFRESH else Verdict.GONE
    }

    suspend fun refreshOnce(
        mediaId: String?,
        failedUrl: String?,
        httpStatus: Int? = null,
        online: Boolean,
        reload: suspend (String) -> String?,
    ): String? {
        if (verdictFor(mediaId, failedUrl, httpStatus, online) != Verdict.REFRESH) return null
        val id = mediaId ?: return null
        attempts.add(attemptKey(id, failedUrl))
        val fresh = try {
            reload(id)
        } catch (_: Exception) {
            null
        }
        return if (!fresh.isNullOrBlank() && fresh != failedUrl) fresh else null
    }

    private fun epochMillisOf(stamp: String): Long? {
        val match = AMZ_DATE.matchEntire(stamp) ?: return null
        val (y, mo, d, h, mi, s) = match.destructured
        val year = y.toInt()
        val month = mo.toInt()
        val day = d.toInt()
        if (year < 1970) return null
        if (month !in 1..12) return null
        if (day !in 1..daysIn(year, month)) return null
        val hour = h.toInt()
        val minute = mi.toInt()
        val second = s.toInt()
        if (hour > 23 || minute > 59 || second > 60) return null
        var days = 0L
        for (yy in 1970 until year) days += if (isLeap(yy)) 366 else 365
        for (mm in 1 until month) days += daysIn(year, mm)
        days += (day - 1)
        return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L
    }

    private fun isLeap(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun daysIn(year: Int, month: Int): Int =
        if (month == 2 && isLeap(year)) 29 else MONTH_DAYS[month - 1]
}
