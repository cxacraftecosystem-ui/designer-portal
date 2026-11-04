package com.designprototype.workshop.ui

import android.content.Context
import android.os.SystemClock
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import java.time.Instant
import kotlin.math.abs

@Serializable
data class DwWorkshopJoinBody(
    val workshopId: String,
    val scannedCode: String? = null,
    val note: String? = null,
)

@Serializable
data class DwWorkshopJoinAck(
    val received: Boolean = false,
    val detail: String? = null,
)

interface DwWorkshopJoinApi {
    @POST("design-workshop-access/requests")
    suspend fun requestDesignWorkshopAccess(@Body body: DwWorkshopJoinBody): DwWorkshopJoinAck

    @GET("design-workshop-access/preview")
    suspend fun scannedWorkshopTitle(@Query("code") code: String): DwScannedWorkshopTitle
}

@Serializable
data class DwScannedWorkshopTitle(
    val workshopId: String = "",
    val title: String = "",
)

suspend fun dwScannedWorkshopTitle(context: Context, code: String): String? =
    withContext(Dispatchers.IO) {
        if (!ConnectivityObserver.isOnline(context)) return@withContext null
        runCatching {
            ApiClient.retrofit(TokenStore(context)).create(DwWorkshopJoinApi::class.java).scannedWorkshopTitle(code)
        }.getOrNull()?.title?.trim()?.ifEmpty { null }
    }

@Serializable
data class DwPendingInduction(
    val workshopId: String,
    val code: String,
    val scannedAtDeviceUtc: String,
    val scannedAtElapsedMs: Long,
    val bootWallClockMs: Long,
    val attempts: Int = 0,
    val kind: String = DW_INDUCTION_ASK,
    val bootId: String = "",
) {
    val queueKey: String get() = "$kind:$workshopId"
}

const val DW_INDUCTION_ASK = "ASK"

const val DW_INDUCTION_JOIN = "JOIN"

fun dwInductionNote(
    pending: DwPendingInduction,
    elapsedNowMs: Long,
    wallNowMs: Long,
): String {
    val bootNow = wallNowMs - elapsedNowMs
    val rebootedOrClockMoved = abs(bootNow - pending.bootWallClockMs) > DW_INDUCTION_CLOCK_SLOP_MS
    val waited = elapsedNowMs - pending.scannedAtElapsedMs

    return buildString {
        append("Scanned with no connection; sent when back online. ")
        if (!rebootedOrClockMoved && waited >= 0) {
            append("Scanned ")
            append(dwSpanInWords(waited))
            append(" before sending (uptime clock, which cannot be set by hand). ")
        } else {
            append("Scan time unknown — the phone restarted or had its clock changed. ")
        }
        append("Phone clock at scan (what the handset claims): ")
        append(pending.scannedAtDeviceUtc)
        append(" UTC. Whichever scan was uploaded first counts.")
    }
}

private const val DW_INDUCTION_CLOCK_SLOP_MS = 2_000L

internal fun dwSpanInWords(millis: Long): String {
    if (millis < 0) return "an unknown time"
    val minutes = millis / 60_000L
    return when {
        minutes < 1L -> "less than a minute"
        minutes < 60L -> "about $minutes minute${if (minutes == 1L) "" else "s"}"
        minutes < 60L * 48L -> {
            val hours = minutes / 60L
            "about $hours hour${if (hours == 1L) "" else "s"}"
        }
        else -> {
            val days = minutes / (60L * 24L)
            "about $days day${if (days == 1L) "" else "s"}"
        }
    }
}

fun dwMergeInduction(
    queue: List<DwPendingInduction>,
    incoming: DwPendingInduction,
): List<DwPendingInduction> {
    val existing = queue.firstOrNull { it.queueKey == incoming.queueKey }
        ?: return queue + incoming
    val merged = existing.copy(
        code = incoming.code,
        bootId = existing.bootId.ifEmpty { incoming.bootId },
        attempts = existing.attempts,
    )
    return queue.map { if (it.queueKey == incoming.queueKey) merged else it }
}

object DwInductionQueue {

    private const val FILE_NAME = "dw-pending-inductions.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val DW_INDUCTION_LIST = ListSerializer(DwPendingInduction.serializer())

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    suspend fun all(context: Context): List<DwPendingInduction> = withContext(Dispatchers.IO) {
        mutex.withLock { read(context) }
    }

    private fun read(context: Context): List<DwPendingInduction> = runCatching {
        val target = file(context)
        if (!target.exists()) return emptyList()
        json.decodeFromString(DW_INDUCTION_LIST, target.readText())
    }.getOrElse { emptyList() }

    private fun write(context: Context, rows: List<DwPendingInduction>) {
        runCatching {
            val target = file(context)
            if (rows.isEmpty()) {
                target.delete()
            } else {
                target.writeText(json.encodeToString(DW_INDUCTION_LIST, rows))
            }
        }
    }

    suspend fun record(
        context: Context,
        workshopId: String,
        code: String,
        kind: String = DW_INDUCTION_ASK,
    ): DwPendingInduction =
        withContext(Dispatchers.IO) {
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val mark = DwBootMark.observe(context, elapsedNowMs = elapsed, wallNowMs = wall).first
            val row = DwPendingInduction(
                workshopId = workshopId,
                code = code,
                scannedAtDeviceUtc = runCatching { Instant.ofEpochMilli(wall).toString() }
                    .getOrElse { "unknown" },
                scannedAtElapsedMs = elapsed,
                bootWallClockMs = wall - elapsed,
                kind = kind,
                bootId = mark.bootId,
            )
            mutex.withLock {
                val merged = dwMergeInduction(read(context), row)
                write(context, merged)
                merged.first { it.queueKey == row.queueKey }
            }.also { com.designprototype.workshop.data.BackgroundSync.requestSoon(context) }
        }

    suspend fun clear(context: Context, queueKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context).filterNot { it.queueKey == queueKey }) }
    }

    internal suspend fun noteAttempt(context: Context, queueKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(
                context,
                read(context).map {
                    if (it.queueKey == queueKey) it.copy(attempts = it.attempts + 1) else it
                },
            )
        }
    }

    suspend fun flush(context: Context): List<Pair<DwPendingInduction, DwJoinOutcome>> {
        val rows = all(context)
        if (rows.isEmpty()) return emptyList()
        return rows.map { row ->
            val outcome = if (row.kind == DW_INDUCTION_JOIN) {
                dwRedeemQueuedJoinCard(context, row)
            } else {
                dwSendJoinAsk(context, row)
            }
            when (outcome) {
                is DwJoinOutcome.Asked -> clear(context, row.queueKey)
                is DwJoinOutcome.Inducted -> clear(context, row.queueKey)
                is DwJoinOutcome.Refused -> clear(context, row.queueKey)
                is DwJoinOutcome.Queued -> noteAttempt(context, row.queueKey)
            }
            row to outcome
        }
    }
}

@Serializable
data class DwBootMark(
    val bootId: String,
    val lastElapsedMs: Long,
    val bootWallClockMs: Long,
) {
    companion object {
        private const val FILE_NAME = "dw-boot-mark.json"
        private val mutex = Mutex()

        private const val CLOCK_SLOP_MS = 2_000L

        private const val REBOOT_SLOP_MS = 5_000L

        fun advance(
            stored: DwBootMark?,
            elapsedNowMs: Long,
            wallNowMs: Long,
            mint: () -> String,
        ): Pair<DwBootMark, Boolean> {
            val bootWallNow = wallNowMs - elapsedNowMs
            val rebooted = stored == null || elapsedNowMs < stored.lastElapsedMs - REBOOT_SLOP_MS
            if (rebooted) {
                return DwBootMark(mint(), elapsedNowMs, bootWallNow) to false
            }
            val jumped = kotlin.math.abs(bootWallNow - stored.bootWallClockMs) > CLOCK_SLOP_MS
            return stored.copy(
                lastElapsedMs = kotlin.math.max(stored.lastElapsedMs, elapsedNowMs),
                bootWallClockMs = stored.bootWallClockMs,
            ) to jumped
        }

        suspend fun observe(
            context: Context,
            elapsedNowMs: Long,
            wallNowMs: Long,
        ): Pair<DwBootMark, Boolean> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val file = File(context.filesDir, FILE_NAME)
                    val stored = runCatching {
                        if (!file.exists()) null else DW_BOOT_JSON.decodeFromString(serializer(), file.readText())
                    }.getOrNull()
                    val advanced = advance(
                        stored,
                        elapsedNowMs = elapsedNowMs,
                        wallNowMs = wallNowMs,
                        mint = { java.util.UUID.randomUUID().toString() },
                    )
                    runCatching {
                        file.writeText(DW_BOOT_JSON.encodeToString(serializer(), advanced.first))
                    }
                    advanced
                }
            }

        suspend fun clockMovedSince(context: Context, row: DwPendingInduction): Boolean =
            withContext(Dispatchers.IO) {
                val elapsed = SystemClock.elapsedRealtime()
                val bootWallNow = System.currentTimeMillis() - elapsed
                kotlin.math.abs(bootWallNow - row.bootWallClockMs) > CLOCK_SLOP_MS
            }
    }
}

private val DW_BOOT_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

sealed interface DwJoinOutcome {
    data class Asked(val detail: String) : DwJoinOutcome

    data class Queued(val message: String) : DwJoinOutcome

    data class Refused(val message: String) : DwJoinOutcome

    data class Inducted(val message: String, val fullMember: Boolean) : DwJoinOutcome
}

fun dwJoinQueuedMessage(): String =
    "Offline — the card is kept on this device with the time you scanned it. Nothing is sent yet; it sends automatically and you do not need to scan again."

suspend fun dwSendJoinAsk(context: Context, pending: DwPendingInduction): DwJoinOutcome =
    withContext(Dispatchers.IO) {
        if (!ConnectivityObserver.isOnline(context)) return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())
        val api = runCatching {
            ApiClient.retrofit(TokenStore(context)).create(DwWorkshopJoinApi::class.java)
        }.getOrNull() ?: return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())

        val elapsedNow = SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        val waited = elapsedNow - pending.scannedAtElapsedMs
        val note = if (waited > DW_INDUCTION_NOTE_THRESHOLD_MS) {
            dwInductionNote(pending, elapsedNow, wallNow)
        } else {
            null
        }

        try {
            val ack = api.requestDesignWorkshopAccess(
                DwWorkshopJoinBody(
                    workshopId = pending.workshopId,
                    scannedCode = pending.code,
                    note = note,
                )
            )
            DwJoinOutcome.Asked(
                ack.detail?.takeIf { it.isNotBlank() }
                    ?: "Request sent. An admin can now see it."
            )
        } catch (error: HttpException) {
            if (error.code() == 422) {
                DwJoinOutcome.Refused(
                    dwJoinBodyRefusal(error) ?: "Card refused — the code doesn't match. Re-check it or ask for a new card."
                )
            } else {
                DwJoinOutcome.Queued(dwJoinQueuedMessage())
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            DwJoinOutcome.Queued(dwJoinQueuedMessage())
        }
    }

private const val DW_INDUCTION_NOTE_THRESHOLD_MS = 10_000L

private fun dwJoinBodyRefusal(error: HttpException): String? {
    return runCatching {
        val raw = error.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
        val element = raw?.let { ApiClient.json.parseToJsonElement(it) }
        val detail = (element as? JsonObject)?.get("detail")
        (detail as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

suspend fun dwJoinDesignWorkshop(context: Context, workshopId: String, code: String): DwJoinOutcome {
    val pending = DwInductionQueue.record(context, workshopId, code, kind = DW_INDUCTION_ASK)
    val outcome = dwSendJoinAsk(context, pending)
    if (outcome !is DwJoinOutcome.Queued) DwInductionQueue.clear(context, pending.queueKey)
    return outcome
}
