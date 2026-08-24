package com.designprototype.workshop.ui

import android.content.Context
import android.os.SystemClock
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.File
import java.time.Instant
import kotlin.math.abs

/**
 * SCANNING A DESIGN-WORKSHOP CARD NOW DOES SOMETHING. The join path that replaces a refusal.
 *
 * ── WHAT THE REFUSAL WAS, AND WHY IT WAS ONE ──────────────────────────────────────────────────
 *
 * `DwWorkshopCodes.designWorkshopCodeNotOpenableMessage()` answered every scanned `DPW1:G:…` card
 * with "This version of the app cannot open a design workshop from a code". It was honest — nothing
 * was asked of the server, so nothing was claimed about the workshop — and it was a dead end: a
 * designer handed a card by the colleague standing next to them, precisely so they could work on the
 * workshop together, was told the app could not act on it and sent to find an admin.
 *
 * ── AND THE SERVER HAS BEEN READY THE WHOLE TIME, WHICH IS THE FINDING ────────────────────────
 *
 * `POST /api/design-workshop-access/requests` exists, is shipped, takes exactly
 * `{workshopId, scannedCode, note}` and answers 202 with the sentence to show. NO CLIENT CALLS IT.
 * `frontend/` has no reference to that prefix at all and `WorkshopRepositoryApi` declares only the
 * REPOSITORY workshop's queue (`workshops/access-requests`), which is a different table for a
 * different object — the very pair of nouns `schemas/design_workshop_access.py` says has already
 * produced one card that opened the wrong kind of record. So this handset is the first client of
 * that route, and the join path needed no server work at all.
 *
 * ── THREE RULES THIS FILE TAKES FROM THE SERVER AND DOES NOT RE-DECIDE ────────────────────────
 *
 *  1. THE SENTENCE ON SUCCESS IS THE SERVER'S, SHOWN AS GIVEN. `design_workshop_access.py` builds
 *     ONE reply for all seven of its outcomes, on purpose, and its own comment says why: "a refusal
 *     or a confirmation that varied by outcome would be the enumeration oracle this route is built
 *     to avoid, and the surest way to reintroduce one is to 'improve' the copy for a single branch."
 *     It also says: "The clients must show this sentence and must not decorate it with a pending
 *     state the server has not confirmed." So [DwJoinOutcome.Asked] carries the server's `detail`
 *     and this file never writes a second version of it.
 *  2. THE SCANNED CODE IS EVIDENCE, NEVER AUTHORISATION. The four check characters are FNV-1a over
 *     the payload, the algorithm ships to every browser, and `design_workshop_access.py` states
 *     plainly that "anyone can compute a valid check for any id". Nothing here treats a valid code
 *     as grounds for anything; it is sent so an admin reading the queue can see the card was
 *     physically present, and that is the whole of its worth.
 *  3. GRANTING ACCESS IS SOMEBODY ELSE'S ACT. This asks. It cannot admit anybody, and it must never
 *     imply that it has — which is also why the queued-offline sentence below says a request is
 *     WAITING TO BE SENT rather than that one has been sent.
 *
 * ── THE OFFLINE HALF, AND THE ONE THING IT MUST NOT CLAIM ─────────────────────────────────────
 *
 * A card is scanned in a courtyard that has had no signal for two days. The scan is written to
 * [DwInductionQueue] with the time it happened, and it is sent on the next opportunity — never
 * discarded, which is the requirement. The time travels as EVIDENCE inside the `note`, and
 * [dwInductionNote] is written so an admin can read it and so that nothing anywhere can mistake it
 * for authority:
 *
 * THE HANDSET CLOCK IS UNTRUSTED AND SAYING SO IS NOT A FORMALITY. If "who scanned first" were
 * decided by a number the scanner supplies, whoever wound their phone's date back furthest would win
 * — and on a single-use card that is somebody else's place in a workshop. So the note carries the
 * device's wall clock AND an independent, clock-proof measure of how long ago the scan was
 * (`SystemClock.elapsedRealtime`, which advances while the device is awake and cannot be set), and
 * says which is which. Arrival order at the server decides. See [DwPendingInduction].
 */

// --------------------------------------------------------------------------------------
// The wire
// --------------------------------------------------------------------------------------

/**
 * `POST /design-workshop-access/requests` — the ask.
 *
 * `workshopId` is required even though `scannedCode` carries the same id, and the redundancy is the
 * server's design rather than sloppiness: it keeps the id the caller BELIEVES it is asking about
 * apart from the id the code DECODES to, so a client that scanned one card and posted another
 * workshop's id gets a 422 instead of a silently redirected request. Both are set from ONE decode
 * here, which is the point of passing them together.
 */
@Serializable
data class DwWorkshopJoinBody(
    val workshopId: String,
    val scannedCode: String? = null,
    val note: String? = null,
)

/** 202: `{received: true, detail: "…"}`. `detail` is the sentence to show — see rule 1 above. */
@Serializable
data class DwWorkshopJoinAck(
    val received: Boolean = false,
    val detail: String? = null,
)

/**
 * The join route as a typed service.
 *
 * DECLARED HERE RATHER THAN ADDED TO `WorkshopRepositoryApi`, and `ApiClient.retrofit`'s own
 * docstring is the licence: it exists "so a feature can declare its OWN typed service without
 * standing up a second HTTP stack beside this one". `ui/designworkshop/DwAsrModelInstallUi.kt:728`
 * is the standing precedent for doing it from a UI file. Going through `ApiClient` is what keeps
 * this call inside the 504 retry that exists because CloudFront times this origin out, and inside
 * the auth interceptor that reads a FRESH token per request.
 *
 * (It would still be tidier inside `WorkshopRepositoryApi`. That file is not this wave's to edit,
 * and this is the seam the repository built for exactly that situation.)
 */
interface DwWorkshopJoinApi {
    @POST("design-workshop-access/requests")
    suspend fun requestDesignWorkshopAccess(@Body body: DwWorkshopJoinBody): DwWorkshopJoinAck
}

// --------------------------------------------------------------------------------------
// The queue
// --------------------------------------------------------------------------------------

/**
 * One scan of a design-workshop card, waiting to be sent.
 *
 * ── EVERY FIELD IS HERE BECAUSE OF SOMETHING THAT CAN BE SPOOFED OR LOST ──────────────────────
 *
 * @property workshopId the workshop the card names. ONE ROW PER WORKSHOP — see [dwMergeInduction].
 * @property code the canonical code, sent as `scannedCode`. Evidence for the admin reading the
 *   queue; not a credential, and never described as one.
 * @property scannedAtDeviceUtc the DEVICE's wall clock at the moment of the scan, ISO-8601.
 *   SETTABLE BY WHOEVER HOLDS THE PHONE, which is exactly why it is not the only time recorded and
 *   why [dwInductionNote] labels it as the device's claim rather than as a fact.
 * @property scannedAtElapsedMs `SystemClock.elapsedRealtime()` at the scan — milliseconds since the
 *   device last booted. It ADVANCES WHILE THE DEVICE IS AWAKE and cannot be set from an app or from
 *   the date screen, so the difference between it and its value at send time is a clock-proof
 *   measure of how long the scan has been waiting. That difference is what an admin can actually
 *   rely on.
 * @property bootWallClockMs `currentTimeMillis() - elapsedRealtime()` at the scan — an estimate of
 *   the wall-clock instant the device booted. Its ONLY job is to make a clock change visible: if
 *   this differs from the same quantity at send time, either the device rebooted (which invalidates
 *   the elapsed measure) or somebody moved the clock. Both are things an admin should see rather
 *   than be protected from, and both are said out loud in the note.
 * @property attempts how many times sending has been tried. **IT IS RECORDED AND NOTHING ACTS ON IT
 *   YET, and that is stated rather than implied**: the field's own comment used to promise that "a
 *   permanently-refused row can be reported rather than retried for ever", and no code read it — nor
 *   could it, because [DwInductionQueue.flush] had no caller at all, so the counter never moved in
 *   production either. It moves now. What must NOT be built on it casually is a retry ceiling: the
 *   whole point of this queue is a courtyard with no signal for a fortnight, and a row abandoned
 *   after five failed sends is the discarded scan the requirement forbids. A ceiling is only honest
 *   once there is a screen that shows the abandoned row to the person whose scan it was.
 * @property kind which door this row goes through — [DW_INDUCTION_ASK] or [DW_INDUCTION_JOIN]. See
 *   the constants: the two are different acts against different endpoints and must not merge.
 * @property bootId an identifier for the boot the scan happened in, so the elapsed measure above is
 *   only ever compared within one. Empty on a row written by a build that did not record it, which
 *   the server reads as "no evidence" rather than as a claim.
 */
@Serializable
data class DwPendingInduction(
    val workshopId: String,
    val code: String,
    val scannedAtDeviceUtc: String,
    val scannedAtElapsedMs: Long,
    val bootWallClockMs: Long,
    val attempts: Int = 0,
    val kind: String = DW_INDUCTION_ASK,
    val bootId: String = "",
) {
    /**
     * What the queue is keyed by. **NOT the workshop id alone, which was the obvious thing and is
     * wrong.** A pending ASK and a pending JOIN CARD for the same workshop are two different acts
     * against two different endpoints, and folding them into one row would silently throw one away —
     * most likely the card, since a card is scanned after the ask has already failed.
     *
     * A computed property so it is not serialised: it is derived from two stored fields and a
     * persisted copy could disagree with them.
     */
    val queueKey: String get() = "$kind:$workshopId"
}

/**
 * A row that goes to `POST /design-workshop-access/requests` — the ASK. An administrator decides.
 *
 * The default, and the default is the old behaviour, deliberately: a queue file written by the build
 * before join cards existed holds rows with no `kind` at all, and they are asks.
 */
const val DW_INDUCTION_ASK = "ASK"

/**
 * A row that goes to `POST /design-workshop-access/redemptions` — the JOIN CARD, which admits.
 *
 * ⚠ **[DwPendingInduction.code] ON ONE OF THESE IS A LIVE CREDENTIAL**, and it is written to this
 * app's private files directory because there is no alternative that keeps the requirement: a card
 * scanned in a courtyard with no signal must not be discarded, and redeeming it later means holding
 * the string. The mitigations are that the file is app-private, that the row is deleted the moment
 * the server answers anything terminal, and that nothing on this device treats the card as admission
 * — it is not a cached grant, it is an unsent request. Do not log one and do not put one on a screen.
 */
const val DW_INDUCTION_JOIN = "JOIN"

/**
 * The note sent beside a queued scan: what the device says, and what cannot be faked.
 *
 * PURE, so `DwWorkshopJoinTest` pins it — and the wording of this one matters more than most,
 * because an admin deciding who gets a single-use place in a workshop will read it and act on it.
 *
 * ── WHAT IT SAYS, IN THIS ORDER, AND WHY ──────────────────────────────────────────────────────
 *
 *  1. That the scan was offline and is only now arriving. Without this the `createdAt` on the queue
 *     row is the only time an admin sees, and a two-day-old scan looks like a scan from this morning.
 *  2. HOW LONG AGO, from the monotonic clock. This is the number to trust and it is named as such.
 *  3. What the DEVICE'S OWN CLOCK claimed, labelled as a claim. It is worth showing because it is
 *     often right and it is what the designer would tell you if asked; it is labelled because it is
 *     the one number in here that whoever holds the handset can choose.
 *  4. A WARNING when the two disagree — a reboot or a clock change between the scan and the send.
 *     Said out loud rather than silently dropped: an admin who is not told cannot ask.
 *
 * And it never says "I was first". Order of arrival at the server decides that, and a note that
 * argued otherwise would be a scanner trying to adjudicate — the exact thing the requirement rules
 * out.
 *
 * @param elapsedNowMs `SystemClock.elapsedRealtime()` at the moment of sending.
 * @param wallNowMs `System.currentTimeMillis()` at the moment of sending.
 */
fun dwInductionNote(
    pending: DwPendingInduction,
    elapsedNowMs: Long,
    wallNowMs: Long,
): String {
    val bootNow = wallNowMs - elapsedNowMs
    val rebootedOrClockMoved = abs(bootNow - pending.bootWallClockMs) > DW_INDUCTION_CLOCK_SLOP_MS
    val waited = elapsedNowMs - pending.scannedAtElapsedMs

    return buildString {
        append("Scanned from the card with no connection and sent when signal returned. ")
        if (!rebootedOrClockMoved && waited >= 0) {
            append("Measured on this device's uptime clock, which cannot be set by hand, the scan ")
            append("happened ")
            append(dwSpanInWords(waited))
            append(" before this was sent. ")
        } else {
            // NAMED, NOT GUESSED AT. The elapsed measure is only meaningful across one boot with an
            // unchanged clock, and claiming it anyway would be inventing precision.
            append("This device has restarted or had its clock changed since the scan, so how long ")
            append("ago the scan happened cannot be measured here. ")
        }
        append("The device's own clock said the scan was at ")
        append(pending.scannedAtDeviceUtc)
        append(" (UTC) — that is what the handset claims, not something this app can verify; a ")
        append("phone's date can be changed by anyone holding it. Which scan reached the server ")
        append("first is the only ordering that counts.")
    }
}

/**
 * How far the boot-instant estimate may drift before it counts as a reboot or a clock change.
 *
 * Two seconds. `currentTimeMillis() - elapsedRealtime()` is not perfectly stable even on an
 * untouched device — the two clocks are read a few instructions apart, and NTP nudges the wall clock
 * by small amounts — so a strict comparison would report a clock change on every single scan and the
 * warning would stop meaning anything. Two seconds is far above that noise and far below any
 * deliberate change worth warning about.
 */
private const val DW_INDUCTION_CLOCK_SLOP_MS = 2_000L

/**
 * A duration in the words an administrator reads, from a monotonic millisecond count.
 *
 * Deliberately coarse. "About 2 days" is what a decision is made on; "48 h 13 m 6 s" invites a
 * precision the measurement does not have (the uptime clock does not advance in deep sleep on every
 * device, so this is a FLOOR on the wait rather than an exact figure — which is the safe direction,
 * because it never makes an old scan look newer than it was).
 */
internal fun dwSpanInWords(millis: Long): String {
    if (millis < 0) return "an unknown time"
    val minutes = millis / 60_000L
    return when {
        minutes < 1L -> "less than a minute"
        minutes < 60L -> "about $minutes minute${if (minutes == 1L) "" else "s"}"
        minutes < 60L * 48L -> {
            val hours = minutes / 60L
            "about $hours hour${if (hours == 1L) "" else "s"}"
        }
        else -> {
            val days = minutes / (60L * 24L)
            "about $days day${if (days == 1L) "" else "s"}"
        }
    }
}

/**
 * Fold a fresh scan into a queue that may already hold one for the same workshop.
 *
 * ── ONE ROW PER WORKSHOP, AND THE FIRST SCAN TIME IS THE ONE THAT SURVIVES ────────────────────
 *
 * The server enforces one request row per (workshop, requester) with a UNIQUE INDEX, and its own
 * header explains what that index is for: "the SAME ask arriving twice must be one row", enforced by
 * Postgres rather than by a read-then-write "with a window in the middle, which is the shape this
 * repository has already shipped a double-filed government record from". A local queue holding three
 * scans of one card would send three asks that collapse into one row anyway — so it holds one.
 *
 * THE FIRST SCAN'S TIME IS KEPT AND THE LATEST CODE WINS, which is the same asymmetry the server
 * applies to its own row: `createdAt` is sacred ("a replay is a no-op and does not move createdAt",
 * so nobody loses their place in the queue by scanning again), while `scannedCode` is overwritten by
 * the reopen branch. Here the reason is more concrete: a designer who scans the card again after ten
 * minutes has not just arrived, and letting the second scan reset the time would quietly hand their
 * place to somebody who scanned later.
 *
 * PURE, so the rule is asserted rather than trusted.
 */
fun dwMergeInduction(
    queue: List<DwPendingInduction>,
    incoming: DwPendingInduction,
): List<DwPendingInduction> {
    // KEYED BY [DwPendingInduction.queueKey] AND NOT BY THE WORKSHOP ID. See that property: an ASK
    // and a JOIN CARD for one workshop are two acts against two endpoints, and merging them would
    // discard one — in practice the card, which is the one that would actually have worked.
    val existing = queue.firstOrNull { it.queueKey == incoming.queueKey }
        ?: return queue + incoming
    val merged = existing.copy(
        // The latest code, because a second card for one workshop is a real thing (a reprint, or a
        // fresh card after the first was spent) and it is the one actually presented.
        code = incoming.code,
        // THE LATEST BOOT ID TRAVELS WITH THE LATEST CODE. The elapsed measure kept below belongs to
        // the FIRST scan, so the boot it was taken in is the one that has to be reported — and it is
        // the same boot, because a reboot is exactly what mints a new id. Carrying the incoming one
        // when the two differ would label the kept elapsed reading with a boot it did not happen in,
        // which is the one thing this field exists to make impossible.
        bootId = existing.bootId.ifEmpty { incoming.bootId },
        // Attempts are NOT reset. A row that has been refused four times is still a row that has
        // been refused four times; forgetting that on a re-scan is how a hopeless send becomes an
        // infinite one.
        attempts = existing.attempts,
    )
    return queue.map { if (it.queueKey == incoming.queueKey) merged else it }
}

/**
 * Where a scan waits. A JSON list in this app's own files directory, oldest first.
 *
 * ── WHY A FILE OF ITS OWN AND NOT `OfflineOutbox` ─────────────────────────────────────────────
 *
 * `data/Offline.kt`'s outbox queues RECORD CREATES with their media, replays them and deletes the
 * local copy only after the server has taken them. An access request is not a record: it creates
 * nothing this device owns, it carries no files, and its replay has a completely different success
 * condition (a 202 that means "somebody may now see that you asked"). Folding it in would mean
 * teaching every branch of that replay about a type that has no payload and no media.
 *
 * THE ORDERING RULE THAT IS NOT THIS FILE'S TO ENFORCE, stated so the next wave does not miss it: a
 * record captured against a workshop must not reach the server BEFORE the ask that explains why this
 * account is touching that workshop, or it arrives with nowhere to land. Doing that properly means
 * ordering this queue ahead of `OfflineOutbox`'s, and `Offline.kt` and `WorkshopSync.kt` are not this
 * wave's files. Today nothing captures against an unjoined workshop, so nothing is broken; the day
 * provisional capture lands, this is the first thing to wire.
 *
 * ── WHY THE WHOLE LIST IS REWRITTEN EVERY TIME ────────────────────────────────────────────────
 *
 * It holds one row per workshop a designer has scanned and not yet been admitted to — realistically
 * one, occasionally three. `OfflineOutbox` streams because it can hold a fortnight of records with
 * their media; this cannot, and a rewrite of a few hundred bytes under a [Mutex] is simpler than a
 * partial update that can half-apply.
 */
object DwInductionQueue {

    private const val FILE_NAME = "dw-pending-inductions.json"

    /**
     * `ignoreUnknownKeys` for the reason every defaulted field in `PendingEntry` exists: the queue on
     * a handset that has been offline for a fortnight predates the build that reads it, and a field
     * added here must not make an older row unreadable — which would mean silently dropping somebody's
     * scan, the one outcome the requirement rules out.
     */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /**
     * The serializer, named rather than reified.
     *
     * `encodeToString(rows)` compiles against `kotlinx.serialization.encodeToString`'s reified
     * overload only when that extension is imported, and the import is the sort of thing an IDE
     * removes as unused when the call site is the only user. Naming the serializer makes the call a
     * plain function call that cannot be broken by an import.
     */
    private val DW_INDUCTION_LIST = ListSerializer(DwPendingInduction.serializer())

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Everything waiting, oldest first. An unreadable file reads as empty rather than throwing. */
    suspend fun all(context: Context): List<DwPendingInduction> = withContext(Dispatchers.IO) {
        mutex.withLock { read(context) }
    }

    private fun read(context: Context): List<DwPendingInduction> = runCatching {
        val target = file(context)
        if (!target.exists()) return emptyList()
        json.decodeFromString(DW_INDUCTION_LIST, target.readText())
    }.getOrElse { emptyList() }

    private fun write(context: Context, rows: List<DwPendingInduction>) {
        runCatching {
            val target = file(context)
            if (rows.isEmpty()) {
                target.delete()
            } else {
                target.writeText(json.encodeToString(DW_INDUCTION_LIST, rows))
            }
        }
    }

    /**
     * Record a scan, or fold it into the one already waiting for that workshop.
     *
     * THE CLOCKS ARE READ HERE AND NOWHERE ELSE, at the moment of the scan, which is the only moment
     * they mean anything. Reading them at send time would record the time the signal came back.
     */
    suspend fun record(
        context: Context,
        workshopId: String,
        code: String,
        kind: String = DW_INDUCTION_ASK,
    ): DwPendingInduction =
        withContext(Dispatchers.IO) {
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            // THE BOOT MARK IS READ AND ADVANCED HERE, in the same breath as the clocks, because
            // that is the only moment the three numbers describe one instant. See [DwBootMark].
            val mark = DwBootMark.observe(context, elapsedNowMs = elapsed, wallNowMs = wall).first
            val row = DwPendingInduction(
                workshopId = workshopId,
                code = code,
                scannedAtDeviceUtc = runCatching { Instant.ofEpochMilli(wall).toString() }
                    .getOrElse { "unknown" },
                scannedAtElapsedMs = elapsed,
                bootWallClockMs = wall - elapsed,
                kind = kind,
                bootId = mark.bootId,
            )
            mutex.withLock {
                val merged = dwMergeInduction(read(context), row)
                write(context, merged)
                merged.first { it.queueKey == row.queueKey }
            }
        }

    /** Forget one row — called only when the server has given a terminal answer about it. */
    suspend fun clear(context: Context, queueKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context).filterNot { it.queueKey == queueKey }) }
    }

    /**
     * Note one more attempt against a row, so a hopeless send can eventually be reported.
     *
     * `internal` RATHER THAN `private`, WHICH IS THE FINDING THIS FILE SHIPPED. It was private and
     * only [flush] called it — and [flush] had no caller anywhere in `src/main`, so `attempts` never
     * incremented in production and the whole queue never drained. It is reachable now because
     * [DwInductionFlusher] drives [flush] from a process-wide network callback, and it is `internal`
     * so `DwWorkshopJoinTest` can assert the counter actually moves.
     */
    internal suspend fun noteAttempt(context: Context, queueKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(
                context,
                read(context).map {
                    if (it.queueKey == queueKey) it.copy(attempts = it.attempts + 1) else it
                },
            )
        }
    }

    /**
     * Send everything waiting, oldest first, and answer with what happened to each.
     *
     * OLDEST FIRST because arrival order at the server is what decides a single-use place, and the
     * one ordering this device can honestly assert is the order it saw the cards in. It is not a
     * claim about anybody else's scan.
     *
     * A refusal (422 — the code is damaged, or names a different workshop from the id beside it) is
     * PERMANENT and clears the row: re-sending a card the server has already explained will never
     * work is how a queue becomes an infinite retry. Anything else leaves the row where it is.
     */
    suspend fun flush(context: Context): List<Pair<DwPendingInduction, DwJoinOutcome>> {
        val rows = all(context)
        if (rows.isEmpty()) return emptyList()
        return rows.map { row ->
            // ONE ROW, ONE DOOR, DECIDED BY [DwPendingInduction.kind]. A join card must not be posted
            // to the ask route: the ask route's decoder refuses the `J` letter outright (and says so),
            // and even if it did not, filing a request for somebody who could simply be let in is the
            // dead end this whole wave removes.
            val outcome = if (row.kind == DW_INDUCTION_JOIN) {
                dwRedeemQueuedJoinCard(context, row)
            } else {
                dwSendJoinAsk(context, row)
            }
            when (outcome) {
                is DwJoinOutcome.Asked -> clear(context, row.queueKey)
                is DwJoinOutcome.Inducted -> clear(context, row.queueKey)
                is DwJoinOutcome.Refused -> clear(context, row.queueKey)
                is DwJoinOutcome.Queued -> noteAttempt(context, row.queueKey)
            }
            row to outcome
        }
    }
}

/**
 * WHERE THE BOOT AN ELAPSED READING BELONGS TO IS REMEMBERED.
 *
 * ── WHY A MONOTONIC READING NEEDS A BOOT IDENTIFIER AT ALL ────────────────────────────────────
 *
 * `SystemClock.elapsedRealtime()` cannot be set by hand, which is what makes it the only
 * device-reported time worth anything — and it RESETS TO ZERO ON REBOOT. So the server's estimate of
 * when a scan really happened (`serverArrivedAt - (syncedAtElapsedSec - scannedAtElapsedSec)`) is
 * meaningful only when both readings come from ONE boot, and `RecordAccessTokenRedemption.bootId`
 * exists precisely so that is CHECKABLE rather than assumed. Without it, a handset that rebooted
 * between the scan and the sync reports a difference that is not a duration at all.
 *
 * ── HOW A REBOOT IS DETECTED, AND WHY NOT BY THE WALL CLOCK ───────────────────────────────────
 *
 * By the monotonic clock GOING BACKWARDS: `elapsedRealtime()` only ever increases within a boot, so a
 * reading lower than the last one this app saw can only mean the device restarted. That test is
 * immune to the thing the whole feature is defending against — somebody moving the date.
 *
 * The obvious alternative, watching `currentTimeMillis() - elapsedRealtime()` for a change, cannot
 * tell a reboot from a clock change, and conflating those two is exactly what `clockJumpObserved`
 * exists to keep apart: a phone that finds a network after two days offline legitimately jumps, and
 * calling that a reboot would throw away a perfectly good elapsed measure. So the drift in that
 * quantity is reported as a CLOCK JUMP and the backwards step is reported as a NEW BOOT.
 *
 * PURE LOGIC IN [advance], SO IT IS ASSERTED RATHER THAN TRUSTED. The file is the only impure part.
 */
@Serializable
data class DwBootMark(
    /** A random identifier for this boot. Not derived from anything about the device. */
    val bootId: String,
    /** The highest `elapsedRealtime()` this app has seen in this boot. */
    val lastElapsedMs: Long,
    /** `currentTimeMillis() - elapsedRealtime()` when this boot was first seen. */
    val bootWallClockMs: Long,
) {
    companion object {
        private const val FILE_NAME = "dw-boot-mark.json"
        private val mutex = Mutex()

        /**
         * How far the boot-instant estimate may drift before it counts as a clock change — the same
         * two seconds, and for the same reason, as [dwInductionNote]'s slop. The two clocks are read
         * a few instructions apart and NTP nudges the wall clock, so a strict comparison would report
         * a jump on every single scan and the flag would stop meaning anything.
         */
        private const val CLOCK_SLOP_MS = 2_000L

        /**
         * The monotonic clock going backwards by more than this is a reboot.
         *
         * Not zero, because `elapsedRealtime()` is read on different threads at unordered moments and
         * a few milliseconds of apparent regression is a race rather than a restart. A real reboot
         * takes the reading to near zero, which is many minutes below any value worth storing.
         */
        private const val REBOOT_SLOP_MS = 5_000L

        /**
         * The mark this observation implies, and whether the wall clock has moved. PURE.
         *
         * @param stored what was on disk, or null the first time this app ever runs.
         * @param mint how a fresh identifier is made — passed in so a test can pin one.
         */
        fun advance(
            stored: DwBootMark?,
            elapsedNowMs: Long,
            wallNowMs: Long,
            mint: () -> String,
        ): Pair<DwBootMark, Boolean> {
            val bootWallNow = wallNowMs - elapsedNowMs
            val rebooted = stored == null || elapsedNowMs < stored.lastElapsedMs - REBOOT_SLOP_MS
            if (rebooted) {
                // A NEW BOOT GETS A NEW ID AND NO CLOCK-JUMP FLAG. There is nothing to compare the
                // wall clock against: the previous boot's estimate belonged to a different run, and
                // reporting a jump for every restart would make the flag noise.
                return DwBootMark(mint(), elapsedNowMs, bootWallNow) to false
            }
            val jumped = kotlin.math.abs(bootWallNow - stored.bootWallClockMs) > CLOCK_SLOP_MS
            return stored.copy(
                // The HIGHEST reading, not the latest: two threads reading a few milliseconds apart
                // must not be able to walk this backwards and fake a reboot on the next scan.
                lastElapsedMs = kotlin.math.max(stored.lastElapsedMs, elapsedNowMs),
                // THE FIRST ESTIMATE OF THIS BOOT IS KEPT, deliberately, so the flag stays true for
                // the rest of the boot once the clock has moved. Rewriting it would mean the second
                // scan after a clock change reported nothing, and an admin comparing two rows from
                // one handset would see the evidence on one and not the other.
                bootWallClockMs = stored.bootWallClockMs,
            ) to jumped
        }

        /**
         * Read the mark, advance it, write it back, and answer with it.
         *
         * An unreadable or absent file is a first run: a fresh boot id, no clock-jump claim. That is
         * the honest reading of "no evidence" and it is what [advance] does with a null.
         */
        suspend fun observe(
            context: Context,
            elapsedNowMs: Long,
            wallNowMs: Long,
        ): Pair<DwBootMark, Boolean> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val file = File(context.filesDir, FILE_NAME)
                    val stored = runCatching {
                        if (!file.exists()) null else DW_BOOT_JSON.decodeFromString(serializer(), file.readText())
                    }.getOrNull()
                    val advanced = advance(
                        stored,
                        elapsedNowMs = elapsedNowMs,
                        wallNowMs = wallNowMs,
                        mint = { java.util.UUID.randomUUID().toString() },
                    )
                    runCatching {
                        file.writeText(DW_BOOT_JSON.encodeToString(serializer(), advanced.first))
                    }
                    advanced
                }
            }

        /**
         * Has the wall clock moved since the boot this row's scan happened in?
         *
         * Read at SEND time from the stored mark, so it answers the question the server's
         * `clockJumpObserved` column asks — "did `ACTION_TIME_CHANGED` fire between the scan and this
         * call" — without needing a broadcast receiver registered for the life of the process. The
         * measurement is the same one [dwInductionNote] shows an admin in prose; this is the sortable
         * form of it that finding 5 says the columns were added for.
         */
        suspend fun clockMovedSince(context: Context, row: DwPendingInduction): Boolean =
            withContext(Dispatchers.IO) {
                val elapsed = SystemClock.elapsedRealtime()
                val bootWallNow = System.currentTimeMillis() - elapsed
                kotlin.math.abs(bootWallNow - row.bootWallClockMs) > CLOCK_SLOP_MS
            }
    }
}

/** `ignoreUnknownKeys` for the reason [DwInductionQueue]'s own reader gives: an older row must read. */
private val DW_BOOT_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// --------------------------------------------------------------------------------------
// The act
// --------------------------------------------------------------------------------------

/** What came of asking to join. */
sealed interface DwJoinOutcome {
    /**
     * The server took the ask. [detail] is ITS sentence and must be shown as given — see rule 1 in
     * the file header. It is deliberately conditional ("if that workshop exists and you are not
     * already on it…") and must not be decorated with a pending state the server has not confirmed.
     */
    data class Asked(val detail: String) : DwJoinOutcome

    /** Written down and waiting for signal. [message] says so without claiming anything was sent. */
    data class Queued(val message: String) : DwJoinOutcome

    /** The server refused the BODY — 422, about the card and not about who may see what. */
    data class Refused(val message: String) : DwJoinOutcome

    /**
     * A JOIN CARD was redeemed. **This arm is an INDUCTION and not an ask** — nobody has to decide.
     *
     * A FOURTH ARM AND NOT AN [Asked] WITH DIFFERENT WORDS, because the two make opposite claims and
     * a screen has to render them differently. [Asked] is deliberately conditional and promises
     * nothing; this says something has actually happened to somebody's access.
     *
     * @property message the server's own sentence for whichever of its three outcomes this was —
     *   full, provisional, or already a member. Shown as given, on the same rule as [Asked]: the
     *   wording distinguishes "you are in" from "that card was already used, but nothing you record
     *   is lost", and a client that rewrote either would be rewriting the one thing the person needs.
     * @property fullMember true only for the server's `FULL` outcome — the person is on the workshop
     *   now. **False for PROVISIONAL, and a screen must not paint that as membership**: the server's
     *   own comment insists any UI over it "must keep the state visibly and persistently provisional
     *   … and must never dress it as membership", because somebody can work for days into a workspace
     *   that turns out to be nothing.
     */
    data class Inducted(val message: String, val fullMember: Boolean) : DwJoinOutcome
}

/**
 * What to say when a scan is written down but not yet sent.
 *
 * IT MUST NOT SAY A REQUEST HAS BEEN SENT, and that is not pedantry: `DwWorkshopCodesTest` already
 * asserts of two neighbouring sentences that neither contains "request sent" or "we have asked",
 * because a designer who believes an admin can see their ask will wait instead of walking over and
 * asking. Nothing has left this device yet.
 *
 * IT ALSO SAYS THE SCAN IS SAFE, which is the half a designer actually wants to know in a courtyard:
 * the alternative reading of "no connection" is "do it again later", and doing it again later is
 * exactly what they should not have to remember to do.
 *
 * ── AND IT USED TO BE FALSE, WHICH IS WORTH RECORDING RATHER THAN QUIETLY FIXING ──────────────
 *
 * It said "the request goes out on its own as soon as there is signal … You do not need to scan
 * again", and [DwInductionQueue.flush] HAD NO CALLER ANYWHERE IN `src/main`. A scan taken with no
 * signal was written to `dw-pending-inductions.json` and sat there for ever, while the sentence on
 * screen told the one person who could have recovered it not to do the one thing that would have.
 *
 * The claim is true now, and the SECOND clause is what makes it true rather than the first: the flush
 * runs from a process-wide network callback registered by [DwInductionFlusher] AND on every launch,
 * so the two cases are "signal returns while the app is alive" and "the app is opened again". What is
 * still NOT promised — deliberately, because promising it would need `WorkManager` and a new
 * dependency — is a send while the app is not running at all. The sentence says so.
 */
fun dwJoinQueuedMessage(): String =
    "There is no connection, so the card has been kept on this device along with the time you " +
        "scanned it. It is sent by itself as soon as this device has signal, and if the app is " +
        "closed before that happens it is sent the next time the app is opened with signal. " +
        "Nothing is sent yet, so nobody can see it yet. You do not need to scan again."

/**
 * Ask to join one design workshop, sending the code as evidence.
 *
 * Called both for a fresh scan and by [DwInductionQueue.flush] for one that has been waiting; the
 * note tells them apart, because a scan from two days ago is a different fact from one from this
 * second and an admin deciding a single-use place needs to know which it is.
 */
suspend fun dwSendJoinAsk(context: Context, pending: DwPendingInduction): DwJoinOutcome =
    withContext(Dispatchers.IO) {
        if (!ConnectivityObserver.isOnline(context)) return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())
        val api = runCatching {
            ApiClient.retrofit(TokenStore(context)).create(DwWorkshopJoinApi::class.java)
        }.getOrNull() ?: return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())

        val elapsedNow = SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        // A NOTE ONLY FOR A SCAN THAT WAITED. A note on a scan made one second ago would tell an
        // admin nothing they cannot see from the row's own arrival time, and a queue full of
        // boilerplate is a queue nobody reads carefully.
        val waited = elapsedNow - pending.scannedAtElapsedMs
        val note = if (waited > DW_INDUCTION_NOTE_THRESHOLD_MS) {
            dwInductionNote(pending, elapsedNow, wallNow)
        } else {
            null
        }

        try {
            val ack = api.requestDesignWorkshopAccess(
                DwWorkshopJoinBody(
                    workshopId = pending.workshopId,
                    scannedCode = pending.code,
                    note = note,
                )
            )
            // THE SERVER'S OWN SENTENCE, and a fallback only for the case where a proxy strips the
            // body. The fallback is deliberately the same shape — conditional, promising nothing.
            DwJoinOutcome.Asked(
                ack.detail?.takeIf { it.isNotBlank() }
                    ?: "If that workshop exists and you are not already on it, an administrator can " +
                    "now see that you have asked to join. Asking again will not send a second request."
            )
        } catch (error: HttpException) {
            // 422 IS THE ONE STATUS THAT MEANS SOMETHING SPECIFIC, and it is safe to show because it
            // is about the BODY: the code is damaged, or belongs to another kind of record, or names
            // a different workshop from the id beside it. None of that depends on which workshops
            // exist, so saying it out loud discloses nothing — the route's own comment says exactly
            // this. Every other status collapses into the queue, because a 500 or a 401 is not the
            // card's fault and re-scanning would not help.
            if (error.code() == 422) {
                DwJoinOutcome.Refused(
                    dwJoinBodyRefusal(error) ?: "That card was refused: the code does not match the " +
                        "workshop it is for. Read the code printed under the QR again, or ask for a " +
                        "fresh card."
                )
            } else {
                DwJoinOutcome.Queued(dwJoinQueuedMessage())
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            DwJoinOutcome.Queued(dwJoinQueuedMessage())
        }
    }

/** How long a scan must have waited before the evidence note is worth attaching. Ten seconds. */
private const val DW_INDUCTION_NOTE_THRESHOLD_MS = 10_000L

/**
 * The `detail` out of a FastAPI 422, or null.
 *
 * Read out of the error body rather than restated here, because the sentence is the service module's
 * and it names which of three things is wrong with the card. Reading the body consumes it, so this is
 * called once and its answer is used.
 */
private fun dwJoinBodyRefusal(error: HttpException): String? {
    return runCatching {
        val raw = error.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
        val element = raw?.let { ApiClient.json.parseToJsonElement(it) }
        val detail = (element as? JsonObject)?.get("detail")
        // FastAPI answers `detail` as a string for an HTTPException and as a LIST of objects for a
        // validation error. Only the first is a sentence written for a person; the second is a schema
        // complaint, and showing it to a designer in a courtyard would be worse than the generic line.
        (detail as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * Record the scan, then try to send it. The whole join path in one call.
 *
 * WRITTEN DOWN FIRST, ALWAYS, AND BEFORE THE NETWORK IS TOUCHED. The requirement is that an offline
 * scan is never discarded, and the way that guarantee gets lost is an implementation that tries the
 * request first and only queues on failure — because the failure that matters is the process being
 * killed mid-request in a courtyard, where there is no `catch` block to reach the queue.
 */
suspend fun dwJoinDesignWorkshop(context: Context, workshopId: String, code: String): DwJoinOutcome {
    val pending = DwInductionQueue.record(context, workshopId, code, kind = DW_INDUCTION_ASK)
    val outcome = dwSendJoinAsk(context, pending)
    // Cleared on both terminal answers. A 422 will never succeed, so keeping the row would retry a
    // card the server has already explained.
    if (outcome !is DwJoinOutcome.Queued) DwInductionQueue.clear(context, pending.queueKey)
    return outcome
}
