package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * THE `/usage` WIRE, AS THIS HANDSET READS IT — the consent a person is asked for at sign-in, their
 * own record of what was noticed about them, and the admin aggregate the web calls `/settings/usage`.
 *
 * ── THE ONE RULE THAT DECIDES EVERY TYPE BELOW ────────────────────────────────────────────────
 *
 * **A WITHHELD FIGURE IS `null` AND MUST STAY `null` ALL THE WAY TO THE SCREEN.** The server refuses
 * to state a number for a route fewer than `limits.minimumIdentifiedUsers` accounts used, and it
 * says so by sending every metric on that row as null with `withheld: true`. In Kotlin a null Int
 * reaches zero through `?: 0`, through `orEmpty()`-shaped habits and through every arithmetic
 * helper anybody will ever add here — and a zero drawn where the server refused to answer is this
 * client publishing a figure the server explicitly would not. So:
 *
 *  * every metric on [UsageRouteRow], [UsageTimelineBucket], [UsageLatencyRow] and [UsageClientRow]
 *    is `Int?` or `Double?`, never defaulted to 0;
 *  * [usageWithheld] is the ONE test a screen makes before touching a number, so nobody re-derives
 *    it by hand — the twin of `isWithheldRoute` in `frontend/lib/usage.ts`;
 *  * [usageMetricText] renders the withheld em dash, and it is the only renderer of a usage figure
 *    on this client.
 *
 * The web page carrying the same table records the same rule as its rule 1 ("Nothing here
 * computes"), and this file exists so the phone cannot quietly acquire a second opinion about it.
 *
 * ── AND THE SECOND RULE, WHICH IS ABOUT THE CONSENT AND NOT THE FIGURES ───────────────────────
 *
 * **NO COPY IS WRITTEN HERE.** Every sentence a person reads while deciding whether to agree comes
 * off [UsageNoticeDto], which the server computes from the collection policy actually in force
 * (`services/usage.collects`). Writing the notice a second time in Kotlin is how a handset comes to
 * describe one decision differently from the web, and for a consent that is not an inconsistency —
 * it is a consent to something else. The only strings this client owns are the ones about the
 * CLIENT's own state: what it is doing, what it could not reach, and what a person may do next.
 * Those live in `ui/UsageCopy.kt` and nowhere else.
 */

// ---------------------------------------------------------------------------------------------
// The three consent tokens, and the two circumstances
// ---------------------------------------------------------------------------------------------

/**
 * `UsageConsent.NOT_RECORDED` — nobody has asked this account.
 *
 * NOT THE SAME THING AS A REFUSAL, and the whole three-state vocabulary exists to keep them apart:
 * a boolean would have had to default to false for every account already in the database, making
 * every never-asked colleague indistinguishable from a refusal nobody ever made. The server's own
 * enum says so in as many words; this constant is the handset reading it back.
 */
const val USAGE_CONSENT_NOT_RECORDED: String = "NOT_RECORDED"

/** `UsageConsent.GRANTED` — the only state under which a request of this account's carries a name. */
const val USAGE_CONSENT_GRANTED: String = "GRANTED"

/**
 * `UsageConsent.REFUSED` — recording stopped, and what had been stored was deleted.
 *
 * It is also HOW A GRANT IS WITHDRAWN. There is deliberately no route that un-records an answer back
 * to [USAGE_CONSENT_NOT_RECORDED], because a gate cannot tell a withdrawal from an account nobody
 * has opened if the two are stored the same way.
 */
const val USAGE_CONSENT_REFUSED: String = "REFUSED"

/**
 * `UsageConsentBasis.REQUIRED_AT_SIGN_IN` — the answer was given at a turnstile.
 *
 * **THIS IS THE COLUMN THAT MAKES THE WHOLE FEATURE HONEST AND IT IS NOT DECORATION.** Agreeing is a
 * condition of using the platform, which under GDPR Art. 7(4) means the agreement is not freely
 * given; a system that stored "GRANTED" alone would be filing a turnstile as a free choice, forging
 * exactly the distinction the three-state enum was built to preserve. Every grant this client sends
 * from the sign-in screen carries this basis and no other.
 */
const val USAGE_BASIS_REQUIRED_AT_SIGN_IN: String = "REQUIRED_AT_SIGN_IN"

/**
 * `UsageConsentBasis.OFFERED_IN_SETTINGS` — the answer was given freely, from the settings screen.
 *
 * Sent for a re-grant made on [com.designprototype.workshop.ui.UsageRecordingScreen]. A WITHDRAWAL
 * never sends it: `POST /usage/consent/withdraw` supplies the basis itself, precisely so that no
 * client can file a withdrawal as though it had been demanded of somebody.
 */
const val USAGE_BASIS_OFFERED_IN_SETTINGS: String = "OFFERED_IN_SETTINGS"

// ---------------------------------------------------------------------------------------------
// The notice — the whole text a person is agreeing to, versioned
// ---------------------------------------------------------------------------------------------

/**
 * `GET /usage/consent/notice`. **The only ungated route in the whole module**, because a person
 * deciding whether to agree has not agreed yet and, on a sign-in screen, holds no token either.
 *
 * THE ORDER OF THE FIELDS IS THE ORDER IT MUST BE READ IN and the screens honour it: what is
 * collected, what is not, THEN that it is required. A person who reads two paragraphs of reassurance
 * and only then discovers the choice was not a choice has been handled rather than asked.
 *
 * [version] TRAVELS WITH IT so this client can send back what it actually showed — see
 * [UsageNoticeStore] for why a handset may be showing a cached one.
 */
@Serializable
data class UsageNoticeDto(
    val version: String = "",
    val title: String = "",
    /** Always true today. Read rather than assumed, so a deployment that ever offers a free choice
     *  is not described by this client as a turnstile. */
    val required: Boolean = true,
    val requiredSentence: String = "",
    val collects: List<String> = emptyList(),
    val doesNotCollect: List<String> = emptyList(),
    /** Server time only — the single most misread number in this feature. */
    val durationCaveat: String = "",
    /** Keyed by route: who may read what. Rendered verbatim; it is a promise, not documentation. */
    val readableBy: Map<String, String> = emptyMap(),
    val withdrawal: UsageWithdrawalNoticeDto = UsageWithdrawalNoticeDto(),
    val retention: String = "",
    val document: String = "",
) {
    /** A notice with no version is one that never arrived; nothing may be answered against it. */
    val isUsable: Boolean get() = version.isNotBlank() && collects.isNotEmpty()
}

/** The withdrawal half of the notice: where, what it costs, what it does and what it does not. */
@Serializable
data class UsageWithdrawalNoticeDto(
    val where: String = "",
    val costsNothing: String = "",
    val does: List<String> = emptyList(),
    val doesNot: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// The recorded answer, and the gate a client renders from it
// ---------------------------------------------------------------------------------------------

/**
 * This account's stored answer. Four fields rather than one, because "GRANTED" alone cannot answer
 * any of the three questions a reader of a consent record actually has: when, under what
 * circumstances, and to which text.
 */
@Serializable
data class UsageConsentRecordDto(
    val state: String = USAGE_CONSENT_NOT_RECORDED,
    val at: String? = null,
    val basis: String? = null,
    val version: String? = null,
)

/**
 * **THE FIELD BOTH CLIENTS RENDER FROM, AND NEITHER COMPUTES.**
 *
 * [required] folds two facts into one answer — have they agreed, and did they agree to the CURRENT
 * text — and the moment the web client and this one each fold it for themselves, the two disagree on
 * the first deploy that bumps the notice version while only one of them is updated. So the server
 * folds it once. **Nothing on this handset may derive [required] from [state] and [agreedVersion];**
 * a screen that did would be a third opinion nobody asked for.
 *
 * It is `true` for NOT_RECORDED and for a GRANTED answer against a stale notice, and `false` for a
 * current grant AND FOR A REFUSAL. That last one is load-bearing: a person who withdrew must not be
 * asked again, or the withdrawal is theatre.
 */
@Serializable
data class UsageConsentGateDto(
    val state: String = USAGE_CONSENT_NOT_RECORDED,
    val required: Boolean = false,
    /** The server's own sentence for this state. Three states, three different next moves, three
     *  different sentences — rendered verbatim, never summarised. */
    val reason: String = "",
    val noticeVersion: String = "",
    val agreedVersion: String? = null,
    val agreedAt: String? = null,
    val basis: String? = null,
    val answerAt: String = "",
    val noticeAt: String = "",
)

/**
 * One recorded answer from the log. **Two clocks, and both are carried.**
 *
 * [recordedAt] is what the client said — when the box was actually ticked — and is null when the
 * answer was given straight against the server. [createdAt] is when the server heard it. On this
 * fleet the two can differ by a fortnight, and a reader who can see only one of them cannot tell an
 * answer given today from one given before the device last synced. Collapsing them would date a
 * signature to the day it was filed.
 */
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

/** What a withdrawal actually reached. Returned rather than assumed, because `withdraw()` never
 *  raises and a failed delete would otherwise look exactly like a successful one. */
@Serializable
data class UsageWithdrawalOutcomeDto(
    val bufferedDropped: Int = 0,
    val storedDeleted: Int = 0,
    /**
     * FALSE MEANS COLLECTION HAS STOPPED AND THE DELETION HAS NOT HAPPENED. It must never be
     * rendered as "0 rows deleted", which reads as "there was nothing to delete" — the opposite
     * fact. [explanation] carries the server's own sentence for both cases.
     */
    val storedDeleteRan: Boolean = false,
    val explanation: String = "",
)

/** `GET /usage/consent`, and the body both writes answer with. */
@Serializable
data class UsageConsentStateDto(
    val userId: String = "",
    val consent: UsageConsentRecordDto = UsageConsentRecordDto(),
    val gate: UsageConsentGateDto = UsageConsentGateDto(),
    /** Present on the GET; absent on the two writes, which is why it is nullable. */
    val notice: UsageNoticeDto? = null,
    /** Newest first. */
    val decisions: List<UsageConsentDecisionDto> = emptyList(),
    val withdrawal: UsageWithdrawalOutcomeDto? = null,
)

/**
 * `POST /usage/consent`. `extra = "forbid"` on the server, so this body carries these five fields and
 * nothing else — an extra key is a 422 for the whole request.
 */
@Serializable
data class UsageConsentBody(
    val decision: String,
    val basis: String,
    /**
     * WHAT THIS CLIENT ACTUALLY SHOWED, not what the server currently publishes. A handset agreeing
     * against a cached notice sends the cached version, and the server stores it verbatim rather
     * than refusing it — see [UsageNoticeStore].
     */
    val noticeVersion: String,
    /**
     * WHEN THE BOX WAS TICKED, on this device's clock. Null is legal and means "answered straight
     * against the server"; a value more than fifteen minutes in the future is refused with a 422
     * rather than stored, so this is only ever the moment of the tap.
     */
    val recordedAt: String? = null,
    val note: String? = null,
)

/** `POST /usage/consent/withdraw`. No decision and no basis: the route supplies both, so a client
 *  cannot file a withdrawal as though somebody had demanded it. */
@Serializable
data class UsageWithdrawBody(
    val noticeVersion: String,
    val recordedAt: String? = null,
    val note: String? = null,
)

// ---------------------------------------------------------------------------------------------
// The figures
// ---------------------------------------------------------------------------------------------

/** The window every aggregate answers over. Half-open `[from, to)`; naive dates are read as UTC. */
@Serializable
data class UsageWindowDto(
    val from: String = "",
    val to: String = "",
    val days: Int = 0,
    val maxDays: Int = 0,
    val interval: String = "",
    val naiveDatesReadAs: String = "",
)

/** The caps a response states about itself. Read rather than hardcoded: a client that assumed the
 *  page size would silently page wrongly the day the server changed it. */
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

/**
 * One screen's aggregate. **Every metric is nullable and that is the contract, not caution** — see
 * this file's header. [withheld] is the branch; [withheldBecause] is the sentence that goes with it.
 */
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

/**
 * The sum over ONE PAGE, withheld rows excluded and COUNTED.
 *
 * The server named it `totalsForThisPage` rather than `total` on purpose: a field called `total`
 * beside a paged list is read as the platform figure by everybody, every time. No arm of this API
 * produces a platform total, and this client must never present one.
 */
@Serializable
data class UsagePageTotalsDto(
    val routes: Int = 0,
    val routesWithheld: Int = 0,
    val requests: Int = 0,
    val ok: Int = 0,
    val clientErrors: Int = 0,
    val serverErrors: Int = 0,
)

/** `GET /usage/routes` — the aggregate the admin screen draws. */
@Serializable
data class UsageRoutesPageDto(
    val items: List<UsageRouteRow> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
    val pages: Int = 0,
    val window: UsageWindowDto = UsageWindowDto(),
    /** "mounted" = every measured screen this deployment serves; "requested" = the caller named some. */
    val routeSource: String = "",
    val limits: UsageLimitsDto = UsageLimitsDto(),
    val totalsForThisPage: UsagePageTotalsDto = UsagePageTotalsDto(),
    /** Structurally never recorded, on any window. Excluded from the rows rather than reported as
     *  zero, because a row that is always zero reads as "nobody uses this screen" and the two are
     *  opposite facts. */
    val notMeasured: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** The consent block of `GET /usage/collection`. */
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

/**
 * `GET /usage/collection` — how the record was made, machine-readable, so a figure and its method can
 * be quoted together. The admin screen renders this ABOVE every figure, which is the web page's own
 * rule 2: a number with no stated method is a number nobody can check.
 */
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

/** One row of `GET /usage/me`'s per-screen aggregate. No withholding floor: there is no group to
 *  hide in when the subject is the reader. */
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

/** `GET /usage/me` — the caller's own aggregate, and only ever the caller's own. There is no
 *  `?userId=` on it: pointing it at somebody else is not a parameter this API withholds, it is a
 *  route that does not exist. */
@Serializable
data class UsageMineDto(
    val userId: String = "",
    val window: UsageWindowDto = UsageWindowDto(),
    val requests: Int = 0,
    val routes: List<UsageMyRouteRow> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** One recorded request, as the trail replays it. */
@Serializable
data class UsageEventDto(
    val id: String = "",
    val routeTemplate: String = "",
    val method: String = "",
    val statusCode: Int = 0,
    val durationMs: Int = 0,
    val clientApp: String = "",
    /** The answer the row was actually collected under. NULL means nobody had been asked. */
    val consentState: String? = null,
    val at: String = "",
)

/**
 * `GET /usage/me/trail` — the caller's own log, request by request, newest first.
 *
 * IT CARRIES ITS OWN [gate] AND [notes] AND BOTH ARE RENDERED. An empty list here would be read as
 * "you have never used the app", which is exactly the defect `/usage/me`'s own docstring names; the
 * gate says whether the emptiness has a cause the reader can act on, and for a refused account it
 * always does — they asked for precisely this.
 */
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

// ---------------------------------------------------------------------------------------------
// The device's copy of the notice
// ---------------------------------------------------------------------------------------------

/**
 * THE LAST NOTICE THIS DEVICE SUCCESSFULLY READ, kept so that a person can still be asked when the
 * text cannot be fetched.
 *
 * ── THE LOCKOUT THIS PREVENTS ─────────────────────────────────────────────────────────────────
 *
 * Agreeing is a condition of access, so a sign-in screen that cannot show the notice cannot ask the
 * question — and a client that refused to proceed without an answer would then be a fleet-wide
 * lockout triggered by one endpoint. That is not hypothetical on this deployment: `consent_notice()`
 * is computed from the running collection policy, so a bad deploy can break it while `POST
 * /auth/login` beside it keeps working, and every handset in every village would meet a disabled
 * button with no way past it.
 *
 * The server anticipated exactly this and wrote the permission into its own contract: *"a client
 * that sends a version this server has never heard of is not refused … because refusing would lock
 * out a handset holding a cached notice, and the honest record of 'they agreed to THAT text' is the
 * version they saw."* So this store is not a performance cache — **it is the thing that makes the
 * blocking gate safe to block with**, and the version it hands back is the one the person actually
 * read, which is the only version it would be honest to record them as having agreed to.
 *
 * ── ITS OWN FILE, AND WHY IT SURVIVES SIGN-OUT ────────────────────────────────────────────────
 *
 * Not `field_repository_auth`, which `TokenStore.clear()` empties on sign-out. The notice is a
 * published document rather than a credential; throwing it away when somebody signs out would mean
 * the next person to open the app on a phone with no signal cannot be asked, which is the lockout
 * again by a different door. It holds no answer, no account and no token — only public text.
 */
class UsageNoticeStore(context: Context) {
    private val store = context.applicationContext
        .getSharedPreferences("usage_consent_notice", Context.MODE_PRIVATE)

    /** The stored notice, or null when this device has never successfully read one. */
    fun read(): UsageNoticeDto? {
        val raw = store.getString(KEY_NOTICE, null) ?: return null
        // Decoded with the app's ONE lenient decoder, not a second `Json` built here: a notice that
        // gained a field on the server must not become an unreadable cache on every installed
        // handset, and `ignoreUnknownKeys` is what stops that. The same argument `ApiClient.json`
        // makes for the download manifest.
        val decoded = runCatching {
            ApiClient.json.decodeFromString(UsageNoticeDto.serializer(), raw)
        }.getOrNull()
        return decoded?.takeIf { it.isUsable }
    }

    /** Keep [notice], but only when it is one somebody could actually answer against. */
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

// ---------------------------------------------------------------------------------------------
// Pure helpers. No Compose, no Context — so the rules above are reachable from a JVM test.
// ---------------------------------------------------------------------------------------------

/**
 * True exactly when the server refused to state this row's figures rather than reporting them.
 *
 * ONE FUNCTION, so no screen re-derives the check by hand. The twin of `isWithheldRoute` in
 * `frontend/lib/usage.ts`, and it exists for the same reason: `row.requests == null` and
 * `row.withheld` are the same fact today, and a reader who tests the first one has written a check
 * that stops being right the day a route reports a genuine null for some other reason.
 */
fun usageWithheld(row: UsageRouteRow): Boolean = row.withheld

/**
 * A figure, or the withheld em dash. **The only renderer of a usage number on this client.**
 *
 * The dash and a small number must not look alike, or a reader skims past a refusal as though it
 * were a quiet fortnight. `RateFigure` on the web's cross-workshop comparison page draws the same
 * distinction and this mirrors it.
 */
fun usageMetricText(value: Int?): String = value?.let { usageCount(it) } ?: "—"

/** A duration in whole milliseconds, or the withheld dash. Never computed — read off the wire. */
fun usageDurationText(value: Int?): String = value?.let { "$it ms" } ?: "—"

/**
 * A count with thousands separators, grouped the Indian way — 1,23,456 — because every reader of
 * this screen is reading Indian figures and `%,d` under a default locale would group them in
 * thousands. The web calls `toLocaleString("en-IN")` at the same call sites.
 */
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

/**
 * An ISO stamp as "12 Aug 2026, 04:35 pm" in the reader's own zone, or null when it is not one.
 *
 * NULL RATHER THAN THE RAW STRING. A screen that fell back to the wire value would print
 * "2026-08-12T11:05:00+00:00" into a sentence about when somebody agreed, which is the moment a
 * consent record stops reading as a record of a decision a person made.
 */
fun usageMoment(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val moment = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: return null
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").format(moment.atZone(ZoneId.systemDefault()))
    }.getOrNull()
}

/** An ISO stamp as "12 Aug 2026", or null. Used where only the day is meaningful. */
fun usageDay(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val moment = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: return null
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy").format(moment.atZone(ZoneId.systemDefault()))
    }.getOrNull()
}

/**
 * The moment a box was ticked, in the shape `recordedAt` wants.
 *
 * UTC WITH AN EXPLICIT OFFSET, never a local naive string. The server refuses a `recordedAt` more
 * than fifteen minutes in the future, and a handset whose clock is running ahead of UTC by hours
 * would be refused for a tap that genuinely just happened if the offset were missing and the value
 * were read as UTC.
 */
fun usageNowStamp(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    .format(Instant.now().atOffset(java.time.ZoneOffset.UTC))

/** An ISO stamp [days] before now, for the default window of a screen. UTC, matching the API. */
fun usageWindowStart(days: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    .format(Instant.now().minusSeconds(days * 86_400L).atOffset(java.time.ZoneOffset.UTC))
