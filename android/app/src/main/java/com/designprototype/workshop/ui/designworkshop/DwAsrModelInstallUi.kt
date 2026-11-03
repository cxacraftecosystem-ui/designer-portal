package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.destructiveAction
import com.designprototype.workshop.ui.ActionRow
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.BuildConfig
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.DW_ASR_MODELS
import com.designprototype.workshop.data.DW_ASR_MODEL_ARTIFACTS
import com.designprototype.workshop.data.DW_ASR_MODEL_MISMATCH_SENTENCE
import com.designprototype.workshop.data.DW_MODEL_FREE_STORAGE_MARGIN_BYTES
import com.designprototype.workshop.data.DwAsrContainerFormat
import com.designprototype.workshop.data.DwAsrEndpointState
import com.designprototype.workshop.data.DwAsrManifestVerdict
import com.designprototype.workshop.data.DwAsrModel
import com.designprototype.workshop.data.DwAsrModelArtifact
import com.designprototype.workshop.data.DwAsrModelEndpointApi
import com.designprototype.workshop.data.DwAsrModelFile
import com.designprototype.workshop.data.DwAsrModelOffer
import com.designprototype.workshop.data.DwAsrModelSource
import com.designprototype.workshop.data.DwAsrModelState
import com.designprototype.workshop.data.DwAsrModelStatus
import com.designprototype.workshop.data.DwAsrVerification
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.dwAsrEndpointStateOf
import com.designprototype.workshop.data.dwAsrManifestVerdict
import com.designprototype.workshop.data.dwAsrModelFileUrl
import com.designprototype.workshop.data.dwAsrModelSourceFor
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.DwRecognitionSupport
import com.designprototype.workshop.data.DwResumeDecision
import com.designprototype.workshop.data.DwTransferControlState
import com.designprototype.workshop.data.DwTransferMeter
import com.designprototype.workshop.data.DwTransferPhase
import com.designprototype.workshop.data.DwTransferReadout
import com.designprototype.workshop.data.dwBytesLabel
import com.designprototype.workshop.data.dwIsDiskFull
import com.designprototype.workshop.data.dwIsPartialFileName
import com.designprototype.workshop.data.dwPauseLabel
import com.designprototype.workshop.data.dwParseContentRangeStart
import com.designprototype.workshop.data.dwPartialFileName
import com.designprototype.workshop.data.dwPausedSentence
import com.designprototype.workshop.data.dwRangeHonoured
import com.designprototype.workshop.data.dwResumePlan
import com.designprototype.workshop.data.dwStalledSentence
import com.designprototype.workshop.data.dwTransferDiskFullSentence
import com.designprototype.workshop.data.dwTransferHeading
import com.designprototype.workshop.data.dwTransferLine
import com.designprototype.workshop.data.dwTransferSpaceRefusal
import com.designprototype.workshop.data.dwAsrModelActionLabel
import com.designprototype.workshop.data.dwAsrModelArtifactFor
import com.designprototype.workshop.data.dwAsrModelMayInstall
import com.designprototype.workshop.data.dwAsrModelOffer
import com.designprototype.workshop.data.dwAsrModelOfferSentence
import com.designprototype.workshop.data.dwAsrModelStateLabel
import com.designprototype.workshop.data.dwAsrModelWhatItBuysSentence
import com.designprototype.workshop.data.dwAsrVerify
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

private const val DW_ASR_MODEL_DIR = "asr-model"

private const val DW_ASR_MODEL_INCOMING = "incoming"

private const val DW_ASR_STAGING = "dwasr"

internal fun dwAsrModelDir(context: Context, modelId: String): File =
    File(File(context.filesDir, DW_ASR_MODEL_DIR), modelId)

internal fun dwAsrStagingDirs(context: Context): List<File> = listOfNotNull(
    context.getExternalFilesDir(null)?.let { File(it, DW_ASR_STAGING) },
    File("/data/local/tmp/$DW_ASR_STAGING"),
)

internal fun stagedModelFiles(context: Context, model: DwAsrModel): Map<String, File>? {
    dwAsrStagingDirs(context).forEach { dir ->
        val found = runCatching {
            model.files.associate { pinned ->
                pinned.fileName to File(dir, pinned.fileName)
            }
        }.getOrNull() ?: return@forEach
        val complete = runCatching {
            found.all { (name, file) ->
                val pinned = model.files.first { it.fileName == name }
                file.isFile && file.length() == pinned.bytes
            }
        }.getOrDefault(false)
        if (complete) return found
    }
    return null
}

internal fun dwAsrReadInstalledModel(
    context: Context,
    model: DwAsrModel? = DW_ASR_MODELS.firstOrNull(),
    onBytesHashed: ((Long) -> Unit)? = null,
): DwAsrModelStatus {
    if (model == null) return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED)
    val dir = dwAsrModelDir(context, model.modelId)
    val verified = mutableListOf<String>()
    var hashedInEarlierFiles = 0L
    model.files.forEach { pinned ->
        val file = File(dir, pinned.fileName)
        if (!file.isFile) return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, model)
        if (file.length() != pinned.bytes) {
            runCatching { file.delete() }
            return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, model)
        }
        val progress: ((Long) -> Unit)? = onBytesHashed?.let { sink ->
            { inThisFile: Long -> sink(hashedInEarlierFiles + inThisFile) }
        }
        val digest = dwAsrSha256OfFile(file, progress)
            ?: return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, model)
        hashedInEarlierFiles += pinned.bytes
        if (dwAsrVerify(pinned.sha256, digest) != DwAsrVerification.VERIFIED) {
            runCatching { file.delete() }
            return DwAsrModelStatus(
                DwAsrModelState.FAILED,
                model,
                failure = DW_ASR_MODEL_MISMATCH_SENTENCE,
            )
        }
        verified += digest
    }
    return DwAsrModelStatus(DwAsrModelState.INSTALLED, model, verifiedSha256 = verified)
}

@Stable
internal class DwAsrModelController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val models: List<DwAsrModel> = DW_ASR_MODELS,
    private val artifacts: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
    private val apiBaseUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
) {

    var status by mutableStateOf(DwAsrModelStatus())
        private set

    var measurement by mutableStateOf<DwDeviceMeasurement?>(null)
        private set

    var connection by mutableStateOf(DwConnection.NONE)
        private set

    var stagedFilesPresent by mutableStateOf(false)
        private set

    var endpoint by mutableStateOf(DwAsrEndpointState.UNKNOWN)
        private set

    var manifestVerdict by mutableStateOf<DwAsrManifestVerdict?>(null)
        private set

    var pausedSource by mutableStateOf<DwAsrModelSource?>(null)
        private set

    var partialOnDiskBytes by mutableStateOf(0L)
        private set

    var busy by mutableStateOf(false)
        private set

    var readout by mutableStateOf<DwTransferReadout?>(null)
        private set

    var phase by mutableStateOf<DwTransferPhase?>(null)
        private set

    private var meter: DwTransferMeter? = null

    @Volatile
    private var countedBytes: Long = 0L

    private var meterFedByTick: Boolean = false

    private var stopIntent: DwTransferControlState = DwTransferControlState.RUNNING

    private var job: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.MINUTES)
            .addInterceptor(
                Interceptor { chain ->
                    val token = TokenStore(context).getToken()
                    val request = if (token.isNullOrBlank()) {
                        chain.request()
                    } else {
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    }
                    chain.proceed(request)
                }
            )
            .build()
    }

    private val model: DwAsrModel? get() = models.firstOrNull()

    val modelCatalogue: List<DwAsrModel> get() = models

    val artifactCatalogue: List<DwAsrModelArtifact> get() = artifacts

    fun refresh() {
        if (status.state == DwAsrModelState.INSTALLING) return
        job?.cancel()
        busy = true
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) { dwProbeDevice(context) }
            val here = dwConnection(context)
            val pinned = model
            val next = withContext(Dispatchers.IO) {
                sweepIncoming()
                val read = if (pinned == null) {
                    DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED)
                } else {
                    readInstalled(pinned)
                }
                if (read.state == DwAsrModelState.NOT_INSTALLED && partialBytes() > 0L) {
                    DwAsrModelStatus(DwAsrModelState.PAUSED, read.model)
                } else {
                    read
                }
            }
            val staged = withContext(Dispatchers.IO) {
                pinned != null && stagedModelFiles(context, pinned) != null
            }
            val deployment = if (pinned == null || here == DwConnection.NONE) {
                endpoint
            } else {
                readManifest(pinned)
            }
            val onDisk = withContext(Dispatchers.IO) { partialBytesAndSource() }
            measurement = reading
            connection = here
            stagedFilesPresent = staged
            endpoint = deployment
            partialOnDiskBytes = onDisk.first
            pausedSource = onDisk.second
            status = next
            DwAsrModelRun.publish(next)
            busy = false
        }
    }

    private fun sweepIncoming() {
        val incoming = File(File(context.filesDir, DW_ASR_MODEL_DIR), DW_ASR_MODEL_INCOMING)
        runCatching {
            incoming.listFiles()?.forEach { file ->
                if (!dwIsPartialFileName(file.name)) file.delete()
            }
        }
    }

    private fun incomingDir(): File =
        File(File(context.filesDir, DW_ASR_MODEL_DIR), DW_ASR_MODEL_INCOMING)

    private fun partialFor(model: DwAsrModel, artifact: DwAsrModelArtifact): File =
        File(incomingDir(), dwPartialFileName("model-${model.modelId}${artifact.container.extension}"))

    private fun endpointPartFor(model: DwAsrModel, pinned: DwAsrModelFile): File =
        File(incomingDir(), dwPartialFileName("${model.modelId}-${pinned.fileName}"))

    private fun partialBytesAndSource(): Pair<Long, DwAsrModelSource?> {
        val pinned = model ?: return 0L to null
        val fromEndpoint = pinned.files.sumOf { file ->
            val part = endpointPartFor(pinned, file)
            if (part.isFile) part.length() else 0L
        }
        if (fromEndpoint > 0L) return fromEndpoint to DwAsrModelSource.DEPLOYMENT_ENDPOINT
        val artifact = dwAsrModelArtifactFor(pinned.modelId, artifacts) ?: return 0L to null
        val container = partialFor(pinned, artifact)
        val bytes = if (container.isFile) container.length() else 0L
        return bytes to (if (bytes > 0L) DwAsrModelSource.PINNED_CONTAINER else null)
    }

    private fun partialBytes(): Long = partialBytesAndSource().first

    private val endpointApi: DwAsrModelEndpointApi by lazy {
        ApiClient.retrofit(TokenStore(context)).create(DwAsrModelEndpointApi::class.java)
    }

    private suspend fun readManifest(pinned: DwAsrModel): DwAsrEndpointState =
        withContext(Dispatchers.IO) {
            try {
                val manifest = endpointApi.asrModel(pinned.modelId)
                val verdict = dwAsrManifestVerdict(manifest, pinned)
                withContext(Dispatchers.Main) { manifestVerdict = verdict }
                dwAsrEndpointStateOf(verdict)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (http: HttpException) {
                withContext(Dispatchers.Main) { manifestVerdict = null }
                when (http.code()) {
                    401 -> DwAsrEndpointState.SESSION_LAPSED
                    403 -> DwAsrEndpointState.FORBIDDEN
                    404 -> DwAsrEndpointState.VERSION_SKEW
                    else -> DwAsrEndpointState.UNREACHABLE
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { manifestVerdict = null }
                DwAsrEndpointState.UNREACHABLE
            }
        }

    private fun readInstalled(
        model: DwAsrModel,
        onBytesHashed: ((Long) -> Unit)? = null,
    ): DwAsrModelStatus = dwAsrReadInstalledModel(context, model, onBytesHashed)

    fun install() {
        val reading = measurement ?: return
        val pinned = model ?: return
        val offer = dwAsrModelOffer(
            status, reading, connection, stagedFilesPresent, models, artifacts, endpoint,
        )
        if (!dwAsrModelMayInstall(offer)) return
        val source = dwAsrModelSourceFor(offer, endpoint, stagedFilesPresent, pausedSource)
        stopIntent = DwTransferControlState.RUNNING
        status = DwAsrModelStatus(DwAsrModelState.INSTALLING, pinned)
        busy = true
        job = scope.launch {
            val outcome = when (source) {
                DwAsrModelSource.STAGED_FILES -> installFromStaged(pinned)
                DwAsrModelSource.DEPLOYMENT_ENDPOINT -> downloadFromEndpoint(pinned)
                else -> {
                    val artifact = dwAsrModelArtifactFor(pinned.modelId, artifacts)
                    if (artifact == null) {
                        failed(pinned, "There is nothing to download for this model.")
                    } else {
                        downloadAndInstall(pinned, artifact)
                    }
                }
            }
            status = outcome
            phase = null
            readout = null
            meter = null
            DwAsrModelRun.publish(outcome)
            busy = false
        }
    }

    fun pause() {
        if (status.state != DwAsrModelState.INSTALLING) return
        if (phase != DwTransferPhase.FETCHING) return
        stopIntent = DwTransferControlState.PAUSED
        job?.cancel()
        job = null
        phase = null
        meter = null
        readout = null
        busy = false
        status = DwAsrModelStatus(DwAsrModelState.PAUSED, status.model ?: model)
        DwAsrModelRun.publish(status)
    }

    fun cancel() {
        val pinned = status.model ?: model
        stopIntent = DwTransferControlState.CANCELLED
        job?.cancel()
        job = null
        phase = null
        readout = null
        meter = null
        busy = true
        job = scope.launch {
            withContext(Dispatchers.IO) {
                pinned?.let { m ->
                    dwAsrModelArtifactFor(m.modelId, artifacts)?.let {
                        runCatching { partialFor(m, it).delete() }
                    }
                    m.files.forEach { file -> runCatching { endpointPartFor(m, file).delete() } }
                    runCatching { dwAsrModelDir(context, m.modelId).listFiles()?.forEach { it.delete() } }
                }
            }
            val next = withContext(Dispatchers.IO) {
                if (pinned == null) DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED) else readInstalled(pinned)
            }
            status = next
            DwAsrModelRun.publish(next)
            busy = false
        }
    }

    fun tick(nowMillis: Long) {
        val live = meter ?: return
        if (status.state != DwAsrModelState.INSTALLING) return
        readout = if (meterFedByTick) live.observe(countedBytes, nowMillis) else live.readAt(nowMillis)
    }

    private suspend fun installFromStaged(model: DwAsrModel): DwAsrModelStatus {
        val target = dwAsrModelDir(context, model.modelId)
        return try {
            withContext(Dispatchers.IO) {
                val staged = stagedModelFiles(context, model)
                    ?: return@withContext failed(
                        model,
                        "Model files are missing. Push them again and tap “Check again”.",
                    )
                target.mkdirs()
                runCatching { target.listFiles()?.forEach { it.delete() } }

                val total = model.files.sumOf { it.bytes }.coerceAtLeast(1L)
                startMeter(DwTransferPhase.COPYING, total, resumedFrom = 0L)
                var written = 0L
                model.files.forEach { pinned ->
                    val source = staged.getValue(pinned.fileName)
                    val out = File(target, pinned.fileName)
                    source.inputStream().use { input ->
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                publishProgress(written, total)
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                }
                startMeter(
                    DwTransferPhase.VERIFYING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                finishOrCleanUp(model, target, onBytesHashed = { countedBytes = it })
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            runCatching { target.listFiles()?.forEach { it.delete() } }
            throw cancellation
        } catch (error: Exception) {
            runCatching { target.listFiles()?.forEach { it.delete() } }
            failed(
                model,
                if (dwIsDiskFull(error.message)) {
                    dwTransferDiskFullSentence(DwTransferPhase.COPYING)
                } else {
                    "Couldn't copy the model. Nothing was installed."
                },
            )
        }
    }

    private suspend fun downloadAndInstall(
        model: DwAsrModel,
        artifact: DwAsrModelArtifact,
    ): DwAsrModelStatus {
        if (!artifact.container.readableInThisBuild) {
            return failed(
                model,
                "Can't download the speech model right now.",
            )
        }
        val incoming = File(File(context.filesDir, DW_ASR_MODEL_DIR), DW_ASR_MODEL_INCOMING)
        val container = partialFor(model, artifact)
        val target = dwAsrModelDir(context, model.modelId)
        return try {
            withContext(Dispatchers.IO) {
                incoming.mkdirs()
                target.mkdirs()
                runCatching { target.listFiles()?.forEach { it.delete() } }

                val spaceRefusal = dwTransferSpaceRefusal(
                    measurement?.freeStorageBytes,
                    (artifact.downloadBytes - (if (container.isFile) container.length() else 0L))
                        .coerceAtLeast(0L) + model.onDiskBytes,
                )
                if (spaceRefusal != null) return@withContext failed(model, spaceRefusal)

                download(artifact, container)
                startMeter(
                    DwTransferPhase.VERIFYING,
                    artifact.downloadBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                val containerDigest = dwAsrSha256OfFile(container, onBytesHashed = { countedBytes = it })
                    ?: return@withContext failedAfterCleanUp(
                        model, container, target,
                        "Download couldn't be read back (storage full?). Free space and retry.",
                    )
                if (dwAsrVerify(artifact.sha256, containerDigest) != DwAsrVerification.VERIFIED) {
                    cleanUp(container, target)
                    return@withContext failed(model, DW_ASR_MODEL_MISMATCH_SENTENCE)
                }
                startMeter(
                    DwTransferPhase.UNPACKING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                var unpacked = 0L
                ZipFile(container).use { zip ->
                    model.files.forEach { pinned ->
                        val entry = zip.getEntry(pinned.fileName)
                            ?: return@withContext failedAfterCleanUp(
                                model, container, target,
                                "Wrong file received; nothing installed. Try later.",
                            )
                        val out = File(target, pinned.fileName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(out).use { output ->
                                val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    unpacked += read
                                    countedBytes = unpacked
                                }
                                output.flush()
                                output.fd.sync()
                            }
                        }
                    }
                }
                runCatching { container.delete() }
                startMeter(
                    DwTransferPhase.VERIFYING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                finishOrCleanUp(model, target, onBytesHashed = { countedBytes = it })
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            runCatching { target.listFiles()?.forEach { it.delete() } }
            if (stopIntent != DwTransferControlState.PAUSED) {
                runCatching { container.delete() }
            }
            throw cancellation
        } catch (error: Exception) {
            runCatching { target.listFiles()?.forEach { it.delete() } }
            val kept = runCatching { if (container.isFile) container.length() else 0L }.getOrDefault(0L)
            failed(
                model,
                when {
                    dwIsDiskFull(error.message) && kept > 0L ->
                        "${dwTransferDiskFullSentence(DwTransferPhase.FETCHING)} " +
                            "${dwBytesLabel(kept)} is on this phone already."
                    dwIsDiskFull(error.message) ->
                        dwTransferDiskFullSentence(DwTransferPhase.FETCHING)
                    kept > 0L ->
                        "Stopped: ${dwModelStopReason(error)}. ${dwBytesLabel(kept)} kept — tap Resume."
                    else ->
                        "Stopped: ${dwModelStopReason(error)}."
                },
            )
        }
    }

    private suspend fun downloadFromEndpoint(model: DwAsrModel): DwAsrModelStatus {
        val target = dwAsrModelDir(context, model.modelId)
        val parts = model.files.map { it to endpointPartFor(model, it) }
        return try {
            withContext(Dispatchers.IO) {
                incomingDir().mkdirs()
                target.mkdirs()
                runCatching { target.listFiles()?.forEach { it.delete() } }

                val alreadyHere = parts.sumOf { (_, part) -> if (part.isFile) part.length() else 0L }
                val refusal = dwTransferSpaceRefusal(
                    measurement?.freeStorageBytes,
                    (model.onDiskBytes - alreadyHere).coerceAtLeast(0L) +
                        DW_MODEL_FREE_STORAGE_MARGIN_BYTES,
                )
                if (refusal != null) return@withContext failed(model, refusal)

                startMeter(
                    DwTransferPhase.FETCHING,
                    model.onDiskBytes,
                    resumedFrom = alreadyHere,
                )
                val attemptTotal = (model.onDiskBytes - alreadyHere).coerceAtLeast(1L)
                var movedThisAttempt = 0L
                parts.forEach { (pinned, part) ->
                    movedThisAttempt += fetchEndpointFile(model, pinned, part) { moved ->
                        publishProgress(movedThisAttempt + moved, attemptTotal)
                    }
                }

                parts.forEach { (pinned, part) ->
                    val out = File(target, pinned.fileName)
                    runCatching { out.delete() }
                    if (!part.renameTo(out)) {
                        runCatching { target.listFiles()?.forEach { it.delete() } }
                        return@withContext failed(
                            model,
                            "Couldn't finish installing. Tap “Check again” and retry.",
                        )
                    }
                }

                startMeter(
                    DwTransferPhase.VERIFYING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                finishOrCleanUp(model, target, onBytesHashed = { countedBytes = it })
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            runCatching { target.listFiles()?.forEach { it.delete() } }
            if (stopIntent != DwTransferControlState.PAUSED) {
                parts.forEach { (_, part) -> runCatching { part.delete() } }
            }
            throw cancellation
        } catch (error: Exception) {
            runCatching { target.listFiles()?.forEach { it.delete() } }
            val kept = runCatching {
                parts.sumOf { (_, part) -> if (part.isFile) part.length() else 0L }
            }.getOrDefault(0L)
            failed(
                model,
                when {
                    error is DwAsrEndpointSignedOut && kept > 0L ->
                        "Signed out during download. ${dwBytesLabel(kept)} kept — sign in and Resume."
                    error is DwAsrEndpointSignedOut ->
                        "Signed out. Sign in and try again."
                    dwIsDiskFull(error.message) && kept > 0L ->
                        "${dwTransferDiskFullSentence(DwTransferPhase.FETCHING)} " +
                            "${dwBytesLabel(kept)} is on this phone already."
                    dwIsDiskFull(error.message) ->
                        dwTransferDiskFullSentence(DwTransferPhase.FETCHING)
                    kept > 0L ->
                        "Stopped: ${dwModelStopReason(error)}. ${dwBytesLabel(kept)} kept — tap Resume."
                    else ->
                        "Stopped: ${dwModelStopReason(error)}."
                },
            )
        }
    }

    private suspend fun fetchEndpointFile(
        model: DwAsrModel,
        pinned: DwAsrModelFile,
        part: File,
        onMoved: suspend (Long) -> Unit,
    ): Long {
        val onDisk = if (part.isFile) part.length() else 0L
        if (onDisk == pinned.bytes) return 0L

        val url = dwAsrModelFileUrl(apiBaseUrl, model.modelId, pinned.fileName)
        val acceptsRanges = onDisk > 0L && serverAcceptsRanges(url)
        var startFrom = when (dwResumePlan(onDisk, pinned.bytes, acceptsRanges)) {
            DwResumeDecision.RESUME_FROM_PARTIAL -> onDisk
            DwResumeDecision.START_FRESH, DwResumeDecision.DISCARD_AND_RESTART -> {
                runCatching { part.delete() }
                0L
            }
        }

        val builder = Request.Builder().url(url).get()
        if (startFrom > 0L) builder.header("Range", "bytes=$startFrom-")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) throw DwAsrEndpointSignedOut()
            if (!response.isSuccessful) {
                throw IllegalStateException("the file was not available")
            }
            if (startFrom > 0L) {
                val honoured = dwRangeHonoured(
                    response.code,
                    dwParseContentRangeStart(response.header("Content-Range")),
                    startFrom,
                )
                if (!honoured) startFrom = 0L
            }
            val body = response.body ?: throw IllegalStateException("no file arrived")

            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(part, startFrom > 0L).use { output ->
                    val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (startFrom + written > pinned.bytes) {
                            throw IllegalStateException("what arrived was not the expected file")
                        }
                        output.write(buffer, 0, read)
                        onMoved(written)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (startFrom + written != pinned.bytes) {
                throw IllegalStateException("the connection closed before the whole file arrived")
            }
            return written
        }
    }

    private suspend fun download(artifact: DwAsrModelArtifact, container: File) {
        val onDisk = if (container.isFile) container.length() else 0L
        val acceptsRanges = onDisk > 0L && serverAcceptsRanges(artifact.url)
        val plan = dwResumePlan(onDisk, artifact.downloadBytes, acceptsRanges)
        var startFrom = when (plan) {
            DwResumeDecision.RESUME_FROM_PARTIAL -> onDisk
            DwResumeDecision.START_FRESH, DwResumeDecision.DISCARD_AND_RESTART -> {
                runCatching { container.delete() }
                0L
            }
        }

        val builder = Request.Builder().url(artifact.url).get()
        if (startFrom > 0L) builder.header("Range", "bytes=$startFrom-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("the file was not available")
            }
            if (startFrom > 0L) {
                val honoured = dwRangeHonoured(
                    response.code,
                    dwParseContentRangeStart(response.header("Content-Range")),
                    startFrom,
                )
                if (!honoured) startFrom = 0L
            }
            val body = response.body ?: throw IllegalStateException("no file arrived")

            startMeter(DwTransferPhase.FETCHING, artifact.downloadBytes, resumedFrom = startFrom)
            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(container, startFrom > 0L).use { output ->
                    val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (startFrom + written > artifact.downloadBytes) {
                            throw IllegalStateException("what arrived was not the expected file")
                        }
                        output.write(buffer, 0, read)
                        publishProgress(written, artifact.downloadBytes - startFrom)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (startFrom + written != artifact.downloadBytes) {
                throw IllegalStateException("the connection closed before the whole file arrived")
            }
        }
    }

    private fun serverAcceptsRanges(url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            response.isSuccessful &&
                response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    private suspend fun startMeter(
        phase: DwTransferPhase,
        total: Long?,
        resumedFrom: Long,
        fedByTick: Boolean = false,
    ) {
        val opened = DwTransferMeter(totalBytes = total, resumedFromBytes = resumedFrom)
        countedBytes = 0L
        withContext(Dispatchers.Main) {
            meter = opened
            meterFedByTick = fedByTick
            this@DwAsrModelController.phase = phase
            readout = opened.observe(0L, SystemClock.elapsedRealtime())
        }
    }

    private suspend fun publishProgress(movedThisAttempt: Long, attemptTotal: Long) {
        currentCoroutineContext().ensureActive()
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishedAt < DW_PROGRESS_MIN_INTERVAL_MS && movedThisAttempt < attemptTotal) return
        lastPublishedAt = now
        val live = meter ?: return
        val next = live.observe(movedThisAttempt, now)
        withContext(Dispatchers.Main) {
            if (status.state == DwAsrModelState.INSTALLING) {
                readout = next
                status = status.copy(percent = next.percent)
            }
        }
    }

    private var lastPublishedAt: Long = 0L

    private fun finishOrCleanUp(
        model: DwAsrModel,
        target: File,
        onBytesHashed: ((Long) -> Unit)? = null,
    ): DwAsrModelStatus {
        val installed = readInstalled(model, onBytesHashed)
        if (installed.state == DwAsrModelState.INSTALLED) return installed
        runCatching { target.listFiles()?.forEach { it.delete() } }
        return failed(
            model,
            installed.failure ?: "Files didn't verify, so nothing was installed.",
        )
    }

    private fun cleanUp(container: File, target: File) {
        runCatching { container.delete() }
        runCatching { target.listFiles()?.forEach { it.delete() } }
    }

    private fun failed(model: DwAsrModel, sentence: String): DwAsrModelStatus =
        DwAsrModelStatus(DwAsrModelState.FAILED, model, failure = sentence)

    private fun failedAfterCleanUp(
        model: DwAsrModel,
        container: File,
        target: File,
        sentence: String,
    ): DwAsrModelStatus {
        cleanUp(container, target)
        return failed(model, sentence)
    }

    fun remove() {
        val pinned = status.model ?: model ?: return
        job?.cancel()
        busy = true
        job = scope.launch {
            val next = withContext(Dispatchers.IO) {
                DwAsrSpeechModel.release()
                val dir = dwAsrModelDir(context, pinned.modelId)
                runCatching { dir.listFiles()?.forEach { it.delete() } }
                runCatching { dir.delete() }
                readInstalled(pinned)
            }
            status = next
            DwAsrModelRun.publish(next)
            busy = false
        }
    }

    fun release() {
        if (status.state == DwAsrModelState.INSTALLING && phase == DwTransferPhase.FETCHING) {
            stopIntent = DwTransferControlState.PAUSED
        }
        job?.cancel()
        job = null
        busy = false
    }
}

private class DwAsrEndpointSignedOut : IllegalStateException("you were signed out")

private fun dwModelStopReason(error: Throwable): String =
    (error as? IllegalStateException)?.message ?: "the connection dropped"

private const val DW_ASR_MODEL_BUFFER = 64 * 1024

private const val DW_PROGRESS_MIN_INTERVAL_MS = 250L

@Composable
internal fun rememberDwAsrModel(active: Boolean): DwAsrModelController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { DwAsrModelController(context, scope) }
    DisposableEffect(active) {
        if (active) controller.refresh()
        onDispose { if (active) controller.release() }
    }
    return controller
}

@Composable
internal fun DwAsrModelBody(
    controller: DwAsrModelController,
    languageLabels: Map<String, String>,
    support: DwRecognitionSupport?,
    modifier: Modifier = Modifier,
) {
    val reading = controller.measurement
    if (controller.status.state == DwAsrModelState.INSTALLING) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                controller.tick(SystemClock.elapsedRealtime())
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reading == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Looking at what this phone has…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        val offer = dwAsrModelOffer(
            controller.status,
            reading,
            controller.connection,
            controller.stagedFilesPresent,
            controller.modelCatalogue,
            controller.artifactCatalogue,
            controller.endpoint,
        )
        val source = dwAsrModelSourceFor(
            offer,
            controller.endpoint,
            controller.stagedFilesPresent,
            controller.pausedSource,
        )

        if (offer == DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD ||
            offer == DwAsrModelOffer.NOTHING_PINNED
        ) {
            return@Column
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Speech model",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                dwAsrModelStateLabel(controller.status.state),
                color = if (controller.status.state == DwAsrModelState.INSTALLED) {
                    MaterialTheme.field.success
                } else {
                    MaterialTheme.field.muted
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            dwAsrModelOfferSentence(
                offer,
                reading,
                controller.modelCatalogue,
                controller.artifactCatalogue,
                source,
            ),
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (controller.manifestVerdict == DwAsrManifestVerdict.DISAGREES_ON_DIGEST ||
            controller.manifestVerdict == DwAsrManifestVerdict.DISAGREES_ON_SIZE
        ) {
            Text(
                "The online model isn't the expected file. Ask an admin.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            dwAsrModelWhatItBuysSentence(languageLabels, support),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        controller.status.failure?.let { failure ->
            Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (offer == DwAsrModelOffer.RESUME && controller.partialOnDiskBytes > 0L) {
            Text(
                dwPausedSentence(controller.partialOnDiskBytes),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        controller.readout?.let { live ->
            val stage = controller.phase
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                stage?.let {
                    Text(dwTransferHeading(it), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                }
                live.percent?.let { percent ->
                    FieldLinearProgressIndicator(
                        progress = { percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(dwTransferLine(live), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
                stage?.let { dwStalledSentence(live, it) }?.let { note ->
                    Text(note, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ActionRow {
            dwAsrModelActionLabel(offer)?.let { label ->
                if (dwAsrModelMayInstall(offer)) {
                    Button(
                        onClick = { controller.install() },
                        enabled = !controller.busy,
                    ) { Text(label) }
                }
            }
            if (controller.status.state == DwAsrModelState.INSTALLING) {
                if (controller.phase == DwTransferPhase.FETCHING) {
                    OutlinedButton(onClick = { controller.pause() }) {
                        Text(dwPauseLabel(DwTransferControlState.RUNNING))
                    }
                }
                OutlinedButton(onClick = { controller.cancel() }) { Text("Cancel") }
            }
            if (offer == DwAsrModelOffer.RESUME) {
                OutlinedButton(onClick = { controller.cancel() }, enabled = !controller.busy) {
                    Text("Cancel")
                }
            }
            if (offer == DwAsrModelOffer.ALREADY_INSTALLED) {
                OutlinedButton(onClick = { controller.remove() }, enabled = !controller.busy, modifier = Modifier.destructiveAction()) {
                    Text("Remove it")
                }
            }
            OutlinedButton(
                onClick = { controller.refresh() },
                enabled = !controller.busy && controller.status.state != DwAsrModelState.INSTALLING
            ) { Text("Check again") }
        }
    }
}
