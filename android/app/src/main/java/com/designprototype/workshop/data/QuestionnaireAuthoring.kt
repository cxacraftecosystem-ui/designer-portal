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

const val OFFLINE_QFORM_OP = "questionnaireAuthoring"

const val TEMP_ID_PREFIX = "tmp-"

fun newTempId(): String = TEMP_ID_PREFIX + UUID.randomUUID().toString()

fun isTempId(id: String?): Boolean = id != null && id.startsWith(TEMP_ID_PREFIX)

@Serializable
data class QFormOp(
    val op: String,
    val questionnaireId: String,
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
    val designWorkshopId: String? = null,
) {
    companion object {
        const val ADD_SECTION = "addSection"
        const val RENAME_SECTION = "renameSection"
        const val ADD_QUESTION = "addQuestion"
        const val EDIT_QUESTION = "editQuestion"
        const val REMOVE_QUESTION = "removeQuestion"
        const val RENAME_QUESTIONNAIRE = "renameQuestionnaire"
        const val REUSE = "reuse"
    }
}

class QFormParentMissing(message: String) : IllegalStateException(message)

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

fun adoptionIdempotencyKey(rowId: String, vararg step: String): String {
    val clean = (listOf("adopt", rowId) + step).joinToString("-") { part -> part.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' } }
    return clean.padEnd(8, '0').take(128)
}

fun queuedAuthoringMessage(online: Boolean, waitsForParent: Boolean, earlierRefused: Boolean): String = when {
    !online -> "Saved on this phone; added when online."
    earlierRefused -> "Saved on this phone. An earlier change was not accepted; open Saved on this phone to fix it."
    waitsForParent -> "Saved on this phone; added once the questionnaire has uploaded."
    else -> "Saved on this phone; added after the changes queued before it."
}

fun resolveTempId(id: String?, map: Map<String, String>): String? = if (isTempId(id)) map[id] ?: id else id

fun reuseCanSendNow(
    sourceId: String,
    designWorkshopId: String?,
    map: Map<String, String>,
    pendingForSource: Int,
    online: Boolean,
): Boolean =
    online && pendingForSource == 0 &&
        !isTempId(resolveTempId(sourceId, map)) && !isTempId(resolveTempId(designWorkshopId, map))

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

fun createdSectionId(result: CustomQuestionnaireDto, title: String, claimed: Collection<String>): String? =
    result.sections
        .filter { it.title.trim() == title.trim() && it.id !in claimed }
        .maxByOrNull { it.sortOrder }?.id

fun createdQuestionId(result: CustomQuestionnaireDto, sectionId: String, prompt: String, claimed: Collection<String>): String? =
    result.sections.firstOrNull { it.id == sectionId }?.questions
        ?.filter { it.prompt.trim() == prompt.trim() && it.id !in claimed }
        ?.maxByOrNull { it.sortOrder }?.id
