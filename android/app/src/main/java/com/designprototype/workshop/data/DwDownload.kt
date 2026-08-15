package com.designprototype.workshop.data

import java.util.Locale

/**
 * **ONE TRANSFER READOUT, FOR EVERY MODEL THIS APP WILL EVER PUT ON A PHONE.**
 *
 * ── THE SENTENCE THIS FILE EXISTS TO ANSWER ───────────────────────────────────────────────────
 *
 * *"the loading bar should show speed, expected duration, and how much of it has been downloaded"*
 *
 * Before this file there were two `LinearProgressIndicator`s — one in `DwAsrModelInstallUi.kt`, one in
 * `DwAsrRuntimeUi.kt` — each drawing a bare bar with **no text of any kind**. The percentage was
 * computed at the call site and thrown away. Every number the owner asked for was already in scope:
 * bytes written, bytes pinned, and a clock.
 *
 * ── WHY ONE COMPONENT AND NOT TWO ─────────────────────────────────────────────────────────────
 *
 * Speech models today, language models next. Two implementations diverge, and the one that diverges is
 * the one nobody looked at — this repository has already shipped that failure with two accounts of one
 * language pack. So the meter, the smoothing, the ETA, the wording and the resume decision live here
 * once, and both the network route and the cable route drive the same object.
 *
 * **"NEXT" ARRIVED ON 2026-08-13**: [DW_TIER2_CATALOGUE] holds two weighed rows, and
 * `data/DwTier2Models.kt` says in as many words that a Tier 2 fetch uses this meter rather than growing
 * a second one. Nothing fetches them yet — no runtime in this build can open one — so that reuse is a
 * stated contract rather than a live call path.
 *
 * ── WHY THE RATE IS A WINDOW AND NOT TOTAL-OVER-ELAPSED ───────────────────────────────────────
 *
 * **This is the one decision in the file worth arguing, and the brief asked for the reason in a
 * comment.** Total-over-elapsed is what every naive progress readout prints, and on the connection
 * this app actually runs on it lies in a specific and damaging direction: a district-town link hands
 * you 20 MB in the first ten seconds off a warm cache and then stalls at 40 kB/s for four minutes.
 * Total-over-elapsed still reads **2 MB/s** a minute into the stall, and the ETA computed from it
 * says "about 2 min left" while the true answer is an hour. The designer plans around the number,
 * puts the phone in their pocket, and walks to the next village.
 *
 * A SLIDING WINDOW OVER THE LAST [DW_RATE_WINDOW_MILLIS] cannot do that: it forgets the fast start.
 * Five seconds is the window because it is long enough to ride out one TCP congestion event on a
 * bad link (which is where the byte counter pauses for a second or two) and short enough that a real
 * stall shows up in the readout while the designer is still looking at it.
 *
 * ── AND WHEN THE RATE IS TOO UNSTABLE TO PROJECT, IT SAYS SO RATHER THAN PRINTING A WILD NUMBER ──
 *
 * A window whose first half and second half disagree by more than [DW_RATE_ERRATIC_RATIO] is a
 * connection that is not doing anything an ETA could describe. Multiplying a remaining byte count by
 * a rate that is swinging 10× would print "about 14 min left" and then "about 40 sec left" two
 * seconds later, which is worse than no figure: a number that jumps is one a designer learns to
 * disbelieve, and they then disbelieve the honest one too. [DwRateStability.ERRATIC] prints the
 * speed (which is measured) and withholds the ETA (which is not).
 *
 * ── WHY IT IS PURE ────────────────────────────────────────────────────────────────────────────
 *
 * Plain Kotlin over longs and doubles: no Context, no okhttp, no `java.io`, no Compose, and no
 * `System.currentTimeMillis()` — **every function takes the clock as an argument**, which is what
 * lets `DwDownloadTest` drive a stalled connection, a sub-second download, a server that never sent
 * a length and a zero-byte start on a desktop JVM in microseconds. The platform half is one HTTP GET
 * with a `Range` header and one file copy, and it decides nothing.
 *
 * ── PURE IS NOT THE SAME AS SINGLE-THREADED, AND THE METER IS ASKED FROM TWO THREADS ──────────
 *
 * [DwTransferMeter] is fed from the IO dispatcher (the write loop, every 250 ms) and read from the
 * main thread (the surface's once-a-second tick, so a stall becomes visible at all). Those are
 * genuinely two threads on one `ArrayDeque`, so its two public methods are synchronised — see the
 * note on [DwTransferMeter.observe]. Everything else here is a function of its arguments and needs
 * no such care.
 */

// ---------------------------------------------------------------------------------------------
// The window, and the two thresholds that decide whether a number is worth printing
// ---------------------------------------------------------------------------------------------

/**
 * How far back the rate looks. **Five seconds — see the file header for why not "since the start".**
 *
 * Not a tuning knob to be nudged: shortening it makes the readout jitter on an ordinary link (every
 * TCP pause becomes a stall), and lengthening it re-introduces exactly the fast-start optimism this
 * whole file exists to refuse.
 */
const val DW_RATE_WINDOW_MILLIS: Long = 5_000L

/**
 * The shortest window that may produce a rate at all.
 *
 * Below a second, one 64 KiB buffer landing early reads as 64 MB/s. A download that finishes inside
 * this window therefore never prints a speed, which is correct: it took no measurable time, and
 * "measuring…" for the one frame it was on screen is the honest thing to have said.
 */
const val DW_RATE_MIN_WINDOW_MILLIS: Long = 1_000L

/**
 * No bytes for this long, with the transfer still open, and the readout says **stalled**.
 *
 * Ten seconds rather than three: a mobile link that pauses for two seconds is ordinary and saying
 * "stalled" every time it happened would make the word meaningless by the time it was true.
 */
const val DW_RATE_STALL_MILLIS: Long = 10_000L

/**
 * How far the two halves of the window may disagree before the ETA is withheld.
 *
 * Four, which is generous on purpose. An ETA is worth having and the bar for suppressing it should
 * be "this connection is not describable", not "this connection varies".
 */
const val DW_RATE_ERRATIC_RATIO: Double = 4.0

// ---------------------------------------------------------------------------------------------
// What is being moved, which changes the wording and nothing else
// ---------------------------------------------------------------------------------------------

/**
 * Which of the four measurable stages a transfer is in.
 *
 * **ALL FOUR ARE MEASURABLE IN BYTES**, which is the entry requirement for being in this enum at all.
 * The decode of a CTC speech model is deliberately absent: it reports no fraction of itself completed,
 * so it gets elapsed time and a cancel button and **no bar** — see `DwOnDeviceModelPanel`. Adding a
 * value here for it would be the invitation to draw one.
 *
 * **AND SINCE 2026-08-13 ALL FOUR ARE ACTUALLY MEASURED**, which the entry requirement had not made
 * true on its own. [VERIFYING] and [UNPACKING] used to be drawn from whichever meter [FETCHING] or
 * [COPYING] had left behind, so the card reported a download's speed and a full bar for a hash that
 * had not read a byte — and would have called a phone hashing at 300 MB/s *stalled* had the hash taken
 * ten seconds. Each phase now opens its own meter over its own length; the two that cannot suspend to
 * publish feed a counter that the surface's tick samples. See `DwAsrModelController.tick`.
 */
enum class DwTransferPhase {
    /** Bytes over a wire, off somebody's data bundle. The only phase that spends money. */
    FETCHING,

    /** Bytes between two directories on this phone. Costs nothing but time, and the time is real. */
    COPYING,

    /** Reading the assembled file back to hash it. 365 MB of reading, so it is worth a readout. */
    VERIFYING,

    /** Reading entries out of a container into `filesDir`. */
    UNPACKING,
}

/** The heading above the readout. One phase, one wording, wherever it is drawn. */
fun dwTransferHeading(phase: DwTransferPhase): String = when (phase) {
    DwTransferPhase.FETCHING -> "Downloading"
    DwTransferPhase.COPYING -> "Copying onto this phone"
    DwTransferPhase.VERIFYING -> "Checking the fingerprint"
    DwTransferPhase.UNPACKING -> "Unpacking"
}

// ---------------------------------------------------------------------------------------------
// How steady the connection is — the question that decides whether an ETA may be printed
// ---------------------------------------------------------------------------------------------

/** Whether the recent rate is steady enough to multiply a remaining byte count by. */
enum class DwRateStability {
    /** Not enough of a window yet. Neither a speed nor an ETA is printed. */
    UNKNOWN,

    /** The window's two halves agree within [DW_RATE_ERRATIC_RATIO]. An ETA may be projected. */
    STEADY,

    /** They do not. The speed is printed (it was measured); the ETA is not (it was not). */
    ERRATIC,
}

// ---------------------------------------------------------------------------------------------
// One reading of a transfer in flight
// ---------------------------------------------------------------------------------------------

/**
 * **EVERYTHING THE OWNER ASKED FOR, AS NUMBERS, WITH NULL MEANING "NOT MEASURED YET".**
 *
 * Every nullable field in here is nullable because the honest answer is genuinely sometimes "we do not
 * know", and this repository's whole rule is that a guess in that slot is worse than a blank. A
 * [totalBytes] the server never sent must not become a percentage; a [bytesPerSecond] taken over
 * 200 ms must not become an ETA.
 */
data class DwTransferReadout(
    /** Bytes on disk right now, **including anything a resumed attempt started from.** */
    val receivedBytes: Long,

    /** What the whole file is, when that is known. Null when the server sent no length. */
    val totalBytes: Long?,

    /** 0–100. Null exactly when [totalBytes] is. */
    val percent: Int?,

    /** The smoothed recent rate. Null until [DW_RATE_MIN_WINDOW_MILLIS] of window exists. */
    val bytesPerSecond: Double?,

    /** Seconds left at the current rate. Null when it cannot be projected honestly. */
    val secondsRemaining: Long?,

    val stability: DwRateStability,

    /** No bytes for [DW_RATE_STALL_MILLIS]. Said in the readout, because it is what is happening. */
    val stalled: Boolean,
) {
    init {
        require(receivedBytes >= 0L) {
            "A transfer cannot have received a negative number of bytes. This came from arithmetic " +
                "on a resume offset that was larger than the file — see dwResumePlan, which refuses " +
                "exactly that case rather than letting it reach here."
        }
        require(totalBytes == null || totalBytes >= 0L) {
            "A total is a real length or it is unknown. A negative one came from a Content-Length " +
                "header nobody validated; pass null, and the readout prints bytes without a bar."
        }
        require(percent == null || percent in 0..100) {
            "Progress is a percentage or nothing at all. A figure outside 0–100 means the received " +
                "count passed the total, which is the over-serving host dwTransferMeter's cap exists " +
                "to stop — pass null rather than clamping silently."
        }
    }
}

/**
 * **THE METER. Feed it a byte count and a clock; it answers with every number the readout prints.**
 *
 * ── WHY IT IS A MUTABLE OBJECT AND NOT A PURE FUNCTION ────────────────────────────────────────
 *
 * Because a sliding window is by definition a memory of the last few seconds, and the alternative —
 * handing the whole sample history in on every call — would make the caller keep it instead, which is
 * the same state in a place with no tests around it. It is still pure in the sense that matters: no
 * clock of its own, no IO, no Android, and every answer is a function of what it has been told.
 *
 * ── IT COUNTS FROM THE TOP OF THE FILE, NOT FROM THE TOP OF THIS ATTEMPT ──────────────────────
 *
 * [resumedFromBytes] is what was already on disk when this attempt opened. The RATE is measured over
 * bytes this attempt moved (a resume that began at 200 MB did not move 200 MB in its first
 * millisecond), while the PERCENTAGE and the ETA are about the whole file — because that is the thing
 * the designer is waiting for. Conflating those two is how a resumed download shows "100%" the moment
 * it starts.
 */
class DwTransferMeter(
    /** The whole file's length, or null when the server never said. */
    val totalBytes: Long?,
    /** Bytes already on disk when this attempt began. Zero for a fresh start. */
    val resumedFromBytes: Long = 0L,
    /** How far back the rate looks. Overridable only so tests can drive a shorter one. */
    private val windowMillis: Long = DW_RATE_WINDOW_MILLIS,
) {
    init {
        require(resumedFromBytes >= 0L) { "A resume offset is a byte count, never negative." }
        require(windowMillis > 0L) { "A rate window with no width would divide by zero." }
    }

    /**
     * (clock, bytes-this-attempt-has-moved) pairs inside the window, oldest first.
     *
     * An `ArrayDeque` because the only two operations are "drop from the front once it is out of the
     * window" and "add to the back", and a list would make the drop O(n) on every 64 KiB buffer.
     */
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** The last count observed, so a stall can be told from a transfer that simply has not been asked. */
    private var lastBytes: Long = 0L
    private var lastAtMillis: Long? = null

    /**
     * Record a reading and answer with the whole readout.
     *
     * [movedThisAttempt] is bytes THIS attempt has written — not the file's length on disk. The
     * distinction only matters on a resume, and it matters entirely: see the class doc.
     *
     * ── SYNCHRONISED, BECAUSE THE TWO CALLERS ARE ON TWO THREADS ──────────────────────────────
     *
     * **This was a live data race and not a theoretical one.** The write loop calls this from
     * `Dispatchers.IO` every [DW_PROGRESS_MIN_INTERVAL_MS]; the surface calls [readAt] from the main
     * thread once a second, because that is the only way a stall becomes visible. Both walk
     * [samples], and this one mutates it — `addLast` then `removeFirst`. `kotlin.collections.ArrayDeque`
     * is not thread-safe: `removeFirst` nulls the head slot before advancing the head index, so a
     * main-thread `samples.first()` interleaved with it can read that null and throw inside
     * `windowRate` — a crash on the main thread, during a download, in the one code path where
     * losing the readout also loses the Pause button. The lock is held for a handful of arithmetic
     * on at most a five-second window and is taken at most five times a second.
     */
    @Synchronized
    fun observe(movedThisAttempt: Long, atMillis: Long): DwTransferReadout {
        require(movedThisAttempt >= 0L) { "A byte count moved is never negative." }
        lastBytes = movedThisAttempt
        lastAtMillis = atMillis
        samples.addLast(atMillis to movedThisAttempt)
        // Anything older than the window is no longer evidence about the connection as it is now.
        while (samples.size > 2 && atMillis - samples.first().first > windowMillis) {
            samples.removeFirst()
        }
        return readAt(atMillis)
    }

    /**
     * The readout as of [atMillis] **without recording a sample**, which is what makes a stall
     * visible at all.
     *
     * A stalled connection stops calling [observe] by definition — no bytes arrive, so nothing is
     * written and nothing is reported. The surface therefore asks the meter on its own clock, and this
     * is the method it asks with. Sampling here instead would fabricate a data point saying the byte
     * count is still climbing.
     *
     * Synchronised on the same lock as [observe], and for the reason written there: this is the
     * main-thread half of the race. [observe] calls it while already holding the lock, which is
     * re-entrant.
     */
    @Synchronized
    fun readAt(atMillis: Long): DwTransferReadout {
        val onDisk = resumedFromBytes + lastBytes
        val total = totalBytes
        val percent = total?.let {
            if (it <= 0L) 0 else ((onDisk.toDouble() / it) * 100.0).toInt().coerceIn(0, 100)
        }
        val stalled = lastAtMillis?.let { atMillis - it >= DW_RATE_STALL_MILLIS } ?: false

        val rate = windowRate(atMillis)
        val stability = when {
            rate == null -> DwRateStability.UNKNOWN
            halvesAgree(atMillis) -> DwRateStability.STEADY
            else -> DwRateStability.ERRATIC
        }
        // AN ETA IS THE ONE FIGURE WITH FOUR PRECONDITIONS, and every one of them has a failure
        // behind it: no total means no remainder to divide, no rate means nothing to divide by, a
        // stall means the rate is a memory rather than a measurement, and ERRATIC means the number
        // would swing far enough to be disbelieved. Any of them and the surface says so in words.
        val remaining = if (total != null) (total - onDisk).coerceAtLeast(0L) else null
        val eta = if (
            remaining != null && rate != null && rate > 0.0 &&
            !stalled && stability == DwRateStability.STEADY
        ) {
            kotlin.math.ceil(remaining / rate).toLong()
        } else {
            null
        }
        return DwTransferReadout(
            receivedBytes = onDisk,
            totalBytes = total,
            percent = percent,
            bytesPerSecond = rate,
            secondsRemaining = eta,
            stability = stability,
            stalled = stalled,
        )
    }

    /** Bytes per second across the whole window, or null while there is not enough of one. */
    private fun windowRate(atMillis: Long): Double? {
        if (samples.size < 2) return null
        val oldest = samples.first()
        // The newest point is the CALLER'S clock, not the last sample's: a connection that has been
        // silent for four seconds has a genuinely lower recent rate than one that just delivered, and
        // measuring only between samples would hide that until the next byte arrived.
        val span = atMillis - oldest.first
        if (span < DW_RATE_MIN_WINDOW_MILLIS) return null
        val moved = lastBytes - oldest.second
        if (moved < 0L) return null
        return moved * 1000.0 / span
    }

    /**
     * Whether the window's older half and newer half describe the same connection.
     *
     * Zero in either half is not automatically erratic: a download that has genuinely finished its
     * work, or one that has not started moving, is steady-at-zero and the STALL check above is what
     * speaks for it. What this refuses is a window where one half is many times the other.
     */
    private fun halvesAgree(atMillis: Long): Boolean {
        if (samples.size < 3) return true
        val oldest = samples.first()
        val span = atMillis - oldest.first
        if (span < DW_RATE_MIN_WINDOW_MILLIS) return true
        val midpoint = oldest.first + span / 2
        // The last sample at or before the midpoint splits the window.
        val split = samples.lastOrNull { it.first <= midpoint } ?: return true
        val firstSpan = split.first - oldest.first
        val secondSpan = atMillis - split.first
        if (firstSpan <= 0L || secondSpan <= 0L) return true
        val firstRate = (split.second - oldest.second) * 1000.0 / firstSpan
        val secondRate = (lastBytes - split.second) * 1000.0 / secondSpan
        if (firstRate <= 0.0 && secondRate <= 0.0) return true
        // One half at a standstill and the other moving IS the erratic case — it is exactly the
        // fast-start-then-stall this file exists to refuse to project through.
        if (firstRate <= 0.0 || secondRate <= 0.0) return false
        val ratio = if (firstRate > secondRate) firstRate / secondRate else secondRate / firstRate
        return ratio <= DW_RATE_ERRATIC_RATIO
    }
}

// ---------------------------------------------------------------------------------------------
// The words. One readout, one wording, so the download card and the copy card cannot drift apart
// ---------------------------------------------------------------------------------------------

/**
 * A transfer rate in the units a person uses. **Null in, null out — never "0 MB/s" for "unmeasured".**
 *
 * Decimal MB to match [dwBytesLabel], which the rest of this app already prints sizes in. Mixing
 * MiB in a speed beside MB in a size would make a 365 MB file at "2.4 MiB/s" arrive at a time that
 * does not divide.
 */
fun dwRateLabel(bytesPerSecond: Double?): String? {
    if (bytesPerSecond == null || bytesPerSecond < 0.0) return null
    val mb = 1000.0 * 1000.0
    return when {
        bytesPerSecond >= mb -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / mb)
        bytesPerSecond >= 1000.0 -> String.format(Locale.ROOT, "%.0f kB/s", bytesPerSecond / 1000.0)
        // Under a kilobyte a second, rounded to kB/s, reads "0 kB/s" — which looks like a bug rather
        // than like the crawling connection it actually is. Say the bytes.
        else -> String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond)
    }
}

/**
 * How long is left, in minutes, **rounded the way a person waiting would round it.**
 *
 * Deliberately coarse. A "2 min 14 sec left" that recomputes every 250 ms is a number nobody can read
 * and everybody watches; minutes change slowly enough to be believed. Under a minute is said as
 * such rather than counted down, because the last ten seconds of a download do not need a clock.
 */
fun dwEtaLabel(secondsRemaining: Long?): String? {
    if (secondsRemaining == null || secondsRemaining < 0L) return null
    val minutes = secondsRemaining / 60
    return when {
        secondsRemaining < 60L -> "under a minute left"
        minutes < 60L -> "about $minutes min left"
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0L) "about $hours hr left" else "about $hours hr $rest min left"
        }
    }
}

/**
 * **THE LINE THE OWNER ASKED FOR.** `184 MB of 349 MB · 53% · 2.4 MB/s · about 1 min left`
 *
 * Every clause is dropped rather than faked when the number behind it is not known, which is why
 * this is a `buildList` and not a format string. The four states a test has to pin, and each of them
 * is a real thing that happens on a district-town connection:
 *
 *  * **nothing yet** — `0 MB of 349 MB · 0% · measuring…`
 *  * **no total from the server** — `184 MB · 2.4 MB/s` and no percentage, because there is no
 *    denominator and inventing one is the one thing this app does not do;
 *  * **stalled** — `184 MB of 349 MB · 53% · stalled` and no speed, because the speed is zero and
 *    "0 B/s" reads as a broken readout rather than as a stopped connection;
 *  * **erratic** — the speed, and `time left is not steady enough to guess` where the ETA would be.
 */
fun dwTransferLine(readout: DwTransferReadout): String {
    val parts = buildList {
        add(
            if (readout.totalBytes != null) {
                "${dwBytesLabel(readout.receivedBytes)} of ${dwBytesLabel(readout.totalBytes)}"
            } else {
                dwBytesLabel(readout.receivedBytes)
            }
        )
        readout.percent?.let { add("$it%") }
        when {
            // Said INSTEAD of a speed, not beside it. A stalled connection's window rate is a true
            // zero and printing "0 B/s · stalled" says one thing twice.
            readout.stalled -> add("stalled")
            readout.bytesPerSecond != null -> dwRateLabel(readout.bytesPerSecond)?.let { add(it) }
            else -> add("measuring…")
        }
        when {
            readout.stalled -> Unit
            readout.secondsRemaining != null -> dwEtaLabel(readout.secondsRemaining)?.let { add(it) }
            readout.stability == DwRateStability.ERRATIC ->
                add("time left is not steady enough to guess")
            else -> Unit
        }
    }
    return parts.joinToString(" · ")
}

/**
 * What to say under the line when a transfer has been sitting still.
 *
 * Its own sentence rather than a longer readout, because the readout is a row of numbers and this is
 * an instruction. Null whenever nothing is wrong.
 */
fun dwStalledSentence(readout: DwTransferReadout, phase: DwTransferPhase): String? {
    if (!readout.stalled) return null
    return when (phase) {
        DwTransferPhase.FETCHING ->
            "Nothing has arrived for a while. It will carry on if the connection comes back; " +
                "pausing keeps what has arrived so far."
        else -> "This has been sitting still for a while."
    }
}

// ---------------------------------------------------------------------------------------------
// Resuming, which is the difference between 292 MB and 292 MB again
// ---------------------------------------------------------------------------------------------

/**
 * What to do about bytes already on disk from an attempt that did not finish.
 *
 * **THE STATE ON DISK IS THE ONLY INPUT THAT MATTERS, AND EVERY ANSWER LEAVES IT REASONABLE.** A
 * partial is kept at a path that cannot be mistaken for a finished file (see [dwPartialFileName]) so
 * that this decision is the only thing that ever promotes one.
 */
enum class DwResumeDecision {
    /** Nothing usable on disk. Ask for the whole file. */
    START_FRESH,

    /** A usable prefix. Ask for `Range: bytes=<partial>-`. */
    RESUME_FROM_PARTIAL,

    /**
     * Something is there and it cannot be resumed from. **Delete it and start over.**
     *
     * Three ways in, and they are one answer because the designer's next move is identical: the host
     * will not honour a range, the partial is already at or past the full length (so it is not a
     * prefix of anything this app can reason about), or no length is known so there is no way to tell
     * a prefix from a whole file.
     */
    DISCARD_AND_RESTART,
}

/**
 * Decide once, before a byte moves. **Pure, and the reason each arm exists is a real failure.**
 *
 * @param partialBytes what is sitting in the part-file, or 0 when there is none.
 * @param totalBytes the pinned length of the whole file. This app always knows it — the artifact
 *   constructor requires it — so a null here is a caller that has lost it, and the answer is to
 *   restart rather than to resume into an unknown.
 * @param serverAcceptsRanges whether the host advertised `Accept-Ranges: bytes`. **If it did not,
 *   this returns [DISCARD_AND_RESTART] and the surface must not use the word "resuming"** — a client
 *   that says "resuming" and then silently refetches 292 MB has spent somebody's bundle and lied
 *   about it.
 */
fun dwResumePlan(
    partialBytes: Long,
    totalBytes: Long?,
    serverAcceptsRanges: Boolean,
): DwResumeDecision = when {
    partialBytes <= 0L -> DwResumeDecision.START_FRESH
    totalBytes == null -> DwResumeDecision.DISCARD_AND_RESTART
    // At or past the pinned length and yet not installed: the digest was never taken or it failed.
    // There is nothing to resume TO, and keeping it would let the next attempt append past the end.
    partialBytes >= totalBytes -> DwResumeDecision.DISCARD_AND_RESTART
    !serverAcceptsRanges -> DwResumeDecision.DISCARD_AND_RESTART
    else -> DwResumeDecision.RESUME_FROM_PARTIAL
}

/**
 * **DID THE SERVER ACTUALLY HONOUR THE RANGE WE ASKED FOR?** Checked on the response, every time.
 *
 * A host that ignores `Range` answers **200 with the whole file**, not an error. Appending that to a
 * 200 MB partial produces a 492 MB file that fails its digest an hour later, having spent the entire
 * bundle. So the response is inspected before a byte is written: only a **206** whose `Content-Range`
 * starts exactly where we asked is a resume. Anything else is a fresh start, and the caller must
 * truncate the part-file to zero before writing.
 *
 * @param contentRangeStart the first byte offset parsed out of `Content-Range: bytes <start>-…`, or
 *   null when the header is absent or unparseable.
 */
fun dwRangeHonoured(statusCode: Int, contentRangeStart: Long?, askedFrom: Long): Boolean =
    statusCode == 206 && contentRangeStart != null && contentRangeStart == askedFrom

/**
 * The first byte offset out of a `Content-Range: bytes 200-1000/1001` header. Null when it says
 * anything else at all — including `bytes * /1001`, which is a 416's way of saying the range was
 * unsatisfiable and must never be read as an offset of zero.
 */
fun dwParseContentRangeStart(header: String?): Long? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    val spec = value.removePrefix("bytes ").removePrefix("bytes").trim().substringBefore('/')
    val start = spec.substringBefore('-').trim()
    return start.toLongOrNull()?.takeIf { it >= 0L }
}

/**
 * What the partial is called on disk. **Deliberately not the finished file's name.**
 *
 * The brief's words: *"a partial kept where it cannot be mistaken for a finished file"*. The suffix is
 * what enforces it — [dwAsrReadInstalledModel] only ever looks for the pinned names, so a part-file
 * cannot be picked up as an installed one no matter how large it grows, and the sweep that clears
 * abandoned downloads can tell "a prefix worth keeping" from "a leftover worth deleting" by name
 * alone rather than by guessing at a length.
 */
fun dwPartialFileName(baseName: String): String = "$baseName.part"

/** True for a name [dwPartialFileName] produced. The sweep's only test. */
fun dwIsPartialFileName(name: String): Boolean = name.endsWith(".part")

// ---------------------------------------------------------------------------------------------
// Room on the phone, asked before the first byte and answered again when it runs out
// ---------------------------------------------------------------------------------------------

/**
 * Whether there is room, in one sentence, or null when there is. **Asked before a byte moves.**
 *
 * The margin is [DW_MODEL_FREE_STORAGE_MARGIN_BYTES] — a workshop day of photographs and audio — for
 * the reason [dwAsrModelStorageNeededBytes] gives: a phone filled to the last megabyte by a speech
 * model is a phone that cannot record the workshop the model was installed for.
 */
fun dwTransferSpaceRefusal(freeBytes: Long?, needBytes: Long): String? {
    if (freeBytes == null) {
        return "This phone would not say how much storage is free, so whether this fits is unknown " +
            "and nothing this size is fetched on a guess."
    }
    if (freeBytes >= needBytes) return null
    return "There is not enough room: this needs ${dwBytesLabel(needBytes)} free and this phone " +
        "reports ${dwBytesLabel(freeBytes)}. Delete what you can spare and try again."
}

/**
 * Said when the volume filled up **while bytes were arriving**, which is a different moment.
 *
 * The check above passed and the phone still ran out — a workshop's photographs landed during the
 * hour the download was running, which on a shared handset is ordinary. It names what was kept,
 * because on a resumable transfer that is the useful half: the part-file survives, so the answer is
 * "free some space and carry on" rather than "start the 292 MB again".
 */
const val DW_TRANSFER_DISK_FULL_SENTENCE: String =
    "This phone ran out of storage while the file was arriving. What had already arrived has been " +
        "kept, so freeing some space and tapping Resume carries on from where it stopped rather " +
        "than starting again."

/**
 * Whether a write failure was **the volume filling up**, off the message the platform gave us.
 *
 * ── WHY A STRING TEST AND NOT AN EXCEPTION TYPE ───────────────────────────────────────────────
 *
 * There is no `DiskFullException` on Android. A full volume surfaces as a plain [java.io.IOException]
 * from `write`, and the only thing distinguishing it from a broken pipe or a revoked permission is the
 * `errno` name the runtime puts in the message: `write failed: ENOSPC (No space left on device)`.
 * `android.system.ErrnoException` carries the number, but by the time `FileOutputStream.write` has
 * wrapped it the caller sees an `IOException` whose cause may or may not still be one — so the message
 * is what is actually available at the only place the decision has to be made.
 *
 * Both spellings are matched because both are seen: the bionic `errno` name and the human clause,
 * which is what some ART builds and every wrapped rethrow carry instead.
 *
 * **THE CONSEQUENCE OF GETTING THIS WRONG IS ONLY THE WORDING**, never the cleanup: the caller keeps
 * or deletes the part-file on the designer's stated intent, not on this. A false negative prints the
 * raw platform text, which is what shipped before this existed; a false positive tells somebody to
 * free space they did not need to free. Neither loses bytes.
 */
fun dwIsDiskFull(message: String?): Boolean {
    val text = message ?: return false
    return text.contains("ENOSPC", ignoreCase = true) ||
        text.contains("No space left on device", ignoreCase = true)
}

/**
 * What to say when the volume filled up **mid-transfer**, which differs by phase in one way that
 * matters: whether there is anything to resume.
 *
 * A FETCH keeps its part-file, so the instruction is "free some space and tap Resume" and the bytes
 * already paid for are not spent again. A COPY or an UNPACK has nothing to resume — the source is
 * still on this phone and restarting costs seconds — so promising a resume there would be offering a
 * button that does not exist.
 */
fun dwTransferDiskFullSentence(phase: DwTransferPhase): String = when (phase) {
    DwTransferPhase.FETCHING -> DW_TRANSFER_DISK_FULL_SENTENCE
    else -> "This phone ran out of storage part-way through, so nothing has been installed and " +
        "nothing has been kept. Free some space and try again."
}

// ---------------------------------------------------------------------------------------------
// Pause, resume, cancel — three controls, and what each one leaves behind
// ---------------------------------------------------------------------------------------------

/**
 * What a transfer is doing, from the point of view of the person holding the phone.
 *
 * **CANCELLED IS NOT PAUSED, AND THE DIFFERENCE IS 292 MB.** Pause keeps the part-file so the next
 * attempt resumes; cancel deletes it and gives the space back. A single "stop" button would make a
 * designer guess which one they were pressing, and the cost of guessing wrong is either an hour of
 * downloading or a phone with a third of a gigabyte in it that nothing will ever open.
 */
enum class DwTransferControlState {
    /** Bytes are moving. */
    RUNNING,

    /** Stopped by the designer, part-file kept, resumable. */
    PAUSED,

    /** Stopped by the designer, part-file deleted, space reclaimed. */
    CANCELLED,
}

/** The button words for each control, so no surface invents its own. */
fun dwPauseLabel(state: DwTransferControlState): String = when (state) {
    DwTransferControlState.RUNNING -> "Pause"
    DwTransferControlState.PAUSED -> "Resume"
    DwTransferControlState.CANCELLED -> "Start again"
}

/**
 * What a pause left behind, **named in bytes**, so the two buttons under it are not a guess.
 *
 * Takes the byte count rather than a [DwTransferReadout] because the moment it is most needed is the
 * one where no meter exists: the app was reopened, the part-file was found on disk by
 * [dwResumePlan]'s caller, and there is nothing in memory but a length.
 */
fun dwPausedSentence(keptBytes: Long): String =
    "${dwBytesLabel(keptBytes)} kept on this phone. Resume carries on from there; Cancel deletes " +
        "it and gives the space back."
