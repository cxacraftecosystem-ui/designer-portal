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

@Serializable
data class CachedQuestionnaireForm(
    val schemaVersion: Int = QUESTIONNAIRE_FORM_CACHE_VERSION,
    val fetchedAt: String = "",
    val includeRetired: Boolean = false,
    val version: Int = 0,
    val form: CustomQuestionnaireDto,
    val ownerUserId: String = "",
)

fun formCacheReadableBy(ownerUserId: String, viewerId: String?): Boolean =
    !viewerId.isNullOrBlank() && ownerUserId.isNotBlank() && ownerUserId == viewerId

const val QUESTIONNAIRE_FORM_CACHE_VERSION = 1

data class QuestionnaireFormRead(
    val form: CustomQuestionnaireDto,
    val fromCache: Boolean,
    val cachedAt: String? = null,
)

object DwQuestionnaireFormCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private fun dir(context: Context): File =
        File(context.filesDir, "questionnaire-forms").apply { mkdirs() }

    private fun file(context: Context, id: String, includeRetired: Boolean): File {
        val key = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        return File(dir(context), "$key-${if (includeRetired) "all" else "active"}.json")
    }

    suspend fun put(
        context: Context,
        form: CustomQuestionnaireDto,
        includeRetired: Boolean,
        ownerUserId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (ownerUserId.isNullOrBlank()) return@withContext false
        mutex.withLock {
            runCatching {
                val target = file(context, form.id, includeRetired)
                val temp = File(target.parentFile, "${target.name}.writing")
                val bytes = json.encodeToString(
                    CachedQuestionnaireForm(
                        fetchedAt = Instant.now().toString(),
                        includeRetired = includeRetired,
                        version = form.version,
                        form = form,
                        ownerUserId = ownerUserId,
                    )
                ).toByteArray()
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    runCatching { temp.delete() }
                    throw IOException("Unable to replace ${target.name}")
                }
                true
            }.getOrDefault(false)
        }
    }

    suspend fun get(
        context: Context,
        id: String,
        includeRetired: Boolean,
        viewerId: String?,
    ): CachedQuestionnaireForm? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = file(context, id, includeRetired)
            if (!target.isFile) return@withLock null
            val cached = runCatching {
                json.decodeFromString<CachedQuestionnaireForm>(target.readText())
            }.getOrNull() ?: return@withLock null
            if (cached.schemaVersion > QUESTIONNAIRE_FORM_CACHE_VERSION) return@withLock null
            if (!formCacheReadableBy(cached.ownerUserId, viewerId)) return@withLock null
            cached
        }
    }

    suspend fun getEither(
        context: Context,
        id: String,
        preferred: Boolean,
        viewerId: String?,
    ): CachedQuestionnaireForm? = get(context, id, preferred, viewerId) ?: get(context, id, !preferred, viewerId)

    suspend fun forget(context: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file(context, id, includeRetired = true).delete() }
            runCatching { file(context, id, includeRetired = false).delete() }
            Unit
        }
    }

    suspend fun forgetAll(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { dir(context).listFiles()?.forEach { file -> file.delete() } }
            Unit
        }
    }
}

fun cachedQuestionnaireNotice(cachedAt: String?, version: Int): String {
    val whenPart = readableStamp(cachedAt)?.let { " on $it" }.orEmpty()
    return "You're reading the copy this phone downloaded$whenPart (version $version). Read-only offline — ANSWERS CANNOT BE SAVED without a connection."
}
