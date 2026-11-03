package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class DwQuestionnaireAnswer(
    val prompt: String = "",
    val answerText: String = "",
    val notes: String = "",
    val sectionCode: String = "",
    val sectionTitle: String = "",
    val isRequired: Boolean = false,
    val isRetired: Boolean = false,
) {
    val hasAnswer: Boolean get() = answerText.isNotBlank()

    val prints: Boolean get() = hasAnswer || isRequired

    val sectionLabel: String
        get() = listOf(sectionCode, sectionTitle).filter { it.isNotEmpty() }.joinToString(" — ")
}

@Serializable
data class DwQuestionnaireSitting(
    val entryId: String = "",
    val title: String = "",
    val respondentName: String = "",
    val source: String = "",
    val notes: String = "",
    val recordedAt: String = "",
    val recordedBy: String = "",
    val answers: List<DwQuestionnaireAnswer> = emptyList(),
) {
    val label: String
        get() = respondentName.trim().ifEmpty { title.trim() }
            .ifEmpty { "Sitting ${entryId.take(8)}" }

    val printedAnswers: List<DwQuestionnaireAnswer> get() = answers.filter { it.prints }
    val answeredCount: Int get() = answers.count { it.hasAnswer }
    val hasAnswers: Boolean get() = answeredCount > 0
}

@Serializable
data class DwQuestionnaireItem(
    val questionnaireId: String = "",
    val title: String = "",
    val description: String = "",
    val version: Int = 1,
    val sourceFilename: String = "",
    val questionCount: Int = 0,
    val kind: String = "",
    val answersHeld: Boolean = false,
    val sittings: List<DwQuestionnaireSitting> = emptyList(),
) {
    val printedSittings: List<DwQuestionnaireSitting> get() = sittings.filter { it.hasAnswers }
    val hasAnswers: Boolean get() = printedSittings.isNotEmpty()
    val answeredTotal: Int get() = printedSittings.sumOf { it.answeredCount }
}

@Serializable
data class DwQuestionnaireCache(
    val workshopId: String = "",
    val items: List<DwQuestionnaireItem> = emptyList(),
    val complete: Boolean = false,
    val fetchedAt: String = "",
    val ownerUserId: String = "",
) {
    val printedItems: List<DwQuestionnaireItem> get() = items.filter { it.hasAnswers }

    val unreadItems: List<DwQuestionnaireItem> get() = items.filter { !it.answersHeld }

    val emptyItems: List<DwQuestionnaireItem>
        get() = items.filter { it.answersHeld && !it.hasAnswers }
}

enum class DwQuestionnaireCopy {
    UNKNOWN,

    NONE_ATTACHED,

    ATTACHED,
}

fun dwQuestionnaireCopy(cache: DwQuestionnaireCache?): DwQuestionnaireCopy = when {
    cache == null -> DwQuestionnaireCopy.UNKNOWN
    cache.items.isNotEmpty() -> DwQuestionnaireCopy.ATTACHED
    cache.complete -> DwQuestionnaireCopy.NONE_ATTACHED
    else -> DwQuestionnaireCopy.UNKNOWN
}

fun dwQuestionnaireItemOf(dto: CustomQuestionnaireDto): DwQuestionnaireItem {
    val pairs = dto.sections.flatMap { section -> section.questions.map { section to it } }
    return DwQuestionnaireItem(
        questionnaireId = dto.id,
        title = dto.title,
        description = dto.description.orEmpty(),
        version = dto.version,
        sourceFilename = dto.sourceFilename.orEmpty(),
        questionCount = pairs.count { (_, question) -> question.isActive },
        kind = dto.kind.orEmpty(),
        answersHeld = true,
        sittings = dto.entries.map { entry ->
            val recorded = entry.answers.associateBy { it.questionId }
            DwQuestionnaireSitting(
                entryId = entry.id,
                title = entry.title,
                respondentName = entry.respondentName.orEmpty(),
                source = entry.source.orEmpty(),
                notes = entry.notes.orEmpty(),
                recordedAt = entry.createdAt.orEmpty(),
                recordedBy = entry.createdByName.orEmpty(),
                answers = pairs.map { (section, question) ->
                    val answer = recorded[question.id]
                    DwQuestionnaireAnswer(
                        prompt = question.prompt,
                        answerText = answer?.answerText.orEmpty(),
                        notes = answer?.notes.orEmpty(),
                        sectionCode = section.code,
                        sectionTitle = section.title,
                        isRequired = question.isRequired,
                        isRetired = !question.isActive,
                    )
                },
            )
        },
    )
}

private fun dwUnreadItemOf(summary: CustomQuestionnaireSummaryDto) = DwQuestionnaireItem(
    questionnaireId = summary.id,
    title = summary.title,
    description = summary.description.orEmpty(),
    version = summary.version,
    sourceFilename = summary.sourceFilename.orEmpty(),
    kind = summary.kind.orEmpty(),
    answersHeld = false,
)

suspend fun WorkshopRepository.designWorkshopQuestionnaires(
    workshopId: String,
): DwQuestionnaireCache {
    val listing: PagedListing<CustomQuestionnaireSummaryDto> = walkPagedListing(
        idOf = { it.id },
    ) { page, size ->
        customQuestionnaires(
            page = page,
            pageSize = size,
            designWorkshopId = workshopId,
            activeOnly = true,
        )
    }
    val summaries = listing.items.sortedBy { it.createdAt.orEmpty() }
    val items = summaries.map { summary ->
        runCatching { customQuestionnaire(summary.id, includeRetired = true) }
            .map { dwQuestionnaireItemOf(it) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                dwUnreadItemOf(summary)
            }
    }
    return DwQuestionnaireCache(
        workshopId = workshopId,
        items = items,
        complete = !listing.truncated,
        fetchedAt = Instant.now().toString(),
    )
}

fun dwCacheReadableBy(ownerUserId: String, viewerId: String?): Boolean =
    !viewerId.isNullOrBlank() && ownerUserId.isNotBlank() && ownerUserId == viewerId

object DwQuestionnaireStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwQuestionnaireCache>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-questionnaires").apply { mkdirs() }

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(context: Context, workshopId: String): File =
        File(rootDir(context), "${safeName(workshopId)}.json")

    suspend fun load(context: Context, workshopId: String, viewerId: String?): DwQuestionnaireCache? =
        loadAny(context, workshopId)?.takeIf { dwCacheReadableBy(it.ownerUserId, viewerId) }

    private suspend fun loadAny(context: Context, workshopId: String): DwQuestionnaireCache? {
        if (workshopId.isBlank()) return null
        memory[workshopId]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                if (!file.exists()) return@withLock null
                val text = runCatching { file.readText() }.getOrNull()
                if (text.isNullOrBlank()) return@withLock null
                runCatching { json.decodeFromString(DwQuestionnaireCache.serializer(), text) }
                    .onSuccess { memory[workshopId] = it }
                    .onFailure { runCatching { file.delete() } }
                    .getOrNull()
            }
        }
    }

    suspend fun store(context: Context, cache: DwQuestionnaireCache, ownerUserId: String?): DwQuestionnaireCache {
        val workshopId = cache.workshopId
        if (workshopId.isBlank() || ownerUserId.isNullOrBlank()) return cache
        val stamped = (if (cache.fetchedAt.isNotBlank()) cache else cache.copy(fetchedAt = Instant.now().toString()))
            .copy(ownerUserId = ownerUserId)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                val temp = File(file.parentFile, "${file.name}.tmp")
                runCatching {
                    temp.writeText(json.encodeToString(DwQuestionnaireCache.serializer(), stamped))
                    if (file.exists()) file.delete()
                    temp.renameTo(file)
                }.onFailure { runCatching { temp.delete() } }
            }
        }
        memory[workshopId] = stamped
        return stamped
    }

    suspend fun mergeQuestionnaire(
        context: Context,
        dto: CustomQuestionnaireDto,
        ownerUserId: String?,
    ): DwQuestionnaireCache? {
        val workshopId = dto.designWorkshopId?.takeIf { it.isNotBlank() } ?: return null
        if (!dto.isActive) return null
        val item = dwQuestionnaireItemOf(dto)
        val existing = load(context, workshopId, ownerUserId)
        val kept = existing?.items.orEmpty().filter { it.questionnaireId != item.questionnaireId }
        val stillComplete = existing != null &&
            existing.complete &&
            kept.size == existing.items.size - 1
        return store(
            context,
            DwQuestionnaireCache(
                workshopId = workshopId,
                items = kept + item,
                complete = stillComplete,
                fetchedAt = Instant.now().toString(),
            ),
            ownerUserId,
        )
    }
}

fun dwQuestionnaireWarnings(cache: DwQuestionnaireCache?): List<String> {
    if (cache == null) return emptyList()
    val said = ArrayList<String>()
    val empty = cache.emptyItems
    if (empty.isNotEmpty()) {
        val names = empty.take(3).joinToString(", ") { it.title.ifBlank { it.questionnaireId } }
        val more = if (empty.size > 3) "…" else ""
        said += "${empty.size} ${if (empty.size == 1) "questionnaire" else "questionnaires"} attached to this workshop " +
            "${if (empty.size == 1) "has" else "have"} no recorded answers " +
            "and ${if (empty.size == 1) "was" else "were"} left out of the questionnaire annexure ($names$more)."
    }
    val unread = cache.unreadItems
    if (unread.isNotEmpty()) {
        val names = unread.take(3).joinToString(", ") { it.title.ifBlank { it.questionnaireId } }
        val more = if (unread.size > 3) "…" else ""
        said += "${plural(unread.size, "questionnaire")} attached to this workshop ${if (unread.size == 1) "is" else "are"} named in the annexure " +
            "without their answers ($names$more). Open the questionnaire on this phone once while " +
            "you have a connection, then export again to include them."
    }
    return said
}
