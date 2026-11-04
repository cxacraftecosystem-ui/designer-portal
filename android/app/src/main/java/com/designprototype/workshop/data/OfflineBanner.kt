package com.designprototype.workshop.data

data class OutboxCounts(
    val waiting: Int,
    val refused: Int,
    val otherAccount: Int = 0,
) {
    val isEmpty: Boolean get() = waiting == 0 && refused == 0 && otherAccount == 0
}

internal fun outboxCountsOf(entries: List<PendingEntry>, signedInUserId: String?): OutboxCounts {
    var waiting = 0
    var refused = 0
    var elsewhere = 0
    entries.forEach { entry ->
        when {
            entry.failure != null -> refused++
            dwDraftIsForAnotherAccount(entry.ownerUserId, signedInUserId) -> elsewhere++
            else -> waiting++
        }
    }
    return OutboxCounts(waiting = waiting, refused = refused, otherAccount = elsewhere)
}

internal fun outboxRetryableFailures(
    entries: List<PendingEntry>,
    signedInUserId: String?,
): List<PendingEntry> = entries.filter {
    it.failure != null && !dwDraftIsForAnotherAccount(it.ownerUserId, signedInUserId)
}

data class OutboxBannerLine(val text: String, val warn: Boolean)

data class OutboxBanner(
    val showCloudOff: Boolean,
    val actionable: Boolean,
    val lines: List<OutboxBannerLine>,
)

internal fun outboxEntryCount(n: Int): String = if (n == 1) "1 entry" else "$n entries"

fun outboxDeviceBanner(counts: OutboxCounts, online: Boolean, serverReachable: Boolean = true): OutboxBanner? {
    if (counts.isEmpty) return null
    val lines = mutableListOf<OutboxBannerLine>()
    if (counts.waiting > 0) {
        lines.add(
            OutboxBannerLine(
                text = if (online && serverReachable) {
                    "${outboxEntryCount(counts.waiting)} saved on this phone — sending now."
                } else if (online) {
                    "${outboxEntryCount(counts.waiting)} saved on this phone — will be sent when " +
                        "the server is reachable."
                } else {
                    "${outboxEntryCount(counts.waiting)} saved on this phone — will be sent when you have a signal."
                },
                warn = false,
            )
        )
    }
    if (counts.refused > 0) {
        lines.add(
            OutboxBannerLine(
                text = "${outboxEntryCount(counts.refused)} couldn't be saved. Syncing will not send ${if (counts.refused == 1) "it" else "them"} — tap to see why. Nothing has been deleted.",
                warn = true,
            )
        )
    }
    if (counts.otherAccount > 0) {
        lines.add(
            OutboxBannerLine(
                text = "${outboxEntryCount(counts.otherAccount)} captured on this phone by another account. Sign in as them to send ${if (counts.otherAccount == 1) "it" else "them"} — nothing has been deleted.",
                warn = true,
            )
        )
    }
    return OutboxBanner(
        showCloudOff = counts.waiting > 0,
        actionable = counts.waiting > 0 || counts.refused > 0,
        lines = lines,
    )
}

data class OutboxQueuedFile(val localPath: String, val name: String, val mimeType: String)

data class OutboxFailureRow(
    val entryId: String,
    val label: String,
    val kind: String,
    val reason: String,
    val mediaCount: Int,
    val awaitingUpdate: Boolean,
    val conflict: Boolean,
    val savedOnServer: Boolean,
    val danglingNouns: List<String> = emptyList(),
    val repickKeys: List<String> = emptyList(),
    val otherAccount: Boolean = false,
    val stale: Boolean = false,
    val needsAadhaar: Boolean = false,
    val aadhaarLast4: String? = null,
    val files: List<OutboxQueuedFile> = emptyList(),
    val possibleDuplicates: List<OutboxDuplicateCandidate> = emptyList(),
    val conflictingAnswers: Int = 0,
)

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
        OFFLINE_DESIGN_RATING -> "Design review rating"
        OFFLINE_APP_FEEDBACK -> "App feedback"
        OFFLINE_QFORM_OP -> "Questionnaire change"
        OFFLINE_FEEDBACK_REPORT -> "Problem or suggestion report"
        OFFLINE_DESIGNER_PROFILE -> "Designer profile"
        else -> type
    }
    return if (isUpdate) "$noun — a correction" else noun
}

fun outboxRecordNoun(type: String): String = when (type) {
    "artisan" -> "artisan it corrects"
    "craft" -> "craft it corrects"
    "product" -> "product it corrects"
    "tool" -> "toolkit it corrects"
    "process" -> "process it corrects"
    "workshop" -> "workshop it corrects"
    "questionnaire" -> "interview it corrects"
    else -> "record it corrects"
}

data class OutboxRetryResult(
    val requestedSent: Boolean,
    val refusedSent: Int,
    val refusedTried: Int,
    val othersSent: Int,
    val attempted: Boolean = true,
)

private fun otherEntries(n: Int): String = if (n == 1) "1 other entry" else "$n other entries"

fun outboxRetryMessage(label: String, result: OutboxRetryResult): String {
    if (!result.attempted) {
        return "There's no connection, so nothing was tried. “$label” is still here; nothing has been deleted."
    }
    val tail = if (result.othersSent > 0) {
        " ${otherEntries(result.othersSent)} in the queue went at the same time."
    } else {
        ""
    }
    return if (result.requestedSent) {
        "“$label” was sent.$tail"
    } else {
        "“$label” still did not go. See the reason below.$tail"
    }
}

fun outboxRetryAllMessage(result: OutboxRetryResult): String {
    if (!result.attempted) {
        return "There's no connection, so nothing was tried. Everything is still here."
    }
    val head = when {
        result.refusedTried == 0 -> "There was nothing to try again."
        result.refusedSent == 0 ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; none went. See each reason."
        result.refusedSent == result.refusedTried ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; all of them went."
        else ->
            "Tried ${outboxEntryCount(result.refusedTried)} again; ${result.refusedSent} went and ${result.refusedTried - result.refusedSent} still did not."
    }
    return if (result.othersSent > 0) {
        val were = if (result.othersSent == 1) "was" else "were"
        "$head ${otherEntries(result.othersSent)} that $were only waiting for a signal went too."
    } else {
        head
    }
}

fun outboxSendWithoutConflictsLabel(count: Int): String =
    if (count == 1) "Send without that answer" else "Send without those $count answers"

fun outboxFailureRows(
    entries: List<PendingEntry>,
    signedInUserId: String? = null,
): List<OutboxFailureRow> =
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
                files = entry.media.map { OutboxQueuedFile(it.localPath, it.originalFilename, it.mimeType) },
                awaitingUpdate = entry.skewRun != null,
                conflict = entry.conflict,
                savedOnServer = entry.createdId != null,
                danglingNouns = entry.danglingKeys.map { key ->
                    referenceFieldNoun(key, recordNoun = outboxRecordNoun(entry.type))
                },
                repickKeys = entry.danglingKeys.filter { it in WORKSHOP_LINK_KEYS },
                otherAccount = dwDraftIsForAnotherAccount(entry.ownerUserId, signedInUserId),
                stale = entry.staleCorrection,
                needsAadhaar = entry.createdId == null && AadhaarAtRest.numberWithheld(entry.payloadJson),
                aadhaarLast4 = AadhaarAtRest.withheldLast4(entry.payloadJson),
                possibleDuplicates = entry.duplicateCandidates,
                conflictingAnswers = entry.conflictingAnswerIds.size,
            )
        }

fun connectionNotice(online: Boolean, serverUnreachable: Boolean): String? = when {
    !online -> OFFLINE_SENTENCE
    serverUnreachable -> ServerReachability.SERVER_UNREACHABLE_SENTENCE
    else -> null
}

fun shouldReprobeServer(wasOnline: Boolean, nowOnline: Boolean, serverUnreachable: Boolean): Boolean =
    nowOnline && !wasOnline && serverUnreachable

const val OFFLINE_SENTENCE = "You're offline — showing what was last loaded. New work stays on this phone."

data class OutboxWaitingRow(
    val entryId: String,
    val label: String,
    val kind: String,
    val status: String,
    val files: List<OutboxQueuedFile>,
)

fun outboxWaitingStatus(online: Boolean, serverReachable: Boolean): String = when {
    !online -> "Waiting for a signal"
    !serverReachable -> "Waiting for the server"
    else -> "Sending now"
}

fun outboxWaitingRows(
    entries: List<PendingEntry>,
    signedInUserId: String?,
    online: Boolean,
    serverReachable: Boolean = true,
): List<OutboxWaitingRow> {
    val status = outboxWaitingStatus(online, serverReachable)
    return entries
        .filter { it.failure == null && !dwDraftIsForAnotherAccount(it.ownerUserId, signedInUserId) }
        .sortedBy { it.createdAt }
        .map { entry ->
            OutboxWaitingRow(
                entryId = entry.id,
                label = entry.label.ifBlank { "(untitled)" },
                kind = outboxKindLabel(
                    entry.type,
                    isUpdate = entry.targetId != null && entry.type != OFFLINE_MEDIA_ONLY,
                ),
                status = status,
                files = entry.media.map { OutboxQueuedFile(it.localPath, it.originalFilename, it.mimeType) },
            )
        }
}
