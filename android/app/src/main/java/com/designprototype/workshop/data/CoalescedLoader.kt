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
        const val DEFAULT_WINDOW_MILLIS = 25L
    }
}

class SharedReads(private val scope: CoroutineScope) {
    private val inFlight = HashMap<String, Deferred<Any>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> read(key: String, fetch: suspend () -> T): T {
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
