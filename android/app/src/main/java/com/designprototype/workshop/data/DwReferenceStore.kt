package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DwReferenceOption(
    val id: String = "",
    val label: String = "",
    @SerialName("sublabel") @JsonNames("hint") val hint: String = "",
    val filterValue: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
    val tentative: Boolean = false,
)

const val DW_LEGACY_REFERENCE_PAGE = 50

@Serializable
data class DwReferenceList(
    val model: String = "",
    val filteredBy: String = "",
    val items: List<DwReferenceOption> = emptyList(),
    val fetchedAt: String = "",
    val tentativeLabel: String = "",
    val complete: Boolean? = null,
) {
    val partial: Boolean
        get() = complete?.not() ?: (items.size >= DW_LEGACY_REFERENCE_PAGE)

    fun narrowedTo(parentValue: String): List<DwReferenceOption> {
        if (parentValue.isBlank()) return items
        return items.filter { it.filterValue.isBlank() || it.filterValue == parentValue }
    }
}

@Serializable
data class DwReferenceResponseDto(
    val model: String = "",
    val scope: String = "",
    val options: List<DwReferenceOption> = emptyList(),
    val truncated: Boolean = false,
    val scopedToWorkshop: Boolean = false,
    val filtered: Boolean = false,
    val outOfScope: Boolean = false,
    val outOfScopeOption: DwReferenceOption? = null,
    val tentativeFirst: Boolean = false,
    val tentativeLabel: String = "",
)

@Serializable
data class DwIdentityCandidateDto(
    val value: String = "",
    val kind: String = "",
    val confidence: Double = 0.0,
    val masked: String = "",
)

@Serializable
data class DwIdentityOcrDto(
    val aadhaarCandidates: List<DwIdentityCandidateDto> = emptyList(),
    val pehchanCandidates: List<DwIdentityCandidateDto> = emptyList(),
    val rejectedAadhaarCount: Int = 0,
    val provider: String = "",
    val requiresConfirmation: Boolean = true,
    val photograph: DwIdentityPhotographDto? = null,
)

object DwReferenceStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwReferenceList>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-references").apply { mkdirs() }

    private fun cacheOwner(scope: String, workshopId: String): String =
        if (scope.equals("WORKSHOP", ignoreCase = true)) workshopId else "ALL"

    fun cacheKey(model: String, scope: String, workshopId: String, filterValue: String): String {
        val owner = cacheOwner(scope, workshopId)
        val filter = filterValue.ifBlank { "_" }
        return listOf(model, owner, filter).joinToString("__") { safeName(it) }
    }

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(root: File, key: String): File = File(root, "$key.json")

    suspend fun load(context: Context, key: String): DwReferenceList? = load(rootDir(context), key)

    internal suspend fun load(root: File, key: String): DwReferenceList? {
        memory[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(root, key)
                if (!file.exists()) return@withLock null
                val text = runCatching { file.readText() }.getOrNull()
                if (text.isNullOrBlank()) return@withLock null
                runCatching { json.decodeFromString(DwReferenceList.serializer(), text) }
                    .onSuccess { memory[key] = it }
                    .onFailure { runCatching { file.delete() } }
                    .getOrNull()
            }
        }
    }

    suspend fun store(context: Context, key: String, list: DwReferenceList): DwReferenceList =
        store(rootDir(context), key, list)

    internal suspend fun store(root: File, key: String, list: DwReferenceList): DwReferenceList {
        val existing = load(root, key)
        if (list.items.isEmpty() && existing != null && existing.items.isNotEmpty()) return existing
        val stamped = list.copy(fetchedAt = Instant.now().toString())
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(root, key)
                val temp = File(file.parentFile, "${file.name}.tmp")
                runCatching {
                    temp.writeText(json.encodeToString(DwReferenceList.serializer(), stamped))
                    if (file.exists()) file.delete()
                    temp.renameTo(file)
                }.onFailure { runCatching { temp.delete() } }
            }
        }
        memory[key] = stamped
        return stamped
    }

    fun labelFor(id: String): String? {
        if (id.isBlank()) return null
        memory.values.forEach { list ->
            list.items.firstOrNull { it.id == id }?.let { return it.label.ifBlank { null } }
        }
        return null
    }

    suspend fun anyForModel(context: Context, model: String, scope: String, workshopId: String): DwReferenceList? =
        anyForModel(rootDir(context), model, scope, workshopId)

    internal suspend fun anyForModel(root: File, model: String, scope: String, workshopId: String): DwReferenceList? {
        val whole = load(root, cacheKey(model, scope, workshopId, ""))
        if (whole != null) return whole
        val prefix = safeName(model) + "__" + safeName(cacheOwner(scope, workshopId)) + "__"
        val merged = withContext(Dispatchers.IO) {
            runCatching { root.listFiles() }.getOrNull().orEmpty()
                .filter { it.name.startsWith(prefix) }
                .mapNotNull { file ->
                    runCatching {
                        json.decodeFromString(DwReferenceList.serializer(), file.readText())
                    }.getOrNull()
                }
        }
        if (merged.isEmpty()) return null
        return DwReferenceList(
            model = model,
            filteredBy = "",
            items = merged.flatMap { it.items }.distinctBy { it.id },
            fetchedAt = merged.mapNotNull { it.fetchedAt.takeIf(String::isNotBlank) }.minOrNull().orEmpty(),
            complete = merged.none { it.partial },
        )
    }
}

fun dwRefId(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> value.content
    else -> DwValues.text(value)
}
