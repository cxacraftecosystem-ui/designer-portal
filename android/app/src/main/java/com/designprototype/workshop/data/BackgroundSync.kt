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

object BackgroundSync {
    const val PERIODIC_JOB_ID = 0x5150_0001

    internal val soonPassRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    const val SOON_JOB_ID = 0x5150_0002

    private val PERIOD_MS = TimeUnit.MINUTES.toMillis(15)
    private const val BACKOFF_MS = 30_000L

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

    fun requestSoon(context: Context) {
        runCatching {
            val scheduler = scheduler(context) ?: return
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

    private val lastDraftRequestAt = java.util.concurrent.atomic.AtomicLong(0L)

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

internal fun backgroundSyncShouldRetry(signedIn: Boolean, waitingAfterPass: Int, online: Boolean): Boolean =
    signedIn && online && waitingAfterPass > 0

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
                    runCatching { com.designprototype.workshop.transcript.portal.TranscriptPortal.repository(app)?.drain() }
                    if (!com.designprototype.workshop.ui.mustChangePasswordBlocks(tokens.getUser())) {
                        runCatching { DwInductionQueue.flush(app) }
                        runCatching { repository.syncNotifications(app) }
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
                retry = true
            } finally {
                BackgroundSync.soonPassRunning.set(false)
                jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        pass?.cancel()
        return true
    }
}

internal const val DRAFT_WRITE_REQUEST_INTERVAL_MS = 60_000L

internal fun draftWriteShouldRequest(online: Boolean, nowMs: Long, lastRequestMs: Long): Boolean =
    !online || lastRequestMs == 0L || nowMs - lastRequestMs >= DRAFT_WRITE_REQUEST_INTERVAL_MS
