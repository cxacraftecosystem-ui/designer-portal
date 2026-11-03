package com.designprototype.workshop.data

const val DW_TIER2_DIR: String = "dwtier2"

const val DW_TIER2_MAX_PASSAGE_CHARS: Int = 1_500

enum class DwTier2ModelState {
    UNKNOWN,

    NOT_INSTALLED,

    DOWNLOADING,

    PAUSED,

    VERIFYING,

    INSTALLED,

    FAILED,
}

data class DwTier2ModelStatus(
    val state: DwTier2ModelState = DwTier2ModelState.UNKNOWN,
    val partialBytes: Long = 0L,
    val failure: String? = null,
)

enum class DwTier2InstallOffer {
    DOWNLOAD,

    RESUME,

    RETRY,

    NO_CONNECTION,

    IN_PROGRESS,

    INSTALLED,

    NOT_OFFERED,

    CHECKING,
}

fun dwTier2InstallOffer(
    choice: DwModelChoice,
    status: DwTier2ModelStatus,
    connection: DwConnection,
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
): DwTier2InstallOffer = when (status.state) {
    DwTier2ModelState.UNKNOWN -> DwTier2InstallOffer.CHECKING
    DwTier2ModelState.INSTALLED -> DwTier2InstallOffer.INSTALLED
    DwTier2ModelState.DOWNLOADING, DwTier2ModelState.VERIFYING -> DwTier2InstallOffer.IN_PROGRESS
    else -> when {
        !runtimePresent || !dwTier2Eligible(choice) -> DwTier2InstallOffer.NOT_OFFERED
        connection == DwConnection.NONE -> DwTier2InstallOffer.NO_CONNECTION
        status.state == DwTier2ModelState.PAUSED && status.partialBytes > 0L -> DwTier2InstallOffer.RESUME
        status.state == DwTier2ModelState.FAILED -> DwTier2InstallOffer.RETRY
        else -> {
            if (dwTier2InstallMayBeOffered(choice, connection, runtimePresent)) {
                DwTier2InstallOffer.DOWNLOAD
            } else {
                DwTier2InstallOffer.NOT_OFFERED
            }
        }
    }
}

fun dwTier2Visible(
    choices: List<DwModelChoice>,
    statuses: Map<String, DwTier2ModelStatus>,
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
): Boolean = runtimePresent && choices.any { choice ->
    dwTier2Eligible(choice) || statuses[choice.plan.modelId]?.state.let {
        it == DwTier2ModelState.INSTALLED || it == DwTier2ModelState.PAUSED
    }
}

fun dwTier2ModelToRun(
    installed: Set<String>,
    failures: List<DwLoadFailureNote>,
    catalogue: List<DwModelPlan> = DW_TIER2_PLANS,
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
): DwModelPlan? {
    if (!runtimePresent) return null
    return catalogue
        .filter { it.modelId in installed }
        .filterNot { plan ->
            failures.any {
                it.tier == DwAiTier.TIER_2 && it.modelId == plan.modelId &&
                    it.contextCapTokens == plan.contextCapTokens
            }
        }
        .minByOrNull { it.peakRssBytes }
}

val DW_TIER2_ON_DEVICE_VERBS: Set<DwTier2Verb> = setOf(DwTier2Verb.PROOFREAD, DwTier2Verb.TRANSLATION)

enum class DwTier2RunRefusal {
    NO_MODEL,

    VERB_NOT_ON_DEVICE,

    PASSAGE_TOO_LONG,

    NOT_NOW,
}

fun dwTier2RunRefusal(
    verb: DwTier2Verb,
    passageChars: Int,
    model: DwModelPlan?,
    capturing: Boolean,
    thermal: DwThermalState,
): DwTier2RunRefusal? {
    if (verb !in DW_TIER2_ON_DEVICE_VERBS) return DwTier2RunRefusal.VERB_NOT_ON_DEVICE
    if (model == null) return DwTier2RunRefusal.NO_MODEL
    if (passageChars <= 0 || passageChars > DW_TIER2_MAX_PASSAGE_CHARS) {
        return DwTier2RunRefusal.PASSAGE_TOO_LONG
    }
    val window = dwTier2RunWindow(DwTierOffer.Available(model, 0L), capturing, thermal)
    return if (window == DwTier2Window.RUN_NOW) null else DwTier2RunRefusal.NOT_NOW
}

fun dwTier2SystemInstruction(verb: DwTier2Verb, targetLanguage: String? = null): String = when (verb) {
    DwTier2Verb.PROOFREAD ->
        "You proofread field notes from craft workshops. Correct spelling, grammar and punctuation " +
            "only. Keep the same language, the same words and the same order. Do not translate, " +
            "summarise, explain or add anything. Leave craft terms, names and local words exactly " +
            "as written. Reply with the corrected passage and nothing else."

    DwTier2Verb.TRANSLATION -> {
        require(!targetLanguage.isNullOrBlank()) { "A translation needs the language to write into." }
        "You translate field notes from craft workshops into ${targetLanguage.trim()}. Translate " +
            "the whole passage faithfully, sentence by sentence. Do not summarise, explain or add " +
            "anything. Keep names and craft terms as they are, transliterated where the script " +
            "requires it. Reply with the translation and nothing else."
    }

    DwTier2Verb.EXPANDED, DwTier2Verb.CAPTION ->
        throw IllegalArgumentException("${verb.name} is not run on this phone.")
}

fun dwTier2OutputText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

fun dwTier2Provenance(model: DwModelPlan, language: String, producedAtIso: String): DwTier2Provenance =
    DwTier2Provenance(
        provider = DW_TIER2_PROVIDER,
        modelId = model.modelId,
        modelVersion = DW_TIER2_RUNTIME_VERSION,
        language = language,
        producedAtIso = producedAtIso,
    )

fun dwTier2RunLanguage(verb: DwTier2Verb, targetLanguage: String?, passageLanguage: String?): String =
    when (verb) {
        DwTier2Verb.TRANSLATION -> targetLanguage?.trim().orEmpty().ifBlank { "multi" }
        else -> passageLanguage?.trim().orEmpty().ifBlank { "multi" }
    }

fun dwTier2ActionLabel(offer: DwTier2InstallOffer): String? = when (offer) {
    DwTier2InstallOffer.DOWNLOAD -> "Download"
    DwTier2InstallOffer.RESUME -> "Resume"
    DwTier2InstallOffer.RETRY -> "Try again"
    DwTier2InstallOffer.INSTALLED -> "Remove"
    DwTier2InstallOffer.NO_CONNECTION, DwTier2InstallOffer.IN_PROGRESS,
    DwTier2InstallOffer.NOT_OFFERED, DwTier2InstallOffer.CHECKING -> null
}

fun dwTier2StateLine(offer: DwTier2InstallOffer, status: DwTier2ModelStatus): String? = when (offer) {
    DwTier2InstallOffer.INSTALLED ->
        "On this phone. Proofreading and translation run locally."
    DwTier2InstallOffer.NO_CONNECTION -> "Connect to download it."
    DwTier2InstallOffer.RESUME -> dwPausedSentence(status.partialBytes)
    DwTier2InstallOffer.RETRY -> status.failure
    else -> null
}

const val DW_TIER2_MISMATCH_SENTENCE: String =
    "Download didn't verify, so it was deleted. Try again."

const val DW_TIER2_WORKING_SENTENCE: String = "Working on the passage on this phone…"

fun dwTier2VerbNote(model: DwModelPlan): String =
    "Runs on this phone with ${model.modelId}. Nothing leaves the phone; no allowance used."

fun dwTier2TierLine(offer: DwTierOffer, installed: Set<String>, catalogue: List<DwModelPlan> = DW_TIER2_PLANS): String? {
    val running = catalogue.filter { it.modelId in installed }.minByOrNull { it.peakRssBytes }
    if (running != null) {
        return "On-device AI: ${running.modelId}."
    }
    return if (offer is DwTierOffer.Available) dwTierOfferSentence(DwAiTier.TIER_2, offer) else null
}
