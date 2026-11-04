package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

data class CachedRead<T>(val value: T, val cachedAt: String? = null) {
    val fromCache: Boolean get() = cachedAt != null
}

@Serializable
internal data class ReadCacheFile(
    val schema: Int = 1,
    val fetchedAt: String = "",
    val payload: JsonElement,
)

object ReadCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val lock = Any()

    fun root(context: Context): File = File(context.filesDir, "read-cache")

    internal fun fileFor(root: File, ownerUserId: String?, key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        return File(File(root, FormDraftStore.ownerSegment(ownerUserId)), "$safe-$digest.json")
    }

    suspend fun <T> read(
        context: Context,
        key: String,
        serializer: KSerializer<T>,
        fetch: suspend () -> T,
    ): CachedRead<T> {
        val owner = runCatching { TokenStore(context).getUser()?.id }.getOrNull()
        return read(root(context), owner, key, serializer, fetch)
    }

    internal suspend fun <T> read(
        root: File,
        ownerUserId: String?,
        key: String,
        serializer: KSerializer<T>,
        fetch: suspend () -> T,
    ): CachedRead<T> {
        val live = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!readCacheMayServe(e)) throw e
            val cached = withContext(Dispatchers.IO) { load(root, ownerUserId, key, serializer) } ?: throw e
            return CachedRead(cached.first, cachedAt = cached.second)
        }
        withContext(Dispatchers.IO) { runCatching { store(root, ownerUserId, key, serializer, live) } }
        return CachedRead(live)
    }

    internal fun <T> store(root: File, ownerUserId: String?, key: String, serializer: KSerializer<T>, value: T) {
        synchronized(lock) {
            val target = fileFor(root, ownerUserId, key)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.writing")
            val bytes = json.encodeToString(
                ReadCacheFile.serializer(),
                ReadCacheFile(fetchedAt = Instant.now().toString(), payload = json.encodeToJsonElement(serializer, value)),
            ).toByteArray()
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
                }
            } catch (e: Throwable) {
                runCatching { temp.delete() }
                throw e
            }
        }
    }

    internal fun <T> load(root: File, ownerUserId: String?, key: String, serializer: KSerializer<T>): Pair<T, String>? =
        synchronized(lock) {
            val file = fileFor(root, ownerUserId, key)
            if (!file.isFile) return@synchronized null
            runCatching {
                val stored = json.decodeFromString(ReadCacheFile.serializer(), file.readText())
                if (stored.schema > 1) return@runCatching null
                json.decodeFromJsonElement(serializer, stored.payload) to stored.fetchedAt
            }.getOrNull()
        }

    suspend fun evictAll(context: Context) = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching { root(context).deleteRecursively() } }
        Unit
    }
}

internal fun readCacheMayServe(error: Throwable): Boolean = when (error) {
    is HttpException -> error.code() == 408 || error.code() == 429 || error.code() >= 500
    is IOException -> true
    else -> false
}

fun readCacheNotice(cachedAt: String?): String {
    val stamp = cachedAt?.let { iso ->
        runCatching {
            val zoned = Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
            java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(zoned)
        }.getOrNull()
    }
    return "No connection — showing the copy saved on this phone" +
        (stamp?.let { " on $it" } ?: "") + ". It may be out of date."
}
