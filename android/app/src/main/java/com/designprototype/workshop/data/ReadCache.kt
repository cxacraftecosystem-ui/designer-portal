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

/**
 * THE LAST COPY OF A READ-ONLY LIST, SO A SCREEN OPENED WITH NO SIGNAL SHOWS IT INSTEAD OF FAILING.
 *
 * The rule (owner, 2026-10-16): a read-only screen shows the last cached data offline. The pattern is
 * the one [WorkshopRepository.customQuestionnaireCached] set, generalised:
 *
 *  * LIVE FIRST, ALWAYS. A successful read is returned and written to disk; the cache is never
 *    preferred to an answer the server can give.
 *  * THE DISK ONLY WHEN NOTHING REACHED THE SERVER — no signal, a dropped socket, a 408/429, a 5xx.
 *    When the server ANSWERED no (401/403/404/422), that answer stands: serving a copy over it would
 *    show somebody a list their access to was just withdrawn, from the app's own pocket.
 *  * IT SAYS SO. The result carries [CachedRead.cachedAt] and the screen prints when the copy is from
 *    ([readCacheNotice]). A copy presented as live is worse than an error.
 *  * PER ACCOUNT. One directory per signed-in account (hashed, like [FormDraftStore]), and every copy
 *    is deleted at sign-out ([forgetAll]) — a cache is not anybody's work, so nothing is lost, and a
 *    shared phone does not show the next person the last one's lists.
 *
 * Only for screens whose rows are plain data. Screens that hold their own rules about not degrading
 * to the device (the DESIGNER tier's rosters and profiles — see `WorkshopRepository`) are deliberately
 * not routed here.
 */
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
        val safe = key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
        return File(File(root, FormDraftStore.ownerSegment(ownerUserId)), "$safe.json")
    }

    /**
     * [fetch] live; on success keep a copy, on a connectivity-class failure serve the copy if there is
     * one, otherwise rethrow exactly what [fetch] threw.
     */
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

    /** The copy and when it was fetched, or null for none / unreadable / a newer build's file. */
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

    /** Every account's copies. Called at sign-out. */
    fun forgetAll(context: Context) {
        synchronized(lock) { runCatching { root(context).deleteRecursively() } }
    }
}

/**
 * Whether [error] means "nothing reached the server" (serve the copy) rather than "the server said
 * no" (show the refusal). The same split as `customQuestionnaireCached` and the outbox's `isTransient`,
 * minus 401: a session that has ended is a refusal to show, not a reason to show yesterday's list.
 * Pinned by `ReadCacheTest`.
 */
internal fun readCacheMayServe(error: Throwable): Boolean = when (error) {
    is HttpException -> error.code() == 408 || error.code() == 429 || error.code() >= 500
    is IOException -> true
    else -> false
}

/** The line a screen shows above a list served from [ReadCache]. */
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
