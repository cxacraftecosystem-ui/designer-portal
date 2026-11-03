package com.designprototype.workshop.data

import java.util.Locale

enum class DwAsrRuntimeState {
    UNKNOWN,

    NOT_INSTALLED,

    DOWNLOADING,

    INSTALLED,

    FAILED,
}

data class DwAsrLibrary(
    val fileName: String,
    val sha256: String,
    val bytes: Long,
) {
    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && ".." !in fileName) {
            "A library name must be a bare file name: “$fileName” is not. These names address files " +
                "written into this app's own internal storage, and an entry like " +
                "../databases/workshop.db would put fetched bytes over this app's own database."
        }
        require(fileName.startsWith("lib") && fileName.endsWith(".so")) {
            "A native library is called lib….so on Android; “$fileName” is not, and `System.load` " +
                "will not open it. If the deployment is serving something else, this catalogue is " +
                "describing the wrong artifact."
        }
        require(dwAsrIsSha256(sha256)) {
            "Every library needs the full 64-character hex SHA-256 of the exact file that will sit " +
                "on the phone — not of the archive it arrives in. The archive is deleted after " +
                "unpacking, and what gets loaded afterwards is this file, so this is the digest that " +
                "has to be checkable on every launch."
        }
        require(bytes > 0L) {
            "Every library needs its real size on disk. The figures are added up to decide whether " +
                "the engine fits with a workshop day's photographs left over, and a zero would let " +
                "an install be offered on a phone with no room for it."
        }
    }
}

data class DwAsrArtifact(
    val abi: String,

    val url: String,

    val sha256: String,

    val downloadBytes: Long,

    val libraries: List<DwAsrLibrary>,

    val upstreamVersion: String,

    val provenance: String,
) {
    val installedBytes: Long get() = libraries.sumOf { it.bytes }

    init {
        require(abi.isNotBlank() && '/' !in abi && '\\' !in abi && ".." !in abi) {
            "An engine artifact needs the exact ABI it was built for as a bare name, e.g. arm64-v8a — " +
                "“$abi” is not one. Read it off the directory the libraries came out of; one artifact " +
                "per ABI, never a list. It also names a directory inside this app's internal storage " +
                "and the file the container is downloaded as, so a separator or “..” in it would write " +
                "fetched bytes outside the one directory this feature owns."
        }
        require(url.startsWith("https://")) {
            "An engine artifact must be served over TLS from this deployment's own storage. This is " +
                "executable code that will run inside the process holding Aadhaar numbers and two " +
                "weeks of unsynced fieldwork — publish the file to the deployment's storage and use " +
                "its https URL. Do not relax this check to make a test pass."
        }
        require(dwAsrIsSha256(sha256)) {
            "An engine artifact needs the full 64-character lower-case hex SHA-256 of the exact file " +
                "being served, taken by the release builder. Compute it with `sha256sum` on the file " +
                "you uploaded, not on the one you built from. Nothing is loaded without it."
        }
        require(downloadBytes > 0L) {
            "An engine artifact needs the real size of the file at its URL. This screen states the " +
                "cost before the tap — unlike the language packs, where Android reports no size — " +
                "and a zero would print as “0 MB” beside somebody's prepaid data bundle."
        }
        require(libraries.isNotEmpty()) {
            "An engine artifact needs the .so files it carries, in the order they must be loaded — " +
                "libonnxruntime.so before libsherpa-onnx-jni.so with the default AAR, or the second " +
                "cannot resolve against the first. Each one carries its own digest, because the " +
                "archive is deleted after unpacking and these are the files that get loaded."
        }
        require(libraries.map { it.fileName }.distinct().size == libraries.size) {
            "Two libraries in one artifact cannot share a file name: they would be written to the " +
                "same path, one would silently replace the other, and the digest check would then " +
                "fail on whichever lost — reporting a substituted file where the real fault is a " +
                "duplicated row in this catalogue."
        }
        require(upstreamVersion.isNotBlank()) {
            "An engine artifact needs the upstream release it came out of, so a defect found later " +
                "can be traced to a version rather than to “the engine”."
        }
        require(provenance.isNotBlank()) {
            "An engine artifact needs a sentence saying how the release builder obtained it. The " +
                "digest proves only that the file is the one they pinned; this is the only record " +
                "of what they believed they were pinning."
        }
    }
}

val DW_ASR_ARTIFACTS: List<DwAsrArtifact> = emptyList()

fun dwAsrArtifactFor(
    abis: List<String>,
    catalogue: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
): DwAsrArtifact? {
    abis.forEach { reported ->
        val match = catalogue.firstOrNull { it.abi.equals(reported.trim(), ignoreCase = true) }
        if (match != null) return match
    }
    return null
}

fun dwAsrIsSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

enum class DwAsrVerification {
    VERIFIED,

    MISMATCH,

    NO_PINNED_DIGEST,

    MALFORMED,
}

fun dwAsrVerify(expected: String, actual: String): DwAsrVerification {
    val want = expected.trim()
    val got = actual.trim()
    if (want.isEmpty()) return DwAsrVerification.NO_PINNED_DIGEST
    if (!dwAsrIsSha256(want) || !dwAsrIsSha256(got)) return DwAsrVerification.MALFORMED
    return if (want.equals(got, ignoreCase = true)) {
        DwAsrVerification.VERIFIED
    } else {
        DwAsrVerification.MISMATCH
    }
}

data class DwAsrRuntimeStatus(
    val state: DwAsrRuntimeState = DwAsrRuntimeState.UNKNOWN,

    val artifact: DwAsrArtifact? = null,

    val verifiedSha256: List<String> = emptyList(),

    val percent: Int? = null,

    val failure: String? = null,
) {
    init {
        require(state != DwAsrRuntimeState.INSTALLED || artifact != null) {
            "An installed engine has to say WHICH artifact is installed. A status that claims an " +
                "engine is present without naming the artifact it was verified against cannot be " +
                "checked by anything, and this app does not load code it cannot check."
        }
        require(
            state != DwAsrRuntimeState.INSTALLED || dwAsrAllVerified(artifact, verifiedSha256)
        ) {
            "An engine may only be called installed when EVERY library on disk has been hashed IN " +
                "THIS RUN and matched the digest pinned in this APK. If you are here having found " +
                "the files present and wanting to skip the hashing: that check is the only thing " +
                "standing between this app's process and somebody else's code. Hash them. And if one " +
                "of several matched, that is not most of a yes — the one that did not match is the " +
                "one that would be loaded."
        }
        require(percent == null || percent in 0..100) {
            "Download progress is a percentage or nothing at all. A figure outside 0–100 came from " +
                "arithmetic on a content length the server did not give — pass null instead, and " +
                "the surfaces will say “downloading” without a bar."
        }
    }
}

fun dwAsrAllVerified(artifact: DwAsrArtifact?, verified: List<String>): Boolean {
    if (artifact == null) return false
    val want = artifact.libraries.map { it.sha256.trim().lowercase(Locale.ROOT) }.sorted()
    val got = verified.map { it.trim().lowercase(Locale.ROOT) }.sorted()
    if (want.size != got.size) return false
    return want.zip(got).all { (expected, actual) ->
        dwAsrVerify(expected, actual) == DwAsrVerification.VERIFIED
    }
}

fun dwAsrMayLoad(status: DwAsrRuntimeStatus): Boolean =
    status.state == DwAsrRuntimeState.INSTALLED &&
        dwAsrAllVerified(status.artifact, status.verifiedSha256)

const val DW_ASR_ENGINE_BYTES_ARM64: Long = 24_169_352L

const val DW_ASR_ENGINE_BYTES_ARM32: Long = 16_437_284L

fun dwAsrMeasuredAbi(abis: List<String>): String? {
    abis.forEach { reported ->
        val normalised = reported.trim().lowercase(Locale.ROOT)
        if (normalised == "arm64-v8a" || normalised == "armeabi-v7a") return normalised
    }
    return null
}

fun dwAsrMeasuredEngineBytes(abis: List<String>): Long? = when (dwAsrMeasuredAbi(abis)) {
    "arm64-v8a" -> DW_ASR_ENGINE_BYTES_ARM64
    "armeabi-v7a" -> DW_ASR_ENGINE_BYTES_ARM32
    else -> null
}

const val DW_ASR_FREE_STORAGE_MARGIN_BYTES: Long = 256L * 1024L * 1024L

fun dwAsrStorageNeededBytes(artifact: DwAsrArtifact): Long =
    artifact.downloadBytes + artifact.installedBytes + DW_ASR_FREE_STORAGE_MARGIN_BYTES

enum class DwAsrOffer {
    BUNDLED_IN_THIS_BUILD,

    INSTALL,

    RETRY,

    NO_CONNECTION,

    ALREADY_INSTALLED,

    IN_PROGRESS,

    NO_MODEL_TO_FEED_IT,

    NOTHING_PUBLISHED_TO_INSTALL,

    NO_BUILD_FOR_THIS_PROCESSOR,

    PROCESSOR_UNMEASURED,

    STORAGE_UNMEASURED,

    NOT_ENOUGH_STORAGE,

    UNKNOWN,
}

fun dwAsrOffer(
    status: DwAsrRuntimeStatus,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    catalogue: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
    modelCatalogue: List<DwModelPlan> = DW_TIER1_CATALOGUE,
    runtimeInApk: Boolean = DW_TIER1_RUNTIME_PRESENT,
): DwAsrOffer {
    if (runtimeInApk) return DwAsrOffer.BUNDLED_IN_THIS_BUILD

    if (catalogue.isEmpty()) return DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL

    when (status.state) {
        DwAsrRuntimeState.INSTALLED -> return DwAsrOffer.ALREADY_INSTALLED
        DwAsrRuntimeState.DOWNLOADING -> return DwAsrOffer.IN_PROGRESS
        DwAsrRuntimeState.UNKNOWN -> return DwAsrOffer.UNKNOWN
        DwAsrRuntimeState.NOT_INSTALLED, DwAsrRuntimeState.FAILED -> Unit
    }

    if (measurement.abis.isEmpty()) return DwAsrOffer.PROCESSOR_UNMEASURED
    val artifact = dwAsrArtifactFor(measurement.abis, catalogue)
        ?: return DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR

    if (modelCatalogue.isEmpty()) return DwAsrOffer.NO_MODEL_TO_FEED_IT

    val free = measurement.freeStorageBytes ?: return DwAsrOffer.STORAGE_UNMEASURED
    if (free < dwAsrStorageNeededBytes(artifact)) return DwAsrOffer.NOT_ENOUGH_STORAGE

    if (connection == DwConnection.NONE) return DwAsrOffer.NO_CONNECTION

    return if (status.state == DwAsrRuntimeState.FAILED) DwAsrOffer.RETRY else DwAsrOffer.INSTALL
}

fun dwAsrMayInstall(offer: DwAsrOffer): Boolean =
    offer == DwAsrOffer.INSTALL || offer == DwAsrOffer.RETRY

fun dwAsrStateLabel(state: DwAsrRuntimeState): String = when (state) {
    DwAsrRuntimeState.UNKNOWN -> "Not checked"
    DwAsrRuntimeState.NOT_INSTALLED -> "Not installed"
    DwAsrRuntimeState.DOWNLOADING -> "Downloading"
    DwAsrRuntimeState.INSTALLED -> "Installed"
    DwAsrRuntimeState.FAILED -> "Last attempt failed"
}

const val DW_ASR_OFFER_BLURB: String =
    "A download you choose, for dictation without signal — including Odia. It needs a speech model too; see below whether it can be installed on this phone today."

const val DW_ASR_ENGINE_WITHOUT_A_VOICE: String =
    "The engine arrives without a voice, so nothing can be installed yet: it needs a speech model, and none has been measured."

const val DW_ASR_WHAT_IT_BUYS: String =
    "With the engine and a language model, you can dictate with no signal."

fun dwAsrCostSentence(
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    artifact: DwAsrArtifact? = null,
): String {
    val bytes = artifact?.downloadBytes ?: dwAsrMeasuredEngineBytes(measurement.abis)
    val processor = artifact?.abi
        ?: dwAsrMeasuredAbi(measurement.abis)
        ?: measurement.abis.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    val size = when {
        bytes == null && processor == null ->
            "Size unmeasured on this phone."
        bytes == null ->
            "Engine size for this processor ($processor) is unmeasured."
        artifact != null && artifact.installedBytes != bytes ->
            "${dwBytesLabel(bytes)} download, ${dwBytesLabel(artifact.installedBytes)} installed ($processor)."
        else ->
            "${dwBytesLabel(bytes)} download and on disk ($processor)."
    }
    val meter = when (connection) {
        DwConnection.NONE ->
            "Offline."
        DwConnection.METERED ->
            "On mobile data — consider waiting for Wi-Fi."
        DwConnection.UNMETERED ->
            "On Wi-Fi."
    }
    return "$size $meter"
}

fun dwAsrOfferSentence(
    offer: DwAsrOffer,
    measurement: DwDeviceMeasurement,
    catalogue: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
): String = when (offer) {
    DwAsrOffer.BUNDLED_IN_THIS_BUILD ->
        "Built in — part of the app itself, nothing to install. Add the speech model on the card below to dictate offline."

    DwAsrOffer.INSTALL ->
        "Ready to install. Removable any time."

    DwAsrOffer.RETRY ->
        "Last attempt didn't finish. Try again."

    DwAsrOffer.NO_CONNECTION ->
        "Offline. Dictation still works as usual."

    DwAsrOffer.ALREADY_INSTALLED ->
        "Installed and verified on this phone."

    DwAsrOffer.IN_PROGRESS ->
        "Downloading — stay on this screen."

    DwAsrOffer.NO_MODEL_TO_FEED_IT -> DW_ASR_ENGINE_WITHOUT_A_VOICE

    DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL ->
        "Nothing has been published to install yet."

    DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR -> buildString {
        append("Not available for this phone's processor")
        measurement.abis.firstOrNull()?.let { append(" ($it)") }
        append(
            "."
        )
        val built = catalogue.map { it.abi }.distinct()
        if (built.isNotEmpty()) append(" The engine is built for ${built.joinToString(", ")}.")
    }

    DwAsrOffer.PROCESSOR_UNMEASURED ->
        "Couldn't identify the processor. Tap “Check again”."

    DwAsrOffer.STORAGE_UNMEASURED ->
        "Couldn't read free storage. Tap “Check again”."

    DwAsrOffer.NOT_ENOUGH_STORAGE -> buildString {
        append("Not enough storage. This phone has ")
        append(dwBytesLabel(measurement.freeStorageBytes))
        append(" free. Free some space and check again.")
    }

    DwAsrOffer.UNKNOWN ->
        "Not checked yet. Tap “Check again”."
}

const val DW_ASR_VERIFY_SENTENCE: String =
    "Downloads are checked against a fingerprint built into this app and thrown away if it does not match."

const val DW_ASR_MISMATCH_SENTENCE: String =
    "Download didn't verify, so it was deleted. Try again; if it happens twice, tell your admin."

const val DW_ASR_CARD_BLURB: String =
    "Dictate without signal. It is optional, and set per phone, not per account."
