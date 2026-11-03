package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

@Serializable
data class DwTranscriptItem(
    val mediaId: String = "",
    val stageKey: String = "",
    val stageNumber: Int = 0,
    val stageTitle: String = "",
    val entityKey: String = "",
    val fieldKey: String = "",
    val fieldLabel: String = "",
    val filename: String = "",
    val recordedAt: String = "",
    val durationSeconds: Int? = null,
    val status: String = "",
    val text: String = "",
) {
    val hasText: Boolean get() = text.isNotBlank()

    val label: String get() = fieldLabel.ifEmpty { filename.ifEmpty { mediaId } }
}

@Serializable
data class DwTranscriptCache(
    val workshopId: String = "",
    val items: List<DwTranscriptItem> = emptyList(),
    val referencedIds: List<String> = emptyList(),
    val fetchedAt: String = "",
    val ownerUserId: String = "",
)

private fun mediaIdsIn(value: JsonElement?): List<String> = when (value) {
    is JsonPrimitive -> if (value.isString && value.content.isNotBlank()) listOf(value.content) else emptyList()
    is JsonArray -> value.mapNotNull { item ->
        (item as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && (it.isString || it.content.toLongOrNull() != null) }
            ?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
    else -> emptyList()
}

data class DwAudioReference(val stageKey: String, val entityKey: String, val fieldKey: String)

fun dwAudioReferences(
    schema: SchemaResponse,
    valuesOf: (stageKey: String, entity: EntityDto) -> List<Map<String, JsonElement>>,
): LinkedHashMap<String, DwAudioReference> {
    val found = LinkedHashMap<String, DwAudioReference>()
    for (stage in schema.stages.sortedBy { it.number }) {
        for (entity in stage.entities) {
            val audio = entity.fields.filter { DwFieldType.of(it.type) == DwFieldType.AUDIO }
            if (audio.isEmpty()) continue
            for (values in valuesOf(stage.key, entity)) {
                for (field in audio) {
                    for (id in mediaIdsIn(values[field.key])) {
                        found.putIfAbsent(id, DwAudioReference(stage.key, entity.key, field.key))
                    }
                }
            }
        }
    }
    return found
}

fun dwAudioReferences(schema: SchemaResponse, draft: WorkshopDraft?): LinkedHashMap<String, DwAudioReference> =
    dwAudioReferences(schema) { stageKey, entity ->
        val stored = draft?.stages?.get(stageKey)
        when {
            stored == null -> emptyList()
            entity.cardinality == "SINGLETON" -> listOf(stored.values)
            else -> stored.rowsFor(entity.key).map { it.values }
        }
    }

fun dwAudioReferencesInBuckets(
    schema: SchemaResponse,
    stages: Map<String, StageBucketDto>,
): LinkedHashMap<String, DwAudioReference> =
    dwAudioReferences(schema) { stageKey, entity ->
        val bucket = stages[stageKey]
        when {
            bucket == null -> emptyList()
            entity.cardinality == "SINGLETON" -> listOf(bucket.singleton)
            else -> bucket.collections[entity.key].orEmpty()
        }
    }

fun dwTranscriptCacheOf(
    workshopId: String,
    schema: SchemaResponse,
    stages: Map<String, StageBucketDto>,
    transcripts: Map<String, DwTranscriptItem>?,
): DwTranscriptCache? {
    if (transcripts == null || workshopId.isBlank()) return null
    return DwTranscriptCache(
        workshopId = workshopId,
        items = transcripts.map { (id, item) -> if (item.mediaId.isBlank()) item.copy(mediaId = id) else item },
        referencedIds = dwAudioReferencesInBuckets(schema, stages).keys.toList(),
        fetchedAt = Instant.now().toString(),
    )
}

data class DwTranscriptSelection(
    val items: List<DwTranscriptItem>,
    val notOnPhone: Int,
    val refused: Int,
)

fun dwTranscriptSelection(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    cache: DwTranscriptCache?,
): DwTranscriptSelection {
    val references = dwAudioReferences(schema, draft)
    val byId = cache?.items.orEmpty().associateBy { it.mediaId }
    val seen = cache?.referencedIds.orEmpty().toSet()
    val items = ArrayList<DwTranscriptItem>()
    var notOnPhone = 0
    var refused = 0
    for (id in references.keys) {
        val item = byId[id]
        when {
            item != null -> items += item
            id in seen -> refused += 1
            else -> notOnPhone += 1
        }
    }
    items.sortWith(compareBy<DwTranscriptItem>({ it.stageNumber }, { it.stageKey }, { it.label }, { it.mediaId }))
    return DwTranscriptSelection(items, notOnPhone, refused)
}

fun dwTranscriptsPendingLine(notOnPhone: Int): String? = when {
    notOnPhone <= 0 -> null
    notOnPhone == 1 -> "1 transcript will be added when you're online."
    else -> "$notOnPhone transcripts will be added when you're online."
}

object DwTranscriptStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwTranscriptCache>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-transcripts").apply { mkdirs() }

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(context: Context, workshopId: String): File =
        File(rootDir(context), "${safeName(workshopId)}.json")

    suspend fun load(context: Context, workshopId: String, viewerId: String?): DwTranscriptCache? =
        loadAny(context, workshopId)?.takeIf { dwCacheReadableBy(it.ownerUserId, viewerId) }

    private suspend fun loadAny(context: Context, workshopId: String): DwTranscriptCache? {
        if (workshopId.isBlank()) return null
        memory[workshopId]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                if (!file.exists()) return@withLock null
                val text = runCatching { file.readText() }.getOrNull()
                if (text.isNullOrBlank()) return@withLock null
                runCatching { json.decodeFromString(DwTranscriptCache.serializer(), text) }
                    .onSuccess { memory[workshopId] = it }
                    .onFailure { runCatching { file.delete() } }
                    .getOrNull()
            }
        }
    }

    suspend fun store(context: Context, cache: DwTranscriptCache, ownerUserId: String?): DwTranscriptCache {
        if (cache.workshopId.isBlank() || ownerUserId.isNullOrBlank()) return cache
        val owned = cache.copy(ownerUserId = ownerUserId)
        val workshopId = owned.workshopId
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                val temp = File(file.parentFile, "${file.name}.tmp")
                runCatching {
                    temp.writeText(json.encodeToString(DwTranscriptCache.serializer(), owned))
                    if (file.exists()) file.delete()
                    temp.renameTo(file)
                }.onFailure { runCatching { temp.delete() } }
            }
        }
        memory[workshopId] = owned
        return owned
    }

    suspend fun keep(
        context: Context,
        workshopId: String,
        schema: SchemaResponse,
        stages: Map<String, StageBucketDto>,
        transcripts: Map<String, DwTranscriptItem>?,
        ownerUserId: String?,
    ): DwTranscriptCache? {
        val cache = dwTranscriptCacheOf(workshopId, schema, stages, transcripts) ?: return null
        return runCatching { store(context, cache, ownerUserId) }.getOrNull()
    }
}
