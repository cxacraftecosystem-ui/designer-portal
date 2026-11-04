package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.designprototype.workshop.data.DW_TIER2_ARTIFACTS
import com.designprototype.workshop.data.DW_TIER2_DIR
import com.designprototype.workshop.data.DW_TIER2_MISMATCH_SENTENCE
import com.designprototype.workshop.data.DwResumeDecision
import com.designprototype.workshop.data.DwTier2Artifact
import com.designprototype.workshop.data.DwTier2Engine
import com.designprototype.workshop.data.DwTier2FailureStore
import com.designprototype.workshop.data.DwTier2FileVerdict
import com.designprototype.workshop.data.DwTier2ModelState
import com.designprototype.workshop.data.DwTier2ModelStatus
import com.designprototype.workshop.data.DwTransferControlState
import com.designprototype.workshop.data.DwTransferMeter
import com.designprototype.workshop.data.DwTransferPhase
import com.designprototype.workshop.data.DwTransferReadout
import com.designprototype.workshop.data.dwIsDiskFull
import com.designprototype.workshop.data.dwParseContentRangeStart
import com.designprototype.workshop.data.dwPartialFileName
import com.designprototype.workshop.data.dwRangeHonoured
import com.designprototype.workshop.data.dwResumePlan
import com.designprototype.workshop.data.dwTier2VerifyFile
import com.designprototype.workshop.data.dwTransferDiskFullSentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

internal class DwTier2ModelController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val artifacts: List<DwTier2Artifact> = DW_TIER2_ARTIFACTS,
) {
    var statuses by mutableStateOf(artifacts.associate { it.modelId to DwTier2ModelStatus() })
        private set

    var activeModelId by mutableStateOf<String?>(null)
        private set

    var phase by mutableStateOf<DwTransferPhase?>(null)
        private set

    var readout by mutableStateOf<DwTransferReadout?>(null)
        private set

    private var meter: DwTransferMeter? = null
    private var stopIntent: DwTransferControlState = DwTransferControlState.RUNNING
    private var job: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.HOURS)
            .build()
    }

    val installed: Set<String>
        get() = statuses.filterValues { it.state == DwTier2ModelState.INSTALLED }.keys

    fun refresh() {
        if (job?.isActive == true) return
        scope.launch {
            statuses = withContext(Dispatchers.IO) {
                artifacts.associate { it.modelId to dwTier2ReadStatus(context, it) }
            }
        }
    }

    fun install(modelId: String) {
        if (job?.isActive == true) return
        val artifact = artifacts.firstOrNull { it.modelId == modelId } ?: return
        stopIntent = DwTransferControlState.RUNNING
        activeModelId = modelId
        job = scope.launch {
            val next = try {
                fetchAndVerify(artifact)
            } catch (cancelled: CancellationException) {
                val kept = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    val part = partFile(artifact)
                    if (stopIntent == DwTransferControlState.CANCELLED) part.delete()
                    if (part.exists()) part.length() else 0L
                }
                setStatus(
                    modelId,
                    if (kept > 0L) DwTier2ModelStatus(DwTier2ModelState.PAUSED, partialBytes = kept)
                    else DwTier2ModelStatus(DwTier2ModelState.NOT_INSTALLED),
                )
                finishTransfer()
                throw cancelled
            } catch (failure: Exception) {
                val sentence = if (dwIsDiskFull(failure.message)) {
                    dwTransferDiskFullSentence(phase ?: DwTransferPhase.FETCHING)
                } else {
                    DW_TIER2_FETCH_FAILED_SENTENCE
                }
                val kept = withContext(Dispatchers.IO) { partFile(artifact).let { if (it.exists()) it.length() else 0L } }
                DwTier2ModelStatus(DwTier2ModelState.FAILED, partialBytes = kept, failure = sentence)
            }
            setStatus(modelId, next)
            finishTransfer()
        }
    }

    fun pause() {
        stopIntent = DwTransferControlState.PAUSED
        job?.cancel()
    }

    fun cancel() {
        stopIntent = DwTransferControlState.CANCELLED
        if (job?.isActive == true) {
            job?.cancel()
        } else {
            scope.launch {
                withContext(Dispatchers.IO) { artifacts.forEach { partFile(it).delete() } }
                refresh()
            }
        }
    }

    fun remove(modelId: String) {
        val artifact = artifacts.firstOrNull { it.modelId == modelId } ?: return
        scope.launch {
            DwTier2Engine.release()
            withContext(Dispatchers.IO) {
                finalFile(artifact).delete()
                markerFile(artifact).delete()
                partFile(artifact).delete()
            }
            DwTier2FailureStore.forget(context, modelId)
            setStatus(modelId, DwTier2ModelStatus(DwTier2ModelState.NOT_INSTALLED))
        }
    }

    fun tick(nowMillis: Long) {
        meter?.let { readout = it.readAt(nowMillis) }
    }

    fun release() {
        if (job?.isActive == true) pause()
    }

    private suspend fun fetchAndVerify(artifact: DwTier2Artifact): DwTier2ModelStatus =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DW_TIER2_DIR).apply { mkdirs() }
            val part = partFile(artifact)
            val staged = dwTier2StagedFile(context, artifact)
            if (staged != null) {
                copyIn(staged, part, artifact.bytes)
            } else {
                download(artifact, part)
            }
            verifyAndInstall(artifact, part, dir)
        }

    private suspend fun download(artifact: DwTier2Artifact, part: File) {
        val onDisk = if (part.exists()) part.length() else 0L
        var startFrom = when (dwResumePlan(onDisk, artifact.bytes, serverAcceptsRanges = true)) {
            DwResumeDecision.RESUME_FROM_PARTIAL -> onDisk
            DwResumeDecision.START_FRESH -> 0L
            DwResumeDecision.DISCARD_AND_RESTART -> {
                part.delete()
                0L
            }
        }
        val builder = Request.Builder().url(artifact.url)
        if (startFrom > 0L) builder.header("Range", "bytes=$startFrom-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("fetch answered ${response.code}")
            if (startFrom > 0L && !dwRangeHonoured(
                    response.code,
                    dwParseContentRangeStart(response.header("Content-Range")),
                    startFrom,
                )
            ) {
                startFrom = 0L
            }
            val body = response.body ?: throw IllegalStateException("the host sent no file")
            val meterNow = DwTransferMeter(artifact.bytes, resumedFromBytes = startFrom)
            startPhase(DwTransferPhase.FETCHING, meterNow)
            FileOutputStream(part, startFrom > 0L).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DW_TIER2_BUFFER)
                    var moved = 0L
                    var lastPublish = 0L
                    while (true) {
                        if (!currentCoroutineContextActive()) throw CancellationException("stopped")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        moved += read
                        if (startFrom + moved > artifact.bytes) {
                            throw IllegalStateException("more bytes than the pinned file holds")
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPublish >= 250L) {
                            lastPublish = now
                            val sample = meterNow.observe(moved, now)
                            withContext(Dispatchers.Main) { readout = sample }
                        }
                    }
                }
            }
        }
    }

    private suspend fun copyIn(staged: File, part: File, total: Long) {
        val meterNow = DwTransferMeter(total)
        startPhase(DwTransferPhase.COPYING, meterNow)
        part.delete()
        staged.inputStream().use { input ->
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(DW_TIER2_BUFFER)
                var moved = 0L
                while (true) {
                    if (!currentCoroutineContextActive()) throw CancellationException("stopped")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    moved += read
                    meterNow.observe(moved, SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private suspend fun verifyAndInstall(
        artifact: DwTier2Artifact,
        part: File,
        dir: File,
    ): DwTier2ModelStatus {
        val meterNow = DwTransferMeter(part.length())
        startPhase(DwTransferPhase.VERIFYING, meterNow)
        withContext(Dispatchers.Main) {
            setStatus(artifact.modelId, DwTier2ModelStatus(DwTier2ModelState.VERIFYING))
        }
        val digest = dwAsrSha256OfFile(part) { hashed ->
            meterNow.observe(hashed, SystemClock.elapsedRealtime())
        }
        val verdict = dwTier2VerifyFile(artifact.modelId, part.length(), digest, artifacts)
        if (verdict != DwTier2FileVerdict.VERIFIED) {
            part.delete()
            return DwTier2ModelStatus(DwTier2ModelState.FAILED, failure = DW_TIER2_MISMATCH_SENTENCE)
        }
        val target = File(dir, artifact.fileName)
        target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("could not move the verified file")
        writeMarker(artifact, target)
        dwTier2StagedFile(context, artifact)?.delete()
        DwTier2FailureStore.forget(context, artifact.modelId)
        return DwTier2ModelStatus(DwTier2ModelState.INSTALLED)
    }

    private suspend fun startPhase(next: DwTransferPhase, meterNow: DwTransferMeter) {
        withContext(Dispatchers.Main) {
            meter = meterNow
            phase = next
            readout = meterNow.readAt(SystemClock.elapsedRealtime())
            activeModelId?.let { id ->
                if (next != DwTransferPhase.VERIFYING) {
                    setStatus(id, DwTier2ModelStatus(DwTier2ModelState.DOWNLOADING))
                }
            }
        }
    }

    private fun finishTransfer() {
        meter = null
        phase = null
        readout = null
        activeModelId = null
    }

    private fun setStatus(modelId: String, status: DwTier2ModelStatus) {
        statuses = statuses + (modelId to status)
    }

    private fun partFile(artifact: DwTier2Artifact): File =
        File(File(context.filesDir, DW_TIER2_DIR), dwPartialFileName(artifact.fileName))

    private fun finalFile(artifact: DwTier2Artifact): File =
        File(File(context.filesDir, DW_TIER2_DIR), artifact.fileName)

    private fun markerFile(artifact: DwTier2Artifact): File = dwTier2MarkerFile(context, artifact)

    private fun writeMarker(artifact: DwTier2Artifact, target: File) {
        markerFile(artifact).writeText(dwTier2MarkerText(artifact, target.length(), target.lastModified()))
    }

    private suspend fun currentCoroutineContextActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive
}

private const val DW_TIER2_BUFFER: Int = 256 * 1024

internal const val DW_TIER2_FETCH_FAILED_SENTENCE: String =
    "Download stopped. Tap Try again to resume."

internal fun dwTier2ReadStatus(context: Context, artifact: DwTier2Artifact): DwTier2ModelStatus {
    val dir = File(context.filesDir, DW_TIER2_DIR)
    val target = File(dir, artifact.fileName)
    val marker = dwTier2MarkerFile(context, artifact)
    if (target.exists()) {
        val expected = dwTier2MarkerText(artifact, target.length(), target.lastModified())
        val recorded = runCatching { marker.readText() }.getOrNull()
        if (recorded == expected && target.length() == artifact.bytes) {
            return DwTier2ModelStatus(DwTier2ModelState.INSTALLED)
        }
        target.delete()
        marker.delete()
    }
    val part = File(dir, dwPartialFileName(artifact.fileName))
    if (part.exists() && part.length() > 0L) {
        return DwTier2ModelStatus(DwTier2ModelState.PAUSED, partialBytes = part.length())
    }
    return DwTier2ModelStatus(DwTier2ModelState.NOT_INSTALLED)
}

internal fun dwTier2MarkerFile(context: Context, artifact: DwTier2Artifact): File =
    File(File(context.filesDir, DW_TIER2_DIR), "${artifact.fileName}.verified")

internal fun dwTier2MarkerText(artifact: DwTier2Artifact, length: Long, lastModified: Long): String =
    "${artifact.sha256}\n$length\n$lastModified\n"

internal fun dwTier2StagedFile(context: Context, artifact: DwTier2Artifact): File? {
    val base = context.getExternalFilesDir(null) ?: return null
    val file = File(File(base, DW_TIER2_DIR), artifact.fileName)
    return file.takeIf { it.isFile && it.length() == artifact.bytes }
}

@Composable
internal fun rememberDwTier2Models(): DwTier2ModelController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember { DwTier2ModelController(context, scope) }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(controller.activeModelId) {
        while (controller.activeModelId != null) {
            controller.tick(SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return controller
}

internal fun dwTier2InstalledIds(context: Context): Set<String> =
    DW_TIER2_ARTIFACTS
        .filter { dwTier2ReadStatus(context, it).state == DwTier2ModelState.INSTALLED }
        .map { it.modelId }
        .toSet()

internal fun dwTier2VerbFor(verb: String): com.designprototype.workshop.data.DwTier2Verb? = when (verb) {
    "PROOFREAD" -> com.designprototype.workshop.data.DwTier2Verb.PROOFREAD
    "TRANSLATE" -> com.designprototype.workshop.data.DwTier2Verb.TRANSLATION
    else -> null
}

internal fun dwTier2CanRecord(context: Context, serverWorkshopId: String?): Boolean =
    !serverWorkshopId.isNullOrBlank() &&
        com.designprototype.workshop.data.ConnectivityObserver.isOnline(context)

internal suspend fun dwRunVerbOnDevice(
    context: Context,
    repository: com.designprototype.workshop.data.WorkshopRepository,
    workshopId: String,
    model: com.designprototype.workshop.data.DwModelPlan,
    verb: com.designprototype.workshop.data.DwTier2Verb,
    passage: String,
    targetLanguage: String?,
): com.designprototype.workshop.data.DwAiVerbResultDto {
    val raw = DwTier2Engine.generate(context, model, verb, passage, targetLanguage)
    val text = com.designprototype.workshop.data.dwTier2OutputText(raw)
        ?: throw IllegalStateException("the model on this phone wrote nothing")
    val draft = com.designprototype.workshop.data.DwTier2Draft(
        verb = verb,
        source = com.designprototype.workshop.data.DwTier2Source.SuppliedText(passage),
        text = text,
        provenance = com.designprototype.workshop.data.dwTier2Provenance(
            model,
            com.designprototype.workshop.data.dwTier2RunLanguage(verb, targetLanguage, null),
            java.time.OffsetDateTime.now().toString(),
        ),
    )
    return repository.designWorkshopOnDeviceLayer(workshopId, draft)
}
