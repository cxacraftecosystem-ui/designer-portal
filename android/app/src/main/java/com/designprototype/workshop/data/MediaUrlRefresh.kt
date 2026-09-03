package com.designprototype.workshop.data

import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * ONE EXPIRED MEDIA URL, REFRESHED ONCE — AND, ABOVE ALL, NEVER TWICE.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS EXISTS FOR
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [MediaFileDto.url] has always been a permanent, unauthenticated CDN link — the host plus the
 * object key, world-readable by bucket policy, good for ever to anybody it is ever copied to.
 * Closing that means two server-side moves: the API stops emitting the permanent string and emits a
 * 15-minute signature instead (`MEDIA_PRESIGNED_READS`), and then a human removes the bucket's
 * public-read statement, at which point every URL that ever leaked starts answering 403.
 *
 * THIS HANDSET IS THE REASON THOSE TWO MOVES ARE SEQUENCED BEHIND A FLAG. 0.0.7 is in the field and
 * caches `url` in its offline store: a designer's phone holds workshop payloads for days and draws
 * photographs from the cached string with no network at all. Handing expiring URLs to that build
 * makes a workshop silently lose its images on a phone that may not see a network for a week, and
 * no server change can reach it. This file is the tolerance that lets 0.0.8 be the build the flag
 * waits for.
 *
 * ── THE TWO WAYS A URL GOES BAD, AND THE TWO ANSWERS ──────────────────────────────────────────
 *
 *  1. **KNOWN BAD BEFORE IT IS DRAWN.** A signed URL carries its own death certificate:
 *     `X-Amz-Date` and `X-Amz-Expires` are in the query string. A payload that has been sitting in
 *     the draft store since yesterday can be READ as expired without asking anybody —
 *     [isExpired] — so the refresh happens before Coil is ever pointed at it and the designer never
 *     sees a broken frame.
 *  2. **DISCOVERED BAD WHEN IT IS DRAWN.** Coil reports a load failure with a throwable, not a
 *     status. [verdictFor] therefore takes whatever the caller could learn and treats "I could not
 *     tell" as its own answer rather than as "probably fine".
 *
 * The answer to both is the same: re-GET the owning row (`GET /media/{id}`, entitlement-gated
 * exactly as the list is), take the `url` it hands back now, and try that ONCE.
 *
 * ── NEVER LOOP ────────────────────────────────────────────────────────────────────────────────
 *
 * Every attempt is recorded against `mediaId + "\n" + theUrlThatFailed`, and [verdictFor] answers
 * [Verdict.ALREADY_TRIED] for a repeat. A gallery of forty photographs whose signatures expired
 * together therefore costs at most forty verdicts and — because [refreshOnce] holds one in-flight
 * request per media id — far fewer requests than that. A URL that fails again after being refreshed
 * is left broken, which the designer can see and report, rather than retried for as long as the
 * screen is open.
 *
 * The ledger is per PROCESS and is deliberately not persisted: a relaunch genuinely retries, which
 * is right for a transient outage and wrong for a render loop.
 *
 * ── OFFLINE: THE RESIDUAL, STATED HONESTLY ────────────────────────────────────────────────────
 *
 * Offline this does nothing at all — [Verdict.OFFLINE] — and that is the whole design, not a gap.
 * There is no network to ask, and a queued refresh would repaint a screen the designer left hours
 * ago. What still works offline is what worked before: `WorkshopDraftStore` keeps the BYTES of a
 * capture until the server confirms it, and `DwMediaCapture` draws those from disk through a
 * `content://`/`file://` Uri that has no signature and cannot expire. So a designer's OWN
 * unconfirmed work is unaffected; what an offline expiry costs is remote media whose bytes this
 * phone never held. That is bounded by the TTL and is the price of the URLs expiring.
 *
 * ── WHY THE NETWORK IS A LAMBDA ───────────────────────────────────────────────────────────────
 *
 * [refreshOnce] takes `reload: suspend (String) -> String?` rather than a repository. Everything in
 * this object is then a JVM test away — no Retrofit, no Android framework, no device — which is the
 * only level at which "exactly once" can actually be asserted. `WorkshopRepository.refreshMediaUrl`
 * is the one caller that supplies the real `api.getMedia`.
 */
object MediaUrlRefresh {

    /** What to do about one failed media load. Every value is a decision somebody can argue with. */
    enum class Verdict {
        /** Re-request the row and try the URL it gives back. */
        REFRESH,

        /** No network. Nothing can be asked and no local bytes can be substituted — see the header. */
        OFFLINE,

        /** A local draft capture with no server id: there is no row to re-request. */
        NO_RECORD,

        /** This exact (media, url) pair has already had its one attempt. THE LOOP GUARD. */
        ALREADY_TRIED,

        /** The server said something a fresh link cannot fix — 404, 5xx. Leave it broken and honest. */
        GONE,
    }

    /** SigV4 query parameters, spelled once. S3 emits them in exactly this case. */
    private const val SIGNATURE_PARAM = "X-Amz-Signature"
    private const val DATE_PARAM = "X-Amz-Date"
    private const val EXPIRES_PARAM = "X-Amz-Expires"

    /**
     * Slack subtracted from a signature's own expiry before it is called dead.
     *
     * A URL with four seconds left is not worth drawing: the request has to be issued, cross a
     * village connection and be answered, and a signature that dies mid-transfer produces a
     * TRUNCATED image rather than a clean failure. Ten seconds is smaller than any TTL this server
     * will issue (900s today) and larger than a round trip, which is the whole requirement. The
     * web's `lib/mediaUrlRefresh.ts` uses the same number for the same reason; they are two
     * renderings of one rule and must not drift.
     */
    private val EXPIRY_SLACK_MS = TimeUnit.SECONDS.toMillis(10)

    /** `20260903T101112Z` — ISO 8601 BASIC, which no date parser on this platform reads by default. */
    private val AMZ_DATE = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$""")

    /** Days in each month, non-leap. Used only by [epochMillisOf]; see its note on why. */
    private val MONTH_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /**
     * Every (media, url) pair that has had its attempt.
     *
     * PROCESS-WIDE AND NOT PER SCREEN, because one photograph is drawn by several composables at
     * once — a carousel thumbnail, the same file in a viewer over it, a report preview behind both.
     * A per-screen ledger would give one expired URL three attempts, and one more with every
     * renderer somebody adds.
     */
    private val attempts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** Is this a signed URL at all? A permanent CDN link carries none of the SigV4 parameters. */
    fun isPresigned(url: String?): Boolean =
        url != null && url.contains("$SIGNATURE_PARAM=") && url.contains("$EXPIRES_PARAM=")

    /**
     * When this signed URL stops working, in epoch milliseconds, or null when it does not say.
     *
     * "This URL does not tell me when it dies" is a real answer and is a great deal better than a
     * fabricated timestamp: erring one way refreshes every image on the screen, erring the other
     * leaves every one of them broken.
     */
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

    /**
     * Is this URL already dead, by its own arithmetic?
     *
     * FALSE FOR A URL THAT IS NOT SIGNED, and that is what keeps this whole file inert while the
     * server flag is off: a permanent CDN link has no expiry, so nothing is pre-emptively refreshed
     * and the app behaves exactly as it did before 0.0.8.
     */
    fun isExpired(url: String?, nowMillis: Long): Boolean {
        val expiresAt = expiresAtMillis(url) ?: return false
        return nowMillis >= expiresAt - EXPIRY_SLACK_MS
    }

    /** The ledger key: one attempt per row PER URL, so a refreshed URL may fail on its own terms. */
    fun attemptKey(mediaId: String, url: String?): String = mediaId + "\n" + (url ?: "")

    /** Has this pair already had its attempt? */
    fun hasAttempted(mediaId: String, url: String?): Boolean =
        attempts.contains(attemptKey(mediaId, url))

    /** Forget every recorded attempt. FOR TESTS AND NOTHING ELSE — see the loop guard above. */
    fun resetLedger() {
        attempts.clear()
    }

    /**
     * Should this failure be answered with a refresh? Pure, and the whole policy of the file.
     *
     * ORDER IS THE ARGUMENT. [Verdict.OFFLINE] is decided before the ledger, so a refresh that could
     * never have been issued does not spend the one attempt this file allows — a designer who came
     * back into signal would otherwise be holding a permanently broken image. [Verdict.ALREADY_TRIED]
     * is decided before the status, so a caller that forgets to consult the ledger cannot get a
     * second attempt by asking again.
     *
     * @param httpStatus the status behind the failure where the caller could read one. NULL is the
     *   ORDINARY case — Coil reports a throwable, not a code — and it is treated as "refresh", not
     *   as "probably fine": an expired signature is invisible from a load failure, and treating the
     *   unreadable case as benign would leave every stale URL broken. One request is the cost of
     *   being wrong, and the ledger has already bounded it at one.
     *
     * A NON-403 STATUS IS DELIBERATELY NOT REFRESHED: 404 means the object is gone and a fresh link
     * points at the same absence; 5xx means storage is unwell and a second request makes it worse.
     */
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

    /**
     * The whole path in one call: decide, re-read the row at most once, and answer with the URL to
     * try next — or null for "leave what is on screen alone".
     *
     * NULL IS A REAL ANSWER WITH FOUR CAUSES, none of which a caller may treat as an error to
     * report: the verdict was not [Verdict.REFRESH]; the row is gone; the account is not entitled to
     * the bytes (the encoder withholds `url` — see [MediaFileDto.url], which has been nullable since
     * long before this file); or the server handed back the SAME string, which is the server saying
     * the URL is not the problem. A caller that gets null must NOT clear the model it is already
     * holding: a broken frame the designer can see beats a blank one they cannot ask about.
     *
     * The attempt is recorded BEFORE the reload is issued, so a refresh that itself throws still
     * costs exactly one.
     */
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
            // A failed re-read is a failed re-read. It must not propagate into a Compose frame or a
            // sync pass — the attempt is already banked, so this cannot become a retry loop either.
            null
        }
        return if (!fresh.isNullOrBlank() && fresh != failedUrl) fresh else null
    }

    /**
     * `20260903T101112Z` -> epoch millis, or null.
     *
     * ARITHMETIC RATHER THAN A DATE LIBRARY, and that is a `minSdk` decision rather than taste:
     * `java.time` needs desugaring, `SimpleDateFormat` is not thread-safe and this is read from
     * several composables at once, and `Calendar` would pull a timezone database in to answer a
     * question that is entirely in UTC. Days-since-epoch by the civil-date formula is exact, has no
     * allocation, and cannot be affected by the device's clock settings or locale — which matters,
     * because the value it produces decides whether a photograph is drawn.
     */
    private fun epochMillisOf(stamp: String): Long? {
        val match = AMZ_DATE.matchEntire(stamp) ?: return null
        val (y, mo, d, h, mi, s) = match.destructured
        val year = y.toInt()
        val month = mo.toInt()
        val day = d.toInt()
        // Below the epoch the year loop below runs zero times and would answer a POSITIVE instant
        // for a date in 1969 — a signature that reads as valid for the next fifty years. Refused
        // outright: nothing this server signs is dated before 1970, so the only way to get here is
        // a malformed URL, and "I cannot read this" is the honest answer to one.
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
