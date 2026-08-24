package com.designprototype.workshop.data

/**
 * WHAT THE RECORDS QUEUE IS ALLOWED TO SAY ABOUT ITSELF.
 *
 * ── THE DEFECT THIS FILE ENDS, WHICH WAS ALREADY FIXED ONCE, NEXT DOOR ────────────────────────
 *
 * The offline outbox drew ONE number under a cloud-off icon and ONE sentence:
 *
 *     "3 entries saved on this device — uploading when you're online."
 *
 * [OfflineOutbox.count] counted every row in the queue, refusals included, and `syncOutbox` steps
 * over a refused entry for ever. So a record the server has permanently rejected — a duplicate
 * artisan, a field the API will not take, a permission this account does not have — sat inside a
 * sentence promising it was on its way, and stayed there for the rest of the installation's life.
 * The words for what that costs are already in `WorkshopRepository.notifyUser`'s KDoc: it "is a lie
 * for an entry the server has refused for good, and which stays a lie for ever". The fix applied at
 * the time was a Toast, and a Toast in a courtyard lasts five seconds — and by design fires only
 * when the REASON changes, so on the second app open it says nothing at all.
 *
 * The design-workshop half of this app solved the identical problem properly and did it first. See
 * `dwDeviceSyncBanner` in `data/WorkshopSync.kt`: it computes a `waiting` boolean that is FALSE when
 * the only outstanding items are ones a connection will not move, gates the cloud-off icon on it,
 * and gives every other class of outstanding work its own sentence naming its own remedy. That was
 * written against a measured failure on an SM-M325F. This file is that treatment applied to the
 * records queue, so that one app does not hold two different ideas of what "saved on this device"
 * means depending on which screen you are looking at.
 *
 * ── WHY IT IS PURE ────────────────────────────────────────────────────────────────────────────
 *
 * No Context, no Compose, no clock, no IO — the same discipline `dwDeviceSyncBanner` keeps and for
 * the same reason. Every sentence a designer with no signal will read is decided here and pinned by
 * `OutboxBannerTest`, rather than being assembled inside a composable where the only way to check it
 * is to take a handset into a village and turn aeroplane mode on.
 */

/**
 * The queue, split by what a connection can do about it.
 *
 * Read in ONE pass under ONE lock (see [OfflineOutbox.counts]), because two separate reads can land
 * either side of a sync that emptied the queue and the banner would then draw a total that never
 * existed on the device at any moment.
 */
data class OutboxCounts(
    /** Entries a working connection WILL send. */
    val waiting: Int,
    /** Entries the server has refused for good. A sync pass will not touch these again. */
    val refused: Int,
) {
    val isEmpty: Boolean get() = waiting == 0 && refused == 0
}

/** One line of the banner, and whether it is the amber one. */
data class OutboxBannerLine(val text: String, val warn: Boolean)

/**
 * What to draw, or null for "draw nothing".
 *
 * @property showCloudOff gate for the cloud-off icon. FALSE when nothing outstanding is waiting on
 *   the network — a refusal drawn under a cloud icon tells the designer to go and find a signal,
 *   which is the one thing that will not help.
 * @property actionable there is something a PERSON can do, so the banner is worth being tappable.
 *   Driven by [OutboxCounts.refused] alone: an entry that is merely waiting needs nobody.
 */
data class OutboxBanner(
    val showCloudOff: Boolean,
    val actionable: Boolean,
    val lines: List<OutboxBannerLine>,
)

/** "1 entry" / "4 entries", so no call site has to spell the plural itself. */
internal fun outboxEntryCount(n: Int): String = if (n == 1) "1 entry" else "$n entries"

/**
 * The banner for a queue holding [counts], on a device that is [online] or not.
 *
 * WHY THE CONNECTION STATE IS AN INPUT. "Uploading when you're online" was said to a designer whose
 * phone had four bars and whose queue was not moving, because the entries in it had been refused.
 * The waiting line now states which of the two situations the phone is actually in, so a queue that
 * is NOT draining on a working connection reads as something to look at rather than as a promise.
 *
 * WHY THE REFUSAL LINE NAMES THE REMEDY AND EXPLICITLY DENIES THE OTHER ONE. `dwDeviceSyncBanner`
 * learned this and says so in words — "a sync will NOT move them" — because a designer whose work is
 * not arriving walks to the top of the hill to find a signal, and does it again the next day.
 * Telling them the walk is pointless is the only useful sentence available.
 */
fun outboxDeviceBanner(counts: OutboxCounts, online: Boolean): OutboxBanner? {
    if (counts.isEmpty) return null
    val lines = mutableListOf<OutboxBannerLine>()
    if (counts.waiting > 0) {
        lines.add(
            OutboxBannerLine(
                text = if (online) {
                    "${outboxEntryCount(counts.waiting)} saved on this device — sending now."
                } else {
                    "${outboxEntryCount(counts.waiting)} saved on this device — will be sent when " +
                        "you have a signal."
                },
                warn = false,
            )
        )
    }
    if (counts.refused > 0) {
        lines.add(
            OutboxBannerLine(
                text = "${outboxEntryCount(counts.refused)} the server would not accept. A sync will " +
                    "NOT move ${if (counts.refused == 1) "it" else "them"} — tap to read why and try " +
                    "again. Nothing has been deleted.",
                warn = true,
            )
        )
    }
    return OutboxBanner(
        // The icon follows the WAITING half only. This is the gate `dwDeviceSyncBanner` computes for
        // the same reason, and getting it wrong in the other direction is what shipped here.
        showCloudOff = counts.waiting > 0,
        actionable = counts.refused > 0,
        lines = lines,
    )
}

/**
 * One refused entry, as the tray lists it: what it was, why it will not send, what it is carrying.
 *
 * A PURE PROJECTION of [PendingEntry] rather than the entry itself, so the tray's wording is pinned
 * by a JVM test and so the tray never holds [PendingEntry.payloadJson]. That payload is the record's
 * whole body; a screen that held it would sooner or later print a field out of it, and the fields in
 * there include an artisan's identity answers.
 */
data class OutboxFailureRow(
    val entryId: String,
    val label: String,
    val kind: String,
    val reason: String,
    val mediaCount: Int,
    /** True when this entry is waiting on a newer build rather than on a person. See `blocksRetry`. */
    val awaitingUpdate: Boolean,
)

/**
 * How a queued entry's type reads to the person holding the phone.
 *
 * The wire values are lower-case route names — "artisan", "questionnaire", [OFFLINE_EXPORT_RECORD] —
 * and one of them is not a record at all. A tray that printed them raw would show a designer the
 * word "dwexport" and leave them to work out that the thing that failed was a bookkeeping row about
 * a report they had already delivered by hand.
 */
fun outboxKindLabel(type: String, isUpdate: Boolean): String {
    val noun = when (type) {
        "artisan" -> "Artisan"
        "craft" -> "Craft"
        "product" -> "Product"
        "tool" -> "Toolkit"
        "process" -> "Process"
        "workshop" -> "Workshop"
        "questionnaire" -> "Interview"
        OFFLINE_MEDIA_ONLY -> "Photographs and recordings"
        OFFLINE_EXPORT_RECORD -> "Report delivery note"
        else -> type
    }
    return if (isUpdate) "$noun — a correction" else noun
}

/**
 * WHAT A RETRY ACTUALLY ACHIEVED, split so that no sentence can attribute one entry's success to
 * another.
 *
 * A retry drains the WHOLE queue — that is deliberate and worth keeping, because a person who has
 * just found a signal wants everything to go, not one row. But the pass therefore moves entries the
 * person did not ask about, and reporting its total as though it were the answer about the entry they
 * tapped is how "“A” was sent." came to be printed above A, still listed, still refused.
 *
 * @property requestedSent the entry the person tapped (or, for "try all", at least one of the refused
 *   ones) is gone from the queue, which for this queue means the server took it.
 * @property refusedSent how many of the entries this retry was ABOUT went.
 * @property refusedTried how many entries this retry was about — 1 for a single row.
 * @property othersSent entries the same pass drained that this retry was NOT about. Worth saying,
 *   because a designer watching the banner's count drop by three after tapping one row deserves to
 *   know where the other two went; never worth counting as success for the row they tapped.
 */
data class OutboxRetryResult(
    val requestedSent: Boolean,
    val refusedSent: Int,
    val refusedTried: Int,
    val othersSent: Int,
    /**
     * The device had a connection, so the queue was actually tried.
     *
     * FALSE MEANS NOTHING WAS SENT ANYWHERE, and it needs its own sentence for the same reason the
     * rest of this file exists: `syncOutbox` returns 0 without attempting anything when there is no
     * signal, which is indistinguishable at the call site from "attempted and refused again". The old
     * message told a designer standing in a courtyard with no bars that "the reason under it is what
     * came back this time" — over a request that was never made.
     */
    val attempted: Boolean = true,
)

/** "1 other entry" / "3 other entries", for the tail of a retry sentence. */
private fun otherEntries(n: Int): String = if (n == 1) "1 other entry" else "$n other entries"

/**
 * What the tray says after ONE refused entry was tried again.
 *
 * PURE and pinned by `OutboxBannerTest`, because the failure it replaces was a sentence assembled
 * inside a composable out of a number that did not mean what it was read as. The success half names
 * the entry; the failure half must NOT, because the reason is already printed verbatim under the row
 * and repeating the label above it only makes the screen say the same thing twice.
 */
fun outboxRetryMessage(label: String, result: OutboxRetryResult): String {
    if (!result.attempted) {
        return "This phone has no connection, so nothing was tried. “$label” is still here and " +
            "nothing has been deleted — try again when you have a signal."
    }
    val tail = if (result.othersSent > 0) {
        " ${otherEntries(result.othersSent)} in the queue went at the same time."
    } else {
        ""
    }
    return if (result.requestedSent) {
        "“$label” was sent.$tail"
    } else {
        "“$label” still did not go. The reason under it is what came back this time.$tail"
    }
}

/** What the tray says after every refused entry was tried again. See [outboxRetryMessage]. */
fun outboxRetryAllMessage(result: OutboxRetryResult): String {
    if (!result.attempted) {
        return "This phone has no connection, so nothing was tried. They are all still here and " +
            "nothing has been deleted — try again when you have a signal."
    }
    val head = when {
        result.refusedTried == 0 -> "There was nothing refused to try."
        result.refusedSent == 0 ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; none went. The reason under each " +
                "one is what came back this time."
        result.refusedSent == result.refusedTried ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; all of them went."
        else ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; ${result.refusedSent} went and " +
                "${result.refusedTried - result.refusedSent} still did not."
    }
    return if (result.othersSent > 0) {
        val were = if (result.othersSent == 1) "was" else "were"
        "$head ${otherEntries(result.othersSent)} that $were only waiting for a signal went too."
    } else {
        head
    }
}

/** The rows for the tray, most recently refused first, projected from the queue. */
fun outboxFailureRows(entries: List<PendingEntry>): List<OutboxFailureRow> =
    entries
        .filter { it.failure != null }
        .sortedByDescending { it.failedAt ?: it.createdAt }
        .map { entry ->
            OutboxFailureRow(
                entryId = entry.id,
                label = entry.label.ifBlank { "(untitled)" },
                kind = outboxKindLabel(
                    entry.type,
                    isUpdate = entry.targetId != null && entry.type != OFFLINE_MEDIA_ONLY,
                ),
                reason = entry.failure.orEmpty(),
                mediaCount = entry.media.size,
                awaitingUpdate = entry.skewRun != null,
            )
        }
