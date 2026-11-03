package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.designprototype.workshop.data.DW_ASR_CARD_BLURB
import com.designprototype.workshop.data.DW_ASR_MISMATCH_SENTENCE
import com.designprototype.workshop.data.DW_ASR_OFFER_BLURB
import com.designprototype.workshop.data.DW_ASR_VERIFY_SENTENCE
import com.designprototype.workshop.data.DW_ASR_WHAT_IT_BUYS
import com.designprototype.workshop.data.DwAsrArtifact
import com.designprototype.workshop.data.DwAsrOffer
import com.designprototype.workshop.data.DwAsrRuntimeState
import com.designprototype.workshop.data.DwAsrRuntimeStatus
import com.designprototype.workshop.data.DwAsrVerification
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.dwAsrArtifactFor
import com.designprototype.workshop.data.dwAsrCostSentence
import com.designprototype.workshop.data.dwAsrMayInstall
import com.designprototype.workshop.data.dwAsrOffer
import com.designprototype.workshop.data.dwAsrOfferSentence
import com.designprototype.workshop.data.dwAsrStateLabel
import com.designprototype.workshop.data.dwAsrMeasuredEngineBytes
import com.designprototype.workshop.data.dwAsrVerify
import com.designprototype.workshop.data.dwBytesLabel
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

private const val DW_ASR_DIR = "asr-engine"

private const val DW_ASR_INCOMING = "incoming"

private fun dwAsrAbiDir(context: Context, abi: String): File =
    File(File(context.filesDir, DW_ASR_DIR), abi)

private const val DW_ASR_BUFFER = 64 * 1024

internal fun dwAsrSha256OfFile(
    file: File,
    onBytesHashed: ((Long) -> Unit)? = null,
): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    var hashed = 0L
    file.inputStream().use { stream ->
        val buffer = ByteArray(DW_ASR_BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            hashed += read
            onBytesHashed?.invoke(hashed)
        }
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}.getOrNull()

@Stable
internal class DwAsrRuntimeController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    var status by mutableStateOf(DwAsrRuntimeStatus())
        private set

    var measurement by mutableStateOf<DwDeviceMeasurement?>(null)
        private set

    var connection by mutableStateOf(DwConnection.NONE)
        private set

    var busy by mutableStateOf(false)
        private set

    private var job: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.MINUTES)
            .build()
    }

    fun refresh() {
        if (status.state == DwAsrRuntimeState.DOWNLOADING) return
        job?.cancel()
        busy = true
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) { dwProbeDevice(context) }
            val here = dwConnection(context)
            val artifact = dwAsrArtifactFor(reading.abis)
            val next = withContext(Dispatchers.IO) {
                sweepIncoming()
                if (artifact == null) DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED) else readInstalled(artifact)
            }
            measurement = reading
            connection = here
            status = next
            busy = false
        }
    }

    private fun sweepIncoming() {
        val incoming = File(File(context.filesDir, DW_ASR_DIR), DW_ASR_INCOMING)
        runCatching { incoming.listFiles()?.forEach { it.delete() } }
    }

    private fun readInstalled(artifact: DwAsrArtifact): DwAsrRuntimeStatus {
        val dir = dwAsrAbiDir(context, artifact.abi)
        val verified = mutableListOf<String>()
        artifact.libraries.forEach { library ->
            val file = File(dir, library.fileName)
            if (!file.isFile) return DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED, artifact)
            if (file.length() != library.bytes) {
                runCatching { file.delete() }
                return DwAsrRuntimeStatus(
                    DwAsrRuntimeState.NOT_INSTALLED,
                    artifact,
                    failure = null,
                )
            }
            val digest = dwAsrSha256OfFile(file)
                ?: return DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED, artifact)
            if (dwAsrVerify(library.sha256, digest) != DwAsrVerification.VERIFIED) {
                runCatching { file.delete() }
                return DwAsrRuntimeStatus(
                    DwAsrRuntimeState.FAILED,
                    artifact,
                    failure = DW_ASR_MISMATCH_SENTENCE,
                )
            }
            verified += digest
        }
        return DwAsrRuntimeStatus(DwAsrRuntimeState.INSTALLED, artifact, verifiedSha256 = verified)
    }

    fun install() {
        val reading = measurement ?: return
        val offer = dwAsrOffer(status, reading, connection)
        if (!dwAsrMayInstall(offer)) return
        val artifact = dwAsrArtifactFor(reading.abis) ?: return
        status = DwAsrRuntimeStatus(DwAsrRuntimeState.DOWNLOADING, artifact)
        busy = true
        job = scope.launch {
            val outcome = installNow(artifact)
            status = outcome
            busy = false
        }
    }

    private suspend fun installNow(artifact: DwAsrArtifact): DwAsrRuntimeStatus {
        val incoming = File(File(context.filesDir, DW_ASR_DIR), DW_ASR_INCOMING)
        val container = File(incoming, "engine-${artifact.abi}.zip")
        val target = dwAsrAbiDir(context, artifact.abi)
        return try {
            withContext(Dispatchers.IO) {
                incoming.mkdirs()
                target.mkdirs()
                runCatching { container.delete() }
                runCatching { target.listFiles()?.forEach { it.delete() } }
                download(artifact, container) { percent ->
                    withContext(Dispatchers.Main) {
                        if (status.state == DwAsrRuntimeState.DOWNLOADING) {
                            status = status.copy(percent = percent)
                        }
                    }
                }
                val containerDigest = dwAsrSha256OfFile(container)
                    ?: return@withContext failedAfterCleanUp(
                        artifact, container, target,
                        "Download couldn't be read back (storage full?). Free space and retry.",
                    )
                if (dwAsrVerify(artifact.sha256, containerDigest) != DwAsrVerification.VERIFIED) {
                    cleanUp(container, target)
                    return@withContext failed(artifact, DW_ASR_MISMATCH_SENTENCE)
                }
                ZipFile(container).use { zip ->
                    artifact.libraries.forEach { library ->
                        val entry = zip.getEntry(library.fileName)
                            ?: return@withContext failedAfterCleanUp(
                                artifact, container, target,
                                "Download is missing ${library.fileName}. Tell your admin.",
                            )
                        val out = File(target, library.fileName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(out).use { output -> input.copyTo(output, DW_ASR_BUFFER) }
                        }
                    }
                }
                runCatching { container.delete() }
                val installed = readInstalled(artifact)
                if (installed.state == DwAsrRuntimeState.INSTALLED) {
                    installed
                } else {
                    cleanUp(container, target)
                    failed(
                        artifact,
                        installed.failure ?: "Files didn't verify, so nothing was installed. Retry on a better connection.",
                    )
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            runCatching { cleanUp(container, target) }
            throw cancellation
        } catch (error: Exception) {
            runCatching { cleanUp(container, target) }
            failed(
                artifact,
                "Couldn't download: ${(error as? IllegalStateException)?.message ?: "the connection dropped"}. Try again.",
            )
        }
    }

    private suspend fun download(
        artifact: DwAsrArtifact,
        container: File,
        onPercent: suspend (Int?) -> Unit,
    ) {
        val request = Request.Builder().url(artifact.url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(speechPackRefusalClause(response.code))
            }
            val body = response.body ?: throw IllegalStateException("the server sent no file")
            var written = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                FileOutputStream(container).use { output ->
                    val buffer = ByteArray(DW_ASR_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > artifact.downloadBytes) {
                            throw IllegalStateException(
                                "what arrived was not the expected file"
                            )
                        }
                        output.write(buffer, 0, read)
                        val percent = (written * 100L / artifact.downloadBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onPercent(percent)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (written != artifact.downloadBytes) {
                throw IllegalStateException(
                    "the connection closed before the whole file arrived"
                )
            }
        }
    }

    private fun cleanUp(container: File, target: File) {
        runCatching { container.delete() }
        runCatching { target.listFiles()?.forEach { it.delete() } }
    }

    private fun failed(artifact: DwAsrArtifact, sentence: String): DwAsrRuntimeStatus =
        DwAsrRuntimeStatus(DwAsrRuntimeState.FAILED, artifact, failure = sentence)

    private fun failedAfterCleanUp(
        artifact: DwAsrArtifact,
        container: File,
        target: File,
        sentence: String,
    ): DwAsrRuntimeStatus {
        cleanUp(container, target)
        return failed(artifact, sentence)
    }

    fun remove() {
        val artifact = status.artifact ?: return
        job?.cancel()
        busy = true
        job = scope.launch {
            val next = withContext(Dispatchers.IO) {
                val dir = dwAsrAbiDir(context, artifact.abi)
                runCatching { dir.listFiles()?.forEach { it.delete() } }
                runCatching { dir.delete() }
                readInstalled(artifact)
            }
            status = next
            busy = false
        }
    }

    fun release() {
        job?.cancel()
        job = null
        busy = false
    }
}

@Composable
internal fun rememberDwAsrRuntime(active: Boolean): DwAsrRuntimeController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { DwAsrRuntimeController(context, scope) }
    DisposableEffect(active) {
        if (active) controller.refresh()
        onDispose { if (active) controller.release() }
    }
    return controller
}

internal fun speechPackRefusalClause(code: Int): String = when (code) {
    401, 403 -> "this account is not allowed to download it"
    404 -> "it is not available right now, so try again later"
    in 500..599 -> "the server could not send it just now, so try again later"
    else -> "the server would not send it"
}
