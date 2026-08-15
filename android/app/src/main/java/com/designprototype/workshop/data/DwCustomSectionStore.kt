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

/**
 * One workshop's custom definition, as a document this DEVICE owns — one JSON file per workshop.
 *
 * The rules it serves are in [DwCustomSections]; there are none in here. This file is a cache and a
 * write discipline, and every decision it makes is about durability.
 *
 * ── WHY THIS IS MODELLED ON [DwQuestionnaireStore] AND NOT ON [StageSchemaStore] ───────────────────
 *
 * [StageSchemaStore] has THREE tiers: one GLOBAL `filesDir/design-workshop-schema.json`, one GLOBAL
 * bundled asset (`assets/design-workshop-schema.json`, 119 KB, shipped in the APK), and the network.
 * A missing asset there is a hard `IOException` because the asset is a build product and its absence
 * is a broken build.
 *
 * **A PER-WORKSHOP DEFINITION HAS NO TIER 2 AND CANNOT HAVE ONE.** An APK cannot bundle a workshop
 * that did not exist when it was built, and a designer writes their sections weeks after the phone
 * was flashed. Tier 1 and tier 3 are all there is, so the honest floor is "no definition at all" —
 * which is why that has to be a STATED STATE ([DwCustomCopy.UNKNOWN]) rather than an error, and why
 * this store is allowed to return null where [StageSchemaStore] would throw.
 *
 * ── THE WRITE IS [WorkshopDraftStore]'S AND NOT [DwQuestionnaireStore]'S, DELIBERATELY ─────────────
 *
 * The two stores this one otherwise copies do NOT fsync. `DwQuestionnaireStore.store` and
 * `DwReferenceStore.store` both do `temp.writeText(…)` → `if (file.exists()) file.delete()` →
 * `temp.renameTo(file)`, which has two faults: the bytes may still be in the page cache when the
 * rename publishes them, and the delete opens a window in which NEITHER file exists. The JSON
 * documents that DO hold the discipline — write to a temp beside the target, `fd.sync()`, then
 * `renameTo` with no delete — are `WorkshopDraftStore.writeLocked`, `StageSchemaStore.writeCacheFile`
 * and `Offline.write`, whose own comment records the incident that bought it: a kill during a queue
 * write left a truncated file, the next read turned it into an empty queue, and every record on that
 * device was gone silently. This is the fourth, on purpose, and it is copied from those three rather
 * than from the two stores whose SHAPE it copies.
 *
 * The three numbered rules are written out in full at `WorkshopDraftStore.writeLocked`; the reason
 * for spending them HERE is specific. This definition decides whether a designer's own section can be
 * DRAWN AT ALL offline. A zero-length file after a power loss costs them the whole section — every
 * question they added to stage 5, invisible on the form, uncounted by the bar and absent from the
 * report — until they next stand somewhere with a tower. The `renameTo` replaces on this filesystem,
 * so the delete buys nothing and only opens the window; it is not propagated.
 *
 * ── ON A DECODE FAILURE THE FILE IS DELETED, AND THAT IS ONLY SAFE BECAUSE OF ONE THING ───────────
 *
 * This is a COPY of something the server still holds, so the cheap recovery is to drop it and re-read
 * — the rule [DwQuestionnaireStore.load] and [DwReferenceStore] both follow, and the opposite of
 * `WorkshopDraftStore.quarantine`, which never destroys a draft because a draft is irreplaceable
 * fieldwork. **It is safe here only because a null [load] resolves through [dwCustomCopy] to
 * [DwCustomCopy.UNKNOWN].** If null resolved to NONE_DEFINED the phone would assert "this workshop
 * has no custom questions" on the strength of a parse error, print a report short a whole section,
 * and say nothing about either.
 *
 * ── A FAILED FETCH CHANGES NOTHING ON DISK, AND THE REFUSAL IS IN THE FETCH ───────────────────────
 *
 * [store] will let an EMPTY definition overwrite a full one, which is [DwQuestionnaireStore]'s
 * deliberate inversion of [DwReferenceStore]'s "an empty fetch does not overwrite a non-empty cache".
 * An empty definition is a MEANINGFUL answer here — a designer retiring their last section is the
 * documented way to take it off the form — so a store that refused to record it would keep drawing
 * questions that no longer exist. The protection is placed in [designWorkshopCustomSections] instead,
 * which THROWS rather than returning empty on a failure, so a refusal or a dead connection never
 * reaches this function at all.
 */
object DwCustomSectionStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One lock for the whole store, for the reason [DwReferenceStore]'s is coarse: write-and-rename. */
    private val mutex = Mutex()

    /** A process-lifetime mirror. Always written AFTER the file, so a kill between them loses nothing. */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, DwCustomCache>()

    private fun rootDir(context: Context): File =
        File(context.filesDir, "dw-custom").apply { mkdirs() }

    /** Anything not plainly safe in a filename becomes '_'; an empty id gets a stable stand-in. */
    private fun safeName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(80).ifBlank { "unnamed" }

    private fun fileFor(context: Context, workshopId: String): File =
        File(rootDir(context), "${safeName(workshopId)}.json")

    /**
     * What this device holds for one workshop, or null when it has never read the definition.
     *
     * Null and an empty [DwCustomCache.sections] are DIFFERENT ANSWERS — see [dwCustomCopy]. Null is
     * "we have never asked"; complete-and-empty is "the server says this workshop has no custom
     * questions", which is the correct and final answer for most workshops and must draw and warn
     * about nothing.
     */
    suspend fun load(context: Context, workshopId: String): DwCustomCache? {
        if (workshopId.isBlank()) return null
        memory[workshopId]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(context, workshopId)
                if (!file.exists()) return@withLock null
                val text = runCatching { file.readText() }.getOrNull()
                // A zero-length file is damage rather than an empty definition — [store] never
                // produces one, because the smallest thing it can write is a whole JSON object — so
                // it is deleted with the rest. See the header for why deleting is the right recovery
                // here and would be indefensible in `WorkshopDraftStore`.
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

    /**
     * Replace what this device holds for one workshop, atomically.
     *
     * AN EMPTY, COMPLETE CACHE OVERWRITES A FULL ONE — see the header. Only a successful read reaches
     * here at all.
     *
     * THROWS ON A FAILED WRITE rather than swallowing it, which is where this departs from
     * [DwQuestionnaireStore.store]'s `runCatching{}.onFailure{}`. A definition that could not be
     * written is a section that will not draw offline tomorrow, and the caller is a fetch that can say
     * so; swallowing it would leave the designer to discover the gap in a courtyard. The previous
     * file is still on disk and still whole either way, because the temp file is what failed.
     */
    suspend fun store(context: Context, cache: DwCustomCache): DwCustomCache {
        val workshopId = cache.workshopId
        if (workshopId.isBlank()) return cache
        val stamped =
            if (cache.fetchedAt.isNotBlank()) cache else cache.copy(fetchedAt = Instant.now().toString())
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = fileFor(context, workshopId)
                // In the SAME directory as the target. A rename is atomic only WITHIN one filesystem;
                // across one it degrades to copy-then-delete, which is the torn write this exists to
                // prevent.
                val temp = File(target.parentFile, "${target.name}.writing")
                val bytes = json.encodeToString(DwCustomCache.serializer(), stamped).toByteArray()
                try {
                    FileOutputStream(temp).use { out ->
                        out.write(bytes)
                        out.flush()
                        // BEFORE the rename. A rename whose bytes are still only in the page cache
                        // publishes a directory entry pointing at a file that, after a power loss, is
                        // zero length — and [load] deletes what it cannot parse, so the designer would
                        // lose a definition they had, offline, to a write that was only a refresh.
                        out.fd.sync()
                    }
                    if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
                } catch (e: Throwable) {
                    runCatching { temp.delete() }
                    throw e
                }
            }
        }
        // AFTER the file, always. A process killed between the two loses nothing: the next launch
        // reads the file that is already there.
        memory[workshopId] = stamped
        return stamped
    }

    /**
     * Forget one workshop's definition entirely — file and memory mirror.
     *
     * For the workshop DELETE path and for tests. It is not a refusal mechanism: nothing in the fetch
     * path calls it, because "the server could not be reached" must never remove a copy the designer
     * is about to need offline.
     */
    suspend fun forget(context: Context, workshopId: String) {
        if (workshopId.isBlank()) return
        withContext(Dispatchers.IO) {
            mutex.withLock { runCatching { fileFor(context, workshopId).delete() } }
        }
        memory.remove(workshopId)
    }
}

// --------------------------------------------------------------------------------------
// Reading it off the server
// --------------------------------------------------------------------------------------

/**
 * This workshop's custom definition, read from the server and written to the device.
 *
 * **IT THROWS ON A FAILURE AND MUST**, which is [designWorkshopQuestionnaires]' rule and not a new
 * one. An empty definition is written to the cache as the positive fact "this workshop has no custom
 * questions" — that is what [DwCustomCopy.NONE_DEFINED] means and it is what stops a warning
 * appearing on the majority of exports. So a dead connection that returned an empty definition
 * instead of raising would erase a designer's whole section from every future offline form, every
 * score and every report, and would do it in the state where they are least able to notice.
 *
 * `complete = true` unconditionally, and honestly: unlike the questionnaire list this is ONE request
 * for the whole set, so a response that arrived at all is the whole set. There is no paged walk here
 * to be truncated.
 */
suspend fun WorkshopRepository.designWorkshopCustomSections(
    context: Context,
    workshopId: String,
    /**
     * The id to FILE the answer under, where that is not the id it was requested with.
     *
     * A workshop created in a courtyard keeps its `local-…` id on this device for the whole of its
     * life while the request has to go to the server's id, and every reader on this handset asks for
     * the draft's. Writing both — which is what a second [DwCustomSectionStore.store] call did —
     * leaves a duplicate under the server's id that nothing ever reads, that a second fsync was spent
     * on at every screen open, and that `forget(workshopId)` does not remove when the workshop is
     * deleted: a copy of one cluster's questions outliving the workshop they belong to.
     */
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
            // The DEVICE's clock, not the server's `fetchedAt`. What this timestamp is read for is
            // "how old is the copy in my hand", and a phone whose clock is a day out would otherwise
            // print a server time it cannot compare anything of its own against.
            fetchedAt = Instant.now().toString(),
        ),
    )
}

/**
 * The definition this device already holds, refreshed from the server where that is possible.
 *
 * THE REFRESH MAY FAIL AND THE CACHED COPY STILL WINS. This is the call every screen makes on open:
 * a designer in a courtyard gets whatever was last read, a designer with signal gets today's copy,
 * and neither gets an error dialog about a section they may not even have. A workshop with no server
 * record at all is not asked about — there is nothing up there to ask.
 */
suspend fun WorkshopRepository.dwCustomDefinition(
    context: Context,
    workshopId: String,
    remoteId: String?,
): DwCustomCache? {
    if (remoteId != null) {
        // Requested under the SERVER's id and filed under the DRAFT's, in one write. They are not
        // always the same string — a workshop created in a courtyard keeps its `local-…` id on this
        // device for the whole of its life — and every reader on this handset asks `load` for the
        // draft's, so filing it under the server's would leave all of them finding nothing.
        runCatching { designWorkshopCustomSections(context, remoteId, cacheAs = workshopId) }
            .onSuccess { fresh -> return fresh }
    }
    return DwCustomSectionStore.load(context, workshopId)
}
