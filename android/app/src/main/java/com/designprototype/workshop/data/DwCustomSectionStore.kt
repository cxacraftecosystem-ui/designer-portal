package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

object DwCustomSectionStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwCustomCache>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-custom").apply { mkdirs() }

    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(context: Context, workshopId: String): File =
        File(rootDir(context), "${safeName(workshopId)}.json")

    suspend fun load(context: Context, workshopId: String): DwCustomCache? {
        if (workshopId.isBlank()) return null
        memory[workshopId]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                if (!file.exists()) return@withLock null
                val text = runCatching { file.readText() }.getOrNull()
                if (text.isNullOrBlank()) {
                    runCatching { file.delete() }
                    return@withLock null
                }
                runCatching { json.decodeFromString(DwCustomCache.serializer(), text) }
                    .onSuccess { memory[workshopId] = it }
                    .onFailure { runCatching { file.delete() } }
                    .getOrNull()
            }
        }
    }

    suspend fun store(context: Context, cache: DwCustomCache): DwCustomCache {
        val workshopId = cache.workshopId
        if (workshopId.isBlank()) return cache
        val stamped =
            if (cache.fetchedAt.isNotBlank()) cache else cache.copy(fetchedAt = Instant.now().toString())
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = fileFor(context, workshopId)
                val temp = File(target.parentFile, "${target.name}.writing")
                val bytes = json.encodeToString(DwCustomCache.serializer(), stamped).toByteArray()
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
        }
        memory[workshopId] = stamped
        return stamped
    }

    suspend fun forget(context: Context, workshopId: String) {
        if (workshopId.isBlank()) return
        withContext(Dispatchers.IO) {
            mutex.withLock { runCatching { fileFor(context, workshopId).delete() } }
        }
        memory.remove(workshopId)
    }
}

suspend fun WorkshopRepository.designWorkshopCustomSections(
    context: Context,
    workshopId: String,
    cacheAs: String = workshopId,
): DwCustomCache {
    val payload = designWorkshopCustomSectionsRaw(workshopId)
    return DwCustomSectionStore.store(
        context,
        DwCustomCache(
            workshopId = cacheAs,
            customSchemaVersion = payload.customSchemaVersion,
            sections = payload.sections,
            complete = true,
            fetchedAt = Instant.now().toString(),
        ),
    )
}

suspend fun WorkshopRepository.dwCustomDefinition(
    context: Context,
    workshopId: String,
    remoteId: String?,
): DwCustomCache? {
    if (remoteId != null) {
        runCatching { designWorkshopCustomSections(context, remoteId, cacheAs = workshopId) }
            .onSuccess { fresh -> return fresh }
    }
    return DwCustomSectionStore.load(context, workshopId)
}
