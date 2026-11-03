package com.designprototype.workshop.data

import java.util.Locale

enum class DwAsrModelFamily {
    OMNILINGUAL_ASR_CTC,

    NEMO_ENC_DEC_CTC,
}

data class DwAsrModelHead(
    val languageTag: String?,
    val graphFileName: String,
    val tokensFileName: String,
) {
    init {
        require(languageTag == null || languageTag.isNotBlank()) {
            "A head's language tag is a real tag or null. Null means “this one head answers for " +
                "every language the model serves”; a blank string is neither and would match nothing " +
                "while looking like an answer."
        }
        listOf(graphFileName, tokensFileName).forEach { name ->
            require(name.isNotBlank() && '/' !in name && '\\' !in name && ".." !in name) {
                "A head names files inside this app's own internal storage, so “$name” has to be a " +
                    "bare file name. The check is [DwAsrModelFile]'s and it is here for the same " +
                    "reason: a row copied carelessly from somewhere could otherwise address " +
                    "../databases/workshop.db."
            }
        }
        require(graphFileName != tokensFileName) {
            "A head's graph and vocabulary are two different files. One name for both means whichever " +
                "the loader opened second would be parsed as the wrong format."
        }
    }
}

data class DwAsrModelFile(
    val fileName: String,
    val sha256: String,
    val bytes: Long,
) {
    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && ".." !in fileName) {
            "A model file needs a bare file name: “$fileName” is not one. These names address files " +
                "inside this app's own internal storage, and an entry like ../databases/workshop.db " +
                "would put fetched bytes over this app's own database."
        }
        require(dwAsrIsSha256(sha256)) {
            "Every model file needs the full 64-character hex SHA-256 of the exact file that will " +
                "sit on the phone. Take it with `sha256sum` on the file you published, not on the " +
                "one you built from — and never leave it blank, because dwAsrVerify answers " +
                "NO_PINNED_DIGEST for a blank and that refusal is the guard, not a nuisance."
        }
        require(bytes > 0L) {
            "Every model file needs its real size on disk. The figures are added up to decide " +
                "whether the model fits with a workshop day's photographs left over, and a zero " +
                "would let an install be offered on a phone with no room for it."
        }
    }
}

data class DwAsrModel(
    val modelId: String,
    val quantisation: String,
    val family: DwAsrModelFamily,
    val files: List<DwAsrModelFile>,
    val heads: List<DwAsrModelHead>,
    val upstreamVersion: String,
    val provenance: String,
    val languageNote: String,
) {
    val onDiskBytes: Long get() = files.sumOf { it.bytes }

    fun headFor(tag: String): DwAsrModelHead? =
        heads.firstOrNull { it.languageTag != null && dwTagCovers(it.languageTag, tag) }
            ?: heads.firstOrNull { it.languageTag == null }

    init {
        require(modelId.isNotBlank() && '/' !in modelId && '\\' !in modelId && ".." !in modelId) {
            "A model needs an id that is also a safe directory name — it names a directory inside " +
                "this app's internal storage. “$modelId” is not one."
        }
        require(quantisation.isNotBlank()) {
            "A model needs its quantisation. int8 and fp32 of the same weights are different " +
                "artifacts with different footprints, and this one is 365 MB against 1.2 GB."
        }
        require(files.isNotEmpty()) {
            "A model needs the files it is made of, each with its own digest. A graph with no " +
                "vocabulary decodes to confident nonsense rather than to an error, so both are pinned."
        }
        require(files.map { it.fileName }.distinct().size == files.size) {
            "Two files in one model cannot share a name: they would be written to the same path, " +
                "one would silently replace the other, and the digest check would then fail on " +
                "whichever lost — reporting a substituted file where the fault is a duplicated row."
        }
        require(upstreamVersion.isNotBlank()) {
            "A model needs the upstream release it came out of, so a defect found later can be " +
                "traced to a version rather than to “the model”."
        }
        require(provenance.isNotBlank()) {
            "A model needs a sentence saying how the release builder obtained it. The digest proves " +
                "only that the file is the one they pinned; this is the only record of what they " +
                "believed they were pinning."
        }
        require(languageNote.isNotBlank()) {
            "A model needs its language note. A model is not a capability, it is a capability in " +
                "some languages, and the one thing this app must never do is let “offline " +
                "dictation” imply nineteen when the artifact serves fewer."
        }
        require(heads.isNotEmpty()) {
            "A model needs at least one head — the graph and the vocabulary that get opened. A " +
                "single-head export takes one row with languageTag = null; a model whose languages " +
                "are separate sliced heads takes one row per language."
        }
        require(heads.count { it.languageTag == null } <= 1) {
            "A model has at most ONE catch-all head. Two rows both claiming to answer for every " +
                "language means dwAsrModel.headFor returns whichever was written first, and which " +
                "graph a designer's dictation opened would be decided by the order of two lines."
        }
        require(
            heads.mapNotNull { it.languageTag }.map { dwNormalizeLanguageTag(it) }.distinct().size ==
                heads.count { it.languageTag != null }
        ) {
            "Each language gets one head. Two heads for one tag (or-IN and or_IN are the same tag to " +
                "every comparison in this app) would make the graph a designer decodes with a coin toss."
        }
        val pinned = files.map { it.fileName }.toSet()
        heads.forEach { head ->
            require(head.graphFileName in pinned) {
                "The head for ${head.languageTag ?: "every language"} opens " +
                    "“${head.graphFileName}”, which is not one of this model's pinned files. Add it " +
                    "to `files` with its own digest and size: a file the loader opens and the " +
                    "verifier never hashed is the one hole this whole arrangement exists to close."
            }
            require(head.tokensFileName in pinned) {
                "The head for ${head.languageTag ?: "every language"} reads " +
                    "“${head.tokensFileName}”, which is not one of this model's pinned files. A " +
                    "substituted vocabulary against a correct graph produces a fluent transcript in " +
                    "the wrong alphabet rather than an error, and nobody would check it against the " +
                    "audio."
            }
        }
    }
}

val DW_ASR_MODELS: List<DwAsrModel> = listOf(
    DwAsrModel(
        modelId = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        quantisation = "int8",
        family = DwAsrModelFamily.OMNILINGUAL_ASR_CTC,
        files = listOf(
            DwAsrModelFile(
                fileName = "model.int8.onnx",
                sha256 = "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c",
                bytes = 365_352_120L,
            ),
            DwAsrModelFile(
                fileName = "tokens.txt",
                sha256 = "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31",
                bytes = 86_423L,
            ),
        ),
        heads = listOf(
            DwAsrModelHead(
                languageTag = null,
                graphFileName = "model.int8.onnx",
                tokensFileName = "tokens.txt",
            ),
        ),
        upstreamVersion = "sherpa-onnx asr-models, asset " +
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        provenance = "Downloaded 2026-08-12 from the k2-fsa/sherpa-onnx GitHub release tag " +
            "`asr-models` — the same project and the same release index the engine AAR comes from — " +
            "and unpacked locally. The tarball was 292,571,207 bytes and hashed to " +
            "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed. It is sherpa-onnx's " +
            "own ONNX export of Meta's Omnilingual ASR CTC 300M; it was NOT re-quantised, re-exported " +
            "or repacked here. Nothing upstream publishes a signature for it, so this chain " +
            "establishes what was pinned and not who made it.",
        languageNote = "Meta CLAIM 1,600+ languages for this family, which is not verified here and " +
            "is not a property of this artifact. What was measured off this artifact's own " +
            "tokens.txt: 9,812 tokens, of which 70 carry Odia-script characters (U+0B00–U+0B7F), 87 " +
            "Devanagari, 72 Bengali, 73 Malayalam, 73 Gujarati, 66 Telugu, 62 Kannada, 55 Gurmukhi, " +
            "49 Tamil, 155 Arabic, 32 Ol Chiki and ZERO Meetei Mayek — so the model is ABLE to write " +
            "those scripts, which is a necessary condition for serving those languages and not a " +
            "sufficient one. Measured 2026-08-13, that stopped being a caution and became a reading: " +
            "handed Urdu speech this artifact writes DEVANAGARI and not one Arabic character, at 100% " +
            "WER, so the 155 Arabic tokens buy Urdu nothing. The zero works the other way and is the " +
            "one case where the vocabulary settles a language on its own: Manipuri in its own script " +
            "cannot be produced at all. What it can actually hear was measured on a handset for eleven " +
            "of this app's nineteen languages and is recorded in DwModelPlan.languages, " +
            "DwModelPlan.accuracy and docs/DEVICE-TIER-MEASUREMENT.md; nothing else in this app may " +
            "fill that list.",
    ),
)

val DW_ASR_ENGINE_ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a")

fun dwAsrEngineAbiFor(abis: List<String>): String? {
    abis.forEach { reported ->
        val normalised = reported.trim().lowercase(Locale.ROOT)
        if (DW_ASR_ENGINE_ABIS.any { it.equals(normalised, ignoreCase = true) }) return normalised
    }
    return null
}

fun dwAsrModel(catalogue: List<DwAsrModel> = DW_ASR_MODELS): DwAsrModel? = catalogue.firstOrNull()
