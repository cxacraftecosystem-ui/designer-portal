package com.designprototype.workshop.data

/**
 * **TIER 2 ON THIS PHONE: WHICH MODEL IS INSTALLED, WHAT MAY BE FETCHED, AND WHEN A VERB MAY RUN ON IT.**
 *
 * The pure half of the on-device language model, so the desktop JVM tests every rule
 * (`DwTier2InstallTest`). The Android halves are `DwTier2Engine.kt` (LiteRT-LM, the runtime in the
 * APK) and `ui/designworkshop/DwTier2ModelInstallUi.kt` (the download, pause, cancel and verify loop,
 * which follows the speech model's controller).
 *
 * ── THE RULES, EACH ONE FROM THE DESIGN AND NOT INVENTED HERE ─────────────────────────────────
 *
 *  * **Recommend; never auto-download** (plan §2.1). A fetch starts on a designer's tap and on nothing
 *    else, with the size shown before the tap. [dwTier2InstallOffer] decides only whether a control
 *    exists.
 *  * **The eligibility is the measured device tier, not a second set of rules.** A row may be
 *    offered when `dwModelFit` says it may be installed ([DwModelFit.mayInstall]) AND the runtime is
 *    in the package for this processor — [dwTier2InstallMayBeOffered], which ANDs
 *    [DW_TIER2_RUNTIME_PRESENT] in. A phone that cannot run a row is simply not offered it.
 *  * **One verify for both routes.** A file that arrived over the wire and one an adb cable left in
 *    the staging directory pass through the same [dwTier2VerifyFile]: right size AND right SHA-256,
 *    or the file is deleted and never opened.
 *  * **Tier 2 never runs beside capture, and never on a phone that is already hot** —
 *    [dwTier2RunWindow], which existed before this file and is now called.
 *  * **A load that fails is data** — recorded as a [DwLoadFailureNote], never tried again on this
 *    handset, and the verb falls back to the cloud path that has always existed
 *    ([dwFallbackAfterLoadFailure]).
 */

/** The directory inside `filesDir` the verified models live in, and the staging directory's name. */
const val DW_TIER2_DIR: String = "dwtier2"

/**
 * The longest passage handed to the model on the phone, in characters.
 *
 * Every memory figure behind the verdict was taken at a 2,048-token context with 1,024 tokens in and
 * 256 out ([DW_TIER2_MEASURED_CONTEXT_TOKENS]). Indic scripts tokenise at well under one character
 * per token, so 1,500 characters plus the instruction stays inside that envelope; a longer paragraph
 * goes to the cloud verb exactly as it did before Tier 2 existed.
 */
const val DW_TIER2_MAX_PASSAGE_CHARS: Int = 1_500

/** What one model is doing on this phone. Starts UNKNOWN — never "not installed" before a look. */
enum class DwTier2ModelState {
    /** Nobody has looked at the disk yet. Never rendered as "not installed". */
    UNKNOWN,

    /** Not on the phone. */
    NOT_INSTALLED,

    /** Bytes are moving. */
    DOWNLOADING,

    /** A designer paused it; the part-file is kept and the next tap resumes from it. */
    PAUSED,

    /** The file is complete and being hashed. */
    VERIFYING,

    /** On the phone, right size, right SHA-256. The only state a load may follow. */
    INSTALLED,

    /** The last attempt failed. [DwTier2ModelStatus.failure] says why. */
    FAILED,
}

/** One model's state, with the figures the row prints. */
data class DwTier2ModelStatus(
    val state: DwTier2ModelState = DwTier2ModelState.UNKNOWN,
    /** Bytes in the part-file, so a paused download says how much it has kept. */
    val partialBytes: Long = 0L,
    /** The sentence for a [DwTier2ModelState.FAILED], or null. */
    val failure: String? = null,
)

/** What a row's control is, for one model on this phone right now. */
enum class DwTier2InstallOffer {
    /** Nothing on the phone and it may be fetched. "Download". */
    DOWNLOAD,

    /** A paused part-file exists. "Resume" — the bytes already paid for are kept. */
    RESUME,

    /** The last attempt failed. "Try again". */
    RETRY,

    /** It may be fetched, but there is no connection. No button; a short line instead. */
    NO_CONNECTION,

    /** A fetch or a hash is running. Pause and Cancel instead of Download. */
    IN_PROGRESS,

    /** Installed and verified. "Remove" is the only control. */
    INSTALLED,

    /** Not for this phone — the measured reading or the processor rules it out. No row at all. */
    NOT_OFFERED,

    /** The disk has not been read yet. Nothing is drawn rather than a guess. */
    CHECKING,
}

/**
 * The control for one row. **The eligibility half is [dwTier2InstallMayBeOffered] and nothing else.**
 *
 * The order is the speech model's: what is on the disk outranks every gate (an installed or a paused
 * model is never hidden because free memory moved), then whether this phone may take it at all, then
 * the connection.
 */
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
            // The same gate the design names for a Tier 2 fetch, asked once more so the two cannot
            // come to disagree: [dwTier2InstallMayBeOffered] is fit AND runtime AND connection.
            if (dwTier2InstallMayBeOffered(choice, connection, runtimePresent)) {
                DwTier2InstallOffer.DOWNLOAD
            } else {
                DwTier2InstallOffer.NOT_OFFERED
            }
        }
    }
}

/**
 * Whether the Tier 2 card has anything to show on this phone. **False means the card says nothing.**
 *
 * A row is shown when it may be installed here, or when it already is (a model installed on a calmer
 * day is not hidden because free memory moved). A phone where neither is true for any row gets no
 * Tier 2 line and no list — not a sentence about what it cannot do.
 */
fun dwTier2Visible(
    choices: List<DwModelChoice>,
    statuses: Map<String, DwTier2ModelStatus>,
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
): Boolean = runtimePresent && choices.any { choice ->
    dwTier2Eligible(choice) || statuses[choice.plan.modelId]?.state.let {
        it == DwTier2ModelState.INSTALLED || it == DwTier2ModelState.PAUSED
    }
}

/**
 * The model a verb runs on, or null when none may run. **Installed, verified, and never one that
 * failed to load on this handset.**
 *
 * The smallest installed model is chosen, by the same peak figure every verdict is computed from:
 * the job that finishes is worth more than the larger model that the low-memory killer ends halfway.
 */
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

/** The two verbs a designer can run on the phone today. The design's "ship first" pair. */
val DW_TIER2_ON_DEVICE_VERBS: Set<DwTier2Verb> = setOf(DwTier2Verb.PROOFREAD, DwTier2Verb.TRANSLATION)

/** Why a verb will not run on the phone right now, or null when it may. Each falls back to the cloud. */
enum class DwTier2RunRefusal {
    /** No verified model on this phone, or the only one failed to load here. */
    NO_MODEL,

    /** The verb is not one of [DW_TIER2_ON_DEVICE_VERBS]. */
    VERB_NOT_ON_DEVICE,

    /** Longer than [DW_TIER2_MAX_PASSAGE_CHARS]. */
    PASSAGE_TOO_LONG,

    /** A recording or the camera is open, or the phone is hot — [dwTier2RunWindow]. */
    NOT_NOW,
}

/**
 * Whether [verb] may run on the phone for a passage of [passageChars] characters. Null means yes.
 *
 * [dwTier2RunWindow] is the design's own predicate — "the only function allowed to say yes" — and it
 * is asked here with an [DwTierOffer.Available] built from the model that would run, so the capture
 * bar and the thermal bar are its rules and not a copy of them.
 */
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

// ---------------------------------------------------------------------------------------------
// The prompts — the device's copy of the cloud verbs' promises
// ---------------------------------------------------------------------------------------------

/**
 * The instruction a verb is run under. **The same promises the cloud verbs make**, so a Tier 2 layer
 * and a Tier 3 layer of the same kind mean the same thing under the same heading in a report:
 * a proofread changes spelling, grammar and punctuation and nothing else, in the same language; a
 * translation is a sibling of the original, never a summary of it. Craft words are left alone,
 * because a general model "corrects" dabu into double.
 */
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

/**
 * What the model wrote, as it is recorded. **Trimmed of surrounding whitespace and nothing else** —
 * `DwTier2Draft.text`'s rule is that this app does not tidy model output before recording it. Null
 * when nothing is left, which is a failed run rather than an empty layer.
 */
fun dwTier2OutputText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The provenance an on-device run records. [producedAtIso] is when the model finished, taken by the
 * caller off the phone's clock with its offset.
 */
fun dwTier2Provenance(model: DwModelPlan, language: String, producedAtIso: String): DwTier2Provenance =
    DwTier2Provenance(
        provider = DW_TIER2_PROVIDER,
        modelId = model.modelId,
        modelVersion = DW_TIER2_RUNTIME_VERSION,
        language = language,
        producedAtIso = producedAtIso,
    )

/**
 * The language a run records. A translation records what it wrote INTO; a proofread records the
 * language the passage was dictated in when the field knows it, and `multi` when it does not —
 * a workshop is code-switched Hindi and English mid-sentence, and `multi` is the honest answer.
 */
fun dwTier2RunLanguage(verb: DwTier2Verb, targetLanguage: String?, passageLanguage: String?): String =
    when (verb) {
        DwTier2Verb.TRANSLATION -> targetLanguage?.trim().orEmpty().ifBlank { "multi" }
        else -> passageLanguage?.trim().orEmpty().ifBlank { "multi" }
    }

// ---------------------------------------------------------------------------------------------
// The words. Terse, and none of them about what the phone cannot do
// ---------------------------------------------------------------------------------------------

/** The button label for an offer, or null when the offer draws no button. */
fun dwTier2ActionLabel(offer: DwTier2InstallOffer): String? = when (offer) {
    DwTier2InstallOffer.DOWNLOAD -> "Download"
    DwTier2InstallOffer.RESUME -> "Resume"
    DwTier2InstallOffer.RETRY -> "Try again"
    DwTier2InstallOffer.INSTALLED -> "Remove"
    DwTier2InstallOffer.NO_CONNECTION, DwTier2InstallOffer.IN_PROGRESS,
    DwTier2InstallOffer.NOT_OFFERED, DwTier2InstallOffer.CHECKING -> null
}

/** One short line under a row for the states that are not a button. Null when there is nothing. */
fun dwTier2StateLine(offer: DwTier2InstallOffer, status: DwTier2ModelStatus): String? = when (offer) {
    DwTier2InstallOffer.INSTALLED ->
        "On this phone. Proofreading and translation run here, without an outside AI service."
    DwTier2InstallOffer.NO_CONNECTION -> "Connect to download it."
    DwTier2InstallOffer.RESUME -> dwPausedSentence(status.partialBytes)
    DwTier2InstallOffer.RETRY -> status.failure
    else -> null
}

/** Said when a downloaded file does not match the pinned fingerprint. It has been deleted. */
const val DW_TIER2_MISMATCH_SENTENCE: String =
    "The download did not match the expected file, so it was deleted and nothing was opened. Try again."

/** The heading on the AI verbs card while a passage is being worked on, on the phone. */
const val DW_TIER2_WORKING_SENTENCE: String = "Working on the passage on this phone…"

/** The note under the verb choices when they will run on the phone. */
fun dwTier2VerbNote(model: DwModelPlan): String =
    "Runs on this phone with ${model.modelId}. The passage does not go to an outside AI service, " +
        "and nothing counts against the daily allowance."

/**
 * The Tier 2 line on the "AI on this phone" card, or null for no line at all.
 *
 * **NO REFUSAL IS EVER SAID FOR TIER 2.** A phone with a verified model is told what runs on it; a
 * phone the arithmetic suggests a model for is given the offer; every other phone gets nothing — the
 * list beneath shows what it may still choose, and a phone that may choose nothing shows no list.
 */
fun dwTier2TierLine(offer: DwTierOffer, installed: Set<String>, catalogue: List<DwModelPlan> = DW_TIER2_PLANS): String? {
    val running = catalogue.filter { it.modelId in installed }.minByOrNull { it.peakRssBytes }
    if (running != null) {
        return "Tier ${DwAiTier.TIER_2.number} runs ${running.modelId} on this phone, for proofreading " +
            "and translating what is already written."
    }
    return if (offer is DwTierOffer.Available) dwTierOfferSentence(DwAiTier.TIER_2, offer) else null
}
