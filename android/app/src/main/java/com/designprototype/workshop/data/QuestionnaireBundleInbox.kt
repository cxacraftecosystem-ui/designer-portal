package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * QUESTIONNAIRES THAT ARRIVED FROM ANOTHER PHONE, kept on this one until they can be adopted.
 *
 * ── WHY A DURABLE STORE AND NOT JUST A FILE PICKER ────────────────────────────────────────────
 *
 * The whole premise is two designers in a courtyard with no signal. The transfer works there —
 * Bluetooth and Quick Share need no internet — but ADOPTING a questionnaire does not: it creates rows
 * on the server, and the server sets the owner from the bearer token, which is precisely what makes
 * the received file safe (see [QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE]). So there is a gap of hours or days
 * between "the file is on my phone" and "the questionnaire is on my account", and something has to
 * hold it across that gap.
 *
 * A content Uri cannot. `ACTION_SEND` grants a read that dies with the task, and the file behind it
 * may be in a chat app's cache. So the BYTES are copied into `filesDir/inbox/questionnaires/` the
 * moment they arrive — the same decision, for the same reason, that
 * [WorkshopDraftStore.importMedia] makes about a photograph: "The picker's Uri is a permission grant
 * scoped to this task and the app's own camera captures land in cacheDir, which Android empties
 * without warning."
 *
 * ── WHY ADOPTION PROGRESS IS RECORDED, AND WHY THAT IS NOT OPTIONAL ───────────────────────────
 *
 * There is no bulk create route. `POST /questionnaires/upload` takes an .xlsx — which this handset
 * cannot build and deliberately does not try to — so adoption is one `POST` for the questionnaire,
 * one per section and ONE PER QUESTION. For the 24-section instrument that is 310 requests on a field
 * connection, and the probability of all 310 landing is not one.
 *
 * Without progress, a connection that drops at question two hundred leaves a half-built questionnaire
 * on the server AND an unadopted file on the phone, and the designer's only move is to try again —
 * producing a second half-built questionnaire. So [ReceivedQuestionnaire] records the created id, how
 * many sections are done and how far into the current one, written after each step, exactly as
 * [PendingEntry] records `createdId` and `uploadedMedia` and for the identical reason: "whatever kills
 * this pass, the next one starts from what has actually happened rather than from the top".
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * No automatic adoption. Nothing in this file is drained by [WorkshopRepository.syncOutbox] and it
 * must not be: adopting a questionnaire creates rows OWNED BY THE PERSON SIGNED IN, and that is a
 * decision a person makes after reading what is in the file. A queue that adopted on reconnect would
 * put a colleague's instrument on somebody's account while the phone was in their pocket.
 *
 * No deletion on adoption. The file stays, marked adopted, so a designer can see what they accepted
 * and when. Removing it is a separate, explicit act — the same policy [OfflineOutbox] keeps.
 */

/** One received file: what it is, where its bytes are, and how far its adoption got. */
@Serializable
data class ReceivedQuestionnaire(
    val id: String,
    /** The name it arrived under, shown as-is. Sanitised before it ever reaches a path. */
    val filename: String,
    val receivedAt: String,
    /** Read out of the bundle, so the list can be drawn without inflating every file. */
    val title: String = "",
    val sectionCount: Int = 0,
    val questionCount: Int = 0,
    val sourceVersion: Int = 0,
    /** The 11-character digest body of [questionnaireHandoffCode], for the QR check. */
    val handoffDigest: String = "",
    /** Absolute path of the copied bytes under `filesDir`. */
    val localPath: String = "",
    /**
     * ADOPTION PROGRESS. All defaulted, so a file received by an earlier build reads as "not started".
     *
     * [remoteId] is the load-bearing one, for the same reason [PendingEntry.createdId] is: non-null
     * means the questionnaire EXISTS on the server, and starting again would make a second one.
     */
    val remoteId: String? = null,
    /** How many of the bundle's sections are completely written. */
    val sectionsDone: Int = 0,
    /** The server id of the section currently being filled, so a resume does not create it twice. */
    val currentSectionRemoteId: String? = null,
    /**
     * A section POST has been SENT and its answer not yet written down. Set before the request, cleared
     * once [currentSectionRemoteId] holds the id.
     *
     * ── WHY [currentSectionRemoteId] ALONE DID NOT KEEP ITS OWN PROMISE ───────────────────────
     *
     * Its comment above says "so a resume does not create it twice", and between the POST landing on
     * the server and the write of the id to this file there is a window in which the section EXISTS
     * and this file says it does not. A kill in that window — an Android low-memory kill during a
     * 24-section adoption is the ordinary case, not the exotic one — sent the resume down the create
     * branch again, and `matchAdoptedSection` then code-matched the FIRST of the two, leaving a
     * permanent empty duplicate section on the designer's account. Nothing in the app can tidy that
     * up; it is a heading with nothing under it, in somebody's instrument, for ever.
     *
     * This flag is the same discipline `PendingEntry.createdId` keeps for a record, applied one level
     * down: write down that a create was ATTEMPTED before attempting it, and look before creating
     * again. Defaulted false, so a file written by an earlier build reads as "no request in flight",
     * which is the safe reading — it is the reading that was in force before this field existed.
     */
    val sectionCreateStarted: Boolean = false,
    /** How many questions of the section at index [sectionsDone] are written. */
    val questionsDone: Int = 0,
    val adoptedAt: String? = null,
    /** Why the last attempt stopped, when it stopped for a reason retrying will not change. */
    val failure: String? = null,
) {
    val adopted: Boolean get() = adoptedAt != null
    /** Questions written so far, across finished sections and the one in progress. Display only. */
    val started: Boolean get() = remoteId != null
}

object QuestionnaireBundleInbox {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private fun dir(context: Context): File =
        File(context.filesDir, "inbox/questionnaires").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), "index.json")

    /**
     * Read the index, or return nothing. A file that will not parse is QUARANTINED rather than
     * overwritten — [OfflineOutbox.read]'s rule, applied here because the failure is the same: the
     * next write would replace the damage with whatever was left, and the received questionnaires
     * would be gone with no error and no trace.
     */
    private fun read(context: Context): List<ReceivedQuestionnaire> {
        val file = indexFile(context)
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ReceivedQuestionnaire>>(text) }.getOrElse {
            runCatching {
                file.renameTo(File(file.parentFile, "index.damaged-${System.currentTimeMillis()}.json"))
            }
            emptyList()
        }
    }

    /** Temp-then-rename, plus an fsync. See [OfflineOutbox.write] for why both halves matter. */
    private fun write(context: Context, rows: List<ReceivedQuestionnaire>) {
        val target = indexFile(context)
        val temp = File(target.parentFile, "${target.name}.writing")
        val bytes = json.encodeToString(rows).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    /**
     * Keep an arrived bundle. The BYTES are written before the index row, so a row never names a file
     * that is not there — the opposite order would leave a listing entry that cannot be opened.
     *
     * @param filename the name it arrived under, for display. It never reaches a path: the stored file
     *   is named by a UUID, because a name that came from another device is untrusted input and
     *   `../` in it is a write somewhere nobody looked.
     */
    suspend fun put(
        context: Context,
        filename: String,
        bytes: ByteArray,
        bundle: QuestionnaireBundle,
    ): ReceivedQuestionnaire = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = UUID.randomUUID().toString()
            val target = File(dir(context), "$id.$QUESTIONNAIRE_BUNDLE_EXTENSION")
            FileOutputStream(target).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val row = ReceivedQuestionnaire(
                id = id,
                filename = safeDownloadName(filename) ?: "questionnaire.$QUESTIONNAIRE_BUNDLE_EXTENSION",
                receivedAt = Instant.now().toString(),
                title = bundle.title,
                sectionCount = bundle.sections.count { it.questions.isNotEmpty() },
                questionCount = bundle.questionCount,
                sourceVersion = bundle.sourceVersion,
                handoffDigest = questionnaireHandoffDigest(bundle),
                localPath = target.absolutePath,
            )
            write(context, read(context) + row)
            row
        }
    }

    /** Everything received, newest first. */
    suspend fun all(context: Context): List<ReceivedQuestionnaire> = withContext(Dispatchers.IO) {
        mutex.withLock { read(context).sortedByDescending { it.receivedAt } }
    }

    /** Read-modify-write by id, never a wholesale snapshot — [OfflineOutbox.update]'s rule. */
    suspend fun update(context: Context, row: ReceivedQuestionnaire) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == row.id }) {
                write(context, current.map { if (it.id == row.id) row else it })
            }
            Unit
        }
    }

    /** The stored bytes, or null if the file has gone. */
    suspend fun bytes(context: Context, row: ReceivedQuestionnaire): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = File(row.localPath)
            if (!file.isFile) null else runCatching { file.readBytes() }.getOrNull()
        }

    /** Forget one, deleting its bytes. A person's explicit act; nothing automatic calls this. */
    suspend fun remove(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val doomed = mutex.withLock {
            val current = read(context)
            val row = current.firstOrNull { it.id == id } ?: return@withLock null
            write(context, current.filterNot { it.id == id })
            row
        } ?: return@withContext false
        runCatching { File(doomed.localPath).delete() }
        true
    }
}

/**
 * How far an adoption got, in one sentence, for the row in the list.
 *
 * PURE, so it is pinned by a test. It exists because the middle state is the one a designer meets
 * after a connection drops, and "in progress" is not enough: they need to know that the questionnaire
 * IS already on their account, half-built, so that they carry on rather than starting again and
 * ending up with two.
 */
fun receivedQuestionnaireStatus(row: ReceivedQuestionnaire): String = when {
    row.adopted ->
        // `readableStamp` and not the raw field: this used to print
        // "Added to your questionnaires on 2026-08-24T09:12:33.221Z" — in UTC — to a designer in a
        // courtyard.
        "Added to your questionnaires${readableStamp(row.adoptedAt)?.let { " on $it" }.orEmpty()}."
    row.failure != null ->
        "Stopped: ${row.failure}"
    row.remoteId != null ->
        "Partly added — ${row.sectionsDone} of ${row.sectionCount} sections are on your account " +
            "already. Carry on where it stopped; do not start again, or you will have two copies."
    else ->
        "Not added yet. ${row.questionCount} question(s) in ${row.sectionCount} section(s)."
}
