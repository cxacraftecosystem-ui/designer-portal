package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@Serializable
data class DwInspectionFeedbackBody(
    val note: String,
    val stageKey: String? = null,
    val fieldKey: String? = null,
    val recordedAt: String? = null,
)

@Serializable
data class DwInspectionFeedbackAnswerDto(
    val id: String = "",
    val status: String = "",
    val submissionRound: Int = 0,
    val reviewNotes: String? = null,
    val reviewedById: String? = null,
    val reviewedAt: String? = null,
    val inspectionFeedback: List<DwInspectionFeedbackDto> = emptyList(),
    val inspectionFeedbackTruncated: Boolean = false,
    val mayRecordFeedback: Boolean = false,
    val myClearedAt: String? = null,
)

fun DwInspectionDetailDto.withFeedbackAnswer(answer: DwInspectionFeedbackAnswerDto): DwInspectionDetailDto =
    copy(
        status = answer.status.ifBlank { status },
        submissionRound = answer.submissionRound,
        reviewNotes = answer.reviewNotes,
        reviewedById = answer.reviewedById,
        reviewedAt = answer.reviewedAt,
        inspectionFeedback = answer.inspectionFeedback,
        inspectionFeedbackTruncated = answer.inspectionFeedbackTruncated,
        mayRecordFeedback = answer.mayRecordFeedback,
        myClearedAt = answer.myClearedAt,
    )

const val DW_CLEARANCE_LABEL = "Checked, no corrections"

fun dwClearanceBlocked(record: DwInspectionDetailDto, userId: String?): Boolean =
    record.myClearedAt == null &&
        !userId.isNullOrBlank() &&
        record.inspectionFeedback.any { it.actorId == userId && it.round == record.submissionRound }

fun dwClearanceDescription(record: DwInspectionDetailDto, userId: String?): String =
    if (dwClearanceBlocked(record, userId)) "You've filed notes on this round." else "Shown to the approving authority."

fun dwClearanceStatus(clearedAt: String?): String? = usageDay(clearedAt)?.let { "Since $it" }

const val DW_INSPECTION_NOTE_MAX = 4000

val DW_UNDER_REVIEW_STATUSES: Set<String> = setOf("PRE_SUBMISSION", "NEEDS_REVISION")

fun dwIsUnderReview(status: String?): Boolean = status?.trim()?.uppercase() in DW_UNDER_REVIEW_STATUSES

fun dwReviewStatusLabel(status: String?): String = when (status?.trim()?.uppercase()) {
    "DRAFT" -> "a draft"
    "IN_PROGRESS" -> "in progress"
    "COMPLETE" -> "complete"
    "PRE_SUBMISSION" -> "in pre-submission"
    "NEEDS_REVISION" -> "waiting for revisions"
    "SUBMITTED" -> "handed on to the office"
    "APPROVED" -> "approved"
    "ARCHIVED" -> "archived"
    else -> "in a state this app does not recognise"
}

const val DW_NOT_UNDER_REVIEW_SENTENCE =
    "Not handed in for inspection yet."

data class DwFeedbackRound(val round: Int, val rows: List<DwInspectionFeedbackDto>)

fun dwFeedbackRounds(rows: List<DwInspectionFeedbackDto>): List<DwFeedbackRound> =
    rows.groupBy { it.round }
        .toSortedMap(compareByDescending { it })
        .map { (round, inRound) -> DwFeedbackRound(round, inRound) }

fun dwFeedbackAuthor(row: DwInspectionFeedbackDto): String =
    row.actorName?.trim()?.takeIf { it.isNotEmpty() } ?: "An officer no longer named"

@Serializable
enum class DwInspectionNoteKind { SUGGESTION, SEND_BACK }

@Serializable
data class DwQueuedInspectionNote(
    val id: String,
    val workshopId: String,
    val workshopTitle: String = "",
    val kind: DwInspectionNoteKind = DwInspectionNoteKind.SUGGESTION,
    val note: String,
    val stageKey: String? = null,
    val recordedAt: String,
    val ownerUserId: String,
    val draftedRound: Int = 0,
    val draftedStatus: String = "",
    val held: String? = null,
    val heldAt: String? = null,
) {
    val waiting: Boolean get() = held == null

    fun body(): DwInspectionFeedbackBody =
        DwInspectionFeedbackBody(note = note.trim(), stageKey = stageKey, recordedAt = recordedAt)
}

fun dwInspectionNoteProblem(note: String): String? {
    val text = note.trim()
    return when {
        text.isEmpty() -> "Write the correction first."
        text.length > DW_INSPECTION_NOTE_MAX ->
            "Max $DW_INSPECTION_NOTE_MAX characters (now ${text.length}). File the rest separately."
        else -> null
    }
}

sealed interface DwInspectionNoteCheck {
    data object Send : DwInspectionNoteCheck
    data class Hold(val sentence: String) : DwInspectionNoteCheck
}

fun dwInspectionNoteCheck(
    note: DwQueuedInspectionNote,
    currentStatus: String,
    currentRound: Int,
): DwInspectionNoteCheck {
    val what = if (note.kind == DwInspectionNoteKind.SEND_BACK) "send-back" else "suggestion"
    if (!dwIsUnderReview(currentStatus)) {
        return DwInspectionNoteCheck.Hold(
            "Nothing was sent — the report moved on and is now ${dwReviewStatusLabel(currentStatus)}. Your note is kept here."
        )
    }
    if (note.draftedRound > 0 && currentRound != note.draftedRound) {
        return DwInspectionNoteCheck.Hold(
            "Nothing was sent — the report moved to round $currentRound since you wrote this (round ${note.draftedRound}). Re-read it, then file or discard."
        )
    }
    return DwInspectionNoteCheck.Send
}

sealed interface DwInspectionSendOutcome {
    data object Sent : DwInspectionSendOutcome
    data object Wait : DwInspectionSendOutcome
    data class Refused(val sentence: String) : DwInspectionSendOutcome
}

fun dwInspectionSendOutcome(
    status: Int?,
    said: String?,
    kind: DwInspectionNoteKind,
): DwInspectionSendOutcome {
    val text = said?.trim()?.takeIf { it.isNotEmpty() }
    val what = if (kind == DwInspectionNoteKind.SEND_BACK) "send-back" else "suggestion"
    val kept = "Nothing was filed, and your note is kept here."
    return when {
        status == null || status == 401 || status == 408 || status == 429 || status >= 500 ->
            DwInspectionSendOutcome.Wait
        status == 404 -> DwInspectionSendOutcome.Refused(
            "This workshop is no longer open to you. $kept Copy it if needed, then discard."
        )
        status == 422 && text != null && text.startsWith("This report has not been handed in") ->
            DwInspectionSendOutcome.Refused(
                "Not sent — the report moved on before your $what arrived. $kept"
            )
        status == 403 -> DwInspectionSendOutcome.Refused(
            "You can't file on this workshop. ${text?.let { "$it " } ?: ""}$kept"
        )
        else -> DwInspectionSendOutcome.Refused(
            (text?.let { if (it.last() in ".!?") "$it " else "$it. " } ?: "Your $what was not accepted. ") + kept
        )
    }
}

fun dwInspectionNoteAlreadyFiled(
    note: DwQueuedInspectionNote,
    register: List<DwInspectionFeedbackDto>,
): Boolean {
    val sent = dwInstantOrNull(note.recordedAt) ?: return false
    val text = note.note.trim()
    return register.any { row ->
        row.actorId == note.ownerUserId &&
            row.note.trim() == text &&
            dwInstantOrNull(row.recordedAt) == sent
    }
}

private fun dwInstantOrNull(text: String?): Instant? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .getOrNull()
        ?.truncatedTo(ChronoUnit.MILLIS)
}

fun dwInspectionNow(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

fun dwQueuedNoteSentence(note: DwQueuedInspectionNote): String =
    if (note.kind == DwInspectionNoteKind.SEND_BACK) {
        "Queued. Sends back to the designers once online, if the round hasn't changed."
    } else {
        "Queued. Files once online, if the round hasn't changed."
    }

data class DwInspectionReviewQueue(
    val awaitingYou: List<DesignWorkshopDto>,
    val withDesigners: List<DesignWorkshopDto>,
    val notHandedIn: List<DesignWorkshopDto>,
)

fun dwInspectionReviewQueue(rows: List<DesignWorkshopDto>): DwInspectionReviewQueue {
    val awaiting = ArrayList<DesignWorkshopDto>()
    val withDesigners = ArrayList<DesignWorkshopDto>()
    val other = ArrayList<DesignWorkshopDto>()
    rows.distinctBy { it.id }.forEach { row ->
        when (row.status.trim().uppercase()) {
            "PRE_SUBMISSION" -> awaiting += row
            "NEEDS_REVISION" -> withDesigners += row
            else -> other += row
        }
    }
    return DwInspectionReviewQueue(awaiting, withDesigners, other)
}

fun dwInspectionWaitingLabel(count: Int): String? = if (count > 0) "$count waiting" else null

fun dwInspectionWaitingSentence(count: Int): String =
    if (count == 1) "1 report is waiting for your review" else "$count reports are waiting for your review"

const val DW_INSPECTION_QUEUE_PAGE_SIZE = 100

const val DW_INSPECTION_QUEUE_MAX_PAGES = 5

@Serializable
data class DwSavedInspection(
    val ownerUserId: String,
    val savedAt: String,
    val detail: DwInspectionDetailDto,
)

@Serializable
data class DwSavedInspectionList(
    val ownerUserId: String,
    val savedAt: String,
    val items: List<DesignWorkshopDto> = emptyList(),
    val total: Int = 0,
)

class DwInspectionStore(private val dir: File) {

    private fun outboxFile() = File(dir, "notes.json")
    private fun listFile() = File(dir, "list.json")
    private fun detailFile(workshopId: String) = File(dir, "workshop-${safe(workshopId)}.json")

    private fun safe(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    fun notes(): List<DwQueuedInspectionNote> = synchronized(LOCK) { readNotes() }

    fun notesFor(ownerUserId: String, workshopId: String? = null): List<DwQueuedInspectionNote> =
        notes().filter { it.ownerUserId == ownerUserId && (workshopId == null || it.workshopId == workshopId) }

    fun add(note: DwQueuedInspectionNote) = synchronized(LOCK) { writeNotes(readNotes() + note) }

    fun update(note: DwQueuedInspectionNote) = synchronized(LOCK) {
        val current = readNotes()
        if (current.any { it.id == note.id }) writeNotes(current.map { if (it.id == note.id) note else it })
    }

    fun remove(noteId: String) = synchronized(LOCK) {
        val current = readNotes()
        if (current.any { it.id == noteId }) writeNotes(current.filterNot { it.id == noteId })
    }

    fun saveDetail(saved: DwSavedInspection) {
        if (saved.detail.id.isBlank()) return
        synchronized(LOCK) { writeText(detailFile(saved.detail.id), json.encodeToString(saved)) }
    }

    fun detail(workshopId: String, ownerUserId: String): DwSavedInspection? = synchronized(LOCK) {
        val file = detailFile(workshopId)
        if (!file.exists()) return@synchronized null
        runCatching { json.decodeFromString<DwSavedInspection>(file.readText()) }.getOrNull()
            ?.takeIf { it.ownerUserId == ownerUserId }
    }

    fun forgetWorkshop(workshopId: String) = synchronized(LOCK) {
        detailFile(workshopId).delete()
        val list = readList() ?: return@synchronized
        if (list.items.any { it.id == workshopId }) {
            writeText(
                listFile(),
                json.encodeToString(
                    list.copy(items = list.items.filterNot { it.id == workshopId }, total = maxOf(0, list.total - 1))
                )
            )
        }
    }

    fun saveList(saved: DwSavedInspectionList) = synchronized(LOCK) { writeText(listFile(), json.encodeToString(saved)) }

    fun list(ownerUserId: String): DwSavedInspectionList? = synchronized(LOCK) {
        readList()?.takeIf { it.ownerUserId == ownerUserId }
    }

    private fun readList(): DwSavedInspectionList? {
        val file = listFile()
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<DwSavedInspectionList>(file.readText()) }.getOrNull()
    }

    private fun readNotes(): List<DwQueuedInspectionNote> {
        val file = outboxFile()
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull()
        val parsed = text?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<DwQueuedInspectionNote>>(it) }.getOrNull() }
        if (parsed == null) {
            runCatching { file.renameTo(File(dir, "notes.damaged-${System.currentTimeMillis()}.json")) }
            return emptyList()
        }
        return parsed
    }

    private fun writeNotes(notes: List<DwQueuedInspectionNote>) = writeText(outboxFile(), json.encodeToString(notes))

    private fun writeText(target: File, text: String) {
        dir.mkdirs()
        val temp = File(dir, "${target.name}.writing")
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
            }
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    companion object {
        private val LOCK = Any()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

        fun of(context: android.content.Context): DwInspectionStore =
            DwInspectionStore(File(context.filesDir, "inspections"))
    }
}

data class DwInspectionSyncReport(
    val sent: Int = 0,
    val held: Int = 0,
    val waiting: Int = 0,
    val refreshed: Map<String, DwInspectionDetailDto> = emptyMap(),
)

sealed interface DwInspectionRead {
    val detail: DwInspectionDetailDto

    data class Live(override val detail: DwInspectionDetailDto) : DwInspectionRead
    data class Saved(override val detail: DwInspectionDetailDto, val savedAt: String) : DwInspectionRead
}

fun dwSavedInspectionSentence(savedAt: String): String {
    val day = savedAt.take(10)
    val time = savedAt.drop(11).take(5)
    val whenSaved = if (day.length == 10 && time.length == 5) "$day at $time UTC" else "earlier"
    return "Offline — saved copy from $whenSaved. You can still write suggestions; they send when you're back online."
}
