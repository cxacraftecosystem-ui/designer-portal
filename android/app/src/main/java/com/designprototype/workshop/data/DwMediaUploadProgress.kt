package com.designprototype.workshop.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WHAT THE UPLOAD IS DOING TO ONE PARTICULAR FILE, RIGHT NOW — the account the handset has never
 * been able to give.
 *
 * ── THE DEFECT THIS EXISTS TO CLOSE ──────────────────────────────────────────────────────────────
 *
 * [WorkshopSyncEngine] sends a workshop's media one file at a time and, until this store existed,
 * the only thing any screen could say about that was a COUNT: [WorkshopSyncStatus.summary]'s
 * "3 files waiting to upload", written on the row and repeated in the device-wide banner. That
 * sentence is honest about what it knows and useless for the case this product is built for. A
 * 300 MB loom video on a village link takes tens of minutes, during which:
 *
 *   - the count says "1 file waiting to upload" and does not move;
 *   - [DwMediaCapture]'s attachment row says "video · 287.4 MB · tap to play" and does not move;
 *   - `DwPhotoGate.GalleryCounts.uploading` is documented, in as many words, as **"ALWAYS ZERO ON
 *     THIS CLIENT AND KEPT ANYWAY"**, so the gallery bar cannot show it either.
 *
 * Nothing on the handset distinguishes that from an app that has hung. A designer with an evening's
 * worth of signal and a bus to catch has no way to tell whether waiting is worth anything, and the
 * rational move — force-stop, reopen, tap Try again — restarts the transfer from byte zero, which is
 * how a fortnight of fieldwork can stay on a phone through three consecutive evenings of signal.
 *
 * ── WHY IT MEASURES BYTES AND NOT A SPINNER, AND WHY IT REPUBLISHES ON A CLOCK ───────────────────
 *
 * "Slow" and "stalled" look identical to a spinner, and they are the two answers that lead to
 * opposite actions: wait, or stop and move somewhere with signal. They are told apart by exactly one
 * thing — whether the byte count is still moving — so this store carries bytes and the row prints
 * them.
 *
 * That is also why [PUBLISH_INTERVAL_MS] exists beside the whole-percent test. A 300 MB video on a
 * 30 kB/s link spends **over a minute and a half inside every single percent**, so republishing only
 * when the percentage changes would leave a progress line frozen for 100 seconds at a stretch —
 * which is the very symptom this is here to remove, reproduced with a number in front of it. One
 * republish a second guarantees a moving figure on the slowest link the field throws at us, and the
 * whole-percent test still fires immediately on a fast one so a small file is not held to 1 Hz.
 *
 * ── WHY IT IS IN MEMORY, AND WHY THAT IS NOT A GAP ───────────────────────────────────────────────
 *
 * [StreamingRequestBody] reports every 64 KB. A 300 MB video is ~4,800 callbacks; a fortnight's
 * media is tens of thousands. Writing any of that through [WorkshopDraftStore] would mean tens of
 * thousands of atomic temp-write-fsync-rename cycles over the one file that holds the entire
 * fortnight of work — spending the durability budget of the document on a number that is worthless
 * the moment the process dies. **An upload in flight cannot outlive the process performing it**, so
 * an in-memory record of one loses nothing by being in memory.
 *
 * The DURABLE half stays exactly where it was and is untouched by this file:
 * [DraftMedia.remoteMediaId] is still the only thing entitled to say the server holds these bytes,
 * and [DraftMedia.uploadFailure] is still the only durable record of a refusal. This store carries
 * the refusal too — see [seedFrom] — purely so the row can print it without reading the draft off
 * disk on every recomposition.
 */
sealed interface DwUploadState {

    /**
     * Bytes are moving to object storage for this file, as of [atElapsedRealtime].
     *
     * [total] is the file's size as the sender declared it, so a row can print "4.2 MB of 11.2 MB"
     * rather than a bare percentage — the figure that answers "is this worth waiting for".
     */
    data class Sending(
        val sent: Long,
        val total: Long,
        /**
         * [SystemClock.elapsedRealtime] at the moment this reading was taken.
         *
         * Deliberately the monotonic clock and NOT [System.currentTimeMillis]: a handset that picks
         * up a network time correction mid-upload — routine on a phone that has been out of coverage
         * for a fortnight and has just found a tower — would otherwise jump this reading backwards
         * or hours forwards, and anything computing "how long since a byte moved" from it would
         * report a stall on a transfer that is running perfectly.
         */
        val atElapsedRealtime: Long,
    ) : DwUploadState {
        /** 0-100, clamped. Never derived from a zero [total] — an empty file would divide by nothing. */
        val percent: Int
            get() = if (total <= 0L) 0 else ((sent.coerceAtMost(total) * 100L) / total).toInt().coerceIn(0, 100)
    }

    /**
     * The last attempt on this file failed, with the reason the sender gave.
     *
     * [permanent] mirrors what the pass actually did with it, and the two words a row prints are not
     * interchangeable. False means the file is still in the queue and the next pass will try it
     * again by itself; true means [DraftMedia.uploadFailure] has been written, the file is excluded
     * from every future pass, and only a person tapping Try again puts it back — so a row that says
     * "will retry" over a parked file is an instruction to wait for something that is never coming.
     */
    data class Refused(val reason: String, val permanent: Boolean) : DwUploadState

    /**
     * The server acknowledged this file during THIS run of the app.
     *
     * Published so the row changes the instant the pass finishes, rather than waiting for the screen
     * to re-read [DraftMedia.remoteMediaId] off disk. It is not the source of truth for "the server
     * has it" and must never be treated as one — the absence of this state says nothing at all,
     * because a file uploaded yesterday has none.
     */
    data object Sent : DwUploadState
}

/**
 * The live upload picture, keyed by the LOCAL draft media id ([DraftMedia.id]).
 *
 * Local id and not [DraftMedia.remoteMediaId], because the whole point of this store is the window
 * in which there IS no remote id yet. `DwMediaItem.id` is the same key, which is what lets the
 * capture card look a row up without carrying anything new down through `DwMediaBridge`.
 */
object DwMediaUploadProgress {

    /** See the class KDoc: one republish a second keeps a slow transfer visibly moving. */
    private const val PUBLISH_INTERVAL_MS = 1_000L

    private val _states = MutableStateFlow<Map<String, DwUploadState>>(emptyMap())

    /** What every file this process has touched is doing. Empty for a file nothing has reported on. */
    val states: StateFlow<Map<String, DwUploadState>> = _states.asStateFlow()

    /**
     * When each id last had a [DwUploadState.Sending] published, for the throttle.
     *
     * Guarded by [lock] rather than being a concurrent map: it is read and written only from
     * [sending], which already has to take the lock to publish, so a second synchronisation
     * primitive would buy nothing and could disagree with the map it is throttling.
     */
    private val lastPublishedAt = HashMap<String, Long>()

    /**
     * Workshops [seedFrom] has already read, so it reads each one once per process.
     *
     * A stage carries up to eleven media fields and every one of them mounts its own capture card
     * with its own `LaunchedEffect`, so an unguarded seed would read the whole draft — the file that
     * holds the entire fortnight — eleven times on every stage open, on the cheapest phone in the
     * room. Once is enough because every refusal recorded AFTER the seed is published directly by
     * the pass that recorded it; the seed exists only to recover the ones written before this
     * process started.
     */
    private val seeded = HashSet<String>()

    /**
     * One writer at a time.
     *
     * The uploads themselves are strictly serial ([WorkshopSyncEngine]'s `uploadPending` uploads one
     * file at a time, deliberately), but [seedFrom] runs from a screen's coroutine and the pass runs
     * from the sync scope, so two threads genuinely do reach the map. A lost update here is a row
     * stuck on a percentage for the rest of the transfer.
     */
    private val lock = Any()

    /**
     * Report progress for one file. Cheap enough to call from [StreamingRequestBody]'s per-64 KB
     * callback: everything below the publish gate is one comparison and one subtraction.
     *
     * THE FINAL BYTE ALWAYS PUBLISHES, whatever the throttle says. A transfer that ends 200 ms after
     * its last published reading would otherwise leave the row reading 97% for as long as the
     * completion call takes — and `/media/complete` on a field link is not instant, so the last
     * thing a designer saw of a successful upload would be a bar that never filled.
     */
    fun sending(
        mediaId: String,
        sent: Long,
        total: Long,
        /**
         * The monotonic clock, injectable ONLY so the throttle can be tested.
         *
         * The unit-test source set runs against `android.jar` stubs with
         * `isReturnDefaultValues = true`, so [SystemClock.elapsedRealtime] answers a constant 0 there
         * — which would make the time-based republish, the half of this that fixes the 300 MB video,
         * the one behaviour no test could ever observe. A defaulted parameter is the smallest seam
         * that lets `DwMediaUploadProgressTest` drive time; every caller in the app takes the
         * default.
         */
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

    /** The server acknowledged it. Clears any throttle bookkeeping so a re-upload starts clean. */
    fun sent(mediaId: String) = publish(mediaId, DwUploadState.Sent)

    /**
     * The attempt failed. See [DwUploadState.Refused] for why [permanent] is not decoration: it is
     * the difference between "the app will try again" and "nothing will happen until you tap".
     */
    fun refused(mediaId: String, reason: String, permanent: Boolean) =
        publish(mediaId, DwUploadState.Refused(reason, permanent))

    /**
     * Forget one file entirely — it is being tried again, or it has been detached.
     *
     * Removal and not a [DwUploadState.Refused] with a cleared reason: an absent entry means "this
     * store knows nothing", which is what sends a row back to reading the durable
     * [DraftMedia.remoteMediaId] it was reading before any of this existed. Overwriting with an
     * empty state would instead assert something false about a file nobody has touched.
     */
    fun forget(mediaId: String) {
        synchronized(lock) {
            lastPublishedAt.remove(mediaId)
            if (_states.value.containsKey(mediaId)) _states.value = _states.value - mediaId
        }
    }

    /**
     * Put the refusals this workshop is already carrying on disk into the store, so a row can print
     * one without reading the draft.
     *
     * WHY A SEED IS NEEDED AT ALL. Everything else here is written by a pass running in this
     * process. A refusal is written by [WorkshopDraftStore], survives the process that recorded it,
     * and is then the ONE thing a designer most needs on the row — a file the sync will never try
     * again by itself. Without this, closing and reopening the app turns a row that said why a
     * photograph is stuck back into a row that says "image · 4.1 MB · tap to play", and the file is
     * excluded from every future pass with nothing anywhere admitting it.
     *
     * Called once per stage screen rather than per row, and it reads the draft the screen has just
     * read anyway. Only files carrying a refusal are published: a file this store already has a live
     * reading for is left alone, because a transfer in flight is newer than anything on disk.
     */
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

    /**
     * Empty the store. **Tests only** — nothing in the app calls this.
     *
     * It exists because this is an `object`: a JVM test class shares one instance with every other
     * test in the run, so a leftover reading from one case is a passing assertion in the next. The
     * app has no use for it — a process-lifetime store that could be wiped mid-flight would drop the
     * readings of an upload still in progress and leave the rows describing it frozen.
     */
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
