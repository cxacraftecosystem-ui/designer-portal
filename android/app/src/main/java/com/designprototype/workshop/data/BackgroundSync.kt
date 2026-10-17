package com.designprototype.workshop.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.designprototype.workshop.ui.DwInductionQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * SENDING WHAT THIS PHONE HOLDS WHILE THE APP IS NOT OPEN.
 *
 * ── THE GAP THIS CLOSES ─────────────────────────────────────────────────────────────────────────
 *
 * Every queue on this device — the records outbox ([OfflineOutbox]), the design workshops
 * ([WorkshopDraftStore] via [WorkshopSyncEngine]), an inspector's notes and a scanned join card —
 * drained only while the process was alive: at sign-in, on `MainActivity`'s 12/45-second loop and on
 * its connectivity callback. `WorkshopSync.kt` and `DwInductionFlusher.kt` both said so out loud and
 * left the remedy to whoever owned the APK budget. The owner's rule (2026-10-16) is that anything
 * captured is kept until there is internet "and then syncs", with nothing lost across app kills and
 * reboots, so a designer who saves in a courtyard, swipes the app away and walks into signal must not
 * have to remember to reopen it for the fortnight to leave the phone.
 *
 * ── WHY JobScheduler AND NOT WorkManager ───────────────────────────────────────────────────────────
 *
 * The objection recorded against WorkManager was the dependency (and its bundled Room/SQLite) in a
 * build whose size is argued line by line. `JobScheduler` is the platform service WorkManager itself
 * sits on at this minSdk (26): a network-constrained job, persisted across reboots, with the
 * platform's own exponential backoff — every guarantee the comment asked for, at zero bytes of APK.
 *
 * ── WHAT RUNS, AND WHY IT IS SAFE TO RUN BESIDE THE FOREGROUND LOOP ──────────────────────────────
 *
 * The job calls the SAME entry points the activity calls — [WorkshopRepository.syncOutbox] (which
 * drains the outbox, then the design workshops, then inspection notes) and [DwInductionQueue.flush] —
 * so there is no second sync policy to keep in step. The outbox and inspection passes lock on
 * process-wide mutexes (see `WorkshopRepository`'s companion), and [WorkshopSyncEngine] already has a
 * single-pass lock, so a job firing while the activity's loop is mid-pass waits rather than sending a
 * record twice.
 *
 * Two jobs, both persisted:
 *  * [PERIODIC_JOB_ID] — every ~15 minutes WHEN a network is up. The safety net: it is what reaches a
 *    workshop stage edited offline, an inspector's note, anything a writer forgot to announce.
 *  * [SOON_JOB_ID] — one shot, scheduled the moment something is queued, that the platform runs as soon
 *    as a network appears. This is what makes "it goes when you get signal" true to the minute.
 */
object BackgroundSync {
    const val PERIODIC_JOB_ID = 0x5150_0001

    /** True while [OutboxSyncJobService] is running a pass — see [requestSoon]. */
    internal val soonPassRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    const val SOON_JOB_ID = 0x5150_0002

    /** The platform floor for a periodic job; anything shorter is silently raised to it. */
    private val PERIOD_MS = TimeUnit.MINUTES.toMillis(15)
    private const val BACKOFF_MS = 30_000L

    /**
     * Make sure the periodic safety-net job exists. Idempotent and cheap: an existing job is left
     * alone, because re-scheduling a job id that is RUNNING cancels the pass in flight.
     */
    fun ensureScheduled(context: Context) {
        runCatching {
            val scheduler = scheduler(context) ?: return
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_JOB_ID, component(context))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .build()
            )
        }
    }

    /**
     * Something was just written that has to reach the server: send it as soon as there is a network,
     * whether or not the app is still open. Never throws — a failure to schedule leaves exactly the
     * behaviour this app had before (the foreground loop and the periodic job still run).
     */
    fun requestSoon(context: Context) {
        runCatching {
            val scheduler = scheduler(context) ?: return
            // RUNNING: leave it. A schedule() of the same id would cancel a pass that may be half-way
            // through a fortnight of photographs; the pass re-reads the queue, and a write that lands
            // during it is picked up by its retry or the periodic job.
            // IDLE (merely waiting, or nothing scheduled): REPLACE, so the job always carries the
            // current constraints and a fresh backoff rather than one left over from an old failure.
            if (soonPassRunning.get()) return
            scheduler.schedule(
                JobInfo.Builder(SOON_JOB_ID, component(context))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
            )
        }
    }

    /** When a draft write last asked for a pass (elapsed ms), for [requestSoonFromDraftWrite]. */
    private val lastDraftRequestAt = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * [requestSoon] for a design-workshop DRAFT WRITE, at most once a minute while online (2026-10-16).
     * The stage screen saves on an 800 ms debounce; each of those used to cost a binder call and, when
     * idle, a re-schedule. Offline every write still asks, because a job that is merely waiting for a
     * network costs nothing and must exist.
     */
    fun requestSoonFromDraftWrite(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        val online = runCatching { ConnectivityObserver.isOnline(context) }.getOrDefault(false)
        if (!draftWriteShouldRequest(online, now, lastDraftRequestAt.get())) return
        lastDraftRequestAt.set(now)
        requestSoon(context)
    }

    private fun scheduler(context: Context): JobScheduler? =
        context.applicationContext.getSystemService(JobScheduler::class.java)

    private fun component(context: Context) =
        ComponentName(context.applicationContext, OutboxSyncJobService::class.java)
}

/**
 * Whether a background pass should ask the platform to run it again (with backoff).
 *
 * Only when work is STILL WAITING and a network was there to use: that is a transient failure (a
 * dropped socket, a 5xx, a 429) that the next attempt can fix. Waiting with no network needs no
 * reschedule — the network constraint fires the job again when one appears, and the periodic job
 * is still there. Nothing waiting, or nobody signed in to send it as, needs nothing at all.
 * Pinned by `BackgroundSyncTest`.
 */
internal fun backgroundSyncShouldRetry(signedIn: Boolean, waitingAfterPass: Int, online: Boolean): Boolean =
    signedIn && online && waitingAfterPass > 0

/**
 * The platform's handle on [BackgroundSync]. Declared in the manifest with
 * `android.permission.BIND_JOB_SERVICE`, so only the system can start it.
 *
 * Builds its own [WorkshopRepository] from the same [TokenStore] the activity reads, so it sends as
 * whoever is signed in on this phone and skips entries another account captured, exactly as the
 * foreground pass does ([PendingEntry.ownerUserId]). Signed out, it does nothing: an entry is never
 * sent under no account, and it waits — on disk — for the next sign-in.
 */
class OutboxSyncJobService : JobService() {
    @Volatile
    private var pass: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        BackgroundSync.soonPassRunning.set(true)
        pass = AppScope.io.launch {
            var retry = false
            try {
                val tokens = TokenStore(app)
                val signedIn = !tokens.getToken().isNullOrBlank() && tokens.getUser() != null
                if (signedIn) {
                    val repository = WorkshopRepository(ApiClient.create(tokens), tokens)
                    runCatching { repository.syncOutbox(app) }
                    // `syncOutbox` holds itself back while a new password is owed; the join-card queue
                    // is told the same way `DwInductionFlusher` tells it, so no pass is spent on a 401.
                    if (!com.designprototype.workshop.ui.mustChangePasswordBlocks(tokens.getUser())) {
                        runCatching { DwInductionQueue.flush(app) }
                    }
                    val waiting = runCatching { repository.outboxCounts(app).waiting }.getOrDefault(0)
                    retry = backgroundSyncShouldRetry(
                        signedIn = true,
                        waitingAfterPass = waiting,
                        online = ConnectivityObserver.isOnline(app),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Every queue keeps its work on any failure; the next run picks it up.
                retry = true
            } finally {
                BackgroundSync.soonPassRunning.set(false)
                jobFinished(params, retry)
            }
        }
        return true
    }

    /**
     * The platform withdrew the job (the network went, or the time budget ran out). Cancelling is
     * safe for the same reason a killed process is: every queue persists its progress per file.
     * `true` asks for it to be run again when the constraints next hold.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        pass?.cancel()
        return true
    }
}

/** The throttle window for draft-write sync requests while online. */
internal const val DRAFT_WRITE_REQUEST_INTERVAL_MS = 60_000L

/**
 * Should a draft write ask for a background pass? Always when offline; while online, at most once per
 * [DRAFT_WRITE_REQUEST_INTERVAL_MS]. Pure. Pinned by `OfflineDeferralsTest`.
 */
internal fun draftWriteShouldRequest(online: Boolean, nowMs: Long, lastRequestMs: Long): Boolean =
    !online || lastRequestMs == 0L || nowMs - lastRequestMs >= DRAFT_WRITE_REQUEST_INTERVAL_MS
