package com.designprototype.workshop.data

import java.util.Locale

enum class DwAsrContainerFormat(
    val extension: String,
    val readableInThisBuild: Boolean,
) {
    ZIP(".zip", true),

    TAR_BZ2(".tar.bz2", false),
}

data class DwAsrModelArtifact(
    val modelId: String,
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val container: DwAsrContainerFormat,
    val upstreamVersion: String,
    val provenance: String,
) {
    init {
        require(modelId.isNotBlank() && '/' !in modelId && '\\' !in modelId && ".." !in modelId) {
            "A model artifact names the model it carries, and that name is also a directory inside " +
                "this app's internal storage. “$modelId” is not a bare name."
        }
        require(url.startsWith("https://")) {
            "A model artifact must be served over TLS. This is 292 MB fed to a native graph " +
                "executor inside the process holding Aadhaar numbers; the digest below is what makes " +
                "it safe, and plaintext would let somebody watch which model a district office is " +
                "fetching even where they could not change it. Do not relax this to make a test pass."
        }
        require(dwAsrIsSha256(sha256)) {
            "A model artifact needs the full 64-character hex SHA-256 of the exact file being " +
                "served. Take it with `sha256sum` on the published file. Nothing is unpacked without " +
                "it — dwAsrVerify answers NO_PINNED_DIGEST for a blank, and that refusal is the guard."
        }
        require(downloadBytes > 0L) {
            "A model artifact needs the real size of the file at its URL. This screen states the " +
                "cost before the tap, and a zero would print as “0 MB” beside a prepaid data bundle " +
                "for a fetch that is nearly three hundred megabytes."
        }
        require(upstreamVersion.isNotBlank()) {
            "A model artifact needs the upstream release it came out of, so a defect found later " +
                "can be traced to a version rather than to “the model”."
        }
        require(provenance.isNotBlank()) {
            "A model artifact needs a sentence saying how the release builder obtained it. The " +
                "digest proves only that the file is the one they pinned; this is the only record " +
                "of what they believed they were pinning."
        }
    }
}

val DW_ASR_MODEL_ARTIFACTS: List<DwAsrModelArtifact> = listOf(
    DwAsrModelArtifact(
        modelId = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        sha256 = "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed",
        downloadBytes = 292_571_207L,
        container = DwAsrContainerFormat.TAR_BZ2,
        upstreamVersion = "sherpa-onnx asr-models, asset " +
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        provenance = "Downloaded 2026-08-12 from the k2-fsa/sherpa-onnx GitHub release tag " +
            "`asr-models` — the same project and the same release index the engine AAR comes from — " +
            "and unpacked locally. 292,571,207 bytes, hashing to the digest above; the two files " +
            "pinned in DW_ASR_MODELS came out of this exact archive and have both since answered " +
            "VERIFIED off the fleet handset's own storage, twice. Nothing upstream publishes a " +
            "signature for it, so this chain establishes what was pinned and not who made it.",
    ),
)

fun dwAsrModelArtifactFor(
    modelId: String,
    catalogue: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
): DwAsrModelArtifact? = catalogue.firstOrNull { it.modelId == modelId }

enum class DwAsrModelState {
    UNKNOWN,

    NOT_INSTALLED,

    INSTALLING,

    INSTALLED,

    PAUSED,

    FAILED,
}

data class DwAsrModelStatus(
    val state: DwAsrModelState = DwAsrModelState.UNKNOWN,
    val model: DwAsrModel? = null,
    val verifiedSha256: List<String> = emptyList(),
    val percent: Int? = null,
    val failure: String? = null,
) {
    init {
        require(state != DwAsrModelState.INSTALLED || model != null) {
            "An installed model has to say WHICH model is installed. A status claiming a model is " +
                "present without naming the artifact it was verified against cannot be checked by " +
                "anything, and this app does not decode with a graph it cannot check."
        }
        require(state != DwAsrModelState.INSTALLED || dwAsrModelAllVerified(model, verifiedSha256)) {
            "A model may only be called installed when EVERY file on disk has been hashed IN THIS " +
                "RUN and matched the digest pinned in this APK. If you are here having found the " +
                "files present and wanting to skip the hashing: a graph with a substituted vocabulary " +
                "decodes to confident nonsense rather than to an error, and nobody would ever check " +
                "the output against the audio. Hash them. One of two matching is not most of a yes."
        }
        require(percent == null || percent in 0..100) {
            "Install progress is a percentage or nothing at all. A figure outside 0–100 came from " +
                "arithmetic on a length nobody supplied — pass null instead, and the surfaces say " +
                "“installing” without a bar."
        }
    }
}

fun dwAsrModelAllVerified(model: DwAsrModel?, verified: List<String>): Boolean {
    if (model == null) return false
    val want = model.files.map { it.sha256.trim().lowercase(Locale.ROOT) }.sorted()
    val got = verified.map { it.trim().lowercase(Locale.ROOT) }.sorted()
    if (want.size != got.size) return false
    return want.zip(got).all { (expected, actual) ->
        dwAsrVerify(expected, actual) == DwAsrVerification.VERIFIED
    }
}

fun dwAsrModelMayLoad(status: DwAsrModelStatus): Boolean =
    status.state == DwAsrModelState.INSTALLED &&
        dwAsrModelAllVerified(status.model, status.verifiedSha256)

fun dwAsrInstalledModelIds(status: DwAsrModelStatus): Set<String> =
    if (dwAsrModelMayLoad(status)) setOfNotNull(status.model?.modelId) else emptySet()

fun dwAsrModelStorageNeededBytes(
    model: DwAsrModel,
    artifact: DwAsrModelArtifact? = null,
): Long = model.onDiskBytes + (artifact?.downloadBytes ?: 0L) + DW_MODEL_FREE_STORAGE_MARGIN_BYTES

enum class DwAsrModelOffer {
    INSTALL_FROM_STAGED_FILES,

    DOWNLOAD,

    RESUME,

    RETRY,

    NO_CONNECTION,

    ALREADY_INSTALLED,

    IN_PROGRESS,

    NOTHING_PINNED,

    CONTAINER_NOT_READABLE_IN_THIS_BUILD,

    NOT_ENTITLED,

    SESSION_LAPSED,

    DEPLOYMENT_DOES_NOT_KNOW_THIS_MODEL,

    STORAGE_UNMEASURED,

    NOT_ENOUGH_STORAGE,

    UNKNOWN,
}

fun dwAsrModelOffer(
    status: DwAsrModelStatus,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    stagedFilesPresent: Boolean,
    catalogue: List<DwAsrModel> = DW_ASR_MODELS,
    artifacts: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
    endpoint: DwAsrEndpointState = DwAsrEndpointState.UNKNOWN,
): DwAsrModelOffer {
    val model = catalogue.firstOrNull() ?: return DwAsrModelOffer.NOTHING_PINNED

    when (status.state) {
        DwAsrModelState.INSTALLED -> return DwAsrModelOffer.ALREADY_INSTALLED
        DwAsrModelState.INSTALLING -> return DwAsrModelOffer.IN_PROGRESS
        DwAsrModelState.PAUSED -> return DwAsrModelOffer.RESUME
        DwAsrModelState.UNKNOWN -> return DwAsrModelOffer.UNKNOWN
        DwAsrModelState.NOT_INSTALLED, DwAsrModelState.FAILED -> Unit
    }

    val free = measurement.freeStorageBytes ?: return DwAsrModelOffer.STORAGE_UNMEASURED

    if (stagedFilesPresent) {
        return if (free < dwAsrModelStorageNeededBytes(model)) {
            DwAsrModelOffer.NOT_ENOUGH_STORAGE
        } else {
            DwAsrModelOffer.INSTALL_FROM_STAGED_FILES
        }
    }

    when (endpoint) {
        DwAsrEndpointState.PUBLISHES -> {
            if (free < dwAsrModelStorageNeededBytes(model)) return DwAsrModelOffer.NOT_ENOUGH_STORAGE
            if (connection == DwConnection.NONE) return DwAsrModelOffer.NO_CONNECTION
            return if (status.state == DwAsrModelState.FAILED) {
                DwAsrModelOffer.RETRY
            } else {
                DwAsrModelOffer.DOWNLOAD
            }
        }
        DwAsrEndpointState.UNKNOWN,
        DwAsrEndpointState.UNREACHABLE,
        DwAsrEndpointState.SESSION_LAPSED,
        DwAsrEndpointState.FORBIDDEN,
        DwAsrEndpointState.VERSION_SKEW,
        DwAsrEndpointState.NOT_PUBLISHED -> Unit
    }

    val artifact = artifacts.firstOrNull { it.modelId == model.modelId }
        ?: return dwAsrModelEndpointRefusal(endpoint) ?: DwAsrModelOffer.NOTHING_PINNED
    if (!artifact.container.readableInThisBuild) {
        return dwAsrModelEndpointRefusal(endpoint)
            ?: DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD
    }

    if (free < dwAsrModelStorageNeededBytes(model, artifact)) return DwAsrModelOffer.NOT_ENOUGH_STORAGE

    if (connection == DwConnection.NONE) return DwAsrModelOffer.NO_CONNECTION

    return if (status.state == DwAsrModelState.FAILED) DwAsrModelOffer.RETRY else DwAsrModelOffer.DOWNLOAD
}

private fun dwAsrModelEndpointRefusal(endpoint: DwAsrEndpointState): DwAsrModelOffer? =
    when (endpoint) {
        DwAsrEndpointState.SESSION_LAPSED -> DwAsrModelOffer.SESSION_LAPSED
        DwAsrEndpointState.FORBIDDEN -> DwAsrModelOffer.NOT_ENTITLED
        DwAsrEndpointState.VERSION_SKEW -> DwAsrModelOffer.DEPLOYMENT_DOES_NOT_KNOW_THIS_MODEL
        DwAsrEndpointState.UNKNOWN,
        DwAsrEndpointState.UNREACHABLE,
        DwAsrEndpointState.NOT_PUBLISHED,
        DwAsrEndpointState.PUBLISHES -> null
    }

enum class DwAsrModelSource {
    STAGED_FILES,

    DEPLOYMENT_ENDPOINT,

    PINNED_CONTAINER,
}

fun dwAsrModelSourceFor(
    offer: DwAsrModelOffer,
    endpoint: DwAsrEndpointState,
    stagedFilesPresent: Boolean = false,
    pausedSource: DwAsrModelSource? = null,
): DwAsrModelSource? {
    val fresh = if (endpoint == DwAsrEndpointState.PUBLISHES) {
        DwAsrModelSource.DEPLOYMENT_ENDPOINT
    } else {
        DwAsrModelSource.PINNED_CONTAINER
    }
    return when (offer) {
        DwAsrModelOffer.INSTALL_FROM_STAGED_FILES -> DwAsrModelSource.STAGED_FILES
        DwAsrModelOffer.RESUME -> pausedSource ?: fresh
        DwAsrModelOffer.DOWNLOAD, DwAsrModelOffer.RETRY -> fresh
        DwAsrModelOffer.NOT_ENOUGH_STORAGE ->
            if (stagedFilesPresent) DwAsrModelSource.STAGED_FILES else fresh
        DwAsrModelOffer.NO_CONNECTION -> fresh
        else -> null
    }
}

fun dwAsrModelMayInstall(offer: DwAsrModelOffer): Boolean =
    offer == DwAsrModelOffer.DOWNLOAD ||
        offer == DwAsrModelOffer.RESUME ||
        offer == DwAsrModelOffer.RETRY ||
        offer == DwAsrModelOffer.INSTALL_FROM_STAGED_FILES

fun dwAsrModelOfferSpendsData(offer: DwAsrModelOffer): Boolean =
    offer == DwAsrModelOffer.DOWNLOAD ||
        offer == DwAsrModelOffer.RESUME ||
        offer == DwAsrModelOffer.RETRY

fun dwAsrModelStateLabel(state: DwAsrModelState): String = when (state) {
    DwAsrModelState.UNKNOWN -> "Not checked"
    DwAsrModelState.NOT_INSTALLED -> "Not installed"
    DwAsrModelState.INSTALLING -> "Installing"
    DwAsrModelState.INSTALLED -> "Installed"
    DwAsrModelState.PAUSED -> "Paused"
    DwAsrModelState.FAILED -> "Last attempt failed"
}

fun dwAsrModelActionLabel(offer: DwAsrModelOffer): String? = when (offer) {
    DwAsrModelOffer.INSTALL_FROM_STAGED_FILES -> "Install from this phone"
    DwAsrModelOffer.DOWNLOAD -> "Download"
    DwAsrModelOffer.RESUME -> "Resume"
    DwAsrModelOffer.RETRY -> "Try again"
    else -> null
}

fun dwAsrModelOfferSentence(
    offer: DwAsrModelOffer,
    measurement: DwDeviceMeasurement,
    catalogue: List<DwAsrModel> = DW_ASR_MODELS,
    artifacts: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
    source: DwAsrModelSource? = null,
): String {
    val model = catalogue.firstOrNull()
    val artifact = model?.let { m -> artifacts.firstOrNull { it.modelId == m.modelId } }
    val fromEndpoint = source == DwAsrModelSource.DEPLOYMENT_ENDPOINT
    return when (offer) {
        DwAsrModelOffer.INSTALL_FROM_STAGED_FILES ->
            "Already on this phone — installing uses no data."

        DwAsrModelOffer.DOWNLOAD ->
            if (fromEndpoint) {
                "${dwBytesLabel(model?.onDiskBytes)} download, same on disk."
            } else {
                "${dwBytesLabel(artifact?.downloadBytes)} download, ${dwBytesLabel(model?.onDiskBytes)} on disk."
            }

        DwAsrModelOffer.RESUME ->
            "Paused. Resume to finish."

        DwAsrModelOffer.RETRY ->
            "Last attempt didn't finish."

        DwAsrModelOffer.NO_CONNECTION ->
            "No connection, so it cannot be fetched now."

        DwAsrModelOffer.ALREADY_INSTALLED ->
            "Installed and verified."

        DwAsrModelOffer.IN_PROGRESS ->
            "Installing now."

        DwAsrModelOffer.NOTHING_PINNED ->
            "There is no speech model to install."

        DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD ->
            "Can't download the speech model right now."

        DwAsrModelOffer.NOT_ENTITLED ->
            "For designers and admins. Ask an admin if you need it."

        DwAsrModelOffer.SESSION_LAPSED ->
            "Signed out. Sign in and tap “Check again”."

        DwAsrModelOffer.DEPLOYMENT_DOES_NOT_KNOW_THIS_MODEL ->
            "Update the app to download the speech model."

        DwAsrModelOffer.STORAGE_UNMEASURED ->
            "Couldn't read free storage. Tap “Check again”."

        DwAsrModelOffer.NOT_ENOUGH_STORAGE -> {
            val staging = if (source == DwAsrModelSource.PINNED_CONTAINER) {
                artifact?.downloadBytes ?: 0L
            } else {
                0L
            }
            "Not enough room: needs ${dwBytesLabel((model?.onDiskBytes ?: 0L) + staging)} plus " +
                "${dwBytesLabel(DW_MODEL_FREE_STORAGE_MARGIN_BYTES)} kept back, and this phone has " +
                "${dwBytesLabel(measurement.freeStorageBytes)} free."
        }

        DwAsrModelOffer.UNKNOWN ->
            "Not checked yet. Tap “Check again”."
    }
}

const val DW_ASR_MODEL_MISMATCH_SENTENCE: String =
    "A model file didn't match, so nothing was installed. Try again; if it happens twice, tell your admin."

fun dwAsrModelWhatItBuysSentence(
    labels: Map<String, String>,
    support: DwRecognitionSupport?,
    plans: List<DwModelPlan> = DW_TIER1_CATALOGUE,
): String {
    val plan = plans.firstOrNull() ?: return "There is no speech model to install."
    val served = labels.keys.filter { plan.servesLanguage(it) }
    if (served.isEmpty()) {
        return "Adds no languages this app uses."
    }
    val servedLabels = served.map { labels[it] ?: it }
    if (support == null) {
        return "Serves ${servedLabels.joinToString(", ")}. This Android version cannot be asked what it already has."
    }
    val adds = served.filter { dwPackState(it, support) != DwPackState.INSTALLED }
    if (adds.isEmpty()) {
        return "Adds nothing: ${servedLabels.joinToString(", ")} already ${dwPluralVerb(servedLabels.size, "works", "work")} offline."
    }
    return "Adds offline dictation in ${adds.map { labels[it] ?: it }.joinToString(", ")}."
}

internal fun dwPluralVerb(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural

internal fun dwPluralNoun(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural
