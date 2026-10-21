package com.designprototype.workshop.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ONE REQUEST FOR LOOKUPS ASKED TOGETHER — the handset's twin of the web's `lib/mediaRows.ts`.
 *
 * A list screen draws one card per row, and each card asks for its own row: twelve transcripts on a
 * record was twelve `GET media/{id}/insights`, and twelve more every fifteen seconds while summaries
 * were being written. Every [load] made within [windowMillis] of the first one waiting is sent as ONE
 * [fetchBatch] (split at [maxBatch]), and each caller gets back exactly what its own single read
 * would have answered.
 *
 * [fetchBatch] answers `key -> value` for the keys it found; a key it leaves out answers `null`, as a
 * single read that found nothing does. It answers `null` as a whole to mean "this server cannot batch
 * this" — an older server — and then each key is asked through [fetchOne], which is the old
 * behaviour exactly. A batch that THROWS answers `null` for every key in it, so a caller that treats a
 * failed single read as "nothing to show" sees the same thing.
 *
 * [scope] must outlive the callers: the flush runs there, so a card that leaves the screen while its
 * row is in flight cannot strand the other cards waiting on the same request.
 */
class CoalescedLoader<V : Any>(
    private val scope: CoroutineScope,
    private val maxBatch: Int,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val fetchBatch: suspend (List<String>) -> Map<String, V>?,
    private val fetchOne: suspend (String) -> V?
) {
    private val lock = Mutex()
    private var pending = LinkedHashMap<String, MutableList<CompletableDeferred<V?>>>()
    private var scheduled = false

    suspend fun load(key: String): V? {
        val answer = CompletableDeferred<V?>()
        lock.withLock {
            pending.getOrPut(key) { mutableListOf() }.add(answer)
            if (!scheduled) {
                scheduled = true
                scope.launch {
                    delay(windowMillis)
                    flush()
                }
            }
        }
        return answer.await()
    }

    private suspend fun flush() {
        val taken = lock.withLock {
            val batch = pending
            pending = LinkedHashMap()
            scheduled = false
            batch
        }
        for (keys in taken.keys.chunked(maxBatch.coerceAtLeast(1))) {
            val answers: Map<String, V?> = try {
                fetchBatch(keys) ?: keys.associateWith { key ->
                    try {
                        fetchOne(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (cancelled: CancellationException) {
                taken.values.flatten().forEach { it.cancel(cancelled) }
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
            for (key in keys) {
                taken[key]?.forEach { it.complete(answers[key]) }
            }
        }
    }

    companion object {
        /** Long enough for the cards of one list to mount; short enough that nobody waits for it. */
        const val DEFAULT_WINDOW_MILLIS = 25L
    }
}

/**
 * READS ASKED WHILE THE SAME READ IS OUT RIDE IT — and nothing is kept once it answers.
 *
 * Not a cache: the entry is dropped the moment the read settles, so the next ask after it reads
 * afresh exactly as before. What it removes is the identical request sent twice at once — sibling
 * fields on one stage asking the same reference list, the cards of every open questionnaire section
 * asking the same interview's drafts. A failure reaches every caller that rode the read, as their own
 * identical request would have failed them. The read runs in [scope], so one caller leaving does not
 * cancel it for the others.
 */
class SharedReads(private val scope: CoroutineScope) {
    private val inFlight = HashMap<String, Deferred<Any>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> read(key: String, fetch: suspend () -> T): T {
        // LAZY, AND STARTED ONLY ONCE IT IS IN THE MAP: a read that finished before it was recorded
        // would have its removal run first and leave a settled answer behind to be served for ever.
        val (shared, fresh) = synchronized(inFlight) {
            val running = inFlight[key]
            if (running != null) {
                running to false
            } else {
                val started = scope.async(start = CoroutineStart.LAZY) { fetch() as Any }
                inFlight[key] = started
                started.invokeOnCompletion {
                    synchronized(inFlight) { if (inFlight[key] === started) inFlight.remove(key) }
                }
                started to true
            }
        }
        if (fresh) shared.start()
        return shared.await() as T
    }
}
