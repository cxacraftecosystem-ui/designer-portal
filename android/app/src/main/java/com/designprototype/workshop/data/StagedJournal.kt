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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
internal data class StagedObject(
    val objectKey: String,
    val owner: String,
    val checksum: String? = null,
    val uploadId: String? = null
)

object StagedJournal {
    private val owner = UUID.randomUUID().toString()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    private fun file(dir: File): File = File(dir, "staged-objects.json")

    private fun dirOf(context: Context): File = context.filesDir

    internal const val RECOVERED_OWNER = "recovered-from-damaged-journal"

    @Volatile
    private var unrescued: String? = null

    private fun damagedSibling(file: File): File =
        File(file.parentFile, "staged-objects.damaged-${System.currentTimeMillis()}.json")

    internal fun read(dir: File, keptFile: (File) -> File = ::damagedSibling): List<StagedObject> {
        val file = file(dir)
        if (unrescued == file.path) unrescued = null
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { return quarantine(dir, file, keptFile(file), emptyList()) }
        if (text.isBlank()) return quarantine(dir, file, keptFile(file), emptyList())
        return runCatching { json.decodeFromString<List<StagedObject>>(text) }
            .getOrElse { quarantine(dir, file, keptFile(file), salvage(text)) }
    }

    private fun quarantine(
        dir: File,
        file: File,
        kept: File,
        recovered: List<StagedObject>
    ): List<StagedObject> {
        val orphaned = recovered.map { it.copy(owner = RECOVERED_OWNER) }
        val setAside = runCatching {
            Files.move(file.toPath(), kept.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!setAside) {
            unrescued = file.path
            return orphaned
        }
        runCatching { write(dir, orphaned) }
        return orphaned
    }

    private fun salvage(text: String): List<StagedObject> {
        val recovered = ArrayList<StagedObject>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching { json.decodeFromString<StagedObject>(text.substring(start, index + 1)) }
                            .onSuccess { recovered.add(it) }
                        start = -1
                    }
                    if (depth < 0) return recovered
                }
            }
        }
        return recovered
    }

    internal fun write(dir: File, entries: List<StagedObject>) {
        val target = file(dir)
        if (unrescued == target.path) {
            throw IOException(
                "The staged-object journal ${target.name} is damaged and couldn't be set aside; it won't be overwritten."
            )
        }
        if (entries.isEmpty()) {
            target.delete()
            return
        }
        val temp = File(target.parentFile, "${target.name}.writing")
        val bytes = json.encodeToString(entries).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    suspend fun record(
        context: Context,
        objectKey: String,
        checksum: String? = null,
        uploadId: String? = null
    ): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                appContext = context.applicationContext
                val dir = dirOf(context)
                val entries = read(dir)
                val existing = entries.firstOrNull { it.objectKey == objectKey }
                write(
                    dir,
                    entries.filterNot { it.objectKey == objectKey } + StagedObject(
                        objectKey = objectKey,
                        owner = owner,
                        checksum = checksum ?: existing?.checksum,
                        uploadId = uploadId ?: existing?.uploadId
                    )
                )
            }
        }

    suspend fun checksumFor(objectKey: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock null
            read(dirOf(context)).firstOrNull { it.objectKey == objectKey }?.checksum
        }
    }

    suspend fun drop(objectKey: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock
            val dir = dirOf(context)
            val entries = read(dir)
            if (entries.any { it.objectKey == objectKey }) write(dir, entries.filterNot { it.objectKey == objectKey })
        }
    }

    suspend fun sweep(context: Context, delete: suspend (objectKey: String, uploadId: String?) -> Boolean): Int {
        val orphans = withContext(Dispatchers.IO) {
            mutex.withLock {
                appContext = context.applicationContext
                read(dirOf(context)).filter { it.owner != owner }.map { it.objectKey to it.uploadId }
            }
        }
        var reclaimed = 0
        for ((objectKey, uploadId) in orphans) {
            if (delete(objectKey, uploadId)) {
                drop(objectKey)
                reclaimed++
            }
        }
        return reclaimed
    }
}
