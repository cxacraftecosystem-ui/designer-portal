package com.designprototype.workshop.data

import java.util.Locale

enum class DwAiTier {
    TIER_1,

    TIER_2,

    TIER_3,
    ;

    val number: Int get() = name.substringAfterLast('_').toInt()

    val where: String
        get() = when (this) {
            TIER_1, TIER_2 -> "on this phone"
            TIER_3 -> "online"
        }
}

enum class DwThermalState {
    UNMEASURED,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
    ;

    val tooHotToStart: Boolean
        get() = this == MODERATE || this == SEVERE || this == CRITICAL ||
            this == EMERGENCY || this == SHUTDOWN
}

data class DwDeviceMeasurement(
    val totalRamBytes: Long? = null,

    val availableRamBytes: Long? = null,

    val lowRamDevice: Boolean? = null,

    val freeStorageBytes: Long? = null,

    val abis: List<String> = emptyList(),

    val thermal: DwThermalState = DwThermalState.UNMEASURED,

    val charging: Boolean? = null,

    val takenAtElapsedMs: Long = 0L,
)

private const val MIB: Long = 1024L * 1024L

const val DW_LOW_RAM_CEILING_BYTES: Long = 3L * 1024L * MIB

const val DW_FOUR_GB_CEILING_BYTES: Long = 5_500L * MIB

const val DW_EIGHT_GB_CEILING_BYTES: Long = 11_000L * MIB

enum class DwDeviceClass {
    UNMEASURED,

    LOW_RAM,

    SMALL_4GB,

    MID_6_TO_8GB,

    LARGE_12GB_PLUS,
}

fun dwDeviceClass(measurement: DwDeviceMeasurement): DwDeviceClass {
    if (measurement.lowRamDevice == true) return DwDeviceClass.LOW_RAM
    val total = measurement.totalRamBytes
    if (total == null || total <= 0L) return DwDeviceClass.UNMEASURED
    return when {
        total < DW_LOW_RAM_CEILING_BYTES -> DwDeviceClass.LOW_RAM
        total < DW_FOUR_GB_CEILING_BYTES -> DwDeviceClass.SMALL_4GB
        total < DW_EIGHT_GB_CEILING_BYTES -> DwDeviceClass.MID_6_TO_8GB
        else -> DwDeviceClass.LARGE_12GB_PLUS
    }
}

data class DwModelRtfBand(
    val fastest: Double,
    val slowest: Double,
    val utterances: Int,
    val measuredOn: String,
) {
    init {
        require(fastest > 0.0 && slowest > 0.0) {
            "A real-time-factor band is a ratio of two durations and cannot be zero or negative. If " +
                "nobody has timed this model, pass null for the whole band — that is the word " +
                "“unmeasured”, and it is a better answer than a number nobody took."
        }
        require(slowest >= fastest) {
            "A real-time-factor band runs fastest to slowest. Swapping them would have every " +
                "sentence in this app size a designer's wait off the best case, which is the exact " +
                "failure docs/DEVICE-TIER-MEASUREMENT.md's own correction was written about."
        }
        require(utterances > 0) {
            "A band needs the number of decodes it spans. One reading is not a band, and a band with " +
                "no count behind it cannot be argued with."
        }
        require(measuredOn.isNotBlank()) {
            "A timing needs the handset it was timed on. Silicon, thermal state and governor decide " +
                "this number, so it is meaningless without the phone attached to it."
        }
    }
}

data class DwModelAccuracy(
    val tag: String,
    val cerPercent: Double,
    val werPercent: Double,
    val corpus: String,
    val utterances: Int,
    val referenceWords: Int,
) {
    init {
        require(tag.isNotBlank()) {
            "An accuracy row needs the BCP-47 tag it scored, so it can be matched against the " +
                "languages this app offers rather than read as a claim about the model in general."
        }
        require(cerPercent >= 0.0 && werPercent >= 0.0) {
            "Error rates are not negative. If nobody scored this language, leave the row out — an " +
                "absent row is the word “unmeasured”, and a zero would read as a perfect transcript."
        }
        require(corpus.isNotBlank()) {
            "An accuracy row needs to say what it was scored against. Studio read speech and a " +
                "courtyard are different tests, and a WER with no corpus behind it will be read as " +
                "the harder one."
        }
        require(utterances > 0 && referenceWords > 0) {
            "An accuracy row needs the size of the sample behind it. This repository has already " +
                "shipped a claim with no sample behind it once; a rate over an unstated number of " +
                "words cannot be argued with."
        }
    }
}

data class DwModelPlan(
    val modelId: String,
    val quantisation: String,
    val abi: String,
    val languages: List<String>?,
    val contextCapTokens: Int?,
    val runBound: String,
    val onDiskBytes: Long,
    val peakRssBytes: Long,
    val measuredOn: String,
    val survivesBackgrounding: Boolean?,
    val realTimeFactor: DwModelRtfBand? = null,
    val accuracy: List<DwModelAccuracy> = emptyList(),
    val unmeasuredLanguagesNote: String? = null,
) {
    fun servesLanguage(tag: String): Boolean = languages?.any { dwTagCovers(it, tag) } == true

    fun accuracyFor(tag: String): DwModelAccuracy? = accuracy.firstOrNull { dwTagCovers(it.tag, tag) }

    init {
        require(modelId.isNotBlank()) {
            "A model plan needs the exact artifact id, not a family name. Take it from the export " +
                "you actually downloaded and record it in docs/DEVICE-TIER-MEASUREMENT.md too."
        }
        require(quantisation.isNotBlank()) {
            "A model plan needs its quantisation. int4 and fp16 of the same weights are different " +
                "artifacts with different footprints; naming the family without it says nothing."
        }
        require(abi.isNotBlank()) {
            "A model plan needs the ABI its runtime build exists for, e.g. arm64-v8a. Read it off " +
                "the runtime you are shipping and put it here."
        }
        require(contextCapTokens == null || contextCapTokens > 0) {
            "A model plan's context cap is either a real cap or null. KV-cache grows with context " +
                "and can exceed the weights, so a decoder named without a cap has not said what " +
                "will be run — measure the peak RSS at a fixed cap and record both. Pass null only " +
                "for a model family that has no such dial at all (a CTC speech model takes audio " +
                "whole and emits one result), and say what bounds a run in `runBound` instead. Zero " +
                "is neither and is refused."
        }
        require(runBound.isNotBlank()) {
            "A model plan has to say what bounds ONE RUN of it and what its peak RSS was measured " +
                "over — “a 2,048-token context cap”, or “one utterance of up to N seconds of 16 kHz " +
                "audio, decoded whole”. This is the field that makes “we recommend this model” " +
                "impossible to say without also saying what was actually run; it has no default for " +
                "that reason."
        }
        require(accuracy.map { dwNormalizeLanguageTag(it.tag) }.distinct().size == accuracy.size) {
            "A model plan scores each language once. Two rows for one tag would let a card print " +
                "whichever was written first, and the one it printed would be a coin toss between " +
                "two real measurements."
        }
        require(onDiskBytes > 0L) {
            "A model plan needs its real on-disk size — this screen states the cost before the tap, " +
                "unlike the language packs, precisely because our own models have a known size."
        }
        require(peakRssBytes > 0L) {
            "A model plan needs a measured peak RSS at its context cap. Run it on the handset and " +
                "read the number; docs/DEVICE-TIER-MEASUREMENT.md says why the on-disk size will " +
                "not do instead."
        }
        require(measuredOn.isNotBlank()) {
            "A model plan needs the name of the handset its peak RSS was measured on. A number with " +
                "no device behind it cannot be checked, and this repository has already shipped one."
        }
        require(languages == null || languages.all { it.isNotBlank() }) {
            "A model plan's language list may not contain a blank tag. Write the BCP-47 tags the " +
                "artifact actually serves (or-IN, hi-IN…), or pass null, which is this app's word " +
                "for “nobody has checked which languages this model can hear”. A blank entry is " +
                "neither, and it would silently match no language row while looking like an answer."
        }
        require(
            languages == null ||
                languages.map { dwNormalizeLanguageTag(it) }.distinct().size == languages.size
        ) {
            "A model plan lists each language it serves once. Two spellings of one tag (or-IN and " +
                "or_IN, hi-IN and HI-in) are the same language to every comparison in this app, and " +
                "a duplicated row would make a coverage count read higher than the languages behind it."
        }
    }
}

val DW_TIER2_CATALOGUE: List<DwModelPlan> = DW_TIER2_PLANS

val DW_TIER1_CATALOGUE: List<DwModelPlan> = listOf(
    DwModelPlan(
        modelId = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        quantisation = "int8",
        abi = "arm64-v8a",
        languages = listOf("hi-IN"),
        contextCapTokens = null,
        runBound = "one utterance handed to the recogniser whole and decoded in one pass — this is " +
            "a non-streaming CTC model with no context window and no KV-cache, so what moves the " +
            "resident set is the LENGTH OF THE AUDIO, not a token count. The peak below was reached " +
            "decoding FLEURS utterances of roughly 9–14 seconds of 16 kHz mono at numThreads = 2. " +
            "What a two-minute recording costs in memory is UNMEASURED — nobody has decoded one.",
        onDiskBytes = 365_438_543L,
        peakRssBytes = 1_258_713_088L,
        measuredOn = "Samsung Galaxy M32 (SM-M325F), Android 13, arm64-v8a, idle and on the charger",
        survivesBackgrounding = null,
        realTimeFactor = DwModelRtfBand(
            fastest = 1.078,
            slowest = 2.967,
            utterances = 12,
            measuredOn = "Samsung Galaxy M32 (SM-M325F), Android 13, numThreads = 2",
        ),
        accuracy = listOf(
            DwModelAccuracy(
                tag = "or-IN",
                cerPercent = 15.2,
                werPercent = 53.3,
                corpus = "Google FLEURS or_in dev — studio read speech by a professional speaker, " +
                    "which is an EASIER test than a courtyard, so this is a ceiling and a real " +
                    "workshop would score worse",
                utterances = 3,
                referenceWords = 60,
            ),
            DwModelAccuracy(
                tag = "hi-IN",
                cerPercent = 7.3,
                werPercent = 24.2,
                corpus = "Google FLEURS hi_in dev — studio read speech, the same ceiling caveat as " +
                    "the row above",
                utterances = 3,
                referenceWords = 91,
            ),
            DwModelAccuracy(
                tag = "gu-IN",
                cerPercent = 6.1,
                werPercent = 24.9,
                corpus = "Google FLEURS gu_in validation, first 3 utterances — studio read speech, " +
                    "the same ceiling caveat as every row here. Decoded on the handset 2026-08-13 " +
                    "through the shipped OMNILINGUAL_ASR_CTC branch",
                utterances = 3,
                referenceWords = 63,
            ),
            DwModelAccuracy(
                tag = "kn-IN",
                cerPercent = 6.2,
                werPercent = 24.7,
                corpus = "Google FLEURS kn_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 55,
            ),
            DwModelAccuracy(
                tag = "pa-IN",
                cerPercent = 10.7,
                werPercent = 24.9,
                corpus = "Google FLEURS pa_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 94,
            ),
            DwModelAccuracy(
                tag = "bn-IN",
                cerPercent = 10.6,
                werPercent = 43.2,
                corpus = "Google FLEURS bn_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 53,
            ),
            DwModelAccuracy(
                tag = "ne-IN",
                cerPercent = 15.3,
                werPercent = 45.9,
                corpus = "Google FLEURS ne_np validation, first 3 utterances — studio read speech, " +
                    "and the corpus is Nepal's rather than India's, which is the closest FLEURS has " +
                    "to this app's ne-IN. Same handset run as the row above",
                utterances = 3,
                referenceWords = 38,
            ),
            DwModelAccuracy(
                tag = "ta-IN",
                cerPercent = 8.2,
                werPercent = 52.0,
                corpus = "Google FLEURS ta_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 58,
            ),
            DwModelAccuracy(
                tag = "ml-IN",
                cerPercent = 9.5,
                werPercent = 54.1,
                corpus = "Google FLEURS ml_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 51,
            ),
            DwModelAccuracy(
                tag = "te-IN",
                cerPercent = 18.0,
                werPercent = 54.6,
                corpus = "Google FLEURS te_in validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the row above",
                utterances = 3,
                referenceWords = 46,
            ),
            DwModelAccuracy(
                tag = "ur-IN",
                cerPercent = 85.5,
                werPercent = 100.0,
                corpus = "Google FLEURS ur_pk validation, first 3 utterances — studio read speech. " +
                    "Same handset run as the rows above. The model answered in DEVANAGARI on all " +
                    "three, emitting no Arabic-script character at all, so this is a script failure " +
                    "and not a word-accuracy figure",
                utterances = 3,
                referenceWords = 87,
            ),
        ),
        unmeasuredLanguagesNote = "Meta CLAIM more than 1,600 languages for the family this artifact " +
            "was exported from. That is a claim about a family and this app does not repeat it as a " +
            "property of this file. ELEVEN languages have now been measured on a handset — Hindi, " +
            "Odia, Bengali, Gujarati, Kannada, Malayalam, Nepali, Punjabi, Tamil, Telugu and Urdu, " +
            "eleven of the nineteen this app offers — and only Hindi is offered, because only Hindi " +
            "clears the bar the catalogue argues for. The eight not yet measured are Assamese, " +
            "English (India), Marathi, Sanskrit, Konkani, Manipuri, Kashmiri and Sindhi: UNMEASURED, " +
            "in that word, and neither claimed nor denied. The vocabulary is a separate and weaker " +
            "fact, and 2026-08-13 turned it into the clearest example this repository has of why: " +
            "tokens.txt carries 155 Arabic-script tokens, and handed Urdu speech the model wrote " +
            "DEVANAGARI on all three utterances and no Arabic character at all. Being able to spell a " +
            "script is necessary for emitting it and is not sufficient, and Urdu is now the measured " +
            "proof rather than the worked example. One language can be denied without audio for the " +
            "same reason read the other way: tokens.txt contains ZERO Meetei Mayek characters, so " +
            "Manipuri written in its own script is not something this artifact can produce at all.",
    ),
)

const val DW_TIER1_RUNTIME_PRESENT: Boolean = true

const val DW_TIER2_RUNTIME_PRESENT: Boolean = true

const val DW_MODEL_FREE_RAM_MARGIN_BYTES: Long = 512L * MIB

const val DW_MODEL_FREE_STORAGE_MARGIN_BYTES: Long = 1024L * MIB

enum class DwTierRefusal {
    NO_MEASURED_MODEL,

    NO_RUNTIME_IN_THIS_BUILD,

    RUNTIME_NOT_INSTALLED,

    RUNTIME_UNMEASURED,

    DEVICE_TOO_SMALL,

    NOT_ENOUGH_FREE_RAM_NOW,

    NOT_ENOUGH_FREE_STORAGE,

    ABI_NOT_BUILT_FOR,

    FREE_RAM_UNMEASURED,

    FREE_STORAGE_UNMEASURED,

    ABI_UNMEASURED,

    LOAD_FAILED_HERE_BEFORE,
}

sealed interface DwTierOffer {

    data class Available(
        val plan: DwModelPlan,
        val headroomBytes: Long,
    ) : DwTierOffer

    data class None(val refusal: DwTierRefusal) : DwTierOffer
}

data class DwLoadFailureNote(
    val tier: DwAiTier,
    val modelId: String,
    val contextCapTokens: Int?,
    val detail: String,
    val chosenAgainstAdvice: Boolean = false,
)

data class DwTierRecommendation(
    val deviceClass: DwDeviceClass,
    val measurement: DwDeviceMeasurement,
    val tier1: DwTierOffer,
    val tier2: DwTierOffer,
    val connection: DwConnection,
    val tier1Choices: List<DwModelChoice> = emptyList(),
    val tier2Choices: List<DwModelChoice> = emptyList(),
) {
    val tier3Available: Boolean get() = connection != DwConnection.NONE
}

internal fun dwPlanFits(plan: DwModelPlan, measurement: DwDeviceMeasurement): DwTierRefusal? {
    if (measurement.lowRamDevice == true) return DwTierRefusal.DEVICE_TOO_SMALL
    val total = measurement.totalRamBytes
    if (total != null && total > 0L && plan.peakRssBytes + DW_MODEL_FREE_RAM_MARGIN_BYTES > total) {
        return DwTierRefusal.DEVICE_TOO_SMALL
    }

    if (measurement.abis.isEmpty()) return DwTierRefusal.ABI_UNMEASURED
    if (measurement.abis.none { it.equals(plan.abi, ignoreCase = true) }) {
        return DwTierRefusal.ABI_NOT_BUILT_FOR
    }

    val storage = measurement.freeStorageBytes ?: return DwTierRefusal.FREE_STORAGE_UNMEASURED
    if (storage < plan.onDiskBytes + DW_MODEL_FREE_STORAGE_MARGIN_BYTES) {
        return DwTierRefusal.NOT_ENOUGH_FREE_STORAGE
    }

    val free = measurement.availableRamBytes ?: return DwTierRefusal.FREE_RAM_UNMEASURED
    if (free < plan.peakRssBytes + DW_MODEL_FREE_RAM_MARGIN_BYTES) {
        return DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW
    }
    return null
}

internal fun dwHeadroomBytes(plan: DwModelPlan, measurement: DwDeviceMeasurement): Long? =
    measurement.availableRamBytes?.let { it - plan.peakRssBytes }

internal fun dwBestPlan(
    catalogue: List<DwModelPlan>,
    measurement: DwDeviceMeasurement,
    failures: List<DwLoadFailureNote>,
): DwTierOffer {
    val untried = catalogue.filterNot { plan ->
        failures.any { it.modelId == plan.modelId && it.contextCapTokens == plan.contextCapTokens }
    }
    if (untried.isEmpty()) {
        return DwTierOffer.None(
            if (catalogue.isEmpty()) DwTierRefusal.NO_MEASURED_MODEL
            else DwTierRefusal.LOAD_FAILED_HERE_BEFORE
        )
    }
    val ranked = untried.sortedWith(
        compareByDescending<DwModelPlan> { it.peakRssBytes }.thenBy { it.modelId }
    )
    ranked.forEach { plan ->
        if (dwPlanFits(plan, measurement) == null) {
            val headroom = dwHeadroomBytes(plan, measurement)
            return DwTierOffer.Available(plan, headroom ?: 0L)
        }
    }
    val smallest = ranked.last()
    return DwTierOffer.None(dwPlanFits(smallest, measurement) ?: DwTierRefusal.DEVICE_TOO_SMALL)
}

internal fun dwTier2Offer(
    measurement: DwDeviceMeasurement,
    deviceClass: DwDeviceClass,
    catalogue: List<DwModelPlan>,
    failures: List<DwLoadFailureNote>,
    runtimeInApk: Boolean = DW_TIER2_RUNTIME_PRESENT,
): DwTierOffer {
    if (catalogue.isEmpty()) return DwTierOffer.None(DwTierRefusal.NO_MEASURED_MODEL)
    if (!runtimeInApk) return DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
    if (deviceClass == DwDeviceClass.LOW_RAM && measurement.lowRamDevice == true) {
        return DwTierOffer.None(DwTierRefusal.DEVICE_TOO_SMALL)
    }
    return dwBestPlan(catalogue, measurement, failures.filter { it.tier == DwAiTier.TIER_2 })
}

internal fun dwTier1Offer(
    measurement: DwDeviceMeasurement,
    catalogue: List<DwModelPlan>,
    failures: List<DwLoadFailureNote>,
    runtime: DwAsrRuntimeStatus,
    connection: DwConnection,
    artifacts: List<DwAsrArtifact>,
    runtimeInApk: Boolean = DW_TIER1_RUNTIME_PRESENT,
): DwTierOffer {
    val engineInstalled = runtimeInApk || dwAsrMayLoad(runtime)
    if (engineInstalled) {
        if (catalogue.isEmpty()) return DwTierOffer.None(DwTierRefusal.NO_MEASURED_MODEL)
        return dwBestPlan(catalogue, measurement, failures.filter { it.tier == DwAiTier.TIER_1 })
    }
    return DwTierOffer.None(
        when (dwAsrOffer(runtime, measurement, connection, artifacts, catalogue, runtimeInApk)) {
            DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL -> DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD

            DwAsrOffer.INSTALL, DwAsrOffer.RETRY, DwAsrOffer.NO_CONNECTION,
            DwAsrOffer.IN_PROGRESS -> DwTierRefusal.RUNTIME_NOT_INSTALLED

            DwAsrOffer.NO_MODEL_TO_FEED_IT -> DwTierRefusal.NO_MEASURED_MODEL

            DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR -> DwTierRefusal.ABI_NOT_BUILT_FOR
            DwAsrOffer.PROCESSOR_UNMEASURED -> DwTierRefusal.ABI_UNMEASURED
            DwAsrOffer.STORAGE_UNMEASURED -> DwTierRefusal.FREE_STORAGE_UNMEASURED
            DwAsrOffer.NOT_ENOUGH_STORAGE -> DwTierRefusal.NOT_ENOUGH_FREE_STORAGE

            DwAsrOffer.ALREADY_INSTALLED -> DwTierRefusal.NO_MEASURED_MODEL

            DwAsrOffer.BUNDLED_IN_THIS_BUILD -> DwTierRefusal.NO_MEASURED_MODEL

            DwAsrOffer.UNKNOWN -> DwTierRefusal.RUNTIME_UNMEASURED
        }
    )
}

fun dwRecommendTiers(
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    failures: List<DwLoadFailureNote> = emptyList(),
    runtime: DwAsrRuntimeStatus = DwAsrRuntimeStatus(),
    catalogue2: List<DwModelPlan> = DW_TIER2_CATALOGUE,
    catalogue1: List<DwModelPlan> = DW_TIER1_CATALOGUE,
    artifacts: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
    runtimeInApk: Boolean = DW_TIER1_RUNTIME_PRESENT,
    tier2RuntimeInApk: Boolean = DW_TIER2_RUNTIME_PRESENT,
): DwTierRecommendation {
    val deviceClass = dwDeviceClass(measurement)
    val tier1 =
        dwTier1Offer(measurement, catalogue1, failures, runtime, connection, artifacts, runtimeInApk)
    val tier2 = dwTier2Offer(measurement, deviceClass, catalogue2, failures, tier2RuntimeInApk)
    return DwTierRecommendation(
        deviceClass = deviceClass,
        measurement = measurement,
        tier1 = tier1,
        tier2 = tier2,
        connection = connection,
        tier1Choices = dwModelChoices(catalogue1, measurement, failures, tier1, DwAiTier.TIER_1),
        tier2Choices = dwModelChoices(catalogue2, measurement, failures, tier2, DwAiTier.TIER_2),
    )
}

fun dwTierDownloadMayBeOffered(offer: DwTierOffer, connection: DwConnection): Boolean =
    offer is DwTierOffer.Available && connection != DwConnection.NONE

const val DW_PROBE_FRESH_FOR_MS: Long = 2L * 60L * 1000L

fun dwProbeIsStale(takenAtElapsedMs: Long, nowElapsedMs: Long): Boolean {
    val age = nowElapsedMs - takenAtElapsedMs
    return age < 0L || age > DW_PROBE_FRESH_FOR_MS
}

enum class DwTier2Window {
    NOTHING_TO_RUN,

    WAIT_CAPTURE_IS_OPEN,

    WAIT_DEVICE_IS_HOT,

    RUN_NOW,
}

fun dwTier2RunWindow(
    offer: DwTierOffer,
    capturing: Boolean,
    thermal: DwThermalState,
): DwTier2Window = when {
    offer !is DwTierOffer.Available -> DwTier2Window.NOTHING_TO_RUN
    capturing -> DwTier2Window.WAIT_CAPTURE_IS_OPEN
    thermal.tooHotToStart -> DwTier2Window.WAIT_DEVICE_IS_HOT
    else -> DwTier2Window.RUN_NOW
}

fun dwTier2PowerAdvice(charging: Boolean?): String? =
    if (charging == false) {
        "On battery — long jobs run better while charging."
    } else {
        null
    }

data class DwTierFallback(
    val goesTo: DwAiTier?,
    val sentence: String,
)

fun dwFallbackAfterLoadFailure(
    note: DwLoadFailureNote,
    connection: DwConnection,
): DwTierFallback {
    val what = note.contextCapTokens
        ?.let { "${note.modelId} at a $it-token context cap" }
        ?: note.modelId
    val opening = if (note.chosenAgainstAdvice) {
        "This phone couldn't load $what — the outcome this app said to expect when you chose it."
    } else {
        "This phone couldn't load $what, although its memory and storage said it would fit."
    }
    return when {
        connection != DwConnection.NONE -> DwTierFallback(
            goesTo = DwAiTier.TIER_3,
            sentence = "$opening Done online instead; it won't be tried here again.",
        )
        else -> DwTierFallback(
            goesTo = null,
            sentence = "$opening Offline — it will run online when there's signal. Nothing has been lost.",
        )
    }
}

fun dwBytesLabel(bytes: Long?): String {
    if (bytes == null) return "unmeasured"
    val kb = 1000.0
    val mb = kb * 1000.0
    val gb = mb * 1000.0
    val magnitude = kotlin.math.abs(bytes.toDouble())
    return when {
        magnitude >= gb -> String.format(Locale.ROOT, "%.1f GB", bytes / gb)
        magnitude >= mb -> String.format(Locale.ROOT, "%.0f MB", bytes / mb)
        else -> String.format(Locale.ROOT, "%.0f kB", bytes / kb)
    }
}

fun dwDeviceClassLabel(deviceClass: DwDeviceClass): String = when (deviceClass) {
    DwDeviceClass.UNMEASURED -> "Unmeasured"
    DwDeviceClass.LOW_RAM -> "Low-memory phone"
    DwDeviceClass.SMALL_4GB -> "4 GB class"
    DwDeviceClass.MID_6_TO_8GB -> "6–8 GB class"
    DwDeviceClass.LARGE_12GB_PLUS -> "12 GB and up"
}

fun dwDeviceReadoutSentence(measurement: DwDeviceMeasurement): String = buildString {
    append("Memory ")
    append(dwBytesLabel(measurement.totalRamBytes))
    append(", ")
    append(dwBytesLabel(measurement.availableRamBytes))
    append(" free now. Storage ")
    append(dwBytesLabel(measurement.freeStorageBytes))
    append(" free.")
    if (measurement.abis.isNotEmpty()) {
        append(" Processor ")
        append(measurement.abis.first())
        append(".")
    }
    when (measurement.lowRamDevice) {
        true -> append(" Android calls this a low-memory phone.")
        null -> append(" The low-memory flag could not be read.")
        false -> Unit
    }
}

fun dwModelSpeedClause(plan: DwModelPlan): String {
    val band = plan.realTimeFactor
        ?: return " Time depends on recording length."
    return " Slower than real time — about ${dwRoughMinutes(120_000L, band.slowest)} for two minutes of audio."
}

fun dwModelBackgroundingClause(plan: DwModelPlan): String = when (plan.survivesBackgrounding) {
    true -> ""
    false -> " It stops if you leave the app — taking a photograph would end the job."
    null -> " Leaving the app may stop it. Your work is saved."
}

fun dwModelAccuracyClause(plan: DwModelPlan, labels: Map<String, String>): String {
    if (plan.accuracy.isEmpty()) {
        return " It is not used for any dictation language."
    }
    fun name(tag: String): String =
        labels[tag] ?: labels.keys.firstOrNull { dwTagCovers(tag, it) }?.let { labels[it] } ?: tag

    val (offered, rejected) = plan.accuracy.partition { plan.servesLanguage(it.tag) }
    return buildString {
        if (offered.isNotEmpty()) {
            append(" Writes: ")
            append(offered.joinToString(", ") { name(it.tag) })
            append(".")
        }
        if (rejected.isNotEmpty()) {
            append(" Tried, not accurate enough: ")
            append(rejected.joinToString(", ") { name(it.tag) })
            append(".")
        }
        if (plan.unmeasuredLanguagesNote != null) append(" Not used for other languages.")
    }
}

internal fun dwRoughMinutes(audioMillis: Long, factor: Double): String {
    val millis = (audioMillis * factor).toLong().coerceAtLeast(0L)
    if (millis < 90_000L) {
        val seconds = ((millis + 999L) / 1000L).coerceAtLeast(1L)
        return "about $seconds seconds"
    }
    val minutes = (millis + 59_999L) / 60_000L
    return "about $minutes minutes"
}

fun dwModelWaitSentence(plan: DwModelPlan, audioMillis: Long): String? {
    val band = plan.realTimeFactor ?: return null
    if (audioMillis <= 0L) return null
    return "Stays on this phone. About ${dwRoughMinutes(audioMillis, band.fastest)}–${dwRoughMinutes(audioMillis, band.slowest)}."
}

fun dwTierOfferSentence(tier: DwAiTier, offer: DwTierOffer): String = when (offer) {
    is DwTierOffer.Available -> buildString {
        append(dwTierName(tier))
        append(": ")
        append(offer.plan.modelId)
        append(" would run ")
        append(tier.where)
        append(" — ")
        append(dwBytesLabel(offer.plan.onDiskBytes))
        append(" to download, and this phone would have ")
        append(dwBytesLabel(offer.headroomBytes))
        append(" memory free. Nothing is fetched unless you ask for it.")
        if (tier == DwAiTier.TIER_1) append(dwModelSpeedClause(offer.plan))
        append(dwModelBackgroundingClause(offer.plan))
    }

    is DwTierOffer.None -> dwTierRefusalSentence(tier, offer.refusal)
}

fun dwTierRefusalSentence(tier: DwAiTier, refusal: DwTierRefusal): String = when (refusal) {
    DwTierRefusal.NO_MEASURED_MODEL -> when (tier) {
        DwAiTier.TIER_1 ->
            "No speech model is offered for this phone, so there is nothing to install."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "No AI model is offered for this phone, so there is nothing to install."
    }

    DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD -> when (tier) {
        DwAiTier.TIER_1 ->
            "This app's own speech engine isn't installed — use the card above. Android's dictation still works."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "This work is done online, whenever there is signal."
    }

    DwTierRefusal.RUNTIME_NOT_INSTALLED -> when (tier) {
        DwAiTier.TIER_1 ->
            "This app's own speech engine is an optional download on the card above. Dictation works without it."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "Runs once a model below is downloaded."
    }

    DwTierRefusal.RUNTIME_UNMEASURED -> when (tier) {
        DwAiTier.TIER_1 ->
            "Couldn't check the speech engine. Tap “Check again”."

        DwAiTier.TIER_2, DwAiTier.TIER_3 ->
            "Couldn't finish checking. Tap “Check again”."
    }

    DwTierRefusal.DEVICE_TOO_SMALL ->
        "Not enough memory for the ${dwTierModelNoun(tier)}. Works online instead."

    DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW ->
        "Not enough free memory now. Close other apps and tap “Check again”."

    DwTierRefusal.NOT_ENOUGH_FREE_STORAGE ->
        "Not enough storage for the ${dwTierModelNoun(tier)}. Free some space and check again."

    DwTierRefusal.ABI_NOT_BUILT_FOR ->
        "The ${dwTierModelNoun(tier)} doesn't run on this processor. Works online instead."

    DwTierRefusal.FREE_RAM_UNMEASURED ->
        "Couldn't read free memory. Tap “Check again”."

    DwTierRefusal.FREE_STORAGE_UNMEASURED ->
        "Couldn't read free storage. Tap “Check again”."

    DwTierRefusal.ABI_UNMEASURED ->
        "Couldn't identify the processor. Tap “Check again”."

    DwTierRefusal.LOAD_FAILED_HERE_BEFORE ->
        "The ${dwTierModelNoun(tier)} failed to load here before. Works online instead."
}

private fun dwTierName(tier: DwAiTier): String = when (tier) {
    DwAiTier.TIER_1 -> "Offline speech"
    DwAiTier.TIER_2 -> "On-device AI"
    DwAiTier.TIER_3 -> "Online AI"
}

private fun dwTierModelNoun(tier: DwAiTier): String = when (tier) {
    DwAiTier.TIER_1 -> "speech model"
    DwAiTier.TIER_2, DwAiTier.TIER_3 -> "AI model"
}

fun dwTier3Sentence(connection: DwConnection): String = when (connection) {
    DwConnection.NONE ->
        "Offline — everything is saved and uploads later; nothing is lost."
    DwConnection.METERED ->
        "On mobile data. Recordings transcribe online with the craft word list (so “dabu” isn't written “double”)."
    DwConnection.UNMETERED ->
        "On Wi-Fi. Recordings transcribe online with the craft word list (so “dabu” isn't written “double”)."
}

const val DW_TIER_STALE_SENTENCE: String =
    "Reading is out of date. Tap “Check again”."
