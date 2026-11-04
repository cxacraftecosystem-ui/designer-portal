package com.designprototype.workshop.data

import java.util.Locale

const val DW_RATE_WINDOW_MILLIS: Long = 5_000L

const val DW_RATE_MIN_WINDOW_MILLIS: Long = 1_000L

const val DW_RATE_STALL_MILLIS: Long = 10_000L

const val DW_RATE_ERRATIC_RATIO: Double = 4.0

enum class DwTransferPhase {
    FETCHING,

    COPYING,

    VERIFYING,

    UNPACKING,
}

fun dwTransferHeading(phase: DwTransferPhase): String = when (phase) {
    DwTransferPhase.FETCHING -> "Downloading"
    DwTransferPhase.COPYING -> "Copying onto this phone"
    DwTransferPhase.VERIFYING -> "Checking the fingerprint"
    DwTransferPhase.UNPACKING -> "Unpacking"
}

enum class DwRateStability {
    UNKNOWN,

    STEADY,

    ERRATIC,
}

data class DwTransferReadout(
    val receivedBytes: Long,

    val totalBytes: Long?,

    val percent: Int?,

    val bytesPerSecond: Double?,

    val secondsRemaining: Long?,

    val stability: DwRateStability,

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

class DwTransferMeter(
    val totalBytes: Long?,
    val resumedFromBytes: Long = 0L,
    private val windowMillis: Long = DW_RATE_WINDOW_MILLIS,
) {
    init {
        require(resumedFromBytes >= 0L) { "A resume offset is a byte count, never negative." }
        require(windowMillis > 0L) { "A rate window with no width would divide by zero." }
    }

    private val samples = ArrayDeque<Pair<Long, Long>>()

    private var lastBytes: Long = 0L
    private var lastAtMillis: Long? = null

    @Synchronized
    fun observe(movedThisAttempt: Long, atMillis: Long): DwTransferReadout {
        require(movedThisAttempt >= 0L) { "A byte count moved is never negative." }
        lastBytes = movedThisAttempt
        lastAtMillis = atMillis
        if (samples.isEmpty() && movedThisAttempt == 0L) return readAt(atMillis)
        samples.addLast(atMillis to movedThisAttempt)
        while (samples.size > 2 && atMillis - samples.first().first > windowMillis) {
            samples.removeFirst()
        }
        return readAt(atMillis)
    }

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

    private fun windowRate(atMillis: Long): Double? {
        if (samples.size < 2) return null
        val oldest = samples.first()
        val span = atMillis - oldest.first
        if (span < DW_RATE_MIN_WINDOW_MILLIS) return null
        val moved = lastBytes - oldest.second
        if (moved < 0L) return null
        return moved * 1000.0 / span
    }

    private fun halvesAgree(atMillis: Long): Boolean {
        if (samples.size < 3) return true
        val oldest = samples.first()
        val span = atMillis - oldest.first
        if (span < DW_RATE_MIN_WINDOW_MILLIS) return true
        val midpoint = oldest.first + span / 2
        val split = samples.lastOrNull { it.first <= midpoint } ?: return true
        val firstSpan = split.first - oldest.first
        val secondSpan = atMillis - split.first
        if (firstSpan <= 0L || secondSpan <= 0L) return true
        val firstRate = (split.second - oldest.second) * 1000.0 / firstSpan
        val secondRate = (lastBytes - split.second) * 1000.0 / secondSpan
        if (firstRate <= 0.0 && secondRate <= 0.0) return true
        if (firstRate <= 0.0 || secondRate <= 0.0) return false
        val ratio = if (firstRate > secondRate) firstRate / secondRate else secondRate / firstRate
        return ratio <= DW_RATE_ERRATIC_RATIO
    }
}

fun dwRateLabel(bytesPerSecond: Double?): String? {
    if (bytesPerSecond == null || bytesPerSecond < 0.0) return null
    val mb = 1000.0 * 1000.0
    return when {
        bytesPerSecond >= mb -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / mb)
        bytesPerSecond >= 1000.0 -> String.format(Locale.ROOT, "%.0f kB/s", bytesPerSecond / 1000.0)
        else -> String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond)
    }
}

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

fun dwStalledSentence(readout: DwTransferReadout, phase: DwTransferPhase): String? {
    if (!readout.stalled) return null
    return when (phase) {
        DwTransferPhase.FETCHING ->
            "Stalled. It resumes if the connection returns; pausing keeps what has arrived."
        else -> "This has been sitting still for a while."
    }
}

enum class DwResumeDecision {
    START_FRESH,

    RESUME_FROM_PARTIAL,

    DISCARD_AND_RESTART,
}

fun dwResumePlan(
    partialBytes: Long,
    totalBytes: Long?,
    serverAcceptsRanges: Boolean,
): DwResumeDecision = when {
    partialBytes <= 0L -> DwResumeDecision.START_FRESH
    totalBytes == null -> DwResumeDecision.DISCARD_AND_RESTART
    partialBytes >= totalBytes -> DwResumeDecision.DISCARD_AND_RESTART
    !serverAcceptsRanges -> DwResumeDecision.DISCARD_AND_RESTART
    else -> DwResumeDecision.RESUME_FROM_PARTIAL
}

fun dwRangeHonoured(statusCode: Int, contentRangeStart: Long?, askedFrom: Long): Boolean =
    statusCode == 206 && contentRangeStart != null && contentRangeStart == askedFrom

fun dwParseContentRangeStart(header: String?): Long? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    val spec = value.removePrefix("bytes ").removePrefix("bytes").trim().substringBefore('/')
    val start = spec.substringBefore('-').trim()
    return start.toLongOrNull()?.takeIf { it >= 0L }
}

fun dwPartialFileName(baseName: String): String = "$baseName.part"

fun dwIsPartialFileName(name: String): Boolean = name.endsWith(".part")

fun dwTransferSpaceRefusal(freeBytes: Long?, needBytes: Long): String? {
    if (freeBytes == null) {
        return "Free storage unknown, so nothing this size is fetched."
    }
    if (freeBytes >= needBytes) return null
    return "Not enough room: needs ${dwBytesLabel(needBytes)}, ${dwBytesLabel(freeBytes)} free."
}

const val DW_TRANSFER_DISK_FULL_SENTENCE: String =
    "Storage full. What arrived is kept — Free some space and tap Resume."

fun dwIsDiskFull(message: String?): Boolean {
    val text = message ?: return false
    return text.contains("ENOSPC", ignoreCase = true) ||
        text.contains("No space left on device", ignoreCase = true)
}

fun dwTransferDiskFullSentence(phase: DwTransferPhase): String = when (phase) {
    DwTransferPhase.FETCHING -> DW_TRANSFER_DISK_FULL_SENTENCE
    else -> "Storage full; nothing installed. Free some space and retry."
}

enum class DwTransferControlState {
    RUNNING,

    PAUSED,

    CANCELLED,
}

fun dwPauseLabel(state: DwTransferControlState): String = when (state) {
    DwTransferControlState.RUNNING -> "Pause"
    DwTransferControlState.PAUSED -> "Resume"
    DwTransferControlState.CANCELLED -> "Start again"
}

fun dwPausedSentence(keptBytes: Long): String =
    "${dwBytesLabel(keptBytes)} kept on this phone. Resume to continue; Cancel deletes it."
