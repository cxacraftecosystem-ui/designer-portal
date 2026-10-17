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

/**
 * The transcripts of this workshop's recordings, as a copy the DEVICE keeps for its own report.
 *
 * ── WHAT THIS ENDS (DA-29) ────────────────────────────────────────────────────────────────────────
 *
 * Every template carries the transcript annexure and stage 20's "Include transcripts" switch turns
 * it on. The office's copy printed it (`report_annexures.append_transcript_annexure`); the phone's
 * copy printed nothing, because the text lives on `MediaFile.transcriptText` on the server and no
 * draft on this handset held it. The export screen said "Transcripts are added when generated
 * online" and the file handed to an officer in the field was short by every word the artisans said.
 *
 * The text was never far away. `GET /design-workshops/{id}` and `GET /design-workshops/{id}/stages`
 * both carry a `transcripts` map — `_transcripts_payload`, one `TranscriptItem.payload()` plus its
 * `text` per AUDIO id the stages reference, already filtered by who may read each recording — and
 * this client decoded neither. So no new request is made: the two reads this app already performs
 * while it has signal (the stage index on open, the report screen on open) now keep what they were
 * sent, and the report is built from that copy with no connection at all.
 *
 * ── WHAT IS KEPT, AND WHY [DwTranscriptCache.referencedIds] IS PART OF IT ─────────────────────────
 *
 * The items, field for field the server's `TranscriptItem`, so the annexure can be a transliteration
 * of the server's rather than a second opinion. And the AUDIO ids the server's copy of the stages
 * referenced when it was read, because a recording the phone's report needs and the cache does not
 * hold is one of two different things and the designer is told different things about each:
 *
 *  * NOT IN [DwTranscriptCache.referencedIds] — the server had not seen it when this copy was taken
 *    (recorded on this phone since, or never read here). It arrives with the next read, so the
 *    export screen says it "will be added when you're online".
 *  * IN [DwTranscriptCache.referencedIds] AND NOT IN [DwTranscriptCache.items] — the server saw it
 *    and refused to hand it over (another account's upload, or the file is gone). Waiting would not
 *    help, so the server's own sentence for that case is used instead.
 *
 * READ-ONLY EVIDENCE, like [DwQuestionnaireStore]: nothing here is ever sent back, and a corrupt file
 * is deleted rather than quarantined because it is a copy of something the server still holds.
 */

/**
 * One recording and its transcript — `report_annexures.TranscriptItem`, as the wire carries it.
 *
 * The SAME class is the wire shape and the stored shape: the server serialises one value object for
 * both its preview and its annexure for the reason its docstring gives, and two shapes here would
 * drift in exactly the way that one avoids.
 */
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
    /** `TranscriptItem.has_text`. */
    val hasText: Boolean get() = text.isNotBlank()

    /** `TranscriptItem.label`: the field's label, then the filename, then the id. */
    val label: String get() = fieldLabel.ifEmpty { filename.ifEmpty { mediaId } }
}

/** What this device holds for one workshop. Null from [DwTranscriptStore.load] means "never read". */
@Serializable
data class DwTranscriptCache(
    val workshopId: String = "",
    val items: List<DwTranscriptItem> = emptyList(),
    /** Every AUDIO id the server's stages referenced at [fetchedAt] — see the file header. */
    val referencedIds: List<String> = emptyList(),
    val fetchedAt: String = "",
)

/** `workshop_transcripts._media_ids_in`: one id, or a list of them. */
private fun mediaIdsIn(value: JsonElement?): List<String> = when (value) {
    is JsonPrimitive -> if (value.isString && value.content.isNotBlank()) listOf(value.content) else emptyList()
    is JsonArray -> value.mapNotNull { item ->
        (item as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && (it.isString || it.content.toLongOrNull() != null) }
            ?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
    else -> emptyList()
}

/** Where a recording is attached: stage key, entity key, field key. */
data class DwAudioReference(val stageKey: String, val entityKey: String, val fieldKey: String)

/**
 * `workshop_transcripts.audio_references`: media id -> where it is attached, for every AUDIO field.
 *
 * Walked from the registry, so a TEXT field holding something id-shaped is never mistaken for a
 * recording; first reference wins, as on the server. [valuesOf] answers the stored value maps of one
 * entity — the singleton's one map, or a collection's rows — so the same walk reads the phone's
 * draft and the server's stage buckets.
 */
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

/** [dwAudioReferences] over this device's draft — what the phone's report will print. */
fun dwAudioReferences(schema: SchemaResponse, draft: WorkshopDraft?): LinkedHashMap<String, DwAudioReference> =
    dwAudioReferences(schema) { stageKey, entity ->
        val stored = draft?.stages?.get(stageKey)
        when {
            stored == null -> emptyList()
            entity.cardinality == "SINGLETON" -> listOf(stored.values)
            else -> stored.rowsFor(entity.key).map { it.values }
        }
    }

/** [dwAudioReferences] over the server's stage buckets — what the server's copy referenced. */
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

/**
 * The copy to keep from one server read, or null when the read carried no `transcripts` key at all.
 *
 * NULL FOR AN ABSENT KEY, which is not the same as an empty map: an older server that never sent the
 * map would otherwise make every recording look refused, and replace a good copy with nothing.
 */
fun dwTranscriptCacheOf(
    workshopId: String,
    schema: SchemaResponse,
    stages: Map<String, StageBucketDto>,
    transcripts: Map<String, DwTranscriptItem>?,
): DwTranscriptCache? {
    if (transcripts == null || workshopId.isBlank()) return null
    return DwTranscriptCache(
        workshopId = workshopId,
        // The map is keyed by media id; an item that somehow lacks its own id takes the key's.
        items = transcripts.map { (id, item) -> if (item.mediaId.isBlank()) item.copy(mediaId = id) else item },
        referencedIds = dwAudioReferencesInBuckets(schema, stages).keys.toList(),
        fetchedAt = Instant.now().toString(),
    )
}

/**
 * Which of the draft's recordings the phone's annexure can print, and what to say about the rest.
 *
 * [items] are the cached items the draft references, in `load_transcript_items`' order — (stage
 * number, stage key, label, media id) — WITH and without text, because the server's annexure and its
 * warnings are both computed from that one list. [notOnPhone] counts the recordings this copy has
 * never heard of; [refused] those the server saw and would not hand over.
 */
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

/**
 * The line on the export screen for recordings this copy does not hold yet, or null when there are none.
 *
 * Concise on purpose: it says what will happen and when, and nothing about why.
 */
fun dwTranscriptsPendingLine(notOnPhone: Int): String? = when {
    notOnPhone <= 0 -> null
    notOnPhone == 1 -> "1 transcript will be added when you're online."
    else -> "$notOnPhone transcripts will be added when you're online."
}

object DwTranscriptStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One lock for the whole store: write-and-rename, as [DwQuestionnaireStore]. */
    private val mutex = Mutex()

    /** A process-lifetime mirror, written AFTER the file so a kill between them loses nothing. */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwTranscriptCache>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-transcripts").apply { mkdirs() }

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(context: Context, workshopId: String): File =
        File(rootDir(context), "${safeName(workshopId)}.json")

    suspend fun load(context: Context, workshopId: String): DwTranscriptCache? {
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

    /** Replace what this device holds. Only a successful server read reaches here. */
    suspend fun store(context: Context, cache: DwTranscriptCache): DwTranscriptCache {
        val workshopId = cache.workshopId
        if (workshopId.isBlank()) return cache
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                val temp = File(file.parentFile, "${file.name}.tmp")
                runCatching {
                    temp.writeText(json.encodeToString(DwTranscriptCache.serializer(), cache))
                    if (file.exists()) file.delete()
                    temp.renameTo(file)
                }.onFailure { runCatching { temp.delete() } }
            }
        }
        memory[workshopId] = cache
        return cache
    }

    /**
     * Keep what one server read carried, filed under [workshopId] — the id the report screen reads
     * back with, which for a workshop created offline is its LOCAL id and not the server's.
     *
     * Best-effort and silent: this is a copy, and failing to keep it must never fail the screen that
     * made the read.
     */
    suspend fun keep(
        context: Context,
        workshopId: String,
        schema: SchemaResponse,
        stages: Map<String, StageBucketDto>,
        transcripts: Map<String, DwTranscriptItem>?,
    ): DwTranscriptCache? {
        val cache = dwTranscriptCacheOf(workshopId, schema, stages, transcripts) ?: return null
        return runCatching { store(context, cache) }.getOrNull()
    }
}
