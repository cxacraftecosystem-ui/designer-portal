package com.designprototype.workshop.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DwUploadState {

    data class Sending(
        val sent: Long,
        val total: Long,
        val atElapsedRealtime: Long,
    ) : DwUploadState {
        val percent: Int
            get() = if (total <= 0L) 0 else ((sent.coerceAtMost(total) * 100L) / total).toInt().coerceIn(0, 100)
    }

    data class Refused(val reason: String, val permanent: Boolean) : DwUploadState

    data object Sent : DwUploadState
}

object DwMediaUploadProgress {

    private const val PUBLISH_INTERVAL_MS = 1_000L

    private val _states = MutableStateFlow<Map<String, DwUploadState>>(emptyMap())

    val states: StateFlow<Map<String, DwUploadState>> = _states.asStateFlow()

    private val lastPublishedAt = HashMap<String, Long>()

    private val seeded = HashSet<String>()

    private val lock = Any()

    fun sending(
        mediaId: String,
        sent: Long,
        total: Long,
        now: Long = SystemClock.elapsedRealtime(),
    ) {
        synchronized(lock) {
            val previous = _states.value[mediaId] as? DwUploadState.Sending
            val last = lastPublishedAt[mediaId]
            val finished = total > 0L && sent >= total
            val movedAPercent = previous == null || previous.percent != percentOf(sent, total)
            val dueOnTheClock = last == null || now - last >= PUBLISH_INTERVAL_MS
            if (!finished && !movedAPercent && !dueOnTheClock) return
            lastPublishedAt[mediaId] = now
            _states.value = _states.value + (mediaId to DwUploadState.Sending(sent, total, now))
        }
    }

    fun sent(mediaId: String) = publish(mediaId, DwUploadState.Sent)

    fun refused(mediaId: String, reason: String, permanent: Boolean) =
        publish(mediaId, DwUploadState.Refused(reason, permanent))

    fun forget(mediaId: String) {
        synchronized(lock) {
            lastPublishedAt.remove(mediaId)
            if (_states.value.containsKey(mediaId)) _states.value = _states.value - mediaId
        }
    }

    suspend fun seedFrom(context: Context, workshopId: String) {
        synchronized(lock) { if (!seeded.add(workshopId)) return }
        val draft = WorkshopDraftStore.load(context, workshopId) ?: return
        synchronized(lock) {
            var next = _states.value
            for (item in draft.media) {
                val reason = item.uploadFailure ?: continue
                if (next[item.id] is DwUploadState.Sending) continue
                next = next + (item.id to DwUploadState.Refused(reason, permanent = true))
            }
            _states.value = next
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            _states.value = emptyMap()
            lastPublishedAt.clear()
            seeded.clear()
        }
    }

    private fun publish(mediaId: String, state: DwUploadState) {
        synchronized(lock) {
            lastPublishedAt.remove(mediaId)
            _states.value = _states.value + (mediaId to state)
        }
    }

    private fun percentOf(sent: Long, total: Long): Int =
        if (total <= 0L) 0 else ((sent.coerceAtMost(total) * 100L) / total).toInt().coerceIn(0, 100)
}
