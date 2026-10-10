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

/**
 * THE INSPECTOR'S NOTE ON THE HANDSET: correction suggestions, the send-back, the review queue, and
 * the copy of an inspection kept on the phone so the work goes on without signal.
 *
 * ── WHAT THE SERVER ALREADY OFFERED, AND THE HANDSET NOW USES ────────────────────────────────────
 *
 * `POST /design-workshop-inspections/{id}/feedback` files one suggestion and moves nothing;
 * `POST /design-workshop-inspections/{id}/send-back` files one and moves the report to Needs revision.
 * Both take a `recordedAt` — the DEVICE's own moment — precisely so a note written in a courtyard can
 * be sent a fortnight later and still say when it was written. Both answer with the register as it now
 * stands. Nothing about either route was widened for this file: the body is the web's body, the
 * permission is the server's `require_inspector`, and the round is copied off the workshop by the
 * server and never sent.
 *
 * ── THE CONFLICT RULES, AND WHY THEY ARE STRICTER ON A QUEUE THAN IN A BROWSER ───────────────────
 *
 * In a browser the note is filed the moment it is written, so the report it talks about is the report
 * on screen. A queued note is filed LATER, and in between the report may have moved on. So before a
 * queued note leaves, the workshop is read again and [dwInspectionNoteCheck] holds it back, with the
 * reason in words, when:
 *
 *  1. the report is no longer under review (it was withdrawn, or approved) — the server would refuse
 *     it with the same sentence the web shows, and the check says so before spending the request; or
 *  2. the report was handed in again since the note was written — the server would ACCEPT the note,
 *     but against a round its author has not read, so it is held for the inspector to re-file on
 *     purpose rather than landing on somebody's desk as a comment on work that has since changed.
 *
 * A second officer's send-back on a report already sent back in the SAME round is not a conflict: the
 * server takes it as a 200 and a row, exactly as it does from the web, and so does this queue.
 *
 * Whatever the server still refuses ([dwInspectionSendOutcome]) is shown plainly beside the note, and
 * NOTHING IS DELETED BY A REFUSAL. The note stays on the phone until the inspector re-files it, puts it
 * back in the box, or discards it themselves.
 *
 * ── A LOST ANSWER DOES NOT FILE THE NOTE TWICE ───────────────────────────────────────────────────
 *
 * The routes carry no idempotency key, so a request that landed while its answer was lost would be
 * re-sent. [dwInspectionNoteAlreadyFiled] closes that: the register is read before every send, and a
 * row by this account with the same text and the same device moment IS this note.
 *
 * ── THE COPY KEPT ON THE PHONE ───────────────────────────────────────────────────────────────────
 *
 * The last read of each workshop under inspection, and the last page of the list, are kept per
 * account. Two things stop that copy outliving the access it was read under: a read that answers "not
 * open to you" deletes it at once, and a copy written for one account is never shown to another.
 */

// --------------------------------------------------------------------------------------
// The wire
// --------------------------------------------------------------------------------------

/** The body of both write routes — the web's `{note, stageKey}` plus the device's own moment. */
@Serializable
data class DwInspectionFeedbackBody(
    val note: String,
    val stageKey: String? = null,
    val fieldKey: String? = null,
    val recordedAt: String? = null,
)

/**
 * What both write routes answer: the workshop header plus the register as the server now holds it.
 *
 * Its own type rather than [DwInspectionDetailDto], because the answer carries no stages and no
 * completeness — decoding it into the read's type and adopting it whole would blank every stage on
 * screen. [DwInspectionDetailDto.withFeedbackAnswer] takes exactly the keys this answer is about.
 */
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
)

/** The read, with the register and the header replaced by what a write just answered. */
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
    )

/** The server's `MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS`. Over it, the note is refused. */
const val DW_INSPECTION_NOTE_MAX = 4000

/** `UNDER_REVIEW` on the server: the only two states in which a suggestion may be filed. */
val DW_UNDER_REVIEW_STATUSES: Set<String> = setOf("PRE_SUBMISSION", "NEEDS_REVISION")

fun dwIsUnderReview(status: String?): Boolean = status?.trim()?.uppercase() in DW_UNDER_REVIEW_STATUSES

/** How each status is spoken in a sentence — the server's `_LABELS`, word for word. */
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

/**
 * The refusal the server gives a note on a report that is not under review — the web prints the same
 * sentence before the press, and so does this app.
 */
const val DW_NOT_UNDER_REVIEW_SENTENCE =
    "This report has not been handed in for inspection yet, so there is nothing to comment on. " +
        "Its designers hand it in from the workshop's own screen; the box opens then."

/** One round of the register, newest round first; rows keep the server's newest-first order. */
data class DwFeedbackRound(val round: Int, val rows: List<DwInspectionFeedbackDto>)

fun dwFeedbackRounds(rows: List<DwInspectionFeedbackDto>): List<DwFeedbackRound> =
    rows.groupBy { it.round }
        .toSortedMap(compareByDescending { it })
        .map { (round, inRound) -> DwFeedbackRound(round, inRound) }

/** Who filed a row. Never a guess: a blank name is said as one. */
fun dwFeedbackAuthor(row: DwInspectionFeedbackDto): String =
    row.actorName?.trim()?.takeIf { it.isNotEmpty() } ?: "An officer no longer named"

// --------------------------------------------------------------------------------------
// The queued note
// --------------------------------------------------------------------------------------

/** Two acts and never one with a flag — the server's own rule, "one button per status". */
@Serializable
enum class DwInspectionNoteKind { SUGGESTION, SEND_BACK }

/**
 * A correction suggestion, or a send-back, written on this phone and not yet on the record.
 *
 * @property draftedRound / [draftedStatus] the report as the inspector READ it when they wrote this —
 *   what [dwInspectionNoteCheck] compares the workshop against before the note leaves.
 * @property held why the note was held back or refused, in words; null while it is waiting to go.
 *   A held note is never retried on its own and never deleted by anything but its author.
 */
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

/** What the box will accept, or the sentence that says why not. Null means it may be queued. */
fun dwInspectionNoteProblem(note: String): String? {
    val text = note.trim()
    return when {
        text.isEmpty() -> "Write what should be corrected first — a note with nothing in it tells a designer only that something is wrong."
        text.length > DW_INSPECTION_NOTE_MAX ->
            "A correction suggestion is at most $DW_INSPECTION_NOTE_MAX characters, and this one is " +
                "${text.length}. File the rest as a second suggestion."
        else -> null
    }
}

/** May a queued note go now, or must it be held for its author? */
sealed interface DwInspectionNoteCheck {
    data object Send : DwInspectionNoteCheck
    data class Hold(val sentence: String) : DwInspectionNoteCheck
}

/**
 * THE CONFLICT RULE, checked against the workshop as it stands NOW, before a queued note leaves.
 * See the file header for why each arm exists.
 */
fun dwInspectionNoteCheck(
    note: DwQueuedInspectionNote,
    currentStatus: String,
    currentRound: Int,
): DwInspectionNoteCheck {
    val what = if (note.kind == DwInspectionNoteKind.SEND_BACK) "send-back" else "suggestion"
    if (!dwIsUnderReview(currentStatus)) {
        return DwInspectionNoteCheck.Hold(
            "The report moved on before your $what could be sent: it is now " +
                "${dwReviewStatusLabel(currentStatus)}, and a report that is not under review takes no " +
                "correction suggestions. Nothing was sent, and your note is kept here — put it back in " +
                "the box to file it once the report is handed in again, or discard it."
        )
    }
    if (note.draftedRound > 0 && currentRound != note.draftedRound) {
        return DwInspectionNoteCheck.Hold(
            "The report was handed in again after you wrote this: you wrote it about round " +
                "${note.draftedRound}, and it is now round $currentRound. Nothing was sent, so that " +
                "your note does not land on a version you have not read. Read the workshop again, then " +
                "file it against round $currentRound or discard it."
        )
    }
    return DwInspectionNoteCheck.Send
}

/** What one send attempt came to. */
sealed interface DwInspectionSendOutcome {
    /** On the record. */
    data object Sent : DwInspectionSendOutcome
    /** Not answered, or not answered in a way anybody can act on: the note stays queued as it is. */
    data object Wait : DwInspectionSendOutcome
    /** A final answer. The note is kept, held, with this sentence beside it. */
    data class Refused(val sentence: String) : DwInspectionSendOutcome
}

/**
 * A send's failure, sorted into "try again later" and "tell the inspector now".
 *
 * NO SIGNAL, A SIGNED-OUT PHONE AND A FAULT ON THE FAR SIDE ALL WAIT: none of them is an answer about
 * the note, and each clears on its own. Everything else the server said is final and is shown.
 *
 * @param status the HTTP status, or null when nothing answered.
 * @param said the server's own sentence, already unwrapped, or null.
 */
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
            "This workshop is no longer open to you to inspect — the assignment may have ended, or the " +
                "workshop was deleted. $kept Copy it if you still need it, then discard it."
        )
        status == 422 && text != null && text.startsWith("This report has not been handed in") ->
            DwInspectionSendOutcome.Refused(
                "The report moved on before your $what arrived: it is no longer under review. $kept " +
                    "Put it back in the box to file it once the report is handed in again."
            )
        status == 403 -> DwInspectionSendOutcome.Refused(
            "This account may not file on this workshop. ${text?.let { "$it " } ?: ""}$kept"
        )
        else -> DwInspectionSendOutcome.Refused(
            (text?.let { if (it.last() in ".!?") "$it " else "$it. " } ?: "Your $what was not accepted. ") + kept
        )
    }
}

/**
 * Is [note] already on the record — sent once, with its answer lost on the way back?
 *
 * Matched on this account, the same text and the same DEVICE moment. The moment is compared as an
 * instant to the millisecond, because the server re-spells it (`…Z` goes out, `…+00:00` comes back).
 */
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

/** Now, as the device's own moment, to the millisecond and in UTC — what `recordedAt` carries. */
fun dwInspectionNow(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/** The sentence under a note that is waiting to go. */
fun dwQueuedNoteSentence(note: DwQueuedInspectionNote): String =
    if (note.kind == DwInspectionNoteKind.SEND_BACK) {
        "Waiting to be sent. When this phone has a connection the report is checked again and, if it " +
            "is still the round you read, sent back to its designers with this note."
    } else {
        "Waiting to be sent. When this phone has a connection the report is checked again and, if it " +
            "is still the round you read, this suggestion is filed."
    }

// --------------------------------------------------------------------------------------
// The review queue
// --------------------------------------------------------------------------------------

/**
 * The workshops assigned to this inspector, sorted by whose move it is.
 *
 * [awaitingYou] is Pre-submission: handed in and waiting for an officer's decision. [withDesigners] is
 * Needs revision: sent back and waiting for its designers. Everything else is [notHandedIn] — counted,
 * because a queue that silently drops a workshop reads as one nobody assigned.
 */
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

/** The server's page ceiling; the queue walks pages of this size. */
const val DW_INSPECTION_QUEUE_PAGE_SIZE = 100

/** How many pages the queue reads before it says the rest were not read. */
const val DW_INSPECTION_QUEUE_MAX_PAGES = 5

// --------------------------------------------------------------------------------------
// What the phone keeps
// --------------------------------------------------------------------------------------

/** One workshop's last read, kept for the account it was read for. */
@Serializable
data class DwSavedInspection(
    val ownerUserId: String,
    val savedAt: String,
    val detail: DwInspectionDetailDto,
)

/** The last answer of the assigned list, kept for the account it was read for. */
@Serializable
data class DwSavedInspectionList(
    val ownerUserId: String,
    val savedAt: String,
    val items: List<DesignWorkshopDto> = emptyList(),
    val total: Int = 0,
)

/**
 * The phone's store for inspections: queued notes, the last read of each workshop, the last list.
 *
 * Plain files under one directory, each replaced as one indivisible step (write, flush to storage,
 * rename) — the arrangement [OfflineOutbox] arrived at after a killed process emptied its queue. A
 * file that will not parse is moved aside rather than treated as empty, so a damaged queue can never
 * be overwritten into nothing.
 *
 * Synchronised on one lock for the whole process, not per instance: two screens and the background
 * pass each build their own handle onto the same directory.
 */
class DwInspectionStore(private val dir: File) {

    private fun outboxFile() = File(dir, "notes.json")
    private fun listFile() = File(dir, "list.json")
    private fun detailFile(workshopId: String) = File(dir, "workshop-${safe(workshopId)}.json")

    private fun safe(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    fun notes(): List<DwQueuedInspectionNote> = synchronized(LOCK) { readNotes() }

    fun notesFor(ownerUserId: String, workshopId: String? = null): List<DwQueuedInspectionNote> =
        notes().filter { it.ownerUserId == ownerUserId && (workshopId == null || it.workshopId == workshopId) }

    fun add(note: DwQueuedInspectionNote) = synchronized(LOCK) { writeNotes(readNotes() + note) }

    /** Replace the queued note carrying [note]'s id; a note no longer queued is not resurrected. */
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

    /** The kept read of [workshopId], or null — and always null for any account but its own. */
    fun detail(workshopId: String, ownerUserId: String): DwSavedInspection? = synchronized(LOCK) {
        val file = detailFile(workshopId)
        if (!file.exists()) return@synchronized null
        runCatching { json.decodeFromString<DwSavedInspection>(file.readText()) }.getOrNull()
            ?.takeIf { it.ownerUserId == ownerUserId }
    }

    /** Forget a workshop this account may no longer open — its kept read, and its row in the list. */
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
            // KEPT, NEVER EMPTIED. The bytes move aside under a name a support request can ask for.
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
                // Windows-hosted JVM tests cannot rename over an existing file; a phone can.
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

/** What a pass over the queued notes did, for a sentence on screen. */
data class DwInspectionSyncReport(
    val sent: Int = 0,
    val held: Int = 0,
    val waiting: Int = 0,
    /** The freshest read of each workshop touched, keyed by id, so a screen can redraw from it. */
    val refreshed: Map<String, DwInspectionDetailDto> = emptyMap(),
)

/** How a workshop under inspection was read: live, or from the copy kept on this phone. */
sealed interface DwInspectionRead {
    val detail: DwInspectionDetailDto

    data class Live(override val detail: DwInspectionDetailDto) : DwInspectionRead
    data class Saved(override val detail: DwInspectionDetailDto, val savedAt: String) : DwInspectionRead
}

/** The line over a workshop read from the phone's copy. */
fun dwSavedInspectionSentence(savedAt: String): String {
    val day = savedAt.take(10)
    val time = savedAt.drop(11).take(5)
    val whenSaved = if (day.length == 10 && time.length == 5) "$day at $time UTC" else "earlier"
    return "No connection — this is the copy kept on this phone from $whenSaved. You can read it and " +
        "write suggestions; they are sent, checked against the report as it then stands, when this " +
        "phone is back online."
}
