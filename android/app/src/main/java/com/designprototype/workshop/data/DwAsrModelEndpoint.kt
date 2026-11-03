package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

private const val DW_ASR_ENDPOINT_PREFIX = "asr-models"

private fun dwAsrIsBarePathSegment(value: String): Boolean =
    value.isNotBlank() &&
        '/' !in value &&
        '\\' !in value &&
        ".." !in value &&
        value != "."

private fun requireBare(value: String, what: String): String {
    require(dwAsrIsBarePathSegment(value)) {
        "“$value” is not a bare $what. These strings are joined onto a URL this app fetches from " +
            "and onto a file inside this app's own internal storage, so a separator, a `..` or a " +
            "bare `.` here would address something other than the one directory this feature owns."
    }
    return value
}

fun dwAsrModelManifestPath(modelId: String): String =
    "$DW_ASR_ENDPOINT_PREFIX/${requireBare(modelId, "model id")}"

fun dwAsrModelFilePath(modelId: String, fileName: String): String =
    "${dwAsrModelManifestPath(modelId)}/files/${requireBare(fileName, "file name")}"

fun dwAsrModelEndpointUrl(apiBaseUrl: String, path: String): String {
    val base = apiBaseUrl.trimEnd('/')
    val tail = path.trimStart('/')
    return "$base/$tail"
}

fun dwAsrModelFileUrl(apiBaseUrl: String, modelId: String, fileName: String): String =
    dwAsrModelEndpointUrl(apiBaseUrl, dwAsrModelFilePath(modelId, fileName))

@Serializable
data class DwAsrManifestFile(
    val fileName: String = "",
    val url: String = "",
    val mediaType: String = "",
    val bytes: Long? = null,
    val sha256: String? = null,
    val available: Boolean = false,
    val unavailableReason: String? = null,
    val detail: String? = null,
)

@Serializable
data class DwAsrManifestArtifact(
    val artifactId: String = "",
    val version: String = "",
    val quantisation: String = "",
    val available: Boolean = false,
    val unavailableReason: String? = null,
    val detail: String? = null,
    val totalBytes: Long? = null,
    val files: List<DwAsrManifestFile> = emptyList(),
)

enum class DwAsrEndpointState {
    UNKNOWN,

    UNREACHABLE,

    SESSION_LAPSED,

    FORBIDDEN,

    NOT_PUBLISHED,

    VERSION_SKEW,

    PUBLISHES,
}

enum class DwAsrManifestVerdict {
    AGREES,

    ARTIFACT_ABSENT,

    NOT_PUBLISHED,

    FILE_MISSING,

    DISAGREES_ON_SIZE,

    DISAGREES_ON_DIGEST,
}

fun dwAsrManifestVerdict(
    manifest: DwAsrManifestArtifact?,
    pinned: DwAsrModel,
): DwAsrManifestVerdict {
    if (manifest == null) return DwAsrManifestVerdict.ARTIFACT_ABSENT
    if (manifest.artifactId != pinned.modelId) return DwAsrManifestVerdict.ARTIFACT_ABSENT
    if (!manifest.available) return DwAsrManifestVerdict.NOT_PUBLISHED

    val published = manifest.files.associateBy { it.fileName }
    pinned.files.forEach { want ->
        val got = published[want.fileName] ?: return DwAsrManifestVerdict.FILE_MISSING
        if (!got.available) return DwAsrManifestVerdict.NOT_PUBLISHED
        val bytes = got.bytes ?: return DwAsrManifestVerdict.NOT_PUBLISHED
        val digest = got.sha256?.trim().orEmpty()
        if (digest.isEmpty()) return DwAsrManifestVerdict.NOT_PUBLISHED
        if (bytes != want.bytes) return DwAsrManifestVerdict.DISAGREES_ON_SIZE
        if (dwAsrVerify(want.sha256, digest) != DwAsrVerification.VERIFIED) {
            return DwAsrManifestVerdict.DISAGREES_ON_DIGEST
        }
    }
    return DwAsrManifestVerdict.AGREES
}

fun dwAsrEndpointStateOf(verdict: DwAsrManifestVerdict): DwAsrEndpointState =
    if (verdict == DwAsrManifestVerdict.AGREES) {
        DwAsrEndpointState.PUBLISHES
    } else {
        DwAsrEndpointState.NOT_PUBLISHED
    }
