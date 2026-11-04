package com.designprototype.workshop.data

data class DwTier2Artifact(
    val modelId: String,
    val repo: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val digestProvenance: String,
    val needsUpstreamApproval: Boolean,
) {
    init {
        require(modelId.isNotBlank()) { "A Tier 2 artifact names the model row it carries." }
        require(repo.isNotBlank()) {
            "A Tier 2 artifact names the repository it came out of. A file with no upstream behind " +
                "it cannot be re-fetched, re-checked or reported as wrong."
        }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && ".." !in fileName) {
            "A Tier 2 artifact needs a bare file name: “$fileName” is not one. These names address " +
                "files inside this app's own storage, and a separator would put multi-gigabyte bytes " +
                "somewhere this feature does not own."
        }
        require(url.startsWith("https://") && "/resolve/" in url && url.endsWith("/$fileName")) {
            "A Tier 2 artifact is fetched over TLS from a URL pinned to a revision and ending in its " +
                "own file name: “$url” is not one."
        }
        require(bytes > 0L) {
            "A Tier 2 artifact needs its real size. This is the figure a designer reads before " +
                "spending a data bundle on it, and a zero would print as “0 MB” for 2.6 GB."
        }
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" }) {
            "A Tier 2 artifact needs the full 64-character lower-case hex SHA-256 of the exact file. " +
                "Nothing this size is opened without one — a substituted language model produces " +
                "confident prose rather than an error, and nobody would check it against the source."
        }
        require(digestProvenance.isNotBlank()) {
            "A digest has to say who took it. “Measured here” and “published by the host” are " +
                "different degrees of evidence and this app may not print them in one voice."
        }
    }
}

val DW_TIER2_ARTIFACTS: List<DwTier2Artifact> = listOf(
    DwTier2Artifact(
        modelId = "gemma-4-E2B-it.litertlm",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        digestProvenance = "MEASURED — the file was downloaded on 2026-08-13 and hashed on the " +
            "release machine. Google's own “Model size (MB)” column reads 2583 for the same " +
            "artifact; the byte count above is what `ls` and `sha256sum` agree on, and it is the " +
            "figure this app states.",
        needsUpstreamApproval = false,
    ),
    DwTier2Artifact(
        modelId = "gemma-4-E4B-it.litertlm",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
        bytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        digestProvenance = "MEASURED — downloaded 2026-08-13 and hashed on the release machine. " +
            "Google's “Model size (MB)” column reads 3654 for it.",
        needsUpstreamApproval = false,
    ),

)

fun dwTier2ArtifactFor(
    modelId: String,
    catalogue: List<DwTier2Artifact> = DW_TIER2_ARTIFACTS,
): DwTier2Artifact? = catalogue.firstOrNull { it.modelId == modelId }

const val DW_TIER2_MEASURED_CONTEXT_TOKENS: Int = 2048

private const val DW_TIER2_MIB: Long = 1024L * 1024L

val DW_TIER2_PLANS: List<DwModelPlan> = listOf(
    DwModelPlan(
        modelId = "gemma-4-E2B-it.litertlm",
        quantisation = "mixed 2/4/8-bit QAT (Gemma 4 mobile scheme)",
        abi = "arm64-v8a",
        languages = null,
        contextCapTokens = DW_TIER2_MEASURED_CONTEXT_TOKENS,
        runBound = "one answer at a 2,048-token context cap (1,024 tokens in, 256 out), which is the " +
            "envelope Google's memory figure was taken at; the artifact supports up to 32k and " +
            "nobody has measured what that costs.",
        onDiskBytes = 2_588_147_712L,
        peakRssBytes = 1733L * DW_TIER2_MIB,
        measuredOn = "Galaxy S26 Ultra — Google's published figure, not a reading from this fleet",
        survivesBackgrounding = null,
        unmeasuredLanguagesNote = "Google CLAIM 35+ languages out of the box and pre-training in " +
            "140+. None of that has been checked against this app's dictation languages, so what " +
            "this artifact can actually write is unmeasured.",
    ),
    DwModelPlan(
        modelId = "gemma-4-E4B-it.litertlm",
        quantisation = "mixed 2/4/8-bit QAT (Gemma 4 mobile scheme)",
        abi = "arm64-v8a",
        languages = null,
        contextCapTokens = DW_TIER2_MEASURED_CONTEXT_TOKENS,
        runBound = "one answer at a 2,048-token context cap (1,024 tokens in, 256 out), the envelope " +
            "Google's memory figure was taken at; up to 32k is supported and unmeasured.",
        onDiskBytes = 3_659_530_240L,
        peakRssBytes = 3283L * DW_TIER2_MIB,
        measuredOn = "Galaxy S26 Ultra — Google's published figure, not a reading from this fleet",
        survivesBackgrounding = null,
        unmeasuredLanguagesNote = "Google CLAIM 35+ languages out of the box and pre-training in " +
            "140+. Nothing has been checked against this app's dictation languages.",
    ),
)

fun dwTier2GpuClaimBytes(modelId: String): Long? = when (modelId) {
    "gemma-4-E2B-it.litertlm" -> 676L * DW_TIER2_MIB
    "gemma-4-E4B-it.litertlm" -> 710L * DW_TIER2_MIB
    else -> null
}

fun dwTier2Eligible(choice: DwModelChoice): Boolean =
    choice.fit.mayInstall &&
        DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE !in choice.notes &&
        DwFitNote.LITTLE_MEMORY_LEFT_OVER !in choice.notes

fun dwTier2InstallMayBeOffered(
    choice: DwModelChoice,
    connection: DwConnection,
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
): Boolean = runtimePresent && dwTier2Eligible(choice) && dwModelDownloadMayBeOffered(choice, connection)

const val DW_TIER2_LIST_INTRO: String =
    "Language models for tidying and translating text. Nothing downloads unless you ask."

fun dwTier2RowSentence(choice: DwModelChoice, measurement: DwDeviceMeasurement): String = buildString {
    val plan = choice.plan
    append(dwBytesLabel(plan.onDiskBytes))
    append(" to download. It needs about ")
    append(dwBytesLabel(plan.peakRssBytes))
    append(" of memory while it runs (Google's figure, Galaxy S26 Ultra).")
    dwTier2GpuClaimBytes(plan.modelId)?.let { gpu ->
        append(" Google also publish ")
        append(dwBytesLabel(gpu))
        append(" on GPU; the larger figure is used.")
    }
    append(" ")
    append(dwTier2FitClause(choice, measurement))
}

fun dwTier2FitClause(choice: DwModelChoice, measurement: DwDeviceMeasurement): String = when (choice.fit) {
    DwModelFit.COMFORTABLE -> buildString {
        append("This phone is comfortable with it")
        choice.freeRamHeadroomBytes?.let {
            append(" — ")
            append(dwBytesLabel(it))
            append(" of memory would be spare")
        }
        append(".")
    }

    DwModelFit.TIGHT -> buildString {
        append("Tight on this phone: ")
        append(choice.notes.joinToString(" ") { dwTier2NoteClause(it, choice, measurement) })
        append(" It would still run; the choice is yours.")
    }

    DwModelFit.WILL_NOT_FIT -> buildString {
        append("It cannot run on this phone: ")
        append(choice.notes.joinToString(" ") { dwTier2NoteClause(it, choice, measurement) })
    }

    DwModelFit.UNMEASURED -> buildString {
        append(choice.notes.joinToString(" ") { dwTier2NoteClause(it, choice, measurement) })
        append(" Tap “Check again”.")
    }
}

private fun dwTier2NoteClause(
    note: DwFitNote,
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
): String = when (note) {
    DwFitNote.LARGER_THAN_THIS_PHONE_S_MEMORY ->
        "needs more memory than this phone has (${dwBytesLabel(measurement.totalRamBytes)})."

    DwFitNote.LARGER_THAN_THE_FREE_STORAGE ->
        "larger than the free storage."

    DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR ->
        "not built for this processor (needs ${choice.plan.abi})."

    DwFitNote.LOAD_FAILED_HERE_BEFORE ->
        "failed to load here before."

    DwFitNote.PROCESSOR_UNMEASURED -> "this phone would not say what processor it has."
    DwFitNote.TOTAL_MEMORY_UNMEASURED -> "this phone would not say how much memory it has."
    DwFitNote.FREE_STORAGE_UNMEASURED -> "free storage unknown."
    DwFitNote.FREE_MEMORY_UNMEASURED -> "this phone would not say how much memory is free."

    DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE ->
        "Android marks this as a low-memory phone."

    DwFitNote.LITTLE_MEMORY_LEFT_OVER ->
        "only ${dwBytesLabel(measurement.totalRamBytes)} memory in total — too little to spare."

    DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW ->
        "only ${dwBytesLabel(measurement.availableRamBytes)} free now; close apps to free more."

    DwFitNote.LITTLE_STORAGE_LEFT_OVER ->
        "would leave only ${dwBytesLabel(choice.freeStorageHeadroomBytes)} free (below ${dwBytesLabel(DW_MODEL_FREE_STORAGE_MARGIN_BYTES)})."
}

enum class DwTier2FileVerdict {
    VERIFIED,

    ABSENT,

    WRONG_SIZE,

    WRONG_DIGEST,

    NOT_PINNED,
}

fun dwTier2VerifyFile(
    modelId: String,
    actualBytes: Long?,
    actualSha256: String?,
    catalogue: List<DwTier2Artifact> = DW_TIER2_ARTIFACTS,
): DwTier2FileVerdict {
    val pinned = dwTier2ArtifactFor(modelId, catalogue) ?: return DwTier2FileVerdict.NOT_PINNED
    if (actualBytes == null) return DwTier2FileVerdict.ABSENT
    if (actualBytes != pinned.bytes) return DwTier2FileVerdict.WRONG_SIZE
    if (actualSha256.isNullOrBlank()) return DwTier2FileVerdict.WRONG_DIGEST
    return if (actualSha256.equals(pinned.sha256, ignoreCase = true)) {
        DwTier2FileVerdict.VERIFIED
    } else {
        DwTier2FileVerdict.WRONG_DIGEST
    }
}
