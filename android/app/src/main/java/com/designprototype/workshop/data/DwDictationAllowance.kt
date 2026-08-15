package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

/**
 * THIS DESIGNER'S DAILY SERVER-DICTATION ALLOWANCE, AS THIS PHONE LAST HEARD IT.
 *
 * ── WHY A COPY ON THE HANDSET AT ALL, WHEN THE SERVER IS THE ONE THAT COUNTS ───────────────────
 *
 * The count that decides anything is a row in the server's `DwDictationDailyUsage`, keyed by
 * (designer, India-time day). A client-side counter is a client-side counter: process memory clears on
 * a swipe-away, and a spend ceiling defeated by a swipe-away is the one direction that costs money
 * rather than a retry. NOTHING HERE ENFORCES ANYTHING.
 *
 * What this copy buys is the refusal happening BEFORE the microphone opens. Without it, a designer
 * whose day is spent records a passage into every one of the several hundred prose fields on a stage,
 * uploads six megabytes for each, and is told the same thing each time — which is precisely the
 * failure [DwDictationRun] already names for the 503: "a six-megabyte upload per field, each one
 * spending mobile data to be told the same thing." In a district town the connection is scarcer than
 * the provider credit the cap is about, so spending it to learn a number we were told this morning is
 * the more expensive mistake of the two.
 *
 * The server therefore puts the allowance on the 200 of every dictation as well as in the refusal, and
 * [DwDictationRun.learnAllowance] writes it down here as it goes by.
 *
 * ── WHY SHAREDPREFERENCES AND NOT A `@Volatile Boolean` IN [DwDictationRun] ────────────────────
 *
 * [DwDictationRun]'s two existing facts are process-scoped for a stated reason — "a pack downloaded
 * overnight, or a server that had its keys configured this morning, must not be permanently written
 * off by a phone that remembers last week" — and NEITHER OF THEM HAS A CLOCK. An allowance does. It
 * expires on a schedule, so:
 *
 *  * a process alive across midnight, or an app opened at 23:00 and used at 00:05, would go on
 *    refusing uploads the server would now accept — the capability withdrawn for a day for nobody's
 *    benefit, with no message anywhere naming the cause;
 *  * and in the other direction, process memory clears on a swipe-away, so a refusal learned at the
 *    cost of a six-megabyte upload would be forgotten by the next cold start and bought again.
 *
 * IT IS ALSO PER USER, WHICH A PROCESS-SCOPED FLAG CANNOT BE. A field phone is handed between
 * designers ([CarryContextStore] keys every row by user id for exactly this reason: "a field phone gets
 * handed between researchers"), and the cap is per DESIGNER. One designer's spent afternoon must not
 * refuse the colleague who signs in after them, and one designer's ceiling must not be printed to
 * another.
 *
 * So: SharedPreferences, alongside [TokenStore] and [CarryContextStore], for their reason — synchronous,
 * which is what `conditionsNow()` requires (it is called on the tap, on composition's main thread, and
 * may do no IO worth measuring), and entirely local, so it works with no connection.
 *
 * ── THE DAY BOUNDARY, AND WHY THIS FAILS OPEN WHERE CONSENT FAILS CLOSED ───────────────────────
 *
 * "Daily" means nothing without a boundary, and the boundary is the SERVER's India-time day —
 * [dwDictationIstDay], the same fixed +05:30 the server's own `dictation_cap.ist_day` uses, for the
 * same stated reason (India has observed one offset with no daylight saving since 1945). The phone's
 * own timezone is deliberately not consulted: two handsets with different clocks would otherwise hold
 * two different allowances against one server row.
 *
 * A STORED RECORD WHOSE DAY IS NOT TODAY RESOLVES TO "NOT SPENT". That is the opposite direction from
 * [DwDictationConditions.tier3Consent], and both directions are deliberate:
 *
 *  * an unknown CONSENT costs a named artisan's recorded voice leaving the device for a third party, so
 *    it fails closed;
 *  * a stale ALLOWANCE costs one upload per designer per day boundary — the phone tries once and learns
 *    the truth from the server — while a phone that failed closed at the wrong midnight would silently
 *    withhold a capability that has already been paid for.
 *
 * Anybody making these two "consistent" has to argue with this paragraph.
 *
 * WHAT A WRONG DEVICE CLOCK COSTS, SAID PLAINLY: the day a 200 carried is the SERVER's, and it is
 * compared against this phone's own reckoning of today. A handset whose date is a day out therefore
 * never matches, so the mirror never applies and every field pays its upload to be refused — the
 * behaviour this app had before the mirror existed, no worse. It is not "corrected" here, because a
 * phone cannot tell a wrong clock from a crossed boundary, and guessing would be a fabricated day.
 */

/**
 * India Standard Time as a fixed offset — the server's `_IST`, copied with its argument.
 *
 * `ZoneOffset` and not `ZoneId("Asia/Kolkata")`: the offset has been +05:30 with no daylight saving
 * since 1945, and a zone id would make this depend on the tz database version on a handset that may
 * never have been updated. What is at stake is only WHICH DAY an allowance belongs to, and the fixed
 * offset answers that identically for every date this application will ever see.
 */
private val DW_DICTATION_IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

/**
 * The India-time calendar date as `"YYYY-MM-DD"` — the second half of the server's own usage key.
 *
 * THE ONE PLACE THE HANDSET DECIDES WHICH DAY IT IS. A second reckoning of the boundary, even a
 * correct one, is a second definition, and the first day the two disagreed a designer would hold two
 * allowances or none. `nowMillis` is a parameter so the boundary itself is testable without waiting
 * for midnight.
 */
fun dwDictationIstDay(nowMillis: Long = System.currentTimeMillis()): String =
    Instant.ofEpochMilli(nowMillis).atOffset(DW_DICTATION_IST).toLocalDate().toString()

/**
 * One designer's allowance for one India-time day, exactly as the server last reported it.
 *
 * EVERY FIELD DEFAULTED, for [DraftMedia]'s stated reason applied to a smaller document: this row is
 * read back by whatever build is on the handset when it is next opened, and a field added without a
 * default would make kotlinx throw on decode — which here would mean a stored refusal silently
 * becoming "not spent" on the update that added the field.
 */
@Serializable
data class DwDictationAllowance(
    /**
     * Whose allowance this is. Repeated inside the payload as well as being the storage key, exactly as
     * [StoredCarryContext.userId] is, so a record can never be read back under the wrong account.
     */
    val userId: String = "",
    /** The day the SERVER named, or — for a refusal learned from a 429 — this phone's reckoning of it. */
    val day: String = "",
    /**
     * The configured ceiling, or null for "this phone has not been told one".
     *
     * NULL IS NOT ZERO AND NOT "UNCAPPED", and the distinction has to survive the round trip: the
     * server sends null when the deployment is uncapped and 0 when server dictation is switched off
     * entirely, and a phone that has never had an answer has neither fact. All three read differently
     * to a designer, so all three are kept apart — see [dwDictationCapView].
     */
    val limit: Int? = null,
    /** How many are left, or null when there is no ceiling to count down from. 0 means refuse. */
    val remaining: Int? = null,
)

/**
 * What the ladder is told about the cap: whether it is spent, and the number to name if it is.
 *
 * TWO ANSWERS OUT OF ONE STALENESS RULE, on purpose. Asking "is it spent" and "what is the limit" as
 * two functions would be two readings of the same freshness test, and the day they drifted by one
 * clause the phone would print yesterday's ceiling beside today's refusal.
 */
data class DwDictationCapView(
    val spent: Boolean,
    val limit: Int?,
)

/** Nothing known: not spent, and no number to name. The honest answer, and the safe one. */
private val DW_DICTATION_CAP_UNKNOWN = DwDictationCapView(spent = false, limit = null)

/**
 * Resolve a stored allowance against who is signed in and what day it is here.
 *
 * Pure, so the four ways this can go wrong are assertable on a desktop JVM with no handset:
 *
 *  1. NOTHING STORED — no dictation has succeeded or been refused on this phone yet. Not spent.
 *  2. NOBODY SIGNED IN — read as unknown rather than as the last account's allowance.
 *  3. A DIFFERENT DESIGNER'S ROW. The cap is per designer; a phone handed over at lunch must not
 *     refuse the afternoon's designer, and must not print the morning's ceiling to them either.
 *  4. A DIFFERENT DAY. Stale, so not spent — see the file docstring on why this direction is open.
 *
 * With all four passed, "spent" is `remaining <= 0`: a server that answered `remaining: 0` is saying
 * the last dictation used the last of the allowance, so the NEXT one is the one to refuse. A null
 * `remaining` is an uncapped deployment and can never be spent.
 */
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

/**
 * The allowance one dictation's answer reported, or null when it reported none.
 *
 * A SERVER THAT SENT NO DAY IS NOT AN ALLOWANCE, and this is where that is enforced rather than at
 * three call sites. `dictationDay` is the key the mirror's whole freshness rule turns on, so a payload
 * without one — an older deployment that predates the cap, or a build of the DTO reading a response
 * that never carried it — must leave whatever is stored alone rather than overwrite it with a record
 * that can never be matched against a day.
 */
fun dwDictationAllowanceOf(dto: DwDictateDto, userId: String?): DwDictationAllowance? {
    val day = dto.dictationDay?.trim().orEmpty()
    if (day.isEmpty() || userId.isNullOrBlank()) return null
    return DwDictationAllowance(
        userId = userId,
        day = day,
        limit = dto.dictationsLimit,
        remaining = dto.dictationsRemaining,
    )
}

/**
 * The refusal a 429 leaves behind, keeping a ceiling this phone was already told TODAY.
 *
 * `remaining = 0` is the whole refusal; the limit is carried over from [previous] only when that record
 * is this designer's and about this same day, because a ceiling learned yesterday is not a fact about
 * today's. Where there is none, the limit stays null and the sentences take their limit-unknown form —
 * which is [dwDownloadCostSentence]'s rule about a figure the phone was never told, applied to a
 * number instead of a file size.
 */
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

/**
 * Where the mirror lives between two taps, and between two runs of the app.
 *
 * SYNCHRONOUS BY DESIGN. `conditionsNow()` is called on the tap that opens the microphone, on
 * composition's main thread, and a suspending read there would either block it or arrive a frame after
 * the decision that needed it. SharedPreferences is the one store in this application that answers in
 * place, which is why [TokenStore] and [CarryContextStore] both use it.
 *
 * ONE ROW PER DESIGNER AND NOT ONE PER DAY. Yesterday's row is of no use to anybody — the server holds
 * the history, and this copy exists only to answer "may I skip the upload right now" — so a new day
 * simply overwrites it. There is nothing here to sweep and no expiry to schedule.
 */
object DwDictationAllowanceStore {

    private const val PREFS = "dw_dictation_allowance"
    private const val KEY_PREFIX = "allowance:"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * This designer's stored allowance, or null.
     *
     * A ROW THAT WILL NOT PARSE IS DELETED, and that is the right recovery here in a way it never is
     * for a draft: this is a COPY of something the server still holds, so throwing it away costs one
     * upload to learn again — the same reasoning `DwReferenceStore` states for deleting rather than
     * quarantining. A draft would never be treated this way.
     */
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
