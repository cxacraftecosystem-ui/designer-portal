package com.designprototype.workshop.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class DwDictateDto(
    val status: String = "",
    val text: String = "",
    val provider: String? = null,
    val languageHint: String? = null,
    val message: String? = null,
    val mediaId: String? = null,

    val dictationsLimit: Int? = null,
    val dictationsUsed: Int? = null,
    val dictationsRemaining: Int? = null,
    val dictationDay: String? = null,
)

@Serializable
data class DwDictationAllowanceDto(
    val dictationsLimit: Int? = null,
    val dictationsUsed: Int? = null,
    val dictationsRemaining: Int? = null,
    val dictationDay: String? = null,
)

class DwDictationNotConfigured(val detail: String) : Exception(detail)

class DwDictationConsentRefused(val detail: String?) : Exception(detail)

class DwDictationCapRefused(
    val detail: String?,
    val day: String?,
    val limit: Int?,
    val retryAfterSeconds: Int?,
) : Exception(detail) {
    val transientThrottle: Boolean get() = day == null && retryAfterSeconds != null
}

object DwDictationRun {

    @Volatile
    private var repository: WorkshopRepository? = null

    internal fun adopt(instance: WorkshopRepository) {
        repository = instance
    }

    fun repository(): WorkshopRepository? = repository

    @Volatile
    private var notConfigured: Boolean = false

    val serverRouteUnavailable: Boolean get() = notConfigured

    fun serverSaidNotConfigured() {
        notConfigured = true
    }

    private val refusedLanguages = ConcurrentHashMap.newKeySet<String>()

    fun engineRefused(tag: String) {
        refusedLanguages.add(dwNormalizeLanguageTag(tag))
    }

    fun engineHasRefused(tag: String): Boolean =
        refusedLanguages.contains(dwNormalizeLanguageTag(tag))

    fun forgetRefusal(tag: String) {
        refusedLanguages.remove(dwNormalizeLanguageTag(tag))
    }

    data class Published(
        val serverId: String?,
        val consent: DwTier3Consent,
    )

    @Volatile
    private var published = Published(serverId = null, consent = DwTier3Consent.NOT_RECORDED)

    private val consentPublication = java.util.concurrent.atomic.AtomicLong(0L)

    fun publishedWorkshop(): Published = published

    private val consentAnswers = ConcurrentHashMap<String, DwTier3Consent>()

    fun rememberConsentAnswer(workshopId: String, consent: DwTier3Consent) {
        if (workshopId.isBlank()) return
        consentAnswers[workshopId] = consent
    }

    fun consentAnswerSeen(workshopId: String): DwTier3Consent? = consentAnswers[workshopId]

    fun publishWorkshopConsent(consent: DwTier3Consent, serverWorkshopId: String?): Long {
        val token = consentPublication.incrementAndGet()
        published = Published(
            serverId = serverWorkshopId?.takeIf { it.isNotBlank() },
            consent = consent,
        )
        return token
    }

    fun forgetWorkshopConsent(token: Long) {
        if (consentPublication.get() == token) {
            published = Published(serverId = null, consent = DwTier3Consent.NOT_RECORDED)
        }
    }

    fun capView(context: Context): DwDictationCapView {
        val userId = signedInUserId()
        return dwDictationCapView(
            stored = DwDictationAllowanceStore.read(context, userId),
            userId = userId,
            today = dwDictationIstDay(),
        )
    }

    fun learnAllowance(context: Context, answer: DwDictateDto) {
        val allowance = dwDictationAllowanceOf(answer, signedInUserId()) ?: return
        DwDictationAllowanceStore.write(context, allowance)
    }

    fun learnCapRefusal(context: Context, refusal: DwDictationCapRefused) {
        val userId = signedInUserId() ?: return
        val today = dwDictationIstDay()
        val day = refusal.day?.trim()?.takeIf { it.isNotEmpty() }
        val record = if (day != null) {
            DwDictationAllowance(userId = userId, day = day, limit = refusal.limit, remaining = 0)
        } else {
            dwDictationCapSpentRecord(
                previous = DwDictationAllowanceStore.read(context, userId),
                userId = userId,
                today = today,
            )
        }
        DwDictationAllowanceStore.write(context, record)
    }

    private fun signedInUserId(): String? = repository()?.cachedUser()?.id

    private val swept = java.util.concurrent.atomic.AtomicBoolean(false)

    fun sweepScratchOnce(context: Context) {
        if (swept.compareAndSet(false, true)) dwSweepDictationScratch(context)
    }
}

private fun dictationScratchDir(context: Context): File =
    File(context.cacheDir, "dictation-scratch").apply { mkdirs() }

private const val DW_DICTATION_SCRATCH_STALE_MILLIS = 10L * 60L * 1000L

fun dwSweepDictationScratch(context: Context) {
    val cutoff = System.currentTimeMillis() - DW_DICTATION_SCRATCH_STALE_MILLIS
    runCatching {
        dictationScratchDir(context).listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}

class DwDictationRecorder {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    val recording: Boolean get() = recorder != null

    fun start(context: Context, onLimitReached: () -> Unit): String? {
        discard()
        val file = File(dictationScratchDir(context), "dictation-${UUID.randomUUID()}.m4a")
        val created = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        }.getOrNull() ?: return "Couldn't open the microphone."

        val configured = runCatching {
            created.configure(MediaRecorder.AudioSource.VOICE_RECOGNITION, file)
        }.recoverCatching {
            created.reset()
            created.configure(MediaRecorder.AudioSource.MIC, file)
        }

        if (configured.isFailure) {
            runCatching { created.release() }
            runCatching { file.delete() }
            return "Couldn't open the microphone. Close other apps using it, check permission, and retry."
        }

        created.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
            ) {
                onLimitReached()
            }
        }

        return runCatching {
            created.start()
            recorder = created
            target = file
            null
        }.getOrElse {
            runCatching { created.release() }
            runCatching { file.delete() }
            "Couldn't start recording. Close other apps using the mic and retry."
        }
    }

    private fun MediaRecorder.configure(source: Int, file: File) {
        setAudioSource(source)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(DW_DICTATION_CHANNELS)
        setAudioSamplingRate(DW_DICTATION_SAMPLE_RATE_HZ)
        setAudioEncodingBitRate(DW_DICTATION_BITS_PER_SECOND)
        setOutputFile(file)
        setMaxDuration(DW_DICTATION_MAX_MILLIS)
        runCatching { setMaxFileSize(DW_DICTATION_MAX_BYTES) }
        prepare()
    }

    fun stop(): File? {
        val active = recorder ?: return null
        val file = target
        recorder = null
        target = null
        val failure = runCatching { active.stop() }.exceptionOrNull()
        runCatching { active.release() }
        val finalised = failure == null || failure is IllegalStateException
        if (!finalised || file == null || !file.exists() || file.length() <= 0L) {
            runCatching { file?.delete() }
            return null
        }
        return file
    }

    fun discard() {
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        recorder = null
        runCatching { target?.delete() }
        target = null
    }
}
