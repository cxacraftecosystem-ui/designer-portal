package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * WRITING A QUESTIONNAIRE WITH NO SIGNAL (2026-10-16).
 *
 * ── THE PROBLEM ─────────────────────────────────────────────────────────────────────────────────
 *
 * A questionnaire is a tree: the questionnaire, its sections, their questions. Each level's create
 * needs the id the server minted for the level above, and an offline queue has no server ids. The
 * outbox could already queue the questionnaire ROW (`OFFLINE_CUSTOM_QUESTIONNAIRE`) and nothing
 * under it, so a designer in a courtyard could start a questionnaire and not write a single question.
 *
 * ── THE MECHANISM: TEMPORARY IDS, RESOLVED AT REPLAY ────────────────────────────────────────────
 *
 * Everything created offline gets a client id, [TEMP_ID_PREFIX] + a UUID, and is used under that id
 * on the phone at once — the screen shows it, and children are queued against it. Every authoring
 * step is one outbox entry of type [OFFLINE_QFORM_OP] carrying a [QFormOp]. The outbox replays in
 * queue order, so a parent's create always runs before its children's; when a create lands, the
 * server id it was given is written to [TempIdMap] (on disk, so a pass killed between the parent and
 * the child resumes correctly), and every later step swaps temporary ids for server ids before it
 * is sent. Each create carries an `Idempotency-Key`, so a create whose answer is lost is not
 * duplicated on the next pass.
 *
 * If a parent is REFUSED, its children cannot be sent; they are refused in their turn with a
 * sentence that says so ([QFormParentMissing]), and nothing is deleted.
 *
 * No server change: the routes are the existing create/patch routes, called in order.
 */
const val OFFLINE_QFORM_OP = "questionnaireAuthoring"

const val TEMP_ID_PREFIX = "tmp-"

fun newTempId(): String = TEMP_ID_PREFIX + UUID.randomUUID().toString()

fun isTempId(id: String?): Boolean = id != null && id.startsWith(TEMP_ID_PREFIX)

/** One authoring step. [op] names it; the id fields may hold temporary ids. */
@Serializable
data class QFormOp(
    val op: String,
    val questionnaireId: String,
    /** The temporary id this step CREATES (add section / add question). */
    val tempId: String? = null,
    val sectionId: String? = null,
    val questionId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val changeDescription: Boolean = false,
    val prompt: String? = null,
    val helpText: String? = null,
    val changeHelpText: Boolean = false,
    val isRequired: Boolean? = null,
    /** [REUSE] only: the design workshop the copy is for. Null makes an unattached copy. */
    val designWorkshopId: String? = null,
) {
    companion object {
        const val ADD_SECTION = "addSection"
        const val RENAME_SECTION = "renameSection"
        const val ADD_QUESTION = "addQuestion"
        const val EDIT_QUESTION = "editQuestion"
        const val REMOVE_QUESTION = "removeQuestion"
        const val RENAME_QUESTIONNAIRE = "renameQuestionnaire"
        /**
         * Copy this questionnaire as a template (2026-10-17). A create of a NEW questionnaire that
         * changes nothing on the source, so it is safe to send late; it waits behind any step still
         * queued for the source, so the copy includes them, and carries an `Idempotency-Key` so a
         * lost answer is not a second copy.
         */
        const val REUSE = "reuse"
    }
}

/** A child whose parent was refused, or never queued: it cannot be sent, and says why. */
class QFormParentMissing(message: String) : IllegalStateException(message)

/** Temporary id -> server id, for every authoring create that has landed. On disk. */
object TempIdMap {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private fun file(context: Context) = File(File(context.filesDir, "outbox").apply { mkdirs() }, "temp-ids.json")

    suspend fun all(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        mutex.withLock { read(file(context)) }
    }

    suspend fun put(context: Context, tempId: String, serverId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = file(context)
            write(target, read(target) + (tempId to serverId))
        }
    }

    internal fun read(target: File): Map<String, String> =
        if (!target.isFile) emptyMap()
        else runCatching { json.decodeFromString(serializer, target.readText()) }.getOrDefault(emptyMap())

    internal fun write(target: File, map: Map<String, String>) {
        val temp = File(target.parentFile, "${target.name}.writing")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(serializer, map).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
        }
    }
}

/**
 * M13 (21 Oct 2026): a stable `Idempotency-Key` for one step of adopting a received questionnaire,
 * derived from the received file's id and the step, so every retry of that step sends the same key.
 * Only `[A-Za-z0-9_-]` survive, and the key stays within the server's 8 to 128 characters.
 */
fun adoptionIdempotencyKey(rowId: String, vararg step: String): String {
    val clean = (listOf("adopt", rowId) + step).joinToString("-") { part -> part.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' } }
    return clean.padEnd(8, '0').take(128)
}

/** [id] with a temporary id swapped for its server id where one is known. */
fun resolveTempId(id: String?, map: Map<String, String>): String? = if (isTempId(id)) map[id] ?: id else id

/**
 * Can "Use this questionnaire again" be sent now, or must it wait in the outbox as a [QFormOp.REUSE]?
 * It waits with no signal, while the source (or the workshop it is for) exists only on this phone,
 * and while any earlier step for the source is still queued — so the copy includes those steps.
 * Pure; pinned by `QuestionnaireReuseOutboxTest`.
 */
fun reuseCanSendNow(
    sourceId: String,
    designWorkshopId: String?,
    map: Map<String, String>,
    pendingForSource: Int,
    online: Boolean,
): Boolean =
    online && pendingForSource == 0 &&
        !isTempId(resolveTempId(sourceId, map)) && !isTempId(resolveTempId(designWorkshopId, map))

/**
 * The form as the designer should see it: the server's copy with every step still waiting on this
 * phone applied on top — new sections and questions drawn under their temporary ids, renames,
 * edits and removals applied. Pure. Pinned by `QuestionnaireAuthoringTest`.
 */
fun applyPendingAuthoring(form: CustomQuestionnaireDto, ops: List<QFormOp>, map: Map<String, String>): CustomQuestionnaireDto {
    var sections = form.sections
    var title = form.title
    var description = form.description
    fun sameId(a: String?, b: String?) = a != null && b != null && (a == b || resolveTempId(a, map) == resolveTempId(b, map))
    for (op in ops) {
        when (op.op) {
            QFormOp.ADD_SECTION -> if (sections.none { sameId(it.id, op.tempId) }) {
                sections = sections + CustomSectionDto(
                    id = op.tempId ?: continue,
                    title = op.title.orEmpty(),
                    sortOrder = (sections.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                )
            }
            QFormOp.RENAME_SECTION -> sections = sections.map {
                if (sameId(it.id, op.sectionId)) it.copy(title = op.title ?: it.title) else it
            }
            QFormOp.ADD_QUESTION -> sections = sections.map { section ->
                if (!sameId(section.id, op.sectionId) || section.questions.any { sameId(it.id, op.tempId) }) section
                else section.copy(
                    questions = section.questions + CustomQuestionDto(
                        id = op.tempId ?: return@map section,
                        prompt = op.prompt.orEmpty(),
                        helpText = op.helpText,
                        isRequired = op.isRequired ?: false,
                        sortOrder = (section.questions.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    )
                )
            }
            QFormOp.EDIT_QUESTION -> sections = sections.map { section ->
                section.copy(questions = section.questions.map { q ->
                    if (!sameId(q.id, op.questionId)) q
                    else q.copy(
                        prompt = op.prompt ?: q.prompt,
                        helpText = if (op.changeHelpText) op.helpText else q.helpText,
                        isRequired = op.isRequired ?: q.isRequired,
                    )
                })
            }
            QFormOp.REMOVE_QUESTION -> sections = sections.map { section ->
                section.copy(questions = section.questions.filterNot { sameId(it.id, op.questionId) })
            }
            QFormOp.RENAME_QUESTIONNAIRE -> {
                title = op.title ?: title
                if (op.changeDescription) description = op.description
            }
        }
    }
    return form.copy(
        title = title,
        description = description,
        sections = sections,
        questionCount = sections.sumOf { s -> s.questions.count { it.isActive } },
    )
}

/**
 * The section a create just made, out of the whole questionnaire the route answers with: the one
 * titled as asked that no earlier temporary id already claims, last by sort order. Pure.
 */
fun createdSectionId(result: CustomQuestionnaireDto, title: String, claimed: Collection<String>): String? =
    result.sections
        .filter { it.title.trim() == title.trim() && it.id !in claimed }
        .maxByOrNull { it.sortOrder }?.id

/** The question a create just made in [sectionId], by the same rule as [createdSectionId]. Pure. */
fun createdQuestionId(result: CustomQuestionnaireDto, sectionId: String, prompt: String, claimed: Collection<String>): String? =
    result.sections.firstOrNull { it.id == sectionId }?.questions
        ?.filter { it.prompt.trim() == prompt.trim() && it.id !in claimed }
        ?.maxByOrNull { it.sortOrder }?.id
