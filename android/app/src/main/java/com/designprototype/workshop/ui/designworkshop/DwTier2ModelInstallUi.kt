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

/**
 * **FETCHING A TIER 2 MODEL: DOWNLOAD ON A TAP, RESUME, PAUSE, CANCEL, VERIFY, REMOVE.**
 *
 * The speech model's controller (`DwAsrModelInstallUi.kt`) is the pattern and its rules are kept:
 *
 *  * **Nothing starts by itself.** [install] runs only from a designer's tap on a row whose control
 *    `dwTier2InstallOffer` drew.
 *  * **The bytes land under a `.part` name and are renamed only after [dwTier2VerifyFile] says
 *    VERIFIED** — right size AND the SHA-256 pinned in the APK. A file that fails is deleted and never
 *    opened, so the runtime can only ever be handed a file that passed.
 *  * **Pause keeps the part-file and Cancel deletes it**, told apart by [stopIntent] because a
 *    coroutine cancellation looks the same whichever button caused it.
 *  * **A resume is checked on the response, not assumed.** `Range: bytes=<n>-` is asked for, and only
 *    a 206 whose `Content-Range` starts at n is appended to ([dwRangeHonoured]); anything else
 *    truncates and starts again rather than producing a corrupt file an hour later.
 *  * **One verify for the cable route too.** A file pushed to the staging directory
 *    (`getExternalFilesDir(null)/dwtier2/<file name>`, the commands are in
 *    docs/TIER2-LANGUAGE-MODEL-MEASUREMENT.md §5) is copied in and passes the same check.
 *
 * The client sends no credential: the pinned URLs are ungated repositories, and the app's own bearer
 * token has no business on a request to anybody else.
 */
internal class DwTier2ModelController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** What this build pins. Overridable only so an instrumentation test can point the loop elsewhere. */
    private val artifacts: List<DwTier2Artifact> = DW_TIER2_ARTIFACTS,
) {
    /** Per model id. Every id starts UNKNOWN until [refresh] has looked at the disk. */
    var statuses by mutableStateOf(artifacts.associate { it.modelId to DwTier2ModelStatus() })
        private set

    /** The model a transfer is running for, or null. One at a time: these are gigabytes. */
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
            // Gigabytes over a district-town connection, but not unbounded: a stalled fetch ends in a
            // sentence and a kept part-file, not in a spinner that outlives the day.
            .callTimeout(3, TimeUnit.HOURS)
            .build()
    }

    /** Ids of the models verified on this phone. */
    val installed: Set<String>
        get() = statuses.filterValues { it.state == DwTier2ModelState.INSTALLED }.keys

    /** Look at the disk. Safe to call again; never interrupts a transfer. */
    fun refresh() {
        if (job?.isActive == true) return
        scope.launch {
            statuses = withContext(Dispatchers.IO) {
                artifacts.associate { it.modelId to dwTier2ReadStatus(context, it) }
            }
        }
    }

    /** Start (or resume) fetching [modelId]. Called only from a designer's tap. */
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

    /** Stop and keep what has arrived. The next [install] resumes from it. */
    fun pause() {
        stopIntent = DwTransferControlState.PAUSED
        job?.cancel()
    }

    /** Stop and delete what has arrived, giving the space back. */
    fun cancel() {
        stopIntent = DwTransferControlState.CANCELLED
        if (job?.isActive == true) {
            job?.cancel()
        } else {
            // A paused or failed fetch abandoned without resuming it.
            scope.launch {
                withContext(Dispatchers.IO) { artifacts.forEach { partFile(it).delete() } }
                refresh()
            }
        }
    }

    /** Delete an installed model and forget any load failure recorded against it. */
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

    /** Sample the meter on the surface's own clock, so a stall shows even when no bytes arrive. */
    fun tick(nowMillis: Long) {
        meter?.let { readout = it.readAt(nowMillis) }
    }

    /** Leaving the screen pauses a transfer rather than throwing the bytes away. */
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
                // The host sent the whole file instead of the remainder. Never append it.
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

/** 256 KiB: the fetch loop writes a few thousand times a second at most, not a few hundred thousand. */
private const val DW_TIER2_BUFFER: Int = 256 * 1024

/** Said when a fetch failed for a reason that is not the disk. The part-file is kept for a resume. */
internal const val DW_TIER2_FETCH_FAILED_SENTENCE: String =
    "The download stopped before it finished. What arrived is kept; Try again carries on from there."

/**
 * What the disk says about one model, without hashing gigabytes on every look.
 *
 * A verified install writes a marker beside the file recording the pinned digest, the size and the
 * file's modification time. A file whose marker matches is INSTALLED; a file with no marker or a
 * stale one was not put there by a verified install and is deleted, because a model file is opened
 * only after the check. A part-file is a PAUSED download.
 */
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

/** The marker's content: the pinned digest, the size and the modification time, one per line. */
internal fun dwTier2MarkerText(artifact: DwTier2Artifact, length: Long, lastModified: Long): String =
    "${artifact.sha256}\n$length\n$lastModified\n"

/**
 * A file an adb cable left for [artifact], at the size this build pins, or null. Staging is
 * `getExternalFilesDir(null)/dwtier2/`, which `adb push` can write with no root and this app reads
 * with no permission. The bytes are copied into `filesDir` and verified there; the staged copy is
 * never opened by the runtime.
 */
internal fun dwTier2StagedFile(context: Context, artifact: DwTier2Artifact): File? {
    val base = context.getExternalFilesDir(null) ?: return null
    val file = File(File(base, DW_TIER2_DIR), artifact.fileName)
    return file.takeIf { it.isFile && it.length() == artifact.bytes }
}

/**
 * The controller for a surface that shows the Tier 2 models. Reads the disk on appearing, samples the
 * meter once a second, and pauses a transfer when the surface leaves.
 */
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

/** Ids of the Tier 2 models verified on this phone, read off the disk (markers, not a re-hash). */
internal fun dwTier2InstalledIds(context: Context): Set<String> =
    DW_TIER2_ARTIFACTS
        .filter { dwTier2ReadStatus(context, it).state == DwTier2ModelState.INSTALLED }
        .map { it.modelId }
        .toSet()

/** The panel's verb names mapped onto the two that run on the phone. EXPAND stays in the cloud. */
internal fun dwTier2VerbFor(verb: String): com.designprototype.workshop.data.DwTier2Verb? = when (verb) {
    "PROOFREAD" -> com.designprototype.workshop.data.DwTier2Verb.PROOFREAD
    "TRANSLATE" -> com.designprototype.workshop.data.DwTier2Verb.TRANSLATION
    else -> null
}

/**
 * Whether a layer a model on this phone produced can be recorded right now: the workshop exists
 * online and there is a connection to record it over. The run itself needs neither.
 */
internal fun dwTier2CanRecord(context: Context, serverWorkshopId: String?): Boolean =
    !serverWorkshopId.isNullOrBlank() &&
        com.designprototype.workshop.data.ConnectivityObserver.isOnline(context)

/**
 * Run [verb] over [passage] on this phone and record the result as a TIER_2 layer, returning the same
 * answer a cloud verb returns so the review sheet treats both alike. Throws when the model would not
 * load ([com.designprototype.workshop.data.DwTier2LoadFailed]), produced nothing, or the layer could
 * not be recorded — and the caller then takes the cloud path.
 */
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
